package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;

import java.nio.file.*;
import java.util.*;
import java.util.function.Function;

/**
 * Document Automation (AI > Document Automation in the Command Center): an agent hands a document to a process, which
 * reads, checks and delivers it like an upload. The runner sends it to the Command Center for the run's user; there is
 * no Document Automation in Studio on its own.
 */
@OoaPackage(id = "ooa.documents", name = "Document Automation", version = "1.0.0",
        description = "Send documents (PDF, PNG, JPG, TIFF) to a Document Automation process and get the fields it reads.")
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
        Function<Map<String, Object>, Map<String, Object>> docflow;
        try { docflow = ctx.service("docflow", Function.class); }
        catch (OoaException e) {
            throw new ActionException("DOCS.NOT_AVAILABLE", "Documents can be sent to a process only when the agent runs from the Command Center",
                    "Check the agent in and run it from the Command Center, where Document Automation is set up.");
        }
        Map<String, Object> r = docflow.apply(Map.of("process", process.trim(), "file", p.toAbsolutePath().toString(), "wait", wait));
        ctx.info("Sent " + name + " to " + r.getOrDefault("process", process) + ": " + r.get("status"));
        return Out.of("document_id", r.get("document_id"), "status", String.valueOf(r.get("status")), "needs_review", Boolean.TRUE.equals(r.get("needs_review")),
                "fields", r.get("fields") instanceof Map<?, ?> f ? f : Map.of());
    }
}
