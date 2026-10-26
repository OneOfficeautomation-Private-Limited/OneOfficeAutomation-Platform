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

/** Okta options added from the gap list: list_groups search expression. */
class OktaMoreOptionsTest {
    final OktaPackage pkg = new OktaPackage();

    @Test void listGroupsSendsSearchExpression() throws Exception {
        try (Fake f = new Fake()) {
            f.on("GET /api/v1/groups", c -> ok("[{\"id\":\"00gABCDEFGHIJKLMNOPQ\",\"type\":\"OKTA_GROUP\",\"profile\":{\"name\":\"Finance\",\"description\":\"AP\"}}]"));
            ActionContext ctx = ctx(PackagesTest.tmp());
            Map<String, Object> r = pkg.listGroups(ctx, f.url(), null, 50, TOKEN, null, "profile.name eq \"Finance\"");
            assertEquals(new BigDecimal(1), r.get("count"));
            assertEquals(List.of("00gABCDEFGHIJKLMNOPQ", "Finance", "AP", "OKTA_GROUP"), rows(r.get("table")).get(0));
            String query = q(f.last("GET", "/api/v1/groups"));
            assertTrue(query.contains("search=profile.name eq \"Finance\""), query);
            assertFalse(query.contains("q="), query);

            pkg.listGroups(ctx, f.url(), "Fin", 50, TOKEN, null);
            assertEquals("q=Fin&limit=50", q(f.last("GET", "/api/v1/groups")), "old signature still uses the name prefix");
        }
    }

    @Test void listGroupsRejectsBothPrefixAndSearch() throws Exception {
        try (Fake f = new Fake()) {
            ActionContext ctx = ctx(PackagesTest.tmp());
            fails("OKTA.BAD_INPUT", () -> pkg.listGroups(ctx, f.url(), "Fin", 50, TOKEN, null, "type eq \"OKTA_GROUP\""));
            assertTrue(f.calls.isEmpty(), "nothing sent when the inputs clash");
        }
    }
}
