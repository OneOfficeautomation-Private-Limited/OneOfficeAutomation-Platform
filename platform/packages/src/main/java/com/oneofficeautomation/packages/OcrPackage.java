package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import java.util.List;

/**
 * Optical character recognition with Tesseract (open source; install on the device, e.g. the UB Mannheim build on Windows).
 * Reads text from images, PDFs rendered to images, or the live screen, and can locate and click words, which is how
 * Citrix and legacy applications without accessible controls are automated.
 */
@OoaPackage(id = "ooa.ocr", name = "OCR", version = "1.0.0", description = "Read text from images or the screen, and find or click text on the screen (Tesseract).")
public final class OcrPackage {

    record Word(String text, int left, int top, int width, int height, int line, double conf) {}

    static String exe() {
        String env = System.getenv("OOA_TESSERACT");
        if (env != null && !env.isBlank()) return env;
        if (ProcessRunner.WINDOWS) for (String p : List.of("C:\\Program Files\\Tesseract-OCR\\tesseract.exe", "C:\\Program Files (x86)\\Tesseract-OCR\\tesseract.exe"))
            if (Files.isRegularFile(Path.of(p))) return p;
        return "tesseract";
    }

    private static ProcessRunner.Result tess(ActionContext ctx, Path image, String lang, int psm, boolean tsv) {
        if (!lang.matches("^[a-z_+]{3,40}$")) throw new ActionException("OCR.BAD_LANGUAGE", "Invalid language '" + lang + "'", "Examples: eng, deu, eng+fra.");
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
    static int[] locate(List<Word> ws, String phrase) {
        String[] target = phrase.trim().toLowerCase(Locale.ROOT).split("\\s+");
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
                return new int[]{l, t, r - l, b - t};
            }
        }
        return null;
    }

    private static Path capture(ActionContext ctx, Integer x, Integer y, Integer w, Integer h, int scale) {
        Robot robot = DesktopSupport.robot();
        Rectangle area = x == null || y == null || w == null || h == null ? new Rectangle(Toolkit.getDefaultToolkit().getScreenSize()) : new Rectangle(x, y, w, h);
        BufferedImage img = robot.createScreenCapture(area);
        if (scale > 1) {
            BufferedImage big = new BufferedImage(img.getWidth() * scale, img.getHeight() * scale, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = big.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(img, 0, 0, big.getWidth(), big.getHeight(), null);
            g.dispose();
            img = big;
        }
        try { Path p = Files.createTempFile("ooa-ocr-", ".png"); ImageIO.write(img, "png", p.toFile()); return p; }
        catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), null, true); }
    }

    private static final String D_LANG = "Tesseract language(s), e.g. eng, deu or eng+fra.";
    private static final String D_PSM = "Page layout mode: 3 automatic, 6 single block, 7 single line, 11 sparse text.";

    @Action(id = "read_image", name = "Read text from image", category = "OCR", idempotent = true, timeoutSeconds = 300, capabilities = "filesystem",
            description = "Extract text from an image file (PNG, JPG, TIFF, BMP).")
    @Output(name = "text", type = OoaType.STRING, description = "Recognised text.")
    public Map<String, Object> readImage(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "Image file.") String path,
            @Input(name = "language", type = OoaType.STRING, required = false, defaultValue = "\"eng\"", description = D_LANG) String lang,
            @Input(name = "page_mode", type = OoaType.NUMBER, required = false, defaultValue = "3", description = D_PSM) int psm) {
        Path p = ctx.resolvePath(path);
        if (!Files.isRegularFile(p)) throw new ActionException("FILE.NOT_FOUND", "Image not found: " + p, "Check the path.");
        return Map.of("text", tess(ctx, p, lang, psm, false).stdout().strip());
    }

    @Action(id = "read_screen", name = "Read text from screen", category = "OCR", timeoutSeconds = 300, capabilities = "desktop",
            description = "Capture the screen (or a region) and extract its text; for Citrix and applications without accessible controls.")
    @Output(name = "text", type = OoaType.STRING, description = "Recognised text.")
    public Map<String, Object> readScreen(ActionContext ctx,
            @Input(name = "x", type = OoaType.NUMBER, required = false, description = "Region left (whole screen when empty).") Integer x,
            @Input(name = "y", type = OoaType.NUMBER, required = false, description = "Region top.") Integer y,
            @Input(name = "width", type = OoaType.NUMBER, required = false, description = "Region width.") Integer w,
            @Input(name = "height", type = OoaType.NUMBER, required = false, description = "Region height.") Integer h,
            @Input(name = "language", type = OoaType.STRING, required = false, defaultValue = "\"eng\"", description = D_LANG) String lang,
            @Input(name = "page_mode", type = OoaType.NUMBER, required = false, defaultValue = "6", description = D_PSM) int psm) {
        Path img = capture(ctx, x, y, w, h, 2);
        try { return Map.of("text", tess(ctx, img, lang, psm, false).stdout().strip()); }
        finally { try { Files.deleteIfExists(img); } catch (IOException ignored) { } }
    }

    @Action(id = "find_text_in_image", name = "Find text in image", category = "OCR", idempotent = true, timeoutSeconds = 300, capabilities = "filesystem",
            description = "Locate a word or phrase in an image; returns whether it was found and its centre.")
    @Output(name = "found", type = OoaType.BOOLEAN, description = "True when found.")
    @Output(name = "x", type = OoaType.NUMBER, description = "Centre x.")
    @Output(name = "y", type = OoaType.NUMBER, description = "Centre y.")
    public Map<String, Object> findInImage(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "Image file.") String path,
            @Input(name = "text", type = OoaType.STRING, description = "Word or phrase to find.") String text,
            @Input(name = "language", type = OoaType.STRING, required = false, defaultValue = "\"eng\"", description = D_LANG) String lang) {
        int[] box = locate(words(tess(ctx, ctx.resolvePath(path), lang, 11, true).stdout()), text);
        return box == null ? Out.of("found", false, "x", null, "y", null)
                : Out.of("found", true, "x", BigDecimal.valueOf(box[0] + box[2] / 2), "y", BigDecimal.valueOf(box[1] + box[3] / 2));
    }

    @Action(id = "click_text", name = "Click text on screen", category = "OCR", timeoutSeconds = 300, capabilities = "desktop",
            description = "Find a word or phrase on the screen with OCR and click its centre (OCR.TEXT_NOT_FOUND when absent).")
    @Output(name = "x", type = OoaType.NUMBER, description = "Clicked x.")
    @Output(name = "y", type = OoaType.NUMBER, description = "Clicked y.")
    public Map<String, Object> clickText(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Word or phrase to click.") String text,
            @Input(name = "double_click", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Double-click.") boolean dbl,
            @Input(name = "language", type = OoaType.STRING, required = false, defaultValue = "\"eng\"", description = D_LANG) String lang) {
        Path img = capture(ctx, null, null, null, null, 2);
        int[] box;
        try { box = locate(words(tess(ctx, img, lang, 11, true).stdout()), text); }
        finally { try { Files.deleteIfExists(img); } catch (IOException ignored) { } }
        if (box == null) throw new ActionException("OCR.TEXT_NOT_FOUND", "'" + text + "' was not found on the screen", "Check the window is visible; try a shorter phrase.", true);
        int cx = (box[0] + box[2] / 2) / 2, cy = (box[1] + box[3] / 2) / 2;  // screen was captured at 2x
        Robot r = DesktopSupport.robot();
        r.mouseMove(cx, cy);
        for (int i = 0; i < (dbl ? 2 : 1); i++) { r.mousePress(InputEvent.BUTTON1_DOWN_MASK); r.mouseRelease(InputEvent.BUTTON1_DOWN_MASK); r.delay(40); }
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
