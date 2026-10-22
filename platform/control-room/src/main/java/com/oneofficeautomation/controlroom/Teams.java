package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.sdk.OoaException;

import java.util.*;
import java.util.regex.Pattern;

import static com.oneofficeautomation.controlroom.ControlRoom.*;
import com.oneofficeautomation.controlroom.ControlRoom.User;

/**
 * Teams: named groups of people, each a member or a lead. A team is named as team:finance wherever access is given to
 * people (folder permissions, vault folder members and consumers, queue members), so adding someone to the team gives
 * them all of it. Administrators (teams.manage) create teams; leads change their own team's members.
 */
public final class Teams {
    static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_.-]{1,80}$");
    static final Set<String> ROLES = Set.of("member", "lead");
    private final ControlRoom cr;
    private final Db db;

    Teams(ControlRoom cr) { this.cr = cr; this.db = cr.db; }

    /** The names of the teams a user is in. */
    List<String> teamsOf(String username) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> r : db.query("SELECT t.name FROM teams t JOIN team_members m ON m.team_id = t.id WHERE m.username = ? ORDER BY t.name", username)) out.add((String) r.get("name"));
        return out;
    }

    /** Every way a rule can name this user: user:x, role:r, role:* and team:t for each of their teams. */
    List<String> principalsOf(String username, String role) {
        List<String> p = new ArrayList<>(List.of("user:" + username, "role:" + role, "role:*"));
        for (String t : teamsOf(username)) p.add("team:" + t);
        return p;
    }

    /** Checks a principal written in a rule: user:x, role:r (or role:*) or team:t, and that it exists. */
    void checkPrincipal(String principal) {
        if (principal.startsWith("user:")) { if (db.one("SELECT id FROM users WHERE username = ?", principal.substring(5)) == null) throw notFound("User " + principal.substring(5)); }
        else if (principal.startsWith("role:")) { String r = principal.substring(5); if (!r.equals("*") && !cr.roleExists(r)) throw notFound("Role " + r); }
        else if (principal.startsWith("team:")) { if (db.one("SELECT id FROM teams WHERE name = ?", principal.substring(5)) == null) throw notFound("Team " + principal.substring(5)); }
        else throw new OoaException("CR.BAD_INPUT", "'" + principal + "' should look like user:jane, role:bot_runner or team:finance");
    }

    /** Teams with their members; people without teams.manage see the teams they are in. */
    public List<Map<String, Object>> list(User by) {
        boolean all = cr.allowed(by.role(), "teams.manage") || cr.allowed(by.role(), "users.manage");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> t : db.query("SELECT * FROM teams ORDER BY name")) {
            List<Map<String, Object>> members = db.query("SELECT username, team_role FROM team_members WHERE team_id = ? ORDER BY team_role DESC, username", t.get("id"));
            if (!all && members.stream().noneMatch(m -> by.username().equals(m.get("username")))) continue;
            Map<String, Object> m = new LinkedHashMap<>(t);
            m.put("members", members);
            out.add(m);
        }
        return out;
    }

    public Map<String, Object> save(User by, String teamId, String name, String description, String ip) {
        cr.require(by, "teams.manage", "manage teams");
        String n = name == null ? "" : name.trim();
        if (!NAME.matcher(n).matches()) throw new OoaException("CR.BAD_INPUT", "Team names use letters, digits, . _ - (up to 80)");
        String id = teamId == null || teamId.isBlank() ? id("team") : teamId;
        if (teamId == null || teamId.isBlank()) db.update("INSERT INTO teams (id, name, description, created, created_by) VALUES (?,?,?,?,?)", id, n, description, now(), by.username());
        else {
            Map<String, Object> old = db.one("SELECT name FROM teams WHERE id = ?", id);
            if (old == null) throw notFound("Team");
            if (!old.get("name").equals(n)) throw new OoaException("CR.BAD_INPUT", "A team keeps its name (rules name it as team:" + old.get("name") + "); create a new team instead");
            db.update("UPDATE teams SET description = ? WHERE id = ?", description, id);
        }
        cr.audit.record(by.username(), "team.save", "team", n, null, "ok", ip);
        return Map.of("id", id, "name", n);
    }

    /** Deletes a team that no rule names any more (folder permissions, vault folders, queue members). */
    public void delete(User by, String teamId, String ip) {
        cr.require(by, "teams.manage", "manage teams");
        Map<String, Object> t = db.one("SELECT name FROM teams WHERE id = ?", teamId);
        if (t == null) throw notFound("Team");
        String p = "team:" + t.get("name");
        List<String> used = new ArrayList<>();
        if (cr.count("SELECT COUNT(*) AS n FROM folder_permissions WHERE principal = ?", p) > 0) used.add("folder permissions");
        if (cr.count("SELECT COUNT(*) AS n FROM queue_members WHERE principal = ?", p) > 0) used.add("work queue members");
        for (Map<String, Object> l : db.query("SELECT name, consumers, managers, participants FROM lockers"))
            for (String k : List.of("consumers", "managers", "participants")) if (csv(l.get(k)).contains(p)) { used.add("vault folder " + l.get("name")); break; }
        if (!used.isEmpty()) throw new OoaException("CR.IN_USE", "The team is still named in " + String.join(", ", used), "Remove it there first.", false, Map.of("used_by", used));
        db.tx(() -> { db.update("DELETE FROM team_members WHERE team_id = ?", teamId); db.update("DELETE FROM teams WHERE id = ?", teamId); return null; });
        cr.audit.record(by.username(), "team.delete", "team", (String) t.get("name"), null, "ok", ip);
    }

    /** Replaces the members [{username, role: member | lead}]. Administrators, or a lead of the team (who stays a lead). */
    public void setMembers(User by, String teamId, List<Object> members, String ip) {
        Map<String, Object> t = db.one("SELECT name FROM teams WHERE id = ?", teamId);
        if (t == null) throw notFound("Team");
        boolean admin = cr.allowed(by.role(), "teams.manage");
        boolean lead = db.one("SELECT username FROM team_members WHERE team_id = ? AND username = ? AND team_role = 'lead'", teamId, by.username()) != null;
        if (!admin && !lead) throw denied("change this team's members");
        List<String[]> clean = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object o : members == null ? List.of() : members) {
            if (!(o instanceof Map<?, ?> m)) throw new OoaException("CR.BAD_INPUT", "Each member is {username, role}");
            String u = String.valueOf(m.get("username")), r = m.get("role") == null ? "member" : String.valueOf(m.get("role"));
            if (!ROLES.contains(r)) throw new OoaException("CR.BAD_INPUT", "A team role is member or lead");
            if (db.one("SELECT id FROM users WHERE username = ?", u) == null) throw notFound("User " + u);
            if (seen.add(u)) clean.add(new String[]{u, r});
        }
        if (!admin && clean.stream().noneMatch(c -> c[0].equals(by.username()) && c[1].equals("lead")))
            throw new OoaException("CR.BAD_INPUT", "Keep yourself as a lead, or you could not change the team any more");
        db.tx(() -> {
            db.update("DELETE FROM team_members WHERE team_id = ?", teamId);
            for (String[] c : clean) db.update("INSERT INTO team_members (team_id, username, team_role) VALUES (?,?,?)", teamId, c[0], c[1]);
            return null;
        });
        cr.audit.record(by.username(), "team.members", "team", (String) t.get("name"), Map.of("members", clean.stream().map(c -> c[0] + "=" + c[1]).toList()), "ok", ip);
    }
}
