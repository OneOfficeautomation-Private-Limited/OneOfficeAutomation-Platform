package com.oneofficeautomation.packages;

import com.oneofficeautomation.packages.ConnectorsTest.Fake;
import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import com.oneofficeautomation.sdk.SecureString;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.*;

import static com.oneofficeautomation.packages.ConnectorsTest.ok;
import static org.junit.jupiter.api.Assertions.*;

/** G6: a sign-in step keeps a session; later steps leave the address and token empty and use it. */
class ApiSessionsG6Test {
    static ActionContext ctx() throws Exception { return PackagesTestSupport.ctx(Files.createTempDirectory("ooa-g6"), Map.of()); }
    static String code(org.junit.jupiter.api.function.Executable e) { return assertThrows(ActionException.class, e).code(); }

    @Test void salesforceSignInKeepsASessionForLaterSteps() throws Exception {
        try (Fake f = new Fake()) {
            String inst = f.url();
            f.on("POST /services/oauth2/token", c -> ok("{\"access_token\":\"00D!session\",\"instance_url\":\"" + inst + "\"}"))
             .on("GET /services/data/v61.0/query", c -> ok("{\"done\":true,\"totalSize\":0,\"records\":[]}"));
            SalesforcePackage sf = new SalesforcePackage();
            ActionContext ctx = ctx();
            assertEquals("SALESFORCE.NO_SIGN_IN", code(() -> sf.findRecords(ctx, null, null, "Contact", null, List.of("Id"), null, 10, false, "v61.0")),
                    "no address and no session: a clear message");
            sf.authenticate(ctx, f.url(), null, "3MVG9", new SecureString("cs-1", "t"), null, null, null, null, false);
            sf.findRecords(ctx, null, null, "Contact", null, List.of("Id"), null, 10, false, "v61.0");
            assertEquals("Bearer 00D!session", f.last("GET", "/services/data/v61.0/query").auth());
            assertEquals(Map.of("closed", true), sf.endSessionSalesforce(ctx, null));
            assertEquals("SALESFORCE.NO_SIGN_IN", code(() -> sf.findRecords(ctx, null, null, "Contact", null, List.of("Id"), null, 10, false, "v61.0")));
        }
    }

    @Test void oktaConnectAndANamedSession() throws Exception {
        String user = "{\"id\":\"00uABCDEFGHIJKLMNOPQ\",\"status\":\"ACTIVE\",\"profile\":{\"login\":\"ann@acme.com\",\"email\":\"ann@acme.com\"}}";
        try (Fake f = new Fake()) {
            f.on("GET /api/v1/users/ann%40acme.com", c -> ok(user));
            OktaPackage okta = new OktaPackage();
            ActionContext ctx = ctx();
            okta.connectOkta(ctx, f.url(), new SecureString("api-1", "t"), null, null, false);
            assertEquals("ann@acme.com", ((Map<?, ?>) okta.getUser(ctx, null, "ann@acme.com", null, null).get("user")).get("login"));
            assertEquals("SSWS api-1", f.last("GET", "/api/v1/users/ann%40acme.com").auth());
            assertTrue(okta.getUser(ctx, f.url(), "ann@acme.com", new SecureString("api-2", "t"), null).containsKey("user"));
            assertEquals("SSWS api-2", f.last("GET", "/api/v1/users/ann%40acme.com").auth(), "a step's own token wins");
            assertEquals(Map.of("closed", true), okta.endSessionOkta(ctx, ""));
            assertEquals(Map.of("closed", false), okta.endSessionOkta(ctx, ""));
            ctx.sessions().put("other", "not a session");
            assertEquals(Map.of("closed", false), okta.endSessionOkta(ctx, "other"));
        }
    }
}
