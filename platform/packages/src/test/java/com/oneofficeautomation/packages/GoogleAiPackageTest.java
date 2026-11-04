package com.oneofficeautomation.packages;

import com.oneofficeautomation.packages.ConnectorsTest.Call;
import com.oneofficeautomation.packages.ConnectorsTest.Fake;
import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.SecureString;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static com.oneofficeautomation.packages.RestToolkitTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Google Cloud AI toolkit against a local imitation of the Natural Language, Vision and Document AI APIs. */
class GoogleAiPackageTest {
    final GoogleAiPackage pkg = new GoogleAiPackage();
    static final SecureString KEY = new SecureString("AIzaTestKey", "google/ai/key"), TOKEN = new SecureString("ya29.token", "google/ai/token");

    static Object[] ok(Object v) { return new Object[]{200, JsonLite.write(v), Map.of("Content-Type", "application/json")}; }

    ActionContext connected(Fake f, Path work, boolean token) {
        ActionContext ctx = ctx(work);
        pkg.connect(ctx, token ? TOKEN : KEY, token ? "access_token" : "api_key", "acme-ai", "eu", f.url(), "google_ai");
        return ctx;
    }

    @Test void naturalLanguageSentimentAndEntities() throws Exception {
        try (Fake f = new Fake()) {
            f.on("POST /v1/documents:analyzeSentiment", c -> ok(Map.of("documentSentiment", Map.of("score", new BigDecimal("0.8"), "magnitude", new BigDecimal("1.6")), "language", "en",
                    "sentences", List.of(Map.of("text", Map.of("content", "Lovely staff.", "beginOffset", 0), "sentiment", Map.of("score", new BigDecimal("0.9"), "magnitude", new BigDecimal("0.9")))))))
             .on("POST /v1/documents:analyzeEntities", c -> ok(Map.of("language", "en", "entities", List.of(
                    Map.of("name", "Google", "type", "ORGANIZATION", "salience", new BigDecimal("0.7"), "metadata", Map.of("wikipedia_url", "https://en.wikipedia.org/wiki/Google", "mid", "/m/045c7b"),
                            "mentions", List.of(Map.of(), Map.of())),
                    Map.of("name", "Mountain View", "type", "LOCATION", "salience", new BigDecimal("0.3"), "metadata", Map.of(), "mentions", List.of(Map.of()))))));
            ActionContext ctx = connected(f, PackagesTest.tmp(), false);
            Map<String, Object> s = pkg.languageSentiment(ctx, "google_ai", "Lovely staff.", null);
            Call c = f.last("POST", "/v1/documents:analyzeSentiment");
            assertEquals("AIzaTestKey", c.header("x-goog-api-key"), "the API key goes in a header, not the address");
            assertNull(c.query());
            assertNull(c.auth());
            Map<?, ?> doc = (Map<?, ?>) c.json().get("document");
            assertEquals("PLAIN_TEXT", doc.get("type"));
            assertEquals("Lovely staff.", doc.get("content"));
            assertFalse(doc.containsKey("language"), "an empty language is detected by Google");
            assertEquals("positive", s.get("sentiment"));
            assertEquals(new BigDecimal("0.8"), s.get("score"));
            assertEquals(List.of(List.of("Lovely staff.", new BigDecimal("0.9"), new BigDecimal("0.9"))), rows(s.get("sentences")));

            Map<String, Object> e = pkg.languageEntities(ctx, "google_ai", "Google is in Mountain View", "en");
            assertEquals("en", ((Map<?, ?>) f.last("POST", "/v1/documents:analyzeEntities").json().get("document")).get("language"));
            assertEquals(new BigDecimal("2"), e.get("count"));
            assertEquals(List.of("Google", "ORGANIZATION", new BigDecimal("0.7"), new BigDecimal("2"), "https://en.wikipedia.org/wiki/Google"), rows(e.get("entities")).get(0));
            fails("GOOGLE_AI.NO_TEXT", () -> pkg.languageEntities(ctx, "google_ai", null, null));
            fails("GOOGLE_AI.BAD_LANGUAGE", () -> pkg.languageEntities(ctx, "google_ai", "x", "Deutsch!"));
        }
    }

