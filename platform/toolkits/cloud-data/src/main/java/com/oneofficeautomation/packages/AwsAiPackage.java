package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ready-made AWS AI services: Amazon Comprehend (sentiment, entities, language, personal data), Amazon Textract (text,
 * forms and tables from scans) and Amazon Rekognition (labels and text in pictures). Requests go to each service's
 * JSON 1.1 API, signed with AWS Signature Version 4 (no AWS SDK); Connect keeps the region and the keys from the vault.
 */
@OoaPackage(id = "ooa.aws_ai", name = "AWS AI services", version = "1.0.0",
        description = "Read sentiment, entities, language and personal data in text with Amazon Comprehend, text, forms and tables from scans with Amazon Textract, and labels and text in pictures with Amazon Rekognition.")
public final class AwsAiPackage {
    static final String P = "AWS_AI";
    private static final Pattern REGION = Pattern.compile("^[a-z]{2}(-[a-z]+)+-\\d{1,2}$");
    private static final Pattern LANGUAGE = Pattern.compile("^[a-z]{2}(-[A-Z]{2})?$");
    private static final String D_SESSION = "Name of the session opened by Connect.";
    private static final String D_LANG = "Language of the text, e.g. en, es, fr, de, it, pt, ja, ko, zh, ar or hi.";
    private static final String SIGN_IN = "Check the access key id and secret key in the vault, the session token (temporary keys expire), the region, and that this computer's clock is right.";
    static final long MAX_DOCUMENT = 10L * 1024 * 1024, MAX_IMAGE = 5L * 1024 * 1024;
    private static final Set<String> AUTH_TYPES = Set.of("UnrecognizedClientException", "InvalidSignatureException", "IncompleteSignature", "MissingAuthenticationToken",
            "MissingAuthenticationTokenException", "ExpiredTokenException", "InvalidClientTokenId", "SignatureDoesNotMatch", "AuthFailure");
    private static final Set<String> BUSY_TYPES = Set.of("ThrottlingException", "Throttling", "ProvisionedThroughputExceededException", "TooManyRequestsException",
            "LimitExceededException", "RequestLimitExceeded");
    private static final Set<String> SERVER_TYPES = Set.of("InternalServerException", "InternalServerError", "InternalFailure", "ServiceUnavailable", "ServiceUnavailableException");
    private static final Set<String> DOCUMENT_TYPES = Set.of("UnsupportedDocumentException", "BadDocumentException", "DocumentTooLargeException", "InvalidImageFormatException",
            "ImageTooLargeException", "InvalidS3ObjectException");

    /** What Connect keeps: the region, an optional address override and the keys (secrets stay SecureStrings). */
    static final class AwsAiSession {
        final String region, endpoint;
        final SecureString accessKeyId, secretKey, sessionToken;
        AwsAiSession(String region, String endpoint, SecureString accessKeyId, SecureString secretKey, SecureString sessionToken) {
            this.region = region; this.endpoint = endpoint; this.accessKeyId = accessKeyId; this.secretKey = secretKey; this.sessionToken = sessionToken;
        }
        String url(String service) { return CloudAi.endpoint(endpoint, service, "https://" + service + "." + region + ".amazonaws.com"); }
    }

    // ----- connection -----

