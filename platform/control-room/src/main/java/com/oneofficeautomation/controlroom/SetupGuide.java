package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.controlroom.ControlRoom.User;
import com.oneofficeautomation.controlroom.module.ModuleContext;
import com.oneofficeautomation.sdk.OoaException;

import java.time.Instant;
import java.util.*;

/**
 * Setup > Install and set up (26 Nov 2026, design S2): the install guide for someone setting OOA up for the first time.
 * Four phases (server, people and runners, developers and AI, first run); each step is checked live when an
 * administrator opens the page, so it says what is done and what is left. Feature modules add their own steps
 * (ModuleContext.setupStep: DocFlow, OneChat) without the kernel naming them. Administrators only (settings.manage);
 * the setting setup_menu ("admins" or "off") shows or hides the page in everyone's menu.
 */
public final class SetupGuide {
    /** The phases in order, with their names on the page. */
    public static final List<List<String>> PHASES = List.of(List.of("server", "Server"), List.of("people", "People and runners"),
            List.of("developers", "Developers and AI"), List.of("first_run", "First run"));
    /** The sample agent that step "first run" checks in and runs. */
    public static final String SAMPLE = "Samples/Hello OOA.flow.json";

    private final ControlRoom cr;

    SetupGuide(ControlRoom cr) { this.cr = cr; }

    /** {phases: [{id, name, done, total, steps: [...]}], done, total}; secure: whether this request came over HTTPS. */
    public Map<String, Object> steps(User u, boolean secure) {
        cr.require(u, "settings.manage", "see Install and set up");
        List<Map<String, Object>> all = new ArrayList<>();
        all.add(install(u));
        all.add(database());
        all.add(https(secure));
        all.add(license(u));
        all.add(people());
        all.add(runners());
        all.add(studio());
        all.add(model());
        all.add(sample());
        for (ModuleContext.SetupStep s : cr.modules.setupSteps) {
            try { all.add(new LinkedHashMap<>(s.check(u))); }
            catch (RuntimeException e) {   // a module whose check fails must not break the page
                all.add(step("module", "developers", 90, "A module's step", false, "todo", "Its check failed: " + e.getMessage(), "See the server log.", null, null));
            }
        }
        List<Map<String, Object>> phases = new ArrayList<>();
        int done = 0, total = 0;
        for (List<String> p : PHASES) {
            List<Map<String, Object>> in = all.stream().filter(s -> p.get(0).equals(s.get("phase")))
                    .sorted(Comparator.comparingInt(s -> ((Number) s.getOrDefault("order", 50)).intValue())).toList();
            int d = (int) in.stream().filter(s -> "done".equals(s.get("status")) || "not_needed".equals(s.get("status"))).count();
            phases.add(Map.of("id", p.get(0), "name", p.get(1), "done", d, "total", in.size(), "steps", in));
            done += d;
            total += in.size();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("phases", phases);
        out.put("done", done);
        out.put("total", total);
        out.put("menu", String.valueOf(cr.platform.setting("setup_menu")));
        return out;
    }

    /** One step's shape (the same as a module's SetupStep). */
    public static Map<String, Object> step(String id, String phase, int order, String title, boolean optional, String status, String detail, String help,
                                           String page, String command) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("phase", phase);
        m.put("order", order);
        m.put("title", title);
        m.put("optional", optional);
        m.put("status", status);
        m.put("detail", detail);
        m.put("help", help);
        if (page != null) m.put("page", page);
        if (command != null) m.put("command", command);
        return m;
    }

    private Map<String, Object> install(User u) {
        Object edition = cr.platform.license(u).get("edition");
        return step("install", "server", 10, "Install One CommandCenter", false, "done", "Version " + ControlRoom.VERSION + ", " + edition + ".",
                "Windows: the setup package or the MSI; Linux: the service unit; or the container image. This server is installed.", null, null);
    }

    private Map<String, Object> database() {
        Map<String, Object> info = cr.db.info();
        String engine = String.valueOf(info.get("engine"));
        boolean current = Objects.equals(info.get("schema_version"), info.get("schema_latest"));
        boolean embedded = engine.startsWith("H2");
        String status = embedded || !current ? "todo" : "done";
        String detail = engine + (info.get("database") != null && !embedded ? " / " + info.get("database") : "") + "; schema "
                + (current ? "up to date" : "version " + info.get("schema_version") + " of " + info.get("schema_latest") + ": run ooa db migrate");
        return step("database", "server", 20, "Connect the database", false, status, detail,
                embedded ? "The built-in database is for trying OOA. For production, create an empty database in SQL Server or PostgreSQL, then run this on the server and restart."
                        : "Upgrades: stop One CommandCenter, back up the database, run ooa db migrate, then start it again.",
                null, embedded ? "ooa db configure" : current ? null : "ooa db migrate");
    }

    private Map<String, Object> https(boolean secure) {
        String url = String.valueOf(cr.platform.setting("public_url")).trim();
        boolean publicHttps = url.toLowerCase(Locale.ROOT).startsWith("https://");
        boolean done = secure && !url.isEmpty() || publicHttps;
        String detail = (secure ? "HTTPS is on" : "HTTPS is off") + "; " + (url.isEmpty() ? "no public address set" : "public address " + url);
        return step("https", "server", 30, "Turn on HTTPS and set the public address", false, done ? "done" : "todo", detail,
                "Run the command on the server (or use your own certificate with ooa tls import), restart, then set the public address in Settings.",
                "settings", secure ? null : "ooa tls self-signed");
    }

