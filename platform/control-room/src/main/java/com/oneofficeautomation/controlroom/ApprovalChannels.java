package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.controlroom.ControlRoom.User;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static com.oneofficeautomation.controlroom.ControlRoom.now;

/**
 * Approving outside the Task inbox (gap G43): from an e-mail (Outlook or any mail program), a Teams channel, and a phone.
 * <ul>
 * <li><b>Action links.</b> Each person a task is given to gets their own Approve and Reject links, signed with a key
 * derived from the audit chain key and valid for task_link_hours. A link opens a page that asks to confirm (so a mail
 * scanner that opens links decides nothing); confirming completes the task as that person, with the same checks as the
 * inbox (still assigned to them, still open). Off until an administrator turns on task_action_links; needs public_url.</li>
 * <li><b>Teams.</b> With a Teams Workflows webhook (teams_approval_webhook_ref, a vault reference to its address), a new
 * task posts an Adaptive Card to the channel: the request, its details and, when one person is assigned, their Approve
 * and Reject links; otherwise a link to the task.</li>
 * <li><b>Phone.</b> Push notifications through an ntfy server (push_server; ntfy.sh or one run on the company's own
 * network), each person subscribed to a private topic made for them in their profile, with the action links as buttons.</li>
 * </ul>
 */
final class ApprovalChannels {
    private final ControlRoom cr;
    private final Db db;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    private volatile byte[] linkKey;

    ApprovalChannels(ControlRoom cr) { this.cr = cr; this.db = cr.db; }

    boolean linksOn() { return cr.platform.intSetting("task_action_links") == 1 && !publicUrl().isEmpty(); }
    String publicUrl() { return String.valueOf(cr.platform.setting("public_url")).trim().replaceAll("/+$", ""); }

    // ---------- signed action links ----------

