package com.oneofficeautomation.cli;

import com.oneofficeautomation.controlroom.ControlRoom;
import com.oneofficeautomation.controlroom.Db;
import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.time.Instant;
import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ooa dr (round 4, A2): the checks that decide whether a second site can take over, and promote, which ends the old
 * site's leader lease, sets the address and writes a record; it refuses while a check fails.
 */
class DrCommandTest {
    @Test
    void checksAndPromoteASecondSite() throws Exception {
        Path data = Files.createTempDirectory("ooa-dr");
        Db db = Db.embedded(data);
        ControlRoom cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        try {
            Map<String, String> checks = new LinkedHashMap<>();
            for (DrCommand.Check c : DrCommand.checks(db, data)) checks.put(c.name(), c.result());
            assertEquals("WARN", checks.get("Database"), "the evaluation database has no replica");
            assertEquals("PASS", checks.get("Schema"));
            assertEquals("PASS", checks.get("Vault key"));
            assertEquals("WARN", checks.get("Address"));

            // the old site's leader still holds the lease, and a repository file was not copied here
            db.update("DELETE FROM cluster_lease");
            db.update("INSERT INTO cluster_lease (name, holder, expires) VALUES (?,?,?)", "leader", "node-site-a", Instant.now().plus(Duration.ofMinutes(5)));
            db.update("INSERT INTO repo_files (path, size, sha256, content_type, created, updated, version) VALUES (?,?,?,?,?,?,?)", "Finance/rates.xlsx", 10, "0".repeat(64), "application/octet-stream", Instant.now(), Instant.now(), 1);
            Map<String, String> again = new LinkedHashMap<>();
            for (DrCommand.Check c : DrCommand.checks(db, data)) again.put(c.name(), c.result());
            assertEquals("WARN", again.get("Leader"));
            assertEquals("FAIL", again.get("Repository files"));
            OoaException refused = assertThrows(OoaException.class, () -> DrCommand.promote(db, data, DrCommand.checks(db, data), Map.of("yes", ""), null));
            assertEquals("CR.DR_NOT_READY", refused.code());

            // once the files are here: without --yes only the checks; with it the lease ends, the address is set, a record is written
            db.update("DELETE FROM repo_files");
            assertEquals(2, DrCommand.promote(db, data, DrCommand.checks(db, data), Map.of(), null));
            assertNotNull(db.one("SELECT holder FROM cluster_lease WHERE name = 'leader'"), "nothing changes without --yes");
            assertEquals(0, DrCommand.promote(db, data, DrCommand.checks(db, data), Map.of("yes", "", "public-url", "https://ooa-dr.example.com/"), null));
            assertNull(db.one("SELECT holder FROM cluster_lease WHERE name = 'leader'"));
            assertEquals("https://ooa-dr.example.com", DrCommand.setting(db, "public_url"));
            try (var s = Files.list(data.resolve("dr"))) { assertEquals(1, s.filter((p) -> p.getFileName().toString().startsWith("promote-")).count()); }
        } finally { cr.triggerPollers.stop(); db.close(); }
    }
}
