package com.oneofficeautomation.packages;

import com.oneofficeautomation.packages.ui.DesktopEvents;
import com.oneofficeautomation.sdk.*;

import javax.imageio.ImageIO;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.JFileChooser;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Mouse, keyboard, screen capture, clipboard and attended dialogs (message box, prompts).
 * Key names: ENTER, TAB, ESC, BACKSPACE, DELETE, UP, DOWN, LEFT, RIGHT, HOME, END, PAGE_UP, PAGE_DOWN, SPACE, F1..F12,
 * combined with + (e.g. CTRL+S, ALT+F4, CTRL+SHIFT+ESC).
 * Mouse and keyboard actions can first bring a chosen window to the front, and mouse coordinates can be measured from
 * that window's top-left corner; both need the Window toolkit (Windows runners).
 */
@OoaPackage(id = "ooa.desktop", name = "Mouse, keyboard and screen", version = "1.1.0",
        description = "Click, type, press keys, capture the screen, use the clipboard and show dialogs on the desktop.")
public final class DesktopPackage {
    private static final Map<String, Integer> KEYS = new HashMap<>();
    static {
        Object[][] k = {{"ENTER", KeyEvent.VK_ENTER}, {"TAB", KeyEvent.VK_TAB}, {"ESC", KeyEvent.VK_ESCAPE}, {"ESCAPE", KeyEvent.VK_ESCAPE},
                {"BACKSPACE", KeyEvent.VK_BACK_SPACE}, {"DELETE", KeyEvent.VK_DELETE}, {"INSERT", KeyEvent.VK_INSERT}, {"UP", KeyEvent.VK_UP},
                {"DOWN", KeyEvent.VK_DOWN}, {"LEFT", KeyEvent.VK_LEFT}, {"RIGHT", KeyEvent.VK_RIGHT}, {"HOME", KeyEvent.VK_HOME}, {"END", KeyEvent.VK_END},
                {"PAGE_UP", KeyEvent.VK_PAGE_UP}, {"PAGE_DOWN", KeyEvent.VK_PAGE_DOWN}, {"SPACE", KeyEvent.VK_SPACE}, {"CTRL", KeyEvent.VK_CONTROL},
                {"ALT", KeyEvent.VK_ALT}, {"SHIFT", KeyEvent.VK_SHIFT}, {"WIN", KeyEvent.VK_WINDOWS}, {"PRINTSCREEN", KeyEvent.VK_PRINTSCREEN},
                {"CAPS_LOCK", KeyEvent.VK_CAPS_LOCK}, {"NUM_LOCK", KeyEvent.VK_NUM_LOCK}, {"SCROLL_LOCK", KeyEvent.VK_SCROLL_LOCK},
                {"PAUSE", KeyEvent.VK_PAUSE}, {"MENU", KeyEvent.VK_CONTEXT_MENU}, {"ALTGR", KeyEvent.VK_ALT_GRAPH}};
        for (Object[] e : k) KEYS.put((String) e[0], (Integer) e[1]);
        for (int i = 1; i <= 12; i++) KEYS.put("F" + i, KeyEvent.VK_F1 + i - 1);
        for (int i = 13; i <= 24; i++) KEYS.put("F" + i, KeyEvent.VK_F13 + i - 13);
        for (char c = 'A'; c <= 'Z'; c++) KEYS.put(String.valueOf(c), KeyEvent.VK_A + (c - 'A'));
        for (char c = '0'; c <= '9'; c++) KEYS.put(String.valueOf(c), KeyEvent.VK_0 + (c - '0'));
        for (int i = 0; i <= 9; i++) KEYS.put("NUMPAD" + i, KeyEvent.VK_NUMPAD0 + i);
    }

    /** Parses "CTRL+SHIFT+ESC" into key codes; unknown names are rejected with a list of valid ones. */
    static List<Integer> parseKeys(String combo) {
        List<Integer> codes = new ArrayList<>();
        for (String part : combo.toUpperCase(Locale.ROOT).split("\\+")) {
            Integer c = KEYS.get(part.trim());
            if (c == null) throw new ActionException("DESKTOP.BAD_KEY", "Unknown key '" + part.trim() + "' in '" + combo + "'",
                    "Use names like ENTER, TAB, ESC, F5, CTRL+S, ALT+F4.");
            codes.add(c);
        }
        return codes;
    }

    private static int button(String b) {
        return switch (b == null ? "left" : b) { case "right" -> InputEvent.BUTTON3_DOWN_MASK; case "middle" -> InputEvent.BUTTON2_DOWN_MASK; default -> InputEvent.BUTTON1_DOWN_MASK; };
    }

    /** Checks an enumerated input and returns its value (or the default when empty). */
    static String choice(String input, String value, String def, String... allowed) {
        String v = value == null || value.isBlank() ? def : value.strip().toLowerCase(Locale.ROOT);
        if (!Arrays.asList(allowed).contains(v))
            throw new ActionException("DESKTOP.BAD_OPTION", "Unknown " + input + " '" + value + "'", "Use one of: " + String.join(", ", allowed) + ".");
        return v;
    }

    // ---------- window targeting ----------

    private static final String D_WINDOW = "Window to bring to the front first: text in its title, a pattern with * or ?, or re:regex. Empty uses the window that is in front.";
    private static final String D_RELATIVE = "screen: x and y are measured from the top-left of the screen; window: from the top-left of the chosen window.";

    /** Screen point for x/y measured from the screen or from a window rectangle {x, y, width, height}. */
    static int[] target(int x, int y, String relativeTo, int[] windowBounds) {
        if (!"window".equals(relativeTo)) return new int[]{x, y};
        if (windowBounds == null) throw new ActionException("DESKTOP.NO_WINDOW", "Coordinates relative to a window need the window input", "Fill in window, or use relative_to screen.");
        return new int[]{windowBounds[0] + x, windowBounds[1] + y};
    }

    /** Brings the window to the front (when given) and returns its rectangle; null when no window was asked for. */
    static int[] focusWindow(String window, boolean needBounds) {
        if (window == null || window.isBlank()) {
            if (needBounds) throw new ActionException("DESKTOP.NO_WINDOW", "Coordinates relative to a window need the window input", "Fill in window, or use relative_to screen.");
            return null;
        }
        DesktopEvents d = DesktopEvents.installed().orElseThrow(() -> new ActionException("DESKTOP.WINDOW_UNAVAILABLE",
                "Choosing a window needs a Windows runner with the Window toolkit installed", "Leave window empty to use the window in front."));
        if (!d.activateWindow(window)) throw new ActionException("DESKTOP.WINDOW_NOT_FOUND", "No visible window matches '" + window + "'",
                "Use Window > List windows to see the exact titles.", true);
        int[] b = d.windowBounds(window).orElse(null);
        if (needBounds && b == null) throw new ActionException("DESKTOP.WINDOW_UNAVAILABLE", "Cannot read the position of window '" + window + "'", "Use relative_to screen.");
        return b;
    }

