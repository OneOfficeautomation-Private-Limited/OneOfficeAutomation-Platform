package com.oneofficeautomation.packages;

import com.oneofficeautomation.packages.ConnectorsTest.Call;
import com.oneofficeautomation.packages.ConnectorsTest.Fake;
import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.SecureString;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static com.oneofficeautomation.packages.ConnectorsTest.ok;
import static com.oneofficeautomation.packages.ConnectorsTest.q;
import static com.oneofficeautomation.packages.RestToolkitTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Okta toolkit against a local imitation of the Okta Management API. */
class OktaPackageTest {
    static final String UID = "00uABCDEFGHIJKLMNOPQ";
    static final String GID = "00gABCDEFGHIJKLMNOPQ";
    static final String USER = "{\"id\":\"" + UID + "\",\"status\":\"ACTIVE\",\"created\":\"2026-01-02T03:04:05.000Z\",\"lastLogin\":null,"
            + "\"profile\":{\"login\":\"ann.lee@acme.com\",\"email\":\"ann.lee@acme.com\",\"firstName\":\"Ann\",\"lastName\":\"Lee\",\"department\":\"Finance\"}}";
    final OktaPackage pkg = new OktaPackage();

    @Test void createGetUpdateUserWithSswsToken() throws Exception {
        try (Fake f = new Fake()) {
            f.on("POST /api/v1/users", c -> ok(USER))
             .on("GET /api/v1/users/ann.lee%40acme.com", c -> ok(USER))
             .on("POST /api/v1/users/" + UID, c -> ok(USER.replace("Finance", "Sales")));
            ActionContext ctx = ctx(PackagesTest.tmp());
            Map<String, Object> r = pkg.createUser(ctx, f.url(), "Ann", "Lee", "ann.lee@acme.com", null, Map.of("department", "Finance"),
                    new SecureString("S3cret!pw", "p"), List.of(GID), true, TOKEN, null);
            assertEquals(UID, r.get("user_id"));
            assertEquals("ACTIVE", r.get("status"));
            Call c = f.last("POST", "/api/v1/users");
            assertEquals("SSWS tok-123", c.auth());
            assertEquals("activate=true", c.query());
            Map<String, Object> body = c.json();
            Map<?, ?> profile = (Map<?, ?>) body.get("profile");
            assertEquals("ann.lee@acme.com", profile.get("login"), "login defaults to the email");
            assertEquals("Finance", profile.get("department"));
            assertEquals("S3cret!pw", ((Map<?, ?>) ((Map<?, ?>) body.get("credentials")).get("password")).get("value"));
            assertEquals(List.of(GID), body.get("groupIds"));

            Map<String, Object> g = pkg.getUser(ctx, f.url(), "ann.lee@acme.com", TOKEN, null);
            Map<?, ?> u = (Map<?, ?>) g.get("user");
            assertEquals("Ann", u.get("first_name"));
            assertEquals("ACTIVE", u.get("status"));
            assertEquals("Finance", ((Map<?, ?>) g.get("profile")).get("department"));

            Map<String, Object> up = pkg.updateUser(ctx, f.url(), UID, Map.of("department", "Sales"), null, TOKEN);
            assertEquals("Bearer tok-123", f.last("POST", "/api/v1/users/" + UID).auth(), "access token wins over API token");
            assertEquals(Map.of("profile", Map.of("department", "Sales")), f.last("POST", "/api/v1/users/" + UID).json());
            assertEquals(UID, ((Map<?, ?>) up.get("user")).get("id"));
            fails("OKTA.NOTHING_TO_UPDATE", () -> pkg.updateUser(ctx, f.url(), UID, Map.of(), TOKEN, null));
            fails("OKTA.NO_TOKEN", () -> pkg.getUser(ctx, f.url(), UID, null, null));
        }
    }

    @Test void lifecycleActionsAndDelete() throws Exception {
        try (Fake f = new Fake()) {
            f.on("POST /api/v1/users/" + UID + "/lifecycle/activate", c -> ok("{\"activationUrl\":\"https://acme.okta.com/activate/xyz\"}"))
             .on("POST /api/v1/users/" + UID + "/lifecycle/reset_password", c -> ok("{\"resetPasswordUrl\":\"https://acme.okta.com/reset/abc\"}"))
             .on("POST /api/v1/users/" + UID + "/lifecycle/.*", c -> ok("{}"))
             .on("GET /api/v1/users/" + UID, c -> ok(USER))
             .on("DELETE /api/v1/users/" + UID, c -> new Object[]{204, ""});
            ActionContext ctx = ctx(PackagesTest.tmp());
            assertEquals("https://acme.okta.com/activate/xyz", pkg.activate(ctx, f.url(), UID, false, TOKEN, null).get("activation_url"));
            assertEquals("sendEmail=false", f.last("POST", ".*/activate").query());
            pkg.deactivate(ctx, f.url(), UID, false, TOKEN, null);
            pkg.suspend(ctx, f.url(), UID, TOKEN, null);
            assertNotNull(f.last("POST", ".*/lifecycle/suspend"));
            pkg.unsuspend(ctx, f.url(), UID, TOKEN, null);
            assertNotNull(f.last("POST", ".*/lifecycle/unsuspend"));
            assertEquals("https://acme.okta.com/reset/abc", pkg.resetPassword(ctx, f.url(), UID, false, TOKEN, null).get("reset_url"));
            assertEquals("sendEmail=false", f.last("POST", ".*/reset_password").query());

            int before = f.calls.size();
            pkg.deleteUser(ctx, f.url(), UID, TOKEN, null);
            List<String> seq = f.calls.subList(before, f.calls.size()).stream().map(c -> c.method() + " " + c.path()).toList();
            assertEquals(List.of("GET /api/v1/users/" + UID, "POST /api/v1/users/" + UID + "/lifecycle/deactivate", "DELETE /api/v1/users/" + UID), seq,
                    "an active user is deactivated before the delete");
            fails("OKTA.BAD_USER", () -> pkg.suspend(ctx, f.url(), " ", TOKEN, null));
        }
    }