    @Test void visionLabelsAndOcr() throws Exception {
        try (Fake f = new Fake()) {
            f.on("POST /v1/images:annotate", c -> {
                Map<?, ?> req = (Map<?, ?>) ((List<?>) c.json().get("requests")).get(0);
                String type = String.valueOf(((Map<?, ?>) ((List<?>) req.get("features")).get(0)).get("type"));
                if (type.equals("LABEL_DETECTION")) return ok(Map.of("responses", List.of(Map.of("labelAnnotations", List.of(
                        Map.of("mid", "/m/0jbk", "description", "Invoice", "score", new BigDecimal("0.95"), "topicality", new BigDecimal("0.95")),
                        Map.of("mid", "/m/1", "description", "Paper", "score", new BigDecimal("0.4"), "topicality", new BigDecimal("0.4")))))));
                if (type.equals("TEXT_DETECTION")) return ok(Map.of("responses", List.of(Map.of("error", Map.of("code", 3, "message", "Bad image data.")))));
                return ok(Map.of("responses", List.of(Map.of("fullTextAnnotation", Map.of("text", "TOTAL 9.99\n"),
                        "textAnnotations", List.of(Map.of("locale", "en", "description", "TOTAL 9.99\n"), Map.of("description", "TOTAL"), Map.of("description", "9.99"))))));
            });
            Path w = PackagesTest.tmp();
            Files.write(w.resolve("r.png"), new byte[]{(byte) 0x89, 'P', 'N', 'G'});
            ActionContext ctx = connected(f, w, true);
            Map<String, Object> l = pkg.visionLabels(ctx, "google_ai", "r.png", 5, 0.5);
            Call c = f.last("POST", "/v1/images:annotate");
            assertEquals("Bearer ya29.token", c.auth());
            assertEquals("acme-ai", c.header("x-goog-user-project"), "an access token bills the project given to Connect");
            Map<?, ?> req = (Map<?, ?>) ((List<?>) c.json().get("requests")).get(0);
            assertEquals(Base64.getEncoder().encodeToString(new byte[]{(byte) 0x89, 'P', 'N', 'G'}), ((Map<?, ?>) req.get("image")).get("content"));
            assertEquals(new BigDecimal("5"), ((Map<?, ?>) ((List<?>) req.get("features")).get(0)).get("maxResults"));
            assertEquals(List.of("Invoice"), l.get("label_names"), "labels below min_score are left out");

            Map<String, Object> o = pkg.visionOcr(ctx, "google_ai", "r.png", "document", List.of("en", "hi"));
            Map<?, ?> oreq = (Map<?, ?>) ((List<?>) f.last("POST", "/v1/images:annotate").json().get("requests")).get(0);
            assertEquals(List.of("en", "hi"), ((Map<?, ?>) oreq.get("imageContext")).get("languageHints"));
            assertEquals("TOTAL 9.99\n", o.get("text"));
            assertEquals("en", o.get("language"));
            assertEquals(List.of(List.of("TOTAL"), List.of("9.99")), rows(o.get("words")));
            ActionException bad = fails("GOOGLE_AI.IMAGE_ERROR", () -> pkg.visionOcr(ctx, "google_ai", "r.png", "text", null));
            assertTrue(bad.getMessage().contains("Bad image data"), bad.getMessage());
            Files.write(w.resolve("big.png"), new byte[(int) GoogleAiPackage.MAX_IMAGE + 1]);
            fails("GOOGLE_AI.FILE_TOO_LARGE", () -> pkg.visionLabels(ctx, "google_ai", "big.png", 5, 0));
        }
    }

    static Map<String, Object> seg(int start, int end) { return Map.of("textAnchor", Map.of("textSegments", List.of(Map.of("startIndex", String.valueOf(start), "endIndex", String.valueOf(end))))); }

