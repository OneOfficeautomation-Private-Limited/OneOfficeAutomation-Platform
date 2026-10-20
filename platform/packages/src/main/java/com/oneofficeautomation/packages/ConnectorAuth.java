package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Authentication for custom connectors: OAuth 2.0 client credentials with a token cache, and AWS Signature Version 4. */
final class ConnectorAuth {
    private ConnectorAuth() {}

    // ---------- OAuth 2.0 client credentials ----------

    record Token(String accessToken, String type, String refreshToken, long expiresAtMillis) {
        boolean fresh(long now) { return expiresAtMillis == 0 || now < expiresAtMillis; }
    }

    /** Tokens per token URL, client, scope and secret (by hash, never the secret itself). Shared by all runs in this process. */
    static final Map<String, Token> TOKENS = new ConcurrentHashMap<>();
    /** Renew this long before the server's expiry so a token never runs out mid-request. */
    static final long SKEW_MILLIS = 60_000;

    static String cacheKey(String tokenUrl, String clientId, String scope, String secret) {
        return tokenUrl + "|" + clientId + "|" + scope + "|" + sha256Hex(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * A valid access token: from the cache while fresh, renewed with the refresh token when the server issued one,
     * otherwise requested again with the client credentials.
     */
    static Token oauthToken(ActionContext ctx, String tokenUrl, String clientId, String secret, String scope, String clientAuth, boolean forceNew) {
        String key = cacheKey(tokenUrl, clientId, scope, secret);
        long now = System.currentTimeMillis();
        Token t = TOKENS.get(key);
        if (!forceNew && t != null && t.fresh(now)) return t;
        Token fresh = null;
        if (t != null && t.refreshToken() != null) {
            try { fresh = requestToken(ctx, tokenUrl, clientId, secret, scope, clientAuth, t.refreshToken()); }
            catch (ActionException e) { ctx.info("Refresh token was not accepted; requesting a new token"); }
        }
        if (fresh == null) fresh = requestToken(ctx, tokenUrl, clientId, secret, scope, clientAuth, null);
        TOKENS.put(key, fresh);
        return fresh;
    }

    static void forget(String tokenUrl, String clientId, String scope, String secret) { TOKENS.remove(cacheKey(tokenUrl, clientId, scope, secret)); }

    private static Token requestToken(ActionContext ctx, String tokenUrl, String clientId, String secret, String scope, String clientAuth, String refreshToken) {
        Map<String, Object> form = new LinkedHashMap<>();
        if (refreshToken != null) { form.put("grant_type", "refresh_token"); form.put("refresh_token", refreshToken); }
        else form.put("grant_type", "client_credentials");
        if (scope != null && !scope.isBlank()) form.put("scope", scope);
        HttpJson.Req req = HttpJson.req("CONNECTOR.OAUTH", "POST", tokenUrl);
        if ("body".equals(clientAuth)) { form.put("client_id", clientId); form.put("client_secret", secret); }
        else req.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                (HttpJson.enc(clientId) + ":" + HttpJson.enc(secret)).getBytes(StandardCharsets.UTF_8)));
        req.body = HttpRequest.BodyPublishers.ofString(HttpJson.query(form));
        req.header("Content-Type", "application/x-www-form-urlencoded");
        HttpJson.Response r;
        try { r = req.send(ctx); }
        catch (ActionException e) {
            throw new ActionException("CONNECTOR.OAUTH_FAILED", "The token endpoint refused the client credentials: " + e.getMessage(),
                    "Check the client id, client secret, scope and token URL.", e.retryable(), e.details());
        }
        Map<String, Object> m = r.map();
        Object at = m.get("access_token");
        if (!(at instanceof String s) || s.isBlank())
            throw new ActionException("CONNECTOR.OAUTH_FAILED", "The token endpoint answered without an access_token", "Check the token URL.");
        long expires = 0;
        if (m.get("expires_in") != null) {
            try { expires = System.currentTimeMillis() + new BigDecimal(String.valueOf(m.get("expires_in"))).longValue() * 1000 - SKEW_MILLIS; }
            catch (NumberFormatException ignored) { }
        }
        String rt = m.get("refresh_token") instanceof String x && !x.isBlank() ? x : refreshToken;
        return new Token(s, m.get("token_type") instanceof String tt ? tt : "Bearer", rt, expires);
    }

