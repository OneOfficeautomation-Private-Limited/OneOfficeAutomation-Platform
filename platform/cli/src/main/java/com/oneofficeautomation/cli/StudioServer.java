package com.oneofficeautomation.cli;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.runner.*;
import com.oneofficeautomation.sdk.OoaException;
import com.oneofficeautomation.sdk.PackageRegistry;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * OOA Studio preview: a local developer workbench for building, validating and running bots against the
 * real Runner and packages. Binds to 127.0.0.1 only, rejects cross-site requests, and reads/writes bot files
 * only inside {workspace}/bots. The production Studio is the React app served by Control Center.
 */
public final class StudioServer {
    private static final int MAX_BODY = 5 * 1024 * 1024;

    private final Workspace ws;
    private final Path workspace;
    private final PackageRegistry registry = FirstPartyPackages.registry();
    private final JsonCodec json = Ooa.codec();
    private final FlowSchemaValidator schema = Ooa.schemaValidator();
    private final int port;
    private TriggerService triggers;
    private final Map<String, LiveRun> live = new java.util.concurrent.ConcurrentHashMap<>();

    /** A run started from Studio in the background, so the UI can follow it live and pause or stop it. */
    static final class LiveRun {
        final String id; final EventSink.ListSink sink = new EventSink.ListSink();
        final java.util.concurrent.atomic.AtomicBoolean cancel = new java.util.concurrent.atomic.AtomicBoolean();
        final java.util.concurrent.atomic.AtomicBoolean pause = new java.util.concurrent.atomic.AtomicBoolean();
        volatile RunResult result; volatile long finishedAt; final long startedAt = System.currentTimeMillis();
        LiveRun(String id) { this.id = id; }
    }
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    StudioServer(Path workspace, int port, Path examples) throws IOException {
        this.ws = new Workspace(workspace);
        this.workspace = ws.root;
        this.port = port;
        ws.seedIfEmpty(examples);
    }

