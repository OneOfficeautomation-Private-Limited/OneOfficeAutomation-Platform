package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.controlroom.ControlRoom.User;
import com.oneofficeautomation.conversation.ChatEngine.ChatBot;
import com.oneofficeautomation.conversation.ChatPlatform;
import com.oneofficeautomation.conversation.Conversation;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;

import java.nio.file.Path;
import java.time.Clock;
import java.util.*;

import static com.oneofficeautomation.controlroom.ControlRoom.*;

/**
 * One Chat in the Command Center. The conversation itself (engines, confirmation, approval rules) is the conversation
 * module; this class is its host: it decides which agents a person may run (runs.start and folder run access, production
 * versions only), starts runs as that person on the chat_runner runner or pool, puts R3 requests in the Task inbox for
 * the chat_approvers, calls models through the AI Gateway (ChatModel) and writes the One Audit Trail.
 */
public final class Chat implements ChatPlatform {
    /** The stage name that marks a Task inbox approval or notice as One Chat's (it has no workflow run). */
    static final String APPROVAL_STAGE = "chat_approval";
    static final List<String> MODES = Conversation.MODES;

    private final ControlRoom cr;
    private final ChatModel model;
    private final Conversation conversation;

    Chat(ControlRoom cr, Path dataDir) { this(cr, dataDir, Clock.systemDefaultZone()); }

    Chat(ControlRoom cr, Path dataDir, Clock clock) {
        this.cr = cr;
        this.model = new ChatModel(cr, dataDir);
        this.conversation = new Conversation(this, clock);
    }

    // ---- what the API, the Task inbox and the console call ----

    /** Handles one message from a signed-in person; see {@link Conversation#message}. */
    public Map<String, Object> message(User u, String sessionId, String text, String ip) {
        return conversation.message(person(u, ip), sessionId, text);
    }

    /** The messages of one of the person's chats, oldest first. */
    public List<Map<String, Object>> history(User u, String sessionId) { return conversation.history(u.username(), sessionId); }

    /** The person who asked for a chat approval task, or null for other tasks. */
    @SuppressWarnings("unchecked")
    static String requester(Map<String, Object> task) {
        if (!APPROVAL_STAGE.equals(task.get("stage")) || task.get("data") == null) return null;
        return String.valueOf(((Map<String, Object>) JsonLite.parse((String) task.get("data"))).get("requested_by"));
    }

    /** Called by the Task inbox once an approver decides a chat approval task; returns the run id when one started. */
    @SuppressWarnings("unchecked")
    String decided(Map<String, Object> task, User approver, String outcome, String comment, String ip) {
        return conversation.decided(String.valueOf(task.get("id")), (Map<String, Object>) JsonLite.parse((String) task.get("data")),
                person(approver, ip), "approved".equals(outcome), comment);
    }

    private static Person person(User u, String ip) { return new Person(u.username(), u.role(), ip); }

    private static User user(Person p) { return new User(p.username(), p.username(), p.role(), p.username(), false); }

    // ---- ChatPlatform: the Command Center's services for the conversation module ----

    @Override @SuppressWarnings("unchecked")
    public List<ChatBot> agents(Person p) {
        List<ChatBot> out = new ArrayList<>();
        if (!cr.can(user(p), "runs.start")) return out;
        for (Map<String, Object> b : cr.db.query("SELECT path FROM bots WHERE production_version IS NOT NULL AND deleted_at IS NULL ORDER BY path")) {
            String path = (String) b.get("path");
            if (!path.endsWith(".flow.json")) continue;
            try {
                cr.folders.require(p.username(), p.role(), path, "run", "run agents");
                out.add(ChatBot.of(path, (Map<String, Object>) cr.content(path, "production").get("flow")));
            } catch (OoaException e) { /* no access, or an unreadable version: not offered */ }
        }
        return out;
    }

    @Override public boolean canViewRuns(Person p) { return cr.can(user(p), "runs.view"); }

