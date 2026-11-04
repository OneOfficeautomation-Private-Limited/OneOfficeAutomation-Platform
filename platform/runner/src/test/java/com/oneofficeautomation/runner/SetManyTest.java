package com.oneofficeautomation.runner;

import com.oneofficeautomation.sdk.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Assign several variables (core/set_many, gap G26): one step, assignments in order, the same rules as Assign variable. */
class SetManyTest {

    static PackageRegistry registry() {
        PackageSigning.PublisherKey first = PackageSigning.PublisherKey.generate("oneofficeautomation");
        PackageRegistry r = new PackageRegistry();
        r.trust(first);
        r.install(CorePackage.class, PackageSigning.sign(CorePackage.class, first));
        return r;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> flow(String vars, String assignments) {
        return (Map<String, Object>) JsonLite.parse("""
            {"kind":"TaskBot","id":"m","name":"m","variables":[%s],
             "steps":[{"id":"s1","action":"core/set_many","args":{"assignments":%s}}]}""".formatted(vars, assignments));
    }

    static final String NUMS = """
        {"name":"price","type":"Number","direction":"output"},{"name":"qty","type":"Number","direction":"output"},
        {"name":"total","type":"Number","direction":"output"},{"name":"label","type":"String","direction":"output"}""";

    @Test void assignsInOrderSoLaterValuesSeeEarlierOnes() {
        EventSink.ListSink sink = new EventSink.ListSink();
        RunResult r = Runner.builder(registry()).sink(sink).build().run(flow(NUMS, """
            [{"variable":"price","value":2.5},{"variable":"qty","value":{"expr":"3"}},
             {"variable":"total","value":{"expr":"$price * $qty"}},{"variable":"label","value":"Total ${total}"}]"""), Map.of());
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals(0, new BigDecimal("7.5").compareTo((BigDecimal) r.outputs().get("total")));
        assertEquals("Total 7.5", r.outputs().get("label"));
        // one variable.set event per row, all from the one step
        List<RunEvent> sets = sink.events().stream().filter(e -> e.type().equals("variable.set")).toList();
        assertEquals(4, sets.size());
        assertTrue(sets.stream().allMatch(e -> "s1".equals(e.stepId())));
    }

    @Test void valuesAreCoercedToTheVariablesTypes() {
        RunResult r = Runner.builder(registry()).build().run(flow(NUMS, """
            [{"variable":"qty","value":"12"},{"variable":"label","value":42}]"""), Map.of());
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals(0, new BigDecimal("12").compareTo((BigDecimal) r.outputs().get("qty")));
        assertEquals("42", r.outputs().get("label"));
    }

    @Test void undeclaredVariableIsRefusedBeforeTheRun() {
        RunResult r = Runner.builder(registry()).build().run(flow(NUMS, """
            [{"variable":"price","value":1},{"variable":"missing","value":2}]"""), Map.of());
        assertFalse(r.ok());
        assertEquals("FLOW.INVALID", r.error().get("code"));
        assertTrue(String.valueOf(r.error()).contains("missing"), String.valueOf(r.error()));
    }

    @Test void rowWithoutVariableAndEmptyListAreRefused() {
        RunResult noVar = Runner.builder(registry()).build().run(flow(NUMS, "[{\"value\":1}]"), Map.of());
        assertEquals("FLOW.INVALID", noVar.error().get("code"));
        RunResult empty = Runner.builder(registry()).build().run(flow(NUMS, "[]"), Map.of());
        assertEquals("FLOW.INVALID", empty.error().get("code"));
    }

    @Test void vaultReferenceOnlyIntoACredential() {
        RunResult r = Runner.builder(registry()).build().run(flow(NUMS, """
            [{"variable":"label","value":{"$credential":"test/app/password"}}]"""), Map.of());
        assertEquals("FLOW.INVALID", r.error().get("code"));
        assertTrue(String.valueOf(r.error()).contains("Credential"), String.valueOf(r.error()));
    }

    @Test void credentialRowGetsTheSecretWithoutLeakingIt() {
        EventSink.ListSink sink = new EventSink.ListSink();
        CredentialVault vault = new CredentialVault.InMemory().put("test", "app", "password", "s3cr3t");
        RunResult r = Runner.builder(registry()).sink(sink).service("vault", vault).build().run(flow("""
            {"name":"pw","type":"Credential"},{"name":"user","type":"String","direction":"output"}""", """
            [{"variable":"user","value":"ada"},{"variable":"pw","value":{"$credential":"test/app/password"}}]"""), Map.of());
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals("ada", r.outputs().get("user"));
        assertFalse(JsonLite.write(sink.events().stream().map(RunEvent::toMap).toList()).contains("s3cr3t"));
    }
}
