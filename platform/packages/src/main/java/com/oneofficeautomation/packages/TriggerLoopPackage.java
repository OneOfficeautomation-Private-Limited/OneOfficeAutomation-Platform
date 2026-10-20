package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Event-driven loops for attended and long-running agents. Each iterator here waits for an event (a file arriving
 * in a folder, a timer tick, a process starting) and hands it to the Loop step, which runs its nested steps for
 * that event. Put one in a core/loop step: {"iterator": "ooa.triggerloop/file_arrives", "iterator_args": {...}, "as": "event"}.
 * The loop ends when max_events or max_seconds is reached, when a nested Exit loop step runs, or when the run is stopped.
 * While waiting, the iterator checks every 200 ms whether the run was stopped.
 */
@OoaPackage(id = "ooa.triggerloop", name = "Trigger Loop", version = "1.0.0",
        description = "Wait for events (new files, timer ticks, process starts) and run the loop's nested steps once per event.")
public final class TriggerLoopPackage {
    static final String P = "TRIGGER";
    static final long SLICE_MS = 200;
    static final String D_MAX_EVENTS = "Stop after this many events; 0 = no limit.";
    static final String D_MAX_SECONDS = "Stop waiting after this many seconds in total; 0 = no limit (until the run is stopped or an Exit loop step runs).";
    static final String D_POLL = "How often to check, in seconds.";

    /**
     * Shared waiting logic: polls {@code probe} until it yields an event, the limits are reached or the run is stopped.
     * The time limit counts from the moment the loop starts.
     */
    static Iterable<Object> events(ActionContext ctx, double pollSeconds, int maxEvents, double maxSeconds, Supplier<Object> probe) {
        if (pollSeconds <= 0) throw new ActionException(P + ".BAD_INTERVAL", "The interval must be greater than 0 seconds", "Use for example 1.");
        if (maxEvents < 0 || maxSeconds < 0) throw new ActionException(P + ".BAD_LIMIT", "Limits cannot be negative", "Use 0 for no limit.");
        long pollNanos = (long) (pollSeconds * 1e9);
        return () -> new Iterator<>() {
            final long start = System.nanoTime();
            final long deadline = maxSeconds > 0 ? start + (long) (maxSeconds * 1e9) : Long.MAX_VALUE;
            int delivered = 0;
            Object next;
            boolean done;

            public boolean hasNext() {
                if (next != null) return true;
                if (done) return false;
                if (maxEvents > 0 && delivered >= maxEvents) { done = true; return false; }
                while (true) {
                    ctx.checkCancelled();
                    Object e = probe.get();
                    if (e != null) { next = e; return true; }
                    long now = System.nanoTime();
                    if (now >= deadline) { done = true; return false; }
                    sleepUntil(ctx, Math.min(deadline, now + pollNanos));
                }
            }

            public Object next() {
                if (!hasNext()) throw new NoSuchElementException();
                Object e = next; next = null; delivered++;
                return e;
            }
        };
    }

    /** Sleeps in short slices so a stop request from Control Center ends the wait promptly. */
    static void sleepUntil(ActionContext ctx, long deadlineNanos) {
        while (true) {
            ctx.checkCancelled();
            long left = deadlineNanos - System.nanoTime();
            if (left <= 0) return;
            try { Thread.sleep(Math.max(1, Math.min(SLICE_MS, left / 1_000_000))); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw new OoaException("RUN.CANCELLED", "Run thread interrupted while waiting for an event"); }
        }
    }

    // ---------- file arrives ----------

    record Seen(long size, long modified) {}

