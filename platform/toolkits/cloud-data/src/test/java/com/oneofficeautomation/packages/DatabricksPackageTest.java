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
import static com.oneofficeautomation.packages.ConnectorsTest.q;
import static com.oneofficeautomation.packages.RestToolkitTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Databricks toolkit against a local imitation of the SQL, Jobs, Clusters, Files and DBFS APIs. */
class DatabricksPackageTest {
    static final String SID = "01ef-5a1b-2c3d-4e5f-abcdef012345";
    static final String SQL = "/api/2.0/sql/statements";
    static final String MANIFEST = "\"manifest\":{\"total_row_count\":3,\"total_chunk_count\":2,\"schema\":{\"columns\":["
            + "{\"name\":\"id\",\"type_name\":\"BIGINT\",\"position\":0},{\"name\":\"region\",\"type_name\":\"STRING\",\"position\":1},"
            + "{\"name\":\"amount\",\"type_name\":\"DECIMAL\",\"position\":2},{\"name\":\"open\",\"type_name\":\"BOOLEAN\",\"position\":3}]}}";
    final DatabricksPackage pkg = new DatabricksPackage();

    @BeforeAll static void fastPolling() { DatabricksPackage.pollMillis = 5; }

    static String done() {
        return "{\"statement_id\":\"" + SID + "\",\"status\":{\"state\":\"SUCCEEDED\"}," + MANIFEST + ",\"result\":{\"chunk_index\":0,\"row_count\":2,"
                + "\"data_array\":[[\"1\",\"EMEA\",\"12.50\",\"true\"],[\"2\",\"APAC\",null,\"false\"]],\"next_chunk_index\":1,"
                + "\"next_chunk_internal_link\":\"" + SQL + "/" + SID + "/result/chunks/1\"}}";
    }

    @Test void executeSqlPollsAndFollowsChunks() throws Exception {
        try (Fake f = new Fake()) {
            AtomicInteger polls = new AtomicInteger();
            f.on("POST " + SQL, c -> ok("{\"statement_id\":\"" + SID + "\",\"status\":{\"state\":\"PENDING\"}}"))
             .on("GET " + SQL + "/" + SID + "/result/chunks/1", c -> ok("{\"chunk_index\":1,\"data_array\":[[\"3\",\"AMER\",\"5\",null]]}"))
             .on("GET " + SQL + "/" + SID, c -> polls.incrementAndGet() < 2 ? ok("{\"statement_id\":\"" + SID + "\",\"status\":{\"state\":\"RUNNING\"}}") : ok(done()));
            ActionContext ctx = ctx(PackagesTest.tmp());
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("region", "EMEA");
            params.put("min", new BigDecimal("10"));
            Map<String, Object> r = pkg.executeSql(ctx, f.url() + "/", "abc123def456", "SELECT * FROM sales.orders WHERE region = :region AND amount > :min", params,
                    "main", "sales", 60, 1000, TOKEN);
            assertEquals("SUCCEEDED", r.get("status"));
            assertEquals(SID, r.get("statement_id"));
            assertEquals(new BigDecimal(3), r.get("row_count"));
            assertEquals(List.of("id", "region", "amount", "open"), cols(r.get("table")));
            assertEquals(Arrays.asList(new BigDecimal(1), "EMEA", new BigDecimal("12.50"), true), rows(r.get("table")).get(0));
            assertEquals(Arrays.asList(new BigDecimal(3), "AMER", new BigDecimal(5), null), rows(r.get("table")).get(2), "second chunk fetched");
            Call post = f.last("POST", SQL);
            assertEquals("Bearer tok-123", post.auth());
            Map<String, Object> b = post.json();
            assertEquals("abc123def456", b.get("warehouse_id"));
            assertEquals("main", b.get("catalog"));
            assertEquals("50s", b.get("wait_timeout"));
            assertEquals("CONTINUE", b.get("on_wait_timeout"));
            assertEquals("JSON_ARRAY", b.get("format"));
            assertEquals(List.of(Map.of("name", "region", "value", "EMEA"), Map.of("name", "min", "value", "10", "type", "BIGINT")), b.get("parameters"));
            fails("DATABRICKS.NO_SQL", () -> pkg.executeSql(ctx, f.url(), "w1", "", null, null, null, 10, 10, TOKEN));
            fails("DATABRICKS.BAD_ID", () -> pkg.executeSql(ctx, f.url(), "w/../1", "SELECT 1", null, null, null, 10, 10, TOKEN));
        }
    }

