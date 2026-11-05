package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.JsonLite;

import java.io.*;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * ODBC for the Database toolkit: a JDBC driver for {@code jdbc:odbc:<connection string or DSN=name>} addresses (the
 * classic JDBC-ODBC bridge form, which Java dropped in version 8). Each session talks to a small helper on Windows
 * (.NET's System.Data.Odbc in PowerShell) over JSON lines, so every ODBC driver on the runner works: DSNs, Excel and
 * Access through the Office drivers, and old databases that only have ODBC. 32-bit drivers (Office 2007, Jet) run in
 * the 32-bit helper: chosen with the property bitness=32, or tried automatically when the 64-bit side has no such
 * driver. Statements, ? parameters, transactions, batches and the table / column lists are supported; stored procedure
 * OUT parameters are not (ODBC returns them only as result sets).
 */
final class OdbcBridge {
    private OdbcBridge() {}

    static final String PREFIX = "jdbc:odbc:";
    /** Test hook: the helper command (a JSON-lines program speaking the same protocol). */
    static volatile List<String> helperCommand;

    private static volatile boolean registered;

    static synchronized void register() {
        if (registered) return;
        try { DriverManager.registerDriver(new Drv()); registered = true; }
        catch (SQLException e) { throw new IllegalStateException(e); }
    }

    static final class Drv implements Driver {
        @Override public Connection connect(String url, Properties info) throws SQLException { return acceptsURL(url) ? open(url.substring(PREFIX.length()), info) : null; }
        @Override public boolean acceptsURL(String url) { return url != null && url.regionMatches(true, 0, PREFIX, 0, PREFIX.length()); }
        @Override public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) { return new DriverPropertyInfo[0]; }
        @Override public int getMajorVersion() { return 1; }
        @Override public int getMinorVersion() { return 0; }
        @Override public boolean jdbcCompliant() { return false; }
        @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
    }

    // ---------------------------------------------------------------- the helper process

    static final String SCRIPT = """
            $ErrorActionPreference = 'Stop'
            Add-Type -AssemblyName System.Data
            $inv = [Globalization.CultureInfo]::InvariantCulture
            $conn = $null; $tx = $null
            function Val($v) {
              if ($v -eq $null) { return [DBNull]::Value }
              if ($v -is [System.Management.Automation.PSCustomObject]) {
                if ($v.t -eq 'datetime') { return [DateTime]::Parse($v.v, $inv) }
                if ($v.t -eq 'bytes') { return ,[Convert]::FromBase64String($v.v) }
                if ($v.t -eq 'decimal') { return [decimal]::Parse($v.v, $inv) }
              }
              return $v
            }
            function Cmd($r, $params) {
              $c = $conn.CreateCommand(); $c.CommandText = $r.sql
              if ($tx -ne $null) { $c.Transaction = $tx }
              if ($r.timeout) { $c.CommandTimeout = [int]$r.timeout }
              if ($params) { $i = 0; foreach ($p in $params) { $i++; $null = $c.Parameters.AddWithValue('p' + $i, (Val $p)) } }
              return $c
            }
            function Cell($v) {
              if ($v -is [DBNull]) { return $null }
              if ($v -is [DateTime]) { return $v.ToString('yyyy-MM-ddTHH:mm:ss.fff', $inv) }
              if ($v -is [byte[]]) { return [Convert]::ToBase64String($v) }
              if ($v -is [decimal] -or $v -is [double] -or $v -is [single]) { return $v.ToString($inv) }
              if ($v -is [TimeSpan] -or $v -is [Guid]) { return $v.ToString() }
              return $v
            }
            function Rows($rd, $max) {
              $cols = New-Object System.Collections.ArrayList
              for ($i = 0; $i -lt $rd.FieldCount; $i++) { $null = $cols.Add(@{ name = $rd.GetName($i); type = $rd.GetFieldType($i).Name; db = $rd.GetDataTypeName($i) }) }
              $rows = New-Object System.Collections.ArrayList
              while ($rd.Read()) {
                if ($max -gt 0 -and $rows.Count -ge $max) { break }
                $row = New-Object System.Collections.ArrayList
                for ($i = 0; $i -lt $rd.FieldCount; $i++) { $null = $row.Add((Cell $rd.GetValue($i))) }
                $null = $rows.Add($row)
              }
              return @{ ok = $true; kind = 'rows'; columns = $cols; rows = $rows }
            }
            function Table($t, $names) {
              $rows = New-Object System.Collections.ArrayList
              foreach ($dr in $t.Rows) { $row = New-Object System.Collections.ArrayList; foreach ($n in $names) { $null = $row.Add((Cell $dr[$n])) }; $null = $rows.Add($row) }
              $cols = New-Object System.Collections.ArrayList
              foreach ($n in $names) { $null = $cols.Add(@{ name = $n; type = 'String'; db = 'VARCHAR' }) }
              return @{ ok = $true; kind = 'rows'; columns = $cols; rows = $rows }
            }
            while ($true) {
              $line = [Console]::In.ReadLine()
              if ($line -eq $null) { break }
              $stop = $false
              try {
                $r = $line | ConvertFrom-Json
                switch ($r.op) {
                  'open' { $conn = New-Object System.Data.Odbc.OdbcConnection($r.cs); if ($r.timeout) { $conn.ConnectionTimeout = [int]$r.timeout }; $conn.Open()
                           $resp = @{ ok = $true; driver = $conn.Driver; version = $conn.ServerVersion; database = $conn.Database; bits = [IntPtr]::Size * 8 } }
                  'query' { $c = Cmd $r $r.params; $rd = $c.ExecuteReader(); try { $resp = Rows $rd ([int]$r.max) } finally { $rd.Close() } }
                  'run' { $c = Cmd $r $r.params; $rd = $c.ExecuteReader()
                          try { if ($rd.FieldCount -gt 0) { $resp = Rows $rd ([int]$r.max) } else { $resp = @{ ok = $true; kind = 'count'; count = $rd.RecordsAffected } } } finally { $rd.Close() } }
                  'exec' { $c = Cmd $r $r.params; $resp = @{ ok = $true; kind = 'count'; count = $c.ExecuteNonQuery() } }
                  'batch' { $counts = New-Object System.Collections.ArrayList; foreach ($ps in $r.rows) { $c = Cmd $r $ps; $null = $counts.Add($c.ExecuteNonQuery()) }; $resp = @{ ok = $true; counts = $counts } }
                  'begin' { $tx = $conn.BeginTransaction(); $resp = @{ ok = $true } }
                  'commit' { if ($tx -ne $null) { $tx.Commit(); $tx = $null }; $resp = @{ ok = $true } }
                  'rollback' { if ($tx -ne $null) { $tx.Rollback(); $tx = $null }; $resp = @{ ok = $true } }
                  'tables' { $resp = Table ($conn.GetSchema('Tables')) @('TABLE_CAT', 'TABLE_SCHEM', 'TABLE_NAME', 'TABLE_TYPE') }
                  'columns' { $resp = Table ($conn.GetSchema('Columns', [string[]]@($null, $null, $r.table))) @('TABLE_NAME', 'COLUMN_NAME', 'TYPE_NAME', 'COLUMN_SIZE', 'NULLABLE', 'ORDINAL_POSITION') }
                  'close' { if ($tx -ne $null) { try { $tx.Rollback() } catch { } }; if ($conn -ne $null) { $conn.Close() }; $resp = @{ ok = $true }; $stop = $true }
                  default { $resp = @{ ok = $false; error = 'unknown operation ' + $r.op } }
                }
              } catch {
                $e = $_.Exception; while ($e.InnerException -ne $null) { $e = $e.InnerException }
                $resp = @{ ok = $false; error = $e.Message }
              }
              [Console]::Out.WriteLine('@@' + ($resp | ConvertTo-Json -Compress -Depth 6)); [Console]::Out.Flush()
              if ($stop) { break }
            }
            """;

    /** One ODBC connection held by a helper process. */
    static final class Helper implements Closeable {
        final Process process; final BufferedWriter in; final BufferedReader out; final Path script; final int bits;
        Helper(Process p, Path script, int bits) {
            process = p; this.script = script; this.bits = bits;
            in = new BufferedWriter(new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8));
            out = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
        }

        @SuppressWarnings("unchecked")
        synchronized Map<String, Object> call(Map<String, Object> req) throws SQLException {
            try {
                in.write(JsonLite.write(req)); in.write('\n'); in.flush();
                String line;
                while ((line = out.readLine()) != null) {
                    if (line.startsWith("@@")) {
                        Map<String, Object> r = (Map<String, Object>) JsonLite.parse(line.substring(2));
                        if (!Boolean.TRUE.equals(r.get("ok"))) throw new SQLException(String.valueOf(r.get("error")), sqlState(String.valueOf(r.get("error"))));
                        return r;
                    }
                }
                throw new SQLException("The ODBC helper stopped" + stderr(), "08S01");
            } catch (IOException e) { throw new SQLException("The ODBC helper stopped: " + e.getMessage() + stderr(), "08S01"); }
        }

        private String stderr() {
            try { byte[] b = process.getErrorStream().readNBytes(Math.min(4000, process.getErrorStream().available())); return b.length == 0 ? "" : ": " + new String(b, StandardCharsets.UTF_8).strip(); }
            catch (IOException e) { return ""; }
        }

        @Override public void close() {
            try { if (process.isAlive()) call(Map.of("op", "close")); } catch (SQLException ignored) { }
            try { in.close(); } catch (IOException ignored) { }
            try { if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly(); } catch (InterruptedException e) { process.destroyForcibly(); Thread.currentThread().interrupt(); }
            if (script != null) try { Files.deleteIfExists(script); } catch (IOException ignored) { }
        }
    }

    static String sqlState(String message) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("ERROR \\[([0-9A-Z]{5})\\]").matcher(message);
        return m.find() ? m.group(1) : "HY000";
    }

    static Helper start(int bits) throws SQLException {
        Path script = null;
        try {
            List<String> cmd;
            if (helperCommand != null) cmd = helperCommand;
            else {
                if (!ProcessRunner.WINDOWS) throw new SQLException("ODBC connections need a Windows runner (the ODBC drivers are Windows drivers)", "IM001");
                script = Files.createTempFile("ooa-odbc-", ".ps1");
                Files.writeString(script, "\uFEFF" + ScriptPackage.UTF8_PREAMBLE + "\n" + SCRIPT, StandardCharsets.UTF_8);
                String root = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
                String ps = root + (bits == 32 ? "\\SysWOW64" : "\\System32") + "\\WindowsPowerShell\\v1.0\\powershell.exe";
                cmd = List.of(ps, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", script.toString());
            }
            Process p = new ProcessBuilder(cmd).start();
            return new Helper(p, script, bits);
        } catch (IOException e) {
            if (script != null) try { Files.deleteIfExists(script); } catch (IOException ignored) { }
            throw new SQLException("Cannot start the ODBC helper: " + e.getMessage(), "IM001");
        }
    }

    /** Alternative driver names to try when one is not installed (Office's ACE drivers and Windows' own Jet drivers). */
    static final Map<String, String> ALTERNATES = Map.of(
            "Microsoft Excel Driver (*.xls, *.xlsx, *.xlsm, *.xlsb)", "Microsoft Excel Driver (*.xls)",
            "Microsoft Access Driver (*.mdb, *.accdb)", "Microsoft Access Driver (*.mdb)");

    static Connection open(String connectionString, Properties info) throws SQLException {
        String cs = connectionString.strip();
        if (cs.isEmpty()) throw new SQLException("Give a DSN or an ODBC connection string after jdbc:odbc:", "IM002");
        if (!cs.contains("=")) cs = "DSN=" + cs;
        String user = info.getProperty("user"), pw = info.getProperty("password");
        if (user != null && !user.isBlank() && !cs.toUpperCase(Locale.ROOT).matches("(?s).*(^|;)\\s*UID\\s*=.*")) cs += ";UID=" + brace(user);
        if (pw != null && !pw.isEmpty() && !cs.toUpperCase(Locale.ROOT).matches("(?s).*(^|;)\\s*PWD\\s*=.*")) cs += ";PWD=" + brace(pw);
        String bitness = info.getProperty("bitness", "").strip();
        List<Integer> sides = bitness.equals("32") ? List.of(32) : bitness.equals("64") ? List.of(64) : List.of(64, 32);
        List<String> strings = new ArrayList<>(List.of(cs));
        for (Map.Entry<String, String> a : ALTERNATES.entrySet()) if (cs.contains("{" + a.getKey() + "}")) strings.add(cs.replace("{" + a.getKey() + "}", "{" + a.getValue() + "}"));
        int timeout = Integer.parseInt(info.getProperty("login_timeout", "0"));
        SQLException last = null;
        for (int bits : sides) for (String s : strings) {
            Helper h = start(bits);
            try {
                Map<String, Object> r = h.call(Map.of("op", "open", "cs", s, "timeout", timeout));
                return connection(h, r);
            } catch (SQLException e) {
                h.close();
                last = e;
                // IM002: no such data source / driver on this side; IM014: 32/64-bit mismatch; try the next side or name
                if (!e.getSQLState().equals("IM002") && !e.getSQLState().equals("IM014")) throw e;
            }
        }
        throw new SQLException(last.getMessage() + " (tried " + (sides.size() > 1 ? "64-bit and 32-bit" : sides.get(0) + "-bit") + " ODBC; the drivers installed are in Windows' ODBC Data Sources tool)", last.getSQLState());
    }

    static String brace(String v) { return v.matches("[^;{}=\\s]*") ? v : "{" + v.replace("}", "}}") + "}"; }

    // ---------------------------------------------------------------- JDBC objects as proxies over the helper

    static Connection connection(Helper h, Map<String, Object> info) {
        boolean[] auto = {true}, closed = {false};
        return (Connection) Proxy.newProxyInstance(OdbcBridge.class.getClassLoader(), new Class<?>[]{Connection.class}, (p, m, a) -> switch (m.getName()) {
            case "prepareStatement", "prepareCall" -> statement(h, (Connection) p, (String) a[0], m.getName().equals("prepareCall"));
            case "createStatement" -> statement(h, (Connection) p, null, false);
            case "setAutoCommit" -> {
                boolean b = (Boolean) a[0];
                if (!b && auto[0]) h.call(Map.of("op", "begin"));
                if (b && !auto[0]) h.call(Map.of("op", "commit"));
                auto[0] = b; yield null;
            }
            case "getAutoCommit" -> auto[0];
            case "commit", "rollback" -> {
                if (auto[0]) throw new SQLException("The connection is in auto-commit mode", "25000");
                h.call(Map.of("op", m.getName())); h.call(Map.of("op", "begin")); yield null;
            }
            case "setReadOnly", "setTransactionIsolation", "clearWarnings", "setCatalog", "setSchema", "setHoldability", "setNetworkTimeout", "setClientInfo" -> null;
            case "isReadOnly" -> false;
            case "isClosed" -> closed[0];
            case "isValid" -> !closed[0] && h.process.isAlive();
            case "close", "abort" -> { if (!closed[0]) { closed[0] = true; h.close(); } yield null; }
            case "getMetaData" -> metaData(h, (Connection) p, info);
            case "getWarnings" -> null;
            case "getCatalog" -> info.get("database");
            case "getTransactionIsolation" -> Connection.TRANSACTION_READ_COMMITTED;
            case "nativeSQL" -> a[0];
            case "unwrap" -> { if (((Class<?>) a[0]).isInstance(p)) yield p; throw new SQLException("Not a wrapper"); }
            case "isWrapperFor" -> ((Class<?>) a[0]).isInstance(p);
            default -> common(p, m, a, "ODBC connection");
        });
    }

    static Object common(Object p, Method m, Object[] a, String what) throws SQLException {
        return switch (m.getName()) {
            case "hashCode" -> System.identityHashCode(p);
            case "equals" -> p == a[0];
            case "toString" -> what;
            default -> throw new SQLFeatureNotSupportedException(what + " does not support " + m.getName());
        };
    }

    /** A statement: parameters are collected, then sent with the SQL; results come back in full (up to max rows). */
    static Object statement(Helper h, Connection conn, String sql, boolean call) {
        List<Object> params = new ArrayList<>();
        List<List<Object>> batch = new ArrayList<>();
        int[] max = {0}, timeout = {0};
        Object[] result = {null};   // ResultSet or Integer count
        boolean[] closed = {false};
        Class<?> type = call ? CallableStatement.class : PreparedStatement.class;
        InvocationHandler hd = (p, m, a) -> {
            String n = m.getName();
            if (n.startsWith("set") && a != null && a.length >= 2 && a[0] instanceof Integer i && !n.equals("setMaxRows") && !n.equals("setQueryTimeout") && !n.equals("setFetchSize")) {
                while (params.size() < i) params.add(null);
                params.set(i - 1, n.equals("setNull") ? null : param(a[1]));
                return null;
            }
            return switch (n) {
                case "setMaxRows" -> { max[0] = (Integer) a[0]; yield null; }
                case "setQueryTimeout" -> { timeout[0] = (Integer) a[0]; yield null; }
                case "setFetchSize", "setEscapeProcessing", "setPoolable", "clearWarnings", "setCursorName", "setFetchDirection", "closeOnCompletion" -> null;
                case "clearParameters" -> { params.clear(); yield null; }
                case "executeQuery" -> { Map<String, Object> r = h.call(req("query", a != null && a.length > 0 ? (String) a[0] : sql, params, max[0], timeout[0])); yield resultSet(r); }
                case "executeUpdate", "executeLargeUpdate" -> {
                    Map<String, Object> r = h.call(req("exec", a != null && a.length > 0 ? (String) a[0] : sql, params, 0, timeout[0]));
                    int c = ((Number) r.get("count")).intValue(); yield n.equals("executeLargeUpdate") ? (Object) (long) c : (Object) c;
                }
                case "execute" -> {
                    Map<String, Object> r = h.call(req("run", a != null && a.length > 0 ? (String) a[0] : sql, params, max[0], timeout[0]));
                    result[0] = "rows".equals(r.get("kind")) ? resultSet(r) : (Object) ((Number) r.get("count")).intValue();
                    yield "rows".equals(r.get("kind"));
                }
                case "getResultSet" -> result[0] instanceof ResultSet rs ? rs : null;
                case "getUpdateCount" -> result[0] instanceof Integer c ? c : -1;
                case "getLargeUpdateCount" -> result[0] instanceof Integer c ? (long) c : -1L;
                case "getMoreResults" -> { result[0] = null; yield false; }
                case "addBatch" -> { if (a != null && a.length == 1) throw new SQLFeatureNotSupportedException("ODBC batches use one statement with parameters"); batch.add(new ArrayList<>(params)); params.clear(); yield null; }
                case "clearBatch" -> { batch.clear(); yield null; }
                case "executeBatch" -> {
                    Map<String, Object> r = h.call(Map.of("op", "batch", "sql", sql, "rows", new ArrayList<>(batch), "timeout", timeout[0]));
                    batch.clear();
                    List<?> counts = (List<?>) r.get("counts");
                    int[] out = new int[counts.size()];
                    for (int i = 0; i < out.length; i++) out[i] = ((Number) counts.get(i)).intValue();
                    yield out;
                }
                case "registerOutParameter" -> throw new SQLFeatureNotSupportedException("ODBC sessions do not return OUT parameters; select them in the procedure instead");
                case "getConnection" -> conn;
                case "getMaxRows" -> max[0];
                case "getQueryTimeout" -> timeout[0];
                case "getWarnings" -> null;
                case "close" -> { closed[0] = true; yield null; }
                case "isClosed" -> closed[0];
                case "unwrap" -> { if (((Class<?>) a[0]).isInstance(p)) yield p; throw new SQLException("Not a wrapper"); }
                case "isWrapperFor" -> ((Class<?>) a[0]).isInstance(p);
                default -> common(p, m, a, "ODBC statement");
            };
        };
        return Proxy.newProxyInstance(OdbcBridge.class.getClassLoader(), new Class<?>[]{type}, hd);
    }

    static Map<String, Object> req(String op, String sql, List<Object> params, int max, int timeout) throws SQLException {
        if (sql == null) throw new SQLException("No SQL for this statement", "HY000");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("op", op); m.put("sql", sql); m.put("params", new ArrayList<>(params)); m.put("max", max); m.put("timeout", timeout);
        return m;
    }

    /** A JDBC parameter as JSON for the helper: dates and decimals are tagged so .NET gets the right type. */
    static Object param(Object v) {
        if (v == null) return null;
        if (v instanceof Timestamp t) return Map.of("t", "datetime", "v", t.toLocalDateTime().toString());
        if (v instanceof java.sql.Date d) return Map.of("t", "datetime", "v", d.toLocalDate().toString());
        if (v instanceof LocalDateTime t) return Map.of("t", "datetime", "v", t.toString());
        if (v instanceof LocalDate d) return Map.of("t", "datetime", "v", d.toString());
        if (v instanceof BigDecimal b) return b.scale() > 0 ? Map.of("t", "decimal", "v", b.toPlainString()) : b;
        if (v instanceof byte[] b) return Map.of("t", "bytes", "v", Base64.getEncoder().encodeToString(b));
        if (v instanceof Number || v instanceof Boolean || v instanceof String) return v;
        return String.valueOf(v);
    }

    static final Map<String, Integer> TYPES = Map.ofEntries(Map.entry("Int16", Types.SMALLINT), Map.entry("Int32", Types.INTEGER), Map.entry("Int64", Types.BIGINT),
            Map.entry("Byte", Types.TINYINT), Map.entry("Decimal", Types.DECIMAL), Map.entry("Double", Types.DOUBLE), Map.entry("Single", Types.REAL),
            Map.entry("Boolean", Types.BOOLEAN), Map.entry("DateTime", Types.TIMESTAMP), Map.entry("Byte[]", Types.VARBINARY), Map.entry("String", Types.VARCHAR));

    /** A Java value for a cell, by the .NET type of its column. */
    static Object value(Object v, String type) {
        if (v == null) return null;
        String s = String.valueOf(v);
        try {
            return switch (type) {
                case "Int16", "Int32", "Byte" -> Integer.valueOf(new BigDecimal(s).intValueExact());
                case "Int64" -> Long.valueOf(new BigDecimal(s).longValueExact());
                case "Decimal" -> new BigDecimal(s);
                case "Double", "Single" -> Double.valueOf(s);
                case "Boolean" -> v instanceof Boolean b ? b : Boolean.valueOf(s);
                case "DateTime" -> Timestamp.valueOf(LocalDateTime.parse(s));
                case "Byte[]" -> Base64.getDecoder().decode(s);
                default -> v instanceof BigDecimal b ? b : s;
            };
        } catch (RuntimeException e) { return s; }
    }

    @SuppressWarnings("unchecked")
    static ResultSet resultSet(Map<String, Object> r) {
        List<Map<String, Object>> cols = (List<Map<String, Object>>) r.get("columns");
        List<List<Object>> rows = (List<List<Object>>) r.get("rows");
        int[] at = {-1};
        boolean[] wasNull = {false}, closed = {false};
        ResultSetMetaData md = (ResultSetMetaData) Proxy.newProxyInstance(OdbcBridge.class.getClassLoader(), new Class<?>[]{ResultSetMetaData.class}, (p, m, a) -> switch (m.getName()) {
            case "getColumnCount" -> cols.size();
            case "getColumnLabel", "getColumnName" -> String.valueOf(cols.get((Integer) a[0] - 1).get("name"));
            case "getColumnType" -> TYPES.getOrDefault(String.valueOf(cols.get((Integer) a[0] - 1).get("type")), Types.VARCHAR);
            case "getColumnTypeName" -> String.valueOf(cols.get((Integer) a[0] - 1).get("db"));
            case "getColumnClassName" -> String.valueOf(cols.get((Integer) a[0] - 1).get("type"));
            case "isNullable" -> ResultSetMetaData.columnNullableUnknown;
            case "getTableName", "getSchemaName", "getCatalogName" -> "";
            case "getPrecision", "getScale", "getColumnDisplaySize" -> 0;
            default -> common(p, m, a, "ODBC result columns");
        });
        return (ResultSet) Proxy.newProxyInstance(OdbcBridge.class.getClassLoader(), new Class<?>[]{ResultSet.class}, (p, m, a) -> {
            String n = m.getName();
            if (n.startsWith("get") && a != null && a.length >= 1 && (a[0] instanceof Integer || a[0] instanceof String) && !n.equals("getMetaData")) {
                int i = a[0] instanceof Integer x ? x : find(cols, (String) a[0]);
                if (at[0] < 0 || at[0] >= rows.size()) throw new SQLException("No current row", "24000");
                Object v = value(rows.get(at[0]).get(i - 1), String.valueOf(cols.get(i - 1).get("type")));
                wasNull[0] = v == null;
                return convert(v, n, m.getReturnType());
            }
            return switch (n) {
                case "next" -> ++at[0] < rows.size();
                case "getMetaData" -> md;
                case "wasNull" -> wasNull[0];
                case "findColumn" -> find(cols, (String) a[0]);
                case "close" -> { closed[0] = true; yield null; }
                case "isClosed" -> closed[0];
                case "getRow" -> at[0] + 1;
                case "isBeforeFirst" -> at[0] < 0 && !rows.isEmpty();
                case "isAfterLast" -> at[0] >= rows.size() && !rows.isEmpty();
                case "getWarnings" -> null;
                case "clearWarnings", "setFetchSize" -> null;
                case "getStatement" -> null;
                case "unwrap" -> { if (((Class<?>) a[0]).isInstance(p)) yield p; throw new SQLException("Not a wrapper"); }
                case "isWrapperFor" -> ((Class<?>) a[0]).isInstance(p);
                default -> common(p, m, a, "ODBC result");
            };
        });
    }

    static int find(List<Map<String, Object>> cols, String name) throws SQLException {
        for (int i = 0; i < cols.size(); i++) if (String.valueOf(cols.get(i).get("name")).equalsIgnoreCase(name)) return i + 1;
        throw new SQLException("No column " + name, "42S22");
    }

    /** The cell as the getter asks: getString gives text, getInt a number, getObject the value as it is. */
    static Object convert(Object v, String getter, Class<?> ret) throws SQLException {
        if (getter.equals("getObject")) return v;
        if (v == null) return ret.isPrimitive() ? (ret == boolean.class ? (Object) false : ret == double.class ? (Object) 0.0 : ret == float.class ? (Object) 0f : ret == long.class ? (Object) 0L : (Object) 0) : null;
        if (ret == String.class) return v instanceof Timestamp t ? t.toLocalDateTime().toString() : v instanceof byte[] b ? new String(b, StandardCharsets.UTF_8) : String.valueOf(v);
        if (ret == int.class) return new BigDecimal(String.valueOf(v)).intValue();
        if (ret == long.class) return new BigDecimal(String.valueOf(v)).longValue();
        if (ret == double.class) return Double.valueOf(String.valueOf(v));
        if (ret == float.class) return Float.valueOf(String.valueOf(v));
        if (ret == boolean.class) return v instanceof Boolean b ? b : !String.valueOf(v).equals("0") && !String.valueOf(v).equalsIgnoreCase("false");
        if (ret == BigDecimal.class) return new BigDecimal(String.valueOf(v));
        if (ret == Timestamp.class) return v instanceof Timestamp t ? t : Timestamp.valueOf(LocalDateTime.parse(String.valueOf(v)));
        if (ret == byte[].class) return v instanceof byte[] b ? b : String.valueOf(v).getBytes(StandardCharsets.UTF_8);
        if (ret.isInstance(v)) return v;
        throw new SQLFeatureNotSupportedException("ODBC results do not support " + getter);
    }

    /**
     * The table list filtered by type as JDBC does. Excel's sheets are SYSTEM TABLEs named Sheet1$ in ODBC: they count as
     * tables, since they are what an agent reads.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> tables(Map<String, Object> r, Set<String> types) {
        List<Object> cols = (List<Object>) r.get("columns");
        int iType = -1, iName = -1;
        for (int i = 0; i < cols.size(); i++) {
            String n = String.valueOf(((Map<String, Object>) cols.get(i)).get("name"));
            if (n.equals("TABLE_TYPE")) iType = i; else if (n.equals("TABLE_NAME")) iName = i;
        }
        List<Object> kept = new ArrayList<>();
        for (Object o : (List<Object>) r.get("rows")) {
            List<Object> row = new ArrayList<>((List<Object>) o);
            String type = String.valueOf(row.get(iType)), name = String.valueOf(row.get(iName));
            if (type.equals("SYSTEM TABLE") && name.endsWith("$")) { row.set(iType, "TABLE"); type = "TABLE"; }
            if (types == null || types.contains(type)) kept.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>(r);
        out.put("rows", kept);
        return out;
    }

    /** Tables and columns for List tables / Describe table, in the column names JDBC uses. */
    static DatabaseMetaData metaData(Helper h, Connection conn, Map<String, Object> info) {
        return (DatabaseMetaData) Proxy.newProxyInstance(OdbcBridge.class.getClassLoader(), new Class<?>[]{DatabaseMetaData.class}, (p, m, a) -> switch (m.getName()) {
            case "getTables" -> resultSet(tables(h.call(Map.of("op", "tables")), a[3] == null ? null : Set.of((String[]) a[3])));
            case "getColumns" -> resultSet(h.call(Map.of("op", "columns", "table", a[2] == null ? "" : String.valueOf(a[2]).replace("\\", ""))));
            case "getSearchStringEscape" -> "\\";
            case "getDatabaseProductName" -> String.valueOf(info.getOrDefault("driver", "ODBC"));
            case "getDatabaseProductVersion" -> String.valueOf(info.getOrDefault("version", ""));
            case "getDriverName" -> "OOA ODBC bridge (" + info.get("bits") + "-bit)";
            case "getDriverVersion" -> "1.0";
            case "getURL" -> PREFIX + "...";
            case "getConnection" -> conn;
            case "getIdentifierQuoteString" -> "\"";
            case "supportsTransactions" -> true;
            case "isReadOnly" -> false;
            default -> common(p, m, a, "ODBC metadata");
        });
    }
}
