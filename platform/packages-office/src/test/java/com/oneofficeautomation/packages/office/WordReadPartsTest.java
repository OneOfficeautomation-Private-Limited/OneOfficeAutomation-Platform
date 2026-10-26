package com.oneofficeautomation.packages.office;

import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.xmlbeans.XmlCursor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.xml.namespace.QName;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** read_text by bookmark and page; read_tables by title and with a row/column span. */
class WordReadPartsTest {
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private final WordPackage word = new WordPackage();
    private Path dir;
    private ActionContext ctx;

    @BeforeEach void setUp() throws Exception {
        dir = Files.createTempDirectory("ooa-word-parts");
        ctx = OfficeTestSupport.ctx(dir);
        try (XWPFDocument d = new XWPFDocument(); OutputStream out = Files.newOutputStream(dir.resolve("parts.docx"))) {
            d.createParagraph().createRun().setText("Intro");                         // page 1
            XWPFParagraph p = d.createParagraph();
            p.createRun().setText("Lead ");
            var bm = p.getCTP().addNewBookmarkStart();
            bm.setName("terms");
            bm.setId(BigInteger.TEN);
            p.createRun().setText("Inside one");
            XWPFParagraph q = d.createParagraph();
            q.createRun().setText("Inside two");
            q.getCTP().addNewBookmarkEnd().setId(BigInteger.TEN);
            q.createRun().setText(" after");
            XWPFRun br = d.createParagraph().createRun();
            br.setText("End of page one");
            br.addBreak(BreakType.PAGE);
            br.setText("Top of page two", 1);                                       // page 2
            XWPFParagraph newPage = d.createParagraph();
            newPage.setPageBreak(true);
            newPage.createRun().setText("Page three text");                           // page 3

            XWPFTable t1 = d.createTable(3, 3);
            fill(t1, new String[][]{{"A", "B", "C"}, {"1", "2", "3"}, {"4", "5", "6"}});
            XWPFTable t2 = d.createTable(2, 2);
            fill(t2, new String[][]{{"Item", "Price"}, {"Pen", "2"}});
            altTitle(t2, "Prices");
            XWPFParagraph cap = d.createParagraph();
            cap.setStyle("Caption");
            cap.createRun().setText("Table 3: Regional sales");
            XWPFTable t3 = d.createTable(2, 2);
            fill(t3, new String[][]{{"Region", "Total"}, {"North", "9"}});
            d.write(out);
        }
        word.open(ctx, "parts.docx", false, "word");
    }

    private static void fill(XWPFTable t, String[][] v) {
        for (int r = 0; r < v.length; r++) for (int c = 0; c < v[r].length; c++) t.getRow(r).getCell(c).setText(v[r][c]);
    }

    private static void altTitle(XWPFTable t, String title) {
        var pr = t.getCTTbl().getTblPr() != null ? t.getCTTbl().getTblPr() : t.getCTTbl().addNewTblPr();
        XmlCursor c = pr.newCursor();
        try {
            c.toEndToken();
            c.beginElement(new QName(W, "tblCaption", "w"));
            c.insertAttributeWithValue(new QName(W, "val", "w"), title);
        } finally { c.close(); }
    }

    private static String code(Runnable r) { return assertThrows(ActionException.class, r::run).code(); }

    @SuppressWarnings("unchecked")
    private static List<Object> rows(Map<String, Object> table) { return (List<Object>) table.get("rows"); }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> table(Map<String, Object> out) { return (Map<String, Object>) out.get("table"); }

    @Test void readTextInsideBookmark() {
        Map<String, Object> out = word.readText(ctx, "word", false, false, null, null, "terms", null);
        assertEquals("Inside one\nInside two", out.get("text"));
        assertEquals(List.of("Inside one", "Inside two"), out.get("paragraphs"));
        assertEquals("WORD.NO_BOOKMARK", code(() -> word.readText(ctx, "word", false, false, null, null, "missing", null)));
    }

    @Test void readTextOfOnePage() {
        String one = (String) word.readText(ctx, "word", false, false, null, null, null, 1).get("text");
        assertTrue(one.startsWith("Intro\nLead Inside one"), one);
        assertTrue(one.endsWith("End of page one"), one);
        assertFalse(one.contains("Top of page two"));
        assertEquals("Top of page two", word.readText(ctx, "word", false, false, null, null, null, 2).get("text"));
        String three = (String) word.readText(ctx, "word", false, false, null, null, null, 3).get("text");
        assertTrue(three.startsWith("Page three text"), three);
        assertTrue(three.contains("Regional sales"), three);
        assertEquals("WORD.NO_PAGE", code(() -> word.readText(ctx, "word", false, false, null, null, null, 9)));
        assertEquals("WORD.NO_PAGE", code(() -> word.readText(ctx, "word", false, false, null, null, null, 0)));
        // The bookmark wins over the page; the old form still reads everything.
        assertEquals("Inside one\nInside two", word.readText(ctx, "word", false, false, null, null, "terms", 2).get("text"));
        assertTrue(((String) word.readText(ctx, "word").get("text")).contains("Page three text"));
    }

    @Test void readTableByAltTitleAndByCaption() {
        Map<String, Object> prices = table(word.readTables(ctx, "word", null, true, "prices", null, null, null, null));
        assertEquals(List.of("Item", "Price"), prices.get("columns"));
        assertEquals(List.of(List.of("Pen", "2")), rows(prices));
        Map<String, Object> sales = table(word.readTables(ctx, "word", null, true, "Regional sales", null, null, null, null));
        assertEquals(List.of("Region", "Total"), sales.get("columns"));
        assertEquals("WORD.NO_TABLE", code(() -> word.readTables(ctx, "word", null, true, "Nothing", null, null, null, null)));
    }

    @Test void readPartOfATable() {
        Map<String, Object> out = word.readTables(ctx, "word", 1, false, null, 2, 3, 2, 3);
        Map<String, Object> t = table(out);
        assertEquals(List.of("Column1", "Column2"), t.get("columns"));
        assertEquals(List.of(List.of("2", "3"), List.of("5", "6")), rows(t));
        assertEquals(3, ((Number) out.get("count")).intValue());
        // With header, the first row read gives the names.
        Map<String, Object> h = table(word.readTables(ctx, "word", 1, true, null, 2, null, null, 2));
        assertEquals(List.of("1", "2"), h.get("columns"));
        assertEquals(List.of(List.of("4", "5")), rows(h));
        assertEquals("WORD.BAD_RANGE", code(() -> word.readTables(ctx, "word", 1, true, null, 3, 2, null, null)));
        assertEquals("WORD.BAD_RANGE", code(() -> word.readTables(ctx, "word", 1, true, null, null, null, 0, null)));
        // The old four-input form is unchanged.
        assertEquals(3, ((List<?>) word.readTables(ctx, "word", null, true).get("tables")).size());
    }
}
