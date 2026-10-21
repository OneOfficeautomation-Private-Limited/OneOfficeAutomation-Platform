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
            p.objectAction(c, obj, "set_text", "left", "auto", 0, 0, text, true, "set_value", 0, null, null, null, 10);
            assertEquals(text, p.objectAction(c, obj, "get_text", "left", "auto", 0, 0, null, true, "auto", 0, null, null, null, 10).get("result"));

            p.objectAction(c, obj, "set_text", "left", "auto", 0, 0, "typed", true, "type", 5, null, null, null, 10);
            long end = System.currentTimeMillis() + 3000;
            Object read;
            do { read = p.objectAction(c, obj, "get_text", "left", "auto", 0, 0, null, true, "auto", 0, null, null, null, 10).get("result"); }
            while (!"typed".equals(read) && System.currentTimeMillis() < end);
            assertEquals("typed", read, "typing replaces the text after select-all and delete");

            assertEquals("Edit", p.objectAction(c, obj, "get_property", "left", "auto", 0, 0, null, true, "auto", 0, "controltype", null, null, 10).get("result"));
            Map<String, Object> props = (Map<String, Object>) p.getObjectProperties(c, obj, 10).get("properties");
            assertEquals("false", props.get("IsReadOnly"));
            assertTrue(String.valueOf(props.get("SupportedPatterns")).contains("Value"));
            Map<String, Object> xy = (Map<String, Object>) p.objectAction(c, obj, "get_coordinates", "left", "auto", 0, 0, null, true, "auto", 0, null, null, null, 10).get("result");
            assertTrue(((BigDecimal) xy.get("width")).intValue() > 0);

            // capture the control at its centre, as Pick object does after Ctrl+Shift+C, and find it again with the result
            Map<String, Object> raw = (Map<String, Object>) bridge.call("capture_at", Map.of("x", xy.get("center_x"), "y", xy.get("center_y"), "screenshot", true), 30_000, null);
            Map<String, Object> captured = DesktopUiPackage.captured(c, raw);
            System.out.println("    captured: " + new LinkedHashMap<>(captured) {{ put("screenshot", String.valueOf(get("screenshot")).length() + " base64 chars"); }});
            assertEquals("desktop", captured.get("technology"));
            assertEquals("charmap", ((Map<String, Object>) captured.get("window")).get("process"));
            assertFalse(((List<?>) captured.get("path")).isEmpty());
            assertTrue(String.valueOf(captured.get("screenshot")).startsWith("iVBOR"), "PNG screenshot");
            assertEquals("typed", p.objectAction(c, captured, "get_text", "left", "auto", 0, 0, null, true, "auto", 0, null, null, null, 10).get("result"));
            assertTrue(((BigDecimal) p.findObjects(c, captured, 0).get("count")).intValue() >= 1);
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
