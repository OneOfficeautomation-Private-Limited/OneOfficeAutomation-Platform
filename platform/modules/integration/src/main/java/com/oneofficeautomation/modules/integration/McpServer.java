package com.oneofficeautomation.modules.integration;

import com.oneofficeautomation.controlroom.ControlRoom.User;
import com.oneofficeautomation.controlroom.Db;
import com.oneofficeautomation.controlroom.module.ModuleContext;

import com.oneofficeautomation.controlroom.module.ModuleContext;

import com.oneofficeautomation.conversation.BotTools;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;

import java.time.Instant;
import java.util.*;

import static com.oneofficeautomation.controlroom.module.ModuleContext.*;

/**
 * Model Context Protocol server over HTTP (JSON-RPC 2.0 on POST /api/mcp). Outside AI assistants list the agents an
 * administrator exposed (Settings: mcp_agents) as tools and run them as the calling user, with that user's role and
 * vault access. Each call starts a normal run (source "mcp"), so it shows in Agent Activity and the Audit Trail.
 * Tools feature modules offer (Document Automation's documents_ tools) come first, for people allowed to use them.
 */
public final class McpServer {
    public static final String PROTOCOL = "2025-06-18";
    private final ModuleContext ctx;

    public McpServer(ModuleContext ctx) { this.ctx = ctx; }

    /** Tools feature modules offer (Document Automation's documents_ tools first), as registered at start-up. */
    private List<ModuleContext.McpTools> moduleTools() { return ctx.mcpTools(); }

    /** What the assistant is told: the agent tools, then what each module's tools are for. */
    private String instructions() {
        StringBuilder b = new StringBuilder("Each agent tool runs a live production agent of ONEOFFICEAUTOMATION as the signed-in user; its result is the agent's outputs.");
        for (ModuleContext.McpTools t : moduleTools()) if (!t.instructions().isBlank()) b.append(' ').append(t.instructions());
        return b.toString();
    }

    /** Handles one JSON-RPC message; returns null for notifications (no response body). */
    @SuppressWarnings("unchecked")
    public Map<String, Object> handle(User u, Object message, String ip) {
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
                        "serverInfo", Map.of("name", "ONEOFFICEAUTOMATION One CommandCenter", "version", "0.2.0"),
                        "instructions", instructions()));
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
    public static String toolName(String path) { return BotTools.toolName(path); }

    private List<String> exposed() {
        String s = String.valueOf(ctx.setting("mcp_agents")).trim();
        if (s.isEmpty()) return List.of();
        if (s.equals("*")) return null;
        return Arrays.stream(s.split("[,\\n]")).map(String::trim).filter(x -> !x.isEmpty()).toList();
    }

    /** The tools this person may use: the modules' (Document Automation's documents_ tools), then the agents exposed. */
    private List<Map<String, Object>> tools(User u) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ModuleContext.McpTools t : moduleTools()) out.addAll(t.tools(u));
        out.addAll(agentTools(u));
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> agentTools(User u) {
        if (!ctx.can(u, "runs.start")) return List.of();
        List<String> allow = exposed();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> b : ctx.db().query("SELECT path, description FROM bots WHERE production_version IS NOT NULL ORDER BY path")) {
            String path = (String) b.get("path");
            if (!path.endsWith(".flow.json")) continue;
            if (allow != null && !allow.contains(path) && !allow.contains(toolName(path))) continue;
            if (moduleTools().stream().anyMatch(t -> t.names().contains(toolName(path)))) continue;   // modules' tools keep their names
            Map<String, Object> flow;
            try { flow = (Map<String, Object>) ctx.agentContent(path, "production").get("flow"); } catch (OoaException e) { continue; }
            Map<String, Object> schema = BotTools.inputSchema(flow);
            String desc = b.get("description") instanceof String d && !d.isBlank() ? d : flow.get("description") instanceof String fd && !fd.isBlank() ? fd : "Runs the agent " + path.replace(".flow.json", "");
            out.add(Map.of("name", toolName(path), "title", path.replace(".flow.json", ""), "description", desc, "inputSchema", schema));
        }
        return out;
    }

    /** Runs the agent on the configured runner or pool (or any online runner) and waits for its outputs. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> call(User u, String name, Map<String, Object> args, String ip) {
        for (ModuleContext.McpTools t : moduleTools()) if (t.names().contains(name)) return t.call(u, name, args, ip);
        Map<String, Object> tool = agentTools(u).stream().filter(t -> t.get("name").equals(name)).findFirst()
                .orElseThrow(() -> new OoaException("MCP.UNKNOWN_TOOL", "No exposed agent is called " + name, "Check the tool's name; Build > Agent APIs lists the agents offered over MCP."));
        String path = String.valueOf(tool.get("title")) + ".flow.json";
        String target = String.valueOf(ctx.setting("mcp_runner")).trim();
        String device = null, pool = null;
        if (target.startsWith("pool_") || ctx.db().one("SELECT id FROM device_pools WHERE id = ?", target) != null) pool = target;
        else if (!target.isEmpty()) device = target;
        else device = ctx.onlineRunner();
        if (device == null && pool == null) throw new OoaException("MCP.NO_RUNNER", "No runner is online to run " + name, "Start a runner (Manage > Runners), then try again.");
        Map<String, Object> run = ctx.startRun(u.username(), u.role(), path, device, pool, args, "mcp", ip, 5, null, null, null);
        String runId = (String) run.get("run_id");
        long deadline = System.currentTimeMillis() + 1000L * Math.max(10, ctx.intSetting("mcp_timeout_seconds"));
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> r = ctx.db().one("SELECT status, outputs, error FROM runs WHERE id = ?", runId);
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
        return Map.of("content", List.of(Map.of("type", "text", "text", "The agent is still running as run " + runId + "; check Agent Activity for its result.")), "isError", false);
    }

}
