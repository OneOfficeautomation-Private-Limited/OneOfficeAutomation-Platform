package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * G44: an AI agent that searches knowledge indexes itself, uses an MCP server's tools, and, when nobody is at the
 * runner, asks the approvers in the Task inbox; and the MCP tools toolkit on its own.
 */
class AiAgentReachTest {
    @TempDir Path w;

    @AfterEach void clear() { System.clearProperty("ooa.runMode"); System.clearProperty("ooa.approval.pollMillis"); }

    /** A small MCP server: initialize (gives a session), tools/list, tools/call (answered as server-sent events). */
    static ConnectorsTest.Fake mcp(List<String> seen) throws Exception {
        ConnectorsTest.Fake f = new ConnectorsTest.Fake();
        f.on("POST /mcp", c -> {
            Map<?, ?> req = (Map<?, ?>) JsonLite.parse(c.text());
            String method = String.valueOf(req.get("method"));
            seen.add(method + " " + (c.headers().getOrDefault("Mcp-session-id", List.of("-")).get(0)) + " " + c.auth());
            Object id = req.get("id");
            if (id == null) return new Object[]{202, ""};
            return switch (method) {
                case "initialize" -> new Object[]{200, JsonLite.write(Map.of("jsonrpc", "2.0", "id", id, "result", Map.of("protocolVersion", "2025-06-18",
                        "serverInfo", Map.of("name", "crm", "version", "2.1"), "capabilities", Map.of("tools", Map.of())))), Map.of("Mcp-Session-Id", "S-1", "Content-Type", "application/json")};
                case "tools/list" -> ConnectorsTest.ok(JsonLite.write(Map.of("jsonrpc", "2.0", "id", id, "result", Map.of("tools", List.of(
                        Map.of("name", "lookup_customer", "description", "Find a customer by number.", "inputSchema", Map.of("type", "object", "properties", Map.of("number", Map.of("type", "string")))),
                        Map.of("name", "delete_customer", "description", "Delete a customer.", "inputSchema", Map.of("type", "object")))))));
                case "tools/call" -> {
                    Map<?, ?> p = (Map<?, ?>) req.get("params");
                    String text = "lookup_customer".equals(p.get("name")) ? "Customer " + ((Map<?, ?>) p.get("arguments")).get("number") + " is ACME Ltd, credit limit 5000" : "deleted";
                    yield new Object[]{200, "event: message\ndata: " + JsonLite.write(Map.of("jsonrpc", "2.0", "id", id, "result", Map.of("content", List.of(Map.of("type", "text", "text", text)), "isError", false))) + "\n\n",
                            Map.of("Content-Type", "text/event-stream")};
                }
                default -> ConnectorsTest.ok(JsonLite.write(Map.of("jsonrpc", "2.0", "id", id, "error", Map.of("code", -32601, "message", "no " + method))));
            };
        });
        return f;
    }

    static ActionContext ctx(Path w, Map<String, Object> extra) {
        Map<String, Object> s = new HashMap<>(extra);
        s.put("repository", (com.oneofficeautomation.runner.AutomationRepository) (id, v) -> { throw new OoaException("REPO.NOT_FOUND", id); });
        s.put("bot_invoker", (com.oneofficeautomation.runner.BotInvoker) (id, in) -> null);
        return PackagesTestSupport.ctx(w, s);
    }

    static Map<String, Object> agent(ActionContext ctx, String llm, List<Object> sensitive, String approvers, List<Object> knowledge, String mcpUrl, List<Object> mcpTools) {
        return new AiAgentPackage().run(ctx, "Check customer C-7", "Be accurate.", null, sensitive, "openai", null, "m", new SecureString("sk", "k"), llm + "/v1",
                5, 60000, false, null, null, null, null, null, false, 0, 0, null, 600, "off", "medium", null, approvers, 60, knowledge, mcpUrl, null, mcpTools);
    }

    @Test void mcpToolkitListsAndCalls() throws Exception {
        List<String> seen = new ArrayList<>();
        try (ConnectorsTest.Fake s = mcp(seen)) {
            McpPackage p = new McpPackage();
            Map<String, Object> l = p.listTools(ctx(w, Map.of()), s.url() + "/mcp", new SecureString("tok-1", "t"), 30);
            assertEquals(Map.of("name", "crm", "version", "2.1"), l.get("server"));
            assertEquals(List.of("lookup_customer", "delete_customer"), ((List<?>) l.get("tools")).stream().map(t -> ((Map<?, ?>) t).get("name")).toList());
            Map<String, Object> c = p.callTool(ctx(w, Map.of()), s.url() + "/mcp", "lookup_customer", Map.of("number", "C-7"), null, true, 30);
            assertEquals("Customer C-7 is ACME Ltd, credit limit 5000", c.get("text"));
            assertEquals(false, c.get("is_error"));
            assertTrue(seen.contains("initialize - Bearer tok-1"), String.valueOf(seen));
            assertTrue(seen.contains("tools/list S-1 Bearer tok-1"), "the session id is sent back: " + seen);
            assertEquals("MCP.BAD_URL", assertThrows(ActionException.class, () -> p.listTools(ctx(w, Map.of()), "file:///etc/passwd", null, 5)).code());
        }
    }