    // ---------- AWS Signature Version 4 ----------

    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

    /**
     * Adds the AWS SigV4 headers (Authorization, X-Amz-Date, and X-Amz-Security-Token / X-Amz-Content-Sha256 where needed)
     * to {@code headers} for a request to {@code url}. Signs host, x-amz-* and content-type headers.
     */
    static void signV4(String method, String url, Map<String, String> headers, byte[] payload, String accessKey, String secretKey,
                       String sessionToken, String region, String service, ZonedDateTime when) {
        URI u = URI.create(url);
        String amzDate = when.withZoneSameInstant(ZoneOffset.UTC).format(AMZ_DATE);
        String day = amzDate.substring(0, 8);
        String payloadHash = sha256Hex(payload == null ? new byte[0] : payload);
        headers.put("X-Amz-Date", amzDate);
        if (sessionToken != null && !sessionToken.isEmpty()) headers.put("X-Amz-Security-Token", sessionToken);
        if ("s3".equals(service)) headers.put("X-Amz-Content-Sha256", payloadHash);
        int port = u.getPort();
        String host = u.getHost() + (port == -1 || (port == 443 && "https".equals(u.getScheme())) || (port == 80 && "http".equals(u.getScheme())) ? "" : ":" + port);
        TreeMap<String, String> canon = new TreeMap<>();
        canon.put("host", host);
        for (var e : headers.entrySet()) {
            String k = e.getKey().toLowerCase(Locale.ROOT);
            if (k.startsWith("x-amz-") || k.equals("content-type")) canon.put(k, e.getValue().trim().replaceAll("\\s+", " "));
        }
        StringBuilder ch = new StringBuilder();
        canon.forEach((k, v) -> ch.append(k).append(':').append(v).append('\n'));
        String signed = String.join(";", canon.keySet());
        String rawPath = u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath();
        String canonicalUri = "s3".equals(service) ? rawPath : encodeSegments(rawPath);
        String canonicalRequest = method + "\n" + canonicalUri + "\n" + canonicalQuery(u.getRawQuery()) + "\n" + ch + "\n" + signed + "\n" + payloadHash;
        String scope = day + "/" + region + "/" + service + "/aws4_request";
        String toSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n" + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        byte[] k = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), day);
        k = hmac(k, region);
        k = hmac(k, service);
        k = hmac(k, "aws4_request");
        String signature = HexFormat.of().formatHex(hmac(k, toSign));
        headers.put("Authorization", "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + scope + ", SignedHeaders=" + signed + ", Signature=" + signature);
    }

    /** Non-S3 services expect each already-encoded path segment to be encoded once more. */
    private static String encodeSegments(String rawPath) {
        StringBuilder sb = new StringBuilder();
        String[] parts = rawPath.split("/", -1);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append('/');
            sb.append(awsEncode(parts[i]));
        }
        return sb.toString();
    }

    private static String canonicalQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) return "";
        List<String[]> pairs = new ArrayList<>();
        for (String kv : rawQuery.split("&")) {
            if (kv.isEmpty()) continue;
            int i = kv.indexOf('=');
            String k = java.net.URLDecoder.decode(i < 0 ? kv : kv.substring(0, i), StandardCharsets.UTF_8);
            String v = i < 0 ? "" : java.net.URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8);
            pairs.add(new String[]{awsEncode(k), awsEncode(v)});
        }
        pairs.sort(Comparator.<String[], String>comparing(p -> p[0]).thenComparing(p -> p[1]));
        StringJoiner j = new StringJoiner("&");
        for (String[] p : pairs) j.add(p[0] + "=" + p[1]);
        return j.toString();
    }

    /** RFC 3986 encoding as AWS wants it: unreserved characters stay, everything else is %XX (upper case). */
    static String awsEncode(String s) {
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~') sb.append((char) c);
            else sb.append('%').append(String.format("%02X", c));
        }
        return sb.toString();
    }

    static String sha256Hex(byte[] data) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
}
