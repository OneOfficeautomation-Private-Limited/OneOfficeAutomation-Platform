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
            description = "Call an exported function, e.g. user32.dll MessageBeep, with typed arguments; returns its value.",
            panel = "(Library: By name or path = library / An open session = session), function, arguments, return_type | calling_convention")
    @Output(name = "result", description = "Return value (number or text; empty for void).")
    public Map<String, Object> call(ActionContext ctx,
            @Input(name = "library", type = OoaType.STRING, required = false, example = "user32", description = "Library name or full path of the DLL (or use session).") String library,
            @Input(name = "function", type = OoaType.STRING, example = "MessageBeep", description = "Exported function name.") String function,
            @Input(name = "arguments", type = OoaType.LIST, required = false, description = "Arguments in order, each {\"type\": \"int|long|double|bool|string|wstring\", \"value\": ...}.") List<Object> args,
            @Input(name = "return_type", type = OoaType.STRING, required = false, defaultValue = "\"int\"", enumValues = {"void", "int", "long", "double", "string", "wstring"}, description = "Type of the return value.") String ret,
            @Input(name = "session", type = OoaType.STRING, required = false, description = "Session from Open DLL; used instead of library.") String session,
            @Input(name = "calling_convention", type = OoaType.STRING, required = false, defaultValue = "\"c\"", enumValues = {"c", "stdcall"},
                    description = "How the function expects its arguments; matters only for 32-bit DLLs (Win32 API functions use stdcall).") String convention) {
        if (function == null || !function.matches("^[A-Za-z_][A-Za-z0-9_@]{0,127}$")) throw new ActionException("DLL.BAD_NAME", "Invalid function name '" + function + "'");
        Object[] in = new Object[args == null ? 0 : args.size()];
        for (int i = 0; i < in.length; i++) {
            if (!(args.get(i) instanceof Map<?, ?> m)) throw new ActionException("DLL.BAD_ARGUMENT", "Argument " + (i + 1) + " must be {\"type\": ..., \"value\": ...}");
            in[i] = arg(m);
        }
        int flags = callFlags(convention);
        NativeLibrary lib;
        if (session != null && !session.isBlank()) {
            lib = ctx.session(session, DllSession.class).lib();
            library = lib.getName();
        } else if (library == null || library.isBlank()) throw new ActionException("DLL.NO_LIBRARY", "Give the library or a session from Open DLL");
        else lib = load(library);
        Function f;
        try { f = lib.getFunction(function, flags); }
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

    /** A library kept loaded for several calls, closed by Close DLL or at the end of the run. */
    record DllSession(NativeLibrary lib) implements AutoCloseable {
        @Override public void close() { lib.close(); }
    }

    static int callFlags(String convention) {
        return switch (convention == null || convention.isBlank() ? "c" : convention.strip().toLowerCase(Locale.ROOT)) {
            case "c", "cdecl" -> Function.C_CONVENTION;
            case "stdcall" -> Function.ALT_CONVENTION;
            default -> throw new ActionException("DLL.BAD_CONVENTION", "Unknown calling convention '" + convention + "'", "Use c or stdcall.");
        };
    }

    private static NativeLibrary load(String library) {
        try { return NativeLibrary.getInstance(library); }
        catch (UnsatisfiedLinkError e) { throw new ActionException("DLL.NOT_FOUND", "Cannot load " + library + ": " + e.getMessage(), "Check the DLL name/path and its bitness (64-bit)."); }
    }

    @Action(id = "open", name = "Open DLL", category = "DLL", capabilities = "desktop",
            description = "Load a trusted native library once as a named session, so several Call DLL function steps can use it.",
            panel = "library | session")
    @Output(name = "path", type = OoaType.STRING, description = "Name or path the library was loaded from.")
    public Map<String, Object> open(ActionContext ctx,
            @Input(name = "library", type = OoaType.STRING, example = "C:/Tools/calc.dll", description = "Library name or full path of the DLL.") String library,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"dll\"", description = "Session name for later steps.") String session) {
        if (library == null || library.isBlank()) throw new ActionException("DLL.NO_LIBRARY", "Give the library to open");
        NativeLibrary lib = load(library);
        ctx.warn("Loaded native library " + library);
        ctx.sessions().put(session, new DllSession(lib));
        return Map.of("path", lib.getFile() == null ? lib.getName() : lib.getFile().toString());
    }

    @Action(id = "close", name = "Close DLL", category = "DLL", capabilities = "desktop", description = "Unload a library opened with Open DLL.",
            panel = "session")
    public Map<String, Object> close(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"dll\"", description = "Session name.") String session) {
        if (ctx.sessions().remove(session) instanceof DllSession d) d.close();
        return Map.of();
    }
}
