package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@OoaPackage(id = "ooa.number", name = "Number", version = "1.1.0", description = "Calculate, round, format and generate numbers with exact decimal arithmetic.")
public final class NumberPackage {
    private static final SecureRandom RANDOM = new SecureRandom();

    @Action(id = "round", name = "Round", category = "Number", idempotent = true, description = "Round a number to a number of decimal places.",
            panel = "value, decimals, mode")
    @Output(name = "result", type = OoaType.NUMBER, description = "Rounded number.")
    public Map<String, Object> round(ActionContext ctx,
            @Input(name = "value", type = OoaType.NUMBER, description = "Number to round.") BigDecimal value,
            @Input(name = "decimals", type = OoaType.NUMBER, required = false, defaultValue = "0", description = "Decimal places.") int decimals,
            @Input(name = "mode", type = OoaType.STRING, required = false, defaultValue = "\"half_up\"", enumValues = {"half_up", "half_even", "up", "down"},
                    description = "Rounding mode; half_even is banker's rounding.") String mode) {
        RoundingMode rm = switch (mode) { case "half_even" -> RoundingMode.HALF_EVEN; case "up" -> RoundingMode.UP; case "down" -> RoundingMode.DOWN; default -> RoundingMode.HALF_UP; };
        return Out.of("result", value.setScale(decimals, rm));
    }

    @Action(id = "random", name = "Random number", category = "Number", description = "Generate a random number between min and max (inclusive), whole or with decimals.",
            panel = "min, max, decimals")
    @Output(name = "result", type = OoaType.NUMBER, description = "Random number.")
    public Map<String, Object> random(ActionContext ctx,
            @Input(name = "min", type = OoaType.NUMBER, required = false, defaultValue = "0", description = "Smallest value.") BigDecimal min,
            @Input(name = "max", type = OoaType.NUMBER, required = false, defaultValue = "100", description = "Largest value.") BigDecimal max,
            @Input(name = "decimals", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0, max = 15, description = "Decimal places of the result; 0 gives a whole number.") int decimals) {
        BigDecimal lo = min == null ? BigDecimal.ZERO : min, hi = max == null ? BigDecimal.valueOf(100) : max;
        if (hi.compareTo(lo) < 0) throw new ActionException("NUMBER.BAD_RANGE", "max (" + hi.toPlainString() + ") is smaller than min (" + lo.toPlainString() + ")", "Swap min and max.");
        if (decimals < 0 || decimals > 15) throw new ActionException("NUMBER.BAD_DECIMALS", "Decimals must be between 0 and 15, got " + decimals, "Use 0 for whole numbers.");
        BigDecimal scale = BigDecimal.TEN.pow(decimals);
        java.math.BigInteger a = lo.multiply(scale).setScale(0, RoundingMode.CEILING).toBigIntegerExact();
        java.math.BigInteger b = hi.multiply(scale).setScale(0, RoundingMode.FLOOR).toBigIntegerExact();
        if (b.compareTo(a) < 0) throw new ActionException("NUMBER.BAD_RANGE", "No number with " + decimals + " decimals lies between min and max", "Widen the range or allow more decimals.");
        java.math.BigInteger span = b.subtract(a).add(java.math.BigInteger.ONE), pick;
        do { pick = new java.math.BigInteger(span.bitLength(), RANDOM); } while (pick.compareTo(span) >= 0);
        return Out.of("result", new BigDecimal(a.add(pick)).divide(scale).setScale(decimals, RoundingMode.UNNECESSARY));
    }

