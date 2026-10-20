package com.oneofficeautomation.packages.office;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;
import org.apache.poi.sl.usermodel.PictureData;
import org.apache.poi.sl.usermodel.Placeholder;
import org.apache.poi.xslf.usermodel.*;
import org.openxmlformats.schemas.drawingml.x2006.main.CTRegularTextRun;
import org.openxmlformats.schemas.drawingml.x2006.main.CTTextCharacterProperties;

import java.awt.Color;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;

/** Create and edit PowerPoint .pptx presentations without Microsoft Office (Apache POI). */
@OoaPackage(id = "ooa.powerpoint", name = "PowerPoint", version = "1.0.0",
        description = "Open or create .pptx presentations, add, copy and delete slides, edit text, pictures and tables, and save.")
public final class PowerPointPackage {

    /** Presentation session shared by later steps; closed automatically at the end of the run. */
    public static final class DeckSession implements AutoCloseable {
        Path path;
        final XMLSlideShow ppt;
        DeckSession(Path p, XMLSlideShow s) { path = p; ppt = s; }
        @Override public void close() throws IOException { ppt.close(); }
    }

    private static DeckSession s(ActionContext ctx, String name) { return ctx.session(name, DeckSession.class); }

    private static XSLFSlide slide(DeckSession d, int number) {
        List<XSLFSlide> slides = d.ppt.getSlides();
        if (number < 1 || number > slides.size())
            throw new ActionException("PPT.NO_SLIDE", "There is no slide " + number + " (the presentation has " + slides.size() + ")",
                    slides.isEmpty() ? "Add a slide first." : "Use a slide number from 1 to " + slides.size() + ".");
        return slides.get(number - 1);
    }

    /** Every text shape on a slide, including those inside groups and table cells. */
    private static List<XSLFTextShape> textShapes(List<XSLFShape> shapes) {
        List<XSLFTextShape> out = new ArrayList<>();
        for (XSLFShape sh : shapes) {
            if (sh instanceof XSLFTextShape t) out.add(t);
            else if (sh instanceof XSLFGroupShape g) out.addAll(textShapes(g.getShapes()));
            else if (sh instanceof XSLFTable tb) for (XSLFTableRow r : tb.getRows()) out.addAll(r.getCells());
        }
        return out;
    }

    private static List<XSLFPictureShape> pictureShapes(List<XSLFShape> shapes) {
        List<XSLFPictureShape> out = new ArrayList<>();
        for (XSLFShape sh : shapes) {
            if (sh instanceof XSLFPictureShape p) out.add(p);
            else if (sh instanceof XSLFGroupShape g) out.addAll(pictureShapes(g.getShapes()));
        }
        return out;
    }

    private static List<XSLFSlide> targetSlides(DeckSession d, Integer slide) {
        return slide == null || slide == 0 ? d.ppt.getSlides() : List.of(slide(d, slide));
    }

