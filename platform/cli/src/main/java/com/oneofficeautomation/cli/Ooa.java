package com.oneofficeautomation.cli;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.runner.*;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;
import com.oneofficeautomation.sdk.PackageRegistry;

import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * OOA CLI (Java). Commands:
 *   ooa manifest [--summary]          print package manifests (what Studio renders)
 *   ooa validate FLOW...              validate Flows against schema and manifests
 *   ooa run FLOW [-i key=value]...    run a Flow file
 *   ooa conformance DIR               run the language-neutral conformance suite
 *   ooa server [--port N] [--workspace DIR] [--data DIR] [--no-agent] [--no-browser]
 *                                     one step: Control Room, Studio (/studio) and a local Bot Agent on http://localhost:8720
 *   ooa studio [--workspace DIR]      start the Studio preview on http://localhost:8710
 *   ooa control-room [--port N] [--host H] [--data DIR] [--db-url URL --db-user U --db-password-env VAR]
 *                                     start Control Room on http://localhost:8720; the database comes from
 *                                     DIR\config\ooa-db.properties when that file exists (SQL Server, PostgreSQL)
 *   ooa db configure|set-password|test|init|migrate|script|copy   set up the Command Center database (DbCommand)
 *   ooa agent register --url URL --key KEY [--name NAME] [--config FILE]
 *   ooa agent start [--config FILE] [--workspace DIR]
 * picocli and GraalVM native image replace this hand-rolled parser in a later slice.
 */
public final class Ooa {
    private Ooa() {}

    static JsonCodec codec() {
        try { return (JsonCodec) Class.forName("com.oneofficeautomation.adapters.jackson.JacksonJsonCodec").getDeclaredConstructor().newInstance(); }
        catch (ReflectiveOperationException | LinkageError e) { return JsonCodec.LITE; }
    }

    static FlowSchemaValidator schemaValidator() {
        try {
            return (FlowSchemaValidator) Class.forName("com.oneofficeautomation.adapters.jackson.NetworkntFlowSchemaValidator")
                    .getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | LinkageError e) {
            System.err.println("warning: JSON Schema adapter not on classpath; running semantic validation only");
            return FlowSchemaValidator.NONE;
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) { usage(); System.exit(1); }
        int code;
        try {
            code = switch (args[0]) {
                case "manifest" -> manifest(Arrays.asList(args).contains("--summary"));
                case "validate" -> validate(Arrays.copyOfRange(args, 1, args.length));
                case "run" -> run(Arrays.copyOfRange(args, 1, args.length));
                case "conformance" -> conformance(Path.of(args.length > 1 ? args[1] : "../contracts/conformance/cases"));
                case "server" -> server(Arrays.copyOfRange(args, 1, args.length));
                case "studio" -> studio(Arrays.copyOfRange(args, 1, args.length));
                case "control-room" -> controlRoom(Arrays.copyOfRange(args, 1, args.length));
                case "agent" -> agent(Arrays.copyOfRange(args, 1, args.length));
                case "reset-admin" -> resetAdmin(Arrays.copyOfRange(args, 1, args.length));
                case "db" -> DbCommand.run(Arrays.copyOfRange(args, 1, args.length));
                case "license" -> LicenseCommand.run(Arrays.copyOfRange(args, 1, args.length));
                case "check" -> CheckCommand.run(Arrays.copyOfRange(args, 1, args.length));
                case "backup" -> BackupCommand.run(Arrays.copyOfRange(args, 1, args.length));
                case "release" -> ReleaseCommand.run(Arrays.copyOfRange(args, 1, args.length));
                case "tls" -> TlsCommand.run(Arrays.copyOfRange(args, 1, args.length));
                case "service" -> WindowsService.run(Arrays.copyOfRange(args, 1, args.length));
                case "service-run" -> WindowsService.serviceRun(Arrays.copyOfRange(args, 1, args.length));
                case "native-host" -> NativeHost.main(Arrays.copyOfRange(args, 1, args.length));
                case "browser-connector" -> BrowserConnector.main(Arrays.copyOfRange(args, 1, args.length));
                default -> { usage(); yield 1; }
            };
        } catch (OoaException e) {
            System.err.println("error: " + e);
            code = 2;
        }
        System.exit(code);
    }

