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
                "Session '" + name + "' is a " + o.getClass().getSimpleName() + ", expected " + kind.getSimpleName());
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

    public void checkCancelled() {
        if (cancelled.get()) throw new OoaException("RUN.CANCELLED", "Run was stopped from Control Center");
    }

    public Path resolvePath(String p) {
        Path path = Path.of(p);
        return path.isAbsolute() ? path : workDir.resolve(path);
    }
}
