package com.oneofficeautomation.packages;

import com.oneofficeautomation.packages.ConnectorsTest.Call;
import com.oneofficeautomation.packages.ConnectorsTest.Fake;
import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.SecureString;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;

import static com.oneofficeautomation.packages.RestToolkitTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** AWS AI toolkit against a local imitation of the Comprehend, Textract, Rekognition and STS APIs (one address, told apart by X-Amz-Target). */
class AwsAiPackageTest {
    final AwsAiPackage pkg = new AwsAiPackage();
    static final SecureString ID = new SecureString("AKIDEXAMPLE", "aws/id"), SECRET = new SecureString("wJalrXUtnFEMI/K7MDENG", "aws/secret");
    static final String STS_OK = "<GetCallerIdentityResponse xmlns=\"https://sts.amazonaws.com/doc/2011-06-15/\"><GetCallerIdentityResult><Arn>arn:aws:iam::123456789012:user/bot</Arn>"
            + "<UserId>AIDA</UserId><Account>123456789012</Account></GetCallerIdentityResult></GetCallerIdentityResponse>";

    static Object[] json(Object v) { return new Object[]{200, JsonLite.write(v), Map.of("Content-Type", "application/x-amz-json-1.1")}; }

    /** Routes by X-Amz-Target; a form body without a target is the STS identity check. */
    static Function<Call, Object[]> by(Map<String, Function<Call, Object[]>> targets) {
        return c -> {
            String t = c.header("X-Amz-Target");
            if (t == null) return new Object[]{200, STS_OK};
            Function<Call, Object[]> h = targets.get(t);
            return h == null ? new Object[]{400, "{\"__type\":\"UnknownOperationException\",\"message\":\"no " + t + "\"}"} : h.apply(c);
        };
    }

    ActionContext connected(Fake f, Path work, SecureString token) {
        ActionContext ctx = ctx(work);
        Map<String, Object> r = pkg.connect(ctx, "eu-west-1", ID, SECRET, token, f.url(), true, "aws_ai");
        assertEquals("123456789012", r.get("account"));
        return ctx;
    }

