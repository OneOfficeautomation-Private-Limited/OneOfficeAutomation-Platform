package com.oneofficeautomation.packages.office;

import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.SecureString;
import org.apache.poi.poifs.crypt.CryptoFunctions;
import org.apache.poi.poifs.crypt.HashAlgorithm;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.xmlbeans.XmlCursor;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.*;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/** The edit (modify) password of new Word and PowerPoint files: kept as a salted hash the Office apps check. */
class OfficeEditPasswordTest {
    private static final SecureString EDIT = new SecureString("Edit-Pass-7", "vault:test");

    @Test void wordRestrictsEditingWithThePassword() throws Exception {
        Path dir = Files.createTempDirectory("ooa-edit-pw");
        ActionContext ctx = OfficeTestSupport.ctx(dir);
        new WordPackage().createDocument(ctx, "locked.docx", false, "w", null, null, EDIT);
        new WordPackage().createDocument(ctx, "open.docx", false, "o");
        try (InputStream in = Files.newInputStream(dir.resolve("locked.docx")); XWPFDocument d = new XWPFDocument(in)) {
            assertTrue(d.isEnforcedReadonlyProtection());
            assertTrue(d.validateProtectionPassword("Edit-Pass-7"));
            assertFalse(d.validateProtectionPassword("wrong"));
        }
        try (InputStream in = Files.newInputStream(dir.resolve("open.docx")); XWPFDocument d = new XWPFDocument(in)) {
            assertFalse(d.isEnforcedReadonlyProtection(), "no edit password, no restriction");
        }
    }

    @Test void powerPointAsksForThePasswordToModify() throws Exception {
        Path dir = Files.createTempDirectory("ooa-edit-pw");
        ActionContext ctx = OfficeTestSupport.ctx(dir);
        new PowerPointPackage().createPresentation(ctx, "deck.pptx", false, "p", null, null, EDIT);
        try (InputStream in = Files.newInputStream(dir.resolve("deck.pptx")); XMLSlideShow ppt = new XMLSlideShow(in);
             XmlCursor c = ppt.getCTPresentation().newCursor()) {
            assertTrue(c.toChild("http://schemas.openxmlformats.org/presentationml/2006/main", "modifyVerifier"));
            assertEquals("SHA-512", c.getAttributeText(new javax.xml.namespace.QName("algorithmName")));
            byte[] salt = Base64.getDecoder().decode(c.getAttributeText(new javax.xml.namespace.QName("saltValue")));
            int spin = Integer.parseInt(c.getAttributeText(new javax.xml.namespace.QName("spinValue")));
            String hash = c.getAttributeText(new javax.xml.namespace.QName("hashValue"));
            assertEquals(hash, Base64.getEncoder().encodeToString(CryptoFunctions.hashPassword("Edit-Pass-7", HashAlgorithm.sha512, salt, spin, false)));
            assertNotEquals(hash, Base64.getEncoder().encodeToString(CryptoFunctions.hashPassword("wrong", HashAlgorithm.sha512, salt, spin, false)));
        }
    }
}
