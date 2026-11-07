package com.oneofficeautomation.modules.docauto;

import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import com.oneofficeautomation.controlroom.ControlRoom.User;
import com.oneofficeautomation.controlroom.module.ModuleContext;

/**
 * Document Automation's tools for AI assistants (MCP, on POST /api/mcp beside the agents): list processes, send a
 * document, ask its status and its result, list the documents waiting for the person's review, and approve or reject
 * one. Every call goes through DocFlowProxy as the signed-in person, so the docs.* permissions and the processes' folder
 * permissions apply as on the screens, and each call is in the Audit Trail (mcp.tool). Nothing waits for the reading:
 * documents_send answers with the document's id at once, documents_status says when it is read, documents_result gives
 * the fields. Settings > AI assistants (MCP) > Document Automation tools turns them off (mcp_documents).
 */
final class McpDocuments implements ModuleContext.McpTools {
    static final String LIST = "documents_list_processes", SEND = "documents_send", STATUS = "documents_status", RESULT = "documents_result",
            REVIEWS = "documents_my_reviews", REVIEW = "documents_review";
    static final Set<String> NAMES = Set.of(LIST, SEND, STATUS, RESULT, REVIEWS, REVIEW);
    /** Statuses of a document that is still being read. */
    static final Set<String> READING = Set.of("received", "queued", "pending", "processing");

    private final ModuleContext ctx;
    private final DocFlowProxy docflow;

    McpDocuments(ModuleContext ctx, DocFlowProxy docflow) { this.ctx = ctx; this.docflow = docflow; }

    public Set<String> names() { return NAMES; }

    public String instructions() {
        return "The documents_ tools use Document Automation as the signed-in user: documents_send returns a document id at once, "
                + "then ask documents_status until it is read and documents_result for its fields.";
    }

    /** The tools this person may use (none when Document Automation is not set up here or the setting is off). */
    public List<Map<String, Object>> tools(User u) {
        if (!on() || !ctx.can(u, "docs.view")) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(tool(LIST, "List document processes", "The Document Automation processes you may see: id, name, folder, status (live or draft) and document types.", Map.of(), List.of()));
        if (ctx.can(u, "docs.manage")) out.add(tool(SEND, "Send a document to a process",
                "Sends one document (PDF, PNG, JPG or TIFF) to a live process and answers at once with its document id; it is read in the background. "
                        + "Then call " + STATUS + " until it is read, and " + RESULT + " for its fields.",
                Map.of("process", prop("string", "The process's name or id, as " + LIST + " shows it."),
                        "file_name", prop("string", "The file's name with its extension, such as INV-1042.pdf."),
                        "content_base64", prop("string", "The file's content, Base64-encoded.")), List.of("process", "file_name", "content_base64")));
        out.add(tool(STATUS, "Document status", "Where a document is: still being read (done is false), read (success), waiting for a person's review (validation), "
                + "approved (validated), rejected (invalid) or failed.", Map.of("document_id", prop("string", "The id " + SEND + " gave.")), List.of("document_id")));
        out.add(tool(RESULT, "Document result", "The fields read from a document, each with its value and confidence (0 to 1), and its document type and status.",
                Map.of("document_id", prop("string", "The document's id.")), List.of("document_id")));
        if (ctx.can(u, "docs.review")) {
            out.add(tool(REVIEWS, "My review tasks", "The documents waiting for a person's review that you may review: why, since when and when it is due.", Map.of(), List.of()));
            Map<String, Object> decision = new LinkedHashMap<>(prop("string", "approve, or reject (the document is not valid)."));
            decision.put("enum", List.of("approve", "reject"));
            out.add(tool(REVIEW, "Approve or reject a review", "Finishes the review of a document waiting for one, as you: approve (optionally with corrected field values) or reject. "
                    + "Its Task inbox task closes.", Map.of("document_id", prop("string", "The document's id."), "decision", decision,
                    "fields", prop("object", "Corrected values by field name, such as {\"total\": 115.0}. Fields not given keep the value read.")), List.of("document_id", "decision")));
        }
        return out;
    }

    /** Whether the tools are offered: Document Automation is set up and Settings > mcp_documents is on. */
    private boolean on() {
        Object v = ctx.setting("mcp_documents");
        return docflow.configured() && !(v instanceof Number n ? n.intValue() == 0 : "0".equals(String.valueOf(v)) || "false".equals(String.valueOf(v)));
    }

