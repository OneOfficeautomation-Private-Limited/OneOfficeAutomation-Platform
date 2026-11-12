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
    private static final int SW_MAXIMIZE = 3, SW_MINIMIZE = 6, SW_RESTORE = 9, WM_CLOSE = 0x0010, GW_OWNER = 4;

    private static void windows() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"))
            throw new ActionException("SYSTEM.WINDOWS_ONLY", "Window actions work on Windows only", "Run this agent on a Windows runner.");
    }

    record Win(WinDef.HWND hwnd, String title) {
        String className() { char[] b = new char[256]; User32.INSTANCE.GetClassName(hwnd, b, b.length); return Native.toString(b); }
        long pid() {
            IntByReference pid = new IntByReference();
            User32.INSTANCE.GetWindowThreadProcessId(hwnd, pid);
            return pid.getValue();
        }
        String processName() {
            return ProcessHandle.of(pid()).flatMap(p -> p.info().command()).map(c -> Path.of(c).getFileName().toString()).orElse("");
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

    /**
     * How every Window command matches a title, the same rule as the window fields of mouse, keyboard and capture steps
     * ({@link TitlePattern}): contained text, * and ? wildcards over the whole title, or re: and a regular expression;
     * case never matters. A title is also always found by the text it contains, as before, so older agents whose
     * titles hold a * or ? (Notepad's "*Untitled", "Save changes?") keep finding their window.
     */
    static java.util.function.Predicate<String> titleRule(String title) {
        String text = title == null ? "" : title;
        String low = text.toLowerCase(Locale.ROOT);
        java.util.function.Predicate<String> contains = t -> t != null && t.toLowerCase(Locale.ROOT).contains(low);
        String q = text.strip();
        if (!q.startsWith("re:") && q.indexOf('*') < 0 && q.indexOf('?') < 0) return contains;
        java.util.function.Predicate<String> pattern;
        try { pattern = TitlePattern.of(q); }
        catch (IllegalArgumentException e) {
            throw new ActionException("WINDOW.BAD_PATTERN", e.getMessage(), "Check the regular expression after re:, e.g. re:^Invoice \\d+ - Excel$.");
        }
        return t -> pattern.test(t) || contains.test(t);
    }

    static Win find(String titleContains) {
        java.util.function.Predicate<String> m = titleRule(titleContains);
        for (Win w : all()) if (m.test(w.title())) return w;
        throw new ActionException("WINDOW.NOT_FOUND", "No visible window title matches '" + titleContains + "'", "Use List windows to see the exact titles.", true);
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
            case "title" -> all.stream().filter(w -> titleRule(q).test(w.title())).findFirst();
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
            throw new ActionException("WINDOW.NOT_FOUND", timeoutSeconds > 0 ? "Window '" + title + "' did not appear within " + timeoutSeconds + " s" : "No visible window title matches '" + title + "'",
                    "Use List windows to see the exact titles, or record alternatives for the window.", true);
        }
    }

    /**
     * The lookup every Window command uses. With a process id: the main window of that program (or of a program it
     * started), narrowed to titles containing the text when one is given. Without: by title, as {@link #locate}.
     */
    static Win target(ActionContext ctx, String title, Long processId, Map<String, Object> alternatives, double timeoutSeconds) {
        if (processId == null || processId <= 0) {
            if (title == null || title.isBlank())
                throw new ActionException("WINDOW.NO_TARGET", "Give the window title or a process id", "Fill in title, or process_id from Start program.");
            return locate(ctx, title, alternatives, timeoutSeconds);
        }
        windows();
        UiGuard.before(ctx);
        long end = System.nanoTime() + (long) (Math.max(0, timeoutSeconds) * 1e9);
        while (true) {
            Optional<Win> w = mainWindow(processId, title);
            if (w.isPresent()) return w.get();
            if (System.nanoTime() >= end) {
                String which = "process " + processId + (title == null || title.isBlank() ? "" : " with a title containing '" + title + "'");
                if (ProcessHandle.of(processId).map(ProcessHandle::isAlive).orElse(false))
                    throw new ActionException("WINDOW.NOT_FOUND", "No visible window of " + which + (timeoutSeconds > 0 ? " within " + timeoutSeconds + " s" : ""),
                            "Raise timeout_seconds so the program can open its window.", true);
                throw new ActionException("WINDOW.NOT_FOUND", "Process " + processId + " is not running", "Some programs hand over to another process at start; find the window by title instead.", true);
            }
            ctx.checkCancelled();
            try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new ActionException("RUN.CANCELLED", "Interrupted"); }
            UiGuard.before(ctx);
        }
    }

    /** First visible top-level window without an owner that belongs to the process or one it started, preferring one with a title. */
    static Optional<Win> mainWindow(long processId, String titleContains) {
        Set<Long> pids = new HashSet<>();
        pids.add(processId);
        ProcessHandle.of(processId).ifPresent(p -> p.descendants().forEach(d -> pids.add(d.pid())));
        java.util.function.Predicate<String> q = titleContains == null || titleContains.isBlank() ? null : titleRule(titleContains);
        List<Win> found = new ArrayList<>();
        User32.INSTANCE.EnumWindows((hwnd, data) -> {
            if (User32.INSTANCE.IsWindowVisible(hwnd) && User32.INSTANCE.GetWindow(hwnd, new WinDef.DWORD(GW_OWNER)) == null) {
                IntByReference pid = new IntByReference();
                User32.INSTANCE.GetWindowThreadProcessId(hwnd, pid);
                if (pids.contains((long) pid.getValue())) {
                    char[] buf = new char[512];
                    User32.INSTANCE.GetWindowText(hwnd, buf, buf.length);
                    String t = Native.toString(buf);
                    if (q == null || q.test(t)) found.add(new Win(hwnd, t));
                }
            }
            return true;
        }, null);
        return found.stream().filter(w -> !w.title().isBlank()).findFirst().or(() -> found.stream().findFirst());
    }

    private static final String D_TITLE_TEXT = "Text in the window title, a pattern with * or ?, or re: and a regular expression (case-insensitive).";
    private static final String D_TITLE = "Text in the window title, a pattern with * or ? (e.g. Invoice*- Excel), or re: and a regular expression; case-insensitive. "
            + "The first visible window that matches is used. May be empty when process_id is given.";
    private static final String D_PID = "Process id from Start program: use that program's main window instead of finding it by title; title, when given, then only narrows the choice.";
    private static final String D_WAIT = "Wait up to this many seconds for the window to appear; 0 looks once.";
    private static final String D_ALT = "Fallbacks when no title matches: title_pattern (wildcards or re:), class_name, process_name, and index (with class_name). The one that works is logged.";

    @Action(id = "list", name = "List windows", category = "Window", idempotent = true, capabilities = "desktop", description = "List the titles of all visible windows.")
    @Output(name = "titles", type = OoaType.LIST, description = "Window titles.")
    public Map<String, Object> list(ActionContext ctx) {
        List<Object> t = new ArrayList<>();
        for (Win w : all()) t.add(w.title());
        return Map.of("titles", t);
    }

    @Action(id = "activate", name = "Activate window", category = "Window", capabilities = "desktop", description = "Bring a window to the front (restoring it if minimised).",
            panel = "title | alternatives, timeout_seconds, process_id")
    @Output(name = "title", type = OoaType.STRING, description = "Full title of the activated window.")
    public Map<String, Object> activate(ActionContext ctx, @Input(name = "title", type = OoaType.STRING, required = false, description = D_TITLE) String title,
            @Input(name = "alternatives", type = OoaType.DICTIONARY, required = false, description = D_ALT) Map<String, Object> alternatives,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0, description = D_WAIT) double wait,
            @Input(name = "process_id", type = OoaType.NUMBER, required = false, description = D_PID) Long processId) {
        Win w = target(ctx, title, processId, alternatives, wait);
        if (!bringToFront(w.hwnd()))
            throw new ActionException("WINDOW.NOT_ACTIVATED", "Windows did not let '" + w.title() + "' come to the front",
                    "Another program is holding the focus (for example a full-screen or elevated window); close it, or run the agent with the desktop unlocked.", true);
        return Map.of("title", w.title());
    }

    /** user32 calls for bringToFront that the bundled JNA User32 mapping does not declare. */
    interface Focus extends com.sun.jna.Library {
        Focus INSTANCE = loadFocus();
        boolean IsIconic(WinDef.HWND hWnd);
        boolean BringWindowToTop(WinDef.HWND hWnd);
        boolean AttachThreadInput(int idAttach, int idAttachTo, boolean attach);
        void keybd_event(byte vk, byte scan, int flags, com.sun.jna.Pointer extraInfo);
    }

    private static Focus loadFocus() {
        try { return Native.load("user32", Focus.class); } catch (UnsatisfiedLinkError | NoClassDefFoundError e) { return null; }
    }

    /**
     * Brings a window to the front and checks that it got there. Windows refuses SetForegroundWindow to a process that
     * is not in front itself, so on a refusal the input of the current foreground thread is attached for a moment and
     * an ALT tap is sent (which lifts the lock), then the call is repeated.
     */
    static boolean bringToFront(WinDef.HWND h) {
        User32 u = User32.INSTANCE;
        Focus f = Focus.INSTANCE;
        if (f != null && f.IsIconic(h)) u.ShowWindow(h, SW_RESTORE); else u.ShowWindow(h, WinUser.SW_SHOW);
        u.SetForegroundWindow(h);
        for (int attempt = 0; attempt < 3; attempt++) {
            if (h.equals(u.GetForegroundWindow())) return true;
            WinDef.HWND fg = u.GetForegroundWindow();
            int fgThread = fg == null ? 0 : u.GetWindowThreadProcessId(fg, new IntByReference());
            int me = com.sun.jna.platform.win32.Kernel32.INSTANCE.GetCurrentThreadId();
            if (f == null) break;
            boolean attached = fgThread != 0 && fgThread != me && f.AttachThreadInput(me, fgThread, true);
            try {
                f.keybd_event((byte) 0x12, (byte) 0, 0, null);   // ALT down
                f.keybd_event((byte) 0x12, (byte) 0, 2, null);   // ALT up
                f.BringWindowToTop(h);
                u.SetForegroundWindow(h);
            } finally {
                if (attached) f.AttachThreadInput(me, fgThread, false);
            }
            try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        return h.equals(u.GetForegroundWindow());
    }

    private Map<String, Object> show(ActionContext ctx, String title, Long processId, Map<String, Object> alternatives, double wait, int cmd) {
        Win w = target(ctx, title, processId, alternatives, wait);
        User32.INSTANCE.ShowWindow(w.hwnd(), cmd);
        return Map.of("title", w.title());
    }

    @Action(id = "maximize", name = "Maximise window", category = "Window", capabilities = "desktop", description = "Maximise a window.",
            panel = "title | alternatives, timeout_seconds, process_id")
    @Output(name = "title", type = OoaType.STRING, description = "Full title of the window.")
    public Map<String, Object> maximize(ActionContext ctx, @Input(name = "title", type = OoaType.STRING, required = false, description = D_TITLE) String title,
            @Input(name = "alternatives", type = OoaType.DICTIONARY, required = false, description = D_ALT) Map<String, Object> alternatives,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0, description = D_WAIT) double wait,
            @Input(name = "process_id", type = OoaType.NUMBER, required = false, description = D_PID) Long processId) { return show(ctx, title, processId, alternatives, wait, SW_MAXIMIZE); }

    @Action(id = "minimize", name = "Minimise window", category = "Window", capabilities = "desktop", description = "Minimise a window.",
            panel = "title | alternatives, timeout_seconds, process_id")
    @Output(name = "title", type = OoaType.STRING, description = "Full title of the window.")
    public Map<String, Object> minimize(ActionContext ctx, @Input(name = "title", type = OoaType.STRING, required = false, description = D_TITLE) String title,
            @Input(name = "alternatives", type = OoaType.DICTIONARY, required = false, description = D_ALT) Map<String, Object> alternatives,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0, description = D_WAIT) double wait,
            @Input(name = "process_id", type = OoaType.NUMBER, required = false, description = D_PID) Long processId) { return show(ctx, title, processId, alternatives, wait, SW_MINIMIZE); }

    @Action(id = "restore", name = "Restore window", category = "Window", capabilities = "desktop", description = "Restore a minimised or maximised window to its normal size.",
            panel = "title | alternatives, timeout_seconds, process_id")
    @Output(name = "title", type = OoaType.STRING, description = "Full title of the window.")
    public Map<String, Object> restore(ActionContext ctx, @Input(name = "title", type = OoaType.STRING, required = false, description = D_TITLE) String title,
            @Input(name = "alternatives", type = OoaType.DICTIONARY, required = false, description = D_ALT) Map<String, Object> alternatives,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0, description = D_WAIT) double wait,
            @Input(name = "process_id", type = OoaType.NUMBER, required = false, description = D_PID) Long processId) { return show(ctx, title, processId, alternatives, wait, SW_RESTORE); }

    // ---------- hide and show (gap G03, 3 Nov 2026) ----------
    private static final int SW_HIDE = 0, SW_SHOWNA = 8;
    private static final String HIDDEN_SESSION = "ooa.window.hidden";

    /** Windows this run hid with show_at_end; shown again when the run ends, however it ends. */
    static final class HiddenWindows implements AutoCloseable {
        final Map<WinDef.HWND, String> windows = new LinkedHashMap<>();
        @Override public void close() {
            for (WinDef.HWND h : windows.keySet()) if (User32.INSTANCE.IsWindow(h) && !User32.INSTANCE.IsWindowVisible(h)) User32.INSTANCE.ShowWindow(h, SW_SHOWNA);
            windows.clear();
        }
    }

    private static HiddenWindows hiddenByRun(ActionContext ctx, boolean create) {
        Object o = ctx.sessions().get(HIDDEN_SESSION);
        if (o instanceof HiddenWindows h) return h;
        if (!create) return null;
        HiddenWindows h = new HiddenWindows();
        ctx.sessions().put(HIDDEN_SESSION, h);
        return h;
    }

    /** Top-level hidden windows without an owner whose title contains the text (hidden program windows, not system ones). */
    static List<Win> hidden(String titleContains, Long processId) {
        Set<Long> pids = new HashSet<>();
        if (processId != null && processId > 0) {
            pids.add(processId);
            ProcessHandle.of(processId).ifPresent(p -> p.descendants().forEach(d -> pids.add(d.pid())));
        }
        java.util.function.Predicate<String> q = titleContains == null || titleContains.isBlank() ? null : titleRule(titleContains);
        List<Win> out = new ArrayList<>();
        User32.INSTANCE.EnumWindows((hwnd, data) -> {
            if (!User32.INSTANCE.IsWindowVisible(hwnd) && User32.INSTANCE.GetWindow(hwnd, new WinDef.DWORD(GW_OWNER)) == null) {
                char[] buf = new char[512];
                User32.INSTANCE.GetWindowText(hwnd, buf, buf.length);
                String t = Native.toString(buf);
                IntByReference pid = new IntByReference();
                User32.INSTANCE.GetWindowThreadProcessId(hwnd, pid);
                if (!t.isBlank() && (q == null || q.test(t)) && (pids.isEmpty() || pids.contains((long) pid.getValue())))
                    out.add(new Win(hwnd, t));
            }
            return true;
        }, null);
        return out;
    }

    @Action(id = "hide", name = "Hide window", category = "Window", capabilities = "desktop",
            description = "Hide a window completely: unlike Minimise it also leaves the taskbar. The program keeps running, and Show window brings it back.",
            panel = "title, show_at_end | alternatives, timeout_seconds, process_id")
    @Output(name = "title", type = OoaType.STRING, description = "Full title of the hidden window.")
    public Map<String, Object> hide(ActionContext ctx, @Input(name = "title", type = OoaType.STRING, required = false, description = D_TITLE) String title,
            @Input(name = "show_at_end", type = OoaType.BOOLEAN, required = false, defaultValue = "true",
                    description = "Show the window again when the run ends (also when it fails), so no invisible windows are left behind. Turn off to keep it hidden after the run.") boolean showAtEnd,
            @Input(name = "alternatives", type = OoaType.DICTIONARY, required = false, description = D_ALT) Map<String, Object> alternatives,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0, description = D_WAIT) double wait,
            @Input(name = "process_id", type = OoaType.NUMBER, required = false, description = D_PID) Long processId) {
        Win w = target(ctx, title, processId, alternatives, wait);
        User32.INSTANCE.ShowWindow(w.hwnd(), SW_HIDE);
        if (User32.INSTANCE.IsWindowVisible(w.hwnd()))
            throw new ActionException("WINDOW.NOT_HIDDEN", "Windows did not let '" + w.title() + "' be hidden",
                    "The window may belong to a program running as administrator; run the agent with the same rights.", true);
        if (showAtEnd) hiddenByRun(ctx, true).windows.put(w.hwnd(), w.title());
        else { HiddenWindows h = hiddenByRun(ctx, false); if (h != null) h.windows.remove(w.hwnd()); }
        return Map.of("title", w.title());
    }

    @Action(id = "show", name = "Show window", category = "Window", capabilities = "desktop",
            description = "Show a window that was hidden (by Hide window or by its program), optionally bringing it to the front.",
            panel = "title, activate | timeout_seconds, process_id")
    @Output(name = "title", type = OoaType.STRING, description = "Full title of the window shown.")
    public Map<String, Object> show(ActionContext ctx, @Input(name = "title", type = OoaType.STRING, required = false, description = D_TITLE_TEXT + " Hidden windows are searched first, then visible ones. May be empty when process_id is given.") String title,
            @Input(name = "activate", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Also bring the window to the front.") boolean activate,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0, description = D_WAIT) double wait,
            @Input(name = "process_id", type = OoaType.NUMBER, required = false, description = D_PID) Long processId) {
        windows();
        UiGuard.before(ctx);
        if ((title == null || title.isBlank()) && (processId == null || processId <= 0))
            throw new ActionException("WINDOW.NO_TARGET", "Give the window title or a process id", "Fill in title, or process_id from Start program.");
        java.util.function.Predicate<String> q = titleRule(title);
        long end = System.nanoTime() + (long) (Math.max(0, wait) * 1e9);
        while (true) {
            Win w = null;
            HiddenWindows mine = hiddenByRun(ctx, false);
            if (mine != null && (processId == null || processId <= 0))  // the windows this run hid come first
                for (Map.Entry<WinDef.HWND, String> e : mine.windows.entrySet())
                    if (User32.INSTANCE.IsWindow(e.getKey()) && q.test(e.getValue())) { w = new Win(e.getKey(), e.getValue()); break; }
            if (w == null) w = hidden(title, processId).stream().findFirst().orElse(null);
            if (w == null) {   // already visible: nothing to show, but bring it to the front when asked
                Optional<Win> v = processId != null && processId > 0 ? mainWindow(processId, title)
                        : all().stream().filter(x -> q.test(x.title())).findFirst();
                if (v.isPresent()) w = v.get();
            }
            if (w != null) {
                User32.INSTANCE.ShowWindow(w.hwnd(), WinUser.SW_SHOW);
                if (mine != null) mine.windows.remove(w.hwnd());
                if (activate) bringToFront(w.hwnd());
                return Map.of("title", w.title());
            }
            if (System.nanoTime() >= end)
                throw new ActionException("WINDOW.NOT_FOUND", "No hidden or visible window " + (title == null || title.isBlank() ? "of process " + processId : "title matches '" + title + "'"),
                        "Check the title; Hide window reports the full title of the window it hid.", true);
            ctx.checkCancelled();
            try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new ActionException("RUN.CANCELLED", "Interrupted"); }
        }
    }

    @Action(id = "move_resize", name = "Move and resize window", category = "Window", capabilities = "desktop",
            description = "Set a window's position and/or size in pixels; values left empty keep the window's current ones.",
            panel = "title, x, y, width, height | alternatives, timeout_seconds, process_id")
    public Map<String, Object> moveResize(ActionContext ctx,
            @Input(name = "title", type = OoaType.STRING, required = false, description = D_TITLE) String title,
            @Input(name = "x", type = OoaType.NUMBER, required = false, description = "Left; unchanged when empty.") Integer x,
            @Input(name = "y", type = OoaType.NUMBER, required = false, description = "Top; unchanged when empty.") Integer y,
            @Input(name = "width", type = OoaType.NUMBER, required = false, description = "Width; unchanged when empty.") Integer w,
            @Input(name = "height", type = OoaType.NUMBER, required = false, description = "Height; unchanged when empty.") Integer h,
            @Input(name = "alternatives", type = OoaType.DICTIONARY, required = false, description = D_ALT) Map<String, Object> alternatives,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0, description = D_WAIT) double wait,
            @Input(name = "process_id", type = OoaType.NUMBER, required = false, description = D_PID) Long processId) {
        if ((w != null && w <= 0) || (h != null && h <= 0)) throw new ActionException("WINDOW.BAD_SIZE", "Width and height must be greater than 0", "Check the size.");
        Win win = target(ctx, title, processId, alternatives, wait);
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
            description = "Find a window and return its title, program, class, position, size and state, e.g. to keep it in a variable for later steps.",
            panel = "title | alternatives, timeout_seconds, process_id")
    @Output(name = "window", type = OoaType.DICTIONARY, description = "title, process_name, class_name, x, y, width, height, state (normal, minimized or maximized), active, process_id.")
    @Output(name = "width", type = OoaType.NUMBER, description = "Width in pixels.")
    @Output(name = "height", type = OoaType.NUMBER, description = "Height in pixels.")
    @Output(name = "x", type = OoaType.NUMBER, description = "Left edge in pixels.")
    @Output(name = "y", type = OoaType.NUMBER, description = "Top edge in pixels.")
    @Output(name = "state", type = OoaType.STRING, description = "normal, minimized or maximized.")
    @Output(name = "process_id", type = OoaType.NUMBER, description = "Id of the program that owns the window, for Stop program.")
    public Map<String, Object> getInfo(ActionContext ctx,
            @Input(name = "title", type = OoaType.STRING, required = false, description = D_TITLE) String title,
            @Input(name = "alternatives", type = OoaType.DICTIONARY, required = false, description = D_ALT) Map<String, Object> alternatives,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0, description = D_WAIT) double wait,
            @Input(name = "process_id", type = OoaType.NUMBER, required = false, description = D_PID) Long processId) {
        return infoOutputs(details(target(ctx, title, processId, alternatives, wait)));
    }

    /** The details dictionary plus its size, position and state as separate outputs. */
    static Map<String, Object> infoOutputs(Map<String, Object> d) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("window", d);
        for (String k : List.of("width", "height", "x", "y", "state", "process_id")) out.put(k, d.get(k));
        return out;
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
        // the owning process, so Stop program can end exactly this program (stopping by name stops every copy)
        com.sun.jna.ptr.IntByReference pid = new com.sun.jna.ptr.IntByReference();
        User32.INSTANCE.GetWindowThreadProcessId(w.hwnd(), pid);
        m.put("process_id", java.math.BigDecimal.valueOf(Integer.toUnsignedLong(pid.getValue())));
        return m;
    }

    static String state(int showCmd) { return switch (showCmd) { case 2, 6, 7 -> "minimized"; case 3 -> "maximized"; default -> "normal"; }; }

    @Action(id = "close", name = "Close window", category = "Window", capabilities = "desktop", description = "Ask a window to close (like clicking its X; the program may ask to save).",
            panel = "title | alternatives, timeout_seconds, wait_closed_seconds, process_id")
    @Output(name = "closed", type = OoaType.BOOLEAN, description = "True when the window was gone by the end of wait_closed_seconds (always true when not waiting).")
    public Map<String, Object> close(ActionContext ctx, @Input(name = "title", type = OoaType.STRING, required = false, description = D_TITLE) String title,
            @Input(name = "alternatives", type = OoaType.DICTIONARY, required = false, description = D_ALT) Map<String, Object> alternatives,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0, description = D_WAIT) double wait,
            @Input(name = "wait_closed_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0, description = "Wait up to this many seconds for the window to disappear; 0 does not wait.") double waitClosed,
            @Input(name = "process_id", type = OoaType.NUMBER, required = false, description = D_PID) Long processId) {
        Win w = target(ctx, title, processId, alternatives, wait);
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
        java.util.function.Predicate<String> m = titleRule(pattern);
        if (m.test("")) throw new ActionException("WINDOW.PATTERN_TOO_BROAD", "The pattern matches an empty title, so it would match every window", "Make the pattern more specific.");
        return m;
    }

    public Map<String, Object> closeAll(ActionContext ctx, String title, boolean regex) { return closeAll(ctx, title, regex, null); }

    @Action(id = "close_all", name = "Close all matching windows", category = "Window", capabilities = "desktop",
            description = "Ask every visible window whose title matches to close (like clicking each X); programs may still ask to save.",
            panel = "title, keep | use_regex")
    @Output(name = "closed", type = OoaType.LIST, description = "Titles of the windows asked to close.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of windows asked to close.")
    public Map<String, Object> closeAll(ActionContext ctx,
            @Input(name = "title", type = OoaType.STRING, example = " - Notepad", description = "Text in the window titles, a pattern with * or ?, or re: and a regular expression (case-insensitive); the tick below reads the whole title as a regular expression, as older agents do.") String title,
            @Input(name = "use_regex", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Treat title as a regular expression.") boolean regex,
            @Input(name = "keep", type = OoaType.LIST, required = false,
                    description = "Windows to leave open: text in their titles (case-insensitive), e.g. [\"Invoices.xlsx\"]. The agent's own windows are always kept.") List<Object> keep) {
        java.util.function.Predicate<String> m = matcher(title, regex);
        List<String> keepTexts = new ArrayList<>();
        if (keep != null) for (Object k : keep) if (k != null && !String.valueOf(k).isBlank()) keepTexts.add(String.valueOf(k).strip().toLowerCase(Locale.ROOT));
        long self = ProcessHandle.current().pid();
        List<Object> closed = new ArrayList<>();
        for (Win w : all()) {
            if (!m.test(w.title())) continue;
            String lower = w.title().toLowerCase(Locale.ROOT);
            if (keepTexts.stream().anyMatch(lower::contains)) continue;
            IntByReference pid = new IntByReference();
            User32.INSTANCE.GetWindowThreadProcessId(w.hwnd(), pid);
            if (pid.getValue() == self) continue;   // never close the run window or Studio running in this process
            User32.INSTANCE.PostMessage(w.hwnd(), WM_CLOSE, new WinDef.WPARAM(0), new WinDef.LPARAM(0));
            closed.add(w.title());
        }
        if (!closed.isEmpty()) ctx.info("Asked " + closed.size() + " window(s) to close");
        return Map.of("closed", closed, "count", java.math.BigDecimal.valueOf(closed.size()));
    }

    interface User32Text extends com.sun.jna.Library {
        User32Text INSTANCE = loadText();
        boolean SetWindowTextW(WinDef.HWND hWnd, com.sun.jna.WString text);
        /** WM_GETTEXT into a buffer; the result is the number of characters copied. */
        WinDef.LRESULT SendMessageW(WinDef.HWND hWnd, int msg, WinDef.WPARAM wParam, char[] buffer);
        WinDef.LRESULT SendMessageW(WinDef.HWND hWnd, int msg, WinDef.WPARAM wParam, com.sun.jna.WString text);
    }

    private static User32Text loadText() {
        try { return Native.load("user32", User32Text.class); } catch (UnsatisfiedLinkError | NoClassDefFoundError e) { return null; }
    }

    // ---------- typing into a window in the background ----------

    static final int WM_SETTEXT = 0x000C, WM_GETTEXT = 0x000D, WM_GETTEXTLENGTH = 0x000E, WM_KEYDOWN = 0x0100, WM_KEYUP = 0x0101, WM_CHAR = 0x0102, EM_SETSEL = 0x00B1;

    /** Special keys written in braces in the text, e.g. {ENTER}, {TAB}, {F5}; {{} and {}} type the braces themselves. */
    static final Map<String, Integer> VKEYS = Map.ofEntries(Map.entry("ENTER", 0x0D), Map.entry("TAB", 0x09), Map.entry("BACKSPACE", 0x08), Map.entry("BS", 0x08),
            Map.entry("DELETE", 0x2E), Map.entry("DEL", 0x2E), Map.entry("ESC", 0x1B), Map.entry("ESCAPE", 0x1B), Map.entry("HOME", 0x24), Map.entry("END", 0x23),
            Map.entry("LEFT", 0x25), Map.entry("UP", 0x26), Map.entry("RIGHT", 0x27), Map.entry("DOWN", 0x28), Map.entry("PGUP", 0x21), Map.entry("PGDN", 0x22),
            Map.entry("INSERT", 0x2D), Map.entry("SPACE", 0x20), Map.entry("F1", 0x70), Map.entry("F2", 0x71), Map.entry("F3", 0x72), Map.entry("F4", 0x73),
            Map.entry("F5", 0x74), Map.entry("F6", 0x75), Map.entry("F7", 0x76), Map.entry("F8", 0x77), Map.entry("F9", 0x78), Map.entry("F10", 0x79),
            Map.entry("F11", 0x7A), Map.entry("F12", 0x7B));

    /** The text split into characters to type and {KEY} or {KEY n} presses: each entry is a String (text) or an int[]{vk, times}. */
    static List<Object> keyPlan(String text) {
        List<Object> out = new ArrayList<>();
        StringBuilder plain = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                int close = text.indexOf('}', i + 2);
                if (text.startsWith("{{}", i)) { plain.append('{'); i += 2; continue; }
                if (text.startsWith("{}}", i)) { plain.append('}'); i += 2; continue; }
                if (close > 0) {
                    String[] parts = text.substring(i + 1, close).trim().toUpperCase(Locale.ROOT).split("\\s+");
                    Integer vk = VKEYS.get(parts[0]);
                    if (vk == null) vk = VKEYS.get(switch (com.oneofficeautomation.packages.KeyNames.desktop(parts[0])) {   // K1: other spellings
                        case "PAGE_UP" -> "PGUP"; case "PAGE_DOWN" -> "PGDN"; case String s -> s; });
                    if (vk != null) {
                        int times = 1;
                        if (parts.length > 1) try { times = Math.max(1, Math.min(200, Integer.parseInt(parts[1]))); } catch (NumberFormatException e) { vk = null; }
                        if (vk != null) {
                            if (plain.length() > 0) { out.add(plain.toString()); plain.setLength(0); }
                            out.add(new int[]{vk, times});
                            i = close;
                            continue;
                        }
                    }
                    throw new ActionException("WINDOW.BAD_KEY", "Unknown key " + text.substring(i, close + 1), "Use {ENTER}, {TAB}, {BACKSPACE}, {DELETE}, {ESC}, arrows, {HOME}, {END}, {PGUP}, {PGDN}, {F1}-{F12}, optionally with a count such as {TAB 3}; {{} types {.");
                }
            }
            plain.append(c == '\n' ? '\r' : c);
        }
        if (plain.length() > 0) out.add(plain.toString());
        return out;
    }

    /** The control to type into: a child window of the given class (and index), the window's focused control, or the window itself. */
    static WinDef.HWND inputControl(Win w, String controlClass, int index) {
        if (controlClass != null && !controlClass.isBlank()) {
            List<WinDef.HWND> found = new ArrayList<>();
            User32.INSTANCE.EnumChildWindows(w.hwnd(), (h, d) -> {
                char[] b = new char[256];
                User32.INSTANCE.GetClassName(h, b, b.length);
                String cls = Native.toString(b);
                if (cls.equalsIgnoreCase(controlClass.trim()) || TitlePattern.matches(controlClass.trim(), cls)) found.add(h);
                return true;
            }, null);
            if (index < 0 || index >= found.size())
                throw new ActionException("WINDOW.NO_CONTROL", "Window '" + w.title() + "' has " + (found.isEmpty() ? "no" : found.size()) + " control" + (found.size() == 1 ? "" : "s") + " of class " + controlClass
                        + (found.isEmpty() ? "" : "; index " + index + " is out of range"), "Check the class name (e.g. Edit, RichEdit20W) with a window spy, and use control_index 0 for the first.");
            return found.get(index);
        }
        int thread = User32.INSTANCE.GetWindowThreadProcessId(w.hwnd(), null);
        WinUser.GUITHREADINFO info = new WinUser.GUITHREADINFO();
        info.cbSize = info.size();
        if (User32.INSTANCE.GetGUIThreadInfo(thread, info) && info.hwndFocus != null) return info.hwndFocus;
        // no focused control known: the first edit-like child, else the window
        WinDef.HWND[] edit = {null};
        User32.INSTANCE.EnumChildWindows(w.hwnd(), (h, d) -> {
            char[] b = new char[256];
            User32.INSTANCE.GetClassName(h, b, b.length);
            String cls = Native.toString(b).toLowerCase(Locale.ROOT);
            if (cls.equals("edit") || cls.startsWith("richedit") || cls.contains("scintilla")) { edit[0] = h; return false; }
            return true;
        }, null);
        return edit[0] != null ? edit[0] : w.hwnd();
    }

    static String controlText(WinDef.HWND h) {
        if (User32Text.INSTANCE == null) return "";
        int len = User32.INSTANCE.SendMessage(h, WM_GETTEXTLENGTH, new WinDef.WPARAM(0), new WinDef.LPARAM(0)).intValue();
        if (len <= 0) return "";
        char[] buf = new char[Math.min(len, 1_000_000) + 1];
        int n = User32Text.INSTANCE.SendMessageW(h, WM_GETTEXT, new WinDef.WPARAM(buf.length), buf).intValue();
        return new String(buf, 0, Math.max(0, Math.min(n, buf.length)));
    }

    @Action(id = "type_in_background", name = "Type into window in background", category = "Keyboard", capabilities = "desktop", timeoutSeconds = 600,
            description = "Type text and keys such as {ENTER} or {TAB} into a window without bringing it to the front or using the keyboard, so it works while the window is behind others or minimised and the person keeps working.",
            panel = "title, text, (Into: Its focused control = / A control of a class = control_class, control_index), method | delay_ms, alternatives, timeout_seconds, process_id")
    @Output(name = "control_class", type = OoaType.STRING, description = "Class of the control typed into, e.g. Edit.")
    @Output(name = "control_text", type = OoaType.STRING, description = "The control's text afterwards, when it reports one (edit boxes do), to check the result.")
    public Map<String, Object> typeInBackground(ActionContext ctx,
            @Input(name = "title", type = OoaType.STRING, required = false, description = D_TITLE) String title,
            @Input(name = "text", description = "Text to type; {ENTER}, {TAB}, {BACKSPACE}, {DELETE}, {ESC}, arrows, {HOME}, {END}, {F1}-{F12} press those keys ({TAB 3} three times; a { inside braces types a {). A Credential is typed without being logged.") Object text,
            @Input(name = "control_class", type = OoaType.STRING, required = false, example = "Edit", description = "Window class of the control to type into, or a pattern with * and ?; the window's focused control when empty.") String controlClass,
            @Input(name = "control_index", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0, description = "Which control of that class (0 is the first).") int controlIndex,
            @Input(name = "method", type = OoaType.STRING, required = false, defaultValue = "\"type\"", enumValues = {"type", "replace", "append"},
                    description = "type sends each character and key to the control; replace sets the control's whole text (edit boxes); append adds the text at the end of it.") String method,
            @Input(name = "delay_ms", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0, max = 1000, description = "Pause after each character or key, for programs that read input slowly.") int delayMs,
            @Input(name = "alternatives", type = OoaType.DICTIONARY, required = false, description = D_ALT) Map<String, Object> alternatives,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0, description = D_WAIT) double wait,
            @Input(name = "process_id", type = OoaType.NUMBER, required = false, description = D_PID) Long processId) {
        String m = method == null || method.isBlank() ? "type" : method.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("type", "replace", "append").contains(m)) throw new ActionException("WINDOW.BAD_OPTION", "method must be type, replace or append", null);
        boolean secret = text instanceof SecureString;
        String value = text instanceof SecureString s ? s.reveal() : text == null ? "" : com.oneofficeautomation.runner.Values.text(text);
        List<Object> plan = m.equals("type") ? keyPlan(value) : List.of();
        Win w = alternatives == null && wait <= 0 && (processId == null || processId <= 0) && title != null && !title.isBlank() ? find(title) : target(ctx, title, processId, alternatives, wait);
        WinDef.HWND h = inputControl(w, controlClass, controlIndex);
        char[] cb = new char[256];
        User32.INSTANCE.GetClassName(h, cb, cb.length);
        String cls = Native.toString(cb);
        if (m.equals("replace") || m.equals("append")) {
            if (User32Text.INSTANCE == null) throw new ActionException("WINDOW.NOT_SUPPORTED", "Cannot set text on this system", null);
            String now = m.equals("append") ? controlText(h) + value.replace("\r\n", "\n").replace("\n", "\r\n") : value.replace("\r\n", "\n").replace("\n", "\r\n");
            User32Text.INSTANCE.SendMessageW(h, WM_SETTEXT, new WinDef.WPARAM(0), new com.sun.jna.WString(now));
        } else {
            // Messages are sent, not posted: each one is handled by the program's thread before the next, so nothing arrives
            // out of order or after the step has read the text back. Sent key presses do not pass through the program's
            // message loop (TranslateMessage), so the character of Enter, Tab, Backspace, Esc and Space is sent with them.
            for (Object step : plan) {
                ctx.checkCancelled();
                if (step instanceof String s) {
                    for (int i = 0; i < s.length(); i++) {
                        send(h, WM_CHAR, s.charAt(i), 1);
                        pause(delayMs);
                    }
                } else {
                    int[] k = (int[]) step;
                    long scan = User32.INSTANCE.MapVirtualKeyEx(k[0], 0, null) & 0xFF;
                    int ch = keyChar(k[0]);
                    for (int t = 0; t < k[1]; t++) {
                        send(h, WM_KEYDOWN, k[0], 1 | (scan << 16));
                        if (ch != 0) send(h, WM_CHAR, ch, 1 | (scan << 16));
                        send(h, WM_KEYUP, k[0], 1 | (scan << 16) | 0xC0000000L);
                        pause(delayMs);
                    }
                }
            }
        }
        if (!secret) ctx.info("Typed into " + cls + " of '" + w.title() + "' in the background");
        String after = secret ? "" : controlText(h);
        return Map.of("control_class", cls, "control_text", after);
    }

    /** The character a key types in an edit control (Enter, Tab, Backspace, Esc, Space), or 0. */
    static int keyChar(int vk) {
        return switch (vk) { case 0x0D -> '\r'; case 0x09 -> '\t'; case 0x08 -> '\b'; case 0x1B -> 0x1B; case 0x20 -> ' '; default -> 0; };
    }

    /** Sends a message and waits until the window's thread has handled it (at most 5 s, not at all when it hangs). */
    private static void send(WinDef.HWND h, int msg, long wParam, long lParam) {
        WinDef.DWORDByReference result = new WinDef.DWORDByReference();
        WinDef.LRESULT ok = User32.INSTANCE.SendMessageTimeout(h, msg, new WinDef.WPARAM(wParam), new WinDef.LPARAM(lParam), 0x0002 /* SMTO_ABORTIFHUNG */, 5000, result);
        if (ok.longValue() == 0) throw new ActionException("WINDOW.NOT_RESPONDING", "The window did not take the typed input within 5 s", "Check the program is not busy or showing a dialog.");
    }

    private static void pause(int ms) {
        if (ms <= 0) return;
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new ActionException("RUN.CANCELLED", "Stopped while typing"); }
    }

    @Action(id = "set_title", name = "Set window title", category = "Window", capabilities = "desktop",
            description = "Change the title bar text of a window, e.g. to tell several copies of the same program apart.",
            panel = "title, new_title | alternatives, timeout_seconds, process_id")
    @Output(name = "previous_title", type = OoaType.STRING, description = "Title before the change.")
    public Map<String, Object> setTitle(ActionContext ctx,
            @Input(name = "title", type = OoaType.STRING, required = false, description = D_TITLE) String title,
            @Input(name = "new_title", type = OoaType.STRING, description = "New title text.") String newTitle,
            @Input(name = "alternatives", type = OoaType.DICTIONARY, required = false, description = D_ALT) Map<String, Object> alternatives,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0, description = D_WAIT) double wait,
            @Input(name = "process_id", type = OoaType.NUMBER, required = false, description = D_PID) Long processId) {
        if (newTitle == null || newTitle.length() > 512 || newTitle.chars().anyMatch(c -> c < 0x20))
            throw new ActionException("WINDOW.BAD_TITLE", "The new title must be at most 512 characters without line breaks", "Shorten the title.");
        Win w = alternatives == null && wait <= 0 && (processId == null || processId <= 0) && title != null && !title.isBlank() ? find(title) : target(ctx, title, processId, alternatives, wait);
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

    public Map<String, Object> waitFor(ActionContext ctx, String title, double timeout, Map<String, Object> alternatives) {
        return waitFor(ctx, title, timeout, alternatives, "open", true, null);
    }

    @Action(id = "wait_for", name = "Wait for window", category = "Window", timeoutSeconds = 3600, capabilities = "desktop",
            description = "Wait until a window whose title matches (or the main window of a process) opens, or until it has closed.",
            panel = "state, title, timeout_seconds, fail_on_timeout | alternatives, process_id")
    @Output(name = "title", type = OoaType.STRING, description = "Full title of the window that opened (or that was open when waiting for it to close).")
    @Output(name = "met", type = OoaType.BOOLEAN, description = "True when the window opened or closed in time.")
    public Map<String, Object> waitFor(ActionContext ctx,
            @Input(name = "title", type = OoaType.STRING, required = false, description = D_TITLE) String title,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "30", min = 0, description = "Maximum wait.") double timeout,
            @Input(name = "alternatives", type = OoaType.DICTIONARY, required = false, visibleWhen = "state=open", description = D_ALT) Map<String, Object> alternatives,
            @Input(name = "state", type = OoaType.STRING, required = false, defaultValue = "\"open\"", enumValues = {"open", "close"},
                    description = "open: wait until the window is there; close: wait until no window with this title is left.") String state,
            @Input(name = "fail_on_timeout", type = OoaType.BOOLEAN, required = false, defaultValue = "true",
                    description = "Fail the step when time runs out; when off, met is false and the agent carries on.") boolean failOnTimeout,
            @Input(name = "process_id", type = OoaType.NUMBER, required = false, description = D_PID) Long processId) {
        if ("close".equals(state)) {
            boolean byProcess = processId != null && processId > 0;
            java.util.function.Predicate<String> m = byProcess ? null : matcher(title, false);
            long end = System.nanoTime() + (long) (timeout * 1e9);
            String last = "";
            while (true) {
                Optional<Win> open = byProcess ? mainWindow(processId, title) : all().stream().filter(w -> m.test(w.title())).findFirst();
                if (open.isEmpty()) return Map.of("title", last, "met", true);
                last = open.get().title();
                if (System.nanoTime() >= end) {
                    if (failOnTimeout) throw new ActionException("WINDOW.STILL_OPEN", "Window '" + last + "' was still open after " + timeout + " s",
                            "Raise timeout_seconds, or close the window first (Close window).", true);
                    return Map.of("title", last, "met", false);
                }
                ctx.checkCancelled();
                try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new ActionException("RUN.CANCELLED", "Interrupted"); }
            }
        }
        try {
            return Map.of("title", target(ctx, title, processId, alternatives, timeout).title(), "met", true);
        } catch (ActionException e) {
            if (failOnTimeout || !"WINDOW.NOT_FOUND".equals(e.code())) throw e;
            return Map.of("title", "", "met", false);
        }
    }

    @Condition(id = "exists", name = "Window exists", description = "True when a visible window title matches (text in it, * or ? pattern, or re:).")
    public boolean exists(ActionContext ctx, @Input(name = "title", type = OoaType.STRING, description = D_TITLE_TEXT) String title) {
        java.util.function.Predicate<String> q = titleRule(title);
        return all().stream().anyMatch(w -> q.test(w.title()));
    }

    @Action(id = "capture", name = "Capture window", category = "Window", capabilities = {"desktop", "filesystem"}, description = "Save a PNG screenshot of one window.",
            panel = "title, path, overwrite | activate, alternatives, timeout_seconds, process_id")
    @Output(name = "path", type = OoaType.FILE, description = "Saved image.")
    public Map<String, Object> capture(ActionContext ctx,
            @Input(name = "title", type = OoaType.STRING, required = false, description = D_TITLE) String title,
            @Input(name = "path", type = OoaType.FILE, description = "PNG file to create.") String path,
            @Input(name = "alternatives", type = OoaType.DICTIONARY, required = false, description = D_ALT) Map<String, Object> alternatives,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0, description = D_WAIT) double wait,
            @Input(name = "activate", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Bring the window to the front first so nothing covers it.") boolean activate,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Replace an existing file; when false an existing file stops the step.") boolean overwrite,
            @Input(name = "process_id", type = OoaType.NUMBER, required = false, description = D_PID) Long processId) {
        if (!overwrite && Files.exists(ctx.resolvePath(path))) throw new ActionException("FILE.EXISTS", "File already exists: " + ctx.resolvePath(path), "Set overwrite to true or choose another name.");
        Win w = target(ctx, title, processId, alternatives, wait);
        if (activate) {
            bringToFront(w.hwnd());
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


    @Condition(id = "is_active", name = "Window is active", description = "True when the window in front has a matching title (text in it, * or ? pattern, or re:).")
    public boolean isActive(ActionContext ctx, @Input(name = "title", type = OoaType.STRING, description = D_TITLE_TEXT) String title) {
        windows();
        char[] buf = new char[512];
        User32.INSTANCE.GetWindowText(User32.INSTANCE.GetForegroundWindow(), buf, buf.length);
        return titleRule(title).test(Native.toString(buf));
    }
}
