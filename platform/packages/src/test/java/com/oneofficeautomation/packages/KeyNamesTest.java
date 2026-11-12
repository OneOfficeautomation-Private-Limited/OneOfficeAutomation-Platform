package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.ActionException;
import org.junit.jupiter.api.Test;

import java.awt.event.KeyEvent;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** K1: one key, many spellings; every command's own names keep working. */
class KeyNamesTest {
    @Test void desktopKeysTakeOtherSpellings() {
        assertEquals(List.of(KeyEvent.VK_PAGE_UP), DesktopPackage.parseKeys("PAGE_UP"));
        assertEquals(List.of(KeyEvent.VK_PAGE_UP), DesktopPackage.parseKeys("PageUp"));
        assertEquals(List.of(KeyEvent.VK_PAGE_DOWN), DesktopPackage.parseKeys("pgdn"));
        assertEquals(List.of(KeyEvent.VK_CONTROL, KeyEvent.VK_ALT, KeyEvent.VK_DELETE), DesktopPackage.parseKeys("Control+Alt+Del"));
        assertEquals(List.of(KeyEvent.VK_ENTER), DesktopPackage.parseKeys("Return"));
        assertEquals(List.of(KeyEvent.VK_CAPS_LOCK), DesktopPackage.parseKeys("Caps Lock"));
        assertEquals("DESKTOP.BAD_KEY", assertThrows(ActionException.class, () -> DesktopPackage.parseKeys("CTRL+WIBBLE")).code());
    }

    @Test void terminalKeysTakeOtherSpellingsAndAaNames() {
        assertEquals("Enter", TerminalPackage.keyAction("KEY_ENTER", false));
        assertEquals("Tab", TerminalPackage.keyAction("NEXTFIELD", false));
        assertEquals("Delete", TerminalPackage.keyAction("Del", false));
        assertEquals("PF(5)", TerminalPackage.keyAction("F5", false), "F5 means PF5 on 3270");
        assertEquals("PF(3)", TerminalPackage.keyAction("pf3", false));
        assertEquals("PageUp", TerminalPackage.keyAction("Page Up", true));
        assertEquals("PageDown", TerminalPackage.keyAction("PGDN", true));
        assertEquals("Escape", TerminalPackage.keyAction("Esc", true));
        assertEquals("Ctrl(C)", TerminalPackage.keyAction("Ctrl+C", true), "combinations are left as written");
        assertEquals("PageUp", Tn5250Session.keyAction("PAGE_UP"));
        assertEquals("FieldExit", Tn5250Session.keyAction("Field Exit"));
        assertEquals("TERMINAL.BAD_KEY", assertThrows(ActionException.class, () -> TerminalPackage.keyAction("Wibble", false)).code());
    }
}