    static void usage() {
        System.err.println("usage: ooa manifest [--summary] | validate FLOW... | run FLOW [-i key=value]... | conformance [DIR]"
                + " | server [--port N] [--workspace DIR] [--data DIR] [--no-agent] [--no-browser]"
                + " | studio [--workspace DIR] [--port N] | control-room [--port N] [--host H] [--data DIR] [--db-url URL]"
                + " | agent register --url URL --key KEY [--name NAME] [--ca FILE] [--proxy system|none|URL] | agent network [--proxy ..] [--proxy-user U --proxy-password-env VAR] [--ca FILE] | agent start [--workspace DIR]"
                + " | reset-admin [--user NAME] [--data DIR] (stop the server first)"
                + " | db configure|set-password|test|init|migrate|script|copy [--data DIR]"
                + " | license show FILE"
                + " | backup now|list|restore FILE [--replace] [--data DIR] | check [--data DIR] [--port N] | agent diagnose | agent move --url URL [--ca FILE] | release verify MANIFEST [--dir DIR]"
                + " | tls configure --pfx FILE|set-password|self-signed [--host NAME]|show|export-cert --out FILE|off [--data DIR]"
                + " | service install|uninstall|start|stop|status [--mode server|control-room|agent] (Windows services)"
                + " | browser-connector install|status|uninstall | native-host (started by the browser)");
    }

    static int studio(String[] a) throws Exception {
        Path workspace = Path.of(System.getProperty("user.home"), "OOA Workspace");
        int port = 8710;
        for (int i = 0; i < a.length - 1; i++) {
            if (a[i].equals("--workspace")) workspace = Path.of(a[++i]);
            else if (a[i].equals("--port")) port = Integer.parseInt(a[++i]);
        }
        new StudioServer(workspace, port, Path.of("../examples/refund")).start();
        Thread.currentThread().join();
        return 0;
    }

    static Map<String, String> options(String[] a) {
        Map<String, String> o = new HashMap<>();
        for (int i = 0; i < a.length; i++) if (a[i].startsWith("--")) o.put(a[i].substring(2), i + 1 < a.length && !a[i + 1].startsWith("--") ? a[++i] : "true");
        return o;
    }

    /** Control Room's database and services, opened from the command-line options shared by `control-room` and `server`. */
    record ControlRoomSetup(com.oneofficeautomation.controlroom.ControlRoom cr, com.oneofficeautomation.controlroom.Db db, Path data, String firstPassword, String dbLabel) {}

    /** The Command Center data folder: --data, else the folder of earlier versions when it holds a database, else ~/OOA Command Center. */
    static Path dataDir(Map<String, String> o) {
        // Keep using the folder of earlier versions ("OOA Control Room") when it holds a database, so an upgrade never starts empty.
        Path legacy = Path.of(System.getProperty("user.home"), "OOA Control Room");
        Path defaultData = Files.exists(legacy.resolve("controlroom.mv.db")) ? legacy : Path.of(System.getProperty("user.home"), "OOA Command Center");
        return Path.of(o.getOrDefault("data", defaultData.toString()));
    }

    static ControlRoomSetup openControlRoom(Map<String, String> o) throws Exception {
        Path data = dataDir(o);
        Files.createDirectories(data);
        com.oneofficeautomation.controlroom.Db db;
        String label;
        Path dbConfig = o.containsKey("db-config") ? Path.of(o.get("db-config")) : com.oneofficeautomation.controlroom.DbConfig.defaultFile(data);
        if (o.containsKey("db-url")) {
            String pw = o.containsKey("db-password-env") ? System.getenv(o.get("db-password-env")) : "";
            db = new com.oneofficeautomation.controlroom.Db(o.get("db-url"), o.getOrDefault("db-user", ""), pw == null ? "" : pw);
            label = o.get("db-url");
        } else if (Files.exists(dbConfig)) {
            var cfg = com.oneofficeautomation.controlroom.DbConfig.load(dbConfig);
            db = com.oneofficeautomation.controlroom.Db.open(cfg, data, new com.oneofficeautomation.controlroom.Vault(data), false);
            label = cfg.label() + " as " + db.user();
        } else {
            db = com.oneofficeautomation.controlroom.Db.embedded(data);
            label = "embedded H2 in " + data;
        }
        var cr = new com.oneofficeautomation.controlroom.ControlRoom(db, data, FirstPartyPackages.registry(), schemaValidator());
        return new ControlRoomSetup(cr, db, data, cr.bootstrap(), label);
    }

