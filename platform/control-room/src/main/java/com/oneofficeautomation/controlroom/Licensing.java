package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.controlroom.ControlRoom.User;
import com.oneofficeautomation.sdk.OoaException;

import java.security.PublicKey;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;

/**
 * The installed license and what it allows. States: unlicensed (no file: nothing is limited), active, expiring (30 days
 * or fewer left: a banner for administrators, critical in the last 7), grace (expired, still working for the grace
 * days in the file) and expired (runs stop and only administrators can sign in, to install a new license).
 * While a license is installed: seats cannot be assigned beyond the file's counts, checking in agents needs a Developer
 * or Citizen Developer seat, and a runner takes Command Center work only with an Unattended Runner seat; a person with an
 * Attended Runner (or developer) seat may still run agents on their own default runner.
 */
public final class Licensing {
    private final ControlRoom cr;
    private final Db db;
    private volatile Supplier<PublicKey> key;
    /** The clock (tests move it). */
    Clock clock = Clock.systemUTC();
    private volatile String cachedText;
    private volatile License cachedLicense;

    Licensing(ControlRoom cr) { this(cr, License::vendorKey); }
    Licensing(ControlRoom cr, Supplier<PublicKey> key) { this.cr = cr; this.db = cr.db; this.key = key; }

    /** The installed license, checked again against the signature (cached while the file is unchanged), or null. */
    License current() {
        Map<String, Object> r = db.one("SELECT content FROM licenses WHERE active = TRUE");
        if (r == null) return null;
        String text = (String) r.get("content");
        if (!text.equals(cachedText)) { cachedLicense = License.verify(text, key.get()); cachedText = text; }
        return cachedLicense;
    }

    private LocalDate today() { return LocalDate.now(clock); }

    public String state() {
        License l;
        try { l = current(); } catch (OoaException e) { return "expired"; }   // a stored file that no longer verifies counts as no valid license
        if (l == null) return "unlicensed";
        LocalDate t = today();
        if (!t.isAfter(l.expires())) return ChronoUnit.DAYS.between(t, l.expires()) <= 30 ? "expiring" : "active";
        return t.isAfter(l.expires().plusDays(l.graceDays())) ? "expired" : "grace";
    }

    /** True while a license is installed and still in force (limits apply). */
    boolean enforcing() { String s = state(); return s.equals("active") || s.equals("expiring") || s.equals("grace"); }

    boolean expired() { return state().equals("expired"); }

    static boolean isAdmin(ControlRoom cr, User u) { return cr.can(u, "settings.manage"); }

    /** Status for the console: everyone gets the state and banner; administrators also get terms and seats. */
    public Map<String, Object> status(User by) {
        Map<String, Object> m = new LinkedHashMap<>();
        String st = state();
        m.put("state", st);
        License l = null;
        try { l = current(); } catch (OoaException e) { m.put("problem", e.getMessage()); }
        if (l != null) {
            long left = ChronoUnit.DAYS.between(today(), l.expires());
            m.put("days_left", left);
            m.put("expires", l.expires().toString());
            m.put("grace_until", l.expires().plusDays(l.graceDays()).toString());
            String text = switch (st) {
                case "expiring" -> "The license expires in " + left + (left == 1 ? " day" : " days") + " (" + l.expires() + "). Install a new license in Administration > Licenses.";
                case "grace" -> "The license expired on " + l.expires() + ". Everything keeps working until " + l.expires().plusDays(l.graceDays()) + "; install a new license before then.";
                case "expired" -> "The license has expired. Runs are stopped and only administrators can sign in. Install a new license in Administration > Licenses.";
                default -> null;
            };
            if (text != null && (isAdmin(cr, by) || st.equals("expired"))) m.put("banner", Map.of("level", st.equals("expiring") && left > 7 ? "warning" : "critical", "text", text));
        }
        if (isAdmin(cr, by) || cr.can(by, "users.manage")) {
            if (l != null) { m.put("id", l.id()); m.put("customer", l.customer()); m.put("edition", l.edition()); m.put("issued", l.issued().toString()); m.put("grace_days", l.graceDays()); }
            Map<String, Object> seats = new LinkedHashMap<>();
            for (String t : License.TYPES) seats.put(t, Map.of("total", l == null ? -1 : l.seats().get(t), "used", used(t)));
            m.put("seats", seats);
        }
        return m;
    }

    long used(String type) { return cr.count("SELECT COUNT(*) AS n FROM license_assignments WHERE license_type = ?", type); }

    /** Installs a license file (administrators): it must verify; it replaces the license in force. */
    public Map<String, Object> install(User by, String text, String ip) {
        cr.require(by, "settings.manage", "install licenses");
        if (text == null || text.isBlank() || text.length() > 20000) throw new OoaException("LICENSE.INVALID", "Paste or upload the license file");
        License l = License.verify(text, key.get());
        if (today().isAfter(l.expires().plusDays(l.graceDays()))) throw new OoaException("LICENSE.EXPIRED_FILE", "This license expired on " + l.expires(), "Ask for a current license");
        for (String t : License.TYPES) if (used(t) > l.seats().get(t))
            throw new OoaException("LICENSE.TOO_FEW_SEATS", "This license has " + l.seats().get(t) + " " + label(t) + " seats but " + used(t) + " are assigned", "Take seats back in Administration > Licenses first");
        db.tx(() -> {
            db.update("UPDATE licenses SET active = FALSE");
            db.update("DELETE FROM licenses WHERE id = ?", l.id());
            db.update("INSERT INTO licenses (id, content, customer, expires, installed, installed_by, active) VALUES (?,?,?,?,?,?,?)",
                l.id(), text.trim(), l.customer(), l.expires().toString(), Instant.now(clock), by.username(), true);
            cr.audit.record(by.username(), "license.install", "license", l.id(), Map.of("customer", l.customer(), "expires", l.expires().toString(), "seats", l.seats()), "ok", ip);
            return null;
        });
        cachedText = null;
        return status(by);
    }

