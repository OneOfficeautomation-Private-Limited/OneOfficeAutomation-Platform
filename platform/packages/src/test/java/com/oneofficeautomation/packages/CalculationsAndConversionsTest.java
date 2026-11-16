package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Calculate, the new String, Number and Boolean commands and Convert value (16 Nov 2026), called directly. */
class CalculationsAndConversionsTest {
    @TempDir Path tmp;

    ActionContext ctx() { return PackagesTestSupport.ctx(tmp, Map.of()); }

    static BigDecimal d(String s) { return new BigDecimal(s); }
    static void num(String expected, Object actual) { assertEquals(0, d(expected).compareTo((BigDecimal) actual), "got " + actual); }
    static String code(org.junit.jupiter.api.function.Executable e) { return assertThrows(OoaException.class, e).code(); }

    final NumberPackage n = new NumberPackage();
    final StringPackage s = new StringPackage();
    final BooleanPackage b = new BooleanPackage();
    final ConvertPackage c = new ConvertPackage();

    Object calc(String op, String a, String bb) { return n.calculate(ctx(), op, a == null ? null : d(a), bb == null ? null : d(bb), null, null, null).get("result"); }

    @Test
    void calculateByOperation() {
        num("15", calc("add", "12.5", "2.5"));
        num("10", calc("subtract", "12.5", "2.5"));
        num("31.25", calc("multiply", "12.5", "2.5"));
        num("5", calc("divide", "12.5", "2.5"));
        num("0.3333333333333333333333333333333333", calc("divide", "1", "3"));
        num("1", calc("remainder", "10", "3"));
        num("1024", calc("power", "2", "10"));
        num("3", calc("square_root", "9", null));
        num("4.5", calc("absolute", "-4.5", null));
        num("-4.5", calc("negate", "4.5", null));
        num("3", calc("round", "2.5", null));
        num("3", calc("round_up", "2.1", null));
        num("2", calc("round_down", "2.9", null));
        num("-2", calc("integer_part", "-2.75", null));
        num("-0.75", calc("decimal_part", "-2.75", null));
        num("30", calc("percent_of", "15", "200"));
        num("25", calc("percentage_change", "80", "100"));
        num("-20", calc("percentage_change", "100", "80"));
        num("2.35", n.calculate(ctx(), "round_up", d("2.341"), null, null, null, 2).get("result"));
        num("0.33", n.calculate(ctx(), "divide", d("1"), d("3"), null, null, 2).get("result"));
        List<Object> list = List.of(d("10"), "", d("20"), d("45"));
        num("75", n.calculate(ctx(), "sum", null, null, list, null, null).get("result"));
        num("25", n.calculate(ctx(), "average", null, null, list, null, null).get("result"));
        num("10", n.calculate(ctx(), "minimum", null, null, list, null, null).get("result"));
        num("45", n.calculate(ctx(), "maximum", null, null, list, null, null).get("result"));
        assertEquals("NUMBER.DIVIDE_BY_ZERO", code(() -> calc("divide", "1", "0")));
        assertEquals("NUMBER.MISSING", code(() -> calc("add", "1", null)));
        assertEquals("NUMBER.NEGATIVE", code(() -> calc("square_root", "-1", null)));
        assertEquals("NUMBER.NO_VALUES", code(() -> n.calculate(ctx(), "sum", null, null, List.of(), null, null)));
    }

    @Test
    void calculateByFormula() {
        // Studio saves the formula as {"expr": ...}, so the runner hands over the result; typed text is worked out here
        num("44.25", n.calculate(ctx(), "add", null, null, null, d("44.25"), null).get("result"));
        num("44.25", n.calculate(ctx(), "add", null, null, null, "(12.5 * 3) * (1 + 18 / 100)", null).get("result"));
        num("44.3", n.calculate(ctx(), "add", null, null, null, "(12.5 * 3) * (1 + 18 / 100)", 1).get("result"));
        assertEquals("NUMBER.BAD_FORMULA", code(() -> n.calculate(ctx(), "add", null, null, null, "(1 + ", null)));
        assertEquals("NUMBER.BAD_FORMULA", code(() -> n.calculate(ctx(), "add", null, null, null, "'abc'", null)));
    }

