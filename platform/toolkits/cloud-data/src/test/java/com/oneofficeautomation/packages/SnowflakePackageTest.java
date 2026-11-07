package com.oneofficeautomation.packages;

import com.oneofficeautomation.packages.ConnectorsTest.Call;
import com.oneofficeautomation.packages.ConnectorsTest.Fake;
import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.ActionContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.oneofficeautomation.packages.ConnectorsTest.ok;
import static com.oneofficeautomation.packages.ConnectorsTest.q;
import static com.oneofficeautomation.packages.RestToolkitTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Snowflake toolkit against a local imitation of the SQL API v2. */
class SnowflakePackageTest {
    static final String H = "01b2c3d4-0000-1234-0000-00a1b2c3d4e5";
    static final String S = "/api/v2/statements";
    static final String META = "\"resultSetMetaData\":{\"numRows\":3,\"format\":\"jsonv2\",\"partitionInfo\":[{\"rowCount\":2},{\"rowCount\":1}],\"rowType\":["
            + "{\"name\":\"ID\",\"type\":\"fixed\",\"scale\":0},{\"name\":\"NAME\",\"type\":\"text\"},{\"name\":\"AMT\",\"type\":\"fixed\",\"scale\":2},"
            + "{\"name\":\"OK\",\"type\":\"boolean\"},{\"name\":\"D\",\"type\":\"date\"},{\"name\":\"TS\",\"type\":\"timestamp_ntz\"}]}";
    final SnowflakePackage pkg = new SnowflakePackage();

    @BeforeAll static void fastPolling() { SnowflakePackage.pollMillis = 5; }

    static String result(String partition0) { return "{\"statementHandle\":\"" + H + "\",\"code\":\"090001\",\"message\":\"Statement executed successfully.\"," + META + ",\"data\":" + partition0 + "}"; }

    @Test void executeWaitsReadsAllPartitionsAndTypesValues() throws Exception {
        try (Fake f = new Fake()) {
            AtomicInteger polls = new AtomicInteger();
            f.on("POST " + S, c -> status(202, "{\"code\":\"333334\",\"message\":\"Asynchronous execution in progress.\",\"statementHandle\":\"" + H + "\"}"))
             .on("GET " + S + "/" + H, c -> {
                 if ("partition=1".equals(c.query())) return ok("{\"data\":[[\"3\",\"Initech\",null,\"false\",null,null]]}");
                 return polls.incrementAndGet() < 2 ? status(202, "{\"code\":\"333334\",\"statementHandle\":\"" + H + "\"}")
                         : ok(result("[[\"1\",\"Acme\",\"12.50\",\"true\",\"20381\",\"1760918400.123000000\"],[\"2\",\"Globex\",\"7.00\",\"false\",\"0\",\"0.000000000\"]]"));
             });
            ActionContext ctx = ctx(PackagesTest.tmp());
            Map<String, Object> r = pkg.execute(ctx, f.url(), "SELECT * FROM ORDERS WHERE REGION = ? AND AMT > ? AND OK = ?", List.of("EMEA", new BigDecimal("10"), true),
                    "WH_XS", "SALES", "PUBLIC", "ANALYST", 60, 1000, TOKEN, "KEYPAIR_JWT");
            assertEquals("succeeded", r.get("status"));
            assertEquals(H, r.get("statement_handle"));
            assertEquals(new BigDecimal(3), r.get("row_count"));
            assertEquals(List.of("ID", "NAME", "AMT", "OK", "D", "TS"), cols(r.get("table")));
            List<Object> first = rows(r.get("table")).get(0);
            assertEquals(new BigDecimal(1), first.get(0));
            assertEquals(new BigDecimal("12.50"), first.get(2));
            assertEquals(true, first.get(3));
            assertEquals(LocalDate.ofEpochDay(20381).toString(), first.get(4));
            assertEquals(LocalDateTime.ofEpochSecond(1760918400L, 123_000_000, ZoneOffset.UTC).toString(), first.get(5));
            assertEquals(Arrays.asList(new BigDecimal(3), "Initech", null, false, null, null), rows(r.get("table")).get(2), "second partition fetched");

            Call post = f.last("POST", S);
            assertEquals("Bearer tok-123", post.auth());
            assertEquals("KEYPAIR_JWT", post.header("X-Snowflake-Authorization-Token-Type"));
            assertTrue(q(post).matches("requestId=[0-9a-f-]{36}"), q(post));
            Map<String, Object> b = post.json();
            assertEquals("WH_XS", b.get("warehouse"));
            assertEquals("ANALYST", b.get("role"));
            assertEquals(Map.of("1", Map.of("type", "TEXT", "value", "EMEA"), "2", Map.of("type", "FIXED", "value", "10"), "3", Map.of("type", "BOOLEAN", "value", "true")),
                    b.get("bindings"));
            assertEquals(1, f.calls.stream().filter(c -> "partition=1".equals(c.query())).count());
        }
    }

