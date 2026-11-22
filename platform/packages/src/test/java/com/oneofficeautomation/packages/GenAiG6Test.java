package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;

import static com.oneofficeautomation.packages.AiProvidersTest.*;
import static com.oneofficeautomation.packages.ConnectorsTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** G6 rows for Generative AI: AWS session tokens, any SageMaker endpoint, raw Hugging Face calls, and the extra model options. */
class GenAiG6Test {
    static final SecureString KEY = new SecureString("sk-test", "k");
    static final SecureString SESSION = new SecureString("FwoGZXIvYXdzEXAMPLESESSIONTOKEN", "st");
    static final String CHAT_OK = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":1}}";

    @TempDir Path w;
    final GenAiPackage g = new GenAiPackage();

    ActionContext ctx(List<String> logs) {
        return new ActionContext("run_test", "s1", "default", w, new HashMap<>(), new HashMap<>(), new java.util.concurrent.atomic.AtomicBoolean(),
                () -> List.of(KEY.reveal(), AWS_SECRET.reveal(), AWS_ID.reveal(), SESSION.reveal()), (l, m) -> logs.add(m));
    }

    // ---------- row 1: AWS session token ----------

    @Test void sessionTokenIsSentAndSignedForSageMaker() throws Exception {
        try (AwsFake f = new AwsFake(s -> new Object[]{200, CHAT_OK})) {
            List<String> logs = new ArrayList<>();
            Map<String, Object> r = g.prompt(ctx(logs), "Hi", null, "aws_bedrock_sagemaker", null, "ep", AWS_SECRET, f.url(), 0, 10, false, AWS_ID, "eu-west-1",
                    null, null, null, null, null, null, 1, "text", null, 0, SESSION, null, null, null);
            assertEquals("ok", r.get("text"));
            Seen s = f.seen.get(0);
            assertEquals(SESSION.reveal(), s.headers().get("x-amz-security-token"));
            String auth = s.headers().get("authorization");
            assertTrue(auth.contains("SignedHeaders=accept;content-type;host;x-amz-date;x-amz-security-token,"), auth);
            Instant at = LocalDateTime.parse(s.headers().get("x-amz-date"), java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")).toInstant(ZoneOffset.UTC);
            Map<String, String> signed = new LinkedHashMap<>();
            signed.put("content-type", s.headers().get("content-type"));
            signed.put("accept", s.headers().get("accept"));
            signed.put("host", s.headers().get("host"));
            signed.put("x-amz-security-token", SESSION.reveal());
            assertEquals(AwsSigV4.sign("POST", URI.create(f.url() + "/endpoints/ep/invocations"), signed, s.body(), "AKIDTESTEXAMPLE", AWS_SECRET.reveal(), "eu-west-1", "sagemaker", at).get("Authorization"), auth);
            assertTrue(logs.stream().noneMatch(m -> m.contains(SESSION.reveal())), "session token never logged");
        }
        // without a token nothing changes
        try (AwsFake f = new AwsFake(s -> new Object[]{200, CHAT_OK})) {
            g.prompt(ctx(new ArrayList<>()), "Hi", null, "aws_bedrock_sagemaker", null, "ep", AWS_SECRET, f.url(), 0, 10, false, AWS_ID, "eu-west-1");
            assertNull(f.seen.get(0).headers().get("x-amz-security-token"));
        }
    }

    @Test void sessionTokenFromAModelConnectionForBedrock() throws Exception {
        try (AwsFake f = new AwsFake(s -> new Object[]{200, "{\"output\":{\"message\":{\"content\":[{\"text\":\"hello\"}]}},\"usage\":{\"inputTokens\":2,\"outputTokens\":1}}"})) {
            Files.writeString(w.resolve("model-connections.json"), "{\"connections\":[{\"name\":\"br\",\"provider\":\"aws_bedrock\",\"model\":\"amazon.nova-lite-v1:0\","
                    + "\"base_url\":\"" + f.url() + "\",\"region\":\"us-east-1\",\"api_key\":{\"$credential\":\"aws/br/secret\"},\"aws_access_key_id\":{\"$credential\":\"aws/br/id\"},"
                    + "\"aws_session_token\":{\"$credential\":\"aws/br/token\"}}]}");
            var vault = new com.oneofficeautomation.runner.CredentialVault.InMemory().put("aws", "br", "secret", AWS_SECRET.reveal()).put("aws", "br", "id", "AKIDTEMP").put("aws", "br", "token", "TOKEN-FROM-VAULT");
            var c = PackagesTestSupport.ctx(w, Map.of("vault", vault));
            assertEquals("hello", g.prompt(c, "Hi", null, "openai", "br", null, null, null, 0, 10, false, null, null).get("text"));
            assertEquals("TOKEN-FROM-VAULT", f.seen.get(0).headers().get("x-amz-security-token"));
            assertTrue(f.seen.get(0).headers().get("authorization").contains("x-amz-security-token"));
        }
    }

    // ---------- row 2: any SageMaker endpoint ----------

    @Test void invokeEndpointSendsTheBodyAsItIsWithOptionalHeaders() throws Exception {
        try (Fake f = new Fake()) {
            f.on("POST /endpoints/churn-xgb/invocations", c -> new Object[]{200, "0.87\n", Map.of("Content-Type", "text/csv", "X-Amzn-Invoked-Production-Variant", "variant-b")});
            List<String> logs = new ArrayList<>();
            Map<String, Object> r = g.invokeEndpoint(ctx(logs), "churn-xgb", "1.5,2,0", null, "text/csv", null, null, AWS_SECRET, AWS_ID, SESSION, "eu-west-1", f.url(),
                    "text/csv", "trace=on", null, "req-42", null, "m1.tar.gz", "variant-b", 0);
            assertEquals("0.87\n", r.get("text"));
            assertEquals("variant-b", r.get("invoked_variant"));
            assertEquals("text/csv", r.get("content_type"));
            Call c = f.last("POST", "/endpoints/churn-xgb/invocations");
            assertEquals("1.5,2,0", c.text(), "body sent as it is");
            assertEquals("text/csv", c.contentType());
            assertEquals("text/csv", c.header("Accept"));
            assertEquals("trace=on", c.header("X-Amzn-SageMaker-Custom-Attributes"));
            assertEquals("req-42", c.header("X-Amzn-SageMaker-Inference-Id"));
            assertEquals("m1.tar.gz", c.header("X-Amzn-SageMaker-Target-Model"));
            assertEquals("variant-b", c.header("X-Amzn-SageMaker-Target-Variant"));
            assertNull(c.header("X-Amzn-SageMaker-Enable-Explanations"), "empty settings are not sent");
            assertEquals(SESSION.reveal(), c.header("X-Amz-Security-Token"));
            assertTrue(c.auth().contains("x-amzn-sagemaker-custom-attributes") && c.auth().contains("/eu-west-1/sagemaker/aws4_request"), c.auth());
            assertTrue(logs.stream().noneMatch(m -> m.contains(AWS_SECRET.reveal()) || m.contains(SESSION.reveal())));
            assertTrue(Files.readString(w.resolve("ai-governance.jsonl")).contains("\"purpose\":\"invoke_endpoint\""));

            // a picture from a file: the type comes from the extension; JSON answers are parsed
            f.on("POST /endpoints/vision/invocations", c2 -> new Object[]{200, "{\"label\":\"cat\"}", Map.of("Content-Type", "application/json")});
            Files.write(w.resolve("cat.png"), new byte[]{(byte) 0x89, 'P', 'N', 'G'});
            Map<String, Object> pic = g.invokeEndpoint(ctx(logs), "vision", null, "cat.png", "image/*", null, null, AWS_SECRET, AWS_ID, null, "eu-west-1", f.url(),
                    null, null, null, null, null, null, null, 0);
            assertEquals("cat", ((Map<?, ?>) pic.get("json")).get("label"));
            assertEquals("image/png", f.last("POST", "/endpoints/vision/invocations").contentType());
            assertEquals(4, f.last("POST", "/endpoints/vision/invocations").body().length);

            // a custom content type
            g.invokeEndpoint(ctx(logs), "vision", "{}", null, "custom", "application/x-npy", null, AWS_SECRET, AWS_ID, null, "eu-west-1", f.url(), null, null, null, null, null, null, null, 0);
            assertEquals("application/x-npy", f.last("POST", "/endpoints/vision/invocations").contentType());
        }
    }

    @Test void invokeEndpointInputErrors() {
        var c = ctx(new ArrayList<>());
        assertEquals("AI.BAD_OPTION", assertThrows(ActionException.class, () -> g.invokeEndpoint(c, "bad name!", "{}", null, "application/json", null, null, AWS_SECRET, AWS_ID, null, "eu-west-1", null, null, null, null, null, null, null, null, 0)).code());
        assertEquals("AI.BAD_OPTION", assertThrows(ActionException.class, () -> g.invokeEndpoint(c, "ep", "{}", "a.json", "application/json", null, null, AWS_SECRET, AWS_ID, null, "eu-west-1", null, null, null, null, null, null, null, null, 0)).code());
        assertEquals("AI.BAD_OPTION", assertThrows(ActionException.class, () -> g.invokeEndpoint(c, "ep", "{}", null, "custom", "nonsense", null, AWS_SECRET, AWS_ID, null, "eu-west-1", null, null, null, null, null, null, null, null, 0)).code());
        assertEquals("AI.BAD_OPTION", assertThrows(ActionException.class, () -> g.invokeEndpoint(c, "ep", "{}", null, "image/*", null, null, AWS_SECRET, AWS_ID, null, "eu-west-1", null, null, null, null, null, null, null, null, 0)).code());
        assertEquals("AI.EMPTY_PROMPT", assertThrows(ActionException.class, () -> g.invokeEndpoint(c, "ep", "", null, "application/json", null, null, AWS_SECRET, AWS_ID, null, "eu-west-1", null, null, null, null, null, null, null, null, 0)).code());
        assertEquals("AI.BAD_OPTION", assertThrows(ActionException.class, () -> g.invokeEndpoint(c, "ep", "{}", null, "application/json", null, null, AWS_SECRET, AWS_ID, null, "eu-west-1", null, null, "line\nbreak", null, null, null, null, null, 0)).code());
    }

    // ---------- row 3: Hugging Face raw inference ----------

    @Test void huggingFaceInferenceWithJsonOrAFile() throws Exception {
        try (Fake f = new Fake()) {
            f.on("POST /models/facebook/bart-large-mnli", c -> new Object[]{200, "[{\"label\":\"positive\",\"score\":0.98}]", Map.of("Content-Type", "application/json")});
            f.on("POST /models/acme/detr", c -> new Object[]{200, "[{\"label\":\"cat\"}]", Map.of("Content-Type", "application/json")});
            f.on("POST /models/acme/draw", c -> new Object[]{200, new byte[]{1, 2, 3}, Map.of("Content-Type", "image/png")});
            var c = ctx(new ArrayList<>());
            Map<String, Object> r = g.huggingFaceInference(c, "facebook/bart-large-mnli", "{\"inputs\": \"I love it\"}", null, HF, f.url() + "/models", null, 0);
            assertEquals("positive", ((Map<?, ?>) ((List<?>) r.get("json")).get(0)).get("label"));
            Call call = f.last("POST", "/models/facebook/bart-large-mnli");
            assertEquals("Bearer hf_test_token", call.auth());
            assertEquals("I love it", call.json().get("inputs"));

            Files.write(w.resolve("photo.jpg"), new byte[]{9, 9, 9, 9, 9});
            g.huggingFaceInference(c, "acme/detr", null, "photo.jpg", HF, f.url() + "/models", null, 0);
            Call bin = f.last("POST", "/models/acme/detr");
            assertEquals("image/jpeg", bin.contentType());
            assertArrayEquals(new byte[]{9, 9, 9, 9, 9}, bin.body(), "the file goes as it is");

            var noFile = assertThrows(ActionException.class, () -> g.huggingFaceInference(c, "acme/draw", "{\"inputs\":\"a cat\"}", null, HF, f.url() + "/models", null, 0));
            assertEquals("AI.FILE_ANSWER", noFile.code());
            Map<String, Object> saved = g.huggingFaceInference(c, "acme/draw", "{\"inputs\":\"a cat\"}", null, HF, f.url() + "/models", "out/cat.png", 0);
            assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(w.resolve("out/cat.png")));
            assertEquals(w.resolve("out/cat.png").toString(), saved.get("file"));

            assertEquals("AI.BAD_OPTION", assertThrows(ActionException.class, () -> g.huggingFaceInference(c, "m", "{not json", null, HF, f.url(), null, 0)).code());
            assertEquals("AI.NO_KEY", assertThrows(ActionException.class, () -> g.huggingFaceInference(c, "m", "{}", null, null, null, null, 0)).code());
            assertEquals("https://router.huggingface.co/hf-inference/models", LlmClient.HUGGINGFACE_INFERENCE_URL);
        }
    }

