package com.oneofficeautomation.packages.pdf;

import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** read_text_ocr on a scanned PDF (a page that is only a picture): runs Tesseract where it is installed. */
class PdfOcrTest {
    static BufferedImage scan(String text) {
        BufferedImage img = new BufferedImage(1200, 300, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setColor(Color.WHITE); g.fillRect(0, 0, 1200, 300);
        g.setColor(Color.BLACK); g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 72));
        g.drawString(text, 40, 180); g.dispose();
        return img;
    }

    @SuppressWarnings("unchecked")
    @Test void readsTheTextOfAScannedPdf() throws Exception {
        Path w = Files.createTempDirectory("ooa-pdf-ocr");
        try (PDDocument d = new PDDocument()) {
            for (String t : List.of("INVOICE 4711", "TOTAL 99")) {
                PDPage p = new PDPage();
                d.addPage(p);
                try (PDPageContentStream cs = new PDPageContentStream(d, p)) { cs.drawImage(LosslessFactory.createFromImage(d, scan(t)), 20, 500, 570, 142); }
            }
            d.save(w.resolve("scan.pdf").toFile());
        }
        PdfPackage pdf = new PdfPackage();
        ActionContext c = PdfImagesPropertiesTest.ctx(w);
        ActionException bad = assertThrows(ActionException.class, () -> pdf.readTextOcr(c, "scan.pdf", null, null, "en;rm", 3, 300, null, null, true));
        assertEquals("OCR.BAD_LANGUAGE", bad.code(), "the language is checked before Tesseract runs");
        assertThrows(ActionException.class, () -> pdf.readTextOcr(c, "scan.pdf", null, "5", "eng", 3, 300, null, null, true), "no page 5");
        Map<String, Object> r;
        try {
            r = pdf.readTextOcr(c, "scan.pdf", null, null, "eng", 6, 300, null, null, true);
        } catch (ActionException e) {
            assertEquals("OCR.NOT_INSTALLED", e.code(), e.getMessage());
            System.out.println("    (tesseract not installed; OCR itself skipped)");
            return;
        }
        List<Object> pages = (List<Object>) r.get("pages");
        assertEquals(2, pages.size());
        assertTrue(pages.get(0).toString().contains("4711"), pages.toString());
        assertTrue(pages.get(1).toString().contains("99"), pages.toString());
        assertEquals("99", pdf.readTextOcr(c, "scan.pdf", null, "2", "eng", 6, 300, "TOTAL", null, true).get("text"), "markers and chosen pages");
    }
}
