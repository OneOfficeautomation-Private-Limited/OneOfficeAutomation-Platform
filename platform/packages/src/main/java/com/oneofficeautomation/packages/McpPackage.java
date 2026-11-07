package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;

import java.time.Duration;
import java.util.*;

/**
 * Tools of other systems through the Model Context Protocol (gap G44): list the tools an MCP server offers and call one.
 * The AI agent can use an MCP server's tools as well (Run AI agent > mcp_server_url). One CommandCenter also offers OOA
 * agents to outside assistants as an MCP server; this is the other direction.
 */
@OoaPackage(id = "ooa.mcp", name = "MCP tools", version = "1.0.0", description = "List and call the tools of an MCP server (Model Context Protocol over HTTP).")
public final class McpPackage {
    static String secret(Object v) { return v instanceof SecureString ss ? ss.reveal() : v == null ? null : Values.text(v); }

    @Action(id = "list_tools", name = "List MCP tools", category = "MCP", idempotent = true, timeoutSeconds = 120, capabilities = "network",
            description = "Connect to an MCP server and list the tools it offers, with what each needs.", panel = "server_url, token | timeout_seconds")
    @Output(name = "tools", type = OoaType.LIST, description = "Each tool: name, description and input_schema.")
    @Output(name = "server", type = OoaType.DICTIONARY, description = "The server's name and version.")
    public Map<String, Object> listTools(ActionContext ctx,
            @Input(name = "server_url", type = OoaType.STRING, example = "https://mcp.example.com/mcp", description = "The MCP server's address (Streamable HTTP).") String url,
            @Input(name = "token", type = OoaType.CREDENTIAL, required = false, description = "A bearer token, when the server needs one.") Object token,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "60", min = 1, max = 600, description = "How long to wait for each answer.") int timeout) {
        McpClient c = new McpClient(url, secret(token), Duration.ofSeconds(timeout));
        Map<String, Object> server = c.initialize();
        List<Object> tools = new ArrayList<>();
        for (Map<String, Object> t : c.tools())
            tools.add(Map.of("name", String.valueOf(t.get("name")), "description", String.valueOf(t.getOrDefault("description", "")),
                    "input_schema", t.get("inputSchema") instanceof Map<?, ?> s ? s : Map.of("type", "object")));
        return Map.of("tools", tools, "server", server);
    }

    @Action(id = "call_tool", name = "Call MCP tool", category = "MCP", timeoutSeconds = 600, capabilities = "network",
            description = "Call one tool of an MCP server with arguments and return what it answers.", panel = "server_url, tool, arguments, token | fail_on_tool_error, timeout_seconds")
    @Output(name = "text", type = OoaType.STRING, description = "The text the tool returned.")
    @Output(name = "result", description = "The tool's structured result, when it gives one.")
    @Output(name = "content", type = OoaType.LIST, description = "Everything the tool returned (text, images, links).")
    @Output(name = "is_error", type = OoaType.BOOLEAN, description = "True when the tool reported an error.")
    public Map<String, Object> callTool(ActionContext ctx,
            @Input(name = "server_url", type = OoaType.STRING, example = "https://mcp.example.com/mcp", description = "The MCP server's address (Streamable HTTP).") String url,
            @Input(name = "tool", type = OoaType.STRING, example = "lookup_customer", description = "The tool's name (List MCP tools shows them).") String tool,
            @Input(name = "arguments", type = OoaType.DICTIONARY, required = false, description = "The tool's arguments.") Map<String, Object> args,
            @Input(name = "token", type = OoaType.CREDENTIAL, required = false, description = "A bearer token, when the server needs one.") Object token,
            @Input(name = "fail_on_tool_error", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Fail the step when the tool reports an error.") boolean fail,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "120", min = 1, max = 600, description = "How long to wait for the answer.") int timeout) {
        if (tool == null || tool.isBlank()) throw new ActionException("MCP.NO_TOOL", "Give the tool's name");
        McpClient c = new McpClient(url, secret(token), Duration.ofSeconds(timeout));
        c.initialize();
        Map<String, Object> r = c.call(tool.strip(), args);
        if (fail && Boolean.TRUE.equals(r.get("is_error"))) throw new ActionException("MCP.TOOL_ERROR", "The tool " + tool + " reported an error: " + r.get("text"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("text", r.get("text")); out.put("result", r.get("structured")); out.put("content", r.get("content")); out.put("is_error", r.get("is_error"));
        return out;
    }
}
