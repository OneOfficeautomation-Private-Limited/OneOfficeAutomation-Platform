package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.controlroom.vault.ExternalVaults;
import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.OoaException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Delinea Secret Server, BeyondTrust Password Safe and ARCON PAM as external vaults (round 4, A3, as AA's list), each
 * against a stand-in that answers as the product's REST API does: sign-in first, then the secret.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MoreExternalVaultsTest {
    Db db; ControlRoom cr; HttpServer fake; String base;
    final List<String> calls = Collections.synchronizedList(new ArrayList<>());

    @BeforeAll void start() throws Exception {
        Path data = Files.createTempDirectory("ooa-more-vaults");
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fake.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath(), q = ex.getRequestURI().getRawQuery() == null ? "" : ex.getRequestURI().getRawQuery();
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), auth = String.valueOf(ex.getRequestHeaders().getFirst("Authorization"));
            calls.add(ex.getRequestMethod() + " " + path);
            String out; int status = 200;
            // Delinea
            if (path.equals("/ss/oauth2/token")) { status = body.contains("password=ss-pass") && body.contains("username=robot") ? 200 : 401; out = "{\"access_token\":\"ss-token\"}"; }
            else if (path.equals("/ss/api/v1/secrets") && auth.equals("Bearer ss-token")) out = "{\"records\":[{\"id\":42,\"name\":\"SAP robot\"}]}";
            else if (path.equals("/ss/api/v1/secrets/42/fields/password") && auth.equals("Bearer ss-token")) out = "\"Sap-Pass-1\"";
            // BeyondTrust
            else if (path.endsWith("/Auth/SignAppin")) { status = auth.equals("PS-Auth key=bt-key; runas=robot;") ? 200 : 401; out = "{}"; ex.getResponseHeaders().add("Set-Cookie", "ASP.NET_SessionId=s1; Path=/"); }
            else if (path.endsWith("/ManagedAccounts") && q.contains("systemName=sql01") && q.contains("accountName=sa")) out = "{\"SystemId\":7,\"AccountId\":9}";
            else if (path.endsWith("/Requests") && body.contains("\"AccountID\":9")) out = "101";
            else if (path.endsWith("/Credentials/101") && String.valueOf(ex.getRequestHeaders().getFirst("Cookie")).contains("ASP.NET_SessionId=s1")) out = "\"Sql-Pass-1\"";
            else if (path.endsWith("/Requests/101/Checkin") || path.endsWith("/Auth/Signout")) out = "{}";
            // ARCON
            else if (path.equals("/arcon/token")) { status = body.contains("grant_type=password") && body.contains("password=ar-pass") ? 200 : 401; out = "{\"access_token\":\"ar-token\"}"; }
            else if (path.equals("/arcon/api/ServicePassword/GetTargetDevicePassKey") && auth.equals("Bearer ar-token") && body.contains("\"ServerIp\":\"10.0.0.5\"") && body.contains("\"TargetType\":\"Windows\""))
                out = "{\"Result\":[{\"UserName\":\"svc_robot\",\"Password\":\"Win-Pass-1\"}]}";
            else { status = 404; out = "{}"; }
            byte[] b = out.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, b.length); ex.getResponseBody().write(b); ex.close();
        });
        fake.start();
        base = "http://127.0.0.1:" + fake.getAddress().getPort();
    }

    @AfterAll void stop() { fake.stop(0); cr.triggerPollers.stop(); db.close(); }

    @Test
    void delineaBeyondTrustAndArconReadASecretAfterSigningIn() {
        ExternalVaults v = new ExternalVaults(cr);
        Map<String, Object> ss = ExternalVaults.validate(Map.of("provider", "delinea", "url", base + "/ss", "username", "robot"));
        assertEquals("Sap-Pass-1", v.read(ss, "ss-pass", "SAP robot", "password"));
        assertEquals("VAULT.EXTERNAL_DENIED", assertThrows(OoaException.class, () -> v.read(ss, "wrong", "SAP robot", "password")).code());

        Map<String, Object> bt = ExternalVaults.validate(Map.of("provider", "beyondtrust", "url", base, "username", "robot"));
        calls.clear();
        assertEquals("Sql-Pass-1", v.read(bt, "bt-key", "sql01/sa", "password"));
        assertTrue(calls.contains("PUT /BeyondTrust/api/public/v3/Requests/101/Checkin") && calls.contains("POST /BeyondTrust/api/public/v3/Auth/Signout"), "checked in and signed out: " + calls);
        assertEquals("VAULT.NOT_FOUND", assertThrows(OoaException.class, () -> v.read(bt, "bt-key", "no-account", "password")).code());

        Map<String, Object> ar = ExternalVaults.validate(Map.of("provider", "arcon", "url", base + "/arcon/api/ServicePassword/GetTargetDevicePassKey", "token_url", base + "/arcon/token", "username", "robot"));
        assertEquals("Win-Pass-1", v.read(ar, "ar-pass", "10.0.0.5/Windows/svc_robot", "password"));
        assertEquals("Win-Pass-1", v.read(ar, "{\"client_secret\": \"cs\", \"password\": \"ar-pass\"}", "10.0.0.5/Windows/svc_robot", "password"));
        assertEquals("svc_robot", v.read(ar, "ar-pass", "10.0.0.5/Windows/svc_robot", "UserName"));

        // what each link needs is checked before it is saved
        assertEquals("CR.BAD_INPUT", assertThrows(OoaException.class, () -> ExternalVaults.validate(Map.of("provider", "arcon", "url", base))).code());
        assertEquals("CR.BAD_INPUT", assertThrows(OoaException.class, () -> ExternalVaults.validate(Map.of("provider", "delinea", "url", base))).code());
    }
}
