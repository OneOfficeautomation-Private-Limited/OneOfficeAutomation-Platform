package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** The Users page: e-mail addresses (one per person, checked) and unlocking an account locked after failed sign-ins. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UsersPageTest {
    Db db; ControlRoom cr; ControlRoom.User admin;

    @BeforeAll void start() throws Exception {
        Path data = Files.createTempDirectory("ooa-userspage");
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        admin = RepositoryLifecycleTest.boot(cr);
    }
    @AfterAll void stop() { cr.triggerPollers.stop(); db.close(); }

    String idOf(String username) { return (String) cr.userDetail(admin, username).get("id"); }

    @Test void anEmailAddressIsCheckedStoredInLowerCaseAndBelongsToOnePerson() {
        cr.createUser(admin, "mia", "Mia", "viewer", null);
        cr.createUser(admin, "noor", "Noor", "viewer", null);
        cr.updateUser(admin, idOf("mia"), Map.of("email", " Mia@Example.com "), null);
        assertEquals("mia@example.com", cr.userDetail(admin, "mia").get("email"));
        assertEquals("CR.CONFLICT", assertThrows(OoaException.class, () -> cr.updateUser(admin, idOf("noor"), Map.of("email", "MIA@example.com"), null)).code());
        assertEquals("CR.BAD_INPUT", assertThrows(OoaException.class, () -> cr.updateUser(admin, idOf("noor"), Map.of("email", "not an address"), null)).code());
        cr.updateUser(admin, idOf("mia"), Map.of("email", ""), null);
        assertNull(cr.userDetail(admin, "mia").get("email"), "an empty address removes it");
    }

    @Test void rolesListWhoHoldsThemAndARoleSomeoneHoldsAsAFurtherRoleIsNotDeleted() {
        cr.platform.saveRole(admin, "auditor", "Reads the audit trail", List.of("audit.view"), null);
        cr.createUser(admin, "ola", "Ola", "viewer", null);
        cr.updateUser(admin, idOf("ola"), Map.of("further_roles", List.of("auditor")), null);
        Map<String, Object> auditor = cr.platform.roles(admin).stream().filter(r -> "auditor".equals(r.get("name"))).findFirst().orElseThrow();
        assertEquals(List.of("ola"), auditor.get("members"));
        Map<String, Object> viewer = cr.platform.roles(admin).stream().filter(r -> "viewer".equals(r.get("name"))).findFirst().orElseThrow();
        assertTrue(((List<?>) viewer.get("members")).contains("ola"), "built-in roles list their holders too");
        assertEquals("CR.CONFLICT", assertThrows(OoaException.class, () -> cr.platform.deleteRole(admin, "auditor", null)).code());
        cr.updateUser(admin, idOf("ola"), Map.of("further_roles", List.of()), null);
        cr.platform.deleteRole(admin, "auditor", null);
    }

    @Test void unlockEndsALockoutAndIsAudited() {
        cr.createUser(admin, "lee", "Lee", "viewer", null);
        String id = idOf("lee");
        db.update("UPDATE users SET failed = 5, locked_until = ? WHERE id = ?", ControlRoom.now().plusSeconds(600), id);
        assertNotNull(cr.userDetail(admin, "lee").get("locked_until"));
        cr.unlockUser(admin, id, null);
        assertNull(cr.userDetail(admin, "lee").get("locked_until"));
        assertEquals(1L, ((Number) db.one("SELECT COUNT(*) AS n FROM audit WHERE action = 'user.unlock'").get("n")).longValue());
    }
}
