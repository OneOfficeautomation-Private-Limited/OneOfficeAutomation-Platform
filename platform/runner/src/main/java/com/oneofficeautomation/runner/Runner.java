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
        return r.execute(inputs == null ? Map.of() : inputs, true);
    }

    public static final class RunOptions {
        String runId; AtomicBoolean cancel; Double maxRunSeconds; AtomicBoolean pause;
        public RunOptions pause(AtomicBoolean p) { pause = p; return this; }
        public RunOptions runId(String id) { runId = id; return this; }
        public RunOptions cancel(AtomicBoolean c) { cancel = c; return this; }
        public RunOptions maxRunSeconds(double s) { maxRunSeconds = s; return this; }
    }

    // internal control-flow signals
    private static final class BreakSignal extends RuntimeException { BreakSignal() { super(null, null, false, false); } }
    private static final class ContinueSignal extends RuntimeException { ContinueSignal() { super(null, null, false, false); } }

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
        final Map<String, Object> sessions = new ConcurrentHashMap<>();
        final List<String> secrets = new CopyOnWriteArrayList<>();
        final List<SecureString> secureValues = new CopyOnWriteArrayList<>();
        final AtomicLong seq = new AtomicLong();
        final AtomicInteger stepsExecuted = new AtomicInteger();
        final ThreadLocal<ExecutorService> worker = new ThreadLocal<>();
        AtomicBoolean pause = new AtomicBoolean();
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
                emit("run.started", null, data("automation", aid, "kind", flow.get("kind"), "planHash", planHash, "depth", depth,
                        "totalSteps", Steps.walk((List<Object>) flow.get("steps")).size()));
                execSteps((List<Object>) flow.get("steps"), true);
            } catch (OoaException e) {
                status = "RUN.CANCELLED".equals(e.code()) ? "stopped" : "failed";
                error = e.toMap();
            } catch (BreakSignal | ContinueSignal e) {
                status = "failed";
                error = new OoaException("FLOW.BAD_BREAK", "break/continue used outside a loop").toMap();
            } catch (RuntimeException e) {
                status = "failed";
                error = new OoaException("RUN.INTERNAL", "Internal runner error: " + e).toMap();
            } finally {
                closeSessions();
                workers.forEach(ExecutorService::shutdownNow);
            }
            Map<String, Object> outputs = new LinkedHashMap<>();
            for (Object o : (List<Object>) flow.getOrDefault("variables", List.of())) {
                Map<String, Object> v = (Map<String, Object>) o;
                if ("output".equals(v.get("direction"))) outputs.put((String) v.get("name"), scope.values.get(v.get("name")));
            }
            long dur = (System.nanoTime() - t0) / 1_000_000;
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
                    .filter(k -> !declared.containsKey(k) || !"input".equals(declared.get(k).get("direction"))).toList();
            if (!unknown.isEmpty()) throw new OoaException("RUN.INPUT_UNKNOWN", "Unknown input(s): " + String.join(", ", unknown),
                    "Only variables with direction 'input' can be supplied.");
            for (var e : declared.entrySet()) {
                String name = e.getKey();
                Map<String, Object> v = e.getValue();
                boolean isInput = "input".equals(v.get("direction"));
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
            for (Object o : steps == null ? List.of() : steps) {
                Map<String, Object> s = (Map<String, Object>) o;
                if (Boolean.TRUE.equals(s.get("disabled"))) continue;
                execStep(s, continueDefault);
            }
        }

        @SuppressWarnings("unchecked")
        List<Object> list(Map<String, Object> step, String key) {
            return step.get(key) instanceof List<?> l ? (List<Object>) l : List.of();
        }

        void execStep(Map<String, Object> step, boolean forceContinue) {
            check();
            String sid = String.valueOf(step.get("id")), act = String.valueOf(step.get("action"));
            emit("step.started", sid, data("action", act));
            long t0 = System.nanoTime();
            try {
                String pid = act.substring(0, act.indexOf('/')), aid = act.substring(act.indexOf('/') + 1);
                if (pid.equals("core")) execCore(step, aid); else execLeaf(step, pid, aid);
                stepsExecuted.incrementAndGet();
            } catch (BreakSignal | ContinueSignal sig) {
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
                case "delay" -> sleep(Values.toNumber(resolve(a.get("seconds"))).doubleValue());
                case "wait_until" -> {
                    double timeout = Values.toNumber(resolve(a.getOrDefault("timeout_seconds", BigDecimal.valueOf(30)))).doubleValue();
                    double poll = Values.toNumber(resolve(a.getOrDefault("poll_seconds", BigDecimal.ONE))).doubleValue();
                    long end = System.nanoTime() + (long) (timeout * 1e9);
                    while (!condition(a.get("condition"), sid)) {
                        if (System.nanoTime() >= end) throw new OoaException("WAIT.TIMEOUT", "Condition not met within " + timeout + " s", null, true, null);
                        sleep(poll);
                    }
                }
                case "throw" -> {
                    Object fix = resolve(a.get("suggested_fix"));
                    throw new OoaException(Values.text(resolve(a.get("code"))), Values.text(resolve(a.get("message"))), fix == null ? null : Values.text(fix));
                }
                case "comment" -> { }
                case "break" -> throw new BreakSignal();
                case "continue" -> throw new ContinueSignal();
                case "run_bot" -> runChild(step, a);
                default -> throw new OoaException("FLOW.UNKNOWN_ACTION", "Unknown core action '" + aid + "'");
            }
        }

        Object resolve(Object v) { return Expressions.resolve(v, this::lookup); }

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
                if (m.containsKey("op")) return Conditions.compare(String.valueOf(m.get("op")), resolve(m.get("left")), resolve(m.get("right")));
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

        @SuppressWarnings("unchecked")
        void loop(Map<String, Object> step, Map<String, Object> a) {
            String sid = String.valueOf(step.get("id"));
            long limit = Values.toNumber(resolve(a.getOrDefault("max_iterations", BigDecimal.valueOf(100000)))).longValue();
            String it = String.valueOf(a.get("iterator"));
            Iterator<Object> items;
            switch (it) {
                case "times" -> {
                    long n = Values.toNumber(resolve(a.get("count"))).longValue();
                    items = new Iterator<>() {
                        long i = 0;
                        public boolean hasNext() { return i < n; }
                        public Object next() { return BigDecimal.valueOf(i++); }
                    };
                }
                case "while" -> items = new Iterator<>() {
                    public boolean hasNext() { return condition(a.get("condition"), sid); }
                    public Object next() { return null; }
                };
                case "list" -> {
                    Object over = resolve(a.get("over"));
                    items = over == null ? Collections.emptyIterator() : new ArrayList<>((List<Object>) Values.coerce(over, "List", "over")).iterator();
                }
                case "dictionary" -> {
                    Object over = resolve(a.get("over"));
                    List<Object> kv = new ArrayList<>();
                    if (over != null) ((Map<String, Object>) Values.coerce(over, "Dictionary", "over")).forEach((k, v) -> {
                        Map<String, Object> e = new LinkedHashMap<>(); e.put("key", k); e.put("value", v); kv.add(e);
                    });
                    items = kv.iterator();
                }
                case "table_rows" -> {
                    Object over = resolve(a.get("over"));
                    List<Object> recs = new ArrayList<>();
                    if (over != null) {
                        Map<String, Object> t = (Map<String, Object>) Values.coerce(over, "Table", "over");
                        List<Object> cols = (List<Object>) t.get("columns");
                        for (Object r : (List<Object>) t.get("rows")) {
                            Map<String, Object> rec = new LinkedHashMap<>();
                            List<Object> row = (List<Object>) r;
                            for (int i = 0; i < cols.size(); i++) rec.put(String.valueOf(cols.get(i)), i < row.size() ? row.get(i) : null);
                            recs.add(rec);
                        }
                    }
                    items = recs.iterator();
                }
                default -> {
                    String pid = it.substring(0, it.indexOf('/')), iid = it.substring(it.indexOf('/') + 1);
                    PackageRegistry.LoadedPackage pkg = registry.resolve(pid, (String) requires.get(pid));
                    ActionSpec spec = pkg.spec("iterator", iid);
                    if (spec == null) throw new OoaException("FLOW.UNKNOWN_ITERATOR", "Unknown iterator '" + it + "'");
                    Object res = invoke(pkg, spec, prepareArgs(spec, (Map<String, Object>) resolve(a.getOrDefault("iterator_args", Map.of()))), ctx(sid));
                    items = ((Iterable<Object>) res).iterator();
                }
            }
            long i = 0;
            while (items.hasNext()) {
                if (i >= limit) throw new OoaException("LOOP.LIMIT", "Loop exceeded " + limit + " iterations",
                        "Check the loop condition or raise max_iterations.");
                check();
                Object item = items.next();
                if (a.get("as") instanceof String as) scope.set(as, item);
                if (a.get("index") instanceof String idx) scope.set(idx, BigDecimal.valueOf(i));
                i++;
                try { execSteps(list(step, "children"), false); }
                catch (ContinueSignal c) { continue; }
                catch (BreakSignal b) { break; }
            }
        }

        @SuppressWarnings("unchecked")
        void tryBlock(Map<String, Object> step) {
            try {
                execSteps(list(step, "children"), false);
            } catch (OoaException e) {
                if ("RUN.CANCELLED".equals(e.code())) throw e;
                boolean handled = false;
                for (Object o : list(step, "catch")) {
                    Map<String, Object> c = (Map<String, Object>) o;
                    List<Object> codes = c.get("codes") instanceof List<?> l ? (List<Object>) l : List.of();
                    boolean match = codes.isEmpty() || codes.stream().map(String::valueOf).anyMatch(x ->
                            x.equals(e.code()) || (x.endsWith("*") && e.code().startsWith(x.substring(0, x.length() - 1))));
                    if (match) {
                        if (c.get("as") instanceof String as) scope.set(as, e.toMap());
                        log("info", "Caught " + e.code(), String.valueOf(step.get("id")));
                        execSteps((List<Object>) c.get("children"), false);
                        handled = true;
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
            try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
                for (Map<String, Object> b : branches) ex.submit(() -> {
                    try { permits.acquire(); execStep(b, false); }
                    catch (OoaException e) { errors.add(e); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    finally { permits.release(); }
                });
            }
            if (!errors.isEmpty()) throw errors.get(0);
        }

        @SuppressWarnings("unchecked")
        void runChild(Map<String, Object> step, Map<String, Object> a) {
            if (depth + 1 > MAX_CHILD_DEPTH) throw new OoaException("CHILD.DEPTH", "Child agent nesting is too deep",
                    "Keep child agent chains under " + MAX_CHILD_DEPTH + " levels; check for recursion.");
            AutomationRepository repo = (AutomationRepository) services.get("repository");
            if (repo == null) throw new OoaException("SERVICE.UNAVAILABLE", "No agent repository available for child agents");
            Map<String, Object> args = (Map<String, Object>) resolve(a);
            String automation = Values.text(args.get("automation"));
            Map<String, Object> childFlow = repo.get(automation, args.getOrDefault("version", "production"));
            Run child = new Run(childFlow, runId + "." + step.get("id"), cancel, null, depth + 1);
            child.pause = pause;
            Map<String, Object> childInputs = args.get("inputs") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
            RunResult res = child.executeAsChild(childInputs, deadlineNanos, secrets);
            stepsExecuted.addAndGet(res.stepsExecuted());
            if ("stopped".equals(res.status())) throw new OoaException("RUN.CANCELLED", "Child run was stopped");
            if (!res.ok()) {
                Map<String, Object> err = res.error() == null ? Map.of() : res.error();
                throw new OoaException(String.valueOf(err.getOrDefault("code", "CHILD.FAILED")),
                        "Child agent '" + automation + "' failed: " + err.get("message"), (String) err.get("suggestedFix"), false,
                        Map.of("childRunId", res.runId(), "childStepId", String.valueOf(err.get("stepId"))));
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("outputs", res.outputs());
            result.put("status", res.status());
            storeOutputs(step, result);
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
                    if (in.type() != OoaType.ANY && in.type() != OoaType.CREDENTIAL && !(v instanceof SecureString))
                        v = Values.coerce(v, in.type().flowName(), in.name());
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
                    Map<String, Object> args = prepareArgs(spec, (Map<String, Object>) resolve(step.getOrDefault("args", Map.of())));
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
                boolean retry = attempt < maxRetry && (codes != null ? codes.contains(err.code()) : err.retryable());
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
