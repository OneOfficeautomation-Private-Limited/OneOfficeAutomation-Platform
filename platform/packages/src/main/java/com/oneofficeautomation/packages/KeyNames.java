package com.oneofficeautomation.packages;

import java.util.Locale;
import java.util.Map;

/**
 * Other spellings people write for the same key (K1, command review batch 6): Page Up, PGUP and PAGE_UP are one key;
 * so are Del and Delete, Return and Enter, Control and Ctrl. Press keys, Type text, Wait for mouse or key, typing into
 * a window in the background and the terminal each read their own names first and fall back to these, so every
 * agent written before keeps its meaning.
 */
public final class KeyNames {
    private KeyNames() {}

    /** A key name squashed: upper case, without spaces, underscores or hyphens ("Page up" → PAGEUP). */
    public static String squash(String name) {
        return name == null ? "" : name.trim().toUpperCase(Locale.ROOT).replaceAll("[\\s_-]", "");
    }

    /** Squashed spelling → the desktop name (DesktopPackage's KEYS). */
    private static final Map<String, String> DESKTOP = Map.ofEntries(
            Map.entry("PAGEUP", "PAGE_UP"), Map.entry("PGUP", "PAGE_UP"), Map.entry("PAGEDOWN", "PAGE_DOWN"), Map.entry("PGDN", "PAGE_DOWN"),
            Map.entry("PGDOWN", "PAGE_DOWN"), Map.entry("DEL", "DELETE"), Map.entry("INS", "INSERT"), Map.entry("RETURN", "ENTER"),
            Map.entry("CONTROL", "CTRL"), Map.entry("ESCAPE", "ESC"), Map.entry("BKSP", "BACKSPACE"), Map.entry("BS", "BACKSPACE"),
            Map.entry("BACK", "BACKSPACE"), Map.entry("WINDOWS", "WIN"), Map.entry("APPS", "MENU"), Map.entry("PRTSC", "PRINTSCREEN"),
            Map.entry("PRINTSCRN", "PRINTSCREEN"), Map.entry("CAPSLOCK", "CAPS_LOCK"), Map.entry("NUMLOCK", "NUM_LOCK"),
            Map.entry("SCROLLLOCK", "SCROLL_LOCK"), Map.entry("SPACEBAR", "SPACE"), Map.entry("ARROWUP", "UP"), Map.entry("ARROWDOWN", "DOWN"),
            Map.entry("ARROWLEFT", "LEFT"), Map.entry("ARROWRIGHT", "RIGHT"), Map.entry("OPTION", "ALT"));

    /** The desktop name for a spelling, e.g. "Page Up" → PAGE_UP, "del" → DELETE; the squashed text when it has none. */
    public static String desktop(String name) {
        String s = squash(name);
        return DESKTOP.getOrDefault(s, s);
    }

    /** Squashed spelling → the terminal name (upper case, as TerminalPackage.keyAction reads it). */
    private static final Map<String, String> TERMINAL = Map.ofEntries(
            Map.entry("PGUP", "PAGEUP"), Map.entry("PGDN", "PAGEDOWN"), Map.entry("PGDOWN", "PAGEDOWN"), Map.entry("DEL", "DELETE"),
            Map.entry("INS", "INSERT"), Map.entry("RETURN", "ENTER"), Map.entry("BKSP", "BACKSPACE"), Map.entry("BS", "BACKSPACE"),
            Map.entry("ESC", "ESCAPE"), Map.entry("BTAB", "BACKTAB"), Map.entry("ERASEEOL", "ERASEEOF"), Map.entry("SYSTEMREQUEST", "SYSREQ"),
            Map.entry("ATTENTION", "ATTN"), Map.entry("KEYENTER", "ENTER"), Map.entry("TABFORWARD", "TAB"), Map.entry("NEXTFIELD", "TAB"));

    /**
     * The terminal name for a spelling: spaces and underscores dropped ("Page Up", "PAGE_UP" → PAGEUP), the other
     * spellings above, AA's names (KEY_ENTER, NEXTFIELD, TABFORWARD), and on 3270 F5 → PF5. Text holding + or a
     * prefix such as SEQ: (Ctrl+C, Alt+X, an escape sequence) is left as it is.
     */
    public static String terminal(String name) {
        String k = name == null ? "" : name.trim().toUpperCase(Locale.ROOT);
        if (k.contains("+") || k.contains(":") || k.matches("CTRL\\s*-\\s*[A-Z]|ALT\\s*-\\s*\\S")) return k;
        String s = squash(k);
        return TERMINAL.getOrDefault(s, s);
    }
}
