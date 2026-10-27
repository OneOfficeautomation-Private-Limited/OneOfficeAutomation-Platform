package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.runner.FlowSchemaValidator;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;
import com.oneofficeautomation.sdk.PackageRegistry;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The tenants of one SaaS region (C3): each customer has its own schema in the region's shared PostgreSQL database,
 * its own data folder under DATA/tenants/ID and its own vault key, kept in the catalog encrypted with the region's
 * master key. Nothing is shared between tenants but the server process and the database server, so the Command
 * Center code stays as it is and one tenant's data cannot reach another's through a missed filter.
 * <p>
 * The catalog (schema ooa_catalog) lists each tenant: id, address name (slug: acme in acme.in.example.com), name,
 * status (active, suspended), its seats, its admin's e-mail address. A tenant's Command Center is opened when the
 * region starts (so its schedules and triggers run) or on first use, and handed requests by {@link TenantHost}.
 */
public final class Tenants implements AutoCloseable {

    /** An address name: lower-case letters, digits and hyphens, 3 to 40 characters, starting with a letter. */
    static final Pattern SLUG = Pattern.compile("^[a-z][a-z0-9-]{1,38}[a-z0-9]$");
    /** Names a tenant may not take: they would look like the region's own addresses. */
    static final Set<String> RESERVED = Set.of("www", "api", "admin", "app", "ooa", "status", "mail", "login", "auth", "support", "docs", "static", "region");

