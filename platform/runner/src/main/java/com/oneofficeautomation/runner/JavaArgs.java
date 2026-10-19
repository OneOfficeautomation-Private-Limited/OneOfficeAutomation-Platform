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
        if (t == int.class || t == Integer.class) return Values.toNumber(v).intValue();
        if (t == long.class || t == Long.class) return Values.toNumber(v).longValue();
        if (t == double.class || t == Double.class) return Values.toNumber(v).doubleValue();
        if (t == Path.class) return Path.of(Values.text(v));
        if (t == List.class) return Values.coerce(v, "List", name);
        if (t == Map.class) return Values.coerce(v, "Dictionary", name);
        if (t == SecureString.class)
            throw new ActionException("VAR.SECURE_REQUIRED", "Input '" + name + "' needs a Credential", "Pass a Credential variable.");
        throw new ActionException("ACTION.INPUT_INVALID", "Input '" + name + "' cannot be converted to " + t.getSimpleName());
    }
}
