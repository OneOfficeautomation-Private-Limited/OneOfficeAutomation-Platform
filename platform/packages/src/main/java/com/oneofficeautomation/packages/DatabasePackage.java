package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;

import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

/**
 * JDBC databases (SQL Server, Oracle, PostgreSQL, MySQL, DB2, ...). Values are always passed as
 * parameters (? placeholders), never concatenated into SQL, so bots are protected from SQL injection.
 * Drivers are added to the Agent's classpath per tenant.
 */
@OoaPackage(id = "ooa.database", name = "Database", version = "1.0.0", description = "Connect to databases and run parameterised queries, updates and transactions.")
public final class DatabasePackage {

    static final class DbSession implements AutoCloseable {
        final Connection conn;
        DbSession(Connection c) { conn = c; }
        @Override public void close() throws SQLException { if (!conn.isClosed()) { if (!conn.getAutoCommit()) conn.rollback(); conn.close(); } }
    }

    private static ActionException sql(SQLException e) {
        boolean transientErr = e instanceof SQLTransientException || e instanceof SQLRecoverableException;
        return new ActionException("DB.SQL_ERROR", e.getMessage() + (e.getSQLState() != null ? " (SQLState " + e.getSQLState() + ")" : ""),
                "Check the SQL and that parameters match the ? placeholders.", transientErr);
    }

