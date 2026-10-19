package com.oneofficeautomation.packages.windows;

import com.oneofficeautomation.sdk.*;
import com.sun.jna.Native;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;

import javax.imageio.ImageIO;
import java.awt.Rectangle;
import java.awt.Robot;
import java.nio.file.*;
import java.util.*;

/** Find, activate, resize, minimise, maximise, close and capture desktop windows (Windows). */
@OoaPackage(id = "ooa.window", name = "Window", version = "1.0.0", description = "Find, activate, move, resize, minimise, maximise, close and capture application windows on Windows.")
public final class WindowPackage {
    private static final int SW_MAXIMIZE = 3, SW_MINIMIZE = 6, SW_RESTORE = 9, WM_CLOSE = 0x0010;

    private static void windows() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"))
            throw new ActionException("SYSTEM.WINDOWS_ONLY", "Window commands work on Windows only", "Run this bot on a Windows device.");
    }

    record Win(WinDef.HWND hwnd, String title) {}

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

    private static final String D_TITLE = "Text contained in the window title (case-insensitive).";

    @Action(id = "list", name = "List windows", category = "Window", idempotent = true, capabilities = "desktop", description = "List the titles of all visible windows.")
    @Output(name = "titles", type = OoaType.LIST, description = "Window titles.")
    public Map<String, Object> list(ActionContext ctx) {
        List<Object> t = new ArrayList<>();
        for (Win w : all()) t.add(w.title());
        return Map.of("titles", t);
    }

    @Action(id = "activate", name = "Activate window", category = "Window", capabilities = "desktop", description = "Bring a window to the front (restoring it if minimised).")
    @Output(name = "title", type = OoaType.STRING, description = "Full title of the activated window.")
    public Map<String, Object> activate(ActionContext ctx, @Input(name = "title", type = OoaType.STRING, description = D_TITLE) String title) {
        Win w = find(title);
        User32.INSTANCE.ShowWindow(w.hwnd(), SW_RESTORE);
        User32.INSTANCE.SetForegroundWindow(w.hwnd());
        return Map.of("title", w.title());
    }

    private Map<String, Object> show(String title, int cmd) { Win w = find(title); User32.INSTANCE.ShowWindow(w.hwnd(), cmd); return Map.of(); }

    @Action(id = "maximize", name = "Maximise window", category = "Window", capabilities = "desktop", description = "Maximise a window.")
    public Map<String, Object> maximize(ActionContext ctx, @Input(name = "title", type = OoaType.STRING, description = D_TITLE) String title) { return show(title, SW_MAXIMIZE); }

    @Action(id = "minimize", name = "Minimise window", category = "Window", capabilities = "desktop", description = "Minimise a window.")
    public Map<String, Object> minimize(ActionContext ctx, @Input(name = "title", type = OoaType.STRING, description = D_TITLE) String title) { return show(title, SW_MINIMIZE); }

    @Action(id = "restore", name = "Restore window", category = "Window", capabilities = "desktop", description = "Restore a minimised or maximised window to its normal size.")
    public Map<String, Object> restore(ActionContext ctx, @Input(name = "title", type = OoaType.STRING, description = D_TITLE) String title) { return show(title, SW_RESTORE); }

    @Action(id = "move_resize", name = "Move and resize window", category = "Window", capabilities = "desktop", description = "Set a window's position and size in pixels.")
    public Map<String, Object> moveResize(ActionContext ctx,
            @Input(name = "title", type = OoaType.STRING, description = D_TITLE) String title,
            @Input(name = "x", type = OoaType.NUMBER, description = "Left.") int x,
            @Input(name = "y", type = OoaType.NUMBER, description = "Top.") int y,
            @Input(name = "width", type = OoaType.NUMBER, description = "Width.") int w,
            @Input(name = "height", type = OoaType.NUMBER, description = "Height.") int h) {
        Win win = find(title);
        User32.INSTANCE.ShowWindow(win.hwnd(), SW_RESTORE);
        User32.INSTANCE.MoveWindow(win.hwnd(), x, y, w, h, true);
        return Map.of();
    }

    @Action(id = "close", name = "Close window", category = "Window", capabilities = "desktop", description = "Ask a window to close (like clicking its X; the program may ask to save).")
    public Map<String, Object> close(ActionContext ctx, @Input(name = "title", type = OoaType.STRING, description = D_TITLE) String title) {
        Win w = find(title);
        User32.INSTANCE.PostMessage(w.hwnd(), WM_CLOSE, new WinDef.WPARAM(0), new WinDef.LPARAM(0));
        return Map.of();
    }

    @Action(id = "get_active", name = "Get active window title", category = "Window", idempotent = true, capabilities = "desktop", description = "Return the title of the window in front.")
    @Output(name = "title", type = OoaType.STRING, description = "Title of the foreground window.")
    public Map<String, Object> getActive(ActionContext ctx) {
        windows();
        char[] buf = new char[512];
        User32.INSTANCE.GetWindowText(User32.INSTANCE.GetForegroundWindow(), buf, buf.length);
        return Map.of("title", Native.toString(buf));
    }

    @Action(id = "wait_for", name = "Wait for window", category = "Window", timeoutSeconds = 3600, capabilities = "desktop", description = "Wait until a window with this title appears.")
    @Output(name = "title", type = OoaType.STRING, description = "Full title of the window.")
    public Map<String, Object> waitFor(ActionContext ctx,
            @Input(name = "title", type = OoaType.STRING, description = D_TITLE) String title,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "30", description = "Maximum wait.") double timeout) {
        long end = System.nanoTime() + (long) (timeout * 1e9);
        while (true) {
            try { return Map.of("title", find(title).title()); }
            catch (ActionException e) {
                if (System.nanoTime() > end) throw new ActionException("WINDOW.NOT_FOUND", "Window '" + title + "' did not appear within " + timeout + " s", "Check the application started.", true);
            }
            ctx.checkCancelled();
            try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new OoaException("RUN.CANCELLED", "Interrupted"); }
        }
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
            @Input(name = "path", type = OoaType.FILE, description = "PNG file to create.") String path) {
        Win w = find(title);
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
