package com.oneofficeautomation.packages;

import com.oneofficeautomation.packages.ConnectorsTest.Fake;
import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.SecureString;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static com.oneofficeautomation.packages.ConnectorsTest.ok;
import static com.oneofficeautomation.packages.RestToolkitTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** G6 rows of the cloud-data toolkits: Databricks service principals, Snowflake parquet, loading and keyed changes, Kafka CSV. */
class CloudDataG6Test {
    @BeforeAll static void fastPolling() { SnowflakePackage.pollMillis = 5; }

    static final String H = SnowflakePackageTest.H, S = SnowflakePackageTest.S;

    static String result(String rowType, String data, String extra) {
        return "{\"statementHandle\":\"" + H + "\",\"resultSetMetaData\":{\"numRows\":1,\"partitionInfo\":[{}],\"rowType\":[" + rowType + "]},\"data\":" + data
                + (extra == null ? "" : "," + extra) + "}";
    }

    // ---------------- Databricks

    @Test void databricksServicePrincipalGetsATokenOncePerRun() throws Exception {
        try (Fake f = new Fake()) {
            int[] tokens = {0};
            f.on("POST /oidc/v1/token", c -> { tokens[0]++; return ok("{\"access_token\":\"sp-token\",\"expires_in\":3600,\"token_type\":\"Bearer\"}"); })
             .on("GET /api/2.0/clusters/list", c -> ok("{\"clusters\":[]}"));
            DatabricksPackage pkg = new DatabricksPackage();
            ActionContext ctx = ctx(PackagesTest.tmp());
            pkg.listClusters(ctx, f.url(), null, "sp-client", new SecureString("sp-secret", "t"));
            pkg.listClusters(ctx, f.url(), null, "sp-client", new SecureString("sp-secret", "t"));
            assertEquals(1, tokens[0], "the token is reused for the rest of the run");
            var tok = f.last("POST", "/oidc/v1/token");
            assertEquals("Basic " + Base64.getEncoder().encodeToString("sp-client:sp-secret".getBytes(StandardCharsets.UTF_8)), tok.auth());
            assertEquals("grant_type=client_credentials&scope=all-apis", tok.text());
            assertEquals("Bearer sp-token", f.last("GET", "/api/2.0/clusters/list").auth());

            pkg.listClusters(ctx, f.url(), TOKEN, null, null);
            assertEquals("Bearer tok-123", f.last("GET", "/api/2.0/clusters/list").auth(), "a personal access token still works");
            fails("DATABRICKS.NO_CLIENT_SECRET", () -> pkg.listClusters(ctx(PackagesTest.tmp()), f.url(), null, "sp-client", null));

            ActionContext ctx2 = ctx(PackagesTest.tmp());
            pkg.connectDatabricks(ctx2, f.url(), null, null, false, "sp-client", new SecureString("sp-secret", "t"));
            assertEquals(2, tokens[0], "Connect signs in at once");
            pkg.listClusters(ctx2, null, null, null, null);
            assertEquals("Bearer sp-token", f.last("GET", "/api/2.0/clusters/list").auth(), "later steps use the session's service principal");
            assertEquals(2, tokens[0]);
        }
    }

    // ---------------- Snowflake

