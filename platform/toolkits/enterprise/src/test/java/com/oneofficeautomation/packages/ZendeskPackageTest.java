package com.oneofficeautomation.packages;

import com.oneofficeautomation.packages.ConnectorsTest.Call;
import com.oneofficeautomation.packages.ConnectorsTest.Fake;
import com.oneofficeautomation.runner.RunResult;
import com.oneofficeautomation.sdk.ActionContext;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static com.oneofficeautomation.packages.ConnectorsTest.ok;
import static com.oneofficeautomation.packages.ConnectorsTest.q;
import static com.oneofficeautomation.packages.PackagesTest.*;
import static com.oneofficeautomation.packages.ToolkitTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Zendesk toolkit against a local server that imitates the Zendesk Support REST API. */
class ZendeskPackageTest {
    final ZendeskPackage zd = new ZendeskPackage();
    final List<String> log = new ArrayList<>();
    static final String AGENT = "agent@acme.com";
    static final String BASIC = "Basic " + Base64.getEncoder().encodeToString((AGENT + "/token:" + SECRET).getBytes(StandardCharsets.UTF_8));

    @Test void catalog() { checkCatalog("ooa.zendesk", 18); }

    @Test void siteAcceptsSubdomainOrAddress() {
        assertEquals("https://acme.zendesk.com", ZendeskPackage.site("acme"));
        assertEquals("https://acme.zendesk.com", ZendeskPackage.site("https://acme.zendesk.com/"));
        assertEquals("ZENDESK.BAD_URL", assertThrows(com.oneofficeautomation.sdk.ActionException.class, () -> ZendeskPackage.site("acme.zendesk.com/x y")).code());
        assertEquals("ZENDESK.BAD_URL", assertThrows(com.oneofficeautomation.sdk.ActionException.class, () -> ZendeskPackage.site("https://acme.zendesk.com/api")).code());
    }

