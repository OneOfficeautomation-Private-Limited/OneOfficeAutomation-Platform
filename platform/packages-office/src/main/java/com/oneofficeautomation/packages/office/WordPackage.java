package com.oneofficeautomation.packages.office;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;
import org.apache.poi.xwpf.usermodel.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;

/** Read, create and edit Word .docx documents without Microsoft Word (Apache POI). */
@OoaPackage(id = "ooa.word", name = "Word (no Office needed)", version = "1.0.0",
        description = "Open or create .docx documents, read text and tables, replace text, add paragraphs and tables, save.")
public final class WordPackage {

    public static final class DocSession implements AutoCloseable {
        Path path; final XWPFDocument doc;
        DocSession(Path p, XWPFDocument d) { path = p; doc = d; }
        @Override public void close() throws IOException { doc.close(); }
    }

    private static DocSession s(ActionContext ctx, String name) { return ctx.session(name, DocSession.class); }

    /** Every paragraph in the body and in table cells, in document order. */
    private static List<XWPFParagraph> allParagraphs(XWPFDocument d) {
        List<XWPFParagraph> out = new ArrayList<>(d.getParagraphs());
        for (XWPFTable t : d.getTables()) for (XWPFTableRow r : t.getRows()) for (XWPFTableCell c : r.getTableCells()) out.addAll(c.getParagraphs());
        for (XWPFHeader h : d.getHeaderList()) out.addAll(h.getParagraphs());
        for (XWPFFooter f : d.getFooterList()) out.addAll(f.getParagraphs());
        return out;
    }

