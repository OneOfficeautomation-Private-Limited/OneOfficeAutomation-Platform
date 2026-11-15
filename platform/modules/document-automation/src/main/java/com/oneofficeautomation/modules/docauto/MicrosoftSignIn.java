package com.oneofficeautomation.modules.docauto;

import com.oneofficeautomation.controlroom.ControlRoom.User;
import com.oneofficeautomation.controlroom.module.ModuleContext;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DA-1, "Sign in with Microsoft" for a process's Microsoft 365 email inbox (the user chose one OOA app, 15 Nov 2026).
 * <p>
 * The person signs in with a code (the OAuth 2.0 device authorization grant): the screen shows a short code and
 * microsoft.com/devicelogin, they sign in there with the mailbox's account, and this server asks Microsoft until the
 * sign-in is done. It needs no web address and no client secret, so it works for a server inside a customer's
 * network. The app is OOA's multi-tenant public app ({@link #OOA_APP_ID}) or the customer's own (Settings
 * microsoft_app_id); the first sign-in from an organisation asks its Microsoft administrator to approve the app once.
 * <p>
 * What is kept (table docflow_ms_signin, module schema version 2): who started the sign-in, the Microsoft account
 * and its directory (tenant), and one secret encrypted with the vault key (bound to ms-signin:ID): the device code
 * while the sign-in is pending, then the long-lived sign-in (refresh token), never shown and renewed each time the
 * inbox is read. Key rotation (VaultAdmin.reencryptAll) and tenant moves (TenantMove.SECRETS) carry it. The browser only ever sees the sign-in's id and the account's name; the
 * device code stays here too. Access tokens are kept in memory until a minute before they run out.
 * <p>
 * Microsoft's sign-in address is login.microsoftonline.com, or OOA_MICROSOFT_LOGIN_URL (national clouds such as
 * login.microsoftonline.us, and tests).
 */
public final class MicrosoftSignIn {
    /** OOA's own Microsoft app (multi-tenant, public client flows on); empty until it is registered in Entra. */
    public static final String OOA_APP_ID = "";
    /** Reading the person's own and shared mailboxes, their name, and a lasting sign-in. */
    static final String SCOPE = "openid profile offline_access https://graph.microsoft.com/User.Read https://graph.microsoft.com/Mail.Read https://graph.microsoft.com/Mail.Read.Shared";
    static final String DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code";
    static final Duration SKEW = Duration.ofSeconds(60);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ModuleContext ctx;
    private final String login;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final Map<String, Token> tokens = new ConcurrentHashMap<>();
    private volatile Instant cleaned = Instant.EPOCH;

    record Token(String access, Instant expires) {}
    /** Microsoft's answer: the HTTP status and the JSON body. */
    record Answer(int status, Map<String, Object> json) {
        String error() { return String.valueOf(json.getOrDefault("error", "")); }
        /** Microsoft's own words, first line only (it adds trace and correlation ids after). */
        String description() {
            String d = String.valueOf(json.getOrDefault("error_description", json.getOrDefault("error", "")));
            d = d.split("\\R", 2)[0].replaceFirst("^AADSTS\\d+:\\s*", "");
            return d.length() > 300 ? d.substring(0, 300) + "…" : d;
        }
    }

    public MicrosoftSignIn(ModuleContext ctx) { this(ctx, null); }

    /** With loginUrl null, Microsoft's address is read on each call (OOA_MICROSOFT_LOGIN_URL, or ooa.microsoft.login.url in tests). */
    public MicrosoftSignIn(ModuleContext ctx, String loginUrl) {
        this.ctx = ctx;
        this.login = loginUrl;
    }

    /** Microsoft's sign-in address. */
    String login() {
        String v = login != null ? login : System.getProperty("ooa.microsoft.login.url", System.getenv("OOA_MICROSOFT_LOGIN_URL"));
        return (v == null || v.isBlank() ? "https://login.microsoftonline.com" : v).replaceAll("/+$", "");
    }

