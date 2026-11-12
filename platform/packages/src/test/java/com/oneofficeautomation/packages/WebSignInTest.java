package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.RunResult;
import com.oneofficeautomation.sdk.ActionException;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static com.oneofficeautomation.packages.ConnectorsTest.*;
import static com.oneofficeautomation.packages.PackagesTest.tmp;
import static org.junit.jupiter.api.Assertions.*;

/** R2 (command review, batch 8): one sign-in for every web step, and addresses relative to base_url. */
class WebSignInTest {
    static final String OAUTH = "\"oauth_token_url\":\"%s/token\",\"oauth_client_id\":\"app\",\"oauth_client_secret\":\"$tok\"";

    @Test void uploadDownloadAndSoapTakeTheSameSignIn() throws Exception {
        try (Fake f = new Fake()) {
            f.on("POST /token", c -> ok("{\"access_token\":\"AT-1\",\"expires_in\":3600}"))
             .on("POST /api/files", c -> ok("{\"id\":\"F1\"}"))
             .on("GET /api/files/F1", c -> ok("file content"))
             .on("POST /svc/orders", c -> new Object[]{200, "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body><r>ok</r></soap:Body></soap:Envelope>"});
            Path w = tmp();
            Files.writeString(w.resolve("a.txt"), "hello");
            String base = "\"base_url\":\"" + f.url() + "/api\"";
            // upload with OAuth 2.0 and a relative address: the file part and the token both arrive
            RunResult up = runRawWithToken(w, "[" + TOKEN_VAR + "]", "[{\"id\":\"u\",\"action\":\"ooa.rest/upload_file\",\"args\":{\"url\":\"/files\",\"path\":\"a.txt\","
                    + base + "," + OAUTH.formatted(f.url()) + "}}]");
            assertTrue(up.ok(), String.valueOf(up.error()));
            Call post = f.last("POST", "/api/files");
            assertEquals("Bearer AT-1", post.header("Authorization"));
            assertTrue(post.text().contains("filename=\"a.txt\"") && post.text().contains("hello"), post.text());
            // download with OAuth 2.0
            RunResult down = runRawWithToken(w, "[" + TOKEN_VAR + "]", "[{\"id\":\"d\",\"action\":\"ooa.rest/download_file\",\"args\":{\"url\":\"/files/F1\",\"path\":\"got.txt\","
                    + base + "," + OAUTH.formatted(f.url()) + "}}]");
            assertTrue(down.ok(), String.valueOf(down.error()));
            assertEquals("Bearer AT-1", f.last("GET", "/api/files/F1").header("Authorization"));
            assertEquals("file content", Files.readString(w.resolve("got.txt")));
            // SOAP with a bearer token and base_url
            RunResult soap = runRawWithToken(w, "[" + TOKEN_VAR + "]", "[{\"id\":\"s\",\"action\":\"ooa.soap/send\",\"args\":{\"url\":\"/orders\",\"body\":\"<q/>\","
                    + "\"base_url\":\"" + f.url() + "/svc/\",\"bearer_token\":\"$tok\"}}]");
            assertTrue(soap.ok(), String.valueOf(soap.error()));
            assertEquals("Bearer tok-123", f.last("POST", "/svc/orders").header("Authorization"));
        }
    }

    @Test void addressesAndConflicts() {
        assertEquals("https://api.x.test/v2/items/1", WebSignIn.address("REST", "https://api.x.test/v2/", "/items/1"));
        assertEquals("https://api.x.test/v2/items?a=1", WebSignIn.address("REST", "https://api.x.test/v2", "items?a=1"));
        assertEquals("https://other.test/x", WebSignIn.address("REST", "https://api.x.test", "https://other.test/x"), "a full address wins");
        assertEquals("REST.BAD_URL", assertThrows(ActionException.class, () -> WebSignIn.address("REST", null, "/items")).code());
        assertEquals("SOAP.BAD_AUTH", assertThrows(ActionException.class, () -> WebSignIn.of("SOAP", "bob", null, null, null, "https://t", "id", null, null, null, null,
                null, null, null, null, null, null, null, null, null, null)).code(), "two sign-ins at once are refused, as Send request always did");
    }
}
