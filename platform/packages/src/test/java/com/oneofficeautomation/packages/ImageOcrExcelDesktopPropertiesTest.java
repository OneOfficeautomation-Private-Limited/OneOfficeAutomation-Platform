package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import com.oneofficeautomation.sdk.SecureString;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.event.InputEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Options added to the Image recognition, OCR and Excel desktop actions after comparing them with the equivalent AA
 * actions. Screen, Tesseract and Excel are not needed: the tests use image files, the pure helpers and the generated
 * Excel scripts.
 */
class ImageOcrExcelDesktopPropertiesTest {
    private static String code(Runnable r) { return assertThrows(ActionException.class, r::run).code(); }
    private static int n(Object o) { return ((BigDecimal) o).intValue(); }

    /** A white picture with three identical black-and-grey buttons: two on the top row, one lower down. */
    private static Path screenWithButtons(Path dir) throws Exception {
        BufferedImage img = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE); g.fillRect(0, 0, 400, 300);
        for (int[] p : new int[][]{{250, 20}, {40, 20}, {120, 200}}) {
            g.setColor(Color.BLACK); g.fillRect(p[0], p[1], 40, 24);
            g.setColor(Color.GRAY); g.fillRect(p[0] + 8, p[1] + 6, 24, 12);
        }
        g.dispose();
        ImageIO.write(img, "png", dir.resolve("screen.png").toFile());
        BufferedImage t = img.getSubimage(40, 20, 40, 24);
        ImageIO.write(t, "png", dir.resolve("button.png").toFile());
        return dir;
    }

    // ------------------------------------------------------------------------------------------------ Image recognition

    @Test void imageOccurrencePositionOffsetAndRegion() throws Exception {
        Path dir = screenWithButtons(PackagesTest.tmp());
        ActionContext ctx = PackagesTestSupport.ctx(dir, Map.of());
        ImagePackage img = new ImagePackage();
        BufferedImage screen = ImageIO.read(dir.resolve("screen.png").toFile()), button = ImageIO.read(dir.resolve("button.png").toFile());
        List<ImagePackage.Match> all = ImagePackage.findAll(screen, button, 5, 10);
        assertEquals(3, all.size());
        assertEquals(List.of(40, 250, 120), all.stream().map(ImagePackage.Match::x).toList(), "reading order: top row left to right, then the lower one");

        Map<String, Object> second = img.findInImage(ctx, "screen.png", "button.png", 5, 2, "center", 0, 0, null, null, null, null);
        assertEquals(true, second.get("found"));
        assertEquals(270, n(second.get("x")));
        assertEquals(32, n(second.get("y")));
        assertEquals(2, n(second.get("count")));

        Map<String, Object> corner = img.findInImage(ctx, "screen.png", "button.png", 5, 3, "top_left", 5, -2, null, null, null, null);
        assertEquals(125, n(corner.get("x")));
        assertEquals(198, n(corner.get("y")));
        assertEquals(120, n(corner.get("left")));
        assertEquals(200, n(corner.get("top")));

        assertEquals(false, img.findInImage(ctx, "screen.png", "button.png", 5, 4, "center", 0, 0, null, null, null, null).get("found"));

        Map<String, Object> inRegion = img.findInImage(ctx, "screen.png", "button.png", 5, 1, "center", 0, 0, 100, 150, 200, 120);
        assertEquals(140, n(inRegion.get("x")), "coordinates are reported for the whole image, not the region");
        assertEquals(212, n(inRegion.get("y")));
        assertEquals("IMAGE.BAD_REGION", code(() -> img.findInImage(ctx, "screen.png", "button.png", 5, 1, "center", 0, 0, 10, 10, null, null)));
        assertEquals("IMAGE.BAD_REGION", code(() -> img.findInImage(ctx, "screen.png", "button.png", 5, 1, "center", 0, 0, 900, 900, 10, 10)));
        assertEquals("IMAGE.BAD_OCCURRENCE", code(() -> img.findInImage(ctx, "screen.png", "button.png", 5, 0, "center", 0, 0, null, null, null, null)));
        assertEquals("IMAGE.BAD_POSITION", code(() -> img.findInImage(ctx, "screen.png", "button.png", 5, 1, "middle", 0, 0, null, null, null, null)));
        assertEquals(true, img.findInImage(ctx, "screen.png", "button.png", 10).get("found"));
    }

    // ------------------------------------------------------------------------------------------------ OCR

    @Test void ocrTextFilterTrimAndOccurrences() {
        String text = "  Invoice number: INV-2026\nTotal: 12.50 GBP\n";
        assertEquals("INV-2026", OcrPackage.filterText(text, "Invoice number:", "\n", true));
        assertEquals(" INV-2026", OcrPackage.filterText(text, "Invoice number:", "\n", false));
        assertEquals("12.50", OcrPackage.filterText(text, "Total:", "GBP", true));
        assertEquals("", OcrPackage.filterText(text, "Missing", null, true));
        assertEquals(text.strip(), OcrPackage.filterText(text, null, null, true));

        List<OcrPackage.Word> ws = List.of(
                new OcrPackage.Word("Save", 10, 10, 40, 12, 1, 90), new OcrPackage.Word("draft", 55, 10, 40, 12, 1, 90),
                new OcrPackage.Word("Save", 10, 50, 40, 12, 2, 90), new OcrPackage.Word("draft", 55, 50, 40, 12, 2, 90));
        assertEquals(2, OcrPackage.locateAll(ws, "save draft").size());
        assertArrayEquals(new int[]{10, 50, 85, 12}, OcrPackage.locate(ws, "save draft", 2));
        assertNull(OcrPackage.locate(ws, "save draft", 3));

        assertEquals(InputEvent.BUTTON3_DOWN_MASK, OcrPackage.button("right"));
        assertEquals(InputEvent.BUTTON1_DOWN_MASK, OcrPackage.button(null));
        assertEquals("DESKTOP.BAD_BUTTON", code(() -> OcrPackage.button("side")));
        assertEquals("OCR.BAD_LANGUAGE", code(() -> OcrPackage.checkLanguage("eng; rm")));
    }

    @Test void ocrImagePreparation() {
        BufferedImage img = new BufferedImage(100, 50, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(200, 220, 255)); g.fillRect(0, 0, 100, 50);
        g.setColor(new Color(20, 20, 60)); g.fillRect(10, 10, 30, 10);
        g.dispose();
        BufferedImage crop = OcrPackage.prepare(img, 10, 10, 40, 20, 2, "none");
        assertEquals(80, crop.getWidth());
        assertEquals(40, crop.getHeight());
        assertEquals(BufferedImage.TYPE_BYTE_GRAY, OcrPackage.prepare(img, null, null, null, null, 1, "gray").getType());
        BufferedImage bw = OcrPackage.prepare(img, null, null, null, null, 1, "black_white");
        assertEquals(BufferedImage.TYPE_BYTE_BINARY, bw.getType());
        assertEquals(0xFFFFFF, bw.getRGB(80, 40) & 0xFFFFFF, "light background becomes white");
        assertEquals(0, bw.getRGB(20, 15) & 0xFFFFFF, "dark text becomes black");
        assertEquals("OCR.BAD_REGION", code(() -> OcrPackage.prepare(img, 1, 1, null, 5, 1, "none")));
        assertEquals("OCR.BAD_REGION", code(() -> OcrPackage.prepare(img, 500, 500, 5, 5, 1, "none")));
        assertEquals("OCR.BAD_SCALE", code(() -> OcrPackage.prepare(img, null, null, null, null, 9, "none")));
        assertEquals("OCR.BAD_MODE", code(() -> OcrPackage.prepare(img, null, null, null, null, 1, "sepia")));
    }

    @Test void ocrDownloadsImagesFromUrls() throws Exception {
        BufferedImage img = new BufferedImage(30, 20, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(img, "png", png);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/scan.png", ex -> { ex.sendResponseHeaders(200, png.size()); ex.getResponseBody().write(png.toByteArray()); ex.close(); });
        server.createContext("/page.html", ex -> { byte[] b = "<html></html>".getBytes(); ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close(); });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            assertEquals(30, OcrPackage.download(base + "/scan.png", 10).getWidth());
            assertEquals("OCR.DOWNLOAD_FAILED", code(() -> OcrPackage.download(base + "/missing.png", 10)));
            assertEquals("OCR.UNSUPPORTED_IMAGE", code(() -> OcrPackage.download(base + "/page.html", 10)));
            assertEquals("OCR.BAD_URL", code(() -> OcrPackage.download("file:///C:/secret.png", 10)));
            ActionContext ctx = PackagesTestSupport.ctx(PackagesTest.tmp(), Map.of());
            assertEquals("OCR.BAD_LANGUAGE", code(() -> new OcrPackage().readUrl(ctx, base + "/scan.png", "x;y", 3, null, null, true, 1, "none", 10)));
        } finally { server.stop(0); }
    }

    // ------------------------------------------------------------------------------------------------ Excel desktop

    @Test void excelDesktopScriptsKeepSecretsOutOfTheCommandLine() {
        SecureString pw = new SecureString("Xl-Secret-1", "vault:xl");
        ExcelDesktopPackage.Script s = ExcelDesktopPackage.runMacroScript("C:\\b.xlsm", "Module1.Go", List.of("a", 2), false, pw, true);
        assertTrue(s.full().contains("$xl.Visible = $true"));
        assertTrue(s.body().contains("Workbooks.Open($args[0], 0, $false, 5, $env:OOA_XL_PASSWORD)"));
        assertEquals(List.of("C:\\b.xlsm", "Module1.Go", "0", "a", "2"), s.args());
        assertEquals("Xl-Secret-1", s.env().get(ExcelDesktopPackage.PASSWORD_ENV));
        assertFalse(s.full().contains("Xl-Secret-1"));
        assertFalse(String.join(" ", s.args()).contains("Xl-Secret-1"));
        ExcelDesktopPackage.Script plain = ExcelDesktopPackage.runMacroScript("C:\\b.xlsm", "Go", null, true, null, false);
        assertTrue(plain.body().contains("Workbooks.Open($args[0], 0, $false)"));
        assertTrue(plain.full().contains("$xl.Visible = $false"));
        assertTrue(plain.env().isEmpty());
        assertEquals("EXCEL_DESKTOP.BAD_MACRO", code(() -> ExcelDesktopPackage.runMacroScript("x", "Go; Remove-Item", null, true, null, false)));

        ExcelDesktopPackage.Script pdf = ExcelDesktopPackage.exportPdfScript("C:\\b.xlsx", "C:\\b.pdf", "Summary", "minimum", false, true, 2, 3, null);
        assertEquals(List.of("C:\\b.xlsx", "C:\\b.pdf", "1", "0", "1", "2", "3", "Summary"), pdf.args());
        assertTrue(pdf.body().contains("Workbooks.Open($args[0], 0, $true)"));
        assertTrue(pdf.body().contains("ExportAsFixedFormat(0, $args[1], [int]$args[2]"));
        assertEquals(List.of("C:\\b.xlsx", "C:\\b.pdf", "0", "1", "0", "-", "-", "-"), ExcelDesktopPackage.exportPdfScript("C:\\b.xlsx", "C:\\b.pdf", null, null, true, false, null, null, null).args());
        assertEquals("EXCEL_DESKTOP.BAD_QUALITY", code(() -> ExcelDesktopPackage.exportPdfScript("a", "b", null, "high", true, false, null, null, null)));
        assertEquals("EXCEL_DESKTOP.BAD_PAGES", code(() -> ExcelDesktopPackage.exportPdfScript("a", "b", null, null, true, false, 3, 2, null)));

        ExcelDesktopPackage.Script conv = ExcelDesktopPackage.convertScript("C:\\a.xls", "C:\\a.xlsb", ExcelDesktopPackage.formatCode("xlsb"), pw, new SecureString("New-2", "v"));
        assertTrue(conv.body().contains("SaveAs($args[1], [int]$args[2], $env:OOA_XL_NEW_PASSWORD)"));
        assertEquals("New-2", conv.env().get(ExcelDesktopPackage.NEW_PASSWORD_ENV));
        assertEquals(List.of("C:\\a.xls", "C:\\a.xlsb", "50"), conv.args());
        assertEquals("60", ExcelDesktopPackage.formatCode("ods"));
        assertNull(ExcelDesktopPackage.formatCode("docx"));

        assertTrue(ExcelDesktopPackage.refreshScript("C:\\a.xlsx", false, null).args().contains("0"));
        ExcelDesktopPackage.Script links = ExcelDesktopPackage.updateLinksScript("C:\\a.xlsx", true, null);
        assertTrue(links.body().contains("$wb.UpdateLink($l, 1)"));
        assertEquals(List.of("C:\\a.xlsx", "1"), links.args());
    }
}
