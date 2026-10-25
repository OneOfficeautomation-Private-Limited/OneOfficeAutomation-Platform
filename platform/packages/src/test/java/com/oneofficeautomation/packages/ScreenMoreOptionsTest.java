package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * New options of the screen toolkits, tested without touching the real mouse, keyboard or screen: Mouse move from a
 * start point (the pointer path), Read text from screen inside a window (the rectangle read), and Find image on screen
 * that keeps looking (with pictures handed in instead of screenshots).
 */
class ScreenMoreOptionsTest {
    static ActionContext ctx() throws Exception { return PackagesTestSupport.ctx(Files.createTempDirectory("ooa-screen-more"), Map.of()); }

    // ---------- ooa.desktop mouse_move: from_x, from_y ----------

    @Test void mouseMoveFromAStartPoint() {
        int[] window = {100, 200, 800, 600};
        int[] to = DesktopPackage.target(50, 60, "window", window);
        assertArrayEquals(new int[]{150, 260}, to);

        // jump: the starting point (measured from the window like x and y), then the target
        List<int[]> jump = DesktopPackage.movePoints(10, 20, to, "window", window, 0, 5, 5);
        assertEquals(2, jump.size());
        assertArrayEquals(new int[]{110, 220, 0}, jump.get(0));
        assertArrayEquals(new int[]{150, 260, 0}, jump.get(1));

        // glide from the starting point, about one step every 10 ms, ending exactly on the target
        List<int[]> glide = DesktopPackage.movePoints(10, 20, to, "window", window, 100, 5, 5);
        assertEquals(11, glide.size());
        assertArrayEquals(new int[]{110, 220}, new int[]{glide.get(0)[0], glide.get(0)[1]});
        assertTrue(glide.get(0)[2] > 0, "a short pause on the starting point before the glide");
        assertArrayEquals(new int[]{114, 224}, new int[]{glide.get(1)[0], glide.get(1)[1]});
        assertArrayEquals(new int[]{150, 260}, new int[]{glide.get(10)[0], glide.get(10)[1]});
        for (int i = 1; i < glide.size(); i++) assertTrue(glide.get(i)[0] >= glide.get(i - 1)[0]);

        // on the screen, the start point is taken as it is
        assertArrayEquals(new int[]{7, 8, 0}, DesktopPackage.movePoints(7, 8, new int[]{70, 80}, "screen", null, 0, 0, 0).get(0));

        // without a starting point: as before, from where the pointer is
        List<int[]> plain = DesktopPackage.movePoints(null, null, to, "window", window, 0, 5, 5);
        assertEquals(1, plain.size());
        assertArrayEquals(new int[]{150, 260, 0}, plain.get(0));
        List<int[]> fromPointer = DesktopPackage.movePoints(null, null, new int[]{105, 5}, "screen", null, 50, 5, 5);
        assertEquals(5, fromPointer.size());
        assertArrayEquals(new int[]{25, 5}, new int[]{fromPointer.get(0)[0], fromPointer.get(0)[1]});
    }

    @Test void mouseMoveRefusesHalfAStartPoint() throws Exception {
        assertEquals("DESKTOP.BAD_POINT", assertThrows(ActionException.class, () -> DesktopPackage.movePoints(10, null, new int[]{1, 1}, "screen", null, 0, 0, 0)).code());
        // refused before the pointer is touched
        ActionContext c = ctx();
        assertEquals("DESKTOP.BAD_POINT", assertThrows(ActionException.class, () -> new DesktopPackage().move(c, 1, 1, "screen", null, 0, null, 5)).code());
        // window-relative start point without the window's rectangle
        assertEquals("DESKTOP.NO_WINDOW", assertThrows(ActionException.class, () -> DesktopPackage.movePoints(1, 2, new int[]{1, 1}, "window", null, 0, 0, 0)).code());
    }

    // ---------- ooa.ocr read_screen: window ----------

    @Test void readScreenAreaInsideAWindow() {
        int[] window = {100, 50, 640, 480};
        assertEquals(new Rectangle(100, 50, 640, 480), OcrPackage.windowRegion(null, null, null, null, window), "the whole window when the area is empty");
        assertEquals(new Rectangle(110, 70, 200, 30), OcrPackage.windowRegion(10, 20, 200, 30, window), "measured from the window's top-left corner");
        assertEquals(new Rectangle(140, 80, 600, 450), OcrPackage.windowRegion(40, 30, null, null, window), "the rest of the window from x, y");
        assertEquals(new Rectangle(100, 50, 640, 100), OcrPackage.windowRegion(null, null, null, 100, window));
    }

