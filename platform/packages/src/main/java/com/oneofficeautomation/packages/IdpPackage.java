package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Document processing (gap G59, first part): routing documents by type, rule-based extraction, and AI extraction that
 * learns from validated documents. Works on text: read it first with the PDF, OCR or Word toolkits.
 * <ul>
 * <li>Classify document: by keywords (each type's words, counted), by AI (the types' descriptions), or keywords first
 * and AI when they do not decide.</li>
 * <li>Extract by rules: regular expressions, or the text after a label, per field; no AI, nothing leaves the runner.</li>
 * <li>Learning from corrections: Save validated example keeps a document's checked values (with the start of its text)
 * in an example file per document type; Extract with examples shows the model the latest ones, so extraction follows
 * the company's own documents and improves as people correct it.</li>
 * </ul>
 */
@OoaPackage(id = "ooa.idp", name = "Document processing", version = "1.0.0",
        description = "Classify documents by type, extract fields by rules, and extract with AI that learns from validated examples.")
public final class IdpPackage {
    static final int MAX_EXAMPLES = 50, EXAMPLE_TEXT = 1500;

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> types(List<Object> types) {
        if (types == null || types.isEmpty()) throw new ActionException("IDP.NO_TYPES", "Give the document types", "Each: {\"name\": \"invoice\", \"keywords\": [\"invoice\", \"VAT\"], \"description\": \"a supplier invoice\"}");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : types) {
            if (!(o instanceof Map<?, ?> m) || !(m.get("name") instanceof String n) || n.isBlank()) throw new ActionException("IDP.BAD_TYPE", "Each document type needs a name");
            out.add((Map<String, Object>) m);
        }
        return out;
    }

    /** Keyword scores: how many of each type's words the text holds (whole words, any case), as a share of its words. */
    static Map<String, Double> keywordScores(String text, List<Map<String, Object>> types) {
        String low = " " + text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ") + " ";
        Map<String, Double> out = new LinkedHashMap<>();
        for (Map<String, Object> t : types) {
            List<?> kws = t.get("keywords") instanceof List<?> l ? l : List.of();
            if (kws.isEmpty()) { out.put(String.valueOf(t.get("name")), 0.0); continue; }
            int hit = 0;
            for (Object k : kws) {
                String w = Values.text(k).toLowerCase(Locale.ROOT).strip();
                if (!w.isEmpty() && Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(w) + "(?![\\p{L}\\p{N}])").matcher(low).find()) hit++;
            }
            out.put(String.valueOf(t.get("name")), (double) hit / kws.size());
        }
        return out;
    }

    @Action(id = "classify_document", name = "Classify document", category = "Document processing", timeoutSeconds = 300, capabilities = {"ai"},
            description = "Decide which type of document a text is (invoice, purchase order, claim...) by keywords, by AI, or keywords first and AI when they do not decide.",
            panel = "text, types, method, (Model to use: A saved connection = connection / Set up here = provider, model, api_key, base_url) | min_score, mask_pii")
    @Output(name = "type", type = OoaType.STRING, description = "The document type, or unknown.")
    @Output(name = "confidence", type = OoaType.NUMBER, description = "0 to 1.")
    @Output(name = "method", type = OoaType.STRING, description = "keywords or ai: what decided.")
    @Output(name = "scores", type = OoaType.DICTIONARY, description = "Keyword score per type.")
    public Map<String, Object> classify(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "The document's text (read it with the PDF, OCR or Word toolkits).") String text,
            @Input(name = "types", type = OoaType.LIST, example = "[{\"name\": \"invoice\", \"keywords\": [\"invoice\", \"VAT\", \"amount due\"], \"description\": \"a supplier invoice\"}]",
                    description = "The document types: name, keywords and a description for the AI.") List<Object> types,
            @Input(name = "method", type = OoaType.STRING, required = false, defaultValue = "\"keywords_then_ai\"", enumValues = {"keywords", "ai", "keywords_then_ai"}, description = "How to decide.") String method,
            @Input(name = "connection", type = OoaType.STRING, required = false, description = ModelConnections.D_CONNECTION) String connection,
            @Input(name = "provider", type = OoaType.STRING, required = false, defaultValue = "\"openai\"", enumValues = {"openai", "azure_openai", "anthropic", "openai_compatible", "huggingface", "aws_bedrock_sagemaker", "aws_bedrock", "google_vertex"}, description = GenAiPackage.D_PROVIDER) String provider,
            @Input(name = "model", type = OoaType.STRING, required = false, description = GenAiPackage.D_MODEL) String model,
            @Input(name = "api_key", type = OoaType.CREDENTIAL, required = false, description = GenAiPackage.D_KEY) Object key,
            @Input(name = "base_url", type = OoaType.STRING, required = false, description = GenAiPackage.D_BASE) String base,
            @Input(name = "min_score", type = OoaType.NUMBER, required = false, defaultValue = "0.5", min = 0, max = 1, description = "Keywords decide when the best type reaches this and leads the next.") double min,
            @Input(name = "mask_pii", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = GenAiPackage.D_MASK) boolean mask) {
        List<Map<String, Object>> ts = types(types);
        String t = text == null ? "" : text;
        String how = method == null ? "keywords_then_ai" : method;
        Map<String, Double> scores = keywordScores(t, ts);
        List<Map.Entry<String, Double>> ranked = new ArrayList<>(scores.entrySet());
        ranked.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> sc = new LinkedHashMap<>();
        scores.forEach((k, v) -> sc.put(k, BigDecimal.valueOf(Math.round(v * 1000) / 1000.0)));
        out.put("scores", sc);
        double best = ranked.isEmpty() ? 0 : ranked.get(0).getValue(), next = ranked.size() > 1 ? ranked.get(1).getValue() : 0;
        boolean decided = best >= min && best > next;
        if (!how.equals("ai") && (decided || how.equals("keywords"))) {
            out.put("type", decided ? ranked.get(0).getKey() : "unknown");
            out.put("confidence", BigDecimal.valueOf(Math.round(best * 1000) / 1000.0));
            out.put("method", "keywords");
            return out;
        }
        StringBuilder list = new StringBuilder();
        for (Map<String, Object> ty : ts) list.append("- ").append(ty.get("name")).append(": ").append(ty.getOrDefault("description", "")).append('\n');
        LlmClient.Config cfg = ModelConnections.config(ctx, connection, provider, model, key, base, 0, 200, mask, null, null);
        String prompt = "Document types:\n" + list + "\nWhich type is this document? Answer with one JSON object: {\"type\": \"<a name from the list, or unknown>\", \"confidence\": 0 to 1}.\n\nDocument:\n"
                + (t.length() > 12000 ? t.substring(0, 12000) : t);
        LlmClient.Reply r = LlmClient.chat(ctx, cfg, List.of(Map.of("role", "user", "content", prompt)), null, true, "classify_document");
        Object parsed;
        try { parsed = r.text() == null ? null : LlmClient.parseJson(r.text()); } catch (ActionException e) { parsed = null; }
        String type = "unknown";
        double conf = 0;
        if (parsed instanceof Map<?, ?> m) {
            String ty = String.valueOf(m.get("type"));
            if (ts.stream().anyMatch(x -> ty.equals(x.get("name")))) { type = ty; conf = m.get("confidence") instanceof Number n ? n.doubleValue() : 0.5; }
        }
        out.put("type", type);
        out.put("confidence", BigDecimal.valueOf(Math.max(0, Math.min(1, conf))));
        out.put("method", "ai");
        return out;
    }

    @Action(id = "extract_by_rules", name = "Extract by rules", category = "Document processing", idempotent = true, timeoutSeconds = 60,
            description = "Extract fields with regular expressions or the text after a label, without AI: nothing leaves the runner.",
            panel = "text, rules")
    @Output(name = "fields", type = OoaType.DICTIONARY, description = "Field name to value (text); fields not found are left out.")
    @Output(name = "missing", type = OoaType.LIST, description = "Fields no rule found.")
    public Map<String, Object> extractByRules(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "The document's text.") String text,
            @Input(name = "rules", type = OoaType.DICTIONARY, example = "{\"invoice_number\": {\"regex\": \"INV-\\\\d+\"}, \"total\": {\"after\": \"Total due\"}}",
                    description = "Per field: regex (the first match, or its first group when it has one), or after (the rest of the line after a label; until gives where it stops).") Map<String, Object> rules) {
        String t = text == null ? "" : text;
        Map<String, Object> fields = new LinkedHashMap<>();
        List<Object> missing = new ArrayList<>();
        for (Map.Entry<String, Object> e : (rules == null ? Map.<String, Object>of() : rules).entrySet()) {
            String v = null;
            if (e.getValue() instanceof Map<?, ?> rule) {
                if (rule.get("regex") instanceof String re) {
                    Matcher m;
                    try { m = Pattern.compile(re, Pattern.MULTILINE).matcher(t); }
                    catch (RuntimeException x) { throw new ActionException("IDP.BAD_RULE", "The rule for " + e.getKey() + " is not a valid regular expression: " + x.getMessage()); }
                    if (m.find()) v = (m.groupCount() >= 1 && m.group(1) != null ? m.group(1) : m.group()).strip();
                } else if (rule.get("after") instanceof String label) {
                    int at = t.toLowerCase(Locale.ROOT).indexOf(label.toLowerCase(Locale.ROOT));
                    if (at >= 0) {
                        String rest = t.substring(at + label.length());
                        int nl = rest.indexOf('\n');
                        String line = nl >= 0 ? rest.substring(0, nl) : rest;
                        if (rule.get("until") instanceof String until && !until.isEmpty()) { int u = line.indexOf(until); if (u >= 0) line = line.substring(0, u); }
                        v = line.replaceFirst("^[\\s:#\\-]+", "").strip();
                        if (v.isEmpty()) v = null;
                    }
                } else throw new ActionException("IDP.BAD_RULE", "The rule for " + e.getKey() + " needs regex or after");
            }
            if (v == null) missing.add(e.getKey()); else fields.put(e.getKey(), v);
        }
        return Map.of("fields", fields, "missing", missing);
    }

    /** The example file: {"type": [{"text": start of the document, "fields": {...}, "saved": time}, ...]}. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> readExamples(Path f) {
        try { return Files.isRegularFile(f) ? new LinkedHashMap<>((Map<String, Object>) JsonLite.parse(Files.readString(f, StandardCharsets.UTF_8))) : new LinkedHashMap<>(); }
        catch (IOException | RuntimeException e) { throw new ActionException("IDP.BAD_EXAMPLES", "Cannot read the example file " + f + ": " + e.getMessage()); }
    }

    @SuppressWarnings("unchecked")
    @Action(id = "save_example", name = "Save validated example", category = "Document processing", timeoutSeconds = 30, capabilities = {"filesystem"},
            description = "Keep a document's checked values as an example for its type, so Extract with examples learns from it (the newest 50 per type are kept).",
            panel = "examples_file, document_type, text, fields")
    @Output(name = "count", type = OoaType.NUMBER, description = "Examples kept for the type now.")
    public Map<String, Object> saveExample(ActionContext ctx,
            @Input(name = "examples_file", type = OoaType.FILE, example = "Documents/invoice.examples.json", description = "The example file (made when missing).") String file,
            @Input(name = "document_type", type = OoaType.STRING, example = "invoice", description = "The document type.") String type,
            @Input(name = "text", type = OoaType.STRING, description = "The document's text (the first 1,500 characters are kept).") String text,
            @Input(name = "fields", type = OoaType.DICTIONARY, description = "The validated values, as a person checked them.") Map<String, Object> fields) {
        if (type == null || type.isBlank()) throw new ActionException("IDP.NO_TYPE", "Give the document type");
        if (fields == null || fields.isEmpty()) throw new ActionException("IDP.NO_FIELDS", "Give the validated values");
        Path f = ctx.resolvePath(file);
        Map<String, Object> all = readExamples(f);
        List<Object> list = new ArrayList<>(all.get(type) instanceof List<?> l ? l : List.of());
        String t = text == null ? "" : text;
        list.add(Map.of("text", t.length() > EXAMPLE_TEXT ? t.substring(0, EXAMPLE_TEXT) : t, "fields", fields, "saved", java.time.Instant.now().toString()));
        while (list.size() > MAX_EXAMPLES) list.remove(0);
        all.put(type, list);
        try {
            if (f.getParent() != null) Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.writeString(tmp, JsonLite.write(all), StandardCharsets.UTF_8);
            Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", "Cannot write " + f + ": " + e.getMessage(), null, true); }
        return Map.of("count", list.size());
    }

    @SuppressWarnings("unchecked")
    @Action(id = "extract_with_examples", name = "Extract with examples", category = "Document processing", timeoutSeconds = 600, capabilities = {"ai"},
            description = "Extract fields with AI, showing the model the latest validated examples of the same document type so it follows how your documents are read.",
            panel = "text, fields, examples_file, document_type, (Model to use: A saved connection = connection / Set up here = provider, model, api_key, base_url) | max_examples, mask_pii")
    @Output(name = "fields", type = OoaType.DICTIONARY, description = "Field name to value (null when not found).")
    @Output(name = "examples_used", type = OoaType.NUMBER, description = "How many examples went with the request.")
    public Map<String, Object> extractWithExamples(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "The document's text.") String text,
            @Input(name = "fields", type = OoaType.DICTIONARY, example = "{\"invoice_number\": \"the invoice id\", \"total\": \"amount due as a number\"}", description = "Field name to what it is.") Map<String, Object> fields,
            @Input(name = "examples_file", type = OoaType.FILE, required = false, description = "The example file Save validated example writes; none: no examples.") String file,
            @Input(name = "document_type", type = OoaType.STRING, required = false, description = "Which type's examples to use.") String type,
            @Input(name = "max_examples", type = OoaType.NUMBER, required = false, defaultValue = "3", min = 0, max = 10, description = "The newest this many examples go with the request.") int max,
            @Input(name = "connection", type = OoaType.STRING, required = false, description = ModelConnections.D_CONNECTION) String connection,
            @Input(name = "provider", type = OoaType.STRING, required = false, defaultValue = "\"openai\"", enumValues = {"openai", "azure_openai", "anthropic", "openai_compatible", "huggingface", "aws_bedrock_sagemaker", "aws_bedrock", "google_vertex"}, description = GenAiPackage.D_PROVIDER) String provider,
            @Input(name = "model", type = OoaType.STRING, required = false, description = GenAiPackage.D_MODEL) String model,
            @Input(name = "api_key", type = OoaType.CREDENTIAL, required = false, description = GenAiPackage.D_KEY) Object key,
            @Input(name = "base_url", type = OoaType.STRING, required = false, description = GenAiPackage.D_BASE) String base,
            @Input(name = "mask_pii", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = GenAiPackage.D_MASK) boolean mask) {
        if (fields == null || fields.isEmpty()) throw new ActionException("IDP.NO_FIELDS", "Give the fields to extract");
        List<Object> examples = List.of();
        if (file != null && !file.isBlank() && type != null && !type.isBlank()) {
            List<?> all = readExamples(ctx.resolvePath(file)).get(type) instanceof List<?> l ? l : List.of();
            examples = new ArrayList<>(all.subList(Math.max(0, all.size() - max), all.size()));
        }
        StringBuilder p = new StringBuilder("Extract these fields from the document. Answer with one JSON object with exactly these keys (null when not in the document):\n");
        fields.forEach((k, v) -> p.append("- ").append(k).append(": ").append(Values.text(v)).append('\n'));
        int n = 0;
        for (Object o : examples) {
            if (!(o instanceof Map<?, ?> ex)) continue;
            p.append("\nExample ").append(++n).append(" (checked by a person). Document:\n").append(ex.get("text")).append("\nAnswer: ").append(JsonLite.write(ex.get("fields"))).append('\n');
        }
        String t = text == null ? "" : text;
        p.append("\nDocument:\n").append(t.length() > 20000 ? t.substring(0, 20000) : t);
        LlmClient.Config cfg = ModelConnections.config(ctx, connection, provider, model, key, base, 0, 2048, mask, null, null);
        LlmClient.Reply r = LlmClient.chat(ctx, cfg, List.of(Map.of("role", "user", "content", p.toString())), null, true, "extract_with_examples");
        Object parsed = r.text() == null ? null : LlmClient.parseJson(r.text());
        if (!(parsed instanceof Map<?, ?> m)) throw new ActionException("IDP.BAD_ANSWER", "The model did not answer with the fields", "Retry, or try another model.", true);
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : fields.keySet()) out.put(k, ((Map<String, Object>) m).get(k));
        return Map.of("fields", out, "examples_used", n);
    }
}
