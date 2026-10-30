package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.*;

import static com.oneofficeautomation.packages.ConnectorsTest.*;
import static com.oneofficeautomation.packages.PackagesTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Fuzzy Match and Currency toolkits. */
class FuzzyCurrencyTest {
    final FuzzyPackage fz = new FuzzyPackage();
    final CurrencyPackage cur = new CurrencyPackage();

    static ActionContext ctx() throws Exception { return PackagesTestSupport.ctx(tmp(), Map.of()); }

    // ---------- fuzzy ----------

    @Test void algorithmsMatchKnownValues() throws Exception {
        var c = ctx();
        eqNum("57.14", fz.similarity(c, "kitten", "sitting", "levenshtein", false).get("score"));
        eqNum("96.11", fz.similarity(c, "MARTHA", "MARHTA", "jaro_winkler", false).get("score"));
        eqNum("81.33", fz.similarity(c, "DIXON", "DICKSONX", "jaro_winkler", false).get("score"));
        eqNum("100", fz.similarity(c, "New York Mets", "mets, NEW york", "token_sort", false).get("score"));
        eqNum("100", fz.similarity(c, "Acme Corporation Ltd", "ACME corporation", "token_set", false).get("score"));
        eqNum("100", fz.similarity(c, "", "", "levenshtein", false).get("score"));
        eqNum("0", fz.similarity(c, "abc", "", "jaro_winkler", false).get("score"));
        eqNum("0", fz.similarity(c, "abc", "xyz", "token_set", false).get("score"));
        eqNum("50", fz.similarity(c, "ab", "Ab", "levenshtein", true).get("score"));  // case_sensitive counts the case difference
        assertTrue(((BigDecimal) fz.similarity(c, "Acme Corp", "Acme Corporation", "token_set", false).get("score")).doubleValue() > 50);
        assertEquals(2, FuzzyPackage.levenshtein("flaw", "lawn"));
        var e = assertThrows(ActionException.class, () -> fz.similarity(c, "a", "b", "soundex", false));
        assertEquals("FUZZY.BAD_ALGORITHM", e.code());
    }

    @Test void bestMatchHonoursThreshold() throws Exception {
        var c = ctx();
        List<Object> names = List.of("Globex Inc", "Acme Corporation", "Initech");
        Map<String, Object> r = fz.bestMatch(c, "ACME Corp.", names, 50, "token_set", false);
        assertEquals(true, r.get("found"));
        assertEquals("Acme Corporation", r.get("match"));
        eqNum("1", r.get("index"));
        Map<String, Object> none = fz.bestMatch(c, "Umbrella", names, 90, "levenshtein", false);
        assertEquals(false, none.get("found"));
        assertEquals("", none.get("match"));
        eqNum("-1", none.get("index"));
        assertTrue(((BigDecimal) none.get("score")).doubleValue() < 90, "best score is still reported");
        Map<String, Object> empty = fz.bestMatch(c, "x", List.of(), 0, "levenshtein", false);
        assertEquals(false, empty.get("found"));
        assertEquals("FUZZY.BAD_THRESHOLD", assertThrows(ActionException.class, () -> fz.bestMatch(c, "x", names, 120, "levenshtein", false)).code());
        assertEquals("FUZZY.BAD_ALGORITHM", assertThrows(ActionException.class, () -> fz.bestMatch(c, "x", List.of(), 50, "nope", false)).code());
    }

