package com.oneofficeautomation.packages.office;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import com.oneofficeautomation.sdk.SecureString;
import org.apache.poi.xslf.usermodel.*;
import org.apache.poi.xwpf.usermodel.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.geom.Rectangle2D;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Options added to the Word and PowerPoint actions after comparing them with the equivalent AA actions. */
class WordPowerPointPropertiesTest {
    private final WordPackage word = new WordPackage();
    private final PowerPointPackage ppt = new PowerPointPackage();
    private Path dir;
    private ActionContext ctx;
    private static final SecureString PW = new SecureString("Deck-Pass-42", "vault:test");

    @BeforeEach void setUp() throws Exception {
        dir = Files.createTempDirectory("ooa-office-props");
        ctx = OfficeTestSupport.ctx(dir);
    }

    private static String code(Runnable r) { return assertThrows(ActionException.class, r::run).code(); }
    private static int n(Object o) { return ((BigDecimal) o).intValue(); }
    private XWPFDocument doc() { return ctx.session("word", WordPackage.DocSession.class).doc; }
    private XMLSlideShow deck(String s) { return ctx.session(s, PowerPointPackage.DeckSession.class).ppt; }

    private void wordDoc() {
        word.createDocument(ctx, "letter.docx", false, "word");
        XWPFDocument d = doc();
        d.getParagraphs().get(0).createRun().setText("Dear Customer, the CUSTOMER portal is ready.");
        XWPFParagraph p2 = d.createParagraph();
        p2.createRun().setText("Customers and customer care. Signed: customer team.");
        XWPFParagraph p3 = d.createParagraph();
        p3.getCTP().addNewBookmarkStart().setName("sig");
        p3.getCTP().getBookmarkStartArray(0).setId(BigInteger.ONE);
        p3.createRun().setText("Signature");
        word.save(ctx, null, "word");
    }

    // ------------------------------------------------------------------------------------------------ Word

    @Test void wordPasswordReadOnlyTemplateAndClose() throws Exception {
        wordDoc();
        word.saveAs(ctx, "secret.docx", "docx", "word", true, PW);
        word.close(ctx, "word");
        assertEquals("WORD.PASSWORD_REQUIRED", code(() -> word.open(ctx, "secret.docx", false, "w")));
        assertEquals("WORD.WRONG_PASSWORD", code(() -> word.open(ctx, "secret.docx", false, "w", false, new SecureString("bad", "x"))));
        word.open(ctx, "secret.docx", false, "w", true, PW);
        assertTrue(((String) word.readText(ctx, "w").get("text")).contains("Dear Customer"));
        assertEquals("WORD.READ_ONLY", code(() -> word.save(ctx, null, "w")));
        word.close(ctx, "w");

        word.createDocument(ctx, "from-template.docx", false, "t", null, "letter.docx");
        assertTrue(((String) word.readText(ctx, "t").get("text")).contains("Signature"));
        word.insertText(ctx, "P.S.", "end", null, true, "t");
        word.close(ctx, "t", true);
        word.open(ctx, "from-template.docx", false, "t");
        assertTrue(((String) word.readText(ctx, "t").get("text")).endsWith("P.S."));
        assertEquals("WORD.EXISTS", code(() -> word.saveAs(ctx, "letter.docx", "docx", "t", false, null)));
    }

    @Test void wordReplaceAndFormatOptions() {
        wordDoc();
        assertEquals(1, n(word.replaceText(ctx, "customer", "client", "word", true, true, "first").get("count")));
        assertTrue(doc().getParagraphs().get(1).getText().contains("customer team"));  // first whole-word, case-sensitive match is "customer care"
        assertTrue(doc().getParagraphs().get(1).getText().contains("client care"));
        assertEquals(3, n(word.replaceText(ctx, "customer", "Client", "word", false, true, "all").get("count")));
        assertEquals("Dear Client, the Client portal is ready.", doc().getParagraphs().get(0).getText());
        assertEquals("Customers and client care. Signed: Client team.", doc().getParagraphs().get(1).getText());

        assertEquals(1, n(word.formatText(ctx, "client", null, null, null, null, null, null, "word", false, false, "last", "yellow", true).get("count")));
        XWPFRun last = doc().getParagraphs().get(1).getRuns().stream().filter(r -> "Client".equals(r.text())).findFirst().orElseThrow();
        assertTrue(last.isStrikeThrough());
        assertEquals("yellow", last.getTextHighlightColor().toString());
        assertEquals("WORD.BAD_OCCURRENCE", code(() -> word.formatText(ctx, "x", true, null, null, null, null, null, "word", true, false, "second", null, null)));
    }

