package com.oneofficeautomation.conversation;

import com.oneofficeautomation.conversation.ChatEngine.ChatBot;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The conversation component on its own, against an in-memory host: no Command Center, no database. The rules hold
 * whatever the host: nothing but R0 runs without a yes, a yes is used once, R3 goes to the approvers and runs only when
 * someone approves, and a person never sees another person's chat.
 */
class ConversationTest {
    static final ChatPlatform.Person ANNA = new ChatPlatform.Person("anna", "bot_runner", "10.0.0.5");
    static final ChatPlatform.Person LEAD = new ChatPlatform.Person("lead", "admin", "10.0.0.9");

    final FakeHost host = new FakeHost();
    final Conversation chat = new Conversation(host, Clock.fixed(Instant.parse("2026-11-03T05:30:00Z"), ZoneId.of("Asia/Kolkata")));

    @Test void r2WaitsForYesAndAYesIsUsedOnce() {
        Map<String, Object> c = chat.message(ANNA, null, "post today's invoices for acme");
        assertEquals("confirm", c.get("kind"), String.valueOf(c));
        assertEquals(Map.of("vendor", "Acme", "from", "2026-11-03"), c.get("inputs"));
        assertTrue(host.runs.isEmpty());
        String sid = (String) c.get("session_id");
        assertEquals("started", chat.message(ANNA, sid, "yes").get("kind"));
        assertNotEquals("started", chat.message(ANNA, sid, "yes").get("kind"));
        assertEquals(1, host.runs.size());
        assertEquals("anna", host.runs.get(0).get("as"));
        assertTrue(host.audit.containsAll(List.of("chat.propose", "chat.confirm")), String.valueOf(host.audit));
    }

    @Test void aChatSurvivesARestartMidQuestionAndMidConfirmation() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-03T05:30:00Z"), ZoneId.of("Asia/Kolkata"));
        String sid = (String) chat.message(ANNA, null, "process vendor invoices").get("session_id");

        Conversation afterRestart = new Conversation(host, clock);
        Map<String, Object> q = afterRestart.message(ANNA, sid, "globex");
        assertEquals("question", q.get("kind"), "the half-filled inputs came back: " + q);

