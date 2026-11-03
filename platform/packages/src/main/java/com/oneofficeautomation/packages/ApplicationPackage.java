package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

@OoaPackage(id = "ooa.application", name = "Application", version = "1.0.0", description = "Open files, start, run and stop programs on the runner.")
public final class ApplicationPackage {

    /**
     * cmd.exe re-reads the whole command line of cmd.exe and of .bat/.cmd files, so a data argument such as a file name
     * "a & del x" would run a second command ("BatBadBut"). Unless the agent says it means shell operators, arguments with
     * & | < > ^ or quotes, and line breaks always, are refused for those programs.
     */
    static void checkShellArguments(String program, List<Object> args, boolean allowOperators) {
        if (program == null || args == null) return;
        String exe = program.strip().replace("\"", "");
        String base = exe.substring(Math.max(exe.lastIndexOf('/'), exe.lastIndexOf('\\')) + 1).toLowerCase(Locale.ROOT);
        boolean viaCmd = base.equals("cmd") || base.equals("cmd.exe") || base.endsWith(".bat") || base.endsWith(".cmd");
        if (!viaCmd) return;
        for (int i = 0; i < args.size(); i++) {
            String a = Values.text(args.get(i));
            if (a.indexOf('\n') >= 0 || a.indexOf('\r') >= 0 || a.indexOf('\0') >= 0)
                throw new ActionException("APPLICATION.UNSAFE_ARGUMENT", "Argument " + (i + 1) + " has a line break, which " + base + " would run as a new command",
                        "Pass multi-line data through input or a file.");
            if (!allowOperators && a.matches("(?s).*[&|<>^\"].*"))
                throw new ActionException("APPLICATION.UNSAFE_ARGUMENT", "Argument " + (i + 1) + " ('" + (a.length() > 60 ? a.substring(0, 60) + "..." : a)
                        + "') contains & | < > ^ or a quote, which " + base + " reads as a command operator, so it could run another command",
                        "Pass data through input or environment variables (read them as %NAME% in the script), or set allow_shell_operators to true when the operator is intended.");
        }
    }

    private static List<String> command(String program, List<Object> args) {
        List<String> cmd = new ArrayList<>();
        cmd.add(program);
        if (args != null) for (Object a : args) cmd.add(Values.text(a));
        return cmd;
    }