    @Action(id = "connect", name = "Connect", category = "Connection", capabilities = "network",
            description = "Open a session for one AWS region with an access key from the vault; the Comprehend, Textract and Rekognition steps use it.",
            panel = "session, region, access_key_id, secret_access_key | session_token, endpoint, check_now")
    @Output(name = "account", type = OoaType.STRING, description = "The AWS account id the keys belong to (empty when the keys were not checked).")
    public Map<String, Object> connect(ActionContext ctx,
            @Input(name = "region", type = OoaType.STRING, example = "eu-west-1", description = "AWS region of the services, e.g. us-east-1, eu-west-1 or ap-south-1.") String region,
            @Input(name = "access_key_id", type = OoaType.CREDENTIAL, description = "Access key id from the vault.") Object accessKeyId,
            @Input(name = "secret_access_key", type = OoaType.CREDENTIAL, description = "Secret access key from the vault.") Object secretKey,
            @Input(name = "session_token", type = OoaType.CREDENTIAL, required = false, description = "Session token from the vault, for temporary keys (assumed roles, single sign-on).") Object sessionToken,
            @Input(name = "endpoint", type = OoaType.STRING, required = false, pattern = HttpJson.HTTPS_OR_LOCAL,
                    description = "Another address for the services, e.g. a private VPC endpoint; {service} in it becomes comprehend, textract, rekognition or sts. Leave empty for AWS's addresses.") String endpoint,
            @Input(name = "check_now", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Check the keys with AWS straight away (a free identity check, no AI call).") boolean checkNow,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"aws_ai\"", description = "Session name.") String session) {
        String r = region == null ? "" : region.strip();
        if (!REGION.matcher(r).matches()) throw new ActionException(P + ".BAD_REGION", "'" + r + "' is not an AWS region", "Use a region code such as us-east-1, eu-west-1 or ap-south-1.");
        SecureString id = CloudAi.secret(accessKeyId), secret = CloudAi.secret(secretKey);
        if (id == null) throw new ActionException(P + ".NO_ACCESS_KEY", "The access key id from the vault is required", "Map access_key_id to a Credential.");
        if (secret == null) throw new ActionException(P + ".NO_SECRET_KEY", "The secret access key from the vault is required", "Map secret_access_key to a Credential.");
        AwsAiSession s = new AwsAiSession(r, endpoint == null || endpoint.isBlank() ? null : endpoint.strip(), id, secret, CloudAi.secret(sessionToken));
        String account = checkNow ? callerAccount(ctx, s) : "";
        CloudAi.sessionName(session, "aws_ai", ctx.sessions(), s);
        return Out.of("account", account);
    }

