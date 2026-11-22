package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Snowflake SQL API v2 over HTTPS (no JDBC driver). Sign-in with an OAuth token, a key-pair JWT or a
 * programmatic access token kept in the vault.
 */
@OoaPackage(id = "ooa.snowflake", name = "Snowflake", version = "1.0.0",
        description = "Run SQL in Snowflake, read results into data tables, browse databases, schemas and tables, and load rows.")
public final class SnowflakePackage {
    static final String P = "SNOWFLAKE";
    /** Pause between status checks of a running statement; tests shorten it. */
    static volatile long pollMillis = 1000;
    private static final Pattern HANDLE = Pattern.compile("^[0-9A-Za-z-]{16,64}$");
    private static final Pattern PLAIN_IDENT = Pattern.compile("^[A-Za-z_][A-Za-z0-9_$]*$");
    private static final String D_URL = "Your Snowflake account address, e.g. https://myorg-myaccount.snowflakecomputing.com.";
    private static final String D_TOKEN = "Access token from the vault: an OAuth token, a key-pair JWT or a programmatic access token. Or leave it empty and choose a connection that signs in, which gets and renews the token.";
    private static final String D_TOKEN_TYPE = "Kind of token in the vault: OAUTH, KEYPAIR_JWT or PROGRAMMATIC_ACCESS_TOKEN.";
    private static final String D_ROLE = "Role to run as. Leave empty for the user's default role.";
    private static final String D_WH = "Warehouse that runs the query. Leave empty for the user's default warehouse.";

    private static String api(String account) { return account.replaceAll("/+$", "") + "/api/v2/statements"; }

    private static HttpJson.Req req(String method, String url, Object token, String tokenType) {
        HttpJson.Req r = HttpJson.req(P, method, url).bearer(token).header("User-Agent", "ONEOFFICEAUTOMATION/1.0");
        if (tokenType != null && !tokenType.isBlank()) r.header("X-Snowflake-Authorization-Token-Type", tokenType.trim().toUpperCase(Locale.ROOT));
        return r;
    }

    private static String handle(String h) {
        if (h == null || !HANDLE.matcher(h.trim()).matches()) throw new ActionException(P + ".BAD_HANDLE", "Invalid statement handle '" + h + "'", "Use the statement_handle returned by Run SQL statement.");
        return h.trim();
    }

    /** Leaves simple names unquoted (so they match case-insensitively) and safely quotes everything else. */
    static String ident(String name) {
        String n = name == null ? "" : name.trim();
        if (n.length() > 1 && n.startsWith("\"") && n.endsWith("\"")) n = n.substring(1, n.length() - 1).replace("\"\"", "\"");
        if (n.isEmpty()) throw new ActionException(P + ".BAD_NAME", "A name is empty", "Give a database, schema, table or column name.");
        return PLAIN_IDENT.matcher(n).matches() ? n : "\"" + n.replace("\"", "\"\"") + "\"";
    }

    static String qualified(String name) {
        if (name == null || name.isBlank()) throw new ActionException(P + ".BAD_NAME", "A table name is required", "Example: SALES.PUBLIC.ORDERS or ORDERS.");
        StringJoiner j = new StringJoiner(".");
        for (String part : name.trim().split("\\.(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)")) j.add(ident(part));
        return j.toString();
    }

    /** Positional bindings for ? placeholders, typed so numbers and booleans keep their meaning. */
    static Map<String, Object> bindings(List<Object> values) {
        Map<String, Object> b = new LinkedHashMap<>();
        if (values == null) return b;
        int i = 1;
        for (Object v : values) {
            if (v instanceof SecureString) throw Values.secureLeak("a SQL binding");
            String type = "TEXT";
            Object value = v == null ? null : Values.text(v);
            if (v instanceof BigDecimal d) type = d.stripTrailingZeros().scale() <= 0 ? "FIXED" : "REAL";
            else if (v instanceof Number) type = "REAL";
            else if (v instanceof Boolean) type = "BOOLEAN";
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("type", type);
            e.put("value", value);
            b.put(String.valueOf(i++), e);
        }
        return b;
    }

    /** Converts a JSON result cell (always text in the SQL API) to a number, boolean, date or time text. */
    static Object cell(Object raw, Map<?, ?> col) {
        if (raw == null) return null;
        String v = Values.text(raw);
        String type = String.valueOf(col.get("type")).toLowerCase(Locale.ROOT);
        try {
            switch (type) {
                case "fixed": case "real": return new BigDecimal(v);
                case "boolean": return v.equalsIgnoreCase("true") || v.equals("1");
                case "date": return LocalDate.ofEpochDay(Long.parseLong(v)).toString();
                case "time": return LocalTime.ofNanoOfDay(new BigDecimal(v).movePointRight(9).longValueExact()).toString();
                case "timestamp_ntz": case "timestamp_ltz": return epoch(v).atOffset(ZoneOffset.UTC).toLocalDateTime().toString();
                case "timestamp_tz": {
                    String[] parts = v.trim().split("\\s+");
                    ZoneOffset off = parts.length > 1 ? ZoneOffset.ofTotalSeconds((Integer.parseInt(parts[1]) - 1440) * 60) : ZoneOffset.UTC;
                    return epoch(parts[0]).atOffset(off).toString();
                }
                default: return v;
            }
        } catch (RuntimeException e) { return v; }
    }

    private static Instant epoch(String secs) {
        BigDecimal d = new BigDecimal(secs);
        long s = d.setScale(0, java.math.RoundingMode.FLOOR).longValueExact();
        return Instant.ofEpochSecond(s, d.subtract(BigDecimal.valueOf(s)).movePointRight(9).longValue());
    }

    record Result(String handle, int httpStatus, Map<String, Object> body) {
        boolean running() { return httpStatus == 202; }
    }

    private static Result submit(ActionContext ctx, String account, Object token, String tokenType, String sql, List<Object> binds,
                                 String warehouse, String database, String schema, String role, int timeoutSeconds) {
        if (sql == null || sql.isBlank()) throw new ActionException(P + ".NO_SQL", "The SQL statement is empty", "Write the SQL to run.");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("statement", sql);
        body.put("timeout", timeoutSeconds);
        if (warehouse != null && !warehouse.isBlank()) body.put("warehouse", warehouse);
        if (database != null && !database.isBlank()) body.put("database", database);
        if (schema != null && !schema.isBlank()) body.put("schema", schema);
        if (role != null && !role.isBlank()) body.put("role", role);
        Map<String, Object> b = bindings(binds);
        if (!b.isEmpty()) body.put("bindings", b);
        String url = api(account) + "?" + HttpJson.query(Map.of("requestId", UUID.randomUUID().toString()));
        HttpJson.Response r = failed(() -> req("POST", url, token, tokenType).json(body).timeout(120).send(ctx));
        return new Result(Values.text(r.map().get("statementHandle")), r.status(), r.map());
    }

    private static Result poll(ActionContext ctx, String account, Object token, String tokenType, String h, String partition) {
        String url = api(account) + "/" + handle(h) + (partition == null ? "" : "?partition=" + partition);
        HttpJson.Response r = failed(() -> req("GET", url, token, tokenType).timeout(120).send(ctx));
        return new Result(h, r.status(), r.map());
    }

    /** Snowflake reports a failed statement as HTTP 422; that becomes STATEMENT_FAILED with Snowflake's message. */
    private static HttpJson.Response failed(java.util.function.Supplier<HttpJson.Response> call) {
        try { return call.get(); }
        catch (ActionException e) {
            if (e.details() != null && BigDecimal.valueOf(422).equals(e.details().get("status")))
                throw new ActionException(P + ".STATEMENT_FAILED", e.getMessage(), "Check the SQL, the object names and the role's privileges.", false, e.details());
            throw e;
        }
    }

    private static Result waitFor(ActionContext ctx, String account, Object token, String tokenType, Result r, int waitSeconds) {
        long deadline = System.currentTimeMillis() + waitSeconds * 1000L;
        while (r.running() && System.currentTimeMillis() < deadline) {
            HttpJson.pause(ctx, pollMillis);
            r = poll(ctx, account, token, tokenType, r.handle(), null);
        }
        return r;
    }

    /** The result's column names. */
    private static List<String> columns(Result first) {
        Map<?, ?> meta = first.body().get("resultSetMetaData") instanceof Map<?, ?> m ? m : Map.of();
        List<String> cols = new ArrayList<>();
        if (meta.get("rowType") instanceof List<?> l) for (Object o : l) cols.add(String.valueOf(((Map<?, ?>) o).get("name")));
        return cols;
    }

    /**
     * Hands each row to {@code sink}, from the first result partition (already in hand) and then the remaining
     * partitions, up to max rows; returns the number of partitions.
     */
    private static int eachRow(ActionContext ctx, String account, Object token, String tokenType, Result first, long max, java.util.function.Consumer<List<Object>> sink) {
        Map<?, ?> meta = first.body().get("resultSetMetaData") instanceof Map<?, ?> m ? m : Map.of();
        List<?> rowType = meta.get("rowType") instanceof List<?> l ? l : List.of();
        int partitions = meta.get("partitionInfo") instanceof List<?> pl ? Math.max(1, pl.size()) : 1;
        long n = 0;
        Map<String, Object> page = first.body();
        for (int p = 0; p < partitions && n < max; p++) {
            if (p > 0) page = poll(ctx, account, token, tokenType, first.handle(), String.valueOf(p)).body();
            ctx.checkCancelled();
            for (Object o : (List<?>) page.getOrDefault("data", List.of())) {
                List<?> raw = (List<?>) o;
                List<Object> row = new ArrayList<>();
                for (int c = 0; c < rowType.size(); c++) row.add(cell(c < raw.size() ? raw.get(c) : null, (Map<?, ?>) rowType.get(c)));
                sink.accept(row);
                if (++n >= max) break;
            }
        }
        return partitions;
    }