    @Action(id = "format", name = "Format number", category = "Number", idempotent = true,
            description = "Format a number as text with thousands separators and fixed decimals, e.g. 1,234.50.",
            panel = "value, decimals, thousands_separator | locale")
    @Output(name = "text", type = OoaType.STRING, description = "Formatted text.")
    public Map<String, Object> format(ActionContext ctx,
            @Input(name = "value", type = OoaType.NUMBER, description = "Number to format.") BigDecimal value,
            @Input(name = "decimals", type = OoaType.NUMBER, required = false, defaultValue = "2", min = 0, max = 20, description = "Decimal places.") int decimals,
            @Input(name = "thousands_separator", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Group thousands.") boolean group,
            @Input(name = "locale", type = OoaType.STRING, required = false, defaultValue = "\"en-US\"", description = "Locale for separators, e.g. en-US, de-DE, en-IN.") String locale) {
        DecimalFormat f = new DecimalFormat((group ? "#,##0" : "0") + (decimals > 0 ? "." + "0".repeat(decimals) : ""),
                DecimalFormatSymbols.getInstance(Locale.forLanguageTag(locale)));
        f.setRoundingMode(RoundingMode.HALF_UP);
        Locale loc = Locale.forLanguageTag(locale);
        return Out.of("text", group ? southAsianGrouping(f.format(value), loc) : f.format(value));
    }

    /** Countries that group digits as 12,34,56,789 (lakh and crore); Java's formats group them in threes. */
    private static final java.util.Set<String> LAKH = java.util.Set.of("IN", "PK", "BD", "NP");

    /** Regroups the whole-number part of an already formatted number for South Asian locales; other locales unchanged. */
    static String southAsianGrouping(String formatted, Locale locale) {
        if (!LAKH.contains(locale.getCountry())) return formatted;
        DecimalFormatSymbols sym = DecimalFormatSymbols.getInstance(locale);
        char g = sym.getGroupingSeparator(), d = sym.getDecimalSeparator();
        int dot = formatted.indexOf(d);
        String whole = dot < 0 ? formatted : formatted.substring(0, dot), rest = dot < 0 ? "" : formatted.substring(dot);
        String sign = whole.startsWith("-") ? "-" : "";
        String digits = whole.substring(sign.length()).replace(String.valueOf(g), "");
        if (digits.length() <= 3 || !digits.chars().allMatch(Character::isDigit)) return formatted;
        StringBuilder sb = new StringBuilder(digits.substring(digits.length() - 3));
        for (int i = digits.length() - 3; i > 0; i -= 2) sb.insert(0, digits.substring(Math.max(0, i - 2), i) + g);
        return sign + sb + rest;
    }

    @Action(id = "increment", name = "Increment", category = "Number", idempotent = true, description = "Add a step (1 by default) to a number.",
            panel = "value, step")
    @Output(name = "result", type = OoaType.NUMBER, description = "Number plus the step.")
    public Map<String, Object> increment(ActionContext ctx,
            @Input(name = "value", type = OoaType.NUMBER, description = "Starting number.") BigDecimal value,
            @Input(name = "step", type = OoaType.NUMBER, required = false, defaultValue = "1", description = "Amount to add.") BigDecimal step) {
        return Out.of("result", value.add(step == null ? BigDecimal.ONE : step));
    }

    @Action(id = "decrement", name = "Decrement", category = "Number", idempotent = true, description = "Subtract a step (1 by default) from a number.",
            panel = "value, step")
    @Output(name = "result", type = OoaType.NUMBER, description = "Number minus the step.")
    public Map<String, Object> decrement(ActionContext ctx,
            @Input(name = "value", type = OoaType.NUMBER, description = "Starting number.") BigDecimal value,
            @Input(name = "step", type = OoaType.NUMBER, required = false, defaultValue = "1", description = "Amount to subtract.") BigDecimal step) {
        return Out.of("result", value.subtract(step == null ? BigDecimal.ONE : step));
    }

    @Action(id = "to_text", name = "Number to text", category = "Number", idempotent = true,
            description = "Write a number as plain text, with fixed decimals or a pattern such as 0000 or #,##0.00.",
            panel = "value, decimals | pattern, rounding")
    @Output(name = "text", type = OoaType.STRING, description = "The number as text.")
    public Map<String, Object> toText(ActionContext ctx,
            @Input(name = "value", type = OoaType.NUMBER, description = "Number to write.") BigDecimal value,
            @Input(name = "decimals", type = OoaType.NUMBER, required = false, min = 0, max = 20, description = "Fixed decimal places (rounded half up).") Integer decimals,
            @Input(name = "pattern", type = OoaType.STRING, required = false, description = "Pattern with 0 for required digits and # for optional ones, e.g. 00000 or #,##0.00.") String pattern,
            @Input(name = "rounding", type = OoaType.STRING, required = false, defaultValue = "\"half_up\"", enumValues = {"half_up", "truncate"},
                    visibleWhen = "pattern=", description = "How extra decimals are dropped: rounded half up, or cut off (truncated).") String rounding) {
        RoundingMode rm = "truncate".equals(rounding) ? RoundingMode.DOWN : RoundingMode.HALF_UP;
        if (pattern != null && !pattern.isBlank()) {
            try {
                DecimalFormat f = new DecimalFormat(pattern, DecimalFormatSymbols.getInstance(Locale.ROOT));
                f.setRoundingMode(rm);
                return Out.of("text", f.format(value));
            } catch (IllegalArgumentException e) {
                throw new ActionException("NUMBER.BAD_PATTERN", "Invalid number pattern '" + pattern + "': " + e.getMessage(), "Use 0, #, comma and dot, e.g. #,##0.00.");
            }
        }
        if (decimals != null) {
            if (decimals < 0) throw new ActionException("NUMBER.BAD_DECIMALS", "Decimals cannot be negative (" + decimals + ")", "Use 0 or more.");
            return Out.of("text", value.setScale(decimals, rm).toPlainString());
        }
        return Out.of("text", com.oneofficeautomation.runner.Values.text(value));
    }

    // ---- Calculations made easy (16 Nov 2026): pick an operation, or write a formula ----

    /** Operations of Calculate that take two numbers (A and B). */
    static final java.util.Set<String> TWO = java.util.Set.of("add", "subtract", "multiply", "divide", "remainder", "power", "percent_of", "percentage_change");
    /** Operations of Calculate that take a list of numbers. */
    static final java.util.Set<String> MANY = java.util.Set.of("sum", "average", "minimum", "maximum");

    @Action(id = "calculate", name = "Calculate", category = "Number", idempotent = true,
            description = "Work out a number: pick an operation (add, divide, power, round up, average, percent of ...) or write a formula such as ($price$ * $qty$) * (1 + $tax$ / 100).",
            panel = "(How: Pick an operation = operation, a, b, values / Write a formula = formula), decimals")
    @Output(name = "result", type = OoaType.NUMBER, description = "The result of the calculation.")
    public Map<String, Object> calculate(ActionContext ctx,
            @Input(name = "operation", type = OoaType.STRING, required = false, defaultValue = "\"add\"", label = "Operation",
                    enumValues = {"add", "subtract", "multiply", "divide", "remainder", "power", "square_root", "absolute", "negate", "round", "round_up", "round_down",
                            "integer_part", "decimal_part", "minimum", "maximum", "sum", "average", "percent_of", "percentage_change"},
                    description = "What to work out. Percent of: A percent of B. Percentage change: from A to B, in percent. Round, round up and round down use Decimals.") String operation,
            @Input(name = "a", type = OoaType.NUMBER, required = false, label = "Number A", visibleWhen = "operation!=minimum|maximum|sum|average",
                    description = "The first number (the only one for square root, absolute, negate, round and the parts).") BigDecimal a,
            @Input(name = "b", type = OoaType.NUMBER, required = false, label = "Number B",
                    visibleWhen = "operation=add|subtract|multiply|divide|remainder|power|percent_of|percentage_change",
                    description = "The second number: what is added, subtracted, multiplied by, divided by, the power, or the whole for Percent of.") BigDecimal b,
            @Input(name = "values", type = OoaType.LIST, required = false, label = "Numbers", visibleWhen = "operation=minimum|maximum|sum|average",
                    description = "The numbers, as a List variable or [1, 2, 3]; empty items are left out.") List<Object> values,
            @Input(name = "formula", type = OoaType.ANY, required = false, label = "Formula",
                    description = "A calculation with variables, brackets and functions, e.g. ($price$ * $qty$) * (1 + $tax$ / 100) or round(average($prices$), 2).") Object formula,
            @Input(name = "decimals", type = OoaType.NUMBER, required = false, min = 0, max = 20, label = "Decimals",
                    description = "Round the result half up to this many decimals; empty keeps it as it is (Round, Round up and Round down: 0 when empty).") Integer decimals) {
        BigDecimal r;
        if (formula != null && !(formula instanceof String s && s.isBlank())) r = formulaValue(formula);
        else r = operate(operation == null ? "add" : operation, a, b, values, decimals);
        if (decimals != null && !java.util.Set.of("round", "round_up", "round_down").contains(operation)) r = r.setScale(decimals, RoundingMode.HALF_UP);
        return Out.of("result", r);
    }

    /** A formula Studio saved as {"expr": ...} arrives already worked out; typed text (numbers put in) is worked out here. */
    static BigDecimal formulaValue(Object formula) {
        Object v = formula;
        if (formula instanceof String text) {
            try { v = com.oneofficeautomation.runner.expr.Expressions.evaluate(text, name -> null); }
            catch (OoaException e) { throw new ActionException("NUMBER.BAD_FORMULA", "The formula '" + text + "' cannot be worked out: " + e.getMessage(), "Check the brackets, operators (+ - * / %) and function names; Help > Formulas lists the functions."); }
        }
        if (v == null) throw new ActionException("NUMBER.BAD_FORMULA", "The formula gave no value", "Check that its variables have values.");
        try { return com.oneofficeautomation.runner.Values.toNumber(v); }
        catch (OoaException e) { throw new ActionException("NUMBER.BAD_FORMULA", "The formula gave '" + com.oneofficeautomation.runner.Values.text(v) + "', which is not a number", "Make the formula give a number, e.g. toNumber($text$)."); }
    }

    static BigDecimal operate(String op, BigDecimal a, BigDecimal b, List<Object> values, Integer decimals) {
        if (MANY.contains(op)) {
            List<BigDecimal> nums = numbers(values, true);
            if (nums.isEmpty()) throw new ActionException("NUMBER.NO_VALUES", "There are no numbers to " + op.replace('_', ' '), "Give a list with at least one number.");
            return switch (op) {
                case "sum" -> nums.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
                case "average" -> com.oneofficeautomation.runner.expr.Functions.plain(nums.stream().reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(nums.size()), 10, RoundingMode.HALF_UP));
                case "minimum" -> nums.stream().min(java.util.Comparator.naturalOrder()).orElseThrow();
                default -> nums.stream().max(java.util.Comparator.naturalOrder()).orElseThrow();
            };
        }
        if (a == null) throw new ActionException("NUMBER.MISSING", "Number A is empty", "Give Number A.");
        if (TWO.contains(op) && b == null) throw new ActionException("NUMBER.MISSING", "Number B is empty", "Give Number B for " + op.replace('_', ' ') + ".");
        int d = decimals == null ? 0 : decimals;
        return switch (op) {
            case "add" -> a.add(b);
            case "subtract" -> a.subtract(b);
            case "multiply" -> a.multiply(b);
            case "divide" -> { if (b.signum() == 0) throw divideByZero(); yield com.oneofficeautomation.runner.expr.Functions.plain(a.divide(b, java.math.MathContext.DECIMAL128)); }
            case "remainder" -> { if (b.signum() == 0) throw divideByZero(); yield a.remainder(b); }
            case "power" -> com.oneofficeautomation.runner.expr.Functions.power(a, b);
            case "square_root" -> {
                if (a.signum() < 0) throw new ActionException("NUMBER.NEGATIVE", "A negative number (" + a.toPlainString() + ") has no square root", "Use Absolute first, or check the number.");
                yield com.oneofficeautomation.runner.expr.Functions.plain(BigDecimal.valueOf(Math.sqrt(a.doubleValue())));
            }
            case "absolute" -> a.abs();
            case "negate" -> a.negate();
            case "round" -> a.setScale(d, RoundingMode.HALF_UP);
            case "round_up" -> a.setScale(d, RoundingMode.CEILING);
            case "round_down" -> a.setScale(d, RoundingMode.FLOOR);
            case "integer_part" -> a.setScale(0, RoundingMode.DOWN);
            case "decimal_part" -> a.subtract(a.setScale(0, RoundingMode.DOWN));
            case "percent_of" -> com.oneofficeautomation.runner.expr.Functions.plain(a.multiply(b).divide(BigDecimal.valueOf(100), java.math.MathContext.DECIMAL128));
            case "percentage_change" -> {
                if (a.signum() == 0) throw new ActionException("NUMBER.DIVIDE_BY_ZERO", "A change from 0 has no percentage", "Percentage change needs a starting number (A) other than 0.");
                yield com.oneofficeautomation.runner.expr.Functions.plain(b.subtract(a).multiply(BigDecimal.valueOf(100)).divide(a.abs(), java.math.MathContext.DECIMAL128));
            }
            default -> throw new ActionException("NUMBER.BAD_OPERATION", "Unknown operation '" + op + "'", "Pick an operation from the list.");
        };
    }