    @Action(id = "connect", name = "Connect", category = "Database", capabilities = "network", description = "Open a database connection as a named session.")
    public Map<String, Object> connect(ActionContext ctx,
            @Input(name = "jdbc_url", type = OoaType.STRING, pattern = "^jdbc:", description = "JDBC URL, e.g. jdbc:sqlserver://host;databaseName=erp or jdbc:postgresql://host/db.") String url,
            @Input(name = "user", type = OoaType.STRING, required = false, description = "User name.") String user,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, description = "Password from the vault.") Object password,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"db\"", description = "Session name for later steps.") String session) {
        try {
            Properties p = new Properties();
            if (user != null) p.setProperty("user", user);
            if (password instanceof SecureString s) p.setProperty("password", s.reveal());
            ctx.sessions().put(session, new DbSession(DriverManager.getConnection(url, p)));
            ctx.info("Connected session '" + session + "' to " + url.replaceAll("(?i)password=[^;&]+", "password=****"));
        } catch (SQLException e) {
            if ("08001".equals(e.getSQLState()) || e.getMessage().contains("No suitable driver"))
                throw new ActionException("DB.NO_DRIVER", "No JDBC driver found for " + url.split(":")[1], "Ask an administrator to install the database driver on the runner.");
            throw new ActionException("DB.CONNECT_FAILED", "Cannot connect: " + e.getMessage(), "Check the URL, network access and credentials.", true);
        }
        return Map.of();
    }

    private static PreparedStatement prepare(Connection c, String sql, List<Object> params) throws SQLException {
        PreparedStatement ps = c.prepareStatement(sql);
        if (params != null) for (int i = 0; i < params.size(); i++) {
            Object v = params.get(i);
            if (v instanceof LocalDateTime t) ps.setTimestamp(i + 1, Timestamp.valueOf(t));
            else if (v instanceof SecureString s) ps.setString(i + 1, s.reveal());
            else ps.setObject(i + 1, v);
        }
        return ps;
    }

    @Action(id = "query", name = "Read from (query)", category = "Database", capabilities = "network",
            description = "Run a SELECT with ? parameters and return the rows as a table.")
    @Output(name = "table", type = OoaType.TABLE, description = "Result rows.")
    @Output(name = "row_count", type = OoaType.NUMBER, description = "Number of rows.")
    public Map<String, Object> query(ActionContext ctx,
            @Input(name = "sql", type = OoaType.STRING, description = "SELECT statement with ? placeholders.") String sql,
            @Input(name = "parameters", type = OoaType.LIST, required = false, description = "Values for the ? placeholders, in order.") List<Object> params,
            @Input(name = "max_rows", type = OoaType.NUMBER, required = false, defaultValue = "100000", description = "Safety limit on rows returned.") int maxRows,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"db\"", description = "Session name.") String session) {
        Connection c = ctx.session(session, DbSession.class).conn;
        try (PreparedStatement ps = prepare(c, sql, params)) {
            ps.setMaxRows(maxRows);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                List<String> cols = new ArrayList<>();
                for (int i = 1; i <= md.getColumnCount(); i++) cols.add(md.getColumnLabel(i));
                List<Object> rows = new ArrayList<>();
                while (rs.next()) {
                    List<Object> r = new ArrayList<>();
                    for (int i = 1; i <= cols.size(); i++) r.add(value(rs.getObject(i)));
                    rows.add(r);
                }
                return Out.of("table", Values.table(cols, rows), "row_count", BigDecimal.valueOf(rows.size()));
            }
        } catch (SQLException e) { throw sql(e); }
    }

    static Object value(Object v) throws SQLException {
        if (v == null || v instanceof String || v instanceof Boolean || v instanceof BigDecimal) return v;
        if (v instanceof Number n) return new BigDecimal(n.toString());
        if (v instanceof Timestamp t) return t.toLocalDateTime();
        if (v instanceof java.sql.Date d) return d.toLocalDate().atStartOfDay();
        if (v instanceof Clob c) return c.getSubString(1, (int) c.length());
        return v.toString();
    }

    @Action(id = "execute", name = "Insert/update/delete", category = "Database", capabilities = "network",
            description = "Run an INSERT, UPDATE, DELETE or DDL statement with ? parameters.")
    @Output(name = "rows_affected", type = OoaType.NUMBER, description = "Rows changed.")
    public Map<String, Object> execute(ActionContext ctx,
            @Input(name = "sql", type = OoaType.STRING, description = "Statement with ? placeholders.") String sql,
            @Input(name = "parameters", type = OoaType.LIST, required = false, description = "Values for the ? placeholders, in order.") List<Object> params,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"db\"", description = "Session name.") String session) {
        Connection c = ctx.session(session, DbSession.class).conn;
        try (PreparedStatement ps = prepare(c, sql, params)) {
            return Out.of("rows_affected", BigDecimal.valueOf(ps.executeUpdate()));
        } catch (SQLException e) { throw sql(e); }
    }

    @Action(id = "begin_transaction", name = "Begin transaction", category = "Transactions", description = "Start a transaction; changes are kept only after Commit.")
    public Map<String, Object> begin(ActionContext ctx, @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"db\"", description = "Session name.") String session) {
        try { ctx.session(session, DbSession.class).conn.setAutoCommit(false); } catch (SQLException e) { throw sql(e); }
        return Map.of();
    }

    @Action(id = "commit", name = "Commit", category = "Transactions", description = "Save the changes made since Begin transaction.")
    public Map<String, Object> commit(ActionContext ctx, @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"db\"", description = "Session name.") String session) {
        try { Connection c = ctx.session(session, DbSession.class).conn; c.commit(); c.setAutoCommit(true); } catch (SQLException e) { throw sql(e); }
        return Map.of();
    }

    @Action(id = "rollback", name = "Roll back", category = "Transactions", description = "Undo the changes made since Begin transaction.")
    public Map<String, Object> rollback(ActionContext ctx, @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"db\"", description = "Session name.") String session) {
        try { Connection c = ctx.session(session, DbSession.class).conn; c.rollback(); c.setAutoCommit(true); } catch (SQLException e) { throw sql(e); }
        return Map.of();
    }

    // ---------- Bulk, procedures, export and schema ----------

    private static final String IDENT = "[A-Za-z_][A-Za-z0-9_$#]*";

    /** Table, column or procedure name: plain identifiers, optionally qualified (schema.table). Quoted names are not accepted. */
    static String identifier(String name, String what) {
        if (name == null || !name.strip().matches(IDENT + "(\\." + IDENT + "){0,2}"))
            throw new ActionException("DB.BAD_NAME", "Invalid " + what + " name '" + name + "'", "Use letters, digits and underscores, optionally schema.name.");
        return name.strip();
    }

    private static void bind(PreparedStatement ps, int i, Object v) throws SQLException {
        if (v instanceof LocalDateTime t) ps.setTimestamp(i, Timestamp.valueOf(t));
        else if (v instanceof SecureString s) ps.setString(i, s.reveal());
        else if (v instanceof Map || v instanceof List) ps.setString(i, JsonLite.write(Values.toJsonable(v)));
        else ps.setObject(i, v);
    }

    static Map<String, Object> table(ResultSet rs, int maxRows) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        List<String> cols = new ArrayList<>();
        for (int i = 1; i <= md.getColumnCount(); i++) cols.add(md.getColumnLabel(i));
        List<Object> rows = new ArrayList<>();
        while (rows.size() < maxRows && rs.next()) {
            List<Object> r = new ArrayList<>();
            for (int i = 1; i <= cols.size(); i++) r.add(value(rs.getObject(i)));
            rows.add(r);
        }
        return Values.table(cols, rows);
    }

    @Action(id = "bulk_insert", name = "Bulk insert", category = "Database", timeoutSeconds = 3600, capabilities = "network",
            description = "Insert every row of a Data Table into a database table in batches, all or nothing.")
    @Output(name = "rows_inserted", type = OoaType.NUMBER, description = "Rows inserted.")
    public Map<String, Object> bulkInsert(ActionContext ctx,
            @Input(name = "table", type = OoaType.STRING, example = "dbo.invoices", description = "Database table to insert into.") String tableName,
            @Input(name = "data", type = OoaType.TABLE, description = "Data Table whose rows are inserted.") Map<String, Object> data,
            @Input(name = "columns", type = OoaType.LIST, required = false, description = "Database column for each Data Table column, in order; the Data Table's column names when empty.") List<Object> columns,
            @Input(name = "batch_size", type = OoaType.NUMBER, required = false, defaultValue = "500", description = "Rows sent to the database per batch.") int batchSize,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"db\"", description = "Session name.") String session) {
        String t = identifier(tableName, "table");
        List<String> srcCols = DataTablePackage.cols(data);
        List<String> cols = new ArrayList<>();
        for (Object c : columns == null || columns.isEmpty() ? new ArrayList<Object>(srcCols) : columns) cols.add(identifier(Values.text(c), "column"));
        if (cols.isEmpty()) throw new ActionException("DB.NO_COLUMNS", "The Data Table has no columns", "Give a Data Table with at least one column.");
        if (cols.size() != srcCols.size())
            throw new ActionException("DB.COLUMN_MISMATCH", "columns lists " + cols.size() + " names but the Data Table has " + srcCols.size() + " columns", "Give one database column per Data Table column.");
        String sql = "INSERT INTO " + t + " (" + String.join(", ", cols) + ") VALUES (" + String.join(", ", Collections.nCopies(cols.size(), "?")) + ")";
        Connection c = ctx.session(session, DbSession.class).conn;
        int size = Math.max(1, Math.min(10_000, batchSize)), done = 0;
        try {
            boolean auto = c.getAutoCommit();
            if (auto) c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                int pending = 0;
                for (List<Object> row : DataTablePackage.rows(data)) {
                    for (int i = 0; i < cols.size(); i++) bind(ps, i + 1, i < row.size() ? row.get(i) : null);
                    ps.addBatch();
                    if (++pending == size) { ps.executeBatch(); done += pending; pending = 0; ctx.checkCancelled(); }
                }
                if (pending > 0) { ps.executeBatch(); done += pending; }
                if (auto) c.commit();
            } catch (SQLException | RuntimeException e) {
                if (auto) c.rollback();
                throw e;
            } finally {
                if (auto) c.setAutoCommit(true);
            }
        } catch (SQLException e) { throw sql(e); }
        return Map.of("rows_inserted", BigDecimal.valueOf(done));
    }

    @Action(id = "call_procedure", name = "Run stored procedure", category = "Database", timeoutSeconds = 600, capabilities = "network",
            description = "Call a stored procedure or function with input parameters and return the rows it produces.")
    @Output(name = "table", type = OoaType.TABLE, description = "First result set, or an empty table.")
    @Output(name = "row_count", type = OoaType.NUMBER, description = "Rows in the result table.")
    @Output(name = "rows_affected", type = OoaType.NUMBER, description = "Rows changed, when the database reports it; otherwise -1.")
    public Map<String, Object> callProcedure(ActionContext ctx,
            @Input(name = "procedure", type = OoaType.STRING, example = "dbo.close_period", description = "Procedure name, optionally schema.name.") String procedure,
            @Input(name = "parameters", type = OoaType.LIST, required = false, description = "Input values, in the procedure's parameter order.") List<Object> params,
            @Input(name = "max_rows", type = OoaType.NUMBER, required = false, defaultValue = "100000", description = "Safety limit on rows returned.") int maxRows,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"db\"", description = "Session name.") String session) {
        String name = identifier(procedure, "procedure");
        int n = params == null ? 0 : params.size();
        String call = "{call " + name + "(" + String.join(", ", Collections.nCopies(n, "?")) + ")}";
        Connection c = ctx.session(session, DbSession.class).conn;
        try (CallableStatement cs = c.prepareCall(call)) {
            for (int i = 0; i < n; i++) bind(cs, i + 1, params.get(i));
            boolean hasRs = cs.execute();
            Map<String, Object> table = Values.table(List.of(), new ArrayList<>());
            long affected = -1;
            while (true) {
                if (hasRs) {
                    if (DataTablePackage.cols(table).isEmpty()) try (ResultSet rs = cs.getResultSet()) { table = table(rs, Math.max(1, maxRows)); }
                } else {
                    int u = cs.getUpdateCount();
                    if (u == -1) break;
                    affected = Math.max(affected, 0) + u;
                }
                hasRs = cs.getMoreResults();
            }
            return Out.of("table", table, "row_count", BigDecimal.valueOf(DataTablePackage.rows(table).size()), "rows_affected", BigDecimal.valueOf(affected));
        } catch (SQLException e) { throw sql(e); }
    }

    @Action(id = "export_to_file", name = "Export to CSV file", category = "Database", timeoutSeconds = 3600, capabilities = {"network", "filesystem"},
            description = "Run a SELECT with ? parameters and write all its rows to a CSV file without loading them into memory.")
    @Output(name = "path", type = OoaType.FILE, description = "Written file.")
    @Output(name = "row_count", type = OoaType.NUMBER, description = "Rows written.")
    public Map<String, Object> exportToFile(ActionContext ctx,
            @Input(name = "sql", type = OoaType.STRING, description = "SELECT statement with ? placeholders.") String sql,
            @Input(name = "path", type = OoaType.FILE, description = "CSV file to create.") String path,
            @Input(name = "parameters", type = OoaType.LIST, required = false, description = "Values for the ? placeholders, in order.") List<Object> params,
            @Input(name = "delimiter", type = OoaType.STRING, required = false, defaultValue = "\",\"", description = "Field separator, e.g. , or ; or tab.") String delimiter,
            @Input(name = "header", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Write the column names as the first line.") boolean header,
            @Input(name = "encoding", type = OoaType.STRING, required = false, defaultValue = "\"utf-8\"", description = "Text encoding of the file.") String encoding,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"db\"", description = "Session name.") String session) {
        char d = delimiter == null || delimiter.isEmpty() ? ',' : delimiter.equalsIgnoreCase("tab") ? '\t' : delimiter.charAt(0);
        java.nio.file.Path p = ctx.resolvePath(path);
        Connection c = ctx.session(session, DbSession.class).conn;
        long count = 0;
        try (PreparedStatement ps = prepare(c, sql, params)) {
            ps.setFetchSize(1000);
            try (ResultSet rs = ps.executeQuery()) {
                if (p.getParent() != null) java.nio.file.Files.createDirectories(p.getParent());
                try (java.io.BufferedWriter w = java.nio.file.Files.newBufferedWriter(p, java.nio.charset.Charset.forName(encoding))) {
                    ResultSetMetaData md = rs.getMetaData();
                    int n = md.getColumnCount();
                    if (header) {
                        for (int i = 1; i <= n; i++) { if (i > 1) w.write(d); w.write(CsvPackage.quote(md.getColumnLabel(i), d)); }
                        w.write("\r\n");
                    }
                    while (rs.next()) {
                        for (int i = 1; i <= n; i++) { if (i > 1) w.write(d); w.write(CsvPackage.quote(Values.text(value(rs.getObject(i))), d)); }
                        w.write("\r\n");
                        if (++count % 10_000 == 0) ctx.checkCancelled();
                    }
                }
            }
        } catch (SQLException e) { throw sql(e);
        } catch (java.io.IOException e) { throw new ActionException("DB.EXPORT_FAILED", "Cannot write " + p + ": " + e.getMessage(), "Check the folder exists and the file is not open.", true); }
        return Out.of("path", p.toString(), "row_count", BigDecimal.valueOf(count));
    }

    @Action(id = "list_tables", name = "List tables", category = "Schema", idempotent = true, capabilities = "network",
            description = "List the tables and views the connection can see, optionally in one schema.")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: schema, name, type.")
    public Map<String, Object> listTables(ActionContext ctx,
            @Input(name = "schema", type = OoaType.STRING, required = false, description = "Only this schema; all schemas when empty.") String schema,
            @Input(name = "include_views", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Also list views.") boolean views,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"db\"", description = "Session name.") String session) {
        Connection c = ctx.session(session, DbSession.class).conn;
        String[] types = views ? new String[]{"TABLE", "VIEW", "BASE TABLE"} : new String[]{"TABLE", "BASE TABLE"};
        List<Object> rows = new ArrayList<>();
        try (ResultSet rs = c.getMetaData().getTables(null, schema == null || schema.isBlank() ? null : schema, "%", types)) {
            while (rs.next()) rows.add(new ArrayList<>(Arrays.asList(rs.getString("TABLE_SCHEM"), rs.getString("TABLE_NAME"), rs.getString("TABLE_TYPE"))));
        } catch (SQLException e) { throw sql(e); }
        return Map.of("table", Values.table(List.of("schema", "name", "type"), rows));
    }

    @Action(id = "describe_table", name = "Describe table", category = "Schema", idempotent = true, capabilities = "network",
            description = "Return the columns of a table with their types, sizes and whether they accept empty values.")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: name, type, size, nullable, position.")
    public Map<String, Object> describeTable(ActionContext ctx,
            @Input(name = "table", type = OoaType.STRING, description = "Table name, optionally schema.table.") String tableName,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"db\"", description = "Session name.") String session) {
        String t = identifier(tableName, "table");
        String schema = t.contains(".") ? t.substring(0, t.lastIndexOf('.')) : null, name = t.substring(t.lastIndexOf('.') + 1);
        if (schema != null && schema.contains(".")) schema = schema.substring(schema.lastIndexOf('.') + 1);
        Connection c = ctx.session(session, DbSession.class).conn;
        List<Object> rows = new ArrayList<>();
        try {
            DatabaseMetaData md = c.getMetaData();
            for (String[] v : List.of(new String[]{schema, name}, new String[]{schema == null ? null : schema.toUpperCase(Locale.ROOT), name.toUpperCase(Locale.ROOT)},
                    new String[]{schema == null ? null : schema.toLowerCase(Locale.ROOT), name.toLowerCase(Locale.ROOT)})) {
                String esc = md.getSearchStringEscape();
                String pattern = esc == null || esc.isEmpty() ? v[1] : v[1].replace(esc, esc + esc).replace("_", esc + "_");
                try (ResultSet rs = md.getColumns(null, v[0], pattern, "%")) {
                    while (rs.next()) if (rs.getString("TABLE_NAME").equalsIgnoreCase(name)) rows.add(new ArrayList<>(Arrays.asList(rs.getString("COLUMN_NAME"), rs.getString("TYPE_NAME"),
                            BigDecimal.valueOf(rs.getInt("COLUMN_SIZE")), rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls, BigDecimal.valueOf(rs.getInt("ORDINAL_POSITION")))));
                }
                if (!rows.isEmpty()) break;
            }
        } catch (SQLException e) { throw sql(e); }
        if (rows.isEmpty()) throw new ActionException("DB.TABLE_NOT_FOUND", "Table '" + t + "' was not found", "Use List tables to see the exact names.");
        return Map.of("table", Values.table(List.of("name", "type", "size", "nullable", "position"), rows));
    }

    @Action(id = "disconnect", name = "Disconnect", category = "Database", description = "Close the session. Uncommitted changes are rolled back.")
    public Map<String, Object> disconnect(ActionContext ctx, @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"db\"", description = "Session name.") String session) {
        Object s = ctx.sessions().remove(session);
        if (s instanceof DbSession d) try { d.close(); } catch (SQLException e) { throw sql(e); }
        return Map.of();
    }
}