    static void printFirstStart(ControlRoomSetup s) {
        if (s.firstPassword() == null) return;
        System.out.println();
        System.out.println("  First start: sign in as  admin  with the one-time password  " + s.firstPassword());
        System.out.println("  You will be asked to choose a new password. This password is shown only once.");
    }

    /** Forgotten password: prints a new one-time password for the user (default admin). The server must be stopped first. */
    static int resetAdmin(String[] a) throws Exception {
        Map<String, String> o = options(a);
        String user = o.getOrDefault("user", "admin");
        ControlRoomSetup s;
        try { s = openControlRoom(o); }
        catch (Exception e) {
            System.err.println("Cannot open the Command Center database. Stop ONEOFFICEAUTOMATION first (Ctrl+C in its window), then run this again.");
            System.err.println("  (" + e.getMessage() + ")");
            return 2;
        }
        try {
            if (s.firstPassword() != null) {
                printFirstStart(s);
                return 0;
            }
            String temp;
            try { temp = s.cr().recoverUser(user); }
            catch (OoaException e) {
                System.err.println(e.getMessage() + ". Users in " + s.dbLabel() + ":");
                for (Map<String, Object> u : s.cr().userNames()) System.err.println("  " + u.get("username") + "  (" + u.get("role") + (Boolean.TRUE.equals(u.get("enabled")) ? "" : ", disabled") + ")");
                return 1;
            }
            System.out.println("Password reset for '" + user + "' in " + s.dbLabel() + ".");
            System.out.println();
            System.out.println("  Sign in as  " + user + "  with the one-time password  " + temp);
            System.out.println("  You will be asked to choose a new password. This password is shown only once.");
            return 0;
        } finally { s.db().close(); }
    }

