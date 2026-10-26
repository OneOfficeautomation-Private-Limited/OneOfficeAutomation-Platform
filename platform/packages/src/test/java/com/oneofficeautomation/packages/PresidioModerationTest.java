package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;
import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;

import static com.oneofficeautomation.packages.ConnectorsTest.*;
import static com.oneofficeautomation.packages.PackagesTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** A3: a connection's Presidio analyzer masks what it finds, and its content check stops flagged answers. */
class PresidioModerationTest {

    @SuppressWarnings("unchecked")
    @Test void presidioSpansAreMaskedAndRestoredAndFlaggedAnswersStop() throws Exception {
        try (Fake f = new Fake()) {
            // the analyzer finds a person and a city the built-in patterns do not know
            f.on("POST /analyze", c -> {
                String text = String.valueOf(((Map<String, Object>) JsonLite.parse(c.text())).get("text"));
                int p = text.indexOf("Ingrid Svensson"), q = text.indexOf("Uppsala");
                List<Object> spans = new ArrayList<>();
                if (p >= 0) spans.add(Map.of("entity_type", "PERSON", "start", p, "end", p + 15, "score", 0.9));
                if (q >= 0) spans.add(Map.of("entity_type", "LOCATION", "start", q, "end", q + 7, "score", 0.8));
                return ok(JsonLite.write(spans));
            });
            List<String> sent = new ArrayList<>();
            f.on("POST /v1/chat/completions", c -> {
                sent.add(c.text());
                List<?> msgs = (List<?>) ((Map<String, Object>) JsonLite.parse(c.text())).get("messages");
                String user = String.valueOf(((Map<?, ?>) msgs.get(msgs.size() - 1)).get("content"));
                String person = user.replaceAll(".*(<PERSON_[0-9a-f]{6}>).*", "$1");
                return ok("{\"choices\":[{\"message\":{\"content\":\"Dear " + person + ", noted.\"}}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":4}}");
            });
            f.on("POST /v1/moderations", c -> ok(c.text().contains("noted") ? "{\"results\":[{\"flagged\":false}]}" : "{\"results\":[{\"flagged\":true,\"categories\":{\"harassment\":true,\"violence\":false}}]}"));
            Path w = tmp();
            Files.writeString(w.resolve("model-connections.json"), JsonLite.write(Map.of("default", "safe", "connections", List.of(
                Map.of("name", "safe", "provider", "openai_compatible", "model", "m", "base_url", f.url() + "/v1", "pii_analyzer_url", f.url(), "content_check", "moderation")))));
            var ctx = PackagesTestSupport.ctx(w, Map.of());
            Map<String, Object> r = new GenAiPackage().prompt(ctx, "Write to Ingrid Svensson in Uppsala, ingrid@example.com", null, "openai", "safe", null, null, null, 0, 100, true, null, null);
            assertFalse(sent.get(0).contains("Ingrid Svensson") || sent.get(0).contains("Uppsala") || sent.get(0).contains("ingrid@example.com"), "the model sees none of it: " + sent.get(0));
            assertEquals("Dear Ingrid Svensson, noted.", r.get("text"), "restored in the answer");
            String gov = Files.readString(w.resolve("ai-governance.jsonl"));
            assertTrue(gov.contains("\"PERSON\":1") && gov.contains("\"LOCATION\":1") && gov.contains("\"EMAIL\":1"), gov);

            // an answer the moderation endpoint flags stops the step
            f.on("POST /v1/chat/completions", c -> ok("{\"choices\":[{\"message\":{\"content\":\"something nasty\"}}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2}}"));
            ActionException e = assertThrows(ActionException.class, () -> new GenAiPackage().prompt(ctx, "Say something", null, "openai", "safe", null, null, null, 0, 100, true, null, null));
            assertEquals("AI.CONTENT_FLAGGED", e.code());
            assertTrue(e.getMessage().contains("harassment") && !e.getMessage().contains("violence"), e.getMessage());
        }
    }
}
