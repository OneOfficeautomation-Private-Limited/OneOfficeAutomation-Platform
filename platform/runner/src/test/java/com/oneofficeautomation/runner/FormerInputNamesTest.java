package com.oneofficeautomation.runner;

import com.oneofficeautomation.sdk.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** K8 (command review, batch 10): a renamed input keeps working under its former name. */
class FormerInputNamesTest {
    @TempDir Path ws;

    @OoaPackage(id = "acme.cal", name = "Calendar", version = "1.0.0", description = "Echoes its inputs.", publisher = "acme")
    public static final class Cal {
        @Action(id = "list", name = "List", description = "Returns what it was given.", panel = "start, base_url")
        @Output(name = "seen", type = OoaType.STRING, description = "The inputs.")
        public Map<String, Object> list(ActionContext ctx,
                @Input(name = "start", type = OoaType.STRING, formerNames = "from", description = "Start.") String start,
                @Input(name = "base_url", type = OoaType.STRING, required = false, formerNames = "endpoint_override", description = "Address.") String base) {
            return Map.of("seen", start + "|" + base);
        }
    }

    static PackageRegistry registry() {
        PackageSigning.PublisherKey first = PackageSigning.PublisherKey.generate("oneofficeautomation"), acme = PackageSigning.PublisherKey.generate("acme");
        PackageRegistry r = new PackageRegistry();
        r.trust(first); r.trust(acme);
        r.install(CorePackage.class, PackageSigning.sign(CorePackage.class, first));
        r.install(Cal.class, PackageSigning.sign(Cal.class, acme));
        return r;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> flow(String step) {
        return (Map<String, Object>) JsonLite.parse("""
                {"kind": "TaskBot", "id": "t", "name": "t", "variables": [{"name": "seen", "type": "String", "direction": "output"}],
                 "steps": [%s]}""".formatted(step));
    }

    @Test void aStepWithTheFormerNamesValidatesAndRuns() {
        Map<String, Object> f = flow("{\"id\": \"s1\", \"action\": \"acme.cal/list\", \"args\": {\"from\": \"Mon\", \"endpoint_override\": \"http://x\"}, \"out\": {\"seen\": \"seen\"}}");
        assertEquals(List.of(), new FlowValidator(registry(), FlowSchemaValidator.NONE).validate(f));
        RunResult r = Runner.builder(registry()).workDir(ws).build().run(f, Map.of());
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals("Mon|http://x", r.outputs().get("seen"));
    }

    @Test void theCurrentNameWinsAndTheManifestListsTheFormerOnes() {
        RunResult r = Runner.builder(registry()).workDir(ws).build()
                .run(flow("{\"id\": \"s1\", \"action\": \"acme.cal/list\", \"args\": {\"start\": \"Tue\", \"from\": \"Mon\"}, \"out\": {\"seen\": \"seen\"}}"), Map.of());
        assertEquals("Tue|null", r.outputs().get("seen"));
        ActionSpec spec = registry().resolve("acme.cal", null).spec("action", "list");
        assertEquals(List.of("from"), spec.input("start").orElseThrow().schema().get("x-ooa-former-names"));
    }

    @Test void aConnectionSavedUnderTheFormerNameStillFillsTheInput() throws Exception {
        Files.writeString(ws.resolve("connections.json"), "{\"cal\": {\"package\": \"acme.cal\", \"values\": {\"endpoint_override\": \"http://saved\"}}}");
        RunResult r = Runner.builder(registry()).workDir(ws).build()
                .run(flow("{\"id\": \"s1\", \"action\": \"acme.cal/list\", \"connection\": \"cal\", \"args\": {\"start\": \"Wed\"}, \"out\": {\"seen\": \"seen\"}}"), Map.of());
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals("Wed|http://saved", r.outputs().get("seen"));
    }
}
