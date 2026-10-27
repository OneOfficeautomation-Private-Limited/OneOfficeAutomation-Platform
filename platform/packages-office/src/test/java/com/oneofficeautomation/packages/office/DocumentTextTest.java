package com.oneofficeautomation.packages.office;

import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTextBox;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.*;

/** X8: the text of old Word, RTF and PowerPoint files, without opening a session. */
class DocumentTextTest {

    @Test void extractsTextFromOldAndNewFormats() throws Exception {
        Path w = Files.createTempDirectory("ooa-doctext");
        ActionContext ctx = OfficeTestSupport.ctx(w);
        Files.writeString(w.resolve("memo.rtf"), "{\\rtf1\\ansi{\\fonttbl\\f0\\fswiss Helvetica;}\\f0 Invoices are due in {\\b 30 days}.\\par}");
        assertTrue(((String) new WordPackage().extractText(ctx, "memo.rtf").get("text")).contains("Invoices are due in 30 days."));

        try (HSLFSlideShow show = new HSLFSlideShow(); OutputStream out = Files.newOutputStream(w.resolve("old.ppt"))) {
            HSLFSlide slide = show.createSlide();
            HSLFTextBox box = slide.createTextBox();
            box.setText("Quarterly results");
            show.write(out);
        }
        assertTrue(((String) new PowerPointPackage().extractText(ctx, "old.ppt", false).get("text")).contains("Quarterly results"));

        try (XWPFDocument d = new XWPFDocument(); OutputStream out = Files.newOutputStream(w.resolve("new.docx"))) {
            d.createParagraph().createRun().setText("A new document.");
            d.write(out);
        }
        assertTrue(((String) new WordPackage().extractText(ctx, "new.docx").get("text")).contains("A new document."));

        Files.writeString(w.resolve("fake.doc"), "not a word file");
        assertEquals("OFFICE.UNREADABLE", assertThrows(ActionException.class, () -> new WordPackage().extractText(ctx, "fake.doc")).code());
        Files.writeString(w.resolve("a.odt"), "x");
        assertEquals("OFFICE.UNSUPPORTED", assertThrows(ActionException.class, () -> DocumentText.text(w.resolve("a.odt"), false)).code());
    }
}
