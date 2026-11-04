package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static com.oneofficeautomation.packages.ConnectorsTest.*;
import static com.oneofficeautomation.packages.PackagesTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** G15: Detect personal data, by pattern (no model) and with a stand-in model for names and addresses. */
class DetectPiiTest {
    static final SecureString KEY = new SecureString("sk-test", "k");

    static Map<String, Object> run(String text, List<Object> kinds, String style) throws Exception {
        return run(text, kinds, style, "*");
    }

    static Map<String, Object> run(String text, List<Object> kinds, String style, String mc) throws Exception {
        return new GenAiPackage().detectPii(PackagesTestSupport.ctx(tmp(), Map.of()), text, kinds, false, mc, style,
                "openai", null, null, null, null, true, null, null);
    }

    /** The texts found, as kind:text. */
    @SuppressWarnings("unchecked")
    static List<String> found(String text, Object... kinds) throws Exception {
        List<String> out = new ArrayList<>();
        for (Object o : (List<Object>) run(text, List.of(kinds), "partial").get("items")) {
            Map<String, Object> m = (Map<String, Object>) o;
            out.add(m.get("kind") + ":" + m.get("text"));
        }
        return out;
    }

    @Test void emails() throws Exception {
        assertEquals(List.of("email:john.doe+bills@acme.co.uk"), found("Write to john.doe+bills@acme.co.uk."));
        assertEquals(List.of(), found("not an address: john@acme, @handle, a@b.c"));
    }

    @Test void phones() throws Exception {
        assertEquals(List.of("phone:+1 555-123-4567"), found("Call +1 555-123-4567 today"));
        assertEquals(List.of("phone:(555) 123-4567"), found("Office (555) 123-4567."));
        assertEquals(List.of("phone:+91 98765 43210"), found("Mobile +91 98765 43210"));
        assertEquals(List.of("phone:+44 (0)20 7946 0958"), found("London +44 (0)20 7946 0958"));
        assertEquals(List.of("phone:9876543210"), found("ring 9876543210 now"));
        // near misses: dates, short numbers, amounts, decimals, bare 9-digit ids
        assertEquals(List.of(), found("Due 2026-11-03 or 03/11/2026, order 12345, total 1 234 567, rate 1234567.89, id 123456789, v1.2.3"));
    }

    @Test void cardsNeedLuhn() throws Exception {
        assertEquals(List.of("credit_card:4111 1111 1111 1111"), found("Card 4111 1111 1111 1111 exp 12/29"));
        assertEquals(List.of("credit_card:378282246310005"), found("Amex 378282246310005"));
        assertEquals(List.of("credit_card:5500-0000-0000-0004"), found("MC 5500-0000-0000-0004"));
        assertFalse(LlmClient.luhn("4111111111111112"));
        assertEquals(List.of(), found("Ref 4111 1111 1111 1112 and 4111111111111112 and 0000000000000000"));
    }

    @Test void ibansNeedMod97() throws Exception {
        assertEquals(List.of("iban:GB82 WEST 1234 5698 7654 32"), found("Pay GB82 WEST 1234 5698 7654 32 please"));
        assertEquals(List.of("iban:DE89370400440532013000"), found("IBAN DE89370400440532013000."));
        // a following word in capitals is not swallowed
        assertEquals(List.of("iban:DE89 3704 0044 0532 0130 00"), found("DE89 3704 0044 0532 0130 00 THEN"));
        assertEquals(List.of(), found("DE89 3704 0044 0532 0130 01 and GB00 WEST 1234 5698 7654 32"));
    }

    @Test void ipAddresses() throws Exception {
        assertEquals(List.of("ip_address:192.168.1.25", "ip_address:2001:db8::8a2e:370:7334"), found("Hosts 192.168.1.25 and 2001:db8::8a2e:370:7334."));
        assertEquals(List.of(), found("999.1.1.1 and version 1.2.3.4.5 and 10:30:45 and std::vector"));
    }

    @Test void usSsn() throws Exception {
        assertEquals(List.of("us_ssn:123-45-6789"), found("SSN 123-45-6789"));
        assertEquals(List.of(), found("000-12-3456, 666-12-3456, 912-34-5678, 123-00-4567, 123-45-0000"));
    }

    @Test void indianPanAndAadhaar() throws Exception {
        assertEquals(List.of("in_pan:ABCPE1234F"), found("PAN ABCPE1234F"));
        assertEquals(List.of(), found("ABCXE1234F and abcpe1234f and XABCPE1234F"));
        assertEquals(List.of("in_aadhaar:2341 2341 2346"), found("Aadhaar 2341 2341 2346"));
        assertEquals(List.of("in_aadhaar:498765432105"), found("UID 498765432105"));
        assertTrue(PiiDetector.verhoeff("234123412346"));
        assertFalse(PiiDetector.verhoeff("234123412345"));
        // fails Verhoeff, and is not then taken for a phone number either; starting 0 or 1 is never an Aadhaar
        assertEquals(List.of(), found("2341 2341 2345 and 1234 5678 9012"));
    }

    @Test void overlapsGoToTheBestKindAndAreNeverReportedTwice() throws Exception {
        // an IP address could also read as a phone number; asking only for phones does not relabel it
        assertEquals(List.of("ip_address:10.20.30.40"), found("at 10.20.30.40"));
        assertEquals(List.of(), found("at 10.20.30.40", "phone"));
        assertEquals(List.of(), found("Aadhaar 2341 2341 2346", "phone"));
        // overlapping candidates: the checksummed kind wins, and items are in text order without overlaps
        List<PiiDetector.Item> s = PiiDetector.settle(List.of(
                new PiiDetector.Item("phone", "1111 1111 1111", 5, 19),
                new PiiDetector.Item("credit_card", "4111 1111 1111 1111", 0, 19),
                new PiiDetector.Item("email", "a@b.co", 25, 31)), List.of(new PiiDetector.Item("person_name", "b.co", 27, 31), new PiiDetector.Item("person_name", "Ann", 40, 43)));
        assertEquals(List.of("credit_card", "email", "person_name"), s.stream().map(PiiDetector.Item::kind).toList());
        assertEquals(List.of(0, 25, 40), s.stream().map(PiiDetector.Item::start).toList());
    }