        Map<String, Object> c = new Conversation(host, clock).message(ANNA, sid, "3 Nov");
        assertEquals("confirm", c.get("kind"));
        assertEquals("started", new Conversation(host, clock).message(ANNA, sid, "yes").get("kind"), "the pending confirmation came back");
        assertEquals(Map.of("vendor", "Globex", "from", "2026-11-03"), host.runs.get(0).get("inputs"));
        assertEquals(8, new Conversation(host, clock).history("anna", sid).size(), "four messages and four replies");
    }

    @Test void r0RunsAtOnce() {
        assertEquals("started", chat.message(ANNA, null, "show payables aging").get("kind"));
    }

    @Test void finishedRunsAreToldInTheChatThatStartedThem() {
        Map<String, Object> r = chat.message(ANNA, null, "show payables aging");
        String sid = (String) r.get("session_id"), runId = (String) r.get("run_id");
        assertEquals(sid, host.watched.get(runId), "the host is asked to watch the run for this chat");

        chat.runFinished(sid, "anna", runId, "AP aging report", "stopped", Map.of(), null);
        chat.runFinished(sid, "someone else", runId, "AP aging report", "success", Map.of(), null);
        List<Map<String, Object>> h = chat.history("anna", sid);
        assertEquals("AP aging report (run " + runId + ") was stopped.", h.get(h.size() - 1).get("text"));
        assertEquals(3, h.size(), "an update for another person's chat is ignored");
    }

    @Test void r3RunsOnlyAfterApproval() {
        String sid = (String) chat.message(ANNA, null, "start the payment run").get("session_id");
        Map<String, Object> asked = chat.message(ANNA, sid, "yes");
        assertEquals("awaiting_approval", asked.get("kind"));
        assertTrue(host.runs.isEmpty());

        Map<String, Object> request = host.approvals.get(asked.get("task_id"));
        String runId = chat.decided((String) asked.get("task_id"), request, LEAD, true, null);
        assertNotNull(runId);
        assertEquals("anna", host.runs.get(0).get("as"), "the run is the requester's");
        assertTrue(host.notices.get(0).startsWith("anna: Approved"), String.valueOf(host.notices));
        List<Map<String, Object>> history = chat.history("anna", sid);
        assertEquals("approval_approved", history.get(history.size() - 1).get("kind"));
    }

    @Test void aRejectedR3RunsNothing() {
        String sid = (String) chat.message(ANNA, null, "start the payment run").get("session_id");
        String task = (String) chat.message(ANNA, sid, "yes").get("task_id");
        assertNull(chat.decided(task, host.approvals.get(task), LEAD, false, "not today"));
        assertTrue(host.runs.isEmpty());
    }

    @Test void aChatBelongsToOnePerson() {
        String sid = (String) chat.message(ANNA, null, "process vendor invoices").get("session_id");
        assertEquals("CR.NOT_FOUND", assertThrows(OoaException.class, () -> chat.message(LEAD, sid, "acme")).code());
    }

    @Test void hostRefusalIsPassedOn() {
        host.runnerOnline = false;
        assertEquals("no_runner", chat.message(ANNA, null, "show payables aging").get("kind"));
    }

    @Test void theModeComesFromTheHostAndAModelFailureIsReported() {
        host.settings.put("chat_mode", "llm");
        Map<String, Object> r = chat.message(ANNA, null, "post today's invoices for acme");
        assertEquals("not_understood", r.get("kind"));
        assertTrue(String.valueOf(r.get("reply")).contains("AI model"), String.valueOf(r));
    }

    /** An in-memory host with three agents: R2 invoices, R0 aging report, R3 payment run. */
    static final class FakeHost implements ChatPlatform {
        final List<ChatBot> agents = List.of(
                bot("Finance/Process invoices.flow.json", "Process vendor invoices", "R2",
                        "[{\"name\":\"vendor\",\"type\":\"String\",\"direction\":\"input\",\"required\":true,\"enum\":[\"Acme\",\"Globex\"]},"
                                + "{\"name\":\"from\",\"type\":\"DateTime\",\"direction\":\"input\",\"required\":true}]",
                        "[\"post today's invoices for acme\",\"process vendor invoices\"]"),
                bot("Finance/Aging report.flow.json", "AP aging report", "R0", "[]", "[\"show payables aging\"]"),
                bot("Finance/Payment run.flow.json", "Vendor payment run", "R3", "[]", "[\"start the payment run\"]"));
        final List<Map<String, Object>> runs = new ArrayList<>();
        final Map<String, Map<String, Object>> approvals = new HashMap<>();
        final List<String> notices = new ArrayList<>(), audit = new ArrayList<>();
        final Map<String, String> settings = new HashMap<>();
        boolean runnerOnline = true;
        int ids;

        @SuppressWarnings("unchecked")
        static ChatBot bot(String path, String name, String risk, String variables, String examples) {
            return ChatBot.of(path, (Map<String, Object>) JsonLite.parse("{\"name\":\"" + name + "\",\"variables\":" + variables
                    + ",\"ui\":{\"chat\":{\"risk\":\"" + risk + "\",\"examples\":" + examples + "}}}"));
        }

        @Override public List<ChatBot> agents(Person p) { return agents; }
        @Override public boolean canViewRuns(Person p) { return true; }

        @Override public String startRun(Person p, String path, Map<String, Object> inputs) {
            if (!runnerOnline) throw new Refused("no_runner", "No runner is online.");
            String id = newId("run");
            runs.add(Map.of("id", id, "as", p.username(), "agent", path, "inputs", inputs));
            return id;
        }

        @Override public List<Map<String, Object>> recentRuns(Person p, int limit) { return List.of(); }

        @Override public String requestApproval(Person p, ApprovalRequest r) {
            String id = newId("task");
            approvals.put(id, Map.of("requested_by", p.username(), "session_id", r.sessionId(), "agent", r.agent(), "inputs", r.inputs()));
            return id;
        }

        @Override public void notify(String username, String title, String message, Map<String, Object> data) { notices.add(username + ": " + title); }
        @Override public Person person(String username) { return new Person(username, "bot_runner", null); }
        @Override public String setting(String name) { return settings.getOrDefault(name, ""); }

        @Override public Map<String, Object> callModel(Person p, String connection, List<Map<String, Object>> m, List<Map<String, Object>> t, String purpose) {
            throw new OoaException("AI.NO_CONNECTION", "No model connection in this host");
        }

        @Override public void audit(Person p, String action, String objectType, String objectId, Map<String, Object> details) { audit.add(action); }
        @Override public String newId(String prefix) { return prefix + "_" + (++ids); }

        /** Stored as JSON text, as a database would, so only plain values survive. */
        final Map<String, String> store = new HashMap<>();

        @Override @SuppressWarnings("unchecked")
        public Map<String, Object> loadSession(String id) { return store.containsKey(id) ? (Map<String, Object>) JsonLite.parse(store.get(id)) : null; }

        @Override public void saveSession(String id, String username, Map<String, Object> data) { store.put(id, JsonLite.write(data)); }
        @Override public void deleteSessionsIdleSince(Instant cutoff) { }

        final Map<String, String> watched = new HashMap<>();
        @Override public void watchRun(String runId, String sessionId, String username) { watched.put(runId, sessionId); }
    }
}
