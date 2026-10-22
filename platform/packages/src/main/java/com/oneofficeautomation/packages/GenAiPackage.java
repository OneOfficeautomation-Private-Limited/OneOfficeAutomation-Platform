package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;

import java.math.BigDecimal;
import java.util.*;

/** Generative AI inside bots: prompt, summarise, classify, extract, translate and sentiment, with PII masking and governance logs. */
@OoaPackage(id = "ooa.genai", name = "Generative AI", version = "1.0.0",
        description = "Prompt large language models (OpenAI, Azure OpenAI, Anthropic, self-hosted) to summarise, classify, extract, translate and analyse text.")
public final class GenAiPackage {
    static final String D_PROVIDER = "openai, azure_openai, anthropic, openai_compatible (LiteLLM, vLLM, Ollama), huggingface (Hugging Face router or your own TGI server) "
            + "or aws_bedrock_sagemaker (a model on an Amazon SageMaker endpoint).";
    static final String D_MODEL = "Model name; the deployment name for Azure OpenAI; the endpoint name for aws_bedrock_sagemaker.";
    static final String D_KEY = "API key or access token from the vault (not needed for some self-hosted endpoints). For aws_bedrock_sagemaker: the AWS secret access key.";
    static final String D_BASE = "Endpoint; required for Azure OpenAI and self-hosted models (for a TGI server, e.g. http://tgi-host:8080/v1).";
    static final String D_MASK = "Replace emails, phone and card numbers, IBANs and similar before sending; restored in the answer.";

    static final String D_AWS_KEY_ID = "aws_bedrock_sagemaker only: AWS access key id from the vault (api_key then holds the secret access key).";
    static final String D_AWS_REGION = "aws_bedrock_sagemaker only: AWS region of the endpoint, e.g. eu-west-1.";
    static final Set<String> PROVIDERS = Set.of("openai", "azure_openai", "anthropic", "openai_compatible", "huggingface", "aws_bedrock_sagemaker");

    static LlmClient.Config cfg(String provider, String model, Object key, String base, double temp, int max, boolean mask) {
        return cfg(provider, model, key, base, temp, max, mask, null, null);
    }

    static LlmClient.Config cfg(String provider, String model, Object key, String base, double temp, int max, boolean mask, Object accessKeyId, String region) {
        if (!PROVIDERS.contains(provider))
            throw new ActionException("AI.BAD_PROVIDER", "Unknown provider '" + provider + "'", D_PROVIDER);
        return new LlmClient.Config(provider, model, key, base, temp, max, mask, accessKeyId, region);
    }

    private static String ask(ActionContext ctx, LlmClient.Config c, String system, String user, boolean json, String purpose) {
        List<Map<String, Object>> m = new ArrayList<>();
        if (system != null && !system.isBlank()) m.add(Map.of("role", "system", "content", system));
        m.add(Map.of("role", "user", "content", user));
        String t = LlmClient.chat(ctx, c, m, null, json, purpose).text();
        if (t == null) throw new ActionException("AI.EMPTY", "The model returned no text", "Retry or raise max_tokens.", true);
        return t;
    }

    static final String D_TOP_P = "Nucleus sampling: only the most likely words whose chances add up to this share are used (above 0 to 1); provider default when empty.";
    static final String D_STOP = "Up to 4 texts at which the model stops writing.";
    static final String D_PRESENCE = "From -2 to 2; higher values push the model toward new topics (OpenAI-style providers).";
    static final String D_FREQUENCY = "From -2 to 2; higher values make the model repeat itself less (OpenAI-style providers).";
    static final String D_TIMEOUT = "Seconds to wait for the model; 180 when 0.";

