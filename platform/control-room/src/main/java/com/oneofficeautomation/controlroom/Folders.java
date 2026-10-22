package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.sdk.OoaException;

import java.util.*;
import java.util.regex.Pattern;

import static com.oneofficeautomation.controlroom.ControlRoom.*;
import com.oneofficeautomation.controlroom.ControlRoom.User;

/**
 * Repository folders and folder permissions. A folder can give roles (role:bot_creator), teams (team:finance) and users (user:jane) one of
 * four levels of access to what it holds: view (see and export), run (also run and schedule), edit (also check out,
 * check in, delete and recover) and manage (also set the live version and labels). The nearest folder that has any
 * permission decides: a sub-folder without its own permissions follows its parent, and a folder with permissions
 * shuts out everyone it does not name. Where no folder up to the root has permissions, the role permissions alone
 * apply, as before folders had permissions. People who can set folder permissions (repo.permissions) see everything.
 * Role permissions still apply on top: folder access never grants what the role does not allow.
 */
final class Folders {
    static final List<String> LEVELS = List.of("none", "view", "run", "edit", "manage");
    static final Pattern FOLDER = Pattern.compile("^[A-Za-z0-9 _.()-]+(/[A-Za-z0-9 _.()-]+){0,8}$");

    private final ControlRoom cr;
    private final Db db;

    Folders(ControlRoom cr) { this.cr = cr; this.db = cr.db; }

    /** The folder an agent is in ("" for the root). */
    static String folderOf(String path) { int i = path.lastIndexOf('/'); return i < 0 ? "" : path.substring(0, i); }

    static String parentOf(String folder) { return folderOf(folder); }

    static boolean atLeast(String access, String level) { return LEVELS.indexOf(access) >= LEVELS.indexOf(level); }

    /** Permission entries by folder: folder -> principal -> access. */
    Map<String, Map<String, String>> entries() {
        Map<String, Map<String, String>> m = new HashMap<>();
        for (Map<String, Object> r : db.query("SELECT folder, principal, access FROM folder_permissions"))
            m.computeIfAbsent((String) r.get("folder"), k -> new HashMap<>()).put((String) r.get("principal"), (String) r.get("access"));
        return m;
    }

    /** What a user may do in a folder: none, view, run, edit or manage. */
    String accessToFolder(String username, String role, String folder, Map<String, Map<String, String>> all) {
        return accessToFolder(cr.teams.principalsOf(username, role), role, folder, all);
    }

    /** As above with the user's principals (user:, role:, team:) worked out once by the caller. */
    String accessToFolder(List<String> principals, String role, String folder, Map<String, Map<String, String>> all) {
        if (cr.allowed(role, "repo.permissions")) return "manage";
        for (String f = folder; ; f = parentOf(f)) {
            Map<String, String> here = all.get(f);
            if (here != null && !here.isEmpty()) {
                String best = "none";
                for (String p : principals) {
                    String a = here.get(p);
                    if (a != null && LEVELS.indexOf(a) > LEVELS.indexOf(best)) best = a;
                }
                return best;
            }
            if (f.isEmpty()) return "manage";
        }
    }

    /** What a user may do with an agent (by its path). */
    String access(String username, String role, String path) { return accessToFolder(username, role, folderOf(path), entries()); }

    /** Refuses with CR.FORBIDDEN unless the user has at least this level of access to the agent's folder. */
    void require(String username, String role, String path, String level, String what) {
        String a = access(username, role, path);
        if (!atLeast(a, level)) {
            String f = folderOf(path);
            throw new OoaException("CR.FORBIDDEN", "You do not have permission to " + what + " in the folder '" + (f.isEmpty() ? "(root)" : f) + "'",
                    "Ask someone who manages the folder's permissions for " + level + " access.");
        }
    }
    void require(User u, String path, String level, String what) { require(u.username(), u.role(), path, level, what); }

    /** Every folder (made explicitly or holding agents), with the caller's access, agent count and whether it has its own permissions. */
    List<Map<String, Object>> list(User by) {
        cr.require(by, "repo.view", "view the agent repository");
        Map<String, Map<String, String>> all = entries();
        List<String> mine = cr.teams.principalsOf(by.username(), by.role());
        TreeMap<String, Map<String, Object>> out = new TreeMap<>();
        java.util.function.Consumer<String> add = f -> {
            for (String x = f; !x.isEmpty(); x = parentOf(x)) out.computeIfAbsent(x, k -> new LinkedHashMap<>(Map.of("path", k)));
        };
        for (Map<String, Object> r : db.query("SELECT path, description, created, created_by FROM repo_folders")) {
            add.accept((String) r.get("path"));
            out.get(r.get("path")).putAll(r);
        }
        Map<String, Integer> counts = new HashMap<>();
        for (Map<String, Object> b : db.query("SELECT path FROM bots WHERE deleted_at IS NULL")) {
            String f = folderOf((String) b.get("path"));
            add.accept(f);
            counts.merge(f, 1, Integer::sum);
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> f : out.values()) {
            String p = (String) f.get("path");
            String a = accessToFolder(mine, by.role(), p, all);
            if (a.equals("none")) continue;
            Map<String, Object> m = new LinkedHashMap<>(f);
            m.put("access", a);
            m.put("agents", counts.getOrDefault(p, 0));
            m.put("has_permissions", all.containsKey(p));
            list.add(m);
        }
        return list;
    }

