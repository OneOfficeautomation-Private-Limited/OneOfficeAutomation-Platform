package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Ready-made Google Cloud AI: Natural Language v1 (sentiment, entities), Vision v1 (labels, text) and Document AI
 * (any processor: text, entities, form fields and tables). Connect keeps an API key or an OAuth access token from the
 * vault, and the project and location that Document AI processors live in.
 */
@OoaPackage(id = "ooa.google_ai", name = "Google Cloud AI", version = "1.0.0",
        description = "Read sentiment and entities in text with Cloud Natural Language, labels and text in pictures with Cloud Vision, and text, entities, form fields and tables from documents with Document AI.")
public final class GoogleAiPackage {
    static final String P = "GOOGLE_AI";
    static final int MAX_TEXT = 1_000_000;
    static final long MAX_IMAGE = 7L * 1024 * 1024, MAX_DOCUMENT = 20L * 1024 * 1024;
    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]*$");
    private static final Pattern LANGUAGE = Pattern.compile("^[a-zA-Z]{2,3}(-[a-zA-Z0-9]{2,8})*$");
    private static final String D_SESSION = "Name of the session opened by Connect.";
    private static final String SIGN_IN = "Check the API key or access token in the vault (access tokens expire after about an hour), that the API is enabled in the project, and that the key's restrictions allow it.";

    /** What Connect keeps: how to sign in, the project and location, and an optional address override. */
    static final class GoogleAiSession {
        final SecureString credential;
        final boolean token;
        final String project, location, endpoint;
        GoogleAiSession(SecureString credential, boolean token, String project, String location, String endpoint) {
            this.credential = credential; this.token = token; this.project = project; this.location = location; this.endpoint = endpoint;
        }
        String url(String service, String dflt) { return CloudAi.endpoint(endpoint, service, dflt); }
        Map<String, String> auth() {
            Map<String, String> h = new LinkedHashMap<>();
            if (token) {
                h.put("Authorization", "Bearer " + credential.reveal().strip());
                if (project != null) h.put("x-goog-user-project", project);
            } else h.put("x-goog-api-key", credential.reveal().strip());
            h.put("Content-Type", "application/json; charset=utf-8");
            h.put("Accept", "application/json");
            return h;
        }
    }

    // ----- connection -----

    @Action(id = "connect", name = "Connect", category = "Connection",
            description = "Open a session with an API key or OAuth access token from the vault, and the project and location of your Document AI processors.",
            panel = "session, credential, credential_type | project, location, endpoint")
    public Map<String, Object> connect(ActionContext ctx,
            @Input(name = "credential", type = OoaType.CREDENTIAL, description = "API key or OAuth access token from the vault.") Object credential,
            @Input(name = "credential_type", type = OoaType.STRING, required = false, enumValues = {"api_key", "access_token"}, defaultValue = "\"api_key\"",
                    description = "api_key: a Google Cloud API key (Natural Language and Vision). access_token: an OAuth token of a service account or user (needed for Document AI).") String credentialType,
            @Input(name = "project", type = OoaType.STRING, required = false, example = "acme-ai-prod", description = "Google Cloud project id or number; needed for Document AI, and billed for calls made with an access token.") String project,
            @Input(name = "location", type = OoaType.STRING, required = false, enumValues = {"us", "eu"}, defaultValue = "\"us\"", description = "Location of the Document AI processors: us or eu.") String location,
            @Input(name = "endpoint", type = OoaType.STRING, required = false, pattern = HttpJson.HTTPS_OR_LOCAL,
                    description = "Another address for the APIs, e.g. a Private Service Connect address; {service} in it becomes language, vision or us-documentai (eu-documentai). Leave empty for Google's addresses.") String endpoint,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"google_ai\"", description = "Session name.") String session) {
        SecureString c = CloudAi.secret(credential);
        if (c == null) throw new ActionException(P + ".NO_CREDENTIAL", "An API key or access token from the vault is required", "Map credential to a Credential.");
        String kind = credentialType == null || credentialType.isBlank() ? "api_key" : credentialType.strip();
        if (!kind.equals("api_key") && !kind.equals("access_token")) throw new ActionException(P + ".BAD_CREDENTIAL_TYPE", "Unknown credential_type '" + kind + "'", "Use api_key or access_token.");
        String proj = project == null || project.isBlank() ? null : project.strip();
        if (proj != null && !ID.matcher(proj).matches()) throw new ActionException(P + ".BAD_PROJECT", "'" + proj + "' is not a project id", "Use the project id, e.g. acme-ai-prod, or its number.");
        String loc = location == null || location.isBlank() ? "us" : location.strip();
        if (!loc.matches("^[a-z][a-z0-9-]*$")) throw new ActionException(P + ".BAD_LOCATION", "'" + loc + "' is not a location", "Use us or eu.");
        String ep = endpoint == null || endpoint.isBlank() ? null : endpoint.strip();
        if (ep != null && !Pattern.compile(HttpJson.HTTPS_OR_LOCAL).matcher(ep).find())
            throw new ActionException(P + ".BAD_ENDPOINT", "'" + ep + "' is not an https address", "Leave endpoint empty for Google's own addresses.");
        CloudAi.sessionName(session, "google_ai", ctx.sessions(), new GoogleAiSession(c, kind.equals("access_token"), proj, loc, ep));
        return Map.of();
    }

    @Action(id = "disconnect", name = "Disconnect", category = "Connection", description = "Close the Google Cloud AI session and forget its key.", panel = "session")
    public Map<String, Object> disconnect(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"google_ai\"", description = "Session name.") String session) {
        ctx.sessions().remove(session);
        return Map.of();
    }

    // ----- Natural Language -----

    @Action(id = "language_sentiment", name = "Analyse sentiment", category = "Natural Language (text)", idempotent = true, capabilities = "network",
            description = "Read how positive or negative a text is, overall and sentence by sentence (Cloud Natural Language).",
            panel = "session, text, language")
    @Output(name = "sentiment", type = OoaType.STRING, description = "positive (score 0.25 or more), negative (-0.25 or less) or neutral.")
    @Output(name = "score", type = OoaType.NUMBER, description = "From -1 (negative) to 1 (positive).")
    @Output(name = "magnitude", type = OoaType.NUMBER, description = "How strong the feeling is overall, from 0 up.")
    @Output(name = "language", type = OoaType.STRING, description = "Language of the text, as given or detected.")
    @Output(name = "sentences", type = OoaType.TABLE, description = "Columns: text, score, magnitude.")
    public Map<String, Object> languageSentiment(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"google_ai\"", description = D_SESSION) String session,
            @Input(name = "text", type = OoaType.STRING, description = "The text to read, at most 1,000,000 bytes.") String text,
            @Input(name = "language", type = OoaType.STRING, required = false, description = "Language of the text, e.g. en, de or ja. Leave empty to detect it.") String language) {
        GoogleAiSession s = ctx.sessionOrOnly(session, "google_ai", GoogleAiSession.class);
        Map<String, Object> a = language(ctx, s, "analyzeSentiment", text, language);
        Map<String, Object> ds = CloudAi.map(a.get("documentSentiment"));
        BigDecimal score = CloudAi.num(ds.get("score"));
        List<Object> rows = new ArrayList<>();
        for (Map<String, Object> st : CloudAi.maps(a.get("sentences"))) {
            Map<String, Object> sen = CloudAi.map(st.get("sentiment"));
            rows.add(CloudAi.row(CloudAi.str(CloudAi.map(st.get("text")).get("content")), CloudAi.num(sen.get("score")), CloudAi.num(sen.get("magnitude"))));
        }
        String label = score == null ? null : score.doubleValue() >= 0.25 ? "positive" : score.doubleValue() <= -0.25 ? "negative" : "neutral";
        return Out.of("sentiment", label, "score", score, "magnitude", CloudAi.num(ds.get("magnitude")), "language", CloudAi.str(a.get("language")),
                "sentences", CloudAi.table(List.of("text", "score", "magnitude"), rows));
    }

    @Action(id = "language_entities", name = "Analyse entities", category = "Natural Language (text)", idempotent = true, capabilities = "network",
            description = "Find people, places, organisations, events, products, dates, prices and other named things in a text, ranked by importance (Cloud Natural Language).",
            panel = "session, text, language")
    @Output(name = "entities", type = OoaType.TABLE, description = "Columns: name, type, salience, mentions, wikipedia_url.")
    @Output(name = "count", type = OoaType.NUMBER, description = "How many entities were found.")
    @Output(name = "language", type = OoaType.STRING, description = "Language of the text, as given or detected.")
    public Map<String, Object> languageEntities(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"google_ai\"", description = D_SESSION) String session,
            @Input(name = "text", type = OoaType.STRING, description = "The text to read, at most 1,000,000 bytes.") String text,
            @Input(name = "language", type = OoaType.STRING, required = false, description = "Language of the text, e.g. en, de or ja. Leave empty to detect it.") String language) {
        GoogleAiSession s = ctx.sessionOrOnly(session, "google_ai", GoogleAiSession.class);
        Map<String, Object> a = language(ctx, s, "analyzeEntities", text, language);
        List<Object> rows = new ArrayList<>();
        for (Map<String, Object> e : CloudAi.maps(a.get("entities")))
            rows.add(CloudAi.row(CloudAi.str(e.get("name")), CloudAi.str(e.get("type")), CloudAi.num(e.get("salience")),
                    BigDecimal.valueOf(CloudAi.maps(e.get("mentions")).size()), CloudAi.str(CloudAi.map(e.get("metadata")).get("wikipedia_url"))));
        return Out.of("entities", CloudAi.table(List.of("name", "type", "salience", "mentions", "wikipedia_url"), rows), "count", BigDecimal.valueOf(rows.size()),
                "language", CloudAi.str(a.get("language")));
    }

    static Map<String, Object> language(ActionContext ctx, GoogleAiSession s, String method, String text, String language) {
        Map<String, Object> doc = Out.of("type", "PLAIN_TEXT", "content", CloudAi.text(P, text, MAX_TEXT, false, "Cloud Natural Language"));
        if (language != null && !language.isBlank()) {
            if (!LANGUAGE.matcher(language.strip()).matches()) throw new ActionException(P + ".BAD_LANGUAGE", "'" + language + "' is not a language code", "Use a code such as en, de or ja, or leave it empty.");
            doc.put("language", language.strip());
        }
        return post(ctx, s, "Cloud Natural Language", s.url("language", "https://language.googleapis.com") + "/v1/documents:" + method,
                Out.of("document", doc, "encodingType", "UTF16"));
    }

    // ----- Vision -----

    @Action(id = "vision_labels", name = "Detect labels", category = "Vision (pictures)", idempotent = true, capabilities = "network",
            description = "Name what a picture shows, e.g. Invoice, Car or Building, each with a score (Cloud Vision).",
            panel = "session, file | max_results, min_score")
    @Output(name = "labels", type = OoaType.TABLE, description = "Columns: description, score, topicality, mid.")
    @Output(name = "label_names", type = OoaType.LIST, description = "The label names, best first.")
    public Map<String, Object> visionLabels(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"google_ai\"", description = D_SESSION) String session,
            @Input(name = "file", type = OoaType.FILE, description = "JPEG, PNG, GIF, BMP, WEBP, ICO, TIFF or PDF picture, at most 7 MB.") String file,
            @Input(name = "max_results", type = OoaType.NUMBER, required = false, defaultValue = "10", min = 1, max = 100, description = "Most labels to return.") int max,
            @Input(name = "min_score", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0, max = 1, description = "Leave out labels scored lower (0 to 1).") double minScore) {
        GoogleAiSession s = ctx.sessionOrOnly(session, "google_ai", GoogleAiSession.class);
        Map<String, Object> r = annotate(ctx, s, file, Out.of("type", "LABEL_DETECTION", "maxResults", BigDecimal.valueOf(Math.max(1, max))), null);
        List<Object> rows = new ArrayList<>(), names = new ArrayList<>();
        for (Map<String, Object> l : CloudAi.maps(r.get("labelAnnotations"))) {
            BigDecimal sc = CloudAi.num(l.get("score"));
            if (sc != null && sc.doubleValue() < minScore) continue;
            rows.add(CloudAi.row(CloudAi.str(l.get("description")), sc, CloudAi.num(l.get("topicality")), CloudAi.str(l.get("mid"))));
            names.add(CloudAi.str(l.get("description")));
        }
        return Out.of("labels", CloudAi.table(List.of("description", "score", "topicality", "mid"), rows), "label_names", names);
    }

    @Action(id = "vision_ocr", name = "Read text in a picture", category = "Vision (pictures)", idempotent = true, capabilities = "network",
            description = "Read the text of a photo, scan or screenshot: dense documents or short text such as signs (Cloud Vision).",
            panel = "session, file | mode, language_hints")
    @Output(name = "text", type = OoaType.STRING, description = "All text found.")
    @Output(name = "words", type = OoaType.TABLE, description = "Columns: text (one row per word or piece of text found).")
    @Output(name = "language", type = OoaType.STRING, description = "Language of the text, when Vision could tell.")
    public Map<String, Object> visionOcr(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"google_ai\"", description = D_SESSION) String session,
            @Input(name = "file", type = OoaType.FILE, description = "JPEG, PNG, GIF, BMP, WEBP, ICO, TIFF or PDF picture, at most 7 MB.") String file,
            @Input(name = "mode", type = OoaType.STRING, required = false, enumValues = {"document", "text"}, defaultValue = "\"document\"",
                    description = "document: dense text such as letters and forms. text: short text in photos, such as signs.") String mode,
            @Input(name = "language_hints", type = OoaType.LIST, required = false, description = "Languages to expect, e.g. [\"en\", \"hi\"]. Leave empty to detect them.") List<Object> hints) {
        GoogleAiSession s = ctx.sessionOrOnly(session, "google_ai", GoogleAiSession.class);
        String type = "text".equals(mode == null ? null : mode.strip()) ? "TEXT_DETECTION" : "DOCUMENT_TEXT_DETECTION";
        List<Object> h = new ArrayList<>();
        if (hints != null) for (Object o : hints) if (!Values.text(o).isBlank()) h.add(Values.text(o).strip());
        Map<String, Object> r = annotate(ctx, s, file, Out.of("type", type), h.isEmpty() ? null : Out.of("languageHints", h));
        List<Map<String, Object>> ann = CloudAi.maps(r.get("textAnnotations"));
        String full = CloudAi.str(CloudAi.map(r.get("fullTextAnnotation")).get("text"));
        if (full == null && !ann.isEmpty()) full = CloudAi.str(ann.get(0).get("description"));
        List<Object> words = new ArrayList<>();
        for (int i = 1; i < ann.size(); i++) words.add(CloudAi.row(CloudAi.str(ann.get(i).get("description"))));
        return Out.of("text", full == null ? "" : full, "words", CloudAi.table(List.of("text"), words),
                "language", ann.isEmpty() ? null : CloudAi.str(ann.get(0).get("locale")));
    }

    /** One images:annotate request for one picture; returns its response or fails with the picture's own error. */
    static Map<String, Object> annotate(ActionContext ctx, GoogleAiSession s, String file, Map<String, Object> feature, Map<String, Object> imageContext) {
        byte[] bytes = CloudAi.readFile(ctx, P, file, MAX_IMAGE, "picture");
        Map<String, Object> req = Out.of("image", Out.of("content", Base64.getEncoder().encodeToString(bytes)), "features", List.of(feature));
        if (imageContext != null) req.put("imageContext", imageContext);
        Map<String, Object> a = post(ctx, s, "Cloud Vision", s.url("vision", "https://vision.googleapis.com") + "/v1/images:annotate", Out.of("requests", List.of(req)));
        List<Map<String, Object>> rs = CloudAi.maps(a.get("responses"));
        Map<String, Object> r = rs.isEmpty() ? new LinkedHashMap<>() : rs.get(0);
        if (r.get("error") instanceof Map<?, ?>) {
            Map<String, Object> e = CloudAi.map(r.get("error"));
            throw new ActionException(P + ".IMAGE_ERROR", "Cloud Vision could not read the picture: " + e.get("message"), "Check the file is a supported, undamaged picture.",
                    false, Map.of("service_code", String.valueOf(e.get("code"))));
        }
        return r;
    }

    // ----- Document AI -----

    @Action(id = "documentai_process", name = "Process a document", category = "Document AI", idempotent = true, capabilities = "network",
            description = "Send a document to a Document AI processor (e.g. an invoice, form, OCR or custom extractor) and get its text, entities, form fields and tables; each table becomes a Data Table.",
            panel = "session, processor_id, file | processor_version, mime_type, first_row_headers")
    @Output(name = "text", type = OoaType.STRING, description = "All text of the document.")
    @Output(name = "entities", type = OoaType.TABLE, description = "What the processor extracted. Columns: type, text, normalized, confidence.")
    @Output(name = "fields", type = OoaType.DICTIONARY, description = "Entity type to its text, e.g. {\"invoice_id\": \"INV-100\", \"total_amount\": \"110.00\"}; repeated types become a list.")
    @Output(name = "form_fields", type = OoaType.TABLE, description = "Form fields found on the pages (form parser). Columns: name, value, confidence, page.")
    @Output(name = "tables", type = OoaType.LIST, description = "One Data Table per table found.")
    @Output(name = "page_count", type = OoaType.NUMBER, description = "Number of pages processed.")
    public Map<String, Object> documentaiProcess(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"google_ai\"", description = D_SESSION) String session,
            @Input(name = "processor_id", type = OoaType.STRING, example = "a1b2c3d4e5f60718", description = "Id of the processor, from its page in the Google Cloud console.") String processorId,
            @Input(name = "file", type = OoaType.FILE, description = "PDF, TIFF, GIF, JPEG, PNG, BMP or WEBP file; at most 20 MB.") String file,
            @Input(name = "processor_version", type = OoaType.STRING, required = false, description = "A processor version id, e.g. pretrained-invoice-v2.0-2023-12-06. Leave empty for the default version.") String version,
            @Input(name = "mime_type", type = OoaType.STRING, required = false, description = "Content type of the file, e.g. application/pdf. Leave empty to take it from the file name.") String mimeType,
            @Input(name = "first_row_headers", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Use each table's header row (or first row) as its column names.") boolean headers) {
        GoogleAiSession s = ctx.sessionOrOnly(session, "google_ai", GoogleAiSession.class);
        if (!s.token) throw new ActionException(P + ".TOKEN_REQUIRED", "Document AI does not take API keys", "Connect with credential_type access_token and an OAuth token of a service account that may use the processor.");
        if (s.project == null) throw new ActionException(P + ".NO_PROJECT", "No project given to Connect", "Give Connect the project id that owns the processor.");
        String pid = processorId == null ? "" : processorId.strip();
        if (!ID.matcher(pid).matches()) throw new ActionException(P + ".BAD_PROCESSOR", "'" + pid + "' is not a processor id", "Copy the id from the processor's page in the Google Cloud console.");
        String path = "/v1/projects/" + s.project + "/locations/" + s.location + "/processors/" + pid;
        if (version != null && !version.isBlank()) {
            if (!ID.matcher(version.strip()).matches()) throw new ActionException(P + ".BAD_PROCESSOR", "'" + version + "' is not a processor version id", "Leave it empty for the default version.");
            path += "/processorVersions/" + version.strip();
        }
        byte[] bytes = CloudAi.readFile(ctx, P, file, MAX_DOCUMENT, "document");
        String mime = mimeType == null || mimeType.isBlank() ? CloudAi.mimeType(file) : mimeType.strip();
        if (mime.equals("application/octet-stream"))
            throw new ActionException(P + ".UNKNOWN_FILE_TYPE", "Cannot tell the file type of " + file, "Give mime_type, e.g. application/pdf or image/png.");
        Map<String, Object> a = post(ctx, s, "Document AI", s.url(s.location + "-documentai", "https://" + s.location + "-documentai.googleapis.com") + path + ":process",
                Out.of("rawDocument", Out.of("content", Base64.getEncoder().encodeToString(bytes), "mimeType", mime), "skipHumanReview", true));
        Map<String, Object> doc = CloudAi.map(a.get("document"));
        String text = Objects.toString(doc.get("text"), "");
        List<Object> ents = new ArrayList<>();
        Map<String, Object> fields = new LinkedHashMap<>();
        for (Map<String, Object> e : CloudAi.maps(doc.get("entities"))) entity(e, text, "", ents, fields);
        List<Object> form = new ArrayList<>(), tables = new ArrayList<>();
        List<Map<String, Object>> pages = CloudAi.maps(doc.get("pages"));
        for (Map<String, Object> p : pages) {
            BigDecimal pageNo = CloudAi.num(p.get("pageNumber"));
            for (Map<String, Object> f : CloudAi.maps(p.get("formFields")))
                form.add(CloudAi.row(anchor(text, CloudAi.map(f.get("fieldName")).get("textAnchor")).strip(), anchor(text, CloudAi.map(f.get("fieldValue")).get("textAnchor")).strip(),
                        CloudAi.num(CloudAi.map(f.get("fieldValue")).get("confidence")), pageNo));
            for (Map<String, Object> t : CloudAi.maps(p.get("tables"))) tables.add(table(t, text, headers));
        }
        return Out.of("text", text, "entities", CloudAi.table(List.of("type", "text", "normalized", "confidence"), ents), "fields", fields,
                "form_fields", CloudAi.table(List.of("name", "value", "confidence", "page"), form), "tables", tables, "page_count", BigDecimal.valueOf(pages.size()));
    }

    /** One entity and its properties (as parent/child types); a type found more than once collects its values in a list. */
    @SuppressWarnings("unchecked")
    static void entity(Map<String, Object> e, String text, String parent, List<Object> rows, Map<String, Object> fields) {
        String type = (parent.isEmpty() ? "" : parent + "/") + Objects.toString(e.get("type"), "");
        String value = e.get("mentionText") != null ? String.valueOf(e.get("mentionText")) : anchor(text, e.get("textAnchor"));
        rows.add(CloudAi.row(type, value, CloudAi.str(CloudAi.map(e.get("normalizedValue")).get("text")), CloudAi.num(e.get("confidence"))));
        Object old = fields.get(type);
        if (old == null && !fields.containsKey(type)) fields.put(type, value);
        else if (old instanceof List<?> l) ((List<Object>) l).add(value);
        else fields.put(type, new ArrayList<>(Arrays.asList(old, value)));
        for (Map<String, Object> p : CloudAi.maps(e.get("properties"))) entity(p, text, type, rows, fields);
    }

    /** The text a textAnchor points at: its segments of the document text joined. */
    static String anchor(String text, Object textAnchor) {
        StringBuilder sb = new StringBuilder();
        Map<String, Object> a = CloudAi.map(textAnchor);
        for (Map<String, Object> seg : CloudAi.maps(a.get("textSegments"))) {
            int start = Math.max(0, Math.min(text.length(), CloudAi.intOf(seg.get("startIndex"), 0)));
            int end = Math.max(start, Math.min(text.length(), CloudAi.intOf(seg.get("endIndex"), start)));
            sb.append(text, start, end);
        }
        if (sb.isEmpty() && a.get("content") != null) sb.append(a.get("content"));
        return sb.toString();
    }

    /** A Document AI table (header rows, then body rows, cells with spans) as a Data Table. */
    static Map<String, Object> table(Map<String, Object> t, String text, boolean headers) {
        List<Map<String, Object>> rows = new ArrayList<>(CloudAi.maps(t.get("headerRows")));
        int headerRows = rows.size();
        rows.addAll(CloudAi.maps(t.get("bodyRows")));
        List<Object[]> cells = new ArrayList<>();
        Set<Long> taken = new HashSet<>();
        for (int r = 0; r < rows.size(); r++) {
            int c = 0;
            for (Map<String, Object> cell : CloudAi.maps(rows.get(r).get("cells"))) {
                while (taken.contains(((long) r << 20) | c)) c++;
                int rs = Math.max(1, CloudAi.intOf(cell.get("rowSpan"), 1)), cs = Math.max(1, CloudAi.intOf(cell.get("colSpan"), 1));
                for (int i = r; i < r + rs; i++) for (int k = c; k < c + cs; k++) taken.add(((long) i << 20) | k);
                cells.add(new Object[]{r, c, rs, cs, anchor(text, CloudAi.map(cell.get("layout")).get("textAnchor")).replaceAll("\\s+", " ").strip()});
                c += cs;
            }
        }
        // several header rows: the first holds the names; without header rows, the first body row is used when asked
        return CloudAi.grid(cells, rows.size(), 0, headers && (headerRows > 0 || !rows.isEmpty()));
    }

    // ----- calls -----

    static Map<String, Object> post(ActionContext ctx, GoogleAiSession s, String service, String url, Map<String, Object> body) {
        URI uri;
        try { uri = URI.create(url); }
        catch (IllegalArgumentException e) { throw new ActionException(P + ".BAD_URL", "Invalid address: " + url, "Check the endpoint given to Connect."); }
        byte[] payload = JsonLite.write(Values.toJsonable(body)).getBytes(StandardCharsets.UTF_8);
        return CloudAi.send(ctx, P, service, "POST", uri, s.auth(), payload, 120, a -> error(service, a)).json();
    }

    /** Google errors: {"error": {"code", "message", "status", "details": [{"reason"}]}}; an invalid API key (HTTP 400) is a sign-in error too. */
    static ActionException error(String service, CloudAi.Answer a) {
        Map<String, Object> e = CloudAi.map(a.json().get("error"));
        String status = CloudAi.str(e.get("status"));
        String msg = CloudAi.str(e.get("message"));
        if (msg == null) msg = CloudAi.rawMessage(a);
        String reason = null;
        for (Map<String, Object> d : CloudAi.maps(e.get("details"))) if (d.get("reason") != null) { reason = String.valueOf(d.get("reason")); break; }
        if ("API_KEY_INVALID".equals(reason) || a.status() == 400 && msg.contains("API key not valid"))
            return CloudAi.error(P + ".AUTH_FAILED", service, a.status(), reason != null ? reason : status, msg, "Google did not accept the API key. " + SIGN_IN, false);
        if ("SERVICE_DISABLED".equals(reason))
            return CloudAi.error(P + ".API_NOT_ENABLED", service, a.status(), reason, msg, "Enable the " + service + " API in the project's API library.", false);
        return CloudAi.statusError(P, service, a.status(), reason != null ? reason : status, msg, SIGN_IN);
    }
}
