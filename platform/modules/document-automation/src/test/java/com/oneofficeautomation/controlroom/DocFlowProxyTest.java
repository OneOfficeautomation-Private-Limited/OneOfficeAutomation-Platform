package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.controlroom.repository.Lifecycle;
import com.oneofficeautomation.controlroom.repository.Templates;
import com.oneofficeautomation.controlroom.runs.TriggerKinds;
import com.oneofficeautomation.modules.integration.McpServer;

import com.oneofficeautomation.modules.docauto.DocFlowConfig;
import com.oneofficeautomation.modules.docauto.DocFlowProxy;
import com.oneofficeautomation.modules.docauto.DocTemplates;

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
    /** Document Automation's proxy as the module builds it, with a fresh context of the module running in this One CommandCenter. */
    static DocFlowProxy proxy(ControlRoom cr) { return new DocFlowProxy(cr.modules.contextFor("document-automation")); }

    /** An empty agent definition (as the kernel's LicensingAndChecksTest makes one). */
    @SuppressWarnings("unchecked")
    static Map<String, Object> agent(String id) {
        return (Map<String, Object>) JsonLite.parse("{\"kind\":\"TaskBot\",\"id\":\"" + id + "\",\"name\":\"" + id + "\",\"description\":\"t\",\"variables\":[],\"steps\":[]}");
    }

    static final HttpClient HTTP = HttpClient.newHttpClient();
    Db db; ControlRoom cr; ControlRoomServer server; HttpServer docflow;
    String base, token, deviceId, deviceSecret;
    final List<Map<String, String>> seen = Collections.synchronizedList(new ArrayList<>());
    /** What the fake DocFlow's review queue holds. */
    volatile String reviewQueue = "[]";
    /** What the fake DocFlow lists as processes. */
    volatile String flowsJson = "[]";
    /** What the fake DocFlow says its version is. */
    static final String PINNED = DocFlowProxy.COMPAT.getProperty("pinned");
    volatile String versionJson = "{\"version\":\"" + PINNED + "\"}";
    /** Answers of the fake DocFlow for "METHOD path" (path after /api/docs/v1/): status and body. */
    final Map<String, Object[]> canned = new java.util.concurrent.ConcurrentHashMap<>();

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
            Object[] c = canned.get(ex.getRequestMethod() + " " + path.substring("/api/docs/v1/".length()));
            if (c != null) {
                byte[] cb = String.valueOf(c[1]).getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "application/json");
                ex.sendResponseHeaders((Integer) c[0], cb.length);
                ex.getResponseBody().write(cb);
                ex.close();
                return;
            }
            byte[] b = (path.endsWith("/documents") && ex.getRequestMethod().equals("POST") ? "{\"documents\":[{\"documentId\":\"d7\",\"status\":\"processing\"}]}"
                    : path.endsWith("/df/documents/d7") ? "{\"id\":\"d7\",\"status\":\"validation\",\"file_name\":\"INV-7.pdf\",\"doctype_id\":\"invoice\",\"fields\":{\"total\":115},\"confidence\":{\"total\":0.62}}"
                    : path.endsWith("/admin/version") ? versionJson
                    : path.endsWith("/df/review") ? reviewQueue : path.endsWith("/df/flows") && ex.getRequestMethod().equals("GET") ? flowsJson : path.contains("/df/flows") && in.length > 0 ? new String(in, StandardCharsets.UTF_8)
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
        Map<String, Object> dev = cr.registerDevice((String) cr.createRegistrationKey(admin, 1, null).get("key"), "PC-1", "pc-1", "Windows 11", "0.2.0", null);
        deviceId = (String) dev.get("device_id");
        deviceSecret = (String) dev.get("secret");
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
        assertEquals("[]", r.body());
        Map<String, String> m = got("GET", "/api/docs/v1/df/flows?limit=5");
        assertEquals("/api/docs/v1/df/flows?limit=5", m.get("uri"));
        assertEquals("Bearer svc-123", m.get("auth"));
        assertEquals("default", m.get("tenant"));
        assertFalse(m.get("user").isBlank());
    }

    @Test void forwardsTheBody() throws Exception {
        seen.clear();
        assertEquals(200, call("POST", "/api/docflow/df/documents/abc123/review", "{\"action\":\"submit\"}").statusCode());
        assertEquals("{\"action\":\"submit\"}", got("POST", "/api/docs/v1/df/documents/abc123/review").get("body"));
    }

    @Test void onlyTheScreensRoutesAreForwarded() throws Exception {
        seen.clear();
        assertEquals(404, call("POST", "/api/docflow/admin/update", "{}").statusCode());
        assertEquals(404, call("GET", "/api/docflow/admin/installer.bat", null).statusCode());
        assertEquals(404, call("GET", "/api/docflow/df/../admin/update", null).statusCode());
        assertTrue(seen.isEmpty());
    }

    @Test void docFlowVersionsAreComparedWithTheTestedOne() {
        assertEquals("compatible", DocFlowProxy.compare("3.1.0", "3.1.0", "3.1.0"));
        assertEquals("compatible", DocFlowProxy.compare("3.1.4", "3.1.0", "3.2.0"));     // between the minimum and the tested version
        assertEquals("newer", DocFlowProxy.compare("3.4.0", "3.1.0", "3.1.0"));
        assertEquals("too_old", DocFlowProxy.compare("3.0.9", "3.1.0", "3.1.0"));
        assertEquals("too_old", DocFlowProxy.compare("2.5.0", "3.1.0", "3.1.0"));
        assertEquals("other_contract", DocFlowProxy.compare("4.0.0", "3.1.0", "3.1.0"));
        assertEquals("compatible", DocFlowProxy.compare("v3.1", "3.1.0", "3.1.0"));
        assertEquals("unknown", DocFlowProxy.compare("dev", "3.1.0", "3.1.0"));
    }

    @Test void thePageSaysWhetherTheDocFlowInUseFits() throws Exception {
        try {
            HttpResponse<String> r = call("GET", "/api/docflow/version", null);
            assertEquals(200, r.statusCode(), r.body());
            Map<?, ?> v = (Map<?, ?>) JsonLite.parse(r.body());
            assertEquals(List.of("compatible", true, PINNED, PINNED), List.of(v.get("status"), v.get("compatible"), v.get("version"), v.get("tested")));
            versionJson = "{\"version\":\"4.0.0\"}";
            Map<String, Object> other = proxy(cr).version();
            assertEquals(List.of("other_contract", false), List.of(other.get("status"), other.get("compatible")));
            assertTrue(String.valueOf(other.get("message")).contains("Install DocFlow " + PINNED));
            versionJson = "{\"version\":\"3.0.0\"}";
            assertEquals("too_old", proxy(cr).version().get("status"));
            versionJson = "{}";
            assertEquals("unknown", proxy(cr).version().get("status"));
        } finally { versionJson = "{\"version\":\"" + PINNED + "\"}"; }
        assertTrue(Rbac.allowed("viewer", "docs.view"));                       // everyone who may see the page may see this
    }

    @Test void withoutSettingsTheContainersAddressAndTokenAreUsed() throws Exception {
        ControlRoom.User admin = cr.session(token);
        String url = "http://127.0.0.1:" + docflow.getAddress().getPort();
        var before = DocFlowProxy.env;
        try {
            cr.platform.updateSettings(admin, Map.of("docflow_url", "", "docflow_token_ref", ""), null);
            DocFlowProxy.env = k -> null;
            DocFlowProxy p = proxy(cr);
            assertFalse(p.configured());
            assertEquals(DocFlowProxy.DEFAULT_URL, p.baseUrl());
            // what the platform's containers and installer set when they start DocFlow beside the Command Center
            DocFlowProxy.env = k -> switch (k) { case "OOA_DOCFLOW_URL" -> url + "/"; case "OOA_DOCFLOW_TOKEN" -> "env-tok"; default -> null; };
            assertTrue(p.configured());
            assertEquals(url, p.baseUrl());
            seen.clear();
            assertEquals(200, call("GET", "/api/docflow/df/flows", null).statusCode());
            assertEquals("Bearer env-tok", seen.get(0).get("auth"));
            // Settings > Document Automation still wins
            cr.platform.updateSettings(admin, Map.of("docflow_url", url, "docflow_token_ref", "docflow/service/token"), null);
            seen.clear();
            assertEquals(200, call("GET", "/api/docflow/df/flows", null).statusCode());
            assertEquals("Bearer svc-123", seen.get(0).get("auth"));
        } finally {
            DocFlowProxy.env = before;
            cr.platform.updateSettings(admin, Map.of("docflow_url", url, "docflow_token_ref", "docflow/service/token"), null);
        }
    }

    @Test void theWindowsInstallersFileIsUsedLastAndKeepsTheTokenEncrypted() throws Exception {
        ControlRoom.User admin = cr.session(token);
        String url = "http://127.0.0.1:" + docflow.getAddress().getPort();
        var before = DocFlowProxy.env;
        Path file = DocFlowConfig.file(cr.dataDir);
        try {
            cr.platform.updateSettings(admin, Map.of("docflow_url", "", "docflow_token_ref", ""), null);
            DocFlowProxy.env = k -> null;
            DocFlowConfig.save(cr.dataDir, cr.vault, url, "file-tok");    // what ooa docflow configure writes
            String saved = Files.readString(file);
            assertTrue(saved.contains("token=enc:"), saved);
            assertFalse(saved.contains("file-tok"), "the token is never kept in clear text");
            DocFlowProxy p = proxy(cr);
            assertTrue(p.configured());
            assertEquals(url, p.baseUrl());
            seen.clear();
            assertEquals(200, call("GET", "/api/docflow/df/flows", null).statusCode());
            assertEquals("Bearer file-tok", seen.get(0).get("auth"));
            // the containers' variables come before the file, and Settings before both
            DocFlowProxy.env = k -> k.equals("OOA_DOCFLOW_TOKEN") ? "env-tok" : null;
            seen.clear();
            call("GET", "/api/docflow/df/flows", null);
            assertEquals("Bearer env-tok", seen.get(0).get("auth"));
        } finally {
            DocFlowProxy.env = before;
            Files.deleteIfExists(file);
            cr.platform.updateSettings(admin, Map.of("docflow_url", url, "docflow_token_ref", "docflow/service/token"), null);
        }
    }

    @Test void permissionsPerRoute() {
        assertEquals("docs.view", DocFlowProxy.permission("GET", "df/flows"));
        assertEquals("docs.review", DocFlowProxy.permission("POST", "df/documents/x1/review"));
        assertEquals("docs.manage", DocFlowProxy.permission("POST", "df/flows"));
        assertEquals("docs.manage", DocFlowProxy.permission("POST", "df/flows/ap/documents"));
        assertEquals("docs.engines", DocFlowProxy.permission("GET", "admin/settings"));
        assertEquals("docs.engines", DocFlowProxy.permission("PUT", "admin/settings"));
        // test sets: everyone who sees Document Automation sees them; making and running them is changing it
        assertEquals("docs.view", DocFlowProxy.permission("GET", "df/testsets"));
        assertEquals("docs.view", DocFlowProxy.permission("GET", "df/testsets/seed-1/runs/r1"));
        assertEquals("docs.view", DocFlowProxy.permission("GET", "df/testsets/seed-1/items/i1/file"));
        assertEquals("docs.manage", DocFlowProxy.permission("POST", "df/testsets/seed"));
        assertEquals("docs.manage", DocFlowProxy.permission("POST", "df/testsets/seed-1/runs"));
        assertEquals("docs.manage", DocFlowProxy.permission("POST", "df/testsets/seed-1/documents"));
        assertEquals("docs.manage", DocFlowProxy.permission("PUT", "df/testsets/seed-1/items/i1"));
        assertEquals("docs.manage", DocFlowProxy.permission("DELETE", "df/testsets/seed-1"));
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
        DocFlowProxy p = proxy(cr);
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
        DocFlowProxy p = proxy(cr);
        assertArrayEquals(new int[] {2, 0}, p.syncReviews());
        assertArrayEquals(new int[] {0, 0}, p.syncReviews());                 // already there: nothing twice
        ControlRoom.User admin = cr.session(token);
        cr.createUser(admin, "rita", "Rita Reviewer", "bot_runner", null);
        cr.createUser(admin, "vera", "Vera Viewer", "viewer", null);
        ControlRoom.User rita = new ControlRoom.User("rita", "rita", "bot_runner", "Rita Reviewer", false);
        ControlRoom.User vera = new ControlRoom.User("vera", "vera", "viewer", "Vera Viewer", false);
        List<Map<String, Object>> ritas = cr.tasks.tasks(rita, "open", false);
        assertEquals(2, ritas.size());
        Map<String, Object> t = ritas.stream().filter(x -> x.get("title").toString().contains("INV-1")).findFirst().orElseThrow();
        assertEquals("document_review", t.get("kind"));
        assertEquals("Invoice · Totals do not add up", t.get("description"));
        assertEquals("/documents/#/review/doc1", ((Map<?, ?>) t.get("data")).get("link"));
        assertNotNull(t.get("due"));
        assertTrue(cr.tasks.tasks(vera, "open", false).isEmpty());        // viewers do not review
        assertEquals("CR.BAD_INPUT", assertThrows(OoaException.class, () -> cr.tasks.complete(rita, (String) t.get("id"), "done", Map.of(), null, null)).code());

        // approved on the review screen: the task closes as that person
        assertEquals(200, call("POST", "/api/docflow/df/documents/doc1/review", "{\"action\":\"submit\"}").statusCode());
        Map<String, Object> done = cr.db.one("SELECT status, outcome, completed_by FROM human_tasks WHERE id = ?", t.get("id"));
        assertEquals(List.of("completed", "approved", "admin"), List.of(done.get("status"), done.get("outcome"), done.get("completed_by")));

        // doc2 left the queue some other way: the next sync closes its task
        reviewQueue = "[]";
        assertArrayEquals(new int[] {0, 1}, p.syncReviews());
        assertTrue(cr.tasks.tasks(rita, "open", false).isEmpty());
    }

    @Test void aStartABotDeliveryRunsAsThePersonWhoSavesIt() throws Exception {
        seen.clear();
        String flow = "{\"name\":\"AP\",\"deliveries\":[{\"id\":\"d1\",\"type\":\"start_bot\",\"name\":\"ERP\",\"params\":{\"bot\":\"Finance/Post invoice\",\"url\":\"http://evil.example\",\"requestedBy\":\"someone\"}},"
                + "{\"id\":\"d2\",\"type\":\"webhook\",\"params\":{\"url\":\"https://erp.example/hook\"}}]}";
        assertEquals(200, call("PUT", "/api/docflow/df/flows/ap", flow).statusCode());
        @SuppressWarnings("unchecked") Map<String, Object> sent = (Map<String, Object>) JsonLite.parse(got("PUT", "/api/docs/v1/df/flows/ap").get("body"));
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

    @Test void attachmentsAreReadFromARawMessage() {
        String raw = "From: a@example.com\r\nSubject: Invoices\r\nMIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=\"outer\"\r\n\r\n"
                + "--outer\r\nContent-Type: multipart/alternative; boundary=\"inner\"\r\n\r\n"
                + "--inner\r\nContent-Type: text/plain; charset=utf-8\r\n\r\nPlease find attached.\r\n--inner--\r\n"
                + "--outer\r\nContent-Type: application/pdf; name=\"INV-1.pdf\"\r\nContent-Disposition: attachment; filename=\"INV-1.pdf\"\r\nContent-Transfer-Encoding: base64\r\n\r\n"
                + Base64.getMimeEncoder().encodeToString("%PDF-1.4 test".getBytes(StandardCharsets.UTF_8)) + "\r\n"
                + "--outer\r\nContent-Type: text/plain\r\nContent-Disposition: attachment; filename*=UTF-8''%D9%81%D8%A7%D8%AA%D9%88%D8%B1%D8%A9.txt\r\nContent-Transfer-Encoding: quoted-printable\r\n\r\nTotal =3D 10\r\n"
                + "--outer--\r\n";
        List<MailSource.Attachment> a = ImapMailSource.attachmentsOf(raw.getBytes(StandardCharsets.ISO_8859_1));
        assertEquals(2, a.size());
        assertEquals("INV-1.pdf", a.get(0).name());
        assertEquals("application/pdf", a.get(0).contentType());
        assertEquals("%PDF-1.4 test", new String(a.get(0).data(), StandardCharsets.UTF_8));
        assertEquals("فاتورة.txt", a.get(1).name());
        assertEquals("Total = 10", new String(a.get(1).data(), StandardCharsets.UTF_8).trim());
    }

    @Test void aLiveProcessReadsItsEmailInbox() {
        flowsJson = "[{\"id\":\"ap\",\"status\":\"live\",\"intake\":{\"email\":{\"enabled\":true,\"kind\":\"email\",\"host\":\"mail.example\",\"subject_contains\":\"invoice\",\"requestedBy\":\"admin\"}}},"
                + "{\"id\":\"draft\",\"status\":\"draft\",\"intake\":{\"email\":{\"enabled\":true}}}]";
        List<String> cursors = new ArrayList<>();
        DocFlowProxy p = proxy(cr);
        p.mails = email -> new MailSource() {
            @Override public Batch fetch(String cursor, boolean fireExisting) {
                cursors.add(String.valueOf(cursor));
                return new Batch("c" + cursors.size(), List.of(Map.of("id", "1", "from", "Supplier <s@example.com>", "subject", "Invoice 42"),
                        Map.of("id", "2", "from", "x@example.com", "subject", "Lunch")));
            }
            @Override public List<Attachment> attachments(Map<String, Object> m) {
                return List.of(new Attachment("INV-42.pdf", "application/pdf", "%PDF".getBytes(StandardCharsets.UTF_8)), new Attachment("notes.docx", "application/msword", new byte[] {1}));
            }
        };
        seen.clear();
        assertEquals(1, p.pollMail());                       // one message passes the subject filter; the .docx is not read
        Map<String, String> up = seen.stream().filter(m -> m.get("method").equals("POST")).findFirst().orElseThrow();
        assertEquals("/api/docs/v1/df/flows/ap/documents?source=email&requireLive=true", up.get("uri"));
        assertTrue(up.get("body").contains("filename=\"INV-42.pdf\""));
        assertTrue(java.net.URLDecoder.decode(up.get("user"), StandardCharsets.UTF_8).startsWith("Email from Supplier"));
        assertEquals(0, p.pollMail());                       // not again before its interval
        Map<String, Object> row = cr.db.one("SELECT mail_cursor, documents, last_error FROM docflow_mail WHERE process_id = 'ap'");
        assertEquals("c1", row.get("mail_cursor"));
        assertEquals(1, ((Number) row.get("documents")).intValue());
        assertNull(row.get("last_error"));
        assertEquals(List.of("null"), cursors);              // the draft process's inbox is not read
        flowsJson = "[]";
    }

    @Test void anInboxUsesAVaultSecretItsAuthorMayUse() throws Exception {
        ControlRoom.User admin = cr.session(token);
        cr.createLocker(admin, "others", "Not for admin", "role:viewer", null);
        seen.clear();
        String ok = "{\"name\":\"AP\",\"intake\":{\"email\":{\"enabled\":true,\"kind\":\"email\",\"host\":\"mail.example\",\"password_ref\":\"docflow/service/token\"}}}";
        assertEquals(200, call("PUT", "/api/docflow/df/flows/ap", ok).statusCode());
        assertTrue(got("PUT", "/api/docs/v1/df/flows/ap").get("body").contains("\"requestedBy\":\"admin\""));
        String denied = ok.replace("docflow/service/token", "others/mailbox/password");
        HttpResponse<String> r = call("PUT", "/api/docflow/df/flows/ap", denied);
        assertEquals(403, r.statusCode(), r.body());
        assertTrue(r.body().contains("VAULT.DENIED"));
    }

    @Test void aRunningAgentSendsADocumentToAProcessByName() throws Exception {
        Map<String, Object> run = cr.startRun("admin", "admin", "Finance/Post invoice.flow.json", deviceId, Map.of(), "test", null);
        String runId = String.valueOf(run.get("run_id"));
        flowsJson = "[{\"id\":\"ap-invoices\",\"name\":\"AP invoices\",\"status\":\"live\"}]";
        String boundary = "b1";
        String body = "--b1\r\nContent-Disposition: form-data; name=\"files\"; filename=\"INV-7.pdf\"\r\nContent-Type: application/octet-stream\r\n\r\n%PDF\r\n--b1--\r\n";
        java.util.function.Function<String, HttpResponse<String>> send = q -> {
            try {
                return HTTP.send(HttpRequest.newBuilder(URI.create(base + "/api/agent/docflow/documents?" + q)).header("X-OOA-Device", deviceId).header("X-OOA-Device-Secret", deviceSecret)
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary).POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            } catch (Exception e) { throw new RuntimeException(e); }
        };
        assertEquals(403, send.apply("run_id=" + runId + "&process=AP%20invoices").statusCode());   // queued, not running yet
        cr.db.update("UPDATE runs SET status = 'running' WHERE id = ?", runId);
        seen.clear();
        HttpResponse<String> r = send.apply("run_id=" + runId + "&process=ap%20INVOICES&wait=true");
        assertEquals(200, r.statusCode(), r.body());
        Map<?, ?> out = (Map<?, ?>) JsonLite.parse(r.body());
        assertEquals(List.of("d7", "AP invoices", "validation", true, "115"),
                List.of(out.get("document_id"), out.get("process"), out.get("status"), out.get("needs_review"), String.valueOf(((Map<?, ?>) out.get("fields")).get("total"))));
        Map<String, String> up = seen.stream().filter(m -> m.get("method").equals("POST")).findFirst().orElseThrow();
        assertEquals("/api/docs/v1/df/flows/ap-invoices/documents?source=bot&requireLive=true&wait=true", up.get("uri"));
        assertTrue(java.net.URLDecoder.decode(up.get("user"), StandardCharsets.UTF_8).startsWith("Agent Finance/Post invoice (admin)"));
        assertEquals(404, send.apply("run_id=" + runId + "&process=Nope").statusCode());
        flowsJson = "[]";
    }

    /** D1 (command review, batch 9): a running agent reads a result by id, lists finished documents and marks them collected. */
    @Test @SuppressWarnings("unchecked")
    void aRunningAgentPicksUpFinishedDocumentsOnce() throws Exception {
        Map<String, Object> run = cr.startRun("admin", "admin", "Finance/Post invoice.flow.json", deviceId, Map.of(), "test", null);
        String runId = String.valueOf(run.get("run_id"));
        flowsJson = "[{\"id\":\"ap-results\",\"name\":\"AP results\",\"status\":\"live\"}]";
        canned.put("GET df/documents/r1", new Object[]{200, "{\"id\":\"r1\",\"flowId\":\"ap-results\",\"status\":\"validated\",\"file_name\":\"INV-1.pdf\",\"doctype_id\":\"invoice\","
                + "\"validated_by\":\"ann\",\"fields\":{\"total\":115,\"lines\":[{\"item\":\"Laptop\",\"qty\":3}]},\"confidence\":{\"total\":0.62}}"});
        canned.put("GET df/documents/r2", new Object[]{200, "{\"id\":\"r2\",\"flowId\":\"ap-results\",\"status\":\"success\",\"fields\":{}}"});
        canned.put("GET df/documents/gone", new Object[]{404, "{\"detail\":\"not found\"}"});
        canned.put("GET df/flows/ap-results/documents", new Object[]{200, "[{\"id\":\"r1\",\"status\":\"validated\",\"file_name\":\"INV-1.pdf\",\"validated_by\":\"ann\"},"
                + "{\"id\":\"r2\",\"status\":\"success\",\"file_name\":\"INV-2.pdf\"},{\"id\":\"r3\",\"status\":\"processing\"},{\"id\":\"r4\",\"status\":\"validation\"}]"});
        java.util.function.Function<String, HttpResponse<String>> ask = body -> {
            try {
                return HTTP.send(HttpRequest.newBuilder(URI.create(base + "/api/agent/docflow/results")).header("X-OOA-Device", deviceId).header("X-OOA-Device-Secret", deviceSecret)
                        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.replace("RUN", runId))).build(), HttpResponse.BodyHandlers.ofString());
            } catch (Exception e) { throw new RuntimeException(e); }
        };
        try {
            assertEquals(403, ask.apply("{\"run_id\":\"RUN\",\"op\":\"get\",\"document_id\":\"r1\"}").statusCode(), "queued, not running yet");
            cr.db.update("UPDATE runs SET status = 'running' WHERE id = ?", runId);
            HttpResponse<String> got = ask.apply("{\"run_id\":\"RUN\",\"op\":\"get\",\"document_id\":\"r1\"}");
            assertEquals(200, got.statusCode(), got.body());
            Map<String, Object> g = (Map<String, Object>) JsonLite.parse(got.body());
            assertEquals(List.of("validated", true, true, false, "ann"), List.of(g.get("status"), g.get("done"), g.get("reviewed"), g.get("needs_review"), g.get("validated_by")));
            assertEquals("115", String.valueOf(((Map<?, ?>) g.get("fields")).get("total")));
            assertFalse(((Map<?, ?>) g.get("fields")).containsKey("lines"), "a list of rows is a table, not a field");
            assertEquals(1, ((List<?>) ((Map<?, ?>) g.get("tables")).get("lines")).size());
            assertEquals(404, ask.apply("{\"run_id\":\"RUN\",\"op\":\"get\",\"document_id\":\"gone\"}").statusCode());
            assertEquals(400, ask.apply("{\"run_id\":\"RUN\",\"op\":\"get\",\"document_id\":\"../admin\"}").statusCode());

            java.util.function.Supplier<List<Object>> ids = () -> ((List<Object>) ((Map<String, Object>) JsonLite.parse(
                    ask.apply("{\"run_id\":\"RUN\",\"op\":\"list\",\"process\":\"ap RESULTS\",\"collector\":\"posting\"}").body())).get("documents"))
                    .stream().map(o -> (Object) ((Map<?, ?>) o).get("document_id")).toList();
            assertEquals(List.of("r1", "r2"), ids.get(), "only finished documents: not those being read or waiting for review");
            HttpResponse<String> mark = ask.apply("{\"run_id\":\"RUN\",\"op\":\"collect\",\"document_ids\":[\"r1\"],\"collector\":\"posting\"}");
            assertEquals(200, mark.statusCode(), mark.body());
            assertEquals("1", String.valueOf(((Map<?, ?>) JsonLite.parse(mark.body())).get("marked")));
            assertEquals(List.of("r2"), ids.get(), "a collected document is not listed again");
            assertEquals("1", String.valueOf(((Map<?, ?>) JsonLite.parse(ask.apply("{\"run_id\":\"RUN\",\"op\":\"collect\",\"document_ids\":[\"r1\"],\"collector\":\"posting\"}").body()))
                    .get("already_collected")), "marking twice is harmless");
            List<Object> otherCollector = (List<Object>) ((Map<String, Object>) JsonLite.parse(ask.apply("{\"run_id\":\"RUN\",\"op\":\"list\",\"process\":\"AP results\"}").body())).get("documents");
            assertEquals(2, otherCollector.size(), "another collector name has its own marks");
            assertEquals(1, cr.db.query("SELECT document_id FROM docflow_collected WHERE collector = 'posting' AND collected_by = 'admin' AND process_id = 'ap-results'").size());
            assertEquals(404, ask.apply("{\"run_id\":\"RUN\",\"op\":\"list\",\"process\":\"Nope\"}").statusCode());
        } finally {
            canned.clear();
            flowsJson = "[]";
        }
    }

    @Test void inboxProblemsAreSaidPlainly() {
        Map<String, Object> email = Map.of("host", "imap.example.com", "port", 993);
        assertEquals("The mail server imap.example.com is not known: check its name", DocFlowProxy.problem(new java.net.UnknownHostException("imap.example.com"), email));
        assertEquals("The mail server imap.example.com does not answer on port 993", DocFlowProxy.problem(new java.net.ConnectException("refused"), email));
        assertEquals("Sign-in to the mailbox failed: NO", DocFlowProxy.problem(new java.io.IOException("Sign-in to the mailbox failed: NO"), email));
    }

    /** A signed-in session for a new user with this role. */
    String sessionFor(String name, String role) {
        ControlRoom.User admin = cr.session(token);
        String temp = String.valueOf(cr.createUser(admin, name, name, role, null).get("temporary_password"));
        String t0 = (String) cr.login(name, temp, null).get("token");
        cr.changePassword(cr.session(t0), temp, "Folder-Passw0rd-2026", t0, null);
        return (String) cr.login(name, "Folder-Passw0rd-2026", null).get("token");
    }

    HttpResponse<String> as(String session, String method, String path, String body, String type) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(base + path)).header("Authorization", "Bearer " + session).header("Content-Type", type)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void processesFollowTheirFoldersPermissions() throws Exception {
        ControlRoom.User admin = cr.session(token);
        String fiona = sessionFor("fiona", "bot_creator"), rob = sessionFor("rob", "bot_runner");
        cr.folders.setPermissions(admin, "Finance/Accounts payable", List.of(Map.of("principal", "user:fiona", "access", "run")), null);
        flowsJson = "[{\"id\":\"ap\",\"name\":\"AP\",\"folder\":\"Finance / Accounts payable\",\"status\":\"live\"},{\"id\":\"open\",\"name\":\"Open\",\"folder\":\"\",\"status\":\"live\"}]";
        try {
            assertEquals("[\"ap\", \"open\"]", ids(as(fiona, "GET", "/api/docflow/df/flows", null, "application/json").body()));
            assertEquals("[\"open\"]", ids(as(rob, "GET", "/api/docflow/df/flows", null, "application/json").body()));
            assertEquals(403, as(rob, "GET", "/api/docflow/df/flows/ap", null, "application/json").statusCode());
            String multipart = "--b\r\nContent-Disposition: form-data; name=\"files\"; filename=\"a.pdf\"\r\n\r\n%PDF\r\n--b--\r\n";
            assertEquals(200, as(fiona, "POST", "/api/docflow/df/flows/ap/documents", multipart, "multipart/form-data; boundary=b").statusCode());   // run: may add documents
            assertEquals(403, as(fiona, "PUT", "/api/docflow/df/flows/ap", "{\"name\":\"AP\"}", "application/json").statusCode());                                  // but not change the process
            assertEquals(403, as(fiona, "POST", "/api/docflow/df/flows", "{\"name\":\"New\",\"folder\":\"Finance/Accounts payable\",\"docTypes\":[\"invoice\"]}", "application/json").statusCode());
            // review tasks of the AP process show only to reviewers who may see its folder
            String now = java.time.Instant.now().toString().replace("Z", "+00:00");
            reviewQueue = "[{\"id\":\"docAP\",\"file_name\":\"AP-1.pdf\",\"created_at\":\"" + now + "\",\"flowId\":\"ap\",\"flowName\":\"AP\",\"doctypeName\":\"Invoice\",\"slaHours\":8,\"flags\":[]}]";
            proxy(cr).syncReviews();
            ControlRoom.User f = cr.session(fiona), r = cr.session(rob);
            assertTrue(cr.tasks.tasks(f, "open", false).stream().anyMatch(t -> t.get("title").toString().contains("AP-1.pdf")));
            assertTrue(cr.tasks.tasks(r, "open", false).stream().noneMatch(t -> t.get("title").toString().contains("AP-1.pdf")));
        } finally {
            reviewQueue = "[]";
            proxy(cr).syncReviews();
            flowsJson = "[]";
            cr.folders.setPermissions(admin, "Finance/Accounts payable", List.of(), null);
        }
    }

    @Test void testSetsAreForwardedAndTakeOnlyDocumentsThePersonMaySee() throws Exception {
        ControlRoom.User admin = cr.session(token);
        String tess = sessionFor("tess", "bot_creator"), tom = sessionFor("tom", "bot_creator");
        cr.folders.setPermissions(admin, "Finance/Payables", List.of(Map.of("principal", "user:tess", "access", "view")), null);
        flowsJson = "[{\"id\":\"pay\",\"name\":\"Payables\",\"folder\":\"Finance / Payables\",\"status\":\"live\"}]";
        canned.put("GET df/documents/docPay", new Object[] {200, "{\"id\":\"docPay\",\"flowId\":\"pay\",\"status\":\"validated\"}"});
        try {
            seen.clear();
            assertEquals(200, as(tom, "GET", "/api/docflow/df/testsets?docType=invoice", null, "application/json").statusCode());
            assertEquals("/api/docs/v1/df/testsets?docType=invoice", got("GET", "/api/docs/v1/df/testsets").get("uri"));
            assertEquals(200, as(tom, "POST", "/api/docflow/df/testsets/seed?count=3&bilingual=true", null, "application/json").statusCode());
            assertEquals(200, as(tom, "POST", "/api/docflow/df/testsets/inv/runs?wait=false", "{\"combos\":[{\"ocr\":\"auto\",\"extraction\":\"builtin-rules\"}]}", "application/json").statusCode());
            String add = "{\"documentId\":\"docPay\"}";
            assertEquals(403, as(tom, "POST", "/api/docflow/df/testsets/inv/documents", add, "application/json").statusCode());   // not his folder
            assertEquals(200, as(tess, "POST", "/api/docflow/df/testsets/inv/documents", add, "application/json").statusCode());
            assertEquals(404, as(tess, "POST", "/api/docflow/df/testsets/inv/documents", "{\"documentId\":\"../x\"}", "application/json").statusCode());
        } finally {
            canned.remove("GET df/documents/docPay");
            flowsJson = "[]";
            cr.folders.setPermissions(admin, "Finance/Payables", List.of(), null);
        }
    }

    /** The request DocFlow got for this method and path start (folder look-ups come first). */
    Map<String, String> got(String method, String uriStart) {
        return seen.stream().filter(m -> m.get("method").equals(method) && m.get("uri").startsWith(uriStart)).findFirst().orElseThrow();
    }

    static String ids(String json) {
        return ((List<?>) JsonLite.parse(json)).stream().map(o -> "\"" + ((Map<?, ?>) o).get("id") + "\"").toList().toString();
    }

    // ------------------------------------------------------------------ document events start triggers

    HttpResponse<String> event(String serviceToken, String tenant, String body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(base + "/api/docflow-callback/events")).header("Authorization", "Bearer " + serviceToken).header("X-OOA-Tenant", tenant)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    /** An events answer as "event started skipped". */
    static String counts(String json) {
        Map<?, ?> m = (Map<?, ?>) JsonLite.parse(json);
        return m.get("event") + " " + m.get("started") + " " + m.get("skipped");
    }

    String trigger(String kind, Map<String, Object> config) {
        Map<String, Object> b = new LinkedHashMap<>(Map.of("kind", kind, "name", "On " + kind, "bot_path", "Finance/Post invoice.flow.json", "device_id", deviceId));
        b.putAll(config);
        return (String) cr.createTrigger(cr.session(token), b, null).get("id");
    }

    @Test void documentEventsStartTheMatchingTriggersAgents() throws Exception {
        flowsJson = "[{\"id\":\"ap-invoices\",\"name\":\"AP invoices\",\"folder\":\"\",\"status\":\"live\"}]";
        String any = trigger("doc_processed", Map.of()), byName = trigger("doc_processed", Map.of("process", "ap INVOICES", "doc_type", "invoice"));
        String other = trigger("doc_processed", Map.of("process", "hr-forms")), wrongType = trigger("doc_processed", Map.of("doc_type", "receipt"));
        String review = trigger("doc_needs_review", Map.of());
        String body = "{\"event\":\"processed\",\"document_id\":\"doc5\",\"process_id\":\"ap-invoices\",\"process\":\"AP invoices\",\"status\":\"success\",\"file_name\":\"INV-5.pdf\","
                + "\"doc_type\":\"Invoice\",\"fields\":{\"invoice_number\":\"INV-5\",\"document_id\":\"not this\"},\"at\":\"2026-11-05T10:00:00Z\"}";
        try {
            assertEquals(401, event("nope", "default", body).statusCode());
            assertEquals(403, event("svc-123", "other", body).statusCode());
            HttpResponse<String> r = event("svc-123", "def%61ult", body);                      // the tenant comes URL-encoded
            assertEquals(200, r.statusCode(), r.body());
            assertEquals("processed 2 0", counts(r.body()));
            for (String t : List.of(any, byName)) {
                Map<String, Object> run = cr.db.one("SELECT inputs, source FROM runs WHERE source = ? ORDER BY created DESC", "trigger:" + t);
                assertNotNull(run, "trigger " + t + " started its agent");
                // only the inputs the agent declares; a field never hides the document's own values
                assertEquals(Map.of("document_id", "doc5", "invoice_number", "INV-5"), JsonLite.parse((String) run.get("inputs")));
            }
            for (String t : List.of(other, wrongType, review)) assertNull(cr.db.one("SELECT id FROM runs WHERE source = ?", "trigger:" + t));
            assertEquals(1, ((Number) cr.db.one("SELECT fire_count FROM triggers WHERE id = ?", any).get("fire_count")).intValue());
            assertEquals(400, event("svc-123", "default", "{\"event\":\"exploded\"}").statusCode());
            assertEquals("CR.BAD_TRIGGER", assertThrows(OoaException.class, () -> cr.triggerPollers.pollNow(any)).code(), "nothing to check now");
        } finally {
            for (String t : List.of(any, byName, other, wrongType, review)) cr.deleteTrigger(cr.session(token), t, null);
            flowsJson = "[]";
        }
    }

    @Test void aTriggerWhoseOwnerMayNotSeeTheProcessFolderIsSkipped() throws Exception {
        ControlRoom.User admin = cr.session(token);
        cr.createUser(admin, "otto", "Otto Owner", "bot_creator", null);
        cr.folders.setPermissions(admin, "Payroll", List.of(Map.of("principal", "user:admin", "access", "manage")), null);
        flowsJson = "[{\"id\":\"pay\",\"name\":\"Payslips\",\"folder\":\"Payroll\",\"status\":\"live\"}]";
        String t = trigger("doc_approved", Map.of("process", "pay"));
        cr.updateTrigger(admin, t, Map.of("owner", "otto"), null);
        try {
            HttpResponse<String> r = event("svc-123", "default", "{\"event\":\"approved\",\"document_id\":\"p1\",\"process_id\":\"pay\",\"process\":\"Payslips\",\"status\":\"validated\",\"fields\":{}}");
            assertEquals("approved 0 1", counts(r.body()));
            assertNull(cr.db.one("SELECT id FROM runs WHERE source = ?", "trigger:" + t));
            assertTrue(String.valueOf(cr.db.one("SELECT last_error FROM triggers WHERE id = ?", t).get("last_error")).contains("may not see the process's folder 'Payroll'"));
            assertNotNull(cr.db.one("SELECT seq FROM audit WHERE action = 'trigger.fire' AND object_id = ? AND result = 'denied'", t));
        } finally {
            cr.deleteTrigger(admin, t, null);
            cr.folders.setPermissions(admin, "Payroll", List.of(), null);
            flowsJson = "[]";
        }
    }

    @Test void documentTriggerSettings() {
        assertEquals(Map.of("process", "AP invoices", "doc_type", "Invoice"), TriggerKinds.validate("doc_rejected", Map.of("process", " AP invoices ", "doc_type", "Invoice", "fire_existing", true)));
        assertEquals(Map.of(), TriggerKinds.validate("doc_review_overdue", Map.of()));
        assertThrows(OoaException.class, () -> TriggerKinds.validate("doc_processed", Map.of("process", "x".repeat(201))));
        Map<String, Object> e = Map.of("process_id", "ap", "process", "AP invoices", "doc_type", "Invoice");
        assertTrue(DocFlowProxy.documentMatches(Map.of(), e));
        assertTrue(DocFlowProxy.documentMatches(Map.of("process", "ap"), e));
        assertTrue(DocFlowProxy.documentMatches(Map.of("process", "ap invoices", "doc_type", "INVOICE"), e));
        assertFalse(DocFlowProxy.documentMatches(Map.of("process", "AP"), e));
        assertFalse(DocFlowProxy.documentMatches(Map.of("doc_type", "Invoice"), Map.of("process_id", "ap")));   // no type known: not a match
        assertEquals("Document needs review", TriggerKinds.KINDS.get(DocFlowProxy.DOCUMENT_EVENTS.get("needs_review")).label());
        assertEquals(6, DocFlowProxy.DOCUMENT_EVENTS.size());
    }

    @Test void startBotAndEventsShareTheDocumentInputs() {
        Map<String, Object> named = new LinkedHashMap<>();
        named.put("status", "success");
        named.put("doc_type", null);
        assertEquals(Map.of("total", 5, "status", "success"), DocFlowProxy.documentValues(named, Map.of("total", 5, "status", "forged", "empty", "")).entrySet().stream()
                .filter(x -> !x.getKey().equals("empty")).collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
    }

    // ------------------------------------------------------------------ AI assistants (MCP)

    @SuppressWarnings("unchecked")
    Map<String, Object> mcp(McpServer s, ControlRoom.User u, String method, Map<String, Object> params) {
        Map<String, Object> r = s.handle(u, Map.of("jsonrpc", "2.0", "id", 1, "method", method, "params", params), "10.0.0.1");
        assertNull(r.get("error"), String.valueOf(r));
        return (Map<String, Object>) r.get("result");
    }

    @SuppressWarnings("unchecked")
    List<String> toolNames(McpServer s, ControlRoom.User u) {
        return ((List<Map<String, Object>>) mcp(s, u, "tools/list", Map.of()).get("tools")).stream().map(t -> (String) t.get("name")).filter(n -> n.startsWith("documents_")).toList();
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> tool(McpServer s, ControlRoom.User u, String name, Map<String, Object> args) {
        return mcp(s, u, "tools/call", Map.of("name", name, "arguments", args));
    }

    @Test @SuppressWarnings("unchecked")
    void assistantsSendADocumentAndAskForItsResultWithoutWaiting() {
        ControlRoom.User admin = cr.session(token);
        McpServer s = cr.modules.service(McpServer.class);
        cr.createUser(admin, "val", "Val Viewer", "viewer", null);
        ControlRoom.User val = new ControlRoom.User("val", "val", "viewer", "Val Viewer", false);
        assertEquals(List.of("documents_list_processes", "documents_send", "documents_status", "documents_result", "documents_my_reviews", "documents_review"), toolNames(s, admin));
        assertEquals(List.of("documents_list_processes", "documents_status", "documents_result"), toolNames(s, val));   // docs.view only

        flowsJson = "[{\"id\":\"ap-invoices\",\"name\":\"AP invoices\",\"folder\":\"\",\"status\":\"live\",\"docTypeNames\":[\"Invoice\"]}]";
        try {
            Map<String, Object> list = tool(s, admin, "documents_list_processes", Map.of());
            assertEquals(false, list.get("isError"));
            assertEquals("AP invoices", ((Map<String, Object>) ((List<Object>) ((Map<String, Object>) list.get("structuredContent")).get("items")).get(0)).get("name"));

            seen.clear();
            Map<String, Object> sent = tool(s, admin, "documents_send", Map.of("process", "ap invoices", "file_name", "INV-7.pdf",
                    "content_base64", Base64.getEncoder().encodeToString("%PDF-1.4".getBytes(StandardCharsets.UTF_8))));
            assertEquals(false, sent.get("isError"), String.valueOf(sent));
            assertEquals("d7", ((Map<String, Object>) sent.get("structuredContent")).get("document_id"));
            Map<String, String> up = got("POST", "/api/docs/v1/df/flows/ap-invoices/documents");
            assertEquals("/api/docs/v1/df/flows/ap-invoices/documents?source=assistant&requireLive=true&wait=false", up.get("uri"));   // never waits
            assertTrue(up.get("body").contains("filename=\"INV-7.pdf\"") && up.get("body").contains("%PDF-1.4"));

            Map<String, Object> st = (Map<String, Object>) tool(s, admin, "documents_status", Map.of("document_id", "d7")).get("structuredContent");
            assertEquals(List.of("validation", true, true), List.of(st.get("status"), st.get("done"), st.get("needs_review")));
            Map<String, Object> res = (Map<String, Object>) tool(s, val, "documents_result", Map.of("document_id", "d7")).get("structuredContent");
            assertEquals("{\"total\":{\"value\":115,\"confidence\":0.62}}", JsonLite.write(res.get("fields")));

            // a viewer may not send; a file Document Automation cannot read is refused before DocFlow
            assertEquals(true, tool(s, val, "documents_send", Map.of("process", "ap-invoices", "file_name", "a.pdf", "content_base64", "JVBERg==")).get("isError"));
            assertEquals(true, tool(s, admin, "documents_send", Map.of("process", "ap-invoices", "file_name", "a.docx", "content_base64", "JVBERg==")).get("isError"));
            assertNotNull(cr.db.one("SELECT seq FROM audit WHERE action = 'mcp.tool' AND object_id = 'documents_send' AND actor = 'val' AND result = 'denied'"));
            assertNotNull(cr.db.one("SELECT seq FROM audit WHERE action = 'mcp.tool' AND object_id = 'documents_send' AND actor = 'admin' AND result = 'ok'"));

            // off in Settings: no tools
            cr.platform.updateSettings(admin, Map.of("mcp_documents", 0), null);
            assertEquals(List.of(), toolNames(s, admin));
            assertEquals(true, tool(s, admin, "documents_list_processes", Map.of()).get("isError"));
        } finally {
            cr.platform.updateSettings(admin, Map.of("mcp_documents", 1), null);
            flowsJson = "[]";
        }
    }

    @Test @SuppressWarnings("unchecked")
    void assistantsReviewOnlyWhatThePersonMayReview() throws Exception {
        ControlRoom.User admin = cr.session(token);
        McpServer s = cr.modules.service(McpServer.class);
        String now = java.time.Instant.now().toString().replace("Z", "+00:00");
        flowsJson = "[{\"id\":\"ap\",\"name\":\"AP\",\"folder\":\"Finance / Accounts payable\",\"status\":\"live\"},{\"id\":\"open\",\"name\":\"Open\",\"folder\":\"\",\"status\":\"live\"}]";
        reviewQueue = "[{\"id\":\"rAP\",\"file_name\":\"AP-1.pdf\",\"created_at\":\"" + now + "\",\"flowId\":\"ap\",\"flowName\":\"AP\",\"doctypeName\":\"Invoice\",\"slaHours\":8,\"flags\":[{\"message\":\"Totals differ\"}]},"
                + "{\"id\":\"rOpen\",\"file_name\":\"O-1.pdf\",\"created_at\":\"" + now + "\",\"flowId\":\"open\",\"flowName\":\"Open\",\"doctypeName\":\"Receipt\",\"slaHours\":8,\"flags\":[]}]";
        canned.put("GET df/documents/rAP", new Object[] {200, "{\"id\":\"rAP\",\"flowId\":\"ap\",\"status\":\"validation\"}"});
        cr.folders.setPermissions(admin, "Finance/Accounts payable", List.of(Map.of("principal", "user:admin", "access", "manage")), null);
        ControlRoom.User rae = cr.session(sessionFor("rae", "bot_runner"));
        try {
            List<Object> mine = (List<Object>) ((Map<String, Object>) tool(s, rae, "documents_my_reviews", Map.of()).get("structuredContent")).get("items");
            assertEquals(1, mine.size(), "only the review of the process whose folder rae may see");
            assertEquals("rOpen", ((Map<String, Object>) mine.get(0)).get("document_id"));
            assertEquals(true, tool(s, rae, "documents_review", Map.of("document_id", "rAP", "decision", "approve")).get("isError"));

            seen.clear();
            Map<String, Object> r = tool(s, admin, "documents_review", Map.of("document_id", "rAP", "decision", "reject", "fields", Map.of("total", 99)));
            assertEquals(false, r.get("isError"), String.valueOf(r));
            assertEquals("{\"action\":\"invalid\",\"fields\":{\"total\":99}}", got("POST", "/api/docs/v1/df/documents/rAP/review").get("body"));
            assertEquals(true, tool(s, admin, "documents_review", Map.of("document_id", "rAP", "decision", "maybe")).get("isError"));
        } finally {
            canned.clear();
            reviewQueue = "[]";
            flowsJson = "[]";
            cr.folders.setPermissions(admin, "Finance/Accounts payable", List.of(), null);
        }
    }

    // ------------------------------------------------------------------ templates with processes

    @Test @SuppressWarnings("unchecked")
    void aProcessTravelsWithoutWhatBelongsToItsCompany() {
        Map<String, Object> flow = (Map<String, Object>) JsonLite.parse("{\"id\":\"ap\",\"name\":\"AP\",\"folder\":\"Finance\",\"status\":\"live\",\"version\":4,\"docTypes\":[\"invoice\"],"
                + "\"review\":{\"slaHours\":8,\"slaWebhook\":\"https://hooks.example/x\"},\"intake\":{\"inputFolder\":\"C:\\\\in\",\"email\":{\"enabled\":true,\"password_ref\":\"m/b/p\"}},"
                + "\"deliveries\":[{\"type\":\"start_bot\",\"params\":{\"bot\":\"Finance/Post invoice.flow.json\",\"platform\":true,\"requestedBy\":\"admin\"}},"
                + "{\"type\":\"start_bot\",\"params\":{\"bot\":\"Elsewhere/Other.flow.json\",\"platform\":true}},"
                + "{\"type\":\"webhook\",\"enabled\":true,\"params\":{\"url\":\"https://erp.example/hook\",\"authHeader\":\"Bearer s3cret\"}}]}");
        Map<String, Object> p = DocTemplates.portable(flow, Set.of("Finance/Post invoice.flow.json"));
        String text = JsonLite.write(p);
        assertFalse(text.contains("s3cret") || text.contains("m/b/p") || text.contains("requestedBy") || text.contains("hooks.example") || text.contains("C:\\\\in"), text);
        assertEquals("draft", p.get("status"));
        assertNull(p.get("version"));
        List<Map<String, Object>> d = (List<Map<String, Object>>) p.get("deliveries");
        assertEquals(Map.of("bot", "Finance/Post invoice.flow.json", "in_template", true), d.get(0).get("params"));
        assertEquals(false, d.get(1).get("enabled"));       // an agent the template does not carry
        assertEquals(false, d.get(2).get("enabled"));       // other deliveries: checked by the company installing
        assertEquals("https://erp.example/hook", ((Map<String, Object>) d.get(2).get("params")).get("url"));
        assertEquals(Map.of("id", "po", "name", "PO", "fields", List.of()), DocTemplates.portableType(Map.of("id", "po", "name", "PO", "fields", List.of(), "learning", Map.of("aliases", Map.of()), "version", 3)));
    }

    @Test @SuppressWarnings("unchecked")
    void aTemplateCarriesProcessesFromSharingToInstalling() throws Exception {
        ControlRoom.User admin = cr.session(token);
        Map<String, String> keys = License.newKeyPair();
        Templates t = new Templates(cr, () -> License.publicKey(keys.get("public")));
        canned.put("GET df/flows/ap", new Object[] {200, "{\"id\":\"ap\",\"name\":\"AP\",\"folder\":\"Finance / Payables\",\"status\":\"live\",\"docTypes\":[\"custom-po\"],"
                + "\"rules\":[{\"type\":\"lookup\",\"name\":\"Known supplier\",\"params\":{\"table\":\"suppliers\"}}],"
                + "\"deliveries\":[{\"type\":\"start_bot\",\"params\":{\"bot\":\"Finance/Doc bot.flow.json\",\"platform\":true,\"requestedBy\":\"admin\"}}]}"});
        canned.put("GET df/doctypes/custom-po", new Object[] {200, "{\"id\":\"custom-po\",\"name\":\"Custom PO\",\"fields\":[],\"learning\":{\"aliases\":{\"total\":[\"Sum\"]}}}"});
        try {
            Map<String, Object> bundle = new LinkedHashMap<>(Templates.bundleOf(Map.of("Finance/Doc bot.flow.json", JsonLite.write(agent("doc_bot"))), "test"));
            new DocTemplates(cr.modules.contextFor("document-automation"), proxy(cr)).addToBundle(admin, bundle, List.of("ap"));
            assertEquals(List.of("AP"), Templates.processNames(bundle));
            assertFalse(JsonLite.write(bundle.get("document_types")).contains("Sum"), "learned labels stay with their company");
            String feed = Templates.makeFeed("Test", List.of(new LinkedHashMap<>(Map.of("id", "ap-kit", "name", "AP kit", "category", "Finance", "bundle", bundle))),
                    License.privateKey(keys.get("private")));
            t.upload(admin, feed, null);
            assertEquals(List.of("AP"), ((List<Map<String, Object>>) t.list(admin).get("templates")).get(0).get("processes"));

            // the installing company has no such document type nor reference data yet
            canned.clear();
            canned.put("GET df/doctypes/custom-po", new Object[] {404, "{\"detail\":\"unknown document type\"}"});
            canned.put("GET df/reftables/suppliers", new Object[] {404, "{\"detail\":\"not found\"}"});
            flowsJson = "[]";
            seen.clear();
            Map<String, Object> r = t.install(admin, "vendor", "ap-kit", "Kits", null);
            assertEquals(true, r.get("ok"), String.valueOf(r));
            assertEquals(true, r.get("processes_ok"), String.valueOf(r));
            Map<String, Object> made = ((List<Map<String, Object>>) r.get("processes")).get(0);
            assertEquals(List.of("AP", "Kits / Finance / Payables", "created"), List.of(made.get("name"), made.get("folder"), made.get("status")));
            assertTrue(String.valueOf(made.get("note")).contains("Known supplier"));
            assertTrue(got("POST", "/api/docs/v1/df/doctypes").get("body").contains("Custom PO"));
            Map<String, Object> sent = (Map<String, Object>) JsonLite.parse(got("POST", "/api/docs/v1/df/flows").get("body"));
            assertEquals("draft", sent.get("status"));
            Map<String, Object> bot = (Map<String, Object>) ((Map<String, Object>) ((List<Object>) sent.get("deliveries")).get(0)).get("params");
            assertEquals(Map.of("bot", "Kits/Finance/Doc bot.flow.json", "platform", true, "requestedBy", "admin"), bot);   // the agent as installed, run as the installer
            assertEquals(false, ((Map<String, Object>) ((List<Object>) sent.get("rules")).get(0)).get("enabled"));
            assertNotNull(cr.db.one("SELECT path FROM bots WHERE path = 'Kits/Finance/Doc bot.flow.json'"));

            // installing again: the process is there (same name and folder), so it is kept
            flowsJson = "[{\"id\":\"ap\",\"name\":\"AP\",\"folder\":\"Kits / Finance / Payables\",\"status\":\"draft\"}]";
            assertEquals("skipped", ((List<Map<String, Object>>) t.install(admin, "vendor", "ap-kit", "Kits", null).get("processes")).get(0).get("status"));
        } finally {
            canned.clear();
            flowsJson = "[]";
        }
    }

    @Test void processFoldersMatchPlatformFolders() {
        assertEquals("Finance/Accounts payable", DocFlowProxy.folderKey(" Finance / Accounts payable "));
        assertEquals("", DocFlowProxy.folderKey(null));
        assertEquals("Shared", DocFlowProxy.folderKey("/Shared/"));
    }
}
