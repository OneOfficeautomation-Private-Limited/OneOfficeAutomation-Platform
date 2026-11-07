package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.controlroom.module.ModuleContext;

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
 * <p>
 * A template can also carry <b>Document Automation processes</b> (bundle.document_processes, with the document types
 * they read in bundle.document_types): sharing pulls their definitions from DocFlow, without what belongs to the
 * company that made them (email inbox, watched folder, delivery credentials, learned labels); installing creates them
 * as drafts in the installing company's DocFlow, in the chosen folder, with their "Start a bot" deliveries pointing at
 * the template's agents as installed there. A process already there (same name and folder) is kept, as agents are.
 * <pre>
 *   feed: {format: ooa-templates-1, payload: Base64 of {catalog, created, templates: [{id, name, description, category, version, bundle}]}, signature}
 * </pre>
 */
public final class Templates {
    static final String FORMAT = "ooa-templates-1";
    /**
     * No catalogue is published at the moment (the customer repository was emptied on 2026-10-30), so an empty setting
     * means none. When releases are published again, this becomes
     * https://oneofficeautomation-private-limited.github.io/OneOfficeAutomation-Customer-Build/templates.json.
     */
    static final String DEFAULT_URL = "off";
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
    /** Document Automation, for templates with processes (made when first needed; tests set their own). */
    private volatile List<Map<String, Object>> cached = List.of();
    private volatile long cachedAt;
    private volatile String cachedFrom = "";

    Templates(ControlRoom cr) { this(cr, () -> License.publicKey(License.VENDOR_PUBLIC_KEY)); }
    Templates(ControlRoom cr, Supplier<PublicKey> key) { this.cr = cr; this.key = key; }

