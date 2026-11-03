package com.oneofficeautomation.conversation;

import java.util.*;

/**
 * An agent's input variables seen as a tool: what an assistant may fill in and the JSON Schema for it. One place for the
 * MCP server and One Chat, so both offer an agent the same way. Credential inputs are left out: secrets never come
 * from an assistant.
 */
public final class BotTools {
    private BotTools() {}

    /** Tool name from an agent path: Finance/Invoice worker.flow.json -> Finance_Invoice_worker (at most 64 characters). */
    public static String toolName(String path) {
        String n = path.replaceAll("\\.flow\\.json$", "").replaceAll("[^A-Za-z0-9_-]+", "_").replaceAll("_+", "_");
        return n.length() > 64 ? n.substring(0, 64) : n;
    }

    /** The input and in/out variables of a flow, in declared order, without Credential ones. */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> inputs(Map<String, Object> flow) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : (List<Object>) flow.getOrDefault("variables", List.of())) {
            if (!(o instanceof Map<?, ?> v) || !Arrays.asList("input", "in_out").contains(v.get("direction"))) continue;
            if ("Credential".equals(v.get("type"))) continue;
            out.add((Map<String, Object>) v);
        }
        return out;
    }

    /** Whether the run is rejected without this input: required and no default. */
    public static boolean required(Map<String, Object> variable) {
        return Boolean.TRUE.equals(variable.get("required")) && variable.get("default") == null;
    }

    /** JSON Schema type of an OOA type. */
    public static String jsonType(String ooaType) {
        return switch (ooaType) {
            case "Number" -> "number";
            case "Boolean" -> "boolean";
            case "List" -> "array";
            case "Dictionary", "Record", "Table" -> "object";
            default -> "string";
        };
    }

    /** The JSON Schema of a flow's inputs, as MCP tools and model tool calls expect it. */
    public static Map<String, Object> inputSchema(Map<String, Object> flow) { return schema(inputs(flow)); }

    /** The JSON Schema of these input variables. */
    public static Map<String, Object> schema(List<Map<String, Object>> inputs) {
        Map<String, Object> props = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (Map<String, Object> v : inputs) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("type", jsonType(String.valueOf(v.get("type"))));
            if (v.get("description") instanceof String d) s.put("description", d);
            if (v.get("enum") instanceof List<?> en) s.put("enum", en);
            props.put(String.valueOf(v.get("name")), s);
            if (required(v)) required.add(String.valueOf(v.get("name")));
        }
        Map<String, Object> schema = new LinkedHashMap<>(Map.of("type", "object", "properties", props));
        if (!required.isEmpty()) schema.put("required", required);
        return schema;
    }
}
