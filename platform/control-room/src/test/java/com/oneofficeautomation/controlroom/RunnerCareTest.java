package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Looking after runners: maintenance, CPU and memory limits, logs and a screen picture on request, pool owners and users. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RunnerCareTest {
    Db db; ControlRoom cr; ControlRoom.User admin;

    @BeforeAll void start() throws Exception {
        Path data = Files.createTempDirectory("ooa-runnercare");
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        admin = RepositoryLifecycleTest.boot(cr);
        cr.checkin(admin, "Care/Hello.flow.json", LicensingAndChecksTest.agent("hello"), "v1", true, null);
    }
    @AfterAll void stop() { cr.triggerPollers.stop(); db.close(); }

    /** A new runner, online; returns {id, secret}. */
    String[] runner(String name) {
        Map<String, Object> dev = cr.registerDevice((String) cr.createRegistrationKey(admin, 1, null).get("key"), name, name.toLowerCase(), "Windows 11", "0.2.0", null);
        return new String[] { (String) dev.get("device_id"), (String) dev.get("secret") };
    }
    Map<String, Object> row(String[] r) { return cr.device(r[0], r[1]); }
    String queue(String deviceId) { return (String) cr.startRun("admin", "admin", "Care/Hello.flow.json", deviceId, Map.of(), "manual", null).get("run_id"); }
    Map<String, Object> runner(String[] r, String key) { return cr.devices(admin).stream().filter(d -> r[0].equals(d.get("id"))).findFirst().orElseThrow(); }

    @Test void maintenanceTakesNoNewWorkAndStopAlsoStopsTheRunsGoingOn() {
        String[] r = runner("Maint");
        queue(r[0]);
        cr.runnerCare.setMaintenance(admin, List.of(r[0]), "drain", null);
        assertNull(cr.claimNext(row(r)), "no new work in maintenance");
        Map<String, Object> beat = cr.heartbeat(row(r), Map.of("agent_version", "0.2.0"));
        assertEquals("drain", beat.get("maintenance"), "the runner is told");
        assertEquals("drain", runner(r, "x").get("maintenance"));
        cr.runnerCare.setMaintenance(admin, List.of(r[0]), null, null);
        Map<String, Object> job = cr.claimNext(row(r));
        assertNotNull(job, "work again once maintenance ends");

        // stop: the running run is asked to stop, and the audit log says so
        Map<String, Object> res = cr.runnerCare.setMaintenance(admin, List.of(r[0]), "stop", null);
        assertEquals(1, res.get("runs_stopped"));
        assertTrue((Boolean) db.one("SELECT cancel_requested FROM runs WHERE id = ?", job.get("run_id")).get("cancel_requested"));
        assertEquals("CR.BAD_INPUT", assertThrows(OoaException.class, () -> cr.runnerCare.setMaintenance(admin, List.of(r[0]), "pause", null)).code());
        cr.runnerCare.setMaintenance(admin, List.of(r[0]), null, null);
    }

    @Test void aRunnerAboveItsCpuOrMemoryLimitTakesNoNewWorkAndPoolsPassItBy() {
        String[] busy = runner("Busy"), calm = runner("Calm");
        cr.heartbeat(row(busy), Map.of("agent_version", "0.2.0", "cpu", 95, "mem", 40));
        cr.heartbeat(row(calm), Map.of("agent_version", "0.2.0", "cpu", 10, "mem", 30));
        cr.platform.updateSettings(admin, Map.of("runner_cpu_max", 80), null);
        try {
            queue(busy[0]);
            assertNull(cr.claimNext(row(busy)));
            assertEquals("CPU use 95% is above its limit of 80%", runner(busy, "x").get("over_limit"));
            String pool = (String) cr.platform.savePool(admin, null, "Care pool", "", List.of(busy[0], calm[0]), null).get("id");
            assertEquals(calm[0], cr.platform.pickDevice(pool), "the pool sends work to the runner below its limit");
            // the runner's own limit wins over the Command Center's
            cr.updateDevices(admin, List.of(busy[0]), Map.of("cpu_max", 99), null);
            assertNull(runner(busy, "x").get("over_limit"));
            assertNotNull(cr.claimNext(row(busy)));
            assertEquals("CR.BAD_INPUT", assertThrows(OoaException.class, () -> cr.updateDevices(admin, List.of(busy[0]), Map.of("mem_max", 150), null)).code());
            cr.platform.deletePool(admin, pool, null);
        } finally { cr.platform.updateSettings(admin, Map.of("runner_cpu_max", 0), null); }
    }

    @Test void logsAndAScreenPictureComeOnRequestAndPicturesNeedTheSetting() {
        String[] r = runner("Asked");
        assertFalse(cr.heartbeat(row(r), Map.of()).containsKey("send_logs"), "nothing is sent unasked");
        cr.runnerCare.request(admin, r[0], "logs", null);
        assertEquals(Boolean.TRUE, cr.runnerCare.file(admin, r[0], "logs").get("waiting"));
        assertEquals(Boolean.TRUE, cr.heartbeat(row(r), Map.of()).get("send_logs"));
        cr.runnerCare.receive(row(r), "logs", Map.of("text", "== Runner log ==\nstarted"));
        Map<String, Object> logs = cr.runnerCare.file(admin, r[0], "logs");
        assertEquals("== Runner log ==\nstarted", logs.get("content"));
        assertEquals("admin", logs.get("requested_by"));
        assertNull(logs.get("waiting"));
        assertEquals("CR.NOT_ASKED", assertThrows(OoaException.class, () -> cr.runnerCare.receive(row(r), "logs", Map.of("text", "again"))).code(), "only what was asked");

        assertEquals("CR.SCREEN_OFF", assertThrows(OoaException.class, () -> cr.runnerCare.request(admin, r[0], "screen", null)).code(), "off by default");
        cr.platform.updateSettings(admin, Map.of("runner_screen_pictures", 1), null);
        try {
            cr.runnerCare.request(admin, r[0], "screen", null);
            assertEquals(Boolean.TRUE, cr.heartbeat(row(r), Map.of()).get("send_screen"));
            assertEquals("CR.BAD_INPUT", assertThrows(OoaException.class, () -> cr.runnerCare.receive(row(r), "screen", Map.of("image", "<script>"))).code());
            cr.runnerCare.receive(row(r), "screen", Map.of("error", "The screen is locked"));
            assertEquals("The screen is locked", cr.runnerCare.file(admin, r[0], "screen").get("error"));
            assertEquals(1L, ((Number) db.one("SELECT COUNT(*) AS n FROM audit WHERE action = 'device.screen_request'").get("n")).longValue(), "each picture asked for is audited");
        } finally { cr.platform.updateSettings(admin, Map.of("runner_screen_pictures", 0), null); }
        cr.runnerCare.describe(admin, r[0], "Night shift", "Second floor", null);
        assertEquals("Night shift", runner(r, "x").get("nickname"));
        cr.deleteDevice(admin, r[0], null);
        assertNull(db.one("SELECT device_id FROM runner_files WHERE device_id = ?", r[0]), "its files go with it");
    }

    @Test void poolUsersLimitWhoSendsRunsAndOwnersManageTheirPool() {
        String[] r = runner("Pooled");
        cr.createUser(admin, "rob", "Rob Runner", "bot_runner", null);
        ControlRoom.User rob = new ControlRoom.User(cr.users(admin).stream().filter(u -> "rob".equals(u.get("username"))).findFirst().orElseThrow().get("id").toString(), "rob", "bot_runner", "Rob", false);
        String pool = (String) cr.platform.savePool(admin, null, "Finance", "", List.of(r[0]), List.of("rob"), List.of("bot_creator"), null).get("id");
        assertEquals("CR.FORBIDDEN", assertThrows(OoaException.class, () -> cr.platform.requirePoolUse("rob2", "bot_runner", pool)).code(), "not one of its roles");
        cr.platform.requirePoolUse("rob", "bot_runner", pool);   // an owner may
        cr.platform.requirePoolUse("ann", "admin", pool);        // so may someone who manages pools
        // the owner changes the pool without pools.manage, but cannot choose the owners
        cr.platform.savePool(rob, pool, "Finance", "Month end", List.of(r[0]), null, List.of("bot_creator", "bot_runner"), null);
        cr.platform.requirePoolUse("rob2", "bot_runner", pool);
        assertEquals("CR.FORBIDDEN", assertThrows(OoaException.class, () -> cr.platform.savePool(rob, pool, "Finance", "", List.of(r[0]), List.of("rob", "eve"), null, null)).code());
        assertEquals("CR.NOT_FOUND", assertThrows(OoaException.class, () -> cr.platform.savePool(admin, pool, "Finance", "", List.of(r[0]), null, List.of("no_such_role"), null)).code());
        assertEquals("CR.FORBIDDEN", assertThrows(OoaException.class, () -> cr.platform.savePool(rob, null, "Mine", "", List.of(), null, null, null)).code(), "creating pools needs pools.manage");
        cr.platform.deletePool(rob, pool, null);
    }
}
