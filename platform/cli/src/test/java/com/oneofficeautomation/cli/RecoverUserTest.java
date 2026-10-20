package com.oneofficeautomation.cli;

import com.oneofficeautomation.controlroom.ControlRoom;
import com.oneofficeautomation.controlroom.Db;
import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** `ooa reset-admin`: a forgotten password is replaced by a one-time password that must be changed at sign-in. */
class RecoverUserTest {
    @Test void forgottenPasswordGetsAOneTimePassword() throws Exception {
        Path data = Files.createTempDirectory("ooa-recover");
        Db db = Db.embedded(data);
        try {
            ControlRoom cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
            String first = cr.bootstrap();
            String token = (String) cr.login("admin", first, "127.0.0.1").get("token");
            cr.changePassword(cr.session(token), first, "Forgotten-Passw0rd-1", token, "127.0.0.1");
            for (int i = 0; i < 5; i++) assertThrows(OoaException.class, () -> cr.login("admin", "wrong-guess-123", "127.0.0.1"));

            String temp = cr.recoverUser("admin");
            Map<String, Object> r = cr.login("admin", temp, "127.0.0.1");
            assertEquals(true, ((Map<?, ?>) r.get("user")).get("must_change"), "the one-time password must be changed at sign-in");
            assertThrows(OoaException.class, () -> cr.login("admin", "Forgotten-Passw0rd-1", "127.0.0.1"), "the old password no longer works");
            assertEquals("CR.NOT_FOUND", assertThrows(OoaException.class, () -> cr.recoverUser("nobody")).code());
            assertTrue(cr.audit.verify().get("valid").equals(true));
        } finally { db.close(); }
    }
}