    /** Reads the first result partition (already in hand) and then the remaining partitions, up to max rows. */
    private static Map<String, Object> table(ActionContext ctx, String account, Object token, String tokenType, Result first, int max) {
        Map<?, ?> meta = first.body().get("resultSetMetaData") instanceof Map<?, ?> m ? m : Map.of();
        List<Object> rows = new ArrayList<>();
        int partitions = eachRow(ctx, account, token, tokenType, first, max, rows::add);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("table", Values.table(columns(first), rows));
        out.put("partitions", BigDecimal.valueOf(partitions));
        out.put("total_rows", meta.get("numRows") instanceof BigDecimal n ? n : BigDecimal.valueOf(rows.size()));
        return out;
    }

    private static BigDecimal rowsAffected(Map<String, Object> body) {
        BigDecimal sum = BigDecimal.ZERO;
        if (body.get("stats") instanceof Map<?, ?> s)
            for (String k : List.of("numRowsInserted", "numRowsUpdated", "numRowsDeleted"))
                if (s.get(k) instanceof BigDecimal n) sum = sum.add(n);
        return sum;
    }

    private Map<String, Object> show(ActionContext ctx, String account, Object token, String tokenType, String role, String sql) {
        Result r = waitFor(ctx, account, token, tokenType, submit(ctx, account, token, tokenType, sql, null, null, null, null, role, 300), 300);
        if (r.running()) throw new ActionException(P + ".STILL_RUNNING", "Snowflake did not finish listing in time", "Try again in a moment.", true);
        Map<String, Object> t = table(ctx, account, token, tokenType, r, 100_000);
        @SuppressWarnings("unchecked") Map<String, Object> tbl = (Map<String, Object>) t.get("table");
        List<Object> names = new ArrayList<>();
        int idx = DataTablePackage.cols(tbl).indexOf("name");
        if (idx >= 0) for (List<Object> row : DataTablePackage.rows(tbl)) names.add(row.get(idx));
        return Out.of("table", tbl, "names", names);
    }

    // ----- statements -----

    @Action(id = "execute_statement", name = "Run SQL statement", category = "SQL", timeoutSeconds = 3600, capabilities = "network",
            description = "Run one SQL statement and return its rows as a data table. Use ? in the SQL for values passed in bindings.",
            panel = "account_url, token, sql, [Fill in ? placeholders: bindings] | warehouse, database, schema, role, wait_seconds, max_rows, token_type")
    @Output(name = "table", type = OoaType.TABLE, description = "Result rows (empty for statements that return no rows).")
    @Output(name = "row_count", type = OoaType.NUMBER, description = "Number of rows in the table.")
    @Output(name = "rows_affected", type = OoaType.NUMBER, description = "Rows inserted, updated or deleted by a data change.")
    @Output(name = "statement_handle", type = OoaType.STRING, description = "Id of the statement, for status, results or cancel.")
    @Output(name = "status", type = OoaType.STRING, description = "succeeded, or running when it did not finish within the wait time.")
    public Map<String, Object> execute(ActionContext ctx,
            @Input(name = "account_url", type = OoaType.STRING, required = false, pattern = "^https?://", description = D_URL) String account,
            @Input(name = "sql", type = OoaType.STRING, example = "SELECT * FROM ORDERS WHERE REGION = ? AND AMOUNT > ?", description = "SQL to run. Put ? where each binding value goes.") String sql,
            @Input(name = "bindings", type = OoaType.LIST, required = false, description = "Values for the ? placeholders, in order, e.g. [\"EMEA\", 1000].") List<Object> binds,
            @Input(name = "warehouse", type = OoaType.STRING, required = false, description = D_WH) String warehouse,
            @Input(name = "database", type = OoaType.STRING, required = false, description = "Database to use for names that are not fully written out.") String database,
            @Input(name = "schema", type = OoaType.STRING, required = false, description = "Schema to use for names that are not fully written out.") String schema,
            @Input(name = "role", type = OoaType.STRING, required = false, description = D_ROLE) String role,
            @Input(name = "wait_seconds", type = OoaType.NUMBER, required = false, defaultValue = "600", description = "How long to wait for the statement to finish before returning with status running.") int wait,
            @Input(name = "max_rows", type = OoaType.NUMBER, required = false, defaultValue = "100000", description = "Most rows to bring back into the table.") int max,
            @Input(name = "token", type = OoaType.CREDENTIAL, required = false, signInToken = true, description = D_TOKEN) Object token,
            @Input(name = "token_type", type = OoaType.STRING, required = false, defaultValue = "\"OAUTH\"", enumValues = {"OAUTH", "KEYPAIR_JWT", "PROGRAMMATIC_ACCESS_TOKEN"}, description = D_TOKEN_TYPE) String tokenType) {
        ApiSession __ss = ApiSession.find(ctx, null, "snowflake", "snowflake");   // G6: an open session fills what the step leaves empty
        account = ApiSession.text(__ss, "account_url", account);
        ApiSession.require("SNOWFLAKE", account, "account url");
        token = ApiSession.value(__ss, "token", token);
        ApiSession.requireSecret("SNOWFLAKE", token, "token");
        tokenType = ApiSession.text(__ss, "token_type", tokenType);
        Result r = waitFor(ctx, account, token, tokenType, submit(ctx, account, token, tokenType, sql, binds, warehouse, database, schema, role, Math.max(wait, 0) + 3600), wait);
        if (r.running())
            return Out.of("table", Values.table(List.of(), new ArrayList<>()), "row_count", BigDecimal.ZERO, "rows_affected", BigDecimal.ZERO,
                    "statement_handle", r.handle(), "status", "running");
        Map<String, Object> t = table(ctx, account, token, tokenType, r, max);
        @SuppressWarnings("unchecked") Map<String, Object> tbl = (Map<String, Object>) t.get("table");
        return Out.of("table", tbl, "row_count", BigDecimal.valueOf(DataTablePackage.rows(tbl).size()), "rows_affected", rowsAffected(r.body()),
                "statement_handle", r.handle(), "status", "succeeded");
    }

    @Action(id = "get_statement_status", name = "Get statement status", category = "SQL", idempotent = true, capabilities = "network",
            description = "Check whether a statement that was left running has finished, failed or is still going.",
            panel = "account_url, token, statement_handle | token_type")
    @Output(name = "status", type = OoaType.STRING, description = "running, succeeded or failed.")
    @Output(name = "message", type = OoaType.STRING, description = "Snowflake's message, including the error text when it failed.")
    @Output(name = "total_rows", type = OoaType.NUMBER, description = "Rows in the result once it succeeded.")
    public Map<String, Object> status(ActionContext ctx,
            @Input(name = "account_url", type = OoaType.STRING, required = false, pattern = "^https?://", description = D_URL) String account,
            @Input(name = "statement_handle", type = OoaType.STRING, description = "Handle from Run SQL statement.") String h,
            @Input(name = "token", type = OoaType.CREDENTIAL, required = false, signInToken = true, description = D_TOKEN) Object token,
            @Input(name = "token_type", type = OoaType.STRING, required = false, defaultValue = "\"OAUTH\"", enumValues = {"OAUTH", "KEYPAIR_JWT", "PROGRAMMATIC_ACCESS_TOKEN"}, description = D_TOKEN_TYPE) String tokenType) {
        ApiSession __ss = ApiSession.find(ctx, null, "snowflake", "snowflake");   // G6: an open session fills what the step leaves empty
        account = ApiSession.text(__ss, "account_url", account);
        ApiSession.require("SNOWFLAKE", account, "account url");
        token = ApiSession.value(__ss, "token", token);
        ApiSession.requireSecret("SNOWFLAKE", token, "token");
        tokenType = ApiSession.text(__ss, "token_type", tokenType);
        try {
            Result r = poll(ctx, account, token, tokenType, h, null);
            Map<?, ?> meta = r.body().get("resultSetMetaData") instanceof Map<?, ?> m ? m : Map.of();
            return Out.of("status", r.running() ? "running" : "succeeded", "message", r.body().get("message"),
                    "total_rows", r.running() ? BigDecimal.ZERO : meta.get("numRows") instanceof BigDecimal n ? n : BigDecimal.ZERO);
        } catch (ActionException e) {
            if (!e.code().equals(P + ".STATEMENT_FAILED")) throw e;
            return Out.of("status", "failed", "message", e.getMessage(), "total_rows", BigDecimal.ZERO);
        }
    }

    @Action(id = "cancel_statement", name = "Cancel statement", category = "SQL", capabilities = "network", description = "Stop a statement that is still running.",
            panel = "account_url, token, statement_handle | token_type")
    public Map<String, Object> cancel(ActionContext ctx,
            @Input(name = "account_url", type = OoaType.STRING, required = false, pattern = "^https?://", description = D_URL) String account,
            @Input(name = "statement_handle", type = OoaType.STRING, description = "Handle from Run SQL statement.") String h,
            @Input(name = "token", type = OoaType.CREDENTIAL, required = false, signInToken = true, description = D_TOKEN) Object token,
            @Input(name = "token_type", type = OoaType.STRING, required = false, defaultValue = "\"OAUTH\"", enumValues = {"OAUTH", "KEYPAIR_JWT", "PROGRAMMATIC_ACCESS_TOKEN"}, description = D_TOKEN_TYPE) String tokenType) {
        ApiSession __ss = ApiSession.find(ctx, null, "snowflake", "snowflake");   // G6: an open session fills what the step leaves empty
        account = ApiSession.text(__ss, "account_url", account);
        ApiSession.require("SNOWFLAKE", account, "account url");
        token = ApiSession.value(__ss, "token", token);
        ApiSession.requireSecret("SNOWFLAKE", token, "token");
        tokenType = ApiSession.text(__ss, "token_type", tokenType);
        req("POST", api(account) + "/" + handle(h) + "/cancel", token, tokenType).send(ctx);
        return Map.of();
    }

