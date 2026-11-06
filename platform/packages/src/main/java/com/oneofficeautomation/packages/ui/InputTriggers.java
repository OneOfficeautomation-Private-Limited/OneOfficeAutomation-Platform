package com.oneofficeautomation.packages.ui;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * Key-press and click triggers (gap G37), shared by runner triggers from One CommandCenter and the attended trigger
 * loop: the settings, which window and element they are limited to, and the pause between firings (debounce). The
 * operating-system hooks sit behind {@link Native} (Windows: packages-windows), so the logic is tested with fake events.
 *
 * <p><b>Privacy.</b> A key-press trigger names one key or key combination, such as F9 or CTRL+ENTER. The native layer
 * compares each key going down with that one key and drops everything else at once: typed text is never recorded,
 * buffered, logged or passed on. The event only says which configured combination was pressed and which window was in
 * front (title and process). Plain typing keys (letters, digits, space, backspace) are refused unless held with CTRL,
 * ALT or WIN, so a trigger cannot count what someone types. A click trigger reports the button, the window and the
 * clicked element's name, type and automation id; never screen contents, field values or password fields' names.
 * Input made by programs (an agent clicking or typing) is ignored: these triggers react to the person at the computer.
 */
public final class InputTriggers {
    private InputTriggers() {}

    public static final int ALT = 0x1, CTRL = 0x2, SHIFT = 0x4, WIN = 0x8;
    public static final int DEFAULT_DEBOUNCE_MS = 500, MAX_DEBOUNCE_MS = 60_000;
    public static final List<String> BUTTONS = List.of("left", "right", "middle", "any");
    public static final String WINDOWS_ONLY = "Key-press and click triggers work on Windows runners only";

    // ---------------- the native layer ----------------

    /** The window in front (key press) or under the pointer (click): its title and process name, nothing else. */
    public record Window(String title, String process) {}

    /** A mouse button going down: which button, where (screen pixels, used only to find the element) and the window there. */
    public record Click(String button, int x, int y, Window window) {}

    /** The UI element at a screen point, for click triggers. */
    public interface ElementLookup extends AutoCloseable {
        /** Name, ControlType, AutomationId (and optionally ClassName) of the element at the point; empty when none. */
        Map<String, String> at(int x, int y);
        @Override void close();
    }

    /** Operating-system hooks. Callbacks come on a thread of the hook's own, never on the hook callback itself. */
    public interface Native {
        /** Calls back each time this exact combination goes down (not repeats while held), with the window in front. */
        AutoCloseable onKeyChord(KeyChord chord, Consumer<Window> onPress) throws Exception;
        /** Calls back for every mouse button that goes down (by the person, not a program), with the window under the pointer. */
        AutoCloseable onMouseDown(Consumer<Click> onClick) throws Exception;
        /** Starts reading UI elements at points (UI Automation); closed with the trigger. */
        ElementLookup openElementLookup();
    }

    // ---------------- settings ----------------

    /** One key with modifiers, as Win32 virtual-key code and the modifier bits above. */
    public record KeyChord(int modifiers, int vk, String text) {
        private static final Map<String, Integer> MODS = Map.of("CTRL", CTRL, "CONTROL", CTRL, "ALT", ALT, "SHIFT", SHIFT, "WIN", WIN);
        private static final Map<String, Integer> KEYS = new LinkedHashMap<>();
        private static final Set<Integer> TYPING = new HashSet<>();
        static {
            Object[][] k = {{"ENTER", 0x0D}, {"TAB", 0x09}, {"ESC", 0x1B}, {"ESCAPE", 0x1B}, {"SPACE", 0x20}, {"BACKSPACE", 0x08}, {"INSERT", 0x2D}, {"DELETE", 0x2E},
                    {"HOME", 0x24}, {"END", 0x23}, {"PAGE_UP", 0x21}, {"PAGE_DOWN", 0x22}, {"UP", 0x26}, {"DOWN", 0x28}, {"LEFT", 0x25}, {"RIGHT", 0x27},
                    {"PAUSE", 0x13}, {"PRINTSCREEN", 0x2C}, {"SCROLL_LOCK", 0x91}};
            for (Object[] e : k) KEYS.put((String) e[0], (Integer) e[1]);
            for (int i = 1; i <= 24; i++) KEYS.put("F" + i, 0x70 + i - 1);
            for (char c = 'A'; c <= 'Z'; c++) { KEYS.put(String.valueOf(c), (int) c); TYPING.add((int) c); }
            for (char c = '0'; c <= '9'; c++) { KEYS.put(String.valueOf(c), (int) c); TYPING.add((int) c); }
            for (int i = 0; i <= 9; i++) { KEYS.put("NUMPAD" + i, 0x60 + i); TYPING.add(0x60 + i); }
            TYPING.add(0x20);
            TYPING.add(0x08);
        }

