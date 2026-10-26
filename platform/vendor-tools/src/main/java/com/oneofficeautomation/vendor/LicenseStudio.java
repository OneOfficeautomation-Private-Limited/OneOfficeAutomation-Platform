package com.oneofficeautomation.vendor;

import com.oneofficeautomation.controlroom.License;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * License Studio: the vendor's page for issuing license files, started with {@code ooa license ui} in the vendor build
 * only. It listens on 127.0.0.1, needs the access token printed at start (so other local pages cannot use it), signs
 * with the private key in the key folder, and keeps a copy of every license it issues in {@code <key folder>\issued}.
 */
public final class LicenseStudio {
    private final Path keyFile, issuedDir;
    private final String token;
    private final int port;
    private HttpServer server;

    public LicenseStudio(Path keyFile, int port) {
        this.keyFile = keyFile;
        this.issuedDir = keyFile.toAbsolutePath().getParent().resolve("issued");
        this.port = port;
        byte[] t = new byte[24];
        new SecureRandom().nextBytes(t);
        this.token = Base64.getUrlEncoder().withoutPadding().encodeToString(t);
    }

    public String url() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/#t=" + token; }

    public void start() throws IOException {
        Files.createDirectories(issuedDir);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    public void stop() { if (server != null) server.stop(0); }

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            var h = ex.getResponseHeaders();
            h.set("X-Content-Type-Options", "nosniff");
            h.set("X-Frame-Options", "DENY");
            h.set("Referrer-Policy", "no-referrer");
            h.set("Cache-Control", "no-store");
            h.set("Content-Security-Policy", "default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'");
            if (!path.startsWith("/api/")) { staticFile(ex, path); return; }
            // every call carries the access token, and only this computer's pages may call (no other origin)
            String origin = ex.getRequestHeaders().getFirst("Origin");
            int p = server.getAddress().getPort();
            if (origin != null && !origin.equals("http://127.0.0.1:" + p) && !origin.equals("http://localhost:" + p)) { json(ex, 403, error("FORBIDDEN", "Other sites may not use License Studio")); return; }
            String t = ex.getRequestHeaders().getFirst("X-Studio-Token");
            if (t == null || !MessageDigest.isEqual(t.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
                json(ex, 401, error("TOKEN", "Open License Studio with the address printed when it started")); return;
            }
            String m = ex.getRequestMethod();
            switch (path) {
                case "/api/info" -> json(ex, 200, info());
                case "/api/licenses" -> json(ex, 200, Map.of("licenses", issued()));
                case "/api/issue" -> { if (!m.equals("POST")) { json(ex, 405, error("METHOD", "POST")); return; } json(ex, 200, issue(body(ex))); }
                case "/api/verify" -> { if (!m.equals("POST")) { json(ex, 405, error("METHOD", "POST")); return; } json(ex, 200, verify(String.valueOf(body(ex).get("text")))); }
                case "/api/download" -> download(ex, query(ex).get("file"));
                default -> json(ex, 404, error("NOT_FOUND", "Unknown route"));
            }
        } catch (OoaException e) {
            json(ex, 400, Map.of("error", e.toMap()));
        } catch (RuntimeException e) {
            json(ex, 400, error("BAD_INPUT", e.getMessage() == null ? e.toString() : e.getMessage()));
        } finally { ex.close(); }
    }

    // ---------- what the page asks for ----------