    private static Map<String, Object> prop(String type, String description) { return Map.of("type", type, "description", description); }

    private static Map<String, Object> tool(String name, String title, String description, Map<String, Object> props, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>(Map.of("type", "object", "properties", new TreeMap<>(props)));
        if (!required.isEmpty()) schema.put("required", required);
        return Map.of("name", name, "title", title, "description", description, "inputSchema", schema);
    }

    /** Runs one tool as the person; the answer is the tool result (an error result when it cannot be done), audited either way. */
    public Map<String, Object> call(User u, String name, Map<String, Object> args, String ip) {
        Map<String, Object> audited = new LinkedHashMap<>();
        for (String k : List.of("process", "file_name", "document_id", "decision")) if (args.get(k) != null) audited.put(k, String.valueOf(args.get(k)));
        try {
            if (tools(u).stream().noneMatch(t -> t.get("name").equals(name)))
                throw new OoaException("CR.FORBIDDEN", on() ? "You may not use " + name : "Document Automation's tools are not offered here");
            Object out = switch (name) {
                case LIST -> processes(u);
                case SEND -> send(u, args);
                case STATUS -> status(u, need(args, "document_id"));
                case RESULT -> result(u, need(args, "document_id"));
                case REVIEWS -> reviews(u);
                default -> review(u, args);
            };
            ctx.audit(u.username(), "mcp.tool", "mcp_tool", name, audited, "ok", ip);
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("content", List.of(Map.of("type", "text", "text", JsonLite.write(out))));
            res.put("structuredContent", out instanceof Map<?, ?> ? out : Map.of("items", out));
            res.put("isError", false);
            return res;
        } catch (OoaException e) {
            audited.put("error", String.valueOf(e.getMessage()));
            ctx.audit(u.username(), "mcp.tool", "mcp_tool", name, audited, "CR.FORBIDDEN".equals(e.code()) ? "denied" : "failed", ip);
            return Map.of("content", List.of(Map.of("type", "text", "text", e.getMessage() + (e.suggestedFix() == null ? "" : " " + e.suggestedFix()))), "isError", true);
        }
    }

