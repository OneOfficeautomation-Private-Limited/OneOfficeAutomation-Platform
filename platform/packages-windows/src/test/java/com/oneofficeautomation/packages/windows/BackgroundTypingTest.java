package com.oneofficeautomation.packages.windows;

import com.oneofficeautomation.sdk.ActionException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Gap: typing into a window that is not in front. The key plan is checked everywhere; typing itself on Windows runners (r05 suite). */
class BackgroundTypingTest {
    @Test void keysInBracesAndPlainText() {
        List<Object> plan = WindowPackage.keyPlan("Invoice 4711{TAB}approved{ENTER 2}{{}x{}}\nend");
        assertEquals("Invoice 4711", plan.get(0));
        assertArrayEquals(new int[]{0x09, 1}, (int[]) plan.get(1));
        assertEquals("approved", plan.get(2));
        assertArrayEquals(new int[]{0x0D, 2}, (int[]) plan.get(3));
        assertEquals("{x}\rend", plan.get(4), "braces typed as text; a new line is Enter's character");
        assertEquals(List.of("a{b"), WindowPackage.keyPlan("a{b"), "an unclosed brace is text");
        assertEquals("WINDOW.BAD_KEY", assertThrows(ActionException.class, () -> WindowPackage.keyPlan("x{WIBBLE}")).code());
        // K1: the desktop's names and other spellings work in braces too
        List<Object> keys = WindowPackage.keyPlan("{PAGE_UP}{PAGE_DOWN 2}{Return}");
        assertArrayEquals(new int[]{0x21, 1}, (int[]) keys.get(0));
        assertArrayEquals(new int[]{0x22, 2}, (int[]) keys.get(1));
        assertArrayEquals(new int[]{0x0D, 1}, (int[]) keys.get(2));
        assertEquals("WINDOW.BAD_KEY", assertThrows(ActionException.class, () -> WindowPackage.keyPlan("{TAB many}")).code());
        assertArrayEquals(new int[]{0x7B, 200}, (int[]) WindowPackage.keyPlan("{f12 999}").get(0), "counts are capped");
    }

    @Test void keysThatTypeACharacter() {
        // sent key presses skip the program's TranslateMessage, so the step sends these characters itself (once)
        assertEquals('\r', WindowPackage.keyChar(0x0D));
        assertEquals('\t', WindowPackage.keyChar(0x09));
        assertEquals('\b', WindowPackage.keyChar(0x08));
        assertEquals(0x1B, WindowPackage.keyChar(0x1B));
        assertEquals(0, WindowPackage.keyChar(0x25), "arrow keys type nothing");
        assertEquals(0, WindowPackage.keyChar(0x7B), "F12 types nothing");
    }
}
