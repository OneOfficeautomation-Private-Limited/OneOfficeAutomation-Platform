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
import java.util.concurrent.atomic.AtomicInteger;

import static com.oneofficeautomation.packages.RestToolkitTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Azure AI toolkit against a local imitation of the Language, Document Intelligence and Image Analysis APIs. */
class AzureAiPackageTest {
    final AzureAiPackage pkg = new AzureAiPackage();
    static final SecureString KEY = new SecureString("az-key-123", "azure/ai/key");

    static Object[] ok(Object v) { return new Object[]{200, JsonLite.write(v), Map.of("Content-Type", "application/json")}; }

    static Map<String, Object> docResult(Object document) { return Map.of("kind", "x", "results", Map.of("documents", List.of(document), "errors", List.of(), "modelVersion", "2024-01-01")); }

    ActionContext connected(Fake f, Path work, String type) {
        ActionContext ctx = ctx(work);
        pkg.connect(ctx, f.url() + "/", KEY, type, "azure_ai");
        return ctx;
    }

    @Test void languageSentimentEntitiesAndPersonalData() throws Exception {
        try (Fake f = new Fake()) {
            f.on("POST /language/:analyze-text", c -> switch (String.valueOf(c.json().get("kind"))) {
                case "SentimentAnalysis" -> ok(docResult(Map.of("id", "1", "sentiment", "mixed", "confidenceScores", Map.of("positive", new BigDecimal("0.5"), "neutral", BigDecimal.ZERO, "negative", new BigDecimal("0.5")),
                        "sentences", List.of(Map.of("text", "Food was great.", "sentiment", "positive", "confidenceScores", Map.of("positive", new BigDecimal("0.99"), "neutral", BigDecimal.ZERO, "negative", new BigDecimal("0.01"))),
                                Map.of("text", "Service was slow.", "sentiment", "negative", "confidenceScores", Map.of("positive", BigDecimal.ZERO, "neutral", BigDecimal.ZERO, "negative", BigDecimal.ONE))),
                        "warnings", List.of(Map.of("code", "LongWordsInDocument", "message", "long words")))));
                case "EntityRecognition" -> ok(docResult(Map.of("id", "1", "entities", List.of(
                        Map.of("text", "Contoso", "category", "Organization", "offset", 0, "length", 7, "confidenceScore", new BigDecimal("0.98")),
                        Map.of("text", "Monday", "category", "DateTime", "subcategory", "Date", "offset", 20, "length", 6, "confidenceScore", new BigDecimal("0.3"))))));
                default -> ok(docResult(Map.of("id", "1", "redactedText", "Call ************ now", "entities", List.of(
                        Map.of("text", "555-0100-2000", "category", "PhoneNumber", "offset", 5, "length", 12, "confidenceScore", new BigDecimal("0.8"))))));
            });
            ActionContext ctx = connected(f, PackagesTest.tmp(), "key");
            Map<String, Object> s = pkg.languageSentiment(ctx, "azure_ai", "Food was great. Service was slow.", "en");
            Call c = f.last("POST", "/language/:analyze-text");
            assertEquals("az-key-123", c.header("Ocp-Apim-Subscription-Key"));
            assertNull(c.auth());
            assertTrue(c.query().contains("api-version=2023-04-01"), c.query());
            Map<String, Object> body = c.json();
            assertEquals("SentimentAnalysis", body.get("kind"));
            Map<?, ?> doc = (Map<?, ?>) ((List<?>) ((Map<?, ?>) body.get("analysisInput")).get("documents")).get(0);
            assertEquals("Food was great. Service was slow.", doc.get("text"));
            assertEquals("en", doc.get("language"));
            assertEquals("mixed", s.get("sentiment"));
            assertEquals(List.of("text", "sentiment", "positive", "neutral", "negative"), cols(s.get("sentences")));
            assertEquals("negative", rows(s.get("sentences")).get(1).get(1));

            Map<String, Object> e = pkg.languageEntities(ctx, "azure_ai", "Contoso pays on Monday", "en", 0.5);
            assertEquals(BigDecimal.ONE, e.get("count"));
            assertEquals("Organization", rows(e.get("entities")).get(0).get(1));

            Map<String, Object> p = pkg.languagePii(ctx, "azure_ai", "Call 555-0100-2000 now", "en", List.of("PhoneNumber"), "phi", 0);
            Map<?, ?> params = (Map<?, ?>) f.last("POST", "/language/:analyze-text").json().get("parameters");
            assertEquals("phi", params.get("domain"));
            assertEquals(List.of("PhoneNumber"), params.get("piiCategories"));
            assertEquals("Call ************ now", p.get("redacted_text"));
            assertEquals("555-0100-2000", rows(p.get("entities")).get(0).get(0));

            fails("AZURE_AI.TEXT_TOO_LONG", () -> pkg.languageSentiment(ctx, "azure_ai", "x".repeat(5121), "en"));
            fails("AZURE_AI.NO_TEXT", () -> pkg.languageEntities(ctx, "azure_ai", "", "en", 0));
        }
    }

