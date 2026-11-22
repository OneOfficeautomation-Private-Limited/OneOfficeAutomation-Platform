package com.oneofficeautomation.controlroom.runs;

import com.oneofficeautomation.controlroom.JsonPath;
import com.oneofficeautomation.controlroom.module.ModuleContext;

import com.oneofficeautomation.packages.ui.InputTriggers;
import com.oneofficeautomation.sdk.OoaException;

import java.net.URI;
import java.util.*;
import java.util.regex.Pattern;

/**
 * The kinds of trigger, where each is evaluated and what each needs. Runner kinds are watched by the OneRunner on
 * the trigger's runner (local machine events); server kinds are polled by One CommandCenter (remote systems), except
 * the Document Automation kinds, which DocFlow reports to it as they happen.
 * Validation turns a request body into the stored config; secrets are never stored, only vault references.
 */
public final class TriggerKinds {
    private TriggerKinds() {}

    public enum Where { RUNNER, SERVER, WEBHOOK }

    public record Kind(String id, Where where, String label, boolean windowsOnly) {}

    public static final Map<String, Kind> KINDS = java.util.Collections.synchronizedMap(new LinkedHashMap<>());
    /** Kinds feature modules add (ModuleContext.triggerKind): the module reports their events; nothing is polled. */
    static final Map<String, ModuleContext.TriggerKind> MODULE_KINDS = new java.util.concurrent.ConcurrentHashMap<>();

    /** A feature module's trigger kind (at most 20 characters, the triggers table's kind column). */
    public static synchronized void defineModuleKind(String kind, String label, ModuleContext.TriggerKind rules) {
        if (!kind.matches("[a-z][a-z0-9_]{1,19}")) throw new IllegalArgumentException("A trigger kind is lower case letters, digits and _, at most 20: " + kind);
        if (KINDS.containsKey(kind) && !MODULE_KINDS.containsKey(kind)) throw new IllegalStateException("The trigger kind " + kind + " is the kernel's");
        KINDS.put(kind, new Kind(kind, Where.SERVER, label, false));
        MODULE_KINDS.put(kind, rules);
    }

    /** True for a kind a module reports (nothing to poll). */
    public static boolean pushed(String kind) { return MODULE_KINDS.containsKey(kind); }
    static {
        for (Kind k : List.of(
                new Kind("file", Where.RUNNER, "File arrives", false),
                new Kind("file_changed", Where.RUNNER, "File changed", false),
                new Kind("file_deleted", Where.RUNNER, "File deleted", false),
                new Kind("folder_created", Where.RUNNER, "Folder created", false),
                new Kind("folder_deleted", Where.RUNNER, "Folder deleted", false),
                new Kind("file_renamed", Where.RUNNER, "File renamed", false),       // G6
                new Kind("folder_renamed", Where.RUNNER, "Folder renamed", false),   // G6
                new Kind("window_opened", Where.RUNNER, "Window opened", true),
                new Kind("window_closed", Where.RUNNER, "Window closed", true),
                new Kind("process_started", Where.RUNNER, "Process started", false),
                new Kind("process_stopped", Where.RUNNER, "Process stopped", false),
                new Kind("service_started", Where.RUNNER, "Service started", true),
                new Kind("service_stopped", Where.RUNNER, "Service stopped", true),
                new Kind("service_paused", Where.RUNNER, "Service paused", true),    // G6
                new Kind("hotkey", Where.RUNNER, "Hotkey pressed", true),
                new Kind("key_pressed", Where.RUNNER, "Key pressed in a window", true),
                new Kind("mouse_clicked", Where.RUNNER, "Mouse click on an element or window", true),
                new Kind("email", Where.SERVER, "New email (IMAP)", false),
                new Kind("outlook", Where.SERVER, "New email (Microsoft 365 Outlook)", false),
                new Kind("mail", Where.SERVER, "New email (any mail account)", false),
                new Kind("servicenow", Where.SERVER, "ServiceNow record", false),
                new Kind("http_poll", Where.SERVER, "Universal poller (HTTP)", false),
                new Kind("webhook", Where.WEBHOOK, "Webhook", false)))
            KINDS.put(k.id(), k);
    }

