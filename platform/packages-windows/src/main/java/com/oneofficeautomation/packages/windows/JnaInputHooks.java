package com.oneofficeautomation.packages.windows;

import com.oneofficeautomation.packages.ui.InputTriggers;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Win32 low-level keyboard and mouse hooks for key-press and click triggers. Each trigger gets one hook on a thread of
 * its own that runs the message loop the hook needs; the hook callback only compares and hands off, then passes the
 * input on unchanged (nothing is blocked or swallowed). Work that takes time (reading the window, asking UI Automation
 * for the element) runs on a separate worker thread. Closing the handle unhooks and ends both threads.
 *
 * <p>Privacy: the keyboard callback looks at one field, the virtual-key code, and compares it with the one configured
 * key; any other key returns at once and nothing about it is kept. Input injected by programs (flag set by Windows) is
 * ignored, so an agent's own clicks and typing never fire these triggers.
 */
final class JnaInputHooks implements InputTriggers.Native {
    private static final int WH_KEYBOARD_LL = 13, WH_MOUSE_LL = 14, WM_QUIT = 0x0012;
    private static final int WM_KEYDOWN = 0x0100, WM_KEYUP = 0x0101, WM_SYSKEYDOWN = 0x0104, WM_SYSKEYUP = 0x0105;
    private static final int WM_LBUTTONDOWN = 0x0201, WM_RBUTTONDOWN = 0x0204, WM_MBUTTONDOWN = 0x0207;
    private static final int LLKHF_INJECTED = 0x10, LLMHF_INJECTED = 0x1;
    private static final int VK_SHIFT = 0x10, VK_CONTROL = 0x11, VK_MENU = 0x12, VK_LWIN = 0x5B, VK_RWIN = 0x5C, GA_ROOT = 2;

    /** WindowFromPoint, which jna-platform's User32 lacks (the POINT goes by value). */
    interface Point extends com.sun.jna.win32.StdCallLibrary {
        Point I = Native.load("user32", Point.class);
        WinDef.HWND WindowFromPoint(WinDef.POINT.ByValue point);
    }

    private static boolean held(int vk) { return (User32.INSTANCE.GetAsyncKeyState(vk) & 0x8000) != 0; }

    static int heldModifiers() {
        int m = 0;
        if (held(VK_CONTROL)) m |= InputTriggers.CTRL;
        if (held(VK_MENU)) m |= InputTriggers.ALT;
        if (held(VK_SHIFT)) m |= InputTriggers.SHIFT;
        if (held(VK_LWIN) || held(VK_RWIN)) m |= InputTriggers.WIN;
        return m;
    }

    static InputTriggers.Window window(WinDef.HWND hwnd) {
        if (hwnd == null) return new InputTriggers.Window("", "");
        WinDef.HWND root = User32.INSTANCE.GetAncestor(hwnd, GA_ROOT);
        if (root != null) hwnd = root;
        char[] buf = new char[512];
        User32.INSTANCE.GetWindowText(hwnd, buf, buf.length);
        String title = Native.toString(buf);
        return new InputTriggers.Window(title, new WindowPackage.Win(hwnd, title).processName());
    }

    @Override public AutoCloseable onKeyChord(InputTriggers.KeyChord chord, Consumer<InputTriggers.Window> onPress) {
        ExecutorService worker = Executors.newSingleThreadExecutor(r -> daemon(r, "ooa-key-trigger-" + chord.text()));
        boolean[] down = {false};
        WinUser.LowLevelKeyboardProc proc = (nCode, wParam, info) -> {
            if (nCode >= 0 && info.vkCode == chord.vk() && (info.flags & LLKHF_INJECTED) == 0) {
                int msg = wParam.intValue();
                if (msg == WM_KEYDOWN || msg == WM_SYSKEYDOWN) {
                    if (!down[0]) {   // key repeat while held is not a new press
                        down[0] = true;
                        if (chord.matches(info.vkCode, heldModifiers())) {
                            WinDef.HWND fg = User32.INSTANCE.GetForegroundWindow();
                            worker.execute(() -> onPress.accept(window(fg)));
                        }
                    }
                } else if (msg == WM_KEYUP || msg == WM_SYSKEYUP) down[0] = false;
            }
            return User32.INSTANCE.CallNextHookEx(null, nCode, wParam, new WinDef.LPARAM(Pointer.nativeValue(info.getPointer())));
        };
        return hook(WH_KEYBOARD_LL, proc, "ooa-key-hook-" + chord.text(), worker);
    }

