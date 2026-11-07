package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.ActionException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static com.oneofficeautomation.packages.RulesPackage.matches;
import static org.junit.jupiter.api.Assertions.*;

/** G48: the conditions a decision table's cells hold, and CSV as Excel writes it. */
class RulesPackageTest {
    @Test void conditions() {
        assertTrue(matches("", "x", 2, "in:a"));
        assertTrue(matches("-", null, 2, "in:a"));
        assertTrue(matches("EU", "eu", 2, "in:a"));
        assertTrue(matches("1000", new BigDecimal("1000.00"), 2, "in:a"));
        assertTrue(matches("<1000", 999, 2, "in:a"));
        assertFalse(matches("<1000", 1000, 2, "in:a"));
        assertTrue(matches(">=1000", "1,000", 2, "in:a"), "1,000 as people type it");
        assertTrue(matches("!=EU", "US", 2, "in:a"));
        assertTrue(matches("10..20", 20, 2, "in:a"));
        assertFalse(matches("[10..20)", 20, 2, "in:a"));
        assertFalse(matches("(10..20]", 10, 2, "in:a"));
        assertTrue(matches("EU, UK, 7", 7, 2, "in:a"));
        assertTrue(matches("not EU, UK", "US", 2, "in:a"));
        assertFalse(matches("not EU, UK", "uk", 2, "in:a"));
        assertEquals("RULES.NOT_A_NUMBER", assertThrows(ActionException.class, () -> matches("<10", "abc", 3, "in:amount")).code());
        assertEquals("RULES.BAD_CONDITION", assertThrows(ActionException.class, () -> matches("<abc", "x", 3, "in:amount")).code());
    }

    @Test void csvAsExcelSavesIt() {
        assertEquals(List.of(List.of("in:a", "out:b"), List.of("x; y", "say \"hi\"")), RulesPackage.csv("﻿in:a;out:b\r\n\"x; y\";\"say \"\"hi\"\"\"\r\n"));
        Map<String, Object> r = RulesPackage.decide(List.of("in:a", "out:b"), List.of(new ArrayList<>(List.of(">1", "true"))), Map.of("a", 2), "first", null, false);
        assertEquals(Map.of("b", true), r.get("outputs"));
        assertEquals("RULES.MISSING_INPUT", assertThrows(ActionException.class, () -> RulesPackage.decide(List.of("in:a", "out:b"), List.of(), Map.of(), "first", null, false)).code());
    }
}
