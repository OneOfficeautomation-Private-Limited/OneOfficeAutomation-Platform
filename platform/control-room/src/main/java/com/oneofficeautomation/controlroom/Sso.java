package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;

import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static com.oneofficeautomation.controlroom.ControlRoom.*;

/**
 * Single sign-on with OpenID Connect (authorization code flow with PKCE). Works with any standards-based identity
 * provider (Microsoft Entra ID, Okta, Google, Keycloak, Auth0, ADFS). The ID token signature is checked against the
 * provider's published keys (RS256/384/512, ES256), plus issuer, audience, expiry and nonce.
 *
 * Settings: sso_issuer, sso_client_id, sso_client_secret_ref (a vault reference folder/credential/attribute),
 * sso_default_role (for new users; empty = only existing users may sign in), sso_label (button text).
 */
final class Sso {
    private final ControlRoom cr;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
    // state -> verifier and nonce, for 10 minutes, in the store all nodes share
    private volatile Map<String, Object> discovery;
    private volatile String discoveryFor;
    private volatile Map<String, Object> jwks;
    private volatile long jwksAt;

    Sso(ControlRoom cr) { this.cr = cr; }

    boolean enabled() {
        return !String.valueOf(cr.platform.setting("sso_issuer")).isBlank() && !String.valueOf(cr.platform.setting("sso_client_id")).isBlank();
    }

    Map<String, Object> info() {
        String label = String.valueOf(cr.platform.setting("sso_label"));
        return Map.of("enabled", enabled(), "label", label.isBlank() ? "Sign in with your organisation account" : label);
    }

    private static String b64u(byte[] b) { return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getJson(String url) {
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() >= 300) throw new OoaException("SSO.PROVIDER", "The identity provider answered HTTP " + r.statusCode() + " for " + url);
            return (Map<String, Object>) JsonLite.parse(r.body());
        } catch (java.io.IOException e) {
            throw new OoaException("SSO.PROVIDER", "The identity provider could not be reached: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OoaException("SSO.PROVIDER", "Interrupted");
        }
    }

    private Map<String, Object> discovery() {
        String issuer = String.valueOf(cr.platform.setting("sso_issuer")).trim().replaceAll("/+$", "");
        if (discovery == null || !issuer.equals(discoveryFor)) {
            discovery = getJson(issuer + "/.well-known/openid-configuration");
            discoveryFor = issuer;
            jwks = null;
        }
        return discovery;
    }

    /** Builds the provider's sign-in URL and remembers the PKCE verifier and nonce for the state. */
    String start(String redirectUri) {
        if (!enabled()) throw new OoaException("SSO.DISABLED", "Single sign-on is not set up", "An administrator sets it up in Administration > Settings.");
        SecureRandom rnd = new SecureRandom();
        byte[] v = new byte[32], s = new byte[24], n = new byte[24];
        rnd.nextBytes(v); rnd.nextBytes(s); rnd.nextBytes(n);
        String verifier = b64u(v), state = b64u(s), nonce = b64u(n);
        cr.cluster.put("oidc", state, verifier + "|" + nonce, Duration.ofMinutes(10));
        String challenge;
        try { challenge = b64u(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        return discovery().get("authorization_endpoint") + "?response_type=code&scope=" + enc("openid email profile") + "&client_id=" + enc(String.valueOf(cr.platform.setting("sso_client_id")))
                + "&redirect_uri=" + enc(redirectUri) + "&state=" + enc(state) + "&nonce=" + enc(nonce) + "&code_challenge=" + enc(challenge) + "&code_challenge_method=S256";
    }

    /** Exchanges the code, checks the ID token and signs the person in; returns the login result (token + user). */
    @SuppressWarnings("unchecked")
    Map<String, Object> callback(String code, String state, String redirectUri, String ip) {
        String held = cr.cluster.take("oidc", state);
        String[] p = held == null ? null : held.split("\\|", 2);
        if (p == null || code == null) throw new OoaException("SSO.BAD_STATE", "The sign-in link expired or was already used", "Start the sign-in again.");
        String clientId = String.valueOf(cr.platform.setting("sso_client_id"));
        String secretRef = String.valueOf(cr.platform.setting("sso_client_secret_ref")).trim();
        String form = "grant_type=authorization_code&code=" + enc(code) + "&redirect_uri=" + enc(redirectUri) + "&client_id=" + enc(clientId) + "&code_verifier=" + enc(p[0]);
        if (!secretRef.isEmpty()) form += "&client_secret=" + enc(cr.systemCredential(secretRef));
        Map<String, Object> tok;
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(String.valueOf(discovery().get("token_endpoint")))).timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() >= 300) throw new OoaException("SSO.TOKEN", "The identity provider refused the sign-in (HTTP " + r.statusCode() + ")");
            tok = (Map<String, Object>) JsonLite.parse(r.body());
        } catch (java.io.IOException e) {
            throw new OoaException("SSO.PROVIDER", "The identity provider could not be reached: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OoaException("SSO.PROVIDER", "Interrupted");
        }
        Map<String, Object> claims = verify(String.valueOf(tok.get("id_token")), clientId, p[1]);
        return cr.ssoLogin(claims, String.valueOf(cr.platform.setting("sso_default_role")).trim(), ip);
    }

