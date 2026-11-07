package com.oneofficeautomation.packages;

import com.oneofficeautomation.packages.ConnectorsTest.Call;
import com.oneofficeautomation.packages.ConnectorsTest.Fake;
import com.oneofficeautomation.runner.RunResult;
import com.oneofficeautomation.sdk.ActionContext;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;

import static com.oneofficeautomation.packages.ConnectorsTest.ok;
import static com.oneofficeautomation.packages.ConnectorsTest.q;
import static com.oneofficeautomation.packages.PackagesTest.*;
import static com.oneofficeautomation.packages.ToolkitTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Asana toolkit against a local server that imitates the Asana REST API. */
class AsanaPackageTest {
    final AsanaPackage as = new AsanaPackage();
    final List<String> log = new ArrayList<>();
    static final String WS = "1100000000000001", PRJ = "1200000000000001", SEC = "1300000000000001", TASK = "1400000000000001", TAG = "1500000000000001";

    @Test void catalog() { checkCatalog("ooa.asana", 20); }

    @Test void workspacesProjectsAndSectionsWithOffsetPaging() throws Exception {
        try (Fake f = new Fake()) {
            String b = f.url() + "/api/1.0";
            f.on("GET /api/1.0/workspaces", c -> ok("{\"data\":[{\"gid\":\"" + WS + "\",\"name\":\"Acme\",\"is_organization\":true}]}"))
             .on("GET /api/1.0/projects", c -> q(c).contains("offset=eyJ")
                    ? ok("{\"data\":[{\"gid\":\"2\",\"name\":\"Payroll\"}],\"next_page\":null}")
                    : ok("{\"data\":[{\"gid\":\"1\",\"name\":\"Close\",\"archived\":false,\"owner\":{\"name\":\"Ana\"},\"permalink_url\":\"p1\"}],\"next_page\":{\"offset\":\"eyJ+abc\",\"path\":\"/projects?offset=eyJ\"}}"))
             .on("GET /api/1.0/projects/" + PRJ, c -> ok("{\"data\":{\"gid\":\"" + PRJ + "\",\"name\":\"Close\",\"team\":{\"name\":\"Finance\"},\"permalink_url\":\"p1\"}}"))
             .on("POST /api/1.0/projects", c -> new Object[]{201, "{\"data\":{\"gid\":\"" + PRJ + "\",\"permalink_url\":\"p1\"}}"})
             .on("GET /api/1.0/projects/" + PRJ + "/sections", c -> ok("{\"data\":[{\"gid\":\"" + SEC + "\",\"name\":\"To do\"}]}"))
             .on("POST /api/1.0/projects/" + PRJ + "/sections", c -> new Object[]{201, "{\"data\":{\"gid\":\"" + SEC + "\"}}"});
            ActionContext ctx = ctx(tmp(), log);
            Map<String, Object> ws = as.listWorkspaces(ctx, b, TOKEN);
            assertEquals(List.of(List.of(WS, "Acme", true)), rows(ws));
            assertEquals("Bearer " + SECRET, f.calls.get(0).auth());
            Map<String, Object> pr = as.listProjects(ctx, b, TOKEN, WS, "777", false, 500);
            assertEquals(List.of("1", "2"), col(pr, 0));
            assertEquals("Ana", rows(pr).get(0).get(3));
            Call first = f.calls.stream().filter(c -> c.path().equals("/api/1.0/projects")).findFirst().orElseThrow();
            String fq = q(first);
            assertTrue(fq.contains("workspace=" + WS) && fq.contains("team=777") && fq.contains("archived=false") && fq.contains("limit=100") && fq.contains("opt_fields="), fq);
            assertTrue(q(f.last("GET", "/api/1.0/projects")).contains("offset=eyJ+abc"), "offset is passed back unchanged");
            assertEquals("Finance", ((Map<?, ?>) as.getProject(ctx, b, TOKEN, PRJ).get("project")).get("team"));
            Map<String, Object> np = as.createProject(ctx, b, TOKEN, WS, "Q1 close", "777", "Notes");
            assertEquals(PRJ, np.get("project_id"));
            assertEquals(Map.of("data", Map.of("name", "Q1 close", "workspace", WS, "team", "777", "notes", "Notes")), json(f.last("POST", "/api/1.0/projects")));
            assertEquals(List.of(SEC), col(as.listSections(ctx, b, TOKEN, PRJ), 0));
            assertEquals(SEC, as.createSection(ctx, b, TOKEN, PRJ, "Waiting").get("section_id"));
            assertEquals("Waiting", json(f.last("POST", "/api/1.0/projects/" + PRJ + "/sections"), "data.name"));
            noSecretInLog(log);
        }
    }

