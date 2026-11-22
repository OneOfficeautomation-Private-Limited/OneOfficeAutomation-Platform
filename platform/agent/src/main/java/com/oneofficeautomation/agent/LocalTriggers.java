package com.oneofficeautomation.agent;

import com.oneofficeautomation.packages.ui.DesktopEvents;
import com.oneofficeautomation.packages.ui.InputTriggers;
import com.oneofficeautomation.packages.ui.TitlePattern;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * One CommandCenter triggers that the OneRunner evaluates on its own machine, by polling: files and folders
 * (arrive, change, delete; folders created or deleted), windows opened or closed, processes started or stopped,
 * Windows services started or stopped, and global hotkeys. The first look at the machine only records what is
 * there; later looks fire for the differences. Key-press and click triggers (gap G37) are not polled: they use the
 * operating system's input hooks through {@link InputTriggers} (Windows only; typed text is never recorded).
 * Operating-system access goes through {@link OsProbe}, {@link Hotkeys} and {@link InputTriggers.Native}, so the
 * logic is tested with fakes.
 */
final class LocalTriggers {
    /** Where events and watch problems go (One CommandCenter in production). */
    interface Reporter {
        void fired(String triggerId, Map<String, Object> event);
        /** error null means the trigger is being watched normally again. */
        void status(String triggerId, String error);
    }

    /** What is running on this machine now. */
    interface OsProbe {
        List<String> windowTitles();
        List<String> processNames();
        /** RUNNING, STOPPED, PAUSED, ... or null when no such service exists. */
        String serviceState(String name);
    }

    interface Hotkeys { AutoCloseable register(String combination, Runnable onPress) throws Exception; }

    private final Reporter reporter;
    private final OsProbe os;
    private final Hotkeys hotkeys;
    private final InputTriggers.Native input;
    /** Milliseconds for the key-press and click debounce; tests set a fake clock. */
    java.util.function.LongSupplier clock = System::currentTimeMillis;
    private final Consumer<String> log;
    private final Map<String, Watch> watches = new LinkedHashMap<>();
    private final Map<String, String> reported = new HashMap<>();   // last status sent per trigger, to send changes only
    private String signature = "";
    private Thread thread;

    LocalTriggers(Reporter reporter, OsProbe os, Hotkeys hotkeys, Consumer<String> log) { this(reporter, os, hotkeys, null, log); }

    LocalTriggers(Reporter reporter, OsProbe os, Hotkeys hotkeys, InputTriggers.Native input, Consumer<String> log) {
        this.reporter = reporter; this.os = os; this.hotkeys = hotkeys; this.input = input; this.log = log;
    }

    /** Replaces the watched triggers with this list from the heartbeat; unchanged lists keep their state. */
    @SuppressWarnings("unchecked")
    synchronized void update(List<Object> triggers) {
        String sig = String.valueOf(triggers);
        if (sig.equals(signature)) return;
        signature = sig;
        Map<String, Watch> next = new LinkedHashMap<>();
        for (Object o : triggers) {
            Map<String, Object> t = (Map<String, Object>) o;
            String id = String.valueOf(t.get("id")), key = id + "|" + t;
            Watch w = watches.remove(key);
            if (w == null) {
                try { w = create(id, t); log.accept("Watching trigger " + id + ": " + w.describe()); }
                catch (RuntimeException e) { status(id, e.getMessage()); continue; }
                // the first look, right away; it fires only for "also start if it already exists" (G6)
                try { for (Map<String, Object> ev : w.poll()) fire(id, ev); } catch (RuntimeException e) { /* reported by the next regular look */ }
            }
            next.put(key, w);
        }
        for (Watch old : watches.values()) old.close();
        watches.clear();
        watches.putAll(next);
    }