    @Test void stillRunningStatusCancelAndFetch() throws Exception {
        try (Fake f = new Fake()) {
            String[] state = {"running"};
            f.on("POST " + S, c -> status(202, "{\"statementHandle\":\"" + H + "\"}"))
             .on("POST " + S + "/" + H + "/cancel", c -> ok("{\"code\":\"000604\",\"message\":\"successfully canceled\"}"))
             .on("GET " + S + "/" + H, c -> switch (state[0]) {
                 case "running" -> status(202, "{\"statementHandle\":\"" + H + "\",\"message\":\"Asynchronous execution in progress.\"}");
                 case "failed" -> status(422, "{\"code\":\"002003\",\"message\":\"SQL compilation error: Object 'NOPE' does not exist.\",\"statementHandle\":\"" + H + "\"}");
                 default -> "partition=1".equals(c.query()) ? ok("{\"data\":[[\"3\",\"c\",null,null,null,null]]}") : ok(result("[[\"1\",\"a\",null,null,null,null],[\"2\",\"b\",null,null,null,null]]"));
             });
            ActionContext ctx = ctx(PackagesTest.tmp());
            Map<String, Object> r = pkg.execute(ctx, f.url(), "CALL LONG_JOB()", null, null, null, null, null, 0, 10, TOKEN, "OAUTH");
            assertEquals("running", r.get("status"));
            assertEquals(H, r.get("statement_handle"));
            assertEquals("running", pkg.status(ctx, f.url(), H, TOKEN, "OAUTH").get("status"));
            fails("SNOWFLAKE.STILL_RUNNING", () -> pkg.fetch(ctx, f.url(), H, 10, TOKEN, "OAUTH"));
            pkg.cancel(ctx, f.url(), H, TOKEN, "OAUTH");
            assertNotNull(f.last("POST", S + "/" + H + "/cancel"));

            state[0] = "done";
            Map<String, Object> st = pkg.status(ctx, f.url(), H, TOKEN, "OAUTH");
            assertEquals("succeeded", st.get("status"));
            assertEquals(new BigDecimal(3), st.get("total_rows"));
            Map<String, Object> fr = pkg.fetch(ctx, f.url(), H, 2, TOKEN, "OAUTH");
            assertEquals(new BigDecimal(2), fr.get("row_count"), "max_rows respected");
            assertEquals(new BigDecimal(3), fr.get("total_rows"));
            assertEquals(0, f.calls.stream().filter(c -> "partition=1".equals(c.query())).count(), "no extra partition read once max_rows is reached");

            state[0] = "failed";
            Map<String, Object> failed = pkg.status(ctx, f.url(), H, TOKEN, "OAUTH");
            assertEquals("failed", failed.get("status"));
            assertTrue(String.valueOf(failed.get("message")).contains("does not exist"));
            fails("SNOWFLAKE.STATEMENT_FAILED", () -> pkg.fetch(ctx, f.url(), H, 10, TOKEN, "OAUTH"));
            fails("SNOWFLAKE.BAD_HANDLE", () -> pkg.status(ctx, f.url(), "../../x", TOKEN, "OAUTH"));
            fails("SNOWFLAKE.NO_SQL", () -> pkg.execute(ctx, f.url(), " ", null, null, null, null, null, 0, 10, TOKEN, "OAUTH"));
        }
    }

    @Test void catalogListingsUseSafeNames() throws Exception {
        try (Fake f = new Fake()) {
            String showMeta = "\"resultSetMetaData\":{\"numRows\":2,\"partitionInfo\":[{\"rowCount\":2}],\"rowType\":[{\"name\":\"created_on\",\"type\":\"timestamp_ltz\"},{\"name\":\"name\",\"type\":\"text\"}]}";
            f.on("POST " + S, c -> ok("{\"statementHandle\":\"" + H + "\"," + showMeta + ",\"data\":[[\"0\",\"SALES\"],[\"0\",\"HR\"]]}"));
            ActionContext ctx = ctx(PackagesTest.tmp());
            Map<String, Object> d = pkg.listDatabases(ctx, f.url(), "SYSADMIN", TOKEN, "OAUTH");
            assertEquals(List.of("SALES", "HR"), d.get("names"));
            assertEquals("SHOW DATABASES", f.last("POST", S).json().get("statement"));
            assertEquals("SYSADMIN", f.last("POST", S).json().get("role"));
            pkg.listSchemas(ctx, f.url(), "sales", null, TOKEN, "OAUTH");
            assertEquals("SHOW SCHEMAS IN DATABASE sales", f.last("POST", S).json().get("statement"));
            pkg.listTables(ctx, f.url(), "My DB", "PUBLIC", null, TOKEN, "OAUTH");
            assertEquals("SHOW TABLES IN SCHEMA \"My DB\".PUBLIC", f.last("POST", S).json().get("statement"));
            pkg.listColumns(ctx, f.url(), "SALES.PUBLIC.\"Order\"\"s\"", null, TOKEN, "OAUTH");
            assertEquals("DESCRIBE TABLE SALES.PUBLIC.\"Order\"\"s\"", f.last("POST", S).json().get("statement"));
            pkg.listWarehouses(ctx, f.url(), null, TOKEN, "OAUTH");
            assertEquals("SHOW WAREHOUSES", f.last("POST", S).json().get("statement"));
            pkg.listSchemas(ctx, f.url(), "x; DROP DATABASE prod", null, TOKEN, "OAUTH");
            assertEquals("SHOW SCHEMAS IN DATABASE \"x; DROP DATABASE prod\"", f.last("POST", S).json().get("statement"), "unusual names are quoted, never spliced in raw");
        }
    }