    @Test void tasksCreateGetUpdateDeleteListAndSearch() throws Exception {
        try (Fake f = new Fake()) {
            String b = f.url() + "/api/1.0";
            f.on("POST /api/1.0/tasks", c -> new Object[]{201, "{\"data\":{\"gid\":\"" + TASK + "\",\"permalink_url\":\"t1\"}}"})
             .on("GET /api/1.0/tasks/" + TASK, c -> ok("{\"data\":{\"gid\":\"" + TASK + "\",\"name\":\"Approve\",\"completed\":false,\"assignee\":{\"name\":\"Ana\",\"email\":\"ana@x.com\"},"
                    + "\"projects\":[{\"name\":\"Close\"}],\"memberships\":[{\"section\":{\"name\":\"To do\"}}],\"tags\":[{\"name\":\"urgent\"}],"
                    + "\"custom_fields\":[{\"name\":\"Amount\",\"display_value\":\"1200\"}],\"due_on\":\"2026-11-15\"}}"))
             .on("PUT /api/1.0/tasks/" + TASK, c -> ok("{\"data\":{}}"))
             .on("DELETE /api/1.0/tasks/" + TASK, c -> ok("{\"data\":{}}"))
             .on("GET /api/1.0/sections/" + SEC + "/tasks", c -> ok("{\"data\":[{\"gid\":\"9\",\"name\":\"A\",\"assignee\":{\"name\":\"Ana\"},\"completed\":false}]}"))
             .on("GET /api/1.0/projects/" + PRJ + "/tasks", c -> ok("{\"data\":[{\"gid\":\"8\",\"name\":\"B\"}]}"))
             .on("GET /api/1.0/tasks", c -> ok("{\"data\":[{\"gid\":\"7\",\"name\":\"Mine\"}]}"))
             .on("GET /api/1.0/workspaces/" + WS + "/tasks/search", c -> ok("{\"data\":[{\"gid\":\"6\",\"name\":\"Invoice 5\",\"completed\":false}]}"));
            ActionContext ctx = ctx(tmp(), log);
            Map<String, Object> t = as.createTask(ctx, b, TOKEN, "Approve", PRJ, SEC, null, "Check it", "ana@x.com", "2026-11-15", TAG, List.of("me"), Map.of("123", "High"));
            assertEquals(TASK, t.get("task_id"));
            assertEquals("t1", t.get("url"));
            Map<String, Object> d = json(f.last("POST", "/api/1.0/tasks"));
            assertEquals(List.of(Map.of("project", PRJ, "section", SEC)), HttpJson.path(d, "data.memberships"));
            assertNull(HttpJson.path(d, "data.projects"), "memberships used instead of projects when a section is given");
            assertEquals("ana@x.com", HttpJson.path(d, "data.assignee"));
            assertEquals(List.of(TAG), HttpJson.path(d, "data.tags"));
            assertEquals(List.of("me"), HttpJson.path(d, "data.followers"));
            assertEquals("High", HttpJson.path(d, "data.custom_fields.123"));
            as.createTask(ctx, b, TOKEN, "Plain", PRJ, null, null, null, null, null, null, null, null);
            assertEquals(List.of(PRJ), json(f.last("POST", "/api/1.0/tasks"), "data.projects"));
            fails("ASANA.MISSING_LOCATION", () -> as.createTask(ctx, b, TOKEN, "x", null, null, null, null, null, null, null, null, null));
            fails("ASANA.BAD_USER", () -> as.createTask(ctx, b, TOKEN, "x", PRJ, null, null, null, "not a user", null, null, null, null));

            Map<String, Object> got = as.getTask(ctx, b, TOKEN, TASK);
            assertEquals(false, got.get("completed"));
            Map<?, ?> task = (Map<?, ?>) got.get("task");
            assertEquals("Ana", task.get("assignee"));
            assertEquals(List.of("Close"), task.get("projects"));
            assertEquals(List.of("To do"), task.get("sections"));
            assertEquals(Map.of("Amount", "1200"), task.get("custom_fields"));
            assertTrue(q(f.last("GET", "/api/1.0/tasks/" + TASK)).contains("opt_fields=name,notes"));

            as.updateTask(ctx, b, TOKEN, TASK, null, null, "none", null, true, null);
            Map<String, Object> ud = json(f.last("PUT", "/api/1.0/tasks/" + TASK));
            Map<?, ?> data = (Map<?, ?>) ud.get("data");
            assertTrue(data.containsKey("assignee") && data.get("assignee") == null, "none unassigns");
            assertEquals(true, data.get("completed"));
            assertEquals(2, data.size());
            fails("ASANA.NOTHING_TO_UPDATE", () -> as.updateTask(ctx, b, TOKEN, TASK, null, null, null, null, null, null));
            as.deleteTask(ctx, b, TOKEN, TASK);
            assertNotNull(f.last("DELETE", "/api/1.0/tasks/" + TASK));

            assertEquals(List.of("9"), col(as.listTasks(ctx, b, TOKEN, PRJ, SEC, null, null, true, null, 100), 0), "section wins over project");
            assertTrue(q(f.last("GET", "/api/1.0/sections/.*")).contains("completed_since=now"));
            assertEquals(List.of("8"), col(as.listTasks(ctx, b, TOKEN, PRJ, null, null, null, false, "2026-10-01T00:00:00Z", 100), 0));
            assertTrue(q(f.last("GET", "/api/1.0/projects/.*")).contains("modified_since=2026-10-01T00:00:00Z"));
            assertEquals(List.of("7"), col(as.listTasks(ctx, b, TOKEN, null, null, "me", WS, false, null, 100), 0));
            String mq = q(f.last("GET", "/api/1.0/tasks"));
            assertTrue(mq.contains("assignee=me") && mq.contains("workspace=" + WS), mq);
            fails("ASANA.MISSING_FILTER", () -> as.listTasks(ctx, b, TOKEN, null, null, "me", null, false, null, 10));

            Map<String, Object> s = as.searchTasks(ctx, b, TOKEN, WS, "invoice", "me", PRJ, false, 50);
            assertEquals(List.of("6"), col(s, 0));
            String sq = q(f.last("GET", ".*/tasks/search"));
            assertTrue(sq.contains("text=invoice") && sq.contains("assignee.any=me") && sq.contains("projects.any=" + PRJ) && sq.contains("completed=false") && sq.contains("limit=50"), sq);
            noSecretInLog(log);
        }
    }