    @SuppressWarnings("unchecked")
    private Watch create(String id, Map<String, Object> t) {
        String kind = String.valueOf(t.getOrDefault("kind", "file"));
        Map<String, Object> cfg = t.get("config") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        return switch (kind) {
            case "file", "file_changed", "file_deleted", "folder_created", "folder_deleted", "file_renamed", "folder_renamed" ->
                    new FolderWatch(id, kind, Path.of(String.valueOf(t.get("folder"))), t.get("pattern") == null ? "*" : String.valueOf(t.get("pattern")),
                            Boolean.TRUE.equals(cfg.get("recursive")), Boolean.TRUE.equals(cfg.get("fire_existing")));
            case "window_opened", "window_closed" -> new CountWatch(id, kind, "title",
                    Boolean.TRUE.equals(cfg.get("case_sensitive")) ? caseSensitive(String.valueOf(cfg.get("title"))) : TitlePattern.of(String.valueOf(cfg.get("title"))),
                    os::windowTitles, kind.endsWith("opened") ? 1 : -1);
            case "process_started", "process_stopped" -> new CountWatch(id, kind, "process", processMatcher(String.valueOf(cfg.get("process"))), os::processNames,
                    processEvents(kind, cfg.get("events")));
            case "service_started", "service_stopped", "service_paused" -> new ServiceWatch(id, kind, String.valueOf(cfg.get("service")));
            case "hotkey" -> new HotkeyWatch(id, String.valueOf(cfg.get("hotkey")));
            case "key_pressed", "mouse_clicked" -> new InputWatch(id, kind, cfg);
            default -> throw new IllegalArgumentException("This OneRunner does not know the trigger kind '" + kind + "'; update the runner");
        };
    }

    /** Process names match exactly (case-insensitive, .exe optional), or by wildcard or re: pattern. */
    static Predicate<String> processMatcher(String p) {
        if (p.startsWith("re:") || p.contains("*") || p.contains("?")) {
            Predicate<String> tp = TitlePattern.of(p);
            return n -> tp.test(n) || tp.test(stripExe(n));
        }
        String want = stripExe(p).toLowerCase(Locale.ROOT);
        return n -> stripExe(n).toLowerCase(Locale.ROOT).equals(want);
    }

    /** Which changes a process trigger fires for (G6): 1 started, -1 stopped, 0 both; the events setting wins over the kind. */
    static int processEvents(String kind, Object events) {
        String e = events == null ? "" : String.valueOf(events);
        return e.equals("both") ? 0 : e.equals("started") ? 1 : e.equals("stopped") ? -1 : kind.endsWith("started") ? 1 : -1;
    }

    /**
     * A window title pattern where case matters (G6): the same forms as {@link TitlePattern} (re:, wildcards, contains),
     * without ignoring case.
     */
    static Predicate<String> caseSensitive(String pattern) {
        String p = pattern == null ? "" : pattern.trim();
        if (p.startsWith("re:")) {
            Pattern re;
            try { re = Pattern.compile(p.substring(3)); }
            catch (java.util.regex.PatternSyntaxException e) { throw new IllegalArgumentException("Not a valid regular expression: " + p.substring(3)); }
            return t -> t != null && re.matcher(t).find();
        }
        if (p.contains("*") || p.contains("?")) {
            StringBuilder sb = new StringBuilder();
            for (char c : p.toCharArray()) sb.append(c == '*' ? ".*" : c == '?' ? "." : Pattern.quote(String.valueOf(c)));
            Pattern re = Pattern.compile(sb.toString(), Pattern.DOTALL);
            return t -> t != null && re.matcher(t).matches();
        }
        return t -> t != null && !p.isEmpty() && t.contains(p);
    }

    private static String stripExe(String n) { return n.toLowerCase(Locale.ROOT).endsWith(".exe") ? n.substring(0, n.length() - 4) : n; }

    /** One evaluation of every watch; the background thread calls this every poll interval. */
    synchronized void pollOnce() {
        for (Watch w : watches.values()) {
            List<Map<String, Object>> events;
            try { events = w.poll(); status(w.id, null); }
            catch (RuntimeException e) { status(w.id, e.getMessage() == null ? e.toString() : e.getMessage()); continue; }
            for (Map<String, Object> ev : events) fire(w.id, ev);
        }
    }