    @Test void listUsersFollowsLinkHeaderPaging() throws Exception {
        try (Fake f = new Fake()) {
            String u2 = USER.replace(UID, "00uSECONDUSER0000000").replace("Ann", "Bob");
            f.on("GET /api/v1/users", c -> q(c).contains("after=xyz")
                    ? ok("[" + u2 + "]")
                    : new Object[]{200, "[" + USER + "]", Map.of("Link", "<" + f.url() + "/api/v1/users?after=xyz&limit=200>; rel=\"next\"")});
            Map<String, Object> r = pkg.listUsers(ctx(PackagesTest.tmp()), f.url(), null, "status eq \"ACTIVE\"", 100, TOKEN, null);
            assertEquals(new BigDecimal(2), r.get("count"));
            assertEquals(List.of("id", "status", "login", "email", "first_name", "last_name", "created", "last_login"), cols(r.get("table")));
            assertEquals("Bob", rows(r.get("table")).get(1).get(4));
            Call first = f.calls.get(0);
            assertEquals("search=status eq \"ACTIVE\"&limit=100", q(first));
        }
    }

    @Test void groupsByNameOrIdAndUserGroups() throws Exception {
        try (Fake f = new Fake()) {
            String group = "{\"id\":\"" + GID + "\",\"type\":\"OKTA_GROUP\",\"profile\":{\"name\":\"Finance Team\",\"description\":\"AP and AR\"}}";
            f.on("GET /api/v1/groups", c -> ok("[" + group + ",{\"id\":\"00gOTHER000000000000\",\"type\":\"OKTA_GROUP\",\"profile\":{\"name\":\"Finance Team Leads\"}}]"))
             .on("GET /api/v1/users/ann.lee%40acme.com/groups", c -> ok("[" + group + "]"))
             .on("GET /api/v1/users/.*", c -> ok(USER))
             .on("PUT /api/v1/groups/" + GID + "/users/" + UID, c -> new Object[]{204, ""})
             .on("DELETE /api/v1/groups/" + GID + "/users/" + UID, c -> new Object[]{204, ""});
            ActionContext ctx = ctx(PackagesTest.tmp());
            Map<String, Object> lg = pkg.listGroups(ctx, f.url(), "Fin", 50, TOKEN, null);
            assertEquals(new BigDecimal(2), lg.get("count"));
            assertEquals("q=Fin&limit=50", q(f.last("GET", "/api/v1/groups")));
            pkg.addToGroup(ctx, f.url(), "ann.lee@acme.com", "finance team", TOKEN, null);
            assertNotNull(f.last("PUT", "/api/v1/groups/" + GID + "/users/" + UID), "group found by exact name, user by login");
            pkg.removeFromGroup(ctx, f.url(), UID, GID, TOKEN, null);
            assertNotNull(f.last("DELETE", "/api/v1/groups/" + GID + "/users/" + UID));
            fails("OKTA.GROUP_NOT_FOUND", () -> pkg.addToGroup(ctx, f.url(), UID, "Nope", TOKEN, null));
            Map<String, Object> ug = pkg.userGroups(ctx, f.url(), "ann.lee@acme.com", TOKEN, null);
            assertEquals(List.of("Finance Team"), ug.get("names"));
            assertEquals(List.of(GID, "Finance Team", "AP and AR", "OKTA_GROUP"), rows(ug.get("table")).get(0));
        }
    }

    @Test void errorsMapToCodesAndRetryFlagThroughAnAgent() throws Exception {
        try (Fake f = new Fake()) {
            f.on("GET /api/v1/users/missing", c -> status(404, "{\"errorCode\":\"E0000007\",\"errorSummary\":\"Not found: Resource not found: missing (User)\"}"))
             .on("GET /api/v1/users/busy", c -> status(429, "{\"errorCode\":\"E0000047\",\"errorSummary\":\"API call exceeded rate limit due to too many requests.\"}"))
             .on("GET /api/v1/users/down", c -> status(503, "{\"errorSummary\":\"Service unavailable\"}"));
            String step = "[{\"id\":\"g\",\"action\":\"ooa.okta/get_user\",\"args\":{\"org_url\":\"" + f.url() + "\",\"user\":\"USER\",\"api_token\":\"$tok\"}}]";
            FlowRun nf = flow(PackagesTest.tmp(), "", step.replace("USER", "missing"));
            assertEquals("OKTA.NOT_FOUND", nf.result().error().get("code"));
            assertTrue(String.valueOf(nf.result().error().get("message")).contains("Resource not found"));
            assertEquals(false, nf.result().error().get("retryable"));
            assertEquals("SSWS tok-123", f.last("GET", "/api/v1/users/missing").auth());
            assertFalse(nf.events().contains("tok-123"), "token never reaches the run log");
            FlowRun rl = flow(PackagesTest.tmp(), "", step.replace("USER", "busy"));
            assertEquals("OKTA.RATE_LIMITED", rl.result().error().get("code"));
            assertEquals(true, rl.result().error().get("retryable"));
            FlowRun down = flow(PackagesTest.tmp(), "", step.replace("USER", "down"));
            assertEquals("OKTA.SERVER_ERROR", down.result().error().get("code"));
            assertEquals(true, down.result().error().get("retryable"));
        }
    }
}
