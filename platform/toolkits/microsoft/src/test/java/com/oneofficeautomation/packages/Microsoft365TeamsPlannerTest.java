package com.oneofficeautomation.packages;

import com.oneofficeautomation.packages.Microsoft365GapsTest.Call;
import com.oneofficeautomation.packages.Microsoft365GapsTest.Fake;
import com.oneofficeautomation.sdk.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.oneofficeautomation.packages.Microsoft365GapsTest.*;
import static com.oneofficeautomation.packages.PackagesTest.tmp;
import static org.junit.jupiter.api.Assertions.*;

/** Teams channel actions (create, read posts, reply) and Planner (plans and tasks) against a fake Microsoft Graph. */
class Microsoft365TeamsPlannerTest {
    final Microsoft365Package pkg = new Microsoft365Package();
    final SecureString tok = new SecureString("tok-123", "t");
    static final String ANN = "0b1c2d3e-4f50-6172-8394-a5b6c7d8e9f0", BOB = "11111111-2222-3333-4444-555555555555";

    static Map<String, String> headers(String... kv) { Map<String, String> h = new LinkedHashMap<>(); for (int i = 0; i < kv.length; i += 2) h.put(kv[i], kv[i + 1]); return h; }

    // ------------------------------------------------------------------------------- Teams

    @Test void createChannelPostsAStandardChannel() throws Exception {
        try (Fake f = new Fake()) {
            f.at("POST", "/v1.0/teams/T1/channels", c -> new Object[]{201, "{\"id\":\"19:new@thread.tacv2\",\"webUrl\":\"https://teams/c/new\"}"});
            ActionContext ctx = Microsoft365GapsTest.ctx(tmp());
            Map<String, Object> o = pkg.createChannel(ctx, tok, "T1", "Month end", "Close tasks", f.base());
            assertEquals("19:new@thread.tacv2", o.get("channel_id"));
            assertEquals("https://teams/c/new", o.get("web_url"));
            Call c = f.last("POST", "/v1.0/teams/T1/channels");
            assertEquals(Map.of("displayName", "Month end", "description", "Close tasks", "membershipType", "standard"), c.json());
            assertEquals("Bearer tok-123", c.header("Authorization"));

            pkg.createChannel(ctx, tok, "T1", "Plain", null, f.base());
            assertFalse(f.last("POST", "/v1.0/teams/T1/channels").json().containsKey("description"));

            f.calls.clear();
            assertEquals("M365.BAD_INPUT", assertThrows(ActionException.class, () -> pkg.createChannel(ctx, tok, "T1", "a/b", null, f.base())).code());
            assertEquals("M365.BAD_INPUT", assertThrows(ActionException.class, () -> pkg.createChannel(ctx, tok, "T1", "x".repeat(51), null, f.base())).code());
            assertEquals("M365.BAD_INPUT", assertThrows(ActionException.class, () -> pkg.createChannel(ctx, tok, " ", "Ok", null, f.base())).code());
            assertTrue(f.calls.isEmpty(), "input errors send nothing");
        }
    }

