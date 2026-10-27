package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;

/**
 * The global directory of a SaaS service with several regions (C4): which region holds each tenant, and nothing else.
 * It holds no customer data, only address name, region and the region's address, so each tenant's data stays in its
 * region. People who know only their company's address name start here: /t/acme sends them to acme.in.example.com.
 * Regions reserve a name when they create a tenant (so a name is used in one region only) and release it on deletion,
 * signing in with their own region token; the directory keeps only the tokens' SHA-256.
 * <pre>
 *   GET  /t/NAME                        302 to https://NAME.REGION-DOMAIN/
 *   GET  /api/directory/NAME            {slug, region, url}
 *   POST /api/directory/NAME            a region reserves NAME  (Authorization: Bearer REGION-TOKEN; 409 when taken)
 *   DELETE /api/directory/NAME          a region releases one of its names
 * </pre>
 */
public final class RegionDirectory {
    /** A region the directory knows: its name, its address (in.example.com) and the SHA-256 of its token. */
    public record Region(String name, String domain, String tokenSha256) {}

    private final Db db;
    private final Map<String, Region> regions;
    private HttpServer server;

    public RegionDirectory(Db db, List<Region> regions) {
        this.db = db;
        Map<String, Region> m = new LinkedHashMap<>();
        for (Region r : regions) m.put(r.name(), r);
        this.regions = m;
        db.update("CREATE TABLE IF NOT EXISTS directory (slug VARCHAR(40) PRIMARY KEY, region VARCHAR(20) NOT NULL, created TIMESTAMP NOT NULL)");
    }

    /** The region holding a name, or null. */
    public Region find(String slug) {
        Map<String, Object> r = db.one("SELECT region FROM directory WHERE slug = ?", slug);
        return r == null ? null : regions.get((String) r.get("region"));
    }

    /** Reserves a name for the region whose token this is; taken names (by any region) are refused. */
    public void reserve(String slug, String token) {
        Region r = regionOf(token);
        if (!Tenants.SLUG.matcher(slug).matches() || Tenants.RESERVED.contains(slug)) throw new OoaException("SAAS.BAD_SLUG", "'" + slug + "' cannot be a tenant's address name");
        Map<String, Object> have = db.one("SELECT region FROM directory WHERE slug = ?", slug);
        if (have != null) {
            if (have.get("region").equals(r.name())) return;   // asked again by the same region
            throw new OoaException("SAAS.SLUG_TAKEN", "The address name '" + slug + "' is taken");
        }
        db.update("INSERT INTO directory (slug, region, created) VALUES (?,?,?)", slug, r.name(), Instant.now());
    }

    /** Releases a name of the region whose token this is. */
    public void release(String slug, String token) {
        Region r = regionOf(token);
        db.update("DELETE FROM directory WHERE slug = ? AND region = ?", slug, r.name());
    }

    private Region regionOf(String token) {
        String sha = token == null ? "" : Security.sha256(token);
        for (Region r : regions.values()) if (java.security.MessageDigest.isEqual(sha.getBytes(StandardCharsets.UTF_8), r.tokenSha256().getBytes(StandardCharsets.UTF_8))) return r;
        throw new OoaException("CR.UNAUTHENTICATED", "Not a region of this directory");
    }

