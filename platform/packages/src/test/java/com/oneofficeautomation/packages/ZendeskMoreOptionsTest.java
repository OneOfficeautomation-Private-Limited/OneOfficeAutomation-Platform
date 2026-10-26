package com.oneofficeautomation.packages;

import com.oneofficeautomation.packages.ConnectorsTest.Fake;
import com.oneofficeautomation.sdk.ActionContext;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static com.oneofficeautomation.packages.ConnectorsTest.ok;
import static com.oneofficeautomation.packages.ConnectorsTest.q;
import static com.oneofficeautomation.packages.RestToolkitTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Zendesk options: role and organization filters on Find users, custom fields on Create user and Create organization. */
class ZendeskMoreOptionsTest {
    final ZendeskPackage zd = new ZendeskPackage();
    static final String AGENT = "agent@acme.com";

    @Test void findUsersByRoleAndOrganization() throws Exception {
        try (Fake f = new Fake()) {
            String s = f.url();
            f.on("GET /api/v2/users/search", c -> ok("{\"users\":[{\"id\":7,\"name\":\"Ann\",\"email\":\"ann@acme.com\",\"role\":\"agent\",\"organization_id\":601}],\"next_page\":null}"));
            ActionContext ctx = ctx(PackagesTest.tmp());

            Map<String, Object> r = zd.findUsers(ctx, s, AGENT, TOKEN, null, "ann", 100, "Agent", "601");
            assertEquals(new BigDecimal(1), r.get("count"));
            assertEquals("query=ann role:agent organization:601", q(f.last("GET", "/api/v2/users/search")), "filters are added as search terms");

            zd.findUsers(ctx, s, AGENT, TOKEN, null, "", 100, "end-user", null);
            assertEquals("query=role:end-user", q(f.last("GET", "/api/v2/users/search")), "query may be empty when a filter is given");
            zd.findUsers(ctx, s, AGENT, TOKEN, null, null, 100, null, "602");
            assertEquals("query=organization:602", q(f.last("GET", "/api/v2/users/search")));
            zd.findUsers(ctx, s, AGENT, TOKEN, null, "pat@customer.com", 100);
            assertEquals("query=pat@customer.com", q(f.last("GET", "/api/v2/users/search")), "earlier signature unchanged");

            int calls = f.calls.size();
            fails("ZENDESK.BAD_INPUT", () -> zd.findUsers(ctx, s, AGENT, TOKEN, null, "ann", 100, "owner", null));
            fails("ZENDESK.BAD_ID", () -> zd.findUsers(ctx, s, AGENT, TOKEN, null, "ann", 100, null, "acme"));
            fails("ZENDESK.BAD_INPUT", () -> zd.findUsers(ctx, s, AGENT, TOKEN, null, " ", 100, null, null));
            assertEquals(calls, f.calls.size(), "invalid input is rejected before any request");
        }
    }

    @Test void createUserAndOrganizationWithCustomFields() throws Exception {
        try (Fake f = new Fake()) {
            String s = f.url();
            f.on("POST /api/v2/users", c -> new Object[]{201, "{\"user\":{\"id\":501}}"})
             .on("POST /api/v2/organizations", c -> new Object[]{201, "{\"organization\":{\"id\":601}}"});
            ActionContext ctx = ctx(PackagesTest.tmp());

            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("customer_tier", "gold");
            fields.put("renewal_date", "2026-12-31");
            assertEquals(new BigDecimal(501), zd.createUser(ctx, s, AGENT, TOKEN, null, "Pat Kim", "pat@customer.com", "end-user", null, null, null, false, fields).get("user_id"));
            Map<String, Object> body = f.last("POST", "/api/v2/users").json();
            assertEquals(Map.of("customer_tier", "gold", "renewal_date", "2026-12-31"), HttpJson.path(body, "user.user_fields"));
            assertEquals("Pat Kim", HttpJson.path(body, "user.name"));

            zd.createUser(ctx, s, AGENT, TOKEN, null, "Lee", null, "end-user", null, null, null, false);
            assertNull(HttpJson.path(f.last("POST", "/api/v2/users").json(), "user.user_fields"), "nothing sent when no fields are given");

            assertEquals(new BigDecimal(601), zd.createOrganization(ctx, s, AGENT, TOKEN, null, "Northwind", "northwind.com", null, null, null,
                    Map.of("account_tier", "enterprise")).get("organization_id"));
            Map<String, Object> org = f.last("POST", "/api/v2/organizations").json();
            assertEquals(Map.of("account_tier", "enterprise"), HttpJson.path(org, "organization.organization_fields"));
            assertEquals(List.of("northwind.com"), HttpJson.path(org, "organization.domain_names"));
            zd.createOrganization(ctx, s, AGENT, TOKEN, null, "Globex", null, null, null, null);
            assertNull(HttpJson.path(f.last("POST", "/api/v2/organizations").json(), "organization.organization_fields"));

            int calls = f.calls.size();
            fails("ZENDESK.BAD_INPUT", () -> zd.createUser(ctx, s, AGENT, TOKEN, null, "X", null, "end-user", null, null, null, false, Map.of("bad key", "v")));
            fails("ZENDESK.BAD_INPUT", () -> zd.createOrganization(ctx, s, AGENT, TOKEN, null, "X", null, null, null, null, Map.of("a/b", "v")));
            assertEquals(calls, f.calls.size(), "bad field keys are rejected before any request");
        }
    }
}
