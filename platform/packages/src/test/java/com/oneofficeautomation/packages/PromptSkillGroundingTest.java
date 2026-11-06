package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;
import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;

import static com.oneofficeautomation.packages.ConnectorsTest.*;
import static com.oneofficeautomation.packages.PackagesTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Prompt Skills grounded in a knowledge index and in documents given to the run (gap: knowledge-base grounding and file inputs). */
class PromptSkillGroundingTest {
    static final SecureString KEY = new SecureString("sk-test", "k");

    @SuppressWarnings("unchecked")
    @Test void skillGetsPassagesAndDocumentsAndReturnsSources() throws Exception {
        try (Fake f = new Fake()) {
            f.on("POST /v1/chat/completions", c -> ok("{\"choices\":[{\"message\":{\"content\":\"Refunds within 14 days [1].\"}}],\"usage\":{\"prompt_tokens\":40,\"completion_tokens\":6}}"));
            Path w = tmp();
            Files.createDirectories(w.resolve("policies"));
            Files.writeString(w.resolve("policies/refunds.md"), "# Refunds\nRefunds are paid within 14 days of the return.\n\n# Travel\nBook travel through the portal.");
            Files.writeString(w.resolve("invoice.txt"), "Invoice INV-77 total 120 EUR, returned on 2 Nov");
            Files.createDirectories(w.resolve("ai-skills"));
            Files.writeString(w.resolve("ai-skills/refund_check.json"), "{\"description\":\"Check a refund\",\"prompt\":\"Is the refund for ${question} on time?\","
                    + "\"provider\":\"openai\",\"model\":\"gpt-test\",\"knowledge\":{\"index\":\"kb/policies.json\",\"top_k\":2,\"query\":\"how are refunds paid for ${question}\"}}");
            var ctx = PackagesTestSupport.ctx(w, Map.of());
            new KnowledgePackage().buildIndex(ctx, List.of("policies"), null, "kb/policies.json", null, null, null, null, null, 1000, 150, false, null, null);

            Map<String, Object> r = new AiSkillsPackage().run(ctx, "refund_check", Map.of("question", "INV-77"), null, KEY, null, f.url() + "/v1", null, null,
                    null, null, true, 0, null, null, List.of("invoice.txt"));
            String sent = f.last("POST", "/v1/chat/completions").text();
            assertTrue(sent.contains("Refunds are paid within 14 days"), "the best passage goes with the prompt: " + sent);
            assertTrue(sent.contains("[1] (") && sent.contains("cite"), sent);
            assertTrue(sent.contains("Document: invoice.txt") && sent.contains("INV-77 total 120 EUR"), "the document goes with the prompt");
            assertTrue(sent.contains("Task:") && sent.contains("Is the refund for INV-77 on time?"));
            List<Object> sources = (List<Object>) r.get("sources");
            assertFalse(sources.isEmpty());
            assertTrue(String.valueOf(((Map<?, ?>) sources.get(0)).get("source")).contains("refunds.md"));
            assertEquals("Refunds within 14 days [1].", r.get("text"));

            // without knowledge or files the prompt is sent as before, and sources is empty
            Files.writeString(w.resolve("ai-skills/plain.json"), "{\"prompt\":\"Say hi\",\"provider\":\"openai\",\"model\":\"gpt-test\"}");
            Map<String, Object> plain = new AiSkillsPackage().run(ctx, "plain", null, null, KEY, null, f.url() + "/v1", null, null);
            assertEquals(List.of(), plain.get("sources"));
            assertTrue(f.last("POST", "/v1/chat/completions").text().contains("\"content\":\"Say hi\""));
            // the run's index input overrides the skill's, and a missing document is a clear error
            assertEquals("FILE.NOT_FOUND", assertThrows(ActionException.class, () -> new AiSkillsPackage().run(ctx, "plain", null, null, KEY, null, f.url() + "/v1", null, null,
                    null, null, true, 0, "kb/policies.json", 1, List.of("missing.pdf"))).code());
        }
    }
}
