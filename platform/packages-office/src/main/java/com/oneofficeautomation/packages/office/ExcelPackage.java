package com.oneofficeautomation.packages.office;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.AreaReference;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;

/** Read and write .xlsx workbooks without Microsoft Office (Apache POI), the equivalent of AA's Excel basic package. */
@OoaPackage(id = "ooa.excel", name = "Excel (no Office needed)", version = "1.0.0",
        description = "Read and write .xlsx workbooks without Microsoft Office installed.")
public final class ExcelPackage {
    private static final String CELL = "^[A-Za-z]{1,3}[1-9][0-9]*$";

    /** Workbook session shared by later steps; closed automatically at the end of the run. */
    public static final class WorkbookSession implements AutoCloseable {
        final Path path;
        final Workbook wb;
        WorkbookSession(Path path, Workbook wb) { this.path = path; this.wb = wb; }
        @Override public void close() throws IOException { wb.close(); }
    }

    private static WorkbookSession session(ActionContext ctx, String name) { return ctx.session(name, WorkbookSession.class); }

    private static Sheet sheet(WorkbookSession s, String name) {
        if (name == null || name.isBlank()) return s.wb.getSheetAt(s.wb.getActiveSheetIndex());
        Sheet sh = s.wb.getSheet(name);
        if (sh == null) {
            List<String> names = new ArrayList<>();
            for (int i = 0; i < s.wb.getNumberOfSheets(); i++) names.add(s.wb.getSheetName(i));
            throw new ActionException("EXCEL.NO_SHEET", "Sheet '" + name + "' not found", "Sheets: " + String.join(", ", names) + ".");
        }
        return sh;
    }

    private static Cell cell(Sheet sh, String address, boolean create) {
        CellReference ref = new CellReference(address.toUpperCase(Locale.ROOT));
        Row row = sh.getRow(ref.getRow());
        if (row == null) { if (!create) return null; row = sh.createRow(ref.getRow()); }
        Cell c = row.getCell(ref.getCol());
        if (c == null && create) c = row.createCell(ref.getCol());
        return c;
    }

    static Object read(Cell c, FormulaEvaluator ev) {
        if (c == null) return null;
        CellType t = c.getCellType();
        if (t == CellType.FORMULA) {
            CellValue v = ev.evaluate(c);
            if (v == null) return null;
            return switch (v.getCellType()) {
                case NUMERIC -> DateUtil.isCellDateFormatted(c) ? c.getLocalDateTimeCellValue() : num(v.getNumberValue());
                case STRING -> v.getStringValue();
                case BOOLEAN -> v.getBooleanValue();
                case ERROR -> "#ERROR";
                default -> null;
            };
        }
        return switch (t) {
            case NUMERIC -> DateUtil.isCellDateFormatted(c) ? c.getLocalDateTimeCellValue() : num(c.getNumericCellValue());
            case STRING -> c.getStringCellValue();
            case BOOLEAN -> c.getBooleanCellValue();
            case ERROR -> "#ERROR";
            default -> null;
        };
    }

    private static BigDecimal num(double d) {
        BigDecimal b = new BigDecimal(Double.toString(d)).stripTrailingZeros();
        return b.scale() < 0 ? b.setScale(0) : b;
    }

    private static void write(WorkbookSession s, Cell c, Object v) {
        Object x = v instanceof SecureString ? null : v;
        if (x == null) c.setBlank();
        else if (x instanceof BigDecimal d) c.setCellValue(d.doubleValue());
        else if (x instanceof Number n) c.setCellValue(n.doubleValue());
        else if (x instanceof Boolean b) c.setCellValue(b);
        else if (x instanceof LocalDateTime t) {
            c.setCellValue(t);
            CellStyle st = s.wb.createCellStyle();
            st.setDataFormat(s.wb.getCreationHelper().createDataFormat().getFormat("yyyy-mm-dd hh:mm"));
            c.setCellStyle(st);
        } else c.setCellValue(Values.text(x));
    }

