package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;

import java.util.*;
import java.util.regex.PatternSyntaxException;

@OoaPackage(id = "ooa.string", name = "String", version = "1.1.0", description = "Text manipulation: extract, replace, split, join, clean, mask, fill templates, encode and change case.")
public final class StringPackage {

    @Action(id = "extract_text", name = "Extract text", category = "Text", idempotent = true,
            description = "Return the text before, after or between markers.",
            panel = "text, after, after_occurrence, before, before_occurrence, if_not_found, [Limit the length: max_length], ignore_case, trim, remove_line_breaks | until")
    @Output(name = "result", type = OoaType.STRING, description = "Extracted text, empty when a marker is missing.")
    public Map<String, Object> extractText(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Source text.") String text,
            @Input(name = "before", type = OoaType.STRING, required = false, description = "Return text before this marker.") String before,
            @Input(name = "after", type = OoaType.STRING, required = false, description = "Return text after this marker.") String after,
            @Input(name = "trim", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Trim whitespace from the result.") boolean trim,
            @Input(name = "after_occurrence", type = OoaType.NUMBER, required = false, defaultValue = "1", visibleWhen = "after!=", min = 1, description = "Which occurrence of the 'after' marker to start from (1 = first).") int afterOccurrence,
            @Input(name = "before_occurrence", type = OoaType.NUMBER, required = false, defaultValue = "1", visibleWhen = "before!=", min = 1, description = "Which occurrence of the 'before' marker to stop at (1 = first).") int beforeOccurrence,
            @Input(name = "ignore_case", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Find the markers without regard to upper/lower case.") boolean ignoreCase,
            @Input(name = "if_not_found", type = OoaType.STRING, required = false, defaultValue = "\"empty\"", enumValues = {"empty", "source"},
                    description = "Result when a marker is missing: empty text, or the whole source text.") String ifNotFound,
            @Input(name = "max_length", type = OoaType.NUMBER, required = false, min = 0, description = "Keep at most this many characters of the result.") Integer maxLength,
            @Input(name = "remove_line_breaks", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Remove line breaks from the result.") boolean removeLineBreaks,
            @Input(name = "until", type = OoaType.STRING, required = false, defaultValue = "\"marker\"", enumValues = {"marker", "end_of_line", "end_of_text"},
                    description = "Where the result ends when the 'before' marker is empty or not found: as before (marker), at the end of the line, or at the end of the text.") String until) {
        if (afterOccurrence < 1 || beforeOccurrence < 1) throw new ActionException("STRING.BAD_OCCURRENCE", "Occurrences start at 1", "Use 1 for the first match.");
        String stop = until == null ? "marker" : until;
        if (!List.of("marker", "end_of_line", "end_of_text").contains(stop))
            throw new ActionException("STRING.BAD_OPTION", "Unknown until option '" + until + "'", "Use marker, end_of_line or end_of_text.");
        String out = text;
        boolean missing = false;
        if (after != null) { int i = nth(out, after, afterOccurrence, ignoreCase); if (i >= 0) out = out.substring(i + after.length()); else { out = ""; missing = true; } }
        int cut = before == null || missing ? -1 : nth(out, before, beforeOccurrence, ignoreCase);
        if (cut >= 0) out = out.substring(0, cut);
        else if (!missing && stop.equals("end_of_line")) { int nl = out.indexOf('\n'), cr = out.indexOf('\r'); int e = nl < 0 ? cr : cr < 0 ? nl : Math.min(nl, cr); if (e >= 0) out = out.substring(0, e); }
        else if (before != null && !missing && after == null && stop.equals("marker")) { out = ""; missing = true; }
        if (missing && "source".equals(ifNotFound)) out = text;
        if (removeLineBreaks) out = out.replace("\r", "").replace("\n", "");
        if (trim) out = stripSpace(out);
        if (maxLength != null) {
            if (maxLength < 0) throw new ActionException("STRING.BAD_LENGTH", "max_length cannot be negative", "Use 0 or more.");
            if (out.length() > maxLength) out = out.substring(0, maxLength);
        }
        return Map.of("result", out);
    }

    public Map<String, Object> extractText(ActionContext ctx, String text, String before, String after, boolean trim, int afterOccurrence, int beforeOccurrence,
            boolean ignoreCase, String ifNotFound, Integer maxLength, boolean removeLineBreaks) {
        return extractText(ctx, text, before, after, trim, afterOccurrence, beforeOccurrence, ignoreCase, ifNotFound, maxLength, removeLineBreaks, "marker");
    }

    /** Position of the n-th (1-based) occurrence of a marker, or -1. */
    static int nth(String text, String marker, int n, boolean ignoreCase) {
        if (marker.isEmpty()) return 0;
        String t = ignoreCase ? text.toLowerCase(Locale.ROOT) : text, m = ignoreCase ? marker.toLowerCase(Locale.ROOT) : marker;
        int from = 0, i = -1;
        for (int k = 0; k < n; k++) {
            i = t.indexOf(m, from);
            if (i < 0) return -1;
            from = i + m.length();
        }
        return i;
    }

    @Action(id = "replace", name = "Replace", category = "Text", idempotent = true, description = "Replace text, optionally using a regular expression.",
            panel = "text, find, replace_with | ignore_case, regex, start, count")
    @Output(name = "result", type = OoaType.STRING, description = "Text after replacement.")
    public Map<String, Object> replace(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Source text.") String text,
            @Input(name = "find", type = OoaType.STRING, description = "Text or regex to find.") String find,
            @Input(name = "replace_with", type = OoaType.STRING, required = false, defaultValue = "\"\"", description = "Replacement text.") String with,
            @Input(name = "regex", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Treat 'find' as a regular expression.") boolean regex,
            @Input(name = "ignore_case", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Match without regard to upper/lower case.") boolean ignoreCase,
            @Input(name = "start", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0, description = "Zero-based position to start replacing from; text before it is kept as is.") int start,
            @Input(name = "count", type = OoaType.NUMBER, required = false, defaultValue = "-1", min = -1, description = "Replace at most this many matches; -1 replaces all.") int count) {
        if (start < 0 || start > text.length())
            throw new ActionException("STRING.BAD_POSITION", "Start position " + start + " is outside the text (length " + text.length() + ")", "Use a position from 0 to the text length.");
        try {
            if (!ignoreCase && start == 0 && count < 0) return Map.of("result", regex ? text.replaceAll(find, with) : text.replace(find, with));
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(regex ? find : java.util.regex.Pattern.quote(find),
                    ignoreCase ? java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE : 0).matcher(text);
            String repl = regex ? with : java.util.regex.Matcher.quoteReplacement(with);
            StringBuilder sb = new StringBuilder();
            int done = 0;
            boolean found = m.find(start);
            while (found && (count < 0 || done < count)) {
                m.appendReplacement(sb, repl);
                done++;
                found = m.find();
            }
            m.appendTail(sb);
            return Map.of("result", sb.toString());
        } catch (PatternSyntaxException e) {
            throw new ActionException("STRING.BAD_REGEX", "Invalid regular expression: " + e.getDescription(), "Escape special characters such as ( ) [ ] . *");
        }
    }

    @Action(id = "split", name = "Split", category = "Text", idempotent = true, description = "Split text into a list by a delimiter.",
            panel = "text, delimiter, ignore_case, max_parts | trim_items, regex, remove_empty")
    @Output(name = "items", type = OoaType.LIST, description = "List of parts.")
    public Map<String, Object> split(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Source text.") String text,
            @Input(name = "delimiter", type = OoaType.STRING, description = "Delimiter, e.g. ','.") String delimiter,
            @Input(name = "trim_items", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Trim each item.") boolean trimItems,
            @Input(name = "max_parts", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0, description = "Split into at most this many parts; the last part keeps the rest. 0 splits at every delimiter.") int maxParts,
            @Input(name = "ignore_case", type = OoaType.BOOLEAN, required = false, defaultValue = "false", visibleWhen = "regex=false", description = "Match a letter delimiter without regard to upper/lower case.") boolean ignoreCase,
            @Input(name = "regex", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Treat the delimiter as a regular expression, e.g. \\R for any line break.") boolean regex,
            @Input(name = "remove_empty", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Leave out empty parts.") boolean removeEmpty) {
        if (maxParts < 0) throw new ActionException("STRING.BAD_COUNT", "max_parts cannot be negative", "Use 0 for all parts.");
        java.util.regex.Pattern p;
        try {
            p = java.util.regex.Pattern.compile(regex ? delimiter : java.util.regex.Pattern.quote(delimiter),
                    ignoreCase ? java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE : 0);
        } catch (PatternSyntaxException e) {
            throw new ActionException("STRING.BAD_REGEX", "Invalid regular expression: " + e.getDescription(), "Escape special characters such as ( ) [ ] . *");
        }
        List<Object> items = new ArrayList<>();
        for (String part : p.split(text, maxParts == 0 ? -1 : maxParts)) {
            String v = trimItems ? stripSpace(part) : part;
            if (!(removeEmpty && v.isEmpty())) items.add(v);
        }
        return Map.of("items", items);
    }

    @Action(id = "change_case", name = "Change case", category = "Text", idempotent = true, description = "Convert text to upper, lower, title or sentence case, or to camelCase, PascalCase, snake_case or kebab-case for names and keys.",
            panel = "text, case")
    @Output(name = "result", type = OoaType.STRING, description = "Converted text.")
    public Map<String, Object> changeCase(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Source text.") String text,
            @Input(name = "case", type = OoaType.STRING, enumValues = {"upper", "lower", "title", "sentence", "camel", "pascal", "snake", "kebab"}, description = "Target case: camel is invoiceTotal, pascal InvoiceTotal, snake invoice_total, kebab invoice-total.") String kase) {
        String r = switch (kase) {
            case "upper" -> text.toUpperCase(Locale.ROOT);
            case "lower" -> text.toLowerCase(Locale.ROOT);
            case "sentence" -> text.isEmpty() ? text : text.substring(0, 1).toUpperCase(Locale.ROOT) + text.substring(1).toLowerCase(Locale.ROOT);
            case "camel", "pascal", "snake", "kebab" -> codeCase(text, kase);
            default -> {
                StringBuilder sb = new StringBuilder();
                boolean up = true;
                for (char c : text.toCharArray()) { sb.append(up ? Character.toUpperCase(c) : Character.toLowerCase(c)); up = !Character.isLetter(c); }
                yield sb.toString();
            }
        };
        return Map.of("result", r);
    }

    @Condition(id = "is_number", name = "Text is a number", description = "True when the text is a number, e.g. 1,250.50 or -3.")
    public boolean isNumber(ActionContext ctx, @Input(name = "text", type = OoaType.STRING, description = "Text to check.") String text) {
        try { new java.math.BigDecimal(text.trim().replace(",", "")); return true; } catch (NumberFormatException e) { return false; }
    }

    @Condition(id = "is_email", name = "Text is an email address", description = "True when the text looks like an email address.")
    public boolean isEmail(ActionContext ctx, @Input(name = "text", type = OoaType.STRING, description = "Text to check.") String text) {
        return text.trim().matches("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    }

    // ---- Comparison, search and measurement ----

    @Action(id = "compare", name = "Compare", category = "Text", idempotent = true,
            description = "Compare two texts exactly, ignoring case, or in natural order where numbers inside the text are compared by value.",
            panel = "text, other, mode | ignore_line_breaks")
    @Output(name = "result", type = OoaType.NUMBER, description = "-1 when the first text comes first, 0 when they are equal, 1 when it comes after.")
    @Output(name = "equal", type = OoaType.BOOLEAN, description = "True when the texts are equal under the chosen mode.")
    public Map<String, Object> compare(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "First text.") String text,
            @Input(name = "other", type = OoaType.STRING, description = "Second text.") String other,
            @Input(name = "mode", type = OoaType.STRING, required = false, defaultValue = "\"exact\"", enumValues = {"exact", "ignore_case", "natural"},
                    description = "exact, ignore_case, or natural (file2 before file10, case ignored).") String mode,
            @Input(name = "ignore_line_breaks", type = OoaType.BOOLEAN, required = false, defaultValue = "false",
                    description = "Treat each line break (\\r\\n or \\n) as a space, so text wrapped onto several lines still matches.") boolean ignoreLineBreaks) {
        String a = text == null ? "" : text, b = other == null ? "" : other;
        if (ignoreLineBreaks) { a = a.replaceAll("\\r\\n|\\r|\\n", " "); b = b.replaceAll("\\r\\n|\\r|\\n", " "); }
        int r = switch (mode == null ? "exact" : mode) {
            case "ignore_case" -> a.compareToIgnoreCase(b);
            case "natural" -> naturalCompare(a, b);
            case "exact" -> a.compareTo(b);
            default -> throw new ActionException("STRING.BAD_MODE", "Unknown compare mode '" + mode + "'", "Use exact, ignore_case or natural.");
        };
        return Out.of("result", java.math.BigDecimal.valueOf(Integer.signum(r)), "equal", r == 0);
    }

    public Map<String, Object> compare(ActionContext ctx, String text, String other, String mode) { return compare(ctx, text, other, mode, false); }

    /** Natural order: runs of digits compare by numeric value, the rest compares without case. */
    static int naturalCompare(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char x = a.charAt(i), y = b.charAt(j);
            if (Character.isDigit(x) && Character.isDigit(y)) {
                int si = i, sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                int c = new java.math.BigInteger(a.substring(si, i)).compareTo(new java.math.BigInteger(b.substring(sj, j)));
                if (c != 0) return c;
            } else {
                int c = Character.compare(Character.toLowerCase(x), Character.toLowerCase(y));
                if (c != 0) return c;
                i++; j++;
            }
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    @Action(id = "find", name = "Find", category = "Text", idempotent = true,
            description = "Find where a text (or regular expression) first appears, starting from a position.",
            panel = "text, find, ignore_case, regex, start | occurrence, group")
    @Output(name = "index", type = OoaType.NUMBER, description = "Zero-based position of the match, or -1 when not found.")
    @Output(name = "found", type = OoaType.BOOLEAN, description = "True when a match was found.")
    @Output(name = "match", type = OoaType.STRING, description = "The matched text (or the chosen group), empty when not found.")
    @Output(name = "position", type = OoaType.NUMBER, description = "One-based position of the match, or 0 when not found.")
    @Output(name = "groups", type = OoaType.LIST, description = "Regular expression: the capture groups of the returned match (group 1 first); empty otherwise.")
    @Output(name = "named_groups", type = OoaType.DICTIONARY, description = "Regular expression: named groups (?<name>...) of the returned match.")
    @Output(name = "all_matches", type = OoaType.LIST, description = "Every match from the start position on, in order.")
    @Output(name = "count", type = OoaType.NUMBER, description = "How many matches there are from the start position on.")
    public Map<String, Object> find(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Text to search in.") String text,
            @Input(name = "find", type = OoaType.STRING, description = "Text or regular expression to look for.") String find,
            @Input(name = "regex", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Treat 'find' as a regular expression.") boolean regex,
            @Input(name = "start", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0, description = "Zero-based position to start searching from.") int start,
            @Input(name = "ignore_case", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Ignore upper/lower case.") boolean ignoreCase,
            @Input(name = "occurrence", type = OoaType.NUMBER, required = false, defaultValue = "1", min = 1, description = "Which match to return (1 = first, 2 = second ...).") int occurrence,
            @Input(name = "group", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0, visibleWhen = "regex=true",
                    description = "Regular expression: return this capture group in match and all_matches instead of the whole match, e.g. 1 for the number in Total: (\\d+).") int group) {
        if (start < 0 || start > text.length())
            throw new ActionException("STRING.BAD_POSITION", "Start position " + start + " is outside the text (length " + text.length() + ")", "Use a position from 0 to the text length.");
        if (occurrence < 1) throw new ActionException("STRING.BAD_OCCURRENCE", "Occurrences start at 1, got " + occurrence, "Use 1 for the first match.");
        try {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(regex ? find : java.util.regex.Pattern.quote(find),
                    ignoreCase ? java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE : 0);
            java.util.regex.Matcher m = p.matcher(text);
            int pick = regex ? group : 0;
            if (pick < 0 || pick > m.groupCount())
                throw new ActionException("STRING.BAD_GROUP", "The expression has " + m.groupCount() + " capture group(s), group " + group + " was asked for", "Use 0 for the whole match or a group number that exists.");
            List<Object> all = new ArrayList<>();
            List<Object> groups = new ArrayList<>();
            Map<String, Object> named = new LinkedHashMap<>();
            int index = -1;
            String match = "";
            if (m.find(start)) do {
                all.add(m.group(pick) == null ? "" : m.group(pick));
                if (all.size() == occurrence) {
                    index = m.start(); match = (String) all.get(all.size() - 1);
                    if (regex) {
                        for (int g = 1; g <= m.groupCount(); g++) groups.add(m.group(g) == null ? "" : m.group(g));
                        for (String name : p.namedGroups().keySet()) named.put(name, m.group(name) == null ? "" : m.group(name));
                    }
                }
            } while (m.find());
            return Out.of("index", java.math.BigDecimal.valueOf(index), "found", index >= 0, "match", match,
                    "position", java.math.BigDecimal.valueOf(index + 1L), "groups", groups, "named_groups", named,
                    "all_matches", all, "count", java.math.BigDecimal.valueOf(all.size()));
        } catch (PatternSyntaxException e) {
            throw new ActionException("STRING.BAD_REGEX", "Invalid regular expression: " + e.getDescription(), "Escape special characters such as ( ) [ ] . *");
        }
    }

    public Map<String, Object> find(ActionContext ctx, String text, String find, boolean regex, int start, boolean ignoreCase, int occurrence) {
        return find(ctx, text, find, regex, start, ignoreCase, occurrence, 0);
    }

    @Action(id = "length", name = "Get length", category = "Text", idempotent = true, description = "Count the characters in a text.",
            panel = "text")
    @Output(name = "length", type = OoaType.NUMBER, description = "Number of characters.")
    public Map<String, Object> length(ActionContext ctx, @Input(name = "text", type = OoaType.STRING, description = "Text to measure.") String text) {
        return Out.of("length", java.math.BigDecimal.valueOf(text == null ? 0 : text.codePointCount(0, text.length())));
    }

    @Action(id = "substring", name = "Get part of text", category = "Text", idempotent = true,
            description = "Return the part of a text from a start position, for a length or up to an end position.",
            panel = "text, start, (Take: Up to the end = / A number of characters = length / Up to a position = end), if_out_of_range")
    @Output(name = "result", type = OoaType.STRING, description = "The selected part of the text.")
    public Map<String, Object> substring(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Source text.") String text,
            @Input(name = "start", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0, description = "Zero-based start position.") int start,
            @Input(name = "length", type = OoaType.NUMBER, required = false, min = 0, description = "Number of characters to take; to the end when empty.") Integer length,
            @Input(name = "end", type = OoaType.NUMBER, required = false, min = 0, description = "Zero-based end position (not included); used when length is empty.") Integer end,
            @Input(name = "if_out_of_range", type = OoaType.STRING, required = false, defaultValue = "\"error\"", enumValues = {"error", "empty"},
                    description = "When the start is past the end of the text: fail with STRING.BAD_POSITION, or return empty text.") String ifOutOfRange) {
        if (start > text.length() && "empty".equals(ifOutOfRange)) return Out.of("result", "");
        if (start < 0 || start > text.length())
            throw new ActionException("STRING.BAD_POSITION", "Start position " + start + " is outside the text (length " + text.length() + ")", "Use a position from 0 to the text length.");
        int stop = text.length();
        if (length != null) {
            if (length < 0) throw new ActionException("STRING.BAD_LENGTH", "Length cannot be negative (" + length + ")", "Use 0 or more.");
            stop = (int) Math.min((long) start + length, text.length());
        } else if (end != null) {
            if (end < start) throw new ActionException("STRING.BAD_POSITION", "End position " + end + " is before start position " + start, "Use an end at or after the start.");
            stop = Math.min(end, text.length());
        }
        return Out.of("result", text.substring(start, stop));
    }

    /** White space as people see it: Java white space, every Unicode space separator (incl. no-break spaces), BOM and zero-width space. */
    private static final java.util.regex.Pattern CUR_START = java.util.regex.Pattern.compile("^(\\p{Sc}+|[A-Z]{3}(?![A-Za-z])|Rs\\.?(?![A-Za-z]))\\s*");
    private static final java.util.regex.Pattern CUR_END = java.util.regex.Pattern.compile("\\s*(\\p{Sc}+|(?<![A-Za-z])[A-Z]{3}|(?<![A-Za-z])Rs\\.?)$");

    /** The text without one currency sign, ISO 4217 code (USD, EUR, INR) or "Rs." before and one after the number. */
    static String withoutCurrency(String t) {
        String s = t;
        java.util.regex.Matcher m = CUR_START.matcher(s);
        if (m.find() && isCurrency(m.group(1))) s = s.substring(m.end());
        m = CUR_END.matcher(s);
        if (m.find() && isCurrency(m.group(1))) s = s.substring(0, m.start());
        return stripSpace(s);
    }

    private static boolean isCurrency(String token) {
        if (!token.matches("[A-Z]{3}")) return true;
        try { java.util.Currency.getInstance(token); return true; } catch (IllegalArgumentException e) { return false; }
    }

    static String stripSpace(String t) {
        int a = 0, b = t.length();
        while (a < b && isSpace(t.charAt(a))) a++;
        while (b > a && isSpace(t.charAt(b - 1))) b--;
        return t.substring(a, b);
    }

    static boolean isSpace(int c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c) || c == '\uFEFF' || c == '\u200B';
    }

    @Action(id = "trim", name = "Trim", category = "Text", idempotent = true,
            description = "Remove spaces, or the characters you list, from both ends, the start or the end of a text.",
            panel = "text, side | characters")
    @Output(name = "result", type = OoaType.STRING, description = "Trimmed text.")
    public Map<String, Object> trim(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Source text.") String text,
            @Input(name = "side", type = OoaType.STRING, required = false, defaultValue = "\"both\"", enumValues = {"both", "left", "right"},
                    description = "Which end to trim.") String side,
            @Input(name = "characters", type = OoaType.STRING, required = false, description = "Characters to remove, e.g. 0 or *-; whitespace when empty.") String chars) {
        // white space includes the no-break spaces common in web pages, PDFs and Excel exports (U+00A0, U+2007, U+202F) and zero-width marks
        java.util.function.IntPredicate strip = chars == null || chars.isEmpty() ? StringPackage::isSpace : c -> chars.indexOf(c) >= 0;
        String s = side == null ? "both" : side;
        int a = 0, b = text.length();
        if (!s.equals("right")) while (a < b && strip.test(text.charAt(a))) a++;
        if (!s.equals("left")) while (b > a && strip.test(text.charAt(b - 1))) b--;
        return Out.of("result", text.substring(a, b));
    }

    @Action(id = "reverse", name = "Reverse", category = "Text", idempotent = true, description = "Return the text with its characters in reverse order.",
            panel = "text")
    @Output(name = "result", type = OoaType.STRING, description = "Reversed text.")
    public Map<String, Object> reverse(ActionContext ctx, @Input(name = "text", type = OoaType.STRING, description = "Source text.") String text) {
        return Out.of("result", new StringBuilder(text).reverse().toString());
    }

    @Action(id = "pad", name = "Pad text", category = "Text", idempotent = true,
            description = "Fill a text up to a length with a character on the left or the right, e.g. 42 to 00042; longer text is left as it is.",
            panel = "text, length, character, side")
    @Output(name = "result", type = OoaType.STRING, description = "The padded text.")
    public Map<String, Object> pad(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Source text; a number is used as it is written, e.g. 42.") String text,
            @Input(name = "length", type = OoaType.NUMBER, min = 0, max = 100000, example = "5", description = "Length the result should have, in characters.") int length,
            @Input(name = "character", type = OoaType.STRING, required = false, defaultValue = "\" \"", example = "0", description = "One character to fill with; a space when empty.") String character,
            @Input(name = "side", type = OoaType.STRING, required = false, defaultValue = "\"left\"", enumValues = {"left", "right"},
                    description = "left puts the fill before the text (numbers, codes); right puts it after (fixed-width columns).") String side) {
        String fill = character == null || character.isEmpty() ? " " : character;
        if (fill.codePointCount(0, fill.length()) != 1)
            throw new ActionException("STRING.BAD_CHARACTER", "Pad with one character, not '" + fill + "'", "Give a single character such as 0 or a space.");
        if (length < 0 || length > 100000) throw new ActionException("STRING.BAD_LENGTH", "Length " + length + " is out of range", "Use a length from 0 to 100000.");
        String t = text == null ? "" : text;
        int have = t.codePointCount(0, t.length());
        if (have >= length) return Out.of("result", t);
        String padding = fill.repeat(length - have);
        return Out.of("result", "right".equals(side) ? t + padding : padding + t);
    }

    @Action(id = "count_occurrences", name = "Count occurrences", category = "Text", idempotent = true,
            description = "Count how many times a piece of text appears in a text (matches do not overlap).",
            panel = "text, find, match_case, whole_word")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of times it appears; 0 when it does not.")
    public Map<String, Object> countOccurrences(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Text to search in.") String text,
            @Input(name = "find", type = OoaType.STRING, example = ",", description = "Text to count.") String find,
            @Input(name = "match_case", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Count only matches with the same upper and lower case.") boolean matchCase,
            @Input(name = "whole_word", type = OoaType.BOOLEAN, required = false, defaultValue = "false",
                    description = "Count only matches that are whole words, not parts of longer words (cat in cat food, not in catalogue).") boolean wholeWord) {
        if (find == null || find.isEmpty()) throw new ActionException("STRING.EMPTY_FIND", "Nothing to count", "Fill in the text to count.");
        String t = text == null ? "" : text;
        int flags = matchCase ? 0 : java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE;
        String q = java.util.regex.Pattern.quote(find);
        if (wholeWord) q = "(?<![\\p{L}\\p{N}_])" + q + "(?![\\p{L}\\p{N}_])";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(q, flags).matcher(t);
        int n = 0;
        while (m.find()) n++;
        return Out.of("count", java.math.BigDecimal.valueOf(n));
    }

    @Action(id = "escape_regex", name = "Escape for regex", category = "Text", idempotent = true,
            description = "Put a backslash before every character that has a meaning in regular expressions, so the text is matched exactly, e.g. 1+1=2? to 1\\+1=2\\?.",
            panel = "text")
    @Output(name = "result", type = OoaType.STRING, description = "The text, safe to use inside a regular expression.")
    public Map<String, Object> escapeRegex(ActionContext ctx, @Input(name = "text", type = OoaType.STRING, description = "Text to escape.") String text) {
        return Out.of("result", (text == null ? "" : text).replaceAll("[\\\\^$.|?*+()\\[\\]{}\\-/]", "\\\\$0"));
    }

    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    @Action(id = "random_string", name = "Random text", category = "Text",
            description = "Generate random text of a given length from letters, digits and/or symbols.",
            panel = "length | letters, digits, symbols, letter_case, exclude_characters")
    @Output(name = "result", type = OoaType.STRING, description = "Random text.")
    public Map<String, Object> randomString(ActionContext ctx,
            @Input(name = "length", type = OoaType.NUMBER, required = false, defaultValue = "12", min = 1, max = 100000, description = "Number of characters.") int length,
            @Input(name = "letters", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Include letters A-Z and a-z.") boolean letters,
            @Input(name = "digits", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Include digits 0-9.") boolean digits,
            @Input(name = "symbols", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Include symbols such as ! # $ %.") boolean symbols,
            @Input(name = "letter_case", type = OoaType.STRING, required = false, defaultValue = "\"mixed\"", enumValues = {"mixed", "upper", "lower"},
                    visibleWhen = "letters=true", description = "Letters in mixed, upper or lower case.") String letterCase,
            @Input(name = "exclude_characters", type = OoaType.STRING, required = false, description = "Characters never to use, e.g. 0O1l to avoid look-alikes.") String exclude) {
        if (length < 0 || length > 100_000) throw new ActionException("STRING.BAD_LENGTH", "Length must be between 0 and 100000, got " + length, "Choose a smaller positive length.");
        String upper = "ABCDEFGHIJKLMNOPQRSTUVWXYZ", lower = "abcdefghijklmnopqrstuvwxyz";
        String abc = "upper".equals(letterCase) ? upper : "lower".equals(letterCase) ? lower : upper + lower;
        String pool = (letters ? abc : "") + (digits ? "0123456789" : "") + (symbols ? "!#$%&*+-=?@^_~" : "");
        if (exclude != null && !exclude.isEmpty()) { StringBuilder kept = new StringBuilder(); for (char c : pool.toCharArray()) if (exclude.indexOf(c) < 0) kept.append(c); pool = kept.toString(); }
        if (pool.isEmpty()) throw new ActionException("STRING.NO_CHARACTER_SET", "No character set selected", "Turn on letters, digits or symbols.");
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) sb.append(pool.charAt(RANDOM.nextInt(pool.length())));
        return Out.of("result", sb.toString());
    }

    // ---- Number conversion ----

    @Action(id = "to_number", name = "Text to number", category = "Text", idempotent = true,
            description = "Read a number from text written for a locale, or with the decimal and grouping separators you give.",
            panel = "text | locale, decimal_separator, grouping_separator, ignore_currency")
    @Output(name = "result", type = OoaType.NUMBER, description = "The number.")
    public Map<String, Object> toNumber(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, example = "1.234,56", description = "Text such as 1,234.56, 1.234,56, $ 1,200.50, (1,234.00) or 1,234.00- (negative).") String text,
            @Input(name = "locale", type = OoaType.STRING, required = false, defaultValue = "\"en-US\"", description = "Locale the text is written in, e.g. en-US, de-DE, fr-FR.") String locale,
            @Input(name = "decimal_separator", type = OoaType.STRING, required = false, description = "Decimal separator; overrides the locale.") String decimalSep,
            @Input(name = "grouping_separator", type = OoaType.STRING, required = false, description = "Thousands separator; overrides the locale.") String groupSep,
            @Input(name = "ignore_currency", type = OoaType.BOOLEAN, required = false, defaultValue = "true",
                    description = "Ignore a currency symbol or code before or after the number ($, €, ₹, USD, EUR, Rs.); when off such text is not a number.") boolean ignoreCurrency) {
        String loc = locale == null || locale.isBlank() ? "en-US" : locale;
        java.text.DecimalFormatSymbols sym = java.text.DecimalFormatSymbols.getInstance(Locale.forLanguageTag(loc));
        String dec = decimalSep != null && !decimalSep.isEmpty() ? decimalSep : String.valueOf(sym.getDecimalSeparator());
        String grp = groupSep != null && !groupSep.isEmpty() ? groupSep : String.valueOf(sym.getGroupingSeparator());
        if (dec.equals(grp)) throw new ActionException("STRING.BAD_SEPARATORS", "Decimal and grouping separators are both '" + dec + "'", "Use different separators.");
        String s = text == null ? "" : stripSpace(text);
        if (ignoreCurrency) s = withoutCurrency(s);
        // accounting negatives: (1,234.00) and 1,234.00- (SAP, bank exports); a currency sign may sit inside the brackets
        boolean negative = false;
        if (s.length() > 2 && s.startsWith("(") && s.endsWith(")")) { negative = true; s = stripSpace(s.substring(1, s.length() - 1)); }
        else if (s.length() > 1 && s.endsWith("-") && !s.startsWith("-")) { negative = true; s = stripSpace(s.substring(0, s.length() - 1)); }
        if (negative && ignoreCurrency) s = withoutCurrency(s);
        s = s.replace(grp, "");
        if (grp.isBlank() || Character.isSpaceChar(grp.charAt(0))) s = s.replaceAll("[\\s\\u00A0\\u202F]", "");
        s = s.replace(dec, ".");
        if (s.startsWith("+")) s = s.substring(1);
        try {
            if (negative && s.startsWith("-")) throw new NumberFormatException("two minus signs");
            java.math.BigDecimal n = new java.math.BigDecimal(s);
            return Out.of("result", negative ? n.negate() : n);
        }
        catch (NumberFormatException e) {
            throw new ActionException("STRING.NOT_A_NUMBER", "'" + text + "' is not a number for locale " + loc + " (decimal '" + dec + "', grouping '" + grp + "')",
                    "Check the locale or set decimal_separator and grouping_separator.");
        }
    }

    @Action(id = "to_locale_number", name = "Number to locale text", category = "Text", idempotent = true,
            description = "Write a number as text using the separators of a locale, e.g. 1.234,5 for de-DE.",
            panel = "value, locale | decimals, grouping")
    @Output(name = "result", type = OoaType.STRING, description = "Formatted number.")
    public Map<String, Object> toLocaleNumber(ActionContext ctx,
            @Input(name = "value", type = OoaType.NUMBER, description = "Number to write.") java.math.BigDecimal value,
            @Input(name = "locale", type = OoaType.STRING, required = false, defaultValue = "\"en-US\"", description = "Locale, e.g. en-US, de-DE, en-IN.") String locale,
            @Input(name = "decimals", type = OoaType.NUMBER, required = false, min = 0, max = 20, description = "Fixed decimal places; keeps the number's own decimals when empty.") Integer decimals,
            @Input(name = "grouping", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Use a thousands separator.") boolean grouping) {
        if (decimals != null && (decimals < 0 || decimals > 50)) throw new ActionException("STRING.BAD_DECIMALS", "Decimals must be between 0 and 50, got " + decimals, "Use a small positive number.");
        java.text.NumberFormat f = java.text.NumberFormat.getNumberInstance(Locale.forLanguageTag(locale == null || locale.isBlank() ? "en-US" : locale));
        int d = decimals != null ? decimals : Math.max(0, value.stripTrailingZeros().scale());
        f.setMinimumFractionDigits(d);
        f.setMaximumFractionDigits(d);
        f.setGroupingUsed(grouping);
        f.setRoundingMode(java.math.RoundingMode.HALF_UP);
        String text = f.format(value);
        return Out.of("result", grouping ? NumberPackage.southAsianGrouping(text, Locale.forLanguageTag(locale == null || locale.isBlank() ? "en-US" : locale)) : text);
    }

    /** Names and keys: the words of the text (split at spaces, punctuation and lower-to-upper changes) in camel, Pascal, snake or kebab case. */
    static String codeCase(String text, String kase) {
        List<String> words = new ArrayList<>();
        for (String w : text.replaceAll("([a-z0-9])([A-Z])", "$1 $2").split("[^A-Za-z0-9]+")) if (!w.isEmpty()) words.add(w.toLowerCase(Locale.ROOT));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.size(); i++) {
            String w = words.get(i);
            switch (kase) {
                case "snake" -> sb.append(i > 0 ? "_" : "").append(w);
                case "kebab" -> sb.append(i > 0 ? "-" : "").append(w);
                default -> sb.append(i == 0 && kase.equals("camel") ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1));
            }
        }
        return sb.toString();
    }

    // ---- More text work made easy (16 Nov 2026) ----

    @Action(id = "join", name = "Join texts", category = "Text", idempotent = true,
            description = "Join the items of a list into one text with a separator, e.g. names into Ann, Bo and Cy, leaving out empty items if you like.",
            panel = "items, separator | skip_empty, last_separator, prefix, suffix")
    @Output(name = "result", type = OoaType.STRING, description = "The joined text.")
    public Map<String, Object> join(ActionContext ctx,
            @Input(name = "items", type = OoaType.LIST, description = "The texts, as a List variable or [\"a\", \"b\"]; numbers and dates are written as text.") List<Object> items,
            @Input(name = "separator", type = OoaType.STRING, required = false, defaultValue = "\", \"", description = "Put between items; a line break is \\n.") String separator,
            @Input(name = "skip_empty", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Leave out empty items.") boolean skipEmpty,
            @Input(name = "last_separator", type = OoaType.STRING, required = false, description = "Put before the last item instead, e.g. \" and \".") String last,
            @Input(name = "prefix", type = OoaType.STRING, required = false, description = "Text before the result.") String prefix,
            @Input(name = "suffix", type = OoaType.STRING, required = false, description = "Text after the result.") String suffix) {
        List<String> parts = new ArrayList<>();
        if (items != null) for (Object o : items) { String s = Values.text(o); if (!(skipEmpty && (s == null || s.isBlank()))) parts.add(s == null ? "" : s); }
        String sep = unescapeBreaks(separator == null ? ", " : separator);
        String body;
        if (last != null && !last.isEmpty() && parts.size() > 1)
            body = String.join(sep, parts.subList(0, parts.size() - 1)) + unescapeBreaks(last) + parts.get(parts.size() - 1);
        else body = String.join(sep, parts);
        return Out.of("result", (prefix == null ? "" : prefix) + body + (suffix == null ? "" : suffix));
    }

    /** \n and \t typed in a separator field mean a line break and a tab. */
    static String unescapeBreaks(String s) { return s.replace("\\r\\n", "\r\n").replace("\\n", "\n").replace("\\t", "\t"); }

    @Action(id = "find_all", name = "Find all matches", category = "Text", idempotent = true,
            description = "Every place a text or pattern occurs, as a list: all invoice numbers, e-mail addresses or amounts in a text.",
            panel = "text, find, regex, ignore_case | group, unique")
    @Output(name = "matches", type = OoaType.LIST, description = "The matches, in order.")
    @Output(name = "count", type = OoaType.NUMBER, description = "How many.")
    public Map<String, Object> findAll(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Text to search.") String text,
            @Input(name = "find", type = OoaType.STRING, example = "INV-\\d+", description = "Text or pattern to find.") String find,
            @Input(name = "regex", type = OoaType.BOOLEAN, required = false, defaultValue = "true", label = "Use a pattern", description = "Find is a regular expression.") boolean regex,
            @Input(name = "ignore_case", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Upper and lower case alike.") boolean ignoreCase,
            @Input(name = "group", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0, visibleWhen = "regex=true",
                    description = "Keep this bracket group of each match; 0 keeps the whole match.") int group,
            @Input(name = "unique", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Keep each match once.") boolean unique) {
        java.util.regex.Matcher m = pattern(find, regex, ignoreCase).matcher(text == null ? "" : text);
        List<Object> out = new ArrayList<>();
        while (m.find()) {
            if (group > m.groupCount()) throw new ActionException("STRING.BAD_GROUP", "The pattern has " + m.groupCount() + " bracket groups, not " + group, "Use a group from 0 to " + m.groupCount() + ".");
            String g = m.group(group);
            if (g != null && !(unique && out.contains(g))) out.add(g);
        }
        return Out.of("matches", out, "count", java.math.BigDecimal.valueOf(out.size()));
    }

    static java.util.regex.Pattern pattern(String find, boolean regex, boolean ignoreCase) {
        int flags = ignoreCase ? java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE : 0;
        try { return java.util.regex.Pattern.compile(regex ? find : java.util.regex.Pattern.quote(find), flags); }
        catch (java.util.regex.PatternSyntaxException e) {
            throw new ActionException("STRING.BAD_PATTERN", "The pattern '" + find + "' is not valid: " + e.getDescription(), "Fix the pattern, or untick Use a pattern to find the text as it is.");
        }
    }

    @Action(id = "extract_pattern", name = "Extract with pattern", category = "Text", idempotent = true,
            description = "Take the parts of a text a pattern describes, e.g. the date, number and amount of \"Invoice 42 of 2026-11-16: 99.50\"; named parts come back as a Dictionary.",
            panel = "text, pattern, occurrence | ignore_case")
    @Output(name = "found", type = OoaType.BOOLEAN, description = "True when the pattern was found.")
    @Output(name = "match", type = OoaType.STRING, description = "The whole match.")
    @Output(name = "groups", type = OoaType.LIST, description = "Each bracket group, in order.")
    @Output(name = "named", type = OoaType.DICTIONARY, description = "Each named group (?<name>...) by its name.")
    public Map<String, Object> extractPattern(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Text to read.") String text,
            @Input(name = "pattern", type = OoaType.STRING, example = "Invoice (?<number>\\d+) of (?<date>[\\d-]+)",
                    description = "A regular expression with bracket groups, named like (?<total>[\\d.]+) if you want them by name.") String pattern,
            @Input(name = "occurrence", type = OoaType.NUMBER, required = false, defaultValue = "1", min = 1, description = "Which match to take: 1 for the first.") int occurrence,
            @Input(name = "ignore_case", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Upper and lower case alike.") boolean ignoreCase) {
        java.util.regex.Pattern p = pattern(pattern, true, ignoreCase);
        java.util.regex.Matcher m = p.matcher(text == null ? "" : text);
        int n = 0;
        while (m.find()) {
            if (++n < Math.max(1, occurrence)) continue;
            List<Object> groups = new ArrayList<>();
            for (int g = 1; g <= m.groupCount(); g++) groups.add(m.group(g));
            Map<String, Object> named = new LinkedHashMap<>();
            for (String name : p.namedGroups().keySet()) named.put(name, m.group(name));
            return Out.of("found", true, "match", m.group(), "groups", groups, "named", named);
        }
        return Out.of("found", false, "match", "", "groups", new ArrayList<>(), "named", new LinkedHashMap<>());
    }

    @Action(id = "remove_characters", name = "Remove characters", category = "Text", idempotent = true,
            description = "Strip digits, letters, punctuation, spaces, line breaks or accents (é becomes e), or characters you list, e.g. to clean phone numbers or IDs.",
            panel = "text, digits, letters, punctuation, spaces, line_breaks, accents | characters, keep_instead")
    @Output(name = "result", type = OoaType.STRING, description = "The cleaned text.")
    public Map<String, Object> removeCharacters(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Text to clean.") String text,
            @Input(name = "digits", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Remove 0-9.") boolean digits,
            @Input(name = "letters", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Remove letters of any alphabet.") boolean letters,
            @Input(name = "punctuation", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Remove punctuation and symbols such as . , - ( ) / # @.") boolean punctuation,
            @Input(name = "spaces", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Remove spaces and tabs.") boolean spaces,
            @Input(name = "line_breaks", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Remove line breaks.") boolean lineBreaks,
            @Input(name = "accents", type = OoaType.BOOLEAN, required = false, defaultValue = "false", label = "Accents (é to e)",
                    description = "Keep the letters but drop their accents: café becomes cafe, Müller becomes Muller.") boolean accents,
            @Input(name = "characters", type = OoaType.STRING, required = false, description = "Any other characters to remove, typed one after another, e.g. $€£.") String characters,
            @Input(name = "keep_instead", type = OoaType.BOOLEAN, required = false, defaultValue = "false", label = "Keep only these instead",
                    description = "Keep only the ticked kinds and listed characters, removing everything else (e.g. tick Digits to keep only digits).") boolean keepInstead) {
        String t = text == null ? "" : text;
        if (accents) t = java.text.Normalizer.normalize(t, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        StringBuilder sb = new StringBuilder(t.length());
        String extra = characters == null ? "" : characters;
        t.codePoints().forEach(cp -> {
            boolean hit = (digits && Character.isDigit(cp)) || (letters && Character.isLetter(cp))
                    || (punctuation && !Character.isLetterOrDigit(cp) && !Character.isWhitespace(cp) && !Character.isISOControl(cp))
                    || (spaces && (cp == ' ' || cp == '\t' || cp == 0xA0)) || (lineBreaks && (cp == '\n' || cp == '\r'))
                    || extra.indexOf(cp) >= 0;
            if (hit == keepInstead) sb.appendCodePoint(cp);
        });
        return Out.of("result", sb.toString());
    }

    @Action(id = "normalize_spaces", name = "Normalize spaces", category = "Text", idempotent = true,
            description = "Turn runs of spaces, tabs and no-break spaces into one space and trim the ends, keeping or joining the lines, e.g. for text read from web pages or PDFs.",
            panel = "text, keep_line_breaks")
    @Output(name = "result", type = OoaType.STRING, description = "The tidied text.")
    public Map<String, Object> normalizeSpaces(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Text to tidy.") String text,
            @Input(name = "keep_line_breaks", type = OoaType.BOOLEAN, required = false, defaultValue = "false",
                    description = "Keep the lines (tidying each, and dropping empty ones); when off, everything becomes one line.") boolean keepLines) {
        String t = text == null ? "" : text;
        if (!keepLines) return Out.of("result", t.replaceAll("[ \\t\\n\\r\\f\\u000b\\u00a0]+", " ").strip());
        List<String> lines = new ArrayList<>();
        for (String line : t.split("\\r?\\n|\\r")) { String s = line.replaceAll("[ \\t\\f\\u000b\\u00a0]+", " ").strip(); if (!s.isEmpty()) lines.add(s); }
        return Out.of("result", String.join("\n", lines));
    }

    @Action(id = "insert", name = "Insert text", category = "Text", idempotent = true,
            description = "Put text into another text at a position (counted from 0), at the start or at the end.",
            panel = "text, insert, position")
    @Output(name = "result", type = OoaType.STRING, description = "The text with the insert.")
    public Map<String, Object> insert(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Text to change.") String text,
            @Input(name = "insert", type = OoaType.STRING, label = "Text to insert", description = "What goes in.") String insert,
            @Input(name = "position", type = OoaType.NUMBER, required = false, defaultValue = "0", min = -1,
                    description = "Where it goes, counted from 0; -1 puts it at the end.") int position) {
        String t = text == null ? "" : text;
        int at = position < 0 ? t.length() : position;
        if (at > t.length()) throw new ActionException("STRING.BAD_POSITION", "Position " + position + " is past the end of the text (length " + t.length() + ")", "Use a position from 0 to " + t.length() + ", or -1 for the end.");
        return Out.of("result", t.substring(0, at) + (insert == null ? "" : insert) + t.substring(at));
    }

    @Action(id = "remove_part", name = "Remove part of text", category = "Text", idempotent = true,
            description = "Cut out a number of characters from a position (counted from 0), e.g. a prefix or a check digit.",
            panel = "text, start, length")
    @Output(name = "result", type = OoaType.STRING, description = "The text without that part.")
    public Map<String, Object> removePart(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Text to change.") String text,
            @Input(name = "start", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0, description = "First character to remove, counted from 0.") int start,
            @Input(name = "length", type = OoaType.NUMBER, required = false, min = 0, description = "How many characters; to the end when empty.") Integer length) {
        String t = text == null ? "" : text;
        if (start > t.length()) throw new ActionException("STRING.BAD_POSITION", "Start " + start + " is past the end of the text (length " + t.length() + ")", "Use a start from 0 to " + t.length() + ".");
        int end = length == null ? t.length() : (int) Math.min((long) start + length, t.length());
        return Out.of("result", t.substring(0, start) + t.substring(end));
    }

    @Action(id = "repeat", name = "Repeat text", category = "Text", idempotent = true,
            description = "Repeat a text a number of times, with a separator between, e.g. a line of dashes or placeholders.",
            panel = "text, times | separator")
    @Output(name = "result", type = OoaType.STRING, description = "The repeated text.")
    public Map<String, Object> repeat(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Text to repeat.") String text,
            @Input(name = "times", type = OoaType.NUMBER, defaultValue = "2", min = 0, max = 100000, description = "How many times.") int times,
            @Input(name = "separator", type = OoaType.STRING, required = false, description = "Put between the copies.") String separator) {
        String t = text == null ? "" : text, sep = separator == null ? "" : unescapeBreaks(separator);
        if ((long) (t.length() + sep.length()) * times > 1_000_000)
            throw new ActionException("STRING.TOO_LONG", "The result would be longer than 1,000,000 characters", "Repeat the text fewer times.");
        return Out.of("result", String.join(sep, java.util.Collections.nCopies(Math.max(0, times), t)));
    }

    @Action(id = "mask", name = "Mask text", category = "Text", idempotent = true,
            description = "Hide most of a text behind a character, keeping the first or last few, e.g. a card number as ************1111 or an e-mail as a***@example.com.",
            panel = "text, keep_last, keep_first | mask_character, keep_separators, email")
    @Output(name = "result", type = OoaType.STRING, description = "The masked text.")
    public Map<String, Object> mask(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Text to mask.") String text,
            @Input(name = "keep_last", type = OoaType.NUMBER, required = false, defaultValue = "4", min = 0, description = "Characters to keep visible at the end.") int keepLast,
            @Input(name = "keep_first", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0, description = "Characters to keep visible at the start.") int keepFirst,
            @Input(name = "mask_character", type = OoaType.STRING, required = false, defaultValue = "\"*\"", description = "The character that hides the rest.") String maskChar,
            @Input(name = "keep_separators", type = OoaType.BOOLEAN, required = false, defaultValue = "true",
                    description = "Leave spaces, dashes and dots as they are (4111 1111 1111 1111 becomes **** **** **** 1111).") boolean keepSeparators,
            @Input(name = "email", type = OoaType.BOOLEAN, required = false, defaultValue = "false", label = "It is an e-mail address",
                    description = "Mask only the part before the @, keeping its first character.") boolean email) {
        String t = text == null ? "" : text, c = maskChar == null || maskChar.isEmpty() ? "*" : maskChar.substring(0, 1);
        if (email && t.contains("@")) {
            int at = t.indexOf('@');
            String local = t.substring(0, at);
            return Out.of("result", (local.isEmpty() ? "" : local.charAt(0) + c.repeat(Math.max(3, local.length() - 1))) + t.substring(at));
        }
        int[] cps = t.codePoints().toArray();
        List<Integer> maskable = new ArrayList<>();
        for (int i = 0; i < cps.length; i++) if (!(keepSeparators && (cps[i] == ' ' || cps[i] == '-' || cps[i] == '.' || cps[i] == '/'))) maskable.add(i);
        StringBuilder sb = new StringBuilder();
        java.util.Set<Integer> hidden = new java.util.HashSet<>();
        for (int k = Math.max(0, keepFirst); k < maskable.size() - Math.max(0, keepLast); k++) hidden.add(maskable.get(k));
        for (int i = 0; i < cps.length; i++) { if (hidden.contains(i)) sb.append(c); else sb.appendCodePoint(cps[i]); }
        return Out.of("result", sb.toString());
    }

    @Action(id = "shorten", name = "Shorten text", category = "Text", idempotent = true,
            description = "Cut a text to a maximum length and add an ending such as …, if you like at a word break, e.g. for subjects, columns or messages.",
            panel = "text, max_length | ending, whole_words")
    @Output(name = "result", type = OoaType.STRING, description = "The shortened text (unchanged when it was short enough).")
    @Output(name = "shortened", type = OoaType.BOOLEAN, description = "True when the text was cut.")
    public Map<String, Object> shorten(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Text to shorten.") String text,
            @Input(name = "max_length", type = OoaType.NUMBER, defaultValue = "100", min = 1, description = "Longest result, ending included.") int max,
            @Input(name = "ending", type = OoaType.STRING, required = false, defaultValue = "\"…\"", description = "Added when the text is cut; empty adds nothing.") String ending,
            @Input(name = "whole_words", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Cut at the last space rather than in a word.") boolean wholeWords) {
        String t = text == null ? "" : text, end = ending == null ? "" : ending;
        if (t.length() <= max) return Out.of("result", t, "shortened", false);
        int room = Math.max(0, max - end.length());
        String cut = t.substring(0, room);
        // at a word break already when the next character is a space; otherwise back to the last space (if not too far back)
        if (wholeWords && !Character.isWhitespace(t.charAt(room))) { int sp = cut.lastIndexOf(' '); if (sp > room / 2) cut = cut.substring(0, sp); }
        return Out.of("result", cut.stripTrailing() + end, "shortened", true);
    }

    @Action(id = "count_words", name = "Count words and lines", category = "Text", idempotent = true,
            description = "How many words, lines, sentences and characters a text has.",
            panel = "text")
    @Output(name = "words", type = OoaType.NUMBER, description = "Words (runs of letters and digits).")
    @Output(name = "lines", type = OoaType.NUMBER, description = "Lines, empty ones included; 0 for empty text.")
    @Output(name = "sentences", type = OoaType.NUMBER, description = "Sentences ending in . ! or ?.")
    @Output(name = "characters", type = OoaType.NUMBER, description = "Characters, spaces included.")
    @Output(name = "characters_no_spaces", type = OoaType.NUMBER, description = "Characters without spaces and line breaks.")
    public Map<String, Object> countWords(ActionContext ctx, @Input(name = "text", type = OoaType.STRING, description = "Text to count.") String text) {
        String t = text == null ? "" : text;
        long words = java.util.regex.Pattern.compile("[\\p{L}\\p{N}]+(?:['’-][\\p{L}\\p{N}]+)*").matcher(t).results().count();
        long lines = t.isEmpty() ? 0 : t.split("\\r?\\n|\\r", -1).length;
        long sentences = t.isBlank() ? 0 : java.util.regex.Pattern.compile("[^.!?]*[\\p{L}\\p{N}][^.!?]*(?:[.!?]+|$)").matcher(t.strip()).results().count();
        long noSpaces = t.codePoints().filter(cp -> !Character.isWhitespace(cp) && cp != 0xA0).count();
        return Out.of("words", java.math.BigDecimal.valueOf(words), "lines", java.math.BigDecimal.valueOf(lines), "sentences", java.math.BigDecimal.valueOf(sentences),
                "characters", java.math.BigDecimal.valueOf(t.codePointCount(0, t.length())), "characters_no_spaces", java.math.BigDecimal.valueOf(noSpaces));
    }

    @Action(id = "fill_template", name = "Fill a template", category = "Text", idempotent = true,
            description = "Put values into a text with {name} places, e.g. \"Dear {first_name}, invoice {number} is due on {due}.\" from a Dictionary or a table row.",
            panel = "template, values | if_missing")
    @Output(name = "result", type = OoaType.STRING, description = "The filled text.")
    @Output(name = "missing", type = OoaType.LIST, description = "Places that had no value.")
    public Map<String, Object> fillTemplate(ActionContext ctx,
            @Input(name = "template", type = OoaType.STRING, description = "Text with places in curly brackets, e.g. {name}; {{ and }} write a bracket.") String template,
            @Input(name = "values", type = OoaType.DICTIONARY, description = "The values by name: a Dictionary or a table row (a Record).") Map<String, Object> values,
            @Input(name = "if_missing", type = OoaType.STRING, required = false, defaultValue = "\"keep\"", enumValues = {"keep", "empty", "error"},
                    description = "A place without a value: keep {name}, leave it empty, or fail with STRING.MISSING_VALUE.") String ifMissing) {
        String t = template == null ? "" : template;
        Map<String, Object> vals = new LinkedHashMap<>();
        if (values != null) values.forEach((k, v) -> vals.put(k.toLowerCase(Locale.ROOT), v));
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{\\{|\\}\\}|\\{([^{}]+)\\}").matcher(t);
        StringBuilder sb = new StringBuilder();
        List<Object> missing = new ArrayList<>();
        while (m.find()) {
            String rep;
            if (m.group().equals("{{")) rep = "{";
            else if (m.group().equals("}}")) rep = "}";
            else {
                String key = m.group(1).strip();
                Object v = vals.get(key.toLowerCase(Locale.ROOT));
                if (v == null && !vals.containsKey(key.toLowerCase(Locale.ROOT))) {
                    missing.add(key);
                    if ("error".equals(ifMissing)) throw new ActionException("STRING.MISSING_VALUE", "The template needs {" + key + "}, which the values do not have", "Add " + key + " to the values, or choose to keep or empty missing places.");
                    rep = "empty".equals(ifMissing) ? "" : m.group();
                } else rep = v == null ? "" : Values.text(v);
            }
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return Out.of("result", sb.toString(), "missing", missing);
    }

    @Action(id = "encode", name = "Encode or decode text", category = "Text", idempotent = true,
            description = "Make text safe for, or read it back from, a web address (URL), HTML, XML, JSON or Base64.",
            panel = "text, direction, format")
    @Output(name = "result", type = OoaType.STRING, description = "The encoded or decoded text.")
    public Map<String, Object> encode(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Text to change.") String text,
            @Input(name = "direction", type = OoaType.STRING, required = false, defaultValue = "\"encode\"", enumValues = {"encode", "decode"}, description = "Encode or decode.") String direction,
            @Input(name = "format", type = OoaType.STRING, defaultValue = "\"url\"", enumValues = {"url", "html", "xml", "json", "base64"},
                    description = "URL: a value in a web address (a space is %20); HTML and XML: < > & and quotes; JSON: the inside of a JSON text (quotes, \\n); Base64: UTF-8 bytes.") String format) {
        String t = text == null ? "" : text;
        boolean enc = !"decode".equals(direction);
        try {
            String r = switch (format == null ? "url" : format) {
                case "url" -> enc ? java.net.URLEncoder.encode(t, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20")
                        : java.net.URLDecoder.decode(t.replace("+", "%2B"), java.nio.charset.StandardCharsets.UTF_8);
                case "html", "xml" -> enc ? t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;") : unescapeMarkup(t);
                case "json" -> {
                    if (enc) { String q = JsonLite.write(t); yield q.substring(1, q.length() - 1); }
                    Object v = JsonLite.parse("\"" + t + "\"");
                    yield String.valueOf(v);
                }
                case "base64" -> enc ? java.util.Base64.getEncoder().encodeToString(t.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        : new String(java.util.Base64.getMimeDecoder().decode(t.strip()), java.nio.charset.StandardCharsets.UTF_8);
                default -> throw new ActionException("STRING.BAD_FORMAT", "Unknown format '" + format + "'", "Use url, html, xml, json or base64.");
            };
            return Out.of("result", r);
        } catch (IllegalArgumentException e) {
            throw new ActionException("STRING.CANNOT_DECODE", "The text is not valid " + format + ": " + e.getMessage(), "Check that the text really is " + format + "-encoded.");
        }
    }

    static String unescapeMarkup(String t) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("&(#x[0-9A-Fa-f]+|#[0-9]+|amp|lt|gt|quot|apos|nbsp);").matcher(t);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String e = m.group(1), rep = switch (e) {
                case "amp" -> "&"; case "lt" -> "<"; case "gt" -> ">"; case "quot" -> "\""; case "apos" -> "'"; case "nbsp" -> " ";
                default -> new String(Character.toChars(e.startsWith("#x") ? Integer.parseInt(e.substring(2), 16) : Integer.parseInt(e.substring(1))));
            };
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ---- Conditions: check text in an If, a Loop's while or Wait for condition ----

    @Condition(id = "starts_with", name = "Text starts with", description = "True when the text begins with the other text.")
    public boolean startsWith(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Text to check.") String text,
            @Input(name = "start", type = OoaType.STRING, label = "Starts with", description = "The beginning to look for.") String start,
            @Input(name = "ignore_case", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Upper and lower case alike.") boolean ignoreCase) {
        String t = text == null ? "" : text, s = start == null ? "" : start;
        return ignoreCase ? t.regionMatches(true, 0, s, 0, s.length()) : t.startsWith(s);
    }

    @Condition(id = "ends_with", name = "Text ends with", description = "True when the text finishes with the other text.")
    public boolean endsWith(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Text to check.") String text,
            @Input(name = "end", type = OoaType.STRING, label = "Ends with", description = "The ending to look for, e.g. .pdf.") String end,
            @Input(name = "ignore_case", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Upper and lower case alike.") boolean ignoreCase) {
        String t = text == null ? "" : text, e = end == null ? "" : end;
        return ignoreCase ? t.regionMatches(true, t.length() - e.length(), e, 0, e.length()) && t.length() >= e.length() : t.endsWith(e);
    }

    @Condition(id = "contains", name = "Text contains", description = "True when the text has the other text in it, anywhere or as a whole word.")
    public boolean containsText(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Text to check.") String text,
            @Input(name = "find", type = OoaType.STRING, label = "Contains", description = "The text to look for.") String find,
            @Input(name = "ignore_case", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Upper and lower case alike.") boolean ignoreCase,
            @Input(name = "whole_word", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Only as a whole word (urgent, not urgently).") boolean wholeWord) {
        String t = text == null ? "" : text, f = find == null ? "" : find;
        if (!wholeWord) return ignoreCase ? t.toLowerCase(Locale.ROOT).contains(f.toLowerCase(Locale.ROOT)) : t.contains(f);
        return pattern("(?<![\\p{L}\\p{N}_])" + java.util.regex.Pattern.quote(f) + "(?![\\p{L}\\p{N}_])", true, ignoreCase).matcher(t).find();
    }

    @Condition(id = "is_empty", name = "Text is empty", description = "True when the text has no characters (or, if you like, only spaces).")
    public boolean isEmpty(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, required = false, description = "Text to check; an empty variable counts as empty.") String text,
            @Input(name = "spaces_are_empty", type = OoaType.BOOLEAN, required = false, defaultValue = "true", label = "Only spaces counts as empty",
                    description = "Text of only spaces, tabs and line breaks counts as empty.") boolean spacesEmpty) {
        return text == null || text.isEmpty() || (spacesEmpty && text.replace(' ', ' ').isBlank());
    }

    @Condition(id = "matches_pattern", name = "Text matches a pattern", description = "True when the text fits a regular expression, as a whole or somewhere in it, e.g. ^INV-\\d{6}$ for an invoice number.")
    public boolean matchesPattern(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Text to check.") String text,
            @Input(name = "pattern", type = OoaType.STRING, example = "^INV-\\d{6}$", description = "The regular expression.") String pattern,
            @Input(name = "whole_text", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "The whole text must fit; when off, a match anywhere counts.") boolean whole,
            @Input(name = "ignore_case", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Upper and lower case alike.") boolean ignoreCase) {
        java.util.regex.Matcher m = pattern(pattern, true, ignoreCase).matcher(text == null ? "" : text);
        return whole ? m.matches() : m.find();
    }
}