    @Action(id = "open_file", name = "Open file", category = "Application", capabilities = "desktop",
            description = "Open a document with its default application, e.g. a PDF in the PDF viewer.",
            panel = "path")
    public Map<String, Object> openFile(ActionContext ctx, @Input(name = "path", type = OoaType.FILE, description = "File to open.") String path) {
        Path p = ctx.resolvePath(path);
        if (!Files.exists(p)) throw new ActionException("FILE.NOT_FOUND", "File not found: " + p, "Check the path.");
        if (GraphicsEnvironment.isHeadless() || !Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN))
            throw new ActionException("DESKTOP.NO_SESSION", "No desktop session to open files in", "Run this agent on a runner with a signed-in desktop (attended, or unattended with auto-login).");
        try { Desktop.getDesktop().open(p.toFile()); } catch (IOException e) { throw new ActionException("APPLICATION.OPEN_FAILED", e.getMessage(), "Check a default application is set for this file type."); }
        return Map.of();
    }

    @Action(id = "start_program", name = "Start program", category = "Application", capabilities = "desktop",
            description = "Start a program and continue without waiting; returns its process id.",
            panel = "program, working_folder, arguments, wait_seconds | window_state, environment, allow_shell_operators")
    @Output(name = "process_id", type = OoaType.NUMBER, description = "Process id, used by Stop program.")
    public Map<String, Object> startProgram(ActionContext ctx,
            @Input(name = "program", type = OoaType.STRING, example = "notepad.exe", description = "Program name or full path.") String program,
            @Input(name = "arguments", type = OoaType.LIST, required = false, description = "Arguments, one list item each.") List<Object> args,
            @Input(name = "working_folder", type = OoaType.FILE, required = false, description = "Folder to start in.") String dir,
            @Input(name = "window_state", type = OoaType.STRING, required = false, defaultValue = "\"normal\"", enumValues = {"normal", "minimized", "maximized", "hidden"},
                    description = "How the program's main window opens (Windows); other systems always use normal.") String windowState,
            @Input(name = "environment", type = OoaType.DICTIONARY, required = false, description = "Extra environment variables for the program; an empty value removes one.") Map<String, Object> environment,
            @Input(name = "wait_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0, description = "Pause this long after starting so the program can open its window; 0 continues at once.") double waitSeconds,
            @Input(name = "allow_shell_operators", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = ALLOW_OPS) boolean allowOperators) {
        checkShellArguments(program, args, allowOperators);
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
            description = "Run a program, wait for it to finish and return its exit code and output.",
            panel = "program, arguments, working_folder | input, timeout_seconds, fail_on_error, environment, output_encoding, allow_shell_operators")
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
            @Input(name = "output_encoding", type = OoaType.STRING, required = false, defaultValue = "\"utf-8\"", example = "cp850", description = "Text encoding of the program's input and output, e.g. utf-8, cp850, windows-1252.") String encoding,
            @Input(name = "allow_shell_operators", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = ALLOW_OPS) boolean allowOperators) {
        checkShellArguments(program, args, allowOperators);
        java.nio.charset.Charset cs = FilePackage.charset(encoding);
        Map<String, String> env = env(environment);
        ProcessRunner.Result r = ProcessRunner.run(ctx, command(program, args), dir == null ? ctx.workDir() : ctx.resolvePath(dir), input, timeout, env.isEmpty() ? null : env, cs);
        if (fail && r.exitCode() != 0)
            throw new ActionException("PROCESS.FAILED", program + " exited with code " + r.exitCode() + ": " + tail(r.stderr().isBlank() ? r.stdout() : r.stderr()),
                    "Check the arguments, or set fail_on_error to false to handle the exit code yourself.", false, Map.of("exit_code", BigDecimal.valueOf(r.exitCode())));
        return r.toMap();
    }

    private static final String ALLOW_OPS = "For cmd.exe and .bat/.cmd files only: let arguments contain & | < > ^ or quotes, which cmd reads as command operators. "
            + "Leave off when arguments carry data (file names, e-mail subjects): such characters are then refused, so data can never run a command.";

    static String tail(String s) { String t = s.strip(); return t.length() > 400 ? "..." + t.substring(t.length() - 400) : t; }

    @Action(id = "stop_program", name = "Stop program", category = "Application",
            description = "Stop a program by process id or by program name (e.g. EXCEL.EXE).",
            panel = "(Program to stop: By process id = process_id / By name = name), force")
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

    // ----- command sessions: a console program kept open across steps (write a line, read until a prompt) -----

    private static final String D_SESSION = "Name of the command session, from Open command session.";

    @Action(id = "open_session", name = "Open command session", category = "Application", timeoutSeconds = 120,
            description = "Start a console program such as cmd.exe or powershell.exe and keep it open, so later steps can write to it and read its output.",
            panel = "session, program, arguments, working_folder | output_encoding, environment")
    @Output(name = "process_id", type = OoaType.NUMBER, description = "Process id of the program.")
    public Map<String, Object> openSession(ActionContext ctx,
            @Input(name = "program", type = OoaType.STRING, required = false, defaultValue = "\"cmd.exe\"", example = "powershell.exe",
                    description = "Console program to run: cmd.exe, powershell.exe or any program that reads its standard input.") String program,
            @Input(name = "arguments", type = OoaType.LIST, required = false,
                    description = "Arguments, one list item each. For powershell.exe or pwsh with none, it reads commands from this session (-NoLogo -NoProfile -Command -).") List<Object> args,
            @Input(name = "working_folder", type = OoaType.FILE, required = false, description = "Folder to start in; the workspace when empty.") String dir,
            @Input(name = "output_encoding", type = OoaType.STRING, required = false, defaultValue = "\"console\"", example = "utf-8",
                    description = "Text encoding of what is written and read: console (the code page console programs use here), utf-8 (any language; the program runs in a UTF-8 console, but cmd.exe itself can then only take ASCII typed text), or a name such as cp850.") String encoding,
            @Input(name = "environment", type = OoaType.DICTIONARY, required = false, description = "Extra environment variables for the program; an empty value removes one.") Map<String, Object> environment,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"cmd\"", description = "Name for this session; later steps use it.") String session) {
        String prog = program == null || program.isBlank() ? (ProcessRunner.WINDOWS ? "cmd.exe" : "sh") : program.strip();
        String name = session == null || session.isBlank() ? "cmd" : session.strip();
        checkShellArguments(prog, args, false);
        String enc = encoding == null || encoding.isBlank() ? "console" : encoding.strip().toLowerCase(Locale.ROOT);
        boolean utf8 = enc.equals("utf-8") || enc.equals("utf8");
        java.nio.charset.Charset cs = enc.equals("console") ? CommandSession.consoleCharset() : FilePackage.charset(enc);
        List<Object> a = args == null || args.isEmpty() && CommandSession.isPowerShell(prog)
                ? (CommandSession.isPowerShell(prog) ? List.of("-NoLogo", "-NoProfile", "-Command", "-") : List.of()) : args;
        List<String> cmdLine = command(prog, a);
        if (utf8 && ProcessRunner.WINDOWS && !CommandSession.isCmd(prog)) {
            // a console program reads its input in the console's code page, fixed when it starts: start it from a cmd.exe
            // switched to 65001 so it inherits a UTF-8 console. Its arguments then pass through cmd, so operators are refused.
            List<Object> all = new ArrayList<>(); all.add(prog); all.addAll(a);
            checkShellArguments("cmd.exe", all, false);
            List<String> wrapped = new ArrayList<>(List.of("cmd.exe", "/d", "/c", "chcp", "65001", ">nul", "&"));
            wrapped.addAll(cmdLine);
            cmdLine = wrapped;
        }
        ProcessBuilder pb = new ProcessBuilder(cmdLine).redirectErrorStream(true);
        pb.directory((dir == null || dir.isBlank() ? ctx.workDir() : ctx.resolvePath(dir)).toFile());
        Object runEnv = ctx.sessions().get(ProcessRunner.RUN_ENV_SESSION);
        if (runEnv instanceof Map<?, ?> m) m.forEach((k, v) -> { if (v == null || String.valueOf(v).isEmpty()) pb.environment().remove(String.valueOf(k)); else pb.environment().put(String.valueOf(k), String.valueOf(v)); });
        applyEnv(pb.environment(), env(environment));
        Process p;
        try { p = pb.start(); }
        catch (IOException e) { throw new ActionException("PROCESS.NOT_FOUND", "Cannot start '" + prog + "': " + e.getMessage(), "Check the program is installed or give its full path."); }
        CommandSession s = new CommandSession(prog, p, cs);
        if (ctx.sessions().get(name) instanceof AutoCloseable old) {
            ctx.warn("Command session '" + name + "' was already open; it is closed and replaced");
            try { old.close(); } catch (Exception ignored) { }
        }
        ctx.sessions().put(name, s);
        if (utf8 && CommandSession.isCmd(prog)) {
            // cmd.exe reads and writes a pipe in the console's code page; 65001 makes both UTF-8. Its echo of this line is skipped.
            try {
                s.write("chcp 65001>nul" + System.lineSeparator());
                s.read(Pattern.compile("chcp 65001>nul\\r?\\n"), false, System.nanoTime() + 10_000_000_000L, 0, false, ctx::checkCancelled);
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                s.close(); ctx.sessions().remove(name);
                throw new ActionException("COMMAND.EXITED", "'" + prog + "' ended as it started", "Check the program and its arguments.");
            }
        }
        ctx.info("Opened command session '" + name + "' (" + prog + ", " + cs.name() + ")");
        return Map.of("process_id", BigDecimal.valueOf(p.pid()));
    }

    static CommandSession commandSession(ActionContext ctx, String session) {
        String name = ctx.sessionNameOrOnly(session, "cmd", CommandSession.class);
        if (!(ctx.sessions().get(name) instanceof CommandSession s))
            throw new ActionException("COMMAND.NO_SESSION", "No command session '" + name + "' is open", "Add an Open command session step with this session name before this step.");
        return s;
    }

    @Action(id = "write_session", name = "Write to command session", category = "Application",
            description = "Send a line of text, or a password from the vault, to the program of a command session, as if typed.",
            panel = "session, (What to send: Text = text / Secret from the vault = secret), press_enter")
    public Map<String, Object> writeSession(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"cmd\"", description = D_SESSION) String session,
            @Input(name = "text", type = OoaType.STRING, required = false, visibleWhen = "secret=", example = "dir /b", description = "Text to send, e.g. a command or an answer to a question.") String text,
            @Input(name = "secret", type = OoaType.CREDENTIAL, required = false, visibleWhen = "text=",
                    description = "A Credential to send instead, e.g. at a password prompt. It is never logged, and if the program prints it back, reads show **** instead.") Object secret,
            @Input(name = "press_enter", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "End with Enter, so the program takes the line.") boolean enter) {
        CommandSession s = commandSession(ctx, session);
        String value;
        if (secret != null && !(secret instanceof String str && str.isEmpty())) {
            value = secret instanceof SecureString ss ? ss.reveal() : Values.text(secret);
            s.addSecret(value);
        } else value = text == null ? "" : text;
        if (CommandSession.isCmd(s.program) && s.charset.equals(java.nio.charset.StandardCharsets.UTF_8) && !value.chars().allMatch(c -> c < 128))
            ctx.warn("cmd.exe reads typed text one byte at a time, so characters outside ASCII arrive as ?; its output is UTF-8. "
                    + "To type other languages use a powershell.exe session with output_encoding utf-8.");
        if (s.exited())
            throw new ActionException("COMMAND.EXITED", "The program of command session '" + session + "' has ended (exit code " + s.process.exitValue() + ")",
                    "Read the session's output to see why, or open the session again.");
        try { s.write(value + (enter ? System.lineSeparator() : "")); }
        catch (IOException e) {
            throw new ActionException("COMMAND.EXITED", "Cannot write to command session '" + session + "': the program has ended or stopped reading",
                    "Read the session's output to see why, or open the session again.");
        }
        return Map.of();
    }

    @Action(id = "read_session", name = "Read command session", category = "Application", timeoutSeconds = 3600,
            description = "Read what the program of a command session printed, optionally waiting until some text (e.g. its prompt) appears.",
            panel = "session, wait_for, match_type, timeout_seconds | from, fail_on_timeout")
    @Output(name = "output", type = OoaType.STRING, description = "The output read (up to the end of wait_for when it was found); secrets written to the session show as ****.")
    @Output(name = "matched", type = OoaType.BOOLEAN, description = "True when wait_for was found.")
    @Output(name = "exited", type = OoaType.BOOLEAN, description = "True when the program has ended.")
    @Output(name = "exit_code", type = OoaType.NUMBER, description = "The program's exit code once it has ended; -1 while it runs.")
    public Map<String, Object> readSession(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"cmd\"", description = D_SESSION) String session,
            @Input(name = "wait_for", type = OoaType.STRING, required = false, example = ">",
                    description = "Wait until this appears in the output, e.g. the prompt or a question. Empty: return what is there once the output has been quiet for half a second.") String waitFor,
            @Input(name = "match_type", type = OoaType.STRING, required = false, defaultValue = "\"text\"", enumValues = {"text", "regex"},
                    description = "How wait_for is matched: as plain text, or as a regular expression, e.g. [A-Z]:\\\\.*>$ for any cmd prompt.") String matchType,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "30", min = 0, max = 3500, description = "Longest time to wait.") double timeout,
            @Input(name = "from", type = OoaType.STRING, required = false, defaultValue = "\"new\"", enumValues = {"new", "all"},
                    description = "new: only output since the last read; all: everything the session has printed (the newest 2,000,000 characters).") String from,
            @Input(name = "fail_on_timeout", type = OoaType.BOOLEAN, required = false, defaultValue = "true",
                    description = "When wait_for does not appear in time (or the program ends first), fail the step; off: return with matched false.") boolean failOnTimeout) {
        CommandSession s = commandSession(ctx, session);
        Pattern pattern = null;
        if (waitFor != null && !waitFor.isEmpty()) {
            boolean regex = "regex".equalsIgnoreCase(matchType == null ? "" : matchType.strip());
            try { pattern = regex ? Pattern.compile(waitFor, Pattern.MULTILINE) : Pattern.compile(Pattern.quote(waitFor)); }
            catch (java.util.regex.PatternSyntaxException e) { throw new ActionException("COMMAND.BAD_PATTERN", "wait_for is not a valid regular expression: " + e.getDescription(), "Fix the expression, or set match_type to text."); }
        }
        boolean all = "all".equalsIgnoreCase(from == null ? "" : from.strip());
        long deadline = System.nanoTime() + (long) (Math.max(0, Math.min(3500, timeout)) * 1e9);
        CommandSession.Read r;
        try { r = s.read(pattern, all, deadline, 500, false, ctx::checkCancelled); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new ActionException("RUN.CANCELLED", "Interrupted"); }
        String out = ctx.redact(s.mask(r.output()));
        if (pattern != null && !r.matched() && failOnTimeout) {
            String why = r.exited() ? "the program ended (exit code " + s.process.exitValue() + ") before '" + waitFor + "' appeared"
                    : "'" + waitFor + "' did not appear within " + BigDecimal.valueOf(timeout).stripTrailingZeros().toPlainString() + " s";
            throw new ActionException(r.exited() ? "COMMAND.EXITED" : "COMMAND.TIMEOUT", "Command session '" + session + "': " + why + ". Last output: " + tail(out),
                    r.exited() ? "Read the output to see why the program ended." : "Raise timeout_seconds, check the text to wait for, or set fail_on_timeout to false.", !r.exited());
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("output", out);
        m.put("matched", r.matched());
        m.put("exited", r.exited());
        m.put("exit_code", BigDecimal.valueOf(r.exited() ? s.process.exitValue() : -1));
        return m;
    }

    @Action(id = "close_session", name = "Close command session", category = "Application",
            description = "End a command session: optionally ask the program to exit, then stop it and every program it started.",
            panel = "session, send_exit | wait_seconds")
    @Output(name = "exit_code", type = OoaType.NUMBER, description = "The program's exit code (1 when it had to be stopped).")
    public Map<String, Object> closeSession(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"cmd\"", description = D_SESSION) String session,
            @Input(name = "send_exit", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Send the line exit first, so the program can end by itself.") boolean sendExit,
            @Input(name = "wait_seconds", type = OoaType.NUMBER, required = false, defaultValue = "5", min = 0, max = 60, description = "How long to wait for it to end by itself before it is stopped.") double waitSeconds) {
        String name = ctx.sessionNameOrOnly(session, "cmd", CommandSession.class);
        CommandSession s = commandSession(ctx, name);
        try {
            if (sendExit && !s.exited()) {
                try { s.write("exit" + System.lineSeparator()); } catch (IOException ignored) { }
                s.waitForExit((long) (Math.max(0, Math.min(60, waitSeconds)) * 1000));
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        finally {
            s.close();
            ctx.sessions().remove(name);
        }
        int code;
        try { code = s.process.exitValue(); } catch (IllegalThreadStateException e) { code = -1; }
        return Map.of("exit_code", BigDecimal.valueOf(code));
    }

    @Condition(id = "is_running",name = "Program is running", description = "True when a process with this program file name is running.")
    public boolean isRunning(ActionContext ctx, @Input(name = "name", type = OoaType.STRING, description = "Program file name, e.g. EXCEL.EXE.") String name) {
        return ProcessHandle.allProcesses().anyMatch(h -> matches(h, name));
    }
}