    @Test void followersSectionsSubtasksCommentsAttachmentsTagsAndUsers() throws Exception {
        try (Fake f = new Fake()) {
            String b = f.url() + "/api/1.0";
            f.on("POST /api/1.0/tasks/" + TASK + "/(addFollowers|removeFollowers|addProject|addTag|removeTag)", c -> ok("{\"data\":{}}"))
             .on("POST /api/1.0/sections/" + SEC + "/addTask", c -> ok("{\"data\":{}}"))
             .on("POST /api/1.0/tasks/" + TASK + "/subtasks", c -> new Object[]{201, "{\"data\":{\"gid\":\"55\",\"permalink_url\":\"s55\"}}"})
             .on("GET /api/1.0/tasks/" + TASK + "/subtasks", c -> ok("{\"data\":[{\"gid\":\"55\",\"name\":\"Sub\"}]}"))
             .on("POST /api/1.0/tasks/" + TASK + "/stories", c -> new Object[]{201, "{\"data\":{\"gid\":\"66\"}}"})
             .on("GET /api/1.0/tasks/" + TASK + "/stories", c -> ok("{\"data\":[{\"gid\":\"1\",\"type\":\"system\",\"text\":\"assigned\"},"
                    + "{\"gid\":\"2\",\"type\":\"comment\",\"text\":\"Looks good\",\"created_by\":{\"name\":\"Ana\"}}]}"))
             .on("POST /api/1.0/attachments", c -> ok("{\"data\":{\"gid\":\"77\",\"view_url\":\"v77\"}}"))
             .on("GET /api/1.0/workspaces/" + WS + "/tags", c -> ok("{\"data\":[{\"gid\":\"" + TAG + "\",\"name\":\"urgent\",\"color\":\"red\"}]}"))
             .on("POST /api/1.0/tags", c -> new Object[]{201, "{\"data\":{\"gid\":\"" + TAG + "\"}}"})
             .on("GET /api/1.0/users", c -> ok("{\"data\":[{\"gid\":\"5\",\"name\":\"Ana\",\"email\":\"ana@x.com\"}]}"))
             .on("GET /api/1.0/users/me", c -> ok("{\"data\":{\"gid\":\"5\",\"name\":\"Ana\",\"email\":\"ana@x.com\",\"workspaces\":[{\"name\":\"Acme\"}]}}"))
             .on("GET /api/1.0/users/ana%40x.com", c -> ok("{\"data\":{\"gid\":\"5\",\"name\":\"Ana\"}}"));
            Path w = tmp();
            Files.writeString(w.resolve("INV-1.pdf"), "%PDF");
            ActionContext ctx = ctx(w, log);
            as.addFollowers(ctx, b, TOKEN, TASK, "ana@x.com; 5");
            assertEquals(List.of("ana@x.com", "5"), json(f.last("POST", ".*/addFollowers"), "data.followers"));
            as.removeFollowers(ctx, b, TOKEN, TASK, List.of("me"));
            assertEquals(List.of("me"), json(f.last("POST", ".*/removeFollowers"), "data.followers"));
            as.addToSection(ctx, b, TOKEN, SEC, TASK);
            assertEquals(TASK, json(f.last("POST", ".*/addTask"), "data.task"));
            as.addToProject(ctx, b, TOKEN, TASK, PRJ, SEC);
            assertEquals(Map.of("project", PRJ, "section", SEC), json(f.last("POST", ".*/addProject")).get("data"));

            Map<String, Object> sub = as.createSubtask(ctx, b, TOKEN, TASK, "Sub", null, "me", "2026-12-01");
            assertEquals("55", sub.get("task_id"));
            assertEquals(Map.of("name", "Sub", "assignee", "me", "due_on", "2026-12-01"), json(f.last("POST", ".*/subtasks")).get("data"));
            assertEquals(List.of("55"), col(as.listSubtasks(ctx, b, TOKEN, TASK), 0));

            assertEquals("66", as.addComment(ctx, b, TOKEN, TASK, "Approved").get("comment_id"));
            assertEquals("Approved", json(f.last("POST", ".*/stories"), "data.text"));
            Map<String, Object> cm = as.listComments(ctx, b, TOKEN, TASK);
            assertEquals(List.of(Arrays.asList("2", "Looks good", "Ana", null)), rows(cm), "system stories left out");

            Map<String, Object> att = as.upload(ctx, b, TOKEN, TASK, "INV-1.pdf");
            assertEquals("77", att.get("attachment_id"));
            assertEquals("v77", att.get("url"));
            Call up = f.last("POST", "/api/1.0/attachments");
            assertTrue(up.contentType().startsWith("multipart/form-data; boundary="));
            assertTrue(up.text().contains("name=\"parent\"\r\n\r\n" + TASK) && up.text().contains("filename=\"INV-1.pdf\"") && up.text().contains("%PDF"));

            assertEquals(List.of(List.of(TAG, "urgent", "red")), rows(as.listTags(ctx, b, TOKEN, WS)));
            assertEquals(TAG, as.createTag(ctx, b, TOKEN, WS, "needs-review").get("tag_id"));
            assertEquals(Map.of("name", "needs-review", "workspace", WS), json(f.last("POST", "/api/1.0/tags")).get("data"));
            as.addTag(ctx, b, TOKEN, TASK, TAG);
            assertEquals(TAG, json(f.last("POST", ".*/addTag"), "data.tag"));
            as.removeTag(ctx, b, TOKEN, TASK, TAG);
            assertEquals(TAG, json(f.last("POST", ".*/removeTag"), "data.tag"));

            assertEquals(List.of(List.of("5", "Ana", "ana@x.com")), rows(as.listUsers(ctx, b, TOKEN, WS, 100)));
            assertTrue(q(f.last("GET", "/api/1.0/users")).contains("workspace=" + WS));
            Map<String, Object> me = as.getUser(ctx, b, TOKEN, "me");
            assertEquals("5", me.get("user_id"));
            assertEquals(List.of("Acme"), ((Map<?, ?>) me.get("user")).get("workspaces"));
            assertEquals("5", as.getUser(ctx, b, TOKEN, "ana@x.com").get("user_id"));
            noSecretInLog(log);
        }
    }

