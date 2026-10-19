package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;

import javax.imageio.ImageIO;
import java.awt.Robot;
import java.awt.event.InputEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.*;
import java.util.*;

/**
 * Finds a picture inside another picture or on the screen (template matching on a coarse-to-fine image pyramid),
 * for applications without accessible controls, such as Citrix sessions or legacy screens.
 */
@OoaPackage(id = "ooa.image", name = "Image recognition", version = "1.0.0", description = "Find, wait for and click images on the screen or inside image files.")
public final class ImagePackage {

    record Match(int x, int y, double score) {}

    static int[] gray(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int[] g = new int[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int rgb = img.getRGB(x, y);
            g[y * w + x] = (((rgb >> 16) & 255) * 299 + ((rgb >> 8) & 255) * 587 + (rgb & 255) * 114) / 1000;
        }
        return g;
    }

    static int[] shrink(int[] g, int w, int h, int f) {
        int nw = w / f, nh = h / f;
        int[] out = new int[nw * nh];
        for (int y = 0; y < nh; y++) for (int x = 0; x < nw; x++) {
            int s = 0;
            for (int dy = 0; dy < f; dy++) for (int dx = 0; dx < f; dx++) s += g[(y * f + dy) * w + x * f + dx];
            out[y * nw + x] = s / (f * f);
        }
        return out;
    }

    /** Mean absolute difference in 0..255 at one position, stopping early once it exceeds the limit. */
    static double mad(int[] img, int iw, int[] t, int tw, int th, int ox, int oy, double limit) {
        long sum = 0;
        long cap = (long) (limit * tw * th);
        for (int y = 0; y < th; y++) {
            int ib = (oy + y) * iw + ox, tb = y * tw;
            for (int x = 0; x < tw; x++) sum += Math.abs(img[ib + x] - t[tb + x]);
            if (sum > cap) return 256;
        }
        return (double) sum / (tw * th);
    }