    /** Templates of One CommandCenter that trust another catalogue key (tests of the vendor's catalogue). */
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
        Map<String, Object> layout = folders();
        Map<String, String> placed = (Map<String, String>) layout.get("placed");
        List<String> folders = (List<String>) layout.get("folders");
        for (Map<String, Object> t : out) {   // a template whose folder has gone sits at the top, under All templates
            String f = placed.getOrDefault(t.get("source") + ":" + t.get("id"), String.valueOf(t.get("category")));
            t.put("folder", folders.contains(f) ? f : "");
        }
        m.put("categories", folders);
        m.put("can_arrange", cr.can(by, "settings.manage"));
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
        m.put("processes", processNames(b));
        return m;
    }

    /** The names of the Document Automation processes a template's bundle carries. */
    static List<Object> processNames(Object bundle) {
        List<Object> out = new ArrayList<>();
        if (bundle instanceof Map<?, ?> bm && bm.get("document_processes") instanceof List<?> l)
            for (Object p : l) if (p instanceof Map<?, ?> pm) out.add(pm.get("name"));
        return out;
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
        // what feature modules add to templates (Document Automation's processes), each creating its part of the bundle
        List<Map<String, Object>> processes = new ArrayList<>();
        if (Boolean.TRUE.equals(r.get("ok")) && cr.modules != null) for (ModuleContext.TemplatePart part : cr.modules.templateParts) processes.addAll(part.install(by, bundle, target));
        boolean processesOk = processes.stream().noneMatch(p -> "failed".equals(p.get("status")));
        cr.audit.record(by.username(), "template.install", "template", source + ":" + id, Map.of("folder", target, "ok", r.get("ok"), "processes", processes.size()),
                Boolean.TRUE.equals(r.get("ok")) && processesOk ? "ok" : "failed", ip);
        r.put("folder", target);
        if (!processes.isEmpty()) { r.put("processes", processes); r.put("processes_ok", processesOk); }
        return r;
    }

    /** SaaS: offers one of the tenant's agents (with the agents it calls) to the region's catalogue, for an operator to approve. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> share(User by, String path, String name, String description, String category, String ip) {
        return share(by, path, name, description, category, List.of(), ip);
    }

    /** As above, with Document Automation processes (their ids) that go along with the agent. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> share(User by, String path, String name, String description, String category, List<String> processes, String ip) {
        cr.require(by, "lifecycle.export", "share templates");
        Shared s = shared;
        if (s == null) throw new OoaException("TEMPLATES.NO_REGION", "Sharing templates is part of the SaaS service", "On premises, export the agent (Export / promote) and send the file.");
        if (name == null || name.isBlank() || name.length() > 120) throw new OoaException("CR.BAD_INPUT", "Give the template a name (at most 120 characters)");
        if (description != null && description.length() > 2000) throw new OoaException("CR.BAD_INPUT", "The description is at most 2000 characters");
        Map<String, Object> bundle = new LinkedHashMap<>(cr.lifecycle.export(by, List.<Object>of(path), "production", true, ip));
        if (processes != null && !processes.isEmpty() && cr.modules != null)
            for (ModuleContext.TemplatePart part : cr.modules.templateParts) part.addToBundle(by, bundle, processes);
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("name", name.trim());
        t.put("description", description == null ? "" : description.trim());
        t.put("category", ((List<String>) folders().get("folders")).contains(category) ? category : "Other");
        t.put("bundle", bundle);
        t.put("submitted_by", by.username());
        Map<String, Object> r = s.submit(cr.tenant, t);
        cr.audit.record(by.username(), "template.share", "bot", path, Map.of("template", r.get("id"), "agents", ((List<Object>) bundle.get("agents")).size(),
                "processes", processNames(bundle).size()), "ok", ip);
        return r;
    }

    // ------------------------------------------------------------ template folders

    /**
     * How the templates are arranged here (review round 2, 31 Oct 2026): administrators make, rename and delete folders
     * (nested with /) and move templates between them, as in Automation. The catalogue's categories are the first
     * folders; a template sits in its category until it is moved. Kept beside the uploaded catalogue, in folders.json.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> folders() {
        Path f = cr.dataDir.resolve("templates").resolve("folders.json");
        try {
            if (Files.exists(f)) {
                Map<String, Object> m = (Map<String, Object>) JsonLite.parse(Files.readString(f));
                Map<String, String> placed = new LinkedHashMap<>();
                if (m.get("placed") instanceof Map<?, ?> p) p.forEach((k, v) -> placed.put(String.valueOf(k), String.valueOf(v)));
                return Map.of("folders", List.copyOf((List<String>) m.getOrDefault("folders", CATEGORIES)), "placed", placed);
            }
        } catch (IOException | RuntimeException e) { /* a damaged file: the categories again */ }
        return Map.of("folders", CATEGORIES, "placed", Map.of());
    }

    /** Saves the folders and where templates sit ("" is the top; administrators). A folder inside another needs that folder too. */
    public Map<String, Object> arrange(User by, List<String> folders, Map<String, String> placed, String ip) {
        cr.require(by, "settings.manage", "arrange the templates");
        if (folders == null || folders.isEmpty() || folders.size() > 500) throw new OoaException("CR.BAD_INPUT", "Give between 1 and 500 folders");
        Set<String> seen = new LinkedHashSet<>();
        for (String f : folders) {
            String n = f == null ? "" : f.trim();
            if (n.isEmpty() || n.length() > 120 || n.startsWith("/") || n.endsWith("/") || n.contains("//") || !n.matches("[^\\\\<>:\"|?*]+"))
                throw new OoaException("CR.BAD_INPUT", "A folder name is 1 to 120 characters, without \\ < > : \" | ? * (use / for a folder inside another)");
            if (!seen.add(n)) throw new OoaException("CR.BAD_INPUT", "The folder '" + n + "' is there twice");
        }
        for (String n : seen) if (n.contains("/") && !seen.contains(n.substring(0, n.lastIndexOf('/'))))
            throw new OoaException("CR.BAD_INPUT", "The folder '" + n + "' is inside '" + n.substring(0, n.lastIndexOf('/')) + "', which is not there");
        Map<String, String> place = new LinkedHashMap<>();
        if (placed != null) placed.forEach((k, v) -> {
            if (!v.isEmpty() && !seen.contains(v)) throw new OoaException("CR.BAD_INPUT", "There is no folder '" + v + "' to put a template in");
            place.put(k, v);
        });
        Path f = cr.dataDir.resolve("templates").resolve("folders.json");
        try { Files.createDirectories(f.getParent()); Files.writeString(f, JsonLite.write(Map.of("folders", List.copyOf(seen), "placed", place))); }
        catch (IOException e) { throw new OoaException("CR.IO", "Cannot save the template folders: " + e.getMessage()); }
        cr.audit.record(by.username(), "template.folders", "template", null, Map.of("folders", seen.size(), "placed", place.size()), "ok", ip);
        return Map.of("folders", List.copyOf(seen), "placed", place);
    }

    /** Uploads a signed catalogue file (administrators; for One CommandCenter without internet access). */
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
