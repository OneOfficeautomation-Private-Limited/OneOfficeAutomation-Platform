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
    private final String host;
    private final int port;
    private HttpServer server;
    private ScheduledExecutorService clock;
    private final Map<String, HttpHandler> mounts = new LinkedHashMap<>();

    public ControlRoomServer(ControlRoom cr, String host, int port) { this.cr = cr; this.host = host; this.port = port; }

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
                case "CR.LOCKED", "CR.CONFLICT", "CR.NOT_CHECKED_OUT", "CR.DUPLICATE_ID" -> 409;
                case "CR.LOCKED_OUT" -> 429;
                default -> 400;
            };
            sendJson(ex, status, Map.of("error", e.toMap()));
        } catch (RuntimeException e) {
            sendJson(ex, 500, Map.of("error", Map.of("code", "CR.INTERNAL", "message", "Internal error; see the Command Center console log")));
            e.printStackTrace();
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
        ControlRoom.User u = cr.session(t);
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
            else if (m.equals("POST") && route.equals("agent/credential")) { Map<String, Object> b = map(body(ex)); sendJson(ex, 200, Map.of("value", cr.credentialForRun(d, (String) b.get("run_id"), (String) b.get("ref")))); }
            else if (m.equals("POST") && route.equals("agent/trigger-fired")) { Map<String, Object> b = map(body(ex)); sendJson(ex, 200, cr.fileFired(d, (String) b.get("trigger_id"), (String) b.get("file"))); }
            else sendJson(ex, 404, Map.of("error", Map.of("code", "CR.NOT_FOUND", "message", "Unknown agent route")));
            return;
        }

        // ---------- signed-in users ----------
        if (route.equals("auth/me") && m.equals("GET")) { sendJson(ex, 200, ControlRoom.publicUser(user(ex, true))); return; }
        if (route.equals("auth/logout") && m.equals("POST")) {
            ControlRoom.User u = user(ex, true);
            cr.logout(token(ex), u, ip);
            ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");
            sendJson(ex, 200, Map.of("ok", true));
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
            case "catalog" -> sendJson(ex, 200, Map.of("actions", cr.actionNames()));
            case "users" -> {
                if (m.equals("GET") && seg.length == 1) sendJson(ex, 200, Map.of("users", cr.users(u)));
                else if (m.equals("POST") && seg.length == 1) { Map<String, Object> b = map(body(ex)); sendJson(ex, 200, cr.createUser(u, (String) b.get("username"), (String) b.get("display_name"), (String) b.get("role"), ip)); }
                else if (m.equals("PATCH") && seg.length == 2) { cr.updateUser(u, seg[1], map(body(ex)), ip); sendJson(ex, 200, Map.of("ok", true)); }
                else if (m.equals("POST") && seg.length == 3 && seg[2].equals("reset-password")) sendJson(ex, 200, cr.resetPassword(u, seg[1], ip));
                else notFound(ex);
            }
            case "repo" -> {
                String p = q.get("path");
                if (m.equals("GET") && seg.length == 1) sendJson(ex, 200, Map.of("bots", cr.repoList(u, q.get("q"))));
                else if (m.equals("GET") && route.equals("repo/bot")) sendJson(ex, 200, cr.getBot(u, p, q.getOrDefault("version", "latest")));
                else if (m.equals("GET") && route.equals("repo/versions")) sendJson(ex, 200, Map.of("versions", cr.versions(u, p)));
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
            case "devices" -> {
                if (m.equals("GET") && seg.length == 1) sendJson(ex, 200, Map.of("devices", cr.devices(u)));
                else if (m.equals("POST") && route.equals("devices/registration-key")) sendJson(ex, 200, cr.createRegistrationKey(u, ((Number) map(body(ex)).getOrDefault("hours", 24)).intValue(), ip));
                else if (m.equals("PATCH") && seg.length == 2) {
                    Map<String, Object> b = map(body(ex));
                    if (b.containsKey("name")) cr.renameDevice(u, seg[1], (String) b.get("name"), ip);
                    if (b.containsKey("enabled")) cr.setDeviceEnabled(u, seg[1], Boolean.TRUE.equals(b.get("enabled")), ip);
                    sendJson(ex, 200, Map.of("ok", true));
                }
                else notFound(ex);
            }
            case "runs" -> {
                if (m.equals("GET") && seg.length == 1) sendJson(ex, 200, cr.runsPage(u, q));
                else if (m.equals("POST") && seg.length == 1) {
                    Map<String, Object> b = map(body(ex));
                    sendJson(ex, 200, cr.startRun(u.username(), u.role(), (String) b.get("bot_path"), (String) b.get("device_id"), (Map<String, Object>) b.getOrDefault("inputs", Map.of()), "manual", ip));
                } else if (m.equals("GET") && seg.length == 2) sendJson(ex, 200, cr.run(u, seg[1], Long.parseLong(q.getOrDefault("after", "0"))));
                else if (m.equals("POST") && seg.length == 3 && seg[2].equals("rerun")) sendJson(ex, 200, cr.rerun(u, seg[1], ip));
                else if (m.equals("POST") && seg.length == 3) { cr.control(u, seg[1], seg[2], ip); sendJson(ex, 200, Map.of("ok", true)); }
                else notFound(ex);
            }
            case "schedules" -> {
                if (m.equals("GET") && seg.length == 1) sendJson(ex, 200, Map.of("schedules", cr.schedules(u)));
                else if (m.equals("POST") && seg.length == 1) sendJson(ex, 200, cr.createSchedule(u, map(body(ex)), ip));
                else if (m.equals("POST") && seg.length == 3 && seg[2].equals("run")) sendJson(ex, 200, cr.runScheduleNow(u, seg[1], ip));
                else if (m.equals("PATCH") && seg.length == 2) { cr.setScheduleEnabled(u, seg[1], Boolean.TRUE.equals(map(body(ex)).get("enabled")), ip); sendJson(ex, 200, Map.of("ok", true)); }
                else if (m.equals("DELETE") && seg.length == 2) { cr.deleteSchedule(u, seg[1], ip); sendJson(ex, 200, Map.of("ok", true)); }
                else notFound(ex);
            }
            case "triggers" -> {
                if (m.equals("GET") && seg.length == 1) sendJson(ex, 200, Map.of("triggers", cr.triggers(u)));
                else if (m.equals("POST") && seg.length == 1) sendJson(ex, 200, cr.createTrigger(u, map(body(ex)), ip));
                else if (m.equals("PATCH") && seg.length == 2) { cr.setTriggerEnabled(u, seg[1], Boolean.TRUE.equals(map(body(ex)).get("enabled")), ip); sendJson(ex, 200, Map.of("ok", true)); }
                else if (m.equals("DELETE") && seg.length == 2) { cr.deleteTrigger(u, seg[1], ip); sendJson(ex, 200, Map.of("ok", true)); }
                else notFound(ex);
            }
            case "lockers" -> {
                if (m.equals("GET") && seg.length == 1) sendJson(ex, 200, Map.of("lockers", cr.lockers(u)));
                else if (m.equals("POST") && seg.length == 1) { Map<String, Object> b = map(body(ex)); cr.createLocker(u, (String) b.get("name"), (String) b.get("description"), b.get("consumers"), ip); sendJson(ex, 200, Map.of("ok", true)); }
                else if (m.equals("PATCH") && seg.length == 2) { Map<String, Object> b = map(body(ex)); cr.updateLocker(u, seg[1], b.get("consumers"), b.get("owners"), ip); sendJson(ex, 200, Map.of("ok", true)); }
                else if (m.equals("PUT") && seg.length == 4) { cr.setCredential(u, seg[1], seg[2], seg[3], (String) map(body(ex)).get("value"), ip); sendJson(ex, 200, Map.of("ok", true)); }
                else if (m.equals("DELETE") && seg.length == 4) { cr.deleteCredential(u, seg[1], seg[2], seg[3], ip); sendJson(ex, 200, Map.of("ok", true)); }
                else notFound(ex);
            }
            case "globals" -> {
                if (m.equals("GET") && seg.length == 1) sendJson(ex, 200, Map.of("globals", cr.globals(u)));
                else if (m.equals("PUT") && seg.length == 2) { cr.setGlobal(u, seg[1], map(body(ex)).get("value"), ip); sendJson(ex, 200, Map.of("ok", true)); }
                else if (m.equals("DELETE") && seg.length == 2) { cr.deleteGlobal(u, seg[1], ip); sendJson(ex, 200, Map.of("ok", true)); }
                else notFound(ex);
            }
            case "audit" -> {
                if (m.equals("GET") && seg.length == 1) sendJson(ex, 200, Map.of("records", cr.auditLog(u, q.get("q"), Integer.parseInt(q.getOrDefault("limit", "200")))));
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
        try (InputStream in = ControlRoomServer.class.getResourceAsStream("/console/" + name)) {
            if (in == null) { sendJson(ex, 404, Map.of("error", "not found")); return; }
            byte[] b = in.readAllBytes();
            securityHeaders(ex);
            ex.getResponseHeaders().set("Content-Type", TYPES.getOrDefault(name.substring(name.lastIndexOf('.') + 1), "application/octet-stream"));
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        }
    }
}
