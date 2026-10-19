package com.oneofficeautomation.agent;

import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;

import java.io.*;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

/**
 * The Bot Agent: registers this device with Control Room, sends heartbeats, takes jobs one at a time and runs each bot
 * in its own worker process (crash isolation), relays live events, credential requests and stop/pause/resume, and
 * watches folders for Control Room file triggers.
 */
public final class Agent {
    public static final String VERSION = "0.2.0";
    private final Path configFile, workspace;
    private final Map<String, Object> config;
    private final AgentClient client;
    private volatile Worker active;
    private volatile boolean stopping;
    private final ExecutorService io = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "ooa-agent-io"); t.setDaemon(true); return t; });
    private final FileTriggers fileTriggers;
    long heartbeatMs = 10_000, pollMs = 2_000;

    /** Heartbeat and job-poll intervals in milliseconds (defaults 10 s and 2 s). */
    public Agent intervals(long heartbeat, long poll) { heartbeatMs = heartbeat; pollMs = poll; return this; }

    // ---------------- configuration ----------------
    public static Path defaultConfig() { return Path.of(System.getProperty("user.home"), ".ooa-agent", "agent.json"); }

    public static String hostname() { try { return InetAddress.getLocalHost().getHostName(); } catch (Exception e) { return "unknown"; } }

    /** One-time registration with a key created in Control Room > Devices. Writes the device credentials to the config file. */
    public static Map<String, Object> register(String url, String key, String name, Path configFile) throws IOException {
        Map<String, Object> r = new AgentClient(url, null, null).post("register",
                Map.of("key", key, "name", name == null ? hostname() : name, "hostname", hostname(), "os", System.getProperty("os.name") + " " + System.getProperty("os.version"), "agent_version", VERSION));
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("url", url.replaceAll("/+$", ""));
        cfg.put("device_id", r.get("device_id"));
        cfg.put("secret", r.get("secret"));
        cfg.put("name", r.get("name"));
        Files.createDirectories(configFile.getParent());
        Files.writeString(configFile, JsonLite.write(cfg), StandardCharsets.UTF_8);
        try { Files.setPosixFilePermissions(configFile, PosixFilePermissions.fromString("rw-------")); } catch (UnsupportedOperationException ignored) { /* Windows: user profile ACLs apply */ }
        return Map.of("device_id", r.get("device_id"), "name", r.get("name"), "config", configFile.toString());
    }

    @SuppressWarnings("unchecked")
    public Agent(Path configFile, Path workspace) throws IOException {
        this.configFile = configFile;
        if (!Files.exists(configFile)) throw new OoaException("AGENT.NOT_REGISTERED", "This runner is not registered (no " + configFile + ")", "Run: ooa agent register --url <Command Center URL> --key <registration key>");
        this.config = (Map<String, Object>) JsonLite.parse(Files.readString(configFile));
        this.workspace = workspace;
        Files.createDirectories(workspace.resolve("logs"));
        this.client = new AgentClient((String) config.get("url"), (String) config.get("device_id"), (String) config.get("secret"));
        this.fileTriggers = new FileTriggers(client, this::log);
    }

    void log(String msg) { System.err.println(LocalDateTime.now().withNano(0) + "  " + msg); }

    // ---------------- main loop ----------------
    public void run() {
        log("ONEOFFICEAUTOMATION One Runner " + VERSION + " for runner '" + config.get("name") + "' connected to " + config.get("url"));
        log("Workspace: " + workspace);
        ScheduledExecutorService hb = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "ooa-agent-heartbeat"); t.setDaemon(true); return t; });
        hb.scheduleWithFixedDelay(this::heartbeat, 0, heartbeatMs, TimeUnit.MILLISECONDS);
        while (!stopping) {
            try {
                if (active == null) {
                    Map<String, Object> r = client.post("jobs/next", Map.of());
                    if (r.get("job") instanceof Map<?, ?> job) { @SuppressWarnings("unchecked") Map<String, Object> j = (Map<String, Object>) job; execute(j); continue; }
                }
            } catch (OoaException e) { log("Command Center: " + e.getMessage()); }
            try { Thread.sleep(pollMs); } catch (InterruptedException e) { break; }
        }
        hb.shutdownNow();
        fileTriggers.stop();
    }

    public void stop() { stopping = true; Worker w = active; if (w != null) w.send(Map.of("type", "cancel")); }

    @SuppressWarnings("unchecked")
    void heartbeat() {
        try {
            Worker w = active;
            Map<String, Object> r = client.post("heartbeat", Map.of("agent_version", VERSION, "hostname", hostname(), "os", System.getProperty("os.name"),
                    "running", w == null ? List.of() : List.of(w.runId)));
            if (w != null) {
                if (((List<Object>) r.getOrDefault("cancel", List.of())).contains(w.runId)) w.cancel();
                else if (((List<Object>) r.getOrDefault("pause", List.of())).contains(w.runId)) w.send(Map.of("type", "pause"));
                else if (((List<Object>) r.getOrDefault("resume", List.of())).contains(w.runId)) w.send(Map.of("type", "resume"));
            }
            fileTriggers.update((List<Object>) r.getOrDefault("file_triggers", List.of()));
        } catch (OoaException e) { log("Heartbeat failed: " + e.getMessage()); }
        catch (RuntimeException e) { log("Heartbeat error: " + e); }
    }

    // ---------------- running one job ----------------
    /** A bot running in its own JVM; the protocol is one JSON object per line on stdin/stdout. */
    final class Worker {
        final String runId; final Process proc; final BufferedWriter in;
        final List<Object> pending = Collections.synchronizedList(new ArrayList<>());
        volatile Map<String, Object> result; volatile boolean cancelled;
        Worker(String runId, Process p) { this.runId = runId; this.proc = p; this.in = new BufferedWriter(new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8)); }
        synchronized void send(Object msg) {
            try { in.write(JsonLite.write(msg)); in.newLine(); in.flush(); } catch (IOException ignored) { /* worker already ended */ }
        }
        void cancel() {
            if (cancelled) return;
            cancelled = true;
            send(Map.of("type", "cancel"));
            io.submit(() -> { try { if (!proc.waitFor(15, TimeUnit.SECONDS)) { log("Run " + runId + " did not stop in time; ending its worker"); proc.descendants().forEach(ProcessHandle::destroyForcibly); proc.destroyForcibly(); } } catch (InterruptedException ignored) { } });
        }
    }

    @SuppressWarnings("unchecked")
    void execute(Map<String, Object> job) {
        String runId = (String) job.get("run_id");
        log("Starting run " + runId + ": " + job.get("bot_path") + " v" + job.get("version"));
        Path logFile = workspace.resolve("logs").resolve(runId + ".log");
        Worker w;
        try {
            String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            ProcessBuilder pb = new ProcessBuilder(javaBin, "-Djava.awt.headless=" + System.getProperty("java.awt.headless", "false"),
                    "-cp", System.getProperty("java.class.path"), RunWorker.class.getName(), workspace.toString());
            pb.redirectError(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
            w = new Worker(runId, pb.start());
        } catch (IOException e) {
            client.post("runs/" + runId + "/finish", Map.of("result", Map.of("status", "failed", "error", Map.of("code", "AGENT.WORKER_START_FAILED", "message", e.getMessage()))));
            return;
        }
        active = w;
        Worker wk = w;
        Future<?> reader = io.submit(() -> readWorker(wk, job));
        ScheduledExecutorService flusher = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "ooa-agent-events"); t.setDaemon(true); return t; });
        flusher.scheduleWithFixedDelay(() -> flush(wk), 1, 1, TimeUnit.SECONDS);
        w.send(job);
        try {
            if (!w.proc.waitFor(24, TimeUnit.HOURS)) { w.proc.destroyForcibly(); }
            reader.get(10, TimeUnit.SECONDS);
        } catch (Exception ignored) { }
        flusher.shutdownNow();
        flush(w);
        Map<String, Object> result = w.result;
        if (result == null) {
            String tail = "";
            try { List<String> lines = Files.readAllLines(logFile); tail = String.join("\n", lines.subList(Math.max(0, lines.size() - 5), lines.size())); } catch (IOException ignored) { }
            result = w.cancelled ? Map.of("status", "stopped", "error", Map.of("code", "RUN.CANCELLED", "message", "Stopped from Control Room"))
                    : Map.of("status", "failed", "error", Map.of("code", "AGENT.WORKER_CRASHED", "message", "The agent's worker process ended unexpectedly (exit " + w.proc.exitValue() + ")",
                            "suggestedFix", "See the agent log " + logFile + (tail.isBlank() ? "" : ": " + tail)));
        }
        try { client.post("runs/" + runId + "/finish", Map.of("result", result)); }
        catch (OoaException e) { log("Could not report the result of " + runId + ": " + e.getMessage()); }
        log("Finished run " + runId + ": " + result.get("status"));
        active = null;
    }

    @SuppressWarnings("unchecked")
    private void readWorker(Worker w, Map<String, Object> job) {
        try (BufferedReader out = new BufferedReader(new InputStreamReader(w.proc.getInputStream(), StandardCharsets.UTF_8))) {
            for (String line; (line = out.readLine()) != null; ) {
                Map<String, Object> msg;
                try { msg = (Map<String, Object>) JsonLite.parse(line); } catch (RuntimeException e) { continue; }
                switch (String.valueOf(msg.get("type"))) {
                    case "event" -> w.pending.add(msg.get("event"));
                    case "result" -> w.result = (Map<String, Object>) msg.get("result");
                    case "credential" -> io.submit(() -> {
                        try {
                            Map<String, Object> r = client.post("credential", Map.of("run_id", w.runId, "ref", msg.get("ref")));
                            w.send(Map.of("type", "credential", "id", msg.get("id"), "value", r.get("value")));
                        } catch (OoaException e) {
                            w.send(Map.of("type", "credential", "id", msg.get("id"), "error", Map.of("code", e.code(), "message", e.getMessage())));
                        }
                    });
                    default -> { }
                }
            }
        } catch (IOException ignored) { }
    }

    private void flush(Worker w) {
        List<Object> batch;
        synchronized (w.pending) { if (w.pending.isEmpty()) return; batch = new ArrayList<>(w.pending); w.pending.clear(); }
        try { client.post("runs/" + w.runId + "/events", Map.of("events", batch)); }
        catch (OoaException e) { synchronized (w.pending) { w.pending.addAll(0, batch); } }
    }
}
