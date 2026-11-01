package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.controlroom.ControlRoom.User;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;

import java.util.*;

import static com.oneofficeautomation.controlroom.ControlRoom.*;

/**
 * Administration > Policies, as AA's (checked 1 Nov 2026): named policies given to folders, of two kinds. Code analysis
 * policies hold the rules agents are checked against and whether errors stop check-in; AI guardrails say how AI steps
 * must treat personal data, which models they may use and how harmful content is handled. A folder uses its own policy
 * of a kind, or the nearest folder's above it; one policy of each kind per folder. Folders with no code analysis policy
 * use the default one (code_policy, "Default code analysis"); folders with no AI guardrails keep each AI step's settings.
 * Seeing policies needs repo.view; changing them needs settings.manage, as the default policy did.
 */
final class Policies {
    static final String DEFAULT_ID = "default", DEFAULT_NAME = "Default code analysis";
    static final Map<String, String> KINDS = Map.of("code", "Code analysis", "ai", "AI guardrails");

    private final ControlRoom cr;
    private final Db db;

    Policies(ControlRoom cr) { this.cr = cr; this.db = cr.db; }

    /** Every policy, the default code analysis one first. */
    List<Map<String, Object>> list(User by) {
        cr.require(by, "repo.view", "see policies");
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(defaultPolicy());
        for (Map<String, Object> r : db.query("SELECT id, name, kind, description, rules, folders, created, created_by, updated, updated_by FROM policies ORDER BY kind, name"))
            out.add(row(r));
        return out;
    }

    Map<String, Object> get(User by, String id) {
        cr.require(by, "repo.view", "see policies");
        if (DEFAULT_ID.equals(id)) return defaultPolicy();
        Map<String, Object> r = db.one("SELECT id, name, kind, description, rules, folders, created, created_by, updated, updated_by FROM policies WHERE id = ?", id);
        if (r == null) throw notFound("Policy " + id);
        return row(r);
    }

    /** Makes (no id) or changes a policy; the default code analysis policy keeps its name and covers every other folder. */
    @SuppressWarnings("unchecked")
    Map<String, Object> save(User by, String id, Map<String, Object> body, String ip) {
        cr.require(by, "settings.manage", "change policies");
        Map<String, Object> rulesIn = body.get("rules") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        if (DEFAULT_ID.equals(id)) { cr.codeAnalysis.savePolicy(by, rulesIn, ip); return defaultPolicy(); }
        Map<String, Object> old = id == null ? null : db.one("SELECT kind FROM policies WHERE id = ?", id);
        if (id != null && old == null) throw notFound("Policy " + id);
        String kind = old != null ? (String) old.get("kind") : String.valueOf(body.get("kind"));
        if (!KINDS.containsKey(kind)) throw new OoaException("CR.BAD_INPUT", "A policy is code (Code analysis) or ai (AI guardrails)");
        String name = body.get("name") == null ? "" : String.valueOf(body.get("name")).trim();
        if (name.isEmpty() || name.length() > 120) throw new OoaException("CR.BAD_INPUT", "Give the policy a name (up to 120 characters)");
        if (name.equalsIgnoreCase(DEFAULT_NAME) || db.one("SELECT id FROM policies WHERE LOWER(name) = ? AND id <> ?", name.toLowerCase(Locale.ROOT), id == null ? "" : id) != null)
            throw new OoaException("CR.CONFLICT", "A policy is already called '" + name + "'", "Choose another name.");
        List<String> folders = new ArrayList<>();
        if (body.get("folders") instanceof List<?> l) for (Object f : l) {
            String p = Folders.normalize(String.valueOf(f));
            if (!folders.contains(p)) folders.add(p);
        }
        // one policy of each kind per folder, so a folder's rules are never in doubt
        for (Map<String, Object> other : db.query("SELECT id, name, folders FROM policies WHERE kind = ? AND id <> ?", kind, id == null ? "" : id))
            for (Object f : (List<Object>) JsonLite.parse((String) other.get("folders")))
                if (folders.contains(f)) throw new OoaException("CR.CONFLICT", "The folder '" + f + "' already has the " + KINDS.get(kind).toLowerCase(Locale.ROOT) + " policy '" + other.get("name") + "'",
                        "Take the folder out of that policy first, or change that policy instead.");
        Map<String, Object> rules = kind.equals("code") ? CodeAnalysis.clean(rulesIn) : cleanGuardrails(rulesIn);
        String description = body.get("description") == null ? "" : String.valueOf(body.get("description")).trim();
        if (description.length() > 500) throw new OoaException("CR.BAD_INPUT", "The description is up to 500 characters");
        String pid = id == null ? ControlRoom.id("pol") : id;
        if (id == null) db.update("INSERT INTO policies (id, name, kind, description, rules, folders, created, created_by, updated, updated_by) VALUES (?,?,?,?,?,?,?,?,?,?)",
                pid, name, kind, description, JsonLite.write(rules), JsonLite.write(folders), now(), by.username(), now(), by.username());
        else db.update("UPDATE policies SET name = ?, description = ?, rules = ?, folders = ?, updated = ?, updated_by = ? WHERE id = ?",
                name, description, JsonLite.write(rules), JsonLite.write(folders), now(), by.username(), pid);
        cr.audit.record(by.username(), id == null ? "policy.create" : "policy.update", "policy", pid, Map.of("name", name, "kind", kind, "folders", folders), "ok", ip);
        return get(by, pid);
    }

