package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;

import java.math.BigDecimal;
import java.util.*;

/**
 * Generative AI inside bots: prompts (also with pictures), chat, picture generation, summarise, classify, extract,
 * translate, sentiment, language, key phrases and named entities, with PII masking and governance logs.
 */
@OoaPackage(id = "ooa.genai", name = "Generative AI", version = "1.1.0",
        description = "Prompt large language models (OpenAI, Azure OpenAI, Anthropic, Amazon Bedrock, Google Vertex AI, self-hosted) to answer, read pictures, draw pictures and analyse text.")
public final class GenAiPackage {
    static final String D_PROVIDER = "Who runs the model: OpenAI, Azure OpenAI, Anthropic, OpenAI-compatible (LiteLLM, vLLM, Ollama), Hugging Face (its router or your own TGI server), "
            + "AWS SageMaker (a model on an Amazon SageMaker endpoint), AWS Bedrock or Google Vertex AI (Gemini).";
    static final String D_MODEL = "Model name; the deployment name for Azure OpenAI; the endpoint name for AWS SageMaker; the model or inference profile id for AWS Bedrock.";
    static final String D_KEY = "API key or access token from the vault (not needed for some self-hosted endpoints). For AWS Bedrock and AWS SageMaker: the AWS secret access key "
            + "(or ACCESS_KEY_ID:SECRET). For Google Vertex AI: a service-account JSON key or an access token.";
    static final String D_BASE = "Endpoint; required for Azure OpenAI, Vertex AI (https://REGION-aiplatform.googleapis.com/v1/projects/PROJECT/locations/REGION) and self-hosted models "
            + "(for a TGI server, e.g. http://tgi-host:8080/v1).";
    static final String D_MASK = "Replace emails, phone and card numbers, IBANs and similar before sending; restored in the answer.";

    static final String D_AWS_KEY_ID = "AWS Bedrock and AWS SageMaker only: AWS access key id from the vault (api_key then holds the secret access key).";
    static final String D_AWS_REGION = "AWS Bedrock and AWS SageMaker only: AWS region, e.g. eu-west-1.";
    static final String D_TOP_K = "Only pick from this many most likely words (Anthropic models).";
    static final String D_SEED = "Same seed and settings give the same answer where the provider supports it.";
    static final String D_FORMAT = "json: ask for one JSON object and also return it parsed.";
    static final Set<String> PROVIDERS = Set.of("openai", "azure_openai", "anthropic", "openai_compatible", "huggingface", "aws_bedrock_sagemaker", "aws_bedrock", "google_vertex");

    static LlmClient.Config cfg(String provider, String model, Object key, String base, double temp, int max, boolean mask) {
        return cfg(provider, model, key, base, temp, max, mask, null, null);
    }

    static LlmClient.Config cfg(String provider, String model, Object key, String base, double temp, int max, boolean mask, Object accessKeyId, String region, Object sessionToken) {
        return cfg(provider, model, key, base, temp, max, mask, accessKeyId, region).withSessionToken(sessionToken);
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

    // G6 options
    static final String D_AWS_SESSION = "AWS Bedrock and AWS SageMaker only: the session token from the vault, when the access key is a temporary one (for example from an assumed role).";
    static final String D_LOGIT_BIAS = "Make words more or less likely (OpenAI and Azure OpenAI): token id to a weight from -100 (never) to 100 (always), e.g. {\"50256\": -100}.";
    static final String D_PROVIDER_OPTIONS = "Extra settings for this provider, added to the request as they are, e.g. {\"reasoning_effort\": \"low\"}; a dictionary is merged into the setting of the same name.";
    static final String D_API_VERSION = "Azure OpenAI only: the API version to call; change it when your resource needs a newer one.";
    static final String D_IMAGE_DETAIL = "How closely the model looks at pictures (OpenAI and Azure OpenAI): low is quicker and cheaper, high reads fine print; auto lets the model decide.";
    static final String D_END_USER = "An id for the person the answer is for, passed on for the provider's abuse monitoring (OpenAI-style providers).";

    @Action(id = "prompt", name = "Prompt", category = "Generative AI", timeoutSeconds = 600, capabilities = {"network", "ai"}, description = "Send a prompt and return the model's answer.",
            panel = "prompt, system, (Model to use: A saved connection = connection / Set up here = provider, model, api_key, base_url, aws_access_key_id, aws_session_token, aws_region) | temperature, max_tokens, response_format, mask_pii, top_p, top_k, stop_sequences, presence_penalty, frequency_penalty, seed, number_of_responses, end_user, logit_bias, provider_options, api_version, timeout_seconds")
    @Output(name = "text", type = OoaType.STRING, description = "Answer (the first one when several were asked for).")
    @Output(name = "responses", type = OoaType.LIST, description = "Every answer, when number_of_responses is more than 1.")
    @Output(name = "json", description = "The answer parsed as JSON, when response_format is json.")
    @Output(name = "input_tokens", type = OoaType.NUMBER, description = "Tokens in the request.")
    @Output(name = "output_tokens", type = OoaType.NUMBER, description = "Tokens in the answer.")
    public Map<String, Object> prompt(ActionContext ctx,
            @Input(name = "prompt", type = OoaType.STRING, description = "Your request; ${...} interpolation allowed.") String prompt,
            @Input(name = "system", type = OoaType.STRING, required = false, defaultValue = "\"You are a precise business assistant.\"", description = "Instructions for the model's role and tone.") String system,
            @Input(name = "provider", type = OoaType.STRING, required = false, defaultValue = "\"openai\"", enumValues = {"openai", "azure_openai", "anthropic", "openai_compatible", "huggingface", "aws_bedrock_sagemaker", "aws_bedrock", "google_vertex"}, description = D_PROVIDER) String provider,
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
            @Input(name = "top_k", type = OoaType.NUMBER, required = false, description = D_TOP_K) Integer topK,
            @Input(name = "stop_sequences", type = OoaType.LIST, required = false, description = D_STOP) List<Object> stop,
            @Input(name = "presence_penalty", type = OoaType.NUMBER, required = false, description = D_PRESENCE) Double presence,
            @Input(name = "frequency_penalty", type = OoaType.NUMBER, required = false, description = D_FREQUENCY) Double frequency,
            @Input(name = "seed", type = OoaType.NUMBER, required = false, description = D_SEED) Integer seed,
            @Input(name = "number_of_responses", type = OoaType.NUMBER, required = false, defaultValue = "1", description = "How many alternative answers to ask for, 1 to 10 (OpenAI-style providers).") int n,
            @Input(name = "response_format", type = OoaType.STRING, required = false, defaultValue = "\"text\"", enumValues = {"text", "json"}, description = "json: ask for one JSON object and also return it parsed.") String format,
            @Input(name = "end_user", type = OoaType.STRING, required = false, description = D_END_USER) String endUser,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_TIMEOUT) int timeout,
            @Input(name = "aws_session_token", type = OoaType.CREDENTIAL, required = false, description = D_AWS_SESSION) Object awsSessionToken,
            @Input(name = "logit_bias", type = OoaType.DICTIONARY, required = false, description = D_LOGIT_BIAS) Map<String, Object> logitBias,
            @Input(name = "provider_options", type = OoaType.DICTIONARY, required = false, description = D_PROVIDER_OPTIONS) Map<String, Object> providerOptions,
            @Input(name = "api_version", type = OoaType.STRING, required = false, defaultValue = "\"" + LlmClient.Options.AZURE_API_VERSION + "\"", description = D_API_VERSION) String apiVersion) {
        LlmClient.Config c = ModelConnections.config(ctx, connection, provider, model, key, base, temp, max, mask, awsKeyId, awsRegion, awsSessionToken)
                .with(LlmClient.Options.of(topP, topK, stop, presence, frequency, seed, endUser, n, timeout).plus(logitBias, null, providerOptions, apiVersion));
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

    /** Earlier signature, kept for callers written before the session token, logit bias, provider options and API version (G6). */
    public Map<String, Object> prompt(ActionContext ctx, String prompt, String system, String provider, String connection, String model, Object key, String base,
                                      double temp, int max, boolean mask, Object awsKeyId, String awsRegion, Double topP, Integer topK, List<Object> stop,
                                      Double presence, Double frequency, Integer seed, int n, String format, String endUser, int timeout) {
        return prompt(ctx, prompt, system, provider, connection, model, key, base, temp, max, mask, awsKeyId, awsRegion, topP, topK, stop, presence, frequency, seed, n, format, endUser, timeout,
                null, null, null, null);
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
            description = "Send a message in a conversation that remembers earlier messages of the same chat session until End chat.",
            panel = "message, chat_session, system, (Model to use: A saved connection = connection / Set up here = provider, model, api_key, base_url, aws_access_key_id, aws_session_token, aws_region), images | temperature, max_tokens, response_format, mask_pii, history_limit, examples, image_detail, top_p, top_k, stop_sequences, presence_penalty, frequency_penalty, seed, end_user, logit_bias, provider_options, api_version, timeout_seconds")
    @Output(name = "text", type = OoaType.STRING, description = "The model's reply.")
    @Output(name = "json", description = "The reply parsed as JSON, when response_format is json.")
    @Output(name = "turns", type = OoaType.NUMBER, description = "Number of messages now held in the session (yours and the model's).")
    @Output(name = "input_tokens", type = OoaType.NUMBER, description = "Tokens in the request.")
    @Output(name = "output_tokens", type = OoaType.NUMBER, description = "Tokens in the reply.")
    public Map<String, Object> chat(ActionContext ctx,
            @Input(name = "message", type = OoaType.STRING, description = "Your next message.") String message,
            @Input(name = "chat_session", type = OoaType.STRING, required = false, defaultValue = "\"Default\"", description = "Name of the conversation; use different names for separate chats in one agent.") String session,
            @Input(name = "system", type = OoaType.STRING, required = false, description = "Instructions for the model's role; set on the first message of a session (a new value replaces the old one).") String system,
            @Input(name = "provider", type = OoaType.STRING, required = false, defaultValue = "\"openai\"", enumValues = {"openai", "azure_openai", "anthropic", "openai_compatible", "huggingface", "aws_bedrock_sagemaker", "aws_bedrock", "google_vertex"}, description = D_PROVIDER) String provider,
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
            @Input(name = "aws_region", type = OoaType.STRING, required = false, description = D_AWS_REGION) String awsRegion,
            @Input(name = "images", type = OoaType.LIST, required = false, description = D_IMAGES_WITH_LINKS + " The model must accept pictures.") List<Object> images,
            @Input(name = "top_k", type = OoaType.NUMBER, required = false, description = D_TOP_K) Integer topK,
            @Input(name = "seed", type = OoaType.NUMBER, required = false, description = D_SEED) Integer seed,
            @Input(name = "response_format", type = OoaType.STRING, required = false, defaultValue = "\"text\"", enumValues = {"text", "json"}, description = D_FORMAT) String format,
            @Input(name = "aws_session_token", type = OoaType.CREDENTIAL, required = false, description = D_AWS_SESSION) Object awsSessionToken,
            @Input(name = "end_user", type = OoaType.STRING, required = false, description = D_END_USER) String endUser,
            @Input(name = "logit_bias", type = OoaType.DICTIONARY, required = false, description = D_LOGIT_BIAS) Map<String, Object> logitBias,
            @Input(name = "examples", type = OoaType.DICTIONARY, required = false, example = "{\"Where is my order 123?\": \"intent: order_status\"}",
                    description = "Sample questions and the answers you want, question to answer; sent before the conversation as earlier turns so the model follows the pattern.") Map<String, Object> examples,
            @Input(name = "image_detail", type = OoaType.STRING, required = false, enumValues = {"auto", "high", "low"}, description = D_IMAGE_DETAIL) String imageDetail,
            @Input(name = "provider_options", type = OoaType.DICTIONARY, required = false, description = D_PROVIDER_OPTIONS) Map<String, Object> providerOptions,
            @Input(name = "api_version", type = OoaType.STRING, required = false, defaultValue = "\"" + LlmClient.Options.AZURE_API_VERSION + "\"", description = D_API_VERSION) String apiVersion) {
        if (message == null || message.isBlank()) throw new ActionException("AI.EMPTY_MESSAGE", "The message is empty", "Fill in the message to send.");
        if (limit < 2 || limit > 500) throw new ActionException("AI.BAD_OPTION", "history_limit must be between 2 and 500", "Set history_limit between 2 and 500.");
        List<Map<String, Object>> shots = examples(examples);
        String k = chatKey(session);
        ChatSession cs = ctx.sessions().get(k) instanceof ChatSession existing ? existing : new ChatSession();
        if (system != null && !system.isBlank()) cs.system = system;
        LlmClient.Config c = ModelConnections.config(ctx, connection, provider, model, key, base, temp, max, mask, awsKeyId, awsRegion, awsSessionToken)
                .with(LlmClient.Options.of(topP, topK, stop, presence, frequency, seed, endUser, null, timeout).plus(logitBias, imageDetail, providerOptions, apiVersion));
        boolean json = "json".equals(format);
        List<Map<String, Object>> m = new ArrayList<>();
        if (cs.system != null) m.add(Map.of("role", "system", "content", cs.system));
        m.addAll(shots);
        m.addAll(cs.messages);
        Object content = withImages(ctx, message, images);
        m.add(Map.of("role", "user", "content", content));
        LlmClient.Reply r = LlmClient.chat(ctx, c, m, null, json, "chat");
        String text = r.text() == null ? "" : r.text();
        cs.messages.add(Map.of("role", "user", "content", content));
        cs.messages.add(Map.of("role", "assistant", "content", text));
        while (cs.messages.size() > limit) cs.messages.remove(0);
        ctx.sessions().put(k, cs);
        return Out.of("text", text, "json", json && !text.isEmpty() ? LlmClient.parseJson(text) : null, "turns", BigDecimal.valueOf(cs.messages.size()), "input_tokens", BigDecimal.valueOf(r.inTokens()), "output_tokens", BigDecimal.valueOf(r.outTokens()));
    }

    /** Earlier signature, kept for callers written before pictures in chats. */
    public Map<String, Object> chat(ActionContext ctx, String message, String session, String system, String provider, String connection, String model, Object key, String base,
                                    double temp, int max, boolean mask, int limit, Double topP, List<Object> stop, Double presence, Double frequency, int timeout, Object awsKeyId, String awsRegion) {
        return chat(ctx, message, session, system, provider, connection, model, key, base, temp, max, mask, limit, topP, stop, presence, frequency, timeout, awsKeyId, awsRegion, null);
    }

    /** Earlier signature, kept for callers written before response format, seed and top K on chats. */
    public Map<String, Object> chat(ActionContext ctx, String message, String session, String system, String provider, String connection, String model, Object key, String base,
                                    double temp, int max, boolean mask, int limit, Double topP, List<Object> stop, Double presence, Double frequency, int timeout, Object awsKeyId, String awsRegion,
                                    List<Object> images) {
        return chat(ctx, message, session, system, provider, connection, model, key, base, temp, max, mask, limit, topP, stop, presence, frequency, timeout, awsKeyId, awsRegion, images, null, null, "text");
    }

    /** Earlier signature, kept for callers written before the session token, examples and the G6 options on chats. */
    public Map<String, Object> chat(ActionContext ctx, String message, String session, String system, String provider, String connection, String model, Object key, String base,
                                    double temp, int max, boolean mask, int limit, Double topP, List<Object> stop, Double presence, Double frequency, int timeout, Object awsKeyId, String awsRegion,
                                    List<Object> images, Integer topK, Integer seed, String format) {
        return chat(ctx, message, session, system, provider, connection, model, key, base, temp, max, mask, limit, topP, stop, presence, frequency, timeout, awsKeyId, awsRegion,
                images, topK, seed, format, null, null, null, null, null, null, null);
    }

    static final String D_IMAGES_WITH_LINKS = "Pictures: file paths (.png, .jpg, .gif, .webp) or web links starting with http:// or https://, which the provider fetches (not Amazon Bedrock).";

    /** G6: sample question-and-answer pairs as earlier turns of the conversation (at most 20). */
    static List<Map<String, Object>> examples(Map<String, Object> examples) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (examples == null || examples.isEmpty()) return out;
        if (examples.size() > 20) throw new ActionException("AI.BAD_OPTION", "Give at most 20 examples", "Keep the 20 examples that show the pattern best.");
        for (var e : examples.entrySet()) {
            String q = String.valueOf(e.getKey()).strip(), a = e.getValue() == null ? "" : Values.text(Values.toJsonable(e.getValue())).strip();
            if (e.getValue() instanceof SecureString) throw new ActionException("AI.SECRET_IN_PROMPT", "A Credential cannot be put into a prompt", "Pass non-secret values only.");
            if (q.isEmpty() || a.isEmpty()) throw new ActionException("AI.BAD_OPTION", "Each example needs a question and an answer", "Fill in both sides of every example, or remove the empty one.");
            out.add(Map.of("role", "user", "content", q));
            out.add(Map.of("role", "assistant", "content", a));
        }
        return out;
    }

