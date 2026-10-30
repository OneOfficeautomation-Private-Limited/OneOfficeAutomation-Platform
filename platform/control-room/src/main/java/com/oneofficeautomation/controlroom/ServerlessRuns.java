package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.runner.*;
import com.oneofficeautomation.sdk.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.oneofficeautomation.controlroom.ControlRoom.*;

/**
 * Serverless Agent APIs (C7): agents that only call web services and work on data run inside the Command Center, with
 * no runner. They run on a virtual runner, "Command Center (serverless)", so they are queued, logged, audited, licensed
 * and finished like any other run. Only the toolkits below may be used (no desktop, browser, programs, scripts or
 * databases), every file path stays inside the run's own temporary workspace, at most four run at once, and a run
 * lasts at most five minutes.
 */
public final class ServerlessRuns {
    static final String DEVICE = "dev_serverless";
    static final String NAME = "Command Center (serverless)";
    static final int PARALLEL = 4;
    static final double MAX_SECONDS = 300;
    /** Toolkits that need no desktop and cannot reach the server's programs, settings or databases. */
    static final Set<String> TOOLKITS = Set.of("core", "ooa.string", "ooa.number", "ooa.boolean", "ooa.datetime", "ooa.list", "ooa.dictionary",
        "ooa.datatable", "ooa.json", "ooa.csv", "ooa.xml", "ooa.log", "ooa.crypto", "ooa.fuzzy", "ooa.currency", "ooa.global_values", "ooa.pgp",
        "ooa.rest", "ooa.soap", "ooa.genai", "ooa.ai_skills", "ooa.knowledge", "ooa.workload", "ooa.pdf", "ooa.excel", "ooa.word", "ooa.powerpoint", "ooa.email",
        "ooa.microsoft365", "ooa.google", "ooa.salesforce", "ooa.servicenow", "ooa.jira", "ooa.slack", "ooa.workday", "ooa.github", "ooa.azure_devops",
        "ooa.asana", "ooa.zendesk", "ooa.okta", "ooa.box", "ooa.docusign", "ooa.snowflake", "ooa.databricks", "ooa.kafka", "ooa.connector");

