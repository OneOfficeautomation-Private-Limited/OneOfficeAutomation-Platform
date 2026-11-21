package com.oneofficeautomation.packages.pdf;

import com.oneofficeautomation.sdk.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.apache.pdfbox.pdmodel.interactive.form.PDXFAResource;
import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * G2 (AA parity, 21 Nov 2026), PDF: named areas read into a Dictionary (AA's Extract field), encryption with one
 * password, and XFA forms (a hybrid one filled through its normal fields; a dynamic one refused with a clear error).
 */
class PdfParityG2Test {
    final PdfPackage pdf = new PdfPackage();

    /** Two pages, each with an invoice number at the top right and a total lower down (PDF y is from the bottom: page height 792). */
    static Path invoices(Path w) throws Exception {
        try (PDDocument d = new PDDocument()) {
            for (String[] p : new String[][]{{"INV-1043", "1,250.00"}, {"INV-1044", "80.00"}}) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                d.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(d, page)) {
                    cs.beginText(); cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12); cs.newLineAtOffset(420, 792 - 100); cs.showText(p[0]); cs.endText();
                    cs.beginText(); cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12); cs.newLineAtOffset(420, 792 - 400); cs.showText(p[1]); cs.endText();
                }
            }
            d.save(w.resolve("invoices.pdf").toFile());
        }
        return w.resolve("invoices.pdf");
    }

    @SuppressWarnings("unchecked")
    @Test void namedAreasIntoADictionary() throws Exception {
        Path w = Files.createTempDirectory("ooa-pdf-g2");
        invoices(w);
        ActionContext c = PdfImagesPropertiesTest.ctx(w);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("invoice_no", Map.of("page", 1, "x", 400, "y", 85, "width", 150, "height", 25));
        fields.put("total", Map.of("x", 400, "y", 385, "width", 150, "height", 25));
        fields.put("notes", Map.of("x", 20, "y", 600, "width", 100, "height", 20));
        Map<String, Object> r = pdf.extractFields(c, "invoices.pdf", fields, true, false, null);
        assertEquals(Map.of("invoice_no", "INV-1043", "total", "1,250.00", "notes", ""), r.get("values"));
        assertEquals(List.of("notes"), r.get("empty"));
        Map<String, Object> every = (Map<String, Object>) pdf.extractFields(c, "invoices.pdf", Map.of("invoice_no", fields.get("invoice_no")), true, true, null).get("values");
        assertEquals(List.of("INV-1043", "INV-1044"), every.get("invoice_no"), "each page, for a form repeated page after page");
        assertEquals("PDF.BAD_AREA", assertThrows(ActionException.class, () -> pdf.extractFields(c, "invoices.pdf", Map.of("x", Map.of("x", 1, "y", 1, "width", 0, "height", 5)), true, false, null)).code());
        assertEquals("PDF.BAD_PAGES", assertThrows(ActionException.class, () -> pdf.extractFields(c, "invoices.pdf", Map.of("x", Map.of("page", 9, "x", 1, "y", 1, "width", 5, "height", 5)), true, false, null)).code());
    }

    @Test void encryptWithOnlyAnOpeningPassword() throws Exception {
        Path w = Files.createTempDirectory("ooa-pdf-g2e");
        invoices(w);
        ActionContext c = PdfImagesPropertiesTest.ctx(w);
        pdf.encrypt(c, "invoices.pdf", "locked.pdf", new SecureString("open-me", "v"), null, true, false, false, false, false, false, true, 256, true);
        assertThrows(Exception.class, () -> Loader.loadPDF(w.resolve("locked.pdf").toFile()).close(), "needs the password");
        try (PDDocument d = Loader.loadPDF(w.resolve("locked.pdf").toFile(), "open-me")) {
            assertFalse(d.getCurrentAccessPermission().canModify(), "the permissions hold: the owner password is one nobody knows");
        }
        assertEquals("PDF.NO_PASSWORD", assertThrows(ActionException.class,
                () -> pdf.encrypt(c, "invoices.pdf", "x.pdf", null, null, true, true, true, true, true, true, true, 256, true)).code());
    }

    static Path form(Path w, boolean withFields) throws Exception {
        try (PDDocument d = new PDDocument()) {
            PDPage page = new PDPage();
            d.addPage(page);
            PDAcroForm form = new PDAcroForm(d);
            d.getDocumentCatalog().setAcroForm(form);
            PDResources res = new PDResources();
            res.put(COSName.getPDFName("Helv"), new PDType1Font(Standard14Fonts.FontName.HELVETICA));
            form.setDefaultResources(res);
            form.setDefaultAppearance("/Helv 0 Tf 0 g");
            if (withFields) {
                PDTextField f = new PDTextField(form);
                f.setPartialName("customer");
                PDAnnotationWidget wd = f.getWidgets().get(0);
                wd.setRectangle(new PDRectangle(50, 700, 200, 20));
                wd.setPage(page);
                page.getAnnotations().add(wd);
                form.getFields().add(f);
            }
            var xfa = new org.apache.pdfbox.cos.COSStream();
            try (var out = xfa.createOutputStream()) { out.write("<xdp:xdp xmlns:xdp=\"http://ns.adobe.com/xdp/\"/>".getBytes()); }
            form.setXFA(new PDXFAResource(xfa));
            d.save(w.resolve(withFields ? "hybrid.pdf" : "dynamic.pdf").toFile());
        }
        return w;
    }

    @Test void xfaFormsFilledOrClearlyRefused() throws Exception {
        Path w = Files.createTempDirectory("ooa-pdf-g2x");
        form(w, true); form(w, false);
        ActionContext c = PdfImagesPropertiesTest.ctx(w);
        pdf.fillForm(c, "hybrid.pdf", Map.of("customer", "Acme"), "filled.pdf", false, null);
        try (PDDocument d = Loader.loadPDF(w.resolve("filled.pdf").toFile())) {
            PDAcroForm f = d.getDocumentCatalog().getAcroForm();
            assertEquals("Acme", f.getField("customer").getValueAsString());
            assertFalse(f.hasXFA(), "the XFA copy is dropped so viewers show the value");
        }
        assertEquals("PDF.XFA_DYNAMIC", assertThrows(ActionException.class, () -> pdf.fillForm(c, "dynamic.pdf", Map.of("customer", "Acme"), "x.pdf", false, null)).code());
    }
}
