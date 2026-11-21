package com.oneofficeautomation.packages;

import com.oneofficeautomation.packages.EnterpriseGapsTest.FakeDirectory;
import com.oneofficeautomation.sdk.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static com.oneofficeautomation.packages.EnterpriseGapsTest.BASE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * AA-parity options of the Active Directory toolkit: users found by full name or distinguished name, an optional
 * container for new objects, a plain list result, one property as a plain value, password options on create, and the
 * sign-in and target user from the vault.
 */
class ActiveDirectoryParityTest {
    final ActiveDirectoryPackage ad = new ActiveDirectoryPackage();
    static final String STAFF = "OU=Staff," + BASE;
    static final String ANN = "CN=Ann Lee," + STAFF;
    static final String GROUP = "CN=Admins A,OU=Groups," + BASE;

    FakeDirectory dir;
    ActionContext ctx;

    void setUp() throws Exception {
        dir = EnterpriseGapsTest.directory();
        dir.add(ANN, "objectClass", "user", "cn", "Ann Lee", "displayName", "Ann Lee", "sAMAccountName", "ann", "userPrincipalName", "ann@corp.example.com", "userAccountControl", "512", "title", "Clerk");
        ctx = EnterpriseGapsTest.ctx();
        ctx.sessions().put("ad", new ActiveDirectoryPackage.AdSession(dir, BASE));
    }

    static String code(Runnable r) { return assertThrows(ActionException.class, r::run).code(); }

    @Test void usersAreFoundByLogonNameFullNameOrDistinguishedName() throws Exception {
        setUp();
        assertEquals(ANN, ((Map<?, ?>) ad.getUser(ctx, "ann", "ad").get("user")).get("dn"));
        assertEquals(ANN, ((Map<?, ?>) ad.getUser(ctx, "Ann Lee", "ad").get("user")).get("dn"), "by full name");
        assertEquals(ANN, ((Map<?, ?>) ad.getUser(ctx, ANN, "ad").get("user")).get("dn"), "by distinguished name");
        assertEquals("AD.USER_NOT_FOUND", code(() -> ad.getUser(ctx, "CN=Nobody," + STAFF, "ad")));
        dir.add("CN=Ann Lee 2,OU=Leavers," + BASE, "objectClass", "user", "cn", "Ann Lee 2", "displayName", "Ann Lee", "sAMAccountName", "ann2", "userAccountControl", "512");
        assertEquals("AD.AMBIGUOUS", code(() -> ad.getUser(ctx, "Ann Lee", "ad")), "a shared full name is refused, not guessed");

        ad.disable(ctx, ANN, "ad");
        assertEquals("514", dir.val(ANN, "userAccountControl"), "every user command takes a DN");
        assertEquals(java.math.BigDecimal.valueOf(2), ad.addToGroup(ctx, ANN, "AdminsA", "ad", List.of("Bob Ray")).get("changed"), "group members by DN or full name");
    }

    @Test void oneAttributeAlsoComesBackAsAPlainValue() throws Exception {
        setUp();
        assertEquals("Clerk", ad.getUser(ctx, "ann", "ad", List.of("title")).get("value"));
        assertNull(ad.getUser(ctx, "ann", "ad", List.of("title", "mail")).get("value"), "several attributes: the dictionary only");
        assertEquals("Admins", ad.getGroupProperty(ctx, "AdminsA", List.of("cn"), "ad").get("value"));
        assertNull(ad.getGroupProperty(ctx, "AdminsA", null, "ad").get("value"));
    }

    @Test void membersAndQueriesCanReturnAPlainList() throws Exception {
        setUp();
        ad.addToGroup(ctx, "ann", GROUP, "ad", List.of("bob"));
        Map<String, Object> names = ad.groupMembers(ctx, GROUP, false, 100, "ad", "user", "names");
        assertEquals(Set.of("Ann Lee", "Bob Ray"), new HashSet<>((List<?>) names.get("list")));
        Map<String, Object> dns = ad.groupMembers(ctx, GROUP, false, 100, "ad", "user", "dns");
        assertTrue(((List<?>) dns.get("list")).contains(ANN));
        assertNull(ad.groupMembers(ctx, GROUP, false, 100, "ad", "user").get("list"), "table stays the default");
        Map<String, Object> q = ad.runQuery(ctx, "(sAMAccountName=ann)", List.of("sAMAccountName"), null, "subtree", 10, "ad", "names");
        assertEquals(List.of("ann"), q.get("list"));
        assertEquals(List.of(ANN), ad.runQuery(ctx, "(sAMAccountName=ann)", null, null, "subtree", 10, "ad", "dns").get("list"));
        assertEquals("AD.BAD_RETURN_AS", code(() -> ad.runQuery(ctx, "(cn=*)", null, null, "subtree", 10, "ad", "csv")));
    }

    @Test void newObjectsGoUnderTheSessionBaseWhenNoContainerIsGiven() throws Exception {
        setUp();
        assertEquals("CN=Temp One," + BASE, ad.createUser(ctx, null, "temp1", null, null, "Temp One", null, null, false, false, null, "ad", null, null, false).get("dn"));
        assertEquals("CN=Ops," + BASE, ad.createGroup(ctx, "", "Ops", "global", "security", null, null, "ad").get("dn"));
        assertEquals("CN=PC-1," + BASE, ad.createComputer(ctx, null, "PC-1", null, true, "ad").get("dn"));
        assertEquals("OU=Labs," + BASE, ad.createOu(ctx, null, "Labs", null, "ad").get("dn"));
        assertEquals("CN=Ops2,OU=Groups," + BASE, ad.createGroup(ctx, "OU=Groups," + BASE, "Ops2", "global", "security", null, null, "ad").get("dn"), "a container given still wins");
        assertEquals("AD.BAD_DN", code(() -> ad.createOu(ctx, "not a dn", "X", null, "ad")));
    }

    @Test void createUserPasswordOptions() throws Exception {
        setUp();
        assertEquals("AD.BAD_OPTION", code(() -> ad.createUser(ctx, STAFF, "x1", null, null, "X One", null, null, false, true, null, "ad", null, null, false, null, null, null, true)));
        assertNull(dir.get("CN=X One," + STAFF), "nothing is created for a contradiction");
        // cannot change password needs the object's permissions, which this directory does not return
        assertEquals("AD.NO_SECURITY_DESCRIPTOR", code(() -> ad.createUser(ctx, STAFF, "x2", null, null, "X Two", null, null, false, false, null, "ad", null, null, false, null, null, null, true)));
        ActionSpec spec = PackageIntrospector.inspect(ActiveDirectoryPackage.class).actions().get("create_user");
        assertEquals("must_change_password=false", spec.input("cannot_change_password").orElseThrow().visibleWhen());
        assertEquals("must_change_password=false", spec.input("password_never_expires").orElseThrow().visibleWhen());
        assertTrue(String.valueOf(spec.panelLayout()).contains("password, must_change_password, password_never_expires, cannot_change_password"), "password options sit inside the first-password tick");
    }

    @Test void signInUserAndTargetUserMayComeFromTheVault() {
        var actions = PackageIntrospector.inspect(ActiveDirectoryPackage.class).actions();
        assertTrue(actions.get("connect").input("bind_user").orElseThrow().acceptsCredential());
        assertTrue(actions.get("set_password").input("username").orElseThrow().acceptsCredential());
        assertTrue(actions.get("update_account_options").input("username").orElseThrow().acceptsCredential());
        assertFalse(actions.get("create_group").input("container_dn").orElseThrow().required());
        assertFalse(actions.get("create_ou").input("parent_dn").orElseThrow().required());
    }
}
