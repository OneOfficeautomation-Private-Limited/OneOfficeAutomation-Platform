package com.oneofficeautomation.packages.office;

import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import org.apache.poi.xwpf.usermodel.*;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Gap: page-based read, replace and picture search, from Word's saved layout or the stored breaks. */
class WordPagesTest {
    static String code(org.junit.jupiter.api.function.Executable e) { return assertThrows(ActionException.class, e).code(); }

    /** Three pages as Word saved them: automatic breaks are marked with w:lastRenderedPageBreak; a picture on page 2 and on page 3. */
    static Path layoutDoc(Path dir) throws Exception {
        Path png = OfficeTestSupport.png(dir, "logo.png", 20, 10, Color.RED);
        try (XWPFDocument d = new XWPFDocument(); OutputStream out = Files.newOutputStream(dir.resolve("laid-out.docx"))) {
            d.createParagraph().createRun().setText("Page one says {{status}}");
            d.createParagraph().createRun().setText("Still page one, {{status}} too, and a long paragraph that ");
            XWPFRun cont = d.getParagraphs().get(1).createRun();
            cont.getCTR().addNewLastRenderedPageBreak();  // Word continued this paragraph on page 2
            cont.setText("flows over to page two with {{status}}");
            XWPFRun pic = d.createParagraph().createRun();
            try (InputStream in = Files.newInputStream(png)) { pic.addPicture(in, Document.PICTURE_TYPE_PNG, "logo.png", 200000, 100000); }
            XWPFRun p3 = d.createParagraph().createRun();
            p3.getCTR().addNewLastRenderedPageBreak();
            p3.setText("Page three {{status}}");
            XWPFRun pic3 = d.getParagraphs().get(3).createRun();
            try (InputStream in = Files.newInputStream(png)) { pic3.addPicture(in, Document.PICTURE_TYPE_PNG, "logo.png", 200000, 100000); }
            d.write(out);
        }
        return dir.resolve("laid-out.docx");
    }

    @Test void pagesFromWordsSavedLayout() throws Exception {
        Path dir = Files.createTempDirectory("ooa-word-pages");
        layoutDoc(dir);
        ActionContext ctx = OfficeTestSupport.ctx(dir);
        WordPackage w = new WordPackage();
        w.open(ctx, "laid-out.docx", false, "word");
        assertEquals(Map.of("pages", BigDecimal.valueOf(3), "counted_from", "layout"), w.pageCount(ctx, "auto", "word"));
        assertEquals(Map.of("pages", BigDecimal.ONE, "counted_from", "breaks"), w.pageCount(ctx, "breaks", "word"), "no stored breaks: one page");
        assertEquals("flows over to page two with {{status}}", w.readText(ctx, "word", false, false, null, null, null, 2, "auto").get("text"));
        assertTrue(String.valueOf(w.readText(ctx, "word", false, false, null, null, null, 1, "layout").get("text")).startsWith("Page one says"));
        assertEquals("WORD.NO_PAGE", code(() -> w.readText(ctx, "word", false, false, null, null, null, 4, "auto")));

        // replace only on page 2
        assertEquals(BigDecimal.ONE, w.replaceText(ctx, "{{status}}", "PAID", "word", true, false, "all", null, null, 2).get("count"));
        String all = String.valueOf(w.readText(ctx, "word").get("text"));
        assertTrue(all.contains("page two with PAID") && all.contains("Page one says {{status}}") && all.contains("Page three {{status}}"), all);
        assertEquals(BigDecimal.valueOf(2), w.replaceText(ctx, "{{status}}", "OPEN", "word", true, false, "all", null, null, 1).get("count"));
        assertEquals("WORD.NO_PAGE", code(() -> w.replaceText(ctx, "x", "y", "word", true, false, "all", null, null, 7)));

        // pictures on a page: index counts the pictures of that page
        Path blue = OfficeTestSupport.png(dir, "blue.png", 30, 30, Color.BLUE);
        assertEquals(BigDecimal.ONE, w.findReplacePicture(ctx, blue.toString(), null, 1, "word", "all", "Blue on page 3", null, null, 3).get("count"));
        XWPFDocument doc = ctx.session("word", WordPackage.DocSession.class).doc;
        List<XWPFPicture> pics = new ArrayList<>();
        for (XWPFParagraph p : doc.getParagraphs()) for (XWPFRun r : p.getRuns()) pics.addAll(r.getEmbeddedPictures());
        assertEquals(2, pics.size());
        assertArrayEquals(Files.readAllBytes(blue), pics.get(1).getPictureData().getData(), "the page-3 picture was replaced");
        assertFalse(Arrays.equals(Files.readAllBytes(blue), pics.get(0).getPictureData().getData()), "the page-2 picture was not");
        assertEquals("WORD.NO_PICTURE", code(() -> w.findReplacePicture(ctx, blue.toString(), null, 2, "word", "all", null, null, null, 3)));
    }

    @Test void layoutModeNeedsASavedLayout() throws Exception {
        Path dir = Files.createTempDirectory("ooa-word-pages2");
        try (XWPFDocument d = new XWPFDocument(); OutputStream out = Files.newOutputStream(dir.resolve("plain.docx"))) {
            d.createParagraph().createRun().setText("one");
            XWPFRun r = d.createParagraph().createRun();
            r.addBreak(BreakType.PAGE);
            r.setText("two");
            d.write(out);
        }
        ActionContext ctx = OfficeTestSupport.ctx(dir);
        WordPackage w = new WordPackage();
        w.open(ctx, "plain.docx", false, "word");
        assertEquals(BigDecimal.valueOf(2), w.pageCount(ctx, "auto", "word").get("pages"));
        assertEquals("WORD.NO_LAYOUT", code(() -> w.pageCount(ctx, "layout", "word")));
        assertEquals("two", w.readText(ctx, "word", false, false, null, null, null, 2, "auto").get("text"));
    }
}