    @Test void languageDocumentErrorsAndBearerTokens() throws Exception {
        try (Fake f = new Fake()) {
            f.on("POST /language/:analyze-text", c -> ok(Map.of("kind", "x", "results", Map.of("documents", List.of(),
                    "errors", List.of(Map.of("id", "1", "error", Map.of("code", "InvalidArgument", "message", "Invalid Language Code.",
                            "innererror", Map.of("code", "UnsupportedLanguageCode", "message", "Invalid language code 'xx'.")))))))) ;
            ActionContext ctx = connected(f, PackagesTest.tmp(), "access_token");
            ActionException e = fails("AZURE_AI.DOCUMENT_ERROR", () -> pkg.languageSentiment(ctx, null, "Hello", "xx"));
            assertTrue(e.getMessage().contains("UnsupportedLanguageCode"), e.getMessage());
            assertEquals("Bearer az-key-123", f.last("POST", "/language/:analyze-text").auth());
            assertNull(f.last("POST", "/language/:analyze-text").header("Ocp-Apim-Subscription-Key"));
        }
    }

    static Map<String, Object> invoiceResult() {
        Map<String, Object> items = Map.of("type", "array", "valueArray", List.of(
                Map.of("type", "object", "valueObject", new TreeMap<>(Map.of("Description", Map.of("type", "string", "valueString", "Pens", "content", "Pens"),
                        "Amount", Map.of("type", "currency", "valueCurrency", Map.of("amount", new BigDecimal("12.5"), "currencyCode", "USD"), "content", "$12.50")))),
                Map.of("type", "object", "valueObject", Map.of("Description", Map.of("type", "string", "valueString", "Paper", "content", "Paper")))));
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("InvoiceId", Map.of("type", "string", "valueString", "INV-100", "content", "INV-100", "confidence", new BigDecimal("0.97")));
        fields.put("InvoiceDate", Map.of("type", "date", "valueDate", "2026-10-01", "content", "1 Oct 2026"));
        fields.put("InvoiceTotal", Map.of("type", "currency", "valueCurrency", Map.of("amount", new BigDecimal("110")), "content", "$110.00"));
        fields.put("CustomerAddress", Map.of("type", "address", "valueAddress", Map.of("city", "Redmond"), "content", "1 Way, Redmond"));
        fields.put("Items", items);
        Map<String, Object> table = Map.of("rowCount", 3, "columnCount", 2, "cells", List.of(
                Map.of("kind", "columnHeader", "rowIndex", 0, "columnIndex", 0, "content", "Item"),
                Map.of("kind", "columnHeader", "rowIndex", 0, "columnIndex", 1, "content", "Amount"),
                Map.of("rowIndex", 1, "columnIndex", 0, "content", "Pens"), Map.of("rowIndex", 1, "columnIndex", 1, "content", "12.50"),
                Map.of("rowIndex", 2, "columnIndex", 0, "columnSpan", 2, "content", "Total 110.00")));
        return Map.of("apiVersion", "2024-11-30", "modelId", "prebuilt-invoice", "content", "INVOICE INV-100\nPens 12.50",
                "pages", List.of(Map.of("pageNumber", 1, "lines", List.of(Map.of("content", "INVOICE INV-100"), Map.of("content", "Pens 12.50")))),
                "languages", List.of(Map.of("locale", "en", "confidence", 1)),
                "tables", List.of(table),
                "keyValuePairs", List.of(Map.of("key", Map.of("content", "Order no."), "value", Map.of("content", "4711"), "confidence", new BigDecimal("0.9"))),
                "documents", List.of(Map.of("docType", "invoice", "fields", fields, "confidence", 1)));
    }

