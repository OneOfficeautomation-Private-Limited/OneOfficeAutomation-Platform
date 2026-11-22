package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * G4 (AA parity, 22 Nov 2026): Python, JavaScript and VBScript sessions that keep what one step defines for the next,
 * and a .NET assembly session with objects kept by name, typed and named arguments and Read assembly. Each part is
 * skipped when its interpreter is not installed.
 */
class ScriptSessionsG4Test {
    final ScriptPackage sp = new ScriptPackage();

    static ActionContext ctx(Path w) { return PackagesTestSupport.ctx(w, Map.of()); }

    static boolean has(String program) {
        try { Process p = new ProcessBuilder(program, "--version").redirectErrorStream(true).start(); p.getInputStream().readAllBytes(); return p.waitFor() == 0; }
        catch (Exception e) { return false; }
    }

    @Test void pythonKeepsWhatAStepDefines() throws Exception {
        if (!has(ProcessRunner.WINDOWS ? "python" : "python3")) { System.out.println("    (no Python; skipped)"); return; }
        Path w = Files.createTempDirectory("ooa-g4py");
        Files.writeString(w.resolve("tools.py"), "rate = 0.2\ndef total(n, extra=0):\n    return round(n * (1 + rate), 2) + extra\n");
        ActionContext c = ctx(w);
        try {
            sp.openSession(c, "python", "py", "tools.py", null, null, false);
            assertEquals(new BigDecimal("120.0"), new BigDecimal(String.valueOf(sp.callInSession(c, "py", "total", List.of(100), null, 30, true).get("result"))));
            sp.runInSession(c, "py", "count = 1\nprint('hello')", null, 30, true, "exec");
            Map<String, Object> r = sp.runInSession(c, "py", "count += 41\ncount", null, 30, true, "exec");
            assertEquals(0, new BigDecimal(42).compareTo(new BigDecimal(String.valueOf(r.get("result")))), "the variable from the step before");
            assertEquals(0, new BigDecimal(125).compareTo(new BigDecimal(String.valueOf(sp.callInSession(c, "py", "total", List.of(100), Map.of("extra", 5), 30, true).get("result")))));
            assertEquals("hello", sp.runInSession(c, "py", "print('hello')", null, 30, true, "exec").get("output"));
            Map<String, Object> bad = sp.runInSession(c, "py", "1/0", null, 30, false, "exec");
            assertTrue(String.valueOf(bad.get("error")).startsWith("ZeroDivisionError"), String.valueOf(bad));
            assertFalse(sp.lastScriptSucceeded(c, "py"));
            assertEquals("SCRIPT.FAILED", assertThrows(ActionException.class, () -> sp.runInSession(c, "py", "undefined_name", null, 30, true, "exec")).code());
            assertTrue(sp.sessionOpen(c, "py"));
            sp.closeSession(c, "py");
            assertFalse(sp.sessionOpen(c, "py"));
            assertEquals("SCRIPT.NO_SESSION", assertThrows(ActionException.class, () -> sp.runInSession(c, "py", "1", null, 30, true, "exec")).code());
        } finally { sp.closeSession(c, "py"); }
    }

    @Test void javascriptKeepsWhatAStepDefines() throws Exception {
        if (!has("node")) { System.out.println("    (no Node.js; skipped)"); return; }
        ActionContext c = ctx(Files.createTempDirectory("ooa-g4js"));
        try {
            sp.openSession(c, "javascript", "js", null, null, null, false);
            sp.runInSession(c, "js", "var n = 40; function add(a, b) { return a + b; } console.log('ready')", null, 30, true, "exec");
            assertEquals(0, new BigDecimal(42).compareTo(new BigDecimal(String.valueOf(sp.runInSession(c, "js", "n + 2", null, 30, true, "exec").get("result")))));
            assertEquals(0, new BigDecimal(7).compareTo(new BigDecimal(String.valueOf(sp.callInSession(c, "js", "add", List.of(3, 4), null, 30, true).get("result")))));
            assertEquals("done", sp.runInSession(c, "js", "(async () => { await new Promise(r => setTimeout(r, 50)); return 'done'; })()", null, 30, true, "exec").get("result"), "promises are awaited");
            assertEquals("SCRIPT.FAILED", assertThrows(ActionException.class, () -> sp.callInSession(c, "js", "missing", null, null, 30, true)).code());
        } finally { sp.closeSession(c, "js"); }
    }

