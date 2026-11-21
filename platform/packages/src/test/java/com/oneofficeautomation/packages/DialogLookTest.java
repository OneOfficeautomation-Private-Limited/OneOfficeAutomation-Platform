package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.Input;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JTextArea;
import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.KeyboardFocusManager;
import java.awt.event.KeyEvent;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The attended dialogs' shared look (design A, 21 Nov 2026): layout rules, titles, buttons, keys, new-step defaults. */
class DialogLookTest {
    @TempDir Path tmp;

    @Test void colourOfTheTopEdgeAndIcon() {
        assertEquals(new Color(0x2f6fde), OoaDialog.toneColor("information"));
        assertEquals(new Color(0x2f6fde), OoaDialog.toneColor("question"));
        assertEquals(new Color(0xb45309), OoaDialog.toneColor("warning"));
        assertEquals(new Color(0xb42318), OoaDialog.toneColor("error"));
        assertEquals(new Color(0x4b5565), OoaDialog.toneColor("none"));
        assertEquals("i", OoaDialog.glyph("information"));
        assertEquals("?", OoaDialog.glyph("question"));
        assertEquals("!", OoaDialog.glyph("warning"));
        assertEquals("", OoaDialog.glyph("none"));
    }

    @Test void widthGrowsWithTheTextBetween360And560() {
        assertEquals(360, OoaDialog.cardWidth(0));
        assertEquals(360, OoaDialog.cardWidth(200));
        assertEquals(444, OoaDialog.cardWidth(400));
        assertEquals(560, OoaDialog.cardWidth(5000));
    }

    @Test void windowTitleIsTheAgentNameUnlessSet() {
        assertEquals("Invoice run", OoaDialog.windowTitle(null, "Invoice run", null));
        assertEquals("Invoice run", OoaDialog.windowTitle("  ", "Invoice run", null));
        assertEquals("Invoice run", OoaDialog.windowTitle("ONEOFFICEAUTOMATION", "Invoice run", null));   // the old default
        assertEquals("Check totals", OoaDialog.windowTitle("Check totals", "Invoice run", null));
        assertEquals("OneOfficeAutomation", OoaDialog.windowTitle(null, "", null));
        // a picker's own default title ("Choose a file") is its heading; the window shows the agent's name
        assertEquals("Invoice run", OoaDialog.windowTitle("Choose a file", "Invoice run", "Choose a file"));
        assertEquals("Choose a file", OoaDialog.windowTitle("Choose a file", "", "Choose a file"));
        assertEquals("Choose a file", OoaDialog.heading("Choose a file", "Invoice run"));
        assertEquals("Invoice run", OoaDialog.heading("ONEOFFICEAUTOMATION", "Invoice run"));
        assertEquals("OneOfficeAutomation", OoaDialog.heading(null, null));
    }

    @Test void buttonsMainFirstAndWhatEscMeans() {
        assertEquals(List.of("ok"), OoaDialog.answers("ok"));
        assertEquals(List.of("ok", "cancel"), OoaDialog.answers("ok_cancel"));
        assertEquals(List.of("yes", "no"), OoaDialog.answers("yes_no"));
        assertEquals(List.of("yes", "no", "cancel"), OoaDialog.answers("yes_no_cancel"));
        assertEquals("closed", OoaDialog.escapeAnswer("ok"));
        assertEquals("cancel", OoaDialog.escapeAnswer("ok_cancel"));
        assertEquals("no", OoaDialog.escapeAnswer("yes_no"));
        assertEquals("cancel", OoaDialog.escapeAnswer("yes_no_cancel"));
        assertEquals("OK", OoaDialog.label("ok"));
        assertEquals("Choose", OoaDialog.label("choose"));
    }

    @Test void countdownAndScrolling() {
        assertEquals("Closes in 5 s", OoaDialog.countdown(5));
        assertEquals("Closes in 0 s", OoaDialog.countdown(-2));
        assertEquals(0, OoaDialog.lines(""));
        assertEquals(3, OoaDialog.lines("a\r\nb\nc"));
        assertTrue(OoaDialog.scrolls("x\n".repeat(31), 30));
        assertFalse(OoaDialog.scrolls("x\n".repeat(29), 30));
        assertFalse(OoaDialog.scrolls("x\n".repeat(99), 0));
    }

    @Test void messageIsSelectableButNotEditableAndEnterIsLeftToTheMainButton() {
        JTextArea a = OoaDialog.messageArea("Line one\nLine two", new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        assertFalse(a.isEditable());
        assertTrue(a.isFocusable());
        assertTrue(a.getLineWrap());
        assertEquals("none", a.getInputMap().get(javax.swing.KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)));
        assertNotNull(a.getComponentPopupMenu());
        assertFalse(a.areFocusTraversalKeysSet(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS));   // Tab moves on to the buttons
    }

    @Test void newStepsScrollAfter30LinesAndChooseSeveralFiles() throws Exception {
        assertEquals("30", input("messageBox", "scroll_after_lines").newStepDefault());
        assertEquals("0", input("messageBox", "scroll_after_lines").defaultValue());   // saved agents keep their behaviour
        assertEquals("", input("messageBox", "title").defaultValue());               // empty: the agent's name
        assertEquals("true", input("promptFile", "multiple").newStepDefault());
        assertEquals("false", input("promptFile", "multiple").defaultValue());
    }

    /** With a desktop, the message box really opens in the new look and closes by itself. */
    @Test void messageBoxClosesByItself() {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "needs a desktop");
        ActionContext c = PackagesTestSupport.ctx(tmp, Map.of());
        Map<String, Object> r = new DesktopPackage().messageBox(c, "Closing by itself.\nSecond line.", "", 1, "ok_cancel", "warning", 30, true);
        assertEquals("timeout", r.get("button"));
    }

    private static Input input(String method, String name) {
        for (Method m : DesktopPackage.class.getMethods()) {
            if (!m.getName().equals(method)) continue;
            for (Parameter p : m.getParameters()) {
                Input in = p.getAnnotation(Input.class);
                if (in != null && in.name().equals(name)) return in;
            }
        }
        throw new AssertionError("no input " + name + " on " + method);
    }
}
