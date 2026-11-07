package com.oneofficeautomation.packages;

import com.oneofficeautomation.packages.ConnectorsTest.Call;
import com.oneofficeautomation.packages.ConnectorsTest.Fake;
import com.oneofficeautomation.sdk.ActionContext;
import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.oneofficeautomation.packages.ConnectorsTest.ok;
import static com.oneofficeautomation.packages.RestToolkitTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Box options: overwrite on copy, move and download, and permanent delete, against a local imitation of the Box API. */
class BoxMoreOptionsTest {
    final BoxPackage pkg = new BoxPackage();
    static final String CONFLICT = "{\"type\":\"error\",\"status\":409,\"code\":\"item_name_in_use\",\"message\":\"Item with the same name already exists\"}";

    static long count(Fake f, String method, String path) {
        synchronized (f.calls) { return f.calls.stream().filter(c -> c.method().equals(method) && c.path().equals(path)).count(); }
    }

    @Test void copyOverwriteAddsNewVersionOfExistingFile() throws Exception {
        try (Fake f = new Fake()) {
            f.on("POST /2.0/files/5/copy", c -> status(409, CONFLICT))
             .on("POST /2.0/files/6/copy", c -> status(201, "{\"id\":\"60\"}"))
             .on("GET /2.0/files/5", c -> ok("{\"id\":\"5\",\"name\":\"Report.pdf\"}"))
             .on("GET /2.0/files/5/content", c -> ok("%PDF-new"))
             .on("GET /2.0/folders/77/items", c -> ok("{\"total_count\":2,\"entries\":[{\"type\":\"folder\",\"id\":\"300\",\"name\":\"report.pdf\"},"
                    + "{\"type\":\"file\",\"id\":\"900\",\"name\":\"REPORT.pdf\"}]}"))
             .on("POST /api/2.0/files/900/content", c -> ok("{\"total_count\":1,\"entries\":[{\"type\":\"file\",\"id\":\"900\"}]}"));
            ActionContext ctx = ctx(PackagesTest.tmp());

            assertEquals("900", pkg.copy(ctx, "5", "77", null, TOKEN, null, f.url(), true, f.url()).get("file_id"), "the existing file id is returned");
            assertEquals(Map.of("parent", Map.of("id", "77")), f.last("POST", "/2.0/files/5/copy").json());
            assertEquals("fields=name", f.last("GET", "/2.0/files/5").query(), "the source name is read for the lookup");
            Call up = f.last("POST", "/api/2.0/files/900/content");
            assertEquals("Bearer tok-123", up.auth());
            assertTrue(up.contentType().startsWith("multipart/form-data; boundary="));
            assertTrue(up.text().contains("{\"name\":\"Report.pdf\"}") && up.text().contains("%PDF-new"), "source content uploaded as a new version");

            pkg.copy(ctx, "5", "77", "report.PDF", TOKEN, null, f.url(), true, f.url());
            assertTrue(f.last("POST", "/api/2.0/files/900/content").text().contains("{\"name\":\"report.PDF\"}"), "new_name is used to find the file to replace");

            fails("BOX.ALREADY_EXISTS", () -> pkg.copy(ctx, "5", "77", null, TOKEN, null, f.url(), false, f.url()));
            fails("BOX.ALREADY_EXISTS", () -> pkg.copy(ctx, "5", "77", null, TOKEN, null, f.url()));
            assertEquals("60", pkg.copy(ctx, "6", "77", null, TOKEN, null, f.url(), true, f.url()).get("file_id"), "no conflict: plain copy");
            long calls = f.calls.size();
            fails("BOX.BAD_ID", () -> pkg.copy(ctx, "5", "x7", null, TOKEN, null, f.url(), true, f.url()));
            assertEquals(calls, f.calls.size(), "bad ids are rejected before any request");
        }
    }

