package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Item 1 (21 Nov 2026): the run window on a runner's screen. Settings > Runners > Run window decide whether it shows,
 * its size, whether it starts minimised and whether Pause and Stop are on it; Run now may choose for one run when
 * Settings allow it; headless runs never show it. The choice travels with the job.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RunWindowSettingsTest {
    Db db; ControlRoom cr; ControlRoom.User admin; Path data;
    Map<String, Object> dev;

    @BeforeAll void start() throws Exception {
        data = Files.createTempDirectory("ooa-runwindow");
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        admin = RepositoryLifecycleTest.boot(cr);
        cr.checkin(admin, "Desk/Show.flow.json", LicensingAndChecksTest.agent("show"), "v1", true, null);
        Map<String, Object> r = cr.registerDevice((String) cr.createRegistrationKey(admin, 1, null).get("key"), "DESK-PC", "desk-pc", "Windows 11", "0.2.0", null);
        dev = cr.device((String) r.get("device_id"), (String) r.get("secret"));
    }
    @AfterAll void stop() { cr.triggerPollers.stop(); db.close(); }
    @AfterEach void reset() {
        cr.platform.updateSettings(admin, Map.of("run_window_show", 1, "run_window_size", 1, "run_window_minimised", 0, "run_window_controls", 1, "run_window_per_run", 1), null);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> window(String mode, Object choice) {
        cr.startRun("admin", "admin", "Desk/Show.flow.json", (String) dev.get("id"), null, Map.of(), "manual", null, 0, null, null, mode, choice);
        Map<String, Object> j = cr.claimNext(dev);
        assertNotNull(j);
        return (Map<String, Object>) j.get("run_window");
    }

    @Test void shownAtMediumByDefaultWithPauseAndStop() {
        assertEquals(Map.of("size", "medium", "minimised", false, "controls", true), window("attended", null));
        assertEquals(Map.of("size", "medium", "minimised", false, "controls", true), window("unattended", "default"));
    }

    @Test void neverForHeadlessRuns() {
        assertNull(window("headless", "large"));
    }

    @Test void settingsDecideSizeMinimisedAndControls() {
        cr.platform.updateSettings(admin, Map.of("run_window_size", 2, "run_window_minimised", 1, "run_window_controls", 0), null);
        assertEquals(Map.of("size", "large", "minimised", true, "controls", false), window("attended", null));
        cr.platform.updateSettings(admin, Map.of("run_window_show", 0), null);
        assertNull(window("attended", null), "switched off for every run");
    }

    @Test void aPersonChoosesForOneRunWhenSettingsAllowIt() {
        assertEquals("small", window("attended", "Small").get("size"));
        assertNull(window("attended", "hidden"));
        cr.platform.updateSettings(admin, Map.of("run_window_show", 0), null);
        assertEquals("large", window("attended", "large").get("size"), "a person may still ask for it on one run");
        cr.platform.updateSettings(admin, Map.of("run_window_per_run", 0, "run_window_show", 1), null);
        assertEquals("medium", window("attended", "hidden").get("size"), "Settings decide when people may not choose");
    }

    @Test void aChoiceThatIsNotAWindowSizeIsRefused() {
        OoaException e = assertThrows(OoaException.class, () -> window("attended", "huge"));
        assertEquals("CR.BAD_INPUT", e.code());
    }

    @Test void settingsAreCheckedForRange() {
        assertThrows(OoaException.class, () -> cr.platform.updateSettings(admin, Map.of("run_window_size", 3), null));
    }
}