    @Test void documentAiReturnsTextEntitiesFormFieldsAndTables() throws Exception {
        String text = "Invoice INV-7\nItem Qty\nPens 2\nTotal: 110.00\n";
        try (Fake f = new Fake()) {
            f.on("POST /v1/projects/acme-ai/locations/eu/processors/abc123(/processorVersions/v2)?:process", c -> ok(Map.of("document", Map.of(
                    "text", text,
                    "entities", List.of(
                            Map.of("type", "invoice_id", "mentionText", "INV-7", "confidence", new BigDecimal("0.99")),
                            Map.of("type", "total_amount", "mentionText", "110.00", "normalizedValue", Map.of("text", "110"), "confidence", new BigDecimal("0.9")),
                            Map.of("type", "line_item", "mentionText", "Pens 2", "properties", List.of(Map.of("type", "description", "mentionText", "Pens"))),
                            Map.of("type", "line_item", "textAnchor", Map.of("textSegments", List.of(Map.of("startIndex", "14", "endIndex", "22"))))),
                    "pages", List.of(Map.of("pageNumber", 1,
                            "formFields", List.of(Map.of("fieldName", seg(30, 36), "fieldValue", Map.of("textAnchor", Map.of("textSegments", List.of(Map.of("startIndex", "37", "endIndex", "43"))), "confidence", new BigDecimal("0.8")))),
                            "tables", List.of(Map.of(
                                    "headerRows", List.of(Map.of("cells", List.of(Map.of("layout", seg(14, 18)), Map.of("layout", seg(19, 22))))),
                                    "bodyRows", List.of(Map.of("cells", List.of(Map.of("layout", seg(23, 27)), Map.of("layout", seg(28, 29)))))))))))));
            Path w = PackagesTest.tmp();
            Files.write(w.resolve("inv.pdf"), "%PDF-1.4".getBytes());
            ActionContext ctx = connected(f, w, true);
            Map<String, Object> r = pkg.documentaiProcess(ctx, "google_ai", "abc123", "inv.pdf", null, null, true);
            Call c = f.last("POST", ".*:process");
            assertEquals("Bearer ya29.token", c.auth());
            Map<?, ?> raw = (Map<?, ?>) c.json().get("rawDocument");
            assertEquals("application/pdf", raw.get("mimeType"));
            assertEquals(Base64.getEncoder().encodeToString("%PDF-1.4".getBytes()), raw.get("content"));
            assertEquals(text, r.get("text"));
            Map<?, ?> fields = (Map<?, ?>) r.get("fields");
            assertEquals("INV-7", fields.get("invoice_id"));
            assertEquals(List.of("Pens 2", "Item Qty"), fields.get("line_item"), "a repeated type collects a list");
            assertEquals("Pens", fields.get("line_item/description"));
            assertEquals(List.of("total_amount", "110.00", "110", new BigDecimal("0.9")), rows(r.get("entities")).get(1));
            assertEquals(List.of(List.of("Total:", "110.00", new BigDecimal("0.8"), BigDecimal.ONE)), rows(r.get("form_fields")));
            List<?> tables = (List<?>) r.get("tables");
            assertEquals(List.of("Item", "Qty"), cols(tables.get(0)));
            assertEquals(List.of(List.of("Pens", "2")), rows(tables.get(0)));
            assertEquals(BigDecimal.ONE, r.get("page_count"));

            pkg.documentaiProcess(ctx, "google_ai", "abc123", "inv.pdf", "v2", "application/pdf", false);
            assertTrue(f.last("POST", ".*:process").path().endsWith("/processors/abc123/processorVersions/v2:process"));

            fails("GOOGLE_AI.BAD_PROCESSOR", () -> pkg.documentaiProcess(ctx, "google_ai", "../x", "inv.pdf", null, null, true));
            Files.write(w.resolve("inv.xyz"), new byte[]{1});
            fails("GOOGLE_AI.UNKNOWN_FILE_TYPE", () -> pkg.documentaiProcess(ctx, "google_ai", "abc123", "inv.xyz", null, null, true));
            ActionContext keyCtx = connected(f, w, false);
            fails("GOOGLE_AI.TOKEN_REQUIRED", () -> pkg.documentaiProcess(keyCtx, "google_ai", "abc123", "inv.pdf", null, null, true));
            ActionContext noProject = ctx(w);
            pkg.connect(noProject, TOKEN, "access_token", null, "us", f.url(), "google_ai");
            fails("GOOGLE_AI.NO_PROJECT", () -> pkg.documentaiProcess(noProject, "google_ai", "abc123", "inv.pdf", null, null, true));
        }
    }

