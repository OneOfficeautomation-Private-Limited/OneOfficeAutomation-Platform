package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.sdk.JsonLite;
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

    /**
     * Per attribute: description, masked (the value is never shown, the default) and password_only (agents may type it
     * into password fields only).
     */
    @SuppressWarnings("unchecked")
    Map<String, Map<String, Object>> attributesInfo(String locker, String name) {
        Map<String, Object> m = db.one("SELECT attributes_info FROM credential_meta WHERE locker = ? AND name = ?", locker, name);
        if (m == null || m.get("attributes_info") == null) return new LinkedHashMap<>();
        Object v = JsonLite.parse((String) m.get("attributes_info"));
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        if (v instanceof Map<?, ?> all) all.forEach((k, x) -> { if (x instanceof Map<?, ?> xm) out.put(String.valueOf(k), new LinkedHashMap<>((Map<String, Object>) xm)); });
        return out;
    }

    boolean passwordOnly(String ref) {
        String[] p = ref == null ? new String[0] : ref.split("/");
        if (p.length != 3) return false;
        Map<String, Object> a = attributesInfo(p[0], p[1]).get(p[2]);
        return a != null && Boolean.TRUE.equals(a.get("password_only"));
    }

    /**
     * Credential requests: user-provided attributes of vault folders the caller may use that they have not filled in
     * yet. Agents that run as them need these values.
     */
    public List<Map<String, Object>> requests(User by) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> l : db.query("SELECT * FROM lockers ORDER BY name")) {
            if (l.get("external") != null || cr.lockerRole(by, l) == null) continue;
            String locker = (String) l.get("name");
            for (Map<String, Object> m : db.query("SELECT name, description, user_attributes, owner FROM credential_meta WHERE locker = ? ORDER BY name", locker)) {
                List<String> ua = csv(m.get("user_attributes"));
                if (ua.isEmpty()) continue;
                Set<String> mine = new HashSet<>();
                for (Map<String, Object> v : db.query("SELECT attribute FROM credential_user_values WHERE locker = ? AND name = ? AND username = ?", locker, m.get("name"), by.username()))
                    mine.add((String) v.get("attribute"));
                Map<String, Map<String, Object>> info = attributesInfo(locker, (String) m.get("name"));
                for (String a : ua) {
                    if (mine.contains(a)) continue;
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("locker", locker); r.put("name", m.get("name")); r.put("attribute", a);
                    r.put("credential_description", m.get("description"));
                    r.put("description", info.getOrDefault(a, Map.of()).get("description"));
                    r.put("password_only", Boolean.TRUE.equals(info.getOrDefault(a, Map.of()).get("password_only")));
                    r.put("owner", m.get("owner"));
                    out.add(r);
                }
            }
        }
        return out;
    }

    /** A shared value whose attribute is not masked, for the people who may change the credential. */
    public Map<String, Object> unmaskedValue(User by, String locker, String name, String attribute, String ip) {
        Map<String, Object> l = locker(locker);
        if (!cr.canChangeCredential(by, l, name)) throw denied("see this credential's values");
        Map<String, Object> a = attributesInfo(locker, name).get(attribute);
        if (a == null || !Boolean.FALSE.equals(a.get("masked"))) throw new OoaException("CR.FORBIDDEN", "This value is masked: it is never shown", "Clear Masked on the attribute if it is not a secret (for example a user name).");
        if (l.get("external") != null) throw new OoaException("CR.BAD_INPUT", "Values of an external vault are not shown here");
        Map<String, Object> c = db.one("SELECT ciphertext FROM credentials WHERE locker = ? AND name = ? AND attribute = ?", locker, name, attribute);
        if (c == null) throw notFound("Value of " + attribute);
        cr.audit.record(by.username(), "credential.view", "credential", locker + "/" + name + "/" + attribute, null, "ok", ip);
        return Map.of("value", cr.vault.decrypt((String) c.get("ciphertext"), locker + "/" + name + "/" + attribute));
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
        for (String ua : userAttrs) if (attrs.stream().noneMatch(a -> ua.equals(a.get("attribute")))) list.add(new LinkedHashMap<>(Map.of("attribute", ua, "user_provided", true)));
        Map<String, Map<String, Object>> info = attributesInfo(locker, name);
        for (Object o : list) {
            @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) o;
            Map<String, Object> i = info.getOrDefault((String) m.get("attribute"), Map.of());
            m.put("description", i.get("description"));
            m.put("masked", !Boolean.FALSE.equals(i.get("masked")));
            m.put("password_only", Boolean.TRUE.equals(i.get("password_only")));
        }
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
        if (b.get("attributes_info") instanceof Map<?, ?> ai) {
            Map<String, Object> clean = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ai.entrySet()) {
                String a = String.valueOf(e.getKey());
                if (!NAME.matcher(a).matches()) throw new OoaException("CR.BAD_INPUT", "Attribute names use letters, digits, . _ -");
                if (!(e.getValue() instanceof Map<?, ?> v)) continue;
                Map<String, Object> x = new LinkedHashMap<>();
                if (v.get("description") instanceof String d && !d.isBlank()) {
                    if (d.length() > 300) throw new OoaException("CR.BAD_INPUT", "An attribute description is at most 300 characters");
                    x.put("description", d.strip());
                }
                x.put("masked", !Boolean.FALSE.equals(v.get("masked")));
                x.put("password_only", Boolean.TRUE.equals(v.get("password_only")));
                clean.put(a, x);
            }
            db.update("UPDATE credential_meta SET attributes_info = ? WHERE locker = ? AND name = ?", JsonLite.write(clean), locker, name);
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
        m.put("key_source", cr.vault.keyFile != null ? "file" : cr.vault.keySource.startsWith("environment") ? "environment" : "external");
        m.put("key_source_detail", cr.vault.keySource);
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
    /**
     * Re-encrypts the enc: values of a properties file with the next key (bound to prefix + key name, as DbConfig and
     * TlsConfig bind them; the TLS file binds its password as ooa-tls:password). Returns how many it changed.
     */
    private int reencryptConfig(Path file, String prefix, Vault next) {
        if (!Files.exists(file)) return 0;
        try {
            Files.copy(file, file.resolveSibling(file.getFileName() + ".before-rotate"), StandardCopyOption.REPLACE_EXISTING);
            Properties p = new Properties();
            try (var r = Files.newBufferedReader(file, java.nio.charset.StandardCharsets.UTF_8)) { p.load(r); }
            int k = 0;
            for (String name : p.stringPropertyNames()) {
                String v = p.getProperty(name).trim();
                if (!v.startsWith("enc:")) continue;
                String bound = prefix + name;
                DbConfig.setValue(file, name, "enc:" + next.encrypt(cr.vault.decrypt(v.substring(4), bound), bound));
                k++;
            }
            return k;
        } catch (IOException e) { throw new OoaException("CR.VAULT_KEY", "Cannot re-encrypt " + file + ": " + e.getMessage()); }
    }

    public Map<String, Object> rotate(User by, String ip) {
        cr.require(by, "lockers.manage", "administer the vault");
        Path file = cr.vault.keyFile;
        if (file == null) throw new OoaException("CR.VAULT_KEY", "The master key comes from " + cr.vault.keySource + ", so it cannot be changed here",
                "Stop the Command Center, run: ooa vault rekey --new-key-env VAR (re-encrypts every value with the new key), put the new key where it is kept, and start again.");
        Vault next = Vault.fresh();
        Path nextFile = file.resolveSibling("master.key.next");
        try { Files.writeString(nextFile, next.keyBase64()); } catch (IOException e) { throw new OoaException("CR.VAULT_KEY", "Cannot write the new key: " + e.getMessage()); }
        int n;
        synchronized (cr.vault) {
            n = reencryptAll(next);
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

    /**
     * Re-encrypts everything with a key the administrator brings (Base64, 32 bytes), for a master key kept outside the
     * data folder (OOA_MASTER_KEY or a key store), where the console cannot change it. Run with the Command Center
     * stopped (ooa vault rekey); afterwards the new key must replace the old one where it is kept before the next start.
     * With a key file, the new key is written to it and the old one kept as master.key.old-&lt;time&gt;.
     */
    public Map<String, Object> rekey(byte[] newKey) {
        Vault next = Vault.ofKey(newKey, "new key");
        String old = cr.vault.fingerprint();
        if (old.equals(next.fingerprint())) throw new OoaException("CR.VAULT_KEY", "The new key is the key in use", "Make a new random key, e.g. 32 random bytes in Base64.");
        int n;
        synchronized (cr.vault) {
            n = reencryptAll(next);
            Path file = cr.vault.keyFile;
            if (file != null) {
                try {
                    Files.copy(file, file.resolveSibling("master.key.old-" + now().toString().replace(":", "")));
                    Files.writeString(file, next.keyBase64());
                } catch (IOException e) {
                    cr.vault.adopt(next);
                    throw new OoaException("CR.VAULT_KEY", "The values were re-encrypted, but the key file could not be written: " + e.getMessage(),
                            "Put the new key into " + file + " before the next start.");
                }
            }
            cr.vault.adopt(next);
            cr.audit.record("ooa vault rekey", "vault.rekey", "vault", null, Map.of("values", n, "old_key", old, "new_key", cr.vault.fingerprint()), "ok", null);
        }
        return Map.of("re_encrypted", n, "key_fingerprint", cr.vault.fingerprint(), "key_file", cr.vault.keyFile != null);
    }

    /** Decrypts every stored value with the current key and encrypts it with next (database in one transaction, then the config files). */
    private int reencryptAll(Vault next) {
        int n = db.tx(() -> {
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
            // values the Command Center keeps for itself: evaluation start, install id, audit chain key
            for (String[] s : new String[][] { {"evaluation_started", "evaluation"}, {"install_id", "install-id"}, {"audit_chain_key", "audit-chain"} }) {
                Map<String, Object> r = db.one("SELECT json_value FROM settings WHERE name = ?", s[0]);
                if (r == null) continue;
                String plain = cr.vault.decrypt(String.valueOf(com.oneofficeautomation.sdk.JsonLite.parse((String) r.get("json_value"))), s[1]);
                db.update("UPDATE settings SET json_value = ? WHERE name = ?", com.oneofficeautomation.sdk.JsonLite.write(next.encrypt(plain, s[1])), s[0]);
                k++;
            }
            // two-step sign-in secrets
            for (Map<String, Object> u : db.query("SELECT id, mfa_secret FROM users WHERE mfa_secret IS NOT NULL")) {
                String bound = "mfa:" + u.get("id");
                db.update("UPDATE users SET mfa_secret = ? WHERE id = ?", next.encrypt(cr.vault.decrypt((String) u.get("mfa_secret"), bound), bound), u.get("id"));
                k++;
            }
            return k;
        });
        // passwords in the configuration files (database, HTTPS certificate): each file is kept as .before-rotate first
        n += reencryptConfig(DbConfig.defaultFile(cr.dataDir), "ooa-db:", next);
        n += reencryptConfig(TlsConfig.defaultFile(cr.dataDir), "ooa-tls:", next);
        return n;
    }
}