    @Test void statementStatusCancelFetchAndFailure() throws Exception {
        try (Fake f = new Fake()) {
            String[] state = {"RUNNING"};
            f.on("POST " + SQL + "/" + SID + "/cancel", c -> ok("{}"))
             .on("POST " + SQL, c -> ok("{\"statement_id\":\"" + SID + "\",\"status\":{\"state\":\"RUNNING\"}}"))
             .on("GET " + SQL + "/" + SID + "/result/chunks/1", c -> ok("{\"chunk_index\":1,\"data_array\":[[\"3\",\"AMER\",\"5\",null]]}"))
             .on("GET " + SQL + "/" + SID, c -> switch (state[0]) {
                 case "RUNNING" -> ok("{\"statement_id\":\"" + SID + "\",\"status\":{\"state\":\"RUNNING\"}}");
                 case "FAILED" -> ok("{\"statement_id\":\"" + SID + "\",\"status\":{\"state\":\"FAILED\",\"error\":{\"error_code\":\"BAD_REQUEST\",\"message\":\"[TABLE_OR_VIEW_NOT_FOUND] nope\"}}}");
                 default -> ok(done());
             });
            ActionContext ctx = ctx(PackagesTest.tmp());
            Map<String, Object> r = pkg.executeSql(ctx, f.url(), "w1", "OPTIMIZE big", null, null, null, 0, 10, TOKEN);
            assertEquals("RUNNING", r.get("status"));
            assertEquals("5s", f.last("POST", SQL).json().get("wait_timeout"), "server wait kept within its 5 to 50 second range");
            assertEquals("RUNNING", pkg.statementStatus(ctx, f.url(), SID, TOKEN).get("status"));
            fails("DATABRICKS.STILL_RUNNING", () -> pkg.fetchResults(ctx, f.url(), SID, 10, TOKEN));
            pkg.cancelStatement(ctx, f.url(), SID, TOKEN);
            assertNotNull(f.last("POST", SQL + "/" + SID + "/cancel"));

            state[0] = "SUCCEEDED";
            Map<String, Object> st = pkg.statementStatus(ctx, f.url(), SID, TOKEN);
            assertEquals("SUCCEEDED", st.get("status"));
            assertEquals(new BigDecimal(3), st.get("total_rows"));
            Map<String, Object> fr = pkg.fetchResults(ctx, f.url(), SID, 2, TOKEN);
            assertEquals(new BigDecimal(2), fr.get("row_count"));
            assertNull(f.last("GET", ".*/chunks/1"), "no further chunk once max_rows is reached");

            state[0] = "FAILED";
            assertEquals("[TABLE_OR_VIEW_NOT_FOUND] nope", pkg.statementStatus(ctx, f.url(), SID, TOKEN).get("message"));
            var e = fails("DATABRICKS.STATEMENT_FAILED", () -> pkg.fetchResults(ctx, f.url(), SID, 10, TOKEN));
            assertTrue(e.getMessage().contains("TABLE_OR_VIEW_NOT_FOUND"));
            fails("DATABRICKS.STATEMENT_FAILED", () -> pkg.executeSql(ctx, f.url(), "w1", "SELECT * FROM nope", null, null, null, 30, 10, TOKEN));
        }
    }

