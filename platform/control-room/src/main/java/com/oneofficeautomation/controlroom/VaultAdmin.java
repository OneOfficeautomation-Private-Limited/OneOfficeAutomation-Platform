package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.sdk.OoaException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

import static com.oneofficeautomation.controlroom.ControlRoom.*;
import com.oneofficeautomation.controlroom.ControlRoom.User;

/**
 * One Agent Vault administration beyond single attribute values: whole credentials (details, owner, delete), vault
 * folder members (owners, managers, participants, consumers) and deleting a folder, user-provided attributes (each
 * person keeps their own value; a run reads the value of the person it runs as), and the master key (status and
 * rotation, which re-encrypts every stored value with a new key).
 */
public final class VaultAdmin {
    private final ControlRoom cr;
    private final Db db;

    VaultAdmin(ControlRoom cr) { this.cr = cr; this.db = cr.db; }

    /** The name a person's own value is encrypted against (so it cannot be swapped with another person's). */
    static String userBound(String ref, String username) { return ref + "#user:" + username; }

    Map<String, Object> locker(String name) {
        Map<String, Object> l = db.one("SELECT * FROM lockers WHERE name = ?", name);
        if (l == null) throw notFound("Vault folder " + name);
        return l;
    }

    boolean userProvided(String locker, String name, String attribute) {
        Map<String, Object> m = db.one("SELECT user_attributes FROM credential_meta WHERE locker = ? AND name = ?", locker, name);
        return m != null && csv(m.get("user_attributes")).contains(attribute);
    }