    @Action(id = "fetch_results", name = "Get statement results", category = "SQL", idempotent = true, timeoutSeconds = 3600, capabilities = "network",
            description = "Read the rows of a finished statement into a data table, page by page.",
            panel = "account_url, token, statement_handle | max_rows, token_type")
    @Output(name = "table", type = OoaType.TABLE, description = "Result rows.")
    @Output(name = "row_count", type = OoaType.NUMBER, description = "Number of rows in the table.")
    @Output(name = "total_rows", type = OoaType.NUMBER, description = "Rows in the full result, which can be more than max_rows.")
    public Map<String, Object> fetch(ActionContext ctx,
            @Input(name = "account_url", type = OoaType.STRING, required = false, pattern = "^https?://", description = D_URL) String account,
            @Input(name = "statement_handle", type = OoaType.STRING, description = "Handle from Run SQL statement.") String h,
            @Input(name = "max_rows", type = OoaType.NUMBER, required = false, defaultValue = "100000", description = "Most rows to bring back.") int max,
            @Input(name = "token", type = OoaType.CREDENTIAL, required = false, signInToken = true, description = D_TOKEN) Object token,
            @Input(name = "token_type", type = OoaType.STRING, required = false, defaultValue = "\"OAUTH\"", enumValues = {"OAUTH", "KEYPAIR_JWT", "PROGRAMMATIC_ACCESS_TOKEN"}, description = D_TOKEN_TYPE) String tokenType) {
        ApiSession __ss = ApiSession.find(ctx, null, "snowflake", "snowflake");   // G6: an open session fills what the step leaves empty
        account = ApiSession.text(__ss, "account_url", account);
        ApiSession.require("SNOWFLAKE", account, "account url");
        token = ApiSession.value(__ss, "token", token);
        ApiSession.requireSecret("SNOWFLAKE", token, "token");
        tokenType = ApiSession.text(__ss, "token_type", tokenType);
        Result r = poll(ctx, account, token, tokenType, h, null);
        if (r.running()) throw new ActionException(P + ".STILL_RUNNING", "The statement is still running", "Wait, then try again, or check it with Get statement status.", true);
        Map<String, Object> t = table(ctx, account, token, tokenType, r, max);
        @SuppressWarnings("unchecked") Map<String, Object> tbl = (Map<String, Object>) t.get("table");
        return Out.of("table", tbl, "row_count", BigDecimal.valueOf(DataTablePackage.rows(tbl).size()), "total_rows", t.get("total_rows"));
    }

    @Action(id = "export_results", name = "Export results to file", category = "SQL", timeoutSeconds = 3600, capabilities = "network,filesystem",
            description = "Run one SQL statement and write all of its rows to a file (CSV, tab-separated, JSON lines or Parquet), optionally compressed, without holding them all in memory.",
            panel = "account_url, token, sql, [Fill in ? placeholders: bindings], file, format, header, compression | overwrite, warehouse, database, schema, role, wait_seconds, token_type")
    @Output(name = "file", type = OoaType.FILE, description = "The file written.")
    @Output(name = "row_count", type = OoaType.NUMBER, description = "Rows written (not counting the header).")
    @Output(name = "statement_handle", type = OoaType.STRING, description = "Id of the statement.")
    public Map<String, Object> exportResults(ActionContext ctx,
            @Input(name = "account_url", type = OoaType.STRING, required = false, pattern = "^https?://", description = D_URL) String account,
            @Input(name = "sql", type = OoaType.STRING, example = "SELECT * FROM ORDERS WHERE ORDER_DATE >= ?", description = "SQL to run. Put ? where each binding value goes.") String sql,
            @Input(name = "file", type = OoaType.FILE, example = "exports/orders.csv", description = "File to write; add .gz yourself when compressing.") String file,
            @Input(name = "bindings", type = OoaType.LIST, required = false, description = "Values for the ? placeholders, in order, e.g. [\"2026-10-01\"].") List<Object> binds,
            @Input(name = "format", type = OoaType.STRING, required = false, defaultValue = "\"csv\"", enumValues = {"csv", "tsv", "json_lines", "parquet"},
                    description = "csv (comma-separated, quoted where needed), tsv (tab-separated), json_lines (one JSON object per row) or parquet (columnar, typed; G6).") String format,
            @Input(name = "header", type = OoaType.BOOLEAN, required = false, defaultValue = "true", visibleWhen = "format=csv|tsv", description = "Write the column names as the first line.") boolean header,
            @Input(name = "compression", type = OoaType.STRING, required = false, defaultValue = "\"none\"", enumValues = {"none", "gzip", "auto", "snappy"},
                    description = "none, or gzip to compress the file. For parquet: auto or snappy (snappy inside the file), gzip, or none. "
                            + "Auto on the other formats compresses with gzip when the file name ends with .gz (G6).") String compression,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Replace a file that already exists.") boolean overwrite,
            @Input(name = "warehouse", type = OoaType.STRING, required = false, description = D_WH) String warehouse,
            @Input(name = "database", type = OoaType.STRING, required = false, description = "Database to use for names that are not fully written out.") String database,
            @Input(name = "schema", type = OoaType.STRING, required = false, description = "Schema to use for names that are not fully written out.") String schema,
            @Input(name = "role", type = OoaType.STRING, required = false, description = D_ROLE) String role,
            @Input(name = "wait_seconds", type = OoaType.NUMBER, required = false, defaultValue = "3000", description = "How long to wait for the statement to finish.") int wait,
            @Input(name = "token", type = OoaType.CREDENTIAL, required = false, signInToken = true, description = D_TOKEN) Object token,
            @Input(name = "token_type", type = OoaType.STRING, required = false, defaultValue = "\"OAUTH\"", enumValues = {"OAUTH", "KEYPAIR_JWT", "PROGRAMMATIC_ACCESS_TOKEN"}, description = D_TOKEN_TYPE) String tokenType) {
        ApiSession __ss = ApiSession.find(ctx, null, "snowflake", "snowflake");   // G6: an open session fills what the step leaves empty
        account = ApiSession.text(__ss, "account_url", account);
        ApiSession.require("SNOWFLAKE", account, "account url");
        token = ApiSession.value(__ss, "token", token);
        ApiSession.requireSecret("SNOWFLAKE", token, "token");
        tokenType = ApiSession.text(__ss, "token_type", tokenType);
        String fmt = format == null || format.isBlank() ? "csv" : format.strip().toLowerCase(Locale.ROOT);
        if (!List.of("csv", "tsv", "json_lines", "parquet").contains(fmt)) throw new ActionException(P + ".BAD_FORMAT", "Unknown format '" + format + "'", "Use csv, tsv, json_lines or parquet.");
        String comp = compression == null || compression.isBlank() ? "none" : compression.strip().toLowerCase(Locale.ROOT);
        if (!List.of("none", "gzip", "auto", "snappy").contains(comp)) throw new ActionException(P + ".BAD_COMPRESSION", "Unknown compression '" + compression + "'", "Use none, gzip, auto or snappy.");
        if (comp.equals("snappy") && !fmt.equals("parquet")) throw new ActionException(P + ".BAD_COMPRESSION", "Snappy compression is only for parquet files", "Use gzip, or choose the parquet format.");
        java.nio.file.Path out = ctx.resolvePath(file);
        boolean gzip = comp.equals("gzip") || (comp.equals("auto") && !fmt.equals("parquet") && out.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gz"));
        if (java.nio.file.Files.exists(out) && !overwrite) throw new ActionException(P + ".FILE_EXISTS", "A file already exists at " + out, "Set overwrite to true, or choose another file.");
        Result r = waitFor(ctx, account, token, tokenType, submit(ctx, account, token, tokenType, sql, binds, warehouse, database, schema, role, Math.max(wait, 0) + 3600), wait);
        if (r.running()) throw new ActionException(P + ".STILL_RUNNING", "The statement did not finish within " + wait + " seconds", "Raise wait_seconds, or use Get statement status with the handle " + r.handle() + ".", true);
        List<String> cols = columns(r);
        long[] count = {0};
        java.nio.file.Path part = out.resolveSibling(out.getFileName() + ".part");
        try {
            if (out.getParent() != null) java.nio.file.Files.createDirectories(out.getParent());
            java.io.OutputStream raw = java.nio.file.Files.newOutputStream(part);
            if (fmt.equals("parquet")) {   // G6: typed columns, compressed inside the file
                Map<?, ?> meta = r.body().get("resultSetMetaData") instanceof Map<?, ?> m ? m : Map.of();
                List<?> rowType = meta.get("rowType") instanceof List<?> l ? l : List.of();
                int codec = comp.equals("gzip") ? ParquetWriter.GZIP : comp.equals("none") ? ParquetWriter.UNCOMPRESSED : ParquetWriter.SNAPPY;
                try (ParquetWriter pw = new ParquetWriter(new java.io.BufferedOutputStream(raw), cols, ParquetWriter.columnsOf(rowType), codec)) {
                    eachRow(ctx, account, token, tokenType, r, Long.MAX_VALUE, row -> {
                        try { pw.add(row); count[0]++; } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
                    });
                }
            } else try (java.io.Writer w = new java.io.BufferedWriter(new java.io.OutputStreamWriter(gzip ? new java.util.zip.GZIPOutputStream(raw) : raw, java.nio.charset.StandardCharsets.UTF_8))) {
                if (header && !fmt.equals("json_lines")) line(w, fmt, new ArrayList<>(cols));
                eachRow(ctx, account, token, tokenType, r, Long.MAX_VALUE, row -> {
                    try {
                        if (fmt.equals("json_lines")) {
                            Map<String, Object> o = new LinkedHashMap<>();
                            for (int c = 0; c < cols.size(); c++) o.put(cols.get(c), row.get(c));
                            w.write(JsonLite.write(o));
                            w.write("\n");
                        } else line(w, fmt, row);
                        count[0]++;
                    } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
                });
            }
            java.nio.file.Files.move(part, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.io.IOException | java.io.UncheckedIOException e) {
            try { java.nio.file.Files.deleteIfExists(part); } catch (java.io.IOException ignored) { }
            throw new ActionException(P + ".WRITE_FAILED", "Cannot write " + out + ": " + e.getMessage(), "Check the folder exists and can be written.");
        } catch (RuntimeException e) {
            try { java.nio.file.Files.deleteIfExists(part); } catch (java.io.IOException ignored) { }
            throw e;
        }
        return Out.of("file", out.toString(), "row_count", BigDecimal.valueOf(count[0]), "statement_handle", r.handle());
    }

    /** One CSV or TSV line: CSV quotes a value with a comma, quote or line break; TSV turns tabs and line breaks into spaces. */
    static void line(java.io.Writer w, String fmt, List<Object> values) throws java.io.IOException {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) w.write(fmt.equals("tsv") ? '\t' : ',');
            Object v = values.get(i);
            String t = v == null ? "" : v instanceof BigDecimal d ? d.toPlainString() : Values.text(v);
            if (fmt.equals("tsv")) w.write(t.replaceAll("[\t\r\n]", " "));
            else if (t.contains(",") || t.contains("\"") || t.contains("\n") || t.contains("\r")) w.write("\"" + t.replace("\"", "\"\"") + "\"");
            else w.write(t);
        }
        w.write(fmt.equals("csv") ? "\r\n" : "\n");
    }

