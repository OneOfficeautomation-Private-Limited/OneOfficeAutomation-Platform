package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.controlroom.ControlRoom.User;
import com.oneofficeautomation.sdk.OoaException;
import com.oneofficeautomation.sdk.PackageRegistry;
import com.oneofficeautomation.sdk.PackageSigning;
import com.oneofficeautomation.sdk.ToolkitJar;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static com.oneofficeautomation.controlroom.ControlRoom.*;

/**
 * Custom toolkits (K6): jars a customer's developers build with the SDK and sign with their publisher key. Admins trust
 * publishers by their public key; an upload is checked (signature, digest, id and version) and loaded in a class loader of
 * its own, so several versions of a toolkit are installed side by side and agents pick one by their version range. The jar
 * is kept with the repository files (the shared folder in a cluster) and sent with each run that uses it; the runner checks
 * the hash and the signature again before loading it.
 */
public final class CustomToolkits {
    static final int MAX_BYTES = 10 * 1024 * 1024;
    private final ControlRoom cr;
    private final Db db;
    private final PackageRegistry registry;
    /** Toolkit ids that come with the product; a custom toolkit may not take one. */
    private final Set<String> firstParty = new HashSet<>();

    CustomToolkits(ControlRoom cr, PackageRegistry registry) {
        this.cr = cr; this.db = cr.db; this.registry = registry;
        for (Map<String, Object> m : registry.allManifests()) firstParty.add(String.valueOf(m.get("id")));
    }

    private Path file(String sha) { return cr.repoFiles.store().resolve("toolkits").resolve(sha + ".jar"); }

    // ---------- trusted publishers ----------

    public List<Map<String, Object>> publishers(User by) {
        cr.require(by, "toolkits.manage", "manage toolkits");
        return db.query("SELECT publisher, public_key, added_by, added FROM toolkit_publishers ORDER BY publisher");
    }

    public void addPublisher(User by, String publisher, String publicKey, String ip) {
        cr.require(by, "toolkits.manage", "manage toolkits");
        String p = publisher == null ? "" : publisher.trim();
        if (!p.matches("^[A-Za-z0-9][A-Za-z0-9 ._-]{0,79}$")) throw new OoaException("CR.BAD_INPUT", "Give the publisher's name (letters, digits, space . _ -)");
        if (p.equalsIgnoreCase("oneofficeautomation")) throw new OoaException("CR.BAD_INPUT", "That publisher name is reserved for the product's own toolkits");
        String key = ToolkitJar.encode(ToolkitJar.publicKey(publicKey == null ? "" : publicKey));
        if (db.update("UPDATE toolkit_publishers SET public_key = ?, added_by = ?, added = ? WHERE publisher = ?", key, by.username(), now(), p) == 0)
            db.update("INSERT INTO toolkit_publishers (publisher, public_key, added_by, added) VALUES (?,?,?,?)", p, key, by.username(), now());
        cr.audit.record(by.username(), "toolkit.publisher.trust", "publisher", p, null, "ok", ip);
    }

    public void removePublisher(User by, String publisher, String ip) {
        cr.require(by, "toolkits.manage", "manage toolkits");
        Number n = (Number) db.one("SELECT COUNT(*) AS n FROM custom_toolkits WHERE publisher = ?", publisher).get("n");
        if (n.intValue() > 0) throw new OoaException("CR.CONFLICT", publisher + " has " + n + " toolkit version(s) here", "Delete them first; toolkits stay trusted while they are installed.");
        if (db.update("DELETE FROM toolkit_publishers WHERE publisher = ?", publisher) == 0) throw notFound("Publisher");
        cr.audit.record(by.username(), "toolkit.publisher.remove", "publisher", publisher, null, "ok", ip);
    }

    private PackageSigning.PublisherKey keyOf(String publisher) {
        Map<String, Object> r = db.one("SELECT public_key FROM toolkit_publishers WHERE publisher = ?", publisher);
        if (r == null) throw new OoaException("PKG.UNTRUSTED", "The publisher '" + publisher + "' is not trusted here", "Add the publisher's public key in Toolkits > Publishers first.");
        return new PackageSigning.PublisherKey(publisher, null, ToolkitJar.publicKey((String) r.get("public_key")));
    }

    // ---------- toolkit versions ----------