    @Test void jobsListRunWaitGetAndCancel() throws Exception {
        try (Fake f = new Fake()) {
            AtomicInteger gets = new AtomicInteger();
            f.on("GET /api/2.1/jobs/list", c -> q(c).contains("page_token=p2")
                    ? ok("{\"jobs\":[{\"job_id\":3,\"settings\":{\"name\":\"Nightly\"}}],\"has_more\":false}")
                    : ok("{\"jobs\":[{\"job_id\":1,\"settings\":{\"name\":\"ETL\"},\"creator_user_name\":\"ann@acme.com\",\"created_time\":1700000000000},"
                       + "{\"job_id\":2,\"settings\":{\"name\":\"Report\"}}],\"has_more\":true,\"next_page_token\":\"p2\"}"))
             .on("POST /api/2.1/jobs/run-now", c -> ok("{\"run_id\":555,\"number_in_job\":1}"))
             .on("GET /api/2.1/jobs/runs/get", c -> {
                 String failed = q(c).contains("run_id=666") ? "FAILED" : "SUCCESS";
                 return gets.incrementAndGet() < 3 && !q(c).contains("run_id=666")
                         ? ok("{\"run_id\":555,\"state\":{\"life_cycle_state\":\"RUNNING\"},\"run_page_url\":\"https://ws/run/555\"}")
                         : ok("{\"run_id\":" + (failed.equals("FAILED") ? 666 : 555) + ",\"state\":{\"life_cycle_state\":\"TERMINATED\",\"result_state\":\"" + failed + "\",\"state_message\":\"done\"},\"run_page_url\":\"https://ws/run/x\",\"start_time\":1,\"end_time\":2}");
             })
             .on("POST /api/2.1/jobs/runs/cancel", c -> ok("{}"));
            ActionContext ctx = ctx(PackagesTest.tmp());
            Map<String, Object> jobs = pkg.listJobs(ctx, f.url(), null, 100, TOKEN);
            assertEquals(new BigDecimal(3), jobs.get("count"), "second page read with page_token");
            assertEquals(Arrays.asList("1", "ETL", "ann@acme.com", new BigDecimal("1700000000000")), rows(jobs.get("table")).get(0));
            pkg.listJobs(ctx, f.url(), "ETL", 100, TOKEN);
            assertTrue(q(f.last("GET", "/api/2.1/jobs/list")).contains("name=ETL"));

            Map<String, Object> run = pkg.runJob(ctx, f.url(), "1", Map.of("run_date", "2026-10-20"), true, 60, true, TOKEN);
            assertEquals("555", run.get("run_id"));
            assertEquals("TERMINATED", run.get("life_cycle_state"));
            assertEquals("SUCCESS", run.get("result_state"));
            assertEquals(Map.of("job_id", new BigDecimal(1), "job_parameters", Map.of("run_date", "2026-10-20")), f.last("POST", "/api/2.1/jobs/run-now").json());
            assertTrue(gets.get() >= 3, "polled until the run finished");

            Map<String, Object> gr = pkg.getRun(ctx, f.url(), "666", TOKEN);
            assertEquals("FAILED", gr.get("result_state"));
            assertEquals("done", ((Map<?, ?>) gr.get("run")).get("state_message"));
            pkg.cancelRun(ctx, f.url(), "555", TOKEN);
            assertEquals(Map.of("run_id", new BigDecimal(555)), f.last("POST", "/api/2.1/jobs/runs/cancel").json());
            fails("DATABRICKS.BAD_ID", () -> pkg.getRun(ctx, f.url(), "abc", TOKEN));
            fails("DATABRICKS.BAD_ID", () -> pkg.runJob(ctx, f.url(), "1; x", null, false, 0, true, TOKEN));
        }
    }

    @Test void runJobFailureStopsTheAgent() throws Exception {
        try (Fake f = new Fake()) {
            f.on("POST /api/2.1/jobs/run-now", c -> ok("{\"run_id\":666}"))
             .on("GET /api/2.1/jobs/runs/get", c -> ok("{\"run_id\":666,\"state\":{\"life_cycle_state\":\"TERMINATED\",\"result_state\":\"FAILED\",\"state_message\":\"Task failed\"},\"run_page_url\":\"https://ws/run/666\"}"));
            ActionContext ctx = ctx(PackagesTest.tmp());
            var e = fails("DATABRICKS.RUN_FAILED", () -> pkg.runJob(ctx, f.url(), "9", null, true, 60, true, TOKEN));
            assertTrue(e.getMessage().contains("Task failed"));
            assertEquals("FAILED", pkg.runJob(ctx, f.url(), "9", null, true, 60, false, TOKEN).get("result_state"));
        }
    }

