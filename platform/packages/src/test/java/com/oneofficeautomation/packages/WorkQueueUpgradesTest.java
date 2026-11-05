package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** Gap G31: tags, unlock, delete, copy and move, waiting for an item, unique references, business and system exceptions (local queue file). */
class WorkQueueUpgradesTest {
    @TempDir Path w;
    final WorkloadPackage q = new WorkloadPackage();

    ActionContext ctx() { return PackagesTestSupport.ctx(w, Map.of()); }

    String add(ActionContext c, String queue, Map<String, Object> data, String ref, List<Object> tags) {
        return (String) q.addItem(c, queue, data, ref, null, tags, false).get("item_id");
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> item(ActionContext c, String queue, String id) { return (Map<String, Object>) q.getItem(c, queue, id, false).get("item"); }

    static List<List<Object>> rows(Object table) {
        List<List<Object>> out = new ArrayList<>();
        for (Object r : (List<?>) ((Map<?, ?>) table).get("rows")) out.add(new ArrayList<>((List<?>) r));
        return out;
    }

    static String code(Runnable r) { return assertThrows(OoaException.class, r::run).code(); }

    @Test void tagsFilterGetNextAndList() {
        var c = ctx();
        add(c, "inv", Map.of("n", 1), null, List.of("north"));
        String b = add(c, "inv", Map.of("n", 2), null, List.of("south", "Urgent"));
        add(c, "inv", Map.of("n", 3), null, null);
        assertEquals(1, rows(q.listItems(c, "inv", null, List.of("urgent", "SOUTH"), false).get("table")).size(), "every tag given must be there, case ignored");
        Map<String, Object> next = q.getNext(c, "inv", List.of("urgent"), 0, false);
        assertEquals(b, next.get("item_id"), "the older items lack the tag");
        assertEquals(List.of("south", "Urgent"), next.get("tags"));
        assertEquals(List.of("south", "Urgent", "late"), q.tagItem(c, "inv", b, List.of("late", "SOUTH"), false).get("tags"), "a tag it has is not added twice");
        assertEquals(List.of("late"), q.untagItem(c, "inv", b, List.of("south", "urgent"), false).get("tags"));
        assertEquals(false, q.getNext(c, "inv", List.of("nowhere"), 0, false).get("found"));
        List<Object> listRow = rows(q.listItems(c, "inv", "in_progress", null, false).get("table")).get(0);
        assertEquals(WorkloadPackage.LIST_COLUMNS.size(), listRow.size());
        assertEquals(List.of("late"), listRow.get(7));
        assertEquals("QUEUE.BAD_TAG", code(() -> q.tagItem(c, "inv", b, List.of("a,b"), false)));
    }

    @Test void uniqueReferenceErrorSkipUpdate() {
        var c = ctx();
        String a = add(c, "inv", Map.of("amount", 10), "INV-1", null);
        assertEquals("QUEUE.DUPLICATE_REFERENCE", code(() -> add(c, "inv", Map.of("amount", 11), "INV-1", null)));
        Map<String, Object> s = q.addItem(c, "inv", Map.of("amount", 12), "INV-1", "skip", null, false);
        assertEquals(a, s.get("item_id")); assertEquals("skipped", s.get("outcome"));
        assertEquals(Map.of("amount", BigDecimal.TEN), item(c, "inv", a).get("data"));
        Map<String, Object> u = q.addItem(c, "inv", Map.of("amount", 13), "INV-1", "update", List.of("fixed"), false);
        assertEquals("updated", u.get("outcome"));
        assertEquals(Map.of("amount", BigDecimal.valueOf(13)), item(c, "inv", a).get("data"));
        assertEquals(List.of("fixed"), item(c, "inv", a).get("tags"));
        assertEquals("added", q.addItem(c, "inv", Map.of("amount", 1), "INV-2", null, null, false).get("outcome"));
        // a table: all or nothing on error, counted on skip
        Map<String, Object> t = Values.table(List.of("ref", "amount"), List.of(List.of("INV-3", 3), List.of("INV-1", 9)));
        assertEquals("QUEUE.DUPLICATE_REFERENCE", code(() -> q.addItems(c, "inv", t, "ref", null, null, false)));
        assertFalse(rows(q.listItems(c, "inv", null).get("table")).stream().anyMatch(r -> "INV-3".equals(r.get(6))), "nothing was added");
        Map<String, Object> r = q.addItems(c, "inv", t, "ref", "skip", List.of("batch"), false);
        assertEquals(BigDecimal.ONE, r.get("count")); assertEquals(BigDecimal.ONE, r.get("skipped"));
        assertEquals(BigDecimal.valueOf(3), ((Map<?, ?>) q.counts(c, "inv").get("counts")).get("total"));
        assertEquals("QUEUE.BAD_INPUT", code(() -> q.addItems(c, "inv", t, "nope", null, null, false)));
    }

    @Test void businessExceptionsAreNotRetried() {
        var c = ctx();
        String a = add(c, "inv", Map.of("n", 1), null, null);
        q.getNext(c, "inv");
        assertEquals(false, q.fail(c, "inv", a, "amount missing", 3, "business", false).get("will_retry"));
        assertEquals("failed", item(c, "inv", a).get("status"));
        assertEquals("business", item(c, "inv", a).get("error_kind"));
        String b = add(c, "inv", Map.of("n", 2), null, null);
        q.getNext(c, "inv");
        assertEquals(true, q.fail(c, "inv", b, "portal down", 3, "system", false).get("will_retry"));
        assertEquals("system", item(c, "inv", b).get("error_kind"));
        assertEquals(true, q.fail(c, "inv", b, "portal down", 3, null, false).get("will_retry"), "system is the default, as before");
        assertEquals("QUEUE.BAD_INPUT", code(() -> q.fail(c, "inv", b, "x", 3, "other", false)));
    }

    @Test void unlockKeepsAttempts() {
        var c = ctx();
        String a = add(c, "inv", Map.of("n", 1), null, null);
        q.getNext(c, "inv");
        assertEquals("QUEUE.NOT_IN_PROGRESS", code(() -> q.unlockItem(c, "inv", add(c, "inv", Map.of("n", 2), null, null), false)));
        q.unlockItem(c, "inv", a, false);
        assertEquals("new", item(c, "inv", a).get("status"));
        Map<String, Object> again = q.getNext(c, "inv");
        assertEquals(a, again.get("item_id"));
        assertEquals(BigDecimal.valueOf(2), again.get("attempt"), "attempts are kept");
    }

    @Test void deleteFinishedOnlyUnlessForced() {
        var c = ctx();
        String done = add(c, "inv", Map.of("n", 1), null, List.of("x"));
        add(c, "inv", Map.of("n", 2), null, List.of("x"));
        q.getNext(c, "inv");
        q.complete(c, "inv", done, null);
        assertEquals("QUEUE.NEEDS_FORCE", code(() -> q.deleteItems(c, "inv", "new", 0, null, null, false, false)));
        assertEquals(BigDecimal.ZERO, q.deleteItems(c, "inv", "finished", 1, null, null, false, false).get("count"), "the completed item is newer than an hour");
        assertEquals(BigDecimal.ONE, q.deleteItems(c, "inv", "any", 0, List.of("x"), null, false, false).get("count"), "without force: finished items only");
        assertEquals(BigDecimal.ONE, q.deleteItems(c, "inv", "any", 0, null, null, true, false).get("count"));
        assertEquals(BigDecimal.ZERO, ((Map<?, ?>) q.counts(c, "inv").get("counts")).get("total"));
    }

    @Test void copyAndMoveToAnotherQueue() {
        var c = ctx();
        String a = add(c, "inv", Map.of("n", 1), "R1", List.of("eu"));
        add(c, "inv", Map.of("n", 2), "R2", null);
        String busy = add(c, "inv", Map.of("n", 3), "R3", List.of("eu"));
        q.getNext(c, "inv"); q.unlockItem(c, "inv", a, false);   // a keeps one attempt
        add(c, "other", Map.of("n", 9), "R2", null);
        Map<String, Object> copied = q.copyItems(c, "inv", "other", null, null, null, false);
        assertEquals(BigDecimal.valueOf(2), copied.get("count")); assertEquals(BigDecimal.ONE, copied.get("skipped"), "R2 is there already");
        List<List<Object>> other = rows(q.listItems(c, "other", null).get("table"));
        List<Object> r1 = other.stream().filter(r -> "R1".equals(r.get(6))).findFirst().orElseThrow();
        assertNotEquals(a, r1.get(0), "a copy has a new id");
        assertEquals(BigDecimal.ZERO, r1.get(2), "attempts start again");
        assertEquals(List.of("eu"), r1.get(7));
        q.getNext(c, "inv", List.of("eu"), 0, false);   // a is in progress again: it is not moved
        Map<String, Object> moved = q.moveItems(c, "inv", "third", null, List.of("eu"), null, false);
        assertEquals(BigDecimal.ONE, moved.get("count"));
        assertEquals("R3", rows(q.listItems(c, "third", null).get("table")).get(0).get(6), "the item that was free moved");
        assertTrue(rows(q.listItems(c, "inv", null).get("table")).stream().noneMatch(r -> busy.equals(r.get(0))));
        assertEquals(BigDecimal.valueOf(2), ((Map<?, ?>) q.counts(c, "inv").get("counts")).get("total"));
        assertEquals("QUEUE.BAD_INPUT", code(() -> q.copyItems(c, "inv", "inv", null, null, null, false)));
    }

    @Test void getNextWaitsForAnItem() throws Exception {
        var c = ctx();
        long t0 = System.nanoTime();
        assertEquals(false, q.getNext(c, "inv", null, 1, false).get("found"), "empty after waiting");
        assertTrue(System.nanoTime() - t0 >= 900_000_000L, "it waited about a second");
        Thread adder = new Thread(() -> { try { Thread.sleep(700); } catch (InterruptedException ignored) { } add(ctx(), "inv", Map.of("late", true), null, null); });
        adder.start();
        Map<String, Object> got = q.getNext(c, "inv", null, 20, false);
        adder.join();
        assertEquals(true, got.get("found"));
        assertEquals(Map.of("late", true), got.get("data"));
        assertTrue(System.nanoTime() - t0 < 15_000_000_000L, "it returned when the item arrived");
        // the run's stop ends the wait
        AtomicBoolean stop = new AtomicBoolean();
        var stoppable = new ActionContext("run_test", "s1", "default", w, new HashMap<>(), new HashMap<>(), stop, List::of, (l, m) -> {});
        new Thread(() -> { try { Thread.sleep(300); } catch (InterruptedException ignored) { } stop.set(true); }).start();
        assertEquals("RUN.CANCELLED", code(() -> q.getNext(stoppable, "inv", null, 60, false)));
        assertEquals("QUEUE.BAD_INPUT", code(() -> q.getNext(c, "inv", null, 99999, false)));
    }

    @SuppressWarnings("unchecked")
    @Test void centralCommandsGoThroughTheRelay() {
        assertEquals("QUEUE.NO_CENTRAL", code(() -> q.counts(ctx(), "Invoices", true)), "a run outside One CommandCenter has no central queues");
        List<Map<String, Object>> sent = new ArrayList<>();
        Function<Map<String, Object>, Map<String, Object>> relay = req -> {
            sent.add(req);
            return switch (String.valueOf(req.get("op"))) {
                case "get_next" -> Map.of("found", true, "item_id", "item_1", "data", Map.of("a", 1), "attempt", 1, "reference", "R", "tags", List.of("t"));
                case "fail" -> Map.of("will_retry", false);
                case "list_items" -> Map.of("rows", List.of(List.of("item_1", "new", 0, "", "", Map.of(), "R", List.of(), "")));
                default -> Map.of("item_id", "item_2", "outcome", "added");
            };
        };
        var c = PackagesTestSupport.ctx(w, Map.of("work_queues", relay));
        assertEquals("item_2", q.addItem(c, "Invoices", Map.of("x", 1), "R9", "skip", List.of("eu"), true).get("item_id"));
        assertEquals(Set.of("op", "queue", "data", "reference", "if_exists", "tags"), sent.get(0).keySet());
        assertEquals("R9", sent.get(0).get("reference")); assertEquals("skip", sent.get(0).get("if_exists")); assertEquals(List.of("eu"), sent.get(0).get("tags"));
        assertEquals("item_1", q.getNext(c, "Invoices", List.of("t"), 0, true).get("item_id"));
        assertEquals(false, q.fail(c, "Invoices", "item_1", "bad", 3, "business", true).get("will_retry"));
        assertEquals("business", sent.get(2).get("kind"));
        assertEquals(1, rows(q.listItems(c, "Invoices", null, null, true).get("table")).size());
    }
}