    @Test void documentIntelligenceFollowsTheOperationUntilItSucceeds() throws Exception {
        try (Fake f = new Fake()) {
            AtomicInteger polls = new AtomicInteger();
            f.on("POST /documentintelligence/documentModels/[^/]+:analyze", c -> {
                String model = c.path().replaceAll(".*/documentModels/([^:]+):analyze", "$1");
                return new Object[]{202, "", Map.of("Operation-Location", f.url() + "/documentintelligence/documentModels/" + model + "/analyzeResults/r1?api-version=2024-11-30", "Retry-After", "0")};
            }).on("GET /documentintelligence/documentModels/.*/analyzeResults/r1", c -> polls.incrementAndGet() < 3
                    ? new Object[]{200, "{\"status\":\"running\"}", Map.of("Retry-After", "0")}
                    : ok(Map.of("status", "succeeded", "analyzeResult", invoiceResult())));
            Path w = PackagesTest.tmp();
            Files.write(w.resolve("invoice.pdf"), "%PDF-1.7 test".getBytes());
            ActionContext ctx = connected(f, w, "key");

            Map<String, Object> r = pkg.documentAnalyze(ctx, "azure_ai", "invoice.pdf", "prebuilt-invoice", true, "1-2", "en-US", 30);
            Call start = f.last("POST", "/documentintelligence/documentModels/prebuilt-invoice:analyze");
            assertTrue(start.query().contains("api-version=2024-11-30"), start.query());
            assertTrue(start.query().contains("pages=1-2") && start.query().contains("locale=en-US") && start.query().contains("features=keyValuePairs"), start.query());
            assertEquals(Base64.getEncoder().encodeToString("%PDF-1.7 test".getBytes()), start.json().get("base64Source"));
            assertEquals("az-key-123", f.last("GET", ".*analyzeResults.*").header("Ocp-Apim-Subscription-Key"), "the status checks carry the key");
            assertEquals(3, polls.get(), "it asked until the analysis had succeeded");

            assertEquals("invoice", r.get("document_type"));
            Map<?, ?> fields = (Map<?, ?>) r.get("fields");
            assertEquals("INV-100", fields.get("InvoiceId"));
            assertEquals("2026-10-01", fields.get("InvoiceDate"));
            assertEquals(new BigDecimal("110"), fields.get("InvoiceTotal"));
            assertEquals("1 Way, Redmond", fields.get("CustomerAddress"));
            Object items = fields.get("Items");
            assertEquals(List.of("Amount", "Description"), cols(items), "line items become a Data Table");
            assertEquals(List.of(new BigDecimal("12.5"), "Pens"), rows(items).get(0));
            assertEquals(Arrays.asList(null, "Paper"), rows(items).get(1));
            assertEquals(5, rows(r.get("fields_table")).size());
            assertEquals(List.of(List.of("Order no.", "4711", new BigDecimal("0.9"))), rows(r.get("key_values")));
            List<?> tables = (List<?>) r.get("tables");
            assertEquals(BigDecimal.ONE, r.get("table_count"));
            assertEquals(List.of("Item", "Amount"), cols(tables.get(0)));
            assertEquals(List.of(List.of("Pens", "12.50"), List.of("Total 110.00", "Total 110.00")), rows(tables.get(0)), "a spanned cell fills the cells it covers");

            polls.set(0);
            Map<String, Object> read = pkg.documentRead(ctx, "azure_ai", "invoice.pdf", null, null, 30);
            assertNotNull(f.last("POST", "/documentintelligence/documentModels/prebuilt-read:analyze"));
            assertEquals("INVOICE INV-100\nPens 12.50", read.get("text"));
            assertEquals(BigDecimal.ONE, read.get("page_count"));
            assertEquals(List.of("en"), read.get("languages"));
            assertEquals(2, rows(read.get("lines")).size());

            fails("AZURE_AI.BAD_PAGES", () -> pkg.documentRead(ctx, "azure_ai", "invoice.pdf", "first", null, 30));
            fails("AZURE_AI.BAD_MODEL", () -> pkg.documentAnalyze(ctx, "azure_ai", "invoice.pdf", "prebuilt-tax", false, null, null, 30));
            fails("AZURE_AI.FILE_NOT_FOUND", () -> pkg.documentRead(ctx, "azure_ai", "nope.pdf", null, null, 30));
        }
    }

