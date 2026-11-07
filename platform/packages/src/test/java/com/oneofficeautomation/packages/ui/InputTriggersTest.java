package com.oneofficeautomation.packages.ui;

import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Key-press and click triggers with a fake native layer: settings, window scope, element match, debounce, privacy. */
public class InputTriggersTest {
    /** Fake hooks: tests call press(...) and click(...) as the operating system would. */
    public static final class FakeNative implements InputTriggers.Native {
        final List<InputTriggers.KeyChord> chords = new ArrayList<>();
        public volatile Consumer<InputTriggers.Window> key;
        public volatile Consumer<InputTriggers.Click> mouse;
        public final Map<String, Map<String, String>> elements = new HashMap<>();
        public volatile int lookupsOpened, lookupsClosed, hooksClosed;
        boolean lookupFails;

        public AutoCloseable onKeyChord(InputTriggers.KeyChord chord, Consumer<InputTriggers.Window> onPress) {
            chords.add(chord); key = onPress;
            return () -> { key = null; hooksClosed++; };
        }
        public AutoCloseable onMouseDown(Consumer<InputTriggers.Click> onClick) { mouse = onClick; return () -> { mouse = null; hooksClosed++; }; }
        public InputTriggers.ElementLookup openElementLookup() {
            if (lookupFails) throw new IllegalStateException("UI Automation is not available");
            lookupsOpened++;
            return new InputTriggers.ElementLookup() {
                public Map<String, String> at(int x, int y) { return elements.getOrDefault(x + "," + y, Map.of()); }
                public void close() { lookupsClosed++; }
            };
        }
        public void press(String title, String process) { if (key != null) key.accept(new InputTriggers.Window(title, process)); }
        public void click(String button, int x, int y, String title, String process) { if (mouse != null) mouse.accept(new InputTriggers.Click(button, x, y, new InputTriggers.Window(title, process))); }
    }

    long now = 0;
    final List<Map<String, Object>> events = new ArrayList<>();

    @Test void keyCombinationsAreNamedKeysAndNeverPlainTyping() {
        assertEquals("CTRL+ENTER", InputTriggers.KeyChord.parse("ctrl + return").text());
        assertEquals("SHIFT+F2", InputTriggers.KeyChord.parse("Shift+F2").text());
        assertEquals("CTRL+ALT+SHIFT+WIN+PAGE_UP", InputTriggers.KeyChord.parse("win+shift+alt+control+PgUp").text(), "modifiers in one order");
        assertEquals("F9", InputTriggers.KeyChord.parse("F9").text());
        assertEquals("ENTER", InputTriggers.KeyChord.parse("Enter").text(), "named non-typing keys may be alone");
        assertEquals("CTRL+S", InputTriggers.KeyChord.parse("ctrl+s").text());
        InputTriggers.KeyChord c = InputTriggers.KeyChord.parse("CTRL+ENTER");
        assertTrue(c.matches(0x0D, InputTriggers.CTRL));
        assertFalse(c.matches(0x0D, InputTriggers.CTRL | InputTriggers.SHIFT), "exact modifiers");
        assertFalse(c.matches(0x41, InputTriggers.CTRL));
        for (String bad : List.of("A", "SHIFT+A", "7", "SPACE", "SHIFT+SPACE", "BACKSPACE", "NUMPAD5", "CTRL", "CTRL+A+B", "CTRL+FOO", "", "CTRL+ALT"))
            assertThrows(IllegalArgumentException.class, () -> InputTriggers.KeyChord.parse(bad), bad);
    }

    @Test void settingsAreCheckedAndNormalised() {
        Map<String, Object> k = InputTriggers.keyConfig(Map.of("key", "ctrl+enter", "window", "*Invoice*", "process", "erp.exe"));
        assertEquals(Map.of("key", "CTRL+ENTER", "window", "*Invoice*", "process", "erp.exe", "debounce_ms", 500), k);
        assertThrows(IllegalArgumentException.class, () -> InputTriggers.keyConfig(Map.of("key", "F9", "debounce_ms", -1)));
        assertThrows(IllegalArgumentException.class, () -> InputTriggers.keyConfig(Map.of("key", "F9", "window", "re:(")));
        Map<String, Object> c = InputTriggers.clickConfig(Map.of("window", "ERP", "element_type", "Button", "debounce_ms", "250"));
        assertEquals(Map.of("button", "left", "window", "ERP", "element_type", "Button", "debounce_ms", 250), c);
        assertThrows(IllegalArgumentException.class, () -> InputTriggers.clickConfig(Map.of("element_name", "OK")), "a window or process is needed");
        assertThrows(IllegalArgumentException.class, () -> InputTriggers.clickConfig(Map.of("window", "ERP", "button", "x1")));
        assertThrows(IllegalArgumentException.class, () -> InputTriggers.clickConfig(Map.of("process", "C:\\erp.exe")));
        assertEquals("key CTRL+ENTER in window '*Invoice*' of erp.exe", InputTriggers.describe("key_pressed", Map.of("key", "ctrl+enter", "window", "*Invoice*", "process", "erp.exe")));
        assertEquals("left click in erp on Button 'Submit'", InputTriggers.describe("mouse_clicked", Map.of("process", "erp", "element_type", "Button", "element_name", "Submit")));
    }