    private static ActionException divideByZero() { return new ActionException("NUMBER.DIVIDE_BY_ZERO", "Division by zero", "Make Number B something other than 0, or check it first with an If."); }

    /** The numbers of a list; empty items are left out, and text that is not a number fails (or is left out). */
    static List<BigDecimal> numbers(List<Object> values, boolean strict) {
        List<BigDecimal> out = new java.util.ArrayList<>();
        if (values == null) return out;
        for (Object v : values) {
            if (v == null || (v instanceof String s && s.isBlank())) continue;
            try { out.add(com.oneofficeautomation.runner.Values.toNumber(v)); }
            catch (OoaException e) {
                if (strict) throw new ActionException("NUMBER.NOT_A_NUMBER", "'" + com.oneofficeautomation.runner.Values.text(v) + "' in the list is not a number", "Clean the list, or tick Skip text that is not a number.");
            }
        }
        return out;
    }

    @Action(id = "statistics", name = "List statistics", category = "Number", idempotent = true,
            description = "Count, sum, average, minimum, maximum and median of a list of numbers, in one step.",
            panel = "values | skip_non_numbers")
    @Output(name = "count", type = OoaType.NUMBER, description = "How many numbers.")
    @Output(name = "sum", type = OoaType.NUMBER, description = "Their total.")
    @Output(name = "average", type = OoaType.NUMBER, description = "Their average (up to 10 decimals).")
    @Output(name = "minimum", type = OoaType.NUMBER, description = "The smallest.")
    @Output(name = "maximum", type = OoaType.NUMBER, description = "The largest.")
    @Output(name = "median", type = OoaType.NUMBER, description = "The middle one (the average of the two middle ones for an even count).")
    public Map<String, Object> statistics(ActionContext ctx,
            @Input(name = "values", type = OoaType.LIST, label = "Numbers", description = "The numbers, as a List variable (a table column from Column to list) or [1, 2, 3].") List<Object> values,
            @Input(name = "skip_non_numbers", type = OoaType.BOOLEAN, required = false, defaultValue = "false", label = "Skip text that is not a number",
                    description = "Leave out items that are not numbers instead of failing.") boolean skip) {
        List<BigDecimal> nums = numbers(values, !skip);
        if (nums.isEmpty()) return Out.of("count", BigDecimal.ZERO, "sum", BigDecimal.ZERO, "average", null, "minimum", null, "maximum", null, "median", null);
        List<BigDecimal> sorted = new java.util.ArrayList<>(nums);
        sorted.sort(java.util.Comparator.naturalOrder());
        BigDecimal sum = nums.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        int n = sorted.size();
        BigDecimal median = n % 2 == 1 ? sorted.get(n / 2)
                : com.oneofficeautomation.runner.expr.Functions.plain(sorted.get(n / 2 - 1).add(sorted.get(n / 2)).divide(BigDecimal.valueOf(2), 10, RoundingMode.HALF_UP));
        return Out.of("count", BigDecimal.valueOf(n), "sum", sum,
                "average", com.oneofficeautomation.runner.expr.Functions.plain(sum.divide(BigDecimal.valueOf(n), 10, RoundingMode.HALF_UP)),
                "minimum", sorted.get(0), "maximum", sorted.get(n - 1), "median", median);
    }