    /** The Microsoft app used: Settings microsoft_app_id, else OOA's own. */
    public String appId() {
        Object s = ctx.setting("microsoft_app_id");
        String v = s == null ? "" : String.valueOf(s).trim();
        return v.isEmpty() ? OOA_APP_ID : v;
    }

    /** Whether "Sign in with Microsoft" can be offered (an app is known). */
    public boolean ready() { return !appId().isBlank(); }

    /** Starts a sign-in for the person: the code they type at Microsoft, where, and for how long. */
    public Map<String, Object> start(User u) throws IOException {
        if (!ready()) throw new OoaException("DOCS.MS_SIGNIN", "Sign in with Microsoft is not set up on this server",
                "An administrator puts the Microsoft app ID in Administration > Settings > Document Automation; or use your own app under Show advanced settings.");
        cleanUp();
        String app = appId();
        Answer a = post("/organizations/oauth2/v2.0/devicecode", Map.of("client_id", app, "scope", SCOPE));
        if (a.status() != 200 || a.json().get("device_code") == null)
            throw new OoaException("DOCS.MS_SIGNIN", "Microsoft did not start the sign-in: " + a.description(),
                    "Check that the Microsoft app ID in Settings is right and that the app allows public client flows.");
        String id = "ms" + hex(12);
        Instant now = Instant.now();
        long expiresIn = number(a.json().get("expires_in"), 900), interval = Math.max(1, number(a.json().get("interval"), 5));
        ctx.db().update("INSERT INTO docflow_ms_signin (id, status, started_by, client_id, secret, poll_every, next_poll, expires, created, updated) VALUES (?,?,?,?,?,?,?,?,?,?)",
                id, "pending", u.username(), app, ctx.vault().encrypt(String.valueOf(a.json().get("device_code")), bound(id)),
                (int) interval, now.plusSeconds(interval), now.plusSeconds(expiresIn), now, now);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("userCode", a.json().get("user_code"));
        out.put("verificationUri", a.json().getOrDefault("verification_uri", "https://microsoft.com/devicelogin"));
        out.put("expiresIn", expiresIn);
        out.put("interval", interval);
        return out;
    }

    /** Where the person's sign-in is: pending, signed_in (with the account) or failed (with what to do). */
    public Map<String, Object> poll(User u, String id) throws IOException {
        Map<String, Object> row = row(id);
        if (row == null || !u.username().equals(row.get("started_by")))
            throw new OoaException("CR.NOT_FOUND", "This Microsoft sign-in is not known", "Choose Sign in with Microsoft again.");
        String status = String.valueOf(row.get("status"));
        if (status.equals("signed_in")) return Map.of("status", "signed_in", "account", String.valueOf(row.get("account")));
        if (!status.equals("pending")) return Map.of("status", "failed", "message", String.valueOf(row.get("last_error")));
        Instant now = Instant.now();
        if (now.isAfter(instant(row.get("expires")))) return fail(id, "The code ran out before the sign-in was finished: choose Sign in with Microsoft again.");
        if (now.isBefore(instant(row.get("next_poll")))) return Map.of("status", "pending");
        int every = ((Number) row.get("poll_every")).intValue();
        Answer a = post("/organizations/oauth2/v2.0/token", Map.of("grant_type", DEVICE_GRANT, "client_id", String.valueOf(row.get("client_id")),
                "device_code", ctx.vault().decrypt(String.valueOf(row.get("secret")), bound(id))));
        if (a.status() == 200) return signedIn(id, a);
        switch (a.error()) {
            case "authorization_pending" -> { ctx.db().update("UPDATE docflow_ms_signin SET next_poll = ?, updated = ? WHERE id = ?", now.plusSeconds(every), now, id); return Map.of("status", "pending"); }
            case "slow_down" -> { ctx.db().update("UPDATE docflow_ms_signin SET poll_every = ?, next_poll = ?, updated = ? WHERE id = ?", every + 5, now.plusSeconds(every + 5L), now, id); return Map.of("status", "pending"); }
            case "authorization_declined" -> { return fail(id, "The sign-in was declined at Microsoft. If Microsoft asked for an administrator's approval, ask your Microsoft 365 administrator to approve One CommandCenter once, then sign in again."); }
            case "expired_token" -> { return fail(id, "The code ran out before the sign-in was finished: choose Sign in with Microsoft again."); }
            default -> { return fail(id, "Microsoft refused the sign-in: " + a.description()); }
        }
    }

