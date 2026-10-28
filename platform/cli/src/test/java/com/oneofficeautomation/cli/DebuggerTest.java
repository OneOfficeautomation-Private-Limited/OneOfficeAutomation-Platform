package com.oneofficeautomation.cli;

import com.oneofficeautomation.sdk.JsonLite;
import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Agent Studio's debugger (breakpoints, stepping, call stack, variable edits) and One Model Connections, through the HTTP API. */
class DebuggerTest {
    @SuppressWarnings("unchecked")
    static Map<String, Object> call(String base, String method, String path, Object body) throws Exception {
        return StudioControlRoomTest.call(base, method, path, body, null);
    }

    @SuppressWarnings("unchecked")
    @Test void breakpointsStepsAndVariableEdits() throws Exception {
        Path ws = Files.createTempDirectory("ooa-dbg");
        int port = StudioControlRoomTest.freePort();
        new StudioServer(ws, port, Path.of("/nonexistent")).start();
        String base = "http://127.0.0.1:" + port;
        Map<String, Object> child = (Map<String, Object>) JsonLite.parse("{\"kind\":\"TaskBot\",\"id\":\"child_agent\",\"name\":\"Child\","
                + "\"variables\":[{\"name\":\"y\",\"type\":\"Number\",\"direction\":\"output\"}],"
                + "\"steps\":[{\"id\":\"c1\",\"action\":\"core/set\",\"args\":{\"variable\":\"y\",\"value\":7}},{\"id\":\"c2\",\"action\":\"core/log\",\"args\":{\"message\":\"child\"}}]}");
        Files.writeString(ws.resolve("Child.flow.json"), JsonLite.write(child));
        Map<String, Object> flow = (Map<String, Object>) JsonLite.parse("{\"kind\":\"TaskBot\",\"id\":\"dbg\",\"name\":\"Debug me\","
                + "\"variables\":[{\"name\":\"x\",\"type\":\"Number\",\"direction\":\"output\"},{\"name\":\"r\",\"type\":\"Dictionary\"}],"
                + "\"steps\":[{\"id\":\"s1\",\"action\":\"core/set\",\"args\":{\"variable\":\"x\",\"value\":1}},"
                + "{\"id\":\"s2\",\"action\":\"core/log\",\"args\":{\"message\":\"x is ${x}\"}},"
                + "{\"id\":\"s3\",\"action\":\"core/run_bot\",\"args\":{\"automation\":\"child_agent\"},\"out\":{\"outputs\":\"r\"}},"
                + "{\"id\":\"s4\",\"action\":\"core/set\",\"args\":{\"variable\":\"x\",\"value\":\"${max($x, 3)}\"}}]}");
        Map<String, Object> started = call(base, "POST", "/api/runs", Map.of("flow", flow, "inputs", Map.of(), "debug", true, "breakpoints", List.of("s2")));
        String id = (String) started.get("run_id");
        assertNotNull(id, String.valueOf(started));
        Map<String, Object> st = waitStopped(base, id);
        Map<String, Object> dbg = (Map<String, Object>) st.get("debug");
        assertEquals("s2", dbg.get("stepId"), "stops at the breakpoint");
        assertEquals("breakpoint", dbg.get("reason"));
        assertEquals(1, ((Number) ((Map<String, Object>) dbg.get("variables")).get("x")).intValue(), "s1 already ran");
        assertEquals(200, call(base, "POST", "/api/runs/" + id + "/variable", Map.of("name", "x", "value", 5)).get("_status"));
        assertEquals(5, ((Number) ((Map<String, Object>) ((Map<String, Object>) waitStopped(base, id).get("debug")).get("variables")).get("x")).intValue(), "variables can be changed while stopped");

        call(base, "POST", "/api/runs/" + id + "/debug", Map.of("command", "stepOver"));
        Map<String, Object> d3 = (Map<String, Object>) waitStoppedAt(base, id, "s3").get("debug");
        assertEquals("step", d3.get("reason"));
        call(base, "POST", "/api/runs/" + id + "/debug", Map.of("command", "stepInto"));
        Map<String, Object> dc = (Map<String, Object>) waitStoppedAt(base, id, "c1").get("debug");
        assertEquals(1, ((Number) dc.get("depth")).intValue(), "step into enters the child agent");
        List<Object> stack = (List<Object>) dc.get("stack");
        assertEquals(2, stack.size());
        assertEquals("dbg", ((Map<String, Object>) stack.get(0)).get("automation"));
        assertEquals("s3", ((Map<String, Object>) stack.get(0)).get("stepId"), "the call stack shows where the child was called");
        call(base, "POST", "/api/runs/" + id + "/debug", Map.of("command", "stepOut"));
        waitStoppedAt(base, id, "s4");
        call(base, "POST", "/api/runs/" + id + "/debug", Map.of("command", "continue"));
        Map<String, Object> done = null;
        for (int i = 0; i < 100 && done == null; i++) { Map<String, Object> r = call(base, "GET", "/api/runs/" + id, null); if (r.get("result") != null) done = (Map<String, Object>) r.get("result"); else Thread.sleep(100); }
        assertNotNull(done);
        assertEquals("success", done.get("status"));
        assertEquals(5, ((Number) ((Map<String, Object>) done.get("outputs")).get("x")).intValue(), "the edited value was used: max(5, 3), not max(1, 3)");
        assertEquals(400, call(base, "POST", "/api/runs/" + id + "/debug", Map.of("command", "jump")).get("_status") instanceof Number n ? n.intValue() : -1,
                "commands are only accepted while stopped");
    }