    @Test void errorsAreTyped() throws Exception {
        try (Fake f = new Fake()) {
            String b = f.url() + "/api/1.0";
            f.on("GET /api/1.0/tasks/1", c -> new Object[]{404, "{\"errors\":[{\"message\":\"task: Unknown object: 1\"}]}"})
             .on("GET /api/1.0/tasks/2", c -> new Object[]{429, "{\"errors\":[{\"message\":\"Rate limit enforced\"}]}"})
             .on("GET /api/1.0/tasks/3", c -> new Object[]{500, "{\"errors\":[{\"message\":\"Server Error\"}]}"})
             .on("GET /api/1.0/tasks/4", c -> new Object[]{403, "{\"errors\":[{\"message\":\"Forbidden\"}]}"})
             .on("GET /api/1.0/workspaces/" + WS + "/tasks/search", c -> new Object[]{402, "{\"errors\":[{\"message\":\"Search is only available to premium users.\"}]}"});
            ActionContext ctx = ctx(tmp(), log);
            assertTrue(fails("ASANA.NOT_FOUND", () -> as.getTask(ctx, b, TOKEN, "1")).getMessage().contains("Unknown object"));
            assertTrue(fails("ASANA.RATE_LIMITED", () -> as.getTask(ctx, b, TOKEN, "2")).retryable());
            assertTrue(fails("ASANA.SERVER_ERROR", () -> as.getTask(ctx, b, TOKEN, "3")).retryable());
            assertFalse(fails("ASANA.FORBIDDEN", () -> as.getTask(ctx, b, TOKEN, "4")).retryable());
            assertTrue(fails("ASANA.PREMIUM_REQUIRED", () -> as.searchTasks(ctx, b, TOKEN, WS, "x", null, null, null, 10)).getMessage().contains("premium"));
            fails("ASANA.BAD_ID", () -> as.getTask(ctx, b, TOKEN, "../workspaces"));
            fails("ASANA.NO_TOKEN", () -> as.getTask(ctx, b, null, TASK));
            noSecretInLog(log);
        }
    }