    void delete(User by, String id, String ip) {
        cr.require(by, "settings.manage", "change policies");
        if (DEFAULT_ID.equals(id)) throw new OoaException("CR.BAD_INPUT", "The default code analysis policy cannot be deleted", "Change its rules instead.");
        Map<String, Object> r = db.one("SELECT name, kind FROM policies WHERE id = ?", id);
        if (r == null) throw notFound("Policy " + id);
        db.update("DELETE FROM policies WHERE id = ?", id);
        cr.audit.record(by.username(), "policy.delete", "policy", id, Map.of("name", r.get("name"), "kind", r.get("kind")), "ok", ip);
    }

    /** The policy of a kind for a folder: its own, or the nearest folder's above it; null when there is none. */
    @SuppressWarnings("unchecked")
    Map<String, Object> find(String kind, String folder) {
        List<Map<String, Object>> all = db.query("SELECT id, name, kind, description, rules, folders, created, created_by, updated, updated_by FROM policies WHERE kind = ?", kind);
        if (all.isEmpty()) return null;
        for (String f = folder == null ? "" : folder; ; f = Folders.parentOf(f)) {
            for (Map<String, Object> r : all) if (((List<Object>) JsonLite.parse((String) r.get("folders"))).contains(f)) return row(r);
            if (f.isEmpty()) return null;
        }
    }

    /**
     * AI guardrails as saved: mask (personal data always masked, whatever an AI step says), models (the model connections
     * agents may use; empty: any) and content (how answers are checked for harmful content: off, log or block).
     */
    Map<String, Object> cleanGuardrails(Map<String, Object> in) {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("mask", !Boolean.FALSE.equals(in.get("mask")));
        List<String> models = new ArrayList<>();
        if (in.get("models") instanceof List<?> l) for (Object o : l) {
            String n = String.valueOf(o);
            if (db.one("SELECT name FROM model_connections WHERE name = ?", n) == null)
                throw new OoaException("CR.BAD_INPUT", "There is no model connection named '" + n + "'", "Pick models from Model Connections.");
            if (!models.contains(n)) models.add(n);
        }
        g.put("models", models);
        String content = in.get("content") == null ? "off" : String.valueOf(in.get("content"));
        if (!Set.of("off", "log", "block").contains(content)) throw new OoaException("CR.BAD_INPUT", "The content check is off, log or block");
        g.put("content", content);
        return g;
    }

    /** The AI guardrails for an agent, by its folder, with the policy's name, as sent with its runs; null when there are none. */
    @SuppressWarnings("unchecked")
    Map<String, Object> guardrailsFor(String agentPath) {
        Map<String, Object> p = find("ai", Folders.folderOf(agentPath == null ? "" : agentPath));
        if (p == null) return null;
        Map<String, Object> g = new LinkedHashMap<>((Map<String, Object>) p.get("rules"));
        g.put("name", p.get("name"));
        return g;
    }

    private Map<String, Object> defaultPolicy() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("id", DEFAULT_ID);
        d.put("name", DEFAULT_NAME);
        d.put("kind", "code");
        d.put("description", "Rules for every folder without its own code analysis policy.");
        d.put("rules", cr.codeAnalysis.policy());
        d.put("folders", List.of());
        d.put("built_in", true);
        Map<String, Object> row = db.one("SELECT updated, updated_by FROM code_policy WHERE id = 1");
        if (row != null) { d.put("updated", row.get("updated")); d.put("updated_by", row.get("updated_by")); }
        return d;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> row(Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>(r);
        Map<String, Object> saved = (Map<String, Object>) JsonLite.parse((String) r.get("rules"));
        m.put("rules", "code".equals(r.get("kind")) ? CodeAnalysis.effective(saved) : saved);
        m.put("folders", JsonLite.parse((String) r.get("folders")));
        m.put("built_in", false);
        return m;
    }
}
