package com.oneofficeautomation.modules.docauto;

import com.oneofficeautomation.controlroom.Vault;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.function.Function;

/**
 * Document Automation starts with the platform (improvement list 13 Nov 2026, item 9). On premises, when DocFlow is
 * installed on this computer, One CommandCenter
 * <ol>
 *   <li>finds it: OOA_DOCFLOW_HOME, the {@code home} line of {@code <data>/config/ooa-docflow.properties}, the
 *       installer's {@code DocFlow} folder next to the program, or the developer folder {@code C:\OOA\oneoffice-docauto};</li>
 *   <li>records where it is and its service token when nothing says so yet (Settings, OOA_DOCFLOW_URL and the
 *       installer's file win), so Document Automation is set up without a manual step;</li>
 *   <li>starts it (its own {@code windows\start.ps1}, hidden, output to {@code <data>/logs/docflow.log}) when nothing
 *       answers at its address, and stops it when One CommandCenter stops.</li>
 * </ol>
 * It does nothing in the SaaS service (the region runs DocFlow), when it is switched off (Settings > Document
 * Automation > Start with One CommandCenter, or OOA_DOCFLOW_AUTOSTART=false), when Settings points at another server,
 * or when DocFlow is not installed here. One CommandCenter keeps working whatever happens to DocFlow.
 */
final class DocFlowLauncher {
    private DocFlowLauncher() { }

    static final String DEV_HOME = "C:\\OOA\\oneoffice-docauto";
    private static volatile Process started;

    /** DocFlow's folder on this computer, or null: one with config\local.env and windows\start.ps1. */
    static Path findHome(Path data, Function<String, String> env, Path programDir) {
        List<Path> candidates = new ArrayList<>();
        String e = env.apply("OOA_DOCFLOW_HOME");
        if (e != null && !e.isBlank()) candidates.add(Path.of(e.trim()));
        String h = DocFlowConfig.load(data).getProperty("home", "").trim();
        if (!h.isEmpty()) candidates.add(Path.of(h));
        if (programDir != null) { candidates.add(programDir.resolve("DocFlow")); if (programDir.getParent() != null) candidates.add(programDir.getParent().resolve("DocFlow")); }
        candidates.add(Path.of(DEV_HOME));
        for (Path p : candidates)
            if (Files.isRegularFile(p.resolve("config").resolve("local.env")) && Files.isRegularFile(p.resolve("windows").resolve("start.ps1"))) return p;
        return null;
    }

    /** DocFlow's own settings (config\local.env): its port and service token. */
    static Properties localEnv(Path home) {
        Properties p = new Properties();
        try {
            for (String line : Files.readAllLines(home.resolve("config").resolve("local.env"), StandardCharsets.UTF_8)) {
                String l = line.replace("\uFEFF", "").trim();
                if (l.isEmpty() || l.startsWith("#") || !l.contains("=")) continue;
                p.setProperty(l.substring(0, l.indexOf('=')).trim(), l.substring(l.indexOf('=') + 1).trim());
            }
        } catch (IOException ignored) { }
        return p;
    }

    static String urlOf(Properties local) { return "http://127.0.0.1:" + local.getProperty("DOCAUTO_PORT", "8085").trim(); }

    /**
     * Records DocFlow's address and token for One CommandCenter (the installer's file) when nothing says where DocFlow
     * is yet; returns the address DocFlow is reached at here, or null when it is somewhere else or not set up.
     */
    static String configure(Path data, Vault vault, Path home, String settingUrl, Function<String, String> env) {
        Properties local = localEnv(home);
        String url = urlOf(local), token = local.getProperty("DOCAUTO_API_TOKEN", "");
        String envUrl = env.apply("OOA_DOCFLOW_URL");
        String chosen = !blank(settingUrl) ? settingUrl.trim() : !blank(envUrl) ? envUrl.trim() : DocFlowConfig.url(data);
        if (blank(chosen)) {
            if (token.isBlank()) return null;   // DocFlow without a token is not ready to be managed
            DocFlowConfig.save(data, vault, url, token);
            DocFlowConfig.setHome(data, home);
            return url;
        }
        // started here only when One CommandCenter is pointed at this computer's DocFlow
        String c = chosen.replaceAll("/+$", "").toLowerCase(Locale.ROOT).replace("localhost", "127.0.0.1");
        return c.equals(url) ? url : null;
    }

