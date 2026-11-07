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
    private static final String D_TOKEN = "Access token from the vault: an OAuth token, a key-pair JWT or a programmatic access token.";
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
            @Input(name = "account_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String account,
            @Input(name = "sql", type = OoaType.STRING, example = "SELECT * FROM ORDERS WHERE REGION = ? AND AMOUNT > ?", description = "SQL to run. Put ? where each binding value goes.") String sql,
            @Input(name = "bindings", type = OoaType.LIST, required = false, description = "Values for the ? placeholders, in order, e.g. [\"EMEA\", 1000].") List<Object> binds,
            @Input(name = "warehouse", type = OoaType.STRING, required = false, description = D_WH) String warehouse,
            @Input(name = "database", type = OoaType.STRING, required = false, description = "Database to use for names that are not fully written out.") String database,
            @Input(name = "schema", type = OoaType.STRING, required = false, description = "Schema to use for names that are not fully written out.") String schema,
            @Input(name = "role", type = OoaType.STRING, required = false, description = D_ROLE) String role,
            @Input(name = "wait_seconds", type = OoaType.NUMBER, required = false, defaultValue = "600", description = "How long to wait for the statement to finish before returning with status running.") int wait,
            @Input(name = "max_rows", type = OoaType.NUMBER, required = false, defaultValue = "100000", description = "Most rows to bring back into the table.") int max,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "token_type", type = OoaType.STRING, required = false, defaultValue = "\"OAUTH\"", enumValues = {"OAUTH", "KEYPAIR_JWT", "PROGRAMMATIC_ACCESS_TOKEN"}, description = D_TOKEN_TYPE) String tokenType) {
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
            @Input(name = "account_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String account,
            @Input(name = "statement_handle", type = OoaType.STRING, description = "Handle from Run SQL statement.") String h,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "token_type", type = OoaType.STRING, required = false, defaultValue = "\"OAUTH\"", enumValues = {"OAUTH", "KEYPAIR_JWT", "PROGRAMMATIC_ACCESS_TOKEN"}, description = D_TOKEN_TYPE) String tokenType) {
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
            @Input(name = "account_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String account,
            @Input(name = "statement_handle", type = OoaType.STRING, description = "Handle from Run SQL statement.") String h,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "token_type", type = OoaType.STRING, required = false, defaultValue = "\"OAUTH\"", enumValues = {"OAUTH", "KEYPAIR_JWT", "PROGRAMMATIC_ACCESS_TOKEN"}, description = D_TOKEN_TYPE) String tokenType) {
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
            @Input(name = "account_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String account,
            @Input(name = "statement_handle", type = OoaType.STRING, description = "Handle from Run SQL statement.") String h,
            @Input(name = "max_rows", type = OoaType.NUMBER, required = false, defaultValue = "100000", description = "Most rows to bring back.") int max,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "token_type", type = OoaType.STRING, required = false, defaultValue = "\"OAUTH\"", enumValues = {"OAUTH", "KEYPAIR_JWT", "PROGRAMMATIC_ACCESS_TOKEN"}, description = D_TOKEN_TYPE) String tokenType) {
        Result r = poll(ctx, account, token, tokenType, h, null);
        if (r.running()) throw new ActionException(P + ".STILL_RUNNING", "The statement is still running", "Wait, then try again, or check it with Get statement status.", true);
        Map<String, Object> t = table(ctx, account, token, tokenType, r, max);
        @SuppressWarnings("unchecked") Map<String, Object> tbl = (Map<String, Object>) t.get("table");
        return Out.of("table", tbl, "row_count", BigDecimal.valueOf(DataTablePackage.rows(tbl).size()), "total_rows", t.get("total_rows"));
    }

    @Action(id = "export_results", name = "Export results to file", category = "SQL", timeoutSeconds = 3600, capabilities = "network,filesystem",
            description = "Run one SQL statement and write all of its rows to a file (CSV, tab-separated or JSON lines), optionally gzip-compressed, without holding them in memory.",
            panel = "account_url, token, sql, [Fill in ? placeholders: bindings], file, format, header, compression | overwrite, warehouse, database, schema, role, wait_seconds, token_type")
    @Output(name = "file", type = OoaType.FILE, description = "The file written.")
    @Output(name = "row_count", type = OoaType.NUMBER, description = "Rows written (not counting the header).")
    @Output(name = "statement_handle", type = OoaType.STRING, description = "Id of the statement.")
    public Map<String, Object> exportResults(ActionContext ctx,
            @Input(name = "account_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String account,
            @Input(name = "sql", type = OoaType.STRING, example = "SELECT * FROM ORDERS WHERE ORDER_DATE >= ?", description = "SQL to run. Put ? where each binding value goes.") String sql,
            @Input(name = "file", type = OoaType.FILE, example = "exports/orders.csv", description = "File to write; add .gz yourself when compressing.") String file,
            @Input(name = "bindings", type = OoaType.LIST, required = false, description = "Values for the ? placeholders, in order, e.g. [\"2026-10-01\"].") List<Object> binds,
            @Input(name = "format", type = OoaType.STRING, required = false, defaultValue = "\"csv\"", enumValues = {"csv", "tsv", "json_lines"},
                    description = "csv (comma-separated, quoted where needed), tsv (tab-separated) or json_lines (one JSON object per row).") String format,
            @Input(name = "header", type = OoaType.BOOLEAN, required = false, defaultValue = "true", visibleWhen = "format=csv,tsv", description = "Write the column names as the first line.") boolean header,
            @Input(name = "compression", type = OoaType.STRING, required = false, defaultValue = "\"none\"", enumValues = {"none", "gzip"}, description = "none, or gzip to compress the file.") String compression,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Replace a file that already exists.") boolean overwrite,
            @Input(name = "warehouse", type = OoaType.STRING, required = false, description = D_WH) String warehouse,
            @Input(name = "database", type = OoaType.STRING, required = false, description = "Database to use for names that are not fully written out.") String database,
            @Input(name = "schema", type = OoaType.STRING, required = false, description = "Schema to use for names that are not fully written out.") String schema,
            @Input(name = "role", type = OoaType.STRING, required = false, description = D_ROLE) String role,
            @Input(name = "wait_seconds", type = OoaType.NUMBER, required = false, defaultValue = "3000", description = "How long to wait for the statement to finish.") int wait,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "token_type", type = OoaType.STRING, required = false, defaultValue = "\"OAUTH\"", enumValues = {"OAUTH", "KEYPAIR_JWT", "PROGRAMMATIC_ACCESS_TOKEN"}, description = D_TOKEN_TYPE) String tokenType) {
        String fmt = format == null || format.isBlank() ? "csv" : format.strip().toLowerCase(Locale.ROOT);
        if (!List.of("csv", "tsv", "json_lines").contains(fmt)) throw new ActionException(P + ".BAD_FORMAT", "Unknown format '" + format + "'", "Use csv, tsv or json_lines.");
        boolean gzip = "gzip".equalsIgnoreCase(compression == null ? "" : compression.strip());
        java.nio.file.Path out = ctx.resolvePath(file);
        if (java.nio.file.Files.exists(out) && !overwrite) throw new ActionException(P + ".FILE_EXISTS", "A file already exists at " + out, "Set overwrite to true, or choose another file.");
        Result r = waitFor(ctx, account, token, tokenType, submit(ctx, account, token, tokenType, sql, binds, warehouse, database, schema, role, Math.max(wait, 0) + 3600), wait);
        if (r.running()) throw new ActionException(P + ".STILL_RUNNING", "The statement did not finish within " + wait + " seconds", "Raise wait_seconds, or use Get statement status with the handle " + r.handle() + ".", true);
        List<String> cols = columns(r);
        long[] count = {0};
        java.nio.file.Path part = out.resolveSibling(out.getFileName() + ".part");
        try {
            if (out.getParent() != null) java.nio.file.Files.createDirectories(out.getParent());
            java.io.OutputStream raw = java.nio.file.Files.newOutputStream(part);
            try (java.io.Writer w = new java.io.BufferedWriter(new java.io.OutputStreamWriter(gzip ? new java.util.zip.GZIPOutputStream(raw) : raw, java.nio.charset.StandardCharsets.UTF_8))) {
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
            @Input(name = "account_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String account,
            @Input(name = "role", type = OoaType.STRING, required = false, description = D_ROLE) String role,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "token_type", type = OoaType.STRING, required = false, defaultValue = "\"OAUTH\"", enumValues = {"OAUTH", "KEYPAIR_JWT", "PROGRAMMATIC_ACCESS_TOKEN"}, description = D_TOKEN_TYPE) String tokenType) {
        return show(ctx, account, token, tokenType, role, "SHOW DATABASES");
    }

    @Action(id = "list_schemas", name = "List schemas", category = "Catalog", idempotent = true, timeoutSeconds = 600, capabilities = "network",
            description = "Return the schemas in a database.",
            panel = "account_url, token, database | role, token_type")
    @Output(name = "table", type = OoaType.TABLE, description = "One row per schema.")
    @Output(name = "names", type = OoaType.LIST, description = "Schema names only.")
    public Map<String, Object> listSchemas(ActionContext ctx,
            @Input(name = "account_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String account,
            @Input(name = "database", type = OoaType.STRING, description = "Database to look in.") String database,
            @Input(name = "role", type = OoaType.STRING, required = false, description = D_ROLE) String role,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "token_type", type = OoaType.STRING, required = false, defaultValue = "\"OAUTH\"", enumValues = {"OAUTH", "KEYPAIR_JWT", "PROGRAMMATIC_ACCESS_TOKEN"}, description = D_TOKEN_TYPE) String tokenType) {
        return show(ctx, account, token, tokenType, role, "SHOW SCHEMAS IN DATABASE " + ident(database));
    }

    @Action(id = "list_tables", name = "List tables", category = "Catalog", idempotent = true, timeoutSeconds = 600, capabilities = "network",
            description = "Return the tables in a schema.",
            panel = "account_url, token, database, schema | role, token_type")
    @Output(name = "table", type = OoaType.TABLE, description = "One row per table with name, row count, size and more.")
    @Output(name = "names", type = OoaType.LIST, description = "Table names only.")
    public Map<String, Object> listTables(ActionContext ctx,
            @Input(name = "account_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String account,
            @Input(name = "database", type = OoaType.STRING, description = "Database to look in.") String database,
            @Input(name = "schema", type = OoaType.STRING, description = "Schema to look in, e.g. PUBLIC.") String schema,
            @Input(name = "role", type = OoaType.STRING, required = false, description = D_ROLE) String role,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "token_type", type = OoaType.STRING, required = false, defaultValue = "\"OAUTH\"", enumValues = {"OAUTH", "KEYPAIR_JWT", "PROGRAMMATIC_ACCESS_TOKEN"}, description = D_TOKEN_TYPE) String tokenType) {
        return show(ctx, account, token, tokenType, role, "SHOW TABLES IN SCHEMA " + ident(database) + "." + ident(schema));
    }

    @Action(id = "list_columns", name = "List columns", category = "Catalog", idempotent = true, timeoutSeconds = 600, capabilities = "network",
            description = "Return the columns of a table with their data types.",
            panel = "account_url, token, table_name | role, token_type")
    @Output(name = "table", type = OoaType.TABLE, description = "One row per column with name, type, nullability and default.")
    @Output(name = "names", type = OoaType.LIST, description = "Column names only.")
    public Map<String, Object> listColumns(ActionContext ctx,
            @Input(name = "account_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String account,
            @Input(name = "table_name", type = OoaType.STRING, example = "SALES.PUBLIC.ORDERS", description = "Table, written as DATABASE.SCHEMA.TABLE.") String table,
            @Input(name = "role", type = OoaType.STRING, required = false, description = D_ROLE) String role,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "token_type", type = OoaType.STRING, required = false, defaultValue = "\"OAUTH\"", enumValues = {"OAUTH", "KEYPAIR_JWT", "PROGRAMMATIC_ACCESS_TOKEN"}, description = D_TOKEN_TYPE) String tokenType) {
        return show(ctx, account, token, tokenType, role, "DESCRIBE TABLE " + qualified(table));
    }

    @Action(id = "list_warehouses", name = "List warehouses", category = "Catalog", idempotent = true, timeoutSeconds = 600, capabilities = "network",
            description = "Return the warehouses the role can use, with their size and state.",
            panel = "account_url, token | role, token_type")
    @Output(name = "table", type = OoaType.TABLE, description = "One row per warehouse.")
    @Output(name = "names", type = OoaType.LIST, description = "Warehouse names only.")
    public Map<String, Object> listWarehouses(ActionContext ctx,
            @Input(name = "account_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String account,
            @Input(name = "role", type = OoaType.STRING, required = false, description = D_ROLE) String role,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "token_type", type = OoaType.STRING, required = false, defaultValue = "\"OAUTH\"", enumValues = {"OAUTH", "KEYPAIR_JWT", "PROGRAMMATIC_ACCESS_TOKEN"}, description = D_TOKEN_TYPE) String tokenType) {
        return show(ctx, account, token, tokenType, role, "SHOW WAREHOUSES");
    }

    // ----- loading -----

    @Action(id = "insert_rows", name = "Insert rows from data table", category = "Loading", timeoutSeconds = 3600, capabilities = "network",
            description = "Add every row of a data table to a Snowflake table. Columns are matched by name.",
            panel = "account_url, token, table_name, data | batch_size, warehouse, database, schema, role, token_type")
    @Output(name = "rows_inserted", type = OoaType.NUMBER, description = "Number of rows added.")
    public Map<String, Object> insert(ActionContext ctx,
            @Input(name = "account_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String account,
            @Input(name = "table_name", type = OoaType.STRING, example = "SALES.PUBLIC.ORDERS", description = "Target table, written as DATABASE.SCHEMA.TABLE (or just TABLE with database and schema set).") String target,
            @Input(name = "data", type = OoaType.TABLE, description = "Rows to add. Its column names must match columns of the target table.") Map<String, Object> data,
            @Input(name = "batch_size", type = OoaType.NUMBER, required = false, defaultValue = "500", description = "Rows sent per request.") int batch,
            @Input(name = "warehouse", type = OoaType.STRING, required = false, description = D_WH) String warehouse,
            @Input(name = "database", type = OoaType.STRING, required = false, description = "Database of the table when table_name has no database.") String database,
            @Input(name = "schema", type = OoaType.STRING, required = false, description = "Schema of the table when table_name has no schema.") String schema,
            @Input(name = "role", type = OoaType.STRING, required = false, description = D_ROLE) String role,
            @Input(name = "token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token,
            @Input(name = "token_type", type = OoaType.STRING, required = false, defaultValue = "\"OAUTH\"", enumValues = {"OAUTH", "KEYPAIR_JWT", "PROGRAMMATIC_ACCESS_TOKEN"}, description = D_TOKEN_TYPE) String tokenType) {
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
}
