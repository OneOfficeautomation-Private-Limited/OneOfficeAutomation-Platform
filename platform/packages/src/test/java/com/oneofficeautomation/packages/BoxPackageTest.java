package com.oneofficeautomation.packages;

import com.oneofficeautomation.packages.ConnectorsTest.Call;
import com.oneofficeautomation.packages.ConnectorsTest.Fake;
import com.oneofficeautomation.sdk.ActionContext;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;

import static com.oneofficeautomation.packages.ConnectorsTest.ok;
import static com.oneofficeautomation.packages.ConnectorsTest.q;
import static com.oneofficeautomation.packages.RestToolkitTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Box toolkit against a local imitation of the Box Content API. */
class BoxPackageTest {
    final BoxPackage pkg = new BoxPackage();

    @Test void foldersCreateReuseListPagingAndDelete() throws Exception {
        try (Fake f = new Fake()) {
            String api = f.url();
            f.on("POST /2.0/folders", c -> c.text().contains("\"Invoices\"")
                    ? status(409, "{\"type\":\"error\",\"status\":409,\"code\":\"item_name_in_use\",\"message\":\"Item with the same name already exists\"}")
                    : status(201, "{\"type\":\"folder\",\"id\":\"111\"}"))
             .on("GET /2.0/folders/0/items", c -> q(c).contains("offset=0")
                    ? ok("{\"total_count\":3,\"entries\":[{\"type\":\"file\",\"id\":\"5\",\"name\":\"a.pdf\",\"size\":10,\"modified_at\":\"2026-10-01T00:00:00Z\",\"parent\":{\"id\":\"0\"}},"
                       + "{\"type\":\"folder\",\"id\":\"222\",\"name\":\"invoices\",\"parent\":{\"id\":\"0\"}}]}")
                    : ok("{\"total_count\":3,\"entries\":[{\"type\":\"web_link\",\"id\":\"7\",\"name\":\"Portal\"}]}"))
             .on("DELETE /2.0/folders/222", c -> status(204, ""));
            ActionContext ctx = ctx(PackagesTest.tmp());
            assertEquals("111", pkg.createFolder(ctx, "Reports", "0", true, TOKEN, null, api).get("folder_id"));
            Call c = f.last("POST", "/2.0/folders");
            assertEquals("Bearer tok-123", c.auth());
            assertEquals(Map.of("name", "Reports", "parent", Map.of("id", "0")), c.json());
            assertEquals("222", pkg.createFolder(ctx, "Invoices", "0", true, TOKEN, null, api).get("folder_id"), "existing folder reused by name");
            fails("BOX.ALREADY_EXISTS", () -> pkg.createFolder(ctx, "Invoices", "0", false, TOKEN, null, api));

            Map<String, Object> l = pkg.listFolder(ctx, "0", 1000, TOKEN, "999", api);
            assertEquals(new BigDecimal(3), l.get("count"), "second page read by offset");
            assertEquals(List.of("id", "type", "name", "size", "modified_at", "parent_id"), cols(l.get("table")));
            assertEquals(List.of("5", "file", "a.pdf", new BigDecimal(10), "2026-10-01T00:00:00Z", "0"), rows(l.get("table")).get(0));
            assertEquals("999", f.last("GET", "/2.0/folders/0/items").header("As-User"));
            assertTrue(q(f.last("GET", "/2.0/folders/0/items")).contains("offset=2"));

            pkg.deleteFolder(ctx, "222", true, TOKEN, null, api);
            assertEquals("recursive=true", f.last("DELETE", "/2.0/folders/222").query());
            fails("BOX.BAD_ID", () -> pkg.deleteFolder(ctx, "0", true, TOKEN, null, api));
            fails("BOX.BAD_ID", () -> pkg.deleteFolder(ctx, "../1", true, TOKEN, null, api));
        }
    }

