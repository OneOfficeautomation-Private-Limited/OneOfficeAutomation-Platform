package com.oneofficeautomation.packages;

import com.oneofficeautomation.packages.ConnectorsTest.Call;
import com.oneofficeautomation.packages.ConnectorsTest.Fake;
import com.oneofficeautomation.sdk.ActionContext;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static com.oneofficeautomation.packages.ConnectorsTest.ok;
import static com.oneofficeautomation.packages.ConnectorsTest.q;
import static com.oneofficeautomation.packages.PackagesTest.*;
import static com.oneofficeautomation.packages.ToolkitTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** New options of the Asana toolkit: subtasks and completed tasks at creation, and listing a task's attachments. */
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
}
