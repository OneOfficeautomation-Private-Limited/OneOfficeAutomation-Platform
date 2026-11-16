package com.oneofficeautomation.packages;

import com.oneofficeautomation.packages.Microsoft365GapsTest.Fake;
import com.oneofficeautomation.sdk.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static com.oneofficeautomation.packages.Microsoft365GapsTest.*;
import static com.oneofficeautomation.packages.PackagesTest.tmp;
import static org.junit.jupiter.api.Assertions.*;

/** Entra ID users and groups (real-world tests finding 3: agents used to call Graph through the REST toolkit). */
class Microsoft365EntraTest {
    final Microsoft365Package pkg = new Microsoft365Package();
    final SecureString tok = new SecureString("tok-123", "t");
    static final String G1 = "11111111-2222-3333-4444-555555555555", U1 = "0b1c2d3e-4f50-6172-8394-a5b6c7d8e9f0";

    @Test void findUsersFiltersAndFollowsEveryPage() throws Exception {
        try (Fake f = new Fake()) {
            String b = f.base();
            f.at("GET", "/v1.0/users", c -> c.q().contains("skiptoken")
                    ? ok("{\"value\":[{\"id\":\"u3\",\"displayName\":\"Cara Ng\",\"userPrincipalName\":\"qa.cara.ng@x.test\",\"accountEnabled\":true}]}")
                    : ok("{\"value\":[{\"id\":\"u1\",\"displayName\":\"Ann Lee\",\"userPrincipalName\":\"qa.ann.lee@x.test\",\"department\":\"QA\",\"accountEnabled\":true,"
                         + "\"signInActivity\":{\"lastSignInDateTime\":\"2026-11-01T08:00:00Z\"}},{\"id\":\"u2\",\"displayName\":\"Ben Ray\",\"accountEnabled\":false}],"
                         + "\"@odata.nextLink\":\"" + b + "/users?$skiptoken=p2\"}"));
            ActionContext ctx = Microsoft365GapsTest.ctx(tmp());
            Map<String, Object> o = pkg.entraFindUsers(ctx, tok, "qa.", "QA", true, true, null, 1000, b);
            assertEquals(BigDecimal.valueOf(3), o.get("count"), "both pages");
            List<List<Object>> r = rows(o.get("table"));
            assertEquals(List.of("u1", "Ann Lee", "qa.ann.lee@x.test", "", "QA", "", true, "2026-11-01T08:00:00Z").toString().replace("\"", ""),
                    Arrays.asList(r.get(0).get(0), r.get(0).get(1), r.get(0).get(2), Objects.toString(r.get(0).get(3), ""), r.get(0).get(4),
                            Objects.toString(r.get(0).get(5), ""), r.get(0).get(6), r.get(0).get(7)).toString());
            String q = f.all("GET", "/v1.0/users").get(0).q();
            assertTrue(q.contains("startswith(userPrincipalName,'qa.') or startswith(displayName,'qa.')"), q);
            assertTrue(q.contains("department eq 'QA'") && q.contains("accountEnabled eq true") && q.contains("signInActivity"), q);

            f.calls.clear();
            pkg.entraFindUsers(ctx, tok, "O'Brien", null, false, false, null, 1, b);
            String q2 = f.all("GET", "/v1.0/users").get(0).q();
            assertTrue(q2.contains("'O''Brien'"), "quotes doubled for OData: " + q2);
            assertFalse(q2.contains("signInActivity"), "sign-in only when asked (it needs another permission)");
            assertEquals(1, f.calls.size(), "max reached on the first page");
        }
    }

    @Test void getUserReadsTheManagerAndToleratesNone() throws Exception {
        try (Fake f = new Fake()) {
            f.at("GET", "/v1.0/users/ann%40x.test", c -> ok("{\"id\":\"" + U1 + "\",\"displayName\":\"Ann\",\"userPrincipalName\":\"ann@x.test\",\"accountEnabled\":false,"
                    + "\"onPremisesDistinguishedName\":\"CN=Ann,OU=QA,DC=x\"}"));
            f.at("GET", "/v1.0/users/ann%40x.test/manager", c -> new Object[]{404, "{\"error\":{\"code\":\"Request_ResourceNotFound\",\"message\":\"no manager\"}}"});
            Map<String, Object> o = pkg.entraGetUser(Microsoft365GapsTest.ctx(tmp()), tok, "ann@x.test", false, f.base());
            Map<?, ?> u = (Map<?, ?>) o.get("user");
            assertEquals(false, o.get("enabled"));
            assertEquals("CN=Ann,OU=QA,DC=x", u.get("on_premises_dn"));
            assertNull(u.get("manager"), "no manager is not an error");

            f.at("GET", "/v1.0/users/ann%40x.test/manager", c -> ok("{\"userPrincipalName\":\"boss@x.test\"}"));
            assertEquals("boss@x.test", ((Map<?, ?>) pkg.entraGetUser(Microsoft365GapsTest.ctx(tmp()), tok, "ann@x.test", false, f.base()).get("user")).get("manager"));
        }
    }