    @Test
    void statisticsStepsFormatsAndWords() {
        Map<String, Object> st = n.statistics(ctx(), List.of(d("3"), d("1"), d("4"), d("1"), d("5")), false);
        num("5", st.get("count")); num("14", st.get("sum")); num("2.8", st.get("average")); num("1", st.get("minimum")); num("5", st.get("maximum")); num("3", st.get("median"));
        num("2.5", n.statistics(ctx(), List.of(d("1"), d("2"), d("3"), d("4")), false).get("median"));
        num("2", n.statistics(ctx(), List.of(d("1"), "n/a", d("3")), true).get("count"));
        assertEquals("NUMBER.NOT_A_NUMBER", code(() -> n.statistics(ctx(), List.of(d("1"), "n/a"), false)));
        num("1.25", n.roundToStep(ctx(), d("1.23"), d("0.05"), "nearest").get("result"));
        num("15", n.roundToStep(ctx(), d("11"), d("5"), "up").get("result"));
        num("24", n.roundToStep(ctx(), d("35"), d("12"), "down").get("result"));
        assertEquals("NUMBER.BAD_STEP", code(() -> n.roundToStep(ctx(), d("1"), d("0"), "nearest")));
        assertEquals("$1,234.50", n.formatAs(ctx(), d("1234.5"), "currency", "USD", null, "en-US", null, true, false).get("text"));
        assertEquals("¥1,235", n.formatAs(ctx(), d("1234.5"), "currency", "JPY", null, "en-US", null, true, false).get("text"));
        assertTrue(((String) n.formatAs(ctx(), d("1234567.5"), "currency", "INR", null, "en-IN", null, true, false).get("text")).contains("12,34,567.50"));
        assertEquals("15%", n.formatAs(ctx(), d("0.15"), "percent", null, null, "en-US", null, true, false).get("text"));
        assertEquals("15.5%", n.formatAs(ctx(), d("15.5"), "percent", null, null, "en-US", 1, false, false).get("text"));
        assertEquals("1,250.5 kg", n.formatAs(ctx(), d("1250.5"), "unit", null, "kg", "en-US", null, true, false).get("text"));
        assertEquals("Rs 120.00", n.formatAs(ctx(), d("120"), "unit", null, "Rs", "en-US", 2, true, true).get("text"));
        assertEquals("NUMBER.BAD_CURRENCY", code(() -> n.formatAs(ctx(), d("1"), "currency", "XYZ1", null, "en-US", null, true, false)));
        assertEquals("One thousand two hundred thirty-four dollars and fifty cents",
                n.toWords(ctx(), d("1234.50"), "amount", "dollars", "cents", "international", "sentence").get("text"));
        assertEquals("Twelve lakh thirty-four thousand five hundred sixty-seven rupees and five paise",
                n.toWords(ctx(), d("1234567.05"), "amount", "rupees", "paise", "indian", "sentence").get("text"));
        assertEquals("ONE CRORE", n.toWords(ctx(), d("10000000"), "amount", "", "", "indian", "upper").get("text"));
        assertEquals("Minus three point one four", n.toWords(ctx(), d("-3.14"), "plain", null, null, "international", "sentence").get("text"));
        assertEquals("Two Million One", n.toWords(ctx(), d("2000001"), "amount", "", "", "international", "title").get("text"));
        assertEquals("zero dollars", n.toWords(ctx(), d("0"), "amount", "dollars", "cents", "international", "lower").get("text"));
        assertTrue(n.isBetween(ctx(), d("5"), d("1"), d("5"), true));
        assertFalse(n.isBetween(ctx(), d("5"), d("1"), d("5"), false));
        assertTrue(n.isBetween(ctx(), d("3"), d("5"), d("1"), true), "the ends may be given either way round");
        assertTrue(n.compareNumbers(ctx(), d("1000"), "greater_or_equal", d("1000"), null));
        assertTrue(n.compareNumbers(ctx(), d("10.004"), "equal", d("10"), d("0.01")));
        assertFalse(n.compareNumbers(ctx(), d("10.004"), "greater", d("10"), d("0.01")), "close numbers count as equal");
        assertTrue(n.compareNumbers(ctx(), d("9"), "less", d("10"), null));
    }

