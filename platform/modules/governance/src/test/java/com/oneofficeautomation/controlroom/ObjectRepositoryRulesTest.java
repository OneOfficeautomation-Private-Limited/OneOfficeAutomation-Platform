package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.controlroom.repository.Lifecycle;
import com.oneofficeautomation.modules.governance.CodeAnalysis;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * G42 in One CommandCenter: a run gets the object repositories its agent uses without listing them, and two code
 * analysis rules (off by default) keep captured objects in repositories and screen steps in object agents.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ObjectRepositoryRulesTest {
    Db db; ControlRoom cr; ControlRoom.User admin, jane;

    @BeforeAll void start() throws Exception {
        Path data = Files.createTempDirectory("ooa-objrepo");
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        admin = RepositoryLifecycleTest.boot(cr);
        jane = RepositoryLifecycleTest.person(cr, admin, "jane", "bot_creator");
    }

    @AfterAll void stop() { db.close(); }

    @SuppressWarnings("unchecked")
    static Map<String, Object> flow(String json) { return (Map<String, Object>) JsonLite.parse(json); }

    static final Map<String, Object> PROCESS = flow("""
            {"kind": "TaskBot", "id": "p", "name": "p", "tags": ["finance"], "steps": [
              {"id": "s1", "action": "ooa.browser/object_action", "args": {"object": {"repository": "Shared/SAP.objects.json", "element": "SAP/Login/User"}}},
              {"id": "s2", "action": "ooa.desktop_ui/object_action", "args": {"object": {"technology": "desktop", "properties": []}}},
              {"id": "s3", "action": "core/log", "args": {"message": "x"}}],
             "subflows": [{"name": "More", "steps": [{"id": "s4", "action": "ooa.browser/object_action", "args": {"object": {"repository": "Other.objects.json", "element": "a/b/c"}}}]}]}""");

    @SuppressWarnings("unchecked")
    Map<String, Object> policyWith(String... rules) {
        Map<String, Object> p = (Map<String, Object>) JsonLite.parse(JsonLite.write(cr.modules.service(CodeAnalysis.class).policy()));
        Map<String, Object> all = (Map<String, Object>) p.get("rules");
        for (String id : all.keySet()) ((Map<String, Object>) all.get(id)).put("enabled", false);
        for (String id : rules) ((Map<String, Object>) all.get(id)).put("enabled", true);
        return p;
    }

    static List<String> stepsOf(List<Map<String, Object>> violations, String rule) {
        return violations.stream().filter(v -> rule.equals(v.get("rule"))).map(v -> String.valueOf(v.get("step"))).toList();
    }

    @Test void rulesAreOffByDefaultAndListed() {
        @SuppressWarnings("unchecked")
        Map<String, Object> rules = (Map<String, Object>) cr.modules.service(CodeAnalysis.class).policy().get("rules");
        for (String id : List.of("repository_objects", "ui_in_object_agents")) {
            assertTrue(rules.containsKey(id), id);
            assertEquals(false, ((Map<?, ?>) rules.get(id)).get("enabled"), id + " is off until an administrator turns it on");
        }
        assertTrue(stepsOf(cr.modules.service(CodeAnalysis.class).check(PROCESS), "repository_objects").isEmpty());
    }

    @Test void capturedObjectsMustComeFromARepository() {
        List<Map<String, Object>> v = cr.modules.service(CodeAnalysis.class).check(PROCESS, policyWith("repository_objects"));
        assertEquals(List.of("s2"), stepsOf(v, "repository_objects"));
        assertEquals("error", v.get(0).get("severity"));
    }

    @Test void screenStepsOnlyInObjectAgents() {
        assertEquals(List.of("s1", "s2", "s4"), stepsOf(cr.modules.service(CodeAnalysis.class).check(PROCESS, policyWith("ui_in_object_agents")), "ui_in_object_agents"));
        Map<String, Object> objectAgent = new LinkedHashMap<>(PROCESS);
        objectAgent.put("tags", List.of("Object-Agent"));
        assertTrue(cr.modules.service(CodeAnalysis.class).check(objectAgent, policyWith("ui_in_object_agents")).isEmpty(), "the tag is matched without regard to case");
    }

    @Test void aRunGetsTheRepositoriesItsAgentUses() {
        cr.repoFiles.put(jane, "Shared/SAP.objects.json", "{\"kind\": \"ObjectRepository\", \"applications\": []}".getBytes(StandardCharsets.UTF_8), "application/json", null);
        cr.repoFiles.put(jane, "Other.objects.json", "{\"kind\": \"ObjectRepository\", \"applications\": []}".getBytes(StandardCharsets.UTF_8), "application/json", null);
        Map<String, Object> files = cr.repoFiles.forJob(PROCESS, "jane", "bot_creator");
        assertEquals(Set.of("Shared/SAP.objects.json", "Other.objects.json"), files.keySet());
        Map<String, Object> missing = new LinkedHashMap<>(PROCESS);
        missing.put("subflows", List.of(Map.of("name", "X", "steps", List.of(Map.of("id", "z", "action", "ooa.browser/object_action",
                "args", Map.of("object", Map.of("repository", "Gone.objects.json", "element", "a/b/c")))))));
        assertThrows(OoaException.class, () -> cr.repoFiles.forJob(missing, "jane", "bot_creator"), "a repository that is not there stops the run before it starts");
    }
}
