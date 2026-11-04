package com.oneofficeautomation.packages;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds personal data in text without a model (G15, Detect personal data): emails, phone numbers, card numbers (Luhn),
 * IBANs (mod 97), IP addresses, US social security numbers, Indian PAN and Aadhaar (Verhoeff) numbers, and masks them.
 * The kind names match the AI guardrails' patterns (LlmClient.PII) in lower case; the rules here are stricter because
 * a match is reported to the bot, not just hidden from a model. Names and addresses come from a model (GenAiPackage).
 */
final class PiiDetector {
    private PiiDetector() {}

    /** A piece of personal data: its kind, the text as written and where it is (start inclusive, end exclusive, from 0). */
    record Item(String kind, String text, int start, int end) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", kind);
            m.put("text", text);
            m.put("start", java.math.BigDecimal.valueOf(start));
            m.put("end", java.math.BigDecimal.valueOf(end));
            return m;
        }
    }

    /** The kinds found by pattern, in the order they win when two overlap (checksummed kinds first, phone last). */
    static final List<String> PATTERN_KINDS = List.of("email", "credit_card", "iban", "in_aadhaar", "us_ssn", "in_pan", "ip_address", "phone");
    /** The kinds only a model can find. */
    static final List<String> AI_KINDS = List.of("person_name", "address");
    static final List<String> ALL_KINDS = List.of("email", "phone", "credit_card", "iban", "ip_address", "us_ssn", "in_pan", "in_aadhaar", "person_name", "address");

    private static final Pattern EMAIL = Pattern.compile("(?<![\\w.%+-])[A-Za-z0-9][A-Za-z0-9._%+-]*@(?:[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?\\.)+[A-Za-z]{2,24}(?![\\w-])");
    private static final Pattern CARD = Pattern.compile("(?<![\\w.-])(?:\\d{4}(?:[ -]\\d{4}){2}[ -]\\d{1,7}|\\d{4}[ -]\\d{6}[ -]\\d{4,5}|\\d{13,19})(?![\\w-])(?![.,]\\d)");
    private static final Pattern IBAN = Pattern.compile("(?<![\\w-])[A-Z]{2}\\d{2}(?: ?[A-Z0-9]{4}){2,7}(?: ?[A-Z0-9]{1,4})?(?![\\w-])");
    private static final Pattern AADHAAR = Pattern.compile("(?<![\\w.-])(?<!\\d )[2-9]\\d{3}([ -]?)\\d{4}\\1\\d{4}(?![\\w-])(?! \\d)(?![.,]\\d)");
    private static final Pattern SSN = Pattern.compile("(?<![\\w.-])(\\d{3})-(\\d{2})-(\\d{4})(?![\\w-])(?![.,]\\d)");
    private static final Pattern PAN = Pattern.compile("(?<![\\w-])[A-Z]{3}[ABCFGHJLPT][A-Z]\\d{4}[A-Z](?![\\w-])");
    private static final String OCTET = "(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)";
    private static final Pattern IPV4 = Pattern.compile("(?<![\\w.])" + OCTET + "(?:\\." + OCTET + "){3}(?![\\w])(?!\\.\\d)");
    private static final Pattern IPV6 = Pattern.compile("(?<![\\w:.])[0-9A-Fa-f]{0,4}(?::[0-9A-Fa-f]{0,4}){2,7}(?![\\w:])");
    private static final Pattern PHONE = Pattern.compile("(?<![\\w+.,/-])(\\+\\d{1,3}[ .-]?)?(\\(\\d{1,5}\\)[ .-]?)?\\d{1,14}(?:[ .-]\\d{1,14})*(?![\\w])(?![.,-]\\d)");
    private static final Pattern DATE_LIKE = Pattern.compile("\\d{4}[-./]\\d{1,2}[-./]\\d{1,2}|\\d{1,2}[-./]\\d{1,2}[-./]\\d{2,4}");

    // ---------- checksums ----------

    static boolean luhn(String digits) { return LlmClient.luhn(digits); }

    /** IBAN check: the country and check digits moved to the end, letters as 10..35, the number mod 97 is 1. */
    static boolean ibanValid(String iban) {
        String s = iban.replace(" ", "");
        if (s.length() < 15 || s.length() > 34) return false;
        String r = s.substring(4) + s.substring(0, 4);
        int mod = 0;
        for (char ch : r.toCharArray()) {
            int v = Character.isDigit(ch) ? ch - '0' : ch >= 'A' && ch <= 'Z' ? ch - 'A' + 10 : -1;
            if (v < 0) return false;
            mod = (v > 9 ? mod * 100 + v : mod * 10 + v) % 97;
        }
        return mod == 1;
    }

    private static final int[][] VD = {{0,1,2,3,4,5,6,7,8,9},{1,2,3,4,0,6,7,8,9,5},{2,3,4,0,1,7,8,9,5,6},{3,4,0,1,2,8,9,5,6,7},{4,0,1,2,3,9,5,6,7,8},
            {5,9,8,7,6,0,4,3,2,1},{6,5,9,8,7,1,0,4,3,2},{7,6,5,9,8,2,1,0,4,3},{8,7,6,5,9,3,2,1,0,4},{9,8,7,6,5,4,3,2,1,0}};
    private static final int[][] VP = {{0,1,2,3,4,5,6,7,8,9},{1,5,7,6,2,8,3,0,9,4},{5,8,0,3,7,9,6,1,4,2},{8,9,1,6,0,4,3,5,2,7},
            {9,4,5,3,1,2,7,6,8,0},{4,2,8,6,5,7,3,9,0,1},{2,7,9,3,8,0,6,4,1,5},{7,0,4,6,9,1,5,2,3,8}};

    /** Verhoeff check (Aadhaar's last digit). */
    static boolean verhoeff(String digits) {
        int c = 0;
        for (int i = 0; i < digits.length(); i++) c = VD[c][VP[i % 8][digits.charAt(digits.length() - 1 - i) - '0']];
        return c == 0;
    }

    // ---------- finding ----------

    /** Every pattern match in the text, overlaps settled (see PATTERN_KINDS), sorted by position. */
    static List<Item> find(String text) {
        List<Item> c = new ArrayList<>();
        if (text == null || text.isEmpty()) return c;
        add(c, "email", EMAIL, text, v -> true);
        add(c, "credit_card", CARD, text, v -> { String d = v.replaceAll("\\D", ""); return d.length() >= 13 && d.length() <= 19 && d.charAt(0) != '0' && !d.chars().allMatch(ch -> ch == d.charAt(0)) && luhn(d); });
        iban(c, text);
        add(c, "in_aadhaar", AADHAAR, text, v -> { String d = v.replaceAll("\\D", ""); return !d.chars().allMatch(ch -> ch == d.charAt(0)) && verhoeff(d); });
        add(c, "us_ssn", SSN, text, v -> { String[] p = v.split("-"); int area = Integer.parseInt(p[0]);
            return area != 0 && area != 666 && area < 900 && !p[1].equals("00") && !p[2].equals("0000"); });
        add(c, "in_pan", PAN, text, v -> true);
        add(c, "ip_address", IPV4, text, v -> true);
        add(c, "ip_address", IPV6, text, PiiDetector::ipv6);
        List<Item> phones = new ArrayList<>();
        add(phones, "phone", PHONE, text, PiiDetector::phone);
        // digits inside an IBAN-shaped string that failed its check are part of a bad IBAN, not a phone number
        List<int[]> ibanShapes = new ArrayList<>();
        for (Matcher m = IBAN.matcher(text); m.find(); ) ibanShapes.add(new int[]{m.start(), m.end()});
        for (Item p : phones) if (ibanShapes.stream().noneMatch(s -> p.start() < s[1] && s[0] < p.end())) c.add(p);
        return settle(c, List.of());
    }

    private interface Check { boolean ok(String v); }

    private static void add(List<Item> out, String kind, Pattern p, String text, Check check) {
        Matcher m = p.matcher(text);
        while (m.find()) if (check.ok(m.group())) out.add(new Item(kind, m.group(), m.start(), m.end()));
    }

    /** IBANs written in groups may run into the next word; the longest prefix that passes mod 97 is the IBAN. */
    private static void iban(List<Item> out, String text) {
        Matcher m = IBAN.matcher(text);
        while (m.find()) {
            String v = m.group();
            for (int end = v.length(); end >= 15; end--) {
                if (end < v.length() && v.charAt(end) != ' ') continue;   // cut only between groups
                String cand = v.substring(0, end).strip();
                if (ibanValid(cand)) { out.add(new Item("iban", cand, m.start(), m.start() + cand.length())); break; }
            }
        }
    }

    private static boolean ipv6(String v) {
        if (!v.contains("::") && v.chars().filter(ch -> ch == ':').count() != 7) return false;
        if (v.indexOf("::") != v.lastIndexOf("::") || v.contains(":::")) return false;
        if (v.replace(":", "").length() < 2) return false;   // "::" or "a::" alone is not worth reporting
        for (String g : v.split(":", -1)) if (g.length() > 4) return false;
        return true;
    }

    /** A phone number has 7 to 15 digits and is not a date, an IP address, a decimal or a thousands-grouped amount. */
    static boolean phone(String v) {
        String d = v.replaceAll("\\D", "");
        if (d.length() < 7 || d.length() > 15) return false;
        boolean plus = v.startsWith("+"), paren = v.contains("(");
        String body = v.replaceFirst("^\\+\\d{1,3}[ .-]?", "");
        String[] groups = body.replaceAll("[()]", " ").trim().split("[ .-]+");
        boolean separated = !v.matches("\\+?\\d+");
        if (!plus && !paren && !separated) return d.length() >= 10 && d.length() <= 11;   // a bare number: only mobile-like lengths
        if (DATE_LIKE.matcher(v).matches() || v.matches("\\d{1,3}(\\.\\d{1,3}){3}") || v.matches("\\d{3}-\\d{2}-\\d{4}")) return false;
        if (!plus && !paren && v.matches("\\d{4}([ -]\\d{4}){2,}")) return false;   // 4-4-4: a card, Aadhaar or account number shape
        if (v.contains(".") && !plus && !paren && (groups.length < 3 || v.matches(".*[ -].*"))) return false;   // 1234567.89, 12.50 1234
        for (int i = 1; i < groups.length; i++) if (groups[i].length() < 2) return false;   // 1 2 3 4 5 6 7
        if (!plus && !paren && groups.length >= 2 && groups[0].length() <= 3 && v.matches("[\\d .]+")) {   // 1 234 567 or 1.234.567: an amount
            boolean thousands = true;
            for (int i = 1; i < groups.length; i++) if (groups[i].length() != 3) thousands = false;
            if (thousands) return false;
        }
        return true;
    }

    /**
     * Drops candidates that overlap one already taken: the given (pattern) items are taken first in kind order, then
     * the extra (model) items. The result is sorted by position.
     */
    static List<Item> settle(List<Item> candidates, List<Item> extra) {
        List<Item> ordered = new ArrayList<>(candidates);
        ordered.sort(Comparator.comparingInt((Item i) -> PATTERN_KINDS.indexOf(i.kind())).thenComparingInt(i -> -(i.end() - i.start())).thenComparingInt(Item::start));
        List<Item> e = new ArrayList<>(extra);
        e.sort(Comparator.comparingInt((Item i) -> -(i.end() - i.start())).thenComparingInt(Item::start));
        ordered.addAll(e);
        List<Item> taken = new ArrayList<>();
        for (Item i : ordered) {
            boolean clash = false;
            for (Item t : taken) if (i.start() < t.end() && t.start() < i.end()) { clash = true; break; }
            if (!clash) taken.add(i);
        }
        taken.sort(Comparator.comparingInt(Item::start));
        return taken;
    }

    /** Every place a model's answer occurs in the text, on word boundaries. */
    static List<Item> locate(String text, String kind, String value) {
        List<Item> out = new ArrayList<>();
        String v = value == null ? "" : value.strip();
        if (v.length() < 2) return out;
        for (int at = text.indexOf(v); at >= 0; at = text.indexOf(v, at + 1)) {
            int end = at + v.length();
            boolean startOk = at == 0 || !Character.isLetterOrDigit(text.charAt(at - 1)) || !Character.isLetterOrDigit(v.charAt(0));
            boolean endOk = end == text.length() || !Character.isLetterOrDigit(text.charAt(end)) || !Character.isLetterOrDigit(v.charAt(v.length() - 1));
            if (startOk && endOk) out.add(new Item(kind, v, at, end));
        }
        return out;
    }

    // ---------- masking ----------

    /** The text with each item masked: full replaces every character, partial keeps a few so a person can still tell them apart. */
    static String mask(String text, List<Item> items, String maskChar, boolean partial) {
        StringBuilder sb = new StringBuilder();
        int at = 0;
        for (Item i : items) {
            sb.append(text, at, i.start()).append(partial ? partial(i, maskChar) : maskChar.repeat(i.text().codePointCount(0, i.text().length())));
            at = i.end();
        }
        return sb.append(text.substring(at)).toString();
    }

    /**
     * Partial masks: an email keeps the first character and the domain (j***@acme.com); card, Aadhaar, SSN and phone
     * numbers keep their last 4 digits; an IBAN its country code and last 4 characters; anything else its first and
     * last letter or digit. Spaces and punctuation stay.
     */
    static String partial(Item i, String mc) {
        String v = i.text();
        switch (i.kind()) {
            case "email" -> {
                int a = v.indexOf('@');
                return v.charAt(0) + mc.repeat(Math.max(0, a - 1)) + v.substring(a);
            }
            case "credit_card", "in_aadhaar", "us_ssn", "phone" -> { return keep(v, mc, 0, 4); }
            case "iban" -> { return keep(v, mc, 2, 4); }
            default -> { return keep(v, mc, 1, 1); }
        }
    }

    /** Masks the letters and digits of v except the first `first` and the last `last` of them. */
    private static String keep(String v, String mc, int first, int last) {
        int total = 0;
        for (int k = 0; k < v.length(); k++) if (Character.isLetterOrDigit(v.charAt(k))) total++;
        if (first + last >= total) { first = Math.min(first, Math.max(0, total - 1)); last = Math.max(0, Math.min(last, total - 1 - first)); }
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (int k = 0; k < v.length(); k++) {
            char ch = v.charAt(k);
            if (!Character.isLetterOrDigit(ch)) { sb.append(ch); continue; }
            sb.append(n < first || n >= total - last ? String.valueOf(ch) : mc);
            n++;
        }
        return sb.toString();
    }
}
