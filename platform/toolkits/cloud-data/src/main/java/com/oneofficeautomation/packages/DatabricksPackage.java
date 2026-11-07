package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/** Databricks REST APIs: SQL Statement Execution, Jobs, Clusters, and file transfer through the Files API (volumes) or DBFS. */
@OoaPackage(id = "ooa.databricks", name = "Databricks", version = "1.0.0",
        description = "Run SQL on a Databricks SQL warehouse, start and watch jobs, start or stop clusters, and move files in and out of the workspace.")
public final class DatabricksPackage {
    static final String P = "DATABRICKS";
    /** Pause between status checks; tests shorten it. */
    static volatile long pollMillis = 2000;
    private static final Pattern NUM_ID = Pattern.compile("^\\d{1,20}$");
    private static final Pattern TEXT_ID = Pattern.compile("^[0-9A-Za-z._-]{1,100}$");
    private static final int DBFS_BLOCK = 1024 * 1024;
    private static final String D_URL = "Workspace address, e.g. https://adb-1234567890123456.7.azuredatabricks.net or https://dbc-a1b2c3d4-e5f6.cloud.databricks.com.";
    private static final String D_TOKEN = "Databricks personal access token or OAuth token from the vault.";
    private static final Set<String> NUMERIC = Set.of("BYTE", "SHORT", "INT", "LONG", "TINYINT", "SMALLINT", "BIGINT", "INTEGER", "FLOAT", "DOUBLE", "DECIMAL");

    private static String ws(String url) { return url.replaceAll("/+$", ""); }
    private static HttpJson.Req req(String method, String url, Object token) { return HttpJson.req(P, method, url).bearer(token); }

    private static String numId(String what, String v) {
        if (v == null || !NUM_ID.matcher(v.trim()).matches()) throw new ActionException(P + ".BAD_ID", "Invalid " + what + " id '" + v + "'", "A " + what + " id is a number, shown in the address of its page.");
        return v.trim();
    }

    private static String textId(String what, String v) {
        if (v == null || !TEXT_ID.matcher(v.trim()).matches()) throw new ActionException(P + ".BAD_ID", "Invalid " + what + " id '" + v + "'", "Copy the id from the " + what + "'s details page.");
        return v.trim();
    }

    // ----- SQL -----