    @Action(id = "open", name = "Open workbook", category = "Workbook", capabilities = "filesystem",
            description = "Open an .xlsx workbook, or create a new one, as a named session.")
    public Map<String, Object> open(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "Workbook path.") String path,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"excel\"", description = "Session name used by later steps.") String session,
            @Input(name = "create", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Create the workbook when it does not exist.") boolean create) {
        Path p = ctx.resolvePath(path);
        Workbook wb;
        if (Files.exists(p)) {
            try (InputStream in = Files.newInputStream(p)) { wb = WorkbookFactory.create(in); }
            catch (IOException | RuntimeException e) {
                throw new ActionException("EXCEL.OPEN_FAILED", "Cannot open " + p + ": " + e.getMessage(), "Close the file in Excel and check it is a valid .xlsx.", true);
            }
        } else if (create) {
            wb = new XSSFWorkbook();
            wb.createSheet("Sheet1");
        } else throw new ActionException("EXCEL.NOT_FOUND", "Workbook not found: " + p, "Set create to true or fix the path.");
        Object old = ctx.sessions().put(session, new WorkbookSession(p, wb));
        if (old instanceof AutoCloseable c) try { c.close(); } catch (Exception ignored) { }
        return Map.of();
    }

    @Action(id = "get_cell", name = "Get cell", category = "Cells", idempotent = true, description = "Read one cell; formulas return their calculated value.")
    @Output(name = "value", description = "Cell value.")
    public Map<String, Object> getCell(ActionContext ctx,
            @Input(name = "cell", type = OoaType.STRING, pattern = CELL, example = "B2", description = "Cell address.") String address,
            @Input(name = "sheet", type = OoaType.STRING, required = false, description = "Sheet name; active sheet when empty.") String sheet,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"excel\"", description = "Workbook session.") String session) {
        WorkbookSession s = session(ctx, session);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("value", read(cell(sheet(s, sheet), address, false), s.wb.getCreationHelper().createFormulaEvaluator()));
        return out;
    }

    @Action(id = "set_cell", name = "Set cell", category = "Cells", description = "Write a value to one cell.")
    public Map<String, Object> setCell(ActionContext ctx,
            @Input(name = "cell", type = OoaType.STRING, pattern = CELL, example = "B2", description = "Cell address.") String address,
            @Input(name = "value", required = false, description = "Value to write; empty clears the cell.") Object value,
            @Input(name = "sheet", type = OoaType.STRING, required = false, description = "Sheet name; active sheet when empty.") String sheet,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"excel\"", description = "Workbook session.") String session) {
        WorkbookSession s = session(ctx, session);
        write(s, cell(sheet(s, sheet), address, true), value);
        return Map.of();
    }

    @Action(id = "set_formula", name = "Set formula", category = "Cells", description = "Put a formula in a cell, e.g. SUM(C2:C10).")
    public Map<String, Object> setFormula(ActionContext ctx,
            @Input(name = "cell", type = OoaType.STRING, pattern = CELL, example = "C11", description = "Cell address.") String address,
            @Input(name = "formula", type = OoaType.STRING, example = "SUM(C2:C10)", description = "Formula, with or without a leading =.") String formula,
            @Input(name = "sheet", type = OoaType.STRING, required = false, description = "Sheet name; active sheet when empty.") String sheet,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"excel\"", description = "Workbook session.") String session) {
        WorkbookSession s = session(ctx, session);
        try { cell(sheet(s, sheet), address, true).setCellFormula(formula.startsWith("=") ? formula.substring(1) : formula); }
        catch (RuntimeException e) { throw new ActionException("EXCEL.BAD_FORMULA", "Invalid formula '" + formula + "': " + e.getMessage(), "Check the formula syntax."); }
        return Map.of();
    }

    private static Map<String, Object> toTable(List<List<Object>> grid, boolean header) {
        grid.removeIf(r -> r.stream().allMatch(Objects::isNull));
        int width = grid.stream().mapToInt(List::size).max().orElse(0);
        List<String> cols = new ArrayList<>();
        if (header && !grid.isEmpty()) for (Object h : grid.remove(0)) cols.add(h == null ? "" : Values.text(h));
        for (int i = 0; i < cols.size(); i++) if (cols.get(i).isEmpty()) cols.set(i, "Column" + (i + 1));
        while (cols.size() < width) cols.add("Column" + (cols.size() + 1));
        List<Object> rows = new ArrayList<>();
        for (List<Object> r : grid) { List<Object> row = new ArrayList<>(r); while (row.size() < cols.size()) row.add(null); rows.add(row); }
        return Values.table(cols, rows);
    }

    @Action(id = "get_all_cells", name = "Get all cells", category = "Cells", idempotent = true,
            description = "Read the used area of a sheet as a table; the first row can be the column names.")
    @Output(name = "table", type = OoaType.TABLE, description = "Sheet content.")
    @Output(name = "row_count", type = OoaType.NUMBER, description = "Number of data rows.")
    public Map<String, Object> getAllCells(ActionContext ctx,
            @Input(name = "sheet", type = OoaType.STRING, required = false, description = "Sheet name; active sheet when empty.") String sheet,
            @Input(name = "header", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "First row holds column names.") boolean header,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"excel\"", description = "Workbook session.") String session) {
        WorkbookSession s = session(ctx, session);
        Sheet sh = sheet(s, sheet);
        FormulaEvaluator ev = s.wb.getCreationHelper().createFormulaEvaluator();
        List<List<Object>> grid = new ArrayList<>();
        for (int r = sh.getFirstRowNum(); r >= 0 && r <= sh.getLastRowNum(); r++) {
            Row row = sh.getRow(r);
            List<Object> vals = new ArrayList<>();
            if (row != null) for (int c = 0; c < Math.max(0, row.getLastCellNum()); c++) vals.add(read(row.getCell(c), ev));
            grid.add(vals);
        }
        Map<String, Object> t = toTable(grid, header);
        return Map.of("table", t, "row_count", BigDecimal.valueOf(((List<?>) t.get("rows")).size()));
    }

    @Action(id = "get_range", name = "Get range", category = "Cells", idempotent = true, description = "Read a rectangular range such as A1:D20 as a table.")
    @Output(name = "table", type = OoaType.TABLE, description = "Range content.")
    public Map<String, Object> getRange(ActionContext ctx,
            @Input(name = "range", type = OoaType.STRING, pattern = "^[A-Za-z]{1,3}[1-9][0-9]*:[A-Za-z]{1,3}[1-9][0-9]*$", example = "A1:D20", description = "Range address.") String range,
            @Input(name = "header", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "First row holds column names.") boolean header,
            @Input(name = "sheet", type = OoaType.STRING, required = false, description = "Sheet name; active sheet when empty.") String sheet,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"excel\"", description = "Workbook session.") String session) {
        WorkbookSession s = session(ctx, session);
        Sheet sh = sheet(s, sheet);
        FormulaEvaluator ev = s.wb.getCreationHelper().createFormulaEvaluator();
        AreaReference area = new AreaReference(range.toUpperCase(Locale.ROOT), s.wb.getSpreadsheetVersion());
        CellReference a = area.getFirstCell(), b = area.getLastCell();
        List<List<Object>> grid = new ArrayList<>();
        for (int r = a.getRow(); r <= b.getRow(); r++) {
            Row row = sh.getRow(r);
            List<Object> vals = new ArrayList<>();
            for (int c = a.getCol(); c <= b.getCol(); c++) vals.add(row == null ? null : read(row.getCell(c), ev));
            grid.add(vals);
        }
        return Map.of("table", toTable(grid, header));
    }

    @Action(id = "write_table", name = "Write from Data Table", category = "Cells", description = "Write a table (with column names) starting at a cell.")
    public Map<String, Object> writeTable(ActionContext ctx,
            @Input(name = "table", type = OoaType.TABLE, description = "Table to write.") Map<String, Object> table,
            @Input(name = "start_cell", type = OoaType.STRING, required = false, defaultValue = "\"A1\"", pattern = CELL, description = "Top-left cell.") String start,
            @Input(name = "include_header", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Write column names first.") boolean includeHeader,
            @Input(name = "sheet", type = OoaType.STRING, required = false, description = "Sheet name; active sheet when empty.") String sheet,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"excel\"", description = "Workbook session.") String session) {
        WorkbookSession s = session(ctx, session);
        Sheet sh = sheet(s, sheet);
        CellReference ref = new CellReference(start.toUpperCase(Locale.ROOT));
        List<List<Object>> lines = new ArrayList<>();
        if (includeHeader) lines.add(new ArrayList<>((List<?>) table.get("columns")));
        for (Object r : (List<?>) table.get("rows")) lines.add(new ArrayList<>((List<?>) r));
        for (int i = 0; i < lines.size(); i++) {
            Row row = sh.getRow(ref.getRow() + i);
            if (row == null) row = sh.createRow(ref.getRow() + i);
            for (int j = 0; j < lines.get(i).size(); j++) write(s, row.createCell(ref.getCol() + j), lines.get(i).get(j));
        }
        return Map.of();
    }

    @Action(id = "find", name = "Find", category = "Cells", idempotent = true, description = "Find cells whose text contains a value; returns their addresses.")
    @Output(name = "cells", type = OoaType.LIST, description = "Addresses such as B7.")
    public Map<String, Object> find(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Text to look for.") String text,
            @Input(name = "match_case", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Match upper/lower case exactly.") boolean matchCase,
            @Input(name = "sheet", type = OoaType.STRING, required = false, description = "Sheet name; active sheet when empty.") String sheet,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"excel\"", description = "Workbook session.") String session) {
        WorkbookSession s = session(ctx, session);
        FormulaEvaluator ev = s.wb.getCreationHelper().createFormulaEvaluator();
        String needle = matchCase ? text : text.toLowerCase(Locale.ROOT);
        List<Object> hits = new ArrayList<>();
        for (Row row : sheet(s, sheet)) for (Cell c : row) {
            Object v = read(c, ev);
            if (v == null) continue;
            String hay = matchCase ? Values.text(v) : Values.text(v).toLowerCase(Locale.ROOT);
            if (hay.contains(needle)) hits.add(new CellReference(c.getRowIndex(), c.getColumnIndex()).formatAsString(false));
        }
        return Map.of("cells", hits);
    }

    @Action(id = "get_sheet_names", name = "Get sheet names", category = "Sheets", idempotent = true, description = "List the worksheet names.")
    @Output(name = "names", type = OoaType.LIST, description = "Sheet names in order.")
    public Map<String, Object> getSheetNames(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"excel\"", description = "Workbook session.") String session) {
        WorkbookSession s = session(ctx, session);
        List<Object> names = new ArrayList<>();
        for (int i = 0; i < s.wb.getNumberOfSheets(); i++) names.add(s.wb.getSheetName(i));
        return Map.of("names", names);
    }

    @Action(id = "add_sheet", name = "Create worksheet", category = "Sheets", description = "Create a worksheet, or clear it first when replace is true.")
    public Map<String, Object> addSheet(ActionContext ctx,
            @Input(name = "name", type = OoaType.STRING, description = "Worksheet name.") String name,
            @Input(name = "replace", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Replace an existing sheet with this name.") boolean replace,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"excel\"", description = "Workbook session.") String session) {
        WorkbookSession s = session(ctx, session);
        int idx = s.wb.getSheetIndex(name);
        if (idx >= 0) {
            if (!replace) throw new ActionException("EXCEL.SHEET_EXISTS", "Sheet '" + name + "' already exists", "Set replace to true or choose another name.");
            s.wb.removeSheetAt(idx);
        }
        try { s.wb.createSheet(name); }
        catch (IllegalArgumentException e) { throw new ActionException("EXCEL.BAD_SHEET_NAME", e.getMessage(), "Sheet names are at most 31 characters and cannot contain : \\ / ? * [ ]."); }
        return Map.of();
    }

    @Action(id = "delete_sheet", name = "Delete worksheet", category = "Sheets", description = "Delete a worksheet.")
    public Map<String, Object> deleteSheet(ActionContext ctx,
            @Input(name = "name", type = OoaType.STRING, description = "Worksheet name.") String name,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"excel\"", description = "Workbook session.") String session) {
        WorkbookSession s = session(ctx, session);
        int idx = s.wb.getSheetIndex(sheet(s, name));
        if (s.wb.getNumberOfSheets() == 1) throw new ActionException("EXCEL.LAST_SHEET", "A workbook must keep at least one sheet", "Create another sheet first.");
        s.wb.removeSheetAt(idx);
        return Map.of();
    }

    @Action(id = "save", name = "Save", category = "Workbook", capabilities = "filesystem", description = "Save the workbook, optionally to a new path (Save as).")
    public Map<String, Object> save(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, required = false, description = "Save-as path; the opened file when empty.") String path,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"excel\"", description = "Workbook session.") String session) {
        WorkbookSession s = session(ctx, session);
        Path target = path == null ? s.path : ctx.resolvePath(path);
        try {
            if (target.getParent() != null) Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            try (OutputStream out = Files.newOutputStream(tmp)) { s.wb.write(out); }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new ActionException("EXCEL.SAVE_FAILED", "Cannot save " + target + ": " + e.getMessage(), "Close the file if it is open in Excel.", true);
        }
        return Map.of();
    }

    @Action(id = "close", name = "Close", category = "Workbook", description = "Close the workbook session without saving.")
    public Map<String, Object> close(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"excel\"", description = "Workbook session.") String session) {
        Object s = ctx.sessions().remove(session);
        if (s instanceof WorkbookSession w) try { w.close(); } catch (IOException ignored) { }
        return Map.of();
    }
}