    // ----- catalog -----

    @Action(id = "list_databases", name = "List databases", category = "Catalog", idempotent = true, timeoutSeconds = 600, capabilities = "network",
            description = "Return the databases the role can see.",
            panel = "account_url, token | role, token_type")
    @Output(name = "table", type = OoaType.TABLE, description = "One row per database with name, owner, comment and more.")
    @Output(name = "names", type = OoaType.LIST, description = "Database names only.")
    public Map<String, Object> listDatabases(ActionContext ctx,
            @Input(name = "account_url", type = OoaType.STRING, required = false, pattern = "^https?://", description = D_URL) String account,
            @Input(name = "role", type = OoaType.STRING, required = false, description = D_ROLE) String role,
            @Input(name = "token", type = OoaType.CREDENTIAL, required = false, signInToken = true, description = D_TOKEN) Object token,
            @Input(name = "token_type", type = OoaType.STRING, required = false, defaultValue = "\"OAUTH\"", enumValues = {"OAUTH", "KEYPAIR_JWT", "PROGRAMMATIC_ACCESS_TOKEN"}, description = D_TOKEN_TYPE) String tokenType) {
        ApiSession __ss = ApiSession.find(ctx, null, "snowflake", "snowflake");   // G6: an open session fills what the step leaves empty
        account = ApiSession.text(__ss, "account_url", account);
        ApiSession.require("SNOWFLAKE", account, "account url");
        token = ApiSession.value(__ss, "token", token);
        ApiSession.requireSecret("SNOWFLAKE", token, "token");
        tokenType = ApiSession.text(__ss, "token_type", tokenType);
        return show(ctx, account, token, tokenType, role, "SHOW DATABASES");
    }

    @Action(id = "list_schemas", name = "List schemas", category = "Catalog", idempotent = true, timeoutSeconds = 600, capabilities = "network",
            description = "Return the schemas in a database.",
            panel = "account_url, token, database | role, token_type")
    @Output(name = "table", type = OoaType.TABLE, description = "One row per schema.")
    @Output(name = "names", type = OoaType.LIST, description = "Schema names only.")
    public Map<String, Object> listSchemas(ActionContext ctx,
            @Input(name = "account_url", type = OoaType.STRING, required = false, pattern = "^https?://", description = D_URL) String account,
            @Input(name = "database", type = OoaType.STRING, description = "Database to look in.") String database,
            @Input(name = "role", type = OoaType.STRING, required = false, description = D_ROLE) String role,
            @Input(name = "token", type = OoaType.CREDENTIAL, required = false, signInToken = true, description = D_TOKEN) Object token,
            @Input(name = "token_type", type = OoaType.STRING, required = false, defaultValue = "\"OAUTH\"", enumValues = {"OAUTH", "KEYPAIR_JWT", "PROGRAMMATIC_ACCESS_TOKEN"}, description = D_TOKEN_TYPE) String tokenType) {
        ApiSession __ss = ApiSession.find(ctx, null, "snowflake", "snowflake");   // G6: an open session fills what the step leaves empty
        account = ApiSession.text(__ss, "account_url", account);
        ApiSession.require("SNOWFLAKE", account, "account url");
        token = ApiSession.value(__ss, "token", token);
        ApiSession.requireSecret("SNOWFLAKE", token, "token");
        tokenType = ApiSession.text(__ss, "token_type", tokenType);
        return show(ctx, account, token, tokenType, role, "SHOW SCHEMAS IN DATABASE " + ident(database));
    }

    @Action(id = "list_tables", name = "List tables", category = "Catalog", idempotent = true, timeoutSeconds = 600, capabilities = "network",
            description = "Return the tables in a schema.",
            panel = "account_url, token, database, schema | role, token_type")
    @Output(name = "table", type = OoaType.TABLE, description = "One row per table with name, row count, size and more.")
    @Output(name = "names", type = OoaType.LIST, description = "Table names only.")
    public Map<String, Object> listTables(ActionContext ctx,
            @Input(name = "account_url", type = OoaType.STRING, required = false, pattern = "^https?://", description = D_URL) String account,
            @Input(name = "database", type = OoaType.STRING, description = "Database to look in.") String database,
            @Input(name = "schema", type = OoaType.STRING, description = "Schema to look in, e.g. PUBLIC.") String schema,
            @Input(name = "role", type = OoaType.STRING, required = false, description = D_ROLE) String role,
            @Input(name = "token", type = OoaType.CREDENTIAL, required = false, signInToken = true, description = D_TOKEN) Object token,
            @Input(name = "token_type", type = OoaType.STRING, required = false, defaultValue = "\"OAUTH\"", enumValues = {"OAUTH", "KEYPAIR_JWT", "PROGRAMMATIC_ACCESS_TOKEN"}, description = D_TOKEN_TYPE) String tokenType) {
        ApiSession __ss = ApiSession.find(ctx, null, "snowflake", "snowflake");   // G6: an open session fills what the step leaves empty
        account = ApiSession.text(__ss, "account_url", account);
        ApiSession.require("SNOWFLAKE", account, "account url");
        token = ApiSession.value(__ss, "token", token);
        ApiSession.requireSecret("SNOWFLAKE", token, "token");
        tokenType = ApiSession.text(__ss, "token_type", tokenType);
        return show(ctx, account, token, tokenType, role, "SHOW TABLES IN SCHEMA " + ident(database) + "." + ident(schema));
    }

    @Action(id = "list_columns", name = "List columns", category = "Catalog", idempotent = true, timeoutSeconds = 600, capabilities = "network",
            description = "Return the columns of a table with their data types.",
            panel = "account_url, token, table_name | role, token_type")
    @Output(name = "table", type = OoaType.TABLE, description = "One row per column with name, type, nullability and default.")
    @Output(name = "names", type = OoaType.LIST, description = "Column names only.")
    public Map<String, Object> listColumns(ActionContext ctx,
            @Input(name = "account_url", type = OoaType.STRING, required = false, pattern = "^https?://", description = D_URL) String account,
            @Input(name = "table_name", type = OoaType.STRING, example = "SALES.PUBLIC.ORDERS", description = "Table, written as DATABASE.SCHEMA.TABLE.") String table,
            @Input(name = "role", type = OoaType.STRING, required = false, description = D_ROLE) String role,
            @Input(name = "token", type = OoaType.CREDENTIAL, required = false, signInToken = true, description = D_TOKEN) Object token,
            @Input(name = "token_type", type = OoaType.STRING, required = false, defaultValue = "\"OAUTH\"", enumValues = {"OAUTH", "KEYPAIR_JWT", "PROGRAMMATIC_ACCESS_TOKEN"}, description = D_TOKEN_TYPE) String tokenType) {
        ApiSession __ss = ApiSession.find(ctx, null, "snowflake", "snowflake");   // G6: an open session fills what the step leaves empty
        account = ApiSession.text(__ss, "account_url", account);
        ApiSession.require("SNOWFLAKE", account, "account url");
        token = ApiSession.value(__ss, "token", token);
        ApiSession.requireSecret("SNOWFLAKE", token, "token");
        tokenType = ApiSession.text(__ss, "token_type", tokenType);
        return show(ctx, account, token, tokenType, role, "DESCRIBE TABLE " + qualified(table));
    }

    @Action(id = "list_warehouses", name = "List warehouses", category = "Catalog", idempotent = true, timeoutSeconds = 600, capabilities = "network",
            description = "Return the warehouses the role can use, with their size and state.",
            panel = "account_url, token | role, token_type")
    @Output(name = "table", type = OoaType.TABLE, description = "One row per warehouse.")
    @Output(name = "names", type = OoaType.LIST, description = "Warehouse names only.")
    public Map<String, Object> listWarehouses(ActionContext ctx,
            @Input(name = "account_url", type = OoaType.STRING, required = false, pattern = "^https?://", description = D_URL) String account,
            @Input(name = "role", type = OoaType.STRING, required = false, description = D_ROLE) String role,
            @Input(name = "token", type = OoaType.CREDENTIAL, required = false, signInToken = true, description = D_TOKEN) Object token,
            @Input(name = "token_type", type = OoaType.STRING, required = false, defaultValue = "\"OAUTH\"", enumValues = {"OAUTH", "KEYPAIR_JWT", "PROGRAMMATIC_ACCESS_TOKEN"}, description = D_TOKEN_TYPE) String tokenType) {
        ApiSession __ss = ApiSession.find(ctx, null, "snowflake", "snowflake");   // G6: an open session fills what the step leaves empty
        account = ApiSession.text(__ss, "account_url", account);
        ApiSession.require("SNOWFLAKE", account, "account url");
        token = ApiSession.value(__ss, "token", token);
        ApiSession.requireSecret("SNOWFLAKE", token, "token");
        tokenType = ApiSession.text(__ss, "token_type", tokenType);
        return show(ctx, account, token, tokenType, role, "SHOW WAREHOUSES");
    }

    // ----- loading -----

