package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.controlroom.repository.Lifecycle;
import com.oneofficeautomation.modules.chat.Chat;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.JsonLite;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * One Chat with a model (chat_mode llm and hybrid), against a stand-in model server that speaks the OpenAI-compatible
 * format (as vLLM, Ollama and LiteLLM do): the person's agents are the model's tools, a tool call becomes a proposal that
 * still waits for yes, only known inputs are kept, the call is in AI governance, and hybrid asks the model only when
 * the deterministic mode is unsure.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LlmChatTest {
    Db db; ControlRoom cr; Chat chat; ControlRoom.User admin; HttpServer model;
    final List<Map<String, Object>> requests = new CopyOnWriteArrayList<>();

    @BeforeAll void start() throws Exception {
        Path data = Files.createTempDirectory("ooa-llmchat");
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        admin = RepositoryLifecycleTest.boot(cr);
        cr.checkin(admin, ChatTest.INVOICES, ChatTest.flow("process_invoices", "Process vendor invoices", "Posts a vendor's open invoices to SAP.",
                "[{\"name\":\"vendor\",\"type\":\"String\",\"direction\":\"input\",\"required\":true,\"enum\":[\"Acme\",\"Globex\"]},"
                        + "{\"name\":\"from\",\"type\":\"DateTime\",\"direction\":\"input\",\"required\":true}]",
                "{\"examples\":[\"post today's invoices for acme\"],\"risk\":\"R2\"}"), "v1", true, null);
        String device = (String) cr.registerDevice((String) cr.createRegistrationKey(admin, 1, null).get("key"), "PC-1", "pc-1", "Windows 11", "0.2.0", null).get("device_id");

        model = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        model.createContext("/v1/chat/completions", ex -> {
            @SuppressWarnings("unchecked") Map<String, Object> body = (Map<String, Object>) JsonLite.parse(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            requests.add(body);
            @SuppressWarnings("unchecked") List<Map<String, Object>> msgs = (List<Map<String, Object>>) body.get("messages");
            String last = String.valueOf(msgs.get(msgs.size() - 1).get("content")).toLowerCase(Locale.ROOT);
            Map<String, Object> message = last.contains("acme")
                    ? Map.of("role", "assistant", "content", "", "tool_calls", List.of(Map.of("id", "call_1", "type", "function",
                        "function", Map.of("name", "Finance_Process_invoices", "arguments", "{\"vendor\":\"Acme\",\"from\":\"2026-11-02\",\"approve_all\":true}"))))
                    : last.contains("invent")
                    ? Map.of("role", "assistant", "content", "", "tool_calls", List.of(Map.of("id", "call_2", "type", "function",
                        "function", Map.of("name", "Delete_everything", "arguments", "{}"))))
                    : Map.of("role", "assistant", "content", "I can run your invoice agent. Which vendor do you mean?");
            byte[] out = JsonLite.write(Map.of("choices", List.of(Map.of("message", message)), "usage", Map.of("prompt_tokens", 120, "completion_tokens", 15))).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        model.start();
        cr.platform.saveModelConnection(admin, Map.of("name", "local", "provider", "openai_compatible", "model", "stub-model",
                "base_url", "http://127.0.0.1:" + model.getAddress().getPort() + "/v1"), null);
        cr.platform.updateSettings(admin, Map.of("chat_runner", device, "chat_mode", "llm", "chat_connection", "local"), null);
        chat = new Chat(cr.modules.contextFor("one-chat"), data, Clock.fixed(Instant.parse("2026-11-03T05:30:00Z"), ZoneId.of("Asia/Kolkata")));
    }

    @AfterAll void stop() { model.stop(0); cr.triggerPollers.stop(); db.close(); }

    @AfterEach void llmMode() { cr.platform.updateSettings(admin, Map.of("chat_mode", "llm"), null); }

    @Test @SuppressWarnings("unchecked")
    void aToolCallBecomesAProposalThatWaitsForYes() {
        int before = requests.size();
        Map<String, Object> r = chat.message(admin, null, "post the acme invoices from yesterday please", null);
        assertEquals("confirm", r.get("kind"), String.valueOf(r));
        assertEquals(Map.of("vendor", "Acme", "from", "2026-11-02"), r.get("inputs"), "inputs the agent does not have are dropped");

        Map<String, Object> sent = requests.get(before);
        List<Map<String, Object>> tools = (List<Map<String, Object>>) sent.get("tools");
        assertEquals("Finance_Process_invoices", ((Map<String, Object>) tools.get(0).get("function")).get("name"));
        String system = String.valueOf(((List<Map<String, Object>>) sent.get("messages")).get(0).get("content"));
        assertTrue(system.contains("Today's date: 2026-11-03"), system);

        assertEquals("started", chat.message(admin, (String) r.get("session_id"), "yes", null).get("kind"));
        Map<String, Object> ai = db.one("SELECT bot_path, requested_by, provider, model, input_tokens FROM ai_calls WHERE requested_by = ? ORDER BY ts DESC LIMIT ?", "admin", 1);
        assertEquals("One Chat", ai.get("bot_path"));
        assertEquals("stub-model", ai.get("model"));
        assertEquals(120, ((Number) ai.get("input_tokens")).intValue());
    }

    @Test void theModelsQuestionIsAskedBack() {
        Map<String, Object> r = chat.message(admin, null, "can you post some invoices", null);
        assertEquals("question", r.get("kind"));
        assertEquals("I can run your invoice agent. Which vendor do you mean?", r.get("reply"));
    }

    @Test void anInventedToolIsNotRun() {
        assertEquals("not_understood", chat.message(admin, null, "invent something", null).get("kind"));
    }

    @Test void hybridAsksTheModelOnlyWhenTheDeterministicModeIsUnsure() {
        cr.platform.updateSettings(admin, Map.of("chat_mode", "hybrid"), null);
        int before = requests.size();
        assertEquals("confirm", chat.message(admin, null, "post today's invoices for acme", null).get("kind"));
        assertEquals(before, requests.size(), "a clear request needs no model call");
        assertEquals("question", chat.message(admin, null, "help me with the supplier bills", null).get("kind"));
        assertEquals(before + 1, requests.size(), "one the deterministic mode cannot place goes to the model");
    }

    @Test void anUnknownConnectionIsReportedNotHidden() {
        cr.platform.updateSettings(admin, Map.of("chat_connection", "missing"), null);
        try {
            Map<String, Object> r = chat.message(admin, null, "post the acme invoices", null);
            assertEquals("not_understood", r.get("kind"));
            assertTrue(String.valueOf(r.get("reply")).contains("not available"), String.valueOf(r));
        } finally { cr.platform.updateSettings(admin, Map.of("chat_connection", "local"), null); }
    }
}