        /**
         * Parses "CTRL+ENTER", "F9" or "Shift+F2": any of CTRL, ALT, SHIFT, WIN and exactly one other key. Letters,
         * digits, space and backspace need CTRL, ALT or WIN (SHIFT alone is just typing).
         */
        public static KeyChord parse(String combo) {
            if (combo == null || combo.isBlank()) throw new IllegalArgumentException("Give a key or key combination such as F9 or CTRL+ENTER");
            int mods = 0;
            String key = null;
            for (String raw : combo.toUpperCase(Locale.ROOT).split("\\+")) {
                String part = raw.trim().replace(' ', '_');
                if (part.equals("PGUP") || part.equals("PAGEUP")) part = "PAGE_UP";
                if (part.equals("PGDN") || part.equals("PAGEDOWN")) part = "PAGE_DOWN";
                if (part.equals("RETURN")) part = "ENTER";
                if (part.equals("DEL")) part = "DELETE";
                if (MODS.containsKey(part)) { mods |= MODS.get(part); continue; }
                if (!KEYS.containsKey(part)) throw new IllegalArgumentException("Unknown key '" + raw.trim() + "' in '" + combo + "'; use CTRL, ALT, SHIFT, WIN with one of A-Z, 0-9, F1-F24, ENTER, TAB, ESC, arrows and similar named keys");
                if (key != null) throw new IllegalArgumentException("Use one key besides CTRL, ALT, SHIFT and WIN: '" + combo + "'");
                key = part;
            }
            if (key == null) throw new IllegalArgumentException("'" + combo + "' has only modifier keys; add the key itself, such as CTRL+ENTER");
            int vk = KEYS.get(key);
            if (TYPING.contains(vk) && (mods & (CTRL | ALT | WIN)) == 0)
                throw new IllegalArgumentException("'" + combo + "' is a typing key; hold CTRL, ALT or WIN with it (typed text is never watched)");
            StringBuilder t = new StringBuilder();
            if ((mods & CTRL) != 0) t.append("CTRL+");
            if ((mods & ALT) != 0) t.append("ALT+");
            if ((mods & SHIFT) != 0) t.append("SHIFT+");
            if ((mods & WIN) != 0) t.append("WIN+");
            return new KeyChord(mods, vk, t.append(key).toString());
        }

        /** True when this key went down with exactly these modifiers held (CTRL+ENTER is not CTRL+SHIFT+ENTER). */
        public boolean matches(int vkDown, int heldModifiers) { return vkDown == vk && heldModifiers == modifiers; }
    }

    /** Which window the trigger listens in: a title pattern (see {@link TitlePattern}) and/or a process name; both empty = any. */
    public record Scope(String title, String process) {
        public boolean any() { return blank(title) && blank(process); }
        public boolean matches(Window w) {
            if (w == null) return any();
            if (!blank(title) && !TitlePattern.matches(title, w.title())) return false;
            return blank(process) || processMatcher(process).test(w.process() == null ? "" : w.process());
        }
        String describe() { return any() ? "any window" : (blank(title) ? "" : "window '" + title + "'") + (!blank(title) && !blank(process) ? " of " : "") + (blank(process) ? "" : process); }
    }

    /** Which element a click must hit; empty fields match anything. Name uses {@link TitlePattern}; type and id match exactly. */
    public record ElementSpec(String name, String type, String automationId) {
        public boolean any() { return blank(name) && blank(type) && blank(automationId); }
        public boolean matches(Map<String, String> el) {
            if (!blank(name) && !TitlePattern.matches(name, el.getOrDefault("Name", ""))) return false;
            if (!blank(type) && !normType(type).equals(normType(el.getOrDefault("ControlType", "")))) return false;
            return blank(automationId) || automationId.equals(el.getOrDefault("AutomationId", ""));
        }
        private static String normType(String t) { return t.trim().toLowerCase(Locale.ROOT).replace("controltype.", "").replace(" ", ""); }
    }

