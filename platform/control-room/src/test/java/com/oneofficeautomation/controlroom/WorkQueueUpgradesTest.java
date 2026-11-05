package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Gap G31 in One CommandCenter: tags, unique references, unlock, delete, copy and move, exception kinds, encrypted data, agents' commands. */
class WorkQueueUpgradesTest {
    Db db;
    ControlRoom cr;
    ControlRoom.User admin;
    String qid;
    static final Map<String, Object> DEVICE = Map.of("id", "dev1", "name", "runner-1");

    @BeforeEach void setUp() throws Exception {
        Path data = Files.createTempDirectory("ooa-g31");
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        admin = RepositoryLifecycleTest.boot(cr);
        qid = (String) cr.platform.saveQueue(admin, null, Map.of("name", "Invoices", "max_attempts", 3), null).get("id");
    }

    @AfterEach void tearDown() { db.close(); }

    static String code(org.junit.jupiter.api.function.Executable e) { return assertThrows(OoaException.class, e).code(); }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> items(String queue, String tag) { return (List<Map<String, Object>>) cr.platform.items(admin, queue, null, null, tag, 100, 0).get("items"); }

    String add(Map<String, Object> item) { return (String) ((List<?>) cr.platform.addItems(admin, qid, List.of(item), null).get("ids")).get(0); }

