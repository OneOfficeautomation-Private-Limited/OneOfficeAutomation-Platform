package com.oneofficeautomation.modules.governance;

import com.oneofficeautomation.controlroom.ControlRoom.User;
import com.oneofficeautomation.controlroom.Db;
import com.oneofficeautomation.controlroom.module.ModuleContext;

import static com.oneofficeautomation.controlroom.module.ModuleContext.*;

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
    private final ModuleContext ctx;
    private final Db db;

    public ProductionApproval(ModuleContext ctx) { this.ctx = ctx; this.db = ctx.db(); }

    public boolean enabled() { return ctx.intSetting("production_approval") == 1; }

    /** Asks for a version to go live; returns the request. The requester needs edit rights on the agent's folder. */
    public Map<String, Object> request(User by, String path, int version, String comment, String ip) {
        ctx.require(by, "repo.checkin", "ask for a production version");
        ctx.requirePathAccess(by.username(), by.role(), path, "edit", "ask for a production version");
        if (db.one("SELECT version FROM bot_versions WHERE path = ? AND version = ?", path, version) == null) throw notFound("Version " + version + " of " + path);
        Map<String, Object> b = db.one("SELECT production_version FROM bots WHERE path = ? AND deleted_at IS NULL", path);
        if (b == null) throw notFound("Agent " + path);
        if (Objects.equals(b.get("production_version") instanceof Number n ? n.intValue() : null, version))
            throw new OoaException("CR.CONFLICT", "Version " + version + " is already live in production", "Nothing to do: that version is already in production.");
        String id = id("prq");
        db.tx(() -> {
            for (Map<String, Object> old : db.query("SELECT id FROM production_requests WHERE path = ? AND status = 'pending'", path)) {
                db.update("UPDATE production_requests SET status = 'replaced', decided = ?, decided_by = ? WHERE id = ?", now(), by.username(), old.get("id"));
            }
            db.update("INSERT INTO production_requests (id, path, version, requested_by, requested, comment, status) VALUES (?,?,?,?,?,?,?)",
                id, path, version, by.username(), now(), comment == null ? "" : comment.strip(), "pending");
            return null;
        });
        ctx.audit(by.username(), "repo.production_request", "bot", path, Map.of("version", version, "request", id), "ok", ip);
        return get(id);
    }

    /** Approves (the version goes live) or rejects a pending request. */
    public Map<String, Object> decide(User by, String id, boolean approve, String note, String ip) {
        ctx.require(by, "repo.approve", "approve production versions");
        Map<String, Object> r = db.one("SELECT * FROM production_requests WHERE id = ?", id);
        if (r == null) throw notFound("Request " + id);
        if (!"pending".equals(r.get("status"))) throw new OoaException("CR.CONFLICT", "This request is already " + r.get("status"), "Reload the page to see the current state.");
        if (by.username().equals(r.get("requested_by"))) throw new OoaException("CR.FORBIDDEN", "You cannot approve or reject your own request", "Another person with the approval right decides.");
        String path = (String) r.get("path");
        int version = ((Number) r.get("version")).intValue();
        db.tx(() -> {
            db.update("UPDATE production_requests SET status = ?, decided = ?, decided_by = ?, note = ? WHERE id = ?", approve ? "approved" : "rejected", now(), by.username(), note == null ? "" : note.strip(), id);
            if (approve) db.update("UPDATE bots SET production_version = ?, updated = ?, updated_by = ? WHERE path = ?", version, now(), by.username(), path);
            return null;
        });
        ctx.audit(by.username(), approve ? "repo.production_approve" : "repo.production_reject", "bot", path,
            Map.of("version", version, "request", id, "requested_by", r.get("requested_by")), "ok", ip);
        if (approve) {
            ctx.audit(by.username(), "repo.set_production", "bot", path, Map.of("version", version, "approved_request", id), "ok", ip);
            ctx.repositoryChanged(by.username(), path + ": v" + version + " is live in production (approved by " + by.username() + ", asked by " + r.get("requested_by") + ")");
        }
        return get(id);
    }

    /** The requester takes a pending request back. */
    public Map<String, Object> withdraw(User by, String id, String ip) {
        Map<String, Object> r = db.one("SELECT * FROM production_requests WHERE id = ?", id);
        if (r == null) throw notFound("Request " + id);
        if (!by.username().equals(r.get("requested_by")) && !ctx.can(by, "repo.approve")) throw denied("withdraw this request");
        if (!"pending".equals(r.get("status"))) throw new OoaException("CR.CONFLICT", "This request is already " + r.get("status"), "Reload the page to see the current state.");
        db.update("UPDATE production_requests SET status = 'withdrawn', decided = ?, decided_by = ? WHERE id = ?", now(), by.username(), id);
        ctx.audit(by.username(), "repo.production_withdraw", "bot", (String) r.get("path"), Map.of("request", id), "ok", ip);
        return get(id);
    }

    /** Requests the person can see (folders they can view): pending first, newest first; status filters. */
    public List<Map<String, Object>> list(User by, String status) {
        ctx.require(by, "repo.view", "see production requests");
        List<Map<String, Object>> out = new ArrayList<>();
        String sql = "SELECT id, path, version, requested_by, requested, comment, status, decided_by, decided, note FROM production_requests"
            + (status == null || status.isBlank() ? "" : " WHERE status = ?") + " ORDER BY requested DESC";
        List<Map<String, Object>> rows = status == null || status.isBlank() ? db.query(sql) : db.query(sql, status);
        for (Map<String, Object> r : rows) {
            if (!ctx.atLeast(ctx.pathAccess(by.username(), by.role(), (String) r.get("path")), "view")) continue;
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.put("can_decide", "pending".equals(r.get("status")) && ctx.can(by, "repo.approve") && !by.username().equals(r.get("requested_by")));
            out.add(m);
            if (out.size() >= 500) break;
        }
        out.sort(Comparator.comparing((Map<String, Object> m) -> !"pending".equals(m.get("status"))));
        return out;
    }

    public int pendingCount(User by) { return (int) list(by, "pending").stream().filter(m -> Boolean.TRUE.equals(m.get("can_decide"))).count(); }

    public Map<String, Object> get(String id) { return db.one("SELECT id, path, version, requested_by, requested, comment, status, decided_by, decided, note FROM production_requests WHERE id = ?", id); }

    private static Instant now() { return Instant.now(); }
}
