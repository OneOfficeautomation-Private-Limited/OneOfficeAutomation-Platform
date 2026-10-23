package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.ActionContext;

import java.util.*;

/**
 * The model client for toolkits in other modules (the web goal agent): one call with messages and tools, in the
 * neutral format LlmClient uses, with the same masking and governance records.
 */
public final class AiBridge {
    private AiBridge() {}

    /** Connection settings an action passes through; values set on the action win over the named connection. */
    public record Settings(String connection, String provider, String model, Object apiKey, String baseUrl, double temperature, int maxTokens, boolean maskPii,
                           Object awsAccessKeyId, String awsRegion, int timeoutSeconds) {}

    /** {text, tool_calls: [{id, name, args}], input_tokens, output_tokens}. */
    public static Map<String, Object> call(ActionContext ctx, Settings s, List<Map<String, Object>> messages, List<Map<String, Object>> tools, String purpose) {
        LlmClient.Config c = ModelConnections.config(ctx, s.connection(), s.provider(), s.model(), s.apiKey(), s.baseUrl(), s.temperature(), s.maxTokens(), s.maskPii(),
                s.awsAccessKeyId(), s.awsRegion()).with(LlmClient.Options.of(null, null, null, null, null, null, null, null, s.timeoutSeconds()));
        LlmClient.Reply r = LlmClient.chat(ctx, c, messages, tools, false, purpose);
        List<Object> calls = new ArrayList<>();
        for (LlmClient.ToolCall tc : r.toolCalls()) calls.add(Map.of("id", tc.id(), "name", tc.name(), "args", tc.args()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("text", r.text());
        out.put("tool_calls", calls);
        out.put("input_tokens", r.inTokens());
        out.put("output_tokens", r.outTokens());
        return out;
    }

    /** Descriptions shared with the AI toolkits, so every AI action explains its settings the same way. */
    public static final String D_PROVIDER = GenAiPackage.D_PROVIDER, D_MODEL = GenAiPackage.D_MODEL, D_KEY = GenAiPackage.D_KEY, D_BASE = GenAiPackage.D_BASE,
            D_MASK = GenAiPackage.D_MASK, D_AWS_KEY_ID = GenAiPackage.D_AWS_KEY_ID, D_AWS_REGION = GenAiPackage.D_AWS_REGION, D_CONNECTION = ModelConnections.D_CONNECTION;
}
