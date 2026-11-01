package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.sdk.OoaException;

import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.oneofficeautomation.controlroom.ControlRoom.*;
import com.oneofficeautomation.controlroom.ControlRoom.User;

/**
 * SCIM 2.0 provisioning (RFC 7643, 7644; Settings item 4f, 1 Nov 2026) at /scim/v2, for Microsoft Entra ID, Okta and
 * other identity providers: they create, change and remove people, and push groups. A SCIM client signs in with a
 * token made in Settings (shown once, kept as a hash). SCIM sees and changes only the accounts it made, so it can never
 * take over a local or administrator account. Removing a person (DELETE, or active false) switches the account off and
 * ends its sessions; history stays. Pushed groups give the role through the groups-to-roles list shared with single
 * sign-on; with no mapped group the role for new people is used, and with neither the account stays off. People sign
 * in with single sign-on, which finds the SCIM account by its user name.
 */
final class Scim {
    static final String USER = "urn:ietf:params:scim:schemas:core:2.0:User", GROUP = "urn:ietf:params:scim:schemas:core:2.0:Group",
        LIST = "urn:ietf:params:scim:api:messages:2.0:ListResponse", ERROR = "urn:ietf:params:scim:api:messages:2.0:Error",
        PATCH = "urn:ietf:params:scim:api:messages:2.0:PatchOp";
    private static final Pattern FILTER = Pattern.compile("^\\s*([A-Za-z.\\[\\]\" ]+?)\\s+eq\\s+\"((?:[^\"\\\\]|\\\\.)*)\"\\s*$", Pattern.CASE_INSENSITIVE);

    /** An answer: its HTTP status and body (null: none). */
    record Answer(int status, Object body) {}