    private Map<String, Object> signedIn(String id, Answer a) {
        Object refresh = a.json().get("refresh_token");
        if (refresh == null) return fail(id, "Microsoft gave no lasting sign-in (offline access): check the app's permissions, then sign in again.");
        Map<String, Object> claims = claims(String.valueOf(a.json().getOrDefault("id_token", "")));
        String account = String.valueOf(claims.getOrDefault("preferred_username", claims.getOrDefault("email", claims.getOrDefault("name", "Microsoft account"))));
        String tenant = String.valueOf(claims.getOrDefault("tid", "organizations"));
        Instant now = Instant.now();
        ctx.db().update("UPDATE docflow_ms_signin SET status = 'signed_in', account = ?, tenant = ?, secret = ?, last_error = NULL, updated = ? WHERE id = ?",
                cut(account, 320), cut(tenant, 80), ctx.vault().encrypt(String.valueOf(refresh), bound(id)), now, id);
        if (a.json().get("access_token") != null) tokens.put(id, new Token(String.valueOf(a.json().get("access_token")), now.plusSeconds(number(a.json().get("expires_in"), 3600))));
        return Map.of("status", "signed_in", "account", account);
    }

    private Map<String, Object> fail(String id, String message) {
        ctx.db().update("UPDATE docflow_ms_signin SET status = 'failed', secret = NULL, last_error = ?, updated = ? WHERE id = ?", cut(message, 1000), Instant.now(), id);
        return Map.of("status", "failed", "message", message);
    }

    /**
     * An access token for reading the inbox: the one in memory while it lasts, else a new one from the lasting
     * sign-in (Microsoft gives a new lasting sign-in with it, which is kept). When Microsoft no longer accepts the
     * sign-in (password changed, access removed), the inbox says to sign in again.
     */
    public String accessToken(String id) throws IOException {
        Token t = tokens.get(id);
        Instant now = Instant.now();
        if (t != null && now.isBefore(t.expires().minus(SKEW))) return t.access();
        Map<String, Object> row = row(id);
        if (row == null) throw new OoaException("DOCS.MS_SIGNIN", "This inbox's Microsoft sign-in is not known", "Open the process, step 1, and choose Sign in with Microsoft again.");
        String account = String.valueOf(row.get("account"));
        if (!"signed_in".equals(row.get("status")))
            throw new OoaException("DOCS.MS_SIGNIN", "The Microsoft sign-in for " + account + " no longer works", "Open the process, step 1, and choose Sign in with Microsoft again.");
        Answer a = post("/" + URLEncoder.encode(String.valueOf(row.get("tenant")), StandardCharsets.UTF_8) + "/oauth2/v2.0/token", Map.of("grant_type", "refresh_token",
                "client_id", String.valueOf(row.get("client_id")), "scope", SCOPE, "refresh_token", ctx.vault().decrypt(String.valueOf(row.get("secret")), bound(id))));
        if (a.status() != 200 || a.json().get("access_token") == null) {
            if (a.error().equals("invalid_grant") || a.error().equals("interaction_required")) {
                ctx.db().update("UPDATE docflow_ms_signin SET status = 'expired', last_error = ?, updated = ? WHERE id = ?", cut("Microsoft no longer accepts this sign-in: " + a.description(), 1000), now, id);
                tokens.remove(id);
                throw new OoaException("DOCS.MS_SIGNIN", "The Microsoft sign-in for " + account + " no longer works: " + a.description(), "Open the process, step 1, and choose Sign in with Microsoft again.");
            }
            throw new IOException("Microsoft sign-in for " + account + " failed: " + a.description());
        }
        Object refresh = a.json().get("refresh_token");
        if (refresh != null) ctx.db().update("UPDATE docflow_ms_signin SET secret = ?, last_used = ?, updated = ? WHERE id = ?", ctx.vault().encrypt(String.valueOf(refresh), bound(id)), now, now, id);
        else ctx.db().update("UPDATE docflow_ms_signin SET last_used = ? WHERE id = ?", now, id);
        Token fresh = new Token(String.valueOf(a.json().get("access_token")), now.plusSeconds(number(a.json().get("expires_in"), 3600)));
        tokens.put(id, fresh);
        return fresh.access();
    }

