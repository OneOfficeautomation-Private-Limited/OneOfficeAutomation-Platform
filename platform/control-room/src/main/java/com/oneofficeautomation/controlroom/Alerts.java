package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.controlroom.ControlRoom.User;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;

import java.time.*;
import java.util.*;

import static com.oneofficeautomation.controlroom.ControlRoom.*;

/**
 * Alerts (Operational Insights > Alerts), as UiPath's real-time monitoring alerts on processes, queues and machines and
 * AA's notifications (checked 31 Oct 2026). A rule watches one condition; the server clock checks the rules once a
 * minute on the leader node. Each condition met raises one alert per subject (a run, an agent, a runner, a queue, a
 * connection) and sends it to the task inbox (user:NAME, role:NAME), to e-mail addresses (Settings > E-mail) and to a
 * webhook. Alerts on a state (a failure rate, a runner offline, a queue overdue, a run running long) clear by
 * themselves when the state ends; alerts on an event (a failed run) stay open until someone acknowledges them.
 * <pre>
 *   kind            scope (optional)   threshold                   window_minutes
 *   run_failed      agent path         -                           -
 *   failure_rate    agent path         % of finished runs (50)     the period looked at (60)
 *   run_long        agent path         -                           running longer than (60)
 *   runner_offline  runner id          -                           silent for (5)
 *   queue_overdue   queue id           overdue items (1)           -
 *   ai_budget       model connection   % of the monthly budget (80) -
 * </pre>
 * Viewing alerts and rules needs analytics.view (alerts about agents only for those who may see the agent); changing
 * rules needs alerts.manage.
 */
public final class Alerts {
    static final List<String> KINDS = List.of("run_failed", "failure_rate", "run_long", "runner_offline", "queue_overdue", "ai_budget");
    /** Kinds that raise an alert per event (cleared by acknowledging); the others follow a state. */
    static final Set<String> EVENTS = Set.of("run_failed");
    static final Set<String> AGENT_KINDS = Set.of("run_failed", "failure_rate", "run_long");
    /** At most this many new alerts per rule per check: a storm of failures raises a summary for the rest (up to 1,000 failures a check are read). */
    static final int BURST = 20;
    /** Finished runs needed before a failure rate counts. */
    static final int MIN_RUNS = 3;
    private final ControlRoom cr;
    private final Db db;
    private Instant lastCheck = Instant.EPOCH;

    Alerts(ControlRoom cr) { this.cr = cr; this.db = cr.db; }

    // ---------- rules ----------

