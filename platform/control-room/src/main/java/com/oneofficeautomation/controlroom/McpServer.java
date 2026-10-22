package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;

import java.time.Instant;
import java.util.*;

import static com.oneofficeautomation.controlroom.ControlRoom.*;

/**
 * Model Context Protocol server over HTTP (JSON-RPC 2.0 on POST /api/mcp). Outside AI assistants list the agents an
 * administrator exposed (Settings: mcp_agents) as tools and run them as the calling user, with that user's role and
 * vault access. Each call starts a normal run (source "mcp"), so it shows in Agent Runs and the One Audit Trail.
 */
final class McpServer {
    static final String PROTOCOL = "2025-06-18";
    private final ControlRoom cr;

    McpServer(ControlRoom cr) { this.cr = cr; }

    /** Handles one JSON-RPC message; returns null for notifications (no response body). */
    @SuppressWarnings("unchecked")
    Map<String, Object> handle(User u, Object message, String ip) {
        if (!(message instanceof Map<?, ?> raw)) return error(null, -32600, "Invalid request");
        Map<String, Object> m = (Map<String, Object>) raw;
        Object id = m.get("id");
        String method = String.valueOf(m.get("method"));
        Map<String, Object> params = m.get("params") instanceof Map<?, ?> p ? (Map<String, Object>) p : Map.of();
        if (id == null) return null; // notifications such as notifications/initialized need no answer
        try {
            return switch (method) {
                case "initialize" -> result(id, Map.of("protocolVersion", params.get("protocolVersion") instanceof String v ? v : PROTOCOL,
                        "capabilities", Map.of("tools", Map.of("listChanged", false)),
                        "serverInfo", Map.of("name", "ONEOFFICEAUTOMATION One Command Center", "version", "0.2.0"),
                        "instructions", "Each tool runs a live production agent of ONEOFFICEAUTOMATION as the signed-in user. Tool results are the agent's outputs."));
                case "ping" -> result(id, Map.of());
                case "tools/list" -> result(id, Map.of("tools", tools(u)));
                case "tools/call" -> result(id, call(u, String.valueOf(params.get("name")),
                        params.get("arguments") instanceof Map<?, ?> a ? (Map<String, Object>) a : Map.of(), ip));
                default -> error(id, -32601, "Method not found: " + method);
            };
        } catch (OoaException e) {
            return error(id, -32000, e.getMessage());
        }
    }

    private static Map<String, Object> result(Object id, Object r) { return Map.of("jsonrpc", "2.0", "id", id, "result", r); }
    private static Map<String, Object> error(Object id, int code, String msg) {
        Map<String, Object> m = new LinkedHashMap<>(); m.put("jsonrpc", "2.0"); m.put("id", id); m.put("error", Map.of("code", code, "message", msg)); return m;
    }

    /** Tool name from an agent path: Finance/Invoice worker.flow.json -> Finance_Invoice_worker. */
    static String toolName(String path) {
        String n = path.replaceAll("\\.flow\\.json$", "").replaceAll("[^A-Za-z0-9_-]+", "_").replaceAll("_+", "_");
        return n.length() > 64 ? n.substring(0, 64) : n;
    }

