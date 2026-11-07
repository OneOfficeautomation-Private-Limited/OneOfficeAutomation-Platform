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
    String base, token, deviceId, deviceSecret;
    final List<Map<String, String>> seen = Collections.synchronizedList(new ArrayList<>());
    /** What the fake DocFlow's review queue holds. */
    volatile String reviewQueue = "[]";
    /** What the fake DocFlow lists as processes. */
    volatile String flowsJson = "[]";
    /** What the fake DocFlow says its version is. */
    static final String PINNED = DocFlowProxy.COMPAT.getProperty("pinned");
    volatile String versionJson = "{\"version\":\"" + PINNED + "\"}";

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
            byte[] b = (path.endsWith("/documents") && ex.getRequestMethod().equals("POST") ? "{\"documents\":[{\"documentId\":\"d7\",\"status\":\"processing\"}]}"
                    : path.endsWith("/df/documents/d7") ? "{\"id\":\"d7\",\"status\":\"validation\",\"fields\":{\"total\":115}}"
                    : path.endsWith("/admin/version") ? versionJson
                    : path.endsWith("/df/review") ? reviewQueue : path.endsWith("/df/flows") && ex.getRequestMethod().equals("GET") && ex.getRequestURI().getQuery() == null ? flowsJson : path.contains("/df/flows") && in.length > 0 ? new String(in, StandardCharsets.UTF_8)
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
            Map<String, Object> other = new DocFlowProxy(cr).version();
            assertEquals(List.of("other_contract", false), List.of(other.get("status"), other.get("compatible")));
            assertTrue(String.valueOf(other.get("message")).contains("Install DocFlow " + PINNED));
            versionJson = "{\"version\":\"3.0.0\"}";
            assertEquals("too_old", new DocFlowProxy(cr).version().get("status"));
            versionJson = "{}";
            assertEquals("unknown", new DocFlowProxy(cr).version().get("status"));
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
            DocFlowProxy p = new DocFlowProxy(cr);
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
            DocFlowProxy p = new DocFlowProxy(cr);
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
        DocFlowProxy p = new DocFlowProxy(cr);
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
        assertTrue(seen.get(0).get("body").contains("\"requestedBy\":\"admin\""));
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

    @Test void inboxProblemsAreSaidPlainly() {
        Map<String, Object> email = Map.of("host", "imap.example.com", "port", 993);
        assertEquals("The mail server imap.example.com is not known: check its name", DocFlowProxy.problem(new java.net.UnknownHostException("imap.example.com"), email));
        assertEquals("The mail server imap.example.com does not answer on port 993", DocFlowProxy.problem(new java.net.ConnectException("refused"), email));
        assertEquals("Sign-in to the mailbox failed: NO", DocFlowProxy.problem(new java.io.IOException("Sign-in to the mailbox failed: NO"), email));
    }
}
