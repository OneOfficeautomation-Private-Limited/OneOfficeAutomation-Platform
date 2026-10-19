package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;

@OoaPackage(id = "ooa.application", name = "Application", version = "1.0.0", description = "Open files, start, run and stop programs on the device.")
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
            throw new ActionException("DESKTOP.NO_SESSION", "No desktop session to open files in", "Run this bot on a device with a signed-in desktop (attended, or unattended with auto-login).");
        try { Desktop.getDesktop().open(p.toFile()); } catch (IOException e) { throw new ActionException("APPLICATION.OPEN_FAILED", e.getMessage(), "Check a default application is set for this file type."); }
        return Map.of();
    }

    @Action(id = "start_program", name = "Start program", category = "Application", capabilities = "desktop",
            description = "Start a program and continue without waiting; returns its process id.")
    @Output(name = "process_id", type = OoaType.NUMBER, description = "Process id, used by Stop program.")
    public Map<String, Object> startProgram(ActionContext ctx,
            @Input(name = "program", type = OoaType.STRING, example = "notepad.exe", description = "Program name or full path.") String program,
            @Input(name = "arguments", type = OoaType.LIST, required = false, description = "Arguments, one list item each.") List<Object> args,
            @Input(name = "working_folder", type = OoaType.FILE, required = false, description = "Folder to start in.") String dir) {
        ProcessBuilder pb = new ProcessBuilder(command(program, args));
        if (dir != null) pb.directory(ctx.resolvePath(dir).toFile());
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
        try { return Map.of("process_id", BigDecimal.valueOf(pb.start().pid())); }
        catch (IOException e) { throw new ActionException("PROCESS.NOT_FOUND", "Cannot start '" + program + "': " + e.getMessage(), "Check the program is installed or give its full path."); }
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
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "300", description = "Maximum run time.") double timeout,
            @Input(name = "fail_on_error", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Fail the step when the exit code is not 0.") boolean fail) {
        ProcessRunner.Result r = ProcessRunner.run(ctx, command(program, args), dir == null ? ctx.workDir() : ctx.resolvePath(dir), input, timeout);
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
            @Input(name = "process_id", type = OoaType.NUMBER, required = false, description = "Process id from Start program.") Long pid,
            @Input(name = "name", type = OoaType.STRING, required = false, description = "Program file name, e.g. notepad.exe.") String name,
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