    static List<Map<String, Object>> parameters(Map<String, Object> params) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (params == null) return out;
        params.forEach((k, v) -> {
            if (v instanceof SecureString) throw Values.secureLeak("a SQL parameter");
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("name", k);
            p.put("value", v == null ? null : Values.text(v));
            if (v instanceof BigDecimal d) p.put("type", d.stripTrailingZeros().scale() <= 0 ? "BIGINT" : "DOUBLE");
            else if (v instanceof Boolean) p.put("type", "BOOLEAN");
            out.add(p);
        });
        return out;
    }

    private static String state(Map<String, Object> st) { return st.get("status") instanceof Map<?, ?> s ? String.valueOf(s.get("state")) : "UNKNOWN"; }
    private static boolean running(String state) { return state.equals("PENDING") || state.equals("RUNNING"); }

    private static Map<String, Object> checkFailed(Map<String, Object> st) {
        String s = state(st);
        if (s.equals("FAILED")) {
            Object msg = st.get("status") instanceof Map<?, ?> m && m.get("error") instanceof Map<?, ?> e ? e.get("message") : "no details";
            throw new ActionException(P + ".STATEMENT_FAILED", "The SQL statement failed: " + msg, "Check the SQL, table names and your permissions on the warehouse.");
        }
        if (s.equals("CANCELED") || s.equals("CLOSED"))
            throw new ActionException(P + ".STATEMENT_CANCELED", "The SQL statement was " + s.toLowerCase(Locale.ROOT), "Run it again.");
        return st;
    }

    private static Map<String, Object> getStatement(ActionContext ctx, String url, Object token, String id) {
        return req("GET", ws(url) + "/api/2.0/sql/statements/" + textId("statement", id), token).timeout(120).send(ctx).map();
    }

    static Object cell(Object raw, String type) {
        if (raw == null) return null;
        String v = Values.text(raw);
        String t = type == null ? "" : type.toUpperCase(Locale.ROOT);
        try {
            if (NUMERIC.contains(t)) return new BigDecimal(v);
            if (t.equals("BOOLEAN")) return Boolean.parseBoolean(v);
        } catch (NumberFormatException ignored) { }
        return v;
    }

    /** Builds a table from the first chunk and follows next-chunk links. */
    private static Map<String, Object> readTable(ActionContext ctx, String url, Object token, Map<String, Object> st, int max) {
        Map<?, ?> manifest = st.get("manifest") instanceof Map<?, ?> m ? m : Map.of();
        List<?> columns = manifest.get("schema") instanceof Map<?, ?> s && s.get("columns") instanceof List<?> l ? l : List.of();
        List<String> names = new ArrayList<>();
        List<String> types = new ArrayList<>();
        for (Object o : columns) { Map<?, ?> c = (Map<?, ?>) o; names.add(String.valueOf(c.get("name"))); types.add(String.valueOf(c.get("type_name"))); }
        List<Object> rows = new ArrayList<>();
        Map<?, ?> chunk = st.get("result") instanceof Map<?, ?> r ? r : null;
        while (chunk != null && rows.size() < max) {
            for (Object o : chunk.get("data_array") instanceof List<?> l ? l : List.of()) {
                List<?> raw = (List<?>) o;
                List<Object> row = new ArrayList<>();
                for (int i = 0; i < names.size(); i++) row.add(cell(i < raw.size() ? raw.get(i) : null, types.get(i)));
                rows.add(row);
                if (rows.size() >= max) break;
            }
            Object next = chunk.get("next_chunk_internal_link");
            if (next == null || rows.size() >= max) break;
            String link = Values.text(next);
            if (!link.startsWith("/api/")) throw new ActionException(P + ".BAD_RESPONSE", "Unexpected result link from Databricks", "Report this with the run id.");
            chunk = req("GET", ws(url) + link, token).timeout(120).send(ctx).map();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("table", Values.table(names, rows));
        out.put("total_rows", manifest.get("total_row_count") instanceof BigDecimal n ? n : BigDecimal.valueOf(rows.size()));
        return out;
    }

    @Action(id = "execute_sql", name = "Run SQL statement", category = "SQL", timeoutSeconds = 3600, capabilities = "network",
            description = "Run SQL on a SQL warehouse and return the rows as a data table. Use :name in the SQL for values passed in parameters.",
            panel = "workspace_url, access_token, warehouse_id, sql, parameters, max_rows, wait_seconds | catalog, schema")
    @Output(name = "table", type = OoaType.TABLE, description = "Result rows (empty for statements that return no rows).")
    @Output(name = "row_count", type = OoaType.NUMBER, description = "Number of rows in the table.")
    @Output(name = "statement_id", type = OoaType.STRING, description = "Id of the statement, for status, results or cancel.")
    @Output(name = "status", type = OoaType.STRING, description = "SUCCEEDED, or PENDING / RUNNING when it did not finish within the wait time.")
    public Map<String, Object> executeSql(ActionContext ctx,
            @Input(name = "workspace_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String url,
            @Input(name = "warehouse_id", type = OoaType.STRING, description = "SQL warehouse that runs the statement (from its Connection details).") String warehouse,
            @Input(name = "sql", type = OoaType.STRING, example = "SELECT * FROM sales.orders WHERE region = :region", description = "SQL to run.") String sql,
            @Input(name = "parameters", type = OoaType.DICTIONARY, required = false, description = "Values for :name markers in the SQL, e.g. {\"region\": \"EMEA\"}.") Map<String, Object> params,
            @Input(name = "catalog", type = OoaType.STRING, required = false, description = "Catalog for names that are not fully written out.") String catalog,
            @Input(name = "schema", type = OoaType.STRING, required = false, description = "Schema for names that are not fully written out.") String schema,
            @Input(name = "wait_seconds", type = OoaType.NUMBER, required = false, defaultValue = "600", description = "How long to wait for the result before returning with status RUNNING.") int wait,
            @Input(name = "max_rows", type = OoaType.NUMBER, required = false, defaultValue = "100000", description = "Most rows to bring back into the table.") int max,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token) {
        if (sql == null || sql.isBlank()) throw new ActionException(P + ".NO_SQL", "The SQL statement is empty", "Write the SQL to run.");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("statement", sql);
        body.put("warehouse_id", textId("warehouse", warehouse));
        if (catalog != null && !catalog.isBlank()) body.put("catalog", catalog);
        if (schema != null && !schema.isBlank()) body.put("schema", schema);
        List<Map<String, Object>> ps = parameters(params);
        if (!ps.isEmpty()) body.put("parameters", ps);
        body.put("wait_timeout", Math.max(5, Math.min(50, wait)) + "s");
        body.put("on_wait_timeout", "CONTINUE");
        body.put("disposition", "INLINE");
        body.put("format", "JSON_ARRAY");
        Map<String, Object> st = req("POST", ws(url) + "/api/2.0/sql/statements", token).json(body).timeout(120).send(ctx).map();
        String id = Values.text(st.get("statement_id"));
        long deadline = System.currentTimeMillis() + wait * 1000L;
        while (running(state(st)) && System.currentTimeMillis() < deadline) {
            HttpJson.pause(ctx, pollMillis);
            st = getStatement(ctx, url, token, id);
        }
        checkFailed(st);
        if (running(state(st)))
            return Out.of("table", Values.table(List.of(), new ArrayList<>()), "row_count", BigDecimal.ZERO, "statement_id", id, "status", state(st));
        Map<String, Object> t = readTable(ctx, url, token, st, max);
        @SuppressWarnings("unchecked") Map<String, Object> tbl = (Map<String, Object>) t.get("table");
        return Out.of("table", tbl, "row_count", BigDecimal.valueOf(DataTablePackage.rows(tbl).size()), "statement_id", id, "status", state(st));
    }

    @Action(id = "get_statement_status", name = "Get statement status", category = "SQL", idempotent = true, capabilities = "network",
            description = "Check whether a SQL statement that was left running has finished.",
            panel = "workspace_url, access_token, statement_id")
    @Output(name = "status", type = OoaType.STRING, description = "PENDING, RUNNING, SUCCEEDED, FAILED, CANCELED or CLOSED.")
    @Output(name = "message", type = OoaType.STRING, description = "Error text when the statement failed.")
    @Output(name = "total_rows", type = OoaType.NUMBER, description = "Rows in the result once it succeeded.")
    public Map<String, Object> statementStatus(ActionContext ctx,
            @Input(name = "workspace_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String url,
            @Input(name = "statement_id", type = OoaType.STRING, description = "Id from Run SQL statement.") String id,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token) {
        Map<String, Object> st = getStatement(ctx, url, token, id);
        Object msg = st.get("status") instanceof Map<?, ?> m && m.get("error") instanceof Map<?, ?> e ? e.get("message") : null;
        Object total = st.get("manifest") instanceof Map<?, ?> mf && mf.get("total_row_count") instanceof BigDecimal n ? n : BigDecimal.ZERO;
        return Out.of("status", state(st), "message", msg, "total_rows", total);
    }

    @Action(id = "cancel_statement", name = "Cancel statement", category = "SQL", capabilities = "network", description = "Stop a SQL statement that is still running.",
            panel = "workspace_url, access_token, statement_id")
    public Map<String, Object> cancelStatement(ActionContext ctx,
            @Input(name = "workspace_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String url,
            @Input(name = "statement_id", type = OoaType.STRING, description = "Id from Run SQL statement.") String id,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token) {
        req("POST", ws(url) + "/api/2.0/sql/statements/" + textId("statement", id) + "/cancel", token).send(ctx);
        return Map.of();
    }

    /** Earlier signature, kept for callers written before wait_seconds. */
    public Map<String, Object> fetchResults(ActionContext ctx, String url, String id, int max, Object token) {
        return fetchResults(ctx, url, id, max, token, 0);
    }

    @Action(id = "fetch_results", name = "Get statement results", category = "SQL", idempotent = true, timeoutSeconds = 3600, capabilities = "network",
            description = "Read the rows of a finished SQL statement into a data table, chunk by chunk.",
            panel = "workspace_url, access_token, statement_id, max_rows, wait_seconds")
    @Output(name = "table", type = OoaType.TABLE, description = "Result rows.")
    @Output(name = "row_count", type = OoaType.NUMBER, description = "Number of rows in the table.")
    @Output(name = "total_rows", type = OoaType.NUMBER, description = "Rows in the full result, which can be more than max_rows.")
    public Map<String, Object> fetchResults(ActionContext ctx,
            @Input(name = "workspace_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String url,
            @Input(name = "statement_id", type = OoaType.STRING, description = "Id from Run SQL statement.") String id,
            @Input(name = "max_rows", type = OoaType.NUMBER, required = false, defaultValue = "100000", description = "Most rows to bring back.") int max,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "wait_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0,
                    description = "How long to wait for a running statement to finish; 0 fails at once if it is still running.") int wait) {
        Map<String, Object> st = getStatement(ctx, url, token, id);
        long deadline = System.currentTimeMillis() + Math.max(0, wait) * 1000L;
        while (running(state(st)) && System.currentTimeMillis() < deadline) {
            HttpJson.pause(ctx, pollMillis);
            st = getStatement(ctx, url, token, id);
        }
        checkFailed(st);
        if (running(state(st))) throw new ActionException(P + ".STILL_RUNNING", "The statement is still running", "Wait, then try again, or check it with Get statement status.", true);
        Map<String, Object> t = readTable(ctx, url, token, st, max);
        @SuppressWarnings("unchecked") Map<String, Object> tbl = (Map<String, Object>) t.get("table");
        return Out.of("table", tbl, "row_count", BigDecimal.valueOf(DataTablePackage.rows(tbl).size()), "total_rows", t.get("total_rows"));
    }

    // ----- jobs -----

    @Action(id = "list_jobs", name = "List jobs", category = "Jobs", idempotent = true, timeoutSeconds = 300, capabilities = "network",
            description = "Return the jobs in the workspace, optionally only those with a given name.",
            panel = "workspace_url, access_token, max_results | name")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: job_id, name, creator, created_time.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of jobs returned.")
    public Map<String, Object> listJobs(ActionContext ctx,
            @Input(name = "workspace_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String url,
            @Input(name = "name", type = OoaType.STRING, required = false, description = "Only the job with exactly this name.") String name,
            @Input(name = "max_results", type = OoaType.NUMBER, required = false, defaultValue = "1000", description = "Most jobs to return.") int max,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token) {
        List<Object> rows = new ArrayList<>();
        String pageToken = null;
        do {
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("limit", 100);
            if (name != null && !name.isBlank()) q.put("name", name);
            if (pageToken != null) q.put("page_token", pageToken);
            Map<String, Object> r = req("GET", ws(url) + "/api/2.1/jobs/list?" + HttpJson.query(q), token).send(ctx).map();
            for (Object o : (List<?>) r.getOrDefault("jobs", List.of())) {
                Map<?, ?> j = (Map<?, ?>) o;
                Object jn = j.get("settings") instanceof Map<?, ?> s ? s.get("name") : null;
                rows.add(new ArrayList<>(Arrays.asList(j.get("job_id") == null ? null : Values.text(j.get("job_id")), jn, j.get("creator_user_name"), j.get("created_time"))));
                if (rows.size() >= max) break;
            }
            pageToken = Boolean.TRUE.equals(r.get("has_more")) && r.get("next_page_token") != null ? Values.text(r.get("next_page_token")) : null;
        } while (pageToken != null && rows.size() < max);
        return Out.of("table", Values.table(List.of("job_id", "name", "creator", "created_time"), rows), "count", BigDecimal.valueOf(rows.size()));
    }

    private static Map<String, Object> runSummary(Map<String, Object> r) {
        Map<?, ?> s = r.get("state") instanceof Map<?, ?> m ? m : Map.of();
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("run_id", r.get("run_id") == null ? null : Values.text(r.get("run_id")));
        o.put("life_cycle_state", s.get("life_cycle_state"));
        o.put("result_state", s.get("result_state"));
        o.put("state_message", s.get("state_message"));
        o.put("run_page_url", r.get("run_page_url"));
        o.put("start_time", r.get("start_time"));
        o.put("end_time", r.get("end_time"));
        return o;
    }

    private static boolean runFinished(Map<String, Object> summary) {
        Object s = summary.get("life_cycle_state");
        return "TERMINATED".equals(s) || "SKIPPED".equals(s) || "INTERNAL_ERROR".equals(s);
    }

    private static Map<String, Object> getRunRaw(ActionContext ctx, String url, Object token, String runId) {
        return req("GET", ws(url) + "/api/2.1/jobs/runs/get?run_id=" + numId("run", runId), token).send(ctx).map();
    }

    @Action(id = "run_job", name = "Run job now", category = "Jobs", timeoutSeconds = 86400, capabilities = "network",
            description = "Start a job right away, optionally with parameters, and wait for it to finish if you like.",
            panel = "workspace_url, access_token, job_id, job_parameters | wait_until_finished, wait_seconds, fail_if_run_fails")
    @Output(name = "run_id", type = OoaType.STRING, description = "Id of the job run.")
    @Output(name = "life_cycle_state", type = OoaType.STRING, description = "Where the run is, e.g. PENDING, RUNNING or TERMINATED.")
    @Output(name = "result_state", type = OoaType.STRING, description = "SUCCESS, FAILED, TIMEDOUT or CANCELED once it has finished; empty before that.")
    @Output(name = "run_page_url", type = OoaType.STRING, description = "Link to the run in the Databricks workspace.")
    public Map<String, Object> runJob(ActionContext ctx,
            @Input(name = "workspace_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String url,
            @Input(name = "job_id", type = OoaType.STRING, description = "Job to start (see List jobs).") String jobId,
            @Input(name = "job_parameters", type = OoaType.DICTIONARY, required = false, description = "Job parameter values, e.g. {\"run_date\": \"2026-10-20\"}.") Map<String, Object> params,
            @Input(name = "wait_until_finished", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Wait for the run to end before going on.") boolean wait,
            @Input(name = "wait_seconds", type = OoaType.NUMBER, required = false, defaultValue = "3600", description = "Longest time to wait when waiting is on.") int waitSeconds,
            @Input(name = "fail_if_run_fails", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "When waiting, stop the agent with an error if the run does not succeed.") boolean failOnError,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("job_id", new BigDecimal(numId("job", jobId)));
        if (params != null && !params.isEmpty()) { Map<String, Object> jp = new LinkedHashMap<>(); params.forEach((k, v) -> jp.put(k, Values.text(v))); body.put("job_parameters", jp); }
        Map<String, Object> started = req("POST", ws(url) + "/api/2.1/jobs/run-now", token).json(body).send(ctx).map();
        String runId = Values.text(started.get("run_id"));
        Map<String, Object> s = runSummary(getRunRaw(ctx, url, token, runId));
        long deadline = System.currentTimeMillis() + waitSeconds * 1000L;
        while (wait && !runFinished(s) && System.currentTimeMillis() < deadline) {
            HttpJson.pause(ctx, pollMillis);
            s = runSummary(getRunRaw(ctx, url, token, runId));
        }
        if (wait && failOnError && runFinished(s) && !"SUCCESS".equals(s.get("result_state")))
            throw new ActionException(P + ".RUN_FAILED", "Job run " + runId + " ended with " + s.get("result_state") + ": " + s.get("state_message"),
                    "Open the run page for details: " + s.get("run_page_url"));
        return Out.of("run_id", runId, "life_cycle_state", s.get("life_cycle_state"), "result_state", s.get("result_state"), "run_page_url", s.get("run_page_url"));
    }

    @Action(id = "get_run", name = "Get job run", category = "Jobs", idempotent = true, capabilities = "network", description = "Check the progress and outcome of a job run.",
            panel = "workspace_url, access_token, run_id")
    @Output(name = "life_cycle_state", type = OoaType.STRING, description = "Where the run is, e.g. PENDING, RUNNING or TERMINATED.")
    @Output(name = "result_state", type = OoaType.STRING, description = "SUCCESS, FAILED, TIMEDOUT or CANCELED once finished.")
    @Output(name = "run", type = OoaType.DICTIONARY, description = "run_id, life_cycle_state, result_state, state_message, run_page_url, start_time, end_time.")
    public Map<String, Object> getRun(ActionContext ctx,
            @Input(name = "workspace_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String url,
            @Input(name = "run_id", type = OoaType.STRING, description = "Run id from Run job now.") String runId,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token) {
        Map<String, Object> s = runSummary(getRunRaw(ctx, url, token, runId));
        return Out.of("life_cycle_state", s.get("life_cycle_state"), "result_state", s.get("result_state"), "run", s);
    }

    @Action(id = "cancel_run", name = "Cancel job run", category = "Jobs", capabilities = "network", description = "Stop a job run that is queued or running.",
            panel = "workspace_url, access_token, run_id")
    public Map<String, Object> cancelRun(ActionContext ctx,
            @Input(name = "workspace_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String url,
            @Input(name = "run_id", type = OoaType.STRING, description = "Run id from Run job now.") String runId,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token) {
        req("POST", ws(url) + "/api/2.1/jobs/runs/cancel", token).json(Map.of("run_id", new BigDecimal(numId("run", runId)))).send(ctx);
        return Map.of();
    }

    // ----- clusters -----

    @Action(id = "list_clusters", name = "List clusters", category = "Clusters", idempotent = true, capabilities = "network", description = "Return the workspace's clusters and their state.",
            panel = "workspace_url, access_token")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: cluster_id, name, state, spark_version, node_type, creator.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of clusters.")
    public Map<String, Object> listClusters(ActionContext ctx,
            @Input(name = "workspace_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String url,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token) {
        List<Object> rows = new ArrayList<>();
        String pageToken = null;
        do {
            Map<String, Object> r = req("GET", ws(url) + "/api/2.0/clusters/list" + (pageToken == null ? "" : "?page_token=" + HttpJson.enc(pageToken)), token).send(ctx).map();
            for (Object o : (List<?>) r.getOrDefault("clusters", List.of())) {
                Map<?, ?> c = (Map<?, ?>) o;
                rows.add(new ArrayList<>(Arrays.asList(c.get("cluster_id"), c.get("cluster_name"), c.get("state"), c.get("spark_version"), c.get("node_type_id"), c.get("creator_user_name"))));
            }
            Object next = r.get("next_page_token");
            pageToken = next == null || Values.text(next).isBlank() ? null : Values.text(next);
        } while (pageToken != null);
        return Out.of("table", Values.table(List.of("cluster_id", "name", "state", "spark_version", "node_type", "creator"), rows), "count", BigDecimal.valueOf(rows.size()));
    }

    private static String clusterState(ActionContext ctx, String url, Object token, String id) {
        return Values.text(req("GET", ws(url) + "/api/2.0/clusters/get?cluster_id=" + HttpJson.enc(id), token).send(ctx).map().get("state"));
    }

    @Action(id = "start_cluster", name = "Start cluster", category = "Clusters", timeoutSeconds = 3600, capabilities = "network",
            description = "Start a stopped cluster, and wait until it is running if you like. Nothing happens if it is already running.",
            panel = "workspace_url, access_token, cluster_id | wait_until_running, wait_seconds")
    @Output(name = "state", type = OoaType.STRING, description = "Cluster state afterwards, e.g. PENDING or RUNNING.")
    public Map<String, Object> startCluster(ActionContext ctx,
            @Input(name = "workspace_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String url,
            @Input(name = "cluster_id", type = OoaType.STRING, description = "Cluster to start (see List clusters).") String clusterId,
            @Input(name = "wait_until_running", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Wait until the cluster is ready before going on.") boolean wait,
            @Input(name = "wait_seconds", type = OoaType.NUMBER, required = false, defaultValue = "1200", description = "Longest time to wait.") int waitSeconds,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token) {
        String id = textId("cluster", clusterId);
        String state = clusterState(ctx, url, token, id);
        if (!state.equals("RUNNING") && !state.equals("PENDING") && !state.equals("RESIZING"))
            req("POST", ws(url) + "/api/2.0/clusters/start", token).json(Map.of("cluster_id", id)).send(ctx);
        long deadline = System.currentTimeMillis() + waitSeconds * 1000L;
        state = clusterState(ctx, url, token, id);
        while (wait && !state.equals("RUNNING") && System.currentTimeMillis() < deadline) {
            if (state.equals("ERROR") || state.equals("TERMINATED") || state.equals("UNKNOWN"))
                throw new ActionException(P + ".CLUSTER_FAILED", "Cluster " + id + " did not start; its state is " + state, "Check the cluster's event log in Databricks.");
            HttpJson.pause(ctx, pollMillis);
            state = clusterState(ctx, url, token, id);
        }
        return Out.of("state", state);
    }

    @Action(id = "terminate_cluster", name = "Stop cluster", category = "Clusters", capabilities = "network",
            description = "Stop a running cluster to save cost. Its settings are kept so it can be started again.",
            panel = "workspace_url, access_token, cluster_id")
    public Map<String, Object> terminateCluster(ActionContext ctx,
            @Input(name = "workspace_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String url,
            @Input(name = "cluster_id", type = OoaType.STRING, description = "Cluster to stop.") String clusterId,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token) {
        req("POST", ws(url) + "/api/2.0/clusters/delete", token).json(Map.of("cluster_id", textId("cluster", clusterId))).send(ctx);
        return Map.of();
    }

    // ----- files -----

    /** Volumes and workspace files use the Files API; everything else (dbfs:/...) uses DBFS. */
    private static boolean filesApi(String remote) { return remote.startsWith("/Volumes/") || remote.startsWith("/Workspace/"); }

    private static String remote(String p) {
        if (p == null || p.isBlank()) throw new ActionException(P + ".BAD_PATH", "A remote path is required", "Example: /Volumes/main/finance/inbox/report.csv or dbfs:/FileStore/report.csv.");
        String r = p.trim().replace('\\', '/');
        if (r.startsWith("dbfs:")) r = r.substring(5);
        if (!r.startsWith("/")) r = "/" + r;
        for (String part : r.split("/")) if (part.equals("..")) throw new ActionException(P + ".BAD_PATH", "Remote path must not contain ..", "Write the full path.");
        return r;
    }

    @Action(id = "upload_file", name = "Upload file", category = "Files", timeoutSeconds = 3600, capabilities = {"network", "filesystem"},
            description = "Copy a local file into a Unity Catalog volume, the workspace, or DBFS.",
            panel = "workspace_url, access_token, local_path, remote_path, overwrite")
    @Output(name = "remote_path", type = OoaType.STRING, description = "Where the file was written.")
    public Map<String, Object> upload(ActionContext ctx,
            @Input(name = "workspace_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String url,
            @Input(name = "local_path", type = OoaType.FILE, description = "File on this machine to upload.") String local,
            @Input(name = "remote_path", type = OoaType.STRING, example = "/Volumes/main/finance/inbox/report.csv", description = "Destination, e.g. /Volumes/catalog/schema/volume/file.csv or dbfs:/FileStore/file.csv.") String remotePath,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Replace a file that is already there.") boolean overwrite,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token) {
        byte[] data = HttpJson.readFile(ctx.resolvePath(local));
        String r = remote(remotePath);
        if (filesApi(r)) {
            req("PUT", ws(url) + "/api/2.0/fs/files/" + HttpJson.encPath(r) + "?overwrite=" + overwrite, token).bytes(data, "application/octet-stream").timeout(3600).send(ctx);
        } else {
            Object handle = req("POST", ws(url) + "/api/2.0/dbfs/create", token).json(Map.of("path", r, "overwrite", overwrite)).send(ctx).map().get("handle");
            if (handle == null) throw new ActionException("DATABRICKS.BAD_RESPONSE", "Databricks did not return an upload handle for " + r,
                    "Check workspace_url and that the token may write to DBFS.", true);
            for (int off = 0; off < data.length; off += DBFS_BLOCK) {
                byte[] block = Arrays.copyOfRange(data, off, Math.min(data.length, off + DBFS_BLOCK));
                req("POST", ws(url) + "/api/2.0/dbfs/add-block", token).json(Map.of("handle", handle, "data", Base64.getEncoder().encodeToString(block))).timeout(300).send(ctx);
            }
            req("POST", ws(url) + "/api/2.0/dbfs/close", token).json(Map.of("handle", handle)).send(ctx);
        }
        return Out.of("remote_path", filesApi(r) ? r : "dbfs:" + r);
    }

    @Action(id = "delete_file", name = "Delete file", category = "Files", capabilities = "network",
            description = "Delete a file from a Unity Catalog volume, the workspace, or DBFS (a DBFS folder with recursive).",
            panel = "workspace_url, access_token, remote_path | recursive")
    public Map<String, Object> deleteFile(ActionContext ctx,
            @Input(name = "workspace_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String url,
            @Input(name = "remote_path", type = OoaType.STRING, example = "/Volumes/main/finance/inbox/old.csv", description = "File to delete.") String remotePath,
            @Input(name = "recursive", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "DBFS only: also delete a folder with everything in it.") boolean recursive,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token) {
        String r = remote(remotePath);
        if (filesApi(r)) req("DELETE", ws(url) + "/api/2.0/fs/files/" + HttpJson.encPath(r), token).send(ctx);
        else req("POST", ws(url) + "/api/2.0/dbfs/delete", token).json(Map.of("path", r, "recursive", recursive)).send(ctx);
        return Map.of();
    }

    @Action(id = "download_file", name = "Download file", category = "Files", timeoutSeconds = 3600, capabilities = {"network", "filesystem"},
            description = "Save a file from a Unity Catalog volume, the workspace, or DBFS to this machine.",
            panel = "workspace_url, access_token, remote_path, save_as | overwrite")
    @Output(name = "path", type = OoaType.FILE, description = "Full path of the saved file.")
    @Output(name = "size", type = OoaType.NUMBER, description = "File size in bytes.")
    public Map<String, Object> download(ActionContext ctx,
            @Input(name = "workspace_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String url,
            @Input(name = "remote_path", type = OoaType.STRING, example = "/Volumes/main/finance/outbox/result.csv", description = "File to download, e.g. /Volumes/catalog/schema/volume/file.csv or dbfs:/FileStore/file.csv.") String remotePath,
            @Input(name = "save_as", type = OoaType.FILE, description = "Where to save it on this machine. Missing folders are created.") String saveAs,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Replace a file already at save_as; when off, the action stops instead and nothing is downloaded.") boolean overwrite) {
        String r = remote(remotePath);
        Path target = ctx.resolvePath(saveAs);
        if (!overwrite && java.nio.file.Files.exists(target))
            throw new ActionException(P + ".FILE_EXISTS", "A file already exists at " + target, "Choose another save_as, delete the file first, or turn on overwrite.");
        byte[] data;
        if (filesApi(r)) {
            data = req("GET", ws(url) + "/api/2.0/fs/files/" + HttpJson.encPath(r), token).binaryResponse().timeout(3600).send(ctx).bytes();
        } else {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            long offset = 0;
            while (true) {
                Map<String, Object> q = new LinkedHashMap<>();
                q.put("path", r); q.put("offset", offset); q.put("length", DBFS_BLOCK);
                Map<String, Object> chunk = req("GET", ws(url) + "/api/2.0/dbfs/read?" + HttpJson.query(q), token).timeout(300).send(ctx).map();
                int n = chunk.get("bytes_read") instanceof BigDecimal b ? b.intValue() : 0;
                if (n <= 0) break;
                out.writeBytes(Base64.getDecoder().decode(Values.text(chunk.get("data"))));
                offset += n;
                if (n < DBFS_BLOCK) break;
            }
            data = out.toByteArray();
        }
        HttpJson.writeFile(target, data);
        return Out.of("path", target.toString(), "size", BigDecimal.valueOf(data.length));
    }

    /** Earlier signature, kept for callers written before overwrite. */
    public Map<String, Object> download(ActionContext ctx, String url, String remotePath, String saveAs, Object token) {
        return download(ctx, url, remotePath, saveAs, token, true);
    }
}
