package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.JsonLite;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** G6: a running agent writes corrected values back, downloads results or the original file, and pins a process version. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocFlowG6Test {
    static final HttpClient HTTP = HttpClient.newHttpClient();
    Db db; ControlRoom cr; ControlRoomServer server; HttpServer docflow;
    String base, deviceId, deviceSecret;
    final List<Map<String, String>> seen = Collections.synchronizedList(new ArrayList<>());
    volatile String docStatus = "validation";

    @BeforeAll @SuppressWarnings("unchecked") void start() throws Exception {
        Path data = Files.createTempDirectory("ooa-docflow-g6");
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        String pw = cr.bootstrap();
        String t0 = (String) cr.login("admin", pw, null).get("token");
        cr.changePassword(cr.session(t0), pw, "Admin-Passw0rd-2026", t0, null);
        ControlRoom.User admin = cr.session((String) cr.login("admin", "Admin-Passw0rd-2026", null).get("token"));
        cr.createLocker(admin, "docflow", "Document Automation", "user:admin", null);
        cr.setCredential(admin, "docflow", "service", "token", "svc-123", null);
        docflow = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        docflow.createContext("/api/docs/v1/", ex -> {
            byte[] in = ex.getRequestBody().readAllBytes();
            String path = ex.getRequestURI().getPath().substring("/api/docs/v1/".length()), m = ex.getRequestMethod();
            seen.add(Map.of("call", m + " " + path + (ex.getRequestURI().getQuery() == null ? "" : "?" + ex.getRequestURI().getQuery()), "body", new String(in, StandardCharsets.UTF_8)));
            byte[] b;
            String type = "application/json";
            int status = 200;
            if (m.equals("GET") && path.equals("df/flows")) b = "[{\"id\":\"ap\",\"name\":\"AP\",\"status\":\"live\",\"version\":4}]".getBytes();
            else if (m.equals("POST") && path.equals("df/flows/ap/documents")) b = "{\"documents\":[{\"documentId\":\"d7\",\"status\":\"processing\"}]}".getBytes();
            else if (m.equals("GET") && path.equals("df/documents/d7"))
                b = ("{\"id\":\"d7\",\"flowId\":\"ap\",\"status\":\"" + docStatus + "\",\"file_name\":\"INV-7.pdf\",\"fields\":{\"total\":" + (docStatus.equals("validated") ? "110" : "115") + "}}").getBytes();
            else if (m.equals("POST") && path.equals("df/documents/d7/review")) { docStatus = "validated"; b = "{\"id\":\"d7\"}".getBytes(); }
            else if (m.equals("GET") && path.equals("documents/d7/export")) { b = "field,value\ntotal,110\n".getBytes(); type = "text/csv"; }
            else if (m.equals("GET") && path.equals("documents/d7/file")) { b = "%PDF-1.4 seven".getBytes(); type = "application/pdf"; }
            else { status = 404; b = "{\"detail\":\"not found\"}".getBytes(); }
            ex.getResponseHeaders().set("Content-Type", type);
            ex.sendResponseHeaders(status, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        docflow.start();
        cr.platform.updateSettings(admin, Map.of("docflow_url", "http://127.0.0.1:" + docflow.getAddress().getPort(), "docflow_token_ref", "docflow/service/token"), null);
        cr.checkin(admin, "Finance/Fix invoice.flow.json", (Map<String, Object>) JsonLite.parse("{\"kind\":\"TaskBot\",\"id\":\"fix\",\"name\":\"Fix\",\"description\":\"t\",\"variables\":[],\"steps\":[]}"),
                "first", true, null);
        Map<String, Object> dev = cr.registerDevice((String) cr.createRegistrationKey(admin, 1, null).get("key"), "PC-1", "pc-1", "Windows 11", "0.2.0", null);
        deviceId = (String) dev.get("device_id");
        deviceSecret = (String) dev.get("secret");
        server = new ControlRoomServer(cr, "127.0.0.1", 0);
        server.start();
        base = "http://127.0.0.1:" + server.port();
    }

    @AfterAll void stop() { server.stop(); docflow.stop(0); db.close(); }

    HttpResponse<String> agent(String path, String type, String body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(base + "/api/agent/" + path)).header("X-OOA-Device", deviceId).header("X-OOA-Device-Secret", deviceSecret)
                .header("Content-Type", type).POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    String running() {
        String runId = String.valueOf(cr.startRun("admin", "admin", "Finance/Fix invoice.flow.json", deviceId, Map.of(), "test", null).get("run_id"));
        cr.db.update("UPDATE runs SET status = 'running' WHERE id = ?", runId);
        return runId;
    }

    @Test @SuppressWarnings("unchecked")
    void updateAndDownload() throws Exception {
        String run = running();
        HttpResponse<String> up = agent("docflow/results", "application/json", "{\"run_id\":\"" + run + "\",\"op\":\"update\",\"document_id\":\"d7\",\"fields\":{\"total\":110}}");
        assertEquals(200, up.statusCode(), up.body());
        Map<String, Object> u = (Map<String, Object>) JsonLite.parse(up.body());
        assertEquals("validated", u.get("status"));
        Map<String, String> review = seen.stream().filter(s -> s.get("call").equals("POST df/documents/d7/review")).findFirst().orElseThrow();
        Map<?, ?> rb = (Map<?, ?>) JsonLite.parse(review.get("body"));
        assertEquals("submit", rb.get("action"));
        assertEquals("110", String.valueOf(((Map<?, ?>) rb.get("fields")).get("total")));
        assertEquals(400, agent("docflow/results", "application/json", "{\"run_id\":\"" + run + "\",\"op\":\"update\",\"document_id\":\"d7\",\"fields\":{}}").statusCode());

        HttpResponse<String> csv = agent("docflow/results", "application/json", "{\"run_id\":\"" + run + "\",\"op\":\"download\",\"document_id\":\"d7\",\"kind\":\"csv\",\"delete_after\":true}");
        assertEquals(200, csv.statusCode(), csv.body());
        Map<String, Object> c = (Map<String, Object>) JsonLite.parse(csv.body());
        assertEquals("INV-7.csv", c.get("file_name"));
        assertEquals("field,value\ntotal,110\n", new String(Base64.getDecoder().decode(String.valueOf(c.get("data"))), StandardCharsets.UTF_8));
        assertEquals(false, c.get("deleted"));
        assertNotNull(c.get("delete_note"));
        assertTrue(seen.stream().anyMatch(s -> s.get("call").equals("GET documents/d7/export?format=csv")));
        Map<String, Object> orig = (Map<String, Object>) JsonLite.parse(agent("docflow/results", "application/json",
                "{\"run_id\":\"" + run + "\",\"op\":\"download\",\"document_id\":\"d7\",\"kind\":\"original\"}").body());
        assertEquals("INV-7.pdf", orig.get("file_name"));
        assertEquals("%PDF-1.4 seven", new String(Base64.getDecoder().decode(String.valueOf(orig.get("data"))), StandardCharsets.UTF_8));
        assertEquals(400, agent("docflow/results", "application/json", "{\"run_id\":\"" + run + "\",\"op\":\"download\",\"document_id\":\"d7\",\"kind\":\"xlsx\"}").statusCode());
    }

    @Test void aPinnedVersionMustBeTheLiveOne() throws Exception {
        String run = running();
        String multipart = "%PDF-1.4";
        HttpResponse<String> wrong = agent("docflow/documents?run_id=" + run + "&process=AP&wait=false&process_version=3", "application/pdf", multipart);
        assertTrue(wrong.statusCode() >= 400, wrong.body());
        assertTrue(wrong.body().contains("DOCS.VERSION_CHANGED"), wrong.body());
        HttpResponse<String> ok = agent("docflow/documents?run_id=" + run + "&process=AP&wait=false&process_version=4", "application/pdf", multipart);
        assertEquals(200, ok.statusCode(), ok.body());
        assertEquals(200, agent("docflow/documents?run_id=" + run + "&process=AP&wait=false", "application/pdf", multipart).statusCode());
    }
}
