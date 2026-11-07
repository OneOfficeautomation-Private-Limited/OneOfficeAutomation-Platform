package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.controlroom.repository.Lifecycle;
import com.oneofficeautomation.modules.lowcode.AppEmbeds;
import com.oneofficeautomation.modules.lowcode.Apps;
import com.oneofficeautomation.modules.lowcode.DataStore;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** G67: apps made of pages and buttons, used by people without run rights, acting with the publisher's rights. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AppsTest {
    Db db; ControlRoom cr; ControlRoom.User admin, vic, cara;
    String devId;

    @BeforeAll @SuppressWarnings("unchecked") void start() throws Exception {
        Path data = Files.createTempDirectory("ooa-apps");
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        admin = RepositoryLifecycleTest.boot(cr);
        cr.createUser(admin, "vic", "Vic Viewer", "viewer", null);
        cr.createUser(admin, "cara", "Cara Creator", "bot_creator", null);
        vic = new ControlRoom.User("vic", "vic", "viewer", "Vic Viewer", false);
        cara = new ControlRoom.User("cara", "cara", "bot_creator", "Cara Creator", false);
        Map<String, Object> agent = new LinkedHashMap<>(LicensingAndChecksTest.agent("refund"));
        agent.put("variables", List.of(Map.of("name", "customer", "type", "String", "direction", "input")));
        cr.checkin(admin, "Finance/Refund.flow.json", agent, "v1", true, null);
        Map<String, Object> r = cr.registerDevice((String) cr.createRegistrationKey(admin, 1, null).get("key"), "App-PC", "app-pc", "Windows 11", "0.2.0", null);
        devId = (String) r.get("device_id");
        cr.modules.service(DataStore.class).saveEntity(admin, null, Map.of("name", "Requests", "fields", List.of(Map.of("name", "customer", "type", "text", "required", true),
            Map.of("name", "amount", "type", "number"))), null);
    }
    @AfterAll void stop() { cr.triggerPollers.stop(); db.close(); }

    static Map<String, Object> comp(String id, String kind, Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>(Map.of("id", id, "kind", kind));
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    Map<String, Object> definition(String agentPath) {
        return Map.of("pages", List.of(Map.of("id", "main", "title", "Refunds", "components", List.of(
            comp("h", "heading", "text", "Ask for a refund"),
            comp("c", "input", "name", "customer", "type", "text", "required", true),
            comp("a", "input", "name", "amount", "type", "number"),
            comp("save", "button", "label", "Save request", "action", Map.of("kind", "create_record", "entity", "Requests",
                "values", Map.of("customer", "{customer}", "amount", "{amount}"), "success", "Saved for {customer}.")),
            comp("run", "button", "label", "Refund now", "action", Map.of("kind", "run_agent", "agent", agentPath, "runner", devId,
                "inputs", Map.of("customer", "{customer}"))),
            comp("list", "records", "entity", "Requests"),
            comp("next", "button", "label", "More", "action", Map.of("kind", "go_page", "page", "more")),
            comp("r", "result"))),
            Map.of("id", "more", "title", "More", "components", List.of(comp("t", "text", "text", "Thanks")))));
    }

    @Test @SuppressWarnings("unchecked")
    void aViewerUsesAnAppThatActsWithItsPublishersRights() {
        assertEquals("CR.FORBIDDEN", assertThrows(OoaException.class, () -> cr.modules.service(Apps.class).save(vic, null, Map.of("name", "X", "definition", definition("Finance/Refund.flow.json")), null)).code());
        Map<String, Object> app = cr.modules.service(Apps.class).save(cara, null, Map.of("name", "Refunds", "description", "Ask for refunds", "definition", definition("Finance/Refund.flow.json")), "127.0.0.1");
        String id = (String) app.get("id");
        assertEquals("cara", app.get("published_by"));
        assertEquals(1, cr.modules.service(Apps.class).list(vic).size(), "everyone signed in when no roles are ticked");

        Map<String, Object> saved = cr.modules.service(Apps.class).act(vic, id, Map.of("component", "save", "fields", Map.of("customer", "ACME", "amount", "120.5")), "127.0.0.1");
        assertEquals("Saved for ACME.", saved.get("message"));
        Map<String, Object> rec = cr.modules.service(DataStore.class).record(cr.modules.service(DataStore.class).entity("Requests"), (String) saved.get("record_id"));
        assertEquals("app:Refunds for vic", rec.get("created_by"));
        assertEquals("120.5", String.valueOf(((Map<String, Object>) rec.get("data")).get("amount")));
        assertEquals(1, cr.modules.service(Apps.class).records(vic, id, "list", null).get("total"));

        Map<String, Object> run = cr.modules.service(Apps.class).act(vic, id, Map.of("component", "run", "fields", Map.of("customer", "ACME")), null);
        String runId = (String) run.get("run_id");
        Map<String, Object> row = db.one("SELECT requested_by, source, inputs FROM runs WHERE id = ?", runId);
        assertEquals("cara", row.get("requested_by"), "the run is the publisher's");
        assertEquals("app:" + id, row.get("source"));
        assertTrue(String.valueOf(row.get("inputs")).contains("ACME"));
        assertEquals("queued", cr.modules.service(Apps.class).runStatus(vic, id, runId).get("status"));
        assertEquals("CR.NOT_FOUND", assertThrows(OoaException.class, () -> cr.modules.service(Apps.class).runStatus(cara, id, runId)).code(), "only the person who started it follows it");

        assertEquals("more", cr.modules.service(Apps.class).act(vic, id, Map.of("component", "next"), null).get("page"));
        assertEquals("CR.BAD_INPUT", assertThrows(OoaException.class, () -> cr.modules.service(Apps.class).act(vic, id, Map.of("component", "c"), null)).code());
        assertTrue(cr.count("SELECT COUNT(*) AS n FROM audit WHERE action = 'app.act' AND actor = 'app:Refunds for vic'") >= 2);

        // an audience of roles keeps others out
        cr.modules.service(Apps.class).save(cara, id, Map.of("name", "Refunds", "audience", List.of("bot_runner"), "definition", definition("Finance/Refund.flow.json")), null);
        assertEquals(0, cr.modules.service(Apps.class).list(vic).size());
        assertEquals("CR.FORBIDDEN", assertThrows(OoaException.class, () -> cr.modules.service(Apps.class).act(vic, id, Map.of("component", "save", "fields", Map.of("customer", "B")), null)).code());

        // a publisher who is switched off stops the app working
        cr.modules.service(Apps.class).save(cara, id, Map.of("name", "Refunds", "definition", definition("Finance/Refund.flow.json")), null);
        db.update("UPDATE users SET enabled = FALSE WHERE username = 'cara'");
        assertEquals("APP.PUBLISHER_GONE", assertThrows(OoaException.class, () -> cr.modules.service(Apps.class).act(vic, id, Map.of("component", "save", "fields", Map.of("customer", "C")), null)).code());
        db.update("UPDATE users SET enabled = TRUE WHERE username = 'cara'");
        cr.modules.service(Apps.class).delete(cara, id, null);
    }

    @Test void savingChecksTheAppAndThePublishersRights() {
        assertEquals("CR.BAD_INPUT", assertThrows(OoaException.class, () -> cr.modules.service(Apps.class).save(cara, null, Map.of("name", "Bad", "definition", Map.of("pages", List.of(Map.of("id", "p",
            "components", List.of(comp("b", "button", "label", "Go", "action", Map.of("kind", "create_record", "entity", "Requests", "values", Map.of("customer", "{nobody}")))))))), null)).code(),
            "a {field} that is not on the page");
        assertEquals("CR.BAD_INPUT", assertThrows(OoaException.class, () -> cr.modules.service(Apps.class).save(cara, null, Map.of("name", "Bad", "definition", Map.of("pages", List.of(Map.of("id", "p",
            "components", List.of(comp("b", "button", "label", "Go", "action", Map.of("kind", "go_page", "page", "nowhere"))))))), null)).code());
        assertEquals("CR.BAD_INPUT", assertThrows(OoaException.class, () -> cr.modules.service(Apps.class).save(cara, null, Map.of("name", "Bad", "definition", definition("Finance/None.flow.json")), null)).code(),
            "an agent with no production version");
        assertEquals(Map.of("x", 5, "y", "Hi ACME"), Apps.fill(Map.of("x", "{n}", "y", "Hi {c}"), Map.of("n", 5, "c", "ACME")));
    }
}
