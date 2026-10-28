package com.oneofficeautomation.packages.pdf;

import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** PDF extract_images resized to a dpi at the placed size, and read_form for chosen fields only. */
class PdfPanelOptionsTest {
    private final PdfPackage pdf = new PdfPackage();

    @SuppressWarnings("unchecked")
    @Test void extractImagesAtADpi() throws Exception {
        Path w = Files.createTempDirectory("ooa-pdf-dpi");
        try (PDDocument d = new PDDocument()) {
            PDImageXObject photo = LosslessFactory.createFromImage(d, PdfImagesPropertiesTest.image(100, 50, Color.BLUE));
            PDPage p = new PDPage();
            d.addPage(p);
            // shown 2 inches wide and 1 inch high (144 x 72 points)
            try (PDPageContentStream cs = new PDPageContentStream(d, p)) { cs.drawImage(photo, 50, 500, 144, 72); }
            d.save(w.resolve("photo.pdf").toFile());
        }
        ActionContext c = PdfImagesPropertiesTest.ctx(w);
        List<Object> stored = (List<Object>) pdf.extractImages(c, "photo.pdf", "stored", 1, null).get("files");
        assertEquals(100, ImageIO.read(Path.of((String) stored.get(0)).toFile()).getWidth(), "dpi 0 (old signature) keeps the stored pixels");
        List<Object> files = (List<Object>) pdf.extractImages(c, "photo.pdf", "at150", 1, null, null, "png", null, "color", 150).get("files");
        BufferedImage img = ImageIO.read(Path.of((String) files.get(0)).toFile());
        assertEquals(300, img.getWidth());
        assertEquals(150, img.getHeight());
    }

    @SuppressWarnings("unchecked")
    @Test void readOnlyChosenFormFields() throws Exception {
        Path w = Files.createTempDirectory("ooa-pdf-form");
        try (PDDocument d = new PDDocument()) {
            PDPage page = new PDPage();
            d.addPage(page);
            PDAcroForm form = new PDAcroForm(d);
            d.getDocumentCatalog().setAcroForm(form);
            PDResources res = new PDResources();
            res.put(org.apache.pdfbox.cos.COSName.getPDFName("Helv"), new PDType1Font(Standard14Fonts.FontName.HELVETICA));
            form.setDefaultResources(res);
            form.setDefaultAppearance("/Helv 0 Tf 0 g");
            int y = 700;
            for (String[] nv : new String[][]{{"customer", "Acme"}, {"invoice", "INV-7"}, {"total", "120.50"}}) {
                PDTextField f = new PDTextField(form);
                f.setPartialName(nv[0]);
                PDAnnotationWidget wd = f.getWidgets().get(0);
                wd.setRectangle(new org.apache.pdfbox.pdmodel.common.PDRectangle(50, y -= 30, 200, 20));
                wd.setPage(page);
                page.getAnnotations().add(wd);
                form.getFields().add(f);
                f.setValue(nv[1]);
            }
            d.save(w.resolve("form.pdf").toFile());
        }
        ActionContext c = PdfImagesPropertiesTest.ctx(w);
        assertEquals(3, ((Map<String, Object>) pdf.readForm(c, "form.pdf", null).get("fields")).size());
        Map<String, Object> some = (Map<String, Object>) pdf.readForm(c, "form.pdf", null, List.of("total", "customer")).get("fields");
        assertEquals(List.of("total", "customer"), new ArrayList<>(some.keySet()), "in the order asked");
        assertEquals("120.50", some.get("total"));
        assertEquals(3, ((Map<String, Object>) pdf.readForm(c, "form.pdf", null, List.of()).get("fields")).size(), "empty list reads all");
        assertEquals("PDF.NO_FIELD", assertThrows(ActionException.class, () -> pdf.readForm(c, "form.pdf", null, List.of("nope"))).code());
    }
}
