package com.oneofficeautomation.packages.windows;

import com.oneofficeautomation.packages.ui.DesktopEvents;
import com.oneofficeautomation.packages.ui.TitlePattern;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;

/** Win32 desktop access for interrupt rules and runner triggers: window titles, close and activate, global hotkeys. */
public final class JnaDesktopEvents implements DesktopEvents {
    private static final int WM_CLOSE = 0x0010, WM_QUIT = 0x0012, WM_HOTKEY = 0x0312, SW_RESTORE = 9;

    public JnaDesktopEvents() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) throw new IllegalStateException("Windows only");
    }

    @Override public List<String> windowTitles() {
        List<String> out = new ArrayList<>();
        for (WindowPackage.Win w : WindowPackage.all()) out.add(w.title());
        return out;
    }

    private static Optional<WindowPackage.Win> first(String pattern) {
        Predicate<String> p = TitlePattern.of(pattern);
        return WindowPackage.all().stream().filter(w -> p.test(w.title())).findFirst();
    }

    @Override public boolean closeWindow(String pattern) {
        return first(pattern).map(w -> { User32.INSTANCE.PostMessage(w.hwnd(), WM_CLOSE, new WinDef.WPARAM(0), new WinDef.LPARAM(0)); return true; }).orElse(false);
    }

    @Override public boolean activateWindow(String pattern) {
        return first(pattern).map(w -> { User32.INSTANCE.ShowWindow(w.hwnd(), SW_RESTORE); User32.INSTANCE.SetForegroundWindow(w.hwnd()); return true; }).orElse(false);
    }

    /** Registers the hotkey on its own thread, which then runs the Win32 message loop that receives WM_HOTKEY. */
    @Override public AutoCloseable onHotkey(String combination, Runnable onPress) {
        HotkeyCombo hk = HotkeyCombo.parse(combination);
        CompletableFuture<Integer> ready = new CompletableFuture<>();
        Thread t = new Thread(() -> {
            int tid = Kernel32.INSTANCE.GetCurrentThreadId();
            if (!User32.INSTANCE.RegisterHotKey(null, 1, hk.modifiers() | HotkeyCombo.MOD_NOREPEAT, hk.vk())) {
                ready.completeExceptionally(new IllegalStateException("The hotkey " + hk.text() + " is already used by another program"));
                return;
            }
            ready.complete(tid);
            WinUser.MSG msg = new WinUser.MSG();
            try {
                while (User32.INSTANCE.GetMessage(msg, null, 0, 0) > 0)
                    if (msg.message == WM_HOTKEY) Thread.ofVirtual().start(onPress);
            } finally { User32.INSTANCE.UnregisterHotKey(null, 1); }
        }, "ooa-hotkey-" + hk.text());
        t.setDaemon(true);
        t.start();
        int tid;
        try { tid = ready.get(5, TimeUnit.SECONDS); }
        catch (ExecutionException e) { throw e.getCause() instanceof RuntimeException r ? r : new IllegalStateException(e.getCause()); }
        catch (InterruptedException | TimeoutException e) { throw new IllegalStateException("The hotkey " + hk.text() + " could not be registered: " + e); }
        return () -> User32.INSTANCE.PostThreadMessage(tid, WM_QUIT, new WinDef.WPARAM(0), new WinDef.LPARAM(0));
    }
}
