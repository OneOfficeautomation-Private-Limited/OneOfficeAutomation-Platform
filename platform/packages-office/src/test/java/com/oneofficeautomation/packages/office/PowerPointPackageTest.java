package com.oneofficeautomation.packages.office;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.runner.RunResult;
import com.oneofficeautomation.runner.Runner;
import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import com.oneofficeautomation.sdk.JsonLite;
import org.apache.poi.sl.usermodel.Placeholder;
import org.apache.poi.xslf.usermodel.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class PowerPointPackageTest {
    private final PowerPointPackage ppt = new PowerPointPackage();
    private Path dir;
    private ActionContext ctx;

    @BeforeEach void setUp() throws Exception {
        dir = Files.createTempDirectory("ooa-ppt");
        ctx = OfficeTestSupport.ctx(dir);
    }

    private XMLSlideShow reopen(String file) throws Exception {
        try (InputStream in = Files.newInputStream(dir.resolve(file))) { return new XMLSlideShow(in); }
    }

    private static BigDecimal n(Object o) { return (BigDecimal) o; }

    @Test void buildDeckWithSlidesTextAndTable() throws Exception {
        ppt.open(ctx, "deck.pptx", true, "powerpoint");
        assertEquals(0, n(ppt.addSlide(ctx, "title", "Quarterly review", "Prepared by the finance agent", null, "powerpoint").get("slide")).compareTo(BigDecimal.ONE));
        ppt.addSlide(ctx, "title_and_content", "Highlights", "Revenue up {{pct}}\nCosts flat", null, "powerpoint");
        ppt.addSlide(ctx, "blank", null, null, null, "powerpoint");
        assertEquals(0, n(ppt.addSlide(ctx, "title_only", "Agenda", null, 2, "powerpoint").get("slide")).compareTo(BigDecimal.valueOf(2)));
        ppt.insertText(ctx, 4, "Free text {{pct}}", "none", 100, 100, 300, 40, 20.0, "powerpoint");
        ppt.insertText(ctx, 2, "Agenda for {{quarter}}", "title", 0, 0, 0, 0, null, "powerpoint");
        Map<String, Object> table = Map.of("columns", List.of("Region", "Sales"), "rows", List.of(List.of("North", new BigDecimal("1200.5")), List.of("South", 900)));
        ppt.insertTable(ctx, 4, table, 50, 200, 400, 12, "powerpoint");
        assertEquals("PPT.NO_PLACEHOLDER", assertThrows(ActionException.class, () -> ppt.insertText(ctx, 4, "x", "title", 0, 0, 0, 0, null, "powerpoint")).code());

        assertEquals(0, n(ppt.findReplaceText(ctx, "{{pct}}", "12%", null, "powerpoint").get("count")).compareTo(BigDecimal.valueOf(2)));
        assertEquals(0, n(ppt.findReplaceText(ctx, "{{quarter}}", "Q3", 2, "powerpoint").get("count")).compareTo(BigDecimal.ONE));
        assertEquals(0, n(ppt.slideCount(ctx, "powerpoint").get("count")).compareTo(BigDecimal.valueOf(4)));
        ppt.save(ctx, null, "powerpoint");

        try (XMLSlideShow back = reopen("deck.pptx")) {
            List<XSLFSlide> slides = back.getSlides();
            assertEquals(4, slides.size());
            assertEquals("Agenda for Q3", slides.get(1).getTitle());
            assertEquals("Highlights", slides.get(2).getTitle());
            XSLFTable t = null;
            for (XSLFShape s : slides.get(3).getShapes()) if (s instanceof XSLFTable x) t = x;
            assertNotNull(t);
            assertEquals(3, t.getNumberOfRows());
            assertEquals("Sales", t.getCell(0, 1).getText());
            assertEquals("1200.5", t.getCell(1, 1).getText());
            assertTrue(t.getCell(0, 0).getTextParagraphs().get(0).getTextRuns().get(0).isBold());
        }
        ppt.open(ctx, "deck.pptx", false, "check");
        @SuppressWarnings("unchecked") List<Object> texts = (List<Object>) ppt.readText(ctx, "check").get("slides");
        assertEquals(4, texts.size());
        assertTrue(((String) texts.get(0)).contains("Quarterly review") && ((String) texts.get(0)).contains("finance agent"));
        assertTrue(((String) texts.get(2)).contains("Revenue up 12%"), (String) texts.get(2));
        assertTrue(((String) texts.get(3)).contains("Free text 12%") && ((String) texts.get(3)).contains("North"));
    }

    @Test void copyDeleteSlidesAndSaveAs() throws Exception {
        ppt.open(ctx, "deck.pptx", true, "powerpoint");
        ppt.addSlide(ctx, "title_and_content", "One", "first", null, "powerpoint");
        ppt.addSlide(ctx, "title_and_content", "Two", "second", null, "powerpoint");
        assertEquals(0, n(ppt.copySlide(ctx, 1, null, "powerpoint").get("slide")).compareTo(BigDecimal.valueOf(2)));
        ppt.copySlide(ctx, 3, 1, "powerpoint");
        ppt.deleteSlide(ctx, 3, "powerpoint");
        assertEquals("PPT.NO_SLIDE", assertThrows(ActionException.class, () -> ppt.deleteSlide(ctx, 9, "powerpoint")).code());
        ppt.saveAs(ctx, "out/copy.pptx", null, "powerpoint");
        assertEquals("PPT.BAD_FORMAT", assertThrows(ActionException.class, () -> ppt.saveAs(ctx, "out/copy.pdf", "pdf", "powerpoint")).code());
        try (XMLSlideShow back = reopen("out/copy.pptx")) {
            List<String> titles = new ArrayList<>();
            for (XSLFSlide s : back.getSlides()) titles.add(s.getTitle());
            assertEquals(List.of("Two", "One", "Two"), titles);
            // a copied slide has its own shapes, not the layout's empty placeholders stacked on top
            long titleShapes = back.getSlides().get(0).getShapes().stream()
                    .filter(s -> s instanceof XSLFTextShape t && t.getPlaceholder() == Placeholder.TITLE).count();
            assertEquals(1, titleShapes);
        }
        ppt.close(ctx, "powerpoint");
        assertFalse(ctx.sessions().containsKey("powerpoint"));
    }

    @Test void formatTextSplitsRunsKeepingOtherText() throws Exception {
        ppt.open(ctx, "fmt.pptx", true, "powerpoint");
        ppt.addSlide(ctx, "title_and_content", "Status", "Budget is RED today and RED tomorrow", null, "powerpoint");
        assertEquals(0, n(ppt.formatText(ctx, "RED", true, true, null, 28.0, "#C00000", null, null, "powerpoint").get("count")).compareTo(BigDecimal.valueOf(2)));
        ppt.save(ctx, null, "powerpoint");
        try (XMLSlideShow back = reopen("fmt.pptx")) {
            XSLFTextShape body = null;
            for (XSLFShape s : back.getSlides().get(0).getShapes())
                if (s instanceof XSLFTextShape t && t.getText().contains("Budget")) body = t;
            assertNotNull(body);
            assertEquals("Budget is RED today and RED tomorrow", body.getText());
            int hits = 0;
            for (XSLFTextRun r : body.getTextParagraphs().get(0).getTextRuns()) {
                if (r.getRawText().equals("RED")) {
                    hits++;
                    assertTrue(r.isBold() && r.isItalic());
                    assertEquals(28.0, r.getFontSize());
                } else assertFalse(r.isBold(), r.getRawText());
            }
            assertEquals(2, hits);
        }
    }

    @Test void insertAndReplacePictures() throws Exception {
        Path red = OfficeTestSupport.png(dir, "red.png", 40, 20, Color.RED);
        Path blue = OfficeTestSupport.png(dir, "blue.png", 8, 8, Color.BLUE);
        ppt.open(ctx, "pics.pptx", true, "powerpoint");
        ppt.addSlide(ctx, "blank", null, null, null, "powerpoint");
        ppt.addSlide(ctx, "blank", null, null, null, "powerpoint");
        ppt.insertPicture(ctx, 1, "red.png", 10, 20, 200.0, "chart", "powerpoint");
        ppt.insertPicture(ctx, 2, "red.png", 0, 0, null, null, "powerpoint");
        assertEquals(0, n(ppt.findReplacePicture(ctx, "blue.png", "chart", null, "powerpoint").get("count")).compareTo(BigDecimal.ONE));
        assertEquals(0, n(ppt.findReplacePicture(ctx, "blue.png", null, 2, "powerpoint").get("count")).compareTo(BigDecimal.ONE));
        assertEquals(0, n(ppt.findReplacePicture(ctx, "blue.png", "missing", null, "powerpoint").get("count")).signum());
        ppt.save(ctx, null, "powerpoint");
        try (XMLSlideShow back = reopen("pics.pptx")) {
            XSLFPictureShape p1 = (XSLFPictureShape) back.getSlides().get(0).getShapes().get(0);
            XSLFPictureShape p2 = (XSLFPictureShape) back.getSlides().get(1).getShapes().get(0);
            assertArrayEquals(Files.readAllBytes(blue), p1.getPictureData().getData());
            assertArrayEquals(Files.readAllBytes(blue), p2.getPictureData().getData());
            assertEquals(200.0, p1.getAnchor().getWidth(), 0.5);
            assertEquals(100.0, p1.getAnchor().getHeight(), 0.5);   // 40x20 aspect ratio kept
            assertEquals(10.0, p1.getAnchor().getX(), 0.5);
        }
        assertTrue(Files.size(red) > 0);
    }

    @Test void registeredAsPackageAndRunsInAFlow() {
        @SuppressWarnings("unchecked") Map<String, Object> flow = (Map<String, Object>) JsonLite.parse("""
            {"kind":"TaskBot","id":"p","name":"p",
             "variables":[{"name":"n","type":"Number","direction":"output"},{"name":"texts","type":"List","direction":"output"}],
             "steps":[
               {"id":"o","action":"ooa.powerpoint/open","args":{"path":"flow.pptx","create":true}},
               {"id":"a","action":"ooa.powerpoint/add_slide","args":{"layout":"title","title":"Hello {{who}}"}},
               {"id":"r","action":"ooa.powerpoint/find_replace_text","args":{"find":"{{who}}","replace_with":"team"}},
               {"id":"c","action":"ooa.powerpoint/slide_count","args":{},"out":{"count":"n"}},
               {"id":"t","action":"ooa.powerpoint/read_text","args":{},"out":{"slides":"texts"}},
               {"id":"s","action":"ooa.powerpoint/save","args":{}}
             ]}""");
        RunResult r = Runner.builder(FirstPartyPackages.registry()).workDir(dir).build().run(flow, Map.of());
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals(0, n(r.outputs().get("n")).compareTo(BigDecimal.ONE));
        assertEquals(List.of("Hello team"), r.outputs().get("texts"));
        assertTrue(Files.exists(dir.resolve("flow.pptx")));
    }
}
