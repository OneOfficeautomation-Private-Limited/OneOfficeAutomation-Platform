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
        /** Opened with read_only: saving over the opened file is refused. */
        boolean readOnly;
        /** Password the file was opened or created with; saves keep the file encrypted. */
        String password;
        DocSession(Path p, XWPFDocument d) { path = p; doc = d; }
        @Override public void close() throws IOException { doc.close(); }
    }

    private static void refuseReadOnly(DocSession d, Path target) {
        if (d.readOnly && target.toAbsolutePath().normalize().equals(d.path.toAbsolutePath().normalize()))
            throw new ActionException("WORD.READ_ONLY", "The document was opened read-only", "Use save_as with another path, or open it without read_only.");
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

    public Map<String, Object> open(ActionContext ctx, String path, boolean create, String session) { return open(ctx, path, create, session, false, null); }

    @Action(id = "open", name = "Open document", category = "Document", capabilities = "filesystem",
            description = "Open a .docx document, or create a new one, as a named session; can open password-protected files and open read-only.")
    public Map<String, Object> open(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "Document path.") String path,
            @Input(name = "create", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Create the document when it does not exist.") boolean create,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"word\"", description = "Session name.") String session,
            @Input(name = "read_only", type = OoaType.BOOLEAN, required = false, defaultValue = "false",
                    description = "Open for reading only: save over the opened file is refused, save_as to another file still works.") boolean readOnly,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false,
                    description = "Password of an encrypted document; saves keep the file encrypted with it.") Object password) {
        Path p = ctx.resolvePath(path);
        String pw = OfficeCrypto.secret(password);
        XWPFDocument doc;
        if (Files.exists(p)) {
            try (InputStream in = OfficeCrypto.open(p, pw, "WORD")) { doc = new XWPFDocument(in); }
            catch (ActionException e) { throw e; }
            catch (IOException | RuntimeException e) { throw new ActionException("WORD.OPEN_FAILED", "Cannot open " + p + ": " + e.getMessage(), "Close it in Word and check it is a .docx file.", true); }
        } else if (create) doc = new XWPFDocument();
        else throw new ActionException("WORD.NOT_FOUND", "Document not found: " + p, "Set create to true or fix the path.");
        DocSession d = new DocSession(p, doc);
        d.readOnly = readOnly;
        d.password = pw;
        Object old = ctx.sessions().put(session, d);
        if (old instanceof AutoCloseable c && old != d) try { c.close(); } catch (Exception ignored) { }
        return Map.of();
    }

    public Map<String, Object> readText(ActionContext ctx, String session) { return readText(ctx, session, false, false, null, null); }

    @Action(id = "read_text", name = "Read text", category = "Text", idempotent = true,
            description = "Return the paragraph text, one paragraph per line, of the whole body or a range of paragraphs, optionally with tables, headers and footers.")
    @Output(name = "text", type = OoaType.STRING, description = "Document text.")
    @Output(name = "paragraphs", type = OoaType.LIST, description = "Each body paragraph's text.")
    public Map<String, Object> readText(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"word\"", description = "Session name.") String session,
            @Input(name = "include_tables", type = OoaType.BOOLEAN, required = false, defaultValue = "false",
                    description = "Also return the text of table cells, in document order, one cell per line.") boolean includeTables,
            @Input(name = "include_headers_footers", type = OoaType.BOOLEAN, required = false, defaultValue = "false",
                    description = "Also return the text of page headers (before the body) and footers (after it).") boolean includeHf,
            @Input(name = "from_paragraph", type = OoaType.NUMBER, required = false, description = "1-based first body paragraph to read; the first when empty.") Integer from,
            @Input(name = "to_paragraph", type = OoaType.NUMBER, required = false, description = "1-based last body paragraph to read; the last when empty.") Integer to) {
        XWPFDocument doc = s(ctx, session).doc;
        List<Object> paras = new ArrayList<>();
        if (includeHf) for (XWPFHeader h : doc.getHeaderList()) for (XWPFParagraph p : h.getParagraphs()) paras.add(p.getText());
        int idx = 0;
        for (IBodyElement e : doc.getBodyElements()) {
            if (e instanceof XWPFParagraph p) {
                idx++;
                if ((from == null || idx >= from) && (to == null || idx <= to)) paras.add(p.getText());
            } else if (includeTables && e instanceof XWPFTable t && from == null && to == null)
                for (XWPFTableRow r : t.getRows()) for (XWPFTableCell c : r.getTableCells()) paras.add(c.getText());
        }
        if (includeHf) for (XWPFFooter f : doc.getFooterList()) for (XWPFParagraph p : f.getParagraphs()) paras.add(p.getText());
        StringJoiner j = new StringJoiner("\n");
        for (Object o : paras) j.add((String) o);
        return Map.of("text", j.toString(), "paragraphs", paras);
    }

    /** One occurrence of a search text: the paragraph and the character range inside its runs' text. */
    private record Hit(XWPFParagraph p, int start, int end) { }

    private static java.util.regex.Pattern searchPattern(String find, boolean matchCase, boolean wholeWord) {
        String q = java.util.regex.Pattern.quote(find);
        if (wholeWord) q = "(?<![\\p{L}\\p{N}_])" + q + "(?![\\p{L}\\p{N}_])";
        return java.util.regex.Pattern.compile(q, matchCase ? 0 : java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);
    }

    /** Occurrences in document order, narrowed to all, the first or the last. */
    private static List<Hit> hits(XWPFDocument d, java.util.regex.Pattern pat, String occurrence) {
        List<Hit> out = new ArrayList<>();
        for (XWPFParagraph p : allParagraphs(d)) {
            String all = p.getRuns().stream().map(WordPackage::runText).reduce("", String::concat);
            var m = pat.matcher(all);
            while (m.find()) if (m.end() > m.start()) out.add(new Hit(p, m.start(), m.end()));
        }
        return pickOccurrence(out, occurrence, "WORD");
    }

    static <T> List<T> pickOccurrence(List<T> all, String occurrence, String prefix) {
        String o = occurrence == null ? "all" : occurrence.toLowerCase(Locale.ROOT);
        return switch (o) {
            case "all" -> all;
            case "first" -> all.isEmpty() ? all : List.of(all.get(0));
            case "last" -> all.isEmpty() ? all : List.of(all.get(all.size() - 1));
            default -> throw new ActionException(prefix + ".BAD_OCCURRENCE", "Occurrence must be all, first or last, not '" + occurrence + "'", "Use all, first or last.");
        };
    }

    private static final String D_MATCH_CASE = "Match upper/lower case exactly.";
    private static final String D_WHOLE_WORD = "Only match the text as a whole word, not inside a longer word.";
    private static final String D_OCCURRENCE = "Which occurrences to change, in document order: all, the first or the last.";

    public Map<String, Object> replaceText(ActionContext ctx, String find, String with, String session) { return replaceText(ctx, find, with, session, true, false, "all"); }

    @Action(id = "replace_text", name = "Replace text", category = "Text",
            description = "Replace text everywhere (body, tables, headers, footers), e.g. fill {{customer}} placeholders in a template; can ignore case, match whole words or change only the first or last occurrence.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of replacements.")
    public Map<String, Object> replaceText(ActionContext ctx,
            @Input(name = "find", type = OoaType.STRING, example = "{{customer}}", description = "Text to find.") String find,
            @Input(name = "replace_with", type = OoaType.STRING, required = false, defaultValue = "\"\"", description = "Replacement text.") String with,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"word\"", description = "Session name.") String session,
            @Input(name = "match_case", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = D_MATCH_CASE) boolean matchCase,
            @Input(name = "whole_word", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = D_WHOLE_WORD) boolean wholeWord,
            @Input(name = "occurrence", type = OoaType.STRING, required = false, defaultValue = "\"all\"", enumValues = {"all", "first", "last"}, description = D_OCCURRENCE) String occurrence) {
        if (find.isEmpty()) throw new ActionException("WORD.EMPTY_FIND", "Text to find is empty");
        String rep = with == null ? "" : with;
        if (!matchCase || wholeWord || !"all".equalsIgnoreCase(occurrence == null ? "all" : occurrence)) {
            List<Hit> hs = hits(s(ctx, session).doc, searchPattern(find, matchCase, wholeWord), occurrence);
            for (int i = hs.size() - 1; i >= 0; i--) {  // back to front keeps earlier offsets valid
                Hit h = hs.get(i);
                List<XWPFRun> runs = isolate(h.p(), h.start(), h.end());
                if (runs.isEmpty()) continue;
                setRunText(runs.get(0), rep);
                for (int k = 1; k < runs.size(); k++) setRunText(runs.get(k), "");
            }
            return Map.of("count", BigDecimal.valueOf(hs.size()));
        }
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

    public Map<String, Object> addTable(ActionContext ctx, Map<String, Object> table, String session) { return addTable(ctx, table, session, true, null, null, null); }

    @Action(id = "add_table", name = "Insert table", category = "Tables",
            description = "Insert a table built from a Data Table (header row first) at the end, after a marker text or at a bookmark, optionally with a table style.")
    public Map<String, Object> addTable(ActionContext ctx,
            @Input(name = "table", type = OoaType.TABLE, description = "Data to insert.") Map<String, Object> table,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"word\"", description = "Session name.") String session,
            @Input(name = "include_header", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Put the column names in a bold first row.") boolean includeHeader,
            @Input(name = "after_text", type = OoaType.STRING, required = false, description = "Insert after the paragraph containing this text; at the end when empty.") String afterText,
            @Input(name = "bookmark", type = OoaType.STRING, required = false, description = "Insert after the paragraph holding this bookmark; used when after_text is empty.") String bookmark,
            @Input(name = "style", type = OoaType.STRING, required = false, example = "TableGrid", description = "Table style id defined in the document, e.g. TableGrid.") String style) {
        List<?> cols = (List<?>) table.get("columns");
        List<?> rows = (List<?>) table.get("rows");
        if (cols.isEmpty()) throw new ActionException("WORD.EMPTY_TABLE", "The table has no columns");
        XWPFDocument d = s(ctx, session).doc;
        int nRows = rows.size() + (includeHeader ? 1 : 0);
        if (nRows == 0) throw new ActionException("WORD.EMPTY_TABLE", "The table has no rows and include_header is false", "Pass rows or include the header.");
        XWPFTable t;
        XWPFParagraph anchor = afterText != null && !afterText.isEmpty() ? findParagraph(d, afterText)
                : bookmark != null && !bookmark.isEmpty() ? bookmarkParagraph(d, bookmark) : null;
        if (anchor == null) t = d.createTable(nRows, cols.size());
        else {
            XWPFParagraph after = paragraphAfter(anchor);
            org.apache.xmlbeans.XmlCursor c = after.getCTP().newCursor();
            try {
                t = after.getBody() instanceof XWPFTableCell cell ? cell.insertNewTbl(c) : d.insertNewTbl(c);
            } finally { c.close(); }
            if (t == null) throw new ActionException("WORD.INSERT_FAILED", "Cannot insert a table at that position", "Leave after_text and bookmark empty to add it at the end.");
            for (int r = 0; r < nRows; r++) { XWPFTableRow tr = r == 0 ? t.getRow(0) : t.createRow(); while (tr.getTableCells().size() < cols.size()) tr.addNewTableCell(); }
        }
        if (style != null && !style.isBlank()) t.setStyleID(style);
        int r0 = 0;
        if (includeHeader) {
            for (int c = 0; c < cols.size(); c++) {
                XWPFTableCell cell = t.getRow(0).getCell(c);
                while (!cell.getParagraphs().isEmpty()) cell.removeParagraph(0);
                XWPFRun run = cell.addParagraph().createRun();
                run.setBold(true);
                run.setText(Values.text(cols.get(c)));
            }
            r0 = 1;
        }
        for (int r = 0; r < rows.size(); r++) {
            List<?> row = (List<?>) rows.get(r);
            for (int c = 0; c < cols.size(); c++) t.getRow(r + r0).getCell(c).setText(c < row.size() && row.get(c) != null ? Values.text(Values.toJsonable(row.get(c))) : "");
        }
        return Map.of();
    }

    private static XWPFParagraph bookmarkParagraph(XWPFDocument d, String name) {
        for (XWPFParagraph p : allParagraphs(d)) for (var bm : p.getCTP().getBookmarkStartList()) if (name.equals(bm.getName())) return p;
        throw new ActionException("WORD.NO_BOOKMARK", "Bookmark '" + name + "' not found", "Check the bookmark name in Word (Insert > Bookmark).");
    }

    public Map<String, Object> readTables(ActionContext ctx, String session) { return readTables(ctx, session, null, true); }

    @Action(id = "read_tables", name = "Read tables", category = "Tables", idempotent = true,
            description = "Read every table in the document, or one table by position; the first row of each can become its column names.")
    @Output(name = "tables", type = OoaType.LIST, description = "List of tables.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of tables in the document.")
    @Output(name = "table", type = OoaType.TABLE, description = "The table chosen by index; the first table when index is empty, or empty when there is none.")
    public Map<String, Object> readTables(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"word\"", description = "Session name.") String session,
            @Input(name = "index", type = OoaType.NUMBER, required = false, description = "1-based position of the one table to read; all tables when empty.") Integer index,
            @Input(name = "header", type = OoaType.BOOLEAN, required = false, defaultValue = "true",
                    description = "The first row holds the column names; false names the columns Column1, Column2 and keeps the first row as data.") boolean header) {
        List<XWPFTable> all = s(ctx, session).doc.getTables();
        if (index != null && (index < 1 || index > all.size()))
            throw new ActionException("WORD.NO_TABLE", "There is no table number " + index + " (the document has " + all.size() + ")", all.isEmpty() ? "The document has no tables." : "Use a number from 1 to " + all.size() + ".");
        List<Object> out = new ArrayList<>();
        for (XWPFTable t : index == null ? all : List.of(all.get(index - 1))) {
            List<String> cols = new ArrayList<>();
            List<Object> rows = new ArrayList<>();
            List<XWPFTableRow> trs = t.getRows();
            int width = 0;
            for (XWPFTableRow tr : trs) width = Math.max(width, tr.getTableCells().size());
            for (int r = 0; r < trs.size(); r++) {
                List<Object> vals = new ArrayList<>();
                for (XWPFTableCell c : trs.get(r).getTableCells()) vals.add(c.getText());
                if (r == 0 && header) for (Object v : vals) cols.add(String.valueOf(v)); else rows.add(vals);
            }
            if (!header) for (int c = 0; c < width; c++) cols.add("Column" + (c + 1));
            out.add(Values.table(cols, rows));
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("tables", out);
        res.put("count", BigDecimal.valueOf(all.size()));
        res.put("table", out.isEmpty() ? Values.table(List.of(), List.of()) : out.get(0));
        return res;
    }

    public Map<String, Object> save(ActionContext ctx, String path, String session) { return save(ctx, path, session, null); }

    @Action(id = "save", name = "Save", category = "Document", capabilities = "filesystem",
            description = "Save the document, optionally to a new path; password-protected documents stay protected.")
    public Map<String, Object> save(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, required = false, description = "Save-as path; the opened file when empty.") String path,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"word\"", description = "Session name.") String session,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false,
                    description = "Encrypt the saved file with this password; the password the document was opened with when empty.") Object password) {
        DocSession d = s(ctx, session);
        Path target = path == null || path.isBlank() ? d.path : ctx.resolvePath(path);
        refuseReadOnly(d, target);
        String pw = OfficeCrypto.secret(password);
        writeTo(d, target, pw != null ? pw : d.password);
        return Map.of();
    }

    public Map<String, Object> close(ActionContext ctx, String session) { return close(ctx, session, false); }

    @Action(id = "close", name = "Close", category = "Document", capabilities = "filesystem", description = "Close the document, saving the changes first when asked.")
    public Map<String, Object> close(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"word\"", description = "Session name.") String session,
            @Input(name = "save", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Save the changes to the opened file before closing.") boolean save) {
        if (save && ctx.sessions().get(session) instanceof DocSession d) { refuseReadOnly(d, d.path); writeTo(d, d.path, d.password); }
        Object o = ctx.sessions().remove(session);
        if (o instanceof DocSession d) try { d.close(); } catch (IOException ignored) { }
        return Map.of();
    }

    // ------------------------------------------------------------------------------------------------
    // Extended actions
    // ------------------------------------------------------------------------------------------------

    public Map<String, Object> createDocument(ActionContext ctx, String path, boolean overwrite, String session) { return createDocument(ctx, path, overwrite, session, null, null); }

    @Action(id = "create_document", name = "Create document", category = "Document", capabilities = "filesystem",
            description = "Create a new .docx file on disk, empty or as a copy of a template document, optionally password-protected, and open it as a named session.")
    public Map<String, Object> createDocument(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "Path of the new document.") String path,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Replace a file that already exists at the path.") boolean overwrite,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"word\"", description = "Session name.") String session,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, description = "Encrypt the new file with this password; later saves keep it.") Object password,
            @Input(name = "template", type = OoaType.FILE, required = false, description = "Existing .docx whose content and styles the new document starts from; empty document when empty.") String template) {
        Path p = ctx.resolvePath(path);
        if (Files.exists(p) && !overwrite) throw new ActionException("WORD.EXISTS", "A file already exists at " + p, "Set overwrite to true or use open to edit it.");
        XWPFDocument doc;
        if (template != null && !template.isBlank()) {
            Path t = ctx.resolvePath(template);
            if (!Files.isRegularFile(t)) throw new ActionException("WORD.NOT_FOUND", "Template not found: " + t, "Fix the template path.");
            try (InputStream in = Files.newInputStream(t)) { doc = new XWPFDocument(in); }
            catch (IOException | RuntimeException e) { throw new ActionException("WORD.OPEN_FAILED", "Cannot open template " + t + ": " + e.getMessage(), "Use a .docx template.", true); }
        } else {
            doc = new XWPFDocument();
            doc.createParagraph();
        }
        DocSession d = new DocSession(p, doc);
        d.password = OfficeCrypto.secret(password);
        Object old = ctx.sessions().put(session, d);
        if (old instanceof AutoCloseable c) try { c.close(); } catch (Exception ignored) { }
        writeTo(d, p, d.password);
        return Map.of();
    }

    private static void writeTo(DocSession d, Path target) { writeTo(d, target, null); }

    /** Writes the document atomically, encrypted when a password is given. */
    private static void writeTo(DocSession d, Path target, String password) {
        try { OfficeCrypto.write(target, OfficeCrypto.bytes(d.doc::write), password); }
        catch (IOException e) { throw new ActionException("WORD.SAVE_FAILED", "Cannot save " + target + ": " + e.getMessage(), "Close the file if it is open in Word.", true); }
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

    public Map<String, Object> insertText(ActionContext ctx, String text, String position, String anchor, boolean newParagraph, String session) {
        return insertText(ctx, text, position, anchor, newParagraph, session, null, null, null, null, null, null);
    }

    /** Character formatting applied to runs; null fields are left unchanged. */
    record RunFormat(Boolean bold, Boolean italic, Boolean underline, Double size, String colour, String font, String highlight, Boolean strike) {
        void apply(XWPFRun r) {
            if (bold != null) r.setBold(bold);
            if (italic != null) r.setItalic(italic);
            if (underline != null) r.setUnderline(underline ? UnderlinePatterns.SINGLE : UnderlinePatterns.NONE);
            if (size != null) r.setFontSize(size);
            if (colour != null) r.setColor(colour);
            if (font != null && !font.isBlank()) r.setFontFamily(font);
            if (highlight != null && !highlight.isBlank()) r.setTextHighlightColor(highlight);
            if (strike != null) r.setStrikeThrough(strike);
        }
    }

    private static void writeStyled(RunFormat f, XWPFRun r, String text) { f.apply(r); writeLines(r, text); }

    /** Validated hex colour without '#', upper case; null when empty. */
    static String hexColour(String colour) {
        String hex = colour == null || colour.isBlank() ? null : colour.trim().replace("#", "").toUpperCase(Locale.ROOT);
        if (hex != null && !hex.matches("[0-9A-F]{6}")) throw new ActionException("WORD.BAD_COLOR", "'" + colour + "' is not a colour", "Use a hex colour such as #C00000.");
        return hex;
    }

    @Action(id = "insert_text", name = "Insert text", category = "Text",
            description = "Insert text at the start or end of the document, or just before or after a marker text or a bookmark, optionally with its own font formatting.")
    public Map<String, Object> insertText(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Text to insert; line breaks are kept.") String text,
            @Input(name = "position", type = OoaType.STRING, required = false, defaultValue = "\"end\"",
                    enumValues = {"end", "start", "after_text", "before_text", "bookmark"}, description = "Where to insert: end, start of the document, after_text, before_text or bookmark.") String position,
            @Input(name = "anchor", type = OoaType.STRING, required = false, description = "Marker text or bookmark name the position refers to.") String anchor,
            @Input(name = "new_paragraph", type = OoaType.BOOLEAN, required = false, defaultValue = "true",
                    description = "Put the text in its own paragraph; false adds it inside the existing paragraph.") boolean newParagraph,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"word\"", description = "Session name.") String session,
            @Input(name = "bold", type = OoaType.BOOLEAN, required = false, description = "Bold on or off for the inserted text; unchanged when empty.") Boolean bold,
            @Input(name = "italic", type = OoaType.BOOLEAN, required = false, description = "Italic on or off for the inserted text.") Boolean italic,
            @Input(name = "underline", type = OoaType.BOOLEAN, required = false, description = "Single underline on or off for the inserted text.") Boolean underline,
            @Input(name = "size", type = OoaType.NUMBER, required = false, description = "Font size in points for the inserted text.") Double size,
            @Input(name = "color", type = OoaType.STRING, required = false, example = "#C00000", description = "Hex colour for the inserted text.") String colour,
            @Input(name = "font", type = OoaType.STRING, required = false, description = "Font name for the inserted text, e.g. Calibri.") String font) {
        XWPFDocument d = s(ctx, session).doc;
        RunFormat fmt = new RunFormat(bold, italic, underline, size, hexColour(colour), font, null, null);
        String pos = position == null ? "end" : position.toLowerCase(Locale.ROOT);
        if (!pos.equals("end") && !pos.equals("start") && (anchor == null || anchor.isEmpty()))
            throw new ActionException("WORD.NO_ANCHOR", "Position '" + pos + "' needs an anchor", "Set anchor to the marker text or bookmark name.");
        switch (pos) {
            case "end" -> {
                List<IBodyElement> els = d.getBodyElements();
                XWPFParagraph last = !els.isEmpty() && els.get(els.size() - 1) instanceof XWPFParagraph lp ? lp : null;
                XWPFParagraph p = !newParagraph && last != null ? last : d.createParagraph();
                writeStyled(fmt, p.createRun(), text);
            }
            case "start" -> {
                List<XWPFParagraph> ps = d.getParagraphs();
                XWPFParagraph first = ps.isEmpty() ? null : ps.get(0);
                if (first == null) writeStyled(fmt, d.createParagraph().createRun(), text);
                else if (!newParagraph) writeStyled(fmt, first.insertNewRun(0), text);
                else {
                    org.apache.xmlbeans.XmlCursor c = d.getBodyElements().get(0) instanceof XWPFTable tb ? tb.getCTTbl().newCursor() : first.getCTP().newCursor();
                    try { writeStyled(fmt, d.insertNewParagraph(c).createRun(), text); } finally { c.close(); }
                }
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
                    writeStyled(fmt, np.createRun(), text);
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
                    writeStyled(fmt, r, text);
                }
            }
            case "bookmark" -> {
                for (XWPFParagraph p : allParagraphs(d)) {
                    for (var bm : p.getCTP().getBookmarkStartList()) {
                        if (!anchor.equals(bm.getName())) continue;
                        if (newParagraph) { writeStyled(fmt, paragraphAfter(p).createRun(), text); return Map.of(); }
                        int runsBefore = 0;  // runs that come before the bookmark start inside the paragraph
                        org.apache.xmlbeans.XmlCursor c = p.getCTP().newCursor();
                        try {
                            if (c.toFirstChild()) do {
                                if (c.getObject() == bm) break;
                                if (c.getObject() instanceof org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR) runsBefore++;
                            } while (c.toNextSibling());
                        } finally { c.close(); }
                        writeStyled(fmt, p.insertNewRun(runsBefore), text);
                        return Map.of();
                    }
                }
                throw new ActionException("WORD.NO_BOOKMARK", "Bookmark '" + anchor + "' not found", "Check the bookmark name in Word (Insert > Bookmark).");
            }
            default -> throw new ActionException("WORD.BAD_POSITION", "Unknown position '" + position + "'", "Use end, start, after_text, before_text or bookmark.");
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
    static double[] sizePoints(byte[] bytes, Double widthPt) { return sizePoints(bytes, widthPt, null); }

    /** Image size in points; one of width or height keeps the aspect ratio, both are used as given. */
    static double[] sizePoints(byte[] bytes, Double widthPt, Double heightPt) {
        double w = 200, h = 150;
        try {
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
            if (img != null) { w = img.getWidth() * 0.75; h = img.getHeight() * 0.75; }
        } catch (IOException ignored) { }
        boolean hasW = widthPt != null && widthPt > 0, hasH = heightPt != null && heightPt > 0;
        if (hasW && hasH) return new double[]{widthPt, heightPt};
        if (hasW) { h = h * widthPt / w; w = widthPt; }
        else if (hasH) { w = w * heightPt / h; h = heightPt; }
        return new double[]{w, h};
    }

    public Map<String, Object> insertPicture(ActionContext ctx, String image, Double width, String afterText, String altText, String session) {
        return insertPicture(ctx, image, width, afterText, altText, session, null, "end", null, null);
    }

    @Action(id = "insert_picture", name = "Insert picture", category = "Pictures", capabilities = "filesystem",
            description = "Insert an image file at the start or end of the document, after a marker text or at a bookmark, optionally sized and aligned.")
    public Map<String, Object> insertPicture(ActionContext ctx,
            @Input(name = "image", type = OoaType.FILE, description = "Image file (PNG, JPEG, GIF, BMP, TIFF, EMF or WMF).") String image,
            @Input(name = "width", type = OoaType.NUMBER, required = false, description = "Width in points (72 per inch); the height keeps the aspect ratio. Original size when empty.") Double width,
            @Input(name = "after_text", type = OoaType.STRING, required = false, description = "Put the picture in a new paragraph after the paragraph with this text; at the end when empty.") String afterText,
            @Input(name = "alt_text", type = OoaType.STRING, required = false, description = "Alternative text, also used later by find_replace_picture.") String altText,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"word\"", description = "Session name.") String session,
            @Input(name = "height", type = OoaType.NUMBER, required = false,
                    description = "Height in points; with width both are used as given, alone the width keeps the aspect ratio.") Double height,
            @Input(name = "position", type = OoaType.STRING, required = false, defaultValue = "\"end\"", enumValues = {"end", "start"},
                    description = "Where the picture goes when after_text and bookmark are empty: end or start of the document.") String position,
            @Input(name = "bookmark", type = OoaType.STRING, required = false, description = "Put the picture in a new paragraph after the paragraph holding this bookmark.") String bookmark,
            @Input(name = "alignment", type = OoaType.STRING, required = false, enumValues = {"left", "center", "right"}, description = "Horizontal alignment of the picture's paragraph; unchanged when empty.") String alignment) {
        XWPFDocument d = s(ctx, session).doc;
        Path img = ctx.resolvePath(image);
        int type = pictureType(img, "WORD");
        byte[] bytes = readImage(img, "WORD");
        double[] size = sizePoints(bytes, width, height);
        XWPFParagraph p;
        if (afterText != null && !afterText.isEmpty()) p = paragraphAfter(findParagraph(d, afterText));
        else if (bookmark != null && !bookmark.isEmpty()) p = paragraphAfter(bookmarkParagraph(d, bookmark));
        else if ("start".equalsIgnoreCase(position) && !d.getBodyElements().isEmpty()) {
            IBodyElement first = d.getBodyElements().get(0);
            org.apache.xmlbeans.XmlCursor c = first instanceof XWPFTable tb ? tb.getCTTbl().newCursor() : ((XWPFParagraph) first).getCTP().newCursor();
            try { p = d.insertNewParagraph(c); } finally { c.close(); }
        } else p = d.createParagraph();
        if (alignment != null && !alignment.isBlank()) p.setAlignment(switch (alignment.toLowerCase(Locale.ROOT)) {
            case "center" -> ParagraphAlignment.CENTER;
            case "right" -> ParagraphAlignment.RIGHT;
            case "left" -> ParagraphAlignment.LEFT;
            default -> throw new ActionException("WORD.BAD_ALIGNMENT", "Alignment must be left, center or right", "Use left, center or right.");
        });
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

    private record PicRef(XWPFRun run, org.openxmlformats.schemas.drawingml.x2006.picture.CTPicture pic, String alt,
                          org.openxmlformats.schemas.drawingml.x2006.main.CTNonVisualDrawingProps docPr,
                          org.openxmlformats.schemas.drawingml.x2006.main.CTPositiveSize2D extent) { }

    private static final String PIC_PATH = "declare namespace pic='http://schemas.openxmlformats.org/drawingml/2006/picture' .//pic:pic";

    /** Pictures in document order, read from the live drawing XML (POI's cached picture objects can be detached copies). */
    private static List<PicRef> pictures(XWPFDocument d) {
        List<PicRef> out = new ArrayList<>();
        for (XWPFParagraph p : allParagraphs(d)) for (XWPFRun r : p.getRuns()) for (var dr : r.getCTR().getDrawingList()) {
            List<Object[]> frames = new ArrayList<>();
            for (var in : dr.getInlineList()) frames.add(new Object[]{in.getDocPr(), in.getGraphic(), in.getExtent()});
            for (var an : dr.getAnchorList()) frames.add(new Object[]{an.getDocPr(), an.getGraphic(), an.getExtent()});
            for (Object[] f : frames) {
                var docPr = (org.openxmlformats.schemas.drawingml.x2006.main.CTNonVisualDrawingProps) f[0];
                var graphic = (org.openxmlformats.schemas.drawingml.x2006.main.CTGraphicalObject) f[1];
                if (graphic == null || graphic.getGraphicData() == null) continue;
                for (org.apache.xmlbeans.XmlObject x : graphic.getGraphicData().selectPath(PIC_PATH)) {
                    if (!(x instanceof org.openxmlformats.schemas.drawingml.x2006.picture.CTPicture pic)) continue;
                    String alt = docPr == null ? null : docPr.getDescr();
                    if ((alt == null || alt.isEmpty()) && pic.getNvPicPr() != null) alt = pic.getNvPicPr().getCNvPr().getDescr();
                    out.add(new PicRef(r, pic, alt == null ? "" : alt, docPr, (org.openxmlformats.schemas.drawingml.x2006.main.CTPositiveSize2D) f[2]));
                }
            }
        }
        return out;
    }

    public Map<String, Object> findReplacePicture(ActionContext ctx, String image, String altText, Integer index, String session) {
        return findReplacePicture(ctx, image, altText, index, session, "all", null, null, null);
    }

    @Action(id = "find_replace_picture", name = "Find and replace picture", category = "Pictures", capabilities = "filesystem",
            description = "Swap an existing picture, found by its alternative text or its position in the document, for a new image file, optionally resizing it and changing its alternative text.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of pictures replaced.")
    public Map<String, Object> findReplacePicture(ActionContext ctx,
            @Input(name = "image", type = OoaType.FILE, description = "New image file.") String image,
            @Input(name = "alt_text", type = OoaType.STRING, required = false, description = "Replace every picture whose alternative text equals this.") String altText,
            @Input(name = "index", type = OoaType.NUMBER, required = false, description = "1-based position of the picture in the document; used when alt_text is empty.") Integer index,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"word\"", description = "Session name.") String session,
            @Input(name = "occurrence", type = OoaType.STRING, required = false, defaultValue = "\"all\"", enumValues = {"all", "first", "last"},
                    description = "With alt_text: replace all matching pictures, or only the first or last one in document order.") String occurrence,
            @Input(name = "new_alt_text", type = OoaType.STRING, required = false, description = "New alternative text for the replaced pictures; kept when empty.") String newAlt,
            @Input(name = "width", type = OoaType.NUMBER, required = false, description = "New width in points; kept when empty (with only height, the width follows the new image's ratio).") Double width,
            @Input(name = "height", type = OoaType.NUMBER, required = false, description = "New height in points; kept when empty (with only width, the height follows the new image's ratio).") Double height) {
        XWPFDocument d = s(ctx, session).doc;
        Path img = ctx.resolvePath(image);
        int type = pictureType(img, "WORD");
        byte[] bytes = readImage(img, "WORD");
        List<PicRef> all = pictures(d);
        List<PicRef> targets = new ArrayList<>();
        if (altText != null && !altText.isEmpty()) { for (PicRef p : all) if (p.alt().equals(altText)) targets.add(p); targets = new ArrayList<>(pickOccurrence(targets, occurrence, "WORD")); }
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
            if (newAlt != null && !newAlt.isEmpty()) {
                if (t.docPr() != null) t.docPr().setDescr(newAlt);
                if (t.pic().getNvPicPr() != null) t.pic().getNvPicPr().getCNvPr().setDescr(newAlt);
            }
            if ((width != null && width > 0) || (height != null && height > 0)) {
                double[] size = sizePoints(bytes, width, height);
                long cx = org.apache.poi.util.Units.toEMU(size[0]), cy = org.apache.poi.util.Units.toEMU(size[1]);
                if (t.extent() != null) { t.extent().setCx(cx); t.extent().setCy(cy); }
                var sp = t.pic().getSpPr();
                if (sp != null && sp.getXfrm() != null && sp.getXfrm().getExt() != null) { sp.getXfrm().getExt().setCx(cx); sp.getXfrm().getExt().setCy(cy); }
            }
        }
        return Map.of("count", BigDecimal.valueOf(targets.size()));
    }

    public Map<String, Object> formatText(ActionContext ctx, String text, Boolean bold, Boolean italic, Boolean underline, Double size, String colour, String font, String session) {
        return formatText(ctx, text, bold, italic, underline, size, colour, font, session, true, false, "all", null, null);
    }

    @Action(id = "format_text", name = "Format text", category = "Text",
            description = "Apply bold, italic, underline, strikethrough, highlight, font, size or colour to occurrences of a text; inputs left empty are not changed.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of occurrences formatted.")
    public Map<String, Object> formatText(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Text to format wherever it appears.") String text,
            @Input(name = "bold", type = OoaType.BOOLEAN, required = false, description = "Bold on or off.") Boolean bold,
            @Input(name = "italic", type = OoaType.BOOLEAN, required = false, description = "Italic on or off.") Boolean italic,
            @Input(name = "underline", type = OoaType.BOOLEAN, required = false, description = "Single underline on or off.") Boolean underline,
            @Input(name = "size", type = OoaType.NUMBER, required = false, description = "Font size in points.") Double size,
            @Input(name = "color", type = OoaType.STRING, required = false, example = "#C00000", description = "Hex text colour.") String colour,
            @Input(name = "font", type = OoaType.STRING, required = false, description = "Font name, e.g. Calibri.") String font,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"word\"", description = "Session name.") String session,
            @Input(name = "match_case", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = D_MATCH_CASE) boolean matchCase,
            @Input(name = "whole_word", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = D_WHOLE_WORD) boolean wholeWord,
            @Input(name = "occurrence", type = OoaType.STRING, required = false, defaultValue = "\"all\"", enumValues = {"all", "first", "last"}, description = D_OCCURRENCE) String occurrence,
            @Input(name = "highlight", type = OoaType.STRING, required = false,
                    enumValues = {"yellow", "green", "cyan", "magenta", "blue", "red", "darkBlue", "darkCyan", "darkGreen", "darkMagenta", "darkRed", "darkYellow", "darkGray", "lightGray", "black", "white", "none"},
                    description = "Highlighter colour behind the text, or none to remove it.") String highlight,
            @Input(name = "strikethrough", type = OoaType.BOOLEAN, required = false, description = "Strikethrough on or off.") Boolean strike) {
        if (text == null || text.isEmpty()) throw new ActionException("WORD.EMPTY_FIND", "Text to format is empty", "Give the text to format.");
        RunFormat f = new RunFormat(bold, italic, underline, size, hexColour(colour), font, highlight, strike);
        List<Hit> hs = hits(s(ctx, session).doc, searchPattern(text, matchCase, wholeWord), occurrence);
        for (int i = hs.size() - 1; i >= 0; i--) {
            Hit h = hs.get(i);
            for (XWPFRun r : isolate(h.p(), h.start(), h.end())) f.apply(r);
        }
        return Map.of("count", BigDecimal.valueOf(hs.size()));
    }

    public Map<String, Object> saveAs(ActionContext ctx, String path, String format, String session) { return saveAs(ctx, path, format, session, true, null); }

    @Action(id = "save_as", name = "Save as", category = "Document", capabilities = "filesystem",
            description = "Save the document to a new .docx file (optionally password-protected) and continue the session on it, or export its text as a .txt file; PDF output is not available in this toolkit.")
    public Map<String, Object> saveAs(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "Target file path.") String path,
            @Input(name = "format", type = OoaType.STRING, required = false, defaultValue = "\"docx\"", enumValues = {"docx", "txt"},
                    description = "docx keeps the document; txt writes the plain text (body, tables and headers) and the session stays on the docx file.") String format,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"word\"", description = "Session name.") String session,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Replace a file that already exists at the target path; false stops with WORD.EXISTS.") boolean overwrite,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, description = "Encrypt the new .docx file with this password; later saves keep it.") Object password) {
        DocSession d = s(ctx, session);
        Path target = ctx.resolvePath(path);
        String fmt = format == null || format.isBlank() ? "docx" : format.toLowerCase(Locale.ROOT);
        if (fmt.equals("pdf") || target.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf"))
            throw new ActionException("WORD.PDF_UNSUPPORTED", "Saving as PDF needs a document converter, which this toolkit does not include",
                    "Save as .docx, then convert it with Word desktop automation or a PDF service.");
        if (!fmt.equals("docx") && !fmt.equals("txt")) throw new ActionException("WORD.BAD_FORMAT", "Unsupported format '" + format + "'", "Use docx or txt.");
        if (!overwrite && Files.exists(target)) throw new ActionException("WORD.EXISTS", "A file already exists at " + target, "Set overwrite to true or choose another path.");
        refuseReadOnly(d, target);
        if (fmt.equals("txt")) {
            try {
                if (target.getParent() != null) Files.createDirectories(target.getParent());
                @SuppressWarnings("resource")  // closing the extractor would close the session's document
                var ex = new org.apache.poi.xwpf.extractor.XWPFWordExtractor(d.doc);
                Files.writeString(target, ex.getText(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (IOException e) { throw new ActionException("WORD.SAVE_FAILED", "Cannot save " + target + ": " + e.getMessage(), "Check the folder is writable.", true); }
            return Map.of();
        }
        String pw = OfficeCrypto.secret(password);
        String keep = pw != null ? pw : d.password;
        writeTo(d, target, keep);
        d.path = target;
        d.password = keep;
        d.readOnly = false;
        return Map.of();
    }
}
