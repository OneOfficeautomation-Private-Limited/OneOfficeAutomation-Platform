package com.oneofficeautomation.packages.pdf;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.runner.CredentialVault;
import com.oneofficeautomation.runner.RunResult;
import com.oneofficeautomation.runner.Runner;
import com.oneofficeautomation.sdk.JsonLite;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class PdfPackageTest {
    static void makePdf(Path p, String... pages) throws Exception {
        try (PDDocument d = new PDDocument()) {
            for (String text : pages) {
                PDPage page = new PDPage();
                d.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(d, page)) {
                    cs.beginText(); cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14); cs.newLineAtOffset(72, 700); cs.showText(text); cs.endText();
                }
            }
            d.save(p.toFile());
        }
    }

    @SuppressWarnings("unchecked")
    @Test void textMergeSplitEncryptDecrypt() throws Exception {
        Path w = Files.createTempDirectory("ooa-pdf");
        makePdf(w.resolve("a.pdf"), "Invoice INV-1 total 129.99");
        makePdf(w.resolve("b.pdf"), "Page two", "Page three");
        Map<String, Object> flow = (Map<String, Object>) JsonLite.parse("""
            {"kind":"TaskBot","id":"p","name":"p",
             "variables":[{"name":"text","type":"String","direction":"output"},{"name":"pages","type":"Number","direction":"output"},
               {"name":"parts","type":"List","direction":"output"},{"name":"back","type":"String","direction":"output"},
               {"name":"pw","type":"Credential","direction":"input","required":true}],
             "steps":[
               {"id":"t","action":"ooa.pdf/extract_text","args":{"path":"a.pdf"},"out":{"text":"text"}},
               {"id":"m","action":"ooa.pdf/merge","args":{"files":["a.pdf","b.pdf"],"target":"out/all.pdf"},"out":{"page_count":"pages"}},
               {"id":"s","action":"ooa.pdf/split","args":{"path":"out/all.pdf","target_folder":"out/parts"},"out":{"files":"parts"}},
               {"id":"e","action":"ooa.pdf/encrypt","args":{"path":"a.pdf","target":"out/secret.pdf","user_password":"$pw","owner_password":"$pw"}},
               {"id":"d","action":"ooa.pdf/decrypt","args":{"path":"out/secret.pdf","password":"$pw","target":"out/open.pdf"}},
               {"id":"b","action":"ooa.pdf/extract_text","args":{"path":"out/open.pdf"},"out":{"text":"back"}}
             ]}""");
        CredentialVault vault = new CredentialVault.InMemory().put("test", "pdf", "password", "s3cret!");
        RunResult r = Runner.builder(FirstPartyPackages.registry()).workDir(w).service("vault", vault).build()
                .run(flow, Map.of("pw", Map.of("$credential", "test/pdf/password")));
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertTrue(((String) r.outputs().get("text")).contains("INV-1 total 129.99"));
        assertEquals(0, BigDecimal.valueOf(3).compareTo((BigDecimal) r.outputs().get("pages")));
        assertEquals(3, ((List<?>) r.outputs().get("parts")).size());
        assertTrue(((String) r.outputs().get("back")).contains("INV-1"));
        Map<String, Object> wrong = (Map<String, Object>) JsonLite.parse("""
            {"kind":"TaskBot","id":"x","name":"x","steps":[{"id":"t","action":"ooa.pdf/extract_text","args":{"path":"out/secret.pdf"}}]}""");
        RunResult locked = Runner.builder(FirstPartyPackages.registry()).workDir(w).build().run(wrong, Map.of());
        assertEquals("PDF.PASSWORD_REQUIRED", locked.error().get("code"));
    }
}