    @Test void insertRowsBatchesWithBindings() throws Exception {
        try (Fake f = new Fake()) {
            f.on("POST " + S, c -> {
                int n = ((Map<?, ?>) c.json().get("bindings")).size() / 2;
                return ok("{\"statementHandle\":\"" + H + "\",\"stats\":{\"numRowsInserted\":" + n + "},\"resultSetMetaData\":{\"numRows\":1,\"rowType\":[{\"name\":\"number of rows inserted\",\"type\":\"fixed\"}]},\"data\":[[\"" + n + "\"]]}");
            });
            ActionContext ctx = ctx(PackagesTest.tmp());
            Map<String, Object> data = Values.table(List.of("ID", "Customer Name"), new ArrayList<>(List.of(
                    new ArrayList<>(Arrays.asList(new BigDecimal(1), "Acme")), new ArrayList<>(Arrays.asList(new BigDecimal(2), null)), new ArrayList<>(Arrays.asList(new BigDecimal(3), "Initech")))));
            Map<String, Object> r = pkg.insert(ctx, f.url(), "SALES.PUBLIC.CUSTOMERS", data, 2, "WH", null, null, null, TOKEN, "OAUTH");
            assertEquals(new BigDecimal(3), r.get("rows_inserted"));
            List<Call> posts = f.calls.stream().filter(c -> c.method().equals("POST")).toList();
            assertEquals(2, posts.size(), "two batches of at most 2 rows");
            assertEquals("INSERT INTO SALES.PUBLIC.CUSTOMERS (ID, \"Customer Name\") VALUES (?, ?), (?, ?)", posts.get(0).json().get("statement"));
            Map<?, ?> binds = (Map<?, ?>) posts.get(0).json().get("bindings");
            assertEquals(Map.of("type", "FIXED", "value", "2"), binds.get("3"));
            Map<String, Object> nullValue = new HashMap<>(); nullValue.put("type", "TEXT"); nullValue.put("value", null);
            assertEquals(nullValue, binds.get("4"));
            assertEquals("INSERT INTO SALES.PUBLIC.CUSTOMERS (ID, \"Customer Name\") VALUES (?, ?)", posts.get(1).json().get("statement"));
            fails("SNOWFLAKE.NO_COLUMNS", () -> pkg.insert(ctx, f.url(), "T", Values.table(List.of(), new ArrayList<>()), 10, null, null, null, null, TOKEN, "OAUTH"));
        }
    }

    @Test void httpErrorsThroughAnAgentRun() throws Exception {
        try (Fake f = new Fake()) {
            f.on("POST " + S, c -> c.text().contains("BUSY") ? status(429, "{\"message\":\"Too many requests\"}")
                    : c.text().contains("DOWN") ? status(503, "{\"message\":\"Service unavailable\"}")
                    : status(401, "{\"code\":\"390303\",\"message\":\"Invalid OAuth access token.\"}"));
            String step = "[{\"id\":\"q\",\"action\":\"ooa.snowflake/execute_statement\",\"args\":{\"account_url\":\"" + f.url() + "\",\"sql\":\"SQL\",\"token\":\"$tok\"}}]";
            FlowRun auth = flow(PackagesTest.tmp(), "", step.replace("SQL", "SELECT 1"));
            assertEquals("SNOWFLAKE.AUTH_FAILED", auth.result().error().get("code"));
            assertTrue(String.valueOf(auth.result().error().get("message")).contains("Invalid OAuth access token"));
            assertEquals("OAUTH", f.last("POST", S).header("X-Snowflake-Authorization-Token-Type"), "default token type");
            assertFalse(auth.events().contains("tok-123"));
            FlowRun busy = flow(PackagesTest.tmp(), "", step.replace("SQL", "SELECT 'BUSY'"));
            assertEquals("SNOWFLAKE.RATE_LIMITED", busy.result().error().get("code"));
            assertEquals(true, busy.result().error().get("retryable"));
            FlowRun down = flow(PackagesTest.tmp(), "", step.replace("SQL", "SELECT 'DOWN'"));
            assertEquals("SNOWFLAKE.SERVER_ERROR", down.result().error().get("code"));
            assertEquals(true, down.result().error().get("retryable"));
        }
    }
}