    Map<String, Object> info() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key_file", keyFile.toAbsolutePath().toString());
        m.put("issued_folder", issuedDir.toString());
        m.put("key_present", Files.exists(keyFile));
        m.put("key_matches_product", Files.exists(keyFile) && keyMatches());
        m.put("seat_types", License.TYPES);
        return m;
    }

    /** True when the private key signs files that this release accepts (its pair is License.VENDOR_PUBLIC_KEY). */
    boolean keyMatches() {
        try {
            String probe = new License("probe", "probe", "probe", LocalDate.now(), LocalDate.now().plusDays(1), 0, Map.of(), "").sign(privateKey());
            License.verify(probe, License.publicKey(License.VENDOR_PUBLIC_KEY));
            return true;
        } catch (RuntimeException | IOException e) { return false; }
    }

    private PrivateKey privateKey() throws IOException { return License.privateKey(Files.readString(keyFile)); }

    Map<String, Object> issue(Map<String, Object> b) throws IOException {
        String customer = str(b.get("customer"));
        if (customer.isEmpty() || customer.length() > 200) throw new OoaException("STUDIO.BAD_INPUT", "Give the customer's name (up to 200 characters)");
        LocalDate expires;
        try { expires = LocalDate.parse(str(b.get("expires"))); } catch (RuntimeException e) { throw new OoaException("STUDIO.BAD_INPUT", "Choose the expiry date"); }
        if (!expires.isAfter(LocalDate.now())) throw new OoaException("STUDIO.BAD_INPUT", "The expiry date must be in the future");
        int grace = num(b.get("grace_days"), 14, 0, 90, "Grace days");
        Map<String, Integer> seats = new LinkedHashMap<>();
        Map<String, Object> in = b.get("seats") instanceof Map<?, ?> s ? castMap(s) : Map.of();
        int sum = 0;
        for (String t : License.TYPES) { int n = num(in.get(t), 0, 0, 100000, t + " seats"); seats.put(t, n); sum += n; }
        if (sum == 0) throw new OoaException("STUDIO.BAD_INPUT", "Give at least one seat");
        if (!keyMatches()) throw new OoaException("STUDIO.KEY", "The private key in " + keyFile + " does not match the key built into the product", "Use the key made with ooa license keygen for this product");
        String edition = str(b.getOrDefault("edition", "Enterprise"));
        String id = "LIC-" + LocalDate.now().toString().replace("-", "") + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        License l = new License(id, customer, edition.isEmpty() ? "Enterprise" : edition, LocalDate.now(), expires, grace, seats, str(b.get("notes")));
        String text = l.sign(privateKey());
        String file = id + " " + customer.replaceAll("[^A-Za-z0-9 ._-]", "_").trim() + ".lic";
        Files.writeString(issuedDir.resolve(file), text);
        Map<String, Object> r = describe(l, file);
        r.put("text", text);
        return r;
    }

    List<Map<String, Object>> issued() throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(issuedDir, "*.lic")) {
            for (Path p : ds) {
                try { out.add(describe(License.verify(Files.readString(p), License.publicKey(License.VENDOR_PUBLIC_KEY)), p.getFileName().toString())); }
                catch (OoaException e) { out.add(Map.of("file", p.getFileName().toString(), "state", "invalid", "problem", e.getMessage())); }
            }
        }
        out.sort(Comparator.comparing((Map<String, Object> m) -> String.valueOf(m.getOrDefault("issued", ""))).reversed());
        return out;
    }

    Map<String, Object> verify(String text) {
        License l = License.verify(text == null ? "" : text, License.publicKey(License.VENDOR_PUBLIC_KEY));
        return describe(l, null);
    }

    static Map<String, Object> describe(License l, String file) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (file != null) m.put("file", file);
        m.put("id", l.id()); m.put("customer", l.customer()); m.put("edition", l.edition());
        m.put("issued", l.issued().toString()); m.put("expires", l.expires().toString()); m.put("grace_days", l.graceDays());
        m.put("seats", l.seats()); m.put("notes", l.notes());
        LocalDate today = LocalDate.now();
        long left = ChronoUnit.DAYS.between(today, l.expires());
        m.put("days_left", left);
        m.put("state", left > 30 ? "active" : left >= 0 ? "expiring" : today.isAfter(l.expires().plusDays(l.graceDays())) ? "expired" : "grace");
        return m;
    }

    private void download(HttpExchange ex, String file) throws IOException {
        if (file == null || !file.endsWith(".lic") || file.contains("/") || file.contains("\\") || file.contains("..")) { json(ex, 400, error("BAD_INPUT", "Unknown file")); return; }
        Path p = issuedDir.resolve(file);
        if (!Files.exists(p)) { json(ex, 404, error("NOT_FOUND", "No such license")); return; }
        byte[] b = Files.readAllBytes(p);
        ex.getResponseHeaders().set("Content-Type", "application/octet-stream");
        ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + file.replace("\"", "") + "\"");
        ex.sendResponseHeaders(200, b.length);
        ex.getResponseBody().write(b);
    }

    // ---------- plumbing ----------

    private void staticFile(HttpExchange ex, String path) throws IOException {
        String name = switch (path) { case "/", "/index.html" -> "index.html"; case "/studio.js" -> "studio.js"; case "/studio.css" -> "studio.css"; default -> null; };
        if (name == null) { ex.sendResponseHeaders(404, -1); return; }
        try (InputStream in = LicenseStudio.class.getResourceAsStream("/license-studio/" + name)) {
            if (in == null) { ex.sendResponseHeaders(404, -1); return; }
            byte[] b = in.readAllBytes();
            ex.getResponseHeaders().set("Content-Type", name.endsWith(".html") ? "text/html; charset=utf-8" : name.endsWith(".js") ? "text/javascript; charset=utf-8" : "text/css; charset=utf-8");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
        }
    }

    private static Map<String, Object> body(HttpExchange ex) throws IOException {
        byte[] b = ex.getRequestBody().readNBytes(256 * 1024 + 1);
        if (b.length > 256 * 1024) throw new OoaException("STUDIO.TOO_LARGE", "The request is too large");
        Object o = JsonLite.parse(new String(b, StandardCharsets.UTF_8));
        if (!(o instanceof Map<?, ?> m)) throw new OoaException("STUDIO.BAD_INPUT", "Expected a JSON object");
        return castMap(m);
    }

    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> q = new HashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw != null) for (String kv : raw.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0) q.put(java.net.URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8), java.net.URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
        }
        return q;
    }

    private static void json(HttpExchange ex, int status, Object o) throws IOException {
        byte[] b = JsonLite.write(o).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
    }

    private static Map<String, Object> error(String code, String message) { return Map.of("error", Map.of("code", "STUDIO." + code, "message", message)); }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o).trim(); }

    private static int num(Object o, int dflt, int min, int max, String what) {
        if (o == null || String.valueOf(o).isBlank()) return dflt;
        int n;
        try { n = o instanceof Number x ? x.intValue() : Integer.parseInt(String.valueOf(o).trim()); }
        catch (NumberFormatException e) { throw new OoaException("STUDIO.BAD_INPUT", what + " must be a whole number"); }
        if (n < min || n > max) throw new OoaException("STUDIO.BAD_INPUT", what + " must be from " + min + " to " + max);
        return n;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) { return (Map<String, Object>) m; }
}
