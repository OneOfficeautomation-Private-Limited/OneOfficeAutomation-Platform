package com.oneofficeautomation.modules.docauto;

import com.oneofficeautomation.controlroom.ControlRoom.User;
import com.oneofficeautomation.controlroom.MailSource;
import com.oneofficeautomation.controlroom.module.ModuleContext;
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
import java.time.temporal.ChronoUnit;
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
 * <p>
 * Folder access: a process's folder ("Finance / Accounts payable") is the platform folder of that path, and its folder
 * permissions apply on top of the docs.* permissions: view to see the process, its documents and its review tasks, run
 * to add documents (also from bots), edit to change the process. A process without a folder is open to everyone with
 * the docs.* permissions. Lists are filtered; the overview's totals stay those of the whole tenant.
 */
public final class DocFlowProxy {
    public static final String DEFAULT_URL = "http://127.0.0.1:8085";
    public static final String TASK_KIND = "document_review";

    /** The DocFlow routes the screens use (relative to /api/docs/v1/); anything else is not forwarded. */
    private static final Pattern ROUTES = Pattern.compile("^(df/[A-Za-z0-9._~/-]+|providers|costs|usage|admin/settings|admin/providers/[a-z0-9-]+/test"
            + "|documents/[A-Za-z0-9_-]+/(result|page/\\d{1,4}\\.png|file))$");
    private static final Pattern REVIEW = Pattern.compile("^df/documents/([A-Za-z0-9_-]+)/(review|reclassify)$");
    private static final Pattern FLOW_WRITE = Pattern.compile("^df/flows(/[A-Za-z0-9._-]+)?$");
    private static final Pattern FLOW_ROUTE = Pattern.compile("^df/flows/([A-Za-z0-9._-]+)(/.*)?$");
    private static final Pattern DOC_ROUTE = Pattern.compile("^(?:df/)?documents/([A-Za-z0-9_-]+)(/.*)?$");
    /** Adding a reviewed document to a test set copies its file and values: the document is named in the body. */
    private static final Pattern TESTSET_DOC = Pattern.compile("^df/testsets/[A-Za-z0-9._-]+/documents$");
    private static final Set<String> REQUEST_HEADERS = Set.of("Content-Type", "Accept");
    private static final List<String> RESPONSE_HEADERS = List.of("Content-Type", "Content-Disposition");

    private final ModuleContext ctx;
    private final HttpClient http;

