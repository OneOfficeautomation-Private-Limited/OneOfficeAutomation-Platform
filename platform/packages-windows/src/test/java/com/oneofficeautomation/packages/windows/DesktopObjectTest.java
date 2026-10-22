package com.oneofficeautomation.packages.windows;

import com.oneofficeautomation.packages.ui.SelfHealing;
import com.oneofficeautomation.sdk.ActionException;
import com.oneofficeautomation.sdk.JsonLite;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class DesktopObjectTest {
    static Map<String, Object> prop(String name, String value, String match, boolean use) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name); m.put("value", value); m.put("match", match); m.put("use", use);
        return m;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> notepadEdit() {
        return (Map<String, Object>) JsonLite.parse("""
                {"technology": "desktop",
                 "window": {"title": "Untitled - Notepad", "title_match": "wildcard", "process": "notepad"},
                 "properties": [{"name": "ControlType", "value": "Edit", "match": "equals", "use": true},
                                {"name": "Name", "value": "Text editor", "match": "contains", "use": true},
                                {"name": "AutomationId", "value": "15", "match": "equals", "use": false},
                                {"name": "ClassName", "value": "Edit", "match": "equals", "use": false}],
                 "path": [{"ControlType": "Window", "Name": "Untitled - Notepad"}],
                 "index": 0,
                 "screenshot": ""}""");
    }

    @Test void matchModes() {
        assertTrue(DesktopObject.matches("equals", "Save", "save"));
        assertFalse(DesktopObject.matches("equals", "Save", "Save as"));
        assertTrue(DesktopObject.matches("not_equals", "Save", "Save as"));
        assertFalse(DesktopObject.matches("not_equals", "Save", "SAVE"));
        assertTrue(DesktopObject.matches("contains", "editor", "Text Editor"));
        assertFalse(DesktopObject.matches("contains", "editor", "Text"));
        assertTrue(DesktopObject.matches("starts_with", "Invoice", "invoice 42"));
        assertFalse(DesktopObject.matches("starts_with", "42", "invoice 42"));
        assertTrue(DesktopObject.matches("ends_with", "- notepad", "Untitled - Notepad"));
        assertFalse(DesktopObject.matches("ends_with", "Untitled", "Untitled - Notepad"));
        assertTrue(DesktopObject.matches("regex", "^Invoice \\d+$", "Invoice 42"));
        assertFalse(DesktopObject.matches("regex", "^Invoice \\d+$", "invoice 42"), "regex is used as written");
        assertTrue(DesktopObject.matches("regex", "(?i)^invoice", "Invoice 42"));
        assertTrue(DesktopObject.matches("regex", "\\d{3}", "ref 12345 end"), "regex may match anywhere");
        assertTrue(DesktopObject.matches("wildcard", "* - Notepad", "Untitled - notepad"));
        assertTrue(DesktopObject.matches("wildcard", "Row ?", "Row 7"));
        assertFalse(DesktopObject.matches("wildcard", "Row ?", "Row 17"), "wildcards match the whole value");
        assertTrue(DesktopObject.matches("wildcard", "a.b(c)", "A.B(C)"), "regex characters are literal in wildcards");
        assertTrue(DesktopObject.matches("equals", "", ""));
    }

    @Test void parseAndMatchRules() {
        DesktopObject o = DesktopObject.parse(notepadEdit());
        assertEquals(2, o.primary().size(), "only properties with use: true take part");
        assertTrue(DesktopObject.matchesAll(o.primary(), Map.of("ControlType", "Edit", "Name", "Text editor", "AutomationId", "99")));
        assertFalse(DesktopObject.matchesAll(o.primary(), Map.of("ControlType", "Document", "Name", "Text editor")));
        assertTrue(o.window().test("Untitled - Notepad", "Notepad.exe", "Notepad"));
        assertFalse(o.window().test("Untitled - Notepad", "wordpad", "x"), "process must match");
        assertFalse(o.window().test("*Untitled - Notepad", "notepad", "x"));
    }

    @Test void validation() {
        Map<String, Object> o = notepadEdit();
        o.put("properties", List.of(prop("Name", "x", "fuzzy", true)));
        assertEquals("DESKTOP_UI.BAD_OBJECT", assertThrows(ActionException.class, () -> DesktopObject.parse(o)).code());
        o.put("properties", List.of(prop("Name", "([", "regex", true)));
        assertEquals("DESKTOP_UI.BAD_OBJECT", assertThrows(ActionException.class, () -> DesktopObject.parse(o)).code());
        o.put("properties", List.of(prop("Name", "x", "equals", false)));
        assertTrue(assertThrows(ActionException.class, () -> DesktopObject.parse(o)).getMessage().contains("use: true"));
        o.put("properties", List.of(prop("Name", "x", "equals", true)));
        o.put("index", -1);
        assertThrows(ActionException.class, () -> DesktopObject.parse(o));
        o.put("index", 0);
        o.put("technology", "web");
        assertThrows(ActionException.class, () -> DesktopObject.parse(o));
        assertThrows(ActionException.class, () -> DesktopObject.parse(Map.of()));
    }

    @Test void indexPicksAmongMatches() {
        List<String> m = List.of("a", "b", "c");
        assertEquals("a", DesktopObject.pick(m, 0, false).orElseThrow());
        assertEquals("c", DesktopObject.pick(m, 2, false).orElseThrow());
        assertTrue(DesktopObject.pick(m, 3, false).isEmpty());
        assertTrue(DesktopObject.pick(List.of(), 0, false).isEmpty());
        assertEquals("only", DesktopObject.pick(List.of("only"), 2, true).orElseThrow(), "a healing candidate with one match takes it");
        assertTrue(DesktopObject.pick(List.of("only"), 2, false).isEmpty());
        Map<String, Object> o = notepadEdit();
        o.put("index", new BigDecimal("2"));
        assertEquals(2, DesktopObject.parse(o).index());
    }

    @Test void selfHealingOrder() {
        Map<String, Object> o = notepadEdit();
        o.put("properties", List.of(prop("ControlType", "Button", "equals", true), prop("Name", "Save", "contains", true),
                prop("HelpText", "Saves the file", "equals", false), prop("ClassName", "Button", "equals", false),
                prop("AutomationId", "btnSave", "equals", false), prop("Value", "", "equals", false)));
        List<SelfHealing.Candidate> chain = DesktopObject.parse(o).chain();
        assertEquals(List.of("properties", "automation_id", "name", "class_name", "help_text", "control_type"), chain.stream().map(SelfHealing.Candidate::strategy).toList());
        List<DesktopObject.Rule> aid = DesktopObject.decode(chain.get(1).query());
        assertEquals(List.of(new DesktopObject.Rule("ControlType", "Button", "equals"), new DesktopObject.Rule("AutomationId", "btnSave", "equals")), aid);
        assertEquals(new DesktopObject.Rule("Name", "Save", "equals"), DesktopObject.decode(chain.get(2).query()).get(1), "healing uses the recorded value exactly");
        assertEquals(List.of(new DesktopObject.Rule("ControlType", "Button", "equals")), DesktopObject.decode(chain.get(5).query()));
        // blank recorded values are skipped, and a candidate equal to the primary one is not repeated
        o.put("properties", List.of(prop("ControlType", "Edit", "equals", true), prop("AutomationId", "", "equals", false)));
        assertEquals(List.of("properties"), DesktopObject.parse(o).chain().stream().map(SelfHealing.Candidate::strategy).toList());
    }

    @SuppressWarnings("unchecked")
    @Test void captureDefaults() {
        Map<String, Object> el = new LinkedHashMap<>();
        el.put("Name", "Text editor"); el.put("AutomationId", "15"); el.put("ClassName", "Edit"); el.put("ControlType", "Edit");
        el.put("IsEnabled", "true"); el.put("BoundingRectangle", "10,20,300,200"); el.put("RuntimeId", "42.1.2");
        Map<String, Object> raw = Map.of("element", el, "window", Map.of("title", "Untitled - Notepad", "process", "notepad", "handle", 5),
                "path", List.of(Map.of("ControlType", "Window", "Name", "Untitled - Notepad")), "screenshot", "iVBOR");
        Map<String, Object> obj = DesktopObject.fromCapture(raw);
        assertEquals("desktop", obj.get("technology"));
        assertEquals(Map.of("title", "Untitled - Notepad", "title_match", "wildcard", "process", "notepad"), obj.get("window"));
        List<Map<String, Object>> props = (List<Map<String, Object>>) obj.get("properties");
        assertEquals(DesktopObject.PROPERTIES.stream().filter(n -> DesktopObject.CORE.contains(n)).toList(), props.stream().map(p -> p.get("name")).toList(),
                "only the core properties when the capture has no details");
        Map<String, Boolean> use = new LinkedHashMap<>();
        for (Map<String, Object> p : props) { use.put((String) p.get("name"), (Boolean) p.get("use")); assertEquals("equals", p.get("match")); }
        assertTrue(use.get("ControlType"));
        assertTrue(use.get("AutomationId"));
        assertFalse(use.get("Name"), "Name is not needed when a stable AutomationId exists");
        assertFalse(use.get("BoundingRectangle"));
        assertEquals("iVBOR", obj.get("screenshot"));
        assertEquals(BigDecimal.ZERO, obj.get("index"));
        DesktopObject.parse(obj);   // the captured format is valid input
        el.put("AutomationId", "4523781");   // looks generated
        List<Map<String, Object>> p2 = (List<Map<String, Object>>) DesktopObject.fromCapture(raw).get("properties");
        assertTrue(p2.stream().anyMatch(p -> p.get("name").equals("Name") && Boolean.TRUE.equals(p.get("use"))));
        assertTrue(p2.stream().anyMatch(p -> p.get("name").equals("AutomationId") && Boolean.FALSE.equals(p.get("use"))));

        // detailed captures list path, XPath, states and text after the identity properties; the program path and size are kept
        el.put("Path", "1/1"); el.put("XPath", "/Pane[1]/Document[@Name='Text editor']"); el.put("States", "Focusable,Focused"); el.put("Text", "hello");
        Map<String, Object> raw2 = Map.of("element", el, "window", Map.of("title", "Untitled - Notepad", "process", "notepad", "path", "C:\\Apps\\notepad.exe", "width", 1440, "height", 740));
        Map<String, Object> o2 = DesktopObject.fromCapture(raw2);
        List<Object> names = ((List<Map<String, Object>>) o2.get("properties")).stream().map(p -> p.get("name")).toList();
        assertTrue(names.indexOf("Text") > names.indexOf("ControlType") && names.indexOf("XPath") > names.indexOf("Path"), names.toString());
        assertEquals("C:\\Apps\\notepad.exe", ((Map<String, Object>) o2.get("window")).get("path"));
        assertEquals(Map.of("width", 1440, "height", 740), ((Map<String, Object>) o2.get("window")).get("size"));
    }

    @Test void detailAnchorAndResize() {
        assertFalse(DesktopObject.needsDetail(List.of(new DesktopObject.Rule("ControlType", "Edit", "equals"), new DesktopObject.Rule("automationid", "x", "equals"))));
        assertTrue(DesktopObject.needsDetail(List.of(new DesktopObject.Rule("XPath", "/Pane[1]", "equals"))));
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("technology", "desktop");
        o.put("window", Map.of("title", "App*", "resize", Map.of("width", 1200, "height", 800)));
        o.put("properties", List.of(prop("ControlType", "Edit", "equals", true)));
        o.put("anchor", Map.of("properties", List.of(prop("Name", "User name", "equals", true)), "offset", Map.of("dx", 120, "dy", 0)));
        DesktopObject d = DesktopObject.parse(o);
        assertEquals(1200, d.resizeWidth);
        assertEquals("App*", d.anchor().window().title(), "the anchor is looked for in the same window");
        assertEquals(120.0, d.anchorDx);
        List<Map<String, ?>> cands = List.of(Map.of("BoundingRectangle", "10,10,50,20"), Map.of("BoundingRectangle", "130,12,50,20"), Map.of());
        assertEquals(1, DesktopObject.nearest(cands, 125, 10, 250));
        assertEquals(-1, DesktopObject.nearest(cands, 900, 900, 250));
        o.put("window", Map.of("resize", Map.of("width", 0, "height", 5)));
        assertThrows(com.oneofficeautomation.sdk.ActionException.class, () -> DesktopObject.parse(o));
    }

    @Test void tableColumnsAreUniqueAndNamed() {
        Map<String, Object> t = DesktopUiPackage.tableOf(List.of("Name", "", "name", "Size"), List.of(List.of("a", "b", "c", "d")));
        assertEquals(List.of("Name", "Column2", "name_2", "Size"), t.get("columns"));
    }
}
