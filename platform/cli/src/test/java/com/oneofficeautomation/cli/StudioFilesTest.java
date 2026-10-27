package com.oneofficeautomation.cli;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Explorer file operations (copy, duplicate, upload, download) and Run from here / Run this step only. */
class StudioFilesTest {
    @Test void workspaceCopyDuplicateAndUploadStayInside() throws Exception {
        Path root = Files.createTempDirectory("ooa-files");
        Workspace ws = new Workspace(root);
        ws.createFolder("", "A");
        ws.createFolder("A", "Inner");
        Files.writeString(root.resolve("A/Inner/data.csv"), "x,y");
        Files.writeString(root.resolve("A/.hidden"), "secret");
        ws.createFolder("", "B");

        assertEquals("B/A", ws.copy("A", "B"));
        assertTrue(Files.isRegularFile(root.resolve("B/A/Inner/data.csv")));
        assertFalse(Files.exists(root.resolve("B/A/.hidden")), "hidden entries are not copied");
        assertEquals("B/A - Copy", ws.copy("A", "B"), "a taken name gets ' - Copy'");
        assertEquals("B/A - Copy (2)", ws.copy("A", "B"));
        assertEquals("WORKSPACE.INTO_ITSELF", assertThrows(com.oneofficeautomation.sdk.OoaException.class, () -> ws.copy("A", "A/Inner")).code());
        assertEquals("WORKSPACE.OUTSIDE", assertThrows(com.oneofficeautomation.sdk.OoaException.class, () -> ws.copy("../x", "B")).code());
        assertEquals("WORKSPACE.OUTSIDE", assertThrows(com.oneofficeautomation.sdk.OoaException.class, () -> ws.copy("A", "../..")).code());
        assertEquals("WORKSPACE.HIDDEN", assertThrows(com.oneofficeautomation.sdk.OoaException.class, () -> ws.copy("A/.hidden", "B")).code());

        ws.createBot("A", "Invoice", "TaskBot", v -> "{\"kind\":\"TaskBot\",\"id\":\"Invoice\",\"name\":\"Invoice\",\"steps\":[]}");
        assertEquals("A/Invoice - Copy.flow.json", ws.duplicate("A/Invoice.flow.json"), "the agent suffix is kept");

        assertEquals("B/report_1_.txt", ws.upload("B", "..\\..\\report[1].txt", "hello".getBytes()), "upload names lose folders and unsafe characters");
        assertEquals("B/report_1_ (2).txt", ws.upload("B", "report[1].txt", "again".getBytes()));
        assertEquals("hello", Files.readString(root.resolve("B/report_1_.txt")));
        assertEquals("WORKSPACE.OUTSIDE", assertThrows(com.oneofficeautomation.sdk.OoaException.class, () -> ws.upload("../..", "x.txt", new byte[0])).code());
    }

