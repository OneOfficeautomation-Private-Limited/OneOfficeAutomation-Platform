package com.oneofficeautomation.packages.browser;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitForSelectorState;
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
        BrowserSession(Playwright pw, com.microsoft.playwright.Browser b, BrowserContext c, Page p) { this.pw = pw; browser = b; context = c; page = p; }
        @Override public void close() { try { context.close(); } finally { try { browser.close(); } finally { pw.close(); } } }
    }

    private static BrowserSession s(ActionContext ctx, String name) { return ctx.session(name, BrowserSession.class); }

    private static ActionException pw(PlaywrightException e, String what) {
        String m = String.valueOf(e.getMessage()).split("\n")[0];
        if (e instanceof TimeoutError || m.contains("Timeout")) return new ActionException("BROWSER.TIMEOUT", what + " timed out: " + m,
                "Check the selector matches an element on the page, or raise timeout_ms.", true);
        if (m.contains("Executable doesn't exist") || m.contains("browserType.launch"))
            return new ActionException("BROWSER.NOT_INSTALLED", "The browser could not be started: " + m, "Use browser edge or chrome (installed), or allow Playwright to download Chromium.");
        return new ActionException("BROWSER.ERROR", what + " failed: " + m, null);
    }

    private static final String D_SEL = "Element selector: CSS (#id, .class), text=\"Sign in\", role=button[name=\"Save\"] or xpath=//input[@name='q'].";
    private static final String D_TIMEOUT = "How long to wait for the element, in milliseconds.";

    @Action(id = "open", name = "Open browser", category = "Browser", timeoutSeconds = 180, capabilities = "browser",
            description = "Start a browser, optionally open a URL, as a named session.")
    public Map<String, Object> open(ActionContext ctx,
            @Input(name = "url", type = OoaType.STRING, required = false, description = "Page to open.") String url,
            @Input(name = "browser", type = OoaType.STRING, required = false, defaultValue = "\"edge\"", enumValues = {"edge", "chrome", "chromium", "firefox"},
                    description = "edge and chrome use the installed browser; chromium and firefox are downloaded by Playwright.") String which,
            @Input(name = "headless", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Run without a visible window (for unattended agents).") boolean headless,
            @Input(name = "download_folder", type = OoaType.FILE, required = false, description = "Where downloads are saved.") String downloads,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"browser\"", description = "Session name.") String session) {
        boolean installed = which.equals("edge") || which.equals("chrome");
        Map<String, String> env = new HashMap<>();
        if (installed) env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
        Playwright pw = null;
        try {
            pw = Playwright.create(new Playwright.CreateOptions().setEnv(env));
            BrowserType type = which.equals("firefox") ? pw.firefox() : pw.chromium();
            BrowserType.LaunchOptions lo = new BrowserType.LaunchOptions().setHeadless(headless);
            if (which.equals("edge")) lo.setChannel("msedge");
            if (which.equals("chrome")) lo.setChannel("chrome");
            if (downloads != null) lo.setDownloadsPath(ctx.resolvePath(downloads));
            com.microsoft.playwright.Browser b = type.launch(lo);
            BrowserContext c = b.newContext(new com.microsoft.playwright.Browser.NewContextOptions().setAcceptDownloads(true));
            Page p = c.newPage();
            if (url != null && !url.isBlank()) p.navigate(url);
            ctx.sessions().put(session, new BrowserSession(pw, b, c, p));
        } catch (PlaywrightException e) {
            if (pw != null) pw.close();
            throw pw(e, "Opening the browser");
        }
        return Map.of();
    }

    @Action(id = "navigate", name = "Go to URL", category = "Browser", timeoutSeconds = 120, capabilities = "browser", description = "Open a URL in the current tab and wait for it to load.")
    public Map<String, Object> navigate(ActionContext ctx,
            @Input(name = "url", type = OoaType.STRING, description = "Address to open.") String url,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"browser\"", description = "Session name.") String session) {
        try { s(ctx, session).page.navigate(url); } catch (PlaywrightException e) { throw pw(e, "Opening " + url); }
        return Map.of();
    }

    @Action(id = "click", name = "Click element", category = "Elements", timeoutSeconds = 120, capabilities = "browser", description = "Click an element (waits until it is visible and enabled).")
    public Map<String, Object> click(ActionContext ctx,
            @Input(name = "selector", type = OoaType.STRING, description = D_SEL) String sel,
            @Input(name = "double_click", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Double-click.") boolean dbl,
            @Input(name = "timeout_ms", type = OoaType.NUMBER, required = false, defaultValue = "30000", description = D_TIMEOUT) double timeout,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"browser\"", description = "Session name.") String session) {
        try {
            Locator l = s(ctx, session).page.locator(sel).first();
            if (dbl) l.dblclick(new Locator.DblclickOptions().setTimeout(timeout)); else l.click(new Locator.ClickOptions().setTimeout(timeout));
        } catch (PlaywrightException e) { throw pw(e, "Clicking " + sel); }
        return Map.of();
    }

    @Action(id = "type_text", name = "Set text", category = "Elements", timeoutSeconds = 120, capabilities = "browser",
            description = "Fill a text field (replacing its content); Credential values are typed without ever being logged.")
    public Map<String, Object> typeText(ActionContext ctx,
            @Input(name = "selector", type = OoaType.STRING, description = D_SEL) String sel,
            @Input(name = "text", description = "Text, or a Credential variable for passwords.") Object text,
            @Input(name = "press_enter", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Press Enter afterwards.") boolean enter,
            @Input(name = "timeout_ms", type = OoaType.NUMBER, required = false, defaultValue = "30000", description = D_TIMEOUT) double timeout,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"browser\"", description = "Session name.") String session) {
        String value = text instanceof SecureString ss ? ss.reveal() : Values.text(text);
        try {
            Locator l = s(ctx, session).page.locator(sel).first();
            l.fill(value, new Locator.FillOptions().setTimeout(timeout));
            if (enter) l.press("Enter");
        } catch (PlaywrightException e) { throw pw(e, "Typing into " + sel); }
        return Map.of();
    }

    @Action(id = "select_option", name = "Select item", category = "Elements", timeoutSeconds = 120, capabilities = "browser", description = "Choose an option in a drop-down list by its value or visible text.")
    public Map<String, Object> select(ActionContext ctx,
            @Input(name = "selector", type = OoaType.STRING, description = D_SEL) String sel,
            @Input(name = "option", type = OoaType.STRING, description = "Option value or visible text.") String option,
            @Input(name = "timeout_ms", type = OoaType.NUMBER, required = false, defaultValue = "30000", description = D_TIMEOUT) double timeout,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"browser\"", description = "Session name.") String session) {
        try {
            Locator l = s(ctx, session).page.locator(sel).first();
            List<String> chosen = l.selectOption(option, new Locator.SelectOptionOptions().setTimeout(timeout));
            if (chosen.isEmpty()) l.selectOption(new com.microsoft.playwright.options.SelectOption().setLabel(option), new Locator.SelectOptionOptions().setTimeout(timeout));
        } catch (PlaywrightException e) { throw pw(e, "Selecting '" + option + "' in " + sel); }
        return Map.of();
    }

    @Action(id = "get_text", name = "Get text", category = "Elements", timeoutSeconds = 120, capabilities = "browser", description = "Read the visible text of an element.")
    @Output(name = "text", type = OoaType.STRING, description = "Element text.")
    public Map<String, Object> getText(ActionContext ctx,
            @Input(name = "selector", type = OoaType.STRING, description = D_SEL) String sel,
            @Input(name = "timeout_ms", type = OoaType.NUMBER, required = false, defaultValue = "30000", description = D_TIMEOUT) double timeout,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"browser\"", description = "Session name.") String session) {
        try { return Map.of("text", s(ctx, session).page.locator(sel).first().innerText(new Locator.InnerTextOptions().setTimeout(timeout)).strip()); }
        catch (PlaywrightException e) { throw pw(e, "Reading " + sel); }
    }

    @Action(id = "get_attribute", name = "Get attribute", category = "Elements", timeoutSeconds = 120, capabilities = "browser", description = "Read an attribute of an element, e.g. href or value.")
    @Output(name = "value", type = OoaType.STRING, description = "Attribute value, empty when missing.")
    public Map<String, Object> getAttribute(ActionContext ctx,
            @Input(name = "selector", type = OoaType.STRING, description = D_SEL) String sel,
            @Input(name = "attribute", type = OoaType.STRING, example = "href", description = "Attribute name; use value for input fields.") String attr,
            @Input(name = "timeout_ms", type = OoaType.NUMBER, required = false, defaultValue = "30000", description = D_TIMEOUT) double timeout,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"browser\"", description = "Session name.") String session) {
        try {
            Locator l = s(ctx, session).page.locator(sel).first();
            String v = attr.equals("value") ? l.inputValue(new Locator.InputValueOptions().setTimeout(timeout)) : l.getAttribute(attr, new Locator.GetAttributeOptions().setTimeout(timeout));
            return Map.of("value", v == null ? "" : v);
        } catch (PlaywrightException e) { throw pw(e, "Reading " + attr + " of " + sel); }
    }

    @Action(id = "wait_for", name = "Wait for element", category = "Elements", timeoutSeconds = 600, capabilities = "browser", description = "Wait until an element appears, becomes visible, hidden or is removed.")
    public Map<String, Object> waitFor(ActionContext ctx,
            @Input(name = "selector", type = OoaType.STRING, description = D_SEL) String sel,
            @Input(name = "state", type = OoaType.STRING, required = false, defaultValue = "\"visible\"", enumValues = {"visible", "attached", "hidden", "detached"}, description = "State to wait for.") String state,
            @Input(name = "timeout_ms", type = OoaType.NUMBER, required = false, defaultValue = "30000", description = D_TIMEOUT) double timeout,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"browser\"", description = "Session name.") String session) {
        try {
            s(ctx, session).page.locator(sel).first().waitFor(new Locator.WaitForOptions().setTimeout(timeout).setState(WaitForSelectorState.valueOf(state.toUpperCase(Locale.ROOT))));
        } catch (PlaywrightException e) { throw pw(e, "Waiting for " + sel); }
        return Map.of();
    }

    @Condition(id = "element_exists", name = "Element exists", description = "True when at least one element matches the selector right now.")
    public boolean exists(ActionContext ctx,
            @Input(name = "selector", type = OoaType.STRING, description = D_SEL) String sel,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"browser\"", description = "Session name.") String session) {
        try { return s(ctx, session).page.locator(sel).count() > 0; } catch (PlaywrightException e) { throw pw(e, "Checking " + sel); }
    }

    @Action(id = "extract_table", name = "Extract table", category = "Data", timeoutSeconds = 120, capabilities = "browser", description = "Read an HTML table into a Data Table (first row as column names).")
    @Output(name = "table", type = OoaType.TABLE, description = "Table content.")
    public Map<String, Object> extractTable(ActionContext ctx,
            @Input(name = "selector", type = OoaType.STRING, required = false, defaultValue = "\"table\"", description = "Selector of the table element.") String sel,
            @Input(name = "timeout_ms", type = OoaType.NUMBER, required = false, defaultValue = "30000", description = D_TIMEOUT) double timeout,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"browser\"", description = "Session name.") String session) {
        try {
            Locator l = s(ctx, session).page.locator(sel).first();
            l.waitFor(new Locator.WaitForOptions().setTimeout(timeout));
            Object raw = l.evaluate("t => Array.from(t.rows).map(r => Array.from(r.cells).map(c => c.innerText.trim()))");
            List<List<Object>> grid = new ArrayList<>();
            if (raw instanceof List<?> rows) for (Object r : rows) grid.add(new ArrayList<>((List<?>) r));
            List<String> cols = new ArrayList<>();
            if (!grid.isEmpty()) for (Object h : grid.remove(0)) cols.add(String.valueOf(h));
            List<Object> rows = new ArrayList<>();
            for (List<Object> g : grid) { while (g.size() < cols.size()) g.add(""); rows.add(g); }
            return Map.of("table", Values.table(cols, rows));
        } catch (PlaywrightException e) { throw pw(e, "Reading table " + sel); }
    }

    @Action(id = "run_javascript", name = "Run JavaScript", category = "Page", timeoutSeconds = 120, capabilities = "browser",
            description = "Run JavaScript in the page and return its result (use a return-value expression, e.g. document.title).")
    @Output(name = "result", description = "Value returned by the script.")
    public Map<String, Object> runJs(ActionContext ctx,
            @Input(name = "script", type = OoaType.STRING, example = "document.querySelectorAll('.item').length", description = "JavaScript expression or function.") String script,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"browser\"", description = "Session name.") String session) {
        try { return Out(Values.toJsonable(s(ctx, session).page.evaluate(script))); }
        catch (PlaywrightException e) { throw pw(e, "Running JavaScript"); }
    }

    private static Map<String, Object> Out(Object v) { Map<String, Object> m = new LinkedHashMap<>(); m.put("result", v); return m; }

    @Action(id = "download", name = "Download file", category = "Page", timeoutSeconds = 600, capabilities = {"browser", "filesystem"},
            description = "Click a link or button that starts a download and save the file into a folder.")
    @Output(name = "path", type = OoaType.FILE, description = "Saved file.")
    public Map<String, Object> download(ActionContext ctx,
            @Input(name = "selector", type = OoaType.STRING, description = "Element that starts the download.") String sel,
            @Input(name = "folder", type = OoaType.FILE, description = "Folder to save into.") String folder,
            @Input(name = "timeout_ms", type = OoaType.NUMBER, required = false, defaultValue = "120000", description = "How long to wait for the download.") double timeout,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"browser\"", description = "Session name.") String session) {
        try {
            Page p = s(ctx, session).page;
            Download d = p.waitForDownload(new Page.WaitForDownloadOptions().setTimeout(timeout), () -> p.locator(sel).first().click());
            String name = d.suggestedFilename().replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_");
            Path target = ctx.resolvePath(folder).resolve(name.isBlank() ? "download" : name);
            Files.createDirectories(target.getParent());
            d.saveAs(target);
            return Map.of("path", target.toString());
        } catch (PlaywrightException e) { throw pw(e, "Downloading via " + sel);
        } catch (java.io.IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), null, true); }
    }

    @Action(id = "screenshot", name = "Take screenshot", category = "Page", timeoutSeconds = 120, capabilities = {"browser", "filesystem"}, description = "Save a PNG screenshot of the page or one element.")
    @Output(name = "path", type = OoaType.FILE, description = "Saved image.")
    public Map<String, Object> screenshot(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "PNG file to create.") String path,
            @Input(name = "full_page", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Whole scrollable page.") boolean full,
            @Input(name = "selector", type = OoaType.STRING, required = false, description = "Only this element.") String sel,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"browser\"", description = "Session name.") String session) {
        Path p = ctx.resolvePath(path);
        try {
            if (p.getParent() != null) Files.createDirectories(p.getParent());
            Page page = s(ctx, session).page;
            if (sel != null && !sel.isBlank()) page.locator(sel).first().screenshot(new Locator.ScreenshotOptions().setPath(p));
            else page.screenshot(new Page.ScreenshotOptions().setPath(p).setFullPage(full));
        } catch (PlaywrightException e) { throw pw(e, "Taking a screenshot");
        } catch (java.io.IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), null, true); }
        return Map.of("path", p.toString());
    }

    @Action(id = "page_info", name = "Get URL and title", category = "Page", idempotent = true, capabilities = "browser", description = "Return the current page's URL and title.")
    @Output(name = "url", type = OoaType.STRING, description = "Current URL.")
    @Output(name = "title", type = OoaType.STRING, description = "Page title.")
    public Map<String, Object> pageInfo(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"browser\"", description = "Session name.") String session) {
        Page p = s(ctx, session).page;
        return Map.of("url", p.url(), "title", p.title());
    }

    @Action(id = "history", name = "Back, forward or refresh", category = "Browser", timeoutSeconds = 120, capabilities = "browser", description = "Go back, go forward or reload the page.")
    public Map<String, Object> history(ActionContext ctx,
            @Input(name = "action", type = OoaType.STRING, enumValues = {"back", "forward", "refresh"}, description = "What to do.") String action,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"browser\"", description = "Session name.") String session) {
        Page p = s(ctx, session).page;
        try {
            switch (action) { case "back" -> p.goBack(); case "forward" -> p.goForward(); default -> p.reload(); }
            p.waitForLoadState(LoadState.LOAD);
        } catch (PlaywrightException e) { throw pw(e, action); }
        return Map.of();
    }

    @Action(id = "switch_tab", name = "Switch tab", category = "Browser", capabilities = "browser", description = "Make another tab current, by number (0 = first) or by title text; new tabs opened by links are included.")
    @Output(name = "title", type = OoaType.STRING, description = "Title of the tab now current.")
    public Map<String, Object> switchTab(ActionContext ctx,
            @Input(name = "index", type = OoaType.NUMBER, required = false, description = "Tab number, starting at 0; -1 for the newest.") Integer index,
            @Input(name = "title_contains", type = OoaType.STRING, required = false, description = "Pick the first tab whose title contains this text.") String title,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"browser\"", description = "Session name.") String session) {
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

    @Action(id = "close", name = "Close browser", category = "Browser", description = "Close the browser session.")
    public Map<String, Object> close(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"browser\"", description = "Session name.") String session) {
        Object o = ctx.sessions().remove(session);
        if (o instanceof BrowserSession b) try { b.close(); } catch (RuntimeException ignored) { }
        return Map.of();
    }

    // ---------- Web recorder ----------
    private static final String RECORDER_JS = """
        (() => {
          if (window.__ooaRec) return; window.__ooaRec = true;
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
          document.addEventListener('click', (e) => {
            if (e.target.closest && e.target.closest('#__ooa_bar')) return;
            const t = (e.target.closest && e.target.closest('button,a,input,select,textarea,[role=button],[onclick],label')) || e.target;
            if (textInput(t) || t.tagName === 'SELECT') return;
            window.__ooaRecord({ kind: 'click', selector: sel(t), label: (t.innerText || t.value || '').trim().slice(0, 40) });
          }, true);
          document.addEventListener('change', (e) => {
            const t = e.target;
            if (t.tagName === 'SELECT') window.__ooaRecord({ kind: 'select', selector: sel(t), value: t.value });
            else if (textInput(t)) window.__ooaRecord({ kind: 'type', selector: sel(t), value: t.type === 'password' ? null : t.value, password: t.type === 'password' });
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
          if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', bar); else bar();
        })();
        """;

    @SuppressWarnings("unchecked")
    @Action(id = "record", name = "Record web actions", category = "Recorder", timeoutSeconds = 3600, capabilities = "browser",
            description = "Open a browser, record your clicks, typing and selections as steps, and return them when you press Stop recording or close the browser. Passwords are never recorded.")
    @Output(name = "steps", type = OoaType.LIST, description = "Recorded steps, ready to insert into an agent.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of steps.")
    public Map<String, Object> record(ActionContext ctx,
            @Input(name = "url", type = OoaType.STRING, description = "Page to start on.") String url,
            @Input(name = "browser", type = OoaType.STRING, required = false, defaultValue = "\"edge\"", enumValues = {"edge", "chrome", "chromium"}, description = "Browser to record in.") String which,
            @Input(name = "timeout_minutes", type = OoaType.NUMBER, required = false, defaultValue = "30", description = "Stop recording automatically after this long.") int minutes) {
        List<Map<String, Object>> events = Collections.synchronizedList(new ArrayList<>());
        final boolean[] stopped = {false};
        Map<String, String> env = new HashMap<>();
        if (!which.equals("chromium")) env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
        try (Playwright pw = Playwright.create(new Playwright.CreateOptions().setEnv(env))) {
            BrowserType.LaunchOptions lo = new BrowserType.LaunchOptions().setHeadless(false);
            if (which.equals("edge")) lo.setChannel("msedge");
            if (which.equals("chrome")) lo.setChannel("chrome");
            com.microsoft.playwright.Browser b = pw.chromium().launch(lo);
            BrowserContext c = b.newContext();
            c.exposeBinding("__ooaRecord", (source, args) -> { if (args.length > 0 && args[0] instanceof Map<?, ?> m && events.size() < 500) events.add((Map<String, Object>) m); return null; });
            c.exposeBinding("__ooaStop", (source, args) -> { stopped[0] = true; return null; });
            c.addInitScript(RECORDER_JS);
            Page p = c.newPage();
            p.navigate(url);
            long end = System.currentTimeMillis() + minutes * 60_000L;
            while (!stopped[0] && System.currentTimeMillis() < end) {
                ctx.checkCancelled();
                if (c.pages().isEmpty()) break;
                try { c.pages().get(0).waitForTimeout(250); } catch (PlaywrightException e) { break; }
            }
            try { b.close(); } catch (PlaywrightException ignored) { }
        } catch (PlaywrightException e) { throw pw(e, "Recording"); }
        List<Object> steps = new ArrayList<>();
        steps.add(stepMap("ooa.browser/open", Map.of("url", url, "browser", which), "Recorded: open " + url));
        Map<String, Object> lastType = null;
        boolean password = false;
        for (Map<String, Object> e : events) {
            String kind = String.valueOf(e.get("kind")), sel = String.valueOf(e.get("selector"));
            if (kind.equals("type")) {
                boolean pwd = Boolean.TRUE.equals(e.get("password"));
                password |= pwd;
                Object text = pwd ? "$password" : String.valueOf(e.getOrDefault("value", ""));
                if (lastType != null && sel.equals(((Map<String, Object>) lastType.get("args")).get("selector"))) { ((Map<String, Object>) lastType.get("args")).put("text", text); continue; }
                lastType = stepMap("ooa.browser/type_text", new LinkedHashMap<>(Map.of("selector", sel, "text", text)), pwd ? "Recorded password field: map a Credential variable named password" : "Recorded typing");
                steps.add(lastType);
            } else {
                lastType = null;
                if (kind.equals("select")) steps.add(stepMap("ooa.browser/select_option", Map.of("selector", sel, "option", String.valueOf(e.get("value"))), "Recorded selection"));
                else steps.add(stepMap("ooa.browser/click", Map.of("selector", sel), "Recorded click" + (e.get("label") != null && !String.valueOf(e.get("label")).isBlank() ? ": " + e.get("label") : "")));
            }
        }
        steps.add(stepMap("ooa.browser/close", Map.of(), "Recorded: close browser"));
        ctx.info("Recorded " + (steps.size() - 2) + " action(s)" + (password ? "; password fields use the variable $password" : ""));
        return Map.of("steps", steps, "count", BigDecimal.valueOf(steps.size()));
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
    @Action(id = "element_action", name = "Capture: element action", category = "Capture", timeoutSeconds = 120, capabilities = "browser",
            description = "Do one action on a captured element: click, type, read text, attributes or properties, select, check, hover, and more.")
    @Output(name = "result", description = "Value returned by get, is and count actions.")
    public Map<String, Object> elementAction(ActionContext ctx,
            @Input(name = "selector", type = OoaType.STRING, description = D_SEL + " Use Pick in Agent Studio to capture it.") String sel,
            @Input(name = "action", type = OoaType.STRING, enumValues = {"click", "double_click", "right_click", "hover", "focus", "set_text", "append_text", "clear",
                    "press_key", "get_text", "get_value", "get_attribute", "get_property", "select_by_text", "select_by_value", "select_by_index", "get_selected",
                    "get_options", "check", "uncheck", "is_checked", "is_visible", "is_enabled", "count", "scroll_into_view"}, description = "What to do with the element.") String action,
            @Input(name = "value", required = false, description = "Text, key, attribute or property name, option or index, depending on the action. Credentials allowed for set_text.") Object value,
            @Input(name = "timeout_ms", type = OoaType.NUMBER, required = false, defaultValue = "30000", description = D_TIMEOUT) double timeout,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"browser\"", description = "Session name.") String session) {
        Page page = s(ctx, session).page;
        Locator all = page.locator(sel);
        Locator l = all.first();
        String v = value instanceof SecureString ss ? ss.reveal() : value == null ? null : Values.text(value);
        java.util.function.Supplier<String> need = () -> { if (v == null || v.isEmpty()) throw new ActionException("BROWSER.VALUE_REQUIRED", "The action '" + action + "' needs a value"); return v; };
        Object result = null;
        try {
            switch (action) {
                case "click" -> l.click(new Locator.ClickOptions().setTimeout(timeout));
                case "double_click" -> l.dblclick(new Locator.DblclickOptions().setTimeout(timeout));
                case "right_click" -> l.click(new Locator.ClickOptions().setTimeout(timeout).setButton(com.microsoft.playwright.options.MouseButton.RIGHT));
                case "hover" -> l.hover(new Locator.HoverOptions().setTimeout(timeout));
                case "focus" -> l.focus(new Locator.FocusOptions().setTimeout(timeout));
                case "set_text" -> l.fill(need.get(), new Locator.FillOptions().setTimeout(timeout));
                case "append_text" -> { l.click(new Locator.ClickOptions().setTimeout(timeout)); l.press("End"); l.pressSequentially(need.get()); }
                case "clear" -> l.clear(new Locator.ClearOptions().setTimeout(timeout));
                case "press_key" -> l.press(need.get(), new Locator.PressOptions().setTimeout(timeout));
                case "get_text" -> result = l.innerText(new Locator.InnerTextOptions().setTimeout(timeout)).strip();
                case "get_value" -> result = l.inputValue(new Locator.InputValueOptions().setTimeout(timeout));
                case "get_attribute" -> result = l.getAttribute(need.get(), new Locator.GetAttributeOptions().setTimeout(timeout));
                case "get_property" -> { l.waitFor(new Locator.WaitForOptions().setTimeout(timeout)); result = Values.toJsonable(l.evaluate("(e, p) => e[p]", need.get())); }
                case "select_by_text" -> l.selectOption(new com.microsoft.playwright.options.SelectOption().setLabel(need.get()), new Locator.SelectOptionOptions().setTimeout(timeout));
                case "select_by_value" -> l.selectOption(need.get(), new Locator.SelectOptionOptions().setTimeout(timeout));
                case "select_by_index" -> l.selectOption(new com.microsoft.playwright.options.SelectOption().setIndex(Integer.parseInt(need.get().trim())), new Locator.SelectOptionOptions().setTimeout(timeout));
                case "get_selected" -> { l.waitFor(new Locator.WaitForOptions().setTimeout(timeout)); result = l.evaluate("e => e.options && e.selectedIndex >= 0 ? e.options[e.selectedIndex].text : null"); }
                case "get_options" -> { l.waitFor(new Locator.WaitForOptions().setTimeout(timeout)); result = l.evaluate("e => Array.from(e.options || []).map(o => o.text)"); }
                case "check" -> l.check(new Locator.CheckOptions().setTimeout(timeout));
                case "uncheck" -> l.uncheck(new Locator.UncheckOptions().setTimeout(timeout));
                case "is_checked" -> result = l.isChecked(new Locator.IsCheckedOptions().setTimeout(timeout));
                case "is_visible" -> result = l.isVisible();
                case "is_enabled" -> result = l.isEnabled(new Locator.IsEnabledOptions().setTimeout(timeout));
                case "count" -> result = BigDecimal.valueOf(all.count());
                case "scroll_into_view" -> l.scrollIntoViewIfNeeded(new Locator.ScrollIntoViewIfNeededOptions().setTimeout(timeout));
                default -> throw new ActionException("BROWSER.BAD_ACTION", "Unknown element action '" + action + "'");
            }
        } catch (PlaywrightException e) { throw pw(e, action + " on " + sel);
        } catch (NumberFormatException e) { throw new ActionException("BROWSER.BAD_INDEX", "select_by_index needs a whole number"); }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("result", result);
        return out;
    }

    @Condition(id = "element_visible", name = "Element is visible", description = "True when an element matching the selector is visible now.")
    public boolean elementVisible(ActionContext ctx,
            @Input(name = "selector", type = OoaType.STRING, description = D_SEL) String sel,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"browser\"", description = "Session name.") String session) {
        try { return s(ctx, session).page.locator(sel).first().isVisible(); } catch (PlaywrightException e) { throw pw(e, "Checking " + sel); }
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
          const box = document.createElement('div');
          box.style.cssText = 'position:fixed;pointer-events:none;z-index:2147483646;border:2px solid #a371ed;background:rgba(163,113,237,.12);border-radius:3px;display:none';
          const tip = document.createElement('div');
          tip.style.cssText = 'position:fixed;z-index:2147483647;top:10px;right:10px;background:#2f3336;color:#fff;font:13px Segoe UI,sans-serif;padding:8px 12px;border-radius:10px';
          tip.textContent = 'OOA Capture: click the element you want';
          const add = () => { if (document.body && !box.isConnected) { document.body.append(box, tip); } };
          if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', add); else add();
          document.addEventListener('mousemove', (e) => {
            const t = e.target; if (!t || t === box || t === tip) return;
            const r = t.getBoundingClientRect();
            Object.assign(box.style, { display: 'block', left: r.left + 'px', top: r.top + 'px', width: r.width + 'px', height: r.height + 'px' });
          }, true);
          document.addEventListener('click', (e) => {
            e.preventDefault(); e.stopPropagation();
            const t = e.target;
            window.__ooaPicked({ selector: sel(t), tag: t.tagName.toLowerCase(), text: (t.innerText || t.value || '').trim().slice(0, 80),
              type: t.getAttribute('type') || '', name: t.getAttribute('name') || '', id: t.id || '' });
          }, true);
        })();
        """;

    @SuppressWarnings("unchecked")
    @Action(id = "pick_element", name = "Pick element (design time)", category = "Capture", timeoutSeconds = 900, capabilities = "browser",
            description = "Open a page and let a person click one element; returns a robust selector and the element's details. Used by Agent Studio's Pick button.")
    @Output(name = "selector", type = OoaType.STRING, description = "Selector for the clicked element.")
    @Output(name = "element", type = OoaType.DICTIONARY, description = "tag, text, type, name and id of the element.")
    public Map<String, Object> pickElement(ActionContext ctx,
            @Input(name = "url", type = OoaType.STRING, description = "Page to open.") String url,
            @Input(name = "browser", type = OoaType.STRING, required = false, defaultValue = "\"edge\"", enumValues = {"edge", "chrome", "chromium"}, description = "Browser.") String which,
            @Input(name = "timeout_minutes", type = OoaType.NUMBER, required = false, defaultValue = "10", description = "Give up after this long.") int minutes) {
        final Map<String, Object>[] picked = new Map[]{null};
        Map<String, String> env = new HashMap<>();
        if (!which.equals("chromium")) env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
        try (Playwright pw = Playwright.create(new Playwright.CreateOptions().setEnv(env))) {
            BrowserType.LaunchOptions lo = new BrowserType.LaunchOptions().setHeadless(false);
            if (which.equals("edge")) lo.setChannel("msedge");
            if (which.equals("chrome")) lo.setChannel("chrome");
            com.microsoft.playwright.Browser b = pw.chromium().launch(lo);
            BrowserContext c = b.newContext();
            c.exposeBinding("__ooaPicked", (source, args) -> { if (args.length > 0 && args[0] instanceof Map<?, ?> m) picked[0] = (Map<String, Object>) m; return null; });
            c.addInitScript(PICKER_JS);
            Page p = c.newPage();
            p.navigate(url);
            long end = System.currentTimeMillis() + minutes * 60_000L;
            while (picked[0] == null && System.currentTimeMillis() < end) {
                ctx.checkCancelled();
                if (c.pages().isEmpty()) break;
                try { c.pages().get(0).waitForTimeout(200); } catch (PlaywrightException e) { break; }
            }
            try { b.close(); } catch (PlaywrightException ignored) { }
        } catch (PlaywrightException e) { throw pw(e, "Picking an element"); }
        if (picked[0] == null) throw new ActionException("BROWSER.NOTHING_PICKED", "No element was picked", "Click an element in the browser window before closing it.");
        return Map.of("selector", String.valueOf(picked[0].get("selector")), "element", picked[0]);
    }
}
