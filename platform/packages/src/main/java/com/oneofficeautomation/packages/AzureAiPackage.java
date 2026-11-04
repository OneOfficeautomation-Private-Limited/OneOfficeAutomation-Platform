package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Ready-made Azure AI services on one Azure AI resource (or a multi-service resource): Language (sentiment, entities,
 * personal data), Document Intelligence (read, invoices, receipts, layout; the long-running analysis is followed
 * until it ends or the time limit passes) and Image Analysis 4.0 (tags, caption, text). Connect keeps the resource's
 * endpoint and its key (or a Microsoft Entra access token) from the vault.
 */
@OoaPackage(id = "ooa.azure_ai", name = "Azure AI services", version = "1.0.0",
        description = "Read sentiment, entities and personal data in text with Azure AI Language, text, fields and tables from documents with Document Intelligence, and tags, captions and text in pictures with Image Analysis.")
public final class AzureAiPackage {
    static final String P = "AZURE_AI";
    static final String LANGUAGE_API = "2023-04-01", DOCUMENT_API = "2024-11-30", VISION_API = "2024-02-01";
    static final int MAX_TEXT = 5120;
    static final long MAX_DOCUMENT = 50L * 1024 * 1024, MAX_IMAGE = 20L * 1024 * 1024;
    private static final Pattern PAGES = Pattern.compile("^\\d+(-\\d+)?(,\\d+(-\\d+)?)*$");
    private static final Pattern LANGUAGE = Pattern.compile("^[a-zA-Z]{2,3}(-[a-zA-Z0-9]{2,8})*$");
    private static final List<String> VISION_FEATURES = List.of("tags", "caption", "read", "objects");
    private static final String D_SESSION = "Name of the session opened by Connect.";
    private static final String D_LANG = "Language of the text, e.g. en, de, fr, es or hi.";
    private static final String SIGN_IN = "Check that the key in the vault belongs to this Azure AI resource (or that the access token is current) and that the endpoint is the resource's own address.";

    /** What Connect keeps: the resource address and how to sign in (the key stays a SecureString). */
    static final class AzureAiSession {
        final String endpoint;
        final SecureString credential;
        final boolean token;
        AzureAiSession(String endpoint, SecureString credential, boolean token) { this.endpoint = endpoint; this.credential = credential; this.token = token; }
        Map<String, String> auth() {
            Map<String, String> h = new LinkedHashMap<>();
            if (token) h.put("Authorization", "Bearer " + credential.reveal().strip());
            else h.put("Ocp-Apim-Subscription-Key", credential.reveal().strip());
            return h;
        }
    }

    // ----- connection -----

