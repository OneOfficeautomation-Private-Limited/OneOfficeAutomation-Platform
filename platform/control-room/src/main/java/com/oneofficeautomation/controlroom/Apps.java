package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.controlroom.ControlRoom.User;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Low-code apps (gap G67, after UiPath Apps and Power Apps): pages of components, made in Build > Apps, that business
 * people use in the console (Apps menu) without opening Studio. Components: heading, text, input fields, buttons,
 * a table of data store records, and the last result. A button does one action: run an agent with the fields as its
 * inputs (and show its outputs), add a data store record, add a work queue item, or open another page.
 * <ul>
 * <li>An app acts with the rights of the person who published it (apps.manage), checked again at every use, never
 * with the rights of whoever uses it; so a business person can use an app without the right to start runs. Saving an
 * app checks the publisher may do every action in it (run the agents, change the entities, work the queues).</li>
 * <li>The browser only says which button was pressed and the field values; what the button does comes from the saved
 * app, so nobody can make an app do something it was not built to do.</li>
 * <li>Who may use it: everyone signed in, or only some roles. Every action is audited as app:&lt;name&gt; for the user.</li>
 * </ul>
 */
public final class Apps {
    static final Pattern REF = Pattern.compile("\\{([A-Za-z][A-Za-z0-9_]{0,59})\\}");
    static final List<String> KINDS = List.of("heading", "text", "input", "button", "records", "result");
    static final List<String> INPUT_TYPES = List.of("text", "textarea", "number", "date", "boolean", "choice");
    static final List<String> ACTIONS = List.of("run_agent", "create_record", "add_queue_item", "go_page");

    private final ControlRoom cr;
    private final Db db;

    Apps(ControlRoom cr) { this.cr = cr; this.db = cr.db; }

    // ===================== definition =====================

    private static String str(Object o) { return o == null ? "" : String.valueOf(o).trim(); }