    @Test void ticketsCreateGetUpdateDeleteCommentTags() throws Exception {
        try (Fake f = new Fake()) {
            String s = f.url();
            f.on("POST /api/v2/tickets", c -> new Object[]{201, "{\"ticket\":{\"id\":35436,\"status\":\"new\"}}"})
             .on("GET /api/v2/tickets/35436", c -> ok("{\"ticket\":{\"id\":35436,\"subject\":\"Refund\",\"status\":\"open\",\"priority\":\"high\",\"requester_id\":9,"
                    + "\"tags\":[\"refund\"],\"custom_fields\":[{\"id\":360012345678,\"value\":\"order_88123\"}]}}"))
             .on("PUT /api/v2/tickets/35436", c -> ok("{\"ticket\":{\"id\":35436}}"))
             .on("DELETE /api/v2/tickets/35436", c -> new Object[]{204, ""})
             .on("PUT /api/v2/tickets/35436/tags", c -> ok("{\"tags\":[\"refund\",\"vip\"]}"))
             .on("GET /api/v2/tickets/35436/comments", c -> q(c).contains("page[after]=xyz")
                    ? ok("{\"comments\":[{\"id\":2,\"author_id\":5,\"plain_body\":\"Done\",\"public\":false}],\"meta\":{\"has_more\":false}}")
                    : ok("{\"comments\":[{\"id\":1,\"author_id\":9,\"plain_body\":\"Where is my refund?\",\"public\":true}],\"meta\":{\"has_more\":true},"
                       + "\"links\":{\"next\":\"" + s + "/api/v2/tickets/35436/comments?page%5Bsize%5D=100&page%5Bafter%5D=xyz\"}}"));
            ActionContext ctx = ctx(tmp(), log);
            Map<String, Object> t = zd.createTicket(ctx, s, AGENT, TOKEN, null, "Refund", "Where is my refund?", "pat@customer.com", null, "high", "question", null,
                    "77", "88", "refund, agent", Map.of("360012345678", "order_88123"), false, List.of("upl1"));
            assertEquals(new BigDecimal(35436), t.get("ticket_id"));
            assertEquals(s + "/agent/tickets/35436", t.get("url"));
            Call c = f.last("POST", "/api/v2/tickets");
            assertEquals(BASIC, c.auth(), "email/token basic sign-in");
            Map<String, Object> body = json(c);
            assertEquals("Refund", HttpJson.path(body, "ticket.subject"));
            assertEquals("Where is my refund?", HttpJson.path(body, "ticket.comment.body"));
            assertEquals(false, HttpJson.path(body, "ticket.comment.public"));
            assertEquals(List.of("upl1"), HttpJson.path(body, "ticket.comment.uploads"));
            assertEquals(Map.of("email", "pat@customer.com", "name", "pat@customer.com"), HttpJson.path(body, "ticket.requester"));
            assertEquals(new BigDecimal(77), HttpJson.path(body, "ticket.assignee_id"));
            assertEquals(new BigDecimal(88), HttpJson.path(body, "ticket.group_id"));
            assertEquals(List.of("refund", "agent"), HttpJson.path(body, "ticket.tags"));
            assertEquals(List.of(Map.of("id", new BigDecimal("360012345678"), "value", "order_88123")), HttpJson.path(body, "ticket.custom_fields"));
            assertNull(HttpJson.path(body, "ticket.status"));

            Map<String, Object> got = zd.getTicket(ctx, s, AGENT, TOKEN, null, "35436");
            assertEquals("open", got.get("status"));
            Map<?, ?> tk = (Map<?, ?>) got.get("ticket");
            assertEquals(Map.of("360012345678", "order_88123"), tk.get("custom_fields"));
            assertEquals(List.of("refund"), tk.get("tags"));

            zd.updateTicket(ctx, s, AGENT, TOKEN, null, "35436", "pending", null, null, null, null, null, null, null);
            assertEquals(Map.of("ticket", Map.of("status", "pending")), json(f.last("PUT", "/api/v2/tickets/35436")));
            fails("ZENDESK.NOTHING_TO_UPDATE", () -> zd.updateTicket(ctx, s, AGENT, TOKEN, null, "35436", null, null, null, null, null, null, null, null));

            zd.addComment(ctx, s, AGENT, TOKEN, null, "35436", "<p>Refund sent</p>", true, true, "solved", "a, b");
            Map<String, Object> cb = json(f.last("PUT", "/api/v2/tickets/35436"));
            assertEquals("<p>Refund sent</p>", HttpJson.path(cb, "ticket.comment.html_body"));
            assertNull(HttpJson.path(cb, "ticket.comment.body"));
            assertEquals("solved", HttpJson.path(cb, "ticket.status"));
            assertEquals(List.of("a", "b"), HttpJson.path(cb, "ticket.comment.uploads"));
            zd.addComment(ctx, s, AGENT, TOKEN, null, "35436", "internal", false, false, null, null);
            Map<String, Object> nb = json(f.last("PUT", "/api/v2/tickets/35436"));
            assertEquals(Map.of("comment", Map.of("body", "internal", "public", false)), nb.get("ticket"));

            Map<String, Object> cm = zd.listComments(ctx, s, AGENT, TOKEN, null, "35436", 500);
            assertEquals(List.of("Where is my refund?", "Done"), col(cm, 2), "cursor paging follows links.next");
            assertTrue(q(f.calls.stream().filter(x -> x.path().endsWith("/comments")).findFirst().orElseThrow()).contains("page[size]=100"));

            assertEquals(List.of("refund", "vip"), zd.addTags(ctx, s, AGENT, TOKEN, null, "35436", List.of("vip")).get("tags"));
            assertEquals(Map.of("tags", List.of("vip")), json(f.last("PUT", "/api/v2/tickets/35436/tags")));
            zd.deleteTicket(ctx, s, AGENT, TOKEN, null, "35436");
            assertNotNull(f.last("DELETE", "/api/v2/tickets/35436"));
            noSecretInLog(log);
        }
    }

