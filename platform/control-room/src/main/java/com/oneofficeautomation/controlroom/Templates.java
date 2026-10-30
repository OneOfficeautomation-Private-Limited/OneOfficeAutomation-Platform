package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

import com.oneofficeautomation.controlroom.ControlRoom.User;

/**
 * Agent templates (K7): ready-made agents people install into their agent repository and adapt. A template is an agent
 * bundle (Lifecycle export: the agent and the agents it calls) with a name, a description and a category.
 * <p>
 * Templates come from:
 * <ul>
 * <li><b>ONEOFFICEAUTOMATION's catalogue</b>: a feed signed with the vendor key, published with each release on the
 *     releases page (setting template_catalog_url; "off" for none). A feed file can also be uploaded, for Command
 *     Centers without internet access. A feed whose signature does not check out is refused as a whole.</li>
 * <li><b>The region's catalogue</b> (SaaS): a tenant shares one of its agents; once a region operator approves it,
 *     every tenant of the region can install it. Only the agents themselves are shared, never credentials or values.</li>
 * </ul>
 * Installing imports the bundle as Lifecycle import does (checksums, validation, all or nothing), into a chosen folder.
 * <pre>
 *   feed: {format: ooa-templates-1, payload: Base64 of {catalog, created, templates: [{id, name, description, category, version, bundle}]}, signature}
 * </pre>
 */
public final class Templates {
    static final String FORMAT = "ooa-templates-1";
    static final String DEFAULT_URL = "https://oneofficeautomation-private-limited.github.io/OneOfficeAutomation-Customer-Build/templates.json";
    static final int MAX_FEED_BYTES = 20 * 1024 * 1024;
    static final List<String> CATEGORIES = List.of("Finance", "Human resources", "Sales", "Customer service", "IT", "Operations", "Documents", "Other");

    /** The region's shared catalogue of a SaaS tenant (Tenants); null on premises. */
    public interface Shared {
        List<Map<String, Object>> approved();
        Map<String, Object> bundle(String id);
        Map<String, Object> submit(String tenantId, Map<String, Object> template);
    }

    private final ControlRoom cr;
    private final Supplier<PublicKey> key;
    volatile Shared shared;
    private volatile List<Map<String, Object>> cached = List.of();
    private volatile long cachedAt;
    private volatile String cachedFrom = "";

    Templates(ControlRoom cr) { this(cr, () -> License.publicKey(License.VENDOR_PUBLIC_KEY)); }
    Templates(ControlRoom cr, Supplier<PublicKey> key) { this.cr = cr; this.key = key; }

    /** Templates of a Command Center that trust another catalogue key (tests of the vendor's catalogue). */
    public static Templates withKey(ControlRoom cr, PublicKey key) { return new Templates(cr, () -> key); }

    // ------------------------------------------------------------ feeds

