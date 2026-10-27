package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.controlroom.ControlRoom.User;
import com.oneofficeautomation.sdk.OoaException;

import java.time.Instant;
import java.util.*;

/**
 * Approval before a version goes live in production (setting {@code production_approval}). While it is on, checking in
 * "as production", Set as production and importing as production create a request instead; a person with
 * {@code repo.approve} (administrators unless a custom role grants it) approves or rejects it, never their own. Approving
 * makes the version live. A newer request for the same agent replaces an older pending one. Every step is audited.
 */
public final class ProductionApproval {
    private final ControlRoom cr;
    private final Db db;

    ProductionApproval(ControlRoom cr) { this.cr = cr; this.db = cr.db; }

    boolean enabled() { return cr.platform.intSetting("production_approval") == 1; }

    /** Asks for a version to go live; returns the request. The requester needs edit rights on the agent's folder. */
    public Map<String, Object> request(User by, String path, int version, String comment, String ip) {
        cr.require(by, "repo.checkin", "ask for a production version");
        cr.folders.require(by, path, "edit", "ask for a production version");
        if (db.one("SELECT version FROM bot_versions WHERE path = ? AND version = ?", path, version) == null) throw ControlRoom.notFound("Version " + version + " of " + path);
        Map<String, Object> b = db.one("SELECT production_version FROM bots WHERE path = ? AND deleted_at IS NULL", path);
        if (b == null) throw ControlRoom.notFound("Agent " + path);
        if (Objects.equals(b.get("production_version") instanceof Number n ? n.intValue() : null, version))
            throw new OoaException("CR.CONFLICT", "Version " + version + " is already live in production");
        String id = ControlRoom.id("prq");
        db.tx(() -> {
            for (Map<String, Object> old : db.query("SELECT id FROM production_requests WHERE path = ? AND status = 'pending'", path)) {
                db.update("UPDATE production_requests SET status = 'replaced', decided = ?, decided_by = ? WHERE id = ?", now(), by.username(), old.get("id"));
            }
            db.update("INSERT INTO production_requests (id, path, version, requested_by, requested, comment, status) VALUES (?,?,?,?,?,?,?)",
                id, path, version, by.username(), now(), comment == null ? "" : comment.strip(), "pending");
            return null;
        });
        cr.audit.record(by.username(), "repo.production_request", "bot", path, Map.of("version", version, "request", id), "ok", ip);
        return get(id);
    }

    /** Approves (the version goes live) or rejects a pending request. */
    public Map<String, Object> decide(User by, String id, boolean approve, String note, String ip) {
        cr.require(by, "repo.approve", "approve production versions");
        Map<String, Object> r = db.one("SELECT * FROM production_requests WHERE id = ?", id);
        if (r == null) throw ControlRoom.notFound("Request " + id);
        if (!"pending".equals(r.get("status"))) throw new OoaException("CR.CONFLICT", "This request is already " + r.get("status"));
        if (by.username().equals(r.get("requested_by"))) throw new OoaException("CR.FORBIDDEN", "You cannot approve or reject your own request", "Another person with the approval right decides.");
        String path = (String) r.get("path");
        int version = ((Number) r.get("version")).intValue();
        db.tx(() -> {
            db.update("UPDATE production_requests SET status = ?, decided = ?, decided_by = ?, note = ? WHERE id = ?", approve ? "approved" : "rejected", now(), by.username(), note == null ? "" : note.strip(), id);
            if (approve) db.update("UPDATE bots SET production_version = ?, updated = ?, updated_by = ? WHERE path = ?", version, now(), by.username(), path);
            return null;
        });
        cr.audit.record(by.username(), approve ? "repo.production_approve" : "repo.production_reject", "bot", path,
            Map.of("version", version, "request", id, "requested_by", r.get("requested_by")), "ok", ip);
        if (approve) {
            cr.audit.record(by.username(), "repo.set_production", "bot", path, Map.of("version", version, "approved_request", id), "ok", ip);
            cr.gitSync.changed(by.username(), path + ": v" + version + " is live in production (approved by " + by.username() + ", asked by " + r.get("requested_by") + ")");
        }
        return get(id);
    }

    /** The requester takes a pending request back. */
    public Map<String, Object> withdraw(User by, String id, String ip) {
        Map<String, Object> r = db.one("SELECT * FROM production_requests WHERE id = ?", id);
        if (r == null) throw ControlRoom.notFound("Request " + id);
        if (!by.username().equals(r.get("requested_by")) && !cr.can(by, "repo.approve")) throw cr.denied("withdraw this request");
        if (!"pending".equals(r.get("status"))) throw new OoaException("CR.CONFLICT", "This request is already " + r.get("status"));
        db.update("UPDATE production_requests SET status = 'withdrawn', decided = ?, decided_by = ? WHERE id = ?", now(), by.username(), id);
        cr.audit.record(by.username(), "repo.production_withdraw", "bot", (String) r.get("path"), Map.of("request", id), "ok", ip);
        return get(id);
    }

    /** Requests the person can see (folders they can view): pending first, newest first; status filters. */
    public List<Map<String, Object>> list(User by, String status) {
        cr.require(by, "repo.view", "see production requests");
        List<Map<String, Object>> out = new ArrayList<>();
        String sql = "SELECT id, path, version, requested_by, requested, comment, status, decided_by, decided, note FROM production_requests"
            + (status == null || status.isBlank() ? "" : " WHERE status = ?") + " ORDER BY requested DESC";
        List<Map<String, Object>> rows = status == null || status.isBlank() ? db.query(sql) : db.query(sql, status);
        for (Map<String, Object> r : rows) {
            if (!Folders.atLeast(cr.folders.access(by.username(), by.role(), (String) r.get("path")), "view")) continue;
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.put("can_decide", "pending".equals(r.get("status")) && cr.can(by, "repo.approve") && !by.username().equals(r.get("requested_by")));
            out.add(m);
            if (out.size() >= 500) break;
        }
        out.sort(Comparator.comparing((Map<String, Object> m) -> !"pending".equals(m.get("status"))));
        return out;
    }

    int pendingCount(User by) { return (int) list(by, "pending").stream().filter(m -> Boolean.TRUE.equals(m.get("can_decide"))).count(); }

    Map<String, Object> get(String id) { return db.one("SELECT id, path, version, requested_by, requested, comment, status, decided_by, decided, note FROM production_requests WHERE id = ?", id); }

    private static Instant now() { return Instant.now(); }
}
