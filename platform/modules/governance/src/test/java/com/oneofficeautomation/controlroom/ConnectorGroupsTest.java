package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.modules.governance.CodeAnalysis;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.JsonLite;
import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** G83: connector groups as Power Automate's data policies: business, non-business (the rest), blocked and neutral toolkits. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConnectorGroupsTest {
    Db db; ControlRoom cr;

    @BeforeAll void start() throws Exception {
        Path data = Files.createTempDirectory("ooa-dlp");
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        RepositoryLifecycleTest.boot(cr);
    }

    @AfterAll void stop() { db.close(); }

    @SuppressWarnings("unchecked")
    Map<String, Object> policy(List<String> business, List<String> blocked) {
        Map<String, Object> p = (Map<String, Object>) JsonLite.parse(JsonLite.write(cr.modules.service(CodeAnalysis.class).policy()));
        Map<String, Object> rules = (Map<String, Object>) p.get("rules");
        for (Object r : rules.values()) ((Map<String, Object>) r).put("enabled", false);
        Map<String, Object> r = (Map<String, Object>) rules.get("connector_groups");
        r.put("enabled", true);
        ((Map<String, Object>) r.get("params")).put("business", business);
        ((Map<String, Object>) r.get("params")).put("blocked", blocked);
        return p;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> agent(String... actions) {
        List<Object> steps = new ArrayList<>();
        int i = 0;
        for (String a : actions) steps.add(Map.of("id", "s" + ++i, "action", a, "args", Map.of()));
        return (Map<String, Object>) JsonLite.parse(JsonLite.write(Map.of("kind", "TaskBot", "id", "a", "name", "a", "steps", steps)));
    }

    @Test void offByDefault() {
        assertEquals(false, ((Map<?, ?>) ((Map<?, ?>) cr.modules.service(CodeAnalysis.class).policy().get("rules")).get("connector_groups")).get("enabled"));
    }

    @Test void businessAndNonBusinessDoNotMixBlockedIsRefusedNeutralGoesWithBoth() {
        Map<String, Object> pol = policy(List.of("ooa.sap", "ooa.microsoft365", "ooa.aws*"), List.of("ooa.ftp"));
        assertTrue(cr.modules.service(CodeAnalysis.class).check(agent("ooa.sap/login", "ooa.microsoft365/send_mail", "ooa.aws_ai/detect_text", "ooa.string/trim", "core/log"), pol).isEmpty(),
                "business toolkits with neutral ones are fine");
        assertTrue(cr.modules.service(CodeAnalysis.class).check(agent("ooa.http/request", "ooa.string/trim", "core/log"), pol).isEmpty(), "non-business with neutral ones is fine");
        List<Map<String, Object>> mixed = cr.modules.service(CodeAnalysis.class).check(agent("ooa.sap/login", "core/log", "ooa.http/request"), pol);
        assertEquals(1, mixed.size(), String.valueOf(mixed));
        assertEquals("s3", mixed.get(0).get("step"));
        assertTrue(String.valueOf(mixed.get(0).get("message")).contains("ooa.sap") && String.valueOf(mixed.get(0).get("message")).contains("ooa.http"));
        List<Map<String, Object>> blocked = cr.modules.service(CodeAnalysis.class).check(agent("ooa.ftp/upload", "ooa.ftp/download"), pol);
        assertEquals(List.of("s1", "s2"), blocked.stream().map(v -> v.get("step")).toList());
        assertEquals("error", blocked.get(0).get("severity"));
    }

    @Test void groupsMatchWholeToolkitIdsOrPrefixes() {
        assertTrue(CodeAnalysis.inGroup("ooa.aws_ai/x", List.of("ooa.aws*")));
        assertFalse(CodeAnalysis.inGroup("ooa.sapling/x", List.of("ooa.sap")));
        assertTrue(CodeAnalysis.inGroup("ooa.sap/x", List.of(" ooa.sap/ ")));
    }
}