    @Action(id = "insert_rows", name = "Insert rows from data table", category = "Loading", timeoutSeconds = 3600, capabilities = "network",
            description = "Add every row of a data table to a Snowflake table. Columns are matched by name.",
            panel = "account_url, token, table_name, data | batch_size, warehouse, database, schema, role, token_type")
    @Output(name = "rows_inserted", type = OoaType.NUMBER, description = "Number of rows added.")
    public Map<String, Object> insert(ActionContext ctx,
            @Input(name = "account_url", type = OoaType.STRING, required = false, pattern = "^https?://", description = D_URL) String account,
            @Input(name = "table_name", type = OoaType.STRING, example = "SALES.PUBLIC.ORDERS", description = "Target table, written as DATABASE.SCHEMA.TABLE (or just TABLE with database and schema set).") String target,
            @Input(name = "data", type = OoaType.TABLE, description = "Rows to add. Its column names must match columns of the target table.") Map<String, Object> data,
            @Input(name = "batch_size", type = OoaType.NUMBER, required = false, defaultValue = "500", description = "Rows sent per request.") int batch,
            @Input(name = "warehouse", type = OoaType.STRING, required = false, description = D_WH) String warehouse,
            @Input(name = "database", type = OoaType.STRING, required = false, description = "Database of the table when table_name has no database.") String database,
            @Input(name = "schema", type = OoaType.STRING, required = false, description = "Schema of the table when table_name has no schema.") String schema,
            @Input(name = "role", type = OoaType.STRING, required = false, description = D_ROLE) String role,
            @Input(name = "token", type = OoaType.CREDENTIAL, required = false, signInToken = true, description = D_TOKEN) Object token,
            @Input(name = "token_type", type = OoaType.STRING, required = false, defaultValue = "\"OAUTH\"", enumValues = {"OAUTH", "KEYPAIR_JWT", "PROGRAMMATIC_ACCESS_TOKEN"}, description = D_TOKEN_TYPE) String tokenType) {
        ApiSession __ss = ApiSession.find(ctx, null, "snowflake", "snowflake");   // G6: an open session fills what the step leaves empty
        account = ApiSession.text(__ss, "account_url", account);
        ApiSession.require("SNOWFLAKE", account, "account url");
        token = ApiSession.value(__ss, "token", token);
        ApiSession.requireSecret("SNOWFLAKE", token, "token");
        tokenType = ApiSession.text(__ss, "token_type", tokenType);
        List<String> cols = DataTablePackage.cols(data);
        List<List<Object>> rows = DataTablePackage.rows(data);
        if (cols.isEmpty()) throw new ActionException(P + ".NO_COLUMNS", "The data table has no columns", "Build the table with column names that match the target table.");
        StringJoiner colList = new StringJoiner(", ", "(", ")");
        for (String c : cols) colList.add(ident(c));
        String rowMarks = "(" + String.join(", ", Collections.nCopies(cols.size(), "?")) + ")";
        int size = Math.max(1, Math.min(batch, 16_000 / cols.size()));
        long inserted = 0;
        for (int i = 0; i < rows.size(); i += size) {
            List<List<Object>> chunk = rows.subList(i, Math.min(rows.size(), i + size));
            List<Object> binds = new ArrayList<>();
            for (List<Object> row : chunk) for (int c = 0; c < cols.size(); c++) binds.add(DataTablePackage.cell(row, c));
            String sql = "INSERT INTO " + qualified(target) + " " + colList + " VALUES " + String.join(", ", Collections.nCopies(chunk.size(), rowMarks));
            Result r = waitFor(ctx, account, token, tokenType, submit(ctx, account, token, tokenType, sql, binds, warehouse, database, schema, role, 3600), 3600);
            if (r.running()) throw new ActionException(P + ".STILL_RUNNING", "Snowflake did not finish the insert in time", "Use a larger warehouse or a smaller batch_size.", true);
            BigDecimal n = rowsAffected(r.body());
            inserted += n.signum() > 0 ? n.longValue() : chunk.size();
        }
        return Out.of("rows_inserted", BigDecimal.valueOf(inserted));
    }

    // ----- G6: loading a file, changing rows by key, stored procedures -----

    private static final Pattern STAGE_PATH = Pattern.compile("^@[A-Za-z0-9_$.%\"/=-]+$");

    /** The stage a table owns, written @DB.SCHEMA.%TABLE (or @%TABLE). */
    static String tableStage(String table) {
        String q = qualified(table);
        int dot = q.lastIndexOf('.', q.endsWith("\"") ? q.lastIndexOf('"', q.length() - 2) : q.length());
        return "@" + (dot < 0 ? "%" + q : q.substring(0, dot + 1) + "%" + q.substring(dot + 1));
    }

    /** COPY's ON_ERROR value for a choice and its number. */
    static String onError(String choice, int limit) {
        String c = choice == null || choice.isBlank() ? "abort" : choice.strip().toLowerCase(Locale.ROOT);
        return switch (c) {
            case "abort" -> "ABORT_STATEMENT";
            case "continue" -> "CONTINUE";
            case "skip_file" -> "SKIP_FILE";
            case "skip_file_after_rows" -> { if (limit < 1) throw new ActionException(P + ".BAD_ON_ERROR", "Give the number of bad rows after which a file is skipped", "Set on_error_limit to 1 or more."); yield "'SKIP_FILE_" + limit + "'"; }
            case "skip_file_after_percent" -> { if (limit < 1 || limit > 100) throw new ActionException(P + ".BAD_ON_ERROR", "Give the percent of bad rows after which a file is skipped", "Set on_error_limit between 1 and 100."); yield "'SKIP_FILE_" + limit + "%'"; }
            default -> throw new ActionException(P + ".BAD_ON_ERROR", "Unknown on_error '" + choice + "'", "Use abort, continue, skip_file, skip_file_after_rows or skip_file_after_percent.");
        };
    }

    private Result runToEnd(ActionContext ctx, String account, Object token, String tokenType, String sql, List<Object> binds, String warehouse, String database, String schema, String role, int wait) {
        Result r = waitFor(ctx, account, token, tokenType, submit(ctx, account, token, tokenType, sql, binds, warehouse, database, schema, role, Math.max(wait, 0) + 3600), wait);
        if (r.running()) throw new ActionException(P + ".STILL_RUNNING", "The statement did not finish within " + wait + " seconds", "Raise wait_seconds, or use Get statement status with the handle " + r.handle() + ".", true);
        return r;
    }

