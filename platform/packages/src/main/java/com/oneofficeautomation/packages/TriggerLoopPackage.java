package com.oneofficeautomation.packages;

import com.oneofficeautomation.packages.ui.DesktopEvents;
import com.oneofficeautomation.packages.ui.InputTriggers;
import com.oneofficeautomation.sdk.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Event-driven loops for attended and long-running agents. Each iterator here waits for an event (a file arriving
 * in a folder, a timer tick, a process starting, a configured key pressed or a click in a window) and hands it to the Loop step, which runs its nested steps for
 * that event. Put one in a core/loop step: {"iterator": "ooa.triggerloop/file_arrives", "iterator_args": {...}, "as": "event"}.
 * The loop ends when max_events or max_seconds is reached, when a nested Exit loop step runs, or when the run is stopped.
 * While waiting, the iterator checks every 200 ms whether the run was stopped.
 */
@OoaPackage(id = "ooa.triggerloop", name = "Trigger Loop", version = "1.0.0",
        description = "Wait for events (file and folder changes, timer ticks, programs starting or stopping, windows opening or closing, services changing state, form events, "
                + "new e-mails or records, key presses, clicks) and run the loop's nested steps once per event.")
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

    /** Sleeps in short slices so a stop request from One CommandCenter ends the wait promptly. */
    static void sleepUntil(ActionContext ctx, long deadlineNanos) {
        while (true) {
            ctx.checkCancelled();
            long left = deadlineNanos - System.nanoTime();
            if (left <= 0) return;
            try { Thread.sleep(Math.max(1, Math.min(SLICE_MS, left / 1_000_000))); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw new OoaException("RUN.CANCELLED", "Run thread interrupted while waiting for an event", "The run was stopped before this step finished. Run the agent again when it should go on."); }
        }
    }

    // ---------- file arrives ----------

    record Seen(long size, long modified) {}

    @LoopIterator(id = "file_arrives", name = "Each new or changed file in folder",
            description = "Wait for files (or folders) to appear, change, go or be renamed in a folder and run the nested steps once for each; gives the change, path, name, size and time (UTC, like every DateTime).")
    public Iterable<Object> fileArrives(ActionContext ctx,
            @Input(name = "folder", type = OoaType.FILE, description = "Folder to watch.") String folder,
            @Input(name = "pattern", type = OoaType.STRING, required = false, defaultValue = "\"*\"", description = "Glob for file names, e.g. *.pdf.") String pattern,
            @Input(name = "include_existing", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Also handle files already in the folder when the loop starts.") boolean includeExisting,
            @Input(name = "settle_seconds", type = OoaType.NUMBER, required = false, defaultValue = "1", description = "Wait until a file has not changed for this long, so half-copied files are skipped until complete.") double settle,
            @Input(name = "poll_seconds", type = OoaType.NUMBER, required = false, defaultValue = "1", description = D_POLL) double poll,
            @Input(name = "max_events", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_MAX_EVENTS) int maxEvents,
            @Input(name = "max_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_MAX_SECONDS) double maxSeconds,
            @Input(name = "event", type = OoaType.STRING, required = false, defaultValue = "\"created\"", enumValues = {"created", "modified", "deleted", "renamed", "any"},
                    description = "Which change starts the steps: a file that appears (created), changes, goes, is renamed, or any of these.") String event,
            @Input(name = "watch", type = OoaType.STRING, required = false, defaultValue = "\"files\"", enumValues = {"files", "folders", "both"},
                    description = "Watch files, folders, or both.") String watch,
            @Input(name = "include_subfolders", type = OoaType.BOOLEAN, required = false, defaultValue = "false",
                    description = "Also watch everything in the folders under it.") boolean subfolders) {
        Path dir = ctx.resolvePath(folder);
        if (!Files.isDirectory(dir)) throw new ActionException(P + ".NO_FOLDER", "Folder not found: " + dir, "Create the folder or check the path.");
        if (settle < 0) throw new ActionException(P + ".BAD_LIMIT", "settle_seconds cannot be negative", "Set settle_seconds to 0 or more.");
        PathMatcher m;
        try { m = FileSystems.getDefault().getPathMatcher("glob:" + (pattern == null || pattern.isBlank() ? "*" : pattern)); }
        catch (IllegalArgumentException e) { throw new ActionException(P + ".BAD_PATTERN", "Invalid file pattern '" + pattern + "'", "Use a glob such as *.csv."); }
        String ev0 = event == null || event.isBlank() ? "created" : event.strip(), w = watch == null || watch.isBlank() ? "files" : watch.strip();
        if (!List.of("created", "modified", "deleted", "renamed", "any").contains(ev0))
            throw new ActionException(P + ".BAD_EVENT", "Unknown change '" + event + "'", "Use created, modified, deleted, renamed or any.");
        if (!List.of("files", "folders", "both").contains(w)) throw new ActionException(P + ".BAD_EVENT", "Unknown watch '" + watch + "'", "Use files, folders or both.");
        if (!ev0.equals("created") || !w.equals("files") || subfolders) return changes(ctx, dir, m, ev0, w, subfolders, includeExisting, settle, poll, maxEvents, maxSeconds);
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
            ev.put("time", LocalDateTime.now(java.time.ZoneOffset.UTC).withNano(0));  // UTC, like every DateTime
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
            throw new ActionException(P + ".IO_ERROR", "Cannot read folder " + dir + ": " + e.getMessage(), "Check that the file or folder exists, is not open in another program, and that the runner's account may read and write it.", true);
        }
        return out;
    }

    public Iterable<Object> fileArrives(ActionContext ctx, String folder, String pattern, boolean includeExisting, double settle, double poll, int maxEvents, double maxSeconds) {
        return fileArrives(ctx, folder, pattern, includeExisting, settle, poll, maxEvents, maxSeconds, "created", "files", false);
    }

    /** A file or folder as seen by one look at the folder. */
    record Entry(long size, long modified, boolean folder) {}

    /** Files and/or folders under dir (all levels with deep) whose name fits the pattern. */
    static Map<Path, Entry> entries(Path dir, PathMatcher m, String watch, boolean deep) {
        Map<Path, Entry> out = new HashMap<>();
        try (Stream<Path> s = deep ? Files.walk(dir) : Files.list(dir)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                if (p.equals(dir) || !m.matches(p.getFileName())) continue;
                try {
                    BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class);
                    boolean f = a.isDirectory();
                    if ((f && watch.equals("files")) || (!f && watch.equals("folders")) || (!f && !a.isRegularFile())) continue;
                    out.put(p, new Entry(f ? 0 : a.size(), f ? 0 : a.lastModifiedTime().toMillis(), f));
                } catch (IOException ignored) { /* vanished or locked; look again next time */ }
            }
        } catch (NoSuchFileException e) {
            throw new ActionException(P + ".NO_FOLDER", "The watched folder was removed: " + dir, "Recreate the folder and start the agent again.");
        } catch (IOException | UncheckedIOException e) {
            throw new ActionException(P + ".IO_ERROR", "Cannot read folder " + dir + ": " + e.getMessage(), "Check that the folder exists and that the runner's account may read it.", true);
        }
        return out;
    }

    /**
     * Changes in a folder found by comparing one look with the last: created, modified (a file's size or time), deleted, and
     * renamed (one entry gone and one of the same kind, size and time new in the same look). A file that appears or changes
     * is handed over once it has stayed the same for settle_seconds, so half-copied files wait until complete.
     */
    static Iterable<Object> changes(ActionContext ctx, Path dir, PathMatcher m, String event, String watch, boolean deep, boolean includeExisting,
                                    double settle, double poll, int maxEvents, double maxSeconds) {
        long settleMs = (long) (settle * 1000);
        Map<Path, Entry> last = includeExisting && (event.equals("created") || event.equals("any")) ? new HashMap<>() : entries(dir, m, watch, deep);
        Map<Path, Object[]> settling = new HashMap<>();   // path -> {change, entry}
        Deque<Map<String, Object>> ready = new ArrayDeque<>();
        java.util.function.Predicate<String> wanted = c -> event.equals("any") || event.equals(c);
        return events(ctx, poll, maxEvents, maxSeconds, () -> {
            if (!ready.isEmpty()) return ready.poll();
            Map<Path, Entry> now = entries(dir, m, watch, deep);
            List<Path> gone = new ArrayList<>(), added = new ArrayList<>();
            for (Path p : last.keySet()) if (!now.containsKey(p)) gone.add(p);
            for (Path p : now.keySet()) if (!last.containsKey(p)) added.add(p);
            gone.sort(null); added.sort(null);
            // a rename: the same kind, size and time under a new name
            for (Iterator<Path> g = gone.iterator(); g.hasNext(); ) {
                Path old = g.next();
                Entry e = last.get(old);
                Path to = added.stream().filter(a -> { Entry n = now.get(a); return n.folder() == e.folder() && n.size() == e.size() && n.modified() == e.modified(); }).findFirst().orElse(null);
                if (to == null) continue;
                g.remove(); added.remove(to); settling.remove(old);
                if (wanted.test("renamed")) ready.add(change("renamed", to, now.get(to), old));
                else { if (wanted.test("deleted")) ready.add(change("deleted", old, e, null)); if (wanted.test("created")) ready.add(change("created", to, now.get(to), null)); }
            }
            for (Path p : gone) { settling.remove(p); if (wanted.test("deleted")) ready.add(change("deleted", p, last.get(p), null)); }
            for (Path p : added) if (wanted.test("created")) settling.put(p, new Object[]{"created", now.get(p)});
            for (var e : now.entrySet())
                if (last.containsKey(e.getKey()) && !last.get(e.getKey()).equals(e.getValue()) && !e.getValue().folder() && wanted.test("modified"))
                    settling.putIfAbsent(e.getKey(), new Object[]{"modified", e.getValue()});
            long wall = System.currentTimeMillis();
            List<Path> done = new ArrayList<>();
            for (var e : settling.entrySet()) {
                Entry seen = (Entry) e.getValue()[1], cur = now.get(e.getKey());
                if (cur == null) { done.add(e.getKey()); continue; }
                if (!cur.equals(seen)) { e.getValue()[1] = cur; if (settleMs > 0) continue; }
                if (cur.folder() || settleMs == 0 || wall - cur.modified() >= settleMs) { ready.add(change((String) e.getValue()[0], e.getKey(), cur, null)); done.add(e.getKey()); }
            }
            done.forEach(settling::remove);
            last.clear(); last.putAll(now);
            return ready.poll();
        });
    }

    static Map<String, Object> change(String what, Path p, Entry e, Path old) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("event", e.folder() ? "folder" : "file");
        ev.put("change", what);
        ev.put("path", p.toString());
        ev.put("name", p.getFileName().toString());
        if (old != null) { ev.put("old_path", old.toString()); ev.put("old_name", old.getFileName().toString()); }
        ev.put("is_folder", e.folder());
        ev.put("size", BigDecimal.valueOf(e.size()));
        ev.put("time", LocalDateTime.now(java.time.ZoneOffset.UTC).withNano(0));
        return ev;
    }

    // ---------- timer ----------

    @LoopIterator(id = "timer_tick", name = "Every interval",
            description = "Run the nested steps on a fixed interval, e.g. every 60 seconds; gives the tick number and time (UTC).")
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
            ev.put("time", LocalDateTime.now(java.time.ZoneOffset.UTC).withNano(0));  // UTC, like every DateTime
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

    @LoopIterator(id = "process_starts", name = "Each time a program starts or stops",
            description = "Watch for a program (by executable name, e.g. excel or notepad.exe) to start, or to stop, and run the nested steps for each process.")
    public Iterable<Object> processStarts(ActionContext ctx,
            @Input(name = "process_name", type = OoaType.STRING, example = "notepad.exe", description = "Executable name, with or without .exe; not case-sensitive.") String name,
            @Input(name = "poll_seconds", type = OoaType.NUMBER, required = false, defaultValue = "1", description = D_POLL) double poll,
            @Input(name = "max_events", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_MAX_EVENTS) int maxEvents,
            @Input(name = "max_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_MAX_SECONDS) double maxSeconds,
            @Input(name = "event", type = OoaType.STRING, required = false, defaultValue = "\"starts\"", enumValues = {"starts", "stops"},
                    description = "Start the steps when the program starts, or when one of its processes ends.") String event) {
        if (name == null || name.isBlank()) throw new ActionException(P + ".NO_PROCESS", "Give the program's executable name", "For example notepad.exe.");
        if ("stops".equals(event)) return processStops(ctx, name, poll, maxEvents, maxSeconds);
        if (event != null && !event.isBlank() && !"starts".equals(event)) throw new ActionException(P + ".BAD_EVENT", "Unknown event '" + event + "'", "Use starts or stops.");
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
            ev.put("change", "started");
            ev.put("pid", BigDecimal.valueOf(found.pid()));
            ev.put("name", processName(found));
            ev.put("command", found.info().command().orElse(""));
            ev.put("started", found.info().startInstant().map(Instant::toString).orElse(""));
            ev.put("time", LocalDateTime.now(java.time.ZoneOffset.UTC).withNano(0));  // UTC, like every DateTime
            return ev;
        });
    }

    public Iterable<Object> processStarts(ActionContext ctx, String name, double poll, int maxEvents, double maxSeconds) {
        return processStarts(ctx, name, poll, maxEvents, maxSeconds, "starts");
    }

    /** Each process of the program that was running and has ended since the last look. */
    static Iterable<Object> processStops(ActionContext ctx, String name, double poll, int maxEvents, double maxSeconds) {
        Map<Long, String[]> running = new HashMap<>();
        ProcessHandle.allProcesses().filter(p -> nameMatches(processName(p), name)).forEach(p -> running.put(p.pid(), new String[]{processName(p), p.info().command().orElse("")}));
        Deque<Map<String, Object>> ready = new ArrayDeque<>();
        return events(ctx, poll, maxEvents, maxSeconds, () -> {
            if (!ready.isEmpty()) return ready.poll();
            Map<Long, String[]> now = new HashMap<>();
            ProcessHandle.allProcesses().filter(p -> nameMatches(processName(p), name)).forEach(p -> now.put(p.pid(), new String[]{processName(p), p.info().command().orElse("")}));
            for (var e : running.entrySet())
                if (!now.containsKey(e.getKey())) {
                    Map<String, Object> ev = new LinkedHashMap<>();
                    ev.put("event", "process"); ev.put("change", "stopped");
                    ev.put("pid", BigDecimal.valueOf(e.getKey())); ev.put("name", e.getValue()[0]); ev.put("command", e.getValue()[1]);
                    ev.put("time", LocalDateTime.now(java.time.ZoneOffset.UTC).withNano(0));
                    ready.add(ev);
                }
            running.clear(); running.putAll(now);
            return ready.poll();
        });
    }

    // ---------- windows opening and closing ----------

    /** The titles of the windows on the desktop now (Windows: packages-windows); tests put fakes here. */
    static Supplier<List<String>> windowTitles = () -> DesktopEvents.installed().map(DesktopEvents::windowTitles)
            .orElseThrow(() -> new ActionException(P + ".WINDOWS_ONLY", "Watching windows needs a Windows runner with the Window toolkit installed",
                    "Run this agent on a Windows runner with a signed-in desktop."));

    @LoopIterator(id = "window_changes", name = "Each time a window opens or closes",
            description = "Watch for a window whose title fits a pattern to open, or to close, and run the nested steps for each; gives the change and the window's title.")
    public Iterable<Object> windowChanges(ActionContext ctx,
            @Input(name = "title", type = OoaType.STRING, example = "*Invoice*", description = "Window title: text the title contains, a pattern with * and ?, or re: and a regular expression.") String title,
            @Input(name = "event", type = OoaType.STRING, required = false, defaultValue = "\"opens\"", enumValues = {"opens", "closes", "any"},
                    description = "Start the steps when such a window opens, closes, or either.") String event,
            @Input(name = "include_existing", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Also count windows already open when the loop starts as opening.") boolean includeExisting,
            @Input(name = "poll_seconds", type = OoaType.NUMBER, required = false, defaultValue = "1", description = D_POLL) double poll,
            @Input(name = "max_events", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_MAX_EVENTS) int maxEvents,
            @Input(name = "max_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_MAX_SECONDS) double maxSeconds) {
        if (title == null || title.isBlank()) throw new ActionException(P + ".NO_WINDOW", "Give the window's title or a pattern", "For example *Invoice*.");
        com.oneofficeautomation.packages.ui.TitlePattern match;
        try { match = com.oneofficeautomation.packages.ui.TitlePattern.of(title.strip()); }
        catch (IllegalArgumentException e) { throw new ActionException(P + ".BAD_PATTERN", "Invalid title pattern '" + title + "': " + e.getMessage(), "Check the regular expression after re:."); }
        String ev0 = event == null || event.isBlank() ? "opens" : event.strip();
        if (!List.of("opens", "closes", "any").contains(ev0)) throw new ActionException(P + ".BAD_EVENT", "Unknown event '" + event + "'", "Use opens, closes or any.");
        java.util.function.Function<List<String>, Map<String, Integer>> count = titles -> {
            Map<String, Integer> c = new TreeMap<>();
            for (String t : titles) if (match.test(t)) c.merge(t, 1, Integer::sum);
            return c;
        };
        Map<String, Integer> last = includeExisting ? new TreeMap<>() : count.apply(windowTitles.get());
        Deque<Map<String, Object>> ready = new ArrayDeque<>();
        return events(ctx, poll, maxEvents, maxSeconds, () -> {
            if (!ready.isEmpty()) return ready.poll();
            Map<String, Integer> now = count.apply(windowTitles.get());
            Set<String> all = new TreeSet<>(last.keySet()); all.addAll(now.keySet());
            for (String t : all) {
                int d = now.getOrDefault(t, 0) - last.getOrDefault(t, 0);
                String what = d > 0 ? "opened" : "closed";
                if (d == 0 || (d > 0 && ev0.equals("closes")) || (d < 0 && ev0.equals("opens"))) continue;
                for (int i = 0; i < Math.abs(d); i++) {
                    Map<String, Object> ev = new LinkedHashMap<>();
                    ev.put("event", "window"); ev.put("change", what); ev.put("title", t);
                    ev.put("time", LocalDateTime.now(java.time.ZoneOffset.UTC).withNano(0));
                    ready.add(ev);
                }
            }
            last.clear(); last.putAll(now);
            return ready.poll();
        });
    }

    // ---------- Windows services ----------

    /** A service's state now (RUNNING, STOPPED, PAUSED or a pending state); tests put fakes here. */
    static java.util.function.BiFunction<ActionContext, String, String> serviceState = ServicesPackage::status;

    @LoopIterator(id = "service_changes", name = "Each time a service changes state",
            description = "Windows only. Watch a service and run the nested steps when it starts, stops, pauses or resumes; gives the change and the new state.")
    public Iterable<Object> serviceChanges(ActionContext ctx,
            @Input(name = "service", type = OoaType.STRING, example = "Spooler", description = "Service name (not its display name), e.g. Spooler.") String service,
            @Input(name = "event", type = OoaType.STRING, required = false, defaultValue = "\"any\"", enumValues = {"starts", "stops", "pauses", "resumes", "any"},
                    description = "Which change starts the steps.") String event,
            @Input(name = "include_current", type = OoaType.BOOLEAN, required = false, defaultValue = "false",
                    description = "Also run the steps once at the start for the state the service is in (is running, has stopped, is paused).") boolean includeCurrent,
            @Input(name = "poll_seconds", type = OoaType.NUMBER, required = false, defaultValue = "5", description = D_POLL) double poll,
            @Input(name = "max_events", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_MAX_EVENTS) int maxEvents,
            @Input(name = "max_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_MAX_SECONDS) double maxSeconds) {
        if (service == null || service.isBlank()) throw new ActionException(P + ".NO_SERVICE", "Give the service's name", "For example Spooler.");
        String name = service.strip(), ev0 = event == null || event.isBlank() ? "any" : event.strip();
        if (!List.of("starts", "stops", "pauses", "resumes", "any").contains(ev0)) throw new ActionException(P + ".BAD_EVENT", "Unknown event '" + event + "'", "Use starts, stops, pauses, resumes or any.");
        String[] last = {stable(serviceState.apply(ctx, name))};
        boolean[] first = {includeCurrent};
        return events(ctx, poll, maxEvents, maxSeconds, () -> {
            if (first[0]) {
                first[0] = false;
                if (last[0] != null) return serviceEvent(name, switch (last[0]) { case "RUNNING" -> "is_running"; case "PAUSED" -> "is_paused"; default -> "has_stopped"; }, last[0]);
            }
            String now = stable(serviceState.apply(ctx, name));
            if (now == null || now.equals(last[0])) return null;
            String before = last[0];
            last[0] = now;
            String change = switch (now) { case "RUNNING" -> "PAUSED".equals(before) ? "resumes" : "starts"; case "PAUSED" -> "pauses"; default -> "stops"; };
            return ev0.equals("any") || ev0.equals(change) ? serviceEvent(name, change, now) : null;
        });
    }

    /** RUNNING, STOPPED or PAUSED; null for a pending state (the change is not finished yet). */
    static String stable(String state) {
        String s = state == null ? "" : state.strip().toUpperCase(Locale.ROOT);
        return s.equals("RUNNING") || s.equals("STOPPED") || s.equals("PAUSED") ? s : null;
    }

    static Map<String, Object> serviceEvent(String name, String change, String state) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("event", "service"); ev.put("change", change); ev.put("service", name); ev.put("state", state);
        ev.put("time", LocalDateTime.now(java.time.ZoneOffset.UTC).withNano(0));
        return ev;
    }

    // ---------- interactive forms ----------

    @LoopIterator(id = "form_events", name = "Each event on an open form",
            description = "Run the nested steps each time the person presses a button, changes a field or submits the open form (Display form with Wait off); the loop ends when the form closes.")
    public Iterable<Object> formEvents(ActionContext ctx,
            @Input(name = "name", type = OoaType.STRING, description = "Name of the open form, as given when it was displayed.") String name,
            @Input(name = "events", type = OoaType.STRING, required = false, defaultValue = "\"any\"", enumValues = {"any", "button", "change", "submit"},
                    description = "Which events start the steps.") String events,
            @Input(name = "element", type = OoaType.STRING, required = false, description = "Only events of this field or button (its id in the form designer); any when empty.") String element,
            @Input(name = "max_events", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_MAX_EVENTS) int maxEvents,
            @Input(name = "max_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_MAX_SECONDS) double maxSeconds) {
        FormsPackage.openForm(ctx, name);   // FORMS.NOT_OPEN at once
        String types = events == null || events.isBlank() ? "any" : events.strip();
        boolean[] closed = {false};
        Iterator<Object> it = events(ctx, 0.05, maxEvents, maxSeconds, () -> {
            if (closed[0]) return null;
            Map<String, Object> e = FormsPackage.nextEvent(ctx, name, types, element, 0.2);
            if (e != null && "close".equals(e.get("event"))) { closed[0] = true; return null; }
            return e;
        }).iterator();
        return () -> new Iterator<>() {
            public boolean hasNext() { return !closed[0] && it.hasNext(); }
            public Object next() { return it.next(); }
        };
    }

    // ---------- new e-mails, records and other items ----------

    @LoopIterator(id = "new_items", name = "Each new e-mail, record or item",
            description = "Read a loop type (such as Each email in a mailbox, Outlook, Exchange or Gmail) or a command's list (such as ServiceNow Query records) again and again, "
                    + "and run the nested steps for each item not seen before, or changed since.")
    public Iterable<Object> newItems(ActionContext ctx,
            @Input(name = "source", type = OoaType.STRING, example = "ooa.email/emails",
                    description = "The loop type (toolkit/loop type) or command (toolkit/command) whose items are watched.") String source,
            @Input(name = "source_inputs", type = OoaType.DICTIONARY, required = false,
                    description = "The source's fields, such as the folder, the sender or subject filter, or the query; keep secrets in a saved connection.") Map<String, Object> inputs,
            @Input(name = "connection", type = OoaType.STRING, required = false, description = "Saved connection that gives the source its address and sign-in.") String connection,
            @Input(name = "list_output", type = OoaType.STRING, required = false, description = "For a command: the output that holds the list; its first list output when empty.") String listOutput,
            @Input(name = "key", type = OoaType.STRING, required = false, example = "id",
                    description = "Field that tells items apart, such as id, message_id or sys_id; the whole item when empty.") String key,
            @Input(name = "changed_field", type = OoaType.STRING, required = false, example = "sys_updated_on",
                    description = "Also run the steps for an item seen before when this field has changed (an updated record).") String changedField,
            @Input(name = "include_existing", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Also handle the items there when the loop starts.") boolean includeExisting,
            @Input(name = "poll_seconds", type = OoaType.NUMBER, required = false, defaultValue = "120", min = 1, description = "How often to read the source, in seconds.") double poll,
            @Input(name = "max_events", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_MAX_EVENTS) int maxEvents,
            @Input(name = "max_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_MAX_SECONDS) double maxSeconds) {
        if (source == null || !source.contains("/")) throw new ActionException(P + ".NO_SOURCE", "Choose the loop type or command to watch", "For example ooa.email/emails or ooa.servicenow/query.");
        @SuppressWarnings("unchecked")
        java.util.function.Function<Map<String, Object>, Object> read = ctx.service(com.oneofficeautomation.runner.Runner.ITEM_SOURCE_SERVICE, java.util.function.Function.class);
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("source", source.strip());
        req.put("inputs", inputs == null ? Map.of() : inputs);
        req.put("connection", connection);
        req.put("list_output", listOutput);
        req.put("step", ctx.stepId());
        java.util.function.Supplier<List<Object>> look = () -> {
            Object r = read.apply(req);
            return r instanceof List<?> l ? new ArrayList<>(l) : List.of();
        };
        Map<String, String> seen = new HashMap<>();   // key -> changed field's value
        if (!includeExisting) for (Object item : look.get()) seen.put(itemKey(item, key), changeMark(item, changedField));
        Deque<Map<String, Object>> ready = new ArrayDeque<>();
        boolean[] firstLook = {includeExisting};
        long pollMs = (long) (poll * 1000);
        long[] next = {includeExisting ? 0 : System.currentTimeMillis() + pollMs};
        return events(ctx, Math.min(poll, 1), maxEvents, maxSeconds, () -> {
            if (!ready.isEmpty()) return ready.poll();
            if (System.currentTimeMillis() < next[0]) return null;
            next[0] = System.currentTimeMillis() + pollMs;
            firstLook[0] = false;
            for (Object item : look.get()) {
                String k = itemKey(item, key), mark = changeMark(item, changedField);
                String was = seen.get(k);
                boolean isNew = !seen.containsKey(k), changed = !isNew && changedField != null && !changedField.isBlank() && !Objects.equals(was, mark);
                seen.put(k, mark);
                if (!isNew && !changed) continue;
                Map<String, Object> ev = new LinkedHashMap<>();
                if (item instanceof Map<?, ?> mi) mi.forEach((a, b) -> ev.put(String.valueOf(a), b)); else ev.put("item", item);
                ev.putIfAbsent("trigger_event", isNew ? "new" : "changed");
                ready.add(ev);
            }
            return ready.poll();
        });
    }

    static String itemKey(Object item, String key) {
        if (key != null && !key.isBlank() && item instanceof Map<?, ?> m) return String.valueOf(m.get(key.strip()));
        return JsonLite.canonical(com.oneofficeautomation.runner.Values.toJsonable(item));
    }

    static String changeMark(Object item, String field) {
        return field == null || field.isBlank() || !(item instanceof Map<?, ?> m) ? "" : String.valueOf(m.get(field.strip()));
    }

    @Condition(id = "process_running", name = "Program is running", description = "True when at least one process with this executable name is running.")
    public boolean processRunning(ActionContext ctx,
            @Input(name = "process_name", type = OoaType.STRING, description = "Executable name, with or without .exe.") String name) {
        if (name == null || name.isBlank()) throw new ActionException(P + ".NO_PROCESS", "Give the program's executable name", "Fill in the program's executable name, for example excel.exe.");
        return ProcessHandle.allProcesses().anyMatch(p -> nameMatches(processName(p), name));
    }

    // ---------- key presses and clicks (Windows, gap G37) ----------

    /** The keyboard and mouse hooks (Windows: packages-windows); tests put fakes here. */
    static Supplier<Optional<InputTriggers.Native>> inputHooks = () -> DesktopEvents.installed().flatMap(DesktopEvents::input);
    static LongSupplier clockMs = System::currentTimeMillis;
    /** Events waiting while the nested steps run; more are dropped (with the debounce, a person rarely gets near). */
    static final int QUEUE = 100;

    interface Hook { AutoCloseable start(Consumer<Map<String, Object>> sink) throws Exception; }

    /**
     * Hooks on start, queues the events while the nested steps run, and unhooks when the loop ends (the iterator is
     * AutoCloseable, which the Loop step closes) or when the limits are reached.
     */
    static Iterable<Object> inputEvents(ActionContext ctx, String event, int maxEvents, double maxSeconds, Hook hook) {
        BlockingQueue<Map<String, Object>> queue = new LinkedBlockingQueue<>(QUEUE);
        AutoCloseable handle;
        try { handle = hook.start(queue::offer); }
        catch (IllegalArgumentException e) { throw new ActionException(P + ".BAD_INPUT", e.getMessage(), "Check the trigger settings."); }
        catch (ActionException e) { throw e; }
        catch (Exception e) { throw new ActionException(P + ".HOOK_FAILED", "Cannot watch the keyboard or mouse: " + e.getMessage(), "Run the agent on a Windows runner with a signed-in desktop."); }
        Iterator<Object> it = events(ctx, 0.2, maxEvents, maxSeconds, () -> {
            Map<String, Object> e = queue.poll();
            if (e == null) return null;
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("event", event);
            ev.putAll(e);
            ev.put("time", LocalDateTime.now(java.time.ZoneOffset.UTC).withNano(0));  // UTC, like every DateTime
            return ev;
        }).iterator();
        final class Closing implements Iterator<Object>, AutoCloseable {
            boolean closed;
            public boolean hasNext() {
                boolean more;
                try { more = !closed && it.hasNext(); } catch (RuntimeException e) { close(); throw e; }
                if (!more) close();
                return more;
            }
            public Object next() { return it.next(); }
            public void close() { if (!closed) { closed = true; try { handle.close(); } catch (Exception ignored) { } } }
        }
        Closing c = new Closing();
        return () -> c;
    }

    static InputTriggers.Native hooks() {
        return inputHooks.get().orElseThrow(() -> new ActionException(P + ".WINDOWS_ONLY", InputTriggers.WINDOWS_ONLY + " (this computer runs " + System.getProperty("os.name") + ")",
                "Run this agent on a Windows runner with a signed-in desktop."));
    }

    static Map<String, Object> settings(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) if (kv[i + 1] != null) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    static final String D_WINDOW = "Only while this window is in front: title text, a pattern with * and ?, or re: and a regular expression. Empty: any window.";
    static final String D_PROCESS = "Only in this program, such as excel.exe. Empty: any program.";
    static final String D_DEBOUNCE = "Ignore repeats within this many milliseconds of the last event.";

    @LoopIterator(id = "key_pressed", name = "Each time a key is pressed",
            description = "Windows only. Run the nested steps each time the named key or combination (such as F9, CTRL+ENTER or SHIFT+F2) is pressed, "
                    + "optionally only while a given window is in front. Typed text is never recorded: the event gives only the key, the window title and the process. "
                    + "The key still reaches the application.")
    public Iterable<Object> keyPressed(ActionContext ctx,
            @Input(name = "key", type = OoaType.STRING, example = "CTRL+ENTER", description = "The key or combination: CTRL, ALT, SHIFT, WIN with one key such as A-Z, 0-9, F1-F24, ENTER, TAB, ESC, arrows. Letters and digits need CTRL, ALT or WIN.") String key,
            @Input(name = "window", type = OoaType.STRING, required = false, description = D_WINDOW) String window,
            @Input(name = "process", type = OoaType.STRING, required = false, description = D_PROCESS) String process,
            @Input(name = "debounce_ms", type = OoaType.NUMBER, required = false, defaultValue = "500", description = D_DEBOUNCE) double debounce,
            @Input(name = "max_events", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_MAX_EVENTS) int maxEvents,
            @Input(name = "max_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_MAX_SECONDS) double maxSeconds) {
        Map<String, Object> cfg = settings("key", key, "window", window, "process", process, "debounce_ms", debounce);
        try { InputTriggers.keyConfig(cfg); } catch (IllegalArgumentException e) { throw new ActionException(P + ".BAD_KEY", e.getMessage(), "Write the key like F9 or CTRL+ENTER."); }
        InputTriggers.Native n = hooks();
        return inputEvents(ctx, "key", maxEvents, maxSeconds, sink -> InputTriggers.watchKey(n, cfg, clockMs, sink));
    }

    @LoopIterator(id = "mouse_clicked", name = "Each click in a window",
            description = "Windows only. Run the nested steps each time the person clicks in a given window, optionally only on a given element (name, type, automation id as captured). "
                    + "The event gives the button, the window title, the process and the element's name, type and automation id; never screen contents. Clicks made by programs are ignored.")
    public Iterable<Object> mouseClicked(ActionContext ctx,
            @Input(name = "window", type = OoaType.STRING, required = false, description = "The window: title text, a pattern with * and ?, or re: and a regular expression. Give this or the process.") String window,
            @Input(name = "process", type = OoaType.STRING, required = false, description = "The program, such as excel.exe. Give this or the window.") String process,
            @Input(name = "button", type = OoaType.STRING, required = false, defaultValue = "\"left\"", description = "left, right, middle or any.") String button,
            @Input(name = "element_name", type = OoaType.STRING, required = false, description = "Only clicks on an element with this name (text, * and ? pattern, or re:). Empty: any element.") String elementName,
            @Input(name = "element_type", type = OoaType.STRING, required = false, example = "Button", description = "Only clicks on this type of element, such as Button or MenuItem. Empty: any type.") String elementType,
            @Input(name = "automation_id", type = OoaType.STRING, required = false, description = "Only clicks on the element with this automation id. Empty: any.") String automationId,
            @Input(name = "debounce_ms", type = OoaType.NUMBER, required = false, defaultValue = "500", description = D_DEBOUNCE) double debounce,
            @Input(name = "max_events", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_MAX_EVENTS) int maxEvents,
            @Input(name = "max_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_MAX_SECONDS) double maxSeconds) {
        Map<String, Object> cfg = settings("window", window, "process", process, "button", button, "element_name", elementName, "element_type", elementType,
                "automation_id", automationId, "debounce_ms", debounce);
        try { InputTriggers.clickConfig(cfg); } catch (IllegalArgumentException e) { throw new ActionException(P + ".BAD_CLICK", e.getMessage(), "Give the window title or the process."); }
        InputTriggers.Native n = hooks();
        return inputEvents(ctx, "click", maxEvents, maxSeconds, sink -> InputTriggers.watchClick(n, cfg, clockMs, sink));
    }
}
