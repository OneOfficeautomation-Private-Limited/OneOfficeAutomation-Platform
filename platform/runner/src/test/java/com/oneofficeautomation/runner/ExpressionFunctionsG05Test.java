package com.oneofficeautomation.runner;

import com.oneofficeautomation.runner.expr.Expressions;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** guid, first, last, union, intersection, base64 and URI functions (gap G05); the same cases as reference/tests/test_expressions.py. */
class ExpressionFunctionsG05Test {
    private static Object eval(String src) { return eval(src, Map.of()); }
    private static Object eval(String src, Map<String, Object> vars) { return Expressions.evaluate(src, vars::get); }

    private static List<BigDecimal> nums(Object o) {
        List<BigDecimal> out = new ArrayList<>();
        for (Object x : (List<?>) o) out.add(((BigDecimal) x).stripTrailingZeros());
        return out;
    }

    @Test void firstAndLast() {
        assertEquals(0, BigDecimal.ONE.compareTo((BigDecimal) eval("first([1, 2, 3])")));
        assertEquals(0, BigDecimal.valueOf(3).compareTo((BigDecimal) eval("last([1, 2, 3])")));
        assertEquals("A", eval("first('Acme')"));
        assertEquals("e", eval("last('Acme')"));
        assertNull(eval("last([])"));
        assertNull(eval("first('')"));
        Map<String, Object> table = Map.of("columns", List.of("k"), "rows", List.of(List.of("x"), List.of("y")));
        assertEquals(List.of("y"), eval("last($t)", Map.of("t", table)), "a Data Table gives its rows");
    }

    @Test void unionAndIntersectionKeepOrderWithoutRepeats() {
        assertEquals(List.of(BigDecimal.ONE, BigDecimal.valueOf(2), BigDecimal.valueOf(3)), nums(eval("union([1, 2], [2, 3])")));
        assertEquals(List.of(BigDecimal.valueOf(2)), nums(eval("intersection([1, 2, 2], [2, 3])")));
        assertEquals(List.of("a", "b"), eval("union(['a'], 'b')"), "a single value counts as a list of one");
        assertEquals(List.of(), eval("intersection([1], [2])"));
    }

    @Test void base64AndUri() {
        assertEquals("aMOpbGxv", eval("base64Encode('héllo')"));
        assertEquals("héllo", eval("base64Decode('aMOpbGxv')"));
        assertEquals("a%20b%26c%2F%C3%A9", eval("uriEncode('a b&c/é')"));
        assertEquals("it's(ok)!~", eval("uriEncode(\"it's(ok)!~\")"));
        assertEquals("a b&c+d", eval("uriDecode('a%20b%26c+d')"));
        OoaException bad = assertThrows(OoaException.class, () -> eval("base64Decode('***')"));
        assertEquals("EXPR.INVALID", bad.code());
    }

    @Test void guidIsNewEachTime() {
        String a = (String) eval("guid()"), b = (String) eval("guid()");
        assertTrue(a.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"), a);
        assertNotEquals(a, b);
        assertThrows(OoaException.class, () -> eval("guid(1)"));
    }
}
