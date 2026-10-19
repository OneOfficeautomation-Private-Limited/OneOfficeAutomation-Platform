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
}
