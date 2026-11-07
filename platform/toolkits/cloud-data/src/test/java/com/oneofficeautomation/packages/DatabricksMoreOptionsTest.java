package com.oneofficeautomation.packages;

import com.oneofficeautomation.packages.ConnectorsTest.Call;
import com.oneofficeautomation.packages.ConnectorsTest.Fake;
import com.oneofficeautomation.sdk.ActionContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.oneofficeautomation.packages.ConnectorsTest.ok;
import static com.oneofficeautomation.packages.RestToolkitTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** New options of the Databricks toolkit: keeping an existing local file when downloading, and waiting for results. */
class DatabricksMoreOptionsTest {
    final DatabricksPackage pkg = new DatabricksPackage();
    static final String SID = DatabricksPackageTest.SID, SQL = DatabricksPackageTest.SQL;

    @BeforeAll static void fastPolling() { DatabricksPackage.pollMillis = 5; }

    @Test void fetchResultsWaitsForARunningStatement() throws Exception {
        try (Fake f = new Fake()) {
            AtomicInteger polls = new AtomicInteger();
            int[] finishAfter = {3};
            f.on("GET " + SQL + "/" + SID + "/result/chunks/1", c -> ok("{\"chunk_index\":1,\"data_array\":[[\"3\",\"AMER\",\"5\",null]]}"))
             .on("GET " + SQL + "/" + SID, c -> polls.incrementAndGet() < finishAfter[0]
                     ? ok("{\"statement_id\":\"" + SID + "\",\"status\":{\"state\":\"RUNNING\"}}") : ok(DatabricksPackageTest.done()));
            ActionContext ctx = ctx(PackagesTest.tmp());

            Map<String, Object> r = pkg.fetchResults(ctx, f.url(), SID, 10, TOKEN, 30);
            assertEquals(new BigDecimal(3), r.get("row_count"), "rows are read once the statement finishes");
            assertEquals(3, polls.get(), "status is polled until it is no longer running");

            polls.set(0);
            fails("DATABRICKS.STILL_RUNNING", () -> pkg.fetchResults(ctx, f.url(), SID, 10, TOKEN));
            assertEquals(1, polls.get(), "without wait_seconds it checks once, as before");

            polls.set(0);
            finishAfter[0] = Integer.MAX_VALUE;
            fails("DATABRICKS.STILL_RUNNING", () -> pkg.fetchResults(ctx, f.url(), SID, 10, TOKEN, 1));
            assertTrue(polls.get() > 1, "polled during the wait before giving up");
        }
    }

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
