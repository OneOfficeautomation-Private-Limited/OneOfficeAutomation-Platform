package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.sdk.OoaException;

import java.sql.*;
import java.util.*;

/**
 * JDBC access with a small connection pool and versioned schema migrations. Portable SQL that runs on embedded H2
 * (default, PostgreSQL compatibility mode) and on PostgreSQL in production.
 */
public final class Db implements AutoCloseable {
    private final String url, user, password;
    private final Deque<Connection> pool = new ArrayDeque<>();
    private final ThreadLocal<Connection> txConn = new ThreadLocal<>();
    public final boolean postgres;

    public Db(String url, String user, String password) {
        this.url = url; this.user = user; this.password = password;
        this.postgres = url.startsWith("jdbc:postgresql:");
        try (Connection c = open()) { /* fail fast on a wrong URL or missing driver */ }
        catch (SQLException e) { throw new OoaException("CR.DB_UNAVAILABLE", "Cannot open the database: " + e.getMessage(), "Check --db-url, the user and password, and that the database server is running."); }
    }

    public static Db embedded(java.nio.file.Path dir) {
        return new Db("jdbc:h2:file:" + dir.resolve("controlroom").toAbsolutePath() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;AUTO_SERVER=FALSE", "ooa", "");
    }

    private Connection open() throws SQLException { return DriverManager.getConnection(url, user, password); }

    private synchronized Connection borrow() throws SQLException {
        Connection c = pool.pollFirst();
        if (c != null && c.isValid(2)) return c;
        if (c != null) try { c.close(); } catch (SQLException ignored) { }
        return open();
    }

    private synchronized void giveBack(Connection c) {
        try {
            if (c.isClosed()) return;
            if (!c.getAutoCommit()) { c.rollback(); c.setAutoCommit(true); }
            if (pool.size() < 10) pool.addLast(c); else c.close();
        } catch (SQLException ignored) { }
    }

    public interface Work<T> { T run() throws SQLException; }

    /** Runs work in one transaction; nested calls join it. Any exception rolls everything back. */
    public <T> T tx(Work<T> work) {
        if (txConn.get() != null) { try { return work.run(); } catch (SQLException e) { throw dbError(e); } }
        Connection c = null;
        try {
            c = borrow();
            c.setAutoCommit(false);
            txConn.set(c);
            T r = work.run();
            c.commit();
            return r;
        } catch (SQLException e) {
            try { if (c != null) c.rollback(); } catch (SQLException ignored) { }
            throw dbError(e);
        } catch (RuntimeException e) {
            try { if (c != null) c.rollback(); } catch (SQLException ignored) { }
            throw e;
        } finally {
            txConn.remove();
            if (c != null) giveBack(c);
        }
    }

    private static OoaException dbError(SQLException e) {
        String state = e.getSQLState() == null ? "" : e.getSQLState();
        if (state.startsWith("23")) return new OoaException("CR.CONFLICT", "The change conflicts with existing data: " + e.getMessage().split("\n")[0], "Refresh and try again.");
        return new OoaException("CR.DB_ERROR", "Database error: " + e.getMessage().split("\n")[0]);
    }

    private <T> T withConn(Work2<T> w) {
        Connection tc = txConn.get();
        if (tc != null) { try { return w.run(tc); } catch (SQLException e) { throw dbError(e); } }
        Connection c = null;
        try { c = borrow(); return w.run(c); }
        catch (SQLException e) { throw dbError(e); }
        finally { if (c != null) giveBack(c); }
    }

    private interface Work2<T> { T run(Connection c) throws SQLException; }

    private static void bind(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            Object p = params[i];
            if (p instanceof java.time.Instant t) ps.setTimestamp(i + 1, Timestamp.from(t));
            else ps.setObject(i + 1, p);
        }
    }

