package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.CredentialVault;
import com.oneofficeautomation.runner.RunResult;
import com.oneofficeautomation.runner.Runner;
import com.oneofficeautomation.sdk.JsonLite;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.util.*;

import static com.oneofficeautomation.packages.ConnectorsTest.*;
import static com.oneofficeautomation.packages.PackagesTest.REG;
import static com.oneofficeautomation.packages.PackagesTest.tmp;
import static org.junit.jupiter.api.Assertions.*;

/**
 * K1 (command review, batch 10): a renewing sign-in for every connector: toolkits mark the input that takes the token
 * (Slack's bot token, Snowflake's token...), and the sign-in kinds unattended agents of those services need: a JWT
 * grant (Google service account, DocuSign), private-key client authentication (Okta), a GitHub App, a Snowflake key
 * pair, and extra fields (Box).
 */
class ConnectionSignInKindsTest {
    static KeyPair keys;
    static String pkcs8Pem, pkcs1Pem;

    @BeforeAll static void keys() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        keys = g.generateKeyPair();
        byte[] p8 = keys.getPrivate().getEncoded();
        pkcs8Pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(p8) + "\n-----END PRIVATE KEY-----\n";
        pkcs1Pem = "-----BEGIN RSA PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(Arrays.copyOfRange(p8, 26, p8.length)) + "\n-----END RSA PRIVATE KEY-----\n";
    }

    static RunResult run(Path w, String connections, String steps, CredentialVault.InMemory vault) throws Exception {
        Files.writeString(w.resolve("connections.json"), connections);
        @SuppressWarnings("unchecked") Map<String, Object> flow = (Map<String, Object>) JsonLite.parse("{\"kind\":\"TaskBot\",\"id\":\"t\",\"name\":\"t\",\"variables\":"
                + "[{\"name\":\"n\",\"type\":\"Any\",\"direction\":\"output\"}],\"steps\":" + steps + "}");
        return Runner.builder(REG).workDir(w).service("vault", vault).build().run(flow, Map.of());
    }

    static CredentialVault.InMemory vault() {
        return new CredentialVault.InMemory().put("apps", "x", "secret", "s3cret").put("apps", "x", "key", pkcs8Pem).put("apps", "x", "key1", pkcs1Pem)
                .put("apps", "x", "google", "{\"type\":\"service_account\",\"client_email\":\"bot@acme.iam.gserviceaccount.com\",\"private_key\":" + JsonLite.write(pkcs8Pem) + "}");
    }

    /** The claims of a JWT whose signature the test key checks. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> claims(String jwt) throws Exception {
        String[] p = jwt.split("\\.");
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initVerify(keys.getPublic());
        s.update((p[0] + "." + p[1]).getBytes(StandardCharsets.US_ASCII));
        assertTrue(s.verify(Base64.getUrlDecoder().decode(p[2])), "signed with the connection's key");
        return (Map<String, Object>) JsonLite.parse(new String(Base64.getUrlDecoder().decode(p[1]), StandardCharsets.UTF_8));
    }

    static Map<String, String> form(String body) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String kv : body.split("&")) { int i = kv.indexOf('='); m.put(URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8), URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8)); }
        return m;
    }

    @Test void slackGetsTheTokenOfAnAppSignInThroughItsMarkedInput() throws Exception {
        try (Fake f = new Fake()) {
            f.on("POST /oauth/token", c -> ok("{\"access_token\":\"xoxb-renewed\",\"expires_in\":43200}"))
             .on("POST /api/chat.postMessage", c -> ok("{\"ok\":true,\"ts\":\"1.1\",\"channel\":\"C1\"}"));
            String conn = "{\"slack\":{\"package\":\"ooa.slack\",\"values\":{\"base_url\":\"" + f.url() + "/api\",\"sign_in_provider\":\"other\",\"sign_in_client_id\":\"app\","
                    + "\"sign_in_token_url\":\"" + f.url() + "/oauth/token\"},\"credentials\":{\"sign_in_client_secret\":\"apps/x/secret\"}}}";
            RunResult r = run(tmp(), conn, "[{\"id\":\"s\",\"action\":\"ooa.slack/post_message\",\"connection\":\"slack\",\"args\":{\"channel\":\"C1\",\"text\":\"hi\"},\"out\":{\"ts\":\"n\"}}]", vault());
            assertTrue(r.ok(), String.valueOf(r.error()));
            assertEquals("Bearer xoxb-renewed", f.last("POST", "/api/chat.postMessage").header("Authorization"));
        }
    }

    @Test void googleServiceAccountJwtGrantFromItsKeyFile() throws Exception {
        try (Fake f = new Fake()) {
            f.on("POST /token", c -> ok("{\"access_token\":\"ya29.sa\",\"expires_in\":3600}"))
             .on("GET /drive/v3/files", c -> ok("{\"files\":[]}"));
            String conn = "{\"g\":{\"package\":\"ooa.google\",\"values\":{\"base_url\":\"" + f.url() + "\",\"sign_in_provider\":\"google\",\"sign_in_grant\":\"jwt_bearer\","
                    + "\"sign_in_subject\":\"ann@acme.com\",\"sign_in_scope\":\"https://www.googleapis.com/auth/drive\",\"sign_in_token_url\":\"" + f.url() + "/token\"},"
                    + "\"credentials\":{\"sign_in_private_key\":\"apps/x/google\"}}}";
            RunResult r = run(tmp(), conn, "[{\"id\":\"d\",\"action\":\"ooa.google/drive_list\",\"connection\":\"g\",\"args\":{},\"out\":{\"count\":\"n\"}}]", vault());
            assertTrue(r.ok(), String.valueOf(r.error()));
            Map<String, String> sent = form(f.last("POST", "/token").text());
            assertEquals("urn:ietf:params:oauth:grant-type:jwt-bearer", sent.get("grant_type"));
            Map<String, Object> c = claims(sent.get("assertion"));
            assertEquals("bot@acme.iam.gserviceaccount.com", c.get("iss"), "the account from Google's key file");
            assertEquals("ann@acme.com", c.get("sub"), "acting as a person (domain-wide delegation)");
            assertEquals("https://www.googleapis.com/auth/drive", c.get("scope"));
            assertEquals(f.url() + "/token", c.get("aud"));
            assertEquals("Bearer ya29.sa", f.last("GET", "/drive/v3/files").header("Authorization"));
        }
    }

    @Test void oktaServiceAppSignsInWithAPrivateKeyAndBoxSendsExtraFields() throws Exception {
        try (Fake f = new Fake()) {
            f.on("POST /oauth2/v1/token", c -> ok("{\"access_token\":\"okta-at\",\"expires_in\":3600}"))
             .on("GET /api/v1/users/ann%40acme.com", c -> ok("{\"id\":\"00u1\",\"status\":\"ACTIVE\",\"profile\":{\"login\":\"ann@acme.com\"}}"))
             .on("POST /box/token", c -> ok("{\"access_token\":\"box-at\",\"expires_in\":3600}"))
             .on("GET /2.0/files/5", c -> ok("{\"id\":\"5\",\"name\":\"a.pdf\"}"));
            String conn = "{\"okta\":{\"package\":\"ooa.okta\",\"values\":{\"sign_in_provider\":\"other\",\"sign_in_client_id\":\"0oa-svc\",\"sign_in_client_auth\":\"private_key_jwt\","
                    + "\"sign_in_key_id\":\"kid-1\",\"sign_in_scope\":\"okta.users.read\",\"sign_in_token_url\":\"" + f.url() + "/oauth2/v1/token\"},\"credentials\":{\"sign_in_private_key\":\"apps/x/key\"}},"
                    + "\"box\":{\"package\":\"ooa.box\",\"values\":{\"base_url\":\"" + f.url() + "\",\"sign_in_provider\":\"other\",\"sign_in_client_id\":\"box-app\","
                    + "\"sign_in_extra_fields\":\"box_subject_type=enterprise, box_subject_id=12345\",\"sign_in_token_url\":\"" + f.url() + "/box/token\"},\"credentials\":{\"sign_in_client_secret\":\"apps/x/secret\"}}}";
            RunResult r = run(tmp(), conn, "[{\"id\":\"o\",\"action\":\"ooa.okta/get_user\",\"connection\":\"okta\",\"args\":{\"org_url\":\"" + f.url() + "\",\"user\":\"ann@acme.com\"},\"out\":{\"user\":\"n\"}},"
                    + "{\"id\":\"b\",\"action\":\"ooa.box/get_file_info\",\"connection\":\"box\",\"args\":{\"file_id\":\"5\"},\"out\":{\"info\":\"n\"}}]", vault());
            assertTrue(r.ok(), String.valueOf(r.error()));
            Map<String, String> okta = form(f.last("POST", "/oauth2/v1/token").text());
            assertEquals("urn:ietf:params:oauth:client-assertion-type:jwt-bearer", okta.get("client_assertion_type"));
            assertFalse(okta.containsKey("client_secret"));
            Map<String, Object> c = claims(okta.get("client_assertion"));
            assertEquals(List.of("0oa-svc", "0oa-svc", f.url() + "/oauth2/v1/token"), List.of(c.get("iss"), c.get("sub"), c.get("aud")));
            assertEquals("Bearer okta-at", f.last("GET", "/api/v1/users/ann%40acme.com").header("Authorization"));
            Map<String, String> box = form(f.last("POST", "/box/token").text());
            assertEquals(List.of("enterprise", "12345"), List.of(box.get("box_subject_type"), box.get("box_subject_id")));
            assertEquals("Bearer box-at", f.last("GET", "/2.0/files/5").header("Authorization"));
        }
    }

    @Test void githubAppWithAPkcs1KeyAndSnowflakeKeyPair() throws Exception {
        try (Fake f = new Fake()) {
            f.on("POST /app/installations/77/access_tokens", c -> new Object[]{201, "{\"token\":\"ghs_inst\",\"expires_at\":\"2099-01-01T00:00:00Z\"}"})
             .on("GET /repos/acme/web", c -> ok("{\"full_name\":\"acme/web\",\"default_branch\":\"main\"}"))
             .on("POST /api/v2/statements", c -> ok("{\"statementHandle\":\"h1\",\"resultSetMetaData\":{\"numRows\":1,\"rowType\":[{\"name\":\"X\",\"type\":\"fixed\",\"scale\":0}],"
                    + "\"partitionInfo\":[{\"rowCount\":1}]},\"data\":[[\"1\"]]}"));
            String conn = "{\"gh\":{\"package\":\"ooa.github\",\"values\":{\"base_url\":\"" + f.url() + "\",\"sign_in_provider\":\"github\",\"sign_in_client_id\":\"4242\","
                    + "\"sign_in_installation_id\":\"77\",\"sign_in_token_url\":\"" + f.url() + "/app/installations/77/access_tokens\"},\"credentials\":{\"sign_in_private_key\":\"apps/x/key1\"}},"
                    + "\"sf\":{\"package\":\"ooa.snowflake\",\"values\":{\"account_url\":\"" + f.url() + "\",\"sign_in_provider\":\"snowflake\",\"sign_in_account\":\"myorg-acct\","
                    + "\"sign_in_user\":\"etl_bot\"},\"credentials\":{\"sign_in_private_key\":\"apps/x/key\"}}}";
            RunResult r = run(tmp(), conn, "[{\"id\":\"g\",\"action\":\"ooa.github/get_repository\",\"connection\":\"gh\",\"args\":{\"owner\":\"acme\",\"repo\":\"web\"},\"out\":{\"repository\":\"n\"}},"
                    + "{\"id\":\"s\",\"action\":\"ooa.snowflake/execute_statement\",\"connection\":\"sf\",\"args\":{\"sql\":\"SELECT 1\"},\"out\":{\"table\":\"n\"}}]", vault());
            assertTrue(r.ok(), String.valueOf(r.error()));
            Call app = f.last("POST", "/app/installations/77/access_tokens");
            assertEquals("4242", claims(app.header("Authorization").substring("Bearer ".length())).get("iss"), "a PKCS#1 key, as GitHub gives it, signs the App's JWT");
            assertEquals("Bearer ghs_inst", f.last("GET", "/repos/acme/web").header("Authorization"));
            Call sql = f.last("POST", "/api/v2/statements");
            assertEquals("KEYPAIR_JWT", sql.header("X-Snowflake-Authorization-Token-Type"), "the step is told the token's kind");
            Map<String, Object> c = claims(sql.header("Authorization").substring("Bearer ".length()));
            assertEquals("MYORG-ACCT.ETL_BOT", c.get("sub"));
            assertTrue(String.valueOf(c.get("iss")).startsWith("MYORG-ACCT.ETL_BOT.SHA256:"), String.valueOf(c.get("iss")));
        }
    }
}
