package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** SQL Server translation, the schema script and the database configuration file (no database server needed). */
class DatabaseEnginesTest {

    @Test void statementsAreTranslatedForSqlServer() {
        var s = SqlServerDialect.translate("SELECT id FROM users WHERE username = ? AND enabled = TRUE");
        assertEquals("SELECT id FROM users WHERE username = ? AND enabled = 1", s.sql());
        assertNull(s.order());
        assertEquals("UPDATE runs SET pause_requested = 0 WHERE id = ?", SqlServerDialect.translate("UPDATE runs SET pause_requested = FALSE WHERE id = ?").sql());
        // quoted text is never changed
        assertEquals("SELECT 'TRUE external LIMIT 1' AS x FROM t WHERE a = 1", SqlServerDialect.translate("SELECT 'TRUE external LIMIT 1' AS x FROM t WHERE a = TRUE").sql());
        // LIMIT with ORDER BY becomes OFFSET/FETCH; a placeholder limit and offset swap places
        var page = SqlServerDialect.translate("SELECT * FROM audit WHERE actor = ? ORDER BY seq DESC LIMIT ? OFFSET ?");
        assertEquals("SELECT * FROM audit WHERE actor = ? ORDER BY seq DESC OFFSET ? ROWS FETCH NEXT ? ROWS ONLY", page.sql());
        assertArrayEquals(new Object[] { "ann", 20, 50 }, page.reorder(new Object[] { "ann", 50, 20 }));
        assertEquals("SELECT seq, hash FROM audit ORDER BY seq DESC OFFSET 0 ROWS FETCH NEXT 1 ROWS ONLY", SqlServerDialect.translate("SELECT seq, hash FROM audit ORDER BY seq DESC LIMIT 1").sql());
        // LIMIT without ORDER BY becomes TOP; a placeholder moves to the front
        assertEquals("SELECT TOP (1) id FROM users", SqlServerDialect.translate("SELECT id FROM users LIMIT 1").sql());
        var top = SqlServerDialect.translate("SELECT name FROM t WHERE a = ? LIMIT ?");
        assertEquals("SELECT TOP (?) name FROM t WHERE a = ?", top.sql());
        assertArrayEquals(new Object[] { 5, "x" }, top.reorder(new Object[] { "x", 5 }));
        // two-argument SUBSTRING gets a length; the reserved column name is bracketed, external_secret is not
        assertEquals("UPDATE bots SET path = CONCAT(?, SUBSTRING(path, ?, 2147483647)) WHERE path LIKE ?",
            SqlServerDialect.translate("UPDATE bots SET path = CONCAT(?, SUBSTRING(path, ?)) WHERE path LIKE ?").sql());
        assertEquals("UPDATE lockers SET [external] = NULL, external_secret = NULL WHERE name = ?",
            SqlServerDialect.translate("UPDATE lockers SET external = NULL, external_secret = NULL WHERE name = ?").sql());
    }

    @Test void schemaIsTranslatedForSqlServer() {
        List<String> ss = Db.script("sqlserver");
        assertTrue(ss.get(0).startsWith("IF OBJECT_ID('schema_version', 'U') IS NULL"));
        String users = ss.stream().filter(x -> x.startsWith("CREATE TABLE users ")).findFirst().orElseThrow();
        assertTrue(users.contains("username NVARCHAR(80) NOT NULL UNIQUE") && users.contains("enabled BIT NOT NULL") && users.contains("created DATETIME2 NOT NULL"), users);
        assertTrue(ss.stream().anyMatch(x -> x.contains("content NVARCHAR(MAX) NOT NULL")));
        assertTrue(ss.contains("ALTER TABLE users ADD email NVARCHAR(200)"));
        assertTrue(ss.contains("ALTER TABLE schedules ALTER COLUMN device_id NVARCHAR(40) NULL"));
        assertTrue(ss.contains("ALTER TABLE lockers ADD [external] NVARCHAR(4000)"));
        assertTrue(ss.stream().anyMatch(x -> x.contains("PRIMARY KEY NONCLUSTERED (folder, principal)")));
        assertEquals("INSERT INTO schema_version (version) VALUES (" + Db.LATEST + ")", ss.get(ss.size() - 1));
        // the portable script is unchanged
        assertTrue(Db.script("postgresql").contains("ALTER TABLE users ADD COLUMN email VARCHAR(200)"));
        assertTrue(Db.tables().containsAll(List.of("users", "audit", "credentials", "connections")));
    }

    @Test void configurationFileBuildsTheConnection(@TempDir Path data) throws Exception {
        Path file = DbConfig.defaultFile(data);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "engine=sqlserver\nhost=db01\ninstance=SQLEXPRESS\ndatabase=ooa_cc\nuser=ooa_app\nsetup.user=ooa_setup\ntls=required\ntls.trustAnyCertificate=true\npassword=hunter2\n");
        DbConfig cfg = DbConfig.load(file);
        String url = cfg.url(data, false);
        assertTrue(url.startsWith("jdbc:sqlserver://db01:1433;instanceName=SQLEXPRESS;databaseName=ooa_cc;"), url);
        assertTrue(url.contains("encrypt=true") && url.contains("trustServerCertificate=true") && url.contains("sendTimeAsDatetime=false"), url);
        assertTrue(cfg.url(data, true).contains("databaseName=master"));
        Vault vault = new Vault(data);
        // a clear-text password is refused; an encrypted one round-trips and is bound to its key
        assertEquals("CR.DB_CONFIG", assertThrows(OoaException.class, () -> cfg.password(vault, false)).code());
        DbConfig.storePassword(file, vault, false, "s3cret!");
        DbConfig.storePassword(file, vault, true, "setup-pw");
        DbConfig again = DbConfig.load(file);
        assertTrue(again.get("password").startsWith("enc:v1:"));
        assertEquals("s3cret!", again.password(vault, false));
        assertEquals("setup-pw", again.password(vault, true));
        assertTrue(Files.readString(file).contains("host=db01"), "other lines are kept");
        assertEquals("SQL Server db01\\SQLEXPRESS / ooa_cc (TLS)", again.label());
        Files.writeString(file, "engine=oracle\n");
        assertEquals("CR.DB_CONFIG", assertThrows(OoaException.class, () -> DbConfig.load(file)).code());
    }
}