    /** The links' own key: the audit chain key put through HMAC with a label, so neither key can stand in for the other. */
    private byte[] key() {
        byte[] k = linkKey;
        if (k == null) linkKey = k = hmac(cr.auditKey(), "ooa task action links v1".getBytes(StandardCharsets.UTF_8));
        return k;
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            return m.doFinal(data);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    /** A link token: task, person, decision and expiry, signed. */
    String token(String taskId, String username, String decision, Instant expires) {
        String payload = taskId + "\n" + username + "\n" + decision + "\n" + expires.getEpochSecond();
        byte[] p = payload.getBytes(StandardCharsets.UTF_8);
        return B64.encodeToString(p) + "." + B64.encodeToString(hmac(key(), p));
    }

    record Act(String task, String username, String decision, Instant expires) { }

    Act verify(String token) {
        OoaException bad = new OoaException("CR.LINK_INVALID", "This approval link is not valid", "Open the task in One CommandCenter (Workflows > Tasks).");
        if (token == null || token.length() > 1000) throw bad;
        int dot = token.indexOf('.');
        if (dot < 1) throw bad;
        byte[] p, sig;
        try { p = Base64.getUrlDecoder().decode(token.substring(0, dot)); sig = Base64.getUrlDecoder().decode(token.substring(dot + 1)); }
        catch (IllegalArgumentException e) { throw bad; }
        if (!MessageDigest.isEqual(sig, hmac(key(), p))) throw bad;
        String[] f = new String(p, StandardCharsets.UTF_8).split("\n");
        if (f.length != 4 || !Set.of("approved", "rejected").contains(f[2])) throw bad;
        Instant exp = Instant.ofEpochSecond(Long.parseLong(f[3]));
        if (exp.isBefore(now())) throw new OoaException("CR.LINK_EXPIRED", "This approval link has expired", "Open the task in One CommandCenter (Workflows > Tasks).");
        return new Act(f[0], f[1], f[2], exp);
    }

    String link(String taskId, String username, String decision) {
        int hours = Math.max(1, cr.platform.intSetting("task_link_hours"));
        return publicUrl() + "/api/tasks/act?t=" + token(taskId, username, decision, now().plus(Duration.ofHours(hours)));
    }

    // ---------- a new task: e-mail, phone and Teams ----------

    /** The user names a task's assignee stands for (user:NAME, role:NAME; not role:* or perm:, which mean too many people). */
    List<String> people(String assignee) {
        Set<String> names = new LinkedHashSet<>();
        for (String a : String.valueOf(assignee).split(",")) {
            String t = a.trim();
            if (t.startsWith("user:")) names.add(t.substring(5));
            else if (t.startsWith("role:") && !t.equals("role:*"))
                for (Map<String, Object> u : db.query("SELECT username FROM users WHERE role = ? UNION SELECT username FROM user_roles WHERE role = ?", t.substring(5), t.substring(5)))
                    names.add((String) u.get("username"));
            if (names.size() >= 50) break;
        }
        List<String> out = new ArrayList<>();
        for (String n : names) if (db.one("SELECT username FROM users WHERE username = ? AND enabled = TRUE", n) != null) out.add(n);
        return out;
    }

    /**
     * Tells the people a new form or approval is for, in the background: e-mail (Settings: notify_task_assigned), each
     * person's phone (when they turned it on), and the Teams channel (when set). Approvals carry each person's own links.
     */
    void taskCreated(String taskId, String kind, String title, String assignee, Instant due, Map<String, Object> data) {
        boolean approval = "approval".equals(kind), links = approval && linksOn();
        List<String> people = people(assignee);
        String inbox = publicUrl().isEmpty() ? "" : publicUrl() + "/next/#/tasks";
        Thread.ofVirtual().start(() -> {
            for (String person : people) {
                String approve = links ? link(taskId, person, "approved") : null, reject = links ? link(taskId, person, "rejected") : null;
                if (cr.platform.intSetting("notify_task_assigned") == 1 && cr.mailer.configured()) {
                    List<String> to = cr.addressesOf(List.of(person));
                    if (!to.isEmpty()) {
                        StringBuilder b = new StringBuilder("You have a new " + (approval ? "approval" : "form") + " to complete: " + title + ".");
                        if (due != null) b.append("\nIt is due by ").append(due).append('.');
                        String details = details(data, 10);
                        if (!details.isEmpty()) b.append("\n\n").append(details);
                        if (links) b.append("\n\nApprove: ").append(approve).append("\nReject: ").append(reject)
                                .append("\n\nEach link asks you to confirm. They are yours only: do not forward this e-mail.");
                        b.append("\n\n").append(inbox.isEmpty() ? "Open One CommandCenter to see it (Workflows > Tasks)." : "See it: " + inbox);
                        try { cr.mailer.send(to, "A task for you: " + title, b.toString()); cr.audit.record("system", "mail.notify", "task", taskId, Map.of("to", to.size()), "ok", null); }
                        catch (RuntimeException e) { cr.audit.record("system", "mail.notify", "task", taskId, String.valueOf(e.getMessage()), "failed", null); }
                    }
                }
                String topic = topic(person);
                if (topic != null) {
                    try { push(topic, "A task for you", title + (due == null ? "" : " (due " + due + ")"), inbox, links ? List.of(new String[] { "Approve", approve }, new String[] { "Reject", reject }) : List.of());
                        cr.audit.record("system", "push.notify", "task", taskId, Map.of("to", person), "ok", null); }
                    catch (RuntimeException e) { cr.audit.record("system", "push.notify", "task", taskId, String.valueOf(e.getMessage()), "failed", null); }
                }
            }
            String hook = String.valueOf(cr.platform.setting("teams_approval_webhook_ref")).trim();
            if (!hook.isEmpty()) {
                String single = assignee != null && assignee.trim().startsWith("user:") && !assignee.contains(",") ? assignee.trim().substring(5) : null;
                try {
                    teams(cr.systemCredential(hook), card(title, approval, due, data, links && single != null && people.contains(single) ? link(taskId, single, "approved") : null,
                            links && single != null && people.contains(single) ? link(taskId, single, "rejected") : null, single, inbox));
                    cr.audit.record("system", "teams.notify", "task", taskId, null, "ok", null);
                } catch (RuntimeException e) { cr.audit.record("system", "teams.notify", "task", taskId, String.valueOf(e.getMessage()), "failed", null); }
            }
        });
    }

    /** The task's visible values as "name: value" lines (at most n; internal values starting with _ left out). */
    static String details(Map<String, Object> data, int n) {
        StringBuilder b = new StringBuilder();
        int k = 0;
        for (Map.Entry<String, Object> e : data.entrySet()) {
            if (e.getKey().startsWith("_") || e.getValue() instanceof Map || e.getValue() instanceof List) continue;
            if (k++ >= n) break;
            String v = String.valueOf(e.getValue());
            b.append(e.getKey()).append(": ").append(v.length() > 200 ? v.substring(0, 200) + "…" : v).append('\n');
        }
        return b.toString().strip();
    }

    // ---------- Teams: an Adaptive Card through a Workflows webhook ----------

    static Map<String, Object> card(String title, boolean approval, Instant due, Map<String, Object> data, String approve, String reject, String single, String inbox) {
        List<Object> body = new ArrayList<>();
        body.add(Map.of("type", "TextBlock", "size", "Medium", "weight", "Bolder", "wrap", true, "text", (approval ? "Approval needed: " : "Task: ") + title));
        if (single != null) body.add(Map.of("type", "TextBlock", "wrap", true, "isSubtle", true, "text", "For " + single + (due == null ? "" : ", due " + due)));
        else if (due != null) body.add(Map.of("type", "TextBlock", "wrap", true, "isSubtle", true, "text", "Due " + due));
        List<Object> facts = new ArrayList<>();
        for (String line : details(data, 8).split("\n")) {
            int c = line.indexOf(": ");
            if (c > 0) facts.add(Map.of("title", line.substring(0, c), "value", line.substring(c + 2)));
        }
        if (!facts.isEmpty()) body.add(Map.of("type", "FactSet", "facts", facts));
        List<Object> actions = new ArrayList<>();
        if (approve != null) {
            actions.add(Map.of("type", "Action.OpenUrl", "title", "Approve", "url", approve, "style", "positive"));
            actions.add(Map.of("type", "Action.OpenUrl", "title", "Reject", "url", reject, "style", "destructive"));
        }
        if (!inbox.isEmpty()) actions.add(Map.of("type", "Action.OpenUrl", "title", "Open the task", "url", inbox));
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("$schema", "http://adaptivecards.io/schemas/adaptive-card.json"); content.put("type", "AdaptiveCard"); content.put("version", "1.4");
        content.put("body", body); content.put("actions", actions);
        return Map.of("type", "message", "attachments", List.of(Map.of("contentType", "application/vnd.microsoft.card.adaptive", "content", content)));
    }

    void teams(String webhook, Map<String, Object> message) {
        if (!webhook.matches("(?i)https://[^\\s]+")) throw new OoaException("CR.BAD_INPUT", "The Teams webhook is an https:// address");
        post(webhook, JsonLite.write(message), Map.of("Content-Type", "application/json"));
    }

    Map<String, Object> teamsTest(User by) {
        cr.require(by, "settings.manage", "test the Teams channel");
        String hook = String.valueOf(cr.platform.setting("teams_approval_webhook_ref")).trim();
        if (hook.isEmpty()) throw new OoaException("CR.BAD_INPUT", "Set the Teams webhook first", "Keep its address in the vault and give the reference.");
        teams(cr.systemCredential(hook), card("Test from One CommandCenter", false, null, Map.of("sent_by", by.username()), null, null, null, publicUrl()));
        cr.audit.record(by.username(), "teams.test", "settings", null, null, "ok", null);
        return Map.of("ok", true);
    }

    // ---------- phone: ntfy push ----------

    private static final String TOPIC = "push_topic:";

    /** The person's private push topic, or null when they have not turned phone notifications on (or no server is set). */
    String topic(String username) {
        if (String.valueOf(cr.platform.setting("push_server")).isBlank()) return null;
        Map<String, Object> r = db.one("SELECT json_value FROM settings WHERE name = ?", TOPIC + username);
        if (r == null) return null;
        try { return cr.vault.decrypt(String.valueOf(JsonLite.parse((String) r.get("json_value"))), TOPIC + username); }
        catch (RuntimeException e) { return null; }
    }

    Map<String, Object> pushStatus(User u) {
        String server = String.valueOf(cr.platform.setting("push_server")).trim().replaceAll("/+$", "");
        String t = topic(u.username());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("available", !server.isEmpty()); m.put("enabled", t != null); m.put("server", server);
        if (t != null) { m.put("topic", t); m.put("subscribe_url", server + "/" + t); }
        return m;
    }

    /** Turns phone notifications on: a new random topic (the old one stops working), kept encrypted. */
    Map<String, Object> pushEnable(User u, String ip) {
        if (String.valueOf(cr.platform.setting("push_server")).isBlank()) throw new OoaException("CR.BAD_INPUT", "Phone notifications are not set up", "Ask an administrator to set the push server in Settings.");
        byte[] r = new byte[18];
        Security.RANDOM.nextBytes(r);
        String topic = "ooa-" + B64.encodeToString(r).replace('-', 'x').replace('_', 'y');
        String stored = JsonLite.write(cr.vault.encrypt(topic, TOPIC + u.username()));
        db.tx(() -> {
            db.update("DELETE FROM settings WHERE name = ?", TOPIC + u.username());
            db.update("INSERT INTO settings (name, json_value, updated, updated_by) VALUES (?,?,?,?)", TOPIC + u.username(), stored, now(), u.username());
            return null;
        });
        cr.audit.record(u.username(), "push.enable", "user", u.username(), null, "ok", ip);
        return pushStatus(u);
    }

    void pushDisable(User u, String ip) {
        db.update("DELETE FROM settings WHERE name = ?", TOPIC + u.username());
        cr.audit.record(u.username(), "push.disable", "user", u.username(), null, "ok", ip);
    }

    Map<String, Object> pushTest(User u) {
        String t = topic(u.username());
        if (t == null) throw new OoaException("CR.BAD_INPUT", "Turn phone notifications on first");
        push(t, "One CommandCenter", "Phone notifications work. New tasks for you will appear here.", publicUrl().isEmpty() ? "" : publicUrl() + "/next/#/tasks", List.of());
        return Map.of("ok", true);
    }

    /** One ntfy message: title, text, where a tap goes, and up to three buttons that open a web address. */
    void push(String topic, String title, String message, String click, List<String[]> buttons) {
        String server = String.valueOf(cr.platform.setting("push_server")).trim().replaceAll("/+$", "");
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Title", ascii(title));
        h.put("Tags", "inbox_tray");
        if (!click.isEmpty()) h.put("Click", click);
        if (!buttons.isEmpty()) {
            StringBuilder a = new StringBuilder();
            for (String[] b : buttons) { if (a.length() > 0) a.append("; "); a.append("view, ").append(b[0]).append(", ").append(b[1]).append(", clear=true"); }
            h.put("Actions", a.toString());
        }
        String ref = String.valueOf(cr.platform.setting("push_token_ref")).trim();
        if (!ref.isEmpty()) h.put("Authorization", "Bearer " + cr.systemCredential(ref));
        post(server + "/" + URLEncoder.encode(topic, StandardCharsets.UTF_8), message, h);
    }

    /** Header values must be plain ASCII; ntfy shows the title as given otherwise through the body. */
    private static String ascii(String s) { return s.replaceAll("[^\\x20-\\x7E]", "?"); }

    private void post(String url, String body, Map<String, String> headers) {
        if (cr.tenant != null) NetGuard.check(cr, url, "The notification address");
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        headers.forEach(b::header);
        try {
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2) throw new OoaException("CR.NOTIFY_FAILED", "The notification service answered HTTP " + r.statusCode(), null);
        } catch (java.io.IOException e) { throw new OoaException("CR.NOTIFY_FAILED", "Cannot reach " + URI.create(url).getHost() + ": " + e.getMessage(), null); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new OoaException("CR.NOTIFY_FAILED", "Interrupted", null); }
    }