    private final ControlRoom cr;
    private final Db db;
    private final PackageRegistry registry;
    private final Path dataDir;
    private final ExecutorService pool = Executors.newFixedThreadPool(PARALLEL, Thread.ofPlatform().daemon().name("ooa-serverless-", 0).factory());
    private final ScheduledExecutorService beat = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("ooa-serverless-beat").factory());

    ServerlessRuns(ControlRoom cr, PackageRegistry registry, Path dataDir) {
        this.cr = cr; this.db = cr.db; this.registry = registry; this.dataDir = dataDir;
    }

    private boolean started;

    /**
     * When the server starts (every node of a cluster): the virtual runner shows as online, queued runs start, and runs
     * cut off by a stopped node are failed once they are past the longest a serverless run may take.
     */
    synchronized void start() {
        if (started || db.one("SELECT id FROM devices WHERE id = ?", DEVICE) == null) return;
        started = true;
        beat.scheduleAtFixedRate(() -> {
            try {
                db.update("UPDATE devices SET last_seen = ? WHERE id = ?", now(), DEVICE);
                db.update("UPDATE runs SET status = 'failed', finished = ?, error = ? WHERE device_id = ? AND status = 'running' AND started < ?", now(),
                    JsonLite.write(new OoaException("RUN.INTERRUPTED", "The Command Center stopped during the run").toMap()), DEVICE, now().minusSeconds((long) MAX_SECONDS + 120));
            } catch (RuntimeException ignored) { }
        }, 0, 30, TimeUnit.SECONDS);
        kick();
    }

    /** The virtual runner's row, made the first time a serverless API task is made. Nobody knows its secret, so no real runner can sign in as it. */
    synchronized Map<String, Object> device() {
        Map<String, Object> d = db.one("SELECT * FROM devices WHERE id = ?", DEVICE);
        if (d != null) return d;
        db.update("INSERT INTO devices (id, name, hostname, os, agent_version, secret_hash, enabled, last_seen, registered, registered_by, lifespan, secret_set) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
            DEVICE, NAME, "command-center", "Command Center", VERSION, Security.sha256(Security.token(32)), true, now(), now(), "system", "persistent", now());
        db.update("UPDATE devices SET device_type = 'serverless' WHERE id = ?", DEVICE);
        start();
        return db.one("SELECT * FROM devices WHERE id = ?", DEVICE);
    }

    /** Refuses an agent (or an agent it calls) that uses a toolkit serverless runs may not use. */
    @SuppressWarnings("unchecked")
    void check(Map<String, Object> main) {
        if (cr.tenant != null && !cr.serverlessAllowed) throw ControlRoom.notInSaas("Running agents in the Command Center (serverless)");
        Map<String, Object> bundle = new LinkedHashMap<>();
        cr.collect(main, bundle, 0);
        List<Map<String, Object>> flows = new ArrayList<>(List.of(main));
        for (Object b : bundle.values()) if (b instanceof Map<?, ?> m) flows.add((Map<String, Object>) m);
        Set<String> bad = new TreeSet<>();
        for (Map<String, Object> f : flows)
            for (Map<String, Object> s : Steps.walk((List<Object>) f.getOrDefault("steps", List.of()))) {
                String a = String.valueOf(s.get("action"));
                String pid = a.contains("/") ? a.substring(0, a.indexOf('/')) : "core";
                if (!TOOLKITS.contains(pid)) bad.add(pid);
            }
        if (!bad.isEmpty()) throw new OoaException("CR.NOT_SERVERLESS", "The agent uses " + String.join(", ", bad) + ", which serverless runs cannot use",
            "Run it on a runner, or keep to web services and data toolkits (REST, SOAP, the cloud apps, Excel, PDF, JSON and the like).");
    }

    /** Looks for queued serverless runs; each pool thread runs one at a time. */
    void kick() { pool.submit(this::drain); }

    private void drain() {
        try {
            for (Map<String, Object> job; (job = cr.claimNext(device())) != null; ) execute(job);
        } catch (RuntimeException e) {
            cr.audit.record("system", "serverless.run", "device", DEVICE, String.valueOf(e.getMessage()), "failed", null);
        }
    }

    @SuppressWarnings("unchecked")
    void execute(Map<String, Object> job) {
        Map<String, Object> d = device();
        String runId = String.valueOf(job.get("run_id"));
        Path ws = dataDir.resolve("serverless").resolve(runId);
        AtomicBoolean cancel = new AtomicBoolean();
        ScheduledFuture<?> watch = beat.scheduleAtFixedRate(() -> {
            try { Map<String, Object> r = db.one("SELECT cancel_requested FROM runs WHERE id = ?", runId); if (r != null && Boolean.TRUE.equals(r.get("cancel_requested"))) cancel.set(true); }
            catch (RuntimeException ignored) { }
        }, 1, 1, TimeUnit.SECONDS);
        List<Object> events = new ArrayList<>();
        Map<String, Object> resultMap;
        try {
            Files.createDirectories(ws);
            writeFiles(ws, (Map<String, Object>) job.getOrDefault("files", Map.of()));
            Map<String, Object> bots = (Map<String, Object>) job.getOrDefault("bots", Map.of());
            Map<String, Object> main = (Map<String, Object>) job.get("flow");
            check(main);
            AutomationRepository repo = (id, version) -> {
                if (id.equals(main.get("id"))) return main;
                Object f = bots.get(id);
                if (f == null) throw new OoaException("REPO.NOT_FOUND", "Agent '" + id + "' is not published in Command Center (no live production version)");
                return (Map<String, Object>) f;
            };
            CredentialVault vault = (ref, principal) -> new SecureString(cr.credentialForRun(d, runId, ref), ref, false);
            Map<String, Object> globals = (Map<String, Object>) job.getOrDefault("global_values", Map.of());
            EventSink sink = e -> {
                synchronized (events) {
                    events.add(e.toMap());
                    if (events.size() >= 25) { cr.appendEvents(d, runId, new ArrayList<>(events)); events.clear(); }
                }
            };
            Runner[] holder = new Runner[1];
            BotInvoker invoker = (automationId, in2) -> holder[0].run(repo.get(automationId, "production"), in2, new Runner.RunOptions().cancel(cancel));
            holder[0] = Runner.builder(registry).sink(sink).workDir(ws).service("repository", repo).service("vault", vault)
                .service("global_values", (java.util.function.Supplier<Object>) () -> globals).service("bot_invoker", invoker)
                .service("connections", (java.util.function.Supplier<Object>) () -> job.getOrDefault("connections", Map.of()))
                .service("model_connections", (java.util.function.Supplier<Object>) () -> job.getOrDefault("model_connections", Map.of()))
                .service("prompt_skills", (java.util.function.Supplier<Object>) () -> job.getOrDefault("skills", Map.of()))
                .service("confine_paths", Boolean.TRUE)
                .principal(String.valueOf(job.getOrDefault("principal", "agent"))).build();
            RunResult result = holder[0].run(main, (Map<String, Object>) job.getOrDefault("inputs", Map.of()),
                new Runner.RunOptions().runId(runId).cancel(cancel).maxRunSeconds(MAX_SECONDS));
            resultMap = new LinkedHashMap<>(result.toMap());
            List<Object> ai = aiCalls(ws.resolve("ai-governance.jsonl"));
            if (!ai.isEmpty()) resultMap.put("ai_calls", ai);
        } catch (OoaException e) {
            resultMap = Map.of("status", "failed", "error", e.toMap());
        } catch (IOException | RuntimeException e) {
            resultMap = Map.of("status", "failed", "error", Map.of("code", "AGENT.RUN_CRASHED", "message", String.valueOf(e)));
        } finally { watch.cancel(false); }
        synchronized (events) { if (!events.isEmpty()) cr.appendEvents(d, runId, new ArrayList<>(events)); }
        cr.finish(d, runId, resultMap);
        deleteTree(ws);
    }

    static void writeFiles(Path ws, Map<String, Object> files) throws IOException {
        Path root = ws.toAbsolutePath().normalize();
        for (Map.Entry<String, Object> f : files.entrySet()) {
            Path target = root.resolve(f.getKey()).normalize();
            if (!target.startsWith(root) || target.equals(root)) throw new IOException("A repository file path leaves the workspace: " + f.getKey());
            Files.createDirectories(target.getParent());
            Files.write(target, Base64.getDecoder().decode(String.valueOf(f.getValue())));
        }
    }

    static List<Object> aiCalls(Path log) {
        List<Object> out = new ArrayList<>();
        try {
            if (!Files.exists(log)) return out;
            for (String line : Files.readAllLines(log, StandardCharsets.UTF_8)) {
                if (line.isBlank() || out.size() >= 500) continue;
                try { out.add(JsonLite.parse(line)); } catch (RuntimeException ignored) { }
            }
        } catch (IOException ignored) { /* no AI calls */ }
        return out;
    }

    private static void deleteTree(Path p) {
        try (var s = Files.walk(p)) { s.sorted(Comparator.reverseOrder()).forEach(x -> { try { Files.deleteIfExists(x); } catch (IOException ignored) { } }); }
        catch (IOException ignored) { }
    }
}
