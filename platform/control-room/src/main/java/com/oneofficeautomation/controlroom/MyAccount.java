package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.sdk.OoaException;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * My account (13 Nov 2026 round 3, item 9, stage A), as AA's user menu: a person's own profile, where they are signed
 * in, and the runners they work with. Everyone may see their own; nothing here needs a permission and nothing is stored
 * beyond what users, sessions, teams, licence seats and runners already hold. Signing out other sessions is recorded in
 * the Audit Trail.
 */
final class MyAccount {
    private final ControlRoom cr;
    MyAccount(ControlRoom cr) { this.cr = cr; }

    /** A registration key a person made for their own computer is created by "self:<user name>" (stage B3). */
    static final String SELF = "self:";

    /**
     * Stage B1, as AA's Edit profile: a person changes their own first name, last name and contact e-mail (the same
     * checks as Administration > Users: one address belongs to one person). Accounts from single sign-on or a directory
     * keep what the provider says.
     */
    void updateProfile(ControlRoom.User u, Map<String, Object> changes, String ip) {
        Map<String, Object> r = cr.db.one("SELECT sso_subject, directory FROM users WHERE id = ?", u.id());
        if (r != null && (r.get("sso_subject") != null || r.get("directory") != null))
            throw new OoaException("CR.BAD_INPUT", "Your name and e-mail come from your organisation's sign-in", "Change them there; they update at your next sign-in.");
        Map<String, Object> done = new LinkedHashMap<>();
        for (String f : List.of("first_name", "last_name")) {
            if (!changes.containsKey(f)) continue;
            String v = changes.get(f) == null ? "" : String.valueOf(changes.get(f)).strip();
            if (v.length() > 50) throw new OoaException("CR.BAD_INPUT", f.replace('_', ' ') + " has up to 50 characters", "Shorten it to 50 characters or fewer.");
            String sql = f.equals("first_name") ? "UPDATE users SET first_name = ? WHERE id = ?" : "UPDATE users SET last_name = ? WHERE id = ?";
            cr.db.update(sql, v.isEmpty() ? null : v, u.id());
            done.put(f, v);
        }
        if (changes.containsKey("email")) {
            String e = changes.get("email") == null ? "" : String.valueOf(changes.get("email")).strip().toLowerCase(Locale.ROOT);
            if (!e.isEmpty() && (e.length() > 200 || !e.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))) throw new OoaException("CR.BAD_INPUT", "Not an e-mail address: " + e, "Write it as name@example.com.");
            if (!e.isEmpty() && cr.db.one("SELECT id FROM users WHERE LOWER(email) = ? AND id <> ?", e, u.id()) != null)
                throw new OoaException("CR.CONFLICT", "Another user has the e-mail address " + e, "Use another address, or ask an administrator.");
            cr.db.update("UPDATE users SET email = ? WHERE id = ?", e.isEmpty() ? null : e, u.id());
            done.put("email", e);
        }
        if (done.containsKey("first_name") || done.containsKey("last_name")) {
            Map<String, Object> n = cr.db.one("SELECT first_name, last_name FROM users WHERE id = ?", u.id());
            String full = ((n.get("first_name") == null ? "" : n.get("first_name")) + " " + (n.get("last_name") == null ? "" : n.get("last_name"))).trim();
            if (!full.isEmpty()) cr.db.update("UPDATE users SET display_name = ? WHERE id = ?", full, u.id());
        }
        cr.audit.record(u.username(), "user.profile", "user", u.username(), done, "ok", ip);
    }

    /** Stage B2, as AA's Default device > Choose: the runners a person may pick (switched on; single-user ones nobody else has). */
    List<Map<String, Object>> runnerChoices(ControlRoom.User u) {
        cr.require(u, "devices.choose_own", "choose your own runner");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> d : cr.db.query("SELECT d.id, d.name, d.hostname, d.device_type FROM devices d WHERE d.enabled = TRUE ORDER BY d.name")) {
            boolean taken = "single_user".equals(d.get("device_type"))
                && cr.db.one("SELECT username FROM user_devices WHERE device_id = ? AND username <> ?", d.get("id"), u.username()) != null;
            if (!taken) out.add(Map.of("id", d.get("id"), "name", d.get("name"), "computer", d.get("hostname") == null ? "" : d.get("hostname")));
        }
        return out;
    }

    /** Stage B2: the person's own default runner, chosen by them; recorded in the Audit Trail. */
    void chooseDefault(ControlRoom.User u, String deviceId, String ip) {
        cr.require(u, "devices.choose_own", "choose your own runner");
        if (deviceId == null || deviceId.isBlank()) throw new OoaException("CR.BAD_INPUT", "Choose a runner");
        if (runnerChoices(u).stream().noneMatch((d) -> deviceId.equals(d.get("id"))))
            throw new OoaException("CR.CONFLICT", "You cannot choose this runner", "It is switched off, or it is a single-user runner someone else already has.");
        cr.db.tx(() -> {
            cr.db.update("DELETE FROM user_devices WHERE username = ?", u.username());
            cr.db.update("INSERT INTO user_devices (username, device_id, updated, updated_by) VALUES (?,?,?,?)", u.username(), deviceId, ControlRoom.now(), u.username());
            return null;
        });
        cr.audit.record(u.username(), "user.default_runner", "device", deviceId, Map.of("by", "self"), "ok", ip);
    }

    /**
     * Stage B3, as AA's Connect local device: a one-hour, one-time key for the person's own computer. The runner it
     * registers is theirs (their local runner) and, if they have none, their default runner (ControlRoom.registerDevice).
     */
    Map<String, Object> connectKey(ControlRoom.User u, String ip) {
        cr.require(u, "devices.register_own", "connect your own computer");
        if (cr.platform.intSetting("runner_registration_open") == 0)
            throw new OoaException("CR.REGISTRATION_CLOSED", "New runners cannot register now", "An administrator opens registrations in Settings > Runner Machines.");
        String key = "ooareg_" + Security.token(24);
        cr.db.update("INSERT INTO registration_keys (key_hash, created_by, created, expires) VALUES (?,?,?,?)", Security.sha256(key), SELF + u.username(),
                ControlRoom.now(), ControlRoom.now().plus(Duration.ofHours(1)));
        cr.audit.record(u.username(), "device.registration_key", "device", null, Map.of("own_computer", true), "ok", ip);
        return Map.of("key", key, "expires_in_hours", 1);
    }

    /** Name, e-mail, roles, teams, licence seat, two-step sign-in, last sign-in and when the password must change. */
    Map<String, Object> profile(ControlRoom.User u) {
        Map<String, Object> r = cr.db.one("SELECT username, display_name, first_name, last_name, email, role, created, last_login, password_changed, sso_subject, directory "
                + "FROM users WHERE id = ?", u.id());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("username", u.username());
        out.put("display_name", u.displayName() == null ? u.username() : u.displayName());
        if (r != null) for (String k : List.of("first_name", "last_name", "email", "created", "last_login")) if (r.get(k) != null) out.put(k, String.valueOf(r.get(k)));
        out.put("roles", cr.rolesOf(u.username(), u.role()));
        out.put("permissions", cr.permissionsOf(u));
        List<Map<String, Object>> teams = new ArrayList<>();
        for (Map<String, Object> t : cr.db.query("SELECT t.name, m.team_role FROM team_members m JOIN teams t ON t.id = m.team_id WHERE m.username = ? ORDER BY t.name", u.username()))
            teams.add(Map.of("name", t.get("name"), "lead", "lead".equals(t.get("team_role"))));
        out.put("teams", teams);
        List<String> seats = new ArrayList<>();
        for (Map<String, Object> a : cr.db.query("SELECT license_type FROM license_assignments WHERE principal_type = 'user' AND principal = ? ORDER BY license_type", u.username()))
            seats.add(String.valueOf(a.get("license_type")));
        out.put("license_seats", seats);
        out.put("mfa_enabled", u.mfaEnabled());
        out.put("sign_in", r != null && r.get("sso_subject") != null ? "single sign-on" : r != null && r.get("directory") != null ? "directory (" + r.get("directory") + ")" : "password");
        int maxDays = cr.platform.intSetting("password_max_days");
        if (maxDays > 0 && r != null && r.get("sso_subject") == null) {
            Object changed = r.get("password_changed") != null ? r.get("password_changed") : r.get("created");
            if (changed != null) out.put("password_expires", Instant.parse(changed.toString()).plus(Duration.ofDays(maxDays)).toString());
        }
        Map<String, Object> last = cr.db.one("SELECT ip, created FROM sessions WHERE user_id = ? ORDER BY created DESC", u.id());
        if (last != null && last.get("ip") != null) out.put("last_sign_in_from", last.get("ip"));
        return out;
    }

    /** Where the person is signed in now, newest first; the one making this call is marked. */
    List<Map<String, Object>> sessions(ControlRoom.User u, String token) {
        String mine = token == null ? "" : Security.sha256(token);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> s : cr.db.query("SELECT token_hash, created, expires, ip FROM sessions WHERE user_id = ? AND expires > ? ORDER BY created DESC", u.id(), ControlRoom.now())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", String.valueOf(s.get("token_hash")).substring(0, 12));   // enough to tell them apart; never the token
            m.put("created", String.valueOf(s.get("created")));
            m.put("expires", String.valueOf(s.get("expires")));
            m.put("from", s.get("ip") == null ? "" : s.get("ip"));
            m.put("current", mine.equals(s.get("token_hash")));
            out.add(m);
        }
        return out;
    }

    /** Signs the person out everywhere except here; returns how many sessions ended. */
    int signOutOthers(ControlRoom.User u, String token, String ip) {
        String mine = token == null ? "" : Security.sha256(token);
        int n = cr.db.update("DELETE FROM sessions WHERE user_id = ? AND token_hash <> ?", u.id(), mine);
        cr.audit.record(u.username(), "user.sessions.end_others", "user", u.username(), Map.of("ended", n), "ok", ip);
        return n;
    }

    /**
     * The runners the person works with: their default runner and every runner they are a default user of, each with
     * whether it is online, its version and when it last reported; and, when their latest run failed because the runner
     * stopped reporting, that in plain words.
     */
    Map<String, Object> devices(ControlRoom.User u) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> def = cr.db.one("SELECT device_id FROM user_devices WHERE username = ?", u.username());
        String defaultId = def == null ? null : (String) def.get("device_id");
        out.put("default_runner", defaultId);
        List<Map<String, Object>> runners = new ArrayList<>();
        // the runners they are the default user of, and the ones they connected themselves (their local runners, B3)
        for (Map<String, Object> d : cr.db.query("SELECT d.id, d.name, d.hostname, d.os, d.agent_version, d.enabled, d.last_seen, d.device_type, d.maintenance, d.registered_by FROM devices d "
                + "WHERE d.id IN (SELECT device_id FROM user_devices WHERE username = ?) OR d.registered_by = ? ORDER BY d.name", u.username(), SELF + u.username())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.get("id")); m.put("name", d.get("name")); m.put("computer", d.get("hostname")); m.put("platform", d.get("os"));
            m.put("version", d.get("agent_version"));
            if (d.get("last_seen") != null) m.put("last_seen", String.valueOf(d.get("last_seen")));
            boolean online = d.get("last_seen") != null && Instant.parse(d.get("last_seen").toString()).isAfter(ControlRoom.now().minus(ControlRoom.ONLINE));
            m.put("status", !Boolean.TRUE.equals(d.get("enabled")) ? "disabled" : d.get("maintenance") != null ? "out of service" : online ? "online" : "offline");
            m.put("shared", !"single_user".equals(d.get("device_type")));
            m.put("default", d.get("id").equals(defaultId));
            m.put("local", (SELF + u.username()).equals(d.get("registered_by")));
            m.put("login", DeviceLogin.state(cr, String.valueOf(d.get("id"))));
            runners.add(m);
        }
        out.put("runners", runners);
        out.put("can_choose", cr.can(u, "devices.choose_own"));
        out.put("can_connect", cr.can(u, "devices.register_own"));
        out.put("credentials_set", DeviceLogin.isSet(cr, u.username()));
        Map<String, Object> lastRun = cr.db.one("SELECT r.status, r.error, r.finished, r.bot_path, d.name AS device FROM runs r LEFT JOIN devices d ON d.id = r.device_id "
                + "WHERE (r.requested_by = ? OR r.run_as = ?) AND r.finished IS NOT NULL ORDER BY r.finished DESC", u.username(), u.username());
        if (lastRun != null && "failed".equals(lastRun.get("status")) && String.valueOf(lastRun.get("error")).contains("AGENT.LOST"))
            out.put("hint", "Your last run stopped because " + (lastRun.get("device") == null ? "its runner" : lastRun.get("device"))
                    + " stopped reporting at " + lastRun.get("finished") + ". Check that the computer is on, signed in and connected, then run it again.");
        return out;
    }
}
