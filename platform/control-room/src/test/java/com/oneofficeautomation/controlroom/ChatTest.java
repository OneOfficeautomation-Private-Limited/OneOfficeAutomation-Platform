package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * One Chat, deterministic mode: plain words reach the right agent, missing inputs are asked for, nothing but R0 runs
 * without the person's yes, a yes is used once, R3 is not started from chat, and people only see agents they may run.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChatTest {
    static final String INVOICES = "Finance/Process invoices.flow.json", AGING = "Finance/Aging report.flow.json", PAYMENT = "Finance/Payment run.flow.json";

    Db db; ControlRoom cr; Chat chat; ControlRoom.User admin, jane, val; String device;

    @BeforeAll void start() throws Exception {
        Path data = Files.createTempDirectory("ooa-chat");
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        admin = RepositoryLifecycleTest.boot(cr);
        jane = RepositoryLifecycleTest.person(cr, admin, "jane", "bot_runner");
        val = RepositoryLifecycleTest.person(cr, admin, "val", "viewer");
        cr.checkin(admin, INVOICES, flow("process_invoices", "Process vendor invoices", "Posts a vendor's open invoices to SAP. Not for credit notes.",
                "[{\"name\":\"vendor\",\"type\":\"String\",\"direction\":\"input\",\"required\":true,\"enum\":[\"Acme\",\"Globex\"]},"
                        + "{\"name\":\"from\",\"type\":\"DateTime\",\"direction\":\"input\",\"required\":true,\"description\":\"the earliest invoice date\"},"
                        + "{\"name\":\"max_amount\",\"type\":\"Number\",\"direction\":\"input\"}]",
                "{\"examples\":[\"post today's invoices for acme\",\"process vendor invoices\"],\"aliases\":[\"ap run\"],"
                        + "\"not_for\":[\"issue a credit note\"],\"risk\":\"R2\",\"prompts\":{\"vendor\":\"Which vendor should I process?\"}}"), "v1", true, null);
        cr.checkin(admin, AGING, flow("aging", "AP aging report", "Read-only payables aging.",
                "[{\"name\":\"company\",\"type\":\"String\",\"direction\":\"input\",\"enum\":[\"1000\",\"2000\"],\"default\":\"1000\"}]",
                "{\"examples\":[\"show payables aging\",\"ap aging report\"],\"risk\":\"R0\"}"), "v1", true, null);
        cr.checkin(admin, PAYMENT, flow("payment", "Vendor payment run", "Pays due invoices.", "[]",
                "{\"examples\":[\"start the payment run\",\"pay due vendor invoices\"],\"risk\":\"R3\"}"), "v1", true, null);
        device = (String) cr.registerDevice((String) cr.createRegistrationKey(admin, 1, null).get("key"), "PC-1", "pc-1", "Windows 11", "0.2.0", null).get("device_id");
        cr.platform.updateSettings(admin, Map.of("chat_runner", device), null);
        chat = new Chat(cr, data, Clock.fixed(Instant.parse("2026-11-03T05:30:00Z"), ZoneId.of("Asia/Kolkata")));
    }

    @AfterAll void stop() { cr.triggerPollers.stop(); db.close(); }

    @SuppressWarnings("unchecked")
    static Map<String, Object> flow(String id, String name, String description, String variables, String chat) {
        return (Map<String, Object>) JsonLite.parse("{\"kind\":\"TaskBot\",\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"description\":\"" + description + "\","
                + "\"variables\":" + variables.replaceFirst("^\\[", "[{\"name\":\"note\",\"type\":\"String\",\"direction\":\"output\"}" + (variables.equals("[]") ? "" : ",")) + ","
                + "\"steps\":[{\"id\":\"1\",\"action\":\"core/set\",\"args\":{\"variable\":\"note\",\"value\":\"ok\"}}],\"chat\":" + chat + "}");
    }

    long runs(String user) { return ((Number) db.one("SELECT COUNT(*) AS n FROM runs WHERE requested_by = ?", user).get("n")).longValue(); }

    @Test void r2AgentRunsOnlyAfterYesAndOnlyOnce() {
        Map<String, Object> r = chat.message(jane, null, "post today's invoices for acme", null);
        assertEquals("confirm", r.get("kind"), String.valueOf(r));
        assertEquals(INVOICES, r.get("agent"));
        assertEquals(Map.of("vendor", "Acme", "from", "2026-11-03"), r.get("inputs"));
        assertEquals(0, runs("jane"), "nothing runs before yes");

        String sid = (String) r.get("session_id");
        Map<String, Object> started = chat.message(jane, sid, "yes", null);
        assertEquals("started", started.get("kind"), String.valueOf(started));
        Map<String, Object> run = db.one("SELECT source, bot_path, inputs FROM runs WHERE id = ?", started.get("run_id"));
        assertEquals("chat", run.get("source"));
        assertEquals(INVOICES, run.get("bot_path"));

        assertNotEquals("started", chat.message(jane, sid, "yes", null).get("kind"), "a confirmation is used once");
        assertEquals(1, runs("jane"));
    }

    @Test void missingInputsAreAskedForInTurn() {
        Map<String, Object> q1 = chat.message(admin, null, "process vendor invoices", null);
        assertEquals("question", q1.get("kind"));
        assertEquals("Which vendor should I process? (Acme, Globex)", q1.get("reply"));
        String sid = (String) q1.get("session_id");

        Map<String, Object> q2 = chat.message(admin, sid, "globex", null);
        assertEquals("question", q2.get("kind"));
        assertTrue(String.valueOf(q2.get("reply")).contains("the earliest invoice date"), String.valueOf(q2));

        Map<String, Object> c = chat.message(admin, sid, "3 Nov", null);
        assertEquals("confirm", c.get("kind"));
        assertEquals(Map.of("vendor", "Globex", "from", "2026-11-03"), c.get("inputs"));
        assertEquals("cancelled", chat.message(admin, sid, "no", null).get("kind"));
    }

    @Test void aChatSurvivesARestartOfTheCommandCenter() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-11-03T05:30:00Z"), ZoneId.of("Asia/Kolkata"));
        Path data = Files.createTempDirectory("ooa-chat-restart");
        String sid = (String) chat.message(admin, null, "process vendor invoices", null).get("session_id");
        assertNotNull(db.one("SELECT username FROM chat_sessions WHERE id = ?", sid), "stored after the message");

        Chat restarted = new Chat(cr, data, clock);   // a new Command Center process reads the same database
        assertEquals("question", restarted.message(admin, sid, "globex", null).get("kind"));
        Map<String, Object> c = restarted.message(admin, sid, "today", null);
        assertEquals("confirm", c.get("kind"));
        assertEquals(Map.of("vendor", "Globex", "from", "2026-11-03"), c.get("inputs"));
        assertEquals("cancelled", new Chat(cr, data, clock).message(admin, sid, "no", null).get("kind"));
        assertEquals(8, new Chat(cr, data, clock).history(admin, sid).size());
    }

    @Test void theChatSaysWhenItsRunsEnd() {
        Map<String, Object> started = cr.chat.message(admin, null, "show payables aging", null);
        String sid = (String) started.get("session_id"), runId = (String) started.get("run_id");
        assertTrue(String.valueOf(started.get("reply")).contains("I'll tell you here when it finishes"));

        // the runner picks the run up and reports it finished: the chat hears at once
        db.update("UPDATE runs SET status = 'running', started = CURRENT_TIMESTAMP WHERE id = ?", runId);
        cr.finish(db.one("SELECT * FROM devices WHERE id = ?", device), runId, Map.of("status", "success", "outputs", Map.of("note", "ok", "overdue_inr", 1840000)));
        List<Map<String, Object>> h = cr.chat.history(admin, sid);
        Map<String, Object> last = h.get(h.size() - 1);
        assertEquals("run_success", last.get("kind"));
        assertEquals(runId, last.get("run_id"));
        String said = String.valueOf(last.get("text"));
        assertTrue(said.contains("finished. Results: ") && said.contains("note = ok") && said.contains("overdue_inr = 1840000"), said);

        // a run that ends another way (here: refused at dispatch) is picked up on the clock tick, and told once
        String sid2 = (String) cr.chat.message(admin, null, "show payables aging", null).get("session_id");
        String run2 = (String) cr.chat.history(admin, sid2).get(1).get("run_id");
        db.update("UPDATE runs SET status = 'failed', finished = CURRENT_TIMESTAMP, error = ? WHERE id = ?",
                JsonLite.write(Map.of("message", "SAP did not answer", "suggestedFix", "Try again in a few minutes.")), run2);
        cr.reap();
        cr.reap();
        List<Map<String, Object>> h2 = cr.chat.history(admin, sid2);
        assertEquals(1, h2.stream().filter(m -> String.valueOf(m.get("kind")).startsWith("run_")).count(), "told once: " + h2);
        assertEquals("AP aging report (run " + run2 + ") failed: SAP did not answer Try again in a few minutes.", h2.get(h2.size() - 1).get("text"));
    }

    @Test void r0AgentStartsAtOnce() {
        Map<String, Object> r = chat.message(admin, null, "show payables aging", null);
        assertEquals("started", r.get("kind"), String.valueOf(r));
        assertEquals(AGING, r.get("agent"));
    }

    @Test @SuppressWarnings("unchecked")
    void r3AgentRunsOnlyAfterSomeoneElseApproves() {
        ControlRoom.User olga = RepositoryLifecycleTest.person(cr, admin, "olga", "admin");
        long before = runs("admin");
        Map<String, Object> c = cr.chat.message(admin, null, "start the payment run", null);
        assertEquals("confirm", c.get("kind"));
        assertTrue(String.valueOf(c.get("reply")).contains("someone other than you must approve"), String.valueOf(c));
        String sid = (String) c.get("session_id");
        Map<String, Object> asked = cr.chat.message(admin, sid, "yes", null);
        assertEquals("awaiting_approval", asked.get("kind"), String.valueOf(asked));
        String task = (String) asked.get("task_id");
        assertEquals(before, runs("admin"), "nothing runs before the approval");

        assertEquals("CR.FORBIDDEN", assertThrows(OoaException.class, () -> cr.workflows.complete(admin, task, "approved", Map.of(), null, null)).code(),
                "never your own request, even as an administrator");
        Map<String, Object> done = cr.workflows.complete(olga, task, "approved", Map.of(), "checked", null);
        String runId = (String) done.get("run_id");
        assertNotNull(runId, String.valueOf(done));
        Map<String, Object> run = db.one("SELECT requested_by, source, bot_path FROM runs WHERE id = ?", runId);
        assertEquals("admin", run.get("requested_by"), "the run is the requester's, under their permissions");
        assertEquals("chat", run.get("source"));
        assertEquals(PAYMENT, run.get("bot_path"));

        List<Map<String, Object>> history = cr.chat.history(admin, sid);
        assertTrue(String.valueOf(history.get(history.size() - 1).get("text")).contains("olga approved"), String.valueOf(history));
        assertTrue(cr.workflows.tasks(admin, "open", false).stream().anyMatch(t -> String.valueOf(t.get("title")).startsWith("Approved: ")), "a notice in the requester's inbox");
        List<String> actions = db.query("SELECT action FROM audit WHERE object_id = ?", task).stream().map(a -> String.valueOf(a.get("action"))).toList();
        assertTrue(actions.containsAll(List.of("chat.approval_requested", "task.complete", "chat.approved")), String.valueOf(actions));
    }

    @Test void aRejectedR3RequestRunsNothing() {
        ControlRoom.User rita = RepositoryLifecycleTest.person(cr, admin, "rita", "admin");
        long before = runs("jane");
        String sid = (String) chat.message(jane, null, "start the payment run", null).get("session_id");
        String task = (String) chat.message(jane, sid, "yes", null).get("task_id");
        assertNotNull(task);
        assertEquals(Map.of("ok", true, "outcome", "rejected"), cr.workflows.complete(rita, task, "rejected", Map.of(), "not this week", null));
        assertEquals(before, runs("jane"));
        assertTrue(cr.workflows.tasks(jane, "open", false).stream().anyMatch(t -> String.valueOf(t.get("description")).contains("not this week")));
    }

    @Test void olderAgentsWithUiChatStillWork() {
        @SuppressWarnings("unchecked") Map<String, Object> old = (Map<String, Object>) JsonLite.parse("{\"kind\":\"TaskBot\",\"id\":\"old_style\",\"name\":\"Old style report\","
                + "\"description\":\"Read-only.\",\"variables\":[{\"name\":\"note\",\"type\":\"String\",\"direction\":\"output\"}],"
                + "\"steps\":[{\"id\":\"1\",\"action\":\"core/set\",\"args\":{\"variable\":\"note\",\"value\":\"ok\"}}],"
                + "\"ui\":{\"chat\":{\"examples\":[\"old style status report\"],\"risk\":\"R0\"}}}");
        cr.checkin(admin, "Finance/Old style.flow.json", old, "v1", true, null);
        assertEquals("started", chat.message(admin, null, "old style status report", null).get("kind"), "ui.chat still works for older agents");
    }

    @Test void perRoleModesAreChecked() {
        assertEquals("CR.BAD_INPUT", assertThrows(OoaException.class, () -> cr.platform.updateSettings(admin, Map.of("chat_mode_by_role", "nobody=llm"), null)).code());
        assertEquals("CR.BAD_INPUT", assertThrows(OoaException.class, () -> cr.platform.updateSettings(admin, Map.of("chat_mode_by_role", "admin=magic"), null)).code());
        cr.platform.updateSettings(admin, Map.of("chat_mode_by_role", "bot_runner=deterministic\nadmin=hybrid"), null);
        cr.platform.updateSettings(admin, Map.of("chat_mode_by_role", ""), null);
    }

    @Test void theApiAnswersInTheConsoleLanguage() {
        Map<String, Object> r = chat.message(admin, null, "order pizza for the team", "fr", null);
        assertTrue(String.valueOf(r.get("reply")).startsWith("Désolé, je n'ai pas compris."), String.valueOf(r));
    }

    @Test void approversSettingIsChecked() {
        assertEquals("CR.BAD_INPUT", assertThrows(OoaException.class, () -> cr.platform.updateSettings(admin, Map.of("chat_approvers", "finance team"), null)).code());
        cr.platform.updateSettings(admin, Map.of("chat_approvers", "role:admin, user:olga"), null);
        cr.platform.updateSettings(admin, Map.of("chat_approvers", "role:admin"), null);
    }

    @Test void negativeExamplesAndUnknownRequestsAreNotUnderstood() {
        assertEquals("not_understood", chat.message(admin, null, "issue a credit note for acme", null).get("kind"));
        assertEquals("not_understood", chat.message(admin, null, "order pizza for the team", null).get("kind"));
    }

    @Test void viewersSeeNoAgentsButCanAskForTheirRuns() {
        assertEquals("not_understood", chat.message(val, null, "post today's invoices for acme", null).get("kind"));
        Map<String, Object> r = chat.message(val, null, "what are my runs", null);
        assertEquals("answer", r.get("kind"));
        assertEquals("You have no runs yet.", r.get("reply"));
    }

    @Test void aChatBelongsToOnePerson() {
        String sid = (String) chat.message(admin, null, "process vendor invoices", null).get("session_id");
        assertEquals("CR.NOT_FOUND", assertThrows(OoaException.class, () -> chat.message(jane, sid, "acme", null)).code());
        assertEquals("CR.NOT_FOUND", assertThrows(OoaException.class, () -> chat.history(jane, sid)).code());
    }

    @Test @SuppressWarnings("unchecked")
    void chatOverTheApi() throws Exception {
        ControlRoomServer server = new ControlRoomServer(cr, "127.0.0.1", 0);
        server.start();
        try {
            String token = (String) cr.login("admin", "Admin-Passw0rd-2026", null).get("token");
            var http = java.net.http.HttpClient.newHttpClient();
            java.util.function.BiFunction<String, String, Map<String, Object>> call = (method, pathAndBody) -> {
                String[] pb = pathAndBody.split(" ", 2);
                var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + server.port() + "/api/" + pb[0]))
                        .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                        .method(method, pb.length > 1 ? java.net.http.HttpRequest.BodyPublishers.ofString(pb[1]) : java.net.http.HttpRequest.BodyPublishers.noBody()).build();
                try { return (Map<String, Object>) JsonLite.parse(http.send(req, java.net.http.HttpResponse.BodyHandlers.ofString()).body()); }
                catch (Exception e) { throw new RuntimeException(e); }
            };
            Map<String, Object> r = call.apply("POST", "chat {\"message\":\"show payables aging\"}");
            assertEquals("started", r.get("kind"), String.valueOf(r));
            Map<String, Object> history = call.apply("GET", "chat/" + r.get("session_id"));
            assertEquals(2, ((List<Object>) history.get("messages")).size());
            assertEquals("CR.NOT_FOUND", ((Map<String, Object>) call.apply("GET", "chat/chat_unknown").get("error")).get("code"));
        } finally { server.stop(); }
    }

    @Test void everyProposalAndConfirmationIsAudited() {
        Map<String, Object> r = chat.message(admin, null, "run the ap run for acme from yesterday", null);
        assertEquals(Map.of("vendor", "Acme", "from", "2026-11-02"), r.get("inputs"));
        chat.message(admin, (String) r.get("session_id"), "yes", null);
        List<String> actions = db.query("SELECT action FROM audit WHERE actor = ? ORDER BY seq", "admin").stream().map(a -> String.valueOf(a.get("action"))).toList();
        assertTrue(actions.contains("chat.propose") && actions.contains("chat.confirm"), String.valueOf(actions));
    }
}