    @Test void documentIntelligenceFailsTimesOutAndStaysOnTheResourceHost() throws Exception {
        try (Fake f = new Fake()) {
            f.on("POST /documentintelligence/documentModels/prebuilt-layout:analyze",
                    c -> new Object[]{202, "", Map.of("Operation-Location", f.url() + "/documentintelligence/documentModels/prebuilt-layout/analyzeResults/slow", "Retry-After", "0")})
             .on("POST /documentintelligence/documentModels/prebuilt-receipt:analyze",
                    c -> new Object[]{202, "", Map.of("Operation-Location", f.url() + "/documentintelligence/documentModels/prebuilt-receipt/analyzeResults/bad", "Retry-After", "0")})
             .on("POST /documentintelligence/documentModels/prebuilt-read:analyze",
                    c -> new Object[]{202, "", Map.of("Operation-Location", "https://evil.example.com/steal")})
             .on("POST /documentintelligence/documentModels/prebuilt-invoice:analyze", c -> new Object[]{202, ""})
             .on("GET .*/slow", c -> new Object[]{200, "{\"status\":\"running\"}", Map.of("Retry-After", "0")})
             .on("GET .*/bad", c -> ok(Map.of("status", "failed", "error", Map.of("code", "InvalidRequest", "message", "Invalid request.",
                    "innererror", Map.of("code", "InvalidContent", "message", "The file is corrupted or format is unsupported.")))));
            Path w = PackagesTest.tmp();
            Files.write(w.resolve("a.png"), new byte[]{1, 2, 3});
            ActionContext ctx = connected(f, w, "key");
            ActionException slow = fails("AZURE_AI.ANALYSIS_TIMEOUT", () -> pkg.documentAnalyze(ctx, "azure_ai", "a.png", "prebuilt-layout", false, null, null, 1));
            assertTrue(slow.retryable());
            ActionException bad = fails("AZURE_AI.ANALYSIS_FAILED", () -> pkg.documentAnalyze(ctx, "azure_ai", "a.png", "prebuilt-receipt", false, null, null, 10));
            assertTrue(bad.getMessage().contains("corrupted"), bad.getMessage());
            fails("AZURE_AI.BAD_RESPONSE", () -> pkg.documentRead(ctx, "azure_ai", "a.png", null, null, 10));
            fails("AZURE_AI.BAD_RESPONSE", () -> pkg.documentAnalyze(ctx, "azure_ai", "a.png", "prebuilt-invoice", false, null, null, 10));
        }
    }

