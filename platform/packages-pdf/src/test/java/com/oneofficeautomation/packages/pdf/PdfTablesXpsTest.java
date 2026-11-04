package com.oneofficeautomation.packages.pdf;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.runner.RunResult;
import com.oneofficeautomation.runner.Runner;
import com.oneofficeautomation.sdk.JsonLite;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Extract tables from text PDFs and read XPS text (gap G12). */
class PdfTablesXpsTest {
    private static final PDType1Font FONT = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

    private static void text(PDPageContentStream cs, float x, float y, String t) throws Exception {
        cs.beginText(); cs.setFont(FONT, 10); cs.newLineAtOffset(x, y); cs.showText(t); cs.endText();
    }

    /** An invoice: a title and an address (not tables), then line items in four columns; page 2 continues the items under the same header. */
    private static Path invoice(Path dir) throws Exception {
        Path p = dir.resolve("invoice.pdf");
        try (PDDocument d = new PDDocument()) {
            PDPage one = new PDPage(); d.addPage(one);
            try (PDPageContentStream cs = new PDPageContentStream(d, one)) {
                text(cs, 72, 740, "INVOICE INV-2026-0042");
                text(cs, 72, 720, "Acme Trading Ltd, 4 Main Street, Pune");
                String[][] rows = {{"Item", "Description", "Qty", "Amount"}, {"P-100", "Blue pens, box of 10", "3", "120.00"},
                        {"P-200", "A4 paper", "10", "45.50"}, {"P-300", "Stapler", "1", "8.99"}};
                float y = 660;
                for (String[] r : rows) { text(cs, 72, y, r[0]); text(cs, 150, y, r[1]); text(cs, 360, y, r[2]); text(cs, 440, y, r[3]); y -= 16; }
                text(cs, 72, 560, "Thank you for your business.");
            }
            PDPage two = new PDPage(); d.addPage(two);
            try (PDPageContentStream cs = new PDPageContentStream(d, two)) {
                String[][] rows = {{"Item", "Description", "Qty", "Amount"}, {"P-400", "Toner cartridge", "2", "150.00"}};
                float y = 740;
                for (String[] r : rows) { text(cs, 72, y, r[0]); text(cs, 150, y, r[1]); text(cs, 360, y, r[2]); text(cs, 440, y, r[3]); y -= 16; }
            }
            d.save(p.toFile());
        }
        return p;
    }

    @SuppressWarnings("unchecked")
    private static RunResult run(Path w, String args) {
        Map<String, Object> flow = (Map<String, Object>) JsonLite.parse("""
            {"kind":"TaskBot","id":"x","name":"x",
             "variables":[{"name":"tables","type":"List","direction":"output"},{"name":"t","type":"Table","direction":"output"},{"name":"n","type":"Number","direction":"output"}],
             "steps":[{"id":"e","action":"ooa.pdf/extract_tables","args":%s,"out":{"tables":"tables","table":"t","count":"n"}}]}""".formatted(args));
        return Runner.builder(FirstPartyPackages.registry()).workDir(w).build().run(flow, Map.of());
    }

    @SuppressWarnings("unchecked")
    @Test void lineItemsAcrossTwoPagesBecomeOneTable() throws Exception {
        Path w = Files.createTempDirectory("ooa-pdft");
        invoice(w);
        RunResult r = run(w, "{\"path\":\"invoice.pdf\"}");
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals(1, ((Number) r.outputs().get("n")).intValue(), "the title, address and thank-you lines are not tables");
        Map<String, Object> t = (Map<String, Object>) r.outputs().get("t");
        assertEquals(List.of("Item", "Description", "Qty", "Amount"), t.get("columns"));
        List<List<Object>> rows = (List<List<Object>>) t.get("rows");
        assertEquals(4, rows.size(), "three rows on page 1, one on page 2, the repeated header left out");
        assertEquals(List.of("P-100", "Blue pens, box of 10", "3", "120.00"), rows.get(0));
        assertEquals(List.of("P-400", "Toner cartridge", "2", "150.00"), rows.get(3));
    }

    @SuppressWarnings("unchecked")
    @Test void pagesKeptApartWithoutHeaders() throws Exception {
        Path w = Files.createTempDirectory("ooa-pdft");
        invoice(w);
        RunResult r = run(w, "{\"path\":\"invoice.pdf\",\"merge_across_pages\":false,\"header\":false}");
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals(2, ((Number) r.outputs().get("n")).intValue());
        Map<String, Object> second = (Map<String, Object>) ((List<Object>) r.outputs().get("tables")).get(1);
        assertEquals(List.of("Column1", "Column2", "Column3", "Column4"), second.get("columns"));
        assertEquals(2, ((List<?>) second.get("rows")).size());
        RunResult p2 = run(w, "{\"path\":\"invoice.pdf\",\"pages\":\"2\"}");
        assertEquals(1, ((List<?>) ((Map<String, Object>) p2.outputs().get("t")).get("rows")).size());
    }

