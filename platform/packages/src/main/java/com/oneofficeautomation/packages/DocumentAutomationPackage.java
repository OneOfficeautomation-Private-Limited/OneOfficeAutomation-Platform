package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;

import java.math.BigDecimal;

import java.nio.file.*;
import java.util.*;
import java.util.function.Function;

/**
 * Document Automation (AI > Document Automation in the Command Center): an agent hands a document to a process, which
 * reads, checks and delivers it like an upload. The runner sends it to the Command Center for the run's user; there is
 * no Document Automation in Studio on its own.
 */
@OoaPackage(id = "ooa.documents", name = "Document Automation", version = "1.0.0",
        description = "Send documents (PDF, PNG, JPG, TIFF) to a Document Automation process, then pick up what it read: fields, tables and confidence, once read or reviewed.")
public final class DocumentAutomationPackage {
    private static final Set<String> READABLE = Set.of("pdf", "png", "jpg", "jpeg", "tif", "tiff");

    @Action(id = "send", name = "Send document to process", category = "Document Automation", timeoutSeconds = 900, capabilities = "filesystem",
            description = "Send a document to a live Document Automation process. Optionally wait until it is read, to use its fields and status in the agent.",
            panel = "process, path, [Wait for the fields: wait]")
    @Output(name = "document_id", type = OoaType.STRING, description = "The document's id in Document Automation.")
    @Output(name = "status", type = OoaType.STRING, description = "processing, success (read and checked), validation (waits for a reviewer), invalid or failed.")
    @Output(name = "needs_review", type = OoaType.BOOLEAN, description = "True when a person must review it (only known when waiting).")
    @Output(name = "fields", type = OoaType.DICTIONARY, description = "The fields read, by name (only when waiting).")
    @SuppressWarnings("unchecked")
    public Map<String, Object> send(ActionContext ctx,
            @Input(name = "process", type = OoaType.STRING, description = "The process's name (or id) as shown in Document Automation. It must be live.") String process,
            @Input(name = "path", type = OoaType.FILE, description = "The document: PDF, PNG, JPG or TIFF.") String path,
            @Input(name = "wait", type = OoaType.BOOLEAN, required = false, defaultValue = "false",
                    description = "Wait until the document is read (up to 15 minutes) and return its status and fields.") boolean wait) {
        if (process == null || process.isBlank()) throw new ActionException("DOCS.NO_PROCESS", "Choose the process to send the document to", "Use the process's name as shown in Document Automation.");
        Path p = ctx.resolvePath(path);
        if (!Files.isRegularFile(p)) throw new ActionException("FILE.NOT_FOUND", "Document not found: " + p, "Check the path.");
        String name = p.getFileName().toString(), ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        if (!READABLE.contains(ext)) throw new ActionException("DOCS.FILE_TYPE", "Document Automation reads PDF, PNG, JPG and TIFF files, not ." + ext, "Convert the file first.");
        Map<String, Object> r = docflow(ctx).apply(Map.of("process", process.trim(), "file", p.toAbsolutePath().toString(), "wait", wait));
        ctx.info("Sent " + name + " to " + r.getOrDefault("process", process) + ": " + r.get("status"));
        return Out.of("document_id", r.get("document_id"), "status", String.valueOf(r.get("status")), "needs_review", Boolean.TRUE.equals(r.get("needs_review")),
                "fields", r.get("fields") instanceof Map<?, ?> f ? f : Map.of());
    }

    private static Function<Map<String, Object>, Map<String, Object>> docflow(ActionContext ctx) {
        try {
            @SuppressWarnings("unchecked") Function<Map<String, Object>, Map<String, Object>> f = ctx.service("docflow", Function.class);
            if (f != null) return f;
        } catch (OoaException ignored) { /* below */ }
        throw new ActionException("DOCS.NOT_AVAILABLE", "Document Automation is reachable only when the agent runs from the Command Center",
                "Check the agent in and run it from the Command Center, where Document Automation is set up.");
    }

