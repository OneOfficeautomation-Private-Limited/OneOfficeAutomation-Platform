package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.modules.docauto.DocFlowConfig;
import com.oneofficeautomation.modules.docauto.DocFlowProxy;
import com.oneofficeautomation.modules.docauto.DocTemplates;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.JsonLite;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.InputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Document Automation against a real DocFlow (oneoffice-docauto at the ref in docflow-compat.properties), as CI's
 * "Document Automation with the real DocFlow" job starts it. Skipped unless -Pooa.test.docflow gives DocFlow's address.
 * The other properties: .token (DocFlow's DOCAUTO_API_TOKEN), .port (this Command Center's port, which DocFlow's
 * DOCAUTO_PLATFORM_URL points at for its callbacks) and .samples (DocFlow's samples folder).
 * <p>
 * DocFlowProxyTest covers the same paths against a stand-in; this one proves the two really fit: the proxy's routes,
 * an upload read by DocFlow, the review queue as Task inbox tasks, and a "Start a bot" delivery calling back here.
 */
@EnabledIfSystemProperty(named = "ooa.test.docflow", matches = "https?://.+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DocFlowLiveTest {
    /** Document Automation's proxy as the module builds it, with a fresh context of the module running in this One CommandCenter. */
    static DocFlowProxy proxy(ControlRoom cr) { return new DocFlowProxy(cr.modules.contextFor("document-automation")); }

    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    Db db; ControlRoom cr; ControlRoomServer server;
    String docflow, base, token, deviceId, flowId, documentId;

    @BeforeAll void start() throws Exception {
        docflow = System.getProperty("ooa.test.docflow").replaceAll("/+$", "");
        Path data = Files.createTempDirectory("ooa-docflow-live");
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        String pw = cr.bootstrap();
        String t0 = (String) cr.login("admin", pw, null).get("token");
        cr.changePassword(cr.session(t0), pw, "Admin-Passw0rd-2026", t0, null);
        token = (String) cr.login("admin", "Admin-Passw0rd-2026", null).get("token");
        ControlRoom.User admin = cr.session(token);
        cr.createLocker(admin, "docflow", "Document Automation", "user:admin", null);
        cr.setCredential(admin, "docflow", "service", "token", System.getProperty("ooa.test.docflow.token", ""), null);
        cr.platform.updateSettings(admin, Map.of("docflow_url", docflow, "docflow_token_ref", "docflow/service/token"), null);

        @SuppressWarnings("unchecked") Map<String, Object> agent = (Map<String, Object>) JsonLite.parse("{\"kind\":\"TaskBot\",\"id\":\"post_invoice\",\"name\":\"Post invoice\",\"description\":\"Posts to the ERP\","
                + "\"variables\":[{\"name\":\"document_id\",\"type\":\"String\",\"direction\":\"input\"},{\"name\":\"status\",\"type\":\"String\",\"direction\":\"input\"}],"
                + "\"steps\":[{\"id\":\"1\",\"action\":\"core/set\",\"args\":{\"variable\":\"status\",\"value\":\"x\"}}]}");
        cr.checkin(admin, "Finance/Post invoice.flow.json", agent, "first", true, null);
        Map<String, Object> dev = cr.registerDevice((String) cr.createRegistrationKey(admin, 1, null).get("key"), "PC-1", "pc-1", "Windows 11", "0.2.0", null);
        deviceId = (String) dev.get("device_id");
        cr.setDefaultDevice(admin, "admin", deviceId, null);

        server = new ControlRoomServer(cr, "127.0.0.1", Integer.parseInt(System.getProperty("ooa.test.docflow.port", "0")));
        server.start();
        base = "http://127.0.0.1:" + server.port();
    }

    @AfterAll void stop() { if (server != null) server.stop(); if (db != null) db.close(); }

    HttpResponse<String> call(String method, String path, String json) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofMinutes(3)).header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @SuppressWarnings("unchecked")
    static <T> T json(HttpResponse<String> r) { return (T) JsonLite.parse(r.body()); }

    @Test @Order(1) void docFlowIsTheVersionThisReleaseIsTestedWith() throws Exception {
        Properties compat = new Properties();
        try (InputStream in = DocFlowLiveTest.class.getResourceAsStream("/docflow-compat.properties")) { compat.load(in); }
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(docflow + "/api/docs/v1/admin/version")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        assertEquals(compat.getProperty("pinned"), ((Map<?, ?>) json(r)).get("version"));
    }

    @Test @Order(2) void theScreensReadDocFlowThroughTheProxy() throws Exception {
        HttpResponse<String> flows = call("GET", "/api/docflow/df/flows", null);
        assertEquals(200, flows.statusCode(), flows.body());
        assertTrue(((List<?>) json(flows)).stream().anyMatch(f -> "smart-inbox".equals(((Map<?, ?>) f).get("id"))), flows.body());
        for (String route : List.of("df/overview", "df/doctypes", "df/library", "df/review", "df/reftables", "providers", "costs", "usage?groupBy=provider_id", "admin/settings")) {
            HttpResponse<String> r = call("GET", "/api/docflow/" + route, null);
            assertEquals(200, r.statusCode(), route + ": " + r.body());
        }
        HttpResponse<String> providers = call("GET", "/api/docflow/providers", null);
        assertTrue(providers.body().contains("\"tesseract\""), providers.body());
    }

    @Test @Order(3) void aLiveProcessWithAStartABotDeliveryIsSaved() throws Exception {
        String flow = "{\"name\":\"Live AP invoices\",\"docTypes\":[\"invoice\"],\"deliveries\":[{\"type\":\"start_bot\",\"name\":\"ERP\","
                + "\"on\":[\"success\",\"validation\",\"validated\",\"invalid\",\"failed\"],\"params\":{\"bot\":\"Finance/Post invoice\"}}]}";
        HttpResponse<String> r = call("POST", "/api/docflow/df/flows", flow);
        assertEquals(200, r.statusCode(), r.body());
        Map<String, Object> saved = json(r);
        flowId = String.valueOf(saved.get("id"));
        @SuppressWarnings("unchecked") Map<String, Object> params = (Map<String, Object>) ((Map<String, Object>) ((List<Object>) saved.get("deliveries")).get(0)).get("params");
        assertEquals(Boolean.TRUE, params.get("platform"));                   // stamped by the proxy, accepted by DocFlow without a URL
        assertEquals("admin", params.get("requestedBy"));
        HttpResponse<String> live = call("POST", "/api/docflow/df/flows/" + flowId + "/live?live=true", null);
        assertEquals(200, live.statusCode(), live.body());
    }

    @Test @Order(4) void anUploadedInvoiceIsReadAndItsFieldsComeBack() throws Exception {
        Path pdf = Path.of(System.getProperty("ooa.test.docflow.samples", "samples"), "invoice_digital.pdf");
        byte[] file = Files.readAllBytes(pdf);
        String boundary = "ooa" + UUID.randomUUID().toString().replace("-", "");
        byte[] head = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"files\"; filename=\"invoice_digital.pdf\"\r\nContent-Type: application/pdf\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[head.length + file.length + tail.length];
        System.arraycopy(head, 0, body, 0, head.length);
        System.arraycopy(file, 0, body, head.length, file.length);
        System.arraycopy(tail, 0, body, head.length + file.length, tail.length);
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/api/docflow/df/flows/" + flowId + "/documents?wait=true")).timeout(Duration.ofMinutes(3))
                .header("Authorization", "Bearer " + token).header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertTrue(r.statusCode() < 300, r.statusCode() + " " + r.body());
        @SuppressWarnings("unchecked") List<Map<String, Object>> docs = (List<Map<String, Object>>) ((Map<String, Object>) json(r)).get("documents");
        documentId = String.valueOf(docs.get(0).get("documentId"));

        HttpResponse<String> d = call("GET", "/api/docflow/df/documents/" + documentId, null);
        assertEquals(200, d.statusCode(), d.body());
        Map<String, Object> doc = json(d);
        assertTrue(List.of("success", "validation").contains(doc.get("status")), d.body());
        assertEquals(flowId, doc.get("flowId"));
        assertFalse(((Map<?, ?>) doc.get("fields")).isEmpty(), d.body());
        assertEquals(200, call("GET", "/api/docflow/documents/" + documentId + "/result", null).statusCode());
    }

    @Test @Order(5) void theDeliveryStartsTheAgentHereWithTheDocument() throws Exception {
        Map<String, Object> run = null;
        for (Instant until = Instant.now().plusSeconds(90); run == null && Instant.now().isBefore(until); Thread.sleep(1000))
            run = cr.db.one("SELECT bot_path, device_id, requested_by, source, inputs FROM runs WHERE source = ?", "docflow:" + flowId);
        assertNotNull(run, "DocFlow's \"Start a bot\" delivery did not reach this Command Center (check DOCAUTO_PLATFORM_URL and the token)");
        assertEquals("Finance/Post invoice.flow.json", run.get("bot_path"));
        assertEquals(deviceId, run.get("device_id"));
        assertEquals("admin", run.get("requested_by"));
        assertEquals(documentId, ((Map<?, ?>) JsonLite.parse((String) run.get("inputs"))).get("document_id"));
    }

    @Test @Order(6) void theReviewQueueBecomesTaskInboxTasks() throws Exception {
        HttpResponse<String> q = call("GET", "/api/docflow/df/review", null);
        int waiting = ((List<?>) json(q)).size();
        proxy(cr).syncReviews();
        Number open = (Number) cr.db.one("SELECT COUNT(*) AS n FROM human_tasks WHERE kind = ? AND status = 'open'", DocFlowProxy.TASK_KIND).get("n");
        assertEquals(waiting, open.intValue(), q.body());
    }

    @Test @Order(7) void aWrongTokenIsReportedPlainly() throws Exception {
        ControlRoom.User admin = cr.session(token);
        cr.setCredential(admin, "docflow", "service", "token", "not-the-token", null);
        try {
            HttpResponse<String> r = call("GET", "/api/docflow/df/flows", null);
            assertTrue(r.body().contains("DOCS.TOKEN"), r.statusCode() + " " + r.body());
        } finally {
            cr.setCredential(admin, "docflow", "service", "token", System.getProperty("ooa.test.docflow.token", ""), null);
        }
    }
}
