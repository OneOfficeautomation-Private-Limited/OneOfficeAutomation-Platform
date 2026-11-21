package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.FlowVariables;
import com.oneofficeautomation.sdk.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;

import static com.oneofficeautomation.packages.PackagesTest.tmp;
import static org.junit.jupiter.api.Assertions.*;

/**
 * G1 (AA parity, 21 Nov 2026), the rest of the flow and text rows: Script succeeded; Extract text with both markers
 * and an exact length; Set item gives what it replaced; a record's key put at or removed from a position; Hide and
 * Show form; Close transaction with a status of your own and every variable; Fill a template from the agent's own
 * variables (never credentials).
 */
class CoreParityG1MoreTest {
    static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    static ActionContext ctxWith(Path w, Map<String, String> declared, Map<String, Object> values) {
        FlowVariables fv = new FlowVariables() {
            public Map<String, String> declared() { return declared; }
            public Object value(String name) { return values.get(name); }
        };
        return PackagesTestSupport.ctx(w, Map.of(FlowVariables.SERVICE, fv));
    }

    @Test void aScriptSucceedsWhenItEndsWithExitCodeZero() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(WINDOWS, "PowerShell is on every Windows runner");
        ScriptPackage sp = new ScriptPackage();
        var ctx = PackagesTestSupport.ctx(tmp(), Map.of());
        assertTrue(sp.scriptSucceeded(ctx, "powershell", "exit 0", null, null, 60));
        assertFalse(sp.scriptSucceeded(ctx, "powershell", "exit 3", null, null, 60));
        assertEquals("SCRIPT.BAD_LANGUAGE", assertThrows(ActionException.class, () -> sp.scriptSucceeded(ctx, "perl", "x", null, null, 60)).code());
    }

    @Test void extractTextWithBothMarkersOrAnExactLength() throws Exception {
        StringPackage s = new StringPackage();
        var ctx = PackagesTestSupport.ctx(tmp(), Map.of());
        String text = "Invoice: INV-1043 due";
        // either marker found is enough (as before): no "Total" before marker, the text runs to the end
        assertEquals("INV-1043 due", s.extractText(ctx, text, "Total", "Invoice:", true, 1, 1, false, "empty", null, false, "end_of_text", "either", "up_to").get("result"));
        assertEquals("", s.extractText(ctx, text, "Total", "Invoice:", true, 1, 1, false, "empty", null, false, "end_of_text", "both", "up_to").get("result"), "both must be found");
        assertEquals("INV-1043", s.extractText(ctx, text, " due", "Invoice:", true, 1, 1, false, "empty", null, false, "marker", "both", "up_to").get("result"));
        assertEquals("INV-1", s.extractText(ctx, text, " due", "Invoice:", true, 1, 1, false, "empty", 5, false, "marker", "either", "exactly").get("result"));
        assertEquals("", s.extractText(ctx, text, " due", "Invoice:", true, 1, 1, false, "empty", 20, false, "marker", "either", "exactly").get("result"), "too short for exactly 20");
        assertEquals(text.substring(0, 20), s.extractText(ctx, text, " due", "Invoice:", true, 1, 1, false, "source", 20, false, "marker", "either", "exactly").get("result"),
                "the whole text when not found, cut to the length as before");
    }

    @Test void setItemGivesTheItemItReplaced() throws Exception {
        Map<String, Object> r = new ListPackage().setItem(PackagesTestSupport.ctx(tmp(), Map.of()), List.of("a", "b", "c"), 1, "B");
        assertEquals(List.of("a", "B", "c"), r.get("list"));
        assertEquals("b", r.get("previous"));
    }

    @SuppressWarnings("unchecked")
    @Test void aRecordsKeyAtAPosition() throws Exception {
        DictionaryPackage d = new DictionaryPackage();
        var ctx = PackagesTestSupport.ctx(tmp(), Map.of());
        Map<String, Object> rec = new LinkedHashMap<>(); rec.put("id", 1); rec.put("name", "Acme");
        Map<String, Object> put = (Map<String, Object>) d.put(ctx, rec, "region", "EU", false, 1).get("dictionary");
        assertEquals(List.of("id", "region", "name"), new ArrayList<>(put.keySet()));
        Map<String, Object> last = (Map<String, Object>) d.put(ctx, rec, "x", 0, false, 2).get("dictionary");
        assertEquals(List.of("id", "name", "x"), new ArrayList<>(last.keySet()));
        assertEquals("DICT.BAD_POSITION", assertThrows(ActionException.class, () -> d.put(ctx, rec, "y", 0, false, 5)).code());
        Map<String, Object> kept = (Map<String, Object>) d.put(ctx, rec, "name", "Acme Ltd", false, 0).get("dictionary");
        assertEquals(List.of("id", "name"), new ArrayList<>(kept.keySet()), "a key already there keeps its place");

        Map<String, Object> removed = d.remove(ctx, put, null, false, 0);
        assertEquals(1, removed.get("removed"));
        assertEquals(List.of("region", "name"), new ArrayList<>(((Map<String, Object>) removed.get("dictionary")).keySet()));
        assertEquals(false, d.remove(ctx, put, null, false, 9).get("found"));
        assertEquals("DICT.KEY_NOT_FOUND", assertThrows(ActionException.class, () -> d.remove(ctx, put, null, true, 9)).code());
        assertEquals("DICT.NO_KEY", assertThrows(ActionException.class, () -> d.remove(ctx, put, null, false, null)).code());
    }

    @Test void hideAndShowTheWholeForm() throws Exception {
        var ctx = PackagesTestSupport.ctx(tmp(), Map.of());
        FormWindow.Session s = FormWindow.open("order", Map.of("elements", List.of()), "Order entry");
        ctx.sessions().put("form:order", s);
        List<String> calls = new ArrayList<>();
        var real = FormsPackage.minimise;
        try {
            FormsPackage.minimise = (title, min) -> { calls.add((min ? "hide " : "show ") + title); return true; };
            FormsPackage fp = new FormsPackage();
            fp.hideForm(ctx, "order");
            fp.showForm(ctx, "order");
            assertEquals(List.of("hide Order entry", "show Order entry"), calls);
            FormsPackage.minimise = (title, min) -> false;
            assertEquals("FORMS.NO_WINDOW", assertThrows(ActionException.class, () -> fp.hideForm(ctx, "order")).code());
            assertEquals("FORMS.NOT_OPEN", assertThrows(ActionException.class, () -> fp.showForm(ctx, "other")).code());
        } finally { FormsPackage.minimise = real; s.close(); }
    }

    @Test void closeTransactionWithYourOwnStatusAndEveryVariable() throws Exception {
        Path w = tmp();
        var ctx = ctxWith(w, new LinkedHashMap<>(Map.of("invoice", "String", "password", "Credential")),
                Map.of("invoice", "INV-7", "password", new SecureString("s3cret", "vault")));
        InsightsPackage ip = new InsightsPackage();
        ip.openTransaction(ctx, "Invoice", "invoices");
        ip.closeTransaction(ctx, "Invoice", Map.of("amount", 120), "success", "Partially paid", true);
        String line = Files.readString(w.resolve("insights/invoices.jsonl")).strip();
        assertTrue(line.contains("\"outcome\":\"Partially paid\""), line);
        assertTrue(line.contains("\"invoice\":\"INV-7\""), line);
        assertFalse(line.contains("password") || line.contains("s3cret"), "credentials are never recorded: " + line);
    }

    @Test void fillATemplateFromTheAgentsVariables() throws Exception {
        var ctx = ctxWith(tmp(), new LinkedHashMap<>(Map.of("name", "String", "order", "Dictionary", "pin", "Credential")),
                Map.of("name", "Ada", "order", Map.of("total", new BigDecimal("12.50")), "pin", new SecureString("1234", "vault")));
        StringPackage s = new StringPackage();
        Map<String, Object> r = s.fillTemplate(ctx, "Dear $name$, your total is $order.total$ ($F$1 stays). PIN $pin$, {extra}", Map.of("extra", "thanks"), "keep", true);
        assertEquals("Dear Ada, your total is 12.50 ($F$1 stays). PIN $pin$, thanks", r.get("result"));
        assertEquals(List.of("pin"), r.get("missing"));
        assertEquals("STRING.NO_VALUES", assertThrows(ActionException.class, () -> s.fillTemplate(ctx, "x", null, "keep", false)).code());
    }
}