    @Test void createUserSendsTheFirstPasswordAndRefusesBadInput() throws Exception {
        try (Fake f = new Fake()) {
            f.at("POST", "/v1.0/users", c -> new Object[]{201, "{\"id\":\"" + U1 + "\"}"});
            ActionContext ctx = Microsoft365GapsTest.ctx(tmp());
            SecureString pw = new SecureString("Qa!Pass-1234", "p");
            Map<String, Object> o = pkg.entraCreateUser(ctx, tok, "Ann Lee", "ann.lee@x.test", pw, true, null, "QA", null, true, Map.of("usageLocation", "IN"), f.base());
            assertEquals(U1, o.get("user_id"));
            Map<String, Object> body = f.last("POST", "/v1.0/users").json();
            assertEquals("ann.lee", body.get("mailNickname"), "alias from the sign-in name");
            assertEquals("IN", body.get("usageLocation"));
            assertEquals(Map.of("forceChangePasswordNextSignIn", true, "password", "Qa!Pass-1234"), body.get("passwordProfile"));
            f.calls.clear();
            assertEquals("M365.BAD_INPUT", assertThrows(ActionException.class, () -> pkg.entraCreateUser(ctx, tok, "Ann", "ann", pw, true, null, null, null, true, null, f.base())).code());
            assertEquals("M365.BAD_INPUT", assertThrows(ActionException.class, () -> pkg.entraCreateUser(ctx, tok, "Ann", "a@x.test", null, true, null, null, null, true, null, f.base())).code());
            assertTrue(f.calls.isEmpty(), "input errors send nothing");
        }
    }

    @Test void updateUserEnablesOrDisablesAndNeedsSomethingToChange() throws Exception {
        try (Fake f = new Fake()) {
            f.at("PATCH", "/v1.0/users/" + U1, c -> none());
            ActionContext ctx = Microsoft365GapsTest.ctx(tmp());
            pkg.entraUpdateUser(ctx, tok, U1, "disable", null, "Lead", null, f.base());
            assertEquals(Map.of("accountEnabled", false, "jobTitle", "Lead"), f.last("PATCH", "/v1.0/users/" + U1).json());
            pkg.entraUpdateUser(ctx, tok, U1, "enable", null, null, null, f.base());
            assertEquals(Map.of("accountEnabled", true), f.last("PATCH", "/v1.0/users/" + U1).json());
            assertEquals("M365.BAD_INPUT", assertThrows(ActionException.class, () -> pkg.entraUpdateUser(ctx, tok, U1, "unchanged", " ", null, null, f.base())).code());
        }
    }

    @Test void groupsOfAUserDirectOrNestedSkipRoles() throws Exception {
        try (Fake f = new Fake()) {
            String two = "{\"value\":[{\"@odata.type\":\"#microsoft.graph.group\",\"id\":\"g1\",\"displayName\":\"GRP-QA-Support\",\"securityEnabled\":true},"
                    + "{\"@odata.type\":\"#microsoft.graph.directoryRole\",\"id\":\"r1\",\"displayName\":\"Helpdesk Administrator\"}]}";
            f.at("GET", "/v1.0/users/" + U1 + "/memberOf", c -> ok(two));
            f.at("GET", "/v1.0/users/" + U1 + "/transitiveMemberOf", c -> ok(two.replace("]}", ",{\"@odata.type\":\"#microsoft.graph.group\",\"id\":\"g2\",\"displayName\":\"GRP-QA-Admins\"}]}")));
            ActionContext ctx = Microsoft365GapsTest.ctx(tmp());
            assertEquals(List.of("GRP-QA-Support"), pkg.entraUserGroups(ctx, tok, U1, false, f.base()).get("names"), "a directory role is not a group");
            assertEquals(List.of("GRP-QA-Support", "GRP-QA-Admins"), pkg.entraUserGroups(ctx, tok, U1, true, f.base()).get("names"), "nested groups too");
        }
    }