    private List<String> exposed() {
        String s = String.valueOf(cr.platform.setting("mcp_agents")).trim();
        if (s.isEmpty()) return List.of();
        if (s.equals("*")) return null;
        return Arrays.stream(s.split("[,\\n]")).map(String::trim).filter(x -> !x.isEmpty()).toList();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> tools(User u) {
        if (!cr.allowed(u.role(), "runs.start")) return List.of();
        List<String> allow = exposed();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> b : cr.db.query("SELECT path, description FROM bots WHERE production_version IS NOT NULL ORDER BY path")) {
            String path = (String) b.get("path");
            if (!path.endsWith(".flow.json")) continue;
            if (allow != null && !allow.contains(path) && !allow.contains(toolName(path))) continue;
            Map<String, Object> flow;
            try { flow = (Map<String, Object>) cr.content(path, "production").get("flow"); } catch (OoaException e) { continue; }
            Map<String, Object> props = new LinkedHashMap<>();
            List<String> required = new ArrayList<>();
            for (Object o : (List<Object>) flow.getOrDefault("variables", List.of())) {
                if (!(o instanceof Map<?, ?> v) || !java.util.Arrays.asList("input", "in_out").contains(v.get("direction"))) continue;
                String type = String.valueOf(v.get("type"));
                if (type.equals("Credential")) continue; // secrets never come from an assistant
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("type", switch (type) { case "Number" -> "number"; case "Boolean" -> "boolean"; case "List", "Table" -> "array"; case "Dictionary", "Record" -> "object"; default -> "string"; });
                if (type.equals("Table")) s.put("type", "object");
                if (v.get("description") instanceof String d) s.put("description", d);
                if (v.get("enum") instanceof List<?> en) s.put("enum", en);
                props.put(String.valueOf(v.get("name")), s);
                if (Boolean.TRUE.equals(v.get("required")) && v.get("default") == null) required.add(String.valueOf(v.get("name")));
            }
            Map<String, Object> schema = new LinkedHashMap<>(Map.of("type", "object", "properties", props));
            if (!required.isEmpty()) schema.put("required", required);
            String desc = b.get("description") instanceof String d && !d.isBlank() ? d : flow.get("description") instanceof String fd && !fd.isBlank() ? fd : "Runs the agent " + path.replace(".flow.json", "");
            out.add(Map.of("name", toolName(path), "title", path.replace(".flow.json", ""), "description", desc, "inputSchema", schema));
        }
        return out;
    }

    /** Runs the agent on the configured runner or pool (or any online runner) and waits for its outputs. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> call(User u, String name, Map<String, Object> args, String ip) {
        Map<String, Object> tool = tools(u).stream().filter(t -> t.get("name").equals(name)).findFirst()
                .orElseThrow(() -> new OoaException("MCP.UNKNOWN_TOOL", "No exposed agent is called " + name));
        String path = String.valueOf(tool.get("title")) + ".flow.json";
        String target = String.valueOf(cr.platform.setting("mcp_runner")).trim();
        String device = null, pool = null;
        if (target.startsWith("pool_") || cr.db.one("SELECT id FROM device_pools WHERE id = ?", target) != null) pool = target;
        else if (!target.isEmpty()) device = target;
        else device = cr.platform.onlineRunner();
        if (device == null && pool == null) throw new OoaException("MCP.NO_RUNNER", "No runner is online to run " + name);
        Map<String, Object> run = cr.startRun(u.username(), u.role(), path, device, pool, args, "mcp", ip, 5, null);
        String runId = (String) run.get("run_id");
        long deadline = System.currentTimeMillis() + 1000L * Math.max(10, cr.platform.intSetting("mcp_timeout_seconds"));
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> r = cr.db.one("SELECT status, outputs, error FROM runs WHERE id = ?", runId);
            String st = String.valueOf(r.get("status"));
            if (Set.of("success", "failed", "stopped").contains(st)) {
                Object outputs = r.get("outputs") == null ? Map.of() : JsonLite.parse((String) r.get("outputs"));
                Map<String, Object> res = new LinkedHashMap<>();
                if (st.equals("success")) {
                    res.put("content", List.of(Map.of("type", "text", "text", JsonLite.write(outputs))));
                    if (outputs instanceof Map<?, ?>) res.put("structuredContent", outputs);
                    res.put("isError", false);
                } else {
                    Object err = r.get("error") == null ? Map.of("message", st) : JsonLite.parse((String) r.get("error"));
                    res.put("content", List.of(Map.of("type", "text", "text", "The agent run " + runId + " " + st + ": " + JsonLite.write(err))));
                    res.put("isError", true);
                }
                return res;
            }
            try { Thread.sleep(400); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        return Map.of("content", List.of(Map.of("type", "text", "text", "The agent is still running as run " + runId + "; check Agent Runs for its result.")), "isError", false);
    }

}
