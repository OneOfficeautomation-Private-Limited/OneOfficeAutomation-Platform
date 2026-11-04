package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.CredentialVault;
import com.oneofficeautomation.runner.EventSink;
import com.oneofficeautomation.runner.RunResult;
import com.oneofficeautomation.runner.Runner;
import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.SecureString;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Send request sign-in options (gap G23): OAuth 2.0 client credentials and refresh token, OAuth 1.0a and NTLM. */
class RestSignInTest {

    static ActionContext ctx() throws Exception {
        return new ActionContext("run", "s", "default", Files.createTempDirectory("ooa-signin"), new HashMap<>(), new HashMap<>(), new AtomicBoolean(), List::of, (l, m) -> { });
    }

    static SecureString sec(String v) { return new SecureString(v, "test/x/" + v.length()); }

    /** Send request with only the new sign-in inputs set (the rest as an agent leaves them). */
    static Map<String, Object> send(ActionContext c, String url, String method, Map<String, Object> form, Map<String, Object> a) {
        return new RestPackage().request(c, url, method, null, null, null, form, null, null, null, null, 30, true, null, false, null, null, null, null, null, false, true, null, null, null,
                (String) a.get("oauth_token_url"), (String) a.get("oauth_client_id"), a.get("oauth_client_secret"), a.get("oauth_refresh_token"), (String) a.get("oauth_scope"),
                (String) a.get("oauth_audience"), (String) a.get("oauth_resource"), (String) a.get("oauth_client_auth"),
                (String) a.get("oauth1_consumer_key"), a.get("oauth1_consumer_secret"), a.get("oauth1_token"), a.get("oauth1_token_secret"), (String) a.get("oauth1_signature_method"),
                (String) a.get("ntlm_user"), (String) a.get("ntlm_domain"), a.get("ntlm_password"));
    }