    @Test void wordInsertTextPictureAndTableOptions() throws Exception {
        wordDoc();
        word.insertText(ctx, "URGENT", "start", null, true, "word", true, null, null, 16.0, "#C00000", null);
        XWPFRun r = doc().getParagraphs().get(0).getRuns().get(0);
        assertEquals("URGENT", r.text());
        assertTrue(r.isBold());
        assertEquals("C00000", r.getColor());

        Path png = OfficeTestSupport.png(dir, "logo.png", 200, 100, Color.BLUE);
        word.insertPicture(ctx, png.toString(), null, null, "logo", "word", 30.0, "end", "sig", "center");
        XWPFParagraph pic = doc().getParagraphs().stream().filter(p -> !p.getRuns().isEmpty() && !p.getRuns().get(0).getEmbeddedPictures().isEmpty()).findFirst().orElseThrow();
        assertEquals(ParagraphAlignment.CENTER, pic.getAlignment());
        var inline = pic.getRuns().get(0).getCTR().getDrawingArray(0).getInlineArray(0);
        assertEquals(org.apache.poi.util.Units.toEMU(60), inline.getExtent().getCx());  // height 30 keeps the 2:1 ratio
        assertEquals(org.apache.poi.util.Units.toEMU(30), inline.getExtent().getCy());

        word.insertPicture(ctx, png.toString(), null, null, "logo", "word");
        Path red = OfficeTestSupport.png(dir, "red.png", 10, 10, Color.RED);
        assertEquals(1, n(word.findReplacePicture(ctx, red.toString(), "logo", null, "word", "last", "new logo", 50.0, null).get("count")));
        assertEquals(1, n(word.findReplacePicture(ctx, red.toString(), "new logo", null, "word", "all", null, null, null).get("count")));

        Map<String, Object> t = Values.table(List.of("Item", "Qty"), List.of(List.of("Pen", 2), List.of("Ink", 5)));
        word.addTable(ctx, t, "word", false, "Customers and", null, "TableGrid");
        List<IBodyElement> els = doc().getBodyElements();
        int ti = -1;
        for (int i = 0; i < els.size(); i++) if (els.get(i) instanceof XWPFTable) ti = i;
        assertTrue(els.get(ti - 1) instanceof XWPFParagraph p && p.getText().contains("Customers"));
        XWPFTable tb = (XWPFTable) els.get(ti);
        assertEquals(2, tb.getNumberOfRows());
        assertEquals("Pen", tb.getRow(0).getCell(0).getText());
        assertEquals("TableGrid", tb.getStyleID());
        word.addTable(ctx, t, "word");

        Map<String, Object> read = word.readTables(ctx, "word", 2, false);
        assertEquals(2, n(read.get("count")));
        @SuppressWarnings("unchecked") Map<String, Object> second = (Map<String, Object>) read.get("table");
        assertEquals(List.of("Column1", "Column2"), second.get("columns"));
        assertEquals(3, ((List<?>) second.get("rows")).size());
        assertEquals("WORD.NO_TABLE", code(() -> word.readTables(ctx, "word", 5, true)));

        String withTables = (String) word.readText(ctx, "word", true, false, null, null).get("text");
        assertTrue(withTables.contains("Pen"));
        assertFalse(((String) word.readText(ctx, "word").get("text")).contains("Pen"));
        assertEquals(List.of("Dear Customer, the CUSTOMER portal is ready."), word.readText(ctx, "word", false, false, 2, 2).get("paragraphs"));

        word.saveAs(ctx, "letter.txt", "txt", "word", true, null);
        assertTrue(Files.readString(dir.resolve("letter.txt")).contains("Signature"));
    }

    // ------------------------------------------------------------------------------------------------ PowerPoint

    @Test void powerPointPasswordReadOnlyCreateAndClose() {
        ppt.createPresentation(ctx, "deck.pptx", false, "p", PW, null);
        ppt.addSlide(ctx, "title", "Hello", "World", null, "p");
        ppt.close(ctx, "p", true);
        assertEquals("PPT.PASSWORD_REQUIRED", code(() -> ppt.open(ctx, "deck.pptx", false, "p")));
        ppt.open(ctx, "deck.pptx", false, "p", true, PW);
        assertEquals(1, n(ppt.slideCount(ctx, "p").get("count")));
        assertEquals("PPT.READ_ONLY", code(() -> ppt.save(ctx, null, "p")));
        ppt.saveAs(ctx, "plain.pptx", "pptx", "p", true, null);  // keeps the open password
        ppt.close(ctx, "p");
        assertEquals("PPT.PASSWORD_REQUIRED", code(() -> ppt.open(ctx, "plain.pptx", false, "p")));
        assertEquals("PPT.EXISTS", code(() -> ppt.createPresentation(ctx, "deck.pptx", false, "q", null, null)));
        assertEquals("PPT.OPEN_FAILED", code(() -> ppt.createPresentation(ctx, "copy.pptx", false, "q", null, "plain.pptx")));  // encrypted templates are not supported
    }