    // ---------- the page a link opens ----------

    /** The confirm page: what the task is, the decision, an optional comment and one button. */
    String page(String token) {
        Act a;
        try { a = verify(token); } catch (OoaException e) { return html("Approval link", "<p>" + esc(e.getMessage()) + ".</p><p>" + esc(String.valueOf(e.suggestedFix())) + "</p>"); }
        Map<String, Object> t = db.one("SELECT title, status, outcome, data FROM human_tasks WHERE id = ?", a.task());
        if (t == null) return html("Approval link", "<p>The task no longer exists.</p>");
        if (!Set.of("open", "claimed").contains(String.valueOf(t.get("status"))))
            return html("Already done", "<p>" + esc(String.valueOf(t.get("title"))) + " is already " + esc(String.valueOf(t.get("outcome") == null ? t.get("status") : t.get("outcome"))) + ".</p>");
        @SuppressWarnings("unchecked") Map<String, Object> data = t.get("data") == null ? Map.of() : (Map<String, Object>) JsonLite.parse((String) t.get("data"));
        boolean yes = a.decision().equals("approved");
        String rows = details(data, 20).lines().map(l -> { int c = l.indexOf(": "); return c < 0 ? "" : "<tr><th>" + esc(l.substring(0, c)) + "</th><td>" + esc(l.substring(c + 2)) + "</td></tr>"; })
                .reduce("", String::concat);
        return html((yes ? "Approve: " : "Reject: ") + t.get("title"),
                "<p>Signed for <strong>" + esc(a.username()) + "</strong>. Nothing is decided until you press the button.</p>"
                + (rows.isEmpty() ? "" : "<table>" + rows + "</table>")
                + "<form method=\"post\" action=\"act\"><input type=\"hidden\" name=\"t\" value=\"" + esc(token) + "\">"
                + "<label for=\"c\">Comment (optional)</label><textarea id=\"c\" name=\"comment\" rows=\"3\" maxlength=\"2000\"></textarea>"
                + "<button type=\"submit\" class=\"" + (yes ? "yes" : "no") + "\">" + (yes ? "Approve" : "Reject") + "</button></form>");
    }

