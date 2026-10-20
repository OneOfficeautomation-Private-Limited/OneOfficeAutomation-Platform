package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Runs PowerShell, Python, JavaScript (Node.js) and VBScript. Scripts run as separate processes with a timeout,
 * never inside the Runner; their output is returned as text, and parsed as JSON when it is JSON.
 */
@OoaPackage(id = "ooa.script", name = "Scripts", version = "1.0.0", description = "Run PowerShell, Python, JavaScript and VBScript with arguments and captured output.")
public final class ScriptPackage {

    private Map<String, Object> run(ActionContext ctx, List<String> interpreters, List<String> preArgs, String ext, String script, String file,
                                    List<Object> args, String input, double timeout, boolean fail) {
        Path temp = null;
        Path target;
        if (file != null && !file.isBlank()) {
            target = ctx.resolvePath(file);
            if (!Files.isRegularFile(target)) throw new ActionException("FILE.NOT_FOUND", "Script not found: " + target, "Check the path.");
        } else if (script != null && !script.isBlank()) {
            try {
                temp = Files.createTempFile("ooa-script-", ext);
                Files.writeString(temp, script, ext.equals(".vbs") ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8);
            } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), null, true); }
            target = temp;
        } else throw new ActionException("SCRIPT.NO_SCRIPT", "Give the script text or a script file", "Fill in 'script' or 'file'.");
        try {
            OoaException last = null;
            for (String interp : interpreters) {
                List<String> cmd = new ArrayList<>();
                cmd.add(interp);
                cmd.addAll(preArgs);
                cmd.add(target.toString());
                if (args != null) for (Object a : args) cmd.add(Values.text(a));
                try {
                    ProcessRunner.Result r = ProcessRunner.run(ctx, cmd, ctx.workDir(), input, timeout);
                    if (fail && r.exitCode() != 0)
                        throw new ActionException("SCRIPT.FAILED", "Script exited with code " + r.exitCode() + ": " + ApplicationPackage.tail(r.stderr().isBlank() ? r.stdout() : r.stderr()),
                                "Read the error output, fix the script, or set fail_on_error to false.");
                    Map<String, Object> out = r.toMap();
                    Object json = null;
                    String t = r.stdout().strip();
                    if (t.startsWith("{") || t.startsWith("[")) try { json = JsonLite.parse(t); } catch (IllegalArgumentException ignored) { }
                    out.put("json", json);
                    return out;
                } catch (ActionException e) {
                    if (!"PROCESS.NOT_FOUND".equals(e.code())) throw e;
                    last = e;
                }
            }
            throw new ActionException("SCRIPT.INTERPRETER_NOT_FOUND", "None of " + interpreters + " is installed on this runner",
                    "Install the interpreter on the runner or add it to the PATH.", false, last == null ? null : Map.of("detail", last.getMessage()));
        } finally {
            if (temp != null) try { Files.deleteIfExists(temp); } catch (IOException ignored) { }
        }
    }

    private static final String D_SCRIPT = "Script text (or use file).";
    private static final String D_FILE = "Script file (or use script).";
    private static final String D_ARGS = "Arguments passed to the script, one list item each.";
    private static final String D_INPUT = "Text sent to the script's standard input (e.g. JSON).";

    @Action(id = "run_powershell", name = "Run PowerShell", category = "Scripts", timeoutSeconds = 3600, description = "Run a PowerShell script (PowerShell 7 if installed, otherwise Windows PowerShell).")
    @Output(name = "output", type = OoaType.STRING, description = "Standard output.")
    @Output(name = "error_output", type = OoaType.STRING, description = "Standard error.")
    @Output(name = "exit_code", type = OoaType.NUMBER, description = "Exit code.")
    @Output(name = "json", description = "Output parsed as JSON, when it is JSON.")
    public Map<String, Object> runPowerShell(ActionContext ctx,
            @Input(name = "script", type = OoaType.STRING, required = false, description = D_SCRIPT) String script,
            @Input(name = "file", type = OoaType.FILE, required = false, description = D_FILE) String file,
            @Input(name = "arguments", type = OoaType.LIST, required = false, description = D_ARGS) List<Object> args,
            @Input(name = "input", type = OoaType.STRING, required = false, description = D_INPUT) String input,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "300", description = "Maximum run time.") double timeout,
            @Input(name = "fail_on_error", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Fail the step when the exit code is not 0.") boolean fail) {
        return run(ctx, List.of("pwsh", "powershell"), List.of("-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File"), ".ps1", script, file, args, input, timeout, fail);
    }

    @Action(id = "run_python", name = "Run Python", category = "Scripts", timeoutSeconds = 3600, description = "Run a Python 3 script.")
    @Output(name = "output", type = OoaType.STRING, description = "Standard output.")
    @Output(name = "error_output", type = OoaType.STRING, description = "Standard error.")
    @Output(name = "exit_code", type = OoaType.NUMBER, description = "Exit code.")
    @Output(name = "json", description = "Output parsed as JSON, when it is JSON.")
    public Map<String, Object> runPython(ActionContext ctx,
            @Input(name = "script", type = OoaType.STRING, required = false, description = D_SCRIPT) String script,
            @Input(name = "file", type = OoaType.FILE, required = false, description = D_FILE) String file,
            @Input(name = "arguments", type = OoaType.LIST, required = false, description = D_ARGS) List<Object> args,
            @Input(name = "input", type = OoaType.STRING, required = false, description = D_INPUT) String input,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "300", description = "Maximum run time.") double timeout,
            @Input(name = "fail_on_error", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Fail the step when the exit code is not 0.") boolean fail) {
        List<String> interp = ProcessRunner.WINDOWS ? List.of("python", "py", "python3") : List.of("python3", "python");
        return run(ctx, interp, List.of(), ".py", script, file, args, input, timeout, fail);
    }

    @Action(id = "run_javascript", name = "Run JavaScript", category = "Scripts", timeoutSeconds = 3600, description = "Run a JavaScript file with Node.js.")
    @Output(name = "output", type = OoaType.STRING, description = "Standard output.")
    @Output(name = "error_output", type = OoaType.STRING, description = "Standard error.")
    @Output(name = "exit_code", type = OoaType.NUMBER, description = "Exit code.")
    @Output(name = "json", description = "Output parsed as JSON, when it is JSON.")
    public Map<String, Object> runJavaScript(ActionContext ctx,
            @Input(name = "script", type = OoaType.STRING, required = false, description = D_SCRIPT) String script,
            @Input(name = "file", type = OoaType.FILE, required = false, description = D_FILE) String file,
            @Input(name = "arguments", type = OoaType.LIST, required = false, description = D_ARGS) List<Object> args,
            @Input(name = "input", type = OoaType.STRING, required = false, description = D_INPUT) String input,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "300", description = "Maximum run time.") double timeout,
            @Input(name = "fail_on_error", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Fail the step when the exit code is not 0.") boolean fail) {
        return run(ctx, List.of("node"), List.of(), ".js", script, file, args, input, timeout, fail);
    }

    @Action(id = "run_vbscript", name = "Run VBScript", category = "Scripts", timeoutSeconds = 3600, description = "Run a VBScript with Windows Script Host (Windows only).")
    @Output(name = "output", type = OoaType.STRING, description = "Standard output.")
    @Output(name = "error_output", type = OoaType.STRING, description = "Standard error.")
    @Output(name = "exit_code", type = OoaType.NUMBER, description = "Exit code.")
    @Output(name = "json", description = "Output parsed as JSON, when it is JSON.")
    public Map<String, Object> runVbScript(ActionContext ctx,
            @Input(name = "script", type = OoaType.STRING, required = false, description = D_SCRIPT) String script,
            @Input(name = "file", type = OoaType.FILE, required = false, description = D_FILE) String file,
            @Input(name = "arguments", type = OoaType.LIST, required = false, description = D_ARGS) List<Object> args,
            @Input(name = "input", type = OoaType.STRING, required = false, description = D_INPUT) String input,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "300", description = "Maximum run time.") double timeout,
            @Input(name = "fail_on_error", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Fail the step when the exit code is not 0.") boolean fail) {
        if (!ProcessRunner.WINDOWS) throw new ActionException("SYSTEM.WINDOWS_ONLY", "VBScript runs only on Windows", "Run this agent on a Windows runner.");
        return run(ctx, List.of("cscript"), List.of("//NoLogo"), ".vbs", script, file, args, input, timeout, fail);
    }

    /** Windows PowerShell command line for a short inline script. Pass values through environment variables, never by pasting them into the script. */
    static List<String> powershellCommand(String script) {
        return List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", script);
    }

    static final String PY_RESULT_MARKER = "__OOA_RESULT__";

    /** Wrapper that imports the user's file, calls one function with JSON arguments from standard input and prints the JSON result after a marker. */
    static final String PY_WRAPPER = """
            import importlib.util, json, os, sys, traceback
            MARK = "__OOA_RESULT__"
            def main():
                path, name = sys.argv[1], sys.argv[2]
                payload = json.loads(sys.stdin.read() or "{}")
                real_out = sys.stdout
                sys.stdout = sys.stderr  # anything the function prints goes to error_output, keeping the result line clean
                try:
                    sys.path.insert(0, os.path.dirname(os.path.abspath(path)))
                    spec = importlib.util.spec_from_file_location("ooa_user_module", path)
                    mod = importlib.util.module_from_spec(spec)
                    spec.loader.exec_module(mod)
                    target = mod
                    for part in name.split("."):
                        target = getattr(target, part)
                    if not callable(target):
                        raise TypeError(name + " is not a function")
                    result = target(*payload.get("args", []), **payload.get("kwargs", {}))
                    text = json.dumps({"ok": True, "result": result}, default=str)
                except BaseException as e:
                    text = json.dumps({"ok": False, "type": type(e).__name__, "message": str(e), "trace": traceback.format_exc()[-2000:]})
                sys.stdout = real_out
                sys.stdout.write("\\n" + MARK + text + "\\n")
                sys.stdout.flush()
            main()
            """;

    @SuppressWarnings("unchecked")
    @Action(id = "run_python_function", name = "Run Python function", category = "Scripts", timeoutSeconds = 3600,
            description = "Call one function in a Python file with JSON-compatible arguments and return what it returns.")
    @Output(name = "result", description = "Value returned by the function (numbers, text, lists and dictionaries).")
    @Output(name = "output", type = OoaType.STRING, description = "Text the function printed.")
    public Map<String, Object> runPythonFunction(ActionContext ctx,
            @Input(name = "file", type = OoaType.FILE, description = "Python file (.py) that defines the function.") String file,
            @Input(name = "function", type = OoaType.STRING, example = "calculate_total", description = "Function name; use Class.method for a static method.") String function,
            @Input(name = "arguments", type = OoaType.LIST, required = false, description = "Positional arguments, in order.") List<Object> args,
            @Input(name = "keyword_arguments", type = OoaType.DICTIONARY, required = false, description = "Named arguments.") Map<String, Object> kwargs,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "300", description = "Maximum run time.") double timeout) {
        Path py = ctx.resolvePath(file);
        if (!Files.isRegularFile(py)) throw new ActionException("FILE.NOT_FOUND", "Python file not found: " + py, "Check the path.");
        if (function == null || !function.matches("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*$"))
            throw new ActionException("SCRIPT.BAD_FUNCTION", "Invalid function name '" + function + "'", "Give a plain Python name such as calculate_total.");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("args", Values.toJsonable(args == null ? List.of() : args));
        payload.put("kwargs", Values.toJsonable(kwargs == null ? Map.of() : kwargs));
        List<String> interp = ProcessRunner.WINDOWS ? List.of("python", "py", "python3") : List.of("python3", "python");
        Map<String, Object> r = run(ctx, interp, List.of(), ".py", PY_WRAPPER, null, List.of(py.toString(), function), JsonLite.write(payload), timeout, false);
        String out = String.valueOf(r.get("output"));
        int i = out.lastIndexOf(PY_RESULT_MARKER);
        if (i < 0) throw new ActionException("SCRIPT.FAILED", "Python did not return a result (exit code " + r.get("exit_code") + "): " + ApplicationPackage.tail(String.valueOf(r.get("error_output"))),
                "Check the file runs with Python 3.");
        Object parsed;
        try { parsed = JsonLite.parse(out.substring(i + PY_RESULT_MARKER.length()).strip()); }
        catch (IllegalArgumentException e) { throw new ActionException("SCRIPT.BAD_RESULT", "The function result could not be read as JSON", "Return numbers, text, lists or dictionaries."); }
        Map<String, Object> m = (Map<String, Object>) parsed;
        if (!Boolean.TRUE.equals(m.get("ok")))
            throw new ActionException("SCRIPT.PYTHON_ERROR", m.get("type") + ": " + m.get("message"), "Fix the Python function or its arguments.", false,
                    Map.of("trace", String.valueOf(m.get("trace"))));
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("result", m.get("result"));
        res.put("output", String.valueOf(r.get("error_output")));
        return res;
    }
}