    @Override
    public String startRun(Person p, String path, Map<String, Object> inputs) {
        String name = path.replaceAll("\\.flow\\.json$", "");
        String target = setting("chat_runner").trim();
        String device = null, pool = null;
        if (!target.isEmpty() && (target.startsWith("pool_") || cr.db.one("SELECT id FROM device_pools WHERE id = ?", target) != null)) pool = target;
        else if (!target.isEmpty()) device = target;
        else device = cr.platform.onlineRunner();
        if (device == null && pool == null) throw new Refused("no_runner", "No runner is online to run " + name + " right now. Try again later.");
        try {
            return (String) cr.startRun(p.username(), p.role(), path, device, pool, inputs, "chat", p.ip(), 5, null).get("run_id");
        } catch (OoaException e) {
            throw new Refused("refused", "The Command Center did not start " + name + ": " + e.getMessage() + (e.suggestedFix() == null ? "" : " " + e.suggestedFix()));
        }
    }

    @Override
    public List<Map<String, Object>> recentRuns(Person p, int limit) {
        return cr.db.query("SELECT id, bot_path, status, created FROM runs WHERE requested_by = ? ORDER BY created DESC LIMIT ?", p.username(), limit).stream()
                .map(r -> Map.<String, Object>of("run_id", r.get("id"), "agent", r.get("bot_path"), "status", r.get("status"))).toList();
    }

    /** An approval task in the Task inbox for the chat_approvers (default role:admin); never decided by the person who asked. */
    @Override
    public String requestApproval(Person p, ApprovalRequest r) {
        String taskId = id("task");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("source", "chat");
        data.put("agent", r.agent());
        data.put("inputs", r.inputs());
        data.put("requested_by", p.username());
        data.put("session_id", r.sessionId());
        data.put("confirmation", r.confirmationId());
        data.put("input_hash", r.inputHash());
        String approvers = setting("chat_approvers").trim();
        cr.db.update("INSERT INTO human_tasks (id, workflow_run_id, stage, kind, title, description, data, assignee, status, created) VALUES (?,?,?,?,?,?,?,?,?,?)",
                taskId, null, APPROVAL_STAGE, "approval", "Approve " + r.agentName() + " for " + p.username(),
                p.username() + " asked One Chat: " + r.summary() + " It runs as " + p.username() + " once you approve.",
                JsonLite.write(data), approvers.isEmpty() ? "role:admin" : approvers, "open", now());
        return taskId;
    }

    @Override
    public void notify(String username, String title, String message, Map<String, Object> data) {
        cr.db.update("INSERT INTO human_tasks (id, workflow_run_id, stage, kind, title, description, data, assignee, status, created) VALUES (?,?,?,?,?,?,?,?,?,?)",
                id("task"), null, APPROVAL_STAGE, "notification", title, message, JsonLite.write(data), "user:" + username, "open", now());
    }

    @Override
    public Person person(String username) {
        Map<String, Object> u = cr.db.one("SELECT role FROM users WHERE username = ?", username);
        return u == null ? null : new Person(username, (String) u.get("role"), null);
    }

    @Override public String setting(String name) { Object v = cr.platform.setting(name); return v == null ? "" : String.valueOf(v); }

    @Override
    public Map<String, Object> callModel(Person p, String connection, List<Map<String, Object>> messages, List<Map<String, Object>> tools, String purpose) {
        return model.call(user(p), connection, messages, tools, purpose);
    }

    @Override
    public void audit(Person p, String action, String objectType, String objectId, Map<String, Object> details) {
        cr.audit.record(p.username(), action, objectType, objectId, details, "ok", p.ip());
    }

    @Override public String newId(String prefix) { return id(prefix); }

    @Override @SuppressWarnings("unchecked")
    public Map<String, Object> loadSession(String id) {
        Map<String, Object> r = cr.db.one("SELECT data FROM chat_sessions WHERE id = ?", id);
        return r == null ? null : (Map<String, Object>) JsonLite.parse((String) r.get("data"));
    }

    @Override
    public void saveSession(String id, String username, Map<String, Object> data) {
        String json = JsonLite.write(data);
        if (cr.db.update("UPDATE chat_sessions SET data = ?, updated = ? WHERE id = ? AND username = ?", json, now(), id, username) == 0)
            cr.db.update("INSERT INTO chat_sessions (id, username, data, updated) VALUES (?,?,?,?)", id, username, json, now());
    }

    @Override public void deleteSessionsIdleSince(java.time.Instant cutoff) { cr.db.update("DELETE FROM chat_sessions WHERE updated < ?", cutoff); }
}
