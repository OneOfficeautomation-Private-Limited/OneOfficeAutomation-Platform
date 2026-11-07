package com.oneofficeautomation.packages.windows;

import com.oneofficeautomation.packages.ui.DesktopEvents;
import com.oneofficeautomation.packages.ui.InputTriggers;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The real Windows hooks of key-press and click triggers: they install and remove cleanly, input sent by a program is
 * ignored (only the person at the computer fires them), and UI Automation reads only name, type and ids at a point.
 */
@EnabledOnOs(OS.WINDOWS)
class JnaInputHooksLiveTest {
    static boolean hookThreadAlive(String prefix) { return Thread.getAllStackTraces().keySet().stream().anyMatch(t -> t.getName().startsWith(prefix) && t.isAlive()); }

    @Test void hooksInstallIgnoreProgramInputAndUnhook() throws Exception {
        InputTriggers.Native n = DesktopEvents.installed().flatMap(DesktopEvents::input).orElseThrow();
        List<Object> got = new CopyOnWriteArrayList<>();
        AutoCloseable key = n.onKeyChord(InputTriggers.KeyChord.parse("F24"), got::add);
        AutoCloseable mouse = n.onMouseDown(c -> { });   // installed and removed only: a real click by the person at the screen would count
        assertTrue(hookThreadAlive("ooa-key-hook-") && hookThreadAlive("ooa-click-hook"));
        // a program's key press (flagged as injected by Windows): F24 alone, nobody's shortcut
        for (int flags : new int[]{0, 2}) {   // down, then up (KEYEVENTF_KEYUP)
            com.sun.jna.platform.win32.WinUser.INPUT in = new com.sun.jna.platform.win32.WinUser.INPUT();
            in.type = new WinDef.DWORD(com.sun.jna.platform.win32.WinUser.INPUT.INPUT_KEYBOARD);
            in.input.setType("ki");
            in.input.ki.wVk = new WinDef.WORD(0x87);
            in.input.ki.dwFlags = new WinDef.DWORD(flags);
            WinDef.DWORD sent = User32.INSTANCE.SendInput(new WinDef.DWORD(1), (com.sun.jna.platform.win32.WinUser.INPUT[]) in.toArray(1), in.size()); assertEquals(1, sent.intValue(), "sent " + com.sun.jna.platform.win32.Kernel32.INSTANCE.GetLastError());
        }
        Thread.sleep(300);
        assertEquals(List.of(), got, "input sent by programs never fires a trigger");
        key.close();
        mouse.close();
        for (int i = 0; i < 50 && (hookThreadAlive("ooa-key-hook-") || hookThreadAlive("ooa-click-hook")); i++) Thread.sleep(20);
        assertFalse(hookThreadAlive("ooa-key-hook-") || hookThreadAlive("ooa-click-hook"), "closing unhooks and ends the hook threads");
    }

    @Test void elementLookupGivesNameTypeAndIdsOnly() {
        InputTriggers.Native n = DesktopEvents.installed().flatMap(DesktopEvents::input).orElseThrow();
        try (InputTriggers.ElementLookup l = n.openElementLookup()) {
            WinDef.RECT r = new WinDef.RECT();
            User32.INSTANCE.GetWindowRect(User32.INSTANCE.GetDesktopWindow(), r);
            Map<String, String> el = l.at(r.left + 5, r.bottom - 5);
            assertEquals(Set.of("Name", "ControlType", "AutomationId", "ClassName", "IsPassword"), el.keySet(), String.valueOf(el));
        }
    }
}