    /** True when something answers at the address. */
    static boolean answers(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) URI.create(url.replaceAll("/+$", "") + "/health").toURL().openConnection();
            c.setConnectTimeout(800); c.setReadTimeout(1500);
            int code = c.getResponseCode();
            c.disconnect();
            return code > 0;
        } catch (IOException | IllegalArgumentException e) { return false; }
    }

    /** How long a retry waits for DocFlow's own database to finish starting (it repairs itself after an unclean stop). */
    static long dbWaitMillis = 180_000;
    /** True from the first start until DocFlow answers or the launcher gives up: the screens say "starting". */
    static volatile boolean starting;

    /** Whether DocFlow is being started here and has not answered yet (14 Nov 2026: shown as "starting", not "not reachable"). */
    public static boolean isStarting() { return starting; }

    /**
     * Starts DocFlow in the background and stops it with the platform; failures are logged, never thrown. DocFlow's own
     * embedded database can take a minute or more to come up (it repairs itself after an unclean stop, and Windows can
     * hold its log file for 30 s): when DocFlow stops on its own without ever answering, the launcher waits until the
     * database says it is ready (up to {@link #dbWaitMillis}) and starts DocFlow again, at most three times.
     */
    static void start(Path home, Path data) { starting = true; start(home, data, urlOf(localEnv(home)), 3); }

    static synchronized void start(Path home, Path data, String url, int retries) {
        if (started != null && started.isAlive()) return;
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) return;   // Linux and containers run DocFlow themselves
        try {
            Path log = data.resolve("logs").resolve("docflow.log");
            Files.createDirectories(log.getParent());
            ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-WindowStyle", "Hidden", "-ExecutionPolicy", "Bypass",
                    "-File", home.resolve("windows").resolve("start.ps1").toString()).directory(home.toFile()).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
            started = pb.start();
            System.out.println("  Document Automation: DocFlow started from " + home + " (its log: " + log + ")");
            Process p = started;
            if (retries == 3) {
                Runtime.getRuntime().addShutdownHook(new Thread(() -> stop(started, home), "stop-docflow"));
                watchUntilAnswered(url);
            }
            p.onExit().thenRun(() -> {
                if (answers(url)) return;
                if (retries <= 0) { starting = false; System.out.println("  Document Automation: DocFlow did not start; see its log. Start it with "
                        + home.resolve("windows").resolve("start.bat") + "."); return; }
                Path pg = embeddedDb(home, localEnv(home));
                String state = pg == null ? null : dbState(pg);
                if (state != null && !"ready".equals(state)) {
                    System.out.println("  Document Automation: DocFlow stopped while its database was still starting (" + state
                            + "); waiting for the database, then starting DocFlow again.");
                    waitForDb(pg, dbWaitMillis);
                } else {
                    System.out.println("  Document Automation: DocFlow stopped while starting; starting it again (see its log).");
                    try { Thread.sleep(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                }
                if (!answers(url)) start(home, data, url, retries - 1);
            });
        } catch (IOException | RuntimeException e) {
            System.out.println("  Document Automation: DocFlow could not be started from " + home + ": " + e.getMessage()
                    + ". Start it with " + home.resolve("windows").resolve("start.bat") + ", or switch this off in Settings > Document Automation.");
        }
    }

    /** Clears "starting" once DocFlow answers (checked every 2 s, for as long as the launcher may still retry). */
    private static void watchUntilAnswered(String url) {
        Thread t = new Thread(() -> {
            long until = System.currentTimeMillis() + 4 * dbWaitMillis + 120_000;
            while (starting && System.currentTimeMillis() < until) {
                if (answers(url)) { starting = false; return; }
                try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
            }
            starting = false;
        }, "docflow-starting");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Stops what was started here: first DocFlow's own database, asked to shut down properly (so the next start needs no
     * repair), then DocFlow with everything it started.
     */
    static void stop(Process p, Path home) {
        if (p == null) return;
        try {
            Path pg = embeddedDb(home, localEnv(home));
            Path pgCtl = pg == null ? null : pgCtl(home);
            if (pgCtl != null && dbState(pg) != null) {
                Process s = new ProcessBuilder(pgCtl.toString(), "stop", "-D", pg.toString(), "-m", "fast", "-w", "-t", "20")
                        .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                s.waitFor(25, java.util.concurrent.TimeUnit.SECONDS);
            }
        } catch (IOException | RuntimeException e) { /* fall through to stopping DocFlow */ }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        p.toHandle().descendants().forEach(ProcessHandle::destroy);
        p.destroy();
    }

    /** DocFlow's embedded database folder (DOCAUTO_DATA_DIR, else home/data, then /postgres); null when it uses another database. */
    static Path embeddedDb(Path home, Properties local) {
        if (!blank(local.getProperty("DOCAUTO_DATABASE_URL"))) return null;
        String d = local.getProperty("DOCAUTO_DATA_DIR", "./data").trim();
        Path dir = Path.of(d.isEmpty() ? "./data" : d);
        return (dir.isAbsolute() ? dir : home.resolve(dir)).normalize().resolve("postgres");
    }

    /**
     * The embedded database's state from its postmaster.pid: "ready", "starting", "stopping" (or another word it
     * writes), or null when it is not running (no file, or its process has ended).
     */
    static String dbState(Path pg) {
        try {
            java.util.List<String> lines = Files.readAllLines(pg.resolve("postmaster.pid"), StandardCharsets.UTF_8);
            if (lines.isEmpty()) return null;
            long pid = Long.parseLong(lines.get(0).trim());
            if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) == Boolean.FALSE) return null;
            for (int i = lines.size() - 1; i > 0; i--) if (!lines.get(i).isBlank()) return lines.get(i).trim();
            return "starting";
        } catch (IOException | RuntimeException e) { return null; }
    }

    /** Waits until the database is ready or not running, at most the given time. */
    static void waitForDb(Path pg, long millis) {
        long until = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < until) {
            String s = dbState(pg);
            if (s == null || "ready".equals(s)) return;
            try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

    /** pg_ctl of the database DocFlow runs (its Python packages' pgserver), or null. */
    static Path pgCtl(Path home) {
        for (Path venv : java.util.List.of(home.resolve(".venv"), home.resolveSibling("DocFlowPython"))) {
            for (Path c : java.util.List.of(venv.resolve("Lib/site-packages/pgserver/pginstall/bin/pg_ctl.exe"),
                    venv.resolve("lib/site-packages/pgserver/pginstall/bin/pg_ctl.exe")))
                if (Files.isRegularFile(c)) return c;
        }
        return null;
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
}