    @Action(id = "round_to_step", name = "Round to a step", category = "Number", idempotent = true,
            description = "Round a number to the nearest step, e.g. prices to 0.05 or 0.99 endings, times to 15 minutes, quantities to a pack size.",
            panel = "value, step, direction")
    @Output(name = "result", type = OoaType.NUMBER, description = "The rounded number.")
    public Map<String, Object> roundToStep(ActionContext ctx,
            @Input(name = "value", type = OoaType.NUMBER, description = "Number to round.") BigDecimal value,
            @Input(name = "step", type = OoaType.NUMBER, defaultValue = "0.05", description = "The step, e.g. 0.05, 0.5, 10 or 12 (a pack of twelve).") BigDecimal step,
            @Input(name = "direction", type = OoaType.STRING, required = false, defaultValue = "\"nearest\"", enumValues = {"nearest", "up", "down"},
                    description = "To the nearest step (halves go up), always up, or always down.") String direction) {
        if (step == null || step.signum() <= 0) throw new ActionException("NUMBER.BAD_STEP", "The step must be more than 0", "Give a step such as 0.05 or 10.");
        RoundingMode rm = switch (direction == null ? "nearest" : direction) { case "up" -> RoundingMode.CEILING; case "down" -> RoundingMode.FLOOR; default -> RoundingMode.HALF_UP; };
        BigDecimal times = value.divide(step, 0, rm);
        return Out.of("result", times.multiply(step).setScale(Math.max(0, step.stripTrailingZeros().scale()), RoundingMode.UNNECESSARY));
    }

