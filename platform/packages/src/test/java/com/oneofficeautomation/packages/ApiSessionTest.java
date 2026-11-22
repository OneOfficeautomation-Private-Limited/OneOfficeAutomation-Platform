package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** The shared sign-in session of web-API toolkits: kept by the sign-in step, used by later steps that leave fields empty. */
class ApiSessionTest {
    static ActionContext ctx() throws Exception { return PackagesTestSupport.ctx(Files.createTempDirectory("ooa-apisession"), Map.of()); }

    @Test void laterStepsTakeWhatTheyLeaveEmpty() throws Exception {
        ActionContext ctx = ctx();
        SecureString tok = new SecureString("t-1", "test");
        ApiSession.open(ctx, "", "salesforce", "salesforce", Map.of("instance_url", "https://x.my.salesforce.com", "access_token", tok), false);
        ApiSession s = ApiSession.find(ctx, null, "salesforce", "salesforce");
        assertNotNull(s, "the default-named session is used when the step names none");
        assertEquals("https://x.my.salesforce.com", ApiSession.text(s, "instance_url", ""));
        assertEquals("https://own", ApiSession.text(s, "instance_url", "https://own"), "what the step fills wins");
        assertSame(tok, ApiSession.value(s, "access_token", null));
        assertFalse(s.toString().contains("t-1"), "secrets never show");
    }

    @Test void aNamedSessionMustBeOpenAndOfThisToolkit() throws Exception {
        ActionContext ctx = ctx();
        assertNull(ApiSession.find(ctx, "", "okta", "okta"), "no session and none named: the step works as today");
        assertEquals("OKTA.NO_SESSION", assertThrows(ActionException.class, () -> ApiSession.find(ctx, "hr", "okta", "okta")).code());
        ApiSession.open(ctx, "hr", "workday", "workday", Map.of("host", "h"), true);
        assertEquals("OKTA.WRONG_SESSION", assertThrows(ActionException.class, () -> ApiSession.find(ctx, "hr", "okta", "okta")).code());
        assertTrue(((Set<?>) ctx.sessions().get(ActionContext.SHARED_SESSIONS)).contains("hr"), "shared with agents this one runs");
        assertEquals("X.NO_SIGN_IN", assertThrows(ActionException.class, () -> ApiSession.require("X", null, "instance URL")).code());
    }

    @Test void closingEndsTheSessionAndWhatItHolds() throws Exception {
        ActionContext ctx = ctx();
        AtomicBoolean closed = new AtomicBoolean();
        ApiSession.open(ctx, "k", "kafka", new ApiSession("kafka", Map.of("a", "b"), () -> closed.set(true)), false);
        assertTrue(ApiSession.close(ctx, "k", "kafka"));
        assertTrue(closed.get());
        assertNull(ctx.sessions().get("k"));
        assertFalse(ApiSession.close(ctx, "k", "kafka"), "closing twice is harmless");
    }
}
