package com.oneofficeautomation.sdk;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/** What a package action can see. The Runner creates one per step. */
public final class ActionContext {
    private final String runId, stepId, tenantId;
    private final Path workDir;
    private final Map<String, Object> sessions, services;
    private final AtomicBoolean cancelled;
    private final Supplier<List<String>> secrets;
    private final BiConsumer<String, String> logSink;

    public ActionContext(String runId, String stepId, String tenantId, Path workDir, Map<String, Object> sessions,
                         Map<String, Object> services, AtomicBoolean cancelled, Supplier<List<String>> secrets,
                         BiConsumer<String, String> logSink) {
        this.runId = runId; this.stepId = stepId; this.tenantId = tenantId; this.workDir = workDir;
        this.sessions = sessions; this.services = services; this.cancelled = cancelled;
        this.secrets = secrets; this.logSink = logSink;
    }

    public String runId() { return runId; }
    public String stepId() { return stepId; }
    public String tenantId() { return tenantId; }
    public Path workDir() { return workDir; }
    public Map<String, Object> sessions() { return sessions; }

    /** Structured log line; any secret value is replaced by **** before it leaves the action. */
    public void log(String level, String message) {
        if (logSink != null) logSink.accept(level, redact(message));
    }

    /** Replaces every secret value known to this run with ****; use before writing text anywhere outside the run. */
    public String redact(String text) {
        String out = text == null ? "" : text;
        for (String s : secrets.get()) if (s != null && !s.isEmpty()) out = out.replace(s, SecureString.MASK);
        return out;
    }

    public void info(String message) { log("info", message); }
    public void warn(String message) { log("warning", message); }

    public <T> T session(String name, Class<T> kind) {
        Object o = sessions.get(name);
        if (o == null) throw new OoaException("SESSION.NOT_FOUND", "Session '" + name + "' is not open",
                "Add an 'open' step that creates session '" + name + "' before this step.");
        if (!kind.isInstance(o)) throw new OoaException("SESSION.WRONG_TYPE",
                "Session '" + name + "' is a " + o.getClass().getSimpleName() + ", expected " + kind.getSimpleName(), "Use the session from the matching Open step (a browser session for browser steps, and so on).");
        return kind.cast(o);
    }

    /**
     * The named session, or, when the step keeps the toolkit's default name and nothing is open under it, the one open
     * session of that kind (a workbook opened as "report" is used by later steps that left the name as "excel"). With
     * several open, the step must name one.
     */
    public <T> T sessionOrOnly(String name, String defaultName, Class<T> kind) { return session(sessionNameOrOnly(name, defaultName, kind), kind); }

    /** The session name such a step means (see {@link #sessionOrOnly}); the given name when nothing else fits. */
    public String sessionNameOrOnly(String name, String defaultName, Class<?> kind) {
        boolean byDefault = name == null || name.isBlank() || name.equals(defaultName);
        if (byDefault && sessions.get(defaultName) == null) {
            List<String> open = new ArrayList<>();
            for (Map.Entry<String, Object> e : sessions.entrySet()) if (kind.isInstance(e.getValue())) open.add(e.getKey());
            if (open.size() == 1) return open.get(0);
            if (open.size() > 1) { Collections.sort(open);
                throw new OoaException("SESSION.NOT_FOUND", "Several are open (" + String.join(", ", open) + "); this step does not say which",
                    "Choose the session on this step."); }
        }
        return byDefault ? defaultName : name;
    }

    public <T> T service(String name, Class<T> kind) {
        Object o = services.get(name);
        if (o == null) throw new OoaException("SERVICE.UNAVAILABLE", "Platform service '" + name + "' is not available to this run",
                "Check the run was started with the required platform services.");
        return kind.cast(o);
    }

    private Map<String, List<Path>> selectedFiles = Map.of();

    /** Set by the runner: the files each File input chose by name rules ({@link FileSelection}). */
    public ActionContext withSelectedFiles(Map<String, List<Path>> files) {
        this.selectedFiles = files == null ? Map.of() : Map.copyOf(files);
        return this;
    }

    /**
     * Every file a File input chose by name rules, sorted by path; null when the input was an exact path or a variable.
     * Commands that work on many files (copy, move, delete, zip, print, upload) use this; the input's own value is then
     * the first match.
     */
    public List<Path> selectedFiles(String input) { return selectedFiles.get(input); }

