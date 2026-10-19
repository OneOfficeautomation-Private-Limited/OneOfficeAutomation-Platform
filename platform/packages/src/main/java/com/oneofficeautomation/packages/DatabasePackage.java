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

    @Action(id = "disconnect", name = "Disconnect", category = "Database", description = "Close the session. Uncommitted changes are rolled back.")
    public Map<String, Object> disconnect(ActionContext ctx, @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"db\"", description = "Session name.") String session) {
        Object s = ctx.sessions().remove(session);
        if (s instanceof DbSession d) try { d.close(); } catch (SQLException e) { throw sql(e); }
        return Map.of();
    }
}
