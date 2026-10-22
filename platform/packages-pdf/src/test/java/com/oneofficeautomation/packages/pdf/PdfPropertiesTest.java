package com.oneofficeautomation.packages.pdf;

import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import com.oneofficeautomation.sdk.SecureString;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Options added to the PDF actions after comparing them with the equivalent AA actions. */
class PdfPropertiesTest {
    private final PdfPackage pdf = new PdfPackage();
    private Path dir;
    private ActionContext ctx;
    private static final SecureString USER = new SecureString("open-me", "vault:u"), OWNER = new SecureString("own-me", "vault:o");

    @BeforeEach void setUp() throws Exception {
        dir = Files.createTempDirectory("ooa-pdf-props");
        ctx = PdfImagesPropertiesTest.ctx(dir);
        try (PDDocument d = new PDDocument()) {
            for (int i = 1; i <= 4; i++) {
                PDPage p = new PDPage();
                d.addPage(p);
                try (PDPageContentStream cs = new PDPageContentStream(d, p)) {
                    cs.beginText(); cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14); cs.newLineAtOffset(100, 700); cs.showText("Page " + i); cs.endText();
                    cs.beginText(); cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12); cs.newLineAtOffset(400, 100); cs.showText("Footer " + i); cs.endText();
                    if (i == 2) cs.drawImage(LosslessFactory.createFromImage(d, PdfImagesPropertiesTest.image(60, 40, Color.RED)), 50, 300);
                }
            }
            d.getDocumentInformation().setTitle("Report");
            d.getDocumentInformation().setCustomMetadataValue("Department", "Finance");
            d.save(dir.resolve("four.pdf").toFile());
        }
    }

    private static String code(Runnable r) { return assertThrows(ActionException.class, r::run).code(); }
    private static int n(Object o) { return ((BigDecimal) o).intValue(); }

    @Test void pageListParsing() {
        assertEquals(List.of(3, 1, 2, 3, 4), PdfPackage.pages("3, 1-2,3-", 4));
        assertEquals(List.of(1, 2, 3, 4), PdfPackage.pages("", 4));
        assertEquals("PDF.BAD_PAGES", code(() -> PdfPackage.pages("0", 4)));
        assertEquals("PDF.BAD_PAGES", code(() -> PdfPackage.pages("2-9", 4)));
        assertEquals("PDF.BAD_PAGES", code(() -> PdfPackage.pages("a-b", 4)));
    }

    @Test void extractTextPagesModeFileAndArea() throws Exception {
        String t = (String) pdf.extractText(ctx, "four.pdf", 1, null, null, "plain", "3,1", "out/text.txt", true).get("text");
        assertTrue(t.indexOf("Page 3") < t.indexOf("Page 1") && !t.contains("Page 2"), t);
        assertEquals(t, Files.readString(dir.resolve("out/text.txt")));
        assertEquals("PDF.EXISTS", code(() -> pdf.extractText(ctx, "four.pdf", 1, null, null, "structured", null, "out/text.txt", false)));
        // the heading sits 700 pt above the bottom of a 792 pt page, so about 92 pt from the top
        assertEquals("Page 2", pdf.extractAreaText(ctx, "four.pdf", 2, 90, 70, 200, 30, null).get("text"));
        assertEquals("Footer 4", pdf.extractAreaText(ctx, "four.pdf", 4, 390, 675, 200, 30, null).get("text"));
        assertEquals("PDF.BAD_PAGES", code(() -> pdf.extractAreaText(ctx, "four.pdf", 9, 0, 0, 10, 10, null)));
        assertEquals("PDF.BAD_AREA", code(() -> pdf.extractAreaText(ctx, "four.pdf", 1, 0, 0, 0, 10, null)));
    }

    @Test void mergeAndSplitOptions() throws Exception {
        Map<String, Object> m = pdf.merge(ctx, List.of("four.pdf", "four.pdf"), "merged.pdf", true, List.of("4,1", ""), null);
        assertEquals(6, n(m.get("page_count")));
        String text = (String) pdf.extractText(ctx, "merged.pdf", 1, 2, null).get("text");
        assertTrue(text.indexOf("Page 4") < text.indexOf("Page 1"), text);
        assertEquals("PDF.EXISTS", code(() -> pdf.merge(ctx, List.of("four.pdf", "four.pdf"), "merged.pdf", false, null, null)));
        assertEquals("PDF.BAD_PAGES", code(() -> pdf.merge(ctx, List.of("four.pdf", "four.pdf"), "x.pdf", true, List.of("1"), null)));

        @SuppressWarnings("unchecked") List<Object> parts = (List<Object>) pdf.split(ctx, "four.pdf", "parts", 1, "ranges", "1-2,3,4", "inv", true, null).get("files");
        assertEquals(3, parts.size());
        assertTrue(parts.get(0).toString().endsWith("inv_part1.pdf"));
        try (PDDocument d = Loader.loadPDF(Path.of(parts.get(0).toString()).toFile())) { assertEquals(2, d.getNumberOfPages()); }
        @SuppressWarnings("unchecked") List<Object> sel = (List<Object>) pdf.split(ctx, "four.pdf", "sel", 1, "selected_pages", "4,2", null, true, null).get("files");
        assertEquals(1, sel.size());
        assertTrue(((String) pdf.extractText(ctx, sel.get(0).toString(), 1, 1, null).get("text")).contains("Page 4"));
        assertEquals("PDF.EXISTS", code(() -> pdf.split(ctx, "four.pdf", "parts", 1, "ranges", "1-2", "inv", false, null)));
        assertEquals("PDF.BAD_MODE", code(() -> pdf.split(ctx, "four.pdf", "parts", 1, "odd", null, null, true, null)));
        assertEquals(4, ((List<?>) pdf.split(ctx, "four.pdf", "each", 1).get("files")).size());
    }

    @Test void encryptPermissionsKeyLengthAndProtectedSources() throws Exception {
        pdf.encrypt(ctx, "four.pdf", "locked.pdf", USER, OWNER, true, false, false, true, true, false, true, 128, true);
        try (PDDocument d = Loader.loadPDF(dir.resolve("locked.pdf").toFile(), "open-me")) {
            AccessPermission ap = d.getCurrentAccessPermission();
            assertTrue(ap.canPrint());
            assertFalse(ap.canExtractContent());
            assertFalse(ap.canModify());
            assertTrue(ap.canModifyAnnotations());
            assertFalse(ap.canAssembleDocument());
            assertEquals(128, d.getEncryption().getLength());
        }
        assertEquals("PDF.BAD_KEY_LENGTH", code(() -> pdf.encrypt(ctx, "four.pdf", "x.pdf", USER, OWNER, true, true, true, true, true, true, true, 40, true)));
        assertEquals("PDF.EXISTS", code(() -> pdf.encrypt(ctx, "four.pdf", "locked.pdf", USER, OWNER, true, true, true, true, true, true, true, 256, false)));
        assertEquals("PDF.EXISTS", code(() -> pdf.decrypt(ctx, "locked.pdf", USER, "four.pdf", false)));
        // protected sources: merge with passwords, split and convert with a password
        assertEquals(8, n(pdf.merge(ctx, List.of("locked.pdf", "four.pdf"), "m2.pdf", true, null, Arrays.asList(USER, "")).get("page_count")));
        assertEquals(4, ((List<?>) pdf.split(ctx, "locked.pdf", "lp", 1, "pages_per_file", null, null, true, USER).get("files")).size());
        assertEquals("PDF.PASSWORD_REQUIRED", code(() -> pdf.split(ctx, "locked.pdf", "lp", 1)));
    }

    @Test void imagesFormatsPagesAndColour() throws Exception {
        @SuppressWarnings("unchecked") List<Object> imgs = (List<Object>) pdf.toImages(ctx, "four.pdf", "img", 50, "jpg", "gray", "2,4", "scan", null).get("files");
        assertEquals(2, imgs.size());
        assertTrue(imgs.get(0).toString().endsWith("scan_page2.jpg"));
        BufferedImage g = ImageIO.read(Path.of(imgs.get(0).toString()).toFile());
        assertEquals(1, g.getColorModel().getNumComponents());
        @SuppressWarnings("unchecked") List<Object> bw = (List<Object>) pdf.toImages(ctx, "four.pdf", "bw", 50, "png", "black_white", "1", null, null).get("files");
        assertEquals(BufferedImage.TYPE_BYTE_BINARY, ImageIO.read(Path.of(bw.get(0).toString()).toFile()).getType());
        assertEquals("PDF.BAD_FORMAT", code(() -> pdf.toImages(ctx, "four.pdf", "x", 50, "webp", "color", null, null, null)));

        Map<String, Object> ex = pdf.extractImages(ctx, "four.pdf", "ex", 1, null, "2", "bmp", "pic", "gray");
        assertEquals(1, n(ex.get("count")));
        assertTrue(((List<?>) ex.get("files")).get(0).toString().endsWith("pic_page2_img1.bmp"));
        assertEquals(0, n(pdf.extractImages(ctx, "four.pdf", "ex2", 1, null, "1,3", "png", null, "color").get("count")));
    }

    @SuppressWarnings("unchecked")
    @Test void customPropertiesAndProtectedForm() throws Exception {
        Map<String, Object> props = (Map<String, Object>) pdf.getProperties(ctx, "four.pdf", null, true).get("properties");
        assertEquals("Report", props.get("title"));
        assertEquals("four.pdf", props.get("file_name"));
        assertEquals(Map.of("Department", "Finance"), props.get("custom"));
        assertFalse(((Map<String, Object>) pdf.getProperties(ctx, "four.pdf", null).get("properties")).containsKey("custom"));

        try (PDDocument d = new PDDocument()) {
            PDPage page = new PDPage();
            d.addPage(page);
            PDAcroForm form = new PDAcroForm(d);
            d.getDocumentCatalog().setAcroForm(form);
            PDResources res = new PDResources();
            res.put(org.apache.pdfbox.cos.COSName.getPDFName("Helv"), new PDType1Font(Standard14Fonts.FontName.HELVETICA));
            form.setDefaultResources(res);
            form.setDefaultAppearance("/Helv 0 Tf 0 g");
            PDTextField f = new PDTextField(form);
            f.setPartialName("customer");
            PDAnnotationWidget w = f.getWidgets().get(0);
            w.setRectangle(new org.apache.pdfbox.pdmodel.common.PDRectangle(50, 700, 200, 20));
            w.setPage(page);
            page.getAnnotations().add(w);
            form.getFields().add(f);
            AccessPermission ap = new AccessPermission();
            StandardProtectionPolicy spp = new StandardProtectionPolicy("own-me", "open-me", ap);
            spp.setEncryptionKeyLength(256);
            d.protect(spp);
            d.save(dir.resolve("form.pdf").toFile());
        }
        assertEquals("PDF.PASSWORD_REQUIRED", code(() -> pdf.fillForm(ctx, "form.pdf", Map.of("customer", "Acme"), "filled.pdf", false)));
        assertEquals(1, n(pdf.fillForm(ctx, "form.pdf", Map.of("customer", "Acme"), "filled.pdf", false, USER).get("filled")));
        assertEquals("Acme", ((Map<String, Object>) pdf.readForm(ctx, "filled.pdf", null).get("fields")).get("customer"));
    }
}