    static int controlRoom(String[] a) throws Exception {
        Map<String, String> o = options(a);
        int port = Integer.parseInt(o.getOrDefault("port", "8720"));
        String host = o.getOrDefault("host", "127.0.0.1");
        ControlRoomSetup s = openControlRoom(o);
        var server = new com.oneofficeautomation.controlroom.ControlRoomServer(s.cr(), host, port);
        String tls = useTls(server, s);
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { server.stop(); s.db().close(); }));
        System.out.println("ONEOFFICEAUTOMATION One Command Center is running.");
        System.out.println("  Open:     " + (server.https() ? "https://" : "http://") + (host.equals("0.0.0.0") ? "localhost" : host) + ":" + port);
        System.out.println("  Database: " + s.dbLabel());
        System.out.println("  Vault key: " + s.cr().vault.keySource);
        if (tls != null) System.out.println("  HTTPS:    " + tls);
        if (!host.equals("127.0.0.1") && !host.equals("localhost") && !server.https())
            System.out.println("  WARNING: listening on " + host + " without TLS; set up HTTPS (ooa tls) or a reverse proxy before other machines use it.");
        printFirstStart(s);
        System.out.println("  Press Ctrl+C to stop.");
        Thread.currentThread().join();
        return 0;
    }

    /**
     * One-step start for a developer machine: Control Room at /, Studio at /studio/ (this computer only) and a Bot Agent
     * for this computer, all in one process on one port. The local agent registers itself on first start.
     */
    static int server(String[] a) throws Exception {
        Map<String, String> o = options(a);
        int port = Integer.parseInt(o.getOrDefault("port", "8720"));
        String host = o.getOrDefault("host", "127.0.0.1");
        Path workspace = Path.of(o.getOrDefault("workspace", Path.of(System.getProperty("user.home"), "OOA Workspace").toString()));
        ControlRoomSetup s = openControlRoom(o);
        var server = new com.oneofficeautomation.controlroom.ControlRoomServer(s.cr(), host, port);
        String tls = useTls(server, s);
        String localUrl = server.internalUrl();   // with HTTPS: a 127.0.0.1-only listener for this machine's runner and Studio
        var studio = new StudioServer(workspace, port, Path.of("../examples/refund"));
        server.mount("/studio", studio.mountedAt("/studio", localUrl));
        server.start();
        Path agentConfig = Path.of(o.getOrDefault("agent-config", Path.of(System.getProperty("user.home"), ".ooa-agent", "local-agent.json").toString()));
        Path agentWorkspace = Path.of(o.getOrDefault("agent-workspace", Path.of(System.getProperty("user.home"), "OOA Agent Workspace").toString()));
        com.oneofficeautomation.agent.Agent agent = o.containsKey("no-agent") ? null : startLocalAgent(s.cr(), localUrl, agentConfig, agentWorkspace);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { if (agent != null) agent.stop(); server.stop(); s.db().close(); }));
        String open = (server.https() ? "https" : "http") + "://localhost:" + port;
        System.out.println("ONEOFFICEAUTOMATION is running.");
        System.out.println("  Open:             " + open);
        System.out.println("  Command Center:   " + open + "/");
        System.out.println("  Agent Studio:     " + open + "/studio/   (this computer only)");
        System.out.println("  One Runner:       " + (agent == null ? "not started (--no-agent)" : "this computer, connected"));
        System.out.println("  My Workspace:     " + studio.workspaceRoot());
        System.out.println("  Database:         " + s.dbLabel());
        if (tls != null) System.out.println("  HTTPS:            " + tls);
        if (!host.equals("127.0.0.1") && !host.equals("localhost") && !server.https())
            System.out.println("  WARNING: Command Center listens on " + host + " without TLS; set up HTTPS (ooa tls) or a reverse proxy before other machines use it.");
        printFirstStart(s);
        System.out.println("  Press Ctrl+C (or close this window) to stop everything.");
        if (!o.containsKey("no-browser")) openBrowser(open);
        Thread.currentThread().join();
        return 0;
    }

    /** Turns HTTPS on when config/ooa-tls.properties exists in the data folder; returns a one-line description, or null. */
    static String useTls(com.oneofficeautomation.controlroom.ControlRoomServer server, ControlRoomSetup s) {
        Path f = com.oneofficeautomation.controlroom.TlsConfig.defaultFile(s.data());
        if (!Files.exists(f)) return null;
        var cfg = com.oneofficeautomation.controlroom.TlsConfig.load(f);
        server.tls(cfg.sslContext(s.cr().vault));
        Map<String, Object> d = cfg.describe(s.cr().vault);
        return "on (" + d.get("subject") + ", valid until " + String.valueOf(d.get("valid_until")).substring(0, 10) + (Boolean.TRUE.equals(d.get("self_signed")) ? ", self-signed" : "") + ")";
    }

    /** Registers this computer once (its own config file, so a separately registered agent is never touched) and runs the agent. */
    @SuppressWarnings("unchecked")
    static com.oneofficeautomation.agent.Agent startLocalAgent(com.oneofficeautomation.controlroom.ControlRoom cr, String url, Path config, Path workspace) throws Exception {
        boolean registered = false;
        if (Files.exists(config)) {
            Map<String, Object> c = (Map<String, Object>) JsonLite.parse(Files.readString(config));
            registered = url.equals(c.get("url")) && cr.device((String) c.get("device_id"), (String) c.get("secret")) != null;
        }
        if (!registered) com.oneofficeautomation.agent.Agent.register(url, cr.localRegistrationKey(), com.oneofficeautomation.agent.Agent.hostname() + " (this computer)", config);
        var agent = new com.oneofficeautomation.agent.Agent(config, workspace);
        Thread t = new Thread(agent::run, "ooa-local-agent");
        t.setDaemon(true);
        t.start();
        return agent;
    }

    static void openBrowser(String url) {
        try {
            if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE))
                java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
            else if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"))
                new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
        } catch (Exception | LinkageError e) { System.out.println("  (Open " + url + " in your browser.)"); }
    }

    static int agent(String[] a) throws Exception {
        if (a.length == 0) { usage(); return 1; }
        Map<String, String> o = options(Arrays.copyOfRange(a, 1, a.length));
        Path config = Path.of(o.getOrDefault("config", com.oneofficeautomation.agent.Agent.defaultConfig().toString()));
        switch (a[0]) {
            case "register" -> {
                if (!o.containsKey("url") || !o.containsKey("key")) { System.err.println("usage: ooa agent register --url http://controlroom:8720 --key ooareg_... [--name NAME] [--ca ooa.cer] [--proxy system|none|http://host:port] [--proxy-user USER --proxy-password-env VAR]"); return 1; }
                Map<String, Object> r = com.oneofficeautomation.agent.Agent.register(o.get("url"), o.get("key"), o.get("name"), config, o.containsKey("ca") ? Path.of(o.get("ca")) : null,
                    o.get("proxy"), o.get("proxy-user"), proxyPassword(o));
                System.out.println("Registered runner '" + r.get("name") + "' (" + r.get("device_id") + "). Credentials saved to " + r.get("config"));
                System.out.println("Start the agent with:  ooa agent start");
                return 0;
            }
            case "start" -> {
                Path ws = Path.of(o.getOrDefault("workspace", Path.of(System.getProperty("user.home"), "OOA Agent Workspace").toString()));
                var agent = new com.oneofficeautomation.agent.Agent(config, ws);
                Runtime.getRuntime().addShutdownHook(new Thread(agent::stop));
                agent.run();
                return 0;
            }
            case "network" -> {
                // proxy and trusted certificate of a registered runner; with no option, shows them
                Map<String, Object> r = com.oneofficeautomation.agent.Agent.network(config, o.get("proxy"), o.get("proxy-user"), proxyPassword(o), o.containsKey("ca") ? Path.of(o.get("ca")) : null);
                System.out.println("Proxy:       " + r.get("proxy") + (String.valueOf(r.get("proxy_user")).isEmpty() ? "" : " (signs in as " + r.get("proxy_user") + ")"));
                System.out.println("Trusted CA:  " + (String.valueOf(r.get("ca")).isEmpty() ? "Java's and Windows' certificate authorities" : r.get("ca") + ", plus Java's and Windows' authorities"));
                System.out.println("Restart the runner for a change to take effect.");
                return 0;
            }
            case "move" -> {
                // the Command Center moved (new server, cloud) with its data: keep this runner's registration
                if (!o.containsKey("url")) { System.err.println("usage: ooa agent move --url https://new-command-center:8720 [--ca ooa.cer]"); return 1; }
                String old = com.oneofficeautomation.agent.Agent.moveTo(config, o.get("url"), o.containsKey("ca") ? Path.of(o.get("ca")) : null);
                System.out.println("This runner now uses " + o.get("url") + " (was " + old + "). Restart the runner for it to take effect.");
                return 0;
            }
            case "diagnose" -> {
                // why this runner cannot reach or work with its Command Center
                int fails = 0;
                for (var l : com.oneofficeautomation.agent.Diagnose.run(config, Path.of(o.getOrDefault("workspace", Path.of(System.getProperty("user.home"), "OOA Agent Workspace").toString())))) {
                    System.out.printf("%-5s %-18s %s%n", l.level(), l.what(), l.detail());
                    if (l.level().equals("FAIL")) fails++;
                }
                System.out.println(fails == 0 ? "No problems found." : fails + " problem(s) found.");
                return fails;
            }
            case "install-task" -> { return WindowsService.installTask(o); }
            case "uninstall-task" -> { return WindowsService.uninstallTask(); }
            default -> { usage(); return 1; }
        }
    }

    /** The proxy password from --proxy-password-env VAR (never on the command line itself). */
    static String proxyPassword(Map<String, String> o) {
        if (!o.containsKey("proxy-password-env")) return null;
        String v = System.getenv(o.get("proxy-password-env"));
        if (v == null) throw new OoaException("AGENT.PROXY_PASSWORD", "The environment variable " + o.get("proxy-password-env") + " is not set");
        return v;
    }

    static int manifest(boolean summary) {
        List<Map<String, Object>> ms = FirstPartyPackages.registry().allManifests();
        if (summary) for (Map<String, Object> m : ms) {
            List<Object> ids = new ArrayList<>();
            for (Object a : (List<?>) m.get("actions")) ids.add(((Map<?, ?>) a).get("id"));
            System.out.println(m.get("id") + "@" + m.get("version") + ": " + ids);
        }
        else System.out.println(codec().write(ms, true));
        return 0;
    }

    @SuppressWarnings("unchecked")
    static int validate(String[] files) throws Exception {
        FlowValidator v = new FlowValidator(FirstPartyPackages.registry(), schemaValidator());
        int bad = 0;
        for (String f : files) {
            List<Map<String, Object>> problems = v.validate((Map<String, Object>) codec().parse(Files.readString(Path.of(f))));
            System.out.println(f + ": " + (problems.isEmpty() ? "OK" : problems.size() + " problem(s)"));
            for (Map<String, Object> p : problems) {
                bad++;
                Object loc = p.get("stepId") != null ? p.get("stepId") : p.get("path");
                System.out.println("  [" + loc + "] " + p.get("message") + (p.get("suggestedFix") != null ? "  -> " + p.get("suggestedFix") : ""));
            }
        }
        return bad == 0 ? 0 : 1;
    }

    @SuppressWarnings("unchecked")
    static int run(String[] a) throws Exception {
        if (a.length == 0) { usage(); return 1; }
        JsonCodec json = codec();
        Map<String, Object> flow = (Map<String, Object>) json.parse(Files.readString(Path.of(a[0])));
        Map<String, Object> inputs = new LinkedHashMap<>();
        for (int i = 1; i < a.length; i++) {
            if ((a[i].equals("-i") || a[i].equals("--input")) && i + 1 < a.length) {
                String kv = a[++i];
                int eq = kv.indexOf('=');
                if (eq < 0) throw new OoaException("CLI.BAD_INPUT", "--input expects key=value, got '" + kv + "'");
                String raw = kv.substring(eq + 1);
                Object val;
                try { val = json.parse(raw); } catch (RuntimeException e) { val = raw; }
                inputs.put(kv.substring(0, eq), val);
            }
        }
        Runner runner = Runner.builder(FirstPartyPackages.registry()).sink(new EventSink.ConsoleSink(false))
                .schemaValidator(schemaValidator()).workDir(Path.of(".")).build();
        RunResult r = runner.run(flow, inputs);
        System.out.println(json.write(r.toMap(), true));
        return r.ok() ? 0 : 2;
    }

    @SuppressWarnings("unchecked")
    static int conformance(Path dir) throws Exception {
        PackageRegistry reg = FirstPartyPackages.registry();
        CredentialVault vault = new CredentialVault.InMemory().put("test", "app", "password", "s3cr3t");
        List<Path> cases;
        try (Stream<Path> s = Files.list(dir)) { cases = s.filter(p -> p.toString().endsWith(".json")).sorted().toList(); }
        int passed = 0;
        List<String> failures = new ArrayList<>();
        for (Path c : cases) {
            Map<String, Object> tc = (Map<String, Object>) JsonLite.parse(Files.readString(c));
            Path work = Files.createTempDirectory("ooa-conf");
            Runner runner = Runner.builder(reg).service("vault", vault).workDir(work).schemaValidator(schemaValidator()).build();
            RunResult r = runner.run((Map<String, Object>) tc.get("flow"), (Map<String, Object>) tc.get("inputs"));
            Map<String, Object> exp = (Map<String, Object>) tc.get("expect");
            List<String> why = new ArrayList<>();
            if (!Objects.equals(exp.get("status"), r.status())) why.add("status " + r.status() + " != " + exp.get("status") + " " + r.error());
            if (exp.containsKey("outputs")) {
                Object got = norm(JsonLite.parse(JsonLite.write(r.toMap().get("outputs"))));
                if (!Objects.equals(got, norm(exp.get("outputs")))) why.add("outputs " + JsonLite.write(got) + " != " + JsonLite.write(exp.get("outputs")));
            }
            if (exp.containsKey("errorCode") && (r.error() == null || !exp.get("errorCode").equals(r.error().get("code"))))
                why.add("errorCode " + (r.error() == null ? null : r.error().get("code")) + " != " + exp.get("errorCode"));
            if (exp.containsKey("errorStepId") && (r.error() == null || !exp.get("errorStepId").equals(r.error().get("stepId"))))
                why.add("errorStepId " + (r.error() == null ? null : r.error().get("stepId")) + " != " + exp.get("errorStepId"));
            String name = c.getFileName().toString().replace(".json", "");
            if (why.isEmpty()) { passed++; System.out.println("PASS " + name); }
            else { failures.add(name); System.out.println("FAIL " + name + ": " + String.join("; ", why)); }
        }
        System.out.println(passed + "/" + cases.size() + " conformance cases passed");
        return failures.isEmpty() ? 0 : 1;
    }

    /** Numbers compare by value (2.20 == 2.2). */
    static Object norm(Object v) {
        if (v instanceof BigDecimal d) return d.signum() == 0 ? BigDecimal.ZERO : d.stripTrailingZeros();
        if (v instanceof Map<?, ?> m) { Map<Object, Object> o = new LinkedHashMap<>(); m.forEach((k, x) -> o.put(k, norm(x))); return o; }
        if (v instanceof List<?> l) { List<Object> o = new ArrayList<>(); for (Object x : l) o.add(norm(x)); return o; }
        return v;
    }
}
