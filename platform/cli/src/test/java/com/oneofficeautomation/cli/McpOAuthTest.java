package com.oneofficeautomation.cli;

import com.oneofficeautomation.controlroom.*;
import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.JsonLite;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** MCP over OAuth (S10): access tokens from the identity provider, the protected resource metadata, and refusals. */
class McpOAuthTest {

    static void reply(HttpExchange ex, int status, Object body) throws java.io.IOException {
        byte[] b = JsonLite.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    @Test void mcpTakesAccessTokensFromTheIdentityProvider() throws Exception {
        Path data = Files.createTempDirectory("ooa-mcp-oauth");
        Db db = Db.embedded(data);
        ControlRoom cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        String pw = cr.bootstrap();
        ControlRoomServer server = new ControlRoomServer(cr, "127.0.0.1", 0);
        server.start();
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        HttpServer idp = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String iss = "http://127.0.0.1:" + idp.getAddress().getPort() + "/idp";
        idp.createContext("/idp/.well-known/openid-configuration", ex -> reply(ex, 200, Map.of("issuer", iss, "authorization_endpoint", iss + "/authorize", "token_endpoint", iss + "/token", "jwks_uri", iss + "/jwks")));
        idp.createContext("/idp/jwks", ex -> {
            RSAPublicKey pub = (RSAPublicKey) kp.getPublic();
            Base64.Encoder e = Base64.getUrlEncoder().withoutPadding();
            reply(ex, 200, Map.of("keys", List.of(Map.of("kty", "RSA", "kid", "k1", "alg", "RS256", "use", "sig",
                "n", e.encodeToString(EnterpriseFeaturesTest.unsigned(pub.getModulus())), "e", e.encodeToString(EnterpriseFeaturesTest.unsigned(pub.getPublicExponent()))))));
        });
        idp.start();
        HttpClient http = HttpClient.newHttpClient();
        String base = "http://127.0.0.1:" + server.port();
        try {
            String t = (String) cr.login("admin", pw, null).get("token");
            cr.changePassword(cr.session(t), pw, "Admin-Passw0rd-2026", t, null);
            ControlRoom.User admin = cr.session((String) cr.login("admin", "Admin-Passw0rd-2026", null).get("token"));
            cr.createUser(admin, "ann@example.com", "Ann", "bot_runner", null);
            cr.platform.updateSettings(admin, Map.of("sso_issuer", iss, "sso_client_id", "console-app"), null);

            String init = JsonLite.write(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list", "params", Map.of()));
            java.util.function.Function<String, HttpResponse<String>> call = (tok) -> {
                try { return http.send(HttpRequest.newBuilder(URI.create(base + "/api/mcp")).header("Authorization", "Bearer " + tok).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(init)).build(), HttpResponse.BodyHandlers.ofString()); }
                catch (Exception e) { throw new RuntimeException(e); }
            };
            long now = Instant.now().getEpochSecond();
            Map<String, Object> claims = new HashMap<>(Map.of("iss", iss, "sub", "ann-1", "aud", "api://ooa-mcp", "exp", now + 600, "iat", now, "email", "ann@example.com", "scp", "mcp.use other"));

            // not set up yet: refused, with a pointer to the metadata
            HttpResponse<String> r0 = call.apply(EnterpriseFeaturesTest.jwt(kp.getPrivate(), claims));
            assertEquals(401, r0.statusCode());
            assertTrue(r0.headers().firstValue("WWW-Authenticate").orElse("").contains("/.well-known/oauth-protected-resource"), r0.headers().map().toString());

            cr.platform.updateSettings(admin, Map.of("mcp_oauth_audience", "api://ooa-mcp", "mcp_oauth_scope", "mcp.use"), null);
            Map<String, Object> meta = (Map<String, Object>) JsonLite.parse(http.send(HttpRequest.newBuilder(URI.create(base + "/.well-known/oauth-protected-resource")).build(), HttpResponse.BodyHandlers.ofString()).body());
            assertEquals("api://ooa-mcp", meta.get("resource"));
            assertEquals(List.of(iss), meta.get("authorization_servers"));
            assertEquals(List.of("mcp.use"), meta.get("scopes_supported"));

            HttpResponse<String> ok = call.apply(EnterpriseFeaturesTest.jwt(kp.getPrivate(), claims));
            assertEquals(200, ok.statusCode(), ok.body());
            assertTrue(ok.body().contains("\"tools\""), ok.body());

            // another audience, an expired token, a missing scope, a forged signature, or no account: refused
            Map<String, Object> other = new HashMap<>(claims); other.put("aud", "api://something-else");
            assertEquals(401, call.apply(EnterpriseFeaturesTest.jwt(kp.getPrivate(), other)).statusCode());
            Map<String, Object> old = new HashMap<>(claims); old.put("exp", now - 3600);
            assertEquals(401, call.apply(EnterpriseFeaturesTest.jwt(kp.getPrivate(), old)).statusCode());
            Map<String, Object> noScope = new HashMap<>(claims); noScope.put("scp", "read");
            assertEquals(403, call.apply(EnterpriseFeaturesTest.jwt(kp.getPrivate(), noScope)).statusCode());
            KeyPair attacker = kpg.generateKeyPair();
            assertEquals(401, call.apply(EnterpriseFeaturesTest.jwt(attacker.getPrivate(), claims)).statusCode());
            Map<String, Object> stranger = new HashMap<>(claims); stranger.put("sub", "x-9"); stranger.put("email", "nobody@example.com");
            assertEquals(401, call.apply(EnterpriseFeaturesTest.jwt(kp.getPrivate(), stranger)).statusCode());
            assertNull(db.one("SELECT id FROM users WHERE username = 'nobody@example.com'"), "no account is created for MCP");

            // a token is accepted on MCP only, never as a console session
            HttpResponse<String> console = http.send(HttpRequest.newBuilder(URI.create(base + "/api/dashboard")).header("Authorization", "Bearer " + EnterpriseFeaturesTest.jwt(kp.getPrivate(), claims)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(401, console.statusCode());
        } finally { server.stop(); idp.stop(0); db.close(); }
    }
}
