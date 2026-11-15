package com.oneofficeautomation.controlroom.mail;

import com.oneofficeautomation.controlroom.ControlRoom;
import com.oneofficeautomation.controlroom.ControlRoom.User;
import com.oneofficeautomation.controlroom.GraphMailSource;
import com.oneofficeautomation.controlroom.ImapMailSource;
import com.oneofficeautomation.controlroom.MailSource;
import com.oneofficeautomation.controlroom.NetGuard;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;

import java.io.IOException;
import java.time.Instant;
import java.util.*;

/**
 * Mail accounts (MA-1; the user's OK "Yes, build it", 15 Nov 2026): a mailbox set up once and chosen by name by
 * Document Automation's processes, triggers and agents, instead of each of them asking for a server, a password or a
 * sign-in. Laid out as the design plan's MA-List and MA-Vendor artboards.
 * <p>
 * An account has a vendor from the catalogue ({@link MailProviders}), the address, and how it signs in: Sign in with
 * Microsoft (a sign-in the owner made, kept in ms_signins), a Microsoft app (tenant id, client id and the client
 * secret's vault reference), or a password or app password (its vault reference). Secrets stay in the vault: the
 * account keeps references only, and the owner, who must be allowed to use them, lends the account to the people,
 * teams and roles in allowed. Each use is audited as the account, for whom.
 * <p>
 * Table mail_accounts (schema 48); its rules (mail_rules) and the emails already handled (mail_seen) belong to the
 * reader (MA-2). Viewing the list needs globals.view and making an account globals.manage, as Manage > Connections.
 */
public final class MailAccounts {
    public static final Set<String> SIGN_INS = Set.of("microsoft", "microsoft_app", "app_password", "password");
    private static final int MIN_INTERVAL = 60;

    private final ControlRoom cr;
    /** The vendor catalogue (tests give one with a fake DNS). */
    public MailProviders providers = MailProviders.standard();

    public MailAccounts(ControlRoom cr) { this.cr = cr; }

    // ------------------------------------------------------------------ reading