    public void start(String host, int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.setExecutor(Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("ooa-directory-", 0).factory()));
        server.createContext("/", this::handle);
        server.start();
    }

    public int port() { return server.getAddress().getPort(); }

    public void stop() { if (server != null) server.stop(0); }

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath(), m = ex.getRequestMethod();
            if (path.equals("/healthz")) { send(ex, 200, Map.of("status", "ok", "regions", regions.keySet())); return; }
            if (path.equals("/") && m.equals("GET")) { page(ex); return; }
            if (path.equals("/go") && m.equals("GET")) {
                String q = ControlRoomServer.query(ex).getOrDefault("name", "").trim().toLowerCase(Locale.ROOT);
                redirect(ex, "/t/" + (Tenants.SLUG.matcher(q).matches() ? q : "-"));
                return;
            }
            if (path.startsWith("/t/") && m.equals("GET")) {
                Region r = find(path.substring(3).toLowerCase(Locale.ROOT));
                if (r == null) { send(ex, 404, Map.of("error", Map.of("code", "SAAS.NO_TENANT", "message", "No Command Center with this name"))); return; }
                redirect(ex, "https://" + path.substring(3).toLowerCase(Locale.ROOT) + "." + r.domain() + "/");
                return;
            }
            if (path.startsWith("/api/directory/")) {
                String slug = path.substring(15).toLowerCase(Locale.ROOT);
                String token = ControlRoomServer.token(ex);
                switch (m) {
                    case "GET" -> {
                        Region r = find(slug);
                        if (r == null) send(ex, 404, Map.of("error", Map.of("code", "SAAS.NO_TENANT", "message", "No Command Center with this name")));
                        else send(ex, 200, Map.of("slug", slug, "region", r.name(), "url", "https://" + slug + "." + r.domain() + "/"));
                    }
                    case "POST" -> { reserve(slug, token); send(ex, 200, Map.of("slug", slug, "reserved", true)); }
                    case "DELETE" -> { release(slug, token); send(ex, 200, Map.of("slug", slug, "released", true)); }
                    default -> send(ex, 405, Map.of("error", Map.of("code", "CR.BAD_INPUT", "message", "Method not allowed")));
                }
                return;
            }
            send(ex, 404, Map.of("error", Map.of("code", "CR.NOT_FOUND", "message", "Not found")));
        } catch (OoaException e) {
            send(ex, switch (e.code()) { case "CR.UNAUTHENTICATED" -> 401; case "SAAS.SLUG_TAKEN" -> 409; default -> 400; }, Map.of("error", e.toMap()));
        } catch (RuntimeException e) {
            e.printStackTrace();
            send(ex, 500, Map.of("error", Map.of("code", "CR.INTERNAL", "message", "Internal error")));
        } finally { ex.close(); }
    }

    private void page(HttpExchange ex) throws IOException {
        byte[] b = ("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
            + "<title>Sign in to ONEOFFICEAUTOMATION</title><style>body{font:16px system-ui,sans-serif;margin:0;display:grid;place-items:center;min-height:100vh;"
            + "background:#f6f8fb;color:#1d2433}@media(prefers-color-scheme:dark){body{background:#12161d;color:#e6e9ef}}form{padding:16px;max-width:420px;width:100%}"
            + "input,button{font:inherit;padding:10px;width:100%;box-sizing:border-box;margin-top:8px}</style></head><body><form action=\"/go\">"
            + "<h1>ONEOFFICEAUTOMATION</h1><label for=\"n\">Your company's address name</label><input id=\"n\" name=\"name\" autocomplete=\"organization\" required>"
            + "<button>Continue</button></form></body></html>").getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(200, b.length);
        ex.getResponseBody().write(b);
    }

    private static void redirect(HttpExchange ex, String to) throws IOException {
        ex.getResponseHeaders().set("Location", to);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(302, -1);
    }

    private static void send(HttpExchange ex, int status, Object body) throws IOException {
        byte[] b = JsonLite.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
    }

    /** What a region uses to reserve and release its tenants' names (Tenants.directory). */
    public interface Client {
        void reserve(String slug);
        void release(String slug);
    }

    /** A region's client of the directory at a base address, with the region's token. */
    public static Client client(String baseUrl, String token) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        String base = baseUrl.replaceAll("/+$", "");
        return new Client() {
            @Override public void reserve(String slug) { call("POST", slug); }
            @Override public void release(String slug) { call("DELETE", slug); }

            private void call(String method, String slug) {
                HttpResponse<String> r;
                try {
                    r = http.send(HttpRequest.newBuilder(URI.create(base + "/api/directory/" + slug)).timeout(Duration.ofSeconds(20))
                        .header("Authorization", "Bearer " + token).method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
                } catch (IOException e) { throw new OoaException("SAAS.DIRECTORY", "The directory at " + base + " cannot be reached: " + e.getMessage()); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new OoaException("SAAS.DIRECTORY", "Interrupted"); }
                if (r.statusCode() == 409) throw new OoaException("SAAS.SLUG_TAKEN", "The address name '" + slug + "' is taken (in this or another region)");
                if (r.statusCode() / 100 != 2) throw new OoaException("SAAS.DIRECTORY", "The directory answered " + r.statusCode() + ": " + r.body());
            }
        };
    }
}
