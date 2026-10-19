package com.oneofficeautomation.agent;

import com.oneofficeautomation.sdk.OoaException;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** Watches folders on this device for Control Room file triggers and reports new matching files. */
final class FileTriggers {
    private final AgentClient client;
    private final Consumer<String> log;
    private String signature = "";
    private WatchService watcher;
    private Thread thread;

    FileTriggers(AgentClient client, Consumer<String> log) { this.client = client; this.log = log; }

    @SuppressWarnings("unchecked")
    synchronized void update(List<Object> triggers) {
        String sig = triggers.toString();
        if (sig.equals(signature)) return;
        signature = sig;
        stop();
        if (triggers.isEmpty()) return;
        Map<WatchKey, List<Map<String, Object>>> keys = new HashMap<>();
        try {
            watcher = FileSystems.getDefault().newWatchService();
            for (Object o : triggers) {
                Map<String, Object> t = (Map<String, Object>) o;
                Path dir = Path.of(String.valueOf(t.get("folder")));
                if (!Files.isDirectory(dir)) { log.accept("File trigger " + t.get("id") + ": folder not found on this device: " + dir); continue; }
                keys.computeIfAbsent(dir.register(watcher, StandardWatchEventKinds.ENTRY_CREATE), k -> new ArrayList<>()).add(t);
                log.accept("Watching " + dir + " for " + t.get("pattern") + " (trigger " + t.get("id") + ")");
            }
        } catch (IOException e) { log.accept("Cannot watch folders: " + e.getMessage()); return; }
        WatchService w = watcher;
        thread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                WatchKey k;
                try { k = w.take(); } catch (InterruptedException | ClosedWatchServiceException e) { return; }
                Path dir = (Path) k.watchable();
                for (WatchEvent<?> ev : k.pollEvents()) {
                    if (!(ev.context() instanceof Path name)) continue;
                    for (Map<String, Object> t : keys.getOrDefault(k, List.of())) {
                        if (!FileSystems.getDefault().getPathMatcher("glob:" + t.get("pattern")).matches(name)) continue;
                        Path full = dir.resolve(name);
                        waitStable(full);
                        try { client.post("trigger-fired", Map.of("trigger_id", t.get("id"), "file", full.toString())); log.accept("Trigger " + t.get("id") + " fired for " + full); }
                        catch (OoaException e) { log.accept("Trigger " + t.get("id") + " could not start its bot: " + e.getMessage()); }
                    }
                }
                k.reset();
            }
        }, "ooa-agent-file-triggers");
        thread.setDaemon(true);
        thread.start();
    }

    static void waitStable(Path f) {
        long prev = -1;
        for (int i = 0; i < 60; i++) {
            try { long s = Files.size(f); if (s == prev && s > 0) return; prev = s; Thread.sleep(500); }
            catch (IOException e) { return; } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

    synchronized void stop() {
        if (thread != null) thread.interrupt();
        try { if (watcher != null) watcher.close(); } catch (IOException ignored) { }
        watcher = null; thread = null;
    }
}
