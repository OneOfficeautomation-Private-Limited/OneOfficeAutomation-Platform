package com.oneofficeautomation.packages.office;

import com.oneofficeautomation.sdk.ActionContext;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Shared helpers for calling Office actions directly in unit tests. */
final class OfficeTestSupport {
    private OfficeTestSupport() {}

    static ActionContext ctx(Path workDir) {
        return new ActionContext("run-1", "step-1", "default", workDir, new HashMap<>(), Map.of(), new AtomicBoolean(), List::of, null);
    }

    /** Writes a solid-colour PNG of the given pixel size and returns its path. */
    static Path png(Path dir, String name, int w, int h, Color colour) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(colour);
        g.fillRect(0, 0, w, h);
        g.dispose();
        Path p = dir.resolve(name);
        ImageIO.write(img, "png", p.toFile());
        return p;
    }
}