    @Action(id = "connect", name = "Connect", category = "Connection",
            description = "Open a session for an Azure AI resource with its endpoint and its key from the vault; the Language, Document Intelligence and Vision steps use it.",
            panel = "session, endpoint, credential | credential_type")
    public Map<String, Object> connect(ActionContext ctx,
            @Input(name = "endpoint", type = OoaType.STRING, pattern = HttpJson.HTTPS_OR_LOCAL, example = "https://contoso-ai.cognitiveservices.azure.com",
                    description = "The resource's endpoint from the Azure portal (Keys and Endpoint), e.g. https://contoso-ai.cognitiveservices.azure.com.") String endpoint,
            @Input(name = "credential", type = OoaType.CREDENTIAL, description = "The resource's key from the vault (or a Microsoft Entra access token, see credential_type).") Object credential,
            @Input(name = "credential_type", type = OoaType.STRING, required = false, enumValues = {"key", "access_token"}, defaultValue = "\"key\"",
                    description = "key: the resource key, sent as Ocp-Apim-Subscription-Key. access_token: a Microsoft Entra token for the resource, sent as Bearer.") String credentialType,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"azure_ai\"", description = "Session name.") String session) {
        String ep = endpoint == null ? "" : endpoint.strip().replaceAll("/+$", "");
        if (!Pattern.compile(HttpJson.HTTPS_OR_LOCAL).matcher(ep).find())
            throw new ActionException(P + ".BAD_ENDPOINT", "'" + ep + "' is not an https address", "Copy the endpoint from the resource's Keys and Endpoint page in the Azure portal.");
        SecureString c = CloudAi.secret(credential);
        if (c == null) throw new ActionException(P + ".NO_KEY", "The resource key from the vault is required", "Map credential to a Credential.");
        String kind = credentialType == null || credentialType.isBlank() ? "key" : credentialType.strip();
        if (!kind.equals("key") && !kind.equals("access_token")) throw new ActionException(P + ".BAD_CREDENTIAL_TYPE", "Unknown credential_type '" + kind + "'", "Use key or access_token.");
        CloudAi.sessionName(session, "azure_ai", ctx.sessions(), new AzureAiSession(ep, c, kind.equals("access_token")));
        return Map.of();
    }

    @Action(id = "disconnect", name = "Disconnect", category = "Connection", description = "Close the Azure AI session and forget its key.", panel = "session")
    public Map<String, Object> disconnect(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"azure_ai\"", description = "Session name.") String session) {
        ctx.sessions().remove(session);
        return Map.of();
    }

    // ----- Language -----

    @Action(id = "language_sentiment", name = "Analyse sentiment", category = "Language (text)", idempotent = true, capabilities = "network",
            description = "Read whether a text is positive, neutral, negative or mixed, overall and sentence by sentence (Azure AI Language).",
            panel = "session, text, language")
    @Output(name = "sentiment", type = OoaType.STRING, description = "positive, neutral, negative or mixed.")
    @Output(name = "scores", type = OoaType.DICTIONARY, description = "Confidence from 0 to 1 for each, e.g. {\"positive\": 0.95, \"neutral\": 0.04, \"negative\": 0.01}.")
    @Output(name = "sentences", type = OoaType.TABLE, description = "Columns: text, sentiment, positive, neutral, negative.")
    public Map<String, Object> languageSentiment(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"azure_ai\"", description = D_SESSION) String session,
            @Input(name = "text", type = OoaType.STRING, description = "The text to read, at most 5,120 characters.") String text,
            @Input(name = "language", type = OoaType.STRING, required = false, defaultValue = "\"en\"", description = D_LANG) String language) {
        Map<String, Object> d = analyzeText(ctx, ctx.sessionOrOnly(session, "azure_ai", AzureAiSession.class), "SentimentAnalysis", text, language, Out.of("opinionMining", false));
        List<Object> rows = new ArrayList<>();
        for (Map<String, Object> s : CloudAi.maps(d.get("sentences"))) {
            Map<String, Object> c = CloudAi.map(s.get("confidenceScores"));
            rows.add(CloudAi.row(CloudAi.str(s.get("text")), CloudAi.str(s.get("sentiment")), CloudAi.num(c.get("positive")), CloudAi.num(c.get("neutral")), CloudAi.num(c.get("negative"))));
        }
        Map<String, Object> c = CloudAi.map(d.get("confidenceScores"));
        return Out.of("sentiment", CloudAi.str(d.get("sentiment")),
                "scores", Out.of("positive", CloudAi.num(c.get("positive")), "neutral", CloudAi.num(c.get("neutral")), "negative", CloudAi.num(c.get("negative"))),
                "sentences", CloudAi.table(List.of("text", "sentiment", "positive", "neutral", "negative"), rows));
    }

    @Action(id = "language_entities", name = "Recognise entities", category = "Language (text)", idempotent = true, capabilities = "network",
            description = "Find people, places, organisations, dates, quantities and other named things in a text (Azure AI Language).",
            panel = "session, text, language | min_confidence")
    @Output(name = "entities", type = OoaType.TABLE, description = "Columns: text, category, subcategory, confidence, offset, length.")
    @Output(name = "count", type = OoaType.NUMBER, description = "How many entities were found.")
    public Map<String, Object> languageEntities(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"azure_ai\"", description = D_SESSION) String session,
            @Input(name = "text", type = OoaType.STRING, description = "The text to read, at most 5,120 characters.") String text,
            @Input(name = "language", type = OoaType.STRING, required = false, defaultValue = "\"en\"", description = D_LANG) String language,
            @Input(name = "min_confidence", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0, max = 1, description = "Leave out entities with a lower confidence (0 to 1).") double min) {
        Map<String, Object> d = analyzeText(ctx, ctx.sessionOrOnly(session, "azure_ai", AzureAiSession.class), "EntityRecognition", text, language, null);
        List<Object> rows = entityRows(d, min, true);
        return Out.of("entities", CloudAi.table(List.of("text", "category", "subcategory", "confidence", "offset", "length"), rows), "count", BigDecimal.valueOf(rows.size()));
    }

    @Action(id = "language_pii", name = "Find personal data", category = "Language (text)", idempotent = true, capabilities = "network",
            description = "Find personal data such as names, phone numbers, emails, addresses and ID or account numbers in a text, and get a masked copy (Azure AI Language).",
            panel = "session, text, language | categories, domain, min_confidence")
    @Output(name = "redacted_text", type = OoaType.STRING, description = "The text with the personal data masked with * characters.")
    @Output(name = "entities", type = OoaType.TABLE, description = "Columns: text, category, subcategory, confidence, offset, length.")
    @Output(name = "count", type = OoaType.NUMBER, description = "How many pieces of personal data were found.")
    public Map<String, Object> languagePii(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"azure_ai\"", description = D_SESSION) String session,
            @Input(name = "text", type = OoaType.STRING, description = "The text to read, at most 5,120 characters.") String text,
            @Input(name = "language", type = OoaType.STRING, required = false, defaultValue = "\"en\"", description = D_LANG) String language,
            @Input(name = "categories", type = OoaType.LIST, required = false,
                    description = "Only look for these kinds, e.g. [\"Email\", \"PhoneNumber\", \"Person\"]. Leave empty for all kinds.") List<Object> categories,
            @Input(name = "domain", type = OoaType.STRING, required = false, enumValues = {"none", "phi"}, defaultValue = "\"none\"",
                    description = "phi also looks for protected health information.") String domain,
            @Input(name = "min_confidence", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0, max = 1, description = "Leave out findings with a lower confidence (0 to 1).") double min) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (domain != null && domain.strip().equals("phi")) params.put("domain", "phi");
        List<Object> cats = new ArrayList<>();
        if (categories != null) for (Object o : categories) if (!Values.text(o).isBlank()) cats.add(Values.text(o).strip());
        if (!cats.isEmpty()) params.put("piiCategories", cats);
        Map<String, Object> d = analyzeText(ctx, ctx.sessionOrOnly(session, "azure_ai", AzureAiSession.class), "PiiEntityRecognition", text, language, params.isEmpty() ? null : params);
        List<Object> rows = entityRows(d, min, true);
        return Out.of("redacted_text", CloudAi.str(d.get("redactedText")),
                "entities", CloudAi.table(List.of("text", "category", "subcategory", "confidence", "offset", "length"), rows), "count", BigDecimal.valueOf(rows.size()));
    }

    static List<Object> entityRows(Map<String, Object> d, double min, boolean sub) {
        List<Object> rows = new ArrayList<>();
        for (Map<String, Object> e : CloudAi.maps(d.get("entities"))) {
            BigDecimal c = CloudAi.num(e.get("confidenceScore"));
            if (c != null && c.doubleValue() < min) continue;
            rows.add(CloudAi.row(CloudAi.str(e.get("text")), CloudAi.str(e.get("category")), sub ? CloudAi.str(e.get("subcategory")) : null, c,
                    CloudAi.num(e.get("offset")), CloudAi.num(e.get("length"))));
        }
        return rows;
    }

    /** One :analyze-text call for one document; returns that document's result or fails with the service's document error. */
    static Map<String, Object> analyzeText(ActionContext ctx, AzureAiSession s, String kind, String text, String language, Map<String, Object> params) {
        String t = CloudAi.text(P, text, MAX_TEXT, true, "Azure AI Language");
        String lang = language == null || language.isBlank() ? "en" : language.strip();
        if (!LANGUAGE.matcher(lang).matches()) throw new ActionException(P + ".BAD_LANGUAGE", "'" + lang + "' is not a language code", "Use a code such as en, de, fr or zh-Hans.");
        Map<String, Object> body = Out.of("kind", kind, "analysisInput", Out.of("documents", List.of(Out.of("id", "1", "language", lang, "text", t))));
        if (params != null) body.put("parameters", params);
        Map<String, Object> a = json(ctx, s, "Azure AI Language", "POST", s.endpoint + "/language/:analyze-text?api-version=" + LANGUAGE_API, body);
        Map<String, Object> results = CloudAi.map(a.get("results"));
        for (Map<String, Object> err : CloudAi.maps(results.get("errors"))) {
            Map<String, Object> e = CloudAi.map(err.get("error"));
            Map<String, Object> inner = CloudAi.map(e.get("innererror"));
            String code = CloudAi.str(inner.get("code") != null ? inner.get("code") : e.get("code"));
            String msg = CloudAi.str(inner.get("message") != null ? inner.get("message") : e.get("message"));
            throw new ActionException(P + ".DOCUMENT_ERROR", "Azure AI Language could not read the text: " + (code == null ? "" : code + ": ") + msg,
                    "Check the text and the language code.", false, code == null ? Map.of() : Map.of("service_code", code));
        }
        List<Map<String, Object>> docs = CloudAi.maps(results.get("documents"));
        if (docs.isEmpty()) throw new ActionException(P + ".BAD_RESPONSE", "Azure AI Language answered without a result", "Check the endpoint is an Azure AI Language or multi-service resource.");
        for (Map<String, Object> w : CloudAi.maps(docs.get(0).get("warnings"))) ctx.warn("Azure AI Language: " + w.get("message"));
        return docs.get(0);
    }

    // ----- Document Intelligence -----

    @Action(id = "document_read", name = "Read a document", category = "Document Intelligence", idempotent = true, capabilities = "network",
            description = "Read all printed and handwritten text of a PDF, picture or Office file with the prebuilt read model, page by page (Azure AI Document Intelligence).",
            panel = "session, file | pages, locale, timeout_seconds")
    @Output(name = "text", type = OoaType.STRING, description = "All text in reading order.")
    @Output(name = "lines", type = OoaType.TABLE, description = "Columns: page, text.")
    @Output(name = "page_count", type = OoaType.NUMBER, description = "Number of pages read.")
    @Output(name = "languages", type = OoaType.LIST, description = "Languages found, e.g. [\"en\", \"de\"].")
    public Map<String, Object> documentRead(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"azure_ai\"", description = D_SESSION) String session,
            @Input(name = "file", type = OoaType.FILE, description = "PDF, picture (JPEG, PNG, BMP, TIFF, HEIF) or Office file; at most 50 MB.") String file,
            @Input(name = "pages", type = OoaType.STRING, required = false, description = "Only these pages, e.g. 1-3,5. Leave empty for all.") String pages,
            @Input(name = "locale", type = OoaType.STRING, required = false, description = "Language hint, e.g. en-US or de-DE. Leave empty to detect it.") String locale,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "120", min = 5, max = 3600, description = "Fail if the analysis has not finished after this many seconds.") int timeout) {
        AzureAiSession s = ctx.sessionOrOnly(session, "azure_ai", AzureAiSession.class);
        Map<String, Object> r = analyzeDocument(ctx, s, "prebuilt-read", CloudAi.readFile(ctx, P, file, MAX_DOCUMENT, "document"), pages, locale, null, timeout);
        List<Object> lines = new ArrayList<>();
        for (Map<String, Object> p : CloudAi.maps(r.get("pages")))
            for (Map<String, Object> l : CloudAi.maps(p.get("lines"))) lines.add(CloudAi.row(CloudAi.num(p.get("pageNumber")), CloudAi.str(l.get("content"))));
        Set<Object> langs = new LinkedHashSet<>();
        for (Map<String, Object> l : CloudAi.maps(r.get("languages"))) if (l.get("locale") != null) langs.add(CloudAi.str(l.get("locale")));
        return Out.of("text", Objects.toString(r.get("content"), ""), "lines", CloudAi.table(List.of("page", "text"), lines),
                "page_count", BigDecimal.valueOf(CloudAi.maps(r.get("pages")).size()), "languages", new ArrayList<>(langs));
    }

    @Action(id = "document_analyze", name = "Analyse a document", category = "Document Intelligence", idempotent = true, capabilities = "network",
            description = "Read an invoice, receipt or any document's layout with a prebuilt model: its fields (e.g. vendor, total, date), key and value pairs and tables, each table as a Data Table (Azure AI Document Intelligence).",
            panel = "session, file, model | key_value_pairs, pages, locale, timeout_seconds")
    @Output(name = "text", type = OoaType.STRING, description = "All text in reading order.")
    @Output(name = "document_type", type = OoaType.STRING, description = "The kind of document found, e.g. invoice or receipt.retailMeal (empty for layout).")
    @Output(name = "fields", type = OoaType.DICTIONARY, description = "Field name to value, e.g. {\"InvoiceId\": \"INV-100\", \"InvoiceTotal\": 110}; line items become a Data Table.")
    @Output(name = "fields_table", type = OoaType.TABLE, description = "Columns: name, value, type, confidence.")
    @Output(name = "key_values", type = OoaType.TABLE, description = "Key and value pairs found on the pages. Columns: key, value, confidence.")
    @Output(name = "tables", type = OoaType.LIST, description = "One Data Table per table found.")
    @Output(name = "table_count", type = OoaType.NUMBER, description = "How many tables were found.")
    public Map<String, Object> documentAnalyze(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"azure_ai\"", description = D_SESSION) String session,
            @Input(name = "file", type = OoaType.FILE, description = "PDF, picture (JPEG, PNG, BMP, TIFF, HEIF) or Office file; at most 50 MB.") String file,
            @Input(name = "model", type = OoaType.STRING, required = false, enumValues = {"prebuilt-invoice", "prebuilt-receipt", "prebuilt-layout"}, defaultValue = "\"prebuilt-invoice\"",
                    description = "prebuilt-invoice: invoice fields and line items. prebuilt-receipt: receipt fields. prebuilt-layout: text, tables and structure of any document.") String model,
            @Input(name = "key_value_pairs", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Also find key and value pairs, e.g. \"Order no.: 4711\" (an add-on feature).") boolean keyValues,
            @Input(name = "pages", type = OoaType.STRING, required = false, description = "Only these pages, e.g. 1-3,5. Leave empty for all.") String pages,
            @Input(name = "locale", type = OoaType.STRING, required = false, description = "Language hint, e.g. en-US or de-DE. Leave empty to detect it.") String locale,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "120", min = 5, max = 3600, description = "Fail if the analysis has not finished after this many seconds.") int timeout) {
        String m = model == null || model.isBlank() ? "prebuilt-invoice" : model.strip();
        if (!List.of("prebuilt-invoice", "prebuilt-receipt", "prebuilt-layout").contains(m))
            throw new ActionException(P + ".BAD_MODEL", "Unknown model '" + m + "'", "Use prebuilt-invoice, prebuilt-receipt or prebuilt-layout.");
        AzureAiSession s = ctx.sessionOrOnly(session, "azure_ai", AzureAiSession.class);
        Map<String, Object> r = analyzeDocument(ctx, s, m, CloudAi.readFile(ctx, P, file, MAX_DOCUMENT, "document"), pages, locale, keyValues ? "keyValuePairs" : null, timeout);
        Map<String, Object> fields = new LinkedHashMap<>();
        List<Object> fieldRows = new ArrayList<>();
        String docType = "";
        List<Map<String, Object>> docs = CloudAi.maps(r.get("documents"));
        if (!docs.isEmpty()) {
            docType = Objects.toString(docs.get(0).get("docType"), "");
            for (var e : CloudAi.map(docs.get(0).get("fields")).entrySet()) {
                Map<String, Object> f = CloudAi.map(e.getValue());
                Object v = fieldValue(f);
                fields.put(e.getKey(), v);
                fieldRows.add(CloudAi.row(e.getKey(), v instanceof Map<?, ?> || v instanceof List<?> ? Objects.toString(f.get("content"), Values.text(v)) : v,
                        CloudAi.str(f.get("type")), CloudAi.num(f.get("confidence"))));
            }
        }
        List<Object> kv = new ArrayList<>();
        for (Map<String, Object> p : CloudAi.maps(r.get("keyValuePairs")))
            kv.add(CloudAi.row(CloudAi.str(CloudAi.map(p.get("key")).get("content")), Objects.toString(CloudAi.map(p.get("value")).get("content"), ""), CloudAi.num(p.get("confidence"))));
        List<Object> tables = tables(r);
        return Out.of("text", Objects.toString(r.get("content"), ""), "document_type", docType, "fields", fields,
                "fields_table", CloudAi.table(List.of("name", "value", "type", "confidence"), fieldRows),
                "key_values", CloudAi.table(List.of("key", "value", "confidence"), kv), "tables", tables, "table_count", BigDecimal.valueOf(tables.size()));
    }

    /** A field's value by its type; arrays of objects (e.g. invoice line items) become a Data Table. */
    static Object fieldValue(Map<String, Object> f) {
        String type = Objects.toString(f.get("type"), "string");
        switch (type) {
            case "currency" -> { Object amount = CloudAi.map(f.get("valueCurrency")).get("amount"); return amount != null ? CloudAi.num(amount) : f.get("content"); }
            case "array" -> {
                List<Map<String, Object>> items = CloudAi.maps(f.get("valueArray"));
                boolean objects = !items.isEmpty() && items.stream().allMatch(i -> "object".equals(i.get("type")));
                if (objects) {
                    List<String> cols = new ArrayList<>();
                    for (Map<String, Object> i : items) for (String k : CloudAi.map(i.get("valueObject")).keySet()) if (!cols.contains(k)) cols.add(k);
                    List<Object> rows = new ArrayList<>();
                    for (Map<String, Object> i : items) {
                        Map<String, Object> o = CloudAi.map(i.get("valueObject"));
                        List<Object> row = new ArrayList<>();
                        for (String c : cols) row.add(o.containsKey(c) ? fieldValue(CloudAi.map(o.get(c))) : null);
                        rows.add(row);
                    }
                    return Values.table(cols, rows);
                }
                List<Object> out = new ArrayList<>();
                for (Map<String, Object> i : items) out.add(fieldValue(i));
                return out;
            }
            case "object" -> {
                Map<String, Object> out = new LinkedHashMap<>();
                for (var e : CloudAi.map(f.get("valueObject")).entrySet()) out.put(e.getKey(), fieldValue(CloudAi.map(e.getValue())));
                return out;
            }
            default -> {
                String key = "value" + Character.toUpperCase(type.charAt(0)) + type.substring(1);
                Object v = f.get(key);
                if (v instanceof Map<?, ?> || v == null) return f.get("content") == null ? null : CloudAi.str(f.get("content"));
                return v;
            }
        }
    }

    static List<Object> tables(Map<String, Object> r) {
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> t : CloudAi.maps(r.get("tables"))) {
            List<Object[]> cells = new ArrayList<>();
            boolean header = false;
            for (Map<String, Object> c : CloudAi.maps(t.get("cells"))) {
                int row = CloudAi.intOf(c.get("rowIndex"), 0);
                if (row == 0 && "columnHeader".equals(c.get("kind"))) header = true;
                cells.add(new Object[]{row, CloudAi.intOf(c.get("columnIndex"), 0), CloudAi.intOf(c.get("rowSpan"), 1), CloudAi.intOf(c.get("columnSpan"), 1),
                        Objects.toString(c.get("content"), "")});
            }
            out.add(CloudAi.grid(cells, CloudAi.intOf(t.get("rowCount"), 0), CloudAi.intOf(t.get("columnCount"), 0), header));
        }
        return out;
    }

    /**
     * Starts an analysis (POST ...:analyze, answered 202 with Operation-Location) and asks for its result until it has
     * succeeded, failed, or the time limit has passed. The result address must be on the resource's own host.
     */
    static Map<String, Object> analyzeDocument(ActionContext ctx, AzureAiSession s, String model, byte[] bytes, String pages, String locale, String features, int timeout) {
        StringBuilder url = new StringBuilder(s.endpoint).append("/documentintelligence/documentModels/").append(model).append(":analyze?api-version=").append(DOCUMENT_API);
        if (pages != null && !pages.isBlank()) {
            String pg = pages.replace(" ", "");
            if (!PAGES.matcher(pg).matches()) throw new ActionException(P + ".BAD_PAGES", "'" + pages + "' is not a page list", "Use page numbers and ranges such as 1-3,5.");
            url.append("&pages=").append(pg);
        }
        if (locale != null && !locale.isBlank()) {
            if (!LANGUAGE.matcher(locale.strip()).matches()) throw new ActionException(P + ".BAD_LANGUAGE", "'" + locale + "' is not a locale", "Use a locale such as en-US or de-DE.");
            url.append("&locale=").append(locale.strip());
        }
        if (features != null) url.append("&features=").append(features);
        Map<String, String> h = s.auth();
        h.put("Content-Type", "application/json");
        h.put("Accept", "application/json");
        byte[] body = JsonLite.write(Map.of("base64Source", Base64.getEncoder().encodeToString(bytes))).getBytes(StandardCharsets.UTF_8);
        String service = "Azure AI Document Intelligence";
        CloudAi.Answer started = CloudAi.send(ctx, P, service, "POST", uri(url.toString()), h, body, 120, a -> error(service, a));
        String op = started.header("Operation-Location");
        if (op == null || op.isBlank())
            throw new ActionException(P + ".BAD_RESPONSE", service + " accepted the document but gave no Operation-Location to follow", "Check the endpoint is a Document Intelligence or multi-service resource.");
        if (!HttpJson.sameOrigin(s.endpoint, op))
            throw new ActionException(P + ".BAD_RESPONSE", "The result address " + HttpJson.host(op) + " is not on the resource's host; the key is not sent there", "Check the endpoint and any proxy in between.");
        long deadline = System.nanoTime() + Math.max(1, timeout) * 1_000_000_000L;
        long wait = waitMillis(started.header("Retry-After"));
        Map<String, String> pollHeaders = s.auth();
        pollHeaders.put("Accept", "application/json");
        while (true) {
            HttpJson.pause(ctx, wait);
            CloudAi.Answer a = CloudAi.send(ctx, P, service, "GET", uri(op), pollHeaders, null, 60, x -> error(service, x));
            String status = Objects.toString(a.json().get("status"), "");
            switch (status) {
                case "succeeded" -> { return CloudAi.map(a.json().get("analyzeResult")); }
                case "failed", "canceled" -> {
                    Map<String, Object> e = CloudAi.map(a.json().get("error"));
                    Map<String, Object> inner = CloudAi.map(e.get("innererror"));
                    String code = CloudAi.str(inner.get("code") != null ? inner.get("code") : e.get("code"));
                    String msg = CloudAi.str(inner.get("message") != null ? inner.get("message") : e.get("message"));
                    throw new ActionException(P + ".ANALYSIS_FAILED", service + " could not analyse the document" + (code == null ? "" : " (" + code + ")") + ": " + (msg == null ? status : msg),
                            "Check the file is a readable PDF, picture or Office file, and the page list.", false, code == null ? Map.of() : Map.of("service_code", code));
                }
                default -> { }
            }
            if (System.nanoTime() > deadline)
                throw new ActionException(P + ".ANALYSIS_TIMEOUT", "The analysis had not finished after " + timeout + " s (status " + (status.isEmpty() ? "unknown" : status) + ")",
                        "Raise timeout_seconds, or send fewer pages.", true, Map.of("operation", op));
            wait = waitMillis(a.header("Retry-After"));
        }
    }

    /** Wait before the next status check: the service's Retry-After (0.1 to 10 s), or 1 s. */
    static long waitMillis(String retryAfter) {
        if (retryAfter == null || retryAfter.isBlank()) return 1000;
        try { return Math.max(100, Math.min(10_000, (long) (Double.parseDouble(retryAfter.strip()) * 1000))); }
        catch (NumberFormatException e) { return 1000; }
    }

    // ----- Vision -----

    @Action(id = "vision_analyze", name = "Analyse a picture", category = "Vision (pictures)", idempotent = true, capabilities = "network",
            description = "Describe a picture in a sentence, tag what is in it, find objects and read its text (Azure AI Vision Image Analysis 4.0).",
            panel = "session, file, features | language, gender_neutral_caption")
    @Output(name = "caption", type = OoaType.STRING, description = "A sentence describing the picture (when caption is asked for).")
    @Output(name = "caption_confidence", type = OoaType.NUMBER, description = "Confidence of the caption (0 to 1).")
    @Output(name = "tags", type = OoaType.TABLE, description = "Columns: name, confidence.")
    @Output(name = "tag_names", type = OoaType.LIST, description = "The tag names.")
    @Output(name = "objects", type = OoaType.TABLE, description = "Columns: name, confidence, x, y, width, height.")
    @Output(name = "text", type = OoaType.STRING, description = "Text read from the picture, one line per line (when read is asked for).")
    @Output(name = "lines", type = OoaType.TABLE, description = "Columns: text, confidence (the lowest word confidence in the line).")
    public Map<String, Object> visionAnalyze(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"azure_ai\"", description = D_SESSION) String session,
            @Input(name = "file", type = OoaType.FILE, description = "JPEG, PNG, GIF, BMP, WEBP, ICO, TIFF or MPO picture, at most 20 MB.") String file,
            @Input(name = "features", type = OoaType.LIST, required = false, defaultValue = "[\"tags\", \"caption\", \"read\"]",
                    description = "What to find: any of tags, caption, read, objects.") List<Object> features,
            @Input(name = "language", type = OoaType.STRING, required = false, defaultValue = "\"en\"", description = "Language of the tags, e.g. en, de or fr (captions are in English).") String language,
            @Input(name = "gender_neutral_caption", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Say \"person\" rather than \"man\" or \"woman\" in the caption.") boolean neutral) {
        List<String> feats = new ArrayList<>();
        if (features != null) for (Object o : features) {
            String f = Values.text(o).strip();
            if (f.isEmpty()) continue;
            if (!VISION_FEATURES.contains(f)) throw new ActionException(P + ".BAD_FEATURE", "Unknown feature '" + f + "'", "Use any of " + String.join(", ", VISION_FEATURES) + ".");
            if (!feats.contains(f)) feats.add(f);
        }
        if (feats.isEmpty()) feats.addAll(List.of("tags", "caption", "read"));
        String lang = language == null || language.isBlank() ? "en" : language.strip();
        if (!LANGUAGE.matcher(lang).matches()) throw new ActionException(P + ".BAD_LANGUAGE", "'" + lang + "' is not a language code", "Use a code such as en, de or fr.");
        AzureAiSession s = ctx.sessionOrOnly(session, "azure_ai", AzureAiSession.class);
        byte[] bytes = CloudAi.readFile(ctx, P, file, MAX_IMAGE, "picture");
        String url = s.endpoint + "/computervision/imageanalysis:analyze?api-version=" + VISION_API + "&features=" + String.join(",", feats) + "&language=" + lang
                + (feats.contains("caption") ? "&gender-neutral-caption=" + neutral : "");
        Map<String, String> h = s.auth();
        h.put("Content-Type", "application/octet-stream");
        h.put("Accept", "application/json");
        String service = "Azure AI Vision";
        Map<String, Object> a = CloudAi.send(ctx, P, service, "POST", uri(url), h, bytes, 60, x -> error(service, x)).json();
        Map<String, Object> cap = CloudAi.map(a.get("captionResult"));
        List<Object> tags = new ArrayList<>(), names = new ArrayList<>(), objects = new ArrayList<>(), lines = new ArrayList<>();
        for (Map<String, Object> t : CloudAi.maps(CloudAi.map(a.get("tagsResult")).get("values"))) {
            tags.add(CloudAi.row(CloudAi.str(t.get("name")), CloudAi.num(t.get("confidence"))));
            names.add(CloudAi.str(t.get("name")));
        }
        for (Map<String, Object> o : CloudAi.maps(CloudAi.map(a.get("objectsResult")).get("values"))) {
            List<Map<String, Object>> t = CloudAi.maps(o.get("tags"));
            Map<String, Object> box = CloudAi.map(o.get("boundingBox"));
            objects.add(CloudAi.row(t.isEmpty() ? null : CloudAi.str(t.get(0).get("name")), t.isEmpty() ? null : CloudAi.num(t.get(0).get("confidence")),
                    CloudAi.num(box.get("x")), CloudAi.num(box.get("y")), CloudAi.num(box.get("w")), CloudAi.num(box.get("h"))));
        }
        StringJoiner text = new StringJoiner("\n");
        for (Map<String, Object> b : CloudAi.maps(CloudAi.map(a.get("readResult")).get("blocks")))
            for (Map<String, Object> l : CloudAi.maps(b.get("lines"))) {
                BigDecimal low = null;
                for (Map<String, Object> w : CloudAi.maps(l.get("words"))) { BigDecimal c = CloudAi.num(w.get("confidence")); if (c != null && (low == null || c.compareTo(low) < 0)) low = c; }
                lines.add(CloudAi.row(CloudAi.str(l.get("text")), low));
                text.add(Objects.toString(l.get("text"), ""));
            }
        return Out.of("caption", CloudAi.str(cap.get("text")), "caption_confidence", CloudAi.num(cap.get("confidence")),
                "tags", CloudAi.table(List.of("name", "confidence"), tags), "tag_names", names,
                "objects", CloudAi.table(List.of("name", "confidence", "x", "y", "width", "height"), objects),
                "text", text.toString(), "lines", CloudAi.table(List.of("text", "confidence"), lines));
    }

    // ----- calls -----

    static URI uri(String url) {
        try { return URI.create(url); }
        catch (IllegalArgumentException e) { throw new ActionException(P + ".BAD_URL", "Invalid address: " + url, "Check the endpoint given to Connect."); }
    }

    static Map<String, Object> json(ActionContext ctx, AzureAiSession s, String service, String method, String url, Map<String, Object> body) {
        Map<String, String> h = s.auth();
        h.put("Content-Type", "application/json");
        h.put("Accept", "application/json");
        byte[] payload = JsonLite.write(Values.toJsonable(body)).getBytes(StandardCharsets.UTF_8);
        return CloudAi.send(ctx, P, service, method, uri(url), h, payload, 60, a -> error(service, a)).json();
    }

    /** Azure errors: {"error": {"code", "message", "innererror"}}; 401/403 explain how to sign in. */
    static ActionException error(String service, CloudAi.Answer a) {
        Map<String, Object> e = CloudAi.map(a.json().get("error"));
        Map<String, Object> inner = CloudAi.map(e.get("innererror"));
        String code = CloudAi.str(inner.get("code") != null ? inner.get("code") : e.get("code"));
        String msg = CloudAi.str(inner.get("message") != null ? inner.get("message") : e.get("message"));
        if (msg == null) msg = CloudAi.str(a.json().get("message"));
        if (msg == null) msg = CloudAi.rawMessage(a);
        return CloudAi.statusError(P, service, a.status(), code, msg, SIGN_IN);
    }
}