    @Test void visionTagsCaptionObjectsAndText() throws Exception {
        try (Fake f = new Fake()) {
            f.on("POST /computervision/imageanalysis:analyze", c -> ok(Map.of(
                    "captionResult", Map.of("text", "a receipt on a table", "confidence", new BigDecimal("0.81")),
                    "tagsResult", Map.of("values", List.of(Map.of("name", "text", "confidence", new BigDecimal("0.99")), Map.of("name", "receipt", "confidence", new BigDecimal("0.9")))),
                    "objectsResult", Map.of("values", List.of(Map.of("boundingBox", Map.of("x", 1, "y", 2, "w", 30, "h", 40), "tags", List.of(Map.of("name", "cup", "confidence", new BigDecimal("0.7")))))),
                    "readResult", Map.of("blocks", List.of(Map.of("lines", List.of(
                            Map.of("text", "TOTAL 9.99", "words", List.of(Map.of("text", "TOTAL", "confidence", new BigDecimal("0.99")), Map.of("text", "9.99", "confidence", new BigDecimal("0.95")))),
                            Map.of("text", "THANK YOU", "words", List.of()))))))));
            Path w = PackagesTest.tmp();
            Files.write(w.resolve("r.jpg"), new byte[]{(byte) 0xff, (byte) 0xd8, 9});
            ActionContext ctx = connected(f, w, "key");
            Map<String, Object> r = pkg.visionAnalyze(ctx, "azure_ai", "r.jpg", List.of("tags", "caption", "read", "objects"), "en", true);
            Call c = f.last("POST", "/computervision/imageanalysis:analyze");
            assertEquals("application/octet-stream", c.contentType());
            assertArrayEquals(new byte[]{(byte) 0xff, (byte) 0xd8, 9}, c.body());
            assertTrue(c.query().contains("features=tags,caption,read,objects") && c.query().contains("gender-neutral-caption=true") && c.query().contains("api-version=2024-02-01"), c.query());
            assertEquals("a receipt on a table", r.get("caption"));
            assertEquals(List.of("text", "receipt"), r.get("tag_names"));
            assertEquals("TOTAL 9.99\nTHANK YOU", r.get("text"));
            assertEquals(new BigDecimal("0.95"), rows(r.get("lines")).get(0).get(1), "a line's confidence is its weakest word");
            assertEquals("cup", rows(r.get("objects")).get(0).get(0));
            fails("AZURE_AI.BAD_FEATURE", () -> pkg.visionAnalyze(ctx, "azure_ai", "r.jpg", List.of("faces"), "en", false));
        }
    }

    @Test void azureErrorsBecomeClearErrors() throws Exception {
        try (Fake f = new Fake()) {
            f.on("POST /language/:analyze-text", c -> switch (((Map<?, ?>) ((List<?>) ((Map<?, ?>) c.json().get("analysisInput")).get("documents")).get(0)).get("text").toString()) {
                case "401" -> new Object[]{401, "{\"error\":{\"code\":\"401\",\"message\":\"Access denied due to invalid subscription key or wrong API endpoint.\"}}"};
                case "403" -> new Object[]{403, "{\"error\":{\"code\":\"Forbidden\",\"message\":\"Public access is disabled.\"}}"};
                case "404" -> new Object[]{404, "{\"error\":{\"code\":\"404\",\"message\":\"Resource not found\"}}"};
                case "429" -> new Object[]{429, "{\"error\":{\"code\":\"429\",\"message\":\"Rate limit is exceeded.\"}}"};
                default -> new Object[]{500, "{\"error\":{\"code\":\"InternalServerError\",\"message\":\"Unexpected\"}}"};
            });
            ActionContext ctx = connected(f, PackagesTest.tmp(), "key");
            ActionException auth = fails("AZURE_AI.AUTH_FAILED", () -> pkg.languageSentiment(ctx, "azure_ai", "401", "en"));
            assertTrue(auth.getMessage().contains("invalid subscription key"), auth.getMessage());
            assertTrue(auth.suggestedFix().contains("key in the vault"), auth.suggestedFix());
            assertFalse(auth.retryable());
            assertTrue(fails("AZURE_AI.FORBIDDEN", () -> pkg.languageSentiment(ctx, "azure_ai", "403", "en")).suggestedFix().contains("key in the vault"));
            fails("AZURE_AI.NOT_FOUND", () -> pkg.languageSentiment(ctx, "azure_ai", "404", "en"));
            assertTrue(fails("AZURE_AI.RATE_LIMITED", () -> pkg.languageSentiment(ctx, "azure_ai", "429", "en")).retryable());
            assertTrue(fails("AZURE_AI.SERVER_ERROR", () -> pkg.languageSentiment(ctx, "azure_ai", "500", "en")).retryable());
            fails("AZURE_AI.BAD_ENDPOINT", () -> pkg.connect(ctx, "http://example.com", KEY, "key", "x"));
            fails("AZURE_AI.NO_KEY", () -> pkg.connect(ctx, "https://contoso.cognitiveservices.azure.com", null, "key", "x"));
            pkg.disconnect(ctx, "azure_ai");
            assertFalse(ctx.sessions().containsKey("azure_ai"));
        }
    }
}
