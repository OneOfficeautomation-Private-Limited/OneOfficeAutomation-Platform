package com.oneofficeautomation.packages;

import com.oneofficeautomation.packages.EnterpriseGapsTest.FakeDirectory;
import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import org.junit.jupiter.api.Test;

import java.util.*;

import static com.oneofficeautomation.packages.EnterpriseGapsTest.BASE;
import static org.junit.jupiter.api.Assertions.*;

/** Options added to the Active Directory toolkit: email/department/title on create, sign-in rename, user cannot change password. */
class ActiveDirectoryMoreOptionsTest {
    final ActiveDirectoryPackage ad = new ActiveDirectoryPackage();
    static final String STAFF = "OU=Staff," + BASE;
    static final String ANN = "CN=Ann Lee," + STAFF;

    FakeDirectory dir;
    ActionContext ctx;

    void setUp() throws Exception {
        dir = EnterpriseGapsTest.directory();
        dir.add(ANN, "objectClass", "user", "cn", "Ann Lee", "sAMAccountName", "ann", "userPrincipalName", "ann@corp.example.com", "userAccountControl", "512");
        ctx = EnterpriseGapsTest.ctx();
        ctx.sessions().put("ad", new ActiveDirectoryPackage.AdSession(dir, BASE));
    }

    static String code(Runnable r) { return assertThrows(ActionException.class, r::run).code(); }

    @Test void createUserWithEmailDepartmentAndTitle() throws Exception {
        setUp();
        String dn = (String) ad.createUser(ctx, STAFF, "jdoe", "Jane", "Doe", null, null, null, false, false, null, "ad", null, null, false,
                " jdoe@corp.example.com ", "Finance", "Accountant").get("dn");
        assertEquals("CN=Jane Doe," + STAFF, dn);
        assertEquals("jdoe@corp.example.com", dir.val(dn, "mail"));
        assertEquals("Finance", dir.val(dn, "department"));
        assertEquals("Accountant", dir.val(dn, "title"));
        assertEquals(List.of("create " + dn), dir.log, "one create request carries the new attributes");

        String plain = (String) ad.createUser(ctx, STAFF, "temp2", null, null, "Temp Two", null, null, false, false, null, "ad", null, null, false).get("dn");
        assertNull(dir.get(plain).get("mail"), "earlier signature still works and sets nothing extra");
        assertNull(dir.get(plain).get("department"));

        assertEquals("AD.BAD_EMAIL", code(() -> ad.createUser(ctx, STAFF, "x2", null, null, "X Two", null, null, false, false, null, "ad", null, null, false, "not-an-email", null, null)));
        assertNull(dir.get("CN=X Two," + STAFF), "nothing is created when the email is invalid");
    }

    @Test void renameSignInNameObjectNameOrBoth() throws Exception {
        setUp();
        String dn = (String) ad.renameUser(ctx, "ann", "alee", true, "ad", "sign_in", null).get("dn");
        assertEquals(ANN, dn, "the object name is kept");
        assertEquals("alee", dir.val(ANN, "sAMAccountName"));
        assertEquals("alee@corp.example.com", dir.val(ANN, "userPrincipalName"), "only the part before @ changes");
        assertEquals("Ann Lee", dir.val(ANN, "cn"));

        String both = (String) ad.renameUser(ctx, "alee", "Ann Smith", true, "ad", "both", "asmith").get("dn");
        assertEquals("CN=Ann Smith," + STAFF, both);
        assertEquals("asmith", dir.val(both, "sAMAccountName"));
        assertEquals("asmith@corp.example.com", dir.val(both, "userPrincipalName"));
        assertEquals("Ann Smith", dir.val(both, "displayName"));

        String objectOnly = (String) ad.renameUser(ctx, "asmith", "Ann Jones", false, "ad").get("dn");
        assertEquals("CN=Ann Jones," + STAFF, objectOnly);
        assertEquals("asmith", dir.val(objectOnly, "sAMAccountName"), "earlier signature renames the object only");

        // a user without a UPN only gets a new sAMAccountName
        ad.renameUser(ctx, "bob", "bray", true, "ad", "sign_in", "");
        assertEquals("bray", dir.val("CN=Bob Ray," + STAFF, "sAMAccountName"));
        assertNull(dir.get("CN=Bob Ray," + STAFF).get("userPrincipalName"));

        dir.log.clear();
        assertEquals("AD.BAD_TARGET", code(() -> ad.renameUser(ctx, "bray", "x", true, "ad", "everything", null)));
        assertEquals("AD.BAD_NAME", code(() -> ad.renameUser(ctx, "bray", "x", true, "ad", "sign_in", "bad*name")));
        assertEquals("AD.BAD_NAME", code(() -> ad.renameUser(ctx, "bray", "x", true, "ad", "both", "a-name-that-is-longer-than-twenty")));
        assertTrue(dir.log.isEmpty(), "invalid input is rejected before any change");
    }

