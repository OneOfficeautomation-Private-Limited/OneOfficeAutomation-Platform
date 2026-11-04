package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sign-in options of Send request beyond basic and bearer: OAuth 2.0 (client credentials or refresh token) with a token
 * cache for the run, and OAuth 1.0a request signing (RFC 5849, HMAC-SHA1 or HMAC-SHA256). Secrets are revealed only
 * where they are sent and never appear in messages: a token endpoint's error text is scrubbed of them.
 */
final class RestSignIn {
    private RestSignIn() {}

    // ------------------------------------------------------------------ OAuth 2.0

    /** The OAuth 2.0 settings of one request; the refresh-token grant when a refresh token is given. */
    record OAuth2(String tokenUrl, String clientId, SecureString secret, SecureString refreshToken, String scope, String audience, String resource, String clientAuth) {
        boolean refreshGrant() { return refreshToken != null; }
        String grant() { return refreshGrant() ? "refresh_token" : "client_credentials"; }
        /** Token URL, client, scope (and audience / resource, grant) identify a token; never the secret. */
        String key() { return String.join("\n", tokenUrl, clientId, nz(scope), nz(audience), nz(resource), grant()); }
    }

    record Token(String header, long expiresAtMillis) {
        boolean fresh(long now) { return expiresAtMillis == 0 || now < expiresAtMillis; }
    }

    /** One cached sign-in: the access token and, for the refresh-token grant, the newest refresh token the server gave. */
    static final class Entry {
        Token token;
        String refreshToken;
    }

    static final String SESSION = "ooa.rest.oauth";
    /** Renew this long before the server's expiry (at most a tenth of the token's life). */
    static final long SKEW_MILLIS = 60_000;

    @SuppressWarnings("unchecked")
    static Map<String, Entry> cache(ActionContext ctx) {
        return (Map<String, Entry>) ctx.sessions().computeIfAbsent(SESSION, k -> new ConcurrentHashMap<String, Entry>());
    }

    /** The Authorization header value for {@code o}: from the run's cache while fresh, else a new token. */
    static String authorization(ActionContext ctx, HttpClient client, OAuth2 o, boolean renew, int timeout) {
        Entry e = cache(ctx).computeIfAbsent(o.key(), k -> new Entry());
        synchronized (e) {
            if (!renew && e.token != null && e.token.fresh(System.currentTimeMillis())) return e.token.header();
            e.token = fetch(ctx, client, o, e, timeout);
            return e.token.header();
        }
    }

    /** The newest refresh token of this run for {@code o} (the given one until the server sends another). */
    static SecureString latestRefreshToken(ActionContext ctx, OAuth2 o) {
        if (!o.refreshGrant()) return null;
        Entry e = cache(ctx).get(o.key());
        if (e == null || e.refreshToken == null) return o.refreshToken();
        synchronized (e) { return new SecureString(e.refreshToken, "rest/oauth/refresh_token"); }
    }