    // ---------- row 4: Azure OpenAI API version ----------

    @Test void azureApiVersionCanBeChanged() throws Exception {
        try (Fake f = new Fake()) {
            f.on("POST /openai/deployments/gpt4o/chat/completions", c -> ok(CHAT_OK));
            var c = ctx(new ArrayList<>());
            g.prompt(c, "Hi", null, "azure_openai", null, "gpt4o", KEY, f.url(), 0, 10, false, null, null);
            assertEquals("api-version=2024-06-01", f.last("POST", "/openai/deployments/gpt4o/chat/completions").query(), "the default stays");
            g.prompt(c, "Hi", null, "azure_openai", null, "gpt4o", KEY, f.url(), 0, 10, false, null, null, null, null, null, null, null, null, 1, "text", null, 0, null, null, null, "2025-01-01-preview");
            assertEquals("api-version=2025-01-01-preview", f.last("POST", "/openai/deployments/gpt4o/chat/completions").query());
            assertEquals("AI.BAD_OPTION", assertThrows(ActionException.class, () -> g.prompt(c, "Hi", null, "azure_openai", null, "gpt4o", KEY, f.url(), 0, 10, false, null, null,
                    null, null, null, null, null, null, 1, "text", null, 0, null, null, null, "x&y=1")).code());
        }
    }