    @Test void runsInsideAFlowWithAVaultCredential() throws Exception {
        try (Fake f = new Fake()) {
            f.on("POST /api/1.0/tasks", c -> new Object[]{201, "{\"data\":{\"gid\":\"" + TASK + "\"}}"});
            Map<String, Object> o = run(tmp(), "[" + ConnectorsTest.TOKEN_VAR + ",{\"name\":\"id\",\"type\":\"String\",\"direction\":\"output\"}]",
                    "[{\"id\":\"c\",\"action\":\"ooa.asana/create_task\",\"args\":{\"base_url\":\"" + f.url() + "/api/1.0\",\"token\":\"$tok\",\"name\":\"From flow\",\"project_id\":\"" + PRJ + "\"},\"out\":{\"task_id\":\"id\"}}]",
                    ConnectorsTest.TOKEN);
            assertEquals(TASK, o.get("id"));
            assertEquals("Bearer tok-123", f.last("POST", ".*").auth());
            RunResult bad = ConnectorsTest.runRawWithToken(tmp(), "[" + ConnectorsTest.TOKEN_VAR + "]",
                    "[{\"id\":\"g\",\"action\":\"ooa.asana/get_task\",\"args\":{\"base_url\":\"" + f.url() + "/api/1.0\",\"token\":\"$tok\",\"task_id\":\"123\"}}]");
            assertEquals("ASANA.NOT_FOUND", bad.error().get("code"));
        }
    }
}