    /** Who holds which seat. */
    public Map<String, Object> assignments(User by) {
        if (!cr.can(by, "users.manage") && !isAdmin(cr, by)) throw cr.denied("see license assignments");
        return Map.of("assignments", db.query("SELECT principal_type, principal, license_type, assigned, assigned_by FROM license_assignments ORDER BY license_type, principal"));
    }

    /** Gives or takes back a seat. Person seats go to user names, Unattended Runner seats to runner ids. */
    public Map<String, Object> assign(User by, String type, String principal, boolean on, String ip) {
        if (!cr.can(by, "users.manage")) throw cr.denied("assign licenses");
        if (!License.TYPES.contains(type)) throw new OoaException("CR.BAD_INPUT", "License types: " + License.TYPES);
        String kind = License.DEVICE_TYPES.contains(type) ? "device" : "user";
        if (principal == null || principal.isBlank()) throw new OoaException("CR.BAD_INPUT", "Choose a " + (kind.equals("device") ? "runner" : "person"));
        if (!on) {
            db.update("DELETE FROM license_assignments WHERE principal_type = ? AND principal = ? AND license_type = ?", kind, principal, type);
            cr.audit.record(by.username(), "license.unassign", kind, principal, Map.of("license", type), "ok", ip);
            return status(by);
        }
        if (kind.equals("user") ? db.one("SELECT id FROM users WHERE username = ?", principal) == null : db.one("SELECT id FROM devices WHERE id = ?", principal) == null)
            throw ControlRoom.notFound(kind.equals("user") ? "User " + principal : "Runner " + principal);
        if (has(kind, principal, type)) return status(by);
        License l = enforcing() ? current() : null;
        db.tx(() -> {
            if (l != null && used(type) >= l.seats().get(type))
                throw new OoaException("CR.LICENSE_LIMIT", "All " + l.seats().get(type) + " " + label(type) + " seats are in use", "Take a seat back from someone, or install a license with more seats");
            db.update("INSERT INTO license_assignments (principal_type, principal, license_type, assigned, assigned_by) VALUES (?,?,?,?,?)", kind, principal, type, Instant.now(clock), by.username());
            cr.audit.record(by.username(), "license.assign", kind, principal, Map.of("license", type), "ok", ip);
            return null;
        });
        return status(by);
    }

    /** Tests sign licenses with their own key pair (package-private: the product only knows the vendor key). */
    void useKeyForTests(PublicKey k) { this.key = () -> k; this.cachedText = null; }

    boolean has(String kind, String principal, String type) {
        return db.one("SELECT principal FROM license_assignments WHERE principal_type = ? AND principal = ? AND license_type = ?", kind, principal, type) != null;
    }

    /** Checking in agents needs a Developer or Citizen Developer seat while a license is in force. */
    void requireDeveloper(User by) {
        if (!enforcing()) { if (expired()) throw expiredError(); return; }
        if (!has("user", by.username(), "developer") && !has("user", by.username(), "citizen_developer"))
            throw new OoaException("CR.LICENSE_REQUIRED", by.username() + " has no Developer or Citizen Developer license", "An administrator assigns one in Administration > Licenses");
    }

    /**
     * Why a run may not go to a runner, or null. While a license is in force the runner needs an Unattended Runner seat,
     * unless a person with an Attended Runner, Developer or Citizen Developer seat started it by hand on their own runner.
     */
    String runProblem(String deviceId, String requestedBy, String source) {
        if (expired()) return "The license has expired, so runs are stopped";
        if (!enforcing() || has("device", deviceId, "unattended_runner")) return null;
        boolean byHand = "manual".equals(source) || "studio".equals(source);
        boolean seat = has("user", requestedBy, "attended_runner") || has("user", requestedBy, "developer") || has("user", requestedBy, "citizen_developer");
        boolean own = db.one("SELECT device_id FROM user_devices WHERE username = ? AND device_id = ?", requestedBy, deviceId) != null;
        if (byHand && seat && own) return null;
        return "This runner has no Unattended Runner license (an administrator assigns one in Administration > Licenses)";
    }

    void requireRunnable(String deviceId, String requestedBy, String source) {
        String p = runProblem(deviceId, requestedBy, source);
        if (p != null) throw expired() ? expiredError() : new OoaException("CR.LICENSE_REQUIRED", p);
    }

    /** After the grace period only administrators may sign in. */
    void checkSignIn(User u) { if (expired() && !isAdmin(cr, u)) throw expiredError(); }

    static OoaException expiredError() {
        return new OoaException("CR.LICENSE_EXPIRED", "The ONEOFFICEAUTOMATION license has expired", "An administrator installs a new license in Administration > Licenses");
    }

    static String label(String type) {
        return switch (type) {
            case "developer" -> "Developer"; case "citizen_developer" -> "Citizen Developer"; case "attended_runner" -> "Attended Runner"; default -> "Unattended Runner";
        };
    }
}
