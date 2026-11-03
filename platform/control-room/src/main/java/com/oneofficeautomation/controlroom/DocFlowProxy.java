package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.sdk.OoaException;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Set;
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
 */
final class DocFlowProxy {
    static final String DEFAULT_URL = "http://127.0.0.1:8085";

    /** The DocFlow routes the screens use (relative to /api/docs/v1/); anything else is not forwarded. */
    private static final Pattern ROUTES = Pattern.compile("^(df/[A-Za-z0-9._~/-]+|providers|costs|usage|admin/settings|admin/providers/[a-z0-9-]+/test"
            + "|documents/[A-Za-z0-9_-]+/(result|page/\\d{1,4}\\.png|file))$");
    private static final Pattern REVIEW = Pattern.compile("^df/documents/[A-Za-z0-9_-]+/(review|reclassify)$");
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
        String raw = ex.getRequestURI().getRawQuery();
        URI target = URI.create(baseUrl() + "/api/docs/v1/" + route + (raw == null ? "" : "?" + raw));
        HttpRequest.Builder rq = HttpRequest.newBuilder(target).timeout(Duration.ofMinutes(5))
                .header("X-OOA-Tenant", cr.tenant == null ? "default" : cr.tenant)
                .header("X-OOA-User", u.displayName() == null || u.displayName().isBlank() ? u.username() : u.displayName());
        String token = token();
        if (!token.isEmpty()) rq.header("Authorization", "Bearer " + token);
        for (String h : REQUEST_HEADERS) { String v = ex.getRequestHeaders().getFirst(h); if (v != null) rq.header(h, v); }
        if (method.equals("GET") || method.equals("DELETE")) rq.method(method, HttpRequest.BodyPublishers.noBody());
        else rq.method(method, HttpRequest.BodyPublishers.ofByteArray(body(ex)));

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

    /** Uploads are forwarded as they came (multipart), up to Settings > Largest upload. */
    private static byte[] body(HttpExchange ex) throws IOException {
        int limit = ControlRoomServer.maxBody.getAsInt();
        byte[] b = ex.getRequestBody().readNBytes(limit + 1);
        if (b.length > limit) throw new OoaException("CR.TOO_LARGE", "The upload is larger than the Largest upload setting allows", "Raise Settings > Largest upload, or send fewer files at once");
        return b;
    }

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
