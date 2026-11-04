package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Document Automation: /api/docflow/ reaches the DocFlow service with the token, tenant and person; permissions per route. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocFlowProxyTest {
    static final HttpClient HTTP = HttpClient.newHttpClient();
    Db db; ControlRoom cr; ControlRoomServer server; HttpServer docflow;
    String base, token, deviceId;
    final List<Map<String, String>> seen = Collections.synchronizedList(new ArrayList<>());
    /** What the fake DocFlow's review queue holds. */
    volatile String reviewQueue = "[]";

    @BeforeAll void start() throws Exception {
        Path data = Files.createTempDirectory("ooa-docflow-proxy");
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        String pw = cr.bootstrap();
        String t0 = (String) cr.login("admin", pw, null).get("token");
        cr.changePassword(cr.session(t0), pw, "Admin-Passw0rd-2026", t0, null);
        token = (String) cr.login("admin", "Admin-Passw0rd-2026", null).get("token");
        ControlRoom.User admin = cr.session(token);
        cr.createLocker(admin, "docflow", "Document Automation", "user:admin", null);
        cr.setCredential(admin, "docflow", "service", "token", "svc-123", null);

        docflow = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        docflow.createContext("/api/docs/v1/", ex -> {
            byte[] in = ex.getRequestBody().readAllBytes();
            Map<String, String> m = new HashMap<>();
            m.put("method", ex.getRequestMethod());
            m.put("uri", ex.getRequestURI().toString());
            m.put("auth", String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
            m.put("tenant", String.valueOf(ex.getRequestHeaders().getFirst("X-OOA-Tenant")));
            m.put("user", String.valueOf(ex.getRequestHeaders().getFirst("X-OOA-User")));
            m.put("body", new String(in, StandardCharsets.UTF_8));
            seen.add(m);
            String path = ex.getRequestURI().getPath();
            byte[] b = (path.endsWith("/df/review") ? reviewQueue : path.contains("/df/flows") && in.length > 0 ? new String(in, StandardCharsets.UTF_8)
                    : "{\"ok\":true}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        docflow.start();
        cr.platform.updateSettings(admin, Map.of("docflow_url", "http://127.0.0.1:" + docflow.getAddress().getPort(), "docflow_token_ref", "docflow/service/token"), null);
        @SuppressWarnings("unchecked") Map<String, Object> agent = (Map<String, Object>) JsonLite.parse("{\"kind\":\"TaskBot\",\"id\":\"post_invoice\",\"name\":\"Post invoice\",\"description\":\"Posts to the ERP\","
                + "\"variables\":[{\"name\":\"document_id\",\"type\":\"String\",\"direction\":\"input\"},{\"name\":\"invoice_number\",\"type\":\"String\",\"direction\":\"input\"}],"
                + "\"steps\":[{\"id\":\"1\",\"action\":\"core/set\",\"args\":{\"variable\":\"invoice_number\",\"value\":\"x\"}}]}");
        cr.checkin(admin, "Finance/Post invoice.flow.json", agent, "first", true, null);
        deviceId = (String) cr.registerDevice((String) cr.createRegistrationKey(admin, 1, null).get("key"), "PC-1", "pc-1", "Windows 11", "0.2.0", null).get("device_id");
        cr.setDefaultDevice(admin, "admin", deviceId, null);
        server = new ControlRoomServer(cr, "127.0.0.1", 0);
        server.start();
        base = "http://127.0.0.1:" + server.port();
    }

    @AfterAll void stop() { server.stop(); docflow.stop(0); db.close(); }

    HttpResponse<String> call(String method, String path, String body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(base + path)).header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void forwardsWithTokenTenantAndPerson() throws Exception {
        seen.clear();
        HttpResponse<String> r = call("GET", "/api/docflow/df/flows?limit=5", null);
        assertEquals(200, r.statusCode());
        assertEquals("{\"ok\":true}", r.body());
        Map<String, String> m = seen.get(0);
        assertEquals("/api/docs/v1/df/flows?limit=5", m.get("uri"));
        assertEquals("Bearer svc-123", m.get("auth"));
        assertEquals("default", m.get("tenant"));
        assertFalse(m.get("user").isBlank());
    }

    @Test void forwardsTheBody() throws Exception {
        seen.clear();
        assertEquals(200, call("POST", "/api/docflow/df/documents/abc123/review", "{\"action\":\"submit\"}").statusCode());
        assertEquals("POST", seen.get(0).get("method"));
        assertEquals("{\"action\":\"submit\"}", seen.get(0).get("body"));
    }

    @Test void onlyTheScreensRoutesAreForwarded() throws Exception {
        seen.clear();
        assertEquals(404, call("POST", "/api/docflow/admin/update", "{}").statusCode());
        assertEquals(404, call("GET", "/api/docflow/admin/installer.bat", null).statusCode());
        assertEquals(404, call("GET", "/api/docflow/df/../admin/update", null).statusCode());
        assertTrue(seen.isEmpty());
    }

    @Test void permissionsPerRoute() {
        assertEquals("docs.view", DocFlowProxy.permission("GET", "df/flows"));
        assertEquals("docs.review", DocFlowProxy.permission("POST", "df/documents/x1/review"));
        assertEquals("docs.manage", DocFlowProxy.permission("POST", "df/flows"));
        assertEquals("docs.manage", DocFlowProxy.permission("POST", "df/flows/ap/documents"));
        assertEquals("docs.engines", DocFlowProxy.permission("GET", "admin/settings"));
        assertEquals("docs.engines", DocFlowProxy.permission("PUT", "admin/settings"));
        assertTrue(Rbac.allowed("viewer", "docs.view"));
        assertFalse(Rbac.allowed("viewer", "docs.review"));
        assertTrue(Rbac.allowed("bot_runner", "docs.review"));
        assertFalse(Rbac.allowed("bot_runner", "docs.manage"));
        assertTrue(Rbac.allowed("bot_creator", "docs.manage"));
        assertFalse(Rbac.allowed("bot_creator", "docs.engines"));
        assertTrue(Rbac.allowed("admin", "docs.engines"));
    }

    @Test void aViewerCannotChangeAnything() {
        ControlRoom.User admin = cr.session(token);
        cr.createUser(admin, "vic", "Vic Viewer", "viewer", null);
        ControlRoom.User vic = new ControlRoom.User("vic", "vic", "viewer", "Vic Viewer", false);
        DocFlowProxy p = new DocFlowProxy(cr);
        assertEquals("CR.FORBIDDEN", assertThrows(OoaException.class, () -> p.forward(null, vic, "POST", "df/flows")).code());
    }

    @Test void settingsCheckTheAddressAndReference() {
        ControlRoom.User admin = cr.session(token);
        assertThrows(OoaException.class, () -> cr.platform.updateSettings(admin, Map.of("docflow_url", "ftp://x"), null));
        assertThrows(OoaException.class, () -> cr.platform.updateSettings(admin, Map.of("docflow_token_ref", "just-a-token"), null));
    }

    @Test void unreachableServiceSaysWhatToDo() throws Exception {
        ControlRoom.User admin = cr.session(token);
        Object before = cr.platform.setting("docflow_url");
        cr.platform.updateSettings(admin, Map.of("docflow_url", "http://127.0.0.1:1"), null);
        try {
            HttpResponse<String> r = call("GET", "/api/docflow/df/overview", null);
            assertEquals(502, r.statusCode());
            assertTrue(r.body().contains("DOCS.UNAVAILABLE"), r.body());
        } finally { cr.platform.updateSettings(admin, Map.of("docflow_url", before), null); }
    }

    @Test void documentsWaitingForReviewBecomeTasksForReviewersAndCloseWhenReviewed() throws Exception {
        String now = java.time.Instant.now().toString().replace("Z", "+00:00");
        reviewQueue = "[{\"id\":\"doc1\",\"file_name\":\"INV-1.pdf\",\"created_at\":\"" + now + "\",\"flowId\":\"ap\",\"flowName\":\"AP invoices\","
                + "\"doctypeName\":\"Invoice\",\"slaHours\":8,\"flags\":[{\"message\":\"Totals do not add up\"}]},"
                + "{\"id\":\"doc2\",\"file_name\":\"INV-2.pdf\",\"created_at\":\"" + now + "\",\"flowId\":\"ap\",\"flowName\":\"AP invoices\",\"doctypeName\":\"Invoice\",\"slaHours\":8,\"flags\":[]}]";
        DocFlowProxy p = new DocFlowProxy(cr);
        assertArrayEquals(new int[] {2, 0}, p.syncReviews());
        assertArrayEquals(new int[] {0, 0}, p.syncReviews());                 // already there: nothing twice
        ControlRoom.User admin = cr.session(token);
        cr.createUser(admin, "rita", "Rita Reviewer", "bot_runner", null);
        cr.createUser(admin, "vera", "Vera Viewer", "viewer", null);
        ControlRoom.User rita = new ControlRoom.User("rita", "rita", "bot_runner", "Rita Reviewer", false);
        ControlRoom.User vera = new ControlRoom.User("vera", "vera", "viewer", "Vera Viewer", false);
        List<Map<String, Object>> ritas = cr.workflows.tasks(rita, "open", false);
        assertEquals(2, ritas.size());
        Map<String, Object> t = ritas.stream().filter(x -> x.get("title").toString().contains("INV-1")).findFirst().orElseThrow();
        assertEquals("document_review", t.get("kind"));
        assertEquals("Invoice · Totals do not add up", t.get("description"));
        assertEquals("/documents/#/review/doc1", ((Map<?, ?>) t.get("data")).get("link"));
        assertNotNull(t.get("due"));
        assertTrue(cr.workflows.tasks(vera, "open", false).isEmpty());        // viewers do not review
        assertEquals("CR.BAD_INPUT", assertThrows(OoaException.class, () -> cr.workflows.complete(rita, (String) t.get("id"), "done", Map.of(), null, null)).code());

        // approved on the review screen: the task closes as that person
        assertEquals(200, call("POST", "/api/docflow/df/documents/doc1/review", "{\"action\":\"submit\"}").statusCode());
        Map<String, Object> done = cr.db.one("SELECT status, outcome, completed_by FROM human_tasks WHERE id = ?", t.get("id"));
        assertEquals(List.of("completed", "approved", "admin"), List.of(done.get("status"), done.get("outcome"), done.get("completed_by")));

        // doc2 left the queue some other way: the next sync closes its task
        reviewQueue = "[]";
        assertArrayEquals(new int[] {0, 1}, p.syncReviews());
        assertTrue(cr.workflows.tasks(rita, "open", false).isEmpty());
    }

    @Test void aStartABotDeliveryRunsAsThePersonWhoSavesIt() throws Exception {
        seen.clear();
        String flow = "{\"name\":\"AP\",\"deliveries\":[{\"id\":\"d1\",\"type\":\"start_bot\",\"name\":\"ERP\",\"params\":{\"bot\":\"Finance/Post invoice\",\"url\":\"http://evil.example\",\"requestedBy\":\"someone\"}},"
                + "{\"id\":\"d2\",\"type\":\"webhook\",\"params\":{\"url\":\"https://erp.example/hook\"}}]}";
        assertEquals(200, call("PUT", "/api/docflow/df/flows/ap", flow).statusCode());
        @SuppressWarnings("unchecked") Map<String, Object> sent = (Map<String, Object>) JsonLite.parse(seen.get(0).get("body"));
        @SuppressWarnings("unchecked") Map<String, Object> bot = (Map<String, Object>) ((Map<String, Object>) ((List<Object>) sent.get("deliveries")).get(0)).get("params");
        assertEquals(Map.of("bot", "Finance/Post invoice.flow.json", "platform", true, "requestedBy", "admin"), bot);
        @SuppressWarnings("unchecked") Map<String, Object> hook = (Map<String, Object>) ((Map<String, Object>) ((List<Object>) sent.get("deliveries")).get(1)).get("params");
        assertEquals("https://erp.example/hook", hook.get("url"));          // other deliveries are left as they are
    }

    @Test void docFlowStartsTheBotWithTheServiceTokenOnly() throws Exception {
        String body = "{\"bot\":\"Finance/Post invoice.flow.json\",\"pool\":\"\",\"requestedBy\":\"admin\",\"input\":{\"documentId\":\"doc9\",\"flowId\":\"ap\",\"flowName\":\"AP\","
                + "\"status\":\"success\",\"fields\":{\"invoice_number\":\"INV-9\",\"total\":115}}}";
        HttpResponse<String> wrong = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/api/docflow-callback/start-bot")).header("Authorization", "Bearer nope").header("X-OOA-Tenant", "default")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(401, wrong.statusCode());
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/api/docflow-callback/start-bot")).header("Authorization", "Bearer svc-123").header("X-OOA-Tenant", "default")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        String runId = String.valueOf(((Map<?, ?>) JsonLite.parse(r.body())).get("run_id"));
        Map<String, Object> run = cr.db.one("SELECT bot_path, device_id, requested_by, source, inputs FROM runs WHERE id = ?", runId);
        assertEquals("Finance/Post invoice.flow.json", run.get("bot_path"));
        assertEquals(deviceId, run.get("device_id"));
        assertEquals("docflow:ap", run.get("source"));
        assertEquals(Map.of("document_id", "doc9", "invoice_number", "INV-9"), JsonLite.parse((String) run.get("inputs")));
    }
}
