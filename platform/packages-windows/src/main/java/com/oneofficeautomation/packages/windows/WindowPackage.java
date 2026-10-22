package com.oneofficeautomation.packages.windows;

import com.oneofficeautomation.packages.UiGuard;
import com.oneofficeautomation.packages.ui.SelfHealing;
import com.oneofficeautomation.packages.ui.TitlePattern;
import com.oneofficeautomation.sdk.*;
import com.sun.jna.Native;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;

import javax.imageio.ImageIO;
import java.awt.Rectangle;
import java.awt.Robot;
import java.nio.file.*;
import java.util.*;

/**
 * Find, activate, resize, minimise, maximise, close and capture desktop windows (Windows). Window actions check the
 * run's interrupt rules first, and can heal a lookup that fails by title with alternatives recorded for the window
 * (title pattern, window class, process name, class plus index).
 */
@OoaPackage(id = "ooa.window", name = "Window", version = "1.0.0", description = "Find, activate, move, resize, minimise, maximise, close and capture application windows on Windows.")
public final class WindowPackage {
    private static final int SW_MAXIMIZE = 3, SW_MINIMIZE = 6, SW_RESTORE = 9, WM_CLOSE = 0x0010;

    private static void windows() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"))
            throw new ActionException("SYSTEM.WINDOWS_ONLY", "Window actions work on Windows only", "Run this agent on a Windows runner.");
    }

    record Win(WinDef.HWND hwnd, String title) {
        String className() { char[] b = new char[256]; User32.INSTANCE.GetClassName(hwnd, b, b.length); return Native.toString(b); }
        String processName() {
            IntByReference pid = new IntByReference();
            User32.INSTANCE.GetWindowThreadProcessId(hwnd, pid);
            return ProcessHandle.of(pid.getValue()).flatMap(p -> p.info().command()).map(c -> Path.of(c).getFileName().toString()).orElse("");
        }
    }

    static List<Win> all() {
        windows();
        List<Win> out = new ArrayList<>();
        User32.INSTANCE.EnumWindows((hwnd, data) -> {
            if (User32.INSTANCE.IsWindowVisible(hwnd)) {
                char[] buf = new char[512];
                User32.INSTANCE.GetWindowText(hwnd, buf, buf.length);
                String t = Native.toString(buf);
                if (!t.isBlank()) out.add(new Win(hwnd, t));
            }
            return true;
        }, null);
        return out;
    }

    static Win find(String titleContains) {
        String q = titleContains.toLowerCase(Locale.ROOT);
        for (Win w : all()) if (w.title().toLowerCase(Locale.ROOT).contains(q)) return w;
        throw new ActionException("WINDOW.NOT_FOUND", "No visible window title contains '" + titleContains + "'", "Use List windows to see the exact titles.", true);
    }

    // ---------- self-healing window lookup ----------
    static final List<String> WINDOW_ORDER = List.of("title_pattern", "class_name", "process_name", "class_index");

    static String windowQuery(String strategy, Map<String, Object> a) {
        return switch (strategy) {
            case "title_pattern", "class_name", "process_name" -> a.get(strategy) == null ? null : String.valueOf(a.get(strategy));
            case "class_index" -> a.get("class_name") != null && a.get("index") instanceof Number n ? a.get("class_name") + "#" + n.intValue() : null;
            default -> null;
        };
    }

    static Optional<Win> resolve(SelfHealing.Candidate c, List<Win> all) {
        String q = c.query();
        return switch (c.strategy()) {
            case "title" -> all.stream().filter(w -> w.title().toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT))).findFirst();
            case "title_pattern" -> all.stream().filter(w -> TitlePattern.matches(q, w.title())).findFirst();
            case "class_name" -> all.stream().filter(w -> w.className().equalsIgnoreCase(q)).findFirst();
            case "process_name" -> all.stream().filter(w -> { String p = w.processName(); return p.equalsIgnoreCase(q) || p.equalsIgnoreCase(q + ".exe"); }).findFirst();
            case "class_index" -> {
                int hash = q.lastIndexOf('#');
                String cls = q.substring(0, hash);
                int index = Integer.parseInt(q.substring(hash + 1));
                List<Win> same = all.stream().filter(w -> w.className().equalsIgnoreCase(cls)).toList();
                yield index >= 0 && index < same.size() ? Optional.of(same.get(index)) : Optional.empty();
            }
            default -> Optional.empty();
        };
    }

    /** Checks interrupt rules, then finds the window by title, falling back to the alternatives; waits up to the timeout. */
    static Win locate(ActionContext ctx, String title, Map<String, Object> alternatives, double timeoutSeconds) {
        windows();
        UiGuard.before(ctx);
        List<SelfHealing.Candidate> chain = SelfHealing.chain(new SelfHealing.Candidate("title", title), alternatives, WINDOW_ORDER, WindowPackage::windowQuery);
        try {
            return SelfHealing.locate(chain, c -> resolve(c, all()), (long) (timeoutSeconds * 1000), SelfHealing.SYSTEM, 300, () -> UiGuard.before(ctx), ctx::warn, ctx::checkCancelled).element();
        } catch (ActionException e) {
            if (!"UI.ELEMENT_NOT_FOUND".equals(e.code())) throw e;
            throw new ActionException("WINDOW.NOT_FOUND", timeoutSeconds > 0 ? "Window '" + title + "' did not appear within " + timeoutSeconds + " s" : "No visible window title contains '" + title + "'",
                    "Use List windows to see the exact titles, or record alternatives for the window.", true);
        }
    }

    private static final String D_TITLE = "Text contained in the window title (case-insensitive).";
    private static final String D_WAIT = "Wait up to this many seconds for the window to appear; 0 looks once.";
    private static final String D_ALT = "Fallbacks when no title matches: title_pattern (wildcards or re:), class_name, process_name, and index (with class_name). The one that works is logged.";

    @Action(id = "list", name = "List windows", category = "Window", idempotent = true, capabilities = "desktop", description = "List the titles of all visible windows.")
    @Output(name = "titles", type = OoaType.LIST, description = "Window titles.")
    public Map<String, Object> list(ActionContext ctx) {
        List<Object> t = new ArrayList<>();
        for (Win w : all()) t.add(w.title());
        return Map.of("titles", t);
    }

    @Action(id = "activate", name = "Activate window", category = "Window", capabilities = "desktop", description = "Bring a window to the front (restoring it if minimised).")
    @Output(name = "title", type = OoaType.STRING, description = "Full title of the activated window.")
    public Map<String, Object> activate(ActionContext ctx, @Input(name = "title", type = OoaType.STRING, description = D_TITLE) String title,
            @Input(name = "alternatives", type = OoaType.DICTIONARY, required = false, description = D_ALT) Map<String, Object> alternatives,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_WAIT) double wait) {
        Win w = locate(ctx, title, alternatives, wait);
        User32.INSTANCE.ShowWindow(w.hwnd(), SW_RESTORE);
        User32.INSTANCE.SetForegroundWindow(w.hwnd());
        return Map.of("title", w.title());
    }

    private Map<String, Object> show(ActionContext ctx, String title, Map<String, Object> alternatives, double wait, int cmd) {
        Win w = locate(ctx, title, alternatives, wait);
        User32.INSTANCE.ShowWindow(w.hwnd(), cmd);
        return Map.of("title", w.title());
    }

    @Action(id = "maximize", name = "Maximise window", category = "Window", capabilities = "desktop", description = "Maximise a window.")
    @Output(name = "title", type = OoaType.STRING, description = "Full title of the window.")
    public Map<String, Object> maximize(ActionContext ctx, @Input(name = "title", type = OoaType.STRING, description = D_TITLE) String title,
            @Input(name = "alternatives", type = OoaType.DICTIONARY, required = false, description = D_ALT) Map<String, Object> alternatives,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_WAIT) double wait) { return show(ctx, title, alternatives, wait, SW_MAXIMIZE); }

    @Action(id = "minimize", name = "Minimise window", category = "Window", capabilities = "desktop", description = "Minimise a window.")
    @Output(name = "title", type = OoaType.STRING, description = "Full title of the window.")
    public Map<String, Object> minimize(ActionContext ctx, @Input(name = "title", type = OoaType.STRING, description = D_TITLE) String title,
            @Input(name = "alternatives", type = OoaType.DICTIONARY, required = false, description = D_ALT) Map<String, Object> alternatives,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_WAIT) double wait) { return show(ctx, title, alternatives, wait, SW_MINIMIZE); }

    @Action(id = "restore", name = "Restore window", category = "Window", capabilities = "desktop", description = "Restore a minimised or maximised window to its normal size.")
    @Output(name = "title", type = OoaType.STRING, description = "Full title of the window.")
    public Map<String, Object> restore(ActionContext ctx, @Input(name = "title", type = OoaType.STRING, description = D_TITLE) String title,
            @Input(name = "alternatives", type = OoaType.DICTIONARY, required = false, description = D_ALT) Map<String, Object> alternatives,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_WAIT) double wait) { return show(ctx, title, alternatives, wait, SW_RESTORE); }

    @Action(id = "move_resize", name = "Move and resize window", category = "Window", capabilities = "desktop",
            description = "Set a window's position and/or size in pixels; values left empty keep the window's current ones.")
    public Map<String, Object> moveResize(ActionContext ctx,
            @Input(name = "title", type = OoaType.STRING, description = D_TITLE) String title,
            @Input(name = "x", type = OoaType.NUMBER, required = false, description = "Left; unchanged when empty.") Integer x,
            @Input(name = "y", type = OoaType.NUMBER, required = false, description = "Top; unchanged when empty.") Integer y,
            @Input(name = "width", type = OoaType.NUMBER, required = false, description = "Width; unchanged when empty.") Integer w,
            @Input(name = "height", type = OoaType.NUMBER, required = false, description = "Height; unchanged when empty.") Integer h,
            @Input(name = "alternatives", type = OoaType.DICTIONARY, required = false, description = D_ALT) Map<String, Object> alternatives,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_WAIT) double wait) {
        if ((w != null && w <= 0) || (h != null && h <= 0)) throw new ActionException("WINDOW.BAD_SIZE", "Width and height must be greater than 0", "Check the size.");
        Win win = locate(ctx, title, alternatives, wait);
        User32.INSTANCE.ShowWindow(win.hwnd(), SW_RESTORE);
        WinDef.RECT r = new WinDef.RECT();
        User32.INSTANCE.GetWindowRect(win.hwnd(), r);
        int[] box = box(new int[]{r.left, r.top, r.right - r.left, r.bottom - r.top}, x, y, w, h);
        User32.INSTANCE.MoveWindow(win.hwnd(), box[0], box[1], box[2], box[3], true);
        return Map.of();
    }

    /** New {x, y, width, height}: given values replace the current ones, empty values keep them. */
    static int[] box(int[] current, Integer x, Integer y, Integer w, Integer h) {
        return new int[]{x == null ? current[0] : x, y == null ? current[1] : y, w == null ? current[2] : w, h == null ? current[3] : h};
    }

    @Action(id = "get_info", name = "Get window details", category = "Window", idempotent = true, capabilities = "desktop",
            description = "Find a window and return its title, program, class, position, size and state, e.g. to keep it in a variable for later steps.")
    @Output(name = "window", type = OoaType.DICTIONARY, description = "title, process_name, class_name, x, y, width, height, state (normal, minimized or maximized), active.")
    public Map<String, Object> getInfo(ActionContext ctx,
            @Input(name = "title", type = OoaType.STRING, description = D_TITLE) String title,
            @Input(name = "alternatives", type = OoaType.DICTIONARY, required = false, description = D_ALT) Map<String, Object> alternatives,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_WAIT) double wait) {
        return Map.of("window", details(locate(ctx, title, alternatives, wait)));
    }

    static Map<String, Object> details(Win w) {
        WinDef.RECT r = new WinDef.RECT();
        User32.INSTANCE.GetWindowRect(w.hwnd(), r);
        WinUser.WINDOWPLACEMENT pl = new WinUser.WINDOWPLACEMENT();
        User32.INSTANCE.GetWindowPlacement(w.hwnd(), pl);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", w.title());
        m.put("process_name", w.processName());
        m.put("class_name", w.className());
        m.put("x", java.math.BigDecimal.valueOf(r.left));
        m.put("y", java.math.BigDecimal.valueOf(r.top));
        m.put("width", java.math.BigDecimal.valueOf(r.right - r.left));
        m.put("height", java.math.BigDecimal.valueOf(r.bottom - r.top));
        m.put("state", state(pl.showCmd));
        m.put("active", w.hwnd().equals(User32.INSTANCE.GetForegroundWindow()));
        return m;
    }

    static String state(int showCmd) { return switch (showCmd) { case 2, 6, 7 -> "minimized"; case 3 -> "maximized"; default -> "normal"; }; }

    @Action(id = "close", name = "Close window", category = "Window", capabilities = "desktop", description = "Ask a window to close (like clicking its X; the program may ask to save).")
    @Output(name = "closed", type = OoaType.BOOLEAN, description = "True when the window was gone by the end of wait_closed_seconds (always true when not waiting).")
    public Map<String, Object> close(ActionContext ctx, @Input(name = "title", type = OoaType.STRING, description = D_TITLE) String title,
            @Input(name = "alternatives", type = OoaType.DICTIONARY, required = false, description = D_ALT) Map<String, Object> alternatives,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_WAIT) double wait,
            @Input(name = "wait_closed_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", description = "Wait up to this many seconds for the window to disappear; 0 does not wait.") double waitClosed) {
        Win w = locate(ctx, title, alternatives, wait);
        User32.INSTANCE.PostMessage(w.hwnd(), WM_CLOSE, new WinDef.WPARAM(0), new WinDef.LPARAM(0));
        long end = System.currentTimeMillis() + (long) (Math.max(0, Math.min(3600, waitClosed)) * 1000);
        while (User32.INSTANCE.IsWindow(w.hwnd()) && System.currentTimeMillis() < end) {
            ctx.checkCancelled();
            try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        return Map.of("closed", waitClosed <= 0 || !User32.INSTANCE.IsWindow(w.hwnd()));
    }

    /** Window titles matching the pattern: contained text (case-insensitive) or a regular expression. */
    static java.util.function.Predicate<String> matcher(String pattern, boolean regex) {
        if (pattern == null || pattern.strip().length() < 2)
            throw new ActionException("WINDOW.PATTERN_TOO_BROAD", "Give at least two characters of the window title", "A blank or one-letter pattern would match almost every window.");
        if (regex) {
            java.util.regex.Pattern p;
            try { p = java.util.regex.Pattern.compile(pattern, java.util.regex.Pattern.CASE_INSENSITIVE); }
            catch (java.util.regex.PatternSyntaxException e) { throw new ActionException("WINDOW.BAD_PATTERN", "Invalid regular expression: " + e.getDescription(), "Check the pattern, e.g. ^Invoice .* - Excel$."); }
            if (p.matcher("").find()) throw new ActionException("WINDOW.PATTERN_TOO_BROAD", "The pattern matches an empty title, so it would match every window", "Make the pattern more specific.");
            return t -> p.matcher(t).find();
        }
        String q = pattern.toLowerCase(Locale.ROOT);
        return t -> t.toLowerCase(Locale.ROOT).contains(q);
    }

    @Action(id = "close_all", name = "Close all matching windows", category = "Window", capabilities = "desktop",
            description = "Ask every visible window whose title matches to close (like clicking each X); programs may still ask to save.")
    @Output(name = "closed", type = OoaType.LIST, description = "Titles of the windows asked to close.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of windows asked to close.")
    public Map<String, Object> closeAll(ActionContext ctx,
            @Input(name = "title", type = OoaType.STRING, example = " - Notepad", description = "Text in the window titles (case-insensitive), or a regular expression when use_regex is true.") String title,
            @Input(name = "use_regex", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Treat title as a regular expression.") boolean regex) {
        java.util.function.Predicate<String> m = matcher(title, regex);
        List<Object> closed = new ArrayList<>();
        for (Win w : all()) {
            if (!m.test(w.title())) continue;
            User32.INSTANCE.PostMessage(w.hwnd(), WM_CLOSE, new WinDef.WPARAM(0), new WinDef.LPARAM(0));
            closed.add(w.title());
        }
        if (!closed.isEmpty()) ctx.info("Asked " + closed.size() + " window(s) to close");
        return Map.of("closed", closed, "count", java.math.BigDecimal.valueOf(closed.size()));
    }

    interface User32Text extends com.sun.jna.Library {
        User32Text INSTANCE = loadText();
        boolean SetWindowTextW(WinDef.HWND hWnd, com.sun.jna.WString text);
    }

    private static User32Text loadText() {
        try { return Native.load("user32", User32Text.class); } catch (UnsatisfiedLinkError | NoClassDefFoundError e) { return null; }
    }

    @Action(id = "set_title", name = "Set window title", category = "Window", capabilities = "desktop",
            description = "Change the title bar text of a window, e.g. to tell several copies of the same program apart.")
    @Output(name = "previous_title", type = OoaType.STRING, description = "Title before the change.")
    public Map<String, Object> setTitle(ActionContext ctx,
            @Input(name = "title", type = OoaType.STRING, description = D_TITLE) String title,
            @Input(name = "new_title", type = OoaType.STRING, description = "New title text.") String newTitle,
            @Input(name = "alternatives", type = OoaType.DICTIONARY, required = false, description = D_ALT) Map<String, Object> alternatives,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_WAIT) double wait) {
        if (newTitle == null || newTitle.length() > 512 || newTitle.chars().anyMatch(c -> c < 0x20))
            throw new ActionException("WINDOW.BAD_TITLE", "The new title must be at most 512 characters without line breaks", "Shorten the title.");
        Win w = alternatives == null && wait <= 0 ? find(title) : locate(ctx, title, alternatives, wait);
        if (User32Text.INSTANCE == null || !User32Text.INSTANCE.SetWindowTextW(w.hwnd(), new com.sun.jna.WString(newTitle)))
            throw new ActionException("WINDOW.SET_TITLE_FAILED", "Cannot change the title of '" + w.title() + "'",
                    "The window may belong to a program running with higher rights than the agent.");
        return Map.of("previous_title", w.title());
    }

    @Action(id = "get_active", name = "Get active window title", category = "Window", idempotent = true, capabilities = "desktop", description = "Return the title of the window in front.")
    @Output(name = "title", type = OoaType.STRING, description = "Title of the foreground window.")
    @Output(name = "process_name", type = OoaType.STRING, description = "Program file name of the foreground window, e.g. EXCEL.EXE.")
    @Output(name = "class_name", type = OoaType.STRING, description = "Window class of the foreground window.")
    public Map<String, Object> getActive(ActionContext ctx) {
        windows();
        WinDef.HWND h = User32.INSTANCE.GetForegroundWindow();
        char[] buf = new char[512];
        User32.INSTANCE.GetWindowText(h, buf, buf.length);
        Win w = new Win(h, Native.toString(buf));
        return Map.of("title", w.title(), "process_name", h == null ? "" : w.processName(), "class_name", h == null ? "" : w.className());
    }

    @Action(id = "wait_for", name = "Wait for window", category = "Window", timeoutSeconds = 3600, capabilities = "desktop", description = "Wait until a window with this title appears.")
    @Output(name = "title", type = OoaType.STRING, description = "Full title of the window.")
    public Map<String, Object> waitFor(ActionContext ctx,
            @Input(name = "title", type = OoaType.STRING, description = D_TITLE) String title,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "30", description = "Maximum wait.") double timeout,
            @Input(name = "alternatives", type = OoaType.DICTIONARY, required = false, description = D_ALT) Map<String, Object> alternatives) {
        return Map.of("title", locate(ctx, title, alternatives, timeout).title());
    }

    @Condition(id = "exists", name = "Window exists", description = "True when a visible window title contains the text.")
    public boolean exists(ActionContext ctx, @Input(name = "title", type = OoaType.STRING, description = D_TITLE) String title) {
        String q = title.toLowerCase(Locale.ROOT);
        return all().stream().anyMatch(w -> w.title().toLowerCase(Locale.ROOT).contains(q));
    }

    @Action(id = "capture", name = "Capture window", category = "Window", capabilities = {"desktop", "filesystem"}, description = "Save a PNG screenshot of one window.")
    @Output(name = "path", type = OoaType.FILE, description = "Saved image.")
    public Map<String, Object> capture(ActionContext ctx,
            @Input(name = "title", type = OoaType.STRING, description = D_TITLE) String title,
            @Input(name = "path", type = OoaType.FILE, description = "PNG file to create.") String path,
            @Input(name = "alternatives", type = OoaType.DICTIONARY, required = false, description = D_ALT) Map<String, Object> alternatives,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_WAIT) double wait,
            @Input(name = "activate", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Bring the window to the front first so nothing covers it.") boolean activate,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Replace an existing file; when false an existing file stops the step.") boolean overwrite) {
        if (!overwrite && Files.exists(ctx.resolvePath(path))) throw new ActionException("FILE.EXISTS", "File already exists: " + ctx.resolvePath(path), "Set overwrite to true or choose another name.");
        Win w = locate(ctx, title, alternatives, wait);
        if (activate) {
            User32.INSTANCE.ShowWindow(w.hwnd(), SW_RESTORE);
            User32.INSTANCE.SetForegroundWindow(w.hwnd());
            try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        WinDef.RECT r = new WinDef.RECT();
        User32.INSTANCE.GetWindowRect(w.hwnd(), r);
        Path p = ctx.resolvePath(path);
        try {
            if (p.getParent() != null) Files.createDirectories(p.getParent());
            ImageIO.write(new Robot().createScreenCapture(new Rectangle(r.left, r.top, Math.max(1, r.right - r.left), Math.max(1, r.bottom - r.top))), "png", p.toFile());
        } catch (Exception e) { throw new ActionException("WINDOW.CAPTURE_FAILED", e.getMessage(), "Make sure the window is visible and not minimised."); }
        return Map.of("path", p.toString());
    }


    @Condition(id = "is_active", name = "Window is active", description = "True when the window in front has a title containing the text.")
    public boolean isActive(ActionContext ctx, @Input(name = "title", type = OoaType.STRING, description = D_TITLE) String title) {
        windows();
        char[] buf = new char[512];
        User32.INSTANCE.GetWindowText(User32.INSTANCE.GetForegroundWindow(), buf, buf.length);
        return Native.toString(buf).toLowerCase(Locale.ROOT).contains(title.toLowerCase(Locale.ROOT));
    }
}
