package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.RunResult;
import org.junit.jupiter.api.Test;

import static com.oneofficeautomation.packages.ConnectorsTest.*;
import static com.oneofficeautomation.packages.PackagesTest.tmp;
import static org.junit.jupiter.api.Assertions.*;

/**
 * R1 (command review, batch 8): a secret from the vault reaches the server as the secret, wherever it is used. A
 * vault secret written as text is its mask, so a header taken from the vault used to be sent as ****.
 */
class RestVaultSecretsTest {
    static String step(String action, String url, String args) {
        return "{\"id\":\"s\",\"action\":\"" + action + "\",\"args\":{\"url\":\"" + url + "\"," + args + "}}";
    }

    @Test void headersQueryFormAndJsonCarryTheSecret() throws Exception {
        try (Fake f = new Fake()) {
            f.on("GET /v1/items", c -> ok("{}")).on("POST /v1/items", c -> ok("{}")).on("PUT /v1/items", c -> ok("{}"))
             .on("GET /files/a.txt", c -> ok("hello")).on("POST /soap", c -> new Object[]{200, "<Envelope><Body/></Envelope>"});
            String url = f.url() + "/v1/items";
            RunResult r = runRawWithToken(tmp(), "[" + TOKEN_VAR + "]", "[" + step("ooa.rest/request", url, "\"headers\":{\"X-Api-Key\":\"$tok\"},\"query\":{\"key\":\"$tok\"}") + "]");
            assertTrue(r.ok(), String.valueOf(r.error()));
            Call get = f.last("GET", "/v1/items");
            assertEquals("tok-123", get.header("X-Api-Key"));
            assertTrue(get.query().contains("key=tok-123"), get.query());

            assertTrue(runRawWithToken(tmp(), "[" + TOKEN_VAR + "]", "[" + step("ooa.rest/request", url, "\"method\":\"POST\",\"form\":{\"secret\":\"$tok\",\"n\":1}") + "]").ok());
            assertEquals("secret=tok-123&n=1", f.last("POST", "/v1/items").text());

            assertTrue(runRawWithToken(tmp(), "[" + TOKEN_VAR + "]", "[" + step("ooa.rest/request", url, "\"method\":\"PUT\",\"json\":{\"auth\":{\"key\":\"$tok\"},\"ids\":[\"$tok\"]}") + "]").ok());
            String json = f.last("PUT", "/v1/items").text();
            assertTrue(json.contains("\"key\":\"tok-123\"") && json.contains("[\"tok-123\"]") && !json.contains("*"), json);

            assertTrue(runRawWithToken(tmp(), "[" + TOKEN_VAR + "]", "[{\"id\":\"d\",\"action\":\"ooa.rest/download_file\",\"args\":{\"url\":\"" + f.url()
                    + "/files/a.txt\",\"path\":\"a.txt\",\"headers\":{\"X-Api-Key\":\"$tok\"}}}]").ok());
            assertEquals("tok-123", f.last("GET", "/files/a.txt").header("X-Api-Key"));

            assertTrue(runRawWithToken(tmp(), "[" + TOKEN_VAR + "]", "[" + step("ooa.soap/send", f.url() + "/soap", "\"body\":\"<x/>\",\"headers\":{\"X-Api-Key\":\"$tok\"}") + "]").ok());
            assertEquals("tok-123", f.last("POST", "/soap").header("X-Api-Key"));
        }
    }
}
