package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;

import java.math.BigDecimal;
import java.util.*;

/** Generative AI inside bots: prompt, summarise, classify, extract, translate and sentiment, with PII masking and governance logs. */
@OoaPackage(id = "ooa.genai", name = "Generative AI", version = "1.0.0",
        description = "Prompt large language models (OpenAI, Azure OpenAI, Anthropic, self-hosted) to summarise, classify, extract, translate and analyse text.")
public final class GenAiPackage {
    static final String D_PROVIDER = "openai, azure_openai, anthropic, or openai_compatible (LiteLLM, vLLM, Ollama).";
    static final String D_MODEL = "Model name, or the deployment name for Azure OpenAI.";
    static final String D_KEY = "API key from the vault (not needed for some self-hosted endpoints).";
    static final String D_BASE = "Endpoint; required for Azure OpenAI and self-hosted models.";
    static final String D_MASK = "Replace emails, phone and card numbers, IBANs and similar before sending; restored in the answer.";

    static LlmClient.Config cfg(String provider, String model, Object key, String base, double temp, int max, boolean mask) {
        if (!Set.of("openai", "azure_openai", "anthropic", "openai_compatible").contains(provider))
            throw new ActionException("AI.BAD_PROVIDER", "Unknown provider '" + provider + "'", D_PROVIDER);
        return new LlmClient.Config(provider, model, key, base, temp, max, mask);
    }

    private static String ask(ActionContext ctx, LlmClient.Config c, String system, String user, boolean json, String purpose) {
        List<Map<String, Object>> m = new ArrayList<>();
        if (system != null && !system.isBlank()) m.add(Map.of("role", "system", "content", system));
        m.add(Map.of("role", "user", "content", user));
        String t = LlmClient.chat(ctx, c, m, null, json, purpose).text();
        if (t == null) throw new ActionException("AI.EMPTY", "The model returned no text", "Retry or raise max_tokens.", true);
        return t;
    }

    @Action(id = "prompt", name = "Prompt", category = "Generative AI", timeoutSeconds = 300, capabilities = {"network", "ai"}, description = "Send a prompt and return the model's answer.")
    @Output(name = "text", type = OoaType.STRING, description = "Answer.")
    public Map<String, Object> prompt(ActionContext ctx,
            @Input(name = "prompt", type = OoaType.STRING, description = "Your request; ${...} interpolation allowed.") String prompt,
            @Input(name = "system", type = OoaType.STRING, required = false, defaultValue = "\"You are a precise business assistant.\"", description = "Instructions for the model's role and tone.") String system,
            @Input(name = "provider", type = OoaType.STRING, required = false, defaultValue = "\"openai\"", enumValues = {"openai", "azure_openai", "anthropic", "openai_compatible"}, description = D_PROVIDER) String provider,
            @Input(name = "model", type = OoaType.STRING, description = D_MODEL) String model,
            @Input(name = "api_key", type = OoaType.CREDENTIAL, required = false, description = D_KEY) Object key,
            @Input(name = "base_url", type = OoaType.STRING, required = false, description = D_BASE) String base,
            @Input(name = "temperature", type = OoaType.NUMBER, required = false, defaultValue = "0", description = "0 = consistent, higher = more creative.") double temp,
            @Input(name = "max_tokens", type = OoaType.NUMBER, required = false, defaultValue = "1024", description = "Maximum answer length in tokens.") int max,
            @Input(name = "mask_pii", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = D_MASK) boolean mask) {
        return Map.of("text", ask(ctx, cfg(provider, model, key, base, temp, max, mask), system, prompt, false, "prompt"));
    }

