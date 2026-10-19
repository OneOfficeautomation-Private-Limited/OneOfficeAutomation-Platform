package com.oneofficeautomation.runner;

import java.util.List;
import java.util.Map;

/** Validates a Flow against the OOA Flow v1 JSON Schema. Production adapter: networknt json-schema-validator. */
public interface FlowSchemaValidator {
    /** @return problems as {stepId, path, message, suggestedFix}; empty when valid */
    List<Map<String, Object>> validate(Map<String, Object> flow);

    FlowSchemaValidator NONE = flow -> List.of();
}
