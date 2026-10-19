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

    @Action(id = "random", name = "Random number", category = "Number", description = "Generate a random whole number between min and max (inclusive).")
    @Output(name = "result", type = OoaType.NUMBER, description = "Random number.")
    public Map<String, Object> random(ActionContext ctx,
            @Input(name = "min", type = OoaType.NUMBER, required = false, defaultValue = "0", description = "Smallest value.") long min,
            @Input(name = "max", type = OoaType.NUMBER, required = false, defaultValue = "100", description = "Largest value.") long max) {
        if (max < min) throw new ActionException("NUMBER.BAD_RANGE", "max (" + max + ") is smaller than min (" + min + ")", "Swap min and max.");
        return Out.of("result", BigDecimal.valueOf(min + (long) (RANDOM.nextDouble() * (max - min + 1))));
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
}
