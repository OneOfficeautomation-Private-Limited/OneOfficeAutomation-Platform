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
 *   <li>credentials (vault references): sign_in_client_secret, sign_in_refresh_token</li>
 * </ul>
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
    public static SecureString token(String connection, Map<String, Object> values, Map<String, Object> credentials, Function<String, SecureString> secret) {
        String provider = text(values, "sign_in_provider").toLowerCase(Locale.ROOT);
        String grant = text(values, "sign_in_grant").isEmpty() ? "client_credentials" : text(values, "sign_in_grant").toLowerCase(Locale.ROOT);
        if (!grant.equals("client_credentials") && !grant.equals("refresh_token"))
            throw fail(connection, "unknown sign-in kind '" + grant + "'", "Use client_credentials (the app signs in) or refresh_token (one person's sign-in).");
        String clientId = text(values, "sign_in_client_id");
        if (clientId.isEmpty()) throw fail(connection, "the client id is missing", "Fill in the app's client id (application id) in the connection.");
        String tokenUrl = text(values, "sign_in_token_url");
        if (tokenUrl.isEmpty()) tokenUrl = switch (provider) {
            case "microsoft" -> {
                String tenant = text(values, "sign_in_tenant");
                if (tenant.isEmpty()) throw fail(connection, "the Microsoft tenant is missing", "Fill in the tenant, e.g. contoso.onmicrosoft.com or its id.");
                yield "https://login.microsoftonline.com/" + enc(tenant) + "/oauth2/v2.0/token";
            }
            case "google" -> "https://oauth2.googleapis.com/token";
            default -> throw fail(connection, "the token address is missing", "Fill in the sign-in server's token address.");
        };
        String scope = text(values, "sign_in_scope");
        if (scope.isEmpty() && provider.equals("microsoft"))
            scope = grant.equals("client_credentials") ? "https://graph.microsoft.com/.default" : "https://graph.microsoft.com/.default offline_access";
        SecureString clientSecret = ref(credentials, "sign_in_client_secret", secret);
        SecureString refresh = ref(credentials, "sign_in_refresh_token", secret);
        if (grant.equals("client_credentials") && clientSecret == null)
            throw fail(connection, "the client secret is missing", "Pick the app's client secret from the vault in the connection.");
        if (grant.equals("refresh_token") && refresh == null)
            throw fail(connection, "the refresh token is missing", "Pick the person's refresh token from the vault in the connection.");

        String key = connection + "|" + tokenUrl + "|" + clientId + "|" + scope + "|" + grant + "|"
                + sha256((clientSecret == null ? "" : clientSecret.reveal()) + "|" + (refresh == null ? "" : refresh.reveal()));
        long now = System.currentTimeMillis();
        Token t = TOKENS.get(key);
        if (t != null && t.fresh(now)) return new SecureString(t.access(), "connection " + connection);
        synchronized (TOKENS) {
            t = TOKENS.get(key);
            if (t != null && t.fresh(System.currentTimeMillis())) return new SecureString(t.access(), "connection " + connection);
            Map<String, String> form = new LinkedHashMap<>();
            form.put("grant_type", grant);
            form.put("client_id", clientId);
            if (clientSecret != null) form.put("client_secret", clientSecret.reveal());
            if (grant.equals("refresh_token")) form.put("refresh_token", ROTATED.getOrDefault(key, refresh.reveal()));
            if (!scope.isEmpty()) form.put("scope", scope);
            Map<String, Object> answer = post(connection, tokenUrl, form);
            Object access = answer.get("access_token");
            if (!(access instanceof String a) || a.isBlank()) throw fail(connection, "the sign-in server gave no access token", "Check the connection's sign-in.");
            long expires = answer.get("expires_in") instanceof Number n ? n.longValue() : answer.get("expires_in") instanceof String s && s.matches("\\d+") ? Long.parseLong(s) : 3600;
            if (answer.get("refresh_token") instanceof String r && !r.isBlank() && grant.equals("refresh_token")) ROTATED.put(key, r);
            TOKENS.put(key, new Token(a, System.currentTimeMillis() + expires * 1000));
            return new SecureString(a, "connection " + connection);
        }
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