    public record Info(String id, String slug, String name, String status, String region, String adminEmail, Map<String, Object> seats, String created) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id); m.put("slug", slug); m.put("name", name); m.put("status", status); m.put("region", region);
            m.put("admin_email", adminEmail); m.put("seats", seats); m.put("created", created);
            return m;
        }
    }

    /** An open tenant: its Command Center and the server that answers its requests. */
    public static final class Tenant {
        public final Info info;
        public final ControlRoom cr;
        final ControlRoomServer server;
        volatile long lastUsed = System.currentTimeMillis();
        Tenant(Info info, ControlRoom cr, ControlRoomServer server) { this.info = info; this.cr = cr; this.server = server; }
    }

    final Db base, catalog;
    final Vault regionVault;
    final Path root;
    public final String region;
    /** The region's address: tenant acme is reached at acme.DOMAIN (TenantHost). */
    public final String domain;
    private final Supplier<PackageRegistry> registries;
    private final FlowSchemaValidator schema;
    private final Map<String, Tenant> open = new ConcurrentHashMap<>();
    /** Whether tenants may run agents in the Command Center (serverless API tasks): off unless the region's network allows it. */
    public volatile boolean serverless = false;
    /** The global directory (several regions): a name is reserved there first, so it is used in one region only. */
    public volatile RegionDirectory.Client directory;

    /**
     * @param base       the region's database (PostgreSQL; H2 in tests), signed in with an account that may create schemas
     * @param root       the region's data folder: tenants' folders go under tenants/
     * @param regionVault the region's master key, which encrypts each tenant's own key
     * @param domain     the region's address, such as in.example.com: tenant acme is acme.in.example.com
     * @param registries a toolkit registry for each tenant (its custom toolkits stay its own)
     */
    public Tenants(Db base, Path root, Vault regionVault, String region, String domain, Supplier<PackageRegistry> registries, FlowSchemaValidator schema) {
        this.base = base; this.root = root; this.regionVault = regionVault; this.region = region; this.domain = domain.toLowerCase(Locale.ROOT);
        this.registries = registries; this.schema = schema;
        this.catalog = base.inSchema("ooa_catalog", 4);
        catalog.update("CREATE TABLE IF NOT EXISTS tenants (id VARCHAR(40) PRIMARY KEY, slug VARCHAR(40) NOT NULL UNIQUE, name VARCHAR(200) NOT NULL, "
            + "status VARCHAR(20) NOT NULL, region VARCHAR(20) NOT NULL, admin_email VARCHAR(200), seats VARCHAR(2000) NOT NULL, key_ciphertext VARCHAR(500) NOT NULL, "
            + "created TIMESTAMP NOT NULL, updated TIMESTAMP NOT NULL, updated_by VARCHAR(80))");
    }

    static String schemaOf(String id) { return "t_" + id; }

    Path folderOf(String id) { return root.resolve("tenants").resolve(id); }

    /**
     * Makes a tenant: its schema with the Command Center tables, its folder, its key, and the first administrator.
     * Returns the tenant and that administrator's one-time password (shown once, as on a new install).
     */
    public Map<String, Object> create(String slug, String name, String adminEmail, Map<String, Object> seats, String by) {
        slug = slug == null ? "" : slug.trim().toLowerCase(Locale.ROOT);
        if (!SLUG.matcher(slug).matches() || RESERVED.contains(slug))
            throw new OoaException("SAAS.BAD_SLUG", "'" + slug + "' cannot be a tenant's address name", "Use 3 to 40 lower-case letters, digits and hyphens, starting with a letter (not www, api, admin, ...).");
        if (name == null || name.isBlank()) throw new OoaException("SAAS.BAD_NAME", "Give the tenant a name");
        if (catalog.one("SELECT id FROM tenants WHERE slug = ?", slug) != null) throw new OoaException("SAAS.SLUG_TAKEN", "The address name '" + slug + "' is taken");
        RegionDirectory.Client dir = directory;
        if (dir != null) dir.reserve(slug);
        String id = Security.token(8).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "x");
        byte[] key = new byte[32];
        Security.RANDOM.nextBytes(key);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        String password;
        try {
            catalog.update("INSERT INTO tenants (id, slug, name, status, region, admin_email, seats, key_ciphertext, created, updated, updated_by) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                id, slug, name.trim(), "creating", region, adminEmail, JsonLite.write(seatCounts(seats)),
                regionVault.encrypt(Base64.getEncoder().encodeToString(key), "tenant:" + id), now, now, by);
            Tenant t = start(info(catalog.one("SELECT * FROM tenants WHERE id = ?", id)));
            password = t.cr.bootstrap();
            catalog.update("UPDATE tenants SET status = 'active', updated = ? WHERE id = ?", now, id);
        } catch (RuntimeException e) {
            // nothing half-made stays behind: no schema, folder, entry or reserved name
            Tenant t = open.remove(id);
            if (t != null) stop(t);
            try { base.dropSchema(schemaOf(id)); } catch (RuntimeException ignored) { }
            catalog.update("DELETE FROM tenants WHERE id = ?", id);
            if (dir != null) try { dir.release(slug); } catch (RuntimeException ignored) { }
            throw e;
        }
        Map<String, Object> out = new LinkedHashMap<>(get(slug).toMap());
        out.put("url", "https://" + slug + "." + domain + "/");
        out.put("admin_user", "admin");
        out.put("admin_password", password);
        return out;
    }

    public List<Info> list() {
        List<Info> out = new ArrayList<>();
        for (Map<String, Object> r : catalog.query("SELECT * FROM tenants ORDER BY slug")) out.add(info(r));
        return out;
    }

    public Info get(String slug) {
        Map<String, Object> r = catalog.one("SELECT * FROM tenants WHERE slug = ?", slug);
        return r == null ? null : info(r);
    }

    /** Stops a tenant: its requests are refused and its schedules and triggers stop, its data stays. */
    public void suspend(String slug, String by) {
        Info i = need(slug);
        catalog.update("UPDATE tenants SET status = 'suspended', updated = ?, updated_by = ? WHERE id = ?", Instant.now(), by, i.id());
        Tenant t = open.remove(i.id());
        if (t != null) stop(t);
    }

    public void resume(String slug, String by) {
        Info i = need(slug);
        catalog.update("UPDATE tenants SET status = 'active', updated = ?, updated_by = ? WHERE id = ?", Instant.now(), by, i.id());
        start(get(slug));
    }

    /** Removes a suspended tenant for good: its schema, its folder and its catalog entry (its key with it). */
    public void delete(String slug, String by) {
        Info i = need(slug);
        if (!i.status().equals("suspended")) throw new OoaException("SAAS.NOT_SUSPENDED", "Suspend " + slug + " first; a tenant is deleted only when suspended");
        base.dropSchema(schemaOf(i.id()));
        Path dir = folderOf(i.id());
        if (Files.exists(dir)) try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        } catch (IOException e) { throw new OoaException("SAAS.DELETE_FAILED", "Cannot remove " + dir + ": " + e.getMessage()); }
        catalog.update("DELETE FROM tenants WHERE id = ?", i.id());
        RegionDirectory.Client names = directory;
        if (names != null) names.release(slug);
    }

    /** The open tenant with this address name, opening it when needed; null when there is none or it is not active. */
    public Tenant serve(String slug) {
        for (Tenant t : open.values()) if (t.info.slug().equals(slug)) { t.lastUsed = System.currentTimeMillis(); return t; }
        Info i = get(slug);
        if (i == null || !i.status().equals("active")) return null;
        return start(i);
    }

    /** Opens every active tenant, so their schedules, triggers and queues run without waiting for a request. */
    public void startAll() {
        for (Info i : list()) if (i.status().equals("active")) {
            try { start(i); } catch (RuntimeException e) { System.err.println("Tenant " + i.slug() + " did not start: " + e.getMessage()); }
        }
    }

    /** Changes a tenant's seats; an open tenant uses them at once. */
    public Info setSeats(String slug, Map<String, Object> seats, String by) {
        Info i = need(slug);
        Map<String, Integer> counts = seatCounts(seats);
        catalog.update("UPDATE tenants SET seats = ?, updated = ?, updated_by = ? WHERE id = ?", JsonLite.write(counts), Instant.now(), by, i.id());
        Tenant t = open.get(i.id());
        if (t != null) t.cr.tenantSeats = counts;
        return get(slug);
    }

    /** Seat counts by license type: only known types, whole numbers from 0. */
    static Map<String, Integer> seatCounts(Map<String, Object> seats) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (seats == null) return out;
        for (Map.Entry<String, Object> e : seats.entrySet()) {
            if (!com.oneofficeautomation.controlroom.License.TYPES.contains(e.getKey()))
                throw new OoaException("SAAS.BAD_SEATS", "No seat type '" + e.getKey() + "'", "Use " + String.join(", ", com.oneofficeautomation.controlroom.License.TYPES));
            if (!(e.getValue() instanceof Number n) || n.intValue() < 0 || n.doubleValue() != n.intValue())
                throw new OoaException("SAAS.BAD_SEATS", "Seats of '" + e.getKey() + "' are a whole number from 0");
            out.put(e.getKey(), n.intValue());
        }
        return out;
    }

    /** Closes database connections of tenants nobody used for the given time; they stay open and reconnect on use. */
    public void closeIdle(long idleMillis) {
        long cut = System.currentTimeMillis() - idleMillis;
        for (Tenant t : open.values()) if (t.lastUsed < cut) t.cr.db.closeIdle();
    }

    /**
     * Closes tenants that another node of the region suspended or deleted (a region may run several nodes behind its
     * load balancer); their requests and background work stop here too.
     */
    public void syncWithCatalog() {
        Map<String, String> status = new HashMap<>();
        for (Map<String, Object> r : catalog.query("SELECT id, status FROM tenants")) status.put((String) r.get("id"), (String) r.get("status"));
        for (Tenant t : List.copyOf(open.values()))
            if (!"active".equals(status.get(t.info.id())) && !"creating".equals(status.get(t.info.id()))) {
                open.remove(t.info.id());
                stop(t);
            }
    }

    public Collection<Tenant> opened() { return Collections.unmodifiableCollection(open.values()); }

    private synchronized Tenant start(Info i) {
        Tenant running = open.get(i.id());
        if (running != null) return running;
        Map<String, Object> row = catalog.one("SELECT key_ciphertext FROM tenants WHERE id = ?", i.id());
        byte[] key = Base64.getDecoder().decode(regionVault.decrypt((String) row.get("key_ciphertext"), "tenant:" + i.id()));
        Path dir = folderOf(i.id());
        try { Files.createDirectories(dir); } catch (IOException e) { throw new OoaException("SAAS.FOLDER", "Cannot create " + dir + ": " + e.getMessage()); }
        Db db = base.inSchema(schemaOf(i.id()), 3);
        ControlRoom cr = new ControlRoom(db, dir, registries.get(), schema, Vault.ofKey(key, "tenant key in the region catalog"), i.id());
        cr.serverlessAllowed = serverless;
        cr.tenantSeats = seatCounts(i.seats());
        // its own address, for sign-in redirects, secure cookies and links in mail (it may set another in Settings)
        if (cr.db.one("SELECT name FROM settings WHERE name = 'public_url'") == null)
            cr.db.update("INSERT INTO settings (name, json_value, updated, updated_by) VALUES (?,?,?,?)", "public_url",
                JsonLite.write("https://" + i.slug() + "." + domain), Instant.now(), "system");
        ControlRoomServer server = ControlRoomServer.forTenant(cr);
        server.startBackground();
        Tenant t = new Tenant(i, cr, server);
        open.put(i.id(), t);
        return t;
    }

    private static void stop(Tenant t) {
        try { t.server.stop(); } catch (RuntimeException ignored) { }
        t.cr.db.close();
    }

    private Info need(String slug) {
        Info i = get(slug);
        if (i == null) throw new OoaException("SAAS.NO_TENANT", "No tenant '" + slug + "' in region " + region);
        return i;
    }

    @SuppressWarnings("unchecked")
    private static Info info(Map<String, Object> r) {
        return new Info((String) r.get("id"), (String) r.get("slug"), (String) r.get("name"), (String) r.get("status"), (String) r.get("region"),
            (String) r.get("admin_email"), (Map<String, Object>) JsonLite.parse((String) r.get("seats")), String.valueOf(r.get("created")));
    }

    @Override public void close() {
        for (Tenant t : open.values()) stop(t);
        open.clear();
        catalog.close();
    }
}