    @Override public AutoCloseable onMouseDown(Consumer<InputTriggers.Click> onClick) {
        ExecutorService worker = Executors.newSingleThreadExecutor(r -> daemon(r, "ooa-click-trigger"));
        WinUser.LowLevelMouseProc proc = (nCode, wParam, info) -> {
            if (nCode >= 0) {
                int msg = wParam.intValue();
                String button = msg == WM_LBUTTONDOWN ? "left" : msg == WM_RBUTTONDOWN ? "right" : msg == WM_MBUTTONDOWN ? "middle" : null;
                if (button != null && (info.flags & LLMHF_INJECTED) == 0) {
                    int x = info.pt.x, y = info.pt.y;
                    worker.execute(() -> onClick.accept(new InputTriggers.Click(button, x, y, window(Point.I.WindowFromPoint(new WinDef.POINT.ByValue(x, y))))));
                }
            }
            return User32.INSTANCE.CallNextHookEx(null, nCode, wParam, new WinDef.LPARAM(Pointer.nativeValue(info.getPointer())));
        };
        return hook(WH_MOUSE_LL, proc, "ooa-click-hook", worker);
    }

    private static Thread daemon(Runnable r, String name) { Thread t = new Thread(r, name); t.setDaemon(true); return t; }

    /** Installs the hook on its own thread, which runs the message loop until WM_QUIT, then unhooks. */
    private static AutoCloseable hook(int type, WinUser.HOOKPROC proc, String name, ExecutorService worker) {
        keep.add(proc);
        CompletableFuture<Integer> ready = new CompletableFuture<>();
        Thread t = new Thread(() -> {
            WinUser.HHOOK h = User32.INSTANCE.SetWindowsHookEx(type, proc, Kernel32.INSTANCE.GetModuleHandle(null), 0);
            if (h == null) { keep.remove(proc); ready.completeExceptionally(new IllegalStateException("Windows refused the input hook (error " + Kernel32.INSTANCE.GetLastError() + ")")); return; }
            ready.complete(Kernel32.INSTANCE.GetCurrentThreadId());
            WinUser.MSG msg = new WinUser.MSG();
            try { while (User32.INSTANCE.GetMessage(msg, null, 0, 0) > 0) { /* the hook is called from inside GetMessage */ } }
            finally { User32.INSTANCE.UnhookWindowsHookEx(h); worker.shutdownNow(); keep.remove(proc); }
        }, name);
        t.setDaemon(true);
        t.start();
        int tid;
        try { tid = ready.get(5, TimeUnit.SECONDS); }
        catch (ExecutionException e) { worker.shutdownNow(); keep.remove(proc); throw e.getCause() instanceof RuntimeException r ? r : new IllegalStateException(e.getCause()); }
        catch (InterruptedException | TimeoutException e) { worker.shutdownNow(); throw new IllegalStateException("The input hook could not be installed: " + e); }
        return () -> { User32.INSTANCE.PostThreadMessage(tid, WM_QUIT, new WinDef.WPARAM(0), new WinDef.LPARAM(0)); };
    }

    /** Hook callbacks stay reachable while hooked (JNA frees a callback that is garbage-collected). */
    private static final Set<Object> keep = ConcurrentHashMap.newKeySet();

    /** UI Automation through the helper process, asked only for the element's name, type and ids (op element_at). */
    @Override public InputTriggers.ElementLookup openElementLookup() {
        UiaBridge bridge = UiaBridge.start();
        return new InputTriggers.ElementLookup() {
            @SuppressWarnings("unchecked")
            public Map<String, String> at(int x, int y) {
                Object r = bridge.call("element_at", Map.of("x", x, "y", y), 5_000, null);
                Map<String, String> out = new HashMap<>();
                if (r instanceof Map<?, ?> m) m.forEach((k, v) -> out.put(String.valueOf(k), v == null ? "" : String.valueOf(v)));
                return out;
            }
            public void close() { bridge.close(); }
        };
    }
}
