package com.oneofficeautomation.cli;

import com.oneofficeautomation.controlroom.License;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.PublicKey;
import java.time.Duration;
import java.util.*;

/**
 * The public releases page (Q10): {@code ooa release page --repo OWNER/NAME --out DIR}. Reads the repository's GitHub
 * releases, checks each release's signed manifest with the vendor key built into the product, and writes index.html
 * (every release, newest first, with its files, sizes, SHA-256 values and download links) and releases.json. Drafts
 * are left out; a release whose manifest does not check out is listed without checksums and marked as such.
 * The publish script puts both files in the release repository's docs folder, which GitHub Pages serves.
 */
final class ReleasePage {
    private ReleasePage() { }

    /** One file of a release: its download address, and the size and SHA-256 from the signed manifest when it checked out. */
    record Asset(String name, long size, String url, String sha256) {}

    record Release(String tag, String name, String published, boolean preview, String notes, String htmlUrl, boolean signed, List<Asset> assets) {}

    static int run(Map<String, String> o) throws Exception {
        String repo = o.get("repo");
        String token = o.containsKey("token-env") ? System.getenv(o.get("token-env")) : null;
        List<Release> releases = fetch(o.getOrDefault("api", "https://api.github.com"), repo, token, License.publicKey(License.VENDOR_PUBLIC_KEY));
        Path out = Path.of(o.get("out"));
        Files.createDirectories(out);
        Files.writeString(out.resolve("index.html"), html(repo, releases), StandardCharsets.UTF_8);
        Files.writeString(out.resolve("releases.json"), json(releases), StandardCharsets.UTF_8);
        System.out.println("Wrote " + out.resolve("index.html") + " with " + releases.size() + " release(s)"
            + (releases.stream().anyMatch(r -> !r.signed()) ? "; some without a valid signed manifest" : "") + ".");
        return 0;
    }

    /** The published releases of a repository, newest first, each checked against its signed manifest. */
    @SuppressWarnings("unchecked")
    static List<Release> fetch(String api, String repo, String token, PublicKey vendorKey) throws IOException, InterruptedException {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build();
        String list = get(http, api + "/repos/" + repo + "/releases?per_page=100", token, "application/vnd.github+json");
        List<Release> out = new ArrayList<>();
        for (Object x : (List<Object>) JsonLite.parse(list)) {
            Map<String, Object> r = (Map<String, Object>) x;
            if (Boolean.TRUE.equals(r.get("draft"))) continue;
            List<Map<String, Object>> assets = (List<Map<String, Object>>) r.getOrDefault("assets", List.of());
            Map<String, Map<String, Object>> listed = null;
            for (Map<String, Object> a : assets) {
                if (!String.valueOf(a.get("name")).endsWith(".manifest.json")) continue;
                String text = get(http, api + "/repos/" + repo + "/releases/assets/" + a.get("id"), token, "application/octet-stream");
                try {
                    listed = new LinkedHashMap<>();
                    for (Map<String, Object> f : (List<Map<String, Object>>) ReleaseCommand.readManifest(text, vendorKey).get("files"))
                        listed.put(String.valueOf(f.get("name")), f);
                } catch (OoaException e) { listed = null; }
            }
            List<Asset> files = new ArrayList<>();
            for (Map<String, Object> a : assets) {
                String name = String.valueOf(a.get("name"));
                Map<String, Object> m = listed == null ? null : listed.get(name);
                long size = ((Number) a.getOrDefault("size", 0)).longValue();
                // a checksum only when the signed manifest lists the file with the size GitHub holds
                String sha = m != null && ((Number) m.get("size")).longValue() == size ? String.valueOf(m.get("sha256")) : null;
                files.add(new Asset(name, size, String.valueOf(a.get("browser_download_url")), sha));
            }
            out.add(new Release(String.valueOf(r.get("tag_name")), String.valueOf(r.getOrDefault("name", r.get("tag_name"))),
                String.valueOf(r.getOrDefault("published_at", "")), Boolean.TRUE.equals(r.get("prerelease")),
                r.get("body") == null ? "" : String.valueOf(r.get("body")), String.valueOf(r.get("html_url")), listed != null, files));
        }
        out.sort(Comparator.comparing(Release::published).reversed());
        return out;
    }

