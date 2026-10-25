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

    @Action(id = "connect", name = "Connect", category = "Database", capabilities = "network", description = "Open a database connection as a named session.",
            panel = "(Connect using: Connection details = database_type, server, port, database, file / A JDBC address = jdbc_url), user, password | session, [Load a specific driver class: driver_class], login_timeout_seconds, properties, read_only, auto_commit")
    public Map<String, Object> connect(ActionContext ctx,
            @Input(name = "jdbc_url", type = OoaType.STRING, required = false, pattern = "^(jdbc:|$)", description = "JDBC URL, e.g. jdbc:sqlserver://host;databaseName=erp; when empty it is built from database_type and the fields below.") String jdbcUrl,
            @Input(name = "database_type", type = OoaType.STRING, required = false, enumValues = {"sqlserver", "postgresql", "mysql", "oracle", "sqlite", "h2", "access"},
                    description = "Kind of database, used to build the URL when jdbc_url is empty.") String type,
            @Input(name = "server", type = OoaType.STRING, required = false, visibleWhen = "database_type=sqlserver|postgresql|mysql|oracle", example = "dbserver01", description = "Database server name or address.") String server,
            @Input(name = "port", type = OoaType.NUMBER, required = false, visibleWhen = "database_type=sqlserver|postgresql|mysql|oracle", min = 1, max = 65535, description = "Server port; the database's usual port when empty.") Integer port,
            @Input(name = "database", type = OoaType.STRING, required = false, visibleWhen = "database_type=sqlserver|postgresql|mysql|oracle", description = "Database name (service name for Oracle).") String database,
            @Input(name = "file", type = OoaType.FILE, required = false, visibleWhen = "database_type=sqlite|h2|access", description = "Database file for SQLite, H2 or Access.") String file,
            @Input(name = "user", type = OoaType.STRING, required = false, description = "User name.") String user,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, description = "Password from the vault.") Object password,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"db\"", description = "Session name for later steps.") String session,
            @Input(name = "driver_class", type = OoaType.STRING, required = false, example = "org.postgresql.Driver", description = "JDBC driver class to load first, for drivers that do not register themselves.") String driverClass,
            @Input(name = "login_timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0, description = "Give up connecting after this many seconds; 0 uses the driver's default.") int loginTimeout,
            @Input(name = "properties", type = OoaType.DICTIONARY, required = false, description = "Extra driver properties, e.g. {\"encrypt\": \"true\"}; Credential values are revealed only to the driver.") Map<String, Object> properties,
            @Input(name = "read_only", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Open the connection read-only, so the agent cannot change data by mistake (when the driver supports it).") boolean readOnly,
            @Input(name = "auto_commit", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Save each statement at once; false starts in a transaction that needs Commit.") boolean autoCommit) {
        String url = jdbcUrl != null && !jdbcUrl.isBlank() ? jdbcUrl.strip()
                : jdbcUrl(type, server, port, database, file == null || file.isBlank() ? null : ctx.resolvePath(file).toString());
        if (driverClass != null && !driverClass.isBlank()) {
            if (!driverClass.strip().matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+"))
                throw new ActionException("DB.BAD_DRIVER", "Invalid driver class '" + driverClass + "'", "Give the full class name, e.g. org.postgresql.Driver.");
            try { Class.forName(driverClass.strip(), true, Thread.currentThread().getContextClassLoader() != null ? Thread.currentThread().getContextClassLoader() : DatabasePackage.class.getClassLoader()); }
            catch (ClassNotFoundException | LinkageError e) { throw new ActionException("DB.NO_DRIVER", "JDBC driver " + driverClass + " is not installed on the runner", "Ask an administrator to install the database driver on the runner."); }
        }
        try {
            Properties p = new Properties();
            if (properties != null) properties.forEach((k, v) -> { if (v != null) p.setProperty(k, v instanceof SecureString ss ? ss.reveal() : Values.text(v)); });
            if (user != null) p.setProperty("user", user);
            if (password instanceof SecureString s) p.setProperty("password", s.reveal());
            Connection conn;
            synchronized (DriverManager.class) {  // the login timeout is global in JDBC, so set it only around this connection
                int previous = DriverManager.getLoginTimeout();
                if (loginTimeout > 0) DriverManager.setLoginTimeout(loginTimeout);
                try { conn = DriverManager.getConnection(url, p); } finally { DriverManager.setLoginTimeout(previous); }
            }
            try {
                if (readOnly) conn.setReadOnly(true);
                if (!autoCommit) conn.setAutoCommit(false);
            } catch (SQLException e) { conn.close(); throw e; }
            ctx.sessions().put(session, new DbSession(conn));
            ctx.info("Connected session '" + session + "' to " + url.replaceAll("(?i)password=[^;&]+", "password=****"));
        } catch (SQLException e) {
            if ("08001".equals(e.getSQLState()) || e.getMessage().contains("No suitable driver"))
                throw new ActionException("DB.NO_DRIVER", "No JDBC driver found for " + url.split(":")[1], "Ask an administrator to install the database driver on the runner.");
            throw new ActionException("DB.CONNECT_FAILED", "Cannot connect: " + e.getMessage(), "Check the URL, network access and credentials.", true);
        }
        return Map.of();
    }

    /** JDBC URL in each driver's usual form; server databases use server/port/database, file databases the file path. */
    static String jdbcUrl(String type, String server, Integer port, String database, String file) {
        if (type == null || type.isBlank())
            throw new ActionException("DB.BAD_INPUT", "Give a jdbc_url or a database_type", "Choose the database type and fill in its server or file, or type the full JDBC URL.");
        String t = type.strip().toLowerCase(Locale.ROOT);
        boolean fileDb = t.equals("sqlite") || t.equals("h2") || t.equals("access");
        if (fileDb) {
            if (file == null || file.isBlank()) throw new ActionException("DB.BAD_INPUT", t + " needs a database file", "Fill in file with the path of the database file.");
            return switch (t) {
                case "sqlite" -> "jdbc:sqlite:" + file;
                case "h2" -> "jdbc:h2:" + file.replaceFirst("(?i)\\.mv\\.db$", "");  // H2 adds .mv.db itself
                default -> "jdbc:ucanaccess://" + file;
            };
        }
        if (server == null || server.isBlank()) throw new ActionException("DB.BAD_INPUT", t + " needs a server", "Fill in server with the database server name or address.");
        String host = server.strip(), db = database == null || database.isBlank() ? null : database.strip();
        String hp = host + (port != null ? ":" + port : "");
        return switch (t) {
            case "sqlserver" -> "jdbc:sqlserver://" + hp + (db != null ? ";databaseName=" + db : "");
            case "postgresql" -> "jdbc:postgresql://" + hp + "/" + (db != null ? db : "");
            case "mysql" -> "jdbc:mysql://" + hp + "/" + (db != null ? db : "");
            case "oracle" -> {
                if (db == null) throw new ActionException("DB.BAD_INPUT", "oracle needs a database (service name)", "Fill in database with the Oracle service name, e.g. ORCLPDB1.");
                yield "jdbc:oracle:thin:@//" + host + ":" + (port != null ? port : 1521) + "/" + db;
            }
            default -> throw new ActionException("DB.BAD_INPUT", "Unknown database_type '" + type + "'", "Use sqlserver, postgresql, mysql, oracle, sqlite, h2 or access.");
        };
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
            description = "Run a SELECT with ? parameters and return the rows as a table.",
            panel = "sql, parameters, max_rows, query_timeout_seconds | session")
    @Output(name = "table", type = OoaType.TABLE, description = "Result rows.")
    @Output(name = "row_count", type = OoaType.NUMBER, description = "Number of rows.")
    public Map<String, Object> query(ActionContext ctx,
            @Input(name = "sql", type = OoaType.STRING, description = "SELECT statement with ? placeholders.") String sql,
            @Input(name = "parameters", type = OoaType.LIST, required = false, description = "Values for the ? placeholders, in order.") List<Object> params,
            @Input(name = "max_rows", type = OoaType.NUMBER, required = false, defaultValue = "100000", min = 1, description = "Safety limit on rows returned.") int maxRows,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"db\"", description = "Session name.") String session,
            @Input(name = "query_timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0, description = "Cancel the statement after this many seconds; 0 waits as long as the step allows.") int queryTimeout) {
        Connection c = ctx.session(session, DbSession.class).conn;
        try (PreparedStatement ps = prepare(c, sql, params)) {
            ps.setMaxRows(maxRows);
            if (queryTimeout > 0) ps.setQueryTimeout(queryTimeout);
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

    @LoopIterator(id = "query_rows", name = "Each row of a SQL query", description = "Run a SELECT with ? parameters and yield each row as a record keyed by column name.")
    @SuppressWarnings("unchecked")
    public Iterable<Object> queryRows(ActionContext ctx,
            @Input(name = "sql", type = OoaType.STRING, description = "SELECT statement with ? placeholders.") String sql,
            @Input(name = "parameters", type = OoaType.LIST, required = false, description = "Values for the ? placeholders, in order.") List<Object> params,
            @Input(name = "max_rows", type = OoaType.NUMBER, required = false, defaultValue = "100000", min = 1, description = "Safety limit on rows read.") int maxRows,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"db\"", description = "Session name.") String session,
            @Input(name = "query_timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0, description = "Cancel the statement after this many seconds; 0 waits as long as the step allows.") int queryTimeout) {
        // rows are read before the loop starts, so steps inside it can use the same session freely
        Map<String, Object> t = query(ctx, sql, params, maxRows, session, queryTimeout);
        Map<String, Object> table = (Map<String, Object>) t.get("table");
        List<String> cols = DataTablePackage.cols(table);
        List<Object> out = new ArrayList<>();
        for (List<Object> r : DataTablePackage.rows(table)) {
            Map<String, Object> rec = new LinkedHashMap<>();
            for (int i = 0; i < cols.size(); i++) rec.put(cols.get(i), DataTablePackage.cell(r, i));
            out.add(rec);
        }
        return out;
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
            description = "Run an INSERT, UPDATE, DELETE or DDL statement with ? parameters.",
            panel = "sql, parameters, query_timeout_seconds | session")
    @Output(name = "rows_affected", type = OoaType.NUMBER, description = "Rows changed.")
    public Map<String, Object> execute(ActionContext ctx,
            @Input(name = "sql", type = OoaType.STRING, description = "Statement with ? placeholders.") String sql,
            @Input(name = "parameters", type = OoaType.LIST, required = false, description = "Values for the ? placeholders, in order.") List<Object> params,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"db\"", description = "Session name.") String session,
            @Input(name = "query_timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0, description = "Cancel the statement after this many seconds; 0 waits as long as the step allows.") int queryTimeout) {
        Connection c = ctx.session(session, DbSession.class).conn;
        try (PreparedStatement ps = prepare(c, sql, params)) {
            if (queryTimeout > 0) ps.setQueryTimeout(queryTimeout);
            return Out.of("rows_affected", BigDecimal.valueOf(ps.executeUpdate()));
        } catch (SQLException e) { throw sql(e); }
    }

    @Action(id = "begin_transaction", name = "Begin transaction", category = "Transactions", description = "Start a transaction; changes are kept only after Commit.",
            panel = "session")
    public Map<String, Object> begin(ActionContext ctx, @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"db\"", description = "Session name.") String session) {
        try { ctx.session(session, DbSession.class).conn.setAutoCommit(false); } catch (SQLException e) { throw sql(e); }
        return Map.of();
    }

    @Action(id = "commit", name = "Commit", category = "Transactions", description = "Save the changes made since Begin transaction.",
            panel = "session")
    public Map<String, Object> commit(ActionContext ctx, @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"db\"", description = "Session name.") String session) {
        try { Connection c = ctx.session(session, DbSession.class).conn; c.commit(); c.setAutoCommit(true); } catch (SQLException e) { throw sql(e); }
        return Map.of();
    }

    @Action(id = "rollback", name = "Roll back", category = "Transactions", description = "Undo the changes made since Begin transaction.",
            panel = "session")
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
            description = "Insert every row of a Data Table, or of a delimited text file read line by line, into a database table in batches, all or nothing.",
            panel = "table, (Rows from: A Data Table = data / A delimited file = source_file, delimiter, start_row, has_header, encoding), columns, batch_size | session")
    @Output(name = "rows_inserted", type = OoaType.NUMBER, description = "Rows inserted.")
    public Map<String, Object> bulkInsert(ActionContext ctx,
            @Input(name = "table", type = OoaType.STRING, example = "dbo.invoices", description = "Database table to insert into.") String tableName,
            @Input(name = "data", type = OoaType.TABLE, required = false, description = "Data Table whose rows are inserted (or use source_file).") Map<String, Object> data,
            @Input(name = "columns", type = OoaType.LIST, required = false, description = "Database column for each Data Table or file column, in order; the Data Table's column names (or the file's header) when empty.") List<Object> columns,
            @Input(name = "batch_size", type = OoaType.NUMBER, required = false, defaultValue = "500", min = 1, max = 100000, description = "Rows sent to the database per batch.") int batchSize,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"db\"", description = "Session name.") String session,
            @Input(name = "source_file", type = OoaType.FILE, required = false, example = "input/invoices.csv",
                    description = "Delimited text file (CSV) whose rows are inserted instead of a Data Table; it is read as it goes, so large files fit. Empty fields are inserted as NULL.") String sourceFile,
            @Input(name = "delimiter", type = OoaType.STRING, required = false, defaultValue = "\",\"", visibleWhen = "source_file!=", description = "Field separator of the file: one character, or tab.") String delimiter,
            @Input(name = "start_row", type = OoaType.NUMBER, required = false, defaultValue = "1", min = 1, visibleWhen = "source_file!=",
                    description = "Line of the file where reading starts (1 = the first line); lines above it, such as a title, are skipped.") int startRow,
            @Input(name = "has_header", type = OoaType.BOOLEAN, required = false, defaultValue = "true", visibleWhen = "source_file!=",
                    description = "The first line read holds the column names, used as database columns when columns is empty.") boolean hasHeader,
            @Input(name = "encoding", type = OoaType.STRING, required = false, defaultValue = "\"utf-8\"", visibleWhen = "source_file!=", description = "Text encoding of the file, e.g. utf-8 or windows-1252.") String encoding) {
        String t = identifier(tableName, "table");
        boolean fromFile = sourceFile != null && !sourceFile.isBlank();
        if (fromFile == (data != null))
            throw new ActionException("DB.BAD_INPUT", fromFile ? "Both data and source_file are given" : "No rows to insert", "Give either a Data Table in data or a file in source_file.");
        if (!fromFile) {
            List<String> srcCols = DataTablePackage.cols(data);
            List<String> cols = columnNames(columns, srcCols);
            if (cols.isEmpty()) throw new ActionException("DB.NO_COLUMNS", "The Data Table has no columns", "Give a Data Table with at least one column.");
            if (cols.size() != srcCols.size())
                throw new ActionException("DB.COLUMN_MISMATCH", "columns lists " + cols.size() + " names but the Data Table has " + srcCols.size() + " columns", "Give one database column per Data Table column.");
            Iterator<List<Object>> it = DataTablePackage.rows(data).iterator();
            return insertBatches(ctx, t, cols, () -> it.hasNext() ? it.next() : null, batchSize, session);
        }
        String d = delimiter == null || delimiter.isEmpty() ? "," : delimiter;
        char dc = d.equalsIgnoreCase("tab") || d.equals("\\t") ? '\t' : d.length() == 1 ? d.charAt(0) : 0;
        if (dc == 0 || dc == '"' || dc == '\r' || dc == '\n') throw new ActionException("DB.BAD_DELIMITER", "The delimiter must be one character, got '" + d + "'", "Use , ; | or tab.");
        if (startRow < 1) throw new ActionException("DB.BAD_INPUT", "start_row must be 1 or more", "Lines are counted from 1.");
        java.nio.file.Path p = ctx.resolvePath(sourceFile);
        if (!java.nio.file.Files.isRegularFile(p)) throw new ActionException("FILE.NOT_FOUND", "File not found: " + p, "Check the path of source_file.");
        java.nio.charset.Charset cs = CsvPackage.encoding(encoding).charset();
        try (java.io.BufferedReader r = java.nio.file.Files.newBufferedReader(p, cs)) {
            DelimitedReader in = new DelimitedReader(r, dc);
            for (int i = 1; i < startRow; i++) if (!in.skipLine()) break;
            List<String> header = hasHeader ? in.next() : null;
            List<String> cols = columnNames(columns, header == null ? List.of() : header);
            if (cols.isEmpty()) throw new ActionException("DB.NO_COLUMNS", "No database columns", hasHeader ? "The file has no header line at start_row; fill in columns." : "Fill in columns with the database column of each field.");
            return insertBatches(ctx, t, cols, () -> {
                List<String> row;
                try { row = in.next(); }
                catch (java.io.IOException e) { throw new ActionException("FILE.IO_ERROR", "Cannot read " + p + ": " + e.getMessage(), null, true); }
                if (row == null) return null;
                if (row.size() > cols.size())
                    throw new ActionException("DB.COLUMN_MISMATCH", "Line " + in.line + " has " + row.size() + " fields but " + cols.size() + " columns are inserted", "Give one database column per field in columns, or check the delimiter.");
                List<Object> vals = new ArrayList<>();
                for (String v : row) vals.add(v.isEmpty() ? null : v);
                return vals;
            }, batchSize, session);
        } catch (java.io.IOException e) { throw new ActionException("FILE.IO_ERROR", "Cannot read " + p + ": " + e.getMessage(), null, true); }
    }

    public Map<String, Object> bulkInsert(ActionContext ctx, String tableName, Map<String, Object> data, List<Object> columns, int batchSize, String session) {
        return bulkInsert(ctx, tableName, data, columns, batchSize, session, null, ",", 1, true, "utf-8");
    }

    private static List<String> columnNames(List<Object> columns, List<String> fallback) {
        List<String> cols = new ArrayList<>();
        for (Object c : columns == null || columns.isEmpty() ? new ArrayList<Object>(fallback) : columns) cols.add(identifier(Values.text(c).strip(), "column"));
        return cols;
    }

    /** Next row to insert, or null at the end. */
    private interface RowSource { List<Object> next(); }

    private static Map<String, Object> insertBatches(ActionContext ctx, String t, List<String> cols, RowSource rows, int batchSize, String session) {
        String sql = "INSERT INTO " + t + " (" + String.join(", ", cols) + ") VALUES (" + String.join(", ", Collections.nCopies(cols.size(), "?")) + ")";
        Connection c = ctx.session(session, DbSession.class).conn;
        int size = Math.max(1, Math.min(10_000, batchSize)), done = 0;
        try {
            boolean auto = c.getAutoCommit();
            if (auto) c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                int pending = 0;
                for (List<Object> row = rows.next(); row != null; row = rows.next()) {
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

    /** Reads RFC 4180 delimited rows one at a time (quoted fields may hold delimiters, quotes and line breaks). */
    static final class DelimitedReader {
        private final java.io.Reader in;
        private final char d;
        private int peeked = -2;
        /** Line number where the last row returned by next() started (1-based). */
        int line;
        private int at = 1;
        private boolean first = true;

        DelimitedReader(java.io.Reader in, char d) { this.in = in; this.d = d; }

        private int read() throws java.io.IOException {
            int c = peeked != -2 ? peeked : in.read();
            peeked = -2;
            if (first) { first = false; if (c == '\uFEFF') return read(); }
            return c;
        }

        private void endOfLine(int c) throws java.io.IOException {
            at++;
            if (c == '\r') { int n = in.read(); if (n != '\n') peeked = n; }
        }

        /** Skips one physical line; false at the end of the file. */
        boolean skipLine() throws java.io.IOException {
            int c = read();
            if (c == -1) return false;
            while (c != -1 && c != '\n' && c != '\r') c = read();
            if (c != -1) endOfLine(c);
            return true;
        }

        /** The next row's fields, skipping empty lines; null at the end of the file. */
        List<String> next() throws java.io.IOException {
            while (true) {
                int c = read();
                if (c == -1) return null;
                if (c == '\n' || c == '\r') { endOfLine(c); continue; }
                line = at;
                List<String> row = new ArrayList<>();
                StringBuilder f = new StringBuilder();
                boolean quoted = false;
                while (true) {
                    if (c == -1) {
                        if (quoted) throw new ActionException("DB.BAD_FILE", "A quoted field that starts on line " + line + " is not closed", "Check for a missing closing quote.");
                        row.add(f.toString());
                        return row;
                    }
                    if (quoted) {
                        if (c == '"') {
                            int n = read();
                            if (n == '"') f.append('"'); else { quoted = false; c = n; continue; }
                        } else { if (c == '\n') at++; f.append((char) c); }
                    } else if (c == '"') quoted = true;
                    else if (c == d) { row.add(f.toString()); f.setLength(0); }
                    else if (c == '\n' || c == '\r') { endOfLine(c); row.add(f.toString()); return row; }
                    else f.append((char) c);
                    c = read();
                }
            }
        }
    }

    public Map<String, Object> callProcedure(ActionContext ctx, String procedure, List<Object> params, int maxRows, String session, int queryTimeout) {
        return callProcedure(ctx, procedure, params, maxRows, session, queryTimeout, false, null);
    }

    /** SQL types an output parameter can be registered with. */
    private static final Map<String, Integer> OUT_TYPES = Map.of("varchar", Types.VARCHAR, "integer", Types.INTEGER, "bigint", Types.BIGINT,
            "decimal", Types.DECIMAL, "double", Types.DOUBLE, "boolean", Types.BOOLEAN, "date", Types.DATE, "timestamp", Types.TIMESTAMP);

    @Action(id = "call_procedure", name = "Run stored procedure", category = "Database", timeoutSeconds = 600, capabilities = "network",
            description = "Call a stored procedure or function with input, output and in-out parameters; return the rows it produces, its output values and a function's return value.",
            panel = "procedure, parameters, [It is a function that returns a value: returns_value, return_type], max_rows, query_timeout_seconds | session")
    @Output(name = "table", type = OoaType.TABLE, description = "First result set, or an empty table.")
    @Output(name = "row_count", type = OoaType.NUMBER, description = "Rows in the result table.")
    @Output(name = "rows_affected", type = OoaType.NUMBER, description = "Rows changed, when the database reports it; otherwise -1.")
    @Output(name = "out_parameters", type = OoaType.DICTIONARY, description = "Output and in-out values by parameter name (or position 1, 2 ... when unnamed); the function's value as \"return\".")
    public Map<String, Object> callProcedure(ActionContext ctx,
            @Input(name = "procedure", type = OoaType.STRING, example = "dbo.close_period", description = "Procedure name, optionally schema.name.") String procedure,
            @Input(name = "parameters", type = OoaType.LIST, required = false,
                    description = "Parameters in the procedure's order: a plain value is an input; {\"value\": v, \"direction\": \"out\" or \"inout\", \"type\": \"varchar|integer|bigint|decimal|double|boolean|date|timestamp\", \"name\": \"p_total\"} declares an output.") List<Object> params,
            @Input(name = "max_rows", type = OoaType.NUMBER, required = false, defaultValue = "100000", min = 1, description = "Safety limit on rows returned.") int maxRows,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"db\"", description = "Session name.") String session,
            @Input(name = "query_timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0, description = "Cancel the statement after this many seconds; 0 waits as long as the step allows.") int queryTimeout,
            @Input(name = "returns_value", type = OoaType.BOOLEAN, required = false, defaultValue = "false",
                    description = "Call it as a function ({? = call ...}) and return its value as out_parameters.return.") boolean returnsValue,
            @Input(name = "return_type", type = OoaType.STRING, required = false, enumValues = {"varchar", "integer", "bigint", "decimal", "double", "boolean", "date", "timestamp"},
                    description = "SQL type of the function's value; varchar when empty.") String returnType) {
        String name = identifier(procedure, "procedure");
        int n = params == null ? 0 : params.size();
        int shift = returnsValue ? 1 : 0;
        String call = "{" + (returnsValue ? "? = " : "") + "call " + name + "(" + String.join(", ", Collections.nCopies(n, "?")) + ")}";
        // directions and types are checked before the database is asked anything
        if (returnsValue) sqlType(returnType);
        for (int i = 0; i < n; i++)
            if (params.get(i) instanceof Map<?, ?> m && m.containsKey("direction")) {
                String dir = String.valueOf(m.get("direction")).strip().toLowerCase(Locale.ROOT);
                if (!dir.equals("in") && !dir.equals("out") && !dir.equals("inout"))
                    throw new ActionException("DB.BAD_PARAMETER", "Parameter " + (i + 1) + " has direction '" + m.get("direction") + "'", "Use in, out or inout.");
                if (!dir.equals("in")) sqlType(m.get("type") == null ? null : String.valueOf(m.get("type")));
            }
        Connection c = ctx.session(session, DbSession.class).conn;
        try (CallableStatement cs = c.prepareCall(call)) {
            // index -> output name, in the order the values are read back
            Map<Integer, String> outs = new LinkedHashMap<>();
            if (returnsValue) { cs.registerOutParameter(1, sqlType(returnType)); outs.put(1, "return"); }
            for (int i = 0; i < n; i++) {
                Object p = params.get(i);
                int at = i + 1 + shift;
                if (p instanceof Map<?, ?> m && m.containsKey("direction")) {
                    String dir = String.valueOf(m.get("direction")).strip().toLowerCase(Locale.ROOT);
                    if (!dir.equals("in") && !dir.equals("out") && !dir.equals("inout"))
                        throw new ActionException("DB.BAD_PARAMETER", "Parameter " + (i + 1) + " has direction '" + m.get("direction") + "'", "Use in, out or inout.");
                    if (!dir.equals("out")) bind(cs, at, m.get("value"));
                    if (!dir.equals("in")) {
                        cs.registerOutParameter(at, sqlType(m.get("type") == null ? null : String.valueOf(m.get("type"))));
                        outs.put(at, m.get("name") == null || String.valueOf(m.get("name")).isBlank() ? String.valueOf(i + 1) : String.valueOf(m.get("name")));
                    }
                } else bind(cs, at, p);
            }
            if (queryTimeout > 0) cs.setQueryTimeout(queryTimeout);
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
            // output values are read after the result sets, as JDBC asks; some drivers (H2) hand a function's value back as a
            // one-cell result set instead, which then stands for it
            Map<String, Object> outValues = new LinkedHashMap<>();
            for (Map.Entry<Integer, String> o : outs.entrySet()) {
                try { outValues.put(o.getValue(), value(cs.getObject(o.getKey()))); }
                catch (SQLException e) {
                    List<List<Object>> rows = DataTablePackage.rows(table);
                    if (!"return".equals(o.getValue()) || rows.size() != 1 || rows.get(0).size() != 1) throw e;
                    outValues.put("return", rows.get(0).get(0));
                }
            }
            return Out.of("table", table, "row_count", BigDecimal.valueOf(DataTablePackage.rows(table).size()), "rows_affected", BigDecimal.valueOf(affected),
                    "out_parameters", outValues);
        } catch (SQLException e) { throw sql(e); }
    }

    private static int sqlType(String name) {
        if (name == null || name.isBlank()) return Types.VARCHAR;
        Integer t = OUT_TYPES.get(name.strip().toLowerCase(Locale.ROOT));
        if (t == null) throw new ActionException("DB.BAD_PARAMETER", "Unknown parameter type '" + name + "'", "Use one of " + String.join(", ", new TreeSet<>(OUT_TYPES.keySet())) + ".");
        return t;
    }

    @Action(id = "export_to_file", name = "Export to CSV file", category = "Database", timeoutSeconds = 3600, capabilities = {"network", "filesystem"},
            description = "Run a SELECT or a stored procedure with ? parameters and write its rows to a CSV file without loading them into memory.",
            panel = "(Rows from: A SELECT = sql / A stored procedure = procedure), parameters, path, encoding, header | delimiter, overwrite, max_rows, query_timeout_seconds, session")
    @Output(name = "path", type = OoaType.FILE, description = "Written file.")
    @Output(name = "row_count", type = OoaType.NUMBER, description = "Rows written.")
    public Map<String, Object> exportToFile(ActionContext ctx,
            @Input(name = "sql", type = OoaType.STRING, required = false, description = "SELECT statement with ? placeholders (or use procedure).") String sql,
            @Input(name = "path", type = OoaType.FILE, description = "CSV file to create.") String path,
            @Input(name = "parameters", type = OoaType.LIST, required = false, description = "Values for the ? placeholders, in order.") List<Object> params,
            @Input(name = "delimiter", type = OoaType.STRING, required = false, defaultValue = "\",\"", description = "Field separator, e.g. , or ; or tab.") String delimiter,
            @Input(name = "header", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Write the column names as the first line.") boolean header,
            @Input(name = "encoding", type = OoaType.STRING, required = false, defaultValue = "\"utf-8\"", description = "Text encoding of the file.") String encoding,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"db\"", description = "Session name.") String session,
            @Input(name = "query_timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0, description = "Cancel the statement after this many seconds; 0 waits as long as the step allows.") int queryTimeout,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Replace an existing file; when false an existing file stops the step.") boolean overwrite,
            @Input(name = "procedure", type = OoaType.STRING, required = false, example = "dbo.month_report",
                    description = "Stored procedure whose first result set is written, used instead of sql; parameters are its input values in order.") String procedure,
            @Input(name = "max_rows", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0, description = "Stop after writing this many rows; 0 writes them all.") int maxRows) {
        boolean byProc = procedure != null && !procedure.isBlank(), bySql = sql != null && !sql.isBlank();
        if (byProc == bySql)
            throw new ActionException("DB.BAD_INPUT", byProc ? "Both sql and procedure are given" : "No SELECT or procedure given", "Fill in either sql or procedure.");
        if (maxRows < 0) throw new ActionException("DB.BAD_INPUT", "max_rows cannot be negative", "Use 0 for all rows, or a positive limit.");
        String proc = byProc ? identifier(procedure, "procedure") : null;
        char d = delimiter == null || delimiter.isEmpty() ? ',' : delimiter.equalsIgnoreCase("tab") ? '\t' : delimiter.charAt(0);
        java.nio.file.Path p = ctx.resolvePath(path);
        if (!overwrite && java.nio.file.Files.exists(p)) throw new ActionException("DB.FILE_EXISTS", "File already exists: " + p, "Set overwrite to true or choose another name.");
        Connection c = ctx.session(session, DbSession.class).conn;
        long count = 0;
        try (PreparedStatement ps = byProc ? c.prepareCall("{call " + proc + "(" + String.join(", ", Collections.nCopies(params == null ? 0 : params.size(), "?")) + ")}") : prepare(c, sql, params)) {
            if (byProc && params != null) for (int i = 0; i < params.size(); i++) bind(ps, i + 1, params.get(i));
            ps.setFetchSize(1000);
            if (maxRows > 0) ps.setMaxRows(maxRows);
            if (queryTimeout > 0) ps.setQueryTimeout(queryTimeout);
            try (ResultSet rs = byProc ? firstResultSet(ps, proc) : ps.executeQuery()) {
                if (p.getParent() != null) java.nio.file.Files.createDirectories(p.getParent());
                try (java.io.BufferedWriter w = java.nio.file.Files.newBufferedWriter(p, FilePackage.charset(encoding))) {
                    ResultSetMetaData md = rs.getMetaData();
                    int n = md.getColumnCount();
                    if (header) {
                        for (int i = 1; i <= n; i++) { if (i > 1) w.write(d); w.write(CsvPackage.quote(md.getColumnLabel(i), d)); }
                        w.write("\r\n");
                    }
                    while ((maxRows == 0 || count < maxRows) && rs.next()) {
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

    public Map<String, Object> exportToFile(ActionContext ctx, String sql, String path, List<Object> params, String delimiter, boolean header, String encoding,
            String session, int queryTimeout, boolean overwrite) {
        return exportToFile(ctx, sql, path, params, delimiter, header, encoding, session, queryTimeout, overwrite, null, 0);
    }

    /** Runs the procedure call and returns its first result set, skipping update counts before it. */
    private static ResultSet firstResultSet(PreparedStatement ps, String proc) throws SQLException {
        boolean hasRs = ps.execute();
        while (!hasRs) {
            if (ps.getUpdateCount() == -1)
                throw new ActionException("DB.NO_RESULT", "The procedure " + proc + " returned no rows to export", "Choose a procedure that ends with a SELECT, or use Run stored procedure.");
            hasRs = ps.getMoreResults();
        }
        return ps.getResultSet();
    }

    @Action(id = "list_tables", name = "List tables", category = "Schema", idempotent = true, capabilities = "network",
            description = "List the tables and views the connection can see, optionally in one schema.",
            panel = "[Only one schema: schema], include_views | session")
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
            description = "Return the columns of a table with their types, sizes and whether they accept empty values.",
            panel = "table | session")
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

    @Action(id = "disconnect", name = "Disconnect", category = "Database", description = "Close the session. Uncommitted changes are rolled back.",
            panel = "session")
    public Map<String, Object> disconnect(ActionContext ctx, @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"db\"", description = "Session name.") String session) {
        Object s = ctx.sessions().remove(session);
        if (s instanceof DbSession d) try { d.close(); } catch (SQLException e) { throw sql(e); }
        return Map.of();
    }
}