    static Match find(BufferedImage image, BufferedImage template) {
        int iw = image.getWidth(), ih = image.getHeight(), tw = template.getWidth(), th = template.getHeight();
        if (tw > iw || th > ih) return null;
        int[] gi = gray(image), gt = gray(template);
        int f = Math.max(1, Math.min(4, Math.min(tw, th) / 8));
        List<int[]> candidates = new ArrayList<>();
        if (f > 1) {
            int[] si = shrink(gi, iw, ih, f), st = shrink(gt, tw, th, f);
            int siw = iw / f, sih = ih / f, stw = tw / f, sth = th / f;
            PriorityQueue<double[]> best = new PriorityQueue<>((a, b) -> Double.compare(b[0], a[0]));
            for (int y = 0; y <= sih - sth; y++) for (int x = 0; x <= siw - stw; x++) {
                double limit = best.size() < 8 ? 256 : best.peek()[0];
                double d = mad(si, siw, st, stw, sth, x, y, limit);
                if (d < limit) { best.add(new double[]{d, x, y}); if (best.size() > 8) best.poll(); }
            }
            for (double[] b : best) candidates.add(new int[]{(int) b[1] * f, (int) b[2] * f});
        } else candidates.add(null);
        Match bestMatch = null;
        for (int[] c : candidates) {
            int x0 = c == null ? 0 : Math.max(0, c[0] - f), x1 = c == null ? iw - tw : Math.min(iw - tw, c[0] + f);
            int y0 = c == null ? 0 : Math.max(0, c[1] - f), y1 = c == null ? ih - th : Math.min(ih - th, c[1] + f);
            for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) {
                double limit = bestMatch == null ? 256 : (1 - bestMatch.score()) * 255;
                double d = mad(gi, iw, gt, tw, th, x, y, limit);
                double score = 1 - d / 255;
                if (bestMatch == null || score > bestMatch.score()) bestMatch = new Match(x, y, score);
            }
        }
        return bestMatch;
    }

    private static BufferedImage load(ActionContext ctx, String path) {
        Path p = ctx.resolvePath(path);
        try {
            BufferedImage img = ImageIO.read(p.toFile());
            if (img == null) throw new ActionException("IMAGE.UNSUPPORTED", "Not a readable image: " + p, "Use PNG, JPG, BMP or GIF.");
            return img;
        } catch (IOException e) { throw new ActionException("FILE.NOT_FOUND", "Cannot read image " + p + ": " + e.getMessage(), "Check the path."); }
    }

    private static Map<String, Object> result(Match m, BufferedImage t, double tolerance) {
        boolean found = m != null && (1 - m.score()) * 100 <= tolerance;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("found", found);
        out.put("x", found ? BigDecimal.valueOf(m.x() + t.getWidth() / 2) : null);
        out.put("y", found ? BigDecimal.valueOf(m.y() + t.getHeight() / 2) : null);
        out.put("similarity", m == null ? BigDecimal.ZERO : BigDecimal.valueOf(m.score() * 100).setScale(1, RoundingMode.HALF_UP));
        return out;
    }

    private static final String TOL = "Allowed difference in percent (0 = pixel-exact); raise for anti-aliased or scaled screens.";

    @Action(id = "find_in_image", name = "Find image in image", category = "Image", idempotent = true,
            description = "Look for a small picture inside a larger image file; returns the centre of the best match.")
    @Output(name = "found", type = OoaType.BOOLEAN, description = "True when a match within the tolerance was found.")
    @Output(name = "x", type = OoaType.NUMBER, description = "Centre x of the match.")
    @Output(name = "y", type = OoaType.NUMBER, description = "Centre y of the match.")
    @Output(name = "similarity", type = OoaType.NUMBER, description = "Similarity of the best match in percent.")
    public Map<String, Object> findInImage(ActionContext ctx,
            @Input(name = "image", type = OoaType.FILE, description = "Image to search in.") String image,
            @Input(name = "template", type = OoaType.FILE, description = "Picture to look for.") String template,
            @Input(name = "tolerance", type = OoaType.NUMBER, required = false, defaultValue = "10", description = TOL) double tol) {
        BufferedImage t = load(ctx, template);
        return result(find(load(ctx, image), t), t, tol);
    }

    @Action(id = "find_on_screen", name = "Find image on screen", category = "Image", capabilities = "desktop",
            description = "Look for a picture on the screen; returns the centre of the best match.")
    @Output(name = "found", type = OoaType.BOOLEAN, description = "True when found.")
    @Output(name = "x", type = OoaType.NUMBER, description = "Centre x on screen.")
    @Output(name = "y", type = OoaType.NUMBER, description = "Centre y on screen.")
    @Output(name = "similarity", type = OoaType.NUMBER, description = "Similarity in percent.")
    public Map<String, Object> findOnScreen(ActionContext ctx,
            @Input(name = "template", type = OoaType.FILE, description = "Picture to look for.") String template,
            @Input(name = "tolerance", type = OoaType.NUMBER, required = false, defaultValue = "10", description = TOL) double tol) {
        BufferedImage t = load(ctx, template);
        return result(find(DesktopPackage.screen(), t), t, tol);
    }

    @Action(id = "wait_for_image", name = "Wait for image", category = "Image", capabilities = "desktop", timeoutSeconds = 3600,
            description = "Wait until a picture appears on the screen (IMAGE.NOT_FOUND after the timeout).")
    @Output(name = "x", type = OoaType.NUMBER, description = "Centre x on screen.")
    @Output(name = "y", type = OoaType.NUMBER, description = "Centre y on screen.")
    public Map<String, Object> waitForImage(ActionContext ctx,
            @Input(name = "template", type = OoaType.FILE, description = "Picture to wait for.") String template,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "30", description = "Maximum wait.") double timeout,
            @Input(name = "tolerance", type = OoaType.NUMBER, required = false, defaultValue = "10", description = TOL) double tol) {
        BufferedImage t = load(ctx, template);
        long end = System.nanoTime() + (long) (timeout * 1e9);
        while (true) {
            Map<String, Object> r = result(find(DesktopPackage.screen(), t), t, tol);
            if (Boolean.TRUE.equals(r.get("found"))) return Out.of("x", r.get("x"), "y", r.get("y"));
            if (System.nanoTime() > end) throw new ActionException("IMAGE.NOT_FOUND", "Image did not appear within " + timeout + " s (best similarity " + r.get("similarity") + "%)",
                    "Check the screen resolution and scaling match the captured picture, or raise the tolerance.", true);
            ctx.checkCancelled();
            try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new OoaException("RUN.CANCELLED", "Interrupted"); }
        }
    }

    @Action(id = "click_image", name = "Click image", category = "Image", capabilities = "desktop", description = "Find a picture on the screen and click its centre.")
    public Map<String, Object> clickImage(ActionContext ctx,
            @Input(name = "template", type = OoaType.FILE, description = "Picture to click.") String template,
            @Input(name = "double_click", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Double-click.") boolean dbl,
            @Input(name = "tolerance", type = OoaType.NUMBER, required = false, defaultValue = "10", description = TOL) double tol) {
        BufferedImage t = load(ctx, template);
        Map<String, Object> r = result(find(DesktopPackage.screen(), t), t, tol);
        if (!Boolean.TRUE.equals(r.get("found")))
            throw new ActionException("IMAGE.NOT_FOUND", "Image not found on screen (best similarity " + r.get("similarity") + "%)", "Check the window is visible and not covered, or raise the tolerance.", true);
        Robot robot = DesktopSupport.robot();
        robot.mouseMove(((BigDecimal) r.get("x")).intValue(), ((BigDecimal) r.get("y")).intValue());
        for (int i = 0; i < (dbl ? 2 : 1); i++) { robot.mousePress(InputEvent.BUTTON1_DOWN_MASK); robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK); robot.delay(40); }
        return Map.of();
    }

    @Condition(id = "image_on_screen", name = "Image is on screen", description = "True when the picture is visible on the screen within the tolerance.")
    public boolean imageOnScreen(ActionContext ctx,
            @Input(name = "template", type = OoaType.FILE, description = "Picture to look for.") String template,
            @Input(name = "tolerance", type = OoaType.NUMBER, required = false, defaultValue = "10", description = TOL) double tol) {
        BufferedImage t = load(ctx, template);
        return Boolean.TRUE.equals(result(find(DesktopPackage.screen(), t), t, tol).get("found"));
    }

    @Condition(id = "image_in_image", name = "Image is in image file", description = "True when the picture appears inside an image file.")
    public boolean imageInImage(ActionContext ctx,
            @Input(name = "image", type = OoaType.FILE, description = "Image to search in.") String image,
            @Input(name = "template", type = OoaType.FILE, description = "Picture to look for.") String template,
            @Input(name = "tolerance", type = OoaType.NUMBER, required = false, defaultValue = "10", description = TOL) double tol) {
        BufferedImage t = load(ctx, template);
        return Boolean.TRUE.equals(result(find(load(ctx, image), t), t, tol).get("found"));
    }
}