    public DocFlowProxy(ModuleContext ctx) {
        this.ctx = ctx;
        // DocFlow runs next to the Command Center: never through the outbound proxy for internet traffic
        // and over HTTP/1.1: DocFlow (uvicorn) does not take the HTTP/2 upgrade and logged a warning for every request
        this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).proxy(HttpClient.Builder.NO_PROXY).build();
    }

    /** The permission a request needs. */
    public static String permission(String method, String route) {
        if (route.startsWith("admin/")) return "docs.engines";
        if (method.equals("GET")) return "docs.view";
        if (REVIEW.matcher(route).matches()) return "docs.review";
        return "docs.manage";
    }

    private void requirePermission(User u, String method, String route) {
        String perm = permission(method, route);
        ctx.require(u, perm, switch (perm) {
            case "docs.engines" -> "change Document Automation's engines";
            case "docs.review" -> "review documents";
            case "docs.manage" -> "change Document Automation";
            default -> "see Document Automation";
        });
    }

    public void forward(HttpExchange ex, User u, String method, String route) throws IOException {
        if (route.equals("mail-status") && method.equals("GET")) {
            ctx.require(u, "docs.view", "see Document Automation");
            ctx.sendJson(ex, 200, Map.of("inboxes", ctx.db().query("SELECT process_id, last_check, last_error, documents FROM docflow_mail ORDER BY process_id")));
            return;
        }
        if (route.equals("version") && method.equals("GET")) {
            ctx.require(u, "docs.view", "see Document Automation");
            ctx.sendJson(ex, 200, version());
            return;
        }
        if (!ROUTES.matcher(route).matches() || route.contains("..")) throw new OoaException("CR.NOT_FOUND", "Not found", "Reload the page and choose it again; it may have been renamed, moved or deleted.");
        requirePermission(u, method, route);   // before the upload is read
        byte[] in = method.equals("GET") || method.equals("DELETE") ? null : body(ex);
        Map<String, String> headers = new HashMap<>();
        for (String h : REQUEST_HEADERS) { String v = ex.getRequestHeaders().getFirst(h); if (v != null) headers.put(h, v); }
        Answer r = exchange(u, method, route, ex.getRequestURI().getRawQuery(), in, headers, person(u));

        var h = ex.getResponseHeaders();
        h.set("X-Content-Type-Options", "nosniff");
        h.set("Cache-Control", "no-store");
        h.set("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
        for (String name : RESPONSE_HEADERS) r.headers().firstValue(name).ifPresent(v -> h.set(name, v));
        byte[] b = r.body();
        if ("HEAD".equalsIgnoreCase(ex.getRequestMethod())) { ex.sendResponseHeaders(r.status(), -1); return; }
        ex.sendResponseHeaders(r.status(), b.length == 0 ? -1 : b.length);
        if (b.length > 0) try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    /** DocFlow's answer to one request made for a person: status, headers and the body as the person may see it. */
    record Answer(int status, java.net.http.HttpHeaders headers, byte[] body) {
        /** The body as JSON; an answer that is not a success says what DocFlow said. */
        Object json() {
            Object parsed;
            try { parsed = JsonLite.parse(new String(body, StandardCharsets.UTF_8)); } catch (IllegalArgumentException e) { parsed = null; }
            if (status >= 300) {
                Object msg = parsed instanceof Map<?, ?> m ? (m.get("message") != null ? m.get("message") : m.get("detail")) : null;
                throw new OoaException(status == 404 ? "CR.NOT_FOUND" : "DOCS.REFUSED", "Document Automation refused it: " + (msg == null ? "HTTP " + status : msg), "Reload the page and choose it again; it may have been renamed, moved or deleted.");
            }
            return parsed;
        }
    }

    /**
     * One request to DocFlow for a person, with every check the screens get: the docs.* permission of the route, the
     * process folder's permission, "Start a bot" deliveries and email inboxes stamped with the person, the Task inbox
     * task closed after a review, and lists without what the person may not see. The screens (forward), AI assistants
     * (McpServer) and templates (Templates) all come through here.
     */
    Answer exchange(User u, String method, String route, String rawQuery, byte[] in, Map<String, String> headers, String person) {
        requirePermission(u, method, route);
        String type = headers == null ? null : headers.get("Content-Type");
        if (in != null && FLOW_WRITE.matcher(route).matches() && type != null && type.startsWith("application/json")) in = stampBotDeliveries(u, in);
        Map<String, Map<String, String>> all = ctx.folderEntries();
        Map<String, String> folders = checkFolder(u, method, route, in, all);
        HttpResponse<byte[]> r = send(method, route, rawQuery, in, headers, person);
        if (r.statusCode() < 300) afterReview(u, method, route, in);
        byte[] b = r.statusCode() == 200 && method.equals("GET") ? onlyVisible(u, route, r.body(), folders, all) : r.body();
        return new Answer(r.statusCode(), r.headers(), b);
    }

    /** A JSON request to DocFlow for a person (exchange), its answer parsed. */
    Object json(User u, String method, String route, Object body) {
        byte[] in = body == null ? null : JsonLite.write(body).getBytes(StandardCharsets.UTF_8);
        return exchange(u, method, route, null, in, in == null ? Map.of() : Map.of("Content-Type", "application/json"), person(u)).json();
    }

    // ------------------------------------------------------------------ folder access

    /** A process's folder as a platform folder path: "Finance / Accounts payable" is Finance/Accounts payable. */
    public static String folderKey(Object folder) {
        return folder == null ? "" : String.valueOf(folder).trim().replaceAll("\\s*/\\s*", "/").replaceAll("^/+|/+$", "");
    }

    /** What the person may do with a process in this folder (none, view, run, edit, manage); without a folder: manage. */
    String folderAccess(User u, Object folder, Map<String, Map<String, String>> all) {
        String key = folderKey(folder);
        return key.isEmpty() ? "manage" : ctx.folderAccess(u.username(), u.role(), key, all);
    }

    /** Process id -> folder, from DocFlow. */
    @SuppressWarnings("unchecked")
    private Map<String, String> processFolders() {
        Map<String, String> out = new HashMap<>();
        HttpResponse<byte[]> r = send("GET", "df/flows", null, null, null, "Command Center");
        if (r.statusCode() == 200)
            for (Object o : (List<Object>) JsonLite.parse(new String(r.body(), StandardCharsets.UTF_8))) {
                Map<String, Object> f = (Map<String, Object>) o;
                out.put(String.valueOf(f.get("id")), f.get("folder") == null ? "" : String.valueOf(f.get("folder")));
            }
        return out;
    }

    private void need(User u, Object folder, String level, Map<String, Map<String, String>> all, String what) {
        if (!ctx.atLeast(folderAccess(u, folder, all), level))
            throw new OoaException("CR.FORBIDDEN", "You do not have permission to " + what + " in the folder '" + folderKey(folder) + "'",
                    "Ask someone who manages the folder's permissions for " + level + " access.");
    }

    /**
     * Refuses a request on a process or document in a folder the person may not use at the level it needs. Returns the
     * processes' folders when the answer will be filtered (or null when nothing was looked up).
     */
    @SuppressWarnings("unchecked")
    private Map<String, String> checkFolder(User u, String method, String route, byte[] in, Map<String, Map<String, String>> all) {
        boolean listing = method.equals("GET") && List.of("df/flows", "df/flows/all/documents", "df/review", "df/overview").contains(route);
        Matcher f = FLOW_ROUTE.matcher(route), d = DOC_ROUTE.matcher(route);
        boolean flow = f.matches() && !f.group(1).equals("all"), doc = d.matches();
        Object newFolder = null;
        if (in != null && FLOW_WRITE.matcher(route).matches()) {
            try { if (JsonLite.parse(new String(in, StandardCharsets.UTF_8)) instanceof Map<?, ?> b && b.containsKey("folder")) newFolder = b.get("folder"); }
            catch (IllegalArgumentException ignored) { }
        }
        String docId = doc ? d.group(1) : null;
        if (in != null && method.equals("POST") && TESTSET_DOC.matcher(route).matches()) {
            try { if (JsonLite.parse(new String(in, StandardCharsets.UTF_8)) instanceof Map<?, ?> b && b.get("documentId") != null) docId = String.valueOf(b.get("documentId")); }
            catch (IllegalArgumentException ignored) { }
            if (docId != null && !docId.matches("[A-Za-z0-9_-]+")) throw new OoaException("CR.NOT_FOUND", "Not found", "Reload the page and choose it again; it may have been renamed, moved or deleted.");
        }
        if (!listing && !flow && docId == null && newFolder == null) return null;
        Map<String, String> folders = processFolders();
        if (newFolder != null) need(u, newFolder, "edit", all, "put a process");
        if (flow) {
            String rest = f.group(2) == null ? "" : f.group(2);
            String level = method.equals("GET") ? "view" : method.equals("POST") && (rest.equals("/documents") || rest.equals("/intake/scan")) ? "run" : "edit";
            need(u, folders.getOrDefault(f.group(1), ""), level, all, level.equals("view") ? "see this process" : level.equals("run") ? "add documents" : "change this process");
        }
        if (docId != null) {
            HttpResponse<byte[]> r = send("GET", "df/documents/" + docId, null, null, null, "Command Center");
            if (r.statusCode() == 200 && JsonLite.parse(new String(r.body(), StandardCharsets.UTF_8)) instanceof Map<?, ?> m)
                need(u, folders.getOrDefault(String.valueOf(m.get("flowId")), ""), "view", all, "see this document");
        }
        return folders;
    }

    /** Lists without what the person may not see: processes, documents and review queue items in other folders. */
    @SuppressWarnings("unchecked")
    private byte[] onlyVisible(User u, String route, byte[] body, Map<String, String> folders, Map<String, Map<String, String>> all) {
        if (folders == null || !List.of("df/flows", "df/flows/all/documents", "df/review", "df/overview").contains(route)) return body;
        java.util.function.Predicate<Object> visible = id -> ctx.atLeast(folderAccess(u, folders.getOrDefault(String.valueOf(id), ""), all), "view");
        Object parsed = JsonLite.parse(new String(body, StandardCharsets.UTF_8));
        Object out = switch (route) {
            case "df/flows" -> ((List<Object>) parsed).stream().filter(o -> visible.test(((Map<String, Object>) o).get("id"))).toList();
            case "df/flows/all/documents" -> ((List<Object>) parsed).stream().filter(o -> visible.test(((Map<String, Object>) o).get("instance_id"))).toList();
            case "df/review" -> ((List<Object>) parsed).stream().filter(o -> visible.test(((Map<String, Object>) o).get("flowId"))).toList();
            default -> {
                Map<String, Object> o = new LinkedHashMap<>((Map<String, Object>) parsed);
                if (o.get("flows") instanceof List<?> l) o.put("flows", l.stream().filter(x -> visible.test(((Map<String, Object>) x).get("id"))).toList());
                if (o.get("recent") instanceof List<?> l) o.put("recent", l.stream().filter(x -> visible.test(((Map<String, Object>) x).get("instance_id"))).toList());
                yield o;
            }
        };
        return JsonLite.write(out).getBytes(StandardCharsets.UTF_8);
    }

    private static String person(User u) { return u.displayName() == null || u.displayName().isBlank() ? u.username() : u.displayName(); }

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
            // improvement list 12 Nov 2026 (item 10): say whether it was never set up or set up and not answering
            if (!configured())
                throw new OoaException("DOCS.NOT_SET_UP", "Document Automation is not set up yet: no address for its DocFlow service has been entered",
                        "An administrator starts DocFlow and enters its address (" + DEFAULT_URL + " when it runs on this server) and token in Administration > Settings > Document Automation.");
            throw new OoaException("DOCS.UNAVAILABLE", "The Document Automation service at " + baseUrl() + " is not answering",
                    "Check that DocFlow is running (on Windows: the OneOffice DocFlow service, or windows\\start.bat), and the address in Administration > Settings > Document Automation.", true, Map.of());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OoaException("DOCS.UNAVAILABLE", "Interrupted", "Try again; if it keeps happening, an administrator can look in the server log.");
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
    private byte[] stampBotDeliveries(User u, byte[] in) {
        Object parsed;
        try { parsed = JsonLite.parse(new String(in, StandardCharsets.UTF_8)); } catch (IllegalArgumentException e) { return in; }
        if (!(parsed instanceof Map<?, ?> flow)) return in;
        boolean changed = false;
        // an email inbox reads the mailbox's vault secret as the person who sets it up (who must be its consumer)
        if (flow.get("intake") instanceof Map<?, ?> intake && intake.get("email") instanceof Map<?, ?> raw && !raw.isEmpty()) {
            Map<String, Object> email = (Map<String, Object>) raw;
            if ("email".equals(email.get("kind")) && email.get("host") instanceof String host && !host.isBlank()) ctx.checkHost(host, "The mail server");
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
            ctx.require(u, "runs.start", "start agents from a document process");
            ctx.requirePathAccess(u.username(), u.role(), bot, "run", "start this agent");
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
    private void afterReview(User u, String method, String route, byte[] in) {
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
    public boolean configured() {
        if (ctx.tenant() != null) return env.apply("OOA_DOCFLOW_URL") != null;
        return !String.valueOf(ctx.setting("docflow_url")).isBlank() || !String.valueOf(ctx.setting("docflow_token_ref")).isBlank()
                || !blank(env.apply("OOA_DOCFLOW_URL")) || !DocFlowConfig.url(ctx.dataDir()).isEmpty();
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }

    /**
     * Keeps the Task inbox in step with DocFlow's review queue: a task for each document waiting for review (due at the
     * process's review target), and tasks of documents no longer waiting (reviewed elsewhere, read again) are closed.
     * Returns how many tasks it opened and closed.
     */
    @SuppressWarnings("unchecked")
    public int[] syncReviews() {
        if (!configured()) return new int[] {0, 0};
        HttpResponse<byte[]> r = send("GET", "df/review", null, null, null, "Command Center");
        if (r.statusCode() != 200) return new int[] {0, 0};
        List<Object> queue = (List<Object>) JsonLite.parse(new String(r.body(), StandardCharsets.UTF_8));
        Map<String, List<Map<String, Object>>> open = openTasks();
        syncFolders = null;
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
            data.put("folder", folderOfProcess(String.valueOf(d.get("flowId"))));
            data.put("document_type", d.get("doctypeName"));
            data.put("link", "/documents/#/review/" + doc);
            ctx.db().update("INSERT INTO human_tasks (id, kind, title, description, data, assignee, status, created, due) VALUES (?,?,?,?,?,?,?,?,?)",
                    id("task"), TASK_KIND, "Review " + d.get("file_name") + " (" + d.get("flowName") + ")", d.get("doctypeName") + " · " + why,
                    JsonLite.write(data), "perm:docs.review", "open", created, created.plus(Duration.ofMinutes((long) (sla * 60))));
            opened++;
        }
        for (var e : open.entrySet())
            if (!waiting.contains(e.getKey())) for (Map<String, Object> t : e.getValue()) { close((String) t.get("id"), "done", "docflow"); closed++; }
        return new int[] {opened, closed};
    }

    private Map<String, String> syncFolders;   // the processes' folders during one review sync

    private String folderOfProcess(String id) {
        if (syncFolders == null) syncFolders = processFolders();
        return syncFolders.getOrDefault(id, "");
    }

    @SuppressWarnings("unchecked")
    private Map<String, List<Map<String, Object>>> openTasks() {
        Map<String, List<Map<String, Object>>> out = new HashMap<>();
        for (Map<String, Object> t : ctx.db().query("SELECT id, data FROM human_tasks WHERE kind = ? AND status IN ('open', 'claimed')", TASK_KIND)) {
            Object data = t.get("data") == null ? null : JsonLite.parse((String) t.get("data"));
            if (data instanceof Map<?, ?> m && m.get("document_id") instanceof String doc) out.computeIfAbsent(doc, k -> new ArrayList<>()).add(t);
        }
        return out;
    }

    private void close(String taskId, String outcome, String by) {
        ctx.db().update("UPDATE human_tasks SET status = 'completed', outcome = ?, completed = ?, completed_by = ?, claimed_by = COALESCE(claimed_by, ?) WHERE id = ? AND status IN ('open', 'claimed')",
                outcome, now(), by, by, taskId);
    }

    // ------------------------------------------------------------------ email inbox

    /** The attachment types a process reads from email (as Add documents accepts). */
    static final Pattern READABLE = Pattern.compile("(?i).+\\.(pdf|png|jpe?g|tiff?)$");

    /**
     * Reads the email inbox of every live process that has one, at most every interval_seconds (60 by default): new
     * messages passing its from / subject filters, and their PDF and image attachments go to the process. The first
     * check only marks where the inbox is (older mail is not read). Returns how many documents it sent.
     */
    @SuppressWarnings("unchecked")
    public int pollMail() {
        if (!configured()) return 0;
        HttpResponse<byte[]> r = send("GET", "df/flows", null, null, null, "Command Center");
        if (r.statusCode() != 200) return 0;
        int sent = 0;
        for (Object o : (List<Object>) JsonLite.parse(new String(r.body(), StandardCharsets.UTF_8))) {
            Map<String, Object> flow = (Map<String, Object>) o;
            Map<String, Object> email = flow.get("intake") instanceof Map<?, ?> in && in.get("email") instanceof Map<?, ?> e ? (Map<String, Object>) e : Map.of();
            if (!Boolean.TRUE.equals(email.get("enabled")) || !"live".equals(flow.get("status"))) continue;
            String id = String.valueOf(flow.get("id"));
            Map<String, Object> row = ctx.db().one("SELECT * FROM docflow_mail WHERE process_id = ?", id);
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
            Instant now = now();
            String err = error == null ? null : error.length() > 1000 ? error.substring(0, 1000) : error;
            if (row == null) ctx.db().update("INSERT INTO docflow_mail (process_id, mail_cursor, last_check, last_error, documents) VALUES (?,?,?,?,?)", id, cursor, now, err, docs);
            else ctx.db().update("UPDATE docflow_mail SET mail_cursor = ?, last_check = ?, last_error = ?, documents = COALESCE(documents, 0) + ? WHERE process_id = ?", cursor, now, err, docs, id);
            sent += docs;
        }
        return sent;
    }

    public interface Mailboxes { MailSource open(Map<String, Object> email) throws IOException; }
    /** Opens an inbox's mailbox (tests replace it). */
    public Mailboxes mails = this::mailSource;

    /** What went wrong with an inbox, in words a person can act on. */
    public static String problem(Exception e, Map<String, Object> email) {
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
            return ctx.graphMailbox(String.valueOf(email.get("tenant_id")), String.valueOf(email.get("client_id")),
                    secret(by, String.valueOf(email.get("client_secret_ref"))), (String) email.get("mailbox"), (String) email.getOrDefault("mail_folder", "inbox"));
        }
        String host = String.valueOf(email.getOrDefault("host", ""));
        ctx.checkHost(host, "The mail server");
        return ctx.imapMailbox(host, email.get("port") instanceof Number p ? p.intValue() : 993, !Boolean.FALSE.equals(email.get("ssl")),
                String.valueOf(email.get("username")), secret(by, String.valueOf(email.get("password_ref"))), String.valueOf(email.getOrDefault("mail_folder", "INBOX")), 30_000);
    }

    /** A mailbox secret, as the person who set the inbox up: they must still be active and a consumer of its vault folder. */
    private String secret(String by, String ref) {
        Map<String, Object> u = ctx.db().one("SELECT role FROM users WHERE username = ? AND enabled = TRUE", by);
        if (u == null) throw new OoaException("VAULT.DENIED", "The person who set up this inbox (" + by + ") is not an active user", "Open the process and save its email inbox again.");
        vaultAccess(by, (String) u.get("role"), ref);
        return ctx.systemCredential(ref);
    }

    private void vaultAccess(String by, String role, String ref) {
        String[] p = ref.split("/");
        if (p.length != 3 || !ctx.consumesVaultFolder(by, role, p[0]))
            throw new OoaException("VAULT.DENIED", "'" + by + "' may not use vault folder '" + (p.length > 0 ? p[0] : ref) + "'", "Ask the vault folder's owner to add you as a consumer.");
    }

    private void upload(String processId, MailSource.Attachment a, Map<String, Object> msg) {
        String boundary = "ooa" + UUID.randomUUID().toString().replace("-", "");
        HttpResponse<byte[]> r = send("POST", "df/flows/" + java.net.URLEncoder.encode(processId, StandardCharsets.UTF_8) + "/documents", "source=email&requireLive=true",
                multipart(boundary, a.name(), a.contentType(), a.data()), Map.of("Content-Type", "multipart/form-data; boundary=" + boundary),
                "Email from " + String.valueOf(msg.getOrDefault("from", "")).replaceAll("[\\r\\n]", " "));
        if (r.statusCode() >= 300) throw new OoaException("DOCS.EMAIL", "DocFlow refused " + a.name() + " (HTTP " + r.statusCode() + ")", "Check that the Document Automation service is running and set up (Administration > Settings > Document Automation); the message has its answer.");
    }

    /** One file as the multipart form Add documents sends (field "files"). */
    static byte[] multipart(String boundary, String fileName, String contentType, byte[] data) {
        String name = fileName.replaceAll("[\\r\\n\"]", "_");
        byte[] head = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"files\"; filename=\"" + name + "\"\r\nContent-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[head.length + data.length + tail.length];
        System.arraycopy(head, 0, body, 0, head.length);
        System.arraycopy(data, 0, body, head.length, data.length);
        System.arraycopy(tail, 0, body, head.length + data.length, tail.length);
        return body;
    }

    // ------------------------------------------------------------------ from a bot

    /**
     * An agent's "Send document to process": the runner sends the file for one of its running runs; it goes to the
     * process (by id or name, which must be live) as the person the run belongs to, who must be able to see Document
     * Automation. With wait=true the answer has the document's status and fields.
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> fromBot(HttpExchange ex, Map<String, Object> device, Map<String, String> q) throws IOException {
        Map<String, Object> run = ctx.runOfDevice(device, q.get("run_id"));
        if (!"running".equals(run.get("status"))) throw new OoaException("CR.FORBIDDEN", "Only running agents can send documents", "Use this from a step of a running agent.");
        String by = (String) (run.get("run_as") != null ? run.get("run_as") : run.get("requested_by"));
        Map<String, Object> user = ctx.db().one("SELECT id, username, role, display_name FROM users WHERE username = ? AND enabled = TRUE", by);
        if (user == null) throw new OoaException("CR.FORBIDDEN", "The run's user " + by + " is not active", "Ask an administrator to enable the run's user, or run the agent as another user.");
        ctx.require(new User((String) user.get("id"), by, (String) user.get("role"), (String) user.get("display_name"), false), "docs.view", "send documents to Document Automation");
        String process = String.valueOf(q.getOrDefault("process", "")).trim();
        HttpResponse<byte[]> flows = send("GET", "df/flows", null, null, null, "Command Center");
        Map<String, Object> flow = flows.statusCode() != 200 ? null : ((List<Object>) JsonLite.parse(new String(flows.body(), StandardCharsets.UTF_8))).stream()
                .map(o -> (Map<String, Object>) o).filter(f -> process.equals(f.get("id")) || process.equalsIgnoreCase(String.valueOf(f.get("name")))).findFirst().orElse(null);
        if (flow == null) throw new OoaException("CR.NOT_FOUND", "No Document Automation process '" + process + "'", "Use the process's name or id as shown in Document Automation.");
        need(new User((String) user.get("id"), by, (String) user.get("role"), (String) user.get("display_name"), false), flow.get("folder"), "run",
                ctx.folderEntries(), "add documents");
        boolean wait = "true".equals(q.get("wait"));
        String id = String.valueOf(flow.get("id"));
        Map<String, String> headers = Map.of("Content-Type", String.valueOf(ex.getRequestHeaders().getFirst("Content-Type")));
        String person = "Agent " + String.valueOf(run.get("bot_path")).replace(".flow.json", "") + " (" + by + ")";
        HttpResponse<byte[]> r = send("POST", "df/flows/" + java.net.URLEncoder.encode(id, StandardCharsets.UTF_8) + "/documents", "source=bot&requireLive=true&wait=" + wait, body(ex), headers, person);
        Object parsed = JsonLite.parse(new String(r.body(), StandardCharsets.UTF_8));
        if (r.statusCode() >= 300) {
            Object msg = parsed instanceof Map<?, ?> m ? (m.get("message") != null ? m.get("message") : m.get("detail")) : null;
            throw new OoaException("DOCS.REFUSED", "Document Automation refused the document: " + (msg == null ? "HTTP " + r.statusCode() : msg), "Check that the Document Automation service is running and set up (Administration > Settings > Document Automation); the message has its answer.");
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

    // ------------------------------------------------------------------ an agent picks up results (D1)

    /** Statuses of a document that is read and needs nothing more: read and checked, approved, or rejected. */
    static final Set<String> FINISHED = Set.of("success", "validated", "invalid");
    /** Statuses of a document still being read. */
    static final Set<String> READING = Set.of("received", "queued", "pending", "processing");
    private static final Pattern DOC_ID = Pattern.compile("[A-Za-z0-9_-]{1,80}");

    /**
     * Document Automation's "Get document result", "List finished documents" and "Mark collected" (command review D1,
     * 12 Nov 2026), on /api/agent/docflow/results for a running agent, as the run's user with docs.view and view access
     * to the process's folder. op get: one document's status, fields, tables and confidence by id; op list: a process's
     * finished documents not yet collected by the collector; op collect: marks documents collected by the collector (kept
     * here in docflow_collected, as DocFlow keeps no such mark), so agents that share a collector name never take one twice.
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> resultsForBot(HttpExchange ex, Map<String, Object> device, Map<String, String> q) throws IOException {
        Map<String, Object> b = ctx.jsonBody(ex);
        Map<String, Object> run = ctx.runOfDevice(device, String.valueOf(b.get("run_id")));
        if (!"running".equals(run.get("status"))) throw new OoaException("CR.FORBIDDEN", "Only running agents can read documents", "Use this from a step of a running agent.");
        User user = runUser(run);
        ctx.require(user, "docs.view", "read Document Automation results");
        String collector = b.get("collector") instanceof String c && !c.isBlank() ? c.strip() : "default";
        if (collector.length() > 120) throw new OoaException("CR.BAD_INPUT", "The collector name is longer than 120 characters");
        Map<String, Map<String, String>> all = ctx.folderEntries();
        String op = String.valueOf(b.getOrDefault("op", ""));
        switch (op) {
            case "get" -> {
                String id = docId(b.get("document_id"));
                Map<String, Object> d = docOf(id);
                need(user, processFolders().getOrDefault(String.valueOf(d.get("flowId")), ""), "view", all, "see this document");
                Map<String, Object> out = result(d);
                out.put("collected", ctx.db().one("SELECT 1 AS x FROM docflow_collected WHERE document_id = ? AND collector = ?", id, collector) != null);
                return out;
            }
            case "list" -> {
                String process = String.valueOf(b.getOrDefault("process", "")).trim();
                Map<String, Object> flow = flowNamed(process);
                need(user, flow.get("folder"), "view", all, "see this process");
                int limit = b.get("limit") instanceof Number n ? Math.max(1, Math.min(500, n.intValue())) : 100;
                Set<String> wanted = new HashSet<>();
                if (b.get("statuses") instanceof List<?> l) for (Object o : l) wanted.add(String.valueOf(o));
                if (wanted.isEmpty()) wanted.addAll(FINISHED);
                boolean includeCollected = Boolean.TRUE.equals(b.get("include_collected"));
                Set<String> taken = new HashSet<>();
                for (Map<String, Object> r : ctx.db().query("SELECT document_id FROM docflow_collected WHERE process_id = ? AND collector = ?", flow.get("id"), collector))
                    taken.add(String.valueOf(r.get("document_id")));
                HttpResponse<byte[]> r = send("GET", "df/flows/" + java.net.URLEncoder.encode(String.valueOf(flow.get("id")), StandardCharsets.UTF_8) + "/documents",
                        "limit=500", null, null, "Command Center");
                if (r.statusCode() != 200) throw new OoaException("DOCS.REFUSED", "Document Automation did not list the documents: HTTP " + r.statusCode(), "Check that the Document Automation service is running and set up (Administration > Settings > Document Automation); the message has its answer.");
                List<Object> rows = new ArrayList<>();
                for (Object o : (List<Object>) JsonLite.parse(new String(r.body(), StandardCharsets.UTF_8))) {
                    Map<String, Object> d = (Map<String, Object>) o;
                    String id = String.valueOf(d.get("id"));
                    if (!wanted.contains(String.valueOf(d.get("status"))) || (!includeCollected && taken.contains(id))) continue;
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("document_id", id);
                    row.put("file_name", d.get("file_name"));
                    row.put("status", d.get("status"));
                    row.put("doc_type", d.get("doctype_id"));
                    row.put("created_at", d.get("created_at"));
                    row.put("validated_by", d.get("validated_by"));
                    row.put("collected", taken.contains(id));
                    rows.add(row);
                    if (rows.size() >= limit) break;
                }
                return new LinkedHashMap<>(Map.of("process", flow.get("name"), "documents", rows));
            }
            case "collect" -> {
                List<Object> ids = b.get("document_ids") instanceof List<?> l ? (List<Object>) l : List.of();
                if (ids.isEmpty()) throw new OoaException("CR.BAD_INPUT", "Give the ids of the documents to mark collected");
                if (ids.size() > 500) throw new OoaException("CR.BAD_INPUT", "Mark at most 500 documents at a time");
                Map<String, String> folders = processFolders();
                int marked = 0, already = 0;
                for (Object o : ids) {
                    String id = docId(o);
                    Map<String, Object> d = docOf(id);
                    String flowId = String.valueOf(d.get("flowId"));
                    need(user, folders.getOrDefault(flowId, ""), "view", all, "see this document");
                    if (ctx.db().one("SELECT 1 AS x FROM docflow_collected WHERE document_id = ? AND collector = ?", id, collector) != null) { already++; continue; }
                    ctx.db().update("INSERT INTO docflow_collected (document_id, collector, process_id, run_id, collected_by, collected) VALUES (?,?,?,?,?,?)",
                            id, collector, flowId, run.get("id"), user.username(), Instant.now());
                    marked++;
                }
                ctx.audit(user.username(), "docflow.collect", "docflow_document", null,
                        Map.of("collector", collector, "marked", marked, "already", already, "run_id", String.valueOf(run.get("id"))), "success", null);
                return new LinkedHashMap<>(Map.of("marked", marked, "already_collected", already));
            }
            default -> throw new OoaException("CR.BAD_INPUT", "Unknown request '" + op + "'");
        }
    }

    /** The user a run acts as, when still active. */
    private User runUser(Map<String, Object> run) {
        String by = (String) (run.get("run_as") != null ? run.get("run_as") : run.get("requested_by"));
        Map<String, Object> user = ctx.db().one("SELECT id, username, role, display_name FROM users WHERE username = ? AND enabled = TRUE", by);
        if (user == null) throw new OoaException("CR.FORBIDDEN", "The run's user " + by + " is not active", "Ask an administrator to enable the run's user, or run the agent as another user.");
        return new User((String) user.get("id"), by, (String) user.get("role"), (String) user.get("display_name"), false);
    }

    private static String docId(Object o) {
        String id = o == null ? "" : String.valueOf(o).strip();
        if (!DOC_ID.matcher(id).matches()) throw new OoaException("CR.BAD_INPUT", "'" + id + "' is not a document id", "Use the document id that Send document to process gave.");
        return id;
    }

    /** A process by its name (any case) or id. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> flowNamed(String process) {
        HttpResponse<byte[]> flows = send("GET", "df/flows", null, null, null, "Command Center");
        Map<String, Object> flow = flows.statusCode() != 200 ? null : ((List<Object>) JsonLite.parse(new String(flows.body(), StandardCharsets.UTF_8))).stream()
                .map(o -> (Map<String, Object>) o).filter(f -> process.equals(f.get("id")) || process.equalsIgnoreCase(String.valueOf(f.get("name")))).findFirst().orElse(null);
        if (flow == null) throw new OoaException("CR.NOT_FOUND", "No Document Automation process '" + process + "'", "Use the process's name or id as shown in Document Automation.");
        return flow;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> docOf(String id) {
        HttpResponse<byte[]> r = send("GET", "df/documents/" + id, null, null, null, "Command Center");
        if (r.statusCode() == 404) throw new OoaException("CR.NOT_FOUND", "No document '" + id + "' in Document Automation", "Use the document id that Send document to process gave.");
        if (r.statusCode() != 200 || !(JsonLite.parse(new String(r.body(), StandardCharsets.UTF_8)) instanceof Map<?, ?> m))
            throw new OoaException("DOCS.REFUSED", "Document Automation did not give the document: HTTP " + r.statusCode(), "Check that the Document Automation service is running and set up (Administration > Settings > Document Automation); the message has its answer.");
        return (Map<String, Object>) m;
    }

    /**
     * What an agent gets for a document: status, whether it is read and whether it waits for a review, the fields
     * (single values), the tables (fields whose value is a list of rows, such as line items) and each field's confidence.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> result(Map<String, Object> d) {
        String st = String.valueOf(d.get("status"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("document_id", d.get("id"));
        out.put("status", st);
        out.put("done", !READING.contains(st));
        out.put("needs_review", "validation".equals(st));
        out.put("reviewed", "validated".equals(st) || "invalid".equals(st));
        out.put("process_id", d.get("flowId"));
        out.put("file_name", d.get("file_name"));
        out.put("doc_type", d.get("doctype_id"));
        out.put("validated_by", d.get("validated_by"));
        Map<String, Object> fields = new LinkedHashMap<>(), tables = new LinkedHashMap<>();
        if (d.get("fields") instanceof Map<?, ?> f)
            ((Map<String, Object>) f).forEach((k, v) -> { if (v instanceof List<?> l && !l.isEmpty() && l.stream().allMatch(x -> x instanceof Map)) tables.put(k, v); else fields.put(k, v); });
        out.put("fields", fields);
        out.put("tables", tables);
        out.put("confidence", d.get("confidence") instanceof Map<?, ?> c ? c : Map.of());
        if (d.get("error") != null) out.put("error", d.get("error"));
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
        fromDocFlow(ex);
        Map<String, Object> b = ctx.jsonBody(ex);
        String bot = String.valueOf(b.getOrDefault("bot", ""));
        String by = String.valueOf(b.getOrDefault("requestedBy", ""));
        Map<String, Object> user = ctx.db().one("SELECT username, role FROM users WHERE username = ? AND enabled = TRUE", by);
        if (user == null) throw new OoaException("CR.NOT_FOUND", "The person who set up this delivery (" + by + ") is not an active user", "Open the process and save its delivery again.");
        Map<String, Object> doc = b.get("input") instanceof Map<?, ?> in ? (Map<String, Object>) in : Map.of();
        Map<String, Object> flow = (Map<String, Object>) ctx.agentContent(bot, "production").get("flow");
        Map<String, Object> named = new LinkedHashMap<>();
        named.put("document", doc);
        named.put("document_id", doc.get("documentId"));
        named.put("process", doc.get("flowName"));
        named.put("status", doc.get("status"));
        named.put("file_name", doc.get("fileName"));
        Set<String> declared = ctx.declaredInputs(flow);
        Map<String, Object> inputs = new LinkedHashMap<>();
        documentValues(named, doc.get("fields")).forEach((k, v) -> { if (declared.contains(k)) inputs.put(k, v); });
        String pool = b.get("pool") instanceof String s && !s.isBlank() ? s : null;
        return ctx.startRun(by, (String) user.get("role"), bot, null, pool, inputs, "docflow:" + doc.getOrDefault("flowId", ""), ip, 0, null, pool == null ? by : null, null);
    }

    // ------------------------------------------------------------------ which DocFlow

    /** The DocFlow this release is built and tested with: contract, pinned, minimum, ref (docflow-compat.properties). */
    public static final Properties COMPAT = new Properties();
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
    public Map<String, Object> version() {
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
     * What an agent started for a document may get as inputs (it gets those it declares): the named values (those that
     * are not null) and each field by its name. A field never hides a named value (a field called status, say).
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> documentValues(Map<String, Object> named, Object fields) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (fields instanceof Map<?, ?> f) ((Map<String, Object>) f).forEach((k, v) -> { if (v != null && !named.containsKey(k)) out.put(k, v); });
        named.forEach((k, v) -> { if (v != null) out.put(k, v); });
        return out;
    }

    /**
     * How a DocFlow version compares with this release: compatible (from the minimum up to the tested version), newer
     * (same major version, newer than tested), too_old, other_contract (another major version), or unknown.
     */
    public static String compare(String version, String minimum, String pinned) {
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

    /**
     * A call back from DocFlow (Start a bot, document events): it must carry the service token and be for this tenant
     * (X-OOA-Tenant, URL-encoded as the Command Center sends it).
     */
    private void fromDocFlow(HttpExchange ex) {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        String token = token();
        if (token.isEmpty() || auth == null || !MessageDigest.isEqual(auth.getBytes(StandardCharsets.UTF_8), ("Bearer " + token).getBytes(StandardCharsets.UTF_8)))
            throw new OoaException("CR.UNAUTHENTICATED", "Not the Document Automation service", "Check the Document Automation token in Administration > Settings > Document Automation.");
        String t = ex.getRequestHeaders().getFirst("X-OOA-Tenant");
        if (t == null || !tenant().equals(java.net.URLDecoder.decode(t, StandardCharsets.UTF_8))) throw new OoaException("CR.FORBIDDEN", "Another tenant's process", "Use a process of your own organisation.");
    }

    // ------------------------------------------------------------------ document events (triggers "On Document Automation")

    /** A document event DocFlow reports at /api/docflow-callback/events (service token, tenant): starts the matching triggers' agents. */
    Map<String, Object> event(HttpExchange ex, String ip) throws IOException {
        fromDocFlow(ex);
        return onEvent(ctx.jsonBody(ex), ip);
    }

    /**
     * Starts the agent of every enabled trigger of the event's kind whose process and document type match. The trigger's
     * owner must still be active, have docs.view and may see the process's folder; otherwise the trigger is skipped, which
     * the audit trail and the trigger's last problem say. The agent gets the inputs it declares among document_id,
     * process, process_id, status, event, file_name, doc_type, each field by its name, and trigger_event (everything).
     * Returns how many agents started and how many matching triggers were skipped.
     */
    public Map<String, Object> onEvent(Map<String, Object> e, String ip) {
        String name = String.valueOf(e.getOrDefault("event", ""));
        String kind = DOCUMENT_EVENTS.get(name);
        if (kind == null) throw new OoaException("CR.BAD_INPUT", "Unknown document event '" + name + "'; use one of " + String.join(", ", DOCUMENT_EVENTS.keySet()));
        List<Map<String, Object>> matching = new ArrayList<>();
        for (Map<String, Object> t : ctx.db().query("SELECT * FROM triggers WHERE enabled = TRUE AND kind = ?", kind))
            if (documentMatches(ctx.triggerConfig(t), e)) matching.add(t);
        int started = 0, skipped = 0;
        if (!matching.isEmpty()) {
            String folder = processFolders().getOrDefault(String.valueOf(e.get("process_id")), "");
            Map<String, Map<String, String>> all = ctx.folderEntries();
            Map<String, Object> named = new LinkedHashMap<>();
            for (String k : List.of("document_id", "process", "process_id", "status", "event", "file_name", "doc_type")) named.put(k, e.get(k));
            for (Map<String, Object> t : matching) {
                String id = (String) t.get("id");
                Map<String, Object> owner = ctx.db().one("SELECT id, username, role, display_name FROM users WHERE username = ? AND enabled = TRUE", t.get("created_by"));
                User u = owner == null ? null : new User((String) owner.get("id"), (String) owner.get("username"), (String) owner.get("role"), (String) owner.get("display_name"), false);
                String why = u == null ? "The trigger's owner " + t.get("created_by") + " is not an active user"
                        : !ctx.can(u, "docs.view") ? "The trigger's owner " + u.username() + " may not see Document Automation (docs.view)"
                        : !ctx.atLeast(folderAccess(u, folder, all), "view") ? "The trigger's owner " + u.username() + " may not see the process's folder '" + folderKey(folder) + "'"
                        : null;
                Map<String, Object> audited = new LinkedHashMap<>(Map.of("event", name, "process", String.valueOf(e.get("process_id")), "document", String.valueOf(e.get("document_id"))));
                if (why != null) {
                    audited.put("reason", why);
                    ctx.audit("trigger:" + id + " for " + t.get("created_by"), "trigger.fire", "trigger", id, audited, "denied", ip);
                    ctx.db().update("UPDATE triggers SET last_error = ? WHERE id = ?", why, id);
                    skipped++;
                    continue;
                }
                Map<String, Object> ev = new LinkedHashMap<>(e);
                ev.put("kind", kind);
                ev.put("trigger_id", id);
                ev.put("trigger_name", t.get("name"));
                ev.put("time", Instant.now().toString());
                Map<String, Object> in = documentValues(named, e.get("fields"));
                in.put("trigger_event", ev);
                try { ctx.fireTrigger(t, in, "trigger:" + id, ip); started++; }
                catch (OoaException x) {
                    audited.put("error", String.valueOf(x.getMessage()));
                    ctx.audit("trigger:" + id + " for " + t.get("created_by"), "trigger.fire", "trigger", id, audited, "failed", ip);
                    skipped++;
                }
            }
        }
        return Map.of("event", name, "started", started, "skipped", skipped);
    }

    /** DocFlow's event names (POST /api/docflow-callback/events) and the trigger kind each starts ("On Document Automation"). */
    public static final Map<String, String> DOCUMENT_EVENTS = new LinkedHashMap<>();
    /** The label of each of those trigger kinds. */
    static final Map<String, String> EVENT_LABELS = new LinkedHashMap<>();
    static {
        for (String[] e : new String[][] {{"processed", "doc_processed", "Document processed"}, {"needs_review", "doc_needs_review", "Document needs review"},
                {"approved", "doc_approved", "Review approved"}, {"rejected", "doc_rejected", "Review rejected"},
                {"delivery_failed", "doc_delivery_failed", "Delivery failed"}, {"review_overdue", "doc_review_overdue", "Review overdue"}}) {
            DOCUMENT_EVENTS.put(e[0], e[1]);
            EVENT_LABELS.put(e[1], e[2]);
        }
    }

    /**
     * Whether a document event is one a Document Automation trigger waits for: its process (by id, or name in any case;
     * none = any process) and its document type (by name or id, in any case; none = any type).
     */
    public static boolean documentMatches(Map<String, Object> config, Map<String, Object> event) {
        String process = str(config, "process"), type = str(config, "doc_type");
        if (process != null && !process.equals(String.valueOf(event.get("process_id"))) && !process.equalsIgnoreCase(String.valueOf(event.get("process")))) return false;
        return type == null || event.get("doc_type") != null && type.equalsIgnoreCase(String.valueOf(event.get("doc_type")).trim());
    }

    /** A document trigger's settings as stored: the process and the document type, each optional and at most 200 characters. */
    static Map<String, Object> triggerConfig(Map<String, Object> request) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : List.of("process", "doc_type")) {
            String v = str(request, k);
            if (v != null && v.length() > 200) throw new OoaException("CR.BAD_TRIGGER", k + " has at most 200 characters", "Shorten the value the message names to 200 characters or fewer.");
            if (v != null) out.put(k, v);
        }
        return out;
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    // ------------------------------------------------------------------ where DocFlow is

    /** Uploads are forwarded as they came (multipart), up to Settings > Largest upload. */
    private byte[] body(HttpExchange ex) throws IOException { return ctx.body(ex); }

    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MILLIS); }

    private static String id(String prefix) { return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16); }

    private String tenant() { return ctx.tenant() == null ? "default" : ctx.tenant(); }

    /**
     * DocFlow's address. On premises Settings > Document Automation wins; when it is empty, OOA_DOCFLOW_URL (set by the
     * platform's containers), then config/ooa-docflow.properties (written by the Windows installer), else 127.0.0.1:8085.
     */
    public String baseUrl() {
        String u = ctx.tenant() != null ? env.apply("OOA_DOCFLOW_URL") : String.valueOf(ctx.setting("docflow_url")).trim();
        if (ctx.tenant() == null && blank(u)) u = env.apply("OOA_DOCFLOW_URL");
        if (ctx.tenant() == null && blank(u)) u = DocFlowConfig.url(ctx.dataDir());
        return (blank(u) ? DEFAULT_URL : u.trim()).replaceAll("/+$", "");
    }

    /**
     * The service token: on premises the vault credential Settings names, else OOA_DOCFLOW_TOKEN (as in SaaS), else the
     * token the Windows installer stored (encrypted) in config/ooa-docflow.properties.
     */
    private String token() {
        if (ctx.tenant() == null) {
            String ref = String.valueOf(ctx.setting("docflow_token_ref")).trim();
            if (!ref.isEmpty()) return ctx.systemCredential(ref);
        }
        String t = env.apply("OOA_DOCFLOW_TOKEN");
        if (!blank(t) || ctx.tenant() != null) return t == null ? "" : t.trim();
        return DocFlowConfig.token(ctx.dataDir(), ctx.vault());
    }

    /** The environment (tests replace it). */
    public static java.util.function.Function<String, String> env = System::getenv;
}
