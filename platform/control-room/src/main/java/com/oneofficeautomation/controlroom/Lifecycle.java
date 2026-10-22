package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

import static com.oneofficeautomation.controlroom.ControlRoom.*;
import com.oneofficeautomation.controlroom.ControlRoom.User;

/**
 * Agent lifecycle: moving agents between Command Centers (development, test, production).
 *
 * <p>Export makes a bundle: the chosen agents at a version (latest, production, a number or a label) and, when asked,
 * every agent they call (at its production version, else its latest), with each version's exact content and SHA-256
 * and a checksum over the whole list. Import checks the bundle (format, checksums), validates every agent as check-in
 * does, and then stores them all in one transaction or none: an agent that exists is skipped or gets a new version
 * (overwrite); history is never replaced. Promote exports here and imports into another Command Center over its API,
 * signing in with an API key read from the vault (never typed in).
 */
public final class Lifecycle {
    static final String FORMAT = "ooa.bundle";
    static final int FORMAT_VERSION = 1;
    private final ControlRoom cr;
    private final Db db;

    Lifecycle(ControlRoom cr) { this.cr = cr; this.db = cr.db; }

    @SuppressWarnings("unchecked")
    public Map<String, Object> export(User by, List<Object> paths, Object version, boolean withDependencies, String ip) {
        cr.require(by, "lifecycle.export", "export agents");
        if (paths == null || paths.isEmpty()) throw new OoaException("CR.BAD_INPUT", "Choose at least one agent to export");
        Object v = version == null || String.valueOf(version).isBlank() ? "production" : version;
        LinkedHashMap<String, Map<String, Object>> agents = new LinkedHashMap<>();
        Deque<String[]> todo = new ArrayDeque<>();
        for (Object p : paths) todo.add(new String[]{String.valueOf(p), String.valueOf(v), "chosen"});
        TreeSet<String> toolkits = new TreeSet<>();
        while (!todo.isEmpty()) {
            String[] t = todo.poll();
            String path = t[0];
            if (agents.containsKey(path)) continue;
            if (agents.size() >= 200) throw new OoaException("CR.TOO_LARGE", "A bundle holds at most 200 agents");
            cr.folders.require(by, path, "view", "export agents");
            Map<String, Object> c;
            try { c = cr.content(path, t[1]); }
            catch (OoaException e) {
                // a dependency without a live production version goes in at its latest version
                if (!t[2].equals("dependency") || !"CR.NO_PRODUCTION".equals(e.code())) throw e;
                c = cr.content(path, "latest");
            }
            Map<String, Object> flow = (Map<String, Object>) c.get("flow");
            Map<String, Object> row = db.one("SELECT v.content, v.sha256, b.bot_id, b.name, b.production_version FROM bot_versions v JOIN bots b ON b.path = v.path WHERE v.path = ? AND v.version = ?",
                    path, c.get("version"));
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("path", path);
            a.put("bot_id", row.get("bot_id"));
            a.put("name", row.get("name"));
            a.put("version", c.get("version"));
            a.put("production", Objects.equals(row.get("production_version"), c.get("version")));
            a.put("included_as", t[2]);
            a.put("sha256", row.get("sha256"));
            a.put("content", row.get("content"));
            agents.put(path, a);
            if (!Workflows.isWorkflow(path) && !Workflows.isForm(path)) toolkits.addAll(ControlRoom.toolkitsOf(flow));
            if (withDependencies) for (String id : ControlRoom.childBotIds(flow)) {
                Map<String, Object> b = db.one("SELECT path FROM bots WHERE bot_id = ? AND deleted_at IS NULL", id);
                if (b == null) throw new OoaException("CR.NOT_FOUND", "Agent " + path + " calls agent id '" + id + "', which is not in this Command Center",
                        "Check that agent in, or export without dependencies.");
                todo.add(new String[]{(String) b.get("path"), "production", "dependency"});
            }
        }
        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("format", FORMAT);
        bundle.put("format_version", FORMAT_VERSION);
        bundle.put("exported_at", now().toString());
        bundle.put("exported_by", by.username());
        bundle.put("source", String.valueOf(cr.platform.setting("public_url")));
        bundle.put("agents", new ArrayList<>(agents.values()));
        bundle.put("toolkits", new ArrayList<>(toolkits));
        bundle.put("checksum", checksum(new ArrayList<>(agents.values())));
        cr.audit.record(by.username(), "lifecycle.export", "bundle", null, Map.of("agents", new ArrayList<>(agents.keySet()), "version", String.valueOf(v)), "ok", ip);
        return bundle;
    }

