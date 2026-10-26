package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.sdk.OoaException;

import java.sql.*;
import java.util.*;

/**
 * JDBC access with a small connection pool and versioned schema migrations. Portable SQL that runs on embedded H2
 * (default, PostgreSQL compatibility mode), on PostgreSQL and on Microsoft SQL Server (translated by
 * {@link SqlServerDialect}). The connection comes from {@code config/ooa-db.properties} ({@link DbConfig}).
 */
public final class Db implements AutoCloseable {
    private final String url, user, password;
    private final int poolMax;
    private final Deque<Connection> pool = new ArrayDeque<>();
    private final ThreadLocal<Connection> txConn = new ThreadLocal<>();
    public final boolean postgres, sqlServer, h2;

    public Db(String url, String user, String password) { this(url, user, password, 10); }

    public Db(String url, String user, String password, int poolMax) {
        this.url = url; this.user = user; this.password = password; this.poolMax = poolMax;
        this.postgres = url.startsWith("jdbc:postgresql:");
        this.sqlServer = url.startsWith("jdbc:sqlserver:");
        this.h2 = url.startsWith("jdbc:h2:");
        try (Connection c = open()) { /* fail fast on a wrong URL or missing driver */ }
        catch (SQLException e) { throw new OoaException("CR.DB_UNAVAILABLE", "Cannot open the database: " + e.getMessage(), "Check the database settings (config\\ooa-db.properties or --db-url), the user and password, and that the database server is running."); }
    }

    /** The database described by a configuration file, signed in as the runtime account (or, with setup, the setup account). */
    public static Db open(DbConfig cfg, java.nio.file.Path dataDir, Vault vault, boolean setup) {
        if (cfg.engine().equals("h2")) return embedded(dataDir);
        if (setup && !cfg.hasSetupAccount()) throw new OoaException("CR.DB_CONFIG", "No setup account in " + cfg.file, "Add setup.user and run: ooa db set-password --setup");
        // Windows sign-in: the process's own account, unless a SQL login is named for setting up the schema
        if (cfg.integrated(setup)) return new Db(cfg.url(dataDir, false, setup), "", "", cfg.poolMax());
        return new Db(cfg.url(dataDir, false, setup), setup ? cfg.get("setup.user") : cfg.get("user"), cfg.password(vault, setup), cfg.poolMax());
    }

    public static Db embedded(java.nio.file.Path dir) {
        String test = System.getProperty("ooa.test.db");
        if (test != null && !test.isBlank()) return testDatabase(test, dir);
        return h2File(dir);
    }