    @Test void clustersListStartWaitAndTerminate() throws Exception {
        try (Fake f = new Fake()) {
            AtomicInteger gets = new AtomicInteger();
            f.on("GET /api/2.0/clusters/list", c -> q(c).contains("page_token=n2")
                    ? ok("{\"clusters\":[{\"cluster_id\":\"0923-b\",\"cluster_name\":\"Adhoc\",\"state\":\"RUNNING\"}]}")
                    : ok("{\"clusters\":[{\"cluster_id\":\"0923-a\",\"cluster_name\":\"ETL\",\"state\":\"TERMINATED\",\"spark_version\":\"15.4.x-scala2.12\",\"node_type_id\":\"Standard_DS3_v2\",\"creator_user_name\":\"ann\"}],\"next_page_token\":\"n2\"}"))
             .on("GET /api/2.0/clusters/get", c -> {
                 int n = gets.incrementAndGet();
                 return ok("{\"state\":\"" + (n == 1 ? "TERMINATED" : n < 4 ? "PENDING" : "RUNNING") + "\"}");
             })
             .on("POST /api/2.0/clusters/start", c -> ok("{}"))
             .on("POST /api/2.0/clusters/delete", c -> ok("{}"));
            ActionContext ctx = ctx(PackagesTest.tmp());
            Map<String, Object> l = pkg.listClusters(ctx, f.url(), TOKEN);
            assertEquals(new BigDecimal(2), l.get("count"));
            assertEquals(Arrays.asList("0923-a", "ETL", "TERMINATED", "15.4.x-scala2.12", "Standard_DS3_v2", "ann"), rows(l.get("table")).get(0));
            assertEquals("RUNNING", pkg.startCluster(ctx, f.url(), "0923-a", true, 60, TOKEN).get("state"));
            assertEquals(Map.of("cluster_id", "0923-a"), f.last("POST", "/api/2.0/clusters/start").json());
            assertEquals("cluster_id=0923-a", f.last("GET", "/api/2.0/clusters/get").query());
            int starts = (int) f.calls.stream().filter(c -> c.path().endsWith("/start")).count();
            assertEquals("RUNNING", pkg.startCluster(ctx, f.url(), "0923-a", true, 60, TOKEN).get("state"));
            assertEquals(starts, f.calls.stream().filter(c -> c.path().endsWith("/start")).count(), "a running cluster is not started again");
            pkg.terminateCluster(ctx, f.url(), "0923-a", TOKEN);
            assertEquals(Map.of("cluster_id", "0923-a"), f.last("POST", "/api/2.0/clusters/delete").json());
        }
    }

