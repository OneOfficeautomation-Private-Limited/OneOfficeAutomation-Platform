package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.packages.FirstPartyPackages;
import org.junit.jupiter.api.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.zip.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A ready runner installer from One CommandCenter (round 4, A1), as AA's Connect local device: made from the setup
 * package an administrator placed in the data folder, with Install.cmd holding the address and the person's one-time key.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RunnerPackageTest {
    Path data; Db db; ControlRoom cr; ControlRoom.User admin;

    static String code(Runnable r) { try { r.run(); return "ok"; } catch (com.oneofficeautomation.sdk.OoaException e) { return e.code(); } }

    @BeforeAll void start() throws Exception {
        data = Files.createTempDirectory("ooa-runner-package");
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        admin = RepositoryLifecycleTest.boot(cr);
    }

    @AfterAll void stop() { cr.triggerPollers.stop(); db.close(); }

    @Test
    void makesARunnerInstallerWithTheAddressAndKeyFromThePlacedSetupPackage() throws Exception {
        String key = (String) cr.myAccount.connectKey(admin, null).get("key");
        // no setup package here yet (a source checkout has no install folder either): it says what to do
        if (RunnerPackage.installFolder() == null) assertEquals(Boolean.FALSE, RunnerPackage.availability(cr).get("available"));

        Path dl = Files.createDirectories(data.resolve("downloads"));
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(dl.resolve("ooa-setup-0.2.0.zip")))) {
            for (String[] f : new String[][] { { "Install.cmd", "the old one" }, { "Install-OOA.ps1", "param()" }, { "bin/ooa.bat", "@echo ooa" },
                    { "lib/cli-0.2.0.jar", "jar" }, { "runtime/bin/java.exe", "java" }, { "DocFlow/app.py", "docflow" } }) {
                z.putNextEntry(new ZipEntry("ooa-setup-0.2.0/" + f[0]));
                z.write(f[1].getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        assertEquals(Map.of("available", true, "from", "ooa-setup-0.2.0.zip"), RunnerPackage.availability(cr));

        Instant expires = RunnerPackage.checkKey(cr, admin, key);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RunnerPackage.write(cr, out, "https://ooa.example.com:8720", key, expires, "admin");
        Map<String, String> entries = new TreeMap<>();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            for (ZipEntry e; (e = zin.getNextEntry()) != null; ) entries.put(e.getName(), new String(zin.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertEquals(Set.of("ooa-runner/Install.cmd", "ooa-runner/Install-OOA.ps1", "ooa-runner/bin/ooa.bat", "ooa-runner/lib/cli-0.2.0.jar", "ooa-runner/runtime/bin/java.exe"),
                entries.keySet(), "the product without Document Automation, under ooa-runner");
        String cmd = entries.get("ooa-runner/Install.cmd");
        assertTrue(cmd.contains("-Role Runner -Url \"https://ooa.example.com:8720\" -RegistrationKey \"" + key + "\""), cmd);
        assertFalse(cmd.contains("the old one"));

        // only the person's own key (or, for someone who manages runners, theirs); an unused one
        assertEquals("CR.BAD_REGISTRATION_KEY", code(() -> RunnerPackage.checkKey(cr, admin, "ooareg_made_up")));
        String adminKey = (String) cr.createRegistrationKey(admin, 1, null).get("key");
        assertDoesNotThrow(() -> RunnerPackage.checkKey(cr, admin, adminKey));
        cr.registerDevice(key, "PC-9", "pc-9", "Windows 11", "0.2.0", null);
        assertEquals("CR.BAD_REGISTRATION_KEY", code(() -> RunnerPackage.checkKey(cr, admin, key)), "used once already");
    }

    @Test
    void neverPacksDataOrTheOldInstaller() {
        assertTrue(RunnerPackage.skip("Install.cmd"));
        assertTrue(RunnerPackage.skip("DocFlow/config/local.env"));
        assertTrue(RunnerPackage.skip("data/master.key"));
        assertFalse(RunnerPackage.skip("lib/control-room-0.2.0.jar"));
    }
}
