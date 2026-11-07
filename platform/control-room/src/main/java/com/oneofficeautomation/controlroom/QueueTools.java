package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static com.oneofficeautomation.controlroom.ControlRoom.*;
import com.oneofficeautomation.controlroom.ControlRoom.User;

/**
 * Work queue upgrades (gap G31, 4 Nov 2026), as UiPath's and Blue Prism's queues: tags on items, unlock, delete by
 * filter, copy and move to another queue, a reference that is unique in its queue, business exceptions apart from
 * system ones, item data encrypted at rest with the vault's key, and the commands agents send for central queues
 * (ooa.workload with central on, relayed by the runner).
 *
 * <p>Tags are kept as ",a,b," so a filter is a LIKE per tag; a filter matches items that have every tag given (case
 * ignored). Encrypted data is the vault's "v1:..." text bound to "queue-item:" + the item id, so it cannot be moved to
 * another row unnoticed; JSON never starts with "v1:", so plain and encrypted rows are told apart by their text.
 */
final class QueueTools {
    private final ControlRoom cr;
    private final Platform p;
    private final Db db;

    QueueTools(ControlRoom cr, Platform p) { this.cr = cr; this.p = p; this.db = cr.db; }

    /** A work item event's data (G38): the item, its queue and its status; never its data, which may be private. */
    static Map<String, Object> item(String itemId, String qid, String reference, String status) { return item(itemId, qid, reference, status, null); }

    static Map<String, Object> item(String itemId, String qid, String reference, String status, String runId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("item_id", itemId); m.put("queue_id", qid); m.put("status", status);
        if (reference != null && !reference.isBlank()) m.put("reference", reference);
        if (runId != null) m.put("run_id", runId);
        return m;
    }

    static final int MAX_TAGS = 20;
    static final List<String> ALL_STATUSES = List.of("new", "in_progress", "review", "retry", "success", "failed", "deferred");

    // ----- tags -----

    /** Tags as given, trimmed, without repeats (ignoring case); at most 20 of at most 50 characters, without commas. */
    static List<String> tags(Object v) {
        List<String> out = new ArrayList<>();
        if (v == null) return out;
        Collection<?> raw = v instanceof Collection<?> c ? c : Arrays.asList(String.valueOf(v).split(","));
        for (Object o : raw) {
            String t = o == null ? "" : String.valueOf(o).trim();
            if (t.isEmpty()) continue;
            if (t.length() > 50 || t.contains(",")) throw new OoaException("CR.BAD_INPUT", "Tag '" + t + "' is not allowed", "Tags have at most 50 characters and no commas.");
            if (out.stream().noneMatch(x -> x.equalsIgnoreCase(t))) out.add(t);
        }
        if (out.size() > MAX_TAGS) throw new OoaException("CR.BAD_INPUT", "An item has at most " + MAX_TAGS + " tags");
        return out;
    }

    static String tagsColumn(List<String> tags) { return tags.isEmpty() ? null : "," + String.join(",", tags) + ","; }

    static List<String> tagList(Object column) {
        List<String> out = new ArrayList<>();
        if (column != null) for (String t : String.valueOf(column).split(",")) if (!t.isBlank()) out.add(t);
        return out;
    }