    @Test void exportToParquetWritesAValidFile() throws Exception {
        String rowType = "{\"name\":\"ID\",\"type\":\"fixed\",\"scale\":0,\"precision\":10},{\"name\":\"NAME\",\"type\":\"text\"},"
                + "{\"name\":\"AMT\",\"type\":\"fixed\",\"scale\":2,\"precision\":12},{\"name\":\"OK\",\"type\":\"boolean\"},{\"name\":\"D\",\"type\":\"date\"},{\"name\":\"R\",\"type\":\"real\"}";
        String data = "[[\"1\",\"Acme Acme Acme Acme Acme Acme\",\"12.50\",\"true\",\"20000\",\"1.5\"],[\"2\",null,null,\"false\",null,null],[\"3\",\"Acme Acme Acme Acme Acme Acme\",\"7.00\",null,\"0\",\"-2\"]]";
        for (String comp : List.of("snappy", "none", "gzip", "auto")) try (Fake f = new Fake()) {
            f.on("POST " + S, c -> ok(result(rowType, data, null)));
            Path dir = PackagesTest.tmp();
            SnowflakePackage pkg = new SnowflakePackage();
            Map<String, Object> r = pkg.exportResults(ctx(dir), f.url(), "SELECT 1", "out.parquet", null, "parquet", true, comp, false, null, null, null, null, 60, TOKEN, "OAUTH");
            assertEquals(new BigDecimal(3), r.get("row_count"));
            byte[] b = Files.readAllBytes(dir.resolve("out.parquet"));
            assertEquals("PAR1", new String(b, 0, 4, StandardCharsets.US_ASCII));
            assertEquals("PAR1", new String(b, b.length - 4, 4, StandardCharsets.US_ASCII));
            int footer = ByteBuffer.wrap(b, b.length - 8, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            assertTrue(footer > 0 && footer < b.length - 12);
            String meta = new String(b, b.length - 8 - footer, footer, StandardCharsets.ISO_8859_1);
            for (String col : List.of("schema", "ID", "NAME", "AMT", "OK", "D", "R")) assertTrue(meta.contains(col), col);
            // the first column chunk starts right after the magic: a page header, then the (compressed) page
            if (!comp.equals("gzip")) {
                String all = new String(b, StandardCharsets.ISO_8859_1);
                int nameAt = all.indexOf("Acme Acme");
                if (comp.equals("none")) assertTrue(nameAt > 0, "text values are stored as they are");
            }
        }
        assertEquals("SNOWFLAKE.BAD_COMPRESSION", fails("SNOWFLAKE.BAD_COMPRESSION", () -> new SnowflakePackage().exportResults(ctx(PackagesTest.tmp()), "http://127.0.0.1:1",
                "SELECT 1", "a.csv", null, "csv", true, "snappy", false, null, null, null, null, 60, TOKEN, "OAUTH")).code());
    }

    @Test void snappyRoundTrips() {
        byte[] in = ("abcdabcdabcdabcd hello hello hello " + "x".repeat(70000) + " tail").getBytes(StandardCharsets.UTF_8);
        byte[] packed = SnowflakePackage.Snappy.compress(in);
        assertTrue(packed.length < in.length / 10);
        assertArrayEquals(in, unsnappy(packed));
        assertArrayEquals(new byte[0], unsnappy(SnowflakePackage.Snappy.compress(new byte[0])));
        byte[] random = new byte[100_000];
        new Random(7).nextBytes(random);
        assertArrayEquals(random, unsnappy(SnowflakePackage.Snappy.compress(random)));
    }

    /** A plain snappy block decoder, to check the encoder. */
    static byte[] unsnappy(byte[] in) {
        int i = 0, shift = 0; long len = 0;
        while (true) { int x = in[i++] & 0xFF; len |= (long) (x & 0x7F) << shift; if (x < 0x80) break; shift += 7; }
        byte[] out = new byte[(int) len];
        int o = 0;
        while (i < in.length) {
            int tag = in[i++] & 0xFF;
            if ((tag & 3) == 0) {
                int n = tag >>> 2;
                if (n == 60) n = in[i++] & 0xFF;
                else if (n == 61) { n = (in[i] & 0xFF) | (in[i + 1] & 0xFF) << 8; i += 2; }
                n++;
                System.arraycopy(in, i, out, o, n); i += n; o += n;
            } else {
                assertEquals(2, tag & 3, "only 2-byte offset copies are written");
                int n = (tag >>> 2) + 1, off = (in[i] & 0xFF) | (in[i + 1] & 0xFF) << 8; i += 2;
                assertTrue(off > 0 && off <= o);
                for (int k = 0; k < n; k++, o++) out[o] = out[o - off];
            }
        }
        assertEquals(len, o);
        return out;
    }

    @Test void loadFilePutsThenCopies() throws Exception {
        try (Fake f = new Fake()) {
            List<String> sql = new ArrayList<>();
            f.on("POST " + S, c -> {
                String st = String.valueOf(c.json().get("statement"));
                sql.add(st);
                if (st.startsWith("COPY")) return ok(result("{\"name\":\"file\",\"type\":\"text\"},{\"name\":\"status\",\"type\":\"text\"},{\"name\":\"rows_loaded\",\"type\":\"fixed\"},{\"name\":\"errors_seen\",\"type\":\"fixed\"}",
                        "[[\"a.csv.gz\",\"PARTIALLY_LOADED\",\"9\",\"1\"]]", null));
                return ok(result("{\"name\":\"status\",\"type\":\"text\"}", "[[\"UPLOADED\"]]", null));
            });
            Path dir = PackagesTest.tmp();
            Files.writeString(dir.resolve("a.csv"), "ID,NAME\n1,x\n");
            SnowflakePackage pkg = new SnowflakePackage();
            Map<String, Object> r = pkg.loadFile(ctx(dir), f.url(), "SALES.PUBLIC.ORDERS", "a.csv", null, "csv", 1, "skip_file_after_percent", 10, 60,
                    null, null, null, null, TOKEN, "OAUTH");
            assertEquals(new BigDecimal(9), r.get("rows_loaded"));
            assertEquals(BigDecimal.ONE, r.get("errors_seen"));
            assertTrue(sql.get(0).startsWith("PUT 'file://"), sql.get(0));
            assertTrue(sql.get(0).contains("' @SALES.PUBLIC.%ORDERS/ooa-load-"), sql.get(0));
            assertTrue(sql.get(1).startsWith("COPY INTO SALES.PUBLIC.ORDERS FROM @SALES.PUBLIC.%ORDERS/ooa-load-"), sql.get(1));
            assertTrue(sql.get(1).endsWith("FILE_FORMAT = (TYPE = CSV SKIP_HEADER = 1 FIELD_OPTIONALLY_ENCLOSED_BY = '\"') ON_ERROR = 'SKIP_FILE_10%' PURGE = TRUE"), sql.get(1));

            sql.clear();
            pkg.loadFile(ctx(dir), f.url(), "ORDERS", "@INBOX/orders/", "", "parquet", 0, "continue", 1, 60, null, null, null, null, TOKEN, "OAUTH");
            assertEquals(List.of("COPY INTO ORDERS FROM @INBOX/orders/ FILE_FORMAT = (TYPE = PARQUET) MATCH_BY_COLUMN_NAME = CASE_INSENSITIVE ON_ERROR = CONTINUE"), sql);
            assertEquals("@%ORDERS", SnowflakePackage.tableStage("ORDERS"));
            assertEquals("@DB.\"my schema\".%\"My T\"", SnowflakePackage.tableStage("DB.\"my schema\".\"My T\""));
            fails("SNOWFLAKE.BAD_STAGE", () -> pkg.loadFile(ctx(dir), f.url(), "ORDERS", "@x'; DROP", null, "csv", 0, null, 1, 60, null, null, null, null, TOKEN, "OAUTH"));
            fails("SNOWFLAKE.BAD_ON_ERROR", () -> pkg.loadFile(ctx(dir), f.url(), "ORDERS", "@x", null, "csv", 0, "skip_file_after_rows", 0, 60, null, null, null, null, TOKEN, "OAUTH"));
        }
    }

    @Test void deleteUpdateAndCallUseBindings() throws Exception {
        try (Fake f = new Fake()) {
            List<Map<String, Object>> bodies = new ArrayList<>();
            f.on("POST " + S, c -> {
                bodies.add(c.json());
                String st = String.valueOf(c.json().get("statement"));
                if (st.startsWith("CALL")) return ok(result("{\"name\":\"CLOSE_MONTH\",\"type\":\"text\"}", "[[\"done\"]]", null));
                return ok(result("{\"name\":\"n\",\"type\":\"fixed\"}", "[[\"2\"]]", "\"stats\":{\"numRowsDeleted\":2,\"numRowsUpdated\":2}"));
            });
            SnowflakePackage pkg = new SnowflakePackage();
            ActionContext ctx = ctx(PackagesTest.tmp());
            Map<String, Object> d = pkg.deleteRows(ctx, f.url(), "ORDERS", "ORDER_ID", List.of(new BigDecimal(1001), "x'y"), null, null, null, null, TOKEN, "OAUTH");
            assertEquals("DELETE FROM ORDERS WHERE ORDER_ID IN (?, ?)", bodies.get(0).get("statement"));
            assertEquals(Map.of("type", "TEXT", "value", "x'y"), ((Map<?, ?>) bodies.get(0).get("bindings")).get("2"));
            assertTrue(((BigDecimal) d.get("rows_changed")).signum() > 0);

            pkg.updateRows(ctx, f.url(), "ORDERS", "ORDER_ID", List.of(7), new LinkedHashMap<>(Map.of("STATUS", "SHIPPED")), null, null, null, null, TOKEN, "OAUTH");
            assertEquals("UPDATE ORDERS SET STATUS = ? WHERE ORDER_ID IN (?)", bodies.get(1).get("statement"));
            assertEquals(2, ((Map<?, ?>) bodies.get(1).get("bindings")).size());
            fails("SNOWFLAKE.NO_KEYS", () -> pkg.deleteRows(ctx, f.url(), "ORDERS", "ORDER_ID", List.of(), null, null, null, null, TOKEN, "OAUTH"));

            Map<String, Object> c = pkg.callProcedure(ctx, f.url(), "SALES.PUBLIC.CLOSE_MONTH", new LinkedHashMap<>(Map.of("MONTH", "2026-10")), 60, null, null, null, null, TOKEN, "OAUTH");
            assertEquals("CALL SALES.PUBLIC.CLOSE_MONTH(MONTH => ?)", bodies.get(bodies.size() - 1).get("statement"));
            assertEquals("done", c.get("result"));
            pkg.callProcedure(ctx, f.url(), "P", List.of("a", 1), 60, null, null, null, null, TOKEN, "OAUTH");
            assertEquals("CALL P(?, ?)", bodies.get(bodies.size() - 1).get("statement"));
            pkg.callProcedure(ctx, f.url(), "P", null, 60, null, null, null, null, TOKEN, "OAUTH");
            assertEquals("CALL P()", bodies.get(bodies.size() - 1).get("statement"));
        }
    }

    // ---------------- Kafka

    @Test void readMessagesCanSaveACsv() throws Exception {
        try (Fake f = new Fake()) {
            String inst = "/consumers/g1/instances/i1";
            f.on("POST /consumers/g1", c -> ok("{\"instance_id\":\"i1\"}"))
             .on("POST " + inst + "/subscription", c -> status(204, ""))
             .on("GET " + inst + "/records", c -> ok("[{\"topic\":\"orders\",\"partition\":0,\"offset\":5,\"key\":\"k1\",\"value\":{\"a\":\"x,y\"}}]"))
             .on("POST " + inst + "/offsets", c -> status(204, ""))
             .on("DELETE " + inst, c -> status(204, ""));
            Path dir = PackagesTest.tmp();
            Map<String, Object> r = new KafkaPackage().read(ctx(dir), f.url(), List.of("orders"), "g1", 1, 1, "json", "earliest", true, "k", TOKEN, null, "out/m.csv");
            assertEquals(dir.resolve("out/m.csv").toString(), r.get("csv_file"));
            List<String> lines = Files.readAllLines(dir.resolve("out/m.csv"));
            assertEquals("topic,partition,offset,key,value", lines.get(0));
            assertTrue(lines.get(1).startsWith("orders,0,5,k1,\""), lines.get(1));
        }
    }

    // ---------------- EC2

    @Test void ec2ListsRunningOnlyForNewSteps() throws Exception {
        var m = Ec2Package.class.getMethods();
        for (var x : m) for (var p : x.getParameters()) {
            var in = p.getAnnotation(com.oneofficeautomation.sdk.Input.class);
            if (in != null && in.name().equals("include_stopped")) { assertEquals("true", in.defaultValue()); assertEquals("false", in.newStepDefault()); return; }
        }
        fail("include_stopped not found");
    }
}
