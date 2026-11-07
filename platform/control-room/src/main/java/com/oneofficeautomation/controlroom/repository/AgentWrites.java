package com.oneofficeautomation.controlroom.repository;

import com.oneofficeautomation.controlroom.ControlRoom;
import com.oneofficeautomation.controlroom.vault.VaultAdmin;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static com.oneofficeautomation.controlroom.ControlRoom.*;
import com.oneofficeautomation.controlroom.ControlRoom.User;

/**
 * What a running agent may change in One CommandCenter (gap G30): a shared value (ooa.global_values set) and a vault
 * credential's value and expiry (ooa.vault). The runner only relays the request; everything is decided here, as the
 * user the run runs as (its run-as user, or whoever started it), with the same rules people have in the console:
 * <ul>
 *   <li>Shared value: the run's user needs Manage shared values (globals.manage). Only an existing shared value can be
 *   changed, and its type is checked as in the console.</li>
 *   <li>Credential: Settings agents_write_credentials must be on, and the run's user must be allowed to change the
 *   credential (owner or manager of the vault folder, or a participant who owns the credential or creates it). For a
 *   user-provided attribute the run's user's own value is stored, as My value. Folders read from a company vault are
 *   not written.</li>
 *   <li>Expiry check: the run's user must be able to use the vault folder (any place in it, consumers included).</li>
 * </ul>
 * Every change, and every refusal, is recorded in the Audit Trail with the runner, the run, the agent and the run's
 * user; values are never recorded.
 */
public final class AgentWrites {
    /** A credential this many days or fewer from its expiry date is shown as expiring soon. */
    static final int EXPIRING_SOON_DAYS = 14;

    private final ControlRoom cr;

    public AgentWrites(ControlRoom cr) { this.cr = cr; }

    /** The run (it must be running on this runner) and the user it runs as. */
    private record RunUser(Map<String, Object> run, User user, String actor, Map<String, Object> details) { }

    private RunUser runUser(Map<String, Object> d, String runId, String objectType, String objectId, String action) {
        Map<String, Object> r = cr.runOnDevice(d, runId);
        String who = String.valueOf(r.get("run_as") != null ? r.get("run_as") : r.get("requested_by"));
        String actor = "agent:" + d.get("name") + " for " + who;
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("run", runId);
        details.put("agent", r.get("bot_path"));
        details.put("run_as", who);
        if (!"running".equals(r.get("status"))) {
            cr.audit.record(actor, action, objectType, objectId, details, "denied", null);
            throw new OoaException("VAULT.DENIED", "Only a running agent may change values in One CommandCenter");
        }
        Map<String, Object> u = cr.db.one("SELECT * FROM users WHERE username = ?", who);
        if (u == null || Boolean.FALSE.equals(u.get("enabled"))) {
            cr.audit.record(actor, action, objectType, objectId, details, "denied", null);
            throw new OoaException("CR.FORBIDDEN", "The user this run runs as (" + who + ") is not an active user", "Run the agent as an active user.");
        }
        return new RunUser(r, user(u), actor, details);
    }