    @Test void moveOverwriteTrashesTheFileInTheWay() throws Exception {
        try (Fake f = new Fake()) {
            AtomicBoolean cleared = new AtomicBoolean();
            f.on("PUT /2.0/files/5", c -> cleared.get() ? ok("{\"id\":\"5\"}") : status(409, CONFLICT))
             .on("GET /2.0/files/5", c -> ok("{\"id\":\"5\",\"name\":\"a.pdf\"}"))
             .on("GET /2.0/folders/88/items", c -> ok("{\"total_count\":1,\"entries\":[{\"type\":\"file\",\"id\":\"901\",\"name\":\"A.pdf\"}]}"))
             .on("DELETE /2.0/files/901", c -> { cleared.set(true); return status(204, ""); });
            ActionContext ctx = ctx(PackagesTest.tmp());

            fails("BOX.ALREADY_EXISTS", () -> pkg.move(ctx, "5", "88", TOKEN, null, f.url()));
            assertEquals(0, count(f, "DELETE", "/2.0/files/901"), "nothing is deleted without overwrite");

            pkg.move(ctx, "5", "88", TOKEN, null, f.url(), true);
            assertEquals(1, count(f, "DELETE", "/2.0/files/901"), "the file in the way goes to the trash");
            assertEquals(3, count(f, "PUT", "/2.0/files/5"), "the move is tried again after the conflict");
            assertEquals(Map.of("parent", Map.of("id", "88")), f.last("PUT", "/2.0/files/5").json());
            fails("BOX.BAD_ID", () -> pkg.move(ctx, "5", "", TOKEN, null, f.url(), true));
        }
    }

    @Test void downloadCanRefuseToReplaceALocalFile() throws Exception {
        try (Fake f = new Fake()) {
            f.on("GET /2.0/files/9001/content", c -> ok("new bytes"));
            Path w = PackagesTest.tmp();
            Files.writeString(w.resolve("r.csv"), "old");
            ActionContext ctx = ctx(w);

            fails("BOX.FILE_EXISTS", () -> pkg.download(ctx, "9001", "r.csv", TOKEN, null, f.url(), false));
            assertNull(f.last("GET", "/2.0/files/9001/content"), "nothing is downloaded when the file would not be saved");
            assertEquals("old", Files.readString(w.resolve("r.csv")));

            pkg.download(ctx, "9001", "fresh.csv", TOKEN, null, f.url(), false);
            assertEquals("new bytes", Files.readString(w.resolve("fresh.csv")), "a new path is fine without overwrite");
            pkg.download(ctx, "9001", "r.csv", TOKEN, null, f.url());
            assertEquals("new bytes", Files.readString(w.resolve("r.csv")), "replacing stays the default");
        }
    }

    @Test void deletePermanentlyEmptiesItFromTheTrash() throws Exception {
        try (Fake f = new Fake()) {
            f.on("DELETE /2.0/files/5", c -> status(204, ""))
             .on("DELETE /2.0/files/5/trash", c -> status(204, ""))
             .on("DELETE /2.0/files/6", c -> status(404, "{\"type\":\"error\",\"status\":404,\"code\":\"not_found\",\"message\":\"Not Found\"}"));
            ActionContext ctx = ctx(PackagesTest.tmp());

            pkg.deleteFile(ctx, "5", TOKEN, null, f.url());
            assertNull(f.last("DELETE", "/2.0/files/5/trash"), "default: trash only");
            pkg.deleteFile(ctx, "5", TOKEN, null, f.url(), true);
            assertNotNull(f.last("DELETE", "/2.0/files/5/trash"), "then removed from the trash");
            assertEquals(2, count(f, "DELETE", "/2.0/files/5"));

            fails("BOX.BAD_ID", () -> pkg.deleteFile(ctx, "5/../7", TOKEN, null, f.url(), true));
            assertThrows(com.oneofficeautomation.sdk.ActionException.class, () -> pkg.deleteFile(ctx, "6", TOKEN, null, f.url(), true));
            assertNull(f.last("DELETE", "/2.0/files/6/trash"), "no trash delete when the first delete failed");
        }
    }
}