    /** Recomputes the SigV4 signature of a received request from its own headers and checks it matches. */
    static void assertSigned(Call c, String service, String url) {
        String auth = c.auth();
        assertTrue(auth.startsWith("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/"), auth);
        assertTrue(auth.contains("/eu-west-1/" + service + "/aws4_request"), auth);
        String signedHeaders = auth.replaceAll(".*SignedHeaders=([^,]+),.*", "$1");
        Map<String, String> h = new LinkedHashMap<>();
        for (String n : signedHeaders.split(";")) if (!n.equals("x-amz-date")) h.put(n, c.header(n));
        Instant when = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).parse(c.header("x-amz-date"), Instant::from);
        Map<String, String> again = AwsSigV4.sign("POST", URI.create(url + "/"), h, c.body(), "AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG", "eu-west-1", service, when);
        assertEquals(again.get("Authorization"), auth, "the signature covers the headers and body that arrived");
    }

    @Test void connectChecksKeysAndComprehendCallsAreSigned() throws Exception {
        try (Fake f = new Fake()) {
            f.on("POST /", by(Map.of(
                    "Comprehend_20171127.DetectSentiment", c -> json(Map.of("Sentiment", "POSITIVE", "SentimentScore", Map.of("Positive", new BigDecimal("0.97"), "Negative", new BigDecimal("0.01"),
                            "Neutral", new BigDecimal("0.02"), "Mixed", BigDecimal.ZERO))),
                    "Comprehend_20171127.DetectEntities", c -> json(Map.of("Entities", List.of(
                            Map.of("Text", "Acme Ltd", "Type", "ORGANIZATION", "Score", new BigDecimal("0.99"), "BeginOffset", 0, "EndOffset", 8),
                            Map.of("Text", "today", "Type", "DATE", "Score", new BigDecimal("0.4"), "BeginOffset", 15, "EndOffset", 20)))),
                    "Comprehend_20171127.DetectKeyPhrases", c -> json(Map.of("KeyPhrases", List.of(
                            Map.of("Text", "the invoice", "Score", new BigDecimal("0.81"), "BeginOffset", 4, "EndOffset", 15),
                            Map.of("Text", "three laptops", "Score", new BigDecimal("0.99"), "BeginOffset", 20, "EndOffset", 33),
                            Map.of("Text", "it", "Score", new BigDecimal("0.3"), "BeginOffset", 0, "EndOffset", 2)))),
                    "Comprehend_20171127.DetectDominantLanguage", c -> json(Map.of("Languages", List.of(Map.of("LanguageCode", "fr", "Score", new BigDecimal("0.2")),
                            Map.of("LanguageCode", "de", "Score", new BigDecimal("0.79"))))),
                    "Comprehend_20171127.DetectPiiEntities", c -> json(Map.of("Entities", List.of(
                            Map.of("Type", "EMAIL", "Score", new BigDecimal("0.99"), "BeginOffset", 5, "EndOffset", 17),
                            Map.of("Type", "NAME", "Score", new BigDecimal("0.98"), "BeginOffset", 21, "EndOffset", 24)))))));
            ActionContext ctx = connected(f, PackagesTest.tmp(), new SecureString("FQoGZXIvYXdzE", "aws/token"));
            Call sts = f.calls.get(0);
            assertEquals("Action=GetCallerIdentity&Version=2011-06-15", sts.text());
            assertSigned(sts, "sts", f.url());
            assertEquals("FQoGZXIvYXdzE", sts.header("x-amz-security-token"));
            assertTrue(sts.auth().contains("x-amz-security-token"), "the session token is signed");

            Map<String, Object> s = pkg.comprehendSentiment(ctx, "aws_ai", "Great service, thank you", "en");
            Call c = f.last("POST", "/");
            assertEquals("Comprehend_20171127.DetectSentiment", c.header("X-Amz-Target"));
            assertEquals("application/x-amz-json-1.1", c.contentType());
            assertEquals(Map.of("Text", "Great service, thank you", "LanguageCode", "en"), c.json());
            assertSigned(c, "comprehend", f.url());
            assertEquals("POSITIVE", s.get("sentiment"));
            assertEquals(new BigDecimal("0.97"), ((Map<?, ?>) s.get("scores")).get("positive"));

            Map<String, Object> e = pkg.comprehendEntities(ctx, "aws_ai", "Acme Ltd paid today", "en", 0.5);
            assertEquals(BigDecimal.ONE, e.get("count"), "the low-scored entity is left out");
            assertEquals(List.of("text", "type", "score", "begin_offset", "end_offset"), cols(e.get("entities")));
            assertEquals("ORGANIZATION", rows(e.get("entities")).get(0).get(1));

            Map<String, Object> k = pkg.comprehendKeyPhrases(ctx, "aws_ai", "Pay the invoice for three laptops", "en", 0.5);
            assertEquals("Comprehend_20171127.DetectKeyPhrases", f.last("POST", "/").header("X-Amz-Target"));
            assertEquals(List.of("three laptops", "the invoice"), k.get("phrases"), "most likely first, the low-scored one left out");
            assertEquals(List.of("text", "score", "begin_offset", "end_offset"), cols(k.get("key_phrases")));
            assertEquals(new BigDecimal("2"), k.get("count"));

            Map<String, Object> l = pkg.comprehendDetectLanguage(ctx, null, "Guten Tag");
            assertEquals("de", l.get("language"));
            assertEquals(2, rows(l.get("languages")).size());

            Map<String, Object> p = pkg.comprehendDetectPii(ctx, "aws_ai", "Mail bob@acme.com to Bob", "en", 0);
            assertEquals("Mail [EMAIL] to [NAME]", p.get("redacted_text"));
            assertEquals("bob@acme.com", rows(p.get("entities")).get(0).get(0));
            assertEquals(new BigDecimal("2"), p.get("count"));

            fails("AWS_AI.NO_TEXT", () -> pkg.comprehendSentiment(ctx, "aws_ai", " ", "en"));
            fails("AWS_AI.TEXT_TOO_LONG", () -> pkg.comprehendSentiment(ctx, "aws_ai", "x".repeat(5001), "en"));
            fails("AWS_AI.BAD_LANGUAGE", () -> pkg.comprehendEntities(ctx, "aws_ai", "x", "English", 0));
        }
    }

    static Map<String, Object> block(String id, String type, Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("Id", id); m.put("BlockType", type);
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    static List<Object> child(String... ids) { return List.of(Map.of("Type", "CHILD", "Ids", List.of((Object[]) ids))); }

    static Map<String, Object> cell(String id, int r, int c, String word) { return block(id, "CELL", "RowIndex", r, "ColumnIndex", c, "RowSpan", 1, "ColumnSpan", 1, "Relationships", child(word)); }

    @Test void textractReadsTextFormsAndTablesAsDataTables() throws Exception {
        List<Object> blocks = List.of(
                block("p1", "PAGE"),
                block("l1", "LINE", "Text", "Invoice: INV-1", "Confidence", new BigDecimal("99.1"), "Page", 1),
                block("l2", "LINE", "Text", "Item Amount", "Page", 1),
                block("w1", "WORD", "Text", "Invoice:"), block("w2", "WORD", "Text", "INV-1"),
                block("w3", "WORD", "Text", "Item"), block("w4", "WORD", "Text", "Amount"), block("w5", "WORD", "Text", "Pens"), block("w6", "WORD", "Text", "12.50"),
                block("s1", "SELECTION_ELEMENT", "SelectionStatus", "SELECTED"), block("w7", "WORD", "Text", "Paid"),
                block("k1", "KEY_VALUE_SET", "EntityTypes", List.of("KEY"), "Confidence", new BigDecimal("95"), "Page", 1,
                        "Relationships", List.of(Map.of("Type", "VALUE", "Ids", List.of("v1")), Map.of("Type", "CHILD", "Ids", List.of("w1")))),
                block("v1", "KEY_VALUE_SET", "EntityTypes", List.of("VALUE"), "Relationships", child("w2")),
                block("k2", "KEY_VALUE_SET", "EntityTypes", List.of("KEY"), "Relationships", List.of(Map.of("Type", "VALUE", "Ids", List.of("v2")), Map.of("Type", "CHILD", "Ids", List.of("w7")))),
                block("v2", "KEY_VALUE_SET", "EntityTypes", List.of("VALUE"), "Relationships", child("s1")),
                block("t1", "TABLE", "Relationships", List.of(Map.of("Type", "CHILD", "Ids", List.of("c1", "c2", "c3", "c4")))),
                cell("c1", 1, 1, "w3"), cell("c2", 1, 2, "w4"), cell("c3", 2, 1, "w5"), cell("c4", 2, 2, "w6"));
        try (Fake f = new Fake()) {
            f.on("POST /", by(Map.of(
                    "Textract.DetectDocumentText", c -> json(Map.of("DocumentMetadata", Map.of("Pages", 1), "Blocks", blocks)),
                    "Textract.AnalyzeDocument", c -> json(Map.of("DocumentMetadata", Map.of("Pages", 1), "Blocks", blocks)))));
            Path w = PackagesTest.tmp();
            Files.write(w.resolve("scan.png"), new byte[]{(byte) 0x89, 'P', 'N', 'G', 1, 2, 3});
            ActionContext ctx = connected(f, w, null);
            assertNull(f.calls.get(0).header("x-amz-security-token"));

            Map<String, Object> t = pkg.textractDetectText(ctx, "aws_ai", "scan.png");
            Call c = f.last("POST", "/");
            assertSigned(c, "textract", f.url());
            assertEquals(Base64.getEncoder().encodeToString(new byte[]{(byte) 0x89, 'P', 'N', 'G', 1, 2, 3}), ((Map<?, ?>) c.json().get("Document")).get("Bytes"));
            assertEquals("Invoice: INV-1\nItem Amount", t.get("text"));
            assertEquals(BigDecimal.ONE, t.get("page_count"));
            assertEquals(2, rows(t.get("lines")).size());

            Map<String, Object> a = pkg.textractAnalyzeDocument(ctx, "aws_ai", "scan.png", true, true, true);
            assertEquals(List.of("FORMS", "TABLES"), f.last("POST", "/").json().get("FeatureTypes"));
            assertEquals(Map.of("Invoice:", "INV-1", "Paid", "X"), a.get("fields"), "a ticked box reads as X");
            assertEquals(List.of("key", "value", "confidence", "page"), cols(a.get("key_values")));
            List<?> tables = (List<?>) a.get("tables");
            assertEquals(BigDecimal.ONE, a.get("table_count"));
            assertEquals(List.of("Item", "Amount"), cols(tables.get(0)));
            assertEquals(List.of(List.of("Pens", "12.50")), rows(tables.get(0)));
            Map<String, Object> noHeader = pkg.textractAnalyzeDocument(ctx, "aws_ai", "scan.png", false, true, false);
            assertEquals(List.of("TABLES"), f.last("POST", "/").json().get("FeatureTypes"));
            assertEquals(List.of("Column 1", "Column 2"), cols(((List<?>) noHeader.get("tables")).get(0)));
            assertEquals(2, rows(((List<?>) noHeader.get("tables")).get(0)).size());

            fails("AWS_AI.NOTHING_TO_READ", () -> pkg.textractAnalyzeDocument(ctx, "aws_ai", "scan.png", false, false, true));
            fails("AWS_AI.FILE_NOT_FOUND", () -> pkg.textractDetectText(ctx, "aws_ai", "missing.pdf"));
            fails("AWS_AI.NO_FILE", () -> pkg.textractDetectText(ctx, "aws_ai", ""));
            Files.write(w.resolve("empty.png"), new byte[0]);
            fails("AWS_AI.EMPTY_FILE", () -> pkg.textractDetectText(ctx, "aws_ai", "empty.png"));
            Files.write(w.resolve("big.jpg"), new byte[(int) AwsAiPackage.MAX_IMAGE + 1]);
            ActionException big = fails("AWS_AI.FILE_TOO_LARGE", () -> pkg.rekognitionDetectLabels(ctx, "aws_ai", "big.jpg", 10, 50));
            assertTrue(big.getMessage().contains("5 MB"), big.getMessage());
        }
    }

    @Test void rekognitionLabelsAndText() throws Exception {
        try (Fake f = new Fake()) {
            f.on("POST /", by(Map.of(
                    "RekognitionService.DetectLabels", c -> json(Map.of("Labels", List.of(
                            Map.of("Name", "Car", "Confidence", new BigDecimal("98.5"), "Parents", List.of(Map.of("Name", "Vehicle"), Map.of("Name", "Transportation")), "Instances", List.of(Map.of(), Map.of())),
                            Map.of("Name", "Road", "Confidence", new BigDecimal("80"))))),
                    "RekognitionService.DetectText", c -> json(Map.of("TextDetections", List.of(
                            Map.of("DetectedText", "STOP", "Type", "LINE", "Confidence", new BigDecimal("99")),
                            Map.of("DetectedText", "STOP", "Type", "WORD", "Confidence", new BigDecimal("99")),
                            Map.of("DetectedText", "blur", "Type", "LINE", "Confidence", new BigDecimal("20"))))))));
            Path w = PackagesTest.tmp();
            Files.write(w.resolve("car.jpg"), new byte[]{(byte) 0xff, (byte) 0xd8, 1});
            ActionContext ctx = connected(f, w, null);
            Map<String, Object> l = pkg.rekognitionDetectLabels(ctx, "aws_ai", "car.jpg", 5, 70);
            Call c = f.last("POST", "/");
            assertSigned(c, "rekognition", f.url());
            assertEquals(new BigDecimal("5"), c.json().get("MaxLabels"));
            assertEquals(70, ((BigDecimal) c.json().get("MinConfidence")).intValue());
            assertEquals(List.of("Car", "Road"), l.get("label_names"));
            assertEquals(List.of("Car", new BigDecimal("98.5"), "Vehicle, Transportation", new BigDecimal("2")), rows(l.get("labels")).get(0));
            Map<String, Object> t = pkg.rekognitionDetectText(ctx, "aws_ai", "car.jpg", 50);
            assertEquals("STOP", t.get("text"));
            assertEquals(2, rows(t.get("detections")).size());
        }
    }

    @Test void awsErrorsBecomeClearErrors() throws Exception {
        try (Fake f = new Fake()) {
            f.on("POST /", c -> {
                String t = c.header("X-Amz-Target");
                if (t == null) return new Object[]{403, "<ErrorResponse><Error><Type>Sender</Type><Code>InvalidClientTokenId</Code><Message>The security token included in the request is invalid.</Message></Error></ErrorResponse>"};
                String text = String.valueOf(c.json().get("Text"));
                return switch (text) {
                    case "403" -> new Object[]{400, "{\"__type\":\"com.amazon.coral.service#AccessDeniedException\",\"Message\":\"User is not authorized to perform comprehend:DetectSentiment\"}"};
                    case "401" -> new Object[]{400, "{\"__type\":\"UnrecognizedClientException\",\"message\":\"The security token included in the request is invalid.\"}"};
                    case "throttle" -> new Object[]{400, "{\"__type\":\"ThrottlingException\",\"message\":\"Rate exceeded\"}"};
                    case "429" -> new Object[]{429, "{\"message\":\"Too many\"}"};
                    case "500" -> new Object[]{500, "{\"__type\":\"InternalServerException\",\"message\":\"oops\"}"};
                    case "503" -> new Object[]{503, "Service Unavailable"};
                    case "404" -> new Object[]{404, "{\"message\":\"Not here\"}"};
                    case "lang" -> new Object[]{400, "{\"__type\":\"UnsupportedLanguageException\",\"Message\":\"Unsupported language\"}"};
                    case "page" -> new Object[]{200, "<html><body>Please sign in to the proxy</body></html>", Map.of("Content-Type", "text/html")};
                    default -> new Object[]{400, "{\"__type\":\"ValidationException\",\"message\":\"bad\"}"};
                };
            });
            ActionContext ctx = ctx(PackagesTest.tmp());
            ActionException auth = fails("AWS_AI.AUTH_FAILED", () -> pkg.connect(ctx, "eu-west-1", ID, SECRET, null, f.url(), true, "aws_ai"));
            assertTrue(auth.getMessage().contains("security token"), auth.getMessage());
            assertTrue(auth.suggestedFix() != null && auth.suggestedFix().contains("clock"), auth.suggestedFix());
            assertFalse(ctx.sessions().containsKey("aws_ai"), "a failed check opens no session");
            assertEquals("", pkg.connect(ctx, "eu-west-1", ID, SECRET, null, f.url(), false, "aws_ai").get("account"));
            assertFalse(fails("AWS_AI.FORBIDDEN", () -> pkg.comprehendSentiment(ctx, "aws_ai", "403", "en")).retryable());
            fails("AWS_AI.AUTH_FAILED", () -> pkg.comprehendSentiment(ctx, "aws_ai", "401", "en"));
            assertTrue(fails("AWS_AI.THROTTLED", () -> pkg.comprehendSentiment(ctx, "aws_ai", "throttle", "en")).retryable());
            assertTrue(fails("AWS_AI.RATE_LIMITED", () -> pkg.comprehendSentiment(ctx, "aws_ai", "429", "en")).retryable());
            assertTrue(fails("AWS_AI.SERVER_ERROR", () -> pkg.comprehendSentiment(ctx, "aws_ai", "500", "en")).retryable());
            assertTrue(fails("AWS_AI.SERVER_ERROR", () -> pkg.comprehendSentiment(ctx, "aws_ai", "503", "en")).retryable());
            fails("AWS_AI.NOT_FOUND", () -> pkg.comprehendSentiment(ctx, "aws_ai", "404", "en"));
            fails("AWS_AI.UNSUPPORTED_LANGUAGE", () -> pkg.comprehendSentiment(ctx, "aws_ai", "lang", "en"));
            fails("AWS_AI.BAD_RESPONSE", () -> pkg.comprehendSentiment(ctx, "aws_ai", "page", "en"));
            ActionException other = fails("AWS_AI.REQUEST_FAILED", () -> pkg.comprehendSentiment(ctx, "aws_ai", "x", "en"));
            assertEquals("ValidationException", other.details().get("service_code"));
            fails("AWS_AI.BAD_REGION", () -> pkg.connect(ctx, "Ireland", ID, SECRET, null, null, false, "aws_ai"));
            fails("AWS_AI.NO_SECRET_KEY", () -> pkg.connect(ctx, "eu-west-1", ID, null, null, null, false, "aws_ai"));
            fails("AWS_AI.NO_ACCESS_KEY", () -> pkg.connect(ctx, "eu-west-1", new SecureString(" ", "x"), SECRET, null, null, false, "aws_ai"));
            pkg.disconnect(ctx, "aws_ai");
            assertFalse(ctx.sessions().containsKey("aws_ai"));
        }
    }

    @Test void defaultAddressesFollowTheRegionAndServiceOverride() {
        AwsAiPackage.AwsAiSession s = new AwsAiPackage.AwsAiSession("ap-south-1", null, ID, SECRET, null);
        assertEquals("https://textract.ap-south-1.amazonaws.com", s.url("textract"));
        AwsAiPackage.AwsAiSession v = new AwsAiPackage.AwsAiSession("ap-south-1", "https://{service}.vpce.example.com/", ID, SECRET, null);
        assertEquals("https://rekognition.vpce.example.com", v.url("rekognition"));
    }
}