    // ---------- mouse ----------

    @Action(id = "mouse_click", name = "Mouse click", category = "Mouse", capabilities = "desktop",
            description = "Click, double-click, or press or release a mouse button at a point on the screen or in a window.",
            panel = "window, x, y, button, button_action, double_click | relative_to, modifier_keys")
    public Map<String, Object> click(ActionContext ctx,
            @Input(name = "x", type = OoaType.NUMBER, description = "Horizontal position in pixels from the left.") int x,
            @Input(name = "y", type = OoaType.NUMBER, description = "Vertical position in pixels from the top.") int y,
            @Input(name = "button", type = OoaType.STRING, required = false, defaultValue = "\"left\"", enumValues = {"left", "right", "middle"}, description = "Mouse button.") String b,
            @Input(name = "double_click", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Double-click.") boolean dbl,
            @Input(name = "button_action", type = OoaType.STRING, required = false, defaultValue = "\"click\"", enumValues = {"click", "down", "up"},
                    description = "click presses and releases; down only presses (hold, e.g. to start a drag); up only releases.") String buttonAction,
            @Input(name = "relative_to", type = OoaType.STRING, required = false, defaultValue = "\"screen\"", enumValues = {"screen", "window"}, description = D_RELATIVE) String relativeTo,
            @Input(name = "window", type = OoaType.STRING, required = false, description = D_WINDOW) String window,
            @Input(name = "modifier_keys", type = OoaType.STRING, required = false, example = "CTRL", description = "Keys held during the click, e.g. CTRL, SHIFT or CTRL+SHIFT.") String modifiers) {
        String act = choice("button_action", buttonAction, "click", "click", "down", "up");
        String rel = choice("relative_to", relativeTo, "screen", "screen", "window");
        String btn = choice("button", b, "left", "left", "right", "middle");
        List<Integer> mods = modifiers == null || modifiers.isBlank() ? List.of() : parseKeys(modifiers);
        UiGuard.before(ctx);
        Robot r = DesktopSupport.robot();
        int[] p = target(x, y, rel, focusWindow(window, "window".equals(rel)));
        r.mouseMove(p[0], p[1]);
        for (int m : mods) r.keyPress(m);
        try {
            int mask = button(btn);
            switch (act) {
                case "down" -> r.mousePress(mask);
                case "up" -> r.mouseRelease(mask);
                default -> { for (int i = 0; i < (dbl ? 2 : 1); i++) { r.mousePress(mask); r.mouseRelease(mask); r.delay(40); } }
            }
        } finally {
            for (int i = mods.size() - 1; i >= 0; i--) r.keyRelease(mods.get(i));
        }
        return Map.of();
    }

    @Action(id = "mouse_move", name = "Mouse move", category = "Mouse", capabilities = "desktop", description = "Move the pointer to a point on the screen or in a window, at once or gradually.",
            panel = "window, relative_to, x, y, duration_ms | [Start from another point: from_x, from_y]")
    public Map<String, Object> move(ActionContext ctx,
            @Input(name = "x", type = OoaType.NUMBER, description = "Horizontal position.") int x,
            @Input(name = "y", type = OoaType.NUMBER, description = "Vertical position.") int y,
            @Input(name = "relative_to", type = OoaType.STRING, required = false, defaultValue = "\"screen\"", enumValues = {"screen", "window"}, description = D_RELATIVE) String relativeTo,
            @Input(name = "window", type = OoaType.STRING, required = false, description = D_WINDOW) String window,
            @Input(name = "duration_ms", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0, description = "Glide to the point over this many milliseconds; 0 jumps there at once.") int duration,
            @Input(name = "from_x", type = OoaType.NUMBER, required = false,
                    description = "Put the pointer at this point first (with from_y, measured like x and y), then move to x, y; e.g. after pressing a button down, for a drag.") Integer fromX,
            @Input(name = "from_y", type = OoaType.NUMBER, required = false, description = "Vertical position of the starting point (with from_x).") Integer fromY) {
        String rel = choice("relative_to", relativeTo, "screen", "screen", "window");
        checkFrom(fromX, fromY);
        if (duration < 0) throw new ActionException("DESKTOP.BAD_DURATION", "duration_ms must be 0 or more, not " + duration, "Use 0 to jump, or e.g. 500 to glide.");
        Robot r = DesktopSupport.robot();
        int[] wb = focusWindow(window, "window".equals(rel));
        int[] p = target(x, y, rel, wb);
        Point cur = fromX == null && duration > 0 ? MouseInfo.getPointerInfo().getLocation() : new Point(p[0], p[1]);
        for (int[] s : movePoints(fromX, fromY, p, rel, wb, duration, cur.x, cur.y)) { r.mouseMove(s[0], s[1]); if (s[2] > 0) r.delay(s[2]); }
        return Map.of();
    }

    /** Mouse move without a starting point (the signature before from_x and from_y were added). */
    public Map<String, Object> move(ActionContext ctx, int x, int y, String relativeTo, String window, int duration) {
        return move(ctx, x, y, relativeTo, window, duration, null, null);
    }

    /** from_x and from_y go together. */
    static void checkFrom(Integer fromX, Integer fromY) {
        if ((fromX == null) != (fromY == null))
            throw new ActionException("DESKTOP.BAD_POINT", "The starting point needs both from_x and from_y", "Fill in both, or leave both empty to start where the pointer is.");
    }

    /**
     * The pointer positions {x, y, pause_ms} of a move to the screen point {@code to}: the starting point first (from_x and
     * from_y measured like x and y), then the glide when a duration is given, ending exactly on the target. Without a
     * starting point the glide starts at the pointer's current place (curX, curY).
     */
    static List<int[]> movePoints(Integer fromX, Integer fromY, int[] to, String relativeTo, int[] windowBounds, int durationMs, int curX, int curY) {
        checkFrom(fromX, fromY);
        List<int[]> out = new ArrayList<>();
        int sx = curX, sy = curY;
        if (fromX != null) {
            int[] f = target(fromX, fromY, relativeTo, windowBounds);
            out.add(new int[]{f[0], f[1], durationMs > 0 ? 20 : 0});
            sx = f[0]; sy = f[1];
        }
        if (durationMs > 0) out.addAll(glide(sx, sy, to[0], to[1], durationMs));
        if (out.isEmpty() || out.get(out.size() - 1)[0] != to[0] || out.get(out.size() - 1)[1] != to[1]) out.add(new int[]{to[0], to[1], 0});
        return out;
    }

    /** Intermediate points {x, y, pause_ms} for a gradual move, about one step every 10 ms. */
    static List<int[]> glide(int fromX, int fromY, int toX, int toY, int durationMs) {
        int steps = Math.max(1, Math.min(500, durationMs / 10));
        int pause = Math.max(1, durationMs / steps);
        List<int[]> out = new ArrayList<>();
        for (int i = 1; i <= steps; i++) out.add(new int[]{fromX + (toX - fromX) * i / steps, fromY + (toY - fromY) * i / steps, pause});
        return out;
    }

    @Action(id = "mouse_scroll", name = "Mouse scroll", category = "Mouse", capabilities = "desktop",
            description = "Scroll the mouse wheel up, down, left or right, optionally over a chosen point or window.",
            panel = "direction, amount, delay_ms | x, y, window, relative_to")
    public Map<String, Object> scroll(ActionContext ctx,
            @Input(name = "amount", type = OoaType.NUMBER, description = "Wheel notches; negative scrolls up (or left) when direction is empty.") int amount,
            @Input(name = "direction", type = OoaType.STRING, required = false, enumValues = {"up", "down", "left", "right"},
                    description = "Scroll direction; when empty the sign of amount decides (positive scrolls down). Left and right hold SHIFT while scrolling.") String direction,
            @Input(name = "x", type = OoaType.NUMBER, required = false, description = "Move the pointer here before scrolling (with y).") Integer x,
            @Input(name = "y", type = OoaType.NUMBER, required = false, description = "Vertical position to scroll at (with x).") Integer y,
            @Input(name = "relative_to", type = OoaType.STRING, required = false, defaultValue = "\"screen\"", enumValues = {"screen", "window"}, description = D_RELATIVE) String relativeTo,
            @Input(name = "window", type = OoaType.STRING, required = false, description = D_WINDOW) String window,
            @Input(name = "delay_ms", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0, description = "Pause between notches; 0 scrolls all notches at once.") int delay) {
        String rel = choice("relative_to", relativeTo, "screen", "screen", "window");
        String dir = direction == null || direction.isBlank() ? null : choice("direction", direction, "down", "up", "down", "left", "right");
        int notches = wheel(amount, dir);
        UiGuard.before(ctx);
        Robot r = DesktopSupport.robot();
        int[] bounds = focusWindow(window, "window".equals(rel) && x != null && y != null);
        if (x != null && y != null) { int[] p = target(x, y, rel, bounds); r.mouseMove(p[0], p[1]); }
        boolean sideways = "left".equals(dir) || "right".equals(dir);
        if (sideways) r.keyPress(KeyEvent.VK_SHIFT);
        try {
            if (delay <= 0) r.mouseWheel(notches);
            else for (int i = 0; i < Math.abs(notches); i++) { r.mouseWheel(Integer.signum(notches)); r.delay(delay); }
        } finally { if (sideways) r.keyRelease(KeyEvent.VK_SHIFT); }
        return Map.of();
    }

    /** Wheel notches for Robot.mouseWheel: positive scrolls down (or right with SHIFT), negative up (or left). */
    static int wheel(int amount, String direction) {
        if (direction == null) return amount;
        int n = Math.abs(amount);
        return "up".equals(direction) || "left".equals(direction) ? -n : n;
    }

    // ---------- keyboard ----------

    /** One part of a key sequence: text to type, or keys to press, hold down or release. */
    record KeyStep(String text, List<Integer> keys, String action, int times) {
        static KeyStep text(String t) { return new KeyStep(t, List.of(), "text", 1); }
    }

    /**
     * Splits text with special keys in square brackets into steps: [ENTER], [CTRL+S], [TAB 3] (press three times),
     * [SHIFT DOWN] and [SHIFT UP] (hold and release). [[ types a literal [. Brackets that do not name keys are typed as text.
     */
    static List<KeyStep> parseSequence(String text) {
        List<KeyStep> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '[' && i + 1 < text.length() && text.charAt(i + 1) == '[') { buf.append('['); i += 2; continue; }
            int close = c == '[' ? text.indexOf(']', i + 1) : -1;
            KeyStep k = close > i ? keyToken(text.substring(i + 1, close)) : null;
            if (k == null) { buf.append(c); i++; continue; }
            if (!buf.isEmpty()) { out.add(KeyStep.text(buf.toString())); buf.setLength(0); }
            out.add(k);
            i = close + 1;
        }
        if (!buf.isEmpty()) out.add(KeyStep.text(buf.toString()));
        return out;
    }

    private static KeyStep keyToken(String token) {
        String[] parts = token.strip().split("\\s+");
        if (parts.length == 0 || parts.length > 2 || parts[0].isEmpty()) return null;
        String action = "press";
        int times = 1;
        if (parts.length == 2) {
            String p = parts[1].toUpperCase(Locale.ROOT);
            if (p.equals("DOWN")) action = "down";
            else if (p.equals("UP")) action = "up";
            else if (p.matches("\\d{1,3}")) times = Math.max(1, Integer.parseInt(p));
            else return null;
        }
        try { return new KeyStep(null, parseKeys(parts[0]), action, times); }
        catch (ActionException e) { return null; }
    }

    private static void pressCodes(Robot r, List<Integer> codes, String action, int times, int holdMs, int delayMs) {
        switch (action) {
            case "down" -> { for (int c : codes) r.keyPress(c); }
            case "up" -> { for (int i = codes.size() - 1; i >= 0; i--) r.keyRelease(codes.get(i)); }
            default -> {
                for (int t = 0; t < Math.max(1, times); t++) {
                    for (int c : codes) r.keyPress(c);
                    if (holdMs > 0) r.delay(holdMs);
                    for (int i = codes.size() - 1; i >= 0; i--) r.keyRelease(codes.get(i));
                    r.delay(Math.max(0, delayMs));
                }
            }
        }
    }

    /** Characters typed with Shift on a US keyboard, and the key that gives them. */
    private static final String SHIFTED = "!@#$%^&*()_+{}|:\"<>?~", UNSHIFTED = "1234567890-=[]\\;',./`";

    /** The key code and whether Shift is held, for one character; null when no key gives it. */
    static int[] keyFor(char c) {
        int s = SHIFTED.indexOf(c);
        if (s >= 0) return new int[] { KeyEvent.getExtendedKeyCodeForChar(UNSHIFTED.charAt(s)), 1 };
        int code = KeyEvent.getExtendedKeyCodeForChar(c);
        if (code == KeyEvent.VK_UNDEFINED) return null;
        return new int[] { code, Character.isUpperCase(c) ? 1 : 0 };
    }

    private static void typeKeystrokes(Robot r, String text, int delay) { typeKeystrokes(r, text, delay, false); }

    /** Types each character as a key press; a secret's characters never appear in an error. */
    private static void typeKeystrokes(Robot r, String text, int delay, boolean secret) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') { r.keyPress(KeyEvent.VK_ENTER); r.keyRelease(KeyEvent.VK_ENTER); continue; }
            int[] k = keyFor(c);
            if (k == null) throw secret
                    ? new ActionException("DESKTOP.UNTYPABLE", "Character " + (i + 1) + " of the credential cannot be typed as a keystroke", "Credentials are typed key by key; use characters found on a US keyboard.")
                    : new ActionException("DESKTOP.UNTYPABLE", "Cannot type '" + c + "' as a keystroke", "Use method paste for this text.");
            try {
                if (k[1] == 1) r.keyPress(KeyEvent.VK_SHIFT);
                r.keyPress(k[0]); r.keyRelease(k[0]);
            } catch (IllegalArgumentException e) {
                throw new ActionException("DESKTOP.UNTYPABLE", secret ? "Character " + (i + 1) + " of the credential cannot be typed on this keyboard" : "Cannot type '" + c + "' on this keyboard",
                        secret ? "Credentials are typed key by key; use characters found on a US keyboard." : "Use method paste for this text.");
            } finally {
                if (k[1] == 1) r.keyRelease(KeyEvent.VK_SHIFT);
            }
            r.delay(delay);
        }
    }

    private static void paste(Robot r, String text, boolean restore) {
        Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
        Transferable previous = null;
        if (restore) try { previous = cb.getContents(null); } catch (IllegalStateException ignored) { }
        cb.setContents(new StringSelection(text), null);
        int mod = ProcessRunner.WINDOWS || !System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac") ? KeyEvent.VK_CONTROL : KeyEvent.VK_META;
        r.keyPress(mod); r.keyPress(KeyEvent.VK_V); r.keyRelease(KeyEvent.VK_V); r.keyRelease(mod);
        r.delay(150);
        if (previous != null) try { cb.setContents(previous, null); } catch (IllegalStateException ignored) { }
    }

    public Map<String, Object> typeText(ActionContext ctx, String text, String method, int delay, String window, boolean special, boolean restore, int before) {
        return typeText(ctx, text, method, delay, window, special, restore, before, null);
    }

    @Action(id = "type_text", name = "Type text", category = "Keyboard", capabilities = "desktop",
            description = "Type text, or a credential from the vault, into the active or a chosen window (text is pasted via the clipboard by default, which handles any language; a credential is always typed key by key and never logged); can include special keys such as [ENTER].",
            panel = "window, (Type: Text = text, special_keys / A credential from the vault = credential), method, delay_ms | restore_clipboard, delay_before_ms")
    public Map<String, Object> typeText(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, required = false, description = "Text to type; used when no credential is given.") String text,
            @Input(name = "method", type = OoaType.STRING, required = false, defaultValue = "\"paste\"", enumValues = {"paste", "keystrokes"},
                    description = "paste uses the clipboard (restored afterwards); keystrokes types letters and digits one by one.") String method,
            @Input(name = "delay_ms", type = OoaType.NUMBER, required = false, defaultValue = "20", visibleWhen = "method=keystrokes", min = 0, description = "Delay between keystrokes.") int delay,
            @Input(name = "window", type = OoaType.STRING, required = false, description = D_WINDOW) String window,
            @Input(name = "special_keys", type = OoaType.BOOLEAN, required = false, defaultValue = "false",
                    description = "Read keys in square brackets: [ENTER], [TAB 3], [CTRL+S], [SHIFT DOWN] ... [SHIFT UP]; [[ types a [.") boolean special,
            @Input(name = "restore_clipboard", type = OoaType.BOOLEAN, required = false, defaultValue = "true", visibleWhen = "method=paste", description = "With paste, put the previous clipboard content back afterwards.") boolean restore,
            @Input(name = "delay_before_ms", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0, description = "Wait this long after bringing the window to the front, before typing.") int before,
            @Input(name = "credential", type = OoaType.CREDENTIAL, required = false,
                    description = "Credential attribute from the vault, e.g. a password; typed key by key (never through the clipboard) instead of text.") Object credential) {
        String m = choice("method", method, "paste", "paste", "keystrokes");
        if (credential != null) {
            if (!(credential instanceof SecureString secret))
                throw new ActionException("DESKTOP.NOT_A_CREDENTIAL", "The credential input needs a vault credential, not plain text", "Pick a credential attribute from the vault, or use the text input.");
            UiGuard.before(ctx);
            Robot r = DesktopSupport.robot();
            focusWindow(window, false);
            if (before > 0) r.delay(Math.min(60_000, before));
            typeKeystrokes(r, secret.reveal(), Math.max(delay, 0), true);
            return Map.of();
        }
        if (text == null) throw new ActionException("DESKTOP.NOTHING_TO_TYPE", "Neither text nor a credential was given", "Fill in the text, or pick a credential from the vault.");
        List<KeyStep> steps = special ? parseSequence(text) : List.of(KeyStep.text(text));
        UiGuard.before(ctx);
        Robot r = DesktopSupport.robot();
        focusWindow(window, false);
        if (before > 0) r.delay(Math.min(60_000, before));
        Set<Integer> held = new LinkedHashSet<>();
        try {
            for (KeyStep s : steps) {
                if (s.text() != null) { if ("keystrokes".equals(m)) typeKeystrokes(r, s.text(), delay); else paste(r, s.text(), restore); continue; }
                pressCodes(r, s.keys(), s.action(), s.times(), 0, Math.max(delay, 20));
                if ("down".equals(s.action())) held.addAll(s.keys()); else if ("up".equals(s.action())) s.keys().forEach(held::remove);
            }
        } finally {
            for (int k : held) r.keyRelease(k);  // never leave a key held down
        }
        return Map.of();
    }

    @Action(id = "press_keys", name = "Press keys", category = "Keyboard", capabilities = "desktop",
            description = "Press, hold down or release a key or key combination, e.g. ENTER, CTRL+S or ALT+F4, in the active or a chosen window; repeat if needed.",
            panel = "window, keys, times, key_action | delay_ms, hold_ms")
    public Map<String, Object> pressKeys(ActionContext ctx,
            @Input(name = "keys", type = OoaType.STRING, example = "CTRL+S", description = "Key or combination joined with +.") String keys,
            @Input(name = "times", type = OoaType.NUMBER, required = false, defaultValue = "1", min = 1, description = "How many times to press.") int times,
            @Input(name = "key_action", type = OoaType.STRING, required = false, defaultValue = "\"press\"", enumValues = {"press", "down", "up"},
                    description = "press presses and releases; down holds the keys; up releases keys held by an earlier step.") String keyAction,
            @Input(name = "delay_ms", type = OoaType.NUMBER, required = false, defaultValue = "40", min = 0, description = "Pause after each press.") int delay,
            @Input(name = "hold_ms", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0, description = "How long to keep the keys down on each press.") int hold,
            @Input(name = "window", type = OoaType.STRING, required = false, description = D_WINDOW) String window) {
        List<Integer> codes = parseKeys(keys);  // validated before touching the desktop
        String act = choice("key_action", keyAction, "press", "press", "down", "up");
        UiGuard.before(ctx);
        Robot r = DesktopSupport.robot();
        focusWindow(window, false);
        pressCodes(r, codes, act, times, Math.max(0, hold), Math.max(0, delay));
        return Map.of();
    }

    // ---------- screen ----------

    @Action(id = "capture_screen", name = "Capture screen", category = "Screen", capabilities = "desktop",
            description = "Save a screenshot of the whole screen, all screens, a region or a window as a PNG, JPEG or BMP file.",
            panel = "[Capture one window only: window], [Capture part of the screen: x, y, width, height], path, overwrite | format, all_screens")
    @Output(name = "path", type = OoaType.FILE, description = "Saved image path.")
    public Map<String, Object> capture(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "Image file to save.") String path,
            @Input(name = "x", type = OoaType.NUMBER, required = false, description = "Region left (whole screen when empty).") Integer x,
            @Input(name = "y", type = OoaType.NUMBER, required = false, description = "Region top.") Integer y,
            @Input(name = "width", type = OoaType.NUMBER, required = false, description = "Region width.") Integer w,
            @Input(name = "height", type = OoaType.NUMBER, required = false, description = "Region height.") Integer h,
            @Input(name = "format", type = OoaType.STRING, required = false, defaultValue = "\"png\"", enumValues = {"png", "jpg", "bmp"}, description = "Image format.") String format,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Replace an existing file; when false an existing file stops the step.") boolean overwrite,
            @Input(name = "all_screens", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Capture every monitor together instead of the main one (when no region or window is given).") boolean allScreens,
            @Input(name = "window", type = OoaType.STRING, required = false, description = "Capture only this window (title text, pattern with * or ?, or re:regex); x and y are then measured from the window and default to its whole area.") String window) {
        String fmt = choice("format", format, "png", "png", "jpg", "bmp");
        Path p = ctx.resolvePath(path);
        if (!overwrite && Files.exists(p)) throw new ActionException("FILE.EXISTS", "File already exists: " + p, "Set overwrite to true or choose another name.");
        Robot r = DesktopSupport.robot();
        int[] wb = focusWindow(window, window != null && !window.isBlank());
        Rectangle area = area(x, y, w, h, wb, allScreens ? allScreens() : new Rectangle(Toolkit.getDefaultToolkit().getScreenSize()));
        if (wb != null) r.delay(200);  // let the window repaint after coming to the front
        try {
            if (p.getParent() != null) Files.createDirectories(p.getParent());
            BufferedImage img = r.createScreenCapture(area);
            if (!"png".equals(fmt)) {  // JPEG and BMP writers need an image without alpha
                BufferedImage rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
                rgb.getGraphics().drawImage(img, 0, 0, null);
                img = rgb;
            }
            if (!ImageIO.write(img, "jpg".equals(fmt) ? "jpeg" : fmt, p.toFile())) throw new IOException("No writer for " + fmt);
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), null, true); }
        return Map.of("path", p.toString());
    }

    /** Capture rectangle: a region (relative to the window when one is given), the window, or the whole screen area. */
    static Rectangle area(Integer x, Integer y, Integer w, Integer h, int[] window, Rectangle screen) {
        if (window != null) {
            int left = window[0] + (x == null ? 0 : x), top = window[1] + (y == null ? 0 : y);
            int width = w == null ? window[2] - (x == null ? 0 : x) : w, height = h == null ? window[3] - (y == null ? 0 : y) : h;
            return new Rectangle(left, top, Math.max(1, width), Math.max(1, height));
        }
        if (x == null || y == null || w == null || h == null) return screen;
        if (w <= 0 || h <= 0) throw new ActionException("DESKTOP.BAD_AREA", "Width and height must be greater than 0", "Check the region size.");
        return new Rectangle(x, y, w, h);
    }

    private static Rectangle allScreens() {
        Rectangle all = new Rectangle();
        for (GraphicsDevice d : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) all = all.union(d.getDefaultConfiguration().getBounds());
        return all;
    }

    static BufferedImage screen() { return DesktopSupport.robot().createScreenCapture(new Rectangle(Toolkit.getDefaultToolkit().getScreenSize())); }

    // ---------- clipboard ----------

    private static String clipboardText() {
        try {
            Transferable t = Toolkit.getDefaultToolkit().getSystemClipboard().getContents(null);
            return t != null && t.isDataFlavorSupported(DataFlavor.stringFlavor) ? (String) t.getTransferData(DataFlavor.stringFlavor) : "";
        } catch (UnsupportedFlavorException | IOException | IllegalStateException e) { throw new ActionException("CLIPBOARD.BUSY", "Clipboard unavailable: " + e.getMessage(), null, true); }
    }

    @Action(id = "clipboard_get", name = "Get clipboard text", category = "Clipboard", capabilities = "desktop",
            description = "Read text from the clipboard, optionally waiting until text arrives (e.g. after pressing CTRL+C).",
            panel = "| wait_seconds, trim")
    @Output(name = "text", type = OoaType.STRING, description = "Clipboard text, empty when it holds no text.")
    @Output(name = "has_text", type = OoaType.BOOLEAN, description = "True when the clipboard held text.")
    public Map<String, Object> clipboardGet(ActionContext ctx,
            @Input(name = "wait_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0, description = "Keep checking up to this long while the clipboard is empty or busy; 0 reads once.") double wait,
            @Input(name = "trim", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Remove spaces and line breaks at both ends.") boolean trim) {
        DesktopSupport.requireDesktop();
        long end = System.currentTimeMillis() + (long) (Math.max(0, wait) * 1000);
        String text;
        while (true) {
            try { text = clipboardText(); } catch (ActionException e) { if (System.currentTimeMillis() >= end) throw e; text = ""; }
            if (!text.isEmpty() || System.currentTimeMillis() >= end) break;
            ctx.checkCancelled();
            try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        return Map.of("text", trim ? text.strip() : text, "has_text", !text.isEmpty());
    }

    @Action(id = "clipboard_set", name = "Copy to clipboard", category = "Clipboard", capabilities = "desktop", description = "Put text on the clipboard, replacing it or adding to what is there.",
            panel = "text | append")
    public Map<String, Object> clipboardSet(ActionContext ctx, @Input(name = "text", type = OoaType.STRING, description = "Text to copy.") String text,
            @Input(name = "append", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Add the text after the current clipboard text instead of replacing it.") boolean append) {
        DesktopSupport.requireDesktop();
        String value = append ? clipboardText() + (text == null ? "" : text) : (text == null ? "" : text);
        try { Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(value), null); }
        catch (IllegalStateException e) { throw new ActionException("CLIPBOARD.BUSY", "Clipboard unavailable: " + e.getMessage(), null, true); }
        return Map.of();
    }

    @Action(id = "clipboard_clear", name = "Clear clipboard", category = "Clipboard", capabilities = "desktop", description = "Empty the clipboard.")
    public Map<String, Object> clipboardClear(ActionContext ctx) { return clipboardSet(ctx, "", false); }

    // ---------- dialogs ----------

    private static <T> T onEdt(java.util.concurrent.Callable<T> c) {
        AtomicReference<T> ref = new AtomicReference<>();
        AtomicReference<Exception> err = new AtomicReference<>();
        try { SwingUtilities.invokeAndWait(() -> { try { ref.set(c.call()); } catch (Exception e) { err.set(e); } }); }
        catch (Exception e) { throw new ActionException("DESKTOP.DIALOG_FAILED", e.getMessage()); }
        if (err.get() != null) throw new ActionException("DESKTOP.DIALOG_FAILED", err.get().getMessage());
        return ref.get();
    }

    private static JDialog dialog(JOptionPane pane, String title, int timeoutSeconds, boolean onTop) {
        JDialog d = pane.createDialog(null, title == null || title.isBlank() ? "ONEOFFICEAUTOMATION" : title);
        d.setAlwaysOnTop(onTop);
        d.setModal(true);
        if (timeoutSeconds > 0) { javax.swing.Timer t = new javax.swing.Timer(timeoutSeconds * 1000, e -> d.dispose()); t.setRepeats(false); t.start(); }
        return d;
    }

    private static final String D_TITLE = "Window title of the dialog.";
    private static final String D_ON_TOP = "Keep the dialog in front of other windows.";

    static int optionType(String buttons) {
        return switch (buttons) {
            case "ok_cancel" -> JOptionPane.OK_CANCEL_OPTION;
            case "yes_no" -> JOptionPane.YES_NO_OPTION;
            case "yes_no_cancel" -> JOptionPane.YES_NO_CANCEL_OPTION;
            default -> JOptionPane.DEFAULT_OPTION;
        };
    }

    static int messageType(String icon) {
        return switch (icon) {
            case "warning" -> JOptionPane.WARNING_MESSAGE;
            case "error" -> JOptionPane.ERROR_MESSAGE;
            case "question" -> JOptionPane.QUESTION_MESSAGE;
            case "none" -> JOptionPane.PLAIN_MESSAGE;
            default -> JOptionPane.INFORMATION_MESSAGE;
        };
    }

    /** Name of the button the user chose: ok, cancel, yes, no; closed when the window was closed; timeout when it closed by itself. */
    static String buttonName(String buttons, Object value) {
        if (!(value instanceof Integer v)) return "timeout";
        if (v == JOptionPane.CLOSED_OPTION) return "closed";
        boolean yesNo = buttons.startsWith("yes_no");
        return switch (v) {
            case 0 -> yesNo ? "yes" : "ok";
            case 1 -> yesNo ? "no" : "cancel";
            case 2 -> "cancel";
            default -> "closed";
        };
    }

    /** Long messages are shown in a scrolling text box once they have more lines than the limit (0 never scrolls). */
    static boolean scrolls(String message, int scrollAfterLines) {
        return scrollAfterLines > 0 && message != null && message.split("\r?\n", -1).length > scrollAfterLines;
    }

    @Action(id = "message_box", name = "Message box", category = "Dialogs", capabilities = "desktop", timeoutSeconds = 3600,
            description = "Show a message to the user (attended agents) with a choice of buttons and icon; can close automatically after a timeout.",
            panel = "title, message, scroll_after_lines, [Close it automatically: timeout_seconds] | buttons, icon, always_on_top")
    @Output(name = "button", type = OoaType.STRING, description = "Button chosen: ok, cancel, yes or no; closed when the window was closed; timeout when it closed by itself.")
    public Map<String, Object> messageBox(ActionContext ctx,
            @Input(name = "message", type = OoaType.STRING, description = "Message text.") String message,
            @Input(name = "title", type = OoaType.STRING, required = false, defaultValue = "\"ONEOFFICEAUTOMATION\"", description = "Window title.") String title,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0, description = "Close automatically after this many seconds; 0 waits for OK.") int timeout,
            @Input(name = "buttons", type = OoaType.STRING, required = false, defaultValue = "\"ok\"", enumValues = {"ok", "ok_cancel", "yes_no", "yes_no_cancel"}, description = "Buttons to show.") String buttons,
            @Input(name = "icon", type = OoaType.STRING, required = false, defaultValue = "\"information\"", enumValues = {"information", "warning", "error", "question", "none"}, description = "Icon next to the message.") String icon,
            @Input(name = "scroll_after_lines", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0, description = "Show the message in a scrolling box when it has more lines than this; 0 never scrolls.") int scrollAfter,
            @Input(name = "always_on_top", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = D_ON_TOP) boolean onTop) {
        String btn = choice("buttons", buttons, "ok", "ok", "ok_cancel", "yes_no", "yes_no_cancel");
        String ic = choice("icon", icon, "information", "information", "warning", "error", "question", "none");
        DesktopSupport.requireDesktop();
        DesktopSupport.requirePerson(timeout, "The message box");
        String text = message == null ? "" : message;
        Object chosen = onEdt(() -> {
            Object body = text;
            if (scrolls(text, scrollAfter)) {
                JTextArea area = new JTextArea(text, scrollAfter, 60);
                area.setEditable(false); area.setLineWrap(true); area.setWrapStyleWord(true);
                body = new JScrollPane(area);
            }
            JOptionPane pane = new JOptionPane(body, messageType(ic), optionType(btn));
            dialog(pane, title, timeout, onTop).setVisible(true);
            return pane.getValue();
        });
        return Map.of("button", buttonName(btn, chosen));
    }

    @Action(id = "prompt_value", name = "Prompt for value", category = "Dialogs", capabilities = "desktop", timeoutSeconds = 3600, description = "Ask the user to type a value, optionally hidden as it is typed.",
            panel = "title, question, masked | default, timeout_seconds, always_on_top")
    @Output(name = "value", type = OoaType.STRING, description = "Entered text.")
    @Output(name = "cancelled", type = OoaType.BOOLEAN, description = "True when the user cancelled or the prompt timed out.")
    @Output(name = "timed_out", type = OoaType.BOOLEAN, description = "True when nobody answered in time; value is then the default.")
    public Map<String, Object> promptValue(ActionContext ctx,
            @Input(name = "question", type = OoaType.STRING, description = "Question to show.") String question,
            @Input(name = "default", type = OoaType.STRING, required = false, description = "Pre-filled answer.") String def,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0, description = "Give up after this many seconds; 0 waits.") int timeout,
            @Input(name = "title", type = OoaType.STRING, required = false, defaultValue = "\"ONEOFFICEAUTOMATION\"", description = D_TITLE) String title,
            @Input(name = "masked", type = OoaType.BOOLEAN, required = false, defaultValue = "false",
                    description = "Show dots instead of the typed characters. The value is still plain text; use Prompt for password for secrets.") boolean masked,
            @Input(name = "always_on_top", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = D_ON_TOP) boolean onTop) {
        DesktopSupport.requireDesktop();
        DesktopSupport.requirePerson(timeout, "The prompt");
        boolean[] timedOut = {false};
        String v = onEdt(() -> {
            if (masked) {
                JPasswordField f = new JPasswordField(def == null ? "" : def, 24);
                JOptionPane pane = new JOptionPane(new Object[]{question, f}, JOptionPane.QUESTION_MESSAGE, JOptionPane.OK_CANCEL_OPTION);
                dialog(pane, title, timeout, onTop).setVisible(true);
                timedOut[0] = pane.getValue() == JOptionPane.UNINITIALIZED_VALUE;
                if (!Objects.equals(pane.getValue(), JOptionPane.OK_OPTION)) return null;
                char[] pw = f.getPassword();
                String s = new String(pw);
                Arrays.fill(pw, '\0');
                return s;
            }
            JOptionPane pane = new JOptionPane(question, JOptionPane.QUESTION_MESSAGE, JOptionPane.OK_CANCEL_OPTION);
            pane.setWantsInput(true);
            if (def != null) pane.setInitialSelectionValue(def);
            dialog(pane, title, timeout, onTop).setVisible(true);
            timedOut[0] = pane.getValue() == JOptionPane.UNINITIALIZED_VALUE;   // the timer closed it: nobody pressed a button
            Object in = pane.getInputValue();
            return Objects.equals(pane.getValue(), JOptionPane.OK_OPTION) && in != JOptionPane.UNINITIALIZED_VALUE ? String.valueOf(in) : null;
        });
        if (timedOut[0]) return Out.of("value", def == null ? "" : def, "cancelled", true, "timed_out", true);
        return Out.of("value", v == null ? "" : v, "cancelled", v == null, "timed_out", false);
    }

    @Action(id = "prompt_yes_no", name = "Prompt for yes/no", category = "Dialogs", capabilities = "desktop", timeoutSeconds = 3600, description = "Ask the user a yes/no question.",
            panel = "title, question | timeout_seconds, answer_on_timeout, always_on_top")
    @Output(name = "answer", type = OoaType.BOOLEAN, description = "True for Yes.")
    @Output(name = "timed_out", type = OoaType.BOOLEAN, description = "True when nobody answered before the timeout (or the window was closed).")
    public Map<String, Object> promptYesNo(ActionContext ctx,
            @Input(name = "question", type = OoaType.STRING, description = "Question to show.") String question,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0, description = "Answer No after this many seconds; 0 waits.") int timeout,
            @Input(name = "title", type = OoaType.STRING, required = false, defaultValue = "\"ONEOFFICEAUTOMATION\"", description = D_TITLE) String title,
            @Input(name = "always_on_top", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = D_ON_TOP) boolean onTop,
            @Input(name = "answer_on_timeout", type = OoaType.BOOLEAN, required = false, defaultValue = "false", visibleWhen = "timeout_seconds!=0", description = "Answer used when the prompt times out or is closed without a choice.") boolean onTimeout) {
        DesktopSupport.requireDesktop();
        DesktopSupport.requirePerson(timeout, "The yes/no prompt");
        Object v = onEdt(() -> {
            JOptionPane pane = new JOptionPane(question, JOptionPane.QUESTION_MESSAGE, JOptionPane.YES_NO_OPTION);
            dialog(pane, title, timeout, onTop).setVisible(true);
            return pane.getValue();
        });
        String b = buttonName("yes_no", v);
        boolean answered = b.equals("yes") || b.equals("no");
        return Map.of("answer", answered ? b.equals("yes") : onTimeout, "timed_out", !answered);
    }

    @Action(id = "prompt_password", name = "Prompt for password", category = "Dialogs", capabilities = "desktop", timeoutSeconds = 3600,
            description = "Ask the user for a password; the result is a Credential that is never shown or logged.",
            panel = "title, question | timeout_seconds, always_on_top")
    @Output(name = "password", type = OoaType.CREDENTIAL, description = "Entered password as a Credential.")
    public Map<String, Object> promptPassword(ActionContext ctx,
            @Input(name = "question", type = OoaType.STRING, required = false, defaultValue = "\"Enter password\"", description = "Prompt text.") String question,
            @Input(name = "title", type = OoaType.STRING, required = false, defaultValue = "\"ONEOFFICEAUTOMATION\"", description = D_TITLE) String title,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0, description = "Stop waiting after this many seconds (the step then fails as cancelled); 0 waits.") int timeout,
            @Input(name = "always_on_top", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = D_ON_TOP) boolean onTop) {
        DesktopSupport.requireDesktop();
        DesktopSupport.requirePerson(timeout, "The password prompt");
        char[] pw = onEdt(() -> {
            JPasswordField f = new JPasswordField(24);
            JOptionPane pane = new JOptionPane(new Object[]{question, f}, JOptionPane.QUESTION_MESSAGE, JOptionPane.OK_CANCEL_OPTION);
            dialog(pane, title, timeout, onTop).setVisible(true);
            return Objects.equals(pane.getValue(), JOptionPane.OK_OPTION) ? f.getPassword() : null;
        });
        if (pw == null) throw new ActionException("DESKTOP.CANCELLED", "The user cancelled the password prompt, or it timed out");
        SecureString s = new SecureString(new String(pw), "prompt");
        Arrays.fill(pw, '\0');
        return Map.of("password", s);
    }

    /** Extensions from "pdf, xlsx", "*.pdf;*.xlsx" or ".csv": lower case, without dots; empty means all files. */
    static List<String> extensions(String fileTypes) {
        if (fileTypes == null || fileTypes.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String part : fileTypes.split("[,;|\\s]+")) {
            String e = part.strip().replaceFirst("^\\*?\\.", "").toLowerCase(Locale.ROOT);
            if (e.isEmpty() || e.equals("*")) continue;
            if (!e.matches("[a-z0-9_\\-]{1,16}")) throw new ActionException("DESKTOP.BAD_FILE_TYPE", "Invalid file type '" + part + "'", "List extensions such as pdf, xlsx or *.csv.");
            if (!out.contains(e)) out.add(e);
        }
        return out;
    }

    /** The message shown above a file or folder picker, or null when there is none. */
    static JTextArea pickerMessage(String message) {
        if (message == null || message.isBlank()) return null;
        JTextArea a = new JTextArea(message.strip());
        a.setEditable(false);
        a.setFocusable(false);
        a.setLineWrap(true);
        a.setWrapStyleWord(true);
        a.setOpaque(false);
        a.setBorder(javax.swing.BorderFactory.createEmptyBorder(10, 12, 0, 12));
        return a;
    }

    private Map<String, Object> pick(String title, boolean folders, String startIn, ActionContext ctx, List<String> types, boolean multiple, int timeout, boolean onTop, String message) {
        DesktopSupport.requireDesktop();
        DesktopSupport.requirePerson(timeout, "The file or folder picker");
        List<String> chosen = onEdt(() -> {
            JFileChooser fc = new JFileChooser(startIn == null ? ctx.workDir().toFile() : ctx.resolvePath(startIn).toFile()) {
                @Override protected JDialog createDialog(Component parent) {
                    JDialog d = super.createDialog(parent);
                    d.setAlwaysOnTop(onTop);
                    JTextArea text = pickerMessage(message);
                    if (text != null) {
                        // wrap the text to the dialog's width instead of widening the dialog
                        text.setSize(Math.max(200, d.getWidth() - 24), Short.MAX_VALUE);
                        d.getContentPane().add(text, BorderLayout.NORTH);
                        d.setSize(d.getWidth(), d.getHeight() + text.getPreferredSize().height);
                        d.setLocationRelativeTo(parent);
                    }
                    return d;
                }
            };
            fc.setDialogTitle(title);
            fc.setFileSelectionMode(folders ? JFileChooser.DIRECTORIES_ONLY : JFileChooser.FILES_ONLY);
            fc.setMultiSelectionEnabled(multiple);
            if (!types.isEmpty()) fc.setFileFilter(new FileNameExtensionFilter(String.join(", ", types), types.toArray(String[]::new)));
            if (timeout > 0) { javax.swing.Timer t = new javax.swing.Timer(timeout * 1000, e -> fc.cancelSelection()); t.setRepeats(false); t.start(); }
            if (fc.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return null;
            File[] all = multiple ? fc.getSelectedFiles() : new File[]{fc.getSelectedFile()};
            List<String> paths = new ArrayList<>();
            for (File f : all) if (f != null) paths.add(f.getAbsolutePath());
            return paths;
        });
        List<Object> paths = chosen == null ? new ArrayList<>() : new ArrayList<>(chosen);
        return Out.of("path", paths.isEmpty() ? "" : paths.get(0), "paths", paths, "cancelled", chosen == null);
    }

    @Action(id = "prompt_file", name = "Prompt for file", category = "Dialogs", capabilities = "desktop", timeoutSeconds = 3600, description = "Ask the user to choose one or more files, optionally of given types.",
            panel = "title, message, file_types, multiple | start_folder, timeout_seconds, always_on_top")
    @Output(name = "path", type = OoaType.FILE, description = "Chosen file (the first one when several), empty when cancelled.")
    @Output(name = "paths", type = OoaType.LIST, description = "All chosen files.")
    @Output(name = "cancelled", type = OoaType.BOOLEAN, description = "True when cancelled or timed out.")
    public Map<String, Object> promptFile(ActionContext ctx,
            @Input(name = "title", type = OoaType.STRING, required = false, defaultValue = "\"Choose a file\"", description = "Dialog title.") String title,
            @Input(name = "start_folder", type = OoaType.FILE, required = false, description = "Folder to start in.") String start,
            @Input(name = "file_types", type = OoaType.STRING, required = false, example = "pdf, xlsx", description = "Only show files with these extensions; all files when empty.") String fileTypes,
            @Input(name = "multiple", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Let the user choose several files.") boolean multiple,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0, description = "Give up after this many seconds; 0 waits.") int timeout,
            @Input(name = "always_on_top", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = D_ON_TOP) boolean onTop,
            @Input(name = "message", type = OoaType.STRING, required = false, example = "Pick this month's bank statement.", description = D_PICK_MESSAGE) String message) {
        return pick(title, false, start, ctx, extensions(fileTypes), multiple, timeout, onTop, message);
    }

    public Map<String, Object> promptFile(ActionContext ctx, String title, String start, String fileTypes, boolean multiple, int timeout, boolean onTop) {
        return promptFile(ctx, title, start, fileTypes, multiple, timeout, onTop, null);
    }

    private static final String D_PICK_MESSAGE = "Text shown in the dialog above the list, e.g. what to choose and why.";

    @Action(id = "prompt_folder", name = "Prompt for folder", category = "Dialogs", capabilities = "desktop", timeoutSeconds = 3600, description = "Ask the user to choose a folder.",
            panel = "title, message | start_folder, timeout_seconds, always_on_top")
    @Output(name = "path", type = OoaType.FILE, description = "Chosen folder, empty when cancelled.")
    @Output(name = "cancelled", type = OoaType.BOOLEAN, description = "True when cancelled or timed out.")
    public Map<String, Object> promptFolder(ActionContext ctx,
            @Input(name = "title", type = OoaType.STRING, required = false, defaultValue = "\"Choose a folder\"", description = "Dialog title.") String title,
            @Input(name = "start_folder", type = OoaType.FILE, required = false, description = "Folder to start in.") String start,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0, description = "Give up after this many seconds; 0 waits.") int timeout,
            @Input(name = "always_on_top", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = D_ON_TOP) boolean onTop,
            @Input(name = "message", type = OoaType.STRING, required = false, example = "Pick the folder with the scanned invoices.", description = D_PICK_MESSAGE) String message) {
        Map<String, Object> r = pick(title, true, start, ctx, List.of(), false, timeout, onTop, message);
        r.remove("paths");
        return r;
    }

    public Map<String, Object> promptFolder(ActionContext ctx, String title, String start, int timeout, boolean onTop) {
        return promptFolder(ctx, title, start, timeout, onTop, null);
    }
}
