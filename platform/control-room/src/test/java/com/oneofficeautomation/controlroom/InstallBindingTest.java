package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.security.PrivateKey;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** A license with an install id works only on that Command Center; one without works anywhere. */
class InstallBindingTest {

    static String license(PrivateKey key, String id, String installId) {
        Map<String, Integer> seats = Map.of("developer", 2, "citizen_developer", 0, "attended_runner", 1, "unattended_runner", 1);
        return new License(id, "Bound Customer", "Enterprise", LocalDate.of(2026, 1, 1), LocalDate.of(2028, 1, 1), 14, seats, "", installId).sign(key);
    }

    @Test void aLicenseIssuedForAnotherInstallIsRefusedAndOneForThisInstallWorks() throws Exception {
        Path data = Files.createTempDirectory("ooa-binding"), other = Files.createTempDirectory("ooa-binding-2");
        Db db = Db.embedded(data), db2 = Db.embedded(other);
        try {
            ControlRoom cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
            ControlRoom cr2 = new ControlRoom(db2, other, FirstPartyPackages.registry(), null);
            ControlRoom.User admin = RepositoryLifecycleTest.boot(cr);
            Map<String, String> kp = License.newKeyPair();
            PrivateKey priv = License.privateKey(kp.get("private"));
            cr.licensing.useKeyForTests(License.publicKey(kp.get("public")));
            cr.licensing.clock = Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC);

            String here = cr.licensing.installId();
            assertTrue(here.matches("OOA(-[A-Z0-9]{4}){4}"), here);
            assertEquals(here, cr.licensing.installId(), "the install id is made once and kept");
            assertNotEquals(here, cr2.licensing.installId(), "every installation has its own id");
            assertEquals(here, cr.licensing.status(admin).get("install_id"), "administrators see it, to send with a license request");

            // a license issued for the other installation is refused here
            String foreign = license(priv, "LIC-OTHER", cr2.licensing.installId());
            OoaException e = assertThrows(OoaException.class, () -> cr.licensing.install(admin, foreign, null));
            assertEquals("LICENSE.OTHER_INSTALL", e.code());
            assertTrue(e.getMessage().contains(here), e.getMessage());

            // one issued for this install id is accepted and shows what it is bound to
            Map<String, Object> st = cr.licensing.install(admin, license(priv, "LIC-HERE", here.toLowerCase(Locale.ROOT)), null);
            assertEquals("active", st.get("state"), "install ids match whatever their letter case");
            assertEquals(here.toLowerCase(Locale.ROOT), st.get("bound_to"));

            // a file bound elsewhere that reaches the database another way counts as no valid license
            db.update("UPDATE licenses SET content = ? WHERE active = TRUE", foreign);
            assertEquals("expired", cr.licensing.state());
            assertTrue(String.valueOf(cr.licensing.status(admin).get("problem")).contains("another installation"));
            assertEquals("CR.LICENSE_EXPIRED", cr.licensing.expiredError().code());
            assertTrue(cr.licensing.expiredError().getMessage().contains("another installation"));

            // a license without an install id works on any installation
            Map<String, Object> any = cr.licensing.install(admin, license(priv, "LIC-ANY", ""), null);
            assertEquals("active", any.get("state"));
            assertEquals("any installation", any.get("bound_to"));

            // an install id edited in the database no longer matches anything
            db.update("UPDATE settings SET json_value = ? WHERE name = 'install_id'", "\"v1:tampered\"");
            assertEquals("unreadable", cr.licensing.installId());
        } finally { db.close(); db2.close(); }
    }

    @Test void licenseFilesKeepTheInstallIdThroughSigning() {
        Map<String, String> kp = License.newKeyPair();
        String text = license(License.privateKey(kp.get("private")), "LIC-1", "OOA-ABCD-EFGH-JKLM-NPQR");
        assertEquals("OOA-ABCD-EFGH-JKLM-NPQR", License.verify(text, License.publicKey(kp.get("public"))).installId());
        String old = new License("LIC-2", "C", "E", LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1), 14, Map.of(), "").sign(License.privateKey(kp.get("private")));
        assertEquals("", License.verify(old, License.publicKey(kp.get("public"))).installId(), "older files have no install id");
    }
}
