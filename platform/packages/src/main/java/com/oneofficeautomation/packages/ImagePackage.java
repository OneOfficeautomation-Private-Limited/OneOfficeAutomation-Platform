package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;

import javax.imageio.ImageIO;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
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
            long s = 0;
            for (int dy = 0; dy < f; dy++) for (int dx = 0; dx < f; dx++) s += g[(y * f + dy) * w + x * f + dx];
            out[y * nw + x] = (int) (s / (f * f));
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
        if (template.getWidth() > image.getWidth() || template.getHeight() > image.getHeight()) return null;
        return find(gray(image), image.getWidth(), image.getHeight(), gray(template), template.getWidth(), template.getHeight());
    }

    static Match find(int[] gi, int iw, int ih, int[] gt, int tw, int th) {
        if (tw > iw || th > ih) return null;
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

    /** Value written over an accepted match so the next search cannot find the same place again. */
    private static final int MASK = 100_000;

    /**
     * Every non-overlapping match within the tolerance (at most max), in reading order: top to bottom, and left to right
     * for matches on roughly the same line.
     */
    static List<Match> findAll(BufferedImage image, BufferedImage template, double tolerance, int max) {
        int iw = image.getWidth(), ih = image.getHeight(), tw = template.getWidth(), th = template.getHeight();
        List<Match> out = new ArrayList<>();
        if (tw > iw || th > ih) return out;
        int[] gi = gray(image), gt = gray(template);
        while (out.size() < max) {
            Match m = find(gi, iw, ih, gt, tw, th);
            if (m == null || (1 - m.score()) * 100 > tolerance) break;
            out.add(m);
            for (int y = m.y(); y < m.y() + th; y++) Arrays.fill(gi, y * iw + m.x(), y * iw + m.x() + tw, MASK);
        }
        int band = Math.max(1, th / 2);
        out.sort(Comparator.<Match>comparingInt(m -> m.y() / band).thenComparingInt(Match::x));
        return out;
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

    /** A search area inside an image or on the screen; null when x, y, width and height are all empty. */
    static Rectangle region(Integer x, Integer y, Integer w, Integer h) {
        if (x == null && y == null && w == null && h == null) return null;
        if (x == null || y == null || w == null || h == null || w <= 0 || h <= 0)
            throw new ActionException("IMAGE.BAD_REGION", "A search region needs x, y, width and height, with width and height above 0", "Set all four, or none to search everywhere.");
        return new Rectangle(x, y, w, h);
    }

    private static BufferedImage crop(BufferedImage img, Rectangle r) {
        if (r == null) return img;
        Rectangle c = r.intersection(new Rectangle(0, 0, img.getWidth(), img.getHeight()));
        if (c.isEmpty()) throw new ActionException("IMAGE.BAD_REGION", "The search region lies outside the image (" + img.getWidth() + "x" + img.getHeight() + ")", "Check the coordinates.");
        BufferedImage out = new BufferedImage(c.width, c.height, BufferedImage.TYPE_INT_RGB);
        var g = out.createGraphics(); g.drawImage(img.getSubimage(c.x, c.y, c.width, c.height), 0, 0, null); g.dispose();
        return out;
    }

    private static BufferedImage screen(Rectangle r) {
        if (r == null) return DesktopPackage.screen();
        Rectangle c = r.intersection(new Rectangle(Toolkit.getDefaultToolkit().getScreenSize()));
        if (c.isEmpty()) throw new ActionException("IMAGE.BAD_REGION", "The search region lies outside the screen", "Check the coordinates.");
        return DesktopSupport.robot().createScreenCapture(c);
    }

    /**
     * Searches an image for the n-th match and reports it in the coordinates of the full image or screen, with the
     * click point chosen by position and offsets.
     */
    static Map<String, Object> search(BufferedImage haystack, Rectangle region, BufferedImage t, double tol, int occurrence, String position, int offsetX, int offsetY) {
        if (occurrence < 1) throw new ActionException("IMAGE.BAD_OCCURRENCE", "Occurrence must be 1 or more", "Use 1 for the first match.");
        int dx = region == null ? 0 : Math.max(0, region.x), dy = region == null ? 0 : Math.max(0, region.y);
        Map<String, Object> out;
        int count;
        if (occurrence == 1) {
            Match m = find(haystack, t);
            out = result(m, t, tol);
            count = Boolean.TRUE.equals(out.get("found")) ? 1 : 0;
            if (count == 1) out = place(out, m, t, dx, dy, position, offsetX, offsetY);
        } else {
            List<Match> all = findAll(haystack, t, tol, Math.max(occurrence, 1));
            count = all.size();
            Match m = all.size() >= occurrence ? all.get(occurrence - 1) : null;
            out = result(m, t, m == null ? -1 : tol);
            if (m != null) out = place(out, m, t, dx, dy, position, offsetX, offsetY);
        }
        out.put("count", BigDecimal.valueOf(count));
        return out;
    }

    private static Map<String, Object> place(Map<String, Object> out, Match m, BufferedImage t, int dx, int dy, String position, int offsetX, int offsetY) {
        boolean corner = "top_left".equalsIgnoreCase(position);
        if (!corner && position != null && !position.equalsIgnoreCase("center"))
            throw new ActionException("IMAGE.BAD_POSITION", "Position must be center or top_left", "Use center or top_left.");
        int px = dx + m.x() + (corner ? 0 : t.getWidth() / 2) + offsetX, py = dy + m.y() + (corner ? 0 : t.getHeight() / 2) + offsetY;
        out.put("x", BigDecimal.valueOf(px));
        out.put("y", BigDecimal.valueOf(py));
        out.put("left", BigDecimal.valueOf(dx + m.x()));
        out.put("top", BigDecimal.valueOf(dy + m.y()));
        return out;
    }

    private static final String TOL = "Allowed difference in percent (0 = pixel-exact); raise for anti-aliased or scaled screens.";
    private static final String D_OCC = "Which match to use when the picture appears several times, counting in reading order from 1.";
    private static final String D_POS = "Point reported (and clicked): center of the match, or its top_left corner; offsets are added to it.";
    private static final String D_OX = "Pixels added to the reported x, e.g. to reach a field to the right of a label picture.";
    private static final String D_OY = "Pixels added to the reported y.";
    private static final String D_RX = "Search region left; the whole image or screen when the region is empty.";

    public Map<String, Object> findInImage(ActionContext ctx, String image, String template, double tol) {
        return findInImage(ctx, image, template, tol, 1, "center", 0, 0, null, null, null, null);
    }

    @Action(id = "find_in_image", name = "Find image in image", category = "Image", idempotent = true,
            description = "Look for a small picture inside a larger image file, optionally in a region and for its n-th appearance; returns the point of the match.")
    @Output(name = "found", type = OoaType.BOOLEAN, description = "True when a match within the tolerance was found.")
    @Output(name = "x", type = OoaType.NUMBER, description = "Centre x of the match (or the chosen point).")
    @Output(name = "y", type = OoaType.NUMBER, description = "Centre y of the match (or the chosen point).")
    @Output(name = "similarity", type = OoaType.NUMBER, description = "Similarity of the best match in percent.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of matches found (up to occurrence when it is above 1).")
    @Output(name = "left", type = OoaType.NUMBER, description = "Left edge of the match.")
    @Output(name = "top", type = OoaType.NUMBER, description = "Top edge of the match.")
    public Map<String, Object> findInImage(ActionContext ctx,
            @Input(name = "image", type = OoaType.FILE, description = "Image to search in.") String image,
            @Input(name = "template", type = OoaType.FILE, description = "Picture to look for.") String template,
            @Input(name = "tolerance", type = OoaType.NUMBER, required = false, defaultValue = "10", description = TOL) double tol,
            @Input(name = "occurrence", type = OoaType.NUMBER, required = false, defaultValue = "1", description = D_OCC) int occurrence,
            @Input(name = "position", type = OoaType.STRING, required = false, defaultValue = "\"center\"", enumValues = {"center", "top_left"}, description = D_POS) String position,
            @Input(name = "offset_x", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_OX) int offsetX,
            @Input(name = "offset_y", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_OY) int offsetY,
            @Input(name = "x", type = OoaType.NUMBER, required = false, description = D_RX) Integer rx,
            @Input(name = "y", type = OoaType.NUMBER, required = false, description = "Search region top.") Integer ry,
            @Input(name = "width", type = OoaType.NUMBER, required = false, description = "Search region width.") Integer rw,
            @Input(name = "height", type = OoaType.NUMBER, required = false, description = "Search region height.") Integer rh) {
        BufferedImage t = load(ctx, template);
        Rectangle r = region(rx, ry, rw, rh);
        return search(crop(load(ctx, image), r), r, t, tol, occurrence, position, offsetX, offsetY);
    }

    public Map<String, Object> findOnScreen(ActionContext ctx, String template, double tol) {
        return findOnScreen(ctx, template, tol, 1, "center", 0, 0, null, null, null, null);
    }

    @Action(id = "find_on_screen", name = "Find image on screen", category = "Image", capabilities = "desktop",
            description = "Look for a picture on the screen, optionally in a region and for its n-th appearance; returns the point of the match.")
    @Output(name = "found", type = OoaType.BOOLEAN, description = "True when found.")
    @Output(name = "x", type = OoaType.NUMBER, description = "Centre x on screen (or the chosen point).")
    @Output(name = "y", type = OoaType.NUMBER, description = "Centre y on screen (or the chosen point).")
    @Output(name = "similarity", type = OoaType.NUMBER, description = "Similarity in percent.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of matches found (up to occurrence when it is above 1).")
    public Map<String, Object> findOnScreen(ActionContext ctx,
            @Input(name = "template", type = OoaType.FILE, description = "Picture to look for.") String template,
            @Input(name = "tolerance", type = OoaType.NUMBER, required = false, defaultValue = "10", description = TOL) double tol,
            @Input(name = "occurrence", type = OoaType.NUMBER, required = false, defaultValue = "1", description = D_OCC) int occurrence,
            @Input(name = "position", type = OoaType.STRING, required = false, defaultValue = "\"center\"", enumValues = {"center", "top_left"}, description = D_POS) String position,
            @Input(name = "offset_x", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_OX) int offsetX,
            @Input(name = "offset_y", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_OY) int offsetY,
            @Input(name = "x", type = OoaType.NUMBER, required = false, description = D_RX) Integer rx,
            @Input(name = "y", type = OoaType.NUMBER, required = false, description = "Search region top.") Integer ry,
            @Input(name = "width", type = OoaType.NUMBER, required = false, description = "Search region width.") Integer rw,
            @Input(name = "height", type = OoaType.NUMBER, required = false, description = "Search region height.") Integer rh) {
        BufferedImage t = load(ctx, template);
        Rectangle r = region(rx, ry, rw, rh);
        return search(screen(r), r, t, tol, occurrence, position, offsetX, offsetY);
    }

    public Map<String, Object> waitForImage(ActionContext ctx, String template, double timeout, double tol) {
        return waitForImage(ctx, template, timeout, tol, 500, false, null, null, null, null);
    }

    @Action(id = "wait_for_image", name = "Wait for image", category = "Image", capabilities = "desktop", timeoutSeconds = 3600,
            description = "Wait until a picture appears on the screen, or disappears from it, checking a region at a chosen interval (IMAGE.NOT_FOUND after the timeout).")
    @Output(name = "x", type = OoaType.NUMBER, description = "Centre x on screen.")
    @Output(name = "y", type = OoaType.NUMBER, description = "Centre y on screen.")
    public Map<String, Object> waitForImage(ActionContext ctx,
            @Input(name = "template", type = OoaType.FILE, description = "Picture to wait for.") String template,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "30", description = "Maximum wait.") double timeout,
            @Input(name = "tolerance", type = OoaType.NUMBER, required = false, defaultValue = "10", description = TOL) double tol,
            @Input(name = "interval_ms", type = OoaType.NUMBER, required = false, defaultValue = "500", description = "Pause between two looks at the screen, in milliseconds.") int intervalMs,
            @Input(name = "until_gone", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Wait for the picture to disappear instead, e.g. a loading spinner.") boolean untilGone,
            @Input(name = "x", type = OoaType.NUMBER, required = false, description = D_RX) Integer rx,
            @Input(name = "y", type = OoaType.NUMBER, required = false, description = "Search region top.") Integer ry,
            @Input(name = "width", type = OoaType.NUMBER, required = false, description = "Search region width.") Integer rw,
            @Input(name = "height", type = OoaType.NUMBER, required = false, description = "Search region height.") Integer rh) {
        BufferedImage t = load(ctx, template);
        Rectangle r = region(rx, ry, rw, rh);
        long end = System.nanoTime() + (long) (timeout * 1e9);
        while (true) {
            Map<String, Object> res = search(screen(r), r, t, tol, 1, "center", 0, 0);
            boolean found = Boolean.TRUE.equals(res.get("found"));
            if (found != untilGone) return Out.of("x", res.get("x"), "y", res.get("y"));
            if (System.nanoTime() > end) {
                if (untilGone) throw new ActionException("IMAGE.STILL_VISIBLE", "Image was still on the screen after " + timeout + " s", "Raise the timeout or lower the tolerance.", true);
                throw new ActionException("IMAGE.NOT_FOUND", "Image did not appear within " + timeout + " s (best similarity " + res.get("similarity") + "%)",
                        "Check the screen resolution and scaling match the captured picture, or raise the tolerance.", true);
            }
            ctx.checkCancelled();
            try { Thread.sleep(Math.max(50, intervalMs)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new OoaException("RUN.CANCELLED", "Interrupted"); }
        }
    }

    public Map<String, Object> clickImage(ActionContext ctx, String template, boolean dbl, double tol) {
        return clickImage(ctx, template, dbl, tol, "left", 1, "center", 0, 0, 0, null, null, null, null);
    }

    @Action(id = "click_image", name = "Click image", category = "Image", capabilities = "desktop",
            description = "Find a picture on the screen (optionally waiting for it, in a region, or its n-th appearance) and click it with the chosen button at its centre, corner or an offset.")
    @Output(name = "x", type = OoaType.NUMBER, description = "Clicked x.")
    @Output(name = "y", type = OoaType.NUMBER, description = "Clicked y.")
    public Map<String, Object> clickImage(ActionContext ctx,
            @Input(name = "template", type = OoaType.FILE, description = "Picture to click.") String template,
            @Input(name = "double_click", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Double-click.") boolean dbl,
            @Input(name = "tolerance", type = OoaType.NUMBER, required = false, defaultValue = "10", description = TOL) double tol,
            @Input(name = "button", type = OoaType.STRING, required = false, defaultValue = "\"left\"", enumValues = {"left", "right", "middle"}, description = "Mouse button to click with.") String button,
            @Input(name = "occurrence", type = OoaType.NUMBER, required = false, defaultValue = "1", description = D_OCC) int occurrence,
            @Input(name = "position", type = OoaType.STRING, required = false, defaultValue = "\"center\"", enumValues = {"center", "top_left"}, description = D_POS) String position,
            @Input(name = "offset_x", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_OX) int offsetX,
            @Input(name = "offset_y", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_OY) int offsetY,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", description = "Keep looking for up to this many seconds before failing; 0 looks once.") double timeout,
            @Input(name = "x", type = OoaType.NUMBER, required = false, description = D_RX) Integer rx,
            @Input(name = "y", type = OoaType.NUMBER, required = false, description = "Search region top.") Integer ry,
            @Input(name = "width", type = OoaType.NUMBER, required = false, description = "Search region width.") Integer rw,
            @Input(name = "height", type = OoaType.NUMBER, required = false, description = "Search region height.") Integer rh) {
        int mask = OcrPackage.button(button);
        BufferedImage t = load(ctx, template);
        Rectangle r = region(rx, ry, rw, rh);
        long end = System.nanoTime() + (long) (Math.max(0, timeout) * 1e9);
        Map<String, Object> res;
        while (true) {
            res = search(screen(r), r, t, tol, occurrence, position, offsetX, offsetY);
            if (Boolean.TRUE.equals(res.get("found"))) break;
            if (System.nanoTime() > end)
                throw new ActionException("IMAGE.NOT_FOUND", "Image not found on screen (best similarity " + res.get("similarity") + "%)", "Check the window is visible and not covered, or raise the tolerance.", true);
            ctx.checkCancelled();
            try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new OoaException("RUN.CANCELLED", "Interrupted"); }
        }
        Robot robot = DesktopSupport.robot();
        int cx = ((BigDecimal) res.get("x")).intValue(), cy = ((BigDecimal) res.get("y")).intValue();
        robot.mouseMove(cx, cy);
        for (int i = 0; i < (dbl ? 2 : 1); i++) { robot.mousePress(mask); robot.mouseRelease(mask); robot.delay(40); }
        return Out.of("x", res.get("x"), "y", res.get("y"));
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