    public static final Set<String> FOLDER_KINDS = Set.of("file", "file_changed", "file_deleted", "folder_created", "folder_deleted", "file_renamed", "folder_renamed");
    static final Pattern VAULT_REF = Pattern.compile("^[A-Za-z0-9_.-]{1,80}/[A-Za-z0-9_.-]{1,80}/[A-Za-z0-9_.-]{1,80}$");
    static final Pattern SERVICE = Pattern.compile("^[A-Za-z0-9_.\\- $]{1,256}$");
    static final Pattern TABLE = Pattern.compile("^[a-z][a-z0-9_]{0,79}$");
    static final int MIN_INTERVAL = 15, DEFAULT_INTERVAL = 60;

    public static Kind kind(String id) {
        Kind k = KINDS.get(id);
        if (k == null) throw bad("Unknown trigger kind '" + id + "'; use one of " + String.join(", ", KINDS.keySet()));
        return k;
    }

    static OoaException bad(String message) { return new OoaException("CR.BAD_TRIGGER", message, "Correct the trigger's settings the message names."); }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null || String.valueOf(v).isBlank() ? null : String.valueOf(v).trim();
    }

    private static String need(Map<String, Object> m, String k, String what) {
        String v = str(m, k);
        if (v == null) throw bad(what);
        return v;
    }

    private static String vaultRef(Map<String, Object> m, String k, String what, boolean required) {
        String v = str(m, k);
        if (v == null) { if (required) throw bad(what + " (a vault reference like folder/credential/attribute)"); return null; }
        if (!VAULT_REF.matcher(v).matches()) throw bad(k + " must be a vault reference like folder/credential/attribute, never the secret itself");
        return v;
    }

    private static String httpsUrl(Map<String, Object> m, String k, String what, boolean allowHttp) {
        String v = need(m, k, what);
        URI u;
        try { u = URI.create(v); } catch (IllegalArgumentException e) { throw bad(k + " is not a valid URL"); }
        String s = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        if (!(s.equals("https") || (allowHttp && s.equals("http"))) || u.getHost() == null) throw bad(k + " must be an " + (allowHttp ? "http or https" : "https") + " URL");
        return v.replaceAll("/+$", "");
    }

    private static int interval(Map<String, Object> c) {
        Object v = c.get("interval_seconds");
        int n;
        try { n = v == null ? DEFAULT_INTERVAL : (int) Double.parseDouble(String.valueOf(v)); } catch (NumberFormatException e) { throw bad("interval_seconds must be a number"); }
        if (n < MIN_INTERVAL) throw bad("interval_seconds must be at least " + MIN_INTERVAL);
        return Math.min(n, 86400);
    }

    /**
     * Checks a create-trigger body and returns the config to store (kind-specific settings only). The folder and
     * pattern of folder kinds also stay in their own columns, as before.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> validate(String kind, Map<String, Object> body) {
        Map<String, Object> in = new LinkedHashMap<>(body);
        if (body.get("config") instanceof Map<?, ?> c) in.putAll((Map<String, Object>) c);   // settings may come flat or under "config"
        Map<String, Object> out = new LinkedHashMap<>();
        switch (kind) {
            case "file", "file_changed", "file_deleted", "folder_created", "folder_deleted", "file_renamed", "folder_renamed" -> {
                need(in, "folder", "A " + kind.replace('_', ' ') + " trigger needs a folder on the runner");
                if (in.get("recursive") != null) out.put("recursive", Boolean.parseBoolean(String.valueOf(in.get("recursive"))));
                // G6: "also start if it already exists" (file arrives, folder created)
                if ((kind.equals("file") || kind.equals("folder_created")) && in.get("fire_existing") != null)
                    out.put("fire_existing", Boolean.parseBoolean(String.valueOf(in.get("fire_existing"))));
            }
            case "window_opened", "window_closed" -> {
                boolean cs = Boolean.parseBoolean(String.valueOf(in.get("case_sensitive")));   // G6
                String title = need(in, "title", "A window trigger needs a title pattern");
                out.put("title", pattern(title));
                if (cs) out.put("case_sensitive", true);
            }
            case "process_started", "process_stopped" -> {
                String p = need(in, "process", "A process trigger needs a process name such as notepad.exe");
                if (p.contains("/") || p.contains("\\")) throw bad("Give the process name only, such as notepad.exe, not a path");
                out.put("process", p);
                String ev = str(in, "events");   // G6: started, stopped or both in one trigger
                if (ev != null) {
                    if (!PROCESS_EVENTS.contains(ev)) throw bad("events is started, stopped or both, not '" + ev + "'");
                    out.put("events", ev);
                }
            }
            case "service_started", "service_stopped", "service_paused" -> {
                String s = need(in, "service", "A service trigger needs a Windows service name such as Spooler");
                if (!SERVICE.matcher(s).matches()) throw bad("Invalid service name '" + s + "'; use the service name shown by sc query");
                out.put("service", s);
            }
            case "hotkey" -> {
                String k = need(in, "hotkey", "A hotkey trigger needs a key combination such as CTRL+ALT+F9");
                if (!k.toUpperCase(Locale.ROOT).matches("^[A-Z0-9_]+(\\s*\\+\\s*[A-Z0-9_]+){0,4}$")) throw bad("Write the hotkey like CTRL+ALT+F9");
                out.put("hotkey", k.toUpperCase(Locale.ROOT).replace(" ", ""));
            }
            case "key_pressed", "mouse_clicked" -> {
                // gap G37: a named key or combination (never typed text) or a click, optionally limited to a window
                try { out.putAll(kind.equals("key_pressed") ? InputTriggers.keyConfig(in) : InputTriggers.clickConfig(in)); }
                catch (IllegalArgumentException e) { throw bad(e.getMessage()); }
            }
            case "email" -> {
                out.put("host", need(in, "host", "An email trigger needs the IMAP server host"));
                boolean ssl = in.get("ssl") == null || Boolean.parseBoolean(String.valueOf(in.get("ssl")));
                out.put("ssl", ssl);
                int port;
                try { port = in.get("port") == null ? (ssl ? 993 : 143) : (int) Double.parseDouble(String.valueOf(in.get("port"))); } catch (NumberFormatException e) { throw bad("port must be a number"); }
                if (port < 1 || port > 65535) throw bad("port must be between 1 and 65535");
                out.put("port", port);
                out.put("username", need(in, "username", "An email trigger needs the mailbox user name"));
                out.put("password_ref", vaultRef(in, "password_ref", "An email trigger needs the password", true));
                out.put("mail_folder", Objects.requireNonNullElse(str(in, "mail_folder"), "INBOX"));
                filters(in, out, true);
                out.put("interval_seconds", interval(in));
            }
            case "outlook" -> {
                String tok = vaultRef(in, "token_ref", "", false), secret = vaultRef(in, "client_secret_ref", "", false);
                String mailbox = str(in, "mailbox"), signIn = str(in, "ms_signin");
                // DA-1: "Sign in with Microsoft" (MicrosoftSignIn); createTrigger checks the person saving made it
                if (signIn != null) {
                    if (!signIn.matches("ms[0-9a-f]{24}")) throw bad("Choose Sign in with Microsoft again: this sign-in is not known");
                    out.put("ms_signin", signIn);
                }
                else if (tok != null) out.put("token_ref", tok);
                else if (secret != null) {
                    out.put("tenant_id", need(in, "tenant_id", "App sign-in needs the Microsoft Entra tenant id"));
                    out.put("client_id", need(in, "client_id", "App sign-in needs the application (client) id"));
                    out.put("client_secret_ref", secret);
                    if (mailbox == null) throw bad("App sign-in needs the mailbox to read, such as invoices@contoso.com");
                    if (str(in, "login_url") != null) out.put("login_url", httpsUrl(in, "login_url", "login_url", true));
                } else throw bad("An Outlook trigger needs Sign in with Microsoft, or your own app (tenant_id, client_id and client_secret_ref), or a Microsoft Graph token_ref");
                if (mailbox != null) out.put("mailbox", mailbox);
                out.put("mail_folder", Objects.requireNonNullElse(str(in, "mail_folder"), "inbox"));
                if (str(in, "graph_url") != null) out.put("graph_url", httpsUrl(in, "graph_url", "graph_url", true));
                azureCloud(in, out);
                filters(in, out, false);
                out.put("interval_seconds", interval(in));
            }
            case "servicenow" -> {
                out.put("instance_url", httpsUrl(in, "instance_url", "A ServiceNow trigger needs the instance URL", true));
                String table = need(in, "table", "A ServiceNow trigger needs a table such as incident");
                if (!TABLE.matcher(table).matches()) throw bad("Invalid table '" + table + "'");
                out.put("table", table);
                if (str(in, "query") != null) out.put("query", str(in, "query"));
                String user = str(in, "username");
                String pw = vaultRef(in, "password_ref", "", false), tok = vaultRef(in, "token_ref", "", false);
                if (tok == null && (user == null || pw == null)) throw bad("A ServiceNow trigger needs a user name and password_ref, or a token_ref");
                if (user != null) out.put("username", user);
                if (pw != null) out.put("password_ref", pw);
                if (tok != null) out.put("token_ref", tok);
                out.put("interval_seconds", interval(in));
            }
            case "http_poll" -> {
                out.put("url", httpsUrl(in, "url", "A poller needs the URL to call", true));
                out.put("items_path", Objects.requireNonNullElse(str(in, "items_path"), "$"));
                JsonPath.compile((String) out.get("items_path"));
                out.put("id_field", need(in, "id_field", "A poller needs the id field that identifies each item"));
                String auth = vaultRef(in, "auth_ref", "", false);
                if (auth != null) out.put("auth_ref", auth);
                if (in.get("headers") instanceof Map<?, ?> h) {
                    Map<String, Object> hs = new LinkedHashMap<>();
                    h.forEach((k, v) -> {
                        String name = String.valueOf(k);
                        if (!name.matches("^[A-Za-z0-9-]{1,80}$") || name.equalsIgnoreCase("authorization")) throw bad("Header '" + name + "' is not allowed; use auth_ref for credentials");
                        hs.put(name, String.valueOf(v));
                    });
                    out.put("headers", hs);
                }
                out.put("interval_seconds", interval(in));
            }
            case "webhook" -> webhookConfig(in, out);
            case "mail" -> {   // Mail accounts (MA-4): the account is read once for all its rules; this trigger's rule says which emails
                out.put("mail_account", need(in, "mail_account", "Choose the mail account to read"));
                if (str(in, "folder") != null) out.put("folder", str(in, "folder"));
            }
            default -> {
                ModuleContext.TriggerKind rules = MODULE_KINDS.get(kind);
                if (rules == null) throw bad("Unknown trigger kind '" + kind + "'");
                out.putAll(rules.config(in));
            }
        }
        if (KINDS.get(kind).where() == Where.SERVER && !MODULE_KINDS.containsKey(kind) && in.get("fire_existing") != null)
            out.put("fire_existing", Boolean.parseBoolean(String.valueOf(in.get("fire_existing"))));
        return out;
    }

    private static String pattern(String p) {
        if (p.startsWith("re:")) {
            try { Pattern.compile(p.substring(3)); } catch (RuntimeException e) { throw bad("Not a valid regular expression: " + p.substring(3)); }
        }
        return p;
    }

    /**
     * The from and subject filters ("contains", any case) and, G6, "only emails with attachments". With lists (IMAP), a
     * filter with ; in it is a list where any one may match: kept as from_any or subject_any.
     */
    private static void filters(Map<String, Object> in, Map<String, Object> out, boolean lists) {
        for (String k : List.of("from_contains", "subject_contains")) {
            String v = str(in, k);
            if (v == null) continue;
            if (lists && v.contains(";")) {
                List<String> any = new ArrayList<>();
                for (String part : v.split(";")) if (!part.isBlank() && !any.contains(part.strip())) any.add(part.strip());
                if (any.size() > 50) throw bad(k + " lists at most 50 values");
                if (!any.isEmpty()) out.put(k.replace("_contains", "_any"), any);
            } else out.put(k, v);
        }
        if (Boolean.parseBoolean(String.valueOf(in.get("has_attachment")))) out.put("has_attachment", true);
    }

    /** Microsoft's clouds (G6): the Graph and sign-in addresses, and the token scope, of each. */
    public static final Map<String, List<String>> AZURE_CLOUDS = Map.of(
            "global", List.of("https://graph.microsoft.com/v1.0", "https://login.microsoftonline.com", "https://graph.microsoft.com/.default"),
            "us_government", List.of("https://graph.microsoft.us/v1.0", "https://login.microsoftonline.us", "https://graph.microsoft.us/.default"),
            "china", List.of("https://microsoftgraph.chinacloudapi.cn/v1.0", "https://login.chinacloudapi.cn", "https://microsoftgraph.chinacloudapi.cn/.default"));

    /** azure_cloud (G6): fills the Graph and sign-in addresses of that cloud unless they were given. */
    private static void azureCloud(Map<String, Object> in, Map<String, Object> out) {
        String cloud = str(in, "azure_cloud");
        if (cloud == null || cloud.equals("global")) return;
        List<String> hosts = AZURE_CLOUDS.get(cloud);
        if (hosts == null) throw bad("azure_cloud is global, us_government or china, not '" + cloud + "'");
        if (out.containsKey("ms_signin")) throw bad("Sign in with Microsoft works with Microsoft's global cloud; for " + cloud.replace('_', ' ') + " use your own app (tenant_id, client_id and client_secret_ref) or a token_ref");
        out.put("azure_cloud", cloud);
        out.putIfAbsent("graph_url", hosts.get(0));
        if (out.containsKey("client_secret_ref")) out.putIfAbsent("login_url", hosts.get(1));
        out.put("graph_scope", hosts.get(2));
    }

    static final Set<String> PROCESS_EVENTS = Set.of("started", "stopped", "both");
    static final Pattern HEADER = Pattern.compile("^[A-Za-z0-9-]{1,80}$");
    static final Pattern INPUT_NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,79}$");

    /**
     * A webhook's checks and mapping (G6), all optional: a header every call must carry (with a value kept in the vault,
     * or just present); an HMAC signature of the body (sha256 or sha1, in the header named, with the shared secret in the
     * vault), which then stands in for the X-OOA-Secret header; a challenge field whose value is echoed back to the
     * sender's address check; and payload values passed to agent inputs of their own (input name to a JSON path).
     */
    private static void webhookConfig(Map<String, Object> in, Map<String, Object> out) {
        String hdr = str(in, "required_header");
        if (hdr != null) {
            if (!HEADER.matcher(hdr).matches()) throw bad("required_header must be a header name such as X-Api-Key");
            out.put("required_header", hdr);
            String ref = vaultRef(in, "required_header_value_ref", "", false);
            if (ref != null) out.put("required_header_value_ref", ref);
        }
        String scheme = str(in, "signature_scheme");
        if (scheme != null && !scheme.equals("none")) {
            if (!Set.of("sha256", "sha1").contains(scheme)) throw bad("signature_scheme is sha256 or sha1, not '" + scheme + "'");
            String sh = need(in, "signature_header", "A signature check needs the header the sender puts the signature in, such as X-Hub-Signature-256");
            if (!HEADER.matcher(sh).matches()) throw bad("signature_header must be a header name such as X-Hub-Signature-256");
            out.put("signature_scheme", scheme);
            out.put("signature_header", sh);
            out.put("signature_secret_ref", vaultRef(in, "signature_secret_ref", "A signature check needs the shared secret", true));
        }
        String ch = str(in, "challenge_field");
        if (ch != null) {
            if (!INPUT_NAME.matcher(ch).matches()) throw bad("challenge_field must be a field name such as challenge");
            out.put("challenge_field", ch);
        }
        if (in.get("input_map") instanceof Map<?, ?> m && !m.isEmpty()) {
            if (m.size() > 50) throw bad("input_map has at most 50 entries");
            Map<String, Object> map = new LinkedHashMap<>();
            m.forEach((k, v) -> {
                String name = String.valueOf(k), path = v == null ? "" : String.valueOf(v).trim();
                if (!INPUT_NAME.matcher(name).matches() || name.equals("payload")) throw bad("'" + name + "' cannot be an input name in input_map");
                if (path.isEmpty()) throw bad("input_map: give the payload path for " + name + ", such as $.order.id");
                try { JsonPath.compile(path); } catch (RuntimeException e) { throw bad("input_map: '" + path + "' is not a payload path: " + e.getMessage()); }
                map.put(name, path);
            });
            out.put("input_map", map);
        } else if (in.get("input_map") != null && !(in.get("input_map") instanceof Map)) throw bad("input_map is an object of input names and payload paths");
    }

    /** Config as shown to users: internal state (keys starting with _) removed. */
    public static Map<String, Object> publicConfig(Map<String, Object> config) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (config != null) config.forEach((k, v) -> { if (!k.startsWith("_")) m.put(k, v); });
        return m;
    }
}