    @Test void keyPressFiresOnlyInTheNamedWindowWithDebounceAndCarriesNoText() throws Exception {
        FakeNative n = new FakeNative();
        AutoCloseable h = InputTriggers.watchKey(n, Map.of("key", "CTRL+ENTER", "window", "*Invoice*", "process", "erp", "debounce_ms", 1000), () -> now, events::add);
        assertEquals("CTRL+ENTER", n.chords.get(0).text(), "only the configured combination is handed to the hook");
        n.press("Invoice 42 - ERP", "erp.exe");
        n.press("Invoice 42 - ERP", "erp.exe");            // same moment: debounced
        n.press("Mail - Outlook", "outlook.exe");          // another window
        n.press("Invoice 42 - Notepad", "notepad.exe");    // right title, other process
        now = 1500;
        n.press("Invoice 43 - ERP", "ERP.EXE");
        assertEquals(List.of(Map.of("key", "CTRL+ENTER", "window", "Invoice 42 - ERP", "process", "erp.exe"),
                Map.of("key", "CTRL+ENTER", "window", "Invoice 43 - ERP", "process", "ERP.EXE")), events);
        for (Map<String, Object> e : events) assertEquals(Set.of("key", "window", "process"), e.keySet(), "the event says which key and window, nothing typed");
        h.close();
        assertEquals(1, n.hooksClosed, "unhooked on stop");
        n.press("Invoice 44 - ERP", "erp.exe");
        assertEquals(2, events.size());
    }

    @Test void keyPressWithoutAWindowFiresAnywhereAndZeroDebounceFiresEachTime() throws Exception {
        FakeNative n = new FakeNative();
        InputTriggers.watchKey(n, Map.of("key", "F9", "debounce_ms", 0), () -> now, events::add);
        n.press("A", "a.exe");
        n.press("B", "b.exe");
        assertEquals(2, events.size());
    }

    @Test void clickFiresOnTheCapturedElementOnly() throws Exception {
        FakeNative n = new FakeNative();
        n.elements.put("10,10", Map.of("Name", "Submit", "ControlType", "Button", "AutomationId", "btnSubmit", "Value", "secret text"));
        n.elements.put("20,20", Map.of("Name", "Cancel", "ControlType", "Button", "AutomationId", "btnCancel"));
        n.elements.put("30,30", Map.of("Name", "Password", "ControlType", "Edit", "AutomationId", "pw", "IsPassword", "true"));
        AutoCloseable h = InputTriggers.watchClick(n, Map.of("window", "ERP*", "element_name", "Sub*", "element_type", "button"), () -> now, events::add);
        assertEquals(1, n.lookupsOpened, "with an element set, the reader starts straight away");
        n.click("left", 10, 10, "ERP - Orders", "erp.exe");
        now += 1000;
        n.click("left", 20, 20, "ERP - Orders", "erp.exe");    // other element
        n.click("right", 10, 10, "ERP - Orders", "erp.exe");   // other button
        n.click("left", 10, 10, "Mail", "outlook.exe");        // other window
        assertEquals(List.of(Map.of("button", "left", "window", "ERP - Orders", "process", "erp.exe", "element_name", "Submit", "element_type", "Button", "automation_id", "btnSubmit")), events,
                "name, type and id of the element; never its value");
        h.close();
        assertEquals(1, n.lookupsClosed);
        assertEquals(1, n.hooksClosed);
    }

    @Test void clickInAWindowReportsTheElementButNeverAPasswordFieldsName() throws Exception {
        FakeNative n = new FakeNative();
        n.elements.put("30,30", Map.of("Name", "hunter2-label", "ControlType", "Edit", "AutomationId", "pw", "IsPassword", "true"));
        InputTriggers.watchClick(n, Map.of("process", "erp.exe", "button", "any", "debounce_ms", 0), () -> now, events::add);
        assertEquals(0, n.lookupsOpened, "without an element the reader waits for the first click in the window");
        n.click("left", 99, 99, "Other", "other.exe");
        assertEquals(0, n.lookupsOpened);
        n.click("right", 30, 30, "ERP", "erp.exe");
        assertEquals(1, n.lookupsOpened);
        assertEquals("", events.get(0).get("element_name"));
        assertEquals("Edit", events.get(0).get("element_type"));
        assertEquals("right", events.get(0).get("button"));
    }

    @Test void clickWithoutUiAutomation() throws Exception {
        FakeNative n = new FakeNative();
        n.lookupFails = true;
        assertThrows(IllegalStateException.class, () -> InputTriggers.watchClick(n, Map.of("window", "ERP", "element_name", "OK"), () -> now, events::add),
                "an element cannot be checked without UI Automation: said at once");
        InputTriggers.watchClick(n, Map.of("window", "ERP"), () -> now, events::add);
        n.click("left", 1, 1, "ERP", "erp.exe");
        assertEquals(1, events.size(), "a window-only click still fires, without element details");
        assertEquals("", events.get(0).get("element_type"));
    }

    @Test void noNativeLayerMeansWindowsOnly() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> InputTriggers.watchKey(null, Map.of("key", "F9"), () -> 0, m -> { }));
        assertTrue(e.getMessage().contains("Windows"));
    }
}