    @Action(id = "summarize", name = "Summarise", category = "Generative AI", timeoutSeconds = 300, capabilities = {"network", "ai"}, description = "Summarise text in a few sentences, keeping figures exact.")
    @Output(name = "summary", type = OoaType.STRING, description = "Summary.")
    public Map<String, Object> summarize(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Text to summarise.") String text,
            @Input(name = "sentences", type = OoaType.NUMBER, required = false, defaultValue = "3", description = "Target length in sentences.") int n,
            @Input(name = "provider", type = OoaType.STRING, required = false, defaultValue = "\"openai\"", enumValues = {"openai", "azure_openai", "anthropic", "openai_compatible"}, description = D_PROVIDER) String provider,
            @Input(name = "model", type = OoaType.STRING, description = D_MODEL) String model,
            @Input(name = "api_key", type = OoaType.CREDENTIAL, required = false, description = D_KEY) Object key,
            @Input(name = "base_url", type = OoaType.STRING, required = false, description = D_BASE) String base,
            @Input(name = "mask_pii", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = D_MASK) boolean mask) {
        return Map.of("summary", ask(ctx, cfg(provider, model, key, base, 0, 1024, mask), "Summarise the user's text in at most " + n + " sentences. Keep names, dates and figures exact.", text, false, "summarize"));
    }

    @Action(id = "classify", name = "Classify", category = "Generative AI", timeoutSeconds = 300, capabilities = {"network", "ai"}, description = "Put text into exactly one of your categories, with a short reason.")
    @Output(name = "label", type = OoaType.STRING, description = "Chosen category (always one of the labels).")
    @Output(name = "reason", type = OoaType.STRING, description = "Why.")
    public Map<String, Object> classify(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Text to classify, e.g. an email body.") String text,
            @Input(name = "labels", type = OoaType.LIST, description = "Allowed categories, e.g. [\"invoice\",\"complaint\",\"other\"].") List<Object> labels,
            @Input(name = "provider", type = OoaType.STRING, required = false, defaultValue = "\"openai\"", enumValues = {"openai", "azure_openai", "anthropic", "openai_compatible"}, description = D_PROVIDER) String provider,
            @Input(name = "model", type = OoaType.STRING, description = D_MODEL) String model,
            @Input(name = "api_key", type = OoaType.CREDENTIAL, required = false, description = D_KEY) Object key,
            @Input(name = "base_url", type = OoaType.STRING, required = false, description = D_BASE) String base,
            @Input(name = "mask_pii", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = D_MASK) boolean mask) {
        List<String> ls = labels.stream().map(Values::text).toList();
        if (ls.isEmpty()) throw new ActionException("AI.NO_LABELS", "Give at least one label");
        Object j = LlmClient.parseJson(ask(ctx, cfg(provider, model, key, base, 0, 300, mask),
                "Classify the text into exactly one of these labels: " + JsonLite.write(ls) + ". Reply with JSON {\"label\": \"...\", \"reason\": \"...\"}.", text, true, "classify"));
        Map<?, ?> m = j instanceof Map<?, ?> mm ? mm : Map.of();
        String label = String.valueOf(m.get("label"));
        String match = ls.stream().filter(l -> l.equalsIgnoreCase(label.strip())).findFirst()
                .orElseThrow(() -> new ActionException("AI.LABEL_NOT_ALLOWED", "The model answered '" + label + "', which is not one of " + ls, "Retry or describe the labels more clearly.", true));
        return Out.of("label", match, "reason", m.get("reason") == null ? "" : String.valueOf(m.get("reason")));
    }

    @Action(id = "extract", name = "Extract fields", category = "Generative AI", timeoutSeconds = 300, capabilities = {"network", "ai"},
            description = "Extract named fields from text (an email, a contract) into a Dictionary; missing fields come back empty.")
    @Output(name = "fields", type = OoaType.DICTIONARY, description = "Field name to value.")
    public Map<String, Object> extract(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Source text.") String text,
            @Input(name = "fields", type = OoaType.DICTIONARY, description = "Field name to description, e.g. {\"invoice_number\": \"the invoice id\", \"total\": \"amount due as a number\"}.") Map<String, Object> fields,
            @Input(name = "provider", type = OoaType.STRING, required = false, defaultValue = "\"openai\"", enumValues = {"openai", "azure_openai", "anthropic", "openai_compatible"}, description = D_PROVIDER) String provider,
            @Input(name = "model", type = OoaType.STRING, description = D_MODEL) String model,
            @Input(name = "api_key", type = OoaType.CREDENTIAL, required = false, description = D_KEY) Object key,
            @Input(name = "base_url", type = OoaType.STRING, required = false, description = D_BASE) String base,
            @Input(name = "mask_pii", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = D_MASK + " Off by default because extraction often needs those details.") boolean mask) {
        Object j = LlmClient.parseJson(ask(ctx, cfg(provider, model, key, base, 0, 1500, mask),
                "Extract these fields from the user's text: " + JsonLite.write(fields) + ". Reply with one JSON object with exactly these keys; use null when a value is not stated. Do not guess.", text, true, "extract"));
        Map<String, Object> out = new LinkedHashMap<>();
        Map<?, ?> m = j instanceof Map<?, ?> mm ? mm : Map.of();
        for (String f : fields.keySet()) out.put(f, m.get(f));
        return Map.of("fields", out);
    }

