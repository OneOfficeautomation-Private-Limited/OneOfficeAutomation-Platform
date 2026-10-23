package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * HTTP API and web console for Control Room. Browser sessions use an HttpOnly, SameSite=Strict cookie plus a required
 * X-OOA-Request header on every change (blocks cross-site request forgery); API clients use "Authorization: Bearer".
 * Agents authenticate with their device id and secret.
 */
public final class ControlRoomServer {
    static final int MAX_BODY = 5 * 1024 * 1024;
    public static final String COOKIE = "ooa_cr";
    private final ControlRoom cr;
    private final McpServer mcp;
    private final Sso sso;
    private final String host;
    private final int port;
    private HttpServer server;
    private ScheduledExecutorService clock;
    private final Map<String, HttpHandler> mounts = new LinkedHashMap<>();

    public ControlRoomServer(ControlRoom cr, String host, int port) { this.cr = cr; this.host = host; this.port = port; this.mcp = new McpServer(cr); this.sso = new Sso(cr); }

    /** The address people use to reach the Command Center (Settings: public_url), for single sign-on redirects. */
    private String origin(HttpExchange ex) {
        String pub = String.valueOf(cr.platform.setting("public_url")).trim().replaceAll("/+$", "");
        if (!pub.isEmpty()) return pub;
        String host = ex.getRequestHeaders().getFirst("Host");
        return "http://" + (host == null ? "localhost" : host);
    }

    private void html(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = ("<!doctype html><html><head><meta charset=\"utf-8\"><title>Signing in</title>" + body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, b.length);
        try (var os = ex.getResponseBody()) { os.write(b); }
    }

    private static String esc(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }

    /** Serves another application under a path prefix on the same port (used by `ooa server` for Studio at /studio). Call before start(). */
    public ControlRoomServer mount(String prefix, HttpHandler handler) { mounts.put(prefix, handler); return this; }

