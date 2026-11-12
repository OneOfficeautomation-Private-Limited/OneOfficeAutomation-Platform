package com.oneofficeautomation.controlroom;

import java.util.Map;

/**
 * What to do about an error One CommandCenter sends when the error gave no fix of its own (error batch E4,
 * 13 Nov 2026), chosen by its code, so every error a page shows says what to do. The error's own fix always wins.
 */
final class ServerFixes {
    private ServerFixes() {}

    private static final Map<String, String> BY_CODE = Map.ofEntries(
            Map.entry("CR.BAD_INPUT", "Correct the value the message names, then try again."),
            Map.entry("CR.BAD_JSON", "Send the request body as a JSON object (Content-Type: application/json)."),
            Map.entry("CR.NOT_FOUND", "It may have been renamed, moved or deleted. Reload the page and choose it again."),
            Map.entry("VAULT.NOT_FOUND", "Check the credential's name and folder in Manage > Credentials, and that you may use it."),
            Map.entry("CR.CONFLICT", "Reload the page to see the current state, then try again."),
            Map.entry("CR.FORBIDDEN", "An administrator can give your role the permission for this."),
            Map.entry("CR.UNAUTHENTICATED", "Sign in again."),
            Map.entry("CR.TOO_LARGE", "Send less at a time: fewer items, or a smaller file."),
            Map.entry("CR.IO", "Check that the server's data folder has space and that the service account may write there."),
            Map.entry("CR.DB_ERROR", "Check that the database is running and reachable; the server log has the details."));

    /** The fix for a code that came without one; never null. */
    static String forCode(String code) {
        String fix = BY_CODE.get(code);
        if (fix != null) return fix;
        String last = code == null ? "" : code.substring(code.lastIndexOf('.') + 1);
        if (last.startsWith("BAD_")) return "Correct the value the message names, then try again.";
        if (last.startsWith("NO_") || last.endsWith("_NOT_FOUND")) return "Check that it exists and that you may see it, then try again.";
        return "Try again; if it keeps happening, an administrator can look in the server log for this code.";
    }
}
