package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.controlroom.ControlRoom.User;
import com.oneofficeautomation.sdk.OoaException;

import java.security.PublicKey;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;

/**
 * The installed license and what it allows. Without a license file the vendor build (license tools present) is
 * unlicensed and unlimited, and a customer install runs a 30-day evaluation (2 developers, 2 attended and 2 unattended
 * runners, given out automatically on first use), then stops like an expired license. States: unlicensed, evaluation,
 * evaluation_ended, active, expiring (30 days
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
    /** Tests choose the vendor (true) or customer (false) behaviour; null: decided by the class path. */
    Boolean vendorOverride;

    /** Tests say whether the database counts as a server database (null: true unless it is the embedded H2). */
    Boolean serverDatabaseOverride;

    /** Licensed customer installs run on a server database (SQL Server or PostgreSQL), never on the embedded one. */
    boolean serverDatabase() { return serverDatabaseOverride != null ? serverDatabaseOverride : !db.h2; }

    static final String DATABASE_STEPS = "Stop the Command Center, then in its folder run: bin\\ooa.bat db configure --engine sqlserver --host DBSERVER --database ooa_command_center "
        + "--user ooa_app --setup-user ooa_setup; bin\\ooa.bat db set-password; bin\\ooa.bat db set-password --setup; bin\\ooa.bat db init; "
        + "bin\\ooa.bat db copy --from \"%USERPROFILE%\\OOA Command Center\" (keeps the evaluation's data). Start it again and install the license. See INSTALL.md, section 5.";

    static final int EVALUATION_DAYS = 30;
    static final Map<String, Integer> EVALUATION_SEATS = Map.of("developer", 2, "citizen_developer", 0, "attended_runner", 2, "unattended_runner", 2);

    /** The vendor build carries META-INF/ooa-vendor (the vendor-tools module); the customer distribution does not. */
    boolean vendorBuild() {
        if (vendorOverride != null) return vendorOverride;
        return Licensing.class.getClassLoader().getResource("META-INF/ooa-vendor") != null;
    }

    /**
     * The first day of the evaluation, recorded at the first start. It is stored encrypted with the vault key, so an edited
     * value counts as an evaluation that has ended.
     */
    LocalDate evaluationStart() {
        Map<String, Object> r = db.one("SELECT json_value FROM settings WHERE name = 'evaluation_started'");
        if (r == null) {
            String start = today().toString();
            try {
                db.update("INSERT INTO settings (name, json_value, updated, updated_by) VALUES (?,?,?,?)", "evaluation_started",
                    com.oneofficeautomation.sdk.JsonLite.write(cr.vault.encrypt(start, "evaluation")), Instant.now(clock), "system");
            } catch (OoaException e) { return evaluationStart(); }   // another request recorded it at the same moment
            return today();
        }
        try { return LocalDate.parse(cr.vault.decrypt(String.valueOf(com.oneofficeautomation.sdk.JsonLite.parse((String) r.get("json_value"))), "evaluation")); }
        catch (RuntimeException e) { return LocalDate.of(2000, 1, 1); }
    }

    /**
     * This Command Center's install id (OOA-XXXX-XXXX-XXXX-XXXX), made at the first start. A license with an install id
     * works only where it matches. It is stored encrypted with the vault key, so copying it into another database without
     * that key does not move a license.
     */
    public String installId() {
        Map<String, Object> r = db.one("SELECT json_value FROM settings WHERE name = 'install_id'");
        if (r != null) {
            try { return cr.vault.decrypt(String.valueOf(com.oneofficeautomation.sdk.JsonLite.parse((String) r.get("json_value"))), "install-id"); }
            catch (RuntimeException e) { return "unreadable"; }   // edited, or the vault key changed: no bound license matches
        }
        java.security.SecureRandom random = new java.security.SecureRandom();
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";   // no 0/O or 1/I to misread
        StringBuilder id = new StringBuilder("OOA");
        for (int i = 0; i < 16; i++) { if (i % 4 == 0) id.append('-'); id.append(alphabet.charAt(random.nextInt(alphabet.length()))); }
        try {
            db.update("INSERT INTO settings (name, json_value, updated, updated_by) VALUES (?,?,?,?)", "install_id",
                com.oneofficeautomation.sdk.JsonLite.write(cr.vault.encrypt(id.toString(), "install-id")), Instant.now(clock), "system");
        } catch (OoaException e) { return installId(); }   // made by another request at the same moment
        return id.toString();
    }

    /** True when the license is tied to another installation. */
    boolean otherInstall(License l) { return !l.installId().isEmpty() && !l.installId().equalsIgnoreCase(installId()); }

    static final String OTHER_INSTALL = "This license was issued for another installation (install id %s; this one is %s)";

    /** Seats of a type: the license's, the evaluation's, or -1 for no limit. */
    int total(String type) {
        String st = state();
        if (st.equals("evaluation") || st.equals("evaluation_ended")) return EVALUATION_SEATS.get(type);
        License l;
        try { l = current(); } catch (OoaException e) { return 0; }
        return l == null ? -1 : l.seats().get(type);
    }

    /** During the evaluation a free seat is given automatically on first use. */
    private boolean autoSeat(String kind, String principal, String type) {
        if (!state().equals("evaluation") || has(kind, principal, type)) return has(kind, principal, type);
        if (used(type) >= EVALUATION_SEATS.get(type)) return false;
        db.update("INSERT INTO license_assignments (principal_type, principal, license_type, assigned, assigned_by) VALUES (?,?,?,?,?)", kind, principal, type, Instant.now(clock), "evaluation");
        cr.audit.record("system", "license.assign", kind, principal, Map.of("license", type, "evaluation", true), "ok", null);
        return true;
    }

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
        if (l == null) {
            if (vendorBuild()) return "unlicensed";
            return ChronoUnit.DAYS.between(evaluationStart(), today()) < EVALUATION_DAYS ? "evaluation" : "evaluation_ended";
        }
        // a licensed customer install on the embedded database (it can only get there by going around the install check)
        if (!vendorBuild() && !serverDatabase()) return "database_required";
        if (otherInstall(l)) return "expired";   // a license copied from another installation counts as none
        LocalDate t = today();
        if (!t.isAfter(l.expires())) return ChronoUnit.DAYS.between(t, l.expires()) <= 30 ? "expiring" : "active";
        return t.isAfter(l.expires().plusDays(l.graceDays())) ? "expired" : "grace";
    }

    /** True while a license is installed and still in force (limits apply). */
    boolean enforcing() { String s = state(); return s.equals("active") || s.equals("expiring") || s.equals("grace") || s.equals("evaluation"); }

    boolean expired() { String s = state(); return s.equals("expired") || s.equals("evaluation_ended") || s.equals("database_required"); }

    static boolean isAdmin(ControlRoom cr, User u) { return cr.can(u, "settings.manage"); }

    /** Status for the console: everyone gets the state and banner; administrators also get terms and seats. */
    public Map<String, Object> status(User by) {
        Map<String, Object> m = new LinkedHashMap<>();
        String st = state();
        m.put("state", st);
        // a customer install on the embedded database must move to SQL Server before a license can be installed
        if (!vendorBuild() && !serverDatabase()) { m.put("requires_server_database", true); m.put("database_steps", DATABASE_STEPS); }
        License l = null;
        try { l = current(); } catch (OoaException e) { m.put("problem", e.getMessage()); }
        if (l != null && otherInstall(l)) m.put("problem", String.format(OTHER_INSTALL, l.installId(), installId()) + ". Ask ONEOFFICEAUTOMATION for a license for this install id.");
        if (l != null) {
            long left = ChronoUnit.DAYS.between(today(), l.expires());
            m.put("days_left", left);
            m.put("expires", l.expires().toString());
            m.put("grace_until", l.expires().plusDays(l.graceDays()).toString());
            String text = st.equals("database_required") ? "A licensed installation runs on Microsoft SQL Server. Runs are stopped until the database is moved: " + DATABASE_STEPS : null;
            if (text == null) text = switch (st) {
                case "expiring" -> "The license expires in " + left + (left == 1 ? " day" : " days") + " (" + l.expires() + "). Install a new license in Administration > Licenses.";
                case "grace" -> "The license expired on " + l.expires() + ". Everything keeps working until " + l.expires().plusDays(l.graceDays()) + "; install a new license before then.";
                case "expired" -> "The license has expired. Runs are stopped and only administrators can sign in. Install a new license in Administration > Licenses.";
                default -> null;
            };
            if (text != null && (isAdmin(cr, by) || st.equals("expired"))) m.put("banner", Map.of("level", st.equals("expiring") && left > 7 ? "warning" : "critical", "text", text));
        } else if (st.equals("evaluation") || st.equals("evaluation_ended")) {
            LocalDate end = evaluationStart().plusDays(EVALUATION_DAYS);
            long left = Math.max(0, ChronoUnit.DAYS.between(today(), end));
            m.put("days_left", left);
            m.put("evaluation_ends", end.toString());
            String text = st.equals("evaluation")
                ? "Evaluation: " + left + (left == 1 ? " day" : " days") + " left (until " + end + "). Install a license in Administration > Licenses to keep using ONEOFFICEAUTOMATION."
                : "The 30-day evaluation has ended. Runs are stopped and only administrators can sign in. Install a license in Administration > Licenses.";
            if (isAdmin(cr, by) || st.equals("evaluation_ended")) m.put("banner", Map.of("level", st.equals("evaluation") && left > 7 ? "warning" : "critical", "text", text));
        }
        if (isAdmin(cr, by) || cr.can(by, "users.manage")) {
            if (l != null) { m.put("id", l.id()); m.put("customer", l.customer()); m.put("edition", l.edition()); m.put("issued", l.issued().toString()); m.put("grace_days", l.graceDays());
                m.put("bound_to", l.installId().isEmpty() ? "any installation" : l.installId()); }
            m.put("install_id", installId());
            Map<String, Object> seats = new LinkedHashMap<>();
            for (String t : License.TYPES) seats.put(t, Map.of("total", total(t), "used", used(t)));
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
        if (otherInstall(l)) throw new OoaException("LICENSE.OTHER_INSTALL", String.format(OTHER_INSTALL, l.installId(), installId()),
            "Send this install id (Administration > Licenses) to ONEOFFICEAUTOMATION for a license issued for it");
        if (!vendorBuild() && !serverDatabase())
            throw new OoaException("LICENSE.DB_REQUIRED", "A licensed installation runs on Microsoft SQL Server; this one still uses the built-in evaluation database", DATABASE_STEPS);
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
        int limit = enforcing() ? total(type) : -1;
        db.tx(() -> {
            if (limit >= 0 && used(type) >= limit)
                throw new OoaException("CR.LICENSE_LIMIT", "All " + limit + " " + label(type) + " seats are in use", "Take a seat back from someone, or install a license with more seats");
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
        if (has("user", by.username(), "developer") || has("user", by.username(), "citizen_developer") || autoSeat("user", by.username(), "developer")) return;
        if (state().equals("evaluation")) throw new OoaException("CR.LICENSE_LIMIT", "The evaluation allows " + EVALUATION_SEATS.get("developer") + " developers, and both seats are in use", "Install a license in Administration > Licenses");
        throw new OoaException("CR.LICENSE_REQUIRED", by.username() + " has no Developer or Citizen Developer license", "An administrator assigns one in Administration > Licenses");
    }

    /**
     * Why a run may not go to a runner, or null. While a license is in force the runner needs an Unattended Runner seat,
     * unless a person with an Attended Runner, Developer or Citizen Developer seat started it by hand on their own runner.
     */
    String runProblem(String deviceId, String requestedBy, String source) {
        if (expired()) return "The license has expired, so runs are stopped";
        if (!enforcing() || has("device", deviceId, "unattended_runner") || autoSeat("device", deviceId, "unattended_runner")) return null;
        boolean byHand = "manual".equals(source) || "studio".equals(source);
        boolean seat = has("user", requestedBy, "attended_runner") || has("user", requestedBy, "developer") || has("user", requestedBy, "citizen_developer");
        boolean own = db.one("SELECT device_id FROM user_devices WHERE username = ? AND device_id = ?", requestedBy, deviceId) != null;
        if (byHand && seat && own) return null;
        return state().equals("evaluation") ? "The evaluation allows " + EVALUATION_SEATS.get("unattended_runner") + " runners, and both are in use (install a license in Administration > Licenses)"
            : "This runner has no Unattended Runner license (an administrator assigns one in Administration > Licenses)";
    }

    void requireRunnable(String deviceId, String requestedBy, String source) {
        String p = runProblem(deviceId, requestedBy, source);
        if (p != null) throw expired() ? expiredError() : new OoaException("CR.LICENSE_REQUIRED", p);
    }

    /** After the grace period only administrators may sign in. */
    void checkSignIn(User u) { if (expired() && !isAdmin(cr, u)) throw expiredError(); }

    OoaException expiredError() {
        License l;
        try { l = current(); } catch (OoaException e) { l = null; }
        if (l != null && otherInstall(l)) return new OoaException("CR.LICENSE_EXPIRED", String.format(OTHER_INSTALL, l.installId(), installId()),
            "An administrator installs a license issued for this install id in Administration > Licenses");
        if (state().equals("database_required")) return new OoaException("CR.LICENSE_EXPIRED", "A licensed installation runs on Microsoft SQL Server; this one uses the built-in database", DATABASE_STEPS);
        return new OoaException("CR.LICENSE_EXPIRED", state().equals("evaluation_ended") ? "The ONEOFFICEAUTOMATION evaluation has ended" : "The ONEOFFICEAUTOMATION license has expired",
            "An administrator installs a license in Administration > Licenses");
    }

    // ---------- expiry e-mails (L3) and the usage report (L2) ----------

    /** Days before the end at which administrators get an e-mail, once each. */
    static final int[] MAIL_DAYS = { 30, 15, 7, 3, 2, 1 };

    /** Who gets licence e-mails: people who may change settings and have an e-mail address, plus Settings > license_mail_to. */
    List<String> mailRecipients() {
        Set<String> to = new LinkedHashSet<>();
        for (Map<String, Object> r : db.query("SELECT * FROM users WHERE enabled = TRUE AND email IS NOT NULL")) {
            String email = String.valueOf(r.get("email")).trim();
            if (!email.isEmpty() && isAdmin(cr, ControlRoom.user(r))) to.add(email);
        }
        for (String a : String.valueOf(cr.platform.setting("license_mail_to")).split("[,;\\s]+")) if (!a.isBlank()) to.add(a.trim());
        return new ArrayList<>(to);
    }

    /**
     * Called by the server clock: records today's seat use once a day, and when the license or the evaluation ends in 30,
     * 15, 7, 3, 2 or 1 days (or the license has just expired), e-mails the administrators once for that step.
     */
    void mailIfDue() {
        snapshotIfDue();
        if (!cr.mailer.configured()) return;
        String st = state();
        License l;
        try { l = current(); } catch (OoaException e) { return; }
        LocalDate end;
        if (l != null && (st.equals("active") || st.equals("expiring") || st.equals("grace"))) end = l.expires();
        else if (l == null && st.equals("evaluation")) end = evaluationStart().plusDays(EVALUATION_DAYS);
        else return;
        long left = ChronoUnit.DAYS.between(today(), end);
        String step = null;
        if (left < 0) step = "expired";
        else for (int d : MAIL_DAYS) if (left <= d) step = String.valueOf(d);
        if (step == null) return;
        String marker = end + ":" + step;
        Map<String, Object> sent = db.one("SELECT json_value FROM settings WHERE name = 'license_mail_sent'");
        if (sent != null && marker.equals(com.oneofficeautomation.sdk.JsonLite.parse((String) sent.get("json_value")))) return;
        List<String> to = mailRecipients();
        if (to.isEmpty()) return;
        String what = l != null ? "The ONEOFFICEAUTOMATION license" + (l.customer().isEmpty() ? "" : " for " + l.customer()) : "The ONEOFFICEAUTOMATION evaluation";
        String subject, body;
        if (left < 0) {
            subject = "ONEOFFICEAUTOMATION license expired on " + end;
            body = what + " expired on " + end + ". Everything keeps working until " + l.expires().plusDays(l.graceDays()) + "; after that runs stop and only administrators can sign in.";
        } else {
            subject = "ONEOFFICEAUTOMATION " + (l == null ? "evaluation" : "license") + " ends in " + left + (left == 1 ? " day" : " days");
            body = what + " ends on " + end + " (" + left + (left == 1 ? " day" : " days") + " from today).";
        }
        String url = String.valueOf(cr.platform.setting("public_url"));
        body += "\n\nInstall a new license in Administration > Licenses. This install's id is " + installId() + "; send it to ONEOFFICEAUTOMATION when you ask for one."
            + (url.isEmpty() ? "" : "\n\nCommand Center: " + url);
        cr.mailer.send(to, subject, body);
        String json = com.oneofficeautomation.sdk.JsonLite.write(marker);
        if (db.update("UPDATE settings SET json_value = ?, updated = ? WHERE name = 'license_mail_sent'", json, Instant.now(clock)) == 0)
            db.update("INSERT INTO settings (name, json_value, updated, updated_by) VALUES (?,?,?,?)", "license_mail_sent", json, Instant.now(clock), "system");
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("step", step);
        details.put("recipients", to.size());
        cr.audit.record("system", "license.mail", "license", l == null ? null : l.id(), details, "ok", null);
    }

    /** Records each type's seats in use and in total for today (once a day; the usage trend reads them). */
    void snapshotIfDue() {
        String day = today().toString();
        if (db.one("SELECT snap_day FROM license_usage WHERE snap_day = ?", day) != null) return;
        for (String t : License.TYPES) {
            try { db.update("INSERT INTO license_usage (snap_day, license_type, used, total) VALUES (?,?,?,?)", day, t, (int) used(t), total(t)); }
            catch (OoaException raced) { return; }   // recorded at the same moment elsewhere
        }
    }

    /**
     * Licence use for administrators: seats per type, who holds each seat with their last activity and runs in the last
     * 30 days (a seat with neither is marked idle, to take back), and the daily trend over the given days.
     */
    public Map<String, Object> usage(User by, int days) {
        if (!cr.can(by, "users.manage") && !isAdmin(cr, by)) throw cr.denied("see license use");
        snapshotIfDue();
        int span = Math.max(7, Math.min(365, days));
        Instant since = Instant.now(clock).minus(Duration.ofDays(30));
        List<Map<String, Object>> holders = new ArrayList<>();
        for (Map<String, Object> a : db.query("SELECT principal_type, principal, license_type, assigned, assigned_by FROM license_assignments ORDER BY license_type, principal")) {
            Map<String, Object> m = new LinkedHashMap<>(a);
            boolean device = "device".equals(a.get("principal_type"));
            Map<String, Object> who = device ? db.one("SELECT name, last_seen FROM devices WHERE id = ?", a.get("principal"))
                : db.one("SELECT display_name, last_login FROM users WHERE username = ?", a.get("principal"));
            Object last = who == null ? null : who.get(device ? "last_seen" : "last_login");
            Object name = who == null ? null : who.get(device ? "name" : "display_name");
            m.put("name", name == null ? a.get("principal") : name);
            m.put("last_active", last);
            long runs = device ? cr.count("SELECT COUNT(*) AS n FROM runs WHERE device_id = ? AND created > ?", a.get("principal"), since)
                : cr.count("SELECT COUNT(*) AS n FROM runs WHERE requested_by = ? AND created > ?", a.get("principal"), since);
            m.put("runs_30_days", runs);
            m.put("idle", (last == null || Instant.parse(String.valueOf(last)).isBefore(since)) && runs == 0);
            holders.add(m);
        }
        Map<String, Object> seats = new LinkedHashMap<>();
        for (String t : License.TYPES) seats.put(t, Map.of("label", label(t), "total", total(t), "used", used(t),
            "idle", holders.stream().filter(h -> t.equals(h.get("license_type")) && Boolean.TRUE.equals(h.get("idle"))).count()));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", state());
        m.put("seats", seats);
        m.put("holders", holders);
        m.put("trend", db.query("SELECT snap_day, license_type, used, total FROM license_usage WHERE snap_day >= ? ORDER BY snap_day, license_type", today().minusDays(span).toString()));
        return m;
    }

    /** The holders list as CSV (the usage report's download). */
    @SuppressWarnings("unchecked")
    public String usageCsv(User by) {
        StringBuilder sb = new StringBuilder("license,holder_type,holder,name,assigned,assigned_by,last_active,runs_30_days,idle\r\n");
        for (Map<String, Object> h : (List<Map<String, Object>>) usage(by, 30).get("holders")) {
            List<String> cells = new ArrayList<>();
            for (String k : List.of("license_type", "principal_type", "principal", "name", "assigned", "assigned_by", "last_active", "runs_30_days", "idle")) {
                Object v = h.get(k);
                cells.add(csv(k.equals("license_type") ? label(String.valueOf(v)) : v == null ? "" : String.valueOf(v)));
            }
            sb.append(String.join(",", cells)).append("\r\n");
        }
        return sb.toString();
    }

    /** A CSV cell: quoted when needed, and never starting like a spreadsheet formula. */
    static String csv(String v) {
        String s = !v.isEmpty() && "=+-@".indexOf(v.charAt(0)) >= 0 ? "'" + v : v;
        return s.contains(",") || s.contains("\"") || s.contains("\n") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    static String label(String type) {
        return switch (type) {
            case "developer" -> "Developer"; case "citizen_developer" -> "Citizen Developer"; case "attended_runner" -> "Attended Runner"; default -> "Unattended Runner";
        };
    }
}