    /** The embedded H2 database file (controlroom.mv.db) of a data folder. */
    public static Db h2File(java.nio.file.Path dir) {
        return new Db("jdbc:h2:file:" + dir.resolve("controlroom").toAbsolutePath() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;AUTO_SERVER=FALSE", "ooa", "");
    }

    /**
     * Test runs only (-Pooa.test.db=jdbc:sqlserver://host:port;encrypt=false, or jdbc:postgresql://host:port/, with
     * ooa.test.db.user and ooa.test.db.password): each data folder gets its own database named after the folder, so the
     * whole test suite runs against SQL Server or PostgreSQL unchanged. The databases are named ooa_t_*.
     */
    private static Db testDatabase(String base, java.nio.file.Path dir) {
        String user = System.getProperty("ooa.test.db.user", ""), pw = System.getProperty("ooa.test.db.password", "");
        String name = "ooa_t_" + Security.sha256(dir.toAbsolutePath().normalize().toString()).substring(0, 20);
        if (base.startsWith("jdbc:postgresql:")) {
            String server = base.endsWith("/") ? base : base + "/";
            try (Connection c = DriverManager.getConnection(server + "postgres", user, pw); Statement s = c.createStatement()) {
                try (ResultSet r = s.executeQuery("SELECT 1 FROM pg_database WHERE datname = '" + name + "'")) {
                    if (!r.next()) s.execute("CREATE DATABASE " + name);
                }
            } catch (SQLException e) { throw new OoaException("CR.DB_UNAVAILABLE", "Cannot create the test database: " + e.getMessage()); }
            return new Db(server + name, user, pw);
        }
        try (Connection c = DriverManager.getConnection(base + ";databaseName=master", user, pw); Statement s = c.createStatement()) {
            s.execute("IF DB_ID('" + name + "') IS NULL BEGIN CREATE DATABASE [" + name + "]; ALTER DATABASE [" + name + "] SET READ_COMMITTED_SNAPSHOT ON; END");
        } catch (SQLException e) { throw new OoaException("CR.DB_UNAVAILABLE", "Cannot create the test database: " + e.getMessage()); }
        return new Db(base + ";databaseName=" + name + ";sendTimeAsDatetime=false", user, pw);
    }

    /** The account this connection signs in with (shown on the console, never the password). */
    public String user() { return user; }

    private Connection open() throws SQLException { return user.isEmpty() && password.isEmpty() && url.contains("integratedSecurity=true") ? DriverManager.getConnection(url) : DriverManager.getConnection(url, user, password); }

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
            if (pool.size() < poolMax) pool.addLast(c); else c.close();
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

    public List<Map<String, Object>> query(String sql0, Object... params0) {
        SqlServerDialect.Statement st = sqlServer ? SqlServerDialect.statement(sql0) : null;
        String sql = st == null ? sql0 : st.sql();
        Object[] params = st == null ? params0 : st.reorder(params0);
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

    public int update(String sql0, Object... params0) {
        SqlServerDialect.Statement st = sqlServer ? SqlServerDialect.statement(sql0) : null;
        String sql = st == null ? sql0 : st.sql();
        Object[] params = st == null ? params0 : st.reorder(params0);
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

    /** Version 3: external vault links, AI usage records, the human task inbox, workflow runs and single sign-on. */
    private static final String[] SCHEMA_V3 = {
        "ALTER TABLE lockers ADD COLUMN external VARCHAR(4000)",
        "ALTER TABLE lockers ADD COLUMN external_secret VARCHAR(8000)",
        "CREATE TABLE ai_calls (id VARCHAR(40) PRIMARY KEY, run_id VARCHAR(40), bot_path VARCHAR(400), requested_by VARCHAR(80), ts TIMESTAMP NOT NULL, purpose VARCHAR(80), " +
            "provider VARCHAR(40), model VARCHAR(200), input_tokens INT, output_tokens INT, latency_ms BIGINT, masked VARCHAR(1000), tool_calls VARCHAR(2000), prompt_sha256 VARCHAR(64))",
        "CREATE INDEX ai_calls_ts ON ai_calls (ts)",
        "CREATE TABLE workflow_runs (id VARCHAR(40) PRIMARY KEY, path VARCHAR(400) NOT NULL, version INT NOT NULL, status VARCHAR(20) NOT NULL, stage VARCHAR(80), " +
            "data VARCHAR(100000) NOT NULL, history VARCHAR(100000) NOT NULL, started_by VARCHAR(80), created TIMESTAMP NOT NULL, updated TIMESTAMP NOT NULL, finished TIMESTAMP, error VARCHAR(4000))",
        "CREATE TABLE human_tasks (id VARCHAR(40) PRIMARY KEY, workflow_run_id VARCHAR(40), stage VARCHAR(80), kind VARCHAR(20) NOT NULL, title VARCHAR(300) NOT NULL, " +
            "description VARCHAR(4000), form VARCHAR(100000), data VARCHAR(100000), assignee VARCHAR(300) NOT NULL, status VARCHAR(20) NOT NULL, claimed_by VARCHAR(80), " +
            "outcome VARCHAR(40), result VARCHAR(100000), created TIMESTAMP NOT NULL, due TIMESTAMP, completed TIMESTAMP, completed_by VARCHAR(80))",
        "CREATE INDEX human_tasks_status ON human_tasks (status, created)",
        "ALTER TABLE users ADD COLUMN sso_subject VARCHAR(300)",
    };

    /**
     * Version 4: repository folders and folder permissions, deleted (recoverable) agents, version labels, run-as users
     * (each user's default runner), run modes, work item models and queue members.
     */
    private static final String[] SCHEMA_V4 = {
        "CREATE TABLE repo_folders (path VARCHAR(400) PRIMARY KEY, description VARCHAR(500), created TIMESTAMP NOT NULL, created_by VARCHAR(80))",
        "CREATE TABLE folder_permissions (folder VARCHAR(400) NOT NULL, principal VARCHAR(120) NOT NULL, access VARCHAR(20) NOT NULL, " +
            "updated TIMESTAMP NOT NULL, updated_by VARCHAR(80), PRIMARY KEY (folder, principal))",
        "ALTER TABLE bots ADD COLUMN deleted_at TIMESTAMP",
        "ALTER TABLE bots ADD COLUMN deleted_by VARCHAR(80)",
        "CREATE TABLE bot_labels (path VARCHAR(400) NOT NULL, label VARCHAR(40) NOT NULL, version INT NOT NULL, created TIMESTAMP NOT NULL, created_by VARCHAR(80), PRIMARY KEY (path, label))",
        "CREATE TABLE user_devices (username VARCHAR(80) PRIMARY KEY, device_id VARCHAR(40) NOT NULL, updated TIMESTAMP NOT NULL, updated_by VARCHAR(80))",
        "ALTER TABLE runs ADD COLUMN run_as VARCHAR(80)",
        "ALTER TABLE runs ADD COLUMN run_mode VARCHAR(20)",
        "CREATE TABLE work_item_models (id VARCHAR(40) PRIMARY KEY, name VARCHAR(120) NOT NULL UNIQUE, description VARCHAR(1000), fields VARCHAR(20000) NOT NULL, " +
            "created TIMESTAMP NOT NULL, created_by VARCHAR(80))",
        "ALTER TABLE queues ADD COLUMN model_id VARCHAR(40)",
        "CREATE TABLE queue_members (queue_id VARCHAR(40) NOT NULL, principal VARCHAR(120) NOT NULL, member_role VARCHAR(20) NOT NULL, " +
            "updated TIMESTAMP NOT NULL, updated_by VARCHAR(80), PRIMARY KEY (queue_id, principal, member_role))",
    };

    /**
     * Version 5: teams, vault folder managers and participants, credential owners and user-provided attributes (each
     * person's own value), and indexes for audit search.
     */
    private static final String[] SCHEMA_V5 = {
        "CREATE TABLE teams (id VARCHAR(40) PRIMARY KEY, name VARCHAR(80) NOT NULL UNIQUE, description VARCHAR(500), created TIMESTAMP NOT NULL, created_by VARCHAR(80))",
        "CREATE TABLE team_members (team_id VARCHAR(40) NOT NULL, username VARCHAR(80) NOT NULL, team_role VARCHAR(20) NOT NULL, PRIMARY KEY (team_id, username))",
        "ALTER TABLE lockers ADD COLUMN managers VARCHAR(1000)",
        "ALTER TABLE lockers ADD COLUMN participants VARCHAR(1000)",
        "CREATE TABLE credential_meta (locker VARCHAR(80) NOT NULL, name VARCHAR(80) NOT NULL, owner VARCHAR(80), description VARCHAR(500), " +
            "user_attributes VARCHAR(1000), created TIMESTAMP NOT NULL, PRIMARY KEY (locker, name))",
        "CREATE TABLE credential_user_values (locker VARCHAR(80) NOT NULL, name VARCHAR(80) NOT NULL, attribute VARCHAR(80) NOT NULL, username VARCHAR(80) NOT NULL, " +
            "ciphertext VARCHAR(20000) NOT NULL, updated TIMESTAMP NOT NULL, PRIMARY KEY (locker, name, attribute, username))",
        "CREATE INDEX audit_ts ON audit (ts)",
        "CREATE INDEX runs_created ON runs (created)",
    };

    /**
     * Version 6: run-as users for schedules and triggers, workflow run references, the code analysis policy and stored
     * reports, and API tasks (an agent behind its own URL and token).
     */
    private static final String[] SCHEMA_V6 = {
        "ALTER TABLE schedules ADD COLUMN run_as VARCHAR(80)",
        "ALTER TABLE triggers ADD COLUMN run_as VARCHAR(80)",
        "ALTER TABLE workflow_runs ADD COLUMN reference VARCHAR(200)",
        "CREATE TABLE code_policy (id INT PRIMARY KEY, json_value VARCHAR(20000) NOT NULL, updated TIMESTAMP NOT NULL, updated_by VARCHAR(80))",
        "CREATE TABLE code_analysis (path VARCHAR(400) NOT NULL, version INT NOT NULL, errors INT NOT NULL, warnings INT NOT NULL, violations VARCHAR(100000) NOT NULL, " +
            "analyzed TIMESTAMP NOT NULL, analyzed_by VARCHAR(80), PRIMARY KEY (path, version))",
        "CREATE TABLE api_tasks (id VARCHAR(40) PRIMARY KEY, name VARCHAR(120) NOT NULL, bot_path VARCHAR(400) NOT NULL, device_id VARCHAR(40), pool_id VARCHAR(40), " +
            "token_hash VARCHAR(64) NOT NULL, timeout_seconds INT NOT NULL, enabled BOOLEAN NOT NULL, created_by VARCHAR(80) NOT NULL, created TIMESTAMP NOT NULL, last_used TIMESTAMP, calls INT NOT NULL)",
    };

    /**
     * Version 7: repository files, credential attribute details (description, masked, password fields only), several
     * roles per user and name details, shared values people may change for themselves, runner log level and type,
     * roles allowed to use a model connection, and Prompt Skills kept in the Command Center.
     */
    private static final String[] SCHEMA_V7 = {
        "CREATE TABLE repo_files (path VARCHAR(400) PRIMARY KEY, size BIGINT NOT NULL, sha256 VARCHAR(64) NOT NULL, content_type VARCHAR(120), created TIMESTAMP NOT NULL, " +
            "updated TIMESTAMP NOT NULL, updated_by VARCHAR(80), version INT NOT NULL)",
        "ALTER TABLE credential_meta ADD COLUMN attributes_info VARCHAR(10000)",
        "CREATE TABLE user_roles (username VARCHAR(80) NOT NULL, role VARCHAR(40) NOT NULL, PRIMARY KEY (username, role))",
        "ALTER TABLE users ADD COLUMN first_name VARCHAR(100)",
        "ALTER TABLE users ADD COLUMN last_name VARCHAR(100)",
        "ALTER TABLE users ADD COLUMN description VARCHAR(500)",
        "ALTER TABLE global_values ADD COLUMN changeable BOOLEAN",
        "CREATE TABLE user_global_values (name VARCHAR(80) NOT NULL, username VARCHAR(80) NOT NULL, json_value VARCHAR(100000) NOT NULL, updated TIMESTAMP NOT NULL, PRIMARY KEY (name, username))",
        "ALTER TABLE devices ADD COLUMN log_level VARCHAR(20)",
        "ALTER TABLE devices ADD COLUMN device_type VARCHAR(20)",
        "ALTER TABLE model_connections ADD COLUMN allowed_roles VARCHAR(1000)",
        "ALTER TABLE model_connections ADD COLUMN model_type VARCHAR(20)",
        "CREATE TABLE prompt_skills (name VARCHAR(80) PRIMARY KEY, description VARCHAR(500), definition VARCHAR(100000) NOT NULL, version INT NOT NULL, " +
            "shared BOOLEAN NOT NULL, template BOOLEAN NOT NULL, created TIMESTAMP NOT NULL, updated TIMESTAMP NOT NULL, updated_by VARCHAR(80), owner VARCHAR(80))",
        "CREATE TABLE prompt_skill_versions (name VARCHAR(80) NOT NULL, version INT NOT NULL, definition VARCHAR(100000) NOT NULL, updated TIMESTAMP NOT NULL, " +
            "updated_by VARCHAR(80), comment VARCHAR(500), PRIMARY KEY (name, version))",
    };

    /** Version 8: saved connections (a connector's address and vault references, chosen by name on steps). */
    private static final String[] SCHEMA_V8 = {
        "CREATE TABLE connections (name VARCHAR(80) PRIMARY KEY, package VARCHAR(120) NOT NULL, description VARCHAR(500), json_values VARCHAR(100000) NOT NULL, " +
            "json_credentials VARCHAR(10000) NOT NULL, updated TIMESTAMP NOT NULL, updated_by VARCHAR(80))",
    };

    /** Version 9: the installed license file and who holds which seat (Licensing). */
    private static final String[] SCHEMA_V9 = {
        "CREATE TABLE licenses (id VARCHAR(80) PRIMARY KEY, content VARCHAR(20000) NOT NULL, customer VARCHAR(200), expires VARCHAR(10) NOT NULL, " +
            "installed TIMESTAMP NOT NULL, installed_by VARCHAR(80) NOT NULL, active BOOLEAN NOT NULL)",
        "CREATE TABLE license_assignments (principal_type VARCHAR(10) NOT NULL, principal VARCHAR(120) NOT NULL, license_type VARCHAR(40) NOT NULL, " +
            "assigned TIMESTAMP NOT NULL, assigned_by VARCHAR(80), PRIMARY KEY (principal_type, principal, license_type))",
    };

    /** Version 10: each runner's screen (unlocked, locked, no desktop) and what it does after unattended runs. */
    private static final String[] SCHEMA_V10 = {
        "ALTER TABLE devices ADD COLUMN session_state VARCHAR(20)",
        "ALTER TABLE devices ADD COLUMN after_run VARCHAR(20)",
    };

    /** Version 11: two-step sign-in (the secret encrypted with the vault key, recovery codes as hashes, the last code used). */
    private static final String[] SCHEMA_V11 = {
        "ALTER TABLE users ADD COLUMN mfa_enabled BOOLEAN",
        "ALTER TABLE users ADD COLUMN mfa_secret VARCHAR(500)",
        "ALTER TABLE users ADD COLUMN mfa_recovery VARCHAR(1000)",
        "ALTER TABLE users ADD COLUMN mfa_last_step BIGINT",
    };

    /** Version 12: requests for a version to go live in production, approved by someone else (ProductionApproval). */
    private static final String[] SCHEMA_V12 = {
        "CREATE TABLE production_requests (id VARCHAR(40) PRIMARY KEY, path VARCHAR(400) NOT NULL, version INT NOT NULL, requested_by VARCHAR(80) NOT NULL, " +
            "requested TIMESTAMP NOT NULL, comment VARCHAR(2000), status VARCHAR(20) NOT NULL, decided_by VARCHAR(80), decided TIMESTAMP, note VARCHAR(2000))",
        "CREATE INDEX production_requests_status ON production_requests (status)",
    };

    /** Version 13: the build of each runner's program files, and an update an administrator asked for (RunnerUpdates). */
    private static final String[] SCHEMA_V13 = {
        "ALTER TABLE devices ADD COLUMN build VARCHAR(40)",
        "ALTER TABLE devices ADD COLUMN update_requested BOOLEAN",
    };

    /** Version 14: temporary runners (VDI) and runner secret rotation (the previous secret stays valid a few minutes). */
    private static final String[] SCHEMA_V14 = {
        "ALTER TABLE registration_keys ADD COLUMN temporary BOOLEAN",
        "ALTER TABLE devices ADD COLUMN lifespan VARCHAR(20)",
        "ALTER TABLE devices ADD COLUMN secret_set TIMESTAMP",
        "ALTER TABLE devices ADD COLUMN secret_prev_hash VARCHAR(64)",
        "ALTER TABLE devices ADD COLUMN secret_prev_until TIMESTAMP",
    };

    /** Version 15: the toolkits each runner has (id@version, comma separated). */
    private static final String[] SCHEMA_V15 = {
        "ALTER TABLE devices ADD COLUMN toolkits VARCHAR(20000)",
    };

    /** Version 16: how each audit entry is chained (null: SHA-256; h1: HMAC-SHA256 with the audit key). */
    private static final String[] SCHEMA_V16 = {
        "ALTER TABLE audit ADD COLUMN alg VARCHAR(8)",
    };

    /** Version 17: password history (recent hashes) and when the password was last changed (expiry). */
    private static final String[] SCHEMA_V17 = {
        "ALTER TABLE users ADD COLUMN password_history VARCHAR(4000)",
        "ALTER TABLE users ADD COLUMN password_changed TIMESTAMP",
    };

    /** Version 18: several nodes (Cluster): the leader lease, the nodes, and sign-ins in progress shared by them. */
    private static final String[] SCHEMA_V18 = {
        "CREATE TABLE cluster_lease (name VARCHAR(40) PRIMARY KEY, holder VARCHAR(120) NOT NULL, expires TIMESTAMP NOT NULL)",
        "CREATE TABLE cluster_nodes (node_id VARCHAR(120) PRIMARY KEY, started TIMESTAMP NOT NULL, last_seen TIMESTAMP NOT NULL, leader_since TIMESTAMP)",
        "CREATE TABLE auth_pending (token VARCHAR(200) PRIMARY KEY, kind VARCHAR(20) NOT NULL, data VARCHAR(2000), expires TIMESTAMP NOT NULL)",
    };

    /** Version 19: licence use per day, for the usage trend (Licensing.usage). */
    private static final String[] SCHEMA_V19 = {
        "CREATE TABLE license_usage (snap_day VARCHAR(10) NOT NULL, license_type VARCHAR(40) NOT NULL, used INT NOT NULL, total INT NOT NULL, PRIMARY KEY (snap_day, license_type))",
    };

    /** The schema version this release needs. */
    public static final int LATEST = 19;
    private static final String[][] SCHEMA = { SCHEMA_V1, SCHEMA_V2, SCHEMA_V3, SCHEMA_V4, SCHEMA_V5, SCHEMA_V6, SCHEMA_V7, SCHEMA_V8, SCHEMA_V9, SCHEMA_V10, SCHEMA_V11, SCHEMA_V12, SCHEMA_V13, SCHEMA_V14, SCHEMA_V15, SCHEMA_V16, SCHEMA_V17, SCHEMA_V18, SCHEMA_V19 };

    /** The schema version recorded in the database (0 when it is empty). */
    public int schemaVersion() {
        try {
            Map<String, Object> v = one("SELECT MAX(version) AS v FROM schema_version");
            return v == null || v.get("v") == null ? 0 : ((Number) v.get("v")).intValue();
        } catch (OoaException e) { return 0; }
    }

    /**
     * Creates or upgrades the schema; versions are recorded so upgrades run once and in order. Nothing runs when the
     * schema is current, so a runtime account with data rights only can start the server. When an upgrade is due and
     * the account may not change tables, the error names the command that does it with the setup account.
     */
    public void migrate() {
        int current = schemaVersion();
        if (current > LATEST) throw new OoaException("CR.DB_NEWER", "The database holds schema version " + current + ", made by a newer release; this release knows version " + LATEST,
            "Start the release that upgraded the database, or restore a backup taken before the upgrade");
        if (current == LATEST) return;
        try {
            tx(() -> {
                if (current == 0) execute(sqlServer ? "IF OBJECT_ID('schema_version', 'U') IS NULL CREATE TABLE schema_version (version INT NOT NULL)"
                    : "CREATE TABLE IF NOT EXISTS schema_version (version INT NOT NULL)");
                for (int v = current + 1; v <= LATEST; v++) {
                    for (String ddl : SCHEMA[v - 1]) execute(ddl(ddl, sqlServer));
                    update("INSERT INTO schema_version (version) VALUES (" + v + ")");
                }
                return null;
            });
        } catch (OoaException e) {
            if (!h2 && e.getMessage() != null && e.getMessage().toLowerCase(Locale.ROOT).contains("permission"))
                throw new OoaException("CR.DB_UPGRADE_NEEDED", "The database schema is at version " + current + " and this release needs " + LATEST
                    + "; the account " + user + " may not change tables", "Run: ooa db migrate (it signs in with the setup account)");
            throw e;
        }
    }

    private void execute(String sql) { withConn(c -> { try (Statement s = c.createStatement()) { s.execute(sql); } return null; }); }

    /** A schema statement for the engine; SQL Server needs its own types and the type of a column made nullable. */
    static String ddl(String ddl, boolean sqlServer) {
        if (!sqlServer) return ddl;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("ALTER TABLE (\\w+) ALTER COLUMN (\\w+) DROP NOT NULL").matcher(ddl);
        if (m.matches()) return SqlServerDialect.ddl("ALTER TABLE " + m.group(1) + " ALTER COLUMN " + m.group(2) + " " + columnType(m.group(1), m.group(2)) + " NULL");
        return SqlServerDialect.ddl(ddl);
    }

    /** The declared type of a column, read from the CREATE TABLE statement of the schema. */
    private static String columnType(String table, String column) {
        for (String[] version : SCHEMA) for (String ddl : version)
            if (ddl.startsWith("CREATE TABLE " + table + " (")) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("[(,] ?" + column + " (\\w+(\\(\\d+\\))?)").matcher(ddl);
                if (m.find()) return m.group(1);
            }
        throw new IllegalStateException("No column " + table + "." + column);
    }

    /** Every schema statement up to the latest version, for a DBA who prefers to run them by hand ("ooa db script"). */
    public static List<String> script(String engine) {
        boolean ss = engine.equals("sqlserver");
        List<String> out = new ArrayList<>();
        out.add(ss ? "IF OBJECT_ID('schema_version', 'U') IS NULL CREATE TABLE schema_version (version INT NOT NULL)" : "CREATE TABLE IF NOT EXISTS schema_version (version INT NOT NULL)");
        for (int v = 1; v <= LATEST; v++) {
            for (String ddl : SCHEMA[v - 1]) out.add(ddl(ddl, ss));
            out.add("INSERT INTO schema_version (version) VALUES (" + v + ")");
        }
        return out;
    }

    /** The tables of the schema, in creation order (for copying data between databases). */
    public static List<String> tables() {
        List<String> out = new ArrayList<>();
        for (String[] version : SCHEMA) for (String ddl : version)
            if (ddl.startsWith("CREATE TABLE ")) out.add(ddl.substring(13, ddl.indexOf(' ', 13)));
        return out;
    }

    /** Runs work with a raw connection of this database (borrowed from the pool, or the open transaction's). */
    public <T> T withConnection(java.util.function.Function<Connection, T> f) { return withConn(c -> f.apply(c)); }

    /** What the console shows about the database: engine and version, database, account, schema, encryption, size. */
    public Map<String, Object> info() {
        Map<String, Object> m = new LinkedHashMap<>();
        withConn(c -> {
            DatabaseMetaData md = c.getMetaData();
            m.put("engine", sqlServer ? "Microsoft SQL Server" : postgres ? "PostgreSQL" : h2 ? "H2 (embedded)" : md.getDatabaseProductName());
            m.put("version", md.getDatabaseProductVersion());
            m.put("driver", md.getDriverName() + " " + md.getDriverVersion());
            m.put("database", c.getCatalog());
            return null;
        });
        m.put("account", h2 ? null : user);
        m.put("schema_version", schemaVersion());
        m.put("schema_latest", LATEST);
        m.put("pool_max", poolMax);
        if (sqlServer) {
            // A data-only account may not read sys.dm_exec_connections, but with encrypt=true the driver refuses any unencrypted session
            Map<String, Object> r = one("SELECT CAST(SERVERPROPERTY('Edition') AS NVARCHAR(128)) AS edition, " +
                "CAST(SERVERPROPERTY('Collation') AS NVARCHAR(128)) AS coll, CAST(@@SERVERNAME AS NVARCHAR(128)) AS server, " +
                "(SELECT SUM(CAST(size AS BIGINT)) * 8 / 1024 FROM sys.database_files) AS size_mb");
            if (r != null) {
                m.put("server", r.get("server")); m.put("edition", r.get("edition")); m.put("collation", r.get("coll"));
                m.put("encrypted", url.contains(";encrypt=true")); m.put("size_mb", r.get("size_mb"));
            }
        } else if (postgres) {
            Map<String, Object> r = one("SELECT pg_database_size(current_database()) / 1048576 AS size_mb, (SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()) AS ssl");
            if (r != null) { m.put("size_mb", r.get("size_mb")); m.put("encrypted", Boolean.TRUE.equals(r.get("ssl"))); }
        } else {
            m.put("encrypted", false);
            m.put("note", "Embedded database for evaluation; use SQL Server or PostgreSQL in production");
        }
        return m;
    }

    @Override public synchronized void close() {
        for (Connection c : pool) try { c.close(); } catch (SQLException ignored) { }
        pool.clear();
        if (url.startsWith("jdbc:h2:file:")) try (Connection c = open(); Statement s = c.createStatement()) { s.execute("SHUTDOWN"); } catch (SQLException ignored) { }
    }
}