    @Test void googleErrorsBecomeClearErrors() throws Exception {
        try (Fake f = new Fake()) {
            f.on("POST /v1/documents:analyzeSentiment", c -> switch (String.valueOf(((Map<?, ?>) c.json().get("document")).get("content"))) {
                case "badkey" -> new Object[]{400, "{\"error\":{\"code\":400,\"message\":\"API key not valid. Please pass a valid API key.\",\"status\":\"INVALID_ARGUMENT\","
                        + "\"details\":[{\"@type\":\"type.googleapis.com/google.rpc.ErrorInfo\",\"reason\":\"API_KEY_INVALID\"}]}}"};
                case "401" -> new Object[]{401, "{\"error\":{\"code\":401,\"message\":\"Request had invalid authentication credentials.\",\"status\":\"UNAUTHENTICATED\"}}"};
                case "403" -> new Object[]{403, "{\"error\":{\"code\":403,\"message\":\"The caller does not have permission\",\"status\":\"PERMISSION_DENIED\"}}"};
                case "disabled" -> new Object[]{403, "{\"error\":{\"code\":403,\"message\":\"Cloud Natural Language API has not been used in project 1 before or it is disabled.\",\"status\":\"PERMISSION_DENIED\","
                        + "\"details\":[{\"reason\":\"SERVICE_DISABLED\"}]}}"};
                case "404" -> new Object[]{404, "{\"error\":{\"code\":404,\"message\":\"Not found\",\"status\":\"NOT_FOUND\"}}"};
                case "429" -> new Object[]{429, "{\"error\":{\"code\":429,\"message\":\"Quota exceeded\",\"status\":\"RESOURCE_EXHAUSTED\"}}"};
                default -> new Object[]{503, "{\"error\":{\"code\":503,\"message\":\"The service is currently unavailable.\",\"status\":\"UNAVAILABLE\"}}"};
            });
            ActionContext ctx = connected(f, PackagesTest.tmp(), false);
            ActionException key = fails("GOOGLE_AI.AUTH_FAILED", () -> pkg.languageSentiment(ctx, "google_ai", "badkey", null));
            assertTrue(key.suggestedFix().contains("API key"), key.suggestedFix());
            assertEquals("API_KEY_INVALID", key.details().get("service_code"));
            fails("GOOGLE_AI.AUTH_FAILED", () -> pkg.languageSentiment(ctx, "google_ai", "401", null));
            assertTrue(fails("GOOGLE_AI.FORBIDDEN", () -> pkg.languageSentiment(ctx, "google_ai", "403", null)).suggestedFix().contains("vault"));
            fails("GOOGLE_AI.API_NOT_ENABLED", () -> pkg.languageSentiment(ctx, "google_ai", "disabled", null));
            fails("GOOGLE_AI.NOT_FOUND", () -> pkg.languageSentiment(ctx, "google_ai", "404", null));
            assertTrue(fails("GOOGLE_AI.RATE_LIMITED", () -> pkg.languageSentiment(ctx, "google_ai", "429", null)).retryable());
            assertTrue(fails("GOOGLE_AI.SERVER_ERROR", () -> pkg.languageSentiment(ctx, "google_ai", "503", null)).retryable());
            fails("GOOGLE_AI.NO_CREDENTIAL", () -> pkg.connect(ctx, null, "api_key", null, "us", null, "x"));
            fails("GOOGLE_AI.BAD_ENDPOINT", () -> pkg.connect(ctx, KEY, "api_key", null, "us", "http://example.com", "x"));
            pkg.disconnect(ctx, "google_ai");
            assertFalse(ctx.sessions().containsKey("google_ai"));
        }
    }

    @Test void defaultAddresses() {
        GoogleAiPackage.GoogleAiSession s = new GoogleAiPackage.GoogleAiSession(KEY, false, "p", "eu", null);
        assertEquals("https://eu-documentai.googleapis.com", s.url("eu-documentai", "https://eu-documentai.googleapis.com"));
        assertEquals("https://psc.example.com/vision", new GoogleAiPackage.GoogleAiSession(KEY, false, "p", "eu", "https://psc.example.com/{service}").url("vision", "x"));
    }
}