    @Action(id = "open", name = "Open document", category = "Document", capabilities = "filesystem", description = "Open a .docx document, or create a new one, as a named session.")
    public Map<String, Object> open(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "Document path.") String path,
            @Input(name = "create", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Create the document when it does not exist.") boolean create,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"word\"", description = "Session name.") String session) {
        Path p = ctx.resolvePath(path);
        XWPFDocument doc;
        if (Files.exists(p)) {
            try (InputStream in = Files.newInputStream(p)) { doc = new XWPFDocument(in); }
            catch (IOException | RuntimeException e) { throw new ActionException("WORD.OPEN_FAILED", "Cannot open " + p + ": " + e.getMessage(), "Close it in Word and check it is a .docx file.", true); }
        } else if (create) doc = new XWPFDocument();
        else throw new ActionException("WORD.NOT_FOUND", "Document not found: " + p, "Set create to true or fix the path.");
        ctx.sessions().put(session, new DocSession(p, doc));
        return Map.of();
    }

    @Action(id = "read_text", name = "Read text", category = "Text", idempotent = true, description = "Return all paragraph text, one paragraph per line.")
    @Output(name = "text", type = OoaType.STRING, description = "Document text.")
    @Output(name = "paragraphs", type = OoaType.LIST, description = "Each body paragraph's text.")
    public Map<String, Object> readText(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"word\"", description = "Session name.") String session) {
        List<Object> paras = new ArrayList<>();
        for (XWPFParagraph p : s(ctx, session).doc.getParagraphs()) paras.add(p.getText());
        StringJoiner j = new StringJoiner("\n");
        for (Object o : paras) j.add((String) o);
        return Map.of("text", j.toString(), "paragraphs", paras);
    }

    @Action(id = "replace_text", name = "Replace text", category = "Text",
            description = "Replace text everywhere (body, tables, headers, footers), e.g. fill {{customer}} placeholders in a template.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of replacements.")
    public Map<String, Object> replaceText(ActionContext ctx,
            @Input(name = "find", type = OoaType.STRING, example = "{{customer}}", description = "Text to find.") String find,
            @Input(name = "replace_with", type = OoaType.STRING, required = false, defaultValue = "\"\"", description = "Replacement text.") String with,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"word\"", description = "Session name.") String session) {
        if (find.isEmpty()) throw new ActionException("WORD.EMPTY_FIND", "Text to find is empty");
        int count = 0;
        for (XWPFParagraph p : allParagraphs(s(ctx, session).doc)) {
            String text = p.getText();
            if (text == null || !text.contains(find)) continue;
            int n = (text.length() - text.replace(find, "").length()) / find.length();
            boolean done = false;
            for (XWPFRun r : p.getRuns()) {  // keep formatting when the text sits inside a single run
                String t = r.getText(0);
                if (t != null && t.contains(find)) { r.setText(t.replace(find, with), 0); done = true; }
            }
            if (!done || p.getText().contains(find)) {  // text split across runs: rewrite into the first run
                String full = p.getText().replace(find, with);
                List<XWPFRun> runs = p.getRuns();
                for (int i = runs.size() - 1; i > 0; i--) p.removeRun(i);
                if (runs.isEmpty()) p.createRun().setText(full, 0); else p.getRuns().get(0).setText(full, 0);
            }
            count += n;
        }
        return Map.of("count", BigDecimal.valueOf(count));
    }

    @Action(id = "add_paragraph", name = "Add paragraph", category = "Text", description = "Append a paragraph, optionally bold or with a style such as Heading1.")
    public Map<String, Object> addParagraph(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Paragraph text.") String text,
            @Input(name = "bold", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Bold text.") boolean bold,
            @Input(name = "style", type = OoaType.STRING, required = false, description = "Style id from the document, e.g. Heading1.") String style,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"word\"", description = "Session name.") String session) {
        XWPFParagraph p = s(ctx, session).doc.createParagraph();
        if (style != null && !style.isBlank()) p.setStyle(style);
        String[] lines = text.split("\n", -1);
        XWPFRun r = p.createRun();
        r.setBold(bold);
        for (int i = 0; i < lines.length; i++) { if (i > 0) r.addBreak(); r.setText(lines[i], i); }
        return Map.of();
    }

    @Action(id = "add_table", name = "Insert table", category = "Tables", description = "Append a table built from a Data Table (header row first).")
    public Map<String, Object> addTable(ActionContext ctx,
            @Input(name = "table", type = OoaType.TABLE, description = "Data to insert.") Map<String, Object> table,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"word\"", description = "Session name.") String session) {
        List<?> cols = (List<?>) table.get("columns");
        List<?> rows = (List<?>) table.get("rows");
        if (cols.isEmpty()) throw new ActionException("WORD.EMPTY_TABLE", "The table has no columns");
        XWPFTable t = s(ctx, session).doc.createTable(rows.size() + 1, cols.size());
        for (int c = 0; c < cols.size(); c++) {
            XWPFTableCell cell = t.getRow(0).getCell(c);
            cell.removeParagraph(0);
            XWPFRun run = cell.addParagraph().createRun();
            run.setBold(true);
            run.setText(Values.text(cols.get(c)));
        }
        for (int r = 0; r < rows.size(); r++) {
            List<?> row = (List<?>) rows.get(r);
            for (int c = 0; c < cols.size(); c++) t.getRow(r + 1).getCell(c).setText(c < row.size() && row.get(c) != null ? Values.text(Values.toJsonable(row.get(c))) : "");
        }
        return Map.of();
    }

    @Action(id = "read_tables", name = "Read tables", category = "Tables", idempotent = true, description = "Read every table in the document; the first row of each becomes its column names.")
    @Output(name = "tables", type = OoaType.LIST, description = "List of tables.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of tables.")
    public Map<String, Object> readTables(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"word\"", description = "Session name.") String session) {
        List<Object> out = new ArrayList<>();
        for (XWPFTable t : s(ctx, session).doc.getTables()) {
            List<String> cols = new ArrayList<>();
            List<Object> rows = new ArrayList<>();
            List<XWPFTableRow> trs = t.getRows();
            for (int r = 0; r < trs.size(); r++) {
                List<Object> vals = new ArrayList<>();
                for (XWPFTableCell c : trs.get(r).getTableCells()) vals.add(c.getText());
                if (r == 0) for (Object v : vals) cols.add(String.valueOf(v)); else rows.add(vals);
            }
            out.add(Values.table(cols, rows));
        }
        return Map.of("tables", out, "count", BigDecimal.valueOf(out.size()));
    }

    @Action(id = "save", name = "Save", category = "Document", capabilities = "filesystem", description = "Save the document, optionally to a new path.")
    public Map<String, Object> save(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, required = false, description = "Save-as path; the opened file when empty.") String path,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"word\"", description = "Session name.") String session) {
        DocSession d = s(ctx, session);
        Path target = path == null ? d.path : ctx.resolvePath(path);
        try {
            if (target.getParent() != null) Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            try (OutputStream out = Files.newOutputStream(tmp)) { d.doc.write(out); }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) { throw new ActionException("WORD.SAVE_FAILED", "Cannot save " + target + ": " + e.getMessage(), "Close the file if it is open in Word.", true); }
        return Map.of();
    }

    @Action(id = "close", name = "Close", category = "Document", description = "Close the document without saving.")
    public Map<String, Object> close(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"word\"", description = "Session name.") String session) {
        Object o = ctx.sessions().remove(session);
        if (o instanceof DocSession d) try { d.close(); } catch (IOException ignored) { }
        return Map.of();
    }

    // ------------------------------------------------------------------------------------------------
    // Extended actions
    // ------------------------------------------------------------------------------------------------

    @Action(id = "create_document", name = "Create document", category = "Document", capabilities = "filesystem",
            description = "Create a new empty .docx file on disk and open it as a named session.")
    public Map<String, Object> createDocument(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "Path of the new document.") String path,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Replace a file that already exists at the path.") boolean overwrite,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"word\"", description = "Session name.") String session) {
        Path p = ctx.resolvePath(path);
        if (Files.exists(p) && !overwrite) throw new ActionException("WORD.EXISTS", "A file already exists at " + p, "Set overwrite to true or use open to edit it.");
        XWPFDocument doc = new XWPFDocument();
        doc.createParagraph();
        DocSession d = new DocSession(p, doc);
        Object old = ctx.sessions().put(session, d);
        if (old instanceof AutoCloseable c) try { c.close(); } catch (Exception ignored) { }
        writeTo(d, p);
        return Map.of();
    }

    private static void writeTo(DocSession d, Path target) {
        try {
            if (target.getParent() != null) Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            try (OutputStream out = Files.newOutputStream(tmp)) { d.doc.write(out); }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) { throw new ActionException("WORD.SAVE_FAILED", "Cannot save " + target + ": " + e.getMessage(), "Close the file if it is open in Word.", true); }
    }

    /** Finds the first paragraph (body, tables, headers, footers) containing the text. */
    private static XWPFParagraph findParagraph(XWPFDocument d, String text) {
        for (XWPFParagraph p : allParagraphs(d)) if (p.getText() != null && p.getText().contains(text)) return p;
        throw new ActionException("WORD.TEXT_NOT_FOUND", "Text '" + text + "' was not found in the document", "Check the marker text; it must appear exactly.");
    }

    private static XWPFParagraph insertBefore(XWPFParagraph p, org.apache.xmlbeans.XmlCursor c) {
        IBody body = p.getBody();
        XWPFParagraph np;
        if (body instanceof XWPFTableCell cell) np = cell.insertNewParagraph(c);
        else if (body instanceof XWPFDocument d) np = d.insertNewParagraph(c);
        else if (body instanceof XWPFHeaderFooter hf) np = hf.insertNewParagraph(c);
        else np = null;
        if (np == null) throw new ActionException("WORD.INSERT_FAILED", "Cannot insert a paragraph at that position", "Use position end instead.");
        return np;
    }

    private static XWPFParagraph paragraphAfter(XWPFParagraph p) {
        org.apache.xmlbeans.XmlCursor c = p.getCTP().newCursor();
        try {
            c.toEndToken();
            c.toNextToken();  // just after the closing paragraph tag
            XWPFParagraph np = insertBefore(p, c);
            if (p.getCTP().isSetPPr()) np.getCTP().setPPr((org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr) p.getCTP().getPPr().copy());
            return np;
        } finally { c.close(); }
    }

    /** Splits runs so that [start,end) of the paragraph text is covered by whole runs; returns those runs. */
    private static List<XWPFRun> isolate(XWPFParagraph p, int start, int end) {
        splitAt(p, start);
        splitAt(p, end);
        List<XWPFRun> out = new ArrayList<>();
        int pos = 0;
        for (XWPFRun r : p.getRuns()) {
            int len = runText(r).length();
            if (pos >= start && pos + len <= end && len > 0) out.add(r);
            pos += len;
        }
        return out;
    }

    private static String runText(XWPFRun r) { String t = r.text(); return t == null ? "" : t; }

    private static void splitAt(XWPFParagraph p, int offset) {
        int pos = 0;
        List<XWPFRun> runs = p.getRuns();
        for (int i = 0; i < runs.size(); i++) {
            XWPFRun r = runs.get(i);
            String t = runText(r);
            if (offset > pos && offset < pos + t.length()) {
                int k = offset - pos;
                XWPFRun tail = p.insertNewRun(i + 1);
                if (r.getCTR().isSetRPr()) tail.getCTR().setRPr((org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRPr) r.getCTR().getRPr().copy());
                setRunText(r, t.substring(0, k));
                setRunText(tail, t.substring(k));
                return;
            }
            pos += t.length();
        }
    }

    private static void setRunText(XWPFRun r, String text) {
        var ctr = r.getCTR();
        for (int i = ctr.sizeOfTArray() - 1; i >= 0; i--) ctr.removeT(i);
        for (int i = ctr.sizeOfTabArray() - 1; i >= 0; i--) ctr.removeTab(i);
        for (int i = ctr.sizeOfBrArray() - 1; i >= 0; i--) ctr.removeBr(i);
        r.setText(text, 0);
    }

    private static void writeLines(XWPFRun r, String text) {
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) { if (i > 0) r.addBreak(); r.setText(lines[i], i); }
    }

    @Action(id = "insert_text", name = "Insert text", category = "Text",
            description = "Insert text at the end of the document, or just before or after a marker text or a bookmark.")
    public Map<String, Object> insertText(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Text to insert; line breaks are kept.") String text,
            @Input(name = "position", type = OoaType.STRING, required = false, defaultValue = "\"end\"",
                    enumValues = {"end", "after_text", "before_text", "bookmark"}, description = "Where to insert: end, after_text, before_text or bookmark.") String position,
            @Input(name = "anchor", type = OoaType.STRING, required = false, description = "Marker text or bookmark name the position refers to.") String anchor,
            @Input(name = "new_paragraph", type = OoaType.BOOLEAN, required = false, defaultValue = "true",
                    description = "Put the text in its own paragraph; false adds it inside the existing paragraph.") boolean newParagraph,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"word\"", description = "Session name.") String session) {
        XWPFDocument d = s(ctx, session).doc;
        String pos = position == null ? "end" : position.toLowerCase(Locale.ROOT);
        if (!pos.equals("end") && (anchor == null || anchor.isEmpty()))
            throw new ActionException("WORD.NO_ANCHOR", "Position '" + pos + "' needs an anchor", "Set anchor to the marker text or bookmark name.");
        switch (pos) {
            case "end" -> {
                List<IBodyElement> els = d.getBodyElements();
                XWPFParagraph last = !els.isEmpty() && els.get(els.size() - 1) instanceof XWPFParagraph lp ? lp : null;
                XWPFParagraph p = !newParagraph && last != null ? last : d.createParagraph();
                writeLines(p.createRun(), text);
            }
            case "after_text", "before_text" -> {
                XWPFParagraph p = findParagraph(d, anchor);
                if (newParagraph) {
                    XWPFParagraph np;
                    if (pos.equals("after_text")) np = paragraphAfter(p);
                    else {
                        org.apache.xmlbeans.XmlCursor c = p.getCTP().newCursor();
                        try { np = insertBefore(p, c); } finally { c.close(); }
                    }
                    writeLines(np.createRun(), text);
                } else {
                    String all = p.getRuns().stream().map(WordPackage::runText).reduce("", String::concat);
                    int found = all.indexOf(anchor);
                    if (found < 0) throw new ActionException("WORD.TEXT_NOT_FOUND", "Text '" + anchor + "' is not inside a single paragraph's runs", "Use new_paragraph true.");
                    int at = found + (pos.equals("after_text") ? anchor.length() : 0);
                    splitAt(p, at);
                    List<XWPFRun> runs = p.getRuns();
                    int idx = 0, acc = 0;
                    while (idx < runs.size() && acc < at) acc += runText(runs.get(idx++)).length();
                    XWPFRun style = idx > 0 ? runs.get(idx - 1) : runs.isEmpty() ? null : runs.get(0);
                    XWPFRun r = p.insertNewRun(idx);
                    if (style != null && style.getCTR().isSetRPr()) r.getCTR().setRPr((org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRPr) style.getCTR().getRPr().copy());
                    writeLines(r, text);
                }
            }
            case "bookmark" -> {
                for (XWPFParagraph p : allParagraphs(d)) {
                    for (var bm : p.getCTP().getBookmarkStartList()) {
                        if (!anchor.equals(bm.getName())) continue;
                        if (newParagraph) { writeLines(paragraphAfter(p).createRun(), text); return Map.of(); }
                        int runsBefore = 0;  // runs that come before the bookmark start inside the paragraph
                        org.apache.xmlbeans.XmlCursor c = p.getCTP().newCursor();
                        try {
                            if (c.toFirstChild()) do {
                                if (c.getObject() == bm) break;
                                if (c.getObject() instanceof org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR) runsBefore++;
                            } while (c.toNextSibling());
                        } finally { c.close(); }
                        writeLines(p.insertNewRun(runsBefore), text);
                        return Map.of();
                    }
                }
                throw new ActionException("WORD.NO_BOOKMARK", "Bookmark '" + anchor + "' not found", "Check the bookmark name in Word (Insert > Bookmark).");
            }
            default -> throw new ActionException("WORD.BAD_POSITION", "Unknown position '" + position + "'", "Use end, after_text, before_text or bookmark.");
        }
        return Map.of();
    }

    static int pictureType(Path image, String prefix) {
        String n = image.getFileName().toString().toLowerCase(Locale.ROOT);
        if (n.endsWith(".png")) return Document.PICTURE_TYPE_PNG;
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return Document.PICTURE_TYPE_JPEG;
        if (n.endsWith(".gif")) return Document.PICTURE_TYPE_GIF;
        if (n.endsWith(".bmp")) return Document.PICTURE_TYPE_BMP;
        if (n.endsWith(".tif") || n.endsWith(".tiff")) return Document.PICTURE_TYPE_TIFF;
        if (n.endsWith(".emf")) return Document.PICTURE_TYPE_EMF;
        if (n.endsWith(".wmf")) return Document.PICTURE_TYPE_WMF;
        throw new ActionException(prefix + ".BAD_IMAGE", "Unsupported image type: " + image.getFileName(), "Use a PNG, JPEG, GIF, BMP, TIFF, EMF or WMF file.");
    }

    static byte[] readImage(Path p, String prefix) {
        if (!Files.isRegularFile(p)) throw new ActionException(prefix + ".IMAGE_NOT_FOUND", "Image not found: " + p, "Fix the image path.");
        try { return Files.readAllBytes(p); }
        catch (IOException e) { throw new ActionException(prefix + ".IMAGE_NOT_FOUND", "Cannot read " + p + ": " + e.getMessage(), "Check the file permissions.", true); }
    }

    /** Image size in points (1/72 inch), scaled to the requested width when one is given. */
    static double[] sizePoints(byte[] bytes, Double widthPt) {
        double w = 200, h = 150;
        try {
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
            if (img != null) { w = img.getWidth() * 0.75; h = img.getHeight() * 0.75; }
        } catch (IOException ignored) { }
        if (widthPt != null && widthPt > 0) { h = h * widthPt / w; w = widthPt; }
        return new double[]{w, h};
    }

    @Action(id = "insert_picture", name = "Insert picture", category = "Pictures", capabilities = "filesystem",
            description = "Insert an image file at the end of the document or after a marker text, optionally scaled to a width.")
    public Map<String, Object> insertPicture(ActionContext ctx,
            @Input(name = "image", type = OoaType.FILE, description = "Image file (PNG, JPEG, GIF, BMP, TIFF, EMF or WMF).") String image,
            @Input(name = "width", type = OoaType.NUMBER, required = false, description = "Width in points (72 per inch); the height keeps the aspect ratio. Original size when empty.") Double width,
            @Input(name = "after_text", type = OoaType.STRING, required = false, description = "Put the picture in a new paragraph after the paragraph with this text; at the end when empty.") String afterText,
            @Input(name = "alt_text", type = OoaType.STRING, required = false, description = "Alternative text, also used later by find_replace_picture.") String altText,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"word\"", description = "Session name.") String session) {
        XWPFDocument d = s(ctx, session).doc;
        Path img = ctx.resolvePath(image);
        int type = pictureType(img, "WORD");
        byte[] bytes = readImage(img, "WORD");
        double[] size = sizePoints(bytes, width);
        XWPFParagraph p = afterText == null || afterText.isEmpty() ? d.createParagraph() : paragraphAfter(findParagraph(d, afterText));
        XWPFRun r = p.createRun();
        try {
            r.addPicture(new java.io.ByteArrayInputStream(bytes), type, img.getFileName().toString(),
                    org.apache.poi.util.Units.toEMU(size[0]), org.apache.poi.util.Units.toEMU(size[1]));
        } catch (Exception e) { throw new ActionException("WORD.BAD_IMAGE", "Cannot insert " + img.getFileName() + ": " + e.getMessage(), "Check the image file is not damaged."); }
        if (altText != null && !altText.isEmpty()) {
            var drawing = r.getCTR().getDrawingArray(r.getCTR().sizeOfDrawingArray() - 1);
            if (drawing.sizeOfInlineArray() > 0) {
                var inline = drawing.getInlineArray(0);
                inline.getDocPr().setDescr(altText);
                for (org.apache.xmlbeans.XmlObject x : inline.getGraphic().getGraphicData().selectPath(PIC_PATH))
                    if (x instanceof org.openxmlformats.schemas.drawingml.x2006.picture.CTPicture live) live.getNvPicPr().getCNvPr().setDescr(altText);
            }
        }
        return Map.of();
    }

    private record PicRef(XWPFRun run, org.openxmlformats.schemas.drawingml.x2006.picture.CTPicture pic, String alt) { }

    private static final String PIC_PATH = "declare namespace pic='http://schemas.openxmlformats.org/drawingml/2006/picture' .//pic:pic";

    /** Pictures in document order, read from the live drawing XML (POI's cached picture objects can be detached copies). */
    private static List<PicRef> pictures(XWPFDocument d) {
        List<PicRef> out = new ArrayList<>();
        for (XWPFParagraph p : allParagraphs(d)) for (XWPFRun r : p.getRuns()) for (var dr : r.getCTR().getDrawingList()) {
            List<Object[]> frames = new ArrayList<>();
            for (var in : dr.getInlineList()) frames.add(new Object[]{in.getDocPr(), in.getGraphic()});
            for (var an : dr.getAnchorList()) frames.add(new Object[]{an.getDocPr(), an.getGraphic()});
            for (Object[] f : frames) {
                var docPr = (org.openxmlformats.schemas.drawingml.x2006.main.CTNonVisualDrawingProps) f[0];
                var graphic = (org.openxmlformats.schemas.drawingml.x2006.main.CTGraphicalObject) f[1];
                if (graphic == null || graphic.getGraphicData() == null) continue;
                for (org.apache.xmlbeans.XmlObject x : graphic.getGraphicData().selectPath(PIC_PATH)) {
                    if (!(x instanceof org.openxmlformats.schemas.drawingml.x2006.picture.CTPicture pic)) continue;
                    String alt = docPr == null ? null : docPr.getDescr();
                    if ((alt == null || alt.isEmpty()) && pic.getNvPicPr() != null) alt = pic.getNvPicPr().getCNvPr().getDescr();
                    out.add(new PicRef(r, pic, alt == null ? "" : alt));
                }
            }
        }
        return out;
    }

    @Action(id = "find_replace_picture", name = "Find and replace picture", category = "Pictures", capabilities = "filesystem",
            description = "Swap an existing picture, found by its alternative text or its position in the document, for a new image file.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of pictures replaced.")
    public Map<String, Object> findReplacePicture(ActionContext ctx,
            @Input(name = "image", type = OoaType.FILE, description = "New image file.") String image,
            @Input(name = "alt_text", type = OoaType.STRING, required = false, description = "Replace every picture whose alternative text equals this.") String altText,
            @Input(name = "index", type = OoaType.NUMBER, required = false, description = "1-based position of the picture in the document; used when alt_text is empty.") Integer index,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"word\"", description = "Session name.") String session) {
        XWPFDocument d = s(ctx, session).doc;
        Path img = ctx.resolvePath(image);
        int type = pictureType(img, "WORD");
        byte[] bytes = readImage(img, "WORD");
        List<PicRef> all = pictures(d);
        List<PicRef> targets = new ArrayList<>();
        if (altText != null && !altText.isEmpty()) { for (PicRef p : all) if (p.alt().equals(altText)) targets.add(p); }
        else if (index != null) {
            if (index < 1 || index > all.size()) throw new ActionException("WORD.NO_PICTURE", "There is no picture number " + index + " (the document has " + all.size() + ")", "Use a number from 1 to " + all.size() + ".");
            targets.add(all.get(index - 1));
        } else throw new ActionException("WORD.NO_PICTURE", "Give alt_text or index to choose the picture", "Set alt_text or index.");
        for (PicRef t : targets) {
            org.apache.poi.ooxml.POIXMLDocumentPart part = t.run().getParent().getPart();
            String relId;
            try {
                if (part instanceof XWPFHeaderFooter hf) relId = hf.addPictureData(bytes, type);
                else relId = d.addPictureData(bytes, type);
            } catch (Exception e) { throw new ActionException("WORD.BAD_IMAGE", "Cannot add " + img.getFileName() + ": " + e.getMessage(), "Check the image file is not damaged."); }
            t.pic().getBlipFill().getBlip().setEmbed(relId);
        }
        return Map.of("count", BigDecimal.valueOf(targets.size()));
    }

    @Action(id = "format_text", name = "Format text", category = "Text",
            description = "Apply bold, italic, underline, font, size or colour to every occurrence of a text; inputs left empty are not changed.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of occurrences formatted.")
    public Map<String, Object> formatText(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Text to format wherever it appears.") String text,
            @Input(name = "bold", type = OoaType.BOOLEAN, required = false, description = "Bold on or off.") Boolean bold,
            @Input(name = "italic", type = OoaType.BOOLEAN, required = false, description = "Italic on or off.") Boolean italic,
            @Input(name = "underline", type = OoaType.BOOLEAN, required = false, description = "Single underline on or off.") Boolean underline,
            @Input(name = "size", type = OoaType.NUMBER, required = false, description = "Font size in points.") Double size,
            @Input(name = "color", type = OoaType.STRING, required = false, example = "#C00000", description = "Hex text colour.") String colour,
            @Input(name = "font", type = OoaType.STRING, required = false, description = "Font name, e.g. Calibri.") String font,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"word\"", description = "Session name.") String session) {
        if (text == null || text.isEmpty()) throw new ActionException("WORD.EMPTY_FIND", "Text to format is empty", "Give the text to format.");
        String hex = colour == null || colour.isBlank() ? null : colour.trim().replace("#", "").toUpperCase(Locale.ROOT);
        if (hex != null && !hex.matches("[0-9A-F]{6}")) throw new ActionException("WORD.BAD_COLOR", "'" + colour + "' is not a colour", "Use a hex colour such as #C00000.");
        int n = 0;
        for (XWPFParagraph p : allParagraphs(s(ctx, session).doc)) {
            String all = p.getRuns().stream().map(WordPackage::runText).reduce("", String::concat);
            int from = 0, at;
            while ((at = all.indexOf(text, from)) >= 0) {
                for (XWPFRun r : isolate(p, at, at + text.length())) {
                    if (bold != null) r.setBold(bold);
                    if (italic != null) r.setItalic(italic);
                    if (underline != null) r.setUnderline(underline ? UnderlinePatterns.SINGLE : UnderlinePatterns.NONE);
                    if (size != null) r.setFontSize(size);
                    if (hex != null) r.setColor(hex);
                    if (font != null && !font.isBlank()) r.setFontFamily(font);
                }
                n++;
                from = at + text.length();
            }
        }
        return Map.of("count", BigDecimal.valueOf(n));
    }

    @Action(id = "save_as", name = "Save as", category = "Document", capabilities = "filesystem",
            description = "Save the document to a new .docx file and continue the session on it; PDF output is not available in this toolkit.")
    public Map<String, Object> saveAs(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "Target file path.") String path,
            @Input(name = "format", type = OoaType.STRING, required = false, defaultValue = "\"docx\"", enumValues = {"docx"}, description = "File format; only docx is supported.") String format,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"word\"", description = "Session name.") String session) {
        DocSession d = s(ctx, session);
        Path target = ctx.resolvePath(path);
        String fmt = format == null || format.isBlank() ? "docx" : format.toLowerCase(Locale.ROOT);
        if (fmt.equals("pdf") || target.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf"))
            throw new ActionException("WORD.PDF_UNSUPPORTED", "Saving as PDF needs a document converter, which this toolkit does not include",
                    "Save as .docx, then convert it with Word desktop automation or a PDF service.");
        if (!fmt.equals("docx")) throw new ActionException("WORD.BAD_FORMAT", "Unsupported format '" + format + "'", "Use docx.");
        writeTo(d, target);
        d.path = target;
        return Map.of();
    }
}
