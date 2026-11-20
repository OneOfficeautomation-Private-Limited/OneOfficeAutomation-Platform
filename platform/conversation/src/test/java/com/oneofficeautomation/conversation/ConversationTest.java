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

    @Test void aGermanChatIsAnsweredInGermanAndUnderstandsJa() {
        Map<String, Object> q = chat.message(ANNA, null, "process vendor invoices", "de");
        String sid = (String) q.get("session_id");
        assertEquals("question", q.get("kind"));
        Map<String, Object> q2 = chat.message(ANNA, sid, "globex", null);
        assertTrue(String.valueOf(q2.get("reply")).startsWith("Was soll ich für "), "the language stays with the chat: " + q2);
        Map<String, Object> c = chat.message(ANNA, sid, "today", null);
        assertTrue(String.valueOf(c.get("reply")).contains("Ich führe Process vendor invoices aus mit vendor = Globex"), String.valueOf(c));
        assertTrue(String.valueOf(c.get("reply")).endsWith("Soll ich fortfahren? (ja/nein)"));
        Map<String, Object> started = chat.message(ANNA, sid, "Ja", null);
        assertEquals("started", started.get("kind"));
        assertTrue(String.valueOf(started.get("reply")).startsWith("Process vendor invoices wurde gestartet"), String.valueOf(started));

        chat.runFinished(sid, "anna", (String) started.get("run_id"), "Process vendor invoices", "failed", Map.of(), Map.of("message", "SAP antwortet nicht"));
        List<Map<String, Object>> h = chat.history("anna", sid);
        assertEquals("Process vendor invoices (Lauf " + started.get("run_id") + ") ist fehlgeschlagen: SAP antwortet nicht", h.get(h.size() - 1).get("text"));
    }

    @Test void aJapaneseNoCancels() {
        String sid = (String) chat.message(ANNA, null, "post today's invoices for acme", "ja").get("session_id");
        Map<String, Object> no = chat.message(ANNA, sid, "いいえ", null);
        assertEquals("cancelled", no.get("kind"));
        assertEquals("了解しました。Process vendor invoices は実行しません。", no.get("reply"));
        assertTrue(host.runs.isEmpty());
    }

    /** Today, yesterday and tomorrow in the person's language; never in another one ("hier" is "here" in English). */
    @Test void relativeDatesFollowTheChatLanguage() {
        String de = (String) chat.message(ANNA, null, "process vendor invoices", "de").get("session_id");
        chat.message(ANNA, de, "globex", null);
        assertEquals(Map.of("vendor", "Globex", "from", "2026-11-02"), chat.message(ANNA, de, "gestern", null).get("inputs"));

        String fr = (String) chat.message(ANNA, null, "process vendor invoices", "fr").get("session_id");
        assertEquals(Map.of("vendor", "Acme", "from", "2026-11-03"), chat.message(ANNA, fr, "acme aujourd’hui", null).get("inputs"));
        assertEquals(Map.of("vendor", "Acme", "from", "2026-11-04"), chat.message(ANNA, null, "process vendor invoices for acme mañana", "es").get("inputs"));
        assertEquals(Map.of("vendor", "Acme", "from", "2026-11-04"), chat.message(ANNA, null, "process vendor invoices for acme 明日", "ja").get("inputs"));

        Map<String, Object> en = chat.message(ANNA, null, "process vendor invoices for acme from hier", "en");
        assertEquals("question", en.get("kind"), "hier is not a date in English: " + en);
    }

    @Test void aRoleCanHaveItsOwnMode() {
        host.settings.put("chat_mode", "deterministic");
        host.settings.put("chat_mode_by_role", "bot_runner=llm\nadmin=hybrid");
        assertEquals("llm", chat.modeFor(ANNA));
        assertEquals("hybrid", chat.modeFor(LEAD));
        assertEquals("deterministic", chat.modeFor(new ChatPlatform.Person("v", "viewer", null)));
        assertEquals("not_understood", chat.message(ANNA, null, "post today's invoices for acme").get("kind"), "anna's role uses the model, which this host has not");
        host.settings.remove("chat_mode_by_role");
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

    // ---- memory and how long chats are kept (S4, 20 Nov 2026) ----

    @Test void withMemoryAcrossChatsRememberKeepsANoteInAnyConsoleLanguage() {
        chat.message(ANNA, null, "remember that my vendor is Acme");
        assertTrue(host.notes.isEmpty(), "without memory across chats it is not a note");
        host.memory = "across";
        Map<String, Object> r = chat.message(ANNA, null, "remember that my vendor is Acme");
        assertEquals("I'll remember that: my vendor is Acme", r.get("reply"));
        chat.message(ANNA, null, "merk dir, dass ich in Berlin arbeite", "de");
        chat.message(ANNA, null, "recuerda que prefiero el resumen");
        assertEquals(List.of("anna: my vendor is Acme", "anna: ich in Berlin arbeite", "anna: prefiero el resumen"), host.notes);
        assertNull(Conversation.rememberNote("post today's invoices for acme"));
        assertEquals("Acmeが取引先", Conversation.rememberNote("Acmeが取引先と覚えておいて"));
    }

    @Test void aChatIsKeptAsLongAsTheHostSaysAndListedForItsPerson() {
        String sid = (String) chat.message(ANNA, null, "show payables aging").get("session_id");
        List<Map<String, Object>> mine = chat.chats("anna");
        assertEquals(1, mine.size());
        assertEquals("show payables aging", mine.get(0).get("title"));
        assertTrue(chat.chats("lead").isEmpty(), "someone else's chat is not listed");
        Conversation later = new Conversation(host, Clock.fixed(Instant.parse("2026-11-03T15:30:00Z"), ZoneId.of("Asia/Kolkata")));
        assertTrue(later.chats("anna").isEmpty(), "unused for 10 hours: gone with the default of 8");
        host.keep = Duration.ofDays(2);
        assertEquals(1, later.chats("anna").size(), "kept when the host keeps chats for 2 days");
        assertEquals(sid, later.chats("anna").get(0).get("id"));
    }

    // ---- tools and permissions, and administration tools (S3, 20 Nov 2026) ----

    @Test void aGroupSetToNeverIsNotOfferedAndCannotBeReached() {
        host.rules.put("agents", "never");
        assertEquals("not_understood", chat.message(ANNA, null, "post today's invoices for acme").get("kind"));
        host.rules.put("read", "never");
        assertNotEquals("answer", chat.message(ANNA, null, "what are my runs").get("kind"), "my runs is hidden too");
        assertTrue(host.runs.isEmpty());
    }

    @Test @SuppressWarnings("unchecked")
    void askFirstMakesEvenAnR0AgentWaitForYesAndSaysWhy() {
        host.rules.put("agents", "ask");
        Map<String, Object> c = chat.message(ANNA, null, "show payables aging");
        assertEquals("confirm", c.get("kind"), String.valueOf(c));
        assertTrue(String.valueOf(c.get("reply")).contains("Asked first: Run agents is set to Ask first."), String.valueOf(c));
        Map<String, Object> card = (Map<String, Object>) c.get("confirmation");
        assertEquals("agent", card.get("kind"));
        assertEquals("R0", card.get("risk"));
        assertTrue(host.runs.isEmpty());
        assertEquals("started", chat.message(ANNA, (String) c.get("session_id"), "yes").get("kind"));
        host.rules.put("agents", "ask_person");
        assertTrue(String.valueOf(chat.message(ANNA, null, "show payables aging").get("reply")).contains("you asked me to confirm"));
    }

    @Test void anAdministrationLookUpAnswersAtOnceWithItsTable() {
        Map<String, Object> r = chat.message(ANNA, null, "who is disabled");
        assertEquals("answer", r.get("kind"), String.valueOf(r));
        assertEquals("2 people, 1 disabled.", r.get("reply"));
        assertEquals(List.of(Map.of("username", "jsmith", "enabled", false)), r.get("rows"));
        assertEquals("#/users", r.get("page"));
        assertEquals(List.of("admin:people as anna"), host.toolsUsed);
        host.rules.put("admin_read", "never");
        assertEquals("not_understood", chat.message(ANNA, null, "who is disabled").get("kind"));
    }

    @Test @SuppressWarnings("unchecked")
    void anAdministrationChangeWaitsForYesAndHappensOnce() {
        Map<String, Object> c = chat.message(ANNA, null, "disable jsmith");
        assertEquals("confirm", c.get("kind"), String.valueOf(c));
        assertTrue(String.valueOf(c.get("reply")).startsWith("I'll do this: Disable a person (person = jsmith)."), String.valueOf(c));
        assertEquals("Enable them again in Users.", ((Map<String, Object>) c.get("confirmation")).get("undo"));
        assertTrue(host.toolsUsed.isEmpty(), "nothing changes before the yes");
        String sid = (String) c.get("session_id");
        Map<String, Object> done = chat.message(ANNA, sid, "yes");
        assertEquals("done", done.get("kind"), String.valueOf(done));
        assertEquals("jsmith is disabled.", done.get("reply"));
        assertNotEquals("done", chat.message(ANNA, sid, "yes").get("kind"));
        assertEquals(List.of("admin:disable_person as anna"), host.toolsUsed);
        assertTrue(host.audit.contains("chat.tool"), String.valueOf(host.audit));
    }

    @Test void aHighImpactChangeHappensOnlyAfterSomeoneElseApproves() {
        Map<String, Object> c = chat.message(ANNA, null, "delete jsmith");
        assertEquals("confirm", c.get("kind"), String.valueOf(c));
        assertTrue(String.valueOf(c.get("reply")).contains("high-impact (R3) change"), String.valueOf(c));
        Map<String, Object> w = chat.message(ANNA, (String) c.get("session_id"), "yes");
        assertEquals("awaiting_approval", w.get("kind"), String.valueOf(w));
        assertTrue(host.toolsUsed.isEmpty(), "nothing is deleted before the approval");
        String task = (String) w.get("task_id");
        chat.decided(task, host.approvals.get(task), LEAD, true, null);
        assertEquals(List.of("admin:delete_person as anna"), host.toolsUsed, "done as the person who asked, after someone else approved");
        assertTrue(host.notices.get(host.notices.size() - 1).startsWith("anna:"));
    }

    @Test void aRefusedToolSaysWhy() {
        host.refuse = true;
        Map<String, Object> r = chat.message(ANNA, null, "who is disabled");
        assertEquals("refused", r.get("kind"));
        assertEquals("I couldn't do People: You may not manage users.", r.get("reply"));
    }

    /** An in-memory host with three agents: R2 invoices, R0 aging report, R3 payment run; and three administration tools. */
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
        /** S4: memory mode, notes kept, how long chats are kept. */
        String memory = "chat";
        final List<String> notes = new ArrayList<>();
        Duration keep = Duration.ofHours(8);
        @Override public String memoryMode(Person p) { return memory; }
        @Override public void remember(Person p, String note) { notes.add(p.username() + ": " + note); }
        @Override public Duration keepChats() { return keep; }
        @Override @SuppressWarnings("unchecked")
        public List<Map<String, Object>> storedChats(String username) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (String json : store.values()) { Map<String, Object> m = (Map<String, Object>) JsonLite.parse(json); if (username.equals(m.get("username"))) out.add(m); }
            return out;
        }
        /** Tool rules by group (S3); missing ones are always. */
        final Map<String, String> rules = new HashMap<>();
        final List<String> toolsUsed = new ArrayList<>();
        boolean refuse;
        final List<Tool> tools = List.of(
                new Tool(bot("admin:people", "People", "R0", "[{\"name\":\"which\",\"type\":\"String\",\"direction\":\"input\",\"enum\":[\"everyone\",\"disabled\"],\"default\":\"everyone\"}]",
                        "[\"who is disabled\",\"list the people\"]"), "admin_read", null, "#/users"),
                new Tool(bot("admin:disable_person", "Disable a person", "R2", "[{\"name\":\"person\",\"type\":\"String\",\"direction\":\"input\",\"required\":true,\"enum\":[\"jsmith\"]}]",
                        "[\"disable {person}\",\"disable a person\"]"), "admin_change", "Enable them again in Users.", "#/users"),
                new Tool(bot("admin:delete_person", "Delete a person", "R3", "[{\"name\":\"person\",\"type\":\"String\",\"direction\":\"input\",\"required\":true,\"enum\":[\"jsmith\"]}]",
                        "[\"delete {person}\",\"delete a person\"]"), "admin_high", "It cannot be undone.", "#/users"));

        @Override public String policy(Person p, String group) { return rules.getOrDefault(group, "always"); }
        @Override public List<Tool> adminTools(Person p) { return tools; }

        @Override public Map<String, Object> runTool(Person p, String path, Map<String, Object> inputs) {
            if (refuse) throw new Refused("refused", "You may not manage users.");
            toolsUsed.add(path + " as " + p.username());
            return switch (path) {
                case "admin:people" -> Map.of("text", "2 people, 1 disabled.", "rows", List.of(Map.of("username", "jsmith", "enabled", false)));
                case "admin:disable_person" -> Map.of("text", inputs.get("person") + " is disabled.");
                default -> Map.of("text", inputs.get("person") + " is deleted.");
            };
        }

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
