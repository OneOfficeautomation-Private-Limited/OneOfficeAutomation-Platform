package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** A running agent's requests (gap G32): get, list and stop other runs, and raise alerts, as the run's user. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JobsAndAlertsTest {
    Path data; Db db; ControlRoom cr;
    ControlRoom.User admin, vera;
    Map<String, Object> dev;

    interface Executable { void run() throws Exception; }
    static String code(Executable e) { return assertThrows(OoaException.class, e::run).code(); }

    @SuppressWarnings("unchecked")
    static Map<String, Object> flow(String id) {
        return (Map<String, Object>) JsonLite.parse("{\"kind\":\"TaskBot\",\"id\":\"" + id + "\",\"name\":\"" + id + "\",\"description\":\"t\",\"variables\":[],"
                + "\"steps\":[{\"id\":\"1\",\"action\":\"core/comment\",\"args\":{\"text\":\"x\"}}]}");
    }

    @BeforeAll void start() throws Exception {
        data = Files.createTempDirectory("ooa-g32");
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        admin = RepositoryLifecycleTest.boot(cr);
        vera = RepositoryLifecycleTest.person(cr, admin, "vera", "viewer");   // sees runs, may not stop them
        cr.checkin(admin, "Ops/Watch.flow.json", flow("watch"), "v1", true, null);
        cr.checkin(admin, "Ops/Work.flow.json", flow("work"), "v1", true, null);
        Map<String, Object> d = cr.registerDevice((String) cr.createRegistrationKey(admin, 1, null).get("key"), "PC-1", "pc-1", "Windows 11", "0.2.0", null);
        dev = cr.device((String) d.get("device_id"), (String) d.get("secret"));
        cr.setDefaultDevice(admin, "admin", (String) d.get("device_id"), null);
        cr.setDefaultDevice(admin, "vera", (String) d.get("device_id"), null);
    }

    @AfterAll void stop() { cr.triggerPollers.stop(); db.close(); }

    /** Starts a run of the agent as `user` and has the runner take it, so it is running. */
    String running(String path, String user) {
        String id = (String) cr.startRun("admin", "admin", path, null, null, Map.of(), "manual", null, 0, null, user, null).get("run_id");
        cr.claimNext(dev);
        return id;
    }

    String queued(String path) {
        return (String) cr.startRun("admin", "admin", path, null, null, Map.of(), "manual", null, 0, null, "admin", null).get("run_id");
    }

    @Test @SuppressWarnings("unchecked")
    void jobsAsTheRunsUser() {
        String watcher = running("Ops/Watch.flow.json", "admin");
        String other = queued("Ops/Work.flow.json");
        Map<String, Object> job = cr.runRequest(dev, watcher, "get_job", Map.of("job_id", other));
        assertEquals("queued", job.get("status"));
        assertEquals("Ops/Work.flow.json", job.get("bot_path"));
        assertFalse(job.containsKey("inputs"), "an agent sees no other run's inputs or outputs");
        List<Object> active = (List<Object>) cr.runRequest(dev, watcher, "list_jobs", Map.of("status", "active", "agent", "Ops/Work.flow.json")).get("jobs");
        assertEquals(1, active.size());
        assertEquals("JOBS.NOT_FOUND", code(() -> cr.runRequest(dev, watcher, "get_job", Map.of("job_id", "run_nope"))));
        // stopping another run; a run that has ended is reported, not stopped again; an agent never stops itself this way
        assertEquals(Boolean.TRUE, cr.runRequest(dev, watcher, "stop_job", Map.of("job_id", other)).get("stopped"));
        assertEquals("stopped", db.one("SELECT status FROM runs WHERE id = ?", other).get("status"));
        assertEquals(Boolean.FALSE, cr.runRequest(dev, watcher, "stop_job", Map.of("job_id", other)).get("stopped"));
        assertEquals("JOBS.SELF", code(() -> cr.runRequest(dev, watcher, "stop_job", Map.of("job_id", watcher))));
        // every request is audited as the agent for its user
        assertTrue(db.query("SELECT actor, action FROM audit WHERE action = 'agent.stop_job'").stream().anyMatch(a -> String.valueOf(a.get("actor")).endsWith("for admin")));
        db.update("UPDATE runs SET status = 'success' WHERE id = ?", watcher);
        // a finished run asks nothing
        assertEquals("JOBS.NOT_RUNNING", code(() -> cr.runRequest(dev, watcher, "get_job", Map.of("job_id", other))));
        // the run's user's own permissions: a viewer's run sees runs but cannot stop them
        String byVera = running("Ops/Watch.flow.json", "vera");
        String another = queued("Ops/Work.flow.json");
        assertEquals("queued", cr.runRequest(dev, byVera, "get_job", Map.of("job_id", another)).get("status"));
        assertEquals("CR.FORBIDDEN", code(() -> cr.runRequest(dev, byVera, "stop_job", Map.of("job_id", another))));
        db.update("UPDATE runs SET status = 'success' WHERE id IN (?, ?)", byVera, another);
    }

    @Test @SuppressWarnings("unchecked")
    void alertsRaisedByAnAgent() {
        String run = running("Ops/Watch.flow.json", "admin");
        // no rule takes them: listed under "Raised by an agent", sent to nobody
        List<Object> raised = (List<Object>) cr.runRequest(dev, run, "raise_alert", Map.of("message", "Invoice 4711 is over the limit", "severity", "critical", "subject", "invoice 4711")).get("alerts");
        assertEquals(1, raised.size());
        Map<String, Object> a = db.one("SELECT * FROM alerts WHERE rule_id = 'agent_raised'");
        assertEquals("critical", a.get("severity"));
        assertEquals("open", a.get("status"));
        assertEquals("Ops/Watch.flow.json", a.get("bot_path"));
        // the same subject once per run
        assertEquals(0, ((List<Object>) cr.runRequest(dev, run, "raise_alert", Map.of("message", "again", "subject", "invoice 4711")).get("alerts")).size());
        // a rule of this kind for the agent sends them to its recipients (the task inbox here)
        cr.alerts.saveRule(admin, null, Map.of("name", "Agent alerts", "kind", "agent_raised", "scope", "Ops/Watch.flow.json", "notify_to", List.of("role:admin")), null);
        raised = (List<Object>) cr.runRequest(dev, run, "raise_alert", Map.of("message", "Bank file missing")).get("alerts");
        assertEquals(1, raised.size());
        assertEquals("Agent alerts", ((Map<String, Object>) raised.get(0)).get("rule"));
        assertNotNull(db.one("SELECT id FROM human_tasks WHERE kind = 'notification' AND description = 'Bank file missing'"));
        // no message, and no more than 20 a run
        assertEquals("ALERT.NO_MESSAGE", code(() -> cr.runRequest(dev, run, "raise_alert", Map.of("message", " "))));
        for (int i = 0; i < 18; i++) cr.runRequest(dev, run, "raise_alert", Map.of("message", "item " + i));
        assertEquals("ALERT.TOO_MANY", code(() -> cr.runRequest(dev, run, "raise_alert", Map.of("message", "one too many"))));
        // the rule check every minute leaves these rules alone
        cr.alerts.checkRule(db.one("SELECT * FROM alert_rules WHERE kind = 'agent_raised'"));
        db.update("UPDATE runs SET status = 'success' WHERE id = ?", run);
        assertEquals("JOBS.BAD_CALL", code(() -> cr.runRequest(dev, running("Ops/Watch.flow.json", "admin"), "delete_everything", Map.of())));
    }
}
