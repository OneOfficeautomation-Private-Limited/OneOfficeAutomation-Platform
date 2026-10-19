package com.oneofficeautomation.packages.office;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.runner.RunResult;
import com.oneofficeautomation.runner.Runner;
import com.oneofficeautomation.sdk.JsonLite;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class WordPackageTest {
    @SuppressWarnings("unchecked")
    @Test void fillTemplateAddTableAndReadBack() throws Exception {
        Path w = Files.createTempDirectory("ooa-word");
        try (XWPFDocument d = new XWPFDocument(); OutputStream out = Files.newOutputStream(w.resolve("template.docx"))) {
            d.createParagraph().createRun().setText("Dear {{customer}},");
            var p = d.createParagraph();            // placeholder split across two runs, as Word often does
            p.createRun().setText("Your refund of {{amo");
            p.createRun().setText("unt}} is approved.");
            d.write(out);
        }
        Map<String, Object> flow = (Map<String, Object>) JsonLite.parse("""
            {"kind":"TaskBot","id":"w","name":"w",
             "variables":[{"name":"n","type":"Number","direction":"output"},{"name":"text","type":"String","direction":"output"},
               {"name":"tables","type":"List","direction":"output"},
               {"name":"t","type":"Table","default":{"columns":["Item","Amount"],"rows":[["Tablet",129.99]]}}],
             "steps":[
               {"id":"o","action":"ooa.word/open","args":{"path":"template.docx"}},
               {"id":"r1","action":"ooa.word/replace_text","args":{"find":"{{customer}}","replace_with":"Jane Doe"},"out":{"count":"n"}},
               {"id":"r2","action":"ooa.word/replace_text","args":{"find":"{{amount}}","replace_with":"129.99 GBP"}},
               {"id":"a","action":"ooa.word/add_paragraph","args":{"text":"Summary","bold":true}},
               {"id":"t","action":"ooa.word/add_table","args":{"table":"$t"}},
               {"id":"s","action":"ooa.word/save","args":{"path":"out/letter.docx"}},
               {"id":"o2","action":"ooa.word/open","args":{"path":"out/letter.docx","session":"check"}},
               {"id":"rt","action":"ooa.word/read_text","args":{"session":"check"},"out":{"text":"text"}},
               {"id":"tb","action":"ooa.word/read_tables","args":{"session":"check"},"out":{"tables":"tables"}}
             ]}""");
        RunResult r = Runner.builder(FirstPartyPackages.registry()).workDir(w).build().run(flow, Map.of());
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals(0, BigDecimal.ONE.compareTo((BigDecimal) r.outputs().get("n")));
        String text = (String) r.outputs().get("text");
        assertTrue(text.contains("Dear Jane Doe,") && text.contains("refund of 129.99 GBP is approved"), text);
        Map<String, Object> table = (Map<String, Object>) ((List<?>) r.outputs().get("tables")).get(0);
        assertEquals(List.of("Item", "Amount"), table.get("columns"));
        assertEquals("Tablet", ((List<?>) ((List<?>) table.get("rows")).get(0)).get(0));
    }
}
