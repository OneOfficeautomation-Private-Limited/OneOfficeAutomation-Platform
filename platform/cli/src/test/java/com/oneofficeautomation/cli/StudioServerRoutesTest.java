package com.oneofficeautomation.cli;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Studio's server, as the Studio page uses it: the seeded workspace, folders and agents, the trash, names and paths that
 * try to leave the workspace, items, the shared clipboard, search and dependencies, triggers with their webhook secrets,
 * and the pages it serves. These are the server checks Classic Studio's page test made (cli/src/test/ui, removed with
 * Classic Studio), kept whatever the screen.
 */
class StudioServerRoutesTest {
    @SuppressWarnings("unchecked")
    @Test void workspaceAgentsAndPaths() throws Exception {
        Path root = Files.createTempDirectory("ooa-routes");
        int port = StudioControlRoomTest.freePort();
        new StudioServer(root, port, Path.of("../../examples/refund")).start();
        String base = "http://127.0.0.1:" + port;

        // an empty workspace starts with the examples, and an example in a sub-folder runs (its data paths still work)
        String tree = String.valueOf(call(base, "GET", "/api/tree", null).get("tree"));
        assertTrue(tree.contains("Examples") && tree.contains("My Agents") && tree.contains("data"), tree);
        Map<String, Object> lookup = (Map<String, Object>) call(base, "GET", "/api/bot?path=" + enc("Examples/Refunds/lookup_order.flow.json"), null).get("flow");
        assertEquals(3, ((List<?>) lookup.get("steps")).size());
        assertNotNull(StudioFilesTest.runOutputs(base, Map.of("flow", lookup, "inputs", Map.of("order_id", "ORD-10042"))));

        // folders, sub-folders and a new agent, saved and read back
        assertEquals("My Agents/Invoices", call(base, "POST", "/api/files/folder", Map.of("parent", "My Agents", "name", "Invoices")).get("path"));
        assertEquals("My Agents/Invoices/2026", call(base, "POST", "/api/files/folder", Map.of("parent", "My Agents/Invoices", "name", "2026")).get("path"));
        String bot = String.valueOf(call(base, "POST", "/api/files/bot", Map.of("parent", "My Agents/Invoices/2026", "name", "Monthly report")).get("path"));
        assertEquals("My Agents/Invoices/2026/Monthly report.flow.json", bot);
        Map<String, Object> flow = new LinkedHashMap<>((Map<String, Object>) call(base, "GET", "/api/bot?path=" + enc(bot), null).get("flow"));
        flow.put("steps", List.of(Map.of("id", "s1", "action", "core/log", "args", Map.of("message", "hello"))));
        assertEquals(200, call(base, "PUT", "/api/bot?path=" + enc(bot), flow).get("_status"));
        assertTrue(Files.readString(root.resolve(bot)).contains("hello"));

        // rename and move keep the item's type; delete goes to the workspace trash, which the tree does not show
        assertEquals("My Agents/Invoices/2026/Report.flow.json", call(base, "POST", "/api/files/rename", Map.of("path", bot, "name", "Report")).get("path"));
        assertEquals("My Agents/Report.flow.json", call(base, "POST", "/api/files/move", Map.of("path", "My Agents/Invoices/2026/Report.flow.json", "to", "My Agents")).get("path"));
        String trashed = String.valueOf(call(base, "POST", "/api/files/delete", Map.of("path", "My Agents/Invoices")).get("path"));
        assertTrue(trashed.startsWith(".trash/") && trashed.endsWith("_Invoices"), trashed);
        assertFalse(Files.exists(root.resolve("My Agents/Invoices")));
        assertTrue(Files.isDirectory(root.resolve(trashed)));
        assertFalse(String.valueOf(call(base, "GET", "/api/tree", null).get("tree")).contains(".trash"), "the trash is hidden");

        // names and paths that try to leave the workspace, or reach hidden files, are refused
        assertEquals("WORKSPACE.BAD_NAME", code(call(base, "POST", "/api/files/folder", Map.of("parent", "My Agents", "name", "../escape"))));
        assertFalse(Files.exists(root.resolve("escape")));
        assertEquals("WORKSPACE.OUTSIDE", code(call(base, "GET", "/api/bot?path=" + enc("../../outside.flow.json"), null)));
        assertEquals("WORKSPACE.HIDDEN", code(call(base, "GET", "/api/bot?path=" + enc(".trash/x.flow.json"), null)));
        assertEquals("WORKSPACE.OUTSIDE", code(call(base, "POST", "/api/files/copy", Map.of("path", "My Agents", "to", "../.."))));

        // items (here a form) as text, checked on save
        String form = String.valueOf(call(base, "POST", "/api/files/item", Map.of("parent", "My Agents", "name", "Invoice", "type", "form")).get("path"));
        assertTrue(form.endsWith(".form.json"), form);
        String text = String.valueOf(call(base, "GET", "/api/item?path=" + enc(form), null).get("text"));
        assertEquals(200, call(base, "PUT", "/api/item?path=" + enc(form), Map.of("text", text)).get("_status"));
        assertEquals(400, call(base, "PUT", "/api/item?path=" + enc(form), Map.of("text", "{ not json")).get("_status"), "a form that does not parse is refused");

        // the shared clipboard, search and dependencies
        assertEquals(200, call(base, "PUT", "/api/clipboard", Map.of("steps", List.of(Map.of("id", "c1", "action", "core/log")), "variables", List.of())).get("_status"));
        assertEquals("c1", ((Map<String, Object>) ((List<?>) call(base, "GET", "/api/clipboard", null).get("steps")).get(0)).get("id"));
        assertEquals("STUDIO.BAD_CLIPBOARD", code(call(base, "PUT", "/api/clipboard", Map.of("nothing", 1))));
        List<Map<String, Object>> found = (List<Map<String, Object>>) call(base, "GET", "/api/search?q=core%2Flog", null).get("results");
        assertTrue(found.stream().anyMatch(r -> "My Agents/Report.flow.json".equals(r.get("path"))), String.valueOf(found));
        assertEquals(200, call(base, "GET", "/api/deps?path=" + enc("My Agents/Report.flow.json"), null).get("_status"));
    }