    // ---- security descriptor helpers for the cannot_change_password tests ----

    static byte[] ace(int type, int flags, int mask, byte[] objectType, byte[] sid) {
        int len = 8 + (objectType == null ? 0 : 4 + objectType.length) + sid.length;
        byte[] a = new byte[len];
        a[0] = (byte) type; a[1] = (byte) flags; a[2] = (byte) len; a[3] = (byte) (len >> 8);
        a[4] = (byte) mask; a[5] = (byte) (mask >> 8); a[6] = (byte) (mask >> 16); a[7] = (byte) (mask >> 24);
        int p = 8;
        if (objectType != null) { a[8] = 1; p = 12; System.arraycopy(objectType, 0, a, p, 16); p += 16; }
        System.arraycopy(sid, 0, a, p, sid.length);
        return a;
    }

    static final byte[] SID_ADMINS = {1, 2, 0, 0, 0, 0, 0, 5, 32, 0, 0, 0, 0x20, 0x02, 0, 0};   // S-1-5-32-544

    /** Self-relative security descriptor with only a DACL holding these ACEs. */
    static byte[] sd(byte[]... aces) {
        int aclSize = 8;
        for (byte[] a : aces) aclSize += a.length;
        byte[] sd = new byte[20 + aclSize];
        sd[0] = 1; sd[2] = 0x04; sd[3] = (byte) 0x80;
        sd[16] = 20;
        sd[20] = 4; sd[22] = (byte) aclSize; sd[23] = (byte) (aclSize >> 8); sd[24] = (byte) aces.length;
        int p = 28;
        for (byte[] a : aces) { System.arraycopy(a, 0, sd, p, a.length); p += a.length; }
        return sd;
    }

    /** The ACEs of a descriptor's DACL. */
    static List<byte[]> aces(byte[] sd) {
        int o = (sd[16] & 0xFF) | (sd[17] & 0xFF) << 8, count = (sd[o + 4] & 0xFF) | (sd[o + 5] & 0xFF) << 8, p = o + 8;
        assertEquals(sd.length - o, (sd[o + 2] & 0xFF) | (sd[o + 3] & 0xFF) << 8, "ACL size matches");
        List<byte[]> out = new ArrayList<>();
        for (int i = 0; i < count; i++) { int size = (sd[p + 2] & 0xFF) | (sd[p + 3] & 0xFF) << 8; out.add(Arrays.copyOfRange(sd, p, p + size)); p += size; }
        assertEquals(sd.length, p, "no bytes after the last ACE");
        return out;
    }

    byte[] storedSd() throws Exception { return (byte[]) dir.get(ANN).get("nTSecurityDescriptor").get(); }