    /** Verifies an ID token and returns its claims. */
    @SuppressWarnings("unchecked")
    Map<String, Object> verify(String jwt, String clientId, String nonce) {
        String[] parts = jwt == null ? new String[0] : jwt.split("\\.");
        if (parts.length != 3) throw new OoaException("SSO.BAD_TOKEN", "The identity provider sent no valid ID token");
        Base64.Decoder d = Base64.getUrlDecoder();
        Map<String, Object> header = (Map<String, Object>) JsonLite.parse(new String(d.decode(parts[0]), StandardCharsets.UTF_8));
        Map<String, Object> claims = (Map<String, Object>) JsonLite.parse(new String(d.decode(parts[1]), StandardCharsets.UTF_8));
        String alg = String.valueOf(header.get("alg"));
        Map<String, Object> key = key(String.valueOf(header.get("kid")), alg);
        try {
            byte[] signed = (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII);
            byte[] sig = d.decode(parts[2]);
            boolean ok;
            if (alg.startsWith("RS")) {
                PublicKey pk = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(new BigInteger(1, d.decode((String) key.get("n"))), new BigInteger(1, d.decode((String) key.get("e")))));
                Signature s = Signature.getInstance("SHA" + alg.substring(2) + "withRSA");
                s.initVerify(pk); s.update(signed); ok = s.verify(sig);
            } else if (alg.equals("ES256")) {
                AlgorithmParameters ap = AlgorithmParameters.getInstance("EC"); ap.init(new ECGenParameterSpec("secp256r1"));
                ECParameterSpec spec = ap.getParameterSpec(ECParameterSpec.class);
                PublicKey pk = KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new java.security.spec.ECPoint(new BigInteger(1, d.decode((String) key.get("x"))),
                        new BigInteger(1, d.decode((String) key.get("y")))), spec));
                Signature s = Signature.getInstance("SHA256withECDSAinP1363Format");
                s.initVerify(pk); s.update(signed); ok = s.verify(sig);
            } else throw new OoaException("SSO.BAD_TOKEN", "Unsupported ID token algorithm " + alg);
            if (!ok) throw new OoaException("SSO.BAD_TOKEN", "The ID token signature is not valid");
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new OoaException("SSO.BAD_TOKEN", "The ID token could not be checked: " + e.getMessage());
        }
        String issuer = String.valueOf(discovery().get("issuer"));
        if (!issuer.equals(claims.get("iss"))) throw new OoaException("SSO.BAD_TOKEN", "The ID token comes from another issuer");
        Object aud = claims.get("aud");
        if (!(clientId.equals(aud) || (aud instanceof List<?> l && l.contains(clientId)))) throw new OoaException("SSO.BAD_TOKEN", "The ID token is for another application");
        long exp = claims.get("exp") instanceof Number n ? n.longValue() : 0;
        if (Instant.now().getEpochSecond() > exp + 60) throw new OoaException("SSO.BAD_TOKEN", "The ID token has expired");
        if (nonce != null && !nonce.equals(claims.get("nonce"))) throw new OoaException("SSO.BAD_TOKEN", "The ID token does not belong to this sign-in");
        return claims;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> key(String kid, String alg) {
        for (int attempt = 0; attempt < 2; attempt++) {
            if (jwks == null || System.currentTimeMillis() - jwksAt > 3_600_000 || attempt == 1) {
                jwks = getJson(String.valueOf(discovery().get("jwks_uri")));
                jwksAt = System.currentTimeMillis();
            }
            for (Object o : (List<Object>) jwks.getOrDefault("keys", List.of())) {
                Map<String, Object> k = (Map<String, Object>) o;
                if ((kid.equals("null") || kid.equals(k.get("kid"))) && (alg.startsWith("RS") ? "RSA".equals(k.get("kty")) : "EC".equals(k.get("kty")))) return k;
            }
        }
        throw new OoaException("SSO.BAD_TOKEN", "The identity provider's signing key " + kid + " was not found");
    }
}