    @Test void listsSearchAndForeignNextLinksIgnored() throws Exception {
        try (Fake f = new Fake()) {
            String s = f.url();
            f.on("GET /api/v2/tickets", c -> ok("{\"tickets\":[{\"id\":1,\"subject\":\"A\",\"status\":\"open\"}],\"meta\":{\"has_more\":true},\"links\":{\"next\":\"https://evil.example/api/v2/tickets?x\"}}"))
             .on("GET /api/v2/users/9/tickets/requested", c -> ok("{\"tickets\":[{\"id\":2}],\"meta\":{\"has_more\":false}}"))
             .on("GET /api/v2/organizations/4/tickets", c -> ok("{\"tickets\":[{\"id\":3}],\"meta\":{\"has_more\":false}}"))
             .on("GET /api/v2/search", c -> q(c).contains("page=2")
                    ? ok("{\"results\":[{\"id\":11,\"subject\":\"S2\"}],\"next_page\":null}")
                    : ok("{\"results\":[{\"id\":10,\"subject\":\"S1\",\"status\":\"open\"}],\"next_page\":\"" + s + "/api/v2/search?page=2&query=x\"}"))
             .on("GET /api/v2/views/active", c -> ok("{\"views\":[{\"id\":360,\"title\":\"Unassigned\",\"position\":1}],\"meta\":{\"has_more\":false}}"))
             .on("GET /api/v2/views/360/tickets", c -> ok("{\"tickets\":[{\"id\":20},{\"id\":21}],\"meta\":{\"has_more\":false}}"))
             .on("GET /api/v2/ticket_fields", c -> ok("{\"ticket_fields\":[{\"id\":360012345678,\"title\":\"Order\",\"type\":\"text\",\"active\":true,\"required\":false}]}"));
            ActionContext ctx = ctx(tmp(), log);
            Map<String, Object> all = zd.listTickets(ctx, s, AGENT, TOKEN, null, null, null, 500);
            assertEquals(List.of(new BigDecimal(1)), col(all, 0));
            assertEquals(1, f.calls.size(), "next link to another host is not followed");
            assertEquals(List.of("id", "subject", "status", "priority", "type", "requester_id", "assignee_id", "group_id", "updated_at"), columns(all));
            assertEquals(List.of(new BigDecimal(2)), col(zd.listTickets(ctx, s, AGENT, TOKEN, null, "9", null, 500), 0));
            assertEquals(List.of(new BigDecimal(3)), col(zd.listTickets(ctx, s, AGENT, TOKEN, null, null, "4", 500), 0));

            Map<String, Object> found = zd.searchTickets(ctx, s, AGENT, TOKEN, null, "status<solved tags:refund", 100);
            assertEquals(List.of(new BigDecimal(10), new BigDecimal(11)), col(found, 0), "offset paging follows next_page");
            String sq = q(f.calls.stream().filter(c -> c.path().equals("/api/v2/search")).findFirst().orElseThrow());
            assertTrue(sq.contains("query=type:ticket status<solved tags:refund") && sq.contains("sort_by=updated_at") && sq.contains("sort_order=desc"), sq);

            assertEquals(List.of(List.of(new BigDecimal(360), "Unassigned", new BigDecimal(1))), rows(zd.listViews(ctx, s, AGENT, TOKEN, null)).stream().map(r -> r.subList(0, 3)).toList());
            assertEquals(new BigDecimal(2), zd.listViewTickets(ctx, s, AGENT, TOKEN, null, "360", 500).get("count"));
            assertEquals(List.of(new BigDecimal("360012345678"), "Order", "text", true, false), rows(zd.listTicketFields(ctx, s, AGENT, TOKEN, null)).get(0));
            noSecretInLog(log);
        }
    }

