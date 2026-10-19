package com.oneofficeautomation.packages.windows;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;
import com.sun.jna.Function;
import com.sun.jna.NativeLibrary;
import com.sun.jna.WString;

import java.math.BigDecimal;
import java.util.*;

/**
 * Calls a function in a native library (DLL on Windows) with simple arguments. Native code runs inside the bot's
 * process, so only call trusted libraries; a crash in the DLL ends the run (the Agent isolates each run in its own process).
 */
@OoaPackage(id = "ooa.dll", name = "DLL", version = "1.0.0", description = "Call a function in a trusted native library (DLL) with numbers and text, and get its return value.")
public final class DllPackage {

    static Object arg(Map<?, ?> a) {
        String type = String.valueOf(a.get("type")).toLowerCase(Locale.ROOT);
        Object v = a.get("value");
        return switch (type) {
            case "int" -> Values.toNumber(v).intValue();
            case "long" -> Values.toNumber(v).longValue();
            case "double" -> Values.toNumber(v).doubleValue();
            case "bool" -> Boolean.TRUE.equals(Values.coerce(v, "Boolean", "value")) ? 1 : 0;
            case "string" -> Values.text(v);
            case "wstring" -> new WString(Values.text(v));
            default -> throw new ActionException("DLL.BAD_TYPE", "Unknown argument type '" + type + "'", "Use int, long, double, bool, string (ANSI) or wstring (Unicode).");
        };
    }

    @Action(id = "call", name = "Call DLL function", category = "DLL", timeoutSeconds = 120, capabilities = "desktop",
            description = "Call an exported function, e.g. user32.dll MessageBeep, with typed arguments; returns its value.")
    @Output(name = "result", description = "Return value (number or text; empty for void).")
    public Map<String, Object> call(ActionContext ctx,
            @Input(name = "library", type = OoaType.STRING, example = "user32", description = "Library name or full path of the DLL.") String library,
            @Input(name = "function", type = OoaType.STRING, example = "MessageBeep", description = "Exported function name.") String function,
            @Input(name = "arguments", type = OoaType.LIST, required = false, description = "Arguments in order, each {\"type\": \"int|long|double|bool|string|wstring\", \"value\": ...}.") List<Object> args,
            @Input(name = "return_type", type = OoaType.STRING, required = false, defaultValue = "\"int\"", enumValues = {"void", "int", "long", "double", "string", "wstring"}, description = "Type of the return value.") String ret) {
        if (!function.matches("^[A-Za-z_][A-Za-z0-9_@]{0,127}$")) throw new ActionException("DLL.BAD_NAME", "Invalid function name '" + function + "'");
        Object[] in = new Object[args == null ? 0 : args.size()];
        for (int i = 0; i < in.length; i++) {
            if (!(args.get(i) instanceof Map<?, ?> m)) throw new ActionException("DLL.BAD_ARGUMENT", "Argument " + (i + 1) + " must be {\"type\": ..., \"value\": ...}");
            in[i] = arg(m);
        }
        Function f;
        try { f = NativeLibrary.getInstance(library).getFunction(function); }
        catch (UnsatisfiedLinkError e) { throw new ActionException("DLL.NOT_FOUND", "Cannot find " + library + "!" + function + ": " + e.getMessage(), "Check the DLL name/path, its bitness (64-bit) and the exported function name."); }
        ctx.warn("Calling native function " + library + "!" + function);
        Object result = switch (ret) {
            case "void" -> { f.invokeVoid(in); yield null; }
            case "long" -> BigDecimal.valueOf(f.invokeLong(in));
            case "double" -> BigDecimal.valueOf(f.invokeDouble(in));
            case "string" -> f.invokeString(in, false);
            case "wstring" -> f.invokeString(in, true);
            default -> BigDecimal.valueOf(f.invokeInt(in));
        };
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("result", result);
        return out;
    }
}
