package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.Test;

import java.net.*;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Administration hygiene (S11): built-in roles can be switched off; the Command Center's outgoing proxy is set in Settings. */
class AdminHygieneTest {

    @Test void aSwitchedOffBuiltInRoleGrantsNothingAndCannotBeGiven() throws Exception {
        Path data = Files.createTempDirectory("ooa-roles-off");
        Db db = Db.embedded(data);
        try {
            ControlRoom cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
            ControlRoom.User admin = RepositoryLifecycleTest.boot(cr);
            cr.createUser(admin, "vic", "Vic Viewer", "viewer", null);
            ControlRoom.User vic = ControlRoom.user(db.one("SELECT * FROM users WHERE username = 'vic'"));
            assertTrue(cr.can(vic, "repo.view"));

            cr.platform.updateSettings(admin, Map.of("switched_off_roles", "viewer, viewer"), null);
            assertEquals("viewer", cr.platform.setting("switched_off_roles"), "kept once");
            assertFalse(cr.can(vic, "repo.view"), "a switched-off role gives nothing");
            assertTrue(cr.can(admin, "settings.manage"));
            assertEquals("CR.BAD_INPUT", assertThrows(OoaException.class, () -> cr.createUser(admin, "val", "Val", "viewer", null)).code());
            assertTrue(cr.platform.roles(admin).stream().anyMatch(r -> "viewer".equals(r.get("name")) && Boolean.TRUE.equals(r.get("switched_off"))));

            // the administrator role and custom roles are not switched off here
            assertThrows(OoaException.class, () -> cr.platform.updateSettings(admin, Map.of("switched_off_roles", "admin"), null));
            assertThrows(OoaException.class, () -> cr.platform.updateSettings(admin, Map.of("switched_off_roles", "my_custom"), null));

            cr.platform.updateSettings(admin, Map.of("switched_off_roles", ""), null);
            assertTrue(cr.can(vic, "repo.view"), "switched on again");
        } finally { db.close(); }
    }

    @Test void theOutgoingProxyFromSettingsIsUsedForOutsideAddressesOnly() throws Exception {
        Path data = Files.createTempDirectory("ooa-proxy");
        Db db = Db.embedded(data);
        Map<String, String> before = new HashMap<>();
        for (String k : List.of("http.proxyHost", "http.proxyPort", "https.proxyHost", "https.proxyPort", "http.nonProxyHosts")) before.put(k, System.getProperty(k));
        try {
            ControlRoom cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
            ControlRoom.User admin = RepositoryLifecycleTest.boot(cr);
            for (String bad : List.of("proxy.example.com:8080", "https://proxy:8080", "http://proxy"))
                assertThrows(OoaException.class, () -> cr.platform.updateSettings(admin, Map.of("outbound_proxy", bad), null), bad);

            cr.platform.updateSettings(admin, Map.of("outbound_proxy", "http://proxy.example.com:3128", "outbound_proxy_bypass", "*.internal.example, 10.*"), null);
            ProxySelector sel = ProxySelector.getDefault();
            Proxy out = sel.select(URI.create("https://login.microsoftonline.com/")).get(0);
            assertEquals(Proxy.Type.HTTP, out.type());
            assertEquals("proxy.example.com", ((InetSocketAddress) out.address()).getHostString());
            assertEquals(3128, ((InetSocketAddress) out.address()).getPort());
            assertEquals(Proxy.NO_PROXY, sel.select(URI.create("https://git.internal.example/")).get(0), "listed hosts go direct");
            assertEquals(Proxy.NO_PROXY, sel.select(URI.create("http://127.0.0.1:8720/")).get(0), "this computer goes direct");

            cr.platform.updateSettings(admin, Map.of("outbound_proxy", ""), null);
            assertEquals(Proxy.NO_PROXY, ProxySelector.getDefault().select(URI.create("https://login.microsoftonline.com/")).get(0), "cleared again");
        } finally {
            before.forEach((k, v) -> { if (v == null) System.clearProperty(k); else System.setProperty(k, v); });
            db.close();
        }
    }
}
