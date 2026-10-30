package com.oneofficeautomation.packages;

import com.oneofficeautomation.packages.ConnectorsTest.Call;
import com.oneofficeautomation.packages.ConnectorsTest.Fake;
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

/** New options of the Asana toolkit: subtasks and completed tasks at creation, listing a task's attachments, and a chosen name for uploads. */
class AsanaMoreOptionsTest {
    final AsanaPackage as = new AsanaPackage();
    final List<String> log = new ArrayList<>();
    static final String PRJ = "1200000000000001", TASK = "1400000000000001", PARENT = "1400000000000009";

    @Test void catalogHasListAttachments() {
        checkCatalog("ooa.asana", 21);
        assertNotNull(PackagesTest.REG.resolve("ooa.asana", null).info().actions().get("list_attachments"));
    }

    @Test void createTaskUnderAParentAndAlreadyCompleted() throws Exception {
        try (Fake f = new Fake()) {
            String b = f.url() + "/api/1.0";
            f.on("POST /api/1.0/tasks", c -> new Object[]{201, "{\"data\":{\"gid\":\"" + TASK + "\",\"permalink_url\":\"t1\"}}"});
            ActionContext ctx = ctx(tmp(), log);
            Map<String, Object> t = as.createTask(ctx, b, TOKEN, "Scan receipts", null, null, null, null, null, null, null, null, null, PARENT, true);
            assertEquals(TASK, t.get("task_id"));
            Call c = f.last("POST", "/api/1.0/tasks");
            assertEquals("Bearer " + SECRET, c.auth());
            assertTrue(q(c).contains("opt_fields=permalink_url"), q(c));
            Map<String, Object> d = json(c);
            assertEquals(PARENT, HttpJson.path(d, "data.parent"));
            assertEquals(true, HttpJson.path(d, "data.completed"));
            assertNull(HttpJson.path(d, "data.workspace"), "a parent task is enough to place the task");
            assertNull(HttpJson.path(d, "data.projects"));

            as.createTask(ctx, b, TOKEN, "Plain", PRJ, null, null, null, null, null, null, null, null);
            Map<String, Object> plain = json(f.last("POST", "/api/1.0/tasks"));
            assertNull(HttpJson.path(plain, "data.parent"));
            assertNull(HttpJson.path(plain, "data.completed"), "completed is only sent when asked for");
            assertEquals(List.of(PRJ), HttpJson.path(plain, "data.projects"));

            int before = f.calls.size();
            fails("ASANA.BAD_ID", () -> as.createTask(ctx, b, TOKEN, "x", null, null, null, null, null, null, null, null, null, "parent-1", false));
            fails("ASANA.MISSING_LOCATION", () -> as.createTask(ctx, b, TOKEN, "x", null, null, null, null, null, null, null, null, null, " ", true));
            assertEquals(before, f.calls.size(), "bad input is refused before any request");
            noSecretInLog(log);
        }
    }

    @Test void listAttachmentsOfATaskWithPaging() throws Exception {
        try (Fake f = new Fake()) {
            String b = f.url() + "/api/1.0";
            f.on("GET /api/1.0/attachments", c -> q(c).contains("offset=next1")
                    ? ok("{\"data\":[{\"gid\":\"2\",\"name\":\"photo.png\",\"size\":2048,\"host\":\"asana\"}],\"next_page\":null}")
                    : ok("{\"data\":[{\"gid\":\"1\",\"name\":\"INV-2041.pdf\",\"size\":10240,\"host\":\"asana\",\"created_at\":\"2026-10-20T08:00:00.000Z\","
                    + "\"download_url\":\"https://files.example/1\",\"view_url\":\"https://app.example/1\"}],\"next_page\":{\"offset\":\"next1\"}}"));
            ActionContext ctx = ctx(tmp(), log);
            Map<String, Object> r = as.listAttachments(ctx, b, TOKEN, TASK, 100);
            assertEquals(new BigDecimal(2), r.get("count"));
            assertEquals(List.of("gid", "name", "size", "host", "created_at", "download_url", "view_url"), columns(r));
            assertEquals(Arrays.asList("1", "INV-2041.pdf", new BigDecimal(10240), "asana", "2026-10-20T08:00:00.000Z", "https://files.example/1", "https://app.example/1"), rows(r).get(0));
            assertEquals("photo.png", rows(r).get(1).get(1));
            String first = q(f.calls.get(0));
            assertTrue(first.contains("parent=" + TASK) && first.contains("opt_fields=name,size,host,created_at,download_url,view_url") && first.contains("limit=100"), first);
            assertEquals("Bearer " + SECRET, f.calls.get(0).auth());

            assertEquals(new BigDecimal(1), as.listAttachments(ctx, b, TOKEN, TASK, 1).get("count"), "max_results stops paging");

            int before = f.calls.size();
            fails("ASANA.BAD_ID", () -> as.listAttachments(ctx, b, TOKEN, "not-a-task", 100));
            assertEquals(before, f.calls.size());
            noSecretInLog(log);
        }
    }