    // ---------- picking up results later (command review D1, 12 Nov 2026) ----------

    static final String D_DOC_ID = "The document id that Send document to process gave (or a document_id input given by a Start-a-bot delivery).";
    static final String D_COLLECTOR = "A name for whoever collects: agents with the same name share the collected marks, so none takes a document twice. Empty: default.";

    @Action(id = "get_result", name = "Get document result", category = "Document Automation", idempotent = true,
            description = "Read a document's status and what Document Automation read from it, by its id: fields, tables (such as line items) and each field's confidence.",
            panel = "document_id")
    @Output(name = "status", type = OoaType.STRING, description = "queued or processing (still being read), success (read and checked), validation (waits for a reviewer), validated (approved), invalid (rejected) or failed.")
    @Output(name = "done", type = OoaType.BOOLEAN, description = "True when the document is no longer being read.")
    @Output(name = "needs_review", type = OoaType.BOOLEAN, description = "True while it waits for a person's review.")
    @Output(name = "reviewed", type = OoaType.BOOLEAN, description = "True when a person approved or rejected it.")
    @Output(name = "fields", type = OoaType.DICTIONARY, description = "The fields read, by name (after review: the corrected values).")
    @Output(name = "tables", type = OoaType.DICTIONARY, description = "Table fields (such as line items), by name, each a Data table.")
    @Output(name = "confidence", type = OoaType.DICTIONARY, description = "How sure the reading was for each field, from 0 to 1.")
    @Output(name = "document_type", type = OoaType.STRING, description = "The document type it was classified as.")
    @Output(name = "file_name", type = OoaType.STRING, description = "The document's file name.")
    @Output(name = "reviewed_by", type = OoaType.STRING, description = "Who approved or rejected it, when reviewed.")
    @Output(name = "error", type = OoaType.STRING, description = "Why reading failed, when it failed.")
    public Map<String, Object> getResult(ActionContext ctx,
            @Input(name = "document_id", type = OoaType.STRING, description = D_DOC_ID) String documentId) {
        return outputs(ask(ctx, Map.of("op", "get", "document_id", id(documentId))));
    }

