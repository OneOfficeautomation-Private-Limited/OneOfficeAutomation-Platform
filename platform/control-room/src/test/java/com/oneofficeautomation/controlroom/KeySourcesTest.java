package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.sdk.OoaException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** The vault master key from Azure Key Vault, AWS Secrets Manager, Google Secret Manager, or typed in at start (S7). */
class KeySourcesTest {
    static final String KEY = Base64.getEncoder().encodeToString(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32 });
    HttpServer server;
    String base;
    final List<String> seen = new CopyOnWriteArrayList<>();
    final Map<String, String> envVars = new HashMap<>();
    String azureLogin, azureImds, gcpMetadata, gcpSecrets, awsEndpoint;

    @BeforeEach void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().toString(), body;
            com.sun.net.httpserver.Headers h = ex.getRequestHeaders();
            seen.add(ex.getRequestMethod() + " " + path + " " + (h.containsKey("Authorization") ? h.getFirst("Authorization") : "") + " " + String.join(",", h.keySet()));
            if (path.startsWith("/metadata/identity/oauth2/token") && "true".equals(h.getFirst("Metadata"))) body = "{\"access_token\":\"azure-mi-token\"}";
            else if (path.startsWith("/tenant-1/oauth2/v2.0/token")) body = "{\"access_token\":\"azure-sp-token\"}";
            else if (path.startsWith("/secrets/ooa-key") && h.getFirst("Authorization") != null && h.getFirst("Authorization").startsWith("Bearer azure-")) body = "{\"value\":\"" + KEY + "\"}";
            else if (path.startsWith("/computeMetadata/v1/instance/service-accounts/default/token") && "Google".equals(h.getFirst("Metadata-Flavor"))) body = "{\"access_token\":\"gcp-token\"}";
            else if (path.equals("/v1/projects/p1/secrets/ooa/versions/latest:access") && "Bearer gcp-token".equals(h.getFirst("Authorization")))
                body = "{\"payload\":{\"data\":\"" + Base64.getEncoder().encodeToString(KEY.getBytes(StandardCharsets.UTF_8)) + "\"}}";
            else if (path.equals("/aws/") && "secretsmanager.GetSecretValue".equals(h.getFirst("X-amz-target"))
                    && h.getFirst("Authorization").startsWith("AWS4-HMAC-SHA256 Credential=AKIDTEST/") && h.getFirst("Authorization").contains("/eu-west-1/secretsmanager/aws4_request")) {
                String req = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                body = req.contains("\"SecretId\":\"ooa/master-key\"") ? "{\"SecretString\":\"" + KEY + "\"}" : "{}";
            } else { ex.sendResponseHeaders(403, -1); ex.close(); return; }
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        azureLogin = KeySources.azureLogin; azureImds = KeySources.azureImds; gcpMetadata = KeySources.gcpMetadata; gcpSecrets = KeySources.gcpSecrets; awsEndpoint = KeySources.awsEndpoint;
        KeySources.azureLogin = base; KeySources.azureImds = base; KeySources.gcpMetadata = base; KeySources.gcpSecrets = base; KeySources.awsEndpoint = base + "/aws/";
        KeySources.env = envVars::get;
    }

    @AfterEach void tearDown() {
        server.stop(0);
        KeySources.azureLogin = azureLogin; KeySources.azureImds = azureImds; KeySources.gcpMetadata = gcpMetadata; KeySources.gcpSecrets = gcpSecrets; KeySources.awsEndpoint = awsEndpoint;
        KeySources.env = System::getenv;
    }

    @Test void azureKeyVaultWithTheManagedIdentityOrAServicePrincipal() {
        assertArrayEquals(Base64.getDecoder().decode(KEY), KeySources.fetch("azure-keyvault:" + base + "/secrets/ooa-key"));
        assertTrue(seen.stream().anyMatch(s -> s.contains("Bearer azure-mi-token")), seen.toString());
        envVars.putAll(Map.of("AZURE_TENANT_ID", "tenant-1", "AZURE_CLIENT_ID", "app", "AZURE_CLIENT_SECRET", "s3cret"));
        seen.clear();
        assertArrayEquals(Base64.getDecoder().decode(KEY), KeySources.fetch("azure-keyvault:" + base + "/secrets/ooa-key"));
        assertTrue(seen.stream().anyMatch(s -> s.contains("Bearer azure-sp-token")), seen.toString());
        assertEquals("CR.VAULT_KEY", assertThrows(OoaException.class, () -> KeySources.fetch("azure-keyvault:http://elsewhere.example/secrets/x")).code(), "https only");
    }

    @Test void googleSecretManagerWithTheMachinesServiceAccount() {
        assertArrayEquals(Base64.getDecoder().decode(KEY), KeySources.fetch("gcp-secret:projects/p1/secrets/ooa/versions/latest"));
        assertThrows(OoaException.class, () -> KeySources.fetch("gcp-secret:ooa"));
    }

    @Test void awsSecretsManagerSignedWithSignatureVersion4() {
        envVars.putAll(Map.of("AWS_ACCESS_KEY_ID", "AKIDTEST", "AWS_SECRET_ACCESS_KEY", "secret"));
        assertArrayEquals(Base64.getDecoder().decode(KEY), KeySources.fetch("aws-secrets:eu-west-1/ooa/master-key"));
        assertThrows(OoaException.class, () -> KeySources.fetch("aws-secrets:not-a-region/x"));
    }

    @Test void signatureVersion4MatchesAwsPublishedExample() throws Exception {
        // the example request in AWS's Signature Version 4 documentation (IAM ListUsers, 2015-08-30)
        Map<String, String> h = new TreeMap<>(Map.of("content-type", "application/x-www-form-urlencoded; charset=utf-8", "host", "iam.amazonaws.com", "x-amz-date", "20150830T123600Z"));
        String auth = KeySources.sigV4("GET", "/", "Action=ListUsers&Version=2010-05-08", h, "", "us-east-1", "iam", "AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY", "20150830T123600Z");
        assertEquals("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/iam/aws4_request, SignedHeaders=content-type;host;x-amz-date, "
            + "Signature=5d672d79c15b13162d9279b0855cfba6789a8edb4c82c400e06b5924a6f2b5d7", auth);
    }

    @Test void manualUnlockAndBadKeys() throws Exception {
        var before = KeySources.prompt;
        try {
            KeySources.prompt = KEY::toCharArray;
            assertArrayEquals(Base64.getDecoder().decode(KEY), KeySources.fetch("prompt"));
            KeySources.prompt = "too-short"::toCharArray;
            assertEquals("CR.VAULT_KEY", assertThrows(OoaException.class, () -> KeySources.fetch("prompt")).code());
            assertThrows(OoaException.class, () -> KeySources.fetch("hsm:slot0"));

            // the vault takes its key from the source and keeps no key file
            KeySources.prompt = KEY::toCharArray;
            envVars.put("OOA_MASTER_KEY_SOURCE", "prompt");
            Path data = Files.createTempDirectory("ooa-keysource");
            Vault v = new Vault(data);
            assertNull(v.keyFile);
            assertFalse(Files.exists(data.resolve("master.key")));
            assertEquals("typed in at start (manual unlock)", v.keySource);
            assertEquals("secret", v.decrypt(v.encrypt("secret", "x"), "x"));
        } finally { KeySources.prompt = before; }
    }
}
