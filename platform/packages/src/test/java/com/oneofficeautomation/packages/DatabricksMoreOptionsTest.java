package com.oneofficeautomation.packages;

import com.oneofficeautomation.packages.ConnectorsTest.Call;
import com.oneofficeautomation.packages.ConnectorsTest.Fake;
import com.oneofficeautomation.sdk.ActionContext;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;

import static com.oneofficeautomation.packages.ConnectorsTest.ok;
import static com.oneofficeautomation.packages.RestToolkitTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** New options of the Databricks toolkit: keeping an existing local file when downloading. */
class DatabricksMoreOptionsTest {
    final DatabricksPackage pkg = new DatabricksPackage();

    @Test void downloadOverwriteChoice() throws Exception {
        try (Fake f = new Fake()) {
            f.on("GET /api/2.0/fs/files/Volumes/main/fin/out/result.csv", c -> ok("id,total\n1,5"));
            Path w = PackagesTest.tmp();
            Files.createDirectories(w.resolve("out"));
            Files.writeString(w.resolve("out/result.csv"), "old");
            ActionContext ctx = ctx(w);

            fails("DATABRICKS.FILE_EXISTS", () -> pkg.download(ctx, f.url(), "/Volumes/main/fin/out/result.csv", "out/result.csv", TOKEN, false));
            assertTrue(f.calls.isEmpty(), "nothing is downloaded when the file would be kept");
            assertEquals("old", Files.readString(w.resolve("out/result.csv")));

            Map<String, Object> fresh = pkg.download(ctx, f.url(), "/Volumes/main/fin/out/result.csv", "out/new.csv", TOKEN, false);
            assertEquals("id,total\n1,5", Files.readString(Path.of((String) fresh.get("path"))));
            assertEquals(new BigDecimal(12), fresh.get("size"));
            Call c = f.last("GET", "/api/2.0/fs/files/.*");
            assertEquals("Bearer tok-123", c.auth());

            pkg.download(ctx, f.url(), "/Volumes/main/fin/out/result.csv", "out/result.csv", TOKEN, true);
            assertEquals("id,total\n1,5", Files.readString(w.resolve("out/result.csv")), "overwrite replaces the file");

            Files.writeString(w.resolve("out/result.csv"), "old again");
            pkg.download(ctx, f.url(), "/Volumes/main/fin/out/result.csv", "out/result.csv", TOKEN);
            assertEquals("id,total\n1,5", Files.readString(w.resolve("out/result.csv")), "the earlier call shape still replaces the file");
        }
    }
}