    static String checksum(List<?> agents) {
        StringBuilder sb = new StringBuilder();
        for (Object o : agents) { Map<?, ?> a = (Map<?, ?>) o; sb.append(a.get("path")).append(':').append(a.get("version")).append(':').append(a.get("sha256")).append('\n'); }
        return Security.sha256(sb.toString());
    }

    /**
     * Imports a bundle. conflict: skip (an agent that exists stays as it is) or overwrite (it gets a new version from the
     * bundle). production: the imported versions become live. targetFolder puts the agents under that folder. Nothing is
     * stored unless every agent passes.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> importBundle(User by, Map<String, Object> bundle, String conflict, boolean production, String targetFolder, String ip) {
        cr.require(by, "lifecycle.import", "import agents");
        String mode = conflict == null || conflict.isBlank() ? "skip" : conflict;
        if (!Set.of("skip", "overwrite").contains(mode)) throw new OoaException("CR.BAD_INPUT", "conflict is skip or overwrite");
        if (bundle == null || !FORMAT.equals(bundle.get("format")) || !(bundle.get("agents") instanceof List<?> list))
            throw new OoaException("CR.BAD_BUNDLE", "This is not an agent bundle", "Export one with Lifecycle > Export in a Command Center.");
        if (!(bundle.get("format_version") instanceof Number fv) || fv.intValue() > FORMAT_VERSION)
            throw new OoaException("CR.BAD_BUNDLE", "The bundle was made by a newer Command Center", "Update this Command Center first.");
        if (!checksum(list).equals(bundle.get("checksum"))) throw new OoaException("CR.BAD_BUNDLE", "The bundle's checksum does not match: it was changed or damaged");
        String prefix = targetFolder == null || targetFolder.isBlank() ? "" : Folders.normalize(targetFolder) + "/";
        List<Map<String, Object>> results = new ArrayList<>();
        List<Object[]> toStore = new ArrayList<>();
        boolean failed = false;
        for (Object o : list) {
            Map<String, Object> a = (Map<String, Object>) o;
            String path = prefix + a.get("path");
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("path", path);
            r.put("bot_id", a.get("bot_id"));
            try {
                String content = String.valueOf(a.get("content"));
                if (!Security.sha256(content).equals(a.get("sha256"))) throw new OoaException("CR.BAD_BUNDLE", "The content of " + a.get("path") + " does not match its SHA-256");
                Map<String, Object> flow = (Map<String, Object>) JsonLite.parse(content);
                cr.folders.require(by, path, production ? "manage" : "edit", "import agents");
                Map<String, Object> existing = db.one("SELECT path, deleted_at, latest_version FROM bots WHERE path = ?", path);
                Map<String, Object> sameId = db.one("SELECT path FROM bots WHERE bot_id = ? AND path <> ?", a.get("bot_id"), path);
                if (sameId != null) throw new OoaException("CR.DUPLICATE_ID", "Agent id '" + a.get("bot_id") + "' is already used here by " + sameId.get("path"));
                if (existing != null && existing.get("deleted_at") != null) throw new OoaException("CR.DELETED", "A deleted agent is at " + path, "Recover or rename it first.");
                if (existing != null && mode.equals("skip")) { r.put("status", "skipped"); r.put("version", existing.get("latest_version")); results.add(r); continue; }
                List<Map<String, Object>> problems = cr.problemsOf(path, flow);
                if (!problems.isEmpty()) throw new OoaException("CR.INVALID_BOT", problems.size() + " validation problem(s): " + problems.get(0).get("message"), null, false, Map.of("problems", problems));
                r.put("status", existing == null ? "created" : "updated");
                toStore.add(new Object[]{path, flow, a, r});
            } catch (OoaException e) {
                failed = true;
                r.put("status", "failed");
                r.put("error", e.toMap());
            }
            results.add(r);
        }
        if (failed) {
            for (Map<String, Object> r : results) if (!"failed".equals(r.get("status")) && !"skipped".equals(r.get("status"))) r.put("status", "not imported");
            cr.audit.record(by.username(), "lifecycle.import", "bundle", null, Map.of("agents", results.size(), "from", String.valueOf(bundle.get("source"))), "failed", ip);
            return Map.of("ok", false, "imported", 0, "results", results);
        }
        String from = String.valueOf(bundle.getOrDefault("source", "")).isBlank() ? "another Command Center" : String.valueOf(bundle.get("source"));
        db.tx(() -> {
            for (Object[] s : toStore) {
                Map<String, Object> a = (Map<String, Object>) s[2], r = (Map<String, Object>) s[3];
                Map<String, Object> stored = cr.storeVersion(by, (String) s[0], (Map<String, Object>) s[1],
                        "Imported from " + from + " (version " + a.get("version") + ", exported by " + bundle.get("exported_by") + ")", production, false, ip);
                r.put("version", stored.get("version"));
            }
            return null;
        });
        cr.audit.record(by.username(), "lifecycle.import", "bundle", null, Map.of("agents", results.size(), "stored", toStore.size(), "from", from, "conflict", mode), "ok", ip);
        return Map.of("ok", true, "imported", toStore.size(), "results", results);
    }

    /**
     * Exports the agents here and imports them into another Command Center. The API key for the other Command Center is
     * read from a vault folder the caller may use, and is sent only to that Command Center.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> promote(User by, Map<String, Object> req, String ip) {
        cr.require(by, "lifecycle.export", "promote agents");
        String target = String.valueOf(req.getOrDefault("target_url", "")).trim().replaceAll("/+$", "");
        if (!target.matches("^https?://[^\\s/]+(:\\d+)?(/\\S*)?$")) throw new OoaException("CR.BAD_INPUT", "target_url is the other Command Center's address, e.g. https://cc-prod.example.com");
        String ref = String.valueOf(req.getOrDefault("api_key_ref", ""));
        String[] p = ref.split("/");
        if (p.length != 3) throw new OoaException("CR.BAD_INPUT", "api_key_ref is a vault reference: vault folder/credential/attribute", "Store the other Command Center's API key in a vault folder first.");
        Map<String, Object> locker = db.one("SELECT * FROM lockers WHERE name = ?", p[0]);
        if (locker == null) throw notFound("Vault folder " + p[0]);
        if (!cr.ownsLocker(by, locker) && !cr.consumes(by.username(), by.role(), locker)) throw new OoaException("VAULT.DENIED", "You may not use vault folder '" + p[0] + "'");
        Map<String, Object> bundle = export(by, (List<Object>) req.get("paths"), req.get("version"), !Boolean.FALSE.equals(req.get("include_dependencies")), ip);
        String key = cr.systemCredential(ref);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bundle", bundle);
        body.put("conflict", req.getOrDefault("conflict", "skip"));
        body.put("production", Boolean.TRUE.equals(req.get("production")));
        if (req.get("target_folder") instanceof String tf && !tf.isBlank()) body.put("target_folder", tf);
        HttpResponse<String> res;
        try {
            res = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build().send(HttpRequest.newBuilder(URI.create(target + "/api/lifecycle/import"))
                    .timeout(Duration.ofSeconds(120)).header("Content-Type", "application/json").header("Authorization", "Bearer " + key)
                    .POST(HttpRequest.BodyPublishers.ofString(JsonLite.write(body))).build(), HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException e) {
            cr.audit.record(by.username(), "lifecycle.promote", "bundle", target, Map.of("error", String.valueOf(e.getMessage())), "failed", ip);
            throw new OoaException("CR.PROMOTE_FAILED", "Cannot reach " + target + ": " + e.getMessage(), "Check the address and that the other Command Center is running.", true, null);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new OoaException("RUN.CANCELLED", "Interrupted"); }
        Object parsed;
        try { parsed = JsonLite.parse(res.body()); } catch (IllegalArgumentException e) { parsed = Map.of("raw", res.body().length() > 300 ? res.body().substring(0, 300) : res.body()); }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("target", target);
        out.put("status", res.statusCode());
        out.put("result", parsed);
        boolean ok = res.statusCode() == 200 && parsed instanceof Map<?, ?> m && Boolean.TRUE.equals(m.get("ok"));
        out.put("ok", ok);
        cr.audit.record(by.username(), "lifecycle.promote", "bundle", target, Map.of("agents", ((List<?>) bundle.get("agents")).size(), "status", res.statusCode()), ok ? "ok" : "failed", ip);
        return out;
    }
}
