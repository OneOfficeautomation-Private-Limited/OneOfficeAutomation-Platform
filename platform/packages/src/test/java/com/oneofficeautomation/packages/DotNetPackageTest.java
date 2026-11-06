package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** G41: inline C# and VB.NET, .NET and COM calls (Windows), and Java calls in a separate process. */
class DotNetPackageTest {
    @TempDir Path dir;
    final DotNetPackage pkg = new DotNetPackage();

    ActionContext ctx() { return new ActionContext("run_1", "s1", "default", dir, new HashMap<>(), new HashMap<>(), new AtomicBoolean(), List::of, (l, m) -> {}); }
    static int num(Object o) { return ((Number) o).intValue(); }

    /** Classes a Java call loads from a class folder: one that answers, one that ends its process. */
    public static final class Fixture {
        private final String greeting;
        public Fixture(String greeting) { this.greeting = greeting; }
        public String greet(String who) { System.out.println("greeting " + who); return greeting + ", " + who; }
        public static List<Object> pair(int a, String b) { return List.of(a, b, Map.of("sum", a + 1)); }
        public static void quit() { System.exit(3); }
        public static int fail(int n) { throw new IllegalStateException("no " + n); }
    }

    @Test void javaStaticAndInstanceMethods() {
        String classes = DotNetPackage.codeSource(Fixture.class);
        assertEquals(BigDecimal.valueOf(9), new BigDecimal(String.valueOf(pkg.callJava(ctx(), null, "java.lang.Math", "max", List.of(4, 9), false, null, null, 60, true).get("result"))));
        Map<String, Object> g = pkg.callJava(ctx(), List.of(classes), Fixture.class.getName(), "greet", List.of("Asha"), true, List.of("Hello"), null, 60, true);
        assertEquals("Hello, Asha", g.get("result"));
        assertTrue(String.valueOf(g.get("output")).contains("greeting Asha"), "what the method prints comes back as output: " + g);
        assertEquals(List.of(BigDecimal.valueOf(2), "x", Map.of("sum", BigDecimal.valueOf(3))),
                pkg.callJava(ctx(), List.of(classes), Fixture.class.getName(), "pair", List.of(2, "x"), false, null, null, 60, true).get("result"));
    }

    @Test void javaErrorsAndAProcessThatExitsLeaveTheRunnerAlone() {
        String classes = DotNetPackage.codeSource(Fixture.class);
        assertEquals("JAVA.ERROR", assertThrows(ActionException.class, () -> pkg.callJava(ctx(), List.of(classes), Fixture.class.getName(), "fail", List.of(7), false, null, null, 60, true)).code());
        Map<String, Object> soft = pkg.callJava(ctx(), List.of(classes), Fixture.class.getName(), "fail", List.of(7), false, null, null, 60, false);
        assertEquals("java.lang.IllegalStateException: no 7", soft.get("error"));
        assertEquals("JAVA.NOT_FOUND", assertThrows(ActionException.class, () -> pkg.callJava(ctx(), null, "no.such.Thing", "x", List.of(), false, null, null, 60, true)).code());
        assertEquals("JAVA.NOT_FOUND", assertThrows(ActionException.class, () -> pkg.callJava(ctx(), null, "java.lang.Math", "max", List.of("a", "b"), false, null, null, 60, true)).code());
        // System.exit in the called code ends only its own process
        assertEquals("JAVA.FAILED", assertThrows(ActionException.class, () -> pkg.callJava(ctx(), List.of(classes), Fixture.class.getName(), "quit", List.of(), false, null, null, 60, true)).code());
        assertEquals("FILE.NOT_FOUND", assertThrows(ActionException.class, () -> pkg.callJava(ctx(), List.of("missing.jar"), "x.Y", "z", List.of(), false, null, null, 60, true)).code());
    }