    @Action(id = "format_as", name = "Format as currency, percent or unit", category = "Number", idempotent = true,
            description = "Write a number as money (1,234.50 EUR in the locale's way), as a percentage (15%) or with a unit (12.5 kg).",
            panel = "value, style, currency, unit, locale | decimals, fraction, unit_before")
    @Output(name = "text", type = OoaType.STRING, description = "The formatted text.")
    public Map<String, Object> formatAs(ActionContext ctx,
            @Input(name = "value", type = OoaType.NUMBER, description = "Number to format.") BigDecimal value,
            @Input(name = "style", type = OoaType.STRING, defaultValue = "\"currency\"", enumValues = {"currency", "percent", "unit"}, description = "Money, percentage or a number with a unit.") String style,
            @Input(name = "currency", type = OoaType.STRING, required = false, defaultValue = "\"USD\"", visibleWhen = "style=currency", example = "EUR",
                    description = "Three-letter currency code, e.g. USD, EUR, GBP, INR, JPY.") String currency,
            @Input(name = "unit", type = OoaType.STRING, required = false, visibleWhen = "style=unit", example = "kg", description = "The unit, e.g. kg, hours, pcs, km/h.") String unit,
            @Input(name = "locale", type = OoaType.STRING, required = false, defaultValue = "\"en-US\"", description = "Locale for separators and symbol position, e.g. en-US, de-DE, en-IN, fr-FR.") String locale,
            @Input(name = "decimals", type = OoaType.NUMBER, required = false, min = 0, max = 10,
                    description = "Decimal places; empty: the currency's own (2 for EUR, 0 for JPY), 0 for percent, as given for units.") Integer decimals,
            @Input(name = "fraction", type = OoaType.BOOLEAN, required = false, defaultValue = "true", visibleWhen = "style=percent", label = "Value is a fraction (0.15 is 15%)",
                    description = "When ticked 0.15 shows as 15%; when not, 15 shows as 15%.") boolean fraction,
            @Input(name = "unit_before", type = OoaType.BOOLEAN, required = false, defaultValue = "false", visibleWhen = "style=unit", label = "Unit before the number",
                    description = "Write the unit first, e.g. Rs 120 instead of 120 Rs.") boolean unitBefore) {
        Locale loc = Locale.forLanguageTag(locale == null || locale.isBlank() ? "en-US" : locale);
        String s = style == null ? "currency" : style;
        switch (s) {
            case "percent" -> {
                java.text.NumberFormat f = java.text.NumberFormat.getPercentInstance(loc);
                int d = decimals == null ? 0 : decimals;
                f.setMinimumFractionDigits(d); f.setMaximumFractionDigits(d); f.setRoundingMode(RoundingMode.HALF_UP);
                return Out.of("text", spaces(f.format(fraction ? value : value.movePointLeft(2))));
            }
            case "unit" -> {
                DecimalFormat f = new DecimalFormat("#,##0" + (decimals != null && decimals > 0 ? "." + "0".repeat(decimals) : decimals == null ? ".##########" : ""), DecimalFormatSymbols.getInstance(loc));
                f.setRoundingMode(RoundingMode.HALF_UP);
                String num = southAsianGrouping(f.format(value), loc), u = unit == null ? "" : unit.strip();
                return Out.of("text", u.isEmpty() ? num : unitBefore ? u + " " + num : num + " " + u);
            }
            case "currency" -> {
                java.util.Currency cur;
                try { cur = java.util.Currency.getInstance((currency == null || currency.isBlank() ? "USD" : currency.strip()).toUpperCase(Locale.ROOT)); }
                catch (IllegalArgumentException e) { throw new ActionException("NUMBER.BAD_CURRENCY", "'" + currency + "' is not a currency code", "Use a three-letter ISO code such as USD, EUR, GBP or INR."); }
                java.text.NumberFormat f = java.text.NumberFormat.getCurrencyInstance(loc);
                f.setCurrency(cur);
                int d = decimals == null ? Math.max(0, cur.getDefaultFractionDigits()) : decimals;
                f.setMinimumFractionDigits(d); f.setMaximumFractionDigits(d); f.setRoundingMode(RoundingMode.HALF_UP);
                if (LAKH.contains(loc.getCountry()) && f instanceof DecimalFormat df) {
                    // the symbol stays where the locale puts it; only the digits are grouped as lakh and crore
                    DecimalFormat plain = new DecimalFormat("#,##0" + (d > 0 ? "." + "0".repeat(d) : ""), DecimalFormatSymbols.getInstance(loc));
                    plain.setRoundingMode(RoundingMode.HALF_UP);
                    boolean neg = value.signum() < 0;
                    return Out.of("text", spaces((neg ? df.getNegativePrefix() : df.getPositivePrefix()) + southAsianGrouping(plain.format(value.abs()), loc)
                            + (neg ? df.getNegativeSuffix() : df.getPositiveSuffix())));
                }
                return Out.of("text", spaces(f.format(value)));
            }
            default -> throw new ActionException("NUMBER.BAD_STYLE", "Unknown style '" + style + "'", "Use currency, percent or unit.");
        }
    }