    /** SQL that keeps rows with every tag (case ignored); adds its parameters. '!' escapes LIKE's wildcards on every engine. */
    static String tagFilter(String column, List<String> tags, List<Object> params) {
        StringBuilder sb = new StringBuilder();
        for (String t : tags) {
            sb.append(" AND LOWER(COALESCE(").append(column).append(", '')) LIKE ? ESCAPE '!'");
            params.add("%," + t.toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_").replace("[", "![") + ",%");
        }
        return sb.toString();
    }

    // ----- encrypted item data -----

    boolean encrypted(String qid) {
        Map<String, Object> q = db.one("SELECT encrypt_data FROM queues WHERE id = ?", qid);
        return q != null && Boolean.TRUE.equals(q.get("encrypt_data"));
    }

    /** The data as stored: encrypted with the vault's key when the queue says so. */
    String seal(boolean encrypt, String itemId, String json) { return encrypt ? cr.vault.encrypt(json, "queue-item:" + itemId) : json; }

    /** The item's data as JSON text, decrypted when it was stored encrypted. */
    String open(String itemId, Object stored) {
        String s = stored == null ? null : String.valueOf(stored);
        return s != null && s.startsWith("v1:") ? cr.vault.decrypt(s, "queue-item:" + itemId) : s;
    }

    /** A stored item row as the API shows it: data decrypted (still JSON text, as before), tags as a list. */
    Map<String, Object> view(Map<String, Object> row) {
        Map<String, Object> m = new LinkedHashMap<>(row);
        m.put("data", open((String) row.get("id"), row.get("data")));
        m.put("tags", tagList(row.get("tags")));
        m.remove("unique_ref");
        return m;
    }

    /** Turns encryption of a queue's item data on or off, re-writing its items in the same transaction. */
    void setEncryption(User by, String qid, boolean on, String ip) {
        if (encrypted(qid) == on) return;
        int n = db.tx(() -> {
            int k = 0;
            for (Map<String, Object> i : db.query("SELECT id, data FROM queue_items WHERE queue_id = ?", qid)) {
                String plain = open((String) i.get("id"), i.get("data"));
                db.update("UPDATE queue_items SET data = ? WHERE id = ?", seal(on, (String) i.get("id"), plain), i.get("id"));
                k++;
            }
            db.update("UPDATE queues SET encrypt_data = ? WHERE id = ?", on, qid);
            return k;
        });
        cr.audit.record(by.username(), on ? "queue.encrypt_on" : "queue.encrypt_off", "queue", qid, Map.of("items", n), "ok", ip);
    }

    /** Re-encrypts every encrypted item with the next vault key (vault rekey); returns how many. */
    int reencrypt(Vault next) {
        int k = 0;
        for (Map<String, Object> i : db.query("SELECT id, data FROM queue_items WHERE data LIKE 'v1:%'")) {
            String bound = "queue-item:" + i.get("id");
            db.update("UPDATE queue_items SET data = ? WHERE id = ?", next.encrypt(cr.vault.decrypt((String) i.get("data"), bound), bound), i.get("id"));
            k++;
        }
        return k;
    }

    // ----- adding with references and tags -----

    /**
     * Adds one item inside the caller's transaction. With ifExists (error, skip or update) its reference is unique in
     * the queue: an item with the same reference is refused, left as it is, or given the new data and tags; the
     * reference is then also kept in unique_ref, which a unique index guards. Without ifExists it is added as before.
     * Returns {id, outcome: added | skipped | updated}.
     */
    Map<String, Object> addOne(String qid, boolean encrypt, Map<String, Object> data, String ref, Integer priority, List<String> tags, String ifExists) {
        if (ref != null && ref.length() > 200) throw new OoaException("CR.BAD_INPUT", "A reference has at most 200 characters");
        if (ifExists != null && ref != null) {
            Map<String, Object> same = db.one("SELECT id, status FROM queue_items WHERE queue_id = ? AND reference = ? ORDER BY created LIMIT 1", qid, ref);
            if (same != null) {
                String sid = (String) same.get("id");
                switch (ifExists) {
                    case "skip" -> { return Map.of("id", sid, "outcome", "skipped"); }
                    case "update" -> {
                        if ("in_progress".equals(same.get("status"))) throw new OoaException("CR.CONFLICT", "The item with reference '" + ref + "' is being worked on", "Change it after its run ends.");
                        db.update("UPDATE queue_items SET data = ?, updated = ? WHERE id = ?", seal(encrypt, sid, JsonLite.write(data)), now(), sid);
                        if (!tags.isEmpty()) db.update("UPDATE queue_items SET tags = ? WHERE id = ?", tagsColumn(tags), sid);
                        if (priority != null) db.update("UPDATE queue_items SET priority = ? WHERE id = ?", priority, sid);
                        return Map.of("id", sid, "outcome", "updated");
                    }
                    default -> throw new OoaException("CR.DUPLICATE_REFERENCE", "The queue already has an item with reference '" + ref + "'",
                            "Give each item its own reference, or add it with if_exists skip or update.");
                }
            }
        }
        String iid = id("item");
        db.update("INSERT INTO queue_items (id, queue_id, reference, data, status, priority, attempts, created, updated, tags, unique_ref) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                iid, qid, ref, seal(encrypt, iid, JsonLite.write(data)), "new", priority == null ? 0 : priority, 0, now(), now(), tagsColumn(tags), ifExists != null ? ref : null);
        return Map.of("id", iid, "outcome", "added");
    }

    static String ifExists(Object v) {
        if (v == null || "".equals(v)) return null;
        String s = String.valueOf(v);
        if (!Set.of("error", "skip", "update").contains(s)) throw new OoaException("CR.BAD_INPUT", "if_exists is error, skip or update, not '" + s + "'");
        return s;
    }

    // ----- tags, unlock, delete, copy and move (people and agents) -----

    /** Adds and removes tags on items not being worked on... and on those too: tags are only labels. Returns how many items changed. */
    int retag(User by, String actor, String qid, List<Object> ids, Object add, Object remove, String ip) {
        cr.require(by, "queues.work", "tag work items");
        p.requireQueue(by, qid, "add", "tag work items in this queue");
        List<String> plus = tags(add), minus = tags(remove);
        int n = 0;
        for (Object o : ids == null ? List.of() : ids) {
            Map<String, Object> i = db.one("SELECT tags FROM queue_items WHERE id = ? AND queue_id = ?", String.valueOf(o), qid);
            if (i == null) continue;
            List<Object> merged = new ArrayList<>(tagList(i.get("tags")));
            merged.addAll(plus);
            merged.removeIf(x -> minus.stream().anyMatch(String.valueOf(x)::equalsIgnoreCase));
            n += db.update("UPDATE queue_items SET tags = ?, updated = ? WHERE id = ?", tagsColumn(tags(merged)), now(), String.valueOf(o));
        }
        cr.audit.record(actor, "queue.items_tag", "queue", qid, Map.of("count", n, "add", plus, "remove", minus), "ok", ip);
        return n;
    }

    /**
     * Releases items stuck in progress: back to new, attempts kept. An item whose run is still queued or running stays
     * locked (stop the run first), except for the run asking (an agent unlocking an item it took). Returns how many.
     */
    int unlock(User by, String actor, String qid, List<Object> ids, String ownRun, String ip) {
        cr.require(by, "queues.work", "unlock work items");
        p.requireQueue(by, qid, "process", "unlock work items in this queue");
        int n = 0;
        for (Object o : ids == null ? List.of() : ids) {
            Map<String, Object> i = db.one("SELECT run_id FROM queue_items WHERE id = ? AND queue_id = ? AND status = 'in_progress'", String.valueOf(o), qid);
            if (i == null) continue;
            String run = (String) i.get("run_id");
            if (run != null && !run.equals(ownRun) && cr.count("SELECT COUNT(*) AS n FROM runs WHERE id = ? AND status IN ('queued', 'running')", run) > 0) continue;
            n += db.update("UPDATE queue_items SET status = 'new', run_id = NULL, device_id = NULL, updated = ? WHERE id = ? AND status = 'in_progress'", now(), String.valueOf(o));
        }
        cr.audit.record(actor, "queue.items_unlock", "queue", qid, Map.of("count", n, "asked", ids == null ? 0 : ids.size()), "ok", ip);
        return n;
    }

    /** The statuses a filter names: finished (success and failed), completed (= success), any, or one status; new includes retry. */
    static List<String> statuses(String status) {
        String s = status == null || status.isBlank() ? "finished" : status;
        return switch (s) {
            case "finished" -> List.of("success", "failed");
            case "completed", "success" -> List.of("success");
            case "new" -> List.of("new", "retry");
            case "any" -> ALL_STATUSES;
            default -> {
                if (!ALL_STATUSES.contains(s)) throw new OoaException("CR.BAD_INPUT", "Unknown status '" + s + "'");
                yield List.of(s);
            }
        };
    }

    /**
     * Deletes the items a filter picks: statuses, last changed more than older_than_hours ago, every tag given, ids.
     * Without force only succeeded and failed items go. Returns how many.
     */
    @SuppressWarnings("unchecked")
    int deleteMatching(User by, String actor, String qid, Map<String, Object> b, String ip) {
        cr.require(by, "queues.work", "delete work items");
        p.requireQueue(by, qid, "add", "delete work items in this queue");
        boolean force = Boolean.TRUE.equals(b.get("force"));
        List<String> st = statuses((String) b.get("status"));
        List<String> allowed = force ? st : st.stream().filter(s -> s.equals("success") || s.equals("failed")).toList();
        if (allowed.isEmpty()) throw new OoaException("CR.BAD_INPUT", "Only succeeded and failed items are deleted unless force is on");
        List<Object> params = new ArrayList<>(List.of(qid));
        StringBuilder where = new StringBuilder(" WHERE queue_id = ? AND status IN (" + String.join(",", Collections.nCopies(allowed.size(), "?")) + ")");
        params.addAll(allowed);
        double hours = b.get("older_than_hours") instanceof Number h ? h.doubleValue() : 0;
        if (hours > 0) { where.append(" AND updated < ?"); params.add(now().minusSeconds((long) (hours * 3600))); }
        where.append(tagFilter("tags", tags(b.get("tags")), params));
        if (b.get("ids") instanceof List<?> ids) {
            if (ids.isEmpty()) return 0;
            where.append(" AND id IN (").append(String.join(",", Collections.nCopies(ids.size(), "?"))).append(")");
            for (Object o : ids) params.add(String.valueOf(o));
        }
        int n = db.update("DELETE FROM queue_items" + where, params.toArray());
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("count", n); detail.put("statuses", allowed); detail.put("force", force);
        if (hours > 0) detail.put("older_than_hours", hours);
        cr.audit.record(actor, "queue.items_delete", "queue", qid, detail, "ok", ip);
        return n;
    }

    /** A queue by id, or else by its name. */
    String queueId(String idOrName) {
        Map<String, Object> q = db.one("SELECT id FROM queues WHERE id = ?", idOrName);
        if (q == null) q = db.one("SELECT id FROM queues WHERE name = ?", idOrName);
        if (q == null) throw new OoaException("CR.NOT_FOUND", "No work queue '" + idOrName + "'", "Use the queue's name as shown in Work queues.");
        return (String) q.get("id");
    }

    /**
     * Copies or moves items (never those in progress) to another queue as new items with new ids: data, reference,
     * priority and tags kept, attempts start again. Items are checked against the other queue's model; an item whose
     * reference the other queue has is skipped. Returns {count, skipped}.
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> transfer(User by, String actor, String qid, String to, Map<String, Object> b, boolean move, String ip) {
        cr.require(by, "queues.work", (move ? "move" : "copy") + " work items");
        p.requireQueue(by, qid, move ? "add" : "view", (move ? "move" : "copy") + " work items from this queue");
        String target = queueId(to);
        if (target.equals(qid)) throw new OoaException("CR.BAD_INPUT", "Choose another queue");
        p.requireQueue(by, target, "add", "add work items to the other queue");
        List<Object> params = new ArrayList<>(List.of(qid));
        StringBuilder where = new StringBuilder(" WHERE queue_id = ? AND status <> 'in_progress'");
        if (b.get("status") instanceof String s && !s.isBlank()) {
            List<String> st = statuses(s);
            where.append(" AND status IN (").append(String.join(",", Collections.nCopies(st.size(), "?"))).append(")");
            params.addAll(st);
        }
        where.append(tagFilter("tags", tags(b.get("tags")), params));
        if (b.get("ids") instanceof List<?> ids) {
            if (ids.isEmpty()) return Map.of("count", 0, "skipped", 0);
            where.append(" AND id IN (").append(String.join(",", Collections.nCopies(ids.size(), "?"))).append(")");
            for (Object o : ids) params.add(String.valueOf(o));
        }
        List<Map<String, Object>> fields = p.modelFields((String) db.one("SELECT model_id FROM queues WHERE id = ?", target).get("model_id"));
        boolean enc = encrypted(target);
        int[] n = new int[2];
        db.tx(() -> {
            for (Map<String, Object> i : db.query("SELECT * FROM queue_items" + where + " ORDER BY created LIMIT 10000", params.toArray())) {
                String ref = (String) i.get("reference");
                if (ref != null && db.one("SELECT id FROM queue_items WHERE queue_id = ? AND reference = ? LIMIT 1", target, ref) != null) { n[1]++; continue; }
                Map<String, Object> data = (Map<String, Object>) JsonLite.parse(open((String) i.get("id"), i.get("data")));
                if (fields != null) data = Platform.checkItem(fields, data, "Item " + (ref == null ? i.get("id") : ref));
                String iid = id("item");
                db.update("INSERT INTO queue_items (id, queue_id, reference, data, status, priority, attempts, created, updated, tags, unique_ref) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                        iid, target, ref, seal(enc, iid, JsonLite.write(data)), "new", i.get("priority"), 0, now(), now(), i.get("tags"), i.get("unique_ref"));
                if (move) db.update("DELETE FROM queue_items WHERE id = ?", i.get("id"));
                n[0]++;
            }
            return null;
        });
        cr.audit.record(actor, move ? "queue.items_move" : "queue.items_copy", "queue", qid, Map.of("count", n[0], "skipped", n[1], "to", target), "ok", ip);
        return Map.of("count", n[0], "skipped", n[1]);
    }

    // ----- commands from agents (ooa.workload with central on) -----

    /**
     * One command from a running agent for a central queue, named by its name. It runs as the person the run belongs to
     * (run-as user, or who started it), with that person's permissions and queue membership; changes are recorded in
     * the audit trail as "agent:RUNNER for PERSON".
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> fromBot(Map<String, Object> device, Map<String, Object> body) {
        Map<String, Object> run = cr.runOnDevice(device, (String) body.get("run_id"));
        if (!"running".equals(run.get("status"))) throw new OoaException("CR.FORBIDDEN", "Only running agents can use work queues");
        String who = (String) (run.get("run_as") != null ? run.get("run_as") : run.get("requested_by"));
        Map<String, Object> u = db.one("SELECT id, username, role, display_name FROM users WHERE username = ? AND enabled = TRUE", who);
        if (u == null) throw new OoaException("CR.FORBIDDEN", "The run's user " + who + " is not active");
        User by = new User((String) u.get("id"), who, (String) u.get("role"), (String) u.get("display_name"), false);
        String actor = "agent:" + device.get("name") + " for " + who;
        Map<String, Object> r = body.get("request") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        String op = String.valueOf(r.get("op"));
        Map<String, Object> qrow = db.one("SELECT id, model_id, max_attempts FROM queues WHERE name = ?", String.valueOf(r.get("queue")));
        if (qrow == null) throw new OoaException("CR.NOT_FOUND", "No work queue '" + r.get("queue") + "' in One CommandCenter", "Use the queue's name as shown in Work queues.");
        String qid = (String) qrow.get("id");
        String runId = (String) run.get("id");
        String itemId = r.get("item_id") == null ? null : String.valueOf(r.get("item_id"));
        return switch (op) {
            case "add_item", "add_items" -> {
                List<Object> items = new ArrayList<>();
                List<Object> given = op.equals("add_item") ? List.of(Map.of("data", r.getOrDefault("data", Map.of()), "reference", r.getOrDefault("reference", ""))) : (List<Object>) r.getOrDefault("items", List.of());
                for (Object o : given) {
                    Map<String, Object> one = (Map<String, Object>) o;
                    Map<String, Object> it = new LinkedHashMap<>(one.get("data") instanceof Map<?, ?> d ? (Map<String, Object>) d : Map.of());
                    if (one.get("reference") instanceof String ref && !ref.isBlank()) it.put("_reference", ref);
                    it.put("_tags", r.getOrDefault("tags", List.of()));
                    it.put("_if_exists", r.getOrDefault("if_exists", "error"));
                    items.add(it);
                }
                Map<String, Object> res = p.addItemsAs(by, actor, qid, items, null);
                if (op.equals("add_items")) yield Map.of("count", ((Number) res.get("added")).intValue() + ((Number) res.get("updated")).intValue(), "skipped", res.get("skipped"));
                List<Object> outcomes = (List<Object>) res.get("outcomes");
                yield Map.of("item_id", ((List<Object>) res.get("ids")).get(0), "outcome", outcomes.get(0));
            }
            case "get_next" -> next(by, qid, tags(r.get("tags")), runId, (String) device.get("id"));
            case "complete" -> {
                requireItem(by, qid, itemId, "process");
                db.update("UPDATE queue_items SET status = 'success', result = ?, updated = ?, finished = ? WHERE id = ?",
                        r.get("result") == null ? null : JsonLite.write(r.get("result")), now(), now(), itemId);
                cr.events.emit("queue.item_finished", item(itemId, qid, null, "success"));
                yield Map.of();
            }
            case "fail" -> {
                Map<String, Object> i = requireItem(by, qid, itemId, "process");
                String kind = "business".equals(r.get("kind")) ? "business" : "system";
                boolean again = kind.equals("system") && ((Number) i.get("attempts")).intValue() < ((Number) qrow.get("max_attempts")).intValue();
                String error = JsonLite.write(Map.of("code", kind.equals("business") ? "QUEUE.BUSINESS_EXCEPTION" : "QUEUE.SYSTEM_EXCEPTION", "message", String.valueOf(r.getOrDefault("error", ""))));
                db.update("UPDATE queue_items SET status = ?, error = ?, error_kind = ?, updated = ?, finished = ? WHERE id = ?",
                        again ? "retry" : "failed", error, kind, now(), again ? null : now(), itemId);
                Map<String, Object> ev = item(itemId, qid, null, again ? "retry" : "failed");
                ev.put("error_kind", kind);
                cr.events.emit("queue.item_finished", ev);
                yield Map.of("will_retry", again);
            }
            case "defer" -> {
                requireItem(by, qid, itemId, "process");
                Instant until = LocalDateTime.parse(String.valueOf(r.get("until"))).toInstant(ZoneOffset.UTC);
                db.update("UPDATE queue_items SET status = 'deferred', defer_until = ?, updated = ? WHERE id = ?", until, now(), itemId);
                yield Map.of();
            }
            case "get_item" -> {
                Map<String, Object> i = p.item(by, qid, itemId);
                Map<String, Object> out = new LinkedHashMap<>(i);
                out.remove("unique_ref");
                yield Map.of("item", out);
            }
            case "counts" -> {
                cr.require(by, "queues.view", "view work queues");
                p.requireQueue(by, qid, "view", "view this work queue");
                Map<String, Object> c = new LinkedHashMap<>(p.counts(qid));
                long total = 0;
                for (Object v : c.values()) total += ((Number) v).longValue();
                c.put("total", total);
                yield Map.of("counts", c);
            }
            case "list_items" -> {
                cr.require(by, "queues.view", "view work queues");
                p.requireQueue(by, qid, "view", "view this work queue");
                List<Object> params = new ArrayList<>(List.of(qid));
                String where = " WHERE queue_id = ?";
                if (r.get("status") instanceof String s && !s.isBlank()) {
                    List<String> st = statuses(s);
                    where += " AND status IN (" + String.join(",", Collections.nCopies(st.size(), "?")) + ")";
                    params.addAll(st);
                }
                where += tagFilter("tags", tags(r.get("tags")), params);
                List<Object> rows = new ArrayList<>();
                for (Map<String, Object> i : db.query("SELECT * FROM queue_items" + where + " ORDER BY created LIMIT 1000", params.toArray()))
                    rows.add(new ArrayList<>(Arrays.asList(i.get("id"), i.get("status"), i.get("attempts"), i.get("updated"), i.get("error") == null ? null : JsonLite.parse((String) i.get("error")),
                            JsonLite.parse(open((String) i.get("id"), i.get("data"))), i.get("reference"), tagList(i.get("tags")), i.get("error_kind"))));
                yield Map.of("rows", rows);
            }
            case "tag_item", "untag_item" -> {
                requireItem(by, qid, itemId, "add");
                retag(by, actor, qid, List.of(itemId), op.equals("tag_item") ? r.get("tags") : null, op.equals("untag_item") ? r.get("tags") : null, null);
                yield Map.of("tags", tagList(db.one("SELECT tags FROM queue_items WHERE id = ?", itemId).get("tags")));
            }
            case "unlock_item" -> {
                requireItem(by, qid, itemId, "process");
                if (unlock(by, actor, qid, List.of(itemId), runId, null) == 0)
                    throw new OoaException("QUEUE.NOT_IN_PROGRESS", "Work item '" + itemId + "' is not in progress, or another run is still working on it", "Only items in progress whose run has ended can be unlocked.");
                yield Map.of();
            }
            case "delete_items" -> {
                Map<String, Object> b = new LinkedHashMap<>(r);
                if (r.get("item_ids") instanceof List<?> l) b.put("ids", l);
                yield Map.of("count", deleteMatching(by, actor, qid, b, null));
            }
            case "copy_items", "move_items" -> {
                Map<String, Object> b = new LinkedHashMap<>(r);
                if (r.get("item_ids") instanceof List<?> l) b.put("ids", l);
                yield transfer(by, actor, qid, String.valueOf(r.get("to_queue")), b, op.equals("move_items"), null);
            }
            default -> throw new OoaException("CR.BAD_INPUT", "Unknown work queue command '" + op + "'");
        };
    }

    private Map<String, Object> requireItem(User by, String qid, String itemId, String need) {
        cr.require(by, need.equals("view") ? "queues.view" : "queues.work", "work on work items");
        p.requireQueue(by, qid, need, "work on items in this queue");
        Map<String, Object> i = itemId == null ? null : db.one("SELECT * FROM queue_items WHERE id = ? AND queue_id = ?", itemId, qid);
        if (i == null) throw new OoaException("QUEUE.ITEM_NOT_FOUND", "Work item '" + itemId + "' not found", "Use the item_id from Get next item.");
        return i;
    }

    /** An agent takes the next waiting item (priority, then age) that has every tag given; it is in progress for this run. */
    Map<String, Object> next(User by, String qid, List<String> tags, String runId, String deviceId) {
        cr.require(by, "queues.work", "process work queues");
        p.requireQueue(by, qid, "process", "process this work queue");
        for (int guard = 0; guard < 10; guard++) {
            Instant t = now();
            List<Object> params = new ArrayList<>(List.of(qid, t));
            String where = tagFilter("tags", tags, params);
            Map<String, Object> i = db.one("SELECT id FROM queue_items WHERE queue_id = ? AND (status IN ('new', 'retry') OR (status = 'deferred' AND defer_until <= ?))" + where
                    + " ORDER BY priority DESC, created LIMIT 1", params.toArray());
            if (i == null) return Map.of("found", false);
            if (db.update("UPDATE queue_items SET status = 'in_progress', attempts = attempts + 1, run_id = ?, device_id = ?, defer_until = NULL, updated = ? WHERE id = ? "
                    + "AND (status IN ('new', 'retry') OR (status = 'deferred' AND defer_until <= ?))", runId, deviceId, now(), i.get("id"), t) != 1) continue;
            Map<String, Object> row = db.one("SELECT * FROM queue_items WHERE id = ?", i.get("id"));
            Map<String, Object> ev = item((String) row.get("id"), qid, (String) row.get("reference"), "in_progress");
            if (runId != null) ev.put("run_id", runId);
            cr.events.emit("queue.item_started", ev);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("found", true);
            out.put("item_id", row.get("id"));
            out.put("data", JsonLite.parse(open((String) row.get("id"), row.get("data"))));
            out.put("attempt", BigDecimal.valueOf(((Number) row.get("attempts")).longValue()));
            out.put("reference", row.get("reference") == null ? "" : row.get("reference"));
            out.put("tags", tagList(row.get("tags")));
            return out;
        }
        return Map.of("found", false);
    }
}