    /** An agent bundle (as Lifecycle export makes) from agent files: path in the repository -> the agent's JSON. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> bundleOf(Map<String, String> agents, String source) {
        List<Object> list = new ArrayList<>();
        for (Map.Entry<String, String> e : agents.entrySet()) {
            Map<String, Object> flow = (Map<String, Object>) JsonLite.parse(e.getValue());
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("path", e.getKey());
            a.put("bot_id", flow.get("id"));
            a.put("name", flow.get("name"));
            a.put("version", 1);
            a.put("production", true);
            a.put("included_as", "chosen");
            a.put("sha256", Security.sha256(e.getValue()));
            a.put("content", e.getValue());
            list.add(a);
        }
        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("format", Lifecycle.FORMAT);
        bundle.put("format_version", Lifecycle.FORMAT_VERSION);
        bundle.put("exported_at", Instant.now().toString());
        bundle.put("exported_by", "ONEOFFICEAUTOMATION");
        bundle.put("source", source);
        bundle.put("agents", list);
        bundle.put("checksum", Lifecycle.checksum(list));
        return bundle;
    }

    /** Makes a signed feed (the vendor's tool, ooa release templates). */
    public static String makeFeed(String catalog, List<Map<String, Object>> templates, PrivateKey key) {
        for (Map<String, Object> t : templates) check(t);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("catalog", catalog);
        body.put("created", Instant.now().toString());
        body.put("templates", templates);
        byte[] payload = JsonLite.write(body).getBytes(StandardCharsets.UTF_8);
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(key);
            s.update(payload);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("format", FORMAT);
            m.put("payload", Base64.getEncoder().encodeToString(payload));
            m.put("signature", Base64.getEncoder().encodeToString(s.sign()));
            return JsonLite.write(m);
        } catch (GeneralSecurityException e) { throw new OoaException("TEMPLATES.SIGN_FAILED", "Signing failed: " + e.getMessage()); }
    }

    /** The templates of a signed feed; a feed that is not signed with the key is refused whole. */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> readFeed(String text, PublicKey key) {
        Map<String, Object> m;
        try { m = (Map<String, Object>) JsonLite.parse(text.trim()); } catch (RuntimeException e) { throw notAFeed(); }
        if (!FORMAT.equals(m.get("format")) || !(m.get("payload") instanceof String p) || !(m.get("signature") instanceof String s)) throw notAFeed();
        byte[] payload;
        try {
            payload = Base64.getDecoder().decode(p);
            Signature v = Signature.getInstance("Ed25519");
            v.initVerify(key);
            v.update(payload);
            if (!v.verify(Base64.getDecoder().decode(s))) throw new OoaException("TEMPLATES.BAD_SIGNATURE", "The template catalogue's signature is not valid: it was changed or not made by ONEOFFICEAUTOMATION");
        } catch (OoaException e) { throw e; }
        catch (Exception e) { throw new OoaException("TEMPLATES.BAD_SIGNATURE", "The template catalogue's signature is not valid: it was changed or not made by ONEOFFICEAUTOMATION"); }
        Map<String, Object> body = (Map<String, Object>) JsonLite.parse(new String(payload, StandardCharsets.UTF_8));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : (List<Object>) body.getOrDefault("templates", List.of())) {
            Map<String, Object> t = (Map<String, Object>) o;
            check(t);
            out.add(t);
        }
        return out;
    }

    private static OoaException notAFeed() { return new OoaException("TEMPLATES.BAD_FEED", "This is not a ONEOFFICEAUTOMATION template catalogue"); }

    /** A template has an id, a name, a category and an agent bundle. */
    static void check(Map<String, Object> t) {
        if (!(t.get("id") instanceof String id) || !id.matches("^[a-z0-9][a-z0-9_-]{0,79}$")) throw new OoaException("TEMPLATES.BAD_TEMPLATE", "A template needs an id of lower-case letters, digits, - and _");
        if (!(t.get("name") instanceof String n) || n.isBlank()) throw new OoaException("TEMPLATES.BAD_TEMPLATE", "Template " + id + " has no name");
        if (!(t.get("bundle") instanceof Map<?, ?> b) || !Lifecycle.FORMAT.equals(b.get("format"))) throw new OoaException("TEMPLATES.BAD_TEMPLATE", "Template " + id + " holds no agent bundle");
    }

    /** ONEOFFICEAUTOMATION's templates: an uploaded feed file, else the catalogue address (fetched at most hourly). */
    List<Map<String, Object>> vendor() {
        Path uploaded = cr.dataDir.resolve("templates").resolve("catalogue.json");
        String url = String.valueOf(cr.platform.setting("template_catalog_url")).trim();
        if (url.isEmpty()) url = DEFAULT_URL;
        String from = Files.exists(uploaded) ? uploaded.toString() : url;
        if (from.equals(cachedFrom) && System.currentTimeMillis() - cachedAt < 3_600_000) return cached;
        List<Map<String, Object>> got;
        try {
            if (Files.exists(uploaded)) got = readFeed(Files.readString(uploaded), key.get());
            else if (url.equalsIgnoreCase("off")) got = List.of();
            else got = readFeed(fetch(url), key.get());
        } catch (IOException e) { throw new OoaException("TEMPLATES.UNAVAILABLE", "The template catalogue cannot be read: " + e.getMessage(), "Check the address in Settings (template catalogue), or upload the catalogue file."); }
        cached = got; cachedAt = System.currentTimeMillis(); cachedFrom = from;
        return got;
    }

    private String fetch(String url) throws IOException {
        if (!url.startsWith("https://") && !url.startsWith("http://")) throw new OoaException("TEMPLATES.BAD_URL", "The template catalogue address starts with https://");
        NetGuard.check(cr, url, "The template catalogue");
        try {
            HttpResponse<byte[]> r = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build()
                .send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            if (r.statusCode() / 100 != 2) throw new IOException("HTTP " + r.statusCode() + " from " + url);
            if (r.body().length > MAX_FEED_BYTES) throw new IOException("the catalogue is larger than 20 MB");
            return new String(r.body(), StandardCharsets.UTF_8);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("interrupted"); }
    }

    // ------------------------------------------------------------ what people do

    /** The templates people may install: ONEOFFICEAUTOMATION's and, in SaaS, the region's approved ones (without their content). */
    @SuppressWarnings("unchecked")
    public Map<String, Object> list(User by) {
        cr.require(by, "repo.view", "see templates");
        List<Map<String, Object>> out = new ArrayList<>();
        String problem = null;
        try { for (Map<String, Object> t : vendor()) out.add(summary(t, "vendor", "ONEOFFICEAUTOMATION")); }
        catch (OoaException e) { problem = e.getMessage(); }
        Shared s = shared;
        if (s != null) for (Map<String, Object> t : s.approved()) out.add(summary(t, "region", String.valueOf(t.get("publisher"))));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("templates", out);
        m.put("categories", CATEGORIES);
        m.put("can_share", s != null && cr.can(by, "lifecycle.export"));
        if (problem != null) m.put("problem", problem);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> summary(Map<String, Object> t, String source, String publisher) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", source);
        m.put("id", t.get("id"));
        m.put("name", t.get("name"));
        m.put("description", t.getOrDefault("description", ""));
        m.put("category", t.getOrDefault("category", "Other"));
        m.put("version", t.getOrDefault("version", "1"));
        m.put("publisher", publisher);
        List<Object> agents = new ArrayList<>();
        Object b = t.get("bundle");
        if (b instanceof Map<?, ?> bm && bm.get("agents") instanceof List<?> l)
            for (Object a : l) if (a instanceof Map<?, ?> am) agents.add(am.get("path"));
        m.put("agents", agents);
        return m;
    }

    /** Installs a template into a folder (created when missing); agents already there are kept (skip). */
    @SuppressWarnings("unchecked")
    public Map<String, Object> install(User by, String source, String id, String folder, String ip) {
        cr.require(by, "lifecycle.import", "install templates");
        Map<String, Object> bundle = null;
        if ("vendor".equals(source)) {
            for (Map<String, Object> t : vendor()) if (t.get("id").equals(id)) bundle = (Map<String, Object>) t.get("bundle");
        } else if ("region".equals(source) && shared != null) bundle = shared.bundle(id);
        if (bundle == null) throw new OoaException("CR.NOT_FOUND", "No template '" + id + "'");
        String target = folder == null || folder.isBlank() ? "Templates" : folder;
        Map<String, Object> r = new LinkedHashMap<>(cr.lifecycle.importBundle(by, bundle, "skip", false, target, ip));
        cr.audit.record(by.username(), "template.install", "template", source + ":" + id, Map.of("folder", target, "ok", r.get("ok")), Boolean.TRUE.equals(r.get("ok")) ? "ok" : "failed", ip);
        r.put("folder", target);
        return r;
    }

    /** SaaS: offers one of the tenant's agents (with the agents it calls) to the region's catalogue, for an operator to approve. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> share(User by, String path, String name, String description, String category, String ip) {
        cr.require(by, "lifecycle.export", "share templates");
        Shared s = shared;
        if (s == null) throw new OoaException("TEMPLATES.NO_REGION", "Sharing templates is part of the SaaS service", "On premises, export the agent (Export / promote) and send the file.");
        if (name == null || name.isBlank() || name.length() > 120) throw new OoaException("CR.BAD_INPUT", "Give the template a name (at most 120 characters)");
        if (description != null && description.length() > 2000) throw new OoaException("CR.BAD_INPUT", "The description is at most 2000 characters");
        Map<String, Object> bundle = cr.lifecycle.export(by, List.<Object>of(path), "production", true, ip);
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("name", name.trim());
        t.put("description", description == null ? "" : description.trim());
        t.put("category", CATEGORIES.contains(category) ? category : "Other");
        t.put("bundle", bundle);
        t.put("submitted_by", by.username());
        Map<String, Object> r = s.submit(cr.tenant, t);
        cr.audit.record(by.username(), "template.share", "bot", path, Map.of("template", r.get("id"), "agents", ((List<Object>) bundle.get("agents")).size()), "ok", ip);
        return r;
    }

    /** Uploads a signed catalogue file (administrators; for a Command Center without internet access). */
    public Map<String, Object> upload(User by, String text, String ip) {
        cr.require(by, "settings.manage", "upload a template catalogue");
        if (text == null || text.length() > MAX_FEED_BYTES) throw new OoaException("CR.BAD_INPUT", "Upload the catalogue file (templates.json, at most 20 MB)");
        List<Map<String, Object>> got = readFeed(text, key.get());
        Path f = cr.dataDir.resolve("templates").resolve("catalogue.json");
        try { Files.createDirectories(f.getParent()); Files.writeString(f, text); }
        catch (IOException e) { throw new OoaException("TEMPLATES.SAVE", "Cannot save the catalogue: " + e.getMessage()); }
        cachedFrom = "";
        cr.audit.record(by.username(), "template.catalogue_upload", "templates", null, Map.of("templates", got.size()), "ok", ip);
        return Map.of("templates", got.size());
    }
}
