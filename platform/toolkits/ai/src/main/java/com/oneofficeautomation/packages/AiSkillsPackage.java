package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * AI Skills: reusable, reviewed prompt templates stored as JSON in the workspace's ai-skills folder, e.g.
 * ai-skills/summarise_complaint.json = {"description": "...", "system": "...", "prompt": "Summarise: ${text}",
 * "provider": "openai", "model": "gpt-4o-mini", "output": "text" | "json", "temperature": 0}. Providers: openai, azure_openai, anthropic,
 * openai_compatible, huggingface, aws_bedrock_sagemaker or aws_bedrock (add "region"), google_vertex. Keys never go in skill files.
 * A skill can be grounded in the company's documents: "knowledge": {"index": "knowledge/policies.json", "top_k": 4,
 * "query": "${question}"} gives the model the best passages of a Knowledge Q&A index with the prompt, and files given
 * to the run (Word, PDF, text, ...) go with the prompt as documents; the answer cites the passages it used.
 */
@OoaPackage(id = "ooa.ai_skills", name = "Prompt Skills", version = "1.0.0", description = "Run saved, reusable prompt templates (Prompt Skills) with your values.")
public final class AiSkillsPackage {

    /** Skills sent by One CommandCenter with the run ({name: definition}); empty when running from Studio. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> central(ActionContext ctx) {
        try {
            java.util.function.Supplier<Object> svc = ctx.service("prompt_skills", java.util.function.Supplier.class);
            if (svc != null && svc.get() instanceof Map<?, ?> m) return (Map<String, Object>) m;
        } catch (RuntimeException ignored) { /* no service */ }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> load(ActionContext ctx, String name) {
        if (!name.matches("^[A-Za-z0-9_-]{1,80}$")) throw new ActionException("AI.BAD_SKILL", "Invalid skill name '" + name + "'", "Use letters, digits, - and _.");
        Map<String, Object> central = central(ctx);
        if (central.get(name) instanceof Map<?, ?> m) {
            if (!(m.get("prompt") instanceof String)) throw new ActionException("AI.BAD_SKILL", "Skill '" + name + "' needs at least a \"prompt\" text", null);
            return (Map<String, Object>) m;
        }
        Path p = LlmClient.skillsDir(ctx).resolve(name + ".json");
        if (!Files.isRegularFile(p)) throw new ActionException("AI.SKILL_NOT_FOUND", "Prompt Skill '" + name + "' not found", "Create ai-skills/" + name + ".json in the workspace.");
        try {
            Object v = JsonLite.parse(Files.readString(p));
            if (!(v instanceof Map) || !(((Map<?, ?>) v).get("prompt") instanceof String))
                throw new ActionException("AI.BAD_SKILL", "Skill '" + name + "' needs at least a \"prompt\" text", null);
            return (Map<String, Object>) v;
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), null, true);
        } catch (IllegalArgumentException e) { throw new ActionException("AI.BAD_SKILL", "Skill '" + name + "' is not valid JSON: " + e.getMessage(), null); }
    }

    @Action(id = "run", name = "Run Prompt Skill", category = "Prompt Skills", timeoutSeconds = 300, capabilities = {"network", "ai"},
            description = "Fill a saved prompt template with your values and run it; returns text, or a Dictionary for JSON skills.",
            panel = "skill, values, connection, [Ground the answer in documents: knowledge_index, top_k, files] | api_key, model, base_url, aws_access_key_id, aws_region, temperature, max_tokens, fail_on_error, timeout_seconds")
    @Output(name = "text", type = OoaType.STRING, description = "The answer as text.")
    @Output(name = "json", description = "The answer parsed as JSON, for skills with output json.")
    @Output(name = "success", type = OoaType.BOOLEAN, description = "True when the skill ran; false only when fail_on_error is off and it failed.")
    @Output(name = "error", type = OoaType.STRING, description = "What went wrong, when success is false.")
    @Output(name = "input_tokens", type = OoaType.NUMBER, description = "Tokens in the request.")
    @Output(name = "output_tokens", type = OoaType.NUMBER, description = "Tokens in the answer.")
    @Output(name = "sources", type = OoaType.LIST, description = "The knowledge passages given to the model (n, source, passage, score, text); empty without a knowledge index.")
    public Map<String, Object> run(ActionContext ctx,
            @Input(name = "skill", type = OoaType.STRING, example = "summarise_complaint", description = "Skill name (file name in ai-skills without .json).") String name,
            @Input(name = "values", type = OoaType.DICTIONARY, required = false, description = "Values for the skill's ${...} placeholders.") Map<String, Object> values,
            @Input(name = "connection", type = OoaType.STRING, required = false, description = "Model Connection to use instead of the skill's own provider and model.") String connection,
            @Input(name = "api_key", type = OoaType.CREDENTIAL, required = false, description = GenAiPackage.D_KEY) Object key,
            @Input(name = "model", type = OoaType.STRING, required = false, description = "Override the skill's model.") String model,
            @Input(name = "base_url", type = OoaType.STRING, required = false, description = GenAiPackage.D_BASE) String base,
            @Input(name = "aws_access_key_id", type = OoaType.CREDENTIAL, required = false, description = GenAiPackage.D_AWS_KEY_ID) Object awsKeyId,
            @Input(name = "aws_region", type = OoaType.STRING, required = false, description = GenAiPackage.D_AWS_REGION + " Overrides the skill's \"region\".") String awsRegion,
            @Input(name = "temperature", type = OoaType.NUMBER, required = false, description = "Override the skill's temperature; the skill's own (or 0) when empty.") Double temperature,
            @Input(name = "max_tokens", type = OoaType.NUMBER, required = false, description = "Override the skill's maximum answer length; the skill's own (or 2000) when empty.") Integer maxTokens,
            @Input(name = "fail_on_error", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Stop the agent when the skill fails; when off, success is false and error says why.") boolean failOnError,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", description = GenAiPackage.D_TIMEOUT) int timeout,
            @Input(name = "knowledge_index", type = OoaType.FILE, required = false, description = "A Knowledge Q&A index (built with Build index) whose best passages go to the model with the prompt; overrides the skill's own \"knowledge\".") String knowledgeIndex,
            @Input(name = "top_k", type = OoaType.NUMBER, required = false, min = 1, max = 20, description = "How many passages of the index the model gets; the skill's own (or 4) when empty.") Integer topK,
            @Input(name = "files", type = OoaType.LIST, required = false, description = "Documents given to the model with the prompt (text, Markdown, HTML, CSV, JSON, XML, Word, PowerPoint or PDF), e.g. an invoice to check.") List<Object> files) {
        List<Object> sources = List.of();
        try {
            Map<String, Object> s = load(ctx, name);
            String prompt = LlmClient.interpolate((String) s.get("prompt"), values);
            Object[] grounded = ground(ctx, s, values, prompt, knowledgeIndex, topK, files);
            sources = (List<Object>) grounded[1];
            prompt = (String) grounded[0];
            String system = s.get("system") instanceof String x ? LlmClient.interpolate(x, values) : null;
            String conn = connection != null && !connection.isBlank() ? connection : (String) s.get("connection");
            String mdl = model != null && !model.isBlank() ? model : conn != null ? null : (String) s.get("model");
            if (mdl == null && conn == null) throw new ActionException("AI.NO_MODEL", "Skill '" + name + "' names no model", "Add \"model\" or \"connection\" to the skill, or set the model input.");
            double temp = temperature != null ? temperature : s.get("temperature") instanceof java.math.BigDecimal d ? d.doubleValue() : 0;
            int max = maxTokens != null ? maxTokens : s.get("max_tokens") instanceof java.math.BigDecimal d ? d.intValue() : 2000;
            if (max < 1 || max > 200_000) throw new ActionException("AI.BAD_OPTION", "max_tokens must be between 1 and 200000");
            Double topP = s.get("top_p") instanceof java.math.BigDecimal d ? d.doubleValue() : null;
            boolean json = "json".equals(s.get("output"));
            LlmClient.Config c = ModelConnections.config(ctx, conn, String.valueOf(s.getOrDefault("provider", "openai")), mdl, key,
                    base != null ? base : (String) s.get("base_url"), temp, max, !Boolean.FALSE.equals(s.get("mask_pii")),
                    awsKeyId, awsRegion != null && !awsRegion.isBlank() ? awsRegion : (String) s.get("region"))
                    .with(LlmClient.Options.of(topP, null, null, null, null, null, null, null, timeout));
            List<Map<String, Object>> m = new ArrayList<>();
            if (system != null) m.add(Map.of("role", "system", "content", system));
            m.add(Map.of("role", "user", "content", prompt));
            LlmClient.Reply r = LlmClient.chat(ctx, c, m, null, json, "skill:" + name);
            String text = r.text();
            return Out.of("text", text == null ? "" : text, "json", json ? LlmClient.parseJson(text) : null, "success", true, "error", "",
                    "input_tokens", java.math.BigDecimal.valueOf(r.inTokens()), "output_tokens", java.math.BigDecimal.valueOf(r.outTokens()), "sources", sources);
        } catch (ActionException e) {
            if (failOnError) throw e;
            ctx.warn("Prompt Skill " + name + " failed: " + e.getMessage());
            return Out.of("text", "", "json", null, "success", false, "error", e.code() + ": " + e.getMessage(),
                    "input_tokens", java.math.BigDecimal.ZERO, "output_tokens", java.math.BigDecimal.ZERO, "sources", sources);
        }
    }

    /** Earlier signatures, kept for callers written before the override, error and grounding options. */
    public Map<String, Object> run(ActionContext ctx, String name, Map<String, Object> values, String connection, Object key, String model, String base, Object awsKeyId, String awsRegion) {
        return run(ctx, name, values, connection, key, model, base, awsKeyId, awsRegion, null, null, true, 0);
    }

    public Map<String, Object> run(ActionContext ctx, String name, Map<String, Object> values, String connection, Object key, String model, String base, Object awsKeyId,
                                   String awsRegion, Double temperature, Integer maxTokens, boolean failOnError, int timeout) {
        return run(ctx, name, values, connection, key, model, base, awsKeyId, awsRegion, temperature, maxTokens, failOnError, timeout, null, null, null);
    }

    static final int MAX_FILE_CHARS = 60_000;
    static final String GROUNDING_RULES = "Use the numbered passages from the company's documents below where they help, and cite the ones you use like [1] or [2][3]. "
            + "If they do not cover something, say so rather than guess.";

    /** The prompt with knowledge passages and documents put before it, and the passages used (for the sources output). */
    @SuppressWarnings("unchecked")
    static Object[] ground(ActionContext ctx, Map<String, Object> skill, Map<String, Object> values, String prompt, String indexInput, Integer topKInput, List<Object> files) {
        Map<String, Object> k = skill.get("knowledge") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        String index = indexInput != null && !indexInput.isBlank() ? indexInput : k.get("index") instanceof String x && !x.isBlank() ? x : null;
        int topK = topKInput != null ? topKInput : k.get("top_k") instanceof java.math.BigDecimal d ? d.intValue() : 4;
        if (topK < 1 || topK > 20) throw new ActionException("AI.BAD_OPTION", "top_k must be between 1 and 20");
        StringBuilder before = new StringBuilder();
        List<Object> sources = new ArrayList<>();
        if (index != null) {
            String query = k.get("query") instanceof String q && !q.isBlank() ? LlmClient.interpolate(q, values) : prompt;
            KnowledgePackage.Index idx = KnowledgePackage.load(ctx.resolvePath(index));
            String conn = k.get("embedding_connection") instanceof String c ? c : null;
            List<KnowledgePackage.Hit> hits = KnowledgePackage.rank(idx.chunks(), query, KnowledgePackage.queryVector(ctx, idx, query, conn, null), topK);
            if (!hits.isEmpty()) {
                before.append(GROUNDING_RULES).append("\n\nPassages:\n\n");
                int n = 0;
                for (KnowledgePackage.Hit h : hits) before.append('[').append(++n).append("] (").append(h.chunk().source()).append(")\n").append(h.chunk().text()).append("\n\n");
                sources = KnowledgePackage.results(hits);
            } else ctx.info("No passage of " + index + " matches the prompt; the skill runs without them");
        }
        if (files != null && !files.isEmpty()) {
            int budget = MAX_FILE_CHARS;
            for (Object f : files) {
                java.nio.file.Path p = ctx.resolvePath(com.oneofficeautomation.runner.Values.text(f));
                if (!Files.isRegularFile(p)) throw new ActionException("FILE.NOT_FOUND", "Document not found: " + p, "Check the files paths.");
                String text;
                try { text = KnowledgePackage.read(p).strip(); }
                catch (IOException e) { throw new ActionException("FILE.IO_ERROR", "Cannot read " + p.getFileName() + ": " + e.getMessage(), null, true); }
                if (text.length() > budget) {
                    ctx.warn(p.getFileName() + " is long; only its first " + budget + " characters go to the model");
                    text = text.substring(0, Math.max(0, budget));
                }
                budget -= text.length();
                before.append("Document: ").append(p.getFileName()).append("\n\"\"\"\n").append(text).append("\n\"\"\"\n\n");
                if (budget <= 0) { ctx.warn("The documents are long; the rest were not given to the model"); break; }
            }
        }
        return new Object[]{before.length() == 0 ? prompt : before + "Task:\n" + prompt, sources};
    }

    @Action(id = "list", name = "List Prompt Skills", category = "Prompt Skills", idempotent = true, description = "List the Prompt Skills this run can use (from One CommandCenter and the workspace) with their descriptions.")
    @Output(name = "skills", type = OoaType.TABLE, description = "Columns: name, description, model, output.")
    public Map<String, Object> list(ActionContext ctx) {
        List<Object> rows = new ArrayList<>();
        Map<String, Object> central = central(ctx);
        for (String n : new TreeSet<>(central.keySet())) {
            Map<?, ?> d = central.get(n) instanceof Map<?, ?> m ? m : Map.of();
            rows.add(new ArrayList<>(Arrays.asList(n, d.get("description"), d.get("model"), d.get("output") == null ? "text" : d.get("output"))));
        }
        Path dir = LlmClient.skillsDir(ctx);
        if (Files.isDirectory(dir)) try (Stream<Path> st = Files.list(dir)) {
            for (Path p : st.filter(f -> f.toString().endsWith(".json")).sorted().toList()) {
                String n = p.getFileName().toString().replaceFirst("\\.json$", "");
                if (central.containsKey(n)) continue;
                try { Map<String, Object> s = load(ctx, n); rows.add(new ArrayList<>(Arrays.asList(n, s.get("description"), s.get("model"), s.getOrDefault("output", "text")))); }
                catch (ActionException e) { rows.add(new ArrayList<>(Arrays.asList(n, "INVALID: " + e.getMessage(), null, null))); }
            }
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage()); }
        return Map.of("skills", com.oneofficeautomation.runner.Values.table(List.of("name", "description", "model", "output"), rows));
    }
}