    @SuppressWarnings("unchecked")
    @Test void modelConnectionsKeepKeysOutOfFiles() throws Exception {
        Path ws = Files.createTempDirectory("ooa-conn");
        int port = StudioControlRoomTest.freePort();
        new StudioServer(ws, port, Path.of("/nonexistent")).start();
        String base = "http://127.0.0.1:" + port;
        Map<String, Object> bad = call(base, "PUT", "/api/model-connections", Map.of("connections", List.of(Map.of("name", "gpt", "provider", "openai", "model", "x", "api_key", "sk-123"))));
        assertEquals("AI.KEY_IN_FILE", ((Map<String, Object>) bad.get("error")).get("code"), "plain keys are refused");
        assertFalse(Files.exists(ws.resolve("model-connections.json")));
        Map<String, Object> ok = call(base, "PUT", "/api/model-connections", Map.of("default", "local", "connections", List.of(
                Map.of("name", "gpt", "provider", "openai", "model", "gpt-4o-mini", "api_key_env", "OOA_TEST_NO_SUCH_KEY"),
                Map.of("name", "local", "provider", "openai_compatible", "model", "llama", "base_url", "http://127.0.0.1:1/v1"))));
        assertEquals("local", ok.get("default"));
        assertEquals(2, ((List<Object>) call(base, "GET", "/api/model-connections", null).get("connections")).size());
        assertEquals("AI.BAD_PROVIDER", ((Map<String, Object>) call(base, "PUT", "/api/model-connections", Map.of("connections", List.of(Map.of("name", "z", "provider", "nope")))).get("error")).get("code"));
        // saved connector connections are their own list (connections.json), not the model connections
        assertEquals(List.of(), call(base, "GET", "/api/connections", null).get("connections"));
        assertEquals(Boolean.TRUE, call(base, "PUT", "/api/connections/ServiceNow%20prod", Map.of("package", "ooa.servicenow",
                "values", Map.of("instance_url", "https://acme.service-now.com"), "credentials", Map.of("password", "itsm/servicenow/password"))).get("ok"));
        List<Map<String, Object>> saved = (List<Map<String, Object>>) call(base, "GET", "/api/connections", null).get("connections");
        assertEquals(1, saved.size());
        assertEquals("ServiceNow prod", saved.get(0).get("name"));
        assertEquals("ooa.servicenow", saved.get(0).get("package"));
        assertEquals(2, ((List<Object>) call(base, "GET", "/api/model-connections", null).get("connections")).size(), "model connections unchanged");
        // An action using a connection whose key variable is missing explains what to do.
        Map<String, Object> flow = (Map<String, Object>) JsonLite.parse("{\"kind\":\"TaskBot\",\"id\":\"ai\",\"name\":\"AI\",\"variables\":[{\"name\":\"t\",\"type\":\"String\",\"direction\":\"output\"}],"
                + "\"steps\":[{\"id\":\"p\",\"action\":\"ooa.genai/prompt\",\"args\":{\"prompt\":\"hi\",\"connection\":\"gpt\"},\"out\":{\"text\":\"t\"}}]}");
        Map<String, Object> run = call(base, "POST", "/api/run", Map.of("flow", flow, "inputs", Map.of()));
        Map<String, Object> err = (Map<String, Object>) ((Map<String, Object>) run.get("result")).get("error");
        assertEquals("AI.NO_KEY", err.get("code"), String.valueOf(err));
        Map<String, Object> asst = call(base, "POST", "/api/assistant/build", Map.of("prompt", "read a file", "connection", "nope"));
        assertEquals("AI.NO_CONNECTION", ((Map<String, Object>) asst.get("error")).get("code"), String.valueOf(asst));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> waitStopped(String base, String id) throws Exception {
        for (int i = 0; i < 100; i++) {
            Map<String, Object> r = call(base, "GET", "/api/runs/" + id, null);
            if ("debugging".equals(r.get("state"))) return r;
            Thread.sleep(100);
        }
        throw new AssertionError("run did not stop");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> waitStoppedAt(String base, String id, String step) throws Exception {
        for (int i = 0; i < 100; i++) {
            Map<String, Object> r = call(base, "GET", "/api/runs/" + id, null);
            if ("debugging".equals(r.get("state")) && step.equals(((Map<String, Object>) r.get("debug")).get("stepId"))) return r;
            Thread.sleep(100);
        }
        throw new AssertionError("run did not stop at " + step);
    }
}
