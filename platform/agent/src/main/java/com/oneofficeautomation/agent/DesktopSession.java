package com.oneofficeautomation.agent;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import java.awt.GraphicsEnvironment;
import java.util.*;

/**
 * The runner's screen: unlocked (someone is signed in and the desktop takes input), locked, or no_desktop (a Windows
 * service in session 0, or a machine without a display). After an unattended run the runner can lock the screen or sign
 * the user out (Command Center: Runners > Update runners > After unattended runs).
 */
final class DesktopSession {
    private DesktopSession() { }

    static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    interface User32 extends StdCallLibrary {
        User32 I = WINDOWS ? Native.load("user32", User32.class, W32APIOptions.DEFAULT_OPTIONS) : null;
        Pointer OpenInputDesktop(int flags, boolean inherit, int access);
        boolean SwitchDesktop(Pointer desktop);
        boolean CloseDesktop(Pointer desktop);
        boolean LockWorkStation();
        boolean ExitWindowsEx(int flags, int reason);
    }

    private static final int DESKTOP_SWITCHDESKTOP = 0x0100, EWX_LOGOFF = 0, SHTDN_REASON_FLAG_PLANNED = 0x80000000;

    /** unlocked, locked or no_desktop. */
    static String state() {
        if (GraphicsEnvironment.isHeadless()) return "no_desktop";
        if (!WINDOWS) return "unlocked";
        try {
            IntByReference session = new IntByReference();
            if (Kernel32.INSTANCE.ProcessIdToSessionId(Kernel32.INSTANCE.GetCurrentProcessId(), session) && session.getValue() == 0) return "no_desktop";
            // the input desktop cannot be opened, or not switched to, while the lock screen (or a UAC prompt) has it
            Pointer d = User32.I.OpenInputDesktop(0, false, DESKTOP_SWITCHDESKTOP);
            if (d == null) return "locked";
            try { return User32.I.SwitchDesktop(d) ? "unlocked" : "locked"; } finally { User32.I.CloseDesktop(d); }
        } catch (Throwable e) { return "unlocked"; }   // cannot tell: do not stop runs over it
    }

    /** True when the agent uses the screen (keyboard, mouse, windows, images, desktop dialogs): it needs an unlocked desktop. */
    @SuppressWarnings("unchecked")
    static boolean needsDesktop(Map<String, Object> flow) {
        return flow != null && flow.get("requires") instanceof Map<?, ?> req && req.get("capabilities") instanceof List<?> caps && caps.contains("desktop");
    }

    /** Why this job cannot start on the screen as it is now, or null. Headless runs never need it. */
    static String problem(String mode, Map<String, Object> flow, String state) {
        if (mode.equals("headless") || !needsDesktop(flow)) return null;
        return switch (state) {
            case "locked" -> "The runner's screen is locked, and this agent uses the screen";
            case "no_desktop" -> "This runner has no desktop (it runs as a Windows service), and this agent uses the screen";
            default -> null;
        };
    }

    /** After an unattended run: nothing, lock or sign_out. Returns what was done, for the runner's log. */
    static String afterRun(String action) {
        if (!WINDOWS || action == null || action.equals("nothing") || !state().equals("unlocked")) return null;
        try {
            if (action.equals("lock")) return User32.I.LockWorkStation() ? "locked the screen" : null;
            if (action.equals("sign_out")) return User32.I.ExitWindowsEx(EWX_LOGOFF, SHTDN_REASON_FLAG_PLANNED) ? "signing out" : null;
        } catch (Throwable ignored) { }
        return null;
    }
}
