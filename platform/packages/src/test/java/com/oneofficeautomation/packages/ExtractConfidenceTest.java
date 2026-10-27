package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;

import static com.oneofficeautomation.packages.ConnectorsTest.*;
import static com.oneofficeautomation.packages.PackagesTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** A1: Extract fields with confidence, and the validation request it makes when a field is uncertain. */
class ExtractConfidenceTest {
    static final SecureString KEY = new SecureString("sk-test", "k");

    @SuppressWarnings("unchecked")
    @Test void uncertainFieldsAskForAPerson() throws Exception {
        try (Fake f = new Fake()) {
            String answer = "{\\\"values\\\":{\\\"invoice_no\\\":\\\"INV-77\\\",\\\"total\\\":\\\"1O0.00\\\",\\\"po\\\":null},\\\"confidence\\\":{\\\"invoice_no\\\":0.97,\\\"total\\\":0.41,\\\"po\\\":0.9}}";
            f.on("POST /v1/chat/completions", c -> ok("{\"choices\":[{\"message\":{\"content\":\"" + answer + "\"}}],\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":9}}"));
            Path w = tmp();
            var ctx = PackagesTestSupport.ctx(w, Map.of());
            Map<String, Object> fields = new LinkedHashMap<>(Map.of("invoice_no", "the invoice id", "total", "amount due", "po", "purchase order"));
            Map<String, Object> r = new GenAiPackage().extract(ctx, "Invoice INV-77, total 1O0.00", fields, "openai", null, "gpt-test", KEY, f.url() + "/v1", false, null, null, 0.8);
            assertTrue(f.last("POST", "/v1/chat/completions").text().contains("confidence"), "the model is asked how sure it is");
            Map<String, Object> conf = (Map<String, Object>) r.get("confidence");
            assertEquals(0, new BigDecimal("0.41").compareTo((BigDecimal) conf.get("total")));
            assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) conf.get("po")), "a missing value counts as unsure");
            assertEquals(true, r.get("needs_review"));
            Map<String, Object> v = (Map<String, Object>) r.get("validation");
            assertEquals("INV-77", ((Map<String, Object>) v.get("fields")).get("invoice_no"));

            // without min_confidence: just the values, as before
            f.on("POST /v1/chat/completions", c -> ok("{\"choices\":[{\"message\":{\"content\":\"{\\\"invoice_no\\\":\\\"INV-77\\\",\\\"total\\\":\\\"100\\\",\\\"po\\\":null}\"}}],\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":9}}"));
            Map<String, Object> plain = new GenAiPackage().extract(ctx, "Invoice INV-77", fields, "openai", null, "gpt-test", KEY, f.url() + "/v1", false, null, null);
            assertEquals(Set.of("fields"), plain.keySet());
            assertEquals("100", ((Map<String, Object>) plain.get("fields")).get("total"));
        }
    }
}