    @Test void channelMessagesFollowPagingFilterByDateAndStopAtMax() throws Exception {
        try (Fake f = new Fake()) {
            String b = f.base(), path = "/v1.0/teams/T1/channels/19%3Agen%40thread.tacv2/messages";
            f.at("GET", path, c -> c.q().contains("skiptoken")
                    ? ok("{\"value\":[{\"id\":\"3\",\"createdDateTime\":\"2026-10-30T08:00:00Z\",\"from\":{\"application\":{\"displayName\":\"Build\"}},"
                       + "\"body\":{\"contentType\":\"text\",\"content\":\"Build green\"}},"
                       + "{\"id\":\"4\",\"createdDateTime\":\"2026-09-01T08:00:00Z\",\"body\":{\"contentType\":\"text\",\"content\":\"old\"}}]}")
                    : ok("{\"value\":[{\"id\":\"1\",\"createdDateTime\":\"2026-11-02T09:15:00Z\",\"subject\":\"Close\",\"from\":{\"user\":{\"displayName\":\"Ann\"}},"
                       + "\"body\":{\"contentType\":\"html\",\"content\":\"<p>Ledger &amp; <b>bank</b> done</p>\"},\"webUrl\":\"https://teams/m/1\"},"
                       + "{\"id\":\"2\",\"createdDateTime\":\"2026-11-01T10:00:00Z\",\"from\":{\"user\":{\"displayName\":\"Bob\"}},"
                       + "\"body\":{\"contentType\":\"text\",\"content\":\"ok\"}}],"
                       + "\"@odata.nextLink\":\"" + b + "/teams/T1/channels/19%3Agen%40thread.tacv2/messages?$skiptoken=p2\"}"));
            ActionContext ctx = Microsoft365GapsTest.ctx(tmp());

            Map<String, Object> o = pkg.channelMessages(ctx, tok, "T1", "19:gen@thread.tacv2", null, 100, b);
            List<List<Object>> r = rows(o.get("table"));
            assertEquals(List.of("1", "2", "3", "4"), r.stream().map(x -> x.get(0)).toList(), "both pages read");
            assertEquals(BigDecimal.valueOf(4), o.get("count"));
            assertEquals("Ann", r.get(0).get(2));
            assertEquals("Close", r.get(0).get(3));
            assertEquals("Ledger & bank done", r.get(0).get(4), "HTML turned into text");
            assertEquals("<p>Ledger &amp; <b>bank</b> done</p>", r.get(0).get(5));
            assertEquals("Build", r.get(2).get(2), "app posts show the app's name");
            assertTrue(f.all("GET", path).get(0).q().contains("$top=50"));

            o = pkg.channelMessages(ctx, tok, "T1", "19:gen@thread.tacv2", LocalDateTime.parse("2026-10-15T00:00:00"), 100, b);
            assertEquals(List.of("1", "2", "3"), rows(o.get("table")).stream().map(x -> x.get(0)).toList(), "posts before since are left out");

            f.calls.clear();
            o = pkg.channelMessages(ctx, tok, "T1", "19:gen@thread.tacv2", null, 2, b);
            assertEquals(2, rows(o.get("table")).size());
            assertEquals(1, f.calls.size(), "no second page once max is reached");
            assertTrue(f.calls.get(0).q().contains("$top=2"));

            assertEquals("M365.BAD_INPUT", assertThrows(ActionException.class, () -> pkg.channelMessages(ctx, tok, "T1", "", null, 10, b)).code());
            assertEquals("M365.BAD_INPUT", assertThrows(ActionException.class, () -> pkg.channelMessages(ctx, tok, "T1", "C1", null, 0, b)).code());
        }
    }

    @Test void channelMessagesRefuseAPageOnAnotherServer() throws Exception {
        try (Fake f = new Fake()) {
            f.at("GET", "/v1.0/teams/T1/channels/C1/messages", c -> ok("{\"value\":[{\"id\":\"1\",\"createdDateTime\":\"2026-11-02T09:15:00Z\"}],"
                    + "\"@odata.nextLink\":\"https://evil.example.com/v1.0/next\"}"));
            ActionContext ctx = Microsoft365GapsTest.ctx(tmp());
            assertEquals("M365.BAD_RESPONSE", assertThrows(ActionException.class, () -> pkg.channelMessages(ctx, tok, "T1", "C1", null, 100, f.base())).code());
        }
    }

    @Test void replyToMessagePostsIntoTheThread() throws Exception {
        try (Fake f = new Fake()) {
            f.at("POST", "/v1.0/teams/T1/channels/C1/messages/M9/replies", c -> new Object[]{201, "{\"id\":\"R1\"}"});
            ActionContext ctx = Microsoft365GapsTest.ctx(tmp());
            assertEquals("R1", pkg.replyToMessage(ctx, tok, "T1", "C1", "M9", "Done <now>", false, f.base()).get("reply_id"));
            assertEquals(Map.of("contentType", "text", "content", "Done <now>"), f.last("POST", "/v1.0/teams/T1/channels/C1/messages/M9/replies").json().get("body"));
            pkg.replyToMessage(ctx, tok, "T1", "C1", "M9", "<b>Done</b>", true, f.base());
            assertEquals("html", ((Map<?, ?>) f.last("POST", "/v1.0/teams/T1/channels/C1/messages/M9/replies").json().get("body")).get("contentType"));
            f.calls.clear();
            assertEquals("M365.BAD_INPUT", assertThrows(ActionException.class, () -> pkg.replyToMessage(ctx, tok, "T1", "C1", "", "x", false, f.base())).code());
            assertEquals("M365.BAD_INPUT", assertThrows(ActionException.class, () -> pkg.replyToMessage(ctx, tok, "T1", "C1", "M9", " ", false, f.base())).code());
            assertTrue(f.calls.isEmpty());
        }
    }

