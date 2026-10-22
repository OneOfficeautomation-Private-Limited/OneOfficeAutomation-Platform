package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Provider-neutral LLM client for the AI packages: OpenAI, Azure OpenAI, Anthropic, any OpenAI-compatible
 * endpoint (LiteLLM, vLLM, Ollama), Hugging Face (Inference Providers router or a self-hosted TGI server) and
 * models hosted on Amazon SageMaker endpoints (signed with AWS Signature V4). Every call masks personal data first (unless disabled), restores it in the
 * answer, and writes a governance record (who, which run, model, tokens, masking; never the prompt text) to
 * ai-governance.jsonl in the workspace.
 */
final class LlmClient {
    static final String P = "AI";

    /**
     * One call's settings. For aws_bedrock_sagemaker, model is the SageMaker endpoint name, apiKey the AWS secret access key,
     * accessKeyId the AWS access key id and region the AWS region.
     */
    record Config(String provider, String model, Object apiKey, String baseUrl, double temperature, int maxTokens, boolean maskPii,
                  Object accessKeyId, String region, Options options) {
        Config(String provider, String model, Object apiKey, String baseUrl, double temperature, int maxTokens, boolean maskPii) {
            this(provider, model, apiKey, baseUrl, temperature, maxTokens, maskPii, null, null, null);
        }
        Config(String provider, String model, Object apiKey, String baseUrl, double temperature, int maxTokens, boolean maskPii, Object accessKeyId, String region) {
            this(provider, model, apiKey, baseUrl, temperature, maxTokens, maskPii, accessKeyId, region, null);
        }
        Config with(Options o) { return new Config(provider, model, apiKey, baseUrl, temperature, maxTokens, maskPii, accessKeyId, region, o); }
        Options opts() { return options == null ? Options.NONE : options; }
    }

    /**
     * Optional sampling settings; null fields are not sent, so each provider keeps its own default. topK is used by
     * Anthropic only; presence and frequency penalties, seed, user and n by OpenAI-style APIs only.
     */
    record Options(Double topP, Integer topK, List<String> stop, Double presencePenalty, Double frequencyPenalty, Integer seed, String user,
                   Integer n, Integer timeoutSeconds) {
        static final Options NONE = new Options(null, null, null, null, null, null, null, null, null);
        int timeout() { return timeoutSeconds == null || timeoutSeconds <= 0 ? 180 : timeoutSeconds; }

        /** Builds options from action inputs, checking ranges; empty values mean "provider default". */
        static Options of(Double topP, Integer topK, List<Object> stop, Double presence, Double frequency, Integer seed, String user, Integer n, Integer timeout) {
            if (topP != null && (topP <= 0 || topP > 1)) throw new ActionException(P + ".BAD_OPTION", "top_p must be above 0 and at most 1");
            if (topK != null && (topK < 1 || topK > 500)) throw new ActionException(P + ".BAD_OPTION", "top_k must be between 1 and 500");
            if (presence != null && (presence < -2 || presence > 2)) throw new ActionException(P + ".BAD_OPTION", "presence_penalty must be between -2 and 2");
            if (frequency != null && (frequency < -2 || frequency > 2)) throw new ActionException(P + ".BAD_OPTION", "frequency_penalty must be between -2 and 2");
            if (n != null && (n < 1 || n > 10)) throw new ActionException(P + ".BAD_OPTION", "number_of_responses must be between 1 and 10");
            if (timeout != null && (timeout < 0 || timeout > 1800)) throw new ActionException(P + ".BAD_OPTION", "timeout_seconds must be between 1 and 1800");
            List<String> s = null;
            if (stop != null && !stop.isEmpty()) {
                s = new ArrayList<>();
                for (Object o : stop) { String t = Values.text(o); if (!t.isEmpty()) s.add(t); }
                if (s.size() > 4) throw new ActionException(P + ".BAD_OPTION", "Give at most 4 stop sequences");
                if (s.isEmpty()) s = null;
            }
            return new Options(topP, topK, s, presence, frequency, seed, user == null || user.isBlank() ? null : user.strip(), n == null || n == 1 ? null : n, timeout == null || timeout == 0 ? null : timeout);
        }
    }

