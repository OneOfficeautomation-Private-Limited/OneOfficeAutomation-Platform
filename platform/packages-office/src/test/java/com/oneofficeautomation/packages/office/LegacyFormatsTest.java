package com.oneofficeautomation.packages.office;

import com.oneofficeautomation.packages.OfficeConvert;
import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import com.oneofficeautomation.sdk.SecureString;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Gap: Word .doc/.rtf/.odt and PowerPoint .ppt/.odp, through Office or LibreOffice. Skipped where neither is installed
 * (CI's Windows and Linux machines have no Office); run on the test PC with Office 2007.
 */
class LegacyFormatsTest {

    static String code(org.junit.jupiter.api.function.Executable e) { return assertThrows(ActionException.class, e).code(); }

    @Test void wordDocAndRtfRoundTrip() throws Exception {
        Assumptions.assumeTrue(OfficeConvert.available("word"), "needs Microsoft Word or LibreOffice");
        Path dir = Files.createTempDirectory("ooa-legacy-word");
        ActionContext ctx = OfficeTestSupport.ctx(dir);
        WordPackage w = new WordPackage();
        w.createDocument(ctx, "old.doc", false, "word");
        w.addParagraph(ctx, "Invoice 4711 approved", false, null, "word");
        w.save(ctx, null, "word");
        w.close(ctx, "word");
        byte[] head = Arrays.copyOf(Files.readAllBytes(dir.resolve("old.doc")), 8);
        assertArrayEquals(new byte[]{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1}, head, "a Word 97-2003 (OLE) file");

        w.open(ctx, "old.doc", false, "word");
        assertTrue(String.valueOf(w.readText(ctx, "word").get("text")).contains("Invoice 4711 approved"));
        w.addParagraph(ctx, "Second line", false, null, "word");
        w.saveAs(ctx, "copy.rtf", "rtf", "word");
        assertTrue(Files.readString(dir.resolve("copy.rtf")).startsWith("{\\rtf"), "an RTF file");
        w.saveAs(ctx, "copy", "odt", "word");
        assertTrue(Files.isRegularFile(dir.resolve("copy.odt")), "extension added for the format");
        assertEquals("WORD.PASSWORD_NOT_SUPPORTED", code(() -> w.saveAs(ctx, "x.doc", "doc", "word", true, new SecureString("pw", "v"))));
        w.close(ctx, "word");

        w.open(ctx, "copy.rtf", false, "word");
        String text = String.valueOf(w.readText(ctx, "word").get("text"));
        assertTrue(text.contains("Invoice 4711 approved") && text.contains("Second line"), text);
        w.close(ctx, "word");
        assertTrue(String.valueOf(w.extractText(ctx, "old.doc").get("text")).contains("Invoice 4711"));
    }

    @Test void powerPointPptRoundTrip() throws Exception {
        Assumptions.assumeTrue(OfficeConvert.available("powerpoint"), "needs Microsoft PowerPoint or LibreOffice");
        Path dir = Files.createTempDirectory("ooa-legacy-ppt");
        ActionContext ctx = OfficeTestSupport.ctx(dir);
        PowerPointPackage p = new PowerPointPackage();
        p.open(ctx, "deck.ppt", true, "powerpoint");
        p.addSlide(ctx, "title_and_content", "Quarterly results", "Revenue up\nCosts down", null, "powerpoint");
        p.save(ctx, null, "powerpoint");
        p.close(ctx, "powerpoint");
        byte[] head = Arrays.copyOf(Files.readAllBytes(dir.resolve("deck.ppt")), 4);
        assertArrayEquals(new byte[]{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0}, head, "a PowerPoint 97-2003 (OLE) file");
        p.open(ctx, "deck.ppt", false, "powerpoint");
        assertEquals(1, ((Number) p.slideCount(ctx, "powerpoint").get("count")).intValue());
        assertTrue(String.valueOf(p.readText(ctx, "powerpoint").get("text")).contains("Quarterly results"));
        try {
            p.saveAs(ctx, "deck2.odp", "odp", "powerpoint");
            assertTrue(Files.isRegularFile(dir.resolve("deck2.odp")));
        } catch (ActionException e) {
            // PowerPoint 2007 before SP2 has no OpenDocument format: the step must say so plainly
            assertEquals("PPT.CONVERTER_UNAVAILABLE", e.code());
            assertTrue(e.getMessage().contains("cannot save .odp files"), e.getMessage());
        }
        assertEquals("PPT.BAD_FORMAT", code(() -> p.saveAs(ctx, "deck3.key", "key", "powerpoint")));
        p.close(ctx, "powerpoint");
        assertEquals("pptx", OfficeConvert.extension(Path.of("A.PPTX")));
    }
}
