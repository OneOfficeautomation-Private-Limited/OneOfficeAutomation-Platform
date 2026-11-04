package com.oneofficeautomation.packages.office;

import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import org.apache.poi.xwpf.usermodel.*;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Gap: Word "insert text as HTML". */
class WordHtmlTest {
    @Test void htmlBecomesWordFormatting() throws Exception {
        Path dir = Files.createTempDirectory("ooa-word-html");
        ActionContext ctx = OfficeTestSupport.ctx(dir);
        WordPackage w = new WordPackage();
        w.createDocument(ctx, "letter.docx", false, "word");
        w.addParagraph(ctx, "Dear customer,", false, null, "word");
        w.addParagraph(ctx, "[[DETAILS]]", false, null, "word");
        w.addParagraph(ctx, "Kind regards", false, null, "word");
        Path png = OfficeTestSupport.png(dir, "dot.png", 4, 4, Color.GREEN);
        String b64 = Base64.getEncoder().encodeToString(Files.readAllBytes(png));
        String html = """
                <h2 style="text-align:center">Order summary</h2>
                <p>Your order <b>#4711</b> is <span style="color:#C00000;font-size:14pt">overdue</span> &amp; <i>urgent</i><br>Pay by <u>Friday</u>.
                <ul><li>First item<li>Second item<ul><li>Nested <a href="https://acme.example/pay">pay here</a></li></ul></ul>
                <ol><li>Step one</li><li>Step two</li></ol>
                <table border="1"><tr><th>Item</th><th>Qty</th></tr><tr><td>Pens</td><td>3</td></tr><tr><td colspan="2">Total: 3</td></tr></table>
                <p><font color="blue" face="Arial">Logo:</font> <img src="data:image/png;base64,%s" width="16"> <img src="dot.png" height="8"></p>
                <pre>code  line
                  indented</pre><script>alert(1)</script><!-- hidden -->
                """.formatted(b64);
        w.insertText(ctx, html, "after_text", "[[DETAILS]]", true, "word", null, null, null, null, null, null, true);
        w.save(ctx, null, "word");
        w.close(ctx, "word");

        try (InputStream in = Files.newInputStream(dir.resolve("letter.docx")); XWPFDocument d = new XWPFDocument(in)) {
            List<IBodyElement> els = d.getBodyElements();
            List<String> texts = new ArrayList<>();
            for (IBodyElement e : els) texts.add(e instanceof XWPFParagraph p ? p.getText() : "<table>");
            int details = texts.indexOf("[[DETAILS]]"), regards = texts.indexOf("Kind regards");
            assertTrue(details >= 0 && regards > details, texts.toString());
            List<String> inserted = texts.subList(details + 1, regards);
            assertEquals("Order summary", inserted.get(0));
            assertTrue(inserted.contains("First item") && inserted.contains("Step two") && inserted.contains("<table>"), inserted.toString());
            assertFalse(String.join("|", texts).contains("alert"), "scripts are dropped");
            XWPFParagraph h = (XWPFParagraph) els.get(details + 1);
            assertEquals("Heading2", h.getStyle());
            assertEquals(ParagraphAlignment.CENTER, h.getAlignment());
            XWPFParagraph para = (XWPFParagraph) els.get(details + 2);
            assertEquals("Your order #4711 is overdue & urgent\nPay by Friday.", para.getText());
            XWPFRun bold = para.getRuns().stream().filter(r -> "#4711".equals(r.text())).findFirst().orElseThrow();
            assertTrue(bold.isBold());
            XWPFRun red = para.getRuns().stream().filter(r -> "overdue".equals(r.text())).findFirst().orElseThrow();
            assertEquals("C00000", red.getColor());
            assertEquals(14.0, red.getFontSizeAsDouble());
            XWPFParagraph first = (XWPFParagraph) els.get(details + 1 + inserted.indexOf("First item"));
            XWPFParagraph nested = (XWPFParagraph) els.get(details + 1 + inserted.indexOf("Nested pay here"));
            assertNotNull(first.getNumID());
            assertEquals(1, nested.getNumIlvl().intValue(), "nested list level");
            assertTrue(nested.getRuns().stream().anyMatch(r -> r instanceof XWPFHyperlinkRun hr && "https://acme.example/pay".equals(hr.getHyperlink(d).getURL())));
            XWPFParagraph step = (XWPFParagraph) els.get(details + 1 + inserted.indexOf("Step one"));
            assertEquals("decimal", step.getNumFmt());
            XWPFTable t = d.getTables().get(0);
            assertEquals("Item", t.getRow(0).getCell(0).getText());
            assertTrue(t.getRow(0).getCell(0).getParagraphs().get(0).getRuns().get(0).isBold(), "header cells are bold");
            assertEquals("3", t.getRow(1).getCell(1).getText());
            assertEquals(1, t.getRow(2).getTableCells().size(), "colspan merges the cells");
            long pics = d.getParagraphs().stream().flatMap(p -> p.getRuns().stream()).mapToLong(r -> r.getEmbeddedPictures().size()).sum();
            assertEquals(2, pics, "the data: URI picture and the file picture");
            XWPFParagraph pre = (XWPFParagraph) els.get(regards - 1);
            assertTrue(pre.getText().contains("code  line\n  indented"), pre.getText());
            assertEquals("Consolas", pre.getRuns().get(0).getFontFamily());
        }
    }

    @Test void htmlAtTheEndAndStartOfANewDocument() throws Exception {
        Path dir = Files.createTempDirectory("ooa-word-html2");
        ActionContext ctx = OfficeTestSupport.ctx(dir);
        WordPackage w = new WordPackage();
        w.createDocument(ctx, "new.docx", false, "word");
        w.insertText(ctx, "<table><tr><td>A</td><td>B</td></tr></table><p>after</p>", "end", null, true, "word", null, null, null, null, null, null, true);
        w.insertText(ctx, "<h1>Title</h1>", "start", null, true, "word", null, null, null, null, null, null, true);
        XWPFDocument d = ctx.session("word", WordPackage.DocSession.class).doc;
        assertEquals("Title", ((XWPFParagraph) d.getBodyElements().get(0)).getText());
        assertTrue(d.getBodyElements().get(1) instanceof XWPFTable, "the blank first paragraph of a new document is replaced");
        assertEquals("after", ((XWPFParagraph) d.getBodyElements().get(2)).getText());
        assertEquals("WORD.NOT_FOUND", assertThrows(ActionException.class,
                () -> w.insertText(ctx, "<img src='missing.png'>", "end", null, true, "word", null, null, null, null, null, null, true)).code());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        d.write(out);
        try (XWPFDocument again = new XWPFDocument(new ByteArrayInputStream(out.toByteArray()))) { assertEquals("A", again.getTables().get(0).getRow(0).getCell(0).getText()); }
    }

    @Test void parserCopesWithLooseHtml() {
        HtmlToDocx.Node root = HtmlToDocx.parse("<P>one<p>two &lt;3&gt; &#x263A; &euro;<li>x<li>y</ul>");
        assertEquals(List.of("p", "p"), root.kids.stream().map(n -> n.tag).toList());
        assertEquals(List.of("li", "li"), root.kids.get(1).kids.stream().map(n -> n.tag).filter(Objects::nonNull).toList(), "a new li closes the previous one");
        assertEquals("two <3> ☺ €", root.kids.get(1).kids.get(0).text);
        assertEquals("C00000", HtmlToDocx.colour("rgb(192, 0, 0)"));
        assertEquals(12.0, HtmlToDocx.points("16px"));
    }
}