    /** Every account (no secrets: vault references and sign-in ids only), with whether the person may use or change it. */
    public List<Map<String, Object>> list(User by) {
        cr.require(by, "globals.view", "see mail accounts");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> a : cr.db.query("SELECT * FROM mail_accounts ORDER BY name")) out.add(view(by, a));
        return out;
    }

    public Map<String, Object> get(User by, String id) {
        cr.require(by, "globals.view", "see mail accounts");
        return view(by, row(id));
    }

    /** An account by id or name, or null. */
    public Map<String, Object> find(String idOrName) {
        if (idOrName == null || idOrName.isBlank()) return null;
        Map<String, Object> a = cr.db.one("SELECT * FROM mail_accounts WHERE id = ?", idOrName.trim());
        return a != null ? a : cr.db.one("SELECT * FROM mail_accounts WHERE name = ?", idOrName.trim());
    }

    Map<String, Object> row(String id) {
        Map<String, Object> a = find(id);
        if (a == null) throw new OoaException("CR.NOT_FOUND", "Mail account '" + id + "' not found", "Reload the page and choose it again; it may have been renamed or deleted.");
        return a;
    }

    private Map<String, Object> view(User by, Map<String, Object> a) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String k : List.of("id", "name", "provider", "address", "secret_ref", "ms_signin", "owner", "rule_mode", "interval_seconds", "status", "last_check", "last_error", "last_mail", "created", "updated", "updated_by"))
            m.put(k, a.get(k));
        m.put("settings", settings(a));
        m.put("allowed", ControlRoom.csv(a.get("allowed")));
        Map<String, Object> p = providers.get(String.valueOf(a.get("provider")));
        m.put("provider_name", p == null ? a.get("provider") : p.get("name"));
        m.put("sign_in", signIn(a));
        if (a.get("ms_signin") instanceof String sid) {
            Map<String, Object> s = cr.microsoft.row(sid);
            m.put("signed_in_as", s == null ? null : s.get("account"));
        }
        m.put("can_use", canUse(by, a));
        m.put("can_change", canChange(by, a));
        m.put("used_by", usedBy(String.valueOf(a.get("id"))));
        return m;
    }

    /** Where an account is used: its rules' targets (processes, triggers, agents). */
    List<Map<String, Object>> usedBy(String id) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : cr.db.query("SELECT target_kind, target FROM mail_rules WHERE account_id = ? ORDER BY position", id))
            out.add(Map.of("kind", r.get("target_kind"), "target", r.get("target")));
        return out;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> settings(Map<String, Object> a) {
        Object s = a.get("settings");
        try { return s == null ? new LinkedHashMap<>() : new LinkedHashMap<>((Map<String, Object>) JsonLite.parse(String.valueOf(s))); }
        catch (IllegalArgumentException e) { return new LinkedHashMap<>(); }
    }

    static String signIn(Map<String, Object> a) {
        Object s = settings(a).get("sign_in");
        return s == null ? (a.get("ms_signin") != null ? "microsoft" : "password") : String.valueOf(s);
    }

    // ------------------------------------------------------------------ who may

    /** The owner, an administrator, or someone the owner lent the account to (a person, team or role). */
    public boolean canUse(User u, Map<String, Object> a) {
        if (u == null || a == null) return false;
        if (canChange(u, a)) return true;
        List<String> mine = cr.teams.principalsOf(u.username(), u.role());
        return ControlRoom.csv(a.get("allowed")).stream().anyMatch(mine::contains);
    }

    /** The owner, or an administrator (users.manage). */
    public boolean canChange(User u, Map<String, Object> a) {
        return u != null && a != null && (u.username().equals(a.get("owner")) || cr.can(u, "users.manage"));
    }

    /** For processes, triggers and agents choosing an account: it must exist and the person may use it. Returns it. */
    public Map<String, Object> requireUse(User u, String idOrName) {
        Map<String, Object> a = find(idOrName);
        if (a == null) throw new OoaException("CR.NOT_FOUND", "Mail account '" + idOrName + "' not found", "Choose a mail account from the list (Manage > Connections).");
        if (!canUse(u, a)) throw new OoaException("CR.FORBIDDEN", "You may not use the mail account '" + a.get("name") + "'",
                "Ask its owner (" + a.get("owner") + ") to add you, your team or your role to who may use it.");
        return a;
    }

    /** May this user (by name) use the account now? For runs and rules that act as a user later. */
    public boolean canUse(String username, Map<String, Object> a) {
        Map<String, Object> u = cr.db.one("SELECT id, role FROM users WHERE username = ? AND enabled = TRUE", username);
        return u != null && canUse(new User(String.valueOf(u.get("id")), username, String.valueOf(u.get("role")), null, false, false, false), a);
    }

    // ------------------------------------------------------------------ changing

    /**
     * Makes (id null) or changes an account. The person saving must be allowed to use a vault reference they set (a
     * consumer of its vault folder) and must have made a Microsoft sign-in they set; a reference or sign-in that does not
     * change is not checked again, so an administrator can still rename an account or change who may use it.
     */
    public Map<String, Object> save(User by, String id, Map<String, Object> in, String ip) {
        Map<String, Object> old = id == null ? null : row(id);
        if (old == null) cr.require(by, "globals.manage", "make mail accounts");
        else if (!canChange(by, old)) throw new OoaException("CR.FORBIDDEN", "Only " + old.get("owner") + ", who owns this mail account, or an administrator can change it");
        String name = str(in, "name", old == null ? null : String.valueOf(old.get("name")));
        if (name == null || name.isBlank() || name.length() > 120) throw bad("A mail account needs a name of 1 to 120 characters");
        Map<String, Object> clash = cr.db.one("SELECT id FROM mail_accounts WHERE name = ?", name.trim());
        if (clash != null && (old == null || !clash.get("id").equals(old.get("id")))) throw bad("There is already a mail account called " + name.trim());
        String providerId = str(in, "provider", old == null ? null : String.valueOf(old.get("provider")));
        Map<String, Object> p = providers.get(providerId);
        if (p == null) throw bad("Choose the vendor of the mailbox");
        String address = str(in, "address", old == null ? null : String.valueOf(old.get("address")));
        if (address == null || !address.trim().matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) throw bad("Give the mailbox's email address, such as invoices@example.com");
        address = address.trim();
        List<String> methods = MailProviders.strings(p.get("signIn"));
        Map<String, Object> was = old == null ? Map.of() : settings(old);
        String signIn = str(in, "sign_in", old == null ? methods.get(0) : signIn(old));
        if (!methods.contains(signIn)) throw bad(p.get("name") + " signs in with " + String.join(" or ", methods) + ", not " + signIn);

        Map<String, Object> s = new LinkedHashMap<>();
        s.put("sign_in", signIn);
        String secretRef = null, msSignIn = null;
        switch (signIn) {
            case "microsoft" -> {
                msSignIn = str(in, "ms_signin", old == null ? null : (String) old.get("ms_signin"));
                if (msSignIn == null) throw bad("Sign in with Microsoft first");
                if (old == null || !msSignIn.equals(old.get("ms_signin"))) cr.microsoft.accountFor(by, msSignIn);
                String mailbox = str(in, "mailbox", (String) was.get("mailbox"));
                if (mailbox != null && !mailbox.isBlank()) {
                    if (!mailbox.trim().matches("[^@\\s]+@[^@\\s]+")) throw bad("A shared mailbox is an email address, such as accounts@example.com");
                    s.put("mailbox", mailbox.trim());
                }
            }
            case "microsoft_app" -> {
                s.put("tenant_id", need(in, "tenant_id", (String) was.get("tenant_id"), "Your own Microsoft app needs the Microsoft Entra tenant id"));
                s.put("client_id", need(in, "client_id", (String) was.get("client_id"), "Your own Microsoft app needs its application (client) id"));
                secretRef = vaultRef(by, str(in, "secret_ref", old == null ? null : (String) old.get("secret_ref")), old, "the client secret");
                s.put("mailbox", address);
            }
            default -> {   // app_password, password: IMAP
                String region = str(in, "region", (String) was.get("region"));
                String host = str(in, "host", (String) was.get("host"));
                if (host == null || host.isBlank()) host = providers.imapHost(p, region);
                if (host == null || host.isBlank()) throw bad("Give the mail server's address, such as mail.example.com");
                if (!host.trim().matches("[A-Za-z0-9.-]{1,253}")) throw bad("'" + host + "' is not a server name");
                int port = number(in.get("port") != null ? in.get("port") : was.get("port"), p.get("imap") instanceof Map<?, ?> imap && imap.get("port") instanceof Number n ? n.intValue() : 993);
                if (port < 1 || port > 65535) throw bad("The port is a number from 1 to 65535");
                Object sslIn = in.containsKey("ssl") ? in.get("ssl") : was.get("ssl");
                boolean ssl = sslIn == null || Boolean.parseBoolean(String.valueOf(sslIn));
                s.put("host", host.trim());
                s.put("port", port);
                s.put("ssl", ssl);
                if (region != null && !region.isBlank()) s.put("region", region);
                String user = str(in, "user", (String) was.get("user"));
                s.put("user", user == null || user.isBlank() ? address : user.trim());
                secretRef = vaultRef(by, str(in, "secret_ref", old == null ? null : (String) old.get("secret_ref")), old,
                        signIn.equals("app_password") ? "the app password" : "the password");
            }
        }
        List<String> allowed = new ArrayList<>();
        for (String principal : ControlRoom.csv(in.containsKey("allowed") ? in.get("allowed") : old == null ? null : old.get("allowed"))) {
            cr.teams.checkPrincipal(principal);
            if (!allowed.contains(principal)) allowed.add(principal);
        }
        String mode = str(in, "rule_mode", old == null ? "first" : String.valueOf(old.get("rule_mode")));
        if (!mode.equals("first") && !mode.equals("every")) throw bad("rule_mode is first (the first matching rule takes an email) or every");
        int interval = number(in.get("interval_seconds") != null ? in.get("interval_seconds") : old == null ? null : old.get("interval_seconds"), MIN_INTERVAL);
        if (interval < MIN_INTERVAL || interval > 86400) throw bad("Check a mailbox every " + MIN_INTERVAL + " seconds to 24 hours");

        Instant now = Instant.now();
        boolean reach = old == null || !Objects.equals(old.get("secret_ref"), secretRef) || !Objects.equals(old.get("ms_signin"), msSignIn)
                || !Objects.equals(was.get("host"), s.get("host")) || !Objects.equals(was.get("mailbox"), s.get("mailbox"));
        String status = reach ? "new" : String.valueOf(old.get("status"));
        String aid = old == null ? ControlRoom.id("mail") : String.valueOf(old.get("id"));
        if (old == null)
            cr.db.update("INSERT INTO mail_accounts (id, name, provider, address, settings, secret_ref, ms_signin, owner, allowed, rule_mode, interval_seconds, status, created, created_by, updated, updated_by) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", aid, name.trim(), providerId, address, JsonLite.write(s), secretRef, msSignIn, by.username(),
                    String.join(",", allowed), mode, interval, status, now, by.username(), now, by.username());
        else
            cr.db.update("UPDATE mail_accounts SET name = ?, provider = ?, address = ?, settings = ?, secret_ref = ?, ms_signin = ?, allowed = ?, rule_mode = ?, interval_seconds = ?, status = ?, "
                    + "last_error = CASE WHEN ? THEN NULL ELSE last_error END, updated = ?, updated_by = ? WHERE id = ?", name.trim(), providerId, address, JsonLite.write(s), secretRef, msSignIn,
                    String.join(",", allowed), mode, interval, status, reach, now, by.username(), aid);
        cr.audit.record(by.username(), old == null ? "mail_account.create" : "mail_account.update", "mail_account", aid,
                Map.of("name", name.trim(), "provider", providerId, "address", address, "sign_in", signIn), "ok", ip);
        return view(by, row(aid));
    }

    /** Deletes an account that no rule uses any more (its owner or an administrator). */
    public void delete(User by, String id, String ip) {
        Map<String, Object> a = row(id);
        if (!canChange(by, a)) throw new OoaException("CR.FORBIDDEN", "Only " + a.get("owner") + ", who owns this mail account, or an administrator can delete it");
        int rules = cr.db.query("SELECT id FROM mail_rules WHERE account_id = ?", a.get("id")).size();
        if (rules > 0) throw new OoaException("CR.CONFLICT", "The mail account '" + a.get("name") + "' is used by " + rules + " rule" + (rules == 1 ? "" : "s"),
                "Remove it from those processes, triggers and agents first (its page lists them).");
        cr.db.update("DELETE FROM mail_seen WHERE scope = ?", "account:" + a.get("id"));
        cr.db.update("DELETE FROM mail_accounts WHERE id = ?", a.get("id"));
        cr.audit.record(by.username(), "mail_account.delete", "mail_account", String.valueOf(a.get("id")), Map.of("name", a.get("name")), "ok", ip);
    }

    // ------------------------------------------------------------------ using

    /** Reaches the mailbox now (its Inbox) and records the status: working, sign_in_expired, wrong_password or failed. */
    public Map<String, Object> test(User by, String id) {
        Map<String, Object> a = row(id);
        if (!canUse(by, a)) throw new OoaException("CR.FORBIDDEN", "You may not use the mail account '" + a.get("name") + "'");
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            MailSource.Batch b = open(a, by.username(), "inbox").fetch(null, false);
            record(a, "working", null);
            out.put("ok", true);
            out.put("status", "working");
            out.put("message", "Reached the Inbox of " + a.get("address") + ".");
            out.put("cursor", b.cursor());
        } catch (Exception e) {
            String status = statusOf(e);
            record(a, status, message(e));
            out.put("ok", false);
            out.put("status", status);
            out.put("message", message(e));
        }
        return out;
    }

    /** Records how the last look at the mailbox went. */
    public void record(Map<String, Object> a, String status, String error) {
        cr.db.update("UPDATE mail_accounts SET status = ?, last_check = ?, last_error = ? WHERE id = ?", status, Instant.now(), error == null ? null : cut(error, 1000), a.get("id"));
    }

    static String statusOf(Exception e) {
        String m = message(e).toLowerCase(Locale.ROOT);
        if (e instanceof OoaException o && o.code().equals("CR.MS_SIGNIN")) return "sign_in_expired";
        if (m.contains("authenticationfailed") || m.contains("login failed") || m.contains("invalid credentials") || m.contains("authentication failed")
                || m.contains("username and password") || m.contains("logon failure")) return "wrong_password";
        return "failed";
    }

    static String message(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    /**
     * Opens a folder of the mailbox for someone who may use the account (a process's owner, a trigger's owner, a run's
     * user). The secret is read from the vault as the account's owner, who lends the account; each read is audited.
     */
    public MailSource open(Map<String, Object> a, String forUser, String folder) throws IOException {
        if (!canUse(forUser, a)) throw new OoaException("CR.FORBIDDEN", "'" + forUser + "' may not use the mail account '" + a.get("name") + "'",
                "Ask its owner (" + a.get("owner") + ") to add them to who may use it.");
        Map<String, Object> s = settings(a);
        String f = folder == null || folder.isBlank() ? null : folder;
        switch (signIn(a)) {
            case "microsoft" -> {
                String sid = String.valueOf(a.get("ms_signin"));
                Map<String, Object> row = cr.microsoft.row(sid);
                if (row == null) throw new OoaException("CR.MS_SIGNIN", "The Microsoft sign-in of mail account '" + a.get("name") + "' is not known",
                        "Open the mail account (Manage > Connections) and choose Sign in again.");
                if (cr.db.one("SELECT id FROM users WHERE username = ? AND enabled = TRUE", row.get("started_by")) == null)
                    throw new OoaException("CR.MS_SIGNIN", "'" + row.get("started_by") + "', who signed in with Microsoft for mail account '" + a.get("name") + "', is not an active user",
                            "Open the mail account and sign in again as someone who is.");
                return new GraphMailSource(graphUrl(), (String) s.get("mailbox"), f == null ? "inbox" : f, cr.microsoft.accessToken(sid));
            }
            case "microsoft_app" -> {
                String secret = secret(a, forUser);
                return new GraphMailSource(graphUrl(), (String) s.get("mailbox"), f == null ? "inbox" : f,
                        GraphMailSource.clientCredentialsToken(null, String.valueOf(s.get("tenant_id")), String.valueOf(s.get("client_id")), secret));
            }
            default -> {
                String host = String.valueOf(s.get("host"));
                NetGuard.check(cr, host, "The mail server");
                return new ImapMailSource(host, number(s.get("port"), 993), !Boolean.FALSE.equals(s.get("ssl")), String.valueOf(s.get("user")), secret(a, forUser),
                        f == null ? "INBOX" : f, 30_000);
            }
        }
    }

    /**
     * For a running agent (credential reference mail:ID): the account's password for the run's user, who must be
     * allowed to use the account. Only accounts read over IMAP can be used by the Email toolkit's steps.
     */
    public String forRun(String runUser, String accountId) {
        Map<String, Object> a = find(accountId);
        if (a == null) throw new OoaException("CR.NOT_FOUND", "Mail account '" + accountId + "' not found", "Choose another mail account on the step.");
        if (!canUse(runUser, a)) throw new OoaException("VAULT.DENIED", "'" + runUser + "' may not use the mail account '" + a.get("name") + "'",
                "Ask its owner (" + a.get("owner") + ") to add the run's user, team or role to who may use it.");
        String how = signIn(a);
        if (!how.equals("app_password") && !how.equals("password"))
            throw new OoaException("CR.BAD_INPUT", "The mail account '" + a.get("name") + "' signs in with Microsoft: the Email toolkit's steps read mail over IMAP",
                    "Use the Microsoft 365 toolkit's steps for it, or a mail account with an app password.");
        return secret(a, runUser);
    }

    /** Mail accounts as the Email toolkit's saved connections for a run's job: their server and user, the password as mail:ID. */
    public Map<String, Object> forJob() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map<String, Object> a : cr.db.query("SELECT * FROM mail_accounts ORDER BY name")) {
            String how = signIn(a);
            if (!how.equals("app_password") && !how.equals("password")) continue;
            Map<String, Object> s = settings(a);
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("imap_host", s.get("host"));
            values.put("port", s.get("port"));
            values.put("user", s.get("user"));
            values.put("security", Boolean.FALSE.equals(s.get("ssl")) ? "none" : "ssl");
            values.put("protocol", "imap");
            out.put(String.valueOf(a.get("name")), Map.of("package", "ooa.email", "values", values, "credentials", Map.of("password", "mail:" + a.get("id"))));
        }
        return out;
    }

    /** The account's vault secret, read as its owner (who must still be a consumer of the vault folder). */
    String secret(Map<String, Object> a, String forUser) {
        String ref = String.valueOf(a.get("secret_ref"));
        String actor = "mail-account:" + a.get("id") + " for " + forUser;
        String[] p = ref.split("/");
        Map<String, Object> locker = p.length == 3 ? cr.db.one("SELECT * FROM lockers WHERE name = ?", p[0]) : null;
        Map<String, Object> owner = cr.db.one("SELECT role FROM users WHERE username = ? AND enabled = TRUE", a.get("owner"));
        if (locker == null || owner == null || !cr.consumes(String.valueOf(a.get("owner")), String.valueOf(owner.get("role")), locker)) {
            cr.audit.record(actor, "credential.read", "credential", ref, Map.of("mail_account", a.get("id")), "denied", null);
            throw new OoaException("VAULT.DENIED", "'" + a.get("owner") + "', who owns mail account '" + a.get("name") + "', may not use vault folder '" + (p.length > 0 ? p[0] : ref) + "'",
                    "Ask the vault folder's owner to add them as a consumer, or give the mail account another owner.");
        }
        Map<String, Object> row = cr.db.one("SELECT ciphertext FROM credentials WHERE locker = ? AND name = ? AND attribute = ?", p[0], p[1], p[2]);
        if (row == null) {
            cr.audit.record(actor, "credential.read", "credential", ref, Map.of("mail_account", a.get("id")), "not_found", null);
            throw new OoaException("VAULT.NOT_FOUND", "Credential '" + ref + "' not found", "Check the credential's name and folder in Manage > Credentials.");
        }
        cr.audit.record(actor, "credential.read", "credential", ref, Map.of("mail_account", a.get("id")), "ok", null);
        return cr.vault.decrypt(String.valueOf(row.get("ciphertext")), ref);
    }

    /** A vault reference the person saving may use (checked when it is new or changed). */
    private String vaultRef(User by, String ref, Map<String, Object> old, String what) {
        if (ref == null || ref.isBlank()) throw bad("Choose " + what + " from the vault");
        ref = ref.trim();
        String[] p = ref.split("/");
        if (p.length != 3 || Arrays.stream(p).anyMatch(String::isBlank)) throw bad(what + " is a vault reference: vault folder/credential/attribute");
        if (old != null && ref.equals(old.get("secret_ref"))) return ref;
        Map<String, Object> locker = cr.db.one("SELECT * FROM lockers WHERE name = ?", p[0]);
        if (locker == null || !cr.consumes(by.username(), by.role(), locker))
            throw new OoaException("VAULT.DENIED", "You may not use vault folder '" + p[0] + "'", "Ask the vault folder's owner to add you as a consumer, or choose another saved value.");
        return ref;
    }

    static String graphUrl() {
        String g = System.getProperty("ooa.microsoft.graph.url", System.getenv("OOA_MICROSOFT_GRAPH_URL"));
        return g == null || g.isBlank() ? null : g;
    }

    private static String str(Map<String, Object> in, String k, String fallback) {
        if (!in.containsKey(k)) return fallback;
        Object v = in.get(k);
        return v == null || String.valueOf(v).isBlank() ? null : String.valueOf(v).trim();
    }

    private static String need(Map<String, Object> in, String k, String fallback, String message) {
        String v = str(in, k, fallback);
        if (v == null) throw bad(message);
        return v;
    }

    static int number(Object v, int fallback) {
        if (v instanceof Number n) return n.intValue();
        try { return v == null || String.valueOf(v).isBlank() ? fallback : (int) Double.parseDouble(String.valueOf(v)); }
        catch (NumberFormatException e) { throw bad("'" + v + "' is not a number"); }
    }

    private static String cut(String s, int max) { return s.length() > max ? s.substring(0, max) : s; }
    static OoaException bad(String m) { return new OoaException("CR.BAD_INPUT", m); }
}