    @LoopIterator(id = "file_arrives", name = "Each new file in folder",
            description = "Wait for files to appear in a folder and run the nested steps once for each, oldest first; gives path, name, size and time.")
    public Iterable<Object> fileArrives(ActionContext ctx,
            @Input(name = "folder", type = OoaType.FILE, description = "Folder to watch.") String folder,
            @Input(name = "pattern", type = OoaType.STRING, required = false, defaultValue = "\"*\"", description = "Glob for file names, e.g. *.pdf.") String pattern,
            @Input(name = "include_existing", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Also handle files already in the folder when the loop starts.") boolean includeExisting,
            @Input(name = "settle_seconds", type = OoaType.NUMBER, required = false, defaultValue = "1", description = "Wait until a file has not changed for this long, so half-copied files are skipped until complete.") double settle,
            @Input(name = "poll_seconds", type = OoaType.NUMBER, required = false, defaultValue = "1", description = D_POLL) double poll,
            @Input(name = "max_events", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_MAX_EVENTS) int maxEvents,
            @Input(name = "max_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_MAX_SECONDS) double maxSeconds) {
        Path dir = ctx.resolvePath(folder);
        if (!Files.isDirectory(dir)) throw new ActionException(P + ".NO_FOLDER", "Folder not found: " + dir, "Create the folder or check the path.");
        if (settle < 0) throw new ActionException(P + ".BAD_LIMIT", "settle_seconds cannot be negative");
        PathMatcher m;
        try { m = FileSystems.getDefault().getPathMatcher("glob:" + (pattern == null || pattern.isBlank() ? "*" : pattern)); }
        catch (IllegalArgumentException e) { throw new ActionException(P + ".BAD_PATTERN", "Invalid file pattern '" + pattern + "'", "Use a glob such as *.csv."); }
        Set<Path> handled = new HashSet<>();
        if (!includeExisting) handled.addAll(list(dir, m).keySet());
        Map<Path, Seen> pending = new HashMap<>();
        long settleMs = (long) (settle * 1000);
        return events(ctx, poll, maxEvents, maxSeconds, () -> {
            Map<Path, Seen> now = list(dir, m);
            pending.keySet().retainAll(now.keySet());
            List<Path> ready = new ArrayList<>();
            long wall = System.currentTimeMillis();
            for (var e : now.entrySet()) {
                if (handled.contains(e.getKey())) continue;
                Seen before = pending.put(e.getKey(), e.getValue());
                boolean unchanged = settleMs == 0 || (before != null && before.equals(e.getValue()));
                if (unchanged && wall - e.getValue().modified() >= settleMs) ready.add(e.getKey());
            }
            if (ready.isEmpty()) return null;
            ready.sort(Comparator.comparingLong((Path p) -> now.get(p).modified()).thenComparing(Path::toString));
            Path p = ready.get(0);
            handled.add(p);
            pending.remove(p);
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("event", "file");
            ev.put("path", p.toString());
            ev.put("name", p.getFileName().toString());
            ev.put("size", BigDecimal.valueOf(now.get(p).size()));
            ev.put("time", LocalDateTime.now().withNano(0));
            return ev;
        });
    }

    static Map<Path, Seen> list(Path dir, PathMatcher m) {
        Map<Path, Seen> out = new HashMap<>();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                if (!m.matches(p.getFileName())) continue;
                try {
                    BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class);
                    if (a.isRegularFile()) out.put(p, new Seen(a.size(), a.lastModifiedTime().toMillis()));
                } catch (IOException ignored) { /* file vanished or is locked; look again next time */ }
            }
        } catch (NoSuchFileException e) {
            throw new ActionException(P + ".NO_FOLDER", "The watched folder was removed: " + dir, "Recreate the folder and start the agent again.");
        } catch (IOException e) {
            throw new ActionException(P + ".IO_ERROR", "Cannot read folder " + dir + ": " + e.getMessage(), null, true);
        }
        return out;
    }

    // ---------- timer ----------

    @LoopIterator(id = "timer_tick", name = "Every interval",
            description = "Run the nested steps on a fixed interval, e.g. every 60 seconds; gives the tick number and time.")
    public Iterable<Object> timerTick(ActionContext ctx,
            @Input(name = "interval_seconds", type = OoaType.NUMBER, description = "Seconds between ticks.") double interval,
            @Input(name = "run_immediately", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Fire the first tick at once instead of after one interval.") boolean immediately,
            @Input(name = "max_events", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_MAX_EVENTS) int maxEvents,
            @Input(name = "max_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_MAX_SECONDS) double maxSeconds) {
        if (interval <= 0) throw new ActionException(P + ".BAD_INTERVAL", "interval_seconds must be greater than 0", "Use for example 60.");
        long step = (long) (interval * 1e9);
        long[] due = {System.nanoTime() + (immediately ? 0 : step)};
        int[] n = {0};
        // Ticks are scheduled from the start time, so slow nested steps do not make the timer drift; missed ticks are skipped.
        return events(ctx, Math.min(interval, 0.2), maxEvents, maxSeconds, () -> {
            long now = System.nanoTime();
            if (now < due[0]) return null;
            while (due[0] <= now) due[0] += step;
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("event", "tick");
            ev.put("tick", BigDecimal.valueOf(++n[0]));
            ev.put("time", LocalDateTime.now().withNano(0));
            return ev;
        });
    }

    // ---------- process starts ----------

    static String processName(ProcessHandle p) {
        return p.info().command().map(c -> Path.of(c).getFileName().toString()).orElse("");
    }

    static boolean nameMatches(String actual, String wanted) {
        String a = actual.toLowerCase(Locale.ROOT), w = wanted.strip().toLowerCase(Locale.ROOT);
        return !w.isEmpty() && a.replaceFirst("\\.exe$", "").equals(w.replaceFirst("\\.exe$", ""));
    }

    @LoopIterator(id = "process_starts", name = "Each time a program starts",
            description = "Watch for a program to start (by executable name, e.g. excel or notepad.exe) and run the nested steps for each new process.")
    public Iterable<Object> processStarts(ActionContext ctx,
            @Input(name = "process_name", type = OoaType.STRING, example = "notepad.exe", description = "Executable name, with or without .exe; not case-sensitive.") String name,
            @Input(name = "poll_seconds", type = OoaType.NUMBER, required = false, defaultValue = "1", description = D_POLL) double poll,
            @Input(name = "max_events", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_MAX_EVENTS) int maxEvents,
            @Input(name = "max_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_MAX_SECONDS) double maxSeconds) {
        if (name == null || name.isBlank()) throw new ActionException(P + ".NO_PROCESS", "Give the program's executable name", "For example notepad.exe.");
        Set<Long> known = new HashSet<>();
        ProcessHandle.allProcesses().filter(p -> nameMatches(processName(p), name)).forEach(p -> known.add(p.pid()));
        return events(ctx, poll, maxEvents, maxSeconds, () -> {
            Set<Long> alive = new HashSet<>();
            ProcessHandle found = null;
            for (ProcessHandle p : (Iterable<ProcessHandle>) ProcessHandle.allProcesses()::iterator) {
                if (!nameMatches(processName(p), name)) continue;
                alive.add(p.pid());
                if (found == null && !known.contains(p.pid())) found = p;
            }
            known.retainAll(alive);   // a reused pid later counts as a new start
            if (found == null) return null;
            known.add(found.pid());
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("event", "process");
            ev.put("pid", BigDecimal.valueOf(found.pid()));
            ev.put("name", processName(found));
            ev.put("command", found.info().command().orElse(""));
            ev.put("started", found.info().startInstant().map(Instant::toString).orElse(""));
            ev.put("time", LocalDateTime.now().withNano(0));
            return ev;
        });
    }

    @Condition(id = "process_running", name = "Program is running", description = "True when at least one process with this executable name is running.")
    public boolean processRunning(ActionContext ctx,
            @Input(name = "process_name", type = OoaType.STRING, description = "Executable name, with or without .exe.") String name) {
        if (name == null || name.isBlank()) throw new ActionException(P + ".NO_PROCESS", "Give the program's executable name");
        return ProcessHandle.allProcesses().anyMatch(p -> nameMatches(processName(p), name));
    }
}