    @Test void membersOfAGroupFoundByName() throws Exception {
        try (Fake f = new Fake()) {
            f.at("GET", "/v1.0/groups", c -> c.q().contains("'GRP-QA-Twice'")
                    ? ok("{\"value\":[{\"id\":\"a\"},{\"id\":\"b\"}]}")
                    : c.q().contains("'GRP-QA-None'") ? ok("{\"value\":[]}") : ok("{\"value\":[{\"id\":\"" + G1 + "\",\"displayName\":\"GRP-QA-Support\"}]}"));
            f.at("GET", "/v1.0/groups/" + G1 + "/members", c -> ok("{\"value\":[{\"@odata.type\":\"#microsoft.graph.user\",\"id\":\"u1\",\"displayName\":\"Ann\","
                    + "\"userPrincipalName\":\"ann@x.test\"},{\"@odata.type\":\"#microsoft.graph.group\",\"id\":\"g9\",\"displayName\":\"Nested\"}]}"));
            ActionContext ctx = Microsoft365GapsTest.ctx(tmp());
            Map<String, Object> o = pkg.entraGroupMembers(ctx, tok, "GRP-QA-Support", false, f.base());
            assertEquals(List.of(List.of("u1", "user", "Ann", "ann@x.test")), rows(o.get("table")).subList(0, 1));
            assertEquals("group", rows(o.get("table")).get(1).get(1));
            assertEquals("M365.AMBIGUOUS", assertThrows(ActionException.class, () -> pkg.entraGroupMembers(ctx, tok, "GRP-QA-Twice", false, f.base())).code());
            assertEquals("M365.NOT_FOUND", assertThrows(ActionException.class, () -> pkg.entraGroupMembers(ctx, tok, "GRP-QA-None", false, f.base())).code());
        }
    }

    @Test void addingAndRemovingMembersIsSafeToRepeat() throws Exception {
        try (Fake f = new Fake()) {
            String ref = "/v1.0/groups/" + G1 + "/members/$ref";
            boolean[] already = {false};
            f.at("GET", "/v1.0/users/ann%40x.test", c -> ok("{\"id\":\"" + U1 + "\"}"));
            f.at("POST", ref, c -> already[0]
                    ? new Object[]{400, "{\"error\":{\"code\":\"Request_BadRequest\",\"message\":\"One or more added object references already exist for the following modified properties: 'members'.\"}}"}
                    : none());
            f.at("DELETE", "/v1.0/groups/" + G1 + "/members/" + U1 + "/$ref", c -> already[0] ? none() : new Object[]{404, "{\"error\":{\"message\":\"not a member\"}}"});
            ActionContext ctx = Microsoft365GapsTest.ctx(tmp());
            assertEquals(true, pkg.entraAddGroupMember(ctx, tok, G1, "ann@x.test", f.base()).get("added"));
            assertTrue(String.valueOf(f.last("POST", ref).json().get("@odata.id")).endsWith("/directoryObjects/" + U1), "member by its object id");
            already[0] = true;
            assertEquals(false, pkg.entraAddGroupMember(ctx, tok, G1, U1, f.base()).get("added"), "already a member: no change, no error");
            assertEquals(true, pkg.entraRemoveGroupMember(ctx, tok, G1, U1, f.base()).get("removed"));
            already[0] = false;
            assertEquals(false, pkg.entraRemoveGroupMember(ctx, tok, G1, U1, f.base()).get("removed"), "not a member: no change, no error");
        }
    }

    @Test void findGroupsAndSignOutEverywhere() throws Exception {
        try (Fake f = new Fake()) {
            f.at("GET", "/v1.0/groups", c -> ok("{\"value\":[{\"id\":\"g1\",\"displayName\":\"GRP-QA-Support\",\"securityEnabled\":true}]}"));
            f.at("POST", "/v1.0/users/" + U1 + "/revokeSignInSessions", c -> ok("{\"value\":true}"));
            ActionContext ctx = Microsoft365GapsTest.ctx(tmp());
            Map<String, Object> o = pkg.entraFindGroups(ctx, tok, "GRP-QA-", true, null, 100, f.base());
            assertEquals(BigDecimal.ONE, o.get("count"));
            String q = f.all("GET", "/v1.0/groups").get(0).q();
            assertTrue(q.contains("startswith(displayName,'GRP-QA-') and securityEnabled eq true"), q);
            pkg.entraSignOutEverywhere(ctx, tok, U1, f.base());
            assertNotNull(f.last("POST", "/v1.0/users/" + U1 + "/revokeSignInSessions"));
        }
    }
}
