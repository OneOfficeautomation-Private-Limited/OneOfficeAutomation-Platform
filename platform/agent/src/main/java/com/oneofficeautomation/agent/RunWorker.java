package com.oneofficeautomation.agent;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.runner.*;
import com.oneofficeautomation.sdk.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs one bot in its own process. Line protocol with the agent (one JSON object per line):
 * stdin: the job, then {"type":"credential","id","value"|"error"}, {"type":"cancel"|"pause"|"resume"};
 * stdout: {"type":"event"}, {"type":"credential","id","ref"} requests and finally {"type":"result"}.
 * Secrets arrive only on request, live only in memory and are masked in every event.
 */
public final class RunWorker {
    private static PrintStream protocol;
    private static final Map<Long, CompletableFuture<Map<String, Object>>> waiting = new ConcurrentHashMap<>();
    private static final AtomicLong ids = new AtomicLong();

    /**
     * Places the repository files the agent requires at the same path in the workspace (base64 contents). Paths come
     * from Command Center, but are still kept inside the workspace.
     */
    static void writeFiles(Path workspace, Map<String, Object> files) throws IOException {
        Path root = workspace.toAbsolutePath().normalize();
        for (Map.Entry<String, Object> f : files.entrySet()) {
            Path target = root.resolve(f.getKey()).normalize();
            if (!target.startsWith(root) || target.equals(root)) throw new IOException("A repository file path leaves the workspace: " + f.getKey());
            java.nio.file.Files.createDirectories(target.getParent());
            java.nio.file.Files.write(target, java.util.Base64.getDecoder().decode(String.valueOf(f.getValue())));
        }
    }

    static synchronized void send(Object msg) { protocol.println(JsonLite.write(msg)); protocol.flush(); }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        protocol = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        System.setOut(System.err);   // nothing but protocol messages may reach stdout
        Path workspace = Path.of(args.length > 0 ? args[0] : ".");
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        Map<String, Object> job = (Map<String, Object>) JsonLite.parse(in.readLine());
        AtomicBoolean cancel = new AtomicBoolean(), pause = new AtomicBoolean();
        Thread control = new Thread(() -> {
            try {
                for (String line; (line = in.readLine()) != null; ) {
                    Map<String, Object> m = (Map<String, Object>) JsonLite.parse(line);
                    switch (String.valueOf(m.get("type"))) {
                        case "cancel" -> { cancel.set(true); pause.set(false); }
                        case "pause" -> pause.set(true);
                        case "resume" -> pause.set(false);
                        case "credential" -> { CompletableFuture<Map<String, Object>> f = waiting.remove(((Number) m.get("id")).longValue()); if (f != null) f.complete(m); }
                        default -> { }
                    }
                }
            } catch (Exception ignored) { }
            cancel.set(true);   // the agent went away: stop safely
        }, "ooa-worker-control");
        control.setDaemon(true);
        control.start();