    private static String need(Map<String, Object> args, String k) {
        Object v = args.get(k);
        if (v == null || String.valueOf(v).isBlank()) throw new OoaException("CR.BAD_INPUT", "Give " + k);
        String s = String.valueOf(v).trim();
        if (k.equals("document_id") && !s.matches("[A-Za-z0-9_-]{1,120}")) throw new OoaException("CR.BAD_INPUT", "Not a document id: " + s);
        return s;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> visibleProcesses(User u) {
        return ((List<Object>) docflow.json(u, "GET", "df/flows", null)).stream().map(o -> (Map<String, Object>) o).toList();
    }

    private List<Map<String, Object>> processes(User u) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> f : visibleProcesses(u)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", f.get("id"));
            m.put("name", f.get("name"));
            m.put("description", f.getOrDefault("description", ""));
            m.put("folder", f.getOrDefault("folder", ""));
            m.put("status", f.get("status"));
            m.put("document_types", f.getOrDefault("docTypeNames", List.of()));
            out.add(m);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> send(User u, Map<String, Object> args) {
        String process = need(args, "process"), fileName = need(args, "file_name");
        if (!DocFlowProxy.READABLE.matcher(fileName).matches()) throw new OoaException("DOCS.FILE_TYPE", "Document Automation reads PDF, PNG, JPG and TIFF files, not " + fileName);
        byte[] data;
        try { data = Base64.getMimeDecoder().decode(need(args, "content_base64")); } catch (IllegalArgumentException e) { throw new OoaException("CR.BAD_INPUT", "content_base64 is not Base64"); }
        if (data.length == 0) throw new OoaException("CR.BAD_INPUT", "The file is empty");
        if (data.length > ctx.maxBody()) throw new OoaException("CR.TOO_LARGE", "The file is larger than the Largest upload setting allows");
        Map<String, Object> flow = visibleProcesses(u).stream().filter(f -> process.equals(f.get("id")) || process.equalsIgnoreCase(String.valueOf(f.get("name")))).findFirst()
                .orElseThrow(() -> new OoaException("CR.NOT_FOUND", "No Document Automation process '" + process + "' that you may see", "Call " + LIST + " for the names."));
        String boundary = "ooa" + UUID.randomUUID().toString().replace("-", "");
        String lower = fileName.toLowerCase(Locale.ROOT);
        String type = lower.endsWith(".pdf") ? "application/pdf" : lower.endsWith(".png") ? "image/png" : lower.matches(".+\\.tiff?") ? "image/tiff" : "image/jpeg";
        Map<String, Object> r = (Map<String, Object>) docflow.exchange(u, "POST", "df/flows/" + flow.get("id") + "/documents", "source=assistant&requireLive=true&wait=false",
                DocFlowProxy.multipart(boundary, fileName, type, data), Map.of("Content-Type", "multipart/form-data; boundary=" + boundary), "Assistant (" + u.username() + ")").json();
        List<Object> docs = (List<Object>) r.getOrDefault("documents", List.of());
        Map<String, Object> d = docs.isEmpty() ? Map.of() : (Map<String, Object>) docs.get(0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("document_id", d.get("documentId"));
        out.put("process", flow.get("name"));
        out.put("process_id", flow.get("id"));
        out.put("status", d.get("status"));
        out.put("next", "Call " + STATUS + " with this document_id until done is true, then " + RESULT + ".");
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> document(User u, String id) {
        return (Map<String, Object>) docflow.json(u, "GET", "df/documents/" + id, null);
    }

    private Map<String, Object> status(User u, String id) {
        Map<String, Object> d = document(u, id);
        String st = String.valueOf(d.get("status"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("document_id", id);
        out.put("status", st);
        out.put("done", !READING.contains(st));
        out.put("needs_review", "validation".equals(st));
        out.put("process_id", d.get("flowId"));
        out.put("file_name", d.get("file_name"));
        if (d.get("error") != null) out.put("error", d.get("error"));
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> result(User u, String id) {
        Map<String, Object> d = document(u, id);
        String st = String.valueOf(d.get("status"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("document_id", id);
        out.put("status", st);
        out.put("file_name", d.get("file_name"));
        out.put("process_id", d.get("flowId"));
        out.put("doc_type", d.get("doctype_id"));
        if (READING.contains(st)) { out.put("message", "The document is still being read: ask " + STATUS + " again in a few seconds."); return out; }
        Map<String, Object> values = d.get("fields") instanceof Map<?, ?> f ? (Map<String, Object>) f : Map.of();
        Map<String, Object> confidence = d.get("confidence") instanceof Map<?, ?> c ? (Map<String, Object>) c : Map.of();
        Map<String, Object> fields = new LinkedHashMap<>();
        values.forEach((k, v) -> {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("value", v);
            one.put("confidence", confidence.get(k));
            fields.put(k, one);
        });
        out.put("fields", fields);
        out.put("needs_review", "validation".equals(st));
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> reviews(User u) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : (List<Object>) docflow.json(u, "GET", "df/review", null)) {
            Map<String, Object> d = (Map<String, Object>) o;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("document_id", d.get("id"));
            m.put("file_name", d.get("file_name"));
            m.put("process", d.get("flowName"));
            m.put("process_id", d.get("flowId"));
            m.put("doc_type", d.get("doctypeName"));
            List<Object> flags = d.get("flags") instanceof List<?> l ? (List<Object>) l : List.of();
            m.put("why", flags.isEmpty() ? "Some values need a person's check" : String.valueOf(((Map<String, Object>) flags.get(0)).get("message")));
            m.put("waiting_since", d.get("created_at"));
            try {
                Instant created = Instant.parse(String.valueOf(d.get("created_at")).replaceFirst("\\+00:00$", "Z"));
                double sla = d.get("slaHours") instanceof Number n ? n.doubleValue() : 24;
                m.put("due", created.plus(Duration.ofMinutes((long) (sla * 60))).toString());
            } catch (RuntimeException ignored) { }
            m.put("overdue", Boolean.TRUE.equals(d.get("overdue")));
            out.add(m);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> review(User u, Map<String, Object> args) {
        String id = need(args, "document_id"), decision = need(args, "decision");
        if (!decision.equals("approve") && !decision.equals("reject")) throw new OoaException("CR.BAD_INPUT", "decision is approve or reject");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", decision.equals("approve") ? "submit" : "invalid");
        body.put("fields", args.get("fields") instanceof Map<?, ?> f ? f : Map.of());
        Map<String, Object> d = (Map<String, Object>) docflow.json(u, "POST", "df/documents/" + id + "/review", body);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("document_id", id);
        out.put("decision", decision);
        out.put("status", d.get("status"));
        return out;
    }
}