    @Action(id = "load_file", name = "Load file into table", category = "Loading", timeoutSeconds = 7200, capabilities = {"network", "filesystem"},
            description = "Load a CSV or Parquet file into a table: a file on this machine is first put on the table's stage or a named stage, a file already on a stage is used as it is; then Snowflake copies it in (G6).",
            panel = "account_url, token, table_name, file, stage, format, [Skip header lines: skip_header], on_error, on_error_limit | warehouse, database, schema, role, wait_seconds, token_type")
    @Output(name = "rows_loaded", type = OoaType.NUMBER, description = "Rows added to the table.")
    @Output(name = "errors_seen", type = OoaType.NUMBER, description = "Bad rows Snowflake found (skipped ones included).")
    @Output(name = "results", type = OoaType.TABLE, description = "Snowflake's result: one row per file with its status, rows loaded and first error.")
    @Output(name = "statement_handle", type = OoaType.STRING, description = "Id of the copy statement.")
    public Map<String, Object> loadFile(ActionContext ctx,
            @Input(name = "account_url", type = OoaType.STRING, required = false, pattern = "^https?://", description = D_URL) String account,
            @Input(name = "table_name", type = OoaType.STRING, example = "SALES.PUBLIC.ORDERS", description = "Table to load into, written as DATABASE.SCHEMA.TABLE (or just TABLE with database and schema set).") String target,
            @Input(name = "file", type = OoaType.FILE, example = "exports/orders.csv",
                    description = "A CSV or Parquet file on this machine, or a path on a stage such as @MY_STAGE/orders/ (then nothing is uploaded).") String file,
            @Input(name = "stage", type = OoaType.STRING, required = false, example = "SALES.PUBLIC.INBOX",
                    description = "Named stage to put a local file on; the table's own stage when empty. The uploaded copy is removed once it is loaded.") String stage,
            @Input(name = "format", type = OoaType.STRING, required = false, defaultValue = "\"csv\"", enumValues = {"csv", "parquet"},
                    description = "csv (comma-separated, values may be in double quotes) or parquet (columns matched to the table's by name).") String format,
            @Input(name = "skip_header", type = OoaType.NUMBER, required = false, defaultValue = "1", min = 0, visibleWhen = "format=csv",
                    description = "Lines at the top of a CSV file that are not data, such as the column names.") int skipHeader,
            @Input(name = "on_error", type = OoaType.STRING, required = false, defaultValue = "\"abort\"",
                    enumValues = {"abort", "continue", "skip_file", "skip_file_after_rows", "skip_file_after_percent"},
                    description = "On a bad row: abort (load nothing), continue (load the good rows), skip_file (leave out the file), or skip the file once it has on_error_limit bad rows or that percent of bad rows.") String onError,
            @Input(name = "on_error_limit", type = OoaType.NUMBER, required = false, defaultValue = "1", min = 1, visibleWhen = "on_error=skip_file_after_rows|skip_file_after_percent",
                    description = "The number of bad rows, or the percent of bad rows, after which a file is skipped.") int onErrorLimit,
            @Input(name = "wait_seconds", type = OoaType.NUMBER, required = false, defaultValue = "3000", description = "How long to wait for each statement to finish.") int wait,
            @Input(name = "warehouse", type = OoaType.STRING, required = false, description = D_WH) String warehouse,
            @Input(name = "database", type = OoaType.STRING, required = false, description = "Database of the table when table_name has no database.") String database,
            @Input(name = "schema", type = OoaType.STRING, required = false, description = "Schema of the table when table_name has no schema.") String schema,
            @Input(name = "role", type = OoaType.STRING, required = false, description = D_ROLE) String role,
            @Input(name = "token", type = OoaType.CREDENTIAL, required = false, signInToken = true, description = D_TOKEN) Object token,
            @Input(name = "token_type", type = OoaType.STRING, required = false, defaultValue = "\"OAUTH\"", enumValues = {"OAUTH", "KEYPAIR_JWT", "PROGRAMMATIC_ACCESS_TOKEN"}, description = D_TOKEN_TYPE) String tokenType) {
        ApiSession __ss = ApiSession.find(ctx, null, "snowflake", "snowflake");   // G6: an open session fills what the step leaves empty
        account = ApiSession.text(__ss, "account_url", account);
        ApiSession.require("SNOWFLAKE", account, "account url");
        token = ApiSession.value(__ss, "token", token);
        ApiSession.requireSecret("SNOWFLAKE", token, "token");
        tokenType = ApiSession.text(__ss, "token_type", tokenType);
        String fmt = format == null || format.isBlank() ? "csv" : format.strip().toLowerCase(Locale.ROOT);
        if (!List.of("csv", "parquet").contains(fmt)) throw new ActionException(P + ".BAD_FORMAT", "Unknown format '" + format + "'", "Use csv or parquet.");
        if (file == null || file.isBlank()) throw new ActionException(P + ".NO_FILE", "No file given", "Give a file on this machine, or a stage path such as @MY_STAGE/orders/.");
        String errors = onError(onError, onErrorLimit);
        String table = qualified(target);
        String from;
        boolean uploaded = false;
        if (file.strip().startsWith("@")) {
            from = file.strip();
            if (!STAGE_PATH.matcher(from).matches()) throw new ActionException(P + ".BAD_STAGE", "Invalid stage path '" + file + "'", "Example: @MY_STAGE/orders/ or @%ORDERS.");
        } else {
            java.nio.file.Path local = ctx.resolvePath(file.strip()).toAbsolutePath().normalize();
            if (!java.nio.file.Files.isRegularFile(local)) throw new ActionException("FILE.NOT_FOUND", "Cannot read " + local, "Check the path.");
            String uri = local.toString().replace('\\', '/');
            if (uri.contains("'")) throw new ActionException(P + ".BAD_FILE_NAME", "The file path has a quote in it: " + local, "Rename the file or folder without the quote.");
            String base = (stage == null || stage.isBlank() ? tableStage(target) : "@" + qualified(stage)) + "/ooa-load-" + UUID.randomUUID().toString().substring(0, 12) + "/";
            try {
                runToEnd(ctx, account, token, tokenType, "PUT 'file://" + uri + "' " + base + " AUTO_COMPRESS = TRUE OVERWRITE = TRUE",
                        null, warehouse, database, schema, role, wait);
            } catch (ActionException e) {
                if (!e.code().equals(P + ".STATEMENT_FAILED")) throw e;
                throw new ActionException(P + ".UPLOAD_FAILED", "Snowflake did not take the file: " + e.getMessage(),
                        "Check the role may write to the stage. If uploads are not accepted over this connection, put the file on a stage first and give its @stage path in file.", false, e.details());
            }
            from = base;
            uploaded = true;
        }
        String fileFormat = fmt.equals("csv")
                ? "FILE_FORMAT = (TYPE = CSV SKIP_HEADER = " + Math.max(0, skipHeader) + " FIELD_OPTIONALLY_ENCLOSED_BY = '\"')"
                : "FILE_FORMAT = (TYPE = PARQUET) MATCH_BY_COLUMN_NAME = CASE_INSENSITIVE";
        String sql = "COPY INTO " + table + " FROM " + from + " " + fileFormat + " ON_ERROR = " + errors + (uploaded ? " PURGE = TRUE" : "");
        Result r = runToEnd(ctx, account, token, tokenType, sql, null, warehouse, database, schema, role, wait);
        Map<String, Object> t = table(ctx, account, token, tokenType, r, 100_000);
        @SuppressWarnings("unchecked") Map<String, Object> tbl = (Map<String, Object>) t.get("table");
        List<String> cols = DataTablePackage.cols(tbl);
        long loaded = 0, seen = 0;
        int li = cols.indexOf("rows_loaded"), ei = cols.indexOf("errors_seen");
        for (List<Object> row : DataTablePackage.rows(tbl)) {
            if (li >= 0 && row.get(li) instanceof BigDecimal n) loaded += n.longValue();
            if (ei >= 0 && row.get(ei) instanceof BigDecimal n) seen += n.longValue();
        }
        if (li < 0) loaded = rowsAffected(r.body()).longValue();
        return Out.of("rows_loaded", BigDecimal.valueOf(loaded), "errors_seen", BigDecimal.valueOf(seen), "results", tbl, "statement_handle", r.handle());
    }

    /** Most key values put into one statement. */
    static final int KEYS_PER_STATEMENT = 1000;

    private long byKeys(ActionContext ctx, String account, Object token, String tokenType, String sqlStart, List<Object> leading, String keyColumn, List<Object> keys,
                        String warehouse, String database, String schema, String role) {
        if (keys == null || keys.isEmpty()) throw new ActionException(P + ".NO_KEYS", "No key values were given", "Give the key values of the rows to change; an empty list would change every row.");
        String key = ident(keyColumn);
        long changed = 0;
        for (int i = 0; i < keys.size(); i += KEYS_PER_STATEMENT) {
            List<Object> chunk = keys.subList(i, Math.min(keys.size(), i + KEYS_PER_STATEMENT));
            List<Object> binds = new ArrayList<>(leading);
            binds.addAll(chunk);
            String sql = sqlStart + " WHERE " + key + " IN (" + String.join(", ", Collections.nCopies(chunk.size(), "?")) + ")";
            changed += rowsAffected(runToEnd(ctx, account, token, tokenType, sql, binds, warehouse, database, schema, role, 3600).body()).longValue();
        }
        return changed;
    }

    @Action(id = "delete_rows", name = "Delete rows by key", category = "Loading", timeoutSeconds = 3600, capabilities = "network",
            description = "Delete the rows of a table whose key column has one of the values given; the values are sent apart from the SQL (G6).",
            panel = "account_url, token, table_name, key_column, key_values | warehouse, database, schema, role, token_type")
    @Output(name = "rows_changed", type = OoaType.NUMBER, description = "Rows deleted.")
    public Map<String, Object> deleteRows(ActionContext ctx,
            @Input(name = "account_url", type = OoaType.STRING, required = false, pattern = "^https?://", description = D_URL) String account,
            @Input(name = "table_name", type = OoaType.STRING, example = "SALES.PUBLIC.ORDERS", description = "Table, written as DATABASE.SCHEMA.TABLE (or just TABLE with database and schema set).") String target,
            @Input(name = "key_column", type = OoaType.STRING, example = "ORDER_ID", description = "Column that identifies a row.") String keyColumn,
            @Input(name = "key_values", type = OoaType.LIST, example = "[1001, 1002]", description = "Key values of the rows to delete.") List<Object> keys,
            @Input(name = "warehouse", type = OoaType.STRING, required = false, description = D_WH) String warehouse,
            @Input(name = "database", type = OoaType.STRING, required = false, description = "Database of the table when table_name has no database.") String database,
            @Input(name = "schema", type = OoaType.STRING, required = false, description = "Schema of the table when table_name has no schema.") String schema,
            @Input(name = "role", type = OoaType.STRING, required = false, description = D_ROLE) String role,
            @Input(name = "token", type = OoaType.CREDENTIAL, required = false, signInToken = true, description = D_TOKEN) Object token,
            @Input(name = "token_type", type = OoaType.STRING, required = false, defaultValue = "\"OAUTH\"", enumValues = {"OAUTH", "KEYPAIR_JWT", "PROGRAMMATIC_ACCESS_TOKEN"}, description = D_TOKEN_TYPE) String tokenType) {
        ApiSession __ss = ApiSession.find(ctx, null, "snowflake", "snowflake");   // G6: an open session fills what the step leaves empty
        account = ApiSession.text(__ss, "account_url", account);
        ApiSession.require("SNOWFLAKE", account, "account url");
        token = ApiSession.value(__ss, "token", token);
        ApiSession.requireSecret("SNOWFLAKE", token, "token");
        tokenType = ApiSession.text(__ss, "token_type", tokenType);
        String sql = "DELETE FROM " + qualified(target);
        return Out.of("rows_changed", BigDecimal.valueOf(byKeys(ctx, account, token, tokenType, sql, List.of(), keyColumn, keys, warehouse, database, schema, role)));
    }

    @Action(id = "update_rows", name = "Update rows by key", category = "Loading", timeoutSeconds = 3600, capabilities = "network",
            description = "Set columns to new values in the rows of a table whose key column has one of the values given; the values are sent apart from the SQL (G6).",
            panel = "account_url, token, table_name, key_column, key_values, values | warehouse, database, schema, role, token_type")
    @Output(name = "rows_changed", type = OoaType.NUMBER, description = "Rows updated.")
    public Map<String, Object> updateRows(ActionContext ctx,
            @Input(name = "account_url", type = OoaType.STRING, required = false, pattern = "^https?://", description = D_URL) String account,
            @Input(name = "table_name", type = OoaType.STRING, example = "SALES.PUBLIC.ORDERS", description = "Table, written as DATABASE.SCHEMA.TABLE (or just TABLE with database and schema set).") String target,
            @Input(name = "key_column", type = OoaType.STRING, example = "ORDER_ID", description = "Column that identifies a row.") String keyColumn,
            @Input(name = "key_values", type = OoaType.LIST, example = "[1001, 1002]", description = "Key values of the rows to update.") List<Object> keys,
            @Input(name = "values", type = OoaType.DICTIONARY, example = "{\"STATUS\": \"SHIPPED\", \"SHIPPED_ON\": \"2026-11-22\"}", description = "Column to its new value.") Map<String, Object> values,
            @Input(name = "warehouse", type = OoaType.STRING, required = false, description = D_WH) String warehouse,
            @Input(name = "database", type = OoaType.STRING, required = false, description = "Database of the table when table_name has no database.") String database,
            @Input(name = "schema", type = OoaType.STRING, required = false, description = "Schema of the table when table_name has no schema.") String schema,
            @Input(name = "role", type = OoaType.STRING, required = false, description = D_ROLE) String role,
            @Input(name = "token", type = OoaType.CREDENTIAL, required = false, signInToken = true, description = D_TOKEN) Object token,
            @Input(name = "token_type", type = OoaType.STRING, required = false, defaultValue = "\"OAUTH\"", enumValues = {"OAUTH", "KEYPAIR_JWT", "PROGRAMMATIC_ACCESS_TOKEN"}, description = D_TOKEN_TYPE) String tokenType) {
        ApiSession __ss = ApiSession.find(ctx, null, "snowflake", "snowflake");   // G6: an open session fills what the step leaves empty
        account = ApiSession.text(__ss, "account_url", account);
        ApiSession.require("SNOWFLAKE", account, "account url");
        token = ApiSession.value(__ss, "token", token);
        ApiSession.requireSecret("SNOWFLAKE", token, "token");
        tokenType = ApiSession.text(__ss, "token_type", tokenType);
        if (values == null || values.isEmpty()) throw new ActionException(P + ".NOTHING_TO_UPDATE", "No new values were given", "Fill values with column names and their new values.");
        StringJoiner set = new StringJoiner(", ");
        List<Object> binds = new ArrayList<>();
        for (Map.Entry<String, Object> e : values.entrySet()) { set.add(ident(e.getKey()) + " = ?"); binds.add(e.getValue()); }
        String sql = "UPDATE " + qualified(target) + " SET " + set;
        return Out.of("rows_changed", BigDecimal.valueOf(byKeys(ctx, account, token, tokenType, sql, binds, keyColumn, keys, warehouse, database, schema, role)));
    }