    @Test void readScreenRefusesABadAreaBeforeTouchingTheWindow() throws Exception {
        int[] window = {100, 50, 640, 480};
        assertEquals("OCR.BAD_REGION", assertThrows(ActionException.class, () -> OcrPackage.windowRegion(700, 0, null, null, window)).code(), "starts right of the window");
        assertEquals("OCR.BAD_REGION", assertThrows(ActionException.class, () -> OcrPackage.windowRegion(-5, 0, null, null, window)).code());
        assertEquals("OCR.BAD_REGION", assertThrows(ActionException.class, () -> OcrPackage.windowRegion(0, 0, 0, 10, window)).code());
        ActionContext c = ctx();
        assertEquals("OCR.BAD_REGION", assertThrows(ActionException.class,
                () -> new OcrPackage().readScreen(c, null, null, 0, 10, "eng", 6, null, null, true, 2, "none", 0, "Invoice entry")).code());
        assertEquals("OCR.BAD_LANGUAGE", assertThrows(ActionException.class,
                () -> new OcrPackage().readScreen(c, null, null, null, null, "English!", 6, null, null, true, 2, "none", 0, "Invoice entry")).code());
    }

    // ---------- ooa.image find_on_screen: timeout_seconds, interval_ms ----------

    static BufferedImage blank(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) img.setRGB(x, y, 0xFFFFFF);
        return img;
    }

    static BufferedImage noise(int w, int h) {
        Random r = new Random(42);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) img.setRGB(x, y, r.nextInt(0xFFFFFF));
        return img;
    }

    static BufferedImage with(BufferedImage template, int left, int top) {
        BufferedImage img = blank(200, 150);
        var g = img.createGraphics(); g.drawImage(template, left, top, null); g.dispose();
        return img;
    }

    @Test void findOnScreenKeepsLookingUntilThePictureAppears() throws Exception {
        ActionContext c = ctx();
        BufferedImage t = noise(20, 20);
        AtomicInteger looks = new AtomicInteger();
        // the picture shows up on the third look
        Map<String, Object> r = ImagePackage.searchUntil(c, () -> looks.incrementAndGet() < 3 ? blank(200, 150) : with(t, 60, 40),
                null, t, 10, 1, "center", 0, 0, 5, 50);
        assertEquals(true, r.get("found"));
        assertEquals(3, looks.get());
        assertEquals(0, new BigDecimal(70).compareTo((BigDecimal) r.get("x")));
        assertEquals(0, new BigDecimal(50).compareTo((BigDecimal) r.get("y")));

        // a search region still reports screen coordinates
        Rectangle region = new Rectangle(30, 10, 150, 120);
        Map<String, Object> inRegion = ImagePackage.searchUntil(c, () -> with(t, 60, 40).getSubimage(30, 10, 150, 120), region, t, 10, 1, "top_left", 0, 0, 1, 50);
        assertEquals(0, new BigDecimal(60).compareTo((BigDecimal) inRegion.get("x")));

        // never there: found is false once the time is up, after several looks
        looks.set(0);
        long t0 = System.nanoTime();
        Map<String, Object> none = ImagePackage.searchUntil(c, () -> { looks.incrementAndGet(); return blank(200, 150); }, null, t, 10, 1, "center", 0, 0, 0.3, 50);
        assertEquals(false, none.get("found"));
        assertTrue(looks.get() >= 3, "looked " + looks.get() + " times");
        assertTrue((System.nanoTime() - t0) / 1_000_000 >= 300);

        // timeout 0 looks once, as before
        looks.set(0);
        assertEquals(false, ImagePackage.searchUntil(c, () -> { looks.incrementAndGet(); return blank(200, 150); }, null, t, 10, 1, "center", 0, 0, 0, 50).get("found"));
        assertEquals(1, looks.get());
    }

    @Test void findOnScreenRefusesBadWaitingOptions() throws Exception {
        ActionContext c = ctx();
        ImagePackage p = new ImagePackage();
        assertEquals("IMAGE.BAD_INTERVAL", assertThrows(ActionException.class,
                () -> p.findOnScreen(c, "missing.png", 10, 1, "center", 0, 0, null, null, null, null, 5, 10)).code());
        assertEquals("IMAGE.BAD_TIMEOUT", assertThrows(ActionException.class,
                () -> p.findOnScreen(c, "missing.png", 10, 1, "center", 0, 0, null, null, null, null, -1, 500)).code());
        ImagePackage.checkWait(0, 500);
        ImagePackage.checkWait(3600, 50);
    }
}
