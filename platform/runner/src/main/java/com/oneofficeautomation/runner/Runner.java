package com.oneofficeautomation.runner;

import com.oneofficeautomation.runner.expr.Expressions;
import com.oneofficeautomation.sdk.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Parameter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ONEOFFICEAUTOMATION Runner: executes one OOA Flow run. Load -> Plan (validate) -> Execute (walk the step
 * tree) -> Stream events -> Finish. In production the Agent starts one Runner JVM per run inside a Job
 * Object / cgroup; leaf actions run on a dedicated worker thread (UI libraries need thread affinity),
 * parallel branches use virtual threads, and a stuck action raises STEP.TIMEOUT.
 */
public final class Runner {
    public static final int MAX_CHILD_DEPTH = 10;

    private final PackageRegistry registry;
    private final Map<String, Object> services;
    private final EventSink sink;
    private final Path workDir;
    private final FlowValidator validator;
    private final String tenantId;
    private final String principal;

    private Runner(Builder b) {
        this.registry = b.registry;
        this.services = Map.copyOf(b.services);
        this.sink = b.sink;
        this.workDir = b.workDir;
        this.validator = new FlowValidator(b.registry, b.schema);
        this.tenantId = b.tenantId;
        this.principal = b.principal;
    }

    public static Builder builder(PackageRegistry registry) { return new Builder(registry); }

    public static final class Builder {
        private final PackageRegistry registry;
        private final Map<String, Object> services = new HashMap<>();
        private EventSink sink = new EventSink.ListSink();
        private Path workDir = Path.of("").toAbsolutePath();
        private FlowSchemaValidator schema = FlowSchemaValidator.NONE;
        private String tenantId = "default";
        private String principal = "system";

        Builder(PackageRegistry registry) { this.registry = registry; }
        public Builder service(String name, Object s) { services.put(name, s); return this; }
        public Builder sink(EventSink s) { sink = s; return this; }
        public Builder workDir(Path p) { workDir = p.toAbsolutePath(); return this; }
        public Builder schemaValidator(FlowSchemaValidator v) { schema = v; return this; }
        public Builder tenant(String t) { tenantId = t; return this; }
        public Builder principal(String p) { principal = p; return this; }
        public Runner build() { return new Runner(this); }
    }

    public RunResult run(Map<String, Object> flow, Map<String, Object> inputs) { return run(flow, inputs, new RunOptions()); }

    public RunResult run(Map<String, Object> flow, Map<String, Object> inputs, RunOptions opts) {
        String runId = opts.runId != null ? opts.runId : "run_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Run r = new Run(flow, runId, opts.cancel != null ? opts.cancel : new AtomicBoolean(), opts.maxRunSeconds, 0);
        r.pause = opts.pause != null ? opts.pause : new AtomicBoolean();
        r.debugger = opts.debugger;
        r.startAt = opts.startAt;
        r.onlyStep = opts.onlyStep;
        r.carryOver = opts.carryOver;
        r.trail = opts.trail;
        return r.execute(inputs == null ? Map.of() : inputs, true);
    }

    /** Debug hook, called before every step (also in child agents). It may block to stop at breakpoints or step, and may read or change variables. */
    public interface Debugger { void beforeStep(Frame frame); }

    /** What a debugger sees while a run is stopped before a step. */
    public interface Frame {
        String stepId();
        String action();
        /** Agent nesting: 0 for the agent being run, 1 for its child agents, and so on. */
        int depth();
        /** Step nesting across agents, for step over and step out: deeper steps have a higher level. */
        int level();
        String automation();
        /** Outermost first: {automation, stepId} for each agent that is running. */
        List<Map<String, Object>> stack();
        /** Declared variables of the current agent; credentials are masked. */
        Map<String, Object> variables();
        /** Change a variable while stopped (the value is converted to the variable's type). */
        void setVariable(String name, Object value);
    }

    /**
     * Run options. {@link #startAt(String)} and {@link #onlyStep(String)} are for testing from Studio ("Run from here" and
     * "Run this step only"), and apply to the agent being run, not to its child agents:
     * <ul>
     *   <li>Steps before the start step, in document order, are skipped: they are not run and emit no events.</li>
     *   <li>Blocks that contain the start step (If, Loop, Try, Step group, Parallel) are entered without evaluating them:
     *       an If's condition is not checked, a Loop runs one pass without setting its item or index variable, and a Parallel
     *       runs only the branch that holds the start step. Try keeps its Catch and Finally when the start step is in the Try
     *       part. After the enclosing blocks end, the run carries on normally with the steps that follow them.</li>
     *   <li>With onlyStep, the run ends successfully once the start step (and, for a block, everything inside it) has run.</li>
     * </ul>
     * The skipped steps never set their variables, so these keep their defaults and inputs, unless {@link #carryOver(Map)}
     * gives values (usually the ones an earlier run had when it reached the start step, from its {@link ValueTrail}).
     */
    public static final class RunOptions {
        String runId; AtomicBoolean cancel; Double maxRunSeconds; AtomicBoolean pause; Debugger debugger; String startAt; boolean onlyStep;
        Map<String, Object> carryOver; ValueTrail trail;
        public RunOptions startAt(String stepId) { startAt = stepId == null || stepId.isBlank() ? null : stepId; return this; }
        /**
         * With startAt: values for the agent's variables before the start step, in place of the ones the skipped steps
         * would have set. Inputs given to the run win; credentials, undeclared names and values of the wrong type are left out.
         */
        public RunOptions carryOver(Map<String, Object> values) { carryOver = values; return this; }
        /** Notes the agent's variable values as the run goes, for a later Run from here (see ValueTrail). */
        public RunOptions trail(ValueTrail t) { trail = t; return this; }
        public RunOptions onlyStep(String stepId) { startAt(stepId); onlyStep = startAt != null; return this; }
        public RunOptions debugger(Debugger d) { debugger = d; return this; }
        public RunOptions pause(AtomicBoolean p) { pause = p; return this; }
        public RunOptions runId(String id) { runId = id; return this; }
        public RunOptions cancel(AtomicBoolean c) { cancel = c; return this; }
        public RunOptions maxRunSeconds(double s) { maxRunSeconds = s; return this; }
    }

    /**
     * The variable values of a run (the agent being run, not its child agents): as they were when each step started for the
     * first time, and at the end. Credentials and secret values are never kept. Values are copies, as plain JSON values.
     */
    public static final class ValueTrail {
        final Map<String, Map<String, Object>> atStep = new ConcurrentHashMap<>();
        volatile Map<String, Object> atEnd;
        volatile boolean endedEarly;
        /**
         * The values when the run first reached `stepId`. When it never did because it failed or was stopped before, the
         * values it ended with; otherwise (a step it skipped or never got to on success) null.
         */
        public Map<String, Object> before(String stepId) {
            Map<String, Object> m = stepId == null ? null : atStep.get(stepId);
            return m != null ? m : endedEarly ? atEnd : null;
        }
        public boolean reached(String stepId) { return stepId != null && atStep.containsKey(stepId); }
        public Map<String, Object> atEnd() { return atEnd; }
        /** After a Run from here: the steps this run skipped keep the values an earlier run noted for them. */
        public ValueTrail keepFrom(ValueTrail older) {
            if (older != null && older != this) older.atStep.forEach(atStep::putIfAbsent);
            return this;
        }
    }

    // internal control-flow signals
    private static final class BreakSignal extends RuntimeException {
        final String label;
        BreakSignal(String label) { super(null, null, false, false); this.label = label; }
    }
    private static final class ContinueSignal extends RuntimeException {
        final String label;
        ContinueSignal(String label) { super(null, null, false, false); this.label = label; }
    }
    /** core/stop: ends the current agent; Finally blocks still run because it passes through them as an exception. */
    private static final class StopSignal extends RuntimeException {
        final boolean failed; final String reason;
        StopSignal(boolean failed, String reason) { super(null, null, false, false); this.failed = failed; this.reason = reason; }
    }

    private static String labelOf(RuntimeException e) {
        return e instanceof BreakSignal b ? b.label : e instanceof ContinueSignal c ? c.label : null;
    }

    /** Takes a screenshot for a Catch block (screenshot option). Register one as service "screen_capture"; otherwise the desktop is captured when there is one. */
    @FunctionalInterface
    public interface ScreenCapture { void capture(Path target) throws Exception; }

    /** Bot-level variable scope. Child bots get a new scope (inputs/outputs only). */
    /** Names reserved for built-in, read-only variables: $sys.* (system values) and $global.* (global values). */
    public static final Set<String> RESERVED = Set.of("sys", "global");

    static final class Scope {
        final Map<String, String> types = new LinkedHashMap<>();
        final Map<String, Object> values = new HashMap<>();
        final Map<String, java.util.function.Supplier<Object>> builtins = new HashMap<>();

        synchronized Object get(String name) {
            java.util.function.Supplier<Object> b = builtins.get(name);
            if (b != null) return b.get();
            if (!types.containsKey(name)) throw new OoaException("VAR.UNDECLARED", "Variable '$" + name + "' is not declared",
                    "Declare '" + name + "' in the agent's variables.");
            return values.get(name);
        }

