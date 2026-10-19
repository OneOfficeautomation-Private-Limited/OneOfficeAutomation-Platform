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
            return new SecureString(String.valueOf(r.get("value")), ref);
        };
        Map<String, Object> globals = (Map<String, Object>) job.getOrDefault("global_values", Map.of());
        PackageRegistry registry = FirstPartyPackages.registry();
        EventSink sink = e -> send(Map.of("type", "event", "event", e.toMap()));
        String principal = String.valueOf(job.getOrDefault("principal", "agent"));
        Runner[] holder = new Runner[1];
        BotInvoker invoker = (automationId, in2) -> holder[0].run(repo.get(automationId, "production"), in2, new Runner.RunOptions().cancel(cancel).pause(pause));
        holder[0] = Runner.builder(registry).sink(sink).workDir(workspace).service("repository", repo).service("vault", vault)
                .service("global_values", (java.util.function.Supplier<Object>) () -> globals).service("bot_invoker", invoker)
                // One Model Connections from Command Center; their keys are vault references resolved through the vault above.
                .service("model_connections", (java.util.function.Supplier<Object>) () -> job.getOrDefault("model_connections", Map.of()))
                .principal(principal).build();
        RunResult result;
        try {
            result = holder[0].run(main, (Map<String, Object>) job.getOrDefault("inputs", Map.of()),
                    new Runner.RunOptions().runId(String.valueOf(job.get("run_id"))).cancel(cancel).pause(pause).maxRunSeconds(24 * 3600.0));
        } catch (RuntimeException e) {
            result = new RunResult(String.valueOf(job.get("run_id")), String.valueOf(main.get("id")), "failed", Map.of(),
                    Map.of("code", "AGENT.RUN_CRASHED", "message", String.valueOf(e)), 0, null, 0);
        }
        send(Map.of("type", "result", "result", result.toMap()));
        System.exit(0);
    }
}