    /** The app's pages, checked: ids unique, fields named, buttons with known actions whose {field} references exist. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> clean(Object raw) {
        if (!(raw instanceof Map<?, ?> d) || !(d.get("pages") instanceof List<?> pages) || pages.isEmpty())
            throw new OoaException("CR.BAD_INPUT", "An app has at least one page");
        if (pages.size() > 30) throw new OoaException("CR.BAD_INPUT", "An app has at most 30 pages");
        List<Object> outPages = new ArrayList<>();
        Set<String> pageIds = new HashSet<>(), compIds = new HashSet<>();
        for (Object po : pages) {
            Map<String, Object> p = (Map<String, Object>) po;
            String pid = str(p.get("id"));
            if (!pid.matches("[A-Za-z0-9_-]{1,40}") || !pageIds.add(pid)) throw new OoaException("CR.BAD_INPUT", "Each page has its own id (letters, digits, _ -)");
            List<Object> comps = p.get("components") instanceof List<?> l ? (List<Object>) l : List.of();
            if (comps.size() > 100) throw new OoaException("CR.BAD_INPUT", "A page has at most 100 components");
            Set<String> fields = new HashSet<>();
            List<Object> outComps = new ArrayList<>();
            for (Object co : comps) {
                Map<String, Object> c = new LinkedHashMap<>((Map<String, Object>) co);
                String kind = str(c.get("kind")), cid = str(c.get("id"));
                if (!KINDS.contains(kind)) throw new OoaException("CR.BAD_INPUT", "A component is one of " + String.join(", ", KINDS));
                if (!cid.matches("[A-Za-z0-9_-]{1,40}") || !compIds.add(cid)) throw new OoaException("CR.BAD_INPUT", "Each component has its own id");
                if (kind.equals("input")) {
                    String n = str(c.get("name"));
                    if (!n.matches("[A-Za-z][A-Za-z0-9_]{0,59}") || !fields.add(n)) throw new OoaException("CR.BAD_INPUT", "Each field on a page has its own name (letters, digits and _)");
                    String t = c.get("type") == null ? "text" : str(c.get("type"));
                    if (!INPUT_TYPES.contains(t)) throw new OoaException("CR.BAD_INPUT", "A field's type is one of " + String.join(", ", INPUT_TYPES));
                    if (t.equals("choice") && !(c.get("choices") instanceof List<?> ch && !ch.isEmpty())) throw new OoaException("CR.BAD_INPUT", "The choice field " + n + " needs its choices");
                }
                outComps.add(c);
            }
            for (Object co : outComps) {
                Map<String, Object> c = (Map<String, Object>) co;
                if (!"button".equals(c.get("kind"))) continue;
                Map<String, Object> a = c.get("action") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
                String k = str(a.get("kind"));
                if (!ACTIONS.contains(k)) throw new OoaException("CR.BAD_INPUT", "The button " + c.get("label") + " needs an action: " + String.join(", ", ACTIONS));
                for (String ref : refs(a)) if (!fields.contains(ref))
                    throw new OoaException("CR.BAD_INPUT", "The button " + c.get("label") + " uses {" + ref + "}, which is not a field on its page");
            }
            Map<String, Object> np = new LinkedHashMap<>();
            np.put("id", pid); np.put("title", str(p.get("title"))); np.put("components", outComps);
            outPages.add(np);
        }
        for (Object po : outPages) for (Object co : (List<Object>) ((Map<String, Object>) po).get("components")) {
            Map<String, Object> c = (Map<String, Object>) co;
            if ("button".equals(c.get("kind")) && c.get("action") instanceof Map<?, ?> a && "go_page".equals(a.get("kind")) && !pageIds.contains(str(a.get("page"))))
                throw new OoaException("CR.BAD_INPUT", "The button " + c.get("label") + " opens a page the app does not have");
        }
        String json = JsonLite.write(Map.of("pages", outPages));
        if (json.length() > 200_000) throw new OoaException("CR.BAD_INPUT", "The app is too large (at most 200,000 characters)");
        return Map.of("pages", outPages);
    }

    /** The {field} names an action's values use. */
    @SuppressWarnings("unchecked")
    static Set<String> refs(Object o) {
        Set<String> out = new LinkedHashSet<>();
        if (o instanceof String s) { Matcher m = REF.matcher(s); while (m.find()) out.add(m.group(1)); }
        else if (o instanceof Map<?, ?> m) for (Object v : m.values()) out.addAll(refs(v));
        else if (o instanceof List<?> l) for (Object v : l) out.addAll(refs(v));
        return out;
    }

    /** A value with its {field} references filled in: "{amount}" alone keeps the field's own value (a number stays a number). */
    @SuppressWarnings("unchecked")
    static Object fill(Object o, Map<String, Object> fields) {
        if (o instanceof String s) {
            Matcher whole = REF.matcher(s);
            if (whole.matches()) return fields.get(whole.group(1));
            Matcher m = REF.matcher(s);
            StringBuilder b = new StringBuilder();
            while (m.find()) m.appendReplacement(b, Matcher.quoteReplacement(fields.get(m.group(1)) == null ? "" : String.valueOf(fields.get(m.group(1)))));
            m.appendTail(b);
            return b.toString();
        }
        if (o instanceof Map<?, ?> m) { Map<String, Object> out = new LinkedHashMap<>(); m.forEach((k, v) -> out.put(String.valueOf(k), fill(v, fields))); return out; }
        if (o instanceof List<?> l) { List<Object> out = new ArrayList<>(); for (Object v : l) out.add(fill(v, fields)); return out; }
        return o;
    }

    /** The publisher as a person with their current role; refused when the account is gone or off. */
    User publisher(Map<String, Object> app) {
        Map<String, Object> u = db.one("SELECT id, username, role, display_name FROM users WHERE username = ? AND enabled = TRUE", app.get("published_by"));
        if (u == null) throw new OoaException("APP.PUBLISHER_GONE", "The person who published the app " + app.get("name") + " is no longer active", "Ask an app maker to publish it again.");
        return new User((String) u.get("id"), (String) u.get("username"), (String) u.get("role"), (String) u.get("display_name"), false);
    }

