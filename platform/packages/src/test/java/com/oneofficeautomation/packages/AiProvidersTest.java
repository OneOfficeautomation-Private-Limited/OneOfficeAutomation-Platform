package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.CredentialVault;
import com.oneofficeautomation.sdk.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static com.oneofficeautomation.packages.ConnectorsTest.*;
import static com.oneofficeautomation.packages.PackagesTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Hugging Face and Amazon SageMaker providers, and AWS Signature V4. */
class AiProvidersTest {
    static final SecureString HF = new SecureString("hf_test_token", "hf");
    static final SecureString AWS_ID = new SecureString("AKIDTESTEXAMPLE", "id");
    static final SecureString AWS_SECRET = new SecureString("wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY", "secret");

    // ---------- SigV4 against AWS's published examples ----------

    @Test void sigV4MatchesAwsTestSuiteGetVanilla() {
        Map<String, String> h = AwsSigV4.sign("GET", URI.create("https://example.amazonaws.com/"), Map.of(), new byte[0],
                "AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY", "us-east-1", "service", Instant.parse("2015-08-30T12:36:00Z"));
        assertEquals("20150830T123600Z", h.get("x-amz-date"));
        assertEquals("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request, SignedHeaders=host;x-amz-date, "
                + "Signature=5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31", h.get("Authorization"));
    }

    @Test void sigV4SigningKeyMatchesAwsDocumentation() {
        assertEquals("f4780e2d9f65fa895f9c67b32ce1baf0b0d8a43505a000a1a9e090d414db404d",
                AwsSigV4.hex(AwsSigV4.signingKey("wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY", "20120215", "us-east-1", "iam")));
    }

    @Test void sigV4Canonicalisation() {
        assertEquals("/endpoints/my%2520model/invocations", AwsSigV4.canonicalPath("/endpoints/my%20model/invocations"), "non-S3 paths are encoded twice");
        assertEquals("/", AwsSigV4.canonicalPath(""));
        assertEquals("a=1&b=x%20y&b=z", AwsSigV4.canonicalQuery("b=z&a=1&b=x+y"));
        assertEquals("127.0.0.1:8080", AwsSigV4.hostHeader(URI.create("http://127.0.0.1:8080/x")));
        assertEquals("runtime.sagemaker.eu-west-1.amazonaws.com", AwsSigV4.hostHeader(URI.create("https://runtime.sagemaker.eu-west-1.amazonaws.com:443/x")));
    }

    // ---------- Hugging Face ----------

    @SuppressWarnings("unchecked")
    @Test void huggingFaceChatCompletions() throws Exception {
        try (Fake f = new Fake()) {
            f.on("POST /v1/chat/completions", c -> ok("{\"choices\":[{\"message\":{\"content\":\"Bonjour\"}}],\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":2}}"));
            Path w = tmp();
            List<String> logs = new ArrayList<>();
            var ctx = new ActionContext("run_test", "s1", "default", w, new HashMap<>(), new HashMap<>(), new java.util.concurrent.atomic.AtomicBoolean(),
                    () -> List.of(HF.reveal()), (l, m) -> logs.add(m));
            Map<String, Object> r = new GenAiPackage().translate(ctx, "Hello", "French", "huggingface", null, "meta-llama/Llama-3.1-8B-Instruct", HF, f.url() + "/v1", true, null, null);
            assertEquals("Bonjour", r.get("text"));
            Call call = f.last("POST", "/v1/chat/completions");
            assertEquals("Bearer hf_test_token", call.auth());
            Map<String, Object> body = (Map<String, Object>) JsonLite.parse(call.text());
            assertEquals("meta-llama/Llama-3.1-8B-Instruct", body.get("model"));
            assertTrue(Files.readString(w.resolve("ai-governance.jsonl")).contains("\"provider\":\"huggingface\""));
            assertTrue(logs.stream().noneMatch(m -> m.contains("hf_test_token")), "token never logged");

            // a self-hosted TGI server needs no token
            Map<String, Object> tgi = new GenAiPackage().prompt(ctx, "Hi", null, "huggingface", null, "tgi", null, f.url() + "/v1", 0, 50, false, null, null);
            assertEquals("Bonjour", tgi.get("text"));
            assertNull(f.last("POST", "/v1/chat/completions").auth());

            // the hosted router does
            var noKey = assertThrows(ActionException.class, () -> new GenAiPackage().prompt(ctx, "Hi", null, "huggingface", null, "m", null, null, 0, 50, false, null, null));
            assertEquals("AI.NO_KEY", noKey.code());
            assertEquals("https://router.huggingface.co/v1", LlmClient.HUGGINGFACE_URL);
        }
    }

    // ---------- SageMaker ----------

    record Seen(String method, String path, Map<String, String> headers, byte[] body) {}

