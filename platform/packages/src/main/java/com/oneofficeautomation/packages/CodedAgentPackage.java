package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Code-first agents (gap G70, after UiPath's coded workflows): an agent's work written as a Python module, kept in the
 * repository and in Git like any file, with a function main(inputs, ooa) that returns the outputs. The module calls any
 * toolkit command as ooa.call("ooa.excel/read_range", file="a.xlsx", ...), logs with ooa.log(...), and reads the
 * agent's inputs; the runner does each call as a step of the run (vault, connections, logs and audit as usual).
 * <ul>
 * <li>Only the toolkits listed in Toolkits it may call can be called, so One CommandCenter's switched-off toolkits and
 * policies see them, as they see the steps of visual agents.</li>
 * <li>The bridge is the process's standard input and output, one JSON message per line; what the code prints goes to
 * the run's log. Unit tests of the module run with plain Python, by giving main a stand-in for ooa.</li>
 * </ul>
 */
@OoaPackage(id = "ooa.coded", name = "Coded agents", version = "1.0.0",
        description = "Run an agent written in Python (main(inputs, ooa)) that calls toolkit commands through the run.")
public final class CodedAgentPackage {
    /** The runner's way to do one toolkit command for code: {action: "toolkit/command", inputs, step} -> outputs. */
    public static final String INVOKER = "action_invoker";

    static final String BRIDGE = """
            # One CommandCenter's bridge for coded agents (G70): runs main(inputs, ooa) of the module given.
            import importlib.util, json, sys, traceback
            _out = sys.stdout
            sys.stdout = sys.stderr   # what the code prints goes to the run's log
            def _send(m):
                _out.write(json.dumps(m, default=str) + "\\n"); _out.flush()
            def _recv():
                line = sys.stdin.readline()
                if not line:
                    sys.exit(3)
                return json.loads(line)
            class OoaError(Exception):
                def __init__(self, code, message):
                    super().__init__(message); self.code = code
            class Ooa:
                \"\"\"What the agent's code may use: inputs, call(), log().\"\"\"
                def __init__(self, inputs):
                    self.inputs = inputs
                def call(self, action, **inputs):
                    _send({"type": "call", "action": action, "inputs": inputs})
                    r = _recv()
                    if r.get("error"):
                        raise OoaError(r["error"].get("code", "ERROR"), r["error"].get("message", ""))
                    return r.get("outputs", {})
                def log(self, message, level="info"):
                    _send({"type": "log", "level": level, "message": str(message)})
            spec = importlib.util.spec_from_file_location("agent_module", sys.argv[1])
            mod = importlib.util.module_from_spec(spec)
            start = _recv()
            try:
                spec.loader.exec_module(mod)
                fn = getattr(mod, sys.argv[2])
                result = fn(start.get("inputs", {}), Ooa(start.get("inputs", {})))
                if result is not None and not isinstance(result, dict):
                    result = {"result": result}
                _send({"type": "done", "outputs": result or {}})
            except OoaError as e:
                _send({"type": "failed", "code": e.code, "message": str(e)})
            except Exception as e:
                _send({"type": "failed", "code": "CODE.ERROR", "message": type(e).__name__ + ": " + str(e), "trace": traceback.format_exc()[-4000:]})
            """;

    @SuppressWarnings("unchecked")
    @Action(id = "run_python_agent", name = "Run Python agent", category = "Coded agents", timeoutSeconds = 3600, capabilities = {"process", "filesystem"},
            description = "Run main(inputs, ooa) of a Python module: it gets the inputs, calls toolkit commands through ooa.call(), and returns the outputs.",
            panel = "(Code: A file = file / Typed here = code), inputs, uses | entry, interpreter")
    @Output(name = "outputs", type = OoaType.DICTIONARY, description = "What main returned (a dictionary; anything else comes back as result).")
    @Output(name = "calls", type = OoaType.NUMBER, description = "How many toolkit commands the code called.")
    public Map<String, Object> run(ActionContext ctx,
            @Input(name = "file", type = OoaType.FILE, required = false, visibleWhen = "code=", example = "agents/invoices.py", description = "The Python module, in the agent's files.") String file,
            @Input(name = "code", type = OoaType.STRING, required = false, visibleWhen = "file=", description = "The module's code, typed here.") String code,
            @Input(name = "inputs", type = OoaType.DICTIONARY, required = false, description = "What main gets as inputs (and as ooa.inputs).") Map<String, Object> inputs,
            @Input(name = "uses", type = OoaType.LIST, required = false, example = "[\"ooa.excel\", \"ooa.email\"]",
                    description = "The toolkits the code may call; any other is refused.") List<Object> uses,
            @Input(name = "entry", type = OoaType.STRING, required = false, defaultValue = "\"main\"", description = "The function to run.") String entry,
            @Input(name = "interpreter", type = OoaType.STRING, required = false, description = "Python program to use (a virtual environment's python); found on the PATH when empty.") String interpreter) {
        Set<String> allowed = new HashSet<>();
        if (uses != null) for (Object u : uses) allowed.add(Values.text(u).strip());
        String fn = entry == null || entry.isBlank() ? "main" : entry.strip();
        if (!fn.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new ActionException("CODE.BAD_ENTRY", "The function name '" + fn + "' is not allowed", "Use a Python function name: letters, digits and _, not starting with a digit.");
        Path module, temp = null, bridge;
        try {
            if (file != null && !file.isBlank()) {
                module = ctx.resolvePath(file);
                if (!Files.isRegularFile(module)) throw new ActionException("FILE.NOT_FOUND", "Python module not found: " + module, "Check the path; the file may have been moved or renamed.");
            } else if (code != null && !code.isBlank()) {
                temp = Files.createTempFile("ooa-agent-", ".py");
                Files.writeString(temp, code, StandardCharsets.UTF_8);
                module = temp;
            } else throw new ActionException("CODE.NO_CODE", "Give the module's file or its code", "Fill in either Module file or Code.");
            bridge = Files.createTempFile("ooa-bridge-", ".py");
            Files.writeString(bridge, BRIDGE, StandardCharsets.UTF_8);
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), "Check that the file or folder exists, is not open in another program, and that the runner's account may read and write it.", true); }
        Function<Map<String, Object>, Object> invoker;
        try { invoker = ctx.service(INVOKER, Function.class); } catch (OoaException e) { invoker = null; }
        try {
            List<String> tried = new ArrayList<>();
            for (String py : ScriptPackage.interpreters(interpreter, ProcessRunnerAccess.windows() ? List.of("python", "py", "python3") : List.of("python3", "python"))) {
                tried.add(py);
                Process p;
                try {
                    ProcessBuilder pb = new ProcessBuilder(py, "-u", bridge.toString(), module.toString(), fn).directory(ctx.workDir().toFile());
                    pb.environment().put("PYTHONIOENCODING", "utf-8");
                    p = pb.start();
                } catch (IOException e) { continue; }
                Map<String, Object> r = talk(ctx, p, inputs == null ? Map.of() : inputs, allowed, invoker);
                if (r != null) return r;
            }
            throw new ActionException("SCRIPT.INTERPRETER_NOT_FOUND", "Python is not installed on this runner (tried " + String.join(", ", tried) + ")",
                "Install Python 3 from python.org (tick Add to PATH) or give its program in interpreter.");
        } finally {
            try { if (temp != null) Files.deleteIfExists(temp); Files.deleteIfExists(bridge); } catch (IOException ignored) { }
        }
    }

    /** The conversation with the module's process; null when this interpreter turned out not to be a real Python. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> talk(ActionContext ctx, Process p, Map<String, Object> inputs, Set<String> allowed, Function<Map<String, Object>, Object> invoker) {
        Thread errs = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
                for (String l; (l = r.readLine()) != null; ) if (!l.isBlank()) ctx.log("info", l.length() > 2000 ? l.substring(0, 2000) : l);
            } catch (IOException ignored) { }
        }, "ooa-coded-stderr");
        errs.setDaemon(true);
        errs.start();
        // a stopped run stops the code too, even while it is busy and says nothing
        Thread watch = new Thread(() -> {
            while (p.isAlive()) {
                try { ctx.checkCancelled(); Thread.sleep(300); }
                catch (OoaException e) { p.destroyForcibly(); return; }
                catch (InterruptedException e) { return; }
            }
        }, "ooa-coded-cancel");
        watch.setDaemon(true);
        watch.start();
        int calls = 0;
        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8));
             BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            w.write(JsonLite.write(Map.of("inputs", inputs))); w.newLine(); w.flush();
            for (String line; (line = r.readLine()) != null; ) {
                ctx.checkCancelled();
                Map<String, Object> m;
                try { m = (Map<String, Object>) JsonLite.parse(line); } catch (RuntimeException e) { ctx.log("info", line); continue; }
                switch (String.valueOf(m.get("type"))) {
                    case "log" -> ctx.log(String.valueOf(m.getOrDefault("level", "info")), String.valueOf(m.get("message")));
                    case "call" -> {
                        calls++;
                        Map<String, Object> reply;
                        try {
                            String action = String.valueOf(m.get("action"));
                            int slash = action.lastIndexOf('/');
                            String pid = slash < 0 ? action : action.substring(0, slash);
                            if (slash < 0) throw new ActionException("CODE.BAD_CALL", "Name the command as toolkit/command, such as ooa.excel/read_range", "Write the command as toolkit/command, for example ooa.excel/read_range.");
                            if (pid.equals("ooa.coded")) throw new ActionException("CODE.BAD_CALL", "Coded agents cannot start coded agents through ooa.call", "Use a Run agent step for the other coded agent instead.");
                            if (!allowed.contains(pid)) throw new ActionException("CODE.TOOLKIT_NOT_LISTED", "The code called " + action + ", but " + pid + " is not in the step's Toolkits it may call",
                                "Add " + pid + " to Toolkits it may call.");
                            if (invoker == null) throw new ActionException("CODE.NO_RUNNER", "Toolkit commands can only be called while the agent runs", "Call toolkit commands only from code that the agent runs.");
                            Map<String, Object> req = new LinkedHashMap<>();
                            req.put("action", action);
                            req.put("inputs", m.get("inputs") instanceof Map<?, ?> in ? (Map<String, Object>) in : Map.of());
                            req.put("step", ctx.stepId());
                            Object out = invoker.apply(req);
                            reply = Map.of("outputs", out == null ? Map.of() : out);
                        } catch (OoaException e) {
                            reply = Map.of("error", Map.of("code", e.code(), "message", String.valueOf(e.getMessage())));
                        }
                        w.write(JsonLite.write(reply)); w.newLine(); w.flush();
                    }
                    case "done" -> {
                        p.waitFor(10, TimeUnit.SECONDS);
                        return Map.of("outputs", m.get("outputs") instanceof Map<?, ?> o ? o : Map.of("result", m.get("outputs")), "calls", calls);
                    }
                    case "failed" -> {
                        if (m.get("trace") != null) ctx.log("error", String.valueOf(m.get("trace")));
                        throw new ActionException(String.valueOf(m.get("code")), String.valueOf(m.get("message")), "Read the run's log for the code's trace.");
                    }
                    default -> ctx.log("info", line);
                }
            }
            int code = p.waitFor();
            if (code == 9009 || code == 49) return null;   // Windows' Store shortcut, not a Python
            throw new ActionException("CODE.ENDED", "The Python process ended (exit code " + code + ") before main returned", "Read the run's log.");
        } catch (IOException e) {
            if (!p.isAlive() && calls == 0) return null;
            throw new ActionException("CODE.IO", "Lost the connection to the Python process: " + e.getMessage(), "Check that the file or folder exists, is not open in another program, and that the runner's account may read and write it.", true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OoaException("RUN.CANCELLED", "Run thread interrupted", "The run was stopped before this step finished. Run the agent again when it should go on.");
        } finally {
            if (p.isAlive()) p.destroyForcibly();
        }
    }

    /** Whether this is Windows (as the Scripts toolkit decides). */
    static final class ProcessRunnerAccess { static boolean windows() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"); } }
}
