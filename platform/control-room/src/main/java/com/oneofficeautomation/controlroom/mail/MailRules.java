package com.oneofficeautomation.controlroom.mail;

import com.oneofficeautomation.controlroom.MailSource;
import com.oneofficeautomation.sdk.OoaException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Which emails a mail rule takes (MA-2; the design plan's MA-RuleEdit artboard). Conditions are
 * {@code {"match": "all" | "any", "when": [...], "except": [...]}}, each condition {@code {"field", "op", "value"}}
 * with value a word or a list (any of them):
 * <ul>
 *   <li>from: contains, is, domain_in (@supplier.com), not_contains; to (to and cc): contains, is;</li>
 *   <li>subject, body: contains, not_contains, regex;</li>
 *   <li>attachment_type: is_one_of (pdf, image, xml, office, zip, any); attachment_name: matches (INV-*.pdf);</li>
 *   <li>has_attachments: is (true or false); received_after: after (2026-11-01).</li>
 * </ul>
 * No condition takes every email (the "Everything else" rule at the end). Attachment conditions read the attachments
 * only when an earlier condition did not already decide.
 */
public final class MailRules {
    private MailRules() {}

    static final Map<String, Set<String>> OPS = Map.of(
            "from", Set.of("contains", "is", "domain_in", "not_contains"),
            "to", Set.of("contains", "is"),
            "subject", Set.of("contains", "not_contains", "regex"),
            "body", Set.of("contains", "not_contains", "regex"),
            "attachment_type", Set.of("is_one_of"),
            "attachment_name", Set.of("matches"),
            "has_attachments", Set.of("is"),
            "received_after", Set.of("after"));
    /** The names people see in the rule builder, used in messages. */
    static final Map<String, String> LABELS = Map.of(
            "from", "From", "to", "To or Cc", "subject", "Subject", "body", "Text", "attachment_type", "Attachment type",
            "attachment_name", "Attachment name", "has_attachments", "Has attachments", "received_after", "Received after");
    static final Map<String, Set<String>> TYPES = Map.of(
            "pdf", Set.of("pdf"), "image", Set.of("png", "jpg", "jpeg", "tif", "tiff", "bmp", "gif", "webp", "heic"),
            "xml", Set.of("xml"), "office", Set.of("doc", "docx", "xls", "xlsx", "xlsm", "csv", "ppt", "pptx"),
            "zip", Set.of("zip", "7z", "rar"));

    /** Checks conditions written by a person and returns them cleaned (match, when, except). */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> check(Object raw) {
        Map<String, Object> in = raw instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        String match = String.valueOf(in.getOrDefault("match", "all"));
        if (!match.equals("all") && !match.equals("any")) throw bad("match is all (every condition) or any (one of them)");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("match", match);
        out.put("when", checkList(in.get("when")));
        out.put("except", checkList(in.get("except")));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> checkList(Object raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (raw == null) return out;
        if (!(raw instanceof List<?> l)) throw bad("Conditions are a list");
        if (l.size() > 30) throw bad("A rule has at most 30 conditions");
        for (Object o : l) {
            if (!(o instanceof Map<?, ?> c)) throw bad("Each condition has field, op and value");
            String field = String.valueOf(c.get("field")), op = String.valueOf(c.get("op"));
            if (!OPS.containsKey(field)) throw bad("'" + field + "' is not something a rule can look at; use one of " + new TreeSet<>(OPS.keySet()));
            if (!OPS.get(field).contains(op)) throw bad(LABELS.get(field) + " can be checked with " + String.join(", ", new TreeSet<>(OPS.get(field))) + ", not " + op);
            List<String> values = values(c.get("value"));
            if (values.isEmpty()) throw bad("Give a value for " + LABELS.get(field) + " (condition " + (out.size() + 1) + ")");
            for (String v : values) {
                if (v.length() > 300) throw bad("A value has at most 300 characters");
                if (op.equals("regex")) try { Pattern.compile(v); } catch (PatternSyntaxException e) { throw bad("'" + v + "' is not a valid pattern: " + e.getDescription()); }
                if (field.equals("attachment_type") && !v.equals("any") && !TYPES.containsKey(v)) throw bad("Attachment types are pdf, image, xml, office, zip or any");
                if (field.equals("received_after")) try { LocalDate.parse(v); } catch (RuntimeException e) { throw bad("Received after is a date such as 2026-11-01"); }
                if (field.equals("has_attachments") && !v.equals("true") && !v.equals("false")) throw bad("Has attachments is true or false");
            }
            out.add(new LinkedHashMap<>(Map.of("field", field, "op", op, "value", values.size() == 1 && !(c.get("value") instanceof List<?>) ? values.get(0) : values)));
        }
        return out;
    }

    /** Does the email match? attachments is asked only when an attachment condition must decide. */
    @SuppressWarnings("unchecked")
    public static boolean matches(Map<String, Object> conditions, Map<String, Object> msg, Supplier<List<MailSource.Attachment>> attachments) {
        List<Map<String, Object>> when = (List<Map<String, Object>>) conditions.getOrDefault("when", List.of());
        List<Map<String, Object>> except = (List<Map<String, Object>>) conditions.getOrDefault("except", List.of());
        Supplier<List<MailSource.Attachment>> once = memo(attachments);
        boolean ok;
        if (when.isEmpty()) ok = true;
        else if ("any".equals(conditions.get("match"))) ok = when.stream().anyMatch(c -> test(c, msg, once));
        else ok = when.stream().allMatch(c -> test(c, msg, once));
        return ok && except.stream().noneMatch(c -> test(c, msg, once));
    }

    static boolean test(Map<String, Object> c, Map<String, Object> msg, Supplier<List<MailSource.Attachment>> attachments) {
        String field = String.valueOf(c.get("field")), op = String.valueOf(c.get("op"));
        List<String> values = values(c.get("value"));
        return switch (field) {
            case "from" -> text(op, low(msg.get("from")), values, true);
            case "to" -> text(op, low(msg.get("to")) + " " + low(msg.get("cc")), values, true);
            case "subject" -> text(op, String.valueOf(Objects.requireNonNullElse(msg.get("subject"), "")), values, false);
            case "body" -> text(op, String.valueOf(Objects.requireNonNullElse(msg.get("body_preview"), "")), values, false);
            case "has_attachments" -> Boolean.parseBoolean(values.get(0)) == hasAttachments(msg);
            case "attachment_type" -> hasAttachments(msg) && attachments.get().stream().anyMatch(a -> values.stream().anyMatch(t -> isType(a, t)));
            case "attachment_name" -> hasAttachments(msg) && attachments.get().stream().anyMatch(a -> values.stream().anyMatch(g -> glob(g).matcher(a.name().toLowerCase(Locale.ROOT)).matches()));
            case "received_after" -> received(msg).map(t -> t.isAfter(LocalDate.parse(values.get(0)).atStartOfDay().toInstant(ZoneOffset.UTC))).orElse(false);
            default -> false;
        };
    }

    private static boolean text(String op, String value, List<String> words, boolean address) {
        String v = value.toLowerCase(Locale.ROOT);
        return switch (op) {
            case "contains" -> words.stream().anyMatch(w -> v.contains(w.toLowerCase(Locale.ROOT)));
            case "not_contains" -> words.stream().noneMatch(w -> v.contains(w.toLowerCase(Locale.ROOT)));
            case "is" -> words.stream().anyMatch(w -> addresses(v).contains(w.toLowerCase(Locale.ROOT).trim()) || v.trim().equals(w.toLowerCase(Locale.ROOT).trim()));
            case "domain_in" -> words.stream().anyMatch(w -> { String d = w.toLowerCase(Locale.ROOT).trim().replaceFirst("^@", ""); return addresses(v).stream().anyMatch(a -> a.endsWith("@" + d) || a.endsWith("." + d)); });
            case "regex" -> words.stream().anyMatch(w -> Pattern.compile(w, Pattern.CASE_INSENSITIVE).matcher(value).find());
            default -> false;
        };
    }

    /** The plain addresses in "Ann <ann@x.com>, bob@y.com". */
    static List<String> addresses(String s) {
        List<String> out = new ArrayList<>();
        var m = Pattern.compile("[^\\s<>,;\"']+@[^\\s<>,;\"']+").matcher(s);
        while (m.find()) out.add(m.group().toLowerCase(Locale.ROOT));
        return out;
    }

    static boolean isType(MailSource.Attachment a, String type) {
        if (type.equals("any")) return true;
        String n = a.name().toLowerCase(Locale.ROOT);
        String ext = n.contains(".") ? n.substring(n.lastIndexOf('.') + 1) : "";
        String ct = a.contentType() == null ? "" : a.contentType().toLowerCase(Locale.ROOT);
        return TYPES.getOrDefault(type, Set.of()).contains(ext) || (type.equals("pdf") && ct.contains("pdf")) || (type.equals("image") && ct.startsWith("image/"))
                || (type.equals("xml") && ct.contains("xml"));
    }

    static Pattern glob(String g) {
        StringBuilder sb = new StringBuilder();
        for (char ch : g.toLowerCase(Locale.ROOT).trim().toCharArray()) sb.append(ch == '*' ? ".*" : ch == '?' ? "." : Pattern.quote(String.valueOf(ch)));
        return Pattern.compile(sb.toString());
    }

    static boolean hasAttachments(Map<String, Object> msg) {
        Object h = msg.get("has_attachments");
        return h == null || Boolean.TRUE.equals(h) || "true".equals(String.valueOf(h));
    }

    static Optional<Instant> received(Map<String, Object> msg) {
        Object d = msg.get("date");
        if (d == null) return Optional.empty();
        String s = String.valueOf(d);
        try { return Optional.of(Instant.parse(s)); } catch (RuntimeException ignored) { }
        try { return Optional.of(ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()); } catch (RuntimeException ignored) { }
        return Optional.empty();
    }

    static List<String> values(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> l) { for (Object o : l) if (o != null && !String.valueOf(o).isBlank()) out.add(String.valueOf(o).trim()); }
        else if (v != null) for (String s : String.valueOf(v).split(",")) if (!s.isBlank()) out.add(s.trim());
        return out;
    }

    private static String low(Object o) { return o == null ? "" : String.valueOf(o).toLowerCase(Locale.ROOT); }

    private static <T> Supplier<T> memo(Supplier<T> s) {
        return new Supplier<>() {
            T value; boolean done;
            public synchronized T get() { if (!done) { value = s.get(); done = true; } return value; }
        };
    }

    /** A short sentence for people: "From @acme.com · has a PDF · not from noreply@". */
    @SuppressWarnings("unchecked")
    public static String describe(Map<String, Object> conditions) {
        List<String> parts = new ArrayList<>();
        for (Map<String, Object> c : (List<Map<String, Object>>) conditions.getOrDefault("when", List.of())) parts.add(say(c));
        String joined = String.join("any".equals(conditions.get("match")) ? " or " : " · ", parts);
        for (Map<String, Object> c : (List<Map<String, Object>>) conditions.getOrDefault("except", List.of())) joined += (joined.isEmpty() ? "" : " · ") + "except " + say(c);
        return joined.isEmpty() ? "Every email" : joined;
    }

    private static String say(Map<String, Object> c) {
        String v = String.join(" or ", values(c.get("value")));
        return switch (c.get("field") + ":" + c.get("op")) {
            case "from:domain_in" -> "from " + v;
            case "from:contains", "from:is" -> "from " + v;
            case "from:not_contains" -> "not from " + v;
            case "to:contains", "to:is" -> "to " + v;
            case "subject:contains" -> "subject has " + v;
            case "subject:not_contains" -> "subject without " + v;
            case "body:contains" -> "text has " + v;
            case "body:not_contains" -> "text without " + v;
            case "attachment_type:is_one_of" -> "has a " + v.toUpperCase(Locale.ROOT).replace(" OR ", " or ") + " file";
            case "attachment_name:matches" -> "a file named " + v;
            case "has_attachments:is" -> Boolean.parseBoolean(v) ? "has attachments" : "no attachments";
            case "received_after:after" -> "received after " + v;
            default -> c.get("field") + " " + c.get("op") + " " + v;
        };
    }

    static OoaException bad(String m) { return new OoaException("CR.BAD_INPUT", m); }
}
