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
        HttpResponse<byte[]> r = send(method, route, ex.getRequestURI().getRawQuery(), in, ex.getRequestHeaders(), person(u));
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
    private HttpResponse<byte[]> send(String method, String route, String rawQuery, byte[] body, com.sun.net.httpserver.Headers headers, String person) {
        HttpRequest.Builder rq = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/docs/v1/" + route + (rawQuery == null ? "" : "?" + rawQuery)))
                .timeout(Duration.ofMinutes(5)).header("X-OOA-Tenant", tenant()).header("X-OOA-User", person);
        String token = token();
        if (!token.isEmpty()) rq.header("Authorization", "Bearer " + token);
        if (headers != null) for (String h : REQUEST_HEADERS) { String v = headers.getFirst(h); if (v != null) rq.header(h, v); }
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
        if (!(parsed instanceof Map<?, ?> flow) || !(flow.get("deliveries") instanceof List<?> list)) return in;
        boolean changed = false;
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