    public void start() throws IOException {
        try { server = HttpServer.create(new InetSocketAddress(host, port), 0); }
        catch (java.net.BindException e) {
            throw new OoaException("CR.PORT_IN_USE", "Port " + port + " is already in use; another Command Center may still be running", "Stop it, or start with --port " + (port + 1) + ".");
        }
        server.setExecutor(Executors.newFixedThreadPool(16, r -> { Thread t = new Thread(r, "ooa-cr-http"); t.setDaemon(true); return t; }));
        server.createContext("/", this::handle);
        mounts.forEach(server::createContext);
        server.start();
        clock = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "ooa-cr-clock"); t.setDaemon(true); return t; });
        clock.scheduleAtFixedRate(() -> { try { cr.tickSchedules(); cr.reap(); } catch (RuntimeException ignored) { } }, 5, 15, TimeUnit.SECONDS);
        clock.scheduleAtFixedRate(() -> { try { cr.platform.dispatch(); } catch (RuntimeException ignored) { } }, 3, 3, TimeUnit.SECONDS);
        clock.scheduleAtFixedRate(() -> { try { cr.workflows.tick(); } catch (RuntimeException ignored) { } }, 4, 5, TimeUnit.SECONDS);
        clock.scheduleWithFixedDelay(() -> { try { cr.triggerPollers.tick(); } catch (RuntimeException ignored) { } }, 7, 5, TimeUnit.SECONDS);
    }

    public int port() { return server.getAddress().getPort(); }

    public void stop() { if (clock != null) clock.shutdownNow(); if (server != null) server.stop(0); }

    // ------------------------------------------------------------------
    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            if (path.startsWith("/api/")) api(ex, path.substring(5));
            else serveStatic(ex, path);
        } catch (OoaException e) {
            int status = switch (e.code()) {
                case "CR.UNAUTHENTICATED", "CR.LOGIN_FAILED" -> 401;
                case "CR.FORBIDDEN", "CR.PASSWORD_CHANGE_REQUIRED", "VAULT.DENIED", "CR.CSRF" -> 403;
                case "CR.NOT_FOUND", "VAULT.NOT_FOUND" -> 404;
                case "CR.LOCKED", "CR.CONFLICT", "CR.NOT_CHECKED_OUT", "CR.DUPLICATE_ID", "CR.IN_USE", "CR.DELETED" -> 409;
                case "CR.POLICY_VIOLATION" -> 422;
                case "CR.LOCKED_OUT" -> 429;
                default -> 400;
            };
            sendJson(ex, status, Map.of("error", e.toMap()));
        } catch (RuntimeException | Error e) {
            // Any failure still gets an answer, so a client never sees a dropped connection.
            e.printStackTrace();
            try { sendJson(ex, 500, Map.of("error", Map.of("code", "CR.INTERNAL", "message", "Internal error; see the Command Center console log"))); }
            catch (RuntimeException | IOException ignored) { /* the response had already started */ }
        } finally { ex.close(); }
    }

    static Map<String, String> query(HttpExchange ex) {
        Map<String, String> q = new HashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw != null) for (String kv : raw.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0) q.put(URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8), URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
        }
        return q;
    }

    static Object body(HttpExchange ex) throws IOException {
        byte[] b = ex.getRequestBody().readNBytes(MAX_BODY + 1);
        if (b.length > MAX_BODY) throw new OoaException("CR.TOO_LARGE", "Request body exceeds 5 MB");
        String t = new String(b, StandardCharsets.UTF_8);
        if (t.isBlank()) return new LinkedHashMap<>();
        try { return JsonLite.parse(t); } catch (IllegalArgumentException e) { throw new OoaException("CR.BAD_JSON", "Request body is not valid JSON"); }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) { if (o instanceof Map) return (Map<String, Object>) o; throw new OoaException("CR.BAD_JSON", "Expected a JSON object"); }

    static String ip(HttpExchange ex) { return ex.getRemoteAddress().getAddress().getHostAddress(); }

    static String token(HttpExchange ex) {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) return auth.substring(7).trim();
        String cookie = ex.getRequestHeaders().getFirst("Cookie");
        if (cookie != null) for (String c : cookie.split(";")) { String[] kv = c.trim().split("=", 2); if (kv.length == 2 && kv[0].equals(COOKIE)) return kv[1]; }
        return null;
    }

    private ControlRoom.User user(HttpExchange ex, boolean allowMustChange) {
        String t = token(ex);
        ControlRoom.User u = t != null && t.startsWith("ooak_") ? cr.platform.apiKeyUser(t) : cr.session(t);
        if (u == null) throw new OoaException("CR.UNAUTHENTICATED", "Please sign in", null);
        boolean viaCookie = ex.getRequestHeaders().getFirst("Authorization") == null;
        if (viaCookie && !ex.getRequestMethod().equals("GET") && !"1".equals(ex.getRequestHeaders().getFirst("X-OOA-Request")))
            throw new OoaException("CR.CSRF", "Missing request header X-OOA-Request");
        if (u.mustChange() && !allowMustChange) throw new OoaException("CR.PASSWORD_CHANGE_REQUIRED", "Change your password before continuing");
        return u;
    }

    private Map<String, Object> device(HttpExchange ex) {
        Map<String, Object> d = cr.device(ex.getRequestHeaders().getFirst("X-OOA-Device"), ex.getRequestHeaders().getFirst("X-OOA-Device-Secret"));
        if (d == null) throw new OoaException("CR.UNAUTHENTICATED", "Unknown or disabled runner", "Re-register the One Runner with a new registration key.");
        return d;
    }

    @SuppressWarnings("unchecked")
    private void api(HttpExchange ex, String route) throws IOException {
        String m = ex.getRequestMethod();
        String ip = ip(ex);
        Map<String, String> q = query(ex);
        String[] seg = route.split("/");

        // ---------- public ----------
        if (m.equals("POST") && route.equals("auth/login")) {
            Map<String, Object> b = map(body(ex));
            Map<String, Object> r = cr.login((String) b.get("username"), (String) b.get("password"), ip);
            ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + r.get("token") + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=28800");
            sendJson(ex, 200, r);
            return;
        }
        if (m.equals("POST") && route.equals("auth/token")) {
            // an API key exchanged for a session token
            sendJson(ex, 200, cr.tokenFromApiKey((String) map(body(ex)).get("api_key"), ip));
            return;
        }
        if (m.equals("GET") && route.equals("auth/token")) { sendJson(ex, 200, cr.verifyToken(token(ex))); return; }
        if (seg[0].equals("apitasks") && seg.length >= 3 && (seg[2].equals("run") || seg[2].equals("runs"))) {
            String t = ex.getRequestHeaders().getFirst("X-OOA-Task-Token");
            if (t == null) t = token(ex);
            if (m.equals("POST") && seg.length == 3 && seg[2].equals("run")) {
                Map<String, Object> b = map(body(ex));
                Map.Entry<Boolean, Map<String, Object>> r = cr.apiTasks.run(seg[1], t, b.get("inputs") instanceof Map<?, ?> im ? (Map<String, Object>) im : Map.of(), ip);
                sendJson(ex, r.getKey() ? 200 : 202, r.getValue());
            } else if (m.equals("GET") && seg.length == 4 && seg[2].equals("runs")) sendJson(ex, 200, cr.apiTasks.poll(seg[1], t, seg[3], ip));
            else notFound(ex);
            return;
        }
        if (m.equals("GET") && route.equals("auth/sso")) { sendJson(ex, 200, sso.info()); return; }
        if (m.equals("GET") && route.equals("auth/sso/start")) {
            ex.getResponseHeaders().set("Location", sso.start(origin(ex) + "/api/auth/sso/callback"));
            ex.getResponseHeaders().set("Cache-Control", "no-store");
            ex.sendResponseHeaders(302, -1);
            return;
        }
        if (m.equals("GET") && route.equals("auth/sso/callback")) {
            try {
                if (q.get("error") != null) throw new OoaException("SSO.DENIED", "The identity provider did not sign you in: " + q.get("error") + (q.get("error_description") != null ? " (" + q.get("error_description") + ")" : ""));
                Map<String, Object> r = sso.callback(q.get("code"), q.get("state"), origin(ex) + "/api/auth/sso/callback", ip);
                ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + r.get("token") + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=28800");
                // A same-site page moves on to the console, so the browser sends the new SameSite=Strict cookie.
                html(ex, 200, "<meta http-equiv=\"refresh\" content=\"0;url=/\"></head><body><p>Signed in. <a href=\"/\">Continue to One Command Center</a></p></body></html>");
            } catch (OoaException e) {
                html(ex, 400, "</head><body style=\"font-family:sans-serif;padding:40px\"><h1>Sign-in did not work</h1><p>" + esc(e.getMessage()) + "</p>"
                        + (e.suggestedFix() == null ? "" : "<p>" + esc(e.suggestedFix()) + "</p>") + "<p><a href=\"/\">Back to the sign-in page</a></p></body></html>");
            }
            return;
        }
        if (m.equals("POST") && seg.length == 2 && seg[0].equals("hooks")) {
            String secret = ex.getRequestHeaders().getFirst("X-OOA-Secret");
            sendJson(ex, 202, cr.webhook(seg[1], secret, body(ex), ip));
            return;
        }
        if (m.equals("GET") && route.equals("health")) {
            Map<String, Object> health = new LinkedHashMap<>();
            health.put("status", "ok");
            // Studio works on this machine's private files, so it is only offered to browsers on this machine.
            if (mounts.containsKey("/studio") && ex.getRemoteAddress().getAddress().isLoopbackAddress()) health.put("studio", "/studio/");
            sendJson(ex, 200, health);
            return;
        }

        // ---------- agents ----------
        if (seg[0].equals("agent")) {
            if (m.equals("POST") && route.equals("agent/register")) {
                Map<String, Object> b = map(body(ex));
                sendJson(ex, 200, cr.registerDevice((String) b.get("key"), (String) b.get("name"), (String) b.get("hostname"), (String) b.get("os"), (String) b.get("agent_version"), ip));
                return;
            }
            Map<String, Object> d = device(ex);
            if (m.equals("POST") && route.equals("agent/heartbeat")) sendJson(ex, 200, cr.heartbeat(d, map(body(ex))));
            else if (m.equals("POST") && route.equals("agent/jobs/next")) { Map<String, Object> job = cr.claimNext(d); sendJson(ex, 200, job == null ? Map.of("job", false) : Map.of("job", job)); }
            else if (m.equals("POST") && seg.length == 4 && seg[1].equals("runs") && seg[3].equals("events")) { cr.appendEvents(d, seg[2], (List<Object>) map(body(ex)).getOrDefault("events", List.of())); sendJson(ex, 200, Map.of("ok", true)); }
            else if (m.equals("POST") && seg.length == 4 && seg[1].equals("runs") && seg[3].equals("finish")) { cr.finish(d, seg[2], map(map(body(ex)).getOrDefault("result", Map.of()))); sendJson(ex, 200, Map.of("ok", true)); }
            else if (m.equals("POST") && route.equals("agent/credential")) {
                Map<String, Object> b = map(body(ex));
                String ref = (String) b.get("ref");
                sendJson(ex, 200, Map.of("value", cr.credentialForRun(d, (String) b.get("run_id"), ref), "password_only", cr.vaultAdmin.passwordOnly(ref)));
            }
            else if (m.equals("POST") && route.equals("agent/trigger-fired")) {
                Map<String, Object> b = map(body(ex));
                sendJson(ex, 200, b.get("event") instanceof Map<?, ?> e ? cr.localFired(d, (String) b.get("trigger_id"), (Map<String, Object>) e) : cr.fileFired(d, (String) b.get("trigger_id"), (String) b.get("file")));
            }
            else if (m.equals("POST") && route.equals("agent/trigger-status")) { Map<String, Object> b = map(body(ex)); cr.localTriggerStatus(d, (String) b.get("trigger_id"), (String) b.get("error")); sendJson(ex, 200, Map.of("ok", true)); }
            else sendJson(ex, 404, Map.of("error", Map.of("code", "CR.NOT_FOUND", "message", "Unknown agent route")));
            return;
        }

        // ---------- signed-in users ----------
        if (route.equals("auth/me") && m.equals("GET")) { sendJson(ex, 200, cr.me(user(ex, true))); return; }
        if (route.equals("auth/logout") && m.equals("POST")) {
            ControlRoom.User u = user(ex, true);
            cr.logout(token(ex), u, ip);
            ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");
            sendJson(ex, 200, Map.of("ok", true));
            return;
        }
        if (route.equals("auth/refresh") && m.equals("POST")) {
            ControlRoom.User u = user(ex, true);
            Map<String, Object> r = cr.refreshToken(token(ex), u, ip);
            if (ex.getRequestHeaders().getFirst("Authorization") == null)
                ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + r.get("token") + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=28800");
            sendJson(ex, 200, r);
            return;
        }
        if (route.equals("auth/password") && m.equals("POST")) {
            ControlRoom.User u = user(ex, true);
            Map<String, Object> b = map(body(ex));
            cr.changePassword(u, (String) b.get("current"), (String) b.get("new"), token(ex), ip);
            sendJson(ex, 200, Map.of("ok", true));
            return;
        }
        ControlRoom.User u = user(ex, false);
        switch (seg[0]) {
            case "dashboard" -> sendJson(ex, 200, cr.dashboard(u));
            case "license" -> sendJson(ex, 200, cr.platform.license(u));
            case "catalog" -> sendJson(ex, 200, Map.of("actions", cr.actionNames()));
            case "mcp" -> {
                if (!m.equals("POST")) { ex.getResponseHeaders().set("Allow", "POST"); ex.sendResponseHeaders(405, -1); return; }
                Map<String, Object> res = mcp.handle(u, body(ex), ip);
                if (res == null) ex.sendResponseHeaders(202, -1); else sendJson(ex, 200, res);
            }
            case "workflows" -> {
                if (m.equals("GET") && route.equals("workflows/runs") && q.get("reference") != null) sendJson(ex, 200, cr.workflows.run(u, null, q.get("reference")));
                else if (m.equals("GET") && route.equals("workflows/runs")) sendJson(ex, 200, Map.of("runs", cr.workflows.runs(u, q.get("status"))));
                else if (m.equals("GET") && seg.length == 3 && seg[1].equals("runs")) sendJson(ex, 200, cr.workflows.run(u, seg[2], null));
                else if (m.equals("GET") && route.equals("workflows/form")) sendJson(ex, 200, cr.workflows.startForm(u, q.get("path")));
                else if (m.equals("POST") && route.equals("workflows/start")) { Map<String, Object> b = map(body(ex)); sendJson(ex, 200, cr.workflows.start(u, (String) b.get("path"), b.get("data") instanceof Map<?, ?> dm ? (Map<String, Object>) dm : Map.of(), (String) b.get("reference"), ip)); }
                else if (m.equals("POST") && seg.length == 4 && seg[1].equals("runs") && seg[3].equals("cancel")) sendJson(ex, 200, cr.workflows.cancel(u, seg[2], ip));
                else notFound(ex);
            }
            case "tasks" -> {
                if (m.equals("GET") && seg.length == 1) sendJson(ex, 200, Map.of("tasks", cr.workflows.tasks(u, q.get("status"), "true".equals(q.get("all")))));
                else if (m.equals("POST") && seg.length == 3 && (seg[2].equals("claim") || seg[2].equals("release"))) { cr.workflows.claim(u, seg[1], seg[2].equals("release"), ip); sendJson(ex, 200, Map.of("ok", true)); }
                else if (m.equals("POST") && seg.length == 3 && seg[2].equals("complete")) {
                    Map<String, Object> b = map(body(ex));
                    sendJson(ex, 200, cr.workflows.complete(u, seg[1], (String) b.get("outcome"), b.get("answers") instanceof Map<?, ?> a ? (Map<String, Object>) a : Map.of(), (String) b.get("comment"), ip));
                }
                else notFound(ex);
            }
            case "governance" -> sendJson(ex, 200, cr.platform.aiGovernance(u, ControlRoom.intOr(q.get("days"), 30)));
            case "settings" -> {
                if (m.equals("GET")) sendJson(ex, 200, cr.platform.settings());
                else if (m.equals("PUT")) sendJson(ex, 200, cr.platform.updateSettings(u, map(body(ex)), ip));
                else notFound(ex);
            }
            case "roles" -> {
                if (m.equals("GET") && seg.length == 1) sendJson(ex, 200, Map.of("roles", cr.platform.roles(u), "permissions", Rbac.all()));
                else if (m.equals("GET") && seg.length == 2) sendJson(ex, 200, cr.platform.roles(u).stream().filter(r -> seg[1].equals(r.get("name"))).findFirst().orElseThrow(() -> ControlRoom.notFound("Role " + seg[1])));
                else if (m.equals("PUT") && seg.length == 2) { Map<String, Object> b = map(body(ex)); cr.platform.saveRole(u, seg[1], (String) b.get("description"), b.get("permissions"), ip); sendJson(ex, 200, Map.of("ok", true)); }
                else if (m.equals("DELETE") && seg.length == 2) { cr.platform.deleteRole(u, seg[1], ip); sendJson(ex, 200, Map.of("ok", true)); }
                else notFound(ex);
            }
            case "apikeys" -> {
                if (m.equals("GET") && seg.length == 1) sendJson(ex, 200, Map.of("keys", cr.platform.apiKeys(u)));
                else if (m.equals("POST") && seg.length == 1) { Map<String, Object> b = map(body(ex)); sendJson(ex, 200, cr.platform.createApiKey(u, (String) b.get("name"), b.get("days") instanceof Number n ? n.intValue() : 90, ip)); }
                else if (m.equals("DELETE") && seg.length == 2) { cr.platform.deleteApiKey(u, seg[1], ip); sendJson(ex, 200, Map.of("ok", true)); }
                else notFound(ex);
            }
            case "pools" -> {
                if (m.equals("GET") && seg.length == 1) sendJson(ex, 200, Map.of("pools", cr.platform.pools(u)));
                else if (m.equals("GET") && seg.length == 2) sendJson(ex, 200, cr.platform.pools(u).stream().filter(p0 -> seg[1].equals(p0.get("id"))).findFirst().orElseThrow(() -> ControlRoom.notFound("Runner pool")));
                else if (m.equals("POST") && seg.length == 1) { Map<String, Object> b = map(body(ex)); sendJson(ex, 200, cr.platform.savePool(u, null, (String) b.get("name"), (String) b.get("description"), b.get("devices"), ip)); }
                else if (m.equals("PUT") && seg.length == 2) { Map<String, Object> b = map(body(ex)); sendJson(ex, 200, cr.platform.savePool(u, seg[1], (String) b.get("name"), (String) b.get("description"), b.get("devices"), ip)); }
                else if (m.equals("DELETE") && seg.length == 2) { cr.platform.deletePool(u, seg[1], ip); sendJson(ex, 200, Map.of("ok", true)); }
                else notFound(ex);
            }
            case "apitasks" -> {
                if (m.equals("GET") && seg.length == 1) sendJson(ex, 200, Map.of("tasks", cr.apiTasks.list(u)));
                else if (m.equals("POST") && seg.length == 1) sendJson(ex, 200, cr.apiTasks.create(u, map(body(ex)), ip));
                else if (m.equals("POST") && seg.length == 3 && seg[2].equals("token")) sendJson(ex, 200, cr.apiTasks.newToken(u, seg[1], ip));
                else if (m.equals("PATCH") && seg.length == 2) { cr.apiTasks.setEnabled(u, seg[1], Boolean.TRUE.equals(map(body(ex)).get("enabled")), ip); sendJson(ex, 200, Map.of("ok", true)); }
                else if (m.equals("DELETE") && seg.length == 2) { cr.apiTasks.delete(u, seg[1], ip); sendJson(ex, 200, Map.of("ok", true)); }
                else notFound(ex);
            }
            case "teams" -> {
                if (m.equals("GET") && seg.length == 1) sendJson(ex, 200, Map.of("teams", cr.teams.list(u)));
                else if (m.equals("POST") && seg.length == 1) { Map<String, Object> b = map(body(ex)); sendJson(ex, 200, cr.teams.save(u, null, (String) b.get("name"), (String) b.get("description"), ip)); }
                else if (m.equals("PUT") && seg.length == 2) { Map<String, Object> b = map(body(ex)); sendJson(ex, 200, cr.teams.save(u, seg[1], (String) b.get("name"), (String) b.get("description"), ip)); }
                else if (m.equals("DELETE") && seg.length == 2) { cr.teams.delete(u, seg[1], ip); sendJson(ex, 200, Map.of("ok", true)); }
                else if (m.equals("PUT") && seg.length == 3 && seg[2].equals("members")) { cr.teams.setMembers(u, seg[1], (List<Object>) map(body(ex)).get("members"), ip); sendJson(ex, 200, Map.of("ok", true)); }
                else notFound(ex);
            }
            case "vault" -> {
                String locker = q.get("locker"), name = q.get("name");
                if (m.equals("GET") && route.equals("vault/status")) sendJson(ex, 200, cr.vaultAdmin.status(u));
                else if (m.equals("POST") && route.equals("vault/rotate")) sendJson(ex, 200, cr.vaultAdmin.rotate(u, ip));
                else if (m.equals("GET") && route.equals("vault/credential")) sendJson(ex, 200, cr.vaultAdmin.credential(u, locker, name));
                else if (m.equals("GET") && route.equals("vault/requests")) sendJson(ex, 200, Map.of("requests", cr.vaultAdmin.requests(u)));
                else if (m.equals("GET") && route.equals("vault/value")) sendJson(ex, 200, cr.vaultAdmin.unmaskedValue(u, locker, name, q.get("attribute"), ip));
                else if (m.equals("PUT") && route.equals("vault/credential")) { cr.vaultAdmin.saveCredential(u, locker, name, map(body(ex)), ip); sendJson(ex, 200, Map.of("ok", true)); }
                else if (m.equals("DELETE") && route.equals("vault/credential")) { cr.vaultAdmin.deleteCredential(u, locker, name, ip); sendJson(ex, 200, Map.of("ok", true)); }
                else if (m.equals("PUT") && route.equals("vault/my-value")) { Map<String, Object> b = map(body(ex)); cr.vaultAdmin.setMyValue(u, (String) b.get("locker"), (String) b.get("name"), (String) b.get("attribute"), (String) b.get("value"), ip); sendJson(ex, 200, Map.of("ok", true)); }
                else if (m.equals("DELETE") && route.equals("vault/my-value")) { cr.vaultAdmin.deleteMyValue(u, locker, name, q.get("attribute"), ip); sendJson(ex, 200, Map.of("ok", true)); }
                else notFound(ex);
            }
            case "analytics" -> {
                if (m.equals("GET") && seg.length == 1) sendJson(ex, 200, cr.analytics.overview(u, q));
                else if (m.equals("GET") && route.equals("analytics/agent")) sendJson(ex, 200, cr.analytics.agent(u, q.get("path"), q));
                else if (m.equals("GET") && route.equals("analytics/variables")) sendJson(ex, 200, cr.analytics.variables(u, q.get("path"), q));
                else if (m.equals("GET") && route.equals("analytics/export")) {
                    String report = q.getOrDefault("report", "");
                    sendText(ex, 200, "text/csv; charset=utf-8", cr.analytics.csv(u, report, q), "operations-" + report + ".csv");
                }
                else notFound(ex);
            }
            case "queue-models" -> {
                if (m.equals("GET") && seg.length == 1) sendJson(ex, 200, Map.of("models", cr.platform.workItemModels(u)));
                else if (m.equals("POST") && seg.length == 1) sendJson(ex, 200, cr.platform.saveWorkItemModel(u, null, map(body(ex)), ip));
                else if (m.equals("PUT") && seg.length == 2) sendJson(ex, 200, cr.platform.saveWorkItemModel(u, seg[1], map(body(ex)), ip));
                else if (m.equals("DELETE") && seg.length == 2) { cr.platform.deleteWorkItemModel(u, seg[1], ip); sendJson(ex, 200, Map.of("ok", true)); }
                else notFound(ex);
            }
            case "lifecycle" -> {
                if (m.equals("POST") && route.equals("lifecycle/export")) {
                    Map<String, Object> b = map(body(ex));
                    sendJson(ex, 200, cr.lifecycle.export(u, (List<Object>) b.get("paths"), b.get("version"), !Boolean.FALSE.equals(b.get("include_dependencies")), ip));
                } else if (m.equals("POST") && route.equals("lifecycle/import")) {
                    Map<String, Object> b = map(body(ex));
                    sendJson(ex, 200, cr.lifecycle.importBundle(u, b.get("bundle") instanceof Map<?, ?> bm ? (Map<String, Object>) bm : null, (String) b.get("conflict"),
                            Boolean.TRUE.equals(b.get("production")), (String) b.get("target_folder"), ip));
                } else if (m.equals("POST") && route.equals("lifecycle/promote")) sendJson(ex, 200, cr.lifecycle.promote(u, map(body(ex)), ip));
                else notFound(ex);
            }
            case "queues" -> {
                if (m.equals("GET") && seg.length == 1) sendJson(ex, 200, Map.of("queues", cr.platform.queues(u)));
                else if (m.equals("GET") && seg.length == 2) sendJson(ex, 200, cr.platform.queue(u, seg[1]));
                else if (m.equals("PUT") && seg.length == 3 && seg[2].equals("members")) { cr.platform.setQueueMembers(u, seg[1], (List<Object>) map(body(ex)).get("members"), ip); sendJson(ex, 200, Map.of("ok", true)); }
                else if (m.equals("GET") && seg.length == 4 && seg[2].equals("items") && seg[3].equals("export"))
                    sendText(ex, 200, "text/csv; charset=utf-8", cr.platform.exportCsv(u, seg[1], q.get("status")), "work-items.csv");
                else if (m.equals("GET") && seg.length == 4 && seg[2].equals("items")) sendJson(ex, 200, cr.platform.item(u, seg[1], seg[3]));
                else if (m.equals("PUT") && seg.length == 4 && seg[2].equals("items")) { cr.platform.updateItem(u, seg[1], seg[3], map(body(ex)), ip); sendJson(ex, 200, Map.of("ok", true)); }
                else if (m.equals("POST") && seg.length == 4 && seg[2].equals("items") && seg[3].equals("csv")) sendJson(ex, 200, cr.platform.importCsv(u, seg[1], (String) map(body(ex)).get("csv"), ip));
                else if (m.equals("POST") && seg.length == 4 && seg[2].equals("items") && seg[3].equals("xlsx")) { Map<String, Object> b = map(body(ex)); sendJson(ex, 200, cr.platform.importXlsx(u, seg[1], (String) b.get("file"), (String) b.get("sheet"), ip)); }
                else if (m.equals("GET") && seg.length == 3 && seg[2].equals("export")) sendJson(ex, 200, cr.platform.exportQueue(u, seg[1], "true".equals(q.get("items"))));
                else if (m.equals("POST") && route.equals("queues/import")) { Map<String, Object> b = map(body(ex)); sendJson(ex, 200, cr.platform.importQueue(u, b.get("definition") instanceof Map<?, ?> dm ? (Map<String, Object>) dm : null, (String) b.get("name"), ip)); }
                else if (m.equals("POST") && seg.length == 1) sendJson(ex, 200, cr.platform.saveQueue(u, null, map(body(ex)), ip));
                else if (m.equals("PUT") && seg.length == 2) sendJson(ex, 200, cr.platform.saveQueue(u, seg[1], map(body(ex)), ip));
                else if (m.equals("DELETE") && seg.length == 2) { cr.platform.deleteQueue(u, seg[1], ip); sendJson(ex, 200, Map.of("ok", true)); }
                else if (m.equals("POST") && seg.length == 3 && (seg[2].equals("start") || seg[2].equals("stop"))) { cr.platform.setProcessing(u, seg[1], seg[2].equals("start"), ip); sendJson(ex, 200, Map.of("ok", true)); }
                else if (m.equals("GET") && seg.length == 3 && seg[2].equals("items"))
                    sendJson(ex, 200, cr.platform.items(u, seg[1], q.get("status"), q.get("q"), ControlRoom.intOr(q.get("limit"), 100), ControlRoom.intOr(q.get("offset"), 0)));
                else if (m.equals("POST") && seg.length == 3 && seg[2].equals("items")) sendJson(ex, 200, cr.platform.addItems(u, seg[1], (List<Object>) map(body(ex)).get("items"), ip));
                else if (m.equals("POST") && seg.length == 4 && seg[2].equals("items")) { cr.platform.updateItems(u, seg[1], (List<Object>) map(body(ex)).get("ids"), seg[3], ip); sendJson(ex, 200, Map.of("ok", true)); }
                else notFound(ex);
            }
            case "connections" -> {
                if (m.equals("GET") && seg.length == 1) sendJson(ex, 200, Map.of("connections", cr.platform.modelConnections(u)));
                else if (m.equals("POST") && route.equals("connections/test")) sendJson(ex, 200, cr.platform.testModelConnection(u, map(body(ex)), ip));
                else if (m.equals("PUT") && seg.length == 2) { Map<String, Object> b = new LinkedHashMap<>(map(body(ex))); b.put("name", seg[1]); cr.platform.saveModelConnection(u, b, ip); sendJson(ex, 200, Map.of("ok", true)); }
                else if (m.equals("DELETE") && seg.length == 2) { cr.platform.deleteModelConnection(u, seg[1], ip); sendJson(ex, 200, Map.of("ok", true)); }
                else notFound(ex);
            }
            case "calendars" -> {
                if (m.equals("GET") && seg.length == 1) sendJson(ex, 200, Map.of("calendars", cr.platform.calendars(u)));
                else if (m.equals("PUT") && seg.length == 2) { cr.platform.saveCalendar(u, URLDecoder.decode(seg[1], StandardCharsets.UTF_8), map(body(ex)).get("dates"), ip); sendJson(ex, 200, Map.of("ok", true)); }
                else if (m.equals("DELETE") && seg.length == 2) { cr.platform.deleteCalendar(u, URLDecoder.decode(seg[1], StandardCharsets.UTF_8), ip); sendJson(ex, 200, Map.of("ok", true)); }
                else notFound(ex);
            }
            case "toolkits" -> {
                if (m.equals("GET") && seg.length == 1) sendJson(ex, 200, Map.of("toolkits", cr.platform.toolkits(u)));
                else if (m.equals("GET") && seg.length == 3 && seg[2].equals("used-by")) sendJson(ex, 200, Map.of("agents", cr.platform.toolkitUsers(u, seg[1])));
                else if (m.equals("GET") && seg.length == 3 && seg[2].equals("versions")) sendJson(ex, 200, cr.platform.toolkitVersions(u, seg[1]));
                else if (m.equals("POST") && seg.length == 3 && seg[2].equals("update-agents")) { Map<String, Object> b = map(body(ex)); sendJson(ex, 200, cr.platform.updateToolkitRange(u, seg[1], (String) b.get("range"), b.get("paths") instanceof List<?> l ? (List<Object>) l : null, ip)); }
                else if (m.equals("PATCH") && seg.length == 2) { cr.platform.setToolkitEnabled(u, seg[1], Boolean.TRUE.equals(map(body(ex)).get("enabled")), ip); sendJson(ex, 200, Map.of("ok", true)); }
                else notFound(ex);
            }
            case "users" -> {
                if (m.equals("GET") && seg.length == 1) sendJson(ex, 200, cr.usersPage(u, q));
                else if (m.equals("GET") && seg.length == 2) sendJson(ex, 200, cr.userDetail(u, seg[1]));
                else if (m.equals("DELETE") && seg.length == 2) { cr.deleteUser(u, seg[1], ip); sendJson(ex, 200, Map.of("ok", true)); }
                else if (m.equals("POST") && seg.length == 1) { Map<String, Object> b = map(body(ex)); sendJson(ex, 200, cr.createUser(u, (String) b.get("username"), (String) b.get("display_name"), (String) b.get("role"), ip)); }
                else if (m.equals("PATCH") && seg.length == 2) { cr.updateUser(u, seg[1], map(body(ex)), ip); sendJson(ex, 200, Map.of("ok", true)); }
                else if (m.equals("POST") && seg.length == 3 && seg[2].equals("reset-password")) sendJson(ex, 200, cr.resetPassword(u, seg[1], ip));
                else if (m.equals("PUT") && seg.length == 3 && seg[2].equals("default-device")) { cr.setDefaultDevice(u, seg[1], (String) map(body(ex)).get("device_id"), ip); sendJson(ex, 200, Map.of("ok", true)); }
                else notFound(ex);
            }
            case "repo" -> {
                String p = q.get("path");
                if (m.equals("GET") && seg.length == 1) sendJson(ex, 200, Map.of("bots", cr.repoList(u, q.get("q"), q.get("folder"), "true".equals(q.get("deleted")))));
                else if (m.equals("GET") && route.equals("repo/folders")) sendJson(ex, 200, Map.of("folders", cr.folders.list(u)));
                else if (m.equals("POST") && route.equals("repo/folders")) { Map<String, Object> b = map(body(ex)); sendJson(ex, 200, cr.folders.create(u, (String) b.get("path"), (String) b.get("description"), ip)); }
                else if (m.equals("DELETE") && route.equals("repo/folders")) { cr.folders.delete(u, p, ip); sendJson(ex, 200, Map.of("ok", true)); }
                else if (m.equals("POST") && route.equals("repo/folders/rename")) { Map<String, Object> b = map(body(ex)); sendJson(ex, 200, cr.folders.rename(u, (String) b.get("from"), (String) b.get("to"), ip)); }
                else if (m.equals("GET") && route.equals("repo/permissions")) sendJson(ex, 200, Map.of("folder", q.getOrDefault("folder", ""), "permissions", cr.folders.permissions(u, q.get("folder"))));
                else if (m.equals("PUT") && route.equals("repo/permissions")) { Map<String, Object> b = map(body(ex)); cr.folders.setPermissions(u, (String) b.get("folder"), (List<Object>) b.get("permissions"), ip); sendJson(ex, 200, Map.of("ok", true)); }
                else if (m.equals("GET") && route.equals("repo/files")) sendJson(ex, 200, Map.of("files", cr.repoFiles.list(u, q.get("folder"))));
                else if (m.equals("PUT") && route.equals("repo/files")) {
                    // the request body is the file itself
                    byte[] content = ex.getRequestBody().readNBytes((int) RepoFiles.MAX_BYTES + 1);
                    sendJson(ex, 200, cr.repoFiles.put(u, p, content, ex.getRequestHeaders().getFirst("Content-Type"), ip));
                }
                else if (m.equals("GET") && route.equals("repo/files/content")) {
                    Map<String, Object> f = cr.repoFiles.get(u, p);
                    String name = RepoFiles.check(p).substring(RepoFiles.check(p).lastIndexOf('/') + 1);
                    sendBytes(ex, (byte[]) f.get("content"), "application/octet-stream", name);
                }
                else if (m.equals("DELETE") && route.equals("repo/files")) { cr.repoFiles.delete(u, p, ip); sendJson(ex, 200, Map.of("ok", true)); }
                else if (m.equals("POST") && route.equals("repo/move")) { Map<String, Object> b = map(body(ex)); sendJson(ex, 200, cr.moveBot(u, (String) b.get("path"), (String) b.get("folder"), ip)); }
                else if (m.equals("POST") && route.equals("repo/copy")) { Map<String, Object> b = map(body(ex)); sendJson(ex, 200, cr.copyBot(u, (String) b.get("path"), (String) b.get("to"), ip)); }
                else if (m.equals("POST") && route.equals("repo/delete")) sendJson(ex, 200, cr.deleteBot(u, (String) map(body(ex)).get("path"), ip));
                else if (m.equals("POST") && route.equals("repo/recover")) { cr.recoverBot(u, (String) map(body(ex)).get("path"), ip); sendJson(ex, 200, Map.of("ok", true)); }
                else if (m.equals("PUT") && route.equals("repo/labels")) {
                    Map<String, Object> b = map(body(ex));
                    if (!(b.get("version") instanceof Number v)) throw new OoaException("CR.BAD_INPUT", "Give the version to label");
                    cr.setLabel(u, (String) b.get("path"), (String) b.get("label"), v.intValue(), ip); sendJson(ex, 200, Map.of("ok", true));
                }
                else if (m.equals("DELETE") && route.equals("repo/labels")) { cr.removeLabel(u, p, q.get("label"), ip); sendJson(ex, 200, Map.of("ok", true)); }
                else if (m.equals("GET") && route.equals("repo/dependencies")) sendJson(ex, 200, cr.dependencies(u, p, q.getOrDefault("version", "latest")));
                else if (m.equals("POST") && route.equals("repo/analyze")) {
                    Map<String, Object> b = map(body(ex));
                    sendJson(ex, 200, b.get("flow") instanceof Map<?, ?> fm ? cr.codeAnalysis.analyzeFlow(u, (Map<String, Object>) fm) : cr.codeAnalysis.analyze(u, (String) b.get("path"), b.getOrDefault("version", "latest")));
                }
                else if (m.equals("GET") && route.equals("repo/analysis")) sendJson(ex, 200, cr.codeAnalysis.stored(u, p, q.getOrDefault("version", "latest")));
                else if (m.equals("GET") && route.equals("repo/analysis/report")) sendText(ex, 200, "text/csv; charset=utf-8", cr.codeAnalysis.repositoryCsv(u), "code-analysis.csv");
                else if (m.equals("GET") && route.equals("repo/policy")) sendJson(ex, 200, cr.codeAnalysis.policy());
                else if (m.equals("PUT") && route.equals("repo/policy")) sendJson(ex, 200, cr.codeAnalysis.savePolicy(u, map(body(ex)), ip));
                else if (m.equals("GET") && route.equals("repo/bot")) sendJson(ex, 200, cr.getBot(u, p, q.getOrDefault("version", "latest")));
                else if (m.equals("GET") && route.equals("repo/versions")) sendJson(ex, 200, Map.of("versions", cr.versions(u, p)));
                else if (m.equals("POST") && route.equals("repo/roi")) { Map<String, Object> b = map(body(ex)); cr.platform.setMinutesSaved(u, (String) b.get("path"), b.get("minutes_saved"), ip); sendJson(ex, 200, Map.of("ok", true)); }
                else if (m.equals("POST") && route.equals("repo/checkout")) sendJson(ex, 200, cr.checkout(u, (String) map(body(ex)).get("path"), ip));
                else if (m.equals("POST") && route.equals("repo/undo-checkout")) { cr.undoCheckout(u, (String) map(body(ex)).get("path"), ip); sendJson(ex, 200, Map.of("ok", true)); }
                else if (m.equals("POST") && route.equals("repo/checkin")) {
                    Map<String, Object> b = map(body(ex));
                    sendJson(ex, 200, cr.checkin(u, (String) b.get("path"), map(b.get("flow")), (String) b.get("comment"), !Boolean.FALSE.equals(b.get("production")), ip));
                } else if (m.equals("POST") && route.equals("repo/production")) {
                    Map<String, Object> b = map(body(ex));
                    cr.setProduction(u, (String) b.get("path"), ((Number) b.get("version")).intValue(), ip);
                    sendJson(ex, 200, Map.of("ok", true));
                } else notFound(ex);
            }
            case "skills" -> {
                if (m.equals("GET") && seg.length == 1) sendJson(ex, 200, Map.of("skills", cr.promptSkills.list(u)));
                else if (m.equals("GET") && route.equals("skills/templates")) sendJson(ex, 200, Map.of("templates", cr.promptSkills.templates(u)));
                else if (m.equals("POST") && route.equals("skills/from-template")) { Map<String, Object> b = map(body(ex)); sendJson(ex, 200, cr.promptSkills.fromTemplate(u, (String) b.get("template"), (String) b.get("name"), ip)); }
                else if (m.equals("GET") && seg.length == 2) sendJson(ex, 200, cr.promptSkills.get(u, seg[1], q.get("version") == null ? null : Integer.valueOf(q.get("version"))));
                else if (m.equals("PUT") && seg.length == 2) sendJson(ex, 200, cr.promptSkills.save(u, seg[1], map(body(ex)), ip));
                else if (m.equals("DELETE") && seg.length == 2) { cr.promptSkills.delete(u, seg[1], ip); sendJson(ex, 200, Map.of("ok", true)); }
                else notFound(ex);
            }
            case "devices" -> {
                if (m.equals("GET") && seg.length == 1) sendJson(ex, 200, cr.devicesPage(u, q));
                else if (m.equals("GET") && route.equals("devices/runasusers")) sendJson(ex, 200, Map.of("users", cr.runAsUsers(u)));
                else if (m.equals("GET") && seg.length == 2) sendJson(ex, 200, cr.deviceDetail(u, seg[1]));
                else if (m.equals("DELETE") && seg.length == 2) { cr.deleteDevice(u, seg[1], ip); sendJson(ex, 200, Map.of("ok", true)); }
                else if (m.equals("PATCH") && seg.length == 1) { Map<String, Object> b = map(body(ex)); sendJson(ex, 200, cr.updateDevices(u, ControlRoom.csv(b.get("ids")), b, ip)); }
                else if (m.equals("POST") && route.equals("devices/registration-key")) sendJson(ex, 200, cr.createRegistrationKey(u, ((Number) map(body(ex)).getOrDefault("hours", 24)).intValue(), ip));
                else if (m.equals("PATCH") && seg.length == 2) {
                    Map<String, Object> b = map(body(ex));
                    if (b.containsKey("name")) cr.renameDevice(u, seg[1], (String) b.get("name"), ip);
                    if (b.containsKey("enabled")) cr.setDeviceEnabled(u, seg[1], Boolean.TRUE.equals(b.get("enabled")), ip);
                    if (b.containsKey("log_level") || b.containsKey("device_type")) cr.updateDevices(u, List.of(seg[1]), b, ip);
                    sendJson(ex, 200, Map.of("ok", true));
                }
                else notFound(ex);
            }
            case "runs" -> {
                if (m.equals("GET") && seg.length == 1) sendJson(ex, 200, cr.runsPage(u, q));
                else if (m.equals("POST") && route.equals("runs/purge")) sendJson(ex, 200, cr.platform.purgeNow(u, map(body(ex)), ip));
                else if (m.equals("POST") && seg.length == 1) {
                    Map<String, Object> b = map(body(ex));
                    // several targets at once (run-as users and/or runners): one run each
                    if (b.get("run_as_users") instanceof List || b.get("device_ids") instanceof List) { sendJson(ex, 200, cr.deploy(u, b, ip)); return; }
                    sendJson(ex, 200, cr.startRun(u.username(), u.role(), (String) b.get("bot_path"), (String) b.get("device_id"), (String) b.get("pool_id"),
                            (Map<String, Object>) b.getOrDefault("inputs", Map.of()), b.get("source") instanceof String src && src.equals("api") ? "api" : "manual", ip,
                            b.get("priority") instanceof Number pr ? pr.intValue() : 0, (String) b.get("callback_url"), (String) b.get("run_as"), (String) b.get("mode")));
                } else if (m.equals("GET") && seg.length == 2) sendJson(ex, 200, cr.run(u, seg[1], Long.parseLong(q.getOrDefault("after", "0"))));
                else if (m.equals("POST") && seg.length == 3 && seg[2].equals("rerun")) sendJson(ex, 200, cr.rerun(u, seg[1], ip));
                else if (m.equals("POST") && seg.length == 3) { cr.control(u, seg[1], seg[2], ip); sendJson(ex, 200, Map.of("ok", true)); }
                else notFound(ex);
            }
            case "schedules" -> {
                if (m.equals("GET") && seg.length == 1) sendJson(ex, 200, Map.of("schedules", cr.schedules(u)));
                else if (m.equals("POST") && route.equals("schedules/bulk")) sendJson(ex, 200, cr.bulkSchedules(u, map(body(ex)), ip));
                else if (m.equals("GET") && seg.length == 2) sendJson(ex, 200, cr.schedule(u, seg[1]));
                else if (m.equals("POST") && seg.length == 1) sendJson(ex, 200, cr.createSchedule(u, map(body(ex)), ip));
                else if (m.equals("POST") && seg.length == 3 && seg[2].equals("run")) sendJson(ex, 200, cr.runScheduleNow(u, seg[1], ip));
                else if (m.equals("PUT") && seg.length == 2) { cr.updateSchedule(u, seg[1], map(body(ex)), ip); sendJson(ex, 200, Map.of("ok", true)); }
                else if (m.equals("PATCH") && seg.length == 2) { cr.setScheduleEnabled(u, seg[1], Boolean.TRUE.equals(map(body(ex)).get("enabled")), ip); sendJson(ex, 200, Map.of("ok", true)); }
                else if (m.equals("DELETE") && seg.length == 2) { cr.deleteSchedule(u, seg[1], ip); sendJson(ex, 200, Map.of("ok", true)); }
                else notFound(ex);
            }
            case "triggers" -> {
                if (m.equals("GET") && seg.length == 1) sendJson(ex, 200, Map.of("triggers", cr.triggers(u)));
                else if (m.equals("GET") && seg.length == 2) sendJson(ex, 200, cr.trigger(u, seg[1]));
                else if (m.equals("PUT") && seg.length == 2) { cr.updateTrigger(u, seg[1], map(body(ex)), ip); sendJson(ex, 200, Map.of("ok", true)); }
                else if (m.equals("POST") && seg.length == 1) sendJson(ex, 200, cr.createTrigger(u, map(body(ex)), ip));
                else if (m.equals("PATCH") && seg.length == 2) { cr.setTriggerEnabled(u, seg[1], Boolean.TRUE.equals(map(body(ex)).get("enabled")), ip); sendJson(ex, 200, Map.of("ok", true)); }
                else if (m.equals("POST") && seg.length == 3 && (seg[2].equals("pause") || seg[2].equals("resume"))) { cr.setTriggerEnabled(u, seg[1], seg[2].equals("resume"), ip); sendJson(ex, 200, Map.of("ok", true)); }
                else if (m.equals("POST") && seg.length == 3 && seg[2].equals("poll")) { cr.require(u, "triggers.manage", "manage triggers"); sendJson(ex, 200, cr.triggerPollers.pollNow(seg[1])); }
                else if (m.equals("DELETE") && seg.length == 2) { cr.deleteTrigger(u, seg[1], ip); sendJson(ex, 200, Map.of("ok", true)); }
                else notFound(ex);
            }
            case "lockers" -> {
                if (m.equals("GET") && seg.length == 1) sendJson(ex, 200, Map.of("lockers", cr.lockers(u)));
                else if (m.equals("POST") && seg.length == 1) { Map<String, Object> b = map(body(ex)); cr.createLocker(u, (String) b.get("name"), (String) b.get("description"), b.get("consumers"), ip); sendJson(ex, 200, Map.of("ok", true)); }
                else if (m.equals("PATCH") && seg.length == 2) { Map<String, Object> b = map(body(ex)); cr.updateLocker(u, seg[1], b.get("consumers"), b.get("owners"), ip); sendJson(ex, 200, Map.of("ok", true)); }
                else if (m.equals("PUT") && seg.length == 4) { cr.setCredential(u, seg[1], seg[2], seg[3], (String) map(body(ex)).get("value"), ip); sendJson(ex, 200, Map.of("ok", true)); }
                else if (m.equals("DELETE") && seg.length == 4) { cr.deleteCredential(u, seg[1], seg[2], seg[3], ip); sendJson(ex, 200, Map.of("ok", true)); }
                else if (m.equals("PUT") && seg.length == 3 && seg[2].equals("external")) {
                    Map<String, Object> b = new LinkedHashMap<>(map(body(ex)));
                    Object secret = b.remove("secret");
                    cr.linkExternalVault(u, seg[1], b, secret instanceof String sv ? sv : null, ip); sendJson(ex, 200, Map.of("ok", true));
                }
                else if (m.equals("DELETE") && seg.length == 3 && seg[2].equals("external")) { cr.linkExternalVault(u, seg[1], null, null, ip); sendJson(ex, 200, Map.of("ok", true)); }
                else if (m.equals("POST") && seg.length == 3 && seg[2].equals("test")) { Map<String, Object> b = map(body(ex)); sendJson(ex, 200, cr.testExternalVault(u, seg[1], (String) b.get("credential"), (String) b.get("attribute"), ip)); }
                else if (m.equals("PUT") && seg.length == 3 && seg[2].equals("members")) { cr.vaultAdmin.setMembers(u, seg[1], map(body(ex)), ip); sendJson(ex, 200, Map.of("ok", true)); }
                else if (m.equals("DELETE") && seg.length == 2) { cr.vaultAdmin.deleteLocker(u, seg[1], ip); sendJson(ex, 200, Map.of("ok", true)); }
                else notFound(ex);
            }
            case "globals" -> {
                if (m.equals("GET") && seg.length == 1) sendJson(ex, 200, Map.of("globals", cr.globals(u)));
                else if (m.equals("PUT") && seg.length == 2) { Map<String, Object> b = map(body(ex)); cr.setGlobal(u, seg[1], b.get("value"), b.get("changeable") instanceof Boolean c ? c : null, ip); sendJson(ex, 200, Map.of("ok", true)); }
                else if (m.equals("PUT") && seg.length == 3 && seg[2].equals("mine")) { cr.setMyGlobal(u, seg[1], map(body(ex)).get("value"), ip); sendJson(ex, 200, Map.of("ok", true)); }
                else if (m.equals("DELETE") && seg.length == 3 && seg[2].equals("mine")) { cr.deleteMyGlobal(u, seg[1], ip); sendJson(ex, 200, Map.of("ok", true)); }
                else if (m.equals("DELETE") && seg.length == 2) { cr.deleteGlobal(u, seg[1], ip); sendJson(ex, 200, Map.of("ok", true)); }
                else notFound(ex);
            }
            case "audit" -> {
                if (m.equals("GET") && seg.length == 1) sendJson(ex, 200, cr.auditSearch(u, q));
                else if (m.equals("GET") && route.equals("audit/export")) sendText(ex, 200, "text/csv; charset=utf-8", cr.auditCsv(u, q, ip), "audit-trail.csv");
                else if (m.equals("GET") && route.equals("audit/verify")) { cr.require(u, "audit.view", "view the audit trail"); sendJson(ex, 200, cr.audit.verify()); }
                else notFound(ex);
            }
            default -> notFound(ex);
        }
    }

    private static void notFound(HttpExchange ex) throws IOException { sendJson(ex, 404, Map.of("error", Map.of("code", "CR.NOT_FOUND", "message", "Unknown API route"))); }

    private static void securityHeaders(HttpExchange ex) {
        var h = ex.getResponseHeaders();
        h.set("X-Content-Type-Options", "nosniff");
        h.set("X-Frame-Options", "DENY");
        h.set("Referrer-Policy", "no-referrer");
        h.set("Cache-Control", "no-store");
        h.set("Content-Security-Policy", "default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
    }

    /** A text download (CSV) with a file name. */
    static void sendText(HttpExchange ex, int status, String type, String body, String filename) throws IOException {
        securityHeaders(ex);
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    /** A file download; always sent as an attachment so the browser never shows it inside the console. */
    static void sendBytes(HttpExchange ex, byte[] b, String type, String filename) throws IOException {
        securityHeaders(ex);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + filename.replace("\"", "") + "\"");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    static void sendJson(HttpExchange ex, int status, Object body) throws IOException {
        securityHeaders(ex);
        byte[] b = JsonLite.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    private static final Map<String, String> TYPES = Map.of("html", "text/html; charset=utf-8", "js", "text/javascript; charset=utf-8", "css", "text/css; charset=utf-8", "png", "image/png", "svg", "image/svg+xml");

    private void serveStatic(HttpExchange ex, String path) throws IOException {
        String name = path.equals("/") ? "index.html" : path.substring(1);
        if (!name.matches("^[A-Za-z0-9_.-]+$")) { sendJson(ex, 404, Map.of("error", "not found")); return; }
        // The shared design system (ooa-tokens.css, ooa-components.css) lives in /ooa-ui/; Agent Studio serves the same files.
        String folder = name.startsWith("ooa-") && name.endsWith(".css") ? "/ooa-ui/" : "/console/";
        try (InputStream in = ControlRoomServer.class.getResourceAsStream(folder + name)) {
            if (in == null) { sendJson(ex, 404, Map.of("error", "not found")); return; }
            byte[] b = in.readAllBytes();
            securityHeaders(ex);
            ex.getResponseHeaders().set("Content-Type", TYPES.getOrDefault(name.substring(name.lastIndexOf('.') + 1), "application/octet-stream"));
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        }
    }
}
