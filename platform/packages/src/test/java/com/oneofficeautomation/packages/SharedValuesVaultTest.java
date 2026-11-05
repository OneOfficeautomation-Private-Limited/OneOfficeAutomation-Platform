package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.CredentialVault;
import com.oneofficeautomation.runner.RunResult;
import com.oneofficeautomation.runner.Runner;
import com.oneofficeautomation.sdk.JsonLite;
import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** Gap G30: ooa.global_values set and the ooa.vault toolkit, in Studio (workspace file) and with One CommandCenter's services. */
class SharedValuesVaultTest {
    static final String OUT = "[{\"name\":\"v\",\"type\":\"Any\",\"direction\":\"output\"},{\"name\":\"e\",\"type\":\"Any\",\"direction\":\"output\"},"
            + "{\"name\":\"d\",\"type\":\"Any\",\"direction\":\"output\"},{\"name\":\"x\",\"type\":\"Any\",\"direction\":\"output\"}]";

    @SuppressWarnings("unchecked")
    static RunResult run(Path w, Map<String, Object> services, String steps) {
        Map<String, Object> flow = (Map<String, Object>) JsonLite.parse("{\"kind\":\"TaskBot\",\"id\":\"t\",\"name\":\"t\",\"variables\":" + OUT + ",\"steps\":" + steps + "}");
        Runner.Builder b = Runner.builder(PackagesTest.REG).workDir(w).service("vault", new CredentialVault.InMemory().put("it", "app", "password", "N3w-Pass"));
        services.forEach(b::service);
        return b.principal("jane").build().run(flow, Map.of());
    }

    @Test void studioChangesTheWorkspaceFile() throws Exception {
        Path w = PackagesTest.tmp();
        Files.writeString(w.resolve("global-values.json"), "{\"threshold\": 1000, \"team\": \"Finance\"}");
        RunResult r = run(w, Map.of(), "[{\"id\":\"1\",\"action\":\"ooa.global_values/set\",\"args\":{\"name\":\"threshold\",\"value\":\"2500\",\"type\":\"Number\"},\"out\":{\"value\":\"v\"}},"
                + "{\"id\":\"2\",\"action\":\"ooa.global_values/get\",\"args\":{\"name\":\"threshold\"},\"out\":{\"value\":\"e\"}}]");
        assertTrue(r.ok(), String.valueOf(r.error()));
        PackagesTest.eqNum("2500", r.outputs().get("v"));
        PackagesTest.eqNum("2500", r.outputs().get("e"));
        assertTrue(Files.readString(w.resolve("global-values.json")).contains("\"team\":\"Finance\""), "other values are kept");
        assertEquals("GLOBALS.NOT_FOUND", run(w, Map.of(), "[{\"id\":\"1\",\"action\":\"ooa.global_values/set\",\"args\":{\"name\":\"nope\",\"value\":1}}]").error().get("code"));
        assertEquals("GLOBALS.WRONG_TYPE", run(w, Map.of(), "[{\"id\":\"1\",\"action\":\"ooa.global_values/set\",\"args\":{\"name\":\"threshold\",\"value\":\"lots\",\"type\":\"Number\"}}]").error().get("code"));
        // a vault reference in value is refused before the run starts (only Credential inputs take one)
        assertEquals("FLOW.INVALID", run(w, Map.of(), "[{\"id\":\"1\",\"action\":\"ooa.global_values/set\",\"args\":{\"name\":\"team\",\"value\":{\"$credential\":\"it/app/password\"}}}]").error().get("code"));
        // the vault cannot be written in Studio; an expiry check reports none
        assertEquals("VAULT.LOCAL_READ_ONLY", run(w, Map.of(), "[{\"id\":\"1\",\"action\":\"ooa.vault/save_credential\",\"args\":{\"credential\":\"it/app/password\",\"value\":{\"$credential\":\"it/app/password\"}}}]").error().get("code"));
        RunResult e = run(w, Map.of(), "[{\"id\":\"1\",\"action\":\"ooa.vault/credential_expiry\",\"args\":{\"credential\":\"it/app\"},\"out\":{\"has_expiry\":\"x\",\"expired\":\"e\"}}]");
        assertEquals(false, e.outputs().get("x"));
        assertEquals(false, e.outputs().get("e"));
    }

