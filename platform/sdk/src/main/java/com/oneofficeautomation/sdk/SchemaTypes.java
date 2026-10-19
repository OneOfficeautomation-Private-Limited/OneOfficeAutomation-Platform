package com.oneofficeautomation.sdk;

import java.util.Map;

/** JSON Schema fragments for OOA types: used in manifests, agent tool schemas and MCP. */
public final class SchemaTypes {
    private SchemaTypes() {}

    public static Map<String, Object> forType(OoaType t) {
        return switch (t) {
            case STRING -> Map.of("type", "string");
            case NUMBER -> Map.of("type", "number");
            case BOOLEAN -> Map.of("type", "boolean");
            case DATETIME -> Map.of("type", "string", "format", "date-time");
            case LIST -> Map.of("type", "array");
            case DICTIONARY, RECORD -> Map.of("type", "object");
            case TABLE -> Map.of("type", "object", "required", java.util.List.of("columns", "rows"));
            case FILE -> Map.of("type", "string");
            case CREDENTIAL -> Map.of("type", "string");
            default -> Map.of();
        };
    }
}
