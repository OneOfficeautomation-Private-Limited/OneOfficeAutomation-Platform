package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.SecureString;
import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** K4 (command review, batch 10): Active Directory searches read page by page, so they are not cut at the server's limit. */
class ActiveDirectoryPagingTest {
    @TempDir Path ws;

    @SuppressWarnings("unchecked")
    @Test void searchesReadEveryPageAndStopExactlyAtMaxResults() throws Exception {
        var cfg = new InMemoryDirectoryServerConfig("DC=acme,DC=test");
        cfg.setSchema(null);
        cfg.addAdditionalBindCredentials("CN=admin,DC=acme,DC=test", "pw");
        cfg.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("default", java.net.InetAddress.getLoopbackAddress(), 0, null));
        InMemoryDirectoryServer ldap = new InMemoryDirectoryServer(cfg);
        ldap.add("dn: DC=acme,DC=test", "objectClass: top", "objectClass: domain", "dc: acme");
        for (int i = 1; i <= 8; i++) ldap.add("dn: CN=u" + i + ",DC=acme,DC=test", "objectClass: top", "objectClass: person", "cn: u" + i, "sn: u" + i);
        ldap.startListening();
        int page = ActiveDirectoryPackage.JndiDirectory.PAGE;
        ActiveDirectoryPackage.JndiDirectory.PAGE = 3;   // three pages for eight people
        List<String> logs = new ArrayList<>();
        ActionContext ctx = new ActionContext("run", "s1", "default", ws, new HashMap<>(), Map.of(), new java.util.concurrent.atomic.AtomicBoolean(), List::of, (l, m) -> logs.add(l + " " + m));
        try {
            ActiveDirectoryPackage ad = new ActiveDirectoryPackage();
            ad.connect(ctx, "ldap://127.0.0.1:" + ldap.getListenPort(), "CN=admin,DC=acme,DC=test", new SecureString("pw", "test"), "DC=acme,DC=test", "ad", 10, 30, true);
            Map<String, Object> all = ad.search(ctx, "(objectClass=person)", List.of("cn"), 0, "ad");
            assertEquals(BigDecimal.valueOf(8), all.get("count"), "every page is read");
            Map<String, Object> five = ad.search(ctx, "(objectClass=person)", List.of("cn"), 5, "ad");
            assertEquals(BigDecimal.valueOf(5), five.get("count"));
            assertTrue(logs.stream().anyMatch(l -> l.contains("Search stopped at 5 results")), String.valueOf(logs));
            Map<String, Object> eight = ad.search(ctx, "(objectClass=person)", List.of("cn"), 8, "ad");
            assertEquals(BigDecimal.valueOf(8), eight.get("count"));
            assertEquals(1, logs.stream().filter(l -> l.contains("stopped")).count(), "exactly max_results entries is not cut");
        } finally {
            ActiveDirectoryPackage.JndiDirectory.PAGE = page;
            ldap.shutDown(true);
        }
    }
}