    @Action(id = "call_procedure", name = "Run stored procedure", category = "SQL", timeoutSeconds = 3600, capabilities = "network",
            description = "Call a stored procedure with values for its arguments, sent apart from the SQL, and return what it gives back (G6).",
            panel = "account_url, token, procedure, parameters | warehouse, database, schema, role, wait_seconds, token_type")
    @Output(name = "result", type = OoaType.ANY, description = "The value the procedure returned (the first cell of its result).")
    @Output(name = "table", type = OoaType.TABLE, description = "The whole result, for a procedure that returns a table.")
    @Output(name = "statement_handle", type = OoaType.STRING, description = "Id of the statement.")
    public Map<String, Object> callProcedure(ActionContext ctx,
            @Input(name = "account_url", type = OoaType.STRING, required = false, pattern = "^https?://", description = D_URL) String account,
            @Input(name = "procedure", type = OoaType.STRING, example = "SALES.PUBLIC.CLOSE_MONTH", description = "Procedure name, written as DATABASE.SCHEMA.NAME (or just NAME).") String procedure,
            @Input(name = "parameters", type = OoaType.ANY, required = false, example = "{\"MONTH\": \"2026-10\"}",
                    description = "Argument values: a list in the procedure's order, or a dictionary of argument name to value.") Object params,
            @Input(name = "wait_seconds", type = OoaType.NUMBER, required = false, defaultValue = "3000", description = "How long to wait for the procedure to finish.") int wait,
            @Input(name = "warehouse", type = OoaType.STRING, required = false, description = D_WH) String warehouse,
            @Input(name = "database", type = OoaType.STRING, required = false, description = "Database of the table when table_name has no database.") String database,
            @Input(name = "schema", type = OoaType.STRING, required = false, description = "Schema of the table when table_name has no schema.") String schema,
            @Input(name = "role", type = OoaType.STRING, required = false, description = D_ROLE) String role,
            @Input(name = "token", type = OoaType.CREDENTIAL, required = false, signInToken = true, description = D_TOKEN) Object token,
            @Input(name = "token_type", type = OoaType.STRING, required = false, defaultValue = "\"OAUTH\"", enumValues = {"OAUTH", "KEYPAIR_JWT", "PROGRAMMATIC_ACCESS_TOKEN"}, description = D_TOKEN_TYPE) String tokenType) {
        ApiSession __ss = ApiSession.find(ctx, null, "snowflake", "snowflake");   // G6: an open session fills what the step leaves empty
        account = ApiSession.text(__ss, "account_url", account);
        ApiSession.require("SNOWFLAKE", account, "account url");
        token = ApiSession.value(__ss, "token", token);
        ApiSession.requireSecret("SNOWFLAKE", token, "token");
        tokenType = ApiSession.text(__ss, "token_type", tokenType);
        StringJoiner args = new StringJoiner(", ", "(", ")");
        List<Object> binds = new ArrayList<>();
        if (params instanceof Map<?, ?> m) for (Map.Entry<?, ?> e : m.entrySet()) { args.add(ident(String.valueOf(e.getKey())) + " => ?"); binds.add(e.getValue()); }
        else if (params instanceof List<?> l) for (Object v : l) { args.add("?"); binds.add(v); }
        else if (params != null && !(params instanceof String t && t.isBlank()))
            throw new ActionException(P + ".BAD_PARAMETERS", "The parameters must be a list or a dictionary", "Example: [\"2026-10\"] or {\"MONTH\": \"2026-10\"}.");
        Result r = runToEnd(ctx, account, token, tokenType, "CALL " + qualified(procedure) + args, binds, warehouse, database, schema, role, wait);
        Map<String, Object> t = table(ctx, account, token, tokenType, r, 100_000);
        @SuppressWarnings("unchecked") Map<String, Object> tbl = (Map<String, Object>) t.get("table");
        List<List<Object>> rows = DataTablePackage.rows(tbl);
        return Out.of("result", rows.isEmpty() || rows.get(0).isEmpty() ? null : rows.get(0).get(0), "table", tbl, "statement_handle", r.handle());
    }

    /**
     * A small Parquet file writer (G6) for export_results: one data page per column and row group, plain encoding,
     * optional columns, and snappy, gzip or no compression. Types: BOOLEAN, INT64 (with DECIMAL for scaled numbers up to
     * 18 digits), DOUBLE, INT32 DATE, and UTF-8 text for everything else.
     */
    static final class ParquetWriter implements java.io.Closeable {
        static final int UNCOMPRESSED = 0, SNAPPY = 1, GZIP = 2;
        static final int ROWS_PER_GROUP = 50_000;
        /** Column kinds: physical type, plus decimal scale and precision. */
        record Col(int type, int scale, int precision, boolean date) {
            static final int BOOLEAN = 0, INT32 = 1, INT64 = 2, DOUBLE = 5, BYTE_ARRAY = 6;
        }

        private final java.io.OutputStream out;
        private final List<String> names;
        private final List<Col> cols;
        private final int codec;
        private final List<List<Object>> buffer = new ArrayList<>();
        private final List<byte[]> rowGroups = new ArrayList<>();
        private long pos, rows;

