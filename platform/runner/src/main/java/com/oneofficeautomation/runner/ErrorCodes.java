package com.oneofficeautomation.runner;

import java.util.*;
import java.util.function.Predicate;

/**
 * Which error codes a Catch block (or a retry policy) entry stands for. An entry is an exact code (FILE.NOT_FOUND), a
 * prefix with a star (BROWSER.*), or one of the names below: error kinds that cut across toolkits, as the reference
 * product's Catch offers them (any "not found", any time-out ...), and older codes that were later split up, so
 * agents written for them keep catching the same errors.
 */
public final class ErrorCodes {
    private ErrorCodes() {}

    /** Kind names a Catch can use, with what they cover (shown in Studio). */
    public static final Map<String, String> KINDS = new LinkedHashMap<>();
    private static final Map<String, Predicate<String>> RULES = new HashMap<>();

    private static void kind(String name, String covers, Predicate<String> rule) { KINDS.put(name, covers); RULES.put(name, rule); }
    private static String tail(String code) { int i = code.indexOf('.'); return i < 0 ? code : code.substring(i + 1); }

    static {
        kind("NOT_FOUND", "Anything not found: file, folder, window, object, record, key ...", c -> tail(c).endsWith("NOT_FOUND"));
        kind("FILE_NOT_FOUND", "A file or folder that does not exist", c -> c.equals("FILE.NOT_FOUND") || c.equals("FOLDER.NOT_FOUND") || tail(c).equals("FILE_NOT_FOUND"));
        kind("WINDOW_NOT_FOUND", "A browser or desktop window that is not open", c -> c.equals("WINDOW.NOT_FOUND") || tail(c).equals("WINDOW_NOT_FOUND"));
        kind("OBJECT_NOT_FOUND", "A control, element, field or image on screen that is not there",
                c -> Set.of("OBJECT_NOT_FOUND", "ELEMENT_NOT_FOUND", "FIELD_NOT_FOUND").contains(tail(c)) || c.equals("IMAGE.NOT_FOUND"));
        kind("TIMEOUT", "Waited too long: page, element, window, process, service or the whole run",
                c -> tail(c).endsWith("TIMEOUT") && !tail(c).startsWith("BAD_"));
        kind("AUTH_FAILED", "Sign-in or permission refused by a service", c -> Set.of("AUTH_FAILED", "UNAUTHORIZED", "FORBIDDEN", "LOGON_FAILED").contains(tail(c)));
        kind("RATE_LIMITED", "A service asked to slow down (too many requests)", c -> tail(c).equals("RATE_LIMITED"));
        // older codes, split into specific ones; a Catch naming them still catches what it used to
        kind("SAP.ERROR", "Any SAP GUI scripting error (older name; now SAP.NOT_RUNNING, SAP.OBJECT_NOT_FOUND ...)",
                c -> c.startsWith("SAP.") && !tail(c).startsWith("BAD_"));
    }

    /** Does a Catch entry (exact code, PREFIX.* or a kind name) cover this error code? */
    public static boolean matches(String entry, String code) {
        if (entry == null || code == null) return false;
        String e = entry.strip();
        if (e.equals(code) || e.equals("*")) return true;
        if (e.endsWith("*")) return code.startsWith(e.substring(0, e.length() - 1));
        Predicate<String> rule = RULES.get(e.toUpperCase(Locale.ROOT));
        return rule != null && rule.test(code);
    }

    /** Does any entry cover the code? An empty list catches everything. */
    public static boolean anyMatches(Collection<?> entries, String code) {
        if (entries == null || entries.isEmpty()) return true;
        for (Object o : entries) if (matches(String.valueOf(o), code)) return true;
        return false;
    }
}