    // ----------------------------------------------------------------------------- Planner

    @Test void listPlansAndTasksWithFiltersAndPaging() throws Exception {
        try (Fake f = new Fake()) {
            String b = f.base();
            f.at("GET", "/v1.0/groups/G1/planner/plans", c -> ok("{\"value\":[{\"id\":\"P1\",\"title\":\"Close\",\"createdDateTime\":\"2026-10-01T00:00:00Z\","
                    + "\"container\":{\"containerId\":\"G1\",\"type\":\"group\"}},{\"id\":\"P2\",\"title\":\"Audit\",\"owner\":\"G1\"}]}"))
             .at("GET", "/v1.0/planner/plans/P1/tasks", c -> c.q().contains("skiptoken")
                    ? ok("{\"value\":[{\"id\":\"K3\",\"title\":\"Accruals\",\"bucketId\":\"B2\",\"percentComplete\":0,\"assignments\":{\"" + ANN + "\":{}}}]}")
                    : ok("{\"value\":[{\"id\":\"K1\",\"title\":\"Bank rec\",\"bucketId\":\"B1\",\"percentComplete\":50,\"dueDateTime\":\"2026-11-05T00:00:00Z\","
                       + "\"priority\":5,\"assignments\":{\"" + ANN + "\":{},\"" + BOB + "\":{}}},"
                       + "{\"id\":\"K2\",\"title\":\"Payroll\",\"bucketId\":\"B1\",\"percentComplete\":100,\"assignments\":{}}],"
                       + "\"@odata.nextLink\":\"" + b + "/planner/plans/P1/tasks?$skiptoken=2\"}"));
            ActionContext ctx = Microsoft365GapsTest.ctx(tmp());

            Map<String, Object> plans = pkg.listPlans(ctx, tok, "G1", b);
            assertEquals(List.of(Arrays.asList("P1", "Close", "2026-10-01T00:00:00Z", "G1"), Arrays.asList("P2", "Audit", null, "G1")), rows(plans.get("table")));
            assertEquals(BigDecimal.valueOf(2), plans.get("count"));

            Map<String, Object> all = pkg.listTasks(ctx, tok, "P1", null, null, b);
            List<List<Object>> r = rows(all.get("table"));
            assertEquals(List.of("K1", "K2", "K3"), r.stream().map(x -> x.get(0)).toList(), "pages followed");
            assertEquals(ANN + ", " + BOB, r.get(0).get(8));
            assertEquals("2026-11-05T00:00:00Z", r.get(0).get(4));

            assertEquals(List.of("K1", "K2"), rows(pkg.listTasks(ctx, tok, "P1", "B1", null, b).get("table")).stream().map(x -> x.get(0)).toList());
            assertEquals(List.of("K1", "K3"), rows(pkg.listTasks(ctx, tok, "P1", null, ANN.toUpperCase(Locale.ROOT), b).get("table")).stream().map(x -> x.get(0)).toList());
            assertEquals(List.of("K1"), rows(pkg.listTasks(ctx, tok, "P1", "B1", BOB, b).get("table")).stream().map(x -> x.get(0)).toList());
            assertEquals("M365.BAD_INPUT", assertThrows(ActionException.class, () -> pkg.listTasks(ctx, tok, "P1", null, "ann@acme.com", b)).code());
        }
    }

