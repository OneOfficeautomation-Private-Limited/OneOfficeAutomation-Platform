package com.oneofficeautomation.packages.browser;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import com.oneofficeautomation.packages.AiBridge;
import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;

import java.math.BigDecimal;
import java.net.URI;
import java.util.*;

/**
 * Web goal agent: give a goal in plain words ("find the order 4711 and download its invoice") and a language model
 * works the page step by step. Each step it sees the page's address, title, visible text and its numbered controls
 * (never password values or vault secrets), and answers with one tool: click, type, select, scroll, go to an address,
 * go back, wait or finish. Secrets are typed through {{secret:name}} placeholders the model never sees the value of.
 * Allowed sites keep the agent on the sites you name.
 */
@OoaPackage(id = "ooa.web_agent", name = "Web goal agent", version = "1.0.0",
        description = "Reach a goal on a web page described in plain words: an AI model reads the page and clicks, types and selects until done.")
public final class WebAgentPackage {
    static final String P = "WEBAGENT";

    private static final String SNAPSHOT_JS = """
            () => {
              const sel = 'a[href],button,input,select,textarea,[role=button],[role=link],[role=checkbox],[role=tab],[role=menuitem],[role=option],[contenteditable=true],summary';
              document.querySelectorAll('[data-ooa-ai]').forEach(e => e.removeAttribute('data-ooa-ai'));
              const out = []; let n = 0;
              for (const e of document.querySelectorAll(sel)) {
                const r = e.getBoundingClientRect(), st = getComputedStyle(e);
                if (r.width < 2 || r.height < 2 || st.visibility === 'hidden' || st.display === 'none' || e.disabled) continue;
                if (e.tagName === 'INPUT' && e.type === 'hidden') continue;
                if (++n > 200) break;
                e.setAttribute('data-ooa-ai', String(n));
                const tag = e.tagName.toLowerCase(), type = (e.getAttribute('type') || '').toLowerCase();
                let label = (e.getAttribute('aria-label') || '').trim();
                if (!label && e.id) { const l = document.querySelector('label[for="' + CSS.escape(e.id) + '"]'); if (l) label = l.innerText.trim(); }
                if (!label) label = (tag === 'input' || tag === 'textarea' ? (e.getAttribute('placeholder') || e.getAttribute('title') || e.getAttribute('name') || '')
                  : (e.innerText || e.getAttribute('title') || e.getAttribute('alt') || '')).trim();
                const item = { id: n, tag: tag, type: type, label: label.replace(/\\s+/g, ' ').slice(0, 80) };
                if (tag === 'input' || tag === 'textarea') {
                  if (type === 'checkbox' || type === 'radio') item.checked = e.checked;
                  else item.value = type === 'password' ? (e.value ? '(filled)' : '') : String(e.value || '').slice(0, 60);
                }
                if (tag === 'select') { const o = e.options[e.selectedIndex]; item.value = o ? o.text.trim() : ''; item.options = Array.from(e.options).slice(0, 25).map(x => x.text.trim()); }
                if (tag === 'a') item.href = String(e.getAttribute('href') || '').slice(0, 80);
                if (r.top > innerHeight || r.bottom < 0) item.offscreen = true;
                out.push(item);
              }
              const text = document.body ? document.body.innerText.replace(/\\n{3,}/g, '\\n\\n') : '';
              return { url: location.href, title: document.title, text: text.slice(0, 4000), elements: out };
            }""";

    static final List<Map<String, Object>> TOOLS = List.of(
            tool("click", "Click a control by its number.", Map.of("id", num("Control number"))),
            tool("type_text", "Replace the text of a field. For a secret write {{secret:NAME}}.", Map.of("id", num("Control number"), "text", str("Text to type"),
                    "press_enter", Map.of("type", "boolean", "description", "Press Enter afterwards"))),
            tool("select_option", "Choose an item of a drop-down list by its visible text.", Map.of("id", num("Control number"), "option", str("Visible text of the item"))),
            tool("scroll", "Scroll the page to see more.", Map.of("direction", Map.of("type", "string", "enum", List.of("down", "up")))),
            tool("go_to", "Open an address.", Map.of("url", str("Full address, https://..."))),
            tool("go_back", "Go back to the previous page.", Map.of()),
            tool("wait", "Wait for the page to finish something (1 to 10 seconds).", Map.of("seconds", num("Seconds"))),
            tool("finish", "Stop: the goal is reached, or cannot be reached.", Map.of("success", Map.of("type", "boolean", "description", "Whether the goal was reached"),
                    "answer", str("What was done or found, in one or two sentences"), "data", Map.of("type", "object", "description", "Values the goal asked for, if any")))
    );

