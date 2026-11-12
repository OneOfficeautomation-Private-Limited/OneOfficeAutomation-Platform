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
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Work queues: a dispatcher bot adds items, performer bots take them one by one, and each item ends completed or
 * failed, with automatic retries. Locally the queue is queues/<name>.json in the workspace, locked so several bots on
 * one device can share it safely. With "central" on, the same commands work a One CommandCenter queue of that name
 * (runs started from One CommandCenter only; the runner relays each command as the run's user).
 *
 * <p>G31 (4 Nov 2026), as UiPath's and Blue Prism's queues: tags on items (filters match items that have every tag
 * given), unlock, delete by filter, copy and move to another queue, waiting for an item, a reference that is unique in
 * its queue, and business exceptions (final, never retried) apart from system exceptions (retried). Encrypted item
 * data is a setting of One CommandCenter queues; a local queue file is protected by the workspace's file permissions.
 */
@OoaPackage(id = "ooa.workload", name = "Work queues", version = "1.0.0", description = "Add work items to a queue, take the next item, and mark items completed, failed or deferred with retries.")
public final class WorkloadPackage {
    private static final Object JVM_LOCK = new Object();
    private static final List<String> STATES = List.of("new", "in_progress", "completed", "failed", "deferred");
    static final int MAX_TAGS = 20, MAX_WAIT = 3600;

    private static final String CENTRAL = "Use the One CommandCenter queue with this name instead of the workspace's own file (runs started from One CommandCenter only).";

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
            } catch (IOException e) { throw new ActionException("QUEUE.IO_ERROR", "Queue '" + queue + "' could not be read or saved: " + e.getMessage(), "Check that the file or folder exists, is not open in another program, and that the runner's account may read and write it.", true); }
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

    // ----- tags, references and the One CommandCenter relay -----

    /** Tags as given, trimmed, without repeats (ignoring case); at most 20 of at most 50 characters, without commas. */
    static List<String> tags(Object v) {
        List<String> out = new ArrayList<>();
        if (v == null) return out;
        Collection<?> raw = v instanceof Collection<?> c ? c : Arrays.asList(String.valueOf(v).split(","));
        for (Object o : raw) {
            String t = o == null ? "" : String.valueOf(o).trim();
            if (t.isEmpty()) continue;
            if (t.length() > 50 || t.contains(",")) throw new ActionException("QUEUE.BAD_TAG", "Tag '" + t + "' is not allowed", "Tags have at most 50 characters and no commas.");
            if (out.stream().noneMatch(x -> x.equalsIgnoreCase(t))) out.add(t);
        }
        if (out.size() > MAX_TAGS) throw new ActionException("QUEUE.BAD_TAG", "An item has at most " + MAX_TAGS + " tags", "Give an item at most the number of tags the message names.");
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<String> itemTags(Map<String, Object> it) { return it.get("tags") instanceof List<?> l ? (List<String>) l : List.of(); }

    /** True when the item has every tag wanted (ignoring case); no tags wanted: every item. */
    static boolean hasTags(Map<String, Object> it, List<String> wanted) {
        for (String w : wanted) if (itemTags(it).stream().noneMatch(t -> t.equalsIgnoreCase(w))) return false;
        return true;
    }

    private static String ifExists(String v) {
        String s = v == null || v.isBlank() ? "error" : v;
        if (!Set.of("error", "skip", "update").contains(s)) throw new ActionException("QUEUE.BAD_INPUT", "if_exists is error, skip or update, not '" + v + "'", "Set if_exists to error, skip or update.");
        return s;
    }

    private static String kind(String v) {
        String s = v == null || v.isBlank() ? "system" : v;
        if (!Set.of("business", "system").contains(s)) throw new ActionException("QUEUE.BAD_INPUT", "kind is business or system, not '" + v + "'", "Set kind to business or system.");
        return s;
    }

    /**
     * Adds an item with an optional reference: a reference is unique in its queue, so an item with the same one is
     * refused (error), left as it is (skip) or given the new data and tags (update). Returns {item_id, outcome}.
     */
    private static Map<String, Object> addOne(List<Map<String, Object>> items, Object data, String reference, List<String> tags, String ifExists) {
        String ref = reference == null || reference.isBlank() ? null : reference.trim();
        if (ref != null && ref.length() > 200) throw new ActionException("QUEUE.BAD_INPUT", "A reference has at most 200 characters", "Shorten the reference to 200 characters or fewer.");
        Map<String, Object> same = ref == null ? null : items.stream().filter(i -> ref.equals(i.get("reference"))).findFirst().orElse(null);
        if (same != null) {
            switch (ifExists) {
                case "skip" -> { return Out.of("item_id", same.get("id"), "outcome", "skipped"); }
                case "update" -> {
                    same.put("data", Values.toJsonable(data));
                    if (!tags.isEmpty()) same.put("tags", new ArrayList<>(tags));
                    same.put("updated", now());
                    return Out.of("item_id", same.get("id"), "outcome", "updated");
                }
                default -> throw new ActionException("QUEUE.DUPLICATE_REFERENCE", "The queue already has an item with reference '" + ref + "'",
                        "Give each item its own reference, or set if_exists to skip or update.");
            }
        }
        Map<String, Object> it = newItem(data);
        if (ref != null) it.put("reference", ref);
        if (!tags.isEmpty()) it.put("tags", new ArrayList<>(tags));
        items.add(it);
        return Out.of("item_id", it.get("id"), "outcome", "added");
    }

    /** One command on a One CommandCenter queue, through the run's relay ("work_queues" service). */
    @SuppressWarnings("unchecked")
    static Map<String, Object> central(ActionContext ctx, String op, String queue, Object... kv) {
        Function<Map<String, Object>, Map<String, Object>> relay;
        try { relay = ctx.service("work_queues", Function.class); }
        catch (OoaException e) {
            throw new ActionException("QUEUE.NO_CENTRAL", "One CommandCenter work queues can only be used by runs started from One CommandCenter",
                    "Turn central off to use the queue file in the workspace, or start the agent from One CommandCenter.");
        }
        if (queue == null || queue.isBlank()) throw new ActionException("QUEUE.BAD_NAME", "Give the name of the One CommandCenter queue", "Fill in the queue's name as Manage > Work queues shows it.");
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("op", op);
        req.put("queue", queue);
        for (int i = 0; i < kv.length; i += 2) if (kv[i + 1] != null) req.put((String) kv[i], Values.toJsonable(kv[i + 1]));
        return relay.apply(req);
    }

    /** Takes an item now, or asks again until one arrives or wait_seconds have passed (the run's stop ends the wait). */
    static Map<String, Object> waitFor(ActionContext ctx, int waitSeconds, long pollMillis, Supplier<Map<String, Object>> take) {
        if (waitSeconds < 0 || waitSeconds > MAX_WAIT) throw new ActionException("QUEUE.BAD_INPUT", "wait_seconds is from 0 to " + MAX_WAIT, "Set wait_seconds within the range the message names.");
        long deadline = System.nanoTime() + waitSeconds * 1_000_000_000L;
        while (true) {
            Map<String, Object> r = take.get();
            long left = (deadline - System.nanoTime()) / 1_000_000;
            if (Boolean.TRUE.equals(r.get("found")) || left <= 0) return r;
            ctx.checkCancelled();
            try { Thread.sleep(Math.min(pollMillis, left)); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); ctx.checkCancelled(); return r; }
            ctx.checkCancelled();
        }
    }

    // ----- commands -----

    /** Before G31 (kept for callers in Java). */
    public Map<String, Object> addItem(ActionContext ctx, String queue, Map<String, Object> data) { return addItem(ctx, queue, data, null, null, null, false); }

    @Action(id = "add_item", name = "Add work item", category = "Queue", description = "Add one item (a Dictionary of fields) to a queue, optionally with a reference that is unique in the queue and tags.",
            panel = "queue, data | reference, if_exists, tags, central")
    @Output(name = "item_id", type = OoaType.STRING, description = "New item id (or the id of the item with the same reference).")
    @Output(name = "outcome", type = OoaType.STRING, description = "added, skipped (the reference was there and if_exists is skip) or updated.")
    public Map<String, Object> addItem(ActionContext ctx,
            @Input(name = "queue", type = OoaType.STRING, example = "invoices", description = "Queue name.") String queue,
            @Input(name = "data", type = OoaType.DICTIONARY, description = "Item fields, e.g. {\"invoice\": \"INV-7\", \"amount\": 120}.") Map<String, Object> data,
            @Input(name = "reference", type = OoaType.STRING, required = false, description = "A name for the item that is unique in the queue, e.g. the invoice number.") String reference,
            @Input(name = "if_exists", type = OoaType.STRING, required = false, defaultValue = "\"error\"", enumValues = {"error", "skip", "update"},
                    description = "When an item with this reference is already there: error stops, skip leaves it, update gives it the new data and tags.") String ifExists,
            @Input(name = "tags", type = OoaType.LIST, required = false, description = "Labels to find the item by, e.g. [\"urgent\", \"north\"].") List<Object> tags,
            @Input(name = "central", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = CENTRAL) boolean central) {
        String mode = ifExists(ifExists);
        List<String> t = tags(tags);
        if (central) return central(ctx, "add_item", queue, "data", data, "reference", reference, "if_exists", mode, "tags", t);
        return update(ctx, queue, true, items -> addOne(items, data, reference, t, mode));
    }

    /** Before G31 (kept for callers in Java). */
    public Map<String, Object> addItems(ActionContext ctx, String queue, Map<String, Object> table) { return addItems(ctx, queue, table, null, null, null, false); }

    @Action(id = "add_items", name = "Add work items from table", category = "Queue", description = "Add one item per table row (columns become fields), optionally with a reference column and tags.",
            panel = "queue, table | reference_column, if_exists, tags, central")
    @Output(name = "count", type = OoaType.NUMBER, description = "Items added (or updated).")
    @Output(name = "skipped", type = OoaType.NUMBER, description = "Rows left out because their reference was there and if_exists is skip.")
    public Map<String, Object> addItems(ActionContext ctx,
            @Input(name = "queue", type = OoaType.STRING, description = "Queue name.") String queue,
            @Input(name = "table", type = OoaType.TABLE, description = "Rows to add.") Map<String, Object> table,
            @Input(name = "reference_column", type = OoaType.STRING, required = false, description = "The column that holds each row's reference (unique in the queue); it stays a field too.") String refColumn,
            @Input(name = "if_exists", type = OoaType.STRING, required = false, defaultValue = "\"error\"", enumValues = {"error", "skip", "update"},
                    description = "When a row's reference is already in the queue: error stops (nothing is added), skip leaves it out, update changes that item.") String ifExists,
            @Input(name = "tags", type = OoaType.LIST, required = false, description = "Labels every new item gets.") List<Object> tags,
            @Input(name = "central", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = CENTRAL) boolean central) {
        List<?> cols = (List<?>) table.get("columns");
        List<?> rows = (List<?>) table.get("rows");
        String mode = ifExists(ifExists);
        List<String> t = tags(tags);
        int refAt = refColumn == null || refColumn.isBlank() ? -1 : cols.stream().map(String::valueOf).toList().indexOf(refColumn);
        if (refColumn != null && !refColumn.isBlank() && refAt < 0) throw new ActionException("QUEUE.BAD_INPUT", "The table has no column '" + refColumn + "'", "Use a column the table has, or add the column first.");
        List<Map<String, Object>> list = new ArrayList<>();
        for (Object r : rows) {
            Map<String, Object> d = new LinkedHashMap<>();
            List<?> row = (List<?>) r;
            for (int i = 0; i < cols.size(); i++) d.put(String.valueOf(cols.get(i)), i < row.size() ? row.get(i) : null);
            Object ref = refAt >= 0 && refAt < row.size() ? row.get(refAt) : null;
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("data", d);
            if (ref != null && !String.valueOf(ref).isBlank()) one.put("reference", String.valueOf(ref));
            list.add(one);
        }
        if (central) return central(ctx, "add_items", queue, "items", list, "if_exists", mode, "tags", t);
        int[] n = new int[2];
        update(ctx, queue, true, items -> {
            List<Map<String, Object>> before = items.stream().map(i -> (Map<String, Object>) new LinkedHashMap<>(i)).toList();
            try {
                for (Map<String, Object> one : list) {
                    Map<String, Object> r = addOne(items, one.get("data"), (String) one.get("reference"), t, mode);
                    if ("skipped".equals(r.get("outcome"))) n[1]++; else n[0]++;
                }
            } catch (ActionException e) { items.clear(); items.addAll(before); throw e; }   // all or nothing
            return null;
        });
        return Map.of("count", BigDecimal.valueOf(n[0]), "skipped", BigDecimal.valueOf(n[1]));
    }

    private static Map<String, Object> takeLocal(ActionContext ctx, String queue, List<String> wanted) {
        return update(ctx, queue, true, items -> {
            String t = now();
            for (Map<String, Object> it : items) {
                boolean due = "new".equals(it.get("status")) || ("deferred".equals(it.get("status")) && String.valueOf(it.getOrDefault("defer_until", "")).compareTo(t) <= 0);
                if (!due || !hasTags(it, wanted)) continue;
                int attempt = Values.toNumber(it.getOrDefault("attempts", BigDecimal.ZERO)).intValue() + 1;
                it.put("status", "in_progress"); it.put("attempts", BigDecimal.valueOf(attempt)); it.put("updated", t); it.put("run_id", ctx.runId());
                return Out.of("found", true, "item_id", it.get("id"), "data", it.get("data"), "attempt", BigDecimal.valueOf(attempt),
                        "reference", it.getOrDefault("reference", ""), "tags", new ArrayList<>(itemTags(it)));
            }
            return empty();
        });
    }

    private static Map<String, Object> empty() {
        return Out.of("found", false, "item_id", "", "data", new LinkedHashMap<>(), "attempt", BigDecimal.ZERO, "reference", "", "tags", new ArrayList<>());
    }

    /** Before G31 (kept for callers in Java). */
    public Map<String, Object> getNext(ActionContext ctx, String queue) { return getNext(ctx, queue, null, 0, false); }

    @Action(id = "get_next", name = "Get next item", category = "Queue", timeoutSeconds = MAX_WAIT + 60,
            description = "Take the oldest available item (new, or deferred and due), optionally only one with every tag given, and mark it in progress; "
                    + "with wait_seconds it waits for an item to arrive. found is false when there is none.",
            panel = "queue | tags, wait_seconds, central")
    @Output(name = "found", type = OoaType.BOOLEAN, description = "True when an item was taken.")
    @Output(name = "item_id", type = OoaType.STRING, description = "Item id.")
    @Output(name = "data", type = OoaType.DICTIONARY, description = "Item fields.")
    @Output(name = "attempt", type = OoaType.NUMBER, description = "Which attempt this is (1 = first).")
    @Output(name = "reference", type = OoaType.STRING, description = "The item's reference (empty when it has none).")
    @Output(name = "tags", type = OoaType.LIST, description = "The item's tags.")
    public Map<String, Object> getNext(ActionContext ctx, @Input(name = "queue", type = OoaType.STRING, description = "Queue name.") String queue,
            @Input(name = "tags", type = OoaType.LIST, required = false, description = "Only an item that has all these tags.") List<Object> tags,
            @Input(name = "wait_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0, max = MAX_WAIT,
                    description = "When the queue has no item, keep looking for this many seconds (stopping the run ends the wait).") int waitSeconds,
            @Input(name = "central", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = CENTRAL) boolean central) {
        List<String> wanted = tags(tags);
        if (central) return waitFor(ctx, waitSeconds, 2000, () -> {
            Map<String, Object> r = new LinkedHashMap<>(empty());
            r.putAll(central(ctx, "get_next", queue, "tags", wanted));
            return r;
        });
        return waitFor(ctx, waitSeconds, 250, () -> takeLocal(ctx, queue, wanted));
    }

    /** Before G31 (kept for callers in Java). */
    public Map<String, Object> complete(ActionContext ctx, String queue, String id, Map<String, Object> result) { return complete(ctx, queue, id, result, false); }

    @Action(id = "complete", name = "Mark completed", category = "Item", description = "Mark an item completed, optionally storing result fields.",
            panel = "queue, item_id, result | central")
    public Map<String, Object> complete(ActionContext ctx,
            @Input(name = "queue", type = OoaType.STRING, description = "Queue name.") String queue,
            @Input(name = "item_id", type = OoaType.STRING, description = "Item id.") String id,
            @Input(name = "result", type = OoaType.DICTIONARY, required = false, description = "Result fields to keep with the item.") Map<String, Object> result,
            @Input(name = "central", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = CENTRAL) boolean central) {
        if (central) { central(ctx, "complete", queue, "item_id", id, "result", result); return Map.of(); }
        update(ctx, queue, true, items -> { Map<String, Object> it = find(items, id); it.put("status", "completed"); it.put("updated", now()); if (result != null) it.put("result", Values.toJsonable(result)); return null; });
        return Map.of();
    }

    /** Before G31 (kept for callers in Java). */
    public Map<String, Object> fail(ActionContext ctx, String queue, String id, String error, int max) { return fail(ctx, queue, id, error, max, null, false); }

    @Action(id = "fail", name = "Mark failed", category = "Item",
            description = "Record a failure. A system exception (the default) puts the item back for another attempt until max_attempts is reached; "
                    + "a business exception (the item itself is wrong) fails it at once, without retries.",
            panel = "queue, item_id, error, kind | max_attempts, central")
    @Output(name = "will_retry", type = OoaType.BOOLEAN, description = "True when the item was put back for another attempt.")
    public Map<String, Object> fail(ActionContext ctx,
            @Input(name = "queue", type = OoaType.STRING, description = "Queue name.") String queue,
            @Input(name = "item_id", type = OoaType.STRING, description = "Item id.") String id,
            @Input(name = "error", type = OoaType.STRING, required = false, defaultValue = "\"\"", description = "What went wrong (shown to operators).") String error,
            @Input(name = "max_attempts", type = OoaType.NUMBER, required = false, defaultValue = "3", min = 1, description = "Total attempts allowed (a central queue uses its own setting).") int max,
            @Input(name = "kind", type = OoaType.STRING, required = false, defaultValue = "\"system\"", enumValues = {"system", "business"},
                    description = "system: something around the item failed (a system was down), retried; business: the item breaks a business rule, not retried.") String kind,
            @Input(name = "central", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = CENTRAL) boolean central) {
        String k = kind(kind);
        if (central) return Map.of("will_retry", Boolean.TRUE.equals(central(ctx, "fail", queue, "item_id", id, "error", ctx.redact(error), "kind", k).get("will_retry")));
        boolean retry = update(ctx, queue, true, items -> {
            Map<String, Object> it = find(items, id);
            boolean again = k.equals("system") && Values.toNumber(it.getOrDefault("attempts", BigDecimal.ZERO)).intValue() < max;
            it.put("status", again ? "new" : "failed"); it.put("error", ctx.redact(error)); it.put("error_kind", k); it.put("updated", now());
            return again;
        });
        return Map.of("will_retry", retry);
    }

    /** Before G31 (kept for callers in Java). */
    public Map<String, Object> defer(ActionContext ctx, String queue, String id, LocalDateTime until) { return defer(ctx, queue, id, until, false); }

    @Action(id = "defer", name = "Defer item", category = "Item", description = "Put an item aside until a date and time (UTC), e.g. when a supplier system is closed.",
            panel = "queue, item_id, until | central")
    public Map<String, Object> defer(ActionContext ctx,
            @Input(name = "queue", type = OoaType.STRING, description = "Queue name.") String queue,
            @Input(name = "item_id", type = OoaType.STRING, description = "Item id.") String id,
            @Input(name = "until", type = OoaType.DATETIME, description = "When it becomes available again.") LocalDateTime until,
            @Input(name = "central", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = CENTRAL) boolean central) {
        if (central) { central(ctx, "defer", queue, "item_id", id, "until", until.withNano(0).toString()); return Map.of(); }
        update(ctx, queue, true, items -> { Map<String, Object> it = find(items, id); it.put("status", "deferred"); it.put("defer_until", until.withNano(0).toString()); it.put("updated", now()); return null; });
        return Map.of();
    }

    /** "On hold" without an end date: deferred until far in the future, so only Update work item brings it back. */
    static final String HOLD_FOREVER = "9999-12-31T23:59:59";

    @Action(id = "update_item", name = "Update work item", category = "Item",
            description = "Change the fields of a work item and optionally send it back for processing or put it on hold (until a time or indefinitely).",
            panel = "queue, item_id, data, status, on_hold_until | replace_data, result")
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
        if (until != null && !st.equals("on_hold")) throw new ActionException("QUEUE.BAD_STATUS", "on_hold_until needs status on_hold", "Set status to on_hold when you give on_hold_until.");
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

    /** Before G31 (kept for callers in Java). */
    public Map<String, Object> getItem(ActionContext ctx, String queue, String id) { return getItem(ctx, queue, id, false); }

    @Action(id = "get_item", name = "Get work item", category = "Item", idempotent = true, description = "Read one work item's fields, status, attempts, result, reference, tags and error kind.",
            panel = "queue, item_id | central")
    @Output(name = "item", type = OoaType.DICTIONARY, description = "id, status, attempts, data, result, error, error_kind, reference, tags, created, updated, defer_until.")
    public Map<String, Object> getItem(ActionContext ctx,
            @Input(name = "queue", type = OoaType.STRING, description = "Queue name.") String queue,
            @Input(name = "item_id", type = OoaType.STRING, description = "Item id.") String id,
            @Input(name = "central", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = CENTRAL) boolean central) {
        if (central) return Map.of("item", central(ctx, "get_item", queue, "item_id", id).get("item"));
        return Map.of("item", update(ctx, queue, false, items -> new LinkedHashMap<>(find(items, id))));
    }

    /** Before G31 (kept for callers in Java). */
    public Map<String, Object> counts(ActionContext ctx, String queue) { return counts(ctx, queue, false); }

    @Action(id = "counts", name = "Get queue counts", category = "Queue", idempotent = true, description = "Count items by status.",
            panel = "queue | central")
    @Output(name = "counts", type = OoaType.DICTIONARY, description = "new, in_progress, completed, failed, deferred, total (a central queue: its own statuses).")
    public Map<String, Object> counts(ActionContext ctx, @Input(name = "queue", type = OoaType.STRING, description = "Queue name.") String queue,
            @Input(name = "central", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = CENTRAL) boolean central) {
        if (central) return Map.of("counts", central(ctx, "counts", queue).get("counts"));
        return Map.of("counts", update(ctx, queue, false, items -> {
            Map<String, Object> c = new LinkedHashMap<>();
            for (String s : STATES) c.put(s, BigDecimal.valueOf(items.stream().filter(i -> s.equals(i.get("status"))).count()));
            c.put("total", BigDecimal.valueOf(items.size()));
            return c;
        }));
    }

    static final List<String> LIST_COLUMNS = List.of("id", "status", "attempts", "updated", "error", "data", "reference", "tags", "error_kind");

    /** Before G31 (kept for callers in Java). */
    public Map<String, Object> listItems(ActionContext ctx, String queue, String status) { return listItems(ctx, queue, status, null, false); }

    @SuppressWarnings("unchecked")
    @Action(id = "list_items", name = "List items", category = "Queue", idempotent = true, description = "List items, optionally only one status or only items with every tag given, as a table.",
            panel = "queue, status | tags, central")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: id, status, attempts, updated, error, data, reference, tags, error_kind.")
    public Map<String, Object> listItems(ActionContext ctx,
            @Input(name = "queue", type = OoaType.STRING, description = "Queue name.") String queue,
            @Input(name = "status", type = OoaType.STRING, required = false, enumValues = {"new", "in_progress", "completed", "failed", "deferred"}, description = "Only this status.") String status,
            @Input(name = "tags", type = OoaType.LIST, required = false, description = "Only items that have all these tags.") List<Object> tags,
            @Input(name = "central", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = CENTRAL) boolean central) {
        List<String> wanted = tags(tags);
        if (central) {
            Map<String, Object> r = central(ctx, "list_items", queue, "status", status, "tags", wanted);
            return Map.of("table", Values.table(LIST_COLUMNS, (List<Object>) r.getOrDefault("rows", List.of())));
        }
        return Map.of("table", update(ctx, queue, false, items -> {
            List<Object> rows = new ArrayList<>();
            for (Map<String, Object> i : items) if ((status == null || status.equals(i.get("status"))) && hasTags(i, wanted))
                rows.add(new ArrayList<>(Arrays.asList(i.get("id"), i.get("status"), i.get("attempts"), i.get("updated"), i.get("error"), i.get("data"),
                        i.get("reference"), new ArrayList<>(itemTags(i)), i.get("error_kind"))));
            return Values.table(LIST_COLUMNS, rows);
        }));
    }

    @Action(id = "tag_item", name = "Tag work item", category = "Item", description = "Add tags to a work item (tags it has already are kept).",
            panel = "queue, item_id, tags | central")
    @Output(name = "tags", type = OoaType.LIST, description = "The item's tags after the change.")
    public Map<String, Object> tagItem(ActionContext ctx,
            @Input(name = "queue", type = OoaType.STRING, description = "Queue name.") String queue,
            @Input(name = "item_id", type = OoaType.STRING, description = "Item id.") String id,
            @Input(name = "tags", type = OoaType.LIST, description = "Tags to add, e.g. [\"urgent\"].") List<Object> tags,
            @Input(name = "central", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = CENTRAL) boolean central) {
        return retag(ctx, queue, id, tags(tags), true, central);
    }

    @Action(id = "untag_item", name = "Remove tags from work item", category = "Item", description = "Remove tags from a work item (ignoring case).",
            panel = "queue, item_id, tags | central")
    @Output(name = "tags", type = OoaType.LIST, description = "The item's tags after the change.")
    public Map<String, Object> untagItem(ActionContext ctx,
            @Input(name = "queue", type = OoaType.STRING, description = "Queue name.") String queue,
            @Input(name = "item_id", type = OoaType.STRING, description = "Item id.") String id,
            @Input(name = "tags", type = OoaType.LIST, description = "Tags to remove.") List<Object> tags,
            @Input(name = "central", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = CENTRAL) boolean central) {
        return retag(ctx, queue, id, tags(tags), false, central);
    }

    private Map<String, Object> retag(ActionContext ctx, String queue, String id, List<String> t, boolean add, boolean central) {
        if (central) return Map.of("tags", central(ctx, add ? "tag_item" : "untag_item", queue, "item_id", id, "tags", t).getOrDefault("tags", List.of()));
        return Map.of("tags", update(ctx, queue, true, items -> {
            Map<String, Object> it = find(items, id);
            List<Object> merged = new ArrayList<>(itemTags(it));
            if (add) merged.addAll(t);
            else merged.removeIf(x -> t.stream().anyMatch(String.valueOf(x)::equalsIgnoreCase));
            List<String> after = tags(merged);
            if (after.isEmpty()) it.remove("tags"); else it.put("tags", after);
            it.put("updated", now());
            return new ArrayList<>(after);
        }));
    }

    @Action(id = "unlock_item", name = "Unlock work item", category = "Item",
            description = "Release an item stuck in progress (its agent stopped without marking it): it becomes new again and keeps its attempts.",
            panel = "queue, item_id | central")
    public Map<String, Object> unlockItem(ActionContext ctx,
            @Input(name = "queue", type = OoaType.STRING, description = "Queue name.") String queue,
            @Input(name = "item_id", type = OoaType.STRING, description = "Item id.") String id,
            @Input(name = "central", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = CENTRAL) boolean central) {
        if (central) { central(ctx, "unlock_item", queue, "item_id", id); return Map.of(); }
        update(ctx, queue, true, items -> {
            Map<String, Object> it = find(items, id);
            if (!"in_progress".equals(it.get("status"))) throw new ActionException("QUEUE.NOT_IN_PROGRESS", "Work item '" + id + "' is not in progress", "Only items in progress can be unlocked.");
            it.put("status", "new"); it.remove("run_id"); it.put("updated", now());
            ctx.log("info", "Unlocked work item " + id + " in queue " + queue);
            return null;
        });
        return Map.of();
    }

    @Action(id = "delete_items", name = "Delete work items", category = "Queue",
            description = "Delete items by status, age, tags or ids. Only completed and failed items are deleted unless force is on (items in progress never are, unless forced).",
            panel = "queue, status, older_than_hours | tags, item_ids, force, central")
    @Output(name = "count", type = OoaType.NUMBER, description = "Items deleted.")
    public Map<String, Object> deleteItems(ActionContext ctx,
            @Input(name = "queue", type = OoaType.STRING, description = "Queue name.") String queue,
            @Input(name = "status", type = OoaType.STRING, required = false, defaultValue = "\"finished\"", enumValues = {"finished", "completed", "failed", "new", "deferred", "in_progress", "any"},
                    description = "finished: completed and failed items; any: every status (needs force for the others).") String status,
            @Input(name = "older_than_hours", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0, description = "Only items last changed more than this many hours ago (0: any age).") double hours,
            @Input(name = "tags", type = OoaType.LIST, required = false, description = "Only items that have all these tags.") List<Object> tags,
            @Input(name = "item_ids", type = OoaType.LIST, required = false, description = "Only these items.") List<Object> ids,
            @Input(name = "force", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Also delete items that are new, deferred or in progress.") boolean force,
            @Input(name = "central", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = CENTRAL) boolean central) {
        String st = status == null || status.isBlank() ? "finished" : status;
        if (!Set.of("finished", "completed", "failed", "new", "deferred", "in_progress", "any").contains(st)) throw new ActionException("QUEUE.BAD_STATUS", "Unknown status '" + status + "'", "Use one of the statuses the step offers.");
        if (!force && Set.of("new", "deferred", "in_progress").contains(st))
            throw new ActionException("QUEUE.NEEDS_FORCE", "Items that are " + st + " are only deleted with force on", "Turn force on, or delete finished items.");
        List<String> wanted = tags(tags);
        Set<String> only = ids == null ? null : new HashSet<>(ids.stream().map(String::valueOf).toList());
        if (central) return Map.of("count", central(ctx, "delete_items", queue, "status", st, "older_than_hours", hours, "tags", wanted, "item_ids", only == null ? null : new ArrayList<>(only), "force", force).get("count"));
        String cutoff = hours > 0 ? LocalDateTime.now(ZoneOffset.UTC).minusSeconds((long) (hours * 3600)).withNano(0).toString() : null;
        int n = update(ctx, queue, true, items -> {
            int before = items.size();
            items.removeIf(i -> {
                String s = String.valueOf(i.get("status"));
                boolean finished = s.equals("completed") || s.equals("failed");
                boolean byStatus = switch (st) { case "finished" -> finished; case "any" -> force || finished; default -> s.equals(st); };
                return byStatus && (force || finished) && hasTags(i, wanted) && (only == null || only.contains(String.valueOf(i.get("id"))))
                        && (cutoff == null || String.valueOf(i.getOrDefault("updated", "")).compareTo(cutoff) < 0);
            });
            return before - items.size();
        });
        if (n > 0) ctx.log("info", "Deleted " + n + " work item(s) from queue " + queue);
        return Map.of("count", BigDecimal.valueOf(n));
    }

    @Action(id = "copy_items", name = "Copy work items", category = "Queue",
            description = "Copy items to another queue as new items (new ids, same data, reference and tags); items whose reference the other queue has already are skipped.",
            panel = "queue, to_queue | status, tags, item_ids, central")
    @Output(name = "count", type = OoaType.NUMBER, description = "Items copied.")
    @Output(name = "skipped", type = OoaType.NUMBER, description = "Items left out because the other queue has their reference.")
    public Map<String, Object> copyItems(ActionContext ctx,
            @Input(name = "queue", type = OoaType.STRING, description = "Queue the items are in.") String queue,
            @Input(name = "to_queue", type = OoaType.STRING, description = "Queue to copy them to.") String to,
            @Input(name = "status", type = OoaType.STRING, required = false, enumValues = {"new", "completed", "failed", "deferred"}, description = "Only items of this status.") String status,
            @Input(name = "tags", type = OoaType.LIST, required = false, description = "Only items that have all these tags.") List<Object> tags,
            @Input(name = "item_ids", type = OoaType.LIST, required = false, description = "Only these items.") List<Object> ids,
            @Input(name = "central", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = CENTRAL) boolean central) {
        return transfer(ctx, queue, to, status, tags, ids, false, central);
    }

    @Action(id = "move_items", name = "Move work items", category = "Queue",
            description = "Move items to another queue as new items (new ids, same data, reference and tags); items in progress, and items whose reference the other queue has, stay.",
            panel = "queue, to_queue | status, tags, item_ids, central")
    @Output(name = "count", type = OoaType.NUMBER, description = "Items moved.")
    @Output(name = "skipped", type = OoaType.NUMBER, description = "Items left where they were because the other queue has their reference.")
    public Map<String, Object> moveItems(ActionContext ctx,
            @Input(name = "queue", type = OoaType.STRING, description = "Queue the items are in.") String queue,
            @Input(name = "to_queue", type = OoaType.STRING, description = "Queue to move them to.") String to,
            @Input(name = "status", type = OoaType.STRING, required = false, enumValues = {"new", "completed", "failed", "deferred"}, description = "Only items of this status.") String status,
            @Input(name = "tags", type = OoaType.LIST, required = false, description = "Only items that have all these tags.") List<Object> tags,
            @Input(name = "item_ids", type = OoaType.LIST, required = false, description = "Only these items.") List<Object> ids,
            @Input(name = "central", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = CENTRAL) boolean central) {
        return transfer(ctx, queue, to, status, tags, ids, true, central);
    }

    /** Copies (or moves) matching items, never those in progress; the copies start new with no attempts. */
    private Map<String, Object> transfer(ActionContext ctx, String queue, String to, String status, List<Object> tags, List<Object> ids, boolean move, boolean central) {
        List<String> wanted = tags(tags);
        Set<String> only = ids == null ? null : new HashSet<>(ids.stream().map(String::valueOf).toList());
        if (central) {
            Map<String, Object> r = central(ctx, move ? "move_items" : "copy_items", queue, "to_queue", to, "status", status, "tags", wanted, "item_ids", only == null ? null : new ArrayList<>(only));
            return Map.of("count", r.getOrDefault("count", BigDecimal.ZERO), "skipped", r.getOrDefault("skipped", BigDecimal.ZERO));
        }
        file(ctx, to);
        if (to.equals(queue)) throw new ActionException("QUEUE.BAD_INPUT", "Choose another queue to " + (move ? "move" : "copy") + " the items to", "Choose a different queue from the one the items are in.");
        List<Map<String, Object>> picked = update(ctx, queue, false, items -> items.stream()
                .filter(i -> !"in_progress".equals(i.get("status")) && (status == null || status.isBlank() || status.equals(i.get("status")))
                        && hasTags(i, wanted) && (only == null || only.contains(String.valueOf(i.get("id")))))
                .map(i -> (Map<String, Object>) new LinkedHashMap<>(i)).toList());
        Set<String> done = new HashSet<>();
        int skipped = update(ctx, to, true, items -> {
            int s = 0;
            for (Map<String, Object> i : picked) {
                Object ref = i.get("reference");
                if (ref != null && items.stream().anyMatch(x -> ref.equals(x.get("reference")))) { s++; continue; }
                Map<String, Object> copy = newItem(i.get("data"));
                if (ref != null) copy.put("reference", ref);
                if (i.get("tags") != null) copy.put("tags", i.get("tags"));
                items.add(copy);
                done.add(String.valueOf(i.get("id")));
            }
            return s;
        });
        if (move && !done.isEmpty()) update(ctx, queue, true, items -> items.removeIf(i -> done.contains(String.valueOf(i.get("id"))) && !"in_progress".equals(i.get("status"))));
        if (move && !done.isEmpty()) ctx.log("info", "Moved " + done.size() + " work item(s) from queue " + queue + " to " + to);
        return Map.of("count", BigDecimal.valueOf(done.size()), "skipped", BigDecimal.valueOf(skipped));
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
