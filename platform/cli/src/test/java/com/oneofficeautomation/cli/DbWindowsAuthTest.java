package com.oneofficeautomation.cli;

import com.oneofficeautomation.controlroom.DbConfig;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Windows sign-in to SQL Server (S8): integrated security for the service account or gMSA, no stored password. */
class DbWindowsAuthTest {

    @Test void configureWritesWindowsSignInAndKeepsBackslashes() throws Exception {
        Path data = Files.createTempDirectory("ooa-winauth");
        Path file = DbConfig.defaultFile(data);
        assertEquals(0, DbCommand.configure(file, Map.of("engine", "sqlserver", "host", "sql01", "windows-auth", "", "windows-login", "CORP\\ooa-svc$")));
        DbConfig cfg = DbConfig.load(file);
        assertTrue(cfg.windowsAuth());
        assertEquals("CORP\\ooa-svc$", cfg.get("windows.login"), "a backslash survives the properties file");
        Path other = data.resolve("other.properties");
        DbCommand.configure(other, Map.of("engine", "sqlserver", "host", "sql01", "user", "ooa_app", "tls-ca", "C:\\certs\\db-ca.crt"));
        assertEquals("C:\\certs\\db-ca.crt", DbConfig.load(other).get("tls.ca"), "a Windows path survives too");
        assertEquals("", cfg.get("user"), "no SQL login needed");

        String runtime = cfg.url(data, false, false);
        assertTrue(runtime.contains(";integratedSecurity=true;authenticationScheme=NativeAuthentication"), runtime);
        assertTrue(cfg.integrated(true), "without a setup login, the schema is set up with Windows sign-in too");
        assertTrue(cfg.hasSetupAccount());
    }

    @Test void aSetupSqlLoginStillWorksBesideWindowsSignIn() throws Exception {
        Path data = Files.createTempDirectory("ooa-winauth2");
        Path file = DbConfig.defaultFile(data);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "engine=sqlserver\nhost=sql01\ndatabase=ooa\nauth=windows\nsetup.user=ooa_setup\ntls=required\n");
        DbConfig cfg = DbConfig.load(file);
        assertTrue(cfg.integrated(false));
        assertFalse(cfg.integrated(true));
        assertFalse(cfg.url(data, true, true).contains("integratedSecurity"), "the setup login signs in with its password");
    }

    @Test void windowsSignInIsCheckedOnLoadAndConfigure() throws Exception {
        Path data = Files.createTempDirectory("ooa-winauth3");
        Path file = DbConfig.defaultFile(data);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "engine=postgresql\nhost=pg\ndatabase=ooa\nauth=windows\n");
        assertEquals("CR.DB_CONFIG", assertThrows(OoaException.class, () -> DbConfig.load(file)).code(), "SQL Server only");
        Files.writeString(file, "engine=sqlserver\nhost=sql\ndatabase=ooa\nauth=kerberos\n");
        assertThrows(OoaException.class, () -> DbConfig.load(file));
        Files.writeString(file, "engine=sqlserver\nhost=sql\ndatabase=ooa\n");
        assertThrows(OoaException.class, () -> DbConfig.load(file), "a SQL login needs its user");

        assertEquals(1, DbCommand.configure(file, Map.of("engine", "postgresql", "host", "pg", "windows-auth", "")));
        assertThrows(OoaException.class, () -> DbCommand.windowsLogin("ooa-svc"), "DOMAIN\\name");
        assertThrows(OoaException.class, () -> DbCommand.windowsLogin("CORP\\ooa]; DROP LOGIN x"));
        assertEquals("CORP\\ooa-svc$", DbCommand.windowsLogin("CORP\\ooa-svc$"));
    }
}
