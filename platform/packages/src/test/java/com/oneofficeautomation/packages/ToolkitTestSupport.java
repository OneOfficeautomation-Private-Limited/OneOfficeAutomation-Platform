package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** Helpers shared by the REST toolkit tests (GitHub, Azure DevOps, Asana, Zendesk). */
final class ToolkitTestSupport {
    private ToolkitTestSupport() {}

    static final String SECRET = "tok-secret-123";
    static final SecureString TOKEN = new SecureString(SECRET, "test/api/token");

    /** Context that keeps every log line, so tests can prove the token never reaches the log. */
    static ActionContext ctx(Path work, List<String> log) {
        return new ActionContext("run_test", "s1", "default", work, new HashMap<>(), new HashMap<>(), new AtomicBoolean(), List::of,
                (level, msg) -> log.add(level + " " + msg));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> json(ConnectorsTest.Call c) { return (Map<String, Object>) JsonLite.parse(c.text()); }

    static Object json(ConnectorsTest.Call c, String dotted) { return HttpJson.path(json(c), dotted); }

    @SuppressWarnings("unchecked")
    static List<List<Object>> rows(Map<String, Object> result) { return (List<List<Object>>) ((Map<String, Object>) result.get("table")).get("rows"); }

    @SuppressWarnings("unchecked")
    static List<Object> columns(Map<String, Object> result) { return (List<Object>) ((Map<String, Object>) result.get("table")).get("columns"); }

    static List<Object> col(Map<String, Object> result, int i) { return rows(result).stream().map(r -> r.get(i)).toList(); }

    static ActionException fails(String code, org.junit.jupiter.api.function.Executable e) {
        ActionException ex = assertThrows(ActionException.class, e);
        assertEquals(code, ex.code(), "message: " + ex.getMessage());
        return ex;
    }

    /** Package registered, enough actions, every action and input described, no "bot" wording. */
    static void checkCatalog(String pid, int minActions) {
        PackageRegistry.LoadedPackage p = PackagesTest.REG.resolve(pid, null);
        assertTrue(p.info().actions().size() >= minActions, pid + " has " + p.info().actions().size() + " actions");
        List<ActionSpec> all = new ArrayList<>(p.info().actions().values());
        all.addAll(p.info().conditions().values());
        assertFalse(p.info().meta().description().toLowerCase(Locale.ROOT).matches(".*\\bbots?\\b.*"));
        for (ActionSpec a : all) {
            assertFalse(a.description().toLowerCase(Locale.ROOT).matches(".*\\bbots?\\b.*"), a.id());
            for (ActionSpec.InputSpec in : a.inputs()) {
                assertFalse(in.description().isBlank(), a.id() + "." + in.name());
                assertFalse(in.description().toLowerCase(Locale.ROOT).matches(".*\\bbots?\\b.*"), a.id() + "." + in.name());
            }
        }
    }

    static void noSecretInLog(List<String> log) {
        assertFalse(log.isEmpty(), "requests are logged");
        for (String l : log) assertFalse(l.contains(SECRET), "token leaked into log: " + l);
    }
}