    @Action(id = "wait_for_document", name = "Wait for document", category = "Document Automation", timeoutSeconds = 86400,
            description = "Wait until a document is read, or until it is also reviewed when it needs a review, then give its result.",
            panel = "document_id, until | timeout_seconds, check_every_seconds")
    @Output(name = "status", type = OoaType.STRING, description = "The document's status when the wait ended.")
    @Output(name = "done", type = OoaType.BOOLEAN, description = "True when the document is no longer being read.")
    @Output(name = "needs_review", type = OoaType.BOOLEAN, description = "True while it waits for a person's review.")
    @Output(name = "reviewed", type = OoaType.BOOLEAN, description = "True when a person approved or rejected it.")
    @Output(name = "fields", type = OoaType.DICTIONARY, description = "The fields read, by name.")
    @Output(name = "tables", type = OoaType.DICTIONARY, description = "Table fields (such as line items), by name, each a Data table.")
    @Output(name = "confidence", type = OoaType.DICTIONARY, description = "How sure the reading was for each field, from 0 to 1.")
    @Output(name = "document_type", type = OoaType.STRING, description = "The document type it was classified as.")
    @Output(name = "file_name", type = OoaType.STRING, description = "The document's file name.")
    @Output(name = "reviewed_by", type = OoaType.STRING, description = "Who approved or rejected it, when reviewed.")
    @Output(name = "error", type = OoaType.STRING, description = "Why reading failed, when it failed.")
    public Map<String, Object> waitForDocument(ActionContext ctx,
            @Input(name = "document_id", type = OoaType.STRING, description = D_DOC_ID) String documentId,
            @Input(name = "until", type = OoaType.STRING, required = false, defaultValue = "\"read\"", enumValues = {"read", "reviewed"},
                    description = "Read: until it is read (it may then wait for a review). Reviewed: also until a person has reviewed it, when it needs a review.") String until,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "900", min = 1, max = 86400,
                    description = "Give up after this many seconds with the error DOCS.WAIT_TIMEOUT (a review may take hours).") int timeout,
            @Input(name = "check_every_seconds", type = OoaType.NUMBER, required = false, defaultValue = "10", min = 1, max = 600,
                    description = "How often to ask Document Automation.") int every) {
        String id = id(documentId);
        boolean reviewed = "reviewed".equals(until);
        long end = System.currentTimeMillis() + timeout * 1000L;
        while (true) {
            Map<String, Object> r = ask(ctx, Map.of("op", "get", "document_id", id));
            boolean read = Boolean.TRUE.equals(r.get("done"));
            if (read && !(reviewed && Boolean.TRUE.equals(r.get("needs_review")))) return outputs(r);
            long left = end - System.currentTimeMillis();
            if (left <= 0) throw new ActionException("DOCS.WAIT_TIMEOUT", "Document " + id + " is still " + r.get("status") + " after " + timeout + " s",
                    reviewed ? "Raise timeout_seconds, or pick the document up later with List finished documents." : "Raise timeout_seconds.", true);
            long nap = Math.min(left, Math.max(1, every) * 1000L);
            for (long t = 0; t < nap; t += 250) {
                ctx.checkCancelled();
                try { Thread.sleep(Math.min(250, nap - t)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new ActionException("RUN.CANCELLED", "The wait was stopped"); }
            }
        }
    }

    @Action(id = "list_finished", name = "List finished documents", category = "Document Automation", idempotent = true,
            description = "List a process's documents that are read and need nothing more (read and checked, approved or rejected) and are not yet marked collected.",
            panel = "process, [Only approved or rejected: reviewed_only], collector | include_collected, limit")
    @Output(name = "documents", type = OoaType.TABLE, description = "Columns: document_id, file_name, status, document_type, created_at, reviewed_by, collected.")
    @Output(name = "document_ids", type = OoaType.LIST, description = "The documents' ids, for a loop or for Mark collected.")
    @Output(name = "count", type = OoaType.NUMBER, description = "How many documents are listed.")
    @SuppressWarnings("unchecked")
    public Map<String, Object> listFinished(ActionContext ctx,
            @Input(name = "process", type = OoaType.STRING, description = "The process's name (or id) as shown in Document Automation.") String process,
            @Input(name = "reviewed_only", type = OoaType.BOOLEAN, required = false, defaultValue = "false",
                    description = "Only documents a person approved or rejected (not those read and checked without a review).") boolean reviewedOnly,
            @Input(name = "collector", type = OoaType.STRING, required = false, description = D_COLLECTOR) String collector,
            @Input(name = "include_collected", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Also list documents already marked collected.") boolean includeCollected,
            @Input(name = "limit", type = OoaType.NUMBER, required = false, defaultValue = "100", min = 1, max = 500, description = "At most this many documents.") int limit) {
        if (process == null || process.isBlank()) throw new ActionException("DOCS.NO_PROCESS", "Choose the process", "Use the process's name as shown in Document Automation.");
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("op", "list");
        req.put("process", process.trim());
        req.put("statuses", reviewedOnly ? List.of("validated", "invalid") : List.of("success", "validated", "invalid"));
        req.put("include_collected", includeCollected);
        req.put("limit", limit);
        if (collector != null && !collector.isBlank()) req.put("collector", collector.strip());
        Map<String, Object> r = ask(ctx, req);
        List<Object> rows = new ArrayList<>(), ids = new ArrayList<>();
        for (Object o : r.get("documents") instanceof List<?> l ? l : List.of()) {
            Map<String, Object> d = (Map<String, Object>) o;
            ids.add(String.valueOf(d.get("document_id")));
            rows.add(new ArrayList<>(Arrays.asList(d.get("document_id"), d.get("file_name"), d.get("status"), d.get("doc_type"), d.get("created_at"), d.get("validated_by"),
                    Boolean.TRUE.equals(d.get("collected")))));
        }
        return Out.of("documents", Values.table(List.of("document_id", "file_name", "status", "document_type", "created_at", "reviewed_by", "collected"), rows),
                "document_ids", ids, "count", BigDecimal.valueOf(ids.size()));
    }

    @Action(id = "mark_collected", name = "Mark collected", category = "Document Automation",
            description = "Mark documents as collected, so List finished documents leaves them out for the same collector name.",
            panel = "document_ids, collector")
    @Output(name = "marked", type = OoaType.NUMBER, description = "How many documents were marked now.")
    @Output(name = "already_collected", type = OoaType.NUMBER, description = "How many were marked before.")
    public Map<String, Object> markCollected(ActionContext ctx,
            @Input(name = "document_ids", type = OoaType.LIST, description = "The ids to mark (a list, or one id).") Object documentIds,
            @Input(name = "collector", type = OoaType.STRING, required = false, description = D_COLLECTOR) String collector) {
        List<Object> ids = new ArrayList<>();
        if (documentIds instanceof Collection<?> c) for (Object o : c) ids.add(id(Values.text(o)));
        else if (documentIds != null && !Values.text(documentIds).isBlank()) ids.add(id(Values.text(documentIds)));
        if (ids.isEmpty()) throw new ActionException("DOCS.NO_DOCUMENT", "Give the ids of the documents to mark", "Use document_ids from List finished documents.");
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("op", "collect");
        req.put("document_ids", ids);
        if (collector != null && !collector.isBlank()) req.put("collector", collector.strip());
        Map<String, Object> r = ask(ctx, req);
        return Out.of("marked", num(r.get("marked")), "already_collected", num(r.get("already_collected")));
    }

    private static Map<String, Object> ask(ActionContext ctx, Map<String, Object> req) {
        try { return docflow(ctx).apply(req); }
        catch (ActionException e) { throw e; }
        catch (OoaException e) { throw new ActionException(e.code(), e.getMessage(), e.suggestedFix(), e.retryable()); }
    }

    private static String id(String documentId) {
        if (documentId == null || documentId.isBlank()) throw new ActionException("DOCS.NO_DOCUMENT", "Give the document id", "Use the document_id that Send document to process gave.");
        return documentId.strip();
    }

    private static BigDecimal num(Object o) { return o instanceof Number n ? new BigDecimal(n.toString()) : BigDecimal.ZERO; }

    /** The result as outputs; each table field becomes a Data table with the columns of its rows. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> outputs(Map<String, Object> r) {
        Map<String, Object> tables = new LinkedHashMap<>();
        if (r.get("tables") instanceof Map<?, ?> t) ((Map<String, Object>) t).forEach((name, v) -> {
            List<String> cols = new ArrayList<>();
            for (Object row : (List<Object>) v) for (Object k : ((Map<String, Object>) row).keySet()) if (!cols.contains(String.valueOf(k))) cols.add(String.valueOf(k));
            List<Object> rows = new ArrayList<>();
            for (Object row : (List<Object>) v) { List<Object> cells = new ArrayList<>(); for (String c : cols) cells.add(((Map<String, Object>) row).get(c)); rows.add(cells); }
            tables.put(name, Values.table(cols, rows));
        });
        return Out.of("status", String.valueOf(r.get("status")), "done", Boolean.TRUE.equals(r.get("done")), "needs_review", Boolean.TRUE.equals(r.get("needs_review")),
                "reviewed", Boolean.TRUE.equals(r.get("reviewed")), "fields", r.get("fields") instanceof Map<?, ?> f ? f : Map.of(), "tables", tables,
                "confidence", r.get("confidence") instanceof Map<?, ?> c ? c : Map.of(), "document_type", r.get("doc_type"), "file_name", r.get("file_name"),
                "reviewed_by", r.get("validated_by"), "error", r.get("error") == null ? null : String.valueOf(r.get("error")));
    }
}
