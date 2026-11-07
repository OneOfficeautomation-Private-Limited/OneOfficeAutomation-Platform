package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** G36: suspended runs in One CommandCenter: waits, the callback URL, the clock that resumes, cancel, resume now, the upgrade. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RunWaitsTest {
    Db db; ControlRoom cr; ControlRoom.User admin;
    Map<String, Object> dev;
    static final String BOT = "Long/Approve.flow.json";

    @SuppressWarnings("unchecked")
    @BeforeAll void start() throws Exception {
        Path data = Files.createTempDirectory("ooa-waits");
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        admin = RepositoryLifecycleTest.boot(cr);
        Map<String, Object> flow = (Map<String, Object>) JsonLite.parse("""
            {"kind":"TaskBot","id":"approve","name":"approve","description":"t","variables":[
              {"name":"invoice","type":"String","direction":"input"},{"name":"note","type":"String","direction":"in_out","default":""}],
             "steps":[{"id":"w","action":"core/log","args":{"message":"wait here"}}]}""");
        cr.checkin(admin, BOT, flow, "v1", true, null);
        cr.platform.updateSettings(admin, Map.of("public_url", "https://cc.example.com/"), null);
        Map<String, Object> r = cr.registerDevice((String) cr.createRegistrationKey(admin, 1, null).get("key"), "Long-PC", "long-pc", "Windows 11", "0.2.0", null);
        dev = cr.device((String) r.get("device_id"), (String) r.get("secret"));
    }
    @AfterAll void stop() { cr.triggerPollers.stop(); db.close(); }

    String running() {
        String id = (String) cr.startRun("admin", "admin", BOT, (String) dev.get("id"), Map.of("invoice", "INV-1", "note", "first"), "manual", null).get("run_id");
        for (int i = 0; i < 50 && !"running".equals(status(id)); i++) assertNotNull(cr.claimNext(dev));   // resumed runs of other tests may be queued first
        assertEquals("running", status(id));
        return id;
    }
    Map<String, Object> suspend(String run, Map<String, Object> args) {
        Map<String, Object> a = new HashMap<>(args);
        a.put("step_id", "w");
        a.putIfAbsent("values", Map.of("note", "changed", "invoice", "INV-1"));
        return cr.runRequest(dev, run, "suspend", a);
    }
    void finishSuspended(String run) { cr.finish(dev, run, Map.of("status", "suspended", "error", Map.of("code", "RUN.SUSPENDED", "message", "x"))); }
    String status(String run) { return (String) db.one("SELECT status FROM runs WHERE id = ?", run).get("status"); }
    Map<String, Object> waitOf(String run) { return db.one("SELECT * FROM run_waits WHERE run_id = ? AND step_id IS NOT NULL", run); }
    /** Makes the clock's "now" later than the wait's time. */
    void due(String waitId) { db.update("UPDATE run_waits SET resume_at = ? WHERE id = ?", Instant.now().minusSeconds(1), waitId); }
    String token(String url) { return url.substring(url.lastIndexOf('/') + 1); }

    @Test @SuppressWarnings("unchecked")
    void aTimeWaitResumesTheSameVersionAtTheStepWithTheSavedValues() {
        String run = running();
        Map<String, Object> s = suspend(run, Map.of("kind", "time", "ref", "60"));
        assertNotNull(s.get("resume_at"));
        finishSuspended(run);
        assertEquals("suspended", status(run));
        assertEquals(0, cr.runWaits.tick(), "not due yet");
        // a newer production version must not be used by the resumed run
        int version = ((Number) db.one("SELECT version FROM runs WHERE id = ?", run).get("version")).intValue();
        cr.checkout(admin, BOT, null);
        cr.checkin(admin, BOT, (Map<String, Object>) cr.content(BOT, "production").get("flow"), "v2", true, null);
        due((String) s.get("wait_id"));
        assertEquals(1, cr.runWaits.tick());
        Map<String, Object> w = waitOf(run);
        assertEquals("resumed", w.get("status"));
        String next = (String) w.get("resumed_run_id");
        Map<String, Object> nr = db.one("SELECT * FROM runs WHERE id = ?", next);
        assertEquals("queued", nr.get("status"));
        assertEquals(version, ((Number) nr.get("version")).intValue(), "same version as the suspended run");
        assertNotEquals(version, ((Number) db.one("SELECT production_version FROM bots WHERE path = ?", BOT).get("production_version")).intValue());
        assertEquals("manual", nr.get("source"));
        Map<String, Object> inputs = (Map<String, Object>) JsonLite.parse((String) nr.get("inputs"));
        assertEquals("changed", inputs.get("note"), "an input changed before the wait keeps its new value");
        Map<String, Object> job = cr.claimNext(dev);
        assertEquals(next, job.get("run_id"));
        Map<String, Object> resume = (Map<String, Object>) job.get("resume");
        assertEquals("w", resume.get("step_id"));
        assertEquals("changed", ((Map<String, Object>) resume.get("values")).get("note"));
        assertEquals("time", ((Map<String, Object>) resume.get("answer")).get("resumed_by"));
        assertEquals(run, cr.run(admin, next, 0).get("resumed_from"), "the run's page shows where it came from");
        assertEquals(next, cr.run(admin, run, 0).get("resumed_as"));
        assertEquals(0, cr.runWaits.tick(), "resumed once");
        cr.finish(dev, next, Map.of("status", "success", "outputs", Map.of()));
        assertEquals("success", status(next));
        assertEquals(run, cr.runWaits.rootRun(next));
    }

    @Test @SuppressWarnings("unchecked")
    void aCallbackUrlResumesOnceWithItsBody() {
        String run = running();
        Map<String, Object> cb = cr.runRequest(dev, run, "create_callback", Map.of());
        String url = (String) cb.get("url");
        assertTrue(url.startsWith("https://cc.example.com/api/callbacks/"), url);
        String token = token(url);
        Map<String, Object> row = db.one("SELECT token_hash FROM run_waits WHERE id = ?", cb.get("callback_id"));
        assertNotEquals(token, row.get("token_hash"), "only the hash is kept");
        assertEquals(RunWaits.sha256(token), row.get("token_hash"));
        suspend(run, Map.of("kind", "callback", "ref", cb.get("callback_id"), "timeout_seconds", 3600));
        finishSuspended(run);
        assertEquals("CR.NOT_FOUND", assertThrows(OoaException.class, () -> cr.runWaits.callback(token + "x", new byte[0], null)).code());
        assertEquals("CR.NOT_FOUND", assertThrows(OoaException.class, () -> cr.runWaits.callback("../bad", new byte[0], null)).code());
        assertEquals("CR.TOO_LARGE", assertThrows(OoaException.class, () -> cr.runWaits.callback(token, new byte[RunWaits.MAX_CALLBACK_BODY + 1], null)).code());
        assertEquals("CR.BAD_JSON", assertThrows(OoaException.class, () -> cr.runWaits.callback(token, "{nope".getBytes(StandardCharsets.UTF_8), null)).code());
        assertEquals(true, cr.runWaits.callback(token, "{\"approved\":true,\"by\":\"erp\"}".getBytes(StandardCharsets.UTF_8), "203.0.113.9").get("accepted"));
        assertEquals("CR.CONFLICT", assertThrows(OoaException.class, () -> cr.runWaits.callback(token, "{}".getBytes(StandardCharsets.UTF_8), null)).code(), "once only");
        cr.runWaits.tick();
        Map<String, Object> w = waitOf(run);
        assertEquals("resumed", w.get("status"));
        Map<String, Object> answer = (Map<String, Object>) JsonLite.parse((String) w.get("payload"));
        assertEquals("callback", answer.get("resumed_by"));
        assertEquals(Map.of("approved", true, "by", "erp"), answer.get("result"));
        assertEquals(1, ((Number) db.one("SELECT COUNT(*) AS n FROM audit WHERE action = 'run.callback' AND object_id = ?", run).get("n")).intValue());
        assertTrue(cr.runWaits.list(admin, "all", 50).stream().anyMatch(x -> run.equals(x.get("run_id")) && x.get("ref") == null), "the console never sees the callback's id");
    }

    @Test void aCallBeforeTheRunSuspendsIsNotLostAndUnusedCallbacksEndWithTheirRun() {
        String run = running();
        Map<String, Object> cb = cr.runRequest(dev, run, "create_callback", Map.of());
        cr.runWaits.callback(token((String) cb.get("url")), "{\"early\":1}".getBytes(StandardCharsets.UTF_8), null);
        assertEquals(0, cr.runWaits.tick(), "the run is still running: nothing to resume yet");
        assertEquals(true, suspend(run, Map.of("kind", "callback", "ref", cb.get("callback_id"))).get("already"));
        finishSuspended(run);
        assertEquals(1, cr.runWaits.tick());
        String other = running();
        Map<String, Object> unused = cr.runRequest(dev, other, "create_callback", Map.of());
        cr.finish(dev, other, Map.of("status", "success"));
        assertEquals("cancelled", db.one("SELECT status FROM run_waits WHERE id = ?", unused.get("callback_id")).get("status"));
        assertEquals("CR.CONFLICT", assertThrows(OoaException.class, () -> cr.runWaits.callback(token((String) unused.get("url")), new byte[0], null)).code());
    }

    @Test @SuppressWarnings("unchecked")
    void taskQueueItemAndJobWaitsResumeWhenTheyEnd() {
        String run = running();
        String task = (String) cr.runRequest(dev, run, "create_task", Map.of("title", "Approve INV-1", "assignee", "role:admin", "data", Map.of("amount", 10))).get("task_id");
        suspend(run, Map.of("kind", "task", "ref", task));
        finishSuspended(run);
        assertEquals(0, cr.runWaits.tick());
        cr.tasks.complete(admin, task, "approved", null, "fine", null);
        assertEquals(1, cr.runWaits.tick());
        Map<String, Object> answer = (Map<String, Object>) JsonLite.parse((String) waitOf(run).get("payload"));
        assertEquals("approved", ((Map<String, Object>) answer.get("result")).get("outcome"));

        db.update("INSERT INTO queues (id, name, max_attempts, processing, created) VALUES (?,?,?,?,?)", "q_long", "Long items", 1, false, Instant.now());
        db.update("INSERT INTO queue_items (id, queue_id, data, status, priority, attempts, created, updated) VALUES ('item_long', 'q_long', '{}', 'new', 0, 0, ?, ?)", Instant.now(), Instant.now());
        String r2 = running();
        suspend(r2, Map.of("kind", "queue_item", "ref", "item_long"));
        finishSuspended(r2);
        assertEquals(0, cr.runWaits.tick());
        db.update("UPDATE queue_items SET status = 'success', result = '{\"ok\":true}' WHERE id = 'item_long'");
        assertEquals(1, cr.runWaits.tick());

        String target = running(), r3 = running();
        suspend(r3, Map.of("kind", "job", "ref", target));
        finishSuspended(r3);
        assertEquals(0, cr.runWaits.tick());
        cr.finish(dev, target, Map.of("status", "failed", "error", Map.of("code", "X", "message", "y")));
        assertEquals(1, cr.runWaits.tick());
        Map<String, Object> jobAnswer = (Map<String, Object>) JsonLite.parse((String) waitOf(r3).get("payload"));
        assertEquals("failed", ((Map<String, Object>) jobAnswer.get("result")).get("status"));
    }

    @Test @SuppressWarnings("unchecked")
    void aTimeoutResumesWithTimedOut() {
        String run = running();
        Map<String, Object> s = suspend(run, Map.of("kind", "time", "ref", "3600", "timeout_seconds", 5));
        finishSuspended(run);
        db.update("UPDATE run_waits SET timeout_at = ? WHERE id = ?", Instant.now().minusSeconds(1), s.get("wait_id"));
        assertEquals(1, cr.runWaits.tick());
        Map<String, Object> w = waitOf(run);
        assertEquals("timed_out", w.get("status"));
        Map<String, Object> answer = (Map<String, Object>) JsonLite.parse((String) w.get("payload"));
        assertEquals(true, answer.get("timed_out"));
        assertEquals("timeout", answer.get("resumed_by"));
    }

    @Test void aSuspendedEndWithoutAWaitFailsAndChecksRefuseBadWaits() {
        String run = running();
        finishSuspended(run);
        assertEquals("failed", status(run));
        assertTrue(String.valueOf(db.one("SELECT error FROM runs WHERE id = ?", run).get("error")).contains("LONGRUN.NOT_REGISTERED"));
        String r2 = running();
        assertEquals("CR.BAD_INPUT", assertThrows(OoaException.class, () -> suspend(r2, Map.of("kind", "forever"))).code());
        assertEquals("CR.NOT_FOUND", assertThrows(OoaException.class, () -> suspend(r2, Map.of("kind", "task", "ref", "task_nope"))).code());
        assertEquals("CR.BAD_INPUT", assertThrows(OoaException.class, () -> suspend(r2, Map.of("kind", "time", "ref", "999999999"))).code());
        assertEquals("CR.BAD_INPUT", assertThrows(OoaException.class, () -> suspend(r2, Map.of("kind", "job", "ref", r2))).code(), "not for itself");
        assertEquals("LONGRUN.TOO_LARGE", assertThrows(OoaException.class, () -> suspend(r2, Map.of("kind", "time", "ref", "5", "values", Map.of("big", "x".repeat(RunWaits.MAX_VALUES))))).code());
        suspend(r2, Map.of("kind", "time", "ref", "5"));
        assertEquals("CR.CONFLICT", assertThrows(OoaException.class, () -> suspend(r2, Map.of("kind", "time", "ref", "5"))).code(), "suspended once");
    }

    @Test void stopCancelsTheWaitAndResumeNowStartsTheNextRun() {
        String run = running();
        Map<String, Object> s = suspend(run, Map.of("kind", "time", "ref", "3600"));
        finishSuspended(run);
        assertTrue(cr.runWaits.list(admin, null, 50).stream().anyMatch(w -> s.get("wait_id").equals(w.get("id"))), "listed as suspended");
        cr.control(admin, run, "stop", null);
        assertEquals("stopped", status(run));
        assertEquals("cancelled", waitOf(run).get("status"));
        assertEquals("CR.CONFLICT", assertThrows(OoaException.class, () -> cr.runWaits.resumeNow(admin, (String) s.get("wait_id"), null, null)).code());

        String r2 = running();
        Map<String, Object> s2 = suspend(r2, Map.of("kind", "time", "ref", "3600"));
        finishSuspended(r2);
        String next = (String) cr.runWaits.resumeNow(admin, (String) s2.get("wait_id"), Map.of("why", "month end"), "10.0.0.1").get("run_id");
        assertEquals("queued", status(next));
        assertTrue(String.valueOf(waitOf(r2).get("payload")).contains("\"admin\""));
        assertEquals(1, ((Number) db.one("SELECT COUNT(*) AS n FROM audit WHERE action = 'run.resume' AND object_id = ? AND actor = 'admin'", next).get("n")).intValue());
        assertEquals(1, ((Number) db.one("SELECT COUNT(*) AS n FROM audit WHERE action = 'run.wait_cancel' AND object_id = ?", run).get("n")).intValue());
    }

    @Test void aDatabaseAt36GetsTheWaitsTable() throws Exception {
        try (Db old = SchemaUpgradeTest.fresh()) {
            SchemaUpgradeTest.upTo(old, 36);
            assertFalse(SchemaUpgradeTest.has(old, "SELECT 1 FROM run_waits WHERE 1 = 0"));
            old.migrate();
            assertEquals(Db.LATEST, old.schemaVersion());
            assertTrue(SchemaUpgradeTest.has(old, "SELECT id, run_id, bot_path, version, step_id, saved_values, kind, ref, token_hash, resume_at, timeout_at, status, "
                + "resumed_run_id, payload, created, created_by, run_as, device_id, pool_id, finished FROM run_waits WHERE 1 = 0"));
            assertTrue(Db.ddl("CREATE UNIQUE INDEX run_waits_token ON run_waits (token_hash) WHERE token_hash IS NOT NULL", true).contains("WHERE"), "SQL Server keeps the filter");
        }
    }
}
