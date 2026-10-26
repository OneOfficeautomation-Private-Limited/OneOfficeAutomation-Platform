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
    static final String D_PROVIDER = "openai, azure_openai, anthropic, openai_compatible (LiteLLM, vLLM, Ollama), huggingface (Hugging Face router or your own TGI server), "
            + "aws_bedrock_sagemaker (a model on an Amazon SageMaker endpoint), aws_bedrock (Amazon Bedrock) or google_vertex (Gemini on Google Vertex AI).";
    static final String D_MODEL = "Model name; the deployment name for Azure OpenAI; the endpoint name for aws_bedrock_sagemaker; the model or inference profile id for aws_bedrock.";
    static final String D_KEY = "API key or access token from the vault (not needed for some self-hosted endpoints). For aws_bedrock and aws_bedrock_sagemaker: the AWS secret access key "
            + "(or ACCESS_KEY_ID:SECRET). For google_vertex: a service-account JSON key or an access token.";
    static final String D_BASE = "Endpoint; required for Azure OpenAI, Vertex AI (https://REGION-aiplatform.googleapis.com/v1/projects/PROJECT/locations/REGION) and self-hosted models "
            + "(for a TGI server, e.g. http://tgi-host:8080/v1).";
    static final String D_MASK = "Replace emails, phone and card numbers, IBANs and similar before sending; restored in the answer.";

    static final String D_AWS_KEY_ID = "aws_bedrock and aws_bedrock_sagemaker only: AWS access key id from the vault (api_key then holds the secret access key).";
    static final String D_AWS_REGION = "aws_bedrock and aws_bedrock_sagemaker only: AWS region, e.g. eu-west-1.";
    static final Set<String> PROVIDERS = Set.of("openai", "azure_openai", "anthropic", "openai_compatible", "huggingface", "aws_bedrock_sagemaker", "aws_bedrock", "google_vertex");

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

    @Action(id = "prompt", name = "Prompt", category = "Generative AI", timeoutSeconds = 600, capabilities = {"network", "ai"}, description = "Send a prompt and return the model's answer.",
            panel = "prompt, system, (Model to use: A saved connection = connection / Set up here = provider, model, api_key, base_url, aws_access_key_id, aws_region) | temperature, max_tokens, response_format, mask_pii, top_p, top_k, stop_sequences, presence_penalty, frequency_penalty, seed, number_of_responses, end_user, timeout_seconds")
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
            description = "Send a message in a conversation that remembers earlier messages of the same chat session until End chat.",
            panel = "message, chat_session, system, (Model to use: A saved connection = connection / Set up here = provider, model, api_key, base_url, aws_access_key_id, aws_region), images | temperature, max_tokens, mask_pii, history_limit, top_p, stop_sequences, presence_penalty, frequency_penalty, timeout_seconds")
    @Output(name = "text", type = OoaType.STRING, description = "The model's reply.")
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
            @Input(name = "images", type = OoaType.LIST, required = false, description = "Pictures sent with this message (file paths: .png, .jpg, .gif, .webp); the model must accept pictures.") List<Object> images) {
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
        Object content = withImages(ctx, message, images);
        m.add(Map.of("role", "user", "content", content));
        LlmClient.Reply r = LlmClient.chat(ctx, c, m, null, false, "chat");
        String text = r.text() == null ? "" : r.text();
        cs.messages.add(Map.of("role", "user", "content", content));
        cs.messages.add(Map.of("role", "assistant", "content", text));
        while (cs.messages.size() > limit) cs.messages.remove(0);
        ctx.sessions().put(k, cs);
        return Out.of("text", text, "turns", BigDecimal.valueOf(cs.messages.size()), "input_tokens", BigDecimal.valueOf(r.inTokens()), "output_tokens", BigDecimal.valueOf(r.outTokens()));
    }

    /** Earlier signature, kept for callers written before pictures in chats. */
    public Map<String, Object> chat(ActionContext ctx, String message, String session, String system, String provider, String connection, String model, Object key, String base,
                                    double temp, int max, boolean mask, int limit, Double topP, List<Object> stop, Double presence, Double frequency, int timeout, Object awsKeyId, String awsRegion) {
        return chat(ctx, message, session, system, provider, connection, model, key, base, temp, max, mask, limit, topP, stop, presence, frequency, timeout, awsKeyId, awsRegion, null);
    }

    /** The text alone, or the text followed by the pictures as parts. */
    static Object withImages(ActionContext ctx, String text, List<Object> images) {
        if (images == null || images.isEmpty()) return text;
        if (images.size() > 10) throw new ActionException("AI.TOO_MANY_IMAGES", "Send at most 10 pictures at a time");
        List<Object> parts = new ArrayList<>();
        parts.add(Map.of("type", "text", "text", text));
        for (Object i : images) parts.add(LlmClient.imagePart(ctx.resolvePath(Values.text(i))));
        return parts;
    }

    @Action(id = "prompt_with_images", name = "Prompt with pictures", category = "Generative AI", timeoutSeconds = 600, capabilities = {"network", "ai"},
            description = "Ask about one or more pictures (a scanned form, a screenshot, a photo of a meter) and return the model's answer.",
            panel = "prompt, images, system, (Model to use: A saved connection = connection / Set up here = provider, model, api_key, base_url, aws_access_key_id, aws_region) | temperature, max_tokens, response_format, mask_pii, timeout_seconds")
    @Output(name = "text", type = OoaType.STRING, description = "Answer.")
    @Output(name = "json", description = "The answer parsed as JSON, when response_format is json.")
    @Output(name = "input_tokens", type = OoaType.NUMBER, description = "Tokens in the request (pictures included).")
    @Output(name = "output_tokens", type = OoaType.NUMBER, description = "Tokens in the answer.")
    public Map<String, Object> promptWithImages(ActionContext ctx,
            @Input(name = "prompt", type = OoaType.STRING, description = "What you want to know about the pictures; ${...} interpolation allowed.") String prompt,
            @Input(name = "images", type = OoaType.LIST, description = "Picture files: .png, .jpg, .gif or .webp, at most 10, each up to 20 MB.") List<Object> images,
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
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_TIMEOUT) int timeout) {
        if (images == null || images.isEmpty()) throw new ActionException("AI.NO_IMAGES", "Give at least one picture");
        LlmClient.Config c = ModelConnections.config(ctx, connection, provider, model, key, base, temp, max, mask, awsKeyId, awsRegion)
                .with(LlmClient.Options.of(null, null, null, null, null, null, null, null, timeout));
        boolean json = "json".equals(format);
        List<Map<String, Object>> m = new ArrayList<>();
        if (system != null && !system.isBlank()) m.add(Map.of("role", "system", "content", system));
        m.add(Map.of("role", "user", "content", withImages(ctx, prompt, images)));
        LlmClient.Reply r = LlmClient.chat(ctx, c, m, null, json, "prompt_with_images");
        if (r.text() == null) throw new ActionException("AI.EMPTY", "The model returned no text", "Retry or raise max_tokens.", true);
        return Out.of("text", r.text(), "json", json ? LlmClient.parseJson(r.text()) : null,
                "input_tokens", BigDecimal.valueOf(r.inTokens()), "output_tokens", BigDecimal.valueOf(r.outTokens()));
    }

    @Action(id = "generate_image", name = "Generate picture", category = "Generative AI", timeoutSeconds = 600, capabilities = {"network", "ai", "filesystem"},
            description = "Draw pictures from a description and save them as PNG files (OpenAI, Azure OpenAI, OpenAI-compatible, Vertex AI Imagen, Amazon Bedrock).",
            panel = "prompt, output_folder, number_of_images, (Model to use: A saved connection = connection / Set up here = provider, model, api_key, base_url, aws_access_key_id, aws_region) | file_name, size, mask_pii, timeout_seconds")
    @Output(name = "file", type = OoaType.STRING, description = "The first picture's file.")
    @Output(name = "files", type = OoaType.LIST, description = "Every picture's file.")
    public Map<String, Object> generateImage(ActionContext ctx,
            @Input(name = "prompt", type = OoaType.STRING, description = "What the picture shows, its style and colours.") String prompt,
            @Input(name = "output_folder", type = OoaType.STRING, required = false, defaultValue = "\"generated-images\"", description = "Folder for the pictures (created when missing).") String folder,
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
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_TIMEOUT) int timeout) {
        if (prompt == null || prompt.isBlank()) throw new ActionException("AI.EMPTY_PROMPT", "Describe the picture");
        if (n < 1 || n > 4) throw new ActionException("AI.BAD_OPTION", "number_of_images must be between 1 and 4");
        java.util.regex.Matcher sz = java.util.regex.Pattern.compile("^(\\d{2,5})x(\\d{2,5})$").matcher(size == null ? "" : size.strip());
        if (!sz.matches()) throw new ActionException("AI.BAD_OPTION", "size is WIDTHxHEIGHT, for example 1024x1024");
        if (fileName == null || !fileName.matches("^[A-Za-z0-9 _.-]{1,80}$")) throw new ActionException("AI.BAD_OPTION", "file_name uses letters, digits, spaces, . - and _");
        LlmClient.Config c = ModelConnections.config(ctx, connection, provider, model, key, base, 0, 0, mask, awsKeyId, awsRegion)
                .with(LlmClient.Options.of(null, null, null, null, null, null, null, null, timeout));
        List<byte[]> pics = LlmClient.generateImages(ctx, c, prompt, n, Integer.parseInt(sz.group(1)), Integer.parseInt(sz.group(2)));
        java.nio.file.Path dir = ctx.resolvePath(folder == null || folder.isBlank() ? "generated-images" : folder);
        List<Object> files = new ArrayList<>();
        try {
            java.nio.file.Files.createDirectories(dir);
            for (int i = 0; i < pics.size(); i++) {
                java.nio.file.Path f = dir.resolve(fileName + "_" + (i + 1) + ".png");
                java.nio.file.Files.write(f, pics.get(i));
                files.add(f.toString());
            }
        } catch (java.io.IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), null, true); }
        return Out.of("file", files.get(0), "files", files);
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
            panel = "text, (Model to use: A saved connection = connection / Set up here = provider, model, api_key, base_url, aws_access_key_id, aws_region) | max_phrases, mask_pii")
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
            @Input(name = "aws_region", type = OoaType.STRING, required = false, description = D_AWS_REGION) String awsRegion) {
        if (maxPhrases < 1 || maxPhrases > 50) throw new ActionException("AI.BAD_OPTION", "max_phrases must be between 1 and 50");
        Object j = LlmClient.parseJson(ask(ctx, ModelConnections.config(ctx, connection, provider, model, key, base, 0, 800, mask, awsKeyId, awsRegion),
                "List at most " + maxPhrases + " key phrases of the user's text, most important first, each copied exactly as it appears. Reply with JSON {\"phrases\": [\"...\"]}.", text, true, "key_phrases"));
        List<Object> out = new ArrayList<>();
        if (j instanceof Map<?, ?> m && m.get("phrases") instanceof List<?> l) for (Object o : l) if (o != null && out.size() < maxPhrases) out.add(String.valueOf(o));
        return Map.of("phrases", out);
    }

    static final List<String> ENTITY_TYPES = List.of("PERSON", "ORGANIZATION", "LOCATION", "DATE", "QUANTITY", "EVENT", "TITLE", "COMMERCIAL_ITEM", "OTHER");

    @Action(id = "entities", name = "Named entities", category = "Generative AI", timeoutSeconds = 300, capabilities = {"network", "ai"},
            description = "Find the people, organisations, places, dates, amounts and products a text names.",
            panel = "text, (Model to use: A saved connection = connection / Set up here = provider, model, api_key, base_url, aws_access_key_id, aws_region) | types, mask_pii")
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
            @Input(name = "aws_region", type = OoaType.STRING, required = false, description = D_AWS_REGION) String awsRegion) {
        List<String> wanted = new ArrayList<>();
        if (types != null) for (Object t : types) {
            String s = Values.text(t).toUpperCase(Locale.ROOT).strip();
            if (!ENTITY_TYPES.contains(s)) throw new ActionException("AI.BAD_OPTION", "Unknown entity type '" + t + "'", "Use " + ENTITY_TYPES);
            wanted.add(s);
        }
        List<String> allowed = wanted.isEmpty() ? ENTITY_TYPES : wanted;
        Object j = LlmClient.parseJson(ask(ctx, ModelConnections.config(ctx, connection, provider, model, key, base, 0, 2000, mask, awsKeyId, awsRegion),
                "Find the named entities in the user's text. Types: " + JsonLite.write(allowed) + ". Copy each entity exactly as written; list each once. "
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
            description = "Extract named fields from text (an email, a contract) into a Dictionary; missing fields come back empty.",
            panel = "text, fields, (Model to use: A saved connection = connection / Set up here = provider, model, api_key, base_url, aws_access_key_id, aws_region) | mask_pii")
    @Output(name = "fields", type = OoaType.DICTIONARY, description = "Field name to value.")
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
            @Input(name = "aws_region", type = OoaType.STRING, required = false, description = D_AWS_REGION) String awsRegion) {
        Object j = LlmClient.parseJson(ask(ctx, ModelConnections.config(ctx, connection, provider, model, key, base, 0, 1500, mask, awsKeyId, awsRegion),
                "Extract these fields from the user's text: " + JsonLite.write(fields) + ". Reply with one JSON object with exactly these keys; use null when a value is not stated. Do not guess.", text, true, "extract"));
        Map<String, Object> out = new LinkedHashMap<>();
        Map<?, ?> m = j instanceof Map<?, ?> mm ? mm : Map.of();
        for (String f : fields.keySet()) out.put(f, m.get(f));
        return Map.of("fields", out);
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
            panel = "text, (Model to use: A saved connection = connection / Set up here = provider, model, api_key, base_url, aws_access_key_id, aws_region) | mask_pii")
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