    public List<Map<String, Object>> list(User by) {
        cr.require(by, "repo.view", "view toolkits");
        return db.query("SELECT id, toolkit_id, version, name, publisher, sha256, size_bytes, uploaded_by, uploaded FROM custom_toolkits ORDER BY toolkit_id, uploaded");
    }

    /** Whether a toolkit id is a custom one (some version uploaded here). */
    boolean isCustom(String pid) { return db.one("SELECT id FROM custom_toolkits WHERE toolkit_id = ?", pid) != null; }

    public Map<String, Object> upload(User by, byte[] jar, String ip) {
        cr.require(by, "toolkits.manage", "manage toolkits");
        // loading a toolkit runs its code in the server, which SaaS tenants share
        if (cr.tenant != null) throw ControlRoom.notInSaas("Uploading custom toolkits");
        if (jar == null || jar.length == 0) throw new OoaException("CR.BAD_INPUT", "Send the toolkit's jar file");
        if (jar.length > MAX_BYTES) throw new OoaException("CR.TOO_LARGE", "Toolkit jars are at most 10 MB");
        String sha = ToolkitJar.sha256(jar);
        Map<String, Object> d = ToolkitJar.descriptor(jar);
        PackageSigning.PublisherKey key = keyOf(String.valueOf(d.get("publisher")));
        Path f = file(sha);
        try {
            Files.createDirectories(f.getParent());
            if (!Files.exists(f)) Files.write(f, jar);
        } catch (IOException e) { throw new OoaException("CR.STORAGE", "Cannot keep the jar: " + e.getMessage()); }
        PackageRegistry.LoadedPackage lp;
        try {
            ToolkitJar.Loaded l = check(f, key);
            var meta = com.oneofficeautomation.sdk.PackageIntrospector.inspect(l.cls()).meta();
            Map<String, Object> same = db.one("SELECT id, sha256 FROM custom_toolkits WHERE toolkit_id = ? AND version = ?", meta.id(), meta.version());
            if (same != null) {
                if (same.get("sha256").equals(sha)) return Map.of("id", same.get("id"), "toolkit_id", meta.id(), "version", meta.version(), "unchanged", true);
                throw new OoaException("CR.CONFLICT", meta.id() + " " + meta.version() + " is already installed with different contents",
                    "Give the new build a new version number; agents that pin " + meta.version() + " keep the one they were tested with.");
            }
            registry.trust(key);
            lp = registry.install(l.cls(), l.signature());
        } catch (OoaException e) { deleteIfUnused(sha); throw e; }
        String pid = lp.id(), ver = lp.version();
        String id = id("tk");
        db.update("INSERT INTO custom_toolkits (id, toolkit_id, version, name, publisher, sha256, size_bytes, uploaded_by, uploaded) VALUES (?,?,?,?,?,?,?,?,?)",
            id, pid, ver, lp.manifest().get("name"), key.publisher(), sha, jar.length, by.username(), now());
        cr.audit.record(by.username(), "toolkit.upload", "toolkit", pid + "@" + ver, Map.of("sha256", sha, "publisher", key.publisher()), "ok", ip);
        return Map.of("id", id, "toolkit_id", pid, "version", ver, "versions", registry.versions(pid));
    }

    /** Loads a stored jar and checks its publisher and toolkit id; the registry checks the signature when it installs it. */
    private ToolkitJar.Loaded check(Path jar, PackageSigning.PublisherKey key) {
        ToolkitJar.Loaded l = ToolkitJar.load(jar, CustomToolkits.class.getClassLoader());
        if (l.signature() == null || !key.publisher().equals(l.signature().publisher()))
            throw new OoaException("PKG.UNTRUSTED", "The jar is not signed by a trusted publisher");
        String pid = com.oneofficeautomation.sdk.PackageIntrospector.inspect(l.cls()).meta().id();
        if (firstParty.contains(pid)) throw new OoaException("CR.CONFLICT", "The toolkit id " + pid + " belongs to a toolkit that comes with the product", "Give your toolkit its own id, e.g. com.yourcompany.billing.");
        Map<String, Object> other = db.one("SELECT publisher FROM custom_toolkits WHERE toolkit_id = ? AND publisher <> ?", pid, key.publisher());
        if (other != null) throw new OoaException("CR.CONFLICT", "The toolkit id " + pid + " is published here by " + other.get("publisher"), "One toolkit id has one publisher.");
        return l;
    }

