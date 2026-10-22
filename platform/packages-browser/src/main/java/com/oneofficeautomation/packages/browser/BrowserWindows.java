package com.oneofficeautomation.packages.browser;

import com.google.gson.JsonObject;
import com.microsoft.playwright.*;
import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import com.oneofficeautomation.sdk.JsonLite;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * The window choice of web element actions, and browsers reached through the DevTools protocol.
 *
 * Window format (all keys optional; values may contain $variables, which the Runner resolves first):
 * {"source": "browser" | "application" | "variable", "title": "Invoice * - Shop", "title_match": "wildcard" | "contains" | "equals" | "regex",
 *  "url": "shop.test/invoices", "url_match": "...", "browser": "edge" | "chrome", "variable": "session name"}
 *
 * - browser: the first tab, among every page of the run's browser sessions (own and attached), whose title and/or address
 *   matches. When nothing matches and a browser listens on the default debugging port 9222, it is attached automatically.
 * - variable: the browser session with that name.
 * - application: a desktop window title. A browser window's title is the tab title plus " - Microsoft Edge" or
 *   " - Google Chrome"; that suffix is removed and the rest is matched against the tabs as above. Any other desktop
 *   window cannot take web element actions, and the error points to the Desktop objects toolkit.
 */
final class BrowserWindows {
    private BrowserWindows() {}

    static final String DEFAULT_ENDPOINT = "http://127.0.0.1:9222";
    static final List<String> MATCHES = List.of("wildcard", "contains", "equals", "regex");

