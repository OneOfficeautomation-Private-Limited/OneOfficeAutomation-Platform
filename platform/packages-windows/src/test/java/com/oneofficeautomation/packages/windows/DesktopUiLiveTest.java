package com.oneofficeautomation.packages.windows;

import com.oneofficeautomation.sdk.ActionContext;
import org.junit.jupiter.api.Test;

import java.awt.GraphicsEnvironment;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real UI Automation against Character Map, a classic Win32 app built into Windows. (Notepad on Windows 11 opens new
 * files as tabs in an already running Notepad and restores unsaved tabs, so killing it could touch the user's work;
 * Character Map has neither.) Starts it, finds its edit box by ControlType, sets text, reads it back, types, captures
 * the control at its position, then kills the process and checks that neither it nor the helper is left running.
 */
class DesktopUiLiveTest {
    static final Path CHARMAP = Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"), "System32", "charmap.exe");

    static Map<String, Object> editObject() {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("technology", "desktop");
        o.put("window", Map.of("title", "Character Map", "title_match", "equals", "process", "charmap"));
        o.put("properties", List.of(DesktopObjectTest.prop("ControlType", "Edit", "equals", true)));
        o.put("index", 0);
        return o;
    }

    @SuppressWarnings("unchecked")
    @Test void characterMapEditBox() throws Exception {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") || GraphicsEnvironment.isHeadless() || !Files.exists(CHARMAP)) {
            System.out.println("    (no Windows desktop session with Character Map; skipped)");
            return;
        }
        List<String> logs = new ArrayList<>();
        Map<String, Object> sessions = new HashMap<>();
        ActionContext c = new ActionContext("run", "s", "default", Files.createTempDirectory("ooa-dui-live"), sessions, new HashMap<>(), new AtomicBoolean(), List::of, (l, m) -> logs.add(l + ": " + m));
        Process app = new ProcessBuilder(List.of(CHARMAP.toString())).start();
        UiaBridge bridge = null;
        try {
            DesktopUiPackage p = new DesktopUiPackage();
            assertEquals(true, p.waitForObject(c, editObject(), "appear", 30, true).get("found"));
            bridge = (UiaBridge) sessions.get(DesktopUiPackage.SESSION);

            Map<String, Object> obj = editObject();
            String text = "OOA agent " + UUID.randomUUID().toString().substring(0, 6);
            p.objectAction(c, obj, "set_text", "left", "auto", 0, 0, text, true, "set_value", 0, null, null, null, 10, "error");
            assertEquals(text, p.objectAction(c, obj, "get_text", "left", "auto", 0, 0, null, true, "auto", 0, null, null, null, 10, "error").get("result"));

            p.objectAction(c, obj, "set_text", "left", "auto", 0, 0, "typed", true, "type", 5, null, null, null, 10, "error");
            long end = System.currentTimeMillis() + 3000;
            Object read;
            do { read = p.objectAction(c, obj, "get_text", "left", "auto", 0, 0, null, true, "auto", 0, null, null, null, 10, "error").get("result"); }
            while (!"typed".equals(read) && System.currentTimeMillis() < end);
            assertEquals("typed", read, "typing replaces the text after select-all and delete");

            assertEquals("Edit", p.objectAction(c, obj, "get_property", "left", "auto", 0, 0, null, true, "auto", 0, "controltype", null, null, 10, "error").get("result"));
            Map<String, Object> props = (Map<String, Object>) p.getObjectProperties(c, obj, 10).get("properties");
            assertEquals("false", props.get("IsReadOnly"));
            assertTrue(String.valueOf(props.get("SupportedPatterns")).contains("Value"));
            Map<String, Object> xy = (Map<String, Object>) p.objectAction(c, obj, "get_coordinates", "left", "auto", 0, 0, null, true, "auto", 0, null, null, null, 10, "error").get("result");
            assertTrue(((BigDecimal) xy.get("width")).intValue() > 0);

            // capture the control at its centre, as Pick object does after Ctrl+Shift+C, and find it again with the result
            Map<String, Object> raw = (Map<String, Object>) bridge.call("capture_at", Map.of("x", xy.get("center_x"), "y", xy.get("center_y"), "screenshot", true), 30_000, null);
            Map<String, Object> captured = DesktopUiPackage.captured(c, raw);
            System.out.println("    captured: " + new LinkedHashMap<>(captured) {{ put("screenshot", String.valueOf(get("screenshot")).length() + " base64 chars"); }});
            assertEquals("desktop", captured.get("technology"));
            assertEquals("charmap", ((Map<String, Object>) captured.get("window")).get("process"));
            assertFalse(((List<?>) captured.get("path")).isEmpty());
            assertTrue(String.valueOf(captured.get("screenshot")).startsWith("iVBOR"), "PNG screenshot");
            assertEquals("typed", p.objectAction(c, captured, "get_text", "left", "auto", 0, 0, null, true, "auto", 0, null, null, null, 10, "error").get("result"));
            assertTrue(((BigDecimal) p.findObjects(c, captured, 0).get("count")).intValue() >= 1);

            // the full property set: where the control sits (path, XPath, index, parent), its states, text and position
            Map<String, String> cp = new LinkedHashMap<>();
            for (Object x : (List<Object>) captured.get("properties")) { Map<String, Object> m = (Map<String, Object>) x; cp.put((String) m.get("name"), String.valueOf(m.get("value"))); }
            assertTrue(cp.get("Path").matches("\\d+(/\\d+)*"), "Path: " + cp.get("Path"));
            assertTrue(cp.get("XPath").startsWith("/") && cp.get("XPath").contains("Edit["), "XPath: " + cp.get("XPath"));
            assertFalse(cp.get("Index").isBlank());
            assertFalse(cp.get("Parent").isBlank());
            assertEquals("typed", cp.get("Text"));
            assertTrue(cp.get("States").contains("Focusable"), "States: " + cp.get("States"));
            assertTrue(Integer.parseInt(cp.get("Width")) > 0 && Integer.parseInt(cp.get("Left")) >= 0, "position relative to the window");
            assertTrue(String.valueOf(((Map<String, Object>) captured.get("window")).get("path")).toLowerCase(Locale.ROOT).endsWith("charmap.exe"));

            // AA-style search by path and XPath alone (the helper reads the details only when such a rule is ticked)
            Map<String, Object> byPath = editObject();
            byPath.put("properties", List.of(DesktopObjectTest.prop("Path", cp.get("Path"), "equals", true), DesktopObjectTest.prop("XPath", cp.get("XPath"), "equals", true)));
            assertEquals("typed", p.objectAction(c, byPath, "get_text", "left", "auto", 0, 0, null, true, "auto", 0, null, null, null, 10, "error").get("result"));

            // the anchor: the Select button next to the edit box finds it when its own properties no longer match
            Map<String, Object> select = editObject();
            select.put("properties", List.of(DesktopObjectTest.prop("ControlType", "Button", "equals", true), DesktopObjectTest.prop("Name", "Select", "equals", true)));
            Map<String, Object> selProps = (Map<String, Object>) p.getObjectProperties(c, select, 10).get("properties");
            double[] e0 = DesktopObject.topLeft(cp.get("BoundingRectangle")), s0 = DesktopObject.topLeft(selProps.get("BoundingRectangle"));
            Map<String, Object> renamed = editObject();
            renamed.put("properties", List.of(DesktopObjectTest.prop("ControlType", "Edit", "equals", true), DesktopObjectTest.prop("AutomationId", "renamed-box", "equals", true)));
            Map<String, Object> anchor = new LinkedHashMap<>(select);
            anchor.put("offset", Map.of("dx", e0[0] - s0[0], "dy", e0[1] - s0[1]));
            renamed.put("anchor", anchor);
            Map<String, Object> viaAnchor = p.objectAction(c, renamed, "get_text", "left", "auto", 0, 0, null, true, "auto", 0, null, null, null, 3, "error");
            assertEquals("typed", viaAnchor.get("result"));
            assertEquals("anchor", viaAnchor.get("healed_by"));

            // not there in time: continue skips the step
            Map<String, Object> missing = editObject();
            missing.put("properties", List.of(DesktopObjectTest.prop("AutomationId", "never-there", "equals", true)));
            Map<String, Object> skipped = p.objectAction(c, missing, "click", "left", "auto", 0, 0, null, true, "auto", 0, null, null, null, 1, "continue");
            assertEquals(false, skipped.get("found"));
        } finally {
            app.descendants().forEach(ProcessHandle::destroyForcibly);
            app.destroyForcibly();
            assertTrue(app.waitFor(10, TimeUnit.SECONDS), "Character Map stopped");
            if (bridge != null) {
                Process helper = bridge.process();
                bridge.close();
                assertFalse(helper.isAlive(), "the UI Automation helper stopped");
                assertTrue(helper.descendants().noneMatch(ProcessHandle::isAlive), "nothing the helper started is left");
            }
        }
    }
}
