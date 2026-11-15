package com.oneofficeautomation.controlroom.mail;

import com.oneofficeautomation.controlroom.ControlRoom;
import com.oneofficeautomation.controlroom.ControlRoom.User;
import com.oneofficeautomation.controlroom.MailSource;
import com.oneofficeautomation.controlroom.module.ModuleContext;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * One reader per mailbox and its mail rules (MA-2; the design plan's MA-Rules artboard). Each mail account with rules
 * is read once every interval_seconds, per folder its rules name, keeping one read position per folder (the first
 * look only marks where the mailbox is). Each new email is checked against the account's rules in order: the first
 * matching rule takes it (rule_mode first) or every matching rule does (every). A rule sends it to a target: a trigger
 * (its agent starts with trigger_email) or a module's target such as Document Automation's processes. An email no
 * rule takes is kept in the account's Not matched list (the newest 500).
 * <p>
 * mail_seen remembers emails already handled, per account and per target, so an email is never handled twice, even
 * when a forwarded copy arrives in another account that sends it to the same target. Entries older than 60 days go.
 */
public final class MailRouter {
    static final int UNMATCHED_KEPT = 500;
    private final ControlRoom cr;
    private final Map<String, ModuleContext.MailTarget> targets = new ConcurrentHashMap<>();
    private final Map<String, Long> nextDue = new ConcurrentHashMap<>();
    private final Set<String> busy = ConcurrentHashMap.newKeySet();
    private final ExecutorService pool = Executors.newFixedThreadPool(4, r -> { Thread t = new Thread(r, "mail-reader"); t.setDaemon(true); return t; });
    private volatile Instant cleaned = Instant.EPOCH;

    public MailRouter(ControlRoom cr) { this.cr = cr; }

    /** A module's target (Document Automation: "process"). */
    public void target(String kind, ModuleContext.MailTarget t) {
        if (kind.equals("trigger")) throw new IllegalArgumentException("trigger is the kernel's own mail target");
        if (targets.putIfAbsent(kind, t) != null) throw new IllegalStateException("Mail target " + kind + " is already registered");
    }

    public Set<String> targetKinds() {
        Set<String> out = new TreeSet<>(targets.keySet());
        out.add("trigger");
        return out;
    }

    // ------------------------------------------------------------------ rules

    /** An account's rules in order, with a sentence for each. */
    public Map<String, Object> rules(User by, String accountId) {
        Map<String, Object> a = cr.mailAccounts.row(accountId);
        if (!cr.mailAccounts.canUse(by, a)) throw new OoaException("CR.FORBIDDEN", "You may not use the mail account '" + a.get("name") + "'");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : cr.db.query("SELECT * FROM mail_rules WHERE account_id = ? ORDER BY position", a.get("id"))) out.add(view(r));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("account", a.get("id"));
        m.put("rule_mode", a.get("rule_mode"));
        m.put("rules", out);
        m.put("can_order", cr.mailAccounts.canChange(by, a));
        return m;
    }

    Map<String, Object> view(Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>(r);
        Map<String, Object> cond = conditions(r);
        m.put("conditions", cond);
        m.put("says", MailRules.describe(cond));
        m.put("target_name", targetName(String.valueOf(r.get("target_kind")), String.valueOf(r.get("target"))));
        return m;
    }

    String targetName(String kind, String target) {
        if (kind.equals("trigger")) {
            Map<String, Object> t = cr.db.one("SELECT name FROM triggers WHERE id = ?", target);
            return t == null ? target + " (deleted)" : String.valueOf(t.get("name"));
        }
        return target;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> conditions(Map<String, Object> r) {
        try { return (Map<String, Object>) JsonLite.parse(String.valueOf(r.get("conditions"))); } catch (RuntimeException e) { return Map.of("match", "all", "when", List.of()); }
    }

    /**
     * Makes (id null) or changes a rule. The person must be allowed to use the account and to send emails to the target
     * (a process they may add documents to, a trigger they may manage); the rule acts as them. A new rule goes at the end,
     * before a rule that takes every email.
     */
    public Map<String, Object> saveRule(User by, String id, String accountId, Map<String, Object> in, String ip) {
        Map<String, Object> old = id == null ? null : rule(id);
        if (old != null && !mayChange(by, old)) throw new OoaException("CR.FORBIDDEN", "Only " + old.get("owner") + ", who made this rule, or the mail account's owner can change it");
        Map<String, Object> a = cr.mailAccounts.row(accountId != null ? accountId : String.valueOf(old.get("account_id")));
        if (!cr.mailAccounts.canUse(by, a)) throw new OoaException("CR.FORBIDDEN", "You may not use the mail account '" + a.get("name") + "'",
                "Ask its owner (" + a.get("owner") + ") to add you to who may use it.");
        String name = text(in, "name", old, 200);
        if (name == null || name.isBlank()) throw MailRules.bad("A rule needs a name, such as Supplier invoices");
        String kind = text(in, "target_kind", old, 20), target = text(in, "target", old, 400);
        if (kind == null || !targetKinds().contains(kind)) throw MailRules.bad("A rule sends emails to one of " + targetKinds());
        if (target == null || target.isBlank()) throw MailRules.bad("Choose where the emails go");
        if (old == null || !kind.equals(old.get("target_kind")) || !target.equals(old.get("target"))) checkTarget(by, kind, target);
        Map<String, Object> cond = MailRules.check(in.containsKey("conditions") ? in.get("conditions") : old == null ? null : conditions(old));
        String folder = text(in, "folder", old, 200);
        String read = Objects.requireNonNullElse(text(in, "read_what", old, 20), "attachments");
        if (!Set.of("attachments", "email", "both").contains(read)) throw MailRules.bad("read_what is attachments, email or both");
        String after = Objects.requireNonNullElse(text(in, "after_read", old, 300), "none");
        if (!after.equals("none") && !after.equals("read") && !after.matches("move:[^\\r\\n]{1,200}")) throw MailRules.bad("after_read is none, read, or move:FOLDER");
        boolean enabled = in.containsKey("enabled") ? !Boolean.FALSE.equals(in.get("enabled")) && !"false".equals(String.valueOf(in.get("enabled"))) : old == null || Boolean.TRUE.equals(old.get("enabled"));
        Instant now = Instant.now();
        String rid = old == null ? ControlRoom.id("mrule") : String.valueOf(old.get("id"));
        if (old == null) {
            int position = nextPosition(String.valueOf(a.get("id")), cond);
            cr.db.update("INSERT INTO mail_rules (id, account_id, position, name, enabled, folder, conditions, target_kind, target, read_what, after_read, owner, matched, created, updated, updated_by) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", rid, a.get("id"), position, name.trim(), enabled, folder, JsonLite.write(cond), kind, target, read, after,
                    by.username(), 0, now, now, by.username());
        } else
            cr.db.update("UPDATE mail_rules SET account_id = ?, name = ?, enabled = ?, folder = ?, conditions = ?, target_kind = ?, target = ?, read_what = ?, after_read = ?, updated = ?, updated_by = ? WHERE id = ?",
                    a.get("id"), name.trim(), enabled, folder, JsonLite.write(cond), kind, target, read, after, now, by.username(), rid);
        nextDue.remove(String.valueOf(a.get("id")));
        cr.audit.record(by.username(), old == null ? "mail_rule.create" : "mail_rule.update", "mail_rule", rid,
                Map.of("account", a.get("id"), "target", kind + ":" + target, "says", MailRules.describe(cond)), "ok", ip);
        return view(rule(rid));
    }

    /** Before a last rule that takes every email (it stays last), else at the end. */
    private int nextPosition(String accountId, Map<String, Object> cond) {
        List<Map<String, Object>> rs = cr.db.query("SELECT id, position, conditions FROM mail_rules WHERE account_id = ? ORDER BY position", accountId);
        if (rs.isEmpty()) return 1;
        Map<String, Object> last = rs.get(rs.size() - 1);
        boolean lastTakesAll = MailRules.describe(conditions(last)).equals("Every email");
        boolean newTakesAll = MailRules.describe(cond).equals("Every email");
        if (lastTakesAll && !newTakesAll) {
            int pos = ((Number) last.get("position")).intValue();
            cr.db.update("UPDATE mail_rules SET position = ? WHERE id = ?", pos + 1, last.get("id"));
            return pos;
        }
        return ((Number) last.get("position")).intValue() + 1;
    }

    void checkTarget(User by, String kind, String target) {
        if (kind.equals("trigger")) {
            Map<String, Object> t = cr.db.one("SELECT id, kind FROM triggers WHERE id = ?", target);
            if (t == null) throw new OoaException("CR.NOT_FOUND", "Trigger " + target + " not found");
            cr.require(by, "triggers.manage", "send emails to triggers");
            return;
        }
        targets.get(kind).check(by, target);
    }

    public void deleteRule(User by, String id, String ip) {
        Map<String, Object> r = rule(id);
        if (!mayChange(by, r)) throw new OoaException("CR.FORBIDDEN", "Only " + r.get("owner") + ", who made this rule, or the mail account's owner can delete it");
        cr.db.update("DELETE FROM mail_rules WHERE id = ?", id);
        cr.audit.record(by.username(), "mail_rule.delete", "mail_rule", id, Map.of("account", r.get("account_id")), "ok", ip);
    }

    /** Removes the rules that send emails to a target (its trigger or process was deleted). */
    public void forgetTarget(String kind, String target) {
        cr.db.update("DELETE FROM mail_rules WHERE target_kind = ? AND target = ?", kind, target);
    }

    /** The order the account's rules are checked in (its owner or an administrator). */
    public void order(User by, String accountId, List<String> ids, String ip) {
        Map<String, Object> a = cr.mailAccounts.row(accountId);
        if (!cr.mailAccounts.canChange(by, a)) throw new OoaException("CR.FORBIDDEN", "Only " + a.get("owner") + ", who owns the mail account, or an administrator can change the order of its rules");
        Set<String> mine = new HashSet<>();
        for (Map<String, Object> r : cr.db.query("SELECT id FROM mail_rules WHERE account_id = ?", a.get("id"))) mine.add(String.valueOf(r.get("id")));
        if (!mine.equals(new HashSet<>(ids)) || ids.size() != mine.size()) throw MailRules.bad("Give every rule of the account once, in the new order");
        for (int i = 0; i < ids.size(); i++) cr.db.update("UPDATE mail_rules SET position = ? WHERE id = ?", i + 1, ids.get(i));
        cr.audit.record(by.username(), "mail_rule.order", "mail_account", String.valueOf(a.get("id")), Map.of("order", ids), "ok", ip);
    }

    boolean mayChange(User by, Map<String, Object> r) {
        if (by.username().equals(r.get("owner"))) return true;
        Map<String, Object> a = cr.mailAccounts.find(String.valueOf(r.get("account_id")));
        return a != null && cr.mailAccounts.canChange(by, a);
    }

    Map<String, Object> rule(String id) {
        Map<String, Object> r = cr.db.one("SELECT * FROM mail_rules WHERE id = ?", id);
        if (r == null) throw new OoaException("CR.NOT_FOUND", "Mail rule " + id + " not found", "Reload the page; it may have been deleted.");
        return r;
    }

    // ------------------------------------------------------------------ trying a rule

    /**
     * Tries conditions on the latest emails of a folder (at most 200), reading nothing into any process: which match,
     * and how many an earlier rule already takes (when the account's first matching rule wins).
     */
    public Map<String, Object> tryRule(User by, String accountId, Map<String, Object> in) throws IOException {
        Map<String, Object> a = cr.mailAccounts.row(accountId);
        if (!cr.mailAccounts.canUse(by, a)) throw new OoaException("CR.FORBIDDEN", "You may not use the mail account '" + a.get("name") + "'");
        Map<String, Object> cond = MailRules.check(in.get("conditions"));
        String folder = in.get("folder") instanceof String f && !f.isBlank() ? f : null;
        String except = in.get("rule") instanceof String r ? r : null;
        MailSource src = cr.mailAccounts.open(a, by.username(), folder);
        List<Map<String, Object>> recent = src.fetch(null, true).messages();
        if (recent.size() > 200) recent = recent.subList(recent.size() - 200, recent.size());
        List<Map<String, Object>> earlier = new ArrayList<>();
        // the rules before this one (a new rule goes after every rule that does not take every email)
        Map<String, Object> self = except == null ? null : cr.db.one("SELECT position FROM mail_rules WHERE id = ? AND account_id = ?", except, a.get("id"));
        int before = self == null ? Integer.MAX_VALUE : ((Number) self.get("position")).intValue();
        if ("first".equals(a.get("rule_mode")))
            for (Map<String, Object> r : cr.db.query("SELECT * FROM mail_rules WHERE account_id = ? AND enabled = TRUE ORDER BY position", a.get("id")))
                if (((Number) r.get("position")).intValue() < before && !r.get("id").equals(except) && !MailRules.describe(conditions(r)).equals("Every email")) earlier.add(r);
        List<Map<String, Object>> matched = new ArrayList<>(), others = new ArrayList<>();
        int taken = 0;
        for (Map<String, Object> msg : recent) {
            Supplier<List<MailSource.Attachment>> files = files(src, msg);
            if (MailRules.matches(cond, msg, files)) {
                boolean earlierTakes = earlier.stream().anyMatch(r -> MailRules.matches(conditions(r), msg, files));
                if (earlierTakes) taken++;
                else matched.add(brief(msg));
            } else if (others.size() < 20) others.add(brief(msg));
        }
        return Map.of("looked_at", recent.size(), "matched", matched, "taken_by_earlier", taken, "not_matched", others, "says", MailRules.describe(cond));
    }

    static Map<String, Object> brief(Map<String, Object> msg) {
        Map<String, Object> b = new LinkedHashMap<>();
        for (String k : List.of("subject", "from", "date", "has_attachments")) b.put(k, msg.get(k));
        return b;
    }

    // ------------------------------------------------------------------ reading

    /** Called by the server clock: reads the accounts with rules that are due, one at a time per account. */
    public void tick() {
        long now = System.currentTimeMillis();
        cleanUp();
        for (Map<String, Object> a : cr.db.query("SELECT id, interval_seconds FROM mail_accounts WHERE id IN (SELECT account_id FROM mail_rules WHERE enabled = TRUE)")) {
            String id = String.valueOf(a.get("id"));
            if (nextDue.getOrDefault(id, 0L) > now || busy.contains(id)) continue;
            nextDue.put(id, now + Math.max(60, ((Number) a.get("interval_seconds")).intValue()) * 1000L);
            busy.add(id);
            pool.submit(() -> { try { readNow(id); } catch (RuntimeException ignored) { } finally { busy.remove(id); } });
        }
    }

    /** Check now, for a person who may use the account. */
    public Map<String, Object> readNow(User by, String accountId) {
        Map<String, Object> a = cr.mailAccounts.row(accountId);
        if (!cr.mailAccounts.canUse(by, a)) throw new OoaException("CR.FORBIDDEN", "You may not use the mail account '" + a.get("name") + "'");
        return readNow(String.valueOf(a.get("id")));
    }

    /**
     * Reads the account's new emails in every folder its rules name and sends each to the rules' targets. Returns how
     * many emails were new, how many went somewhere, how many no rule took, and any problems.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> readNow(String accountId) {
        Map<String, Object> a = cr.mailAccounts.find(accountId);
        if (a == null) return Map.of("read", 0, "error", "not found");
        List<Map<String, Object>> rules = cr.db.query("SELECT * FROM mail_rules WHERE account_id = ? AND enabled = TRUE ORDER BY position", a.get("id"));
        Map<String, List<Map<String, Object>>> byFolder = new LinkedHashMap<>();
        for (Map<String, Object> r : rules) byFolder.computeIfAbsent(r.get("folder") == null || String.valueOf(r.get("folder")).isBlank() ? "" : String.valueOf(r.get("folder")), k -> new ArrayList<>()).add(r);
        Map<String, Object> cursors;
        try { cursors = a.get("cursors") == null ? new LinkedHashMap<>() : new LinkedHashMap<>((Map<String, Object>) JsonLite.parse(String.valueOf(a.get("cursors")))); }
        catch (RuntimeException e) { cursors = new LinkedHashMap<>(); }
        int read = 0, delivered = 0, unmatched = 0;
        List<String> problems = new ArrayList<>();
        boolean every = "every".equals(a.get("rule_mode"));
        String owner = String.valueOf(a.get("owner"));
        for (var folder : byFolder.entrySet()) {
            MailSource src;
            MailSource.Batch batch;
            try {
                src = cr.mailAccounts.open(a, owner, folder.getKey().isEmpty() ? null : folder.getKey());
                batch = src.fetch((String) cursors.get(folder.getKey()), false);
            } catch (Exception e) {
                cr.mailAccounts.record(a, MailAccounts.statusOf(e), MailAccounts.message(e));
                return Map.of("read", read, "delivered", delivered, "unmatched", unmatched, "error", MailAccounts.message(e));
            }
            for (Map<String, Object> msg : batch.messages()) {
                String key = key(msg);
                if (!firstTime("account:" + a.get("id"), key)) continue;
                read++;
                Supplier<List<MailSource.Attachment>> files = files(src, msg);
                boolean taken = false;
                for (Map<String, Object> r : folder.getValue()) {
                    if (!MailRules.matches(conditions(r), msg, files)) continue;
                    taken = true;
                    try {
                        if (firstTime("target:" + r.get("target_kind") + ":" + r.get("target"), key)) {
                            deliver(a, r, msg, files);
                            delivered++;
                        }
                        cr.db.update("UPDATE mail_rules SET matched = matched + 1, last_matched = ? WHERE id = ?", Instant.now(), r.get("id"));
                    } catch (Exception e) {
                        problems.add("Rule " + r.get("name") + ": " + MailAccounts.message(e));
                    }
                    if (!every) break;
                }
                if (!taken) { keepUnmatched(a, msg); unmatched++; }
            }
            cursors.put(folder.getKey(), batch.cursor());
        }
        String problem = problems.isEmpty() ? null : cut(String.join("; ", problems));
        cr.db.update("UPDATE mail_accounts SET cursors = ?, status = ?, last_check = ?, last_error = ? WHERE id = ?", JsonLite.write(cursors), "working", Instant.now(), problem, a.get("id"));
        if (read > 0) cr.db.update("UPDATE mail_accounts SET last_mail = ? WHERE id = ?", Instant.now(), a.get("id"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("read", read);
        out.put("delivered", delivered);
        out.put("unmatched", unmatched);
        out.put("problems", problems);
        return out;
    }

    /** Sends one email to a rule's target, as the rule's owner (who must still be allowed to use the account). */
    void deliver(Map<String, Object> a, Map<String, Object> r, Map<String, Object> msg, Supplier<List<MailSource.Attachment>> files) throws IOException {
        String owner = String.valueOf(r.get("owner"));
        if (!cr.mailAccounts.canUse(owner, a))
            throw new OoaException("CR.FORBIDDEN", "'" + owner + "', who made the rule, may no longer use the mail account", "Ask the account's owner to add them again, or make the rule as someone who may.");
        String kind = String.valueOf(r.get("target_kind")), target = String.valueOf(r.get("target"));
        Map<String, Object> email = new LinkedHashMap<>(msg);
        email.put("mail_account", a.get("name"));
        email.put("address", a.get("address"));
        email.put("rule", r.get("name"));
        if (kind.equals("trigger")) {
            Map<String, Object> t = cr.db.one("SELECT * FROM triggers WHERE id = ?", target);
            if (t == null) throw new OoaException("CR.NOT_FOUND", "Trigger " + target + " not found");
            if (!Boolean.TRUE.equals(t.get("enabled"))) return;
            Map<String, Object> ev = new LinkedHashMap<>(email);
            ev.put("kind", "mail");
            ev.put("trigger_id", target);
            ev.put("trigger_name", t.get("name"));
            ev.put("time", Instant.now().toString());
            cr.fireTrigger(t, Map.of("trigger_email", email, "trigger_event", ev), "mail-rule:" + r.get("id"), null);
            return;
        }
        ModuleContext.MailTarget t = targets.get(kind);
        if (t == null) throw new OoaException("CR.NOT_FOUND", "Nothing receives emails for " + kind + " here", "Install the module that provides it, or change the rule.");
        t.deliver(new ModuleContext.MailDelivery(String.valueOf(a.get("id")), String.valueOf(a.get("name")), String.valueOf(r.get("id")), String.valueOf(r.get("name")),
                owner, target, email, files.get(), String.valueOf(r.get("read_what"))));
    }

    static Supplier<List<MailSource.Attachment>> files(MailSource src, Map<String, Object> msg) {
        return () -> {
            if (!MailRules.hasAttachments(msg)) return List.of();
            try { return src.attachments(msg); } catch (IOException e) { throw new UncheckedIOException(e); }
        };
    }

    /** An email's lasting identity: its Message-ID, else the mailbox's own id. */
    static String key(Map<String, Object> msg) {
        Object mid = msg.get("message_id");
        String k = mid != null && !String.valueOf(mid).isBlank() ? "mid:" + String.valueOf(mid).trim().replaceAll("^<|>$", "").toLowerCase(Locale.ROOT) : "id:" + msg.get("id");
        return k.length() > 300 ? k.substring(0, 300) : k;
    }

    /** True the first time this email is seen in this scope (an account, or a target). */
    boolean firstTime(String scope, String key) {
        if (cr.db.one("SELECT scope FROM mail_seen WHERE scope = ? AND message_key = ?", scope, key) != null) return false;
        try { cr.db.update("INSERT INTO mail_seen (scope, message_key, seen) VALUES (?,?,?)", scope, key, Instant.now()); return true; }
        catch (RuntimeException e) { return false; }   // another reader just took it
    }

    void cleanUp() {
        Instant now = Instant.now();
        if (now.isBefore(cleaned.plus(Duration.ofHours(1)))) return;
        cleaned = now;
        cr.db.update("DELETE FROM mail_seen WHERE seen < ?", now.minus(Duration.ofDays(60)));
    }

    // ------------------------------------------------------------------ not matched

    void keepUnmatched(Map<String, Object> a, Map<String, Object> msg) {
        cr.db.update("INSERT INTO mail_unmatched (id, account_id, received, sender, subject, message, status) VALUES (?,?,?,?,?,?,?)", ControlRoom.id("mun"), a.get("id"), Instant.now(),
                cut(String.valueOf(msg.getOrDefault("from", "")), 400), cut(String.valueOf(msg.getOrDefault("subject", "")), 500), JsonLite.write(msg), "waiting");
        List<Map<String, Object>> old = cr.db.query("SELECT id FROM mail_unmatched WHERE account_id = ? ORDER BY received DESC", a.get("id"));
        for (int i = UNMATCHED_KEPT; i < old.size(); i++) cr.db.update("DELETE FROM mail_unmatched WHERE id = ?", old.get(i).get("id"));
    }

    /** The emails no rule took, newest first. */
    public List<Map<String, Object>> unmatched(User by, String accountId) {
        Map<String, Object> a = cr.mailAccounts.row(accountId);
        if (!cr.mailAccounts.canUse(by, a)) throw new OoaException("CR.FORBIDDEN", "You may not use the mail account '" + a.get("name") + "'");
        return cr.db.query("SELECT id, received, sender, subject, status FROM mail_unmatched WHERE account_id = ? AND status = 'waiting' ORDER BY received DESC", a.get("id"));
    }

    /** Sends a not-matched email to a rule's target now, or ignores it (rule null). */
    @SuppressWarnings("unchecked")
    public void handleUnmatched(User by, String unmatchedId, String ruleId, String ip) throws IOException {
        Map<String, Object> u = cr.db.one("SELECT * FROM mail_unmatched WHERE id = ?", unmatchedId);
        if (u == null) throw new OoaException("CR.NOT_FOUND", "That email is no longer in Not matched");
        Map<String, Object> a = cr.mailAccounts.row(String.valueOf(u.get("account_id")));
        if (!cr.mailAccounts.canUse(by, a)) throw new OoaException("CR.FORBIDDEN", "You may not use the mail account '" + a.get("name") + "'");
        if (ruleId != null) {
            Map<String, Object> r = rule(ruleId);
            if (!r.get("account_id").equals(a.get("id"))) throw MailRules.bad("Choose one of this account's rules");
            Map<String, Object> msg = (Map<String, Object>) JsonLite.parse(String.valueOf(u.get("message")));
            String folder = r.get("folder") == null || String.valueOf(r.get("folder")).isBlank() ? null : String.valueOf(r.get("folder"));
            MailSource src = cr.mailAccounts.open(a, by.username(), folder);
            deliver(a, r, msg, files(src, msg));
            firstTime("target:" + r.get("target_kind") + ":" + r.get("target"), key(msg));
        }
        cr.db.update("UPDATE mail_unmatched SET status = ? WHERE id = ?", ruleId == null ? "ignored" : "sent", unmatchedId);
        cr.audit.record(by.username(), ruleId == null ? "mail.unmatched.ignore" : "mail.unmatched.send", "mail_account", String.valueOf(a.get("id")),
                ruleId == null ? Map.of("email", unmatchedId) : Map.of("email", unmatchedId, "rule", ruleId), "ok", ip);
    }

    private static String text(Map<String, Object> in, String k, Map<String, Object> old, int max) {
        Object v = in.containsKey(k) ? in.get(k) : old == null ? null : old.get(k);
        if (v == null || String.valueOf(v).isBlank()) return null;
        String s = String.valueOf(v).trim();
        if (s.length() > max) throw MailRules.bad(k + " has at most " + max + " characters");
        return s;
    }

    private static String cut(String s) { return cut(s, 1000); }
    private static String cut(String s, int max) { return s.length() > max ? s.substring(0, max) : s; }
}