    static final String D_WINDOW = "Which window to act in. Leave empty to use the session's current tab. "
            + "{\"source\": \"browser\", \"title\": \"Invoice * - Shop\", \"title_match\": \"wildcard\", \"url\": \"...\", \"url_match\": \"contains\", \"browser\": \"edge\"} "
            + "switches to the first open tab whose title and/or address matches (also in attached browsers, and in one listening on port 9222); "
            + "{\"source\": \"variable\", \"variable\": \"session name\"} uses that browser session; "
            + "{\"source\": \"application\", \"title\": \"...\"} names a desktop window, which works only when it is a window of an attached browser. "
            + "Matches: wildcard (default for the title), contains (default for the address), equals or regex; text matches ignore case. Values may use variables.";

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(800)).build();

    /** The session to act in for this window choice; the matching tab becomes that session's current page. */
    /** Set while a step runs in the background: the chosen tab is used where it is, not brought to the front. */
    static final ThreadLocal<Boolean> BACKGROUND = ThreadLocal.withInitial(() -> false);

    /** As use, leaving the window where it is when inBackground is true. */
    static String use(ActionContext ctx, String session, Object windowArg, double timeoutMs, boolean inBackground) {
        BACKGROUND.set(inBackground);
        try { return use(ctx, session, windowArg, timeoutMs); }
        finally { BACKGROUND.remove(); }
    }

    static String use(ActionContext ctx, String session, Object windowArg, double timeoutMs) {
        Map<String, Object> w = window(windowArg);
        if (w == null) return session;
        String source = text(w.get("source"));
        if (source.isEmpty()) source = !text(w.get("variable")).isEmpty() ? "variable" : "browser";
        switch (source) {
            case "variable" -> {
                // a window variable (as the recorder makes): its value is a window choice
                if (w.get("variable") instanceof Map<?, ?> m && (m.get("title") != null || m.get("url") != null) && !(m.get("variable") != null))
                    return use(ctx, session, m, timeoutMs);
                return byVariable(ctx, w);
            }
            case "browser", "application" -> { return byTab(ctx, session, w, source.equals("application"), timeoutMs); }
            default -> throw new ActionException("BROWSER.BAD_WINDOW", "Unknown window source '" + source + "'", "Use browser, application or variable.");
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> window(Object arg) {
        if (arg == null) return null;
        Object v = arg instanceof String s ? (s.isBlank() ? null : s.trim().startsWith("{") ? JsonLite.parse(s) : Map.of("source", "variable", "variable", s)) : arg;
        if (v == null) return null;
        if (!(v instanceof Map<?, ?> m)) throw new ActionException("BROWSER.BAD_WINDOW", "The window must be a dictionary such as {\"source\": \"browser\", \"title\": \"...\"}");
        Map<String, Object> w = (Map<String, Object>) m;
        boolean any = false;
        for (String k : List.of("title", "url", "variable")) if (!text(w.get(k)).isEmpty()) any = true;
        return any ? w : null;
    }

    private static String byVariable(ActionContext ctx, Map<String, Object> w) {
        Object v = w.get("variable");
        if (v instanceof Map<?, ?> m) v = m.get("variable") != null ? m.get("variable") : m.get("session");
        if (v instanceof BrowserPackage.BrowserSession bs) {
            for (Map.Entry<String, Object> e : ctx.sessions().entrySet()) if (e.getValue() == bs) return e.getKey();
        }
        String name = text(v);
        if (ctx.sessions().get(name) instanceof BrowserPackage.BrowserSession || ctx.sessions().get(name) instanceof ExtensionBridge.TabSession) return name;
        throw new ActionException("BROWSER.WINDOW_NOT_FOUND", "There is no browser session named '" + name + "'. Open browser sessions: " + sessionNames(ctx),
                "Open or attach the browser first (open, attach or open_for_automation), or check the variable holds the session name.");
    }

    private static String byTab(ActionContext ctx, String preferred, Map<String, Object> w, boolean application, double timeoutMs) {
        String title = text(w.get("title")), url = text(w.get("url"));
        List<String> titles = application && !title.isEmpty() ? tabTitlesOf(title) : List.of(title);
        String titleMatch = match(w.get("title_match"), "wildcard"), urlMatch = match(w.get("url_match"), "contains");
        String kind = text(w.get("browser")).toLowerCase(Locale.ROOT);
        boolean caseSensitive = Boolean.TRUE.equals(w.get("case_sensitive")) || "true".equalsIgnoreCase(text(w.get("case_sensitive")));
        if (caseSensitive) titleMatch = titleMatch + ":case";
        long end = System.currentTimeMillis() + Math.max(0, (long) timeoutMs);
        boolean triedDefault = false;
        List<String> seen;
        while (true) {
            ctx.checkCancelled();
            seen = new ArrayList<>();
            for (String name : sessionOrder(ctx, preferred)) {
                BrowserPackage.BrowserSession bs = (BrowserPackage.BrowserSession) ctx.sessions().get(name);
                if (!kind.isEmpty() && !kind.equals(bs.kind)) continue;
                for (Page p : pages(bs)) {
                    String pt, pu;
                    try { pt = p.title(); pu = p.url(); } catch (PlaywrightException e) { continue; }
                    seen.add("'" + pt + "' (" + pu + ", session " + name + ")");
                    boolean titleOk = title.isEmpty();
                    for (String t : titles) if (!titleOk && matches(pt, t, titleMatch)) titleOk = true;
                    if (titleOk && (url.isEmpty() || matches(pu, url, urlMatch))) {
                        bs.page = p;
                        if (!BACKGROUND.get()) try { p.bringToFront(); } catch (PlaywrightException ignored) { }
                        if (!name.equals(preferred)) ctx.info("Using the tab '" + pt + "' of browser session '" + name + "'");
                        return name;
                    }
                }
            }
            // the person's own Edge or Chrome, through the browser connector
            String viaConnector = connectorTab(ctx, preferred, titles, title.isEmpty(), titleMatch, url, urlMatch, kind, seen);
            if (viaConnector != null) return viaConnector;
            if (!triedDefault) {
                triedDefault = true;
                autoAttachDefault(ctx);
                continue;
            }
            if (System.currentTimeMillis() >= end) break;
            try { Thread.sleep(250); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new ActionException("RUN.CANCELLED", "Interrupted"); }
        }
        String wanted = (title.isEmpty() ? "" : "title " + titleMatch + " '" + title + "'") + (title.isEmpty() || url.isEmpty() ? "" : " and ")
                + (url.isEmpty() ? "" : "address " + urlMatch + " '" + url + "'");
        String tabs = seen.isEmpty() ? "none" : String.join(", ", seen.subList(0, Math.min(20, seen.size()))) + (seen.size() > 20 ? ", ..." : "");
        String hint = application
                ? "Web element actions work only in browser tabs. For a desktop application use the Desktop objects toolkit (ooa.desktop_ui); "
                  + "for a browser that is already open, start it with open_for_automation or attach to it first."
                : "Check the title or address, or raise timeout_ms. A browser you started yourself is only reachable when it has a debugging port: "
                  + "start it with open_for_automation, then attach.";
        throw new ActionException("BROWSER.WINDOW_NOT_FOUND", "No " + (application ? "browser window" : "tab") + " with " + wanted + " within " + (long) timeoutMs
                + " ms. Open tabs: " + tabs, hint, true, Map.of("tabs", seen));
    }

    /** A tab of a browser connected through the extension that matches; registered as a session (the preferred name when free). */
    static String connectorTab(ActionContext ctx, String preferred, List<String> titles, boolean anyTitle, String titleMatch, String url, String urlMatch, String kind, List<String> seen) {
        if (!ExtensionBridge.available()) return null;
        // when several tabs match, the one the person looks at wins: the active tab of the focused window, then active tabs
        List<Map<String, Object>> all = new ArrayList<>(ExtensionBridge.tabs());
        all.sort(Comparator.comparingInt(t -> (Boolean.TRUE.equals(t.get("active")) && Boolean.TRUE.equals(t.get("focused")) ? 0 : Boolean.TRUE.equals(t.get("active")) ? 1 : 2)));
        for (Map<String, Object> t : all) {
            String b = text(t.get("browser"));
            if (!kind.isEmpty() && !kind.equals(b)) continue;
            String pt = text(t.get("title")), pu = text(t.get("url"));
            seen.add("'" + pt + "' (" + pu + ", " + b + ")");
            boolean titleOk = anyTitle;
            for (String x : titles) if (!titleOk && matches(pt, x, titleMatch)) titleOk = true;
            if (!titleOk || !(url.isEmpty() || matches(pu, url, urlMatch))) continue;
            long id = ((Number) t.get("tab_id")).longValue();
            String name = preferred != null && !(ctx.sessions().get(preferred) instanceof BrowserPackage.BrowserSession) ? preferred : "tab_" + id;
            boolean own = ctx.sessions().get(name) instanceof ExtensionBridge.TabSession old && old.tabId == id && old.own;
            ctx.sessions().put(name, new ExtensionBridge.TabSession(b, id, own));
            if (!BACKGROUND.get()) try { ExtensionBridge.call(b, "activate", Map.of("tab_id", id), 10000); } catch (ActionException ignored) { }
            return name;
        }
        return null;
    }

    private static final Pattern BROWSER_NAME = Pattern.compile("(?i)\\s+-\\s+(Microsoft\\W{0,2}Edge|Google Chrome|Chromium)\\s*$");
    private static final Pattern PROFILE = Pattern.compile("\\s+-\\s+[^-]+$");
    private static final Pattern MORE_PAGES = Pattern.compile("(?i)\\s+and \\d+ more pages?$");

    /**
     * The tab titles a desktop window title can stand for: "Invoice 7 - Microsoft Edge" gives "Invoice 7"; with a profile name
     * ("Invoice 7 - Work - Microsoft Edge") both "Invoice 7 - Work" and "Invoice 7" are tried, and "and 3 more pages" is dropped.
     * A title without a browser name stays as it is.
     */
    static List<String> tabTitlesOf(String windowTitle) {
        var m = BROWSER_NAME.matcher(windowTitle);
        if (!m.find()) return List.of(windowTitle);
        String rest = windowTitle.substring(0, m.start());
        LinkedHashSet<String> out = new LinkedHashSet<>();
        out.add(MORE_PAGES.matcher(rest).replaceFirst("").trim());
        var p = PROFILE.matcher(rest);
        if (p.find()) out.add(MORE_PAGES.matcher(rest.substring(0, p.start())).replaceFirst("").trim());
        return new ArrayList<>(out);
    }

    private static String match(Object v, String def) {
        String m = text(v).toLowerCase(Locale.ROOT);
        if (m.isEmpty()) return def;
        if (!MATCHES.contains(m)) throw new ActionException("BROWSER.BAD_WINDOW", "Unknown window match rule '" + m + "'", "Use one of " + MATCHES);
        return m;
    }

    /** Wildcard, contains and equals ignore case; regex is used as written (add (?i) to ignore case). */
    static boolean matches(String actual, String value, String match) {
        if (actual == null) return false;
        // "equals:case" and the like: the window title's Case sensitive switch
        boolean cs = match.endsWith(":case");
        if (cs) match = match.substring(0, match.length() - 5);
        String a = cs ? actual : actual.toLowerCase(Locale.ROOT), v = cs ? value : value.toLowerCase(Locale.ROOT);
        return switch (match) {
            case "contains" -> a.contains(v);
            case "equals" -> a.equals(v);
            case "regex" -> {
                try { yield Pattern.compile(value).matcher(actual).find(); }
                catch (java.util.regex.PatternSyntaxException e) { throw new ActionException("BROWSER.BAD_WINDOW", "Invalid regular expression '" + value + "': " + e.getDescription()); }
            }
            default -> a.matches(("\\Q" + v + "\\E").replace("*", "\\E.*\\Q").replace("?", "\\E.\\Q"));
        };
    }

    /** Browser sessions of this run, the step's own session first. */
    private static List<String> sessionOrder(ActionContext ctx, String preferred) {
        List<String> out = new ArrayList<>();
        if (preferred != null && ctx.sessions().get(preferred) instanceof BrowserPackage.BrowserSession) out.add(preferred);
        List<String> rest = new ArrayList<>();
        for (Map.Entry<String, Object> e : ctx.sessions().entrySet())
            if (e.getValue() instanceof BrowserPackage.BrowserSession && !e.getKey().equals(preferred)) rest.add(e.getKey());
        Collections.sort(rest);
        out.addAll(rest);
        return out;
    }

    private static String sessionNames(ActionContext ctx) {
        List<String> n = sessionOrder(ctx, null);
        return n.isEmpty() ? "none" : String.join(", ", n);
    }

    static List<Page> pages(BrowserPackage.BrowserSession bs) {
        List<Page> out = new ArrayList<>();
        try {
            if (!bs.browser.isConnected()) return out;
            for (BrowserContext c : bs.browser.contexts()) out.addAll(c.pages());
            if (!bs.browser.contexts().contains(bs.context)) out.addAll(bs.context.pages());
        } catch (PlaywrightException ignored) { }
        return out;
    }

    /** Attaches the browser on the default port 9222, unless a session already uses it or nothing listens there. */
    private static void autoAttachDefault(ActionContext ctx) {
        for (Object o : ctx.sessions().values())
            if (o instanceof BrowserPackage.BrowserSession bs && DEFAULT_ENDPOINT.equals(bs.endpoint)) return;
        Map<String, Object> version = version(DEFAULT_ENDPOINT);
        if (version == null) return;
        String name = "attached_9222";
        if (ctx.sessions().containsKey(name)) return;
        try {
            attach(ctx, DEFAULT_ENDPOINT, kindOf(version), name, 10_000);
            ctx.info("Attached to the browser listening on " + DEFAULT_ENDPOINT + " as session '" + name + "'");
        } catch (ActionException e) { ctx.warn("A browser listens on " + DEFAULT_ENDPOINT + " but could not be attached: " + e.getMessage()); }
    }

    // ---------- DevTools endpoints ----------

    /** "9222", "127.0.0.1:9222" or "http://host:9222/" become "http://host:9222". */
    static String endpoint(String e) {
        String s = e == null || e.isBlank() ? DEFAULT_ENDPOINT : e.trim();
        if (s.matches("\\d{1,5}")) s = "http://127.0.0.1:" + s;
        if (!s.matches("(?i)^(https?|wss?)://.*")) s = "http://" + s;
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /** /json/version of a DevTools endpoint, or null when nothing answers there. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> version(String endpoint) {
        Object v = getJson(endpoint, "/json/version");
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    static Object getJson(String endpoint, String path) {
        String base = endpoint.replaceFirst("(?i)^ws", "http");
        try {
            URI u = URI.create(base.replaceFirst("(https?://[^/]+).*", "$1") + path);
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(u).timeout(Duration.ofMillis(1500)).GET().build(), HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200 ? JsonLite.parse(r.body()) : null;
        } catch (IOException | RuntimeException e) { return null;
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
    }

    static String kindOf(Map<String, Object> version) {
        String b = text(version == null ? null : version.get("Browser"));
        if (b.startsWith("Edg") || b.contains("Edg/")) return "edge";
        if (b.contains("Chrome")) return "chrome";
        return "chromium";
    }

    /** Connects to a running Edge or Chrome over the DevTools protocol and registers it as a session. */
    static BrowserPackage.BrowserSession attach(ActionContext ctx, String endpoint, String kind, String session, double timeoutMs) {
        String ep = endpoint(endpoint);
        Map<String, Object> version = ep.toLowerCase(Locale.ROOT).startsWith("ws") ? null : version(ep);
        if (version == null && !ep.toLowerCase(Locale.ROOT).startsWith("ws"))
            throw new ActionException("BROWSER.NOT_LISTENING", "No browser answers on " + ep,
                    "A browser started the usual way has no debugging port. Start it with open_for_automation, or start it yourself with --remote-debugging-port=9222 and a separate --user-data-dir.", true);
        Playwright pw = null;
        try {
            pw = Playwright.create(new Playwright.CreateOptions().setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
            com.microsoft.playwright.Browser b = pw.chromium().connectOverCDP(ep, new BrowserType.ConnectOverCDPOptions().setTimeout(Math.max(1000, timeoutMs)));
            BrowserContext c = b.contexts().isEmpty() ? b.newContext() : b.contexts().get(0);
            Page page = null;
            for (Page p : c.pages()) { if (!p.url().startsWith("devtools://")) { page = p; break; } }
            if (page == null) page = c.newPage();
            String k = kind == null || kind.isBlank() ? kindOf(version) : kind;
            BrowserPackage.BrowserSession bs = new BrowserPackage.BrowserSession(pw, b, c, page, k, ep, true);
            Object old = ctx.sessions().put(session, bs);
            if (old instanceof BrowserPackage.BrowserSession ob && ob != bs) try { ob.close(); } catch (RuntimeException ignored) { }
            BrowserPackage.registerHooksPublic(ctx, session, bs);
            return bs;
        } catch (PlaywrightException e) {
            if (pw != null) try { pw.close(); } catch (RuntimeException ignored) { }
            throw BrowserPackage.pwPublic(e, "Attaching to " + ep);
        }
    }

    // ---------- Opening a window that stays open ----------

    /** Debugging port used for browsers that agents open: Edge 9222 (the one window lists look at), Chrome 9223. */
    static int keepOpenPort(String kind) { return "chrome".equals(kind) ? 9223 : 9222; }

    /**
     * Open browser with keep_open: a new window in the Edge or Chrome that listens on the agents' debugging port (started
     * with its own profile when none is running), used through the DevTools protocol. The run ending only disconnects,
     * so the window stays; Close browser closes the windows this session opened.
     */
    static BrowserPackage.BrowserSession openWindow(ActionContext ctx, String kind, String url, String session, double timeoutMs) {
        int port = keepOpenPort(kind);
        String ep = "http://127.0.0.1:" + port;
        boolean started = false;
        if (version(ep) == null) {
            if (!portFree(port)) throw new ActionException("BROWSER.PORT_IN_USE", "Port " + port + " is used by another program", "Close that program, or set keep_open to false.");
            Path dir = ctx.workDir().resolve(".ooa").resolve("browser-profiles").resolve(kind + "-" + port);
            start(executable(kind, null), port, dir, url, false);
            started = true;
            long end = System.currentTimeMillis() + (long) Math.max(1000, timeoutMs);
            while (version(ep) == null) {
                ctx.checkCancelled();
                if (System.currentTimeMillis() >= end)
                    throw new ActionException("BROWSER.NOT_LISTENING", "The browser started but did not open debugging port " + port + " within " + (long) timeoutMs + " ms",
                            "Close other windows that use the profile " + dir + ", or set keep_open to false.", true);
                try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new ActionException("RUN.CANCELLED", "Interrupted"); }
            }
        }
        BrowserPackage.BrowserSession bs = attach(ctx, ep, kind, session, timeoutMs);
        try {
            Page page;
            if (started) {
                page = bs.page;
            } else {
                // a new window, like opening the browser again, in the browser that is already running
                CDPSession cdp = bs.browser.newBrowserCDPSession();
                JsonObject params = new JsonObject();
                params.addProperty("url", "about:blank");
                params.addProperty("newWindow", true);
                page = bs.context.waitForPage(() -> cdp.send("Target.createTarget", params));
                try { cdp.detach(); } catch (PlaywrightException ignored) { }
                if (url != null && !url.isBlank()) page.navigate(url);
            }
            if (started && url != null && !url.isBlank() && !page.url().startsWith(url.replaceAll("/$", ""))) page.navigate(url);
            bs.page = page;
            page.bringToFront();
            bs.ownPages.add(page);
            ctx.info((started ? "Started " : "Opened a window in ") + kind + " (debugging port " + port + "); it stays open after the agent ends");
            return bs;
        } catch (PlaywrightException e) { throw BrowserPackage.pwPublic(e, "Opening a browser window"); }
    }

    // ---------- Starting a browser with a debugging port ----------

    static Path executable(String kind, String override) {
        if (override != null && !override.isBlank()) {
            Path p = Path.of(override);
            if (!Files.isRegularFile(p)) throw new ActionException("BROWSER.NOT_INSTALLED", "No browser program at " + p);
            return p;
        }
        List<String> c = new ArrayList<>();
        String pf = System.getenv().getOrDefault("ProgramFiles", "C:\\Program Files"), pf86 = System.getenv().getOrDefault("ProgramFiles(x86)", "C:\\Program Files (x86)");
        String local = System.getenv().getOrDefault("LOCALAPPDATA", "");
        if (kind.equals("edge")) {
            for (String root : List.of(pf86, pf, local)) if (!root.isEmpty()) c.add(root + "\\Microsoft\\Edge\\Application\\msedge.exe");
            c.addAll(List.of("/usr/bin/microsoft-edge", "/usr/bin/microsoft-edge-stable", "/opt/microsoft/msedge/msedge", "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge"));
        } else {
            for (String root : List.of(pf, pf86, local)) if (!root.isEmpty()) c.add(root + "\\Google\\Chrome\\Application\\chrome.exe");
            c.addAll(List.of("/usr/bin/google-chrome", "/usr/bin/google-chrome-stable", "/opt/google/chrome/chrome", "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"));
        }
        for (String s : c) { try { Path p = Path.of(s); if (Files.isRegularFile(p)) return p; } catch (RuntimeException ignored) { } }
        throw new ActionException("BROWSER.NOT_INSTALLED", (kind.equals("edge") ? "Microsoft Edge" : "Google Chrome") + " was not found on this computer",
                "Install it, choose the other browser, or give the program's path in executable.");
    }

    /** Starts the browser with a debugging port and its own profile; returns the process. */
    static Process start(Path exe, int port, Path profile, String url, boolean headless) {
        try { Files.createDirectories(profile); } catch (IOException e) { throw new ActionException("BROWSER.FILE_ERROR", "Cannot create the profile folder " + profile + ": " + e.getMessage()); }
        List<String> cmd = new ArrayList<>(List.of(exe.toString(), "--remote-debugging-port=" + port, "--user-data-dir=" + profile.toAbsolutePath(),
                "--no-first-run", "--no-default-browser-check", "--disable-features=msEdgeStartupBoost"));
        if (headless) cmd.add("--headless=new");
        cmd.add(url == null || url.isBlank() ? "about:blank" : url);
        try {
            return new ProcessBuilder(cmd).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException e) { throw new ActionException("BROWSER.NOT_INSTALLED", "Cannot start " + exe + ": " + e.getMessage()); }
    }

    static boolean portFree(int port) {
        try (java.net.ServerSocket s = new java.net.ServerSocket()) {
            s.setReuseAddress(false);
            s.bind(new java.net.InetSocketAddress("127.0.0.1", port));
            return true;
        } catch (IOException e) { return false; }
    }

    // ---------- Listing windows (design time) ----------

    /** Tabs reachable through these DevTools endpoints and the run's sessions: source, title, url, browser. */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> browserTabs(ActionContext ctx, List<String> endpoints) {
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> done = new HashSet<>();
        for (String name : sessionOrder(ctx, null)) {
            BrowserPackage.BrowserSession bs = (BrowserPackage.BrowserSession) ctx.sessions().get(name);
            if (bs.endpoint != null) { if (!done.add(bs.endpoint)) continue; }
            for (Page p : pages(bs)) {
                try { out.add(row("browser", p.title(), p.url(), bs.kind)); } catch (PlaywrightException ignored) { }
            }
        }
        for (String e : endpoints) {
            String ep = endpoint(e);
            if (!done.add(ep)) continue;
            Map<String, Object> version = version(ep);
            if (version == null) continue;
            String kind = kindOf(version);
            if (getJson(ep, "/json/list") instanceof List<?> l)
                for (Object o : l)
                    if (o instanceof Map<?, ?> m && "page".equals(m.get("type"))) { Map<String, Object> r = row("browser", text(m.get("title")), text(m.get("url")), kind); r.put("endpoint", ep); out.add(r); }
        }
        return out;
    }

    /** Top-level desktop windows with a title (Windows only; empty elsewhere or when PowerShell is unavailable). */
    static List<Map<String, Object>> desktopWindows(ActionContext ctx) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) return out;
        String script = "[Console]::OutputEncoding = [Text.Encoding]::UTF8; Get-Process | Where-Object { $_.MainWindowTitle } | "
                + "ForEach-Object { $_.ProcessName + [char]9 + $_.MainWindowTitle }";
        try {
            Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script).redirectErrorStream(true).start();
            p.getOutputStream().close();
            byte[] bytes;
            try (var in = p.getInputStream()) { bytes = in.readAllBytes(); }
            if (!p.waitFor(15, TimeUnit.SECONDS)) { p.destroyForcibly(); return out; }
            for (String line : new String(bytes, StandardCharsets.UTF_8).split("\\r?\\n")) {
                int tab = line.indexOf('\t');
                if (tab <= 0) continue;
                String proc = line.substring(0, tab).trim().replace("\uFEFF", ""), title = line.substring(tab + 1).trim();
                if (title.isEmpty()) continue;
                String browser = proc.equalsIgnoreCase("msedge") ? "edge" : proc.equalsIgnoreCase("chrome") ? "chrome" : "";
                out.add(row("application", title, "", browser));
            }
        } catch (IOException e) {
            if (ctx != null) ctx.warn("Desktop windows could not be listed: " + e.getMessage());
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return out;
    }

    private static Map<String, Object> row(String source, String title, String url, String browser) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", source); m.put("title", title == null ? "" : title); m.put("url", url == null ? "" : url); m.put("browser", browser == null ? "" : browser);
        return m;
    }

    static String text(Object o) { return o == null ? "" : String.valueOf(o).trim(); }
}
