package com.oneofficeautomation.runner;

import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;
import com.oneofficeautomation.sdk.SecureString;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * A saved connection that signs in by itself (A1, command review batch 7). Microsoft 365, Gmail and IMAP with OAuth
 * take an access token, which lasts about an hour; a connection with a sign-in gets one when a step needs it and
 * renews it a minute before it runs out, so an unattended agent keeps working.
 *
 * <p>The sign-in lives in the connection's own settings and vault references (no new storage):
 * <ul>
 *   <li>values: sign_in_provider (microsoft, google or other), sign_in_grant (client_credentials, the app's own
 *       sign-in for unattended agents; or refresh_token, one person's sign-in), sign_in_tenant (Microsoft),
 *       sign_in_client_id, sign_in_scope (optional), sign_in_token_url (other servers, or to point elsewhere)</li>
 *   <li>credentials (vault references): sign_in_client_secret, sign_in_refresh_token, sign_in_private_key</li>
 * </ul>
 * K1 (command review, batch 10) adds the sign-ins unattended agents of other services need: jwt_bearer (a Google
 * service account, optionally as a person through sign_in_subject; DocuSign's JWT grant), private_key_jwt client
 * authentication (sign_in_client_auth, for Okta service apps), github_app (sign_in_installation_id), snowflake_key_pair
 * (sign_in_account, sign_in_user: the signed JWT is the token) and sign_in_extra_fields (Box's box_subject_type and
 * box_subject_id).
 * Steps that pass their own access token keep it; the sign-in fills only an empty access_token.
 */
public final class OAuthSignIn {
    private OAuthSignIn() {}

    /** Renew this long before the server's expiry, so a token never runs out in the middle of a step. */
    static final long SKEW_MILLIS = 60_000;

    record Token(String access, long expiresAtMillis) {
        boolean fresh(long now) { return expiresAtMillis == 0 || now < expiresAtMillis - SKEW_MILLIS; }
    }

    /** Tokens per connection and sign-in (secrets only by hash). Shared by all runs in this process. */
    static final Map<String, Token> TOKENS = new ConcurrentHashMap<>();
    /** Refresh tokens the server replaced (Microsoft gives a new one with each token), newest first used. */
    static final Map<String, String> ROTATED = new ConcurrentHashMap<>();

    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

    /** True when the connection's settings hold a sign-in. */
    public static boolean configured(Map<String, Object> values) {
        Object p = values == null ? null : values.get("sign_in_provider");
        return p instanceof String s && !s.isBlank() && !s.equalsIgnoreCase("none");
    }

    /**
     * A valid access token for the connection: from the cache while fresh, otherwise asked for again.
     * @param secret reads a vault reference (folder/credential/attribute) as the person the run belongs to
     */
    /** The sign-in kinds (sign_in_grant). */
    static final Set<String> GRANTS = Set.of("client_credentials", "refresh_token", "jwt_bearer", "github_app", "snowflake_key_pair");

    public static SecureString token(String connection, Map<String, Object> values, Map<String, Object> credentials, Function<String, SecureString> secret) {
        String provider = text(values, "sign_in_provider").toLowerCase(Locale.ROOT);
        String grant = text(values, "sign_in_grant").isEmpty() ? defaultGrant(provider) : text(values, "sign_in_grant").toLowerCase(Locale.ROOT);
        if (!GRANTS.contains(grant))
            throw fail(connection, "unknown sign-in kind '" + grant + "'", "Use client_credentials (the app signs in), refresh_token (one person's sign-in), jwt_bearer (a service account's key), "
                    + "github_app (a GitHub App) or snowflake_key_pair (a Snowflake key pair).");
        SecureString clientSecret = ref(credentials, "sign_in_client_secret", secret);
        SecureString refresh = ref(credentials, "sign_in_refresh_token", secret);
        SecureString privateKey = ref(credentials, "sign_in_private_key", secret);
        boolean keyAuth = text(values, "sign_in_client_auth").equalsIgnoreCase("private_key_jwt");
        String clientId = text(values, "sign_in_client_id");
        JwtSigner.Key key = privateKey == null ? null : JwtSigner.key(privateKey.reveal());
        if (clientId.isEmpty() && key != null && key.clientEmail() != null) clientId = key.clientEmail();   // Google's key file names its account
        if (clientId.isEmpty() && !grant.equals("snowflake_key_pair"))
            throw fail(connection, "the client id is missing", grant.equals("github_app") ? "Fill in the GitHub App's id." : "Fill in the app's client id (application id) in the connection.");
        if ((grant.equals("jwt_bearer") || grant.equals("github_app") || grant.equals("snowflake_key_pair") || keyAuth) && key == null)
            throw fail(connection, "the private key is missing", "Put the key in the vault (PEM, or Google's JSON key file) and pick it in the connection.");
        if (grant.equals("client_credentials") && clientSecret == null && !keyAuth)
            throw fail(connection, "the client secret is missing", "Pick the app's client secret from the vault in the connection, or sign in with a private key.");
        if (grant.equals("refresh_token") && refresh == null)
            throw fail(connection, "the refresh token is missing", "Pick the person's refresh token from the vault in the connection.");
        String tokenUrl = tokenUrl(connection, provider, grant, values);
        String scope = text(values, "sign_in_scope");
        if (scope.isEmpty() && provider.equals("microsoft"))
            scope = grant.equals("refresh_token") ? "https://graph.microsoft.com/.default offline_access" : "https://graph.microsoft.com/.default";

        String key0 = connection + "|" + tokenUrl + "|" + clientId + "|" + scope + "|" + grant + "|" + text(values, "sign_in_subject") + "|" + text(values, "sign_in_user") + "|"
                + sha256((clientSecret == null ? "" : clientSecret.reveal()) + "|" + (refresh == null ? "" : refresh.reveal()) + "|" + (privateKey == null ? "" : privateKey.reveal()));
        long now = System.currentTimeMillis();
        Token t = TOKENS.get(key0);
        if (t != null && t.fresh(now)) return new SecureString(t.access(), "connection " + connection);
        synchronized (TOKENS) {
            t = TOKENS.get(key0);
            if (t != null && t.fresh(System.currentTimeMillis())) return new SecureString(t.access(), "connection " + connection);
            long nowS = System.currentTimeMillis() / 1000;
            switch (grant) {
                case "snowflake_key_pair" -> {
                    // K1 (batch 10): Snowflake takes the signed JWT itself as the token (KEYPAIR_JWT), for up to an hour
                    String account = text(values, "sign_in_account").toUpperCase(Locale.ROOT), user = text(values, "sign_in_user").toUpperCase(Locale.ROOT);
                    if (account.isEmpty() || user.isEmpty()) throw fail(connection, "the Snowflake account or user is missing", "Fill in the account identifier (e.g. MYORG-MYACCOUNT) and the user.");
                    if (account.contains(".")) account = account.substring(0, account.indexOf('.'));
                    String qualified = account + "." + user;
                    String jwt = JwtSigner.sign(new LinkedHashMap<>(Map.of("iss", qualified + "." + JwtSigner.publicKeyFingerprint(key.privateKey()), "sub", qualified,
                            "iat", nowS, "exp", nowS + 3600)), key.privateKey(), null);
                    TOKENS.put(key0, new Token(jwt, (nowS + 3600) * 1000));
                    return new SecureString(jwt, "connection " + connection);
                }
                case "github_app" -> {
                    // K1 (batch 10): a GitHub App signs a short JWT and exchanges it for an installation token (one hour)
                    String jwt = JwtSigner.sign(new LinkedHashMap<>(Map.of("iat", nowS - 60, "exp", nowS + 540, "iss", clientId)), key.privateKey(), null);
                    Map<String, Object> answer = send(connection, tokenUrl, "", Map.of("Authorization", "Bearer " + jwt, "Accept", "application/vnd.github+json"), Map.of());
                    Object access = answer.get("token");
                    if (!(access instanceof String a) || a.isBlank()) throw fail(connection, "GitHub gave no installation token", "Check the App id, the installation id and the key.");
                    long expires = answer.get("expires_at") instanceof String e ? java.time.Instant.parse(e).toEpochMilli() : System.currentTimeMillis() + 3_600_000;
                    TOKENS.put(key0, new Token(a, expires));
                    return new SecureString(a, "connection " + connection);
                }
                default -> { }
            }
            Map<String, String> form = new LinkedHashMap<>();
            if (grant.equals("jwt_bearer")) {
                // K1 (batch 10): RFC 7523, as a Google service account (optionally acting as a person) or DocuSign signs in
                form.put("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer");
                Map<String, Object> claims = new LinkedHashMap<>();
                claims.put("iss", clientId);
                if (!text(values, "sign_in_subject").isEmpty()) claims.put("sub", text(values, "sign_in_subject"));
                claims.put("aud", text(values, "sign_in_audience").isEmpty() ? audience(provider, tokenUrl) : text(values, "sign_in_audience"));
                if (!scope.isEmpty()) claims.put("scope", scope);
                claims.put("iat", nowS);
                claims.put("exp", nowS + 3600);
                form.put("assertion", JwtSigner.sign(claims, key.privateKey(), text(values, "sign_in_key_id")));
            } else {
                form.put("grant_type", grant);
                form.put("client_id", clientId);
                if (keyAuth) {
                    // K1 (batch 10): private_key_jwt client authentication (Okta service apps refuse client secrets for their admin API)
                    form.put("client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer");
                    form.put("client_assertion", JwtSigner.sign(new LinkedHashMap<>(Map.of("iss", clientId, "sub", clientId, "aud", tokenUrl,
                            "iat", nowS, "exp", nowS + 300, "jti", UUID.randomUUID().toString())), key.privateKey(), text(values, "sign_in_key_id")));
                } else if (clientSecret != null) form.put("client_secret", clientSecret.reveal());
                if (grant.equals("refresh_token")) form.put("refresh_token", ROTATED.getOrDefault(key0, refresh.reveal()));
                if (!scope.isEmpty()) form.put("scope", scope);
            }
            form.putAll(extraFields(values));
            Map<String, Object> answer = post(connection, tokenUrl, form);
            Object access = answer.get("access_token");
            if (!(access instanceof String a) || a.isBlank()) throw fail(connection, "the sign-in server gave no access token", "Check the connection's sign-in.");
            long expires = answer.get("expires_in") instanceof Number n ? n.longValue() : answer.get("expires_in") instanceof String x && x.matches("\\d+") ? Long.parseLong(x) : 3600;
            if (answer.get("refresh_token") instanceof String r && !r.isBlank() && grant.equals("refresh_token")) ROTATED.put(key0, r);
            TOKENS.put(key0, new Token(a, System.currentTimeMillis() + expires * 1000));
            return new SecureString(a, "connection " + connection);
        }
    }

    /** The sign-in kind of a connection's settings (the provider's usual one when not said). */
    public static String grant(Map<String, Object> values) {
        String g = text(values, "sign_in_grant").toLowerCase(Locale.ROOT);
        return g.isEmpty() ? defaultGrant(text(values, "sign_in_provider").toLowerCase(Locale.ROOT)) : g;
    }

    private static String defaultGrant(String provider) {
        return switch (provider) { case "github" -> "github_app"; case "snowflake" -> "snowflake_key_pair"; case "docusign" -> "jwt_bearer"; default -> "client_credentials"; };
    }

    private static String tokenUrl(String connection, String provider, String grant, Map<String, Object> values) {
        String u = text(values, "sign_in_token_url");
        if (!u.isEmpty() || grant.equals("snowflake_key_pair")) return u;
        return switch (provider) {
            case "microsoft" -> {
                String tenant = text(values, "sign_in_tenant");
                if (tenant.isEmpty()) throw fail(connection, "the Microsoft tenant is missing", "Fill in the tenant, e.g. contoso.onmicrosoft.com or its id.");
                yield "https://login.microsoftonline.com/" + enc(tenant) + "/oauth2/v2.0/token";
            }
            case "google" -> "https://oauth2.googleapis.com/token";
            case "docusign" -> "https://account.docusign.com/oauth/token";
            case "box" -> "https://api.box.com/oauth2/token";
            case "github" -> {
                String inst = text(values, "sign_in_installation_id");
                if (!inst.matches("\\d+")) throw fail(connection, "the GitHub App's installation id is missing", "Fill in the installation id (the number in the App's installation settings address).");
                yield "https://api.github.com/app/installations/" + inst + "/access_tokens";
            }
            default -> throw fail(connection, "the token address is missing", "Fill in the sign-in server's token address.");
        };
    }

    /** Who a JWT grant is for: Google wants its token address; DocuSign its account server's host. */
    private static String audience(String provider, String tokenUrl) {
        if (provider.equals("docusign")) return URI.create(tokenUrl).getHost();
        return tokenUrl;
    }

    /** Extra fields for the token request, e.g. Box's box_subject_type=enterprise, box_subject_id=12345 (K1, batch 10). */
    @SuppressWarnings("unchecked")
    static Map<String, String> extraFields(Map<String, Object> values) {
        Map<String, String> out = new LinkedHashMap<>();
        Object v = values == null ? null : values.get("sign_in_extra_fields");
        if (v instanceof Map<?, ?> m) ((Map<String, Object>) m).forEach((k, x) -> out.put(k, String.valueOf(x)));
        else if (v instanceof String t) for (String part : t.split("[,&\\n]")) {
            int eq = part.indexOf('=');
            if (eq > 0) out.put(part.substring(0, eq).strip(), part.substring(eq + 1).strip());
        }
        for (String k : List.of("grant_type", "client_id", "client_secret", "refresh_token", "assertion", "client_assertion", "client_assertion_type")) out.remove(k);
        return out;
    }

    /** POST with headers and a form (empty: no body); the answer as JSON. Used where the token request is not a plain form. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> send(String connection, String url, String body, Map<String, String> headers, Map<String, String> secrets) {
        HttpResponse<String> r;
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).POST(HttpRequest.BodyPublishers.ofString(body));
            headers.forEach(b::header);
            r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OoaException("RUN.CANCELLED", "Interrupted while signing in");
        } catch (Exception e) {
            throw new OoaException("CONNECTION.SIGN_IN_FAILED", "Connection '" + connection + "' could not reach its sign-in server: " + e.getMessage(),
                    "Check the token address and that the runner can reach it.", true, Map.of());
        }
        Object parsed;
        try { parsed = JsonLite.parse(r.body()); } catch (RuntimeException e) { parsed = null; }
        Map<String, Object> m = parsed instanceof Map<?, ?> p ? (Map<String, Object>) p : Map.of();
        if (r.statusCode() / 100 != 2)
            throw new OoaException("CONNECTION.SIGN_IN_FAILED", "Connection '" + connection + "' could not sign in (HTTP " + r.statusCode() + ")"
                    + (text(m, "message").isEmpty() ? "" : ": " + text(m, "message").lines().findFirst().orElse("")), "Check the App id, the installation id and the key.", r.statusCode() >= 500, Map.of());
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> post(String connection, String url, Map<String, String> form) {
        StringBuilder body = new StringBuilder();
        for (var e : form.entrySet()) body.append(body.length() == 0 ? "" : "&").append(enc(e.getKey())).append('=').append(enc(e.getValue()));
        HttpResponse<String> r;
        try {
            r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/x-www-form-urlencoded").header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OoaException("RUN.CANCELLED", "Interrupted while signing in");
        } catch (Exception e) {
            throw new OoaException("CONNECTION.SIGN_IN_FAILED", "Connection '" + connection + "' could not reach its sign-in server: " + e.getMessage(),
                    "Check the token address and that the runner can reach it.", true, Map.of());
        }
        Object parsed;
        try { parsed = JsonLite.parse(r.body()); } catch (RuntimeException e) { parsed = null; }
        Map<String, Object> m = parsed instanceof Map<?, ?> p ? (Map<String, Object>) p : Map.of();
        if (r.statusCode() / 100 != 2) {
            // the server's own words, without anything that was sent (secrets never appear in messages)
            String why = text(m, "error_description").isEmpty() ? text(m, "error") : text(m, "error_description");
            for (String v : form.values()) if (v.length() > 3) why = why.replace(v, "***");
            throw new OoaException("CONNECTION.SIGN_IN_FAILED", "Connection '" + connection + "' could not sign in (HTTP " + r.statusCode() + ")"
                    + (why.isEmpty() ? "" : ": " + why.lines().findFirst().orElse("")), "Check the client id, the secret or refresh token in the vault, and the tenant.",
                    r.statusCode() >= 500, Map.of());
        }
        return m;
    }

    private static SecureString ref(Map<String, Object> credentials, String name, Function<String, SecureString> secret) {
        Object r = credentials == null ? null : credentials.get(name);
        return r instanceof String s && !s.isBlank() ? secret.apply(s) : null;
    }

    private static OoaException fail(String connection, String what, String fix) {
        return new OoaException("CONNECTION.SIGN_IN_INCOMPLETE", "Connection '" + connection + "': " + what, fix);
    }

    private static String text(Map<String, Object> m, String k) { return m == null || m.get(k) == null ? "" : String.valueOf(m.get(k)).strip(); }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    private static String sha256(String s) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