    @Test void uploadNewAndAsNewVersionThenDownload() throws Exception {
        try (Fake f = new Fake()) {
            f.on("POST /api/2.0/files/content", c -> c.text().contains("dup.txt")
                    ? status(409, "{\"type\":\"error\",\"status\":409,\"code\":\"item_name_in_use\",\"message\":\"Item with the same name already exists\"}")
                    : status(201, "{\"total_count\":1,\"entries\":[{\"type\":\"file\",\"id\":\"9001\"}]}"))
             .on("POST /api/2.0/files/9002/content", c -> ok("{\"total_count\":1,\"entries\":[{\"type\":\"file\",\"id\":\"9002\"}]}"))
             .on("GET /2.0/folders/42/items", c -> ok("{\"total_count\":1,\"entries\":[{\"type\":\"file\",\"id\":\"9002\",\"name\":\"DUP.txt\"}]}"))
             .on("GET /2.0/files/9001/content", c -> ok("downloaded bytes"));
            Path w = PackagesTest.tmp();
            Files.writeString(w.resolve("report.csv"), "a,b\n1,2");
            Files.writeString(w.resolve("dup.txt"), "v2");
            ActionContext ctx = ctx(w);
            assertEquals("9001", pkg.upload(ctx, "report.csv", "42", null, false, TOKEN, null, f.url(), f.url()).get("file_id"));
            Call up = f.last("POST", "/api/2.0/files/content");
            assertTrue(up.contentType().startsWith("multipart/form-data; boundary="));
            assertTrue(up.text().contains("name=\"attributes\"") && up.text().contains("{\"name\":\"report.csv\",\"parent\":{\"id\":\"42\"}}"));
            assertTrue(up.text().contains("filename=\"report.csv\"") && up.text().contains("a,b\n1,2"));

            fails("BOX.ALREADY_EXISTS", () -> pkg.upload(ctx, "dup.txt", "42", null, false, TOKEN, null, f.url(), f.url()));
            assertEquals("9002", pkg.upload(ctx, "dup.txt", "42", null, true, TOKEN, null, f.url(), f.url()).get("file_id"));
            assertTrue(f.last("POST", "/api/2.0/files/9002/content").text().contains("v2"), "uploaded as a new version of the existing file");
            fails("FILE.NOT_FOUND", () -> pkg.upload(ctx, "missing.txt", "42", null, false, TOKEN, null, f.url(), f.url()));

            Map<String, Object> d = pkg.download(ctx, "9001", "out/sub/r.csv", TOKEN, null, f.url());
            assertEquals("downloaded bytes", Files.readString(Path.of((String) d.get("path"))));
            assertTrue(Files.exists(w.resolve("out/sub/r.csv")));
        }
    }

    @Test void copyMoveRenameDeleteAndInfo() throws Exception {
        try (Fake f = new Fake()) {
            f.on("POST /2.0/files/5/copy", c -> status(201, "{\"id\":\"6\"}"))
             .on("PUT /2.0/files/5", c -> ok("{\"id\":\"5\"}"))
             .on("DELETE /2.0/files/5", c -> status(204, ""))
             .on("GET /2.0/files/5", c -> ok("{\"id\":\"5\",\"name\":\"a.pdf\",\"size\":10,\"sha1\":\"abc\",\"parent\":{\"id\":\"42\",\"name\":\"Inbox\"},"
                    + "\"owned_by\":{\"login\":\"ann@acme.com\"},\"shared_link\":null}"));
            ActionContext ctx = ctx(PackagesTest.tmp());
            assertEquals("6", pkg.copy(ctx, "5", "77", "copy.pdf", TOKEN, null, f.url()).get("file_id"));
            assertEquals(Map.of("parent", Map.of("id", "77"), "name", "copy.pdf"), f.last("POST", "/2.0/files/5/copy").json());
            pkg.move(ctx, "5", "88", TOKEN, null, f.url());
            assertEquals(Map.of("parent", Map.of("id", "88")), f.last("PUT", "/2.0/files/5").json());
            pkg.rename(ctx, "5", "b.pdf", TOKEN, null, f.url());
            assertEquals(Map.of("name", "b.pdf"), f.last("PUT", "/2.0/files/5").json());
            pkg.deleteFile(ctx, "5", TOKEN, null, f.url());
            assertNotNull(f.last("DELETE", "/2.0/files/5"));
            Map<?, ?> info = (Map<?, ?>) pkg.info(ctx, "5", TOKEN, null, f.url()).get("info");
            assertEquals("Inbox", info.get("parent_name"));
            assertEquals("ann@acme.com", info.get("owner"));
            assertNull(info.get("shared_link"));
            assertTrue(q(f.last("GET", "/2.0/files/5")).startsWith("fields=id,name,size"));
            fails("BOX.BAD_ID", () -> pkg.copy(ctx, "abc", "1", null, TOKEN, null, f.url()));
        }
    }