    /** Minimal server that keeps every request header, which the shared fake does not. */
    static final class AwsFake implements AutoCloseable {
        final HttpServer server;
        final List<Seen> seen = new CopyOnWriteArrayList<>();
        AwsFake(Function<Seen, Object[]> handler) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", ex -> {
                Map<String, String> h = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                ex.getRequestHeaders().forEach((k, v) -> h.put(k, v.get(0)));
                Seen s = new Seen(ex.getRequestMethod(), ex.getRequestURI().getRawPath(), h, ex.getRequestBody().readAllBytes());
                seen.add(s);
                Object[] r = handler.apply(s);
                byte[] b = String.valueOf(r[1]).getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders((Integer) r[0], b.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(b); }
            });
            server.start();
        }
        String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        @Override public void close() { server.stop(0); }
    }

    /** Recomputes the signature from what the server actually received. */
    static void assertValidSignature(Seen s, String url, String accessKeyId, String secret, String region) {
        Instant at = LocalDateTime.parse(s.headers().get("x-amz-date"), java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")).toInstant(ZoneOffset.UTC);
        Map<String, String> signed = new LinkedHashMap<>();
        signed.put("content-type", s.headers().get("content-type"));
        signed.put("accept", s.headers().get("accept"));
        signed.put("host", s.headers().get("host"));
        String expected = AwsSigV4.sign("POST", URI.create(url), signed, s.body(), accessKeyId, secret, region, "sagemaker", at).get("Authorization");
        assertEquals(expected, s.headers().get("authorization"));
    }

    @SuppressWarnings("unchecked")
    @Test void sageMakerInvokeEndpointIsSigned() throws Exception {
        try (AwsFake f = new AwsFake(s -> s.path().equals("/endpoints/support-llm/invocations")
                ? new Object[]{200, "{\"choices\":[{\"message\":{\"content\":\"Reset sent to <EMAIL_x>\"}}],\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":4}}"}
                : new Object[]{404, "{\"message\":\"Endpoint not found\"}"})) {
            Path w = tmp();
            List<String> logs = new ArrayList<>();
            var ctx = new ActionContext("run_test", "s1", "default", w, new HashMap<>(), new HashMap<>(), new java.util.concurrent.atomic.AtomicBoolean(),
                    () -> List.of(AWS_ID.reveal(), AWS_SECRET.reveal()), (l, m) -> logs.add(m));
            Map<String, Object> r = new GenAiPackage().prompt(ctx, "Reset the password for ann@example.com", "Be brief", "aws_bedrock_sagemaker", null, "support-llm",
                    AWS_SECRET, f.url(), 0.2, 200, true, AWS_ID, "eu-west-1");
            assertTrue(String.valueOf(r.get("text")).startsWith("Reset sent to"));
            Seen s = f.seen.get(0);
            String auth = s.headers().get("authorization");
            assertTrue(auth.startsWith("AWS4-HMAC-SHA256 Credential=AKIDTESTEXAMPLE/" + s.headers().get("x-amz-date").substring(0, 8) + "/eu-west-1/sagemaker/aws4_request, "
                    + "SignedHeaders=accept;content-type;host;x-amz-date, Signature="), auth);
            assertValidSignature(s, f.url() + "/endpoints/support-llm/invocations", "AKIDTESTEXAMPLE", AWS_SECRET.reveal(), "eu-west-1");
            String sent = new String(s.body(), StandardCharsets.UTF_8);
            assertFalse(sent.contains("ann@example.com"), "PII masked before it leaves");
            assertFalse(sent.contains(AWS_SECRET.reveal()) || auth.contains(AWS_SECRET.reveal()), "secret key never sent");
            Map<String, Object> body = (Map<String, Object>) JsonLite.parse(sent);
            assertNull(body.get("model"), "the endpoint name is in the URL, not the body");
            assertEquals(2, ((List<?>) body.get("messages")).size());
            assertTrue(logs.stream().noneMatch(m -> m.contains(AWS_SECRET.reveal()) || m.contains(AWS_ID.reveal())), "credentials never logged: " + logs);

            // unknown endpoint: the service's own message and a typed error
            var nf = assertThrows(ActionException.class, () -> new GenAiPackage().prompt(ctx, "x", null, "aws_bedrock_sagemaker", null, "other",
                    AWS_SECRET, f.url(), 0, 10, false, AWS_ID, "eu-west-1"));
            assertEquals("AI.NOT_FOUND", nf.code());
            assertTrue(nf.getMessage().contains("Endpoint not found"));
        }
    }

    @Test void sageMakerGeneratedTextAndCombinedKey() throws Exception {
        try (AwsFake f = new AwsFake(s -> new Object[]{200, "[{\"generated_text\":\"positive\"}]"})) {
            var ctx = PackagesTestSupport.ctx(tmp(), Map.of());
            SecureString combined = new SecureString("AKIDCOMBINED:" + AWS_SECRET.reveal(), "c");
            Map<String, Object> r = new GenAiPackage().summarize(ctx, "text", 1, "aws_bedrock_sagemaker", null, "ep", combined, f.url(), false, null, "us-east-2");
            assertEquals("positive", r.get("summary"));
            assertTrue(f.seen.get(0).headers().get("authorization").contains("Credential=AKIDCOMBINED/"));
            assertValidSignature(f.seen.get(0), f.url() + "/endpoints/ep/invocations", "AKIDCOMBINED", AWS_SECRET.reveal(), "us-east-2");
        }
        try (AwsFake f = new AwsFake(s -> new Object[]{200, "{\"unexpected\":true}"})) {
            var ctx = PackagesTestSupport.ctx(tmp(), Map.of());
            var e = assertThrows(ActionException.class, () -> new GenAiPackage().prompt(ctx, "x", null, "aws_bedrock_sagemaker", null, "ep", AWS_SECRET, f.url(), 0, 10, false, AWS_ID, "us-east-2"));
            assertEquals("AI.BAD_RESPONSE", e.code());
        }
    }

    @Test void sageMakerFromAModelConnectionWithVaultKeys() throws Exception {
        try (AwsFake f = new AwsFake(s -> new Object[]{200, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}"})) {
            Path w = tmp();
            Files.writeString(w.resolve("model-connections.json"), "{\"connections\":[{\"name\":\"sm\",\"provider\":\"aws_bedrock_sagemaker\",\"model\":\"ep-1\","
                    + "\"base_url\":\"" + f.url() + "\",\"region\":\"ap-south-1\",\"api_key\":{\"$credential\":\"aws/sm/secret\"},\"aws_access_key_id\":{\"$credential\":\"aws/sm/id\"}}]}");
            CredentialVault vault = new CredentialVault.InMemory().put("aws", "sm", "secret", AWS_SECRET.reveal()).put("aws", "sm", "id", "AKIDFROMVAULT");
            var ctx = PackagesTestSupport.ctx(w, Map.of("vault", vault));
            Map<String, Object> r = new GenAiPackage().prompt(ctx, "Hi", null, "openai", "sm", null, null, null, 0, 10, false, null, null);
            assertEquals("ok", r.get("text"));
            Seen s = f.seen.get(0);
            assertEquals("/endpoints/ep-1/invocations", s.path());
            assertTrue(s.headers().get("authorization").contains("Credential=AKIDFROMVAULT/"));
            assertTrue(s.headers().get("authorization").contains("/ap-south-1/sagemaker/aws4_request"));
        }
    }

    @Test void sageMakerInputErrors() throws Exception {
        var ctx = PackagesTestSupport.ctx(tmp(), Map.of());
        GenAiPackage g = new GenAiPackage();
        assertEquals("AI.NO_KEY", assertThrows(ActionException.class, () -> g.prompt(ctx, "x", null, "aws_bedrock_sagemaker", null, "ep", null, null, 0, 10, false, AWS_ID, "eu-west-1")).code());
        assertEquals("AI.NO_ACCESS_KEY", assertThrows(ActionException.class, () -> g.prompt(ctx, "x", null, "aws_bedrock_sagemaker", null, "ep", AWS_SECRET, null, 0, 10, false, null, "eu-west-1")).code());
        assertEquals("AI.BAD_REGION", assertThrows(ActionException.class, () -> g.prompt(ctx, "x", null, "aws_bedrock_sagemaker", null, "ep", AWS_SECRET, null, 0, 10, false, AWS_ID, "EU West")).code());
        if (System.getenv("AWS_REGION") == null)
            assertEquals("AI.NO_REGION", assertThrows(ActionException.class, () -> g.prompt(ctx, "x", null, "aws_bedrock_sagemaker", null, "ep", AWS_SECRET, null, 0, 10, false, AWS_ID, null)).code());
        assertEquals("eu-central-1", LlmClient.awsRegion(new LlmClient.Config("aws_bedrock_sagemaker", "ep", AWS_SECRET, "https://runtime.sagemaker.eu-central-1.amazonaws.com", 0, 1, false)));
        assertEquals("AI.BAD_PROVIDER", assertThrows(ActionException.class, () -> g.prompt(ctx, "x", null, "bedrock", null, "m", AWS_SECRET, null, 0, 10, false, null, null)).code());
    }

    @Test void providerListsIncludeTheNewProviders() throws Exception {
        for (Class<?> c : List.of(GenAiPackage.class, AiAgentPackage.class))
            for (Method m : c.getMethods())
                for (var params : m.getParameterAnnotations())
                    for (var a : params)
                        if (a instanceof Input in && in.name().equals("provider"))
                            assertTrue(List.of(in.enumValues()).containsAll(List.of("huggingface", "aws_bedrock_sagemaker")), c.getSimpleName() + "." + m.getName());
        assertTrue(GenAiPackage.PROVIDERS.containsAll(List.of("huggingface", "aws_bedrock_sagemaker")));
    }
}
