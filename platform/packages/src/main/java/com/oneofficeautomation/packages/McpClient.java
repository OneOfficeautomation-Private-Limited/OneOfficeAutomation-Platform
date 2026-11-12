package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.ActionException;
import com.oneofficeautomation.sdk.JsonLite;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * A client for an MCP server over Streamable HTTP (gap G44): initialize, list its tools, call one. Answers come as JSON
 * or as server-sent events; both are read. One session per client; the server's Mcp-Session-Id is sent back on each call.
 */
final class McpClient {
    static final String PROTOCOL = "2025-06-18";
    private final URI url;
    private final Map<String, String> headers;
    private final Duration timeout;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    private String session;
    private int nextId = 1;

    McpClient(String url, String bearer, Duration timeout) {
        if (url == null || !url.strip().matches("(?i)https?://[^\\s]+")) throw new ActionException("MCP.BAD_URL", "The MCP server address starts with https:// (or http:// on this computer's network)", "Give the MCP server's address starting with https://.");
        this.url = URI.create(url.strip());
        this.headers = bearer == null || bearer.isBlank() ? Map.of() : Map.of("Authorization", "Bearer " + bearer);
        this.timeout = timeout;
    }

    /** Opens the session: initialize, then the initialized notice. Returns the server's name and version. */
    @SuppressWarnings("unchecked")
    Map<String, Object> initialize() {
        Map<String, Object> r = request("initialize", Map.of("protocolVersion", PROTOCOL, "capabilities", Map.of(),
                "clientInfo", Map.of("name", "OneOfficeAutomation", "version", "1.0")));
        notice("notifications/initialized");
        return r.get("serverInfo") instanceof Map<?, ?> s ? (Map<String, Object>) s : Map.of();
    }

    /** Every tool the server offers (following its pages): name, description, inputSchema. */
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> tools() {
        List<Map<String, Object>> out = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 20; page++) {
            Map<String, Object> r = request("tools/list", cursor == null ? Map.of() : Map.of("cursor", cursor));
            if (r.get("tools") instanceof List<?> l) for (Object t : l) if (t instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
            cursor = r.get("nextCursor") instanceof String c && !c.isBlank() ? c : null;
            if (cursor == null) break;
        }
        return out;
    }

    /** Calls a tool; returns {text, content, structured, is_error}. */
    @SuppressWarnings("unchecked")
    Map<String, Object> call(String tool, Map<String, Object> args) {
        Map<String, Object> r = request("tools/call", Map.of("name", tool, "arguments", args == null ? Map.of() : args));
        StringBuilder text = new StringBuilder();
        if (r.get("content") instanceof List<?> l) for (Object c : l)
            if (c instanceof Map<?, ?> m && "text".equals(m.get("type"))) { if (text.length() > 0) text.append('\n'); text.append(m.get("text")); }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("text", text.toString());
        out.put("content", r.getOrDefault("content", List.of()));
        out.put("structured", r.get("structuredContent"));
        out.put("is_error", Boolean.TRUE.equals(r.get("isError")));
        return out;
    }

    private void notice(String method) {
        send(JsonLite.write(Map.of("jsonrpc", "2.0", "method", method)), null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> request(String method, Map<String, Object> params) {
        int id = nextId++;
        Object answer = send(JsonLite.write(Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params)), id);
        if (!(answer instanceof Map<?, ?> m)) throw new ActionException("MCP.BAD_ANSWER", "The MCP server gave no answer to " + method, "Check that the address is an MCP server and that it is running.");
        if (m.get("error") instanceof Map<?, ?> e)
            throw new ActionException("MCP.ERROR", "The MCP server refused " + method + ": " + e.get("message"), "Check the tool's inputs against what the MCP server expects; the message has the server's answer.", false, Map.of("code", String.valueOf(e.get("code"))));
        return m.get("result") instanceof Map<?, ?> res ? (Map<String, Object>) res : Map.of();
    }

    /** POSTs one JSON-RPC message; for a request, returns the answer with that id (from JSON or from server-sent events). */
    private Object send(String body, Integer id) {
        HttpRequest.Builder b = HttpRequest.newBuilder(url).timeout(timeout).POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream").header("MCP-Protocol-Version", PROTOCOL);
        headers.forEach(b::header);
        if (session != null) b.header("Mcp-Session-Id", session);
        HttpResponse<String> r;
        try { r = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)); }
        catch (java.io.IOException e) { throw new ActionException("MCP.UNREACHABLE", "Cannot reach the MCP server " + url.getHost() + ": " + e.getMessage(), "Check the address and that the server is running.", true, null); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new ActionException("RUN.CANCELLED", "Interrupted", "The run was stopped before this step finished. Run the agent again when it should go on."); }
        r.headers().firstValue("Mcp-Session-Id").ifPresent(s -> session = s);
        if (r.statusCode() == 401 || r.statusCode() == 403) throw new ActionException("MCP.DENIED", "The MCP server refused the sign-in (HTTP " + r.statusCode() + ")", "Check the token.");
        if (r.statusCode() / 100 != 2) throw new ActionException("MCP.HTTP", "The MCP server answered HTTP " + r.statusCode(), "Check the MCP server's address and its sign-in token; the server may also be down.", r.statusCode() >= 500, null);
        if (id == null) return null;
        String type = r.headers().firstValue("Content-Type").orElse("");
        if (type.startsWith("text/event-stream")) {
            StringBuilder data = new StringBuilder();
            for (String line : (r.body() + "\n\n").split("\r?\n", -1)) {   // -1 keeps the empty lines that end each event
                if (line.startsWith("data:")) { data.append(line.substring(5).strip()); continue; }
                if (line.isEmpty() && data.length() > 0) {
                    try { if (JsonLite.parse(data.toString()) instanceof Map<?, ?> m && id.equals(num(m.get("id")))) return m; }
                    catch (IllegalArgumentException ignored) { }
                    data.setLength(0);
                }
            }
            return null;
        }
        try { return JsonLite.parse(r.body()); }
        catch (IllegalArgumentException e) { throw new ActionException("MCP.BAD_ANSWER", "The MCP server's answer is not JSON", "Check that the address is an MCP server (not a web page) and that it is running."); }
    }

    private static Integer num(Object v) { return v instanceof Number n ? n.intValue() : null; }
}
