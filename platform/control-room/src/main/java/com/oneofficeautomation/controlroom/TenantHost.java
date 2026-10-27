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
 */
public final class TenantHost {
    private final Tenants tenants;
    private final String host;
    private final int port;
    private HttpServer server;
    private ScheduledExecutorService housekeeping;

    public TenantHost(Tenants tenants, String host, int port) { this.tenants = tenants; this.host = host; this.port = port; }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.setExecutor(Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("ooa-region-http-", 0).factory()));
        server.createContext("/", this::route);
        tenants.startAll();
        server.start();
        housekeeping = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "ooa-region-housekeeping"); t.setDaemon(true); return t; });
        // database connections of tenants unused for 10 minutes are closed; they reconnect on the next request
        housekeeping.scheduleAtFixedRate(() -> { try { tenants.closeIdle(600_000); } catch (RuntimeException ignored) { } }, 5, 5, TimeUnit.MINUTES);
    }

    public int port() { return server.getAddress().getPort(); }

    public void stop() {
        if (housekeeping != null) housekeeping.shutdownNow();
        if (server != null) server.stop(0);
    }

    /** The tenant's address name in a Host header, "" for the region's own address, or null for an address not of this region. */
    static String slugOf(String hostHeader, String domain) {
        if (hostHeader == null) return null;
        String h = hostHeader.trim().toLowerCase(Locale.ROOT);
        int colon = h.lastIndexOf(':');
        if (colon > 0 && h.indexOf(']') < colon) h = h.substring(0, colon);   // the port
        if (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        if (h.equals(domain)) return "";
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
            if ("".equals(slug) && ex.getRequestURI().getPath().equals("/healthz")) {
                send(ex, 200, Map.of("status", "ok", "region", tenants.region, "tenants_open", tenants.opened().size()));
                return;
            }
            send(ex, 404, Map.of("error", Map.of("code", "SAAS.NO_TENANT", "message", "No Command Center at this address")));
        } catch (RuntimeException e) {
            e.printStackTrace();
            try { send(ex, 500, Map.of("error", Map.of("code", "CR.INTERNAL", "message", "Internal error"))); } catch (RuntimeException | IOException ignored) { }
        } finally { ex.close(); }
    }

    private static void send(HttpExchange ex, int status, Object body) throws IOException {
        byte[] b = JsonLite.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
    }
}
