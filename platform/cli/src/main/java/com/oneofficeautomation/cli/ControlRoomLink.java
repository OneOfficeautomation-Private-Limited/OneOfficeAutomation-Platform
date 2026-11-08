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
 * A checked-out agent lands at the same folder path in the private workspace as in the repository, as AA's check-out
 * does (since 1 Nov 2026; earlier copies under "Control Room/<repository path>" still check in to the right place), and
 * the mapping is remembered in .ooa/checkouts.json so Check in sends a private copy back to the right repository path.
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
        if (auth && token == null) throw new OoaException("STUDIO.CR_NOT_CONNECTED", "Not connected to One CommandCenter", "Connect from the Explorer's One CommandCenter section.");
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url + "/api/" + path)).timeout(Duration.ofSeconds(60))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JsonLite.write(body)))
                .header("Content-Type", "application/json");
        if (auth) b.header("Authorization", "Bearer " + token);
        HttpResponse<String> r;
        try { r = http.send(b.build(), HttpResponse.BodyHandlers.ofString()); }
        catch (IOException e) { throw new OoaException("STUDIO.CR_UNREACHABLE", "Cannot reach One CommandCenter at " + url + (e.getMessage() == null ? "" : ": " + e.getMessage()), "Check the address and that One CommandCenter is running."); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new OoaException("STUDIO.INTERRUPTED", "Interrupted"); }
        Object parsed;
        try { parsed = r.body().isBlank() ? Map.of() : JsonLite.parse(r.body()); } catch (IllegalArgumentException e) { throw new OoaException("STUDIO.CR_BAD_RESPONSE", "Unexpected answer from " + url + " (HTTP " + r.statusCode() + ")", "Check One CommandCenter address."); }
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
    Map<String, Object> connect(String crUrl, String username, String password, String code) throws IOException { return connect(crUrl, username, password, code, false); }

    /** As above; {@code accepted}: the person accepted One CommandCenter's sign-in notice (Settings), when it must be accepted. */
    @SuppressWarnings("unchecked")
    Map<String, Object> connect(String crUrl, String username, String password, String code, boolean accepted) throws IOException {
        if (crUrl == null || !crUrl.matches("^https?://[A-Za-z0-9.\\-:\\[\\]]+(/.*)?$")) throw new OoaException("STUDIO.BAD_URL", "Enter One CommandCenter address, e.g. http://localhost:8720");
        url = crUrl.replaceAll("/+$", "");
        Map<String, Object> login = new java.util.HashMap<>(Map.of("username", username == null ? "" : username, "password", password == null ? "" : password));
        if (code != null && !code.isBlank()) login.put("code", code.trim());
        if (accepted) login.put("accepted", true);
        Map<String, Object> r = call("POST", "auth/login", login, false);
        if (Boolean.TRUE.equals(r.get("mfa_required")))
            throw new OoaException("STUDIO.CR_MFA_CODE", "This account uses two-step sign-in", "Enter the code from your authenticator app in the Code box, then connect again.");
        Map<String, Object> u = (Map<String, Object>) r.get("user");
        if (Boolean.TRUE.equals(u.get("must_change")))
            throw new OoaException("STUDIO.CR_PASSWORD_CHANGE", "Your One CommandCenter password must be changed first", "Sign in to One CommandCenter in the browser (" + url + "), choose a new password, then connect again.");
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
            if (Boolean.TRUE.equals(u.get("must_change"))) throw new OoaException("STUDIO.CR_PASSWORD_CHANGE", "Your One CommandCenter password must be changed first");
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

    /**
     * The vault credentials the signed-in person may use, as folder (locker), credential and attribute: never a value.
     * For a step's Credential field in One AgentStudio; none when not connected.
     */
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> credentials() {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!connected()) return out;
        if (call("GET", "lockers", null, true).get("lockers") instanceof List<?> lockers)
            for (Object o : lockers)
                if (o instanceof Map<?, ?> l && l.get("credentials") instanceof List<?> creds)
                    for (Object c : creds)
                        if (c instanceof Map<?, ?> m && m.get("name") != null && m.get("attribute") != null)
                            out.add(Map.of("locker", String.valueOf(l.get("name")), "name", String.valueOf(m.get("name")), "attribute", String.valueOf(m.get("attribute"))));
        out.sort(Comparator.comparing((Map<String, Object> m) -> String.valueOf(m.get("locker"))).thenComparing(m -> String.valueOf(m.get("name"))).thenComparing(m -> String.valueOf(m.get("attribute"))));
        return out;
    }

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

    /**
     * Checks the agent out into the same folder path in the private workspace. A private item there that is not this
     * agent's copy is never overwritten (the check-out is refused before anything is locked); this agent's own copy with
     * changes that were not checked in is kept as it is, so nothing typed is lost ("kept": true).
     */
    Map<String, Object> checkout(String repoPath, JsonCodec json) throws IOException { return checkout(repoPath, null, json); }

    /** As above, for a chosen version (null: the latest); the agents it runs that are not in the workspace yet are copied too. */
    @SuppressWarnings("unchecked")
    Map<String, Object> checkout(String repoPath, Object version, JsonCodec json) throws IOException {
        Map<String, Object> m = mapping();
        String local = m.entrySet().stream().filter(e -> repoPath.equals(e.getValue())).map(Map.Entry::getKey)
                .filter(k -> Files.exists(ws.resolve(k))).findFirst().orElse(repoPath);
        Path p = ws.resolve(local);
        if (Files.exists(p) && !repoPath.equals(m.get(local)))
            throw new OoaException("STUDIO.NAME_TAKEN", "Your workspace already has your own " + local + ", which is not a copy of this agent",
                    "Rename or move your own item, then check out again.");
        Map<String, Object> r = call("POST", "repo/checkout", version == null ? Map.of("path", repoPath) : Map.of("path", repoPath, "version", version), true);
        String text = json.write(r.get("flow"), true);
        boolean kept = Files.exists(p) && edited(local, json) && !normal(local, json).equals(text);
        if (!kept) {
            Files.createDirectories(p.getParent());
            Path tmp = p.resolveSibling("." + p.getFileName() + ".saving");
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
            markSynced(local, text);
        }
        m.put(local, repoPath);
        saveMapping(m);
        forgetClone(local);
        List<String> cloned = cloneDependencies((Map<String, Object>) r.get("flow"), json);
        return Map.of("local", local, "path", repoPath, "version", r.get("version"), "kept", kept, "cloned", cloned);
    }

    // ---------- Clone and Copy to Private (usage review item 21, 9 Nov 2026, as AA's repository) ----------

    /**
     * Clone: a read-only copy of the agent's latest version at the same folder path in the private workspace, without
     * checking it out, so others can still change it. It follows the repository (a newer version replaces it when the
     * states are read). To change it: Copy to Private, or check it out (the check-out replaces the clone).
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> cloneBot(String repoPath, JsonCodec json) throws IOException {
        Map<String, Object> m = mapping();
        Path p = ws.resolve(repoPath);
        if (Files.exists(p) && !repoPath.equals(m.get(repoPath)))
            throw new OoaException("STUDIO.NAME_TAKEN", "Your workspace already has your own " + repoPath + ", which is not a copy of this agent",
                    "Rename or move your own item, then clone again.");
        if (Files.exists(p) && !isClone(repoPath))
            throw new OoaException("STUDIO.ALREADY_COPIED", "You already have a copy of " + repoPath + " in your workspace",
                    "Open it from the Private tab; check it out to change it.");
        Map<String, Object> got = call("GET", "repo/bot?path=" + enc(repoPath), null, true);
        String text = json.write(got.get("flow"), true);
        writeCopy(p, text);
        m.put(repoPath, repoPath);
        saveMapping(m);
        markSynced(repoPath, text);
        Map<String, Object> c = state("clones.json");
        c.put(repoPath, got.get("version"));
        saveState("clones.json", c);
        return Map.of("local", repoPath, "path", repoPath, "version", got.get("version"));
    }

    /** True when this private item is a clone (read-only until it is checked out). */
    boolean isClone(String local) { return state("clones.json").containsKey(local); }

    private void forgetClone(String local) throws IOException {
        Map<String, Object> c = state("clones.json");
        if (c.remove(local) != null) saveState("clones.json", c);
    }

    /**
     * Copy to Private: the agent's latest version becomes a new agent of the person's own (a new id, not linked to the
     * repository, status New) at the path asked for; the agents it runs come along as at check-out. As AA, the name may
     * not be one the repository already has in that folder, nor an item already in the workspace.
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> copyToPrivate(String repoPath, String to, JsonCodec json) throws IOException {
        if (repoPath == null || repoPath.isBlank()) throw new OoaException("STUDIO.NOT_FOUND", "Choose the agent to copy");
        if (to == null || to.isBlank()) throw new OoaException("STUDIO.BAD_NAME", "Give the copy a name");
        String target = to.strip();
        String kind = repoPath.substring(repoPath.indexOf('.', repoPath.lastIndexOf('/') + 1));
        if (!target.endsWith(kind)) target = target + kind;
        String name = target.substring(target.lastIndexOf('/') + 1, target.length() - kind.length());
        if (name.isBlank() || name.endsWith(".") || name.endsWith(" ") || name.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])"))
            throw new OoaException("STUDIO.BAD_NAME", "'" + name + "' cannot be used as a name",
                    "Do not end a name with a dot or a space, or use a name Windows keeps for itself (CON, PRN, AUX, NUL, COM1, LPT1 ...).");
        Path p = ws.resolve(target);
        if (Files.exists(p)) throw new OoaException("STUDIO.NAME_TAKEN", "Your workspace already has " + target, "Choose another name or folder.");
        for (Object o : (List<Object>) repo("").getOrDefault("bots", List.of()))
            if (target.equalsIgnoreCase(String.valueOf(((Map<String, Object>) o).get("path"))))
                throw new OoaException("STUDIO.NAME_TAKEN", "The repository already has an agent called " + target,
                        "Choose another name or folder: names stay unique across Public and Private.");
        Map<String, Object> got = call("GET", "repo/bot?path=" + enc(repoPath), null, true);
        Map<String, Object> flow = new LinkedHashMap<>((Map<String, Object>) got.get("flow"));
        if (target.endsWith(Workspace.BOT_SUFFIX)) {
            flow.put("id", newAgentId(String.valueOf(flow.getOrDefault("id", "agent"))));
            flow.put("name", name);
        }
        writeCopy(p, json.write(flow, true));
        List<String> cloned = cloneDependencies(flow, json);
        return Map.of("local", target, "from", repoPath, "version", got.get("version"), "cloned", cloned);
    }

    /** A new agent id, not used by any agent in the repository. */
    @SuppressWarnings("unchecked")
    private String newAgentId(String from) {
        Set<String> used = new HashSet<>();
        for (Object o : (List<Object>) repo("").getOrDefault("bots", List.of())) used.add(String.valueOf(((Map<String, Object>) o).get("bot_id")));
        String base = from.replaceAll("_copy[0-9]*$", "") + "_copy", id = base;
        for (int k = 2; used.contains(id); k++) id = base + k;
        return id;
    }

    private void writeCopy(Path p, String text) throws IOException {
        Files.createDirectories(p.getParent());
        Path tmp = p.resolveSibling("." + p.getFileName() + ".saving");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
    }

    private void saveState(String name, Map<String, Object> value) throws IOException {
        Files.createDirectories(stateFile("x").getParent());
        Files.writeString(stateFile(name), JsonLite.write(value), StandardCharsets.UTF_8);
    }

    /**
     * The agents a checked-out agent runs (Run agent steps and an AI agent's tools, found by id in the repository) are
     * copied into the workspace at their own folder paths when they are not there yet, as AA's check-out clones its
     * dependencies: they are not checked out, so others can still change them. The agents those run come too (up to 50).
     * A private item already at such a path is left as it is; an agent the person may not see is skipped.
     */
    @SuppressWarnings("unchecked")
    private List<String> cloneDependencies(Map<String, Object> flow, JsonCodec json) throws IOException {
        Map<String, String> pathById = new HashMap<>();
        for (Object o : (List<Object>) repo("").getOrDefault("bots", List.of())) {
            Map<String, Object> b = (Map<String, Object>) o;
            if (b.get("bot_id") != null) pathById.put(String.valueOf(b.get("bot_id")), String.valueOf(b.get("path")));
        }
        List<String> cloned = new ArrayList<>();
        Deque<Map<String, Object>> todo = new ArrayDeque<>(List.of(flow));
        Set<String> seen = new HashSet<>();
        while (!todo.isEmpty() && seen.size() < 50) {
            for (String id : idsUsedBy(todo.poll())) {
                String path = pathById.get(id);
                if (path == null || !seen.add(path)) continue;
                Path p = ws.resolve(path);
                if (Files.exists(p)) {
                    try { todo.add((Map<String, Object>) json.parse(Files.readString(p))); } catch (RuntimeException unreadable) { /* its own item */ }
                    continue;
                }
                Map<String, Object> got;
                try { got = call("GET", "repo/bot?path=" + enc(path), null, true); } catch (OoaException notAllowed) { continue; }
                String text = json.write(got.get("flow"), true);
                Files.createDirectories(p.getParent());
                Path tmp = p.resolveSibling("." + p.getFileName() + ".saving");
                Files.writeString(tmp, text, StandardCharsets.UTF_8);
                Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
                Map<String, Object> m = mapping();
                m.put(path, path);
                saveMapping(m);
                markSynced(path, text);
                cloned.add(path);
                todo.add((Map<String, Object>) got.get("flow"));
            }
        }
        return cloned;
    }

    /** The ids of the agents a flow runs: Run agent steps and an AI agent's tools, in blocks too. */
    @SuppressWarnings("unchecked")
    static List<String> idsUsedBy(Map<String, Object> flow) {
        List<String> ids = new ArrayList<>();
        for (Map<String, Object> step : com.oneofficeautomation.runner.Steps.walkFlow(flow)) {
            Map<String, Object> args = step.get("args") instanceof Map ? (Map<String, Object>) step.get("args") : Map.of();
            if ("core/run_bot".equals(step.get("action")) && args.get("automation") instanceof String a) ids.add(a);
            if ("ooa.ai_agent/run".equals(step.get("action")))
                for (String k : List.of("tools", "sensitive_tools")) if (args.get(k) instanceof List<?> l) for (Object x : l) if (x instanceof String s) ids.add(s);
        }
        return ids;
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
        markSynced(local, json.write(flow, true));
        Map<String, Object> out = new java.util.LinkedHashMap<>(Map.of("path", repoPath, "version", r.get("version"), "local", local, "production", production));
        if (r.get("production_request") != null) out.put("production_request", r.get("production_request"));   // approval before production is on
        List<String> sent = sendObjectRepositories(flow);
        if (!sent.isEmpty()) out.put("files", sent);
        return out;
    }

    /**
     * The shared object repositories the agent uses (G42) go to the repository's files at the same path, so runs from
     * One CommandCenter find them; a file One CommandCenter already has unchanged is not sent again.
     */
    @SuppressWarnings("unchecked")
    private List<String> sendObjectRepositories(Map<String, Object> flow) throws IOException {
        List<String> sent = new ArrayList<>();
        for (String repo : com.oneofficeautomation.runner.ObjectRepositories.referenced(flow)) {
            Path f;
            try { f = ws.resolve(repo); } catch (OoaException outside) { continue; }
            if (!Files.isRegularFile(f)) throw new OoaException("STUDIO.NOT_FOUND", "The agent uses the object repository " + repo + ", which is not in the workspace",
                    "Put the repository file back, or choose the elements again.");
            byte[] bytes = Files.readAllBytes(f);
            String sha = java.util.HexFormat.of().formatHex(sha256Bytes(bytes));
            String folder = repo.contains("/") ? repo.substring(0, repo.lastIndexOf('/')) : "";
            boolean same = false;
            for (Object o : (List<Object>) call("GET", "repo/files?folder=" + java.net.URLEncoder.encode(folder, StandardCharsets.UTF_8), null, true).getOrDefault("files", List.of()))
                if (o instanceof Map<?, ?> m && repo.equals(m.get("path")) && sha.equals(m.get("sha256"))) same = true;
            if (same) continue;
            putFile(repo, bytes, "application/json");
            sent.add(repo);
        }
        return sent;
    }

    private static byte[] sha256Bytes(byte[] b) {
        try { return java.security.MessageDigest.getInstance("SHA-256").digest(b); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    /** PUT repo/files?path=...: the body is the file itself. */
    private void putFile(String path, byte[] bytes, String type) {
        if (token == null) throw new OoaException("STUDIO.CR_NOT_CONNECTED", "Not connected to One CommandCenter", "Connect from the Explorer's One CommandCenter section.");
        HttpRequest req = HttpRequest.newBuilder(URI.create(url + "/api/repo/files?path=" + java.net.URLEncoder.encode(path, StandardCharsets.UTF_8).replace("+", "%20")))
                .timeout(Duration.ofSeconds(120)).PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).header("Content-Type", type).header("Authorization", "Bearer " + token).build();
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 400) {
                String msg = "HTTP " + res.statusCode();
                try { if (JsonLite.parse(res.body()) instanceof Map<?, ?> m && m.get("error") instanceof Map<?, ?> e) msg = String.valueOf(e.get("message")); } catch (IllegalArgumentException ignored) { }
                throw new OoaException("STUDIO.CR_FILE", "Cannot send " + path + " to One CommandCenter: " + msg, "Check you may add files to that folder.");
            }
        } catch (IOException e) { throw new OoaException("STUDIO.CR_UNREACHABLE", "Cannot reach One CommandCenter at " + url, null); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new OoaException("STUDIO.INTERRUPTED", "Interrupted"); }
    }

    // ---------- what each private copy looked like when it was last checked in or out ----------
    /** A private copy's text as JSON in one form, so a copy saved by Studio and the repository's version compare equal. */
    private String normal(String local, JsonCodec json) throws IOException {
        String text = Files.readString(ws.resolve(local));
        try { return json.write(json.parse(text), true); } catch (RuntimeException e) { return text; }
    }

    /** True when the copy changed since it was last checked in or out here (or that is not known: copies from before 1 Nov 2026). */
    boolean edited(String local, JsonCodec json) throws IOException {
        Object was = state("synced.json").get(local);
        return was == null || !was.equals(sha256(normal(local, json)));
    }

    private void markSynced(String local, String normalText) throws IOException {
        Map<String, Object> s = state("synced.json");
        s.put(local, sha256(normalText));
        Files.createDirectories(stateFile("x").getParent());
        Files.writeString(stateFile("synced.json"), JsonLite.write(s), StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> state(String name) {
        try { Path f = stateFile(name); if (Files.exists(f)) return new LinkedHashMap<>((Map<String, Object>) JsonLite.parse(Files.readString(f))); }
        catch (Exception ignored) { }
        return new LinkedHashMap<>();
    }

    private static String sha256(String text) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    /**
     * Each private copy linked to the repository, with its state as AA's statuses (1 Nov 2026): checked out to me,
     * unchanged or edited; checked in (an unchanged copy, not checked out); edited but not checked out (check it out again
     * to check the changes in: they are kept). An item with no link is new; so is one whose agent left the repository.
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> states(JsonCodec json) throws IOException {
        Map<String, Object> locks = new HashMap<>();
        Map<String, Map<String, Object>> bots = new HashMap<>();
        if (connected()) for (Object o : (List<Object>) repo("").getOrDefault("bots", List.of())) {
            Map<String, Object> b = (Map<String, Object>) o;
            locks.put(String.valueOf(b.get("path")), b.get("locked_by") == null ? "" : String.valueOf(b.get("locked_by")));
            bots.put(String.valueOf(b.get("path")), b);
        }
        String me = user == null ? null : String.valueOf(user.get("username"));
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : mapping().entrySet()) {
            String local = e.getKey(), repoPath = String.valueOf(e.getValue());
            if (!Files.isRegularFile(ws.resolve(local))) continue;
            boolean edited = edited(local, json);
            Map<String, Object> clones = state("clones.json");
            if (clones.containsKey(local) && connected() && bots.containsKey(repoPath)) {
                // a clone follows the repository: a newer version replaces it (it cannot have changes of its own)
                Object latest = bots.get(repoPath).get("latest_version");
                if (latest != null && !String.valueOf(latest).equals(String.valueOf(clones.get(local)))) {
                    Map<String, Object> got = call("GET", "repo/bot?path=" + enc(repoPath), null, true);
                    String text = json.write(got.get("flow"), true);
                    writeCopy(ws.resolve(local), text);
                    markSynced(local, text);
                    clones.put(local, got.get("version"));
                    saveState("clones.json", clones);
                    edited = false;
                }
            }
            // signed out, the locks are not known: the copy is only "linked" (or edited)
            String status = clones.containsKey(local) ? "cloned" : !connected() ? (edited ? "edited" : "linked")
                    : !locks.containsKey(repoPath) ? "new"
                    : !Objects.equals(me, locks.get(repoPath)) ? (edited ? "edited" : "checked_in")
                    : edited ? "checked_out_edited" : "checked_out";
            Map<String, Object> st = new LinkedHashMap<>();
            st.put("status", status);
            st.put("path", repoPath);
            Object by = locks.get(repoPath);
            if (by != null && !"".equals(by)) st.put("locked_by", by);
            // the repository's facts for the list's columns: its version, labels, code analysis and who changed it last
            Map<String, Object> b = bots.get(repoPath);
            if (b != null) for (String k : List.of("latest_version", "production_version", "labels", "analysis", "updated", "updated_by"))
                if (b.get(k) != null) st.put(k, b.get(k));
            out.put(local, st);
        }
        return Map.of("states", out, "connected", connected());
    }

    /** Revert edits: the private copy goes back to the repository's latest version, as it was checked out. */
    Map<String, Object> revert(String local, JsonCodec json) throws IOException {
        Object repoPath = mapping().get(local);
        if (repoPath == null) throw new OoaException("STUDIO.NOT_LINKED", local + " is not a copy of an agent in the repository", "Only checked-out agents can go back to their checked-out version.");
        Map<String, Object> r = call("GET", "repo/bot?path=" + enc(String.valueOf(repoPath)), null, true);
        String text = json.write(r.get("flow"), true);
        Path p = ws.resolve(local), tmp = p.resolveSibling("." + p.getFileName() + ".saving");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
        markSynced(local, text);
        return Map.of("local", local, "path", repoPath, "version", r.get("version"));
    }

    void undoCheckout(String repoPath) { call("POST", "repo/undo-checkout", Map.of("path", repoPath), true); }
}
