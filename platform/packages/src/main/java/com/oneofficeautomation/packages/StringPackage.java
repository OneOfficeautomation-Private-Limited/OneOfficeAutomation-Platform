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
}
