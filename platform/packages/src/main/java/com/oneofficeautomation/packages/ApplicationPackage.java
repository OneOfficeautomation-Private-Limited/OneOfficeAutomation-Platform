package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;

@OoaPackage(id = "ooa.application", name = "Application", version = "1.0.0", description = "Open files, start, run and stop programs on the runner.")
public final class ApplicationPackage {

    private static List<String> command(String program, List<Object> args) {
        List<String> cmd = new ArrayList<>();
        cmd.add(program);
        if (args != null) for (Object a : args) cmd.add(Values.text(a));
        return cmd;
    }

    @Action(id = "open_file", name = "Open file", category = "Application", capabilities = "desktop",
            description = "Open a document with its default application, e.g. a PDF in the PDF viewer.")
    public Map<String, Object> openFile(ActionContext ctx, @Input(name = "path", type = OoaType.FILE, description = "File to open.") String path) {
        Path p = ctx.resolvePath(path);
        if (!Files.exists(p)) throw new ActionException("FILE.NOT_FOUND", "File not found: " + p, "Check the path.");
        if (GraphicsEnvironment.isHeadless() || !Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN))
            throw new ActionException("DESKTOP.NO_SESSION", "No desktop session to open files in", "Run this agent on a runner with a signed-in desktop (attended, or unattended with auto-login).");
        try { Desktop.getDesktop().open(p.toFile()); } catch (IOException e) { throw new ActionException("APPLICATION.OPEN_FAILED", e.getMessage(), "Check a default application is set for this file type."); }
        return Map.of();
    }

    @Action(id = "start_program", name = "Start program", category = "Application", capabilities = "desktop",
            description = "Start a program and continue without waiting; returns its process id.")
    @Output(name = "process_id", type = OoaType.NUMBER, description = "Process id, used by Stop program.")
    public Map<String, Object> startProgram(ActionContext ctx,
            @Input(name = "program", type = OoaType.STRING, example = "notepad.exe", description = "Program name or full path.") String program,
            @Input(name = "arguments", type = OoaType.LIST, required = false, description = "Arguments, one list item each.") List<Object> args,
            @Input(name = "working_folder", type = OoaType.FILE, required = false, description = "Folder to start in.") String dir,
            @Input(name = "window_state", type = OoaType.STRING, required = false, defaultValue = "\"normal\"", enumValues = {"normal", "minimized", "maximized", "hidden"},
                    description = "How the program's main window opens (Windows); other systems always use normal.") String windowState,
            @Input(name = "environment", type = OoaType.DICTIONARY, required = false, description = "Extra environment variables for the program; an empty value removes one.") Map<String, Object> environment,
            @Input(name = "wait_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0, description = "Pause this long after starting so the program can open its window; 0 continues at once.") double waitSeconds) {
        String state = windowState == null || windowState.isBlank() ? "normal" : windowState.strip().toLowerCase(Locale.ROOT);
        if (!List.of("normal", "minimized", "maximized", "hidden").contains(state))
            throw new ActionException("APPLICATION.BAD_WINDOW_STATE", "Unknown window_state '" + windowState + "'", "Use normal, minimized, maximized or hidden.");
        Map<String, String> env = env(environment);
        long pid;
        if (!"normal".equals(state) && ProcessRunner.WINDOWS) pid = startWithWindowState(ctx, program, args, dir, state, env);
        else {
            if (!"normal".equals(state)) ctx.warn("window_state " + state + " is supported on Windows only; the program opens normally");
            ProcessBuilder pb = new ProcessBuilder(command(program, args));
            if (dir != null) pb.directory(ctx.resolvePath(dir).toFile());
            applyEnv(pb.environment(), env);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
            try { pid = pb.start().pid(); }
            catch (IOException e) { throw new ActionException("PROCESS.NOT_FOUND", "Cannot start '" + program + "': " + e.getMessage(), "Check the program is installed or give its full path."); }
        }
        if (waitSeconds > 0) {
            long end = System.currentTimeMillis() + (long) (Math.min(600, waitSeconds) * 1000);
            while (System.currentTimeMillis() < end) {
                ctx.checkCancelled();
                try { Thread.sleep(Math.min(200, Math.max(1, end - System.currentTimeMillis()))); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        }
        return Map.of("process_id", BigDecimal.valueOf(pid));
    }

    static Map<String, String> env(Map<String, Object> environment) {
        Map<String, String> env = new LinkedHashMap<>();
        if (environment != null) environment.forEach((k, v) -> {
            if (k == null || !k.matches("^[A-Za-z_][A-Za-z0-9_.()]{0,254}$"))
                throw new ActionException("APPLICATION.BAD_VARIABLE_NAME", "Invalid environment variable name '" + k + "'", "Use letters, digits and underscores.");
            env.put(k, v == null ? "" : v instanceof SecureString s ? s.reveal() : Values.text(v));
        });
        return env;
    }

    static void applyEnv(Map<String, String> target, Map<String, String> env) {
        env.forEach((k, v) -> { if (v.isEmpty()) target.remove(k); else target.put(k, v); });
    }

    /** Start-Process with a window style; program, arguments and folder arrive in environment variables, never inside the script. */
    static final String START_SCRIPT = "$ErrorActionPreference='Stop'; $a = @(ConvertFrom-Json $env:OOA_APP_ARGS); "
            + "$p = @{ FilePath = $env:OOA_APP_PROGRAM; WindowStyle = $env:OOA_APP_STYLE; PassThru = $true }; "
            + "if ($a.Count -gt 0) { $p.ArgumentList = @($a | ForEach-Object { if ($_ -match '[\\s\"]') { '\"' + ($_ -replace '\"', '\\\"') + '\"' } else { $_ } }) }; "
            + "if ($env:OOA_APP_DIR) { $p.WorkingDirectory = $env:OOA_APP_DIR }; (Start-Process @p).Id";

    static String windowStyle(String state) {
        return switch (state) { case "minimized" -> "Minimized"; case "maximized" -> "Maximized"; case "hidden" -> "Hidden"; default -> "Normal"; };
    }

    private static long startWithWindowState(ActionContext ctx, String program, List<Object> args, String dir, String state, Map<String, String> extraEnv) {
        Map<String, String> env = new LinkedHashMap<>(extraEnv);
        env.put("OOA_APP_PROGRAM", program);
        List<Object> a = new ArrayList<>();
        if (args != null) for (Object o : args) a.add(Values.text(o));
        env.put("OOA_APP_ARGS", JsonLite.write(a));
        env.put("OOA_APP_STYLE", windowStyle(state));
        env.put("OOA_APP_DIR", dir == null ? "" : ctx.resolvePath(dir).toString());
        ProcessRunner.Result r = ProcessRunner.run(ctx, ScriptPackage.powershellCommand(START_SCRIPT), null, null, 60, env, java.nio.charset.StandardCharsets.UTF_8);
        String out = r.stdout().strip();
        if (r.exitCode() != 0 || !out.matches("\\d+"))
            throw new ActionException("PROCESS.NOT_FOUND", "Cannot start '" + program + "': " + tail(r.stderr() + out), "Check the program is installed or give its full path.");
        return Long.parseLong(out);
    }

    @Action(id = "run_program", name = "Run program and wait", category = "Application", timeoutSeconds = 3600,
            description = "Run a program, wait for it to finish and return its exit code and output.")
    @Output(name = "exit_code", type = OoaType.NUMBER, description = "Exit code; 0 usually means success.")
    @Output(name = "output", type = OoaType.STRING, description = "Standard output.")
    @Output(name = "error_output", type = OoaType.STRING, description = "Standard error.")
    public Map<String, Object> runProgram(ActionContext ctx,
            @Input(name = "program", type = OoaType.STRING, description = "Program name or full path.") String program,
            @Input(name = "arguments", type = OoaType.LIST, required = false, description = "Arguments, one list item each (no shell quoting needed).") List<Object> args,
            @Input(name = "working_folder", type = OoaType.FILE, required = false, description = "Folder to run in; the workspace when empty.") String dir,
            @Input(name = "input", type = OoaType.STRING, required = false, description = "Text sent to the program's standard input.") String input,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "300", min = 0, description = "Maximum run time.") double timeout,
            @Input(name = "fail_on_error", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Fail the step when the exit code is not 0.") boolean fail,
            @Input(name = "environment", type = OoaType.DICTIONARY, required = false, description = "Extra environment variables for the program.") Map<String, Object> environment,
            @Input(name = "output_encoding", type = OoaType.STRING, required = false, defaultValue = "\"utf-8\"", example = "cp850", description = "Text encoding of the program's input and output, e.g. utf-8, cp850, windows-1252.") String encoding) {
        java.nio.charset.Charset cs = FilePackage.charset(encoding);
        Map<String, String> env = env(environment);
        ProcessRunner.Result r = ProcessRunner.run(ctx, command(program, args), dir == null ? ctx.workDir() : ctx.resolvePath(dir), input, timeout, env.isEmpty() ? null : env, cs);
        if (fail && r.exitCode() != 0)
            throw new ActionException("PROCESS.FAILED", program + " exited with code " + r.exitCode() + ": " + tail(r.stderr().isBlank() ? r.stdout() : r.stderr()),
                    "Check the arguments, or set fail_on_error to false to handle the exit code yourself.", false, Map.of("exit_code", BigDecimal.valueOf(r.exitCode())));
        return r.toMap();
    }

    static String tail(String s) { String t = s.strip(); return t.length() > 400 ? "..." + t.substring(t.length() - 400) : t; }

    @Action(id = "stop_program", name = "Stop program", category = "Application",
            description = "Stop a program by process id or by program name (e.g. EXCEL.EXE).")
    @Output(name = "stopped", type = OoaType.NUMBER, description = "Number of processes stopped.")
    public Map<String, Object> stopProgram(ActionContext ctx,
            @Input(name = "process_id", type = OoaType.NUMBER, required = false, visibleWhen = "name=", description = "Process id from Start program.") Long pid,
            @Input(name = "name", type = OoaType.STRING, required = false, visibleWhen = "process_id=", description = "Program file name, e.g. notepad.exe.") String name,
            @Input(name = "force", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Force-stop if it does not close normally.") boolean force) {
        long self = ProcessHandle.current().pid();
        List<ProcessHandle> targets = new ArrayList<>();
        if (pid != null) ProcessHandle.of(pid).ifPresent(targets::add);
        else if (name != null && !name.isBlank()) ProcessHandle.allProcesses().filter(h -> matches(h, name)).forEach(targets::add);
        else throw new ActionException("APPLICATION.NO_TARGET", "Give a process id or a program name");
        int n = 0;
        for (ProcessHandle h : targets) {
            if (h.pid() == self) continue;
            boolean ok = force ? h.destroyForcibly() : h.destroy();
            if (ok) n++;
        }
        return Map.of("stopped", BigDecimal.valueOf(n));
    }

    private static boolean matches(ProcessHandle h, String name) {
        return h.info().command().map(c -> Path.of(c).getFileName().toString().equalsIgnoreCase(name)).orElse(false);
    }

    @Condition(id = "is_running", name = "Program is running", description = "True when a process with this program file name is running.")
    public boolean isRunning(ActionContext ctx, @Input(name = "name", type = OoaType.STRING, description = "Program file name, e.g. EXCEL.EXE.") String name) {
        return ProcessHandle.allProcesses().anyMatch(h -> matches(h, name));
    }
}
