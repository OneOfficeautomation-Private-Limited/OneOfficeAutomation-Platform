package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import com.oneofficeautomation.sdk.SecureString;
import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.util.ssl.KeyStoreKeyManager;
import com.unboundid.util.ssl.SSLUtil;
import com.unboundid.util.ssl.TrustAllTrustManager;
import com.unboundid.util.ssl.cert.ManageCertificates;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * K6 (command review, batch 10): Active Directory over LDAPS and StartTLS with a certificate from the company's own CA
 * (here a self-signed one), trusted through ca_file; AA's LDAP://host/base address form.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ActiveDirectoryTlsTest {
    @TempDir static Path dir;
    InMemoryDirectoryServer ldap;
    Path ca;

    static void certs(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream();
        ResultCode rc = ManageCertificates.main(null, out, err, args);
        assertEquals(ResultCode.SUCCESS, rc, err.toString());
    }

    @BeforeAll void start() throws Exception {
        Path ks = dir.resolve("server.p12");
        ca = dir.resolve("company-ca.pem");
        certs("generate-self-signed-certificate", "--keystore", ks.toString(), "--keystore-password", "pw-123", "--keystore-type", "PKCS12", "--alias", "server",
                "--subject-dn", "CN=localhost", "--subject-alternative-name-dns", "localhost", "--subject-alternative-name-ip-address", "127.0.0.1",
                "--key-algorithm", "RSA", "--key-size-bits", "2048", "--days-valid", "30");
        certs("export-certificate", "--keystore", ks.toString(), "--keystore-password", "pw-123", "--alias", "server", "--output-format", "PEM", "--output-file", ca.toString());
        SSLUtil server = new SSLUtil(new KeyStoreKeyManager(ks.toFile(), "pw-123".toCharArray(), "PKCS12", "server"), new TrustAllTrustManager());
        var cfg = new InMemoryDirectoryServerConfig("DC=acme,DC=test");
        cfg.setSchema(null);
        cfg.addAdditionalBindCredentials("CN=admin,DC=acme,DC=test", "pw");
        InetAddress lo = InetAddress.getLoopbackAddress();
        cfg.setListenerConfigs(
                InMemoryListenerConfig.createLDAPSConfig("ldaps", lo, 0, server.createSSLServerSocketFactory(), null),
                InMemoryListenerConfig.createLDAPConfig("starttls", lo, 0, server.createSSLSocketFactory()));
        ldap = new InMemoryDirectoryServer(cfg);
        ldap.add("dn: DC=acme,DC=test", "objectClass: top", "objectClass: domain", "dc: acme");
        ldap.add("dn: CN=ann,DC=acme,DC=test", "objectClass: top", "objectClass: person", "cn: ann", "sn: ann");
        ldap.startListening();
    }

    @AfterAll void stop() { if (ldap != null) ldap.shutDown(true); }

    ActionContext ctx() { return new ActionContext("run", "s1", "default", dir, new HashMap<>(), Map.of(), new java.util.concurrent.atomic.AtomicBoolean(), List::of, (l, m) -> { }); }

    int count(ActiveDirectoryPackage ad, ActionContext ctx) {
        return ((BigDecimal) ad.search(ctx, "(objectClass=person)", List.of("cn"), 10, "ad").get("count")).intValue();
    }

    @Test void ldapsAndStartTlsTrustTheCompanyCaFromAFile() {
        ActiveDirectoryPackage ad = new ActiveDirectoryPackage();
        boolean windows = DirectoryTrust.useWindowsStore;
        DirectoryTrust.useWindowsStore = false;
        try {
            ActionContext c1 = ctx();
            String ldaps = "LDAPS://127.0.0.1:" + ldap.getListenPort("ldaps") + "/DC=acme,DC=test";
            ActionException e = assertThrows(ActionException.class, () -> ad.connect(c1, ldaps, "CN=admin,DC=acme,DC=test", new SecureString("pw", "t"), null, "ad", 5, 10, true, false, null));
            assertEquals("AD.CERTIFICATE_NOT_TRUSTED", e.code(), e.getMessage());
            ad.connect(c1, ldaps, "CN=admin,DC=acme,DC=test", new SecureString("pw", "t"), null, "ad", 5, 10, true, false, ca.toString());
            assertEquals(1, count(ad, c1), "LDAPS with the CA file, base DN from AA's address form");

            ActionContext c2 = ctx();
            ad.connect(c2, "ldap://127.0.0.1:" + ldap.getListenPort("starttls"), "CN=admin,DC=acme,DC=test", new SecureString("pw", "t"), "DC=acme,DC=test", "ad", 5, 10, true, true, ca.toString());
            assertEquals(1, count(ad, c2), "StartTLS");
            ActionException wrong = assertThrows(ActionException.class, () -> ad.connect(ctx(), "ldap://127.0.0.1:" + ldap.getListenPort("starttls"), "CN=admin,DC=acme,DC=test",
                    new SecureString("bad", "t"), "DC=acme,DC=test", "ad", 5, 10, true, true, ca.toString()));
            assertEquals("AD.AUTH_FAILED", wrong.code(), "the sign-in is checked when connecting");
        } finally { DirectoryTrust.useWindowsStore = windows; }
    }

    @Test void addressForms() {
        assertArrayEquals(new String[]{"ldap://dc01", "DC=corp,DC=com"}, ActiveDirectoryPackage.address("LDAP://dc01/DC=corp,DC=com", null));
        assertArrayEquals(new String[]{"ldaps://dc01:636", "OU=Staff,DC=corp"}, ActiveDirectoryPackage.address("ldaps://dc01:636/DC=x", "OU=Staff,DC=corp"));
        assertEquals("AD.NO_BASE_DN", assertThrows(ActionException.class, () -> ActiveDirectoryPackage.address("ldap://dc01", " ")).code());
        assertEquals("AD.BAD_URL", assertThrows(ActionException.class, () -> ActiveDirectoryPackage.address("http://dc01/DC=x", null)).code());
    }
}
