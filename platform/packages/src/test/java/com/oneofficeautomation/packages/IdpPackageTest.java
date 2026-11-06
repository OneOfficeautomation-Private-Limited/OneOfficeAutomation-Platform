package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** G59: classify documents (keywords, then AI), extract by rules, and AI extraction that learns from validated examples. */
class IdpPackageTest {
    @TempDir Path w;
    final IdpPackage p = new IdpPackage();
    static final SecureString KEY = new SecureString("sk", "k");
    static final List<Object> TYPES = List.of(
            Map.of("name", "invoice", "keywords", List.of("invoice", "VAT", "amount due"), "description", "a supplier invoice"),
            Map.of("name", "purchase_order", "keywords", List.of("purchase order", "PO number", "deliver to"), "description", "an order we send"));

    @Test void keywordsDecideWhenClearAndTheModelWhenNot() throws Exception {
        ActionContext ctx = PackagesTestSupport.ctx(w, Map.of());
        Map<String, Object> k = p.classify(ctx, "INVOICE 42\nVAT 20%\nAmount due: 120.00", TYPES, "keywords_then_ai", null, "openai", null, null, null, 0.5, true);
        assertEquals("invoice", k.get("type"));
        assertEquals("keywords", k.get("method"));
        assertEquals(new BigDecimal("1.0"), new BigDecimal(String.valueOf(k.get("confidence"))));
        assertEquals("unknown", p.classify(ctx, "Dear customer, thanks.", TYPES, "keywords", null, "openai", null, null, null, 0.5, true).get("type"));
        try (ConnectorsTest.Fake llm = new ConnectorsTest.Fake()) {
            List<String> asked = new ArrayList<>();
            llm.on("POST /v1/chat/completions", c -> { asked.add(c.text()); return ConnectorsTest.ok("{\"choices\":[{\"message\":{\"content\":\"{\\\"type\\\": \\\"purchase_order\\\", \\\"confidence\\\": 0.8}\"}}]}"); });
            Map<String, Object> a = p.classify(ctx, "Please supply 40 chairs to our Pune office.", TYPES, "keywords_then_ai", null, "openai_compatible", "m", KEY, llm.url() + "/v1", 0.5, false);
            assertEquals("purchase_order", a.get("type"));
            assertEquals("ai", a.get("method"));
            assertTrue(asked.get(0).contains("purchase_order: an order we send"));
            llm.on("POST /v1/chat/completions", c -> ConnectorsTest.ok("{\"choices\":[{\"message\":{\"content\":\"{\\\"type\\\": \\\"recipe\\\", \\\"confidence\\\": 0.9}\"}}]}"));
            assertEquals("unknown", p.classify(ctx, "x", TYPES, "ai", null, "openai_compatible", "m", KEY, llm.url() + "/v1", 0.5, false).get("type"), "a type not in the list is unknown");
        }
    }

    @Test void rulesExtractWithoutAi() {
        ActionContext ctx = PackagesTestSupport.ctx(w, Map.of());
        Map<String, Object> r = p.extractByRules(ctx, "Invoice no: INV-2041\nTotal due: 1,250.00 EUR\nDue 30 days",
                Map.of("number", Map.of("regex", "INV-(\\d+)"), "total", Map.of("after", "total due", "until", " EUR"), "po", Map.of("regex", "PO-\\d+")));
        assertEquals(Map.of("number", "2041", "total", "1,250.00"), r.get("fields"));
        assertEquals(List.of("po"), r.get("missing"));
        assertEquals("IDP.BAD_RULE", assertThrows(ActionException.class, () -> p.extractByRules(ctx, "x", Map.of("a", Map.of("regex", "(")))).code());
    }

    @Test void validatedExamplesGoWithTheRequest() throws Exception {
        ActionContext ctx = PackagesTestSupport.ctx(w, Map.of());
        for (int i = 1; i <= 4; i++) p.saveExample(ctx, "ex/invoice.examples.json", "invoice", "INVOICE " + i + " from Acme", Map.of("number", "A-" + i));
        assertTrue(Files.readString(w.resolve("ex/invoice.examples.json")).contains("A-4"));
        try (ConnectorsTest.Fake llm = new ConnectorsTest.Fake()) {
            List<String> asked = new ArrayList<>();
            llm.on("POST /v1/chat/completions", c -> { asked.add(c.text()); return ConnectorsTest.ok("{\"choices\":[{\"message\":{\"content\":\"{\\\"number\\\": \\\"A-9\\\", \\\"extra\\\": 1}\"}}]}"); });
            Map<String, Object> r = p.extractWithExamples(ctx, "INVOICE 9 from Acme", Map.of("number", "the invoice number"), "ex/invoice.examples.json", "invoice", 2,
                    null, "openai_compatible", "m", KEY, llm.url() + "/v1", false);
            assertEquals(Map.of("number", "A-9"), r.get("fields"), "only the asked fields");
            assertEquals(2, r.get("examples_used"));
            assertTrue(asked.get(0).contains("A-4") && asked.get(0).contains("A-3") && !asked.get(0).contains("A-2"), "the newest two examples");
        }
    }
}