    private void fire(String id, Map<String, Object> ev) {
        try { reporter.fired(id, ev); log.accept("Trigger " + id + " fired: " + ev); }
        catch (RuntimeException e) { log.accept("Trigger " + id + " could not start its agent: " + e.getMessage()); }
    }

    private void status(String id, String error) {
        if (Objects.equals(reported.get(id), error) && reported.containsKey(id)) return;
        reported.put(id, error);
        if (error != null) log.accept("Trigger " + id + ": " + error);
        try { reporter.status(id, error); } catch (RuntimeException e) { reported.remove(id); }
    }

    void start(long pollMs) {
        thread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try { pollOnce(); } catch (RuntimeException e) { log.accept("Trigger watch error: " + e); }
                try { Thread.sleep(pollMs); } catch (InterruptedException e) { return; }
            }
        }, "ooa-agent-triggers");
        thread.setDaemon(true);
        thread.start();
    }

    synchronized void stop() {
        if (thread != null) thread.interrupt();
        for (Watch w : watches.values()) w.close();
        watches.clear();
        signature = "";
    }

    // ---------------- watches ----------------
    abstract static class Watch {
        final String id;
        Watch(String id) { this.id = id; }
        abstract List<Map<String, Object>> poll();
        abstract String describe();
        void close() { }
    }

    /** Polls a folder (optionally with sub-folders) and compares it with the previous look. */
    static final class FolderWatch extends Watch {
        /** key: the file system's own id for the entry (an inode), where it has one; it tells renames apart (G6). */
        record Entry(boolean dir, long size, long modified, Object key) {
            Entry(boolean dir, long size, long modified) { this(dir, size, modified, null); }
            boolean sameAs(Entry o) { return o != null && dir == o.dir && size == o.size && modified == o.modified && Objects.equals(key, o.key); }
        }
        final String kind; final Path folder; final PathMatcher matcher; final boolean recursive;
        /** G6: the first look also fires for what is already there (file arrives, folder created). */
        final boolean fireExisting;
        Map<Path, Entry> previous;
        final Map<Path, Entry> pending = new HashMap<>();   // new or changed files waiting until their size stops changing
        final Map<Path, Integer> pendingPolls = new HashMap<>();

        FolderWatch(String id, String kind, Path folder, String pattern, boolean recursive) { this(id, kind, folder, pattern, recursive, false); }

        FolderWatch(String id, String kind, Path folder, String pattern, boolean recursive, boolean fireExisting) {
            super(id);
            this.kind = kind; this.folder = folder; this.recursive = recursive;
            this.fireExisting = fireExisting && (kind.equals("file") || kind.equals("folder_created"));
            this.matcher = FileSystems.getDefault().getPathMatcher("glob:" + (pattern == null || pattern.isBlank() ? "*" : pattern));
        }

        String describe() { return kind + " in " + folder + (recursive ? " and sub-folders" : ""); }

        Map<Path, Entry> scan() {
            if (!Files.isDirectory(folder)) throw new IllegalStateException("Folder not found on this runner: " + folder);
            Map<Path, Entry> out = new HashMap<>();
            try (Stream<Path> s = recursive ? Files.walk(folder, 10) : Files.list(folder)) {
                s.limit(20_000).forEach(p -> {
                    if (p.equals(folder)) return;
                    try {
                        BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class);
                        out.put(p, new Entry(a.isDirectory(), a.isDirectory() ? 0 : a.size(), a.lastModifiedTime().toMillis(), a.fileKey()));
                    } catch (IOException ignored) { /* removed meanwhile */ }
                });
            } catch (IOException | java.io.UncheckedIOException e) { throw new IllegalStateException("Cannot read " + folder + ": " + e.getMessage()); }
            return out;
        }

        boolean matches(Path p) { return p.getFileName() != null && matcher.matches(p.getFileName()); }

        Map<String, Object> event(Path p, Entry e) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", p.toString());
            m.put("name", String.valueOf(p.getFileName()));
            m.put("folder", String.valueOf(p.getParent()));
            if (e != null && !e.dir()) m.put("size", e.size());
            return m;
        }

        List<Map<String, Object>> poll() {
            Map<Path, Entry> now = scan();
            List<Map<String, Object>> out = new ArrayList<>();
            if (previous == null) {
                if (!fireExisting) { previous = now; return out; }
                previous = Map.of();   // G6: everything already there counts as new
            }
            boolean folders = kind.startsWith("folder");
            switch (kind) {
                case "file", "file_changed" -> {
                    for (var e : now.entrySet()) {
                        Path p = e.getKey(); Entry cur = e.getValue(), old = previous.get(p);
                        if (cur.dir() || !matches(p)) continue;
                        boolean candidate = kind.equals("file") ? old == null || (old.dir()) : old != null && !old.dir() && (old.size() != cur.size() || old.modified() != cur.modified());
                        if (candidate && !pending.containsKey(p)) { pending.put(p, cur); pendingPolls.put(p, -1); }   // settles over at least one more look
                    }
                    for (Iterator<Map.Entry<Path, Entry>> it = pending.entrySet().iterator(); it.hasNext(); ) {
                        var e = it.next();
                        Entry cur = now.get(e.getKey());
                        if (cur == null) { it.remove(); pendingPolls.remove(e.getKey()); continue; }   // gone before it settled
                        int polls = pendingPolls.merge(e.getKey(), 1, Integer::sum);
                        boolean stable = cur.size() == e.getValue().size() && cur.modified() == e.getValue().modified();
                        if (stable && polls >= 1 && (cur.size() > 0 || polls >= 3)) {
                            out.add(event(e.getKey(), cur));
                            it.remove(); pendingPolls.remove(e.getKey());
                        } else e.setValue(cur);
                    }
                }
                case "file_deleted", "folder_deleted" -> {
                    for (var e : previous.entrySet())
                        if (e.getValue().dir() == folders && !now.containsKey(e.getKey()) && matches(e.getKey())) out.add(event(e.getKey(), null));
                }
                case "folder_created" -> {
                    for (var e : now.entrySet()) {
                        Entry old = previous.get(e.getKey());
                        if (e.getValue().dir() && (old == null || !old.dir()) && matches(e.getKey())) out.add(event(e.getKey(), e.getValue()));
                    }
                }
                case "file_renamed", "folder_renamed" -> out.addAll(renames(now, folders));
                default -> { }
            }
            previous = now;
            out.sort(Comparator.comparing(m -> String.valueOf(m.get("path"))));
            return out;
        }

        /**
         * Renames (G6): an entry that disappeared and one that appeared at the same look with the same size, change time
         * and file id. Only one-to-one pairs count, so two copies of the same file are never taken for a rename. The
         * pattern may match the old or the new name.
         */
        List<Map<String, Object>> renames(Map<Path, Entry> now, boolean folders) {
            List<Map.Entry<Path, Entry>> gone = new ArrayList<>(), added = new ArrayList<>();
            for (var e : previous.entrySet()) if (e.getValue().dir() == folders && !now.containsKey(e.getKey())) gone.add(e);
            for (var e : now.entrySet()) if (e.getValue().dir() == folders && !previous.containsKey(e.getKey())) added.add(e);
            List<Map<String, Object>> out = new ArrayList<>();
            for (var g : gone) {
                List<Map.Entry<Path, Entry>> to = added.stream().filter(a -> a.getValue().sameAs(g.getValue())).toList();
                if (to.size() != 1) continue;
                var a = to.get(0);
                if (gone.stream().filter(x -> x.getValue().sameAs(a.getValue())).count() != 1) continue;
                if (!matches(a.getKey()) && !matches(g.getKey())) continue;
                Map<String, Object> ev = event(a.getKey(), a.getValue());
                ev.put("old_path", g.getKey().toString());
                ev.put("old_name", String.valueOf(g.getKey().getFileName()));
                out.add(ev);
            }
            return out;
        }
    }

    /** Counts matching windows or processes; fires when the count of a name goes up (opened, started) or down (closed, stopped). */
    static final class CountWatch extends Watch {
        final String kind, field; final Predicate<String> match; final java.util.function.Supplier<List<String>> source;
        /** 1: fires when one appears; -1: when one goes; 0: both, each event saying which (G6). */
        final int mode;
        Map<String, Integer> previous;

        CountWatch(String id, String kind, String field, Predicate<String> match, java.util.function.Supplier<List<String>> source, boolean appear) {
            this(id, kind, field, match, source, appear ? 1 : -1);
        }

        CountWatch(String id, String kind, String field, Predicate<String> match, java.util.function.Supplier<List<String>> source, int mode) {
            super(id); this.kind = kind; this.field = field; this.match = match; this.source = source; this.mode = mode;
        }

        String describe() { return mode == 0 ? kind.substring(0, kind.indexOf('_')) + " started or stopped" : kind.replace('_', ' '); }

        List<Map<String, Object>> poll() {
            Map<String, Integer> now = new TreeMap<>();
            for (String s : source.get()) if (s != null && match.test(s)) now.merge(s, 1, Integer::sum);
            List<Map<String, Object>> out = new ArrayList<>();
            if (previous != null) {
                Set<String> names = new TreeSet<>(now.keySet());
                names.addAll(previous.keySet());
                for (String n : names) {
                    int diff = now.getOrDefault(n, 0) - previous.getOrDefault(n, 0);
                    int times = mode == 0 ? Math.abs(diff) : mode > 0 ? diff : -diff;
                    for (int i = 0; i < times; i++) {
                        Map<String, Object> ev = new LinkedHashMap<>(Map.of(field, n));
                        if (mode == 0) ev.put("event", diff > 0 ? "started" : "stopped");
                        out.add(ev);
                    }
                }
            }
            previous = now;
            return out;
        }
    }

    /** Polls one Windows service's state and fires on the change to RUNNING (started), STOPPED (stopped) or PAUSED (paused). */
    final class ServiceWatch extends Watch {
        final String kind, service; String previous;
        ServiceWatch(String id, String kind, String service) { super(id); this.kind = kind; this.service = service; }
        String describe() { return kind.replace('_', ' ') + " " + service; }
        List<Map<String, Object>> poll() {
            String state = os.serviceState(service);
            if (state == null) throw new IllegalStateException("Service '" + service + "' does not exist on this runner");
            List<Map<String, Object>> out = new ArrayList<>();
            String wanted = kind.equals("service_started") ? "RUNNING" : kind.equals("service_paused") ? "PAUSED" : "STOPPED";   // paused: G6
            if (previous != null && !previous.equals(state) && state.equals(wanted))
                out.add(new LinkedHashMap<>(Map.of("service", service, "state", state, "previous_state", previous)));
            previous = state;
            return out;
        }
    }

    /** A global hotkey: registered once, it fires from the hotkey thread. */
    final class HotkeyWatch extends Watch {
        final String combo; AutoCloseable handle; String error;
        HotkeyWatch(String id, String combo) {
            super(id); this.combo = combo;
            if (hotkeys == null) throw new IllegalStateException("Hotkey triggers need a Windows runner with the Window toolkit (packages-windows) installed");
            try { handle = hotkeys.register(combo, () -> fire(id, new LinkedHashMap<>(Map.of("hotkey", combo)))); }
            catch (Exception e) { throw new IllegalStateException("The hotkey " + combo + " cannot be used: " + e.getMessage()); }
        }
        String describe() { return "hotkey " + combo; }
        List<Map<String, Object>> poll() { return List.of(); }
        void close() { try { if (handle != null) handle.close(); } catch (Exception ignored) { } }
    }

    /**
     * A key-press or click trigger: hooked once, it fires from the hook's worker thread. The event carries only the
     * configured key (or the button and element) and the window, never typed text.
     */
    final class InputWatch extends Watch {
        final String what; final AutoCloseable handle;
        InputWatch(String id, String kind, Map<String, Object> cfg) {
            super(id);
            if (input == null) throw new IllegalStateException(WINDOWS ? InputTriggers.WINDOWS_ONLY + " with the Window toolkit (packages-windows) installed"
                    : InputTriggers.WINDOWS_ONLY + "; this runner runs " + System.getProperty("os.name") + ", so the trigger is not watched here");
            try { what = InputTriggers.describe(kind, cfg); }
            catch (IllegalArgumentException e) { throw new IllegalStateException(e.getMessage()); }
            try {
                handle = kind.equals("key_pressed") ? InputTriggers.watchKey(input, cfg, clock, ev -> fire(id, ev))
                        : InputTriggers.watchClick(input, cfg, clock, ev -> fire(id, ev));
            } catch (RuntimeException e) { throw new IllegalStateException("The " + what + " trigger cannot be watched: " + e.getMessage()); }
            catch (Exception e) { throw new IllegalStateException("The " + what + " trigger cannot be watched: " + e); }
        }
        String describe() { return what; }
        List<Map<String, Object>> poll() { return List.of(); }
        void close() { try { handle.close(); } catch (Exception ignored) { } }
    }

    // ---------------- the real machine ----------------
    static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    /** Reads windows (Win32 through the Window toolkit, else tasklist), processes (Java) and services (sc.exe). */
    static final class SystemProbe implements OsProbe {
        public List<String> windowTitles() {
            Optional<DesktopEvents> d = DesktopEvents.installed();
            if (d.isPresent()) return d.get().windowTitles();
            if (!WINDOWS) throw new IllegalStateException("Window triggers work on Windows runners only");
            List<String> out = new ArrayList<>();
            for (String line : exec(List.of("tasklist.exe", "/v", "/fo", "csv", "/nh")).split("\r?\n")) {
                List<String> cols = csv(line);
                if (cols.size() >= 9 && !cols.get(8).isBlank() && !cols.get(8).equals("N/A")) out.add(cols.get(8));
            }
            return out;
        }

        public List<String> processNames() {
            List<String> out = new ArrayList<>();
            ProcessHandle.allProcesses().forEach(p -> p.info().command().ifPresent(c -> out.add(Path.of(c).getFileName().toString())));
            return out;
        }

        private static final Pattern STATE = Pattern.compile("STATE\\s*:\\s*\\d+\\s+(\\w+)");

        public String serviceState(String name) {
            if (!WINDOWS) throw new IllegalStateException("Service triggers work on Windows runners only");
            String out = exec(List.of("sc.exe", "query", name));
            if (out.contains("1060")) return null;
            Matcher m = STATE.matcher(out);
            return m.find() ? m.group(1).toUpperCase(Locale.ROOT) : "UNKNOWN";
        }

        static String exec(List<String> cmd) {
            try {
                Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
                byte[] b = p.getInputStream().readAllBytes();
                if (!p.waitFor(20, TimeUnit.SECONDS)) p.destroyForcibly();
                return new String(b, StandardCharsets.UTF_8);
            } catch (IOException e) { throw new IllegalStateException("Cannot run " + cmd.get(0) + ": " + e.getMessage()); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return ""; }
        }

        static List<String> csv(String line) {
            List<String> out = new ArrayList<>();
            StringBuilder cur = new StringBuilder();
            boolean q = false;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '"') { if (q && i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; } else q = !q; }
                else if (c == ',' && !q) { out.add(cur.toString()); cur.setLength(0); }
                else cur.append(c);
            }
            out.add(cur.toString());
            return out;
        }
    }

    /** Keyboard and mouse hooks through the Window toolkit, or null when it is not installed (not Windows). */
    static InputTriggers.Native systemInput() {
        return DesktopEvents.installed().flatMap(DesktopEvents::input).orElse(null);
    }

    /** Global hotkeys through the Window toolkit, or null when it is not installed (not Windows). */
    static Hotkeys systemHotkeys() {
        return DesktopEvents.installed().<Hotkeys>map(d -> d::onHotkey).orElse(null);
    }
}