    /** Lets a firing through when at least this long has passed since the last one let through. */
    public static final class Debounce {
        private final long ms; private final LongSupplier clock; private long last = Long.MIN_VALUE;
        public Debounce(long ms, LongSupplier clock) { this.ms = ms; this.clock = clock; }
        public synchronized boolean allow() {
            long now = clock.getAsLong();
            if (last != Long.MIN_VALUE && now - last < ms) return false;
            last = now;
            return true;
        }
    }

    static boolean blank(String s) { return s == null || s.isBlank(); }

    /** Process names match exactly (case-insensitive, .exe optional), or by wildcard or re: pattern. */
    public static Predicate<String> processMatcher(String p) {
        if (p.startsWith("re:") || p.contains("*") || p.contains("?")) {
            Predicate<String> tp = TitlePattern.of(p);
            return n -> tp.test(n) || tp.test(stripExe(n));
        }
        String want = stripExe(p.trim()).toLowerCase(Locale.ROOT);
        return n -> stripExe(n).toLowerCase(Locale.ROOT).equals(want);
    }

    private static String stripExe(String n) { return n.toLowerCase(Locale.ROOT).endsWith(".exe") ? n.substring(0, n.length() - 4) : n; }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null || String.valueOf(v).isBlank() ? null : String.valueOf(v).trim();
    }

    private static int debounce(Map<String, Object> in) {
        Object v = in.get("debounce_ms");
        if (v == null || String.valueOf(v).isBlank()) return DEFAULT_DEBOUNCE_MS;
        int n;
        try { n = (int) Double.parseDouble(String.valueOf(v)); } catch (NumberFormatException e) { throw new IllegalArgumentException("debounce_ms must be a number of milliseconds"); }
        if (n < 0 || n > MAX_DEBOUNCE_MS) throw new IllegalArgumentException("debounce_ms must be between 0 and " + MAX_DEBOUNCE_MS);
        return n;
    }

    private static void scope(Map<String, Object> in, Map<String, Object> out) {
        String w = str(in, "window"), p = str(in, "process");
        if (w != null) { TitlePattern.of(w); out.put("window", w); }
        if (p != null) {
            if (p.contains("/") || p.contains("\\")) throw new IllegalArgumentException("Give the process name only, such as excel.exe, not a path");
            out.put("process", p);
        }
    }

    /** Checks key-press settings (key; optional window, process, debounce_ms) and returns them normalised. */
    public static Map<String, Object> keyConfig(Map<String, Object> in) {
        Map<String, Object> out = new LinkedHashMap<>();
        String k = str(in, "key");
        if (k == null) throw new IllegalArgumentException("A key-press trigger needs a key or key combination such as F9 or CTRL+ENTER");
        out.put("key", KeyChord.parse(k).text());
        scope(in, out);
        out.put("debounce_ms", debounce(in));
        return out;
    }

    /** Checks click settings (button; window and/or process; optional element_name, element_type, automation_id, debounce_ms). */
    public static Map<String, Object> clickConfig(Map<String, Object> in) {
        Map<String, Object> out = new LinkedHashMap<>();
        String b = Objects.requireNonNullElse(str(in, "button"), "left").toLowerCase(Locale.ROOT);
        if (!BUTTONS.contains(b)) throw new IllegalArgumentException("button must be one of " + String.join(", ", BUTTONS));
        out.put("button", b);
        scope(in, out);
        if (!out.containsKey("window") && !out.containsKey("process")) throw new IllegalArgumentException("A click trigger needs the window (title pattern) or the process it listens in");
        for (String f : List.of("element_name", "element_type", "automation_id")) if (str(in, f) != null) out.put(f, str(in, f));
        if (out.get("element_name") instanceof String n) TitlePattern.of(n);
        out.put("debounce_ms", debounce(in));
        return out;
    }

    private static Scope scopeOf(Map<String, Object> c) { return new Scope(str(c, "window"), str(c, "process")); }

    /** A short description for logs, such as "CTRL+ENTER in window '*Invoice*'". */
    public static String describe(String kind, Map<String, Object> c) {
        Map<String, Object> cfg = kind.equals("key_pressed") ? keyConfig(c) : clickConfig(c);
        Scope s = scopeOf(cfg);
        return kind.equals("key_pressed") ? "key " + cfg.get("key") + " in " + s.describe()
                : cfg.get("button") + " click in " + s.describe() + (cfg.containsKey("element_name") || cfg.containsKey("element_type") || cfg.containsKey("automation_id")
                ? " on " + Objects.toString(cfg.get("element_type"), "element") + (cfg.containsKey("element_name") ? " '" + cfg.get("element_name") + "'" : "") : "");
    }

    // ---------------- watching ----------------

    /**
     * Starts a key-press trigger: each press of the configured combination while a matching window is in front, at
     * most once per debounce_ms, gives {key, window, process} to the sink. Close the result to unhook.
     */
    public static AutoCloseable watchKey(Native n, Map<String, Object> config, LongSupplier clockMs, Consumer<Map<String, Object>> sink) throws Exception {
        if (n == null) throw new IllegalStateException(WINDOWS_ONLY);
        Map<String, Object> c = keyConfig(config);
        KeyChord chord = KeyChord.parse((String) c.get("key"));
        Scope scope = scopeOf(c);
        Debounce d = new Debounce(((Number) c.get("debounce_ms")).longValue(), clockMs);
        return n.onKeyChord(chord, w -> {
            if (!scope.matches(w) || !d.allow()) return;
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("key", chord.text());
            ev.put("window", w == null ? "" : Objects.toString(w.title(), ""));
            ev.put("process", w == null ? "" : Objects.toString(w.process(), ""));
            sink.accept(ev);
        });
    }

    /**
     * Starts a click trigger: each press of the configured button in a matching window, on a matching element when one
     * is set, at most once per debounce_ms, gives {button, window, process, element_name, element_type, automation_id}.
     * The element reader starts at once when an element is set (so a missing reader is reported straight away),
     * otherwise at the first click in the window; without one the event has no element details.
     */
    public static AutoCloseable watchClick(Native n, Map<String, Object> config, LongSupplier clockMs, Consumer<Map<String, Object>> sink) throws Exception {
        if (n == null) throw new IllegalStateException(WINDOWS_ONLY);
        Map<String, Object> c = clickConfig(config);
        String button = (String) c.get("button");
        Scope scope = scopeOf(c);
        ElementSpec spec = new ElementSpec(str(c, "element_name"), str(c, "element_type"), str(c, "automation_id"));
        Debounce d = new Debounce(((Number) c.get("debounce_ms")).longValue(), clockMs);
        Object lock = new Object();
        ElementLookup[] lookup = {spec.any() ? null : n.openElementLookup()};
        boolean[] closed = {false}, noLookup = {false};
        AutoCloseable hook;
        try {
            hook = n.onMouseDown(click -> {
                if (!(button.equals("any") || button.equals(click.button())) || !scope.matches(click.window())) return;
                Map<String, String> el = Map.of();
                synchronized (lock) {
                    if (closed[0]) return;
                    if (lookup[0] == null && !noLookup[0]) {
                        try { lookup[0] = n.openElementLookup(); } catch (RuntimeException e) { noLookup[0] = true; }
                    }
                    if (lookup[0] != null) {
                        try { el = Objects.requireNonNullElse(lookup[0].at(click.x(), click.y()), Map.of()); }
                        catch (RuntimeException e) { if (!spec.any()) return; }   // cannot tell which element: no firing
                    }
                }
                if (!spec.matches(el) || !d.allow()) return;
                Map<String, Object> ev = new LinkedHashMap<>();
                ev.put("button", click.button());
                ev.put("window", click.window() == null ? "" : Objects.toString(click.window().title(), ""));
                ev.put("process", click.window() == null ? "" : Objects.toString(click.window().process(), ""));
                boolean password = "true".equalsIgnoreCase(el.get("IsPassword"));
                ev.put("element_name", password ? "" : el.getOrDefault("Name", ""));
                ev.put("element_type", el.getOrDefault("ControlType", ""));
                ev.put("automation_id", el.getOrDefault("AutomationId", ""));
                sink.accept(ev);
            });
        } catch (Exception | Error e) {
            if (lookup[0] != null) lookup[0].close();
            throw e;
        }
        return () -> {
            try { hook.close(); }
            finally { synchronized (lock) { closed[0] = true; if (lookup[0] != null) lookup[0].close(); lookup[0] = null; } }
        };
    }
}
