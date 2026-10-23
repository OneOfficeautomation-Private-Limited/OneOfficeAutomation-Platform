package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;

import java.util.*;

import static com.oneofficeautomation.controlroom.ControlRoom.*;
import com.oneofficeautomation.controlroom.ControlRoom.User;

/**
 * Prompt Skills kept in the Command Center: named, reviewed prompt templates ({system, prompt, connection, output,
 * temperature, inputs}) that agents run with ooa.ai_skills/run. Each save is a new version. A skill is private to its
 * owner until shared; templates are starting points people copy. Runs receive the skills their agents name (shared
 * ones, and the owner's own private ones).
 */
public final class PromptSkills {
    static final int MAX_DEFINITION = 100_000;
    private final ControlRoom cr;
    private final Db db;

    PromptSkills(ControlRoom cr) { this.cr = cr; this.db = cr.db; }

    /** Starting points shipped with the product (our own wording). */
    static final List<Map<String, Object>> BUILT_IN_TEMPLATES = List.of(
            Map.of("name", "template_summarise", "description", "Summarise a text in a few sentences for a busy reader.",
                    "definition", Map.of("system", "You write short, factual summaries. Never add facts that are not in the text.",
                            "prompt", "Summarise the following in at most ${sentences} sentences:\n\n${text}", "output", "text", "temperature", 0.2,
                            "inputs", List.of(Map.of("name", "text", "description", "The text to summarise"), Map.of("name", "sentences", "description", "How many sentences", "default", "3")))),
            Map.of("name", "template_route_request", "description", "Put a customer message into one of your categories.",
                    "definition", Map.of("system", "You sort customer messages. Answer with JSON only.",
                            "prompt", "Categories: ${categories}\n\nMessage:\n${message}\n\nAnswer as {\"category\": \"...\", \"reason\": \"...\"}.", "output", "json", "temperature", 0.0,
                            "inputs", List.of(Map.of("name", "message", "description", "The customer's message"), Map.of("name", "categories", "description", "Comma-separated categories")))),
            Map.of("name", "template_invoice_fields", "description", "Read supplier, number, date and total from invoice text.",
                    "definition", Map.of("system", "You read invoices. Answer with JSON only; use null for anything you cannot find.",
                            "prompt", "From this invoice text, give {\"supplier\", \"invoice_number\", \"invoice_date\", \"total\", \"currency\"}:\n\n${text}", "output", "json", "temperature", 0.0,
                            "inputs", List.of(Map.of("name", "text", "description", "The invoice text (from OCR or a PDF)")))));

    static String checkName(String name) {
        String n = name == null ? "" : name.trim();
        if (!n.matches("^[A-Za-z0-9_-]{1,80}$")) throw new OoaException("CR.BAD_INPUT", "Skill names use letters, digits, - and _ (up to 80)");
        return n;
    }

    private boolean visible(User by, Map<String, Object> s) {
        return Boolean.TRUE.equals(s.get("shared")) || Boolean.TRUE.equals(s.get("template")) || by.username().equals(s.get("owner")) || cr.can(by, "skills.all");
    }

