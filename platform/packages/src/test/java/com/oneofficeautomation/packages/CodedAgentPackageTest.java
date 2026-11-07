package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.RunResult;
import com.oneofficeautomation.sdk.ActionException;
import com.oneofficeautomation.sdk.JsonLite;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** G70: an agent written in Python, calling toolkit commands through the run. Needs Python 3 on the machine. */
class CodedAgentPackageTest {
    @TempDir Path w;

    @BeforeAll static void python() {
        boolean found = false;
        for (String py : List.of("python3", "python", "py")) {
            try { Process p = new ProcessBuilder(py, "--version").redirectErrorStream(true).start(); found |= p.waitFor() == 0 && new String(p.getInputStream().readAllBytes()).startsWith("Python 3"); }
            catch (Exception ignored) { }
            if (found) break;
        }
        Assumptions.assumeTrue(found, "Python 3 is not installed here");
    }

    static final String AGENT = """
            def main(inputs, ooa):
                ooa.log("Cleaning " + inputs["name"])
                print("a line printed by the code")
                r = ooa.call("ooa.string/change_case", text=inputs["name"], case="upper")
                return {"upper": r["result"], "count": len(inputs["name"]), "grüße": "ok"}
            """;

    static String step(String code, String uses) {
        return "[{\"id\":\"code\",\"action\":\"ooa.coded/run_python_agent\",\"args\":{\"code\":" + JsonLite.write(code) + ",\"inputs\":{\"name\":\"acme ltd\"},\"uses\":" + uses
            + "},\"out\":{\"outputs\":\"r\",\"calls\":\"r2\"}}]";
    }

    @Test @SuppressWarnings("unchecked")
    void pythonCallsAToolkitCommandThroughTheRun() {
        RunResult r = CommandTestFixesTest.flow(w, CommandTestFixesTest.OUT, step(AGENT, "[\"ooa.string\"]"));
        assertEquals("success", r.status(), String.valueOf(r));
        Map<String, Object> out = (Map<String, Object>) r.outputs().get("r");
        assertEquals("ACME LTD", out.get("upper"));
        assertEquals("8", String.valueOf(out.get("count")));
        assertEquals("ok", out.get("grüße"), "text other than ASCII comes back intact");
        assertEquals("1", String.valueOf(r.outputs().get("r2")));
    }

    @Test void onlyTheToolkitsListedMayBeCalledAndErrorsComeBackAsTheCodesError() {
        RunResult r = CommandTestFixesTest.flow(w, CommandTestFixesTest.OUT, step(AGENT, "[]"));
        assertEquals("failed", r.status());
        assertTrue(String.valueOf(r).contains("CODE.TOOLKIT_NOT_LISTED"), String.valueOf(r));

        String caught = """
                def main(inputs, ooa):
                    try:
                        ooa.call("ooa.string/no_such_command", text="x")
                    except Exception as e:
                        return {"caught": e.code}
                """;
        RunResult c = CommandTestFixesTest.flow(w, CommandTestFixesTest.OUT, step(caught, "[\"ooa.string\"]"));
        assertEquals("success", c.status(), String.valueOf(c));
        assertEquals("FLOW.UNKNOWN_ACTION", String.valueOf(((Map<?, ?>) c.outputs().get("r")).get("caught")), String.valueOf(c));

        RunResult broken = CommandTestFixesTest.flow(w, CommandTestFixesTest.OUT, step("def main(inputs, ooa):\n    return 1 / 0\n", "[]"));
        assertEquals("failed", broken.status());
        assertTrue(String.valueOf(broken).contains("ZeroDivisionError"), String.valueOf(broken));
    }

    @Test void aModuleFileWithAnotherEntryAndNoRunner() throws Exception {
        Files.writeString(w.resolve("agent.py"), "def handle(inputs, ooa):\n    return inputs['a'] + 1\n");
        Map<String, Object> r = new CodedAgentPackage().run(PackagesTestSupport.ctx(w, Map.of()), "agent.py", null, Map.of("a", 41), List.of(), "handle", null);
        assertEquals(Map.of("result", 42L), Map.of("result", ((Number) ((Map<?, ?>) r.get("outputs")).get("result")).longValue()));
        assertEquals("CODE.BAD_ENTRY", assertThrows(ActionException.class, () -> new CodedAgentPackage().run(PackagesTestSupport.ctx(w, Map.of()), "agent.py", null, null, null, "x; import os", null)).code());
    }
}
