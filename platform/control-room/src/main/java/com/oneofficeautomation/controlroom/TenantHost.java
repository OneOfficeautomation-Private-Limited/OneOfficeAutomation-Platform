package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.sdk.JsonLite;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * The web server of a SaaS region (C3): one port for every tenant. The host name picks the tenant, acme.DOMAIN for
 * tenant acme, and its own Command Center answers (ControlRoomServer, with its own database schema, files and vault).
 * The region's own address (DOMAIN) answers only /healthz. TLS ends in front of it, at the region's load balancer or
 * ingress with a wildcard certificate for *.DOMAIN; it sends X-Forwarded-Proto: https.
 * <p>
 * A host that is no active tenant's gets 404, the same for an unknown and a suspended tenant, so the answer does not
 * tell which address names exist.
 * <p>
 * The operators' tenant API is on the region's own address, signed in with the operator token (its SHA-256 is in the
 * region settings; without it the API is off):
 * <pre>
 *   GET    /api/operator/tenants                    the tenants
 *   POST   /api/operator/tenants                    {slug, name, admin_email, seats}: a new tenant and its admin's one-time password
 *   POST   /api/operator/tenants/NAME/seats           {seats}: change its seats
 *   POST   /api/operator/tenants/NAME/suspend|resume
 *   DELETE /api/operator/tenants/NAME               a suspended tenant, for good
 * </pre>
 */
public final class TenantHost {
    private final Tenants tenants;
    private final String host;
    private final int port;
    private final String operatorTokenSha256;
    private HttpServer server;
    private ScheduledExecutorService housekeeping;

    public TenantHost(Tenants tenants, String host, int port) { this(tenants, host, port, ""); }