    private static String get(HttpClient http, String url, String token, String accept) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).header("Accept", accept)
            .header("X-GitHub-Api-Version", "2022-11-28").header("User-Agent", "ONEOFFICEAUTOMATION-release-page");
        if (token != null && !token.isBlank()) b.header("Authorization", "Bearer " + token);
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (r.statusCode() / 100 != 2)
            throw new OoaException("RELEASE.PAGE_FETCH", "GitHub answered " + r.statusCode() + " for " + url,
                r.statusCode() == 404 ? "Check --repo; a private repository needs --token-env with a token that can read it." : null);
        return r.body();
    }

    static String json(List<Release> releases) {
        List<Object> list = new ArrayList<>();
        for (Release r : releases) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tag", r.tag()); m.put("name", r.name()); m.put("published", r.published()); m.put("preview", r.preview());
            m.put("signed_manifest", r.signed()); m.put("page", r.htmlUrl());
            List<Object> files = new ArrayList<>();
            for (Asset a : r.assets()) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("name", a.name()); f.put("size", a.size()); f.put("url", a.url());
                if (a.sha256() != null) f.put("sha256", a.sha256());
                files.add(f);
            }
            m.put("files", files);
            list.add(m);
        }
        return JsonLite.write(list);
    }

    static String html(String repo, List<Release> releases) {
        StringBuilder s = new StringBuilder();
        s.append("""
            <!doctype html>
            <html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
            <title>ONEOFFICEAUTOMATION releases</title>
            <style>
            :root{--bg:#fff;--fg:#1d2433;--muted:#5b6578;--line:#dde2ea;--card:#f6f8fb;--accent:#1f5fbf;--warn:#9a5b00}
            @media (prefers-color-scheme:dark){:root{--bg:#12161d;--fg:#e6e9ef;--muted:#9aa3b2;--line:#2b3240;--card:#1a2029;--accent:#7fb0ff;--warn:#f0b35a}}
            body{margin:0;background:var(--bg);color:var(--fg);font:15px/1.5 system-ui,-apple-system,"Segoe UI",sans-serif}
            main{max-width:980px;margin:0 auto;padding:24px 16px 48px}
            h1{font-size:26px;margin:0 0 4px}h2{font-size:19px;margin:0}
            a{color:var(--accent)}.muted{color:var(--muted)}
            section{border:1px solid var(--line);border-radius:10px;padding:16px;margin:18px 0;background:var(--card)}
            .tag{display:inline-block;font-size:12px;padding:1px 8px;border-radius:9px;border:1px solid var(--line);margin-left:8px;vertical-align:2px}
            .warn{color:var(--warn)}
            .files{width:100%;border-collapse:collapse;margin-top:10px;font-size:14px}
            .files th,.files td{text-align:left;padding:6px 8px;border-top:1px solid var(--line);vertical-align:top}
            .sha{font-family:ui-monospace,Consolas,monospace;font-size:12px;word-break:break-all}
            .notes{white-space:pre-wrap;margin:10px 0 0}
            code{font-family:ui-monospace,Consolas,monospace;font-size:13px}
            .scroll{overflow-x:auto}
            </style></head><body><main>
            <h1>ONEOFFICEAUTOMATION releases</h1>
            """);
        s.append("<p class=\"muted\">Every release, newest first. Download <b>ooa-setup-VERSION.zip</b> (Java included), unzip it and run "
            + "<code>Install.cmd</code>. Install your license file in Administration &gt; Licenses; without one, a new install runs a 30-day evaluation.</p>\n");
        s.append("<p class=\"muted\">Check a download before installing: with an earlier install, run <code>ooa release verify ooa-VERSION.manifest.json</code> "
            + "in the folder with the downloads (it checks the vendor's signature, then each file). Otherwise compare the SHA-256 below with "
            + "<code>certutil -hashfile FILE SHA256</code>. The checksums here come from each release's signed manifest.</p>\n");
        if (releases.isEmpty()) s.append("<p>No releases yet.</p>\n");
        for (Release r : releases) {
            s.append("<section><h2>").append(esc(r.name()));
            if (r.preview()) s.append("<span class=\"tag\">sandbox preview</span>");
            s.append("</h2><div class=\"muted\">").append(esc(r.tag())).append(" &middot; ").append(esc(day(r.published())))
                .append(" &middot; <a href=\"").append(href(r.htmlUrl())).append("\">on GitHub</a></div>\n");
            if (!r.signed()) s.append("<p class=\"warn\">No valid signed manifest for this release: no checksums are shown.</p>\n");
            if (!r.notes().isBlank()) s.append("<p class=\"notes\">").append(esc(r.notes().replace("**", ""))).append("</p>\n");
            s.append("<div class=\"scroll\"><table class=\"files\"><tr><th>File</th><th>Size</th><th>SHA-256</th></tr>\n");
            for (Asset a : r.assets())
                s.append("<tr><td><a href=\"").append(href(a.url())).append("\">").append(esc(a.name())).append("</a></td><td>")
                    .append(size(a.size())).append("</td><td class=\"sha\">").append(a.sha256() == null ? "&ndash;" : esc(a.sha256())).append("</td></tr>\n");
            s.append("</table></div></section>\n");
        }
        s.append("<p class=\"muted\">Generated from <a href=\"https://github.com/").append(esc(repo)).append("/releases\">")
            .append(esc(repo)).append("</a>. Machine-readable: <a href=\"releases.json\">releases.json</a>.</p>\n</main></body></html>\n");
        return s.toString();
    }

    static String size(long bytes) {
        if (bytes >= 1 << 20) return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0);
        if (bytes >= 1 << 10) return String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0);
        return bytes + " B";
    }

    /** Only https addresses become links: the page never carries another kind of address from the API. */
    static String href(String url) { return url != null && url.startsWith("https://") ? esc(url) : "#"; }

    private static String day(String iso) { return iso.length() >= 10 ? iso.substring(0, 10) : iso; }

    static String esc(String v) {
        return v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}
