package com.oneofficeautomation.packages.windows;

import java.util.*;

/** A global hotkey such as CTRL+ALT+F9, as the Win32 RegisterHotKey modifiers and virtual-key code. */
record HotkeyCombo(int modifiers, int vk, String text) {
    static final int MOD_ALT = 0x1, MOD_CONTROL = 0x2, MOD_SHIFT = 0x4, MOD_WIN = 0x8, MOD_NOREPEAT = 0x4000;
    private static final Map<String, Integer> MODS = Map.of("CTRL", MOD_CONTROL, "CONTROL", MOD_CONTROL, "ALT", MOD_ALT, "SHIFT", MOD_SHIFT, "WIN", MOD_WIN);
    private static final Map<String, Integer> KEYS = new HashMap<>();
    static {
        Object[][] k = {{"ENTER", 0x0D}, {"TAB", 0x09}, {"ESC", 0x1B}, {"ESCAPE", 0x1B}, {"SPACE", 0x20}, {"BACKSPACE", 0x08}, {"INSERT", 0x2D}, {"DELETE", 0x2E},
                {"HOME", 0x24}, {"END", 0x23}, {"PAGE_UP", 0x21}, {"PAGE_DOWN", 0x22}, {"UP", 0x26}, {"DOWN", 0x28}, {"LEFT", 0x25}, {"RIGHT", 0x27},
                {"PAUSE", 0x13}, {"PRINTSCREEN", 0x2C}, {"SCROLL_LOCK", 0x91}};
        for (Object[] e : k) KEYS.put((String) e[0], (Integer) e[1]);
        for (int i = 1; i <= 24; i++) KEYS.put("F" + i, 0x70 + i - 1);
        for (char c = 'A'; c <= 'Z'; c++) KEYS.put(String.valueOf(c), (int) c);
        for (char c = '0'; c <= '9'; c++) KEYS.put(String.valueOf(c), (int) c);
        for (int i = 0; i <= 9; i++) KEYS.put("NUMPAD" + i, 0x60 + i);
    }

    /** Parses "CTRL+ALT+F9": any modifiers (CTRL, ALT, SHIFT, WIN) and exactly one other key. */
    static HotkeyCombo parse(String combo) {
        if (combo == null || combo.isBlank()) throw new IllegalArgumentException("No hotkey given");
        int mods = 0;
        Integer vk = null;
        for (String raw : combo.toUpperCase(Locale.ROOT).split("\\+")) {
            String part = raw.trim();
            if (MODS.containsKey(part)) { mods |= MODS.get(part); continue; }
            Integer code = KEYS.get(part);
            if (code == null) throw new IllegalArgumentException("Unknown key '" + part + "' in '" + combo + "'; use names like CTRL, ALT, SHIFT, WIN, A-Z, 0-9, F1-F24");
            if (vk != null) throw new IllegalArgumentException("A hotkey has one key besides CTRL, ALT, SHIFT and WIN: '" + combo + "'");
            vk = code;
        }
        if (vk == null) throw new IllegalArgumentException("The hotkey '" + combo + "' has only modifier keys");
        if (mods == 0 && !(vk >= 0x70 && vk <= 0x87)) throw new IllegalArgumentException("Use at least one of CTRL, ALT, SHIFT or WIN with '" + combo + "' (only F-keys work alone)");
        return new HotkeyCombo(mods, vk, combo.toUpperCase(Locale.ROOT).replace(" ", ""));
    }
}