    public List<Map<String, Object>> list(User by) {
        cr.require(by, "skills.view", "view Prompt Skills");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> s : db.query("SELECT name, description, version, shared, template, owner, updated, updated_by FROM prompt_skills ORDER BY name"))
            if (visible(by, s)) out.add(s);
        return out;
    }

    public List<Map<String, Object>> templates(User by) {
        cr.require(by, "skills.view", "view Prompt Skills");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> t : BUILT_IN_TEMPLATES) { Map<String, Object> m = new LinkedHashMap<>(t); m.put("built_in", true); out.add(m); }
        for (Map<String, Object> s : db.query("SELECT name, description, definition FROM prompt_skills WHERE template = TRUE ORDER BY name")) {
            Map<String, Object> m = new LinkedHashMap<>(s);
            m.put("definition", JsonLite.parse((String) s.get("definition")));
            m.put("built_in", false);
            out.add(m);
        }
        return out;
    }

    public Map<String, Object> get(User by, String name, Integer version) {
        cr.require(by, "skills.view", "view Prompt Skills");
        Map<String, Object> s = db.one("SELECT * FROM prompt_skills WHERE name = ?", checkName(name));
        if (s == null || !visible(by, s)) throw notFound("Prompt Skill " + name);
        Map<String, Object> out = new LinkedHashMap<>(s);
        String def = (String) s.get("definition");
        if (version != null && version != ((Number) s.get("version")).intValue()) {
            Map<String, Object> v = db.one("SELECT definition FROM prompt_skill_versions WHERE name = ? AND version = ?", s.get("name"), version);
            if (v == null) throw notFound("Version " + version + " of " + name);
            def = (String) v.get("definition");
            out.put("version", version);
        }
        out.put("definition", JsonLite.parse(def));
        out.put("versions", db.query("SELECT version, updated, updated_by, comment FROM prompt_skill_versions WHERE name = ? ORDER BY version DESC", s.get("name")));
        return out;
    }

    static void checkDefinition(Object d) {
        if (!(d instanceof Map<?, ?> m) || !(m.get("prompt") instanceof String p) || p.isBlank())
            throw new OoaException("CR.BAD_INPUT", "A skill needs at least a prompt", "For example {\"prompt\": \"Summarise: ${text}\"}.");
        Object out = m.get("output");
        if (out != null && !"text".equals(out) && !"json".equals(out)) throw new OoaException("CR.BAD_INPUT", "output is text or json");
        if (m.get("temperature") != null && !(m.get("temperature") instanceof Number)) throw new OoaException("CR.BAD_INPUT", "temperature is a number");
        if (m.get("inputs") != null && !(m.get("inputs") instanceof List)) throw new OoaException("CR.BAD_INPUT", "inputs is a list of {name, description, default}");
        if (JsonLite.write(d).length() > MAX_DEFINITION) throw new OoaException("CR.TOO_LARGE", "A skill is at most " + MAX_DEFINITION + " characters");
    }

    /** Creates the skill or saves a new version of it. Only the owner (or someone with skills.all) changes a skill. */
    public Map<String, Object> save(User by, String name, Map<String, Object> b, String ip) {
        cr.require(by, "skills.manage", "change Prompt Skills");
        String n = checkName(name);
        Object def = b.get("definition");
        checkDefinition(def);
        String json = JsonLite.write(def);
        boolean shared = Boolean.TRUE.equals(b.get("shared")), template = Boolean.TRUE.equals(b.get("template"));
        if (template && !cr.can(by, "skills.all")) throw denied("publish skill templates");
        Map<String, Object> r = db.tx(() -> {
            Map<String, Object> old = db.one("SELECT owner, version FROM prompt_skills WHERE name = ?", n);
            int v;
            if (old == null) {
                v = 1;
                db.update("INSERT INTO prompt_skills (name, description, definition, version, shared, template, created, updated, updated_by, owner) VALUES (?,?,?,?,?,?,?,?,?,?)",
                        n, b.get("description"), json, v, shared, template, now(), now(), by.username(), by.username());
            } else {
                if (!by.username().equals(old.get("owner")) && !cr.can(by, "skills.all")) throw denied("change a skill someone else owns");
                v = ((Number) old.get("version")).intValue() + 1;
                db.update("UPDATE prompt_skills SET description = ?, definition = ?, version = ?, shared = ?, template = ?, updated = ?, updated_by = ? WHERE name = ?",
                        b.get("description"), json, v, shared, template, now(), by.username(), n);
            }
            db.update("INSERT INTO prompt_skill_versions (name, version, definition, updated, updated_by, comment) VALUES (?,?,?,?,?,?)", n, v, json, now(), by.username(), b.get("comment"));
            return Map.of("name", (Object) n, "version", v);
        });
        cr.audit.record(by.username(), "skill.save", "skill", n, Map.of("version", r.get("version"), "shared", shared, "template", template), "ok", ip);
        return r;
    }

    /** A new private skill from a template (built-in or saved). */
    public Map<String, Object> fromTemplate(User by, String template, String name, String ip) {
        for (Map<String, Object> t : templates(by))
            if (t.get("name").equals(template))
                return save(by, name, Map.of("description", t.getOrDefault("description", ""), "definition", t.get("definition"), "comment", "From template " + template), ip);
        throw notFound("Template " + template);
    }

    public void delete(User by, String name, String ip) {
        cr.require(by, "skills.manage", "change Prompt Skills");
        String n = checkName(name);
        Map<String, Object> s = db.one("SELECT owner FROM prompt_skills WHERE name = ?", n);
        if (s == null) throw notFound("Prompt Skill " + n);
        if (!by.username().equals(s.get("owner")) && !cr.can(by, "skills.all")) throw denied("delete a skill someone else owns");
        db.tx(() -> {
            db.update("DELETE FROM prompt_skill_versions WHERE name = ?", n);
            db.update("DELETE FROM prompt_skills WHERE name = ?", n);
            return null;
        });
        cr.audit.record(by.username(), "skill.delete", "skill", n, null, "ok", ip);
    }

    /**
     * The skills a run may use, as {name: definition}: those its agents name in ooa.ai_skills steps. When a step names
     * the skill through a variable, or lists skills, every skill the run's person may use is sent.
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> forJob(Map<String, Object> flow, Map<String, Object> bundle, String principal) {
        Set<String> named = new TreeSet<>();
        boolean any[] = {false};
        List<Object> flows = new ArrayList<>(List.of(flow));
        if (bundle != null) flows.addAll(bundle.values());
        for (Object f : flows) walk(f, named, any);
        if (named.isEmpty() && !any[0]) return Map.of();
        Map<String, Object> out = new TreeMap<>();
        for (Map<String, Object> s : db.query("SELECT name, definition, shared, owner FROM prompt_skills")) {
            String n = (String) s.get("name");
            if (!Boolean.TRUE.equals(s.get("shared")) && !String.valueOf(principal).equals(s.get("owner"))) continue;
            if (any[0] || named.contains(n)) out.put(n, JsonLite.parse((String) s.get("definition")));
        }
        return out;
    }

    private static void walk(Object o, Set<String> named, boolean[] any) {
        if (o instanceof Map<?, ?> m) {
            if (m.get("action") instanceof String a && a.startsWith("ooa.ai_skills/")) {
                Object skill = m.get("args") instanceof Map<?, ?> in ? in.get("skill") : m.get("inputs") instanceof Map<?, ?> in2 ? in2.get("skill") : null;
                if (a.equals("ooa.ai_skills/run") && skill instanceof String s && s.matches("^[A-Za-z0-9_-]{1,80}$")) named.add(s);
                else any[0] = true;
            }
            for (Object v : m.values()) walk(v, named, any);
        } else if (o instanceof List<?> l) for (Object v : l) walk(v, named, any);
    }
}
