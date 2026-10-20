package com.oneofficeautomation.packages.office;

import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import org.apache.poi.xwpf.usermodel.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class WordGapsTest {
    private final WordPackage word = new WordPackage();
    private Path dir;
    private ActionContext ctx;

    @BeforeEach void setUp() throws Exception {
        dir = Files.createTempDirectory("ooa-word-gaps");
        ctx = OfficeTestSupport.ctx(dir);
    }

    private XWPFDocument reopen(String file) throws Exception {
        try (InputStream in = Files.newInputStream(dir.resolve(file))) { return new XWPFDocument(in); }
    }

    private static List<String> paragraphs(XWPFDocument d) {
        List<String> out = new ArrayList<>();
        for (XWPFParagraph p : d.getParagraphs()) out.add(p.getText());
        return out;
    }

    @Test void createDocumentAndInsertTextAtEveryPosition() throws Exception {
        word.createDocument(ctx, "new/letter.docx", false, "word");
        assertTrue(Files.exists(dir.resolve("new/letter.docx")));
        assertEquals("WORD.EXISTS", assertThrows(ActionException.class, () -> word.createDocument(ctx, "new/letter.docx", false, "other")).code());

        word.insertText(ctx, "Dear customer,", "end", null, false, "word");     // fills the empty first paragraph
        word.insertText(ctx, "Closing line", "end", null, true, "word");
        word.insertText(ctx, "Middle line", "after_text", "Dear", true, "word");
        word.insertText(ctx, "Heading", "before_text", "Dear", true, "word");
        word.insertText(ctx, " valued", "after_text", "Dear", false, "word");
        word.save(ctx, null, "word");
        try (XWPFDocument d = reopen("new/letter.docx")) {
            assertEquals(List.of("Heading", "Dear valued customer,", "Middle line", "Closing line"), paragraphs(d));
        }
        assertEquals("WORD.TEXT_NOT_FOUND", assertThrows(ActionException.class, () -> word.insertText(ctx, "x", "after_text", "missing", true, "word")).code());
    }

    @Test void insertTextAtBookmark() throws Exception {
        try (XWPFDocument d = new XWPFDocument(); OutputStream out = Files.newOutputStream(dir.resolve("bm.docx"))) {
            XWPFParagraph p = d.createParagraph();
            p.createRun().setText("Hello");
            var bm = p.getCTP().addNewBookmarkStart();
            bm.setName("here");
            bm.setId(BigInteger.ONE);
            p.getCTP().addNewBookmarkEnd().setId(BigInteger.ONE);
            p.createRun().setText(" world");
            d.createParagraph().createRun().setText("Last");
            d.write(out);
        }
        word.open(ctx, "bm.docx", false, "word");
        word.insertText(ctx, " there", "bookmark", "here", false, "word");
        word.insertText(ctx, "After bookmark", "bookmark", "here", true, "word");
        word.save(ctx, null, "word");
        try (XWPFDocument d = reopen("bm.docx")) {
            assertEquals(List.of("Hello there world", "After bookmark", "Last"), paragraphs(d));
        }
        assertEquals("WORD.NO_BOOKMARK", assertThrows(ActionException.class, () -> word.insertText(ctx, "x", "bookmark", "nope", true, "word")).code());
    }

    @Test void insertAndReplacePictures() throws Exception {
        Path red = OfficeTestSupport.png(dir, "red.png", 40, 20, Color.RED);
        Path blue = OfficeTestSupport.png(dir, "blue.png", 10, 10, Color.BLUE);
        Path green = OfficeTestSupport.png(dir, "green.png", 10, 10, Color.GREEN);
        word.createDocument(ctx, "pics.docx", false, "word");
        word.insertText(ctx, "Logo below", "end", null, false, "word");
        word.insertText(ctx, "Footer text", "end", null, true, "word");
        word.insertPicture(ctx, "red.png", 144.0, "Logo below", "logo", "word");
        word.insertPicture(ctx, "red.png", null, null, null, "word");
        assertEquals(0, ((BigDecimal) word.findReplacePicture(ctx, "blue.png", "logo", null, "word").get("count")).compareTo(BigDecimal.ONE));
        assertEquals(0, ((BigDecimal) word.findReplacePicture(ctx, "green.png", null, 2, "word").get("count")).compareTo(BigDecimal.ONE));
        assertEquals("WORD.NO_PICTURE", assertThrows(ActionException.class, () -> word.findReplacePicture(ctx, "green.png", null, 5, "word")).code());
        word.save(ctx, null, "word");
        try (XWPFDocument d = reopen("pics.docx")) {
            List<String> texts = paragraphs(d);
            assertEquals("Logo below", texts.get(0));
            List<XWPFPicture> pics = new ArrayList<>();
            for (XWPFParagraph p : d.getParagraphs()) for (XWPFRun r : p.getRuns()) pics.addAll(r.getEmbeddedPictures());
            assertEquals(2, pics.size());
            assertArrayEquals(Files.readAllBytes(blue), pics.get(0).getPictureData().getData());
            assertEquals("logo", pics.get(0).getDescription());
            assertEquals(144.0, pics.get(0).getWidth(), 0.5);                 // scaled to 144 pt wide
            assertEquals(72.0, pics.get(0).getDepth(), 0.5);                  // aspect ratio kept (40x20)
            assertArrayEquals(Files.readAllBytes(green), pics.get(1).getPictureData().getData());
            // picture paragraph sits right after "Logo below"
            assertTrue(d.getParagraphs().get(1).getRuns().get(0).getEmbeddedPictures().size() == 1);
            assertEquals("Footer text", d.getParagraphs().get(2).getText());
        }
    }

    @Test void formatTextSplitsRunsAndSaveAs() throws Exception {
        try (XWPFDocument d = new XWPFDocument(); OutputStream out = Files.newOutputStream(dir.resolve("fmt.docx"))) {
            XWPFParagraph p = d.createParagraph();
            p.createRun().setText("Total due: ");
            XWPFRun r = p.createRun();
            r.setText("100 GBP now, 100 GBP later");
            r.setItalic(true);
            d.write(out);
        }
        word.open(ctx, "fmt.docx", false, "word");
        assertEquals(0, ((BigDecimal) word.formatText(ctx, "100 GBP", true, null, null, 14.0, "#C00000", null, "word").get("count")).compareTo(BigDecimal.valueOf(2)));
        word.saveAs(ctx, "out/fmt2.docx", null, "word");
        assertEquals("WORD.PDF_UNSUPPORTED", assertThrows(ActionException.class, () -> word.saveAs(ctx, "out/fmt.pdf", "pdf", "word")).code());
        try (XWPFDocument d = reopen("out/fmt2.docx")) {
            XWPFParagraph p = d.getParagraphs().get(0);
            assertEquals("Total due: 100 GBP now, 100 GBP later", p.getText());
            int bold = 0;
            for (XWPFRun r : p.getRuns()) {
                String t = r.text();
                if (t.equals("100 GBP")) {
                    bold++;
                    assertTrue(r.isBold());
                    assertTrue(r.isItalic());                       // existing formatting kept
                    assertEquals(14.0, r.getFontSizeAsDouble());
                    assertEquals("C00000", r.getColor());
                } else assertFalse(r.isBold(), t);
            }
            assertEquals(2, bold);
        }
        // the session continues on the new path
        word.addParagraph(ctx, "Extra", false, null, "word");
        word.save(ctx, null, "word");
        try (XWPFDocument d = reopen("out/fmt2.docx")) { assertEquals("Extra", d.getParagraphs().get(1).getText()); }
        try (XWPFDocument d = reopen("fmt.docx")) { assertEquals(1, d.getParagraphs().size()); }
    }
}