    @Test void csharpAndVbNet() {
        assumeTrue(ProcessRunner.WINDOWS, "Windows only");
        Map<String, Object> cs = pkg.runCsharp(ctx(), "Console.WriteLine(\"Grüße\");\nvar items = (IList<object>)args[\"items\"];\nreturn new Dictionary<string, object> { { \"count\", items.Count }, { \"first\", items[0] }, { \"twice\", Twice(Convert.ToInt32(args[\"n\"])) } };",
                Map.of("items", List.of("a", "b"), "n", 21), null, "static int Twice(int x) { return x * 2; }", null, 120, true);
        assertEquals(Map.of("count", BigDecimal.valueOf(2), "first", "a", "twice", BigDecimal.valueOf(42)), normalise(cs.get("result")), String.valueOf(cs));
        assertEquals("Grüße", cs.get("output"));
        Map<String, Object> vb = pkg.runVbNet(ctx(), "Return String.Join(\"-\", CType(args(\"parts\"), IList(Of Object)).Select(Function(x) x.ToString().ToUpper()))",
                Map.of("parts", List.of("a", "b")), null, null, null, 120, true);
        assertEquals("A-B", vb.get("result"));
        ActionException compile = assertThrows(ActionException.class, () -> pkg.runCsharp(ctx(), "return nope;", null, null, null, null, 120, true));
        assertEquals("DOTNET.COMPILE_FAILED", compile.code());
        assertTrue(compile.getMessage().contains("nope"), compile.getMessage());
        assertEquals("DOTNET.ERROR", assertThrows(ActionException.class, () -> pkg.runCsharp(ctx(), "throw new InvalidOperationException(\"stop\");", null, null, null, null, 120, true)).code());
        Map<String, Object> soft = pkg.runCsharp(ctx(), "throw new InvalidOperationException(\"stop\");", null, null, null, null, 120, false);
        assertEquals("System.InvalidOperationException: stop", soft.get("error"));
        assertNull(pkg.runCsharp(ctx(), "Console.WriteLine(1);", null, null, null, null, 120, true).get("result"), "code without return gives nothing");
    }

    @Test void dotnetAndComCalls() {
        assumeTrue(ProcessRunner.WINDOWS, "Windows only");
        assertEquals(3, num(pkg.callDotNet(ctx(), null, "System.Math", "Max", "method", List.of(2, 3), false, null, 60, true).get("result")));
        assertEquals("ab", pkg.callDotNet(ctx(), null, "System.Text.StringBuilder", "ToString", "method", List.of(), true, List.of("ab"), 60, true).get("result"));
        assertEquals("DOTNET.NOT_FOUND", assertThrows(ActionException.class, () -> pkg.callDotNet(ctx(), null, "No.Such.Type", "X", "method", List.of(), false, null, 60, true)).code());
        assertEquals("DOTNET.NOT_FOUND", assertThrows(ActionException.class, () -> pkg.callDotNet(ctx(), null, "System.Math", "NoSuchMethod", "method", List.of(), false, null, 60, true)).code());
        String windows = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
        assertEquals("True", String.valueOf(pkg.callCom(ctx(), "Scripting.FileSystemObject", null, "FolderExists", "method", List.of(windows), false, 60, true).get("result")));
        assertEquals("COM.NOT_FOUND", assertThrows(ActionException.class, () -> pkg.callCom(ctx(), "No.Such.ProgId", null, "X", "method", List.of(), false, 60, true)).code());
    }

    /** JSON numbers as BigDecimal, so a check does not depend on how they were written. */
    @SuppressWarnings("unchecked")
    static Object normalise(Object v) {
        if (v instanceof Number n) return new BigDecimal(n.toString());
        if (v instanceof Map<?, ?> m) { Map<String, Object> o = new LinkedHashMap<>(); m.forEach((k, x) -> o.put((String) k, normalise(x))); return o; }
        if (v instanceof List<?> l) return l.stream().map(DotNetPackageTest::normalise).toList();
        return v;
    }
}
