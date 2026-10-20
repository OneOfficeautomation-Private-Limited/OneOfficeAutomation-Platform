package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.*;
import com.oneofficeautomation.sdk.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;

import static com.oneofficeautomation.packages.ConnectorsTest.*;
import static com.oneofficeautomation.packages.PackagesTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Generative AI, AI Skills, AI Agents (against simulated model APIs), work queues, insights and RSA. */
class AiAndWorkTest {
    static final SecureString KEY = new SecureString("sk-test", "k");

    @SuppressWarnings("unchecked")
    @Test void openAiPromptMasksPiiAndRestoresIt() throws Exception {
        try (Fake f = new Fake()) {
            f.on("POST /v1/chat/completions", c -> {
                Map<String, Object> body = (Map<String, Object>) JsonLite.parse(c.text());
                String user = String.valueOf(((Map<?, ?>) ((List<?>) body.get("messages")).get(1)).get("content"));
                String token = user.replaceAll(".*(<EMAIL_[0-9a-f]{6}>).*", "$1");
                return ok("{\"choices\":[{\"message\":{\"content\":\"Reply sent to " + token + "\"}}],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":5}}");
            });
            Path w = tmp();
            var ctx = PackagesTestSupport.ctx(w, Map.of());
            Map<String, Object> r = new GenAiPackage().prompt(ctx, "Write to jane.doe@example.com about her refund", "Be brief", "openai", null, "gpt-test", KEY, f.url() + "/v1", 0, 100, true, null, null);
            Call call = f.last("POST", "/v1/chat/completions");
            assertFalse(call.text().contains("jane.doe@example.com"), "the model never sees the email address");
            assertEquals("Bearer sk-test", call.auth());
            assertEquals("Reply sent to jane.doe@example.com", r.get("text"), "masked value restored in the answer");
            String gov = Files.readString(w.resolve("ai-governance.jsonl"));
            assertTrue(gov.contains("\"input_tokens\":12") && gov.contains("\"EMAIL\":1") && !gov.contains("jane.doe"), gov);
        }
    }

    @Test void classifyRejectsLabelsOutsideTheList() throws Exception {
        try (Fake f = new Fake()) {
            f.on("POST /v1/chat/completions", c -> ok(c.text().contains("complaint")
                    ? "{\"choices\":[{\"message\":{\"content\":\"```json\\n{\\\"label\\\": \\\"Invoice\\\", \\\"reason\\\": \\\"mentions amount due\\\"}\\n```\"}}]}"
                    : "{\"choices\":[{\"message\":{\"content\":\"{\\\"label\\\": \\\"spam\\\"}\"}}]}"));
            var ctx = PackagesTestSupport.ctx(tmp(), Map.of());
            Map<String, Object> r = new GenAiPackage().classify(ctx, "Amount due 120", List.of("invoice", "complaint"), "openai", null, "m", KEY, f.url() + "/v1", true, null, null);
            assertEquals("invoice", r.get("label"), "case-insensitive match to an allowed label");
            var e = assertThrows(ActionException.class, () -> new GenAiPackage().classify(ctx, "x", List.of("a", "b"), "openai", null, "m", KEY, f.url() + "/v1", true, null, null));
            assertEquals("AI.LABEL_NOT_ALLOWED", e.code());
        }
    }

    @SuppressWarnings("unchecked")
    @Test void anthropicFormatAndSkills() throws Exception {
        try (Fake f = new Fake()) {
            f.on("POST /v1/messages", c -> {
                assertEquals("2023-06-01", "2023-06-01");
                Map<String, Object> b = (Map<String, Object>) JsonLite.parse(c.text());
                String sys = String.valueOf(b.get("system"));
                return ok("{\"content\":[{\"type\":\"text\",\"text\":\"" + (sys.contains("JSON") ? "{\\\"priority\\\":\\\"high\\\"}" : "Summary OK") + "\"}],\"usage\":{\"input_tokens\":9,\"output_tokens\":3}}");
            });
            Path w = tmp();
            Files.createDirectories(w.resolve("ai-skills"));
            Files.writeString(w.resolve("ai-skills/triage.json"), "{\"description\":\"Triage a ticket\",\"system\":\"Rate priority. Reply as JSON.\",\"prompt\":\"Ticket: ${text}\",\"provider\":\"anthropic\",\"model\":\"claude-test\",\"output\":\"json\"}");
            var ctx = PackagesTestSupport.ctx(w, Map.of());
            Map<String, Object> s = new GenAiPackage().summarize(ctx, "long text", 2, "anthropic", null, "claude-test", KEY, f.url(), true, null, null);
            assertEquals("Summary OK", s.get("summary"));
            Map<String, Object> r = new AiSkillsPackage().run(ctx, "triage", Map.of("text", "Server down"), null, KEY, null, f.url(), null, null);
            assertEquals("high", ((Map<?, ?>) r.get("json")).get("priority"));
            assertTrue(f.last("POST", "/v1/messages").text().contains("Ticket: Server down"));
            Map<String, Object> list = new AiSkillsPackage().list(ctx);
            assertEquals(1, ((List<?>) ((Map<?, ?>) list.get("skills")).get("rows")).size());
            var bad = assertThrows(ActionException.class, () -> new AiSkillsPackage().run(ctx, "triage", Map.of("text", new SecureString("x", "y")), null, KEY, null, f.url(), null, null));
            assertEquals("AI.SECRET_IN_PROMPT", bad.code());
        }
    }

    @SuppressWarnings("unchecked")
    @Test void agentCallsBotsAsToolsWithGuardrails() throws Exception {
        Map<String, Object> lookup = (Map<String, Object>) JsonLite.parse("""
            {"kind":"ApiTask","id":"lookup_order","name":"Look up order","description":"Find an order by id.",
             "variables":[{"name":"order_id","type":"String","direction":"input","required":true},{"name":"total","type":"Number","direction":"output"}],
             "steps":[{"id":"s","action":"core/set","args":{"variable":"total","value":129.99}}]}""");
        AutomationRepository repo = (id, v) -> { if (id.equals("lookup_order")) return lookup; throw new OoaException("REPO.NOT_FOUND", id); };
        List<String> invoked = new ArrayList<>();
        BotInvoker inv = (id, inputs) -> { invoked.add(id + inputs); return Runner.builder(REG).build().run(repo.get(id, "production"), inputs); };
        int[] turn = {0};
        try (Fake f = new Fake()) {
            f.on("POST /v1/chat/completions", c -> {
                turn[0]++;
                if (turn[0] == 1) return ok("{\"choices\":[{\"message\":{\"content\":null,\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"delete_everything\",\"arguments\":\"{}\"}}]}}]}");
                if (turn[0] == 2) return ok("{\"choices\":[{\"message\":{\"content\":\"Checking\",\"tool_calls\":[{\"id\":\"c2\",\"type\":\"function\",\"function\":{\"name\":\"lookup_order\",\"arguments\":\"{\\\"order_id\\\":\\\"ORD-1\\\"}\"}}]}}],\"usage\":{\"prompt_tokens\":50,\"completion_tokens\":10}}");
                assertTrue(c.text().contains("129.99"), "tool result was sent back to the model");
                return ok("{\"choices\":[{\"message\":{\"content\":\"Order ORD-1 totals 129.99.\"}}],\"usage\":{\"prompt_tokens\":80,\"completion_tokens\":8}}");
            });
            var ctx = PackagesTestSupport.ctx(tmp(), Map.of("repository", repo, "bot_invoker", inv));
            Map<String, Object> r = new AiAgentPackage().run(ctx, "What is the total of ORD-1?", "Be accurate.", List.of("lookup_order"), null,
                    "openai", null, "gpt-test", KEY, f.url() + "/v1", 6, 60000, true, null, null);
            assertEquals("completed", r.get("status"));
            assertEquals("Order ORD-1 totals 129.99.", r.get("answer"));
            assertEquals(List.of("lookup_order{order_id=ORD-1}"), invoked, "only the allowed bot ran, with the model's arguments");
            assertTrue(String.valueOf(r.get("trace")).contains("rejected unknown tool delete_everything"));
            assertEquals(0, new BigDecimal(148).compareTo((BigDecimal) r.get("tokens")));
        }
        try (Fake f = new Fake()) {  // sensitive tool on a headless device is denied, never run
            int[] t2 = {0};
            f.on("POST /v1/chat/completions", c -> ++t2[0] == 1
                    ? ok("{\"choices\":[{\"message\":{\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"lookup_order\",\"arguments\":\"{\\\"order_id\\\":\\\"X\\\"}\"}}]}}]}")
                    : ok("{\"choices\":[{\"message\":{\"content\":\"I could not proceed without approval.\"}}]}"));
            invoked.clear();
            System.setProperty("java.awt.headless", "true"); System.setProperty("ooa.approval.timeoutSeconds", "0"); // nobody answers: denied at once, even on a desktop
            var ctx = PackagesTestSupport.ctx(tmp(), Map.of("repository", repo, "bot_invoker", inv));
            Map<String, Object> r = new AiAgentPackage().run(ctx, "Refund X", "x", List.of("lookup_order"), List.of("lookup_order"), "openai", null, "m", KEY, f.url() + "/v1", 4, 60000, true, null, null);
            assertTrue(invoked.isEmpty());
            assertTrue(String.valueOf(r.get("trace")).contains("denied"));
        }
    }

    @Test void workQueueRetriesAndCounts() throws Exception {
        Path w = tmp();
        var q = new WorkloadPackage();
        var ctx = PackagesTestSupport.ctx(w, Map.of());
        q.addItems(ctx, "inv", Values.table(List.of("invoice", "amount"), List.of(List.of("INV-1", 10), List.of("INV-2", 20))));
        Map<String, Object> a = q.getNext(ctx, "inv");
        assertEquals("INV-1", ((Map<?, ?>) a.get("data")).get("invoice"));
        assertEquals(true, q.fail(ctx, "inv", (String) a.get("item_id"), "portal down", 2).get("will_retry"));
        Map<String, Object> b = q.getNext(ctx, "inv");
        assertEquals("INV-1", ((Map<?, ?>) b.get("data")).get("invoice"), "failed item comes back first");
        eqNum("2", b.get("attempt"));
        assertEquals(false, q.fail(ctx, "inv", (String) b.get("item_id"), "still down", 2).get("will_retry"));
        Map<String, Object> c = q.getNext(ctx, "inv");
        q.complete(ctx, "inv", (String) c.get("item_id"), Map.of("posted", true));
        assertEquals(false, q.getNext(ctx, "inv").get("found"));
        Map<?, ?> counts = (Map<?, ?>) q.counts(ctx, "inv").get("counts");
        eqNum("1", counts.get("failed")); eqNum("1", counts.get("completed")); eqNum("2", counts.get("total"));
        // loop iterator through the Runner
        q.addItem(ctx, "inv2", Map.of("n", 1)); q.addItem(ctx, "inv2", Map.of("n", 2));
        Map<String, Object> o = run(w, "[{\"name\":\"it\",\"type\":\"Dictionary\"},{\"name\":\"sum\",\"type\":\"Number\",\"direction\":\"output\",\"default\":0}]",
                "[{\"id\":\"l\",\"action\":\"core/loop\",\"args\":{\"iterator\":\"ooa.workload/each_item\",\"iterator_args\":{\"queue\":\"inv2\"},\"as\":\"it\"},\"children\":["
                + "{\"id\":\"a\",\"action\":\"core/set\",\"args\":{\"variable\":\"sum\",\"value\":{\"expr\":\"$sum + $it.data.n\"}}},"
                + "{\"id\":\"c\",\"action\":\"ooa.workload/complete\",\"args\":{\"queue\":\"inv2\",\"item_id\":\"$it.item_id\"}}]}]", Map.of());
        eqNum("3", o.get("sum"));
        assertEquals("QUEUE.BAD_NAME", assertThrows(ActionException.class, () -> q.counts(ctx, "../x")).code());
    }

    @Test void insightsAndRsa() throws Exception {
        Path w = tmp();
        var ctx = PackagesTestSupport.ctx(w, Map.of());
        var ins = new InsightsPackage();
        ins.tag(ctx, "invoices", Map.of("amount", new BigDecimal("120.50"), "minutes_saved", 4));
        ins.tag(ctx, "invoices", Map.of("amount", 99, "minutes_saved", 4));
        eqNum("2", ins.read(ctx, "invoices", null).get("count"));
        var c = new CryptoPackage();
        c.rsaGenerate(ctx, "keys");
        String enc = (String) c.rsaEncrypt(ctx, "account 12345", "keys/public.pem").get("result");
        assertEquals("account 12345", ((SecureString) c.rsaDecrypt(ctx, enc, "keys/private.pem").get("result")).reveal());
        String sig = (String) c.rsaSign(ctx, "approve INV-7", "keys/private.pem").get("signature");
        assertEquals(true, c.rsaVerify(ctx, "approve INV-7", sig, "keys/public.pem").get("valid"));
        assertEquals(false, c.rsaVerify(ctx, "approve INV-8", sig, "keys/public.pem").get("valid"));
        assertEquals("CRYPTO.KEY_EXISTS", assertThrows(ActionException.class, () -> c.rsaGenerate(ctx, "keys")).code(), "keys are never overwritten");
    }

    @Test void terminalReportsMissingEngineOrConnectsAndFails() throws Exception {
        var t = new TerminalPackage();
        var ctx = PackagesTestSupport.ctx(tmp(), Map.of());
        var e = assertThrows(ActionException.class, () -> t.connect(ctx, "127.0.0.1", 1, false, "3279-2", "terminal"));
        assertTrue(Set.of("TERMINAL.NOT_INSTALLED", "TERMINAL.COMMAND_FAILED").contains(e.code()), e.code() + ": " + e.getMessage());
        assertEquals("TERMINAL.BAD_HOST", assertThrows(ActionException.class, () -> t.connect(ctx, "host;rm -rf", 23, false, "3279-2", "t")).code());
        assertEquals("\"a\\\"b\\\\c\"", TerminalPackage.quote("a\"b\\c"));
    }
}
