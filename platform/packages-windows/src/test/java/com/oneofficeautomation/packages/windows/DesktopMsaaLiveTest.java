package com.oneofficeautomation.packages.windows;

import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import org.junit.jupiter.api.Test;

import java.awt.GraphicsEnvironment;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * X6: MSAA (IAccessible) against Character Map, as DesktopUiLiveTest does for UI Automation: list its push buttons,
 * press Select (its default action) so a character goes into "Characters to copy", read that box's value, set it, and
 * report a control that is not there. Character Map is closed and the helper stopped at the end.
 */
class DesktopMsaaLiveTest {

    @SuppressWarnings("unchecked")
    @Test void characterMapThroughMsaa() throws Exception {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") || GraphicsEnvironment.isHeadless() || !Files.exists(DesktopUiLiveTest.CHARMAP)) {
            System.out.println("    (no Windows desktop session with Character Map; skipped)");
            return;
        }
        Map<String, Object> sessions = new HashMap<>();
        ActionContext c = new ActionContext("run", "s", "default", Files.createTempDirectory("ooa-msaa-live"), sessions, new HashMap<>(), new AtomicBoolean(), List::of, (l, m) -> { });
        Process app = new ProcessBuilder(List.of(DesktopUiLiveTest.CHARMAP.toString())).start();
        try {
            DesktopUiPackage p = new DesktopUiPackage();
            // the window takes a moment to appear: the action waits for it
            Map<String, Object> select = p.legacyObjectAction(c, "Character Map", "Select", "push button", 0, null, "get_properties", null, "charmap", 30);
            assertEquals("push button", ((Map<String, Object>) select.get("object")).get("role"));

            Map<String, Object> buttons = p.findLegacyObjects(c, "Character Map", null, "push button", null, "charmap", 100);
            List<String> names = ((List<Object>) buttons.get("objects")).stream().map(o -> String.valueOf(((Map<String, Object>) o).get("name"))).toList();
            assertTrue(names.contains("Select") && names.contains("Copy"), names.toString());

            p.legacyObjectAction(c, "Character Map", "Select", "push button", 0, null, "default_action", null, "charmap", 5);
            Map<String, Object> box = p.legacyObjectAction(c, "Character Map", "Characters to copy", "editable text", 0, null, "get_value", null, "charmap", 5);
            assertFalse(String.valueOf(box.get("result")).isEmpty(), "Select put the chosen character in the box");

            String path = String.valueOf(((Map<String, Object>) box.get("object")).get("path"));
            p.legacyObjectAction(c, "Character Map", null, null, 0, path, "set_value", "OOA", "charmap", 5);
            assertEquals("OOA", p.legacyObjectAction(c, "Character Map", null, null, 0, path, "get_value", null, "charmap", 5).get("result"));

            assertEquals("DESKTOP_UI.OBJECT_NOT_FOUND", assertThrows(ActionException.class,
                () -> p.legacyObjectAction(c, "Character Map", "No such button", "push button", 0, null, "default_action", null, "charmap", 0)).code());
            assertEquals(BigDecimal.ZERO, p.findLegacyObjects(c, "Character Map", "No such button", null, null, "charmap", 10).get("count"));
        } finally {
            app.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
            if (sessions.get(DesktopUiPackage.SESSION) instanceof UiaBridge b) b.close();
        }
        assertFalse(app.isAlive());
    }
}