    @Test void createTaskSendsPlannerShape() throws Exception {
        try (Fake f = new Fake()) {
            f.at("POST", "/v1.0/planner/tasks", c -> new Object[]{201, "{\"id\":\"K9\",\"@odata.etag\":\"W/\\\"JzEtVGFzayAgQEBAQEBAQEBAQEBAQEBARCc=\\\"\"}"});
            ActionContext ctx = Microsoft365GapsTest.ctx(tmp());
            Map<String, Object> o = pkg.createTask(ctx, tok, "P1", "B1", "Bank rec", LocalDateTime.parse("2026-11-05T17:00:00"), List.of(ANN, BOB), f.base());
            assertEquals("K9", o.get("task_id"));
            Map<String, Object> body = f.last("POST", "/v1.0/planner/tasks").json();
            assertEquals("P1", body.get("planId"));
            assertEquals("B1", body.get("bucketId"));
            assertEquals("Bank rec", body.get("title"));
            assertEquals("2026-11-05T17:00:00Z", body.get("dueDateTime"));
            Map<?, ?> asg = (Map<?, ?>) body.get("assignments");
            assertEquals(List.of(ANN, BOB), new ArrayList<>(asg.keySet()));
            assertEquals(Map.of("@odata.type", "#microsoft.graph.plannerAssignment", "orderHint", " !"), asg.get(ANN));

            pkg.createTask(ctx, tok, "P1", null, "Just a title", null, null, f.base());
            assertEquals(Map.of("planId", "P1", "title", "Just a title"), f.last("POST", "/v1.0/planner/tasks").json());

            f.calls.clear();
            assertEquals("M365.BAD_INPUT", assertThrows(ActionException.class, () -> pkg.createTask(ctx, tok, "P1", null, "T", null, List.of("ann@acme.com"), f.base())).code());
            assertEquals("M365.BAD_INPUT", assertThrows(ActionException.class, () -> pkg.createTask(ctx, tok, "P1", null, " ", null, null, f.base())).code());
            assertTrue(f.calls.isEmpty());
        }
    }

    @Test void updateTaskReadsTheETagFirstAndSendsIfMatch() throws Exception {
        try (Fake f = new Fake()) {
            AtomicInteger version = new AtomicInteger(1);
            f.at("GET", "/v1.0/planner/tasks/K1", c -> new Object[]{200, "{\"id\":\"K1\",\"@odata.etag\":\"W/\\\"body-" + version.get() + "\\\"\"}",
                    headers("ETag", "W/\"v" + version.get() + "\"")})
             .at("PATCH", "/v1.0/planner/tasks/K1", c -> ("W/\"v" + version.get() + "\"").equals(c.header("If-Match")) ? new Object[]{204, ""}
                    : new Object[]{412, "{\"error\":{\"code\":\"\",\"message\":\"The If-Match header must be specified for this kind of request.\"}}"})
             .at("GET", "/v1.0/planner/tasks/K2", c -> ok("{\"id\":\"K2\",\"@odata.etag\":\"W/\\\"only-body\\\"\"}"))
             .at("PATCH", "/v1.0/planner/tasks/K2", c -> new Object[]{204, ""});
            ActionContext ctx = Microsoft365GapsTest.ctx(tmp());

            pkg.updateTask(ctx, tok, "K1", "Bank rec (done)", 100, LocalDateTime.parse("2026-11-06T00:00:00"), f.base());
            Call patch = f.last("PATCH", "/v1.0/planner/tasks/K1");
            assertEquals("W/\"v1\"", patch.header("If-Match"), "ETag header from the GET");
            assertEquals(Map.of("title", "Bank rec (done)", "percentComplete", BigDecimal.valueOf(100), "dueDateTime", "2026-11-06T00:00:00Z"), patch.json());
            int get = f.calls.indexOf(f.last("GET", "/v1.0/planner/tasks/K1")), put = f.calls.indexOf(patch);
            assertTrue(get < put, "read before change");

            pkg.updateTask(ctx, tok, "K2", null, 50, null, f.base());
            assertEquals("W/\"only-body\"", f.last("PATCH", "/v1.0/planner/tasks/K2").header("If-Match"), "falls back to @odata.etag in the body");
            assertEquals(Map.of("percentComplete", BigDecimal.valueOf(50)), f.last("PATCH", "/v1.0/planner/tasks/K2").json());

            // someone changes the task between our read and our change: read once more and try again
            f.calls.clear();
            AtomicInteger patches = new AtomicInteger();
            f.routes.clear();
            f.at("GET", "/v1.0/planner/tasks/K1", c -> new Object[]{200, "{}", headers("ETag", "W/\"v" + version.get() + "\"")})
             .at("PATCH", "/v1.0/planner/tasks/K1", c -> {
                 if (patches.incrementAndGet() == 1) { version.incrementAndGet(); return new Object[]{412, "{\"error\":{\"message\":\"Precondition failed\"}}"}; }
                 return ("W/\"v" + version.get() + "\"").equals(c.header("If-Match")) ? new Object[]{204, ""} : new Object[]{412, "{}"};
             });
            pkg.updateTask(ctx, tok, "K1", null, 25, null, f.base());
            assertEquals(2, f.all("GET", "/v1\\.0/planner/tasks/K1").size());
            assertEquals("W/\"v2\"", f.last("PATCH", "/v1.0/planner/tasks/K1").header("If-Match"));

            // it keeps changing: a typed, retryable conflict
            f.routes.clear();
            f.at("GET", "/v1.0/planner/tasks/K1", c -> new Object[]{200, "{}", headers("ETag", "W/\"x\"")})
             .at("PATCH", "/v1.0/planner/tasks/K1", c -> new Object[]{412, "{\"error\":{\"message\":\"Precondition failed\"}}"});
            ActionException e = assertThrows(ActionException.class, () -> pkg.updateTask(ctx, tok, "K1", null, 25, null, f.base()));
            assertEquals("M365.CONFLICT", e.code());
            assertTrue(e.retryable());

            f.calls.clear();
            assertEquals("M365.BAD_INPUT", assertThrows(ActionException.class, () -> pkg.updateTask(ctx, tok, "K1", null, 101, null, f.base())).code());
            assertEquals("M365.BAD_INPUT", assertThrows(ActionException.class, () -> pkg.updateTask(ctx, tok, "K1", " ", null, null, f.base())).code());
            assertTrue(f.calls.isEmpty(), "input errors send nothing");
        }
    }

