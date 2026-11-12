package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.CredentialVault;
import com.oneofficeautomation.runner.RunResult;
import com.oneofficeautomation.runner.Runner;
import com.oneofficeautomation.sdk.JsonLite;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static com.oneofficeautomation.packages.ConnectorsTest.*;
import static com.oneofficeautomation.packages.PackagesTest.REG;
import static com.oneofficeautomation.packages.PackagesTest.tmp;
import static org.junit.jupiter.api.Assertions.*;

/** A1 (command review, batch 7): a saved connection that signs in by itself and renews its token. */
class ConnectionSignInTest {
    static RunResult run(Path w, String connections, String steps) throws Exception {
        Files.writeString(w.resolve("connections.json"), connections);
        @SuppressWarnings("unchecked") Map<String, Object> flow = (Map<String, Object>) JsonLite.parse("{\"kind\":\"TaskBot\",\"id\":\"t\",\"name\":\"t\",\"variables\":"
                + "[{\"name\":\"n\",\"type\":\"Number\",\"direction\":\"output\"}],\"steps\":" + steps + "}");
        return Runner.builder(REG).workDir(w).service("vault", new CredentialVault.InMemory()
                .put("apps", "mail", "secret", "s3cret-value").put("apps", "mail", "refresh", "refresh-1")).build().run(flow, Map.of());
    }

    @Test void appSignInGivesEveryStepAFreshTokenAndRenewsIt() throws Exception {
        try (Fake f = new Fake()) {
            List<String> forms = Collections.synchronizedList(new ArrayList<>());
            int[] issued = {0};
            f.on("POST /tenant-1/oauth2/v2.0/token", c -> { forms.add(c.text()); issued[0]++;
                    // the first token runs out at once (expires within the renewal margin), so the second step gets a new one
                    return ok("{\"access_token\":\"at-" + issued[0] + "\",\"expires_in\":" + (issued[0] == 1 ? 30 : 3600) + ",\"token_type\":\"Bearer\"}"); })
             .on("GET /v1.0/me/mailFolders/inbox/messages", c -> ok("{\"value\":[]}"));
            String conn = "{\"mail\":{\"package\":\"ooa.microsoft365\",\"values\":{\"base_url\":\"" + f.url() + "/v1.0\",\"sign_in_provider\":\"microsoft\","
                    + "\"sign_in_grant\":\"client_credentials\",\"sign_in_tenant\":\"tenant-1\",\"sign_in_client_id\":\"app-9\","
                    + "\"sign_in_token_url\":\"" + f.url() + "/tenant-1/oauth2/v2.0/token\"},\"credentials\":{\"sign_in_client_secret\":\"apps/mail/secret\"}}}";
            String step = "{\"id\":\"%s\",\"action\":\"ooa.microsoft365/list_mail\",\"connection\":\"mail\",\"args\":{},\"out\":{\"count\":\"n\"}}";
            RunResult r = run(tmp(), conn, "[" + step.formatted("a") + "," + step.formatted("b") + "," + step.formatted("c") + "]");
            assertTrue(r.ok(), String.valueOf(r.error()));
            assertEquals(2, issued[0], "a token for the first step; renewed once because it was about to run out; reused for the third");
            String form = forms.get(0);
            assertTrue(form.contains("grant_type=client_credentials") && form.contains("client_id=app-9") && form.contains("client_secret=s3cret-value")
                    && form.contains("scope=https%3A%2F%2Fgraph.microsoft.com%2F.default"), form);
            assertEquals("Bearer at-2", f.last("GET", "/v1.0/me/mailFolders/inbox/messages").header("Authorization"));
        }
    }

    @Test void onePersonsSignInKeepsTheNewestRefreshTokenAndFailuresAreClear() throws Exception {
        try (Fake f = new Fake()) {
            List<String> forms = Collections.synchronizedList(new ArrayList<>());
            f.on("POST /token", c -> { forms.add(c.text());
                    return forms.size() == 1 ? ok("{\"access_token\":\"g-1\",\"expires_in\":1,\"refresh_token\":\"refresh-2\"}") : ok("{\"access_token\":\"g-2\",\"expires_in\":3600}"); })
             .on("POST /bad/token", c -> new Object[]{400, "{\"error\":\"invalid_grant\",\"error_description\":\"AADSTS70008: The refresh token has expired. refresh-1\"}"})
             .on("GET /gmail/v1/users/me/messages", c -> ok("{\"messages\":[]}"));
            String conn = "{\"g\":{\"package\":\"ooa.google\",\"values\":{\"endpoint_override\":\"" + f.url() + "\",\"sign_in_provider\":\"google\",\"sign_in_grant\":\"refresh_token\","
                    + "\"sign_in_client_id\":\"cid\",\"sign_in_token_url\":\"" + f.url() + "/token\"},\"credentials\":{\"sign_in_client_secret\":\"apps/mail/secret\","
                    + "\"sign_in_refresh_token\":\"apps/mail/refresh\"}},"
                    + "\"bad\":{\"package\":\"ooa.google\",\"values\":{\"endpoint_override\":\"" + f.url() + "\",\"sign_in_provider\":\"google\",\"sign_in_grant\":\"refresh_token\","
                    + "\"sign_in_client_id\":\"cid\",\"sign_in_token_url\":\"" + f.url() + "/bad/token\"},\"credentials\":{\"sign_in_refresh_token\":\"apps/mail/refresh\"}},"
                    + "\"half\":{\"package\":\"ooa.google\",\"values\":{\"sign_in_provider\":\"google\",\"sign_in_grant\":\"refresh_token\"},\"credentials\":{}}}";
            String step = "{\"id\":\"%s\",\"action\":\"ooa.google/gmail_list_messages\",\"connection\":\"%s\",\"args\":{},\"out\":{\"count\":\"n\"}}";
            RunResult r = run(tmp(), conn, "[" + step.formatted("a", "g") + "," + step.formatted("b", "g") + "]");
            assertTrue(r.ok(), String.valueOf(r.error()));
            assertTrue(forms.get(0).contains("grant_type=refresh_token") && forms.get(0).contains("refresh_token=refresh-1"), forms.get(0));
            assertTrue(forms.get(1).contains("refresh_token=refresh-2"), "the newest refresh token the server gave is used next: " + forms.get(1));

            RunResult bad = run(tmp(), conn, "[" + step.formatted("a", "bad") + "]");
            assertEquals("CONNECTION.SIGN_IN_FAILED", bad.error().get("code"));
            String msg = String.valueOf(bad.error().get("message"));
            assertTrue(msg.contains("refresh token has expired") && !msg.contains("refresh-1"), "the server's words, without the secret: " + msg);

            RunResult half = run(tmp(), conn, "[" + step.formatted("a", "half") + "]");
            assertEquals("CONNECTION.SIGN_IN_INCOMPLETE", half.error().get("code"));
        }
    }
}
