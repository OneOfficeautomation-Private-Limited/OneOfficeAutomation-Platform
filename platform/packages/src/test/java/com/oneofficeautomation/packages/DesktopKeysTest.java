package com.oneofficeautomation.packages;

import org.junit.jupiter.api.Test;

import java.awt.event.KeyEvent;

import static org.junit.jupiter.api.Assertions.*;

/** Keys typed for each character when text or a credential is typed key by key. */
class DesktopKeysTest {
    @Test void lettersDigitsAndShiftedSymbols() {
        assertArrayEquals(new int[]{KeyEvent.VK_A, 0}, DesktopPackage.keyFor('a'));
        assertArrayEquals(new int[]{KeyEvent.VK_A, 1}, DesktopPackage.keyFor('A'));
        assertArrayEquals(new int[]{KeyEvent.VK_7, 0}, DesktopPackage.keyFor('7'));
        // symbols a password often holds are typed with Shift and the key that gives them on a US keyboard
        assertArrayEquals(new int[]{KeyEvent.VK_1, 1}, DesktopPackage.keyFor('!'));
        assertArrayEquals(new int[]{KeyEvent.VK_2, 1}, DesktopPackage.keyFor('@'));
        assertArrayEquals(new int[]{KeyEvent.VK_MINUS, 1}, DesktopPackage.keyFor('_'));
        assertArrayEquals(new int[]{KeyEvent.VK_SLASH, 1}, DesktopPackage.keyFor('?'));
        assertArrayEquals(new int[]{KeyEvent.VK_PERIOD, 0}, DesktopPackage.keyFor('.'));
    }
}