    static final String HUGGINGFACE_URL = "https://router.huggingface.co/v1";
    record ToolCall(String id, String name, Map<String, Object> args) {}
    record Reply(String text, List<ToolCall> toolCalls, int inTokens, int outTokens, Map<String, Integer> masked, List<String> choices) {
        Reply(String text, List<ToolCall> toolCalls, int inTokens, int outTokens, Map<String, Integer> masked) { this(text, toolCalls, inTokens, outTokens, masked, null); }
        /** Every answer when several were asked for; just the one answer otherwise. */
        List<String> all() { return choices != null && !choices.isEmpty() ? choices : text == null ? List.of() : List.of(text); }
    }

    // ---------- PII guard (same rules as the Python reference) ----------
    private static final Map<String, Pattern> PII = new LinkedHashMap<>();
    static {
        PII.put("EMAIL", Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b"));
        PII.put("IBAN", Pattern.compile("\\b[A-Z]{2}\\d{2}[A-Z0-9]{11,30}\\b"));
        PII.put("CREDIT_CARD", Pattern.compile("(?<![\\w.-])(?:\\d{4}(?:[ -]\\d{4}){2}[ -]\\d{1,7}|\\d{4}[ -]\\d{6}[ -]\\d{4,5}|\\d{13,19})(?![\\w-])(?!\\.\\d)"));
        PII.put("US_SSN", Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b"));
        PII.put("IP_ADDRESS", Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b"));
        PII.put("PHONE", Pattern.compile("(?<!\\w)(?<!\\d[ .-])\\+?\\d{1,3}[ .-]?\\(?\\d{2,4}\\)?[ .-]?\\d{3,4}[ .-]?\\d{3,4}(?!\\w)(?![ .-]?\\d)"));
    }

    static boolean luhn(String digits) {
        int sum = 0; boolean alt = false;
        for (int i = digits.length() - 1; i >= 0; i--) { int n = digits.charAt(i) - '0'; if (alt) { n *= 2; if (n > 9) n -= 9; } sum += n; alt = !alt; }
        return sum % 10 == 0;
    }

    static String token(String entity, String value) {
        try { return "<" + entity + "_" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(("ooa" + value).getBytes(StandardCharsets.UTF_8))).substring(0, 6) + ">"; }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** Masks text in place; fills mapping (token → original) and counts. */
    static String mask(String text, Map<String, String> mapping, Map<String, Integer> counts) {
        String out = text;
        for (var e : PII.entrySet()) {
            Matcher m = e.getValue().matcher(out);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String v = m.group();
                if (e.getKey().equals("CREDIT_CARD")) { String d = v.replaceAll("\\D", ""); if (d.length() < 13 || d.length() > 19 || !luhn(d)) { m.appendReplacement(sb, Matcher.quoteReplacement(v)); continue; } }
                String t = token(e.getKey(), v);
                mapping.put(t, v);
                counts.merge(e.getKey(), 1, Integer::sum);
                m.appendReplacement(sb, Matcher.quoteReplacement(t));
            }
            m.appendTail(sb);
            out = sb.toString();
        }
        return out;
    }

    static String unmask(String text, Map<String, String> mapping) {
        if (text == null) return null;
        String out = text;
        for (var e : mapping.entrySet()) out = out.replace(e.getKey(), e.getValue());
        return out;
    }

    @SuppressWarnings("unchecked")
    static Object unmaskDeep(Object v, Map<String, String> mapping) {
        if (v instanceof String s) return unmask(s, mapping);
        if (v instanceof Map<?, ?> m) { Map<String, Object> o = new LinkedHashMap<>(); m.forEach((k, x) -> o.put(String.valueOf(k), unmaskDeep(x, mapping))); return o; }
        if (v instanceof List<?> l) { List<Object> o = new ArrayList<>(); for (Object x : l) o.add(unmaskDeep(x, mapping)); return o; }
        return v;
    }

    // ---------- chat ----------
    /**
     * messages: [{role: system|user|assistant|tool, content, tool_calls?, tool_call_id?, name?}] (neutral format).
     * tools: [{name, description, input_schema}].
     */
    @SuppressWarnings("unchecked")
    static Reply chat(ActionContext ctx, Config cfg, List<Map<String, Object>> messages, List<Map<String, Object>> tools, boolean jsonMode, String purpose) {
        Map<String, String> mapping = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<Map<String, Object>> msgs = new ArrayList<>();
        for (Map<String, Object> m : messages) {
            Map<String, Object> c = new LinkedHashMap<>(m);
            if (cfg.maskPii() && !"system".equals(m.get("role")) && m.get("content") instanceof String s) c.put("content", mask(s, mapping, counts));
            if (cfg.maskPii() && m.get("tool_calls") instanceof List<?> tcs) {
                List<Object> masked = new ArrayList<>();
                for (Object t : tcs) {
                    Map<String, Object> tc = new LinkedHashMap<>((Map<String, Object>) t);
                    tc.put("args", JsonLite.parse(mask(JsonLite.write(tc.get("args")), mapping, counts)));
                    masked.add(tc);
                }
                c.put("tool_calls", masked);
            }
            msgs.add(c);
        }
        String provider = cfg.provider() == null ? "openai" : cfg.provider();
        long t0 = System.currentTimeMillis();
        Reply r = switch (provider) {
            case "anthropic" -> anthropic(ctx, cfg, msgs, tools, jsonMode);
            case "aws_bedrock_sagemaker" -> sagemaker(ctx, cfg, msgs, tools, jsonMode);
            default -> openai(ctx, cfg, provider, msgs, tools, jsonMode);
        };
        List<ToolCall> calls = new ArrayList<>();
        for (ToolCall tc : r.toolCalls()) calls.add(new ToolCall(tc.id(), tc.name(), (Map<String, Object>) unmaskDeep(tc.args(), mapping)));
        List<String> choices = null;
        if (r.choices() != null) { choices = new ArrayList<>(); for (String c : r.choices()) choices.add(unmask(c, mapping)); }
        Reply out = new Reply(unmask(r.text(), mapping), calls, r.inTokens(), r.outTokens(), counts, choices);
        govern(ctx, cfg, provider, purpose, out, System.currentTimeMillis() - t0, msgs);
        return out;
    }

    private static void govern(ActionContext ctx, Config cfg, String provider, String purpose, Reply r, long ms, List<Map<String, Object>> sent) {
        try {
            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("ts", Instant.now().toString());
            rec.put("run_id", ctx.runId());
            rec.put("step_id", ctx.stepId());
            rec.put("purpose", purpose);
            rec.put("provider", provider);
            rec.put("model", cfg.model());
            rec.put("input_tokens", r.inTokens());
            rec.put("output_tokens", r.outTokens());
            rec.put("latency_ms", ms);
            rec.put("masked", r.masked());
            rec.put("tool_calls", r.toolCalls().stream().map(ToolCall::name).toList());
            rec.put("prompt_sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JsonLite.canonical(sent).getBytes(StandardCharsets.UTF_8))));
            Files.writeString(ctx.workDir().resolve("ai-governance.jsonl"), JsonLite.write(rec) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) { /* governance logging must never break a run */ }
        ctx.info("AI call: " + provider + "/" + cfg.model() + ", " + r.inTokens() + "+" + r.outTokens() + " tokens" + (r.masked().isEmpty() ? "" : ", masked " + r.masked()));
    }

    private static int num(Object v) { return v instanceof BigDecimal d ? d.intValue() : 0; }

    /** Chat-completions request body (OpenAI format, also used by Hugging Face TGI and SageMaker's messages API). */
    @SuppressWarnings("unchecked")
    static Map<String, Object> openAiBody(Config cfg, String model, List<Map<String, Object>> msgs, List<Map<String, Object>> tools, boolean json) {
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> m : msgs) {
            Map<String, Object> o = new LinkedHashMap<>();
            String role = String.valueOf(m.get("role"));
            o.put("role", role);
            if (role.equals("tool")) { o.put("tool_call_id", m.get("tool_call_id")); o.put("content", m.get("content")); }
            else {
                o.put("content", m.get("content"));
                if (m.get("tool_calls") instanceof List<?> tcs && !tcs.isEmpty()) {
                    List<Object> l = new ArrayList<>();
                    for (Object t : tcs) { Map<String, Object> tc = (Map<String, Object>) t;
                        l.add(Map.of("id", tc.get("id"), "type", "function", "function", Map.of("name", tc.get("name"), "arguments", JsonLite.write(tc.get("args"))))); }
                    o.put("tool_calls", l);
                }
            }
            out.add(o);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        if (model != null) body.put("model", model);
        body.put("messages", out);
        body.put("temperature", BigDecimal.valueOf(cfg.temperature()));
        body.put("max_tokens", cfg.maxTokens());
        Options o = cfg.opts();
        if (o.topP() != null) body.put("top_p", BigDecimal.valueOf(o.topP()));
        if (o.stop() != null) body.put("stop", o.stop());
        if (o.presencePenalty() != null) body.put("presence_penalty", BigDecimal.valueOf(o.presencePenalty()));
        if (o.frequencyPenalty() != null) body.put("frequency_penalty", BigDecimal.valueOf(o.frequencyPenalty()));
        if (o.seed() != null) body.put("seed", o.seed());
        if (o.user() != null) body.put("user", o.user());
        if (o.n() != null) body.put("n", o.n());
        if (tools != null && !tools.isEmpty()) {
            List<Object> l = new ArrayList<>();
            for (Map<String, Object> t : tools) l.add(Map.of("type", "function", "function", Map.of("name", t.get("name"), "description", t.get("description"), "parameters", t.get("input_schema"))));
            body.put("tools", l);
        } else if (json) body.put("response_format", Map.of("type", "json_object"));
        return body;
    }

    private static Reply openai(ActionContext ctx, Config cfg, String provider, List<Map<String, Object>> msgs, List<Map<String, Object>> tools, boolean json) {
        Map<String, Object> body = openAiBody(cfg, provider.equals("azure_openai") ? null : cfg.model(), msgs, tools, json);
        String url;
        HttpJson.Req req;
        if (provider.equals("azure_openai")) {
            if (cfg.baseUrl() == null || cfg.baseUrl().isBlank()) throw new ActionException(P + ".NO_ENDPOINT", "Azure OpenAI needs base_url (your resource endpoint)", "Example: https://myresource.openai.azure.com");
            url = cfg.baseUrl().replaceAll("/+$", "") + "/openai/deployments/" + HttpJson.enc(cfg.model()) + "/chat/completions?api-version=2024-06-01";
            if (!(cfg.apiKey() instanceof SecureString k)) throw new ActionException(P + ".NO_KEY", "An API key from the vault is required");
            req = HttpJson.req(P, "POST", url).header("api-key", k.reveal());
        } else {
            String dflt = provider.equals("huggingface") ? HUGGINGFACE_URL : "https://api.openai.com/v1";
            String base = cfg.baseUrl() == null || cfg.baseUrl().isBlank() ? dflt : cfg.baseUrl().replaceAll("/+$", "");
            req = HttpJson.req(P, "POST", base + "/chat/completions");
            if (cfg.apiKey() instanceof SecureString) req.bearer(cfg.apiKey());
            else if (provider.equals("openai")) throw new ActionException(P + ".NO_KEY", "An API key from the vault is required", "Map api_key to a Credential variable.");
            else if (provider.equals("huggingface") && (cfg.baseUrl() == null || cfg.baseUrl().isBlank()))
                throw new ActionException(P + ".NO_KEY", "A Hugging Face access token from the vault is required", "Map api_key to a Credential holding your hf_... token.");
        }
        return openAiReply(req.json(body).timeout(cfg.opts().timeout()).send(ctx).map());
    }

    @SuppressWarnings("unchecked")
    static Reply openAiReply(Map<String, Object> r) {
        List<?> all = (List<?>) r.getOrDefault("choices", List.of(Map.of()));
        Map<String, Object> msg = (Map<String, Object>) ((Map<String, Object>) all.get(0)).getOrDefault("message", Map.of());
        List<String> choices = null;
        if (all.size() > 1) {
            choices = new ArrayList<>();
            for (Object c : all) if (c instanceof Map<?, ?> cm && cm.get("message") instanceof Map<?, ?> mm && mm.get("content") instanceof String s) choices.add(s);
        }
        List<ToolCall> calls = new ArrayList<>();
        if (msg.get("tool_calls") instanceof List<?> tcs) for (Object t : tcs) {
            Map<String, Object> tc = (Map<String, Object>) t, fn = (Map<String, Object>) tc.get("function");
            Object args;
            try { args = JsonLite.parse(String.valueOf(fn.getOrDefault("arguments", "{}"))); } catch (IllegalArgumentException e) { args = Map.of(); }
            calls.add(new ToolCall(String.valueOf(tc.get("id")), String.valueOf(fn.get("name")), args instanceof Map ? (Map<String, Object>) args : Map.of()));
        }
        Map<String, Object> u = r.get("usage") instanceof Map ? (Map<String, Object>) r.get("usage") : Map.of();
        return new Reply((String) msg.get("content"), calls, num(u.get("prompt_tokens")), num(u.get("completion_tokens")), Map.of(), choices);
    }

    /**
     * A model on an Amazon SageMaker real-time endpoint (for example a Hugging Face TGI or LMI container), called through
     * InvokeEndpoint with the messages API body and signed with AWS Signature V4. Answers in chat-completions form, or the
     * older [{"generated_text": ...}] form, are both understood.
     */
    @SuppressWarnings("unchecked")
    private static Reply sagemaker(ActionContext ctx, Config cfg, List<Map<String, Object>> msgs, List<Map<String, Object>> tools, boolean json) {
        String[] keys = awsKeys(cfg);
        String region = awsRegion(cfg);
        if (!region.matches("^[a-z0-9-]{4,30}$")) throw new ActionException(P + ".BAD_REGION", "'" + region + "' is not an AWS region name", "For example eu-west-1.");
        String base = cfg.baseUrl() == null || cfg.baseUrl().isBlank() ? "https://runtime.sagemaker." + region + ".amazonaws.com" : cfg.baseUrl().replaceAll("/+$", "");
        String url = base + "/endpoints/" + HttpJson.enc(cfg.model()) + "/invocations";
        byte[] payload = JsonLite.write(Values.toJsonable(openAiBody(cfg, null, msgs, tools, json))).getBytes(StandardCharsets.UTF_8);
        java.net.URI uri;
        try { uri = java.net.URI.create(url); }
        catch (IllegalArgumentException e) { throw new ActionException(P + ".BAD_URL", "Invalid SageMaker URL: " + url, "Check the region, endpoint name and base_url."); }
        Map<String, String> signed = new LinkedHashMap<>();
        signed.put("content-type", "application/json");
        signed.put("accept", "application/json");
        HttpJson.Req req = HttpJson.req(P, "POST", url).bytes(payload, "application/json").header("Accept", "application/json");
        AwsSigV4.sign("POST", uri, signed, payload, keys[0], keys[1], region, "sagemaker", Instant.now()).forEach(req::header);
        Object j = req.timeout(cfg.opts().timeout()).send(ctx).json();
        if (j instanceof Map<?, ?> m && m.get("choices") instanceof List) return openAiReply((Map<String, Object>) m);
        Object first = j instanceof List<?> l && !l.isEmpty() ? l.get(0) : j;
        if (first instanceof Map<?, ?> m && m.get("generated_text") != null) return new Reply(String.valueOf(m.get("generated_text")), List.of(), 0, 0, Map.of());
        throw new ActionException(P + ".BAD_RESPONSE", "The SageMaker endpoint answered in a format this client does not understand",
                "Use a container with the messages API (TGI 1.4+ or LMI), or one that returns generated_text.");
    }

    /** [access key id, secret key]. The secret may also hold "ACCESS_KEY_ID:SECRET" when no separate access key id is given. */
    static String[] awsKeys(Config cfg) {
        if (!(cfg.apiKey() instanceof SecureString secret))
            throw new ActionException(P + ".NO_KEY", "The AWS secret access key from the vault is required", "Map api_key to a Credential holding the secret access key.");
        String s = secret.reveal();
        if (cfg.accessKeyId() instanceof SecureString id) return new String[]{id.reveal().strip(), s.strip()};
        int i = s.indexOf(':');
        if (i > 0) return new String[]{s.substring(0, i).strip(), s.substring(i + 1).strip()};
        throw new ActionException(P + ".NO_ACCESS_KEY", "The AWS access key id from the vault is required",
                "Map aws_access_key_id to a Credential, or store the key as ACCESS_KEY_ID:SECRET in one Credential.");
    }

    /** Region from the action, else from a runtime.sagemaker.REGION.amazonaws.com base_url, else the AWS_REGION variable. */
    static String awsRegion(Config cfg) {
        if (cfg.region() != null && !cfg.region().isBlank()) return cfg.region().strip();
        if (cfg.baseUrl() != null) {
            Matcher m = Pattern.compile("runtime\\.sagemaker\\.([a-z0-9-]+)\\.amazonaws\\.com").matcher(cfg.baseUrl());
            if (m.find()) return m.group(1);
        }
        String env = System.getenv("AWS_REGION");
        if (env != null && !env.isBlank()) return env.strip();
        throw new ActionException(P + ".NO_REGION", "The AWS region is required", "Set aws_region, for example eu-west-1.");
    }

    @SuppressWarnings("unchecked")
    private static Reply anthropic(ActionContext ctx, Config cfg, List<Map<String, Object>> msgs, List<Map<String, Object>> tools, boolean json) {
        StringBuilder system = new StringBuilder();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> m : msgs) {
            String role = String.valueOf(m.get("role"));
            if (role.equals("system")) { system.append(m.get("content")).append("\n\n"); continue; }
            if (role.equals("tool")) {
                Map<String, Object> block = Map.of("type", "tool_result", "tool_use_id", m.get("tool_call_id"), "content", String.valueOf(m.get("content")));
                Map<String, Object> last = out.isEmpty() ? null : out.get(out.size() - 1);
                if (last != null && "user".equals(last.get("role")) && last.get("content") instanceof List<?> l && !l.isEmpty() && ((Map<String, Object>) l.get(0)).get("type").equals("tool_result"))
                    ((List<Object>) l).add(block);
                else out.add(new LinkedHashMap<>(Map.of("role", "user", "content", new ArrayList<>(List.of(block)))));
            } else if (role.equals("assistant")) {
                List<Object> blocks = new ArrayList<>();
                if (m.get("content") instanceof String s && !s.isEmpty()) blocks.add(Map.of("type", "text", "text", s));
                if (m.get("tool_calls") instanceof List<?> tcs) for (Object t : tcs) { Map<String, Object> tc = (Map<String, Object>) t;
                    blocks.add(Map.of("type", "tool_use", "id", tc.get("id"), "name", tc.get("name"), "input", tc.get("args"))); }
                out.add(Map.of("role", "assistant", "content", blocks));
            } else out.add(Map.of("role", "user", "content", String.valueOf(m.get("content"))));
        }
        if (json && (tools == null || tools.isEmpty())) system.append("Respond with a single JSON object only, no other text.");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", cfg.model());
        body.put("max_tokens", cfg.maxTokens());
        body.put("temperature", BigDecimal.valueOf(cfg.temperature()));
        if (system.length() > 0) body.put("system", system.toString().strip());
        body.put("messages", out);
        if (tools != null && !tools.isEmpty()) body.put("tools", tools);
        Options opt = cfg.opts();
        if (opt.topP() != null) body.put("top_p", BigDecimal.valueOf(opt.topP()));
        if (opt.topK() != null) body.put("top_k", opt.topK());
        if (opt.stop() != null) body.put("stop_sequences", opt.stop());
        if (!(cfg.apiKey() instanceof SecureString k)) throw new ActionException(P + ".NO_KEY", "An API key from the vault is required", "Map api_key to a Credential variable.");
        String base = cfg.baseUrl() == null || cfg.baseUrl().isBlank() ? "https://api.anthropic.com" : cfg.baseUrl().replaceAll("/+$", "");
        Map<String, Object> r = HttpJson.req(P, "POST", base + "/v1/messages").header("x-api-key", k.reveal()).header("anthropic-version", "2023-06-01")
                .json(body).timeout(opt.timeout()).send(ctx).map();
        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        for (Object o : (List<?>) r.getOrDefault("content", List.of())) {
            Map<String, Object> b = (Map<String, Object>) o;
            if ("text".equals(b.get("type"))) text.append(b.get("text"));
            if ("tool_use".equals(b.get("type"))) calls.add(new ToolCall(String.valueOf(b.get("id")), String.valueOf(b.get("name")), b.get("input") instanceof Map ? (Map<String, Object>) b.get("input") : Map.of()));
        }
        Map<String, Object> u = r.get("usage") instanceof Map ? (Map<String, Object>) r.get("usage") : Map.of();
        return new Reply(text.length() == 0 ? null : text.toString(), calls, num(u.get("input_tokens")), num(u.get("output_tokens")), Map.of());
    }

    /** Parses a model's JSON answer, tolerating ```json fences. */
    static Object parseJson(String text) {
        String t = text == null ? "" : text.strip();
        if (t.startsWith("```")) t = t.replaceFirst("^```[a-zA-Z]*\\s*", "").replaceFirst("\\s*```$", "");
        int a = t.indexOf('{'), b = t.lastIndexOf('}');
        if (a >= 0 && b > a) t = t.substring(a, b + 1);
        try { return JsonLite.parse(t); }
        catch (IllegalArgumentException e) { throw new ActionException(P + ".BAD_JSON", "The model did not return valid JSON", "Retry, lower the temperature or use a stronger model.", true); }
    }

    static String interpolate(String template, Map<String, Object> values) {
        Matcher m = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)\\}").matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Object v = values == null ? null : values.get(m.group(1));
            if (v instanceof SecureString) throw new ActionException(P + ".SECRET_IN_PROMPT", "A Credential cannot be put into a prompt", "Pass non-secret values only.");
            m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? "" : Values.text(Values.toJsonable(v))));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    static Path skillsDir(ActionContext ctx) { return ctx.workDir().resolve("ai-skills"); }

}
