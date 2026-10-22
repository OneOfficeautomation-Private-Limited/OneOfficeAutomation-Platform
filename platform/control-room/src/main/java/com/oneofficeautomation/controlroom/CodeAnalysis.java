package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.runner.Steps;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;

import java.util.*;
import java.util.regex.Pattern;

import static com.oneofficeautomation.controlroom.ControlRoom.*;
import com.oneofficeautomation.controlroom.ControlRoom.User;

/**
 * Code analysis: checks agents against the Command Center's policy, a set of rules each switched on with a severity
 * (error or warning) and its own settings. Analysis runs on demand, and at every check-in; when the policy says so, a
 * check-in with errors is refused. Each analysed version's report is stored, and a report of the whole repository can
 * be downloaded. Workflows and forms are not analysed (they have no steps).
 */
public final class CodeAnalysis {
    /** The rules with their defaults: enabled, severity and settings. */
    static final Map<String, Map<String, Object>> RULES = new LinkedHashMap<>();
    static {
        rule("hardcoded_secrets", true, "error", Map.of(), "Passwords, tokens and keys typed into steps instead of coming from the vault");
        rule("forbidden_actions", false, "error", Map.of("actions", List.of()), "Actions (or whole toolkits, e.g. ooa.shell/) agents may not use");
        rule("require_error_handling", true, "warning", Map.of("min_steps", 5), "Agents with more than a few steps should handle errors with Try/Catch");
        rule("empty_catch", true, "warning", Map.of(), "A Catch block that does nothing hides errors");
        rule("require_description", true, "warning", Map.of(), "Every agent says what it does");
        rule("variable_naming", false, "warning", Map.of("pattern", "^[a-z][a-z0-9_]*$"), "Variable names follow the naming pattern");
        rule("unused_variables", true, "warning", Map.of(), "Variables no step uses");
        rule("disabled_steps", true, "warning", Map.of(), "Steps switched off are left in the agent");
        rule("long_waits", true, "warning", Map.of("max_seconds", 600), "Fixed delays longer than the limit");
        rule("max_steps", true, "warning", Map.of("limit", 300), "Very long agents are hard to maintain; split them into child agents");
        rule("max_nesting", true, "warning", Map.of("limit", 6), "Blocks nested deeper than the limit");
    }
    private static void rule(String id, boolean on, String severity, Map<String, Object> params, String description) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("enabled", on); r.put("severity", severity); r.put("params", params); r.put("description", description);
        RULES.put(id, r);
    }

    static final Pattern SECRET_NAME = Pattern.compile("(?i).*(password|passwd|pwd|secret|token|api_?key|apikey|client_secret|private_key).*");

    private final ControlRoom cr;
    private final Db db;

    CodeAnalysis(ControlRoom cr) { this.cr = cr; this.db = cr.db; }

    /** The policy in force: the defaults with the saved changes, plus enforce_at_checkin. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> policy() {
        Map<String, Object> saved = Map.of();
        Map<String, Object> row = db.one("SELECT json_value FROM code_policy WHERE id = 1");
        if (row != null) saved = (Map<String, Object>) JsonLite.parse((String) row.get("json_value"));
        Map<String, Object> rules = new LinkedHashMap<>();
        Map<String, Object> changed = saved.get("rules") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        for (var e : RULES.entrySet()) {
            Map<String, Object> r = new LinkedHashMap<>(e.getValue());
            if (changed.get(e.getKey()) instanceof Map<?, ?> c) {
                if (c.get("enabled") instanceof Boolean b) r.put("enabled", b);
                if (c.get("severity") instanceof String sv) r.put("severity", sv);
                if (c.get("params") instanceof Map<?, ?> pm) { Map<String, Object> p = new LinkedHashMap<>((Map<String, Object>) r.get("params")); p.putAll((Map<String, Object>) pm); r.put("params", p); }
            }
            rules.put(e.getKey(), r);
        }
        return Map.of("enforce_at_checkin", Boolean.TRUE.equals(saved.get("enforce_at_checkin")), "rules", rules);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> savePolicy(User by, Map<String, Object> p, String ip) {
        cr.require(by, "settings.manage", "change the code analysis policy");
        Map<String, Object> clean = new LinkedHashMap<>();
        clean.put("enforce_at_checkin", Boolean.TRUE.equals(p.get("enforce_at_checkin")));
        Map<String, Object> rules = new LinkedHashMap<>();
        if (p.get("rules") instanceof Map<?, ?> rm) for (var e : ((Map<String, Object>) rm).entrySet()) {
            if (!RULES.containsKey(e.getKey())) throw new OoaException("CR.BAD_INPUT", "Unknown rule '" + e.getKey() + "'", "Rules: " + RULES.keySet());
            if (!(e.getValue() instanceof Map<?, ?> r)) continue;
            Map<String, Object> c = new LinkedHashMap<>();
            if (r.get("enabled") instanceof Boolean b) c.put("enabled", b);
            if (r.get("severity") != null) {
                String sv = String.valueOf(r.get("severity"));
                if (!Set.of("error", "warning").contains(sv)) throw new OoaException("CR.BAD_INPUT", "Severity is error or warning");
                c.put("severity", sv);
            }
            if (r.get("params") instanceof Map<?, ?> pm) {
                if (pm.get("pattern") instanceof String pat) try { Pattern.compile(pat); } catch (RuntimeException x) { throw new OoaException("CR.BAD_INPUT", "The naming pattern is not a valid regular expression"); }
                c.put("params", pm);
            }
            rules.put(e.getKey(), c);
        }
        clean.put("rules", rules);
        String json = JsonLite.write(clean);
        if (db.update("UPDATE code_policy SET json_value = ?, updated = ?, updated_by = ? WHERE id = 1", json, now(), by.username()) == 0)
            db.update("INSERT INTO code_policy (id, json_value, updated, updated_by) VALUES (1,?,?,?)", json, now(), by.username());
        cr.audit.record(by.username(), "analysis.policy", "policy", null, clean, "ok", ip);
        return policy();
    }

    record Violation(String rule, String severity, String stepId, String message) {
        Map<String, Object> map() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rule", rule); m.put("severity", severity); m.put("step", stepId); m.put("message", message);
            return m;
        }
    }

    /** Checks a flow against the policy. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> check(Map<String, Object> flow) {
        Map<String, Object> rules = (Map<String, Object>) policy().get("rules");
        List<Violation> out = new ArrayList<>();
        List<Object> top = (List<Object>) flow.getOrDefault("steps", List.of());
        List<Map<String, Object>> all = Steps.walk(top);
        String json = JsonLite.write(flow.getOrDefault("steps", List.of()));
        for (var e : rules.entrySet()) {
            Map<String, Object> r = (Map<String, Object>) e.getValue();
            if (!Boolean.TRUE.equals(r.get("enabled"))) continue;
            String sev = String.valueOf(r.get("severity"));
            Map<String, Object> p = (Map<String, Object>) r.get("params");
            switch (e.getKey()) {
                case "hardcoded_secrets" -> {
                    for (Map<String, Object> s : all) secrets(s.get("args"), "", s, sev, out);
                    for (Object v : (List<Object>) flow.getOrDefault("variables", List.of()))
                        if (v instanceof Map<?, ?> vm && vm.get("default") instanceof String d && !d.isEmpty() && SECRET_NAME.matcher(String.valueOf(vm.get("name"))).matches())
                            out.add(new Violation("hardcoded_secrets", sev, null, "Variable $" + vm.get("name") + " has a secret-looking default value; make it a Credential from the vault"));
                }
                case "forbidden_actions" -> {
                    List<String> bad = csv(p.get("actions"));
                    for (Map<String, Object> s : all) {
                        String a = String.valueOf(s.get("action"));
                        for (String b : bad) if (!b.isBlank() && (a.equals(b) || (b.endsWith("/") && a.startsWith(b)) || (b.endsWith("*") && a.startsWith(b.substring(0, b.length() - 1)))))
                            { out.add(new Violation("forbidden_actions", sev, id(s), "The action " + a + " is not allowed by the policy")); break; }
                    }
                }
                case "require_error_handling" -> {
                    int min = num(p.get("min_steps"), 5);
                    if (all.size() > min && all.stream().noneMatch(s -> "core/try".equals(s.get("action"))))
                        out.add(new Violation("require_error_handling", sev, null, "The agent has " + all.size() + " steps and no Try/Catch"));
                }
                case "empty_catch" -> {
                    for (Map<String, Object> s : all) if ("core/try".equals(s.get("action")))
                        for (Object c : (List<Object>) s.getOrDefault("catch", List.of()))
                            if (c instanceof Map<?, ?> cm && (!(cm.get("children") instanceof List<?> l) || l.isEmpty()) && !Boolean.TRUE.equals(cm.get("rethrow")))
                                out.add(new Violation("empty_catch", sev, id(s), "A Catch block does nothing: log the error, store it, or raise it again"));
                }
                case "require_description" -> {
                    if (!(flow.get("description") instanceof String d) || d.isBlank()) out.add(new Violation("require_description", sev, null, "The agent has no description"));
                }
                case "variable_naming" -> {
                    Pattern pat = Pattern.compile(String.valueOf(p.getOrDefault("pattern", "^[a-z][a-z0-9_]*$")));
                    for (Object v : (List<Object>) flow.getOrDefault("variables", List.of()))
                        if (v instanceof Map<?, ?> vm && !pat.matcher(String.valueOf(vm.get("name"))).matches())
                            out.add(new Violation("variable_naming", sev, null, "Variable $" + vm.get("name") + " does not follow the naming pattern " + pat.pattern()));
                }
                case "unused_variables" -> {
                    for (Object v : (List<Object>) flow.getOrDefault("variables", List.of())) {
                        if (!(v instanceof Map<?, ?> vm)) continue;
                        String n = String.valueOf(vm.get("name")), dir = String.valueOf(vm.get("direction"));
                        if (Set.of("input", "output", "in_out").contains(dir)) continue;
                        if (!Pattern.compile("\\$\\{?" + Pattern.quote(n) + "(?![A-Za-z0-9_])").matcher(json).find() && !json.contains("\"" + n + "\""))
                            out.add(new Violation("unused_variables", sev, null, "Variable $" + n + " is not used"));
                    }
                }
                case "disabled_steps" -> {
                    for (Map<String, Object> s : all) if (Boolean.TRUE.equals(s.get("disabled"))) out.add(new Violation("disabled_steps", sev, id(s), "A switched-off step is left in the agent"));
                }
                case "long_waits" -> {
                    int max = num(p.get("max_seconds"), 600);
                    for (Map<String, Object> s : all) {
                        if (!"core/delay".equals(s.get("action")) || !(s.get("args") instanceof Map<?, ?> a)) continue;
                        if (a.get("seconds") instanceof Number n) {
                            double secs = "milliseconds".equals(a.get("unit")) ? n.doubleValue() / 1000 : n.doubleValue();
                            if (secs > max) out.add(new Violation("long_waits", sev, id(s), "A fixed delay of " + Math.round(secs) + " s; wait for what the agent needs instead (limit " + max + " s)"));
                        }
                    }
                }
                case "max_steps" -> {
                    int limit = num(p.get("limit"), 300);
                    if (all.size() > limit) out.add(new Violation("max_steps", sev, null, "The agent has " + all.size() + " steps (limit " + limit + "); split it into child agents"));
                }
                case "max_nesting" -> {
                    int limit = num(p.get("limit"), 6);
                    int depth = depth(top, 0);
                    if (depth > limit) out.add(new Violation("max_nesting", sev, null, "Blocks are nested " + depth + " deep (limit " + limit + ")"));
                }
                default -> { }
            }
        }
        return out.stream().map(Violation::map).toList();
    }

    static String id(Map<String, Object> s) { return s.get("id") == null ? null : String.valueOf(s.get("id")); }
    static int num(Object v, int d) { return v instanceof Number n ? n.intValue() : d; }

    @SuppressWarnings("unchecked")
    static void secrets(Object args, String path, Map<String, Object> step, String sev, List<Violation> out) {
        if (!(args instanceof Map<?, ?> m)) return;
        for (var e : ((Map<String, Object>) m).entrySet()) {
            String k = e.getKey();
            Object v = e.getValue();
            if (v instanceof Map<?, ?> inner) { if (!inner.containsKey("$credential")) secrets(inner, path + k + ".", step, sev, out); continue; }
            if (v instanceof String s && !s.isBlank() && !s.startsWith("$") && SECRET_NAME.matcher(k).matches())
                out.add(new Violation("hardcoded_secrets", sev, id(step), "'" + path + k + "' is typed into the step; use a Credential from the vault"));
        }
    }

    @SuppressWarnings("unchecked")
    static int depth(List<Object> steps, int d) {
        int max = d;
        for (Object o : steps == null ? List.of() : steps) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Map<String, Object> s = (Map<String, Object>) m;
            List<List<Object>> inner = new ArrayList<>();
            for (String k : List.of("children", "else", "finally")) if (s.get(k) instanceof List<?> l) inner.add((List<Object>) l);
            for (String k : List.of("elseIf", "catch")) if (s.get(k) instanceof List<?> l) for (Object b : l) if (b instanceof Map<?, ?> bm && bm.get("children") instanceof List<?> c) inner.add((List<Object>) c);
            for (List<Object> l : inner) if (!l.isEmpty()) max = Math.max(max, depth(l, d + 1));
        }
        return max;
    }

    static boolean analysable(String path) { return path != null && path.endsWith(".flow.json"); }

    /** Analyses a stored version and keeps the report. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> analyze(User by, String path, Object version) {
        Map<String, Object> c = cr.getBot(by, path, version == null ? "latest" : version);
        if (!analysable(path)) throw new OoaException("CR.BAD_INPUT", "Only agents are analysed (not workflows or forms)");
        return store(path, ((Number) c.get("version")).intValue(), check((Map<String, Object>) c.get("flow")), by.username());
    }

    /** Analyses a flow that is not stored (for example from Studio before check-in). */
    public Map<String, Object> analyzeFlow(User by, Map<String, Object> flow) {
        cr.require(by, "repo.view", "analyse agents");
        List<Map<String, Object>> v = check(flow);
        return report(null, null, v);
    }

    Map<String, Object> store(String path, int version, List<Map<String, Object>> violations, String by) {
        Map<String, Object> rep = report(path, version, violations);
        db.tx(() -> {
            db.update("DELETE FROM code_analysis WHERE path = ? AND version = ?", path, version);
            db.update("INSERT INTO code_analysis (path, version, errors, warnings, violations, analyzed, analyzed_by) VALUES (?,?,?,?,?,?,?)",
                    path, version, rep.get("errors"), rep.get("warnings"), JsonLite.write(violations), now(), by);
            return null;
        });
        return rep;
    }

    static Map<String, Object> report(String path, Integer version, List<Map<String, Object>> v) {
        long errors = v.stream().filter(x -> "error".equals(x.get("severity"))).count();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", path); m.put("version", version);
        m.put("errors", (int) errors); m.put("warnings", v.size() - (int) errors);
        m.put("passed", errors == 0);
        m.put("violations", v);
        return m;
    }

    /** The stored report of a version (analysed now when there is none yet). */
    @SuppressWarnings("unchecked")
    public Map<String, Object> stored(User by, String path, Object version) {
        Map<String, Object> c = cr.getBot(by, path, version == null ? "latest" : version);
        int v = ((Number) c.get("version")).intValue();
        Map<String, Object> r = db.one("SELECT * FROM code_analysis WHERE path = ? AND version = ?", path, v);
        if (r == null) return analyze(by, path, v);
        Map<String, Object> rep = report(path, v, (List<Map<String, Object>>) JsonLite.parse((String) r.get("violations")));
        rep.put("analyzed", r.get("analyzed")); rep.put("analyzed_by", r.get("analyzed_by"));
        return rep;
    }

    /** Every agent the caller may see, analysed at its latest version against today's policy, as CSV. */
    public String repositoryCsv(User by) {
        StringBuilder sb = new StringBuilder(Platform.Csv.row(List.of("agent", "version", "rule", "severity", "step", "message")));
        for (Map<String, Object> b : cr.repoList(by, null)) {
            String path = (String) b.get("path");
            if (!analysable(path)) continue;
            Map<String, Object> rep = analyze(by, path, "latest");
            @SuppressWarnings("unchecked") List<Map<String, Object>> v = (List<Map<String, Object>>) rep.get("violations");
            if (v.isEmpty()) sb.append(Platform.Csv.row(List.of(path, String.valueOf(rep.get("version")), "", "passed", "", "")));
            for (Map<String, Object> x : v) sb.append(Platform.Csv.row(Arrays.asList(path, String.valueOf(rep.get("version")), String.valueOf(x.get("rule")), String.valueOf(x.get("severity")),
                    x.get("step") == null ? "" : String.valueOf(x.get("step")), String.valueOf(x.get("message")))));
        }
        return sb.toString();
    }

    /** At check-in: the violations, and whether the policy refuses the check-in for them. */
    void atCheckin(String path, Map<String, Object> flow) {
        if (!analysable(path)) return;
        if (!Boolean.TRUE.equals(policy().get("enforce_at_checkin"))) return;
        List<Map<String, Object>> v = check(flow);
        List<Map<String, Object>> errors = v.stream().filter(x -> "error".equals(x.get("severity"))).toList();
        if (!errors.isEmpty()) throw new OoaException("CR.POLICY_VIOLATION", errors.size() + " code analysis error(s): " + errors.get(0).get("message"),
                "Fix them, or ask an administrator about the code analysis policy.", false, Map.of("violations", v));
    }
}