    /** The text alone, or the text followed by the pictures as parts. */
    static Object withImages(ActionContext ctx, String text, List<Object> images) {
        return withMedia(ctx, text, images, null);
    }

    /** G6: the text followed by pictures (files, or web links sent as links) and videos (files, Gemini models only). */
    static Object withMedia(ActionContext ctx, String text, List<Object> images, List<Object> videos) {
        boolean noImages = images == null || images.isEmpty(), noVideos = videos == null || videos.isEmpty();
        if (noImages && noVideos) return text;
        if (!noImages && images.size() > 10) throw new ActionException("AI.TOO_MANY_IMAGES", "Send at most 10 pictures at a time", "Send at most 10 pictures in one step.");
        if (!noVideos && videos.size() > 5) throw new ActionException("AI.TOO_MANY_VIDEOS", "Send at most 5 videos at a time", "Send at most 5 videos in one step.");
        List<Object> parts = new ArrayList<>();
        parts.add(Map.of("type", "text", "text", text));
        if (!noImages) for (Object i : images) {
            String s = Values.text(i).strip();
            parts.add(s.regionMatches(true, 0, "http://", 0, 7) || s.regionMatches(true, 0, "https://", 0, 8) ? LlmClient.imageUrlPart(s) : LlmClient.imagePart(ctx.resolvePath(s)));
        }
        if (!noVideos) for (Object v : videos) parts.add(LlmClient.videoPart(ctx.resolvePath(Values.text(v).strip())));
        return parts;
    }