    @Test void commandCenterServicesGetTheRequests() throws Exception {
        Path w = PackagesTest.tmp();
        Map<String, Object> globals = new LinkedHashMap<>(Map.of("threshold", 1000));
        List<Map<String, Object>> sent = new ArrayList<>();
        Function<Map<String, Object>, Map<String, Object>> gw = req -> { sent.add(req); return Map.of("value", 2500, "type", "number"); };
        String in30 = LocalDate.now().plusDays(30).toString();
        Function<Map<String, Object>, Map<String, Object>> vw = req -> {
            sent.add(req);
            return "expiry".equals(req.get("op")) ? Map.of("expires", in30, "days_left", 30, "expired", false) : Map.of("expires", in30, "days_left", 30);
        };
        Map<String, Object> svc = Map.of("global_values", (Supplier<Object>) () -> globals, "global_values_write", gw, "vault_write", vw);
        RunResult r = run(w, svc, "[{\"id\":\"1\",\"action\":\"ooa.global_values/set\",\"args\":{\"name\":\"threshold\",\"value\":2500},\"out\":{\"value\":\"v\"}},"
                + "{\"id\":\"2\",\"action\":\"ooa.global_values/get\",\"args\":{\"name\":\"threshold\"},\"out\":{\"value\":\"e\"}},"
                + "{\"id\":\"3\",\"action\":\"ooa.vault/save_credential\",\"args\":{\"credential\":\"it/app/password\",\"value\":{\"$credential\":\"it/app/password\"},\"expires_in_days\":30},\"out\":{\"days_left\":\"d\"}},"
                + "{\"id\":\"4\",\"action\":\"ooa.vault/credential_expiry\",\"args\":{\"credential\":\"it/app/password\",\"warn_days\":45},\"out\":{\"expiring_soon\":\"x\"}}]");
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals("2500", String.valueOf(r.outputs().get("v")));
        assertEquals("2500", String.valueOf(r.outputs().get("e")), "later reads in the run see the new value");
        assertEquals(30, ((Number) r.outputs().get("d")).intValue());
        assertEquals(true, r.outputs().get("x"));
        assertEquals("threshold", sent.get(0).get("name"));
        assertEquals("2500", String.valueOf(sent.get(0).get("value")));
        assertEquals(Map.of("op", "save", "ref", "it/app/password", "value", "N3w-Pass", "expires", in30), sent.get(1));
        // the value is never in the run's events
        assertFalse(JsonLite.write(r.toMap()).contains("N3w-Pass"));
        // typed text is refused, so a secret never sits in the agent
        assertEquals("VAULT.NO_SECRET", run(w, svc, "[{\"id\":\"1\",\"action\":\"ooa.vault/save_credential\",\"args\":{\"credential\":\"it/app/password\",\"value\":\"plain\"}}]").error().get("code"));
        assertEquals("VAULT.BAD_REF", run(w, svc, "[{\"id\":\"1\",\"action\":\"ooa.vault/save_credential\",\"args\":{\"credential\":\"it\",\"value\":{\"$credential\":\"it/app/password\"}}}]").error().get("code"));
        assertEquals("VAULT.BAD_DATE", run(w, svc, "[{\"id\":\"1\",\"action\":\"ooa.vault/save_credential\",\"args\":{\"credential\":\"it/app/password\",\"value\":{\"$credential\":\"it/app/password\"},\"expires\":\"next week\"}}]").error().get("code"));
        // a run that can read shared values but has no way to change them says so
        assertEquals("GLOBALS.READ_ONLY", run(w, Map.of("global_values", (Supplier<Object>) () -> globals),
                "[{\"id\":\"1\",\"action\":\"ooa.global_values/set\",\"args\":{\"name\":\"threshold\",\"value\":1}}]").error().get("code"));
    }
}