        synchronized Object set(String name, Object value) {
            if (builtins.containsKey(name)) throw new OoaException("VAR.READ_ONLY", "'" + name + "' is a built-in variable and cannot be changed",
                    "Assign to one of your own variables instead.");
            if (!types.containsKey(name)) throw new OoaException("VAR.UNDECLARED", "Variable '" + name + "' is not declared");
            Object v = Values.coerce(value, types.get(name), name);
            values.put(name, v);
            return v;
        }
    }

    private final class Run {
        final Map<String, Object> flow;
        final String runId;
        final AtomicBoolean cancel;
        final long deadlineNanos;
        final int depth;
        final Scope scope = new Scope();
        final Map<String, Object> requires;
        Map<String, Object> sessions = new ConcurrentHashMap<>();
        boolean borrowedSessions;
        final List<String> secrets = new CopyOnWriteArrayList<>();
        final List<SecureString> secureValues = new CopyOnWriteArrayList<>();
        final AtomicLong seq = new AtomicLong();
        final AtomicInteger stepsExecuted = new AtomicInteger();
        final ThreadLocal<ExecutorService> worker = new ThreadLocal<>();
        AtomicBoolean pause = new AtomicBoolean();
        Debugger debugger;
        /** Run from here / run this step only (see RunOptions): the start step, the ids of the blocks around it, and progress. */
        String startAt;
        boolean onlyStep;
        Set<String> startPath = Set.of();
        volatile boolean reached, onlyDone;
        Map<String, Object> carryOver;
        ValueTrail trail;
        Run parent;
        String parentStep;
        volatile int nesting;
        final List<ExecutorService> workers = new CopyOnWriteArrayList<>();

        @SuppressWarnings("unchecked")
        Run(Map<String, Object> flow, String runId, AtomicBoolean cancel, Double maxRunSeconds, int depth) {
            this.flow = flow; this.runId = runId; this.cancel = cancel; this.depth = depth;
            this.deadlineNanos = maxRunSeconds == null ? Long.MAX_VALUE : System.nanoTime() + (long) (maxRunSeconds * 1e9);
            for (Object o : (List<Object>) flow.getOrDefault("variables", List.of())) {
                Map<String, Object> v = (Map<String, Object>) o;
                scope.types.put((String) v.get("name"), (String) v.get("type"));
            }
            Map<String, Object> req = (Map<String, Object>) flow.getOrDefault("requires", Map.of());
            this.requires = (Map<String, Object>) req.getOrDefault("packages", Map.of());
            scope.builtins.put("sys", this::systemValues);
            scope.builtins.put("global", this::globalValues);
            if (services.get("repository") instanceof AutomationRepository) sessions.put(ChildAgents.SESSION, (ChildAgents) this::childAgent);
        }

