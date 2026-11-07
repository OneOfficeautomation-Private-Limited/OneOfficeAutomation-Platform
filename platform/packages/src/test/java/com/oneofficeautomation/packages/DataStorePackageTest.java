package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** G66: the Data store toolkit, in Studio's work folder and through One CommandCenter (as the runner relays it). */
class DataStorePackageTest {
    @TempDir Path w;

    @Test @SuppressWarnings("unchecked")
    void recordsAndFilesInTheWorkFolderWithoutOneCommandCenter() throws Exception {
        ActionContext ctx = PackagesTestSupport.ctx(w, Map.of());
        DataStorePackage p = new DataStorePackage();
        String a = (String) p.create(ctx, "Suppliers", Map.of("name", "ACME", "limit", 5000)).get("id");
        p.create(ctx, "Suppliers", Map.of("name", "Globex", "limit", 900));
        p.update(ctx, "Suppliers", a, Map.of("limit", 6000));
        assertEquals("6000", String.valueOf(((Map<String, Object>) p.get(ctx, "Suppliers", a).get("record")).get("limit")));
        Map<String, Object> q = p.query(ctx, "Suppliers", List.of(Map.of("field", "limit", "op", ">=", "value", 1000)), "name", false, 100, 0);
        assertEquals(1, q.get("total"));
        Map<String, Object> t = (Map<String, Object>) q.get("table");
        assertEquals(Set.of("id", "name", "limit"), new HashSet<>((List<String>) t.get("columns")));
        assertEquals(true, p.delete(ctx, "Suppliers", a).get("deleted"));
        assertEquals("DATA.NOT_FOUND", assertThrows(ActionException.class, () -> p.get(ctx, "Suppliers", a)).code());

        Files.writeString(w.resolve("invoice.pdf"), "pdf");
        assertEquals("2026/invoice.pdf", p.upload(ctx, "invoice.pdf", "Invoices", "2026/invoice.pdf", true).get("path"));
        assertEquals(List.of("2026/invoice.pdf"), p.list(ctx, "Invoices", "2026/").get("paths"));
        Path back = Path.of((String) p.download(ctx, "Invoices", "2026/invoice.pdf", "copy/inv.pdf").get("file"));
        assertEquals("pdf", Files.readString(back));
        assertEquals(true, p.deleteFile(ctx, "Invoices", "2026/invoice.pdf").get("deleted"));
        assertEquals("DATA.BAD_PATH", assertThrows(ActionException.class, () -> p.download(ctx, "Invoices", "../../x", null)).code());
    }

    @Test @SuppressWarnings("unchecked")
    void throughOneCommandCenterEachStepIsOneRequest() {
        List<Map<String, Object>> seen = new ArrayList<>();
        Function<Map<String, Object>, Map<String, Object>> server = req -> {
            seen.add(req);
            return switch (String.valueOf(req.get("op"))) {
                case "create" -> Map.of("id", "rec_1", "data", req.get("data"));
                case "query" -> Map.of("records", List.of(Map.of("id", "rec_1", "data", Map.of("name", "ACME"))), "total", 1,
                    "fields", List.of(Map.of("name", "name"), Map.of("name", "country")));
                default -> Map.of();
            };
        };
        ActionContext ctx = PackagesTestSupport.ctx(w, Map.of("data_store", server));
        DataStorePackage p = new DataStorePackage();
        assertEquals("rec_1", p.create(ctx, "Suppliers", Map.of("name", "ACME")).get("id"));
        Map<String, Object> q = p.query(ctx, "Suppliers", null, null, false, 10, 0);
        assertEquals(List.of("id", "name", "country"), ((Map<String, Object>) q.get("table")).get("columns"), "every field is a column, even when empty");
        assertEquals("create", seen.get(0).get("op"));
        assertEquals("Suppliers", seen.get(1).get("entity"));
        assertFalse(Files.exists(w.resolve("data-store")), "nothing is kept in the work folder");
    }
}