        Map<String, Object> bots = (Map<String, Object>) job.getOrDefault("bots", Map.of());
        Map<String, Object> main = (Map<String, Object>) job.get("flow");
        AutomationRepository repo = (id, version) -> {
            if (id.equals(main.get("id"))) return main;
            Object f = bots.get(id);
            if (f == null) throw new OoaException("REPO.NOT_FOUND", "Agent '" + id + "' is not published in Command Center (no live production version)", "Check it in and set a live production version.");
            return (Map<String, Object>) f;
        };
        CredentialVault vault = (ref, principal) -> {
            long id = ids.incrementAndGet();
            CompletableFuture<Map<String, Object>> f = new CompletableFuture<>();
            waiting.put(id, f);
            send(Map.of("type", "credential", "id", id, "ref", ref));
            Map<String, Object> r;
            try { r = f.get(60, TimeUnit.SECONDS); }
            catch (Exception e) { throw new OoaException("VAULT.TIMEOUT", "No answer from Control Room for credential " + ref, null, true, null); }
            if (r.get("error") instanceof Map<?, ?> e) throw new OoaException(String.valueOf(e.get("code")), String.valueOf(e.get("message")));
            return new SecureString(String.valueOf(r.get("value")), ref, Boolean.TRUE.equals(r.get("password_only")));
        };
        Map<String, Object> globals = (Map<String, Object>) job.getOrDefault("global_values", Map.of());
        writeFiles(workspace, (Map<String, Object>) job.getOrDefault("files", Map.of()));
        Map<String, Object> skills = (Map<String, Object>) job.getOrDefault("skills", Map.of());
        PackageRegistry registry = FirstPartyPackages.registry();
        String toolkitProblem = loadCustomToolkits(registry, workspace, (List<Object>) job.getOrDefault("custom_toolkits", List.of()));
        EventSink sink = e -> send(Map.of("type", "event", "event", e.toMap()));
        String principal = String.valueOf(job.getOrDefault("principal", "agent"));
        Runner[] holder = new Runner[1];
        BotInvoker invoker = (automationId, in2) -> holder[0].run(repo.get(automationId, "production"), in2, new Runner.RunOptions().cancel(cancel).pause(pause));
        holder[0] = Runner.builder(registry).sink(sink).workDir(workspace).service("repository", repo).service("vault", vault)
                .service("global_values", (java.util.function.Supplier<Object>) () -> globals).service("bot_invoker", invoker)
                // saved connections (address and vault references per connector) from Command Center; secrets resolve through the vault above
                .service("connections", (java.util.function.Supplier<Object>) () -> job.getOrDefault("connections", Map.of()))
                // Model Connections from Command Center; their keys are vault references resolved through the vault above.
                .service("model_connections", (java.util.function.Supplier<Object>) () -> job.getOrDefault("model_connections", Map.of()))
                // Prompt Skills kept in Command Center; a skill file in the workspace is used when Command Center sends none
                .service("prompt_skills", (java.util.function.Supplier<Object>) () -> skills)
                .principal(principal).build();
        RunResult result;
        // AI calls are appended to ai-governance.jsonl in the workspace; remember where this run starts reading.
        Path aiLog = workspace.resolve("ai-governance.jsonl");
        long aiStart = 0;
        try { aiStart = java.nio.file.Files.exists(aiLog) ? java.nio.file.Files.size(aiLog) : 0; } catch (IOException ignored) { }
        try {
            if (toolkitProblem != null) throw new OoaException("RUN.TOOLKIT_LOAD", toolkitProblem);
            result = holder[0].run(main, (Map<String, Object>) job.getOrDefault("inputs", Map.of()),
                    new Runner.RunOptions().runId(String.valueOf(job.get("run_id"))).cancel(cancel).pause(pause).maxRunSeconds(24 * 3600.0));
        } catch (OoaException e) {
            result = new RunResult(String.valueOf(job.get("run_id")), String.valueOf(main.get("id")), "failed", Map.of(), e.toMap(), 0, null, 0);
        } catch (RuntimeException e) {
            result = new RunResult(String.valueOf(job.get("run_id")), String.valueOf(main.get("id")), "failed", Map.of(),
                    Map.of("code", "AGENT.RUN_CRASHED", "message", String.valueOf(e)), 0, null, 0);
        }
        Map<String, Object> resultMap = new LinkedHashMap<>(result.toMap());
        List<Object> aiCalls = aiCalls(aiLog, aiStart);
        if (!aiCalls.isEmpty()) resultMap.put("ai_calls", aiCalls);
        send(Map.of("type", "result", "result", resultMap));
        System.exit(0);
    }

    /**
     * Custom toolkits the Command Center sent with the run (K6): each jar's hash and publisher signature are checked here
     * again before it is loaded in a class loader of its own. Returns why one could not be loaded, or null.
     */
    @SuppressWarnings("unchecked")
    static String loadCustomToolkits(PackageRegistry registry, Path workspace, List<Object> toolkits) {
        for (Object o : toolkits) {
            Map<String, Object> t = (Map<String, Object>) o;
            String name = t.get("id") + " " + t.get("version");
            try {
                byte[] jar = Base64.getDecoder().decode(String.valueOf(t.get("jar")));
                if (!ToolkitJar.sha256(jar).equals(t.get("sha256"))) return "The jar of " + name + " was changed on the way";
                Path f = workspace.resolve(".ooa-toolkits").resolve(t.get("sha256") + ".jar");
                java.nio.file.Files.createDirectories(f.getParent());
                java.nio.file.Files.write(f, jar);
                ToolkitJar.Loaded l = ToolkitJar.load(f, RunWorker.class.getClassLoader());
                if (l.signature() == null || !String.valueOf(t.get("publisher")).equals(l.signature().publisher())) return name + " is not signed by its publisher";
                registry.trust(new PackageSigning.PublisherKey(String.valueOf(t.get("publisher")), null, ToolkitJar.publicKey(String.valueOf(t.get("public_key")))));
                registry.install(l.cls(), l.signature());
            } catch (OoaException e) { return "Cannot load " + name + ": " + e.getMessage(); }
            catch (IOException | IllegalArgumentException e) { return "Cannot load " + name + ": " + e; }
        }
        return null;
    }

    /** The AI call records this run appended to the governance log (at most 500), for the Command Center's AI governance view. */
    static List<Object> aiCalls(Path log, long from) {
        List<Object> out = new ArrayList<>();
        try (RandomAccessFile f = new RandomAccessFile(log.toFile(), "r")) {
            if (f.length() <= from) return out;
            f.seek(from);
            byte[] b = new byte[(int) Math.min(f.length() - from, 4_000_000)];
            f.readFully(b);
            for (String line : new String(b, StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank() || out.size() >= 500) continue;
                try { out.add(JsonLite.parse(line)); } catch (RuntimeException ignored) { }
            }
        } catch (IOException ignored) { /* no AI calls */ }
        return out;
    }
}
