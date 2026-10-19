package com.oneofficeautomation.cli;

import com.oneofficeautomation.runner.RunResult;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiFunction;

/**
 * Event triggers for Studio: run a bot when a file arrives in a folder, on a schedule (every N minutes or daily at a
 * time), or when a local webhook is called. Definitions live in triggers.json in the workspace; every firing is logged.
 * A trigger never starts a second run while its previous run is still going. The Bot Agent takes this over for
 * unattended devices (with email, window and hotkey triggers).
 */
final class TriggerService {
    private final Workspace ws;
    private final BiFunction<String, Map<String, Object>, RunResult> runBot;   // (bot path, inputs) -> result
    private final ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "ooa-trigger-clock"));
    private final ExecutorService runs = Executors.newFixedThreadPool(2, r -> daemon(r, "ooa-trigger-run"));
    private final Deque<Map<String, Object>> log = new ConcurrentLinkedDeque<>();
    private final Set<String> running = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> lastFired = new ConcurrentHashMap<>();
    private volatile List<Map<String, Object>> triggers = List.of();
    private WatchService watcher;
    private Thread watchThread;

    TriggerService(Workspace ws, BiFunction<String, Map<String, Object>, RunResult> runBot) { this.ws = ws; this.runBot = runBot; }

    private static Thread daemon(Runnable r, String name) { Thread t = new Thread(r, name); t.setDaemon(true); return t; }

    Path file() { return ws.root.resolve("triggers.json"); }

    @SuppressWarnings("unchecked")
    void start() {
        try { if (Files.exists(file())) triggers = validate((List<Object>) JsonLite.parse(Files.readString(file()))); }
        catch (Exception e) { addLog(null, "load", "error", null, "triggers.json could not be loaded: " + e.getMessage()); }
        clock.scheduleAtFixedRate(this::tick, 5, 20, TimeUnit.SECONDS);
        restartWatcher();
    }

    List<Map<String, Object>> list() { return triggers; }
    List<Map<String, Object>> recentLog() { return new ArrayList<>(log); }

    synchronized void save(List<Object> raw) throws IOException {
        List<Map<String, Object>> v = validate(raw);
        Path tmp = file().resolveSibling(".triggers.json.saving");
        Files.writeString(tmp, JsonLite.write(v), StandardCharsets.UTF_8);
        try { Files.move(tmp, file(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(tmp, file(), StandardCopyOption.REPLACE_EXISTING); }
        triggers = v;
        restartWatcher();
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> validate(List<Object> raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Object o : raw) {
            if (!(o instanceof Map)) throw new OoaException("TRIGGER.INVALID", "Each trigger must be an object");
            Map<String, Object> t = new LinkedHashMap<>((Map<String, Object>) o);
            String id = String.valueOf(t.get("id"));
            if (!id.matches("^[A-Za-z0-9_-]{1,40}$") || !ids.add(id)) throw new OoaException("TRIGGER.BAD_ID", "Trigger ids must be unique: letters, digits, - and _");
            String bot = String.valueOf(t.get("bot"));
            if (!bot.endsWith(Workspace.BOT_SUFFIX) || !Files.isRegularFile(ws.resolve(bot))) throw new OoaException("TRIGGER.BAD_BOT", "Trigger '" + id + "': agent not found: " + bot, "Choose an agent from the workspace.");
            String type = String.valueOf(t.get("type"));
            switch (type) {
                case "file" -> {
                    Path dir = folder(t);
                    if (!Files.isDirectory(dir)) throw new OoaException("TRIGGER.BAD_FOLDER", "Trigger '" + id + "': folder not found: " + dir, "Create the folder first.");
                    t.putIfAbsent("pattern", "*");
                }
                case "schedule" -> {
                    boolean every = t.get("every_minutes") != null, daily = t.get("daily_at") != null;
                    if (every == daily) throw new OoaException("TRIGGER.BAD_SCHEDULE", "Trigger '" + id + "': give either every_minutes or daily_at (HH:mm)");
                    if (every && Integer.parseInt(String.valueOf(t.get("every_minutes")).replaceAll("\\..*", "")) < 1) throw new OoaException("TRIGGER.BAD_SCHEDULE", "every_minutes must be at least 1");
                    if (daily && !String.valueOf(t.get("daily_at")).matches("^([01]\\d|2[0-3]):[0-5]\\d$")) throw new OoaException("TRIGGER.BAD_SCHEDULE", "daily_at must look like 08:30");
                }
                case "webhook" -> {
                    if (!(t.get("secret") instanceof String s) || s.length() < 16) throw new OoaException("TRIGGER.WEAK_SECRET", "Trigger '" + id + "': webhook secret must be at least 16 characters");
                }
                default -> throw new OoaException("TRIGGER.BAD_TYPE", "Trigger '" + id + "': type must be file, schedule or webhook");
            }
            t.putIfAbsent("enabled", true);
            out.add(t);
        }
        return out;
    }

    private Path folder(Map<String, Object> t) {
        String f = String.valueOf(t.get("folder"));
        Path p = Path.of(f);
        return p.isAbsolute() ? p.normalize() : ws.resolve(f);
    }

    private synchronized void restartWatcher() {
        try { if (watcher != null) watcher.close(); } catch (IOException ignored) { }
        if (watchThread != null) watchThread.interrupt();
        Map<WatchKey, List<Map<String, Object>>> keys = new HashMap<>();
        try {
            watcher = FileSystems.getDefault().newWatchService();
            for (Map<String, Object> t : triggers) {
                if (!"file".equals(t.get("type")) || !Boolean.TRUE.equals(t.get("enabled"))) continue;
                WatchKey k = folder(t).register(watcher, StandardWatchEventKinds.ENTRY_CREATE);
                keys.computeIfAbsent(k, x -> new ArrayList<>()).add(t);
            }
        } catch (IOException e) { addLog(null, "watch", "error", null, "Cannot watch folders: " + e.getMessage()); return; }
        WatchService w = watcher;
        watchThread = daemon(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                WatchKey k;
                try { k = w.take(); } catch (InterruptedException | ClosedWatchServiceException e) { return; }
                for (WatchEvent<?> ev : k.pollEvents()) {
                    if (!(ev.context() instanceof Path name)) continue;
                    for (Map<String, Object> t : keys.getOrDefault(k, List.of())) {
                        PathMatcher pm = FileSystems.getDefault().getPathMatcher("glob:" + t.get("pattern"));
                        if (pm.matches(name)) {
                            Path full = folder(t).resolve(name);
                            fire(t, "file " + name, Map.of("trigger_file", full.toString()), true);
                        }
                    }
                }
                k.reset();
            }
        }, "ooa-trigger-watch");
        watchThread.start();
    }

    private void tick() {
        LocalDateTime now = LocalDateTime.now();
        for (Map<String, Object> t : triggers) {
            if (!"schedule".equals(t.get("type")) || !Boolean.TRUE.equals(t.get("enabled"))) continue;
            String id = (String) t.get("id");
            long last = lastFired.getOrDefault(id, 0L);
            boolean due;
            if (t.get("every_minutes") != null) {
                long every = Long.parseLong(String.valueOf(t.get("every_minutes")).replaceAll("\\..*", "")) * 60_000L;
                due = System.currentTimeMillis() - last >= every;
            } else {
                LocalTime at = LocalTime.parse(String.valueOf(t.get("daily_at")));
                LocalDateTime todayAt = now.toLocalDate().atTime(at);
                boolean ranToday = last > 0 && Instant.ofEpochMilli(last).atZone(ZoneId.systemDefault()).toLocalDate().equals(now.toLocalDate());
                due = !now.isBefore(todayAt) && now.isBefore(todayAt.plusMinutes(15)) && !ranToday;
            }
            if (due) fire(t, "schedule", Map.of(), false);
        }
    }

    /** Runs the trigger's bot in the background; skips if its previous run is still going. */
    void fire(Map<String, Object> t, String reason, Map<String, Object> event, boolean waitForFile) {
        String id = (String) t.get("id");
        lastFired.put(id, System.currentTimeMillis());
        if (!running.add(id)) { addLog(t, reason, "skipped", null, "previous run still in progress"); return; }
        runs.submit(() -> {
            try {
                if (waitForFile && event.get("trigger_file") instanceof String f) waitUntilStable(Path.of(f));
                RunResult r = runBot.apply((String) t.get("bot"), event);
                addLog(t, reason, r.status(), r, r.error() == null ? null : String.valueOf(r.error().get("code")));
            } catch (RuntimeException e) {
                addLog(t, reason, "failed", null, e.getMessage());
            } finally { running.remove(id); }
        });
    }

    RunResult runNow(String id) {
        Map<String, Object> t = triggers.stream().filter(x -> id.equals(x.get("id"))).findFirst()
                .orElseThrow(() -> new OoaException("TRIGGER.NOT_FOUND", "Trigger '" + id + "' not found"));
        RunResult r = runBot.apply((String) t.get("bot"), Map.of());
        addLog(t, "manual test", r.status(), r, r.error() == null ? null : String.valueOf(r.error().get("code")));
        return r;
    }

    Map<String, Object> webhook(String id, String secret, Object payload) {
        Map<String, Object> t = triggers.stream().filter(x -> id.equals(x.get("id")) && "webhook".equals(x.get("type"))).findFirst()
                .orElseThrow(() -> new OoaException("TRIGGER.NOT_FOUND", "Webhook '" + id + "' not found"));
        if (!Boolean.TRUE.equals(t.get("enabled"))) throw new OoaException("TRIGGER.DISABLED", "Webhook '" + id + "' is disabled");
        if (secret == null || !java.security.MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8), String.valueOf(t.get("secret")).getBytes(StandardCharsets.UTF_8)))
            throw new OoaException("TRIGGER.FORBIDDEN", "Wrong webhook secret");
        fire(t, "webhook", payload == null ? Map.of() : Map.of("payload", payload), false);
        return Map.of("accepted", true);
    }

    private static void waitUntilStable(Path f) {
        long prev = -1;
        for (int i = 0; i < 60; i++) {
            try { long size = Files.size(f); if (size == prev && size > 0) return; prev = size; Thread.sleep(500); }
            catch (IOException e) { return; } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

    private void addLog(Map<String, Object> t, String reason, String status, RunResult r, String detail) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("ts", LocalDateTime.now().withNano(0).toString());
        e.put("trigger", t == null ? null : t.get("id"));
        e.put("name", t == null ? null : t.get("name"));
        e.put("bot", t == null ? null : t.get("bot"));
        e.put("reason", reason);
        e.put("status", status);
        e.put("run_id", r == null ? null : r.runId());
        e.put("duration_ms", r == null ? null : r.durationMs());
        e.put("detail", detail);
        log.addFirst(e);
        while (log.size() > 200) log.removeLast();
        try { Files.writeString(ws.root.resolve("triggers-log.jsonl"), JsonLite.write(e) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
        catch (IOException ignored) { }
    }

    void stop() { clock.shutdownNow(); runs.shutdownNow(); try { if (watcher != null) watcher.close(); } catch (IOException ignored) { } }
}
