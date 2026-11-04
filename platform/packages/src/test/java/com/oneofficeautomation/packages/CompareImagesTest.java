package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.RunResult;
import com.oneofficeautomation.runner.Runner;
import com.oneofficeautomation.sdk.JsonLite;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Compare images (gap G11): same, differences, tolerance, sizes and the difference picture. */
class CompareImagesTest {

    /** 100 x 50 white picture with a blue bar; extra draws a 10 x 5 black box at (60, 20); shift adds a little to every colour. */
    private static void picture(Path p, int w, int h, boolean extra, int shift) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(255 - shift, 255 - shift, 255 - shift)); g.fillRect(0, 0, w, h);
        g.setColor(new Color(30 + shift, 60 + shift, 200 + Math.min(shift, 55))); g.fillRect(0, 0, w, h / 5);   // in proportion, so a picture at twice the size shows the same drawing
        if (extra) { g.setColor(Color.BLACK); g.fillRect(60, 20, 10, 5); }
        g.dispose();
        ImageIO.write(img, "png", p.toFile());
    }

    @SuppressWarnings("unchecked")
    private static RunResult compare(Path w, String args) {
        Map<String, Object> flow = (Map<String, Object>) JsonLite.parse("""
            {"kind":"TaskBot","id":"x","name":"x",
             "variables":[{"name":"same","type":"Boolean","direction":"output"},{"name":"pct","type":"Number","direction":"output"},
                          {"name":"n","type":"Number","direction":"output"},{"name":"area","type":"Dictionary","direction":"output"},
                          {"name":"diff","type":"String","direction":"output"}],
             "steps":[{"id":"c","action":"ooa.image/compare_images","args":%s,
                "out":{"same":"same","difference_percent":"pct","changed_pixels":"n","changed_area":"area","difference_image":"diff"}}]}""".formatted(args));
        return Runner.builder(FirstPartyPackages.registry()).workDir(w).build().run(flow, Map.of());
    }

    @Test void identicalPicturesAreTheSame() throws Exception {
        Path w = Files.createTempDirectory("ooa-cmp");
        picture(w.resolve("a.png"), 100, 50, false, 0);
        picture(w.resolve("b.png"), 100, 50, false, 0);
        RunResult r = compare(w, "{\"image\":\"a.png\",\"other\":\"b.png\"}");
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals(true, r.outputs().get("same"));
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) r.outputs().get("pct")));
        assertEquals(Map.of(), r.outputs().get("area"));
    }

    @SuppressWarnings("unchecked")
    @Test void aChangedBoxIsMeasuredAndShownInRed() throws Exception {
        Path w = Files.createTempDirectory("ooa-cmp");
        picture(w.resolve("a.png"), 100, 50, false, 0);
        picture(w.resolve("b.png"), 100, 50, true, 0);
        RunResult r = compare(w, "{\"image\":\"a.png\",\"other\":\"b.png\",\"difference_image\":\"out/diff.png\"}");
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals(false, r.outputs().get("same"));
        assertEquals(0, BigDecimal.valueOf(50).compareTo((BigDecimal) r.outputs().get("n")), "10 x 5 pixels");
        assertEquals(0, new BigDecimal("1.00").compareTo((BigDecimal) r.outputs().get("pct")), "50 of 5000");
        Map<String, Object> area = (Map<String, Object>) r.outputs().get("area");
        assertEquals(0, BigDecimal.valueOf(60).compareTo((BigDecimal) area.get("x")));
        assertEquals(0, BigDecimal.valueOf(20).compareTo((BigDecimal) area.get("y")));
        assertEquals(0, BigDecimal.valueOf(10).compareTo((BigDecimal) area.get("width")));
        assertEquals(0, BigDecimal.valueOf(5).compareTo((BigDecimal) area.get("height")));
        BufferedImage diff = ImageIO.read(w.resolve("out/diff.png").toFile());
        assertEquals(0xFF0000, diff.getRGB(62, 22) & 0xFFFFFF, "a changed pixel is red");
        assertNotEquals(0xFF0000, diff.getRGB(5, 40) & 0xFFFFFF, "an unchanged pixel is not");
        RunResult allowed = compare(w, "{\"image\":\"a.png\",\"other\":\"b.png\",\"max_difference_percent\":1.5}");
        assertEquals(true, allowed.outputs().get("same"), "1 % changed is within 1.5 %");
    }

    @Test void toleranceIgnoresSlightColourShifts() throws Exception {
        Path w = Files.createTempDirectory("ooa-cmp");
        picture(w.resolve("a.png"), 100, 50, false, 0);
        picture(w.resolve("b.png"), 100, 50, false, 6);
        assertEquals(true, compare(w, "{\"image\":\"a.png\",\"other\":\"b.png\"}").outputs().get("same"), "18 of 765 is within 10 %");
        assertEquals(false, compare(w, "{\"image\":\"a.png\",\"other\":\"b.png\",\"tolerance\":0}").outputs().get("same"), "exact comparison sees the shift");
    }

    @Test void differentSizesFailOrAreScaled() throws Exception {
        Path w = Files.createTempDirectory("ooa-cmp");
        picture(w.resolve("a.png"), 100, 50, false, 0);
        picture(w.resolve("big.png"), 200, 100, false, 0);
        RunResult r = compare(w, "{\"image\":\"a.png\",\"other\":\"big.png\"}");
        assertFalse(r.ok());
        assertEquals("IMAGE.SIZE_MISMATCH", r.error().get("code"));
        RunResult s = compare(w, "{\"image\":\"a.png\",\"other\":\"big.png\",\"size_mismatch\":\"scale\",\"max_difference_percent\":2}");
        assertTrue(s.ok(), String.valueOf(s.error()));
        assertEquals(true, s.outputs().get("same"), "the same drawing at twice the size: " + s.outputs().get("pct") + " % differ, area " + s.outputs().get("area"));
    }
}