    @Test
    void textCommands() {
        assertEquals("Ann, Bo and Cy", s.join(ctx(), List.of("Ann", "", "Bo", "Cy"), ", ", true, " and ", null, null).get("result"));
        assertEquals("[a|b]", s.join(ctx(), List.of("a", "b"), "|", true, null, "[", "]").get("result"));
        assertEquals("a\nb", s.join(ctx(), List.of("a", "b"), "\\n", true, null, null, null).get("result"));
        Map<String, Object> fa = s.findAll(ctx(), "INV-1, inv-22 and INV-1", "INV-(\\d+)", true, true, 1, true);
        assertEquals(List.of("1", "22"), fa.get("matches")); num("2", fa.get("count"));
        assertEquals("STRING.BAD_PATTERN", code(() -> s.findAll(ctx(), "x", "(", true, false, 0, false)));
        Map<String, Object> ep = s.extractPattern(ctx(), "Invoice 42 of 2026-11-16: 99.50", "Invoice (?<number>\\d+) of (?<date>[\\d-]+): (?<total>[\\d.]+)", 1, false);
        assertEquals(true, ep.get("found"));
        assertEquals(List.of("42", "2026-11-16", "99.50"), ep.get("groups"));
        assertEquals("99.50", ((Map<?, ?>) ep.get("named")).get("total"));
        assertEquals(false, s.extractPattern(ctx(), "nothing", "\\d+", 1, false).get("found"));
        assertEquals("+15550100", s.removeCharacters(ctx(), "+1 (555) 0100", false, false, false, true, false, false, "()", false).get("result"));
        assertEquals("15550100", s.removeCharacters(ctx(), "+1 (555) 0100", true, false, false, false, false, false, null, true).get("result"));
        assertEquals("Cafe Muller", s.removeCharacters(ctx(), "Café Müller", false, false, false, false, false, true, null, false).get("result"));
        assertEquals("Hello World", s.removeCharacters(ctx(), "Hello, World!", false, false, true, false, false, false, null, false).get("result"));
        assertEquals("a b c", s.normalizeSpaces(ctx(), "  a \t b \n c ", false).get("result"));
        assertEquals("a b\nc", s.normalizeSpaces(ctx(), " a   b \n\n  c ", true).get("result"));
        assertEquals("INV-0042", s.insert(ctx(), "INV0042", "-", 3).get("result"));
        assertEquals("abc!", s.insert(ctx(), "abc", "!", -1).get("result"));
        assertEquals("STRING.BAD_POSITION", code(() -> s.insert(ctx(), "abc", "!", 9)));
        assertEquals("0042", s.removePart(ctx(), "INV-0042", 0, 4).get("result"));
        assertEquals("INV", s.removePart(ctx(), "INV-0042", 3, null).get("result"));
        assertEquals("ab-ab-ab", s.repeat(ctx(), "ab", 3, "-").get("result"));
        assertEquals("STRING.TOO_LONG", code(() -> s.repeat(ctx(), "abcdefghijk", 100000, "-")));
        assertEquals("**** **** **** 1111", s.mask(ctx(), "4111 1111 1111 1111", 4, 0, "*", true, false).get("result"));
        assertEquals("A***3", s.mask(ctx(), "AB123", 1, 1, "*", true, false).get("result"));
        assertEquals("a***@example.com", s.mask(ctx(), "ann@example.com", 4, 0, "*", true, true).get("result"));
        Map<String, Object> sh = s.shorten(ctx(), "The quick brown fox jumps over the lazy dog", 20, "…", true);
        assertEquals("The quick brown fox…", sh.get("result")); assertEquals(true, sh.get("shortened"));
        assertEquals(false, s.shorten(ctx(), "short", 20, "…", true).get("shortened"));
        Map<String, Object> cw = s.countWords(ctx(), "Hello there. It's 9 o'clock!\nBye");
        num("6", cw.get("words")); num("2", cw.get("lines")); num("3", cw.get("sentences"));
        num("0", s.countWords(ctx(), "").get("lines"));
        Map<String, Object> ft = s.fillTemplate(ctx(), "Dear {First_Name}, invoice {number} {{ref}} is due {due}.", Map.of("first_name", "Ann", "number", d("42")), "keep");
        assertEquals("Dear Ann, invoice 42 {ref} is due {due}.", ft.get("result"));
        assertEquals(List.of("due"), ft.get("missing"));
        assertEquals("STRING.MISSING_VALUE", code(() -> s.fillTemplate(ctx(), "{x}", Map.of(), "error")));
        assertEquals("a%20b%26c%2F%C3%A9", s.encode(ctx(), "a b&c/é", "encode", "url").get("result"));
        assertEquals("a b&c/é", s.encode(ctx(), "a%20b%26c%2F%C3%A9", "decode", "url").get("result"));
        assertEquals("&lt;b&gt; &amp; &quot;x&quot;", s.encode(ctx(), "<b> & \"x\"", "encode", "html").get("result"));
        assertEquals("<b> & é", s.encode(ctx(), "&lt;b&gt; &amp; &#233;", "decode", "html").get("result"));
        assertEquals("line1\\nsaid \\\"hi\\\"", s.encode(ctx(), "line1\nsaid \"hi\"", "encode", "json").get("result"));
        assertEquals("line1\nsaid \"hi\"", s.encode(ctx(), "line1\\nsaid \\\"hi\\\"", "decode", "json").get("result"));
        assertEquals("aMOpbGxv", s.encode(ctx(), "héllo", "encode", "base64").get("result"));
        assertEquals("héllo", s.encode(ctx(), "aMOpbGxv", "decode", "base64").get("result"));
        assertEquals("STRING.CANNOT_DECODE", code(() -> s.encode(ctx(), "%zz", "decode", "url")));
        assertEquals("invoiceTotalAmount", s.changeCase(ctx(), "Invoice total-amount", "camel").get("result"));
        assertEquals("InvoiceTotal", s.changeCase(ctx(), "invoice_total", "pascal").get("result"));
        assertEquals("invoice_total_eur", s.changeCase(ctx(), "invoiceTotal EUR", "snake").get("result"));
        assertEquals("invoice-total", s.changeCase(ctx(), "Invoice Total", "kebab").get("result"));
        assertTrue(s.startsWith(ctx(), "INV-42", "inv", true));
        assertFalse(s.startsWith(ctx(), "INV-42", "inv", false));
        assertTrue(s.endsWith(ctx(), "report.PDF", ".pdf", true));
        assertFalse(s.endsWith(ctx(), "a", "abc", true));
        assertTrue(s.containsText(ctx(), "This is URGENT", "urgent", true, true));
        assertFalse(s.containsText(ctx(), "urgently", "urgent", true, true));
        assertTrue(s.isEmpty(ctx(), "  \n", true));
        assertFalse(s.isEmpty(ctx(), " ", false));
        assertTrue(s.isEmpty(ctx(), null, true));
        assertTrue(s.matchesPattern(ctx(), "INV-123456", "^INV-\\d{6}$", true, false));
        assertTrue(s.matchesPattern(ctx(), "see INV-1", "INV-\\d", false, false));
        assertFalse(s.matchesPattern(ctx(), "see INV-1", "INV-\\d", true, false));
    }

