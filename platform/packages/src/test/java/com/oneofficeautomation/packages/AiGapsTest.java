package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;

import static com.oneofficeautomation.packages.ConnectorsTest.*;
import static com.oneofficeautomation.packages.PackagesTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Amazon Bedrock and Google Vertex AI, pictures in prompts, picture generation, language actions and the connection probe. */
class AiGapsTest {
    static final SecureString KEY = new SecureString("sk-test-key", "k");
    static final SecureString AWS = new SecureString("AKIDTESTEXAMPLE:wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY", "aws");
    /** A 1x1 PNG. */
    static final byte[] PNG = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=");

    static ActionContext ctx(Path w, List<String> logs) {
        return new ActionContext("run_test", "s1", "default", w, new HashMap<>(), new HashMap<>(), new java.util.concurrent.atomic.AtomicBoolean(),
                () -> List.of(KEY.reveal(), AWS.reveal()), (l, m) -> logs.add(m));
    }

    static final String CHAT_OK = "{\"choices\":[{\"message\":{\"content\":\"%s\"}}],\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":3}}";

    @SuppressWarnings("unchecked")
    @Test void bedrockConverseIsSignedAndCarriesPicturesAndTools() throws Exception {
        try (Fake f = new Fake()) {
            f.on("POST /model/.*/converse", c -> ok("{\"output\":{\"message\":{\"role\":\"assistant\",\"content\":[{\"text\":\"A red invoice stamp\"}]}},\"usage\":{\"inputTokens\":21,\"outputTokens\":5}}"));
            Path w = tmp();
            Files.write(w.resolve("scan.png"), PNG);
            List<String> logs = new ArrayList<>();
            Map<String, Object> r = new GenAiPackage().promptWithImages(ctx(w, logs), "What does the stamp say? Mail ann@example.com", List.of("scan.png"), "Be brief",
                    "aws_bedrock", null, "amazon.nova-pro-v1:0", AWS, f.url(), true, null, "eu-central-1", 0, 300, "text", 0);
            assertEquals("A red invoice stamp", r.get("text"));
            assertEquals(BigDecimal.valueOf(21), r.get("input_tokens"));
            Call c = f.last("POST", "/model/.*/converse");
            assertEquals("/model/amazon.nova-pro-v1%3A0/converse", c.path());
            String auth = c.header("Authorization");
            assertTrue(auth.startsWith("AWS4-HMAC-SHA256 Credential=AKIDTESTEXAMPLE/"), auth);
            assertTrue(auth.contains("/eu-central-1/bedrock/aws4_request"), auth);
            // the signature matches what the server received
            Map<String, String> signed = new LinkedHashMap<>();
            signed.put("content-type", c.header("Content-Type")); signed.put("accept", c.header("Accept")); signed.put("host", c.header("Host"));
            Instant at = LocalDateTime.parse(c.header("X-Amz-Date"), java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")).toInstant(ZoneOffset.UTC);
            assertEquals(AwsSigV4.sign("POST", URI.create(f.url() + c.path()), signed, c.body(), "AKIDTESTEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY", "eu-central-1", "bedrock", at).get("Authorization"), auth);
            Map<String, Object> body = c.json();
            assertEquals(List.of(Map.of("text", "Be brief")), body.get("system"));
            List<Object> content = (List<Object>) ((Map<String, Object>) ((List<Object>) body.get("messages")).get(0)).get("content");
            assertFalse(String.valueOf(content.get(0)).contains("ann@example.com"), "text parts are masked");
            Map<String, Object> image = (Map<String, Object>) ((Map<String, Object>) content.get(1)).get("image");
            assertEquals("png", image.get("format"));
            assertEquals(Base64.getEncoder().encodeToString(PNG), ((Map<String, Object>) image.get("source")).get("bytes"));
            assertEquals(300, ((BigDecimal) ((Map<String, Object>) body.get("inferenceConfig")).get("maxTokens")).intValue());
            assertFalse(c.text().contains("wJalrXUtnFEMI"), "secret key never sent");

            // tool use round trip through the AI agent's client
            f.on("POST /model/.*/converse", x -> ok("{\"output\":{\"message\":{\"content\":[{\"toolUse\":{\"toolUseId\":\"t1\",\"name\":\"lookup\",\"input\":{\"id\":7}}}]}},\"usage\":{\"inputTokens\":3,\"outputTokens\":2}}"));
            LlmClient.Config cfg = GenAiPackage.cfg("aws_bedrock", "m", AWS, f.url(), 0, 100, false, null, "eu-central-1");
            LlmClient.Reply rep = LlmClient.chat(ctx(w, logs), cfg, List.of(Map.of("role", "user", "content", "find 7")),
                    List.of(Map.of("name", "lookup", "description", "Find", "input_schema", Map.of("type", "object"))), false, "t");
            assertEquals("lookup", rep.toolCalls().get(0).name());
            assertEquals(BigDecimal.valueOf(7), rep.toolCalls().get(0).args().get("id"));
            Map<String, Object> tb = f.last("POST", "/model/.*/converse").json();
            assertEquals("lookup", ((Map<String, Object>) ((Map<String, Object>) ((List<Object>) ((Map<String, Object>) tb.get("toolConfig")).get("tools")).get(0)).get("toolSpec")).get("name"));
            // the tool's result goes back as a toolResult block
            List<Map<String, Object>> conv = List.of(Map.of("role", "user", "content", "find 7"),
                    Map.of("role", "assistant", "content", "", "tool_calls", List.of(Map.of("id", "t1", "name", "lookup", "args", Map.of("id", 7)))),
                    Map.of("role", "tool", "tool_call_id", "t1", "content", "{\"name\":\"Ann\"}"));
            LlmClient.chat(ctx(w, logs), cfg, conv, null, false, "t");
            List<Object> msgs = (List<Object>) f.last("POST", "/model/.*/converse").json().get("messages");
            assertEquals(3, msgs.size());
            assertTrue(String.valueOf(msgs.get(2)).contains("toolResult"));
        }
    }

    @SuppressWarnings("unchecked")
    @Test void vertexWithServiceAccountKeyAndAccessToken() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(kp.getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----\n";
        try (Fake f = new Fake()) {
            f.on("POST /token", c -> ok("{\"access_token\":\"ya29.test-token\",\"expires_in\":3599,\"token_type\":\"Bearer\"}"));
            f.on("POST /v1/projects/p1/locations/europe-west4/publishers/google/models/gemini-2.5-flash:generateContent", c -> ok(
                    "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"{\\\"total\\\": 12}\"}]}}],\"usageMetadata\":{\"promptTokenCount\":14,\"candidatesTokenCount\":6}}"));
            String sa = JsonLite.write(Map.of("type", "service_account", "client_email", "bot@p1.iam.gserviceaccount.com", "private_key", pem, "token_uri", f.url() + "/token"));
            SecureString key = new SecureString(sa, "sa");
            Path w = tmp();
            Files.write(w.resolve("receipt.jpg"), PNG);
            String base = f.url() + "/v1/projects/p1/locations/europe-west4";
            Map<String, Object> r = new GenAiPackage().promptWithImages(ctx(w, new ArrayList<>()), "Total?", List.of("receipt.jpg"), null,
                    "google_vertex", null, "gemini-2.5-flash", key, base, false, null, null, 0, 200, "json", 0);
            assertEquals(Map.of("total", BigDecimal.valueOf(12)), r.get("json"));
            Call tok = f.last("POST", "/token");
            String form = java.net.URLDecoder.decode(tok.text(), StandardCharsets.UTF_8);
            assertTrue(form.startsWith("grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion="));
            String jwt = form.substring(form.indexOf("assertion=") + 10);
            String[] parts = jwt.split("\\.");
            Signature v = Signature.getInstance("SHA256withRSA");
            v.initVerify(kp.getPublic());
            v.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
            assertTrue(v.verify(Base64.getUrlDecoder().decode(parts[2])), "the JWT is signed with the service-account key");
            Map<String, Object> claims = (Map<String, Object>) JsonLite.parse(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
            assertEquals("bot@p1.iam.gserviceaccount.com", claims.get("iss"));
            assertEquals(f.url() + "/token", claims.get("aud"));
            Call gen = f.last("POST", ".*:generateContent");
            assertEquals("Bearer ya29.test-token", gen.auth());
            Map<String, Object> body = gen.json();
            List<Object> ps = (List<Object>) ((Map<String, Object>) ((List<Object>) body.get("contents")).get(0)).get("parts");
            assertEquals("image/jpeg", ((Map<String, Object>) ((Map<String, Object>) ps.get(1)).get("inlineData")).get("mimeType"));
            assertEquals("application/json", ((Map<String, Object>) body.get("generationConfig")).get("responseMimeType"));
            // the token is kept: a second call does not ask again
            int tokens = (int) f.calls.stream().filter(c -> c.path().equals("/token")).count();
            new GenAiPackage().prompt(ctx(w, new ArrayList<>()), "Again", null, "google_vertex", null, "gemini-2.5-flash", key, base, 0, 50, false, null, null);
            assertEquals(tokens, f.calls.stream().filter(c -> c.path().equals("/token")).count());
            // an access token is used as it is
            new GenAiPackage().prompt(ctx(w, new ArrayList<>()), "Hi", null, "google_vertex", null, "gemini-2.5-flash", new SecureString("ya29.direct", "t"), base, 0, 50, false, null, null);
            assertEquals("Bearer ya29.direct", f.last("POST", ".*:generateContent").auth());
            // without the location's address there is a clear error
            assertEquals("AI.NO_ENDPOINT", assertThrows(ActionException.class, () -> new GenAiPackage().prompt(ctx(w, new ArrayList<>()), "Hi", null, "google_vertex", null, "gemini", key, null, 0, 50, false, null, null)).code());
        }
    }

    @SuppressWarnings("unchecked")
    @Test void picturesInChatsForOpenAiAndAnthropic() throws Exception {
        try (Fake f = new Fake()) {
            f.on("POST /v1/chat/completions", c -> ok(CHAT_OK.formatted("A cat")));
            f.on("POST /v1/messages", c -> ok("{\"content\":[{\"type\":\"text\",\"text\":\"A dog\"}],\"usage\":{\"input_tokens\":4,\"output_tokens\":2}}"));
            Path w = tmp();
            Files.write(w.resolve("pet.png"), PNG);
            Map<String, Object> r = new GenAiPackage().chat(ctx(w, new ArrayList<>()), "What animal?", "pets", null, "openai", null, "gpt-4o", KEY, f.url() + "/v1", 0, 100, true, 40,
                    null, null, null, null, 0, null, null, List.of("pet.png"));
            assertEquals("A cat", r.get("text"));
            List<Object> content = (List<Object>) ((Map<String, Object>) ((List<Object>) f.last("POST", "/v1/chat/completions").json().get("messages")).get(0)).get("content");
            assertEquals("text", ((Map<String, Object>) content.get(0)).get("type"));
            assertEquals("data:image/png;base64," + Base64.getEncoder().encodeToString(PNG), ((Map<String, Object>) ((Map<String, Object>) content.get(1)).get("image_url")).get("url"));
            // the picture stays in the conversation for the next message
            new GenAiPackage().chat(ctx(w, new ArrayList<>()), "And its colour?", "pets", null, "openai", null, "gpt-4o", KEY, f.url() + "/v1", 0, 100, true, 40, null, null, null, null, 0, null, null);
            new GenAiPackage().promptWithImages(ctx(w, new ArrayList<>()), "Which animal?", List.of("pet.png"), null, "anthropic", null, "claude-sonnet-4-5", KEY, f.url(), false, null, null, 0, 100, "text", 0);
            List<Object> blocks = (List<Object>) ((Map<String, Object>) ((List<Object>) f.last("POST", "/v1/messages").json().get("messages")).get(0)).get("content");
            Map<String, Object> src = (Map<String, Object>) ((Map<String, Object>) blocks.get(1)).get("source");
            assertEquals("base64", src.get("type"));
            assertEquals("image/png", src.get("media_type"));
            // only picture files are accepted
            Files.writeString(w.resolve("notes.txt"), "x");
            assertEquals("AI.BAD_IMAGE", assertThrows(ActionException.class, () -> new GenAiPackage().promptWithImages(ctx(w, new ArrayList<>()), "?", List.of("notes.txt"), null,
                    "openai", null, "gpt-4o", KEY, f.url() + "/v1", false, null, null, 0, 100, "text", 0)).code());
        }
    }

    @SuppressWarnings("unchecked")
    @Test void pictureGenerationSavesPngFiles() throws Exception {
        String b64 = Base64.getEncoder().encodeToString(PNG);
        try (Fake f = new Fake()) {
            f.on("POST /v1/images/generations", c -> ok("{\"data\":[{\"b64_json\":\"" + b64 + "\"},{\"b64_json\":\"" + b64 + "\"}]}"));
            f.on("POST /vx/publishers/google/models/imagen-4.0-generate-001:predict", c -> ok("{\"predictions\":[{\"bytesBase64Encoded\":\"" + b64 + "\",\"mimeType\":\"image/png\"}]}"));
            f.on("POST /model/.*/invoke", c -> ok("{\"images\":[\"" + b64 + "\"]}"));
            Path w = tmp();
            Map<String, Object> r = new GenAiPackage().generateImage(ctx(w, new ArrayList<>()), "A blue logo for ann@example.com", "out", "logo", 2, "1024x1024",
                    "openai", null, "gpt-image-1", KEY, f.url() + "/v1", true, null, null, 0);
            List<Object> files = (List<Object>) r.get("files");
            assertEquals(2, files.size());
            assertArrayEquals(PNG, Files.readAllBytes(Path.of(String.valueOf(files.get(1)))));
            assertTrue(String.valueOf(r.get("file")).endsWith("logo_1.png"));
            Map<String, Object> sent = f.last("POST", "/v1/images/generations").json();
            assertEquals("1024x1024", sent.get("size"));
            assertFalse(String.valueOf(sent.get("prompt")).contains("ann@example.com"));
            assertNull(sent.get("response_format"), "gpt-image models always answer in base64");
            assertTrue(Files.readString(w.resolve("ai-governance.jsonl")).contains("\"purpose\":\"generate_image\""));

            new GenAiPackage().generateImage(ctx(w, new ArrayList<>()), "A tree", "out", "tree", 1, "1792x1024", "google_vertex", null, "imagen-4.0-generate-001",
                    new SecureString("ya29.x", "t"), f.url() + "/vx", false, null, null, 0);
            assertEquals("16:9", ((Map<String, Object>) f.last("POST", ".*:predict").json().get("parameters")).get("aspectRatio"));

            new GenAiPackage().generateImage(ctx(w, new ArrayList<>()), "A boat", "out", "boat", 1, "512x512", "aws_bedrock", null, "amazon.nova-canvas-v1:0",
                    AWS, f.url(), false, null, "us-east-1", 0);
            Map<String, Object> inv = f.last("POST", "/model/.*/invoke").json();
            assertEquals("TEXT_IMAGE", inv.get("taskType"));
            assertTrue(Files.exists(w.resolve("out/boat_1.png")));

            assertEquals("AI.NOT_SUPPORTED", assertThrows(ActionException.class, () -> new GenAiPackage().generateImage(ctx(w, new ArrayList<>()), "x", "out", "x", 1, "512x512",
                    "anthropic", null, "claude", KEY, f.url(), false, null, null, 0)).code());
            assertEquals("AI.BAD_OPTION", assertThrows(ActionException.class, () -> new GenAiPackage().generateImage(ctx(w, new ArrayList<>()), "x", "out", "x", 1, "big",
                    "openai", null, "m", KEY, f.url() + "/v1", false, null, null, 0)).code());
        }
    }

    @SuppressWarnings("unchecked")
    @Test void languageKeyPhrasesAndEntities() throws Exception {
        try (Fake f = new Fake()) {
            Path w = tmp();
            f.on("POST /v1/chat/completions", c -> {
                String t = c.text();
                if (t.contains("Which language")) return ok(CHAT_OK.formatted("{\\\"language\\\": \\\"German\\\", \\\"code\\\": \\\"DE\\\", \\\"confidence\\\": 1.4}"));
                if (t.contains("key phrases")) return ok(CHAT_OK.formatted("{\\\"phrases\\\": [\\\"late delivery\\\", \\\"refund\\\", \\\"order 4711\\\"]}"));
                return ok(CHAT_OK.formatted("{\\\"entities\\\": [{\\\"text\\\": \\\"Ann Lee\\\", \\\"type\\\": \\\"person\\\", \\\"score\\\": 0.9}, {\\\"text\\\": \\\"Contoso\\\", \\\"type\\\": \\\"ORGANIZATION\\\"}, {\\\"text\\\": \\\"blue\\\", \\\"type\\\": \\\"COLOUR\\\"}]}"));
            });
            Map<String, Object> lang = new GenAiPackage().detectLanguage(ctx(w, new ArrayList<>()), "Guten Tag", "openai", null, "gpt-4o-mini", KEY, f.url() + "/v1", true, null, null);
            assertEquals("German", lang.get("language"));
            assertEquals("de", lang.get("code"));
            assertEquals(BigDecimal.ONE, lang.get("confidence"), "kept between 0 and 1");
            Map<String, Object> kp = new GenAiPackage().keyPhrases(ctx(w, new ArrayList<>()), "My order 4711 came late; I want a refund.", 2, "openai", null, "m", KEY, f.url() + "/v1", true, null, null);
            assertEquals(List.of("late delivery", "refund"), kp.get("phrases"));
            Map<String, Object> en = new GenAiPackage().entities(ctx(w, new ArrayList<>()), "Ann Lee of Contoso", null, "openai", null, "m", KEY, f.url() + "/v1", false, null, null);
            Map<String, Object> byType = (Map<String, Object>) en.get("by_type");
            assertEquals(List.of("Ann Lee"), byType.get("PERSON"));
            assertEquals(List.of("Contoso"), byType.get("ORGANIZATION"));
            assertEquals(List.of("blue"), byType.get("OTHER"), "unknown types become OTHER");
            // only some types
            Map<String, Object> people = new GenAiPackage().entities(ctx(w, new ArrayList<>()), "Ann Lee of Contoso", List.of("person"), "openai", null, "m", KEY, f.url() + "/v1", false, null, null);
            assertEquals(Set.of("PERSON"), ((Map<String, Object>) people.get("by_type")).keySet());
            assertEquals("AI.BAD_OPTION", assertThrows(ActionException.class, () -> new GenAiPackage().entities(ctx(w, new ArrayList<>()), "x", List.of("ANIMAL"), "openai", null, "m", KEY, f.url() + "/v1", false, null, null)).code());
        }
    }

    @Test void connectionProbe() throws Exception {
        try (Fake f = new Fake()) {
            f.on("POST /v1/chat/completions", c -> c.auth() != null && c.auth().equals("Bearer good") ? ok(CHAT_OK.formatted("OK")) : new Object[]{401, "{\"error\":{\"message\":\"Invalid key good-not\"}}"});
            Map<String, Object> ok = ModelProbe.test("openai", "gpt-4o-mini", f.url() + "/v1", "good");
            assertEquals(true, ok.get("ok"));
            assertEquals("OK", ok.get("answer"));
            Map<String, Object> bad = ModelProbe.test("openai", "gpt-4o-mini", f.url() + "/v1", "good-not");
            assertEquals(false, bad.get("ok"));
            assertFalse(String.valueOf(bad.get("message")).contains("good-not"), "the key is never echoed");
            assertEquals(false, ModelProbe.test("nobody", "m", null, null).get("ok"));
        }
    }
}
