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
 */
@OoaPackage(id = "ooa.ai_skills", name = "Prompt Skills", version = "1.0.0", description = "Run saved, reusable prompt templates (Prompt Skills) with your values.")
public final class AiSkillsPackage {

    /** Skills sent by Command Center with the run ({name: definition}); empty when running from Studio. */
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
            description = "Fill a saved prompt template with your values and run it; returns text, or a Dictionary for JSON skills.")
    @Output(name = "text", type = OoaType.STRING, description = "The answer as text.")
    @Output(name = "json", description = "The answer parsed as JSON, for skills with output json.")
    @Output(name = "success", type = OoaType.BOOLEAN, description = "True when the skill ran; false only when fail_on_error is off and it failed.")
    @Output(name = "error", type = OoaType.STRING, description = "What went wrong, when success is false.")
    @Output(name = "input_tokens", type = OoaType.NUMBER, description = "Tokens in the request.")
    @Output(name = "output_tokens", type = OoaType.NUMBER, description = "Tokens in the answer.")
    public Map<String, Object> run(ActionContext ctx,
            @Input(name = "skill", type = OoaType.STRING, example = "summarise_complaint", description = "Skill name (file name in ai-skills without .json).") String name,
            @Input(name = "values", type = OoaType.DICTIONARY, required = false, description = "Values for the skill's ${...} placeholders.") Map<String, Object> values,
            @Input(name = "connection", type = OoaType.STRING, required = false, description = "One Model Connection to use instead of the skill's own provider and model.") String connection,
            @Input(name = "api_key", type = OoaType.CREDENTIAL, required = false, description = GenAiPackage.D_KEY) Object key,
            @Input(name = "model", type = OoaType.STRING, required = false, description = "Override the skill's model.") String model,
            @Input(name = "base_url", type = OoaType.STRING, required = false, description = GenAiPackage.D_BASE) String base,
            @Input(name = "aws_access_key_id", type = OoaType.CREDENTIAL, required = false, description = GenAiPackage.D_AWS_KEY_ID) Object awsKeyId,
            @Input(name = "aws_region", type = OoaType.STRING, required = false, description = GenAiPackage.D_AWS_REGION + " Overrides the skill's \"region\".") String awsRegion,
            @Input(name = "temperature", type = OoaType.NUMBER, required = false, description = "Override the skill's temperature; the skill's own (or 0) when empty.") Double temperature,
            @Input(name = "max_tokens", type = OoaType.NUMBER, required = false, description = "Override the skill's maximum answer length; the skill's own (or 2000) when empty.") Integer maxTokens,
            @Input(name = "fail_on_error", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Stop the agent when the skill fails; when off, success is false and error says why.") boolean failOnError,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", description = GenAiPackage.D_TIMEOUT) int timeout) {
        try {
            Map<String, Object> s = load(ctx, name);
            String prompt = LlmClient.interpolate((String) s.get("prompt"), values);
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
                    "input_tokens", java.math.BigDecimal.valueOf(r.inTokens()), "output_tokens", java.math.BigDecimal.valueOf(r.outTokens()));
        } catch (ActionException e) {
            if (failOnError) throw e;
            ctx.warn("Prompt Skill " + name + " failed: " + e.getMessage());
            return Out.of("text", "", "json", null, "success", false, "error", e.code() + ": " + e.getMessage(),
                    "input_tokens", java.math.BigDecimal.ZERO, "output_tokens", java.math.BigDecimal.ZERO);
        }
    }

    /** Earlier signature, kept for callers written before the override and error options. */
    public Map<String, Object> run(ActionContext ctx, String name, Map<String, Object> values, String connection, Object key, String model, String base, Object awsKeyId, String awsRegion) {
        return run(ctx, name, values, connection, key, model, base, awsKeyId, awsRegion, null, null, true, 0);
    }

    @Action(id = "list", name = "List Prompt Skills", category = "Prompt Skills", idempotent = true, description = "List the Prompt Skills this run can use (from Command Center and the workspace) with their descriptions.")
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
