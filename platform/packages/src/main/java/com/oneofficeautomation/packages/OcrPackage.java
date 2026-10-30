package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.List;

/**
 * Optical character recognition with Tesseract (open source; install on the device, e.g. the UB Mannheim build on Windows).
 * Reads text from images, image URLs, PDFs rendered to images, or the live screen, and can locate and click words, which is how
 * Citrix and legacy applications without accessible controls are automated.
 */
@OoaPackage(id = "ooa.ocr", name = "OCR", version = "1.0.0", description = "Read text from images, image URLs or the screen, and find or click text on the screen (Tesseract).")
public final class OcrPackage {

    record Word(String text, int left, int top, int width, int height, int line, double conf) {}

    static String exe() {
        String env = System.getenv("OOA_TESSERACT");
        if (env != null && !env.isBlank()) return env;
        if (ProcessRunner.WINDOWS) for (String p : List.of("C:\\Program Files\\Tesseract-OCR\\tesseract.exe", "C:\\Program Files (x86)\\Tesseract-OCR\\tesseract.exe"))
            if (Files.isRegularFile(Path.of(p))) return p;
        return "tesseract";
    }

    static void checkLanguage(String lang) {
        if (lang == null || !lang.matches("^[a-z_+]{3,40}$")) throw new ActionException("OCR.BAD_LANGUAGE", "Invalid language '" + lang + "'", "Examples: eng, deu, eng+fra.");
    }

    private static ProcessRunner.Result tess(ActionContext ctx, Path image, String lang, int psm, boolean tsv) {
        checkLanguage(lang);
        List<String> cmd = new ArrayList<>(List.of(exe(), image.toString(), "stdout", "-l", lang, "--psm", String.valueOf(psm)));
        if (tsv) cmd.add("tsv");
        ProcessRunner.Result r;
        try { r = ProcessRunner.run(ctx, cmd, null, null, 300); }
        catch (ActionException e) {
            if ("PROCESS.NOT_FOUND".equals(e.code())) throw new ActionException("OCR.NOT_INSTALLED", "Tesseract OCR is not installed on this runner",
                    "Install Tesseract (on Windows the UB Mannheim installer) or set OOA_TESSERACT to tesseract.exe.");
            throw e;
        }
        if (r.exitCode() != 0) {
            if (r.stderr().contains("Failed loading language")) throw new ActionException("OCR.LANGUAGE_MISSING", "Tesseract language data '" + lang + "' is not installed", "Install the language pack for Tesseract.");
            throw new ActionException("OCR.FAILED", "Tesseract failed: " + ApplicationPackage.tail(r.stderr()), "Check the image is readable.");
        }
        return r;
    }

