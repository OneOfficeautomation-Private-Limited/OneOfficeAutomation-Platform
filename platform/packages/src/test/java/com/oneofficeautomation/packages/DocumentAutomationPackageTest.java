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

    // ---------- D1 (command review, batch 9): picking up results later ----------

    static Map<String, Object> row(Object... kv) { Map<String, Object> m = new LinkedHashMap<>(); for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]); return m; }

    static Map<String, Object> doc(String status) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("document_id", "d1"); d.put("status", status);
        d.put("done", !status.equals("processing")); d.put("needs_review", status.equals("validation")); d.put("reviewed", status.equals("validated"));
        d.put("fields", Map.of("total", 115)); d.put("confidence", Map.of("total", 0.62)); d.put("doc_type", "invoice"); d.put("file_name", "INV-1.pdf");
        d.put("tables", Map.of("lines", List.of(row("item", "Laptop", "qty", 3), row("item", "Mouse", "price", 9))));
        if (status.equals("validated")) d.put("validated_by", "ann");
        return d;
    }

    @SuppressWarnings("unchecked")
    @Test void getResultGivesFieldsTablesAndConfidence(@TempDir Path dir) {
        List<Map<String, Object>> asked = new ArrayList<>();
        Function<Map<String, Object>, Map<String, Object>> service = req -> { asked.add(req); return doc("validation"); };
        Map<String, Object> out = docs.getResult(ctx(dir, Map.of("docflow", service)), " d1 ");
        assertEquals(Map.of("op", "get", "document_id", "d1"), asked.get(0));
        assertEquals(List.of("validation", true, true, false), List.of(out.get("status"), out.get("done"), out.get("needs_review"), out.get("reviewed")));
        assertEquals(Map.of("total", 115), out.get("fields"));
        assertEquals(Map.of("total", 0.62), out.get("confidence"));
        assertEquals("invoice", out.get("document_type"));
        Map<String, Object> lines = (Map<String, Object>) ((Map<String, Object>) out.get("tables")).get("lines");
        assertEquals(List.of("item", "qty", "price"), lines.get("columns"), "every row's columns, in the order met");
        assertEquals(Arrays.asList("Mouse", null, 9), ((List<Object>) lines.get("rows")).get(1));
        assertEquals("DOCS.NO_DOCUMENT", assertThrows(ActionException.class, () -> docs.getResult(ctx(dir, Map.of("docflow", service)), " ")).code());
        assertEquals("DOCS.NOT_AVAILABLE", assertThrows(ActionException.class, () -> docs.getResult(ctx(dir, Map.of()), "d1")).code());
    }

    @Test void waitForDocumentWaitsUntilReadOrReviewed(@TempDir Path dir) {
        Deque<String> statuses = new ArrayDeque<>(List.of("processing", "validation", "validation", "validated"));
        Function<Map<String, Object>, Map<String, Object>> service = req -> doc(statuses.size() > 1 ? statuses.poll() : statuses.peek());
        Map<String, Object> read = docs.waitForDocument(ctx(dir, Map.of("docflow", service)), "d1", "read", 60, 1);
        assertEquals("validation", read.get("status"), "read: the first status that is no longer being read");
        Map<String, Object> reviewed = docs.waitForDocument(ctx(dir, Map.of("docflow", service)), "d1", "reviewed", 60, 1);
        assertEquals(List.of("validated", "ann"), List.of(reviewed.get("status"), reviewed.get("reviewed_by")));
        Function<Map<String, Object>, Map<String, Object>> never = req -> doc("validation");
        ActionException e = assertThrows(ActionException.class, () -> docs.waitForDocument(ctx(dir, Map.of("docflow", never)), "d1", "reviewed", 1, 1));
        assertEquals("DOCS.WAIT_TIMEOUT", e.code());
        assertTrue(e.retryable());
    }

    @SuppressWarnings("unchecked")
    @Test void listFinishedAndMarkCollected(@TempDir Path dir) {
        List<Map<String, Object>> asked = new ArrayList<>();
        Function<Map<String, Object>, Map<String, Object>> service = req -> {
            asked.add(req);
            return req.get("op").equals("list")
                    ? Map.of("process", "AP invoices", "documents", List.of(Map.of("document_id", "d1", "file_name", "INV-1.pdf", "status", "validated", "validated_by", "ann", "collected", false),
                        Map.of("document_id", "d2", "file_name", "INV-2.pdf", "status", "success", "collected", false)))
                    : Map.of("marked", 1, "already_collected", 1);
        };
        ActionContext c = ctx(dir, Map.of("docflow", service));
        Map<String, Object> list = docs.listFinished(c, " AP invoices ", false, "posting", false, 50);
        assertEquals(Map.of("op", "list", "process", "AP invoices", "statuses", List.of("success", "validated", "invalid"), "include_collected", false, "limit", 50, "collector", "posting"),
                asked.get(0));
        assertEquals(List.of("d1", "d2"), list.get("document_ids"));
        assertEquals(new java.math.BigDecimal("2"), list.get("count"));
        assertEquals("ann", ((List<Object>) ((List<Object>) ((Map<String, Object>) list.get("documents")).get("rows")).get(0)).get(5));
        docs.listFinished(c, "AP invoices", true, null, false, 100);
        assertEquals(List.of("validated", "invalid"), asked.get(1).get("statuses"));
        assertFalse(asked.get(1).containsKey("collector"), "an empty collector is the default one");

        Map<String, Object> marked = docs.markCollected(c, List.of("d1", " d2 "), "posting");
        assertEquals(Map.of("op", "collect", "document_ids", List.of("d1", "d2"), "collector", "posting"), asked.get(2));
        assertEquals(List.of(java.math.BigDecimal.ONE, java.math.BigDecimal.ONE), List.of(marked.get("marked"), marked.get("already_collected")));
        docs.markCollected(c, "d9", null);
        assertEquals(List.of("d9"), asked.get(3).get("document_ids"), "one id on its own");
        assertEquals("DOCS.NO_DOCUMENT", assertThrows(ActionException.class, () -> docs.markCollected(c, List.of(), null)).code());
        assertEquals("DOCS.NO_PROCESS", assertThrows(ActionException.class, () -> docs.listFinished(c, "", false, null, false, 100)).code());
    }
}
