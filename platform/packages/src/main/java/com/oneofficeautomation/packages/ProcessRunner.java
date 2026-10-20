package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Runs external programs safely: arguments as a list (never through a shell), bounded output, timeout, cancel. */
final class ProcessRunner {
    private ProcessRunner() {}
    static final int MAX_OUTPUT = 1_000_000;
    /** Session key holding environment variables set with System > Set environment variable (process scope) for this run. */
    static final String RUN_ENV_SESSION = "ooa.system.env";
    static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    record Result(int exitCode, String stdout, String stderr, boolean truncated) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("exit_code", BigDecimal.valueOf(exitCode)); m.put("output", stdout); m.put("error_output", stderr);
            return m;
        }
    }

    static Result run(ActionContext ctx, List<String> command, Path workDir, String stdin, double timeoutSeconds, Map<String, String> env, Charset cs) {
        ProcessBuilder pb = new ProcessBuilder(command);
        if (workDir != null) pb.directory(workDir.toFile());
        Object runEnv = ctx.sessions() == null ? null : ctx.sessions().get(RUN_ENV_SESSION);
        if (runEnv instanceof Map<?, ?> m) m.forEach((k, v) -> { if (v == null || String.valueOf(v).isEmpty()) pb.environment().remove(String.valueOf(k)); else pb.environment().put(String.valueOf(k), String.valueOf(v)); });
        if (env != null) pb.environment().putAll(env);
        Process p;
        try { p = pb.start(); }
        catch (IOException e) {
            throw new ActionException("PROCESS.NOT_FOUND", "Cannot start '" + command.get(0) + "': " + e.getMessage(),
                    "Check the program is installed and on the PATH, or give its full path.");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream();
        Thread to = pump(p.getInputStream(), out), te = pump(p.getErrorStream(), err);
        try (OutputStream os = p.getOutputStream()) {
            if (stdin != null) os.write(stdin.getBytes(cs));
        } catch (IOException ignored) { }
        long deadline = System.nanoTime() + (long) (timeoutSeconds * 1e9);
        try {
            while (!p.waitFor(100, TimeUnit.MILLISECONDS)) {
                if (System.nanoTime() > deadline) {
                    p.descendants().forEach(ProcessHandle::destroyForcibly);
                    p.destroyForcibly();
                    throw new ActionException("PROCESS.TIMEOUT", "'" + command.get(0) + "' did not finish within " + timeoutSeconds + " s",
                            "Raise timeout_seconds or check the program is not waiting for input.", true);
                }
                try { ctx.checkCancelled(); }
                catch (RuntimeException c) { p.descendants().forEach(ProcessHandle::destroyForcibly); p.destroyForcibly(); throw c; }
            }
            to.join(2000); te.join(2000);
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new ActionException("RUN.CANCELLED", "Interrupted");
        }
        boolean trunc = out.size() >= MAX_OUTPUT || err.size() >= MAX_OUTPUT;
        return new Result(p.exitValue(), ctx.redact(out.toString(cs)), ctx.redact(err.toString(cs)), trunc);
    }

    static Result run(ActionContext ctx, List<String> command, Path workDir, String stdin, double timeoutSeconds) {
        return run(ctx, command, workDir, stdin, timeoutSeconds, null, StandardCharsets.UTF_8);
    }

    private static Thread pump(InputStream in, ByteArrayOutputStream sink) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[8192];
            try { for (int n; (n = in.read(buf)) > 0; ) if (sink.size() < MAX_OUTPUT) sink.write(buf, 0, Math.min(n, MAX_OUTPUT - sink.size())); }
            catch (IOException ignored) { }
        }, "ooa-process-io");
        t.setDaemon(true);
        t.start();
        return t;
    }
}