    public TenantHost(Tenants tenants, String host, int port, String operatorTokenSha256) {
        this.tenants = tenants; this.host = host; this.port = port; this.operatorTokenSha256 = operatorTokenSha256 == null ? "" : operatorTokenSha256;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.setExecutor(Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("ooa-region-http-", 0).factory()));
        server.createContext("/", this::route);
        tenants.startAll();
        server.start();
        housekeeping = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "ooa-region-housekeeping"); t.setDaemon(true); return t; });
        // database connections of tenants unused for 10 minutes are closed; they reconnect on the next request
        housekeeping.scheduleAtFixedRate(() -> { try { tenants.closeIdle(600_000); } catch (RuntimeException ignored) { } }, 5, 5, TimeUnit.MINUTES);
        // tenants suspended or deleted on another node of the region stop here too
        housekeeping.scheduleAtFixedRate(() -> { try { tenants.syncWithCatalog(); } catch (RuntimeException ignored) { } }, 30, 30, TimeUnit.SECONDS);
    }

    public int port() { return server.getAddress().getPort(); }

    public void stop() {
        if (housekeeping != null) housekeeping.shutdownNow();
        if (server != null) server.stop(0);
    }

    /**
     * The tenant's address name in a Host header, "" for the region's own address (also an IP address or localhost: the
     * node itself, or an internal load balancer), or null for an address not of this region.
     */
    static String slugOf(String hostHeader, String domain) {
        if (hostHeader == null) return null;
        String h = hostHeader.trim().toLowerCase(Locale.ROOT);
        int colon = h.lastIndexOf(':');
        if (colon > 0 && h.indexOf(']') < colon) h = h.substring(0, colon);   // the port
        if (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        if (h.equals(domain) || h.equals("localhost") || h.matches("\\d{1,3}(\\.\\d{1,3}){3}") || h.startsWith("[")) return "";
        if (!h.endsWith("." + domain)) return null;
        String slug = h.substring(0, h.length() - domain.length() - 1);
        return Tenants.SLUG.matcher(slug).matches() ? slug : null;   // one label only: a.b.DOMAIN is no tenant
    }

    private void route(HttpExchange ex) throws IOException {
        try {
            String slug = slugOf(ex.getRequestHeaders().getFirst("Host"), tenants.domain);
            if (slug != null && !slug.isEmpty()) {
                Tenants.Tenant t = tenants.serve(slug);
                if (t != null) { t.server.handle(ex); return; }
            }
            if ("".equals(slug) && ex.getRequestURI().getPath().startsWith("/api/operator/") && !operatorTokenSha256.isEmpty()) { operator(ex); return; }
            if ("".equals(slug) && (ex.getRequestURI().getPath().equals("/healthz") || ex.getRequestURI().getPath().equals("/api/health"))) {
                send(ex, 200, Map.of("status", "ok", "region", tenants.region, "tenants_open", tenants.opened().size()));
                return;
            }
            send(ex, 404, Map.of("error", Map.of("code", "SAAS.NO_TENANT", "message", "No Command Center at this address")));
        } catch (RuntimeException e) {
            e.printStackTrace();
            try { send(ex, 500, Map.of("error", Map.of("code", "CR.INTERNAL", "message", "Internal error"))); } catch (RuntimeException | IOException ignored) { }
        } finally { ex.close(); }
    }

    private void operator(HttpExchange ex) throws IOException {
        String token = ControlRoomServer.token(ex);
        if (token == null || !java.security.MessageDigest.isEqual(Security.sha256(token).getBytes(StandardCharsets.UTF_8), operatorTokenSha256.getBytes(StandardCharsets.UTF_8))) {
            send(ex, 401, Map.of("error", Map.of("code", "CR.UNAUTHENTICATED", "message", "Operator token required")));
            return;
        }
        String rest = ex.getRequestURI().getPath().substring("/api/operator/".length()), m = ex.getRequestMethod();
        String who = "operator";
        try {
            if (rest.equals("tenants") && m.equals("GET")) {
                send(ex, 200, Map.of("region", tenants.region, "tenants", tenants.list().stream().map(Tenants.Info::toMap).toList()));
            } else if (rest.equals("tenants") && m.equals("POST")) {
                Map<String, Object> b = ControlRoomServer.map(ControlRoomServer.body(ex));
                @SuppressWarnings("unchecked") Map<String, Object> seats = b.get("seats") instanceof Map<?, ?> s ? (Map<String, Object>) s : Map.of();
                send(ex, 200, tenants.create(String.valueOf(b.get("slug")), (String) b.get("name"), (String) b.get("admin_email"), seats, who));
            } else if (rest.matches("tenants/[a-z0-9-]+/seats") && m.equals("POST")) {
                @SuppressWarnings("unchecked") Map<String, Object> seats = ControlRoomServer.map(ControlRoomServer.body(ex)).get("seats") instanceof Map<?, ?> s ? (Map<String, Object>) s : Map.of();
                send(ex, 200, tenants.setSeats(rest.split("/")[1], seats, who).toMap());
            } else if (rest.matches("tenants/[a-z0-9-]+/(suspend|resume)") && m.equals("POST")) {
                String[] part = rest.split("/");
                if (part[2].equals("suspend")) tenants.suspend(part[1], who); else tenants.resume(part[1], who);
                send(ex, 200, tenants.get(part[1]).toMap());
            } else if (rest.matches("tenants/[a-z0-9-]+") && m.equals("DELETE")) {
                tenants.delete(rest.substring(8), who);
                send(ex, 200, Map.of("deleted", rest.substring(8)));
            } else send(ex, 404, Map.of("error", Map.of("code", "CR.NOT_FOUND", "message", "Not found")));
        } catch (com.oneofficeautomation.sdk.OoaException e) {
            send(ex, switch (e.code()) { case "SAAS.NO_TENANT" -> 404; case "SAAS.SLUG_TAKEN", "SAAS.NOT_SUSPENDED" -> 409; default -> 400; }, Map.of("error", e.toMap()));
        }
    }

    private static void send(HttpExchange ex, int status, Object body) throws IOException {
        byte[] b = JsonLite.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
    }
}
