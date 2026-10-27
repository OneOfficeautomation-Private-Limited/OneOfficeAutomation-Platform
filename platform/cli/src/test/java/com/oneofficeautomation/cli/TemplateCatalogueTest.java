package com.oneofficeautomation.cli;

import com.oneofficeautomation.controlroom.ControlRoom;
import com.oneofficeautomation.controlroom.Db;
import com.oneofficeautomation.controlroom.License;
import com.oneofficeautomation.packages.FirstPartyPackages;
import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * K7: the template catalogue published with each release, made from the repository's templates folder by
 * "ooa release templates". Every template must import into a fresh Command Center (checksums and validation pass).
 */
class TemplateCatalogueTest {

    @Test @SuppressWarnings("unchecked")
    void everyPublishedTemplateInstalls() throws Exception {
        Path templates = Path.of(System.getProperty("ooa.examples")).getParent().resolve("templates");
        Path work = Files.createTempDirectory("ooa-catalogue");
        Map<String, String> kp = License.newKeyPair();
        Files.writeString(work.resolve("private.key"), kp.get("private"));
        Path out = work.resolve("templates.json");
        assertEquals(0, ReleaseCommand.templates(Map.of("dir", templates.toString(), "key", work.resolve("private.key").toString(), "out", out.toString())));

        Path data = Files.createTempDirectory("ooa-catalogue-cr");
        Db db = Db.embedded(data);
        try {
            ControlRoom cr = new ControlRoom(db, data, FirstPartyPackages.registry(), Ooa.schemaValidator());
            String pw = cr.bootstrap();
            String t0 = (String) cr.login("admin", pw, null).get("token");
            cr.changePassword(cr.session(t0), pw, "Admin-Passw0rd-2026", t0, null);
            ControlRoom.User admin = cr.session((String) cr.login("admin", "Admin-Passw0rd-2026", null).get("token"));
            var t = com.oneofficeautomation.controlroom.Templates.withKey(cr, License.publicKey(kp.get("public")));
            assertEquals(6, ((Number) t.upload(admin, Files.readString(out), null).get("templates")).intValue());
            for (Map<String, Object> tpl : (List<Map<String, Object>>) t.list(admin).get("templates")) {
                Map<String, Object> r = t.install(admin, "vendor", (String) tpl.get("id"), "Templates", null);
                assertEquals(true, r.get("ok"), tpl.get("id") + ": " + r);
            }
            assertEquals(6, db.query("SELECT path FROM bots WHERE path LIKE 'Templates/%'").size());
        } finally { db.close(); }
    }
}