    /** A SCIM error: its status, scimType and detail (RFC 7644 3.12). */
    static final class Problem extends RuntimeException {
        final int status; final String type;
        Problem(int status, String type, String detail) { super(detail); this.status = status; this.type = type; }
        Map<String, Object> body() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("schemas", List.of(ERROR)); m.put("status", String.valueOf(status));
            if (type != null) m.put("scimType", type);
            m.put("detail", getMessage());
            return m;
        }
    }

    private final ControlRoom cr;
    private final Db db;

    Scim(ControlRoom cr) { this.cr = cr; this.db = cr.db; }

    // ---------- tokens (Settings > Single sign-on) ----------

    public List<Map<String, Object>> tokens(User by) {
        cr.require(by, "settings.manage", "see SCIM tokens");
        return db.query("SELECT id, name, created, created_by, last_used FROM scim_tokens ORDER BY created");
    }

    /** A new token for an identity provider; the token itself is shown this once. */
    public Map<String, Object> makeToken(User by, String name, String ip) {
        cr.require(by, "settings.manage", "make SCIM tokens");
        String n = name == null ? "" : name.trim();
        if (n.isEmpty() || n.length() > 120) throw new OoaException("CR.BAD_INPUT", "Name the token after its identity provider, for example Entra ID");
        String token = "scim_" + Security.token(32), id = id("scim");
        db.update("INSERT INTO scim_tokens (id, name, token_hash, created, created_by) VALUES (?,?,?,?,?)", id, n, Security.sha256(token), now(), by.username());
        cr.audit.record(by.username(), "scim.token_create", "scim_token", id, Map.of("name", n), "ok", ip);
        return Map.of("id", id, "name", n, "token", token);
    }

    public void revokeToken(User by, String id, String ip) {
        cr.require(by, "settings.manage", "revoke SCIM tokens");
        if (db.update("DELETE FROM scim_tokens WHERE id = ?", id) == 0) throw notFound("SCIM token");
        cr.audit.record(by.username(), "scim.token_revoke", "scim_token", id, null, "ok", ip);
    }

    /** The token's id, or null when it is not a live SCIM token. */
    String tokenId(String bearer) {
        if (bearer == null || !bearer.startsWith("scim_")) return null;
        Map<String, Object> t = db.one("SELECT id FROM scim_tokens WHERE token_hash = ?", Security.sha256(bearer));
        if (t == null) return null;
        db.update("UPDATE scim_tokens SET last_used = ? WHERE id = ?", now(), t.get("id"));
        return (String) t.get("id");
    }

    // ---------- requests ----------

    /** One SCIM request; path is what follows /scim/v2/. */
    Answer handle(String method, String path, Map<String, String> q, Object body, String base) {
        String[] seg = path.replaceAll("^/+|/+$", "").split("/");
        String what = seg[0];
        String id = seg.length > 1 ? seg[1] : null;
        switch (what) {
            case "ServiceProviderConfig": return new Answer(200, serviceProviderConfig());
            case "ResourceTypes": return new Answer(200, list(List.of(resourceType("User", "/Users", USER), resourceType("Group", "/Groups", GROUP)), 1, 2));
            case "Schemas": return new Answer(200, list(List.of(Map.of("id", USER, "name", "User"), Map.of("id", GROUP, "name", "Group")), 1, 2));
            case "Users": return users(method, id, q, body, base);
            case "Groups": return groups(method, id, q, body, base);
            default: throw new Problem(404, null, "No SCIM resource " + what);
        }
    }

    private static Map<String, Object> serviceProviderConfig() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schemas", List.of("urn:ietf:params:scim:schemas:core:2.0:ServiceProviderConfig"));
        m.put("patch", Map.of("supported", true));
        m.put("bulk", Map.of("supported", false, "maxOperations", 0, "maxPayloadSize", 0));
        m.put("filter", Map.of("supported", true, "maxResults", 200));
        m.put("changePassword", Map.of("supported", false));
        m.put("sort", Map.of("supported", false));
        m.put("etag", Map.of("supported", false));
        m.put("authenticationSchemes", List.of(Map.of("type", "oauthbearertoken", "name", "Bearer token", "description", "A SCIM token made in One CommandCenter's Settings", "primary", true)));
        return m;
    }

    private static Map<String, Object> resourceType(String name, String endpoint, String schema) {
        return Map.of("schemas", List.of("urn:ietf:params:scim:schemas:core:2.0:ResourceType"), "id", name, "name", name, "endpoint", endpoint, "schema", schema);
    }

    private static Map<String, Object> list(List<?> items, int start, long total) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schemas", List.of(LIST)); m.put("totalResults", total); m.put("startIndex", start); m.put("itemsPerPage", items.size()); m.put("Resources", items);
        return m;
    }

    /** A page of rows: startIndex (1 first) and count (100, at most 200). */
    private static <T> Answer page(List<T> all, Map<String, String> q, java.util.function.Function<T, Object> shape) {
        int start = Math.max(1, intOr(q.get("startIndex"), 1)), count = Math.max(0, Math.min(200, intOr(q.get("count"), 100)));
        List<Object> out = new ArrayList<>();
        for (int i = start - 1; i < all.size() && out.size() < count; i++) out.add(shape.apply(all.get(i)));
        return new Answer(200, list(out, start, all.size()));
    }

    /** attribute eq "value": the attribute (lower case) and value, or a 400 for any other filter. */
    private static String[] filter(String f) {
        Matcher m = FILTER.matcher(f);
        if (!m.matches()) throw new Problem(400, "invalidFilter", "Only filters of the form attribute eq \"value\" are supported");
        return new String[] { m.group(1).trim().toLowerCase(Locale.ROOT).replace(" ", ""), m.group(2).replace("\\\"", "\"").replace("\\\\", "\\") };
    }

    // ---------- users ----------

    private Answer users(String method, String id, Map<String, String> q, Object body, String base) {
        if (id == null && method.equals("GET")) {
            List<Map<String, Object>> rows;
            if (q.get("filter") != null && !q.get("filter").isBlank()) {
                String[] f = filter(q.get("filter"));
                rows = switch (f[0]) {
                    case "username" -> db.query("SELECT * FROM users WHERE directory = 'scim' AND LOWER(username) = ?", f[1].toLowerCase(Locale.ROOT));
                    case "externalid" -> db.query("SELECT * FROM users WHERE directory = 'scim' AND external_id = ?", f[1]);
                    case "emails.value", "emails[type eq \"work\"].value", "emails[typeeq\"work\"].value" -> db.query("SELECT * FROM users WHERE directory = 'scim' AND LOWER(email) = ?", f[1].toLowerCase(Locale.ROOT));
                    case "id" -> db.query("SELECT * FROM users WHERE directory = 'scim' AND id = ?", f[1]);
                    default -> throw new Problem(400, "invalidFilter", "Users can be filtered by userName, externalId, emails.value or id");
                };
            } else rows = db.query("SELECT * FROM users WHERE directory = 'scim' ORDER BY created");
            return page(rows, q, r -> user(r, base));
        }
        if (id == null && method.equals("POST")) return new Answer(201, user(createUser(obj(body)), base));
        if (id == null) throw new Problem(405, null, "Use GET or POST on /Users");
        Map<String, Object> r = db.one("SELECT * FROM users WHERE id = ? AND directory = 'scim'", id);
        if (r == null) throw new Problem(404, null, "No SCIM user " + id);
        return switch (method) {
            case "GET" -> new Answer(200, user(r, base));
            case "PUT" -> new Answer(200, user(replaceUser(r, obj(body)), base));
            case "PATCH" -> new Answer(200, user(patchUser(r, obj(body)), base));
            case "DELETE" -> { setActive(r, false); cr.audit.record("scim", "user.disable", "user", (String) r.get("username"), Map.of("via", "scim delete"), "ok", null); yield new Answer(204, null); }
            default -> throw new Problem(405, null, "Use GET, PUT, PATCH or DELETE on a user");
        };
    }

    private Map<String, Object> user(Map<String, Object> r, String base) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schemas", List.of(USER));
        m.put("id", r.get("id"));
        if (r.get("external_id") != null) m.put("externalId", r.get("external_id"));
        m.put("userName", r.get("username"));
        Map<String, Object> name = new LinkedHashMap<>();
        if (r.get("first_name") != null) name.put("givenName", r.get("first_name"));
        if (r.get("last_name") != null) name.put("familyName", r.get("last_name"));
        name.put("formatted", r.get("display_name"));
        m.put("name", name);
        m.put("displayName", r.get("display_name"));
        if (r.get("email") != null) m.put("emails", List.of(Map.of("value", r.get("email"), "type", "work", "primary", true)));
        m.put("active", !Boolean.FALSE.equals(r.get("directory_active")));
        List<Map<String, Object>> groups = new ArrayList<>();
        for (Map<String, Object> g : db.query("SELECT g.id, g.display_name FROM scim_groups g JOIN scim_members x ON x.group_id = g.id WHERE x.user_id = ? ORDER BY g.display_name", r.get("id")))
            groups.add(Map.of("value", g.get("id"), "display", g.get("display_name"), "$ref", base + "/Groups/" + g.get("id")));
        m.put("groups", groups);
        m.put("meta", Map.of("resourceType", "User", "created", String.valueOf(r.get("created")), "lastModified", String.valueOf(r.get("created")), "location", base + "/Users/" + r.get("id")));
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> obj(Object body) {
        if (body instanceof Map<?, ?> m) return (Map<String, Object>) m;
        throw new Problem(400, "invalidSyntax", "Expected a JSON object");
    }

    /** What a SCIM user says about a person, as columns: user name, names, e-mail, active, external id. */
    private static final class Fields {
        String userName, display, first, last, email, externalId; Boolean active;
    }

    @SuppressWarnings("unchecked")
    private static Fields fields(Map<String, Object> b) {
        Fields f = new Fields();
        if (b.get("userName") != null) f.userName = String.valueOf(b.get("userName")).trim();
        if (b.get("externalId") != null) f.externalId = String.valueOf(b.get("externalId"));
        if (b.get("displayName") != null) f.display = String.valueOf(b.get("displayName"));
        if (b.get("name") instanceof Map<?, ?> n) {
            if (n.get("givenName") != null) f.first = String.valueOf(n.get("givenName"));
            if (n.get("familyName") != null) f.last = String.valueOf(n.get("familyName"));
            if (f.display == null && n.get("formatted") != null) f.display = String.valueOf(n.get("formatted"));
        }
        if (b.get("emails") instanceof List<?> es && !es.isEmpty()) {
            Object pick = es.get(0);
            for (Object e : es) if (e instanceof Map<?, ?> em && truthy(em.get("primary"))) { pick = e; break; }
            if (pick instanceof Map<?, ?> em && em.get("value") != null) f.email = String.valueOf(em.get("value")).trim();
        }
        if (b.containsKey("active")) f.active = truthy(b.get("active"));
        return f;
    }

    /** Booleans arrive as true or "True" (Entra ID sends strings in PATCH). */
    private static boolean truthy(Object v) { return v instanceof Boolean x ? x : v != null && String.valueOf(v).equalsIgnoreCase("true"); }

    private static String checkedName(String userName) {
        if (userName == null || userName.isBlank()) throw new Problem(400, "invalidValue", "userName is required");
        String u = userName.trim();
        if (u.length() > 80 || !u.matches("[A-Za-z0-9_.@+-]+")) throw new Problem(400, "invalidValue", "userName may hold letters, digits and _ . @ + - (at most 80)");
        return u;
    }

    private void nameFree(String userName, String exceptId) {
        Map<String, Object> other = db.one("SELECT id FROM users WHERE LOWER(username) = ?", userName.toLowerCase(Locale.ROOT));
        if (other != null && !other.get("id").equals(exceptId)) throw new Problem(409, "uniqueness", "An account named " + userName + " exists already");
    }

    private Map<String, Object> createUser(Map<String, Object> b) {
        Fields f = fields(b);
        String userName = checkedName(f.userName);
        nameFree(userName, null);
        if (f.email == null && userName.contains("@")) f.email = userName;
        String display = f.display != null ? f.display : (f.first != null || f.last != null) ? ((f.first == null ? "" : f.first) + " " + (f.last == null ? "" : f.last)).trim() : userName;
        String id = id("usr");
        boolean active = f.active == null || f.active;
        String role = defaultRole();
        db.update("INSERT INTO users (id, username, display_name, password_hash, role, enabled, must_change, failed, created) VALUES (?,?,?,?,?,?,?,?,?)",
            id, userName, display, Security.hashPassword(Security.token(24).toCharArray()), role == null ? "viewer" : role, active && role != null, false, 0, now());
        db.update("UPDATE users SET directory = 'scim', external_id = ?, directory_active = ?, email = ?, first_name = ?, last_name = ? WHERE id = ?", f.externalId, active, f.email, f.first, f.last, id);
        cr.audit.record("scim", "user.create", "user", userName, Map.of("via", "scim", "role", role == null ? "none yet" : role), "ok", null);
        return db.one("SELECT * FROM users WHERE id = ?", id);
    }

    private Map<String, Object> replaceUser(Map<String, Object> r, Map<String, Object> b) {
        Fields f = fields(b);
        String userName = checkedName(f.userName);
        nameFree(userName, (String) r.get("id"));
        String display = f.display != null ? f.display : userName;
        db.update("UPDATE users SET username = ?, display_name = ?, first_name = ?, last_name = ?, email = ?, external_id = ? WHERE id = ?",
            userName, display, f.first, f.last, f.email, f.externalId != null ? f.externalId : r.get("external_id"), r.get("id"));
        cr.audit.record("scim", "user.update", "user", userName, Map.of("via", "scim"), "ok", null);
        Map<String, Object> now = db.one("SELECT * FROM users WHERE id = ?", r.get("id"));
        if (f.active != null) setActive(now, f.active);
        return db.one("SELECT * FROM users WHERE id = ?", r.get("id"));
    }

    /** PATCH: add, replace and remove on the attributes One CommandCenter keeps; others (title, addresses...) are accepted and ignored. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> patchUser(Map<String, Object> r, Map<String, Object> b) {
        Map<String, Object> set = new LinkedHashMap<>();
        Boolean active = null;
        for (Object o : ops(b)) {
            Map<String, Object> op = (Map<String, Object>) o;
            String kind = String.valueOf(op.getOrDefault("op", "")).toLowerCase(Locale.ROOT), path = op.get("path") == null ? null : String.valueOf(op.get("path"));
            Object value = op.get("value");
            if (!List.of("add", "replace", "remove").contains(kind)) throw new Problem(400, "invalidSyntax", "op is add, replace or remove");
            Map<String, Object> changes = new LinkedHashMap<>();
            if (path == null) { if (value instanceof Map<?, ?> vm) changes.putAll((Map<String, Object>) vm); else throw new Problem(400, "invalidSyntax", "Without a path, value is an object"); }
            else changes.put(path, kind.equals("remove") ? null : value);
            for (var c : changes.entrySet()) {
                String p = c.getKey().replace(" ", "").toLowerCase(Locale.ROOT);
                Object v = c.getValue();
                switch (p) {
                    case "active" -> active = truthy(v);
                    case "username" -> { String u = checkedName(v == null ? null : String.valueOf(v)); nameFree(u, (String) r.get("id")); set.put("username", u); }
                    case "displayname", "name.formatted" -> set.put("display_name", v == null ? r.get("username") : String.valueOf(v));
                    case "name.givenname" -> set.put("first_name", v == null ? null : String.valueOf(v));
                    case "name.familyname" -> set.put("last_name", v == null ? null : String.valueOf(v));
                    case "externalid" -> set.put("external_id", v == null ? null : String.valueOf(v));
                    case "emails", "emails[type eq\"work\"].value", "emails[typeeq\"work\"].value", "emails.value" -> {
                        Object e = v instanceof List<?> l && !l.isEmpty() ? (l.get(0) instanceof Map<?, ?> em ? em.get("value") : l.get(0)) : v instanceof Map<?, ?> em ? em.get("value") : v;
                        set.put("email", e == null ? null : String.valueOf(e).trim());
                    }
                    case "name" -> { if (v instanceof Map<?, ?> n) { if (n.containsKey("givenName")) set.put("first_name", n.get("givenName")); if (n.containsKey("familyName")) set.put("last_name", n.get("familyName")); } }
                    default -> { /* an attribute One CommandCenter does not keep */ }
                }
            }
        }
        for (var e : set.entrySet())   // the column names are the fixed ones above, never from the request
            switch (e.getKey()) {
                case "username" -> db.update("UPDATE users SET username = ? WHERE id = ?", e.getValue(), r.get("id"));
                case "display_name" -> db.update("UPDATE users SET display_name = ? WHERE id = ?", e.getValue(), r.get("id"));
                case "first_name" -> db.update("UPDATE users SET first_name = ? WHERE id = ?", e.getValue(), r.get("id"));
                case "last_name" -> db.update("UPDATE users SET last_name = ? WHERE id = ?", e.getValue(), r.get("id"));
                case "external_id" -> db.update("UPDATE users SET external_id = ? WHERE id = ?", e.getValue(), r.get("id"));
                case "email" -> db.update("UPDATE users SET email = ? WHERE id = ?", e.getValue(), r.get("id"));
                default -> { }
            }
        if (!set.isEmpty()) cr.audit.record("scim", "user.update", "user", (String) r.get("username"), Map.of("via", "scim", "changed", new ArrayList<>(set.keySet())), "ok", null);
        Map<String, Object> now = db.one("SELECT * FROM users WHERE id = ?", r.get("id"));
        if (active != null) setActive(now, active);
        return db.one("SELECT * FROM users WHERE id = ?", r.get("id"));
    }

    @SuppressWarnings("unchecked")
    private static List<Object> ops(Map<String, Object> b) {
        if (!(b.get("Operations") instanceof List<?> l)) throw new Problem(400, "invalidSyntax", "A PATCH holds Operations");
        return (List<Object>) l;
    }

    /** The identity provider's active flag; the account is on only when active and a role is given. Off ends its sessions. */
    private void setActive(Map<String, Object> r, boolean active) {
        db.update("UPDATE users SET directory_active = ? WHERE id = ?", active, r.get("id"));
        if (!active) cr.audit.record("scim", "user.disable", "user", (String) r.get("username"), Map.of("via", "scim"), "ok", null);
        recompute((String) r.get("id"));
    }

    /** The role for new people (Settings), when it is a role in use; else null. */
    private String defaultRole() {
        String d = String.valueOf(cr.platform.setting("sso_default_role")).trim();
        return d.isEmpty() || !cr.roleExists(d) || cr.roleSwitchedOff(d) ? null : d;
    }

    /** The person's SCIM groups give the role (else the role for new people); with neither, or not active, the account is off. */
    void recompute(String userId) {
        Map<String, Object> r = db.one("SELECT * FROM users WHERE id = ?", userId);
        if (r == null) return;
        List<String> names = new ArrayList<>();
        for (Map<String, Object> g : db.query("SELECT g.display_name FROM scim_groups g JOIN scim_members x ON x.group_id = g.id WHERE x.user_id = ?", userId)) names.add((String) g.get("display_name"));
        String role = cr.roleFromGroups(names);
        if (role == null) role = defaultRole();
        boolean on = !Boolean.FALSE.equals(r.get("directory_active")) && role != null;
        if (role != null && !role.equals(r.get("role"))) {
            db.update("UPDATE users SET role = ? WHERE id = ?", role, userId);
            cr.audit.record("scim", "user.role", "user", (String) r.get("username"), Map.of("from", String.valueOf(r.get("role")), "to", role, "via", "scim groups"), "ok", null);
        }
        if (on != Boolean.TRUE.equals(r.get("enabled"))) db.update("UPDATE users SET enabled = ? WHERE id = ?", on, userId);
        if (!on) db.update("DELETE FROM sessions WHERE user_id = ?", userId);
    }

    // ---------- groups ----------

    private Answer groups(String method, String id, Map<String, String> q, Object body, String base) {
        boolean members = !String.valueOf(q.getOrDefault("excludedAttributes", "")).toLowerCase(Locale.ROOT).contains("members");
        if (id == null && method.equals("GET")) {
            List<Map<String, Object>> rows;
            if (q.get("filter") != null && !q.get("filter").isBlank()) {
                String[] f = filter(q.get("filter"));
                rows = switch (f[0]) {
                    case "displayname" -> db.query("SELECT * FROM scim_groups WHERE LOWER(display_name) = ?", f[1].toLowerCase(Locale.ROOT));
                    case "externalid" -> db.query("SELECT * FROM scim_groups WHERE external_id = ?", f[1]);
                    case "id" -> db.query("SELECT * FROM scim_groups WHERE id = ?", f[1]);
                    default -> throw new Problem(400, "invalidFilter", "Groups can be filtered by displayName, externalId or id");
                };
            } else rows = db.query("SELECT * FROM scim_groups ORDER BY display_name");
            return page(rows, q, g -> group(g, base, members));
        }
        if (id == null && method.equals("POST")) return new Answer(201, group(createGroup(obj(body)), base, true));
        if (id == null) throw new Problem(405, null, "Use GET or POST on /Groups");
        Map<String, Object> g = db.one("SELECT * FROM scim_groups WHERE id = ?", id);
        if (g == null) throw new Problem(404, null, "No SCIM group " + id);
        return switch (method) {
            case "GET" -> new Answer(200, group(g, base, members));
            case "PUT" -> new Answer(200, group(replaceGroup(g, obj(body)), base, true));
            case "PATCH" -> new Answer(200, group(patchGroup(g, obj(body)), base, true));
            case "DELETE" -> {
                List<String> had = memberIds(id);
                db.update("DELETE FROM scim_members WHERE group_id = ?", id);
                db.update("DELETE FROM scim_groups WHERE id = ?", id);
                cr.audit.record("scim", "scim.group_delete", "scim_group", id, Map.of("name", g.get("display_name")), "ok", null);
                had.forEach(this::recompute);
                yield new Answer(204, null);
            }
            default -> throw new Problem(405, null, "Use GET, PUT, PATCH or DELETE on a group");
        };
    }

    private List<String> memberIds(String groupId) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> m : db.query("SELECT user_id FROM scim_members WHERE group_id = ?", groupId)) out.add((String) m.get("user_id"));
        return out;
    }

    private Map<String, Object> group(Map<String, Object> g, String base, boolean withMembers) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schemas", List.of(GROUP));
        m.put("id", g.get("id"));
        if (g.get("external_id") != null) m.put("externalId", g.get("external_id"));
        m.put("displayName", g.get("display_name"));
        if (withMembers) {
            List<Map<String, Object>> ms = new ArrayList<>();
            for (Map<String, Object> u : db.query("SELECT u.id, u.username FROM users u JOIN scim_members x ON x.user_id = u.id WHERE x.group_id = ? ORDER BY u.username", g.get("id")))
                ms.add(Map.of("value", u.get("id"), "display", u.get("username"), "$ref", base + "/Users/" + u.get("id")));
            m.put("members", ms);
        }
        m.put("meta", Map.of("resourceType", "Group", "created", String.valueOf(g.get("created")), "lastModified", String.valueOf(g.get("updated")), "location", base + "/Groups/" + g.get("id")));
        return m;
    }

    private static String groupName(Object v) {
        String n = v == null ? "" : String.valueOf(v).trim();
        if (n.isEmpty() || n.length() > 256) throw new Problem(400, "invalidValue", "displayName is required (at most 256 characters)");
        return n;
    }

    private void groupNameFree(String name, String exceptId) {
        Map<String, Object> other = db.one("SELECT id FROM scim_groups WHERE LOWER(display_name) = ?", name.toLowerCase(Locale.ROOT));
        if (other != null && !other.get("id").equals(exceptId)) throw new Problem(409, "uniqueness", "A group named " + name + " exists already");
    }

    /** Member ids from [{value}]: only accounts SCIM made; others are left out. */
    private List<String> scimUsers(Object members) {
        List<String> out = new ArrayList<>();
        if (members instanceof List<?> l) for (Object o : l) {
            Object v = o instanceof Map<?, ?> m ? m.get("value") : o;
            if (v != null && db.one("SELECT id FROM users WHERE id = ? AND directory = 'scim'", String.valueOf(v)) != null && !out.contains(String.valueOf(v))) out.add(String.valueOf(v));
        }
        return out;
    }

    private void setMembers(String groupId, Collection<String> add, Collection<String> remove) {
        Set<String> touched = new LinkedHashSet<>();
        for (String u : remove) if (db.update("DELETE FROM scim_members WHERE group_id = ? AND user_id = ?", groupId, u) > 0) touched.add(u);
        for (String u : add) if (db.one("SELECT user_id FROM scim_members WHERE group_id = ? AND user_id = ?", groupId, u) == null) { db.update("INSERT INTO scim_members (group_id, user_id) VALUES (?,?)", groupId, u); touched.add(u); }
        if (!touched.isEmpty()) db.update("UPDATE scim_groups SET updated = ? WHERE id = ?", now(), groupId);
        touched.forEach(this::recompute);
    }

    private Map<String, Object> createGroup(Map<String, Object> b) {
        String name = groupName(b.get("displayName"));
        groupNameFree(name, null);
        String id = id("grp");
        db.update("INSERT INTO scim_groups (id, display_name, external_id, created, updated) VALUES (?,?,?,?,?)", id, name, b.get("externalId") == null ? null : String.valueOf(b.get("externalId")), now(), now());
        cr.audit.record("scim", "scim.group_create", "scim_group", id, Map.of("name", name), "ok", null);
        setMembers(id, scimUsers(b.get("members")), List.of());
        return db.one("SELECT * FROM scim_groups WHERE id = ?", id);
    }

    private Map<String, Object> replaceGroup(Map<String, Object> g, Map<String, Object> b) {
        String id = (String) g.get("id"), name = groupName(b.get("displayName"));
        groupNameFree(name, id);
        boolean renamed = !name.equals(g.get("display_name"));
        db.update("UPDATE scim_groups SET display_name = ?, external_id = ?, updated = ? WHERE id = ?", name, b.get("externalId") == null ? g.get("external_id") : String.valueOf(b.get("externalId")), now(), id);
        List<String> want = scimUsers(b.get("members")), had = memberIds(id);
        setMembers(id, want, had.stream().filter(u -> !want.contains(u)).toList());
        if (renamed) memberIds(id).forEach(this::recompute);   // a new name can map to another role
        return db.one("SELECT * FROM scim_groups WHERE id = ?", id);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> patchGroup(Map<String, Object> g, Map<String, Object> b) {
        String id = (String) g.get("id");
        for (Object o : ops(b)) {
            Map<String, Object> op = (Map<String, Object>) o;
            String kind = String.valueOf(op.getOrDefault("op", "")).toLowerCase(Locale.ROOT), path = op.get("path") == null ? "" : String.valueOf(op.get("path")).trim();
            Object value = op.get("value");
            Matcher one = Pattern.compile("^members\\[value eq \"([^\"]+)\"\\]$", Pattern.CASE_INSENSITIVE).matcher(path);
            if (path.equalsIgnoreCase("members")) {
                List<String> ids = scimUsers(value);
                switch (kind) {
                    case "add" -> setMembers(id, ids, List.of());
                    case "remove" -> setMembers(id, List.of(), value == null ? memberIds(id) : ids);
                    case "replace" -> { List<String> had = memberIds(id); setMembers(id, ids, had.stream().filter(u -> !ids.contains(u)).toList()); }
                    default -> throw new Problem(400, "invalidSyntax", "op is add, replace or remove");
                }
            } else if (one.matches() && kind.equals("remove")) setMembers(id, List.of(), List.of(one.group(1)));
            else if (path.equalsIgnoreCase("displayName") || (path.isEmpty() && value instanceof Map<?, ?> vm && vm.containsKey("displayName"))) {
                String name = groupName(path.isEmpty() ? ((Map<String, Object>) value).get("displayName") : value);
                groupNameFree(name, id);
                db.update("UPDATE scim_groups SET display_name = ?, updated = ? WHERE id = ?", name, now(), id);
                memberIds(id).forEach(this::recompute);
            } else if (path.equalsIgnoreCase("externalId")) db.update("UPDATE scim_groups SET external_id = ? WHERE id = ?", value == null ? null : String.valueOf(value), id);
            else if (!path.isEmpty()) throw new Problem(400, "invalidPath", "Groups take members, displayName or externalId");
        }
        cr.audit.record("scim", "scim.group_update", "scim_group", id, Map.of("name", g.get("display_name")), "ok", null);
        return db.one("SELECT * FROM scim_groups WHERE id = ?", id);
    }
}
