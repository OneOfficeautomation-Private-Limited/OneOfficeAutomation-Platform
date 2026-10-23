package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;
import com.oneofficeautomation.sdk.LoopIterator;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Work queues: a dispatcher bot adds items, performer bots take them one by one, and each item ends completed or
 * failed, with automatic retries. Locally the queue is queues/<name>.json in the workspace, locked so several bots on
 * one device can share it safely; in Control Center the same commands use central queues with SLAs across devices.
 */
@OoaPackage(id = "ooa.workload", name = "Work queues", version = "1.0.0", description = "Add work items to a queue, take the next item, and mark items completed, failed or deferred with retries.")
public final class WorkloadPackage {
    private static final Object JVM_LOCK = new Object();
    private static final List<String> STATES = List.of("new", "in_progress", "completed", "failed", "deferred");

    private static Path file(ActionContext ctx, String queue) {
        if (!queue.matches("^[A-Za-z0-9_-]{1,64}$")) throw new ActionException("QUEUE.BAD_NAME", "Invalid queue name '" + queue + "'", "Use letters, digits, - and _.");
        return ctx.workDir().resolve("queues").resolve(queue + ".json");
    }

    interface Change<T> { T apply(List<Map<String, Object>> items); }

    /** Read-modify-write under a JVM lock and an OS file lock, then an atomic replace. */
    @SuppressWarnings("unchecked")
    static <T> T update(ActionContext ctx, String queue, boolean write, Change<T> change) {
        Path p = file(ctx, queue);
        synchronized (JVM_LOCK) {
            try {
                Files.createDirectories(p.getParent());
                try (FileChannel lockCh = FileChannel.open(p.resolveSibling(queue + ".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                     FileLock lock = lockCh.lock()) {
                    List<Map<String, Object>> items = new ArrayList<>();
                    if (Files.exists(p)) for (Object o : (List<Object>) ((Map<String, Object>) JsonLite.parse(Files.readString(p))).getOrDefault("items", List.of()))
                        items.add(new LinkedHashMap<>((Map<String, Object>) o));
                    T result = change.apply(items);
                    if (write) {
                        Path tmp = p.resolveSibling(queue + ".json.tmp");
                        Files.writeString(tmp, JsonLite.write(Map.of("queue", queue, "items", items)), StandardCharsets.UTF_8);
                        Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    }
                    return result;
                }
            } catch (IOException e) { throw new ActionException("QUEUE.IO_ERROR", "Queue '" + queue + "' could not be read or saved: " + e.getMessage(), null, true); }
        }
    }

    private static String now() { return LocalDateTime.now(ZoneOffset.UTC).withNano(0).toString(); }

    private static Map<String, Object> find(List<Map<String, Object>> items, String id) {
        return items.stream().filter(i -> id.equals(i.get("id"))).findFirst()
                .orElseThrow(() -> new ActionException("QUEUE.ITEM_NOT_FOUND", "Work item '" + id + "' not found", "Use the item_id from Get next item."));
    }

    private static Map<String, Object> newItem(Object data) {
        Map<String, Object> it = new LinkedHashMap<>();
        it.put("id", "wi_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        it.put("status", "new");
        it.put("attempts", BigDecimal.ZERO);
        it.put("data", Values.toJsonable(data));
        it.put("created", now());
        it.put("updated", now());
        return it;
    }

    @Action(id = "add_item", name = "Add work item", category = "Queue", description = "Add one item (a Dictionary of fields) to a queue.")
    @Output(name = "item_id", type = OoaType.STRING, description = "New item id.")
    public Map<String, Object> addItem(ActionContext ctx,
            @Input(name = "queue", type = OoaType.STRING, example = "invoices", description = "Queue name.") String queue,
            @Input(name = "data", type = OoaType.DICTIONARY, description = "Item fields, e.g. {\"invoice\": \"INV-7\", \"amount\": 120}.") Map<String, Object> data) {
        return Map.of("item_id", update(ctx, queue, true, items -> { Map<String, Object> it = newItem(data); items.add(it); return it.get("id"); }));
    }

    @Action(id = "add_items", name = "Add work items from table", category = "Queue", description = "Add one item per table row (columns become fields).")
    @Output(name = "count", type = OoaType.NUMBER, description = "Items added.")
    public Map<String, Object> addItems(ActionContext ctx,
            @Input(name = "queue", type = OoaType.STRING, description = "Queue name.") String queue,
            @Input(name = "table", type = OoaType.TABLE, description = "Rows to add.") Map<String, Object> table) {
        List<?> cols = (List<?>) table.get("columns");
        List<?> rows = (List<?>) table.get("rows");
        update(ctx, queue, true, items -> {
            for (Object r : rows) {
                Map<String, Object> d = new LinkedHashMap<>();
                List<?> row = (List<?>) r;
                for (int i = 0; i < cols.size(); i++) d.put(String.valueOf(cols.get(i)), i < row.size() ? row.get(i) : null);
                items.add(newItem(d));
            }
            return null;
        });
        return Map.of("count", BigDecimal.valueOf(rows.size()));
    }

    @Action(id = "get_next", name = "Get next item", category = "Queue",
            description = "Take the oldest available item (new, or deferred and due) and mark it in progress; found is false when the queue is empty.")
    @Output(name = "found", type = OoaType.BOOLEAN, description = "True when an item was taken.")
    @Output(name = "item_id", type = OoaType.STRING, description = "Item id.")
    @Output(name = "data", type = OoaType.DICTIONARY, description = "Item fields.")
    @Output(name = "attempt", type = OoaType.NUMBER, description = "Which attempt this is (1 = first).")
    public Map<String, Object> getNext(ActionContext ctx, @Input(name = "queue", type = OoaType.STRING, description = "Queue name.") String queue) {
        return update(ctx, queue, true, items -> {
            String t = now();
            for (Map<String, Object> it : items) {
                boolean due = "new".equals(it.get("status")) || ("deferred".equals(it.get("status")) && String.valueOf(it.getOrDefault("defer_until", "")).compareTo(t) <= 0);
                if (!due) continue;
                int attempt = Values.toNumber(it.getOrDefault("attempts", BigDecimal.ZERO)).intValue() + 1;
                it.put("status", "in_progress"); it.put("attempts", BigDecimal.valueOf(attempt)); it.put("updated", t); it.put("run_id", ctx.runId());
                return Out.of("found", true, "item_id", it.get("id"), "data", it.get("data"), "attempt", BigDecimal.valueOf(attempt));
            }
            return Out.of("found", false, "item_id", "", "data", new LinkedHashMap<>(), "attempt", BigDecimal.ZERO);
        });
    }

    @Action(id = "complete", name = "Mark completed", category = "Item", description = "Mark an item completed, optionally storing result fields.")
    public Map<String, Object> complete(ActionContext ctx,
            @Input(name = "queue", type = OoaType.STRING, description = "Queue name.") String queue,
            @Input(name = "item_id", type = OoaType.STRING, description = "Item id.") String id,
            @Input(name = "result", type = OoaType.DICTIONARY, required = false, description = "Result fields to keep with the item.") Map<String, Object> result) {
        update(ctx, queue, true, items -> { Map<String, Object> it = find(items, id); it.put("status", "completed"); it.put("updated", now()); if (result != null) it.put("result", Values.toJsonable(result)); return null; });
        return Map.of();
    }

    @Action(id = "fail", name = "Mark failed", category = "Item",
            description = "Record a failure; the item goes back to the queue for another attempt until max_attempts is reached.")
    @Output(name = "will_retry", type = OoaType.BOOLEAN, description = "True when the item was put back for another attempt.")
    public Map<String, Object> fail(ActionContext ctx,
            @Input(name = "queue", type = OoaType.STRING, description = "Queue name.") String queue,
            @Input(name = "item_id", type = OoaType.STRING, description = "Item id.") String id,
            @Input(name = "error", type = OoaType.STRING, required = false, defaultValue = "\"\"", description = "What went wrong (shown to operators).") String error,
            @Input(name = "max_attempts", type = OoaType.NUMBER, required = false, defaultValue = "3", min = 1, description = "Total attempts allowed.") int max) {
        boolean retry = update(ctx, queue, true, items -> {
            Map<String, Object> it = find(items, id);
            boolean again = Values.toNumber(it.getOrDefault("attempts", BigDecimal.ZERO)).intValue() < max;
            it.put("status", again ? "new" : "failed"); it.put("error", ctx.redact(error)); it.put("updated", now());
            return again;
        });
        return Map.of("will_retry", retry);
    }

    @Action(id = "defer", name = "Defer item", category = "Item", description = "Put an item aside until a date and time (UTC), e.g. when a supplier system is closed.")
    public Map<String, Object> defer(ActionContext ctx,
            @Input(name = "queue", type = OoaType.STRING, description = "Queue name.") String queue,
            @Input(name = "item_id", type = OoaType.STRING, description = "Item id.") String id,
            @Input(name = "until", type = OoaType.DATETIME, description = "When it becomes available again.") LocalDateTime until) {
        update(ctx, queue, true, items -> { Map<String, Object> it = find(items, id); it.put("status", "deferred"); it.put("defer_until", until.withNano(0).toString()); it.put("updated", now()); return null; });
        return Map.of();
    }

    /** "On hold" without an end date: deferred until far in the future, so only Update work item brings it back. */
    static final String HOLD_FOREVER = "9999-12-31T23:59:59";

    @Action(id = "update_item", name = "Update work item", category = "Item",
            description = "Change the fields of a work item and optionally send it back for processing or put it on hold (until a time or indefinitely).")
    @Output(name = "status", type = OoaType.STRING, description = "The item's status after the change.")
    public Map<String, Object> updateItem(ActionContext ctx,
            @Input(name = "queue", type = OoaType.STRING, description = "Queue name.") String queue,
            @Input(name = "item_id", type = OoaType.STRING, description = "Item id.") String id,
            @Input(name = "data", type = OoaType.DICTIONARY, required = false, description = "Fields to change; other fields keep their values unless replace_data is on.") Map<String, Object> data,
            @Input(name = "replace_data", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Replace all fields with data instead of merging.") boolean replace,
            @Input(name = "status", type = OoaType.STRING, required = false, defaultValue = "\"unchanged\"", enumValues = {"unchanged", "reprocess", "on_hold"},
                    description = "reprocess: back to new so it is taken again (attempts restart); on_hold: put aside until on_hold_until, or until changed again.") String status,
            @Input(name = "on_hold_until", type = OoaType.DATETIME, required = false, visibleWhen = "status=on_hold", description = "When an on-hold item becomes available again (UTC); held indefinitely when empty.") LocalDateTime until,
            @Input(name = "result", type = OoaType.DICTIONARY, required = false, description = "Result fields to keep with the item.") Map<String, Object> result) {
        String st = status == null || status.isBlank() ? "unchanged" : status;
        if (!Set.of("unchanged", "reprocess", "on_hold").contains(st)) throw new ActionException("QUEUE.BAD_STATUS", "Unknown status '" + status + "'", "Use unchanged, reprocess or on_hold.");
        if (until != null && !st.equals("on_hold")) throw new ActionException("QUEUE.BAD_STATUS", "on_hold_until needs status on_hold");
        if (until != null && until.isBefore(LocalDateTime.now(ZoneOffset.UTC))) throw new ActionException("QUEUE.BAD_TIME", "on_hold_until is in the past", "Give a future time (UTC).");
        @SuppressWarnings("unchecked")
        String after = update(ctx, queue, true, items -> {
            Map<String, Object> it = find(items, id);
            if (data != null) {
                Map<String, Object> d = replace || !(it.get("data") instanceof Map) ? new LinkedHashMap<>() : new LinkedHashMap<>((Map<String, Object>) it.get("data"));
                d.putAll((Map<String, Object>) Values.toJsonable(data));
                it.put("data", d);
            }
            if (result != null) it.put("result", Values.toJsonable(result));
            if (st.equals("reprocess")) { it.put("status", "new"); it.put("attempts", BigDecimal.ZERO); it.remove("defer_until"); it.remove("error"); }
            if (st.equals("on_hold")) { it.put("status", "deferred"); it.put("defer_until", until == null ? HOLD_FOREVER : until.withNano(0).toString()); }
            it.put("updated", now());
            return String.valueOf(it.get("status"));
        });
        return Map.of("status", after);
    }

    @Action(id = "get_item", name = "Get work item", category = "Item", idempotent = true, description = "Read one work item's fields, status, attempts and result.")
    @Output(name = "item", type = OoaType.DICTIONARY, description = "id, status, attempts, data, result, error, created, updated, defer_until.")
    public Map<String, Object> getItem(ActionContext ctx,
            @Input(name = "queue", type = OoaType.STRING, description = "Queue name.") String queue,
            @Input(name = "item_id", type = OoaType.STRING, description = "Item id.") String id) {
        return Map.of("item", update(ctx, queue, false, items -> new LinkedHashMap<>(find(items, id))));
    }

    @Action(id = "counts", name = "Get queue counts", category = "Queue", idempotent = true, description = "Count items by status.")
    @Output(name = "counts", type = OoaType.DICTIONARY, description = "new, in_progress, completed, failed, deferred, total.")
    public Map<String, Object> counts(ActionContext ctx, @Input(name = "queue", type = OoaType.STRING, description = "Queue name.") String queue) {
        return Map.of("counts", update(ctx, queue, false, items -> {
            Map<String, Object> c = new LinkedHashMap<>();
            for (String s : STATES) c.put(s, BigDecimal.valueOf(items.stream().filter(i -> s.equals(i.get("status"))).count()));
            c.put("total", BigDecimal.valueOf(items.size()));
            return c;
        }));
    }

    @Action(id = "list_items", name = "List items", category = "Queue", idempotent = true, description = "List items, optionally only one status, as a table.")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: id, status, attempts, updated, error, data.")
    public Map<String, Object> listItems(ActionContext ctx,
            @Input(name = "queue", type = OoaType.STRING, description = "Queue name.") String queue,
            @Input(name = "status", type = OoaType.STRING, required = false, enumValues = {"new", "in_progress", "completed", "failed", "deferred"}, description = "Only this status.") String status) {
        return Map.of("table", update(ctx, queue, false, items -> {
            List<Object> rows = new ArrayList<>();
            for (Map<String, Object> i : items) if (status == null || status.equals(i.get("status")))
                rows.add(new ArrayList<>(Arrays.asList(i.get("id"), i.get("status"), i.get("attempts"), i.get("updated"), i.get("error"), i.get("data"))));
            return Values.table(List.of("id", "status", "attempts", "updated", "error", "data"), rows);
        }));
    }

    @LoopIterator(id = "each_item", name = "Each available work item", description = "Take items one by one until the queue is empty; mark each completed or failed inside the loop.")
    public Iterable<Object> eachItem(ActionContext ctx,
            @Input(name = "queue", type = OoaType.STRING, description = "Queue name.") String queue,
            @Input(name = "max_items", type = OoaType.NUMBER, required = false, defaultValue = "1000", min = 1, description = "Stop after this many items.") int max) {
        return () -> new Iterator<>() {
            int taken = 0; Map<String, Object> next;
            public boolean hasNext() {
                if (next == null && taken < max) { Map<String, Object> r = getNext(ctx, queue); if (Boolean.TRUE.equals(r.get("found"))) next = r; }
                return next != null;
            }
            public Object next() {
                if (!hasNext()) throw new NoSuchElementException();
                Map<String, Object> r = next; next = null; taken++;
                return Out.of("item_id", r.get("item_id"), "data", r.get("data"), "attempt", r.get("attempt"));
            }
        };
    }

    @Condition(id = "has_items", name = "Queue has items", description = "True when the queue has items waiting (new, or deferred and due).")
    public boolean hasItems(ActionContext ctx, @Input(name = "queue", type = OoaType.STRING, description = "Queue name.") String queue) {
        String t = now();
        return update(ctx, queue, false, items -> items.stream().anyMatch(i -> "new".equals(i.get("status"))
                || ("deferred".equals(i.get("status")) && String.valueOf(i.getOrDefault("defer_until", "")).compareTo(t) <= 0)));
    }
}
