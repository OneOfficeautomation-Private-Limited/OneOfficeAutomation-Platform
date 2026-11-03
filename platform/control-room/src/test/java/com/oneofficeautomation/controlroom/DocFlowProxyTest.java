package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.packages.FirstPartyPackages;
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
    String base, token;
    final List<Map<String, String>> seen = Collections.synchronizedList(new ArrayList<>());

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
            byte[] b = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        docflow.start();
        cr.platform.updateSettings(admin, Map.of("docflow_url", "http://127.0.0.1:" + docflow.getAddress().getPort(), "docflow_token_ref", "docflow/service/token"), null);
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
}