    @Test void usersOrganizationsGroupsAndUploads() throws Exception {
        try (Fake f = new Fake()) {
            String s = f.url();
            f.on("POST /api/v2/users", c -> new Object[]{201, "{\"user\":{\"id\":501}}"})
             .on("POST /api/v2/users/create_or_update", c -> ok("{\"user\":{\"id\":502}}"))
             .on("GET /api/v2/users/search", c -> ok("{\"users\":[{\"id\":501,\"name\":\"Pat Kim\",\"email\":\"pat@customer.com\",\"role\":\"end-user\"}],\"next_page\":null}"))
             .on("GET /api/v2/users/501", c -> ok("{\"user\":{\"id\":501,\"name\":\"Pat Kim\",\"role\":\"end-user\",\"user_fields\":{\"tier\":\"gold\"}}}"))
             .on("PUT /api/v2/users/501", c -> ok("{\"user\":{\"id\":501}}"))
             .on("POST /api/v2/organizations", c -> new Object[]{201, "{\"organization\":{\"id\":601}}"})
             .on("GET /api/v2/organizations/601", c -> ok("{\"organization\":{\"id\":601,\"name\":\"Northwind\",\"domain_names\":[\"northwind.com\"]}}"))
             .on("GET /api/v2/organizations", c -> ok("{\"organizations\":[{\"id\":601,\"name\":\"Northwind\"}],\"meta\":{\"has_more\":false}}"))
             .on("GET /api/v2/groups", c -> ok("{\"groups\":[{\"id\":88,\"name\":\"Billing\",\"default\":true}],\"meta\":{\"has_more\":false}}"))
             .on("POST /api/v2/uploads", c -> new Object[]{201, "{\"upload\":{\"token\":\"tokA\",\"attachment\":{\"id\":901}}}"});
            Path w = tmp();
            Files.writeString(w.resolve("receipt.txt"), "paid");
            ActionContext ctx = ctx(w, log);
            assertEquals(new BigDecimal(501), zd.createUser(ctx, s, AGENT, TOKEN, null, "Pat Kim", "pat@customer.com", "end-user", "601", null, "CRM-1", false).get("user_id"));
            Map<String, Object> ub = json(f.last("POST", "/api/v2/users"));
            assertEquals("pat@customer.com", HttpJson.path(ub, "user.email"));
            assertEquals(new BigDecimal(601), HttpJson.path(ub, "user.organization_id"));
            assertEquals("CRM-1", HttpJson.path(ub, "user.external_id"));
            assertEquals(new BigDecimal(502), zd.createUser(ctx, s, AGENT, TOKEN, null, "Pat", "pat@customer.com", "end-user", null, null, null, true).get("user_id"));

            Map<String, Object> users = zd.findUsers(ctx, s, AGENT, TOKEN, null, "pat@customer.com", 100);
            assertEquals(List.of("Pat Kim"), col(users, 1));
            assertTrue(q(f.last("GET", "/api/v2/users/search")).contains("query=pat@customer.com"));
            Map<?, ?> u = (Map<?, ?>) zd.getUser(ctx, s, AGENT, TOKEN, null, "501").get("user");
            assertEquals(Map.of("tier", "gold"), u.get("user_fields"));
            zd.updateUser(ctx, s, AGENT, TOKEN, null, "501", null, null, "+1 555 0100", null, null, Map.of("tier", "platinum"));
            assertEquals(Map.of("user", Map.of("phone", "+1 555 0100", "user_fields", Map.of("tier", "platinum"))), json(f.last("PUT", "/api/v2/users/501")));
            fails("ZENDESK.NOTHING_TO_UPDATE", () -> zd.updateUser(ctx, s, AGENT, TOKEN, null, "501", null, null, null, null, null, null));

            assertEquals(new BigDecimal(601), zd.createOrganization(ctx, s, AGENT, TOKEN, null, "Northwind", "northwind.com", null, null, "ACC-9").get("organization_id"));
            assertEquals(Map.of("organization", Map.of("name", "Northwind", "domain_names", List.of("northwind.com"), "external_id", "ACC-9")), json(f.last("POST", "/api/v2/organizations")));
            assertEquals(List.of("northwind.com"), ((Map<?, ?>) zd.getOrganization(ctx, s, AGENT, TOKEN, null, "601").get("organization")).get("domain_names"));
            assertEquals(new BigDecimal(1), zd.listOrganizations(ctx, s, AGENT, TOKEN, null, 100).get("count"));
            assertEquals(List.of(Arrays.asList(new BigDecimal(88), "Billing", null, true)), rows(zd.listGroups(ctx, s, AGENT, TOKEN, null)));

            Map<String, Object> up = zd.upload(ctx, s, AGENT, TOKEN, null, "receipt.txt", "tok0");
            assertEquals("tokA", up.get("token"));
            assertEquals(new BigDecimal(901), up.get("attachment_id"));
            Call uc = f.last("POST", "/api/v2/uploads");
            assertEquals("paid", uc.text());
            assertTrue(q(uc).contains("filename=receipt.txt") && q(uc).contains("token=tok0"));
            noSecretInLog(log);
        }
    }

