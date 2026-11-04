package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.OoaException;
import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import org.junit.jupiter.api.*;

import java.net.InetAddress;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sign-in with Active Directory or LDAP (Settings item 4f): against an in-memory directory, as OpenLDAP-style entries
 * (uid, groupOfNames). The password is checked by the directory; groups give the role; only mapped groups get in.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LdapSignInTest {
    Db db; ControlRoom cr; ControlRoom.User admin; InMemoryDirectoryServer ldap;

    static String code(Runnable r) { try { r.run(); return "ok"; } catch (OoaException e) { return e.code(); } }

    @BeforeAll void start() throws Exception {
        InMemoryDirectoryServerConfig cfg = new InMemoryDirectoryServerConfig("dc=example,dc=com");
        cfg.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("ldap", InetAddress.getLoopbackAddress(), 0, null));
        cfg.setSchema(null);   // any attributes
        ldap = new InMemoryDirectoryServer(cfg);
        ldap.add("dn: dc=example,dc=com", "objectClass: top", "objectClass: domain", "dc: example");
        ldap.add("dn: ou=people,dc=example,dc=com", "objectClass: organizationalUnit", "ou: people");
        ldap.add("dn: ou=groups,dc=example,dc=com", "objectClass: organizationalUnit", "ou: groups");
        ldap.add("dn: cn=svc-ooa,dc=example,dc=com", "objectClass: person", "cn: svc-ooa", "sn: svc", "userPassword: Service-Pass-1");
        ldap.add("dn: uid=alice,ou=people,dc=example,dc=com", "objectClass: inetOrgPerson", "uid: alice", "cn: Alice Ames", "sn: Ames", "givenName: Alice",
            "mail: alice@example.com", "userPassword: Alice-Pass-1");
        ldap.add("dn: uid=bob,ou=people,dc=example,dc=com", "objectClass: inetOrgPerson", "uid: bob", "cn: Bob Bell", "sn: Bell", "userPassword: Bob-Pass-1");
        ldap.add("dn: cn=Finance Automation,ou=groups,dc=example,dc=com", "objectClass: groupOfNames", "cn: Finance Automation", "member: uid=alice,ou=people,dc=example,dc=com");
        ldap.startListening();

        Path data = Files.createTempDirectory("ooa-ldap");
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        admin = RepositoryLifecycleTest.boot(cr);
        cr.createLocker(admin, "directory", "Directory", "user:admin", null);
        cr.setCredential(admin, "directory", "svc-ooa", "password", "Service-Pass-1", null);
        cr.platform.updateSettings(admin, Map.of("ldap_urls", "ldap://127.0.0.1:" + ldap.getListenPort(), "ldap_base_dn", "dc=example,dc=com",
            "ldap_bind_dn", "cn=svc-ooa,dc=example,dc=com", "ldap_bind_password_ref", "directory/svc-ooa/password", "ldap_user_filter", "(uid={0})",
            "ldap_name_attribute", "uid", "sso_group_roles", "Finance Automation = bot_runner"), null);
    }

    @AfterAll void stop() { db.close(); ldap.shutDown(true); }

    @Test void settingsAreChecked() {
        for (Map<String, Object> bad : List.<Map<String, Object>>of(Map.of("ldap_urls", "ldap://dc1.example.com:389"), Map.of("ldap_urls", "https://dc1"),
                Map.of("ldap_user_filter", "uid=x"), Map.of("ldap_bind_password_ref", "plain"), Map.of("ldap_name_attribute", "bad name")))
            assertEquals("CR.BAD_INPUT", code(() -> cr.platform.updateSettings(admin, bad, null)), String.valueOf(bad));
        cr.platform.updateSettings(admin, Map.of("ldap_urls", "ldaps://dc1.example.com:636 ldap://127.0.0.1:" + ldap.getListenPort()), null);   // failover
        cr.platform.updateSettings(admin, Map.of("ldap_urls", "ldap://127.0.0.1:" + ldap.getListenPort()), null);
        assertEquals("CR.LOGIN_FAILED", code(() -> cr.directory.authenticate("alice*", "x")));   // filter characters are escaped
        assertEquals("CR.LOGIN_FAILED", code(() -> cr.directory.authenticate("alice", "")), "an empty password is never an anonymous bind");
    }

    @Test @SuppressWarnings("unchecked") void signInWithTheDirectory() {
        // the test in Settings: the person, their groups and the role they would get
        Map<String, Object> t = cr.directory.test("alice");
        assertEquals(true, t.get("found"));
        assertEquals(List.of("Finance Automation"), t.get("groups"));
        assertEquals("bot_runner", t.get("role"));

        // wrong password: refused, and counted against the account once it exists
        assertEquals("CR.LOGIN_FAILED", code(() -> cr.login("alice", "wrong", null)));
        // the first sign-in makes the account, with the group's role and the directory's details
        Map<String, Object> s = cr.login("Alice", "Alice-Pass-1", null);
        assertNotNull(s.get("token"));
        Map<String, Object> u = db.one("SELECT * FROM users WHERE username = 'alice'");
        assertEquals("ldap", u.get("directory"));
        assertEquals("uid=alice,ou=people,dc=example,dc=com", u.get("external_id"));
        assertEquals("bot_runner", u.get("role"));
        assertEquals("alice@example.com", u.get("email"));
        assertEquals("Alice Ames", u.get("display_name"));
        assertEquals(Boolean.FALSE, u.get("must_change"));
        // the local password does not work for a directory account; the directory's does, again
        assertNotNull(cr.login("alice", "Alice-Pass-1", null).get("token"));

        // in no mapped group: no account
        assertEquals("LDAP.NO_ROLE", code(() -> cr.login("bob", "Bob-Pass-1", null)));
        assertNull(db.one("SELECT id FROM users WHERE username = 'bob'"));

        // the groups decide again at each sign-in: a new rule gives Alice another role
        cr.platform.updateSettings(admin, Map.of("sso_group_roles", "Finance Automation = viewer"), null);
        cr.login("alice", "Alice-Pass-1", null);
        assertEquals("viewer", db.one("SELECT role FROM users WHERE username = 'alice'").get("role"));
        cr.platform.updateSettings(admin, Map.of("sso_group_roles", "Finance Automation = bot_runner"), null);

        // switched off here: stays off whatever the directory says
        db.update("UPDATE users SET enabled = FALSE WHERE username = 'alice'");
        assertEquals("CR.USER_DISABLED", code(() -> cr.login("alice", "Alice-Pass-1", null)));
        db.update("UPDATE users SET enabled = TRUE WHERE username = 'alice'");

        // local accounts still sign in with their own password
        assertNotNull(cr.login("admin", "Admin-Passw0rd-2026", null).get("token"));

        // the directory unreachable: a clear error, never the local password
        cr.platform.updateSettings(admin, Map.of("ldap_urls", "ldap://127.0.0.1:1"), null);
        assertEquals("LDAP.UNREACHABLE", code(() -> cr.login("alice", "Alice-Pass-1", null)));
        cr.platform.updateSettings(admin, Map.of("ldap_urls", "ldap://127.0.0.1:" + ldap.getListenPort()), null);
    }
}
