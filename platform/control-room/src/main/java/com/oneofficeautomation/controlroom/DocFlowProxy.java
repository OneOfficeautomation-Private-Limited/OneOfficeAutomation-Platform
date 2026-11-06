package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Document Automation (AI > Document Automation): the Command Center forwards /api/docflow/... to the DocFlow service
 * (the separate oneoffice-docauto server) at /api/docs/v1/..., after checking the person's permission. DocFlow trusts
 * the service token and is told the tenant (X-OOA-Tenant) and the person (X-OOA-User); it keeps each tenant's
 * documents, processes and routing apart. The browser never sees the token or DocFlow's address.
 * <p>
 * Where DocFlow is: on premises, Settings docflow_url (default http://127.0.0.1:8085) and docflow_token_ref (a vault
 * reference folder/credential/attribute). In the SaaS service the region's operator sets OOA_DOCFLOW_URL and
 * OOA_DOCFLOW_TOKEN for every tenant; a tenant cannot point it elsewhere.
 * <p>
 * Permissions: docs.view to see, docs.review to review documents, docs.manage to add documents and change processes,
 * document types and reference data, docs.engines (administrators) for engines, routing and cloud opt-in.
 * <p>
 * Two ways back from DocFlow: documents waiting for review become Task inbox tasks (kind document_review, for everyone
 * with docs.review; {@link #syncReviews}), and a process's "Start a bot" delivery calls
 * /api/docflow-callback/start-bot with the service token, which starts the bot as the person who set the delivery up.
 * And one more way in: a live process's email inbox (intake.email, IMAP or Microsoft 365) is read here, with the
 * mailbox's vault secret as the person who set it up, and new PDF and image attachments go to the process
 * ({@link #pollMail}); /api/docflow/mail-status says how each inbox's last check went.
 */
final class DocFlowProxy {
    static final String DEFAULT_URL = "http://127.0.0.1:8085";
    static final String TASK_KIND = "document_review";

    /** The DocFlow routes the screens use (relative to /api/docs/v1/); anything else is not forwarded. */
    private static final Pattern ROUTES = Pattern.compile("^(df/[A-Za-z0-9._~/-]+|providers|costs|usage|admin/settings|admin/providers/[a-z0-9-]+/test"
            + "|documents/[A-Za-z0-9_-]+/(result|page/\\d{1,4}\\.png|file))$");
    private static final Pattern REVIEW = Pattern.compile("^df/documents/([A-Za-z0-9_-]+)/(review|reclassify)$");
    private static final Pattern FLOW_WRITE = Pattern.compile("^df/flows(/[A-Za-z0-9._-]+)?$");
    private static final Set<String> REQUEST_HEADERS = Set.of("Content-Type", "Accept");
    private static final List<String> RESPONSE_HEADERS = List.of("Content-Type", "Content-Disposition");

    private final ControlRoom cr;
    private final HttpClient http;

    DocFlowProxy(ControlRoom cr) {
        this.cr = cr;
        // DocFlow runs next to the Command Center: never through the outbound proxy for internet traffic
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).proxy(HttpClient.Builder.NO_PROXY).build();
    }

    /** The permission a request needs. */
    static String permission(String method, String route) {
        if (route.startsWith("admin/")) return "docs.engines";
        if (method.equals("GET")) return "docs.view";
        if (REVIEW.matcher(route).matches()) return "docs.review";
        return "docs.manage";
    }

    void forward(HttpExchange ex, ControlRoom.User u, String method, String route) throws IOException {
        if (route.equals("mail-status") && method.equals("GET")) {
            cr.require(u, "docs.view", "see Document Automation");
            ControlRoomServer.sendJson(ex, 200, Map.of("inboxes", cr.db.query("SELECT process_id, last_check, last_error, documents FROM docflow_mail ORDER BY process_id")));
            return;
        }
        if (route.equals("version") && method.equals("GET")) {
            cr.require(u, "docs.view", "see Document Automation");
            ControlRoomServer.sendJson(ex, 200, version());
            return;
        }
        if (!ROUTES.matcher(route).matches() || route.contains("..")) throw new OoaException("CR.NOT_FOUND", "Not found");
        String perm = permission(method, route);
        cr.require(u, perm, switch (perm) {
            case "docs.engines" -> "change Document Automation's engines";
            case "docs.review" -> "review documents";
            case "docs.manage" -> "change Document Automation";
            default -> "see Document Automation";
        });
        byte[] in = method.equals("GET") || method.equals("DELETE") ? null : body(ex);
        String type = ex.getRequestHeaders().getFirst("Content-Type");
        if (in != null && FLOW_WRITE.matcher(route).matches() && type != null && type.startsWith("application/json")) in = stampBotDeliveries(u, in);
        Map<String, String> headers = new HashMap<>();
        for (String h : REQUEST_HEADERS) { String v = ex.getRequestHeaders().getFirst(h); if (v != null) headers.put(h, v); }
        HttpResponse<byte[]> r = send(method, route, ex.getRequestURI().getRawQuery(), in, headers, person(u));
        if (r.statusCode() < 300) afterReview(u, method, route, in);

        var h = ex.getResponseHeaders();
        h.set("X-Content-Type-Options", "nosniff");
        h.set("Cache-Control", "no-store");
        h.set("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
        for (String name : RESPONSE_HEADERS) r.headers().firstValue(name).ifPresent(v -> h.set(name, v));
        byte[] b = r.body();
        if ("HEAD".equalsIgnoreCase(ex.getRequestMethod())) { ex.sendResponseHeaders(r.statusCode(), -1); return; }
        ex.sendResponseHeaders(r.statusCode(), b.length == 0 ? -1 : b.length);
        if (b.length > 0) try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    private static String person(ControlRoom.User u) { return u.displayName() == null || u.displayName().isBlank() ? u.username() : u.displayName(); }

    /** One request to DocFlow with the token, tenant and person. */
    private HttpResponse<byte[]> send(String method, String route, String rawQuery, byte[] body, Map<String, String> headers, String person) {
        HttpRequest.Builder rq = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/docs/v1/" + route + (rawQuery == null ? "" : "?" + rawQuery)))
                .timeout(Duration.ofMinutes(5)).header("X-OOA-Tenant", tenant())
                // URL-encoded (UTF-8): display names may be Arabic, and header values must stay plain text
                .header("X-OOA-User", java.net.URLEncoder.encode(person, StandardCharsets.UTF_8).replace("+", "%20"));
        String token = token();
        if (!token.isEmpty()) rq.header("Authorization", "Bearer " + token);
        if (headers != null) headers.forEach(rq::header);
        rq.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        HttpResponse<byte[]> r;
        try { r = http.send(rq.build(), HttpResponse.BodyHandlers.ofByteArray()); }
        catch (IOException e) {
            throw new OoaException("DOCS.UNAVAILABLE", "The Document Automation service is not reachable", "Start DocFlow, or check Settings > Document Automation (" + baseUrl() + ").");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OoaException("DOCS.UNAVAILABLE", "Interrupted");
        }
        if (r.statusCode() == 401) throw new OoaException("DOCS.TOKEN", "The Document Automation service refused the Command Center's token",
                "Put the same token in DocFlow (DOCAUTO_API_TOKEN) and in the vault credential named by Settings > Document Automation.");
        return r;
    }

    /**
     * A "Start a bot" delivery runs as the person who saves it: they must be allowed to start that agent, and DocFlow is
     * told to call the Command Center back (platform) rather than an address someone typed in.
     */
    @SuppressWarnings("unchecked")
    private byte[] stampBotDeliveries(ControlRoom.User u, byte[] in) {
        Object parsed;
        try { parsed = JsonLite.parse(new String(in, StandardCharsets.UTF_8)); } catch (IllegalArgumentException e) { return in; }
        if (!(parsed instanceof Map<?, ?> flow)) return in;
        boolean changed = false;
        // an email inbox reads the mailbox's vault secret as the person who sets it up (who must be its consumer)
        if (flow.get("intake") instanceof Map<?, ?> intake && intake.get("email") instanceof Map<?, ?> raw && !raw.isEmpty()) {
            Map<String, Object> email = (Map<String, Object>) raw;
            if ("email".equals(email.get("kind")) && email.get("host") instanceof String host && !host.isBlank()) NetGuard.check(cr, host, "The mail server");
            String ref = String.valueOf(email.getOrDefault("kind", "").equals("outlook") ? email.getOrDefault("client_secret_ref", "") : email.getOrDefault("password_ref", ""));
            if (Boolean.TRUE.equals(email.get("enabled")) && !ref.isBlank()) vaultAccess(u.username(), u.role(), ref);
            email.put("requestedBy", u.username());
            changed = true;
        }
        if (!(flow.get("deliveries") instanceof List<?> list)) return changed ? JsonLite.write(flow).getBytes(StandardCharsets.UTF_8) : in;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> raw) || !"start_bot".equals(raw.get("type"))) continue;
            Map<String, Object> d = (Map<String, Object>) raw;
            Map<String, Object> p = d.get("params") instanceof Map<?, ?> pm ? new LinkedHashMap<>((Map<String, Object>) pm) : new LinkedHashMap<>();
            String bot = String.valueOf(p.getOrDefault("bot", "")).trim();
            if (!bot.endsWith(".flow.json")) bot = bot + ".flow.json";
            cr.require(u, "runs.start", "start agents from a document process");
            cr.folders.require(u.username(), u.role(), bot, "run", "start this agent");
            p.put("bot", bot);
            p.put("platform", true);
            p.put("requestedBy", u.username());
            p.remove("url");
            p.remove("authHeader");
            d.put("params", p);
            changed = true;
        }
        return changed ? JsonLite.write(flow).getBytes(StandardCharsets.UTF_8) : in;
    }

    /** A review done on the review screen closes its Task inbox task at once, as that person. */
    @SuppressWarnings("unchecked")
    private void afterReview(ControlRoom.User u, String method, String route, byte[] in) {
        Matcher m = REVIEW.matcher(route);
        if (!method.equals("POST") || !m.matches()) return;
        String outcome = "reclassified";
        if (m.group(2).equals("review")) {
            Object action = null;
            try { if (in != null && JsonLite.parse(new String(in, StandardCharsets.UTF_8)) instanceof Map<?, ?> b) action = b.get("action"); } catch (IllegalArgumentException ignored) { }
            outcome = "invalid".equals(action) ? "rejected" : "approved";
        }
        for (Map<String, Object> t : openTasks().getOrDefault(m.group(1), List.of())) close((String) t.get("id"), outcome, u.username());
    }

    // ------------------------------------------------------------------ Task inbox

    /** Whether Document Automation is set up here (so the background sync does not knock on a service that is not there). */
    boolean configured() {
        if (cr.tenant != null) return env.apply("OOA_DOCFLOW_URL") != null;
        return !String.valueOf(cr.platform.setting("docflow_url")).isBlank() || !String.valueOf(cr.platform.setting("docflow_token_ref")).isBlank();
    }

    /**
     * Keeps the Task inbox in step with DocFlow's review queue: a task for each document waiting for review (due at the
     * process's review target), and tasks of documents no longer waiting (reviewed elsewhere, read again) are closed.
     * Returns how many tasks it opened and closed.
     */
    @SuppressWarnings("unchecked")
    int[] syncReviews() {
        if (!configured()) return new int[] {0, 0};
        HttpResponse<byte[]> r = send("GET", "df/review", null, null, null, "Command Center");
        if (r.statusCode() != 200) return new int[] {0, 0};
        List<Object> queue = (List<Object>) JsonLite.parse(new String(r.body(), StandardCharsets.UTF_8));
        Map<String, List<Map<String, Object>>> open = openTasks();
        Set<String> waiting = new HashSet<>();
        int opened = 0, closed = 0;
        for (Object o : queue) {
            Map<String, Object> d = (Map<String, Object>) o;
            String doc = String.valueOf(d.get("id"));
            waiting.add(doc);
            if (open.containsKey(doc)) continue;
            Instant created = Instant.parse(String.valueOf(d.get("created_at")).replaceFirst("\\+00:00$", "Z"));
            double sla = d.get("slaHours") instanceof Number n ? n.doubleValue() : 24;
            List<Object> flags = d.get("flags") instanceof List<?> l ? (List<Object>) l : List.of();
            String why = flags.isEmpty() ? "Some values need a person's check" : String.valueOf(((Map<String, Object>) flags.get(0)).get("message"));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("document_id", doc);
            data.put("process_id", d.get("flowId"));
            data.put("process", d.get("flowName"));
            data.put("document_type", d.get("doctypeName"));
            data.put("link", "/documents/#/review/" + doc);
            cr.db.update("INSERT INTO human_tasks (id, kind, title, description, data, assignee, status, created, due) VALUES (?,?,?,?,?,?,?,?,?)",
                    ControlRoom.id("task"), TASK_KIND, "Review " + d.get("file_name") + " (" + d.get("flowName") + ")", d.get("doctypeName") + " · " + why,
                    JsonLite.write(data), "perm:docs.review", "open", created, created.plus(Duration.ofMinutes((long) (sla * 60))));
            opened++;
        }
        for (var e : open.entrySet())
            if (!waiting.contains(e.getKey())) for (Map<String, Object> t : e.getValue()) { close((String) t.get("id"), "done", "docflow"); closed++; }
        return new int[] {opened, closed};
    }

    @SuppressWarnings("unchecked")
    private Map<String, List<Map<String, Object>>> openTasks() {
        Map<String, List<Map<String, Object>>> out = new HashMap<>();
        for (Map<String, Object> t : cr.db.query("SELECT id, data FROM human_tasks WHERE kind = ? AND status IN ('open', 'claimed')", TASK_KIND)) {
            Object data = t.get("data") == null ? null : JsonLite.parse((String) t.get("data"));
            if (data instanceof Map<?, ?> m && m.get("document_id") instanceof String doc) out.computeIfAbsent(doc, k -> new ArrayList<>()).add(t);
        }
        return out;
    }

    private void close(String taskId, String outcome, String by) {
        cr.db.update("UPDATE human_tasks SET status = 'completed', outcome = ?, completed = ?, completed_by = ?, claimed_by = COALESCE(claimed_by, ?) WHERE id = ? AND status IN ('open', 'claimed')",
                outcome, ControlRoom.now(), by, by, taskId);
    }

    // ------------------------------------------------------------------ email inbox

    /** The attachment types a process reads from email (as Add documents accepts). */
    private static final Pattern READABLE = Pattern.compile("(?i).+\\.(pdf|png|jpe?g|tiff?)$");

    /**
     * Reads the email inbox of every live process that has one, at most every interval_seconds (60 by default): new
     * messages passing its from / subject filters, and their PDF and image attachments go to the process. The first
     * check only marks where the inbox is (older mail is not read). Returns how many documents it sent.
     */
    @SuppressWarnings("unchecked")
    int pollMail() {
        if (!configured()) return 0;
        HttpResponse<byte[]> r = send("GET", "df/flows", null, null, null, "Command Center");
        if (r.statusCode() != 200) return 0;
        int sent = 0;
        for (Object o : (List<Object>) JsonLite.parse(new String(r.body(), StandardCharsets.UTF_8))) {
            Map<String, Object> flow = (Map<String, Object>) o;
            Map<String, Object> email = flow.get("intake") instanceof Map<?, ?> in && in.get("email") instanceof Map<?, ?> e ? (Map<String, Object>) e : Map.of();
            if (!Boolean.TRUE.equals(email.get("enabled")) || !"live".equals(flow.get("status"))) continue;
            String id = String.valueOf(flow.get("id"));
            Map<String, Object> row = cr.db.one("SELECT * FROM docflow_mail WHERE process_id = ?", id);
            long every = Math.max(60, email.get("interval_seconds") instanceof Number n ? n.longValue() : 60);
            if (row != null && row.get("last_check") != null && Instant.parse(String.valueOf(row.get("last_check"))).plusSeconds(every).isAfter(Instant.now())) continue;
            String cursor = row == null ? null : (String) row.get("mail_cursor"), error = null;
            int docs = 0;
            try {
                MailSource src = mails.open(email);
                MailSource.Batch b = src.fetch(cursor, false);
                for (Map<String, Object> msg : b.messages()) {
                    if (!MailSource.matches(msg, email)) continue;
                    for (MailSource.Attachment a : src.attachments(msg)) if (READABLE.matcher(a.name()).matches()) { upload(id, a, msg); docs++; }
                }
                cursor = b.cursor();
            } catch (IOException | RuntimeException e) { error = problem(e, email); }
            Instant now = ControlRoom.now();
            String err = error == null ? null : error.length() > 1000 ? error.substring(0, 1000) : error;
            if (row == null) cr.db.update("INSERT INTO docflow_mail (process_id, mail_cursor, last_check, last_error, documents) VALUES (?,?,?,?,?)", id, cursor, now, err, docs);
            else cr.db.update("UPDATE docflow_mail SET mail_cursor = ?, last_check = ?, last_error = ?, documents = COALESCE(documents, 0) + ? WHERE process_id = ?", cursor, now, err, docs, id);
            sent += docs;
        }
        return sent;
    }

    interface Mailboxes { MailSource open(Map<String, Object> email) throws IOException; }
    /** Opens an inbox's mailbox (tests replace it). */
    Mailboxes mails = this::mailSource;

    /** What went wrong with an inbox, in words a person can act on. */
    static String problem(Exception e, Map<String, Object> email) {
        String host = String.valueOf(email.getOrDefault("host", ""));
        if (e instanceof java.net.UnknownHostException) return "The mail server " + host + " is not known: check its name";
        if (e instanceof java.net.ConnectException || e instanceof java.net.SocketTimeoutException) return "The mail server " + host + " does not answer on port " + email.getOrDefault("port", 993);
        if (e instanceof javax.net.ssl.SSLException) return "A secure connection to " + host + " failed: " + e.getMessage();
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    /** The mailbox of an email inbox, as an email trigger's (IMAP, or Microsoft 365 through Graph). */
    MailSource mailSource(Map<String, Object> email) throws IOException {
        String by = String.valueOf(email.getOrDefault("requestedBy", ""));
        if ("outlook".equals(email.get("kind"))) {
            String token = GraphMailSource.clientCredentialsToken(null, String.valueOf(email.get("tenant_id")), String.valueOf(email.get("client_id")),
                    secret(by, String.valueOf(email.get("client_secret_ref"))));
            return new GraphMailSource(null, (String) email.get("mailbox"), (String) email.getOrDefault("mail_folder", "inbox"), token);
        }
        String host = String.valueOf(email.getOrDefault("host", ""));
        NetGuard.check(cr, host, "The mail server");
        return new ImapMailSource(host, email.get("port") instanceof Number p ? p.intValue() : 993, !Boolean.FALSE.equals(email.get("ssl")),
                String.valueOf(email.get("username")), secret(by, String.valueOf(email.get("password_ref"))), String.valueOf(email.getOrDefault("mail_folder", "INBOX")), 30_000);
    }

    /** A mailbox secret, as the person who set the inbox up: they must still be active and a consumer of its vault folder. */
    private String secret(String by, String ref) {
        Map<String, Object> u = cr.db.one("SELECT role FROM users WHERE username = ? AND enabled = TRUE", by);
        if (u == null) throw new OoaException("VAULT.DENIED", "The person who set up this inbox (" + by + ") is not an active user", "Open the process and save its email inbox again.");
        vaultAccess(by, (String) u.get("role"), ref);
        return cr.systemCredential(ref);
    }

    private void vaultAccess(String by, String role, String ref) {
        String[] p = ref.split("/");
        Map<String, Object> l = p.length == 3 ? cr.db.one("SELECT * FROM lockers WHERE name = ?", p[0]) : null;
        if (l == null || !cr.consumes(by, role, l))
            throw new OoaException("VAULT.DENIED", "'" + by + "' may not use vault folder '" + (p.length > 0 ? p[0] : ref) + "'", "Ask the vault folder's owner to add you as a consumer.");
    }

    private void upload(String processId, MailSource.Attachment a, Map<String, Object> msg) {
        String boundary = "ooa" + UUID.randomUUID().toString().replace("-", "");
        String name = a.name().replaceAll("[\\r\\n\"]", "_");
        byte[] head = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"files\"; filename=\"" + name + "\"\r\nContent-Type: " + a.contentType() + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[head.length + a.data().length + tail.length];
        System.arraycopy(head, 0, body, 0, head.length);
        System.arraycopy(a.data(), 0, body, head.length, a.data().length);
        System.arraycopy(tail, 0, body, head.length + a.data().length, tail.length);
        HttpResponse<byte[]> r = send("POST", "df/flows/" + java.net.URLEncoder.encode(processId, StandardCharsets.UTF_8) + "/documents", "source=email&requireLive=true", body,
                Map.of("Content-Type", "multipart/form-data; boundary=" + boundary), "Email from " + String.valueOf(msg.getOrDefault("from", "")).replaceAll("[\\r\\n]", " "));
        if (r.statusCode() >= 300) throw new OoaException("DOCS.EMAIL", "DocFlow refused " + a.name() + " (HTTP " + r.statusCode() + ")");
    }

    // ------------------------------------------------------------------ from a bot

    /**
     * An agent's "Send document to process": the runner sends the file for one of its running runs; it goes to the
     * process (by id or name, which must be live) as the person the run belongs to, who must be able to see Document
     * Automation. With wait=true the answer has the document's status and fields.
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> fromBot(HttpExchange ex, Map<String, Object> device, Map<String, String> q) throws IOException {
        Map<String, Object> run = cr.runOnDevice(device, q.get("run_id"));
        if (!"running".equals(run.get("status"))) throw new OoaException("CR.FORBIDDEN", "Only running agents can send documents");
        String by = (String) (run.get("run_as") != null ? run.get("run_as") : run.get("requested_by"));
        Map<String, Object> user = cr.db.one("SELECT id, username, role, display_name FROM users WHERE username = ? AND enabled = TRUE", by);
        if (user == null) throw new OoaException("CR.FORBIDDEN", "The run's user " + by + " is not active");
        cr.require(new ControlRoom.User((String) user.get("id"), by, (String) user.get("role"), (String) user.get("display_name"), false), "docs.view", "send documents to Document Automation");
        String process = String.valueOf(q.getOrDefault("process", "")).trim();
        HttpResponse<byte[]> flows = send("GET", "df/flows", null, null, null, "Command Center");
        Map<String, Object> flow = flows.statusCode() != 200 ? null : ((List<Object>) JsonLite.parse(new String(flows.body(), StandardCharsets.UTF_8))).stream()
                .map(o -> (Map<String, Object>) o).filter(f -> process.equals(f.get("id")) || process.equalsIgnoreCase(String.valueOf(f.get("name")))).findFirst().orElse(null);
        if (flow == null) throw new OoaException("CR.NOT_FOUND", "No Document Automation process '" + process + "'", "Use the process's name or id as shown in Document Automation.");
        boolean wait = "true".equals(q.get("wait"));
        String id = String.valueOf(flow.get("id"));
        Map<String, String> headers = Map.of("Content-Type", String.valueOf(ex.getRequestHeaders().getFirst("Content-Type")));
        String person = "Agent " + String.valueOf(run.get("bot_path")).replace(".flow.json", "") + " (" + by + ")";
        HttpResponse<byte[]> r = send("POST", "df/flows/" + java.net.URLEncoder.encode(id, StandardCharsets.UTF_8) + "/documents", "source=bot&requireLive=true&wait=" + wait, body(ex), headers, person);
        Object parsed = JsonLite.parse(new String(r.body(), StandardCharsets.UTF_8));
        if (r.statusCode() >= 300) {
            Object msg = parsed instanceof Map<?, ?> m ? (m.get("message") != null ? m.get("message") : m.get("detail")) : null;
            throw new OoaException("DOCS.REFUSED", "Document Automation refused the document: " + (msg == null ? "HTTP " + r.statusCode() : msg));
        }
        List<Object> docs = (List<Object>) ((Map<String, Object>) parsed).get("documents");
        Map<String, Object> d = docs.isEmpty() ? Map.of() : (Map<String, Object>) docs.get(0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("document_id", d.get("documentId"));
        out.put("process", flow.get("name"));
        out.put("status", d.get("status"));
        if (wait && d.get("documentId") != null) {
            HttpResponse<byte[]> full = send("GET", "df/documents/" + d.get("documentId"), null, null, null, person);
            if (full.statusCode() == 200 && JsonLite.parse(new String(full.body(), StandardCharsets.UTF_8)) instanceof Map<?, ?> fd) {
                out.put("status", fd.get("status"));
                out.put("fields", fd.get("fields"));
            }
        }
        out.put("needs_review", "validation".equals(out.get("status")));
        return out;
    }

    // ------------------------------------------------------------------ Start a bot

    /**
     * DocFlow's "Start a bot" delivery: checks the service token and tenant, then starts the agent as the person who set
     * the delivery up, on their default runner or the runner pool chosen. The agent gets the inputs it declares among:
     * document (everything), document_id, process, status, file_name, and any field by its name.
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> startBot(HttpExchange ex, String ip) throws IOException {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        String token = token();
        if (token.isEmpty() || auth == null || !MessageDigest.isEqual(auth.getBytes(StandardCharsets.UTF_8), ("Bearer " + token).getBytes(StandardCharsets.UTF_8)))
            throw new OoaException("CR.UNAUTHENTICATED", "Not the Document Automation service", null);
        if (!tenant().equals(String.valueOf(ex.getRequestHeaders().getFirst("X-OOA-Tenant")))) throw new OoaException("CR.FORBIDDEN", "Another tenant's process");
        Map<String, Object> b = ControlRoomServer.map(ControlRoomServer.body(ex));
        String bot = String.valueOf(b.getOrDefault("bot", ""));
        String by = String.valueOf(b.getOrDefault("requestedBy", ""));
        Map<String, Object> user = cr.db.one("SELECT username, role FROM users WHERE username = ? AND enabled = TRUE", by);
        if (user == null) throw new OoaException("CR.NOT_FOUND", "The person who set up this delivery (" + by + ") is not an active user", "Open the process and save its delivery again.");
        Map<String, Object> doc = b.get("input") instanceof Map<?, ?> in ? (Map<String, Object>) in : Map.of();
        Map<String, Object> inputs = new LinkedHashMap<>();
        Map<String, Object> flow = (Map<String, Object>) cr.content(bot, "production").get("flow");
        Map<String, Object> fields = doc.get("fields") instanceof Map<?, ?> f ? (Map<String, Object>) f : Map.of();
        for (Object v : (List<Object>) flow.getOrDefault("variables", List.of())) {
            Map<String, Object> var = (Map<String, Object>) v;
            if (!List.of("input", "in_out").contains(var.get("direction"))) continue;
            String name = String.valueOf(var.get("name"));
            Object value = switch (name) {
                case "document" -> doc;
                case "document_id" -> doc.get("documentId");
                case "process" -> doc.get("flowName");
                case "status" -> doc.get("status");
                case "file_name" -> doc.get("fileName");
                default -> fields.get(name);
            };
            if (value != null) inputs.put(name, value);
        }
        String pool = b.get("pool") instanceof String s && !s.isBlank() ? s : null;
        return cr.startRun(by, (String) user.get("role"), bot, null, pool, inputs, "docflow:" + doc.getOrDefault("flowId", ""), ip, 0, null, pool == null ? by : null, null);
    }

    // ------------------------------------------------------------------ which DocFlow

    /** The DocFlow this release is built and tested with: contract, pinned, minimum, ref (docflow-compat.properties). */
    static final Properties COMPAT = new Properties();
    static {
        try (var in = DocFlowProxy.class.getResourceAsStream("/docflow-compat.properties")) { if (in != null) COMPAT.load(in); }
        catch (IOException ignored) { }
    }

    private volatile Map<String, Object> lastVersion;
    private volatile Instant lastVersionAt = Instant.EPOCH;

    /**
     * Whether the DocFlow in use is one this Command Center works with, for the Document Automation page. It only
     * warns: nothing is blocked. Asked of DocFlow (GET /api/docs/v1/admin/version) at most once a minute.
     */
    Map<String, Object> version() {
        Map<String, Object> cached = lastVersion;
        if (cached != null && lastVersionAt.plusSeconds(60).isAfter(Instant.now())) return cached;
        Map<String, Object> out = new LinkedHashMap<>();
        String pinned = COMPAT.getProperty("pinned", ""), minimum = COMPAT.getProperty("minimum", pinned);
        out.put("contract", COMPAT.getProperty("contract", "v1"));
        out.put("tested", pinned);
        out.put("minimum", minimum);
        String version = null, status;
        if (!configured()) status = "not_set_up";
        else {
            try {
                HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(URI.create(baseUrl() + "/api/docs/v1/admin/version")).timeout(Duration.ofSeconds(5)).GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                Object body = r.statusCode() == 200 ? JsonLite.parse(new String(r.body(), StandardCharsets.UTF_8)) : null;
                if (body instanceof Map<?, ?> m && m.get("version") != null) version = String.valueOf(m.get("version"));
                status = version == null ? "unknown" : compare(version, minimum, pinned);
            } catch (IOException | IllegalArgumentException e) {
                status = "unreachable";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                status = "unreachable";
            }
        }
        out.put("version", version);
        out.put("status", status);
        out.put("compatible", status.equals("compatible") || status.equals("newer"));
        out.put("message", switch (status) {
            case "compatible" -> "DocFlow " + version + " works with this One CommandCenter.";
            case "newer" -> "DocFlow " + version + " is newer than the version tested with this release (" + pinned + "). It should work; tell support if something looks wrong.";
            case "too_old" -> "DocFlow " + version + " is too old for this One CommandCenter. Update DocFlow to " + pinned + " (at least " + minimum + ").";
            case "other_contract" -> "DocFlow " + version + " speaks a different version of the interface than this One CommandCenter (tested with " + pinned + "). Install DocFlow " + pinned + ".";
            case "unreachable" -> "The Document Automation service does not answer at " + baseUrl() + ". Start DocFlow, or check Settings > Document Automation.";
            case "not_set_up" -> "Document Automation's service is not set up yet (Settings > Document Automation).";
            default -> "DocFlow answered but did not say its version. It may be older than " + minimum + ".";
        });
        lastVersion = out;
        lastVersionAt = Instant.now();
        return out;
    }

    /**
     * How a DocFlow version compares with this release: compatible (from the minimum up to the tested version), newer
     * (same major version, newer than tested), too_old, other_contract (another major version), or unknown.
     */
    static String compare(String version, String minimum, String pinned) {
        int[] v = parse(version), min = parse(minimum), tested = parse(pinned);
        if (v == null || min == null || tested == null) return "unknown";
        if (v[0] != tested[0]) return v[0] < tested[0] && order(v, min) < 0 ? "too_old" : "other_contract";
        if (order(v, min) < 0) return "too_old";
        return order(v, tested) > 0 ? "newer" : "compatible";
    }

    private static int[] parse(String s) {
        Matcher m = Pattern.compile("^v?(\\d+)\\.(\\d+)(?:\\.(\\d+))?").matcher(s == null ? "" : s.trim());
        return m.find() ? new int[] {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), m.group(3) == null ? 0 : Integer.parseInt(m.group(3))} : null;
    }

    private static int order(int[] a, int[] b) { for (int i = 0; i < 3; i++) if (a[i] != b[i]) return Integer.compare(a[i], b[i]); return 0; }

    // ------------------------------------------------------------------ where DocFlow is

    /** Uploads are forwarded as they came (multipart), up to Settings > Largest upload. */
    private static byte[] body(HttpExchange ex) throws IOException {
        int limit = ControlRoomServer.maxBody.getAsInt();
        byte[] b = ex.getRequestBody().readNBytes(limit + 1);
        if (b.length > limit) throw new OoaException("CR.TOO_LARGE", "The upload is larger than the Largest upload setting allows", "Raise Settings > Largest upload, or send fewer files at once");
        return b;
    }

    private String tenant() { return cr.tenant == null ? "default" : cr.tenant; }

    String baseUrl() {
        String u = cr.tenant != null ? env.apply("OOA_DOCFLOW_URL") : String.valueOf(cr.platform.setting("docflow_url")).trim();
        return (u == null || u.isBlank() ? DEFAULT_URL : u.trim()).replaceAll("/+$", "");
    }

    private String token() {
        if (cr.tenant != null) { String t = env.apply("OOA_DOCFLOW_TOKEN"); return t == null ? "" : t.trim(); }
        String ref = String.valueOf(cr.platform.setting("docflow_token_ref")).trim();
        return ref.isEmpty() ? "" : cr.systemCredential(ref);
    }

    /** The environment (tests replace it). */
    static java.util.function.Function<String, String> env = System::getenv;
}