    // ---------- rows 6, 7, 8, 10: picture links and detail, logit bias, end user, examples, provider options ----------

    @SuppressWarnings("unchecked")
    @Test void chatSendsLinksDetailBiasExamplesAndProviderOptions() throws Exception {
        try (Fake f = new Fake()) {
            f.on("POST /v1/chat/completions", c -> ok(CHAT_OK));
            Files.write(w.resolve("meter.png"), new byte[]{1, 2, 3});
            var c = ctx(new ArrayList<>());
            Map<String, Object> examples = new LinkedHashMap<>();
            examples.put("Where is order 1?", "intent: order_status");
            examples.put("Cancel order 2", "intent: cancel");
            g.chat(c, "What does the meter say?", "g6", "Be brief", "openai", null, "gpt-test", KEY, f.url() + "/v1", 0, 50, false, 40, null, null, null, null, 0, null, null,
                    List.of("https://example.com/meter.jpg", "meter.png"), null, null, "text", null, "user-7", Map.of("50256", -100), examples, "low",
                    Map.of("reasoning_effort", "low", "metadata", Map.of("team", "ap")), null);
            Map<String, Object> body = f.last("POST", "/v1/chat/completions").json();
            assertEquals("user-7", body.get("user"));
            assertEquals(-100, ((Number) ((Map<?, ?>) body.get("logit_bias")).get("50256")).intValue());
            assertEquals("low", body.get("reasoning_effort"));
            assertEquals("ap", ((Map<?, ?>) body.get("metadata")).get("team"));
            List<Map<String, Object>> msgs = (List<Map<String, Object>>) body.get("messages");
            assertEquals(List.of("system", "user", "assistant", "user", "assistant", "user"), msgs.stream().map(m -> m.get("role")).toList(), "examples come first, as earlier turns");
            assertEquals("Where is order 1?", msgs.get(1).get("content"));
            assertEquals("intent: cancel", msgs.get(4).get("content"));
            List<Map<String, Object>> parts = (List<Map<String, Object>>) msgs.get(5).get("content");
            Map<String, Object> link = (Map<String, Object>) parts.get(1).get("image_url");
            assertEquals("https://example.com/meter.jpg", link.get("url"), "a link is sent as a link");
            assertEquals("low", link.get("detail"));
            assertTrue(String.valueOf(((Map<?, ?>) parts.get(2).get("image_url")).get("url")).startsWith("data:image/png;base64,"));

            // the examples are not kept in the session: the next message has the first exchange only
            g.chat(c, "And now?", "g6", null, "openai", null, "gpt-test", KEY, f.url() + "/v1", 0, 50, false, 40, null, null, null, null, 0, null, null);
            List<?> next = (List<?>) f.last("POST", "/v1/chat/completions").json().get("messages");
            assertEquals(4, next.size());

            // checks
            assertEquals("AI.BAD_OPTION", assertThrows(ActionException.class, () -> g.chat(c, "x", "g6", null, "openai", null, "m", KEY, f.url() + "/v1", 0, 50, false, 40, null, null, null, null, 0, null, null,
                    null, null, null, "text", null, null, Map.of("abc", 5), null, null, null, null)).code());
            assertEquals("AI.BAD_OPTION", assertThrows(ActionException.class, () -> g.chat(c, "x", "g6", null, "openai", null, "m", KEY, f.url() + "/v1", 0, 50, false, 40, null, null, null, null, 0, null, null,
                    null, null, null, "text", null, null, Map.of("1", 101), null, null, null, null)).code());
            assertEquals("AI.BAD_OPTION", assertThrows(ActionException.class, () -> g.chat(c, "x", "g6", null, "openai", null, "m", KEY, f.url() + "/v1", 0, 50, false, 40, null, null, null, null, 0, null, null,
                    null, null, null, "text", null, null, null, null, "huge", null, null)).code());
            assertEquals("AI.BAD_IMAGE", assertThrows(ActionException.class, () -> g.chat(c, "x", "g6", null, "openai", null, "m", KEY, f.url() + "/v1", 0, 50, false, 40, null, null, null, null, 0, null, null,
                    List.of("https://"), null, null, "text")).code());
        }
    }