    private static void write(DeckSession d, Path target) {
        try {
            if (target.getParent() != null) Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            try (OutputStream out = Files.newOutputStream(tmp)) { d.ppt.write(out); }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new ActionException("PPT.SAVE_FAILED", "Cannot save " + target + ": " + e.getMessage(), "Close the file if it is open in PowerPoint.", true);
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Presentation
    // ------------------------------------------------------------------------------------------------

    @Action(id = "open", name = "Open presentation", category = "Presentation", capabilities = "filesystem",
            description = "Open a .pptx presentation, or create a new empty one, as a named session.")
    public Map<String, Object> open(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "Presentation path.") String path,
            @Input(name = "create", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Create the presentation when it does not exist.") boolean create,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"powerpoint\"", description = "Session name used by later steps.") String session) {
        Path p = ctx.resolvePath(path);
        XMLSlideShow ppt;
        if (Files.exists(p)) {
            try (InputStream in = Files.newInputStream(p)) { ppt = new XMLSlideShow(in); }
            catch (IOException | RuntimeException e) {
                throw new ActionException("PPT.OPEN_FAILED", "Cannot open " + p + ": " + e.getMessage(), "Close it in PowerPoint and check it is a .pptx file.", true);
            }
        } else if (create) ppt = new XMLSlideShow();
        else throw new ActionException("PPT.NOT_FOUND", "Presentation not found: " + p, "Set create to true or fix the path.");
        Object old = ctx.sessions().put(session, new DeckSession(p, ppt));
        if (old instanceof AutoCloseable c) try { c.close(); } catch (Exception ignored) { }
        return Map.of();
    }

    @Action(id = "save", name = "Save", category = "Presentation", capabilities = "filesystem", description = "Save the presentation to the file it was opened from, or to another path.")
    public Map<String, Object> save(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, required = false, description = "Other path to write to; the opened file when empty.") String path,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"powerpoint\"", description = "Presentation session.") String session) {
        DeckSession d = s(ctx, session);
        write(d, path == null || path.isBlank() ? d.path : ctx.resolvePath(path));
        return Map.of();
    }

    @Action(id = "save_as", name = "Save as", category = "Presentation", capabilities = "filesystem",
            description = "Save the presentation to a new .pptx file and continue the session on that file; PDF and image export are not available.")
    public Map<String, Object> saveAs(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "Target file path.") String path,
            @Input(name = "format", type = OoaType.STRING, required = false, defaultValue = "\"pptx\"", enumValues = {"pptx"}, description = "File format; only pptx is supported.") String format,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"powerpoint\"", description = "Presentation session.") String session) {
        DeckSession d = s(ctx, session);
        Path target = ctx.resolvePath(path);
        String fmt = format == null || format.isBlank() ? "pptx" : format.toLowerCase(Locale.ROOT);
        if (!fmt.equals("pptx") || target.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf"))
            throw new ActionException("PPT.BAD_FORMAT", "Only the pptx format can be written", "Save as .pptx; use PowerPoint desktop automation for PDF.");
        write(d, target);
        d.path = target;
        return Map.of();
    }

    @Action(id = "close", name = "Close", category = "Presentation", description = "Close the presentation session without saving.")
    public Map<String, Object> close(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"powerpoint\"", description = "Presentation session.") String session) {
        Object o = ctx.sessions().remove(session);
        if (o instanceof DeckSession d) try { d.close(); } catch (IOException ignored) { }
        return Map.of();
    }

    @Action(id = "slide_count", name = "Get slide count", category = "Slides", idempotent = true, description = "Count the slides in the presentation.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of slides.")
    public Map<String, Object> slideCount(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"powerpoint\"", description = "Presentation session.") String session) {
        return Map.of("count", BigDecimal.valueOf(s(ctx, session).ppt.getSlides().size()));
    }

    // ------------------------------------------------------------------------------------------------
    // Slides
    // ------------------------------------------------------------------------------------------------

    private static SlideLayout layoutOf(String layout) {
        String l = layout == null ? "title_and_content" : layout.toLowerCase(Locale.ROOT);
        return switch (l) {
            case "title" -> SlideLayout.TITLE;
            case "title_and_content" -> SlideLayout.TITLE_AND_CONTENT;
            case "title_only" -> SlideLayout.TITLE_ONLY;
            case "section_header" -> SlideLayout.SECTION_HEADER;
            case "blank" -> SlideLayout.BLANK;
            default -> throw new ActionException("PPT.BAD_LAYOUT", "Unknown layout '" + layout + "'", "Use title, title_and_content, title_only, section_header or blank.");
        };
    }

    @Action(id = "add_slide", name = "Add slide", category = "Slides",
            description = "Add a slide with a chosen layout, optionally filling its title and body, at the end or at a given position.")
    @Output(name = "slide", type = OoaType.NUMBER, description = "1-based number of the new slide.")
    public Map<String, Object> addSlide(ActionContext ctx,
            @Input(name = "layout", type = OoaType.STRING, required = false, defaultValue = "\"title_and_content\"",
                    enumValues = {"title", "title_and_content", "title_only", "section_header", "blank"}, description = "Slide layout from the first slide master.") String layout,
            @Input(name = "title", type = OoaType.STRING, required = false, description = "Text for the title placeholder.") String title,
            @Input(name = "body", type = OoaType.STRING, required = false, description = "Text for the body or subtitle placeholder; one line per bullet.") String body,
            @Input(name = "position", type = OoaType.NUMBER, required = false, description = "1-based position for the new slide; at the end when empty.") Integer position,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"powerpoint\"", description = "Presentation session.") String session) {
        DeckSession d = s(ctx, session);
        SlideLayout kind = layoutOf(layout);
        XSLFSlideLayout lay = null;
        for (XSLFSlideMaster m : d.ppt.getSlideMasters()) { lay = m.getLayout(kind); if (lay != null) break; }
        XSLFSlide sl = lay == null ? d.ppt.createSlide() : d.ppt.createSlide(lay);
        for (XSLFShape sh : sl.getShapes())  // drop the layout's prompt text ("Click to edit ...") copied into new placeholders
            if (sh instanceof XSLFTextShape t && t.isPlaceholder()) t.clearText();
        if (title != null && !title.isEmpty()) placeholder(sl, true).setText(title);
        if (body != null && !body.isEmpty()) placeholder(sl, false).setText(body);
        int n = d.ppt.getSlides().size();
        if (position != null && position >= 1 && position < n) { d.ppt.setSlideOrder(sl, position - 1); n = position; }
        return Map.of("slide", BigDecimal.valueOf(n));
    }

    private static XSLFTextShape placeholder(XSLFSlide sl, boolean title) {
        for (XSLFShape sh : sl.getShapes()) {
            if (!(sh instanceof XSLFTextShape t)) continue;
            Placeholder p = t.getPlaceholder();
            if (p == null) continue;
            if (title && (p == Placeholder.TITLE || p == Placeholder.CENTERED_TITLE)) return t;
            if (!title && (p == Placeholder.BODY || p == Placeholder.SUBTITLE || p == Placeholder.CONTENT)) return t;
        }
        throw new ActionException("PPT.NO_PLACEHOLDER", "The slide has no " + (title ? "title" : "body") + " placeholder",
                "Use a layout with that placeholder, or insert a text box instead.");
    }

    @Action(id = "delete_slide", name = "Delete slide", category = "Slides", description = "Remove a slide from the presentation.")
    public Map<String, Object> deleteSlide(ActionContext ctx,
            @Input(name = "slide", type = OoaType.NUMBER, description = "1-based slide number.") int slide,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"powerpoint\"", description = "Presentation session.") String session) {
        DeckSession d = s(ctx, session);
        slide(d, slide);
        d.ppt.removeSlide(slide - 1);
        return Map.of();
    }

    @Action(id = "copy_slide", name = "Copy slide", category = "Slides", description = "Duplicate a slide, including its text, pictures and tables, and place the copy at a position.")
    @Output(name = "slide", type = OoaType.NUMBER, description = "1-based number of the copy.")
    public Map<String, Object> copySlide(ActionContext ctx,
            @Input(name = "slide", type = OoaType.NUMBER, description = "1-based number of the slide to copy.") int slide,
            @Input(name = "position", type = OoaType.NUMBER, required = false, description = "1-based position for the copy; right after the original when empty.") Integer position,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"powerpoint\"", description = "Presentation session.") String session) {
        DeckSession d = s(ctx, session);
        XSLFSlide src = slide(d, slide);
        XSLFSlide copy = d.ppt.createSlide(src.getSlideLayout());
        for (int i = copy.getShapes().size() - 1; i >= 0; i--) copy.removeShape(copy.getShapes().get(i));  // drop the layout's empty placeholders
        copy.importContent(src);
        int n = d.ppt.getSlides().size();
        int target = position == null ? slide + 1 : Math.max(1, Math.min(position, n));
        d.ppt.setSlideOrder(copy, target - 1);
        return Map.of("slide", BigDecimal.valueOf(target));
    }

    // ------------------------------------------------------------------------------------------------
    // Text
    // ------------------------------------------------------------------------------------------------

    @Action(id = "insert_text", name = "Insert text", category = "Text",
            description = "Put text into a slide's title or body placeholder, or add it as a new text box at a position given in points.")
    public Map<String, Object> insertText(ActionContext ctx,
            @Input(name = "slide", type = OoaType.NUMBER, description = "1-based slide number.") int slide,
            @Input(name = "text", type = OoaType.STRING, description = "Text to insert; each line becomes a paragraph.") String text,
            @Input(name = "placeholder", type = OoaType.STRING, required = false, defaultValue = "\"none\"", enumValues = {"none", "title", "body"},
                    description = "title or body replaces that placeholder's text; none adds a new text box.") String placeholder,
            @Input(name = "x", type = OoaType.NUMBER, required = false, defaultValue = "50", description = "Text box left edge in points.") double x,
            @Input(name = "y", type = OoaType.NUMBER, required = false, defaultValue = "50", description = "Text box top edge in points.") double y,
            @Input(name = "width", type = OoaType.NUMBER, required = false, defaultValue = "400", description = "Text box width in points.") double width,
            @Input(name = "height", type = OoaType.NUMBER, required = false, defaultValue = "50", description = "Text box height in points.") double height,
            @Input(name = "font_size", type = OoaType.NUMBER, required = false, description = "Font size in points for the new text.") Double fontSize,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"powerpoint\"", description = "Presentation session.") String session) {
        XSLFSlide sl = slide(s(ctx, session), slide);
        String ph = placeholder == null ? "none" : placeholder.toLowerCase(Locale.ROOT);
        XSLFTextShape shape;
        switch (ph) {
            case "title" -> shape = placeholder(sl, true);
            case "body" -> shape = placeholder(sl, false);
            case "none" -> {
                XSLFTextBox box = sl.createTextBox();
                box.setAnchor(new Rectangle2D.Double(x, y, width, height));
                shape = box;
            }
            default -> throw new ActionException("PPT.BAD_PLACEHOLDER", "Unknown placeholder '" + placeholder + "'", "Use none, title or body.");
        }
        shape.clearText();
        String[] lines = text.split("\n", -1);
        for (String line : lines) {
            XSLFTextRun r = shape.addNewTextParagraph().addNewTextRun();
            r.setText(line);
            if (fontSize != null) r.setFontSize(fontSize);
        }
        return Map.of();
    }

    @Action(id = "read_text", name = "Read text", category = "Text", idempotent = true, description = "Return the text of every slide, one entry per slide in slide order.")
    @Output(name = "slides", type = OoaType.LIST, description = "Text of each slide; shapes are separated by line breaks.")
    @Output(name = "text", type = OoaType.STRING, description = "All slide texts joined, separated by blank lines.")
    public Map<String, Object> readText(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"powerpoint\"", description = "Presentation session.") String session) {
        List<Object> slides = new ArrayList<>();
        for (XSLFSlide sl : s(ctx, session).ppt.getSlides()) {
            StringJoiner j = new StringJoiner("\n");
            for (XSLFTextShape t : textShapes(sl.getShapes())) { String v = t.getText(); if (v != null && !v.isEmpty()) j.add(v); }
            slides.add(j.toString());
        }
        StringJoiner all = new StringJoiner("\n\n");
        for (Object o : slides) all.add((String) o);
        return Map.of("slides", slides, "text", all.toString());
    }

    @Action(id = "find_replace_text", name = "Find and replace text", category = "Text",
            description = "Replace text on every slide, including tables and grouped shapes, e.g. fill {{name}} placeholders.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of replacements.")
    public Map<String, Object> findReplaceText(ActionContext ctx,
            @Input(name = "find", type = OoaType.STRING, example = "{{customer}}", description = "Text to find.") String find,
            @Input(name = "replace_with", type = OoaType.STRING, required = false, defaultValue = "\"\"", description = "Replacement text.") String with,
            @Input(name = "slide", type = OoaType.NUMBER, required = false, description = "Only this 1-based slide; all slides when empty.") Integer slide,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"powerpoint\"", description = "Presentation session.") String session) {
        if (find == null || find.isEmpty()) throw new ActionException("PPT.EMPTY_FIND", "Text to find is empty", "Give the text to replace.");
        String rep = with == null ? "" : with;
        int count = 0;
        for (XSLFSlide sl : targetSlides(s(ctx, session), slide))
            for (XSLFTextShape t : textShapes(sl.getShapes()))
                for (XSLFTextParagraph p : t.getTextParagraphs()) {
                    String text = p.getText();
                    if (text == null || !text.contains(find)) continue;
                    count += (text.length() - text.replace(find, "").length()) / find.length();
                    for (XSLFTextRun r : p.getTextRuns()) {  // keep formatting when the text sits inside a single run
                        String rt = r.getRawText();
                        if (rt != null && rt.contains(find) && r.getXmlObject() instanceof CTRegularTextRun) r.setText(rt.replace(find, rep));
                    }
                    if (p.getText().contains(find)) {  // split across runs: merge the paragraph's text runs into the first one
                        List<XSLFTextRun> runs = new ArrayList<>();
                        for (XSLFTextRun r : p.getTextRuns()) if (r.getXmlObject() instanceof CTRegularTextRun) runs.add(r);
                        StringBuilder sb = new StringBuilder();
                        for (XSLFTextRun r : runs) sb.append(r.getRawText());
                        runs.get(0).setText(sb.toString().replace(find, rep));
                        for (int i = runs.size() - 1; i > 0; i--) p.removeTextRun(runs.get(i));
                    }
                }
        return Map.of("count", BigDecimal.valueOf(count));
    }

    @Action(id = "format_text", name = "Format text", category = "Text",
            description = "Apply bold, italic, underline, size, colour or font to every occurrence of a text on the slides; inputs left empty are not changed.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of occurrences formatted.")
    public Map<String, Object> formatText(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Text to format wherever it appears.") String text,
            @Input(name = "bold", type = OoaType.BOOLEAN, required = false, description = "Bold on or off.") Boolean bold,
            @Input(name = "italic", type = OoaType.BOOLEAN, required = false, description = "Italic on or off.") Boolean italic,
            @Input(name = "underline", type = OoaType.BOOLEAN, required = false, description = "Underline on or off.") Boolean underline,
            @Input(name = "size", type = OoaType.NUMBER, required = false, description = "Font size in points.") Double size,
            @Input(name = "color", type = OoaType.STRING, required = false, example = "#C00000", description = "Hex text colour.") String colour,
            @Input(name = "font", type = OoaType.STRING, required = false, description = "Font name, e.g. Calibri.") String font,
            @Input(name = "slide", type = OoaType.NUMBER, required = false, description = "Only this 1-based slide; all slides when empty.") Integer slide,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"powerpoint\"", description = "Presentation session.") String session) {
        if (text == null || text.isEmpty()) throw new ActionException("PPT.EMPTY_FIND", "Text to format is empty", "Give the text to format.");
        Color c = null;
        if (colour != null && !colour.isBlank()) {
            String h = colour.trim().replace("#", "");
            if (!h.matches("[0-9A-Fa-f]{6}")) throw new ActionException("PPT.BAD_COLOR", "'" + colour + "' is not a colour", "Use a hex colour such as #C00000.");
            c = new Color(Integer.parseInt(h, 16));
        }
        int n = 0;
        for (XSLFSlide sl : targetSlides(s(ctx, session), slide))
            for (XSLFTextShape t : textShapes(sl.getShapes()))
                for (XSLFTextParagraph p : t.getTextParagraphs()) {
                    List<int[]> hits = new ArrayList<>();
                    String all = rawText(p);
                    for (int at = all.indexOf(text); at >= 0; at = all.indexOf(text, at + text.length())) hits.add(new int[]{at, at + text.length()});
                    if (hits.isEmpty()) continue;
                    n += hits.size();
                    for (XSLFTextRun r : isolate(p, hits)) {
                        if (bold != null) r.setBold(bold);
                        if (italic != null) r.setItalic(italic);
                        if (underline != null) r.setUnderlined(underline);
                        if (size != null) r.setFontSize(size);
                        if (c != null) r.setFontColor(c);
                        if (font != null && !font.isBlank()) r.setFontFamily(font);
                    }
                }
        return Map.of("count", BigDecimal.valueOf(n));
    }

    private static String rawText(XSLFTextParagraph p) {
        StringBuilder sb = new StringBuilder();
        for (XSLFTextRun r : p.getTextRuns()) sb.append(r.getRawText() == null ? "" : r.getRawText());
        return sb.toString();
    }

    /**
     * Rebuilds the paragraph's runs so every hit range is covered by its own runs, keeping each piece's character
     * properties. Paragraphs with line breaks or fields are not split; the runs overlapping a hit are returned whole.
     */
    private static List<XSLFTextRun> isolate(XSLFTextParagraph p, List<int[]> hits) {
        List<XSLFTextRun> runs = new ArrayList<>(p.getTextRuns());
        boolean simple = runs.stream().allMatch(r -> r.getXmlObject() instanceof CTRegularTextRun);
        List<XSLFTextRun> out = new ArrayList<>();
        if (!simple) {
            int pos = 0;
            for (XSLFTextRun r : runs) {
                int len = r.getRawText() == null ? 0 : r.getRawText().length();
                for (int[] h : hits) if (pos < h[1] && pos + len > h[0]) { out.add(r); break; }
                pos += len;
            }
            return out;
        }
        // cut points from run boundaries and hit edges
        record Piece(String text, CTTextCharacterProperties props, boolean hit) { }
        List<Piece> pieces = new ArrayList<>();
        int pos = 0;
        for (XSLFTextRun r : runs) {
            String t = r.getRawText() == null ? "" : r.getRawText();
            CTRegularTextRun x = (CTRegularTextRun) r.getXmlObject();
            TreeSet<Integer> cuts = new TreeSet<>(List.of(0, t.length()));
            for (int[] h : hits) { if (h[0] > pos && h[0] < pos + t.length()) cuts.add(h[0] - pos); if (h[1] > pos && h[1] < pos + t.length()) cuts.add(h[1] - pos); }
            Integer prev = null;
            for (int cut : cuts) {
                if (prev != null && cut > prev) {
                    int a = pos + prev;
                    boolean hit = hits.stream().anyMatch(h -> a >= h[0] && a < h[1]);
                    pieces.add(new Piece(t.substring(prev, cut), x.isSetRPr() ? (CTTextCharacterProperties) x.getRPr().copy() : null, hit));
                }
                prev = cut;
            }
            pos += t.length();
        }
        for (XSLFTextRun r : runs) p.removeTextRun(r);
        for (Piece pc : pieces) {
            XSLFTextRun r = p.addNewTextRun();
            CTRegularTextRun x = (CTRegularTextRun) r.getXmlObject();
            if (pc.props() != null) x.setRPr(pc.props()); else if (x.isSetRPr()) x.unsetRPr();
            r.setText(pc.text());
            if (pc.hit()) out.add(r);
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------
    // Pictures and tables
    // ------------------------------------------------------------------------------------------------

    private static PictureData.PictureType pictureType(Path image) {
        String n = image.getFileName().toString().toLowerCase(Locale.ROOT);
        if (n.endsWith(".png")) return PictureData.PictureType.PNG;
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return PictureData.PictureType.JPEG;
        if (n.endsWith(".gif")) return PictureData.PictureType.GIF;
        if (n.endsWith(".bmp")) return PictureData.PictureType.BMP;
        if (n.endsWith(".tif") || n.endsWith(".tiff")) return PictureData.PictureType.TIFF;
        if (n.endsWith(".emf")) return PictureData.PictureType.EMF;
        if (n.endsWith(".wmf")) return PictureData.PictureType.WMF;
        throw new ActionException("PPT.BAD_IMAGE", "Unsupported image type: " + image.getFileName(), "Use a PNG, JPEG, GIF, BMP, TIFF, EMF or WMF file.");
    }

    private static org.openxmlformats.schemas.presentationml.x2006.main.CTPicture ctPicture(XSLFPictureShape p) {
        return (org.openxmlformats.schemas.presentationml.x2006.main.CTPicture) p.getXmlObject();
    }

    private static String altText(XSLFPictureShape p) {
        String d = ctPicture(p).getNvPicPr().getCNvPr().getDescr();
        return d == null ? "" : d;
    }

    @Action(id = "insert_picture", name = "Insert picture", category = "Pictures", capabilities = "filesystem",
            description = "Place an image file on a slide at a position in points, optionally scaled to a width.")
    public Map<String, Object> insertPicture(ActionContext ctx,
            @Input(name = "slide", type = OoaType.NUMBER, description = "1-based slide number.") int slide,
            @Input(name = "image", type = OoaType.FILE, description = "Image file (PNG, JPEG, GIF, BMP, TIFF, EMF or WMF).") String image,
            @Input(name = "x", type = OoaType.NUMBER, required = false, defaultValue = "50", description = "Left edge in points.") double x,
            @Input(name = "y", type = OoaType.NUMBER, required = false, defaultValue = "50", description = "Top edge in points.") double y,
            @Input(name = "width", type = OoaType.NUMBER, required = false, description = "Width in points; the height keeps the aspect ratio. Original size when empty.") Double width,
            @Input(name = "alt_text", type = OoaType.STRING, required = false, description = "Alternative text, also used later by find_replace_picture.") String altText,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"powerpoint\"", description = "Presentation session.") String session) {
        DeckSession d = s(ctx, session);
        XSLFSlide sl = slide(d, slide);
        Path img = ctx.resolvePath(image);
        PictureData.PictureType type = pictureType(img);
        byte[] bytes = WordPackage.readImage(img, "PPT");
        double[] size = WordPackage.sizePoints(bytes, width);
        XSLFPictureShape pic = sl.createPicture(d.ppt.addPicture(bytes, type));
        pic.setAnchor(new Rectangle2D.Double(x, y, size[0], size[1]));
        if (altText != null && !altText.isEmpty()) ctPicture(pic).getNvPicPr().getCNvPr().setDescr(altText);
        return Map.of();
    }

    @Action(id = "find_replace_picture", name = "Find and replace picture", category = "Pictures", capabilities = "filesystem",
            description = "Swap pictures, found by alternative text or by their order across the slides, for a new image file, keeping position and size.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of pictures replaced.")
    public Map<String, Object> findReplacePicture(ActionContext ctx,
            @Input(name = "image", type = OoaType.FILE, description = "New image file.") String image,
            @Input(name = "alt_text", type = OoaType.STRING, required = false, description = "Replace every picture whose alternative text equals this.") String altText,
            @Input(name = "index", type = OoaType.NUMBER, required = false, description = "1-based position of the picture counting across all slides; used when alt_text is empty.") Integer index,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"powerpoint\"", description = "Presentation session.") String session) {
        DeckSession d = s(ctx, session);
        Path img = ctx.resolvePath(image);
        PictureData.PictureType type = pictureType(img);
        byte[] bytes = WordPackage.readImage(img, "PPT");
        List<Object[]> all = new ArrayList<>();
        for (XSLFSlide sl : d.ppt.getSlides()) for (XSLFPictureShape p : pictureShapes(sl.getShapes())) all.add(new Object[]{sl, p});
        List<Object[]> targets = new ArrayList<>();
        if (altText != null && !altText.isEmpty()) { for (Object[] o : all) if (altText((XSLFPictureShape) o[1]).equals(altText)) targets.add(o); }
        else if (index != null) {
            if (index < 1 || index > all.size()) throw new ActionException("PPT.NO_PICTURE", "There is no picture number " + index + " (the presentation has " + all.size() + ")", "Use a number from 1 to " + Math.max(1, all.size()) + ".");
            targets.add(all.get(index - 1));
        } else throw new ActionException("PPT.NO_PICTURE", "Give alt_text or index to choose the picture", "Set alt_text or index.");
        if (targets.isEmpty()) return Map.of("count", BigDecimal.ZERO);
        XSLFPictureData pd = d.ppt.addPicture(bytes, type);
        for (Object[] o : targets) {
            XSLFSlide sl = (XSLFSlide) o[0];
            String rel = sl.getRelationId(pd);
            if (rel == null) rel = sl.addRelation(null, XSLFRelation.IMAGES, pd).getRelationship().getId();
            ctPicture((XSLFPictureShape) o[1]).getBlipFill().getBlip().setEmbed(rel);
        }
        return Map.of("count", BigDecimal.valueOf(targets.size()));
    }

    @Action(id = "insert_table", name = "Insert table", category = "Tables",
            description = "Add a table to a slide from a Data Table, with the column names as a bold header row.")
    public Map<String, Object> insertTable(ActionContext ctx,
            @Input(name = "slide", type = OoaType.NUMBER, description = "1-based slide number.") int slide,
            @Input(name = "table", type = OoaType.TABLE, description = "Data to insert.") Map<String, Object> table,
            @Input(name = "x", type = OoaType.NUMBER, required = false, defaultValue = "50", description = "Left edge in points.") double x,
            @Input(name = "y", type = OoaType.NUMBER, required = false, defaultValue = "120", description = "Top edge in points.") double y,
            @Input(name = "width", type = OoaType.NUMBER, required = false, defaultValue = "600", description = "Total table width in points.") double width,
            @Input(name = "font_size", type = OoaType.NUMBER, required = false, defaultValue = "14", description = "Font size in points.") double fontSize,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"powerpoint\"", description = "Presentation session.") String session) {
        XSLFSlide sl = slide(s(ctx, session), slide);
        List<?> cols = (List<?>) table.get("columns");
        List<?> rows = (List<?>) table.get("rows");
        if (cols == null || cols.isEmpty()) throw new ActionException("PPT.EMPTY_TABLE", "The table has no columns", "Pass a Data Table with at least one column.");
        XSLFTable t = sl.createTable();
        double rowHeight = fontSize * 2;
        List<List<?>> lines = new ArrayList<>();
        lines.add(cols);
        if (rows != null) for (Object r : rows) lines.add((List<?>) r);
        for (int i = 0; i < lines.size(); i++) {
            XSLFTableRow tr = t.addRow();
            tr.setHeight(rowHeight);
            List<?> vals = lines.get(i);
            for (int c = 0; c < cols.size(); c++) {
                XSLFTableCell cell = tr.addCell();
                Object v = c < vals.size() ? vals.get(c) : null;
                XSLFTextRun run = cell.getTextParagraphs().isEmpty() ? cell.addNewTextParagraph().addNewTextRun()
                        : cell.getTextParagraphs().get(0).addNewTextRun();
                run.setText(v == null ? "" : Values.text(Values.toJsonable(v)));
                run.setFontSize(fontSize);
                if (i == 0) run.setBold(true);
                cell.setBorderColor(org.apache.poi.sl.usermodel.TableCell.BorderEdge.top, Color.GRAY);
                cell.setBorderColor(org.apache.poi.sl.usermodel.TableCell.BorderEdge.bottom, Color.GRAY);
                cell.setBorderColor(org.apache.poi.sl.usermodel.TableCell.BorderEdge.left, Color.GRAY);
                cell.setBorderColor(org.apache.poi.sl.usermodel.TableCell.BorderEdge.right, Color.GRAY);
            }
        }
        for (int c = 0; c < cols.size(); c++) t.setColumnWidth(c, width / cols.size());
        t.setAnchor(new Rectangle2D.Double(x, y, width, rowHeight * lines.size()));
        return Map.of();
    }

}
