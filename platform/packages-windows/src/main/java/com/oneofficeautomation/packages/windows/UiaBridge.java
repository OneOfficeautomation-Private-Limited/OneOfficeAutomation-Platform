package com.oneofficeautomation.packages.windows;

import com.oneofficeautomation.sdk.ActionException;
import com.oneofficeautomation.sdk.JsonLite;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Connection to the UI Automation helper process. The protocol is one JSON object per line in each direction:
 * the helper first writes {"ready": true}; each request is {"id": n, "op": "...", ...}; each response is
 * {"id": n, "ok": true, "result": ...} or {"id": n, "ok": false, "code": "...", "error": "..."}.
 *
 * <p>{@link #start()} copies the bundled PowerShell script to a temporary file and runs
 * {@code powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File <copy>} as an argument list (no shell).
 * The helper lives for one agent run (it is kept in the run's sessions and closed at the end) and exits by itself when
 * its standard input closes. Tests connect the same class to in-memory streams with a fake helper.
 */
public class UiaBridge implements AutoCloseable {
    public static final String SCRIPT = "uia_bridge.ps1";
    static final long START_TIMEOUT_MS = 90_000;

    private final BufferedWriter out;
    private final BlockingQueue<Object> lines = new LinkedBlockingQueue<>();
    private final Process process;
    private final Path scriptCopy;
    private final AtomicLong ids = new AtomicLong();
    private final StringBuffer stderr = new StringBuffer();
    private volatile boolean closed;
    private static final Object EOF = new Object();
    /** Helpers still running; stopped when the JVM exits so none outlives Studio or the runner. */
    private static final Set<UiaBridge> LIVE = java.util.concurrent.ConcurrentHashMap.newKeySet();
    static { Runtime.getRuntime().addShutdownHook(new Thread(() -> List.copyOf(LIVE).forEach(UiaBridge::close), "ooa-uia-bridge-stop")); }

    /** Connects to a helper on the given streams and waits for its ready line. */
    UiaBridge(InputStream fromHelper, OutputStream toHelper, Process process, Path scriptCopy, long readyTimeoutMs) {
        this.out = new BufferedWriter(new OutputStreamWriter(toHelper, StandardCharsets.UTF_8));
        this.process = process;
        this.scriptCopy = scriptCopy;
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(fromHelper, StandardCharsets.UTF_8))) {
                for (String l; (l = r.readLine()) != null; ) if (l.startsWith("{")) lines.add(l);
            } catch (IOException ignored) {
            } finally { lines.add(EOF); }
        }, "ooa-uia-bridge-out");
        reader.setDaemon(true);
        reader.start();
        if (process != null) {
            Thread err = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                    for (String l; (l = r.readLine()) != null; ) if (stderr.length() < 8000) stderr.append(l).append('\n');
                } catch (IOException ignored) { }
            }, "ooa-uia-bridge-err");
            err.setDaemon(true);
            err.start();
        }
        Map<String, Object> ready = next(readyTimeoutMs, null);
        if (ready == null || !Boolean.TRUE.equals(ready.get("ready"))) {
            close();
            throw new ActionException("DESKTOP_UI.BRIDGE_FAILED", "The UI Automation helper did not start" + detail(),
                    "Check that Windows PowerShell 5.1 and .NET Framework 4 are available on this machine.");
        }
    }

    /** Starts the bundled PowerShell helper. Windows only. */
    public static UiaBridge start() {
        Path copy = null;
        try (InputStream in = UiaBridge.class.getResourceAsStream(SCRIPT)) {
            if (in == null) throw new IllegalStateException("missing resource " + SCRIPT);
            copy = Files.createTempFile("ooa-uia-", ".ps1");
            Files.write(copy, in.readAllBytes());
            List<String> cmd = List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", copy.toString());
            Process p = new ProcessBuilder(cmd).start();
            UiaBridge b = new UiaBridge(p.getInputStream(), p.getOutputStream(), p, copy, START_TIMEOUT_MS);
            LIVE.add(b);
            return b;
        } catch (IOException | IllegalStateException e) {
            if (copy != null) try { Files.deleteIfExists(copy); } catch (IOException ignored) { }
            throw new ActionException("DESKTOP_UI.BRIDGE_FAILED", "Cannot start the UI Automation helper: " + e.getMessage(),
                    "Check that powershell.exe is on the PATH (Windows PowerShell 5.1).");
        }
    }

    public boolean alive() { return !closed && (process == null || process.isAlive()); }

    /** The helper process, or null for an in-memory helper (tests). */
    Process process() { return process; }

    /**
     * Sends one request and waits for its response. A helper error becomes an ActionException with code
     * DESKTOP_UI.&lt;helper code&gt;; a helper that does not answer within the timeout is stopped (the next call starts a new one).
     */
    @SuppressWarnings("unchecked")
    public synchronized Object call(String op, Map<String, Object> args, long timeoutMs, Runnable checkCancelled) {
        if (!alive()) throw new ActionException("DESKTOP_UI.BRIDGE_STOPPED", "The UI Automation helper has stopped" + detail(), "Run the step again.", true);
        long id = ids.incrementAndGet();
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("id", BigDecimal.valueOf(id));
        req.put("op", op);
        if (args != null) args.forEach((k, v) -> { if (!k.equals("id") && !k.equals("op")) req.put(k, v); });
        try {
            out.write(JsonLite.write(req));
            out.write('\n');
            out.flush();
        } catch (IOException e) {
            close();
            throw new ActionException("DESKTOP_UI.BRIDGE_STOPPED", "The UI Automation helper has stopped" + detail(), "Run the step again.", true);
        }
        long end = System.currentTimeMillis() + timeoutMs;
        while (true) {
            Map<String, Object> resp = next(Math.min(200, Math.max(1, end - System.currentTimeMillis())), checkCancelled);
            if (resp == null) {
                if (closed) throw new ActionException("DESKTOP_UI.BRIDGE_STOPPED", "The UI Automation helper stopped while working" + detail(), "Run the step again.", true);
                if (System.currentTimeMillis() >= end) {
                    close();
                    throw new ActionException("DESKTOP_UI.BRIDGE_TIMEOUT", "The application did not answer UI Automation within " + timeoutMs / 1000 + " s",
                            "The application may be busy or not responding; wait for it or raise the timeout.", true);
                }
                continue;
            }
            Object rid = resp.get("id");
            if (rid == null || new BigDecimal(String.valueOf(rid)).longValue() != id) continue;   // a late answer to an abandoned request
            if (Boolean.TRUE.equals(resp.get("ok"))) return resp.get("result");
            String code = String.valueOf(resp.getOrDefault("code", "FAILED"));
            throw new ActionException("DESKTOP_UI." + code, String.valueOf(resp.getOrDefault("error", "UI Automation failed")), hint(code), code.equals("STALE"));
        }
    }

    static String hint(String code) {
        return switch (code) {
            case "STALE" -> "The object changed or closed while the agent worked with it; run the step again.";
            case "NOT_SUPPORTED" -> "Choose another action or method (for example click with the mouse, or type the text).";
            case "DISABLED" -> "Wait until the object is enabled, for example with Wait for object.";
            case "ITEM_NOT_FOUND" -> "Check the item text; Get list items shows the available items.";
            case "NO_POSITION" -> "Bring the window to the front, scroll the object into view or expand its parent.";
            case "TIMEOUT" -> "Point at the control and press Ctrl+Shift+C before the time runs out.";
            default -> "Check the application is open and responding.";
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> next(long waitMs, Runnable checkCancelled) {
        try {
            Object l = lines.poll(waitMs, TimeUnit.MILLISECONDS);
            if (checkCancelled != null) {
                try { checkCancelled.run(); } catch (RuntimeException e) { close(); throw e; }
            }
            if (l == null) return null;
            if (l == EOF) { closed = true; lines.add(EOF); return null; }
            Object v = JsonLite.parse((String) l);
            return v instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            close();
            throw new ActionException("RUN.CANCELLED", "Interrupted");
        } catch (IllegalArgumentException e) {
            return null;   // not a protocol line
        }
    }

    private String detail() {
        String e = stderr.toString().strip();
        return e.isEmpty() ? "" : ": " + (e.length() > 600 ? e.substring(0, 600) + "..." : e);
    }

    /** Asks the helper to exit, then stops it (and anything it started) if it is still running. */
    @Override public void close() {
        LIVE.remove(this);
        if (closed && (process == null || !process.isAlive())) { deleteCopy(); return; }
        closed = true;
        try { out.write("{\"op\":\"exit\"}\n"); out.flush(); } catch (IOException ignored) { }
        try { out.close(); } catch (IOException ignored) { }
        if (process != null) {
            try { if (!process.waitFor(1500, TimeUnit.MILLISECONDS)) { process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly(); process.waitFor(3, TimeUnit.SECONDS); } }
            catch (InterruptedException e) { process.destroyForcibly(); Thread.currentThread().interrupt(); }
        }
        deleteCopy();
    }

    private void deleteCopy() { if (scriptCopy != null) try { Files.deleteIfExists(scriptCopy); } catch (IOException ignored) { } }
}