    /**
     * Where a step saves a file: the path given; inside it when it is a folder (or ends with / or \), under
     * {@code fileName}; or, when no path is given, in this run's temporary folder, so a later step can use the path the
     * step returns (as AA's file variables, without choosing a folder). The parent folder is created.
     */
    public Path outputPath(String given, String fileName) {
        String safe = fileName == null || fileName.isBlank() ? "file" : fileName.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").strip();
        Path target;
        if (given == null || given.isBlank()) target = workDir.resolve(".ooa-temp").resolve(runId.replaceAll("[^A-Za-z0-9_.-]", "_")).resolve(safe);
        else {
            Path p = resolvePath(given.strip());
            target = given.endsWith("/") || given.endsWith("\\") || java.nio.file.Files.isDirectory(p) ? p.resolve(safe) : p;
        }
        try { if (target.getParent() != null) java.nio.file.Files.createDirectories(target.getParent()); }
        catch (java.io.IOException e) { throw new ActionException("FILE.IO", "Cannot create the folder for " + target + ": " + e.getMessage(), "Check the runner may write there, or leave the path empty to use a temporary file."); }
        return target;
    }

    /**
     * The item of the innermost loop around this step (an email of "Each email", a row, a file), or null outside a
     * loop. Commands that act on "the current item" (reply to, move, save) use it when their id field is left empty.
     */
    public Object loopItem() {
        Object s = services == null ? null : services.get(LOOP_ITEM_SERVICE);
        return s instanceof Supplier<?> sup ? sup.get() : null;
    }

    /** Key in the sessions map holding the names of sessions that agents this one runs may use (never a session name). */
    public static final String SHARED_SESSIONS = "\u0000shared";

    /**
     * Lets agents that this agent runs (Run agent) use the named session, as AA's global sessions: they find it under
     * the same name and leave it open when they end. Without it a child only gets sessions with "same session" on.
     */
    @SuppressWarnings("unchecked")
    public void shareSession(String name) {
        ((java.util.Set<String>) sessions.computeIfAbsent(SHARED_SESSIONS, k -> java.util.concurrent.ConcurrentHashMap.newKeySet())).add(name);
    }

    /** The service key under which the runner offers the current loop item. */
    public static final String LOOP_ITEM_SERVICE = "loop.item";

    /** The service key under which the runner offers the running agent's name. */
    public static final String AGENT_NAME_SERVICE = "agent.name";

    /** The running agent's name, or empty outside a run (dialogs use it as their window title). */
    public String agentName() {
        Object s = services == null ? null : services.get(AGENT_NAME_SERVICE);
        return s == null || "null".equals(s) ? "" : String.valueOf(s).strip();
    }

    /**
     * G4 (AA parity): checks again every 300 ms until the check is true or the seconds have passed (0 checks once);
     * a stopped run ends the wait. Conditions use it for their "keep trying" option.
     */
    public boolean keepTrying(double seconds, java.util.function.BooleanSupplier check) {
        long end = System.currentTimeMillis() + (long) (Math.max(0, Math.min(3600, seconds)) * 1000);
        while (true) {
            if (check.getAsBoolean()) return true;
            long left = end - System.currentTimeMillis();
            if (left <= 0) return false;
            checkCancelled();
            try { Thread.sleep(Math.min(300, left)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
    }

    public void checkCancelled() {
        if (cancelled.get()) throw new OoaException("RUN.CANCELLED", "Run was stopped from One CommandCenter", "The run was stopped before this step finished. Run the agent again when it should go on.");
    }

    /** A path relative to the working folder; where the run is confined to it (service confine_paths), no other path. */
    public Path resolvePath(String p) {
        Path path = Path.of(p);
        Path r = path.isAbsolute() ? path : workDir.resolve(path);
        if (services != null && Boolean.TRUE.equals(services.get("confine_paths"))
                && !r.toAbsolutePath().normalize().startsWith(workDir.toAbsolutePath().normalize()))
            throw new ActionException("FILE.OUTSIDE_WORKSPACE", "This run may only use files in its own workspace, not " + p, "Use a path relative to the workspace.");
        return r;
    }
}
