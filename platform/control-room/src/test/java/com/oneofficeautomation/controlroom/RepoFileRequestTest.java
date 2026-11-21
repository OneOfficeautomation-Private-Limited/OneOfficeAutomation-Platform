package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * G2 (AA parity, 21 Nov 2026): a running agent gets a file kept in One CommandCenter by its path (Get file from One
 * CommandCenter), read as the run's user and recorded in the Audit Trail.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RepoFileRequestTest {
    Db db; ControlRoom cr; ControlRoom.User admin; Path data;
    Map<String, Object> dev;

    @BeforeAll void start() throws Exception {
        data = Files.createTempDirectory("ooa-g2-file");
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        admin = RepositoryLifecycleTest.boot(cr);
        cr.checkin(admin, "Ops/Get.flow.json", LicensingAndChecksTest.agent("get"), "v1", true, null);
        Map<String, Object> d = cr.registerDevice((String) cr.createRegistrationKey(admin, 1, null).get("key"), "PC-1", "pc-1", "Windows 11", "0.2.0", null);
        dev = cr.device((String) d.get("device_id"), (String) d.get("secret"));
        cr.repoFiles.put(admin, "Templates/rates.csv", "EUR,1\n".getBytes(), "text/csv", "127.0.0.1");
    }
    @AfterAll void stop() { cr.triggerPollers.stop(); db.close(); }

    @Test void aRunGetsAFileByPathAsItsUser() {
        String run = (String) cr.startRun("admin", "admin", "Ops/Get.flow.json", (String) dev.get("id"), Map.of(), "manual", null).get("run_id");
        cr.claimNext(dev);
        Map<String, Object> f = cr.runRequest(dev, run, "repo_file", Map.of("path", "Templates/rates.csv"));
        assertEquals("EUR,1\n", new String(Base64.getDecoder().decode(String.valueOf(f.get("content")))));
        assertEquals("text/csv", f.get("content_type"));
        assertTrue(db.query("SELECT action FROM audit WHERE action = ?", "agent.repo_file").size() >= 1, "recorded in the Audit Trail");
        OoaException e = assertThrows(OoaException.class, () -> cr.runRequest(dev, run, "repo_file", Map.of("path", "Templates/none.csv")));
        assertTrue(e.code().endsWith("NOT_FOUND"), e.code());
    }
}
