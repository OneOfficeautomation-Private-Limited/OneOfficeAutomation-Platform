package com.oneofficeautomation.cli;

import com.oneofficeautomation.controlroom.*;
import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Part 3: `ooa server` (Control Room at /, Studio at /studio/ on one port, a self-registering Bot Agent), the Activity APIs
 * (filters, paging, progress, rerun, run schedule now), and Studio's typed workspace items and dependencies.
 */
class UnifiedServerTest {
    static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    record Res(int status, String body, HttpHeaders headers) {
        @SuppressWarnings("unchecked") Map<String, Object> json() { return body.isBlank() ? Map.of() : (Map<String, Object>) JsonLite.parse(body); }
    }

    static Res call(String base, String method, String path, Object body, String bearer, String cookie) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JsonLite.write(body)))
                .header("Content-Type", "application/json");
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        if (cookie != null) b.header("Cookie", ControlRoomServer.COOKIE + "=" + cookie);
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Res(r.statusCode(), r.body(), r.headers());
    }

    @SuppressWarnings("unchecked")
    @Test void oneServerControlRoomStudioAndLocalAgent() throws Exception {
        Path data = Files.createTempDirectory("ooa-us-data"), ws = Files.createTempDirectory("ooa-us-ws"), agentHome = Files.createTempDirectory("ooa-us-agent");
        Db db = Db.embedded(data);
        ControlRoom cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        String pw = cr.bootstrap();
        int port = StudioControlRoomTest.freePort();
        String base = "http://127.0.0.1:" + port;
        StudioServer studio = new StudioServer(ws, port, Path.of("/nonexistent"));
        ControlRoomServer server = new ControlRoomServer(cr, "127.0.0.1", port).mount("/studio", studio.mountedAt("/studio", base));
        server.start();
        com.oneofficeautomation.agent.Agent agent = null;
        try {
            // ---- one URL: Control Room at /, Studio at /studio/ ----
            Res redirect = call(base, "GET", "/studio", null, null, null);
            assertEquals(302, redirect.status());
            assertEquals("/studio/", redirect.headers().firstValue("Location").orElse(""));
            Res page = call(base, "GET", "/studio/", null, null, null);
            assertEquals(200, page.status());
            assertTrue(page.body().contains("<script src=\"studio.js\">"), "Studio's page uses relative paths so it works under /studio/");
            assertEquals(200, call(base, "GET", "/studio/studio.js", null, null, null).status());
            // HEAD (used by uptime checks and some proxies): same status, no body, and no server warning
            for (String p : List.of("/", "/studio/", "/api/health")) {
                Res head = call(base, "HEAD", p, null, null, null);
                assertEquals(200, head.status(), "HEAD " + p);
                assertEquals("", head.body(), "HEAD " + p + " has no body");
            }
            // ---- one design system: both applications serve the same token and component sheets ----
            for (String css : List.of("ooa-tokens.css", "ooa-components.css")) {
                Res fromStudio = call(base, "GET", "/studio/" + css, null, null, null), fromConsole = call(base, "GET", "/" + css, null, null, null);
                assertEquals(200, fromStudio.status(), css);
                assertEquals(200, fromConsole.status(), css);
                assertTrue(fromStudio.headers().firstValue("Content-Type").orElse("").startsWith("text/css"), css);
                assertEquals(fromConsole.body(), fromStudio.body(), css + " is the same file in both applications");
                assertTrue(page.body().contains("href=\"" + css + "\""), "Studio's page loads " + css);
            }
            assertTrue(call(base, "GET", "/ooa-tokens.css", null, null, null).body().contains("--surface-canvas"));
            assertEquals("/", call(base, "GET", "/studio/api/info", null, null, null).json().get("controlRoom"));
            assertEquals("/studio/", call(base, "GET", "/api/health", null, null, null).json().get("studio"), "Control Room links to Studio for browsers on this computer");
            assertTrue(call(base, "GET", "/", null, null, null).body().contains("One Command Center"));
            assertEquals(404, call(base, "GET", "/studio-other", null, null, null).status());

            // ---- blueprints (Create > Start from), one shared list for both Studios ----
            List<Object> blueprints = (List<Object>) call(base, "GET", "/studio/api/blueprints", null, null, null).json().get("blueprints");
            assertTrue(blueprints.size() >= 7, String.valueOf(blueprints.size()));
            for (Object b : blueprints) {
                Map<String, Object> bp = (Map<String, Object>) b;
                assertTrue(bp.get("id") instanceof String && bp.get("name") instanceof String && bp.get("steps") instanceof List<?>, String.valueOf(bp.get("id")));
            }

            // ---- the new Studio preview (platform/ui) at /studio/next/, its own files only ----
            assertEquals("/studio/next/", call(base, "GET", "/studio/next", null, null, null).headers().firstValue("Location").orElse(""));
            Res next = call(base, "GET", "/studio/next/", null, null, null);
            assertEquals(200, next.status());
            java.util.regex.Matcher script = java.util.regex.Pattern.compile("src=\"\\./(assets/[^\"]+\\.js)\"").matcher(next.body());
            assertTrue(script.find(), "the page loads its script by a relative path, so it works under /studio/next/");
            Res js = call(base, "GET", "/studio/next/" + script.group(1), null, null, null);
            assertEquals(200, js.status());
            assertTrue(js.headers().firstValue("Content-Type").orElse("").startsWith("text/javascript"));
            assertTrue(js.headers().firstValue("Content-Security-Policy").orElse("").contains("script-src 'self'"), "the same security policy as the classic Studio");
            // each page gets a fresh style nonce, in its policy and in the page; no other inline style is allowed
            java.util.regex.Matcher nonce = java.util.regex.Pattern.compile("style-src 'self' 'nonce-([A-Za-z0-9+/=]{24})'").matcher(next.headers().firstValue("Content-Security-Policy").orElse(""));
            assertTrue(nonce.find(), next.headers().toString());
            assertTrue(next.body().contains("content=\"" + nonce.group(1) + "\""), "the page carries the same nonce");
            assertFalse(next.body().contains("__CSP_NONCE__"));
            assertNotEquals(nonce.group(1), call(base, "GET", "/studio/next/", null, null, null).headers().firstValue("Content-Security-Policy").orElse("").replaceAll(".*'nonce-([^']+)'.*", "$1"), "a new nonce per page");
            for (String bad : List.of("/studio/next/assets/../../studio.js", "/studio/next/..%2fstudio.js", "/studio/next/assets/x.exe", "/studio/next/secret.txt"))
                assertEquals(404, call(base, "GET", bad, null, null, null).status(), bad);

            // ---- Studio reuses the browser's Control Room sign-in ----
            String token = (String) call(base, "POST", "/api/auth/login", Map.of("username", "admin", "password", pw), null, null).json().get("token");
            Map<String, Object> pending = call(base, "GET", "/studio/api/cr/status", null, null, token).json();
            assertEquals(false, pending.get("connected"), "not while the first-time password change is pending");
            assertEquals(true, pending.get("sameServer"));
            call(base, "POST", "/api/auth/password", Map.of("current", pw, "new", "Unified-Passw0rd-1"), token, null);
            Map<String, Object> st = call(base, "GET", "/studio/api/cr/status", null, null, token).json();
            assertEquals(true, st.get("connected"), String.valueOf(st));
            assertEquals("admin", ((Map<String, Object>) st.get("user")).get("username"));
            Files.createDirectories(ws.resolve("My Bots"));
            Files.writeString(ws.resolve("My Bots/Quick.flow.json"), "{\"kind\":\"TaskBot\",\"id\":\"quick\",\"name\":\"Quick\",\"variables\":[{\"name\":\"who\",\"type\":\"String\",\"direction\":\"input\",\"default\":\"x\"}],"
                    + "\"steps\":[{\"id\":\"s1\",\"action\":\"core/log\",\"args\":{\"message\":\"hello ${who}\"}}]}");
            Map<String, Object> in = call(base, "POST", "/studio/api/cr/checkin", Map.of("local", "My Bots/Quick.flow.json", "comment", "v1"), null, null).json();
            assertEquals(1, ((Number) in.get("version")).intValue(), String.valueOf(in));
            call(base, "POST", "/studio/api/cr/disconnect", null, null, null);
            assertEquals(200, call(base, "GET", "/api/auth/me", null, token, null).status(), "Studio never signs out the browser's own session");

            // ---- the local Bot Agent registers itself once ----
            Path cfg = agentHome.resolve("local-agent.json");
            agent = Ooa.startLocalAgent(cr, base, cfg, agentHome.resolve("work"));
            Map<String, Object> agentCfg = (Map<String, Object>) JsonLite.parse(Files.readString(cfg));
            List<Object> devices = (List<Object>) call(base, "GET", "/api/devices", null, token, null).json().get("devices");
            assertEquals(1, devices.size());
            String deviceId = (String) ((Map<String, Object>) devices.get(0)).get("id");
            assertEquals(deviceId, agentCfg.get("device_id"));
            agent.stop();
            agent = Ooa.startLocalAgent(cr, base, cfg, agentHome.resolve("work"));
            assertEquals(1, ((List<Object>) call(base, "GET", "/api/devices", null, token, null).json().get("devices")).size(), "a second start reuses the registration");

            // ---- runs: history filters, paging, rerun, run a schedule now ----
            String runId = (String) call(base, "POST", "/api/runs", Map.of("bot_path", "My Bots/Quick.flow.json", "device_id", deviceId, "inputs", Map.of("who", "unified")), token, null).json().get("run_id");
            assertTrue(ControlRoomE2ETest.until(() -> "success".equals(call(base, "GET", "/api/runs/" + runId, null, token, null).json().get("status")), 60000), "the local agent runs the bot");
            Map<String, Object> rerun = call(base, "POST", "/api/runs/" + runId + "/rerun", null, token, null).json();
            String rerunId = (String) rerun.get("run_id");
            assertNotNull(rerunId, String.valueOf(rerun));
            assertTrue(ControlRoomE2ETest.until(() -> "success".equals(call(base, "GET", "/api/runs/" + rerunId, null, token, null).json().get("status")), 60000));
            Map<String, Object> rr = call(base, "GET", "/api/runs/" + rerunId, null, token, null).json();
            assertEquals("rerun", rr.get("source"));
            assertTrue(String.valueOf(rr.get("inputs")).contains("unified"), "a rerun keeps the original inputs");
            Map<String, Object> sched = call(base, "POST", "/api/schedules", Map.of("name", "Hourly", "bot_path", "My Bots/Quick.flow.json", "device_id", deviceId, "kind", "every",
                    "every_minutes", 60, "time_zone", "UTC", "inputs", Map.of()), token, null).json();
            String sid = (String) sched.get("id");
            assertNotNull(sid, String.valueOf(sched));
            String schedRun = (String) call(base, "POST", "/api/schedules/" + sid + "/run", null, token, null).json().get("run_id");
            assertTrue(ControlRoomE2ETest.until(() -> "success".equals(call(base, "GET", "/api/runs/" + schedRun, null, token, null).json().get("status")), 60000));

            Map<String, Object> all = call(base, "GET", "/api/runs?filter=history&limit=2", null, token, null).json();
            assertEquals(3, ((Number) all.get("total")).intValue());
            assertEquals(2, ((List<Object>) all.get("runs")).size(), "paging limits the page");
            assertEquals(1, ((List<Object>) call(base, "GET", "/api/runs?filter=history&limit=2&offset=2", null, token, null).json().get("runs")).size());
            assertEquals(1, ((Number) call(base, "GET", "/api/runs?filter=history&source=rerun", null, token, null).json().get("total")).intValue());
            assertEquals(1, ((Number) call(base, "GET", "/api/runs?filter=history&source=schedule", null, token, null).json().get("total")).intValue(), "schedule:<id> sources match 'schedule'");
            assertEquals(3, ((Number) call(base, "GET", "/api/runs?filter=history&status=success&bot=" + StudioServerUrl.enc("My Bots/Quick.flow.json"), null, token, null).json().get("total")).intValue());
            assertEquals(0, ((Number) call(base, "GET", "/api/runs?filter=history&status=failed", null, token, null).json().get("total")).intValue());
            assertEquals(3, ((Number) call(base, "GET", "/api/runs?filter=history&q=quick&device=" + deviceId, null, token, null).json().get("total")).intValue());
            assertEquals(0, ((Number) call(base, "GET", "/api/runs?filter=history&from=2999-01-01", null, token, null).json().get("total")).intValue());
            assertEquals(400, call(base, "GET", "/api/runs?filter=history&from=not-a-date", null, token, null).status());

            // ---- devices, dashboard, command names ----
            assertEquals(200, call(base, "PATCH", "/api/devices/" + deviceId, Map.of("name", "Build PC"), token, null).status());
            assertTrue(call(base, "GET", "/api/devices", null, token, null).body().contains("Build PC"));
            Map<String, Object> dash = call(base, "GET", "/api/dashboard", null, token, null).json();
            assertEquals(7, ((List<Object>) dash.get("days")).size());
            assertEquals(100, ((Number) dash.get("success_rate_24h")).intValue());
            assertFalse(((List<Object>) dash.get("top_bots")).isEmpty());
            assertEquals("Log message", ((Map<String, Object>) call(base, "GET", "/api/catalog", null, token, null).json().get("actions")).get("core/log"));
        } finally {
            if (agent != null) agent.stop();
            server.stop();
            db.close();
        }
    }

    @SuppressWarnings("unchecked")
    @Test void workspaceItemsAndDependencies() throws Exception {
        Path root = Files.createTempDirectory("ooa-items");
        Workspace w = new Workspace(root);
        Workspace.JsonWriter json = v -> JsonLite.write(v);
        Files.createDirectories(root.resolve("My Bots"));
        assertEquals("My Bots/Expense.form.json", w.createItem("My Bots", "Expense", "form", json));
        assertEquals("My Bots/Onboarding.process.json", w.createItem("My Bots", "Onboarding", "process", json));
        assertEquals("My Bots/Invoices.doc.json", w.createItem("My Bots", "Invoices", "document", json));
        assertEquals("ai-skills/summarise_email.json", w.createItem("My Bots", "summarise email", "skill", json), "skills always go to ai-skills");
        assertEquals("My Bots/clean.py", w.createItem("My Bots", "clean", "script-python", json));
        assertEquals("My Bots/tidy.ps1", w.createItem("My Bots", "tidy", "script-powershell", json));
        assertThrows(OoaException.class, () -> w.createItem("My Bots", "Expense", "form", json), "no overwriting");
        assertThrows(OoaException.class, () -> w.createItem("My Bots", "x", "virus", json));
        assertEquals("form", w.typeOf(root.resolve("My Bots/Expense.form.json")));
        assertEquals("skill", w.typeOf(root.resolve("ai-skills/summarise_email.json")));
        assertEquals("script", w.typeOf(root.resolve("My Bots/clean.py")));
        assertEquals("file", w.typeOf(root.resolve("My Bots/other.json")), "JSON outside ai-skills is a plain file");

        String form = w.readItem("My Bots/Expense.form.json");
        assertTrue(form.contains("\"fields\""));
        assertThrows(OoaException.class, () -> w.writeItem("My Bots/Expense.form.json", "{not json", JsonLite::parse), "designer files stay valid JSON");
        assertThrows(OoaException.class, () -> w.writeItem("My Bots/Expense.form.json", "[1,2]", JsonLite::parse));
        w.writeItem("My Bots/clean.py", "print('hi')\n", JsonLite::parse);
        assertEquals("print('hi')\n", w.readItem("My Bots/clean.py"));
        assertThrows(OoaException.class, () -> w.writeItem("My Bots/evil.exe", "x", JsonLite::parse), "only text item types can be written");
        assertThrows(OoaException.class, () -> w.writeItem("../outside.py", "x", JsonLite::parse));

        assertEquals("My Bots/Expenses.form.json", w.rename("My Bots/Expense.form.json", "Expenses"), "renaming keeps the item type");
        assertEquals("My Bots/cleanup.py", w.rename("My Bots/clean.py", "cleanup"));
        assertEquals("My Bots/cleanup.txt", w.rename("My Bots/cleanup.py", "cleanup.txt"), "a typed extension wins");

        Files.writeString(root.resolve("My Bots/Child.flow.json"), "{\"kind\":\"ApiTask\",\"id\":\"child_bot\",\"name\":\"Child\",\"steps\":[]}");
        Map<String, Object> tree = w.tree();
        String treeJson = JsonLite.write(tree);
        assertTrue(treeJson.contains("\"kind\":\"ApiTask\""), "the tree tells API Tasks from Task Bots");
        assertTrue(treeJson.contains("\"type\":\"process\"") && treeJson.contains("\"type\":\"document\"") && treeJson.contains("\"modified\""));

        // dependencies: the parent calls the child bot, a script, an AI Skill and a data file
        Files.writeString(root.resolve("My Bots/data.csv"), "a,b\n1,2\n");
        Files.writeString(root.resolve("My Bots/Parent.flow.json"), "{\"kind\":\"TaskBot\",\"id\":\"parent\",\"name\":\"Parent\",\"steps\":["
                + "{\"id\":\"s1\",\"action\":\"core/run_bot\",\"args\":{\"automation\":\"child_bot\"}},"
                + "{\"id\":\"s2\",\"action\":\"ooa.script/run_powershell\",\"args\":{\"file\":\"My Bots/tidy.ps1\"}},"
                + "{\"id\":\"s3\",\"action\":\"ooa.ai_skills/run\",\"args\":{\"skill\":\"summarise_email\"}},"
                + "{\"id\":\"s4\",\"action\":\"ooa.csv/read\",\"args\":{\"path\":\"My Bots/data.csv\"}},"
                + "{\"id\":\"s5\",\"action\":\"core/run_bot\",\"args\":{\"automation\":\"missing_bot\"}}]}");
        StudioServer studio = new StudioServer(root, StudioControlRoomTest.freePort(), Path.of("/nonexistent"));
        Map<String, Object> deps = studio.dependencies("My Bots/Parent.flow.json");
        String d = JsonLite.write(deps);
        assertTrue(d.contains("\"path\":\"My Bots/Child.flow.json\""), d);
        assertTrue(d.contains("\"name\":\"missing_bot\"") && d.contains("\"missing\":true"), "unknown child bots are flagged");
        assertEquals("My Bots/tidy.ps1", ((Map<String, Object>) ((List<Object>) deps.get("scripts")).get(0)).get("path"));
        assertEquals("ai-skills/summarise_email.json", ((Map<String, Object>) ((List<Object>) deps.get("skills")).get(0)).get("path"));
        assertEquals("My Bots/data.csv", ((Map<String, Object>) ((List<Object>) deps.get("files")).get(0)).get("path"));
        List<Object> usedBy = (List<Object>) studio.dependencies("My Bots/Child.flow.json").get("usedBy");
        assertEquals("My Bots/Parent.flow.json", ((Map<String, Object>) usedBy.get(0)).get("path"));
    }

    /** URL-encodes a query value. */
    static final class StudioServerUrl { static String enc(String s) { return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8); } }
}