    @Test void promptSendsLogitBiasAndProviderOptionsToAnthropic() throws Exception {
        try (Fake f = new Fake()) {
            f.on("POST /v1/messages", c -> ok("{\"content\":[{\"type\":\"text\",\"text\":\"hi\"}],\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}"));
            g.prompt(ctx(new ArrayList<>()), "Hi", null, "anthropic", null, "claude-test", KEY, f.url(), 0, 10, false, null, null, null, null, null, null, null, null, 1, "text", null, 0,
                    null, null, Map.of("thinking", Map.of("type", "enabled", "budget_tokens", 1024)), null);
            Map<String, Object> body = f.last("POST", "/v1/messages").json();
            assertEquals("enabled", ((Map<?, ?>) body.get("thinking")).get("type"));
            assertEquals("claude-test", body.get("model"), "the action's own settings stay");
        }
    }

    // ---------- row 5: video for Gemini ----------

    @SuppressWarnings("unchecked")
    @Test void videosGoToGeminiInlineAndProviderOptionsMerge() throws Exception {
        try (Fake f = new Fake()) {
            f.on("POST /v1/projects/p/locations/l/publishers/google/models/.*", c -> ok("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"A cat walks\"}]}}],\"usageMetadata\":{\"promptTokenCount\":9,\"candidatesTokenCount\":3}}"));
            Files.write(w.resolve("clip.mp4"), new byte[]{0, 0, 0, 24, 'f', 't', 'y', 'p'});
            var c = ctx(new ArrayList<>());
            String base = f.url() + "/v1/projects/p/locations/l";
            Map<String, Object> r = g.promptWithImages(c, "What happens?", null, null, "google_vertex", null, "gemini-2.0-flash", new SecureString("ya29.token", "t"), base, false, null, null,
                    0, 100, "text", 0, null, null, null, null, null, null, null, List.of("clip.mp4"), null, Map.of("generationConfig", Map.of("candidateCount", 1)), null);
            assertEquals("A cat walks", r.get("text"));
            Map<String, Object> body = f.last("POST", "/v1/projects/p/locations/l/publishers/google/models/.*").json();
            List<Map<String, Object>> parts = (List<Map<String, Object>>) ((Map<String, Object>) ((List<?>) body.get("contents")).get(0)).get("parts");
            Map<String, Object> inline = (Map<String, Object>) parts.get(1).get("inlineData");
            assertEquals("video/mp4", inline.get("mimeType"));
            assertEquals(Base64.getEncoder().encodeToString(new byte[]{0, 0, 0, 24, 'f', 't', 'y', 'p'}), inline.get("data"));
            Map<String, Object> gen = (Map<String, Object>) body.get("generationConfig");
            assertEquals(1, ((Number) gen.get("candidateCount")).intValue(), "merged into the settings");
            assertEquals(100, ((Number) gen.get("maxOutputTokens")).intValue(), "the step's own settings stay");

            // a picture link goes to Gemini as file data
            g.promptWithImages(c, "What is it?", List.of("https://example.com/x.webp"), null, "google_vertex", null, "gemini-2.0-flash", new SecureString("ya29.token", "t"), base, false, null, null,
                    0, 100, "text", 0);
            Map<String, Object> b2 = f.last("POST", "/v1/projects/p/locations/l/publishers/google/models/.*").json();
            Map<String, Object> fd = (Map<String, Object>) ((List<Map<String, Object>>) ((Map<String, Object>) ((List<?>) b2.get("contents")).get(0)).get("parts")).get(1).get("fileData");
            assertEquals("image/webp", fd.get("mimeType"));
            assertEquals("https://example.com/x.webp", fd.get("fileUri"));

            // videos are for Gemini only, and must be video files
            assertEquals("AI.NOT_SUPPORTED", assertThrows(ActionException.class, () -> g.promptWithImages(c, "x", null, null, "openai", null, "gpt-test", KEY, f.url(), false, null, null,
                    0, 100, "text", 0, null, null, null, null, null, null, null, List.of("clip.mp4"), null, null, null)).code());
            Files.write(w.resolve("notes.txt"), new byte[]{1});
            assertEquals("AI.BAD_VIDEO", assertThrows(ActionException.class, () -> g.promptWithImages(c, "x", null, null, "google_vertex", null, "gemini", new SecureString("t", "t"), base, false, null, null,
                    0, 100, "text", 0, null, null, null, null, null, null, null, List.of("notes.txt"), null, null, null)).code());
            assertEquals("AI.NO_IMAGES", assertThrows(ActionException.class, () -> g.promptWithImages(c, "x", null, null, "google_vertex", null, "gemini", new SecureString("t", "t"), base, false, null, null,
                    0, 100, "text", 0)).code());
        }
    }

