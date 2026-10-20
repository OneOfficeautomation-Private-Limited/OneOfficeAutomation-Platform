package com.oneofficeautomation.packages.windows;

import com.oneofficeautomation.packages.ui.SelfHealing;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Hotkey parsing and the window self-healing order; no desktop needed. */
class HotkeyComboTest {
    @Test void parsesModifiersAndOneKey() {
        HotkeyCombo h = HotkeyCombo.parse("ctrl + alt + F9");
        assertEquals(HotkeyCombo.MOD_CONTROL | HotkeyCombo.MOD_ALT, h.modifiers());
        assertEquals(0x78, h.vk());
        assertEquals("CTRL+ALT+F9", h.text());
        assertEquals('S', HotkeyCombo.parse("WIN+SHIFT+S").vk());
        assertEquals(0x7B, HotkeyCombo.parse("F12").vk(), "F-keys may be used alone");
    }

    @Test void rejectsUnclearCombinations() {
        assertThrows(IllegalArgumentException.class, () -> HotkeyCombo.parse("CTRL+ALT"));
        assertThrows(IllegalArgumentException.class, () -> HotkeyCombo.parse("CTRL+A+B"));
        assertThrows(IllegalArgumentException.class, () -> HotkeyCombo.parse("A"));
        assertThrows(IllegalArgumentException.class, () -> HotkeyCombo.parse("CTRL+BANANA"));
    }

    @Test void windowFallbacksFollowTheirOrder() {
        Map<String, Object> alt = Map.of("process_name", "erp.exe", "class_name", "ErpMain", "index", 1, "title_pattern", "re:^ERP");
        List<SelfHealing.Candidate> chain = SelfHealing.chain(new SelfHealing.Candidate("title", "ERP 2024"), alt, WindowPackage.WINDOW_ORDER, WindowPackage::windowQuery);
        assertEquals(List.of("title", "title_pattern", "class_name", "process_name", "class_index"), chain.stream().map(SelfHealing.Candidate::strategy).toList());
        assertEquals("ErpMain#1", chain.get(4).query());
    }
}