    @Test void vbscriptKeepsWhatAStepDefines() throws Exception {
        if (!ProcessRunner.WINDOWS) return;
        ActionContext c = ctx(Files.createTempDirectory("ooa-g4vbs"));
        try {
            sp.openSession(c, "vbscript", "vb", null, null, null, false);
            sp.runInSession(c, "vb", "Dim total\ntotal = 40\nFunction Greet(name, n)\n  Greet = \"Hi \" & name & \" \" & (n + total)\nEnd Function", null, 30, true, "exec");
            assertEquals("Hi Ann \"x\" 42", sp.callInSession(c, "vb", "Greet", List.of("Ann \"x\"", 2), null, 30, true).get("result"));
            assertEquals(0, new BigDecimal(41).compareTo(new BigDecimal(String.valueOf(sp.runInSession(c, "vb", "total + 1", null, 30, true, "eval").get("result")))));
            Map<String, Object> bad = sp.runInSession(c, "vb", "x = 1 / 0", null, 30, false, "exec");
            assertTrue(String.valueOf(bad.get("error")).toLowerCase(Locale.ROOT).contains("division"), String.valueOf(bad));
            assertFalse(sp.lastScriptSucceeded(c, "vb"));
        } finally { sp.closeSession(c, "vb"); }
    }

    @SuppressWarnings("unchecked")
    @Test void dotNetAssemblySession() throws Exception {
        if (!ProcessRunner.WINDOWS) return;
        Path w = Files.createTempDirectory("ooa-g4net");
        Path dll = w.resolve("Pricing.dll");
        String cs = "namespace Pricing { public class Calculator { decimal rate; public Calculator(decimal rate) { this.rate = rate; } "
                + "public decimal Price(decimal amount, int quantity = 1, bool rounded = false) { decimal p = amount * quantity * (1 + rate); return rounded ? System.Math.Round(p) : p; } "
                + "public static string Hello(string name) { return \"Hello \" + name; } } }";
        Files.writeString(w.resolve("Pricing.cs"), cs);
        Process p = new ProcessBuilder("powershell", "-NoProfile", "-Command", "Add-Type -Path '" + w.resolve("Pricing.cs") + "' -OutputAssembly '" + dll + "' -OutputType Library")
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        assertEquals(0, p.waitFor(), out);
        ActionContext c = ctx(w);
        DotNetPackage dn = new DotNetPackage();
        try {
            assertTrue(String.valueOf(dn.openAssembly(c, "Pricing.dll", "net", false).get("assembly")).startsWith("Pricing"));
            assertTrue(dn.assemblySessionOpen(c, "net"));
            assertEquals("Hello Ann", dn.callMethod(c, "net", "Pricing.Calculator", null, "Hello", List.of("Ann"), null, null, 60).get("result"));
            dn.createObject(c, "net", "Pricing.Calculator", "calc", List.of("0.5"), List.of("decimal"), null);
            assertEquals(0, new BigDecimal("30").compareTo(new BigDecimal(String.valueOf(dn.callMethod(c, "net", null, "calc", "Price", List.of(10), null, Map.of("quantity", 2), 60).get("result")))),
                    "named argument, optional one left out");
            assertEquals(0, new BigDecimal("15").compareTo(new BigDecimal(String.valueOf(dn.callMethod(c, "net", null, "calc", "Price", List.of("9.7", 1, "true"), List.of("decimal", "int", "bool"), null, 60).get("result")))),
                    "typed arguments, rounded");
            assertEquals(0, new BigDecimal(3).compareTo(new BigDecimal(String.valueOf(dn.callMethod(c, "net", "System.Math", null, "Max", List.of(2, 3), List.of("int", "int"), null, 60).get("result")))),
                    "a framework type");
            assertEquals("DOTNET.FAILED", assertThrows(ActionException.class, () -> dn.callMethod(c, "net", null, "nobody", "Price", List.of(1), null, null, 60)).code());
            List<Object> types = (List<Object>) dn.readAssembly(c, "Pricing.dll").get("types");
            Map<String, Object> calc = (Map<String, Object>) types.get(0);
            assertEquals("Pricing.Calculator", calc.get("type"));
            assertTrue(String.valueOf(calc.get("methods")).contains("quantity"), String.valueOf(calc.get("methods")));
            dn.closeAssembly(c, "net");
            assertFalse(dn.assemblySessionOpen(c, "net"));
        } finally { dn.closeAssembly(c, "net"); }
    }
}
