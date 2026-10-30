package com.oneofficeautomation.packages.browser;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.oneofficeautomation.packages.UiGuard;
import com.oneofficeautomation.packages.ui.InterruptRules;
import com.oneofficeautomation.packages.ui.SelfHealing;
import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;

import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;

/**
 * Web automation with Playwright. Selectors: CSS (#id, .class), text="Sign in", role=button[name="Save"], or xpath=//...
 * Playwright waits automatically for elements to be ready, which makes web bots far more reliable than coordinate clicks.
 * By default the installed Microsoft Edge is used, so no browser download is needed on Windows.
 */
@OoaPackage(id = "ooa.browser", name = "Browser", version = "1.0.0",
        description = "Open web pages, click, type, select, read text and tables, run JavaScript, download files and take screenshots.")
public final class BrowserPackage {

    public static final class BrowserSession implements AutoCloseable {
        final Playwright pw; final com.microsoft.playwright.Browser browser; final BrowserContext context; Page page;
        /** edge, chrome, chromium or firefox. */
        final String kind;
        /** DevTools endpoint for an attached browser, otherwise null. */
        final String endpoint;
        /** An attached browser belongs to the person or to another agent: closing the session only disconnects. */
        final boolean attached;
        /** Windows this session opened in a browser that stays open after the run; Close browser closes them. */
        final List<Page> ownPages = new ArrayList<>();
        BrowserSession(Playwright pw, com.microsoft.playwright.Browser b, BrowserContext c, Page p) { this(pw, b, c, p, "edge", null, false); }
        BrowserSession(Playwright pw, com.microsoft.playwright.Browser b, BrowserContext c, Page p, String kind, String endpoint, boolean attached) {
            this.pw = pw; browser = b; context = c; page = p; this.kind = kind; this.endpoint = endpoint; this.attached = attached;
        }
        @Override public void close() {
            if (attached) { try { browser.close(); } finally { pw.close(); } return; }
            try { context.close(); } finally { try { browser.close(); } finally { pw.close(); } }
        }
    }

    private static BrowserSession s(ActionContext ctx, String name) {
        if (ctx.sessions().get(name) instanceof ExtensionBridge.TabSession)
            throw new ActionException("BROWSER.NOT_SUPPORTED", "This action is not available for a tab driven through the browser connector",
                    "Use the Capture element action, or open the browser with engine playwright for this agent.");
        return ctx.session(name, BrowserSession.class);
    }

    // ---------- the browser connector: the person's own Edge or Chrome, through the extension ----------
    static ExtensionBridge.TabSession tab(ActionContext ctx, String session) { return ctx.sessions().get(session) instanceof ExtensionBridge.TabSession t ? t : null; }

    static Object onTab(ExtensionBridge.TabSession t, String op, Map<String, Object> args, double timeoutMs) {
        Map<String, Object> a = new LinkedHashMap<>(args);
        a.put("tab_id", t.tabId);
        return ExtensionBridge.call(t.browser, op, a, (long) timeoutMs + 5000);
    }

    /** One element request through the extension: the captured object (or selector), the action, its value and options. */
    @SuppressWarnings("unchecked")
    static Object onElement(ActionContext ctx, ExtensionBridge.TabSession t, Object object, String sel, String action, Object value, double timeoutMs, Map<String, Object> opts) {
        UiGuard.before(ctx);
        Map<String, Object> a = new LinkedHashMap<>(opts);
        if (object != null) { Map<String, Object> o = new LinkedHashMap<>(WebObjects.object(object)); o.remove("screenshot"); a.put("object", o); }
        else if (sel != null && !sel.isBlank()) a.put("selector", sel);
        else throw new ActionException("BROWSER.NO_ELEMENT", "Give the captured object or a selector", "Use Capture in Agent Studio, or type a selector.");
        a.put("action", action);
        if (value != null) a.put("value", value instanceof SecureString ss ? ss.reveal() : value instanceof List<?> ? value : Values.text(value));
        a.put("timeout_ms", BigDecimal.valueOf((long) timeoutMs));
        Map<String, Object> r = (Map<String, Object>) onTab(t, "element", a, timeoutMs);
        if (r != null && r.get("healed_by") instanceof String h && !h.isBlank()) ctx.warn("Captured object found by its " + h + " (self-healing)");
        return r == null ? null : r.get("result");
    }

    private static ActionException pw(PlaywrightException e, String what) {
        String full = String.valueOf(e.getMessage());
        String m = full.split("\n")[0];
        // Playwright wraps its message as "Error {\n  message='...'": show the message itself
        var inner = java.util.regex.Pattern.compile("message='([^\\n']*)").matcher(full);
        if (m.trim().equals("Error {") && inner.find()) m = inner.group(1);
        if (e instanceof TimeoutError || m.contains("Timeout")) return new ActionException("BROWSER.TIMEOUT", what + " timed out: " + m,
                "Check the selector matches an element on the page, or raise timeout_ms.", true);
        if (m.contains("Executable doesn't exist") || m.contains("browserType.launch"))
            return new ActionException("BROWSER.NOT_INSTALLED", "The browser could not be started: " + m, "Use browser edge or chrome (installed), or allow Playwright to download Chromium.");
        return new ActionException("BROWSER.ERROR", what + " failed: " + m, null);
    }

    /** For helpers in this module (captured objects). */
    static ActionException pwPublic(PlaywrightException e, String what) { return pw(e, what); }

    private static final String D_SEL = "Element selector: CSS (#id, .class), text=\"Sign in\", role=button[name=\"Save\"] or xpath=//input[@name='q'].";
    private static final String D_OBJ = "The captured object from Capture or Pick in Agent Studio: the element's properties to match (each with use and a match rule: "
            + "equals, not_equals, contains, starts_with, ends_with, wildcard or regex), search_by (properties, css or xpath), index and iframes. Used instead of the selector when given.";
    private static final String D_SEL_OPT = "Element selector, used when no captured object is given: CSS (#id, .class), text=\"Sign in\", role=button[name=\"Save\"] or xpath=//input[@name='q'].";
    private static final String D_TIMEOUT = "How long to wait for the element, in milliseconds.";
    private static final String D_ALT = "Self-healing fallbacks recorded with the element (Pick records them): automation_id, name, id, aria_label, tag, class, index, text, "
            + "relative {anchor, direction}. Tried in that order when the selector finds nothing; the one that works is logged.";

    /** Lets the run's interrupt rules look for and act on elements in this browser session. */
    private static void registerHooks(ActionContext ctx, String session, BrowserSession bs) {
        UiGuard.register(ctx, session, new UiGuard.ElementHooks() {
            public boolean present(String selector) { Locator l = bs.page.locator(selector); return l.count() > 0 && l.first().isVisible(); }
            public void click(String selector) { bs.page.locator(selector).first().click(new Locator.ClickOptions().setTimeout(5000)); }
            public void press(String keys) { bs.page.keyboard().press(playwrightKeys(keys)); }
        });
    }

    static void registerHooksPublic(ActionContext ctx, String session, BrowserSession bs) { registerHooks(ctx, session, bs); }

    /** "CTRL+ENTER" or "ESC" in the desktop key style becomes Playwright's "Control+Enter" or "Escape". */
    static String playwrightKeys(String keys) {
        StringJoiner j = new StringJoiner("+");
        for (String part : keys.split("\\+")) {
            String p = part.trim();
            j.add(switch (p.toUpperCase(Locale.ROOT)) {
                case "CTRL", "CONTROL" -> "Control"; case "ALT" -> "Alt"; case "SHIFT" -> "Shift"; case "WIN", "META", "CMD" -> "Meta";
                case "ENTER", "RETURN" -> "Enter"; case "ESC", "ESCAPE" -> "Escape"; case "TAB" -> "Tab"; case "SPACE" -> "Space";
                case "BACKSPACE" -> "Backspace"; case "DELETE" -> "Delete"; case "HOME" -> "Home"; case "END" -> "End";
                case "PAGE_UP" -> "PageUp"; case "PAGE_DOWN" -> "PageDown"; case "UP" -> "ArrowUp"; case "DOWN" -> "ArrowDown"; case "LEFT" -> "ArrowLeft"; case "RIGHT" -> "ArrowRight";
                default -> p.length() == 1 ? p.toLowerCase(Locale.ROOT) : p;
            });
        }
        return j.toString();
    }

    /**
     * The element to act on. Checks the run's interrupt rules first. Without alternatives or rules this is the plain
     * Playwright locator (which waits by itself); otherwise the selector and its recorded alternatives are polled in order
     * until one matches, running the interrupt rules again on the first miss, and a healed match is logged.
     */
    /** The element from the captured object when given, otherwise from the selector (with its recorded alternatives). */
    static Locator target(ActionContext ctx, String session, Object object, String sel, Map<String, Object> alternatives, double timeoutMs) {
        if (object != null) return WebObjects.locate(ctx, s(ctx, session), WebObjects.object(object), timeoutMs, false);
        if (sel == null || sel.isBlank()) throw new ActionException("BROWSER.NO_ELEMENT", "Give the captured object or a selector", "Use Pick or Capture in Agent Studio, or type a selector.");
        return element(ctx, session, sel, alternatives, timeoutMs);
    }

    /** How the element is named in error messages. */
    static String what(Object object, String sel) {
        if (object == null) return sel;
        try { return "the captured object " + WebObjects.describe(WebObjects.object(object)); } catch (ActionException e) { return "the captured object"; }
    }

    /** Elements matching right now (no waiting), from the captured object or the selector. */
    static int countNow(ActionContext ctx, String session, Object object, String sel) {
        if (object != null) { Map<String, Object> o = WebObjects.object(object); return WebObjects.findAll(s(ctx, session).page, o).length; }
        if (sel == null || sel.isBlank()) throw new ActionException("BROWSER.NO_ELEMENT", "Give the captured object or a selector");
        int n = s(ctx, session).page.locator(sel).count();
        if (n > 0) ObjectRecorder.note(ctx, s(ctx, session).page.locator(sel).first(), sel, 0);
        return n;
    }

    static Locator element(ActionContext ctx, String session, String sel, Map<String, Object> alternatives, double timeoutMs) {
        Page page = s(ctx, session).page;
        UiGuard.before(ctx);
        InterruptRules rules = InterruptRules.existing(ctx);
        boolean noAlt = alternatives == null || alternatives.isEmpty();
        if (noAlt && (rules == null || rules.isEmpty())) return ObjectRecorder.note(ctx, page.locator(sel).first(), sel, timeoutMs);
        List<SelfHealing.Candidate> chain = SelfHealing.chain(new SelfHealing.Candidate("selector", sel), alternatives, SelfHealing::webSelector);
        try {
            return ObjectRecorder.note(ctx, SelfHealing.locate(chain, c -> { Locator l = s(ctx, session).page.locator(c.query()); return l.count() > 0 ? Optional.of(l.first()) : Optional.empty(); },
                    (long) timeoutMs, SelfHealing.SYSTEM, 250, () -> UiGuard.before(ctx), ctx::warn, ctx::checkCancelled).element(), sel, timeoutMs);
        } catch (ActionException e) {
            if (noAlt && "UI.ELEMENT_NOT_FOUND".equals(e.code()))
                throw new ActionException("BROWSER.TIMEOUT", "No element matches " + sel + " within " + (long) timeoutMs + " ms", "Check the selector matches an element on the page, or raise timeout_ms.", true);
            throw e;
        }
    }

    /**
     * Environment for the Playwright driver and the browsers it starts. A Windows compatibility layer inherited from
     * the program that started the runner (for example __COMPAT_LAYER=DetectorsAppHealth from a desktop app) makes
     * Edge and Chrome quit right after they start, so it is cleared for them.
     */
    static Map<String, String> browserEnv(Map<String, String> extra) {
        Map<String, String> env = new HashMap<>(extra);
        if (System.getenv("__COMPAT_LAYER") != null) env.put("__COMPAT_LAYER", "");
        return env;
    }

    /** A local file (C:\reports\day.html, or a path in the workspace) becomes its file: address; web addresses stay as they are. */
    static String localPage(ActionContext ctx, String url) {
        if (url == null || url.isBlank()) return url;
        String u = url.strip();
        boolean drive = u.matches("^[A-Za-z]:[\\\\/].*");
        if (!drive && u.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*")) return u;   // http:, https:, file:, about:, data: ...
        try {
            java.nio.file.Path p = ctx.resolvePath(u);
            if (drive || java.nio.file.Files.exists(p)) return p.toAbsolutePath().normalize().toUri().toString();
        } catch (java.nio.file.InvalidPathException ignored) { /* not a path */ }
        return u;
    }

    @Action(id = "open", name = "Open browser", category = "Browser", timeoutSeconds = 180, capabilities = "browser",
            description = "Start a browser, optionally open a URL, as a named session.",
            panel = "open_in, window, browser, url | download_folder, headless, keep_open, engine, session, timeout_ms")
    public Map<String, Object> open(ActionContext ctx,
            @Input(name = "url", type = OoaType.STRING, required = false, description = "Page to open.") String url,
            @Input(name = "browser", type = OoaType.STRING, required = false, defaultValue = "\"edge\"", enumValues = {"edge", "chrome", "chromium", "firefox"},
                    visibleWhen = "open_in=new_window", description = "edge and chrome use the installed browser; chromium and firefox are downloaded by Playwright.") String which,
            @Input(name = "headless", type = OoaType.BOOLEAN, required = false, defaultValue = "false", visibleWhen = "open_in=new_window", description = "Run without a visible window (for unattended agents).") boolean headless,
            @Input(name = "download_folder", type = OoaType.FILE, required = false, visibleWhen = "open_in=new_window", description = "Where downloads are saved.") String downloads,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"browser\"", description = "Session name.") String session,
            @Input(name = "keep_open", type = OoaType.BOOLEAN, required = false, defaultValue = "true",
                    description = "Edge and Chrome: leave the window open when the agent ends (only Close browser closes it), and let Studio and later agents reach it. "
                            + "False closes the browser with the run, as before.") boolean keepOpen,
            @Input(name = "engine", type = OoaType.STRING, required = false, defaultValue = "\"auto\"", enumValues = {"auto", "connector", "playwright"},
                    description = "connector: open a window in the person's own Edge, Chrome or Firefox through the ONEOFFICEAUTOMATION Browser Connector extension; "
                            + "playwright: a browser the agent starts itself; auto: the connector when that browser has it, else playwright.") String engine,
            @Input(name = "open_in", type = OoaType.STRING, required = false, defaultValue = "\"new_window\"", enumValues = {"new_window", "new_tab", "existing_tab"},
                    description = "new_window: a new browser window; new_tab: a new tab next to the chosen one; existing_tab: load the address in the chosen tab.") String openIn,
            @Input(name = "window", type = OoaType.DICTIONARY, required = false, visibleWhen = "open_in!=new_window", description = "For new_tab and existing_tab: the tab to use. " + BrowserWindows.D_WINDOW) Object window,
            @Input(name = "timeout_ms", type = OoaType.NUMBER, required = false, defaultValue = "240000", min = 1000, description = "How long to wait for the page to load.") double timeout) {
        url = localPage(ctx, url);
        if (openIn != null && !openIn.equals("new_window")) {
            String s = BrowserWindows.use(ctx, session, window, Math.min(timeout, 30000));
            if (!s.equals(session)) { Object found = ctx.sessions().get(s); ctx.sessions().put(session, found); }
            if (tab(ctx, session) != null) {
                ExtensionBridge.TabSession t = tab(ctx, session);
                @SuppressWarnings("unchecked")
                Map<String, Object> r = (Map<String, Object>) onTab(t, "open", Map.of("mode", openIn, "url", url == null || url.isBlank() ? "about:blank" : url, "timeout_ms", BigDecimal.valueOf((long) timeout)), timeout);
                t.tabId = ((Number) r.get("tab_id")).longValue();
                return Map.of();
            }
            BrowserSession bs = s(ctx, session);
            try {
                if (openIn.equals("new_tab")) { bs.page = bs.context.newPage(); bs.ownPages.add(bs.page); }
                if (url != null && !url.isBlank()) bs.page.navigate(url, new Page.NavigateOptions().setTimeout(timeout));
                bs.page.bringToFront();
            } catch (PlaywrightException e) { throw pw(e, "Opening " + url); }
            return Map.of();
        }
        if ("headless".equals(System.getProperty("ooa.runMode"))) headless = true;   // a headless run has no desktop to show a window on
        boolean installed = which.equals("edge") || which.equals("chrome");
        // Firefox counts as the person's own browser when its connector is there (Playwright would download another Firefox)
        boolean connector ="connector".equals(engine) || ("auto".equals(engine == null ? "auto" : engine) && (installed || which.equals("firefox")) && !headless
                && downloads == null && keepOpen && ExtensionBridge.browsers().contains(which));
        if (connector) {
            @SuppressWarnings("unchecked")
            Map<String, Object> t = (Map<String, Object>) ExtensionBridge.call(which, "open", Map.of("mode", "new_window", "url", url == null || url.isBlank() ? "about:blank" : url), 90000);
            Object old = ctx.sessions().put(session, new ExtensionBridge.TabSession(which, ((Number) t.get("tab_id")).longValue(), true));
            if (old instanceof AutoCloseable c && !(old instanceof ExtensionBridge.TabSession)) try { c.close(); } catch (Exception ignored) { }
            ctx.info("Opened a window in " + which + " through the browser connector: " + t.get("title"));
            return Map.of();
        }
        if (keepOpen && installed && !headless && downloads == null) {
            BrowserWindows.openWindow(ctx, which, url, session, 30000);
            return Map.of();
        }
        launchPlaywright(ctx, which, headless, downloads, session, url, null, null);
        return Map.of();
    }

    /**
     * Starts a browser with Playwright as the named session and opens the address. proxy (or null) routes its traffic
     * through a proxy server; executable (or null) starts that browser program instead of the installed or downloaded one.
     */
    static void launchPlaywright(ActionContext ctx, String which, boolean headless, String downloads, String session, String url,
            com.microsoft.playwright.options.Proxy proxy, Path executable) {
        boolean installed = which.equals("edge") || which.equals("chrome");
        Map<String, String> env = new HashMap<>();
        if (installed || executable != null) env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
        Playwright pw = null;
        try {
            pw = Playwright.create(new Playwright.CreateOptions().setEnv(browserEnv(env)));
            BrowserType type = which.equals("firefox") ? pw.firefox() : pw.chromium();
            BrowserType.LaunchOptions lo = new BrowserType.LaunchOptions().setHeadless(headless);
            if (executable != null) lo.setExecutablePath(executable);
            else if (which.equals("edge")) lo.setChannel("msedge");
            else if (which.equals("chrome")) lo.setChannel("chrome");
            if (proxy != null) lo.setProxy(proxy);
            if (downloads != null) lo.setDownloadsPath(ctx.resolvePath(downloads));
            com.microsoft.playwright.Browser b = type.launch(lo);
            BrowserContext c = b.newContext(new com.microsoft.playwright.Browser.NewContextOptions().setAcceptDownloads(true));
            Page p = c.newPage();
            if (url != null && !url.isBlank()) p.navigate(url);
            BrowserSession bs = new BrowserSession(pw, b, c, p, which, null, false);
            ctx.sessions().put(session, bs);
            registerHooks(ctx, session, bs);
        } catch (PlaywrightException e) {
            if (pw != null) pw.close();
            throw pw(e, "Opening the browser");
        }
    }