    static List<Word> words(String tsv) {
        List<Word> out = new ArrayList<>();
        for (String line : tsv.split("\\r?\\n")) {
            String[] c = line.split("\t", -1);
            if (c.length < 12 || !c[0].equals("5") || c[11].isBlank()) continue;
            try {
                int lineKey = Integer.parseInt(c[2]) * 1_000_000 + Integer.parseInt(c[3]) * 1000 + Integer.parseInt(c[4]);
                out.add(new Word(c[11], Integer.parseInt(c[6]), Integer.parseInt(c[7]), Integer.parseInt(c[8]), Integer.parseInt(c[9]), lineKey, Double.parseDouble(c[10])));
            } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    /** Finds a phrase as consecutive words on one line (case-insensitive); returns the bounding box or null. */
    static int[] locate(List<Word> ws, String phrase) { return locate(ws, phrase, 1); }

    /** The n-th (1-based) place a phrase appears, in reading order; null when it appears fewer times. */
    static int[] locate(List<Word> ws, String phrase, int occurrence) {
        List<int[]> all = locateAll(ws, phrase);
        return occurrence >= 1 && occurrence <= all.size() ? all.get(occurrence - 1) : null;
    }

    /** Every place a phrase appears as consecutive words on one line (case-insensitive), as {left, top, width, height}. */
    static List<int[]> locateAll(List<Word> ws, String phrase) {
        String[] target = phrase.trim().toLowerCase(Locale.ROOT).split("\\s+");
        List<int[]> out = new ArrayList<>();
        for (int i = 0; i + target.length <= ws.size(); i++) {
            boolean ok = true;
            for (int k = 0; k < target.length && ok; k++) {
                Word w = ws.get(i + k);
                String t = w.text().toLowerCase(Locale.ROOT).replaceAll("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$", "");
                ok = w.line() == ws.get(i).line() && (k == target.length - 1 ? t.startsWith(target[k].replaceAll("[^\\p{L}\\p{N}]", "")) : t.equals(target[k].replaceAll("[^\\p{L}\\p{N}]", "")));
            }
            if (ok) {
                int l = Integer.MAX_VALUE, t = Integer.MAX_VALUE, r = 0, b = 0;
                for (int k = 0; k < target.length; k++) { Word w = ws.get(i + k); l = Math.min(l, w.left()); t = Math.min(t, w.top()); r = Math.max(r, w.left() + w.width()); b = Math.max(b, w.top() + w.height()); }
                out.add(new int[]{l, t, r - l, b - t});
            }
        }
        return out;
    }

    /**
     * Keeps the text between two markers and trims it: after a non-empty "after" marker (first occurrence) and before a
     * non-empty "before" marker (first occurrence after that); empty when a marker is missing.
     */
    public static String filterText(String text, String after, String before, boolean trim) {
        String t = text == null ? "" : text;
        if (after != null && !after.isEmpty()) {
            int i = t.indexOf(after);
            t = i < 0 ? "" : t.substring(i + after.length());
        }
        if (before != null && !before.isEmpty()) {
            int i = t.indexOf(before);
            t = i < 0 ? "" : t.substring(0, i);
        }
        return trim ? t.strip() : t;
    }

    /** Crops (when a region is given), scales up and optionally turns the image grey or black and white before OCR. */
    static final double MAX_OCR_PIXELS = 25_000_000;

    static BufferedImage prepare(BufferedImage img, Integer x, Integer y, Integer w, Integer h, double scale, String mode) {
        BufferedImage src = img;
        if (x != null || y != null || w != null || h != null) {
            if (x == null || y == null || w == null || h == null || w <= 0 || h <= 0)
                throw new ActionException("OCR.BAD_REGION", "A region needs x, y, width and height, with width and height above 0", "Set all four, or none for the whole image.");
            Rectangle r = new Rectangle(x, y, w, h).intersection(new Rectangle(0, 0, img.getWidth(), img.getHeight()));
            if (r.isEmpty()) throw new ActionException("OCR.BAD_REGION", "The region lies outside the image (" + img.getWidth() + "x" + img.getHeight() + ")", "Check the coordinates.");
            src = img.getSubimage(r.x, r.y, r.width, r.height);
        }
        if (scale < 1 || scale > 5) throw new ActionException("OCR.BAD_SCALE", "Scale must be between 1 and 5", "Use 2 or 3 for small text.");
        String m = mode == null ? "none" : mode.toLowerCase(Locale.ROOT);
        int type = switch (m) {
            case "none" -> BufferedImage.TYPE_INT_RGB;
            case "gray", "grey" -> BufferedImage.TYPE_BYTE_GRAY;
            case "black_white" -> BufferedImage.TYPE_BYTE_BINARY;
            default -> throw new ActionException("OCR.BAD_MODE", "Image mode must be none, gray or black_white", "Use none, gray or black_white.");
        };
        // a whole multi-monitor screen scaled up can need more memory than the runner has: keep the enlarged image
        // at about 25 megapixels, which is still far more detail than OCR needs
        double pixels = (double) src.getWidth() * src.getHeight();
        if (pixels * scale * scale > MAX_OCR_PIXELS) scale = Math.max(1, Math.sqrt(MAX_OCR_PIXELS / pixels));
        int nw = (int) Math.round(src.getWidth() * scale), nh = (int) Math.round(src.getHeight() * scale);
        BufferedImage out = new BufferedImage(nw, nh, type == BufferedImage.TYPE_BYTE_BINARY ? BufferedImage.TYPE_BYTE_GRAY : type);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, nw, nh);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(src, 0, 0, nw, nh, null);
        g.dispose();
        if (type == BufferedImage.TYPE_BYTE_BINARY) {  // threshold at the mean brightness
            long sum = 0;
            var raster = out.getRaster();
            for (int yy = 0; yy < nh; yy++) for (int xx = 0; xx < nw; xx++) sum += raster.getSample(xx, yy, 0);
            int thr = (int) (sum / Math.max(1, (long) nw * nh));
            BufferedImage bw = new BufferedImage(nw, nh, BufferedImage.TYPE_BYTE_BINARY);
            for (int yy = 0; yy < nh; yy++) for (int xx = 0; xx < nw; xx++) bw.setRGB(xx, yy, raster.getSample(xx, yy, 0) >= thr ? 0xFFFFFF : 0);
            out = bw;
        }
        return out;
    }

    private static Path temp(BufferedImage img) {
        try { Path p = Files.createTempFile("ooa-ocr-", ".png"); ImageIO.write(img, "png", p.toFile()); return p; }
        catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), null, true); }
    }

    private static BufferedImage capture(Integer x, Integer y, Integer w, Integer h) {
        Robot robot = DesktopSupport.robot();
        Rectangle area = x == null || y == null || w == null || h == null ? new Rectangle(Toolkit.getDefaultToolkit().getScreenSize()) : new Rectangle(x, y, w, h);
        return robot.createScreenCapture(area);
    }

    private static Path capture(ActionContext ctx, Integer x, Integer y, Integer w, Integer h, int scale) {
        return temp(prepare(capture(x, y, w, h), null, null, null, null, scale, "none"));
    }

    private static BufferedImage readFile(Path p) {
        if (!Files.isRegularFile(p)) throw new ActionException("FILE.NOT_FOUND", "Image not found: " + p, "Check the path.");
        try {
            BufferedImage img = ImageIO.read(p.toFile());
            if (img == null) throw new ActionException("OCR.UNSUPPORTED_IMAGE", "Not a readable image: " + p, "Use PNG, JPG, BMP or GIF.");
            return img;
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", "Cannot read " + p + ": " + e.getMessage(), "Check the file permissions.", true); }
    }

    /** OCR of an image in memory, such as a rendered PDF page (the PDF toolkit's read_text_ocr), then the marker filter. */
    public static String readText(ActionContext ctx, BufferedImage img, String lang, int psm, String after, String before, boolean trim) {
        return filterText(ocr(ctx, img, lang, psm), after, before, trim);
    }

    /** OCR of an image after the optional preparation; the temporary file is always removed. */
    private static String ocr(ActionContext ctx, BufferedImage img, String lang, int psm) {
        checkLanguage(lang);
        Path p = temp(img);
        try { return tess(ctx, p, lang, psm, false).stdout(); }
        finally { try { Files.deleteIfExists(p); } catch (IOException ignored) { } }
    }

    private static boolean plain(Integer x, Integer y, Integer w, Integer h, double scale, String mode) {
        return x == null && y == null && w == null && h == null && scale == 1 && (mode == null || mode.equalsIgnoreCase("none"));
    }

    private static final String D_LANG = "Tesseract language(s), e.g. eng, deu or eng+fra.";
    private static final String D_PSM = "Page layout mode: 3 automatic, 6 single block, 7 single line, 11 sparse text.";
    private static final String D_AFTER = "Keep only the text after the first occurrence of this marker, e.g. Invoice number:.";
    private static final String D_BEFORE = "Keep only the text before this marker (searched after the after_text marker).";
    private static final String D_TRIM = "Remove spaces and line breaks at the start and end of the result.";
    private static final String D_SCALE = "Enlarge the image by this factor (1 to 5) before reading; 2 or 3 helps with small text.";
    private static final String D_MODE = "Prepare the image: none, gray, or black_white (a simple threshold that often helps with coloured backgrounds).";

    public Map<String, Object> readImage(ActionContext ctx, String path, String lang, int psm) {
        return readImage(ctx, path, lang, psm, null, null, true, 1, "none", null, null, null, null);
    }

    @Action(id = "read_image", name = "Read text from image", category = "OCR", idempotent = true, timeoutSeconds = 300, capabilities = "filesystem",
            description = "Extract text from an image file (PNG, JPG, TIFF, BMP), or from a region of it, optionally keeping only the text between two markers.",
            panel = "path, [Keep only part of the text: after_text, before_text], trim, language | [Read only an area: x, y, width, height], page_mode, scale, image_mode")
    @Output(name = "text", type = OoaType.STRING, description = "Recognised text.")
    public Map<String, Object> readImage(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "Image file.") String path,
            @Input(name = "language", type = OoaType.STRING, required = false, defaultValue = "\"eng\"", description = D_LANG) String lang,
            @Input(name = "page_mode", type = OoaType.NUMBER, required = false, defaultValue = "3", min = 0, max = 13, description = D_PSM) int psm,
            @Input(name = "after_text", type = OoaType.STRING, required = false, description = D_AFTER) String after,
            @Input(name = "before_text", type = OoaType.STRING, required = false, description = D_BEFORE) String before,
            @Input(name = "trim", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = D_TRIM) boolean trim,
            @Input(name = "scale", type = OoaType.NUMBER, required = false, defaultValue = "1", min = 1, max = 5, description = D_SCALE) double scale,
            @Input(name = "image_mode", type = OoaType.STRING, required = false, defaultValue = "\"none\"", enumValues = {"none", "gray", "black_white"}, description = D_MODE) String mode,
            @Input(name = "x", type = OoaType.NUMBER, required = false, description = "Region left in pixels; the whole image when the region is empty.") Integer x,
            @Input(name = "y", type = OoaType.NUMBER, required = false, description = "Region top in pixels.") Integer y,
            @Input(name = "width", type = OoaType.NUMBER, required = false, description = "Region width in pixels.") Integer w,
            @Input(name = "height", type = OoaType.NUMBER, required = false, description = "Region height in pixels.") Integer h) {
        Path p = ctx.resolvePath(path);
        if (!Files.isRegularFile(p)) throw new ActionException("FILE.NOT_FOUND", "Image not found: " + p, "Check the path.");
        String raw = plain(x, y, w, h, scale, mode) ? tess(ctx, p, lang, psm, false).stdout() : ocr(ctx, prepare(readFile(p), x, y, w, h, scale, mode), lang, psm);
        return Map.of("text", filterText(raw, after, before, trim));
    }

    /** Downloads an image over http or https, refusing other schemes and anything that is not an image. */
    static BufferedImage download(String url, int timeoutSeconds) {
        URI u;
        try { u = URI.create(url.trim()); } catch (IllegalArgumentException e) { throw new ActionException("OCR.BAD_URL", "'" + url + "' is not a URL", "Use an http or https address."); }
        if (u.getScheme() == null || !(u.getScheme().equalsIgnoreCase("http") || u.getScheme().equalsIgnoreCase("https")))
            throw new ActionException("OCR.BAD_URL", "Only http and https image addresses are supported", "Download the file first for other locations.");
        try {
            HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(timeoutSeconds)).build();
            HttpResponse<byte[]> r = c.send(HttpRequest.newBuilder(u).timeout(Duration.ofSeconds(timeoutSeconds)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            if (r.statusCode() / 100 != 2) throw new ActionException("OCR.DOWNLOAD_FAILED", "The server answered " + r.statusCode() + " for " + u, "Check the address is reachable without signing in.", r.statusCode() >= 500);
            BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(r.body()));
            if (img == null) throw new ActionException("OCR.UNSUPPORTED_IMAGE", "The address did not return a readable image", "Use the direct address of a PNG, JPG, BMP or GIF image.");
            return img;
        } catch (IOException e) {
            throw new ActionException("OCR.DOWNLOAD_FAILED", "Cannot download " + u + ": " + e.getMessage(), "Check the address and the network.", true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ActionException("RUN.CANCELLED", "Interrupted");
        }
    }

    @Action(id = "read_url", name = "Read text from image URL", category = "OCR", idempotent = true, timeoutSeconds = 300, capabilities = "network",
            description = "Download an image from an http or https address and extract its text, optionally keeping only the text between two markers.",
            panel = "url, [Keep only part of the text: after_text, before_text], trim, language | page_mode, scale, image_mode, timeout_seconds")
    @Output(name = "text", type = OoaType.STRING, description = "Recognised text.")
    public Map<String, Object> readUrl(ActionContext ctx,
            @Input(name = "url", type = OoaType.STRING, example = "https://example.com/scan.png", description = "Address of the image.") String url,
            @Input(name = "language", type = OoaType.STRING, required = false, defaultValue = "\"eng\"", description = D_LANG) String lang,
            @Input(name = "page_mode", type = OoaType.NUMBER, required = false, defaultValue = "3", min = 0, max = 13, description = D_PSM) int psm,
            @Input(name = "after_text", type = OoaType.STRING, required = false, description = D_AFTER) String after,
            @Input(name = "before_text", type = OoaType.STRING, required = false, description = D_BEFORE) String before,
            @Input(name = "trim", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = D_TRIM) boolean trim,
            @Input(name = "scale", type = OoaType.NUMBER, required = false, defaultValue = "1", min = 1, max = 5, description = D_SCALE) double scale,
            @Input(name = "image_mode", type = OoaType.STRING, required = false, defaultValue = "\"none\"", enumValues = {"none", "gray", "black_white"}, description = D_MODE) String mode,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "60", min = 1, description = "Maximum time for the download.") int timeout) {
        checkLanguage(lang);
        BufferedImage img = download(url, Math.max(1, timeout));
        return Map.of("text", filterText(ocr(ctx, prepare(img, null, null, null, null, scale, mode), lang, psm), after, before, trim));
    }

    public Map<String, Object> readScreen(ActionContext ctx, Integer x, Integer y, Integer w, Integer h, String lang, int psm) {
        return readScreen(ctx, x, y, w, h, lang, psm, null, null, true, 2, "none", 0);
    }

    /** Read text from screen without a window (the signature before window was added). */
    public Map<String, Object> readScreen(ActionContext ctx, Integer x, Integer y, Integer w, Integer h, String lang, int psm,
            String after, String before, boolean trim, double scale, String mode, int waitMs) {
        return readScreen(ctx, x, y, w, h, lang, psm, after, before, trim, scale, mode, waitMs, null);
    }

    @Action(id = "read_screen", name = "Read text from screen", category = "OCR", timeoutSeconds = 300, capabilities = "desktop",
            description = "Capture the screen (or a region) and extract its text, optionally keeping only the text between two markers; for Citrix and applications without accessible controls.",
            panel = "(Read from: The whole screen = / One window = window), [Read only an area: x, y, width, height], wait_ms, [Keep only part of the text: after_text, before_text], trim, language | page_mode, scale, image_mode")
    @Output(name = "text", type = OoaType.STRING, description = "Recognised text.")
    public Map<String, Object> readScreen(ActionContext ctx,
            @Input(name = "x", type = OoaType.NUMBER, required = false, description = "Region left (whole screen when empty).") Integer x,
            @Input(name = "y", type = OoaType.NUMBER, required = false, description = "Region top.") Integer y,
            @Input(name = "width", type = OoaType.NUMBER, required = false, description = "Region width.") Integer w,
            @Input(name = "height", type = OoaType.NUMBER, required = false, description = "Region height.") Integer h,
            @Input(name = "language", type = OoaType.STRING, required = false, defaultValue = "\"eng\"", description = D_LANG) String lang,
            @Input(name = "page_mode", type = OoaType.NUMBER, required = false, defaultValue = "6", min = 0, max = 13, description = D_PSM) int psm,
            @Input(name = "after_text", type = OoaType.STRING, required = false, description = D_AFTER) String after,
            @Input(name = "before_text", type = OoaType.STRING, required = false, description = D_BEFORE) String before,
            @Input(name = "trim", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = D_TRIM) boolean trim,
            @Input(name = "scale", type = OoaType.NUMBER, required = false, defaultValue = "2", min = 1, max = 5, description = D_SCALE) double scale,
            @Input(name = "image_mode", type = OoaType.STRING, required = false, defaultValue = "\"none\"", enumValues = {"none", "gray", "black_white"}, description = D_MODE) String mode,
            @Input(name = "wait_ms", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0, description = "Wait this many milliseconds before capturing, e.g. 1000 for a slow remote session.") int waitMs,
            @Input(name = "window", type = OoaType.STRING, required = false, example = "Invoice entry*",
                    description = "Read from this window (title text, a pattern with * or ?, or re:regex): it is brought to the front and x, y are measured from its top-left corner; the whole window when the area is empty.") String window) {
        checkLanguage(lang);
        boolean inWindow = window != null && !window.isBlank();
        if (inWindow) checkWindowArea(x, y, w, h);   // before the window is touched
        int[] wb = inWindow ? DesktopPackage.focusWindow(window, true) : null;
        pause(ctx, Math.max(waitMs, inWindow ? 200 : 0));   // a window brought to the front needs a moment to repaint
        BufferedImage shot;
        if (inWindow) { Rectangle r = windowRegion(x, y, w, h, wb); shot = capture(r.x, r.y, r.width, r.height); }
        else shot = capture(x, y, w, h);
        return Map.of("text", filterText(ocr(ctx, prepare(shot, null, null, null, null, scale, mode), lang, psm), after, before, trim));
    }

    /**
     * The screen rectangle to read inside a window {left, top, width, height}: the area measured from the window's
     * top-left corner, or the whole window when the area is empty. A partly given area fills the rest from the window.
     */
    static Rectangle windowRegion(Integer x, Integer y, Integer w, Integer h, int[] window) {
        checkWindowArea(x, y, w, h);
        int ox = x == null ? 0 : x, oy = y == null ? 0 : y;
        int width = w == null ? window[2] - ox : w, height = h == null ? window[3] - oy : h;
        if (width <= 0 || height <= 0)
            throw new ActionException("OCR.BAD_REGION", "The area starts outside the window (" + window[2] + "x" + window[3] + ")", "Check x and y against the window's size.");
        return new Rectangle(window[0] + ox, window[1] + oy, width, height);
    }

    /** An area inside a window: positive width and height, x and y not negative. */
    static void checkWindowArea(Integer x, Integer y, Integer w, Integer h) {
        if ((w != null && w <= 0) || (h != null && h <= 0))
            throw new ActionException("OCR.BAD_REGION", "The area's width and height must be above 0", "Set a positive width and height, or leave the area empty for the whole window.");
        if ((x != null && x < 0) || (y != null && y < 0))
            throw new ActionException("OCR.BAD_REGION", "x and y inside a window must be 0 or more", "Measure x and y from the window's top-left corner.");
    }

    private static void pause(ActionContext ctx, int ms) {
        if (ms <= 0) return;
        long end = System.nanoTime() + ms * 1_000_000L;
        while (System.nanoTime() < end) {
            ctx.checkCancelled();
            try { Thread.sleep(Math.min(100, Math.max(1, (end - System.nanoTime()) / 1_000_000))); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new ActionException("RUN.CANCELLED", "Interrupted"); }
        }
    }

    public Map<String, Object> findInImage(ActionContext ctx, String path, String text, String lang) { return findInImage(ctx, path, text, lang, 1); }

    @Action(id = "find_text_in_image", name = "Find text in image", category = "OCR", idempotent = true, timeoutSeconds = 300, capabilities = "filesystem",
            description = "Locate a word or phrase in an image, or its n-th appearance; returns whether it was found, its centre and how often it appears.",
            panel = "path, text, occurrence | language")
    @Output(name = "found", type = OoaType.BOOLEAN, description = "True when found.")
    @Output(name = "x", type = OoaType.NUMBER, description = "Centre x.")
    @Output(name = "y", type = OoaType.NUMBER, description = "Centre y.")
    @Output(name = "count", type = OoaType.NUMBER, description = "How many times the phrase appears in the image.")
    public Map<String, Object> findInImage(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "Image file.") String path,
            @Input(name = "text", type = OoaType.STRING, description = "Word or phrase to find.") String text,
            @Input(name = "language", type = OoaType.STRING, required = false, defaultValue = "\"eng\"", description = D_LANG) String lang,
            @Input(name = "occurrence", type = OoaType.NUMBER, required = false, defaultValue = "1", min = 1, description = "Which appearance to return, counting in reading order from 1.") int occurrence) {
        List<int[]> all = locateAll(words(tess(ctx, ctx.resolvePath(path), lang, 11, true).stdout()), text);
        int[] box = occurrence >= 1 && occurrence <= all.size() ? all.get(occurrence - 1) : null;
        BigDecimal count = BigDecimal.valueOf(all.size());
        return box == null ? Out.of("found", false, "x", null, "y", null, "count", count)
                : Out.of("found", true, "x", BigDecimal.valueOf(box[0] + box[2] / 2), "y", BigDecimal.valueOf(box[1] + box[3] / 2), "count", count);
    }

    /** Mouse button mask for left, right or middle. */
    static int button(String button) {
        return switch (button == null ? "left" : button.toLowerCase(Locale.ROOT)) {
            case "left" -> InputEvent.BUTTON1_DOWN_MASK;
            case "right" -> InputEvent.BUTTON3_DOWN_MASK;
            case "middle" -> InputEvent.BUTTON2_DOWN_MASK;
            default -> throw new ActionException("DESKTOP.BAD_BUTTON", "Button must be left, right or middle, not '" + button + "'", "Use left, right or middle.");
        };
    }

    public Map<String, Object> clickText(ActionContext ctx, String text, boolean dbl, String lang) { return clickText(ctx, text, dbl, lang, 1, "left", 0, 0); }

    @Action(id = "click_text", name = "Click text on screen", category = "OCR", timeoutSeconds = 300, capabilities = "desktop",
            description = "Find a word or phrase (or its n-th appearance) on the screen with OCR and click it with the chosen button, optionally offset from its centre (OCR.TEXT_NOT_FOUND when absent).",
            panel = "text, double_click, occurrence | button, offset_x, offset_y, language")
    @Output(name = "x", type = OoaType.NUMBER, description = "Clicked x.")
    @Output(name = "y", type = OoaType.NUMBER, description = "Clicked y.")
    public Map<String, Object> clickText(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Word or phrase to click.") String text,
            @Input(name = "double_click", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Double-click.") boolean dbl,
            @Input(name = "language", type = OoaType.STRING, required = false, defaultValue = "\"eng\"", description = D_LANG) String lang,
            @Input(name = "occurrence", type = OoaType.NUMBER, required = false, defaultValue = "1", min = 1, description = "Which appearance to click, counting in reading order from 1.") int occurrence,
            @Input(name = "button", type = OoaType.STRING, required = false, defaultValue = "\"left\"", enumValues = {"left", "right", "middle"}, description = "Mouse button to click with.") String button,
            @Input(name = "offset_x", type = OoaType.NUMBER, required = false, defaultValue = "0", description = "Pixels to the right (negative: left) of the text's centre to click, e.g. to hit a field next to a label.") int offsetX,
            @Input(name = "offset_y", type = OoaType.NUMBER, required = false, defaultValue = "0", description = "Pixels below (negative: above) the text's centre to click.") int offsetY) {
        int mask = button(button);
        Path img = capture(ctx, null, null, null, null, 2);
        int[] box;
        try { box = locate(words(tess(ctx, img, lang, 11, true).stdout()), text, occurrence); }
        finally { try { Files.deleteIfExists(img); } catch (IOException ignored) { } }
        if (box == null) throw new ActionException("OCR.TEXT_NOT_FOUND", "'" + text + "' was not found on the screen" + (occurrence > 1 ? " " + occurrence + " times" : ""), "Check the window is visible; try a shorter phrase.", true);
        int cx = (box[0] + box[2] / 2) / 2 + offsetX, cy = (box[1] + box[3] / 2) / 2 + offsetY;  // screen was captured at 2x
        Robot r = DesktopSupport.robot();
        r.mouseMove(cx, cy);
        for (int i = 0; i < (dbl ? 2 : 1); i++) { r.mousePress(mask); r.mouseRelease(mask); r.delay(40); }
        return Out.of("x", BigDecimal.valueOf(cx), "y", BigDecimal.valueOf(cy));
    }

    @Condition(id = "text_on_screen", name = "Text is on screen", description = "True when the word or phrase is visible on the screen (OCR).")
    public boolean textOnScreen(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Word or phrase.") String text,
            @Input(name = "language", type = OoaType.STRING, required = false, defaultValue = "\"eng\"", description = D_LANG) String lang) {
        Path img = capture(ctx, null, null, null, null, 2);
        try { return locate(words(tess(ctx, img, lang, 11, true).stdout()), text) != null; }
        finally { try { Files.deleteIfExists(img); } catch (IOException ignored) { } }
    }
}
