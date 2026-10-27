package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** A1: work items an agent read with too little confidence wait for a person, who corrects and approves or rejects them. */
class ValidationQueueTest {

    @Test @SuppressWarnings("unchecked")
    void lowConfidenceItemsWaitForAPersonAndGoBackChecked() throws Exception {
        Path data = Files.createTempDirectory("ooa-validation");
        Db db = Db.embedded(data);
        try {
            ControlRoom cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
            ControlRoom.User admin = RepositoryLifecycleTest.boot(cr);
            String qid = (String) cr.platform.saveQueue(admin, null, Map.of("name", "Invoices"), null).get("id");
            cr.platform.addItems(admin, qid, List.of(Map.of("file", "inv-1.pdf"), Map.of("file", "inv-2.pdf")), null);
            List<Map<String, Object>> items = (List<Map<String, Object>>) cr.platform.items(admin, qid, null, null, 10, 0).get("items");
            String one = (String) items.stream().filter(i -> String.valueOf(i.get("data")).contains("inv-1")).findFirst().orElseThrow().get("id");
            String two = (String) items.stream().filter(i -> String.valueOf(i.get("data")).contains("inv-2")).findFirst().orElseThrow().get("id");

            // the agent's runs end; the first reports fields to check
            Map<String, Object> asked = Map.of("fields", Map.of("invoice_no", "INV-77", "total", "1O0.00"), "confidence", Map.of("invoice_no", 0.97, "total", 0.41), "min_confidence", 0.8);
            finish(cr, db, one, "run_a", Map.of("validation", asked));
            finish(cr, db, two, "run_b", Map.of("done", true));
            assertEquals("review", status(db, one));
            assertEquals("success", status(db, two));
            assertEquals("CR.CONFLICT", assertThrows(OoaException.class, () -> cr.platform.validateItem(admin, qid, two, Map.of(), null)).code(), "only items waiting for it");

            // the person corrects the total; only the fields asked about are taken
            Map<String, Object> r = cr.platform.validateItem(admin, qid, one, Map.of("fields", Map.of("total", "100.00", "smuggled", "x")), null);
            assertEquals(List.of("total"), r.get("changed"));
            assertEquals("new", status(db, one), "back in the queue for the agent");
            Map<String, Object> item = (Map<String, Object>) JsonLite.parse((String) db.one("SELECT data FROM queue_items WHERE id = ?", one).get("data"));
            assertEquals(Map.of("invoice_no", "INV-77", "total", "100.00"), item.get("validated_fields"));
            assertEquals(true, item.get("validated"));
            assertEquals("admin", item.get("validated_by"));
            assertEquals("inv-1.pdf", item.get("file"), "the original data stays");
            assertNotNull(db.one("SELECT seq FROM audit WHERE action = 'queue.item_validate' AND object_id = ?", one));

            // rejecting needs a reason and fails the item
            finish(cr, db, one, "run_c", Map.of("validation", asked));
            assertEquals("CR.BAD_INPUT", assertThrows(OoaException.class, () -> cr.platform.validateItem(admin, qid, one, Map.of("reject", true), null)).code());
            cr.platform.validateItem(admin, qid, one, Map.of("reject", true, "reason", "Not an invoice"), null);
            assertEquals("failed", status(db, one));
            assertTrue(String.valueOf(db.one("SELECT error FROM queue_items WHERE id = ?", one).get("error")).contains("VALIDATION.REJECTED"));
        } finally { db.close(); }
    }

    static String status(Db db, String id) { return String.valueOf(db.one("SELECT status FROM queue_items WHERE id = ?", id).get("status")); }

    /** Marks the item worked on by a run that ended with these outputs. */
    static void finish(ControlRoom cr, Db db, String itemId, String runId, Map<String, Object> outputs) {
        db.update("UPDATE queue_items SET status = 'in_progress' WHERE id = ?", itemId);
        db.update("INSERT INTO runs (id, bot_path, version, device_id, status, inputs, requested_by, source, created, cancel_requested, pause_requested, queue_item_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
            runId, "Invoices.flow.json", 1, "dev", "success", "{}", "admin", "queue", Instant.now(), false, false, itemId);
        cr.platform.itemFinished(runId, "success", null, outputs);
    }
}