    @SuppressWarnings("unchecked")
    @Test void triggersAndWebhooks() throws Exception {
        Path root = Files.createTempDirectory("ooa-routes-trig");
        int port = StudioControlRoomTest.freePort();
        new StudioServer(root, port, Path.of("/nonexistent")).start();
        String base = "http://127.0.0.1:" + port;
        Files.createDirectories(root.resolve("inbox"));
        Files.createDirectories(root.resolve("My Agents"));
        Files.writeString(root.resolve("My Agents/on_file.flow.json"), "{\"kind\":\"TaskBot\",\"id\":\"on_file\",\"name\":\"on file\",\"description\":\"\","
                + "\"variables\":[{\"name\":\"trigger_file\",\"type\":\"String\",\"direction\":\"input\"}],"
                + "\"steps\":[{\"id\":\"w\",\"action\":\"ooa.file/write_text\",\"args\":{\"path\":\"trigger-out.txt\",\"text\":\"got ${trigger_file}\",\"append\":true}}]}");

        Map<String, Object> bad = call(base, "PUT", "/api/triggers", List.of(Map.of("id", "t1", "name", "x", "bot", "../../evil.flow.json", "type", "file", "folder", "inbox")));
        assertTrue(code(bad).startsWith("WORKSPACE") || code(bad).startsWith("TRIGGER"), String.valueOf(bad));
        assertEquals("TRIGGER.WEAK_SECRET", code(call(base, "PUT", "/api/triggers", List.of(Map.of("id", "h1", "bot", "My Agents/on_file.flow.json", "type", "webhook", "secret", "short")))));
        String secret = "s3cret-webhook-0123456789";
        Map<String, Object> ok = call(base, "PUT", "/api/triggers", List.of(
                Map.of("id", "files", "name", "New files", "bot", "My Agents/on_file.flow.json", "type", "file", "folder", "inbox", "pattern", "*.pdf"),
                Map.of("id", "sched", "name", "Every minute", "bot", "My Agents/on_file.flow.json", "type", "schedule", "every_minutes", 1),
                Map.of("id", "hook", "name", "Hook", "bot", "My Agents/on_file.flow.json", "type", "webhook", "secret", secret)));
        assertEquals(3, ((List<?>) ok.get("triggers")).size(), String.valueOf(ok));

        // a file trigger runs the agent with the arriving file; files that do not match its pattern are ignored
        Thread.sleep(500);
        Files.writeString(root.resolve("inbox/INV-77.pdf"), "%PDF");
        Files.writeString(root.resolve("inbox/notes.txt"), "ignored");
        Path out = root.resolve("trigger-out.txt");
        assertTrue(waitFor(() -> Files.exists(out) && read(out).contains("INV-77.pdf"), 15000), "the file trigger ran");
        assertFalse(read(out).contains("notes.txt"));

        // a webhook needs its secret
        HttpClient http = HttpClient.newHttpClient();
        assertEquals(403, http.send(HttpRequest.newBuilder(URI.create(base + "/api/hooks/hook")).header("X-OOA-Secret", "wrong")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(202, http.send(HttpRequest.newBuilder(URI.create(base + "/api/hooks/hook")).header("X-OOA-Secret", secret).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"invoice\":\"INV-9\"}")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());

        // a schedule fires on its own, and the log says so
        assertTrue(waitFor(() -> ((List<Map<String, Object>>) call(base, "GET", "/api/triggers", null).get("log")).stream()
                .anyMatch(e -> "sched".equals(e.get("trigger")) && "success".equals(e.get("status"))), 20000),
                String.valueOf(call(base, "GET", "/api/triggers", null).get("log")));
        assertEquals(List.of(), call(base, "PUT", "/api/triggers", List.of()).get("triggers"));
    }

    @Test void classicStudioIsGone() throws Exception {
        int port = StudioControlRoomTest.freePort();
        new StudioServer(Files.createTempDirectory("ooa-routes-pages"), port, Path.of("/nonexistent")).start();
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        String base = "http://127.0.0.1:" + port;
        for (String p : List.of("/classic/", "/classic", "/classic/studio.js", "/studio.js", "/logo-mark.png"))
            assertEquals(404, http.send(HttpRequest.newBuilder(URI.create(base + p)).build(), HttpResponse.BodyHandlers.ofString()).statusCode(), p);
        HttpResponse<String> next = http.send(HttpRequest.newBuilder(URI.create(base + "/next/")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(302, next.statusCode(), "the preview's old address goes to the Studio");
        assertEquals("/", next.headers().firstValue("Location").orElse(""));
    }

    interface Check { boolean ok() throws Exception; }

    static boolean waitFor(Check c, long ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) { if (c.ok()) return true; Thread.sleep(250); }
        return false;
    }

    static String read(Path p) { try { return Files.readString(p); } catch (java.io.IOException e) { return ""; } }

    @SuppressWarnings("unchecked")
    static String code(Map<String, Object> r) { return r.get("error") instanceof Map<?, ?> e ? String.valueOf(((Map<String, Object>) e).get("code")) : "none: " + r; }

    static String enc(String s) { return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8); }

    static Map<String, Object> call(String base, String method, String path, Object body) throws Exception { return StudioControlRoomTest.call(base, method, path, body, null); }
}