    /** Locales put no-break spaces in money and percentages (1.234,50 €); ordinary spaces are safer to type, compare and save. */
    static String spaces(String s) { return s.replace('\u00a0', ' ').replace('\u202f', ' '); }

    private static final String[] ONES = {"zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
            "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen"};
    private static final String[] TENS = {"", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"};

    @Action(id = "to_words", name = "Number to words", category = "Number", idempotent = true,
            description = "Write a number in English words, for cheques and invoices: 1,234.50 as one thousand two hundred thirty-four dollars and fifty cents (lakh and crore for India).",
            panel = "value, style, major_unit, minor_unit | system, letter_case")
    @Output(name = "text", type = OoaType.STRING, description = "The number in words.")
    public Map<String, Object> toWords(ActionContext ctx,
            @Input(name = "value", type = OoaType.NUMBER, description = "Number to write in words.") BigDecimal value,
            @Input(name = "style", type = OoaType.STRING, required = false, defaultValue = "\"amount\"", enumValues = {"amount", "plain"},
                    description = "Amount: whole units and two-digit cents (dollars and cents); plain: the number as it is, decimals read digit by digit (point two five).") String style,
            @Input(name = "major_unit", type = OoaType.STRING, required = false, defaultValue = "\"dollars\"", visibleWhen = "style=amount", label = "Units",
                    description = "Name of the whole units, e.g. dollars, euros, rupees; empty for none.") String major,
            @Input(name = "minor_unit", type = OoaType.STRING, required = false, defaultValue = "\"cents\"", visibleWhen = "style=amount", label = "Hundredths",
                    description = "Name of the hundredths, e.g. cents, paise; empty leaves them out.") String minor,
            @Input(name = "system", type = OoaType.STRING, required = false, defaultValue = "\"international\"", enumValues = {"international", "indian"},
                    description = "International (thousand, million, billion) or Indian (thousand, lakh, crore).") String system,
            @Input(name = "letter_case", type = OoaType.STRING, required = false, defaultValue = "\"sentence\"", enumValues = {"sentence", "lower", "upper", "title"},
                    description = "One thousand ..., one thousand ..., ONE THOUSAND ... or One Thousand ....") String letterCase) {
        boolean indian = "indian".equals(system);
        boolean negative = value.signum() < 0;
        BigDecimal abs = value.abs();
        StringBuilder sb = new StringBuilder(negative ? "minus " : "");
        if ("plain".equals(style)) {
            BigDecimal whole = abs.setScale(0, RoundingMode.DOWN);
            sb.append(words(whole.toBigIntegerExact(), indian));
            String dec = abs.stripTrailingZeros().toPlainString();
            int dot = dec.indexOf('.');
            if (dot >= 0) { sb.append(" point"); for (char c : dec.substring(dot + 1).toCharArray()) sb.append(' ').append(ONES[c - '0']); }
        } else {
            BigDecimal r = abs.setScale(2, RoundingMode.HALF_UP);
            java.math.BigInteger whole = r.setScale(0, RoundingMode.DOWN).toBigIntegerExact();
            int cents = r.subtract(new BigDecimal(whole)).movePointRight(2).intValue();
            sb.append(words(whole, indian));
            if (major != null && !major.isBlank()) sb.append(' ').append(major.strip());
            if (cents > 0 && minor != null && !minor.isBlank()) sb.append(" and ").append(words(java.math.BigInteger.valueOf(cents), false)).append(' ').append(minor.strip());
        }
        String t = sb.toString();
        String cased = switch (letterCase == null ? "sentence" : letterCase) {
            case "upper" -> t.toUpperCase(Locale.ROOT);
            case "lower" -> t;
            case "title" -> {
                StringBuilder tc = new StringBuilder(t.length());
                boolean start = true;
                for (char c : t.toCharArray()) { tc.append(start ? Character.toUpperCase(c) : c); start = c == ' ' || c == '-'; }
                yield tc.toString();
            }
            default -> Character.toUpperCase(t.charAt(0)) + t.substring(1);
        };
        return Out.of("text", cased);
    }

    /** A whole number in English words: international (thousand, million ...) or Indian (thousand, lakh, crore) grouping. */
    static String words(java.math.BigInteger n, boolean indian) {
        if (n.signum() == 0) return "zero";
        if (n.bitLength() > 100) throw new ActionException("NUMBER.TOO_LARGE", "The number is too large to write in words", "Write numbers below 10^30 in words.");
        List<String> parts = new java.util.ArrayList<>();
        if (indian) {
            java.math.BigInteger crore = java.math.BigInteger.valueOf(10_000_000);
            java.math.BigInteger[] cr = n.divideAndRemainder(crore);
            if (cr[0].signum() > 0) parts.add(words(cr[0], true) + " crore");
            long rest = cr[1].longValue();
            if (rest / 100_000 > 0) parts.add(belowThousand((int) (rest / 100_000)) + " lakh");
            if (rest / 1000 % 100 > 0) parts.add(belowThousand((int) (rest / 1000 % 100)) + " thousand");
            if (rest % 1000 > 0) parts.add(belowThousand((int) (rest % 1000)));
        } else {
            String[] scale = {"", " thousand", " million", " billion", " trillion", " quadrillion", " quintillion", " sextillion", " septillion", " octillion"};
            java.math.BigInteger k = java.math.BigInteger.valueOf(1000);
            int i = 0;
            java.util.Deque<String> stack = new java.util.ArrayDeque<>();
            while (n.signum() > 0) {
                int chunk = n.mod(k).intValue();
                if (chunk > 0) stack.push(belowThousand(chunk) + scale[i]);
                n = n.divide(k);
                i++;
            }
            parts.addAll(stack);
        }
        return String.join(" ", parts);
    }

    private static String belowThousand(int n) {
        StringBuilder sb = new StringBuilder();
        if (n >= 100) { sb.append(ONES[n / 100]).append(" hundred"); n %= 100; if (n > 0) sb.append(' '); }
        if (n >= 20) { sb.append(TENS[n / 10]); if (n % 10 > 0) sb.append('-').append(ONES[n % 10]); }
        else if (n > 0) sb.append(ONES[n]);
        return sb.toString();
    }

    // ---- Conditions ----

    @Condition(id = "is_between", name = "Number is between", description = "True when the number lies between two others (the ends count unless you untick Include the ends).")
    public boolean isBetween(ActionContext ctx,
            @Input(name = "value", type = OoaType.NUMBER, description = "Number to check.") BigDecimal value,
            @Input(name = "low", type = OoaType.NUMBER, description = "Lower end.") BigDecimal low,
            @Input(name = "high", type = OoaType.NUMBER, description = "Upper end.") BigDecimal high,
            @Input(name = "inclusive", type = OoaType.BOOLEAN, required = false, defaultValue = "true", label = "Include the ends", description = "Count the ends themselves as between.") boolean inclusive) {
        BigDecimal lo = low.min(high), hi = low.max(high);
        int a = value.compareTo(lo), b = value.compareTo(hi);
        return inclusive ? a >= 0 && b <= 0 : a > 0 && b < 0;
    }

    @Condition(id = "compare", name = "Compare numbers", description = "True when the comparison holds, e.g. Amount greater than or equal to 1000; with a tolerance, close numbers count as equal.")
    public boolean compareNumbers(ActionContext ctx,
            @Input(name = "value", type = OoaType.NUMBER, description = "The number to check.") BigDecimal value,
            @Input(name = "operator", type = OoaType.STRING, defaultValue = "\"equal\"",
                    enumValues = {"equal", "not_equal", "greater", "greater_or_equal", "less", "less_or_equal"}, description = "How to compare.") String operator,
            @Input(name = "other", type = OoaType.NUMBER, description = "The number to compare with.") BigDecimal other,
            @Input(name = "tolerance", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0,
                    description = "Numbers this close count as equal, e.g. 0.01 for amounts in cents.") BigDecimal tolerance) {
        BigDecimal tol = tolerance == null ? BigDecimal.ZERO : tolerance.abs();
        int c = value.subtract(other).abs().compareTo(tol) <= 0 ? 0 : value.compareTo(other);
        return switch (operator == null ? "equal" : operator) {
            case "not_equal" -> c != 0;
            case "greater" -> c > 0;
            case "greater_or_equal" -> c >= 0;
            case "less" -> c < 0;
            case "less_or_equal" -> c <= 0;
            default -> c == 0;
        };
    }
}