    @Test
    void yesNoCommands() {
        assertEquals(true, b.set(ctx(), "Yes", "no", null).get("result"));
        assertEquals(false, b.set(ctx(), d("0"), "no", null).get("result"));
        assertEquals(false, b.set(ctx(), "", "no", null).get("result"));
        assertEquals(true, b.set(ctx(), "", "yes", null).get("result"));
        assertEquals("BOOLEAN.EMPTY", code(() -> b.set(ctx(), null, "error", null)));
        assertEquals(true, b.set(ctx(), "maybe", "no", null).get("result"), "other text counts as Yes");
        assertEquals("BOOLEAN.INVALID", code(() -> b.set(ctx(), "maybe", "error", null)));
        assertEquals(true, b.set(ctx(), null, "no", true).get("result"));
        assertEquals(true, b.set(ctx(), null, "no", "1500 > 1000 and 'IN' == 'IN'").get("result"));
        assertEquals("BOOLEAN.BAD_FORMULA", code(() -> b.set(ctx(), null, "no", "1 >")));
        assertEquals(true, b.combine(ctx(), List.of(true, true), "all").get("result"));
        assertEquals(false, b.combine(ctx(), List.of(true, false), "all").get("result"));
        assertEquals(false, b.combine(ctx(), List.of(), "all").get("result"));
        assertEquals(true, b.combine(ctx(), List.of(false, true), "any").get("result"));
        assertEquals(false, b.combine(ctx(), List.of(true, true), "exactly_one").get("result"));
        assertEquals(true, b.combine(ctx(), List.of(false, false), "none").get("result"));
        num("2", b.combine(ctx(), List.of(true, false, true), "any").get("true_count"));
        assertEquals(true, b.fromNumber(ctx(), d("-3"), "non_zero").get("result"));
        assertEquals(false, b.fromNumber(ctx(), d("0"), "one_or_zero").get("result"));
        assertEquals("BOOLEAN.INVALID", code(() -> b.fromNumber(ctx(), d("2"), "one_or_zero")));
        assertTrue(b.isTrue(ctx(), "yes"));
        assertFalse(b.isTrue(ctx(), "No"));
        assertTrue(b.isFalse(ctx(), "0"));
        assertTrue(b.isFalse(ctx(), null));
        assertFalse(b.isFalse(ctx(), "anything"));
    }

