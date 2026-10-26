package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static com.oneofficeautomation.packages.ConnectorsTest.*;
import static com.oneofficeautomation.packages.PackagesTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Currency conversion at a past day's ECB rates (the ECB's eurofxref history files). */
class CurrencyMoreOptionsTest {
    final CurrencyPackage cur = new CurrencyPackage();

    static ActionContext ctx() throws Exception { return PackagesTestSupport.ctx(tmp(), Map.of()); }

    /** Same layout as eurofxref-hist-90d.xml: newest day first, one Cube per day. */
    static final String HIST = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gesmes:Envelope xmlns:gesmes="http://www.gesmes.org/xml/2002-08-01" xmlns="http://www.ecb.int/vocabulary/2002-08-01/eurofxref">
              <gesmes:subject>Reference rates</gesmes:subject>
              <Cube>
                <Cube time="2026-10-23"><Cube currency="USD" rate="1.1193"/><Cube currency="GBP" rate="0.83728"/></Cube>
                <Cube time="2026-10-22"><Cube currency="USD" rate="1.1000"/><Cube currency="GBP" rate="0.8400"/></Cube>
                <Cube time="2026-10-16"><Cube currency="USD" rate="1.2000"/><Cube currency="GBP" rate="0.8000"/></Cube>
              </Cube>
            </gesmes:Envelope>""";

    @Test void convertsAtAPastDaysRate() throws Exception {
        try (Fake f = new Fake()) {
            f.on("GET /hist.xml", c -> ok(HIST));
            var c = ctx();
            String url = f.url() + "/hist.xml";
            Map<String, Object> r = cur.convert(c, new BigDecimal("110"), "USD", "EUR", 2, "ecb", url, null, null, null, "2026-10-22");
            eqNum("100.00", r.get("amount"));
            assertEquals("2026-10-22", r.get("rate_date"));
            // a Sunday uses the Friday before it
            Map<String, Object> sunday = cur.convert(c, new BigDecimal("100"), "EUR", "USD", 2, "ecb", url, null, null, null, LocalDate.parse("2026-10-18").atStartOfDay());
            eqNum("120.00", sunday.get("amount"));
            assertEquals("2026-10-16", sunday.get("rate_date"));
            eqNum("0.80", cur.convert(c, BigDecimal.ONE, "EUR", "GBP", 2, "ecb", url, null, null, null, "2026-10-20").get("amount"));
            assertEquals(1, f.calls.size(), "the history is loaded once per run");
            assertEquals("GET", f.calls.get(0).method());
            assertEquals("/hist.xml", f.calls.get(0).path());
            assertEquals("CURRENCY.NO_RATE_FOR_DATE", assertThrows(ActionException.class,
                    () -> cur.convert(c, BigDecimal.ONE, "EUR", "USD", 2, "ecb", url, null, null, null, "2026-09-01")).code());
        }
    }

    @Test void historyParsingAndErrors() throws Exception {
        TreeMap<String, CurrencyPackage.Rates> days = CurrencyPackage.ecbHistory(HIST);
        assertEquals(List.of("2026-10-16", "2026-10-22", "2026-10-23"), new ArrayList<>(days.keySet()));
        assertEquals(0, new BigDecimal("0.83728").compareTo(days.get("2026-10-23").rates().get("GBP")));
        assertEquals("CURRENCY.BAD_RESPONSE", assertThrows(ActionException.class, () -> CurrencyPackage.ecbHistory("<html/>")).code());
        var c = ctx();
        assertEquals("CURRENCY.DATE_NOT_SUPPORTED", assertThrows(ActionException.class,
                () -> cur.convert(c, BigDecimal.ONE, "EUR", "USD", 2, "fixed", null, null, "EUR", Map.of("USD", 1.1), "2026-10-22")).code());
        assertEquals("CURRENCY.BAD_DATE", assertThrows(ActionException.class,
                () -> cur.convert(c, BigDecimal.ONE, "EUR", "USD", 2, "ecb", "http://127.0.0.1:9/x", null, null, null, "22/10/2026")).code());
        assertEquals("CURRENCY.BAD_DATE", assertThrows(ActionException.class,
                () -> cur.convert(c, BigDecimal.ONE, "EUR", "USD", 2, "ecb", "http://127.0.0.1:9/x", null, null, null, LocalDate.now().plusDays(3).toString())).code());
        // no date: today's rates, exactly as before
        eqNum("1.10", cur.convert(c, BigDecimal.ONE, "EUR", "USD", 2, "fixed", null, null, "EUR", Map.of("USD", 1.1), "").get("amount"));
        eqNum("1.10", cur.convert(c, BigDecimal.ONE, "EUR", "USD", 2, "fixed", null, null, "EUR", Map.of("USD", 1.1)).get("amount"));
    }

    @Test void theRightEcbFileIsChosen() {
        assertTrue(CurrencyPackage.ECB_90D_URL.endsWith("/stats/eurofxref/eurofxref-hist-90d.xml"));
        assertTrue(CurrencyPackage.ECB_HIST_URL.endsWith("/stats/eurofxref/eurofxref-hist.xml"));
    }
}
