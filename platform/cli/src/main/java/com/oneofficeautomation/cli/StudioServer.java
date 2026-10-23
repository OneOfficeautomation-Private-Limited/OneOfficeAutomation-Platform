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
    private ControlRoomLink cr;
    private final Map<String, LiveRun> live = new java.util.concurrent.ConcurrentHashMap<>();

    /** A run started from Studio in the background, so the UI can follow it live and pause or stop it. */
    static final class LiveRun {
        final String id; final EventSink.ListSink sink = new EventSink.ListSink();
        final java.util.concurrent.atomic.AtomicBoolean cancel = new java.util.concurrent.atomic.AtomicBoolean();
        final java.util.concurrent.atomic.AtomicBoolean pause = new java.util.concurrent.atomic.AtomicBoolean();
        volatile RunResult result; volatile long finishedAt; final long startedAt = System.currentTimeMillis();
        volatile StudioDebugger debugger;
        LiveRun(String id) { this.id = id; }
    }
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    /** Path prefix when Studio shares a port with Control Room ("/studio"), or "" when it runs on its own port. */
    private String base = "";
    /** Control Room on the same server (`ooa server`), used to connect Studio automatically; null when standalone. */
    private String sameServerControlRoom;

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
            throw new OoaException("STUDIO.PORT_IN_USE", "Port " + port + " is already in use; another Agent Studio may still be running",
                    "Stop the other Agent Studio (Ctrl+C in its window), or start this one with --port " + (port + 1) + ".");
        }
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::handle);
        server.start();
        startServices();
        System.out.println("ONEOFFICEAUTOMATION One Agent Studio (preview) is running.");
        System.out.println("  Open:      http://localhost:" + port);
        System.out.println("  Workspace: " + workspace);
        System.out.println("  Press Ctrl+C to stop.");
    }

    /**
     * Prepares Studio to be served by Control Room's server under a prefix such as /studio (see `ooa server`). Studio still
     * answers only browsers on this machine, because it reads and writes the developer's private workspace.
     */
    com.sun.net.httpserver.HttpHandler mountedAt(String prefix, String controlRoomUrl) {
        this.base = prefix;
        this.sameServerControlRoom = controlRoomUrl;
        startServices();
        return this::handle;
    }

    Path workspaceRoot() { return workspace; }

    private void startServices() {
        cr = new ControlRoomLink(ws);
        triggers = new TriggerService(ws, (botPath, inputs) -> runFile(botPath, inputs));
        triggers.start();
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            if (!ex.getRemoteAddress().getAddress().isLoopbackAddress()) { send(ex, 403, "text/plain", "Agent Studio is only available on this computer"); return; }
            String host = Optional.ofNullable(ex.getRequestHeaders().getFirst("Host")).orElse("");
            if (!host.startsWith("localhost:") && !host.startsWith("127.0.0.1:")) { send(ex, 403, "text/plain", "Forbidden host"); return; }
            String origin = ex.getRequestHeaders().getFirst("Origin");
            if (origin != null && !origin.equals("http://localhost:" + port) && !origin.equals("http://127.0.0.1:" + port)) {
                send(ex, 403, "text/plain", "Cross-site request refused"); return;
            }
            String path = ex.getRequestURI().getPath();
            if (!base.isEmpty()) {
                if (path.equals(base)) { ex.getResponseHeaders().set("Location", base + "/"); send(ex, 302, "text/plain", "Moved"); return; }
                if (!path.startsWith(base + "/")) { send(ex, 404, "text/plain", "Not found"); return; }
                path = path.substring(base.length());
            }
            String method = ex.getRequestMethod();
            if (path.startsWith("/api/")) api(ex, method, path.substring(5));
            else asset(ex, path);
        } catch (OoaException e) {
            int status = switch (e.code()) {
                case "CR.LOCKED", "CR.NOT_CHECKED_OUT", "CR.DUPLICATE_ID", "CR.CONFLICT" -> 409;
                case "CR.FORBIDDEN", "TRIGGER.FORBIDDEN" -> 403;
                case "CR.NOT_FOUND", "STUDIO.NOT_FOUND", "CONNECTOR.NOT_FOUND" -> 404;
                case "CONNECTOR.EXISTS" -> 409;
                default -> 400;
            };
            sendJson(ex, status, Map.of("error", e.toMap()));
        } catch (RuntimeException e) {
            sendJson(ex, 500, Map.of("error", new OoaException("STUDIO.INTERNAL", String.valueOf(e)).toMap()));
        } finally {
            ex.close();
        }
    }

    private void asset(HttpExchange ex, String path) throws IOException {
        String name = switch (path) {
            case "/", "/index.html" -> "index.html";
            case "/studio.css", "/studio.js", "/logo-mark.png", "/logo-full.png", "/ooa-tokens.css", "/ooa-components.css" -> path.substring(1);
            default -> null;
        };
        if (name == null) { send(ex, 404, "text/plain", "Not found"); return; }
        // The shared design system comes from the Command Center module, so both applications use the same files.
        String folder = name.startsWith("ooa-") ? "/ooa-ui/" : "/studio/";
        try (InputStream in = StudioServer.class.getResourceAsStream(folder + name)) {
            if (in == null) { send(ex, 404, "text/plain", "Missing asset " + name); return; }
            String type = name.endsWith(".css") ? "text/css; charset=utf-8" : name.endsWith(".js") ? "text/javascript; charset=utf-8"
                    : name.endsWith(".png") ? "image/png" : "text/html; charset=utf-8";
            sendBytes(ex, 200, type, in.readAllBytes());
        }
    }

    @SuppressWarnings("unchecked")
    private void api(HttpExchange ex, String method, String route) throws IOException {
        if (method.equals("GET") && route.equals("info")) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("product", "ONEOFFICEAUTOMATION One Agent Studio");
            info.put("edition", "preview");
            info.put("workspace", workspace.toString());
            info.put("controlRoom", sameServerControlRoom == null ? null : "/");
            sendJson(ex, 200, info);
        } else if (method.equals("GET") && route.equals("packages")) {
            sendJson(ex, 200, Map.of("packages", registry.allManifests()));
        } else if (method.equals("GET") && route.equals("tree")) {
            sendJson(ex, 200, Map.of("workspace", workspace.toString(), "tree", ws.tree()));
        } else if (method.equals("GET") && route.equals("agents")) {
            // every agent in the workspace with its id and variables, for Run Agent's agent choice and variable mapping
            List<Object> agents = new ArrayList<>();
            try (Stream<Path> s = Files.walk(workspace)) {
                for (Path p : s.filter(f -> f.toString().endsWith(Workspace.BOT_SUFFIX))
                        .filter(f -> !workspace.relativize(f).toString().startsWith(".")).sorted().toList()) {
                    try {
                        Map<String, Object> f = (Map<String, Object>) json.parse(Files.readString(p));
                        Map<String, Object> a = new LinkedHashMap<>();
                        a.put("path", workspace.relativize(p).toString().replace('\\', '/'));
                        a.put("id", f.getOrDefault("id", ""));
                        a.put("name", f.getOrDefault("name", ""));
                        a.put("variables", f.getOrDefault("variables", List.of()));
                        agents.add(a);
                    } catch (RuntimeException | IOException ignored) { /* not a readable agent */ }
                }
            }
            sendJson(ex, 200, Map.of("agents", agents));
        } else if (route.equals("bot")) {
            String rel = query(ex).getOrDefault("path", "");
            if (!rel.endsWith(Workspace.BOT_SUFFIX)) throw new OoaException("STUDIO.NOT_A_BOT", "Not an agent file: " + rel, "Agents end with .flow.json.");
            Path p = ws.resolve(rel);
            if (method.equals("GET")) {
                if (!Files.isRegularFile(p)) throw new OoaException("STUDIO.NOT_FOUND", "Agent not found: " + rel);
                sendJson(ex, 200, Map.of("path", rel, "flow", json.parse(Files.readString(p))));
            } else if (method.equals("PUT")) {
                if (!Files.isDirectory(p.getParent())) throw new OoaException("STUDIO.NOT_FOUND", "Folder not found for " + rel);
                Map<String, Object> flow = (Map<String, Object>) body(ex);
                Path tmp = p.resolveSibling("." + p.getFileName() + ".tmp");
                Files.writeString(tmp, json.write(flow, true));
                Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
                sendJson(ex, 200, Map.of("saved", rel, "problems", validator().validate(flow)));
            } else send(ex, 405, "text/plain", "Method not allowed");
        } else if (method.equals("POST") && route.equals("files/upload")) {
            // Raw bytes, so uploads are not limited by the JSON body size: POST files/upload?parent=<folder>&name=<file name>
            Map<String, String> q = query(ex);
            byte[] data = ex.getRequestBody().readNBytes(MAX_UPLOAD + 1);
            if (data.length > MAX_UPLOAD) throw new OoaException("STUDIO.TOO_LARGE", "Uploads are limited to " + (MAX_UPLOAD / 1024 / 1024) + " MB per file");
            sendJson(ex, 200, Map.of("path", ws.upload(q.getOrDefault("parent", ""), q.getOrDefault("name", ""), data)));
        } else if (method.equals("GET") && route.equals("files/download")) {
            download(ex, query(ex).getOrDefault("path", ""));
        } else if (method.equals("POST") && route.startsWith("files/")) {
            Map<String, Object> b = (Map<String, Object>) body(ex);
            String path = String.valueOf(b.getOrDefault("path", ""));
            String result = switch (route.substring(6)) {
                case "copy" -> ws.copy(path, String.valueOf(b.getOrDefault("to", "")));
                case "duplicate" -> renameDuplicatedAgent(ws.duplicate(path));
                case "reveal" -> reveal(path);
                case "folder" -> ws.createFolder(String.valueOf(b.getOrDefault("parent", "")), String.valueOf(b.get("name")));
                case "bot" -> ws.createBot(String.valueOf(b.getOrDefault("parent", "")), String.valueOf(b.get("name")),
                        String.valueOf(b.getOrDefault("kind", "TaskBot")), v -> json.write(v, true));
                case "item" -> ws.createItem(String.valueOf(b.getOrDefault("parent", "")), String.valueOf(b.get("name")), String.valueOf(b.get("type")), v -> json.write(v, true));
                case "rename" -> ws.rename(path, String.valueOf(b.get("name")));
                case "move" -> ws.move(path, String.valueOf(b.getOrDefault("to", "")));
                case "delete" -> ws.delete(path);
                default -> throw new OoaException("STUDIO.UNKNOWN", "Unknown file operation " + route);
            };
            sendJson(ex, 200, Map.of("path", result));
        } else if (route.equals("item")) {
            String rel = query(ex).getOrDefault("path", "");
            if (method.equals("GET")) {
                Path p = ws.resolve(rel);
                sendJson(ex, 200, Map.of("path", rel, "type", ws.typeOf(p), "text", ws.readItem(rel)));
            } else if (method.equals("PUT")) {
                Object b = body(ex);
                String text = b instanceof Map<?, ?> m && m.get("text") instanceof String t ? t : null;
                sendJson(ex, 200, Map.of("saved", ws.writeItem(rel, text, json::parse)));
            } else send(ex, 405, "text/plain", "Method not allowed");
        } else if (route.equals("connections") && method.equals("GET")) {
            Path f = ws.root.resolve("model-connections.json");
            sendJson(ex, 200, Files.isRegularFile(f) ? json.parse(Files.readString(f)) : Map.of("default", "", "connections", List.of()));
        } else if (route.equals("connections") && method.equals("PUT")) {
            sendJson(ex, 200, saveConnections(body(ex)));
        } else if (route.equals("connectors") || route.startsWith("connectors/")) {
            connectors(ex, method, route.substring("connectors".length()));
        } else if (method.equals("POST") && route.equals("assistant/build")) {
            sendJson(ex, 200, assistantBuild((Map<String, Object>) body(ex)));
        } else if (method.equals("GET") && route.equals("deps")) {
            sendJson(ex, 200, dependencies(query(ex).getOrDefault("path", "")));
        } else if (method.equals("POST") && route.equals("validate")) {
            sendJson(ex, 200, Map.of("problems", validator().validate((Map<String, Object>) body(ex))));
        } else if (method.equals("POST") && route.equals("run")) {
            Map<String, Object> req = (Map<String, Object>) body(ex);
            EventSink.ListSink sink = new EventSink.ListSink();
            Runner runner = runner(sink);
            Map<String, Object> inputs = req.get("inputs") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
            RunResult r = runner.run((Map<String, Object>) req.get("flow"), inputs, new Runner.RunOptions().maxRunSeconds(300));
            sendJson(ex, 200, Map.of("result", r.toMap(), "events", sink.events().stream().map(RunEvent::toMap).toList()));
        } else if (route.startsWith("cr/")) {
            String r = route.substring(3);
            Map<String, String> q = query(ex);
            if (method.equals("GET") && r.equals("status")) {
                // Same server as Control Room: reuse the browser's Control Room sign-in instead of asking again.
                if (sameServerControlRoom != null && !cr.connected()) {
                    String token = sessionCookie(ex);
                    if (token != null) try { cr.adopt(sameServerControlRoom, token); } catch (OoaException ignored) { /* signed out, or a password change is pending */ }
                }
                Map<String, Object> st = new LinkedHashMap<>(cr.status());
                st.put("sameServer", sameServerControlRoom != null);
                sendJson(ex, 200, st);
            }
            else if (method.equals("POST") && r.equals("connect")) { Map<String, Object> b = (Map<String, Object>) body(ex); sendJson(ex, 200, cr.connect((String) b.get("url"), (String) b.get("username"), (String) b.get("password"))); }
            else if (method.equals("POST") && r.equals("disconnect")) { cr.disconnect(); sendJson(ex, 200, cr.status()); }
            else if (method.equals("GET") && r.equals("repo")) { Map<String, Object> out = new LinkedHashMap<>(cr.repo(q.get("q"))); out.put("mapping", cr.mapping()); sendJson(ex, 200, out); }
            else if (method.equals("GET") && r.equals("versions")) sendJson(ex, 200, cr.versions(q.get("path")));
            else if (method.equals("POST") && r.equals("checkout")) sendJson(ex, 200, cr.checkout((String) ((Map<String, Object>) body(ex)).get("path"), json));
            else if (method.equals("POST") && r.equals("checkin")) { Map<String, Object> b = (Map<String, Object>) body(ex); sendJson(ex, 200, cr.checkin((String) b.get("local"), (String) b.get("path"), (String) b.get("comment"), json)); }
            else if (method.equals("POST") && r.equals("undo-checkout")) { cr.undoCheckout((String) ((Map<String, Object>) body(ex)).get("path")); sendJson(ex, 200, Map.of("ok", true)); }
            else send(ex, 404, "text/plain", "Unknown API route");
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
            Runner.RunOptions opts = new Runner.RunOptions().runId(id).cancel(lr.cancel).pause(lr.pause).maxRunSeconds(3600);
            if (Boolean.TRUE.equals(req.get("debug"))) {
                List<String> bps = new ArrayList<>();
                if (req.get("breakpoints") instanceof List<?> l) for (Object o : l) bps.add(String.valueOf(o));
                lr.debugger = new StudioDebugger(bps, Boolean.TRUE.equals(req.get("stopAtStart")), lr.cancel);
                opts.debugger(lr.debugger).maxRunSeconds(86400);
            }
            // Step menu: "Run this step only" (only_step) and "Run from here" (start_at); see Runner.RunOptions.
            if (req.get("only_step") instanceof String only && !only.isBlank()) opts.onlyStep(only);
            else if (req.get("start_at") instanceof String from && !from.isBlank()) opts.startAt(from);
            live.put(id, lr);
            Thread t = new Thread(() -> {
                try { lr.result = runner(lr.sink).run(flow, inputs, opts); }
                catch (RuntimeException e) { lr.result = new RunResult(id, null, "failed", Map.of(), Map.of("code", "STUDIO.RUN_CRASHED", "message", String.valueOf(e.getMessage())), System.currentTimeMillis() - lr.startedAt, null, 0); }
                finally { lr.finishedAt = System.currentTimeMillis(); }
            }, "ooa-studio-run-" + id);
            t.setDaemon(true);
            t.start();
            // the run window on the desktop, on top of the applications the agent works in
            if (Boolean.TRUE.equals(req.get("run_window")) && lr.debugger == null && RunWindow.available()) {
                Map<String, String> names = new HashMap<>();
                for (Map<String, Object> m : registry.allManifests())
                    if (m.get("actions") instanceof List<?> acts) for (Object a : acts)
                        if (a instanceof Map<?, ?> am) names.put(m.get("id") + "/" + am.get("id"), String.valueOf(am.get("name")));
                RunWindow.open(String.valueOf(flow.getOrDefault("name", "Agent")), flow, lr.sink, lr.pause, lr.cancel, () -> lr.result != null,
                        a -> names.getOrDefault(a, a));
            }
            sendJson(ex, 202, Map.of("run_id", id));
        } else if (method.equals("GET") && route.matches("runs/run_[a-f0-9]{16}")) {
            LiveRun lr = live.get(route.substring(5));
            if (lr == null) throw new OoaException("STUDIO.NOT_FOUND", "Unknown or expired run");
            int after = Integer.parseInt(query(ex).getOrDefault("after", "0"));
            List<Map<String, Object>> evs = new ArrayList<>();
            for (RunEvent e : lr.sink.events()) if (e.seq() > after) evs.add(e.toMap());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("run_id", lr.id);
            Map<String, Object> dbg = lr.result == null && lr.debugger != null ? lr.debugger.state() : null;
            out.put("state", lr.result != null ? lr.result.status() : dbg != null ? "debugging" : lr.pause.get() ? "paused" : "running");
            if (dbg != null) out.put("debug", dbg);
            out.put("elapsed_ms", (lr.finishedAt > 0 ? lr.finishedAt : System.currentTimeMillis()) - lr.startedAt);
            out.put("events", evs);
            if (lr.result != null) out.put("result", lr.result.toMap());
            sendJson(ex, 200, out);
        } else if (method.equals("POST") && route.matches("runs/run_[a-f0-9]{16}/(pause|resume|stop)")) {
            String[] parts = route.split("/");
            LiveRun lr = live.get(parts[1]);
            if (lr == null) throw new OoaException("STUDIO.NOT_FOUND", "Unknown or expired run");
            switch (parts[2]) {
                case "pause" -> lr.pause.set(true);
                case "resume" -> lr.pause.set(false);
                default -> { lr.cancel.set(true); lr.pause.set(false); if (lr.debugger != null) lr.debugger.release(); }
            }
            sendJson(ex, 200, Map.of("ok", true));
        } else if (method.equals("POST") && route.matches("runs/run_[a-f0-9]{16}/(debug|breakpoints|variable)")) {
            String[] parts = route.split("/");
            LiveRun lr = live.get(parts[1]);
            if (lr == null || lr.debugger == null) throw new OoaException("STUDIO.NOT_FOUND", "Unknown run, or the run is not being debugged");
            Map<String, Object> b = (Map<String, Object>) body(ex);
            switch (parts[2]) {
                case "debug" -> lr.debugger.command(String.valueOf(b.get("command")));
                case "breakpoints" -> lr.debugger.breakpoints(b.get("ids") instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of());
                default -> lr.debugger.setVariable(String.valueOf(b.get("name")), b.get("value"));
            }
            sendJson(ex, 200, Map.of("ok", true));
        } else if (method.equals("POST") && route.equals("capture")) {
            Map<String, Object> req = (Map<String, Object>) body(ex);
            if (registry.allManifests().stream().noneMatch(m -> "ooa.browser".equals(m.get("id"))))
                throw new OoaException("STUDIO.NO_BROWSER_MODULE", "The Browser module is not installed", "Add packages-browser to ooa.optionalModules in gradle.properties.");
            Map<String, Object> flow = new LinkedHashMap<>();
            flow.put("kind", "TaskBot"); flow.put("id", "studio_capture"); flow.put("name", "Agent Studio capture");
            flow.put("variables", List.of(Map.of("name", "selector", "type", "String", "direction", "output"), Map.of("name", "element", "type", "Dictionary", "direction", "output"),
                    Map.of("name", "object", "type", "Dictionary", "direction", "output"), Map.of("name", "alternatives", "type", "Dictionary", "direction", "output")));
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("url", String.valueOf(req.getOrDefault("url", "about:blank")));
            args.put("browser", String.valueOf(req.getOrDefault("browser", "edge")));
            // pick in a window that is already open (a browser with a debugging port), instead of opening a new one
            for (String k : List.of("endpoint", "tab_title", "tab_url")) if (req.get(k) != null && !String.valueOf(req.get(k)).isBlank()) args.put(k, String.valueOf(req.get(k)));
            if (req.get("tab_id") instanceof Number n) args.put("tab_id", n);
            flow.put("steps", List.of(Map.of("id", "pick", "action", "ooa.browser/pick_element", "args", args,
                    "out", Map.of("selector", "selector", "element", "element", "object", "object", "alternatives", "alternatives"))));
            RunResult r = runner(new EventSink.ListSink()).run(flow, Map.of(), new Runner.RunOptions().maxRunSeconds(900));
            if (!r.ok()) sendJson(ex, 400, Map.of("error", r.error()));
            else sendJson(ex, 200, r.outputs());
        } else if (method.equals("POST") && route.equals("capture/test")) {
            // Design time: open the page, outline every element the captured object matches, and report how many match.
            Map<String, Object> req = (Map<String, Object>) body(ex);
            if (registry.allManifests().stream().noneMatch(m -> "ooa.browser".equals(m.get("id"))))
                throw new OoaException("STUDIO.NO_BROWSER_MODULE", "The Browser module is not installed", "Add packages-browser to ooa.optionalModules in gradle.properties.");
            Map<String, Object> flow = new LinkedHashMap<>();
            flow.put("kind", "TaskBot"); flow.put("id", "studio_capture_test"); flow.put("name", "Agent Studio capture test");
            flow.put("variables", List.of(Map.of("name", "count", "type", "Number", "direction", "output"), Map.of("name", "first", "type", "Dictionary", "direction", "output")));
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("url", String.valueOf(req.getOrDefault("url", "about:blank")));
            args.put("object", Map.of("literal", req.get("object")));
            args.put("browser", String.valueOf(req.getOrDefault("browser", "edge")));
            flow.put("steps", List.of(Map.of("id", "test", "action", "ooa.browser/test_object", "args", args, "out", Map.of("count", "count", "first", "first"))));
            RunResult r = runner(new EventSink.ListSink()).run(flow, Map.of(), new Runner.RunOptions().maxRunSeconds(300));
            if (!r.ok()) sendJson(ex, 400, Map.of("error", r.error()));
            else sendJson(ex, 200, r.outputs());
        } else if (method.equals("POST") && route.equals("capture-desktop")) {
            captureDesktop(ex);
        } else if (method.equals("GET") && route.equals("windows")) {
            listWindows(ex);
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
            Map<String, Object> flow = Map.of("kind", "TaskBot", "id", "probe", "name", "Agent Studio", "variables",
                    List.of(Map.of("name", "s", "type", "Any", "direction", "output"), Map.of("name", "g", "type", "Any", "direction", "output")),
                    "steps", List.of(Map.of("id", "a", "action", "core/set", "args", Map.of("variable", "s", "value", "$sys")),
                            Map.of("id", "b", "action", "core/set", "args", Map.of("variable", "g", "value", "$global"))));
            RunResult r = probe.run(flow, Map.of());
            sendJson(ex, 200, Map.of("sys", r.ok() ? r.outputs().get("s") : Map.of(), "global", r.ok() ? r.outputs().get("g") : Map.of(),
                    "error", r.ok() ? Map.of() : r.error()));
        } else if (method.equals("POST") && route.equals("record")) {
            Map<String, Object> req = (Map<String, Object>) body(ex);
            boolean desktop = "desktop".equals(req.get("target"));
            String module = desktop ? "ooa.desktop_ui" : "ooa.browser";
            if (registry.allManifests().stream().noneMatch(m -> module.equals(m.get("id"))))
                throw desktop ? new OoaException("STUDIO.NO_DESKTOP_MODULE", "The Windows desktop module is not installed", "Add packages-windows to ooa.optionalModules in gradle.properties.")
                        : new OoaException("STUDIO.NO_BROWSER_MODULE", "The Browser module is not installed", "Add packages-browser to ooa.optionalModules in gradle.properties.");
            Map<String, Object> flow = new LinkedHashMap<>();
            flow.put("kind", "TaskBot"); flow.put("id", "studio_recorder"); flow.put("name", "Agent Studio recorder");
            flow.put("variables", List.of(Map.of("name", "steps", "type", "List", "direction", "output")));
            Map<String, Object> args = new LinkedHashMap<>();
            if (!desktop) {
                args.put("url", String.valueOf(req.getOrDefault("url", "about:blank")));
                args.put("browser", String.valueOf(req.getOrDefault("browser", "edge")));
                // record in an open tab of a browser connected through the extension
                if (req.get("tab_id") instanceof Number n) args.put("tab_id", n);
            }
            flow.put("steps", List.of(Map.of("id", "rec", "action", module + "/record", "args", args, "out", Map.of("steps", "steps"))));
            // the recording's progress (GET record/progress) and Cancel (POST record/cancel) while this request waits
            EventSink.ListSink sink = new EventSink.ListSink();
            java.util.concurrent.atomic.AtomicBoolean cancel = new java.util.concurrent.atomic.AtomicBoolean();
            recording = new Recording(sink, cancel);
            try {
                RunResult r = runner(sink).run(flow, Map.of(), new Runner.RunOptions().maxRunSeconds(3700).cancel(cancel));
                if (cancel.get()) sendJson(ex, 200, Map.of("steps", List.of(), "cancelled", true));
                else if (!r.ok()) sendJson(ex, 400, Map.of("error", r.error()));
                else sendJson(ex, 200, Map.of("steps", r.outputs().get("steps")));
            } finally { recording = null; }
        } else if (method.equals("GET") && route.equals("record/progress")) {
            Recording rec = recording;
            long n = rec == null ? 0 : rec.sink().events().stream().filter(e -> String.valueOf(e.data()).contains("Recorded step")).count();
            sendJson(ex, 200, Map.of("recording", rec != null, "count", n));
        } else if (method.equals("POST") && route.equals("record/cancel")) {
            Recording rec = recording;
            if (rec != null) rec.cancel().set(true);
            sendJson(ex, 200, Map.of("ok", true));
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

    /**
     * POST capture-desktop {timeout_seconds?: 60, screenshot?: true, highlight?: true}: runs ooa.desktop_ui/pick_object, where a
     * person points at a control in any Windows application and presses Ctrl+Shift+C. 200 {object: {...captured object...}};
     * 400 {error: {code, message, hint}} on timeout or failure.
     */
    @SuppressWarnings("unchecked")
    private void captureDesktop(HttpExchange ex) throws IOException {
        Map<String, Object> req = body(ex) instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        if (registry.allManifests().stream().noneMatch(man -> "ooa.desktop_ui".equals(man.get("id"))))
            throw new OoaException("STUDIO.NO_DESKTOP_MODULE", "The Desktop objects toolkit is not installed", "Add packages-windows to ooa.optionalModules in gradle.properties and run Studio on Windows.");
        Map<String, Object> args = new LinkedHashMap<>();
        for (String k : List.of("timeout_seconds", "screenshot", "highlight")) if (req.get(k) != null) args.put(k, req.get(k));
        Map<String, Object> flow = new LinkedHashMap<>();
        flow.put("kind", "TaskBot"); flow.put("id", "studio_capture_desktop"); flow.put("name", "Agent Studio desktop capture");
        flow.put("variables", List.of(Map.of("name", "object", "type", "Dictionary", "direction", "output")));
        flow.put("steps", List.of(Map.of("id", "pick", "action", "ooa.desktop_ui/pick_object", "args", args, "out", Map.of("object", "object"))));
        RunResult r = runner(new EventSink.ListSink()).run(flow, Map.of(), new Runner.RunOptions().maxRunSeconds(900));
        if (!r.ok()) sendJson(ex, 400, Map.of("error", r.error()));
        else sendJson(ex, 200, Map.of("object", r.outputs().get("object")));
    }

    /**
     * GET windows[?endpoints=http://127.0.0.1:9222,9333][&desktop=false]: runs ooa.browser/list_windows for the window choice of
     * web actions. 200 {windows: [{source: "browser" | "application", title, url, browser}]}; browser tabs come from browsers with a
     * debugging port, desktop titles only on Windows.
     */
    private void listWindows(HttpExchange ex) throws IOException {
        if (registry.allManifests().stream().noneMatch(m -> "ooa.browser".equals(m.get("id"))))
            throw new OoaException("STUDIO.NO_BROWSER_MODULE", "The Browser module is not installed", "Add packages-browser to ooa.optionalModules in gradle.properties.");
        Map<String, String> q = query(ex);
        Map<String, Object> args = new LinkedHashMap<>();
        String eps = q.getOrDefault("endpoints", "");
        if (!eps.isBlank()) args.put("endpoints", Arrays.stream(eps.split(",")).map(String::trim).filter(e -> !e.isEmpty()).toList());
        args.put("include_desktop", !"false".equalsIgnoreCase(q.getOrDefault("desktop", "true")));
        Map<String, Object> flow = new LinkedHashMap<>();
        flow.put("kind", "TaskBot"); flow.put("id", "studio_list_windows"); flow.put("name", "Agent Studio window list");
        flow.put("variables", List.of(Map.of("name", "windows", "type", "List", "direction", "output")));
        flow.put("steps", List.of(Map.of("id", "list", "action", "ooa.browser/list_windows", "args", args, "out", Map.of("windows", "windows"))));
        RunResult r = runner(new EventSink.ListSink()).run(flow, Map.of(), new Runner.RunOptions().maxRunSeconds(60));
        if (!r.ok()) sendJson(ex, 400, Map.of("error", r.error()));
        else sendJson(ex, 200, Map.of("windows", r.outputs().get("windows") instanceof List<?> l ? l : List.of()));
    }

    private FlowValidator validator() { return new FlowValidator(registry, schema); }

    /** The recording in progress, for its live count and Cancel. */
    record Recording(EventSink.ListSink sink, java.util.concurrent.atomic.AtomicBoolean cancel) {}
    private volatile Recording recording;

    private static final int MAX_UPLOAD = 50 * 1024 * 1024;
    private static final long MAX_DOWNLOAD = 200L * 1024 * 1024;

    /** A duplicated agent gets its own agent id (from its new name), so it does not clash with the original at check-in. */
    @SuppressWarnings("unchecked")
    private String renameDuplicatedAgent(String rel) throws IOException {
        if (!rel.endsWith(Workspace.BOT_SUFFIX)) return rel;
        Path p = ws.resolve(rel);
        try {
            Map<String, Object> flow = (Map<String, Object>) json.parse(Files.readString(p));
            String name = p.getFileName().toString();
            name = name.substring(0, name.length() - Workspace.BOT_SUFFIX.length());
            flow.put("id", name.trim().replaceAll("[^A-Za-z0-9_.-]+", "_"));
            flow.put("name", name);
            Files.writeString(p, json.write(flow, true));
        } catch (RuntimeException unreadable) { /* not a valid agent file: keep the plain copy */ }
        return rel;
    }

    /** GET files/download?path=: a file as it is, or a folder as a .zip (hidden entries left out). */
    private void download(HttpExchange ex, String rel) throws IOException {
        Path p = ws.resolve(rel);
        if (!Files.exists(p) || Files.isSymbolicLink(p)) throw new OoaException("STUDIO.NOT_FOUND", "Not found: " + rel);
        byte[] bytes;
        String name = p.equals(ws.root) ? "workspace" : p.getFileName().toString();
        if (Files.isDirectory(p)) {
            var buf = new java.io.ByteArrayOutputStream();
            long total = 0;
            try (var zip = new java.util.zip.ZipOutputStream(buf); Stream<Path> s = Files.walk(p)) {
                for (Path x : s.filter(Files::isRegularFile).toList()) {
                    Path r = p.relativize(x);
                    boolean hidden = false;
                    for (Path part : r) if (part.toString().startsWith(".")) hidden = true;
                    if (hidden || Files.isSymbolicLink(x)) continue;
                    total += Files.size(x);
                    if (total > MAX_DOWNLOAD) throw new OoaException("STUDIO.TOO_LARGE", "The folder is larger than 200 MB", "Download smaller folders, or copy it in File Explorer.");
                    zip.putNextEntry(new java.util.zip.ZipEntry(name + "/" + r.toString().replace('\\', '/')));
                    Files.copy(x, zip);
                    zip.closeEntry();
                }
            }
            bytes = buf.toByteArray();
            name = name + ".zip";
        } else {
            if (Files.size(p) > MAX_DOWNLOAD) throw new OoaException("STUDIO.TOO_LARGE", "The file is larger than 200 MB", "Copy it in File Explorer instead.");
            bytes = Files.readAllBytes(p);
        }
        ex.getResponseHeaders().set("Content-Disposition", "attachment; filename*=UTF-8''" + java.net.URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20"));
        sendBytes(ex, 200, "application/octet-stream", bytes);
    }

    /** Opens File Explorer at a workspace item (Windows). Started with an argument list, never through a shell. */
    private String reveal(String rel) throws IOException {
        Path p = ws.resolve(rel);
        if (!Files.exists(p)) throw new OoaException("STUDIO.NOT_FOUND", "Not found: " + rel);
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"))
            throw new OoaException("STUDIO.UNSUPPORTED", "Open in File Explorer works on Windows only", "Open the workspace folder yourself: " + ws.root);
        List<String> cmd = Files.isDirectory(p) ? List.of("explorer.exe", p.toString()) : List.of("explorer.exe", "/select," + p);
        new ProcessBuilder(cmd).start();
        return rel;
    }

    /**
     * Custom connectors in {workspace}/connectors: GET connectors (list), POST connectors/import {text, name?, server?, overwrite?},
     * GET connectors/{name} (definition) and DELETE connectors/{name}. Specs are OpenAPI 3.x, Swagger 2.0 or Postman v2.1.
     */
    @SuppressWarnings("unchecked")
    private void connectors(HttpExchange ex, String method, String rest) throws IOException {
        String name = rest.startsWith("/") ? java.net.URLDecoder.decode(rest.substring(1), StandardCharsets.UTF_8) : "";
        if (name.isEmpty() && method.equals("GET")) {
            sendJson(ex, 200, Map.of("connectors", com.oneofficeautomation.packages.ConnectorStore.list(workspace)));
        } else if (name.equals("import") && method.equals("POST")) {
            Object b = body(ex);
            if (!(b instanceof Map<?, ?> m) || !(m.get("text") instanceof String text))
                throw new OoaException("CONNECTOR.EMPTY_SPEC", "Send {text: <file content>, name?, server?}");
            Map<String, Object> def = com.oneofficeautomation.packages.ConnectorImporter.importSpec(text,
                    m.get("name") instanceof String n ? n : null, m.get("server") instanceof String sv ? sv : null);
            String path = com.oneofficeautomation.packages.ConnectorStore.save(workspace, def, !Boolean.FALSE.equals(m.get("overwrite")));
            sendJson(ex, 200, Map.of("path", path, "connector", def));
        } else if (!name.isEmpty() && method.equals("GET")) {
            sendJson(ex, 200, Map.of("connector", com.oneofficeautomation.packages.ConnectorStore.load(workspace, name)));
        } else if (!name.isEmpty() && method.equals("DELETE")) {
            if (!com.oneofficeautomation.packages.ConnectorStore.delete(workspace, name))
                throw new OoaException("CONNECTOR.NOT_FOUND", "There is no connector named '" + name + "'");
            sendJson(ex, 200, Map.of("deleted", name));
        } else send(ex, 405, "text/plain", "Method not allowed");
    }

    private static final Set<String> PROVIDERS = Set.of("openai", "azure_openai", "anthropic", "openai_compatible", "huggingface", "aws_bedrock_sagemaker", "aws_bedrock", "google_vertex");

    /** Saves model-connections.json. API keys are only references (vault credential or environment variable), never values. */
    @SuppressWarnings("unchecked")
    Map<String, Object> saveConnections(Object body) throws IOException {
        if (!(body instanceof Map<?, ?> m) || !(m.get("connections") instanceof List<?> list))
            throw new OoaException("AI.BAD_CONNECTIONS", "Send {default, connections: [...]}");
        Set<String> names = new HashSet<>();
        List<Object> clean = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> c)) throw new OoaException("AI.BAD_CONNECTIONS", "Each connection is an object");
            String name = String.valueOf(c.get("name") == null ? "" : c.get("name")).trim();
            if (!name.matches("^[A-Za-z0-9_.-]{1,60}$") || !names.add(name))
                throw new OoaException("AI.BAD_CONNECTIONS", "Connection names are unique and use letters, digits, . _ - (got '" + name + "')");
            if (!PROVIDERS.contains(String.valueOf(c.get("provider")))) throw new OoaException("AI.BAD_PROVIDER", "Unknown provider for '" + name + "'", "Use " + PROVIDERS + ".");
            if (c.get("api_key") instanceof String) throw new OoaException("AI.KEY_IN_FILE", "API keys are never stored in connections",
                    "Use api_key_env (an environment variable name) or a vault credential reference.");
            Map<String, Object> keep = new LinkedHashMap<>();
            for (String k : List.of("name", "provider", "model", "base_url", "api_key_env", "api_key", "description"))
                if (c.get(k) != null && !"".equals(c.get(k))) keep.put(k, k.equals("name") ? name : c.get(k));
            if (keep.get("api_key") != null && !(keep.get("api_key") instanceof Map<?, ?> km && km.get("$credential") instanceof String))
                throw new OoaException("AI.BAD_CONNECTIONS", "api_key must be {\"$credential\": \"vault folder/credential/attribute\"}");
            clean.add(keep);
        }
        String def = m.get("default") instanceof String d && names.contains(d) ? d : names.isEmpty() ? "" : names.iterator().next();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("default", def);
        out.put("connections", clean);
        Path f = ws.root.resolve("model-connections.json");
        Path tmp = f.resolveSibling(".model-connections.json.saving");
        Files.writeString(tmp, json.write(out, true), StandardCharsets.UTF_8);
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
        return out;
    }

    /**
     * The AI assistant: turns a description into agent steps using a One Model Connection. The model gets the action
     * catalog (ids, names, required inputs) and the agent's variables, answers with JSON, and the result is validated
     * before Agent Studio shows it for review.
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> assistantBuild(Map<String, Object> req) {
        String prompt = String.valueOf(req.getOrDefault("prompt", "")).trim();
        if (prompt.isEmpty()) throw new OoaException("ASSISTANT.EMPTY", "Describe what the agent should do");
        String connection = String.valueOf(req.getOrDefault("connection", "default"));
        StringBuilder catalog = new StringBuilder();
        for (Map<String, Object> m : registry.allManifests())
            for (Object ao : (List<Object>) m.getOrDefault("actions", List.of())) {
                Map<String, Object> a = (Map<String, Object>) ao;
                Map<String, Object> in = (Map<String, Object>) a.getOrDefault("inputs", Map.of());
                Map<String, Object> props = (Map<String, Object>) in.getOrDefault("properties", Map.of());
                List<Object> requiredIn = (List<Object>) in.getOrDefault("required", List.of());
                catalog.append(m.get("id")).append('/').append(a.get("id")).append(" | ").append(a.get("name"))
                        .append(" | inputs: ").append(String.join(", ", props.keySet().stream().map(k -> requiredIn.contains(k) ? k + "*" : k).toList()))
                        .append(" | outputs: ").append(String.join(", ", ((Map<String, Object>) a.getOrDefault("outputs", Map.of())).keySet())).append('\n');
            }
        Map<String, Object> flow = req.get("flow") instanceof Map<?, ?> f ? (Map<String, Object>) f : Map.of();
        String system = "You design steps for ONEOFFICEAUTOMATION agents. Answer with one JSON object only: "
                + "{\"variables\": [{\"name\", \"type\", \"direction\"?: \"input\"|\"output\", \"default\"?}], \"steps\": [step...], \"notes\": \"short explanation\"}. "
                + "A step is {\"id\": \"a1\", \"action\": \"package/action\", \"args\": {input: value}, \"out\": {output: variableName}}. "
                + "Containers: core/if has \"args\": {\"condition\": {\"op\": \"eq|ne|gt|ge|lt|le|contains|empty|notEmpty\", \"left\", \"right\"}}, \"children\" and \"else\"; "
                + "core/loop has \"args\": {\"iterator\": \"list|times|table_rows|dictionary|while\", \"over\" or \"count\", \"as\"} and \"children\"; "
                + "core/try has \"children\", \"catch\": [{\"codes\": [], \"as\": \"error\", \"children\": []}], \"finally\": []. "
                + "Reference variables as $name, or inside text as ${name}. Declare every variable you use. Types: String, Number, Boolean, DateTime, List, "
                + "Dictionary, Table, Record, File, Credential, Any. Secrets are Credential inputs. Use only actions from this catalog "
                + "(* = required input):\n" + catalog;
        String user = "Existing variables: " + json.write(flow.getOrDefault("variables", List.of()), false) + "\nRequest: " + prompt;
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("kind", "TaskBot"); run.put("id", "studio_assistant"); run.put("name", "Agent Studio assistant");
        // The texts go in as inputs, so their $name and ${...} examples are never read as variable references.
        run.put("variables", List.of(Map.of("name", "answer", "type", "String", "direction", "output"),
                Map.of("name", "request", "type", "String", "direction", "input"), Map.of("name", "instructions", "type", "String", "direction", "input")));
        run.put("steps", List.of(Map.of("id", "ask", "action", "ooa.genai/prompt", "out", Map.of("text", "answer"),
                "args", Map.of("prompt", "$request", "system", "$instructions", "connection", connection, "temperature", java.math.BigDecimal.ZERO, "max_tokens", java.math.BigDecimal.valueOf(4000), "mask_pii", false))));
        RunResult r = runner(new EventSink.ListSink()).run(run, Map.of("request", user, "instructions", system), new Runner.RunOptions().maxRunSeconds(300));
        if (!r.ok()) throw new OoaException(String.valueOf(r.error().getOrDefault("code", "ASSISTANT.FAILED")), "The assistant could not answer: " + r.error().get("message"),
                (String) r.error().get("suggestedFix"));
        String text = String.valueOf(r.outputs().get("answer"));
        int a = text.indexOf('{'), b = text.lastIndexOf('}');
        if (a < 0 || b <= a) throw new OoaException("ASSISTANT.NOT_JSON", "The model did not answer with steps", "Rephrase the request and try again.");
        Map<String, Object> answer;
        try { answer = (Map<String, Object>) json.parse(text.substring(a, b + 1)); }
        catch (RuntimeException e) { throw new OoaException("ASSISTANT.NOT_JSON", "The model's answer was not valid JSON", "Try again; smaller requests work better."); }
        List<Object> vars = new ArrayList<>((List<Object>) flow.getOrDefault("variables", List.of()));
        Set<String> have = new HashSet<>();
        for (Object v : vars) have.add(String.valueOf(((Map<String, Object>) v).get("name")));
        for (Object v : (List<Object>) answer.getOrDefault("variables", List.of()))
            if (v instanceof Map<?, ?> vm && have.add(String.valueOf(vm.get("name")))) vars.add(v);
        Map<String, Object> check = new LinkedHashMap<>(flow);
        check.putIfAbsent("kind", "TaskBot"); check.putIfAbsent("id", "check"); check.putIfAbsent("name", "check");
        check.put("variables", vars);
        check.put("steps", answer.getOrDefault("steps", List.of()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("variables", answer.getOrDefault("variables", List.of()));
        out.put("steps", answer.getOrDefault("steps", List.of()));
        out.put("notes", answer.getOrDefault("notes", ""));
        out.put("problems", validator().validate(check));
        return out;
    }

    /** What a bot uses (child bots, agent tools, scripts, AI Skills, files) and which bots use it. */
    @SuppressWarnings("unchecked")
    Map<String, Object> dependencies(String rel) throws IOException {
        Path self = ws.resolve(rel);
        if (!Files.isRegularFile(self) || !rel.endsWith(Workspace.BOT_SUFFIX)) throw new OoaException("STUDIO.NOT_A_BOT", "Not an agent file: " + rel);
        Map<String, Object> flow = (Map<String, Object>) json.parse(Files.readString(self));
        Map<String, String> botsById = new LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> stepsByBot = new LinkedHashMap<>();
        try (var st = Files.walk(ws.root, 12)) {
            for (Path p : st.filter(f -> f.getFileName().toString().endsWith(Workspace.BOT_SUFFIX)).toList()) {
                String r = ws.rel(p);
                if (r.startsWith(".") || r.contains("/.")) continue;
                try {
                    Map<String, Object> f = (Map<String, Object>) json.parse(Files.readString(p));
                    botsById.putIfAbsent(String.valueOf(f.get("id")), r);
                    stepsByBot.put(r, com.oneofficeautomation.runner.Steps.walk((List<Object>) f.getOrDefault("steps", List.of())));
                } catch (RuntimeException ignored) { /* unreadable bot */ }
            }
        }
        List<Map<String, Object>> bots = new ArrayList<>(), scripts = new ArrayList<>(), skills = new ArrayList<>(), files = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Map<String, Object> step : com.oneofficeautomation.runner.Steps.walk((List<Object>) flow.getOrDefault("steps", List.of()))) {
            String action = String.valueOf(step.get("action"));
            Map<String, Object> args = step.get("args") instanceof Map ? (Map<String, Object>) step.get("args") : Map.of();
            List<String> botIds = new ArrayList<>();
            if (action.equals("core/run_bot") && args.get("automation") instanceof String a) botIds.add(a);
            if (action.equals("ooa.ai_agent/run")) for (String k : List.of("tools", "sensitive_tools")) if (args.get(k) instanceof List<?> l) for (Object x : l) if (x instanceof String xs) botIds.add(xs);
            for (String id : botIds) if (seen.add("bot:" + id)) bots.add(dep(id, botsById.get(id), step));
            if (action.equals("ooa.ai_skills/run") && args.get("skill") instanceof String sk && !sk.startsWith("$") && seen.add("skill:" + sk)) {
                String path = Workspace.SKILLS_FOLDER + "/" + sk + ".json";
                skills.add(dep(sk, Files.isRegularFile(ws.root.resolve(path)) ? path : null, step));
            }
            for (Map.Entry<String, Object> e : args.entrySet()) {
                if (!(e.getValue() instanceof String v) || v.startsWith("$") || v.length() > 300 || !v.matches("^[^<>|\"*?\\r\\n]+\\.[A-Za-z0-9]{1,8}$")) continue;
                Path f;
                try { f = ws.resolve(v); } catch (OoaException outside) { continue; }
                if (!Files.isRegularFile(f) || !seen.add("file:" + v)) continue;
                (action.startsWith("ooa.script/") ? scripts : files).add(dep(v, ws.rel(f), step));
            }
        }
        List<Map<String, Object>> usedBy = new ArrayList<>();
        String myId = String.valueOf(flow.get("id"));
        stepsByBot.forEach((path, steps) -> {
            if (path.equals(rel)) return;
            for (Map<String, Object> st : steps) {
                Map<String, Object> a = st.get("args") instanceof Map ? (Map<String, Object>) st.get("args") : Map.of();
                boolean calls = "core/run_bot".equals(st.get("action")) && myId.equals(a.get("automation"));
                if ("ooa.ai_agent/run".equals(st.get("action"))) for (String k : List.of("tools", "sensitive_tools")) if (a.get(k) instanceof List<?> l && l.contains(myId)) calls = true;
                if (calls) { usedBy.add(Map.of("name", path.replaceAll("\\.flow\\.json$", ""), "path", path, "step", String.valueOf(st.get("id")))); return; }
            }
        });
        return Map.of("bots", bots, "scripts", scripts, "skills", skills, "files", files, "usedBy", usedBy);
    }

    private static Map<String, Object> dep(String name, String path, Map<String, Object> step) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("path", path);
        m.put("missing", path == null);
        m.put("step", String.valueOf(step.get("id")));
        return m;
    }

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
                    if (String.valueOf(f.getOrDefault("id", "")).toLowerCase(Locale.ROOT).contains(needle)) why.add("agent id");
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
            if (DEPTH.get() >= 5) throw new OoaException("AGENT.TOO_DEEP", "Agents calling agents calling agents are nested too deeply", "Simplify the agent's tools.");
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
                if (java.util.Arrays.asList("input", "in_out").contains(((Map<String, Object>) v).get("direction"))) declared.add(String.valueOf(((Map<String, Object>) v).get("name")));
            Map<String, Object> in = new LinkedHashMap<>();
            inputs.forEach((k, v) -> { if (declared.contains(k)) in.put(k, v); });
            return runner(new EventSink.ListSink()).run(flow, in, new Runner.RunOptions().maxRunSeconds(3600));
        } catch (IOException e) { throw new OoaException("STUDIO.NOT_FOUND", "Cannot read agent " + botPath + ": " + e.getMessage()); }
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
                    "Save the child agent anywhere in the workspace; its agent id must match.");
        }
    }

    private Object body(HttpExchange ex) throws IOException {
        byte[] b = ex.getRequestBody().readNBytes(MAX_BODY + 1);
        if (b.length > MAX_BODY) throw new OoaException("STUDIO.TOO_LARGE", "Request body exceeds 5 MB");
        String text = new String(b, StandardCharsets.UTF_8);
        return text.isBlank() ? null : json.parse(text);
    }

    private static String sessionCookie(HttpExchange ex) {
        String cookie = ex.getRequestHeaders().getFirst("Cookie");
        if (cookie != null) for (String c : cookie.split(";")) {
            String[] kv = c.trim().split("=", 2);
            if (kv.length == 2 && kv[0].equals(com.oneofficeautomation.controlroom.ControlRoomServer.COOKIE)) return kv[1];
        }
        return null;
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