    @SuppressWarnings("unchecked")
    @Test void fileEndpointsAndRunFromHere() throws Exception {
        Path root = Files.createTempDirectory("ooa-files-api");
        int port = StudioControlRoomTest.freePort();
        new StudioServer(root, port, Path.of("/nonexistent")).start();
        String base = "http://127.0.0.1:" + port;
        Files.createDirectories(root.resolve("Agents"));
        Files.writeString(root.resolve("Agents/one.flow.json"), "{\"kind\":\"TaskBot\",\"id\":\"one\",\"name\":\"one\",\"variables\":[],\"steps\":[]}");

        Map<String, Object> dup = call(base, "POST", "/api/files/duplicate", Map.of("path", "Agents/one.flow.json"));
        assertEquals("Agents/one - Copy.flow.json", dup.get("path"), String.valueOf(dup));
        assertEquals("one_-_Copy", ((Map<String, Object>) Ooa.codec().parse(Files.readString(root.resolve("Agents/one - Copy.flow.json")))).get("id"),
                "a duplicated agent gets its own id");
        assertEquals("one.flow.json", call(base, "POST", "/api/files/copy", Map.of("path", "Agents/one.flow.json", "to", "")).get("path"));
        assertEquals(400, ((Number) call(base, "POST", "/api/files/copy", Map.of("path", "Agents/one.flow.json", "to", "../outside")).get("_status")).intValue());

        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<String> up = http.send(HttpRequest.newBuilder(URI.create(base + "/api/files/upload?parent=Agents&name=notes.txt"))
                .POST(HttpRequest.BodyPublishers.ofString("uploaded text")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, up.statusCode(), up.body());
        assertEquals("uploaded text", Files.readString(root.resolve("Agents/notes.txt")));
        HttpResponse<byte[]> file = http.send(HttpRequest.newBuilder(URI.create(base + "/api/files/download?path=Agents/notes.txt")).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals("uploaded text", new String(file.body()));
        assertTrue(file.headers().firstValue("Content-Disposition").orElse("").contains("notes.txt"));
        HttpResponse<byte[]> zip = http.send(HttpRequest.newBuilder(URI.create(base + "/api/files/download?path=Agents")).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals('P', (char) zip.body()[0], "a folder downloads as a zip");
        HttpResponse<String> evil = http.send(HttpRequest.newBuilder(URI.create(base + "/api/files/download?path=..%2F..%2Fwindows")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(400, evil.statusCode());
        assertTrue(evil.body().contains("WORKSPACE.OUTSIDE"));

        Map<String, Object> flow = Map.of("kind", "TaskBot", "id", "f", "name", "f",
                "variables", List.of(Map.of("name", "a", "type", "String", "direction", "output", "default", "-"), Map.of("name", "b", "type", "String", "direction", "output", "default", "-")),
                "steps", List.of(Map.of("id", "s1", "action", "core/set", "args", Map.of("variable", "a", "value", "one")),
                        Map.of("id", "s2", "action", "core/set", "args", Map.of("variable", "b", "value", "two"))));
        assertEquals(Map.of("a", "-", "b", "two"), runOutputs(base, Map.of("flow", flow, "start_at", "s2")));
        assertEquals(Map.of("a", "one", "b", "-"), runOutputs(base, Map.of("flow", flow, "only_step", "s1")));

        // after a full run, Run from here starts with the values that run had at the step
        assertEquals(Map.of("a", "one", "b", "two"), runOutputs(base, Map.of("flow", flow)));
        Map<String, Object> from = new java.util.HashMap<>(Map.of("flow", flow, "start_at", "s2"));
        assertEquals("step", call(base, "POST", "/api/runs", from).get("carried_over"));
        assertEquals(Map.of("a", "one", "b", "two"), runOutputs(base, from), "a comes from the last run although s1 is skipped");
        from.put("carry_over", false);
        assertEquals(Map.of("a", "-", "b", "two"), runOutputs(base, from), "with default values");

        // the agents list says which agents each one runs (Run agent steps, also inside blocks), for Studio's "Run by" note
        Files.writeString(root.resolve("Agents/parent.flow.json"), "{\"kind\":\"TaskBot\",\"id\":\"parent\",\"name\":\"parent\",\"variables\":[],\"steps\":["
                + "{\"id\":\"p1\",\"action\":\"core/run_bot\",\"args\":{\"automation\":\"one\"}},"
                + "{\"id\":\"p2\",\"action\":\"core/if\",\"args\":{\"condition\":true},\"children\":[{\"id\":\"p3\",\"action\":\"core/run_bot\",\"args\":{\"automation\":\"two\"}},"
                + "{\"id\":\"p4\",\"action\":\"core/run_bot\",\"args\":{\"automation\":\"one\"}}]}]}");
        List<Map<String, Object>> agents = (List<Map<String, Object>>) call(base, "GET", "/api/agents", null).get("agents");
        Map<String, Object> parent = agents.stream().filter(a -> "parent".equals(a.get("id"))).findFirst().orElseThrow();
        assertEquals(List.of("one", "two"), parent.get("runs"));
        assertEquals(List.of(), agents.stream().filter(a -> "one".equals(a.get("id"))).findFirst().orElseThrow().get("runs"));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> runOutputs(String base, Map<String, Object> req) throws Exception {
        String id = String.valueOf(call(base, "POST", "/api/runs", req).get("run_id"));
        for (int i = 0; i < 100; i++) {
            Map<String, Object> r = call(base, "GET", "/api/runs/" + id, null);
            if (r.get("result") instanceof Map<?, ?> res) {
                assertEquals("success", res.get("status"), String.valueOf(res));
                return (Map<String, Object>) res.get("outputs");
            }
            Thread.sleep(50);
        }
        throw new AssertionError("run did not finish");
    }

    static Map<String, Object> call(String base, String method, String path, Object body) throws Exception {
        return StudioControlRoomTest.call(base, method, path, body, null);
    }
}
