package com.oneofficeautomation.controlroom;

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
        for (Map<String, Object> d : cr.db.query("SELECT d.id, d.name, d.hostname, d.os, d.agent_version, d.enabled, d.last_seen, d.device_type, d.maintenance FROM devices d "
                + "WHERE d.id IN (SELECT device_id FROM user_devices WHERE username = ?) ORDER BY d.name", u.username())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.get("id")); m.put("name", d.get("name")); m.put("computer", d.get("hostname")); m.put("platform", d.get("os"));
            m.put("version", d.get("agent_version"));
            if (d.get("last_seen") != null) m.put("last_seen", String.valueOf(d.get("last_seen")));
            boolean online = d.get("last_seen") != null && Instant.parse(d.get("last_seen").toString()).isAfter(ControlRoom.now().minus(ControlRoom.ONLINE));
            m.put("status", !Boolean.TRUE.equals(d.get("enabled")) ? "disabled" : d.get("maintenance") != null ? "out of service" : online ? "online" : "offline");
            m.put("shared", !"single_user".equals(d.get("device_type")));
            m.put("default", d.get("id").equals(defaultId));
            runners.add(m);
        }
        out.put("runners", runners);
        Map<String, Object> lastRun = cr.db.one("SELECT r.status, r.error, r.finished, r.bot_path, d.name AS device FROM runs r LEFT JOIN devices d ON d.id = r.device_id "
                + "WHERE (r.requested_by = ? OR r.run_as = ?) AND r.finished IS NOT NULL ORDER BY r.finished DESC", u.username(), u.username());
        if (lastRun != null && "failed".equals(lastRun.get("status")) && String.valueOf(lastRun.get("error")).contains("AGENT.LOST"))
            out.put("hint", "Your last run stopped because " + (lastRun.get("device") == null ? "its runner" : lastRun.get("device"))
                    + " stopped reporting at " + lastRun.get("finished") + ". Check that the computer is on, signed in and connected, then run it again.");
        return out;
    }
}
