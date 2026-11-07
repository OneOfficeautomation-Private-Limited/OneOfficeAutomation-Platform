package com.oneofficeautomation.packages;

import com.oneofficeautomation.packages.ui.DesktopEvents;
import com.oneofficeautomation.packages.ui.InputTriggers;
import com.oneofficeautomation.sdk.*;

import java.io.IOException;
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
        description = "Wait for events (new files, timer ticks, process starts, key presses, clicks) and run the loop's nested steps once per event.")
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
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw new OoaException("RUN.CANCELLED", "Run thread interrupted while waiting for an event"); }
        }
    }

    // ---------- file arrives ----------

    record Seen(long size, long modified) {}

    @LoopIterator(id = "file_arrives", name = "Each new file in folder",
            description = "Wait for files to appear in a folder and run the nested steps once for each, oldest first; gives path, name, size and time (UTC, like every DateTime).")
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
            throw new ActionException(P + ".IO_ERROR", "Cannot read folder " + dir + ": " + e.getMessage(), null, true);
        }
        return out;
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
            ev.put("time", LocalDateTime.now(java.time.ZoneOffset.UTC).withNano(0));  // UTC, like every DateTime
            return ev;
        });
    }

    @Condition(id = "process_running", name = "Program is running", description = "True when at least one process with this executable name is running.")
    public boolean processRunning(ActionContext ctx,
            @Input(name = "process_name", type = OoaType.STRING, description = "Executable name, with or without .exe.") String name) {
        if (name == null || name.isBlank()) throw new ActionException(P + ".NO_PROCESS", "Give the program's executable name");
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