    /** A sign-in as kept (without its secrets), or null. */
    public Map<String, Object> row(String id) {
        if (id == null || !id.matches("ms[0-9a-f]{24}")) return null;
        return ctx.db().one("SELECT * FROM docflow_ms_signin WHERE id = ?", id);
    }

    /** Unfinished and failed sign-ins are forgotten after a day (at most once an hour). */
    public void cleanUp() {
        Instant now = Instant.now();
        if (now.isBefore(cleaned.plus(Duration.ofHours(1)))) return;
        cleaned = now;
        ctx.db().update("DELETE FROM docflow_ms_signin WHERE status IN ('pending', 'failed') AND created < ?", now.minus(Duration.ofDays(1)));
    }

    // ------------------------------------------------------------------ helpers

    @SuppressWarnings("unchecked")
    private Answer post(String path, Map<String, String> form) throws IOException {
        URI uri = URI.create(login() + path);
        ctx.checkHost(uri.getHost(), "Microsoft sign-in");
        StringJoiner body = new StringJoiner("&");
        form.forEach((k, v) -> body.add(URLEncoder.encode(k, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(v, StandardCharsets.UTF_8)));
        HttpResponse<String> r;
        try {
            r = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted");
        }
        Object json;
        try { json = JsonLite.parse(r.body()); } catch (IllegalArgumentException e) { json = null; }
        if (!(json instanceof Map<?, ?> m)) throw new IOException("Microsoft sign-in (" + uri.getHost() + ") answered HTTP " + r.statusCode() + " without JSON");
        return new Answer(r.statusCode(), (Map<String, Object>) m);
    }

    /**
     * The claims of the ID token Microsoft returned to this server over TLS (its own token endpoint, so its signature
     * need not be checked to read the account name and directory; nothing else is taken from it).
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> claims(String idToken) {
        String[] p = idToken.split("\\.");
        if (p.length < 2) return Map.of();
        try {
            Object o = JsonLite.parse(new String(Base64.getUrlDecoder().decode(p[1]), StandardCharsets.UTF_8));
            return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        } catch (IllegalArgumentException e) { return Map.of(); }
    }

    /** What a sign-in's secret is bound to (the same in TenantMove and VaultAdmin.reencryptAll). */
    static String bound(String id) { return "ms-signin:" + id; }
    private static long number(Object o, long fallback) {
        if (o instanceof Number n) return n.longValue();
        try { return o == null ? fallback : Long.parseLong(String.valueOf(o)); } catch (NumberFormatException e) { return fallback; }
    }
    private static Instant instant(Object o) {
        if (o instanceof Instant i) return i;
        if (o instanceof java.sql.Timestamp ts) return ts.toInstant();
        return o == null ? Instant.EPOCH : Instant.parse(String.valueOf(o));
    }
    private static String cut(String s, int max) { return s.length() > max ? s.substring(0, max) : s; }
    private static String hex(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