    Object conv(Object v, String to) { return c.convert(ctx(), v, to, null, null, ",", "fail", null).get("result"); }

    @Test
    void convertValue() {
        num("1234.5", conv("1,234.50", "number"));
        num("0.15", conv("15%", "number"));
        num("99.9", conv("$ 99.90", "number"));
        num("1234.5", c.convert(ctx(), "1.234,50", "number", null, "de-DE", ",", "fail", null).get("result"));
        num("1", conv(true, "number"));
        assertEquals("CONVERT.FAILED", code(() -> conv("abc", "number")));
        Map<String, Object> fb = c.convert(ctx(), "abc", "number", null, null, ",", "fallback", d("0"));
        num("0", fb.get("result")); assertEquals(false, fb.get("ok"));
        assertEquals(true, conv("Yes", "boolean"));
        assertEquals(false, conv("", "boolean"));
        assertEquals(LocalDateTime.of(2026, 11, 16, 0, 0), c.convert(ctx(), "16/11/2026", "date_time", "dd/MM/yyyy", null, ",", "fail", null).get("result"));
        assertEquals(LocalDateTime.of(2026, 11, 16, 9, 30), c.convert(ctx(), "16/11/2026 09:30", "date_time", "dd/MM/yyyy HH:mm", null, ",", "fail", null).get("result"));
        assertEquals("CONVERT.FAILED", code(() -> c.convert(ctx(), "2026-11-16", "date_time", "dd/MM/yyyy", null, ",", "fail", null)));
        assertEquals("16 Nov 2026", c.convert(ctx(), LocalDateTime.of(2026, 11, 16, 9, 30), "text", "dd MMM yyyy", null, ",", "fail", null).get("result"));
        assertEquals("1.234,5", c.convert(ctx(), d("1234.5"), "text", null, "de-DE", ",", "fail", null).get("result"));
        assertEquals("a; b", c.convert(ctx(), List.of("a", "b"), "text", null, null, "; ", "fail", null).get("result"));
        assertEquals(List.of("a", "b", "c"), conv(" a , b,c ", "list"));
        assertEquals(List.of(d("1"), d("2")).toString(), conv("[1, 2]", "list").toString());
        assertEquals(Map.of("name", "Ann"), conv("{\"name\": \"Ann\"}", "dictionary"));
        assertEquals(Map.of("name", "Ann", "city", "Pune"), conv("name=Ann; city=Pune", "dictionary"));
        assertEquals("CONVERT.FAILED", code(() -> conv("just text", "dictionary")));
        assertEquals("{\"a\":1}", conv(Map.of("a", d("1")), "json_text"));
        assertEquals("[\"x\",\"y\"]", conv(List.of("x", "y"), "json_text"));
    }

    @Test
    void manifestsCarryTheNewCommands() {
        PackageIntrospector.PackageInfo num = PackageIntrospector.inspect(NumberPackage.class);
        for (String a : List.of("calculate", "statistics", "round_to_step", "format_as", "to_words")) assertTrue(num.actions().containsKey(a), a);
        for (String cnd : List.of("is_between", "compare")) assertTrue(num.conditions().containsKey(cnd), cnd);
        PackageIntrospector.PackageInfo str = PackageIntrospector.inspect(StringPackage.class);
        for (String a : List.of("join", "find_all", "extract_pattern", "remove_characters", "normalize_spaces", "insert", "remove_part", "repeat", "mask", "shorten",
                "count_words", "fill_template", "encode")) assertTrue(str.actions().containsKey(a), a);
        for (String cnd : List.of("starts_with", "ends_with", "contains", "is_empty", "matches_pattern")) assertTrue(str.conditions().containsKey(cnd), cnd);
        PackageIntrospector.PackageInfo bool = PackageIntrospector.inspect(BooleanPackage.class);
        for (String a : List.of("set", "combine", "from_number")) assertTrue(bool.actions().containsKey(a), a);
        assertTrue(PackageIntrospector.inspect(ConvertPackage.class).actions().containsKey("convert"));
        assertTrue(FirstPartyPackages.registry().allManifests().stream().anyMatch(m -> "ooa.convert".equals(m.get("id"))));
    }
}