    @Action(id = "navigate", name = "Go to URL", category = "Browser", timeoutSeconds = 120, capabilities = "browser", description = "Open a URL in the current tab and wait for it to load.",
            panel = "url | session")
    public Map<String, Object> navigate(ActionContext ctx,
            @Input(name = "url", type = OoaType.STRING, description = "Address to open.") String url,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"browser\"", description = "Session name.") String session) {
        url = localPage(ctx, url);   // a local file path works here too, as in Open browser
        if (tab(ctx, session) != null) { onTab(tab(ctx, session), "navigate", Map.of("url", url), 60000); return Map.of(); }
        try { s(ctx, session).page.navigate(url); } catch (PlaywrightException e) { throw pw(e, "Opening " + url); }
        return Map.of();
    }

    /** The files of an upload_file value: a list, or paths separated by ; or new lines, relative to the workspace; each must exist. */
    static List<Path> uploadFiles(ActionContext ctx, Object value) {
        List<Path> files = new ArrayList<>();
        if (value instanceof List<?> list) for (Object o : list) files.add(ctx.workDir().resolve(Values.text(o)));
        else if (value != null) for (String f : String.valueOf(value).split("[;\n]")) if (!f.isBlank()) files.add(ctx.workDir().resolve(f.trim()));
        if (files.isEmpty()) throw new ActionException("BROWSER.BAD_INPUT", "upload_file needs the file path(s) in value");
        for (Path f : files) if (!Files.isRegularFile(f)) throw new ActionException("BROWSER.FILE_NOT_FOUND", "File not found: " + f);
        return files;
    }

    /** Click's "then wait for": the page loaded, or an element shown or gone. */
    private void afterClick(ActionContext ctx, String session, String then, String waitSelector, double timeout) {
        switch (then) {
            case "page_load" -> {
                sleep(ctx, 300);   // let a navigation the click started begin
                if (tab(ctx, session) != null) {
                    long end = System.currentTimeMillis() + (long) timeout;
                    while (!"complete".equals(String.valueOf(onTab(tab(ctx, session), "run_js", Map.of("code", "return document.readyState"), 30000)))) {
                        ctx.checkCancelled();
                        if (System.currentTimeMillis() > end) throw new ActionException("BROWSER.TIMEOUT", "The page did not finish loading after the click", "Raise timeout_ms.");
                        sleep(ctx, 200);
                    }
                } else {
                    try { s(ctx, session).page.waitForLoadState(com.microsoft.playwright.options.LoadState.LOAD, new Page.WaitForLoadStateOptions().setTimeout(timeout)); }
                    catch (PlaywrightException e) { throw pw(e, "Waiting for the page to load after the click"); }
                }
            }
            case "element_visible" -> waitFor(ctx, null, waitSelector, "visible", timeout, session, null);
            case "element_gone" -> waitFor(ctx, null, waitSelector, "hidden", timeout, session, null);
            default -> { }
        }
    }

    /** Click element without "then wait for" (the signature before it was added). */
    public Map<String, Object> click(ActionContext ctx, Object object, String sel, boolean dbl, double timeout, String session, Map<String, Object> alternatives, Object window,
                                     String clickMethod, boolean force) {
        return click(ctx, object, sel, dbl, timeout, session, alternatives, window, clickMethod, force, "nothing", null);
    }

    @Action(id = "click", name = "Click element", category = "Elements", timeoutSeconds = 120, capabilities = "browser", description = "Click an element (waits until it is visible and enabled).",
            panel = "window, object, selector, double_click, then_wait_for, wait_selector | click_method, force, alternatives, session, timeout_ms")
    public Map<String, Object> click(ActionContext ctx,
            @Input(name = "object", type = OoaType.DICTIONARY, required = false, description = D_OBJ) Object object,
            @Input(name = "selector", type = OoaType.STRING, required = false, description = D_SEL_OPT) String sel,
            @Input(name = "double_click", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Double-click.") boolean dbl,
            @Input(name = "timeout_ms", type = OoaType.NUMBER, required = false, defaultValue = "30000", min = 0, description = D_TIMEOUT) double timeout,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"browser\"", description = "Session name.") String session,
            @Input(name = "alternatives", type = OoaType.DICTIONARY, required = false, description = D_ALT) Map<String, Object> alternatives,
            @Input(name = "window", type = OoaType.DICTIONARY, required = false, description = BrowserWindows.D_WINDOW) Object window,
            @Input(name = "click_method", type = OoaType.STRING, required = false, defaultValue = "\"mouse\"", enumValues = {"mouse", "javascript"},
                    description = "mouse: a real click (scrolls the element into view and waits until it can be clicked); javascript: fire the click on the element itself, for elements covered by an advert or a banner.") String clickMethod,
            @Input(name = "force", type = OoaType.BOOLEAN, required = false, defaultValue = "false",
                    description = "Mouse click without waiting until nothing covers the element.") boolean force,
            @Input(name = "then_wait_for", type = OoaType.STRING, required = false, defaultValue = "\"nothing\"", enumValues = {"nothing", "page_load", "element_visible", "element_gone"},
                    description = "After the click, wait until the page has loaded, or until the element in wait_selector appears or is gone (for example a spinner): "
                        + "no fixed Delay needed when Next keeps the same page.") String thenWaitFor,
            @Input(name = "wait_selector", type = OoaType.STRING, required = false,
                    description = "With then_wait_for element_visible or element_gone: the element to wait for (a selector).") String waitSelector) {
        session = BrowserWindows.use(ctx, session, window, timeout);
        boolean js = "javascript".equals(clickMethod);
        String then = thenWaitFor == null || thenWaitFor.isBlank() ? "nothing" : thenWaitFor;
        if (then.startsWith("element_") && (waitSelector == null || waitSelector.isBlank()))
            throw new ActionException("BROWSER.BAD_INPUT", "Then wait for " + then.replace('_', ' ') + " needs wait_selector", "Give the selector of the element to wait for.");
        if (tab(ctx, session) != null) {
            onElement(ctx, tab(ctx, session), object, sel, dbl ? "double_click" : "click", null, timeout, Map.of("click_method", js ? "javascript" : "mouse"));
            afterClick(ctx, session, then, waitSelector, timeout);
            return Map.of();
        }
        try {
            Locator l = target(ctx, session, object, sel, alternatives, timeout);
            if (js) {
                l.waitFor(new Locator.WaitForOptions().setTimeout(timeout).setState(WaitForSelectorState.ATTACHED));
                l.evaluate(dbl ? "e => { e.click(); e.click(); e.dispatchEvent(new MouseEvent('dblclick', {bubbles: true})); }" : "e => e.click()");
            } else if (dbl) l.dblclick(new Locator.DblclickOptions().setTimeout(timeout).setForce(force));
            else l.click(new Locator.ClickOptions().setTimeout(timeout).setForce(force));
        } catch (PlaywrightException e) { throw pw(e, "Clicking " + what(object, sel)); }
        afterClick(ctx, session, then, waitSelector, timeout);
        return Map.of();
    }

    /** Click with a mouse click, as before the click method existed. */
    public Map<String, Object> click(ActionContext ctx, Object object, String sel, boolean dbl, double timeout, String session, Map<String, Object> alternatives, Object window) {
        return click(ctx, object, sel, dbl, timeout, session, alternatives, window, "mouse", false);
    }

    @Action(id = "type_text", name = "Set text", category = "Elements", timeoutSeconds = 120, capabilities = "browser",
            description = "Fill a text field (replacing its content); Credential values are typed without ever being logged.",
            panel = "window, object, selector, text, press_enter | alternatives, session, timeout_ms")
    public Map<String, Object> typeText(ActionContext ctx,
            @Input(name = "object", type = OoaType.DICTIONARY, required = false, description = D_OBJ) Object object,
            @Input(name = "selector", type = OoaType.STRING, required = false, description = D_SEL_OPT) String sel,
            @Input(name = "text", description = "Text, or a Credential variable for passwords.") Object text,
            @Input(name = "press_enter", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Press Enter afterwards.") boolean enter,
            @Input(name = "timeout_ms", type = OoaType.NUMBER, required = false, defaultValue = "30000", min = 0, description = D_TIMEOUT) double timeout,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"browser\"", description = "Session name.") String session,
            @Input(name = "alternatives", type = OoaType.DICTIONARY, required = false, description = D_ALT) Map<String, Object> alternatives,
            @Input(name = "window", type = OoaType.DICTIONARY, required = false, description = BrowserWindows.D_WINDOW) Object window) {
        session = BrowserWindows.use(ctx, session, window, timeout);
        String value = text instanceof SecureString ss ? ss.reveal() : Values.text(text);
        if (tab(ctx, session) != null) {
            // the connector cannot tell field types: password-only values stay with Playwright sessions
            SecureString.checkPasswordField(text, false, what(object, sel));
            onElement(ctx, tab(ctx, session), object, sel, "set_text", value, timeout, Map.of());
            if (enter) onElement(ctx, tab(ctx, session), object, sel, "press_key", "Enter", timeout, Map.of());
            return Map.of();
        }
        try {
            Locator l = target(ctx, session, object, sel, alternatives, timeout);
            if (text instanceof SecureString ss && ss.passwordOnly())
                SecureString.checkPasswordField(text, "password".equals(l.evaluate("e => (e.getAttribute('type') || '').toLowerCase()")), what(object, sel));
            l.fill(value, new Locator.FillOptions().setTimeout(timeout));
            if (enter) l.press("Enter");
        } catch (PlaywrightException e) { throw pw(e, "Typing into " + what(object, sel)); }
        return Map.of();
    }

    @Action(id = "select_option", name = "Select item", category = "Elements", timeoutSeconds = 120, capabilities = "browser", description = "Choose an option in a drop-down list by its value or visible text.",
            panel = "window, object, selector, option | alternatives, session, timeout_ms")
    public Map<String, Object> select(ActionContext ctx,
            @Input(name = "object", type = OoaType.DICTIONARY, required = false, description = D_OBJ) Object object,
            @Input(name = "selector", type = OoaType.STRING, required = false, description = D_SEL_OPT) String sel,
            @Input(name = "option", type = OoaType.STRING, description = "Option value or visible text.") String option,
            @Input(name = "timeout_ms", type = OoaType.NUMBER, required = false, defaultValue = "30000", min = 0, description = D_TIMEOUT) double timeout,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"browser\"", description = "Session name.") String session,
            @Input(name = "alternatives", type = OoaType.DICTIONARY, required = false, description = D_ALT) Map<String, Object> alternatives,
            @Input(name = "window", type = OoaType.DICTIONARY, required = false, description = BrowserWindows.D_WINDOW) Object window) {
        session = BrowserWindows.use(ctx, session, window, timeout);
        if (tab(ctx, session) != null) {
            try { onElement(ctx, tab(ctx, session), object, sel, "select_by_text", option, timeout, Map.of()); }
            catch (ActionException e) { if (!"BROWSER.NO_ITEM".equals(e.code())) throw e; onElement(ctx, tab(ctx, session), object, sel, "select_by_value", option, timeout, Map.of()); }
            return Map.of();
        }
        try {
            Locator l = target(ctx, session, object, sel, alternatives, timeout);
            List<String> chosen = l.selectOption(option, new Locator.SelectOptionOptions().setTimeout(timeout));
            if (chosen.isEmpty()) l.selectOption(new com.microsoft.playwright.options.SelectOption().setLabel(option), new Locator.SelectOptionOptions().setTimeout(timeout));
        } catch (PlaywrightException e) { throw pw(e, "Selecting '" + option + "' in " + what(object, sel)); }
        return Map.of();
    }

    @Action(id = "get_text", name = "Get text", category = "Elements", timeoutSeconds = 120, capabilities = "browser", description = "Read the visible text of an element.",
            panel = "window, object, selector | alternatives, session, timeout_ms, if_not_found")
    @Output(name = "text", type = OoaType.STRING, description = "Element text; empty when the element was not found and if_not_found is continue.")
    @Output(name = "found", type = OoaType.BOOLEAN, description = "False when the element was not there in time (if_not_found = continue).")
    public Map<String, Object> getText(ActionContext ctx,
            @Input(name = "object", type = OoaType.DICTIONARY, required = false, description = D_OBJ) Object object,
            @Input(name = "selector", type = OoaType.STRING, required = false, description = D_SEL_OPT) String sel,
            @Input(name = "timeout_ms", type = OoaType.NUMBER, required = false, defaultValue = "30000", min = 0, description = D_TIMEOUT) double timeout,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"browser\"", description = "Session name.") String session,
            @Input(name = "alternatives", type = OoaType.DICTIONARY, required = false, description = D_ALT) Map<String, Object> alternatives,
            @Input(name = "window", type = OoaType.DICTIONARY, required = false, description = BrowserWindows.D_WINDOW) Object window,
            @Input(name = "if_not_found", type = OoaType.STRING, required = false, defaultValue = "\"error\"", enumValues = {"error", "continue"}, description = D_IF_NOT_FOUND) String ifNotFound) {
        return orEmpty(ifNotFound, "text", () -> getText(ctx, object, sel, timeout, session, alternatives, window));
    }

    /** Get text that stops with an error when the element is not there. */
    public Map<String, Object> getText(ActionContext ctx, Object object, String sel, double timeout, String session, Map<String, Object> alternatives, Object window) {
        session = BrowserWindows.use(ctx, session, window, timeout);
        if (tab(ctx, session) != null) return Map.of("text", String.valueOf(onElement(ctx, tab(ctx, session), object, sel, "get_text", null, timeout, Map.of())));
        try { return Map.of("text", target(ctx, session, object, sel, alternatives, timeout).innerText(new Locator.InnerTextOptions().setTimeout(timeout)).strip()); }
        catch (PlaywrightException e) { throw pw(e, "Reading " + what(object, sel)); }
    }

    @Action(id = "get_attribute", name = "Get attribute", category = "Elements", timeoutSeconds = 120, capabilities = "browser", description = "Read an attribute of an element, e.g. href or value.",
            panel = "window, object, selector, attribute | session, timeout_ms, if_not_found")
    @Output(name = "value", type = OoaType.STRING, description = "Attribute value, empty when missing.")
    @Output(name = "found", type = OoaType.BOOLEAN, description = "False when the element was not there in time (if_not_found = continue).")
    public Map<String, Object> getAttribute(ActionContext ctx,
            @Input(name = "object", type = OoaType.DICTIONARY, required = false, description = D_OBJ) Object object,
            @Input(name = "selector", type = OoaType.STRING, required = false, description = D_SEL_OPT) String sel,
            @Input(name = "attribute", type = OoaType.STRING, example = "href", description = "Attribute name; use value for input fields.") String attr,
            @Input(name = "timeout_ms", type = OoaType.NUMBER, required = false, defaultValue = "30000", min = 0, description = D_TIMEOUT) double timeout,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"browser\"", description = "Session name.") String session,
            @Input(name = "window", type = OoaType.DICTIONARY, required = false, description = BrowserWindows.D_WINDOW) Object window,
            @Input(name = "if_not_found", type = OoaType.STRING, required = false, defaultValue = "\"error\"", enumValues = {"error", "continue"}, description = D_IF_NOT_FOUND) String ifNotFound) {
        return orEmpty(ifNotFound, "value", () -> getAttribute(ctx, object, sel, attr, timeout, session, window));
    }

    /** Get attribute that stops with an error when the element is not there. */
    public Map<String, Object> getAttribute(ActionContext ctx, Object object, String sel, String attr, double timeout, String session, Object window) {
        session = BrowserWindows.use(ctx, session, window, timeout);
        if (tab(ctx, session) != null) {
            Object v = onElement(ctx, tab(ctx, session), object, sel, attr.equals("value") ? "get_value" : "get_attribute", attr.equals("value") ? null : attr, timeout, Map.of());
            return Map.of("value", v == null ? "" : String.valueOf(v));
        }
        try {
            Locator l = target(ctx, session, object, sel, null, timeout);
            String v = attr.equals("value") ? l.inputValue(new Locator.InputValueOptions().setTimeout(timeout)) : l.getAttribute(attr, new Locator.GetAttributeOptions().setTimeout(timeout));
            return Map.of("value", v == null ? "" : v);
        } catch (PlaywrightException e) { throw pw(e, "Reading " + attr + " of " + what(object, sel)); }
    }

    @Action(id = "wait_for", name = "Wait for element", category = "Elements", timeoutSeconds = 600, capabilities = "browser", description = "Wait until an element appears, becomes visible, hidden or is removed.",
            panel = "window, object, selector, state | any_of, session, timeout_ms")
    @Output(name = "which", type = OoaType.NUMBER, description = "With any_of: which element was found, 0 for the element, 1 for the first of the others.")
    public Map<String, Object> waitFor(ActionContext ctx,
            @Input(name = "object", type = OoaType.DICTIONARY, required = false, description = D_OBJ) Object object,
            @Input(name = "selector", type = OoaType.STRING, required = false, description = D_SEL_OPT) String sel,
            @Input(name = "state", type = OoaType.STRING, required = false, defaultValue = "\"visible\"", enumValues = {"visible", "attached", "hidden", "detached"}, description = "State to wait for.") String state,
            @Input(name = "timeout_ms", type = OoaType.NUMBER, required = false, defaultValue = "30000", min = 0, description = D_TIMEOUT) double timeout,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"browser\"", description = "Session name.") String session,
            @Input(name = "window", type = OoaType.DICTIONARY, required = false, description = BrowserWindows.D_WINDOW) Object window,
            @Input(name = "any_of", type = OoaType.LIST, required = false,
                    description = "Other elements (captured objects or selectors): the wait ends when the element or any of these is visible (or there). Output which tells which one: 0 for the element, 1 for the first of these, and so on.") List<Object> anyOf) {
        session = BrowserWindows.use(ctx, session, window, timeout);
        if (anyOf != null && !anyOf.isEmpty() && !"hidden".equals(state) && !"detached".equals(state) && tab(ctx, session) == null)
            return waitForAny(ctx, session, object, sel, anyOf, "visible".equals(state), timeout);
        if (tab(ctx, session) != null) {
            String how = switch (state) { case "hidden" -> "wait_to_disappear"; case "detached" -> "wait_detached"; default -> "wait_to_appear"; };
            onElement(ctx, tab(ctx, session), object, sel, how, null, timeout, Map.of());
            return Map.of();
        }
        try {
            UiGuard.before(ctx);
            WaitForSelectorState st = WaitForSelectorState.valueOf(state.toUpperCase(Locale.ROOT));
            if (object == null) {
                if (sel == null || sel.isBlank()) throw new ActionException("BROWSER.NO_ELEMENT", "Give the captured object or a selector");
                s(ctx, session).page.locator(sel).first().waitFor(new Locator.WaitForOptions().setTimeout(timeout).setState(st));
                if (st == WaitForSelectorState.VISIBLE || st == WaitForSelectorState.ATTACHED) ObjectRecorder.note(ctx, s(ctx, session).page.locator(sel).first(), sel, 0);
            } else if (st == WaitForSelectorState.VISIBLE || st == WaitForSelectorState.ATTACHED) {
                Locator l = target(ctx, session, object, null, null, timeout);
                l.waitFor(new Locator.WaitForOptions().setTimeout(timeout).setState(st));
            } else {
                long end = System.currentTimeMillis() + (long) timeout;
                Map<String, Object> o = WebObjects.object(object);
                while (true) {
                    ctx.checkCancelled();
                    Locator[] found = WebObjects.findAll(s(ctx, session).page, o);
                    boolean gone = st == WaitForSelectorState.DETACHED ? found.length == 0 : java.util.Arrays.stream(found).noneMatch(Locator::isVisible);
                    if (gone) break;
                    if (System.currentTimeMillis() >= end) throw new ActionException("BROWSER.TIMEOUT", what(object, null) + " is still on the page after " + (long) timeout + " ms", "Raise timeout_ms.", true);
                    s(ctx, session).page.waitForTimeout(250);
                }
            }
        } catch (PlaywrightException e) { throw pw(e, "Waiting for " + what(object, sel)); }
        return Map.of("which", BigDecimal.ZERO);
    }

    /** Wait for element without the any-of choice. */
    public Map<String, Object> waitFor(ActionContext ctx, Object object, String sel, String state, double timeout, String session, Object window) {
        return waitFor(ctx, object, sel, state, timeout, session, window, null);
    }

    /** Waits until the element or one of the others is there (and visible): the number of the first one found, 0 for the element. */
    private Map<String, Object> waitForAny(ActionContext ctx, String session, Object object, String sel, List<Object> anyOf, boolean visible, double timeout) {
        List<Object> all = new ArrayList<>();
        all.add(object != null ? object : sel);
        all.addAll(anyOf);
        long end = System.currentTimeMillis() + (long) timeout;
        try {
            while (true) {
                ctx.checkCancelled();
                UiGuard.before(ctx);
                for (int i = 0; i < all.size(); i++) {
                    Object one = all.get(i);
                    if (one == null || (one instanceof String str && str.isBlank())) continue;
                    Locator[] found = one instanceof String str ? new Locator[]{ s(ctx, session).page.locator(str).first() }
                            : WebObjects.findAll(s(ctx, session).page, WebObjects.object(one));
                    for (Locator l : found) if (l.count() > 0 && (!visible || l.isVisible())) return Map.of("which", BigDecimal.valueOf(i));
                }
                if (System.currentTimeMillis() >= end)
                    throw new ActionException("BROWSER.TIMEOUT", "None of the " + all.size() + " elements appeared within " + (long) timeout + " ms", "Raise timeout_ms, or check the elements.", true);
                s(ctx, session).page.waitForTimeout(250);
            }
        } catch (PlaywrightException e) { throw pw(e, "Waiting for any of " + all.size() + " elements"); }
    }

    @Condition(id = "element_exists", name = "Element exists", description = "True when at least one element matches the selector (right away, or within the wait).")
    public boolean exists(ActionContext ctx,
            @Input(name = "object", type = OoaType.DICTIONARY, required = false, description = D_OBJ) Object object,
            @Input(name = "selector", type = OoaType.STRING, required = false, description = D_SEL_OPT) String sel,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"browser\"", description = "Session name.") String session,
            @Input(name = "window", type = OoaType.DICTIONARY, required = false, description = BrowserWindows.D_WINDOW) Object window,
            @Input(name = "wait_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0, description = D_COND_WAIT) double waitSeconds) {
        return waitUntil(ctx, waitSeconds, () -> exists(ctx, object, sel, session, window));
    }

    /** Element exists right now. */
    public boolean exists(ActionContext ctx, Object object, String sel, String session, Object window) {
        try { session = BrowserWindows.use(ctx, session, window, 0); } catch (ActionException e) { if ("BROWSER.WINDOW_NOT_FOUND".equals(e.code())) return false; throw e; }
        if (tab(ctx, session) != null) return ((Number) onElement(ctx, tab(ctx, session), object, sel, "count", null, 0, Map.of())).intValue() > 0;
        try { return countNow(ctx, session, object, sel) > 0; } catch (PlaywrightException e) { throw pw(e, "Checking " + what(object, sel)); }
    }

    @Action(id = "extract_table", name = "Extract table", category = "Data", timeoutSeconds = 600, capabilities = "browser",
            description = "Read an HTML table into a Data Table (first row as column names), or chosen columns of any repeated rows (a grid or list) by XPath.",
            panel = "window, object, selector, [Choose the columns: columns, row_xpath], lazy_load | session, timeout_ms, retries, retry_wait_ms")
    @Output(name = "table", type = OoaType.TABLE, description = "Table content.")
    public Map<String, Object> extractTable(ActionContext ctx,
            @Input(name = "object", type = OoaType.DICTIONARY, required = false, description = D_OBJ) Object object,
            @Input(name = "selector", type = OoaType.STRING, required = false, defaultValue = "\"table\"", description = "Selector of the table element, used when no captured object is given.") String sel,
            @Input(name = "timeout_ms", type = OoaType.NUMBER, required = false, defaultValue = "30000", min = 0, description = D_TIMEOUT) double timeout,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"browser\"", description = "Session name.") String session,
            @Input(name = "window", type = OoaType.DICTIONARY, required = false, description = BrowserWindows.D_WINDOW) Object window,
            @Input(name = "lazy_load", type = OoaType.BOOLEAN, required = false, defaultValue = "false",
                    description = "The page adds rows as you scroll: scroll to the end and read again until no more rows appear.") boolean lazyLoad,
            @Input(name = "retries", type = OoaType.NUMBER, required = false, defaultValue = "10", min = 1, max = 500,
                    description = "With lazy_load: the most times to scroll and read again.") double retries,
            @Input(name = "retry_wait_ms", type = OoaType.NUMBER, required = false, defaultValue = "1000", min = 0, max = 60000,
                    description = "With lazy_load: how long to wait after each scroll for new rows, in milliseconds.") double retryWaitMs,
            @Input(name = "columns", type = OoaType.DICTIONARY, required = false, example = "{\"Name\": \"td[1]\", \"Link\": \".//a/@href\"}",
                    description = "Columns to read instead of the whole table: column name to an XPath inside each row (its text, or an attribute such as .//a/@href).") Map<String, Object> columns,
            @Input(name = "row_xpath", type = OoaType.STRING, required = false, defaultValue = "\".//tr[td]\"",
                    description = "With columns: XPath of the rows inside the table or container; table rows with cells when empty.") String rowXpath) {
        checkLazyLoad(lazyLoad, retries, retryWaitMs);
        Map<String, Object> spec = columns == null || columns.isEmpty() ? null : columnSpec(columns, rowXpath);
        session = BrowserWindows.use(ctx, session, window, timeout);
        if (spec != null) return customColumns(ctx, session, object, sel, timeout, spec, lazyLoad, retries, retryWaitMs);
        if (tab(ctx, session) != null) {
            ExtensionBridge.TabSession t = tab(ctx, session);
            Map<String, Object> tb = tabTable(ctx, t, object, sel, timeout);
            if (lazyLoad) {
                int before = ((List<?>) tb.get("rows")).size();
                for (int i = 0; i < (int) retries; i++) {
                    onTab(t, "run_js", Map.of("code", SCROLL_TO_END_JS.replace("$T", "null")), 30000);
                    sleep(ctx, retryWaitMs);
                    tb = tabTable(ctx, t, object, sel, timeout);
                    int now = ((List<?>) tb.get("rows")).size();
                    if (now <= before) break;
                    before = now;
                }
            }
            List<String> cols = new ArrayList<>();
            for (Object h : (List<?>) tb.get("columns")) cols.add(String.valueOf(h));
            List<Object> rows = new ArrayList<>();
            for (Object r : (List<?>) tb.get("rows")) { List<Object> g = new ArrayList<>((List<?>) r); while (g.size() < cols.size()) g.add(""); rows.add(g); }
            return Map.of("table", Values.table(cols, rows));
        }
        try {
            Locator l = object != null ? target(ctx, session, object, null, null, timeout) : s(ctx, session).page.locator(sel).first();
            l.waitFor(new Locator.WaitForOptions().setTimeout(timeout));
            if (lazyLoad) {
                int before = ((Number) l.evaluate(ROW_COUNT_JS)).intValue();
                for (int i = 0; i < (int) retries; i++) {
                    l.evaluate("t => { " + SCROLL_TO_END_JS.replace("$T", "t") + " }");
                    sleep(ctx, retryWaitMs);
                    int now = ((Number) l.evaluate(ROW_COUNT_JS)).intValue();
                    if (now <= before) break;
                    before = now;
                }
            }
            Object raw = l.evaluate("t => Array.from((t.closest('table') || t).rows).map(r => Array.from(r.cells).map(c => c.innerText.trim()))");
            List<List<Object>> grid = new ArrayList<>();
            if (raw instanceof List<?> rows) for (Object r : rows) grid.add(new ArrayList<>((List<?>) r));
            List<String> cols = new ArrayList<>();
            if (!grid.isEmpty()) for (Object h : grid.remove(0)) cols.add(String.valueOf(h));
            List<Object> rows = new ArrayList<>();
            for (List<Object> g : grid) { while (g.size() < cols.size()) g.add(""); rows.add(g); }
            return Map.of("table", Values.table(cols, rows));
        } catch (PlaywrightException e) { throw pw(e, "Reading table " + what(object, sel)); }
    }

    /** Extract table without the lazy-loading options (the signature before they were added). */
    public Map<String, Object> extractTable(ActionContext ctx, Object object, String sel, double timeout, String session, Object window) {
        return extractTable(ctx, object, sel, timeout, session, window, false, 10, 1000);
    }

    /** Extract table without custom columns (the signature before they were added). */
    public Map<String, Object> extractTable(ActionContext ctx, Object object, String sel, double timeout, String session, Object window, boolean lazyLoad, double retries, double retryWaitMs) {
        return extractTable(ctx, object, sel, timeout, session, window, lazyLoad, retries, retryWaitMs, null, null);
    }

    /** Reads the rows under the container {@code t} and, in each, the text or attribute each column's XPath finds. */
    static final String COLUMNS_JS = "const it = document.evaluate(spec.rows, t, null, XPathResult.ORDERED_NODE_SNAPSHOT_TYPE, null); const out = [];"
            + " for (let i = 0; i < it.snapshotLength; i++) { const r = it.snapshotItem(i);"
            + " out.push(spec.cols.map(x => document.evaluate(x, r, null, XPathResult.STRING_TYPE, null).stringValue.replace(/\\s+/g, ' ').trim())); }"
            + " return out;";

    /** The column names and XPaths, checked before the browser is touched. */
    static Map<String, Object> columnSpec(Map<String, Object> columns, String rowXpath) {
        List<Object> names = new ArrayList<>(), paths = new ArrayList<>();
        for (var e : columns.entrySet()) {
            String x = e.getValue() == null ? "" : Values.text(e.getValue()).strip();
            if (e.getKey() == null || e.getKey().isBlank() || x.isEmpty())
                throw new ActionException("BROWSER.BAD_INPUT", "Each column needs a name and an XPath", "For example {\"Name\": \"td[1]\", \"Link\": \".//a/@href\"}.");
            names.add(e.getKey());
            paths.add(x);
        }
        String rows = rowXpath == null || rowXpath.isBlank() ? ".//tr[td]" : rowXpath.strip();
        return Map.of("names", names, "cols", paths, "rows", rows);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> customColumns(ActionContext ctx, String session, Object object, String sel, double timeout, Map<String, Object> spec,
                                              boolean lazyLoad, double retries, double retryWaitMs) {
        Map<String, Object> arg = Map.of("rows", spec.get("rows"), "cols", spec.get("cols"));
        java.util.function.Supplier<Object> read;
        Runnable scroll;
        if (tab(ctx, session) != null) {
            ExtensionBridge.TabSession t = tab(ctx, session);
            if (object != null) throw new ActionException("BROWSER.BAD_INPUT", "Custom columns through the browser connector need a selector, not a captured object",
                    "Give the table or container's selector.");
            String code = "const t = document.querySelector(" + JsonLite.write(sel == null || sel.isBlank() ? "table" : sel) + "); if (!t) return null; const spec = " + JsonLite.write(arg) + "; " + COLUMNS_JS;
            read = () -> onTab(t, "run_js", Map.of("code", code), timeout);
            scroll = () -> onTab(t, "run_js", Map.of("code", SCROLL_TO_END_JS.replace("$T", "null")), 30000);
        } else {
            Locator l;
            try {
                l = object != null ? target(ctx, session, object, null, null, timeout) : s(ctx, session).page.locator(sel == null || sel.isBlank() ? "table" : sel).first();
                l.waitFor(new Locator.WaitForOptions().setTimeout(timeout));
            } catch (PlaywrightException e) { throw pw(e, "Reading table " + what(object, sel)); }
            read = () -> l.evaluate("(t, spec) => { " + COLUMNS_JS + " }", arg);
            scroll = () -> l.evaluate("t => { " + SCROLL_TO_END_JS.replace("$T", "t") + " }");
        }
        try {
            Object raw = read.get();
            if (raw == null) throw new ActionException("BROWSER.NOT_FOUND", "No element matches " + what(object, sel), "Check the selector.");
            if (lazyLoad) {
                int before = ((List<?>) raw).size();
                for (int i = 0; i < (int) retries; i++) {
                    scroll.run();
                    sleep(ctx, retryWaitMs);
                    raw = read.get();
                    int now = raw instanceof List<?> l2 ? l2.size() : 0;
                    if (now <= before) break;
                    before = now;
                }
            }
            List<Object> rows = new ArrayList<>();
            if (raw instanceof List<?> rs) for (Object r : rs) rows.add(new ArrayList<>((List<?>) r));
            List<String> cols = new ArrayList<>();
            for (Object n : (List<?>) spec.get("names")) cols.add(String.valueOf(n));
            return Map.of("table", Values.table(cols, rows));
        } catch (PlaywrightException e) {
            String m = String.valueOf(e.getMessage());
            if (m.contains("XPath") || m.contains("evaluate")) throw new ActionException("BROWSER.BAD_XPATH", "An XPath of the columns or rows is not valid: " + m.lines().findFirst().orElse(m), "Check row_xpath and each column's XPath.");
            throw pw(e, "Reading table " + what(object, sel));
        }
    }

    /** The lazy-loading options are checked before the browser is touched. */
    static void checkLazyLoad(boolean lazyLoad, double retries, double retryWaitMs) {
        if (!lazyLoad) return;
        if (Double.isNaN(retries) || retries < 1 || retries > 500)
            throw new ActionException("BROWSER.BAD_INPUT", "retries must be between 1 and 500, not " + retries, "Give how many times to scroll and read again, e.g. 10.");
        if (Double.isNaN(retryWaitMs) || retryWaitMs < 0 || retryWaitMs > 60000)
            throw new ActionException("BROWSER.BAD_INPUT", "retry_wait_ms must be between 0 and 60000, not " + retryWaitMs, "Give the wait after each scroll in milliseconds, e.g. 1000.");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> tabTable(ActionContext ctx, ExtensionBridge.TabSession t, Object object, String sel, double timeout) {
        return (Map<String, Object>) onElement(ctx, t, object, object == null ? sel : null, "get_table", null, timeout, Map.of());
    }

    /** Rows of the table now (the element itself, or the table around it). */
    static final String ROW_COUNT_JS = "t => { const x = t.closest('table') || t; return x.rows ? x.rows.length : 0; }";

    /**
     * Scrolls every scrollable box around the table ($T, or nothing) to its end, then the window: pages that load rows
     * on scroll listen on one of them.
     */
    static final String SCROLL_TO_END_JS = "const t0 = $T; if (t0) { t0.scrollIntoView({block: 'end'}); for (let e = t0.parentElement; e; e = e.parentElement) "
            + "{ if (e.scrollHeight > e.clientHeight) e.scrollTop = e.scrollHeight; } } "
            + "const se = document.scrollingElement || document.documentElement; se.scrollTop = se.scrollHeight; window.scrollTo(0, se.scrollHeight);";

    /** Waits, stopping early when the run is cancelled. */
    private static void sleep(ActionContext ctx, double ms) {
        long end = System.currentTimeMillis() + (long) ms;
        do {
            ctx.checkCancelled();
            long left = end - System.currentTimeMillis();
            if (left <= 0) break;
            try { Thread.sleep(Math.min(100, left)); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new OoaException("RUN.CANCELLED", "Interrupted"); }
        } while (true);
    }

    @Action(id = "run_javascript", name = "Run JavaScript", category = "Page", timeoutSeconds = 120, capabilities = "browser",
            description = "Run JavaScript in the page and return its result (use a return-value expression, e.g. document.title).",
            panel = "window, (Script: Type it here = script / Run a file = script_file) | frame, session")
    @Output(name = "result", description = "Value returned by the script.")
    public Map<String, Object> runJs(ActionContext ctx,
            @Input(name = "script", type = OoaType.STRING, required = false, example = "document.querySelectorAll('.item').length", visibleWhen = "script_file=", description = "JavaScript expression or function, typed here.") String script,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"browser\"", description = "Session name.") String session,
            @Input(name = "script_file", type = OoaType.FILE, required = false, visibleWhen = "script=", description = "Or a .js file to run (UTF-8), used instead of the typed script.") String scriptFile,
            @Input(name = "window", type = OoaType.DICTIONARY, required = false, description = BrowserWindows.D_WINDOW) Object window,
            @Input(name = "frame", type = OoaType.DICTIONARY, required = false, description = "Run the script inside this iframe instead of the main page: the captured iframe element.") Object frame) {
        session = BrowserWindows.use(ctx, session, window, 30000);
        script = scriptText(ctx, script, scriptFile);
        if (tab(ctx, session) != null) {
            if (frame != null) throw frameNotOnTab();
            return Out(Values.toJsonable(onTab(tab(ctx, session), "run_js", Map.of("code", script), 60000)));
        }
        try { return Out(Values.toJsonable(frame != null ? frameOf(ctx, session, frame).evaluate(script) : s(ctx, session).page.evaluate(script))); }
        catch (PlaywrightException e) { throw pw(e, "Running JavaScript"); }
    }

    /** Run JavaScript on the main page (the signature before the frame input was added). */
    public Map<String, Object> runJs(ActionContext ctx, String script, String session, String scriptFile, Object window) {
        return runJs(ctx, script, session, scriptFile, window, null);
    }

    /** The content of a captured iframe element. */
    static Frame frameOf(ActionContext ctx, String session, Object frame) {
        ElementHandle fe = target(ctx, session, frame, null, null, 30000).elementHandle();
        Frame f = fe == null ? null : fe.contentFrame();
        if (f == null) throw new ActionException("BROWSER.NOT_A_FRAME", "The captured element is not an iframe", "Capture the iframe itself.");
        return f;
    }

    private static ActionException frameNotOnTab() {
        return new ActionException("BROWSER.NOT_SUPPORTED", "Running a script inside an iframe is not available for a tab driven through the browser connector",
                "Open the browser with engine playwright, or run the script on the main page.");
    }

    /** The script to run: the typed one, or the file's content. */
    static String scriptText(ActionContext ctx, String script, String file) {
        if (file != null && !file.isBlank()) {
            Path f = ctx.resolvePath(file);
            try { return Files.readString(f, java.nio.charset.StandardCharsets.UTF_8); }
            catch (java.io.IOException e) { throw new ActionException("BROWSER.FILE_NOT_FOUND", "Cannot read the script file " + f + ": " + e.getMessage(), "Check the path (a .js file in the workspace)."); }
        }
        if (script == null || script.isBlank()) throw new ActionException("BROWSER.VALUE_REQUIRED", "Give the script to run, or a script file");
        return script;
    }

    /** The function's result in the type the agent asked for. */
    static Object asReturnType(Object v, String type) {
        if (type == null || type.isBlank() || type.equals("any")) return v;
        return switch (type) {
            case "none" -> null;
            case "text" -> v == null ? "" : v instanceof String s ? s : com.oneofficeautomation.sdk.JsonLite.write(v);
            case "number" -> { try { yield v instanceof BigDecimal d ? d : new BigDecimal(String.valueOf(v).trim()); }
                catch (NumberFormatException e) { throw new ActionException("BROWSER.BAD_RETURN", "The function returned '" + v + "', not a number"); } }
            case "boolean" -> v instanceof Boolean b ? b : v != null && !String.valueOf(v).isEmpty() && !"false".equalsIgnoreCase(String.valueOf(v)) && !"0".equals(String.valueOf(v));
            case "list" -> { if (v instanceof List<?>) yield v; throw new ActionException("BROWSER.BAD_RETURN", "The function did not return a list"); }
            case "dictionary" -> { if (v instanceof Map<?, ?>) yield v; throw new ActionException("BROWSER.BAD_RETURN", "The function did not return a dictionary"); }
            default -> throw new ActionException("BROWSER.BAD_RETURN", "Unknown return type " + type);
        };
    }

    private static Map<String, Object> Out(Object v) { Map<String, Object> m = new LinkedHashMap<>(); m.put("result", v); return m; }

    @Action(id = "get_page_source", name = "Get page source", category = "Page", timeoutSeconds = 120, capabilities = "browser",
            description = "Return the current HTML of the page (after scripts have run), and optionally save it to a file.",
            panel = "window, (Read from: The whole page = / One iframe = frame), [Also save it to a file: path] | session")
    @Output(name = "source", type = OoaType.STRING, description = "HTML of the page.")
    @Output(name = "path", type = OoaType.FILE, description = "Saved file, or empty.")
    public Map<String, Object> getPageSource(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, required = false, description = "Also save the HTML to this file.") String path,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"browser\"", description = "Session name.") String session,
            @Input(name = "frame", type = OoaType.DICTIONARY, required = false, description = "The source of this iframe instead of the whole page: the captured iframe element.") Object frame,
            @Input(name = "window", type = OoaType.DICTIONARY, required = false, description = BrowserWindows.D_WINDOW) Object window) {
        session = BrowserWindows.use(ctx, session, window, 30000);
        String html;
        if (frame != null && tab(ctx, session) != null) html = String.valueOf(onElement(ctx, tab(ctx, session), frame, null, "get_frame_html", null, 30000, Map.of()));
        else if (frame != null) {
            try {
                ElementHandle fe = target(ctx, session, frame, null, null, 30000).elementHandle();
                Frame f = fe.contentFrame();
                if (f == null) throw new ActionException("BROWSER.NOT_A_FRAME", "The captured element is not an iframe", "Capture the iframe itself.");
                html = f.content();
            } catch (PlaywrightException e) { throw pw(e, "Reading the iframe's source"); }
        }
        else if (tab(ctx, session) != null) html = String.valueOf(onTab(tab(ctx, session), "page_source", Map.of(), 60000));
        else try { html = s(ctx, session).page.content(); } catch (PlaywrightException e) { throw pw(e, "Reading the page source"); }
        String saved = "";
        if (path != null && !path.isBlank()) {
            Path p = ctx.resolvePath(path);
            try {
                if (p.getParent() != null) Files.createDirectories(p.getParent());
                Files.writeString(p, html, java.nio.charset.StandardCharsets.UTF_8);
            } catch (java.io.IOException e) { throw new ActionException("BROWSER.FILE_ERROR", "Cannot save " + p + ": " + e.getMessage(), "Check the folder is writable.", true); }
            saved = p.toString();
        }
        return Map.of("source", html, "path", saved);
    }

    /** Resolves a dotted name from window, calls it with the arguments and returns its (awaited) result. */
    static final String CALL_FUNCTION_JS = """
            async ([name, args]) => {
              let owner = window, fn = window;
              for (const part of name.split('.')) { owner = fn; fn = fn == null ? undefined : fn[part]; }
              if (typeof fn !== 'function') throw new Error('No JavaScript function named ' + name + ' on this page');
              return await fn.apply(owner, args);
            }""";

    @Action(id = "call_javascript_function", name = "Call JavaScript function", category = "Page", timeoutSeconds = 120, capabilities = "browser",
            description = "Call a function defined by the page (e.g. app.calculateTotal) with arguments and return its result.",
            panel = "window, function, arguments, return_type | frame, session")
    @Output(name = "result", description = "Value returned by the function (awaited when it returns a promise).")
    public Map<String, Object> callJsFunction(ActionContext ctx,
            @Input(name = "function", type = OoaType.STRING, example = "app.calculateTotal", description = "Function name, reachable from window; dots for nested objects.") String function,
            @Input(name = "arguments", type = OoaType.LIST, required = false, description = "Arguments, in order (text, numbers, true/false, lists and dictionaries).") List<Object> args,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"browser\"", description = "Session name.") String session,
            @Input(name = "return_type", type = OoaType.STRING, required = false, defaultValue = "\"any\"", enumValues = {"any", "none", "text", "number", "boolean", "list", "dictionary"},
                    description = "What the function returns: the result is checked and converted (none ignores it).") String returnType,
            @Input(name = "window", type = OoaType.DICTIONARY, required = false, description = BrowserWindows.D_WINDOW) Object window,
            @Input(name = "frame", type = OoaType.DICTIONARY, required = false, description = "Call the function inside this iframe instead of the main page: the captured iframe element.") Object frame) {
        session = BrowserWindows.use(ctx, session, window, 30000);
        if (function == null || !function.matches("^[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*$"))
            throw new ActionException("BROWSER.BAD_FUNCTION", "Invalid JavaScript function name '" + function + "'", "Give a name such as calculateTotal or app.utils.calculateTotal.");
        List<Object> plain = new ArrayList<>();
        if (args != null) for (Object a : args) plain.add(jsValue(Values.toJsonable(a)));
        if (tab(ctx, session) != null) {
            if (frame != null) throw frameNotOnTab();
            return Out(asReturnType(Values.toJsonable(onTab(tab(ctx, session), "call_function", Map.of("name", function, "args", plain), 60000)), returnType));
        }
        try {
            Object v = frame != null ? frameOf(ctx, session, frame).evaluate(CALL_FUNCTION_JS, List.of(function, plain))
                    : s(ctx, session).page.evaluate(CALL_FUNCTION_JS, List.of(function, plain));
            return Out(asReturnType(Values.toJsonable(v), returnType));
        }
        catch (PlaywrightException e) { throw pw(e, "Calling " + function); }
    }

    /** Call a JavaScript function on the main page (the signature before the frame input was added). */
    public Map<String, Object> callJsFunction(ActionContext ctx, String function, List<Object> args, String session, String returnType, Object window) {
        return callJsFunction(ctx, function, args, session, returnType, window, null);
    }

    /** Playwright serialises Integer and Double (not Long or BigDecimal): whole numbers in int range become Integer, others Double. */
    static Object jsValue(Object v) {
        if (v instanceof BigDecimal d) {
            try { return d.stripTrailingZeros().scale() <= 0 ? (Object) d.intValueExact() : (Object) d.doubleValue(); }
            catch (ArithmeticException e) { return d.doubleValue(); }
        }
        if (v instanceof Map<?, ?> m) { Map<String, Object> o = new LinkedHashMap<>(); m.forEach((k, x) -> o.put(String.valueOf(k), jsValue(x))); return o; }
        if (v instanceof List<?> l) { List<Object> o = new ArrayList<>(); for (Object x : l) o.add(jsValue(x)); return o; }
        return v;
    }

    private static final java.net.http.HttpClient LINKS = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(10)).followRedirects(java.net.http.HttpClient.Redirect.NORMAL).build();

    /** Checks each URL with HEAD (falling back to GET when HEAD is refused). Rows: url, status, ok, error. */
    static List<Object> checkLinks(ActionContext ctx, List<String> urls, int timeoutMs, boolean onlyBroken) {
        List<Object> rows = new ArrayList<>();
        java.time.Duration t = java.time.Duration.ofMillis(Math.max(500, timeoutMs));
        for (String u : urls) {
            if (ctx != null) ctx.checkCancelled();
            int status = 0;
            String error = "";
            try {
                java.net.URI uri = java.net.URI.create(u);
                status = LINKS.send(java.net.http.HttpRequest.newBuilder(uri).timeout(t).method("HEAD", java.net.http.HttpRequest.BodyPublishers.noBody()).build(),
                        java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode();
                if (status == 405 || status == 501 || status == 403)
                    status = LINKS.send(java.net.http.HttpRequest.newBuilder(uri).timeout(t).GET().header("Range", "bytes=0-0").build(),
                            java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode();
            } catch (java.net.http.HttpTimeoutException e) { error = "timeout";
            } catch (java.io.IOException | IllegalArgumentException e) { error = String.valueOf(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new OoaException("RUN.CANCELLED", "Interrupted"); }
            boolean ok = status >= 200 && status < 400;
            if (!onlyBroken || !ok) rows.add(new ArrayList<>(Arrays.asList(u, BigDecimal.valueOf(status), ok, error)));
        }
        return rows;
    }

    static String hostOf(String u) { try { String h = java.net.URI.create(u).getHost(); return h == null ? "" : h; } catch (IllegalArgumentException e) { return ""; } }

    private static final java.util.regex.Pattern HREF = java.util.regex.Pattern.compile("(?i)<(?:a|area)\\b[^>]*?\\bhref\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))");

    /** The links of a page fetched over HTTP, made absolute. */
    static List<String> linksOf(String pageUrl, int timeoutMs) {
        List<String> out = new ArrayList<>();
        try {
            java.net.http.HttpResponse<String> r = LINKS.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(pageUrl)).timeout(java.time.Duration.ofMillis(Math.max(1000, timeoutMs))).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            String ct = r.headers().firstValue("content-type").orElse("");
            if (r.statusCode() >= 400 || (!ct.isEmpty() && !ct.contains("html"))) return out;
            java.net.URI base = r.uri();
            var m = HREF.matcher(r.body());
            while (m.find()) {
                String h = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
                h = h.replace("&amp;", "&").trim();
                if (h.isEmpty() || h.startsWith("javascript:") || h.startsWith("mailto:") || h.startsWith("tel:")) continue;
                try { out.add(base.resolve(h).toString()); } catch (IllegalArgumentException ignored) { }
            }
        } catch (java.io.IOException | IllegalArgumentException e) { /* the page itself is unreachable: it is reported as a broken link by its parent */
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return out;
    }

    /** checkLinks with several links at the same time; rows keep the order of the links. */
    static List<Object> checkLinksParallel(ActionContext ctx, List<String> urls, int timeoutMs, int threads) {
        if (threads <= 1 || urls.size() <= 1) return checkLinks(ctx, urls, timeoutMs, false);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        try {
            List<java.util.concurrent.Future<List<Object>>> fs = new ArrayList<>();
            for (String u : urls) fs.add(pool.submit(() -> checkLinks(null, List.of(u), timeoutMs, false)));
            List<Object> out = new ArrayList<>();
            for (var f : fs) {
                ctx.checkCancelled();
                try { out.addAll(f.get()); }
                catch (java.util.concurrent.ExecutionException e) { throw new ActionException("BROWSER.ERROR", "Checking a link failed: " + e.getCause()); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new OoaException("RUN.CANCELLED", "Interrupted"); }
            }
            return out;
        } finally { pool.shutdownNow(); }
    }

    static void writeLinksCsv(Path f, List<Object> rows, boolean append, String encoding) {
        java.nio.charset.Charset cs = "ANSI".equalsIgnoreCase(encoding) ? java.nio.charset.Charset.forName("windows-1252")
                : "UTF-16".equalsIgnoreCase(encoding) ? java.nio.charset.StandardCharsets.UTF_16 : java.nio.charset.StandardCharsets.UTF_8;
        boolean exists = Files.exists(f);
        StringBuilder sb = new StringBuilder();
        if (!append || !exists) sb.append("url,status,ok,error\r\n");
        for (Object o : rows) {
            List<?> r = (List<?>) o;
            for (int i = 0; i < r.size(); i++) {
                String v = String.valueOf(r.get(i));
                if (v.contains(",") || v.contains("\"") || v.contains("\n")) v = "\"" + v.replace("\"", "\"\"") + "\"";
                sb.append(i > 0 ? "," : "").append(v);
            }
            sb.append("\r\n");
        }
        try {
            if (f.getParent() != null) Files.createDirectories(f.getParent());
            if (append && exists) Files.writeString(f, sb.toString(), cs, java.nio.file.StandardOpenOption.APPEND);
            else Files.writeString(f, sb.toString(), cs);
        } catch (java.io.IOException e) { throw new ActionException("BROWSER.FILE_ERROR", "Cannot write " + f + ": " + e.getMessage(), "Check the folder is writable.", true); }
    }

    @Action(id = "download_url", name = "Download file from address", category = "Page", timeoutSeconds = 3600, capabilities = {"network", "filesystem"},
            description = "Download a file from its address and save it, signing in with Windows (NTLM/Kerberos) or a user name and password when the server asks.",
            panel = "url, save_to, overwrite | [The server asks to sign in: username, password], timeout_ms")
    @Output(name = "path", type = OoaType.FILE, description = "Saved file.")
    @Output(name = "size", type = OoaType.NUMBER, description = "Size in bytes.")
    public Map<String, Object> downloadUrl(ActionContext ctx,
            @Input(name = "url", type = OoaType.STRING, example = "https://intranet.example.com/reports/invoice.xlsx", description = "Address of the file.") String url,
            @Input(name = "save_to", type = OoaType.FILE, description = "File to create, or a folder (the name then comes from the address).") String saveTo,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Replace the file if it already exists.") boolean overwrite,
            @Input(name = "username", type = OoaType.STRING, required = false, description = "User name when the server asks to sign in (domain\\user for Windows sign-in); empty uses the Windows sign-in of the agent's user.") String username,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, description = "Password for that user name (a Credential).") Object password,
            @Input(name = "timeout_ms", type = OoaType.NUMBER, required = false, defaultValue = "600000", min = 1000, description = "How long the download may take.") int timeoutMs) {
        Path target = ctx.resolvePath(saveTo);
        String name = url.replaceFirst("[?#].*$", "").replaceFirst(".*/", "");
        if (Files.isDirectory(target) || saveTo.endsWith("/") || saveTo.endsWith("\\")) target = target.resolve(name.isBlank() ? "download" : java.net.URLDecoder.decode(name, java.nio.charset.StandardCharsets.UTF_8));
        if (Files.exists(target) && !overwrite) throw new ActionException("BROWSER.FILE_EXISTS", "The file " + target + " already exists", "Turn on overwrite, or choose another name.");
        String pw = password instanceof SecureString ss ? ss.reveal() : password == null ? null : String.valueOf(password);
        java.net.Authenticator auth = username == null || username.isBlank() ? null : new java.net.Authenticator() {
            @Override protected java.net.PasswordAuthentication getPasswordAuthentication() { return new java.net.PasswordAuthentication(username, pw == null ? new char[0] : pw.toCharArray()); }
        };
        try {
            // HttpURLConnection signs in with NTLM/Kerberos (Windows) as well as basic and digest
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) java.net.URI.create(url).toURL().openConnection();
            if (auth != null) c.setAuthenticator(auth);
            c.setConnectTimeout(Math.min(timeoutMs, 30000)); c.setReadTimeout(timeoutMs);
            c.setInstanceFollowRedirects(true);
            int code = c.getResponseCode();
            if (code == 401 || code == 407) throw new ActionException("BROWSER.SIGN_IN_FAILED", "The server refused the sign-in (" + code + ")", "Check the user name and password, or the agent user's Windows access.");
            if (code >= 400) throw new ActionException("BROWSER.DOWNLOAD_FAILED", "The server answered " + code + " for " + url, null, code >= 500);
            if (target.getParent() != null) Files.createDirectories(target.getParent());
            Path tmp = Files.createTempFile(target.getParent() == null ? Path.of(".") : target.getParent(), "dl", ".part");
            try (var in = c.getInputStream()) { Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return Map.of("path", target.toString(), "size", BigDecimal.valueOf(Files.size(target)));
        } catch (java.io.IOException | IllegalArgumentException e) {
            throw new ActionException("BROWSER.DOWNLOAD_FAILED", "Downloading " + url + " failed: " + e.getMessage(), "Check the address and the network.", true);
        }
    }

    @Action(id = "find_broken_links", name = "Find broken links", category = "Page", timeoutSeconds = 3600, capabilities = {"browser", "network"},
            description = "Collect the links on the current page (or on a page or whole site given by its address) and check each one, reporting those that fail "
                    + "or return an HTTP error; optionally save the list to a CSV file.",
            panel = "url, scope, max_pages, [Save the list to a CSV file: csv_path, append, encoding], threads | only_broken, same_site_only, max_links, timeout_ms, session")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: url, status (0 when unreachable), ok, error.")
    @Output(name = "broken_count", type = OoaType.NUMBER, description = "Number of links that failed.")
    @Output(name = "checked_count", type = OoaType.NUMBER, description = "Number of distinct links checked.")
    @SuppressWarnings("unchecked")
    public Map<String, Object> findBrokenLinks(ActionContext ctx,
            @Input(name = "only_broken", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "List only failing links instead of every link.") boolean onlyBroken,
            @Input(name = "same_site_only", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Check only links to the page's own host.") boolean sameSite,
            @Input(name = "max_links", type = OoaType.NUMBER, required = false, defaultValue = "200", min = 1, description = "Safety limit on links checked.") int maxLinks,
            @Input(name = "timeout_ms", type = OoaType.NUMBER, required = false, defaultValue = "10000", min = 100, description = "Time allowed for each link, in milliseconds.") int timeoutMs,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"browser\"", description = "Session name.") String session,
            @Input(name = "url", type = OoaType.STRING, required = false, description = "Page to check, by its address, instead of the browser's current page (no browser needed).") String url,
            @Input(name = "scope", type = OoaType.STRING, required = false, defaultValue = "\"page\"", enumValues = {"page", "site"},
                    description = "page: the links of that page; site: also follow the site's own pages (same host) and check their links.") String scope,
            @Input(name = "max_pages", type = OoaType.NUMBER, required = false, defaultValue = "50", visibleWhen = "scope=site", min = 1, description = "With scope site: at most this many pages are read.") int maxPages,
            @Input(name = "csv_path", type = OoaType.FILE, required = false, description = "Also save the list to this CSV file.") String csvPath,
            @Input(name = "append", type = OoaType.BOOLEAN, required = false, defaultValue = "false", visibleWhen = "csv_path!=", description = "Add to the CSV file if it exists, instead of replacing it.") boolean append,
            @Input(name = "encoding", type = OoaType.STRING, required = false, defaultValue = "\"UTF-8\"", enumValues = {"UTF-8", "UTF-16", "ANSI"}, visibleWhen = "csv_path!=", description = "CSV file encoding.") String encoding,
            @Input(name = "threads", type = OoaType.NUMBER, required = false, defaultValue = "10", min = 1, max = 64, description = "How many links are checked at the same time (1-50).") int threads) {
        List<Object> hrefs;
        String pageUrl;
        if (url != null && !url.isBlank()) {
            pageUrl = url.trim();
            hrefs = new ArrayList<>();
            LinkedHashSet<String> seenPages = new LinkedHashSet<>();
            ArrayDeque<String> queue = new ArrayDeque<>(List.of(pageUrl));
            String siteHost = hostOf(pageUrl);
            while (!queue.isEmpty() && seenPages.size() < ("site".equals(scope) ? Math.max(1, maxPages) : 1)) {
                ctx.checkCancelled();
                String pg = queue.poll();
                if (!seenPages.add(pg)) continue;
                for (String h : linksOf(pg, timeoutMs)) {
                    hrefs.add(h);
                    String clean = h.replaceFirst("#.*$", "");
                    if ("site".equals(scope) && siteHost.equalsIgnoreCase(hostOf(clean)) && !seenPages.contains(clean)) queue.add(clean);
                }
            }
        } else if (tab(ctx, session) != null) {
            Map<String, Object> l = (Map<String, Object>) onTab(tab(ctx, session), "links", Map.of(), 30000);
            hrefs = (List<Object>) l.get("links"); pageUrl = String.valueOf(l.get("url"));
        } else {
            Page page = s(ctx, session).page;
            try { hrefs = (List<Object>) page.evaluate("() => Array.from(document.querySelectorAll('a[href], area[href]')).map(a => a.href)"); }
            catch (PlaywrightException e) { throw pw(e, "Reading the links"); }
            pageUrl = page.url();
        }
        String host = "";
        try { host = String.valueOf(java.net.URI.create(pageUrl).getHost()); } catch (IllegalArgumentException ignored) { }
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        for (Object h : hrefs) {
            String u = String.valueOf(h).replaceFirst("#.*$", "");
            if (!u.matches("(?i)^https?://.+")) continue;
            if (sameSite) { try { if (!host.equalsIgnoreCase(java.net.URI.create(u).getHost())) continue; } catch (IllegalArgumentException e) { continue; } }
            urls.add(u);
            if (urls.size() >= Math.max(1, maxLinks)) break;
        }
        List<Object> all = checkLinksParallel(ctx, new ArrayList<>(urls), timeoutMs, Math.max(1, Math.min(50, threads)));
        List<Object> rows = new ArrayList<>();
        int broken = 0;
        for (Object o : all) {
            boolean ok = (Boolean) ((List<Object>) o).get(2);
            if (!ok) broken++;
            if (!onlyBroken || !ok) rows.add(o);
        }
        if (csvPath != null && !csvPath.isBlank()) writeLinksCsv(ctx.resolvePath(csvPath), rows, append, encoding);
        return Map.of("table", Values.table(List.of("url", "status", "ok", "error"), rows), "broken_count", BigDecimal.valueOf(broken), "checked_count", BigDecimal.valueOf(all.size()));
    }

    @Action(id = "download", name = "Download file", category = "Page", timeoutSeconds = 600, capabilities = {"browser", "filesystem"},
            description = "Click a link or button that starts a download and save the file into a folder.",
            panel = "window, object, selector, folder | session, timeout_ms")
    @Output(name = "path", type = OoaType.FILE, description = "Saved file.")
    public Map<String, Object> download(ActionContext ctx,
            @Input(name = "object", type = OoaType.DICTIONARY, required = false, description = D_OBJ) Object object,
            @Input(name = "selector", type = OoaType.STRING, required = false, description = "Element that starts the download, used when no captured object is given.") String sel,
            @Input(name = "folder", type = OoaType.FILE, description = "Folder to save into.") String folder,
            @Input(name = "timeout_ms", type = OoaType.NUMBER, required = false, defaultValue = "120000", min = 0, description = "How long to wait for the download.") double timeout,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"browser\"", description = "Session name.") String session,
            @Input(name = "window", type = OoaType.DICTIONARY, required = false, description = BrowserWindows.D_WINDOW) Object window) {
        session = BrowserWindows.use(ctx, session, window, timeout);
        if (tab(ctx, session) != null) {
            Map<String, Object> el = new LinkedHashMap<>();
            if (object != null) { Map<String, Object> o = new LinkedHashMap<>(WebObjects.object(object)); o.remove("screenshot"); el.put("object", o); } else el.put("selector", sel);
            el.put("timeout_ms", BigDecimal.valueOf((long) Math.min(timeout, 30000)));
            @SuppressWarnings("unchecked")
            Map<String, Object> d = (Map<String, Object>) onTab(tab(ctx, session), "download_click", Map.of("element", el, "timeout_ms", BigDecimal.valueOf((long) timeout)), timeout);
            try {
                Path src = Path.of(String.valueOf(d.get("path")));
                Path target = ctx.resolvePath(folder).resolve(src.getFileName().toString());
                Files.createDirectories(target.getParent());
                Files.copy(src, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return Map.of("path", target.toString());
            } catch (java.io.IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), null, true); }
        }
        try {
            Page p = s(ctx, session).page;
            Locator trigger = target(ctx, session, object, sel, null, Math.min(timeout, 30000));
            Download d = p.waitForDownload(new Page.WaitForDownloadOptions().setTimeout(timeout), () -> trigger.click());
            String name = d.suggestedFilename().replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_");
            Path target = ctx.resolvePath(folder).resolve(name.isBlank() ? "download" : name);
            Files.createDirectories(target.getParent());
            d.saveAs(target);
            return Map.of("path", target.toString());
        } catch (PlaywrightException e) { throw pw(e, "Downloading via " + what(object, sel));
        } catch (java.io.IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), null, true); }
    }

    @Action(id = "screenshot", name = "Take screenshot", category = "Page", timeoutSeconds = 120, capabilities = {"browser", "filesystem"}, description = "Save a PNG screenshot of the page or one element.",
            panel = "window, object, selector, path, full_page | session")
    @Output(name = "path", type = OoaType.FILE, description = "Saved image.")
    public Map<String, Object> screenshot(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "PNG file to create.") String path,
            @Input(name = "full_page", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Whole scrollable page.") boolean full,
            @Input(name = "selector", type = OoaType.STRING, required = false, description = "Only this element (selector).") String sel,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"browser\"", description = "Session name.") String session,
            @Input(name = "object", type = OoaType.DICTIONARY, required = false, description = "Only this element: the captured object from Capture or Pick in Agent Studio.") Object object,
            @Input(name = "window", type = OoaType.DICTIONARY, required = false, description = BrowserWindows.D_WINDOW) Object window) {
        session = BrowserWindows.use(ctx, session, window, 30000);
        Path p = ctx.resolvePath(path);
        if (tab(ctx, session) != null) {
            Object b64 = object != null || (sel != null && !sel.isBlank()) ? onElement(ctx, tab(ctx, session), object, sel, "screenshot", null, 30000, Map.of())
                    : onTab(tab(ctx, session), "screenshot", Map.of(), 30000);
            if (full) ctx.warn("A tab driven through the browser connector is captured as it is shown (not the whole scrollable page)");
            try {
                if (p.getParent() != null) Files.createDirectories(p.getParent());
                Files.write(p, Base64.getDecoder().decode(String.valueOf(b64)));
            } catch (java.io.IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), null, true); }
            return Map.of("path", p.toString());
        }
        try {
            if (p.getParent() != null) Files.createDirectories(p.getParent());
            Page page = s(ctx, session).page;
            if (object != null) target(ctx, session, object, null, null, 30000).screenshot(new Locator.ScreenshotOptions().setPath(p));
            else if (sel != null && !sel.isBlank()) page.locator(sel).first().screenshot(new Locator.ScreenshotOptions().setPath(p));
            else page.screenshot(new Page.ScreenshotOptions().setPath(p).setFullPage(full));
        } catch (PlaywrightException e) { throw pw(e, "Taking a screenshot");
        } catch (java.io.IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), null, true); }
        return Map.of("path", p.toString());
    }

    @Action(id = "page_info", name = "Get URL and title", category = "Page", idempotent = true, capabilities = "browser", description = "Return the current page's URL and title.",
            panel = "session")
    @Output(name = "url", type = OoaType.STRING, description = "Current URL.")
    @Output(name = "title", type = OoaType.STRING, description = "Page title.")
    public Map<String, Object> pageInfo(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"browser\"", description = "Session name.") String session) {
        if (tab(ctx, session) != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> t = (Map<String, Object>) onTab(tab(ctx, session), "info", Map.of(), 10000);
            return Map.of("url", String.valueOf(t.get("url")), "title", String.valueOf(t.get("title")));
        }
        Page p = s(ctx, session).page;
        return Map.of("url", p.url(), "title", p.title());
    }

    @Action(id = "history", name = "Back, forward or refresh", category = "Browser", timeoutSeconds = 120, capabilities = "browser", description = "Go back, go forward or reload the page.",
            panel = "window, action, steps, error_if_too_many | session")
    public Map<String, Object> history(ActionContext ctx,
            @Input(name = "action", type = OoaType.STRING, enumValues = {"back", "forward", "refresh"}, description = "What to do.") String action,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"browser\"", description = "Session name.") String session,
            @Input(name = "steps", type = OoaType.NUMBER, required = false, defaultValue = "1", visibleWhen = "action=back|forward", min = 1, description = "back: how many pages to go back (e.g. 3).") int steps,
            @Input(name = "error_if_too_many", type = OoaType.BOOLEAN, required = false, defaultValue = "false",
                    visibleWhen = "action=back|forward", description = "back: fail when the tab has visited fewer pages than the steps asked for.") boolean strict,
            @Input(name = "window", type = OoaType.DICTIONARY, required = false, description = BrowserWindows.D_WINDOW) Object window) {
        session = BrowserWindows.use(ctx, session, window, 30000);
        int n = Math.max(1, steps);
        if (tab(ctx, session) != null) {
            Map<String, Object> a = action.equals("back") ? Map.of("steps", n, "strict", strict) : Map.of();
            onTab(tab(ctx, session), switch (action) { case "back" -> "back"; case "forward" -> "forward"; default -> "reload"; }, a, 60000);
            return Map.of();
        }
        Page p = s(ctx, session).page;
        try {
            if (action.equals("back") && strict) {
                int len = ((Number) p.evaluate("() => history.length")).intValue();
                if (n >= len) throw new ActionException("BROWSER.HISTORY_TOO_SHORT", "The tab has only " + (len - 1) + " page(s) to go back to, not " + n, "Lower the steps, or turn off error_if_too_many.");
            }
            switch (action) { case "back" -> { for (int i = 0; i < n; i++) p.goBack(); } case "forward" -> p.goForward(); default -> p.reload(); }
            p.waitForLoadState(LoadState.LOAD);
        } catch (PlaywrightException e) { throw pw(e, action); }
        return Map.of();
    }

    @Action(id = "switch_tab", name = "Switch tab", category = "Browser", capabilities = "browser", description = "Make another tab current, by number (0 = first) or by title text; new tabs opened by links are included.",
            panel = "(Find the tab: By position = index / By title = title_contains) | session")
    @Output(name = "title", type = OoaType.STRING, description = "Title of the tab now current.")
    public Map<String, Object> switchTab(ActionContext ctx,
            @Input(name = "index", type = OoaType.NUMBER, required = false, description = "Tab number, starting at 0; -1 for the newest.") Integer index,
            @Input(name = "title_contains", type = OoaType.STRING, required = false, description = "Pick the first tab whose title contains this text.") String title,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"browser\"", description = "Session name.") String session) {
        if (tab(ctx, session) != null) {
            ExtensionBridge.TabSession t = tab(ctx, session);
            @SuppressWarnings("unchecked")
            Map<String, Object> cur = (Map<String, Object>) onTab(t, "info", Map.of(), 10000);
            List<Map<String, Object>> same = new ArrayList<>();
            for (Map<String, Object> x : ExtensionBridge.tabs()) if (t.browser.equals(x.get("browser")) && String.valueOf(cur.get("window_id")).equals(String.valueOf(x.get("window_id")))) same.add(x);
            same.sort(Comparator.comparingInt(x -> ((Number) x.get("index")).intValue()));
            Map<String, Object> pick = null;
            if (title != null && !title.isBlank()) for (Map<String, Object> x : same) if (String.valueOf(x.get("title")).toLowerCase(Locale.ROOT).contains(title.toLowerCase(Locale.ROOT))) { pick = x; break; }
            if (pick == null && index != null) { int i = index < 0 ? same.size() + index : index; if (i >= 0 && i < same.size()) pick = same.get(i); }
            if (pick == null) throw new ActionException("BROWSER.NO_TAB", "No matching tab among " + same.size() + " open tab(s)", "Check the tab number or title text.");
            t.tabId = ((Number) pick.get("tab_id")).longValue();
            onTab(t, "activate", Map.of(), 10000);
            return Map.of("title", String.valueOf(pick.get("title")), "count", BigDecimal.valueOf(same.size()));
        }
        BrowserSession bs = s(ctx, session);
        List<Page> pages = bs.context.pages();
        Page target = null;
        if (title != null && !title.isBlank()) for (Page p : pages) if (p.title().toLowerCase(Locale.ROOT).contains(title.toLowerCase(Locale.ROOT))) { target = p; break; }
        if (target == null && index != null) { int i = index < 0 ? pages.size() + index : index; if (i >= 0 && i < pages.size()) target = pages.get(i); }
        if (target == null) throw new ActionException("BROWSER.NO_TAB", "No matching tab among " + pages.size() + " open tab(s)", "Check the tab number or title text.");
        target.bringToFront();
        bs.page = target;
        return Map.of("title", target.title(), "count", BigDecimal.valueOf(pages.size()));
    }

    @Action(id = "close", name = "Close browser", category = "Browser", description = "Close the browser session.",
            panel = "what, window | session")
    public Map<String, Object> close(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"browser\"", description = "Session name.") String session,
            @Input(name = "what", type = OoaType.STRING, required = false, defaultValue = "\"session\"", enumValues = {"session", "tab", "window", "all"},
                    description = "session: what this agent opened (its window, or the tab it worked in); tab: the current tab; window: the whole window; all: every browser window.") String what,
            @Input(name = "window", type = OoaType.DICTIONARY, required = false, visibleWhen = "what=tab|window", description = "The tab or window to close, instead of the session's current one. " + BrowserWindows.D_WINDOW) Object window) {
        if (window != null) session = BrowserWindows.use(ctx, session, window, 10000);
        String w = what == null ? "session" : what;
        if (!w.equals("session") && ctx.sessions().get(session) instanceof BrowserSession bs0 && !w.equals("all")) {
            try { if (w.equals("tab")) { bs0.page.close(); List<Page> rest = bs0.context.pages(); if (!rest.isEmpty()) { bs0.page = rest.get(rest.size() - 1); return Map.of(); } }
                  else for (Page pg : new ArrayList<>(bs0.context.pages())) pg.close(); }
            catch (PlaywrightException e) { throw pw(e, "Closing the " + w); }
        }
        Object o = ctx.sessions().remove(session);
        UiGuard.unregister(ctx, session);
        if (o instanceof ExtensionBridge.TabSession t) {
            // a window this agent opened closes; a tab it only found closes as a tab
            String how = w.equals("session") ? (t.own ? "window" : "tab") : w;
            try { onTab(t, "close", Map.of("what", how), 10000); } catch (ActionException e) { if (!"BROWSER.CONNECTOR_FAILED".equals(e.code())) ctx.warn("Could not close the tab: " + e.getMessage()); }
            return Map.of();
        }
        if (w.equals("all")) {
            // every browser: the other sessions of this run, and the connected browsers' windows
            for (String k : new ArrayList<>(ctx.sessions().keySet())) if (ctx.sessions().get(k) instanceof BrowserSession || ctx.sessions().get(k) instanceof ExtensionBridge.TabSession) {
                Object x = ctx.sessions().remove(k);
                if (x instanceof BrowserSession xb) try { xb.close(); } catch (RuntimeException ignored) { }
            }
            for (String b : ExtensionBridge.browsers()) try { ExtensionBridge.call(b, "close", Map.of("what", "all"), 15000); } catch (ActionException ignored) { }
        }
        if (o instanceof BrowserSession b) {
            // a window this session opened in a browser that stays open: close that window, then disconnect
            for (Page p : b.ownPages) try { if (!p.isClosed()) p.close(); } catch (RuntimeException ignored) { }
            try { b.close(); } catch (RuntimeException ignored) { }
        }
        return Map.of();
    }

    // ---------- Browsers that are already open ----------

    private static Map<String, Object> attachedOut(BrowserSession bs, String session) {
        Map<String, Object> out = new LinkedHashMap<>();
        String title = "", url = "";
        try { title = bs.page.title(); url = bs.page.url(); } catch (PlaywrightException ignored) { }
        out.put("title", title);
        out.put("url", url);
        out.put("tab_count", BigDecimal.valueOf(BrowserWindows.pages(bs).size()));
        out.put("window", new LinkedHashMap<>(Map.of("source", "variable", "variable", session)));
        return out;
    }

    @Action(id = "attach", name = "Attach to open browser", category = "Browser", timeoutSeconds = 120, capabilities = "browser",
            description = "Connect to an Edge or Chrome that is already running, through its DevTools debugging port, and use it as a named session. "
                    + "Only a browser started with a debugging port can be reached (use Start browser for automation, or start it with --remote-debugging-port); "
                    + "a browser opened the usual way has none. Closing the session later only disconnects; the browser stays open.",
            panel = "endpoint, browser | session, timeout_ms")
    @Output(name = "title", type = OoaType.STRING, description = "Title of the session's current tab.")
    @Output(name = "url", type = OoaType.STRING, description = "Address of the session's current tab.")
    @Output(name = "tab_count", type = OoaType.NUMBER, description = "Number of open tabs in the browser.")
    @Output(name = "window", type = OoaType.DICTIONARY, description = "A window choice for this session, to pass as the window of element actions.")
    public Map<String, Object> attach(ActionContext ctx,
            @Input(name = "endpoint", type = OoaType.STRING, required = false, defaultValue = "\"http://127.0.0.1:9222\"", example = "http://127.0.0.1:9222",
                    description = "Debugging address of the browser: http://host:port, just the port, or a ws:// DevTools address.") String endpoint,
            @Input(name = "browser", type = OoaType.STRING, required = false, enumValues = {"edge", "chrome"},
                    description = "Which browser it is; found out from the browser itself when left empty. Used by window choices that name a browser.") String which,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"browser\"", description = "Session name.") String session,
            @Input(name = "timeout_ms", type = OoaType.NUMBER, required = false, defaultValue = "30000", description = "How long to wait for the connection, in milliseconds.") double timeout) {
        BrowserSession bs = BrowserWindows.attach(ctx, endpoint, which, session, timeout);
        return attachedOut(bs, session);
    }

    @Action(id = "open_for_automation", name = "Start browser for automation", category = "Browser", timeoutSeconds = 180, capabilities = {"browser", "filesystem"},
            description = "Start Edge or Chrome with a debugging port and its own profile folder in the workspace, so this agent, later agents and Attach to open browser "
                    + "can all reach it. When a browser already listens on the port it is reused instead. A browser opened the usual way cannot be reached, "
                    + "because it has no debugging port; recent browsers also refuse a debugging port on the everyday profile, which is why a separate profile is used. "
                    + "The browser keeps running after the agent ends.",
            panel = "browser, url, port | profile_folder, headless, attach, session, executable, timeout_ms")
    @Output(name = "title", type = OoaType.STRING, description = "Title of the current tab (when attached).")
    @Output(name = "url", type = OoaType.STRING, description = "Address of the current tab (when attached).")
    @Output(name = "tab_count", type = OoaType.NUMBER, description = "Number of open tabs (when attached).")
    @Output(name = "window", type = OoaType.DICTIONARY, description = "A window choice for this session, to pass as the window of element actions.")
    @Output(name = "endpoint", type = OoaType.STRING, description = "Debugging address to attach to.")
    @Output(name = "started", type = OoaType.BOOLEAN, description = "True when a new browser was started; false when one already listened on the port.")
    @Output(name = "pid", type = OoaType.NUMBER, description = "Process id of the started browser, or 0 when an existing one was reused.")
    public Map<String, Object> openForAutomation(ActionContext ctx,
            @Input(name = "browser", type = OoaType.STRING, required = false, defaultValue = "\"edge\"", enumValues = {"edge", "chrome"}, description = "Installed browser to start.") String which,
            @Input(name = "port", type = OoaType.NUMBER, required = false, defaultValue = "9222", description = "Debugging port. 9222 is the one window choices look at by themselves.") int port,
            @Input(name = "url", type = OoaType.STRING, required = false, description = "Page to open first.") String url,
            @Input(name = "profile_folder", type = OoaType.FILE, required = false,
                    description = "Profile folder for this browser (cookies, sign-ins). Default: .ooa/browser-profiles/<browser>-<port> in the workspace.") String profile,
            @Input(name = "headless", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Run without a visible window.") boolean headless,
            @Input(name = "attach", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Also attach to it as the named session.") boolean attach,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"browser\"", description = "Session name.") String session,
            @Input(name = "executable", type = OoaType.FILE, required = false, description = "Path of the browser program, when it is not installed in the usual place.") String executable,
            @Input(name = "timeout_ms", type = OoaType.NUMBER, required = false, defaultValue = "30000", description = "How long to wait for the browser to start, in milliseconds.") double timeout) {
        if (port < 1 || port > 65535) throw new ActionException("BROWSER.BAD_PORT", "The port must be between 1 and 65535");
        String kind = "chrome".equals(which) ? "chrome" : "edge";
        String ep = "http://127.0.0.1:" + port;
        boolean started = false;
        if ("headless".equals(System.getProperty("ooa.runMode"))) headless = true;
        long pid = 0;
        if (BrowserWindows.version(ep) == null) {
            if (!BrowserWindows.portFree(port))
                throw new ActionException("BROWSER.PORT_IN_USE", "Port " + port + " is used by another program that is not a browser", "Choose another port.");
            Path exe = BrowserWindows.executable(kind, executable);
            Path dir = profile != null && !profile.isBlank() ? ctx.resolvePath(profile) : ctx.workDir().resolve(".ooa").resolve("browser-profiles").resolve(kind + "-" + port);
            Process p = BrowserWindows.start(exe, port, dir, url, headless);
            pid = p.pid();
            started = true;
            long end = System.currentTimeMillis() + (long) Math.max(1000, timeout);
            while (BrowserWindows.version(ep) == null) {
                ctx.checkCancelled();
                if (System.currentTimeMillis() >= end) {
                    throw new ActionException("BROWSER.NOT_LISTENING", "The browser started but did not open debugging port " + port + " within " + (long) timeout + " ms",
                            "If this profile folder is already open in a browser without a debugging port, close that window, or choose another profile folder.", true);
                }
                try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new ActionException("RUN.CANCELLED", "Interrupted"); }
            }
            ctx.info("Started " + kind + " with debugging port " + port + " and profile " + dir);
        } else ctx.info("A browser already listens on port " + port + "; reusing it");
        Map<String, Object> out = new LinkedHashMap<>();
        if (attach) {
            BrowserSession bs = BrowserWindows.attach(ctx, ep, kind, session, timeout);
            if (!started && url != null && !url.isBlank()) { try { bs.page.navigate(url); } catch (PlaywrightException e) { throw pw(e, "Opening " + url); } }
            out.putAll(attachedOut(bs, session));
        } else {
            out.put("title", ""); out.put("url", ""); out.put("tab_count", BigDecimal.ZERO);
            out.put("window", new LinkedHashMap<>(Map.of("source", "browser", "url", url == null ? "" : url)));
        }
        out.put("endpoint", ep);
        out.put("started", started);
        out.put("pid", BigDecimal.valueOf(pid));
        return out;
    }

    @Action(id = "list_windows", name = "List windows (design time)", category = "Browser", timeoutSeconds = 60, idempotent = true, capabilities = "browser",
            description = "List the browser tabs an agent can reach (in this run's sessions and in browsers with a debugging port) and, on Windows, the titles of "
                    + "open desktop windows. Used by Agent Studio to offer windows to choose from.",
            panel = "endpoints, include_desktop")
    @Output(name = "windows", type = OoaType.LIST, description = "One entry per window: source (browser or application), title, url and browser.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of windows listed.")
    public Map<String, Object> listWindows(ActionContext ctx,
            @Input(name = "endpoints", type = OoaType.LIST, required = false, description = "Debugging addresses to look at; default http://127.0.0.1:9222.") List<Object> endpoints,
            @Input(name = "include_desktop", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Also list desktop window titles (Windows only).") boolean desktop) {
        List<String> eps = new ArrayList<>();
        if (endpoints != null) for (Object e : endpoints) if (e != null && !String.valueOf(e).isBlank()) eps.add(String.valueOf(e));
        if (eps.isEmpty()) { eps.add(BrowserWindows.DEFAULT_ENDPOINT); eps.add("http://127.0.0.1:" + BrowserWindows.keepOpenPort("chrome")); }
        List<Object> rows = new ArrayList<>();
        for (Map<String, Object> t : ExtensionBridge.tabs()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("source", "browser"); r.put("title", t.get("title")); r.put("url", t.get("url")); r.put("browser", t.get("browser"));
            r.put("tab_id", t.get("tab_id")); r.put("window_id", t.get("window_id")); r.put("connector", true);
            rows.add(r);
        }
        rows.addAll(BrowserWindows.browserTabs(ctx, eps));
        if (desktop) rows.addAll(BrowserWindows.desktopWindows(ctx));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("windows", rows);
        out.put("count", BigDecimal.valueOf(rows.size()));
        return out;
    }

    // ---------- Web recorder ----------
    // package-private for RecorderOutlineTest
    static final String RECORDER_JS = """
        (() => {
          // a page recorded before (and left open) starts reporting again and gets its bar back
          if (window.__ooaRec) { window.__ooaRecOff = false; if (window.__ooaBar) window.__ooaBar(); return; }
          window.__ooaRec = true;
          /*OOA_LIB*/
          const view = (t) => { const r = t.getBoundingClientRect(); return { x: r.left, y: r.top, width: r.width, height: r.height }; };
          const cap = (t) => ({ props: __props(t), css: __cssPath(t), xpath: __absXpath(t), dom_xpath: __domXpath(t), page_rect: __rect(t), rect: view(t),
                                page: { url: location.href, title: document.title } });
          const esc = (s) => String(s).replace(/\\\\/g, '\\\\\\\\').replace(/"/g, '\\\\"');
          const sel = (el) => {
            if (!el || el.nodeType !== 1) return null;
            const tid = el.getAttribute('data-testid'); if (tid) return '[data-testid="' + esc(tid) + '"]';
            if (el.id && !/\\d{3,}|^\\d/.test(el.id)) return '#' + CSS.escape(el.id);
            const name = el.getAttribute('name');
            if (name && /^(INPUT|SELECT|TEXTAREA)$/.test(el.tagName)) return el.tagName.toLowerCase() + '[name="' + esc(name) + '"]';
            const aria = el.getAttribute('aria-label'); if (aria) return '[aria-label="' + esc(aria) + '"]';
            if (/^(BUTTON|A)$/.test(el.tagName) || el.getAttribute('role') === 'button') {
              const t = (el.innerText || '').trim(); if (t && t.length < 60 && !t.includes('\\n')) return 'text="' + esc(t) + '"';
            }
            const parts = []; let e = el;
            while (e && e.nodeType === 1 && parts.length < 5) {
              if (e.id && !/\\d{3,}/.test(e.id)) { parts.unshift('#' + CSS.escape(e.id)); break; }
              let p = e.tagName.toLowerCase();
              const sib = e.parentElement ? Array.from(e.parentElement.children).filter(c => c.tagName === e.tagName) : [];
              if (sib.length > 1) p += ':nth-of-type(' + (sib.indexOf(e) + 1) + ')';
              parts.unshift(p); e = e.parentElement;
            }
            return parts.join(' > ');
          };
          const textInput = (t) => (t.tagName === 'TEXTAREA') || (t.tagName === 'INPUT' && !/^(checkbox|radio|submit|button|file|image|reset)$/.test(t.type));
          // the element a click would record, outlined under the pointer (as Capture does); it turns green when recorded
          const target = (el) => (el.closest && el.closest('button,a,input,select,textarea,[role=button],[onclick],label')) || el;
          const box = () => {
            let h = document.getElementById('__ooa_hl');
            if (!h && document.body) {
              h = document.createElement('div'); h.id = '__ooa_hl';
              h.style.cssText = 'position:fixed;z-index:2147483646;pointer-events:none;border:2px solid #a371ed;background:rgba(163,113,237,.12);border-radius:3px;display:none';
              document.body.appendChild(h);
            }
            return h;
          };
          // drawn at once on each move (not on an animation frame, which a covered window holds back); nothing over the
          // page itself (html, body) or the bar
          document.addEventListener('mousemove', (e) => {
            const h = box(), over = e.target; if (!h) return;
            if (window.__ooaRecOff || !over || over.nodeType !== 1 || over === document.documentElement || over === document.body || over.id === '__ooa_hl'
                || (over.closest && over.closest('#__ooa_bar'))) { h.style.display = 'none'; return; }
            const r = target(over).getBoundingClientRect();
            Object.assign(h.style, { display: 'block', left: r.left + 'px', top: r.top + 'px', width: r.width + 'px', height: r.height + 'px' });
          }, true);
          addEventListener('scroll', () => { const h = document.getElementById('__ooa_hl'); if (h) h.style.display = 'none'; }, true);
          const flash = () => { const h = document.getElementById('__ooa_hl'); if (!h) return; h.style.borderColor = '#1d7a46'; setTimeout(() => { h.style.borderColor = '#a371ed'; }, 400); };
          document.addEventListener('click', (e) => {
            if (window.__ooaRecOff) return;
            if (e.target.closest && e.target.closest('#__ooa_bar')) return;
            const t = target(e.target);
            if (textInput(t) || t.tagName === 'SELECT') return;
            window.__ooaRecord(Object.assign({ kind: 'click', selector: sel(t), label: (t.innerText || t.value || '').trim().slice(0, 40) }, cap(t)));
            flash();
          }, true);
          document.addEventListener('change', (e) => {
            if (window.__ooaRecOff) return;
            const t = e.target;
            if (t.tagName === 'SELECT') { window.__ooaRecord(Object.assign({ kind: 'select', selector: sel(t), value: t.value,
              option: t.selectedIndex >= 0 ? (t.options[t.selectedIndex].text || '').trim() : null }, cap(t))); flash(); }
            else if (textInput(t)) { window.__ooaRecord(Object.assign({ kind: 'type', selector: sel(t), value: t.type === 'password' ? null : t.value, password: t.type === 'password' }, cap(t))); flash(); }
          }, true);
          const bar = () => {
            if (document.getElementById('__ooa_bar') || !document.body) return;
            const b = document.createElement('div'); b.id = '__ooa_bar';
            b.style.cssText = 'position:fixed;z-index:2147483647;top:10px;right:10px;background:#2f3336;color:#fff;font:13px Segoe UI,sans-serif;padding:8px 12px;border-radius:10px;box-shadow:0 6px 18px rgba(0,0,0,.3);display:flex;gap:10px;align-items:center';
            const dot = document.createElement('span'); dot.style.cssText = 'width:10px;height:10px;border-radius:50%;background:#e5484d';
            const label = document.createElement('span'); label.textContent = 'OOA recording';
            const stop = document.createElement('button'); stop.type = 'button'; stop.textContent = 'Stop recording';
            stop.style.cssText = 'background:#0a7bbb;color:#fff;border:0;border-radius:6px;padding:5px 10px;cursor:pointer;font:inherit';
            stop.onclick = () => window.__ooaStop();
            b.append(dot, label, stop); document.body.appendChild(b);
          };
          window.__ooaBar = bar;
          if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', bar); else bar();
        })();
        """;

    @SuppressWarnings("unchecked")
    @Action(id = "record", name = "Record web actions", category = "Recorder", timeoutSeconds = 3600, capabilities = "browser",
            description = "Open a browser, record your clicks, typing and selections as steps, and return them when you press Stop recording or close the browser. "
                    + "Edge and Chrome stay open afterwards (on the agents' debugging port), so the page can be captured again. Passwords are never recorded.",
            panel = "url, browser | timeout_minutes, tab_id, endpoint, tab_title, tab_url")
    @Output(name = "steps", type = OoaType.LIST, description = "Recorded steps, ready to insert into an agent.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of steps.")
    public Map<String, Object> record(ActionContext ctx,
            @Input(name = "url", type = OoaType.STRING, description = "Page to start on.") String url,
            @Input(name = "browser", type = OoaType.STRING, required = false, defaultValue = "\"edge\"", enumValues = {"edge", "chrome", "chromium"}, description = "Browser to record in.") String which,
            @Input(name = "timeout_minutes", type = OoaType.NUMBER, required = false, defaultValue = "30", description = "Stop recording automatically after this long.") int minutes,
            @Input(name = "tab_id", type = OoaType.NUMBER, required = false,
                    description = "Record in this open tab of a browser connected through the ONEOFFICEAUTOMATION Browser Connector (from the open-windows list) instead of a new browser.") Long tabId,
            @Input(name = "endpoint", type = OoaType.STRING, required = false,
                    description = "Record in an open tab of the browser on this debugging address (the one agents keep open, from the open-windows list) instead of a new window.") String endpoint,
            @Input(name = "tab_title", type = OoaType.STRING, required = false, description = "With endpoint: the tab to record in, by its exact title.") String tabTitle,
            @Input(name = "tab_url", type = OoaType.STRING, required = false, description = "With endpoint: the tab to record in, by its address.") String tabUrl) {
        if (tabId != null) return recordInTab(ctx, which, tabId, minutes);
        if (endpoint != null && !endpoint.isBlank()) return recordKeptOpen(ctx, null, which, minutes, endpoint, tabTitle, tabUrl);
        if (!which.equals("chromium")) return recordKeptOpen(ctx, url, which, minutes, null, null, null);
        List<Map<String, Object>> events = Collections.synchronizedList(new ArrayList<>());
        final boolean[] stopped = {false};
        Map<String, String> env = new HashMap<>();
        if (!which.equals("chromium")) env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
        try (Playwright pw = Playwright.create(new Playwright.CreateOptions().setEnv(browserEnv(env)))) {
            BrowserType.LaunchOptions lo = new BrowserType.LaunchOptions().setHeadless(false);
            if (which.equals("edge")) lo.setChannel("msedge");
            if (which.equals("chrome")) lo.setChannel("chrome");
            com.microsoft.playwright.Browser b = pw.chromium().launch(lo);
            BrowserContext c = b.newContext();
            List<Frame> eventFrames = Collections.synchronizedList(new ArrayList<>());
            c.exposeBinding("__ooaRecord", (source, args) -> {
                if (args.length > 0 && args[0] instanceof Map<?, ?> m && events.size() < 500) { events.add(withShot(source, (Map<String, Object>) m)); eventFrames.add(source.frame()); ctx.info("Recorded step " + events.size()); }
                return null; });
            c.exposeBinding("__ooaStop", (source, args) -> { stopped[0] = true; return null; });
            c.addInitScript(RECORDER_JS.replace("/*OOA_LIB*/", WebObjects.LIB_JS));
            Page p = c.newPage();
            p.navigate(url);
            long end = System.currentTimeMillis() + minutes * 60_000L;
            while (!stopped[0] && System.currentTimeMillis() < end) {
                ctx.checkCancelled();
                if (c.pages().isEmpty()) break;
                try { c.pages().get(0).waitForTimeout(250); } catch (PlaywrightException e) { break; }
            }
            // The iframe chain of each recorded element, while the frames are still there.
            for (int i = 0; i < events.size() && i < eventFrames.size(); i++) {
                List<String> frames = new ArrayList<>();
                try {
                    for (Frame f = eventFrames.get(i); f != null && f.parentFrame() != null; f = f.parentFrame())
                        frames.add(0, String.valueOf(f.frameElement().evaluate(FRAME_SELECTOR_JS)));
                } catch (PlaywrightException ignored) { /* the frame is gone; the object still searches the page */ }
                events.get(i).put("frames", frames);
            }
            try { b.close(); } catch (PlaywrightException ignored) { }
        } catch (PlaywrightException e) { throw pw(e, "Recording"); }
        return stepsFromEvents(ctx, events, url, which, false);
    }

    /**
     * Records in a window of the Edge or Chrome that agents keep open (debugging port 9222 / 9223, started with its own
     * profile when needed). Stop recording, closing the window, or the time running out ends the recording; the browser
     * and the page stay open, so the objects can be captured again or the agent run against the same page.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> recordKeptOpen(ActionContext ctx, String url, String which, int minutes, String endpoint, String tabTitle, String tabUrl) {
        List<Map<String, Object>> events = Collections.synchronizedList(new ArrayList<>());
        final boolean[] stopped = {false};
        String session = "__recorder";
        BrowserSession bs;
        if (endpoint == null) bs = BrowserWindows.openWindow(ctx, which, url, session, 60_000);
        else {
            // a tab already open in that browser: the same title and address first, then the address, then the title
            bs = BrowserWindows.attach(ctx, endpoint, which, session, 60_000);
            boolean hasTitle = tabTitle != null && !tabTitle.isBlank(), hasUrl = tabUrl != null && !tabUrl.isBlank();
            Page found = null;
            for (Page p : bs.context.pages()) if (hasTitle && hasUrl && tabTitle.equals(p.title()) && p.url().equals(tabUrl)) { found = p; break; }
            if (found == null) for (Page p : bs.context.pages()) if (hasUrl && p.url().startsWith(tabUrl)) { found = p; break; }
            if (found == null) for (Page p : bs.context.pages()) if (hasTitle && tabTitle.equals(p.title())) { found = p; break; }
            if (found == null) {
                ctx.sessions().remove(session);
                try { bs.close(); } catch (RuntimeException ignored) { }
                throw new ActionException("BROWSER.WINDOW_NOT_FOUND", "No open tab is titled '" + tabTitle + "'" + (hasUrl ? " or at " + tabUrl : ""),
                        "Choose the window again from the list; it may have been closed or navigated.");
            }
            bs.page = found;
        }
        Page page = bs.page;
        try {
            BrowserContext c = bs.context;
            List<Frame> eventFrames = Collections.synchronizedList(new ArrayList<>());
            String js = RECORDER_JS.replace("/*OOA_LIB*/", WebObjects.LIB_JS);
            c.exposeBinding("__ooaRecord", (source, args) -> {
                if (source.page() != page) return null;   // only the recording window
                if (args.length > 0 && args[0] instanceof Map<?, ?> m && events.size() < 500) { events.add(withShot(source, (Map<String, Object>) m)); eventFrames.add(source.frame()); ctx.info("Recorded step " + events.size()); }
                return null; });
            c.exposeBinding("__ooaStop", (source, args) -> { if (source.page() == page) stopped[0] = true; return null; });
            page.addInitScript(js);
            for (Frame f : page.frames()) { try { f.evaluate("() => { " + js + " }"); } catch (PlaywrightException ignored) { /* a frame that is still loading gets it from the init script */ } }
            page.bringToFront();
            long end = System.currentTimeMillis() + minutes * 60_000L;
            while (!stopped[0] && System.currentTimeMillis() < end && !page.isClosed()) {
                ctx.checkCancelled();
                try { page.waitForTimeout(250); } catch (PlaywrightException e) { break; }
            }
            for (int i = 0; i < events.size() && i < eventFrames.size(); i++) {
                List<String> frames = new ArrayList<>();
                try {
                    for (Frame f = eventFrames.get(i); f != null && f.parentFrame() != null; f = f.parentFrame())
                        frames.add(0, String.valueOf(f.frameElement().evaluate(FRAME_SELECTOR_JS)));
                } catch (PlaywrightException ignored) { /* the frame is gone; the object still searches the page */ }
                events.get(i).put("frames", frames);
            }
            // the page stays as it is: the recording bar goes and the page stops reporting
            if (!page.isClosed()) for (Frame f : page.frames()) {
                try { f.evaluate("() => { window.__ooaRecOff = true; for (const id of ['__ooa_bar', '__ooa_hl']) { const b = document.getElementById(id); if (b) b.remove(); } }"); } catch (PlaywrightException ignored) { }
            }
        } catch (PlaywrightException e) { throw pw(e, "Recording"); }
        finally {
            // disconnect only: the window stays open (the session never closes pages it did not open itself)
            Object sess = ctx.sessions().remove(session);
            if (sess instanceof BrowserSession b) { b.ownPages.clear(); try { b.close(); } catch (RuntimeException ignored) { } }
        }
        ctx.info("Recording ended; the browser window stays open");
        return stepsFromEvents(ctx, events, url, which, true);
    }

    /** Recorded events (click, type, select with the element's properties) as steps; open/close steps when a URL is given. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> stepsFromEvents(ActionContext ctx, List<Map<String, Object>> events, String url, String which, boolean inTab) {
        List<Object> steps = new ArrayList<>();
        if (url != null) steps.add(stepMap("ooa.browser/open", Map.of("url", url, "browser", which), "Recorded: open " + url));
        Map<String, Object> lastType = null;
        boolean password = false;
        for (Map<String, Object> e : events) {
            String kind = String.valueOf(e.get("kind")), sel = String.valueOf(e.get("selector"));
            // Each step gets the full captured object (every property, editable in Studio); typed text is not a property to match.
            Map<String, Object> target = new LinkedHashMap<>();
            if (e.get("props") instanceof Map<?, ?> pm) {
                Map<String, Object> picked = new LinkedHashMap<>(e);
                Map<String, Object> props = new LinkedHashMap<>((Map<String, Object>) pm);
                if (!kind.equals("click")) { props.remove("value"); props.remove("selected"); }
                picked.put("props", props);
                target.put("object", capturedObject(picked));
            } else target.put("selector", sel);
            if (inTab && e.get("page") instanceof Map<?, ?> pg) {
                // recorded in the person's own browser: each step names the window it happened in, as Capture does
                Map<String, Object> w = new LinkedHashMap<>();
                w.put("source", "browser");
                if (pg.get("title") != null && !String.valueOf(pg.get("title")).isBlank()) { w.put("title", String.valueOf(pg.get("title"))); w.put("title_match", "equals"); }
                if (pg.get("url") != null) w.put("link", String.valueOf(pg.get("url")));
                w.put("browser", which);
                if (w.containsKey("title")) target.put("window", w);
            }
            if (kind.equals("type")) {
                boolean pwd = Boolean.TRUE.equals(e.get("password"));
                password |= pwd;
                Object text = pwd ? "$password" : String.valueOf(e.getOrDefault("value", ""));
                if (lastType != null && sel.equals(lastType.get("selector"))) { ((Map<String, Object>) lastType.get("args")).put("text", text); continue; }
                Map<String, Object> args = new LinkedHashMap<>(target);
                args.put("text", text);
                Map<String, Object> step = stepMap("ooa.browser/type_text", args, pwd ? "Recorded password field: map a Credential variable named password" : "Recorded typing" + label(e));
                steps.add(step);
                lastType = Map.of("selector", sel, "args", step.get("args"));
            } else {
                lastType = null;
                Map<String, Object> args = new LinkedHashMap<>(target);
                if (kind.equals("select")) {
                    args.put("option", String.valueOf(e.get("option") != null ? e.get("option") : e.get("value")));
                    steps.add(stepMap("ooa.browser/select_option", args, "Recorded selection"));
                } else steps.add(stepMap("ooa.browser/click", args, "Recorded click" + label(e)));
            }
        }
        ctx.info("Recorded " + (steps.size() - (url != null ? 1 : 0)) + " action(s)" + (password ? "; password fields use the variable $password" : ""));
        return Map.of("steps", steps, "count", BigDecimal.valueOf(steps.size()));
    }

    /** Records in an open tab through the browser connector until Stop recording is pressed on the page (or the time runs out). */
    @SuppressWarnings("unchecked")
    Map<String, Object> recordInTab(ActionContext ctx, String which, long tabId, int minutes) {
        String token = "rec-" + UUID.randomUUID();
        List<Map<String, Object>> events = new ArrayList<>();
        Map<String, Object> tabInfo;
        try (ExtensionBridge.Subscription sub = ExtensionBridge.subscribe(m -> token.equals(m.get("token")))) {
            tabInfo = (Map<String, Object>) ExtensionBridge.call(which, "record", Map.of("tab_id", tabId, "token", token), 15000);
            ctx.info("Recording in " + which + ": press Stop recording on the page when you are done");
            long end = System.currentTimeMillis() + minutes * 60_000L;
            while (System.currentTimeMillis() < end) {
                ctx.checkCancelled();
                Map<String, Object> ev;
                try { ev = sub.next(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                if (ev == null) continue;
                if ("record_stopped".equals(ev.get("event"))) break;
                if ("recorded".equals(ev.get("event")) && events.size() < 500) { events.add(new LinkedHashMap<>(ev)); ctx.info("Recorded step " + events.size() + ": " + ev.get("kind")); }
            }
        } finally {
            try { ExtensionBridge.call(which, "record_stop", Map.of(), 10000); } catch (ActionException ignored) { }
        }
        return stepsFromEvents(ctx, events, null, which, true);
    }

    private static String label(Map<String, Object> e) {
        Object l = e.get("label");
        if ((l == null || String.valueOf(l).isBlank()) && e.get("props") instanceof Map<?, ?> p) l = p.get("label") != null ? p.get("label") : p.get("name");
        return l == null || String.valueOf(l).isBlank() ? "" : ": " + String.valueOf(l).strip();
    }

    private static Map<String, Object> stepMap(String action, Map<String, Object> args, String note) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("action", action);
        s.put("args", new LinkedHashMap<>(args));
        s.put("description", note);
        return s;
    }
    // ---------- Capture: element actions and design-time picker ----------
    @SuppressWarnings("unchecked")
    @Action(id = "element_action", name = "Capture: element action", category = "Capture", timeoutSeconds = 300, capabilities = "browser",
            description = "Do one action on a captured element: click, type, read text, attributes or all properties, select, check, hover, drag, upload, wait and more. "
                    + "The element is the captured object (properties you choose, each with its own match rule) or a selector.",
            panel = "window, object, selector, action, value, row, column, run_in_background | typing, key_delay_ms, clear_first, click_method, modifiers, offset_x, offset_y, force, wait_after_ms, use_captured_page, alternatives, session, timeout_ms, wait_for_page_ms, if_not_found")
    @Output(name = "result", description = "Value returned by get, is, count, wait and coordinate actions.")
    @Output(name = "found", type = OoaType.BOOLEAN, description = "False when the step was skipped because the element was not found (if_not_found = continue).")
    public Map<String, Object> elementAction(ActionContext ctx,
            @Input(name = "object", type = OoaType.DICTIONARY, required = false, description = "The captured object from Capture in Agent Studio: the properties to match "
                    + "(each with use and a match rule: equals, not_equals, contains, starts_with, ends_with, wildcard, regex), search_by (properties, css or xpath), "
                    + "index when several elements match, and iframes. Used instead of the selector when given.") Object objectArg,
            @Input(name = "selector", type = OoaType.STRING, required = false, description = D_SEL + " Used when no captured object is given.") String sel,
            @Input(name = "action", type = OoaType.STRING, enumValues = {"click", "double_click", "right_click", "middle_click", "hover", "focus", "set_text", "append_text", "clear",
                    "press_key", "get_text", "get_value", "get_attribute", "get_property", "get_all_properties", "get_html", "get_inner_html", "get_coordinates",
                    "get_children_text", "get_table", "select_by_text", "select_by_value", "select_by_index", "get_selected", "get_options", "check", "uncheck",
                    "is_checked", "is_visible", "is_enabled", "is_present", "count", "scroll_into_view", "highlight", "wait_to_appear", "wait_to_disappear",
                    "drag_to", "upload_file", "screenshot", "get_status", "toggle", "select", "get_selected_index", "get_total_items", "get_total_rows", "get_total_columns",
                    "get_cell_text", "get_cell_index_by_text", "set_cell_text", "click_cell", "set_position", "get_control_type"},
                    description = "What to do with the element. Studio lists the actions that suit the captured control type.") String action,
            @Input(name = "value", required = false, description = "Depends on the action: the text to type, the key (e.g. Enter, Ctrl+A), the attribute or property name, "
                    + "the option text, value or index, the target selector for drag_to, the file path(s) for upload_file, or the image path for screenshot. Credentials allowed for set_text.") Object value,
            @Input(name = "timeout_ms", type = OoaType.NUMBER, required = false, defaultValue = "30000", description = D_TIMEOUT) double timeout,
            @Input(name = "click_method", type = OoaType.STRING, required = false, defaultValue = "\"mouse\"", enumValues = {"mouse", "javascript"},
                    description = "mouse: a real click at the element (scrolls it into view and waits until it can be clicked); javascript: fire the click event directly, for elements that are covered or off screen.") String clickMethod,
            @Input(name = "modifiers", type = OoaType.STRING, required = false, description = "Keys held during the click, e.g. CTRL, SHIFT or CTRL+SHIFT.") String modifiers,
            @Input(name = "offset_x", type = OoaType.NUMBER, required = false, description = "Click this many pixels from the element's left edge instead of its centre.") Double offsetX,
            @Input(name = "offset_y", type = OoaType.NUMBER, required = false, description = "Click this many pixels from the element's top edge instead of its centre.") Double offsetY,
            @Input(name = "typing", type = OoaType.STRING, required = false, defaultValue = "\"fill\"", enumValues = {"fill", "keystrokes"},
                    description = "fill: set the whole text at once (fast); keystrokes: type key by key like a person, for fields that react to each key.") String typing,
            @Input(name = "key_delay_ms", type = OoaType.NUMBER, required = false, defaultValue = "0", description = "Pause between keys when typing is keystrokes.") double keyDelay,
            @Input(name = "clear_first", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Clear the field before set_text.") boolean clearFirst,
            @Input(name = "force", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Act even if the element looks hidden, covered or disabled.") boolean force,
            @Input(name = "wait_after_ms", type = OoaType.NUMBER, required = false, defaultValue = "0", description = "Pause after the action, e.g. to let the page react.") double waitAfter,
            @Input(name = "use_captured_page", type = OoaType.BOOLEAN, required = false, defaultValue = "false",
                    description = "Switch to the open tab whose address (and title, when its match rule is set) matches the page recorded with the captured object.") boolean useCapturedPage,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"browser\"", description = "Session name.") String session,
            @Input(name = "alternatives", type = OoaType.DICTIONARY, required = false, description = D_ALT) Map<String, Object> alternatives,
            @Input(name = "window", type = OoaType.DICTIONARY, required = false, description = BrowserWindows.D_WINDOW) Object window,
            @Input(name = "wait_for_page_ms", type = OoaType.NUMBER, required = false, defaultValue = "0",
                    description = "First wait up to this long for the page to finish loading (0: don't wait). Counts as not found when it runs out.") double waitForPage,
            @Input(name = "if_not_found", type = OoaType.STRING, required = false, defaultValue = "\"error\"", enumValues = {"error", "continue"},
                    description = "When the page or the element is not there in time: error stops with an error; continue skips this step (result is empty) and carries on.") String ifNotFound,
            @Input(name = "row", type = OoaType.NUMBER, required = false, description = "Table cell actions: the row, 1 for the first.") Integer row,
            @Input(name = "column", type = OoaType.NUMBER, required = false, description = "Table cell actions: the column, 1 for the first.") Integer column,
            @Input(name = "run_in_background", type = OoaType.BOOLEAN, required = false, defaultValue = "false",
                    description = "Leave the browser window where it is: the tab is not brought to the front and clicks are sent to the page (not with the mouse), so other work on the screen can go on.") boolean runInBackground) {
        if ("continue".equals(ifNotFound)) {
            try { return elementAction(ctx, objectArg, sel, action, value, timeout, clickMethod, modifiers, offsetX, offsetY, typing, keyDelay, clearFirst, force, waitAfter,
                    useCapturedPage, session, alternatives, window, waitForPage, "error", row, column, runInBackground); }
            catch (ActionException e) {
                if (!NOT_THERE.contains(e.code())) throw e;
                ctx.warn("Skipped '" + action + "': " + e.getMessage());
                Map<String, Object> out = single(null);
                out.put("found", false);
                return out;
            }
        }
        session = BrowserWindows.use(ctx, session, window, timeout, runInBackground);
        if (tab(ctx, session) != null) {
            Map<String, Object> opts = new LinkedHashMap<>();
            opts.put("click_method", clickMethod == null ? "mouse" : clickMethod);
            if (modifiers != null) opts.put("modifiers", modifiers);
            if (offsetX != null) opts.put("offset_x", offsetX);
            if (offsetY != null) opts.put("offset_y", offsetY);
            opts.put("typing", typing == null ? "fill" : typing);
            opts.put("key_delay_ms", keyDelay);
            opts.put("clear_first", clearFirst);
            if (row != null) opts.put("row", row);
            if (column != null) opts.put("column", column);
            String tabAction = action;
            if (action.equals("upload_file")) {
                // the files travel to the page (the connector cannot read the disk): at most 50 MB in all
                List<Map<String, Object>> files = new ArrayList<>();
                long total = 0;
                for (Path f : uploadFiles(ctx, value)) {
                    try {
                        byte[] b = Files.readAllBytes(f);
                        total += b.length;
                        if (total > 50L * 1024 * 1024) throw new ActionException("BROWSER.FILE_TOO_LARGE", "Files over 50 MB in all cannot go through the browser connector", "Use Open browser with the built-in browser for large uploads.");
                        String type = Files.probeContentType(f);
                        files.add(Map.of("name", f.getFileName().toString(), "type", type == null ? "application/octet-stream" : type, "base64", Base64.getEncoder().encodeToString(b)));
                    } catch (java.io.IOException e) { throw new ActionException("BROWSER.FILE_ERROR", "Cannot read " + f + ": " + e.getMessage()); }
                }
                opts.put("files", files);
                tabAction = "upload_files";
            }
            if (action.equals("drag_to")) {
                if (value == null || String.valueOf(value).isBlank()) throw new ActionException("BROWSER.BAD_INPUT", "drag_to needs the target selector in value");
                opts.put("target", String.valueOf(value));
            }
            Object r = onElement(ctx, tab(ctx, session), objectArg, sel, tabAction, value, timeout, opts);
            if (r instanceof Number n && tabAction.equals("upload_files")) r = new BigDecimal(n.toString());
            if (action.equals("screenshot") && r != null) {
                Path out = ctx.workDir().resolve(String.valueOf(value));
                try { if (out.getParent() != null) Files.createDirectories(out.getParent()); Files.write(out, Base64.getDecoder().decode(String.valueOf(r))); }
                catch (java.io.IOException e) { throw new ActionException("BROWSER.FILE_ERROR", "Cannot write the screenshot: " + e.getMessage()); }
                r = out.toString();
            }
            if (waitAfter > 0) { try { Thread.sleep((long) waitAfter); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
            return single(r instanceof Number n ? new BigDecimal(n.toString()) : r);
        }
        // a tab that is not in front may not paint, so a click waits for nothing and goes straight to the element
        if (runInBackground && "click".equals(action)) clickMethod = "javascript";
        BrowserSession bs = s(ctx, session);
        if (waitForPage > 0) {
            try { bs.page.waitForLoadState(com.microsoft.playwright.options.LoadState.LOAD, new Page.WaitForLoadStateOptions().setTimeout(waitForPage)); }
            catch (PlaywrightException e) { throw new ActionException("BROWSER.PAGE_TIMEOUT", "The page did not finish loading within " + (long) waitForPage + " ms", "Raise wait_for_page_ms, or set if_not_found to continue.", true); }
        }
        Map<String, Object> object = objectArg == null ? null : WebObjects.object(objectArg);
        if (object == null && (sel == null || sel.isBlank()))
            throw new ActionException("BROWSER.NO_ELEMENT", "Give the captured object or a selector", "Use Capture in Agent Studio, or type a selector.");
        String what = object != null ? "the captured object " + WebObjects.describe(object) : sel;
        boolean ask = action.equals("count") || action.equals("is_visible") || action.equals("is_present");
        Locator l = null;
        Object result = null;
        if (ask) {
            UiGuard.before(ctx);
            Page page = object != null ? WebObjects.page(bs, object, useCapturedPage) : bs.page;
            int n;
            try {
                if (object != null) {
                    Locator[] found = WebObjects.findAll(page, object);
                    n = found.length;
                    int idx = object.get("index") instanceof Number x ? x.intValue() : 0;
                    l = found.length > idx ? found[idx] : null;
                } else { Locator all = page.locator(sel); n = all.count(); l = n > 0 ? ObjectRecorder.note(ctx, all.first(), sel, 0) : null; }
                result = switch (action) {
                    case "count" -> BigDecimal.valueOf(n);
                    case "is_present" -> l != null;
                    default -> l != null && l.isVisible();
                };
            } catch (PlaywrightException e) { throw pw(e, action + " on " + what); }
            return single(result);
        }
        if (action.equals("wait_to_disappear")) {
            long end = System.currentTimeMillis() + (long) timeout;
            while (true) {
                ctx.checkCancelled();
                boolean present;
                try {
                    if (object != null) { Locator[] f = WebObjects.findAll(WebObjects.page(bs, object, useCapturedPage), object); present = Arrays.stream(f).anyMatch(Locator::isVisible); }
                    else { Locator f = bs.page.locator(sel); present = f.count() > 0 && f.first().isVisible(); }
                } catch (PlaywrightException e) { present = false; }
                if (!present) return single(true);
                if (System.currentTimeMillis() >= end) throw new ActionException("BROWSER.STILL_VISIBLE", what + " is still on the page after " + (long) timeout + " ms", "Raise timeout_ms.", true);
                try { bs.page.waitForTimeout(250); } catch (PlaywrightException e) { throw pw(e, "Waiting"); }
            }
        }
        try { l = object != null ? WebObjects.locate(ctx, bs, object, timeout, useCapturedPage) : element(ctx, session, sel, alternatives, timeout); }
        catch (PlaywrightException e) { throw pw(e, action + " on " + what); }
        String v = value instanceof SecureString ss ? ss.reveal() : value == null ? null : value instanceof List<?> ? null : Values.text(value);
        java.util.function.Supplier<String> need = () -> { if (v == null || v.isEmpty()) throw new ActionException("BROWSER.VALUE_REQUIRED", "The action '" + action + "' needs a value"); return v; };
        List<com.microsoft.playwright.options.KeyboardModifier> mods = modifiers(modifiers);
        com.microsoft.playwright.options.Position at = offsetX != null || offsetY != null
                ? new com.microsoft.playwright.options.Position(offsetX == null ? 1 : offsetX, offsetY == null ? 1 : offsetY) : null;
        boolean js = "javascript".equals(clickMethod);
        try {
            switch (action) {
                case "click" -> { if (js) l.evaluate("e => e.click()"); else { Locator.ClickOptions o = new Locator.ClickOptions().setTimeout(timeout).setForce(force).setModifiers(mods); if (at != null) o.setPosition(at); l.click(o); } }
                case "double_click" -> { if (js) l.evaluate("e => { e.click(); e.click(); e.dispatchEvent(new MouseEvent('dblclick', {bubbles: true})); }");
                    else { Locator.DblclickOptions o = new Locator.DblclickOptions().setTimeout(timeout).setForce(force).setModifiers(mods); if (at != null) o.setPosition(at); l.dblclick(o); } }
                case "right_click" -> { if (js) l.evaluate("e => e.dispatchEvent(new MouseEvent('contextmenu', {bubbles: true, button: 2}))");
                    else { Locator.ClickOptions o = new Locator.ClickOptions().setTimeout(timeout).setForce(force).setModifiers(mods).setButton(com.microsoft.playwright.options.MouseButton.RIGHT); if (at != null) o.setPosition(at); l.click(o); } }
                case "middle_click" -> { Locator.ClickOptions o = new Locator.ClickOptions().setTimeout(timeout).setForce(force).setModifiers(mods).setButton(com.microsoft.playwright.options.MouseButton.MIDDLE); if (at != null) o.setPosition(at); l.click(o); }
                case "hover" -> { Locator.HoverOptions o = new Locator.HoverOptions().setTimeout(timeout).setForce(force).setModifiers(mods); if (at != null) o.setPosition(at); l.hover(o); }
                case "focus" -> l.focus(new Locator.FocusOptions().setTimeout(timeout));
                case "set_text" -> {
                    String text = value instanceof SecureString ? v : need.get();
                    if ("keystrokes".equals(typing)) {
                        if (clearFirst) l.clear(new Locator.ClearOptions().setTimeout(timeout).setForce(force));
                        else { l.click(new Locator.ClickOptions().setTimeout(timeout)); l.press("End"); }
                        l.pressSequentially(text, new Locator.PressSequentiallyOptions().setDelay(keyDelay).setTimeout(timeout + text.length() * (keyDelay + 50)));
                    } else if (clearFirst) l.fill(text, new Locator.FillOptions().setTimeout(timeout).setForce(force));
                    else { l.click(new Locator.ClickOptions().setTimeout(timeout)); l.press("End"); l.pressSequentially(text, new Locator.PressSequentiallyOptions().setDelay(keyDelay)); }
                }
                case "append_text" -> { l.click(new Locator.ClickOptions().setTimeout(timeout)); l.press("End"); l.pressSequentially(need.get(), new Locator.PressSequentiallyOptions().setDelay(keyDelay)); }
                case "clear" -> l.clear(new Locator.ClearOptions().setTimeout(timeout).setForce(force));
                case "press_key" -> l.press(playwrightKeys(need.get()), new Locator.PressOptions().setTimeout(timeout));
                case "get_text" -> result = l.innerText(new Locator.InnerTextOptions().setTimeout(timeout)).strip();
                case "get_value" -> result = l.inputValue(new Locator.InputValueOptions().setTimeout(timeout));
                case "get_attribute" -> result = l.getAttribute(need.get(), new Locator.GetAttributeOptions().setTimeout(timeout));
                case "get_property" -> { l.waitFor(new Locator.WaitForOptions().setTimeout(timeout)); result = Values.toJsonable(l.evaluate("(e, p) => e[p]", need.get())); }
                case "get_all_properties" -> result = Values.toJsonable(l.evaluate(WebObjects.ALL_PROPS_JS));
                case "get_html" -> result = l.evaluate("e => e.outerHTML");
                case "get_inner_html" -> result = l.innerHTML(new Locator.InnerHTMLOptions().setTimeout(timeout));
                case "get_coordinates" -> {
                    var b = l.boundingBox(new Locator.BoundingBoxOptions().setTimeout(timeout));
                    if (b == null) throw new ActionException("BROWSER.NOT_VISIBLE", what + " has no position because it is not visible");
                    result = Map.of("x", BigDecimal.valueOf(Math.round(b.x)), "y", BigDecimal.valueOf(Math.round(b.y)), "width", BigDecimal.valueOf(Math.round(b.width)), "height", BigDecimal.valueOf(Math.round(b.height)));
                }
                case "get_children_text" -> result = Values.toJsonable(l.evaluate("e => Array.from(e.children).map(c => (c.innerText || c.textContent || '').trim())"));
                case "get_table" -> {
                    Object t = l.evaluate("""
                        e => { const t = e.closest('table') || e; const rows = Array.from(t.querySelectorAll('tr'));
                          if (!rows.length) return null;
                          const cells = (r) => Array.from(r.querySelectorAll('th,td')).map(c => (c.innerText || '').trim());
                          const head = rows[0].querySelector('th') ? cells(rows[0]) : cells(rows[0]).map((_, i) => 'Column' + (i + 1));
                          const body = rows.slice(rows[0].querySelector('th') ? 1 : 0).map(cells);
                          return { columns: head, rows: body }; }""");
                    if (t == null) throw new ActionException("BROWSER.NOT_A_TABLE", what + " is not a table and contains no table rows");
                    result = Values.toJsonable(t);
                }
                case "select_by_text" -> l.selectOption(new com.microsoft.playwright.options.SelectOption().setLabel(need.get()), new Locator.SelectOptionOptions().setTimeout(timeout).setForce(force));
                case "select_by_value" -> l.selectOption(need.get(), new Locator.SelectOptionOptions().setTimeout(timeout).setForce(force));
                case "select_by_index" -> l.selectOption(new com.microsoft.playwright.options.SelectOption().setIndex(Integer.parseInt(need.get().trim())), new Locator.SelectOptionOptions().setTimeout(timeout).setForce(force));
                case "get_selected" -> { l.waitFor(new Locator.WaitForOptions().setTimeout(timeout)); result = l.evaluate("e => e.options && e.selectedIndex >= 0 ? e.options[e.selectedIndex].text : null"); }
                case "get_options" -> { l.waitFor(new Locator.WaitForOptions().setTimeout(timeout)); result = l.evaluate("e => Array.from(e.options || []).map(o => o.text)"); }
                case "check" -> l.check(new Locator.CheckOptions().setTimeout(timeout).setForce(force));
                case "uncheck" -> l.uncheck(new Locator.UncheckOptions().setTimeout(timeout).setForce(force));
                case "is_checked" -> result = l.isChecked(new Locator.IsCheckedOptions().setTimeout(timeout));
                case "is_enabled" -> result = l.isEnabled(new Locator.IsEnabledOptions().setTimeout(timeout));
                case "scroll_into_view" -> l.scrollIntoViewIfNeeded(new Locator.ScrollIntoViewIfNeededOptions().setTimeout(timeout));
                case "highlight" -> { l.evaluate("e => { const o = e.style.outline; e.style.outline = '3px solid #a371ed'; setTimeout(() => { e.style.outline = o; }, 2000); }"); result = true; }
                case "wait_to_appear" -> { l.waitFor(new Locator.WaitForOptions().setTimeout(timeout).setState(WaitForSelectorState.VISIBLE)); result = true; }
                case "drag_to" -> l.dragTo(bs.page.locator(need.get()), new Locator.DragToOptions().setTimeout(timeout).setForce(force));
                case "upload_file" -> {
                    need.get();
                    List<Path> files = uploadFiles(ctx, value);
                    l.setInputFiles(files.toArray(Path[]::new), new Locator.SetInputFilesOptions().setTimeout(timeout));
                    result = BigDecimal.valueOf(files.size());
                }
                case "screenshot" -> {
                    Path out = ctx.workDir().resolve(need.get());
                    if (out.getParent() != null) Files.createDirectories(out.getParent());
                    l.screenshot(new Locator.ScreenshotOptions().setPath(out).setTimeout(timeout));
                    result = out.toString();
                }
                case "toggle" -> { if (l.isChecked(new Locator.IsCheckedOptions().setTimeout(timeout))) l.uncheck(new Locator.UncheckOptions().setTimeout(timeout).setForce(force));
                    else l.check(new Locator.CheckOptions().setTimeout(timeout).setForce(force)); result = l.isChecked(); }
                case "select" -> { l.check(new Locator.CheckOptions().setTimeout(timeout).setForce(force)); result = true; }
                case "get_status", "get_selected_index", "get_total_items", "get_total_rows", "get_total_columns", "get_cell_text", "get_cell_index_by_text",
                     "set_cell_text", "click_cell", "set_position", "get_control_type" -> {
                    if (action.endsWith("_cell") || action.startsWith("get_cell_text") || action.equals("set_cell_text"))
                        if (row == null || column == null) throw new ActionException("BROWSER.VALUE_REQUIRED", "The action '" + action + "' needs a row and a column (1 for the first)");
                    Map<String, Object> a = new LinkedHashMap<>();
                    if (row != null) a.put("row", row);
                    if (column != null) a.put("column", column);
                    a.put("text", v == null ? "" : v);
                    l.waitFor(new Locator.WaitForOptions().setTimeout(timeout));
                    result = Values.toJsonable(l.evaluate(EXTRA_JS, Map.of("op", action, "a", a)));
                }
                default -> throw new ActionException("BROWSER.BAD_ACTION", "Unknown element action '" + action + "'");
            }
        } catch (PlaywrightException e) { throw pw(e, action + " on " + what);
        } catch (java.io.IOException e) { throw new ActionException("BROWSER.FILE_ERROR", "Cannot write the screenshot: " + e.getMessage());
        } catch (NumberFormatException e) { throw new ActionException("BROWSER.BAD_INDEX", "select_by_index needs a whole number"); }
        if (waitAfter > 0) { try { bs.page.waitForTimeout(waitAfter); } catch (PlaywrightException ignored) { } }
        return single(result);
    }

    /** The actions shared with the browser connector (ooa-page.js): status, list counts, table cells, slider position. */
    static final String EXTRA_JS = "(el, q) => {\n" + WebObjects.LIB_JS + "\nreturn __extra(el, q.op, q.a);\n}";

    static final String D_IF_NOT_FOUND = "When the element is not there in time: error stops with an error; continue leaves the result empty, sets found to false and carries on.";

    /** Runs a read; with if_not_found = continue, an element that is not there gives an empty result and found = false. */
    private static Map<String, Object> orEmpty(String ifNotFound, String key, java.util.function.Supplier<Map<String, Object>> read) {
        Map<String, Object> out = new LinkedHashMap<>();
        try { out.putAll(read.get()); out.put("found", true); }
        catch (ActionException e) {
            if (!"continue".equals(ifNotFound) || !NOT_THERE.contains(e.code())) throw e;
            out.put(key, ""); out.put("found", false);
        }
        return out;
    }

    /** Errors that mean "the page or element was not there in time", which if_not_found = continue skips. */
    static final Set<String> NOT_THERE = Set.of("BROWSER.OBJECT_NOT_FOUND", "BROWSER.INDEX_OUT_OF_RANGE", "BROWSER.PAGE_TIMEOUT", "BROWSER.TIMEOUT",
            "BROWSER.WINDOW_NOT_FOUND", "UI.ELEMENT_NOT_FOUND", "BROWSER.ELEMENT_NOT_FOUND");

    private static Map<String, Object> single(Object result) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("result", result instanceof Boolean || result == null || result instanceof BigDecimal || result instanceof String || result instanceof Map || result instanceof List ? result : Values.toJsonable(result));
        return out;
    }

    static List<com.microsoft.playwright.options.KeyboardModifier> modifiers(String spec) {
        List<com.microsoft.playwright.options.KeyboardModifier> out = new ArrayList<>();
        if (spec == null || spec.isBlank()) return out;
        for (String p : spec.split("[+, ]+")) {
            switch (p.trim().toUpperCase(Locale.ROOT)) {
                case "CTRL", "CONTROL" -> out.add(com.microsoft.playwright.options.KeyboardModifier.CONTROL);
                case "SHIFT" -> out.add(com.microsoft.playwright.options.KeyboardModifier.SHIFT);
                case "ALT" -> out.add(com.microsoft.playwright.options.KeyboardModifier.ALT);
                case "WIN", "META", "CMD" -> out.add(com.microsoft.playwright.options.KeyboardModifier.META);
                case "" -> { }
                default -> throw new ActionException("BROWSER.BAD_MODIFIER", "Unknown modifier key '" + p + "'", "Use CTRL, SHIFT, ALT or WIN, joined with +.");
            }
        }
        return out;
    }

    @Condition(id = "element_visible", name = "Element is visible", description = "True when the captured element (or one matching the selector) is visible (right away, or within the wait).")
    public boolean elementVisible(ActionContext ctx,
            @Input(name = "object", type = OoaType.DICTIONARY, required = false, description = D_OBJ) Object object,
            @Input(name = "selector", type = OoaType.STRING, required = false, description = D_SEL_OPT) String sel,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"browser\"", description = "Session name.") String session,
            @Input(name = "window", type = OoaType.DICTIONARY, required = false, description = BrowserWindows.D_WINDOW) Object window,
            @Input(name = "wait_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0, description = D_COND_WAIT) double waitSeconds) {
        return waitUntil(ctx, waitSeconds, () -> elementVisible(ctx, object, sel, session, window));
    }

    /** Element is visible right now. */
    public boolean elementVisible(ActionContext ctx, Object object, String sel, String session, Object window) {
        try { session = BrowserWindows.use(ctx, session, window, 0); } catch (ActionException e) { if ("BROWSER.WINDOW_NOT_FOUND".equals(e.code())) return false; throw e; }
        try {
            if (tab(ctx, session) != null) return Boolean.TRUE.equals(onElement(ctx, tab(ctx, session), object, sel, "is_visible", null, 0, Map.of()));
            if (object != null) { Map<String, Object> o = WebObjects.object(object); int idx = o.get("index") instanceof Number n ? n.intValue() : 0;
                Locator[] f = WebObjects.findAll(s(ctx, session).page, o); int at = idx < 0 ? f.length + idx : idx; return at >= 0 && f.length > at && f[at].isVisible(); }
            if (sel == null || sel.isBlank()) throw new ActionException("BROWSER.NO_ELEMENT", "Give the captured object or a selector");
            boolean visible = s(ctx, session).page.locator(sel).first().isVisible();
            if (visible) ObjectRecorder.note(ctx, s(ctx, session).page.locator(sel).first(), sel, 0);
            return visible;
        } catch (PlaywrightException e) { throw pw(e, "Checking " + what(object, sel)); }
    }

    @Condition(id = "element_text", name = "Element text is", description = "True when the element's text (or a field's value) equals, contains, starts with or matches the text (right away, or within the wait).")
    public boolean elementText(ActionContext ctx,
            @Input(name = "object", type = OoaType.DICTIONARY, required = false, description = D_OBJ) Object object,
            @Input(name = "selector", type = OoaType.STRING, required = false, description = D_SEL_OPT) String sel,
            @Input(name = "text", type = OoaType.STRING, description = "Text to compare with.") String text,
            @Input(name = "match", type = OoaType.STRING, required = false, defaultValue = "\"equals\"", enumValues = {"equals", "contains", "starts_with", "matches"},
                    description = "How the text is compared; matches takes a regular expression.") String match,
            @Input(name = "ignore_case", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Upper and lower case count as the same.") boolean ignoreCase,
            @Input(name = "wait_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0, description = D_COND_WAIT) double waitSeconds,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"browser\"", description = "Session name.") String session,
            @Input(name = "window", type = OoaType.DICTIONARY, required = false, description = BrowserWindows.D_WINDOW) Object window) {
        return waitUntil(ctx, waitSeconds, () -> {
            String s2;
            try { s2 = session == null ? "browser" : BrowserWindows.use(ctx, session, window, 0); }
            catch (ActionException e) { if ("BROWSER.WINDOW_NOT_FOUND".equals(e.code())) return false; throw e; }
            if (countNowSafe(ctx, s2, object, sel) == 0) return false;
            String actual;
            try {
                Locator l = target(ctx, s2, object, sel, null, 1000);
                actual = String.valueOf(l.evaluate("e => (e.value !== undefined && /^(INPUT|TEXTAREA|SELECT)$/.test(e.tagName)) ? e.value : e.innerText")).strip();
            } catch (PlaywrightException | ActionException e) { return false; }
            return textMatches(actual, text, match, ignoreCase);
        });
    }

    static boolean textMatches(String actual, String want, String match, boolean ignoreCase) {
        String a = actual == null ? "" : actual, w = want == null ? "" : want;
        if ("matches".equals(match)) return java.util.regex.Pattern.compile(w, ignoreCase ? java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE : 0).matcher(a).find();
        if (ignoreCase) { a = a.toLowerCase(Locale.ROOT); w = w.toLowerCase(Locale.ROOT); }
        return switch (match == null ? "equals" : match) { case "contains" -> a.contains(w); case "starts_with" -> a.startsWith(w); default -> a.equals(w); };
    }

    private static int countNowSafe(ActionContext ctx, String session, Object object, String sel) {
        try { return countNow(ctx, session, object, sel); } catch (PlaywrightException e) { return 0; }
    }

    static final String D_COND_WAIT = "Wait up to this many seconds for it to become true (0 checks once, right away).";

    /** Checks the condition until it is true or the wait runs out (0: once). */
    static boolean waitUntil(ActionContext ctx, double seconds, java.util.function.BooleanSupplier check) {
        long end = System.currentTimeMillis() + (long) (seconds * 1000);
        while (true) {
            if (check.getAsBoolean()) return true;
            if (System.currentTimeMillis() >= end) return false;
            ctx.checkCancelled();
            try { Thread.sleep(250); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
    }

    @Action(id = "extract_list", name = "Extract list", category = "Data", timeoutSeconds = 120, capabilities = "browser",
            description = "Read repeated items (product cards, search results, list entries) into a table: one row per item, one column per field found inside it.",
            panel = "window, object, selector, fields, max_items | session, timeout_ms")
    @Output(name = "table", type = OoaType.TABLE, description = "One row per item.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of items.")
    public Map<String, Object> extractList(ActionContext ctx,
            @Input(name = "object", type = OoaType.DICTIONARY, required = false, description = "A captured item (one card or row): every element like it is read.") Object object,
            @Input(name = "selector", type = OoaType.STRING, required = false, example = ".product", description = "Selector of the repeated item, used when no captured item is given.") String sel,
            @Input(name = "fields", type = OoaType.DICTIONARY, example = "{\"Name\": \"h3 a\", \"Price\": \".price\", \"Link\": \"h3 a@href\"}",
                    description = "Column name to a selector inside the item; add @attribute to read an attribute (a@href); an empty selector reads the item's own text.") Map<String, Object> fields,
            @Input(name = "max_items", type = OoaType.NUMBER, required = false, min = 0, description = "Read at most this many items (all when empty).") Integer maxItems,
            @Input(name = "timeout_ms", type = OoaType.NUMBER, required = false, defaultValue = "30000", min = 0, description = D_TIMEOUT) double timeout,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"browser\"", description = "Session name.") String session,
            @Input(name = "window", type = OoaType.DICTIONARY, required = false, description = BrowserWindows.D_WINDOW) Object window) {
        session = BrowserWindows.use(ctx, session, window, timeout);
        if (fields == null || fields.isEmpty()) throw new ActionException("BROWSER.NO_FIELDS", "Name at least one field to read from each item", "Fields: {\"Name\": \"h3\", \"Price\": \".price\"}");
        List<String> cols = new ArrayList<>(fields.keySet());
        List<Object> specs = new ArrayList<>();
        for (String c : cols) specs.add(Values.text(fields.get(c)));
        try {
            Locator[] items;
            if (object != null) {
                target(ctx, session, object, null, null, timeout);
                Map<String, Object> o = new LinkedHashMap<>(WebObjects.object(object));
                o.remove("index");
                items = WebObjects.findAll(s(ctx, session).page, o);
            } else {
                if (sel == null || sel.isBlank()) throw new ActionException("BROWSER.NO_ELEMENT", "Give the captured item or a selector");
                Locator all = s(ctx, session).page.locator(sel);
                all.first().waitFor(new Locator.WaitForOptions().setTimeout(timeout).setState(WaitForSelectorState.ATTACHED));
                int n = all.count();
                items = new Locator[n];
                for (int i = 0; i < n; i++) items[i] = all.nth(i);
            }
            List<Object> rows = new ArrayList<>();
            for (Locator item : items) {
                if (maxItems != null && maxItems > 0 && rows.size() >= maxItems) break;
                Object r = item.evaluate(EXTRACT_ITEM_JS, specs);
                rows.add(r instanceof List<?> l ? new ArrayList<>(l) : new ArrayList<>());
            }
            return Map.of("table", Values.table(cols, rows), "count", BigDecimal.valueOf(rows.size()));
        } catch (PlaywrightException e) { throw pw(e, "Reading the list"); }
    }

    /** One item's fields: "selector", "selector@attribute", "@attribute" (the item's own) or "" (the item's text). */
    static final String EXTRACT_ITEM_JS = """
        (item, specs) => specs.map((spec) => {
          spec = String(spec || '');
          const at = spec.lastIndexOf('@');
          const css = (at >= 0 ? spec.slice(0, at) : spec).trim(), attr = at >= 0 ? spec.slice(at + 1).trim() : '';
          const el = css ? item.querySelector(css) : item;
          if (!el) return '';
          if (attr) { const v = attr === 'href' || attr === 'src' ? el[attr] : el.getAttribute(attr); return v == null ? '' : String(v); }
          return (el.value !== undefined && /^(INPUT|TEXTAREA|SELECT)$/.test(el.tagName) ? el.value : el.innerText || '').trim();
        })
        """;

    @LoopIterator(id = "pages", name = "Each page (click Next until it is gone)",
            description = "One pass per page: the first pass is the page as it is, then Next is clicked and the loop waits for the page to change, until Next is no longer there (or is disabled). Gives the page number, from 1.")
    public Iterable<Object> pages(ActionContext ctx,
            @Input(name = "next_object", type = OoaType.DICTIONARY, required = false, description = "The captured Next button or link.") Object nextObject,
            @Input(name = "next_selector", type = OoaType.STRING, required = false, example = "li.next a", description = "Selector of the Next button or link, used when no captured object is given.") String nextSel,
            @Input(name = "max_pages", type = OoaType.NUMBER, required = false, defaultValue = "100", min = 1, description = "Stop after this many pages.") int maxPages,
            @Input(name = "wait_ms", type = OoaType.NUMBER, required = false, defaultValue = "10000", min = 0, description = "How long to wait for the next page after clicking Next.") double waitMs,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"browser\"", description = "Session name.") String session) {
        if (nextObject == null && (nextSel == null || nextSel.isBlank())) throw new ActionException("BROWSER.NO_ELEMENT", "Give the captured Next button or its selector");
        return () -> new Iterator<>() {
            int page = 0;
            boolean done = false, ready = false;   // ready: the next page is showing and not yet handed out
            public boolean hasNext() {
                if (ready) return true;
                if (done || page >= maxPages) return false;
                if (page == 0) return ready = true;
                // the next page exists when Next is there and not disabled; clicking it must change the page
                Page p = s(ctx, session).page;
                Locator next = nextObject != null ? firstOrNull(WebObjects.findAll(p, WebObjects.object(nextObject))) : p.locator(nextSel).first();
                if (next == null || next.count() == 0 || !next.isVisible() || isDisabled(next)) { done = true; return false; }
                String before = pageMark(p);
                // a banner or an advert over Next: the click goes to the element itself
                try { next.click(new Locator.ClickOptions().setTimeout(Math.min(waitMs, 3000))); }
                catch (PlaywrightException covered) { next.evaluate("e => e.click()"); }
                long end = System.currentTimeMillis() + (long) waitMs;
                while (pageMark(p).equals(before)) {
                    if (System.currentTimeMillis() >= end) { done = true; return false; }
                    ctx.checkCancelled();
                    p.waitForTimeout(200);
                }
                try { p.waitForLoadState(LoadState.DOMCONTENTLOADED, new Page.WaitForLoadStateOptions().setTimeout(waitMs)); } catch (PlaywrightException ignored) { }
                return ready = true;
            }
            public Object next() { if (!hasNext()) throw new NoSuchElementException(); ready = false; page++; return BigDecimal.valueOf(page); }
        };
    }

    private static Locator firstOrNull(Locator[] found) { return found.length == 0 ? null : found[0]; }

    private static boolean isDisabled(Locator l) {
        try { return Boolean.TRUE.equals(l.evaluate("e => e.disabled === true || e.getAttribute('aria-disabled') === 'true' || /(^|\\s)disabled(\\s|$)/.test(e.className || '') || /(^|\\s)disabled(\\s|$)/.test((e.parentElement && e.parentElement.className) || '')")); }
        catch (PlaywrightException e) { return false; }
    }

    /** What changes when another page shows: the address, plus the start of the page text for pages that load in place. */
    private static String pageMark(Page p) {
        try { return p.url() + "|" + String.valueOf(p.evaluate("() => (document.body ? document.body.innerText : '').slice(0, 2000)")).hashCode(); }
        catch (PlaywrightException e) { return p.url(); }
    }

    @Condition(id = "text_on_page", name = "Page contains text", description = "True when the visible page text contains the text.")
    public boolean textOnPage(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Text to look for.") String text,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"browser\"", description = "Session name.") String session) {
        try { return s(ctx, session).page.locator("body").innerText().contains(text); } catch (PlaywrightException e) { throw pw(e, "Reading page text"); }
    }

    private static final String PICKER_JS = """
        (() => {
          if (window.__ooaPick) return; window.__ooaPick = true;
          const esc = (s) => String(s).replace(/\\\\/g, '\\\\\\\\').replace(/"/g, '\\\\"');
          const sel = (el) => {
            const tid = el.getAttribute('data-testid'); if (tid) return '[data-testid="' + esc(tid) + '"]';
            if (el.id && !/\\d{3,}|^\\d/.test(el.id)) return '#' + CSS.escape(el.id);
            const name = el.getAttribute('name'); if (name && /^(INPUT|SELECT|TEXTAREA)$/.test(el.tagName)) return el.tagName.toLowerCase() + '[name="' + esc(name) + '"]';
            const aria = el.getAttribute('aria-label'); if (aria) return '[aria-label="' + esc(aria) + '"]';
            if (/^(BUTTON|A)$/.test(el.tagName)) { const t = (el.innerText || '').trim(); if (t && t.length < 60 && !t.includes('\\n')) return 'text="' + esc(t) + '"'; }
            const parts = []; let e = el;
            while (e && e.nodeType === 1 && parts.length < 5) {
              if (e.id && !/\\d{3,}/.test(e.id)) { parts.unshift('#' + CSS.escape(e.id)); break; }
              let p = e.tagName.toLowerCase();
              const sib = e.parentElement ? Array.from(e.parentElement.children).filter(c => c.tagName === e.tagName) : [];
              if (sib.length > 1) p += ':nth-of-type(' + (sib.indexOf(e) + 1) + ')';
              parts.unshift(p); e = e.parentElement;
            }
            return parts.join(' > ');
          };
          // Self-healing fallbacks, tried in order when the selector stops matching (see SelfHealing.ORDER).
          const alt = (el) => {
            const a = { tag: el.tagName.toLowerCase() };
            const tid = el.getAttribute('data-testid'); if (tid) a.automation_id = tid;
            const nm = el.getAttribute('name'); if (nm) a.name = nm;
            if (el.id) a.id = el.id;
            const al = el.getAttribute('aria-label'); if (al) a.aria_label = al;
            const classes = (el.getAttribute('class') || '').split(' ').filter(c => /^[A-Za-z_-][A-Za-z0-9_-]*$/.test(c));
            if (classes.length) a['class'] = classes.join(' ');
            a.index = Array.from(document.querySelectorAll(a.tag + classes.map(c => '.' + c).join(''))).indexOf(el);
            const tx = (el.innerText || '').trim(); if (tx && tx.length < 80 && tx.indexOf(String.fromCharCode(10)) < 0) a.text = tx;
            const lab = el.labels && el.labels.length ? (el.labels[0].innerText || '').trim() : '';
            if (lab) a.relative = { anchor: lab.slice(0, 60), direction: 'near' };
            return a;
          };
          const box = document.createElement('div');
          box.style.cssText = 'position:fixed;pointer-events:none;z-index:2147483646;border:2px solid #a371ed;background:rgba(163,113,237,.12);border-radius:3px;display:none';
          const tip = document.createElement('div');
          tip.style.cssText = 'position:fixed;pointer-events:none;z-index:2147483647;top:10px;right:10px;background:#2f3336;color:#fff;font:13px Segoe UI,sans-serif;padding:8px 12px;border-radius:10px';
          tip.textContent = 'OOA Capture: click the element you want';
          const add = () => { if (document.body && !box.isConnected) { document.body.append(box, tip); } };
          if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', add); else add();
          document.addEventListener('mousemove', (e) => {
            if (window.__ooaPickOff) { box.style.display = 'none'; tip.style.display = 'none'; return; }
            const t = e.target; if (!t || t === box || t === tip) return;
            const r = t.getBoundingClientRect();
            Object.assign(box.style, { display: 'block', left: r.left + 'px', top: r.top + 'px', width: r.width + 'px', height: r.height + 'px' });
          }, true);
          /*OOA_LIB*/
          document.addEventListener('click', (e) => {
            if (window.__ooaPickOff) return;
            e.preventDefault(); e.stopPropagation();
            const t = e.target;
            const r = t.getBoundingClientRect();
            box.style.display = 'none'; tip.style.display = 'none';
            window.__ooaPicked({ selector: sel(t), tag: t.tagName.toLowerCase(), text: (t.innerText || t.value || '').trim().slice(0, 80),
              type: t.getAttribute('type') || '', name: t.getAttribute('name') || '', id: t.id || '', alternatives: alt(t),
              props: __props(t), css: __cssPath(t), xpath: __absXpath(t), dom_xpath: __domXpath(t),
              label_unique: (() => { const l = __labelOf(t); return !!l && Array.from(document.querySelectorAll('input:not([type=hidden]),select,textarea')).filter((x) => __labelOf(x) === l).length === 1; })(),
              rect: { x: r.left, y: r.top, width: r.width, height: r.height }, page_rect: __rect(t), page: { url: location.href, title: document.title } });
          }, true);
        })();
        """;

    @SuppressWarnings("unchecked")
    @Action(id = "pick_element", name = "Pick element (design time)", category = "Capture", timeoutSeconds = 900, capabilities = "browser",
            description = "Open a page and let a person click one element; returns a robust selector and the element's details. Used by Agent Studio's Pick button.",
            panel = "url, browser | timeout_minutes, endpoint, tab_title, tab_url, tab_id")
    @Output(name = "selector", type = OoaType.STRING, description = "Selector for the clicked element.")
    @Output(name = "element", type = OoaType.DICTIONARY, description = "tag, text, type, name and id of the element.")
    @Output(name = "alternatives", type = OoaType.DICTIONARY, description = "Self-healing fallbacks for the element; pass them as alternatives to element actions.")
    @Output(name = "object", type = OoaType.DICTIONARY, description = "The captured object: every property with use and match settings, CSS, XPath, iframes, page and a screenshot.")
    public Map<String, Object> pickElement(ActionContext ctx,
            @Input(name = "url", type = OoaType.STRING, description = "Page to open.") String url,
            @Input(name = "browser", type = OoaType.STRING, required = false, defaultValue = "\"edge\"", enumValues = {"edge", "chrome", "chromium"}, description = "Browser.") String which,
            @Input(name = "timeout_minutes", type = OoaType.NUMBER, required = false, defaultValue = "10", description = "Give up after this long.") int minutes,
            @Input(name = "endpoint", type = OoaType.STRING, required = false,
                    description = "Pick in a browser that is already open (its debugging address, e.g. http://127.0.0.1:9222) instead of opening a new one; it stays open.") String endpoint,
            @Input(name = "tab_title", type = OoaType.STRING, required = false, description = "With endpoint: the tab to pick in, by its exact title.") String tabTitle,
            @Input(name = "tab_url", type = OoaType.STRING, required = false, description = "With endpoint: the tab to pick in, by its address (used when the title is not given or not found).") String tabUrl,
            @Input(name = "tab_id", type = OoaType.NUMBER, required = false,
                    description = "Pick in this open tab of a browser connected through the ONEOFFICEAUTOMATION Browser Connector (from the open-windows list).") Long tabId) {
        if (tabId != null) return pickInConnectorTab(ctx, which, tabId, minutes);
        if (endpoint != null && !endpoint.isBlank()) return pickInOpenTab(ctx, endpoint, tabTitle, tabUrl, minutes);
        final Map<String, Object>[] picked = new Map[]{null};
        Map<String, String> env = new HashMap<>();
        if (!which.equals("chromium")) env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
        try (Playwright pw = Playwright.create(new Playwright.CreateOptions().setEnv(browserEnv(env)))) {
            BrowserType.LaunchOptions lo = new BrowserType.LaunchOptions().setHeadless(false);
            if (which.equals("edge")) lo.setChannel("msedge");
            if (which.equals("chrome")) lo.setChannel("chrome");
            com.microsoft.playwright.Browser b = pw.chromium().launch(lo);
            BrowserContext c = b.newContext();
            final Frame[] pickedFrame = new Frame[1];
            c.exposeBinding("__ooaPicked", (source, args) -> { if (args.length > 0 && args[0] instanceof Map<?, ?> m) { picked[0] = (Map<String, Object>) m; pickedFrame[0] = source.frame(); } return null; });
            c.addInitScript(PICKER_JS.replace("/*OOA_LIB*/", WebObjects.LIB_JS));
            Page p = c.newPage();
            p.navigate(url);
            long end = System.currentTimeMillis() + minutes * 60_000L;
            while (picked[0] == null && System.currentTimeMillis() < end) {
                ctx.checkCancelled();
                if (c.pages().isEmpty()) break;
                try { c.pages().get(0).waitForTimeout(200); } catch (PlaywrightException e) { break; }
            }
            if (picked[0] != null) {
                List<String> frames = new ArrayList<>();
                try {
                    for (Frame f = pickedFrame[0]; f != null && f.parentFrame() != null; f = f.parentFrame())
                        frames.add(0, String.valueOf(f.frameElement().evaluate(FRAME_SELECTOR_JS)));
                } catch (PlaywrightException ignored) { /* the frame went away; the object still works on the page */ }
                picked[0].put("frames", frames);
                if (frames.isEmpty() && !c.pages().isEmpty()) { String shot = elementShot(c.pages().get(0), picked[0].get("rect")); if (shot != null) picked[0].put("screenshot", shot); }
            }
            try { b.close(); } catch (PlaywrightException ignored) { }
        } catch (PlaywrightException e) { throw pw(e, "Picking an element"); }
        if (picked[0] == null) throw new ActionException("BROWSER.NOTHING_PICKED", "No element was picked", "Click an element in the browser window before closing it.");
        Object alternatives = picked[0].get("alternatives") instanceof Map<?, ?> m ? m : Map.of();
        Map<String, Object> object = capturedObject(picked[0]);
        Map<String, Object> element = new LinkedHashMap<>(picked[0]);
        element.remove("screenshot"); element.remove("props");
        return Map.of("selector", String.valueOf(picked[0].get("selector")), "element", element, "alternatives", alternatives, "object", object);
    }

    /** Pick element in a tab of the person's own browser, through the connector: the page shows the picker, the extension sends the element back. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> pickInConnectorTab(ActionContext ctx, String which, long tabId, int minutes) {
        String token = "pick-" + UUID.randomUUID();
        Map<String, Object> picked = null;
        try (ExtensionBridge.Subscription sub = ExtensionBridge.subscribe(m -> token.equals(m.get("token")))) {
            ExtensionBridge.call(which, "pick", Map.of("tab_id", tabId, "token", token), 15000);
            long end = System.currentTimeMillis() + minutes * 60_000L;
            while (System.currentTimeMillis() < end && picked == null) {
                ctx.checkCancelled();
                Map<String, Object> ev;
                try { ev = sub.next(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                if (ev == null) continue;
                if ("pick_cancelled".equals(ev.get("event"))) throw new ActionException("BROWSER.NOTHING_PICKED", "Capture was cancelled in the browser (Esc)");
                if ("picked".equals(ev.get("event"))) picked = new LinkedHashMap<>(ev);
            }
        } finally {
            if (picked == null) try { ExtensionBridge.call(which, "pick_cancel", Map.of("tab_id", tabId), 5000); } catch (ActionException ignored) { }
        }
        if (picked == null) throw new ActionException("BROWSER.NOTHING_PICKED", "No element was picked", "Click an element in the browser window.");
        // the tab's title for the page (a frame does not know it)
        try {
            Map<String, Object> info = (Map<String, Object>) ExtensionBridge.call(which, "info", Map.of("tab_id", tabId), 5000);
            Map<String, Object> page = picked.get("page") instanceof Map<?, ?> pm ? new LinkedHashMap<>((Map<String, Object>) pm) : new LinkedHashMap<>();
            page.put("title", String.valueOf(info.get("title"))); page.put("url", String.valueOf(info.get("url")));
            picked.put("page", page);
        } catch (ActionException ignored) { }
        Map<String, Object> object = capturedObject(picked);
        Map<String, Object> element = new LinkedHashMap<>(picked);
        element.remove("screenshot"); element.remove("props"); element.remove("token"); element.remove("event");
        return Map.of("selector", String.valueOf(picked.get("selector")), "element", element, "alternatives", Map.of(), "object", object);
    }

    /** Pick element in a tab of a browser that stays open: the picker is added to that tab and switched off afterwards. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> pickInOpenTab(ActionContext ctx, String endpoint, String tabTitle, String tabUrl, int minutes) {
        String ep = BrowserWindows.endpoint(endpoint);
        String id = Long.toString(System.nanoTime(), 36);
        String script = PICKER_JS.replace("/*OOA_LIB*/", WebObjects.LIB_JS).replace("__ooaPick", "__ooaPick" + id);
        final Map<String, Object>[] picked = new Map[]{null};
        final Frame[] pickedFrame = new Frame[1];
        Map<String, String> env = Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
        try (Playwright pw = Playwright.create(new Playwright.CreateOptions().setEnv(browserEnv(env)))) {
            com.microsoft.playwright.Browser b = pw.chromium().connectOverCDP(ep);
            if (b.contexts().isEmpty()) throw new ActionException("BROWSER.WINDOW_NOT_FOUND", "The browser at " + ep + " has no open window");
            BrowserContext c = b.contexts().get(0);
            Page page = null;
            boolean hasTitle = tabTitle != null && !tabTitle.isBlank(), hasUrl = tabUrl != null && !tabUrl.isBlank();
            // several tabs can share a title: the one with the same title and address first, then the address, then the title
            for (Page p : c.pages()) if (hasTitle && hasUrl && tabTitle.equals(p.title()) && p.url().equals(tabUrl)) { page = p; break; }
            if (page == null) for (Page p : c.pages()) if (hasUrl && p.url().startsWith(tabUrl)) { page = p; break; }
            if (page == null) for (Page p : c.pages()) if (hasTitle && tabTitle.equals(p.title())) { page = p; break; }
            if (page == null) throw new ActionException("BROWSER.WINDOW_NOT_FOUND", "No open tab is titled '" + tabTitle + "'" + (tabUrl == null ? "" : " or at " + tabUrl),
                    "Choose the window again from the list; it may have been closed or navigated.");
            c.exposeBinding("__ooaPick" + id + "ed", (source, args) -> { if (args.length > 0 && args[0] instanceof Map<?, ?> m) { picked[0] = (Map<String, Object>) m; pickedFrame[0] = source.frame(); } return null; });
            for (Frame f : page.frames()) { try { f.evaluate("() => { " + script + " }"); } catch (PlaywrightException e) { if (f == page.mainFrame()) throw e; } }
            page.bringToFront();
            long end = System.currentTimeMillis() + minutes * 60_000L;
            while (picked[0] == null && System.currentTimeMillis() < end) {
                ctx.checkCancelled();
                if (page.isClosed()) break;
                try { page.waitForTimeout(200); } catch (PlaywrightException e) { break; }
            }
            for (Frame f : page.isClosed() ? List.<Frame>of() : page.frames()) { try { f.evaluate("() => { window.__ooaPick" + id + "Off = true; }"); } catch (PlaywrightException ignored) { } }
            if (picked[0] != null) {
                List<String> frames = new ArrayList<>();
                try {
                    for (Frame f = pickedFrame[0]; f != null && f.parentFrame() != null; f = f.parentFrame())
                        frames.add(0, String.valueOf(f.frameElement().evaluate(FRAME_SELECTOR_JS)));
                } catch (PlaywrightException ignored) { }
                picked[0].put("frames", frames);
                if (frames.isEmpty()) { String shot = elementShot(page, picked[0].get("rect")); if (shot != null) picked[0].put("screenshot", shot); }
            }
            // Playwright closing here only disconnects: the browser and its windows stay open.
        } catch (PlaywrightException e) { throw pw(e, "Picking an element in the open browser"); }
        if (picked[0] == null) throw new ActionException("BROWSER.NOTHING_PICKED", "No element was picked", "Click an element in the browser window.");
        Object alternatives = picked[0].get("alternatives") instanceof Map<?, ?> m ? m : Map.of();
        Map<String, Object> object = capturedObject(picked[0]);
        Map<String, Object> element = new LinkedHashMap<>(picked[0]);
        element.remove("screenshot"); element.remove("props");
        return Map.of("selector", String.valueOf(picked[0].get("selector")), "element", element, "alternatives", alternatives, "object", object);
    }

    /** A PNG (base64) of the element at this rectangle of the page's view, with a small margin; null when there is none to take. */
    static String elementShot(Page page, Object rect) {
        if (page == null || !(rect instanceof Map<?, ?> r) || !(r.get("x") instanceof Number x) || !(r.get("y") instanceof Number y)
                || !(r.get("width") instanceof Number w) || !(r.get("height") instanceof Number h) || w.doubleValue() < 2 || h.doubleValue() < 2) return null;
        try {
            byte[] png = page.screenshot(new Page.ScreenshotOptions().setTimeout(3000).setClip(Math.max(0, x.doubleValue() - 4), Math.max(0, y.doubleValue() - 4),
                    Math.min(w.doubleValue() + 8, 1200), Math.min(h.doubleValue() + 8, 600)));
            return png.length < 300_000 ? Base64.getEncoder().encodeToString(png) : null;
        } catch (PlaywrightException e) { return null; }   // a picture is a nice-to-have (the page may be moving on)
    }

    /** A recorded event with a picture of its element, as Capture keeps one (in the top frame: a frame's rectangle is not the page's). */
    static Map<String, Object> withShot(com.microsoft.playwright.options.BindingCallback.Source source, Map<String, Object> event) {
        Map<String, Object> e = new LinkedHashMap<>(event);
        Frame f = source.frame();
        if (f != null && f.parentFrame() == null) { String shot = elementShot(source.page(), e.get("rect")); if (shot != null) e.put("screenshot", shot); }
        e.remove("rect");
        return e;
    }

    private static final String FRAME_SELECTOR_JS = """
        (el) => {
          if (el.id && !/\\d{3,}/.test(el.id)) return 'iframe#' + CSS.escape(el.id);
          const nm = el.getAttribute('name'); if (nm) return 'iframe[name="' + nm.replace(/"/g, '\\"') + '"]';
          const src = el.getAttribute('src'); if (src && src.length < 200) return 'iframe[src="' + src.replace(/"/g, '\\"') + '"]';
          const all = Array.from(el.ownerDocument.querySelectorAll('iframe,frame'));
          return (el.tagName.toLowerCase()) + ':nth-of-type(' + (Array.from(el.parentElement.children).filter(c => c.tagName === el.tagName).indexOf(el) + 1) + ')';
        }
        """;

    /** Turns the picker's raw properties into the captured object, choosing sensible properties to match by default. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> capturedObject(Map<String, Object> picked) {
        Map<String, Object> p = picked.get("props") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        String tag = String.valueOf(p.getOrDefault("tag", "")), id = (String) p.get("id"), name = (String) p.get("name");
        boolean stableId = id != null && !generatedId(id);
        boolean field = Set.of("input", "select", "textarea").contains(tag);
        // attributes written for testing and automation name an element on purpose and do not change with the data
        String testAttr = null;
        for (String a : List.of("data-testid", "data-test", "data-test-id", "data-qa", "data-cy", "data-automation-id"))
            if (p.get(a) != null && !String.valueOf(p.get(a)).isBlank()) { testAttr = a; break; }
        // text or a label with numbers in it is data (a total, a count): it changes, so the element is not found by it
        java.util.function.Predicate<String> steady = (k) -> p.get(k) != null && !String.valueOf(p.get(k)).isBlank() && !String.valueOf(p.get(k)).matches(".*\\d.*");
        Set<String> use = new LinkedHashSet<>(List.of("tag"));
        // a field is found by its label when no other field on the page has the same one: that is how a person tells fields
        // apart, and it survives ids and names that a framework makes up anew on every visit
        boolean byLabel = field && p.get("label") != null && Boolean.TRUE.equals(picked.get("label_unique"));
        if (testAttr != null) use.add(testAttr);
        else if (byLabel) use.add("label");
        else if (stableId) use.add("id");
        else if (field && p.get("label") != null && (name == null || generatedId(name))) use.add("label");
        else if (name != null && !generatedId(name)) use.add("name");
        else if (steady.test("aria-label")) use.add("aria-label");
        else if (field && p.get("placeholder") != null) use.add("placeholder");
        else if (field && p.get("label") != null) use.add("label");
        else if (steady.test("text") && String.valueOf(p.get("text")).length() <= 80) use.add("text");
        else if (steady.test("title")) use.add("title");
        if (field && p.get("type") != null && !use.contains("id")) use.add("type");
        // nothing names the element: its own class (contains, as classes come and go with its state), else its place in the page
        String cls = null;
        if (use.size() == 1 && p.get("class") instanceof String c)
            for (String t : c.trim().split("\\s+")) if (t.length() > 3 && !generatedId(t) && !t.matches("(?i)(ng|is|has)-.*|active|selected|open|show|hidden|disabled|focus.*")) { cls = t; break; }
        if (use.size() == 1 && cls != null) use.add("class");
        else if (use.size() == 1 && (picked.get("dom_xpath") instanceof String dx && !dx.isBlank() || p.get("dom_xpath") != null)) use.add("dom_xpath");
        List<String> order = new ArrayList<>(List.of("tag", "control_type", "id", "name", "data-testid", "data-test", "type", "aria-label", "label", "placeholder", "text", "title", "role", "class", "value", "href", "src", "alt", "for"));
        for (String k : p.keySet()) if (!order.contains(k) && !TECHNICAL.contains(k)) order.add(k);
        order.addAll(TECHNICAL);
        List<Map<String, Object>> props = new ArrayList<>();
        for (String k : order) {
            if (!p.containsKey(k)) continue;
            Map<String, Object> e = new LinkedHashMap<>();
            boolean byClass = k.equals("class") && cls != null && use.contains("class");
            e.put("name", k); e.put("value", byClass ? cls : p.get(k)); e.put("match", byClass ? "contains" : "equals"); e.put("use", use.contains(k));
            props.add(e);
        }
        for (String k : List.of("dom_xpath", "css", "relative_xpath", "xpath"))
            if (!p.containsKey(k) && picked.get(k) instanceof String v && !v.isBlank()) props.add(new LinkedHashMap<>(Map.of("name", k, "value", v, "match", "equals", "use", use.contains(k))));
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("technology", "web");
        Map<String, Object> page = picked.get("page") instanceof Map<?, ?> pm ? new LinkedHashMap<>((Map<String, Object>) pm) : new LinkedHashMap<>();
        page.put("url_match", "starts_with");
        o.put("page", page);
        o.put("frames", picked.getOrDefault("frames", List.of()));
        o.put("search_by", "properties");
        o.put("index", 0);
        o.put("ignore_case", false);
        o.put("properties", props);
        if (picked.get("page_rect") instanceof Map<?, ?> r) o.put("rect", r);
        if (picked.get("screenshot") != null) o.put("screenshot", picked.get("screenshot"));
        return o;
    }

    private static final java.util.regex.Pattern SHORT_FORMS = java.util.regex.Pattern.compile(
            "(?i)btn|txt|lbl|msg|img|pwd|nbr|num|qty|amt|cfg|ctl|ddl|chk|rb|cb|tb|dt|db|nav|src|dst|ctx|tmp|hdr|ftr|tbl|col|row|frm|pnl|grd|dlg|html|xml|css|url|pdf|sql|csv|xls|png|jpg|svg|http|www");

    /**
     * Is this id made up by a framework or at random (it changes between visits)? Many digits, a leading digit, or a short
     * mix of letters that does not read as words (UeWWS, jQjQk, g7Aeq), unlike firstName, user_email or btnSave.
     * The same rule as __generated in ooa-page.js.
     */
    static boolean generatedId(String v) {
        if (v == null) return false;
        if (v.matches(".*\\d{3,}.*") || v.matches("^\\d.*")) return true;
        if (!v.matches("[A-Za-z0-9]{4,12}")) return false;
        if (v.matches(".*[A-Za-z]\\d+[A-Za-z].*")) return true;
        if (v.matches("[A-Z]+") || v.matches("[a-z]+")) return false;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[A-Z]?[a-z]+|[A-Z]+(?![a-z])|\\d+").matcher(v);
        List<String> words = new ArrayList<>();
        while (m.find()) words.add(m.group());
        if (!String.join("", words).equals(v)) return true;
        for (int i = 0; i < words.size(); i++) {
            String w = words.get(i);
            if (w.matches("[A-Za-z]{2,}") && !w.matches("[A-Z]{2}") && !w.matches(".*[aeiouyAEIOUY].*") && !SHORT_FORMS.matcher(w).matches()) return true;
            if (w.length() == 1 && i < words.size() - 1 && Character.isLetter(w.charAt(0))) return true;
        }
        return false;
    }

    /** Properties computed from the element's place in the page, listed after its attributes and text. */
    static final List<String> TECHNICAL = List.of("dom_xpath", "css", "xpath", "path", "parent", "tag_index", "source_index", "child_count",
            "left", "top", "width", "height", "visible", "enabled", "checked", "selected", "in_frame", "frame_url", "inner_html", "outer_html");

    @Action(id = "test_object", name = "Test captured object (design time)", category = "Capture", timeoutSeconds = 300, capabilities = "browser",
            description = "Open a page, find every element that matches a captured object, outline them for a few seconds and report how many match. Used by Agent Studio's Test button.",
            panel = "url, object, browser | wait_seconds, show_seconds")
    @Output(name = "count", type = OoaType.NUMBER, description = "How many elements match.")
    @Output(name = "first", type = OoaType.DICTIONARY, description = "All properties of the element at the object's index, when it exists.")
    public Map<String, Object> testObject(ActionContext ctx,
            @Input(name = "url", type = OoaType.STRING, description = "Page to open.") String url,
            @Input(name = "object", type = OoaType.DICTIONARY, description = "The captured object to test.") Object objectArg,
            @Input(name = "browser", type = OoaType.STRING, required = false, defaultValue = "\"edge\"", enumValues = {"edge", "chrome", "chromium"}, description = "Browser.") String which,
            @Input(name = "wait_seconds", type = OoaType.NUMBER, required = false, defaultValue = "10", description = "How long to wait for the page and the element.") int waitSeconds,
            @Input(name = "show_seconds", type = OoaType.NUMBER, required = false, defaultValue = "4", description = "How long to keep the matches outlined.") int showSeconds) {
        Map<String, Object> object = WebObjects.object(objectArg);
        Map<String, String> env = new HashMap<>();
        if (!which.equals("chromium")) env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
        try (Playwright pw = Playwright.create(new Playwright.CreateOptions().setEnv(browserEnv(env)))) {
            BrowserType.LaunchOptions lo = new BrowserType.LaunchOptions().setHeadless(false);
            if (which.equals("edge")) lo.setChannel("msedge");
            if (which.equals("chrome")) lo.setChannel("chrome");
            com.microsoft.playwright.Browser b = pw.chromium().launch(lo);
            Page page = b.newContext().newPage();
            page.navigate(url);
            long end = System.currentTimeMillis() + waitSeconds * 1000L;
            Locator[] found = new Locator[0];
            while (System.currentTimeMillis() < end) {
                ctx.checkCancelled();
                try { found = WebObjects.findAll(page, object); } catch (PlaywrightException ignored) { }
                if (found.length > 0) break;
                page.waitForTimeout(300);
            }
            int idx = object.get("index") instanceof Number n ? n.intValue() : 0;
            Object first = null;
            for (int i = 0; i < found.length; i++) {
                String color = i == idx ? "#16a34a" : "#a371ed";
                try { found[i].evaluate("(e, c) => { e.style.outline = '3px solid ' + c; e.style.outlineOffset = '2px'; }", color); } catch (PlaywrightException ignored) { }
            }
            if (found.length > idx) { try { first = Values.toJsonable(found[idx].evaluate(WebObjects.ALL_PROPS_JS)); found[idx].scrollIntoViewIfNeeded(); } catch (PlaywrightException ignored) { } }
            page.waitForTimeout(Math.max(0, showSeconds) * 1000.0);
            try { b.close(); } catch (PlaywrightException ignored) { }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("count", BigDecimal.valueOf(found.length));
            out.put("first", first);
            return out;
        } catch (PlaywrightException e) { throw pw(e, "Testing the captured object"); }
    }
}
