package com.oneofficeautomation.modules.lowcode;

import com.oneofficeautomation.controlroom.ControlRoom.User;
import com.oneofficeautomation.controlroom.Db;
import com.oneofficeautomation.controlroom.module.ModuleContext;

import static com.oneofficeautomation.controlroom.module.ModuleContext.*;

import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Apps embedded in other web pages (gap G68, after AA's Automation Co-Pilot for Salesforce and ServiceNow, and Teams
 * tabs): an app maker makes an embed link for an app, naming the sites allowed to show it in a frame (Salesforce,
 * ServiceNow, an intranet). The link is /embed/&lt;id&gt;#k=&lt;key&gt;:
 * <ul>
 * <li>The key is in the URL's fragment, which browsers never send to servers or in Referer headers; the page sends
 * it as a header to /api/embed/... and only those four calls (the app, a button, a run's status, a records table)
 * accept it, for that one app. It is shown once and kept only as its SHA-256 hash.</li>
 * <li>The page may be framed only by the sites named (Content-Security-Policy frame-ancestors); the console itself
 * stays unframeable, and browser sessions keep their SameSite=Strict cookie.</li>
 * <li>Nobody signs in: the embed link stands for whoever has it, so make one only for what that page's users may do.
 * Each press is audited as app:&lt;name&gt; for embed:&lt;link name&gt;, with the publisher's rights as for any app use.</li>
 * <li>The host page may fill fields from the URL's query (?customer=ACME), such as the record the person is on.</li>
 * </ul>
 * A Teams app package (manifest and icons) puts the link in a Teams tab.
 */
public final class AppEmbeds {
    static final Pattern ORIGIN = Pattern.compile("^https://(\\*\\.)?[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+(:\\d{1,5})?$|^http://localhost(:\\d{1,5})?$");
    static final int MAX_ORIGINS = 10;

    private final ModuleContext ctx;
    private final Db db;

    private final Apps apps;

    public AppEmbeds(ModuleContext ctx, Apps apps) { this.ctx = ctx; this.db = ctx.db(); this.apps = apps; }

    static List<String> origins(Object raw) {
        List<String> out = new ArrayList<>();
        Collection<?> l = raw instanceof Collection<?> c ? c : raw == null ? List.of() : List.of(String.valueOf(raw).split("[,\\s]+"));
        for (Object o : l) {
            String s = String.valueOf(o).trim().replaceAll("/+$", "");
            if (s.isEmpty()) continue;
            if (!ORIGIN.matcher(s).matches()) throw new OoaException("CR.BAD_INPUT", "'" + s + "' is not a site address", "Use https://host (or https://*.host), such as https://acme.lightning.force.com.");
            if (!out.contains(s)) out.add(s);
        }
        if (out.isEmpty()) throw new OoaException("CR.BAD_INPUT", "Name at least one site that may show the app", "Such as https://acme.lightning.force.com or https://acme.service-now.com.");
        if (out.size() > MAX_ORIGINS) throw new OoaException("CR.BAD_INPUT", "At most " + MAX_ORIGINS + " sites per embed link");
        return out;
    }

    private static Map<String, Object> row(Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>(r);
        m.remove("key_hash");
        m.put("origins", List.of(String.valueOf(r.get("origins")).split(" ")));
        return m;
    }

    public List<Map<String, Object>> list(User by, String appId) {
        ctx.require(by, "apps.manage", "manage embed links");
        apps.app(appId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : db.query("SELECT id, app_id, name, origins, created, created_by, last_used FROM app_embeds WHERE app_id = ? ORDER BY created", appId)) out.add(row(r));
        return out;
    }

    /** A new embed link; its key is in the answer this once. */
    public Map<String, Object> make(User by, String appId, Map<String, Object> b, String ip) {
        ctx.require(by, "apps.manage", "manage embed links");
        Map<String, Object> app = apps.app(appId);
        String name = b.get("name") == null ? "" : String.valueOf(b.get("name")).trim();
        if (name.isEmpty() || name.length() > 80) throw new OoaException("CR.BAD_INPUT", "Give the embed link a name (up to 80 characters), such as Salesforce account page");
        List<String> origins = origins(b.get("origins"));
        if (ctx.count("SELECT COUNT(*) AS n FROM app_embeds WHERE app_id = ?", appId) >= 20) throw new OoaException("CR.BAD_INPUT", "An app has at most 20 embed links");
        String id = id("emb"), key = "ooae_" + ModuleContext.secureToken(24);
        db.update("INSERT INTO app_embeds (id, app_id, name, key_hash, origins, created, created_by) VALUES (?,?,?,?,?,?,?)",
            id, appId, name, ModuleContext.sha256(key), String.join(" ", origins), now(), by.username());
        ctx.audit(by.username(), "app.embed_create", "app", appId, Map.of("embed", id, "name", name, "origins", origins, "app", app.get("name")), "ok", ip);
        Map<String, Object> m = new LinkedHashMap<>(row(db.one("SELECT id, app_id, name, origins, created, created_by, last_used FROM app_embeds WHERE id = ?", id)));
        m.put("key", key);
        m.put("path", "/embed/" + id + "#k=" + key);
        return m;
    }

    public void delete(User by, String appId, String embedId, String ip) {
        ctx.require(by, "apps.manage", "manage embed links");
        Map<String, Object> r = db.one("SELECT name FROM app_embeds WHERE id = ? AND app_id = ?", embedId, appId);
        if (r == null) throw notFound("Embed link " + embedId);
        db.update("DELETE FROM app_embeds WHERE id = ?", embedId);
        ctx.audit(by.username(), "app.embed_delete", "app", appId, Map.of("embed", embedId, "name", r.get("name")), "ok", ip);
    }

    /** The sites that may frame the embed page, for its Content-Security-Policy; null when there is no such link. */
    public String frameAncestors(String embedId) {
        Map<String, Object> r = embedId == null || !embedId.matches("emb_[a-f0-9]{16}") ? null : db.one("SELECT origins FROM app_embeds WHERE id = ?", embedId);
        return r == null ? null : String.valueOf(r.get("origins"));
    }

    /** The link and its app, when the key is right (compared by hash, in constant time). */
    Map<String, Object> resolve(String embedId, String key) {
        Map<String, Object> r = embedId == null ? null : db.one("SELECT id, app_id, name, key_hash FROM app_embeds WHERE id = ?", embedId);
        if (r == null || key == null || !ModuleContext.constantEquals(ModuleContext.sha256(key), (String) r.get("key_hash")))
            throw new OoaException("CR.UNAUTHENTICATED", "This embed link is not valid any more", "Ask the app's maker for a new link.");
        db.update("UPDATE app_embeds SET last_used = ? WHERE id = ?", now(), embedId);
        return r;
    }

    // what the embed page may do: exactly what a person using the app may, for this one app
    public Map<String, Object> app(String embedId, String key) {
        Map<String, Object> e = resolve(embedId, key);
        Map<String, Object> a = apps.app((String) e.get("app_id"));
        return Map.of("id", a.get("id"), "name", a.get("name"), "definition", JsonLite.parse((String) a.get("definition")), "audience", List.of(), "published_by", a.get("published_by"));
    }

    public Map<String, Object> act(String embedId, String key, Map<String, Object> body, String ip) {
        Map<String, Object> e = resolve(embedId, key);
        return apps.actAs(apps.app((String) e.get("app_id")), "embed:" + e.get("id"), "embed:" + e.get("name"), body, ip);
    }

    public Map<String, Object> runStatus(String embedId, String key, String runId) {
        Map<String, Object> e = resolve(embedId, key);
        return apps.runStatusFor((String) e.get("app_id"), "embed:" + e.get("id"), runId);
    }

    public Map<String, Object> records(String embedId, String key, String comp, String search) {
        Map<String, Object> e = resolve(embedId, key);
        return apps.recordsFor(apps.app((String) e.get("app_id")), comp, search);
    }

    // ===================== Teams =====================

    /** A Teams app package: manifest.json with a static tab showing the embed link, and the two icons Teams asks for. */
    public byte[] teamsPackage(User by, String appId, String embedId, String key, String baseUrl) {
        ctx.require(by, "apps.manage", "manage embed links");
        Map<String, Object> e = resolve(embedId, key);
        if (!appId.equals(e.get("app_id"))) throw notFound("Embed link " + embedId);
        String base = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        if (!base.startsWith("https://")) throw new OoaException("CR.BAD_INPUT", "Teams needs One CommandCenter's https:// address", "Set Public address in Settings, or open the console over https.");
        Map<String, Object> app = apps.app(appId);
        String host = base.replaceFirst("^https://", "").replaceAll("[:/].*$", "");
        String name = String.valueOf(app.get("name"));
        String shortName = name.length() > 30 ? name.substring(0, 30) : name;
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("$schema", "https://developer.microsoft.com/en-us/json-schemas/teams/v1.17/MicrosoftTeams.schema.json");
        manifest.put("manifestVersion", "1.17");
        manifest.put("version", "1.0.0");
        manifest.put("id", UUID.nameUUIDFromBytes(("ooa-app-embed:" + embedId).getBytes(StandardCharsets.UTF_8)).toString());
        manifest.put("developer", Map.of("name", "OneOfficeAutomation", "websiteUrl", base, "privacyUrl", base, "termsOfUseUrl", base));
        manifest.put("name", Map.of("short", shortName, "full", name));
        String desc = app.get("description") == null || String.valueOf(app.get("description")).isBlank() ? "An app from One CommandCenter" : String.valueOf(app.get("description"));
        manifest.put("description", Map.of("short", desc.length() > 80 ? desc.substring(0, 80) : desc, "full", desc));
        manifest.put("icons", Map.of("color", "color.png", "outline", "outline.png"));
        manifest.put("accentColor", "#2F6FDE");
        manifest.put("staticTabs", List.of(Map.of("entityId", embedId, "name", shortName, "contentUrl", base + "/embed/" + embedId + "#k=" + key,
            "websiteUrl", base + "/embed/" + embedId, "scopes", List.of("personal"))));
        manifest.put("permissions", List.of("identity"));
        manifest.put("validDomains", List.of(host));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            z.putNextEntry(new ZipEntry("manifest.json")); z.write(JsonLite.write(manifest).getBytes(StandardCharsets.UTF_8)); z.closeEntry();
            z.putNextEntry(new ZipEntry("color.png")); z.write(icon(192, true)); z.closeEntry();
            z.putNextEntry(new ZipEntry("outline.png")); z.write(icon(32, false)); z.closeEntry();
        } catch (IOException ex) { throw new OoaException("CR.INTERNAL", "Cannot make the Teams package: " + ex.getMessage()); }
        ctx.audit(by.username(), "app.embed_teams", "app", appId, Map.of("embed", embedId), "ok", null);
        return out.toByteArray();
    }

    /** Teams' icons: a 192 px colour square, and a 32 px white outline on transparent. */
    static byte[] icon(int size, boolean color) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        if (color) { g.setColor(new Color(0x2F6FDE)); g.fillRect(0, 0, size, size); }
        g.setColor(Color.WHITE);
        int m = size / 5, w = size - 2 * m;
        g.setStroke(new BasicStroke(Math.max(2f, size / 16f)));
        g.drawRoundRect(m, m, w, w, size / 6, size / 6);
        g.fillRect(m + w / 4, m + w / 3, w / 2, Math.max(2, size / 16));
        g.fillRect(m + w / 4, m + w / 2 + w / 12, w / 3, Math.max(2, size / 16));
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try { ImageIO.write(img, "png", out); } catch (IOException e) { throw new OoaException("CR.INTERNAL", e.getMessage()); }
        return out.toByteArray();
    }
}
