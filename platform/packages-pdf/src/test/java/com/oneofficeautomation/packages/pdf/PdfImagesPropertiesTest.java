package com.oneofficeautomation.packages.pdf;

import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import com.oneofficeautomation.sdk.SecureString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class PdfImagesPropertiesTest {
    static ActionContext ctx(Path w) {
        return new ActionContext("run", "s", "default", w, new HashMap<>(), new HashMap<>(), new AtomicBoolean(), List::of, (l, m) -> {});
    }

    static BufferedImage image(int w, int h, Color c) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setColor(c); g.fillRect(0, 0, w, h); g.dispose();
        return img;
    }

    @SuppressWarnings("unchecked")
    @Test void extractImagesAndReadProperties() throws Exception {
        Path w = Files.createTempDirectory("ooa-pdf-img");
        try (PDDocument d = new PDDocument()) {
            PDImageXObject logo = LosslessFactory.createFromImage(d, image(40, 20, Color.RED));
            PDImageXObject photo = LosslessFactory.createFromImage(d, image(120, 80, Color.BLUE));
            PDImageXObject icon = LosslessFactory.createFromImage(d, image(8, 8, Color.GREEN));
            PDPage p1 = new PDPage(), p2 = new PDPage();
            d.addPage(p1); d.addPage(p2);
            try (PDPageContentStream cs = new PDPageContentStream(d, p1)) { cs.drawImage(logo, 50, 700); cs.drawImage(icon, 10, 10); }
            try (PDPageContentStream cs = new PDPageContentStream(d, p2)) { cs.drawImage(photo, 50, 500); cs.drawImage(logo, 50, 700); }
            PDDocumentInformation info = d.getDocumentInformation();
            info.setTitle("Invoice 7"); info.setAuthor("Finance Agent"); info.setSubject("AP"); info.setKeywords("invoice, 2026");
            info.setCreator("OOA test"); info.setProducer("PDFBox");
            Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            cal.clear(); cal.set(2026, Calendar.OCTOBER, 19, 10, 30, 0);
            info.setCreationDate(cal);
            d.save(w.resolve("doc.pdf").toFile());
        }
        PdfPackage pdf = new PdfPackage();
        ActionContext c = ctx(w);
        Map<String, Object> r = pdf.extractImages(c, "doc.pdf", "images", 1, null);
        assertEquals(0, new BigDecimal(3).compareTo((BigDecimal) r.get("count")), "the logo used on both pages is saved once");
        List<Object> files = (List<Object>) r.get("files");
        BufferedImage first = ImageIO.read(Path.of((String) files.get(0)).toFile());
        assertTrue(Set.of(40, 8).contains(first.getWidth()));
        assertTrue(files.stream().anyMatch(f -> ((String) f).endsWith("doc_page2_img1.png")));
        Map<String, Object> big = pdf.extractImages(c, "doc.pdf", "big", 32, null);
        assertEquals(0, new BigDecimal(1).compareTo((BigDecimal) big.get("count")), "icons and the 20 px high logo are skipped");

        Map<String, Object> props = (Map<String, Object>) pdf.getProperties(c, "doc.pdf", null).get("properties");
        assertEquals("Invoice 7", props.get("title"));
        assertEquals("Finance Agent", props.get("author"));
        assertEquals("AP", props.get("subject"));
        assertEquals("invoice, 2026", props.get("keywords"));
        assertEquals("OOA test", props.get("creator"));
        assertEquals("PDFBox", props.get("producer"));
        assertEquals(LocalDateTime.of(2026, 10, 19, 10, 30), props.get("created"));
        assertNull(props.get("modified"));
        assertEquals(0, new BigDecimal(2).compareTo((BigDecimal) props.get("page_count")));
        assertEquals(false, props.get("encrypted"));

        try (PDDocument d = org.apache.pdfbox.Loader.loadPDF(w.resolve("doc.pdf").toFile())) {
            StandardProtectionPolicy pol = new StandardProtectionPolicy("owner", "user", new AccessPermission());
            pol.setEncryptionKeyLength(256);
            d.protect(pol);
            d.save(w.resolve("locked.pdf").toFile());
        }
        assertEquals("PDF.PASSWORD_REQUIRED", assertThrows(ActionException.class, () -> pdf.getProperties(c, "locked.pdf", null)).code());
        Map<String, Object> locked = (Map<String, Object>) pdf.getProperties(c, "locked.pdf", new SecureString("user", "test/pdf")).get("properties");
        assertEquals(true, locked.get("encrypted"));
        assertEquals("Invoice 7", locked.get("title"));
        assertEquals("FILE.NOT_FOUND", assertThrows(ActionException.class, () -> pdf.getProperties(c, "none.pdf", null)).code());
    }
}