    @Test void bestMatchReadsTheTextFromAFile() throws Exception {
        Path w = tmp();
        ActionContext c = PackagesTestSupport.ctx(w, Map.of());
        java.nio.file.Files.writeString(w.resolve("name.txt"), "  ACME Corp.\r\n");
        List<Object> names = List.of("Globex Inc", "Acme Corporation", "Initech");
        Map<String, Object> r = fz.bestMatch(c, null, names, 50, "token_set", false, "name.txt");
        assertEquals("Acme Corporation", r.get("match"));
        eqNum("1", r.get("index"));
        // typed text wins over the file
        assertEquals("Initech", fz.bestMatch(c, "Initech", names, 50, "levenshtein", false, "name.txt").get("match"));
        assertEquals("FILE.NOT_FOUND", assertThrows(ActionException.class, () -> fz.bestMatch(c, null, names, 50, "levenshtein", false, "missing.txt")).code());
        assertEquals("FUZZY.NO_TEXT", assertThrows(ActionException.class, () -> fz.bestMatch(c, null, names, 50, "levenshtein", false, null)).code());
    }

    @Test void fuzzyLookupJoinsTables() throws Exception {
        var c = ctx();
        Map<String, Object> invoices = Values.table(List.of("invoice", "vendor"), List.of(
                List.of("INV-1", "Acme Corp"), List.of("INV-2", "Globex Incorporated"), List.of("INV-3", "Totally Unknown")));
        Map<String, Object> vendors = Values.table(List.of("vendor", "vendor_id"), List.of(
                List.of("Acme Corporation", "V100"), List.of("Globex Inc", "V200"), List.of("Initech", "V300")));
        Map<String, Object> r = fz.fuzzyLookup(c, invoices, "vendor", vendors, "vendor", 50, "token_set", true, false);
        Map<?, ?> t = (Map<?, ?>) r.get("table");
        assertEquals(List.of("invoice", "vendor", "right_vendor", "vendor_id", "match_score"), t.get("columns"));
        List<?> rows = (List<?>) t.get("rows");
        assertEquals(3, rows.size());
        assertEquals("V100", ((List<?>) rows.get(0)).get(3));
        assertEquals("V200", ((List<?>) rows.get(1)).get(3));
        assertNull(((List<?>) rows.get(2)).get(3), "no match leaves the reference columns empty");
        eqNum("2", r.get("matched"));
        eqNum("1", r.get("unmatched"));
        Map<String, Object> dropped = fz.fuzzyLookup(c, invoices, "vendor", vendors, "vendor", 50, "token_set", false, false);
        assertEquals(2, ((List<?>) ((Map<?, ?>) dropped.get("table")).get("rows")).size());
        assertEquals("TABLE.NO_COLUMN", assertThrows(ActionException.class, () -> fz.fuzzyLookup(c, invoices, "name", vendors, "vendor", 50, "token_set", true, false)).code());
        assertEquals("FUZZY.BAD_THRESHOLD", assertThrows(ActionException.class, () -> fz.fuzzyLookup(c, invoices, "vendor", vendors, "vendor", -1, "token_set", true, false)).code());
    }

    @Test void similarConditionThroughTheRunner() throws Exception {
        var c = ctx();
        assertTrue(fz.textsSimilar(c, "Jon Smith", "John Smith", 85, "jaro_winkler", false));
        assertFalse(fz.textsSimilar(c, "Jon Smith", "Jane Doe", 85, "levenshtein", false));
        Path w = tmp();
        Map<String, Object> o = run(w, "[{\"name\":\"s\",\"type\":\"Number\",\"direction\":\"output\"},{\"name\":\"hit\",\"type\":\"String\",\"direction\":\"output\",\"default\":\"no\"}]",
                "[{\"id\":\"a\",\"action\":\"ooa.fuzzy/similarity\",\"args\":{\"text1\":\"colour\",\"text2\":\"color\"},\"out\":{\"score\":\"s\"}},"
                + "{\"id\":\"b\",\"action\":\"core/if\",\"args\":{\"condition\":{\"condition\":\"ooa.fuzzy/texts_similar\",\"args\":{\"text1\":\"colour\",\"text2\":\"color\",\"threshold\":80}}},"
                + "\"children\":[{\"id\":\"c\",\"action\":\"core/set\",\"args\":{\"variable\":\"hit\",\"value\":\"yes\"}}]}]", Map.of());
        eqNum("83.33", o.get("s"));
        assertEquals("yes", o.get("hit"));
    }