    private Map<String, Object> license(User u) {
        Map<String, Object> lic = cr.licensing.status(u);
        String state = String.valueOf(lic.get("state"));
        boolean ok = List.of("active", "expiring", "grace").contains(state);
        String detail = ok ? "Licensed to " + lic.getOrDefault("customer", "?") + (lic.get("expires") != null ? ", valid to " + lic.get("expires") : "")
                : state.startsWith("evaluation") ? "Evaluation, until " + lic.get("evaluation_ends") : "No license installed (" + state + ")";
        return step("license", "server", 40, "Install the license", false, ok ? "done" : "todo", detail,
                "Open Licenses and upload the license file you received.", "licenses", null);
    }

    private Map<String, Object> people() {
        long people = cr.count("SELECT COUNT(*) AS n FROM users WHERE enabled = TRUE");
        boolean sso = !String.valueOf(cr.platform.setting("sso_issuer")).isBlank() || !String.valueOf(cr.platform.setting("saml_idp_sso_url")).isBlank();
        return step("people", "people", 10, "Add people and choose how they sign in", false, people > 1 || sso ? "done" : "todo",
                people + (people == 1 ? " person" : " people") + "; single sign-on " + (sso ? "on" : "off"),
                "Add people and give them roles in Users, or connect your identity provider (OpenID Connect or SAML) in Settings so people sign in with their company account.",
                "users", null);
    }

    private Map<String, Object> runners() {
        long online = cr.count("SELECT COUNT(*) AS n FROM devices WHERE enabled = TRUE AND last_seen > ?", Instant.now().minus(ControlRoom.ONLINE));
        long all = cr.count("SELECT COUNT(*) AS n FROM devices WHERE enabled = TRUE");
        return step("runners", "people", 20, "Install runners on the worker machines", false, online > 0 ? "done" : "todo",
                online + " of " + all + " runner" + (all == 1 ? "" : "s") + " online",
                "On each worker machine, install OOA and register it as a runner: make a registration key in Runner Machines, then download the ready runner package for it, or run the command with the key.",
                "devices", "ooa agent register --server <this address> --key <registration key>");
    }

    private Map<String, Object> studio() {
        long agents = cr.count("SELECT COUNT(*) AS n FROM bots WHERE deleted_at IS NULL AND path <> ?", SAMPLE);
        return step("studio", "developers", 10, "Set up Studio and the browser extension", false, agents > 0 ? "done" : "todo",
                agents > 0 ? agents + " agent" + (agents == 1 ? "" : "s") + " checked in" : "No agent checked in yet",
                "Developers install OOA on their computer and open Studio (ooa studio, or /studio/ on this server from the same computer), sign in to this One CommandCenter, and add the browser extension for Edge, Chrome or Firefox from the installation folder (browser-extension).",
                null, "ooa studio");
    }

    private Map<String, Object> model() {
        long n = cr.count("SELECT COUNT(*) AS n FROM model_connections");
        return step("model", "developers", 20, "Connect an AI model", true, n > 0 ? "done" : "todo",
                n > 0 ? n + " model connection" + (n == 1 ? "" : "s") : "No model connection yet",
                "Add a Model Connection (OpenAI, Azure OpenAI, Anthropic, an OpenAI-compatible server…) with its key in the vault. AI steps, Studio's assistant and One Chat use it.",
                "connections", null);
    }

    private Map<String, Object> sample() {
        Map<String, Object> last = cr.db.one("SELECT status FROM runs WHERE bot_path = ? ORDER BY created DESC LIMIT 1", SAMPLE);
        String status = last == null ? "No sample run yet" : "Last sample run: " + last.get("status");
        return step("sample", "first_run", 10, "Run the sample agent", false, last != null && "success".equals(last.get("status")) ? "done" : "todo", status,
                "Run it: the agent \"Hello OOA\" (in Samples) is checked in if it is not there, and runs on the first runner online. It writes one line to the run's log.",
                "activity", null);
    }

    /** The sample agent: one step that writes to the run's log. */
    static Map<String, Object> sampleFlow() {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("kind", "TaskBot");
        f.put("id", "hello_ooa");
        f.put("name", "Hello OOA");
        f.put("description", "The sample agent of Install and set up: writes one line to the run's log.");
        f.put("variables", List.of());
        f.put("steps", List.of(Map.of("id", "s1", "action", "core/log", "description", "Say hello",
                "args", Map.of("message", "Hello from OOA: this runner runs agents."))));
        return f;
    }

    /** Checks the sample agent in (when it is not there) and runs it on the first runner online. -> the run */
    public Map<String, Object> runSample(User u, String ip) {
        cr.require(u, "settings.manage", "run the sample agent");
        if (cr.db.one("SELECT path FROM bots WHERE path = ? AND deleted_at IS NULL", SAMPLE) == null)
            cr.checkin(u, SAMPLE, sampleFlow(), "Install and set up: the sample agent", true, ip);
        Map<String, Object> device = cr.db.one("SELECT id FROM devices WHERE enabled = TRUE AND last_seen > ? ORDER BY last_seen DESC LIMIT 1", Instant.now().minus(ControlRoom.ONLINE));
        if (device == null) throw new OoaException("SETUP.NO_RUNNER", "No runner is online to run the sample agent", "Install a runner first (step: Install runners), or start the one on this computer.");
        return cr.startRun(u.username(), u.role(), SAMPLE, String.valueOf(device.get("id")), Map.of(), "manual", ip);
    }
}