    private static Token fetch(ActionContext ctx, HttpClient client, OAuth2 o, Entry e, int timeout) {
        String secret = o.secret() == null ? null : o.secret().reveal();
        String refresh = o.refreshGrant() ? (e.refreshToken != null ? e.refreshToken : o.refreshToken().reveal()) : null;
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", o.grant());
        if (refresh != null) form.put("refresh_token", refresh);
        if (!blank(o.scope())) form.put("scope", o.scope().strip());
        if (!blank(o.audience())) form.put("audience", o.audience().strip());
        if (!blank(o.resource())) form.put("resource", o.resource().strip());
        HttpRequest.Builder b;
        try { b = HttpRequest.newBuilder(URI.create(o.tokenUrl())).timeout(Duration.ofSeconds(Math.max(1, timeout))); }
        catch (IllegalArgumentException x) { throw new ActionException("REST.OAUTH_SETTINGS", "Invalid oauth_token_url: " + masked(o.tokenUrl()), "Give the token endpoint, e.g. https://login.example.com/oauth2/token."); }
        if (secret != null && !"body".equals(o.clientAuth())) {
            b.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                    (formEnc(o.clientId()) + ":" + formEnc(secret)).getBytes(StandardCharsets.UTF_8)));
        } else {
            form.put("client_id", o.clientId());
            if (secret != null) form.put("client_secret", secret);
        }
        StringJoiner body = new StringJoiner("&");
        form.forEach((k, v) -> body.add(formEnc(k) + "=" + formEnc(v)));
        b.header("Content-Type", "application/x-www-form-urlencoded").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        ctx.info("Getting an OAuth 2.0 token from " + masked(o.tokenUrl()) + " (" + o.grant().replace('_', ' ') + ")");
        HttpResponse<String> r;
        try { r = client.send(b.build(), HttpResponse.BodyHandlers.ofString()); }
        catch (HttpTimeoutException x) { throw new ActionException("REST.OAUTH_FAILED", "No answer from the token endpoint " + masked(o.tokenUrl()) + " within " + timeout + " s", "Check the token URL.", true); }
        catch (IOException x) { throw new ActionException("REST.OAUTH_FAILED", "Cannot reach the token endpoint " + masked(o.tokenUrl()) + ": " + scrub(NetErrors.reason(x, null), secret, refresh), "Check the token URL, proxy settings and network access.", true); }
        catch (InterruptedException x) { Thread.currentThread().interrupt(); throw new OoaException("RUN.CANCELLED", "Request interrupted"); }
        Map<String, Object> m = parseTokenResponse(r.body());
        int status = r.statusCode();
        if (status >= 400) {
            String why = m.get("error") == null ? "" : ": " + m.get("error") + (m.get("error_description") == null ? "" : " - " + m.get("error_description"));
            if (why.length() > 300) why = why.substring(0, 300);
            throw new ActionException("REST.OAUTH_FAILED", "The token endpoint " + masked(o.tokenUrl()) + " refused the sign-in (HTTP " + status + scrub(why, secret, refresh) + ")",
                    o.refreshGrant() ? "Check the client id and that the refresh token is still valid (sign in again to get a new one)." : "Check the client id, client secret, scope and token URL.",
                    status == 429 || status >= 500, Map.of("status", BigDecimal.valueOf(status)));
        }
        if (!(m.get("access_token") instanceof String at) || at.isBlank())
            throw new ActionException("REST.OAUTH_FAILED", "The token endpoint " + masked(o.tokenUrl()) + " answered without an access_token", "Check the token URL.");
        long expires = 0;
        if (m.get("expires_in") != null) {
            try {
                long life = new BigDecimal(String.valueOf(m.get("expires_in"))).longValue() * 1000;
                if (life > 0) expires = System.currentTimeMillis() + life - Math.min(SKEW_MILLIS, life / 10);
            } catch (NumberFormatException ignored) { }
        }
        if (o.refreshGrant()) {
            if (m.get("refresh_token") instanceof String nr && !nr.isBlank()) { e.refreshToken = nr; ctx.info("The token endpoint issued a new refresh token; it is used for the rest of this run"); }
            else if (e.refreshToken == null) e.refreshToken = refresh;
        }
        String type = m.get("token_type") instanceof String t && !t.isBlank() && !t.equalsIgnoreCase("bearer") ? t : "Bearer";
        return new Token(type + " " + at, expires);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseTokenResponse(String text) {
        if (text == null || text.isBlank()) return new LinkedHashMap<>();
        try { if (JsonLite.parse(text) instanceof Map<?, ?> m) return (Map<String, Object>) m; }
        catch (IllegalArgumentException ignored) { }
        // a few older servers answer form-encoded (access_token=...&expires_in=...)
        Map<String, Object> m = new LinkedHashMap<>();
        if (text.contains("=") && !text.strip().startsWith("<"))
            for (String[] p : parseForm(text.strip())) m.put(p[0], p[1]);
        return m;
    }

    // ------------------------------------------------------------------ OAuth 1.0a

    record OAuth1(String consumerKey, SecureString consumerSecret, SecureString token, SecureString tokenSecret, String method) {
        String algorithm() { return "HMAC-SHA256".equals(method) ? "HmacSHA256" : "HmacSHA1"; }
        String signatureMethod() { return "HMAC-SHA256".equals(method) ? "HMAC-SHA256" : "HMAC-SHA1"; }
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    /** The Authorization header for one request; {@code formParams} are the decoded fields of a form body, else empty. */
    static String oauth1Header(OAuth1 o, String method, String url, List<String[]> formParams) {
        byte[] n = new byte[16];
        RANDOM.nextBytes(n);
        return oauth1Header(o, method, url, formParams, HexFormat.of().formatHex(n), String.valueOf(System.currentTimeMillis() / 1000));
    }

    static String oauth1Header(OAuth1 o, String method, String url, List<String[]> formParams, String nonce, String timestamp) {
        Map<String, String> oauth = new TreeMap<>();
        oauth.put("oauth_consumer_key", o.consumerKey());
        oauth.put("oauth_nonce", nonce);
        oauth.put("oauth_signature_method", o.signatureMethod());
        oauth.put("oauth_timestamp", timestamp);
        if (o.token() != null && !o.token().reveal().isEmpty()) oauth.put("oauth_token", o.token().reveal());
        oauth.put("oauth_version", "1.0");
        String base = baseString(method, url, formParams, oauth);
        String sig = sign(base, o.consumerSecret().reveal(), o.tokenSecret() == null ? "" : o.tokenSecret().reveal(), o.algorithm());
        StringJoiner h = new StringJoiner(", ", "OAuth ", "");
        oauth.put("oauth_signature", sig);
        oauth.forEach((k, v) -> h.add(pct(k) + "=\"" + pct(v) + "\""));
        return h.toString();
    }

    /** RFC 5849 section 3.4.1: method, base URI and the sorted, encoded parameters (query, form body, oauth_*). */
    static String baseString(String method, String url, List<String[]> formParams, Map<String, String> oauth) {
        URI u = URI.create(url);
        String scheme = u.getScheme().toLowerCase(Locale.ROOT), host = u.getHost().toLowerCase(Locale.ROOT);
        int port = u.getPort();
        boolean defaultPort = port == -1 || (port == 80 && scheme.equals("http")) || (port == 443 && scheme.equals("https"));
        String path = u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath();
        String baseUri = scheme + "://" + host + (defaultPort ? "" : ":" + port) + path;
        List<String[]> params = new ArrayList<>();
        if (u.getRawQuery() != null) params.addAll(parseForm(u.getRawQuery()));
        if (formParams != null) params.addAll(formParams);
        oauth.forEach((k, v) -> params.add(new String[]{k, v}));
        List<String[]> enc = new ArrayList<>();
        for (String[] p : params) enc.add(new String[]{pct(p[0]), pct(p[1])});
        enc.sort(Comparator.<String[], String>comparing(p -> p[0]).thenComparing(p -> p[1]));
        StringJoiner j = new StringJoiner("&");
        for (String[] p : enc) j.add(p[0] + "=" + p[1]);
        return method.toUpperCase(Locale.ROOT) + "&" + pct(baseUri) + "&" + pct(j.toString());
    }

    static String sign(String base, String consumerSecret, String tokenSecret, String algorithm) {
        try {
            Mac mac = Mac.getInstance(algorithm);
            mac.init(new SecretKeySpec((pct(consumerSecret) + "&" + pct(tokenSecret)).getBytes(StandardCharsets.UTF_8), algorithm));
            return Base64.getEncoder().encodeToString(mac.doFinal(base.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) { throw new IllegalStateException(e); }
    }

    /** Decoded name/value pairs of a query string or form body (application/x-www-form-urlencoded rules: + is a space). */
    static List<String[]> parseForm(String text) {
        List<String[]> out = new ArrayList<>();
        for (String kv : text.split("&")) {
            if (kv.isEmpty()) continue;
            int i = kv.indexOf('=');
            out.add(new String[]{URLDecoder.decode(i < 0 ? kv : kv.substring(0, i), StandardCharsets.UTF_8),
                    i < 0 ? "" : URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8)});
        }
        return out;
    }

    /** RFC 3986 percent-encoding: unreserved characters stay, everything else is %XX in upper case. */
    static String pct(String s) { return ConnectorAuth.awsEncode(s); }

    // ------------------------------------------------------------------ helpers

    private static String formEnc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    static String scrub(String text, String... secrets) {
        String out = text == null ? "" : text;
        for (String s : secrets) if (s != null && s.length() >= 3) out = out.replace(s, SecureString.MASK);
        return out;
    }

    static String masked(String url) { return url == null ? "" : url.replaceAll("(?i)([?&][^=]*(token|key|secret|password)[^=]*=)[^&]+", "$1****"); }

    private static boolean blank(String s) { return s == null || s.isBlank(); }

    private static String nz(String s) { return s == null ? "" : s.strip(); }
}