    // ---------- row 9: picture settings ----------

    @SuppressWarnings("unchecked")
    @Test void generateImageSendsStrengthStepsAndSeedWhereTaken() throws Exception {
        String png = Base64.getEncoder().encodeToString(new byte[]{(byte) 0x89, 'P', 'N', 'G'});
        try (Fake f = new Fake()) {
            f.on("POST /model/amazon.*/invoke", c -> ok("{\"images\":[\"" + png + "\"]}"));
            f.on("POST /model/stability.stable-diffusion-xl.*/invoke", c -> ok("{\"artifacts\":[{\"base64\":\"" + png + "\",\"finishReason\":\"SUCCESS\"}]}"));
            var c = ctx(new ArrayList<>());
            g.generateImage(c, "A lighthouse", "pics", "titan", 1, "1024x1024", "aws_bedrock", null, "amazon.titan-image-generator-v2:0", AWS_SECRET, f.url(), false, AWS_ID, "us-east-1", 0,
                    SESSION, 7.5, null, 42);
            Map<String, Object> cfg = (Map<String, Object>) f.last("POST", "/model/amazon.*/invoke").json().get("imageGenerationConfig");
            assertEquals(7.5, ((Number) cfg.get("cfgScale")).doubleValue());
            assertEquals(42, ((Number) cfg.get("seed")).intValue());
            assertEquals(SESSION.reveal(), f.last("POST", "/model/amazon.*/invoke").header("X-Amz-Security-Token"));
            assertTrue(Files.exists(w.resolve("pics/titan_1.png")));

            Map<String, Object> r = g.generateImage(c, "A lighthouse", "pics", "sdxl", 1, "1024x1024", "aws_bedrock", null, "stability.stable-diffusion-xl-v1", AWS_SECRET, f.url(), false, AWS_ID, "us-east-1", 0,
                    null, 12.0, 40, 7);
            Map<String, Object> body = f.last("POST", "/model/stability.stable-diffusion-xl.*/invoke").json();
            assertEquals(12, ((Number) body.get("cfg_scale")).intValue());
            assertEquals(40, ((Number) body.get("steps")).intValue());
            assertEquals(7, ((Number) body.get("seed")).intValue());
            assertEquals("A lighthouse", ((Map<?, ?>) ((List<?>) body.get("text_prompts")).get(0)).get("text"));
            assertArrayEquals(new byte[]{(byte) 0x89, 'P', 'N', 'G'}, Files.readAllBytes(Path.of(String.valueOf(r.get("file")))));

            assertEquals("AI.BAD_OPTION", assertThrows(ActionException.class, () -> g.generateImage(c, "x", "pics", "p", 1, "1024x1024", "aws_bedrock", null, "m", AWS_SECRET, f.url(), false, AWS_ID, "us-east-1", 0,
                    null, null, 5, null)).code());
        }
    }

    @Test void newActionsAppearInTheManifestWithTheirPanels() {
        Map<String, Object> m = PackageIntrospector.manifest(GenAiPackage.class);
        List<String> ids = ((List<?>) m.get("actions")).stream().map(a -> String.valueOf(((Map<?, ?>) a).get("id"))).toList();
        assertTrue(ids.containsAll(List.of("invoke_endpoint", "huggingface_inference", "chat", "prompt", "prompt_with_images", "generate_image")), ids.toString());
        PackageIntrospector.inspect(GenAiPackage.class);   // every panel places every input once
    }

    static String text(byte[] b) { return new String(b, StandardCharsets.UTF_8); }
}
