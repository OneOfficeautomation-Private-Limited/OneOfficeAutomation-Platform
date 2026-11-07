package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.controlroom.repository.Lifecycle;
import com.oneofficeautomation.modules.ai.PromptSkills;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Prompt Skills (the ai-services module): shared and versioned centrally, and sent with the runs whose agents name them. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PromptSkillsTest {
    Db db; ControlRoom cr; ControlRoom.User admin, jane, bob;
    String device;

    interface Executable { void run() throws Exception; }
    static String code(Executable e) { return assertThrows(OoaException.class, e::run).code(); }

    @SuppressWarnings("unchecked")
    static Map<String, Object> json(String s) { return (Map<String, Object>) JsonLite.parse(s); }

    static Map<String, Object> agent(String id, String extra) {
        return json("{\"kind\":\"TaskBot\",\"id\":\"" + id + "\",\"name\":\"" + id + "\",\"description\":\"t\",\"variables\":[{\"name\":\"x\",\"type\":\"Number\"}]," + extra
                + "\"steps\":[{\"id\":\"1\",\"action\":\"core/set\",\"args\":{\"variable\":\"x\",\"value\":1}}]}");
    }

    ControlRoom.User real(String name) { return ControlRoom.user(db.one("SELECT * FROM users WHERE username = ?", name)); }

    @BeforeAll void start() throws Exception {
        Path data = Files.createTempDirectory("ooa-prompt-skills");
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        admin = RepositoryLifecycleTest.boot(cr);
        RepositoryLifecycleTest.person(cr, admin, "jane", "bot_creator");
        RepositoryLifecycleTest.person(cr, admin, "bob", "viewer");
        jane = real("jane"); bob = real("bob");
        Map<String, Object> reg = cr.registerDevice((String) cr.createRegistrationKey(admin, 1, null).get("key"), "PC-1", "pc-1", "Windows 11", "0.2.0", null);
        device = (String) reg.get("device_id");
    }

    @AfterAll void stop() { cr.triggerPollers.stop(); db.close(); }

    @SuppressWarnings("unchecked")
    Map<String, Object> claim() { return cr.claimNext(db.one("SELECT * FROM devices WHERE id = ?", device)); }

    void finishAll() { db.update("UPDATE runs SET status = 'completed', finished = CURRENT_TIMESTAMP WHERE status IN ('queued', 'running')"); }

    PromptSkills skills() { return cr.modules.service(PromptSkills.class); }

    @Test
    @SuppressWarnings("unchecked")
    void promptSkillsAreSharedVersionedAndSentWithRuns() {
        Map<String, Object> def = Map.of("prompt", "Summarise: ${text}", "output", "text", "temperature", 0.2);
        assertEquals(1, skills().save(jane, "summary", Map.of("description", "Short summary", "definition", def), null).get("version"));
        assertTrue(skills().list(bob).isEmpty(), "private until shared");
        assertEquals(2, skills().save(jane, "summary", Map.of("description", "Short summary", "definition", Map.of("prompt", "Summarise briefly: ${text}"), "shared", true, "comment", "shorter"), null).get("version"));
        assertEquals(1, skills().list(bob).size());
        Map<String, Object> s = skills().get(bob, "summary", 1);
        assertEquals("Summarise: ${text}", ((Map<String, Object>) s.get("definition")).get("prompt"));
        assertEquals(2, ((List<Object>) s.get("versions")).size());
        assertEquals("CR.FORBIDDEN", code(() -> skills().save(bob, "summary", Map.of("definition", def), null)));
        assertEquals("CR.BAD_INPUT", code(() -> skills().save(jane, "bad", Map.of("definition", Map.of("output", "text")), null)));
        assertEquals("CR.FORBIDDEN", code(() -> skills().save(jane, "tpl", Map.of("definition", def, "template", true), null)), "only administrators publish templates");
        assertEquals(3, skills().templates(jane).size(), "the built-in templates");
        assertEquals("route", skills().fromTemplate(jane, "template_route_request", "route", null).get("name"));

        // a run gets the skills its agent names
        cr.checkin(jane, "Sales/Summarise.flow.json", json("{\"kind\":\"TaskBot\",\"id\":\"summ\",\"name\":\"s\",\"variables\":[],\"steps\":[{\"id\":\"1\",\"action\":\"ooa.ai_skills/run\",\"args\":{\"skill\":\"summary\",\"values\":{}}}]}"), "v1", true, null);
        cr.startRun("bob", "bot_runner", "Sales/Summarise.flow.json", device, Map.of(), "manual", null);
        Map<String, Object> job = claim();
        Map<String, Object> skills = (Map<String, Object>) job.get("skills");
        assertEquals(Set.of("summary"), skills.keySet());
        assertEquals("Summarise briefly: ${text}", ((Map<String, Object>) skills.get("summary")).get("prompt"));
        finishAll();
        skills().delete(jane, "route", null);
        assertEquals("CR.NOT_FOUND", code(() -> skills().get(jane, "route", null)));
    }
}
