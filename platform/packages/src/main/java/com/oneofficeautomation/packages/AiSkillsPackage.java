package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * AI Skills: reusable, reviewed prompt templates stored as JSON in the workspace's ai-skills folder, e.g.
 * ai-skills/summarise_complaint.json = {"description": "...", "system": "...", "prompt": "Summarise: ${text}",
 * "provider": "openai", "model": "gpt-4o-mini", "output": "text" | "json", "temperature": 0}. Keys never go in skill files.
 */
@OoaPackage(id = "ooa.ai_skills", name = "Prompt Skills", version = "1.0.0", description = "Run saved, reusable prompt blueprints (Prompt Skills) with your values.")
public final class AiSkillsPackage {

    @SuppressWarnings("unchecked")
    static Map<String, Object> load(ActionContext ctx, String name) {
        if (!name.matches("^[A-Za-z0-9_-]{1,80}$")) throw new ActionException("AI.BAD_SKILL", "Invalid skill name '" + name + "'", "Use letters, digits, - and _.");
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
            description = "Fill a saved prompt blueprint with your values and run it; returns text, or a Dictionary for JSON skills.")
    @Output(name = "text", type = OoaType.STRING, description = "The answer as text.")
    @Output(name = "json", description = "The answer parsed as JSON, for skills with output json.")
    public Map<String, Object> run(ActionContext ctx,
            @Input(name = "skill", type = OoaType.STRING, example = "summarise_complaint", description = "Skill name (file name in ai-skills without .json).") String name,
            @Input(name = "values", type = OoaType.DICTIONARY, required = false, description = "Values for the blueprint's ${...} placeholders.") Map<String, Object> values,
            @Input(name = "api_key", type = OoaType.CREDENTIAL, required = false, description = GenAiPackage.D_KEY) Object key,
            @Input(name = "model", type = OoaType.STRING, required = false, description = "Override the skill's model.") String model,
            @Input(name = "base_url", type = OoaType.STRING, required = false, description = GenAiPackage.D_BASE) String base) {
        Map<String, Object> s = load(ctx, name);
        String prompt = LlmClient.interpolate((String) s.get("prompt"), values);
        String system = s.get("system") instanceof String x ? LlmClient.interpolate(x, values) : null;
        String mdl = model != null && !model.isBlank() ? model : (String) s.get("model");
        if (mdl == null) throw new ActionException("AI.NO_MODEL", "Skill '" + name + "' names no model", "Add \"model\" to the skill or set the model input.");
        double temp = s.get("temperature") instanceof java.math.BigDecimal d ? d.doubleValue() : 0;
        boolean json = "json".equals(s.get("output"));
        LlmClient.Config c = GenAiPackage.cfg(String.valueOf(s.getOrDefault("provider", "openai")), mdl, key,
                base != null ? base : (String) s.get("base_url"), temp, 2000, !Boolean.FALSE.equals(s.get("mask_pii")));
        List<Map<String, Object>> m = new ArrayList<>();
        if (system != null) m.add(Map.of("role", "system", "content", system));
        m.add(Map.of("role", "user", "content", prompt));
        String text = LlmClient.chat(ctx, c, m, null, json, "skill:" + name).text();
        return Out.of("text", text == null ? "" : text, "json", json ? LlmClient.parseJson(text) : null);
    }

    @Action(id = "list", name = "List Prompt Skills", category = "Prompt Skills", idempotent = true, description = "List the Prompt Skills saved in the workspace with their descriptions.")
    @Output(name = "skills", type = OoaType.TABLE, description = "Columns: name, description, model, output.")
    public Map<String, Object> list(ActionContext ctx) {
        List<Object> rows = new ArrayList<>();
        Path dir = LlmClient.skillsDir(ctx);
        if (Files.isDirectory(dir)) try (Stream<Path> st = Files.list(dir)) {
            for (Path p : st.filter(f -> f.toString().endsWith(".json")).sorted().toList()) {
                String n = p.getFileName().toString().replaceFirst("\\.json$", "");
                try { Map<String, Object> s = load(ctx, n); rows.add(new ArrayList<>(Arrays.asList(n, s.get("description"), s.get("model"), s.getOrDefault("output", "text")))); }
                catch (ActionException e) { rows.add(new ArrayList<>(Arrays.asList(n, "INVALID: " + e.getMessage(), null, null))); }
            }
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage()); }
        return Map.of("skills", com.oneofficeautomation.runner.Values.table(List.of("name", "description", "model", "output"), rows));
    }
}
