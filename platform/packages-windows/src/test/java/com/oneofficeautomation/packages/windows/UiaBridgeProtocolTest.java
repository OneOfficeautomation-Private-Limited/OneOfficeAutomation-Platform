package com.oneofficeautomation.packages.windows;

import com.oneofficeautomation.packages.ui.InterruptRules;
import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import com.oneofficeautomation.sdk.JsonLite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** The JSON-lines protocol and the toolkit's find, heal and act flow, against an in-memory fake helper. */
class UiaBridgeProtocolTest {
    /** A helper that answers each request line with handler(request); null from the handler means "never answer". */
    static final class FakeHelper {
        final List<Map<String, Object>> requests = new CopyOnWriteArrayList<>();
        final UiaBridge bridge;
        FakeHelper(Function<Map<String, Object>, Map<String, Object>> handler) throws IOException {
            PipedOutputStream toBridge = new PipedOutputStream();
            PipedInputStream fromHelper = new PipedInputStream(toBridge, 1 << 16);
            PipedOutputStream toHelper = new PipedOutputStream();
            PipedInputStream helperIn = new PipedInputStream(toHelper, 1 << 16);
            Thread t = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(helperIn, StandardCharsets.UTF_8));
                     Writer w = new OutputStreamWriter(toBridge, StandardCharsets.UTF_8)) {
                    w.write("some banner text that is not JSON\n{\"ready\":true,\"protocol\":1}\n"); w.flush();
                    for (String l; (l = r.readLine()) != null; ) {
                        @SuppressWarnings("unchecked") Map<String, Object> req = (Map<String, Object>) JsonLite.parse(l);
                        requests.add(req);
                        if ("exit".equals(req.get("op"))) return;
                        Map<String, Object> resp = handler.apply(req);
                        if (resp == null) continue;
                        Map<String, Object> full = new LinkedHashMap<>(resp);
                        full.put("id", req.get("id"));
                        w.write(JsonLite.write(full) + "\n"); w.flush();
                    }
                } catch (IOException ignored) { }
            }, "fake-uia-helper");
            t.setDaemon(true);
            t.start();
            bridge = new UiaBridge(fromHelper, toHelper, null, null, 5000);
        }
        List<String> ops() { return requests.stream().map(r -> String.valueOf(r.get("op"))).toList(); }
    }

    static Map<String, Object> ok(Object result) { Map<String, Object> m = new LinkedHashMap<>(); m.put("ok", true); m.put("result", result); return m; }

    final List<String> logs = new CopyOnWriteArrayList<>();
    FakeHelper helper;

    ActionContext ctx(FakeHelper h) throws IOException {
        Map<String, Object> sessions = new HashMap<>();
        sessions.put(DesktopUiPackage.SESSION, h.bridge);
        return new ActionContext("run", "s", "default", Files.createTempDirectory("ooa-dui"), sessions, new HashMap<>(), new AtomicBoolean(), List::of, (l, m) -> logs.add(l + ": " + m));
    }

    @AfterEach void stop() { if (helper != null) helper.bridge.close(); }

    @Test void requestsAndResponses() throws Exception {
        helper = new FakeHelper(req -> switch (String.valueOf(req.get("op"))) {
            case "ping" -> ok("pong");
            case "boom" -> Map.of("ok", false, "code", "NOT_SUPPORTED", "error", "This object cannot be expanded");
            default -> null;
        });
        assertEquals("pong", helper.bridge.call("ping", Map.of("x", "é \"quoted\"\nline"), 2000, null));
        Map<String, Object> sent = helper.requests.get(0);
        assertEquals(BigDecimal.ONE, sent.get("id"));
        assertEquals("ping", sent.get("op"));
        assertEquals("é \"quoted\"\nline", sent.get("x"), "text round-trips through the JSON line unchanged");
        ActionException e = assertThrows(ActionException.class, () -> helper.bridge.call("boom", Map.of(), 2000, null));
        assertEquals("DESKTOP_UI.NOT_SUPPORTED", e.code());
        assertEquals("This object cannot be expanded", e.getMessage());
        ActionException t = assertThrows(ActionException.class, () -> helper.bridge.call("silent", Map.of(), 300, null));
        assertEquals("DESKTOP_UI.BRIDGE_TIMEOUT", t.code());
        assertFalse(helper.bridge.alive(), "a helper that stops answering is dropped so the next step starts a new one");
    }

    @Test void cancellingStopsTheCall() throws Exception {
        helper = new FakeHelper(req -> null);
        AtomicBoolean cancel = new AtomicBoolean();
        new Thread(() -> { try { Thread.sleep(300); } catch (InterruptedException ignored) { } cancel.set(true); }).start();
        RuntimeException e = assertThrows(RuntimeException.class, () -> helper.bridge.call("pick", Map.of(), 10_000, () -> { if (cancel.get()) throw new ActionException("RUN.CANCELLED", "stopped"); }));
        assertTrue(e.getMessage().contains("stopped"));
        assertFalse(helper.bridge.alive());
    }

    static Map<String, Object> element(int ref, String type, String name, String aid) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("Name", name); m.put("AutomationId", aid); m.put("ClassName", "Button"); m.put("ControlType", type);
        m.put("RuntimeId", "42." + ref); m.put("ref", BigDecimal.valueOf(ref));
        return m;
    }

    /** Windows: one Calculator-like window. Elements: two buttons (refs 1, 2) and an edit (3). */
    static FakeHelper desktop(List<Map<String, Object>> elements) throws IOException {
        return new FakeHelper(req -> switch (String.valueOf(req.get("op"))) {
            case "windows" -> ok(List.of(Map.of("handle", 100, "title", "Invoices - Billing", "class_name", "Main", "process", "billing", "pid", 7),
                    Map.of("handle", 200, "title", "Other", "class_name", "Main", "process", "other", "pid", 8)));
            case "find" -> ok(elements);
            case "act" -> switch (String.valueOf(req.get("action"))) {
                case "get_text" -> ok("text of " + req.get("ref"));
                case "click" -> ok("pattern");
                default -> ok(true);
            };
            default -> Map.of("ok", false, "code", "BAD_REQUEST", "error", "unknown");
        });
    }

    static Map<String, Object> object(String name, String nameMatch, int index) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("technology", "desktop");
        o.put("window", Map.of("title", "* - Billing", "title_match", "wildcard", "process", "billing.exe"));
        o.put("properties", List.of(DesktopObjectTest.prop("ControlType", "Button", "equals", true), DesktopObjectTest.prop("Name", name, nameMatch, true),
                DesktopObjectTest.prop("AutomationId", "btnSave", "equals", false)));
        o.put("index", index);
        return o;
    }

    @Test void findsByUsedPropertiesWithIndexAndActs() throws Exception {
        helper = desktop(List.of(element(1, "Button", "Save draft", "btnDraft"), element(2, "Button", "Save", "btnSave"), element(3, "Edit", "Notes", "")));
        ActionContext c = ctx(helper);
        DesktopUiPackage p = new DesktopUiPackage();
        Map<String, Object> r = p.objectAction(c, object("save", "starts_with", 1), "get_text", "left", "auto", 0, 0, null, true, "auto", 0, null, null, null, 1);
        assertEquals("text of 2", r.get("result"), "index 1 picks the second of the two matching buttons");
        assertEquals("", r.get("healed_by"));
        Map<String, Object> find = helper.requests.stream().filter(q -> "find".equals(q.get("op"))).findFirst().orElseThrow();
        assertEquals(List.of(new BigDecimal(100)), find.get("windows"), "only the window matching title and process is searched");
        assertEquals(Map.of("ControlType", "Button"), find.get("prefilter"), "exact ControlType is passed on so the helper searches less");

        p.objectAction(c, object("Save", "equals", 0), "click", "double", "mouse", 5, -2, null, true, "auto", 0, null, null, null, 1);
        Map<String, Object> click = helper.requests.get(helper.requests.size() - 1);
        assertEquals("act", click.get("op"));
        assertEquals("click", click.get("action"));
        assertEquals(new BigDecimal(2), click.get("ref"));
        assertEquals("left", click.get("button"));
        assertEquals(true, click.get("double"));
        assertEquals("mouse", click.get("method"));
        assertEquals(new BigDecimal(5), click.get("offset_x"));
    }

    @Test void healsByRecordedAutomationIdAndLogsIt() throws Exception {
        helper = desktop(List.of(element(1, "Button", "Store", "btnSave"), element(2, "Button", "Cancel", "btnCancel")));
        ActionContext c = ctx(helper);
        Map<String, Object> r = new DesktopUiPackage().objectAction(c, object("Save", "equals", 0), "set_text", "left", "auto", 0, 0, "hello", false, "type", 25, null, null, null, 1);
        assertEquals("automation_id", r.get("healed_by"));
        Map<String, Object> set = helper.requests.get(helper.requests.size() - 1);
        assertEquals("set_text", set.get("action"));
        assertEquals(new BigDecimal(1), set.get("ref"));
        assertEquals("hello", set.get("text"));
        assertEquals(false, set.get("clear_first"));
        assertEquals("type", set.get("method"));
        assertEquals(new BigDecimal(25), set.get("delay_ms"));
        assertTrue(logs.stream().anyMatch(l -> l.startsWith("warning") && l.contains("found the element by automation_id")), logs.toString());
    }

    @Test void notFoundAndConditionAndWait() throws Exception {
        helper = desktop(List.of(element(1, "Button", "Cancel", "btnCancel"), element(2, "Button", "Help", "btnHelp")));
        ActionContext c = ctx(helper);
        DesktopUiPackage p = new DesktopUiPackage();
        ActionException e = assertThrows(ActionException.class, () -> p.objectAction(c, object("Save", "equals", 0), "click", "left", "auto", 0, 0, null, true, "auto", 0, null, null, null, 0));
        assertEquals("DESKTOP_UI.OBJECT_NOT_FOUND", e.code());
        assertFalse(p.objectExists(c, object("Save", "equals", 0)), "two buttons exist, so ControlType alone does not heal");
        assertTrue(p.objectExists(c, object("help", "contains", 0)));
        assertEquals(true, p.waitForObject(c, object("Save", "equals", 0), "disappear", 0, true).get("found"));
        assertEquals(false, p.waitForObject(c, object("Save", "equals", 0), "appear", 0, false).get("found"));
        Map<String, Object> f = p.findObjects(c, object("*", "wildcard", 0), 0);
        assertEquals(new BigDecimal(2), f.get("count"));
        assertFalse(((Map<?, ?>) ((List<?>) f.get("objects")).get(0)).containsKey("ref"), "helper references stay internal");
    }

    @Test void interruptRulesAreCheckedBeforeActions() throws Exception {
        helper = desktop(List.of(element(1, "Button", "Save", "btnSave")));
        ActionContext c = ctx(helper);
        InterruptRules.of(c).add(new InterruptRules.Rule("never", "No such window title 9f1c", null, null, "ignore", null, null, 0));
        assertEquals(true, new DesktopUiPackage().objectAction(c, object("Save", "equals", 0), "invoke", "left", "auto", 0, 0, null, true, "auto", 0, null, null, null, 1).get("result"));
        assertEquals("invoke", helper.requests.get(helper.requests.size() - 1).get("action"));
    }

    @Test void pickBuildsTheObjectAndItsIndex() throws Exception {
        Map<String, Object> picked = new LinkedHashMap<>(element(9, "Button", "Save", "btnSave"));
        picked.remove("ref");
        helper = new FakeHelper(req -> switch (String.valueOf(req.get("op"))) {
            case "pick" -> ok(Map.of("element", picked, "window", Map.of("title", "Invoices - Billing", "process", "billing", "handle", 100),
                    "path", List.of(Map.of("ControlType", "Window", "Name", "Invoices - Billing")), "screenshot", "iVBORw0KGgo="));
            case "find" -> ok(List.of(element(1, "Button", "Other", "x"), element(5, "Button", "Save", "btnSave"), element(9, "Button", "Save", "btnSave")));
            default -> null;
        });
        @SuppressWarnings("unchecked")
        Map<String, Object> o = (Map<String, Object>) new DesktopUiPackage().pickObject(ctx(helper), 5, true, false).get("object");
        assertEquals("desktop", o.get("technology"));
        assertEquals(new BigDecimal(1), o.get("index"), "the picked control is the second one matching its default properties");
        assertEquals("iVBORw0KGgo=", o.get("screenshot"));
        Map<String, Object> pick = helper.requests.get(0);
        assertEquals(new BigDecimal(5000), pick.get("timeout_ms"));
        assertEquals(false, pick.get("highlight"));
    }
}