    /** Loads every stored toolkit when the Command Center starts; one that no longer checks out is reported and left out. */
    void loadAll() {
        if (cr.tenant != null) return;
        for (Map<String, Object> r : db.query("SELECT toolkit_id, version, publisher, sha256 FROM custom_toolkits")) {
            try {
                Path f = file((String) r.get("sha256"));
                if (!ToolkitJar.sha256(Files.readAllBytes(f)).equals(r.get("sha256"))) throw new OoaException("PKG.TAMPERED", "The stored jar was changed");
                PackageSigning.PublisherKey key = keyOf((String) r.get("publisher"));
                ToolkitJar.Loaded l = check(f, key);
                registry.trust(key);
                registry.install(l.cls(), l.signature());
            } catch (IOException | RuntimeException e) {
                cr.audit.record("system", "toolkit.load", "toolkit", r.get("toolkit_id") + "@" + r.get("version"), String.valueOf(e.getMessage()), "failed", null);
            }
        }
    }

    public void delete(User by, String id, String ip) {
        cr.require(by, "toolkits.manage", "manage toolkits");
        Map<String, Object> r = db.one("SELECT * FROM custom_toolkits WHERE id = ?", id);
        if (r == null) throw notFound("Toolkit version");
        String pid = (String) r.get("toolkit_id"), ver = (String) r.get("version");
        if (registry.versions(pid).size() <= 1 && !cr.platform.toolkitUsers(by, pid).isEmpty())
            throw new OoaException("CR.CONFLICT", "Agents use " + pid + " and this is its only version", "Change those agents first (Toolkits > Used by).");
        registry.uninstall(pid, ver);
        db.update("DELETE FROM custom_toolkits WHERE id = ?", id);
        deleteIfUnused((String) r.get("sha256"));
        cr.audit.record(by.username(), "toolkit.delete", "toolkit", pid + "@" + ver, null, "ok", ip);
    }

    private void deleteIfUnused(String sha) {
        if (db.one("SELECT id FROM custom_toolkits WHERE sha256 = ?", sha) != null) return;
        try { Files.deleteIfExists(file(sha)); } catch (IOException ignored) { /* left for the next upload */ }
    }

    /** The custom toolkit versions the agent and the agents it calls resolve to, with their jars, for the runner. */
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> forJob(Map<String, Object> main, Map<String, Object> bundle) {
        List<Map<String, Object>> flows = new ArrayList<>(List.of(main));
        for (Object b : bundle.values()) if (b instanceof Map<?, ?> m) flows.add((Map<String, Object>) m);
        Set<String> wanted = new LinkedHashSet<>();
        for (Map<String, Object> f : flows) {
            Map<String, Object> req = f.get("requires") instanceof Map<?, ?> r && r.get("packages") instanceof Map<?, ?> pk ? (Map<String, Object>) pk : Map.of();
            for (Map<String, Object> s : com.oneofficeautomation.runner.Steps.walk((List<Object>) f.getOrDefault("steps", List.of()))) {
                String a = String.valueOf(s.get("action"));
                if (!a.contains("/")) continue;
                String pid = a.substring(0, a.indexOf('/'));
                if (firstParty.contains(pid)) continue;
                try { wanted.add(pid + "@" + registry.resolve(pid, (String) req.get(pid)).version()); } catch (OoaException e) { /* the run reports it */ }
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (String w : wanted) {
            String pid = w.substring(0, w.indexOf('@')), ver = w.substring(w.indexOf('@') + 1);
            Map<String, Object> r = db.one("SELECT t.publisher, t.sha256, p.public_key FROM custom_toolkits t JOIN toolkit_publishers p ON p.publisher = t.publisher WHERE t.toolkit_id = ? AND t.version = ?", pid, ver);
            if (r == null) continue;
            try {
                out.add(Map.of("id", pid, "version", ver, "publisher", r.get("publisher"), "public_key", r.get("public_key"), "sha256", r.get("sha256"),
                    "jar", Base64.getEncoder().encodeToString(Files.readAllBytes(file((String) r.get("sha256"))))));
            } catch (IOException e) { throw new OoaException("RUN.TOOLKIT_MISSING", "The jar of " + w + " cannot be read on the Command Center: " + e.getMessage()); }
        }
        return out;
    }
}