    @Action(id = "translate", name = "Translate", category = "Generative AI", timeoutSeconds = 300, capabilities = {"network", "ai"}, description = "Translate text into another language.")
    @Output(name = "text", type = OoaType.STRING, description = "Translation.")
    public Map<String, Object> translate(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Text to translate.") String text,
            @Input(name = "target_language", type = OoaType.STRING, example = "German", description = "Language to translate into.") String lang,
            @Input(name = "provider", type = OoaType.STRING, required = false, defaultValue = "\"openai\"", enumValues = {"openai", "azure_openai", "anthropic", "openai_compatible"}, description = D_PROVIDER) String provider,
            @Input(name = "model", type = OoaType.STRING, description = D_MODEL) String model,
            @Input(name = "api_key", type = OoaType.CREDENTIAL, required = false, description = D_KEY) Object key,
            @Input(name = "base_url", type = OoaType.STRING, required = false, description = D_BASE) String base,
            @Input(name = "mask_pii", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = D_MASK) boolean mask) {
        return Map.of("text", ask(ctx, cfg(provider, model, key, base, 0, 2000, mask), "Translate the user's text into " + lang + ". Return only the translation.", text, false, "translate"));
    }

    @Action(id = "sentiment", name = "Sentiment", category = "Generative AI", timeoutSeconds = 300, capabilities = {"network", "ai"}, description = "Rate text as positive, neutral or negative, with a score from -1 to 1.")
    @Output(name = "sentiment", type = OoaType.STRING, description = "positive, neutral or negative.")
    @Output(name = "score", type = OoaType.NUMBER, description = "From -1 (very negative) to 1 (very positive).")
    public Map<String, Object> sentiment(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Text, e.g. a customer message.") String text,
            @Input(name = "provider", type = OoaType.STRING, required = false, defaultValue = "\"openai\"", enumValues = {"openai", "azure_openai", "anthropic", "openai_compatible"}, description = D_PROVIDER) String provider,
            @Input(name = "model", type = OoaType.STRING, description = D_MODEL) String model,
            @Input(name = "api_key", type = OoaType.CREDENTIAL, required = false, description = D_KEY) Object key,
            @Input(name = "base_url", type = OoaType.STRING, required = false, description = D_BASE) String base,
            @Input(name = "mask_pii", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = D_MASK) boolean mask) {
        Object j = LlmClient.parseJson(ask(ctx, cfg(provider, model, key, base, 0, 200, mask),
                "Rate the sentiment of the user's text. Reply with JSON {\"sentiment\": \"positive|neutral|negative\", \"score\": number between -1 and 1}.", text, true, "sentiment"));
        Map<?, ?> m = j instanceof Map<?, ?> mm ? mm : Map.of();
        String s = String.valueOf(m.get("sentiment")).toLowerCase(Locale.ROOT);
        if (!Set.of("positive", "neutral", "negative").contains(s)) s = "neutral";
        BigDecimal score;
        try { score = Values.toNumber(m.get("score")).max(BigDecimal.ONE.negate()).min(BigDecimal.ONE); } catch (OoaException e) { score = BigDecimal.ZERO; }
        return Out.of("sentiment", s, "score", score);
    }
}
