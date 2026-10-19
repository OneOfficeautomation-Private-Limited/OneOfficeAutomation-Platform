package com.oneofficeautomation.adapters.jackson;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.oneofficeautomation.runner.FlowSchemaValidator;
import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.OoaException;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/** Validates Flows against the bundled OOA Flow v1 JSON Schema (2020-12). */
public final class NetworkntFlowSchemaValidator implements FlowSchemaValidator {
    private static final JsonSchema SCHEMA = load();

    private static JsonSchema load() {
        try (InputStream in = NetworkntFlowSchemaValidator.class.getResourceAsStream("/flow.v1.json")) {
            if (in == null) throw new OoaException("FLOW.SCHEMA_MISSING", "flow.v1.json is not on the classpath",
                    "Run gradlew :adapters-jackson:syncFlowSchema");
            JsonNode node = JacksonJsonCodec.MAPPER.readTree(in);
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(node);
        } catch (IOException e) { throw new IllegalStateException("Cannot load OOA Flow schema", e); }
    }

    @Override public List<Map<String, Object>> validate(Map<String, Object> flow) {
        JsonNode node = JacksonJsonCodec.MAPPER.valueToTree(Values.toJsonable(flow));
        List<Map<String, Object>> problems = new ArrayList<>();
        for (ValidationMessage m : new TreeSet<>(Comparator.comparing(ValidationMessage::getMessage)) {{ addAll(SCHEMA.validate(node)); }}) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("stepId", null);
            p.put("path", null);
            p.put("message", m.getMessage());
            p.put("suggestedFix", "Fix the Flow JSON so it matches ooa.flow/v1.");
            problems.add(p);
        }
        return problems;
    }
}