    /** Credentials of a folder with owner, description, user-provided attributes and which of them the caller has a value for. */
    List<Map<String, Object>> credentialInfo(User by, String locker) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map<String, Object> c : db.query("SELECT DISTINCT name FROM credentials WHERE locker = ? ORDER BY name", locker)) out.put((String) c.get("name"), new LinkedHashMap<>(Map.of("name", c.get("name"))));
        for (Map<String, Object> m : db.query("SELECT * FROM credential_meta WHERE locker = ? ORDER BY name", locker)) {
            Map<String, Object> r = out.computeIfAbsent((String) m.get("name"), k -> new LinkedHashMap<>(Map.of("name", k)));
            r.put("owner", m.get("owner"));
            r.put("description", m.get("description"));
            r.put("user_attributes", csv(m.get("user_attributes")));
        }
        for (Map<String, Object> r : out.values()) {
            r.putIfAbsent("user_attributes", List.of());
            List<String> mine = new ArrayList<>();
            for (Map<String, Object> v : db.query("SELECT attribute FROM credential_user_values WHERE locker = ? AND name = ? AND username = ?", locker, r.get("name"), by.username()))
                mine.add((String) v.get("attribute"));
            r.put("my_values", mine);
        }
        return new ArrayList<>(out.values());
    }

    /** One credential: its attributes (never their values), owner, description and user-provided attributes. */
    public Map<String, Object> credential(User by, String locker, String name) {
        Map<String, Object> l = locker(locker);
        if (cr.lockerRole(by, l) == null) throw denied("see this vault folder");
        List<Map<String, Object>> attrs = db.query("SELECT attribute, updated, updated_by FROM credentials WHERE locker = ? AND name = ? ORDER BY attribute", locker, name);
        Map<String, Object> meta = db.one("SELECT * FROM credential_meta WHERE locker = ? AND name = ?", locker, name);
        if (attrs.isEmpty() && meta == null) throw notFound("Credential " + locker + "/" + name);
        List<String> userAttrs = meta == null ? List.of() : csv(meta.get("user_attributes"));
        List<Object> list = new ArrayList<>();
        for (Map<String, Object> a : attrs) { Map<String, Object> m = new LinkedHashMap<>(a); m.put("user_provided", userAttrs.contains(a.get("attribute"))); list.add(m); }
        for (String ua : userAttrs) if (attrs.stream().noneMatch(a -> ua.equals(a.get("attribute")))) list.add(Map.of("attribute", ua, "user_provided", true));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("locker", locker); out.put("name", name);
        out.put("owner", meta == null ? null : meta.get("owner"));
        out.put("description", meta == null ? null : meta.get("description"));
        out.put("attributes", list);
        out.put("can_change", cr.canChangeCredential(by, l, name));
        List<String> mine = new ArrayList<>();
        for (Map<String, Object> v : db.query("SELECT attribute FROM credential_user_values WHERE locker = ? AND name = ? AND username = ?", locker, name, by.username())) mine.add((String) v.get("attribute"));
        out.put("my_values", mine);
        return out;
    }

    /**
     * Sets a credential's description, its user-provided attributes (each person stores their own value for those) and
     * its owner (a person; only folder owners and managers can hand it over).
     */
    public void saveCredential(User by, String locker, String name, Map<String, Object> b, String ip) {
        Map<String, Object> l = locker(locker);
        if (!cr.canChangeCredential(by, l, name)) throw denied("change this credential");
        if (!NAME.matcher(name == null ? "" : name).matches()) throw new OoaException("CR.BAD_INPUT", "Credential names use letters, digits, . _ -");
        Map<String, Object> meta = db.one("SELECT * FROM credential_meta WHERE locker = ? AND name = ?", locker, name);
        if (meta == null) {
            db.update("INSERT INTO credential_meta (locker, name, owner, created) VALUES (?,?,?,?)", locker, name, by.username(), now());
            meta = db.one("SELECT * FROM credential_meta WHERE locker = ? AND name = ?", locker, name);
        }
        if (b.containsKey("description")) db.update("UPDATE credential_meta SET description = ? WHERE locker = ? AND name = ?", b.get("description") == null ? null : String.valueOf(b.get("description")), locker, name);
        if (b.containsKey("user_attributes")) {
            List<String> ua = csv(b.get("user_attributes"));
            for (String a : ua) if (!NAME.matcher(a).matches()) throw new OoaException("CR.BAD_INPUT", "Attribute names use letters, digits, . _ -");
            for (String a : ua) if (db.one("SELECT attribute FROM credentials WHERE locker = ? AND name = ? AND attribute = ?", locker, name, a) != null)
                throw new OoaException("CR.CONFLICT", "'" + a + "' already has a shared value", "Delete that value first, so nobody's own value is mixed up with it.");
            db.update("UPDATE credential_meta SET user_attributes = ? WHERE locker = ? AND name = ?", String.join(",", ua), locker, name);
            // values of attributes that are no longer user-provided are removed
            for (Map<String, Object> v : db.query("SELECT DISTINCT attribute FROM credential_user_values WHERE locker = ? AND name = ?", locker, name))
                if (!ua.contains((String) v.get("attribute"))) db.update("DELETE FROM credential_user_values WHERE locker = ? AND name = ? AND attribute = ?", locker, name, v.get("attribute"));
        }
        if (b.get("owner") instanceof String o && !o.equals(meta.get("owner"))) {
            String role = cr.lockerRole(by, l);
            if (!"owner".equals(role) && !"manager".equals(role)) throw denied("hand this credential to someone else");
            if (db.one("SELECT id FROM users WHERE username = ?", o) == null) throw notFound("User " + o);
            db.update("UPDATE credential_meta SET owner = ? WHERE locker = ? AND name = ?", o, locker, name);
        }
        cr.audit.record(by.username(), "credential.save", "credential", locker + "/" + name, Map.of("changed", new ArrayList<>(b.keySet())), "ok", ip);
    }

    /** Deletes a whole credential: every attribute, everyone's own values and its details. */
    public void deleteCredential(User by, String locker, String name, String ip) {
        Map<String, Object> l = locker(locker);
        if (!cr.canChangeCredential(by, l, name)) throw denied("delete this credential");
        int n = db.tx(() -> {
            int k = db.update("DELETE FROM credentials WHERE locker = ? AND name = ?", locker, name);
            k += db.update("DELETE FROM credential_user_values WHERE locker = ? AND name = ?", locker, name);
            k += db.update("DELETE FROM credential_meta WHERE locker = ? AND name = ?", locker, name);
            return k;
        });
        if (n == 0) throw notFound("Credential " + locker + "/" + name);
        cr.audit.record(by.username(), "credential.delete_all", "credential", locker + "/" + name, null, "ok", ip);
    }

    /** Deletes an empty vault folder (delete its credentials first). Owners or vault administrators. */
    public void deleteLocker(User by, String locker, String ip) {
        Map<String, Object> l = locker(locker);
        if (!cr.ownsLocker(by, l)) throw denied("delete this vault folder");
        if (cr.count("SELECT COUNT(*) AS n FROM credentials WHERE locker = ?", locker) + cr.count("SELECT COUNT(*) AS n FROM credential_meta WHERE locker = ?", locker) > 0)
            throw new OoaException("CR.CONFLICT", "The vault folder still has credentials", "Delete them first, so no secret is removed by accident.");
        db.tx(() -> { db.update("DELETE FROM credential_user_values WHERE locker = ?", locker); db.update("DELETE FROM lockers WHERE name = ?", locker); return null; });
        cr.audit.record(by.username(), "locker.delete", "locker", locker, null, "ok", ip);
    }

    /**
     * Sets a folder's members: owners (people), managers, participants and consumers (people, roles or teams). Only
     * owners; at least one owner stays.
     */
    public void setMembers(User by, String locker, Map<String, Object> b, String ip) {
        Map<String, Object> l = locker(locker);
        if (!cr.ownsLocker(by, l)) throw denied("change this vault folder's members");
        Map<String, Object> changes = new LinkedHashMap<>();
        if (b.containsKey("owners")) {
            List<String> o = csv(b.get("owners"));
            if (o.isEmpty()) throw new OoaException("CR.BAD_INPUT", "A vault folder needs at least one owner");
            for (String u : o) if (db.one("SELECT id FROM users WHERE username = ?", u) == null) throw notFound("User " + u);
            db.update("UPDATE lockers SET owners = ? WHERE name = ?", String.join(",", o), locker);
            changes.put("owners", o);
        }
        for (String k : List.of("managers", "participants", "consumers")) {
            if (!b.containsKey(k)) continue;
            List<String> v = csv(b.get(k));
            for (String p : v) cr.teams.checkPrincipal(p);
            String joined = String.join(",", v);
            switch (k) {
                case "managers" -> db.update("UPDATE lockers SET managers = ? WHERE name = ?", joined, locker);
                case "participants" -> db.update("UPDATE lockers SET participants = ? WHERE name = ?", joined, locker);
                default -> db.update("UPDATE lockers SET consumers = ? WHERE name = ?", joined, locker);
            }
            changes.put(k, v);
        }
        cr.audit.record(by.username(), "locker.members", "locker", locker, changes, "ok", ip);
    }

    /** Stores the caller's own value of a user-provided attribute (they must be able to use the folder). */
    public void setMyValue(User by, String locker, String name, String attribute, String value, String ip) {
        Map<String, Object> l = locker(locker);
        if (cr.lockerRole(by, l) == null) throw denied("use this vault folder");
        if (!userProvided(locker, name, attribute)) throw new OoaException("CR.BAD_INPUT", "'" + attribute + "' is not a user-provided attribute of " + locker + "/" + name);
        if (value == null || value.isEmpty()) throw new OoaException("CR.BAD_INPUT", "The value is empty");
        synchronized (cr.vault) {
            String ct = cr.vault.encrypt(value, userBound(locker + "/" + name + "/" + attribute, by.username()));
            db.tx(() -> {
                if (db.update("UPDATE credential_user_values SET ciphertext = ?, updated = ? WHERE locker = ? AND name = ? AND attribute = ? AND username = ?", ct, now(), locker, name, attribute, by.username()) == 0)
                    db.update("INSERT INTO credential_user_values (locker, name, attribute, username, ciphertext, updated) VALUES (?,?,?,?,?,?)", locker, name, attribute, by.username(), ct, now());
                return null;
            });
        }
        cr.audit.record(by.username(), "credential.my_value", "credential", locker + "/" + name + "/" + attribute, null, "ok", ip);
    }

    public void deleteMyValue(User by, String locker, String name, String attribute, String ip) {
        if (db.update("DELETE FROM credential_user_values WHERE locker = ? AND name = ? AND attribute = ? AND username = ?", locker, name, attribute, by.username()) == 0) throw notFound("Your value");
        cr.audit.record(by.username(), "credential.my_value_delete", "credential", locker + "/" + name + "/" + attribute, null, "ok", ip);
    }

    /** Where the master key comes from, its fingerprint, and how much the vault holds. Vault administrators. */
    public Map<String, Object> status(User by) {
        cr.require(by, "lockers.manage", "administer the vault");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key_source", cr.vault.keyFile == null ? "environment" : "file");
        m.put("key_fingerprint", cr.vault.fingerprint());
        m.put("can_rotate", cr.vault.keyFile != null);
        m.put("vault_folders", cr.count("SELECT COUNT(*) AS n FROM lockers"));
        m.put("values", cr.count("SELECT COUNT(*) AS n FROM credentials"));
        m.put("personal_values", cr.count("SELECT COUNT(*) AS n FROM credential_user_values"));
        m.put("external_links", cr.count("SELECT COUNT(*) AS n FROM lockers WHERE external IS NOT NULL"));
        return m;
    }

    /**
     * Rotates the master key: every stored value (shared values, people's own values, external vault sign-in secrets)
     * is decrypted with the current key and encrypted with a new one in one transaction. The new key is written next to
     * the old one first (master.key.next), then replaces it; the old key is kept as master.key.old-&lt;time&gt; until an
     * administrator removes it. With OOA_MASTER_KEY the key cannot be changed here.
     */
    public Map<String, Object> rotate(User by, String ip) {
        cr.require(by, "lockers.manage", "administer the vault");
        Path file = cr.vault.keyFile;
        if (file == null) throw new OoaException("CR.VAULT_KEY", "The master key comes from OOA_MASTER_KEY, so it cannot be changed here",
                "Rotate it where OOA_MASTER_KEY is managed, with a planned restart.");
        Vault next = Vault.fresh();
        Path nextFile = file.resolveSibling("master.key.next");
        try { Files.writeString(nextFile, next.keyBase64()); } catch (IOException e) { throw new OoaException("CR.VAULT_KEY", "Cannot write the new key: " + e.getMessage()); }
        int n;
        synchronized (cr.vault) {
            n = db.tx(() -> {
                int k = 0;
                for (Map<String, Object> c : db.query("SELECT locker, name, attribute, ciphertext FROM credentials")) {
                    String ref = c.get("locker") + "/" + c.get("name") + "/" + c.get("attribute");
                    db.update("UPDATE credentials SET ciphertext = ? WHERE locker = ? AND name = ? AND attribute = ?",
                            next.encrypt(cr.vault.decrypt((String) c.get("ciphertext"), ref), ref), c.get("locker"), c.get("name"), c.get("attribute"));
                    k++;
                }
                for (Map<String, Object> c : db.query("SELECT locker, name, attribute, username, ciphertext FROM credential_user_values")) {
                    String bound = userBound(c.get("locker") + "/" + c.get("name") + "/" + c.get("attribute"), (String) c.get("username"));
                    db.update("UPDATE credential_user_values SET ciphertext = ? WHERE locker = ? AND name = ? AND attribute = ? AND username = ?",
                            next.encrypt(cr.vault.decrypt((String) c.get("ciphertext"), bound), bound), c.get("locker"), c.get("name"), c.get("attribute"), c.get("username"));
                    k++;
                }
                for (Map<String, Object> l : db.query("SELECT name, external_secret FROM lockers WHERE external_secret IS NOT NULL")) {
                    String bound = "external:" + l.get("name");
                    db.update("UPDATE lockers SET external_secret = ? WHERE name = ?", next.encrypt(cr.vault.decrypt((String) l.get("external_secret"), bound), bound), l.get("name"));
                    k++;
                }
                return k;
            });
            String old = cr.vault.fingerprint();
            try {
                Files.move(file, file.resolveSibling("master.key.old-" + now().toString().replace(":", "")), StandardCopyOption.ATOMIC_MOVE);
                Files.move(nextFile, file, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                cr.vault.adopt(next);
                cr.audit.record(by.username(), "vault.rotate", "vault", null, Map.of("values", n, "error", String.valueOf(e.getMessage())), "failed", ip);
                throw new OoaException("CR.VAULT_KEY", "The values were re-encrypted, but the key file could not be replaced: " + e.getMessage(),
                        "Copy " + nextFile + " over " + file + " before the next restart.");
            }
            cr.vault.adopt(next);
            cr.audit.record(by.username(), "vault.rotate", "vault", null, Map.of("values", n, "old_key", old, "new_key", cr.vault.fingerprint()), "ok", ip);
        }
        return Map.of("re_encrypted", n, "key_fingerprint", cr.vault.fingerprint());
    }
}
