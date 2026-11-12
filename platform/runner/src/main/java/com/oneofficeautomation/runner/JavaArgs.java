package com.oneofficeautomation.runner;

import com.oneofficeautomation.sdk.ActionException;
import com.oneofficeautomation.sdk.SecureString;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Converts runtime values to the Java parameter types an action method declares. */
final class JavaArgs {
    private JavaArgs() {}

    static Object convert(Object v, Class<?> t, String name) {
        if (v == null) {
            if (t == boolean.class) return false;
            if (t == int.class) return 0;
            if (t == long.class) return 0L;
            if (t == double.class) return 0.0;
            return null;
        }
        if (t == Object.class || t.isInstance(v)) return v;
        if (t == String.class) return Values.text(v);
        if (t == BigDecimal.class) return Values.toNumber(v);
        if (t == boolean.class || t == Boolean.class) return Values.coerce(v, "Boolean", name);
        if (t == int.class || t == Integer.class) return whole(v, name).intValue();
        if (t == long.class || t == Long.class) return whole(v, name).longValue();
        if (t == double.class || t == Double.class) return Values.toNumber(v).doubleValue();
        if (t == Path.class) return Path.of(Values.text(v));
        if (t == List.class) return Values.coerce(v, "List", name);
        if (t == Map.class) return Values.coerce(v, "Dictionary", name);
        if (t == java.time.LocalTime.class) return Values.toTime(v);
        if (t == java.time.Duration.class) return Values.toTimeSpan(v);
        if (t == com.oneofficeautomation.sdk.ImageValue.class) return Values.toImage(v, null);
        if (t == com.oneofficeautomation.sdk.BinaryValue.class) return Values.toBinary(v, null);
        if (t == byte[].class) return Values.toBinary(v, null).bytes();
        if (t == SecureString.class)
            throw new ActionException("VAR.SECURE_REQUIRED", "Input '" + name + "' needs a Credential, not plain text",
                    "Give a vault reference {\"$credential\": \"locker/credential/attribute\"} or a Credential variable.");
        throw new ActionException("ACTION.INPUT_INVALID", "Input '" + name + "' cannot be converted to " + t.getSimpleName(), "Give a value of the type the input expects.");
    }

    /** Measures where a fraction is meaningful but the command works in whole units: rounded to the nearest one. */
    private static final java.util.regex.Pattern ROUNDED = java.util.regex.Pattern.compile(
            "(^|_)(x|y|width|height|dpi|font_size|indent)$|seconds$|minutes$|_ms$|percent$|^ms$");

    /**
     * Positions, counts, ids and sizes are whole numbers: 1.7 used to become 1 silently, hiding calculation mistakes, so it
     * is refused. Times, coordinates and similar measures are rounded to the nearest whole unit instead of cut off.
     */
    static BigDecimal whole(Object v, String name) {
        BigDecimal n = Values.toNumber(v);
        if (n.signum() != 0 && n.stripTrailingZeros().scale() > 0 && name != null && ROUNDED.matcher(name).find())
            n = n.setScale(0, java.math.RoundingMode.HALF_UP);
        if (n.signum() != 0 && n.stripTrailingZeros().scale() > 0)
            throw new ActionException("ACTION.INPUT_INVALID", "Input '" + name + "' must be a whole number, got " + n.stripTrailingZeros().toPlainString(),
                    "Round the value first (Number > Round), or check the calculation that produced it.");
        if (n.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0 || n.compareTo(BigDecimal.valueOf(Long.MIN_VALUE)) < 0)
            throw new ActionException("ACTION.INPUT_INVALID", "Input '" + name + "' is too large: " + n.toPlainString(), "Give a smaller number.");
        return n;
    }
}