    void start() throws IOException {
        HttpServer server;
        try { server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0); }
        catch (java.net.BindException e) {
            throw new OoaException("STUDIO.PORT_IN_USE", "Port " + port + " is already in use; another Studio may still be running",
                    "Stop the other Studio (Ctrl+C in its window), or start this one with --port " + (port + 1) + ".");
        }
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::handle);
        server.start();
        triggers = new TriggerService(ws, (botPath, inputs) -> runFile(botPath, inputs));
        triggers.start();
        System.out.println("ONEOFFICEAUTOMATION Studio (preview) is running.");
        System.out.println("  Open:      http://localhost:" + port);
        System.out.println("  Workspace: " + workspace);
        System.out.println("  Press Ctrl+C to stop.");
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            String host = Optional.ofNullable(ex.getRequestHeaders().getFirst("Host")).orElse("");
            if (!host.startsWith("localhost:") && !host.startsWith("127.0.0.1:")) { send(ex, 403, "text/plain", "Forbidden host"); return; }
            String origin = ex.getRequestHeaders().getFirst("Origin");
            if (origin != null && !origin.equals("http://localhost:" + port) && !origin.equals("http://127.0.0.1:" + port)) {
                send(ex, 403, "text/plain", "Cross-site request refused"); return;
            }
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if (path.startsWith("/api/")) api(ex, method, path.substring(5));
            else asset(ex, path);
        } catch (OoaException e) {
            sendJson(ex, 400, Map.of("error", e.toMap()));
        } catch (RuntimeException e) {
            sendJson(ex, 500, Map.of("error", new OoaException("STUDIO.INTERNAL", String.valueOf(e)).toMap()));
        } finally {
            ex.close();
        }
    }

    private void asset(HttpExchange ex, String path) throws IOException {
        String name = switch (path) {
            case "/", "/index.html" -> "index.html";
            case "/studio.css", "/studio.js", "/logo-mark.png", "/logo-full.png" -> path.substring(1);
            default -> null;
        };
        if (name == null) { send(ex, 404, "text/plain", "Not found"); return; }
        try (InputStream in = StudioServer.class.getResourceAsStream("/studio/" + name)) {
            if (in == null) { send(ex, 404, "text/plain", "Missing asset " + name); return; }
            String type = name.endsWith(".css") ? "text/css; charset=utf-8" : name.endsWith(".js") ? "text/javascript; charset=utf-8"
                    : name.endsWith(".png") ? "image/png" : "text/html; charset=utf-8";
            sendBytes(ex, 200, type, in.readAllBytes());
        }
    }

    @SuppressWarnings("unchecked")
    private void api(HttpExchange ex, String method, String route) throws IOException {
        if (method.equals("GET") && route.equals("info")) {
            sendJson(ex, 200, Map.of("product", "ONEOFFICEAUTOMATION Studio", "edition", "preview", "workspace", workspace.toString()));
        } else if (method.equals("GET") && route.equals("packages")) {
            sendJson(ex, 200, Map.of("packages", registry.allManifests()));
        } else if (method.equals("GET") && route.equals("tree")) {
            sendJson(ex, 200, Map.of("workspace", workspace.toString(), "tree", ws.tree()));
        } else if (route.equals("bot")) {
            String rel = query(ex).getOrDefault("path", "");
            if (!rel.endsWith(Workspace.BOT_SUFFIX)) throw new OoaException("STUDIO.NOT_A_BOT", "Not a bot file: " + rel, "Bots end with .flow.json.");
            Path p = ws.resolve(rel);
            if (method.equals("GET")) {
                if (!Files.isRegularFile(p)) throw new OoaException("STUDIO.NOT_FOUND", "Bot not found: " + rel);
                sendJson(ex, 200, Map.of("path", rel, "flow", json.parse(Files.readString(p))));
            } else if (method.equals("PUT")) {
                if (!Files.isDirectory(p.getParent())) throw new OoaException("STUDIO.NOT_FOUND", "Folder not found for " + rel);
                Map<String, Object> flow = (Map<String, Object>) body(ex);
                Path tmp = p.resolveSibling("." + p.getFileName() + ".tmp");
                Files.writeString(tmp, json.write(flow, true));
                Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
                sendJson(ex, 200, Map.of("saved", rel, "problems", validator().validate(flow)));
            } else send(ex, 405, "text/plain", "Method not allowed");
        } else if (method.equals("POST") && route.startsWith("files/")) {
            Map<String, Object> b = (Map<String, Object>) body(ex);
            String path = String.valueOf(b.getOrDefault("path", ""));
            String result = switch (route.substring(6)) {
                case "folder" -> ws.createFolder(String.valueOf(b.getOrDefault("parent", "")), String.valueOf(b.get("name")));
                case "bot" -> ws.createBot(String.valueOf(b.getOrDefault("parent", "")), String.valueOf(b.get("name")),
                        String.valueOf(b.getOrDefault("kind", "TaskBot")), v -> json.write(v, true));
                case "rename" -> ws.rename(path, String.valueOf(b.get("name")));
                case "move" -> ws.move(path, String.valueOf(b.getOrDefault("to", "")));
                case "delete" -> ws.delete(path);
                default -> throw new OoaException("STUDIO.UNKNOWN", "Unknown file operation " + route);
            };
            sendJson(ex, 200, Map.of("path", result));
        } else if (method.equals("POST") && route.equals("validate")) {
            sendJson(ex, 200, Map.of("problems", validator().validate((Map<String, Object>) body(ex))));
        } else if (method.equals("POST") && route.equals("run")) {
            Map<String, Object> req = (Map<String, Object>) body(ex);
            EventSink.ListSink sink = new EventSink.ListSink();
            Runner runner = runner(sink);
            Map<String, Object> inputs = req.get("inputs") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
            RunResult r = runner.run((Map<String, Object>) req.get("flow"), inputs, new Runner.RunOptions().maxRunSeconds(300));
            sendJson(ex, 200, Map.of("result", r.toMap(), "events", sink.events().stream().map(RunEvent::toMap).toList()));
        } else if (method.equals("POST") && route.equals("runs")) {
            Map<String, Object> req = (Map<String, Object>) body(ex);
            Map<String, Object> flow = (Map<String, Object>) req.get("flow");
            Map<String, Object> inputs = (Map<String, Object>) req.getOrDefault("inputs", Map.of());
            List<Map<String, Object>> problems = validator().validate(flow);
            if (!problems.isEmpty()) { sendJson(ex, 400, Map.of("error", Map.of("code", "FLOW.INVALID", "message", problems.size() + " problem(s): fix them before running"), "problems", problems)); return; }
            live.values().removeIf(r -> r.finishedAt > 0 && System.currentTimeMillis() - r.finishedAt > 600_000);
            if (live.values().stream().filter(r -> r.result == null).count() >= 4) throw new OoaException("STUDIO.TOO_MANY_RUNS", "Four runs are already in progress", "Wait for a run to finish or stop one.");
            String id = "run_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            LiveRun lr = new LiveRun(id);
            live.put(id, lr);
            Thread t = new Thread(() -> {
                try { lr.result = runner(lr.sink).run(flow, inputs, new Runner.RunOptions().runId(id).cancel(lr.cancel).pause(lr.pause).maxRunSeconds(3600)); }
                catch (RuntimeException e) { lr.result = new RunResult(id, null, "failed", Map.of(), Map.of("code", "STUDIO.RUN_CRASHED", "message", String.valueOf(e.getMessage())), System.currentTimeMillis() - lr.startedAt, null, 0); }
                finally { lr.finishedAt = System.currentTimeMillis(); }
            }, "ooa-studio-run-" + id);
            t.setDaemon(true);
            t.start();
            sendJson(ex, 202, Map.of("run_id", id));
        } else if (method.equals("GET") && route.matches("runs/run_[a-f0-9]{16}")) {
            LiveRun lr = live.get(route.substring(5));
            if (lr == null) throw new OoaException("STUDIO.NOT_FOUND", "Unknown or expired run");
            int after = Integer.parseInt(query(ex).getOrDefault("after", "0"));
            List<Map<String, Object>> evs = new ArrayList<>();
            for (RunEvent e : lr.sink.events()) if (e.seq() > after) evs.add(e.toMap());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("run_id", lr.id);
            out.put("state", lr.result != null ? lr.result.status() : lr.pause.get() ? "paused" : "running");
            out.put("elapsed_ms", (lr.finishedAt > 0 ? lr.finishedAt : System.currentTimeMillis()) - lr.startedAt);
            out.put("events", evs);
            if (lr.result != null) out.put("result", lr.result.toMap());
            sendJson(ex, 200, out);
        } else if (method.equals("POST") && route.matches("runs/run_[a-f0-9]{16}/(pause|resume|stop)")) {
            String[] parts = route.split("/");
            LiveRun lr = live.get(parts[1]);
            if (lr == null) throw new OoaException("STUDIO.NOT_FOUND", "Unknown or expired run");
            switch (parts[2]) { case "pause" -> lr.pause.set(true); case "resume" -> lr.pause.set(false); default -> { lr.cancel.set(true); lr.pause.set(false); } }
            sendJson(ex, 200, Map.of("ok", true));
        } else if (method.equals("POST") && route.equals("capture")) {
            Map<String, Object> req = (Map<String, Object>) body(ex);
            if (registry.allManifests().stream().noneMatch(m -> "ooa.browser".equals(m.get("id"))))
                throw new OoaException("STUDIO.NO_BROWSER_MODULE", "The Browser module is not installed", "Add packages-browser to ooa.optionalModules in gradle.properties.");
            Map<String, Object> flow = new LinkedHashMap<>();
            flow.put("kind", "TaskBot"); flow.put("id", "studio_capture"); flow.put("name", "Studio capture");
            flow.put("variables", List.of(Map.of("name", "selector", "type", "String", "direction", "output"), Map.of("name", "element", "type", "Dictionary", "direction", "output")));
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("url", String.valueOf(req.getOrDefault("url", "about:blank")));
            args.put("browser", String.valueOf(req.getOrDefault("browser", "edge")));
            flow.put("steps", List.of(Map.of("id", "pick", "action", "ooa.browser/pick_element", "args", args, "out", Map.of("selector", "selector", "element", "element"))));
            RunResult r = runner(new EventSink.ListSink()).run(flow, Map.of(), new Runner.RunOptions().maxRunSeconds(900));
            if (!r.ok()) sendJson(ex, 400, Map.of("error", r.error()));
            else sendJson(ex, 200, r.outputs());
        } else if (method.equals("GET") && route.equals("search")) {
            sendJson(ex, 200, Map.of("results", search(query(ex).getOrDefault("q", ""))));
        } else if (route.equals("clipboard") && (method.equals("GET") || method.equals("PUT"))) {
            Path clip = ws.root.resolve(".ooa").resolve("shared-clipboard.json");
            if (method.equals("PUT")) {
                Object b = body(ex);
                if (!(b instanceof Map<?, ?> m) || !(m.get("steps") instanceof List)) throw new OoaException("STUDIO.BAD_CLIPBOARD", "Send {steps: [...]}");
                Files.createDirectories(clip.getParent());
                Files.writeString(clip, json.write(Map.of("steps", m.get("steps"), "variables", m.get("variables") instanceof List ? m.get("variables") : List.of(),
                        "copied_by", System.getProperty("user.name", ""), "copied_at", java.time.Instant.now().toString()), false), StandardCharsets.UTF_8);
                sendJson(ex, 200, Map.of("ok", true));
            } else sendJson(ex, 200, Files.exists(clip) ? json.parse(Files.readString(clip)) : Map.of("steps", List.of()));
        } else if (method.equals("GET") && route.equals("builtins")) {
            Runner probe = runner(new EventSink.ListSink());
            Map<String, Object> flow = Map.of("kind", "TaskBot", "id", "probe", "name", "Studio", "variables",
                    List.of(Map.of("name", "s", "type", "Any", "direction", "output"), Map.of("name", "g", "type", "Any", "direction", "output")),
                    "steps", List.of(Map.of("id", "a", "action", "core/set", "args", Map.of("variable", "s", "value", "$sys")),
                            Map.of("id", "b", "action", "core/set", "args", Map.of("variable", "g", "value", "$global"))));
            RunResult r = probe.run(flow, Map.of());
            sendJson(ex, 200, Map.of("sys", r.ok() ? r.outputs().get("s") : Map.of(), "global", r.ok() ? r.outputs().get("g") : Map.of(),
                    "error", r.ok() ? Map.of() : r.error()));
        } else if (method.equals("POST") && route.equals("record")) {
            Map<String, Object> req = (Map<String, Object>) body(ex);
            if (registry.allManifests().stream().noneMatch(m -> "ooa.browser".equals(m.get("id"))))
                throw new OoaException("STUDIO.NO_BROWSER_MODULE", "The Browser module is not installed", "Add packages-browser to ooa.optionalModules in gradle.properties.");
            Map<String, Object> flow = new LinkedHashMap<>();
            flow.put("kind", "TaskBot"); flow.put("id", "studio_recorder"); flow.put("name", "Studio recorder");
            flow.put("variables", List.of(Map.of("name", "steps", "type", "List", "direction", "output")));
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("url", String.valueOf(req.getOrDefault("url", "about:blank")));
            args.put("browser", String.valueOf(req.getOrDefault("browser", "edge")));
            flow.put("steps", List.of(Map.of("id", "rec", "action", "ooa.browser/record", "args", args, "out", Map.of("steps", "steps"))));
            RunResult r = runner(new EventSink.ListSink()).run(flow, Map.of(), new Runner.RunOptions().maxRunSeconds(3700));
            if (!r.ok()) sendJson(ex, 400, Map.of("error", r.error()));
            else sendJson(ex, 200, Map.of("steps", r.outputs().get("steps")));
        } else if (method.equals("GET") && route.equals("triggers")) {
            sendJson(ex, 200, Map.of("triggers", triggers.list(), "log", triggers.recentLog()));
        } else if (method.equals("PUT") && route.equals("triggers")) {
            Object b = body(ex);
            if (!(b instanceof List)) throw new OoaException("TRIGGER.INVALID", "Send a list of triggers");
            triggers.save((List<Object>) b);
            sendJson(ex, 200, Map.of("triggers", triggers.list()));
        } else if (method.equals("POST") && route.matches("triggers/[A-Za-z0-9_-]{1,40}/run")) {
            RunResult r = triggers.runNow(route.split("/")[1]);
            sendJson(ex, 200, Map.of("result", r.toMap()));
        } else if (method.equals("POST") && route.matches("hooks/[A-Za-z0-9_-]{1,40}")) {
            String secret = ex.getRequestHeaders().getFirst("X-OOA-Secret");
            if (secret == null) secret = query(ex).get("secret");
            sendJson(ex, 202, triggers.webhook(route.substring(6), secret, body(ex)));
        } else send(ex, 404, "text/plain", "Unknown API route");
    }

    private FlowValidator validator() { return new FlowValidator(registry, schema); }

    /** Finds bots whose name, path, description, commands or variables match the query. */
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> search(String q) throws IOException {
        String needle = q.trim().toLowerCase(Locale.ROOT);
        List<Map<String, Object>> out = new ArrayList<>();
        if (needle.length() < 2) return out;
        Map<String, String> names = new HashMap<>();
        for (Map<String, Object> m : registry.allManifests())
            for (Object a : (List<Object>) m.getOrDefault("actions", List.of()))
                names.put(m.get("id") + "/" + ((Map<String, Object>) a).get("id"), ((Map<String, Object>) a).get("name") + " " + m.get("name"));
        try (var st = Files.walk(ws.root, 12)) {
            for (Path p : st.filter(f -> f.getFileName().toString().endsWith(Workspace.BOT_SUFFIX)).toList()) {
                String rel = ws.root.relativize(p).toString().replace('\\', '/');
                if (rel.startsWith(".") || rel.contains("/.")) continue;
                List<String> why = new ArrayList<>();
                if (rel.toLowerCase(Locale.ROOT).contains(needle)) why.add("name");
                try {
                    Map<String, Object> f = (Map<String, Object>) json.parse(Files.readString(p));
                    if (String.valueOf(f.getOrDefault("description", "")).toLowerCase(Locale.ROOT).contains(needle)) why.add("description");
                    if (String.valueOf(f.getOrDefault("id", "")).toLowerCase(Locale.ROOT).contains(needle)) why.add("bot id");
                    for (Map<String, Object> step : com.oneofficeautomation.runner.Steps.walk((List<Object>) f.getOrDefault("steps", List.of()))) {
                        String a = String.valueOf(step.get("action"));
                        String name = names.getOrDefault(a, "");
                        if ((a + " " + name + " " + step.getOrDefault("description", "")).toLowerCase(Locale.ROOT).contains(needle)) { why.add("uses " + a); break; }
                    }
                    for (Object v : (List<Object>) f.getOrDefault("variables", List.of()))
                        if (String.valueOf(((Map<String, Object>) v).get("name")).toLowerCase(Locale.ROOT).contains(needle)) { why.add("variable " + ((Map<String, Object>) v).get("name")); break; }
                } catch (RuntimeException e) { /* unreadable bot: match by name only */ }
                if (!why.isEmpty()) out.add(Map.of("path", rel, "matches", why));
                if (out.size() >= 100) break;
            }
        }
        return out;
    }

    /** One configured Runner: repository, vault and bot invoker services (agents can call bots; nesting is limited). */
    Runner runner(EventSink sink) {
        BotInvoker invoker = (automationId, inputs) -> {
            if (DEPTH.get() >= 5) throw new OoaException("AGENT.TOO_DEEP", "Bots calling agents calling bots are nested too deeply", "Simplify the agent's tools.");
            DEPTH.set(DEPTH.get() + 1);
            try { return runner(new EventSink.ListSink()).run(new FolderRepository().get(automationId, "production"), inputs, new Runner.RunOptions().maxRunSeconds(600)); }
            finally { DEPTH.set(DEPTH.get() - 1); }
        };
        return Runner.builder(registry).sink(sink).workDir(workspace).schemaValidator(schema)
                .service("repository", new FolderRepository()).service("vault", new EnvVault()).service("bot_invoker", invoker)
                .principal(System.getProperty("user.name", "developer")).build();
    }

    /** Runs a bot file; only inputs the bot declares are passed (e.g. trigger_file, payload). */
    @SuppressWarnings("unchecked")
    RunResult runFile(String botPath, Map<String, Object> inputs) {
        try {
            Map<String, Object> flow = (Map<String, Object>) json.parse(Files.readString(ws.resolve(botPath)));
            Set<String> declared = new HashSet<>();
            for (Object v : (List<Object>) flow.getOrDefault("variables", List.of()))
                if ("input".equals(((Map<String, Object>) v).get("direction"))) declared.add(String.valueOf(((Map<String, Object>) v).get("name")));
            Map<String, Object> in = new LinkedHashMap<>();
            inputs.forEach((k, v) -> { if (declared.contains(k)) in.put(k, v); });
            return runner(new EventSink.ListSink()).run(flow, in, new Runner.RunOptions().maxRunSeconds(3600));
        } catch (IOException e) { throw new OoaException("STUDIO.NOT_FOUND", "Cannot read bot " + botPath + ": " + e.getMessage()); }
    }

    /** Child bots (core/run_bot) resolve to any bot in the workspace with that automation id. */
    private final class FolderRepository implements AutomationRepository {
        @SuppressWarnings("unchecked")
        @Override public Map<String, Object> get(String automationId, Object version) {
            try (Stream<Path> s = Files.walk(workspace)) {
                for (Path p : s.filter(f -> f.toString().endsWith(Workspace.BOT_SUFFIX))
                        .filter(f -> !workspace.relativize(f).toString().startsWith(".")).toList()) {
                    Map<String, Object> f = (Map<String, Object>) json.parse(Files.readString(p));
                    if (automationId.equals(f.get("id"))) return f;
                }
            } catch (IOException e) { throw new OoaException("REPO.IO", e.getMessage()); }
            throw new OoaException("REPO.NOT_FOUND", "Automation '" + automationId + "' not found in the workspace",
                    "Save the child bot anywhere in the workspace; its bot id must match.");
        }
    }

    private Object body(HttpExchange ex) throws IOException {
        byte[] b = ex.getRequestBody().readNBytes(MAX_BODY + 1);
        if (b.length > MAX_BODY) throw new OoaException("STUDIO.TOO_LARGE", "Request body exceeds 5 MB");
        String text = new String(b, StandardCharsets.UTF_8);
        return text.isBlank() ? null : json.parse(text);
    }

    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> q = new HashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw != null) for (String kv : raw.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0) q.put(java.net.URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8),
                    java.net.URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
        }
        return q;
    }

    private void sendJson(HttpExchange ex, int code, Object body) throws IOException {
        send(ex, code, "application/json; charset=utf-8", json.write(body, false));
    }

    private static void send(HttpExchange ex, int code, String type, String body) throws IOException {
        sendBytes(ex, code, type, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void sendBytes(HttpExchange ex, int code, String type, byte[] b) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().set("Content-Security-Policy", "default-src 'self'; img-src 'self'; style-src 'self'; script-src 'self'; frame-ancestors 'none'");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }
}