    public List<Map<String, Object>> rules(User by) {
        cr.require(by, "analytics.view", "view alert rules");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : db.query("SELECT * FROM alert_rules ORDER BY name")) {
            if (AGENT_KINDS.contains(r.get("kind")) && r.get("scope") != null && !cr.folders.mayView(by, (String) r.get("scope"))) continue;
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.put("notify_to", csv(r.get("notify_to")));
            m.put("open", cr.count("SELECT COUNT(*) AS n FROM alerts WHERE rule_id = ? AND status = 'open'", r.get("id")));
            out.add(m);
        }
        return out;
    }

    public Map<String, Object> saveRule(User by, String id, Map<String, Object> b, String ip) {
        cr.require(by, "alerts.manage", "manage alert rules");
        String name = String.valueOf(b.getOrDefault("name", "")).trim();
        if (name.isEmpty() || name.length() > 120) throw new OoaException("CR.BAD_INPUT", "Alert rule names have 1 to 120 characters");
        String kind = String.valueOf(b.get("kind"));
        if (!KINDS.contains(kind)) throw new OoaException("CR.BAD_INPUT", "Alert kinds: " + KINDS);
        String scope = b.get("scope") instanceof String s && !s.isBlank() ? s.trim() : null;
        if (scope != null) checkScope(by, kind, scope);
        Double threshold = switch (kind) {
            case "failure_rate" -> number(b.get("threshold"), 50, 1, 100, "The failure rate is 1 to 100%");
            case "queue_overdue" -> number(b.get("threshold"), 1, 1, 1_000_000, "Overdue items are 1 or more");
            case "ai_budget" -> number(b.get("threshold"), 80, 1, 100, "The share of the budget is 1 to 100%");
            default -> null;
        };
        Integer window = switch (kind) {
            case "failure_rate" -> (int) (double) number(b.get("window_minutes"), 60, 5, 10_080, "The period is 5 minutes to 7 days");
            case "run_long" -> (int) (double) number(b.get("window_minutes"), 60, 1, 10_080, "Running longer than 1 minute to 7 days");
            case "runner_offline" -> (int) (double) number(b.get("window_minutes"), 5, 1, 1_440, "Silent for 1 minute to 1 day");
            default -> null;
        };
        List<String> to = new ArrayList<>();
        if (b.get("notify_to") instanceof List<?> l) for (Object o : l) {
            String s = String.valueOf(o).trim();
            if (s.isEmpty()) continue;
            if (!s.matches("(role|user):[A-Za-z0-9_.@-]{1,80}") && !s.matches("[^@\\s,]{1,80}@[^@\\s,]{1,200}"))
                throw new OoaException("CR.BAD_INPUT", "Send to user:NAME, role:NAME or an e-mail address: " + s);
            to.add(s);
        }
        if (to.size() > 30) throw new OoaException("CR.BAD_INPUT", "At most 30 recipients");
        String webhook = b.get("webhook_url") instanceof String w && !w.isBlank() ? w.trim() : null;
        if (webhook != null) {
            if (!webhook.matches("https?://\\S{1,990}")) throw new OoaException("CR.BAD_INPUT", "The webhook is an http or https address");
            NetGuard.check(cr, webhook, "The webhook");
        }
        if (to.isEmpty() && webhook == null) throw new OoaException("CR.BAD_INPUT", "Send the alert to someone or to a webhook");
        String severity = "critical".equals(b.get("severity")) ? "critical" : "warning";
        boolean enabled = !Boolean.FALSE.equals(b.get("enabled"));
        if (id == null || id.isBlank()) {
            id = id("alrule");
            // last_checked: a new rule counts what happens from now on, not what happened before it
            db.update("INSERT INTO alert_rules (id, name, kind, scope, threshold, window_minutes, notify_to, webhook_url, severity, enabled, created_by, created, updated, updated_by, last_checked) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", id, name, kind, scope, threshold, window, String.join(",", to), webhook, severity, enabled, by.username(), now(), now(), by.username(), now());
        } else {
            if (db.one("SELECT id FROM alert_rules WHERE id = ?", id) == null) throw notFound("Alert rule");
            db.update("UPDATE alert_rules SET name = ?, kind = ?, scope = ?, threshold = ?, window_minutes = ?, notify_to = ?, webhook_url = ?, severity = ?, enabled = ?, updated = ?, updated_by = ? WHERE id = ?",
                    name, kind, scope, threshold, window, String.join(",", to), webhook, severity, enabled, now(), by.username(), id);
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("name", name); details.put("kind", kind); details.put("scope", scope); details.put("notify_to", to); details.put("webhook", webhook != null); details.put("enabled", enabled);
        cr.audit.record(by.username(), "alert.rule.save", "alert_rule", id, details, "ok", ip);
        return Map.of("id", id);
    }

    private void checkScope(User by, String kind, String scope) {
        switch (kind) {
            case "run_failed", "failure_rate", "run_long" -> cr.folders.require(by, scope, "view", "watch this agent");
            case "runner_offline" -> { if (db.one("SELECT id FROM devices WHERE id = ?", scope) == null) throw notFound("Runner"); }
            case "queue_overdue" -> { if (db.one("SELECT id FROM queues WHERE id = ?", scope) == null) throw notFound("Work queue"); }
            case "ai_budget" -> { if (db.one("SELECT name FROM model_connections WHERE name = ?", scope) == null) throw notFound("Model connection"); }
            default -> { }
        }
    }

    private static double number(Object v, double dflt, double min, double max, String rule) {
        if (v == null || String.valueOf(v).isBlank()) return dflt;
        double d;
        try { d = v instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(v).trim()); }
        catch (NumberFormatException e) { throw new OoaException("CR.BAD_INPUT", rule); }
        if (d < min || d > max) throw new OoaException("CR.BAD_INPUT", rule);
        return d;
    }

    public void setEnabled(User by, String id, boolean enabled, String ip) {
        cr.require(by, "alerts.manage", "manage alert rules");
        if (db.update("UPDATE alert_rules SET enabled = ?, updated = ?, updated_by = ? WHERE id = ?", enabled, now(), by.username(), id) == 0) throw notFound("Alert rule");
        cr.audit.record(by.username(), enabled ? "alert.rule.enable" : "alert.rule.disable", "alert_rule", id, null, "ok", ip);
    }

    public void deleteRule(User by, String id, String ip) {
        cr.require(by, "alerts.manage", "manage alert rules");
        if (db.update("DELETE FROM alert_rules WHERE id = ?", id) == 0) throw notFound("Alert rule");
        db.update("UPDATE alerts SET status = 'cleared', cleared = ? WHERE rule_id = ? AND status <> 'cleared'", now(), id);   // their history stays
        cr.audit.record(by.username(), "alert.rule.delete", "alert_rule", id, null, "ok", ip);
    }

    /** Sends a test alert through the rule's recipients and webhook, to check they arrive. */
    public Map<String, Object> test(User by, String id, String ip) {
        cr.require(by, "alerts.manage", "manage alert rules");
        Map<String, Object> rule = db.one("SELECT * FROM alert_rules WHERE id = ?", id);
        if (rule == null) throw notFound("Alert rule");
        Map<String, Object> sent = deliver(rule, "test_" + id("t"), "Test of the alert rule " + rule.get("name") + ", sent by " + by.username() + ".", "#/alerts", null);
        cr.audit.record(by.username(), "alert.rule.test", "alert_rule", id, sent, "ok", ip);
        return sent;
    }

    // ---------- alerts ----------

    /** Alerts, newest first: status open (the default), acknowledged, cleared or all; rule; paged. */
    public Map<String, Object> list(User by, Map<String, String> q) {
        cr.require(by, "analytics.view", "view alerts");
        List<String> where = new ArrayList<>();
        List<Object> p = new ArrayList<>();
        String status = q.getOrDefault("status", "open");
        if (Set.of("open", "acknowledged", "cleared").contains(status)) { where.add("status = ?"); p.add(status); }
        if (q.get("rule") != null && !q.get("rule").isBlank()) { where.add("rule_id = ?"); p.add(q.get("rule")); }
        String w = where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where);
        int limit = Math.max(1, Math.min(500, intOr(q.get("limit"), 100))), offset = Math.max(0, intOr(q.get("offset"), 0));
        List<Map<String, Object>> out = new ArrayList<>();
        int skipped = 0;
        for (Map<String, Object> a : db.query("SELECT * FROM alerts" + w + " ORDER BY raised DESC LIMIT " + (limit + offset + 500), p.toArray())) {
            if (a.get("bot_path") != null && !cr.folders.mayView(by, (String) a.get("bot_path"))) continue;
            if (skipped++ < offset) continue;
            Map<String, Object> m = new LinkedHashMap<>(a);
            if (a.get("delivery") instanceof String d && !d.isBlank()) { try { m.put("delivery", JsonLite.parse(d)); } catch (RuntimeException ignored) { } }
            out.add(m);
            if (out.size() >= limit) break;
        }
        return Map.of("alerts", out, "open", cr.count("SELECT COUNT(*) AS n FROM alerts WHERE status = 'open'"));
    }

    /** Marks alerts as seen: an event alert is then done; a state alert stays until its state ends. */
    public Map<String, Object> acknowledge(User by, List<?> ids, String ip) {
        cr.require(by, "analytics.view", "acknowledge alerts");
        int n = 0;
        for (Object o : ids) {
            String id = String.valueOf(o);
            Map<String, Object> a = db.one("SELECT bot_path FROM alerts WHERE id = ? AND status = 'open'", id);
            if (a == null || (a.get("bot_path") != null && !cr.folders.mayView(by, (String) a.get("bot_path")))) continue;
            n += db.update("UPDATE alerts SET status = 'acknowledged', acknowledged = ?, acknowledged_by = ? WHERE id = ? AND status = 'open'", now(), by.username(), id);
            cr.audit.record(by.username(), "alert.acknowledge", "alert", id, null, "ok", ip);
        }
        return Map.of("acknowledged", n);
    }

    // ---------- checking ----------

    /** Called by the server clock (every 15 seconds on the leader): checks the rules once a minute. */
    void checkIfDue() {
        if (Duration.between(lastCheck, Instant.now()).toSeconds() < 60) return;
        lastCheck = Instant.now();
        check();
    }

    /** Checks every enabled rule now; one rule failing to check never stops the others. */
    void check() {
        for (Map<String, Object> rule : db.query("SELECT * FROM alert_rules WHERE enabled = ?", true)) {
            try { checkRule(rule); }
            catch (RuntimeException e) { cr.audit.record("system", "alert.check", "alert_rule", (String) rule.get("id"), Map.of("error", String.valueOf(e.getMessage())), "failed", null); }
        }
    }

    /** One condition found: the subject it is about, what to say, where it leads and the agent it concerns (for who may see it). */
    record Hit(String subject, String label, String message, String link, String bot) {}

    void checkRule(Map<String, Object> rule) {
        String kind = (String) rule.get("kind"), scope = (String) rule.get("scope");
        Instant now = now();
        Instant since = rule.get("last_checked") == null ? now : Instant.parse(String.valueOf(rule.get("last_checked")));
        double threshold = rule.get("threshold") instanceof Number n ? n.doubleValue() : 0;
        int window = rule.get("window_minutes") instanceof Number n ? n.intValue() : 0;
        List<Hit> hits = new ArrayList<>();
        switch (kind) {
            case "run_failed" -> {
                for (Map<String, Object> r : db.query("SELECT id, bot_path, error FROM runs WHERE status = 'failed' AND finished > ? AND finished <= ?" + (scope == null ? "" : " AND bot_path = ?")
                        + " ORDER BY finished LIMIT 1000", scope == null ? new Object[] {since, now} : new Object[] {since, now, scope}))
                    hits.add(new Hit((String) r.get("id"), name((String) r.get("bot_path")), name((String) r.get("bot_path")) + " failed" + errorText(r.get("error")),
                            "#/activity?run=" + r.get("id"), (String) r.get("bot_path")));
            }
            case "failure_rate" -> {
                Instant from = now.minus(Duration.ofMinutes(window));
                Map<String, long[]> per = new LinkedHashMap<>();   // agent (or * for all): failed, finished
                for (Map<String, Object> r : db.query("SELECT bot_path, status FROM runs WHERE status IN ('success', 'failed') AND finished > ?" + (scope == null ? "" : " AND bot_path = ?"),
                        scope == null ? new Object[] {from} : new Object[] {from, scope})) {
                    long[] c = per.computeIfAbsent(scope == null ? "*" : scope, k -> new long[2]);
                    if ("failed".equals(r.get("status"))) c[0]++;
                    c[1]++;
                }
                for (var e : per.entrySet()) {
                    long failed = e.getValue()[0], finished = e.getValue()[1];
                    if (finished < MIN_RUNS || 100.0 * failed / finished < threshold) continue;
                    String who = e.getKey().equals("*") ? "all agents" : name(e.getKey());
                    hits.add(new Hit(e.getKey(), who, Math.round(100.0 * failed / finished) + "% of the runs of " + who + " failed in the last " + minutes(window) + " (" + failed + " of " + finished + ")",
                            "#/activity?tab=completed&status=failed" + (e.getKey().equals("*") ? "" : "&bot=" + java.net.URLEncoder.encode(e.getKey(), java.nio.charset.StandardCharsets.UTF_8)),
                            e.getKey().equals("*") ? null : e.getKey()));
                }
            }
            case "run_long" -> {
                for (Map<String, Object> r : db.query("SELECT id, bot_path, started FROM runs WHERE status = 'running' AND started < ?" + (scope == null ? "" : " AND bot_path = ?"),
                        scope == null ? new Object[] {now.minus(Duration.ofMinutes(window))} : new Object[] {now.minus(Duration.ofMinutes(window)), scope}))
                    hits.add(new Hit((String) r.get("id"), name((String) r.get("bot_path")), "A run of " + name((String) r.get("bot_path")) + " has been running for "
                            + minutes((int) Duration.between(Instant.parse(String.valueOf(r.get("started"))), now).toMinutes()) + " (more than " + minutes(window) + ")",
                            "#/activity?run=" + r.get("id"), (String) r.get("bot_path")));
            }
            case "runner_offline" -> {
                for (Map<String, Object> d : db.query("SELECT id, name, nickname, last_seen FROM devices WHERE enabled = ? AND maintenance IS NULL" + (scope == null ? "" : " AND id = ?"),
                        scope == null ? new Object[] {true} : new Object[] {true, scope})) {
                    Instant seen = d.get("last_seen") == null ? null : Instant.parse(String.valueOf(d.get("last_seen")));
                    if (seen != null && seen.isAfter(now.minus(Duration.ofMinutes(window)))) continue;
                    String who = d.get("nickname") != null ? (String) d.get("nickname") : (String) d.get("name");
                    hits.add(new Hit((String) d.get("id"), who, "Runner " + who + (seen == null ? " has never reported" : " has not reported since " + seen), "#/devices", null));
                }
            }
            case "queue_overdue" -> {
                for (Map<String, Object> qu : db.query("SELECT id, name, sla_minutes FROM queues WHERE sla_minutes > 0" + (scope == null ? "" : " AND id = ?"), scope == null ? new Object[0] : new Object[] {scope})) {
                    long overdue = cr.count("SELECT COUNT(*) AS n FROM queue_items WHERE queue_id = ? AND status IN ('new', 'in_progress', 'retry') AND created < ?",
                            qu.get("id"), now.minus(Duration.ofMinutes(((Number) qu.get("sla_minutes")).intValue())));
                    if (overdue >= Math.max(1, threshold))
                        hits.add(new Hit((String) qu.get("id"), (String) qu.get("name"), overdue + " item" + (overdue == 1 ? " is" : "s are") + " overdue in the work queue " + qu.get("name")
                                + " (waiting more than " + minutes(((Number) qu.get("sla_minutes")).intValue()) + ")", "#/queues", null));
                }
            }
            case "ai_budget" -> {
                String month = YearMonth.now(ZoneOffset.UTC).toString();
                for (Map<String, Object> c : db.query("SELECT name, monthly_tokens FROM model_connections WHERE monthly_tokens > 0" + (scope == null ? "" : " AND name = ?"), scope == null ? new Object[0] : new Object[] {scope})) {
                    long used = cr.platform.tokensThisMonth((String) c.get("name")), budget = ((Number) c.get("monthly_tokens")).longValue();
                    long pct = Math.round(100.0 * used / budget);
                    if (pct >= threshold)
                        hits.add(new Hit(c.get("name") + "@" + month, (String) c.get("name"), "The model connection " + c.get("name") + " has used " + pct + "% of its monthly budget ("
                                + used + " of " + budget + " tokens)", "#/connections", null));
                }
            }
            default -> { }
        }
        raise(rule, hits, now);
        db.update("UPDATE alert_rules SET last_checked = ? WHERE id = ?", now, rule.get("id"));
    }

    /** Raises an alert for each new subject; for a state rule, clears the alerts whose state has ended. */
    private void raise(Map<String, Object> rule, List<Hit> hits, Instant now) {
        String ruleId = (String) rule.get("id"), kind = (String) rule.get("kind");
        boolean event = EVENTS.contains(kind);
        Set<String> current = new HashSet<>();
        int raised = 0, held = 0;
        for (Hit h : hits) {
            current.add(h.subject());
            if (!event && db.one("SELECT id FROM alerts WHERE rule_id = ? AND subject = ? AND status <> 'cleared'", ruleId, h.subject()) != null) continue;
            if (event && db.one("SELECT id FROM alerts WHERE rule_id = ? AND subject = ?", ruleId, h.subject()) != null) continue;
            if (raised >= BURST) { held++; continue; }
            insert(rule, h.subject(), h.label(), h.message(), h.link(), h.bot(), now);
            raised++;
        }
        if (held > 0) insert(rule, "burst@" + now, String.valueOf(rule.get("name")), held + (held == 1 ? " more alert" : " more alerts") + " of this rule held back: look at the agents' runs", "#/activity?tab=completed&status=failed", null, now);
        if (!event)
            for (Map<String, Object> a : db.query("SELECT id, subject FROM alerts WHERE rule_id = ? AND status <> 'cleared'", ruleId))
                if (!current.contains((String) a.get("subject"))) db.update("UPDATE alerts SET status = 'cleared', cleared = ? WHERE id = ?", now, a.get("id"));
    }

    private void insert(Map<String, Object> rule, String subject, String label, String message, String link, String bot, Instant now) {
        String id = id("alert");
        String text = message.length() > 1900 ? message.substring(0, 1900) : message;
        db.update("INSERT INTO alerts (id, rule_id, rule_name, kind, subject, subject_label, message, severity, status, raised, link, bot_path) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                id, rule.get("id"), rule.get("name"), rule.get("kind"), subject, label, text, rule.get("severity"), "open", now, link, bot);
        Map<String, Object> sent = deliver(rule, id, text, link, bot);
        db.update("UPDATE alerts SET delivery = ? WHERE id = ?", JsonLite.write(sent), id);
    }

    /** Sends an alert to the rule's inbox recipients, e-mail addresses and webhook; says what was sent and what was not. */
    private Map<String, Object> deliver(Map<String, Object> rule, String alertId, String message, String link, String bot) {
        List<String> inbox = new ArrayList<>(), mail = new ArrayList<>();
        for (String t : csv(rule.get("notify_to"))) if (t.startsWith("user:") || t.startsWith("role:")) inbox.add(t); else mail.add(t);
        String title = ("critical".equals(rule.get("severity")) ? "Critical alert: " : "Alert: ") + rule.get("name");
        Map<String, Object> sent = new LinkedHashMap<>();
        if (!inbox.isEmpty()) {
            db.update("INSERT INTO human_tasks (id, workflow_run_id, stage, kind, title, description, data, assignee, status, created) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    id("task"), null, null, "notification", title, message, JsonLite.write(Map.of("alert_id", alertId, "link", link == null ? "" : link)), String.join(",", inbox), "open", now());
            sent.put("inbox", inbox);
        }
        if (!mail.isEmpty()) {
            if (!cr.mailer.configured()) { sent.put("not_emailed", mail); sent.put("note", "E-mail is not set up (Administration > Settings > E-mail)"); }
            else {
                try { cr.mailer.send(mail, title, message + "\n\nOpen ONEOFFICEAUTOMATION One CommandCenter to see it (Operational Insights > Alerts)."); sent.put("emailed", mail); }
                catch (OoaException e) { sent.put("not_emailed", mail); sent.put("note", e.getMessage()); }
            }
        }
        if (rule.get("webhook_url") instanceof String url && !url.isBlank()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("alert_id", alertId); body.put("rule", rule.get("name")); body.put("kind", rule.get("kind")); body.put("severity", rule.get("severity"));
            body.put("message", message); body.put("agent", bot); body.put("raised", now().toString());
            post(url, alertId, body);
            sent.put("webhook", "sent in the background");
        }
        return sent;
    }

    /** Posts to the webhook in the background (3 attempts, like run callbacks); the outcome is audited, never raised. */
    private void post(String url, String alertId, Map<String, Object> body) {
        Thread t = new Thread(() -> {
            try { NetGuard.check(cr, url, "The webhook"); }
            catch (OoaException e) { cr.audit.record("system", "alert.webhook", "alert", alertId, Map.of("error", e.getMessage()), "failed", null); return; }
            java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            for (int attempt = 1; attempt <= 3; attempt++) {
                try {
                    var res = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).timeout(Duration.ofSeconds(20))
                            .header("Content-Type", "application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString(JsonLite.write(body))).build(),
                            java.net.http.HttpResponse.BodyHandlers.discarding());
                    if (res.statusCode() < 300) { cr.audit.record("system", "alert.webhook", "alert", alertId, Map.of("status", res.statusCode()), "ok", null); return; }
                } catch (Exception ignored) { /* retry */ }
                try { Thread.sleep(1000L * attempt); } catch (InterruptedException e) { return; }
            }
            cr.audit.record("system", "alert.webhook", "alert", alertId, Map.of("url", url), "failed", null);
        }, "ooa-alert-webhook");
        t.setDaemon(true);
        t.start();
    }

    // ---------- helpers ----------

    static String name(String path) {
        String n = path == null ? "" : path.substring(path.lastIndexOf('/') + 1);
        return n.replaceAll("\\.(flow|process)\\.json$", "");
    }

    static String minutes(int m) { return m < 120 ? m + " minutes" : m % 60 == 0 ? m / 60 + " hours" : m / 60 + " h " + m % 60 + " min"; }

    @SuppressWarnings("unchecked")
    static String errorText(Object error) {
        if (error == null) return "";
        try {
            Object e = JsonLite.parse(String.valueOf(error));
            if (e instanceof Map<?, ?> m) return ": " + (m.get("code") != null ? m.get("code") + " " : "") + (m.get("message") != null ? m.get("message") : "");
        } catch (RuntimeException ignored) { }
        return ": " + error;
    }

    private static List<String> csv(Object v) {
        List<String> out = new ArrayList<>();
        if (v != null) for (String s : String.valueOf(v).split(",")) if (!s.isBlank()) out.add(s.trim());
        return out;
    }
}