    @Test void oauthErrorsAndValidation() throws Exception {
        try (Fake f = new Fake()) {
            String s = f.url();
            f.on("GET /api/v2/tickets/1", c -> new Object[]{404, "{\"error\":\"RecordNotFound\",\"description\":\"Not found\"}"})
             .on("GET /api/v2/tickets/2", c -> new Object[]{429, "{\"error\":\"TooManyRequests\"}"})
             .on("GET /api/v2/tickets/3", c -> new Object[]{503, "Service Unavailable"})
             .on("GET /api/v2/tickets/4", c -> new Object[]{401, "{\"error\":\"Couldn't authenticate you\"}"})
             .on("GET /api/v2/tickets/5", c -> ok("{\"ticket\":{\"id\":5,\"status\":\"solved\"}}"))
             .on("POST /api/v2/tickets", c -> new Object[]{422, "{\"error\":\"RecordInvalid\",\"description\":\"Record validation errors\",\"details\":{}}"});
            ActionContext ctx = ctx(tmp(), log);
            assertEquals("solved", zd.getTicket(ctx, s, null, null, TOKEN, "5").get("status"));
            assertEquals("Bearer " + SECRET, f.last("GET", "/api/v2/tickets/5").auth(), "OAuth token sent as bearer");
            assertTrue(fails("ZENDESK.NOT_FOUND", () -> zd.getTicket(ctx, s, AGENT, TOKEN, null, "1")).getMessage().contains("RecordNotFound: Not found"));
            assertTrue(fails("ZENDESK.RATE_LIMITED", () -> zd.getTicket(ctx, s, AGENT, TOKEN, null, "2")).retryable());
            assertTrue(fails("ZENDESK.SERVER_ERROR", () -> zd.getTicket(ctx, s, AGENT, TOKEN, null, "3")).retryable());
            assertFalse(fails("ZENDESK.AUTH_FAILED", () -> zd.getTicket(ctx, s, AGENT, TOKEN, null, "4")).retryable());
            assertTrue(fails("ZENDESK.REQUEST_FAILED", () -> zd.createTicket(ctx, s, AGENT, TOKEN, null, "x", "y", null, null, null, null, null, null, null, null, null, true, null))
                    .getMessage().contains("Record validation errors"));
            fails("ZENDESK.BAD_ID", () -> zd.getTicket(ctx, s, AGENT, TOKEN, null, "12/../../users"));
            fails("ZENDESK.NO_EMAIL", () -> zd.getTicket(ctx, s, null, TOKEN, null, "5"));
            fails("ZENDESK.NO_PASSWORD", () -> zd.getTicket(ctx, s, AGENT, null, null, "5"));
            fails("ZENDESK.BAD_ID", () -> zd.createTicket(ctx, s, AGENT, TOKEN, null, "x", "y", null, null, null, null, null, "bob", null, null, null, true, null));
            noSecretInLog(log);
        }
    }

    @Test void runsInsideAFlowWithAVaultCredential() throws Exception {
        try (Fake f = new Fake()) {
            f.on("GET /api/v2/tickets/7", c -> ok("{\"ticket\":{\"id\":7,\"status\":\"pending\"}}"));
            Map<String, Object> o = run(tmp(), "[" + ConnectorsTest.TOKEN_VAR + ",{\"name\":\"s\",\"type\":\"String\",\"direction\":\"output\"}]",
                    "[{\"id\":\"g\",\"action\":\"ooa.zendesk/get_ticket\",\"args\":{\"site\":\"" + f.url() + "\",\"email\":\"" + AGENT + "\",\"api_token\":\"$tok\",\"ticket_id\":7},\"out\":{\"status\":\"s\"}}]",
                    ConnectorsTest.TOKEN);
            assertEquals("pending", o.get("s"));
            RunResult bad = ConnectorsTest.runRawWithToken(tmp(), "[" + ConnectorsTest.TOKEN_VAR + "]",
                    "[{\"id\":\"g\",\"action\":\"ooa.zendesk/get_ticket\",\"args\":{\"site\":\"" + f.url() + "\",\"email\":\"" + AGENT + "\",\"api_token\":\"$tok\",\"ticket_id\":8}}]");
            assertEquals("ZENDESK.NOT_FOUND", bad.error().get("code"));
        }
    }
}
