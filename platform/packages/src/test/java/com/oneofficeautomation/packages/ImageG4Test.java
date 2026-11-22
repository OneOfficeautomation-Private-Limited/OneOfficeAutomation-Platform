package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** G4 (AA parity, 22 Nov 2026), image recognition: the condition keeps trying, counts occurrences, and the defaults new steps get. */
class ImageG4Test {
    @Test void theConditionWaitsAndCountsOccurrences() throws Exception {
        if (GraphicsEnvironment.isHeadless()) { System.out.println("    (no screen; skipped)"); return; }
        Path w = Files.createTempDirectory("ooa-g4img");
        ActionContext c = PackagesTestSupport.ctx(w, Map.of());
        // a picture that is certainly on the screen: a piece of the screen itself
        BufferedImage shot = new Robot().createScreenCapture(new Rectangle(0, 0, 200, 120));
        ImageIO.write(shot.getSubimage(40, 30, 60, 40), "png", w.resolve("piece.png").toFile());
        ImagePackage img = new ImagePackage();
        assertTrue(img.imageOnScreen(c, "piece.png", 10, null, 1, 1, false), "the piece is on the screen");
        long t0 = System.currentTimeMillis();
        assertFalse(img.imageOnScreen(c, "piece.png", 10, null, 0.8, 500, false), "it is not there 500 times");
        assertTrue(System.currentTimeMillis() - t0 >= 700, "it kept trying for the seconds given");
        assertTrue(img.imageOnScreen(c, "piece.png", 10), "the older signature");
        assertNull(ImagePackage.area(null, null, null, null, null), "no window and no area: the whole screen");

        var specs = PackageIntrospector.inspect(ImagePackage.class).actions();
        assertEquals(5, ((Number) specs.get("find_on_screen").inputs().stream().filter(i -> i.name().equals("tolerance")).findFirst().orElseThrow().newStepDefault()).intValue(), "a 95 % match for new steps");
        assertEquals(3, ((Number) specs.get("click_image").inputs().stream().filter(i -> i.name().equals("timeout_seconds")).findFirst().orElseThrow().newStepDefault()).intValue(), "about 3 s of looking");
    }

    @Test void theFieldNextToALabel() {
        int[] label = {200, 100, 120, 30};   // found on a capture at twice the size: 100,50 60x15 on the screen
        assertEquals(new Rectangle(168, 46, 220, 23), OcrPackage.beside(label, "right", 8, 220, 0));
        assertEquals(new Rectangle(100, 73, 220, 23), OcrPackage.beside(label, "below", 8, 220, 0));
        assertEquals(new Rectangle(0, 46, 80, 23), OcrPackage.beside(label, "left", 20, 80, 0));
        assertEquals(new Rectangle(100, 17, 220, 25), OcrPackage.beside(label, "above", 8, 220, 25));
        assertEquals("OCR.BAD_INPUT", assertThrows(ActionException.class, () -> OcrPackage.beside(label, "inside", 8, 220, 0)).code());
    }
}
