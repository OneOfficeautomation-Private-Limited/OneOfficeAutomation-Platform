package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;

import java.util.*;
import java.util.regex.PatternSyntaxException;

@OoaPackage(id = "ooa.string", name = "String", version = "1.0.0", description = "Text manipulation: extract, replace, split, change case.")
public final class StringPackage {

    @Action(id = "extract_text", name = "Extract text", category = "Text", idempotent = true,
            description = "Return the text before, after or between markers.")
    @Output(name = "result", type = OoaType.STRING, description = "Extracted text, empty when a marker is missing.")
    public Map<String, Object> extractText(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Source text.") String text,
            @Input(name = "before", type = OoaType.STRING, required = false, description = "Return text before this marker.") String before,
            @Input(name = "after", type = OoaType.STRING, required = false, description = "Return text after this marker.") String after,
            @Input(name = "trim", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Trim whitespace from the result.") boolean trim) {
        String out = text;
        if (after != null) { int i = out.indexOf(after); out = i >= 0 ? out.substring(i + after.length()) : ""; }
        if (before != null) { int i = out.indexOf(before); out = i >= 0 ? out.substring(0, i) : (after == null ? "" : out); }
        return Map.of("result", trim ? out.strip() : out);
    }

    @Action(id = "replace", name = "Replace", category = "Text", idempotent = true, description = "Replace text, optionally using a regular expression.")
    @Output(name = "result", type = OoaType.STRING, description = "Text after replacement.")
    public Map<String, Object> replace(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Source text.") String text,
            @Input(name = "find", type = OoaType.STRING, description = "Text or regex to find.") String find,
            @Input(name = "replace_with", type = OoaType.STRING, required = false, defaultValue = "\"\"", description = "Replacement text.") String with,
            @Input(name = "regex", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Treat 'find' as a regular expression.") boolean regex) {
        try {
            return Map.of("result", regex ? text.replaceAll(find, with) : text.replace(find, with));
        } catch (PatternSyntaxException e) {
            throw new ActionException("STRING.BAD_REGEX", "Invalid regular expression: " + e.getDescription(), "Escape special characters such as ( ) [ ] . *");
        }
    }

    @Action(id = "split", name = "Split", category = "Text", idempotent = true, description = "Split text into a list by a delimiter.")
    @Output(name = "items", type = OoaType.LIST, description = "List of parts.")
    public Map<String, Object> split(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Source text.") String text,
            @Input(name = "delimiter", type = OoaType.STRING, description = "Delimiter, e.g. ','.") String delimiter,
            @Input(name = "trim_items", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Trim each item.") boolean trimItems) {
        List<Object> items = new ArrayList<>();
        for (String p : text.split(java.util.regex.Pattern.quote(delimiter), -1)) items.add(trimItems ? p.strip() : p);
        return Map.of("items", items);
    }

    @Action(id = "change_case", name = "Change case", category = "Text", idempotent = true, description = "Convert text to upper, lower, title or sentence case.")
    @Output(name = "result", type = OoaType.STRING, description = "Converted text.")
    public Map<String, Object> changeCase(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Source text.") String text,
            @Input(name = "case", type = OoaType.STRING, enumValues = {"upper", "lower", "title", "sentence"}, description = "Target case.") String kase) {
        String r = switch (kase) {
            case "upper" -> text.toUpperCase(Locale.ROOT);
            case "lower" -> text.toLowerCase(Locale.ROOT);
            case "sentence" -> text.isEmpty() ? text : text.substring(0, 1).toUpperCase(Locale.ROOT) + text.substring(1).toLowerCase(Locale.ROOT);
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
            description = "Compare two texts exactly, ignoring case, or in natural order where numbers inside the text are compared by value.")
    @Output(name = "result", type = OoaType.NUMBER, description = "-1 when the first text comes first, 0 when they are equal, 1 when it comes after.")
    @Output(name = "equal", type = OoaType.BOOLEAN, description = "True when the texts are equal under the chosen mode.")
    public Map<String, Object> compare(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "First text.") String text,
            @Input(name = "other", type = OoaType.STRING, description = "Second text.") String other,
            @Input(name = "mode", type = OoaType.STRING, required = false, defaultValue = "\"exact\"", enumValues = {"exact", "ignore_case", "natural"},
                    description = "exact, ignore_case, or natural (file2 before file10, case ignored).") String mode) {
        String a = text == null ? "" : text, b = other == null ? "" : other;
        int r = switch (mode == null ? "exact" : mode) {
            case "ignore_case" -> a.compareToIgnoreCase(b);
            case "natural" -> naturalCompare(a, b);
            case "exact" -> a.compareTo(b);
            default -> throw new ActionException("STRING.BAD_MODE", "Unknown compare mode '" + mode + "'", "Use exact, ignore_case or natural.");
        };
        return Out.of("result", java.math.BigDecimal.valueOf(Integer.signum(r)), "equal", r == 0);
    }

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
            description = "Find where a text (or regular expression) first appears, starting from a position.")
    @Output(name = "index", type = OoaType.NUMBER, description = "Zero-based position of the match, or -1 when not found.")
    @Output(name = "found", type = OoaType.BOOLEAN, description = "True when a match was found.")
    @Output(name = "match", type = OoaType.STRING, description = "The matched text, empty when not found.")
    public Map<String, Object> find(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Text to search in.") String text,
            @Input(name = "find", type = OoaType.STRING, description = "Text or regular expression to look for.") String find,
            @Input(name = "regex", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Treat 'find' as a regular expression.") boolean regex,
            @Input(name = "start", type = OoaType.NUMBER, required = false, defaultValue = "0", description = "Zero-based position to start searching from.") int start,
            @Input(name = "ignore_case", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Ignore upper/lower case.") boolean ignoreCase) {
        if (start < 0 || start > text.length())
            throw new ActionException("STRING.BAD_POSITION", "Start position " + start + " is outside the text (length " + text.length() + ")", "Use a position from 0 to the text length.");
        try {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(regex ? find : java.util.regex.Pattern.quote(find),
                    ignoreCase ? java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE : 0);
            java.util.regex.Matcher m = p.matcher(text);
            if (m.find(start)) return Out.of("index", java.math.BigDecimal.valueOf(m.start()), "found", true, "match", m.group());
            return Out.of("index", java.math.BigDecimal.valueOf(-1), "found", false, "match", "");
        } catch (PatternSyntaxException e) {
            throw new ActionException("STRING.BAD_REGEX", "Invalid regular expression: " + e.getDescription(), "Escape special characters such as ( ) [ ] . *");
        }
    }

    @Action(id = "length", name = "Get length", category = "Text", idempotent = true, description = "Count the characters in a text.")
    @Output(name = "length", type = OoaType.NUMBER, description = "Number of characters.")
    public Map<String, Object> length(ActionContext ctx, @Input(name = "text", type = OoaType.STRING, description = "Text to measure.") String text) {
        return Out.of("length", java.math.BigDecimal.valueOf(text == null ? 0 : text.codePointCount(0, text.length())));
    }

    @Action(id = "substring", name = "Get part of text", category = "Text", idempotent = true,
            description = "Return the part of a text from a start position, for a length or up to an end position.")
    @Output(name = "result", type = OoaType.STRING, description = "The selected part of the text.")
    public Map<String, Object> substring(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Source text.") String text,
            @Input(name = "start", type = OoaType.NUMBER, required = false, defaultValue = "0", description = "Zero-based start position.") int start,
            @Input(name = "length", type = OoaType.NUMBER, required = false, description = "Number of characters to take; to the end when empty.") Integer length,
            @Input(name = "end", type = OoaType.NUMBER, required = false, description = "Zero-based end position (not included); used when length is empty.") Integer end) {
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

    @Action(id = "trim", name = "Trim", category = "Text", idempotent = true,
            description = "Remove spaces, or the characters you list, from both ends, the start or the end of a text.")
    @Output(name = "result", type = OoaType.STRING, description = "Trimmed text.")
    public Map<String, Object> trim(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Source text.") String text,
            @Input(name = "side", type = OoaType.STRING, required = false, defaultValue = "\"both\"", enumValues = {"both", "left", "right"},
                    description = "Which end to trim.") String side,
            @Input(name = "characters", type = OoaType.STRING, required = false, description = "Characters to remove, e.g. 0 or *-; whitespace when empty.") String chars) {
        java.util.function.IntPredicate strip = chars == null || chars.isEmpty() ? Character::isWhitespace : c -> chars.indexOf(c) >= 0;
        String s = side == null ? "both" : side;
        int a = 0, b = text.length();
        if (!s.equals("right")) while (a < b && strip.test(text.charAt(a))) a++;
        if (!s.equals("left")) while (b > a && strip.test(text.charAt(b - 1))) b--;
        return Out.of("result", text.substring(a, b));
    }

    @Action(id = "reverse", name = "Reverse", category = "Text", idempotent = true, description = "Return the text with its characters in reverse order.")
    @Output(name = "result", type = OoaType.STRING, description = "Reversed text.")
    public Map<String, Object> reverse(ActionContext ctx, @Input(name = "text", type = OoaType.STRING, description = "Source text.") String text) {
        return Out.of("result", new StringBuilder(text).reverse().toString());
    }

    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    @Action(id = "random_string", name = "Random text", category = "Text",
            description = "Generate random text of a given length from letters, digits and/or symbols.")
    @Output(name = "result", type = OoaType.STRING, description = "Random text.")
    public Map<String, Object> randomString(ActionContext ctx,
            @Input(name = "length", type = OoaType.NUMBER, required = false, defaultValue = "12", description = "Number of characters.") int length,
            @Input(name = "letters", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Include letters A-Z and a-z.") boolean letters,
            @Input(name = "digits", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Include digits 0-9.") boolean digits,
            @Input(name = "symbols", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Include symbols such as ! # $ %.") boolean symbols) {
        if (length < 0 || length > 100_000) throw new ActionException("STRING.BAD_LENGTH", "Length must be between 0 and 100000, got " + length, "Choose a smaller positive length.");
        String pool = (letters ? "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz" : "") + (digits ? "0123456789" : "") + (symbols ? "!#$%&*+-=?@^_~" : "");
        if (pool.isEmpty()) throw new ActionException("STRING.NO_CHARACTER_SET", "No character set selected", "Turn on letters, digits or symbols.");
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) sb.append(pool.charAt(RANDOM.nextInt(pool.length())));
        return Out.of("result", sb.toString());
    }

    // ---- Number conversion ----

    @Action(id = "to_number", name = "Text to number", category = "Text", idempotent = true,
            description = "Read a number from text written for a locale, or with the decimal and grouping separators you give.")
    @Output(name = "result", type = OoaType.NUMBER, description = "The number.")
    public Map<String, Object> toNumber(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, example = "1.234,56", description = "Text such as 1,234.56 or 1.234,56.") String text,
            @Input(name = "locale", type = OoaType.STRING, required = false, defaultValue = "\"en-US\"", description = "Locale the text is written in, e.g. en-US, de-DE, fr-FR.") String locale,
            @Input(name = "decimal_separator", type = OoaType.STRING, required = false, description = "Decimal separator; overrides the locale.") String decimalSep,
            @Input(name = "grouping_separator", type = OoaType.STRING, required = false, description = "Thousands separator; overrides the locale.") String groupSep) {
        String loc = locale == null || locale.isBlank() ? "en-US" : locale;
        java.text.DecimalFormatSymbols sym = java.text.DecimalFormatSymbols.getInstance(Locale.forLanguageTag(loc));
        String dec = decimalSep != null && !decimalSep.isEmpty() ? decimalSep : String.valueOf(sym.getDecimalSeparator());
        String grp = groupSep != null && !groupSep.isEmpty() ? groupSep : String.valueOf(sym.getGroupingSeparator());
        if (dec.equals(grp)) throw new ActionException("STRING.BAD_SEPARATORS", "Decimal and grouping separators are both '" + dec + "'", "Use different separators.");
        String s = text == null ? "" : text.strip();
        s = s.replace(grp, "");
        if (grp.isBlank() || Character.isSpaceChar(grp.charAt(0))) s = s.replaceAll("[\\s\\u00A0\\u202F]", "");
        s = s.replace(dec, ".");
        if (s.startsWith("+")) s = s.substring(1);
        try { return Out.of("result", new java.math.BigDecimal(s)); }
        catch (NumberFormatException e) {
            throw new ActionException("STRING.NOT_A_NUMBER", "'" + text + "' is not a number for locale " + loc + " (decimal '" + dec + "', grouping '" + grp + "')",
                    "Check the locale or set decimal_separator and grouping_separator.");
        }
    }

    @Action(id = "to_locale_number", name = "Number to locale text", category = "Text", idempotent = true,
            description = "Write a number as text using the separators of a locale, e.g. 1.234,5 for de-DE.")
    @Output(name = "result", type = OoaType.STRING, description = "Formatted number.")
    public Map<String, Object> toLocaleNumber(ActionContext ctx,
            @Input(name = "value", type = OoaType.NUMBER, description = "Number to write.") java.math.BigDecimal value,
            @Input(name = "locale", type = OoaType.STRING, required = false, defaultValue = "\"en-US\"", description = "Locale, e.g. en-US, de-DE, en-IN.") String locale,
            @Input(name = "decimals", type = OoaType.NUMBER, required = false, description = "Fixed decimal places; keeps the number's own decimals when empty.") Integer decimals,
            @Input(name = "grouping", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Use a thousands separator.") boolean grouping) {
        if (decimals != null && (decimals < 0 || decimals > 50)) throw new ActionException("STRING.BAD_DECIMALS", "Decimals must be between 0 and 50, got " + decimals, "Use a small positive number.");
        java.text.NumberFormat f = java.text.NumberFormat.getNumberInstance(Locale.forLanguageTag(locale == null || locale.isBlank() ? "en-US" : locale));
        int d = decimals != null ? decimals : Math.max(0, value.stripTrailingZeros().scale());
        f.setMinimumFractionDigits(d);
        f.setMaximumFractionDigits(d);
        f.setGroupingUsed(grouping);
        f.setRoundingMode(java.math.RoundingMode.HALF_UP);
        return Out.of("result", f.format(value));
    }
}