    @Test void filesApiAndDbfsTransfers() throws Exception {
        try (Fake f = new Fake()) {
            byte[] big = new byte[1024 * 1024 + 300];
            for (int i = 0; i < big.length; i++) big[i] = (byte) (i % 251);
            f.on("PUT /api/2.0/fs/files/Volumes/main/fin/in%20box/r%C3%A9sum%C3%A9.csv", c -> new Object[]{204, ""})
             .on("GET /api/2.0/fs/files/Volumes/main/fin/out/result.csv", c -> ok("id,total\n1,5"))
             .on("POST /api/2.0/dbfs/create", c -> ok("{\"handle\":7}"))
             .on("POST /api/2.0/dbfs/add-block", c -> ok("{}"))
             .on("POST /api/2.0/dbfs/close", c -> ok("{}"))
             .on("GET /api/2.0/dbfs/read", c -> {
                 int off = Integer.parseInt(q(c).replaceAll(".*offset=(\\d+).*", "$1"));
                 int n = Math.min(1024 * 1024, big.length - off);
                 return ok("{\"bytes_read\":" + n + ",\"data\":\"" + Base64.getEncoder().encodeToString(Arrays.copyOfRange(big, off, off + n)) + "\"}");
             });
            Path w = PackagesTest.tmp();
            Files.writeString(w.resolve("r.csv"), "a,b");
            Files.write(w.resolve("big.bin"), big);
            ActionContext ctx = ctx(w);
            assertEquals("/Volumes/main/fin/in box/résumé.csv", pkg.upload(ctx, f.url(), "r.csv", "/Volumes/main/fin/in box/résumé.csv", true, TOKEN).get("remote_path"));
            Call put = f.last("PUT", "/api/2.0/fs/files/.*");
            assertEquals("overwrite=true", put.query());
            assertEquals("application/octet-stream", put.contentType());
            assertEquals("a,b", put.text());

            assertEquals("dbfs:/FileStore/big.bin", pkg.upload(ctx, f.url(), "big.bin", "dbfs:/FileStore/big.bin", false, TOKEN).get("remote_path"));
            assertEquals(Map.of("path", "/FileStore/big.bin", "overwrite", false), f.last("POST", "/api/2.0/dbfs/create").json());
            List<Call> blocks = f.calls.stream().filter(c -> c.path().endsWith("/add-block")).toList();
            assertEquals(2, blocks.size(), "file sent in 1 MB blocks");
            byte[] joined = new byte[0];
            for (Call b : blocks) {
                byte[] part = Base64.getDecoder().decode((String) b.json().get("data"));
                byte[] n = Arrays.copyOf(joined, joined.length + part.length);
                System.arraycopy(part, 0, n, joined.length, part.length);
                joined = n;
            }
            assertArrayEquals(big, joined);
            assertEquals(Map.of("handle", new BigDecimal(7)), f.last("POST", "/api/2.0/dbfs/close").json());

            Map<String, Object> d1 = pkg.download(ctx, f.url(), "/Volumes/main/fin/out/result.csv", "out/result.csv", TOKEN);
            assertEquals("id,total\n1,5", Files.readString(Path.of((String) d1.get("path"))));
            Map<String, Object> d2 = pkg.download(ctx, f.url(), "dbfs:/FileStore/big.bin", "out/big.bin", TOKEN);
            assertEquals(new BigDecimal(big.length), d2.get("size"));
            assertArrayEquals(big, Files.readAllBytes(w.resolve("out/big.bin")));
            fails("DATABRICKS.BAD_PATH", () -> pkg.download(ctx, f.url(), "/Volumes/a/../../etc", "x", TOKEN));
        }
    }

    @Test void httpErrorsThroughAnAgentRun() throws Exception {
        try (Fake f = new Fake()) {
            f.on("GET /api/2.1/jobs/runs/get", c -> q(c).contains("run_id=1")
                    ? status(404, "{\"error_code\":\"RESOURCE_DOES_NOT_EXIST\",\"message\":\"Run 1 does not exist.\"}")
                    : q(c).contains("run_id=2") ? status(429, "{\"error_code\":\"REQUEST_LIMIT_EXCEEDED\",\"message\":\"Too many requests\"}")
                    : status(403, "{\"error_code\":\"PERMISSION_DENIED\",\"message\":\"No access\"}"));
            String step = "[{\"id\":\"r\",\"action\":\"ooa.databricks/get_run\",\"args\":{\"workspace_url\":\"" + f.url() + "\",\"run_id\":\"ID\",\"access_token\":\"$tok\"}}]";
            FlowRun nf = flow(PackagesTest.tmp(), "", step.replace("ID", "1"));
            assertEquals("DATABRICKS.NOT_FOUND", nf.result().error().get("code"));
            assertTrue(String.valueOf(nf.result().error().get("message")).contains("RESOURCE_DOES_NOT_EXIST: Run 1 does not exist."));
            assertFalse(nf.events().contains("tok-123"));
            FlowRun rl = flow(PackagesTest.tmp(), "", step.replace("ID", "2"));
            assertEquals("DATABRICKS.RATE_LIMITED", rl.result().error().get("code"));
            assertEquals(true, rl.result().error().get("retryable"));
            assertEquals("DATABRICKS.FORBIDDEN", flow(PackagesTest.tmp(), "", step.replace("ID", "3")).result().error().get("code"));
        }
    }
}
