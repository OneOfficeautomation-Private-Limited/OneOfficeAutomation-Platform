package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import com.oneofficeautomation.sdk.JsonLite;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** Item 4n on the runner: AI switched off refuses every model call; full logging adds the masked prompt and answer. */
class AiSwitchPackagesTest {
    @TempDir Path w;

    static ActionContext ctx(Path w, Map<String, Object> guardrails) {
        return PackagesTestSupport.ctx(w, Map.of("ai_guardrails", (Supplier<Object>) () -> guardrails));
    }

    @Test void switchedOffNoModelIsCalled() {
        ActionException e = assertThrows(ActionException.class, () -> ModelConnections.config(ctx(w, Map.of("disabled", true)), null, "openai", "gpt", null, "http://127.0.0.1:9", 0, 0, true));
        assertEquals("AI.DISABLED", e.code());
        assertNotNull(ModelConnections.config(ctx(w, Map.of()), null, "openai", "gpt", null, "http://127.0.0.1:9", 0, 0, true));
    }

    @Test @SuppressWarnings("unchecked")
    void onlyMetadataUnlessFullLoggingIsOn() throws Exception {
        LlmClient.Config cfg = ModelConnections.config(ctx(w, Map.of()), null, "openai", "gpt", null, "http://127.0.0.1:9", 0, 0, true);
        LlmClient.Reply r = new LlmClient.Reply("Hello", List.of(), 3, 1, Map.of(), null);
        List<Map<String, Object>> sent = List.of(Map.of("role", "user", "content", "Hi <PERSON_1>"));
        LlmClient.govern(ctx(w, Map.of()), cfg, "openai", "prompt", r, 5, sent, "Hello <PERSON_1>");
        LlmClient.govern(ctx(w, Map.of("log_content", true)), cfg, "openai", "prompt", r, 5, sent, "Hello <PERSON_1>");
        List<String> lines = Files.readAllLines(w.resolve("ai-governance.jsonl"));
        Map<String, Object> plain = (Map<String, Object>) JsonLite.parse(lines.get(0)), full = (Map<String, Object>) JsonLite.parse(lines.get(1));
        assertFalse(plain.containsKey("content"));
        assertNotNull(plain.get("prompt_sha256"));
        Map<String, Object> content = (Map<String, Object>) full.get("content");
        assertTrue(String.valueOf(content.get("prompt")).contains("<PERSON_1>"), "the prompt as the provider saw it, masked");
        assertEquals("Hello <PERSON_1>", content.get("answer"));
    }
}