    /** Confirming: completes the task as the person the link was made for, with the inbox's own checks. */
    String act(String token, String comment, String ip) {
        Act a;
        try { a = verify(token); } catch (OoaException e) { return html("Approval link", "<p>" + esc(e.getMessage()) + ".</p>"); }
        Map<String, Object> row = db.one("SELECT * FROM users WHERE username = ? AND enabled = TRUE", a.username());
        if (row == null) return html("Approval link", "<p>The account " + esc(a.username()) + " is not active.</p>");
        try {
            cr.tasks.complete(ControlRoom.user(row), a.task(), a.decision(), null, comment == null ? null : comment.strip(), ip == null ? "link" : ip);
            cr.audit.record(a.username(), "task.link_used", "task", a.task(), Map.of("decision", a.decision()), "ok", ip);
            return html(a.decision().equals("approved") ? "Approved" : "Rejected", "<p>Done: you " + (a.decision().equals("approved") ? "approved" : "rejected")
                    + " the request. You can close this window.</p>");
        } catch (OoaException e) {
            return html("Not done", "<p>" + esc(e.getMessage()) + ".</p>");
        }
    }

    static String esc(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }

    static String html(String title, String body) {
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<meta name=\"referrer\" content=\"no-referrer\"><title>" + esc(title) + " - One CommandCenter</title><style>"
                + "body{font:15px/1.45 'Segoe UI',system-ui,sans-serif;margin:0;background:#f4f6f9;color:#1f2933}main{max-width:560px;margin:32px auto;background:#fff;"
                + "border:1px solid #cbd2d9;border-radius:8px;padding:20px 22px}h1{font-size:19px;margin:0 0 10px}table{border-collapse:collapse;width:100%;margin:10px 0}"
                + "th,td{text-align:left;border-bottom:1px solid #e4e7eb;padding:5px 6px;vertical-align:top}th{width:35%;color:#3e4c59;font-weight:600}"
                + "label{display:block;margin:12px 0 4px;font-weight:600}textarea{width:100%;box-sizing:border-box;font:inherit;border:1px solid #9aa5b1;border-radius:4px;padding:6px}"
                + "button{margin-top:14px;font:inherit;font-weight:600;color:#fff;border:0;border-radius:4px;padding:9px 18px;cursor:pointer}button.yes{background:#1d6b38}"
                + "button.no{background:#b42318}button:focus-visible{outline:3px solid #1d4ed8;outline-offset:2px}</style></head><body><main><h1>" + esc(title) + "</h1>"
                + body + "</main></body></html>";
    }
}
