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
        /** Opened with read_only: saving over the opened file is refused. */
        boolean readOnly;
        /** Password the file was opened or created with; saves keep the file encrypted. */
        String password;
        DeckSession(Path p, XMLSlideShow s) { path = p; ppt = s; }
        @Override public void close() throws IOException { ppt.close(); }
    }

    private static DeckSession s(ActionContext ctx, String name) { return ctx.sessionOrOnly(name, "powerpoint", DeckSession.class); }

    private static int lastSlide(DeckSession d) { return d.ppt.getSlides().size(); }

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

    private static void write(DeckSession d, Path target) { write(d, target, d.password); }

    private static void write(DeckSession d, Path target, String password) {
        if (d.readOnly && target.toAbsolutePath().normalize().equals(d.path.toAbsolutePath().normalize()))
            throw new ActionException("PPT.READ_ONLY", "The presentation was opened read-only", "Use save_as with another path, or open it without read_only.");
        try { OfficeCrypto.write(target, OfficeCrypto.bytes(d.ppt::write), password); }
        catch (IOException e) {
            throw new ActionException("PPT.SAVE_FAILED", "Cannot save " + target + ": " + e.getMessage(), "Close the file if it is open in PowerPoint.", true);
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Presentation
    // ------------------------------------------------------------------------------------------------

    public Map<String, Object> open(ActionContext ctx, String path, boolean create, String session) { return open(ctx, path, create, session, false, null); }

    @Action(id = "open", name = "Open presentation", category = "Presentation", capabilities = "filesystem",
            description = "Open a .pptx presentation, or create a new empty one, as a named session; can open password-protected files and open read-only.",
            panel = "path, read_only, [The file has a password: password] | create, session")
    public Map<String, Object> open(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "Presentation path.") String path,
            @Input(name = "create", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Create the presentation when it does not exist.") boolean create,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"powerpoint\"", description = "Session name used by later steps.") String session,
            @Input(name = "read_only", type = OoaType.BOOLEAN, required = false, defaultValue = "false",
                    description = "Open for reading only: save over the opened file is refused, save_as to another file still works.") boolean readOnly,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false,
                    description = "Password of an encrypted presentation; saves keep the file encrypted with it.") Object password) {
        Path p = ctx.resolvePath(path);
        String pw = OfficeCrypto.secret(password);
        XMLSlideShow ppt;
        if (Files.exists(p)) {
            try (InputStream in = OfficeCrypto.open(p, pw, "PPT")) { ppt = new XMLSlideShow(in); }
            catch (ActionException e) { throw e; }
            catch (IOException | RuntimeException e) {
                throw new ActionException("PPT.OPEN_FAILED", "Cannot open " + p + ": " + e.getMessage(), "Close it in PowerPoint and check it is a .pptx file.", true);
            }
        } else if (create) ppt = new XMLSlideShow();
        else throw new ActionException("PPT.NOT_FOUND", "Presentation not found: " + p, "Set create to true or fix the path.");
        DeckSession d = new DeckSession(p, ppt);
        d.readOnly = readOnly;
        d.password = pw;
        Object old = ctx.sessions().put(session, d);
        if (old instanceof AutoCloseable c) try { c.close(); } catch (Exception ignored) { }
        return Map.of();
    }

    @Action(id = "create_presentation", name = "Create presentation", category = "Presentation", capabilities = "filesystem",
            description = "Create a new .pptx file on disk, empty or as a copy of a template presentation, optionally password-protected, and open it as a named session.",
            panel = "path, [Require a password to open the file: password] | overwrite, template, session")
    public Map<String, Object> createPresentation(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "Path of the new presentation.") String path,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Replace a file that already exists at the path.") boolean overwrite,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"powerpoint\"", description = "Session name used by later steps.") String session,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, description = "Encrypt the new file with this password; later saves keep it.") Object password,
            @Input(name = "template", type = OoaType.FILE, required = false, description = "Existing .pptx whose slide masters, layouts and slides the new file starts from; empty when empty.") String template) {
        Path p = ctx.resolvePath(path);
        if (Files.exists(p) && !overwrite) throw new ActionException("PPT.EXISTS", "A file already exists at " + p, "Set overwrite to true or use open to edit it.");
        XMLSlideShow ppt;
        if (template != null && !template.isBlank()) {
            Path t = ctx.resolvePath(template);
            if (!Files.isRegularFile(t)) throw new ActionException("PPT.NOT_FOUND", "Template not found: " + t, "Fix the template path.");
            try (InputStream in = Files.newInputStream(t)) { ppt = new XMLSlideShow(in); }
            catch (IOException | RuntimeException e) { throw new ActionException("PPT.OPEN_FAILED", "Cannot open template " + t + ": " + e.getMessage(), "Use a .pptx template.", true); }
        } else ppt = new XMLSlideShow();
        DeckSession d = new DeckSession(p, ppt);
        d.password = OfficeCrypto.secret(password);
        write(d, p);
        Object old = ctx.sessions().put(session, d);
        if (old instanceof AutoCloseable c) try { c.close(); } catch (Exception ignored) { }
        return Map.of();
    }

    public Map<String, Object> save(ActionContext ctx, String path, String session) { return save(ctx, path, session, null); }

    @Action(id = "save", name = "Save", category = "Presentation", capabilities = "filesystem",
            description = "Save the presentation to the file it was opened from, or to another path; password-protected files stay protected.",
            panel = "session | path, password")
    public Map<String, Object> save(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, required = false, description = "Other path to write to; the opened file when empty.") String path,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"powerpoint\"", description = "Presentation session.") String session,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false,
                    description = "Encrypt the saved file with this password; the password the presentation was opened with when empty.") Object password) {
        DeckSession d = s(ctx, session);
        String pw = OfficeCrypto.secret(password);
        write(d, path == null || path.isBlank() ? d.path : ctx.resolvePath(path), pw != null ? pw : d.password);
        return Map.of();
    }

    public Map<String, Object> saveAs(ActionContext ctx, String path, String format, String session) { return saveAs(ctx, path, format, session, true, null); }

    @Action(id = "save_as", name = "Save as", category = "Presentation", capabilities = "filesystem",
            description = "Save the presentation to a new .pptx file, optionally password-protected, and continue the session on that file; PDF and image export are not available.",
            panel = "path, overwrite | format, password, session")
    public Map<String, Object> saveAs(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "Target file path.") String path,
            @Input(name = "format", type = OoaType.STRING, required = false, defaultValue = "\"pptx\"", enumValues = {"pptx"}, description = "File format; only pptx is supported.") String format,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"powerpoint\"", description = "Presentation session.") String session,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Replace a file that already exists at the target path; false stops with PPT.EXISTS.") boolean overwrite,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, description = "Encrypt the new file with this password; later saves keep it.") Object password) {
        DeckSession d = s(ctx, session);
        Path target = ctx.resolvePath(path);
        String fmt = format == null || format.isBlank() ? "pptx" : format.toLowerCase(Locale.ROOT);
        if (!fmt.equals("pptx") || target.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf"))
            throw new ActionException("PPT.BAD_FORMAT", "Only the pptx format can be written", "Save as .pptx; use PowerPoint desktop automation for PDF.");
        if (!overwrite && Files.exists(target)) throw new ActionException("PPT.EXISTS", "A file already exists at " + target, "Set overwrite to true or choose another path.");
        String pw = OfficeCrypto.secret(password);
        String keep = pw != null ? pw : d.password;
        write(d, target, keep);
        d.path = target;
        d.password = keep;
        d.readOnly = false;
        return Map.of();
    }

    public Map<String, Object> close(ActionContext ctx, String session) { return close(ctx, session, false); }

    @Action(id = "close", name = "Close", category = "Presentation", capabilities = "filesystem", description = "Close the presentation session, saving the changes first when asked.",
            panel = "save | session")
    public Map<String, Object> close(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"powerpoint\"", description = "Presentation session.") String session,
            @Input(name = "save", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Save the changes to the opened file before closing.") boolean save) {
        session = ctx.sessionNameOrOnly(session, "powerpoint", DeckSession.class);
        if (save && ctx.sessions().get(session) instanceof DeckSession d) write(d, d.path);
        Object o = ctx.sessions().remove(session);
        if (o instanceof DeckSession d) try { d.close(); } catch (IOException ignored) { }
        return Map.of();
    }

    @Action(id = "slide_count", name = "Get slide count", category = "Slides", idempotent = true, description = "Count the slides in the presentation.",
            panel = "session")
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
            case "two_content" -> SlideLayout.TWO_OBJ;
            case "comparison" -> SlideLayout.TWO_TX_TWO_OBJ;
            case "content_with_caption" -> SlideLayout.OBJ_TX;
            case "picture_with_caption" -> SlideLayout.PIC_TX;
            default -> throw new ActionException("PPT.BAD_LAYOUT", "Unknown layout '" + layout + "'",
                    "Use title, title_and_content, title_only, section_header, blank, two_content, comparison, content_with_caption or picture_with_caption.");
        };
    }

    @Action(id = "add_slide", name = "Add slide", category = "Slides",
            description = "Add a slide with a chosen layout, optionally filling its title and body, at the end or at a given position.",
            panel = "layout, (Position: At the end = / At a slide number = position) | title, body, session")
    @Output(name = "slide", type = OoaType.NUMBER, description = "1-based number of the new slide.")
    public Map<String, Object> addSlide(ActionContext ctx,
            @Input(name = "layout", type = OoaType.STRING, required = false, defaultValue = "\"title_and_content\"",
                    enumValues = {"title", "title_and_content", "title_only", "section_header", "blank", "two_content", "comparison", "content_with_caption", "picture_with_caption"},
                    description = "Slide layout from the first slide master that has it.") String layout,
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

    public Map<String, Object> deleteSlide(ActionContext ctx, int slide, String session) { return deleteSlide(ctx, slide, session, 1); }

    @Action(id = "delete_slide", name = "Delete slide", category = "Slides", description = "Remove one slide, or several slides in a row, from the presentation.",
            panel = "slide | count, session")
    public Map<String, Object> deleteSlide(ActionContext ctx,
            @Input(name = "slide", type = OoaType.NUMBER, description = "1-based slide number.") int slide,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"powerpoint\"", description = "Presentation session.") String session,
            @Input(name = "count", type = OoaType.NUMBER, required = false, defaultValue = "1", description = "How many slides to delete, starting at slide.") int count) {
        DeckSession d = s(ctx, session);
        if (count < 1) throw new ActionException("PPT.BAD_COUNT", "Count must be at least 1", "Set count to 1 or more.");
        slide(d, slide);
        slide(d, slide + count - 1);
        for (int i = 0; i < count; i++) d.ppt.removeSlide(slide - 1);
        return Map.of();
    }

    public Map<String, Object> copySlide(ActionContext ctx, int slide, Integer position, String session) { return copySlide(ctx, slide, position, session, null); }

    @Action(id = "copy_slide", name = "Copy slide", category = "Slides",
            description = "Duplicate a slide, including its text, pictures and tables, from this or another open presentation, and place the copy at a position.",
            panel = "slide, (Put the copy: Right after the original = / At a slide number = position) | session, source_session")
    @Output(name = "slide", type = OoaType.NUMBER, description = "1-based number of the copy.")
    public Map<String, Object> copySlide(ActionContext ctx,
            @Input(name = "slide", type = OoaType.NUMBER, description = "1-based number of the slide to copy.") int slide,
            @Input(name = "position", type = OoaType.NUMBER, required = false, description = "1-based position for the copy; right after the original (or at the end, for another presentation) when empty.") Integer position,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"powerpoint\"", description = "Presentation session the copy goes into.") String session,
            @Input(name = "source_session", type = OoaType.STRING, required = false,
                    description = "Session of another open presentation to copy the slide from; this presentation when empty.") String sourceSession) {
        DeckSession d = s(ctx, session);
        boolean other = sourceSession != null && !sourceSession.isBlank() && !sourceSession.equals(session);
        XSLFSlide src = slide(other ? s(ctx, sourceSession) : d, slide);
        XSLFSlide copy;
        if (other) {
            XSLFSlideLayout lay = null;
            SlideLayout kind = src.getSlideLayout().getType();
            for (XSLFSlideMaster m : d.ppt.getSlideMasters()) { lay = m.getLayout(kind); if (lay != null) break; }
            copy = lay == null ? d.ppt.createSlide() : d.ppt.createSlide(lay);
        } else copy = d.ppt.createSlide(src.getSlideLayout());
        for (int i = copy.getShapes().size() - 1; i >= 0; i--) copy.removeShape(copy.getShapes().get(i));  // drop the layout's empty placeholders
        copy.importContent(src);
        int n = d.ppt.getSlides().size();
        int target = position == null ? (other ? n : slide + 1) : Math.max(1, Math.min(position, n));
        d.ppt.setSlideOrder(copy, target - 1);
        return Map.of("slide", BigDecimal.valueOf(target));
    }

    // ------------------------------------------------------------------------------------------------
    // Text
    // ------------------------------------------------------------------------------------------------

    @Action(id = "insert_text", name = "Insert text", category = "Text",
            description = "Put text into a slide's title or body placeholder, or add it as a new text box at a position given in points.",
            panel = "(Slide: The last slide = / A slide number = slide), text, x, y, [Format the text: font, font_size, color, bold, italic, underline] | placeholder, width, height, alignment, session")
    public Map<String, Object> insertText(ActionContext ctx,
            @Input(name = "slide", type = OoaType.NUMBER, required = false, min = 1, description = "1-based slide number; the last slide (usually the one just added) when empty.") Integer slide,
            @Input(name = "text", type = OoaType.STRING, description = "Text to insert; each line becomes a paragraph.") String text,
            @Input(name = "placeholder", type = OoaType.STRING, required = false, defaultValue = "\"none\"", enumValues = {"none", "title", "body"},
                    description = "title or body replaces that placeholder's text; none adds a new text box.") String placeholder,
            @Input(name = "x", type = OoaType.NUMBER, required = false, defaultValue = "50", description = "Text box left edge in points.") double x,
            @Input(name = "y", type = OoaType.NUMBER, required = false, defaultValue = "50", description = "Text box top edge in points.") double y,
            @Input(name = "width", type = OoaType.NUMBER, required = false, defaultValue = "400", description = "Text box width in points.") double width,
            @Input(name = "height", type = OoaType.NUMBER, required = false, defaultValue = "50", description = "Text box height in points.") double height,
            @Input(name = "font_size", type = OoaType.NUMBER, required = false, description = "Font size in points for the new text.") Double fontSize,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"powerpoint\"", description = "Presentation session.") String session,
            @Input(name = "bold", type = OoaType.BOOLEAN, required = false, description = "Bold on or off for the new text.") Boolean bold,
            @Input(name = "italic", type = OoaType.BOOLEAN, required = false, description = "Italic on or off for the new text.") Boolean italic,
            @Input(name = "underline", type = OoaType.BOOLEAN, required = false, description = "Underline on or off for the new text.") Boolean underline,
            @Input(name = "color", type = OoaType.STRING, required = false, example = "#C00000", description = "Hex colour for the new text.") String colour,
            @Input(name = "font", type = OoaType.STRING, required = false, description = "Font name for the new text, e.g. Calibri.") String font,
            @Input(name = "alignment", type = OoaType.STRING, required = false, enumValues = {"left", "center", "right", "justify"}, description = "Paragraph alignment of the new text; unchanged when empty.") String alignment) {
        XSLFSlide sl = slide(s(ctx, session), slide == null ? lastSlide(s(ctx, session)) : slide);
        Color c = colour(colour);
        org.apache.poi.sl.usermodel.TextParagraph.TextAlign align = null;
        if (alignment != null && !alignment.isBlank()) align = switch (alignment.toLowerCase(Locale.ROOT)) {
            case "left" -> org.apache.poi.sl.usermodel.TextParagraph.TextAlign.LEFT;
            case "center" -> org.apache.poi.sl.usermodel.TextParagraph.TextAlign.CENTER;
            case "right" -> org.apache.poi.sl.usermodel.TextParagraph.TextAlign.RIGHT;
            case "justify" -> org.apache.poi.sl.usermodel.TextParagraph.TextAlign.JUSTIFY;
            default -> throw new ActionException("PPT.BAD_ALIGNMENT", "Alignment must be left, center, right or justify", "Use left, center, right or justify.");
        };
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
            XSLFTextParagraph para = shape.addNewTextParagraph();
            if (align != null) para.setTextAlign(align);
            XSLFTextRun r = para.addNewTextRun();
            r.setText(line);
            if (fontSize != null) r.setFontSize(fontSize);
            if (bold != null) r.setBold(bold);
            if (italic != null) r.setItalic(italic);
            if (underline != null) r.setUnderlined(underline);
            if (c != null) r.setFontColor(c);
            if (font != null && !font.isBlank()) r.setFontFamily(font);
        }
        return Map.of();
    }

    public Map<String, Object> insertText(ActionContext ctx, int slide, String text, String placeholder, double x, double y, double width, double height, Double fontSize, String session) {
        return insertText(ctx, slide, text, placeholder, x, y, width, height, fontSize, session, null, null, null, null, null, null);
    }

    private static Color colour(String colour) {
        if (colour == null || colour.isBlank()) return null;
        String h = colour.trim().replace("#", "");
        if (!h.matches("[0-9A-Fa-f]{6}")) throw new ActionException("PPT.BAD_COLOR", "'" + colour + "' is not a colour", "Use a hex colour such as #C00000.");
        return new Color(Integer.parseInt(h, 16));
    }

    /** One occurrence of a search text inside a paragraph. */
    private record Hit(XSLFTextParagraph p, int start, int end) { }

    private static List<Hit> hits(List<XSLFSlide> slides, java.util.regex.Pattern pat, String occurrence) {
        List<Hit> out = new ArrayList<>();
        for (XSLFSlide sl : slides) for (XSLFTextShape t : textShapes(sl.getShapes())) for (XSLFTextParagraph p : t.getTextParagraphs()) {
            var m = pat.matcher(rawText(p));
            while (m.find()) if (m.end() > m.start()) out.add(new Hit(p, m.start(), m.end()));
        }
        return WordPackage.pickOccurrence(out, occurrence, "PPT");
    }

    private static java.util.regex.Pattern searchPattern(String find, boolean matchCase, boolean wholeWord) {
        String q = java.util.regex.Pattern.quote(find);
        if (wholeWord) q = "(?<![\\p{L}\\p{N}_])" + q + "(?![\\p{L}\\p{N}_])";
        return java.util.regex.Pattern.compile(q, matchCase ? 0 : java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);
    }

    private static final String D_MATCH_CASE = "Match upper/lower case exactly.";
    private static final String D_WHOLE_WORD = "Only match the text as a whole word, not inside a longer word.";
    private static final String D_OCCURRENCE = "Which occurrences to change, in slide order: all, the first or the last.";

    @Action(id = "extract_text", name = "Extract text from a file", category = "Text", idempotent = true, capabilities = "filesystem",
            description = "Return the whole text of a .ppt or .pptx file without opening it as a session, optionally with the speaker notes; old .ppt files included.",
            panel = "path, include_notes")
    @Output(name = "text", type = OoaType.STRING, description = "The presentation's text, slide after slide.")
    public Map<String, Object> extractText(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "The .ppt or .pptx file.") String path,
            @Input(name = "include_notes", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Also return the speaker notes.") boolean notes) {
        return Map.of("text", DocumentText.text(ctx.resolvePath(path), notes));
    }

    @Action(id = "read_text", name = "Read text", category = "Text", idempotent = true, description = "Return the text of every slide, one entry per slide in slide order.",
            panel = "session")
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

    public Map<String, Object> findReplaceText(ActionContext ctx, String find, String with, Integer slide, String session) {
        return findReplaceText(ctx, find, with, slide, session, true, false, "all");
    }

    @Action(id = "find_replace_text", name = "Find and replace text", category = "Text",
            description = "Replace text on every slide, including tables and grouped shapes, e.g. fill {{name}} placeholders; can ignore case, match whole words or change only the first or last occurrence.",
            panel = "find, match_case, whole_word, (Look in: All slides = / One slide = slide), replace_with, occurrence | session")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of replacements.")
    public Map<String, Object> findReplaceText(ActionContext ctx,
            @Input(name = "find", type = OoaType.STRING, example = "{{customer}}", description = "Text to find.") String find,
            @Input(name = "replace_with", type = OoaType.STRING, required = false, defaultValue = "\"\"", description = "Replacement text.") String with,
            @Input(name = "slide", type = OoaType.NUMBER, required = false, description = "Only this 1-based slide; all slides when empty.") Integer slide,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"powerpoint\"", description = "Presentation session.") String session,
            @Input(name = "match_case", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = D_MATCH_CASE) boolean matchCase,
            @Input(name = "whole_word", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = D_WHOLE_WORD) boolean wholeWord,
            @Input(name = "occurrence", type = OoaType.STRING, required = false, defaultValue = "\"all\"", enumValues = {"all", "first", "last"}, description = D_OCCURRENCE) String occurrence) {
        if (find == null || find.isEmpty()) throw new ActionException("PPT.EMPTY_FIND", "Text to find is empty", "Give the text to replace.");
        String rep = with == null ? "" : with;
        if (!matchCase || wholeWord || !"all".equalsIgnoreCase(occurrence == null ? "all" : occurrence)) {
            List<Hit> hs = hits(targetSlides(s(ctx, session), slide), searchPattern(find, matchCase, wholeWord), occurrence);
            int done = 0;
            for (int i = hs.size() - 1; i >= 0; i--) {  // back to front keeps earlier offsets valid
                Hit h = hs.get(i);
                List<XSLFTextRun> runs = isolate(h.p(), List.of(new int[]{h.start(), h.end()}));
                boolean simple = h.p().getTextRuns().stream().allMatch(r -> r.getXmlObject() instanceof CTRegularTextRun);
                if (runs.isEmpty()) continue;
                if (simple) {
                    runs.get(0).setText(rep);
                    for (int k = runs.size() - 1; k > 0; k--) h.p().removeTextRun(runs.get(k));
                    done++;
                } else if (runs.size() == 1) {  // paragraph with breaks or fields: replace inside the one run holding the hit
                    int pos = 0;
                    for (XSLFTextRun r : h.p().getTextRuns()) { if (r == runs.get(0)) break; pos += r.getRawText() == null ? 0 : r.getRawText().length(); }
                    String rt = runs.get(0).getRawText();
                    runs.get(0).setText(rt.substring(0, h.start() - pos) + rep + rt.substring(h.end() - pos));
                    done++;
                }
            }
            return Map.of("count", BigDecimal.valueOf(done));
        }
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

    public Map<String, Object> formatText(ActionContext ctx, String text, Boolean bold, Boolean italic, Boolean underline, Double size, String colour, String font, Integer slide, String session) {
        return formatText(ctx, text, bold, italic, underline, size, colour, font, slide, session, true, false, "all", null);
    }

    @Action(id = "format_text", name = "Format text", category = "Text",
            description = "Apply bold, italic, underline, strikethrough, size, colour or font to occurrences of a text on the slides; inputs left empty are not changed.",
            panel = "text, match_case, whole_word, (Look in: All slides = / One slide = slide), occurrence, font, size, color, bold, italic, underline | strikethrough, session")
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
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"powerpoint\"", description = "Presentation session.") String session,
            @Input(name = "match_case", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = D_MATCH_CASE) boolean matchCase,
            @Input(name = "whole_word", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = D_WHOLE_WORD) boolean wholeWord,
            @Input(name = "occurrence", type = OoaType.STRING, required = false, defaultValue = "\"all\"", enumValues = {"all", "first", "last"}, description = D_OCCURRENCE) String occurrence,
            @Input(name = "strikethrough", type = OoaType.BOOLEAN, required = false, description = "Strikethrough on or off.") Boolean strike) {
        if (text == null || text.isEmpty()) throw new ActionException("PPT.EMPTY_FIND", "Text to format is empty", "Give the text to format.");
        Color c = colour(colour);
        List<Hit> hs = hits(targetSlides(s(ctx, session), slide), searchPattern(text, matchCase, wholeWord), occurrence);
        Map<XSLFTextParagraph, List<int[]>> byPara = new LinkedHashMap<>();
        for (Hit h : hs) byPara.computeIfAbsent(h.p(), k -> new ArrayList<>()).add(new int[]{h.start(), h.end()});
        for (var e : byPara.entrySet())
            for (XSLFTextRun r : isolate(e.getKey(), e.getValue())) {
                if (bold != null) r.setBold(bold);
                if (italic != null) r.setItalic(italic);
                if (underline != null) r.setUnderlined(underline);
                if (size != null) r.setFontSize(size);
                if (c != null) r.setFontColor(c);
                if (font != null && !font.isBlank()) r.setFontFamily(font);
                if (strike != null) r.setStrikethrough(strike);
            }
        return Map.of("count", BigDecimal.valueOf(hs.size()));
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

    public Map<String, Object> insertPicture(ActionContext ctx, int slide, String image, double x, double y, Double width, String altText, String session) {
        return insertPicture(ctx, slide, image, x, y, width, altText, session, null);
    }

    @Action(id = "insert_picture", name = "Insert picture", category = "Pictures", capabilities = "filesystem",
            description = "Place an image file on a slide at a position in points, optionally scaled to a width, a height or both.",
            panel = "(Slide: The last slide = / A slide number = slide), image, x, y, width, height | alt_text, session")
    @Output(name = "shape", type = OoaType.STRING, description = "Name of the new picture shape.")
    public Map<String, Object> insertPicture(ActionContext ctx,
            @Input(name = "slide", type = OoaType.NUMBER, required = false, min = 1, description = "1-based slide number; the last slide (usually the one just added) when empty.") Integer slide,
            @Input(name = "image", type = OoaType.FILE, description = "Image file (PNG, JPEG, GIF, BMP, TIFF, EMF or WMF).") String image,
            @Input(name = "x", type = OoaType.NUMBER, required = false, defaultValue = "50", description = "Left edge in points.") double x,
            @Input(name = "y", type = OoaType.NUMBER, required = false, defaultValue = "50", description = "Top edge in points.") double y,
            @Input(name = "width", type = OoaType.NUMBER, required = false, description = "Width in points; the height keeps the aspect ratio. Original size when empty.") Double width,
            @Input(name = "alt_text", type = OoaType.STRING, required = false, description = "Alternative text, also used later by find_replace_picture.") String altText,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"powerpoint\"", description = "Presentation session.") String session,
            @Input(name = "height", type = OoaType.NUMBER, required = false,
                    description = "Height in points; with width both are used as given, alone the width keeps the aspect ratio.") Double height) {
        DeckSession d = s(ctx, session);
        XSLFSlide sl = slide(d, slide == null ? d.ppt.getSlides().size() : slide);
        Path img = ctx.resolvePath(image);
        PictureData.PictureType type = pictureType(img);
        byte[] bytes = WordPackage.readImage(img, "PPT");
        double[] size = WordPackage.sizePoints(bytes, width, height);
        XSLFPictureShape pic = sl.createPicture(d.ppt.addPicture(bytes, type));
        pic.setAnchor(new Rectangle2D.Double(x, y, size[0], size[1]));
        if (altText != null && !altText.isEmpty()) ctPicture(pic).getNvPicPr().getCNvPr().setDescr(altText);
        return Map.of("shape", pic.getShapeName());
    }

    public Map<String, Object> findReplacePicture(ActionContext ctx, String image, String altText, Integer index, String session) {
        return findReplacePicture(ctx, image, altText, index, session, null, "all", null, null, null, true);
    }

    @Action(id = "find_replace_picture", name = "Find and replace picture", category = "Pictures", capabilities = "filesystem",
            description = "Swap pictures, found by alternative text or by their order across the slides, for a new image file, keeping the position and, unless a new size is given, the size.",
            panel = "(Find the picture by: Its alt text = alt_text, occurrence / Its position = index), (Look in: All slides = / One slide = slide), image, new_alt_text, width, height, keep_aspect_ratio | session")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of pictures replaced.")
    public Map<String, Object> findReplacePicture(ActionContext ctx,
            @Input(name = "image", type = OoaType.FILE, description = "New image file.") String image,
            @Input(name = "alt_text", type = OoaType.STRING, required = false, description = "Replace every picture whose alternative text equals this.") String altText,
            @Input(name = "index", type = OoaType.NUMBER, required = false, description = "1-based position of the picture counting across all slides; used when alt_text is empty.") Integer index,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"powerpoint\"", description = "Presentation session.") String session,
            @Input(name = "slide", type = OoaType.NUMBER, required = false, description = "Only look on this 1-based slide (index then counts on that slide); all slides when empty.") Integer slide,
            @Input(name = "occurrence", type = OoaType.STRING, required = false, defaultValue = "\"all\"", enumValues = {"all", "first", "last"},
                    description = "With alt_text: replace all matching pictures, or only the first or last one in slide order.") String occurrence,
            @Input(name = "new_alt_text", type = OoaType.STRING, required = false, description = "New alternative text for the replaced pictures; kept when empty.") String newAlt,
            @Input(name = "width", type = OoaType.NUMBER, required = false, description = "New width in points; the current size is kept when width and height are empty.") Double width,
            @Input(name = "height", type = OoaType.NUMBER, required = false, description = "New height in points.") Double height,
            @Input(name = "keep_aspect_ratio", type = OoaType.BOOLEAN, required = false, defaultValue = "true",
                    description = "When only width or only height is given, scale the other side to the new image's proportions.") boolean keepRatio) {
        DeckSession d = s(ctx, session);
        Path img = ctx.resolvePath(image);
        PictureData.PictureType type = pictureType(img);
        byte[] bytes = WordPackage.readImage(img, "PPT");
        List<Object[]> all = new ArrayList<>();
        for (XSLFSlide sl : targetSlides(d, slide)) for (XSLFPictureShape p : pictureShapes(sl.getShapes())) all.add(new Object[]{sl, p});
        List<Object[]> targets = new ArrayList<>();
        if (altText != null && !altText.isEmpty()) {
            for (Object[] o : all) if (altText((XSLFPictureShape) o[1]).equals(altText)) targets.add(o);
            targets = new ArrayList<>(WordPackage.pickOccurrence(targets, occurrence, "PPT"));
        }
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
            XSLFPictureShape ps = (XSLFPictureShape) o[1];
            ctPicture(ps).getBlipFill().getBlip().setEmbed(rel);
            if (newAlt != null && !newAlt.isEmpty()) ctPicture(ps).getNvPicPr().getCNvPr().setDescr(newAlt);
            boolean hasW = width != null && width > 0, hasH = height != null && height > 0;
            if (hasW || hasH) {
                Rectangle2D a = ps.getAnchor();
                double[] size = keepRatio ? WordPackage.sizePoints(bytes, width, height)
                        : new double[]{hasW ? width : a.getWidth(), hasH ? height : a.getHeight()};
                ps.setAnchor(new Rectangle2D.Double(a.getX(), a.getY(), size[0], size[1]));
            }
        }
        return Map.of("count", BigDecimal.valueOf(targets.size()));
    }

    public Map<String, Object> insertTable(ActionContext ctx, int slide, Map<String, Object> table, double x, double y, double width, double fontSize, String session) {
        return insertTable(ctx, slide, table, x, y, width, fontSize, session, true, null, null);
    }

    @Action(id = "insert_table", name = "Insert table", category = "Tables",
            description = "Add a table to a slide from a Data Table, with the column names as an optional bold header row, at a position and size in points.",
            panel = "(Slide: The last slide = / A slide number = slide), x, y, width, row_height, column_widths, table, include_header | font_size, session")
    @Output(name = "shape", type = OoaType.STRING, description = "Name of the new table shape.")
    public Map<String, Object> insertTable(ActionContext ctx,
            @Input(name = "slide", type = OoaType.NUMBER, required = false, min = 1, description = "1-based slide number; the last slide (usually the one just added) when empty.") Integer slide,
            @Input(name = "table", type = OoaType.TABLE, description = "Data to insert.") Map<String, Object> table,
            @Input(name = "x", type = OoaType.NUMBER, required = false, defaultValue = "50", description = "Left edge in points.") double x,
            @Input(name = "y", type = OoaType.NUMBER, required = false, defaultValue = "120", description = "Top edge in points.") double y,
            @Input(name = "width", type = OoaType.NUMBER, required = false, defaultValue = "600", description = "Total table width in points.") double width,
            @Input(name = "font_size", type = OoaType.NUMBER, required = false, defaultValue = "14", description = "Font size in points.") double fontSize,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"powerpoint\"", description = "Presentation session.") String session,
            @Input(name = "include_header", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Put the column names in a bold first row.") boolean includeHeader,
            @Input(name = "row_height", type = OoaType.NUMBER, required = false, description = "Height of each row in points; twice the font size when empty.") Double rowHeightPt,
            @Input(name = "column_widths", type = OoaType.LIST, required = false,
                    description = "Width of each column in points, in order; the total width is shared equally when empty.") List<Object> columnWidths) {
        XSLFSlide sl = slide(s(ctx, session), slide == null ? lastSlide(s(ctx, session)) : slide);
        List<?> cols = (List<?>) table.get("columns");
        List<?> rows = (List<?>) table.get("rows");
        if (cols == null || cols.isEmpty()) throw new ActionException("PPT.EMPTY_TABLE", "The table has no columns", "Pass a Data Table with at least one column.");
        if (columnWidths != null && !columnWidths.isEmpty() && columnWidths.size() != cols.size())
            throw new ActionException("PPT.BAD_WIDTHS", "column_widths has " + columnWidths.size() + " values for " + cols.size() + " columns", "Give one width per column.");
        XSLFTable t = sl.createTable();
        double rowHeight = rowHeightPt != null && rowHeightPt > 0 ? rowHeightPt : fontSize * 2;
        List<List<?>> lines = new ArrayList<>();
        if (includeHeader) lines.add(cols);
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
                if (i == 0 && includeHeader) run.setBold(true);
                cell.setBorderColor(org.apache.poi.sl.usermodel.TableCell.BorderEdge.top, Color.GRAY);
                cell.setBorderColor(org.apache.poi.sl.usermodel.TableCell.BorderEdge.bottom, Color.GRAY);
                cell.setBorderColor(org.apache.poi.sl.usermodel.TableCell.BorderEdge.left, Color.GRAY);
                cell.setBorderColor(org.apache.poi.sl.usermodel.TableCell.BorderEdge.right, Color.GRAY);
            }
        }
        double total = 0;
        for (int c = 0; c < cols.size(); c++) {
            double w = columnWidths == null || columnWidths.isEmpty() ? width / cols.size() : Values.toNumber(columnWidths.get(c)).doubleValue();
            t.setColumnWidth(c, w);
            total += w;
        }
        t.setAnchor(new Rectangle2D.Double(x, y, total, rowHeight * lines.size()));
        return Map.of("shape", t.getShapeName());
    }

}