    private static Map<String, Object> tool(String name, String description, Map<String, Object> props) {
        List<String> required = new ArrayList<>(props.keySet());
        required.remove("press_enter");
        required.remove("data");
        return Map.of("name", name, "description", description, "input_schema", Map.of("type", "object", "properties", props, "required", required));
    }
    private static Map<String, Object> num(String d) { return Map.of("type", "integer", "description", d); }
    private static Map<String, Object> str(String d) { return Map.of("type", "string", "description", d); }

    @Action(id = "start_session", name = "Start web agent session", category = "Web goal agent", timeoutSeconds = 300, capabilities = "browser",
            description = "Open a browser for the web goal agent (the same session works with every Browser action).")
    public Map<String, Object> startSession(ActionContext ctx,
            @Input(name = "url", type = OoaType.STRING, description = "Page to start on.") String url,
            @Input(name = "browser", type = OoaType.STRING, required = false, defaultValue = "\"edge\"", enumValues = {"edge", "chrome", "chromium", "firefox"}, description = "Browser to use.") String which,
            @Input(name = "headless", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Run without a visible window (for unattended agents).") boolean headless,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"browser\"", description = "Session name.") String session) {
        return new BrowserPackage().open(ctx, url, which, headless, null, session, false, "playwright", "new_window", null, 240000);
    }

