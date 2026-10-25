package com.oneofficeautomation.packages.pdf;

import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitWidthDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Split modes blank_page and bookmarks (with bookmark_level). */
class PdfSplitModesTest {
    private final PdfPackage pdf = new PdfPackage();
    private Path dir;
    private ActionContext ctx;

    /** Pages: A1, A2, (blank), B1, (blank), C1. Bookmarks: Chapter A -> 1, Chapter B -> 4 (child Section B.1 -> 5), Chapter C -> 6. */
    @BeforeEach void setUp() throws Exception {
        dir = Files.createTempDirectory("ooa-pdf-split");
        ctx = PdfImagesPropertiesTest.ctx(dir);
        String[] texts = {"A1", "A2", null, "B1", null, "C1"};
        try (PDDocument d = new PDDocument()) {
            for (String t : texts) {
                PDPage p = new PDPage();
                d.addPage(p);
                if (t != null) try (PDPageContentStream cs = new PDPageContentStream(d, p)) {
                    cs.beginText(); cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14); cs.newLineAtOffset(100, 700); cs.showText("Text " + t); cs.endText();
                }
            }
            PDDocumentOutline outline = new PDDocumentOutline();
            d.getDocumentCatalog().setDocumentOutline(outline);
            outline.addLast(item(d, "Chapter A", 1));
            PDOutlineItem b = item(d, "Chapter B", 4);
            b.addLast(item(d, "Section B.1", 5));
            outline.addLast(b);
            outline.addLast(item(d, "Chapter C", 6));
            d.save(dir.resolve("book.pdf").toFile());
            // A copy without bookmarks
            d.getDocumentCatalog().setDocumentOutline(null);
            d.save(dir.resolve("plain.pdf").toFile());
        }
    }

    private static PDOutlineItem item(PDDocument d, String title, int page) {
        PDOutlineItem i = new PDOutlineItem();
        i.setTitle(title);
        PDPageFitWidthDestination dest = new PDPageFitWidthDestination();
        dest.setPage(d.getPage(page - 1));
        i.setDestination(dest);
        return i;
    }

    private static String code(Runnable r) { return assertThrows(ActionException.class, r::run).code(); }

    @SuppressWarnings("unchecked")
    private List<String> parts(Map<String, Object> out) { return ((List<Object>) out.get("files")).stream().map(Object::toString).toList(); }

    /** Page count and the page texts (letters and digits only) of one part. */
    private static String describe(String file) throws Exception {
        try (PDDocument d = Loader.loadPDF(Path.of(file).toFile())) {
            return d.getNumberOfPages() + ":" + new PDFTextStripper().getText(d).replace("Text", "").replaceAll("\\s+", " ").trim();
        }
    }

    @Test void blankPageSeparatorsStartNewFilesAndAreLeftOut() throws Exception {
        List<String> files = parts(pdf.split(ctx, "book.pdf", "blank", 1, "blank_page", null, "doc", true, null, 1));
        assertEquals(3, files.size());
        assertEquals("2:A1 A2", describe(files.get(0)));
        assertEquals("1:B1", describe(files.get(1)));
        assertEquals("1:C1", describe(files.get(2)));
        assertTrue(files.get(0).endsWith("doc_part1.pdf"));
    }

    @Test void blankDetection() throws Exception {
        try (PDDocument d = Loader.loadPDF(dir.resolve("book.pdf").toFile())) {
            assertFalse(PdfPackage.isBlankPage(d, 1));
            assertTrue(PdfPackage.isBlankPage(d, 3));
        }
    }

    @Test void topLevelBookmarksStartNewFiles() throws Exception {
        List<String> files = parts(pdf.split(ctx, "book.pdf", "bm1", 1, "bookmarks", null, null, true, null, 1));
        assertEquals(3, files.size());
        assertEquals("3:A1 A2", describe(files.get(0)));
        assertEquals("2:B1", describe(files.get(1)));
        assertEquals("1:C1", describe(files.get(2)));
        assertTrue(files.get(0).endsWith("book_part1.pdf"));
    }

    @Test void deeperBookmarkLevelAlsoCutsAtSubBookmarks() throws Exception {
        List<String> files = parts(pdf.split(ctx, "book.pdf", "bm2", 1, "bookmarks", null, null, true, null, 2));
        assertEquals(4, files.size());
        assertEquals("1:B1", describe(files.get(1)));
        assertEquals("1:", describe(files.get(2)));
        assertEquals("1:C1", describe(files.get(3)));
    }

    @Test void errors() {
        assertEquals("PDF.NO_BOOKMARKS", code(() -> pdf.split(ctx, "plain.pdf", "x", 1, "bookmarks", null, null, true, null, 1)));
        assertEquals("PDF.BAD_BOOKMARK_LEVEL", code(() -> pdf.split(ctx, "book.pdf", "x", 1, "bookmarks", null, null, true, null, 0)));
        assertEquals("PDF.BAD_MODE", code(() -> pdf.split(ctx, "book.pdf", "x", 1, "chapters", null, null, true, null, 1)));
        // The old nine-input form still works and defaults to level 1.
        assertEquals(3, parts(pdf.split(ctx, "book.pdf", "old", 1, "bookmarks", null, null, true, null)).size());
    }
}
