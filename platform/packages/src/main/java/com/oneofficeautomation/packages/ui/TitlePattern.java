package com.oneofficeautomation.packages.ui;

import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Matching for window titles and process or service names, shared by interrupt rules and runner triggers.
 * "re:..." is a regular expression (case-insensitive, found anywhere); text with * or ? is a wildcard pattern that
 * must match the whole text; anything else matches when the text contains it. Case never matters.
 */
public final class TitlePattern {
    private TitlePattern() {}

    public static Predicate<String> of(String pattern) {
        String p = pattern == null ? "" : pattern.trim();
        if (p.startsWith("re:")) {
            Pattern re;
            try { re = Pattern.compile(p.substring(3), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE); }
            catch (PatternSyntaxException e) { throw new IllegalArgumentException("Not a valid regular expression: " + p.substring(3)); }
            return t -> t != null && re.matcher(t).find();
        }
        if (p.contains("*") || p.contains("?")) {
            StringBuilder sb = new StringBuilder();
            for (char c : p.toCharArray()) sb.append(c == '*' ? ".*" : c == '?' ? "." : Pattern.quote(String.valueOf(c)));
            Pattern re = Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL);
            return t -> t != null && re.matcher(t).matches();
        }
        String low = p.toLowerCase(Locale.ROOT);
        return t -> t != null && !low.isEmpty() && t.toLowerCase(Locale.ROOT).contains(low);
    }

    public static boolean matches(String pattern, String text) { return of(pattern).test(text); }
}