    @Action(id = "prompt", name = "Prompt", category = "Generative AI", timeoutSeconds = 600, capabilities = {"network", "ai"}, description = "Send a prompt and return the model's answer.")
    @Output(name = "text", type = OoaType.STRING, description = "Answer (the first one when several were asked for).")
    @Output(name = "responses", type = OoaType.LIST, description = "Every answer, when number_of_responses is more than 1.")
    @Output(name = "json", description = "The answer parsed as JSON, when response_format is json.")
    @Output(name = "input_tokens", type = OoaType.NUMBER, description = "Tokens in the request.")
    @Output(name = "output_tokens", type = OoaType.NUMBER, description = "Tokens in the answer.")
    public Map<String, Object> prompt(ActionContext ctx,
            @Input(name = "prompt", type = OoaType.STRING, description = "Your request; ${...} interpolation allowed.") String prompt,
            @Input(name = "system", type = OoaType.STRING, required = false, defaultValue = "\"You are a precise business assistant.\"", description = "Instructions for the model's role and tone.") String system,
            @Input(name = "provider", type = OoaType.STRING, required = false, defaultValue = "\"openai\"", enumValues = {"openai", "azure_openai", "anthropic", "openai_compatible", "huggingface", "aws_bedrock_sagemaker"}, description = D_PROVIDER) String provider,
            @Input(name = "connection", type = OoaType.STRING, required = false, description = ModelConnections.D_CONNECTION) String connection,
            @Input(name = "model", type = OoaType.STRING, required = false, description = D_MODEL) String model,
            @Input(name = "api_key", type = OoaType.CREDENTIAL, required = false, description = D_KEY) Object key,
            @Input(name = "base_url", type = OoaType.STRING, required = false, description = D_BASE) String base,
            @Input(name = "temperature", type = OoaType.NUMBER, required = false, defaultValue = "0", description = "0 = consistent, higher = more creative.") double temp,
            @Input(name = "max_tokens", type = OoaType.NUMBER, required = false, defaultValue = "1024", description = "Maximum answer length in tokens.") int max,
            @Input(name = "mask_pii", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = D_MASK) boolean mask,
            @Input(name = "aws_access_key_id", type = OoaType.CREDENTIAL, required = false, description = D_AWS_KEY_ID) Object awsKeyId,
            @Input(name = "aws_region", type = OoaType.STRING, required = false, description = D_AWS_REGION) String awsRegion,
            @Input(name = "top_p", type = OoaType.NUMBER, required = false, description = D_TOP_P) Double topP,
            @Input(name = "top_k", type = OoaType.NUMBER, required = false, description = "Only pick from this many most likely words (Anthropic models).") Integer topK,
            @Input(name = "stop_sequences", type = OoaType.LIST, required = false, description = D_STOP) List<Object> stop,
            @Input(name = "presence_penalty", type = OoaType.NUMBER, required = false, description = D_PRESENCE) Double presence,
            @Input(name = "frequency_penalty", type = OoaType.NUMBER, required = false, description = D_FREQUENCY) Double frequency,
            @Input(name = "seed", type = OoaType.NUMBER, required = false, description = "Same seed and settings give the same answer where the provider supports it.") Integer seed,
            @Input(name = "number_of_responses", type = OoaType.NUMBER, required = false, defaultValue = "1", description = "How many alternative answers to ask for, 1 to 10 (OpenAI-style providers).") int n,
            @Input(name = "response_format", type = OoaType.STRING, required = false, defaultValue = "\"text\"", enumValues = {"text", "json"}, description = "json: ask for one JSON object and also return it parsed.") String format,
            @Input(name = "end_user", type = OoaType.STRING, required = false, description = "An id for the person the answer is for, passed on for the provider's abuse monitoring (OpenAI-style providers).") String endUser,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_TIMEOUT) int timeout) {
        LlmClient.Config c = ModelConnections.config(ctx, connection, provider, model, key, base, temp, max, mask, awsKeyId, awsRegion)
                .with(LlmClient.Options.of(topP, topK, stop, presence, frequency, seed, endUser, n, timeout));
        boolean json = "json".equals(format);
        List<Map<String, Object>> m = new ArrayList<>();
        if (system != null && !system.isBlank()) m.add(Map.of("role", "system", "content", system));
        m.add(Map.of("role", "user", "content", prompt));
        LlmClient.Reply r = LlmClient.chat(ctx, c, m, null, json, "prompt");
        if (r.text() == null) throw new ActionException("AI.EMPTY", "The model returned no text", "Retry or raise max_tokens.", true);
        return Out.of("text", r.text(), "responses", new ArrayList<Object>(r.all()), "json", json ? LlmClient.parseJson(r.text()) : null,
                "input_tokens", BigDecimal.valueOf(r.inTokens()), "output_tokens", BigDecimal.valueOf(r.outTokens()));
    }

    /** Earlier signature, kept for callers written before the sampling options. */
    public Map<String, Object> prompt(ActionContext ctx, String prompt, String system, String provider, String connection, String model, Object key, String base,
                                      double temp, int max, boolean mask, Object awsKeyId, String awsRegion) {
        return prompt(ctx, prompt, system, provider, connection, model, key, base, temp, max, mask, awsKeyId, awsRegion, null, null, null, null, null, null, 1, "text", null, 0);
    }

    /** One chat conversation: the messages so far (unmasked; masking happens on every call). */
    static final class ChatSession {
        final List<Map<String, Object>> messages = new ArrayList<>();
        String system;
    }

    private static String chatKey(String session) {
        String s = session == null || session.isBlank() ? "Default" : session.strip();
        if (!s.matches("^[A-Za-z0-9 _.-]{1,64}$")) throw new ActionException("AI.BAD_SESSION", "Invalid chat session name '" + session + "'", "Use letters, digits, spaces, . - and _.");
        return "genai_chat:" + s;
    }

    @Action(id = "chat", name = "Chat", category = "Generative AI", timeoutSeconds = 600, capabilities = {"network", "ai"},
            description = "Send a message in a conversation that remembers earlier messages of the same chat session until End chat.")
    @Output(name = "text", type = OoaType.STRING, description = "The model's reply.")
    @Output(name = "turns", type = OoaType.NUMBER, description = "Number of messages now held in the session (yours and the model's).")
    @Output(name = "input_tokens", type = OoaType.NUMBER, description = "Tokens in the request.")
    @Output(name = "output_tokens", type = OoaType.NUMBER, description = "Tokens in the reply.")
    public Map<String, Object> chat(ActionContext ctx,
            @Input(name = "message", type = OoaType.STRING, description = "Your next message.") String message,
            @Input(name = "chat_session", type = OoaType.STRING, required = false, defaultValue = "\"Default\"", description = "Name of the conversation; use different names for separate chats in one agent.") String session,
            @Input(name = "system", type = OoaType.STRING, required = false, description = "Instructions for the model's role; set on the first message of a session (a new value replaces the old one).") String system,
            @Input(name = "provider", type = OoaType.STRING, required = false, defaultValue = "\"openai\"", enumValues = {"openai", "azure_openai", "anthropic", "openai_compatible", "huggingface", "aws_bedrock_sagemaker"}, description = D_PROVIDER) String provider,
            @Input(name = "connection", type = OoaType.STRING, required = false, description = ModelConnections.D_CONNECTION) String connection,
            @Input(name = "model", type = OoaType.STRING, required = false, description = D_MODEL) String model,
            @Input(name = "api_key", type = OoaType.CREDENTIAL, required = false, description = D_KEY) Object key,
            @Input(name = "base_url", type = OoaType.STRING, required = false, description = D_BASE) String base,
            @Input(name = "temperature", type = OoaType.NUMBER, required = false, defaultValue = "0", description = "0 = consistent, higher = more creative.") double temp,
            @Input(name = "max_tokens", type = OoaType.NUMBER, required = false, defaultValue = "1024", description = "Maximum reply length in tokens.") int max,
            @Input(name = "mask_pii", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = D_MASK) boolean mask,
            @Input(name = "history_limit", type = OoaType.NUMBER, required = false, defaultValue = "40", description = "Most messages to keep in the session; the oldest are dropped first.") int limit,
            @Input(name = "top_p", type = OoaType.NUMBER, required = false, description = D_TOP_P) Double topP,
            @Input(name = "stop_sequences", type = OoaType.LIST, required = false, description = D_STOP) List<Object> stop,
            @Input(name = "presence_penalty", type = OoaType.NUMBER, required = false, description = D_PRESENCE) Double presence,
            @Input(name = "frequency_penalty", type = OoaType.NUMBER, required = false, description = D_FREQUENCY) Double frequency,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_TIMEOUT) int timeout,
            @Input(name = "aws_access_key_id", type = OoaType.CREDENTIAL, required = false, description = D_AWS_KEY_ID) Object awsKeyId,
            @Input(name = "aws_region", type = OoaType.STRING, required = false, description = D_AWS_REGION) String awsRegion) {
        if (message == null || message.isBlank()) throw new ActionException("AI.EMPTY_MESSAGE", "The message is empty");
        if (limit < 2 || limit > 500) throw new ActionException("AI.BAD_OPTION", "history_limit must be between 2 and 500");
        String k = chatKey(session);
        ChatSession cs = ctx.sessions().get(k) instanceof ChatSession existing ? existing : new ChatSession();
        if (system != null && !system.isBlank()) cs.system = system;
        LlmClient.Config c = ModelConnections.config(ctx, connection, provider, model, key, base, temp, max, mask, awsKeyId, awsRegion)
                .with(LlmClient.Options.of(topP, null, stop, presence, frequency, null, null, null, timeout));
        List<Map<String, Object>> m = new ArrayList<>();
        if (cs.system != null) m.add(Map.of("role", "system", "content", cs.system));
        m.addAll(cs.messages);
        m.add(Map.of("role", "user", "content", message));
        LlmClient.Reply r = LlmClient.chat(ctx, c, m, null, false, "chat");
        String text = r.text() == null ? "" : r.text();
        cs.messages.add(Map.of("role", "user", "content", message));
        cs.messages.add(Map.of("role", "assistant", "content", text));
        while (cs.messages.size() > limit) cs.messages.remove(0);
        ctx.sessions().put(k, cs);
        return Out.of("text", text, "turns", BigDecimal.valueOf(cs.messages.size()), "input_tokens", BigDecimal.valueOf(r.inTokens()), "output_tokens", BigDecimal.valueOf(r.outTokens()));
    }

    @Action(id = "end_chat", name = "End chat", category = "Generative AI", description = "Forget the messages of a chat session, so the next Chat starts a new conversation.")
    public Map<String, Object> endChat(ActionContext ctx,
            @Input(name = "chat_session", type = OoaType.STRING, required = false, defaultValue = "\"Default\"", description = "Name of the conversation to end.") String session) {
        ctx.sessions().remove(chatKey(session));
        return Map.of();
    }

    @Action(id = "summarize", name = "Summarise", category = "Generative AI", timeoutSeconds = 300, capabilities = {"network", "ai"}, description = "Summarise text in a few sentences, keeping figures exact.")
    @Output(name = "summary", type = OoaType.STRING, description = "Summary.")
    public Map<String, Object> summarize(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Text to summarise.") String text,
            @Input(name = "sentences", type = OoaType.NUMBER, required = false, defaultValue = "3", description = "Target length in sentences.") int n,
            @Input(name = "provider", type = OoaType.STRING, required = false, defaultValue = "\"openai\"", enumValues = {"openai", "azure_openai", "anthropic", "openai_compatible", "huggingface", "aws_bedrock_sagemaker"}, description = D_PROVIDER) String provider,
            @Input(name = "connection", type = OoaType.STRING, required = false, description = ModelConnections.D_CONNECTION) String connection,
            @Input(name = "model", type = OoaType.STRING, required = false, description = D_MODEL) String model,
            @Input(name = "api_key", type = OoaType.CREDENTIAL, required = false, description = D_KEY) Object key,
            @Input(name = "base_url", type = OoaType.STRING, required = false, description = D_BASE) String base,
            @Input(name = "mask_pii", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = D_MASK) boolean mask,
            @Input(name = "aws_access_key_id", type = OoaType.CREDENTIAL, required = false, description = D_AWS_KEY_ID) Object awsKeyId,
            @Input(name = "aws_region", type = OoaType.STRING, required = false, description = D_AWS_REGION) String awsRegion) {
        return Map.of("summary", ask(ctx, ModelConnections.config(ctx, connection, provider, model, key, base, 0, 1024, mask, awsKeyId, awsRegion), "Summarise the user's text in at most " + n + " sentences. Keep names, dates and figures exact.", text, false, "summarize"));
    }

    @Action(id = "classify", name = "Classify", category = "Generative AI", timeoutSeconds = 300, capabilities = {"network", "ai"}, description = "Put text into exactly one of your categories, with a short reason.")
    @Output(name = "label", type = OoaType.STRING, description = "Chosen category (always one of the labels).")
    @Output(name = "reason", type = OoaType.STRING, description = "Why.")
    public Map<String, Object> classify(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Text to classify, e.g. an email body.") String text,
            @Input(name = "labels", type = OoaType.LIST, description = "Allowed categories, e.g. [\"invoice\",\"complaint\",\"other\"].") List<Object> labels,
            @Input(name = "provider", type = OoaType.STRING, required = false, defaultValue = "\"openai\"", enumValues = {"openai", "azure_openai", "anthropic", "openai_compatible", "huggingface", "aws_bedrock_sagemaker"}, description = D_PROVIDER) String provider,
            @Input(name = "connection", type = OoaType.STRING, required = false, description = ModelConnections.D_CONNECTION) String connection,
            @Input(name = "model", type = OoaType.STRING, required = false, description = D_MODEL) String model,
            @Input(name = "api_key", type = OoaType.CREDENTIAL, required = false, description = D_KEY) Object key,
            @Input(name = "base_url", type = OoaType.STRING, required = false, description = D_BASE) String base,
            @Input(name = "mask_pii", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = D_MASK) boolean mask,
            @Input(name = "aws_access_key_id", type = OoaType.CREDENTIAL, required = false, description = D_AWS_KEY_ID) Object awsKeyId,
            @Input(name = "aws_region", type = OoaType.STRING, required = false, description = D_AWS_REGION) String awsRegion) {
        List<String> ls = labels.stream().map(Values::text).toList();
        if (ls.isEmpty()) throw new ActionException("AI.NO_LABELS", "Give at least one label");
        Object j = LlmClient.parseJson(ask(ctx, ModelConnections.config(ctx, connection, provider, model, key, base, 0, 300, mask, awsKeyId, awsRegion),
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
            @Input(name = "provider", type = OoaType.STRING, required = false, defaultValue = "\"openai\"", enumValues = {"openai", "azure_openai", "anthropic", "openai_compatible", "huggingface", "aws_bedrock_sagemaker"}, description = D_PROVIDER) String provider,
            @Input(name = "connection", type = OoaType.STRING, required = false, description = ModelConnections.D_CONNECTION) String connection,
            @Input(name = "model", type = OoaType.STRING, required = false, description = D_MODEL) String model,
            @Input(name = "api_key", type = OoaType.CREDENTIAL, required = false, description = D_KEY) Object key,
            @Input(name = "base_url", type = OoaType.STRING, required = false, description = D_BASE) String base,
            @Input(name = "mask_pii", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = D_MASK + " Off by default because extraction often needs those details.") boolean mask,
            @Input(name = "aws_access_key_id", type = OoaType.CREDENTIAL, required = false, description = D_AWS_KEY_ID) Object awsKeyId,
            @Input(name = "aws_region", type = OoaType.STRING, required = false, description = D_AWS_REGION) String awsRegion) {
        Object j = LlmClient.parseJson(ask(ctx, ModelConnections.config(ctx, connection, provider, model, key, base, 0, 1500, mask, awsKeyId, awsRegion),
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
            @Input(name = "provider", type = OoaType.STRING, required = false, defaultValue = "\"openai\"", enumValues = {"openai", "azure_openai", "anthropic", "openai_compatible", "huggingface", "aws_bedrock_sagemaker"}, description = D_PROVIDER) String provider,
            @Input(name = "connection", type = OoaType.STRING, required = false, description = ModelConnections.D_CONNECTION) String connection,
            @Input(name = "model", type = OoaType.STRING, required = false, description = D_MODEL) String model,
            @Input(name = "api_key", type = OoaType.CREDENTIAL, required = false, description = D_KEY) Object key,
            @Input(name = "base_url", type = OoaType.STRING, required = false, description = D_BASE) String base,
            @Input(name = "mask_pii", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = D_MASK) boolean mask,
            @Input(name = "aws_access_key_id", type = OoaType.CREDENTIAL, required = false, description = D_AWS_KEY_ID) Object awsKeyId,
            @Input(name = "aws_region", type = OoaType.STRING, required = false, description = D_AWS_REGION) String awsRegion) {
        return Map.of("text", ask(ctx, ModelConnections.config(ctx, connection, provider, model, key, base, 0, 2000, mask, awsKeyId, awsRegion), "Translate the user's text into " + lang + ". Return only the translation.", text, false, "translate"));
    }

    @Action(id = "sentiment", name = "Sentiment", category = "Generative AI", timeoutSeconds = 300, capabilities = {"network", "ai"}, description = "Rate text as positive, neutral or negative, with a score from -1 to 1.")
    @Output(name = "sentiment", type = OoaType.STRING, description = "positive, neutral or negative.")
    @Output(name = "score", type = OoaType.NUMBER, description = "From -1 (very negative) to 1 (very positive).")
    public Map<String, Object> sentiment(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Text, e.g. a customer message.") String text,
            @Input(name = "provider", type = OoaType.STRING, required = false, defaultValue = "\"openai\"", enumValues = {"openai", "azure_openai", "anthropic", "openai_compatible", "huggingface", "aws_bedrock_sagemaker"}, description = D_PROVIDER) String provider,
            @Input(name = "connection", type = OoaType.STRING, required = false, description = ModelConnections.D_CONNECTION) String connection,
            @Input(name = "model", type = OoaType.STRING, required = false, description = D_MODEL) String model,
            @Input(name = "api_key", type = OoaType.CREDENTIAL, required = false, description = D_KEY) Object key,
            @Input(name = "base_url", type = OoaType.STRING, required = false, description = D_BASE) String base,
            @Input(name = "mask_pii", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = D_MASK) boolean mask,
            @Input(name = "aws_access_key_id", type = OoaType.CREDENTIAL, required = false, description = D_AWS_KEY_ID) Object awsKeyId,
            @Input(name = "aws_region", type = OoaType.STRING, required = false, description = D_AWS_REGION) String awsRegion) {
        Object j = LlmClient.parseJson(ask(ctx, ModelConnections.config(ctx, connection, provider, model, key, base, 0, 200, mask, awsKeyId, awsRegion),
                "Rate the sentiment of the user's text. Reply with JSON {\"sentiment\": \"positive|neutral|negative\", \"score\": number between -1 and 1}.", text, true, "sentiment"));
        Map<?, ?> m = j instanceof Map<?, ?> mm ? mm : Map.of();
        String s = String.valueOf(m.get("sentiment")).toLowerCase(Locale.ROOT);
        if (!Set.of("positive", "neutral", "negative").contains(s)) s = "neutral";
        BigDecimal score;
        try { score = Values.toNumber(m.get("score")).max(BigDecimal.ONE.negate()).min(BigDecimal.ONE); } catch (OoaException e) { score = BigDecimal.ZERO; }
        return Out.of("sentiment", s, "score", score);
    }
}
