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
        final Path path; final XWPFDocument doc;
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
}
