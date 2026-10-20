package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.sdk.OoaException;

import java.net.URI;
import java.util.*;
import java.util.regex.Pattern;

/**
 * The kinds of trigger, where each is evaluated and what each needs. Runner kinds are watched by the One Runner on
 * the trigger's runner (local machine events); server kinds are polled by the Command Center (remote systems).
 * Validation turns a request body into the stored config; secrets are never stored, only vault references.
 */
final class TriggerKinds {
    private TriggerKinds() {}

    enum Where { RUNNER, SERVER, WEBHOOK }

    record Kind(String id, Where where, String label, boolean windowsOnly) {}

    static final Map<String, Kind> KINDS = new LinkedHashMap<>();
    static {
        for (Kind k : List.of(
                new Kind("file", Where.RUNNER, "File arrives", false),
                new Kind("file_changed", Where.RUNNER, "File changed", false),
                new Kind("file_deleted", Where.RUNNER, "File deleted", false),
                new Kind("folder_created", Where.RUNNER, "Folder created", false),
                new Kind("folder_deleted", Where.RUNNER, "Folder deleted", false),
                new Kind("window_opened", Where.RUNNER, "Window opened", true),
                new Kind("window_closed", Where.RUNNER, "Window closed", true),
                new Kind("process_started", Where.RUNNER, "Process started", false),
                new Kind("process_stopped", Where.RUNNER, "Process stopped", false),
                new Kind("service_started", Where.RUNNER, "Service started", true),
                new Kind("service_stopped", Where.RUNNER, "Service stopped", true),
                new Kind("hotkey", Where.RUNNER, "Hotkey pressed", true),
                new Kind("email", Where.SERVER, "New email (IMAP)", false),
                new Kind("outlook", Where.SERVER, "New email (Microsoft 365 Outlook)", false),
                new Kind("servicenow", Where.SERVER, "ServiceNow record", false),
                new Kind("http_poll", Where.SERVER, "Universal poller (HTTP)", false),
                new Kind("webhook", Where.WEBHOOK, "Webhook", false)))
            KINDS.put(k.id(), k);
    }

    static final Set<String> FOLDER_KINDS = Set.of("file", "file_changed", "file_deleted", "folder_created", "folder_deleted");
    static final Pattern VAULT_REF = Pattern.compile("^[A-Za-z0-9_.-]{1,80}/[A-Za-z0-9_.-]{1,80}/[A-Za-z0-9_.-]{1,80}$");
    static final Pattern SERVICE = Pattern.compile("^[A-Za-z0-9_.\\- $]{1,256}$");
    static final Pattern TABLE = Pattern.compile("^[a-z][a-z0-9_]{0,79}$");
    static final int MIN_INTERVAL = 15, DEFAULT_INTERVAL = 60;

    static Kind kind(String id) {
        Kind k = KINDS.get(id);
        if (k == null) throw bad("Unknown trigger kind '" + id + "'; use one of " + String.join(", ", KINDS.keySet()));
        return k;
    }

    static OoaException bad(String message) { return new OoaException("CR.BAD_TRIGGER", message); }

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
    static Map<String, Object> validate(String kind, Map<String, Object> body) {
        Map<String, Object> in = new LinkedHashMap<>(body);
        if (body.get("config") instanceof Map<?, ?> c) in.putAll((Map<String, Object>) c);   // settings may come flat or under "config"
        Map<String, Object> out = new LinkedHashMap<>();
        switch (kind) {
            case "file", "file_changed", "file_deleted", "folder_created", "folder_deleted" -> {
                need(in, "folder", "A " + kind.replace('_', ' ') + " trigger needs a folder on the runner");
                if (in.get("recursive") != null) out.put("recursive", Boolean.parseBoolean(String.valueOf(in.get("recursive"))));
            }
            case "window_opened", "window_closed" -> out.put("title", pattern(need(in, "title", "A window trigger needs a title pattern")));
            case "process_started", "process_stopped" -> {
                String p = need(in, "process", "A process trigger needs a process name such as notepad.exe");
                if (p.contains("/") || p.contains("\\")) throw bad("Give the process name only, such as notepad.exe, not a path");
                out.put("process", p);
            }
            case "service_started", "service_stopped" -> {
                String s = need(in, "service", "A service trigger needs a Windows service name such as Spooler");
                if (!SERVICE.matcher(s).matches()) throw bad("Invalid service name '" + s + "'; use the service name shown by sc query");
                out.put("service", s);
            }
            case "hotkey" -> {
                String k = need(in, "hotkey", "A hotkey trigger needs a key combination such as CTRL+ALT+F9");
                if (!k.toUpperCase(Locale.ROOT).matches("^[A-Z0-9_]+(\\s*\\+\\s*[A-Z0-9_]+){0,4}$")) throw bad("Write the hotkey like CTRL+ALT+F9");
                out.put("hotkey", k.toUpperCase(Locale.ROOT).replace(" ", ""));
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
                filters(in, out);
                out.put("interval_seconds", interval(in));
            }
            case "outlook" -> {
                String tok = vaultRef(in, "token_ref", "", false), secret = vaultRef(in, "client_secret_ref", "", false);
                String mailbox = str(in, "mailbox");
                if (tok != null) out.put("token_ref", tok);
                else if (secret != null) {
                    out.put("tenant_id", need(in, "tenant_id", "App sign-in needs the Microsoft Entra tenant id"));
                    out.put("client_id", need(in, "client_id", "App sign-in needs the application (client) id"));
                    out.put("client_secret_ref", secret);
                    if (mailbox == null) throw bad("App sign-in needs the mailbox to read, such as invoices@contoso.com");
                    if (str(in, "login_url") != null) out.put("login_url", httpsUrl(in, "login_url", "login_url", true));
                } else throw bad("An Outlook trigger needs a Microsoft Graph token_ref, or tenant_id, client_id and client_secret_ref (vault references)");
                if (mailbox != null) out.put("mailbox", mailbox);
                out.put("mail_folder", Objects.requireNonNullElse(str(in, "mail_folder"), "inbox"));
                if (str(in, "graph_url") != null) out.put("graph_url", httpsUrl(in, "graph_url", "graph_url", true));
                filters(in, out);
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
            case "webhook" -> { }
            default -> throw bad("Unknown trigger kind '" + kind + "'");
        }
        if (KINDS.get(kind).where() == Where.SERVER && in.get("fire_existing") != null)
            out.put("fire_existing", Boolean.parseBoolean(String.valueOf(in.get("fire_existing"))));
        return out;
    }

    private static String pattern(String p) {
        if (p.startsWith("re:")) {
            try { Pattern.compile(p.substring(3)); } catch (RuntimeException e) { throw bad("Not a valid regular expression: " + p.substring(3)); }
        }
        return p;
    }

    private static void filters(Map<String, Object> in, Map<String, Object> out) {
        if (str(in, "from_contains") != null) out.put("from_contains", str(in, "from_contains"));
        if (str(in, "subject_contains") != null) out.put("subject_contains", str(in, "subject_contains"));
    }

    /** Config as shown to users: internal state (keys starting with _) removed. */
    static Map<String, Object> publicConfig(Map<String, Object> config) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (config != null) config.forEach((k, v) -> { if (!k.startsWith("_")) m.put(k, v); });
        return m;
    }
}
