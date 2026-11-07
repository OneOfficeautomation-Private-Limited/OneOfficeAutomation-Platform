package com.oneofficeautomation.packages;

import com.oneofficeautomation.packages.ConnectorsTest.Fake;
import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

import static com.oneofficeautomation.packages.ConnectorsTest.ok;
import static com.oneofficeautomation.packages.RestToolkitTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Export results to file: every partition streamed to CSV, TSV or JSON lines, optionally gzip-compressed. */
class SnowflakeExportTest {
    final SnowflakePackage pkg = new SnowflakePackage();

    @BeforeAll static void fastPolling() { SnowflakePackage.pollMillis = 5; }

    static final String FIRST = "[[\"1\",\"Acme, Inc.\",\"12.50\",\"true\",null,null],[\"2\",\"Say \\\"hi\\\"\",\"7.00\",\"false\",null,null]]";
    static final String SECOND = "{\"data\":[[\"3\",\"Initech\",null,\"false\",null,null]]}";

    Map<String, Object> export(ActionContext ctx, Fake f, String file, String format, boolean header, String compression) {
        return pkg.exportResults(ctx, f.url(), "SELECT * FROM ORDERS", file, null, format, header, compression, false, null, null, null, null, 60, TOKEN, "OAUTH");
    }

    @Test void exportWritesEveryPartitionToAFile() throws Exception {
        try (Fake f = new Fake()) {
            f.on("POST " + SnowflakePackageTest.S, c -> ok(SnowflakePackageTest.result(FIRST)))
             .on("GET " + SnowflakePackageTest.S + "/" + SnowflakePackageTest.H, c -> ok(SECOND));
            Path dir = PackagesTest.tmp();
            ActionContext ctx = ctx(dir);

            Map<String, Object> r = export(ctx, f, "out/orders.csv", "csv", true, "none");
            assertEquals(new BigDecimal(3), r.get("row_count"));
            assertEquals(String.join("\r\n", "ID,NAME,AMT,OK,D,TS", "1,\"Acme, Inc.\",12.50,true,,", "2,\"Say \"\"hi\"\"\",7.00,false,,", "3,Initech,,false,,", ""),
                    Files.readString(dir.resolve("out/orders.csv")));
            assertEquals("SNOWFLAKE.FILE_EXISTS", assertThrows(ActionException.class, () -> export(ctx, f, "out/orders.csv", "csv", true, "none")).code());
            assertFalse(Files.exists(dir.resolve("out/orders.csv.part")), "no part file is left behind");

            export(ctx, f, "out/orders.jsonl.gz", "json_lines", true, "gzip");
            try (GZIPInputStream in = new GZIPInputStream(Files.newInputStream(dir.resolve("out/orders.jsonl.gz")))) {
                String[] lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n");
                assertEquals(3, lines.length);
                assertTrue(lines[0].startsWith("{\"ID\":1,\"NAME\":\"Acme, Inc.\",\"AMT\":12.5,\"OK\":true"), lines[0]);
            }

            export(ctx, f, "out/orders.tsv", "tsv", false, "none");
            assertEquals("1\tAcme, Inc.\t12.50\ttrue\t\t", Files.readString(dir.resolve("out/orders.tsv")).split("\n")[0], "no header");
            assertEquals("SNOWFLAKE.BAD_FORMAT", assertThrows(ActionException.class, () -> export(ctx, f, "x.xml", "xml", true, "none")).code());
        }
    }
}
