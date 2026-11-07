package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** G55: how long a serverless run may last and how many run at once are settings, within their limits. */
class ServerlessLimitsTest {
    @Test void limitsComeFromSettings() throws Exception {
        Path data = Files.createTempDirectory("ooa-g55");
        Db db = Db.embedded(data);
        try {
            ControlRoom cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
            ControlRoom.User admin = RepositoryLifecycleTest.boot(cr);
            assertEquals(300.0, cr.serverless.maxSeconds(), "five minutes at first");
            assertEquals(4, cr.serverless.parallel());
            cr.platform.updateSettings(admin, Map.of("serverless_max_minutes", 45, "serverless_parallel", 8), null);
            assertEquals(2700.0, cr.serverless.maxSeconds());
            assertEquals(8, cr.serverless.parallel());
            assertThrows(OoaException.class, () -> cr.platform.updateSettings(admin, Map.of("serverless_max_minutes", 61), null));
            assertThrows(OoaException.class, () -> cr.platform.updateSettings(admin, Map.of("serverless_parallel", 17), null));
        } finally { db.close(); }
    }
}
