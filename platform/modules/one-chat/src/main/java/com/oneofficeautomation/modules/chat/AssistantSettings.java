package com.oneofficeautomation.modules.chat;

import com.oneofficeautomation.controlroom.ControlRoom.User;
import com.oneofficeautomation.controlroom.module.ModuleContext;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;

import java.util.*;

import static com.oneofficeautomation.controlroom.module.ModuleContext.*;

/**
 * The AI assistants' settings (S2, 20 Nov 2026; docs/integration/one-chat-contract.md section 10). Administrators set
 * them for One Chat ("chat") and Studio's assistant ("studio") in Settings > AI assistants; each person adjusts their
 * own model, answer language and instructions in My account > AI assistants (the table assistant_prefs, this module's
 * schema step 1). {@link #effective} is the one rule that combines the two, used by the built-in engine and, later, by
 * the OneChat service. The defaults are the values the assistants used before these settings existed, so nothing
 * changes until an administrator changes something.
 */
public final class AssistantSettings {
    public static final List<String> AREAS = List.of("chat", "studio");
    /** The console's languages, in the console's order, with their names for the model. */
    public static final Map<String, String> LANGUAGES;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("en", "English"); m.put("de", "German"); m.put("fr", "French"); m.put("es", "Spanish"); m.put("ja", "Japanese");
        LANGUAGES = Collections.unmodifiableMap(m);
    }
    /** The longest instructions, company or personal (the settings store keeps texts of up to 2,000 characters). */
    static final int MAX_TEXT = 2000;

    /** What an assistant runs with for one person: the model connection (blank: their first allowed one), creativity,
     *  the longest answer, the answer language (null: the person's console language), the instructions to add, and
     *  (S4) how long to wait, whether personal data is masked, and the memory mode (off, chat, across). */
    public record Effective(String connection, double temperature, int maxTokens, String language, String instructions,
                            int timeoutSeconds, boolean maskPii, String memory) {}

    /** One Chat's memory: off (only the current message), chat (this chat; the default, as before), across (also the notes the person asked it to remember). */
    public static final List<String> MEMORY = List.of("chat", "off", "across");
    /** The most notes a person keeps, and the longest note. */
    static final int MAX_NOTES = 50, MAX_NOTE = 300;

    private final ModuleContext ctx;

    public AssistantSettings(ModuleContext ctx) { this.ctx = ctx; }

    /**
     * Both areas' settings, with their checks. One Chat's model stays chat_connection (it existed before); the rest is
     * new. Defaults: creativity 0.2 and 2,048 tokens for One Chat, 0 and 4,000 for Studio's assistant, as before.
     */
    static void define(ModuleContext ctx) {
        ctx.defineSetting("studio_connection", "", (v, saas) -> checkConnections(ctx, v, false));
        for (String area : AREAS) {
            String p = area + "_";
            ctx.defineSetting(p + "models", "", (v, saas) -> checkConnections(ctx, v, true));
            ctx.defineSetting(p + "temperature", area.equals("chat") ? "0.2" : "0", (v, saas) -> temperature(v));
            ctx.defineSetting(p + "max_tokens", area.equals("chat") ? 2048 : 4000, (v, saas) -> {
                int n = Integer.parseInt(v);
                if (n < 100 || n > 8000) throw new OoaException("CR.BAD_INPUT", "The longest answer is from 100 to 8000 tokens");
            });
            ctx.defineSetting(p + "language", "", (v, saas) -> {
                if (!v.isEmpty() && !LANGUAGES.containsKey(v))
                    throw new OoaException("CR.BAD_INPUT", "The answer language is empty (the person's console language) or one of " + String.join(", ", LANGUAGES.keySet()));
            });
            ctx.defineSetting(p + "instructions", "", (v, saas) -> {
                if (v.length() > MAX_TEXT) throw new OoaException("CR.BAD_INPUT", "Company instructions are at most " + MAX_TEXT + " characters");
            });
            ctx.defineSetting(p + "skills", "", (v, saas) -> checkSkills(ctx, v));
            ctx.defineSetting(p + "own_instructions", 1, (v, saas) -> {
                if (!v.equals("0") && !v.equals("1")) throw new OoaException("CR.BAD_INPUT", "Setting '" + p + "own_instructions' must be 0 or 1");
            });
        }
        // S4: memory, limits and privacy. Defaults keep what the assistants did before: One Chat remembers this chat,
        // keeps a chat 8 hours unused, waits 90 seconds and masks personal data; Studio's waits 300 and does not mask
        ctx.defineSetting("chat_memory", "chat", (v, saas) -> {
            if (!MEMORY.contains(v)) throw new OoaException("CR.BAD_INPUT", "One Chat's memory is chat, off or across");
        });
        ctx.defineSetting("chat_keep_hours", 8, (v, saas) -> range(v, 1, 2160, "Keep chats for is from 1 to 2160 hours (90 days)"));
        ctx.defineSetting("chat_timeout_seconds", 90, (v, saas) -> range(v, 10, 300, "One Chat waits from 10 to 300 seconds for an answer"));
        ctx.defineSetting("studio_timeout_seconds", 300, (v, saas) -> range(v, 30, 600, "Studio's assistant waits from 30 to 600 seconds for an answer"));
        ctx.defineSetting("chat_mask_pii", 1, (v, saas) -> range(v, 0, 1, "Setting 'chat_mask_pii' must be 0 or 1"));
        ctx.defineSetting("studio_mask_pii", 0, (v, saas) -> range(v, 0, 1, "Setting 'studio_mask_pii' must be 0 or 1"));
        // tokens per person a month, in thousands (0: only the Model Connection's own budget)
        for (String area : AREAS) ctx.defineSetting(area + "_ktokens_per_person", 0, (v, saas) -> range(v, 0, 100000, "Tokens per person is from 0 to 100000 thousand"));
        // S3: One Chat's tools and permissions, one rule per tool group. Defaults keep what One Chat did before:
        // reading runs and running agents allowed (with each agent's risk rules); administration tools off.
        for (String group : TOOL_GROUPS)
            ctx.defineSetting("chat_tool_" + group, defaultRule(group), (v, saas) -> {
                if (!RULES.contains(v)) throw new OoaException("CR.BAD_INPUT", "A tool rule is always, ask or never");
            });
    }

    /** One Chat's tool groups (the conversation library's ChatPlatform.GROUPS). */
    public static final List<String> TOOL_GROUPS = List.of("read", "agents", "admin_read", "admin_change", "admin_high");
    /** always: allowed with the tool's own risk rules; ask: always wait for the person's yes; never: not offered. */
    static final List<String> RULES = List.of("always", "ask", "never");

    static String defaultRule(String group) { return group.startsWith("admin_") ? "never" : "always"; }

    /**
     * The rule for a tool group and this person: never, ask (the company's Ask first), ask_person (the person asked to
     * confirm first: before an agent runs or something changes) or always. The person's choice only makes it stricter.
     */
    public String policy(User u, String group) {
        if (!TOOL_GROUPS.contains(group)) return "never";
        String rule = setting("chat_tool_" + group);
        if (!RULES.contains(rule)) rule = defaultRule(group);
        if (rule.equals("always") && (group.equals("agents") || group.equals("admin_change") || group.equals("admin_high")) && askFirst(u.username()))
            return "ask_person";
        return rule;
    }

    /** Whether the person asked One Chat to confirm before an agent runs or something changes (My account > AI assistants). */
    boolean askFirst(String username) {
        Map<String, Object> r = ctx.db().one("SELECT ask_first FROM assistant_prefs WHERE username = ? AND assistant = ?", username, "chat");
        return r != null && Boolean.TRUE.equals(r.get("ask_first"));
    }

    private static void range(String v, int min, int max, String message) {
        int n;
        try { n = Integer.parseInt(v.trim()); } catch (NumberFormatException e) { throw new OoaException("CR.BAD_INPUT", message); }
        if (n < min || n > max) throw new OoaException("CR.BAD_INPUT", message);
    }

    // ---- S4: limits per person, and the notes a person asked One Chat to remember ----

    /** How long One Chat keeps a chat that is not used (Settings: Keep chats for). */
    public java.time.Duration keepChats() {
        int h = ctx.intSetting("chat_keep_hours");
        return java.time.Duration.ofHours(h < 1 ? 8 : h);
    }

    /**
     * Refuses a model call once the person has used their tokens for this assistant this month (Settings: Tokens per
     * person), counted from the AI call record (the name each call is recorded under: "One Chat", "Studio assistant").
     */
    public void checkAllowance(User u, String area) {
        long limit = 1000L * ctx.intSetting(area + "_ktokens_per_person");
        if (limit <= 0) return;
        java.time.Instant month = java.time.LocalDate.now(java.time.ZoneOffset.UTC).withDayOfMonth(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        Map<String, Object> r = ctx.db().one("SELECT SUM(input_tokens) AS i, SUM(output_tokens) AS o FROM ai_calls WHERE requested_by = ? AND bot_path = ? AND ts >= ?",
                u.username(), area.equals("chat") ? "One Chat" : "Studio assistant", month);
        long used = (r != null && r.get("i") instanceof Number i ? i.longValue() : 0) + (r != null && r.get("o") instanceof Number o ? o.longValue() : 0);
        if (used >= limit)
            throw new OoaException("AI.PERSON_LIMIT", "You have used your AI allowance for " + (area.equals("chat") ? "One Chat" : "Studio's assistant") + " this month ("
                    + used + " of " + limit + " tokens)", "Ask an administrator to raise Tokens per person in Settings > AI assistants.");
    }

    /** The notes the person asked One Chat to remember, oldest first: [{id, note, created}]. */
    public List<Map<String, Object>> notes(String username) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : ctx.db().query("SELECT id, note, created FROM assistant_memory WHERE username = ? AND assistant = ? ORDER BY created", username, "chat"))
            out.add(new LinkedHashMap<>(Map.of("id", r.get("id"), "note", r.get("note"), "created", String.valueOf(r.get("created")))));
        return out;
    }

    /** Keeps a note for the person (at most MAX_NOTES, each MAX_NOTE characters). */
    public void remember(User u, String note, String ip) {
        String n = note == null ? "" : note.trim();
        if (n.isEmpty()) throw new OoaException("CR.BAD_INPUT", "Say what to remember");
        if (n.length() > MAX_NOTE) n = n.substring(0, MAX_NOTE);
        if (ctx.count("SELECT COUNT(*) AS n FROM assistant_memory WHERE username = ?", u.username()) >= MAX_NOTES)
            throw new OoaException("AI.MEMORY_FULL", "One Chat already remembers " + MAX_NOTES + " notes for you", "Forget some in My account > AI assistants first.");
        ctx.db().update("INSERT INTO assistant_memory (id, username, assistant, note, created) VALUES (?,?,?,?,?)", id("mem"), u.username(), "chat", n, now());
        ctx.audit(u.username(), "assistant.remember", "user", u.username(), Map.of("characters", n.length()), "ok", ip);
    }

    /** Forgets one of the person's notes, or all of them (id null). */
    public Map<String, Object> forget(User u, String noteId, String ip) {
        int n = noteId == null ? ctx.db().update("DELETE FROM assistant_memory WHERE username = ?", u.username())
                : ctx.db().update("DELETE FROM assistant_memory WHERE username = ? AND id = ?", u.username(), noteId);
        if (noteId != null && n == 0) throw notFound("The note " + noteId);
        ctx.audit(u.username(), "assistant.forget", "user", u.username(), Map.of("notes", n), "ok", ip);
        return mine(u);
    }

    /** Creativity: a number from 0 to 1. */
    static double temperature(String v) {
        double t;
        try { t = Double.parseDouble(v.trim()); }
        catch (NumberFormatException e) { throw new OoaException("CR.BAD_INPUT", "Creativity is a number from 0 to 1, such as 0.2"); }
        if (t < 0 || t > 1 || Double.isNaN(t)) throw new OoaException("CR.BAD_INPUT", "Creativity is a number from 0 to 1, such as 0.2");
        return t;
    }

    /** Model connections named in a setting must exist (a list, or one name). */
    private static void checkConnections(ModuleContext ctx, String v, boolean list) {
        if (v.isEmpty()) return;
        List<String> names = list ? names(v) : List.of(v.trim());
        for (String n : names)
            if (ctx.db().one("SELECT name FROM model_connections WHERE name = ?", n) == null)
                throw new OoaException("CR.BAD_INPUT", "There is no One Model Connection called " + n, "Add it in AI > One Model Connections first, or choose another.");
    }

    /** Prompt Skills named in a setting must exist and be shared (a private skill or a template cannot be given to everyone). */
    private static void checkSkills(ModuleContext ctx, String v) {
        for (String n : names(v))
            if (ctx.db().one("SELECT name FROM prompt_skills WHERE name = ? AND shared = ? AND template = ?", n, true, false) == null)
                throw new OoaException("CR.BAD_INPUT", "There is no shared Prompt Skill called " + n, "Share it in AI > Prompt Skills first, or choose another.");
    }

    /** A comma-separated list, trimmed, without empty entries or repeats. */
    static List<String> names(String v) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String s : String.valueOf(v == null ? "" : v).split(",")) if (!s.isBlank()) out.add(s.trim());
        return new ArrayList<>(out);
    }

    private String setting(String key) { Object v = ctx.setting(key); return v == null ? "" : String.valueOf(v).trim(); }

    // ---- the person's own choices ----

    /** The person's own choices for an area: model, language, instructions (each empty when not set). */
    public Map<String, String> prefs(String username, String area) {
        Map<String, Object> r = ctx.db().one("SELECT model, language, instructions FROM assistant_prefs WHERE username = ? AND assistant = ?", username, area);
        Map<String, String> out = new LinkedHashMap<>();
        for (String k : List.of("model", "language", "instructions")) out.put(k, r == null || r.get(k) == null ? "" : String.valueOf(r.get(k)));
        return out;
    }

    /**
     * Saves the person's own choices for an area, within what the administrator allows: a model from the allowed list
     * that the person may use, a language only while the company has not fixed one, and own instructions only while
     * they are allowed. The change is in the Audit Trail; the instructions' text is not.
     */
    public Map<String, Object> savePrefs(User u, String area, Map<String, Object> body, String ip) {
        if (!AREAS.contains(area)) throw notFound("The assistant " + area);
        String model = text(body.get("model")), language = text(body.get("language")), instructions = text(body.get("instructions"));
        // "always ask me first" (S3, One Chat): kept as it was when not sent
        boolean askFirst = body.containsKey("ask_first") ? Boolean.TRUE.equals(body.get("ask_first")) : askFirst(u.username()) && area.equals("chat");
        if (!model.isEmpty() && !modelsFor(u, area).contains(model))
            throw new OoaException("CR.BAD_INPUT", "You may not choose the model " + model, "Choose one your administrator allows in Settings > AI assistants.");
        if (!language.isEmpty()) {
            if (!LANGUAGES.containsKey(language)) throw new OoaException("CR.BAD_INPUT", "The answer language is one of " + String.join(", ", LANGUAGES.keySet()));
            if (!setting(area + "_language").isEmpty()) throw new OoaException("CR.BAD_INPUT", "Your administrator has set the answer language", "Leave it as it is.");
        }
        if (instructions.length() > MAX_TEXT) throw new OoaException("CR.BAD_INPUT", "Your instructions are at most " + MAX_TEXT + " characters");
        if (!instructions.isEmpty() && !"1".equals(setting(area + "_own_instructions")))
            throw new OoaException("CR.BAD_INPUT", "Your administrator does not allow your own instructions here", "Leave them empty.");
        Map<String, String> before = prefs(u.username(), area);
        if (ctx.db().update("UPDATE assistant_prefs SET model = ?, language = ?, instructions = ?, ask_first = ?, updated = ? WHERE username = ? AND assistant = ?",
                model, language, instructions, askFirst, now(), u.username(), area) == 0)
            ctx.db().update("INSERT INTO assistant_prefs (username, assistant, model, language, instructions, ask_first, updated) VALUES (?,?,?,?,?,?,?)",
                    u.username(), area, model, language, instructions, askFirst, now());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("assistant", area);
        details.put("model", model);
        details.put("language", language);
        details.put("instructions_changed", !before.get("instructions").equals(instructions));
        details.put("ask_first", askFirst);
        ctx.audit(u.username(), "assistant.prefs_changed", "user", u.username(), details, "ok", ip);
        return mine(u);
    }

    private static String text(Object v) { return v == null ? "" : String.valueOf(v).trim(); }

    /** The models the person may choose for an area: the administrator's list, of those the person's roles may use. */
    List<String> modelsFor(User u, String area) {
        Set<String> usable = new HashSet<>();
        Object list = ctx.modelConnectionsFor(ctx.rolesOf(u.username(), u.role())).get("connections");
        if (list instanceof List<?> l) for (Object c : l) if (c instanceof Map<?, ?> m) usable.add(String.valueOf(m.get("name")));
        return names(setting(area + "_models")).stream().filter(usable::contains).toList();
    }

    /** What My account > AI assistants shows: per area, the person's own choices and what the administrator allows. */
    public Map<String, Object> mine(User u) {
        Map<String, Object> areas = new LinkedHashMap<>();
        for (String area : AREAS) {
            Map<String, Object> a = new LinkedHashMap<>();
            Map<String, Object> mine = new LinkedHashMap<>(prefs(u.username(), area));
            mine.put("ask_first", area.equals("chat") && askFirst(u.username()));
            a.put("mine", mine);
            a.put("default_model", setting(area.equals("chat") ? "chat_connection" : "studio_connection"));
            a.put("models", modelsFor(u, area));
            a.put("language_fixed", setting(area + "_language"));
            a.put("own_instructions", "1".equals(setting(area + "_own_instructions")));
            a.put("company_instructions", setting(area + "_instructions"));
            areas.put(area, a);
        }
        String memory = setting("chat_memory");
        return Map.of("assistants", areas, "languages", LANGUAGES, "memory", Map.of("mode", MEMORY.contains(memory) ? memory : "chat", "notes", notes(u.username())));
    }

    /** For Settings > AI assistants: every model connection and every shared Prompt Skill an administrator may choose. */
    public Map<String, Object> options() {
        List<Object> connections = new ArrayList<>(), skills = new ArrayList<>();
        for (Map<String, Object> c : ctx.db().query("SELECT name, provider, model FROM model_connections ORDER BY name"))
            connections.add(Map.of("name", c.get("name"), "provider", String.valueOf(c.get("provider")), "model", c.get("model") == null ? "" : String.valueOf(c.get("model"))));
        for (Map<String, Object> s : ctx.db().query("SELECT name, description FROM prompt_skills WHERE shared = ? AND template = ? ORDER BY name", true, false))
            skills.add(Map.of("name", s.get("name"), "description", s.get("description") == null ? "" : String.valueOf(s.get("description"))));
        return Map.of("connections", connections, "skills", skills, "languages", LANGUAGES);
    }

    /** Removes the choices of people who no longer have an account (on One CommandCenter's clock). */
    public void forgetDeletedPeople() {
        ctx.db().update("DELETE FROM assistant_prefs WHERE username NOT IN (SELECT username FROM users)");
        ctx.db().update("DELETE FROM assistant_memory WHERE username NOT IN (SELECT username FROM users)");
    }

    // ---- the one rule ----

    /** The answer language for the person in an area: the administrator's fixed one, else theirs, else null (their console language). */
    public String language(User u, String area) {
        String fixed = setting(area + "_language");
        if (!fixed.isEmpty()) return fixed;
        String mine = prefs(u.username(), area).get("language");
        return LANGUAGES.containsKey(mine) ? mine : null;
    }

    /**
     * The settings an area's assistant uses for this person. Model: theirs when it is on the allowed list and they may
     * use it, else the administrator's default. Instructions: the company's, then the allowed Prompt Skills, then the
     * person's own when allowed; the company's always come first and the person's never replace them.
     */
    public Effective effective(User u, String area) {
        if (!AREAS.contains(area)) throw notFound("The assistant " + area);
        String p = area + "_";
        Map<String, String> mine = prefs(u.username(), area);
        String model = setting(area.equals("chat") ? "chat_connection" : "studio_connection");
        if (!mine.get("model").isEmpty() && modelsFor(u, area).contains(mine.get("model"))) model = mine.get("model");
        double temperature;
        try { temperature = temperature(setting(p + "temperature")); } catch (OoaException e) { temperature = area.equals("chat") ? 0.2 : 0; }
        int maxTokens = ctx.intSetting(p + "max_tokens");
        if (maxTokens < 100) maxTokens = area.equals("chat") ? 2048 : 4000;
        int timeout = ctx.intSetting(p + "timeout_seconds");
        if (timeout < 10) timeout = area.equals("chat") ? 90 : 300;
        String memory = area.equals("chat") ? setting("chat_memory") : "off";
        return new Effective(model, temperature, maxTokens, language(u, area), instructions(area, mine.get("instructions")),
                timeout, ctx.intSetting(p + "mask_pii") == 1, MEMORY.contains(memory) ? memory : "chat");
    }

    /** The text added to the model's instructions: company instructions, allowed skills, the person's own. */
    String instructions(String area, String own) {
        StringBuilder out = new StringBuilder();
        String company = setting(area + "_instructions");
        if (!company.isEmpty()) out.append("Company instructions (always follow these):\n").append(company);
        for (String name : names(setting(area + "_skills"))) {
            Map<String, Object> s = ctx.db().one("SELECT name, description, definition FROM prompt_skills WHERE name = ? AND shared = ? AND template = ?", name, true, false);
            if (s == null) continue;   // removed or unshared since: left out
            Map<?, ?> d;
            try { d = JsonLite.parse(String.valueOf(s.get("definition"))) instanceof Map<?, ?> m ? m : Map.of(); }
            catch (RuntimeException e) { d = Map.of(); }
            String body = (text(d.get("system")) + "\n" + text(d.get("prompt"))).trim();
            if (body.length() > MAX_TEXT) body = body.substring(0, MAX_TEXT);
            if (out.length() > 0) out.append("\n\n");
            out.append("Saved Prompt Skill \"").append(name).append("\"").append(s.get("description") == null ? "" : " (" + s.get("description") + ")").append(":\n").append(body);
        }
        if (own != null && !own.isBlank() && "1".equals(setting(area + "_own_instructions"))) {
            if (out.length() > 0) out.append("\n\n");
            out.append("The person's own instructions (they add to the company's and never replace them):\n").append(own.trim());
        }
        return out.toString();
    }
}
