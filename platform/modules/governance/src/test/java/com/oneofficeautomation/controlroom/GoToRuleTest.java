package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.modules.governance.CodeAnalysis;
import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.JsonLite;
import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Command review C8 (9 Nov 2026): Go to stays, with a hint (AA keeps its Goto for migrated bots only). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GoToRuleTest {
    Db db; ControlRoom cr;
    @BeforeAll void start() throws Exception {
        Path data = Files.createTempDirectory("ooa-goto");
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        RepositoryLifecycleTest.boot(cr);
    }
    @AfterAll void stop() { db.close(); }

    @SuppressWarnings("unchecked")
    @Test void goToIsAWarningOnByDefault() {
        CodeAnalysis ca = cr.modules.service(CodeAnalysis.class);
        Map<String, Object> rule = (Map<String, Object>) ((Map<String, Object>) ca.policy().get("rules")).get("go_to");
        assertEquals(true, rule.get("enabled"));
        assertEquals("warning", rule.get("severity"));
        Map<String, Object> agent = (Map<String, Object>) JsonLite.parse("""
            {"kind":"TaskBot","id":"g","name":"g","description":"d","steps":[{"id":"l","action":"core/label","args":{"name":"again"}},
              {"id":"j","action":"core/goto","args":{"label":"again"}}]}""");
        List<Map<String, Object>> found = ca.check(agent, ca.policy()).stream().filter(v -> "go_to".equals(v.get("rule"))).toList();
        assertEquals(1, found.size(), String.valueOf(found));
        assertEquals("j", found.get(0).get("step"));
        assertTrue(String.valueOf(found.get(0).get("message")).contains("'again'"));
    }
}