    Map<String, Object> create(User by, String path, String description, String ip) {
        cr.require(by, "repo.checkin", "create folders");
        String p = normalize(path);
        require(by, p, "edit", "create folders");   // edit access to the parent folder (p's folder is its parent)
        if (db.one("SELECT path FROM repo_folders WHERE path = ?", p) != null) throw new OoaException("CR.CONFLICT", "The folder '" + p + "' already exists");
        db.update("INSERT INTO repo_folders (path, description, created, created_by) VALUES (?,?,?,?)", p, description, now(), by.username());
        cr.audit.record(by.username(), "repo.folder_create", "folder", p, null, "ok", ip);
        return Map.of("path", p);
    }

    /** Removes an empty folder (no agents in it or below, deleted ones included) and its permissions. */
    void delete(User by, String path, String ip) {
        cr.require(by, "repo.delete", "delete folders");
        String p = normalize(path);
        require(by, p + "/x", "manage", "delete folders");
        String like = p.replace("%", "\\%").replace("_", "\\_") + "/%";
        if (cr.count("SELECT COUNT(*) AS n FROM bots WHERE path LIKE ?", like) > 0)
            throw new OoaException("CR.CONFLICT", "The folder '" + p + "' is not empty", "Move or delete its agents first (deleted agents stay recoverable and also keep the folder).");
        db.tx(() -> {
            db.update("DELETE FROM repo_folders WHERE path = ? OR path LIKE ?", p, like);
            db.update("DELETE FROM folder_permissions WHERE folder = ? OR folder LIKE ?", p, like);
            return null;
        });
        cr.audit.record(by.username(), "repo.folder_delete", "folder", p, null, "ok", ip);
    }

    List<Map<String, Object>> permissions(User by, String folder) {
        cr.require(by, "repo.view", "view the agent repository");
        String f = folder == null ? "" : folder.isEmpty() ? "" : normalize(folder);
        return db.query("SELECT principal, access, updated, updated_by FROM folder_permissions WHERE folder = ? ORDER BY principal", f);
    }

    /** Replaces a folder's permissions; an empty list removes them, so the folder follows its parent again. */
    @SuppressWarnings("unchecked")
    void setPermissions(User by, String folder, List<Object> list, String ip) {
        cr.require(by, "repo.permissions", "set folder permissions");
        String f = folder == null || folder.isEmpty() ? "" : normalize(folder);
        List<Map<String, Object>> clean = new ArrayList<>();
        for (Object o : list == null ? List.of() : list) {
            if (!(o instanceof Map<?, ?> m)) throw new OoaException("CR.BAD_INPUT", "Each permission is {principal, access}");
            String principal = String.valueOf(m.get("principal")), access = String.valueOf(m.get("access"));
            if (!LEVELS.contains(access) || access.equals("none")) throw new OoaException("CR.BAD_INPUT", "Access is view, run, edit or manage");
            cr.teams.checkPrincipal(principal);
            clean.add(Map.of("principal", principal, "access", access));
        }
        db.tx(() -> {
            db.update("DELETE FROM folder_permissions WHERE folder = ?", f);
            for (Map<String, Object> e : clean)
                db.update("INSERT INTO folder_permissions (folder, principal, access, updated, updated_by) VALUES (?,?,?,?,?)", f, e.get("principal"), e.get("access"), now(), by.username());
            return null;
        });
        cr.audit.record(by.username(), "repo.folder_permissions", "folder", f.isEmpty() ? "(root)" : f, Map.of("permissions", clean), "ok", ip);
    }

    static String normalize(String path) {
        String p = path == null ? "" : path.trim().replace('\\', '/').replaceAll("/+$", "").replaceAll("^/+", "");
        if (!FOLDER.matcher(p).matches() || p.contains("..")) throw new OoaException("CR.BAD_PATH", "Invalid folder '" + path + "'", "Use names with letters, digits, spaces and . _ ( ) -, separated by /.");
        return p;
    }
}