    @Test void cannotChangePasswordWritesDenyAcesAndCanBeUndone() throws Exception {
        setUp();
        byte[] adminsFull = ace(0, 0, 0xF01FF, null, SID_ADMINS);
        byte[] inheritedRead = ace(0, 0x10, 0x20094, null, ActiveDirectoryPackage.SID_EVERYONE);
        byte[] allowEveryone = ace(5, 0, 0x100, ActiveDirectoryPackage.CHANGE_PASSWORD_GUID, ActiveDirectoryPackage.SID_EVERYONE);
        dir.get(ANN).put("nTSecurityDescriptor", sd(adminsFull, allowEveryone, inheritedRead));

        Map<String, Object> r = ad.accountOptions(ctx, "ann", null, null, null, "ad", null, null, null, null, null, true);
        assertEquals(new java.math.BigDecimal(512), r.get("user_account_control"));
        List<byte[]> acl = aces(storedSd());
        assertEquals(4, acl.size(), "the old allow ACE is replaced by deny ACEs for Everyone and SELF");
        assertArrayEquals(ActiveDirectoryPackage.changePasswordAce(6, ActiveDirectoryPackage.SID_EVERYONE), acl.get(0), "deny ACEs come first");
        assertArrayEquals(ActiveDirectoryPackage.changePasswordAce(6, ActiveDirectoryPackage.SID_SELF), acl.get(1));
        assertArrayEquals(adminsFull, acl.get(2), "other ACEs are kept in order");
        assertArrayEquals(inheritedRead, acl.get(3));
        byte[] deny = acl.get(0);
        assertEquals(40, deny.length);
        assertEquals(0x100, (deny[4] & 0xFF) | (deny[5] & 0xFF) << 8, "control access right");
        assertEquals(1, deny[8], "object type present");
        assertTrue(dir.log.stream().noneMatch(l -> l.contains("userAccountControl")), "flags not touched when unchanged");

        // a deny ACE that already exists is not duplicated
        ad.accountOptions(ctx, "ann", null, null, null, "ad", null, null, null, null, null, true);
        assertEquals(4, aces(storedSd()).size());

        // undo: allow ACEs after the explicit deny ACEs, before the inherited ones
        byte[] denyOther = ace(1, 0, 0x10, null, SID_ADMINS);
        dir.get(ANN).put("nTSecurityDescriptor", sd(ActiveDirectoryPackage.changePasswordAce(6, ActiveDirectoryPackage.SID_EVERYONE), denyOther,
                ActiveDirectoryPackage.changePasswordAce(6, ActiveDirectoryPackage.SID_SELF), adminsFull, inheritedRead));
        ad.accountOptions(ctx, "ann", null, null, null, "ad", null, null, null, null, null, false);
        acl = aces(storedSd());
        assertEquals(5, acl.size());
        assertArrayEquals(denyOther, acl.get(0));
        assertArrayEquals(ActiveDirectoryPackage.changePasswordAce(5, ActiveDirectoryPackage.SID_EVERYONE), acl.get(1));
        assertArrayEquals(ActiveDirectoryPackage.changePasswordAce(5, ActiveDirectoryPackage.SID_SELF), acl.get(2));
        assertArrayEquals(adminsFull, acl.get(3));
        assertArrayEquals(inheritedRead, acl.get(4));
        assertEquals(4, storedSd()[20], "object ACEs need ACL revision 4");
    }

    @Test void cannotChangePasswordErrors() throws Exception {
        setUp();
        assertEquals("AD.NO_SECURITY_DESCRIPTOR", code(() -> ad.accountOptions(ctx, "ann", null, null, null, "ad", null, null, null, null, null, true)),
                "a directory that does not return the descriptor");
        dir.get(ANN).put("nTSecurityDescriptor", new byte[]{1, 0, 4});
        assertEquals("AD.BAD_SECURITY_DESCRIPTOR", code(() -> ad.accountOptions(ctx, "ann", null, null, null, "ad", null, null, null, null, null, true)));
        assertEquals("AD.USER_NOT_FOUND", code(() -> ad.accountOptions(ctx, "nobody", null, null, null, "ad", null, null, null, null, null, true)));
        // the option is not touched when left empty: the earlier signature still works without a descriptor
        dir.get(ANN).remove("nTSecurityDescriptor");
        ad.accountOptions(ctx, "ann", false, null, null, "ad");
        assertEquals("514", dir.val(ANN, "userAccountControl"));
    }
}
