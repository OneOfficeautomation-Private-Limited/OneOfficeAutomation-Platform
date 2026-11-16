package com.oneofficeautomation.runner;

import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.SecureString;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.oneofficeautomation.runner.RunnerTest.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A Credential variable may default to a vault reference (real-world tests finding 7): a child agent run on its own, or
 * with no credential passed from its parent, reads its usual credential from the vault. The default is never the secret.
 */
class CredentialDefaultTest {
    static final String PW = "{\"name\":\"pw\",\"type\":\"Credential\",\"direction\":\"input\",\"default\":{\"$credential\":\"test/app/password\"}}";
    static final String LOG = "{\"id\":\"s\",\"action\":\"acme.flaky/leaky_log\",\"args\":{\"secret\":\"$pw\"}}";

    /** A vault that remembers which references were read. */
    static CredentialVault vault(List<String> asked) {
        return (ref, principal) -> { asked.add(ref); return new SecureString(ref.endsWith("other/password") ? "0th3r-pass" : "s3cr3t-pass", ref); };
    }

    @Test void theDefaultIsReadFromTheVaultAndMasked() {
        EventSink.ListSink sink = new EventSink.ListSink();
        List<String> asked = new CopyOnWriteArrayList<>();
        RunResult res = Runner.builder(registry()).sink(sink).service("vault", vault(asked)).build()
                .run(bot(List.of(j(LOG)), List.of(j(PW))), Map.of());
        assertTrue(res.ok(), String.valueOf(res.error()));
        assertEquals(List.of("test/app/password"), asked);
        String all = JsonLite.write(sink.events().stream().map(RunEvent::toMap).toList());
        assertFalse(all.contains("s3cr3t-pass"), "the secret is masked in the run's events");
        assertTrue(all.contains("****"));
    }

    @Test void aValueGivenWinsOverTheDefault() {
        List<String> asked = new CopyOnWriteArrayList<>();
        RunResult res = Runner.builder(registry()).service("vault", vault(asked)).build()
                .run(bot(List.of(j(LOG)), List.of(j(PW))), Map.of("pw", Map.of("$credential", "test/other/password")));
        assertTrue(res.ok(), String.valueOf(res.error()));
        assertEquals(List.of("test/other/password"), asked, "the default is not read when the caller gives a credential");
    }

    @Test void withoutAVaultTheRunSaysSo() {
        RunResult res = Runner.builder(registry()).build().run(bot(List.of(j(LOG)), List.of(j(PW))), Map.of());
        assertFalse(res.ok());
        assertEquals("VAULT.UNAVAILABLE", res.error().get("code"));
    }

    @Test void validateTakesAVaultReferenceDefaultButNeverTheSecret() {
        FlowValidator v = new FlowValidator(registry(), null);
        assertTrue(v.validate(bot(List.of(j(LOG)), List.of(j(PW)))).stream().noneMatch(p -> String.valueOf(p.get("message")).contains("Credential variable")));
        for (String bad : List.of("\"s3cr3t\"", "{\"$credential\":\"\"}", "{\"$credential\":\"test/app/password\",\"x\":1}")) {
            List<Map<String, Object>> problems = v.validate(bot(List.of(j(LOG)), List.of(j(PW.replace("{\"$credential\":\"test/app/password\"}", bad)))));
            assertTrue(problems.stream().anyMatch(p -> String.valueOf(p.get("message")).equals("Credential variable 'pw' can only default to a vault reference")),
                    bad + " -> " + problems);
        }
    }
}
