package com.oneofficeautomation.runner;

import com.oneofficeautomation.runner.expr.Expressions;
import com.oneofficeautomation.sdk.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RunnerTest {
    static final AtomicInteger CALLS = new AtomicInteger();

    @OoaPackage(id = "acme.flaky", name = "Flaky", version = "1.0.0", description = "Controllable failures for tests.", publisher = "acme")
    public static final class Flaky {
        @Action(id = "sometimes", name = "Sometimes fails", description = "Fails the first N calls with a retryable error.")
        @Output(name = "attempts", type = OoaType.NUMBER, description = "Calls so far.")
        public Map<String, Object> sometimes(ActionContext ctx, @Input(name = "fail_times", type = OoaType.NUMBER, description = "Failures.") int failTimes) {
            int n = CALLS.incrementAndGet();
            if (n <= failTimes) throw new ActionException("ACME.TRANSIENT", "transient", null, true);
            return Map.of("attempts", n);
        }

        @Action(id = "slow", name = "Slow", description = "Sleeps.", timeoutSeconds = 0.2)
        public Map<String, Object> slow(ActionContext ctx, @Input(name = "seconds", type = OoaType.NUMBER, description = "Sleep.") double s) throws Exception {
            Thread.sleep((long) (s * 1000));
            return Map.of();
        }

        @Action(id = "crash", name = "Crash", description = "Throws a plain Java exception.")
        public Map<String, Object> crash(ActionContext ctx) { throw new ArithmeticException("bug"); }

        @Action(id = "leaky_log", name = "Leaky log", description = "Logs a secret by mistake.")
        public Map<String, Object> leakyLog(ActionContext ctx, @Input(name = "secret", type = OoaType.CREDENTIAL, description = "Credential.") SecureString s) {
            ctx.info("password is " + s.reveal());
            return Map.of();
        }
    }

    static PackageRegistry registry() {
        PackageSigning.PublisherKey first = PackageSigning.PublisherKey.generate("oneofficeautomation");
        PackageSigning.PublisherKey acme = PackageSigning.PublisherKey.generate("acme");
        PackageRegistry r = new PackageRegistry();
        r.trust(first); r.trust(acme);
        r.install(CorePackage.class, PackageSigning.sign(CorePackage.class, first));
        r.install(Flaky.class, PackageSigning.sign(Flaky.class, acme));
        return r;
    }

    static Map<String, Object> bot(List<Object> steps, List<Object> vars) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("kind", "TaskBot"); f.put("id", "t"); f.put("name", "t"); f.put("variables", vars); f.put("steps", steps);
        return f;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> j(String json) { return (Map<String, Object>) JsonLite.parse(json); }

    static Object eval(String src, Map<String, Object> vars) { return Expressions.evaluate(src, vars::get); }

    @Test void expressionsUseExactDecimals() {
        assertEquals(0, ((BigDecimal) eval("0.1 + 0.2", Map.of())).compareTo(new BigDecimal("0.3")));
        assertEquals(new BigDecimal("2.35"), eval("round(2.345, 2)", Map.of()));
        assertEquals(true, eval("2 * 3 > 5 and not false", Map.of()));
        assertEquals("ACME", eval("upper($s)", Map.of("s", "acme")));
    }

    @Test void expressionSyntaxErrorsAreTyped() {
        for (String bad : List.of("1 +", "upper(", "foo()", "$s.upper()"))
            assertEquals("EXPR.INVALID", assertThrows(OoaException.class, () -> eval(bad, Map.of("s", "x"))).code(), bad);
        assertEquals("EXPR.DIV_ZERO", assertThrows(OoaException.class, () -> eval("1 / 0", Map.of())).code());
    }

    @Test void secretsCannotLeakThroughExpressions() {
        Map<String, Object> vars = Map.of("pw", new SecureString("hunter2", "x"));
        for (String src : List.of("upper($pw)", "$pw + 'x'", "$pw == 'hunter2'", "len($pw)"))
            assertEquals("VAR.SECURE_LEAK", assertThrows(OoaException.class, () -> eval(src, vars)).code(), src);
    }

    @Test void retryWithBackoff() {
        CALLS.set(0);
        EventSink.ListSink sink = new EventSink.ListSink();
        Runner r = Runner.builder(registry()).sink(sink).build();
        RunResult res = r.run(bot(List.of(j("{\"id\":\"s\",\"action\":\"acme.flaky/sometimes\",\"args\":{\"fail_times\":2},"
                + "\"out\":{\"attempts\":\"n\"},\"retry\":{\"max\":3,\"backoff\":\"exponential\",\"delayMs\":1}}")),
                List.of(j("{\"name\":\"n\",\"type\":\"Number\",\"direction\":\"output\"}"))), Map.of());
        assertTrue(res.ok(), String.valueOf(res.error()));
        assertEquals(0, ((BigDecimal) res.outputs().get("n")).compareTo(BigDecimal.valueOf(3)));
        assertEquals(2, sink.ofType("step.retry").size());
    }

    @Test void actionTimeout() {
        RunResult res = Runner.builder(registry()).build().run(bot(List.of(j("{\"id\":\"s\",\"action\":\"acme.flaky/slow\",\"args\":{\"seconds\":2}}")), List.of()), Map.of());
        assertEquals("STEP.TIMEOUT", res.error().get("code"));
        assertEquals("s", res.error().get("stepId"));
    }

    @Test void packageBugBecomesTypedError() {
        RunResult res = Runner.builder(registry()).build().run(bot(List.of(j("{\"id\":\"s\",\"action\":\"acme.flaky/crash\"}")), List.of()), Map.of());
        assertEquals("ACTION.UNEXPECTED", res.error().get("code"));
        assertTrue(String.valueOf(res.error().get("message")).contains("ArithmeticException"));
    }

    @Test void secretsAreRedactedFromEvents() {
        EventSink.ListSink sink = new EventSink.ListSink();
        CredentialVault vault = new CredentialVault.InMemory().put("test", "app", "password", "s3cr3t");
        Runner r = Runner.builder(registry()).sink(sink).service("vault", vault).build();
        RunResult res = r.run(bot(List.of(j("{\"id\":\"s\",\"action\":\"acme.flaky/leaky_log\",\"args\":{\"secret\":\"$pw\"}}")),
                List.of(j("{\"name\":\"pw\",\"type\":\"Credential\",\"direction\":\"input\",\"required\":true}"))),
                Map.of("pw", Map.of("$credential", "test/app/password")));
        assertTrue(res.ok(), String.valueOf(res.error()));
        String all = JsonLite.write(sink.events().stream().map(RunEvent::toMap).toList());
        assertFalse(all.contains("s3cr3t"));
        assertTrue(all.contains("****"));
    }

    @Test void cancelStopsRun() throws Exception {
        AtomicBoolean cancel = new AtomicBoolean();
        new Thread(() -> { try { Thread.sleep(100); } catch (InterruptedException ignored) { } cancel.set(true); }).start();
        long t0 = System.nanoTime();
        RunResult res = Runner.builder(registry()).build().run(bot(List.of(j("{\"id\":\"d\",\"action\":\"core/delay\",\"args\":{\"seconds\":5}}")), List.of()),
                Map.of(), new Runner.RunOptions().cancel(cancel));
        assertEquals("stopped", res.status());
        assertTrue((System.nanoTime() - t0) / 1e9 < 2);
    }

    @Test void parallelBranchesRunConcurrently() {
        long t0 = System.nanoTime();
        RunResult res = Runner.builder(registry()).build().run(bot(List.of(j("{\"id\":\"p\",\"action\":\"core/parallel\",\"children\":["
                + "{\"id\":\"a\",\"action\":\"core/delay\",\"args\":{\"seconds\":0.3}},"
                + "{\"id\":\"b\",\"action\":\"core/delay\",\"args\":{\"seconds\":0.3}},"
                + "{\"id\":\"c\",\"action\":\"core/delay\",\"args\":{\"seconds\":0.3}}]}")), List.of()), Map.of());
        assertTrue(res.ok());
        assertTrue((System.nanoTime() - t0) / 1e9 < 0.8);
    }

    @Test void planHashIgnoresLayout() {
        Runner r = Runner.builder(registry()).build();
        Map<String, Object> f = bot(List.of(j("{\"id\":\"c\",\"action\":\"core/comment\"}")), List.of());
        String h1 = r.run(f, Map.of()).planHash();
        Map<String, Object> withUi = new LinkedHashMap<>(f);
        withUi.put("ui", Map.of("c", Map.of("x", 1)));
        assertEquals(h1, r.run(withUi, Map.of()).planHash());
    }

    @Test void validationReportsAllProblemsWithFixes() {
        Map<String, Object> f = bot(List.of(
                j("{\"id\":\"s\",\"action\":\"core/set\",\"args\":{\"variable\":\"ghost\",\"value\":\"${nobody}\"}}"),
                j("{\"id\":\"s\",\"action\":\"ooa.nothing/act\"}")), List.of());
        List<Map<String, Object>> problems = new FlowValidator(registry(), null).validate(f);
        String all = problems.toString();
        for (String frag : List.of("Duplicate step id", "not declared", "not installed")) assertTrue(all.contains(frag), frag);
    }
}