    /** The publisher may do every action in the app: run its agents, change its entities, work its queues. */
    @SuppressWarnings("unchecked")
    void checkActions(User by, Map<String, Object> def) {
        for (Object po : (List<Object>) def.get("pages")) for (Object co : (List<Object>) ((Map<String, Object>) po).get("components")) {
            Map<String, Object> c = (Map<String, Object>) co;
            if ("records".equals(c.get("kind"))) { cr.require(by, "data.view", "show data store records in apps"); cr.dataStore.entity(str(c.get("entity"))); }
            if (!"button".equals(c.get("kind"))) continue;
            Map<String, Object> a = (Map<String, Object>) c.get("action");
            switch (str(a.get("kind"))) {
                case "run_agent" -> {
                    String path = str(a.get("agent"));
                    cr.require(by, "runs.start", "run agents from apps");
                    cr.folders.require(by.username(), by.role(), path, "run", "run agents from apps");
                    if (db.one("SELECT path FROM bots WHERE path = ? AND production_version IS NOT NULL", path) == null)
                        throw new OoaException("CR.BAD_INPUT", "The agent " + path + " has no production version", "Put a version in production first.");
                    if (str(a.get("runner")).isEmpty() && str(a.get("pool")).isEmpty()) throw new OoaException("CR.BAD_INPUT", "The button " + c.get("label") + " needs a runner or a runner pool");
                }
                case "create_record" -> { cr.require(by, "data.edit", "add data store records from apps"); cr.dataStore.entity(str(a.get("entity"))); }
                case "add_queue_item" -> {
                    cr.require(by, "queues.work", "add work items from apps");
                    if (db.one("SELECT id FROM queues WHERE name = ?", str(a.get("queue"))) == null) throw new OoaException("CR.NOT_FOUND", "No work queue '" + a.get("queue") + "'");
                }
                default -> { }
            }
        }
    }

    private Map<String, Object> row(Map<String, Object> r, boolean full) {
        Map<String, Object> m = new LinkedHashMap<>(r);
        m.put("audience", r.get("audience") == null || str(r.get("audience")).isEmpty() ? List.of() : List.of(str(r.get("audience")).split(",")));
        if (full) m.put("definition", JsonLite.parse((String) r.get("definition"))); else m.remove("definition");
        return m;
    }

    static final String COLS = "id, name, description, definition, audience, published_by, created, created_by, updated, updated_by";

    Map<String, Object> app(String id) {
        Map<String, Object> r = db.one("SELECT " + COLS + " FROM apps WHERE id = ?", id);
        if (r == null) throw new OoaException("CR.NOT_FOUND", "App " + id + " not found");
        return r;
    }

    boolean mayUse(User by, Map<String, Object> app) {
        String aud = str(app.get("audience"));
        return aud.isEmpty() || cr.can(by, "apps.manage") || Arrays.asList(aud.split(",")).contains(by.role());
    }