    @Test void searchSharedLinksAndCollaborations() throws Exception {
        try (Fake f = new Fake()) {
            f.on("GET /2.0/search", c -> ok("{\"total_count\":1,\"entries\":[{\"type\":\"file\",\"id\":\"5\",\"name\":\"invoice 2026.pdf\"}]}"))
             .on("PUT /2.0/files/5", c -> ok("{\"id\":\"5\",\"shared_link\":" + (c.text().contains("null") ? "null" : "{\"url\":\"https://app.box.com/s/abc\",\"download_url\":\"https://app.box.com/shared/static/abc.pdf\"}") + "}"))
             .on("POST /2.0/collaborations", c -> status(201, "{\"id\":\"c1\"}".replace("c1", "3001")))
             .on("GET /2.0/folders/42/collaborations", c -> q(c).contains("marker=m2")
                    ? ok("{\"entries\":[{\"id\":\"3002\",\"role\":\"viewer\",\"status\":\"pending\",\"accessible_by\":{\"type\":\"group\",\"name\":\"Finance\"}}]}")
                    : ok("{\"next_marker\":\"m2\",\"entries\":[{\"id\":\"3001\",\"role\":\"editor\",\"status\":\"accepted\",\"accessible_by\":{\"type\":\"user\",\"name\":\"Ann\",\"login\":\"ann@acme.com\"}}]}"))
             .on("DELETE /2.0/collaborations/3001", c -> status(204, ""));
            ActionContext ctx = ctx(PackagesTest.tmp());
            Map<String, Object> s = pkg.search(ctx, "invoice 2026", "file", "42", ".pdf, docx", 50, TOKEN, null, f.url());
            assertEquals(new BigDecimal(1), s.get("count"));
            String sq = q(f.last("GET", "/2.0/search"));
            assertTrue(sq.startsWith("query=invoice 2026&type=file&ancestor_folder_ids=42&file_extensions=pdf,docx&fields="), sq);
            assertTrue(sq.endsWith("limit=50&offset=0"), sq);

            Map<String, Object> link = pkg.sharedLink(ctx, "file", "5", "open", false, LocalDateTime.of(2026, 12, 31, 18, 0), TOKEN, null, f.url());
            assertEquals("https://app.box.com/s/abc", link.get("url"));
            assertEquals("https://app.box.com/shared/static/abc.pdf", link.get("download_url"));
            Call put = f.last("PUT", "/2.0/files/5");
            assertEquals("fields=shared_link", put.query());
            assertEquals(Map.of("shared_link", Map.of("access", "open", "permissions", Map.of("can_download", false), "unshared_at", "2026-12-31T18:00:00Z")), put.json());
            pkg.removeSharedLink(ctx, "file", "5", TOKEN, null, f.url());
            assertEquals("{\"shared_link\":null}", f.last("PUT", "/2.0/files/5").text());
            fails("BOX.BAD_ITEM_TYPE", () -> pkg.removeSharedLink(ctx, "drive", "5", TOKEN, null, f.url()));

            assertEquals("3001", pkg.addCollaborator(ctx, "folder", "42", "ann@acme.com", null, "editor", false, TOKEN, null, f.url()).get("collaboration_id"));
            Call col = f.last("POST", "/2.0/collaborations");
            assertEquals("notify=false", col.query());
            assertEquals(Map.of("item", Map.of("type", "folder", "id", "42"), "accessible_by", Map.of("type", "user", "login", "ann@acme.com"), "role", "editor"), col.json());
            pkg.addCollaborator(ctx, "folder", "42", null, "555", "viewer", true, TOKEN, null, f.url());
            assertEquals(Map.of("type", "group", "id", "555"), f.last("POST", "/2.0/collaborations").json().get("accessible_by"));
            fails("BOX.NO_COLLABORATOR", () -> pkg.addCollaborator(ctx, "folder", "42", null, null, "viewer", true, TOKEN, null, f.url()));

            Map<String, Object> lc = pkg.listCollaborators(ctx, "folder", "42", TOKEN, null, f.url());
            assertEquals(2, rows(lc.get("table")).size(), "follows next_marker");
            assertEquals(Arrays.asList("3001", "Ann", "ann@acme.com", "user", "editor", "accepted"), rows(lc.get("table")).get(0));
            pkg.removeCollaborator(ctx, "3001", TOKEN, null, f.url());
            assertNotNull(f.last("DELETE", "/2.0/collaborations/3001"));
        }
    }

    @Test void errorsThroughAnAgentRun() throws Exception {
        try (Fake f = new Fake()) {
            f.on("GET /2.0/files/404", c -> status(404, "{\"type\":\"error\",\"status\":404,\"code\":\"not_found\",\"message\":\"Not Found\"}"))
             .on("GET /2.0/files/500", c -> status(502, "{\"type\":\"error\",\"message\":\"Bad gateway\"}"))
             .on("GET /2.0/files/401", c -> status(401, ""));
            String step = "[{\"id\":\"i\",\"action\":\"ooa.box/get_file_info\",\"args\":{\"file_id\":\"ID\",\"access_token\":\"$tok\",\"base_url\":\"" + f.url() + "\"},\"out\":{\"info\":\"info\"}}]";
            String out = "{\"name\":\"info\",\"type\":\"Dictionary\",\"direction\":\"output\"}";
            assertEquals("BOX.NOT_FOUND", flow(PackagesTest.tmp(), out, step.replace("ID", "404")).result().error().get("code"));
            FlowRun bad = flow(PackagesTest.tmp(), out, step.replace("ID", "500"));
            assertEquals("BOX.SERVER_ERROR", bad.result().error().get("code"));
            assertEquals(true, bad.result().error().get("retryable"));
            assertFalse(bad.events().contains("tok-123"));
            assertEquals("BOX.AUTH_FAILED", flow(PackagesTest.tmp(), out, step.replace("ID", "401")).result().error().get("code"));
        }
    }
}
