package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.controlroom.ControlRoom.User;
import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

import static com.oneofficeautomation.controlroom.ControlRoom.*;

/**
 * Test sets and scores (A2). A test set belongs to an agent and lists cases: inputs, and what the run should give back.
 * Running a set starts one run per case (source "test:RUN") on a runner or pool; each run is judged when it finishes,
 * and the test run is scored once every case has an answer. Expectations suit AI output as well as exact values:
 * <pre>
 *   {"status": "success" | "failed", "error_code": "EMAIL.*",
 *    "outputs": {"total": 120.5,                                   equals (numbers by value)
 *                "summary": {"op": "contains", "value": "refund"},  contains, not_contains, matches (regex),
 *                "score":   {"op": "approx", "value": 0.8, "tolerance": 0.1},  approx, not_empty, equals
 *                "label":   {"op": "one_of", "value": ["urgent", "high"]}}}
 * </pre>
 */
public final class TestSets {
    static final Set<String> OPS = Set.of("equals", "contains", "not_contains", "matches", "approx", "not_empty", "one_of");
    private final ControlRoom cr;
    private final Db db;

    TestSets(ControlRoom cr) { this.cr = cr; this.db = cr.db; }

    // ---------- the sets ----------

    public List<Map<String, Object>> list(User by, String botPath) {
        cr.require(by, "repo.view", "view test sets");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> s : botPath == null || botPath.isBlank() ? db.query("SELECT * FROM test_sets ORDER BY bot_path, name")
                : db.query("SELECT * FROM test_sets WHERE bot_path = ? ORDER BY name", botPath)) {
            if (!cr.folders.mayView(by, (String) s.get("bot_path"))) continue;
            Map<String, Object> m = view(s);
            Map<String, Object> last = db.one("SELECT id, started, finished, total, passed, status FROM test_runs WHERE set_id = ? ORDER BY started DESC LIMIT 1", s.get("id"));
            m.put("last_run", last);
            out.add(m);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> view(Map<String, Object> s) {
        Map<String, Object> m = new LinkedHashMap<>(s);
        m.put("cases", JsonLite.parse((String) s.get("cases")));
        return m;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> save(User by, String id, Map<String, Object> b, String ip) {
        cr.require(by, "repo.checkin", "manage test sets");
        String name = String.valueOf(b.getOrDefault("name", "")).trim();
        if (name.isEmpty() || name.length() > 120) throw new OoaException("CR.BAD_INPUT", "Test set names have 1 to 120 characters");
        String bot = String.valueOf(b.getOrDefault("bot_path", ""));
        if (!bot.endsWith(".flow.json")) throw new OoaException("CR.BAD_INPUT", "Choose the agent the test set is for");
        cr.content(bot, "latest");
        cr.folders.require(by.username(), by.role(), bot, "edit", "test this agent");
        if (!(b.get("cases") instanceof List<?> cases) || cases.isEmpty()) throw new OoaException("CR.BAD_INPUT", "Add at least one case");
        if (cases.size() > 500) throw new OoaException("CR.BAD_INPUT", "At most 500 cases in a test set");
        Set<String> names = new HashSet<>();
        for (Object o : cases) {
            if (!(o instanceof Map<?, ?> c)) throw new OoaException("CR.BAD_INPUT", "Each case is a dictionary with name, inputs and expect");
            String cn = String.valueOf(c.get("name") == null ? "" : c.get("name")).trim();
            if (cn.isEmpty() || !names.add(cn)) throw new OoaException("CR.BAD_INPUT", "Every case needs its own name" + (cn.isEmpty() ? "" : ": " + cn + " is used twice"));
            if (c.get("inputs") != null && !(c.get("inputs") instanceof Map)) throw new OoaException("CR.BAD_INPUT", "Case " + cn + ": inputs is a dictionary");
            checkExpect(cn, c.get("expect"));
        }
        String json = JsonLite.write(cases);
        if (json.length() > 100_000) throw new OoaException("CR.BAD_INPUT", "The test set is too large (100 KB at most)");
        String sid = id == null || id.isBlank() ? id("tset") : id;
        if (id == null || id.isBlank())
            db.update("INSERT INTO test_sets (id, name, bot_path, description, cases, created_by, updated, updated_by) VALUES (?,?,?,?,?,?,?,?)",
                sid, name, bot, b.get("description"), json, by.username(), now(), by.username());
        else if (db.update("UPDATE test_sets SET name = ?, bot_path = ?, description = ?, cases = ?, updated = ?, updated_by = ? WHERE id = ?",
                name, bot, b.get("description"), json, now(), by.username(), sid) == 0) throw notFound("Test set");
        cr.audit.record(by.username(), "testset.save", "test_set", sid, Map.of("name", name, "agent", bot, "cases", cases.size()), "ok", ip);
        return Map.of("id", sid);
    }

    @SuppressWarnings("unchecked")
    static void checkExpect(String cn, Object e) {
        if (e == null) return;
        if (!(e instanceof Map<?, ?> m)) throw new OoaException("CR.BAD_INPUT", "Case " + cn + ": expect is a dictionary");
        Object st = m.get("status");
        if (st != null && !List.of("success", "failed").contains(st)) throw new OoaException("CR.BAD_INPUT", "Case " + cn + ": expected status is success or failed");
        if (m.get("outputs") != null && !(m.get("outputs") instanceof Map)) throw new OoaException("CR.BAD_INPUT", "Case " + cn + ": expected outputs is a dictionary");
        if (m.get("outputs") instanceof Map<?, ?> outs) for (var o : outs.entrySet()) {
            if (o.getValue() instanceof Map<?, ?> rule && rule.containsKey("op")) {
                String op = String.valueOf(rule.get("op"));
                if (!OPS.contains(op)) throw new OoaException("CR.BAD_INPUT", "Case " + cn + ", output " + o.getKey() + ": the check is one of " + new TreeSet<>(OPS));
                if (op.equals("matches")) {
                    try { Pattern.compile(String.valueOf(rule.get("value"))); }
                    catch (RuntimeException x) { throw new OoaException("CR.BAD_INPUT", "Case " + cn + ", output " + o.getKey() + ": not a regular expression"); }
                }
                if (op.equals("approx") && !(rule.get("value") instanceof Number)) throw new OoaException("CR.BAD_INPUT", "Case " + cn + ", output " + o.getKey() + ": approx compares numbers");
            }
        }
    }

    public void delete(User by, String id, String ip) {
        cr.require(by, "repo.checkin", "manage test sets");
        Map<String, Object> s = db.one("SELECT bot_path FROM test_sets WHERE id = ?", id);
        if (s == null) throw notFound("Test set");
        cr.folders.require(by.username(), by.role(), (String) s.get("bot_path"), "edit", "test this agent");
        db.tx(() -> {
            db.update("DELETE FROM test_results WHERE test_run_id IN (SELECT id FROM test_runs WHERE set_id = ?)", id);
            db.update("DELETE FROM test_runs WHERE set_id = ?", id);
            db.update("DELETE FROM test_sets WHERE id = ?", id);
            return null;
        });
        cr.audit.record(by.username(), "testset.delete", "test_set", id, null, "ok", ip);
    }

    // ---------- running and scoring ----------

    /**
     * Starts one run per case on the runner or pool, against the agent's live production version (as every run);
     * returns the test run id. A case that cannot start fails at once.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> run(User by, String setId, String deviceId, String poolId, String version, String ip) {
        Map<String, Object> s = db.one("SELECT * FROM test_sets WHERE id = ?", setId);
        if (s == null) throw notFound("Test set");
        List<Map<String, Object>> cases = (List<Map<String, Object>>) JsonLite.parse((String) s.get("cases"));
        String bot = (String) s.get("bot_path");
        Map<String, Object> content = cr.content(bot, "production");
        String trId = id("trun");
        db.update("INSERT INTO test_runs (id, set_id, bot_path, version, started, started_by, total, passed, status) VALUES (?,?,?,?,?,?,?,?,?)",
            trId, setId, bot, ((Number) content.get("version")).intValue(), now(), by.username(), cases.size(), 0, "running");
        int started = 0;
        for (Map<String, Object> c : cases) {
            Map<String, Object> inputs = c.get("inputs") instanceof Map<?, ?> in ? (Map<String, Object>) in : Map.of();
            try {
                Map<String, Object> r = cr.startRun(by.username(), by.role(), bot, deviceId, poolId, inputs, "test:" + trId, ip, 0, null, null, null);
                db.update("INSERT INTO test_results (run_id, test_run_id, case_name, status, passed, detail, finished) VALUES (?,?,?,?,?,?,?)",
                    r.get("run_id"), trId, c.get("name"), "running", false, null, null);
                started++;
            } catch (OoaException e) {
                db.update("INSERT INTO test_results (run_id, test_run_id, case_name, status, passed, detail, finished) VALUES (?,?,?,?,?,?,?)",
                    id("norun"), trId, c.get("name"), "failed", false, "Did not start: " + e.getMessage(), now());
            }
        }
        cr.audit.record(by.username(), "testset.run", "test_set", setId, Map.of("test_run", trId, "cases", cases.size(), "started", started), "ok", ip);
        settle(trId);
        return Map.of("test_run_id", trId, "cases", cases.size(), "started", started);
    }

    /** Called when any run finishes: judges a test case's run and scores its test run once every case answered. */
    @SuppressWarnings("unchecked")
    void onRunFinished(Map<String, Object> run, String status, Map<String, Object> result) {
        String src = String.valueOf(run.get("source"));
        if (!src.startsWith("test:")) return;
        String trId = src.substring(5);
        Map<String, Object> tr = db.one("SELECT set_id FROM test_runs WHERE id = ?", trId);
        Map<String, Object> res = db.one("SELECT case_name FROM test_results WHERE run_id = ?", run.get("id"));
        if (tr == null || res == null) return;
        Map<String, Object> set = db.one("SELECT cases FROM test_sets WHERE id = ?", tr.get("set_id"));
        Map<String, Object> theCase = set == null ? Map.of() : ((List<Map<String, Object>>) JsonLite.parse((String) set.get("cases"))).stream()
            .filter(c -> String.valueOf(c.get("name")).equals(res.get("case_name"))).findFirst().orElse(Map.of());
        List<String> why = judge(theCase.get("expect") instanceof Map<?, ?> e ? (Map<String, Object>) e : Map.of(), status, result);
        db.update("UPDATE test_results SET status = ?, passed = ?, detail = ?, finished = ? WHERE run_id = ?",
            status, why.isEmpty(), why.isEmpty() ? null : cut(String.join("; ", why), 4000), now(), run.get("id"));
        settle(trId);
    }

    /** Scores a test run when no case is still running. */
    synchronized void settle(String trId) {
        if (cr.count("SELECT COUNT(*) AS n FROM test_results WHERE test_run_id = ? AND status = 'running'", trId) > 0) return;
        long passed = cr.count("SELECT COUNT(*) AS n FROM test_results WHERE test_run_id = ? AND passed = TRUE", trId);
        long total = cr.count("SELECT COUNT(*) AS n FROM test_results WHERE test_run_id = ?", trId);
        if (db.update("UPDATE test_runs SET status = ?, passed = ?, total = ?, finished = ? WHERE id = ? AND status = 'running'",
                passed == total ? "passed" : "failed", passed, total, now(), trId) == 1)
            cr.audit.record("system", "testset.scored", "test_run", trId, Map.of("passed", passed, "total", total), passed == total ? "ok" : "failed", null);
    }

    /** What is wrong with a run's result against a case's expectations; empty when it passes. */
    @SuppressWarnings("unchecked")
    static List<String> judge(Map<String, Object> expect, String status, Map<String, Object> result) {
        List<String> why = new ArrayList<>();
        String want = expect.get("status") == null ? "success" : String.valueOf(expect.get("status"));
        if (!want.equals(status)) {
            Object err = result.get("error");
            why.add("status " + status + ", expected " + want + (err instanceof Map<?, ?> m && m.get("message") != null ? " (" + m.get("code") + ": " + m.get("message") + ")" : ""));
            return why;
        }
        if (expect.get("error_code") instanceof String code) {
            String got = result.get("error") instanceof Map<?, ?> m ? String.valueOf(m.get("code")) : "";
            boolean ok = code.endsWith(".*") ? got.startsWith(code.substring(0, code.length() - 1)) : got.equals(code);
            if (!ok) why.add("error " + got + ", expected " + code);
        }
        Map<String, Object> outs = result.get("outputs") instanceof Map<?, ?> o ? (Map<String, Object>) o : Map.of();
        if (expect.get("outputs") instanceof Map<?, ?> exp) for (var e : ((Map<String, Object>) exp).entrySet()) {
            Object got = outs.get(e.getKey());
            String miss = check(e.getValue(), got);
            if (miss != null) why.add(e.getKey() + ": " + miss);
        }
        return why;
    }

    /** One output against its rule; null when it passes. */
    @SuppressWarnings("unchecked")
    static String check(Object rule, Object got) {
        String op = "equals";
        Object want = rule;
        double tol = 0;
        if (rule instanceof Map<?, ?> r && r.containsKey("op")) {
            op = String.valueOf(r.get("op"));
            want = r.get("value");
            tol = r.get("tolerance") instanceof Number n ? n.doubleValue() : 0;
        }
        String text = got == null ? "" : Values.text(got);
        return switch (op) {
            case "not_empty" -> text.isBlank() ? "is empty" : null;
            case "contains" -> text.toLowerCase(Locale.ROOT).contains(String.valueOf(want).toLowerCase(Locale.ROOT)) ? null : "\"" + cut(text, 120) + "\" does not contain \"" + want + "\"";
            case "not_contains" -> text.toLowerCase(Locale.ROOT).contains(String.valueOf(want).toLowerCase(Locale.ROOT)) ? "contains \"" + want + "\"" : null;
            case "matches" -> Pattern.compile(String.valueOf(want)).matcher(text).find() ? null : "\"" + cut(text, 120) + "\" does not match " + want;
            case "one_of" -> want instanceof List<?> l && l.stream().anyMatch(x -> Values.looseEquals(x, got)) ? null : "\"" + cut(text, 120) + "\" is not one of " + want;
            case "approx" -> {
                try {
                    double g = Values.toNumber(got).doubleValue(), w = ((Number) want).doubleValue();
                    yield Math.abs(g - w) <= tol ? null : g + " is not within " + tol + " of " + w;
                } catch (RuntimeException x) { yield "\"" + cut(text, 120) + "\" is not a number"; }
            }
            default -> {
                Object w = want instanceof Number n && !(want instanceof BigDecimal) ? new BigDecimal(n.toString()) : want;
                yield Values.looseEquals(w, got) || Objects.equals(JsonLite.write(w), JsonLite.write(got)) ? null : "got " + cut(JsonLite.write(got), 160) + ", expected " + cut(JsonLite.write(want), 160);
            }
        };
    }

    private static String cut(String s, int n) { return s.length() > n ? s.substring(0, n) + "..." : s; }

    /** A test run with each case's verdict. */
    public Map<String, Object> testRun(User by, String trId) {
        cr.require(by, "repo.view", "view test results");
        Map<String, Object> tr = db.one("SELECT * FROM test_runs WHERE id = ?", trId);
        if (tr == null) throw notFound("Test run");
        if (!cr.folders.mayView(by, (String) tr.get("bot_path"))) throw cr.denied("view this agent's tests");
        Map<String, Object> m = new LinkedHashMap<>(tr);
        m.put("results", db.query("SELECT run_id, case_name, status, passed, detail, finished FROM test_results WHERE test_run_id = ? ORDER BY case_name", trId));
        return m;
    }

    /** The scores of a set over time, newest first. */
    public List<Map<String, Object>> history(User by, String setId) {
        cr.require(by, "repo.view", "view test results");
        Map<String, Object> s = db.one("SELECT bot_path FROM test_sets WHERE id = ?", setId);
        if (s == null) throw notFound("Test set");
        if (!cr.folders.mayView(by, (String) s.get("bot_path"))) throw cr.denied("view this agent's tests");
        return db.query("SELECT id, version, started, started_by, finished, total, passed, status FROM test_runs WHERE set_id = ? ORDER BY started DESC LIMIT 50", setId);
    }
}
