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

    // ---------- the "UT Document Automation" agent's cases, through a running agent's routes (T1-T3, 25 Nov 2026) ----------

    String runId, deviceSecret, utFlow, utDoc, utChecked;

    HttpResponse<String> agent(String path, String contentType, HttpRequest.BodyPublisher body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(base + "/api/agent/docflow/" + path)).timeout(Duration.ofMinutes(3))
                .header("X-OOA-Device", deviceId).header("X-OOA-Device-Secret", deviceSecret).header("Content-Type", contentType)
                .POST(body).build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> results(String json) throws Exception {
        return agent("results", "application/json", HttpRequest.BodyPublishers.ofString(json.replace("RUN", runId)));
    }

    /** A made-up invoice as a PDF with a text layer (DocFlow reads it without OCR). */
    static byte[] invoicePdf(String number, String total) {
        String text = "BT /F1 12 Tf 60 760 Td (ACME Trading Co.) Tj 0 -22 Td (Invoice) Tj 0 -22 Td (Invoice Number: " + number
                + ") Tj 0 -22 Td (Invoice Date: 2026-11-03) Tj 0 -22 Td (Subtotal: " + total + ") Tj 0 -22 Td (Total: " + total + " EUR) Tj ET";
        String[] objs = {"<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
                "<< /Length " + text.length() + " >>\nstream\n" + text + "\nendstream", "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"};
        StringBuilder pdf = new StringBuilder("%PDF-1.4\n");
        List<Integer> at = new ArrayList<>();
        for (int i = 0; i < objs.length; i++) { at.add(pdf.length()); pdf.append(i + 1).append(" 0 obj\n").append(objs[i]).append("\nendobj\n"); }
        int xref = pdf.length();
        pdf.append("xref\n0 ").append(objs.length + 1).append("\n0000000000 65535 f \n");
        for (int a : at) pdf.append(String.format("%010d 00000 n \n", a));
        pdf.append("trailer\n<< /Size ").append(objs.length + 1).append(" /Root 1 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n");
        return pdf.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    HttpResponse<String> sendDocument(String process, String name, byte[] file, boolean waitForIt) throws Exception {
        String boundary = "ut" + UUID.randomUUID().toString().replace("-", "");
        byte[] head = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"files\"; filename=\"" + name + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[head.length + file.length + tail.length];
        System.arraycopy(head, 0, body, 0, head.length);
        System.arraycopy(file, 0, body, head.length, file.length);
        System.arraycopy(tail, 0, body, head.length + file.length, tail.length);
        return agent("documents?run_id=" + runId + "&process=" + java.net.URLEncoder.encode(process, StandardCharsets.UTF_8) + "&wait=" + waitForIt,
                "multipart/form-data; boundary=" + boundary, HttpRequest.BodyPublishers.ofByteArray(body));
    }

    @Test @Order(8) void utSetUp() throws Exception {
        // the test process ut_invoices: the built-in invoice type, live, read on this server; a running agent to send from
        HttpResponse<String> r = call("POST", "/api/docflow/df/flows", "{\"name\":\"ut_invoices\",\"docTypes\":[\"invoice\"],\"intake\":{\"einvoiceFirst\":false}}");
        assertEquals(200, r.statusCode(), r.body());
        utFlow = String.valueOf(((Map<?, ?>) json(r)).get("id"));
        assertEquals(200, call("POST", "/api/docflow/df/flows/" + utFlow + "/live?live=true", null).statusCode());
        ControlRoom.User admin = cr.session(token);
        Map<String, Object> dev = cr.registerDevice((String) cr.createRegistrationKey(admin, 1, null).get("key"), "UT-PC", "ut-pc", "Windows 11", "0.2.0", null);
        deviceId = (String) dev.get("device_id");
        deviceSecret = (String) dev.get("secret");
        runId = String.valueOf(cr.startRun("admin", "admin", "Finance/Post invoice.flow.json", deviceId, Map.of(), "test", null).get("run_id"));
        cr.db.update("UPDATE runs SET status = 'running' WHERE id = ?", runId);
    }

    @Test @Order(9) @SuppressWarnings("unchecked")
    void utSendAnInvoiceAndWaitUntilItIsRead() throws Exception {
        HttpResponse<String> r = sendDocument("UT_INVOICES", "ut-invoice.pdf", invoicePdf("INV-UT-1", "120.50"), true);
        assertEquals(200, r.statusCode(), r.body());
        Map<String, Object> out = json(r);
        utDoc = String.valueOf(out.get("document_id"));
        assertTrue(List.of("success", "validation").contains(out.get("status")), r.body());
        assertEquals("INV-UT-1", ((Map<String, Object>) out.get("fields")).get("invoice_number"));
    }

    @Test @Order(10) void utOnlyReadableFilesAndKnownProcesses() throws Exception {
        assertEquals(404, sendDocument("No such process", "a.pdf", invoicePdf("X", "1.00"), false).statusCode());
        HttpResponse<String> notes = sendDocument("ut_invoices", "notes.docx", "not a document".getBytes(StandardCharsets.UTF_8), false);
        assertTrue(notes.statusCode() >= 400 && notes.body().contains("DOCS."), notes.statusCode() + " " + notes.body());
        int unknown = results("{\"run_id\":\"RUN\",\"op\":\"get\",\"document_id\":\"nosuchdocument\"}").statusCode();
        assertTrue(unknown == 404 || unknown == 400, "an unknown document id is refused: " + unknown);
        assertEquals(400, results("{\"run_id\":\"RUN\",\"op\":\"get\",\"document_id\":\"../admin\"}").statusCode());
    }

    @Test @Order(11) @SuppressWarnings("unchecked")
    void utGetResultAndCorrectIt() throws Exception {
        HttpResponse<String> got = results("{\"run_id\":\"RUN\",\"op\":\"get\",\"document_id\":\"" + utDoc + "\"}");
        assertEquals(200, got.statusCode(), got.body());
        Map<String, Object> g = json(got);
        assertEquals(Boolean.TRUE, g.get("done"));
        assertEquals("INV-UT-1", ((Map<String, Object>) g.get("fields")).get("invoice_number"));
        HttpResponse<String> up = results("{\"run_id\":\"RUN\",\"op\":\"update\",\"document_id\":\"" + utDoc + "\",\"fields\":{\"invoice_number\":\"INV-UT-0001\",\"total\":120.5}}");
        assertEquals(200, up.statusCode(), up.body());
        Map<String, Object> after = json(results("{\"run_id\":\"RUN\",\"op\":\"get\",\"document_id\":\"" + utDoc + "\"}"));
        assertEquals("validated", after.get("status"), "corrected values count as an approval");
        assertEquals("INV-UT-0001", ((Map<String, Object>) after.get("fields")).get("invoice_number"));
    }

    @Test @Order(12) @SuppressWarnings("unchecked")
    void utListFinishedAndMarkCollectedOnce() throws Exception {
        java.util.function.Supplier<List<Object>> ids = () -> {
            try {
                return ((List<Object>) ((Map<String, Object>) json(results("{\"run_id\":\"RUN\",\"op\":\"list\",\"process\":\"ut_invoices\",\"collector\":\"ut_collector\"}"))).get("documents"))
                        .stream().map(o -> (Object) ((Map<?, ?>) o).get("document_id")).toList();
            } catch (Exception e) { throw new RuntimeException(e); }
        };
        assertTrue(ids.get().contains(utDoc));
        Map<String, Object> mark = json(results("{\"run_id\":\"RUN\",\"op\":\"collect\",\"document_ids\":[\"" + utDoc + "\"],\"collector\":\"ut_collector\"}"));
        assertEquals("1", String.valueOf(mark.get("marked")));
        assertEquals("1", String.valueOf(((Map<?, ?>) json(results("{\"run_id\":\"RUN\",\"op\":\"collect\",\"document_ids\":[\"" + utDoc + "\"],\"collector\":\"ut_collector\"}"))).get("already_collected")));
        assertFalse(ids.get().contains(utDoc), "a collected document is not listed again for that collector");
    }

    // ---------- the global and flexible features, through the Command Center's proxy (25 Nov 2026) ----------

    @Test @Order(13) @SuppressWarnings("unchecked")
    void utTheNewDocFlowRoutesAnswerThroughTheProxy() throws Exception {
        for (String route : List.of("df/einvoice-standards", "df/tax-presets", "df/flows/" + utFlow + "/accuracy", "df/flows/" + utFlow + "/spend"))
            assertEquals(200, call("GET", "/api/docflow/" + route, null).statusCode(), route);
        Map<String, Object> acc = json(call("GET", "/api/docflow/df/flows/" + utFlow + "/accuracy", null));
        assertTrue(((Number) acc.get("reviewed")).intValue() >= 1, "the corrected document is a reviewed one");
        List<Map<String, Object>> fields = (List<Map<String, Object>>) acc.get("fields");
        assertTrue(((Number) fields.stream().filter(f -> "invoice_number".equals(f.get("name"))).findFirst().orElseThrow().get("corrected")).intValue() >= 1);
        List<?> types = json(call("GET", "/api/docflow/df/doctypes", null));
        assertTrue(types.stream().anyMatch(t -> "other".equals(((Map<?, ?>) t).get("id"))), "the built-in Other document type");
        Map<String, Object> invoice = (Map<String, Object>) types.stream().filter(t -> "invoice".equals(((Map<?, ?>) t).get("id"))).findFirst().orElseThrow();
        assertTrue(((List<Map<String, Object>>) invoice.get("fields")).stream().noneMatch(f -> f.get("regex") != null && String.valueOf(f.get("regex")).startsWith("3")),
                "no Saudi VAT pattern on the built-in invoice");
    }

    @Test @Order(14) @SuppressWarnings("unchecked")
    void utNoTypeFitsKeepsItAsOther() throws Exception {
        HttpResponse<String> r = call("POST", "/api/docflow/df/flows", "{\"name\":\"ut_checked\",\"docTypes\":[\"receipt\"],"
                + "\"classify\":{\"mode\":\"single\",\"checkType\":true,\"whenNoType\":\"other\"},\"intake\":{\"einvoiceFirst\":false}}");
        assertEquals(200, r.statusCode(), r.body());
        String id = utChecked = String.valueOf(((Map<?, ?>) json(r)).get("id"));
        assertEquals(200, call("POST", "/api/docflow/df/flows/" + id + "/live?live=true", null).statusCode());
        Map<String, Object> sent = json(sendDocument("ut_checked", "not-a-receipt.pdf", invoicePdf("INV-9", "9.00"), true));
        assertEquals("success", sent.get("status"), "not a receipt: kept as an Other document and delivered");
        Map<String, Object> doc = json(call("GET", "/api/docflow/df/documents/" + sent.get("document_id"), null));
        assertEquals("other", doc.get("doctype_id"));
    }

    @Test @Order(15) void utTidyUp() throws Exception {
        for (String id : List.of(utFlow, utChecked)) {
            HttpResponse<String> r = call("DELETE", "/api/docflow/df/flows/" + id, null);
            assertTrue(r.statusCode() < 300, id + ": " + r.statusCode() + " " + r.body());
        }
    }
}
