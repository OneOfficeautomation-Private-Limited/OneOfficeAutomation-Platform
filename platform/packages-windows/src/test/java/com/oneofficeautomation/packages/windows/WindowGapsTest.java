package com.oneofficeautomation.packages.windows;

import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import org.junit.jupiter.api.Test;

import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class WindowGapsTest {
    static ActionContext ctx() throws Exception {
        return new ActionContext("run", "s", "default", Files.createTempDirectory("ooa-win"), new HashMap<>(), new HashMap<>(), new AtomicBoolean(), List::of, (l, m) -> {});
    }

    @Test void patternsMustBeSpecific() {
        assertEquals("WINDOW.PATTERN_TOO_BROAD", assertThrows(ActionException.class, () -> WindowPackage.matcher("", false)).code());
        assertEquals("WINDOW.PATTERN_TOO_BROAD", assertThrows(ActionException.class, () -> WindowPackage.matcher("a", false)).code());
        assertEquals("WINDOW.PATTERN_TOO_BROAD", assertThrows(ActionException.class, () -> WindowPackage.matcher(".*", true)).code());
        assertEquals("WINDOW.BAD_PATTERN", assertThrows(ActionException.class, () -> WindowPackage.matcher("([a", true)).code());
        assertTrue(WindowPackage.matcher("notepad", false).test("Untitled - Notepad"));
        assertTrue(WindowPackage.matcher("^Invoice \\d+ - Excel$", true).test("Invoice 42 - Excel"));
        assertFalse(WindowPackage.matcher("^Invoice \\d+ - Excel$", true).test("Invoice x - Excel"));
    }

    @Test void windowAndDllOptions() throws Exception {
        assertArrayEquals(new int[]{10, 20, 300, 200}, WindowPackage.box(new int[]{10, 20, 640, 480}, null, null, 300, 200), "empty values keep the current ones");
        assertArrayEquals(new int[]{0, 5, 640, 480}, WindowPackage.box(new int[]{10, 20, 640, 480}, 0, 5, null, null));
        assertEquals("minimized", WindowPackage.state(2));
        assertEquals("maximized", WindowPackage.state(3));
        assertEquals("normal", WindowPackage.state(1));
        assertEquals(com.sun.jna.Function.ALT_CONVENTION, DllPackage.callFlags("stdcall"));
        assertEquals(com.sun.jna.Function.C_CONVENTION, DllPackage.callFlags(null));
        assertEquals("DLL.BAD_CONVENTION", assertThrows(ActionException.class, () -> DllPackage.callFlags("pascal")).code());
        ActionContext c = ctx();
        DllPackage dll = new DllPackage();
        assertEquals("DLL.NO_LIBRARY", assertThrows(ActionException.class, () -> dll.call(c, null, "GetTickCount", null, "int", null, "c")).code());
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) return;
        WindowPackage wp = new WindowPackage();
        assertEquals("WINDOW.BAD_SIZE", assertThrows(ActionException.class, () -> wp.moveResize(c, "x", null, null, 0, 10, null, 0)).code());
        assertEquals("WINDOW.NOT_FOUND", assertThrows(ActionException.class, () -> wp.getInfo(c, "OOA no such window " + UUID.randomUUID(), null, 0.3)).code());
        dll.open(c, "kernel32", "k");
        try {
            Object pid = dll.call(c, null, "GetCurrentProcessId", null, "int", "k", "stdcall").get("result");
            assertEquals(ProcessHandle.current().pid(), ((java.math.BigDecimal) pid).longValue());
        } finally { dll.close(c, "k"); }
        assertFalse(c.sessions().containsKey("k"));
    }

    /** Opens two AWT windows with a unique title, renames one, then closes both through the actions. Needs a desktop session on Windows. */
    @Test void setTitleAndCloseAll() throws Exception {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") || GraphicsEnvironment.isHeadless()) {
            System.out.println("    (no Windows desktop session; skipped)");
            return;
        }
        String tag = "OOA-test-" + UUID.randomUUID().toString().substring(0, 8);
        CountDownLatch closed = new CountDownLatch(2);
        List<Frame> frames = new ArrayList<>();
        for (int i = 1; i <= 2; i++) {
            Frame f = new Frame(tag + " window " + i);
            f.setSize(200, 100);
            f.addWindowListener(new WindowAdapter() { @Override public void windowClosing(WindowEvent e) { f.dispose(); closed.countDown(); } });
            f.setVisible(true);
            frames.add(f);
        }
        try {
            ActionContext c = ctx();
            WindowPackage wp = new WindowPackage();
            long end = System.currentTimeMillis() + 5000;
            while (WindowPackage.all().stream().filter(w -> w.title().startsWith(tag)).count() < 2 && System.currentTimeMillis() < end) Thread.sleep(100);
            assertEquals(tag + " window 1", wp.setTitle(c, tag + " window 1", tag + " renamed", null, 0).get("previous_title"));
            end = System.currentTimeMillis() + 3000;
            while (!frames.get(0).getTitle().equals(tag + " renamed") && System.currentTimeMillis() < end) Thread.sleep(50);
            assertTrue(WindowPackage.all().stream().anyMatch(w -> w.title().equals(tag + " renamed")));
            assertEquals("WINDOW.BAD_TITLE", assertThrows(ActionException.class, () -> wp.setTitle(c, tag, "two\nlines", null, 0)).code());
            Map<String, Object> r = wp.closeAll(c, "^" + tag + " (renamed|window \\d)$", true);
            assertEquals(2, ((List<?>) r.get("closed")).size());
            assertTrue(closed.await(5, TimeUnit.SECONDS), "both windows received the close request");
        } finally {
            for (Frame f : frames) f.dispose();
        }
    }
}