    @Test void powerPointSlidesTextAndPictures() throws Exception {
        ppt.open(ctx, "talk.pptx", true, "p");
        ppt.addSlide(ctx, "title", "Customer review", "for the customer team", null, "p");
        ppt.addSlide(ctx, "two_content", null, null, null, "p");
        ppt.addSlide(ctx, "blank", null, null, null, "p");
        ppt.addSlide(ctx, "blank", null, null, null, "p");
        ppt.deleteSlide(ctx, 3, "p", 2);
        assertEquals(2, n(ppt.slideCount(ctx, "p").get("count")));
        assertEquals("PPT.NO_SLIDE", code(() -> ppt.deleteSlide(ctx, 2, "p", 5)));

        ppt.insertText(ctx, 2, "Agenda", "none", 10, 10, 300, 40, 20.0, "p", true, true, null, "#00B050", "Arial", "center");
        XSLFTextShape box = (XSLFTextShape) deck("p").getSlides().get(1).getShapes().get(deck("p").getSlides().get(1).getShapes().size() - 1);
        XSLFTextRun run = box.getTextParagraphs().get(0).getTextRuns().get(0);
        assertTrue(run.isBold() && run.isItalic());
        assertEquals("Arial", run.getFontFamily());
        assertEquals(org.apache.poi.sl.usermodel.TextParagraph.TextAlign.CENTER, box.getTextParagraphs().get(0).getTextAlign());

        assertEquals(1, n(ppt.findReplaceText(ctx, "CUSTOMER", "Client", null, "p", false, true, "last").get("count")));
        String all = (String) ppt.readText(ctx, "p").get("text");
        assertTrue(all.contains("Customer review") && all.contains("Client team"), all);
        assertEquals(1, n(ppt.formatText(ctx, "customer", null, null, null, null, null, null, null, "p", false, false, "first", true).get("count")));

        Path png = OfficeTestSupport.png(dir, "p.png", 100, 50, Color.GREEN);
        Map<String, Object> pic = ppt.insertPicture(ctx, 2, png.toString(), 20, 20, null, "chart", "p", 100.0);
        assertFalse(((String) pic.get("shape")).isEmpty());
        XSLFPictureShape shape = deck("p").getSlides().get(1).getShapes().stream().filter(s -> s instanceof XSLFPictureShape).map(s -> (XSLFPictureShape) s).findFirst().orElseThrow();
        assertEquals(200, shape.getAnchor().getWidth(), 0.5);
        Path red = OfficeTestSupport.png(dir, "r.png", 10, 10, Color.RED);
        assertEquals(0, n(ppt.findReplacePicture(ctx, red.toString(), "chart", null, "p", 1, "all", null, null, null, true).get("count")));
        assertEquals(1, n(ppt.findReplacePicture(ctx, red.toString(), "chart", null, "p", 2, "first", "new chart", 50.0, 25.0, false).get("count")));
        Rectangle2D a = shape.getAnchor();
        assertEquals(50, a.getWidth(), 0.5);
        assertEquals(25, a.getHeight(), 0.5);
        assertEquals(20, a.getX(), 0.5);

        Map<String, Object> t = Values.table(List.of("A", "B"), List.of(List.of("1", "2")));
        Map<String, Object> tr = ppt.insertTable(ctx, 2, t, 10, 200, 300, 12, "p", false, 30.0, List.of(100, 50));
        assertNotNull(tr.get("shape"));
        XSLFTable table = deck("p").getSlides().get(1).getShapes().stream().filter(s -> s instanceof XSLFTable).map(s -> (XSLFTable) s).findFirst().orElseThrow();
        assertEquals(1, table.getNumberOfRows());
        assertEquals(100, table.getColumnWidth(0), 0.5);
        assertEquals(30, table.getRows().get(0).getHeight(), 0.5);
        assertEquals("PPT.BAD_WIDTHS", code(() -> ppt.insertTable(ctx, 2, t, 10, 200, 300, 12, "p", true, null, List.of(1))));

        ppt.createPresentation(ctx, "other.pptx", false, "o", null, null);
        assertEquals(1, n(ppt.copySlide(ctx, 1, null, "o", "p").get("slide")));
        assertTrue(((String) ppt.readText(ctx, "o").get("text")).contains("Customer review"));
        ppt.close(ctx, "o", true);
        ppt.open(ctx, "other.pptx", false, "o");
        assertEquals(1, n(ppt.slideCount(ctx, "o").get("count")));
        assertEquals("PPT.EXISTS", code(() -> ppt.saveAs(ctx, "other.pptx", "pptx", "p", false, null)));
    }
}
