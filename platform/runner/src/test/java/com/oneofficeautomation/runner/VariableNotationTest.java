package com.oneofficeautomation.runner;

import com.oneofficeautomation.runner.expr.Expressions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Variables written $name$ (as AA writes them, 14 Nov 2026 item F) next to the earlier $name and ${name}: a whole value
 * keeps its type, $name$ inside text is replaced by the value, a $ without a closing $ stays as typed, and calculations
 * read both forms.
 */
class VariableNotationTest {
    private static final Map<String, Object> VARS = Map.of(
            "count", new BigDecimal("3"), "customer", "Acme", "order", Map.of("id", "A-7", "lines", List.of("x", "y")),
            "price", new BigDecimal("2.5"), "qty", new BigDecimal("4"));
    private static Object resolve(Object v) { return Expressions.resolve(v, VARS::get); }

    @Test void aWholeValueKeepsItsTypeInEitherForm() {
        assertEquals(0, new BigDecimal("3").compareTo((BigDecimal) resolve("$count$")));
        assertEquals(0, new BigDecimal("3").compareTo((BigDecimal) resolve("$count")));
        assertEquals("A-7", resolve("$order.id$"));
        assertEquals("y", resolve("$order.lines[1]$"));
    }

    @Test void dollarNameDollarInsideTextIsReplaced() {
        assertEquals("Total 3 for Acme", resolve("Total $count$ for $customer$"));
        assertEquals("Order A-7", resolve("Order $order.id$"));
        assertEquals("Total 3 and 3", resolve("Total $count$ and ${count}"));
    }

    @Test void aDollarWithoutItsClosingDollarStaysAsTyped() {
        assertEquals("Costs US$ 5 or $5", resolve("Costs US$ 5 or $5"));
        assertEquals("Total $count", resolve("Total $count"));   // as before 14 Nov: only $count$ or ${count} is replaced in text
    }

    @Test void calculationsReadBothForms() {
        assertEquals(0, new BigDecimal("10").compareTo((BigDecimal) Expressions.evaluate("$price$ * $qty$", VARS::get)));
        assertEquals(0, new BigDecimal("10").compareTo((BigDecimal) Expressions.evaluate("$price * $qty$", VARS::get)));
        assertEquals(0, new BigDecimal("10").compareTo((BigDecimal) resolve(Map.of("expr", "$price$ * $qty$"))));
    }

    @Test void referencesAreFoundInEveryForm() {
        assertEquals(Set.of("count", "customer"), Expressions.references("Total $count$ for $customer$"));
        assertEquals(Set.of("count"), Expressions.references("$count$"));
        assertEquals(Set.of("price", "qty"), Expressions.references(Map.of("expr", "$price$ * $qty")));
        assertEquals(Set.of(), Expressions.references("Total $count"));
    }

    @Test void aNameIsReadFromEveryWayOfWritingIt() {
        assertEquals("total", Expressions.nameOf("$total$"));
        assertEquals("total", Expressions.nameOf("$total"));
        assertEquals("total", Expressions.nameOf(" total "));
    }
}