    /** The apps the person may use (and, for app makers, every app). */
    public List<Map<String, Object>> list(User by) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : db.query("SELECT " + COLS + " FROM apps ORDER BY name")) if (mayUse(by, r)) out.add(row(r, false));
        return out;
    }

    public Map<String, Object> get(User by, String id) {
        Map<String, Object> r = app(id);
        if (!mayUse(by, r)) throw new OoaException("CR.FORBIDDEN", "This app is not shared with you");
        Map<String, Object> m = row(r, true);
        m.put("can_edit", cr.can(by, "apps.manage"));
        return m;
    }

    public Map<String, Object> save(User by, String id, Map<String, Object> b, String ip) {
        cr.require(by, "apps.manage", "make apps");
        String name = str(b.get("name"));
        if (name.isEmpty() || name.length() > 120) throw new OoaException("CR.BAD_INPUT", "Give the app a name (up to 120 characters)");
        String desc = str(b.get("description"));
        if (desc.length() > 500) throw new OoaException("CR.BAD_INPUT", "The description is up to 500 characters");
        Map<String, Object> def = clean(b.get("definition"));
        checkActions(by, def);
        List<String> aud = new ArrayList<>();
        if (b.get("audience") instanceof List<?> l) for (Object o : l) { String r = str(o); if (!r.isEmpty() && !aud.contains(r)) aud.add(r); }
        if (db.one("SELECT id FROM apps WHERE LOWER(name) = ? AND id <> ?", name.toLowerCase(Locale.ROOT), id == null ? "" : id) != null)
            throw new OoaException("CR.CONFLICT", "An app is already called '" + name + "'");
        String aid = id == null ? ControlRoom.id("app") : id;
        if (id == null) db.update("INSERT INTO apps (id, name, description, definition, audience, published_by, created, created_by, updated, updated_by) VALUES (?,?,?,?,?,?,?,?,?,?)",
            aid, name, desc, JsonLite.write(def), String.join(",", aud), by.username(), ControlRoom.now(), by.username(), ControlRoom.now(), by.username());
        else {
            app(id);
            db.update("UPDATE apps SET name = ?, description = ?, definition = ?, audience = ?, published_by = ?, updated = ?, updated_by = ? WHERE id = ?",
                name, desc, JsonLite.write(def), String.join(",", aud), by.username(), ControlRoom.now(), by.username(), id);
        }
        cr.audit.record(by.username(), id == null ? "app.create" : "app.update", "app", aid, Map.of("name", name, "audience", aud), "ok", ip);
        return get(by, aid);
    }

    public void delete(User by, String id, String ip) {
        cr.require(by, "apps.manage", "make apps");
        Map<String, Object> r = app(id);
        db.update("DELETE FROM app_embeds WHERE app_id = ?", id);
        db.update("DELETE FROM apps WHERE id = ?", id);
        cr.audit.record(by.username(), "app.delete", "app", id, Map.of("name", r.get("name")), "ok", ip);
    }

    // ===================== using an app =====================

    @SuppressWarnings("unchecked")
    private static Map<String, Object> component(Map<String, Object> def, String compId) {
        for (Object po : (List<Object>) def.get("pages")) for (Object co : (List<Object>) ((Map<String, Object>) po).get("components"))
            if (compId.equals(((Map<String, Object>) co).get("id"))) return (Map<String, Object>) co;
        throw new OoaException("CR.NOT_FOUND", "The app has no component " + compId);
    }

    /** A button was pressed: its saved action is done with the publisher's rights, with the page's field values. */
    public Map<String, Object> act(User by, String id, Map<String, Object> body, String ip) {
        Map<String, Object> app = app(id);
        if (!mayUse(by, app)) throw new OoaException("CR.FORBIDDEN", "This app is not shared with you");
        return actAs(app, by.username(), by.username(), body, ip);
    }

    /**
     * A button pressed by someone: owner is who may follow the runs it starts (a user name, or embed:&lt;link id&gt;),
     * user how the audit trail names them.
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> actAs(Map<String, Object> app, String owner, String user, Map<String, Object> body, String ip) {
        String id = (String) app.get("id");
        Map<String, Object> def = (Map<String, Object>) JsonLite.parse((String) app.get("definition"));
        Map<String, Object> c = component(def, str(body.get("component")));
        if (!"button".equals(c.get("kind"))) throw new OoaException("CR.BAD_INPUT", "Only buttons act");
        Map<String, Object> fields = body.get("fields") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        Map<String, Object> a = (Map<String, Object>) c.get("action");
        User pub = publisher(app);
        String actor = "app:" + app.get("name") + " for " + user;
        Map<String, Object> result = new LinkedHashMap<>();
        switch (str(a.get("kind"))) {
            case "run_agent" -> {
                Map<String, Object> inputs = (Map<String, Object>) fill(a.getOrDefault("inputs", Map.of()), fields);
                inputs.values().removeIf(Objects::isNull);
                Map<String, Object> r = cr.startRun(pub.username(), pub.role(), str(a.get("agent")), str(a.get("runner")).isEmpty() ? null : str(a.get("runner")),
                    str(a.get("pool")).isEmpty() ? null : str(a.get("pool")), inputs, "app:" + id, ip, 0, null, null, null, null);
                db.update("INSERT INTO app_runs (run_id, app_id, username, created) VALUES (?,?,?,?)", r.get("run_id"), id, owner, ControlRoom.now());
                result.put("run_id", r.get("run_id"));
                result.put("status", "queued");
            }
            case "create_record" -> {
                cr.require(pub, "data.edit", "add data store records from apps");
                Map<String, Object> e = cr.dataStore.entity(str(a.get("entity")));
                Map<String, Object> rec = cr.dataStore.create(e, (Map<String, Object>) fill(a.getOrDefault("values", Map.of()), fields), actor);
                result.put("record_id", rec.get("id"));
                result.put("outputs", rec.get("data"));
            }
            case "add_queue_item" -> {
                cr.require(pub, "queues.work", "add work items from apps");
                Map<String, Object> q = db.one("SELECT id FROM queues WHERE name = ?", str(a.get("queue")));
                if (q == null) throw new OoaException("CR.NOT_FOUND", "No work queue '" + a.get("queue") + "'");
                Map<String, Object> added = cr.platform.addItemsAs(pub, actor, (String) q.get("id"), List.of(fill(a.getOrDefault("data", Map.of()), fields)), ip);
                result.put("item_id", ((List<Object>) added.get("ids")).get(0));
            }
            case "go_page" -> { result.put("page", a.get("page")); return result; }
            default -> throw new OoaException("CR.BAD_INPUT", "Unknown action");
        }
        result.put("message", fill(str(a.get("success")).isEmpty() ? "Done." : str(a.get("success")), fields));
        cr.audit.record(actor, "app.act", "app", id, Map.of("button", str(c.get("label")), "action", a.get("kind")), "ok", ip);
        return result;
    }

    /** How a run started from the app is going, and its outputs when it has finished: only for the person who started it. */
    public Map<String, Object> runStatus(User by, String id, String runId) { return runStatusFor(id, by.username(), runId); }

    Map<String, Object> runStatusFor(String id, String owner, String runId) {
        if (db.one("SELECT run_id FROM app_runs WHERE run_id = ? AND app_id = ? AND username = ?", runId, id, owner) == null)
            throw new OoaException("CR.NOT_FOUND", "Run " + runId + " was not started by you from this app");
        Map<String, Object> r = db.one("SELECT status, outputs, error FROM runs WHERE id = ?", runId);
        if (r == null) throw new OoaException("CR.NOT_FOUND", "Run " + runId + " not found");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", r.get("status"));
        m.put("outputs", r.get("outputs") == null ? Map.of() : JsonLite.parse((String) r.get("outputs")));
        if (r.get("error") != null) m.put("error", r.get("error"));
        return m;
    }

    /** The records a records component shows, with the publisher's right to see them. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> records(User by, String id, String compId, String search) {
        Map<String, Object> app = app(id);
        if (!mayUse(by, app)) throw new OoaException("CR.FORBIDDEN", "This app is not shared with you");
        return recordsFor(app, compId, search);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> recordsFor(Map<String, Object> app, String compId, String search) {
        Map<String, Object> c = component((Map<String, Object>) JsonLite.parse((String) app.get("definition")), compId);
        if (!"records".equals(c.get("kind"))) throw new OoaException("CR.BAD_INPUT", "Not a records table");
        cr.require(publisher(app), "data.view", "show data store records in apps");
        Map<String, Object> e = cr.dataStore.entity(str(c.get("entity")));
        int limit = c.get("limit") instanceof Number n ? Math.min(500, n.intValue()) : 100;
        return cr.dataStore.query(e, c.get("filters") instanceof List<?> l ? (List<Object>) l : List.of(), str(c.get("sort")).isEmpty() ? null : str(c.get("sort")),
            Boolean.TRUE.equals(c.get("descending")), limit, 0, search);
    }
}