        /** $sys.*: evaluated at the moment of use, so date and time are always current. */
        Map<String, Object> systemValues() {
            java.time.LocalDateTime local = java.time.LocalDateTime.now().withNano(0);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", local.toLocalDate().toString());
            m.put("time", local.toLocalTime().toString());
            m.put("now", java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).withNano(0));
            m.put("today", java.time.LocalDate.now(java.time.ZoneOffset.UTC).atStartOfDay());
            m.put("year", java.math.BigDecimal.valueOf(local.getYear()));
            m.put("month", java.math.BigDecimal.valueOf(local.getMonthValue()));
            m.put("day", java.math.BigDecimal.valueOf(local.getDayOfMonth()));
            m.put("weekday", local.getDayOfWeek().getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH));
            m.put("hour", java.math.BigDecimal.valueOf(local.getHour()));
            m.put("minute", java.math.BigDecimal.valueOf(local.getMinute()));
            m.put("second", java.math.BigDecimal.valueOf(local.getSecond()));
            m.put("millisecond", java.math.BigDecimal.valueOf(System.currentTimeMillis() % 1000));
            m.put("day_of_year", java.math.BigDecimal.valueOf(local.getDayOfYear()));
            m.put("timestamp", java.math.BigDecimal.valueOf(System.currentTimeMillis()));
            m.put("os_version", System.getProperty("os.version", ""));
            m.put("processors", java.math.BigDecimal.valueOf(Runtime.getRuntime().availableProcessors()));
            m.put("user_domain", Objects.requireNonNullElse(System.getenv("USERDOMAIN"), ""));
            m.put("home_folder", System.getProperty("user.home", ""));
            m.put("file_separator", java.io.File.separator);
            if (java.lang.management.ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
                long total = os.getTotalMemorySize(), free = os.getFreeMemorySize();
                double cpu = os.getCpuLoad();
                m.put("cpu_usage", cpu < 0 ? null : java.math.BigDecimal.valueOf(Math.round(cpu * 1000) / 10.0));
                m.put("total_ram_mb", java.math.BigDecimal.valueOf(total / (1024 * 1024)));
                m.put("ram_usage_mb", java.math.BigDecimal.valueOf((total - free) / (1024 * 1024)));
            }
            m.put("user", System.getProperty("user.name", ""));
            String host;
            try { host = java.net.InetAddress.getLocalHost().getHostName(); } catch (Exception e) { host = "unknown"; }
            m.put("machine", host);
            m.put("os", System.getProperty("os.name", ""));
            m.put("run_id", runId);
            m.put("bot_id", String.valueOf(flow.getOrDefault("id", "")));
            m.put("bot_name", String.valueOf(flow.getOrDefault("name", "")));
            m.put("principal", principal);
            m.put("workspace", workDir.toString());
            m.put("temp_folder", System.getProperty("java.io.tmpdir", ""));
            m.put("newline", "\n");
            m.put("tab", "\t");
            m.put("empty", "");
            return m;
        }

        /** $global.*: shared values from the global values service (Control Room) or global-values.json in the workspace. */
        @SuppressWarnings("unchecked")
        Map<String, Object> globalValues() {
            Object svc = services.get("global_values");
            if (svc instanceof java.util.function.Supplier<?> sup) { Object v = sup.get(); return v instanceof Map ? (Map<String, Object>) v : Map.of(); }
            java.nio.file.Path p = workDir.resolve("global-values.json");
            if (!java.nio.file.Files.isRegularFile(p)) return Map.of();
            try { Object v = JsonLite.parse(java.nio.file.Files.readString(p)); return v instanceof Map ? (Map<String, Object>) v : Map.of(); }
            catch (Exception e) { throw new OoaException("GLOBALS.INVALID", "global-values.json is not valid JSON: " + e.getMessage(), "Fix the file in the workspace."); }
        }

        /**
         * Saved connections by name: from the connections service (Command Center) or connections.json in the
         * workspace. Each is {"package": "ooa.servicenow", "values": {input: value}, "credentials": {input: vault ref}}.
         */
        @SuppressWarnings("unchecked")
        Map<String, Object> connections() {
            Object svc = services.get("connections");
            if (svc instanceof java.util.function.Supplier<?> sup) { Object v = sup.get(); return v instanceof Map ? (Map<String, Object>) v : Map.of(); }
            java.nio.file.Path p = workDir.resolve("connections.json");
            if (!java.nio.file.Files.isRegularFile(p)) return Map.of();
            try { Object v = JsonLite.parse(java.nio.file.Files.readString(p)); return v instanceof Map ? (Map<String, Object>) v : Map.of(); }
            catch (Exception e) { throw new OoaException("CONNECTION.INVALID", "connections.json is not valid JSON: " + e.getMessage(), "Fix the file in the workspace."); }
        }

        /**
         * The step's arguments with the inputs it leaves empty taken from the saved connection: plain values as they
         * are, credentials fetched from the vault just in time (and masked in logs). What the step sets itself wins.
         */
        @SuppressWarnings("unchecked")
        Map<String, Object> withConnection(String name, String pid, ActionSpec spec, Map<String, Object> raw) {
            if (!(connections().get(name) instanceof Map<?, ?> found))
                throw new OoaException("CONNECTION.NOT_FOUND", "There is no saved connection named '" + name + "'",
                        "Create it under Connections (Command Center or Studio), or choose another connection on the step.");
            Map<String, Object> conn = (Map<String, Object>) found;
            Object pkgOf = conn.get("package");
            if (pkgOf instanceof String cp && !cp.isBlank() && !cp.equals(pid))
                throw new OoaException("CONNECTION.WRONG_TOOLKIT", "Connection '" + name + "' is for " + cp + ", not " + pid, "Choose a connection made for " + pid + ".");
            Map<String, Object> out = new LinkedHashMap<>(raw == null ? Map.of() : raw);
            java.util.function.Predicate<String> empty = k -> out.get(k) == null || (out.get(k) instanceof String s && s.isBlank());
            if (conn.get("values") instanceof Map<?, ?> vals)
                for (var e : ((Map<String, Object>) vals).entrySet()) if (spec.input(e.getKey()).isPresent() && empty.test(e.getKey())) out.put(e.getKey(), e.getValue());
            if (conn.get("credentials") instanceof Map<?, ?> creds)
                for (var e : ((Map<String, Object>) creds).entrySet()) {
                    if (spec.input(e.getKey()).isEmpty() || !empty.test(e.getKey())) continue;
                    CredentialVault vault = (CredentialVault) services.get("vault");
                    if (vault == null) throw new OoaException("VAULT.UNAVAILABLE", "No vault configured for the credentials of connection '" + name + "'");
                    SecureString s = vault.getSecret(String.valueOf(e.getValue()), principal);
                    secrets.add(s.reveal()); secureValues.add(s);
                    out.put(e.getKey(), s);
                }
            return out;
        }

        void emit(String type, String stepId, Map<String, Object> data) {
            sink.emit(new RunEvent(runId, seq.incrementAndGet(), type, System.currentTimeMillis() / 1000.0, stepId, data));
        }

        Map<String, Object> data(Object... kv) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
            return m;
        }

        void log(String level, String message, String stepId) {
            for (String s : secrets) if (!s.isEmpty()) message = message.replace(s, SecureString.MASK);
            emit("log", stepId, data("level", level, "message", message));
        }

        @SuppressWarnings("unchecked")
        RunResult execute(Map<String, Object> inputs, boolean validate) {
            long t0 = System.nanoTime();
            String aid = String.valueOf(flow.getOrDefault("id", "?"));
            String status = "success";
            Map<String, Object> error = null;
            String planHash = "";
            try {
                if ("Agent".equals(flow.get("kind")))
                    throw new OoaException("RUN.WRONG_KIND", "Agent definitions run in the agent runtime, not the Runner", "Use `ooa agent run`.");
                if (validate) validator.validateOrThrow(flow);
                bindInputs(inputs);
                planHash = planHash(inputs);
                if (startAt != null) {
                    List<String> path = pathTo((List<Object>) flow.get("steps"), startAt);
                    if (path == null) throw new OoaException("RUN.START_NOT_FOUND", "There is no step '" + startAt + "' in agent " + aid,
                            "Choose a step of the agent being run.");
                    startPath = Set.copyOf(path.subList(0, path.size() - 1));
                }
                List<String> carried = startAt != null && carryOver != null ? applyCarryOver(inputs) : List.of();
                emit("run.started", null, data("automation", aid, "kind", flow.get("kind"), "planHash", planHash, "depth", depth,
                        "totalSteps", Steps.walk((List<Object>) flow.get("steps")).size(), "startAt", startAt, "onlyStep", onlyStep));
                if (startAt != null) log("info", (onlyStep ? "Running only step " : "Running from step ") + startAt + "; earlier steps are skipped", null);
                if (!carried.isEmpty()) {
                    log("info", "Values from the last run: " + String.join(", ", carried), null);
                    for (String n : carried) emit("variable.set", null, data("name", n, "value", Values.safeRepr(scope.values.get(n)), "carried", true));
                }
                execSteps((List<Object>) flow.get("steps"), true);
            } catch (OoaException e) {
                status = "RUN.CANCELLED".equals(e.code()) ? "stopped" : "failed";
                error = e.toMap();
            } catch (BreakSignal | ContinueSignal e) {
                status = "failed";
                error = new OoaException("FLOW.BAD_BREAK", "break/continue used outside a loop" + (labelOf(e) == null ? "" : " with label '" + labelOf(e) + "'")).toMap();
            } catch (StopSignal e) {
                if (e.failed) {
                    status = "failed";
                    error = new OoaException("AGENT.STOPPED", e.reason == null || e.reason.isEmpty() ? "The agent was stopped by a Stop agent step" : e.reason).toMap();
                }
            } catch (RuntimeException e) {
                status = "failed";
                error = new OoaException("RUN.INTERNAL", "Internal runner error: " + e).toMap();
            } finally {
                if (!borrowedSessions) closeSessions();
                workers.forEach(ExecutorService::shutdownNow);
            }
            Map<String, Object> outputs = new LinkedHashMap<>();
            for (Object o : (List<Object>) flow.getOrDefault("variables", List.of())) {
                Map<String, Object> v = (Map<String, Object>) o;
                if (java.util.Arrays.asList("output", "in_out").contains(v.get("direction"))) outputs.put((String) v.get("name"), scope.values.get(v.get("name")));
            }
            long dur = (System.nanoTime() - t0) / 1_000_000;
            if (trail != null && depth == 0) { trail.endedEarly = !"success".equals(status); trail.atEnd = snapshot(); }
            emit("run.finished", null, data("status", status, "durationMs", dur, "error", error, "outputs", outputs));
            if (depth == 0) { secureValues.forEach(SecureString::wipe); secrets.clear(); }
            return new RunResult(runId, aid, status, outputs, error, dur, planHash, stepsExecuted.get());
        }

        @SuppressWarnings("unchecked")
        void bindInputs(Map<String, Object> inputs) {
            Map<String, Map<String, Object>> declared = new LinkedHashMap<>();
            for (Object o : (List<Object>) flow.getOrDefault("variables", List.of())) {
                Map<String, Object> v = (Map<String, Object>) o;
                declared.put((String) v.get("name"), v);
            }
            List<String> unknown = inputs.keySet().stream()
                    .filter(k -> !declared.containsKey(k) || !java.util.Arrays.asList("input", "in_out").contains(declared.get(k).get("direction"))).toList();
            if (!unknown.isEmpty()) throw new OoaException("RUN.INPUT_UNKNOWN", "Unknown input(s): " + String.join(", ", unknown),
                    "Only variables with direction 'input' can be supplied.");
            for (var e : declared.entrySet()) {
                String name = e.getKey();
                Map<String, Object> v = e.getValue();
                boolean isInput = java.util.Arrays.asList("input", "in_out").contains(v.get("direction"));
                if (isInput && inputs.containsKey(name)) {
                    Object val = inputs.get(name);
                    if (val instanceof Map<?, ?> m && m.size() == 1 && m.containsKey("$credential")) {
                        CredentialVault vault = (CredentialVault) services.get("vault");
                        if (vault == null) throw new OoaException("VAULT.UNAVAILABLE", "No vault configured for credential inputs");
                        val = vault.getSecret(String.valueOf(m.get("$credential")), principal);
                    }
                    if (val instanceof SecureString s) { secrets.add(s.reveal()); secureValues.add(s); }
                    if (v.get("enum") instanceof List<?> en && en.stream().noneMatch(x -> Values.looseEquals(x, inputs.get(name))))
                        throw new OoaException("RUN.INPUT_INVALID", "Input '" + name + "' must be one of " + en);
                    scope.set(name, val);
                } else if (v.containsKey("default")) {
                    scope.set(name, Values.deepCopy(v.get("default")));
                } else if (isInput && Boolean.TRUE.equals(v.get("required"))) {
                    throw new OoaException("RUN.INPUT_MISSING", "Required input '" + name + "' is missing",
                            v.get("description") != null ? (String) v.get("description") : "Provide a " + v.get("type") + " value for '" + name + "'.");
                }
            }
        }

        /** Run from here: the carried-over values that were set, by name; see RunOptions.carryOver. */
        List<String> applyCarryOver(Map<String, Object> inputs) {
            List<String> set = new ArrayList<>();
            for (var e : carryOver.entrySet()) {
                String name = e.getKey(), type = scope.types.get(name);
                if (type == null || "Credential".equals(type) || inputs.containsKey(name) || e.getValue() == null) continue;
                try { scope.set(name, Values.deepCopy(e.getValue())); set.add(name); }
                catch (OoaException ignored) { /* the variable's type changed since: keep its default */ }
            }
            return set;
        }

        /** The variables as plain JSON copies, without credentials or secret values (ValueTrail). */
        Map<String, Object> snapshot() {
            Map<String, Object> out = new LinkedHashMap<>();
            synchronized (scope) {
                for (var e : scope.types.entrySet()) {
                    Object v = scope.values.get(e.getKey());
                    if (v == null || "Credential".equals(e.getValue()) || v instanceof SecureString || (v instanceof String s && secrets.contains(s))) continue;
                    out.put(e.getKey(), Values.toJsonable(v));
                }
            }
            return out;
        }

        /** Same Flow + inputs + package versions -> same plan (determinism, replay, audit). */
        @SuppressWarnings("unchecked")
        String planHash(Map<String, Object> inputs) {
            Map<String, Object> logic = new LinkedHashMap<>(flow);
            logic.remove("ui");
            Map<String, Object> versions = new TreeMap<>();
            for (Map<String, Object> s : Steps.walk((List<Object>) flow.get("steps"))) {
                String pid = String.valueOf(s.get("action")).split("/")[0];
                try { versions.put(pid, registry.resolve(pid, (String) requires.get(pid)).version()); }
                catch (OoaException e) { versions.put(pid, "?"); }
            }
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                for (Object part : List.of(logic, versions, Values.toJsonable(inputs)))
                    md.update(JsonLite.canonical(part).getBytes(StandardCharsets.UTF_8));
                return HexFormat.of().formatHex(md.digest());
            } catch (Exception e) { return ""; }
        }

        void closeSessions() {
            for (Object s : sessions.values()) if (s instanceof AutoCloseable c) try { c.close(); } catch (Exception ignored) { }
            sessions.clear();
        }

        Object lookup(String name) { return scope.get(name); }

        void check() {
            if (cancel.get()) throw new OoaException("RUN.CANCELLED", "Run was stopped from Control Center");
            if (pause.get()) {
                emit("run.paused", null, data());
                while (pause.get() && !cancel.get()) {
                    try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new OoaException("RUN.CANCELLED", "Run thread interrupted"); }
                }
                if (cancel.get()) throw new OoaException("RUN.CANCELLED", "Run was stopped while paused");
                emit("run.resumed", null, data());
            }
            if (System.nanoTime() > deadlineNanos)
                throw new OoaException("RUN.TIMEOUT", "Run exceeded its maximum duration", "Raise the run time limit or split the bot.");
        }

        @SuppressWarnings("unchecked")
        void execSteps(List<Object> steps, boolean topLevel) {
            boolean continueDefault = topLevel && "continue".equals(((Map<String, Object>) flow.getOrDefault("errorHandling", Map.of())).get("default"));
            if (!topLevel) nesting++;
            try {
                for (Object o : steps == null ? List.of() : steps) {
                    if (onlyDone) return;
                    Map<String, Object> s = (Map<String, Object>) o;
                    boolean off = Boolean.TRUE.equals(s.get("disabled"));
                    String id = String.valueOf(s.get("id"));
                    if (startAt != null && !reached) {
                        if (id.equals(startAt)) reached = true;
                        else { if (!off && startPath.contains(id)) enterTowards(s); continue; }
                    }
                    if (!off) execStep(s, continueDefault);
                    if (onlyStep && id.equals(startAt)) onlyDone = true;
                }
            } finally { if (!topLevel) nesting--; }
        }

        /** Ids from the outermost block down to the step with `id`, or null when the agent has no such step. */
        @SuppressWarnings("unchecked")
        List<String> pathTo(List<Object> steps, String id) {
            for (Object o : steps == null ? List.of() : steps) {
                if (!(o instanceof Map)) continue;
                Map<String, Object> s = (Map<String, Object>) o;
                String sid = String.valueOf(s.get("id"));
                if (sid.equals(id)) return new ArrayList<>(List.of(sid));
                List<List<Object>> sections = new ArrayList<>();
                for (String k : List.of("children", "else", "finally")) sections.add(list(s, k));
                for (String k : List.of("elseIf", "catch"))
                    for (Object b : list(s, k)) if (b instanceof Map<?, ?> bm && bm.get("children") instanceof List<?> c) sections.add((List<Object>) c);
                for (List<Object> sec : sections) {
                    List<String> p = pathTo(sec, id);
                    if (p != null) { p.add(0, sid); return p; }
                }
            }
            return null;
        }

        boolean holdsStart(List<Object> steps) { return pathTo(steps, startAt) != null; }

        /**
         * Run from here: goes into a block that holds the start step without evaluating it (no If condition, one Loop pass
         * without item variables, only the Parallel branch that holds the step). A Try keeps its Catch and Finally.
         */
        @SuppressWarnings("unchecked")
        void enterTowards(Map<String, Object> step) {
            check();
            String act = String.valueOf(step.get("action")), sid = String.valueOf(step.get("id"));
            log("info", "Entering " + act + " [" + sid + "] without evaluating it, to reach step " + startAt, sid);
            List<Object> kids = list(step, "children");
            if (holdsStart(kids)) {
                switch (act) {
                    case "core/try" -> tryBlock(step);
                    case "core/parallel" -> { for (Object b : kids) if (holdsStart(List.of(b))) { execSteps(List.of(b), false); break; } }
                    case "core/loop" -> {
                        Map<String, Object> a = step.get("args") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
                        String label = optText(a.get("label"));
                        try { execSteps(kids, false); }
                        catch (ContinueSignal | BreakSignal e) { if (labelOf(e) != null && !labelOf(e).equals(label)) throw e; }
                    }
                    default -> execSteps(kids, false);
                }
                return;
            }
            for (String k : List.of("else", "finally")) if (holdsStart(list(step, k))) { execSteps(list(step, k), false); return; }
            for (String k : List.of("elseIf", "catch"))
                for (Object b : list(step, k))
                    if (b instanceof Map<?, ?> bm && bm.get("children") instanceof List<?> c && holdsStart((List<Object>) c)) { execSteps((List<Object>) c, false); return; }
        }

        /** The debugger's view of this run, stopped before `step`. */
        Frame frame(Map<String, Object> step) {
            Run run = this;
            return new Frame() {
                public String stepId() { return String.valueOf(step.get("id")); }
                public String action() { return String.valueOf(step.get("action")); }
                public int depth() { return depth; }
                public int level() { int l = nesting; for (Run p = parent; p != null; p = p.parent) l += p.nesting + 1; return l; }
                public String automation() { return String.valueOf(flow.getOrDefault("id", "?")); }
                public List<Map<String, Object>> stack() {
                    LinkedList<Map<String, Object>> out = new LinkedList<>();
                    out.addFirst(Map.of("automation", automation(), "stepId", stepId()));
                    String at = parentStep;
                    for (Run p = parent; p != null; at = p.parentStep, p = p.parent)
                        out.addFirst(Map.of("automation", String.valueOf(p.flow.getOrDefault("id", "?")), "stepId", at == null ? "" : at));
                    return out;
                }
                public Map<String, Object> variables() {
                    Map<String, Object> out = new LinkedHashMap<>();
                    synchronized (run.scope) {
                        for (String name : run.scope.types.keySet()) {
                            Object v = run.scope.values.get(name);
                            out.put(name, v instanceof SecureString || (v instanceof String str && run.secrets.contains(str)) ? SecureString.MASK : Values.toJsonable(v));
                        }
                    }
                    return out;
                }
                public void setVariable(String name, Object value) {
                    if (run.scope.types.containsKey(name) && "Credential".equals(run.scope.types.get(name)))
                        throw new OoaException("DEBUG.READ_ONLY", "Credentials cannot be changed while debugging");
                    Object stored = run.scope.set(name, value);
                    emit("variable.set", stepId(), data("name", name, "value", Values.safeRepr(stored), "edited", true));
                }
            };
        }

        @SuppressWarnings("unchecked")
        List<Object> list(Map<String, Object> step, String key) {
            return step.get(key) instanceof List<?> l ? (List<Object>) l : List.of();
        }

        void execStep(Map<String, Object> step, boolean forceContinue) {
            check();
            if (debugger != null) { debugger.beforeStep(frame(step)); check(); }
            String sid = String.valueOf(step.get("id")), act = String.valueOf(step.get("action"));
            if (trail != null && depth == 0 && !trail.atStep.containsKey(sid)) trail.atStep.put(sid, snapshot());
            emit("step.started", sid, data("action", act));
            long t0 = System.nanoTime();
            try {
                String pid = act.substring(0, act.indexOf('/')), aid = act.substring(act.indexOf('/') + 1);
                if (pid.equals("core")) execCore(step, aid); else execLeaf(step, pid, aid);
                stepsExecuted.incrementAndGet();
            } catch (BreakSignal | ContinueSignal | StopSignal sig) {
                emit("step.finished", sid, data("action", act, "durationMs", (System.nanoTime() - t0) / 1_000_000));
                throw sig;
            } catch (OoaException e) {
                if ("RUN.CANCELLED".equals(e.code())) throw e;
                e.atStep(sid);
                Map<String, Object> d = data("action", act, "durationMs", (System.nanoTime() - t0) / 1_000_000);
                d.putAll(e.toMap());
                emit("step.failed", sid, d);
                if (Boolean.TRUE.equals(step.get("continueOnError")) || forceContinue) {
                    log("warning", "Continuing after error " + e.code(), sid);
                    return;
                }
                throw e;
            }
            emit("step.finished", sid, data("action", act, "durationMs", (System.nanoTime() - t0) / 1_000_000));
        }

        @SuppressWarnings("unchecked")
        void execCore(Map<String, Object> step, String aid) {
            Map<String, Object> a = step.get("args") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
            String sid = String.valueOf(step.get("id"));
            switch (aid) {
                case "if" -> {
                    if (condition(a.get("condition"), sid)) { execSteps(list(step, "children"), false); return; }
                    for (Object b : list(step, "elseIf")) {
                        Map<String, Object> br = (Map<String, Object>) b;
                        if (condition(br.get("condition"), sid)) { execSteps((List<Object>) br.get("children"), false); return; }
                    }
                    execSteps(list(step, "else"), false);
                }
                case "loop" -> loop(step, a);
                case "try" -> tryBlock(step);
                case "step" -> execSteps(list(step, "children"), false);
                case "parallel" -> parallel(step, a);
                case "set" -> {
                    Object stored = scope.set((String) a.get("variable"), resolve(a.get("value")));
                    emit("variable.set", sid, data("name", a.get("variable"), "value", Values.safeRepr(stored)));
                }
                case "log" -> log(String.valueOf(a.getOrDefault("level", "info")), Values.text(resolve(a.get("message"))), sid);
                case "delay" -> delay(a);
                case "wait_until" -> {
                    double timeout = Values.toNumber(resolve(a.getOrDefault("timeout_seconds", BigDecimal.valueOf(30)))).doubleValue();
                    double poll = Values.toNumber(resolve(a.getOrDefault("poll_seconds", BigDecimal.ONE))).doubleValue();
                    boolean failOnTimeout = !Boolean.FALSE.equals(Values.coerce(resolve(a.getOrDefault("fail_on_timeout", true)), "Boolean", "fail_on_timeout"));
                    long end = System.nanoTime() + (long) (timeout * 1e9);
                    boolean met = true;
                    while (!condition(a.get("condition"), sid)) {
                        if (System.nanoTime() >= end) {
                            if (failOnTimeout) throw new OoaException("WAIT.TIMEOUT", "Condition not met within " + timeout + " s", null, true, null);
                            log("info", "Condition not met within " + timeout + " s; carrying on", sid);
                            met = false;
                            break;
                        }
                        sleep(poll);
                    }
                    storeOutputs(step, Map.of("met", met));
                }
                case "throw" -> {
                    Object fix = resolve(a.get("suggested_fix"));
                    Object details = resolve(a.get("details"));
                    boolean retryable = Boolean.TRUE.equals(Values.coerce(resolve(a.get("retryable")), "Boolean", "retryable"));
                    throw new OoaException(Values.text(resolve(a.get("code"))), Values.text(resolve(a.get("message"))), fix == null ? null : Values.text(fix),
                            retryable, details instanceof Map<?, ?> d ? (Map<String, Object>) d : null);
                }
                case "comment" -> { }
                case "break" -> throw new BreakSignal(optText(a.get("label")));
                case "continue" -> throw new ContinueSignal(optText(a.get("label")));
                case "stop" -> {
                    String msg = optText(a.get("message"));
                    boolean failed = "failed".equals(optText(a.get("status")));
                    log(failed ? "error" : "info", "Agent stopped" + (msg == null ? "" : ": " + msg), sid);
                    throw new StopSignal(failed, msg);
                }
                case "pause" -> pauseHere(a, sid);
                case "run_bot" -> runChild(step, a);
                default -> throw new OoaException("FLOW.UNKNOWN_ACTION", "Unknown core action '" + aid + "'");
            }
        }

        Object resolve(Object v) { return Expressions.resolve(v, this::lookup); }

        String optText(Object raw) {
            Object v = resolve(raw);
            return v == null || Values.text(v).isEmpty() ? null : Values.text(v);
        }

        boolean flag(Map<String, Object> a, String key, boolean def) {
            Object v = resolve(a.get(key));
            return v == null ? def : Boolean.TRUE.equals(Values.coerce(v, "Boolean", key));
        }

        void delay(Map<String, Object> a) {
            double low = Values.toNumber(resolve(a.get("seconds"))).doubleValue();
            Object maxRaw = resolve(a.get("random_max"));
            double wait = low;
            if (maxRaw != null && !"".equals(maxRaw)) {
                double high = Values.toNumber(maxRaw).doubleValue();
                if (high < low) throw new OoaException("DELAY.BAD_RANGE", "random_max (" + Values.text(maxRaw) + ") is smaller than seconds (" + Values.text(resolve(a.get("seconds"))) + ")",
                        "Give a random_max at least as large as seconds.");
                wait = low + ThreadLocalRandom.current().nextDouble() * (high - low);
            }
            if (wait < 0) throw new OoaException("DELAY.BAD_VALUE", "A delay cannot be negative", "Use 0 or more.");
            String unit = optText(a.get("unit"));
            if (unit != null && !unit.equals("seconds") && !unit.equals("milliseconds") && !unit.equals("minutes"))
                throw new OoaException("DELAY.BAD_UNIT", "Unknown delay unit '" + unit + "'", "Use seconds, milliseconds or minutes.");
            sleep("milliseconds".equals(unit) ? wait / 1000 : "minutes".equals(unit) ? wait * 60 : wait);
        }

        /** core/pause: the same pause the Control Center button uses, started from inside the agent. */
        void pauseHere(Map<String, Object> a, String sid) {
            String msg = optText(a.get("message"));
            Object t = resolve(a.get("timeout_seconds"));
            long end = t == null || "".equals(t) ? Long.MAX_VALUE : System.nanoTime() + (long) (Values.toNumber(t).doubleValue() * 1e9);
            log("info", "Paused by the agent" + (msg == null ? "" : ": " + msg), sid);
            emit("run.paused", sid, data("message", msg, "byAgent", true));
            pause.set(true);
            while (pause.get() && !cancel.get() && System.nanoTime() < end) {
                try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new OoaException("RUN.CANCELLED", "Run thread interrupted"); }
            }
            pause.set(false);
            if (cancel.get()) throw new OoaException("RUN.CANCELLED", "Run was stopped while paused");
            emit("run.resumed", sid, data());
        }

        void sleep(double seconds) {
            long end = System.nanoTime() + (long) (seconds * 1e9);
            while (true) {
                check();
                long left = end - System.nanoTime();
                if (left <= 0) return;
                try { Thread.sleep(Math.min(left / 1_000_000 + 1, 50)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new OoaException("RUN.CANCELLED", "Run thread interrupted"); }
            }
        }

        @SuppressWarnings("unchecked")
        boolean condition(Object c, String stepId) {
            if (c == null) return false;
            if (c instanceof Boolean b) return b;
            if (c instanceof String s) return Expressions.truthy(Expressions.evaluate(s, this::lookup));
            if (c instanceof Map<?, ?> raw) {
                Map<String, Object> m = (Map<String, Object>) raw;
                if (m.get("all") instanceof List<?> l) { for (Object x : l) if (!condition(x, stepId)) return false; return true; }
                if (m.get("any") instanceof List<?> l) { for (Object x : l) if (condition(x, stepId)) return true; return false; }
                if (m.containsKey("not")) return !condition(m.get("not"), stepId);
                if (m.containsKey("expr")) return Expressions.truthy(Expressions.evaluate(String.valueOf(m.get("expr")), this::lookup));
                if (m.containsKey("waitSeconds")) {
                    double wait = Values.toNumber(resolve(m.get("waitSeconds"))).doubleValue();
                    double poll = m.containsKey("pollSeconds") ? Values.toNumber(resolve(m.get("pollSeconds"))).doubleValue() : 0.5;
                    Map<String, Object> once = new LinkedHashMap<>(m);
                    once.remove("waitSeconds"); once.remove("pollSeconds");
                    long end = System.nanoTime() + (long) (wait * 1e9);
                    while (true) {
                        if (condition(once, stepId)) return true;
                        if (System.nanoTime() >= end) return false;
                        sleep(Math.max(0.01, poll));
                    }
                }
                if (m.containsKey("op")) {
                    Map<String, Object> opts = new HashMap<>();
                    for (String k : List.of("ignoreCase", "trim", "includeTime", "ignoreLineBreaks")) if (m.containsKey(k)) opts.put(k, resolve(m.get(k)));
                    Object left = resolve(m.get("left")), right = resolve(m.get("right"));
                    if (Boolean.TRUE.equals(opts.get("ignoreLineBreaks"))) { left = noBreaks(left); right = noBreaks(right); }
                    return Conditions.compare(String.valueOf(m.get("op")), left, right, opts);
                }
                if (m.get("condition") instanceof String addr) {
                    String pid = addr.substring(0, addr.indexOf('/')), cid = addr.substring(addr.indexOf('/') + 1);
                    PackageRegistry.LoadedPackage pkg = registry.resolve(pid, (String) requires.get(pid));
                    ActionSpec spec = pkg.spec("condition", cid);
                    if (spec == null) throw new OoaException("FLOW.UNKNOWN_CONDITION", "Unknown condition '" + addr + "'");
                    Object r = invoke(pkg, spec, prepareArgs(spec, (Map<String, Object>) resolve(m.getOrDefault("args", Map.of()))), ctx(stepId));
                    return Boolean.TRUE.equals(r);
                }
            }
            return Expressions.truthy(resolve(c));
        }

        Object noBreaks(Object v) { return v instanceof String s ? s.replace("\r", "").replace("\n", "") : v; }

        Long optLong(Object raw, String name) {
            Object v = resolve(raw);
            if (v == null || "".equals(v)) return null;
            BigDecimal d = Values.toNumber(v);
            if (d.stripTrailingZeros().scale() > 0) throw new OoaException("LOOP.BAD_RANGE", "'" + name + "' must be a whole number, got " + Values.text(d), "Use 0, 1, 2 ...");
            return d.longValue();
        }

        /** Applies from_index, to_index, step and reverse to a finite list; each entry is {item, zero-based position}. */
        List<Object[]> select(List<Object> all, Long from, Long to, long every, boolean reverse) {
            List<Object[]> out = new ArrayList<>();
            long hi = to == null ? all.size() - 1 : Math.min(all.size() - 1, to);
            for (long i = from == null ? 0 : from; i <= hi; i += every) out.add(new Object[]{all.get((int) i), BigDecimal.valueOf(i)});
            if (reverse) Collections.reverse(out);
            return out;
        }

        @SuppressWarnings("unchecked")
        void loop(Map<String, Object> step, Map<String, Object> a) {
            String sid = String.valueOf(step.get("id"));
            long limit = Values.toNumber(resolve(a.getOrDefault("max_iterations", BigDecimal.valueOf(100000)))).longValue();
            String it = String.valueOf(a.get("iterator"));
            String label = optText(a.get("label"));
            Long from = optLong(a.get("from_index"), "from_index"), to = optLong(a.get("to_index"), "to_index");
            if ((from != null && from < 0) || (to != null && to < 0))
                throw new OoaException("LOOP.BAD_RANGE", "from_index and to_index cannot be negative", "Indexes start at 0.");
            boolean reverse = flag(a, "reverse", false);
            Iterator<Object[]> items;
            switch (it) {
                case "times" -> {
                    long n = Values.toNumber(resolve(a.get("count"))).longValue();
                    Object st = resolve(a.get("start")), inc = resolve(a.get("step"));
                    BigDecimal start = st == null || "".equals(st) ? BigDecimal.ZERO : Values.toNumber(st);
                    BigDecimal by = inc == null || "".equals(inc) ? BigDecimal.ONE : Values.toNumber(inc);
                    items = new Iterator<>() {
                        long i = 0;
                        public boolean hasNext() { return i < n; }
                        public Object[] next() { Object[] r = {start.add(by.multiply(BigDecimal.valueOf(i))), BigDecimal.valueOf(i)}; i++; return r; }
                    };
                }
                case "while" -> {
                    boolean atEnd = flag(a, "check_at_end", false);
                    items = new Iterator<>() {
                        long i = 0;
                        public boolean hasNext() { return (atEnd && i == 0) || condition(a.get("condition"), sid); }
                        public Object[] next() { return new Object[]{null, BigDecimal.valueOf(i++)}; }
                    };
                }
                case "list", "dictionary", "dictionary_keys", "dictionary_values", "table_rows" -> {
                    long every = stepOf(a);
                    Object over = resolve(a.get("over"));
                    List<Object> all = new ArrayList<>();
                    if (over != null) switch (it) {
                        case "list" -> all.addAll((List<Object>) Values.coerce(over, "List", "over"));
                        case "dictionary" -> ((Map<String, Object>) Values.coerce(over, "Dictionary", "over")).forEach((k, v) -> {
                            Map<String, Object> e = new LinkedHashMap<>(); e.put("key", k); e.put("value", v); all.add(e);
                        });
                        case "dictionary_keys" -> all.addAll(((Map<String, Object>) Values.coerce(over, "Dictionary", "over")).keySet());
                        case "dictionary_values" -> all.addAll(((Map<String, Object>) Values.coerce(over, "Dictionary", "over")).values());
                        default -> {
                            Map<String, Object> t = (Map<String, Object>) Values.coerce(over, "Table", "over");
                            List<Object> cols = (List<Object>) t.get("columns");
                            for (Object r : (List<Object>) t.get("rows")) {
                                Map<String, Object> rec = new LinkedHashMap<>();
                                List<Object> row = (List<Object>) r;
                                for (int i = 0; i < cols.size(); i++) rec.put(String.valueOf(cols.get(i)), i < row.size() ? row.get(i) : null);
                                all.add(rec);
                            }
                        }
                    }
                    items = select(all, from, to, every, reverse).iterator();
                }
                default -> {
                    long every = stepOf(a);
                    String pid = it.substring(0, it.indexOf('/')), iid = it.substring(it.indexOf('/') + 1);
                    PackageRegistry.LoadedPackage pkg = registry.resolve(pid, (String) requires.get(pid));
                    ActionSpec spec = pkg.spec("iterator", iid);
                    if (spec == null) throw new OoaException("FLOW.UNKNOWN_ITERATOR", "Unknown iterator '" + it + "'");
                    Object res = invoke(pkg, spec, prepareArgs(spec, (Map<String, Object>) resolve(a.getOrDefault("iterator_args", Map.of()))), ctx(sid));
                    Iterator<Object> src = ((Iterable<Object>) res).iterator();
                    if (reverse) {
                        List<Object> all = new ArrayList<>();
                        while (src.hasNext()) {
                            if (all.size() > limit) throw new OoaException("LOOP.LIMIT", "Loop exceeded " + limit + " iterations", "A reversed loop reads every item first; raise max_iterations or turn reverse off.");
                            all.add(src.next());
                        }
                        items = select(all, from, to, every, true).iterator();
                    } else items = lazyRange(src, from == null ? 0 : from, to, every);
                }
            }
            long i = 0;
            while (items.hasNext()) {
                if (i >= limit) throw new OoaException("LOOP.LIMIT", "Loop exceeded " + limit + " iterations",
                        "Check the loop condition or raise max_iterations.");
                check();
                Object[] cur = items.next();
                if (a.get("as") instanceof String as) scope.set(as, cur[0]);
                if (a.get("index") instanceof String idx) scope.set(idx, cur[1]);
                // chosen columns of the current row straight into their own variables
                if (a.get("columns_to") instanceof Map<?, ?> cols && cur[0] instanceof Map<?, ?> row)
                    for (Map.Entry<?, ?> e : cols.entrySet()) {
                        String var = String.valueOf(e.getValue()).trim(); if (var.startsWith("$")) var = var.substring(1);
                        if (var.isEmpty()) continue;
                        if (!row.containsKey(e.getKey())) throw new OoaException("LOOP.NO_COLUMN", "The current row has no column '" + e.getKey() + "'", "Columns: " + row.keySet() + ".");
                        scope.set(var, row.get(e.getKey()));
                    }
                i++;
                try { execSteps(list(step, "children"), false); }
                catch (ContinueSignal c) { if (c.label != null && !c.label.equals(label)) throw c; }
                catch (BreakSignal b) { if (b.label != null && !b.label.equals(label)) throw b; break; }
            }
        }

        long stepOf(Map<String, Object> a) {
            Long every = optLong(a.get("step"), "step");
            if (every == null) return 1;
            if (every < 1) throw new OoaException("LOOP.BAD_RANGE", "step must be 1 or more for this iterator, got " + every, "Use 1 to visit every item, 2 for every second item.");
            return every;
        }

        /** Toolkit iterators can be endless (trigger loops), so the range is applied while reading. */
        Iterator<Object[]> lazyRange(Iterator<Object> src, long lo, Long to, long every) {
            return new Iterator<Object[]>() {
                long pos = -1; Object[] pending; boolean done;
                void advance() {
                    while (!done && pending == null) {
                        if (to != null && pos >= to) { done = true; return; }
                        if (!src.hasNext()) { done = true; return; }
                        Object x = src.next();
                        pos++;
                        if (pos < lo || (pos - lo) % every != 0) continue;
                        pending = new Object[]{x, BigDecimal.valueOf(pos)};
                    }
                }
                public boolean hasNext() { advance(); return pending != null; }
                public Object[] next() { advance(); Object[] r = pending; pending = null; return r; }
            };
        }

        private List<Map<String, Object>> walked;

        /** The error as a Catch variable: code, message, suggestedFix, stepId, plus the failing step's action and its line (1-based position in the agent). */
        @SuppressWarnings("unchecked")
        Map<String, Object> errorInfo(OoaException e) {
            Map<String, Object> m = new LinkedHashMap<>(e.toMap());
            if (walked == null) walked = Steps.walk((List<Object>) flow.get("steps"));
            Object line = null, action = null;
            for (int i = 0; i < walked.size(); i++)
                if (String.valueOf(walked.get(i).get("id")).equals(e.stepId())) { line = BigDecimal.valueOf(i + 1); action = walked.get(i).get("action"); break; }
            m.put("line", line);
            m.put("action", action);
            return m;
        }

        /** Screenshot for a Catch block; never fails the Catch. Returns the file path, or null when no picture could be taken. */
        String screenshot(Object option, String stepId) {
            Object v = resolve(option);
            String name = v instanceof String s && !s.isBlank() && !s.equalsIgnoreCase("true") ? s
                    : "screenshots/" + runId + "_" + stepId + "_" + System.currentTimeMillis() + ".png";
            Path target = workDir.resolve(name).toAbsolutePath();
            try {
                if (target.getParent() != null) java.nio.file.Files.createDirectories(target.getParent());
                if (services.get("screen_capture") instanceof ScreenCapture sc) sc.capture(target);
                else if (!java.awt.GraphicsEnvironment.isHeadless()) {
                    java.awt.Rectangle all = new java.awt.Rectangle(java.awt.Toolkit.getDefaultToolkit().getScreenSize());
                    javax.imageio.ImageIO.write(new java.awt.Robot().createScreenCapture(all), "png", target.toFile());
                } else { log("warning", "No screen to capture for the error screenshot", stepId); return null; }
                return target.toString();
            } catch (Exception | LinkageError ex) {
                log("warning", "Could not take the error screenshot: " + ex.getMessage(), stepId);
                return null;
            }
        }

        @SuppressWarnings("unchecked")
        void tryBlock(Map<String, Object> step) {
            String sid = String.valueOf(step.get("id"));
            try {
                execSteps(list(step, "children"), false);
            } catch (OoaException e) {
                if ("RUN.CANCELLED".equals(e.code())) throw e;
                boolean handled = false;
                for (Object o : list(step, "catch")) {
                    Map<String, Object> c = (Map<String, Object>) o;
                    List<Object> codes = c.get("codes") instanceof List<?> l ? (List<Object>) l : List.of();
                    boolean match = ErrorCodes.anyMatches(codes, e.code());   // exact, PREFIX.* or a kind such as NOT_FOUND
                    if (match) {
                        Map<String, Object> err = errorInfo(e);
                        if (c.get("screenshot") != null && !Boolean.FALSE.equals(c.get("screenshot"))) err.put("screenshot", screenshot(c.get("screenshot"), e.stepId() == null ? sid : e.stepId()));
                        if (c.get("as") instanceof String as) scope.set(as, err);
                        if (c.get("message_to") instanceof String mt) scope.set(mt, e.getMessage());
                        if (c.get("line_to") instanceof String lt) scope.set(lt, err.get("line"));
                        log("info", "Caught " + e.code(), sid);
                        List<Object> body = c.get("children") instanceof List<?> l ? (List<Object>) l : List.of();
                        if (Boolean.TRUE.equals(c.get("continueOnError"))) {
                            nesting++;
                            try { for (Object s : body) if (!Boolean.TRUE.equals(((Map<String, Object>) s).get("disabled"))) execStep((Map<String, Object>) s, true); }
                            finally { nesting--; }
                        } else execSteps(body, false);
                        handled = true;
                        if (Boolean.TRUE.equals(c.get("rethrow"))) throw e;
                        break;
                    }
                }
                if (!handled) throw e;
            } finally {
                if (!list(step, "finally").isEmpty()) execSteps(list(step, "finally"), false);
            }
        }

        @SuppressWarnings("unchecked")
        void parallel(Map<String, Object> step, Map<String, Object> a) {
            int n = Values.toNumber(resolve(a.getOrDefault("max_concurrency", BigDecimal.valueOf(4)))).intValue();
            List<Map<String, Object>> branches = new ArrayList<>();
            for (Object o : list(step, "children")) if (!Boolean.TRUE.equals(((Map<String, Object>) o).get("disabled"))) branches.add((Map<String, Object>) o);
            Semaphore permits = new Semaphore(Math.max(1, n));
            List<OoaException> errors = new CopyOnWriteArrayList<>();
            List<StopSignal> stops = new CopyOnWriteArrayList<>();
            try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
                for (Map<String, Object> b : branches) ex.submit(() -> {
                    try { permits.acquire(); execStep(b, false); }
                    catch (OoaException e) { errors.add(e); }
                    catch (StopSignal s) { stops.add(s); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    finally { permits.release(); }
                });
            }
            if (!errors.isEmpty()) throw errors.get(0);
            if (!stops.isEmpty()) throw stops.get(0);
        }

        @SuppressWarnings("unchecked")
        void runChild(Map<String, Object> step, Map<String, Object> a) {
            if (depth + 1 > MAX_CHILD_DEPTH) throw new OoaException("CHILD.DEPTH", "Child agent nesting is too deep",
                    "Keep child agent chains under " + MAX_CHILD_DEPTH + " levels; check for recursion.");
            AutomationRepository repo = (AutomationRepository) services.get("repository");
            if (repo == null) throw new OoaException("SERVICE.UNAVAILABLE", "No agent repository available for child agents");
            Map<String, Object> args = (Map<String, Object>) resolve(a);
            String automation = Values.text(args.get("automation"));
            Object version = args.get("version") == null || "".equals(args.get("version")) ? "production" : args.get("version");
            int repeat = args.get("repeat") == null ? 1 : Values.toNumber(args.get("repeat")).intValue();
            if (repeat < 1) throw new OoaException("CHILD.BAD_REPEAT", "repeat must be 1 or more, got " + repeat, "Use 1 to run the child once.");
            double repeatDelay = args.get("repeat_delay_seconds") == null ? 0 : Values.toNumber(args.get("repeat_delay_seconds")).doubleValue();
            boolean failOnError = args.get("fail_on_error") == null || Boolean.TRUE.equals(Values.coerce(args.get("fail_on_error"), "Boolean", "fail_on_error"));
            boolean same = args.get("same_session") != null && Boolean.TRUE.equals(Values.coerce(args.get("same_session"), "Boolean", "same_session"));
            Double timeout = args.get("timeout_seconds") == null || "".equals(args.get("timeout_seconds")) ? null : Values.toNumber(args.get("timeout_seconds")).doubleValue();
            if (timeout != null && timeout <= 0) throw new OoaException("CHILD.BAD_TIMEOUT", "timeout_seconds must be more than 0", "Leave it empty for no limit.");
            Map<String, Object> childInputs = args.get("inputs") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
            Map<String, Object> outputs = Map.of(), error = new LinkedHashMap<>();
            String status = "success";
            int runs = 0;
            for (int r = 0; r < repeat; r++) {
                if (r > 0 && repeatDelay > 0) sleep(repeatDelay);
                Map<String, Object> childFlow = repo.get(automation, version);
                Run child = new Run(childFlow, runId + "." + step.get("id") + (repeat > 1 ? "." + (r + 1) : ""), cancel, timeout, depth + 1);
                child.pause = pause;
                child.debugger = debugger;
                child.parent = this;
                child.parentStep = String.valueOf(step.get("id"));
                if (same) { child.sessions = sessions; child.borrowedSessions = true; }
                RunResult res = child.executeAsChild(childInputs, deadlineNanos, secrets);
                runs++;
                stepsExecuted.addAndGet(res.stepsExecuted());
                if ("stopped".equals(res.status())) throw new OoaException("RUN.CANCELLED", "Child run was stopped");
                outputs = res.outputs();
                if (!res.ok()) {
                    Map<String, Object> err = res.error() == null ? Map.of() : res.error();
                    String code = String.valueOf(err.getOrDefault("code", "CHILD.FAILED"));
                    String msg = "Child agent '" + automation + "' failed: " + err.get("message");
                    if ("RUN.TIMEOUT".equals(code) && timeout != null) { code = "CHILD.TIMEOUT"; msg = "Child agent '" + automation + "' did not finish within " + Values.text(BigDecimal.valueOf(timeout)) + " s"; }
                    if (failOnError) throw new OoaException(code, msg, (String) err.get("suggestedFix"), false,
                            Map.of("childRunId", res.runId(), "childStepId", String.valueOf(err.get("stepId"))));
                    status = "failed";
                    error = new LinkedHashMap<>();
                    error.put("code", code); error.put("message", msg); error.put("stepId", err.get("stepId")); error.put("childRunId", res.runId());
                    log("warning", msg + " (carrying on: fail_on_error is off)", String.valueOf(step.get("id")));
                }
            }
            if (args.get("output_map") instanceof Map<?, ?> om) {
                for (var e : ((Map<String, Object>) om).entrySet()) {
                    if (!outputs.containsKey(e.getKey())) {
                        if ("failed".equals(status)) continue;
                        throw new OoaException("CHILD.OUTPUT_MISSING", "Child agent '" + automation + "' has no output '" + e.getKey() + "'",
                                "Outputs: " + String.join(", ", outputs.keySet()) + ".");
                    }
                    Object v = outputs.get(e.getKey());
                    Object stored = scope.set(Values.text(e.getValue()), Values.toJsonable(v) == v ? v : normalise(v));
                    emit("variable.set", String.valueOf(step.get("id")), data("name", Values.text(e.getValue()), "value", Values.safeRepr(stored)));
                }
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("outputs", outputs);
            result.put("status", status);
            result.put("error", error);
            result.put("runs", BigDecimal.valueOf(runs));
            storeOutputs(step, result);
        }

        /** A child agent started by a package action (for example an interrupt handler) rather than by a run_bot step. */
        Map<String, Object> childAgent(String automation, Map<String, Object> inputs) {
            if (depth + 1 > MAX_CHILD_DEPTH) throw new OoaException("CHILD.DEPTH", "Child agent nesting is too deep",
                    "Keep child agent chains under " + MAX_CHILD_DEPTH + " levels; check for recursion.");
            AutomationRepository repo = (AutomationRepository) services.get("repository");
            Run child = new Run(repo.get(automation, "production"), runId + ".handler-" + UUID.randomUUID().toString().substring(0, 8), cancel, null, depth + 1);
            child.pause = pause;
            child.debugger = debugger;
            child.parent = this;
            child.parentStep = "interrupt";
            RunResult res = child.executeAsChild(inputs == null ? Map.of() : inputs, deadlineNanos, secrets);
            stepsExecuted.addAndGet(res.stepsExecuted());
            if ("stopped".equals(res.status())) throw new OoaException("RUN.CANCELLED", "Child run was stopped");
            if (!res.ok()) {
                Map<String, Object> err = res.error() == null ? Map.of() : res.error();
                throw new OoaException(String.valueOf(err.getOrDefault("code", "CHILD.FAILED")), "Child agent '" + automation + "' failed: " + err.get("message"),
                        (String) err.get("suggestedFix"));
            }
            return res.outputs();
        }

        RunResult executeAsChild(Map<String, Object> inputs, long parentDeadline, List<String> parentSecrets) {
            secrets.addAll(parentSecrets);
            return execute(inputs, true);
        }

        // ----- leaf actions -----
        ActionContext ctx(String stepId) {
            return new ActionContext(runId, stepId, tenantId, workDir, sessions, services, cancel, () -> List.copyOf(secrets),
                    (lvl, msg) -> emit("log", stepId, data("level", lvl, "message", msg)));
        }

        Map<String, Object> prepareArgs(ActionSpec spec, Map<String, Object> raw) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (ActionSpec.InputSpec in : spec.inputs()) {
                Object v = raw == null ? null : raw.get(in.name());
                if (v != null) {
                    if (in.type() != OoaType.ANY && in.type() != OoaType.CREDENTIAL && !(v instanceof SecureString)) {
                        try { v = Values.coerce(v, in.type().flowName(), in.name()); }
                        catch (OoaException e) {
                            // name the step's input, not a variable: "Input 'values' expects Dictionary, got List"
                            if (!"VAR.TYPE_MISMATCH".equals(e.code())) throw e;
                            throw new OoaException(e.code(), e.getMessage().replaceFirst("^Variable '", "Input '"),
                                    "Give " + in.name() + " a " + in.type().flowName() + (in.description().isEmpty() ? "." : ": " + in.description()), false, null);
                        }
                    }
                    if (!in.pattern().isEmpty() && v instanceof String s && !java.util.regex.Pattern.compile(in.pattern()).matcher(s).find())
                        throw new ActionException("ACTION.INPUT_INVALID", "Input '" + in.name() + "' value '" + s + "' does not match " + in.pattern(), in.description());
                    out.put(in.name(), v);
                } else if (in.defaultValue() != null) {
                    out.put(in.name(), in.defaultValue());
                } else if (in.required()) {
                    throw new ActionException("ACTION.INPUT_MISSING", "Input '" + in.name() + "' resolved to empty", in.description());
                }
            }
            return out;
        }

        Object invoke(PackageRegistry.LoadedPackage pkg, ActionSpec spec, Map<String, Object> args, ActionContext ctx) {
            Parameter[] ps = spec.method().getParameters();
            Object[] call = new Object[ps.length];
            call[0] = ctx;
            for (int i = 1; i < ps.length; i++) {
                ActionSpec.InputSpec in = spec.inputs().get(i - 1);
                call[i] = JavaArgs.convert(args.get(in.name()), ps[i].getType(), in.name());
            }
            try {
                return spec.method().invoke(pkg.instance(), call);
            } catch (InvocationTargetException e) {
                Throwable t = e.getCause();
                if (t instanceof OoaException oe) throw oe;
                throw new ActionException("ACTION.UNEXPECTED", pkg.id() + "/" + spec.id() + " raised " + t.getClass().getSimpleName() + ": " + t.getMessage(),
                        "Report this to the toolkit publisher with the run id.");
            } catch (IllegalAccessException e) {
                throw new ActionException("ACTION.UNEXPECTED", "Cannot call " + pkg.id() + "/" + spec.id() + ": " + e.getMessage());
            } catch (Error e) {
                if (e instanceof VirtualMachineError) throw e;
                // the native executable knows every toolkit's commands but runs only the built-in toolkits
                throw new ActionException("ACTION.NOT_IN_THIS_PROGRAM", pkg.id() + "/" + spec.id() + " cannot run in this program: " + e.getMessage(),
                        "Run the automation with the full install (bin\\ooa.bat run) or on a runner.");
            }
        }

        Object callWithTimeout(Callable<Object> fn, double timeoutSeconds) {
            ExecutorService ex = worker.get();
            if (ex == null) {
                ex = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "ooa-action-" + runId); t.setDaemon(true); return t; });
                worker.set(ex);
                workers.add(ex);
            }
            Future<Object> f = ex.submit(fn);
            try {
                return f.get((long) (timeoutSeconds * 1000), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                f.cancel(true);
                ex.shutdownNow();
                worker.remove();
                throw new OoaException("STEP.TIMEOUT", "Action did not finish within " + timeoutSeconds + " s",
                        "Raise timeoutSeconds on the step or check the target application.", true, null);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof OoaException oe) throw oe;
                if (e.getCause() instanceof RuntimeException re) throw re;
                throw new IllegalStateException(e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new OoaException("RUN.CANCELLED", "Run thread interrupted");
            }
        }

        @SuppressWarnings("unchecked")
        void execLeaf(Map<String, Object> step, String pid, String aid) {
            String sid = String.valueOf(step.get("id"));
            PackageRegistry.LoadedPackage pkg = registry.resolve(pid, (String) requires.get(pid));
            ActionSpec spec = pkg.spec("action", aid);
            if (spec == null) throw new OoaException("FLOW.UNKNOWN_ACTION", pid + "@" + pkg.version() + " has no action '" + aid + "'");
            registry.recordUsage(pid, pkg.version(), String.valueOf(flow.getOrDefault("id", "?")));
            Map<String, Object> eh = (Map<String, Object>) flow.getOrDefault("errorHandling", Map.of());
            Map<String, Object> policy = step.get("retry") instanceof Map<?, ?> m ? (Map<String, Object>) m
                    : eh.get("retry") instanceof Map<?, ?> m2 ? (Map<String, Object>) m2 : Map.of();
            int maxRetry = Values.toNumber(policy.getOrDefault("max", BigDecimal.ZERO)).intValue();
            double timeout = step.get("timeoutSeconds") != null ? Values.toNumber(step.get("timeoutSeconds")).doubleValue() : spec.timeoutSeconds();
            int attempt = 0;
            Object result;
            while (true) {
                OoaException err;
                try {
                    Map<String, Object> raw = (Map<String, Object>) resolve(step.getOrDefault("args", Map.of()));
                    if (step.get("connection") instanceof String cn && !cn.isBlank()) raw = withConnection(cn.strip(), pid, spec, raw);
                    Map<String, Object> args = prepareArgs(spec, raw);
                    ActionContext c = ctx(sid);
                    result = callWithTimeout(() -> invoke(pkg, spec, args, c), timeout);
                    break;
                } catch (OoaException e) {
                    if ("RUN.CANCELLED".equals(e.code())) throw e;
                    err = e;
                } catch (RuntimeException e) {
                    err = new ActionException("ACTION.UNEXPECTED", pid + "/" + aid + " raised " + e.getClass().getSimpleName() + ": " + e.getMessage(),
                            "Report this to the toolkit publisher with the run id.");
                }
                List<Object> codes = policy.get("codes") instanceof List<?> l ? (List<Object>) l : null;
                boolean retry = attempt < maxRetry && (codes != null ? !codes.isEmpty() && ErrorCodes.anyMatches(codes, err.code()) : err.retryable());
                if (!retry) throw err;
                attempt++;
                double delay = Values.toNumber(policy.getOrDefault("delayMs", BigDecimal.valueOf(500))).doubleValue() / 1000;
                if ("exponential".equals(policy.get("backoff"))) delay *= Math.pow(2, attempt - 1);
                emit("step.retry", sid, data("attempt", attempt, "code", err.code(), "delayMs", (long) (delay * 1000)));
                sleep(delay);
            }
            storeOutputs(step, result instanceof Map<?, ?> r ? (Map<String, Object>) r : Map.of());
        }

        @SuppressWarnings("unchecked")
        void storeOutputs(Map<String, Object> step, Map<String, Object> result) {
            Map<String, Object> out = step.get("out") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
            for (var e : out.entrySet()) {
                if (!result.containsKey(e.getKey()))
                    throw new ActionException("ACTION.OUTPUT_MISSING", "Action did not return output '" + e.getKey() + "'");
                Object v = result.get(e.getKey());
                if (v instanceof SecureString s) { secrets.add(s.reveal()); secureValues.add(s); }
                Object stored = scope.set(String.valueOf(e.getValue()), Values.toJsonable(v) == v ? v : normalise(v));
                emit("variable.set", String.valueOf(step.get("id")), data("name", e.getValue(), "value", Values.safeRepr(stored)));
            }
        }

        /** Package results may use int/long/double; the runtime model uses BigDecimal. SecureString is kept. */
        Object normalise(Object v) {
            if (v instanceof SecureString || v instanceof java.time.LocalDateTime) return v;
            return Values.toJsonable(v);
        }
    }
}