    @Test void theAgentUsesAnMcpToolAndSearchesKnowledge() throws Exception {
        List<String> seen = new ArrayList<>();
        new KnowledgePackage().buildIndex(ctx(w, Map.of()), null, List.of(Map.of("source", "credit.pdf", "text", "Orders above the credit limit need the finance manager's approval.")),
                "kb/credit.json", null, null, null, null, null, 1000, 150, false);
        try (ConnectorsTest.Fake s = mcp(seen); ConnectorsTest.Fake llm = new ConnectorsTest.Fake()) {
            List<String> bodies = new ArrayList<>();
            int[] turn = {0};
            llm.on("POST /v1/chat/completions", c -> {
                bodies.add(c.text());
                return switch (++turn[0]) {
                    case 1 -> ConnectorsTest.ok("{\"choices\":[{\"message\":{\"content\":null,\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"mcp_lookup_customer\",\"arguments\":\"{\\\"number\\\":\\\"C-7\\\"}\"}},"
                            + "{\"id\":\"c2\",\"type\":\"function\",\"function\":{\"name\":\"search_knowledge\",\"arguments\":\"{\\\"query\\\":\\\"credit limit approval\\\"}\"}}]}}]}");
                    default -> ConnectorsTest.ok("{\"choices\":[{\"message\":{\"content\":\"ACME Ltd; above the limit the finance manager approves.\"}}]}");
                };
            });
            Map<String, Object> r = agent(ctx(w, Map.of()), llm.url(), null, null, List.of("kb/credit.json"), s.url() + "/mcp", List.of("lookup_customer"));
            assertEquals("completed", r.get("status"), String.valueOf(r));
            assertTrue(bodies.get(0).contains("mcp_lookup_customer") && bodies.get(0).contains("search_knowledge"), "both are offered");
            assertFalse(bodies.get(0).contains("mcp_delete_customer"), "only the tools allowed in mcp_tools");
            assertTrue(bodies.get(1).contains("ACME Ltd, credit limit 5000"), "the MCP answer goes back to the model");
            assertTrue(bodies.get(1).contains("finance manager"), "so do the passages");
            String trace = String.valueOf(r.get("trace"));
            assertTrue(trace.contains("mcp:lookup_customer") && trace.contains("knowledge"), trace);
        }
    }

    @Test @SuppressWarnings("unchecked")
    void unattendedApprovalGoesToTheTaskInbox() throws Exception {
        System.setProperty("ooa.runMode", "unattended");
        System.setProperty("ooa.approval.pollMillis", "10");
        List<String> seen = new ArrayList<>();
        try (ConnectorsTest.Fake s = mcp(seen); ConnectorsTest.Fake llm = new ConnectorsTest.Fake()) {
            int[] turn = {0};
            llm.on("POST /v1/chat/completions", c -> ++turn[0] == 1
                    ? ConnectorsTest.ok("{\"choices\":[{\"message\":{\"content\":null,\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"mcp_delete_customer\",\"arguments\":\"{\\\"number\\\":\\\"C-7\\\"}\"}}]}}]}")
                    : ConnectorsTest.ok("{\"choices\":[{\"message\":{\"content\":\"Done.\"}}]}"));
            List<Map<String, Object>> asked = new ArrayList<>();
            String[] answer = {"approved"};
            int[] polls = {0};
            Function<Map<String, Object>, Map<String, Object>> cc = req -> {
                asked.add(req);
                if ("create_task".equals(req.get("call"))) return Map.of("task_id", "task_1");
                return ++polls[0] < 3 ? Map.of("status", "open") : Map.of("status", "completed", "outcome", answer[0], "comment", "fine");
            };
            Map<String, Object> r = agent(ctx(w, Map.of("command_center", cc)), llm.url(), List.of("mcp:delete_customer"), "role:supervisor", null, s.url() + "/mcp", null);
            assertEquals("completed", r.get("status"), String.valueOf(r));
            Map<String, Object> args = (Map<String, Object>) asked.get(0).get("args");
            assertEquals("approval", args.get("kind"));
            assertEquals("role:supervisor", args.get("assignee"));
            assertTrue(String.valueOf(args.get("title")).contains("mcp:delete_customer"));
            assertTrue(seen.stream().anyMatch(x -> x.startsWith("tools/call")), "approved: the tool ran");

            // rejected: the tool does not run
            seen.clear(); turn[0] = 0; polls[0] = 0; answer[0] = "rejected"; asked.clear();
            Map<String, Object> no = agent(ctx(w, Map.of("command_center", cc)), llm.url(), List.of("mcp:delete_customer"), "role:supervisor", null, s.url() + "/mcp", null);
            assertTrue(seen.stream().noneMatch(x -> x.startsWith("tools/call")), String.valueOf(seen));
            assertTrue(String.valueOf(no.get("trace")).contains("denied"));

            // no approvers named: denied at once, nobody asked
            seen.clear(); turn[0] = 0; asked.clear();
            agent(ctx(w, Map.of("command_center", cc)), llm.url(), List.of("mcp:delete_customer"), null, null, s.url() + "/mcp", null);
            assertTrue(asked.isEmpty());
            assertTrue(seen.stream().noneMatch(x -> x.startsWith("tools/call")));
        }
    }
}