        ParquetWriter(java.io.OutputStream out, List<String> names, List<Col> cols, int codec) throws java.io.IOException {
            this.out = out; this.names = names; this.cols = cols; this.codec = codec;
            write("PAR1".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        }

        /** The Parquet column for each Snowflake result column. */
        static List<Col> columnsOf(List<?> rowType) {
            List<Col> out = new ArrayList<>();
            for (Object o : rowType) {
                Map<?, ?> c = (Map<?, ?>) o;
                String type = String.valueOf(c.get("type")).toLowerCase(Locale.ROOT);
                int scale = c.get("scale") instanceof Number n ? n.intValue() : 0, precision = c.get("precision") instanceof Number pn ? pn.intValue() : 38;
                out.add(switch (type) {
                    case "boolean" -> new Col(Col.BOOLEAN, 0, 0, false);
                    case "real" -> new Col(Col.DOUBLE, 0, 0, false);
                    case "fixed" -> precision <= 18 ? new Col(Col.INT64, scale, Math.max(1, precision), false) : new Col(Col.BYTE_ARRAY, 0, 0, false);
                    case "date" -> new Col(Col.INT32, 0, 0, true);
                    default -> new Col(Col.BYTE_ARRAY, 0, 0, false);
                });
            }
            return out;
        }

        private void write(byte[] b) throws java.io.IOException { out.write(b); pos += b.length; }

        void add(List<Object> row) throws java.io.IOException {
            buffer.add(row);
            if (buffer.size() >= ROWS_PER_GROUP) flushGroup();
        }

        private void flushGroup() throws java.io.IOException {
            if (buffer.isEmpty()) return;
            int n = buffer.size();
            Thrift rg = new Thrift();
            rg.listBegin(1, Thrift.STRUCT, cols.size());
            long total = 0;
            for (int c = 0; c < cols.size(); c++) {
                Col col = cols.get(c);
                byte[] page = page(c, col, n);
                byte[] packed = compress(page);
                Thrift h = new Thrift();
                h.i32(1, 0);                       // DATA_PAGE
                h.i32(2, page.length);
                h.i32(3, packed.length);
                h.beginStruct(5);                  // data_page_header
                h.i32(1, n); h.i32(2, 0); h.i32(3, 3); h.i32(4, 3);   // values, PLAIN, RLE levels
                h.endStruct();
                byte[] header = h.end();
                long offset = pos;
                write(header);
                write(packed);
                total += header.length + page.length;
                rg.beginElement();
                rg.i64(2, offset);                 // file_offset
                rg.beginStruct(3);                 // meta_data
                rg.i32(1, col.type());
                rg.listBegin(2, Thrift.I32, 2); rg.listI32(0); rg.listI32(3);
                rg.listBegin(3, Thrift.BINARY, 1); rg.listString(names.get(c));
                rg.i32(4, codec);
                rg.i64(5, n);
                rg.i64(6, header.length + page.length);
                rg.i64(7, header.length + packed.length);
                rg.i64(9, offset);
                rg.endStruct();
                rg.endStruct();
            }
            rg.i64(2, total);
            rg.i64(3, n);
            rowGroups.add(rg.end());
            rows += n;
            buffer.clear();
        }

        /** Definition levels (bit-packed, 1 = a value) then the plain-encoded values of one column. */
        private byte[] page(int c, Col col, int n) {
            java.io.ByteArrayOutputStream values = new java.io.ByteArrayOutputStream();
            byte[] defs = new byte[(n + 7) / 8];
            int bits = 0, bitCount = 0;
            java.io.ByteArrayOutputStream boolBytes = new java.io.ByteArrayOutputStream();
            for (int r = 0; r < n; r++) {
                List<Object> row = buffer.get(r);
                Object v = c < row.size() ? row.get(c) : null;
                Object w = v == null ? null : value(col, v);
                if (w == null) continue;
                defs[r / 8] |= (byte) (1 << (r % 8));
                switch (col.type()) {
                    case Col.BOOLEAN -> { if ((Boolean) w) bits |= 1 << bitCount; if (++bitCount == 8) { boolBytes.write(bits); bits = 0; bitCount = 0; } }
                    case Col.INT32 -> le(values, (Integer) w, 4);
                    case Col.INT64 -> le(values, (Long) w, 8);
                    case Col.DOUBLE -> le(values, Double.doubleToLongBits((Double) w), 8);
                    default -> { byte[] b = ((String) w).getBytes(java.nio.charset.StandardCharsets.UTF_8); le(values, b.length, 4); values.writeBytes(b); }
                }
            }
            if (bitCount > 0) boolBytes.write(bits);
            java.io.ByteArrayOutputStream levels = new java.io.ByteArrayOutputStream();
            varint(levels, ((long) defs.length << 1) | 1);   // one bit-packed run of groups of 8
            levels.writeBytes(defs);
            java.io.ByteArrayOutputStream page = new java.io.ByteArrayOutputStream();
            le(page, levels.size(), 4);
            page.writeBytes(levels.toByteArray());
            page.writeBytes(col.type() == Col.BOOLEAN ? boolBytes.toByteArray() : values.toByteArray());
            return page.toByteArray();
        }

        /** A cell as the column's Java value; text when a number or date does not fit the column. */
        private static Object value(Col col, Object v) {
            try {
                return switch (col.type()) {
                    case Col.BOOLEAN -> v instanceof Boolean b ? b : Boolean.parseBoolean(Values.text(v));
                    case Col.INT32 -> (int) LocalDate.parse(Values.text(v)).toEpochDay();
                    case Col.INT64 -> new BigDecimal(Values.text(v)).setScale(col.scale(), java.math.RoundingMode.UNNECESSARY).unscaledValue().longValueExact();
                    case Col.DOUBLE -> new BigDecimal(Values.text(v)).doubleValue();
                    default -> v instanceof BigDecimal d ? d.toPlainString() : Values.text(v);
                };
            } catch (RuntimeException e) {
                throw new ActionException(P + ".PARQUET_VALUE", "The value '" + Values.text(v) + "' does not fit a parquet " + col + " column", "Cast the column to text in the SQL, or export as csv.");
            }
        }

        private byte[] compress(byte[] page) throws java.io.IOException {
            if (codec == SNAPPY) return Snappy.compress(page);
            if (codec == GZIP) {
                java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
                try (java.util.zip.GZIPOutputStream g = new java.util.zip.GZIPOutputStream(b)) { g.write(page); }
                return b.toByteArray();
            }
            return page;
        }

        @Override public void close() throws java.io.IOException {
            try {
                flushGroup();
                Thrift f = new Thrift();
                f.i32(1, 1);                                   // version
                f.listBegin(2, Thrift.STRUCT, cols.size() + 1);
                f.beginElement(); f.string(4, "schema"); f.i32(5, cols.size()); f.endStruct();
                for (int c = 0; c < cols.size(); c++) {
                    Col col = cols.get(c);
                    f.beginElement();
                    f.i32(1, col.type());
                    f.i32(3, 1);                               // OPTIONAL
                    f.string(4, names.get(c));
                    if (col.type() == Col.BYTE_ARRAY) f.i32(6, 0);                     // UTF8
                    else if (col.date()) f.i32(6, 6);                                  // DATE
                    else if (col.type() == Col.INT64 && col.scale() > 0) { f.i32(6, 5); f.i32(7, col.scale()); f.i32(8, col.precision()); }   // DECIMAL
                    f.endStruct();
                }
                f.i64(3, rows);
                f.listBegin(4, Thrift.STRUCT, rowGroups.size());
                for (byte[] g : rowGroups) f.raw(g);
                f.string(6, "One Office Automation");
                byte[] footer = f.end();
                write(footer);
                java.io.ByteArrayOutputStream len = new java.io.ByteArrayOutputStream();
                le(len, footer.length, 4);
                write(len.toByteArray());
                write("PAR1".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            } finally { out.close(); }
        }

        static void le(java.io.ByteArrayOutputStream b, long v, int bytes) { for (int i = 0; i < bytes; i++) b.write((int) (v >>> (8 * i)) & 0xFF); }
        static void varint(java.io.ByteArrayOutputStream b, long v) { while ((v & ~0x7FL) != 0) { b.write((int) ((v & 0x7F) | 0x80)); v >>>= 7; } b.write((int) v); }

        /** Thrift compact protocol, just what the Parquet metadata needs. */
        static final class Thrift {
            static final int I32 = 5, I64 = 6, BINARY = 8, LIST = 9, STRUCT = 12;
            private final java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            private final Deque<Integer> stack = new ArrayDeque<>();
            private int last;
            private void field(int id, int type) {
                int d = id - last;
                if (d > 0 && d <= 15) b.write((d << 4) | type);
                else { b.write(type); varint(b, zig(id)); }
                last = id;
            }
            private static long zig(long v) { return (v << 1) ^ (v >> 63); }
            void i32(int id, int v) { field(id, I32); varint(b, zig(v)); }
            void i64(int id, long v) { field(id, I64); varint(b, zig(v)); }
            void string(int id, String v) { field(id, BINARY); byte[] x = v.getBytes(java.nio.charset.StandardCharsets.UTF_8); varint(b, x.length); b.writeBytes(x); }
            void beginStruct(int id) { field(id, STRUCT); stack.push(last); last = 0; }
            void beginElement() { stack.push(last); last = 0; }
            void endStruct() { b.write(0); last = stack.pop(); }
            void listBegin(int id, int elemType, int size) {
                field(id, LIST);
                if (size < 15) b.write((size << 4) | elemType); else { b.write(0xF0 | elemType); varint(b, size); }
            }
            void listI32(int v) { varint(b, zig(v)); }
            void listString(String v) { byte[] x = v.getBytes(java.nio.charset.StandardCharsets.UTF_8); varint(b, x.length); b.writeBytes(x); }
            /** A struct list element written by another Thrift (it ends with its own stop byte). */
            void raw(byte[] structBody) { b.writeBytes(structBody); }
            /** The struct's bytes, closed with the stop byte. */
            byte[] end() { b.write(0); return b.toByteArray(); }
        }
    }

    /** Snappy block compression (G6): greedy matches through a hash table, in 64 KB blocks, as the format allows. */
    static final class Snappy {
        private Snappy() {}
        static byte[] compress(byte[] in) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(in.length / 2 + 16);
            ParquetWriter.varint(out, in.length);
            int[] table = new int[1 << 14];
            for (int block = 0; block < in.length; block += 65536) {
                int end = Math.min(in.length, block + 65536);
                Arrays.fill(table, -1);
                int i = block, lit = block;
                while (i + 4 <= end) {
                    int h = (read32(in, i) * 0x1e35a7bd) >>> 18;
                    int cand = table[h];
                    table[h] = i;
                    if (cand >= block && read32(in, cand) == read32(in, i)) {
                        literal(out, in, lit, i - lit);
                        int len = 4;
                        while (i + len < end && in[cand + len] == in[i + len]) len++;
                        copy(out, i - cand, len);
                        i += len;
                        lit = i;
                    } else i++;
                }
                literal(out, in, lit, end - lit);
            }
            return out.toByteArray();
        }
        private static int read32(byte[] b, int i) { return (b[i] & 0xFF) | (b[i + 1] & 0xFF) << 8 | (b[i + 2] & 0xFF) << 16 | (b[i + 3] & 0xFF) << 24; }
        private static void literal(java.io.ByteArrayOutputStream out, byte[] in, int from, int len) {
            if (len == 0) return;
            int n = len - 1;
            if (n < 60) out.write(n << 2);
            else if (n < 256) { out.write(60 << 2); out.write(n); }
            else { out.write(61 << 2); out.write(n & 0xFF); out.write(n >>> 8); }   // a 64 KB block keeps n below 65536
            out.write(in, from, len);
        }
        private static void copy(java.io.ByteArrayOutputStream out, int offset, int len) {
            while (len > 0) {
                int part = Math.min(len, 64);
                if (len - part > 0 && len - part < 4) part = len - 4;   // keep the last piece at least 4 long
                out.write(((part - 1) << 2) | 2);
                out.write(offset & 0xFF);
                out.write(offset >>> 8);
                len -= part;
            }
        }
    }

    @Action(id = "connect", name = "Connect", category = "Connection",
            description = "Keep the address and sign-in under a session for the steps after it, so they need not repeat them (G6).",
            panel = "account_url, token, token_type | session, share")
    public Map<String, Object> connectSnowflake(ActionContext ctx,
            @Input(name = "account_url", type = OoaType.STRING, required = false, description = "The account url the later steps use (G6).") String accountUrl,
            @Input(name = "token", type = OoaType.CREDENTIAL, required = false, description = "The token the later steps use (G6).") Object token,
            @Input(name = "token_type", type = OoaType.STRING, required = false, description = "The token type the later steps use (G6).") String tokenType,
            @Input(name = "session", type = OoaType.STRING, required = false, description = "The session's name; the toolkit's default session when empty.") String session,
            @Input(name = "share", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Agents this one runs use the same session.") boolean share) {
        Map<String, Object> v = new LinkedHashMap<>();
        Object[] kv = {"account_url", accountUrl, "token", token, "token_type", tokenType};
        for (int i = 0; i < kv.length; i += 2) v.put((String) kv[i], kv[i + 1]);
        ApiSession.open(ctx, session, "snowflake", "snowflake", v, share);
        return Map.of();
    }

    @Action(id = "disconnect", name = "Disconnect", category = "Connection",
            description = "Forget the open Snowflake session; later steps then need their own address and sign-in (G6).", panel = "session")
    @Output(name = "closed", type = OoaType.BOOLEAN, description = "True when a session was open.")
    public Map<String, Object> endSessionSnowflake(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, description = "The session; the toolkit's default session when empty.") String session) {
        return Map.of("closed", ApiSession.close(ctx, session, "snowflake"));
    }
}