    @Test void graphErrorsAreMappedToTypedCodes() throws Exception {
        try (Fake f = new Fake()) {
            f.at("GET", "/v1.0/groups/NOAUTH/planner/plans", c -> new Object[]{401, "{\"error\":{\"code\":\"InvalidAuthenticationToken\",\"message\":\"Access token has expired.\"}}"})
             .at("GET", "/v1.0/planner/plans/DENIED/tasks", c -> new Object[]{403, "{\"error\":{\"code\":\"Forbidden\",\"message\":\"You do not have the required permissions to access this item.\"}}"})
             .at("GET", "/v1.0/planner/tasks/GONE", c -> new Object[]{404, "{\"error\":{\"code\":\"\",\"message\":\"The requested item is not found.\"}}"})
             .at("GET", "/v1.0/teams/T1/channels/BUSY/messages", c -> new Object[]{429, "{\"error\":{\"code\":\"TooManyRequests\",\"message\":\"Too many requests\"}}"});
            ActionContext ctx = Microsoft365GapsTest.ctx(tmp());
            ActionException e = assertThrows(ActionException.class, () -> pkg.listPlans(ctx, tok, "NOAUTH", f.base()));
            assertEquals("M365.AUTH_FAILED", e.code());
            assertTrue(e.getMessage().contains("Access token has expired"));
            assertEquals("M365.FORBIDDEN", assertThrows(ActionException.class, () -> pkg.listTasks(ctx, tok, "DENIED", null, null, f.base())).code());
            assertEquals("M365.NOT_FOUND", assertThrows(ActionException.class, () -> pkg.updateTask(ctx, tok, "GONE", "x", null, null, f.base())).code());
            ActionException busy = assertThrows(ActionException.class, () -> pkg.channelMessages(ctx, tok, "T1", "BUSY", null, 10, f.base()));
            assertEquals("M365.RATE_LIMITED", busy.code());
            assertTrue(busy.retryable());
        }
    }

    @Test void newActionsAreDescribed() {
        Set<String> ids = new HashSet<>();
        for (var m : Microsoft365Package.class.getMethods()) { Action a = m.getAnnotation(Action.class); if (a != null) ids.add(a.id()); }
        assertTrue(ids.containsAll(List.of("create_channel", "get_channel_messages", "reply_to_message", "list_plans", "list_tasks", "create_task", "update_task", "list_channels")));
    }
}
