package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Interactive form options added after the command comparison: assign mode, highlight kind and message, always on top. No window is shown. */
class FormsMoreOptionsTest {
    final FormsPackage forms = new FormsPackage();

    @BeforeAll static void noWindows() { FormWindow.openWindows = false; }

    ActionContext open(String name, boolean onTop) throws Exception {
        Path dir = Files.createTempDirectory("ooa-forms-more");
        ActionContext ctx = PackagesTestSupport.ctx(dir, Map.of());
        forms.display(ctx, FormsPackageTest.form(dir).toString(), name, "", null, "no", 900, true, 560, 640, onTop);
        return ctx;
    }

    static FormWindow.Session session(ActionContext ctx, String name) { return (FormWindow.Session) ctx.sessions().get("form:" + name); }

    @SuppressWarnings("unchecked")
    static Map<String, Object> look(String address, String id) throws Exception {
        return (Map<String, Object>) ((Map<String, Object>) FormsPackageTest.state(address).get("elements")).get(id);
    }

    @Test void assignAppendsOptionsNotAlreadyOffered() throws Exception {
        ActionContext ctx = open("a", false);
        String address = session(ctx, "a").address();
        forms.assign(ctx, "a", "kind", List.of("Sick", "Unpaid", "Study"), null, "append");
        assertEquals(List.of("Holiday", "Sick", "Unpaid", "Study"), look(address, "kind").get("options"), "designer options first, duplicates skipped");
        forms.assign(ctx, "a", "kind", List.of("Study", "Parental"), "Parental", "append");
        assertEquals(List.of("Holiday", "Sick", "Unpaid", "Study", "Parental"), look(address, "kind").get("options"), "appends to what the agent set before");
        assertEquals("Parental", forms.get(ctx, "a", "kind").get("value"));
        forms.assign(ctx, "a", "kind", List.of("Only"), null, "replace");
        assertEquals(List.of("Only"), look(address, "kind").get("options"));
        assertEquals("", forms.get(ctx, "a", "kind").get("value"), "a choice no longer offered is cleared");
        forms.assign(ctx, "a", "kind", List.of("A", "B"), "B");
        assertEquals(List.of("A", "B"), look(address, "kind").get("options"), "the old signature replaces");
        assertEquals("FORMS.BAD_MODE", assertThrows(ActionException.class, () -> forms.assign(ctx, "a", "kind", List.of("x"), null, "merge")).code());
        assertEquals("FORMS.NOT_A_LIST", assertThrows(ActionException.class, () -> forms.assign(ctx, "a", "name", List.of("x"), null, "append")).code());
        forms.close(ctx, "a");
    }

    @Test void highlightKindsAndMessages() throws Exception {
        ActionContext ctx = open("h", false);
        String address = session(ctx, "h").address();
        forms.highlight(ctx, "h", "email", null, "error", "This address bounced");
        Map<String, Object> el = look(address, "email");
        assertEquals(FormsPackage.HIGHLIGHT_ERROR, el.get("highlight"));
        assertEquals("This address bounced", el.get("message"));
        forms.highlight(ctx, "h", "days", null, "warning", null);
        assertEquals(FormsPackage.HIGHLIGHT_WARNING, look(address, "days").get("highlight"));
        assertNull(look(address, "days").get("message"));
        forms.highlight(ctx, "h", "name", "#123abc", "color", "Check the spelling");
        assertEquals("#123abc", look(address, "name").get("highlight"));
        forms.highlight(ctx, "h", "name", "#00ff00");
        assertNull(look(address, "name").get("message"), "the old signature shows no message");
        forms.unhighlight(ctx, "h", "email");
        Map<String, Object> cleared = look(address, "email");
        assertTrue(cleared == null || (cleared.get("highlight") == null && cleared.get("message") == null), "unhighlight removes the message too");
        assertEquals("FORMS.BAD_KIND", assertThrows(ActionException.class, () -> forms.highlight(ctx, "h", "name", null, "info", null)).code());
        assertEquals("FORMS.BAD_COLOR", assertThrows(ActionException.class, () -> forms.highlight(ctx, "h", "name", "red; x", "color", null)).code());
        assertEquals("FORMS.ELEMENT_NOT_FOUND", assertThrows(ActionException.class, () -> forms.highlight(ctx, "h", "nope", null, "error", "x")).code());
        forms.close(ctx, "h");
    }

    @Test void displayCanAskToStayOnTop() throws Exception {
        ActionContext top = open("t", true);
        assertTrue(session(top, "t").alwaysOnTop, "the window is asked to stay on top");
        forms.close(top, "t");
        ActionContext normal = open("n", false);
        assertFalse(session(normal, "n").alwaysOnTop);
        forms.close(normal, "n");
        Path dir = Files.createTempDirectory("ooa-forms-more");
        ActionContext ctx = PackagesTestSupport.ctx(dir, Map.of());
        forms.display(ctx, FormsPackageTest.form(dir).toString(), "old", "", null, "no", 900, true, 560, 640);
        assertFalse(session(ctx, "old").alwaysOnTop, "the old signature is not on top");
        forms.close(ctx, "old");
        assertEquals("FILE.NOT_FOUND", assertThrows(ActionException.class,
                () -> forms.display(ctx, dir.resolve("missing.form.json").toString(), "m", "", null, "no", 900, true, 560, 640, true)).code());
        assertTrue(FormWindow.TOPMOST_SCRIPT.contains("$env:OOA_FORM_TITLE"), "the title reaches PowerShell through the environment, not the script text");
    }
}