    /** Excel's PDF layout: headings on the left of each column, numbers right-aligned, two headings only 6 points apart. */
    @SuppressWarnings("unchecked")
    @Test void excelStyleLeftHeadingsOverRightAlignedNumbers() throws Exception {
        Path w = Files.createTempDirectory("ooa-pdft");
        try (PDDocument d = new PDDocument()) {
            PDPage pg = new PDPage(); d.addPage(pg);
            try (PDPageContentStream cs = new PDPageContentStream(d, pg)) {
                float y = 700;
                text(cs, 53, y, "Item"); text(cs, 103, y, "Description"); text(cs, 243, y, "Qty"); text(cs, 293, y, "Unit price"); text(cs, 343, y, "Amount");
                String[][] rows = {{"P-100", "Blue pens, box of 10", "3", "40.00", "120.00"}, {"P-200", "A4 paper ream", "10", "4.55", "45.50"}, {"P-300", "Stapler", "1", "8.99", "8.99"}};
                for (String[] r : rows) {
                    y -= 15;
                    text(cs, 53, y, r[0]); text(cs, 103, y, r[1]);
                    // right-aligned at x = 289 (Qty), 339 (Unit price) and 389 (Amount)
                    text(cs, 289 - FONT.getStringWidth(r[2]) / 100, y, r[2]);
                    text(cs, 339 - FONT.getStringWidth(r[3]) / 100, y, r[3]);
                    text(cs, 389 - FONT.getStringWidth(r[4]) / 100, y, r[4]);
                }
            }
            d.save(w.resolve("excel.pdf").toFile());
        }
        RunResult r = run(w, "{\"path\":\"excel.pdf\"}");
        assertTrue(r.ok(), String.valueOf(r.error()));
        Map<String, Object> t = (Map<String, Object>) r.outputs().get("t");
        assertEquals(List.of("Item", "Description", "Qty", "Unit price", "Amount"), t.get("columns"));
        assertEquals(List.of("P-200", "A4 paper ream", "10", "4.55", "45.50"), ((List<?>) t.get("rows")).get(1));
    }

    @SuppressWarnings("unchecked")
    @Test void aPageWithoutTablesGivesNone() throws Exception {
        Path w = Files.createTempDirectory("ooa-pdft");
        try (PDDocument d = new PDDocument()) {
            PDPage pg = new PDPage(); d.addPage(pg);
            try (PDPageContentStream cs = new PDPageContentStream(d, pg)) { text(cs, 72, 700, "Just one line of text."); text(cs, 72, 680, "And another."); }
            d.save(w.resolve("plain.pdf").toFile());
        }
        RunResult r = run(w, "{\"path\":\"plain.pdf\"}");
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals(0, ((Number) r.outputs().get("n")).intValue());
        assertEquals(List.of(), ((Map<String, Object>) r.outputs().get("t")).get("rows"));
    }

    /** A two-page XPS: a FixedDocument listing the pages, each page with Glyphs out of order on purpose. */
    private static Path xps(Path dir) throws Exception {
        Path p = dir.resolve("letter.xps");
        String ns = "http://schemas.microsoft.com/xps/2005/06";
        Map<String, String> files = new LinkedHashMap<>();
        files.put("FixedDocSeq.fdseq", "<FixedDocumentSequence xmlns='" + ns + "'><DocumentReference Source='Documents/1/FixedDoc.fdoc'/></FixedDocumentSequence>");
        files.put("Documents/1/FixedDoc.fdoc", "<FixedDocument xmlns='" + ns + "'><PageContent Source='Pages/1.fpage'/><PageContent Source='Pages/2.fpage'/></FixedDocument>");
        files.put("Documents/1/Pages/1.fpage", "<FixedPage xmlns='" + ns + "' Width='816' Height='1056'>"
                + "<Glyphs OriginX='200' OriginY='100' FontRenderingEmSize='12' UnicodeString='Smith'/>"
                + "<Glyphs OriginX='96' OriginY='100' FontRenderingEmSize='12' UnicodeString='Dear Ms'/>"
                + "<Glyphs OriginX='96' OriginY='130' FontRenderingEmSize='12' UnicodeString='Your order &amp; invoice are attached.'/></FixedPage>");
        files.put("Documents/1/Pages/2.fpage", "<FixedPage xmlns='" + ns + "' Width='816' Height='1056'>"
                + "<Glyphs OriginX='96' OriginY='100' FontRenderingEmSize='12' UnicodeString='{}{Regards}'/></FixedPage>");
        try (OutputStream o = Files.newOutputStream(p); ZipOutputStream z = new ZipOutputStream(o)) {
            for (var e : files.entrySet()) { z.putNextEntry(new ZipEntry(e.getKey())); z.write(e.getValue().getBytes(StandardCharsets.UTF_8)); z.closeEntry(); }
        }
        return p;
    }

    @SuppressWarnings("unchecked")
    @Test void xpsTextInReadingOrder() throws Exception {
        Path w = Files.createTempDirectory("ooa-xps");
        xps(w);
        Map<String, Object> flow = (Map<String, Object>) JsonLite.parse("""
            {"kind":"TaskBot","id":"x","name":"x",
             "variables":[{"name":"text","type":"String","direction":"output"},{"name":"pages","type":"List","direction":"output"},{"name":"n","type":"Number","direction":"output"}],
             "steps":[{"id":"r","action":"ooa.pdf/read_xps_text","args":{"path":"letter.xps"},"out":{"text":"text","pages":"pages","page_count":"n"}}]}""");
        RunResult r = Runner.builder(FirstPartyPackages.registry()).workDir(w).build().run(flow, Map.of());
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals(2, ((Number) r.outputs().get("n")).intValue());
        assertEquals(List.of("Dear Ms Smith\nYour order & invoice are attached.", "{Regards}"), r.outputs().get("pages"));
        Files.writeString(w.resolve("not.xps"), "plain text");
        Map<String, Object> bad = (Map<String, Object>) JsonLite.parse("""
            {"kind":"TaskBot","id":"y","name":"y","steps":[{"id":"r","action":"ooa.pdf/read_xps_text","args":{"path":"not.xps"}}]}""");
        RunResult b = Runner.builder(FirstPartyPackages.registry()).workDir(w).build().run(bad, Map.of());
        assertEquals("PDF.INVALID", b.error().get("code"));
    }
}
