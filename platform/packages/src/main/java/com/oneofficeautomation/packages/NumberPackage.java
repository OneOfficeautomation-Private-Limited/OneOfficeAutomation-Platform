package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Map;

@OoaPackage(id = "ooa.number", name = "Number", version = "1.0.0", description = "Round, format and generate numbers with exact decimal arithmetic.")
public final class NumberPackage {
    private static final SecureRandom RANDOM = new SecureRandom();

    @Action(id = "round", name = "Round", category = "Number", idempotent = true, description = "Round a number to a number of decimal places.")
    @Output(name = "result", type = OoaType.NUMBER, description = "Rounded number.")
    public Map<String, Object> round(ActionContext ctx,
            @Input(name = "value", type = OoaType.NUMBER, description = "Number to round.") BigDecimal value,
            @Input(name = "decimals", type = OoaType.NUMBER, required = false, defaultValue = "0", description = "Decimal places.") int decimals,
            @Input(name = "mode", type = OoaType.STRING, required = false, defaultValue = "\"half_up\"", enumValues = {"half_up", "half_even", "up", "down"},
                    description = "Rounding mode; half_even is banker's rounding.") String mode) {
        RoundingMode rm = switch (mode) { case "half_even" -> RoundingMode.HALF_EVEN; case "up" -> RoundingMode.UP; case "down" -> RoundingMode.DOWN; default -> RoundingMode.HALF_UP; };
        return Out.of("result", value.setScale(decimals, rm));
    }

    @Action(id = "random", name = "Random number", category = "Number", description = "Generate a random number between min and max (inclusive), whole or with decimals.")
    @Output(name = "result", type = OoaType.NUMBER, description = "Random number.")
    public Map<String, Object> random(ActionContext ctx,
            @Input(name = "min", type = OoaType.NUMBER, required = false, defaultValue = "0", description = "Smallest value.") BigDecimal min,
            @Input(name = "max", type = OoaType.NUMBER, required = false, defaultValue = "100", description = "Largest value.") BigDecimal max,
            @Input(name = "decimals", type = OoaType.NUMBER, required = false, defaultValue = "0", description = "Decimal places of the result; 0 gives a whole number.") int decimals) {
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
            description = "Format a number as text with thousands separators and fixed decimals, e.g. 1,234.50.")
    @Output(name = "text", type = OoaType.STRING, description = "Formatted text.")
    public Map<String, Object> format(ActionContext ctx,
            @Input(name = "value", type = OoaType.NUMBER, description = "Number to format.") BigDecimal value,
            @Input(name = "decimals", type = OoaType.NUMBER, required = false, defaultValue = "2", description = "Decimal places.") int decimals,
            @Input(name = "thousands_separator", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Group thousands.") boolean group,
            @Input(name = "locale", type = OoaType.STRING, required = false, defaultValue = "\"en-US\"", description = "Locale for separators, e.g. en-US, de-DE, en-IN.") String locale) {
        DecimalFormat f = new DecimalFormat((group ? "#,##0" : "0") + (decimals > 0 ? "." + "0".repeat(decimals) : ""),
                DecimalFormatSymbols.getInstance(Locale.forLanguageTag(locale)));
        f.setRoundingMode(RoundingMode.HALF_UP);
        return Out.of("text", f.format(value));
    }

    @Action(id = "increment", name = "Increment", category = "Number", idempotent = true, description = "Add a step (1 by default) to a number.")
    @Output(name = "result", type = OoaType.NUMBER, description = "Number plus the step.")
    public Map<String, Object> increment(ActionContext ctx,
            @Input(name = "value", type = OoaType.NUMBER, description = "Starting number.") BigDecimal value,
            @Input(name = "step", type = OoaType.NUMBER, required = false, defaultValue = "1", description = "Amount to add.") BigDecimal step) {
        return Out.of("result", value.add(step == null ? BigDecimal.ONE : step));
    }

    @Action(id = "decrement", name = "Decrement", category = "Number", idempotent = true, description = "Subtract a step (1 by default) from a number.")
    @Output(name = "result", type = OoaType.NUMBER, description = "Number minus the step.")
    public Map<String, Object> decrement(ActionContext ctx,
            @Input(name = "value", type = OoaType.NUMBER, description = "Starting number.") BigDecimal value,
            @Input(name = "step", type = OoaType.NUMBER, required = false, defaultValue = "1", description = "Amount to subtract.") BigDecimal step) {
        return Out.of("result", value.subtract(step == null ? BigDecimal.ONE : step));
    }

    @Action(id = "to_text", name = "Number to text", category = "Number", idempotent = true,
            description = "Write a number as plain text, with fixed decimals or a pattern such as 0000 or #,##0.00.")
    @Output(name = "text", type = OoaType.STRING, description = "The number as text.")
    public Map<String, Object> toText(ActionContext ctx,
            @Input(name = "value", type = OoaType.NUMBER, description = "Number to write.") BigDecimal value,
            @Input(name = "decimals", type = OoaType.NUMBER, required = false, description = "Fixed decimal places (rounded half up).") Integer decimals,
            @Input(name = "pattern", type = OoaType.STRING, required = false, description = "Pattern with 0 for required digits and # for optional ones, e.g. 00000 or #,##0.00.") String pattern,
            @Input(name = "rounding", type = OoaType.STRING, required = false, defaultValue = "\"half_up\"", enumValues = {"half_up", "truncate"},
                    description = "How extra decimals are dropped: rounded half up, or cut off (truncated).") String rounding) {
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
}