    @SuppressWarnings("unchecked")
    @Test void outputsAndMaskingStyles() throws Exception {
        String text = "Mail john@acme.com, card 4111 1111 1111 1111, call +1 555-123-4567, IBAN DE89 3704 0044 0532 0130 00, PAN ABCPE1234F.";
        Map<String, Object> r = run(text, null, "partial");
        assertEquals(true, r.get("found"));
        assertEquals(0, BigDecimal.valueOf(5).compareTo((BigDecimal) r.get("count")));
        assertEquals(List.of("email", "phone", "credit_card", "iban", "in_pan"), r.get("kinds_found"));
        Map<String, Object> first = (Map<String, Object>) ((List<Object>) r.get("items")).get(0);
        assertEquals("email", first.get("kind"));
        assertEquals(0, BigDecimal.valueOf(5).compareTo((BigDecimal) first.get("start")));
        assertEquals(0, BigDecimal.valueOf(18).compareTo((BigDecimal) first.get("end")));
        assertEquals("Mail j***@acme.com, card **** **** **** 1111, call +* ***-***-4567, IBAN DE** **** **** **** **30 00, PAN A********F.", r.get("masked_text"));

        Map<String, Object> full = run("Mail john@acme.com now", null, "full", "#");
        assertEquals("Mail ############# now", full.get("masked_text"));

        Map<String, Object> only = run(text, List.of("email", "in_pan"), "partial");
        assertEquals(List.of("email", "in_pan"), only.get("kinds_found"));
        assertTrue(((String) only.get("masked_text")).contains("4111 1111 1111 1111"), "kinds not asked for stay as they are");

        Map<String, Object> none = run("Nothing personal here, order 12345 on 2026-11-03.", null, "partial");
        assertEquals(false, none.get("found"));
        assertEquals("Nothing personal here, order 12345 on 2026-11-03.", none.get("masked_text"));
    }

    @Test void badOptions() {
        assertEquals("AI.BAD_OPTION", assertThrows(ActionException.class, () -> run("x", List.of("passport"), "partial")).code());
        assertEquals("AI.BAD_OPTION", assertThrows(ActionException.class, () -> run("x", List.of("person_name"), "partial")).code(), "names need use_ai");
        assertEquals("AI.BAD_OPTION", assertThrows(ActionException.class, () -> run("x", null, "half")).code());
        assertEquals("AI.BAD_OPTION", assertThrows(ActionException.class, () -> run("x", null, "full", "**")).code());
    }

    @SuppressWarnings("unchecked")
    @Test void useAiAddsNamesAndAddressesFromTheModel() throws Exception {
        try (Fake f = new Fake()) {
            String answer = "{\\\"items\\\":[{\\\"kind\\\":\\\"person_name\\\",\\\"text\\\":\\\"Jane Doe\\\"},{\\\"kind\\\":\\\"address\\\",\\\"text\\\":\\\"12 Baker Street, London\\\"},"
                    + "{\\\"kind\\\":\\\"person_name\\\",\\\"text\\\":\\\"Nobody Here\\\"},{\\\"kind\\\":\\\"bank\\\",\\\"text\\\":\\\"Acme\\\"}]}";
            f.on("POST /v1/chat/completions", c -> ok("{\"choices\":[{\"message\":{\"content\":\"" + answer + "\"}}],\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":9}}"));
            String text = "Jane Doe lives at 12 Baker Street, London; mail jane@acme.com. Regards, Jane Doe";
            Map<String, Object> r = new GenAiPackage().detectPii(PackagesTestSupport.ctx(tmp(), Map.of()), text, null, true, "*", "full",
                    "openai", null, "gpt-test", KEY, f.url() + "/v1", true, null, null);
            String sent = f.last("POST", "/v1/chat/completions").text();
            assertTrue(sent.contains("person_name") && sent.contains("address"), "the model is asked for names and addresses");
            assertFalse(sent.contains("jane@acme.com"), "the email is masked before the text goes to the model");
            List<String> got = new ArrayList<>();
            for (Object o : (List<Object>) r.get("items")) { Map<String, Object> m = (Map<String, Object>) o; got.add(m.get("kind") + ":" + m.get("text")); }
            assertEquals(List.of("person_name:Jane Doe", "address:12 Baker Street, London", "email:jane@acme.com", "person_name:Jane Doe"), got);
            assertEquals(List.of("email", "person_name", "address"), r.get("kinds_found"));
            assertEquals("******** lives at ***********************; mail *************. Regards, ********", r.get("masked_text"));

            // only names wanted: the model is asked for names alone
            new GenAiPackage().detectPii(PackagesTestSupport.ctx(tmp(), Map.of()), text, List.of("person_name"), true, "*", "partial",
                    "openai", null, "gpt-test", KEY, f.url() + "/v1", true, null, null);
            String sent2 = f.last("POST", "/v1/chat/completions").text();
            assertTrue(sent2.contains("person_name") && !sent2.contains("\\\"address\\\""), sent2);
        }

        // without use_ai no model is needed at all
        assertEquals(true, run("jane@acme.com", null, "partial").get("found"));
    }
}