    static void reply(HttpExchange ex, int status, String body) throws java.io.IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
        if (b.length > 0) ex.getResponseBody().write(b);
        ex.close();
    }

    static Map<String, String> form(String body) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String[] p : RestSignIn.parseForm(body)) m.put(p[0], p[1]);
        return m;
    }

    // ------------------------------------------------------------------ OAuth 2.0 client credentials

    /** A token endpoint plus an API that accepts only the newest token; the API can be told to forget it (to answer 401). */
    static final class OAuthServer implements AutoCloseable {
        final HttpServer s;
        final AtomicInteger tokens = new AtomicInteger();
        final List<Map<String, String>> tokenForms = new CopyOnWriteArrayList<>();
        final List<String> tokenAuth = new CopyOnWriteArrayList<>();
        volatile String valid;
        volatile String latestRefresh = "rt-0";
        volatile int expiresIn = 3600;
        volatile boolean rotate = true;

        OAuthServer() throws Exception {
            s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext("/token", ex -> {
                Map<String, String> f = form(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                tokenForms.add(f);
                tokenAuth.add(Optional.ofNullable(ex.getRequestHeaders().getFirst("Authorization")).orElse(""));
                if ("refresh_token".equals(f.get("grant_type"))) {
                    if (!latestRefresh.equals(f.get("refresh_token"))) { reply(ex, 400, "{\"error\":\"invalid_grant\",\"error_description\":\"refresh token " + f.get("refresh_token") + " is not valid\"}"); return; }
                    if (rotate) latestRefresh = "rt-" + (tokens.get() + 1);
                }
                valid = "at-" + tokens.incrementAndGet();
                reply(ex, 200, "{\"access_token\":\"" + valid + "\",\"token_type\":\"bearer\",\"expires_in\":" + expiresIn
                        + ("refresh_token".equals(f.get("grant_type")) && rotate ? ",\"refresh_token\":\"" + latestRefresh + "\"" : "") + "}");
            });
            s.createContext("/api", ex -> {
                String auth = ex.getRequestHeaders().getFirst("Authorization");
                if (valid == null || !("Bearer " + valid).equals(auth)) { reply(ex, 401, "{\"error\":\"unauthorized\"}"); return; }
                reply(ex, 200, "{\"ok\":true,\"token\":\"seen\"}");
            });
            s.start();
        }

        String url(String p) { return "http://127.0.0.1:" + s.getAddress().getPort() + p; }

        @Override public void close() { s.stop(0); }
    }

    @Test void clientCredentialsFetchesCachesAndRenewsOn401() throws Exception {
        try (OAuthServer srv = new OAuthServer()) {
            ActionContext c = ctx();
            Map<String, Object> a = new HashMap<>(Map.of("oauth_token_url", srv.url("/token"), "oauth_client_id", "app 1", "oauth_client_secret", sec("s3cr&t"),
                    "oauth_scope", "orders.read orders.write", "oauth_audience", "https://api.example.test"));
            Map<String, Object> r = send(c, srv.url("/api"), "GET", null, a);
            assertEquals(new BigDecimal(200), r.get("status"));
            assertNull(r.get("refresh_token"));
            // client authentication as HTTP Basic (form-encoded id and secret), scope and audience in the form
            assertEquals("Basic " + Base64.getEncoder().encodeToString("app+1:s3cr%26t".getBytes(StandardCharsets.UTF_8)), srv.tokenAuth.get(0));
            assertEquals(Map.of("grant_type", "client_credentials", "scope", "orders.read orders.write", "audience", "https://api.example.test"), srv.tokenForms.get(0));

            // cached for the run: a second request fetches no new token
            send(c, srv.url("/api"), "GET", null, a);
            assertEquals(1, srv.tokens.get());

            // the API forgets the token: 401, a new token, the request sent once more
            srv.valid = "revoked";
            assertEquals(new BigDecimal(200), send(c, srv.url("/api"), "GET", null, a).get("status"));
            assertEquals(2, srv.tokens.get());

            // a 401 that persists after the renewal is reported, not retried for ever
            srv.s.removeContext("/api");
            srv.s.createContext("/api", ex -> reply(ex, 401, "{}"));
            assertEquals("REST.HTTP_ERROR", assertThrows(ActionException.class, () -> send(c, srv.url("/api"), "GET", null, a)).code());
            assertEquals(3, srv.tokens.get());

            // another run has its own cache
            srv.s.removeContext("/api");
            srv.s.createContext("/api", ex -> reply(ex, ("Bearer " + srv.valid).equals(ex.getRequestHeaders().getFirst("Authorization")) ? 200 : 401, "{}"));
            send(ctx(), srv.url("/api"), "GET", null, a);
            assertEquals(4, srv.tokens.get());
        }
    }

    @Test void tokenIsRenewedShortlyBeforeItExpires() throws Exception {
        try (OAuthServer srv = new OAuthServer()) {
            srv.expiresIn = 1;   // renewed a tenth of its life (0.1 s) early
            ActionContext c = ctx();
            Map<String, Object> a = Map.of("oauth_token_url", srv.url("/token"), "oauth_client_id", "app", "oauth_client_secret", sec("s3cret"), "oauth_client_auth", "body");
            send(c, srv.url("/api"), "GET", null, a);
            assertEquals(Map.of("grant_type", "client_credentials", "client_id", "app", "client_secret", "s3cret"), srv.tokenForms.get(0));
            assertEquals("", srv.tokenAuth.get(0));
            send(c, srv.url("/api"), "GET", null, a);
            assertEquals(1, srv.tokens.get());
            Thread.sleep(1000);
            send(c, srv.url("/api"), "GET", null, a);
            assertEquals(2, srv.tokens.get());
        }
    }

    // ------------------------------------------------------------------ OAuth 2.0 refresh token

    @Test void refreshTokenRotationIsKeptForTheRunAndReturned() throws Exception {
        try (OAuthServer srv = new OAuthServer()) {
            ActionContext c = ctx();
            Map<String, Object> a = Map.of("oauth_token_url", srv.url("/token"), "oauth_client_id", "public-app", "oauth_refresh_token", sec("rt-0"));
            Map<String, Object> r = send(c, srv.url("/api"), "GET", null, a);
            assertEquals(Map.of("grant_type", "refresh_token", "refresh_token", "rt-0", "client_id", "public-app"), srv.tokenForms.get(0));
            assertEquals("rt-1", ((SecureString) r.get("refresh_token")).reveal());

            // the old refresh token is no longer valid on the server: a renewal on 401 must use the rotated one
            srv.valid = "revoked";
            r = send(c, srv.url("/api"), "GET", null, a);
            assertEquals(new BigDecimal(200), r.get("status"));
            assertEquals("rt-1", srv.tokenForms.get(1).get("refresh_token"));
            assertEquals("rt-2", ((SecureString) r.get("refresh_token")).reveal());
            assertEquals("****", String.valueOf(r.get("refresh_token")));

            // a server that does not rotate: the given refresh token is returned unchanged
            try (OAuthServer still = new OAuthServer()) {
                still.rotate = false;
                Map<String, Object> r2 = send(ctx(), still.url("/api"), "GET", null, Map.of("oauth_token_url", still.url("/token"), "oauth_client_id", "app",
                        "oauth_client_secret", sec("cs"), "oauth_refresh_token", sec("rt-0")));
                assertEquals("rt-0", ((SecureString) r2.get("refresh_token")).reveal());
                assertTrue(still.tokenAuth.get(0).startsWith("Basic "));
            }

            // a refused refresh token is REST.OAUTH_FAILED, without the token in the message
            ActionException e = assertThrows(ActionException.class, () -> send(ctx(), srv.url("/api"), "GET", null,
                    Map.of("oauth_token_url", srv.url("/token"), "oauth_client_id", "public-app", "oauth_refresh_token", sec("rt-stolen-value"))));
            assertEquals("REST.OAUTH_FAILED", e.code());
            assertTrue(e.getMessage().contains("invalid_grant"), e.getMessage());
            assertFalse(e.getMessage().contains("rt-stolen-value"), e.getMessage());
        }
    }

    @Test void settingsAreChecked() throws Exception {
        ActionContext c = ctx();
        String url = "http://127.0.0.1:1/api";
        assertEquals("REST.OAUTH_SETTINGS", assertThrows(ActionException.class, () -> send(c, url, "GET", null, Map.of("oauth_client_id", "a", "oauth_client_secret", sec("s")))).code());
        assertEquals("REST.OAUTH_SETTINGS", assertThrows(ActionException.class, () -> send(c, url, "GET", null, Map.of("oauth_token_url", url))).code());
        assertEquals("REST.OAUTH_SETTINGS", assertThrows(ActionException.class, () -> send(c, url, "GET", null, Map.of("oauth_token_url", url, "oauth_client_id", "a"))).code());
        assertEquals("REST.NO_SECRET", assertThrows(ActionException.class, () -> send(c, url, "GET", null, Map.of("oauth_token_url", url, "oauth_client_id", "a", "oauth_client_secret", "plain"))).code());
        assertEquals("REST.OAUTH_SETTINGS", assertThrows(ActionException.class, () -> send(c, url, "GET", null, Map.of("oauth1_consumer_key", "k"))).code());
        assertEquals("REST.OAUTH_SETTINGS", assertThrows(ActionException.class, () -> send(c, url, "GET", null, Map.of("oauth1_consumer_key", "k", "oauth1_consumer_secret", sec("s"), "oauth1_token", sec("t")))).code());
        assertEquals("REST.BAD_AUTH", assertThrows(ActionException.class, () -> send(c, url, "GET", null, Map.of("oauth1_consumer_key", "k", "ntlm_user", "u"))).code());
        assertEquals("REST.NO_SECRET", assertThrows(ActionException.class, () -> send(c, url, "GET", null, Map.of("ntlm_user", "u", "ntlm_password", "plain"))).code());
        assertEquals("REST.NO_USER", assertThrows(ActionException.class, () -> send(c, url, "GET", null, Map.of("ntlm_password", sec("p")))).code());
        // an unreachable token endpoint
        assertEquals("REST.OAUTH_FAILED", assertThrows(ActionException.class, () -> send(c, url, "GET", null,
                Map.of("oauth_token_url", "http://127.0.0.1:1/token", "oauth_client_id", "a", "oauth_client_secret", sec("s")))).code());
    }

    // ------------------------------------------------------------------ OAuth 1.0a

    @Test void oauth1BaseStringAndSignatureMatchTheSpecifications() {
        // RFC 5849 section 3.4.1.1: query, form body and oauth parameters, percent-encoded and sorted
        Map<String, String> oauth = new TreeMap<>(Map.of("oauth_consumer_key", "9djdj82h48djs9d2", "oauth_token", "kkk9d7dh3k39sjv7",
                "oauth_signature_method", "HMAC-SHA1", "oauth_timestamp", "137131201", "oauth_nonce", "7d8f3e4a"));
        assertEquals("POST&http%3A%2F%2Fexample.com%2Frequest&a2%3Dr%2520b%26a3%3D2%2520q%26a3%3Da%26b5%3D%253D%25253D%26c%2540%3D%26c2%3D%26oauth_consumer_key%3D9djdj82h48djs9d2%26oauth_nonce%3D7d8f3e4a%26oauth_signature_method%3DHMAC-SHA1%26oauth_timestamp%3D137131201%26oauth_token%3Dkkk9d7dh3k39sjv7",
                RestSignIn.baseString("POST", "http://example.com/request?b5=%3D%253D&a3=a&c%40=&a2=r%20b", RestSignIn.parseForm("c2&a3=2+q"), oauth));
        assertEquals("http%3A%2F%2Fexample.com%3A8080%2Fa%20b", RestSignIn.baseString("GET", "HTTP://Example.COM:8080/a%20b", List.of(), Map.of()).split("&")[1]
                .replace("%2520", "%20"));

        // the photos example of the OAuth 1.0 specification (appendix A.5), signed with HMAC-SHA1
        RestSignIn.OAuth1 o = new RestSignIn.OAuth1("dpf43f3p2l4k3l03", sec("kd94hf93k423kf44"), sec("nnch734d00sl2jdk"), sec("pfkkdhi9sl3r4s00"), "HMAC-SHA1");
        String h = RestSignIn.oauth1Header(o, "GET", "http://photos.example.net/photos?file=vacation.jpg&size=original", List.of(), "kllo9940pd9333jh", "1191242096");
        assertTrue(h.contains("oauth_signature=\"tR3%2BTy81lMeYAr%2FFid0kMTYa%2FWM%3D\""), h);
        assertTrue(h.startsWith("OAuth ") && h.contains("oauth_version=\"1.0\"") && h.contains("oauth_token=\"nnch734d00sl2jdk\""), h);
        assertFalse(h.contains("kd94hf93k423kf44") || h.contains("pfkkdhi9sl3r4s00"), "secrets never go on the wire");
    }

    /** A server that checks OAuth 1.0a signatures itself (its own base string), with query and form-body parameters. */
    @Test void oauth1SignatureIsVerifiedByTheServer() throws Exception {
        Map<String, String> secrets = Map.of("ck", "consumer secret!", "tok", "token/secret");
        List<String> seen = new CopyOnWriteArrayList<>();
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            Map<String, String> p = new TreeMap<>();
            for (String part : auth.substring(6).split(",\\s*")) {
                int eq = part.indexOf('=');
                p.put(dec(part.substring(0, eq)), dec(part.substring(eq + 2, part.length() - 1)));
            }
            String sig = p.remove("oauth_signature");
            List<String> params = new ArrayList<>();
            p.forEach((k, v) -> params.add(enc(k) + "=" + enc(v)));
            for (String q : (Optional.ofNullable(ex.getRequestURI().getRawQuery()).orElse("") + "&" + body).split("&")) {
                if (q.isEmpty()) continue;
                int eq = q.indexOf('=');
                params.add(enc(URLDecoder.decode(q.substring(0, eq), StandardCharsets.UTF_8)) + "=" + enc(URLDecoder.decode(q.substring(eq + 1), StandardCharsets.UTF_8)));
            }
            Collections.sort(params);
            String base = ex.getRequestMethod() + "&" + enc("http://127.0.0.1:" + s.getAddress().getPort() + ex.getRequestURI().getRawPath()) + "&" + enc(String.join("&", params));
            String alg = p.get("oauth_signature_method").equals("HMAC-SHA256") ? "HmacSHA256" : "HmacSHA1";
            try {
                Mac mac = Mac.getInstance(alg);
                mac.init(new SecretKeySpec((enc(secrets.get(p.get("oauth_consumer_key"))) + "&" + enc(p.get("oauth_token") == null ? "" : secrets.get(p.get("oauth_token")))).getBytes(StandardCharsets.UTF_8), alg));
                boolean ok = Base64.getEncoder().encodeToString(mac.doFinal(base.getBytes(StandardCharsets.UTF_8))).equals(sig);
                seen.add(p.get("oauth_signature_method") + " " + ok);
                reply(ex, ok ? 200 : 401, "{\"ok\":" + ok + "}");
            } catch (Exception e) { reply(ex, 500, "{}"); }
        });
        s.start();
        try {
            String url = "http://127.0.0.1:" + s.getAddress().getPort() + "/v1/items list?filter=a+b&x=%7E1";
            url = url.replace(" ", "%20");
            for (String m : List.of("HMAC-SHA1", "HMAC-SHA256")) {
                Map<String, Object> r = send(ctx(), url, "POST", new LinkedHashMap<>(Map.of("note", "café & tea", "n", 2)), Map.of("oauth1_consumer_key", "ck",
                        "oauth1_consumer_secret", sec(secrets.get("ck")), "oauth1_token", sec("tok"), "oauth1_token_secret", sec(secrets.get("tok")), "oauth1_signature_method", m));
                assertEquals(new BigDecimal(200), r.get("status"), m);
            }
            // two-legged (consumer only), GET without a body
            assertEquals(new BigDecimal(200), send(ctx(), url, "GET", null, Map.of("oauth1_consumer_key", "ck", "oauth1_consumer_secret", sec(secrets.get("ck")))).get("status"));
            // a wrong secret fails the server's check
            assertEquals("REST.HTTP_ERROR", assertThrows(ActionException.class, () -> send(ctx(), "http://127.0.0.1:" + s.getAddress().getPort() + "/x", "GET", null,
                    Map.of("oauth1_consumer_key", "ck", "oauth1_consumer_secret", sec("wrong")))).code());
            assertEquals(List.of("HMAC-SHA1 true", "HMAC-SHA256 true", "HMAC-SHA1 true", "HMAC-SHA1 false"), seen);
        } finally { s.stop(0); }
    }

    static String enc(String v) { return RestSignIn.pct(v); }
    static String dec(String v) { return URLDecoder.decode(v.replace("+", "%2B"), StandardCharsets.UTF_8); }

    // ------------------------------------------------------------------ NTLM

    /** NTLM negotiate / challenge / authenticate against a server that checks the NTLMv2 response with the password. */
    @Test void ntlmSignsInWithUserDomainAndPassword() throws Exception {
        HttpServer srv = IntegratedAuthHttpTest.ntlmServer("N7lm-pass");
        try {
            String url = "http://127.0.0.1:" + srv.getAddress().getPort() + "/whoami";
            Map<String, Object> r = send(ctx(), url, "GET", null, Map.of("ntlm_user", "bob", "ntlm_domain", "TEST", "ntlm_password", sec("N7lm-pass")));
            assertEquals(new BigDecimal(200), r.get("status"));
            assertTrue(((String) r.get("body")).startsWith("signed in as TEST\\bob;"), (String) r.get("body"));
            // a user name that already names the domain
            r = send(ctx(), url, "GET", null, Map.of("ntlm_user", "TEST\\carol", "ntlm_password", sec("N7lm-pass")));
            assertTrue(((String) r.get("body")).startsWith("signed in as TEST\\carol;"), (String) r.get("body"));
            // a wrong password: the server's NTLMv2 check fails
            assertEquals("REST.HTTP_ERROR", assertThrows(ActionException.class, () -> send(ctx(), url, "GET", null,
                    Map.of("ntlm_user", "dave", "ntlm_domain", "TEST", "ntlm_password", sec("wrong-pass")))).code());
        } finally { srv.stop(0); }
    }

    // ------------------------------------------------------------------ no secret in events or errors

    @Test void secretsNeverReachEventsOrErrors() throws Exception {
        try (OAuthServer srv = new OAuthServer()) {
            // a token endpoint that echoes what it was sent in its error
            srv.s.createContext("/echo", ex -> reply(ex, 401, "{\"error\":\"invalid_client\",\"error_description\":\"bad: "
                    + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).replace("\"", "") + "\"}"));
            CredentialVault vault = new CredentialVault.InMemory().put("test", "app", "secret", "Very-Secret-42").put("test", "app", "refresh", "rt-0");
            String vars = "[{\"name\":\"cs\",\"type\":\"Credential\",\"direction\":\"input\"},{\"name\":\"rt\",\"type\":\"Credential\",\"direction\":\"input\"},"
                    + "{\"name\":\"newrt\",\"type\":\"Credential\",\"direction\":\"output\"},{\"name\":\"st\",\"type\":\"Number\",\"direction\":\"output\"}]";
            String steps = "[{\"id\":\"a\",\"action\":\"ooa.rest/request\",\"args\":{\"url\":\"API\",\"oauth_token_url\":\"TOKEN\",\"oauth_client_id\":\"app\",\"oauth_client_secret\":\"$cs\",\"oauth_client_auth\":\"body\"},\"out\":{\"status\":\"st\"}},"
                    + "{\"id\":\"b\",\"action\":\"ooa.rest/request\",\"args\":{\"url\":\"API\",\"oauth_token_url\":\"TOKEN\",\"oauth_client_id\":\"pub\",\"oauth_refresh_token\":\"$rt\"},\"out\":{\"refresh_token\":\"newrt\"}},"
                    + "{\"id\":\"c\",\"action\":\"ooa.rest/request\",\"args\":{\"url\":\"API\",\"oauth_token_url\":\"ECHO\",\"oauth_client_id\":\"app\",\"oauth_client_secret\":\"$cs\",\"oauth_client_auth\":\"body\"}}]";
            steps = steps.replace("API", srv.url("/api")).replace("TOKEN", srv.url("/token")).replace("ECHO", srv.url("/echo"));
            @SuppressWarnings("unchecked")
            Map<String, Object> f = (Map<String, Object>) JsonLite.parse("{\"kind\":\"TaskBot\",\"id\":\"t\",\"name\":\"t\",\"variables\":" + vars + ",\"steps\":" + steps + "}");
            EventSink.ListSink sink = new EventSink.ListSink();
            RunResult r = Runner.builder(PackagesTest.REG).workDir(Files.createTempDirectory("ooa-signin")).sink(sink).service("vault", vault).build()
                    .run(f, Map.of("cs", Map.of("$credential", "test/app/secret"), "rt", Map.of("$credential", "test/app/refresh")));
            String all = sink.events().toString() + " " + r;
            assertTrue(all.contains("REST.OAUTH_FAILED"), all);   // step c failed at the echoing endpoint
            assertEquals(2, srv.tokens.get(), all);   // a and b got tokens
            for (String secret : List.of("Very-Secret-42", "rt-0", "rt-2", "at-1", "at-2"))
                assertFalse(all.contains(secret), secret + " leaked: " + all);
        }
    }
}