    static final String STORY = "1500000000000001", WS = "1100000000000001", TEAM = "1300000000000001";

    @Test void catalogHasCommentAndTeamLists() {
        checkCatalog("ooa.asana", 24);
        var actions = PackagesTest.REG.resolve("ooa.asana", null).info().actions();
        for (String id : List.of("get_comment", "list_teams", "list_user_teams")) assertNotNull(actions.get(id), id);
    }

    @Test void commentsAsRichTextAndPinned() throws Exception {
        try (Fake f = new Fake()) {
            String b = f.url() + "/api/1.0";
            f.on("POST /api/1.0/tasks/" + TASK + "/stories", c -> new Object[]{201, "{\"data\":{\"gid\":\"" + STORY + "\"}}"})
             .on("PUT /api/1.0/stories/" + STORY, c -> ok("{\"data\":{\"gid\":\"" + STORY + "\"}}"))
             .on("GET /api/1.0/stories/" + STORY, c -> ok("{\"data\":{\"gid\":\"" + STORY + "\",\"type\":\"comment\",\"resource_subtype\":\"comment_added\",\"text\":\"Paid today\","
                     + "\"html_text\":\"<body>Paid <strong>today</strong></body>\",\"is_pinned\":true,\"created_by\":{\"name\":\"Ana\"},\"created_at\":\"2026-10-20T08:00:00.000Z\","
                     + "\"target\":{\"gid\":\"" + TASK + "\",\"name\":\"Pay invoice\"}}}"));
            ActionContext ctx = ctx(tmp(), log);

            assertEquals(STORY, as.addComment(ctx, b, TOKEN, TASK, "<body>Paid <strong>today</strong></body>", "html", true).get("comment_id"));
            assertEquals(Map.of("html_text", "<body>Paid <strong>today</strong></body>", "is_pinned", true), json(f.last("POST", ".*/stories"), "data"));
            as.addComment(ctx, b, TOKEN, TASK, "Approved");
            assertEquals(Map.of("text", "Approved"), json(f.last("POST", ".*/stories"), "data"), "earlier call shape sends plain text, no pin");

            as.updateComment(ctx, b, TOKEN, STORY, "<body>Done</body>", "html", false);
            assertEquals(Map.of("html_text", "<body>Done</body>", "is_pinned", false), json(f.last("PUT", ".*/stories/.*"), "data"));
            as.updateComment(ctx, b, TOKEN, STORY, null, null, true);
            assertEquals(Map.of("is_pinned", true), json(f.last("PUT", ".*/stories/.*"), "data"), "pin only, text kept");
            as.updateComment(ctx, b, TOKEN, STORY, "Fixed typo");
            assertEquals(Map.of("text", "Fixed typo"), json(f.last("PUT", ".*/stories/.*"), "data"));

            Map<String, Object> got = as.getComment(ctx, b, TOKEN, STORY);
            assertEquals(Out.of("gid", STORY, "type", "comment_added", "text", "Paid today", "html_text", "<body>Paid <strong>today</strong></body>", "pinned", true,
                    "author", "Ana", "created_at", "2026-10-20T08:00:00.000Z", "task", "Pay invoice", "task_id", TASK), got.get("comment"));
            assertTrue(q(f.last("GET", ".*/stories/.*")).contains("opt_fields=type,resource_subtype,text,html_text,is_pinned"));

            int before = f.calls.size();
            fails("ASANA.BAD_FORMAT", () -> as.addComment(ctx, b, TOKEN, TASK, "x", "markdown", false));
            fails("ASANA.NOTHING_TO_UPDATE", () -> as.updateComment(ctx, b, TOKEN, STORY, "", "plain", null));
            fails("ASANA.BAD_ID", () -> as.getComment(ctx, b, TOKEN, "story-1"));
            assertEquals(before, f.calls.size(), "bad input is refused before any request");
            noSecretInLog(log);
        }
    }

