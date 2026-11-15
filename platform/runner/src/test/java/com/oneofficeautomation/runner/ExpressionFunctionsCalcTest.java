package com.oneofficeautomation.runner;

import com.oneofficeautomation.runner.expr.Expressions;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Calculations and text made easy (16 Nov 2026): floor, ceiling, truncate, power, sqrt, mod, average, clamp, indexOf,
 * left, right, repeat, padLeft, padRight, titleCase and normalizeSpace. The same cases as reference/tests/test_expressions.py.
 */
class ExpressionFunctionsCalcTest {
    private static Object eval(String src) { return Expressions.evaluate(src, Map.<String, Object>of("prices", List.of(BigDecimal.valueOf(10), BigDecimal.valueOf(20), BigDecimal.valueOf(45)))::get); }

    private static void num(String expected, String src) {
        Object v = eval(src);
        assertInstanceOf(BigDecimal.class, v, src);
        assertEquals(expected, ((BigDecimal) v).toPlainString(), src);
    }

    @Test void roundingDownUpAndCut() {
        num("2", "floor(2.7)");
        num("-3", "floor(-2.1)");
        num("2.34", "floor(2.349, 2)");
        num("3", "ceiling(2.1)");
        num("2.35", "ceiling(2.341, 2)");
        num("-2", "truncate(-2.9)");
        num("2.34", "truncate(2.349, 2)");
    }

    @Test void powersRootsAndRemainders() {
        num("1024", "power(2, 10)");
        num("1", "power(7, 0)");
        num("2.25", "power(1.5, 2)");
        num("0.25", "power(2, -2)");
        num("1.4142135623730951", "power(2, 0.5)");
        num("4", "sqrt(16)");
        num("1.4142135623730951", "sqrt(2)");
        num("1", "mod(10, 3)");
        num("-1", "mod(-10, 3)");
        num("0.5", "mod(5.5, 1)");
        assertEquals("EXPR.INVALID", assertThrows(OoaException.class, () -> eval("sqrt(-1)")).code());
        assertEquals("EXPR.INVALID", assertThrows(OoaException.class, () -> eval("mod(1, 0)")).code());
        assertEquals("EXPR.INVALID", assertThrows(OoaException.class, () -> eval("power(-8, 0.5)")).code());
    }

    @Test void averagesAndLimits() {
        num("25", "average($prices)");
        num("2", "average(1, 2, 3)");
        num("0.3333333333", "average(0, 0, 1)");
        assertNull(eval("average([])"));
        num("10", "clamp(12, 0, 10)");
        num("0", "clamp(-4, 0, 10)");
        num("5.5", "clamp(5.5, 0, 10)");
    }

    @Test void textPieces() {
        num("6", "indexOf('Hello World', 'World')");
        num("-1", "indexOf('Hello', 'x')");
        num("5", "indexOf('a-b-c-d', '-', 4)");
        assertEquals("INV", eval("left('INV-2026', 3)"));
        assertEquals("2026", eval("right('INV-2026', 4)"));
        assertEquals("", eval("left('abc', -1)"));
        assertEquals("abc", eval("right('abc', 10)"));
        assertEquals("-=-=-=", eval("repeat('-=', 3)"));
        assertEquals("00042", eval("padLeft('42', 5, '0')"));
        assertEquals("ab   ", eval("padRight('ab', 5)"));
        assertEquals("abcdef", eval("padLeft('abcdef', 3)"));
        assertEquals("Hello Wide World", eval("titleCase('hello wIDE world')"));
        assertEquals("a b c", eval("normalizeSpace('  a \t b\n\n c  ')"));
        assertEquals("EXPR.INVALID", assertThrows(OoaException.class, () -> eval("repeat('abc', 400000)")).code());
    }
}
