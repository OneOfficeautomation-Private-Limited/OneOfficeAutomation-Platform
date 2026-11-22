package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;
import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * G4 (AA parity, 22 Nov 2026): conditions that keep trying for some seconds, a program found by its full path, and the
 * defaults new steps get (overwrite off for screen captures, special keys read when typing, wait for the window).
 */
class ConditionsParityG4Test {

    @Test void keepTryingChecksAgainUntilTrueOrTimeUp() throws Exception {
        ActionContext ctx = PackagesTestSupport.ctx(Files.createTempDirectory("ooa-g4"), Map.of());
        AtomicInteger n = new AtomicInteger();
        assertTrue(ctx.keepTrying(5, () -> n.incrementAndGet() >= 3), "true on the third check");
        assertEquals(3, n.get());
        n.set(0);
        long t0 = System.currentTimeMillis();
        assertFalse(ctx.keepTrying(0.7, () -> { n.incrementAndGet(); return false; }));
        long took = System.currentTimeMillis() - t0;
        assertTrue(took >= 600 && took < 3000, "about the time given: " + took);
        assertTrue(n.get() >= 2, "checked more than once");
        n.set(0);
        assertFalse(ctx.keepTrying(0, () -> { n.incrementAndGet(); return false; }));
        assertEquals(1, n.get(), "0 checks once");
    }

    @Test void aProgramByItsFullPathAndAHostWithRetries() throws Exception {
        ActionContext ctx = PackagesTestSupport.ctx(Files.createTempDirectory("ooa-g4"), Map.of());
        ApplicationPackage app = new ApplicationPackage();
        String me = ProcessHandle.current().info().command().orElseThrow();
        assertTrue(app.isRunning(ctx, me, 0), "this test's own java, by its full path");
        assertTrue(app.isRunning(ctx, Path.of(me).getFileName().toString()), "and by its file name");
        assertFalse(app.isRunning(ctx, "C:\\nowhere\\" + Path.of(me).getFileName(), 0.4), "the same name in another folder is not it");
        assertTrue(new NetworkPackage().reachable(ctx, "127.0.0.1", 1000, 1));
    }

    @Test void newStepDefaults() {
        var typing = PackageIntrospector.inspect(DesktopPackage.class).actions().get("type_text");
        assertEquals(true, typing.inputs().stream().filter(i -> i.name().equals("special_keys")).findFirst().orElseThrow().newStepDefault(), "special keys read in new steps");
        var shot = PackageIntrospector.inspect(DesktopPackage.class).actions().get("capture_screen");
        assertEquals(false, shot.inputs().stream().filter(i -> i.name().equals("overwrite")).findFirst().orElseThrow().newStepDefault(), "no overwrite in new steps");
        String a = String.valueOf(PackageIntrospector.manifest(ApplicationPackage.class));
        assertTrue(a.contains("wait_for_window"));
        assertTrue(String.valueOf(PackageIntrospector.manifest(NetworkPackage.class)).contains("wait_seconds"));
    }
}
