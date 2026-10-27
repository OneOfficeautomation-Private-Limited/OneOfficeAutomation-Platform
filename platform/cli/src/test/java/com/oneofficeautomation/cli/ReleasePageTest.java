package com.oneofficeautomation.cli;

import com.oneofficeautomation.controlroom.License;
import com.oneofficeautomation.sdk.JsonLite;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Q10: the public releases page, made from a repository's GitHub releases (a fake GitHub API here). Checksums appear
 * only from a manifest signed with the vendor key; drafts and other addresses than https never reach the page.
 */
class ReleasePageTest {

    @Test @SuppressWarnings("unchecked")
    void listsReleasesWithChecksumsFromTheirSignedManifest() throws Exception {
        Map<String, String> vendor = License.newKeyPair(), other = License.newKeyPair();
        Path dist = Files.createTempDirectory("ooa-rel");
        Files.writeString(dist.resolve("ooa-setup-0.3.0.zip"), "setup bytes");
        String sha = ReleaseCommand.sha256(dist.resolve("ooa-setup-0.3.0.zip"));
        String good = ReleaseCommand.sign(dist, "0.3.0", License.privateKey(vendor.get("private")));
        String forged = ReleaseCommand.sign(dist, "0.4.0", License.privateKey(other.get("private")));
        long size = Files.size(dist.resolve("ooa-setup-0.3.0.zip"));

        List<Object> releases = List.of(
            release("v0.3.0", "2026-10-01T10:00:00Z", false, false, List.of(asset(1, "ooa-setup-0.3.0.zip", size), asset(2, "ooa-0.3.0.manifest.json", good.length()))),
            release("v0.4.0-preview", "2026-10-20T10:00:00Z", true, false, List.of(asset(3, "ooa-setup-0.4.0.zip", size), asset(4, "ooa-0.4.0.manifest.json", forged.length()))),
            release("v0.5.0", "2026-10-25T10:00:00Z", false, true, List.of()));
        ((Map<String, Object>) releases.get(0)).put("body", "Fixes <script>alert(1)</script> and **more**");
        ((Map<String, Object>) ((List<Object>) ((Map<String, Object>) releases.get(1)).get("assets")).get(0)).put("browser_download_url", "javascript:alert(1)");

        HttpServer api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> auth = new ArrayList<>();
        api.createContext("/", ex -> {
            auth.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
            String path = ex.getRequestURI().getPath();
            String body = path.endsWith("/releases") ? JsonLite.write(releases)
                : path.endsWith("/assets/2") ? good : path.endsWith("/assets/4") ? forged : null;
            byte[] b = (body == null ? "{}" : body).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(body == null ? 404 : 200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        api.start();
        try {
            List<ReleasePage.Release> got = ReleasePage.fetch("http://127.0.0.1:" + api.getAddress().getPort(), "acme/releases", "t0ken",
                License.publicKey(vendor.get("public")));
            assertEquals(List.of("v0.4.0-preview", "v0.3.0"), got.stream().map(ReleasePage.Release::tag).toList(), "newest first, no draft");
            assertTrue(auth.stream().allMatch(a -> a.equals("Bearer t0ken")), auth.toString());

            ReleasePage.Release v3 = got.get(1), v4 = got.get(0);
            assertTrue(v3.signed());
            assertEquals(sha, v3.assets().get(0).sha256());
            assertFalse(v4.signed(), "a manifest signed with another key is not trusted");
            assertNull(v4.assets().get(0).sha256());

            String html = ReleasePage.html("acme/releases", got);
            assertTrue(html.contains(sha) && html.contains("sandbox preview") && html.contains("No valid signed manifest"), html);
            assertFalse(html.contains("<script>alert") || html.contains("javascript:"), "notes are escaped and only https links are made");
            assertTrue(html.contains("&lt;script&gt;"));
            assertTrue(ReleasePage.json(got).contains("\"sha256\":\"" + sha + "\""));
        } finally { api.stop(0); }
    }

    static Map<String, Object> release(String tag, String published, boolean preview, boolean draft, List<Object> assets) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("tag_name", tag); r.put("name", "ONEOFFICEAUTOMATION " + tag); r.put("published_at", published);
        r.put("prerelease", preview); r.put("draft", draft); r.put("html_url", "https://github.com/acme/releases/releases/tag/" + tag);
        r.put("body", ""); r.put("assets", new ArrayList<>(assets));
        return r;
    }

    static Map<String, Object> asset(int id, String name, long size) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("id", id); a.put("name", name); a.put("size", size);
        a.put("browser_download_url", "https://github.com/acme/releases/releases/download/x/" + name);
        return a;
    }
}