    public List<Map<String, Object>> query(String sql, Object... params) {
        return withConn(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                bind(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    ResultSetMetaData md = rs.getMetaData();
                    List<Map<String, Object>> out = new ArrayList<>();
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= md.getColumnCount(); i++) {
                            Object v = rs.getObject(i);
                            if (v instanceof Timestamp t) v = t.toInstant().toString();
                            if (v instanceof Clob cl) v = cl.getSubString(1, (int) cl.length());
                            row.put(md.getColumnLabel(i).toLowerCase(Locale.ROOT), v);
                        }
                        out.add(row);
                    }
                    return out;
                }
            }
        });
    }

    public Map<String, Object> one(String sql, Object... params) {
        List<Map<String, Object>> r = query(sql, params);
        return r.isEmpty() ? null : r.get(0);
    }

    public int update(String sql, Object... params) {
        return withConn(c -> { try (PreparedStatement ps = c.prepareStatement(sql)) { bind(ps, params); return ps.executeUpdate(); } });
    }

    private static final String[] SCHEMA_V1 = {
        "CREATE TABLE users (id VARCHAR(40) PRIMARY KEY, username VARCHAR(80) NOT NULL UNIQUE, display_name VARCHAR(200), password_hash VARCHAR(300) NOT NULL, " +
            "role VARCHAR(30) NOT NULL, enabled BOOLEAN NOT NULL, must_change BOOLEAN NOT NULL, failed INT NOT NULL, locked_until TIMESTAMP, created TIMESTAMP NOT NULL, last_login TIMESTAMP)",
        "CREATE TABLE sessions (token_hash VARCHAR(64) PRIMARY KEY, user_id VARCHAR(40) NOT NULL, created TIMESTAMP NOT NULL, expires TIMESTAMP NOT NULL, ip VARCHAR(64))",
        "CREATE TABLE audit (seq BIGINT PRIMARY KEY, ts TIMESTAMP NOT NULL, actor VARCHAR(120) NOT NULL, action VARCHAR(80) NOT NULL, object_type VARCHAR(40), object_id VARCHAR(400), " +
            "details VARCHAR(4000), result VARCHAR(20) NOT NULL, ip VARCHAR(64), prev_hash VARCHAR(64) NOT NULL, hash VARCHAR(64) NOT NULL)",
        "CREATE TABLE bots (path VARCHAR(400) PRIMARY KEY, bot_id VARCHAR(100) NOT NULL UNIQUE, name VARCHAR(200), description VARCHAR(2000), latest_version INT NOT NULL, " +
            "production_version INT, locked_by VARCHAR(80), locked_at TIMESTAMP, created TIMESTAMP NOT NULL, updated TIMESTAMP NOT NULL, updated_by VARCHAR(80))",
        "CREATE TABLE bot_versions (path VARCHAR(400) NOT NULL, version INT NOT NULL, content VARCHAR(1000000) NOT NULL, sha256 VARCHAR(64) NOT NULL, comment VARCHAR(1000), " +
            "author VARCHAR(80) NOT NULL, created TIMESTAMP NOT NULL, PRIMARY KEY (path, version))",
        "CREATE TABLE devices (id VARCHAR(40) PRIMARY KEY, name VARCHAR(120) NOT NULL, hostname VARCHAR(200), os VARCHAR(120), agent_version VARCHAR(40), secret_hash VARCHAR(64) NOT NULL, " +
            "enabled BOOLEAN NOT NULL, last_seen TIMESTAMP, registered TIMESTAMP NOT NULL, registered_by VARCHAR(80))",
        "CREATE TABLE registration_keys (key_hash VARCHAR(64) PRIMARY KEY, created_by VARCHAR(80) NOT NULL, created TIMESTAMP NOT NULL, expires TIMESTAMP NOT NULL, used_by VARCHAR(40))",
        "CREATE TABLE runs (id VARCHAR(40) PRIMARY KEY, bot_path VARCHAR(400) NOT NULL, version INT NOT NULL, device_id VARCHAR(40) NOT NULL, status VARCHAR(20) NOT NULL, " +
            "inputs VARCHAR(100000), requested_by VARCHAR(80) NOT NULL, source VARCHAR(40) NOT NULL, created TIMESTAMP NOT NULL, started TIMESTAMP, finished TIMESTAMP, " +
            "duration_ms BIGINT, error VARCHAR(10000), outputs VARCHAR(100000), cancel_requested BOOLEAN NOT NULL, pause_requested BOOLEAN NOT NULL, steps INT)",
        "CREATE INDEX runs_status ON runs (status, device_id)",
        "CREATE TABLE run_events (run_id VARCHAR(40) NOT NULL, seq BIGINT NOT NULL, event VARCHAR(20000) NOT NULL, PRIMARY KEY (run_id, seq))",
        "CREATE TABLE schedules (id VARCHAR(40) PRIMARY KEY, name VARCHAR(200) NOT NULL, bot_path VARCHAR(400) NOT NULL, device_id VARCHAR(40) NOT NULL, kind VARCHAR(20) NOT NULL, " +
            "every_minutes INT, at_time VARCHAR(5), days VARCHAR(40), time_zone VARCHAR(60) NOT NULL, inputs VARCHAR(100000), enabled BOOLEAN NOT NULL, next_run TIMESTAMP, last_run TIMESTAMP, created_by VARCHAR(80))",
        "CREATE TABLE triggers (id VARCHAR(40) PRIMARY KEY, name VARCHAR(200) NOT NULL, bot_path VARCHAR(400) NOT NULL, device_id VARCHAR(40) NOT NULL, kind VARCHAR(20) NOT NULL, " +
            "folder VARCHAR(500), pattern VARCHAR(200), secret_hash VARCHAR(64), enabled BOOLEAN NOT NULL, created_by VARCHAR(80))",
        "CREATE TABLE lockers (name VARCHAR(80) PRIMARY KEY, description VARCHAR(500), owners VARCHAR(1000) NOT NULL, consumers VARCHAR(1000) NOT NULL, created_by VARCHAR(80))",
        "CREATE TABLE credentials (locker VARCHAR(80) NOT NULL, name VARCHAR(80) NOT NULL, attribute VARCHAR(80) NOT NULL, ciphertext VARCHAR(20000) NOT NULL, " +
            "updated TIMESTAMP NOT NULL, updated_by VARCHAR(80), PRIMARY KEY (locker, name, attribute))",
        "CREATE TABLE global_values (name VARCHAR(80) PRIMARY KEY, json_value VARCHAR(100000) NOT NULL, updated TIMESTAMP NOT NULL, updated_by VARCHAR(80))",
    };

    /** Version 2 (Part 4): settings, custom roles, API keys, runner pools, work queues, model connections, holiday calendars, toolkit switches. */
    private static final String[] SCHEMA_V2 = {
        "CREATE TABLE settings (name VARCHAR(80) PRIMARY KEY, json_value VARCHAR(10000) NOT NULL, updated TIMESTAMP NOT NULL, updated_by VARCHAR(80))",
        "CREATE TABLE roles (name VARCHAR(40) PRIMARY KEY, description VARCHAR(500), permissions VARCHAR(4000) NOT NULL, created_by VARCHAR(80), created TIMESTAMP NOT NULL)",
        "CREATE TABLE api_keys (id VARCHAR(40) PRIMARY KEY, user_id VARCHAR(40) NOT NULL, name VARCHAR(120) NOT NULL, key_hash VARCHAR(64) NOT NULL UNIQUE, " +
            "created TIMESTAMP NOT NULL, expires TIMESTAMP, last_used TIMESTAMP)",
        "CREATE TABLE device_pools (id VARCHAR(40) PRIMARY KEY, name VARCHAR(120) NOT NULL UNIQUE, description VARCHAR(500), devices VARCHAR(4000) NOT NULL, created_by VARCHAR(80), created TIMESTAMP NOT NULL)",
        "CREATE TABLE queues (id VARCHAR(40) PRIMARY KEY, name VARCHAR(120) NOT NULL UNIQUE, description VARCHAR(1000), bot_path VARCHAR(400), pool_id VARCHAR(40), " +
            "sla_minutes INT, max_attempts INT NOT NULL, processing BOOLEAN NOT NULL, run_as VARCHAR(80), created_by VARCHAR(80), created TIMESTAMP NOT NULL)",
        "CREATE TABLE queue_items (id VARCHAR(40) PRIMARY KEY, queue_id VARCHAR(40) NOT NULL, reference VARCHAR(200), data VARCHAR(100000) NOT NULL, status VARCHAR(20) NOT NULL, " +
            "priority INT NOT NULL, attempts INT NOT NULL, run_id VARCHAR(40), device_id VARCHAR(40), error VARCHAR(10000), result VARCHAR(100000), " +
            "created TIMESTAMP NOT NULL, updated TIMESTAMP NOT NULL, finished TIMESTAMP)",
        "CREATE INDEX queue_items_next ON queue_items (queue_id, status, priority, created)",
        "CREATE TABLE model_connections (name VARCHAR(60) PRIMARY KEY, provider VARCHAR(40) NOT NULL, model VARCHAR(200), base_url VARCHAR(500), key_ref VARCHAR(300), " +
            "description VARCHAR(500), updated TIMESTAMP NOT NULL, updated_by VARCHAR(80))",
        "CREATE TABLE holiday_calendars (name VARCHAR(80) PRIMARY KEY, dates VARCHAR(20000) NOT NULL, updated TIMESTAMP NOT NULL, updated_by VARCHAR(80))",
        "CREATE TABLE toolkit_settings (id VARCHAR(120) PRIMARY KEY, enabled BOOLEAN NOT NULL, updated TIMESTAMP NOT NULL, updated_by VARCHAR(80))",
        "ALTER TABLE runs ADD COLUMN callback_url VARCHAR(1000)",
        "ALTER TABLE runs ADD COLUMN priority INT",
        "ALTER TABLE runs ADD COLUMN queue_item_id VARCHAR(40)",
        "ALTER TABLE schedules ADD COLUMN holiday_calendar VARCHAR(80)",
        "ALTER TABLE schedules ADD COLUMN pool_id VARCHAR(40)",
        "ALTER TABLE schedules ALTER COLUMN device_id DROP NOT NULL",
        "ALTER TABLE triggers ADD COLUMN config VARCHAR(10000)",
        "ALTER TABLE triggers ADD COLUMN last_fired TIMESTAMP",
        "ALTER TABLE triggers ADD COLUMN fire_count INT",
        "ALTER TABLE triggers ADD COLUMN last_error VARCHAR(2000)",
        "ALTER TABLE triggers ADD COLUMN last_hash VARCHAR(64)",
        "ALTER TABLE bots ADD COLUMN minutes_saved INT",
        "ALTER TABLE users ADD COLUMN email VARCHAR(200)",
    };

    /** Creates or upgrades the schema; versions are recorded so upgrades run once and in order. */
    public void migrate() {
        tx(() -> {
            withConn(c -> { try (Statement s = c.createStatement()) { s.execute("CREATE TABLE IF NOT EXISTS schema_version (version INT NOT NULL)"); } return null; });
            Map<String, Object> v = one("SELECT MAX(version) AS v FROM schema_version");
            int current = v == null || v.get("v") == null ? 0 : ((Number) v.get("v")).intValue();
            if (current < 1) {
                for (String ddl : SCHEMA_V1) withConn(c -> { try (Statement s = c.createStatement()) { s.execute(ddl); } return null; });
                update("INSERT INTO schema_version (version) VALUES (1)");
            }
            if (current < 2) {
                for (String ddl : SCHEMA_V2) withConn(c -> { try (Statement s = c.createStatement()) { s.execute(ddl); } return null; });
                update("INSERT INTO schema_version (version) VALUES (2)");
            }
            return null;
        });
    }

    @Override public synchronized void close() {
        for (Connection c : pool) try { c.close(); } catch (SQLException ignored) { }
        pool.clear();
        if (url.startsWith("jdbc:h2:file:")) try (Connection c = open(); Statement s = c.createStatement()) { s.execute("SHUTDOWN"); } catch (SQLException ignored) { }
    }
}
