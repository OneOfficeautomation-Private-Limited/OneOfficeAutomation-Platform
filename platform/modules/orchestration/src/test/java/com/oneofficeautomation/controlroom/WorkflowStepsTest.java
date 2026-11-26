package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.modules.orchestration.Workflows;
import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Review round, 26 Nov 2026 (workflows, parts F and G): End with a status, decisions with else-if, Parallel branches
 * that join (or fail as the step says), Wait, Sub-workflow; approvals by several people and with a reason; who a task
 * goes to; Remind, Reassign and Skip; late tasks that escalate; and what Run > Workflow runs shows.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkflowStepsTest {
    Db db; ControlRoom cr; ControlRoom.User admin, vera; Workflows wf;

    @BeforeAll void start() throws Exception {
        Path data = Files.createTempDirectory("ooa-wf-steps");
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        admin = RepositoryLifecycleTest.boot(cr);
        String temp = (String) cr.createUser(admin, "vera", "Vera", "admin", null).get("temporary_password");
        vera = cr.session((String) cr.login("vera", temp, null).get("token"));
        wf = cr.modules.service(Workflows.class);
    }

    @AfterAll void stop() { cr.triggerPollers.stop(); db.close(); }

    @SuppressWarnings("unchecked")
    String live(String path, String json) {
        cr.checkin(admin, path, (Map<String, Object>) JsonLite.parse(json), "v1", true, null);
        return path;
    }

    Map<String, Object> run(String id) { return wf.run(admin, id, null); }
    String status(String id) { return String.valueOf(run(id).get("status")); }

    /** The open task of a run (its own or its branches'), by stage. */
    Map<String, Object> task(String stage) {
        return db.query("SELECT * FROM human_tasks WHERE status IN ('open', 'claimed') AND stage = ? ORDER BY created DESC", stage).stream().findFirst().orElseThrow(() -> new AssertionError("no open task at " + stage));
    }

    void approve(ControlRoom.User u, String stage) { cr.tasks.complete(u, (String) task(stage).get("id"), "approved", Map.of(), null, null); }

    @Test @SuppressWarnings("unchecked")
    void elseIfBranchesAndAnEndWithItsOwnStatus() {
        String p = live("WF/Route.process.json", "{\"name\":\"Route\",\"stages\":["
                + "{\"id\":\"d\",\"type\":\"decision\",\"name\":\"Amount?\",\"branches\":["
                + "  {\"name\":\"Big\",\"condition\":\"$amount$ > 100000\",\"goto\":\"big\"},"
                + "  {\"name\":\"Medium\",\"condition\":\"$amount$ > 10000\",\"goto\":\"stop\"}],\"else\":\"small\"},"
                + "{\"id\":\"big\",\"type\":\"end\",\"name\":\"Big\",\"status\":\"completed\"},"
                + "{\"id\":\"stop\",\"type\":\"end\",\"name\":\"Too much\",\"status\":\"failed\",\"message\":\"$amount$ needs a contract\"},"
                + "{\"id\":\"small\",\"type\":\"end\",\"name\":\"Small\"}]}");
        String id = (String) wf.start(admin, p, Map.of("amount", 50000), null).get("id");
        Map<String, Object> r = run(id);
        assertEquals("failed", r.get("status"));
        assertEquals("WF.ENDED_FAILED", ((Map<String, Object>) r.get("error")).get("code"));
        assertEquals("50000 needs a contract", ((Map<String, Object>) r.get("error")).get("message"));
        assertEquals("completed", status((String) wf.start(admin, p, Map.of("amount", 5), null).get("id")), "else");
        assertTrue(Workflows.validate((Map<String, Object>) JsonLite.parse("{\"stages\":[{\"id\":\"d\",\"type\":\"decision\",\"branches\":[{\"condition\":\"$a$ >\",\"goto\":\"nowhere\"}]}]}"))
                .stream().map(x -> String.valueOf(x.get("message"))).anyMatch(m -> m.contains("missing stage nowhere")));
    }

    @Test @SuppressWarnings("unchecked")
    void parallelBranchesJoinOrFailAsTheStepSays() {
        String p = live("WF/Both.process.json", "{\"name\":\"Both\",\"stages\":["
                + "{\"id\":\"par\",\"type\":\"parallel\",\"name\":\"Checks\",\"branches\":["
                + "  {\"name\":\"Legal\",\"stages\":[{\"id\":\"legal\",\"type\":\"approval\",\"name\":\"Legal ok\",\"assignee\":\"user:admin\"}]},"
                + "  {\"name\":\"Money\",\"stages\":[{\"id\":\"money\",\"type\":\"approval\",\"name\":\"Money ok\",\"assignee\":\"user:vera\"}]}]},"
                + "{\"id\":\"done\",\"type\":\"end\",\"name\":\"Done\"}]}");
        String id = (String) wf.start(admin, p, Map.of("order", "PO-1"), null).get("id");
        Map<String, Object> r = run(id);
        assertEquals("waiting", r.get("status"));
        assertEquals(2, ((List<Object>) r.get("children")).size());
        assertEquals("user:admin, user:vera", r.get("waiting_on"), "a parallel step waits on its branches' people");
        assertTrue(wf.runs(admin, null).stream().noneMatch(x -> x.get("parent_id") != null), "branches are not runs of their own in the list");
        approve(admin, "legal");
        assertEquals("waiting", status(id));
        approve(vera, "money");
        r = run(id);
        assertEquals("completed", r.get("status"));
        assertEquals(true, ((Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) r.get("data")).get("branches")).get("Money")).get("approved"));

        // one branch fails: by default the others stop at once and the workflow fails
        String q = live("WF/Stop.process.json", "{\"name\":\"Stop\",\"stages\":["
                + "{\"id\":\"par2\",\"type\":\"parallel\",\"name\":\"Checks\",\"branches\":["
                + "  {\"name\":\"Fails\",\"stages\":[{\"id\":\"bad\",\"type\":\"end\",\"status\":\"failed\"}]},"
                + "  {\"name\":\"Waits\",\"stages\":[{\"id\":\"slow\",\"type\":\"approval\",\"name\":\"Slow\",\"assignee\":\"user:admin\"}]}]}]}");
        String id2 = (String) wf.start(admin, q, Map.of(), null).get("id");
        r = run(id2);
        assertEquals("failed", r.get("status"));
        assertEquals("WF.BRANCH_FAILED", ((Map<String, Object>) r.get("error")).get("code"));
        assertTrue(((List<Map<String, Object>>) r.get("children")).stream().anyMatch(c -> "cancelled".equals(c.get("status"))), "the other branch was stopped");
        assertTrue(db.query("SELECT 1 AS x FROM human_tasks WHERE stage = 'slow' AND status = 'open'").isEmpty());
    }

    @Test
    void aWaitGoesOnWhenItsTimeComes() {
        String p = live("WF/Wait.process.json", "{\"name\":\"Wait\",\"stages\":[{\"id\":\"w\",\"type\":\"wait\",\"name\":\"A day\",\"days\":1},{\"id\":\"e\",\"type\":\"end\"}]}");
        String id = (String) wf.start(admin, p, Map.of(), null).get("id");
        Map<String, Object> r = run(id);
        assertEquals("waiting", r.get("status"));
        assertEquals("time", r.get("waiting_kind"));
        db.update("UPDATE workflow_runs SET wake_at = ? WHERE id = ?", Instant.now().minusSeconds(5), id);
        wf.tick();
        assertEquals("completed", status(id));
        String past = live("WF/Past.process.json", "{\"name\":\"Past\",\"stages\":[{\"id\":\"w\",\"type\":\"wait\",\"until\":\"2020-01-01T00:00:00Z\"}]}");
        assertEquals("completed", status((String) wf.start(admin, past, Map.of(), null).get("id")), "a time already passed does not wait");
    }

    @Test @SuppressWarnings("unchecked")
    void aSubWorkflowRunsAndHandsBackItsData() {
        live("WF/Child.process.json", "{\"name\":\"Child\",\"stages\":[{\"id\":\"ok\",\"type\":\"approval\",\"name\":\"Child ok\",\"assignee\":\"user:admin\"}]}");
        String p = live("WF/Parent.process.json", "{\"name\":\"Parent\",\"stages\":[{\"id\":\"sub\",\"type\":\"subflow\",\"name\":\"Ask the child\",\"workflow\":\"WF/Child.process.json\",\"inputs\":{\"order\":\"$order$\"}}]}");
        String id = (String) wf.start(admin, p, Map.of("order", "PO-7"), null).get("id");
        assertEquals("waiting", status(id));
        approve(admin, "ok");
        Map<String, Object> r = run(id);
        assertEquals("completed", r.get("status"));
        assertEquals("PO-7", ((Map<String, Object>) r.get("data")).get("order"));
        assertEquals(true, ((Map<String, Object>) r.get("data")).get("approved"));
    }

    @Test @SuppressWarnings("unchecked")
    void approvalsByEveryoneNamedWithAReasonForRejecting() {
        String p = live("WF/Two.process.json", "{\"name\":\"Two\",\"stages\":[{\"id\":\"two\",\"type\":\"approval\",\"name\":\"Both sign\",\"assignee\":\"user:admin,user:vera\","
                + "\"approvals_needed\":\"all\",\"reason_required\":true,\"approve_label\":\"Sign\",\"reject_label\":\"Refuse\"}]}");
        String id = (String) wf.start(admin, p, Map.of(), null).get("id");
        Map<String, Object> t = cr.tasks.tasks(admin, "open", false).stream().filter(x -> "two".equals(x.get("stage"))).findFirst().orElseThrow();
        assertEquals("Sign", t.get("approve_label"));
        assertEquals(true, t.get("reason_required"));
        assertEquals("CR.BAD_INPUT", assertThrows(OoaException.class, () -> cr.tasks.complete(admin, (String) task("two").get("id"), "rejected", Map.of(), " ", null)).code());
        approve(admin, "two");
        assertEquals("waiting", status(id), "one of two");
        assertEquals("user:vera", task("two").get("assignee"), "the task goes on to the one still to approve");
        approve(vera, "two");
        assertEquals("completed", status(id));
        // a second run: admin approves, then may not approve the step again
        String id2 = (String) wf.start(admin, p, Map.of(), null).get("id");
        approve(admin, "two");
        db.update("UPDATE human_tasks SET assignee = 'user:admin,user:vera' WHERE stage = 'two' AND status = 'open'");
        assertEquals("CR.CONFLICT", assertThrows(OoaException.class, () -> approve(admin, "two")).code());
        cr.tasks.complete(vera, (String) task("two").get("id"), "rejected", Map.of(), "Wrong supplier", null);
        assertTrue(((List<Map<String, Object>>) run(id2).get("history")).stream().anyMatch(e -> "rejected".equals(e.get("event"))), "a rejection ends it at once");
        assertNotEquals("waiting", status(id2));
    }

    @Test
    void tasksGoToTheStarterOrARandomMember() {
        String p = live("WF/Who.process.json", "{\"name\":\"Who\",\"stages\":[{\"id\":\"mine\",\"type\":\"form\",\"name\":\"Fill in\",\"form\":\"WF/F.form.json\",\"assignee\":\"starter\"},"
                + "{\"id\":\"any\",\"type\":\"approval\",\"name\":\"Anyone\",\"assignee\":\"random:role:admin\"}]}");
        live("WF/F.form.json", "{\"name\":\"F\",\"fields\":[{\"id\":\"note\",\"type\":\"text\",\"label\":\"Note\"}]}");
        wf.start(vera, p, Map.of(), null);
        assertEquals("user:vera", task("mine").get("assignee"));
        cr.tasks.complete(vera, (String) task("mine").get("id"), null, Map.of("note", "hi"), null, null);
        assertTrue(Set.of("user:admin", "user:vera").contains(String.valueOf(task("any").get("assignee"))));
        db.update("UPDATE human_tasks SET status = 'cancelled' WHERE stage = 'any'");
    }

    @Test @SuppressWarnings("unchecked")
    void operatorsRemindReassignAndSkipAndLateTasksEscalate() {
        String p = live("WF/Late.process.json", "{\"name\":\"Late\",\"stages\":["
                + "{\"id\":\"slow\",\"type\":\"approval\",\"name\":\"Slow\",\"assignee\":\"user:admin\",\"due_hours\":1,\"escalate\":{\"action\":\"reassign\",\"to\":\"user:vera\"}},"
                + "{\"id\":\"next\",\"type\":\"approval\",\"name\":\"Next\",\"assignee\":\"user:admin\"}]}");
        String id = (String) wf.start(admin, p, Map.of(), "LATE-1").get("id");
        Map<String, Object> listed = wf.runs(admin, null).stream().filter(x -> id.equals(x.get("id"))).findFirst().orElseThrow();
        assertEquals("Slow", listed.get("stage_name"));
        assertEquals("user:admin", listed.get("waiting_on"));
        assertEquals(false, listed.get("late"));
        db.update("UPDATE human_tasks SET due = ? WHERE stage = 'slow'", Instant.now().minusSeconds(60));
        assertEquals(true, run(id).get("late"));
        wf.tick();
        assertEquals("user:vera", task("slow").get("assignee"), "escalated: handed to Vera");
        assertTrue(((List<Map<String, Object>>) run(id).get("history")).stream().anyMatch(e -> "escalated".equals(e.get("event"))));
        assertEquals(1, ((Number) wf.remind(admin, id, null).get("tasks")).intValue());
        wf.reassign(admin, id, "user:admin", null);
        assertEquals("user:admin", task("slow").get("assignee"));
        assertEquals("CR.BAD_INPUT", assertThrows(OoaException.class, () -> wf.skip(admin, id, " ", null)).code());
        wf.skip(admin, id, "Approved by phone", null);
        Map<String, Object> r = run(id);
        assertEquals("Next", r.get("stage_name"));
        assertTrue(((List<Map<String, Object>>) r.get("history")).stream().anyMatch(e -> "skipped".equals(e.get("event"))));
        assertTrue(db.query("SELECT 1 AS x FROM human_tasks WHERE stage = 'slow' AND status = 'open'").isEmpty());
        wf.cancel(admin, id, null);
    }
}
