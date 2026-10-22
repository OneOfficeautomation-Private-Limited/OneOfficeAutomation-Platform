package com.oneofficeautomation.packages.windows;

import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** The desktop recorder's events become Desktop object action steps; Run in background refuses what needs the mouse. */
class DesktopRecordTest {
    static ActionContext ctx() throws Exception {
        return new ActionContext("run", "s", "default", Files.createTempDirectory("drec"), new HashMap<>(), new HashMap<>(), new AtomicBoolean(), List::of, (l, m) -> { });
    }

    static Map<String, Object> capture(String type, String name) {
        Map<String, Object> el = new LinkedHashMap<>();
        el.put("ControlType", type); el.put("Name", name); el.put("AutomationId", "");
        return Map.of("element", el, "window", Map.of("title", "Order entry", "process", "orders"), "path", List.of());
    }

    @Test @SuppressWarnings("unchecked")
    void eventsBecomeObjectActionSteps() throws Exception {
        List<Object> events = List.of(
                Map.of("kind", "click", "capture", capture("Button", "New")),
                Map.of("kind", "type", "capture", capture("Edit", "Customer"), "text", "Ann", "password", false),
                Map.of("kind", "type", "capture", capture("Edit", "Password"), "password", true),
                Map.of("kind", "double_click", "capture", capture("ListItem", "Row 1")),
                Map.of("kind", "right_click", "capture", capture("Pane", "Grid")));
        Map<String, Object> r = DesktopUiPackage.stepsFromEvents(ctx(), events);
        List<Map<String, Object>> steps = (List<Map<String, Object>>) r.get("steps");
        assertEquals(5, steps.size());
        steps.forEach(s -> assertEquals("ooa.desktop_ui/object_action", s.get("action")));
        Map<String, Object> a0 = (Map<String, Object>) steps.get(0).get("args");
        assertEquals("click", a0.get("action"));
        assertEquals("Order entry", ((Map<String, Object>) ((Map<String, Object>) a0.get("object")).get("window")).get("title"));
        Map<String, Object> a1 = (Map<String, Object>) steps.get(1).get("args");
        assertEquals("set_text", a1.get("action"));
        assertEquals("Ann", a1.get("text"));
        assertEquals("$password", ((Map<String, Object>) steps.get(2).get("args")).get("text"));
        assertEquals("double", ((Map<String, Object>) steps.get(3).get("args")).get("click_type"));
        assertEquals("right", ((Map<String, Object>) steps.get(4).get("args")).get("click_type"));
    }

    @Test
    void runInBackgroundRefusesWhatNeedsTheMouse() throws Exception {
        Map<String, Object> obj = DesktopObject.fromCapture(capture("Button", "Save"));
        ActionException e = assertThrows(ActionException.class, () -> new DesktopUiPackage().objectAction(ctx(), obj, "click", "right", "auto", 0, 0, null, true, "auto", 0,
                null, null, null, 1, "error", true));
        assertEquals("DESKTOP_UI.NOT_IN_BACKGROUND", e.code());
        e = assertThrows(ActionException.class, () -> new DesktopUiPackage().objectAction(ctx(), obj, "focus", "left", "auto", 0, 0, null, true, "auto", 0,
                null, null, null, 1, "error", true));
        assertEquals("DESKTOP_UI.NOT_IN_BACKGROUND", e.code());
    }
}
