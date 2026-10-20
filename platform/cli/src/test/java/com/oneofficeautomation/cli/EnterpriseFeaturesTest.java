package com.oneofficeautomation.cli;

import com.oneofficeautomation.controlroom.*;
import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.JsonLite;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * One Command Center enterprise features end to end: external vaults (HashiCorp KV v2, AWS SigV4), AI governance,
 * the MCP server, single sign-on with OpenID Connect, and One Workflow with the task inbox.
 */
class EnterpriseFeaturesTest {
    static UnifiedServerTest.Res call(String base, String method, String path, Object body, String bearer) throws Exception {
        return UnifiedServerTest.call(base, method, path, body, bearer, null);
    }

    static Map<String, String> query(String q) {
        Map<String, String> m = new HashMap<>();
        for (String kv : q.split("&")) { int i = kv.indexOf('='); m.put(URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8), URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8)); }
        return m;
    }

    static void reply(com.sun.net.httpserver.HttpExchange ex, int status, Object body) throws java.io.IOException {
        byte[] b = JsonLite.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    @Test void awsSignatureMatchesThePublishedExample() throws Exception {
        // The worked example from the AWS Signature Version 4 documentation (IAM ListUsers).
        Map<String, String> h = ExternalVaults.SigV4.sign("GET", URI.create("https://iam.amazonaws.com/?Action=ListUsers&Version=2010-05-08"),
                Map.of("content-type", "application/x-www-form-urlencoded; charset=utf-8"), "", "AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY",
                "us-east-1", "iam", ZonedDateTime.of(2015, 8, 30, 12, 36, 0, 0, ZoneOffset.UTC));
        assertEquals("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/iam/aws4_request, SignedHeaders=content-type;host;x-amz-date, "
                + "Signature=5d672d79c15b13162d9279b0855cfba6789a8edb4c82c400e06b5924a6f2b5d7", h.get("authorization"));
    }

    @SuppressWarnings("unchecked")
    @Test void vaultsGovernanceMcpSsoAndWorkflows() throws Exception {
        Path data = Files.createTempDirectory("ooa-ent-data"), agentHome = Files.createTempDirectory("ooa-ent-agent");
        Db db = Db.embedded(data);
        ControlRoom cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        String pw = cr.bootstrap();
        ControlRoomServer server = new ControlRoomServer(cr, "127.0.0.1", 0);
        server.start();
        String base = "http://127.0.0.1:" + server.port();

        // Fake HashiCorp Vault, fake OpenAI-compatible model and fake OpenID Connect provider.
        List<String> llmAuth = new CopyOnWriteArrayList<>();
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        String[] lastCodeVerifier = new String[1];
        String[] idToken = new String[1];
        HttpServer fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String fakeBase = "http://127.0.0.1:" + fake.getAddress().getPort();
        fake.createContext("/v1/secret/data/", ex -> {
            if (!"vault-token-1".equals(ex.getRequestHeaders().getFirst("X-Vault-Token"))) { reply(ex, 403, Map.of("errors", List.of("permission denied"))); return; }
            if (ex.getRequestURI().getPath().endsWith("/erp")) reply(ex, 200, Map.of("data", Map.of("data", Map.of("password", "S3cret-from-vault"), "metadata", Map.of())));
            else reply(ex, 404, Map.of("errors", List.of()));
        });
        fake.createContext("/v1/chat/completions", ex -> {
            llmAuth.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
            ex.getRequestBody().readAllBytes();
            reply(ex, 200, Map.of("choices", List.of(Map.of("message", Map.of("role", "assistant", "content", "Hello from the model"), "finish_reason", "stop")),
                    "usage", Map.of("prompt_tokens", 11, "completion_tokens", 4)));
        });
        fake.createContext("/idp/.well-known/openid-configuration", ex -> reply(ex, 200, Map.of("issuer", fakeBase + "/idp", "authorization_endpoint", fakeBase + "/idp/authorize",
                "token_endpoint", fakeBase + "/idp/token", "jwks_uri", fakeBase + "/idp/jwks")));
        fake.createContext("/idp/jwks", ex -> {
            RSAPublicKey pub = (RSAPublicKey) kp.getPublic();
            Base64.Encoder e = Base64.getUrlEncoder().withoutPadding();
            reply(ex, 200, Map.of("keys", List.of(Map.of("kty", "RSA", "kid", "k1", "alg", "RS256", "use", "sig",
                    "n", e.encodeToString(unsigned(pub.getModulus())), "e", e.encodeToString(unsigned(pub.getPublicExponent()))))));
        });
        fake.createContext("/idp/token", ex -> {
            Map<String, String> f = query(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastCodeVerifier[0] = f.get("code_verifier");
            if (!"good-code".equals(f.get("code"))) { reply(ex, 400, Map.of("error", "invalid_grant")); return; }
            reply(ex, 200, Map.of("access_token", "at", "token_type", "Bearer", "id_token", idToken[0]));
        });
        fake.start();
        com.oneofficeautomation.agent.Agent agent = null;
        try {
            String t0 = (String) call(base, "POST", "/api/auth/login", Map.of("username", "admin", "password", pw), null).json().get("token");
            call(base, "POST", "/api/auth/password", Map.of("current", pw, "new", "Enterprise-Passw0rd-1"), t0);
            String admin = (String) call(base, "POST", "/api/auth/login", Map.of("username", "admin", "password", "Enterprise-Passw0rd-1"), null).json().get("token");
            agent = Ooa.startLocalAgent(cr, base, agentHome.resolve("agent.json"), agentHome.resolve("work"));
            String apiKey = (String) call(base, "POST", "/api/apikeys", Map.of("name", "assistant", "days", 30), admin).json().get("key");

            // ---- external vault ----
            assertEquals(200, call(base, "POST", "/api/lockers", Map.of("name", "corp", "description", "Company vault", "consumers", "role:admin"), admin).status());
            assertEquals(400, call(base, "PUT", "/api/lockers/corp/external", Map.of("provider", "hashicorp"), admin).status(), "a link needs the vault address");
            assertEquals(200, call(base, "PUT", "/api/lockers/corp/external", Map.of("provider", "hashicorp", "url", fakeBase, "mount", "secret", "secret", "vault-token-1"), admin).status());
            String lockers = call(base, "GET", "/api/lockers", null, admin).body();
            assertTrue(lockers.contains("hashicorp") && !lockers.contains("vault-token-1") && !lockers.contains("external_secret"), "the sign-in secret never leaves the server: " + lockers);
            Map<String, Object> test = call(base, "POST", "/api/lockers/corp/test", Map.of("credential", "erp", "attribute", "password"), admin).json();
            assertEquals(true, test.get("ok"), String.valueOf(test));
            assertFalse(test.toString().contains("S3cret"), "testing never returns the value");
            assertEquals("VAULT.NOT_FOUND", call(base, "POST", "/api/lockers/corp/test", Map.of("credential", "nope", "attribute", "password"), admin).json().get("code"));

            // ---- an agent that calls an AI model with a key from the external vault: AI governance ----
            Map<String, Object> ai = (Map<String, Object>) JsonLite.parse("{\"kind\":\"TaskBot\",\"id\":\"ask_ai\",\"name\":\"Ask AI\",\"variables\":["
                    + "{\"name\":\"key\",\"type\":\"Credential\",\"direction\":\"input\"},{\"name\":\"answer\",\"type\":\"String\",\"direction\":\"output\"}],"
                    + "\"steps\":[{\"id\":\"s1\",\"action\":\"ooa.genai/prompt\",\"args\":{\"prompt\":\"Say hello\",\"provider\":\"openai_compatible\",\"model\":\"test-model\","
                    + "\"api_key\":\"$key\",\"base_url\":\"" + fakeBase + "/v1\",\"mask_pii\":false},\"out\":{\"text\":\"answer\"}}]}");
            assertEquals(200, call(base, "POST", "/api/repo/checkin", Map.of("path", "AI/Ask AI.flow.json", "flow", ai, "comment", "v1"), admin).status());
            String deviceId = (String) ((Map<String, Object>) ((List<Object>) call(base, "GET", "/api/devices", null, admin).json().get("devices")).get(0)).get("id");
            String aiRun = (String) call(base, "POST", "/api/runs", Map.of("bot_path", "AI/Ask AI.flow.json", "device_id", deviceId,
                    "inputs", Map.of("key", Map.of("$credential", "corp/erp/password"))), admin).json().get("run_id");
            assertTrue(ControlRoomE2ETest.until(() -> call(base, "GET", "/api/runs/" + aiRun, null, admin).body().contains("\"status\":\"success\""), 90000),
                    "the AI agent finishes: " + call(base, "GET", "/api/runs/" + aiRun, null, admin).body());
            assertEquals(List.of("Bearer S3cret-from-vault"), llmAuth, "the key came from the external vault at run time");
            Map<String, Object> gov = call(base, "GET", "/api/governance?days=7", null, admin).json();
            assertEquals(1, ((Number) ((Map<String, Object>) gov.get("totals")).get("calls")).intValue(), String.valueOf(gov));
            assertTrue(String.valueOf(gov.get("by_model")).contains("test-model") && String.valueOf(gov.get("recent")).contains("AI/Ask AI.flow.json"), String.valueOf(gov));
            assertFalse(String.valueOf(gov).contains("Say hello"), "prompts are not stored, only a fingerprint");
            assertTrue(call(base, "GET", "/api/audit?limit=200&q=corp", null, admin).body().contains("credential.read"), "external reads are audited");

            // ---- MCP server ----
            Map<String, Object> echo = (Map<String, Object>) JsonLite.parse("{\"kind\":\"TaskBot\",\"id\":\"echo_amount\",\"name\":\"Echo amount\",\"description\":\"Returns the amount it was given.\","
                    + "\"variables\":[{\"name\":\"amount\",\"type\":\"Number\",\"direction\":\"input\",\"required\":true,\"description\":\"Any amount\"},"
                    + "{\"name\":\"total\",\"type\":\"Number\",\"direction\":\"output\"}],"
                    + "\"steps\":[{\"id\":\"s1\",\"action\":\"core/set\",\"args\":{\"variable\":\"total\",\"value\":\"$amount\"}}]}");
            call(base, "POST", "/api/repo/checkin", Map.of("path", "Finance/Echo amount.flow.json", "flow", echo, "comment", "v1"), admin);
            Map<String, Object> init = call(base, "POST", "/api/mcp", Map.of("jsonrpc", "2.0", "id", 1, "method", "initialize",
                    "params", Map.of("protocolVersion", "2025-06-18", "capabilities", Map.of(), "clientInfo", Map.of("name", "test", "version", "1"))), apiKey).json();
            assertEquals("2025-06-18", ((Map<String, Object>) init.get("result")).get("protocolVersion"), String.valueOf(init));
            assertEquals(202, call(base, "POST", "/api/mcp", Map.of("jsonrpc", "2.0", "method", "notifications/initialized"), apiKey).status());
            List<Object> none = (List<Object>) ((Map<String, Object>) call(base, "POST", "/api/mcp", Map.of("jsonrpc", "2.0", "id", 2, "method", "tools/list"), apiKey).json().get("result")).get("tools");
            assertTrue(none.isEmpty(), "no agent is offered until an administrator chooses");
            call(base, "PUT", "/api/settings", Map.of("mcp_agents", "Finance/Echo amount.flow.json", "mcp_timeout_seconds", 90), admin);
            List<Object> tools = (List<Object>) ((Map<String, Object>) call(base, "POST", "/api/mcp", Map.of("jsonrpc", "2.0", "id", 3, "method", "tools/list"), apiKey).json().get("result")).get("tools");
            assertEquals(1, tools.size());
            Map<String, Object> tool = (Map<String, Object>) tools.get(0);
            assertEquals("Finance_Echo_amount", tool.get("name"));
            assertEquals("number", ((Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) tool.get("inputSchema")).get("properties")).get("amount")).get("type"));
            Map<String, Object> called = (Map<String, Object>) call(base, "POST", "/api/mcp", Map.of("jsonrpc", "2.0", "id", 4, "method", "tools/call",
                    "params", Map.of("name", "Finance_Echo_amount", "arguments", Map.of("amount", 7))), apiKey).json().get("result");
            assertEquals(false, called.get("isError"), String.valueOf(called));
            assertTrue(String.valueOf(called.get("content")).contains("7"), String.valueOf(called));
            assertTrue(call(base, "GET", "/api/runs?filter=history&limit=50", null, admin).body().contains("\"source\":\"mcp\""), "MCP calls are normal runs with source mcp");
            Map<String, Object> unknown = call(base, "POST", "/api/mcp", Map.of("jsonrpc", "2.0", "id", 5, "method", "no/such"), apiKey).json();
            assertEquals(-32601, ((Number) ((Map<String, Object>) unknown.get("error")).get("code")).intValue());

            // ---- One Workflow and the task inbox ----
            Map<String, Object> form = (Map<String, Object>) JsonLite.parse("{\"name\":\"Expense details\",\"fields\":[{\"id\":\"reason\",\"type\":\"text\",\"label\":\"Reason\",\"required\":true},"
                    + "{\"id\":\"level\",\"type\":\"dropdown\",\"label\":\"Level\",\"options\":[\"low\",\"high\"]}]}");
            assertEquals(200, call(base, "POST", "/api/repo/checkin", Map.of("path", "Forms/Expense details.form.json", "flow", form, "comment", "v1"), admin).status());
            Map<String, Object> bad = (Map<String, Object>) JsonLite.parse("{\"name\":\"Bad\",\"stages\":[{\"id\":\"a\",\"type\":\"bot\",\"name\":\"No agent\"}]}");
            assertEquals("CR.INVALID_BOT", ((Map<String, Object>) call(base, "POST", "/api/repo/checkin", Map.of("path", "Flows/Bad.process.json", "flow", bad), admin).json().get("error")).get("code"));
            Map<String, Object> wfDef = (Map<String, Object>) JsonLite.parse("{\"name\":\"Expense\",\"stages\":["
                    + "{\"id\":\"details\",\"type\":\"form\",\"name\":\"Details\",\"form\":\"Forms/Expense details.form.json\",\"assignee\":\"role:admin\",\"due_hours\":4},"
                    + "{\"id\":\"route\",\"type\":\"decision\",\"name\":\"Route\",\"condition\":\"$level == \\\"high\\\"\",\"if_true\":\"approve\",\"if_false\":\"pay\"},"
                    + "{\"id\":\"approve\",\"type\":\"approval\",\"name\":\"Manager approval\",\"assignee\":\"user:admin\",\"on_reject\":\"end\"},"
                    + "{\"id\":\"pay\",\"type\":\"bot\",\"name\":\"Pay\",\"bot\":\"Finance/Echo amount.flow.json\",\"inputs\":{\"amount\":\"$amount\"}},"
                    + "{\"id\":\"tell\",\"type\":\"notify\",\"name\":\"Paid\",\"to\":\"user:admin\",\"message\":\"Paid ${total}\"}]}");
            assertEquals(200, call(base, "POST", "/api/repo/checkin", Map.of("path", "Flows/Expense.process.json", "flow", wfDef, "comment", "v1"), admin).status());
            assertEquals("CR.NOT_AN_AGENT", ((Map<String, Object>) call(base, "POST", "/api/runs", Map.of("bot_path", "Flows/Expense.process.json", "device_id", deviceId), admin).json().get("error")).get("code"));

            String wf = (String) call(base, "POST", "/api/workflows/start", Map.of("path", "Flows/Expense.process.json", "data", Map.of("amount", 12)), admin).json().get("id");
            List<Object> tasks = (List<Object>) call(base, "GET", "/api/tasks", null, admin).json().get("tasks");
            assertEquals(1, tasks.size(), String.valueOf(tasks));
            Map<String, Object> t1 = (Map<String, Object>) tasks.get(0);
            assertEquals("form", t1.get("kind"));
            assertTrue(String.valueOf(t1.get("form")).contains("reason") && t1.get("due") != null);
            assertEquals(400, call(base, "POST", "/api/tasks/" + t1.get("id") + "/complete", Map.of("answers", Map.of("level", "high")), admin).status(), "required fields are checked");
            assertEquals(400, call(base, "POST", "/api/tasks/" + t1.get("id") + "/complete", Map.of("answers", Map.of("reason", "Travel", "level", "huge")), admin).status(), "choices are checked");
            assertEquals(200, call(base, "POST", "/api/tasks/" + t1.get("id") + "/claim", null, admin).status());
            assertEquals(200, call(base, "POST", "/api/tasks/" + t1.get("id") + "/complete", Map.of("answers", Map.of("reason", "Travel", "level", "high")), admin).status());
            Map<String, Object> t2 = (Map<String, Object>) ((List<Object>) call(base, "GET", "/api/tasks", null, admin).json().get("tasks")).get(0);
            assertEquals("approval", t2.get("kind"), "high amounts go to approval");
            assertEquals("Travel", ((Map<String, Object>) t2.get("data")).get("reason"), "the approver sees the workflow data");
            assertEquals(200, call(base, "POST", "/api/tasks/" + t2.get("id") + "/complete", Map.of("outcome", "approved", "comment", "OK"), admin).status());
            assertTrue(ControlRoomE2ETest.until(() -> call(base, "GET", "/api/workflows/runs", null, admin).body().contains("\"status\":\"completed\""), 90000),
                    "the workflow runs the agent and completes: " + call(base, "GET", "/api/workflows/runs", null, admin).body());
            Map<String, Object> done = (Map<String, Object>) ((List<Object>) call(base, "GET", "/api/workflows/runs", null, admin).json().get("runs")).stream()
                    .filter(r -> wf.equals(((Map<String, Object>) r).get("id"))).findFirst().orElseThrow();
            Map<String, Object> wd = (Map<String, Object>) done.get("data");
            assertEquals(true, wd.get("approved"));
            assertEquals(12, ((Number) wd.get("total")).intValue(), "the agent's outputs join the workflow data");
            assertTrue(String.valueOf(done.get("history")).contains("agent_finished"));
            List<Object> notes = (List<Object>) call(base, "GET", "/api/tasks", null, admin).json().get("tasks");
            assertTrue(notes.stream().anyMatch(n -> "notification".equals(((Map<String, Object>) n).get("kind")) && String.valueOf(((Map<String, Object>) n).get("description")).equals("Paid 12")), String.valueOf(notes));

            String wf2 = (String) call(base, "POST", "/api/workflows/start", Map.of("path", "Flows/Expense.process.json", "data", Map.of("amount", 5)), admin).json().get("id");
            Map<String, Object> f2 = (Map<String, Object>) ((List<Object>) call(base, "GET", "/api/tasks", null, admin).json().get("tasks")).stream()
                    .filter(x -> "form".equals(((Map<String, Object>) x).get("kind"))).findFirst().orElseThrow();
            call(base, "POST", "/api/tasks/" + f2.get("id") + "/complete", Map.of("answers", Map.of("reason", "Taxi", "level", "high")), admin);
            Map<String, Object> a2 = (Map<String, Object>) ((List<Object>) call(base, "GET", "/api/tasks", null, admin).json().get("tasks")).stream()
                    .filter(x -> "approval".equals(((Map<String, Object>) x).get("kind"))).findFirst().orElseThrow();
            call(base, "POST", "/api/tasks/" + a2.get("id") + "/complete", Map.of("outcome", "rejected"), admin);
            Map<String, Object> rejected = (Map<String, Object>) ((List<Object>) call(base, "GET", "/api/workflows/runs", null, admin).json().get("runs")).stream()
                    .filter(r -> wf2.equals(((Map<String, Object>) r).get("id"))).findFirst().orElseThrow();
            assertEquals("completed", rejected.get("status"));
            assertFalse(String.valueOf(rejected.get("history")).contains("agent_started"), "a rejection ends the workflow before payment");
            assertEquals(1, ((Number) call(base, "GET", "/api/dashboard", null, admin).json().get("open_tasks")).intValue(), "the notification is still waiting to be read");

            // ---- single sign-on ----
            assertEquals(false, call(base, "GET", "/api/auth/sso", null, null).json().get("enabled"));
            call(base, "PUT", "/api/settings", Map.of("sso_issuer", fakeBase + "/idp", "sso_client_id", "ooa-cc", "sso_default_role", "viewer", "public_url", base), admin);
            assertEquals(true, call(base, "GET", "/api/auth/sso", null, null).json().get("enabled"));
            UnifiedServerTest.Res start = call(base, "GET", "/api/auth/sso/start", null, null);
            assertEquals(302, start.status());
            URI loc = URI.create(start.headers().firstValue("Location").orElseThrow());
            Map<String, String> aq = query(loc.getRawQuery());
            assertEquals("S256", aq.get("code_challenge_method"));
            assertEquals(base + "/api/auth/sso/callback", aq.get("redirect_uri"));
            idToken[0] = jwt(kp.getPrivate(), Map.of("iss", fakeBase + "/idp", "aud", "ooa-cc", "sub", "u-123", "email", "Dana@Example.com", "email_verified", true, "name", "Dana Example",
                    "nonce", aq.get("nonce"), "exp", System.currentTimeMillis() / 1000 + 300, "iat", System.currentTimeMillis() / 1000));
            UnifiedServerTest.Res cb = call(base, "GET", "/api/auth/sso/callback?code=good-code&state=" + java.net.URLEncoder.encode(aq.get("state"), StandardCharsets.UTF_8), null, null);
            assertEquals(200, cb.status(), cb.body());
            assertNotNull(lastCodeVerifier[0], "PKCE verifier sent to the token endpoint");
            String cookie = cb.headers().firstValue("Set-Cookie").orElseThrow();
            String session = cookie.substring(cookie.indexOf('=') + 1, cookie.indexOf(';'));
            Map<String, Object> me = call(base, "GET", "/api/auth/me", null, session).json();
            assertEquals("dana@example.com", me.get("username"));
            assertEquals("viewer", me.get("role"), "new people get the default role");
            UnifiedServerTest.Res replay = call(base, "GET", "/api/auth/sso/callback?code=good-code&state=" + java.net.URLEncoder.encode(aq.get("state"), StandardCharsets.UTF_8), null, null);
            assertEquals(400, replay.status(), "a sign-in link works once");
            // A token signed by someone else is refused.
            UnifiedServerTest.Res start2 = call(base, "GET", "/api/auth/sso/start", null, null);
            Map<String, String> aq2 = query(URI.create(start2.headers().firstValue("Location").orElseThrow()).getRawQuery());
            KeyPair other = kpg.generateKeyPair();
            idToken[0] = jwt(other.getPrivate(), Map.of("iss", fakeBase + "/idp", "aud", "ooa-cc", "sub", "u-999", "email", "eve@example.com", "nonce", aq2.get("nonce"), "exp", System.currentTimeMillis() / 1000 + 300));
            UnifiedServerTest.Res forged = call(base, "GET", "/api/auth/sso/callback?code=good-code&state=" + java.net.URLEncoder.encode(aq2.get("state"), StandardCharsets.UTF_8), null, null);
            assertEquals(400, forged.status());
            assertTrue(forged.body().contains("signature"), forged.body());
            assertFalse(call(base, "GET", "/api/users", null, admin).body().contains("eve@example.com"));
        } finally {
            if (agent != null) agent.stop();
            fake.stop(0);
            server.stop();
            db.close();
        }
    }

    static byte[] unsigned(BigInteger v) {
        byte[] b = v.toByteArray();
        return b.length > 1 && b[0] == 0 ? Arrays.copyOfRange(b, 1, b.length) : b;
    }

    static String jwt(PrivateKey key, Map<String, Object> claims) throws Exception {
        Base64.Encoder e = Base64.getUrlEncoder().withoutPadding();
        String head = e.encodeToString(JsonLite.write(Map.of("alg", "RS256", "kid", "k1", "typ", "JWT")).getBytes(StandardCharsets.UTF_8));
        String body = e.encodeToString(JsonLite.write(claims).getBytes(StandardCharsets.UTF_8));
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(key);
        s.update((head + "." + body).getBytes(StandardCharsets.US_ASCII));
        return head + "." + body + "." + e.encodeToString(s.sign());
    }
}
