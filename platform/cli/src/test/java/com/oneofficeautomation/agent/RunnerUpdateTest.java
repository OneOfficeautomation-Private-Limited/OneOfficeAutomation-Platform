package com.oneofficeautomation.agent;

import com.oneofficeautomation.controlroom.*;
import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runner updates: the Command Center's file list and build id, when a heartbeat offers an update, staging with checksum
 * checks (reusing files the runner has), and the helper that swaps the program folders.
 */
class RunnerUpdateTest {
    Path root, ccLib, runnerLib;
    Db db; ControlRoom cr; ControlRoom.User admin; Map<String, Object> device;

    static void jar(Path dir, String name, String content) throws Exception { Files.writeString(dir.resolve(name), content); }

    @BeforeEach void setUp() throws Exception {
        root = Files.createTempDirectory("ooa-update");
        ccLib = Files.createDirectories(root.resolve("cc").resolve("lib"));
        runnerLib = Files.createDirectories(root.resolve("runner").resolve("lib"));
        jar(ccLib, "cli-0.1.2.jar", "cli new");          jar(runnerLib, "cli-0.1.1.jar", "cli old");
        jar(ccLib, "packages-0.1.2.jar", "packages");    jar(runnerLib, "packages-0.1.2.jar", "packages");   // unchanged
        jar(ccLib, "vendor-tools-0.1.2.jar", "licence tools");
        Path data = Files.createDirectories(root.resolve("data"));
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        String pw = cr.bootstrap();
        String t = (String) cr.login("admin", pw, null).get("token");
        cr.changePassword(cr.session(t), pw, "Admin-Passw0rd-2026", t, null);
        admin = cr.session((String) cr.login("admin", "Admin-Passw0rd-2026", null).get("token"));
        String id = (String) cr.registerDevice((String) cr.createRegistrationKey(admin, 1, null).get("key"), "R1", "r1", "Windows 11", "0.2.0", null).get("device_id");
        device = db.one("SELECT * FROM devices WHERE id = ?", id);
        cr.runnerUpdates.useLib(ccLib);
    }

    @AfterEach void tearDown() { db.close(); }

    Map<String, Object> beat(String build) { return cr.heartbeat(db.one("SELECT * FROM devices WHERE id = ?", device.get("id")), Map.of("build", build)); }

    @Test @SuppressWarnings("unchecked")
    void theCommandCenterOffersItsFilesAndTheRunnerStagesThem() throws Exception {
        Map<String, Object> m = cr.runnerUpdates.manifest();
        List<Map<String, Object>> files = (List<Map<String, Object>>) m.get("files");
        assertEquals(List.of("cli-0.1.2.jar", "packages-0.1.2.jar"), files.stream().map(f -> f.get("name")).toList(), "the licence tools never go to runners");
        assertEquals("0.1.2", m.get("version"));
        assertEquals(m.get("build"), Updater.build(ccLib), "runner and Command Center calculate the build alike");
        String mine = Updater.build(runnerLib);
        assertNotEquals(m.get("build"), mine);

        // an update is offered only when asked for, or with automatic updates on
        assertNull(beat(mine).get("update"));
        cr.runnerUpdates.request(admin, List.of((String) device.get("id")), null);
        Map<String, Object> u = (Map<String, Object>) beat(mine).get("update");
        assertEquals(m.get("build"), u.get("build"));
        assertEquals("0.1.2", u.get("version"));

        // staging: the unchanged file is copied, the changed one downloaded, every checksum checked
        List<String> downloaded = new ArrayList<>();
        Updater.Source src = new Updater.Source() {
            public Map<String, Object> manifest() { return cr.runnerUpdates.manifest(); }
            public byte[] file(String name) { downloaded.add(name); try { return cr.runnerUpdates.file(name); } catch (Exception e) { throw new RuntimeException(e); } }
        };
        Path staged = Updater.stage(runnerLib, src);
        assertEquals(List.of("cli-0.1.2.jar"), downloaded);
        assertEquals("cli new", Files.readString(staged.resolve("cli-0.1.2.jar")));
        assertEquals(m.get("build"), Updater.build(staged));
        assertEquals("CR.NOT_FOUND", assertThrows(OoaException.class, () -> cr.runnerUpdates.file("vendor-tools-0.1.2.jar")).code());

        // the helper swaps the folders once the runner has ended (Windows)
        if (Network.WINDOWS) {
            Process done = new ProcessBuilder("cmd.exe", "/c", "exit").start();
            done.waitFor(10, TimeUnit.SECONDS);
            Path script = runnerLib.getParent().resolve("update").resolve("apply-update.cmd");
            Files.writeString(script, Updater.applyScript(runnerLib.getParent(), done.pid(), null));
            Process p = new ProcessBuilder("cmd.exe", "/c", script.toString()).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(p.waitFor(60, TimeUnit.SECONDS), out);
            assertEquals(m.get("build"), Updater.build(runnerLib), "the runner's lib folder now holds the new build: " + out);
            assertFalse(Files.exists(runnerLib.getParent().resolve("update")), "the staging folder is gone");
            assertFalse(Files.exists(runnerLib.getParent().resolve("lib.old")));
        }

        // a runner on the Command Center's build gets nothing more, and the request is cleared
        assertNull(beat((String) m.get("build")).get("update"));
        assertEquals(false, db.one("SELECT update_requested FROM devices WHERE id = ?", device.get("id")).get("update_requested"));
        cr.platform.updateSettings(admin, Map.of("runner_auto_update", 1), null);
        assertNotNull(beat(mine).get("update"), "automatic updates: every runner on another build is offered one");
    }

    @Test void aFileThatDoesNotMatchItsChecksumStopsTheUpdate() throws Exception {
        Updater.Source bad = new Updater.Source() {
            public Map<String, Object> manifest() { return cr.runnerUpdates.manifest(); }
            public byte[] file(String name) { return "tampered".getBytes(StandardCharsets.UTF_8); }
        };
        OoaException e = assertThrows(OoaException.class, () -> Updater.stage(runnerLib, bad));
        assertTrue(e.getMessage().contains("checksum"), e.getMessage());
        assertFalse(Files.exists(runnerLib.getParent().resolve("update")), "nothing half-staged is left");
        assertEquals("cli old", Files.readString(runnerLib.resolve("cli-0.1.1.jar")), "the runner's own files are untouched");
    }

    @Test void theHelperStartsTheSignInTaskAgain() {
        String s = Updater.applyScript(Path.of("C:\\OOA"), 4242, "OOA Runner");
        assertTrue(s.contains("PID eq 4242"));
        assertTrue(s.contains("move \"C:\\OOA\\update\\lib\" \"C:\\OOA\\lib\""));
        assertTrue(s.contains("schtasks /run /tn \"OOA Runner\""));
        assertFalse(Updater.applyScript(Path.of("C:\\OOA"), 1, null).contains("schtasks"));
    }
}