    // ---------- currency ----------

    static final String ECB = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gesmes:Envelope xmlns:gesmes="http://www.gesmes.org/xml/2002-08-01" xmlns="http://www.ecb.int/vocabulary/2002-08-01/eurofxref">
              <gesmes:subject>Reference rates</gesmes:subject>
              <Cube><Cube time='2026-10-19'>
                <Cube currency='USD' rate='1.0800'/><Cube currency='GBP' rate='0.8500'/><Cube currency='INR' rate='90.25'/>
              </Cube></Cube>
            </gesmes:Envelope>""";

    @Test void ecbRatesAreFetchedOncePerRun() throws Exception {
        try (Fake f = new Fake()) {
            f.on("GET /eurofxref.xml", c -> ok(ECB));
            var c = ctx();
            String url = f.url() + "/eurofxref.xml";
            Map<String, Object> r = cur.convert(c, new BigDecimal("108"), "usd", "EUR", 2, "ecb", url, null, null, null);
            eqNum("100.00", r.get("amount"));
            eqNum("0.9259259259259259", r.get("rate"));
            assertEquals("2026-10-19", r.get("rate_date"));
            eqNum("106.1764705882353", cur.getRate(c, "GBP", "INR", "ecb", url, null, null, null).get("rate"));
            Map<String, Object> l = cur.listCurrencies(c, "ecb", url, null, null, null);
            assertEquals(List.of("EUR", "GBP", "INR", "USD"), l.get("currencies"));
            assertEquals("EUR", l.get("base_currency"));
            assertEquals(1, f.calls.size(), "rates are cached for the run");
            cur.convert(ctx(), BigDecimal.ONE, "EUR", "USD", 2, "ecb", url, null, null, null);
            assertEquals(2, f.calls.size(), "a new run loads fresh rates");
            assertEquals("CURRENCY.UNKNOWN_CURRENCY", assertThrows(ActionException.class, () -> cur.convert(c, BigDecimal.ONE, "EUR", "JPY", 2, "ecb", url, null, null, null)).code());
            assertEquals("CURRENCY.BAD_CODE", assertThrows(ActionException.class, () -> cur.convert(c, BigDecimal.ONE, "EURO", "USD", 2, "ecb", url, null, null, null)).code());
        }
        try (Fake f = new Fake()) {
            f.on("GET /bad.xml", c -> ok("<html>maintenance</html>")).on("GET /down.xml", c -> new Object[]{503, "busy"});
            var c = ctx();
            assertEquals("CURRENCY.BAD_RESPONSE", assertThrows(ActionException.class, () -> cur.getRate(c, "EUR", "USD", "ecb", f.url() + "/bad.xml", null, null, null)).code());
            var down = assertThrows(ActionException.class, () -> cur.getRate(c, "EUR", "USD", "ecb", f.url() + "/down.xml", null, null, null));
            assertEquals("CURRENCY.SERVER_ERROR", down.code());
            assertTrue(down.retryable());
        }
    }

    @Test void jsonSourceWithPath() throws Exception {
        try (Fake f = new Fake()) {
            f.on("GET /latest", c -> ok("{\"base\":\"USD\",\"date\":\"2026-10-19\",\"rates\":{\"EUR\":0.92,\"GBP\":0.79,\"note\":\"x\"}}"))
             .on("GET /nested", c -> ok("{\"data\":[{\"quotes\":{\"eur\":\"0.5\",\"CHF\":0.45}}]}"));
            var c = ctx();
            Map<String, Object> r = cur.convert(c, new BigDecimal("50"), "USD", "GBP", 1, "json", f.url() + "/latest", "$.rates", null, null);
            eqNum("39.5", r.get("amount"));
            assertEquals("2026-10-19", r.get("rate_date"));
            eqNum("1.111111111111111", cur.getRate(c, "CHF", "EUR", "json", f.url() + "/nested", "data[0].quotes", "USD", null).get("rate"));
            assertEquals("CURRENCY.NO_BASE", assertThrows(ActionException.class, () -> cur.getRate(c, "CHF", "EUR", "json", f.url() + "/nested", "$.data[0]['quotes']", null, null)).code());
            assertEquals("CURRENCY.BAD_RESPONSE", assertThrows(ActionException.class, () -> cur.getRate(c, "USD", "EUR", "json", f.url() + "/latest", "rates.EUR", null, null)).code());
            assertEquals("CURRENCY.NO_URL", assertThrows(ActionException.class, () -> cur.getRate(c, "USD", "EUR", "json", null, null, null, null)).code());
        }
    }

    @Test void fixedRatesAndInputErrors() throws Exception {
        var c = ctx();
        Map<String, Object> rates = Map.of("USD", 1.1, "INR", 90);
        eqNum("90.91", cur.convert(c, new BigDecimal("100"), "USD", "EUR", 2, "fixed", null, null, "EUR", rates).get("amount"));
        eqNum("8181.82", cur.convert(c, new BigDecimal("100"), "USD", "INR", 2, "fixed", null, null, "eur", rates).get("amount"));
        eqNum("100", cur.convert(c, new BigDecimal("100"), "INR", "INR", 0, "fixed", null, null, "EUR", rates).get("amount"));
        assertEquals(List.of("EUR", "INR", "USD"), cur.listCurrencies(c, "fixed", null, null, "EUR", rates).get("currencies"));
        assertEquals("CURRENCY.NO_BASE", assertThrows(ActionException.class, () -> cur.convert(c, BigDecimal.ONE, "USD", "EUR", 2, "fixed", null, null, null, rates)).code());
        assertEquals("CURRENCY.NO_RATES", assertThrows(ActionException.class, () -> cur.convert(c, BigDecimal.ONE, "USD", "EUR", 2, "fixed", null, null, "EUR", Map.of())).code());
        assertEquals("CURRENCY.BAD_RATE", assertThrows(ActionException.class, () -> cur.convert(c, BigDecimal.ONE, "USD", "EUR", 2, "fixed", null, null, "EUR", Map.of("USD", 0))).code());
        assertEquals("CURRENCY.BAD_RATE", assertThrows(ActionException.class, () -> cur.convert(c, BigDecimal.ONE, "USD", "EUR", 2, "fixed", null, null, "EUR", Map.of("USD", "abc"))).code());
        assertEquals("CURRENCY.BAD_SOURCE", assertThrows(ActionException.class, () -> cur.convert(c, BigDecimal.ONE, "USD", "EUR", 2, "bank", null, null, "EUR", rates)).code());
        assertEquals("CURRENCY.BAD_DECIMALS", assertThrows(ActionException.class, () -> cur.convert(c, BigDecimal.ONE, "USD", "EUR", -1, "fixed", null, null, "EUR", rates)).code());
        assertEquals("CURRENCY.NO_AMOUNT", assertThrows(ActionException.class, () -> cur.convert(c, null, "USD", "EUR", 2, "fixed", null, null, "EUR", rates)).code());
        assertEquals("CURRENCY.BAD_PATH", assertThrows(ActionException.class, () -> CurrencyPackage.select(Map.of(), "a..[")).code());
        Map<String, Object> o = run(tmp(), "[{\"name\":\"amt\",\"type\":\"Number\",\"direction\":\"output\"}]",
                "[{\"id\":\"a\",\"action\":\"ooa.currency/convert\",\"args\":{\"amount\":10,\"from_currency\":\"EUR\",\"to_currency\":\"USD\",\"source\":\"fixed\",\"base_currency\":\"EUR\",\"rates\":{\"USD\":1.25}},\"out\":{\"amount\":\"amt\"}}]", Map.of());
        eqNum("12.50", o.get("amt"));
    }
}