    /** Changes a shared value for a running agent; returns the value as stored. */
    public Map<String, Object> setGlobal(Map<String, Object> d, String runId, String name, Object value) {
        RunUser ru = runUser(d, runId, "global", name, "global.set");
        if (!cr.can(ru.user(), "globals.manage")) {
            cr.audit.record(ru.actor(), "global.set", "global", name, ru.details(), "denied", null);
            throw new OoaException("GLOBALS.DENIED", ru.user().username() + ", the user this run runs as, may not change shared values",
                "Give that user a role with Manage shared values, or run the agent as a user who has it.");
        }
        Map<String, Object> g = name == null ? null : cr.db.one("SELECT value_type FROM global_values WHERE name = ?", name);
        if (g == null) {
            cr.audit.record(ru.actor(), "global.set", "global", name, ru.details(), "not_found", null);
            throw new OoaException("GLOBALS.NOT_FOUND", "Shared value '" + name + "' is not defined in One CommandCenter",
                "Agents change existing shared values only; create it in One CommandCenter under Shared values first.");
        }
        String type = g.get("value_type") == null ? "any" : (String) g.get("value_type");
        Object typed;
        try { typed = typedGlobal(name, type, value); }
        catch (OoaException e) {
            cr.audit.record(ru.actor(), "global.set", "global", name, ru.details(), "failed", null);
            throw new OoaException("GLOBALS.WRONG_TYPE", e.getMessage(), "Give a value of the shared value's type (" + type + ").");
        }
        String json = JsonLite.write(typed);
        if (json.length() > 100_000) throw new OoaException("GLOBALS.TOO_LARGE", "A shared value is at most 100000 characters");
        cr.db.update("UPDATE global_values SET json_value = ?, updated = ?, updated_by = ? WHERE name = ?", json, now(), ru.user().username(), name);
        cr.audit.record(ru.actor(), "global.set", "global", name, ru.details(), "ok", null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("value", typed);
        out.put("type", type);
        return out;
    }

    /** yyyy-MM-dd, or null for "no expiry date"; anything else is refused. */
    public static String expiryDate(Object v, String code) {
        if (v == null || String.valueOf(v).isBlank()) return null;
        String s = String.valueOf(v).strip();
        if (s.length() > 10) s = s.substring(0, 10);   // a date and time: its date
        try { return LocalDate.parse(s).toString(); }
        catch (DateTimeParseException e) { throw new OoaException(code, "The expiry date must be a date (yyyy-MM-dd), not '" + v + "'"); }
    }

    /** Expiry facts: the date, days left (negative once past) and whether it has expired or expires soon. */
    public static Map<String, Object> expiryFacts(String expires) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("expires", expires);
        out.put("has_expiry", expires != null);
        if (expires == null) { out.put("days_left", null); out.put("expired", false); out.put("expiring_soon", false); return out; }
        long days = ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(expires));
        out.put("days_left", days);
        out.put("expired", days < 0);
        out.put("expiring_soon", days >= 0 && days <= EXPIRING_SOON_DAYS);
        return out;
    }

    String expiresOf(String locker, String name) {
        Map<String, Object> m = cr.db.one("SELECT expires FROM credential_meta WHERE locker = ? AND name = ?", locker, name);
        return m == null || m.get("expires") == null || String.valueOf(m.get("expires")).isBlank() ? null : String.valueOf(m.get("expires"));
    }

    /** Sets (or with null clears) a credential's expiry date; the credential's details row is made when missing. */
    void storeExpiry(String locker, String name, String expires, String owner) {
        if (cr.db.update("UPDATE credential_meta SET expires = ? WHERE locker = ? AND name = ?", expires, locker, name) == 0)
            cr.db.update("INSERT INTO credential_meta (locker, name, owner, created, expires) VALUES (?,?,?,?,?)", locker, name, owner, now(), expires);
    }

    /**
     * Saves one attribute of a credential for a running agent (for example a new password after a reset) and, when
     * given, the credential's new expiry date. The value is encrypted at once and never recorded.
     */
    public Map<String, Object> setCredential(Map<String, Object> d, String runId, String ref, String value, Object expires) {
        String[] p = ref == null ? new String[0] : ref.split("/");
        RunUser ru = runUser(d, runId, "credential", ref, "credential.set");
        Map<String, Object> details = new LinkedHashMap<>(ru.details());
        details.put("by_agent", true);
        if (cr.platform.intSetting("agents_write_credentials") != 1) {
            cr.audit.record(ru.actor(), "credential.set", "credential", ref, details, "denied", null);
            throw new OoaException("VAULT.WRITE_OFF", "Agents may not save credentials in this One CommandCenter",
                "An administrator can switch on Settings > Agents and repository > Agents may save credentials.");
        }
        if (p.length != 3 || !NAME.matcher(p[0]).matches() || !NAME.matcher(p[1]).matches() || !NAME.matcher(p[2]).matches())
            throw new OoaException("VAULT.BAD_REF", "Bad credential reference '" + ref + "'", "Use vault folder/credential/attribute, with letters, digits, . _ -");
        if (value == null || value.isEmpty()) throw new OoaException("VAULT.EMPTY", "The new value is empty", "Give the credential's new value.");
        String exp = expiryDate(expires, "VAULT.BAD_DATE");
        if (exp != null) details.put("expires", exp);
        Map<String, Object> l = cr.db.one("SELECT * FROM lockers WHERE name = ?", p[0]);
        if (l == null) {
            cr.audit.record(ru.actor(), "credential.set", "credential", ref, details, "not_found", null);
            throw new OoaException("VAULT.NOT_FOUND", "Vault folder '" + p[0] + "' not found");
        }
        if (l.get("external") != null) {
            cr.audit.record(ru.actor(), "credential.set", "credential", ref, details, "denied", null);
            throw new OoaException("VAULT.EXTERNAL", "Vault folder '" + p[0] + "' reads its values from a company vault", "Change the value in the company vault itself.");
        }
        boolean own = cr.vaultAdmin.userProvided(p[0], p[1], p[2]);
        String who = ru.user().username();
        // a user-provided attribute: the run's user stores their own value, as My value in the console
        boolean allowed = own ? cr.lockerRole(ru.user(), l) != null : cr.canChangeCredential(ru.user(), l, p[1]);
        if (!allowed) {
            cr.audit.record(ru.actor(), "credential.set", "credential", ref, details, "denied", null);
            throw new OoaException("VAULT.DENIED", who + ", the user this run runs as, may not change '" + p[0] + "/" + p[1] + "'",
                own ? "Add the user to the vault folder." : "Make the user an owner or manager of the vault folder, or the credential's owner.");
        }
        if (own) details.put("user_value", true);
        synchronized (cr.vault) {
            String ct = cr.vault.encrypt(value, own ? VaultAdmin.userBound(ref, who) : ref);
            cr.db.tx(() -> {
                if (own) {
                    if (cr.db.update("UPDATE credential_user_values SET ciphertext = ?, updated = ? WHERE locker = ? AND name = ? AND attribute = ? AND username = ?", ct, now(), p[0], p[1], p[2], who) == 0)
                        cr.db.update("INSERT INTO credential_user_values (locker, name, attribute, username, ciphertext, updated) VALUES (?,?,?,?,?,?)", p[0], p[1], p[2], who, ct, now());
                } else {
                    if (cr.db.update("UPDATE credentials SET ciphertext = ?, updated = ?, updated_by = ? WHERE locker = ? AND name = ? AND attribute = ?", ct, now(), who, p[0], p[1], p[2]) == 0)
                        cr.db.update("INSERT INTO credentials (locker, name, attribute, ciphertext, updated, updated_by) VALUES (?,?,?,?,?,?)", p[0], p[1], p[2], ct, now(), who);
                    // the user who creates a credential owns it, as in the console
                    if (cr.db.one("SELECT name FROM credential_meta WHERE locker = ? AND name = ?", p[0], p[1]) == null)
                        cr.db.update("INSERT INTO credential_meta (locker, name, owner, created) VALUES (?,?,?,?)", p[0], p[1], who, now());
                }
                if (exp != null) storeExpiry(p[0], p[1], exp, who);
                return null;
            });
        }
        cr.audit.record(ru.actor(), "credential.set", "credential", ref, details, "ok", null);
        Map<String, Object> out = new LinkedHashMap<>(expiryFacts(expiresOf(p[0], p[1])));
        out.put("saved", true);
        out.put("own_value", own);
        return out;
    }

    /** A credential's expiry for a running agent: ref is folder/credential or folder/credential/attribute. */
    public Map<String, Object> credentialExpiry(Map<String, Object> d, String runId, String ref) {
        String[] p = ref == null ? new String[0] : ref.split("/");
        if (p.length != 2 && p.length != 3) throw new OoaException("VAULT.BAD_REF", "Bad credential reference '" + ref + "'", "Use vault folder/credential or vault folder/credential/attribute.");
        String credRef = p[0] + "/" + p[1];
        RunUser ru = runUser(d, runId, "credential", credRef, "credential.expiry");
        Map<String, Object> l = cr.db.one("SELECT * FROM lockers WHERE name = ?", p[0]);
        if (l == null || cr.lockerRole(ru.user(), l) == null) {
            cr.audit.record(ru.actor(), "credential.expiry", "credential", credRef, ru.details(), "denied", null);
            throw new OoaException("VAULT.DENIED", "'" + ru.user().username() + "' may not use vault folder '" + p[0] + "'", "Ask the vault folder owner to add the user or role as a consumer.");
        }
        boolean exists = cr.db.one("SELECT name FROM credential_meta WHERE locker = ? AND name = ?", p[0], p[1]) != null
            || cr.db.one("SELECT name FROM credentials WHERE locker = ? AND name = ? LIMIT 1", p[0], p[1]) != null;
        if (!exists && l.get("external") == null) throw new OoaException("VAULT.NOT_FOUND", "Credential '" + credRef + "' not found");
        return expiryFacts(expiresOf(p[0], p[1]));
    }
}
