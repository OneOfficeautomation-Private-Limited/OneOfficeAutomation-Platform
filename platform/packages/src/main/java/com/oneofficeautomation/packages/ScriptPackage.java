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
        return run(ctx, interpreters, preArgs, ext, script, file, args, input, timeout, fail, null, null);
    }

    /** Interpreters to try: the one the user named, or the usual names on this system. */
    static List<String> interpreters(String chosen, List<String> usual) {
        if (chosen == null || chosen.isBlank()) return usual;
        String c = chosen.strip();
        if (c.startsWith("-")) throw new ActionException("SCRIPT.BAD_INTERPRETER", "Invalid interpreter '" + chosen + "'", "Give the program name or full path, e.g. C:/Python312/python.exe.");
        return List.of(c);
    }

    private Map<String, Object> run(ActionContext ctx, List<String> interpreters, List<String> preArgs, String ext, String script, String file,
                                    List<Object> args, String input, double timeout, boolean fail, String workingFolder, Map<String, Object> environment) {
        Path temp = null, boot = null;
        Path target;
        if (file != null && !file.isBlank()) {
            target = ctx.resolvePath(file);
            if (!Files.isRegularFile(target)) throw new ActionException("FILE.NOT_FOUND", "Script not found: " + target, "Check the path.");
        } else if (script != null && !script.isBlank()) {
            try {
                temp = Files.createTempFile("ooa-script-", ext);
                // Windows PowerShell 5.1 reads a .ps1 without a byte order mark as ANSI, so "Grüße" in a typed script broke
                if (ext.equals(".ps1")) Files.writeString(temp, "\uFEFF" + script, StandardCharsets.UTF_8);
                else Files.writeString(temp, script, ext.equals(".vbs") ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8);
            } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), "Check that the file or folder exists, is not open in another program, and that the runner's account may read and write it.", true); }
            target = temp;
        } else throw new ActionException("SCRIPT.NO_SCRIPT", "Give the script text or a script file", "Fill in 'script' or 'file'.");
        if (ext.equals(".ps1")) {
            try { boot = powershellBootstrap(target); }
            catch (IOException e) { throw new ActionException("FILE.IO_ERROR", "Cannot write the PowerShell start file: " + e.getMessage(), "Check that the file or folder exists, is not open in another program, and that the runner's account may read and write it.", true); }
            target = boot;
        }
        try {
            OoaException last = null;
            for (String interp : interpreters) {
                List<String> cmd = new ArrayList<>();
                cmd.add(interp);
                cmd.addAll(preArgs);
                cmd.add(target.toString());
                if (args != null) for (Object a : args) cmd.add(Values.text(a));
                try {
                    Map<String, String> env = ApplicationPackage.env(environment);
                    Path dir = workingFolder == null || workingFolder.isBlank() ? ctx.workDir() : ctx.resolvePath(workingFolder);
                    ProcessRunner.Result r = ProcessRunner.run(ctx, cmd, dir, input, timeout, env.isEmpty() ? null : env, StandardCharsets.UTF_8);
                    if (storeAlias(r)) { last = new ActionException("PROCESS.NOT_FOUND", interp + " is only the Microsoft Store shortcut, not an installed Python", "Install Python from python.org (or turn off the Microsoft Store shortcut in App execution aliases), then try again."); continue; }
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
            boolean python = ext.equals(".py");
            throw new ActionException("SCRIPT.INTERPRETER_NOT_FOUND", python ? "Python is not installed on this runner" + (last != null && last.getMessage().contains("Microsoft Store") ? " (python only opens the Microsoft Store)" : "")
                            : "None of " + interpreters + " is installed on this runner",
                    python ? "Install Python 3 from python.org (tick Add to PATH) or give its program in interpreter, e.g. C:/Python312/python.exe."
                            : "Install the interpreter on the runner or add it to the PATH.", false, last == null ? null : Map.of("detail", last.getMessage()));
        } finally {
            if (temp != null) try { Files.deleteIfExists(temp); } catch (IOException ignored) { }
            if (boot != null) try { Files.deleteIfExists(boot); } catch (IOException ignored) { }
        }
    }

    /** Windows' "python" placeholder that only offers the Microsoft Store (exit 9009 "Python was not found; run without arguments to install from the Microsoft Store"). */
    static boolean storeAlias(ProcessRunner.Result r) {
        return ProcessRunner.WINDOWS && r.exitCode() == 9009 && (r.stderr() + r.stdout()).contains("Microsoft Store");
    }

    /**
     * A start file that switches the console to UTF-8 (output, input and text sent to programs) and then runs the script
     * with the step's arguments. Without it Windows PowerShell wrote "Grüße" as "GrA�AYe" in the output. Exit codes stay
     * as with -File: the script's exit N, 1 after a terminating error, 0 otherwise (even when the last program it ran failed).
     */
    static Path powershellBootstrap(Path script) throws IOException {
        Path boot = Files.createTempFile("ooa-ps-start-", ".ps1");
        String text = "\uFEFF" + UTF8_PREAMBLE + "\n"
                + "$global:LASTEXITCODE = 0\n"
                + "& '" + script.toAbsolutePath().toString().replace("'", "''") + "' @args\n"
                + "if (-not $?) { exit $global:LASTEXITCODE }\n"
                + "exit 0\n";
        Files.writeString(boot, text, StandardCharsets.UTF_8);
        return boot;
    }

    /** PowerShell statements that make output, input and text piped to programs UTF-8. */
    static final String UTF8_PREAMBLE = "try { [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding $false } catch { }; "
            + "try { [Console]::InputEncoding = New-Object System.Text.UTF8Encoding $false } catch { }; "
            + "$OutputEncoding = New-Object System.Text.UTF8Encoding $false";

    private static final String D_SCRIPT = "Script text (or use file).";
    private static final String D_FILE = "Script file (or use script).";
    private static final String D_ARGS = "Arguments passed to the script, one list item each.";
    private static final String D_INPUT = "Text sent to the script's standard input (e.g. JSON).";

    @Action(id = "run_powershell", name = "Run PowerShell", category = "Scripts", timeoutSeconds = 3600, description = "Run a PowerShell script (PowerShell 7 if installed, otherwise Windows PowerShell).",
            panel = "(Script: Type it here = script / Use a file = file), arguments | input, timeout_seconds, fail_on_error, working_folder, environment, interpreter")
    @Output(name = "output", type = OoaType.STRING, description = "Standard output.")
    @Output(name = "error_output", type = OoaType.STRING, description = "Standard error.")
    @Output(name = "exit_code", type = OoaType.NUMBER, description = "Exit code.")
    @Output(name = "json", description = "Output parsed as JSON, when it is JSON.")
    public Map<String, Object> runPowerShell(ActionContext ctx,
            @Input(name = "script", type = OoaType.STRING, required = false, visibleWhen = "file=", description = D_SCRIPT) String script,
            @Input(name = "file", type = OoaType.FILE, required = false, visibleWhen = "script=", description = D_FILE) String file,
            @Input(name = "arguments", type = OoaType.LIST, required = false, description = D_ARGS) List<Object> args,
            @Input(name = "input", type = OoaType.STRING, required = false, description = D_INPUT) String input,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "300", min = 1, description = "Maximum run time.") double timeout,
            @Input(name = "fail_on_error", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Fail the step when the exit code is not 0.") boolean fail,
            @Input(name = "working_folder", type = OoaType.FILE, required = false, description = "Folder the script runs in; the workspace when empty.") String workingFolder,
            @Input(name = "environment", type = OoaType.DICTIONARY, required = false, description = "Extra environment variables for the script.") Map<String, Object> environment,
            @Input(name = "interpreter", type = OoaType.STRING, required = false, example = "powershell", description = "PowerShell program to use, e.g. pwsh or powershell; PowerShell 7 first when empty.") String interpreter) {
        return run(ctx, interpreters(interpreter, List.of("pwsh", "powershell")), List.of("-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File"), ".ps1", script, file, args, input, timeout, fail, workingFolder, environment);
    }

    @Action(id = "run_python", name = "Run Python", category = "Scripts", timeoutSeconds = 3600, description = "Run a Python 3 script.",
            panel = "(Script: Type it here = script / Use a file = file), arguments, fail_on_error | input, timeout_seconds, working_folder, environment, interpreter")
    @Output(name = "output", type = OoaType.STRING, description = "Standard output.")
    @Output(name = "error_output", type = OoaType.STRING, description = "Standard error.")
    @Output(name = "exit_code", type = OoaType.NUMBER, description = "Exit code.")
    @Output(name = "json", description = "Output parsed as JSON, when it is JSON.")
    public Map<String, Object> runPython(ActionContext ctx,
            @Input(name = "script", type = OoaType.STRING, required = false, visibleWhen = "file=", description = D_SCRIPT) String script,
            @Input(name = "file", type = OoaType.FILE, required = false, visibleWhen = "script=", description = D_FILE) String file,
            @Input(name = "arguments", type = OoaType.LIST, required = false, description = D_ARGS) List<Object> args,
            @Input(name = "input", type = OoaType.STRING, required = false, description = D_INPUT) String input,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "300", min = 1, description = "Maximum run time.") double timeout,
            @Input(name = "fail_on_error", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Fail the step when the exit code is not 0.") boolean fail,
            @Input(name = "working_folder", type = OoaType.FILE, required = false, description = "Folder the script runs in; the workspace when empty.") String workingFolder,
            @Input(name = "environment", type = OoaType.DICTIONARY, required = false, description = "Extra environment variables for the script.") Map<String, Object> environment,
            @Input(name = "interpreter", type = OoaType.STRING, required = false, example = "C:/Python312/python.exe", description = "Python program to use, e.g. a virtual environment's python; found on the PATH when empty.") String interpreter) {
        List<String> interp = interpreters(interpreter, ProcessRunner.WINDOWS ? List.of("python", "py", "python3") : List.of("python3", "python"));
        return run(ctx, interp, List.of(), ".py", script, file, args, input, timeout, fail, workingFolder, environment);
    }

    @Action(id = "run_javascript", name = "Run JavaScript", category = "Scripts", timeoutSeconds = 3600, description = "Run JavaScript with Node.js, or with Windows' built-in JScript when Node.js is not installed.",
            panel = "(Script: Type it here = script / Use a file = file), arguments | input, timeout_seconds, fail_on_error, working_folder, environment, interpreter")
    @Output(name = "output", type = OoaType.STRING, description = "Standard output.")
    @Output(name = "error_output", type = OoaType.STRING, description = "Standard error.")
    @Output(name = "exit_code", type = OoaType.NUMBER, description = "Exit code.")
    @Output(name = "json", description = "Output parsed as JSON, when it is JSON.")
    public Map<String, Object> runJavaScript(ActionContext ctx,
            @Input(name = "script", type = OoaType.STRING, required = false, visibleWhen = "file=", description = D_SCRIPT) String script,
            @Input(name = "file", type = OoaType.FILE, required = false, visibleWhen = "script=", description = D_FILE) String file,
            @Input(name = "arguments", type = OoaType.LIST, required = false, description = D_ARGS) List<Object> args,
            @Input(name = "input", type = OoaType.STRING, required = false, description = D_INPUT) String input,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "300", min = 1, description = "Maximum run time.") double timeout,
            @Input(name = "fail_on_error", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Fail the step when the exit code is not 0.") boolean fail,
            @Input(name = "working_folder", type = OoaType.FILE, required = false, description = "Folder the script runs in; the workspace when empty.") String workingFolder,
            @Input(name = "environment", type = OoaType.DICTIONARY, required = false, description = "Extra environment variables for the script.") Map<String, Object> environment,
            @Input(name = "interpreter", type = OoaType.STRING, required = false, example = "node", description = "Node.js program to use; node on the PATH when empty, and Windows' built-in JScript when Node.js is not installed.") String interpreter) {
        try { return run(ctx, interpreters(interpreter, List.of("node")), List.of(), ".js", script, file, args, input, timeout, fail, workingFolder, environment); }
        catch (ActionException e) {
            // Windows always has JScript (Windows Script Host), as other RPA tools use; a fresh runner has no Node.js
            if (!"SCRIPT.INTERPRETER_NOT_FOUND".equals(e.code()) || !ProcessRunner.WINDOWS || (interpreter != null && !interpreter.isBlank())) throw e;
            ctx.info("Node.js is not installed; running the script with Windows JScript (console.log, process.argv and process.exit work; Node modules do not)");
            Path src = file != null && !file.isBlank() ? ctx.resolvePath(file) : null;
            if (src != null && !Files.isRegularFile(src)) throw new ActionException("FILE.NOT_FOUND", "Script not found: " + src, "Check the path.");
            return jscript(ctx, src, script, null, null, args, input, timeout, fail, workingFolder, environment);
        }
    }

    /** JScript stand-ins for console and process, and a JSON writer (Windows Script Host has neither); see jscript_prelude.js. */
    static final String JSCRIPT_PRELUDE = resource("jscript_prelude.js");

    private static String resource(String name) {
        try (var in = ScriptPackage.class.getResourceAsStream(name)) {
            if (in == null) throw new IllegalStateException(name + " is missing from the toolkit");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) { throw new IllegalStateException(e); }
    }

    /**
     * Runs JavaScript with Windows JScript: the user's code (read as UTF-8) is evaluated in global scope after the prelude;
     * with a function name it is then called with the arguments and its result printed after the result marker, as the
     * Node.js wrapper does. Output is read as Unicode (cscript //U), so non-English text survives.
     */
    private Map<String, Object> jscript(ActionContext ctx, Path file, String script, String function, List<Object> fnArgs, List<Object> args, String input,
                                        double timeout, boolean fail, String workingFolder, Map<String, Object> environment) {
        Path user = null, wrapper = null;
        try {
            if (file == null) { user = Files.createTempFile("ooa-js-", ".js"); Files.writeString(user, script == null ? "" : script, StandardCharsets.UTF_8); }
            Path code = file != null ? file : user;
            StringBuilder w = new StringBuilder(JSCRIPT_PRELUDE);
            w.append("for (var __i = 0; __i < WScript.Arguments.length; __i++) process.argv.push(WScript.Arguments(__i));\n");
            String lit = code.toAbsolutePath().toString().replace("\\", "\\\\").replace("'", "\\'");
            if (function == null) w.append("eval(__ooaRead('").append(lit).append("'));\n");
            else {
                String argLit = JsonLite.write(Values.toJsonable(fnArgs == null ? List.of() : fnArgs)).replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
                w.append("console.log = console.error;\n")   // printed text goes to error_output, keeping the result line clean
                        .append("var __out;\ntry {\n  eval(__ooaRead('").append(lit).append("'));\n")
                        .append("  var __t = this, __p = '").append(function).append("'.split('.');\n")
                        .append("  for (var __j = 0; __j < __p.length; __j++) __t = __t == null ? undefined : __t[__p[__j]];\n")
                        .append("  if (typeof __t != 'function') throw new TypeError('").append(function).append(" is not a function');\n")
                        .append("  var __r = __t.apply(null, ").append(argLit).append(");\n")
                        .append("  __out = '{\"ok\":true,\"result\":' + __ooaJson(__r === undefined ? null : __r) + '}';\n")
                        .append("} catch (e) { __out = '{\"ok\":false,\"type\":' + __ooaJson(e.name || 'Error') + ',\"message\":' + __ooaJson(e.message !== undefined ? e.message : String(e)) + ',\"trace\":\"\"}'; }\n")
                        .append("WScript.StdOut.WriteLine('');\nWScript.StdOut.WriteLine('").append(PY_RESULT_MARKER).append("' + __out);\n");
            }
            wrapper = Files.createTempFile("ooa-jscript-", ".js");
            Files.writeString(wrapper, "\uFEFF" + w, StandardCharsets.UTF_16LE);  // cscript reads Unicode script files that start with a byte order mark
            List<String> cmd = new ArrayList<>(List.of("cscript", "//NoLogo", "//U", "//E:JScript", wrapper.toString()));
            if (args != null) for (Object a : args) cmd.add(Values.text(a));
            Map<String, String> env = ApplicationPackage.env(environment);
            Path dir = workingFolder == null || workingFolder.isBlank() ? ctx.workDir() : ctx.resolvePath(workingFolder);
            ProcessRunner.Result r = ProcessRunner.run(ctx, cmd, dir, input, timeout, env.isEmpty() ? null : env, StandardCharsets.UTF_16LE);
            if (function != null) return r.toMap();
            if (fail && r.exitCode() != 0)
                throw new ActionException("SCRIPT.FAILED", "Script exited with code " + r.exitCode() + ": " + ApplicationPackage.tail(r.stderr().isBlank() ? r.stdout() : r.stderr()),
                        "Read the error output, fix the script, or set fail_on_error to false.");
            Map<String, Object> out = r.toMap();
            Object json = null;
            String t = r.stdout().strip();
            if (t.startsWith("{") || t.startsWith("[")) try { json = JsonLite.parse(t); } catch (IllegalArgumentException ignored) { }
            out.put("json", json);
            return out;
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), "Check that the file or folder exists, is not open in another program, and that the runner's account may read and write it.", true);
        } finally {
            for (Path p : new Path[]{user, wrapper}) if (p != null) try { Files.deleteIfExists(p); } catch (IOException ignored) { }
        }
    }

    @Action(id = "run_vbscript", name = "Run VBScript", category = "Scripts", timeoutSeconds = 3600, description = "Run a VBScript with Windows Script Host (Windows only).",
            panel = "(Script: Type it here = script / Use a file = file), arguments | input, timeout_seconds, fail_on_error, working_folder, environment")
    @Output(name = "output", type = OoaType.STRING, description = "Standard output.")
    @Output(name = "error_output", type = OoaType.STRING, description = "Standard error.")
    @Output(name = "exit_code", type = OoaType.NUMBER, description = "Exit code.")
    @Output(name = "json", description = "Output parsed as JSON, when it is JSON.")
    public Map<String, Object> runVbScript(ActionContext ctx,
            @Input(name = "script", type = OoaType.STRING, required = false, visibleWhen = "file=", description = D_SCRIPT) String script,
            @Input(name = "file", type = OoaType.FILE, required = false, visibleWhen = "script=", description = D_FILE) String file,
            @Input(name = "arguments", type = OoaType.LIST, required = false, description = D_ARGS) List<Object> args,
            @Input(name = "input", type = OoaType.STRING, required = false, description = D_INPUT) String input,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "300", min = 1, description = "Maximum run time.") double timeout,
            @Input(name = "fail_on_error", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Fail the step when the exit code is not 0.") boolean fail,
            @Input(name = "working_folder", type = OoaType.FILE, required = false, description = "Folder the script runs in; the workspace when empty.") String workingFolder,
            @Input(name = "environment", type = OoaType.DICTIONARY, required = false, description = "Extra environment variables for the script.") Map<String, Object> environment) {
        if (!ProcessRunner.WINDOWS) throw new ActionException("SYSTEM.WINDOWS_ONLY", "VBScript runs only on Windows", "Run this agent on a Windows runner.");
        return run(ctx, List.of("cscript"), List.of("//NoLogo"), ".vbs", script, file, args, input, timeout, fail, workingFolder, environment);
    }

    /** Windows PowerShell command line for a short inline script. Pass values through environment variables, never by pasting them into the script. */
    static List<String> powershellCommand(String script) {
        return List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", UTF8_PREAMBLE + "; " + script);
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

    /** The file that defines the function: the given file, or the typed script written to a temporary file (deleted after the call). */
    private static Path functionSource(ActionContext ctx, String file, String script, String ext, String language) {
        if (script != null && !script.isBlank()) {
            try {
                Path tmp = Files.createTempFile("ooa-function-", ext);
                Files.writeString(tmp, script, java.nio.charset.StandardCharsets.UTF_8);
                return tmp;
            } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", "Cannot write the script to a temporary file: " + e.getMessage(), "Check that the file or folder exists, is not open in another program, and that the runner's account may read and write it.", true); }
        }
        if (file == null || file.isBlank()) throw new ActionException("SCRIPT.NO_SOURCE", "Give the " + language + " file or type the script", "Fill in file or script.");
        Path p = ctx.resolvePath(file);
        if (!Files.isRegularFile(p)) throw new ActionException("FILE.NOT_FOUND", language + " file not found: " + p, "Check the path.");
        return p;
    }

    @Action(id = "run_python_function", name = "Run Python function", category = "Scripts", timeoutSeconds = 3600,
            description = "Call one function in a Python file with JSON-compatible arguments and return what it returns.",
            panel = "(Code: Use a file = file / Type it here = script), function, arguments, keyword_arguments, fail_on_error | timeout_seconds, interpreter, environment")
    @Output(name = "result", description = "Value returned by the function (numbers, text, lists and dictionaries).")
    @Output(name = "output", type = OoaType.STRING, description = "Text the function printed.")
    @Output(name = "error", type = OoaType.STRING, description = "Error raised by the function when fail_on_error is false; empty on success.")
    public Map<String, Object> runPythonFunction(ActionContext ctx,
            @Input(name = "file", type = OoaType.FILE, required = false, visibleWhen = "script=", description = "Python file (.py) that defines the function.") String file,
            @Input(name = "function", type = OoaType.STRING, example = "calculate_total", description = "Function name; use Class.method for a static method.") String function,
            @Input(name = "arguments", type = OoaType.LIST, required = false, description = "Positional arguments, in order.") List<Object> args,
            @Input(name = "keyword_arguments", type = OoaType.DICTIONARY, required = false, description = "Named arguments.") Map<String, Object> kwargs,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "300", min = 1, description = "Maximum run time.") double timeout,
            @Input(name = "fail_on_error", type = OoaType.BOOLEAN, required = false, defaultValue = "true",
                    description = "Fail the step when the function raises an error; when false the error is returned in error and the step continues.") boolean fail,
            @Input(name = "interpreter", type = OoaType.STRING, required = false, description = "Python program to use; found on the PATH when empty.") String interpreter,
            @Input(name = "environment", type = OoaType.DICTIONARY, required = false, description = "Extra environment variables for Python.") Map<String, Object> environment,
            @Input(name = "script", type = OoaType.STRING, required = false, visibleWhen = "file=", description = "Code that defines the function, typed in the agent, instead of a file.") String script) {
        Path py = functionSource(ctx, file, script, ".py", "Python");
        try { return runPythonFunction(ctx, py, function, args, kwargs, timeout, fail, interpreter, environment); }
        finally { if (script != null && !script.isBlank()) try { Files.deleteIfExists(py); } catch (IOException ignored) { } }
    }

    public Map<String, Object> runPythonFunction(ActionContext ctx, String file, String function, List<Object> args, Map<String, Object> kwargs, double timeout,
                                                 boolean fail, String interpreter, Map<String, Object> environment) {
        return runPythonFunction(ctx, file, function, args, kwargs, timeout, fail, interpreter, environment, null);
    }

    private Map<String, Object> runPythonFunction(ActionContext ctx, Path py, String function, List<Object> args, Map<String, Object> kwargs, double timeout,
                                                  boolean fail, String interpreter, Map<String, Object> environment) {
        if (function == null || !function.matches("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*$"))
            throw new ActionException("SCRIPT.BAD_FUNCTION", "Invalid function name '" + function + "'", "Give a plain Python name such as calculate_total.");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("args", Values.toJsonable(args == null ? List.of() : args));
        payload.put("kwargs", Values.toJsonable(kwargs == null ? Map.of() : kwargs));
        List<String> interp = interpreters(interpreter, ProcessRunner.WINDOWS ? List.of("python", "py", "python3") : List.of("python3", "python"));
        Map<String, Object> r = run(ctx, interp, List.of(), ".py", PY_WRAPPER, null, List.of(py.toString(), function), JsonLite.write(payload), timeout, false, null, environment);
        return functionResult(r, "Python", "SCRIPT.PYTHON_ERROR", fail);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> functionResult(Map<String, Object> r, String language, String errorCode, boolean fail) {
        String out = String.valueOf(r.get("output"));
        int i = out.lastIndexOf(PY_RESULT_MARKER);
        if (i < 0) throw new ActionException("SCRIPT.FAILED", language + " did not return a result (exit code " + r.get("exit_code") + "): " + ApplicationPackage.tail(String.valueOf(r.get("error_output"))),
                "Check the file runs on its own.");
        Object parsed;
        try { parsed = JsonLite.parse(out.substring(i + PY_RESULT_MARKER.length()).strip()); }
        catch (IllegalArgumentException e) { throw new ActionException("SCRIPT.BAD_RESULT", "The function result could not be read as JSON", "Return numbers, text, lists or dictionaries."); }
        Map<String, Object> m = (Map<String, Object>) parsed;
        Map<String, Object> res = new LinkedHashMap<>();
        if (!Boolean.TRUE.equals(m.get("ok"))) {
            String msg = m.get("type") + ": " + m.get("message");
            if (fail) throw new ActionException(errorCode, msg, "Fix the " + language + " function or its arguments.", false, Map.of("trace", String.valueOf(m.get("trace"))));
            res.put("result", null);
            res.put("output", String.valueOf(r.get("error_output")));
            res.put("error", msg);
            return res;
        }
        res.put("result", m.get("result"));
        res.put("output", String.valueOf(r.get("error_output")));
        res.put("error", "");
        return res;
    }

    /** Node.js wrapper: loads the user's file, calls one function (exported or global) with JSON arguments and prints the JSON result after a marker. */
    static final String JS_WRAPPER = """
            const fs = require('fs'), path = require('path'), vm = require('vm');
            const MARK = '__OOA_RESULT__';
            const [file, name] = process.argv.slice(2);
            let data = '';
            process.stdin.on('data', d => data += d);
            process.stdin.on('end', async () => {
              const payload = JSON.parse(data || '{}');
              console.log = (...a) => console.error(...a);  // printed text goes to error_output, keeping the result line clean
              let out;
              try {
                const full = path.resolve(file);
                let target = require(full);
                for (const part of name.split('.')) target = target == null ? undefined : target[part];
                if (typeof target !== 'function') {
                  globalThis.module = { exports: {} }; globalThis.exports = globalThis.module.exports; globalThis.require = require;
                  vm.runInThisContext(fs.readFileSync(full, 'utf8'), { filename: full });
                  target = globalThis;
                  for (const part of name.split('.')) target = target == null ? undefined : target[part];
                }
                if (typeof target !== 'function') throw new TypeError(name + ' is not a function');
                const result = await target(...(payload.args || []));
                out = { ok: true, result: result === undefined ? null : result };
              } catch (e) {
                out = { ok: false, type: (e && e.name) || 'Error', message: String(e && e.message !== undefined ? e.message : e), trace: String((e && e.stack) || '').slice(-2000) };
              }
              process.stdout.write('\\n' + MARK + JSON.stringify(out) + '\\n');
            });
            """;

    @Action(id = "run_javascript_function", name = "Run JavaScript function", category = "Scripts", timeoutSeconds = 3600,
            description = "Call one function in a JavaScript file with Node.js (or Windows' built-in JScript when Node.js is not installed), passing JSON-compatible arguments, and return what it returns (promises are awaited with Node.js).",
            panel = "(Code: Use a file = file / Type it here = script), function, arguments | timeout_seconds, fail_on_error, interpreter")
    @Output(name = "result", description = "Value returned by the function (numbers, text, lists and dictionaries).")
    @Output(name = "output", type = OoaType.STRING, description = "Text the function printed.")
    @Output(name = "error", type = OoaType.STRING, description = "Error thrown by the function when fail_on_error is false; empty on success.")
    public Map<String, Object> runJavaScriptFunction(ActionContext ctx,
            @Input(name = "file", type = OoaType.FILE, required = false, visibleWhen = "script=", description = "JavaScript file (.js) that defines or exports the function.") String file,
            @Input(name = "function", type = OoaType.STRING, example = "calculateTotal", description = "Function name; use object.method for a method of an exported object.") String function,
            @Input(name = "arguments", type = OoaType.LIST, required = false, description = "Arguments, in order.") List<Object> args,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "300", min = 1, description = "Maximum run time.") double timeout,
            @Input(name = "fail_on_error", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Fail the step when the function throws; when false the error is returned in error.") boolean fail,
            @Input(name = "interpreter", type = OoaType.STRING, required = false, description = "Node.js program to use; node on the PATH when empty.") String interpreter,
            @Input(name = "script", type = OoaType.STRING, required = false, visibleWhen = "file=", description = "Code that defines the function, typed in the agent, instead of a file.") String script) {
        Path js = functionSource(ctx, file, script, ".js", "JavaScript");
        try { return runJavaScriptFunction(ctx, js, function, args, timeout, fail, interpreter); }
        finally { if (script != null && !script.isBlank()) try { Files.deleteIfExists(js); } catch (IOException ignored) { } }
    }

    public Map<String, Object> runJavaScriptFunction(ActionContext ctx, String file, String function, List<Object> args, double timeout, boolean fail, String interpreter) {
        return runJavaScriptFunction(ctx, file, function, args, timeout, fail, interpreter, null);
    }

    private Map<String, Object> runJavaScriptFunction(ActionContext ctx, Path js, String function, List<Object> args, double timeout, boolean fail, String interpreter) {
        if (function == null || !function.matches("^[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*$"))
            throw new ActionException("SCRIPT.BAD_FUNCTION", "Invalid function name '" + function + "'", "Give a plain JavaScript name such as calculateTotal.");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("args", Values.toJsonable(args == null ? List.of() : args));
        Map<String, Object> r;
        try { r = run(ctx, interpreters(interpreter, List.of("node")), List.of(), ".js", JS_WRAPPER, null, List.of(js.toString(), function), JsonLite.write(payload), timeout, false, null, null); }
        catch (ActionException e) {
            if (!"SCRIPT.INTERPRETER_NOT_FOUND".equals(e.code()) || !ProcessRunner.WINDOWS || (interpreter != null && !interpreter.isBlank())) throw e;
            ctx.info("Node.js is not installed; calling the function with Windows JScript (promises and Node modules are not available there)");
            r = jscript(ctx, js, null, function, args, null, null, timeout, false, null, null);
        }
        return functionResult(r, "JavaScript", "SCRIPT.JAVASCRIPT_ERROR", fail);
    }

    /** VBScript wrapper: runs the user's file with ExecuteGlobal, calls one function with text arguments and echoes the result after a marker. */
    static final String VBS_WRAPPER = String.join("\r\n",
            "Option Explicit",
            "Dim fso, src, env, i, n, expr, result, msg, a",
            "Set fso = CreateObject(\"Scripting.FileSystemObject\")",
            "src = fso.OpenTextFile(WScript.Arguments(0), 1).ReadAll",
            "ExecuteGlobal src",
            "Set env = CreateObject(\"WScript.Shell\").Environment(\"PROCESS\")",
            "n = CInt(env(\"OOA_VBS_ARGC\"))",
            "expr = WScript.Arguments(1) & \"(\"",
            "For i = 0 To n - 1",
            "  If i > 0 Then expr = expr & \", \"",
            "  a = env(\"OOA_VBS_ARG_\" & i)",
            // n = number (invariant literal), b = True/False, e = Empty, anything else is a quoted string
            "  Select Case Left(a, 1)",
            "    Case \"n\", \"b\": expr = expr & Mid(a, 2)",
            "    Case \"e\": expr = expr & \"Empty\"",
            "    Case Else: expr = expr & \"\"\"\" & Replace(Mid(a, 2), \"\"\"\", \"\"\"\"\"\") & \"\"\"\"",
            "  End Select",
            "Next",
            "expr = expr & \")\"",
            "On Error Resume Next",
            "result = Eval(expr)",
            "If Err.Number <> 0 Then",
            "  msg = \"Error \" & Err.Number & \": \" & Err.Description",
            "  Err.Clear",
            "  WScript.StdOut.WriteLine \"" + "__OOA_ERROR__" + "\" & msg",
            "Else",
            "  WScript.StdOut.WriteLine \"" + "__OOA_RESULT__" + "\" & CStr(result)",
            "End If", "");

    @Action(id = "run_vbscript_function", name = "Run VBScript function", category = "Scripts", timeoutSeconds = 3600,
            description = "Call one function in a VBScript file and return its result as text (Windows only).",
            panel = "(Code: Use a file = file / Type it here = script), function, arguments | timeout_seconds, fail_on_error")
    @Output(name = "result", type = OoaType.STRING, description = "Value returned by the function, as text.")
    @Output(name = "output", type = OoaType.STRING, description = "Other text the script wrote.")
    @Output(name = "error", type = OoaType.STRING, description = "Error raised by the function when fail_on_error is false; empty on success.")
    public Map<String, Object> runVbScriptFunction(ActionContext ctx,
            @Input(name = "file", type = OoaType.FILE, required = false, visibleWhen = "script=", description = "VBScript file (.vbs) that defines the function.") String file,
            @Input(name = "function", type = OoaType.STRING, example = "FormatInvoice", description = "Function name.") String function,
            @Input(name = "arguments", type = OoaType.LIST, required = false, description = "Arguments, in order: numbers and true/false are passed as VBScript numbers and booleans, empty values as Empty, everything else as text.") List<Object> args,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "300", min = 1, description = "Maximum run time.") double timeout,
            @Input(name = "fail_on_error", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Fail the step when the function raises an error; when false the error is returned in error.") boolean fail,
            @Input(name = "script", type = OoaType.STRING, required = false, visibleWhen = "file=", description = "Code that defines the function, typed in the agent, instead of a file.") String script) {
        Path vbs = functionSource(ctx, file, script, ".vbs", "VBScript");
        try { return runVbScriptFunction(ctx, vbs, function, args, timeout, fail); }
        finally { if (script != null && !script.isBlank()) try { Files.deleteIfExists(vbs); } catch (IOException ignored) { } }
    }

    public Map<String, Object> runVbScriptFunction(ActionContext ctx, String file, String function, List<Object> args, double timeout, boolean fail) {
        return runVbScriptFunction(ctx, file, function, args, timeout, fail, null);
    }

    /** A VBScript numeric literal (locale-independent); negative numbers are bracketed, very long ones written as 1.5E+20. */
    static String vbsNumber(Number num) {
        java.math.BigDecimal d = num instanceof java.math.BigDecimal bd ? bd : new java.math.BigDecimal(num.toString());
        String t = d.stripTrailingZeros().toPlainString();
        if (t.replace("-", "").replace(".", "").length() > 15) t = new java.math.BigDecimal(d.doubleValue()).round(java.math.MathContext.DECIMAL64).stripTrailingZeros().toString();
        return d.signum() < 0 ? "(" + t + ")" : t;
    }

    private Map<String, Object> runVbScriptFunction(ActionContext ctx, Path vbs, String function, List<Object> args, double timeout, boolean fail) {
        if (function == null || !function.matches("^[A-Za-z][A-Za-z0-9_]{0,127}$"))
            throw new ActionException("SCRIPT.BAD_FUNCTION", "Invalid function name '" + function + "'", "Give a plain VBScript name such as FormatInvoice.");
        if (!ProcessRunner.WINDOWS) throw new ActionException("SYSTEM.WINDOWS_ONLY", "VBScript runs only on Windows", "Run this agent on a Windows runner.");
        // arguments travel in environment variables: Windows command-line quoting would lose quotes inside them
        Map<String, Object> env = new LinkedHashMap<>();
        int n = args == null ? 0 : args.size();
        if (n > 64) throw new ActionException("SCRIPT.BAD_ARGUMENT", "At most 64 arguments can be passed to a VBScript function", "Pass at most 64 arguments; put more in a file or a list the function reads.");
        for (int i = 0; i < n; i++) {
            Object a = args.get(i);
            // numbers and true/false arrive as VBScript numbers and booleans (Add(2, 3) is 5, not "23"); the first letter
            // says which, and also keeps empty values (Windows drops empty variables)
            if (a == null) { env.put("OOA_VBS_ARG_" + i, "e"); continue; }
            if (a instanceof Boolean b) { env.put("OOA_VBS_ARG_" + i, "b" + (b ? "True" : "False")); continue; }
            if (a instanceof Number num) { env.put("OOA_VBS_ARG_" + i, "n" + vbsNumber(num)); continue; }
            String t = Values.text(a);
            if (t.indexOf('\n') >= 0 || t.indexOf('\r') >= 0) throw new ActionException("SCRIPT.BAD_ARGUMENT", "VBScript function arguments cannot contain line breaks", "Pass multi-line text through a file instead.");
            env.put("OOA_VBS_ARG_" + i, "s" + t);
        }
        env.put("OOA_VBS_ARGC", String.valueOf(n));
        Map<String, Object> r = run(ctx, List.of("cscript"), List.of("//NoLogo"), ".vbs", VBS_WRAPPER, null, List.of(vbs.toString(), function), null, timeout, false, null, env);
        String out = String.valueOf(r.get("output"));
        int ok = out.lastIndexOf("__OOA_RESULT__"), bad = out.lastIndexOf("__OOA_ERROR__");
        Map<String, Object> res = new LinkedHashMap<>();
        if (ok < 0 && bad < 0) throw new ActionException("SCRIPT.FAILED", "VBScript did not return a result (exit code " + r.get("exit_code") + "): " + ApplicationPackage.tail(String.valueOf(r.get("error_output")) + out),
                "Check the file runs on its own with cscript.");
        int at = Math.max(ok, bad);
        String line = out.substring(at).split("\r?\n", 2)[0];
        String before = out.substring(0, at).strip();
        if (bad > ok) {
            String msg = line.substring("__OOA_ERROR__".length()).strip();
            if (fail) throw new ActionException("SCRIPT.VBSCRIPT_ERROR", msg, "Fix the VBScript function or its arguments.");
            res.put("result", ""); res.put("output", before); res.put("error", msg);
            return res;
        }
        res.put("result", line.substring("__OOA_RESULT__".length()));
        res.put("output", before);
        res.put("error", "");
        return res;
    }
}