    @Action(id = "end_session", name = "End web agent session", category = "Web goal agent", description = "Close the browser the web agent session opened.")
    public Map<String, Object> endSession(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"browser\"", description = "Session name.") String session) {
        return new BrowserPackage().close(ctx, session, "session", null);
    }

    @Action(id = "run", name = "Reach a goal", category = "Web goal agent", timeoutSeconds = 1800, capabilities = {"browser", "network", "ai"},
            description = "Describe the goal in plain words; the AI model works the page in a browser session until the goal is reached, cannot be reached, or the step limit.")
    @Output(name = "success", type = OoaType.BOOLEAN, description = "Whether the model reports the goal reached.")
    @Output(name = "answer", type = OoaType.STRING, description = "What was done or found.")
    @Output(name = "data", type = OoaType.DICTIONARY, description = "Values the goal asked for, if any.")
    @Output(name = "steps", type = OoaType.LIST, description = "Each step taken, in words.")
    @Output(name = "url", type = OoaType.STRING, description = "Address of the page at the end.")
    @Output(name = "input_tokens", type = OoaType.NUMBER, description = "Tokens sent to the model in all steps.")
    @Output(name = "output_tokens", type = OoaType.NUMBER, description = "Tokens the model answered with in all steps.")
    public Map<String, Object> run(ActionContext ctx,
            @Input(name = "goal", type = OoaType.STRING, example = "Search for invoice 4711 and tell me its total and due date.", description = "What to achieve, in plain words; ${...} interpolation allowed.") String goal,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"browser\"", description = "Browser session (from Start web agent session or Open browser).") String session,
            @Input(name = "max_steps", type = OoaType.NUMBER, required = false, defaultValue = "15", description = "Most actions to take, 1 to 60.") int maxSteps,
            @Input(name = "allowed_sites", type = OoaType.LIST, required = false, description = "Host names the agent may visit (sub-domains included), e.g. [\"portal.example.com\"]; any site when empty.") List<Object> allowedSites,
            @Input(name = "secrets", type = OoaType.DICTIONARY, required = false, description = "Name to Credential, e.g. {\"password\": a Credential}. The model types them as {{secret:password}} and never sees the values.") Map<String, Object> secrets,
            @Input(name = "instructions", type = OoaType.STRING, required = false, description = "Extra rules, e.g. \"Never press Delete or Pay.\"") String instructions,
            @Input(name = "provider", type = OoaType.STRING, required = false, defaultValue = "\"openai\"", enumValues = {"openai", "azure_openai", "anthropic", "openai_compatible", "huggingface", "aws_bedrock_sagemaker", "aws_bedrock", "google_vertex"}, description = AiBridge.D_PROVIDER) String provider,
            @Input(name = "connection", type = OoaType.STRING, required = false, description = AiBridge.D_CONNECTION) String connection,
            @Input(name = "model", type = OoaType.STRING, required = false, description = AiBridge.D_MODEL) String model,
            @Input(name = "api_key", type = OoaType.CREDENTIAL, required = false, description = AiBridge.D_KEY) Object key,
            @Input(name = "base_url", type = OoaType.STRING, required = false, description = AiBridge.D_BASE) String base,
            @Input(name = "mask_pii", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = AiBridge.D_MASK) boolean mask,
            @Input(name = "aws_access_key_id", type = OoaType.CREDENTIAL, required = false, description = AiBridge.D_AWS_KEY_ID) Object awsKeyId,
            @Input(name = "aws_region", type = OoaType.STRING, required = false, description = AiBridge.D_AWS_REGION) String awsRegion,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", description = "Seconds to wait for the model on each step; 180 when 0.") int timeout) {
        if (goal == null || goal.isBlank()) throw new ActionException(P + ".NO_GOAL", "Describe the goal");
        if (maxSteps < 1 || maxSteps > 60) throw new ActionException(P + ".BAD_OPTION", "max_steps must be between 1 and 60");
        if (ctx.sessions().get(session) instanceof ExtensionBridge.TabSession)
            throw new ActionException(P + ".NOT_SUPPORTED", "The web goal agent needs a browser it starts itself", "Use Start web agent session, or Open browser with engine playwright.");
        Page page = ctx.session(session, BrowserPackage.BrowserSession.class).page;
        List<String> sites = new ArrayList<>();
        if (allowedSites != null) for (Object o : allowedSites) { String s = Values.text(o).strip().toLowerCase(Locale.ROOT); if (!s.isEmpty()) sites.add(s); }
        Map<String, String> secretValues = new LinkedHashMap<>();
        Set<String> passwordOnly = new HashSet<>();
        if (secrets != null) secrets.forEach((k, v) -> {
            if (!k.matches("^[A-Za-z0-9_-]{1,40}$")) throw new ActionException(P + ".BAD_SECRET", "Secret names use letters, digits, - and _: " + k);
            if (!(v instanceof SecureString ss)) throw new ActionException(P + ".BAD_SECRET", "The secret '" + k + "' must be a Credential", "Map it to a Credential variable.");
            secretValues.put(k, ss.reveal());
            if (ss.passwordOnly()) passwordOnly.add(k);
        });
        AiBridge.Settings settings = new AiBridge.Settings(connection, provider, model, key, base, 0, 1024, mask, awsKeyId, awsRegion, timeout);

        StringBuilder system = new StringBuilder("You operate a web browser to reach the user's goal. Each turn you get the page: address, title, visible text and numbered controls. "
                + "Use exactly one tool per turn. Use only control numbers from the latest page. When the goal is reached, or cannot be reached, call finish. "
                + "Do not invent data. Do not buy, pay, delete or send anything unless the goal clearly asks for it.");
        if (!secretValues.isEmpty()) system.append(" Secrets you may type (write the placeholder, not a value): ").append(String.join(", ", secretValues.keySet().stream().map(n -> "{{secret:" + n + "}}").toList())).append('.');
        if (!sites.isEmpty()) system.append(" Stay on these sites: ").append(String.join(", ", sites)).append('.');
        if (instructions != null && !instructions.isBlank()) system.append(" Rules from the agent's author: ").append(instructions.strip());

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", system.toString()));
        messages.add(new LinkedHashMap<>(Map.of("role", "user", "content", "Goal: " + goal.strip() + "\n\n" + snapshot(page, secretValues))));
        List<Object> steps = new ArrayList<>();
        int in = 0, out = 0, nudges = 0;
        for (int step = 0; step < maxSteps; step++) {
            ctx.checkCancelled();
            Map<String, Object> r = AiBridge.call(ctx, settings, messages, TOOLS, "web_agent");
            in += (int) r.get("input_tokens"); out += (int) r.get("output_tokens");
            @SuppressWarnings("unchecked") List<Map<String, Object>> calls = (List<Map<String, Object>>) r.get("tool_calls");
            if (calls.isEmpty()) {
                if (++nudges > 2) return result(false, r.get("text") == null ? "The model stopped without finishing." : String.valueOf(r.get("text")), Map.of(), steps, page, in, out);
                messages.add(Map.of("role", "assistant", "content", r.get("text") == null ? "" : r.get("text")));
                messages.add(Map.of("role", "user", "content", "Answer with one of the tools."));
                step--;
                continue;
            }
            Map<String, Object> asst = new LinkedHashMap<>();
            asst.put("role", "assistant");
            asst.put("content", r.get("text") == null ? "" : r.get("text"));
            asst.put("tool_calls", calls);
            messages.add(asst);
            // older page views are shortened to their first line, so the conversation stays small
            for (Map<String, Object> m : messages) if ("tool".equals(m.get("role")) && m.get("content") instanceof String s && s.contains("\nPAGE ")) m.put("content", s.substring(0, s.indexOf("\nPAGE ")));
            for (int i = 0; i < calls.size(); i++) {
                Map<String, Object> call = calls.get(i);
                @SuppressWarnings("unchecked") Map<String, Object> args = call.get("args") instanceof Map<?, ?> a ? (Map<String, Object>) a : Map.of();
                String name = String.valueOf(call.get("name"));
                if (name.equals("finish")) {
                    boolean ok = Boolean.TRUE.equals(args.get("success"));
                    String answer = args.get("answer") == null ? "" : String.valueOf(args.get("answer"));
                    steps.add("finish: " + answer);
                    @SuppressWarnings("unchecked") Map<String, Object> data = args.get("data") instanceof Map<?, ?> d ? (Map<String, Object>) d : Map.of();
                    return result(ok, answer, data, steps, page, in, out);
                }
                String done;
                try { done = act(page, name, args, secretValues, passwordOnly, sites); }
                catch (PlaywrightException | ActionException e) { done = "Failed: " + firstLine(e.getMessage()); }
                steps.add(name + " " + describe(args) + " -> " + done);
                ctx.info("Web agent step " + (step + 1) + ": " + name + " " + describe(args) + " -> " + done);
                String outside = outside(page, sites);
                if (outside != null) {
                    page.goBack();
                    done += ". The page left the allowed sites (" + outside + ") and was taken back.";
                }
                Map<String, Object> toolMsg = new LinkedHashMap<>();
                toolMsg.put("role", "tool");
                toolMsg.put("tool_call_id", call.get("id"));
                toolMsg.put("name", name);
                toolMsg.put("content", done + (i == calls.size() - 1 ? "\n" + snapshot(page, secretValues) : ""));
                messages.add(toolMsg);
            }
        }
        return result(false, "Stopped after " + maxSteps + " steps without reaching the goal.", Map.of(), steps, page, in, out);
    }

    static Map<String, Object> result(boolean ok, String answer, Map<String, Object> data, List<Object> steps, Page page, int in, int out) {
        String url;
        try { url = page.url(); } catch (PlaywrightException e) { url = ""; }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", ok); r.put("answer", answer); r.put("data", data); r.put("steps", steps); r.put("url", url);
        r.put("input_tokens", BigDecimal.valueOf(in)); r.put("output_tokens", BigDecimal.valueOf(out));
        return r;
    }

    /** Runs one tool on the page and says what happened. */
    static String act(Page page, String name, Map<String, Object> a, Map<String, String> secrets, Set<String> passwordOnly, List<String> sites) {
        double t = 10000;
        switch (name) {
            case "click" -> { control(page, a).click(new Locator.ClickOptions().setTimeout(t)); settle(page); return "Clicked"; }
            case "type_text" -> {
                String text = String.valueOf(a.getOrDefault("text", ""));
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{\\{secret:([A-Za-z0-9_-]+)}}").matcher(text);
                StringBuilder sb = new StringBuilder();
                Locator l = control(page, a);
                while (m.find()) {
                    String v = secrets.get(m.group(1));
                    if (v == null) throw new ActionException(P + ".NO_SECRET", "There is no secret named " + m.group(1));
                    if (passwordOnly.contains(m.group(1)) && !"password".equals(l.evaluate("e => (e.getAttribute('type') || '').toLowerCase()")))
                        throw new ActionException("VAULT.PASSWORD_ONLY", "The secret " + m.group(1) + " may only be typed into password fields");
                    m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(v));
                }
                m.appendTail(sb);
                l.fill(sb.toString(), new Locator.FillOptions().setTimeout(t));
                if (Boolean.TRUE.equals(a.get("press_enter"))) { l.press("Enter"); settle(page); }
                return "Typed";
            }
            case "select_option" -> { control(page, a).selectOption(new com.microsoft.playwright.options.SelectOption().setLabel(String.valueOf(a.get("option"))), new Locator.SelectOptionOptions().setTimeout(t)); settle(page); return "Selected"; }
            case "scroll" -> { page.mouse().wheel(0, "up".equals(a.get("direction")) ? -700 : 700); page.waitForTimeout(400); return "Scrolled"; }
            case "go_to" -> {
                String url = String.valueOf(a.get("url"));
                if (!url.matches("^https?://.+")) return "Refused: only http and https addresses";
                String host = host(url);
                if (!sites.isEmpty() && !allowed(host, sites)) return "Refused: " + host + " is not an allowed site";
                page.navigate(url);
                settle(page);
                return "Opened";
            }
            case "go_back" -> { page.goBack(); settle(page); return "Went back"; }
            case "wait" -> { double s = a.get("seconds") instanceof Number n ? n.doubleValue() : 2; page.waitForTimeout(Math.max(1, Math.min(10, s)) * 1000); return "Waited"; }
            default -> { return "Unknown tool " + name; }
        }
    }

    static Locator control(Page page, Map<String, Object> a) {
        if (!(a.get("id") instanceof Number n)) throw new ActionException(P + ".BAD_STEP", "No control number given");
        Locator l = page.locator("[data-ooa-ai=\"" + n.intValue() + "\"]");
        if (l.count() == 0) throw new ActionException(P + ".NO_CONTROL", "There is no control " + n.intValue() + " on the page now");
        return l.first();
    }

    static void settle(Page page) {
        try { page.waitForLoadState(LoadState.DOMCONTENTLOADED, new Page.WaitForLoadStateOptions().setTimeout(10000)); } catch (PlaywrightException ignored) { }
        page.waitForTimeout(500);
    }

    /** The page as text for the model; vault secrets that appear on it are replaced by ****. */
    @SuppressWarnings("unchecked")
    static String snapshot(Page page, Map<String, String> secrets) {
        Map<String, Object> s;
        try { s = (Map<String, Object>) page.evaluate(SNAPSHOT_JS); }
        catch (PlaywrightException e) { return "PAGE (could not be read: " + firstLine(e.getMessage()) + ")"; }
        StringBuilder sb = new StringBuilder("PAGE ").append(s.get("url")).append("\nTITLE ").append(s.get("title")).append("\nCONTROLS\n");
        for (Object o : (List<Object>) s.getOrDefault("elements", List.of())) {
            Map<String, Object> e = (Map<String, Object>) o;
            sb.append('[').append(((Number) e.get("id")).intValue()).append("] ").append(e.get("tag"));
            if (e.get("type") instanceof String ty && !ty.isEmpty()) sb.append(' ').append(ty);
            sb.append(" \"").append(e.get("label")).append('"');
            if (e.get("value") instanceof String v && !v.isEmpty()) sb.append(" value=\"").append(v).append('"');
            if (e.get("checked") instanceof Boolean c) sb.append(c ? " checked" : " unchecked");
            if (e.get("options") instanceof List<?> l) sb.append(" options=").append(l);
            if (e.get("href") instanceof String h && !h.isEmpty()) sb.append(" href=").append(h);
            if (Boolean.TRUE.equals(e.get("offscreen"))) sb.append(" (scroll to see)");
            sb.append('\n');
        }
        sb.append("TEXT\n").append(s.get("text"));
        String text = sb.toString();
        for (String v : secrets.values()) if (v != null && v.length() >= 3) text = text.replace(v, SecureString.MASK);
        return text;
    }

    static String outside(Page page, List<String> sites) {
        if (sites.isEmpty()) return null;
        String url;
        try { url = page.url(); } catch (PlaywrightException e) { return null; }
        if (!url.startsWith("http")) return null;
        String h = host(url);
        return allowed(h, sites) ? null : h;
    }

    static boolean allowed(String host, List<String> sites) {
        for (String s : sites) if (host.equals(s) || host.endsWith("." + s)) return true;
        return false;
    }

    static String host(String url) {
        try { String h = URI.create(url).getHost(); return h == null ? "" : h.toLowerCase(Locale.ROOT); } catch (IllegalArgumentException e) { return ""; }
    }

    /** The step's arguments as the model gave them: secrets appear as their placeholders, never as values. */
    static String describe(Map<String, Object> a) { return JsonLite.write(Values.toJsonable(a)); }

    static String firstLine(String s) {
        if (s == null) return "";
        int i = s.indexOf('\n');
        return (i < 0 ? s : s.substring(0, i)).strip();
    }
}
