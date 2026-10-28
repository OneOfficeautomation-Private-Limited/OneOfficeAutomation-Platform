package com.oneofficeautomation.cli;

import com.oneofficeautomation.runner.JsonCodec;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/**
 * Studio's connection to Control Room. The session token stays in this Studio process (never in the browser).
 * Checked-out bots are copied into the private workspace under "Control Room/<repository path>", and the mapping is
 * remembered in .ooa/checkouts.json so Check in sends a private copy back to the right repository path.
 */
final class ControlRoomLink {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Workspace ws;
    private volatile String url, token;
    private volatile Map<String, Object> user;
    /** True when the session belongs to the browser (same server); Studio then never signs it out. */
    private volatile boolean adopted;
    static final String FOLDER = "Control Room";

    ControlRoomLink(Workspace ws) { this.ws = ws; }

    boolean connected() { return token != null; }

    Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("connected", connected());
        m.put("url", url == null ? lastUrl() : url);
        m.put("user", user);
        return m;
    }

    private Path stateFile(String name) { return ws.root.resolve(".ooa").resolve(name); }

    private String lastUrl() {
        try { Path f = stateFile("controlroom.json"); if (Files.exists(f)) return String.valueOf(((Map<?, ?>) JsonLite.parse(Files.readString(f))).get("url")); }
        catch (Exception ignored) { }
        return "http://localhost:8720";
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> call(String method, String path, Object body, boolean auth) {
        if (auth && token == null) throw new OoaException("STUDIO.CR_NOT_CONNECTED", "Not connected to Command Center", "Connect from the Explorer's Command Center section.");
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url + "/api/" + path)).timeout(Duration.ofSeconds(60))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JsonLite.write(body)))
                .header("Content-Type", "application/json");
        if (auth) b.header("Authorization", "Bearer " + token);
        HttpResponse<String> r;
        try { r = http.send(b.build(), HttpResponse.BodyHandlers.ofString()); }
        catch (IOException e) { throw new OoaException("STUDIO.CR_UNREACHABLE", "Cannot reach Command Center at " + url + ": " + e.getMessage(), "Check the address and that Command Center is running."); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new OoaException("STUDIO.INTERRUPTED", "Interrupted"); }
        Object parsed;
        try { parsed = r.body().isBlank() ? Map.of() : JsonLite.parse(r.body()); } catch (IllegalArgumentException e) { throw new OoaException("STUDIO.CR_BAD_RESPONSE", "Unexpected answer from " + url + " (HTTP " + r.statusCode() + ")", "Check the Command Center address."); }
        Map<String, Object> m = parsed instanceof Map ? (Map<String, Object>) parsed : Map.of();
        if (r.statusCode() >= 400) {
            Map<String, Object> e = m.get("error") instanceof Map ? (Map<String, Object>) m.get("error") : Map.of();
            if ("CR.UNAUTHENTICATED".equals(e.get("code"))) { token = null; user = null; }
            throw new OoaException(String.valueOf(e.getOrDefault("code", "STUDIO.CR_HTTP_" + r.statusCode())), String.valueOf(e.getOrDefault("message", "HTTP " + r.statusCode())), (String) e.get("suggestedFix"),
                    false, e.get("details") instanceof Map ? (Map<String, Object>) e.get("details") : null);
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> connect(String crUrl, String username, String password) throws IOException { return connect(crUrl, username, password, null); }

    /** As above; {@code code} is the authenticator code for an account with two-step sign-in. */
    @SuppressWarnings("unchecked")
    Map<String, Object> connect(String crUrl, String username, String password, String code) throws IOException {
        if (crUrl == null || !crUrl.matches("^https?://[A-Za-z0-9.\\-:\\[\\]]+(/.*)?$")) throw new OoaException("STUDIO.BAD_URL", "Enter the Command Center address, e.g. http://localhost:8720");
        url = crUrl.replaceAll("/+$", "");
        Map<String, Object> login = new java.util.HashMap<>(Map.of("username", username == null ? "" : username, "password", password == null ? "" : password));
        if (code != null && !code.isBlank()) login.put("code", code.trim());
        Map<String, Object> r = call("POST", "auth/login", login, false);
        if (Boolean.TRUE.equals(r.get("mfa_required")))
            throw new OoaException("STUDIO.CR_MFA_CODE", "This account uses two-step sign-in", "Enter the code from your authenticator app in the Code box, then connect again.");
        Map<String, Object> u = (Map<String, Object>) r.get("user");
        if (Boolean.TRUE.equals(u.get("must_change")))
            throw new OoaException("STUDIO.CR_PASSWORD_CHANGE", "Your Command Center password must be changed first", "Sign in to Command Center in the browser (" + url + "), choose a new password, then connect again.");
        token = (String) r.get("token");
        user = u;
        adopted = false;
        Files.createDirectories(stateFile("x").getParent());
        Files.writeString(stateFile("controlroom.json"), JsonLite.write(Map.of("url", url)), StandardCharsets.UTF_8);
        return status();
    }

    /** Uses an existing Control Room sign-in (the browser's session on the same server) instead of a password. */
    @SuppressWarnings("unchecked")
    Map<String, Object> adopt(String crUrl, String sessionToken) {
        String previous = url;
        url = crUrl.replaceAll("/+$", "");
        token = sessionToken;
        try {
            Map<String, Object> u = call("GET", "auth/me", null, true);
            if (Boolean.TRUE.equals(u.get("must_change"))) throw new OoaException("STUDIO.CR_PASSWORD_CHANGE", "Your Command Center password must be changed first");
            user = u;
            adopted = true;
            return status();
        } catch (OoaException e) {
            token = null; user = null; url = previous;
            throw e;
        }
    }

    void disconnect() {
        try { if (token != null && !adopted) call("POST", "auth/logout", null, true); } catch (OoaException ignored) { }
        token = null; user = null; adopted = false;
    }

    static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20"); }

    Map<String, Object> repo(String q) { return call("GET", "repo?q=" + enc(q == null ? "" : q), null, true); }

    /** Whether the agent at this repository path has a live (production) version already. */
    boolean hasLiveVersion(String repoPath) {
        if (!(repo(repoPath).get("bots") instanceof List<?> bots)) return false;
        for (Object o : bots)
            if (o instanceof Map<?, ?> b && repoPath.equals(b.get("path")) && b.get("production_version") instanceof Number n && n.intValue() > 0) return true;
        return false;
    }

    Map<String, Object> versions(String path) { return call("GET", "repo/versions?path=" + enc(path), null, true); }

    // ---------- check-out mapping ----------
    @SuppressWarnings("unchecked")
    Map<String, Object> mapping() {
        try { Path f = stateFile("checkouts.json"); if (Files.exists(f)) return new LinkedHashMap<>((Map<String, Object>) JsonLite.parse(Files.readString(f))); }
        catch (Exception ignored) { }
        return new LinkedHashMap<>();
    }

    void saveMapping(Map<String, Object> m) throws IOException {
        Files.createDirectories(stateFile("x").getParent());
        Files.writeString(stateFile("checkouts.json"), JsonLite.write(m), StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> checkout(String repoPath, JsonCodec json) throws IOException {
        Map<String, Object> r = call("POST", "repo/checkout", Map.of("path", repoPath), true);
        String local = FOLDER + "/" + repoPath;
        Path p = ws.resolve(local);
        Files.createDirectories(p.getParent());
        Path tmp = p.resolveSibling("." + p.getFileName() + ".saving");
        Files.writeString(tmp, json.write(r.get("flow"), true), StandardCharsets.UTF_8);
        Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
        Map<String, Object> m = mapping();
        m.put(local, repoPath);
        saveMapping(m);
        return Map.of("local", local, "path", repoPath, "version", r.get("version"));
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> checkin(String local, String repoPathOverride, String comment, JsonCodec json) throws IOException {
        return checkin(local, repoPathOverride, comment, true, json);
    }

    /** Checks the agent in as a new version; with production it also becomes the live version (or a request for it, when approval is on). */
    @SuppressWarnings("unchecked")
    Map<String, Object> checkin(String local, String repoPathOverride, String comment, boolean production, JsonCodec json) throws IOException {
        Path p = ws.resolve(local);
        if (!Files.isRegularFile(p)) throw new OoaException("STUDIO.NOT_FOUND", "Agent not found: " + local);
        Map<String, Object> flow = (Map<String, Object>) json.parse(Files.readString(p));
        Map<String, Object> m = mapping();
        String repoPath = repoPathOverride != null && !repoPathOverride.isBlank() ? repoPathOverride
                : m.containsKey(local) ? String.valueOf(m.get(local)) : local.startsWith(FOLDER + "/") ? local.substring(FOLDER.length() + 1) : local;
        // an agent with no live version yet gets its first one live, or nothing could run it
        if (!production && !hasLiveVersion(repoPath)) production = true;
        Map<String, Object> r = call("POST", "repo/checkin", Map.of("path", repoPath, "flow", flow, "comment", comment == null ? "" : comment, "production", production), true);
        m.put(local, repoPath);
        saveMapping(m);
        Map<String, Object> out = new java.util.LinkedHashMap<>(Map.of("path", repoPath, "version", r.get("version"), "local", local, "production", production));
        if (r.get("production_request") != null) out.put("production_request", r.get("production_request"));   // approval before production is on
        return out;
    }

    void undoCheckout(String repoPath) { call("POST", "repo/undo-checkout", Map.of("path", repoPath), true); }
}