    @Test void teamsOfAWorkspaceAndOfAUser() throws Exception {
        try (Fake f = new Fake()) {
            String b = f.url() + "/api/1.0";
            String team = "{\"data\":[{\"gid\":\"" + TEAM + "\",\"name\":\"Finance\",\"description\":\"AP and AR\",\"permalink_url\":\"https://app.asana.com/0/team/1\"}],\"next_page\":null}";
            f.on("GET /api/1.0/workspaces/" + WS + "/teams", c -> ok(team))
             .on("GET /api/1.0/users/.*/teams", c -> ok(team));
            ActionContext ctx = ctx(tmp(), log);

            Map<String, Object> r = as.listTeams(ctx, b, TOKEN, WS, 50);
            assertEquals(List.of("gid", "name", "description", "url"), columns(r));
            assertEquals(List.of(TEAM, "Finance", "AP and AR", "https://app.asana.com/0/team/1"), rows(r).get(0));
            assertTrue(q(f.last("GET", ".*/teams")).contains("limit=50"));

            assertEquals(new BigDecimal(1), as.listUserTeams(ctx, b, TOKEN, "ana@contoso.com", WS, 100).get("count"));
            Call c = f.last("GET", "/api/1.0/users/.*/teams");
            assertEquals("/api/1.0/users/ana@contoso.com/teams", java.net.URLDecoder.decode(c.path(), java.nio.charset.StandardCharsets.UTF_8));
            assertTrue(q(c).contains("organization=" + WS) && q(c).contains("opt_fields=name,description,permalink_url"), q(c));
            as.listUserTeams(ctx, b, TOKEN, null, WS, 100);
            assertTrue(f.last("GET", "/api/1.0/users/.*/teams").path().endsWith("/users/me/teams"), "the token's own user when empty");

            fails("ASANA.BAD_ID", () -> as.listTeams(ctx, b, TOKEN, "acme", 50));
            fails("ASANA.BAD_USER", () -> as.listUserTeams(ctx, b, TOKEN, "a/b", WS, 50));
        }
    }

    @Test void updateTaskMovesItUnderAnotherParent() throws Exception {
        try (Fake f = new Fake()) {
            String b = f.url() + "/api/1.0";
            f.on("PUT /api/1.0/tasks/" + TASK, c -> ok("{\"data\":{\"gid\":\"" + TASK + "\"}}"))
             .on("POST /api/1.0/tasks/" + TASK + "/setParent", c -> ok("{\"data\":{\"gid\":\"" + TASK + "\"}}"));
            ActionContext ctx = ctx(tmp(), log);

            as.updateTask(ctx, b, TOKEN, TASK, "Renamed", null, null, null, null, null, PARENT);
            assertEquals(Map.of("name", "Renamed"), json(f.last("PUT", ".*"), "data"));
            assertEquals(Map.of("parent", PARENT), json(f.last("POST", ".*/setParent"), "data"));

            int puts = (int) f.calls.stream().filter(c -> c.method().equals("PUT")).count();
            as.updateTask(ctx, b, TOKEN, TASK, null, null, null, null, null, null, "none");
            assertEquals(puts, f.calls.stream().filter(c -> c.method().equals("PUT")).count(), "only the parent changes: no PUT");
            assertTrue(json(f.last("POST", ".*/setParent")).containsKey("data"));
            assertNull(json(f.last("POST", ".*/setParent"), "data.parent"), "none makes it a top-level task");
            assertTrue(((Map<?, ?>) json(f.last("POST", ".*/setParent"), "data")).containsKey("parent"));

            int before = f.calls.size();
            fails("ASANA.BAD_ID", () -> as.updateTask(ctx, b, TOKEN, TASK, null, null, null, null, null, null, "parent-1"));
            fails("ASANA.NOTHING_TO_UPDATE", () -> as.updateTask(ctx, b, TOKEN, TASK, null, null, null, null, null, null, " "));
            assertEquals(before, f.calls.size());
        }
    }

    @Test void uploadAttachmentUnderAnotherName() throws Exception {
        try (Fake f = new Fake()) {
            String b = f.url() + "/api/1.0";
            f.on("POST /api/1.0/attachments", c -> ok("{\"data\":{\"gid\":\"1600000000000001\",\"view_url\":\"v1\"}}"));
            Path w = tmp();
            Files.writeString(w.resolve("scan-0001.pdf"), "%PDF-1.4");
            ActionContext ctx = ctx(w, log);

            Map<String, Object> r = as.upload(ctx, b, TOKEN, TASK, "scan-0001.pdf", "INV-2041.pdf");
            assertEquals("1600000000000001", r.get("attachment_id"));
            String body = new String(f.last("POST", "/api/1.0/attachments").body(), StandardCharsets.UTF_8);
            assertTrue(body.contains("name=\"file\"; filename=\"INV-2041.pdf\""), body);
            assertTrue(body.contains("%PDF-1.4"), body);

            as.upload(ctx, b, TOKEN, TASK, "scan-0001.pdf", "");
            assertTrue(new String(f.last("POST", "/api/1.0/attachments").body(), StandardCharsets.UTF_8).contains("filename=\"scan-0001.pdf\""), "empty name keeps the local file name");
            as.upload(ctx, b, TOKEN, TASK, "scan-0001.pdf");
            assertTrue(new String(f.last("POST", "/api/1.0/attachments").body(), StandardCharsets.UTF_8).contains("filename=\"scan-0001.pdf\""), "earlier signature unchanged");
        }
    }
}