    @Action(id = "disconnect", name = "Disconnect", category = "Connection", description = "Close the AWS AI session and forget its keys.", panel = "session")
    public Map<String, Object> disconnect(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"aws_ai\"", description = "Session name.") String session) {
        ctx.sessions().remove(session);
        return Map.of();
    }

    // ----- Comprehend -----

    @Action(id = "comprehend_sentiment", name = "Detect sentiment", category = "Comprehend (text)", idempotent = true, capabilities = "network",
            description = "Read whether a text is positive, negative, neutral or mixed, with a score for each (Amazon Comprehend).",
            panel = "session, text, language")
    @Output(name = "sentiment", type = OoaType.STRING, description = "POSITIVE, NEGATIVE, NEUTRAL or MIXED.")
    @Output(name = "scores", type = OoaType.DICTIONARY, description = "Score from 0 to 1 for each, e.g. {\"positive\": 0.97, \"negative\": 0.01, \"neutral\": 0.02, \"mixed\": 0}.")
    public Map<String, Object> comprehendSentiment(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"aws_ai\"", description = D_SESSION) String session,
            @Input(name = "text", type = OoaType.STRING, description = "The text to read, at most 5,000 bytes.") String text,
            @Input(name = "language", type = OoaType.STRING, required = false, defaultValue = "\"en\"", description = D_LANG) String language) {
        AwsAiSession s = ctx.sessionOrOnly(session, "aws_ai", AwsAiSession.class);
        Map<String, Object> body = Out.of("Text", CloudAi.text(P, text, 5000, false, "Comprehend sentiment"), "LanguageCode", language(language));
        Map<String, Object> a = call(ctx, s, "comprehend", "Comprehend_20171127.DetectSentiment", body);
        Map<String, Object> sc = CloudAi.map(a.get("SentimentScore"));
        Map<String, Object> scores = Out.of("positive", CloudAi.num(sc.get("Positive")), "negative", CloudAi.num(sc.get("Negative")),
                "neutral", CloudAi.num(sc.get("Neutral")), "mixed", CloudAi.num(sc.get("Mixed")));
        return Out.of("sentiment", CloudAi.str(a.get("Sentiment")), "scores", scores);
    }

    @Action(id = "comprehend_entities", name = "Detect entities", category = "Comprehend (text)", idempotent = true, capabilities = "network",
            description = "Find people, places, organisations, dates, quantities and other named things in a text (Amazon Comprehend).",
            panel = "session, text, language | min_score")
    @Output(name = "entities", type = OoaType.TABLE, description = "Columns: text, type, score, begin_offset, end_offset.")
    @Output(name = "count", type = OoaType.NUMBER, description = "How many entities were found.")
    public Map<String, Object> comprehendEntities(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"aws_ai\"", description = D_SESSION) String session,
            @Input(name = "text", type = OoaType.STRING, description = "The text to read, at most 100,000 bytes.") String text,
            @Input(name = "language", type = OoaType.STRING, required = false, defaultValue = "\"en\"", description = D_LANG) String language,
            @Input(name = "min_score", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0, max = 1, description = "Leave out entities scored below this (0 to 1).") double minScore) {
        AwsAiSession s = ctx.sessionOrOnly(session, "aws_ai", AwsAiSession.class);
        Map<String, Object> a = call(ctx, s, "comprehend", "Comprehend_20171127.DetectEntities",
                Out.of("Text", CloudAi.text(P, text, 100_000, false, "Comprehend entities"), "LanguageCode", language(language)));
        List<Object> rows = new ArrayList<>();
        for (Map<String, Object> e : CloudAi.maps(a.get("Entities"))) {
            BigDecimal score = CloudAi.num(e.get("Score"));
            if (score != null && score.doubleValue() < minScore) continue;
            rows.add(CloudAi.row(CloudAi.str(e.get("Text")), CloudAi.str(e.get("Type")), score, CloudAi.num(e.get("BeginOffset")), CloudAi.num(e.get("EndOffset"))));
        }
        return Out.of("entities", CloudAi.table(List.of("text", "type", "score", "begin_offset", "end_offset"), rows), "count", BigDecimal.valueOf(rows.size()));
    }

    @Action(id = "comprehend_detect_language", name = "Detect language", category = "Comprehend (text)", idempotent = true, capabilities = "network",
            description = "Find which language a text is written in (Amazon Comprehend).", panel = "session, text")
    @Output(name = "language", type = OoaType.STRING, description = "Code of the most likely language, e.g. en, de or fr.")
    @Output(name = "score", type = OoaType.NUMBER, description = "How sure Comprehend is about that language (0 to 1).")
    @Output(name = "languages", type = OoaType.TABLE, description = "Every language found. Columns: language, score.")
    public Map<String, Object> comprehendDetectLanguage(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"aws_ai\"", description = D_SESSION) String session,
            @Input(name = "text", type = OoaType.STRING, description = "The text to read, at most 100,000 bytes.") String text) {
        AwsAiSession s = ctx.sessionOrOnly(session, "aws_ai", AwsAiSession.class);
        Map<String, Object> a = call(ctx, s, "comprehend", "Comprehend_20171127.DetectDominantLanguage", Out.of("Text", CloudAi.text(P, text, 100_000, false, "Comprehend")));
        List<Object> rows = new ArrayList<>();
        String best = null; BigDecimal bestScore = null;
        for (Map<String, Object> l : CloudAi.maps(a.get("Languages"))) {
            BigDecimal sc = CloudAi.num(l.get("Score"));
            rows.add(CloudAi.row(CloudAi.str(l.get("LanguageCode")), sc));
            if (bestScore == null || sc != null && sc.compareTo(bestScore) > 0) { best = CloudAi.str(l.get("LanguageCode")); bestScore = sc; }
        }
        return Out.of("language", best, "score", bestScore, "languages", CloudAi.table(List.of("language", "score"), rows));
    }

    @Action(id = "comprehend_detect_pii", name = "Detect personal data", category = "Comprehend (text)", idempotent = true, capabilities = "network",
            description = "Find personal data such as names, addresses, phone numbers, emails and card or account numbers in a text, and give a copy with them masked (Amazon Comprehend).",
            panel = "session, text, language | min_score")
    @Output(name = "entities", type = OoaType.TABLE, description = "Columns: text, type, score, begin_offset, end_offset.")
    @Output(name = "redacted_text", type = OoaType.STRING, description = "The text with each piece of personal data replaced by its type, e.g. [EMAIL].")
    @Output(name = "count", type = OoaType.NUMBER, description = "How many pieces of personal data were found.")
    public Map<String, Object> comprehendDetectPii(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"aws_ai\"", description = D_SESSION) String session,
            @Input(name = "text", type = OoaType.STRING, description = "The text to read, at most 100,000 bytes.") String text,
            @Input(name = "language", type = OoaType.STRING, required = false, defaultValue = "\"en\"", description = "Language of the text: en or es.") String language,
            @Input(name = "min_score", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0, max = 1, description = "Leave out findings scored below this (0 to 1).") double minScore) {
        AwsAiSession s = ctx.sessionOrOnly(session, "aws_ai", AwsAiSession.class);
        String t = CloudAi.text(P, text, 100_000, false, "Comprehend personal data");
        Map<String, Object> a = call(ctx, s, "comprehend", "Comprehend_20171127.DetectPiiEntities", Out.of("Text", t, "LanguageCode", language(language)));
        List<Object> rows = new ArrayList<>();
        List<int[]> spans = new ArrayList<>();
        List<String> types = new ArrayList<>();
        int cps = t.codePointCount(0, t.length());
        for (Map<String, Object> e : CloudAi.maps(a.get("Entities"))) {
            BigDecimal score = CloudAi.num(e.get("Score"));
            if (score != null && score.doubleValue() < minScore) continue;
            int b = Math.max(0, Math.min(cps, CloudAi.intOf(e.get("BeginOffset"), 0))), en = Math.max(b, Math.min(cps, CloudAi.intOf(e.get("EndOffset"), b)));
            int bi = t.offsetByCodePoints(0, b), ei = t.offsetByCodePoints(0, en);
            String type = CloudAi.str(e.get("Type"));
            rows.add(CloudAi.row(t.substring(bi, ei), type, score, BigDecimal.valueOf(b), BigDecimal.valueOf(en)));
            spans.add(new int[]{bi, ei});
            types.add(type);
        }
        return Out.of("entities", CloudAi.table(List.of("text", "type", "score", "begin_offset", "end_offset"), rows),
                "redacted_text", redact(t, spans, types), "count", BigDecimal.valueOf(rows.size()));
    }

    /** The text with each span replaced by [TYPE]; overlapping spans are merged into the first. */
    static String redact(String t, List<int[]> spans, List<String> types) {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < spans.size(); i++) order.add(i);
        order.sort(Comparator.comparingInt(i -> spans.get(i)[0]));
        StringBuilder sb = new StringBuilder();
        int at = 0;
        for (int i : order) {
            int[] sp = spans.get(i);
            if (sp[0] < at) { at = Math.max(at, sp[1]); continue; }
            sb.append(t, at, sp[0]).append('[').append(types.get(i)).append(']');
            at = sp[1];
        }
        return sb.append(t.substring(Math.min(at, t.length()))).toString();
    }

    // ----- Textract -----

    @Action(id = "textract_detect_text", name = "Read text from a scan", category = "Textract (documents)", idempotent = true, capabilities = "network",
            description = "Read the printed and handwritten text of a picture or a one-page PDF (Amazon Textract).", panel = "session, file")
    @Output(name = "text", type = OoaType.STRING, description = "All lines of text, one per line.")
    @Output(name = "lines", type = OoaType.TABLE, description = "Columns: page, text, confidence.")
    @Output(name = "page_count", type = OoaType.NUMBER, description = "Number of pages read.")
    public Map<String, Object> textractDetectText(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"aws_ai\"", description = D_SESSION) String session,
            @Input(name = "file", type = OoaType.FILE, description = "PNG, JPEG or TIFF picture, or a one-page PDF; at most 10 MB.") String file) {
        AwsAiSession s = ctx.sessionOrOnly(session, "aws_ai", AwsAiSession.class);
        byte[] bytes = CloudAi.readFile(ctx, P, file, MAX_DOCUMENT, "document");
        Map<String, Object> a = call(ctx, s, "textract", "Textract.DetectDocumentText", Out.of("Document", Out.of("Bytes", Base64.getEncoder().encodeToString(bytes))));
        Blocks b = new Blocks(a);
        return Out.of("text", b.text(), "lines", b.lines(), "page_count", b.pageCount(a));
    }

    @Action(id = "textract_analyze_document", name = "Read forms and tables", category = "Textract (documents)", idempotent = true, capabilities = "network",
            description = "Read the text, the form fields (key and value) and the tables of a picture or one-page PDF; each table becomes a Data Table (Amazon Textract).",
            panel = "session, file, [Read: forms, tables] | first_row_headers")
    @Output(name = "text", type = OoaType.STRING, description = "All lines of text, one per line.")
    @Output(name = "key_values", type = OoaType.TABLE, description = "Form fields. Columns: key, value, confidence, page.")
    @Output(name = "fields", type = OoaType.DICTIONARY, description = "Form fields as key to value, e.g. {\"Invoice number:\": \"INV-1001\"}.")
    @Output(name = "tables", type = OoaType.LIST, description = "One Data Table per table found, top of the page first.")
    @Output(name = "table_count", type = OoaType.NUMBER, description = "How many tables were found.")
    public Map<String, Object> textractAnalyzeDocument(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"aws_ai\"", description = D_SESSION) String session,
            @Input(name = "file", type = OoaType.FILE, description = "PNG, JPEG or TIFF picture, or a one-page PDF; at most 10 MB.") String file,
            @Input(name = "forms", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Read form fields (key and value pairs).") boolean forms,
            @Input(name = "tables", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Read tables.") boolean tables,
            @Input(name = "first_row_headers", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Use the first row of each table as its column names.") boolean headers) {
        if (!forms && !tables) throw new ActionException(P + ".NOTHING_TO_READ", "Neither forms nor tables is ticked", "Tick forms, tables or both; use Read text from a scan for text only.");
        AwsAiSession s = ctx.sessionOrOnly(session, "aws_ai", AwsAiSession.class);
        byte[] bytes = CloudAi.readFile(ctx, P, file, MAX_DOCUMENT, "document");
        List<Object> features = new ArrayList<>();
        if (forms) features.add("FORMS");
        if (tables) features.add("TABLES");
        Map<String, Object> a = call(ctx, s, "textract", "Textract.AnalyzeDocument",
                Out.of("Document", Out.of("Bytes", Base64.getEncoder().encodeToString(bytes)), "FeatureTypes", features));
        Blocks b = new Blocks(a);
        Map<String, Object> fields = new LinkedHashMap<>();
        Map<String, Object> kv = b.keyValues(fields);
        List<Object> tbls = b.tables(headers);
        return Out.of("text", b.text(), "key_values", kv, "fields", fields, "tables", tbls, "table_count", BigDecimal.valueOf(tbls.size()));
    }

    /** Textract's answer: blocks by id, read into lines, form fields and tables. */
    static final class Blocks {
        final List<Map<String, Object>> all;
        final Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        Blocks(Map<String, Object> answer) {
            all = CloudAi.maps(answer.get("Blocks"));
            for (Map<String, Object> b : all) if (b.get("Id") != null) byId.put(String.valueOf(b.get("Id")), b);
        }
        static String type(Map<String, Object> b) { return String.valueOf(b.get("BlockType")); }
        List<Map<String, Object>> related(Map<String, Object> b, String kind) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> r : CloudAi.maps(b.get("Relationships")))
                if (kind.equals(r.get("Type")) && r.get("Ids") instanceof List<?> ids)
                    for (Object id : ids) { Map<String, Object> c = byId.get(String.valueOf(id)); if (c != null) out.add(c); }
            return out;
        }
        /** Words of a block joined with spaces; ticked boxes read as "X". */
        String words(Map<String, Object> b) {
            StringJoiner j = new StringJoiner(" ");
            for (Map<String, Object> c : related(b, "CHILD")) {
                if ("WORD".equals(type(c)) && c.get("Text") != null) j.add(String.valueOf(c.get("Text")));
                else if ("SELECTION_ELEMENT".equals(type(c)) && "SELECTED".equals(c.get("SelectionStatus"))) j.add("X");
            }
            return j.toString();
        }
        String text() {
            StringJoiner j = new StringJoiner("\n");
            for (Map<String, Object> b : all) if ("LINE".equals(type(b)) && b.get("Text") != null) j.add(String.valueOf(b.get("Text")));
            return j.toString();
        }
        Map<String, Object> lines() {
            List<Object> rows = new ArrayList<>();
            for (Map<String, Object> b : all) if ("LINE".equals(type(b))) rows.add(CloudAi.row(page(b), CloudAi.str(b.get("Text")), CloudAi.num(b.get("Confidence"))));
            return CloudAi.table(List.of("page", "text", "confidence"), rows);
        }
        static BigDecimal page(Map<String, Object> b) { BigDecimal p = CloudAi.num(b.get("Page")); return p == null ? BigDecimal.ONE : p; }
        BigDecimal pageCount(Map<String, Object> answer) {
            BigDecimal n = CloudAi.num(CloudAi.map(answer.get("DocumentMetadata")).get("Pages"));
            if (n != null) return n;
            int pages = 0;
            for (Map<String, Object> b : all) if ("PAGE".equals(type(b))) pages++;
            return BigDecimal.valueOf(pages);
        }
        Map<String, Object> keyValues(Map<String, Object> fields) {
            List<Object> rows = new ArrayList<>();
            for (Map<String, Object> b : all) {
                if (!"KEY_VALUE_SET".equals(type(b)) || !(b.get("EntityTypes") instanceof List<?> et) || !et.contains("KEY")) continue;
                String key = words(b).strip();
                StringJoiner v = new StringJoiner(" ");
                for (Map<String, Object> val : related(b, "VALUE")) { String w = words(val).strip(); if (!w.isEmpty()) v.add(w); }
                rows.add(CloudAi.row(key, v.toString(), CloudAi.num(b.get("Confidence")), page(b)));
                if (!key.isEmpty()) fields.putIfAbsent(key, v.toString());
            }
            return CloudAi.table(List.of("key", "value", "confidence", "page"), rows);
        }
        List<Object> tables(boolean headers) {
            List<Object> out = new ArrayList<>();
            for (Map<String, Object> t : all) {
                if (!"TABLE".equals(type(t))) continue;
                List<Object[]> cells = new ArrayList<>();
                for (Map<String, Object> c : related(t, "CHILD")) {
                    if (!"CELL".equals(type(c))) continue;
                    cells.add(new Object[]{CloudAi.intOf(c.get("RowIndex"), 1) - 1, CloudAi.intOf(c.get("ColumnIndex"), 1) - 1,
                            CloudAi.intOf(c.get("RowSpan"), 1), CloudAi.intOf(c.get("ColumnSpan"), 1), words(c)});
                }
                out.add(CloudAi.grid(cells, 0, 0, headers));
            }
            return out;
        }
    }

    // ----- Rekognition -----

    @Action(id = "rekognition_detect_labels", name = "Detect labels in a picture", category = "Rekognition (pictures)", idempotent = true, capabilities = "network",
            description = "Name the objects, scenes and activities in a picture, e.g. Car, Invoice or Person, each with a confidence (Amazon Rekognition).",
            panel = "session, file | max_labels, min_confidence")
    @Output(name = "labels", type = OoaType.TABLE, description = "Columns: name, confidence, parents, instances.")
    @Output(name = "label_names", type = OoaType.LIST, description = "The label names, most confident first.")
    public Map<String, Object> rekognitionDetectLabels(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"aws_ai\"", description = D_SESSION) String session,
            @Input(name = "file", type = OoaType.FILE, description = "PNG or JPEG picture, at most 5 MB.") String file,
            @Input(name = "max_labels", type = OoaType.NUMBER, required = false, defaultValue = "20", min = 1, max = 1000, description = "Most labels to return.") int maxLabels,
            @Input(name = "min_confidence", type = OoaType.NUMBER, required = false, defaultValue = "55", min = 0, max = 100, description = "Leave out labels with a lower confidence (0 to 100).") double minConfidence) {
        AwsAiSession s = ctx.sessionOrOnly(session, "aws_ai", AwsAiSession.class);
        byte[] bytes = CloudAi.readFile(ctx, P, file, MAX_IMAGE, "picture");
        Map<String, Object> a = call(ctx, s, "rekognition", "RekognitionService.DetectLabels", Out.of("Image", Out.of("Bytes", Base64.getEncoder().encodeToString(bytes)),
                "MaxLabels", BigDecimal.valueOf(Math.max(1, maxLabels)), "MinConfidence", BigDecimal.valueOf(minConfidence)));
        List<Object> rows = new ArrayList<>(), names = new ArrayList<>();
        for (Map<String, Object> l : CloudAi.maps(a.get("Labels"))) {
            List<Object> parents = new ArrayList<>();
            for (Map<String, Object> p : CloudAi.maps(l.get("Parents"))) parents.add(CloudAi.str(p.get("Name")));
            rows.add(CloudAi.row(CloudAi.str(l.get("Name")), CloudAi.num(l.get("Confidence")), String.join(", ", parents.stream().map(String::valueOf).toList()),
                    BigDecimal.valueOf(CloudAi.maps(l.get("Instances")).size())));
            names.add(CloudAi.str(l.get("Name")));
        }
        return Out.of("labels", CloudAi.table(List.of("name", "confidence", "parents", "instances"), rows), "label_names", names);
    }

    @Action(id = "rekognition_detect_text", name = "Detect text in a picture", category = "Rekognition (pictures)", idempotent = true, capabilities = "network",
            description = "Read short text in a photo or screenshot, e.g. signs, number plates or labels (Amazon Rekognition).",
            panel = "session, file | min_confidence")
    @Output(name = "text", type = OoaType.STRING, description = "The lines found, one per line.")
    @Output(name = "detections", type = OoaType.TABLE, description = "Columns: text, type (LINE or WORD), confidence.")
    public Map<String, Object> rekognitionDetectText(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"aws_ai\"", description = D_SESSION) String session,
            @Input(name = "file", type = OoaType.FILE, description = "PNG or JPEG picture, at most 5 MB.") String file,
            @Input(name = "min_confidence", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0, max = 100, description = "Leave out text read with a lower confidence (0 to 100).") double minConfidence) {
        AwsAiSession s = ctx.sessionOrOnly(session, "aws_ai", AwsAiSession.class);
        byte[] bytes = CloudAi.readFile(ctx, P, file, MAX_IMAGE, "picture");
        Map<String, Object> a = call(ctx, s, "rekognition", "RekognitionService.DetectText", Out.of("Image", Out.of("Bytes", Base64.getEncoder().encodeToString(bytes))));
        List<Object> rows = new ArrayList<>();
        StringJoiner lines = new StringJoiner("\n");
        for (Map<String, Object> d : CloudAi.maps(a.get("TextDetections"))) {
            BigDecimal c = CloudAi.num(d.get("Confidence"));
            if (c != null && c.doubleValue() < minConfidence) continue;
            rows.add(CloudAi.row(CloudAi.str(d.get("DetectedText")), CloudAi.str(d.get("Type")), c));
            if ("LINE".equals(d.get("Type")) && d.get("DetectedText") != null) lines.add(String.valueOf(d.get("DetectedText")));
        }
        return Out.of("text", lines.toString(), "detections", CloudAi.table(List.of("text", "type", "confidence"), rows));
    }

    // ----- signed calls -----

    static String language(String language) {
        String l = language == null || language.isBlank() ? "en" : language.strip();
        if (!LANGUAGE.matcher(l).matches()) throw new ActionException(P + ".BAD_LANGUAGE", "'" + l + "' is not a language code", "Use a code such as en, es, de or zh-TW.");
        return l;
    }

    /** One JSON 1.1 call: POST / with X-Amz-Target, signed with SigV4 for the service. */
    static Map<String, Object> call(ActionContext ctx, AwsAiSession s, String service, String target, Map<String, Object> body) {
        byte[] payload = JsonLite.write(com.oneofficeautomation.runner.Values.toJsonable(body)).getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", "application/x-amz-json-1.1");
        headers.put("x-amz-target", target);
        return CloudAi.send(ctx, P, name(service), "POST", URI.create(s.url(service) + "/"), signed(s, service, URI.create(s.url(service) + "/"), headers, payload), payload, 60,
                a -> error(name(service), a)).json();
    }

    static Map<String, String> signed(AwsAiSession s, String service, URI uri, Map<String, String> headers, byte[] payload) {
        Map<String, String> h = new LinkedHashMap<>(headers);
        if (s.sessionToken != null) h.put("x-amz-security-token", s.sessionToken.reveal());
        Map<String, String> out = new LinkedHashMap<>(h);
        out.putAll(AwsSigV4.sign("POST", uri, h, payload, s.accessKeyId.reveal().strip(), s.secretKey.reveal().strip(), s.region, service, Instant.now()));
        return out;
    }

    static String name(String service) {
        return switch (service) {
            case "comprehend" -> "Amazon Comprehend";
            case "textract" -> "Amazon Textract";
            case "rekognition" -> "Amazon Rekognition";
            default -> "AWS STS";
        };
    }

    /** Checks the keys with STS GetCallerIdentity (free) and returns the account id. */
    static String callerAccount(ActionContext ctx, AwsAiSession s) {
        byte[] payload = "Action=GetCallerIdentity&Version=2011-06-15".getBytes(StandardCharsets.UTF_8);
        URI uri = URI.create(s.url("sts") + "/");
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", "application/x-www-form-urlencoded; charset=utf-8");
        CloudAi.Answer a = CloudAi.send(ctx, P, "AWS STS", "POST", uri, signed(s, "sts", uri, headers, payload), payload, 30, x -> error("AWS STS", x));
        String account = xmlText(a.text(), "Account");
        if (account == null)
            throw new ActionException(P + ".BAD_RESPONSE", "The identity check did not answer with an AWS account", "Check the region or endpoint, and the proxy between this computer and AWS.");
        return account;
    }

    static String xmlText(String xml, String tag) {
        Matcher m = Pattern.compile("<" + tag + ">([^<]*)</" + tag + ">").matcher(xml == null ? "" : xml);
        return m.find() ? m.group(1).strip() : null;
    }

    /** AWS errors: JSON {"__type": "...#Code", "message"} or the XML of STS, mapped to AWS_AI codes. */
    static ActionException error(String service, CloudAi.Answer a) {
        Map<String, Object> j = a.json();
        String type = CloudAi.str(j.get("__type"));
        if (type == null) type = a.header("x-amzn-ErrorType");
        if (type == null) type = xmlText(a.text(), "Code");
        if (type != null) { type = type.contains("#") ? type.substring(type.lastIndexOf('#') + 1) : type; type = type.contains(":") ? type.substring(0, type.indexOf(':')) : type; }
        String msg = CloudAi.str(j.get("message") != null ? j.get("message") : j.get("Message"));
        if (msg == null) msg = xmlText(a.text(), "Message");
        if (msg == null) msg = CloudAi.rawMessage(a);
        int st = a.status();
        if (type != null && AUTH_TYPES.contains(type))
            return CloudAi.error(P + ".AUTH_FAILED", service, st, type, msg, "AWS did not accept the keys. " + SIGN_IN, false);
        if ("AccessDeniedException".equals(type) || "AccessDenied".equals(type))
            return CloudAi.error(P + ".FORBIDDEN", service, st, type, msg, "The keys' IAM policy does not allow this " + service + " action; ask your AWS administrator.", false);
        if (type != null && BUSY_TYPES.contains(type))
            return CloudAi.error(P + ".THROTTLED", service, st, type, msg, "AWS is limiting requests; try again later or ask for a higher quota.", true);
        if (type != null && SERVER_TYPES.contains(type)) return CloudAi.error(P + ".SERVER_ERROR", service, st, type, msg, service + " had a problem; try again later.", true);
        if ("TextSizeLimitExceededException".equals(type)) return CloudAi.error(P + ".TEXT_TOO_LONG", service, st, type, msg, "Split the text and analyse each part.", false);
        if ("UnsupportedLanguageException".equals(type)) return CloudAi.error(P + ".UNSUPPORTED_LANGUAGE", service, st, type, msg, "Use a language this feature supports.", false);
        if (type != null && DOCUMENT_TYPES.contains(type))
            return CloudAi.error(P + ".BAD_DOCUMENT", service, st, type, msg, "Use a PNG, JPEG or TIFF picture or a one-page PDF within the size limit.", false);
        if ("ResourceNotFoundException".equals(type)) return CloudAi.error(P + ".NOT_FOUND", service, st, type, msg, "Check the region and the endpoint.", false);
        return CloudAi.statusError(P, service, st, type, msg, SIGN_IN);
    }

}