    @Action(id = "prompt_with_images", name = "Prompt with pictures", category = "Generative AI", timeoutSeconds = 600, capabilities = {"network", "ai"},
            description = "Ask about one or more pictures (a scanned form, a screenshot, a photo of a meter), or about videos with Gemini models, and return the model's answer.",
            panel = "prompt, images, videos, system, (Model to use: A saved connection = connection / Set up here = provider, model, api_key, base_url, aws_access_key_id, aws_session_token, aws_region) | temperature, max_tokens, response_format, mask_pii, image_detail, top_p, top_k, stop_sequences, presence_penalty, frequency_penalty, seed, provider_options, api_version, timeout_seconds")
    @Output(name = "text", type = OoaType.STRING, description = "Answer.")
    @Output(name = "json", description = "The answer parsed as JSON, when response_format is json.")
    @Output(name = "input_tokens", type = OoaType.NUMBER, description = "Tokens in the request (pictures included).")
    @Output(name = "output_tokens", type = OoaType.NUMBER, description = "Tokens in the answer.")
    public Map<String, Object> promptWithImages(ActionContext ctx,
            @Input(name = "prompt", type = OoaType.STRING, description = "What you want to know about the pictures; ${...} interpolation allowed.") String prompt,
            @Input(name = "images", type = OoaType.LIST, required = false, description = "Picture files: .png, .jpg, .gif or .webp, at most 10, each up to 20 MB; or web links starting with http:// or https://, "
                    + "which the provider fetches (not Amazon Bedrock).") List<Object> images,
            @Input(name = "system", type = OoaType.STRING, required = false, defaultValue = "\"You are a precise business assistant.\"", description = "Instructions for the model's role and tone.") String system,
            @Input(name = "provider", type = OoaType.STRING, required = false, defaultValue = "\"openai\"", enumValues = {"openai", "azure_openai", "anthropic", "openai_compatible", "huggingface", "aws_bedrock_sagemaker", "aws_bedrock", "google_vertex"}, description = D_PROVIDER) String provider,
            @Input(name = "connection", type = OoaType.STRING, required = false, description = ModelConnections.D_CONNECTION) String connection,
            @Input(name = "model", type = OoaType.STRING, required = false, description = D_MODEL) String model,
            @Input(name = "api_key", type = OoaType.CREDENTIAL, required = false, description = D_KEY) Object key,
            @Input(name = "base_url", type = OoaType.STRING, required = false, description = D_BASE) String base,
            @Input(name = "mask_pii", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = D_MASK) boolean mask,
            @Input(name = "aws_access_key_id", type = OoaType.CREDENTIAL, required = false, description = D_AWS_KEY_ID) Object awsKeyId,
            @Input(name = "aws_region", type = OoaType.STRING, required = false, description = D_AWS_REGION) String awsRegion,
            @Input(name = "temperature", type = OoaType.NUMBER, required = false, defaultValue = "0", description = "0 = consistent, higher = more creative.") double temp,
            @Input(name = "max_tokens", type = OoaType.NUMBER, required = false, defaultValue = "1024", description = "Maximum answer length in tokens.") int max,
            @Input(name = "response_format", type = OoaType.STRING, required = false, defaultValue = "\"text\"", enumValues = {"text", "json"}, description = "json: ask for one JSON object and also return it parsed.") String format,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_TIMEOUT) int timeout,
            @Input(name = "top_p", type = OoaType.NUMBER, required = false, description = D_TOP_P) Double topP,
            @Input(name = "top_k", type = OoaType.NUMBER, required = false, description = D_TOP_K) Integer topK,
            @Input(name = "stop_sequences", type = OoaType.LIST, required = false, description = D_STOP) List<Object> stop,
            @Input(name = "presence_penalty", type = OoaType.NUMBER, required = false, description = D_PRESENCE) Double presence,
            @Input(name = "frequency_penalty", type = OoaType.NUMBER, required = false, description = D_FREQUENCY) Double frequency,
            @Input(name = "seed", type = OoaType.NUMBER, required = false, description = D_SEED) Integer seed,
            @Input(name = "aws_session_token", type = OoaType.CREDENTIAL, required = false, description = D_AWS_SESSION) Object awsSessionToken,
            @Input(name = "videos", type = OoaType.LIST, required = false, description = "Video files for Gemini models (provider google_vertex): .mp4, .mov, .mpeg, .avi, .wmv, .webm, .flv or .3gp, "
                    + "at most 5, each up to 20 MB.") List<Object> videos,
            @Input(name = "image_detail", type = OoaType.STRING, required = false, enumValues = {"auto", "high", "low"}, description = D_IMAGE_DETAIL) String imageDetail,
            @Input(name = "provider_options", type = OoaType.DICTIONARY, required = false, description = D_PROVIDER_OPTIONS) Map<String, Object> providerOptions,
            @Input(name = "api_version", type = OoaType.STRING, required = false, defaultValue = "\"" + LlmClient.Options.AZURE_API_VERSION + "\"", description = D_API_VERSION) String apiVersion) {
        if ((images == null || images.isEmpty()) && (videos == null || videos.isEmpty())) throw new ActionException("AI.NO_IMAGES", "Give at least one picture or video", "Add at least one picture, or a video for a Gemini model.");
        LlmClient.Config c = ModelConnections.config(ctx, connection, provider, model, key, base, temp, max, mask, awsKeyId, awsRegion, awsSessionToken)
                .with(LlmClient.Options.of(topP, topK, stop, presence, frequency, seed, null, null, timeout).plus(null, imageDetail, providerOptions, apiVersion));
        if (videos != null && !videos.isEmpty() && !"google_vertex".equals(c.provider())) throw LlmClient.notForVideo(c.provider());
        boolean json = "json".equals(format);
        List<Map<String, Object>> m = new ArrayList<>();
        if (system != null && !system.isBlank()) m.add(Map.of("role", "system", "content", system));
        m.add(Map.of("role", "user", "content", withMedia(ctx, prompt, images, videos)));
        LlmClient.Reply r = LlmClient.chat(ctx, c, m, null, json, "prompt_with_images");
        if (r.text() == null) throw new ActionException("AI.EMPTY", "The model returned no text", "Retry or raise max_tokens.", true);
        return Out.of("text", r.text(), "json", json ? LlmClient.parseJson(r.text()) : null,
                "input_tokens", BigDecimal.valueOf(r.inTokens()), "output_tokens", BigDecimal.valueOf(r.outTokens()));
    }

    /** Earlier signature, kept for callers written before the sampling options on pictures. */
    public Map<String, Object> promptWithImages(ActionContext ctx, String prompt, List<Object> images, String system, String provider, String connection, String model, Object key,
                                                String base, boolean mask, Object awsKeyId, String awsRegion, double temp, int max, String format, int timeout) {
        return promptWithImages(ctx, prompt, images, system, provider, connection, model, key, base, mask, awsKeyId, awsRegion, temp, max, format, timeout,
                null, null, null, null, null, null);
    }

    /** Earlier signature, kept for callers written before videos, picture links and the G6 options. */
    public Map<String, Object> promptWithImages(ActionContext ctx, String prompt, List<Object> images, String system, String provider, String connection, String model, Object key,
                                                String base, boolean mask, Object awsKeyId, String awsRegion, double temp, int max, String format, int timeout,
                                                Double topP, Integer topK, List<Object> stop, Double presence, Double frequency, Integer seed) {
        return promptWithImages(ctx, prompt, images, system, provider, connection, model, key, base, mask, awsKeyId, awsRegion, temp, max, format, timeout,
                topP, topK, stop, presence, frequency, seed, null, null, null, null, null);
    }

    @Action(id = "generate_image", name = "Generate picture", category = "Generative AI", timeoutSeconds = 600, capabilities = {"network", "ai", "filesystem"},
            description = "Draw pictures from a description and save them as PNG files (OpenAI, Azure OpenAI, OpenAI-compatible, Vertex AI Imagen, Amazon Bedrock).",
            panel = "prompt, output_folder, number_of_images, (Model to use: A saved connection = connection / Set up here = provider, model, api_key, base_url, aws_access_key_id, aws_session_token, aws_region) | file_name, size, prompt_strength, steps, seed, mask_pii, timeout_seconds")
    @Output(name = "file", type = OoaType.STRING, description = "The first picture's file.")
    @Output(name = "files", type = OoaType.LIST, description = "Every picture's file.")
    public Map<String, Object> generateImage(ActionContext ctx,
            @Input(name = "prompt", type = OoaType.STRING, description = "What the picture shows, its style and colours.") String prompt,
            @Input(name = "output_folder", type = OoaType.FILE, required = false, defaultValue = "\"generated-images\"", description = "Folder for the pictures (created when missing).") String folder,
            @Input(name = "file_name", type = OoaType.STRING, required = false, defaultValue = "\"picture\"", description = "File name start; pictures are numbered: picture_1.png, picture_2.png.") String fileName,
            @Input(name = "number_of_images", type = OoaType.NUMBER, required = false, defaultValue = "1", description = "How many pictures, 1 to 4.") int n,
            @Input(name = "size", type = OoaType.STRING, required = false, defaultValue = "\"1024x1024\"", example = "1792x1024", description = "Width x height in pixels; providers that work with aspect ratios use the nearest one.") String size,
            @Input(name = "provider", type = OoaType.STRING, required = false, defaultValue = "\"openai\"", enumValues = {"openai", "azure_openai", "anthropic", "openai_compatible", "huggingface", "aws_bedrock_sagemaker", "aws_bedrock", "google_vertex"}, description = D_PROVIDER) String provider,
            @Input(name = "connection", type = OoaType.STRING, required = false, description = ModelConnections.D_CONNECTION) String connection,
            @Input(name = "model", type = OoaType.STRING, required = false, description = D_MODEL) String model,
            @Input(name = "api_key", type = OoaType.CREDENTIAL, required = false, description = D_KEY) Object key,
            @Input(name = "base_url", type = OoaType.STRING, required = false, description = D_BASE) String base,
            @Input(name = "mask_pii", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = D_MASK) boolean mask,
            @Input(name = "aws_access_key_id", type = OoaType.CREDENTIAL, required = false, description = D_AWS_KEY_ID) Object awsKeyId,
            @Input(name = "aws_region", type = OoaType.STRING, required = false, description = D_AWS_REGION) String awsRegion,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_TIMEOUT) int timeout,
            @Input(name = "aws_session_token", type = OoaType.CREDENTIAL, required = false, description = D_AWS_SESSION) Object awsSessionToken,
            @Input(name = "prompt_strength", type = OoaType.NUMBER, required = false, min = 0, max = 35,
                    description = "How closely the picture follows the description; higher is closer (Amazon Titan and Nova from 1.1 to 10, Stable Diffusion XL up to 35). Model default when empty.") Double promptStrength,
            @Input(name = "steps", type = OoaType.NUMBER, required = false, min = 10, max = 150,
                    description = "How many refining passes the model makes, 10 to 150; more is slower and more detailed (Stable Diffusion XL). Model default when empty.") Integer steps,
            @Input(name = "seed", type = OoaType.NUMBER, required = false, min = 0, max = 2147483646,
                    description = "Same seed and description give the same picture (Amazon Titan, Nova and Stability models). Random when empty.") Integer seed) {
        if (prompt == null || prompt.isBlank()) throw new ActionException("AI.EMPTY_PROMPT", "Describe the picture", "Describe the picture to make.");
        if (n < 1 || n > 4) throw new ActionException("AI.BAD_OPTION", "number_of_images must be between 1 and 4", "Set number_of_images between 1 and 4.");
        if (promptStrength != null && (promptStrength < 0 || promptStrength > 35)) throw new ActionException("AI.BAD_OPTION", "prompt_strength must be between 0 and 35", "Set prompt_strength between 0 and 35, or leave it empty.");
        if (steps != null && (steps < 10 || steps > 150)) throw new ActionException("AI.BAD_OPTION", "steps must be between 10 and 150", "Set steps between 10 and 150, or leave it empty.");
        if (seed != null && (seed < 0 || seed > 2147483646)) throw new ActionException("AI.BAD_OPTION", "seed must be between 0 and 2147483646", "Set seed between 0 and 2147483646, or leave it empty.");
        java.util.regex.Matcher sz = java.util.regex.Pattern.compile("^(\\d{2,5})x(\\d{2,5})$").matcher(size == null ? "" : size.strip());
        if (!sz.matches()) throw new ActionException("AI.BAD_OPTION", "size is WIDTHxHEIGHT, for example 1024x1024", "Write the size as width x height, for example 1024x1024.");
        if (fileName == null || !fileName.matches("^[A-Za-z0-9 _.-]{1,80}$")) throw new ActionException("AI.BAD_OPTION", "file_name uses letters, digits, spaces, . - and _", "Use letters, digits, spaces, . - and _ in the file name.");
        LlmClient.Config c = ModelConnections.config(ctx, connection, provider, model, key, base, 0, 0, mask, awsKeyId, awsRegion, awsSessionToken)
                .with(LlmClient.Options.of(null, null, null, null, null, null, null, null, timeout));
        List<byte[]> pics = LlmClient.generateImages(ctx, c, prompt, n, Integer.parseInt(sz.group(1)), Integer.parseInt(sz.group(2)),
                new LlmClient.ImageOptions(promptStrength, steps, seed));
        java.nio.file.Path dir = ctx.resolvePath(folder == null || folder.isBlank() ? "generated-images" : folder);
        List<Object> files = new ArrayList<>();
        try {
            java.nio.file.Files.createDirectories(dir);
            for (int i = 0; i < pics.size(); i++) {
                java.nio.file.Path f = dir.resolve(fileName + "_" + (i + 1) + ".png");
                java.nio.file.Files.write(f, pics.get(i));
                files.add(f.toString());
            }
        } catch (java.io.IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), "Check that the file or folder exists, is not open in another program, and that the runner's account may read and write it.", true); }
        return Out.of("file", files.get(0), "files", files);
    }

    /** Earlier signature, kept for callers written before the session token, prompt strength, steps and seed (G6). */
    public Map<String, Object> generateImage(ActionContext ctx, String prompt, String folder, String fileName, int n, String size, String provider, String connection, String model,
                                             Object key, String base, boolean mask, Object awsKeyId, String awsRegion, int timeout) {
        return generateImage(ctx, prompt, folder, fileName, n, size, provider, connection, model, key, base, mask, awsKeyId, awsRegion, timeout, null, null, null, null);
    }

    static final List<String> ENDPOINT_TYPES = List.of("application/json", "application/jsonlines", "text/csv", "application/x-protobuf", "image/*", "custom");

    /** G6: any Amazon SageMaker endpoint, with the body as it is and the optional InvokeEndpoint settings. */
    @Action(id = "invoke_endpoint", name = "Call SageMaker endpoint", category = "Generative AI", timeoutSeconds = 600, capabilities = {"network", "ai", "filesystem"},
            description = "Send a request as it is to any Amazon SageMaker endpoint (a model of any kind: forecasting, classification, pictures) and return its answer as text.",
            panel = "endpoint_name, (Request: Typed here = body / From a file = body_file), content_type, custom_content_type, (Sign-in: A saved connection = connection / Set up here = api_key, aws_access_key_id, aws_session_token, aws_region, base_url) "
                    + "| accept, custom_attributes, enable_explanations, inference_id, target_container_hostname, target_model, target_variant, timeout_seconds")
    @Output(name = "text", type = OoaType.STRING, description = "The endpoint's answer as text.")
    @Output(name = "json", description = "The answer parsed as JSON, when it is JSON.")
    @Output(name = "content_type", type = OoaType.STRING, description = "The answer's content type, as the endpoint gave it.")
    @Output(name = "invoked_variant", type = OoaType.STRING, description = "The endpoint variant that answered.")
    public Map<String, Object> invokeEndpoint(ActionContext ctx,
            @Input(name = "endpoint_name", type = OoaType.STRING, example = "churn-xgboost-prod", description = "Name of the SageMaker endpoint.") String endpoint,
            @Input(name = "body", type = OoaType.STRING, required = false, visibleWhen = "body_file=", example = "{\"instances\": [[1.5, 2, 0]]}", description = "The request body, sent as it is.") String body,
            @Input(name = "body_file", type = OoaType.FILE, required = false, visibleWhen = "body=", description = "A file whose bytes are the request body (a picture, a CSV or protobuf file); at most 25 MB.") String bodyFile,
            @Input(name = "content_type", type = OoaType.STRING, required = false, defaultValue = "\"application/json\"", enumValues = {"application/json", "application/jsonlines", "text/csv", "application/x-protobuf", "image/*", "custom"},
                    description = "What the body is; image/* takes the picture type from the file's extension; custom: give it in custom_content_type.") String contentType,
            @Input(name = "custom_content_type", type = OoaType.STRING, required = false, visibleWhen = "content_type=custom", example = "application/x-npy", description = "The content type, when content_type is custom.") String customType,
            @Input(name = "connection", type = OoaType.STRING, required = false, description = ModelConnections.D_CONNECTION) String connection,
            @Input(name = "api_key", type = OoaType.CREDENTIAL, required = false, description = "The AWS secret access key from the vault (or ACCESS_KEY_ID:SECRET).") Object key,
            @Input(name = "aws_access_key_id", type = OoaType.CREDENTIAL, required = false, description = "The AWS access key id from the vault.") Object awsKeyId,
            @Input(name = "aws_session_token", type = OoaType.CREDENTIAL, required = false, description = D_AWS_SESSION) Object awsSessionToken,
            @Input(name = "aws_region", type = OoaType.STRING, required = false, example = "eu-west-1", description = "AWS region of the endpoint.") String awsRegion,
            @Input(name = "base_url", type = OoaType.STRING, required = false, description = "Another address for the SageMaker runtime (a private endpoint); https://runtime.sagemaker.REGION.amazonaws.com when empty.") String base,
            @Input(name = "accept", type = OoaType.STRING, required = false, defaultValue = "\"*/*\"", example = "application/json", description = "The answer type to ask for; any when */*.") String accept,
            @Input(name = "custom_attributes", type = OoaType.STRING, required = false, description = "Extra information passed to the model's code as it is, up to 1024 characters.") String customAttributes,
            @Input(name = "enable_explanations", type = OoaType.STRING, required = false, example = "`true`", description = "With SageMaker Clarify explanations set up: an expression saying which answers to explain.") String explanations,
            @Input(name = "inference_id", type = OoaType.STRING, required = false, description = "Your id for this request, kept with the captured data (up to 64 characters).") String inferenceId,
            @Input(name = "target_container_hostname", type = OoaType.STRING, required = false, description = "Which container answers, on an endpoint with several containers called directly.") String targetContainer,
            @Input(name = "target_model", type = OoaType.STRING, required = false, example = "model-42.tar.gz", description = "Which model answers, on a multi-model endpoint.") String targetModel,
            @Input(name = "target_variant", type = OoaType.STRING, required = false, description = "Which production variant answers, instead of the endpoint's own split.") String targetVariant,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_TIMEOUT) int timeout) {
        if (endpoint == null || !endpoint.strip().matches("^[A-Za-z0-9](-*[A-Za-z0-9]){0,62}$"))
            throw new ActionException("AI.BAD_OPTION", "'" + endpoint + "' is not a SageMaker endpoint name", "Use the endpoint's name: letters, digits and hyphens, at most 63 characters.");
        boolean fromFile = bodyFile != null && !bodyFile.isBlank();
        if (fromFile && body != null && !body.isEmpty()) throw new ActionException("AI.BAD_OPTION", "Give the body or a body file, not both", "Clear body or body_file.");
        String type = contentType == null || contentType.isBlank() ? "application/json" : contentType.strip();
        if (!ENDPOINT_TYPES.contains(type)) throw new ActionException("AI.BAD_OPTION", "Unknown content_type '" + contentType + "'", "Use one of " + String.join(", ", ENDPOINT_TYPES) + ".");
        if (type.equals("custom")) {
            if (customType == null || !customType.strip().matches("^[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+*-]+(;.*)?$"))
                throw new ActionException("AI.BAD_OPTION", "custom_content_type is not a content type: '" + customType + "'", "Write it as type/subtype, for example application/x-npy.");
            type = customType.strip();
        }
        byte[] payload;
        if (fromFile) {
            java.nio.file.Path p = ctx.resolvePath(bodyFile);
            try { if (java.nio.file.Files.isRegularFile(p) && java.nio.file.Files.size(p) > 25L * 1024 * 1024) throw new ActionException("AI.BODY_TOO_LARGE", "The body file is larger than 25 MB", "SageMaker real-time endpoints take at most 25 MB; use an asynchronous endpoint for larger requests."); }
            catch (java.io.IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), "Check that the file exists and that the runner's account may read it.", true); }
            payload = HttpJson.readFile(ctx, bodyFile);
            if (type.equals("image/*")) type = imageType(p);
        } else {
            if (type.equals("image/*")) throw new ActionException("AI.BAD_OPTION", "A picture is sent from a file", "Choose the picture in body_file.");
            payload = (body == null ? "" : body).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        if (payload.length == 0) throw new ActionException("AI.EMPTY_PROMPT", "The request body is empty", "Type the body, or choose a file holding it.");
        Map<String, String> headers = new LinkedHashMap<>();
        header(headers, "X-Amzn-SageMaker-Custom-Attributes", customAttributes, 1024);
        header(headers, "X-Amzn-SageMaker-Enable-Explanations", explanations, 64);
        header(headers, "X-Amzn-SageMaker-Inference-Id", inferenceId, 64);
        header(headers, "X-Amzn-SageMaker-Target-Container-Hostname", targetContainer, 63);
        header(headers, "X-Amzn-SageMaker-Target-Model", targetModel, 1024);
        header(headers, "X-Amzn-SageMaker-Target-Variant", targetVariant, 63);
        LlmClient.Config c = ModelConnections.config(ctx, connection, "aws_bedrock_sagemaker", endpoint.strip(), key, base, 0, 0, false, awsKeyId, awsRegion, awsSessionToken)
                .with(LlmClient.Options.of(null, null, null, null, null, null, null, null, timeout));
        String acc = accept == null || accept.isBlank() ? "*/*" : accept.strip();
        HttpJson.Response r = LlmClient.sagemakerInvoke(ctx, c, payload, type, acc, headers);
        return answer(r, "invoked_variant", r.header("X-Amzn-Invoked-Production-Variant"));
    }

    private static void header(Map<String, String> headers, String name, String value, int max) {
        if (value == null || value.isBlank()) return;
        String v = value.strip();
        if (v.length() > max || !v.matches("^[\\x20-\\x7E]+$"))
            throw new ActionException("AI.BAD_OPTION", name.replace("X-Amzn-SageMaker-", "").replace('-', ' ') + " must be plain text of at most " + max + " characters", "Use printable characters only (no line breaks) and keep it short.");
        headers.put(name, v);
    }

    /** The picture type of a file from its extension. */
    static String imageType(java.nio.file.Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".bmp")) return "image/bmp";
        if (n.endsWith(".tif") || n.endsWith(".tiff")) return "image/tiff";
        throw new ActionException("AI.BAD_IMAGE", "Cannot tell the picture type of " + p.getFileName(), "Use a .png, .jpg, .gif, .webp, .bmp or .tif file, or choose custom and give the content type.");
    }

    /** The answer as text and, when it is JSON, parsed; plus its content type and one more named value. */
    private static Map<String, Object> answer(HttpJson.Response r, String extraName, Object extraValue) {
        String text = new String(r.bytes() == null ? new byte[0] : r.bytes(), java.nio.charset.StandardCharsets.UTF_8);
        Object json = null;
        if (!text.isBlank()) try { json = JsonLite.parse(text.strip()); } catch (IllegalArgumentException ignored) { }
        String type = r.header("Content-Type");
        return Out.of("text", text, "json", json, "content_type", type == null ? "" : type, extraName, extraValue == null ? "" : extraValue);
    }

    /** G6: a Hugging Face model called directly, for any task: a JSON payload, or a file sent as it is. */
    @Action(id = "huggingface_inference", name = "Hugging Face model", category = "Generative AI", timeoutSeconds = 600, capabilities = {"network", "ai", "filesystem"},
            description = "Call a Hugging Face model for any task (classify, fill in, read a picture or sound) with a JSON payload or a data file, and return its answer.",
            panel = "model, (Send: A JSON payload = payload / A data file = data_file), api_key | base_url, output_file, timeout_seconds")
    @Output(name = "text", type = OoaType.STRING, description = "The model's answer as text (empty when it answered with a file).")
    @Output(name = "json", description = "The answer parsed as JSON, when it is JSON.")
    @Output(name = "content_type", type = OoaType.STRING, description = "The answer's content type.")
    @Output(name = "file", type = OoaType.STRING, description = "Where a picture or sound the model made was saved (output_file).")
    public Map<String, Object> huggingFaceInference(ActionContext ctx,
            @Input(name = "model", type = OoaType.STRING, example = "facebook/bart-large-mnli", description = "The model's id on Hugging Face, owner/name.") String model,
            @Input(name = "payload", type = OoaType.STRING, required = false, visibleWhen = "data_file=", example = "{\"inputs\": \"I love this product\"}",
                    description = "The request as JSON, as the model's task expects it, usually {\"inputs\": ..., \"parameters\": {...}}.") String payload,
            @Input(name = "data_file", type = OoaType.FILE, required = false, visibleWhen = "payload=", description = "A picture or sound file sent as it is, for tasks that read one (at most 25 MB).") String dataFile,
            @Input(name = "api_key", type = OoaType.CREDENTIAL, required = false, description = "Your Hugging Face access token (hf_...) from the vault; not needed for your own server.") Object key,
            @Input(name = "base_url", type = OoaType.STRING, required = false, description = "Address the model id is added to; " + LlmClient.HUGGINGFACE_INFERENCE_URL + " when empty, or your own inference server.") String base,
            @Input(name = "output_file", type = OoaType.FILE, required = false, example = "out/picture.png", description = "Where to save the answer when the model answers with a picture or sound.") String outputFile,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_TIMEOUT) int timeout) {
        if (model == null || !model.strip().matches("^[A-Za-z0-9][A-Za-z0-9._-]*(/[A-Za-z0-9][A-Za-z0-9._-]*)?$"))
            throw new ActionException("AI.BAD_OPTION", "'" + model + "' is not a Hugging Face model id", "Write it as owner/name, for example facebook/bart-large-mnli.");
        boolean fromFile = dataFile != null && !dataFile.isBlank();
        if (fromFile && payload != null && !payload.isBlank()) throw new ActionException("AI.BAD_OPTION", "Give a payload or a data file, not both", "Clear payload or data_file.");
        byte[] body;
        String type;
        if (fromFile) {
            java.nio.file.Path p = ctx.resolvePath(dataFile);
            try { if (java.nio.file.Files.isRegularFile(p) && java.nio.file.Files.size(p) > 25L * 1024 * 1024) throw new ActionException("AI.BODY_TOO_LARGE", "The data file is larger than 25 MB", "Send a smaller file."); }
            catch (java.io.IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), "Check that the file exists and that the runner's account may read it.", true); }
            body = HttpJson.readFile(ctx, dataFile);
            type = dataType(p);
        } else {
            if (payload == null || payload.isBlank()) throw new ActionException("AI.EMPTY_PROMPT", "The payload is empty", "Type the JSON payload, or choose a data file.");
            try { JsonLite.parse(payload.strip()); }
            catch (IllegalArgumentException e) { throw new ActionException("AI.BAD_OPTION", "The payload is not valid JSON: " + e.getMessage(), "Check quotes, commas and brackets, for example {\"inputs\": \"text\"}."); }
            body = payload.strip().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            type = "application/json";
        }
        LlmClient.Config c = ModelConnections.config(ctx, null, "huggingface", model.strip(), key, base, 0, 0, false, null, null)
                .with(LlmClient.Options.of(null, null, null, null, null, null, null, null, timeout));
        HttpJson.Response r = LlmClient.huggingFaceInvoke(ctx, c, body, type);
        String rt = r.header("Content-Type") == null ? "" : r.header("Content-Type").toLowerCase(Locale.ROOT);
        boolean textual = rt.isEmpty() || rt.startsWith("text/") || rt.contains("json");
        if (textual) return answer(r, "file", null);
        if (outputFile == null || outputFile.isBlank())
            throw new ActionException("AI.FILE_ANSWER", "The model answered with a file (" + rt + "), not text", "Set output_file to where the answer should be saved.");
        java.nio.file.Path out = ctx.resolvePath(outputFile);
        HttpJson.writeFile(out, r.bytes());
        return Out.of("text", "", "json", null, "content_type", rt, "file", out.toString());
    }

    /** The content type of a data file sent to a model, from its extension. */
    static String dataType(java.nio.file.Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = n.lastIndexOf('.');
        return switch (dot < 0 ? "" : n.substring(dot)) {
            case ".png" -> "image/png";
            case ".jpg", ".jpeg" -> "image/jpeg";
            case ".gif" -> "image/gif";
            case ".webp" -> "image/webp";
            case ".flac" -> "audio/flac";
            case ".wav" -> "audio/wav";
            case ".mp3" -> "audio/mpeg";
            case ".ogg" -> "audio/ogg";
            case ".webm" -> "audio/webm";
            case ".json" -> "application/json";
            default -> "application/octet-stream";
        };
    }

    @Action(id = "detect_language", name = "Detect language", category = "Generative AI", timeoutSeconds = 300, capabilities = {"network", "ai"},
            description = "Tell which language a text is written in.",
            panel = "text, (Model to use: A saved connection = connection / Set up here = provider, model, api_key, base_url, aws_access_key_id, aws_region) | mask_pii")
    @Output(name = "language", type = OoaType.STRING, description = "Language name in English, e.g. German.")
    @Output(name = "code", type = OoaType.STRING, description = "Two-letter ISO 639-1 code, e.g. de.")
    @Output(name = "confidence", type = OoaType.NUMBER, description = "From 0 to 1.")
    public Map<String, Object> detectLanguage(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Text, e.g. an email body.") String text,
            @Input(name = "provider", type = OoaType.STRING, required = false, defaultValue = "\"openai\"", enumValues = {"openai", "azure_openai", "anthropic", "openai_compatible", "huggingface", "aws_bedrock_sagemaker", "aws_bedrock", "google_vertex"}, description = D_PROVIDER) String provider,
            @Input(name = "connection", type = OoaType.STRING, required = false, description = ModelConnections.D_CONNECTION) String connection,
            @Input(name = "model", type = OoaType.STRING, required = false, description = D_MODEL) String model,
            @Input(name = "api_key", type = OoaType.CREDENTIAL, required = false, description = D_KEY) Object key,
            @Input(name = "base_url", type = OoaType.STRING, required = false, description = D_BASE) String base,
            @Input(name = "mask_pii", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = D_MASK) boolean mask,
            @Input(name = "aws_access_key_id", type = OoaType.CREDENTIAL, required = false, description = D_AWS_KEY_ID) Object awsKeyId,
            @Input(name = "aws_region", type = OoaType.STRING, required = false, description = D_AWS_REGION) String awsRegion) {
        Object j = LlmClient.parseJson(ask(ctx, ModelConnections.config(ctx, connection, provider, model, key, base, 0, 100, mask, awsKeyId, awsRegion),
                "Which language is the user's text written in? Reply with JSON {\"language\": \"name in English\", \"code\": \"ISO 639-1 code\", \"confidence\": number from 0 to 1}.", text, true, "detect_language"));
        Map<?, ?> m = j instanceof Map<?, ?> mm ? mm : Map.of();
        String code = String.valueOf(m.get("code")).toLowerCase(Locale.ROOT).strip();
        if (!code.matches("^[a-z]{2,3}$")) code = "";
        return Out.of("language", m.get("language") == null ? "" : String.valueOf(m.get("language")), "code", code, "confidence", unit(m.get("confidence")));
    }

    @Action(id = "key_phrases", name = "Key phrases", category = "Generative AI", timeoutSeconds = 300, capabilities = {"network", "ai"},
            description = "List the phrases that say what a text is about, most important first.",
            panel = "text, (Model to use: A saved connection = connection / Set up here = provider, model, api_key, base_url, aws_access_key_id, aws_region) | language, max_phrases, mask_pii")
    @Output(name = "phrases", type = OoaType.LIST, description = "Phrases as they appear in the text.")
    public Map<String, Object> keyPhrases(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Text, e.g. a support ticket.") String text,
            @Input(name = "max_phrases", type = OoaType.NUMBER, required = false, defaultValue = "10", description = "Most phrases to return, 1 to 50.") int maxPhrases,
            @Input(name = "provider", type = OoaType.STRING, required = false, defaultValue = "\"openai\"", enumValues = {"openai", "azure_openai", "anthropic", "openai_compatible", "huggingface", "aws_bedrock_sagemaker", "aws_bedrock", "google_vertex"}, description = D_PROVIDER) String provider,
            @Input(name = "connection", type = OoaType.STRING, required = false, description = ModelConnections.D_CONNECTION) String connection,
            @Input(name = "model", type = OoaType.STRING, required = false, description = D_MODEL) String model,
            @Input(name = "api_key", type = OoaType.CREDENTIAL, required = false, description = D_KEY) Object key,
            @Input(name = "base_url", type = OoaType.STRING, required = false, description = D_BASE) String base,
            @Input(name = "mask_pii", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = D_MASK) boolean mask,
            @Input(name = "aws_access_key_id", type = OoaType.CREDENTIAL, required = false, description = D_AWS_KEY_ID) Object awsKeyId,
            @Input(name = "aws_region", type = OoaType.STRING, required = false, description = D_AWS_REGION) String awsRegion,
            @Input(name = "language", type = OoaType.STRING, required = false, example = "de", description = D_LANGUAGE) String language) {
        if (maxPhrases < 1 || maxPhrases > 50) throw new ActionException("AI.BAD_OPTION", "max_phrases must be between 1 and 50", "Set max_phrases between 1 and 50.");
        Object j = LlmClient.parseJson(ask(ctx, ModelConnections.config(ctx, connection, provider, model, key, base, 0, 800, mask, awsKeyId, awsRegion),
                "List at most " + maxPhrases + " key phrases of the user's text, most important first, each copied exactly as it appears." + languageNote(language) + " Reply with JSON {\"phrases\": [\"...\"]}.", text, true, "key_phrases"));
        List<Object> out = new ArrayList<>();
        if (j instanceof Map<?, ?> m && m.get("phrases") instanceof List<?> l) for (Object o : l) if (o != null && out.size() < maxPhrases) out.add(String.valueOf(o));
        return Map.of("phrases", out);
    }

    /** Key phrases without the language option (the signature before it was added). */
    public Map<String, Object> keyPhrases(ActionContext ctx, String text, int maxPhrases, String provider, String connection, String model, Object key, String base,
                                          boolean mask, Object awsKeyId, String awsRegion) {
        return keyPhrases(ctx, text, maxPhrases, provider, connection, model, key, base, mask, awsKeyId, awsRegion, null);
    }

    static final String D_LANGUAGE = "Language of the text, e.g. en or de; worked out from the text when empty.";

    /** A sentence telling the model the text's language, or nothing when none is given. */
    static String languageNote(String language) {
        return language == null || language.isBlank() ? "" : " The text is written in " + language.strip() + ".";
    }

    static final List<String> ENTITY_TYPES = List.of("PERSON", "ORGANIZATION", "LOCATION", "DATE", "QUANTITY", "EVENT", "TITLE", "COMMERCIAL_ITEM", "OTHER");

    @Action(id = "entities", name = "Named entities", category = "Generative AI", timeoutSeconds = 300, capabilities = {"network", "ai"},
            description = "Find the people, organisations, places, dates, amounts and products a text names.",
            panel = "text, (Model to use: A saved connection = connection / Set up here = provider, model, api_key, base_url, aws_access_key_id, aws_region) | language, types, mask_pii")
    @Output(name = "entities", type = OoaType.TABLE, description = "Columns: text, type, score.")
    @Output(name = "by_type", type = OoaType.DICTIONARY, description = "Type to the list of texts of that type.")
    public Map<String, Object> entities(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Text, e.g. a contract clause or an email.") String text,
            @Input(name = "types", type = OoaType.LIST, required = false, description = "Only these types (PERSON, ORGANIZATION, LOCATION, DATE, QUANTITY, EVENT, TITLE, COMMERCIAL_ITEM, OTHER); all when empty.") List<Object> types,
            @Input(name = "provider", type = OoaType.STRING, required = false, defaultValue = "\"openai\"", enumValues = {"openai", "azure_openai", "anthropic", "openai_compatible", "huggingface", "aws_bedrock_sagemaker", "aws_bedrock", "google_vertex"}, description = D_PROVIDER) String provider,
            @Input(name = "connection", type = OoaType.STRING, required = false, description = ModelConnections.D_CONNECTION) String connection,
            @Input(name = "model", type = OoaType.STRING, required = false, description = D_MODEL) String model,
            @Input(name = "api_key", type = OoaType.CREDENTIAL, required = false, description = D_KEY) Object key,
            @Input(name = "base_url", type = OoaType.STRING, required = false, description = D_BASE) String base,
            @Input(name = "mask_pii", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = D_MASK + " Off by default because names and numbers are what you are looking for.") boolean mask,
            @Input(name = "aws_access_key_id", type = OoaType.CREDENTIAL, required = false, description = D_AWS_KEY_ID) Object awsKeyId,
            @Input(name = "aws_region", type = OoaType.STRING, required = false, description = D_AWS_REGION) String awsRegion,
            @Input(name = "language", type = OoaType.STRING, required = false, example = "de", description = D_LANGUAGE) String language) {
        List<String> wanted = new ArrayList<>();
        if (types != null) for (Object t : types) {
            String s = Values.text(t).toUpperCase(Locale.ROOT).strip();
            if (!ENTITY_TYPES.contains(s)) throw new ActionException("AI.BAD_OPTION", "Unknown entity type '" + t + "'", "Use " + ENTITY_TYPES);
            wanted.add(s);
        }
        List<String> allowed = wanted.isEmpty() ? ENTITY_TYPES : wanted;
        Object j = LlmClient.parseJson(ask(ctx, ModelConnections.config(ctx, connection, provider, model, key, base, 0, 2000, mask, awsKeyId, awsRegion),
                "Find the named entities in the user's text. Types: " + JsonLite.write(allowed) + ". Copy each entity exactly as written; list each once." + languageNote(language) + " "
                        + "Reply with JSON {\"entities\": [{\"text\": \"...\", \"type\": \"...\", \"score\": number from 0 to 1}]}.", text, true, "entities"));
        List<Object> rows = new ArrayList<>();
        Map<String, Object> byType = new LinkedHashMap<>();
        if (j instanceof Map<?, ?> m && m.get("entities") instanceof List<?> l) for (Object o : l) {
            if (!(o instanceof Map<?, ?> e) || e.get("text") == null) continue;
            String type = String.valueOf(e.get("type")).toUpperCase(Locale.ROOT).strip();
            if (!allowed.contains(type)) { if (!wanted.isEmpty()) continue; type = "OTHER"; }
            String t = String.valueOf(e.get("text"));
            rows.add(new ArrayList<>(Arrays.asList(t, type, unit(e.get("score")))));
            @SuppressWarnings("unchecked") List<Object> list = (List<Object>) byType.computeIfAbsent(type, k -> new ArrayList<>());
            list.add(t);
        }
        return Map.of("entities", Values.table(List.of("text", "type", "score"), rows), "by_type", byType);
    }

    /** Named entities without the language option (the signature before it was added). */
    public Map<String, Object> entities(ActionContext ctx, String text, List<Object> types, String provider, String connection, String model, Object key, String base,
                                        boolean mask, Object awsKeyId, String awsRegion) {
        return entities(ctx, text, types, provider, connection, model, key, base, mask, awsKeyId, awsRegion, null);
    }

    /** A model's 0..1 number, kept in range; 0 when it gave none. */
    static BigDecimal unit(Object v) {
        try { return Values.toNumber(v).max(BigDecimal.ZERO).min(BigDecimal.ONE); } catch (RuntimeException e) { return BigDecimal.ZERO; }
    }

    @Action(id = "end_chat", name = "End chat", category = "Generative AI", description = "Forget the messages of a chat session, so the next Chat starts a new conversation.",
            panel = "chat_session")
    public Map<String, Object> endChat(ActionContext ctx,
            @Input(name = "chat_session", type = OoaType.STRING, required = false, defaultValue = "\"Default\"", description = "Name of the conversation to end.") String session) {
        ctx.sessions().remove(chatKey(session));
        return Map.of();
    }

    @Action(id = "summarize", name = "Summarise", category = "Generative AI", timeoutSeconds = 300, capabilities = {"network", "ai"}, description = "Summarise text in a few sentences, keeping figures exact.",
            panel = "text, sentences, (Model to use: A saved connection = connection / Set up here = provider, model, api_key, base_url, aws_access_key_id, aws_region) | mask_pii")
    @Output(name = "summary", type = OoaType.STRING, description = "Summary.")
    public Map<String, Object> summarize(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Text to summarise.") String text,
            @Input(name = "sentences", type = OoaType.NUMBER, required = false, defaultValue = "3", description = "Target length in sentences.") int n,
            @Input(name = "provider", type = OoaType.STRING, required = false, defaultValue = "\"openai\"", enumValues = {"openai", "azure_openai", "anthropic", "openai_compatible", "huggingface", "aws_bedrock_sagemaker", "aws_bedrock", "google_vertex"}, description = D_PROVIDER) String provider,
            @Input(name = "connection", type = OoaType.STRING, required = false, description = ModelConnections.D_CONNECTION) String connection,
            @Input(name = "model", type = OoaType.STRING, required = false, description = D_MODEL) String model,
            @Input(name = "api_key", type = OoaType.CREDENTIAL, required = false, description = D_KEY) Object key,
            @Input(name = "base_url", type = OoaType.STRING, required = false, description = D_BASE) String base,
            @Input(name = "mask_pii", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = D_MASK) boolean mask,
            @Input(name = "aws_access_key_id", type = OoaType.CREDENTIAL, required = false, description = D_AWS_KEY_ID) Object awsKeyId,
            @Input(name = "aws_region", type = OoaType.STRING, required = false, description = D_AWS_REGION) String awsRegion) {
        return Map.of("summary", ask(ctx, ModelConnections.config(ctx, connection, provider, model, key, base, 0, 1024, mask, awsKeyId, awsRegion), "Summarise the user's text in at most " + n + " sentences. Keep names, dates and figures exact.", text, false, "summarize"));
    }

    @Action(id = "classify", name = "Classify", category = "Generative AI", timeoutSeconds = 300, capabilities = {"network", "ai"}, description = "Put text into exactly one of your categories, with a short reason.",
            panel = "text, labels, (Model to use: A saved connection = connection / Set up here = provider, model, api_key, base_url, aws_access_key_id, aws_region) | mask_pii")
    @Output(name = "label", type = OoaType.STRING, description = "Chosen category (always one of the labels).")
    @Output(name = "reason", type = OoaType.STRING, description = "Why.")
    public Map<String, Object> classify(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Text to classify, e.g. an email body.") String text,
            @Input(name = "labels", type = OoaType.LIST, description = "Allowed categories, e.g. [\"invoice\",\"complaint\",\"other\"].") List<Object> labels,
            @Input(name = "provider", type = OoaType.STRING, required = false, defaultValue = "\"openai\"", enumValues = {"openai", "azure_openai", "anthropic", "openai_compatible", "huggingface", "aws_bedrock_sagemaker", "aws_bedrock", "google_vertex"}, description = D_PROVIDER) String provider,
            @Input(name = "connection", type = OoaType.STRING, required = false, description = ModelConnections.D_CONNECTION) String connection,
            @Input(name = "model", type = OoaType.STRING, required = false, description = D_MODEL) String model,
            @Input(name = "api_key", type = OoaType.CREDENTIAL, required = false, description = D_KEY) Object key,
            @Input(name = "base_url", type = OoaType.STRING, required = false, description = D_BASE) String base,
            @Input(name = "mask_pii", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = D_MASK) boolean mask,
            @Input(name = "aws_access_key_id", type = OoaType.CREDENTIAL, required = false, description = D_AWS_KEY_ID) Object awsKeyId,
            @Input(name = "aws_region", type = OoaType.STRING, required = false, description = D_AWS_REGION) String awsRegion) {
        List<String> ls = labels.stream().map(Values::text).toList();
        if (ls.isEmpty()) throw new ActionException("AI.NO_LABELS", "Give at least one label", "Add at least one label to choose from.");
        Object j = LlmClient.parseJson(ask(ctx, ModelConnections.config(ctx, connection, provider, model, key, base, 0, 300, mask, awsKeyId, awsRegion),
                "Classify the text into exactly one of these labels: " + JsonLite.write(ls) + ". Reply with JSON {\"label\": \"...\", \"reason\": \"...\"}.", text, true, "classify"));
        Map<?, ?> m = j instanceof Map<?, ?> mm ? mm : Map.of();
        String label = String.valueOf(m.get("label"));
        String match = ls.stream().filter(l -> l.equalsIgnoreCase(label.strip())).findFirst()
                .orElseThrow(() -> new ActionException("AI.LABEL_NOT_ALLOWED", "The model answered '" + label + "', which is not one of " + ls, "Retry or describe the labels more clearly.", true));
        return Out.of("label", match, "reason", m.get("reason") == null ? "" : String.valueOf(m.get("reason")));
    }

    @Action(id = "extract", name = "Extract fields", category = "Generative AI", timeoutSeconds = 300, capabilities = {"network", "ai"},
            description = "Extract named fields from text (an email, a contract) into a Dictionary; missing fields come back empty.",
            panel = "text, fields, (Model to use: A saved connection = connection / Set up here = provider, model, api_key, base_url, aws_access_key_id, aws_region) | min_confidence, mask_pii")
    @Output(name = "fields", type = OoaType.DICTIONARY, description = "Field name to value.")
    @Output(name = "confidence", type = OoaType.DICTIONARY, description = "With min_confidence: field name to how sure the model is, from 0 to 1 (0 for a missing value).")
    @Output(name = "needs_review", type = OoaType.BOOLEAN, description = "With min_confidence: true when any field is below it, so a person should check the values.")
    @Output(name = "validation", type = OoaType.DICTIONARY, description = "With min_confidence and a field below it: {fields, confidence, min_confidence}. "
            + "Return it as the agent's output named validation from a work-queue run, and the item waits in the queue for a person to check it.")
    public Map<String, Object> extract(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Source text.") String text,
            @Input(name = "fields", type = OoaType.DICTIONARY, description = "Field name to description, e.g. {\"invoice_number\": \"the invoice id\", \"total\": \"amount due as a number\"}.") Map<String, Object> fields,
            @Input(name = "provider", type = OoaType.STRING, required = false, defaultValue = "\"openai\"", enumValues = {"openai", "azure_openai", "anthropic", "openai_compatible", "huggingface", "aws_bedrock_sagemaker", "aws_bedrock", "google_vertex"}, description = D_PROVIDER) String provider,
            @Input(name = "connection", type = OoaType.STRING, required = false, description = ModelConnections.D_CONNECTION) String connection,
            @Input(name = "model", type = OoaType.STRING, required = false, description = D_MODEL) String model,
            @Input(name = "api_key", type = OoaType.CREDENTIAL, required = false, description = D_KEY) Object key,
            @Input(name = "base_url", type = OoaType.STRING, required = false, description = D_BASE) String base,
            @Input(name = "mask_pii", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = D_MASK + " Off by default because extraction often needs those details.") boolean mask,
            @Input(name = "aws_access_key_id", type = OoaType.CREDENTIAL, required = false, description = D_AWS_KEY_ID) Object awsKeyId,
            @Input(name = "aws_region", type = OoaType.STRING, required = false, description = D_AWS_REGION) String awsRegion,
            @Input(name = "min_confidence", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0, max = 1,
                    description = "0 (off) to 1: the model also says how sure it is of each field; fields below this send the values to a person (needs_review, validation).") double minConfidence) {
        LlmClient.Config cfg = ModelConnections.config(ctx, connection, provider, model, key, base, 0, 1500, mask, awsKeyId, awsRegion);
        boolean rate = minConfidence > 0;
        Object j = LlmClient.parseJson(ask(ctx, cfg, rate
                ? "Extract these fields from the user's text: " + JsonLite.write(fields) + ". Reply with one JSON object {\"values\": {...}, \"confidence\": {...}}, both with exactly these keys: "
                    + "values holds each value (null when not stated; do not guess), confidence how sure you are of it from 0 to 1."
                : "Extract these fields from the user's text: " + JsonLite.write(fields) + ". Reply with one JSON object with exactly these keys; use null when a value is not stated. Do not guess.", text, true, "extract"));
        Map<?, ?> m = j instanceof Map<?, ?> mm ? mm : Map.of();
        Map<?, ?> values = rate && m.get("values") instanceof Map<?, ?> vv ? vv : m;
        Map<?, ?> conf = rate && m.get("confidence") instanceof Map<?, ?> cc ? cc : Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        for (String f : fields.keySet()) out.put(f, values.get(f));
        if (!rate) return Map.of("fields", out);
        Map<String, Object> sure = new LinkedHashMap<>();
        boolean review = false;
        for (String f : fields.keySet()) {
            BigDecimal c;
            try { c = out.get(f) == null ? BigDecimal.ZERO : Values.toNumber(conf.get(f)).max(BigDecimal.ZERO).min(BigDecimal.ONE); } catch (RuntimeException e) { c = BigDecimal.ZERO; }
            sure.put(f, c);
            if (c.doubleValue() < minConfidence) review = true;
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("fields", out);
        r.put("confidence", sure);
        r.put("needs_review", review);
        if (review) r.put("validation", Map.of("fields", out, "confidence", sure, "min_confidence", BigDecimal.valueOf(minConfidence)));
        return r;
    }

    /** Extract fields without the confidence option (the signature before it was added). */
    public Map<String, Object> extract(ActionContext ctx, String text, Map<String, Object> fields, String provider, String connection, String model, Object key, String base,
                                       boolean mask, Object awsKeyId, String awsRegion) {
        return extract(ctx, text, fields, provider, connection, model, key, base, mask, awsKeyId, awsRegion, 0);
    }

    @Action(id = "translate", name = "Translate", category = "Generative AI", timeoutSeconds = 300, capabilities = {"network", "ai"}, description = "Translate text into another language.",
            panel = "text, target_language, (Model to use: A saved connection = connection / Set up here = provider, model, api_key, base_url, aws_access_key_id, aws_region) | mask_pii")
    @Output(name = "text", type = OoaType.STRING, description = "Translation.")
    public Map<String, Object> translate(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Text to translate.") String text,
            @Input(name = "target_language", type = OoaType.STRING, example = "German", description = "Language to translate into.") String lang,
            @Input(name = "provider", type = OoaType.STRING, required = false, defaultValue = "\"openai\"", enumValues = {"openai", "azure_openai", "anthropic", "openai_compatible", "huggingface", "aws_bedrock_sagemaker", "aws_bedrock", "google_vertex"}, description = D_PROVIDER) String provider,
            @Input(name = "connection", type = OoaType.STRING, required = false, description = ModelConnections.D_CONNECTION) String connection,
            @Input(name = "model", type = OoaType.STRING, required = false, description = D_MODEL) String model,
            @Input(name = "api_key", type = OoaType.CREDENTIAL, required = false, description = D_KEY) Object key,
            @Input(name = "base_url", type = OoaType.STRING, required = false, description = D_BASE) String base,
            @Input(name = "mask_pii", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = D_MASK) boolean mask,
            @Input(name = "aws_access_key_id", type = OoaType.CREDENTIAL, required = false, description = D_AWS_KEY_ID) Object awsKeyId,
            @Input(name = "aws_region", type = OoaType.STRING, required = false, description = D_AWS_REGION) String awsRegion) {
        return Map.of("text", ask(ctx, ModelConnections.config(ctx, connection, provider, model, key, base, 0, 2000, mask, awsKeyId, awsRegion), "Translate the user's text into " + lang + ". Return only the translation.", text, false, "translate"));
    }

    @Action(id = "sentiment", name = "Sentiment", category = "Generative AI", timeoutSeconds = 300, capabilities = {"network", "ai"}, description = "Rate text as positive, neutral or negative, with a score from -1 to 1.",
            panel = "text, (Model to use: A saved connection = connection / Set up here = provider, model, api_key, base_url, aws_access_key_id, aws_region) | language, mask_pii")
    @Output(name = "sentiment", type = OoaType.STRING, description = "positive, neutral or negative.")
    @Output(name = "score", type = OoaType.NUMBER, description = "From -1 (very negative) to 1 (very positive).")
    public Map<String, Object> sentiment(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Text, e.g. a customer message.") String text,
            @Input(name = "provider", type = OoaType.STRING, required = false, defaultValue = "\"openai\"", enumValues = {"openai", "azure_openai", "anthropic", "openai_compatible", "huggingface", "aws_bedrock_sagemaker", "aws_bedrock", "google_vertex"}, description = D_PROVIDER) String provider,
            @Input(name = "connection", type = OoaType.STRING, required = false, description = ModelConnections.D_CONNECTION) String connection,
            @Input(name = "model", type = OoaType.STRING, required = false, description = D_MODEL) String model,
            @Input(name = "api_key", type = OoaType.CREDENTIAL, required = false, description = D_KEY) Object key,
            @Input(name = "base_url", type = OoaType.STRING, required = false, description = D_BASE) String base,
            @Input(name = "mask_pii", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = D_MASK) boolean mask,
            @Input(name = "aws_access_key_id", type = OoaType.CREDENTIAL, required = false, description = D_AWS_KEY_ID) Object awsKeyId,
            @Input(name = "aws_region", type = OoaType.STRING, required = false, description = D_AWS_REGION) String awsRegion,
            @Input(name = "language", type = OoaType.STRING, required = false, example = "de", description = D_LANGUAGE) String language) {
        Object j = LlmClient.parseJson(ask(ctx, ModelConnections.config(ctx, connection, provider, model, key, base, 0, 200, mask, awsKeyId, awsRegion),
                "Rate the sentiment of the user's text." + languageNote(language) + " Reply with JSON {\"sentiment\": \"positive|neutral|negative\", \"score\": number between -1 and 1}.", text, true, "sentiment"));
        Map<?, ?> m = j instanceof Map<?, ?> mm ? mm : Map.of();
        String s = String.valueOf(m.get("sentiment")).toLowerCase(Locale.ROOT);
        if (!Set.of("positive", "neutral", "negative").contains(s)) s = "neutral";
        BigDecimal score;
        try { score = Values.toNumber(m.get("score")).max(BigDecimal.ONE.negate()).min(BigDecimal.ONE); } catch (OoaException e) { score = BigDecimal.ZERO; }
        return Out.of("sentiment", s, "score", score);
    }

    /** Sentiment without the language option (the signature before it was added). */
    public Map<String, Object> sentiment(ActionContext ctx, String text, String provider, String connection, String model, Object key, String base,
                                         boolean mask, Object awsKeyId, String awsRegion) {
        return sentiment(ctx, text, provider, connection, model, key, base, mask, awsKeyId, awsRegion, null);
    }

    @Action(id = "detect_pii", name = "Detect personal data", category = "Generative AI", timeoutSeconds = 300, capabilities = {"network", "ai"},
            description = "Find personal data in text (emails, phone, card and ID numbers, IBANs, IP addresses; with a model also names and addresses) and give a masked copy. "
                    + "Patterns work without a model; numbers with a check digit must pass it.",
            panel = "text, kinds, mask_style, mask_char, use_ai, (Model to use: A saved connection = connection / Set up here = provider, model, api_key, base_url, aws_access_key_id, aws_region) | mask_pii")
    @Output(name = "found", type = OoaType.BOOLEAN, description = "True when any personal data was found.")
    @Output(name = "count", type = OoaType.NUMBER, description = "How many pieces were found.")
    @Output(name = "items", type = OoaType.LIST, description = "Each piece as {kind, text, start, end}, in text order and never overlapping; start counts from 0 and end is just past the last character.")
    @Output(name = "masked_text", type = OoaType.STRING, description = "The text with every piece found masked.")
    @Output(name = "kinds_found", type = OoaType.LIST, description = "Each kind found, once, e.g. [\"email\", \"phone\"].")
    public Map<String, Object> detectPii(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Text to check, e.g. an email body or a document's text.") String text,
            @Input(name = "kinds", type = OoaType.LIST, required = false, description = "Only these kinds: email, phone, credit_card, iban, ip_address, us_ssn, in_pan, in_aadhaar, "
                    + "person_name, address (the last two need use_ai). Every kind when empty (names and addresses only with use_ai).") List<Object> kinds,
            @Input(name = "use_ai", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Also ask the model for people's names and postal addresses; "
                    + "the patterns still find the rest without it.") boolean useAi,
            @Input(name = "mask_char", type = OoaType.STRING, required = false, defaultValue = "\"*\"", description = "The one character that replaces masked characters.") String maskChar,
            @Input(name = "mask_style", type = OoaType.STRING, required = false, defaultValue = "\"partial\"", enumValues = {"partial", "full"},
                    description = "partial keeps a little so values can be told apart (j***@acme.com, last 4 digits of numbers); full masks every character.") String maskStyle,
            @Input(name = "provider", type = OoaType.STRING, required = false, defaultValue = "\"openai\"", enumValues = {"openai", "azure_openai", "anthropic", "openai_compatible", "huggingface", "aws_bedrock_sagemaker", "aws_bedrock", "google_vertex"}, description = D_PROVIDER) String provider,
            @Input(name = "connection", type = OoaType.STRING, required = false, description = ModelConnections.D_CONNECTION) String connection,
            @Input(name = "model", type = OoaType.STRING, required = false, description = D_MODEL) String model,
            @Input(name = "api_key", type = OoaType.CREDENTIAL, required = false, description = D_KEY) Object key,
            @Input(name = "base_url", type = OoaType.STRING, required = false, description = D_BASE) String base,
            @Input(name = "mask_pii", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = D_MASK + " Only used with use_ai; the model then sees the patterns' finds as placeholders.") boolean mask,
            @Input(name = "aws_access_key_id", type = OoaType.CREDENTIAL, required = false, description = D_AWS_KEY_ID) Object awsKeyId,
            @Input(name = "aws_region", type = OoaType.STRING, required = false, description = D_AWS_REGION) String awsRegion) {
        String t = text == null ? "" : text;
        String mc = maskChar == null || maskChar.isEmpty() ? "*" : maskChar;
        if (mc.codePointCount(0, mc.length()) != 1) throw new ActionException("AI.BAD_OPTION", "mask_char must be exactly one character, not '" + mc + "'", "Give one character, for example *.");
        String style = maskStyle == null || maskStyle.isBlank() ? "partial" : maskStyle.strip().toLowerCase(Locale.ROOT);
        if (!style.equals("partial") && !style.equals("full")) throw new ActionException("AI.BAD_OPTION", "Unknown mask_style '" + maskStyle + "'", "Use partial or full.");
        List<String> wanted = new ArrayList<>();
        if (kinds != null) for (Object k : kinds) {
            String s = Values.text(k).toLowerCase(Locale.ROOT).strip();
            if (s.isEmpty()) continue;
            if (!PiiDetector.ALL_KINDS.contains(s)) throw new ActionException("AI.BAD_OPTION", "Unknown kind '" + k + "'", "Use " + String.join(", ", PiiDetector.ALL_KINDS) + ".");
            if (PiiDetector.AI_KINDS.contains(s) && !useAi) throw new ActionException("AI.BAD_OPTION", "Finding " + s + " needs a model", "Turn on use_ai and choose a model, or leave " + s + " out of kinds.");
            if (!wanted.contains(s)) wanted.add(s);
        }
        List<String> allowed = wanted.isEmpty() ? (useAi ? PiiDetector.ALL_KINDS : PiiDetector.PATTERN_KINDS) : wanted;
        // every pattern runs, so a number is labelled by its best kind even when that kind is not wanted, then filtered
        List<PiiDetector.Item> pattern = PiiDetector.find(t);
        List<PiiDetector.Item> fromModel = new ArrayList<>();
        List<String> aiWanted = PiiDetector.AI_KINDS.stream().filter(allowed::contains).toList();
        if (useAi && !aiWanted.isEmpty() && !t.isBlank()) {
            Object j = LlmClient.parseJson(ask(ctx, ModelConnections.config(ctx, connection, provider, model, key, base, 0, 2000, mask, awsKeyId, awsRegion),
                    "Find personal data of these kinds in the user's text: " + JsonLite.write(aiWanted) + " (person_name: a person's name; address: a postal or street address). "
                            + "Copy each exactly as written; list each once. Reply with JSON {\"items\": [{\"kind\": \"...\", \"text\": \"...\"}]}.", t, true, "detect_pii"));
            if (j instanceof Map<?, ?> m && m.get("items") instanceof List<?> l) for (Object o : l) {
                if (!(o instanceof Map<?, ?> e) || e.get("text") == null) continue;
                String kind = String.valueOf(e.get("kind")).toLowerCase(Locale.ROOT).strip();
                if (!aiWanted.contains(kind)) continue;
                fromModel.addAll(PiiDetector.locate(t, kind, String.valueOf(e.get("text"))));
            }
        }
        List<PiiDetector.Item> items = new ArrayList<>();
        for (PiiDetector.Item i : PiiDetector.settle(pattern, fromModel)) if (allowed.contains(i.kind())) items.add(i);
        List<Object> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (PiiDetector.Item i : items) { rows.add(i.toMap()); seen.add(i.kind()); }
        List<Object> kindsFound = new ArrayList<>();
        for (String k : PiiDetector.ALL_KINDS) if (seen.contains(k)) kindsFound.add(k);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("found", !items.isEmpty());
        r.put("count", BigDecimal.valueOf(items.size()));
        r.put("items", rows);
        r.put("masked_text", PiiDetector.mask(t, items, mc, style.equals("partial")));
        r.put("kinds_found", kindsFound);
        return r;
    }
}
