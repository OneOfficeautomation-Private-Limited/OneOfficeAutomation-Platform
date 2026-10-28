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
 * watches this machine for Command Center triggers (files and folders, windows, processes, services, hotkeys).
 */
public final class Agent {
    public static final String VERSION = com.oneofficeautomation.sdk.BuildInfo.VERSION;
    private final Path configFile, workspace;
    private final Map<String, Object> config;
    private final AgentClient client;
    private volatile Worker active;
    private volatile boolean stopping;
    /** The build of this runner's program files (null for a build from source); sent with each heartbeat. */
    private final String build = Updater.build(Updater.lib());
    private volatile boolean updating;
    /** The toolkits this runner has (id@version), sent with the first heartbeat and when the Command Center asks. */
    static final List<String> TOOLKITS = toolkits();
    private volatile boolean toolkitsSent;

    static List<String> toolkits() {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> m : com.oneofficeautomation.packages.FirstPartyPackages.registry().allManifests()) out.add(m.get("id") + "@" + m.get("version"));
        return out;
    }
    private final ExecutorService io = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "ooa-agent-io"); t.setDaemon(true); return t; });
    private final LocalTriggers localTriggers;
    long heartbeatMs = 10_000, pollMs = 2_000, triggerPollMs = 2_000;
    /** How long the Command Center may hold a request for work before answering (the long poll), in seconds. */
    long waitSeconds = 25;

    /** Heartbeat and job-poll intervals in milliseconds (defaults 10 s and 2 s); a poll under a second also turns the long poll off. */
    public Agent intervals(long heartbeat, long poll) { heartbeatMs = heartbeat; pollMs = poll; if (poll < 1000) waitSeconds = 0; return this; }

    // ---------------- configuration ----------------
    public static Path defaultConfig() { return Path.of(System.getProperty("user.home"), ".ooa-agent", "agent.json"); }

    public static String hostname() { try { return InetAddress.getLocalHost().getHostName(); } catch (Exception e) { return "unknown"; } }

    /** One-time registration with a key created in Control Room > Devices. Writes the device credentials to the config file. */
    public static Map<String, Object> register(String url, String key, String name, Path configFile) throws IOException { return register(url, key, name, configFile, null); }

    /** As above; {@code ca} is a certificate to trust for an HTTPS Command Center (copied next to the config as ca.cer). */
    public static Map<String, Object> register(String url, String key, String name, Path configFile, Path ca) throws IOException { return register(url, key, name, configFile, ca, null, null, null); }

    /**
     * As above, with the network settings: {@code proxy} system, none or http://host:port; {@code proxyUser} and
     * {@code proxyPassword} (plain text here; stored encrypted) for a proxy that asks for them.
     */
    public static Map<String, Object> register(String url, String key, String name, Path configFile, Path ca, String proxy, String proxyUser, String proxyPassword) throws IOException {
        Path keptCa = null;
        if (ca != null) {
            Files.createDirectories(configFile.getParent());
            keptCa = configFile.resolveSibling("ca.cer");
            if (!ca.toAbsolutePath().equals(keptCa.toAbsolutePath())) Files.copy(ca, keptCa, StandardCopyOption.REPLACE_EXISTING);
        }
        Network net = new Network(keptCa, proxy, proxyUser, proxyUser == null || proxyPassword == null ? null : Network.protect(proxyPassword, configFile.toAbsolutePath().getParent()));
        Map<String, Object> r = new AgentClient(url, null, null, net).post("register",
                Map.of("key", key, "name", name == null ? hostname() : name, "hostname", hostname(), "os", System.getProperty("os.name") + " " + System.getProperty("os.version"), "agent_version", VERSION));
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("url", url.replaceAll("/+$", ""));
        cfg.put("device_id", r.get("device_id"));
        cfg.put("secret", r.get("secret"));
        cfg.put("name", r.get("name"));
        net.into(cfg);
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
        this.client = new AgentClient((String) config.get("url"), (String) config.get("device_id"), (String) config.get("secret"), Network.from(config));
        this.localTriggers = new LocalTriggers(new LocalTriggers.Reporter() {
            public void fired(String id, Map<String, Object> event) { client.post("trigger-fired", Map.of("trigger_id", id, "event", event)); }
            public void status(String id, String error) { Map<String, Object> b = new HashMap<>(); b.put("trigger_id", id); b.put("error", error); client.post("trigger-status", b); }
        }, new LocalTriggers.SystemProbe(), LocalTriggers.systemHotkeys(), this::log);
    }

    /**
     * Changes the network settings in a runner's config file (ooa agent network); null leaves a value as it is. Returns
     * the settings now in force, without the password.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> network(Path configFile, String proxy, String proxyUser, String proxyPassword, Path ca) throws IOException {
        if (!Files.exists(configFile)) throw new OoaException("AGENT.NOT_REGISTERED", "This runner is not registered (no " + configFile + ")");
        Map<String, Object> cfg = new LinkedHashMap<>((Map<String, Object>) JsonLite.parse(Files.readString(configFile)));
        Network now = Network.from(cfg);
        Path keptCa = now.ca();
        if (ca != null) { keptCa = configFile.resolveSibling("ca.cer"); if (!ca.toAbsolutePath().equals(keptCa.toAbsolutePath())) Files.copy(ca, keptCa, StandardCopyOption.REPLACE_EXISTING); }
        String user = proxyUser == null ? now.proxyUser() : proxyUser.isBlank() ? null : proxyUser;
        String pw = user == null ? null : proxyPassword != null ? Network.protect(proxyPassword, configFile.toAbsolutePath().getParent()) : now.proxyPassword();
        Network next = new Network(keptCa, proxy == null ? now.proxy() : proxy, user, pw);
        next.into(cfg);
        Files.writeString(configFile, JsonLite.write(cfg), StandardCharsets.UTF_8);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("proxy", next.proxy()); out.put("proxy_user", next.proxyUser() == null ? "" : next.proxyUser()); out.put("ca", next.ca() == null ? "" : next.ca().toString());
        return out;
    }

    /**
     * Points a registered runner at a new Command Center address (ooa agent move), keeping its registration: for a
     * Command Center that moved (to the cloud, a new server) with its data copied or restored. The new address must
     * accept this runner's credentials before anything is saved; a new certificate authority file may come along.
     */
    @SuppressWarnings("unchecked")
    public static String moveTo(Path configFile, String url, Path ca) throws IOException {
        if (!Files.exists(configFile)) throw new OoaException("AGENT.NOT_REGISTERED", "This runner is not registered (no " + configFile + ")");
        if (url == null || !url.matches("(?i)https?://[^\\s/]+.*")) throw new OoaException("AGENT.BAD_URL", "The Command Center address starts with https:// (or http://)");
        Map<String, Object> cfg = new LinkedHashMap<>((Map<String, Object>) JsonLite.parse(Files.readString(configFile)));
        Network now = Network.from(cfg);
        Path newCa = null;
        if (ca != null) { newCa = configFile.resolveSibling("ca.cer.new"); Files.copy(ca, newCa, StandardCopyOption.REPLACE_EXISTING); }
        String clean = url.replaceAll("/+$", "");
        try {
            new AgentClient(clean, (String) cfg.get("device_id"), (String) cfg.get("secret"), new Network(newCa != null ? newCa : now.ca(), now.proxy(), now.proxyUser(), now.proxyPassword()))
                .post("heartbeat", Map.of("agent_version", VERSION, "diagnose", true));
        } catch (RuntimeException e) {
            if (newCa != null) Files.deleteIfExists(newCa);
            throw new OoaException("AGENT.MOVE_REFUSED", "The Command Center at " + clean + " did not accept this runner: " + e.getMessage(),
                "Copy or restore the old Command Center's data there first (its runners come with it), or register this runner again");
        }
        Path keptCa = now.ca();
        if (newCa != null) { keptCa = configFile.resolveSibling("ca.cer"); Files.move(newCa, keptCa, StandardCopyOption.REPLACE_EXISTING); }
        new Network(keptCa, now.proxy(), now.proxyUser(), now.proxyPassword()).into(cfg);
        String old = String.valueOf(cfg.get("url"));
        cfg.put("url", clean);
        Path tmp = configFile.resolveSibling(configFile.getFileName() + ".new");
        Files.writeString(tmp, JsonLite.write(cfg), StandardCharsets.UTF_8);
        Files.move(tmp, configFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return old;
    }

    /**
     * Updates the program files from the Command Center (it asked, and nothing is running): stages them, starts the helper
     * that swaps them once this process ends, and exits. A service is started again by Windows; a runner started at sign-in
     * by the helper. Failing leaves everything as it was.
     */
    void update(Map<String, Object> u) {
        Path lib = Updater.lib();
        try {
            if (lib == null || !DesktopSession.WINDOWS) { log("info", "Command Center offers update " + u.get("version") + ", but this runner is not an installation on Windows; update it by hand"); return; }
            log("Updating to " + u.get("version") + " (build " + u.get("build") + ") from Command Center");
            Updater.stage(lib, new Updater.Source() {
                public Map<String, Object> manifest() { return client.getJson("update/manifest"); }
                public byte[] file(String name) { return client.getBytes("update/file?name=" + java.net.URLEncoder.encode(name, StandardCharsets.UTF_8)); }
            });
            if (active != null) { log("warning", "A run started while updating; the update waits for the next heartbeat"); Updater.deleteTree(lib.getParent().resolve("update")); return; }
            stopping = true;
            String task = System.getProperty("ooa.runner.task");
            Updater.launch(lib.getParent(), task);
            log("Update staged; restarting " + (task != null ? "through the task " + task : System.getProperty("ooa.runner.service") != null ? "(Windows starts the service again)" : "(start the runner again)"));
            System.exit(75);
        } catch (Exception e) {
            log("error", "Update failed, staying on this version: " + e.getMessage());
        } finally { updating = false; }
    }

    /**
     * Takes a new secret from the Command Center (its heartbeat asked for it) and saves it in the config file: written to
     * a new file first, then moved over the old one, so the file always holds a working secret.
     */
    synchronized void rotateSecret() {
        try {
            Map<String, Object> r = client.post("rotate", Map.of());
            String s = (String) r.get("secret");
            if (s == null || s.isBlank()) return;
            Map<String, Object> cfg = new LinkedHashMap<>(config);
            cfg.put("secret", s);
            Path tmp = configFile.resolveSibling(configFile.getFileName() + ".new");
            Files.writeString(tmp, JsonLite.write(cfg), StandardCharsets.UTF_8);
            Files.move(tmp, configFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            config.put("secret", s);
            client.secret(s);
            log("Runner secret rotated");
        } catch (Exception e) { log("warning", "Could not rotate the runner secret (the old one keeps working): " + e.getMessage()); }
    }

    /** The runner's log level, set in Command Center and received with each heartbeat: error, warning, info or debug. */
    volatile String logLevel = "info";
    /** What to do with the screen after an unattended run (nothing, lock, sign_out), set in Command Center. */
    volatile String afterRun = "nothing";
    static final List<String> LEVELS = List.of("error", "warning", "info", "debug");

    void log(String msg) { log("info", msg); }

    void log(String level, String msg) {
        if (LEVELS.indexOf(level) > LEVELS.indexOf(logLevel)) return;
        System.err.println(LocalDateTime.now().withNano(0) + "  " + (level.equals("info") ? "" : level.toUpperCase(Locale.ROOT) + "  ") + msg);
    }

    // ---------------- main loop ----------------
    public void run() {
        log("ONEOFFICEAUTOMATION One Runner " + VERSION + " for runner '" + config.get("name") + "' connected to " + config.get("url"));
        log("Workspace: " + workspace);
        ScheduledExecutorService hb = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "ooa-agent-heartbeat"); t.setDaemon(true); return t; });
        hb.scheduleWithFixedDelay(this::heartbeat, 0, heartbeatMs, TimeUnit.MILLISECONDS);
        localTriggers.start(triggerPollMs);
        while (!stopping) {
            try {
                if (active == null) {
                    long asked = System.currentTimeMillis();
                    Map<String, Object> r = client.post(waitSeconds > 0 ? "jobs/next?wait=" + waitSeconds : "jobs/next", Map.of());
                    if (r.get("job") instanceof Map<?, ?> job) { @SuppressWarnings("unchecked") Map<String, Object> j = (Map<String, Object>) job; execute(j); continue; }
                    if (Boolean.TRUE.equals(r.get("check"))) io.submit(this::heartbeat);
                    // the Command Center held the request (long poll): ask again at once; an older one answers at once, so wait as before
                    if (System.currentTimeMillis() - asked >= pollMs) continue;
                }
            } catch (OoaException e) { log("warning", "Command Center: " + e.getMessage()); }
            try { Thread.sleep(pollMs); } catch (InterruptedException e) { break; }
        }
        hb.shutdownNow();
        localTriggers.stop();
    }

    public void stop() { stopping = true; Worker w = active; if (w != null) w.send(Map.of("type", "cancel")); }

    @SuppressWarnings("unchecked")
    void heartbeat() {
        try {
            Worker w = active;
            Map<String, Object> beat = new HashMap<>(Map.of("agent_version", VERSION, "hostname", hostname(), "os", System.getProperty("os.name"),
                    "running", w == null ? List.of() : List.of(w.runId), "session", DesktopSession.state(), "build", build == null ? "" : build));
            if (!toolkitsSent) beat.put("toolkits", TOOLKITS);
            Map<String, Object> r = client.post("heartbeat", beat);
            toolkitsSent = !Boolean.TRUE.equals(r.get("send_toolkits"));
            if (r.get("update") instanceof Map<?, ?> u && w == null && !updating) { updating = true; io.submit(() -> update((Map<String, Object>) u)); }
            if (w != null) {
                if (((List<Object>) r.getOrDefault("cancel", List.of())).contains(w.runId)) w.cancel();
                else if (((List<Object>) r.getOrDefault("pause", List.of())).contains(w.runId)) w.send(Map.of("type", "pause"));
                else if (((List<Object>) r.getOrDefault("resume", List.of())).contains(w.runId)) w.send(Map.of("type", "resume"));
            }
            localTriggers.update(r.get("local_triggers") instanceof List<?> l ? (List<Object>) l : (List<Object>) r.getOrDefault("file_triggers", List.of()));
            if (r.get("log_level") instanceof String lv && LEVELS.contains(lv) && !lv.equals(logLevel)) { logLevel = lv; log("Log level set to " + lv + " by Command Center"); }
            if (r.get("after_run") instanceof String ar && !ar.equals(afterRun)) { afterRun = ar; log("After unattended runs: " + ar + " (set in Command Center)"); }
            if (Boolean.TRUE.equals(r.get("rotate"))) rotateSecret();
        } catch (OoaException e) { log("warning", "Heartbeat failed: " + e.getMessage()); }
        catch (RuntimeException e) { log("error", "Heartbeat error: " + e); }
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
            io.submit(() -> { try { if (!proc.waitFor(15, TimeUnit.SECONDS)) { log("warning", "Run " + runId + " did not stop in time; ending its worker"); proc.descendants().forEach(ProcessHandle::destroyForcibly); proc.destroyForcibly(); } } catch (InterruptedException ignored) { } });
        }
    }

    /** The run's mode from Command Center: attended, unattended (the default) or headless. */
    static String workerMode(Map<String, Object> job) {
        String m = String.valueOf(job.getOrDefault("mode", "unattended"));
        return List.of("attended", "unattended", "headless").contains(m) ? m : "unattended";
    }

    /**
     * The worker's command line. A headless run gets no desktop (java.awt.headless, and browsers open without a window);
     * every run tells its commands the mode (ooa.runMode), so an unattended one never waits for a person.
     */
    static List<String> workerCommand(String javaBin, String mode, String runnerHeadless, Path workspace) {
        return List.of(javaBin, "-Djava.awt.headless=" + (mode.equals("headless") ? "true" : runnerHeadless), "-Dooa.runMode=" + mode,
                "-cp", System.getProperty("java.class.path"), RunWorker.class.getName(), workspace.toString());
    }

    @SuppressWarnings("unchecked")
    void execute(Map<String, Object> job) {
        String runId = (String) job.get("run_id");
        String mode = workerMode(job);
        log("Starting run " + runId + ": " + job.get("bot_path") + " v" + job.get("version") + " (" + mode + ")");
        Path logFile = workspace.resolve("logs").resolve(runId + ".log");
        String screen = DesktopSession.problem(mode, (Map<String, Object>) job.get("flow"), DesktopSession.state());
        if (screen != null) {
            log("warning", "Run " + runId + " not started: " + screen);
            reportResult(runId, Map.of("status", "failed", "error", Map.of("code", "RUN.NO_DESKTOP", "message", screen,
                    "suggestedFix", "Sign in on the runner and leave it unlocked, or send the run to a runner where someone is signed in")));
            return;
        }
        Worker w;
        try {
            String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            ProcessBuilder pb = new ProcessBuilder(workerCommand(javaBin, mode, System.getProperty("java.awt.headless", "false"), workspace));
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
        reportResult(runId, result);
        log("Finished run " + runId + ": " + result.get("status"));
        active = null;
        if (mode.equals("unattended")) { String done = DesktopSession.afterRun(afterRun); if (done != null) log("After the run: " + done); }
    }

    /**
     * Sends a run's result, trying again when the Command Center cannot be reached (a dropped connection, a restart):
     * 6 attempts over about a minute. Sending it twice is harmless, as the Command Center ignores a result for a run
     * that is no longer running.
     */
    void reportResult(String runId, Map<String, Object> result) {
        for (int attempt = 1; ; attempt++) {
            try { client.post("runs/" + runId + "/finish", Map.of("result", result)); return; }
            catch (OoaException e) {
                if (!"AGENT.UNREACHABLE".equals(e.code()) || attempt >= 6) { log("error", "Could not report the result of " + runId + ": " + e.getMessage()); return; }
                try { Thread.sleep(Math.min(30_000L, 1000L << (attempt - 1))); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
            }
        }
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
                            w.send(Map.of("type", "credential", "id", msg.get("id"), "value", r.get("value"), "password_only", Boolean.TRUE.equals(r.get("password_only"))));
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
