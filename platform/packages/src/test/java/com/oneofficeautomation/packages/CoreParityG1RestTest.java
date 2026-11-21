package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

import static com.oneofficeautomation.packages.ConnectorsTest.*;
import static com.oneofficeautomation.packages.PackagesTest.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * G1 (AA parity, 21 Nov 2026), the last rows: date conditions take a date, a variable or text in a pattern; form fields
 * append text and take a vault credential; options come as comma text; dynamic areas append, clear and get a height;
 * Currency's json source takes a past day; new steps of Message box and Prompt for file start as AA's; Run Agent shows
 * the wait between runs behind a tick.
 */
class CoreParityG1RestTest {
    static ActionContext ctx() throws Exception { return PackagesTestSupport.ctx(tmp(), Map.of()); }

    @Test void dateConditionsReadTextInAPattern() throws Exception {
        DateTimePackage d = new DateTimePackage();
        var c = ctx();
        assertTrue(d.isAfter(c, "21/11/2026", "20/11/2026", true, "UTC", "dd/MM/yyyy"));
        assertFalse(d.isBefore(c, "21/11/2026", "2026-11-20", true, "UTC", "dd/MM/yyyy"), "ISO text is read as such even with a pattern");
        assertTrue(d.isEqual(c, LocalDateTime.of(2026, 11, 21, 9, 30), "21/11/2026", false, "UTC", "dd/MM/yyyy"), "a Date variable and typed text, the date only");
        assertTrue(d.isBetween(c, "2026-11-21", "2026-11-01", "2026-11-30", true, "UTC", null), "ISO text needs no pattern");
        assertTrue(d.isAfter(c, "21/11/2026 14:30", "21/11/2026 09:00", true, "UTC", "dd/MM/yyyy HH:mm"));
        assertEquals("DATETIME.PARSE_FAILED", assertThrows(ActionException.class, () -> d.isAfter(c, "31/02/2026", "2026-01-01", true, "UTC", "dd/MM/yyyy")).code());
        assertEquals("DATETIME.MISSING", assertThrows(ActionException.class, () -> d.isAfter(c, "", "2026-01-01", true, "UTC", null)).code());
        assertTrue(d.isAfter(c, LocalDateTime.of(2026, 1, 2, 0, 0), LocalDateTime.of(2026, 1, 1, 0, 0), true, "UTC"), "the actions' own form still works");
    }

    @SuppressWarnings("unchecked")
    @Test void formFieldsAppendTakeCredentialsAndAreasGrow() throws Exception {
        var c = ctx();
        Map<String, Object> form = new LinkedHashMap<>(Map.of("fields", new ArrayList<>(List.of(
                new LinkedHashMap<>(Map.of("id", "note", "type", "text")),
                new LinkedHashMap<>(Map.of("id", "pin", "type", "password")),
                new LinkedHashMap<>(Map.of("id", "kind", "type", "dropdown")),
                new LinkedHashMap<>(Map.of("id", "lines", "type", "area", "fields", new ArrayList<>()))))));
        FormWindow.Session s = FormWindow.open("f", form, "Form");
        c.sessions().put("form:f", s);
        try {
            FormsPackage fp = new FormsPackage();
            fp.set(c, "f", "note", "Paid", "replace");
            fp.set(c, "f", "note", " in full", "append");
            assertEquals("Paid in full", s.values.get("note"));
            fp.set(c, "f", "pin", new SecureString("4321", "vault"), "replace");
            assertEquals("4321", s.values.get("pin"), "the field gets the secret; the log never does");
            assertEquals("FORMS.BAD_MODE", assertThrows(ActionException.class, () -> fp.set(c, "f", "note", "x", "prepend")).code());

            fp.assign(c, "f", "kind", "Invoice, Credit note , ,Receipt", null, "replace");
            assertEquals(List.of("Invoice", "Credit note", "Receipt"), s.elements.get("kind").get("options"));

            assertEquals(new BigDecimal(1), fp.dynamicArea(c, "f", "lines", List.of(Map.of("id", "l1", "read_only", true, "tooltip", "From the ERP")), "replace", 120).get("count"));
            assertEquals(new BigDecimal(2), fp.dynamicArea(c, "f", "lines", List.of(Map.of("id", "l2")), "append", null).get("count"));
            assertEquals(120, s.elements.get("lines").get("height"));
            assertEquals("FORMS.BAD_FIELD", assertThrows(ActionException.class, () -> fp.dynamicArea(c, "f", "lines", List.of(Map.of("id", "l1")), "append", null)).code(), "ids stay unique");
            assertEquals(new BigDecimal(0), fp.dynamicArea(c, "f", "lines", null, "clear", null).get("count"));
            assertFalse(s.values.containsKey("l1"));
        } finally { s.close(); }
    }

    @Test void jsonRatesOfAPastDay() throws Exception {
        try (Fake f = new Fake()) {
            f.on("GET /2026-10-22", x -> ok("{\"base\":\"EUR\",\"rates\":{\"USD\":1.1}}"));
            CurrencyPackage cur = new CurrencyPackage();
            var c = ctx();
            Map<String, Object> r = cur.convert(c, new BigDecimal("10"), "EUR", "USD", 2, "json", f.url() + "/{date}", "rates", null, null, "2026-10-22");
            eqNum("11.00", r.get("amount"));
            assertEquals("CURRENCY.DATE_NOT_SUPPORTED", assertThrows(ActionException.class,
                    () -> cur.convert(c, BigDecimal.ONE, "EUR", "USD", 2, "json", f.url() + "/latest", "rates", null, null, "2026-10-22")).code(), "the url needs {date}");
        }
    }

    @SuppressWarnings("unchecked")
    @Test void newStepsStartAsAAsAndRunAgentHasATick() {
        var registry = FirstPartyPackages.registry();
        Map<String, Object> desktop = registry.allManifests().stream().filter(m -> "ooa.desktop".equals(m.get("id"))).findFirst().orElseThrow();
        Map<String, Object> mb = action(desktop, "message_box"), pf = action(desktop, "prompt_file");
        assertEquals(30, ((Number) prop(mb, "scroll_after_lines").get("x-ooa-new-step-default")).intValue());
        assertEquals(true, prop(pf, "multiple").get("x-ooa-new-step-default"));
        Map<String, Object> core = registry.allManifests().stream().filter(m -> "core".equals(m.get("id"))).findFirst().orElseThrow();
        assertTrue(String.valueOf(action(core, "run_bot").get("x-ooa-panel")).contains("Wait between runs"), String.valueOf(action(core, "run_bot").get("x-ooa-panel")));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> action(Map<String, Object> manifest, String id) {
        return ((List<Map<String, Object>>) manifest.get("actions")).stream().filter(a -> id.equals(a.get("id"))).findFirst().orElseThrow();
    }
    @SuppressWarnings("unchecked")
    static Map<String, Object> prop(Map<String, Object> action, String name) {
        return (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) action.get("inputs")).get("properties")).get(name);
    }
}
