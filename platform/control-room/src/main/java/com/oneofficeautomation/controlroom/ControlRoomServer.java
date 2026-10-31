package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;

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
    private final RateLimiter limiter = new RateLimiter();

    /** The request size limit; the server sets it from Settings (Largest upload). */
    static volatile java.util.function.IntSupplier maxBody = () -> 5 * 1024 * 1024;
    public static final String COOKIE = "ooa_cr";
    private final ControlRoom cr;
    private final McpServer mcp;
    private final Sso sso;
    private final String host;
    private final int port;
    private HttpServer server;
    /** With HTTPS on: a plain-HTTP listener on 127.0.0.1 only, for this machine's own runner and Studio (see internalUrl). */
    private HttpServer internal;
    private javax.net.ssl.SSLContext tls;
    private ScheduledExecutorService clock;
    private final Map<String, HttpHandler> mounts = new LinkedHashMap<>();

    public ControlRoomServer(ControlRoom cr, String host, int port) {
        this.cr = cr; this.host = host; this.port = port; this.mcp = new McpServer(cr); this.sso = new Sso(cr);
        // a SaaS region sets one request limit for all its tenants (TenantHost)
        if (cr.tenant == null) maxBody = () -> cr.platform.maxBodyBytes();
    }

    /** A tenant's Command Center inside a SaaS region: no port of its own; the region's server hands it requests (TenantHost). */
    static ControlRoomServer forTenant(ControlRoom cr) { return new ControlRoomServer(cr, null, 0); }

    /** True when the browser reached the Command Center over HTTPS: served here directly, or through a TLS proxy that says so. */
    private static boolean overHttps(HttpExchange ex) { return ex instanceof HttpsExchange || "https".equalsIgnoreCase(ex.getRequestHeaders().getFirst("X-Forwarded-Proto")); }

    /** The Secure cookie flag whenever the Command Center is used over HTTPS, so the session cookie never travels in clear text. */
    private String secureFlag(HttpExchange ex) {
        return overHttps(ex) || String.valueOf(cr.platform.setting("public_url")).toLowerCase(Locale.ROOT).startsWith("https://") ? "; Secure" : "";
    }

    /** The address people use to reach the Command Center (Settings: public_url), for single sign-on redirects. */
    private String origin(HttpExchange ex) {
        String pub = String.valueOf(cr.platform.setting("public_url")).trim().replaceAll("/+$", "");
        if (!pub.isEmpty()) return pub;
        String host = ex.getRequestHeaders().getFirst("Host");
        return (overHttps(ex) ? "https://" : "http://") + (host == null ? "localhost" : host);
    }

    private void html(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = ("<!doctype html><html><head><meta charset=\"utf-8\"><title>Signing in</title>" + body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        writeBody(ex, status, b);
    }

    private static String esc(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }

    /**
     * After a single sign-on: a same-site page that moves on, so the browser sends the new SameSite=Strict cookie, to
     * the page the sign-in started from (the new console comes back to /next/), never another site (safeReturn).
     */
    private static String continuePage(Map<String, Object> r) {
        String to = esc(ControlRoom.safeReturn(r.get("return") instanceof String s ? s : "/"));
        return "<meta http-equiv=\"refresh\" content=\"0;url=" + to + "\"></head><body><p>Signed in. <a href=\"" + to + "\">Continue to One CommandCenter</a></p></body></html>";
    }

    /** Serves another application under a path prefix on the same port (used by `ooa server` for Studio at /studio). Call before start(). */
    public ControlRoomServer mount(String prefix, HttpHandler handler) { mounts.put(prefix, handler); return this; }

    /** Serves HTTPS on the port with this context (from config/ooa-tls.properties). Call before start(). */
    public ControlRoomServer tls(javax.net.ssl.SSLContext ctx) { this.tls = ctx; return this; }

    public boolean https() { return tls != null; }

    /**
     * The address this machine's own runner and Studio use. Without HTTPS it is the port itself; with HTTPS it is a
     * plain-HTTP listener bound to 127.0.0.1 only (other machines cannot reach it), so they need no certificate trust.
     * Call before start(); the listener opens here.
     */
    public String internalUrl() throws IOException {
        if (tls == null) return "http://127.0.0.1:" + port;
        if (internal == null) internal = HttpServer.create(new InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        return "http://127.0.0.1:" + internal.getAddress().getPort();
    }

    public void start() throws IOException {
        try {
            if (tls == null) server = HttpServer.create(new InetSocketAddress(host, port), 0);
            else {
                HttpsServer s = HttpsServer.create(new InetSocketAddress(host, port), 0);
                s.setHttpsConfigurator(new HttpsConfigurator(tls) {
                    @Override public void configure(HttpsParameters params) {
                        javax.net.ssl.SSLParameters sp = tls.getDefaultSSLParameters();
                        sp.setProtocols(new String[] { "TLSv1.3", "TLSv1.2" });   // no TLS 1.0 or 1.1
                        params.setSSLParameters(sp);
                    }
                });
                server = s;
            }
        }
        catch (java.net.BindException e) {
            throw new OoaException("CR.PORT_IN_USE", "Port " + port + " is already in use; another Command Center may still be running", "Stop it, or start with --port " + (port + 1) + ".");
        }
        // one virtual thread per request: runners waiting for work (long polls) cost almost nothing while they wait
        server.setExecutor(Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("ooa-cr-http-", 0).factory()));
        server.createContext("/", this::handle);
        mounts.forEach(server::createContext);
        server.start();
        if (internal != null) {
            internal.setExecutor(Executors.newFixedThreadPool(4, r -> { Thread t = new Thread(r, "ooa-cr-local"); t.setDaemon(true); return t; }));
            internal.createContext("/", this::handle);
            mounts.forEach(internal::createContext);
            internal.start();
        }
        startBackground();
    }

    /** Schedules, triggers, queues, workflows and serverless runs: the work that goes on without requests. */
    void startBackground() {
        clock = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "ooa-cr-clock" + (cr.tenant == null ? "" : "-" + cr.tenant)); t.setDaemon(true); return t; });
        // with several nodes on one database, only the leader runs the background work (Cluster)
        cr.cluster.tick();
        // the outgoing proxy from Settings, checked every minute so a change made on another node arrives here too; it is
        // process-wide, so a SaaS region's tenants do not set it
        if (cr.tenant == null) clock.scheduleAtFixedRate(() -> { try { cr.platform.applyOutboundProxy(); } catch (RuntimeException ignored) { } }, 0, 60, TimeUnit.SECONDS);
        clock.scheduleAtFixedRate(() -> { try { cr.cluster.tick(); } catch (RuntimeException ignored) { } }, Cluster.RENEW_SECONDS, Cluster.RENEW_SECONDS, TimeUnit.SECONDS);
        clock.scheduleAtFixedRate(() -> { try { if (cr.cluster.isLeader()) { cr.tickSchedules(); cr.reap(); } } catch (RuntimeException ignored) { } }, 5, 15, TimeUnit.SECONDS);
        clock.scheduleAtFixedRate(() -> { try { if (cr.cluster.isLeader()) cr.platform.dispatch(); } catch (RuntimeException ignored) { } }, 3, 3, TimeUnit.SECONDS);
        clock.scheduleAtFixedRate(() -> { try { if (cr.cluster.isLeader()) cr.workflows.tick(); } catch (RuntimeException ignored) { } }, 4, 5, TimeUnit.SECONDS);
        cr.serverless.start();
        clock.scheduleWithFixedDelay(() -> { try { if (cr.cluster.isLeader()) cr.triggerPollers.tick(); } catch (RuntimeException ignored) { } }, 7, 5, TimeUnit.SECONDS);
    }

    public int port() { return server.getAddress().getPort(); }

    public void stop() { if (clock != null) clock.shutdownNow(); cr.cluster.release(); if (server != null) server.stop(0); if (internal != null) internal.stop(0); }

    // ------------------------------------------------------------------
    void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            if (path.equals("/.well-known/oauth-protected-resource") || path.equals("/.well-known/oauth-protected-resource/api/mcp")) { protectedResource(ex); return; }
            if (path.startsWith("/api/")) api(ex, path.substring(5));
            else if (path.equals("/next")) { ex.getResponseHeaders().set("Location", "/next/"); securityHeaders(ex); writeBody(ex, 302, new byte[0]); }
            else if (path.startsWith("/next/")) nextConsole(ex, path.substring(6));
            else serveStatic(ex, path);
        } catch (OoaException e) {
            int status = switch (e.code()) {
                case "CR.UNAUTHENTICATED", "CR.LOGIN_FAILED", "CR.MFA_WRONG_CODE", "CR.MFA_EXPIRED" -> 401;
                case "CR.FORBIDDEN", "CR.PASSWORD_CHANGE_REQUIRED", "CR.MFA_SETUP_REQUIRED", "VAULT.DENIED", "CR.CSRF", "CR.LICENSE_REQUIRED", "CR.LICENSE_EXPIRED", "CR.IP_BLOCKED", "CR.FILE_TYPE_BLOCKED" -> 403;
                case "CR.NOT_FOUND", "VAULT.NOT_FOUND" -> 404;
                case "CR.LOCKED", "CR.CONFLICT", "CR.NOT_CHECKED_OUT", "CR.DUPLICATE_ID", "CR.IN_USE", "CR.DELETED", "CR.LICENSE_LIMIT", "LICENSE.TOO_FEW_SEATS" -> 409;
                case "CR.POLICY_VIOLATION" -> 422;
                case "CR.LOCKED_OUT", "CR.RATE_LIMITED" -> 429;
                default -> 400;
            };
            if (status == 401 && ex.getRequestURI().getPath().equals("/api/mcp")) {
                // tells MCP clients where to learn how to get a token (RFC 9728)
                String base = String.valueOf(cr.platform.setting("public_url")).replaceAll("/+$", "");
                if (base.isEmpty()) base = (tls != null ? "https://" : "http://") + ex.getRequestHeaders().getFirst("Host");
                ex.getResponseHeaders().set("WWW-Authenticate", "Bearer resource_metadata=\"" + base + "/.well-known/oauth-protected-resource\"");
            }
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
        int limit = maxBody.getAsInt();
        byte[] b = ex.getRequestBody().readNBytes(limit + 1);
        if (b.length > limit) throw new OoaException("CR.TOO_LARGE", "The request is larger than the Largest upload setting allows", "Raise Settings > Largest upload, or send a smaller file");
        String t = new String(b, StandardCharsets.UTF_8);
        if (t.isBlank()) return new LinkedHashMap<>();
        try { return JsonLite.parse(t); } catch (IllegalArgumentException e) { throw new OoaException("CR.BAD_JSON", "Request body is not valid JSON"); }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) { if (o instanceof Map) return (Map<String, Object>) o; throw new OoaException("CR.BAD_JSON", "Expected a JSON object"); }

    static String ip(HttpExchange ex) { return ex.getRemoteAddress().getAddress().getHostAddress(); }

    /**
     * The client's address: the connection's, or, when the connection comes from this computer or from a trusted proxy
     * (Settings: trusted_proxies, e.g. the load balancer), the last address in X-Forwarded-For that is not itself a
     * trusted proxy. A client that is not behind a trusted proxy cannot choose its address with the header.
     */
    static String clientIp(HttpExchange ex, IpAllowList trusted) {
        return clientIp(ip(ex), ex.getRemoteAddress().getAddress().isLoopbackAddress(), ex.getRequestHeaders().getFirst("X-Forwarded-For"), trusted);
    }

    static String clientIp(String peer, boolean peerIsLocal, String fwd, IpAllowList trusted) {
        boolean viaProxy = peerIsLocal || (!trusted.isEmpty() && trusted.allows(peer));
        if (!viaProxy || fwd == null || fwd.isBlank()) return peer;
        String[] hops = fwd.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String h = hops[i].trim();
            if (!h.matches("[0-9A-Fa-f:.]{2,45}")) return peer;   // not an address: do not trust the header
            if (i == 0 || trusted.isEmpty() || !trusted.allows(h)) return h;
        }
        return peer;
    }

    static boolean isLoopback(String address) {
        try { return java.net.InetAddress.getByName(address).isLoopbackAddress(); } catch (Exception e) { return false; }
    }

    static String token(HttpExchange ex) {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) return auth.substring(7).trim();
        String cookie = ex.getRequestHeaders().getFirst("Cookie");
        if (cookie != null) for (String c : cookie.split(";")) { String[] kv = c.trim().split("=", 2); if (kv.length == 2 && kv[0].equals(COOKIE)) return kv[1]; }
        return null;
    }

    /**
     * OAuth protected resource metadata (RFC 9728) for MCP clients: this Command Center's MCP address and the identity
     * provider that issues tokens for it. Empty of authorization servers until MCP over OAuth is set up.
     */
    private void protectedResource(HttpExchange ex) throws IOException {
        String base = String.valueOf(cr.platform.setting("public_url")).replaceAll("/+$", "");
        if (base.isEmpty()) base = (tls != null ? "https://" : "http://") + ex.getRequestHeaders().getFirst("Host");
        String issuer = String.valueOf(cr.platform.setting("sso_issuer")).trim(), audience = String.valueOf(cr.platform.setting("mcp_oauth_audience")).trim();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("resource", audience.isEmpty() ? base + "/api/mcp" : audience);
        m.put("authorization_servers", issuer.isEmpty() || audience.isEmpty() ? List.of() : List.of(issuer));
        m.put("bearer_methods_supported", List.of("header"));
        String scope = String.valueOf(cr.platform.setting("mcp_oauth_scope")).trim();
        if (!scope.isEmpty()) m.put("scopes_supported", List.of(scope));
        m.put("resource_name", "ONEOFFICEAUTOMATION Command Center (MCP)");
        sendJson(ex, 200, m);
    }

    private ControlRoom.User user(HttpExchange ex, boolean allowMustChange) {
        String t = token(ex);
        // MCP clients may send an OAuth access token (a JWT) from the identity provider instead of an API key
        boolean jwt = t != null && !t.startsWith("ooak_") && t.chars().filter(ch -> ch == '.').count() == 2 && ex.getRequestURI().getPath().equals("/api/mcp");
        ControlRoom.User u = jwt ? cr.oauthUser(sso, t, ex.getRemoteAddress().getAddress().getHostAddress()) : t != null && t.startsWith("ooak_") ? cr.platform.apiKeyUser(t) : cr.session(t);
        if (u == null) throw new OoaException("CR.UNAUTHENTICATED", "Please sign in", null);
        boolean viaCookie = ex.getRequestHeaders().getFirst("Authorization") == null;
        if (viaCookie && !Set.of("GET", "HEAD").contains(ex.getRequestMethod()) && !"1".equals(ex.getRequestHeaders().getFirst("X-OOA-Request")))
            throw new OoaException("CR.CSRF", "Missing request header X-OOA-Request");
        if (u.mustChange() && !allowMustChange) throw new OoaException("CR.PASSWORD_CHANGE_REQUIRED", "Change your password before continuing");
        if (!allowMustChange && !t.startsWith("ooak_") && !jwt && cr.mfa.setupNeeded(u))
            throw new OoaException("CR.MFA_SETUP_REQUIRED", "Set up two-step sign-in before continuing", "Your administrator requires it for your account.");
        return u;
    }

    private Map<String, Object> device(HttpExchange ex) {
        Map<String, Object> d = cr.device(ex.getRequestHeaders().getFirst("X-OOA-Device"), ex.getRequestHeaders().getFirst("X-OOA-Device-Secret"));
        if (d == null) throw new OoaException("CR.UNAUTHENTICATED", "Unknown or disabled runner", "Re-register the OneRunner with a new registration key.");
        return d;
    }

    @SuppressWarnings("unchecked")
    private void api(HttpExchange ex, String route) throws IOException {
        String m = "HEAD".equals(ex.getRequestMethod()) ? "GET" : ex.getRequestMethod();   // HEAD is answered like GET, without the body
        String ip = clientIp(ex, IpAllowList.parse(String.valueOf(cr.platform.setting("trusted_proxies"))));
        Map<String, String> q = query(ex);
        String[] seg = route.split("/");
        boolean fromHere = isLoopback(ip);

        // the IP allow list (Settings): this computer and the health checks are always allowed; behind a proxy, the
        // client's own address counts (not the proxy's)
        if (!route.startsWith("health") && !fromHere) {
            IpAllowList allow = IpAllowList.parse(String.valueOf(cr.platform.setting("ip_allow_list")));
            if (!allow.isEmpty() && !allow.allows(ip)) {
                sendJson(ex, 403, Map.of("error", Map.of("code", "CR.IP_BLOCKED", "message", "This address may not use the Command Center", "retryable", false)));
                return;
            }
        }

        // per-address limits on the routes that need no session (a client on this computer without a proxy is not limited)
        String limitGroup = RateLimiter.group(m, route);
        if (limitGroup != null) {
            String who = fromHere ? null : ip;
            if (who != null && !limiter.allow(who, limitGroup)) {
                ex.getResponseHeaders().set("Retry-After", String.valueOf(limiter.retryAfter()));
                sendJson(ex, 429, Map.of("error", Map.of("code", "CR.RATE_LIMITED", "message", "Too many requests from this address; try again in a minute", "retryable", true)));
                return;
            }
        }

        // ---------- public ----------
        if (m.equals("POST") && route.equals("auth/login")) {
            Map<String, Object> b = map(body(ex));
            Map<String, Object> r = cr.login((String) b.get("username"), (String) b.get("password"), (String) b.get("code"), ip);
            if (r.get("token") == null) { sendJson(ex, 200, r); return; }   // two-step sign-in: the code comes next (auth/mfa)
            ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + r.get("token") + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + cr.platform.sessionLength().toSeconds() + secureFlag(ex));
            sendJson(ex, 200, r);
            return;
        }
        if (m.equals("POST") && route.equals("auth/mfa")) {
            Map<String, Object> b = map(body(ex));
            Map<String, Object> r = cr.loginMfa((String) b.get("mfa_token"), (String) b.get("code"), ip);
            ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + r.get("token") + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + cr.platform.sessionLength().toSeconds() + secureFlag(ex));
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
        if (m.equals("GET") && route.equals("auth/sso")) { Map<String, Object> info = new LinkedHashMap<>(sso.info()); info.put("saml", cr.saml.info()); sendJson(ex, 200, info); return; }
        if (m.equals("GET") && route.equals("auth/saml/metadata")) {
            byte[] b = cr.saml.metadata(origin(ex)).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/samlmetadata+xml; charset=utf-8");
            ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close();
            return;
        }
        if (m.equals("GET") && route.equals("auth/saml/start")) {
            ex.getResponseHeaders().set("Location", cr.saml.start(origin(ex), q.get("return")));
            ex.sendResponseHeaders(302, -1); ex.close();
            return;
        }
        if (m.equals("POST") && route.equals("auth/saml/acs")) {
            // the identity provider posts the form back (HTTP-POST binding)
            Map<String, String> form = new HashMap<>();
            for (String pair : new String(ex.getRequestBody().readNBytes(3_000_000), StandardCharsets.UTF_8).split("&")) {
                int i = pair.indexOf('=');
                if (i > 0) form.put(java.net.URLDecoder.decode(pair.substring(0, i), StandardCharsets.UTF_8), java.net.URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
            }
            try {
                Map<String, Object> r = cr.saml.acs(form.get("SAMLResponse"), origin(ex), ip);
                ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + r.get("token") + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + cr.platform.sessionLength().toSeconds() + secureFlag(ex));
                html(ex, 200, continuePage(r));
            } catch (OoaException e) {
                html(ex, 400, "</head><body style=\"font-family:sans-serif;padding:40px\"><h1>Sign-in did not work</h1><p>" + esc(e.getMessage()) + "</p>"
                        + (e.suggestedFix() == null ? "" : "<p>" + esc(e.suggestedFix()) + "</p>") + "<p><a href=\"/\">Back to the sign-in page</a></p></body></html>");
            }
            return;
        }
        if (m.equals("GET") && route.equals("auth/sso/start")) {
            ex.getResponseHeaders().set("Location", sso.start(origin(ex) + "/api/auth/sso/callback", q.get("return")));
            ex.getResponseHeaders().set("Cache-Control", "no-store");
            ex.sendResponseHeaders(302, -1);
            return;
        }
        if (m.equals("GET") && route.equals("auth/sso/callback")) {
            try {
                if (q.get("error") != null) throw new OoaException("SSO.DENIED", "The identity provider did not sign you in: " + q.get("error") + (q.get("error_description") != null ? " (" + q.get("error_description") + ")" : ""));
                Map<String, Object> r = sso.callback(q.get("code"), q.get("state"), origin(ex) + "/api/auth/sso/callback", ip);
                ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + r.get("token") + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + cr.platform.sessionLength().toSeconds() + secureFlag(ex));
                // A same-site page moves on to the console, so the browser sends the new SameSite=Strict cookie.
                html(ex, 200, continuePage(r));
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
        if (m.equals("GET") && route.equals("health/ready")) {
            Map<String, Object> r = cr.readiness();
            sendJson(ex, "ready".equals(r.get("status")) ? 200 : 503, r);
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
            else if (m.equals("POST") && route.equals("agent/rotate")) sendJson(ex, 200, cr.rotateSecret(d, ip));
            else if (m.equals("GET") && route.equals("agent/update/manifest")) { Map<String, Object> mf = cr.runnerUpdates.manifest(); if (mf == null) notFound(ex); else sendJson(ex, 200, mf); }
            else if (m.equals("GET") && route.equals("agent/update/file")) sendBytes(ex, cr.runnerUpdates.file(q.get("name")), "application/java-archive", q.get("name"));
            else if (m.equals("POST") && route.equals("agent/jobs/next")) {
                // ?wait=SECONDS: hold the request until work arrives (at most 25 s); without it, answer at once (older runners)
                long wait = q.get("wait") != null && q.get("wait").matches("\\d{1,3}") ? Long.parseLong(q.get("wait")) * 1000 : 0;
                sendJson(ex, 200, cr.nextJob(d, wait));
            }
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
            else if (m.equals("POST") && (route.equals("agent/logs") || route.equals("agent/screen"))) { cr.runnerCare.receive(d, seg[1], map(body(ex))); sendJson(ex, 200, Map.of("ok", true)); }
            else if (m.equals("POST") && route.equals("agent/trigger-status")) { Map<String, Object> b = map(body(ex)); cr.localTriggerStatus(d, (String) b.get("trigger_id"), (String) b.get("error")); sendJson(ex, 200, Map.of("ok", true)); }
            else sendJson(ex, 404, Map.of("error", Map.of("code", "CR.NOT_FOUND", "message", "Unknown agent route")));
            return;
        }

        // ---------- signed-in users ----------
        if (route.equals("auth/me") && m.equals("GET")) { sendJson(ex, 200, cr.me(user(ex, true))); return; }
        if (route.equals("auth/logout") && m.equals("POST")) {
            ControlRoom.User u = user(ex, true);
            cr.logout(token(ex), u, ip);
            ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0" + secureFlag(ex));
            sendJson(ex, 200, Map.of("ok", true));
            return;
        }
        if (route.equals("auth/refresh") && m.equals("POST")) {
            ControlRoom.User u = user(ex, true);
            Map<String, Object> r = cr.refreshToken(token(ex), u, ip);
            if (ex.getRequestHeaders().getFirst("Authorization") == null)
                ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + r.get("token") + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + cr.platform.sessionLength().toSeconds() + secureFlag(ex));
            sendJson(ex, 200, r);
            return;
        }
        if (route.startsWith("auth/mfa/")) {
            ControlRoom.User u = user(ex, true);
            Map<String, Object> b = m.equals("POST") ? map(body(ex)) : Map.of();
            switch (route) {
                case "auth/mfa/status" -> sendJson(ex, 200, cr.mfa.status(u));
                case "auth/mfa/setup" -> sendJson(ex, 200, cr.mfa.setup(u, ip));
                case "auth/mfa/enable" -> sendJson(ex, 200, cr.mfa.enable(u, (String) b.get("code"), ip));
                case "auth/mfa/disable" -> { cr.mfa.disable(u, (String) b.get("password"), (String) b.get("code"), ip); sendJson(ex, 200, Map.of("ok", true)); }
                default -> notFound(ex);
            }
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
            case "dashboard" -> sendJson(ex, 200, cr.dashboard(u, q.get("tz")));
            // pages optional modules add (Document Automation): what is installed, for the menu's plug-in pages
            case "plugins" -> sendJson(ex, 200, Map.of("plugins", ConsolePlugin.installed()));
            case "changes" -> sendJson(ex, 200, cr.changes(u, q.get("since"), ControlRoom.intOr(q.get("wait"), 0)));
            case "license" -> {
                if (m.equals("GET") && seg.length == 1) sendJson(ex, 200, cr.platform.license(u));
                else if (m.equals("POST") && seg.length == 1) sendJson(ex, 200, cr.licensing.install(u, (String) map(body(ex)).get("content"), ip));
                else if (m.equals("GET") && route.equals("license/assignments")) sendJson(ex, 200, cr.licensing.assignments(u));
                else if (m.equals("GET") && route.equals("license/usage")) sendJson(ex, 200, cr.licensing.usage(u, ControlRoom.intOr(q.get("days"), 90)));
                else if (m.equals("GET") && route.equals("license/usage/export")) sendText(ex, 200, "text/csv; charset=utf-8", cr.licensing.usageCsv(u), "license-use.csv");
                else if (m.equals("PUT") && route.equals("license/assignments")) {
                    Map<String, Object> b = map(body(ex));
                    sendJson(ex, 200, cr.licensing.assign(u, (String) b.get("license_type"), (String) b.get("principal"), !Boolean.FALSE.equals(b.get("assigned")), ip));
                } else notFound(ex);
            }
            case "catalog" -> sendJson(ex, 200, Map.of("actions", cr.actionNames()));
            case "dashboards" -> {
                if (m.equals("GET") && seg.length == 1) sendJson(ex, 200, Map.of("dashboards", cr.dashboards.list(u)));
                else if (m.equals("GET") && route.equals("dashboards/kpis")) sendJson(ex, 200, Map.of("kpis", cr.dashboards.kpis(u)));
                else if (m.equals("POST") && seg.length == 1) sendJson(ex, 200, cr.dashboards.save(u, null, map(body(ex)), ip));
                else if (m.equals("PUT") && seg.length == 2) sendJson(ex, 200, cr.dashboards.save(u, seg[1], map(body(ex)), ip));
                else if (m.equals("DELETE") && seg.length == 2) { cr.dashboards.delete(u, seg[1], ip); sendJson(ex, 200, Map.of("ok", true)); }
                else if (m.equals("GET") && seg.length == 3 && seg[2].equals("data")) sendJson(ex, 200, cr.dashboards.data(u, seg[1], q.get("tz")));
                else notFound(ex);
            }
            case "alerts" -> {
                if (m.equals("GET") && seg.length == 1) sendJson(ex, 200, cr.alerts.list(u, q));
                else if (m.equals("POST") && route.equals("alerts/acknowledge")) sendJson(ex, 200, cr.alerts.acknowledge(u, (List<?>) map(body(ex)).getOrDefault("ids", List.of()), ip));
                else notFound(ex);
            }
            case "alert-rules" -> {
                if (m.equals("GET") && seg.length == 1) sendJson(ex, 200, Map.of("rules", cr.alerts.rules(u)));
                else if (m.equals("POST") && seg.length == 1) sendJson(ex, 200, cr.alerts.saveRule(u, null, map(body(ex)), ip));
                else if (m.equals("PUT") && seg.length == 2) sendJson(ex, 200, cr.alerts.saveRule(u, seg[1], map(body(ex)), ip));
                else if (m.equals("PATCH") && seg.length == 2) { cr.alerts.setEnabled(u, seg[1], Boolean.TRUE.equals(map(body(ex)).get("enabled")), ip); sendJson(ex, 200, Map.of("ok", true)); }
                else if (m.equals("DELETE") && seg.length == 2) { cr.alerts.deleteRule(u, seg[1], ip); sendJson(ex, 200, Map.of("ok", true)); }
                else if (m.equals("POST") && seg.length == 3 && seg[2].equals("test")) sendJson(ex, 200, cr.alerts.test(u, seg[1], ip));
                else notFound(ex);
            }
            case "test-sets" -> {
                if (m.equals("GET") && seg.length == 1) sendJson(ex, 200, Map.of("sets", cr.testSets.list(u, q.get("bot"))));
                else if (m.equals("POST") && seg.length == 1) sendJson(ex, 200, cr.testSets.save(u, null, map(body(ex)), ip));
                else if (m.equals("PUT") && seg.length == 2) sendJson(ex, 200, cr.testSets.save(u, seg[1], map(body(ex)), ip));
                else if (m.equals("DELETE") && seg.length == 2) { cr.testSets.delete(u, seg[1], ip); sendJson(ex, 200, Map.of("ok", true)); }
                else if (m.equals("POST") && seg.length == 3 && seg[2].equals("run")) { Map<String, Object> b = map(body(ex));
                    sendJson(ex, 200, cr.testSets.run(u, seg[1], (String) b.get("device_id"), (String) b.get("pool_id"), b.get("version") == null ? null : String.valueOf(b.get("version")), ip)); }
                else if (m.equals("GET") && seg.length == 3 && seg[2].equals("history")) sendJson(ex, 200, Map.of("runs", cr.testSets.history(u, seg[1])));
                else if (m.equals("GET") && seg.length == 3 && seg[1].equals("runs")) sendJson(ex, 200, cr.testSets.testRun(u, seg[2]));
                else notFound(ex);
            }
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
            case "database" -> {
                if (!m.equals("GET")) { notFound(ex); return; }
                cr.require(u, "settings.manage", "see the database details");
                sendJson(ex, 200, cr.db.info());
            }
            case "settings" -> {
                if (m.equals("POST") && route.equals("settings/syslog-test")) { cr.require(u, "settings.manage", "test the syslog connection"); sendJson(ex, 200, cr.syslog.test(u.username())); }
                else if (m.equals("POST") && route.equals("settings/mail-test")) sendJson(ex, 200, cr.mailer.test(u, (String) map(body(ex)).get("to"), ip));
                else if (m.equals("GET")) sendJson(ex, 200, cr.platform.settings());
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
                else if (m.equals("POST") && seg.length == 1) { Map<String, Object> b = map(body(ex)); sendJson(ex, 200, cr.platform.savePool(u, null, (String) b.get("name"), (String) b.get("description"), b.get("devices"), b.get("owners"), b.get("consumers"), ip)); }
                else if (m.equals("PUT") && seg.length == 2) { Map<String, Object> b = map(body(ex)); sendJson(ex, 200, cr.platform.savePool(u, seg[1], (String) b.get("name"), (String) b.get("description"), b.get("devices"), b.get("owners"), b.get("consumers"), ip)); }
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
                else if (m.equals("GET") && route.equals("analytics/capacity")) sendJson(ex, 200, cr.analytics.capacity(u, q));
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
            case "templates" -> {
                if (m.equals("GET") && seg.length == 1) sendJson(ex, 200, cr.templates.list(u));
                else if (m.equals("POST") && route.equals("templates/install")) {
                    Map<String, Object> b = map(body(ex));
                    sendJson(ex, 200, cr.templates.install(u, (String) b.get("source"), (String) b.get("id"), (String) b.get("folder"), ip));
                } else if (m.equals("POST") && route.equals("templates/share")) {
                    Map<String, Object> b = map(body(ex));
                    sendJson(ex, 200, cr.templates.share(u, (String) b.get("path"), (String) b.get("name"), (String) b.get("description"), (String) b.get("category"), ip));
                } else if (m.equals("POST") && route.equals("templates/catalogue")) sendJson(ex, 200, cr.templates.upload(u, (String) map(body(ex)).get("text"), ip));
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
                else if (m.equals("POST") && seg.length == 4 && seg[2].equals("items")) {
                    Map<String, Object> b = map(body(ex));
                    cr.platform.updateItems(u, seg[1], (List<Object>) b.get("ids"), seg[3], ControlRoom.instantOr(b.get("until")), ip);
                    sendJson(ex, 200, Map.of("ok", true));
                }
                else if (m.equals("POST") && seg.length == 5 && seg[2].equals("items") && seg[4].equals("validate")) sendJson(ex, 200, cr.platform.validateItem(u, seg[1], seg[3], map(body(ex)), ip));
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
                else if (m.equals("GET") && route.equals("toolkits/custom")) sendJson(ex, 200, Map.of("versions", cr.customToolkits.list(u)));
                else if (m.equals("POST") && route.equals("toolkits/custom")) {
                    // the request body is the signed jar itself
                    sendJson(ex, 200, cr.customToolkits.upload(u, ex.getRequestBody().readNBytes(CustomToolkits.MAX_BYTES + 1), ip));
                }
                else if (m.equals("DELETE") && seg.length == 3 && seg[1].equals("custom")) { cr.customToolkits.delete(u, seg[2], ip); sendJson(ex, 200, Map.of("ok", true)); }
                else if (m.equals("GET") && route.equals("toolkits/publishers")) sendJson(ex, 200, Map.of("publishers", cr.customToolkits.publishers(u)));
                else if (m.equals("POST") && route.equals("toolkits/publishers")) { Map<String, Object> b = map(body(ex)); cr.customToolkits.addPublisher(u, (String) b.get("publisher"), (String) b.get("public_key"), ip); sendJson(ex, 200, Map.of("ok", true)); }
                else if (m.equals("DELETE") && seg.length == 3 && seg[1].equals("publishers")) { cr.customToolkits.removePublisher(u, URLDecoder.decode(seg[2], StandardCharsets.UTF_8), ip); sendJson(ex, 200, Map.of("ok", true)); }
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
                else if (m.equals("POST") && seg.length == 3 && seg[2].equals("unlock")) { cr.unlockUser(u, seg[1], ip); sendJson(ex, 200, Map.of("ok", true)); }
                else if (m.equals("POST") && seg.length == 3 && seg[2].equals("reset-mfa")) { cr.mfa.reset(u, seg[1], ip); sendJson(ex, 200, Map.of("ok", true)); }
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
                    sendJson(ex, 200, cr.setOrRequestProduction(u, (String) b.get("path"), ((Number) b.get("version")).intValue(), (String) b.get("comment"), ip));
                } else if (m.equals("GET") && route.equals("repo/production-requests")) sendJson(ex, 200, Map.of("requests", cr.approvals.list(u, q.get("status")), "approval_on", cr.approvals.enabled()));
                else if (m.equals("POST") && route.equals("repo/production-requests")) {
                    Map<String, Object> b = map(body(ex));
                    sendJson(ex, 200, cr.approvals.request(u, (String) b.get("path"), ((Number) b.get("version")).intValue(), (String) b.get("comment"), ip));
                } else if (m.equals("POST") && seg.length == 4 && seg[1].equals("production-requests") && Set.of("approve", "reject", "withdraw").contains(seg[3])) {
                    Map<String, Object> b = map(body(ex));
                    sendJson(ex, 200, seg[3].equals("withdraw") ? cr.approvals.withdraw(u, seg[2], ip) : cr.approvals.decide(u, seg[2], seg[3].equals("approve"), (String) b.get("note"), ip));
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
            case "cluster" -> { cr.require(u, "settings.manage", "see the Command Center nodes"); sendJson(ex, 200, Map.of("nodes", cr.cluster.nodes(), "this_node", cr.cluster.nodeId)); }
            case "backups" -> {
                if (m.equals("GET") && seg.length == 1) sendJson(ex, 200, cr.backups.status(u));
                else if (m.equals("POST") && seg.length == 1) sendJson(ex, 200, cr.backups.backupNow(u, ip));
                else notFound(ex);
            }
            case "git" -> {
                if (m.equals("GET") && route.equals("git/status")) sendJson(ex, 200, cr.gitSync.status(u));
                else if (m.equals("POST") && route.equals("git/sync")) {
                    try { sendJson(ex, 200, cr.gitSync.syncNow(u, ip)); }
                    catch (OoaException e) { throw e; } catch (Exception e) { throw new OoaException("CR.GIT", "Git sync failed: " + e.getMessage()); }
                }
                else notFound(ex);
            }
            case "devices" -> {
                if (m.equals("GET") && seg.length == 1) sendJson(ex, 200, cr.devicesPage(u, q));
                else if (m.equals("GET") && route.equals("devices/runasusers")) sendJson(ex, 200, Map.of("users", cr.runAsUsers(u)));
                else if (m.equals("GET") && seg.length == 2) sendJson(ex, 200, cr.deviceDetail(u, seg[1]));
                else if (m.equals("DELETE") && seg.length == 2) { cr.deleteDevice(u, seg[1], ip); sendJson(ex, 200, Map.of("ok", true)); }
                else if (m.equals("PATCH") && seg.length == 1) { Map<String, Object> b = map(body(ex)); sendJson(ex, 200, cr.updateDevices(u, ControlRoom.csv(b.get("ids")), b, ip)); }
                else if (m.equals("POST") && route.equals("devices/maintenance")) { Map<String, Object> b = map(body(ex)); sendJson(ex, 200, cr.runnerCare.setMaintenance(u, ControlRoom.csv(b.get("ids")), (String) b.get("mode"), ip)); }
                else if (m.equals("POST") && seg.length == 3 && (seg[2].equals("logs") || seg[2].equals("screen"))) sendJson(ex, 200, cr.runnerCare.request(u, seg[1], seg[2], ip));
                else if (m.equals("GET") && seg.length == 3 && (seg[2].equals("logs") || seg[2].equals("screen"))) sendJson(ex, 200, cr.runnerCare.file(u, seg[1], seg[2]));
                else if (m.equals("POST") && route.equals("devices/update")) sendJson(ex, 200, cr.runnerUpdates.request(u, ControlRoom.csv(map(body(ex)).get("ids")), ip));
                else if (m.equals("GET") && route.equals("devices/update")) { Map<String, Object> mf = cr.runnerUpdates.manifest(); sendJson(ex, 200, mf == null ? Map.of("available", false) : Map.of("available", true, "build", mf.get("build"), "version", mf.get("version"), "auto", cr.platform.intSetting("runner_auto_update") == 1)); }
                else if (m.equals("POST") && route.equals("devices/registration-key")) { Map<String, Object> b = map(body(ex)); sendJson(ex, 200, cr.createRegistrationKey(u, ((Number) b.getOrDefault("hours", 24)).intValue(), Boolean.TRUE.equals(b.get("temporary")), ip)); }
                else if (m.equals("PATCH") && seg.length == 2) {
                    Map<String, Object> b = map(body(ex));
                    if (b.containsKey("name")) cr.renameDevice(u, seg[1], (String) b.get("name"), ip);
                    if (b.containsKey("enabled")) cr.setDeviceEnabled(u, seg[1], Boolean.TRUE.equals(b.get("enabled")), ip);
                    if (b.containsKey("nickname") || b.containsKey("description")) cr.runnerCare.describe(u, seg[1], (String) b.get("nickname"), (String) b.get("description"), ip);
                    if (b.containsKey("log_level") || b.containsKey("device_type") || b.containsKey("after_run") || b.containsKey("cpu_max") || b.containsKey("mem_max")) cr.updateDevices(u, List.of(seg[1]), b, ip);
                    sendJson(ex, 200, Map.of("ok", true));
                }
                else notFound(ex);
            }
            case "runs" -> {
                if (m.equals("GET") && seg.length == 1) sendJson(ex, 200, cr.runsPage(u, q));
                else if (m.equals("POST") && route.equals("runs/purge")) sendJson(ex, 200, cr.platform.purgeNow(u, map(body(ex)), ip));
                else if (m.equals("GET") && seg.length == 3 && seg[2].equals("ai-calls")) sendJson(ex, 200, Map.of("calls", cr.platform.aiCallsOfRun(u, seg[1])));
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
            case "connection-kinds" -> {
                if (m.equals("GET") && seg.length == 1) sendJson(ex, 200, Map.of("kinds", cr.connectionKinds(u)));
                else notFound(ex);
            }
            case "saved-connections" -> {
                if (m.equals("GET") && seg.length == 1) sendJson(ex, 200, Map.of("connections", cr.connections(u)));
                else if (m.equals("PUT") && seg.length == 2) { cr.saveConnection(u, seg[1], map(body(ex)), ip); sendJson(ex, 200, Map.of("ok", true)); }
                else if (m.equals("DELETE") && seg.length == 2) { cr.deleteConnection(u, seg[1], ip); sendJson(ex, 200, Map.of("ok", true)); }
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
        if (overHttps(ex)) h.set("Strict-Transport-Security", "max-age=31536000");
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
        writeBody(ex, status, b);
    }

    /** A file download; always sent as an attachment so the browser never shows it inside the console. */
    static void sendBytes(HttpExchange ex, byte[] b, String type, String filename) throws IOException {
        securityHeaders(ex);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + filename.replace("\"", "") + "\"");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        writeBody(ex, 200, b);
    }

    /** Sends a reply body; a HEAD request gets the same status and headers without a body (HTTP does not allow one). */
    static void writeBody(HttpExchange ex, int status, byte[] b) throws IOException {
        if ("HEAD".equalsIgnoreCase(ex.getRequestMethod())) { ex.sendResponseHeaders(status, -1); ex.close(); return; }
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    static void sendJson(HttpExchange ex, int status, Object body) throws IOException {
        securityHeaders(ex);
        byte[] b = JsonLite.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        writeBody(ex, status, b);
    }

    private static final Map<String, String> TYPES = Map.of("html", "text/html; charset=utf-8", "js", "text/javascript; charset=utf-8", "css", "text/css; charset=utf-8", "png", "image/png", "svg", "image/svg+xml",
        "json", "application/json; charset=utf-8");

    /** Files of the rebuilt console (platform/ui's console.html and its assets, built into /studio-next/ with Studio's). */
    private static final java.util.regex.Pattern NEXT_FILE = java.util.regex.Pattern.compile("^assets/[A-Za-z0-9._-]+\\.(js|css|svg|woff2|png)$");
    private static final java.security.SecureRandom NONCES = new java.security.SecureRandom();

    /**
     * The rebuilt console, served beside today's at /next/ while it is built (docs/architecture/12-ui-redesign.md, C0).
     * Its page gets a fresh style nonce each time: the dialogs' one style element carries it, every other inline style
     * is still refused. A copy built without Node.js has no page and says how to build one.
     */
    private void nextConsole(HttpExchange ex, String file) throws IOException {
        boolean page = file.isEmpty() || file.equals("index.html");
        if (!page && !NEXT_FILE.matcher(file).matches()) { sendJson(ex, 404, Map.of("error", "not found")); return; }
        try (InputStream in = ControlRoomServer.class.getResourceAsStream("/studio-next/" + (page ? "console.html" : file))) {
            if (in == null) {
                if (!page) { sendJson(ex, 404, Map.of("error", "not found")); return; }
                securityHeaders(ex);
                ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                writeBody(ex, 503, "The new console was not built into this copy. Build it with Node.js 22 installed: gradlew :cli:installDist".getBytes(StandardCharsets.UTF_8));
                return;
            }
            byte[] b = in.readAllBytes();
            securityHeaders(ex);
            if (page) {
                byte[] r = new byte[16];
                NONCES.nextBytes(r);
                String nonce = Base64.getEncoder().encodeToString(r);
                b = new String(b, StandardCharsets.UTF_8).replace("__CSP_NONCE__", nonce).getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Security-Policy", "default-src 'self'; img-src 'self' data:; style-src 'self' 'nonce-" + nonce
                        + "'; script-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
            }
            ex.getResponseHeaders().set("Content-Type", page ? "text/html; charset=utf-8" : TYPES.getOrDefault(file.substring(file.lastIndexOf('.') + 1), "application/octet-stream"));
            writeBody(ex, 200, b);
        }
    }

    private void serveStatic(HttpExchange ex, String path) throws IOException {
        String name = path.equals("/") ? "index.html" : path.substring(1);
        if (!name.matches("^[A-Za-z0-9_.-]+$")) { sendJson(ex, 404, Map.of("error", "not found")); return; }
        // The shared design system (ooa-tokens.css, ooa-components.css) and the interface languages (ooa-i18n.js and its
        // catalogs) live in /ooa-ui/; Agent Studio serves the same files.
        String folder = name.startsWith("ooa-") && name.matches(".*\\.(css|js|json)$") ? "/ooa-ui/" : "/console/";
        try (InputStream in = ControlRoomServer.class.getResourceAsStream(folder + name)) {
            if (in == null) { sendJson(ex, 404, Map.of("error", "not found")); return; }
            byte[] b = in.readAllBytes();
            securityHeaders(ex);
            ex.getResponseHeaders().set("Content-Type", TYPES.getOrDefault(name.substring(name.lastIndexOf('.') + 1), "application/octet-stream"));
            writeBody(ex, 200, b);
        }
    }
}
