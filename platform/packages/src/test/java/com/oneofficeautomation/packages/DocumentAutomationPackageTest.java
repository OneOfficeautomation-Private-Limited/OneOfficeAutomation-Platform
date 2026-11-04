package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** "Send document to process": the file goes to the run's docflow service; outside the Command Center it says why not. */
class DocumentAutomationPackageTest {
    final DocumentAutomationPackage docs = new DocumentAutomationPackage();

    static ActionContext ctx(Path dir, Map<String, Object> services) {
        return new ActionContext("run_test", "s1", "default", dir, new HashMap<>(), services, new java.util.concurrent.atomic.AtomicBoolean(), List::of, (l, m) -> { });
    }

    @Test void sendsTheDocumentAndReturnsWhatTheProcessRead(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("INV-1.pdf"), "%PDF-1.4");
        List<Map<String, Object>> asked = new ArrayList<>();
        Function<Map<String, Object>, Map<String, Object>> service = req -> {
            asked.add(req);
            return Map.of("document_id", "d1", "process", "AP invoices", "status", "validation", "needs_review", true, "fields", Map.of("total", 115));
        };
        Map<String, Object> out = docs.send(ctx(dir, Map.of("docflow", service)), " AP invoices ", "INV-1.pdf", true);
        assertEquals(Map.of("document_id", "d1", "status", "validation", "needs_review", true, "fields", Map.of("total", 115)), out);
        assertEquals("AP invoices", asked.get(0).get("process"));
        assertEquals(dir.resolve("INV-1.pdf").toAbsolutePath().toString(), asked.get(0).get("file"));
        assertEquals(true, asked.get(0).get("wait"));
    }

    @Test void onlyReadableFilesAndOnlyFromTheCommandCenter(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("notes.docx"), "x");
        Files.writeString(dir.resolve("scan.png"), "x");
        assertEquals("DOCS.FILE_TYPE", assertThrows(ActionException.class, () -> docs.send(ctx(dir, Map.of()), "AP", "notes.docx", false)).code());
        assertEquals("FILE.NOT_FOUND", assertThrows(ActionException.class, () -> docs.send(ctx(dir, Map.of()), "AP", "missing.pdf", false)).code());
        assertEquals("DOCS.NOT_AVAILABLE", assertThrows(ActionException.class, () -> docs.send(ctx(dir, Map.of()), "AP", "scan.png", false)).code());
        assertEquals("DOCS.NO_PROCESS", assertThrows(ActionException.class, () -> docs.send(ctx(dir, Map.of()), " ", "scan.png", false)).code());
    }
}