    void run(String id, String status) {
        db.update("INSERT INTO runs (id, bot_path, version, device_id, status, inputs, requested_by, source, created, cancel_requested, pause_requested) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                id, "Invoices.flow.json", 1, "dev1", status, "{}", "admin", "test", Instant.now(), false, false);
    }

    Map<String, Object> bot(String runId, Map<String, Object> request) { return cr.platform.queueTools.fromBot(DEVICE, Map.of("run_id", runId, "request", request)); }

    ControlRoom.User user(String name, String role) {
        Map<String, Object> u = cr.createUser(admin, name, name, role, null);
        return new ControlRoom.User(String.valueOf(u.getOrDefault("id", name)), name, role, name, false);
    }

    boolean audited(String action) { return db.one("SELECT seq FROM audit WHERE action = ?", action) != null; }

    @Test void tagsAndUniqueReferences() {
        String a = add(Map.of("n", 1, "_tags", List.of("north", "Urgent")));
        add(Map.of("n", 2, "_tags", "south"));
        assertEquals(List.of(a), items(qid, "urgent").stream().map(i -> i.get("id")).toList(), "tags are found ignoring case");
        assertEquals(List.of("north", "Urgent"), items(qid, "urgent").get(0).get("tags"));
        assertTrue(items(qid, "north,south").isEmpty(), "every tag given");
        assertEquals(1, cr.platform.queueTools.retag(admin, "admin", qid, List.of(a), List.of("late"), List.of("north"), null));
        assertEquals(List.of("Urgent", "late"), cr.platform.item(admin, qid, a).get("tags"));
        assertTrue(audited("queue.items_tag"));

        // references added as unique
        Map<String, Object> first = cr.platform.addItems(admin, qid, List.of(Map.of("_reference", "INV-1", "amount", 1, "_if_exists", "error")), null);
        String inv1 = (String) ((List<?>) first.get("ids")).get(0);
        assertEquals("CR.DUPLICATE_REFERENCE", code(() -> cr.platform.addItems(admin, qid, List.of(Map.of("_reference", "X", "_if_exists", "error"), Map.of("_reference", "INV-1", "_if_exists", "error")), null)));
        assertTrue(items(qid, null).stream().noneMatch(i -> "X".equals(i.get("reference"))), "the whole batch is refused");
        Map<String, Object> skip = cr.platform.addItems(admin, qid, List.of(Map.of("_reference", "INV-1", "amount", 2, "_if_exists", "skip")), null);
        assertEquals(0, skip.get("added")); assertEquals(1, skip.get("skipped")); assertEquals(List.of(inv1), skip.get("ids"));
        Map<String, Object> upd = cr.platform.addItems(admin, qid, List.of(Map.of("_reference", "INV-1", "amount", 3, "_if_exists", "update", "_tags", List.of("fixed"))), null);
        assertEquals(1, upd.get("updated"));
        assertEquals(Map.of("amount", new java.math.BigDecimal(3)), cr.platform.item(admin, qid, inv1).get("data"));
        // without _if_exists, as before: references may repeat
        assertEquals(1, cr.platform.addItems(admin, qid, List.of(Map.of("_reference", "INV-1", "amount", 4)), null).get("added"));
        // changing a unique reference to one in use is refused
        String inv2 = (String) ((List<?>) cr.platform.addItems(admin, qid, List.of(Map.of("_reference", "INV-2", "_if_exists", "error")), null).get("ids")).get(0);
        assertEquals("CR.DUPLICATE_REFERENCE", code(() -> cr.platform.updateItem(admin, qid, inv2, Map.of("reference", "INV-1"), null)));
        cr.platform.updateItem(admin, qid, inv2, Map.of("reference", "INV-9"), null);
        assertEquals("INV-9", db.one("SELECT unique_ref FROM queue_items WHERE id = ?", inv2).get("unique_ref"));
    }

    @Test void unlockDeleteCopyAndMove() {
        String a = add(Map.of("n", 1, "_reference", "R1", "_tags", "eu", "_priority", 5));
        String b = add(Map.of("n", 2, "_reference", "R2"));
        run("run_live", "running");
        db.update("UPDATE queue_items SET status = 'in_progress', attempts = 1, run_id = 'run_live' WHERE id = ?", a);
        assertEquals(0, cr.platform.queueTools.unlock(admin, "admin", qid, List.of(a), null, null), "its run is still going");
        db.update("UPDATE runs SET status = 'failed' WHERE id = 'run_live'");
        assertEquals(1, cr.platform.queueTools.unlock(admin, "admin", qid, List.of(a, b), null, null), "b is not in progress");
        Map<String, Object> ua = db.one("SELECT status, attempts, run_id FROM queue_items WHERE id = ?", a);
        assertEquals("new", ua.get("status")); assertEquals(1, ((Number) ua.get("attempts")).intValue(), "attempts kept"); assertNull(ua.get("run_id"));
        assertTrue(audited("queue.items_unlock"));

        // copy keeps data, reference, tags and priority, with new ids; move takes them out
        String other = (String) cr.platform.saveQueue(admin, null, Map.of("name", "Archive"), null).get("id");
        Map<String, Object> copied = cr.platform.queueTools.transfer(admin, "admin", qid, "Archive", Map.of(), false, null);
        assertEquals(2, copied.get("count"));
        Map<String, Object> c1 = items(other, "eu").get(0);
        assertNotEquals(a, c1.get("id")); assertEquals("R1", c1.get("reference")); assertEquals(5, ((Number) c1.get("priority")).intValue());
        assertEquals(0, ((Number) c1.get("attempts")).intValue());
        assertEquals(Map.of("count", 0, "skipped", 2), cr.platform.queueTools.transfer(admin, "admin", qid, other, Map.of(), true, null), "the archive has both references");
        String third = (String) cr.platform.saveQueue(admin, null, Map.of("name", "Third"), null).get("id");
        assertEquals(1, cr.platform.queueTools.transfer(admin, "admin", qid, third, Map.of("tags", List.of("eu")), true, null).get("count"));
        assertEquals(List.of(b), items(qid, null).stream().map(i -> i.get("id")).toList());
        assertTrue(audited("queue.items_copy") && audited("queue.items_move"));

        // delete: finished items only unless forced
        db.update("UPDATE queue_items SET status = 'success', finished = ? WHERE queue_id = ?", Instant.now(), other);
        String waiting = add(Map.of("n", 3));
        assertEquals("CR.BAD_INPUT", code(() -> cr.platform.queueTools.deleteMatching(admin, "admin", qid, Map.of("status", "new"), null)));
        assertEquals(0, cr.platform.queueTools.deleteMatching(admin, "admin", qid, Map.of("status", "any"), null));
        assertEquals(2, cr.platform.queueTools.deleteMatching(admin, "admin", qid, Map.of("status", "new", "force", true, "ids", List.of(b, waiting)), null));
        assertEquals(0, cr.platform.queueTools.deleteMatching(admin, "admin", other, Map.of("older_than_hours", 1), null), "they finished just now");
        assertEquals(2, cr.platform.queueTools.deleteMatching(admin, "admin", other, Map.of(), null));
        assertTrue(audited("queue.items_delete"));
    }

    @Test void permissionsOfPeople() {
        String a = add(Map.of("n", 1));
        ControlRoom.User vic = user("vic", "viewer");
        assertEquals("CR.FORBIDDEN", code(() -> cr.platform.queueTools.deleteMatching(vic, "vic", qid, Map.of(), null)));
        assertEquals("CR.FORBIDDEN", code(() -> cr.platform.queueTools.unlock(vic, "vic", qid, List.of(a), null, null)));
        assertEquals("CR.FORBIDDEN", code(() -> cr.platform.queueTools.retag(vic, "vic", qid, List.of(a), "x", null, null)));
        // a member-only target queue: a runner who is no member may not add to it
        ControlRoom.User rita = user("rita", "bot_runner");
        String closed = (String) cr.platform.saveQueue(admin, null, Map.of("name", "Closed"), null).get("id");
        cr.platform.setQueueMembers(admin, closed, List.of(Map.of("principal", "user:admin", "role", "owner")), null);
        assertEquals("CR.FORBIDDEN", code(() -> cr.platform.queueTools.transfer(rita, "rita", qid, closed, Map.of(), false, null)));
    }

    @Test @SuppressWarnings("unchecked")
    void agentsCommandsWithExceptionKinds() {
        run("run_1", "running");
        assertEquals("added", bot("run_1", Map.of("op", "add_item", "queue", "Invoices", "data", Map.of("amount", 10), "reference", "INV-1", "tags", List.of("eu"))).get("outcome"));
        assertEquals("CR.DUPLICATE_REFERENCE", code(() -> bot("run_1", Map.of("op", "add_item", "queue", "Invoices", "data", Map.of(), "reference", "INV-1"))), "error is the default");
        bot("run_1", Map.of("op", "add_items", "queue", "Invoices", "items", List.of(Map.of("data", Map.of("amount", 20)), Map.of("data", Map.of("amount", 30), "reference", "INV-1")), "if_exists", "skip"));
        assertEquals(false, bot("run_1", Map.of("op", "get_next", "queue", "Invoices", "tags", List.of("none"))).get("found"));
        Map<String, Object> got = bot("run_1", Map.of("op", "get_next", "queue", "Invoices", "tags", List.of("EU")));
        assertEquals(true, got.get("found")); assertEquals("INV-1", got.get("reference"));
        String id = (String) got.get("item_id");
        assertEquals("run_1", db.one("SELECT run_id FROM queue_items WHERE id = ?", id).get("run_id"));
        // business: final at once; system: retried while attempts remain
        assertEquals(false, bot("run_1", Map.of("op", "fail", "queue", "Invoices", "item_id", id, "error", "no PO", "kind", "business")).get("will_retry"));
        Map<String, Object> it = cr.platform.item(admin, qid, id);
        assertEquals("failed", it.get("status")); assertEquals("business", it.get("error_kind"));
        assertEquals("QUEUE.BUSINESS_EXCEPTION", ((Map<String, Object>) it.get("error")).get("code"));
        String id2 = (String) bot("run_1", Map.of("op", "get_next", "queue", "Invoices")).get("item_id");
        assertEquals(true, bot("run_1", Map.of("op", "fail", "queue", "Invoices", "item_id", id2, "error", "portal down")).get("will_retry"));
        assertEquals("retry", cr.platform.item(admin, qid, id2).get("status"));
        assertEquals("system", cr.platform.item(admin, qid, id2).get("error_kind"));
        // an agent may unlock the item it took itself
        String id3 = (String) bot("run_1", Map.of("op", "get_next", "queue", "Invoices")).get("item_id");
        bot("run_1", Map.of("op", "unlock_item", "queue", "Invoices", "item_id", id3));
        assertEquals("new", cr.platform.item(admin, qid, id3).get("status"));
        assertTrue(db.one("SELECT actor FROM audit WHERE action = 'queue.items_unlock'").get("actor").toString().startsWith("agent:runner-1 for admin"));
        // listing, counts, completing
        List<Object> rows = (List<Object>) bot("run_1", Map.of("op", "list_items", "queue", "Invoices", "status", "failed")).get("rows");
        assertEquals(1, rows.size()); assertEquals("business", ((List<Object>) rows.get(0)).get(8));
        String id4 = (String) bot("run_1", Map.of("op", "get_next", "queue", "Invoices")).get("item_id");
        bot("run_1", Map.of("op", "complete", "queue", "Invoices", "item_id", id4, "result", Map.of("ok", true)));
        assertEquals(1L, ((Map<String, Object>) bot("run_1", Map.of("op", "counts", "queue", "Invoices")).get("counts")).get("success"));
        // only running agents, and only known queues
        assertEquals("CR.NOT_FOUND", code(() -> bot("run_1", Map.of("op", "counts", "queue", "Nope"))));
        run("run_done", "success");
        assertEquals("CR.FORBIDDEN", code(() -> bot("run_done", Map.of("op", "counts", "queue", "Invoices"))));
    }

    @Test void aDispatchedRunKeepsTheMarkItsAgentMade() {
        String a = add(Map.of("n", 1));
        db.update("UPDATE queue_items SET status = 'failed', error_kind = 'business', attempts = 1 WHERE id = ?", a);
        db.update("INSERT INTO runs (id, bot_path, version, device_id, status, inputs, requested_by, source, created, cancel_requested, pause_requested, queue_item_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                "run_q", "Invoices.flow.json", 1, "dev1", "failed", "{}", "admin", "queue", Instant.now(), false, false, a);
        cr.platform.itemFinished("run_q", "failed", Map.of("code", "X"), null);
        assertEquals("failed", db.one("SELECT status FROM queue_items WHERE id = ?", a).get("status"), "not put back for a retry");
        // a run that fails on its own: a system exception, retried as before
        String b = add(Map.of("n", 2));
        ValidationQueueTest.finish(cr, db, b, "run_b", null);
        db.update("UPDATE queue_items SET status = 'in_progress', attempts = 1 WHERE id = ?", b);
        db.update("INSERT INTO runs (id, bot_path, version, device_id, status, inputs, requested_by, source, created, cancel_requested, pause_requested, queue_item_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                "run_c", "Invoices.flow.json", 1, "dev1", "failed", "{}", "admin", "queue", Instant.now(), false, false, b);
        cr.platform.itemFinished("run_c", "failed", Map.of("code", "BUSINESS.BAD"), null);
        Map<String, Object> rb = db.one("SELECT status, error_kind FROM queue_items WHERE id = ?", b);
        assertEquals("retry", rb.get("status"), "an error code alone does not make a business exception");
        assertEquals("system", rb.get("error_kind"));
    }

    @Test @SuppressWarnings("unchecked")
    void encryptedItemData() {
        String plain = add(Map.of("iban", "DE89370400440532013000"));
        cr.platform.saveQueue(admin, qid, Map.of("name", "Invoices", "max_attempts", 3, "encrypt_data", true), null);
        String later = add(Map.of("iban", "GB29NWBK60161331926819", "_reference", "R1"));
        for (String id : List.of(plain, later)) {
            String stored = (String) db.one("SELECT data FROM queue_items WHERE id = ?", id).get("data");
            assertTrue(stored.startsWith("v1:") && !stored.contains("DE89") && !stored.contains("GB29"), "not readable in the table: " + stored);
        }
        assertEquals("GB29NWBK60161331926819", ((Map<String, Object>) cr.platform.item(admin, qid, later).get("data")).get("iban"), "readable through the API");
        assertTrue(String.valueOf(items(qid, null).get(0).get("data")).contains("GB29"));
        assertTrue(audited("queue.encrypt_on"));
        // a person without the right to view the queue reads nothing
        String closedQueue = qid;
        cr.platform.setQueueMembers(admin, closedQueue, List.of(Map.of("principal", "user:admin", "role", "owner")), null);
        ControlRoom.User rita = user("rita", "bot_runner");
        assertEquals("CR.FORBIDDEN", code(() -> cr.platform.item(rita, qid, later)));
        // agents get it decrypted, copies to a plain queue are plain, and turning it off decrypts in place
        run("run_1", "running");
        assertEquals("DE89370400440532013000", ((Map<String, Object>) bot("run_1", Map.of("op", "get_next", "queue", "Invoices")).get("data")).get("iban"));
        String plainQ = (String) cr.platform.saveQueue(admin, null, Map.of("name", "Plain"), null).get("id");
        cr.platform.queueTools.transfer(admin, "admin", qid, plainQ, Map.of(), false, null);
        assertTrue(String.valueOf(db.one("SELECT data FROM queue_items WHERE queue_id = ?", plainQ).get("data")).contains("GB29"));
        cr.platform.saveQueue(admin, qid, Map.of("name", "Invoices", "max_attempts", 3, "encrypt_data", false), null);
        assertEquals("GB29NWBK60161331926819", ((Map<String, Object>) JsonLite.parse((String) db.one("SELECT data FROM queue_items WHERE id = ?", later).get("data"))).get("iban"));
        // the CSV export is readable too
        assertTrue(cr.platform.exportCsv(admin, qid, null).contains("GB29NWBK60161331926819"));
    }
}
