package com.oneofficeautomation.packages.office;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.AreaReference;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.util.CellAddress;
import org.apache.poi.poifs.crypt.HashAlgorithm;
import org.apache.poi.xssf.model.ExternalLinksTable;
import org.apache.poi.xssf.usermodel.*;

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
        Path path;
        Workbook wb;
        /** Cell or range chosen by go_to_cell / select_range; empty until one of them runs. */
        String selection;
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

    // ------------------------------------------------------------------------------------------------
    // Helpers for the extended actions
    // ------------------------------------------------------------------------------------------------

    private static final String RANGE = "^[A-Za-z]{1,3}[1-9][0-9]*(:[A-Za-z]{1,3}[1-9][0-9]*)?$";

    private static XSSFWorkbook xlsx(WorkbookSession s, String what) {
        if (s.wb instanceof XSSFWorkbook x) return x;
        throw new ActionException("EXCEL.XLSX_ONLY", what + " is only supported for .xlsx workbooks", "Save the workbook as .xlsx first (save_as with format xlsx).");
    }

    private static FormulaEvaluator evaluator(WorkbookSession s) { return s.wb.getCreationHelper().createFormulaEvaluator(); }

    private static String addr(int row, int col) { return new CellReference(row, col).formatAsString(false); }

    /** Zero-based column index from letters ("C"), a 1-based number (3) or a numeric string ("3"). */
    static int columnIndex(Object column) {
        if (column == null || Values.text(column).isBlank())
            throw new ActionException("EXCEL.BAD_COLUMN", "No column given", "Give a column letter such as C or a number such as 3.");
        if (column instanceof Number n) return checkCol(n.intValue());
        String t = Values.text(column).trim();
        if (t.matches("\\d+")) return checkCol(Integer.parseInt(t));
        if (!t.matches("[A-Za-z]{1,3}"))
            throw new ActionException("EXCEL.BAD_COLUMN", "'" + t + "' is not a column", "Give a column letter such as C or a number such as 3.");
        return CellReference.convertColStringToIndex(t.toUpperCase(Locale.ROOT));
    }

    private static int checkCol(int oneBased) {
        if (oneBased < 1 || oneBased > 16384) throw new ActionException("EXCEL.BAD_COLUMN", "Column number " + oneBased + " is out of range", "Columns are numbered 1 to 16384.");
        return oneBased - 1;
    }

    private static int rowIndex(int oneBased) {
        if (oneBased < 1 || oneBased > 1048576) throw new ActionException("EXCEL.BAD_ROW", "Row number " + oneBased + " is out of range", "Rows are numbered from 1.");
        return oneBased - 1;
    }

    private static int count(int n) {
        if (n < 1) throw new ActionException("EXCEL.BAD_COUNT", "Count must be at least 1", "Set count to 1 or more.");
        return n;
    }

    private static AreaReference area(WorkbookSession s, String range) {
        String r = range.toUpperCase(Locale.ROOT).trim();
        if (!r.contains(":")) r = r + ":" + r;
        try { return new AreaReference(r, s.wb.getSpreadsheetVersion()); }
        catch (RuntimeException e) { throw new ActionException("EXCEL.BAD_RANGE", "'" + range + "' is not a range", "Use an address such as A1:D20."); }
    }

    private static int lastCol(Sheet sh) {
        int max = -1;
        for (Row r : sh) max = Math.max(max, r.getLastCellNum() - 1);
        return max;
    }

    private static boolean blank(Cell c) {
        if (c == null) return true;
        CellType t = c.getCellType();
        return t == CellType.BLANK || (t == CellType.STRING && c.getStringCellValue().isBlank());
    }

    private static boolean blankRow(Row r) {
        if (r == null) return true;
        for (Cell c : r) if (!blank(c)) return false;
        return true;
    }

    /** Copies content and style of src to dst (same workbook); clears dst when src is null. */
    private static void copyCell(Cell src, Row dstRow, int dstCol) {
        if (src == null) { Cell d = dstRow.getCell(dstCol); if (d != null) dstRow.removeCell(d); return; }
        Cell dst = dstRow.getCell(dstCol);
        if (dst == null) dst = dstRow.createCell(dstCol);
        dst.setCellStyle(src.getCellStyle());
        switch (src.getCellType()) {
            case STRING -> dst.setCellValue(src.getStringCellValue());
            case NUMERIC -> dst.setCellValue(src.getNumericCellValue());
            case BOOLEAN -> dst.setCellValue(src.getBooleanCellValue());
            case FORMULA -> dst.setCellFormula(src.getCellFormula());
            case ERROR -> dst.setCellErrorValue(src.getErrorCellValue());
            default -> dst.setBlank();
        }
    }

    private static Row row(Sheet sh, int r) { Row x = sh.getRow(r); return x == null ? sh.createRow(r) : x; }

    /** Copies one sheet's values, formulas, column widths and (when both workbooks are the same type) styles. */
    private static void copySheetInto(Sheet src, Sheet dst) {
        Workbook dwb = dst.getWorkbook();
        boolean sameWb = src.getWorkbook() == dwb;
        boolean sameKind = src.getWorkbook().getClass() == dwb.getClass();
        Map<Integer, CellStyle> styles = new HashMap<>();
        int maxCol = -1;
        for (Row sr : src) {
            Row dr = dst.createRow(sr.getRowNum());
            if (sr.getZeroHeight()) dr.setZeroHeight(true);
            if (sr.isFormatted() || sr.getHeight() != src.getDefaultRowHeight()) dr.setHeight(sr.getHeight());
            for (Cell sc : sr) {
                maxCol = Math.max(maxCol, sc.getColumnIndex());
                Cell dc = dr.createCell(sc.getColumnIndex());
                if (sameWb) dc.setCellStyle(sc.getCellStyle());
                else if (sameKind) {
                    CellStyle st = styles.computeIfAbsent((int) sc.getCellStyle().getIndex(), k -> {
                        CellStyle n = dwb.createCellStyle();
                        n.cloneStyleFrom(sc.getCellStyle());
                        return n;
                    });
                    dc.setCellStyle(st);
                } else if (sc.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(sc)) {
                    CellStyle st = styles.computeIfAbsent(-1, k -> {
                        CellStyle n = dwb.createCellStyle();
                        n.setDataFormat(dwb.getCreationHelper().createDataFormat().getFormat("yyyy-mm-dd hh:mm"));
                        return n;
                    });
                    dc.setCellStyle(st);
                }
                switch (sc.getCellType()) {
                    case STRING -> dc.setCellValue(sc.getStringCellValue());
                    case NUMERIC -> dc.setCellValue(sc.getNumericCellValue());
                    case BOOLEAN -> dc.setCellValue(sc.getBooleanCellValue());
                    case FORMULA -> {
                        try { dc.setCellFormula(sc.getCellFormula()); }
                        catch (RuntimeException e) { dc.setCellValue("=" + sc.getCellFormula()); }
                    }
                    case ERROR -> dc.setCellErrorValue(sc.getErrorCellValue());
                    default -> { }
                }
            }
        }
        for (int c = 0; c <= maxCol; c++) {
            dst.setColumnWidth(c, src.getColumnWidth(c));
            if (src.isColumnHidden(c)) dst.setColumnHidden(c, true);
        }
        for (int i = 0; i < src.getNumMergedRegions(); i++) dst.addMergedRegion(src.getMergedRegion(i).copy());
    }

    private static String uniqueSheetName(Workbook wb, String wanted) {
        String base = wanted.length() > 31 ? wanted.substring(0, 31) : wanted;
        if (wb.getSheetIndex(base) < 0) return base;
        for (int i = 2; ; i++) {
            String suffix = " (" + i + ")";
            String n = (base.length() + suffix.length() > 31 ? base.substring(0, 31 - suffix.length()) : base) + suffix;
            if (wb.getSheetIndex(n) < 0) return n;
        }
    }

    private static Workbook load(Path p) {
        if (!Files.exists(p)) throw new ActionException("EXCEL.NOT_FOUND", "Workbook not found: " + p, "Fix the path.");
        try (InputStream in = Files.newInputStream(p)) { return WorkbookFactory.create(in); }
        catch (IOException | RuntimeException e) {
            throw new ActionException("EXCEL.OPEN_FAILED", "Cannot open " + p + ": " + e.getMessage(), "Close the file in Excel and check it is a valid workbook.", true);
        }
    }

    private static Map<String, Object> out(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static BigDecimal n(long v) { return BigDecimal.valueOf(v); }

    // ------------------------------------------------------------------------------------------------
    // Workbook and sheet structure
    // ------------------------------------------------------------------------------------------------

    @Action(id = "append_workbook", name = "Append workbook", category = "Workbook", capabilities = "filesystem",
            description = "Copy every sheet of another workbook file to the end of this workbook; clashing names get a number suffix.")
    @Output(name = "sheets", type = OoaType.LIST, description = "Names the copied sheets received.")
    public Map<String, Object> appendWorkbook(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "Workbook file whose sheets are copied in.") String path,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"excel\"", description = "Workbook session.") String session) {
        WorkbookSession s = session(ctx, session);
        List<Object> added = new ArrayList<>();
        try (Workbook other = load(ctx.resolvePath(path))) {
            for (int i = 0; i < other.getNumberOfSheets(); i++) {
                Sheet src = other.getSheetAt(i);
                Sheet dst = s.wb.createSheet(uniqueSheetName(s.wb, src.getSheetName()));
                copySheetInto(src, dst);
                added.add(dst.getSheetName());
            }
        } catch (IOException ignored) { }
        return out("sheets", added);
    }

    @Action(id = "append_worksheet", name = "Append worksheet", category = "Sheets", capabilities = "filesystem",
            description = "Copy one sheet from another workbook file to the end of this workbook.")
    @Output(name = "name", type = OoaType.STRING, description = "Name the copied sheet received.")
    public Map<String, Object> appendWorksheet(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "Workbook file that holds the sheet.") String path,
            @Input(name = "source_sheet", type = OoaType.STRING, required = false, description = "Sheet to copy; the first sheet when empty.") String sourceSheet,
            @Input(name = "new_name", type = OoaType.STRING, required = false, description = "Name for the copy; the source name when empty.") String newName,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"excel\"", description = "Workbook session.") String session) {
        WorkbookSession s = session(ctx, session);
        try (Workbook other = load(ctx.resolvePath(path))) {
            Sheet src = sourceSheet == null || sourceSheet.isBlank() ? other.getSheetAt(0) : other.getSheet(sourceSheet);
            if (src == null) throw new ActionException("EXCEL.NO_SHEET", "Sheet '" + sourceSheet + "' not found in " + path, "Check the sheet name in the source workbook.");
            Sheet dst = s.wb.createSheet(uniqueSheetName(s.wb, newName == null || newName.isBlank() ? src.getSheetName() : newName));
            copySheetInto(src, dst);
            return out("name", dst.getSheetName());
        } catch (IOException e) { throw new ActionException("EXCEL.OPEN_FAILED", e.getMessage()); }
    }

    @Action(id = "copy_sheet", name = "Copy worksheet", category = "Sheets", description = "Duplicate a worksheet inside the same workbook under a new name.")
    @Output(name = "name", type = OoaType.STRING, description = "Name of the new sheet.")
    public Map<String, Object> copySheet(ActionContext ctx,
            @Input(name = "sheet", type = OoaType.STRING, required = false, description = "Sheet to copy; active sheet when empty.") String sheet,
            @Input(name = "new_name", type = OoaType.STRING, required = false, description = "Name for the copy; a numbered name when empty.") String newName,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"excel\"", description = "Workbook session.") String session) {
        WorkbookSession s = session(ctx, session);
        Sheet src = sheet(s, sheet);
        if (newName != null && !newName.isBlank() && s.wb.getSheetIndex(newName) >= 0)
            throw new ActionException("EXCEL.SHEET_EXISTS", "Sheet '" + newName + "' already exists", "Choose another name.");
        Sheet copy = s.wb.cloneSheet(s.wb.getSheetIndex(src));
        int idx = s.wb.getSheetIndex(copy);
        if (newName != null && !newName.isBlank()) {
            try { s.wb.setSheetName(idx, newName); }
            catch (IllegalArgumentException e) { s.wb.removeSheetAt(idx); throw new ActionException("EXCEL.BAD_SHEET_NAME", e.getMessage(), "Sheet names are at most 31 characters and cannot contain : \\ / ? * [ ]."); }
        }
        return out("name", s.wb.getSheetName(idx));
    }

    @Action(id = "rename_sheet", name = "Rename worksheet", category = "Sheets", description = "Give a worksheet a new name.")
    public Map<String, Object> renameSheet(ActionContext ctx,
            @Input(name = "sheet", type = OoaType.STRING, description = "Current sheet name.") String sheet,
            @Input(name = "new_name", type = OoaType.STRING, description = "New sheet name.") String newName,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"excel\"", description = "Workbook session.") String session) {
        WorkbookSession s = session(ctx, session);
        int idx = s.wb.getSheetIndex(sheet(s, sheet));
        int clash = s.wb.getSheetIndex(newName);
        if (clash >= 0 && clash != idx) throw new ActionException("EXCEL.SHEET_EXISTS", "Sheet '" + newName + "' already exists", "Choose another name.");
        try { s.wb.setSheetName(idx, newName); }
        catch (IllegalArgumentException e) { throw new ActionException("EXCEL.BAD_SHEET_NAME", e.getMessage(), "Sheet names are at most 31 characters and cannot contain : \\ / ? * [ ]."); }
        return Map.of();
    }

    @Action(id = "sheet_count", name = "Get sheet count", category = "Sheets", idempotent = true, description = "Count the worksheets in the workbook, hidden ones included.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of worksheets.")
    public Map<String, Object> sheetCount(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"excel\"", description = "Workbook session.") String session) {
        return out("count", n(session(ctx, session).wb.getNumberOfSheets()));
    }

    @Action(id = "get_current_sheet_name", name = "Get current sheet name", category = "Sheets", idempotent = true, description = "Return the name of the active worksheet.")
    @Output(name = "name", type = OoaType.STRING, description = "Active sheet name.")
    public Map<String, Object> currentSheet(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"excel\"", description = "Workbook session.") String session) {
        WorkbookSession s = session(ctx, session);
        return out("name", s.wb.getSheetName(s.wb.getActiveSheetIndex()));
    }

    private static void activate(WorkbookSession s, int idx) {
        if (s.wb.isSheetHidden(idx) || s.wb.isSheetVeryHidden(idx)) s.wb.setSheetHidden(idx, false);
        for (int i = 0; i < s.wb.getNumberOfSheets(); i++) s.wb.getSheetAt(i).setSelected(i == idx);
        s.wb.setActiveSheet(idx);
    }

    @Action(id = "switch_sheet", name = "Switch to sheet", category = "Sheets",
            description = "Make a worksheet the active one; later steps with an empty sheet input work on it, and it opens first in Excel after saving.")
    public Map<String, Object> switchSheet(ActionContext ctx,
            @Input(name = "sheet", type = OoaType.STRING, description = "Sheet name to activate.") String sheet,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"excel\"", description = "Workbook session.") String session) {
        WorkbookSession s = session(ctx, session);
        activate(s, s.wb.getSheetIndex(sheet(s, sheet)));
        return Map.of();
    }

    @Action(id = "hide_sheet", name = "Hide sheet", category = "Sheets", description = "Hide a worksheet; at least one sheet must stay visible.")
    public Map<String, Object> hideSheet(ActionContext ctx,
            @Input(name = "sheet", type = OoaType.STRING, description = "Sheet to hide.") String sheet,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"excel\"", description = "Workbook session.") String session) {
        WorkbookSession s = session(ctx, session);
        int idx = s.wb.getSheetIndex(sheet(s, sheet));
        int otherVisible = -1;
        for (int i = 0; i < s.wb.getNumberOfSheets(); i++) if (i != idx && !s.wb.isSheetHidden(i) && !s.wb.isSheetVeryHidden(i)) { otherVisible = i; break; }
        if (otherVisible < 0) throw new ActionException("EXCEL.LAST_VISIBLE_SHEET", "Cannot hide the only visible sheet", "Unhide or create another sheet first.");
        if (s.wb.getActiveSheetIndex() == idx) activate(s, otherVisible);
        s.wb.getSheetAt(idx).setSelected(false);
        s.wb.setSheetHidden(idx, true);
        return Map.of();
    }

    @Action(id = "unhide_sheet", name = "Unhide sheet", category = "Sheets", description = "Make a hidden worksheet visible again.")
    public Map<String, Object> unhideSheet(ActionContext ctx,
            @Input(name = "sheet", type = OoaType.STRING, description = "Sheet to show.") String sheet,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"excel\"", description = "Workbook session.") String session) {
        WorkbookSession s = session(ctx, session);
        s.wb.setSheetHidden(s.wb.getSheetIndex(sheet(s, sheet)), false);
        return Map.of();
    }

    @Action(id = "unhide_all_sheets", name = "Unhide all sheets", category = "Sheets", description = "Make every hidden worksheet visible.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of sheets that were hidden.")
    public Map<String, Object> unhideAllSheets(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"excel\"", description = "Workbook session.") String session) {
        WorkbookSession s = session(ctx, session);
        int n = 0;
        for (int i = 0; i < s.wb.getNumberOfSheets(); i++)
            if (s.wb.isSheetHidden(i) || s.wb.isSheetVeryHidden(i)) { s.wb.setSheetHidden(i, false); n++; }
        return out("count", n(n));
    }

    // ------------------------------------------------------------------------------------------------
    // Rows, columns and cells
    // ------------------------------------------------------------------------------------------------

    @Action(id = "insert_rows", name = "Insert rows", category = "Rows and columns", description = "Insert empty rows before a row number, moving the rows below down.")
    public Map<String, Object> insertRows(ActionContext ctx,
            @Input(name = "row", type = OoaType.NUMBER, example = "3", description = "1-based row number the new rows go before.") int row,
            @Input(name = "count", type = OoaType.NUMBER, required = false, defaultValue = "1", description = "How many rows to insert.") int count,
            @Input(name = "sheet", type = OoaType.STRING, required = false, description = "Sheet name; active sheet when empty.") String sheet,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"excel\"", description = "Workbook session.") String session) {
        Sheet sh = sheet(session(ctx, session), sheet);
        int start = rowIndex(row), k = count(count);
        if (start <= sh.getLastRowNum() && sh.getPhysicalNumberOfRows() > 0) sh.shiftRows(start, sh.getLastRowNum(), k, true, false);
        return Map.of();
    }

    @Action(id = "delete_rows", name = "Delete rows", category = "Rows and columns", description = "Delete whole rows and move the rows below up.")
    public Map<String, Object> deleteRows(ActionContext ctx,
            @Input(name = "row", type = OoaType.NUMBER, example = "3", description = "1-based number of the first row to delete.") int row,
            @Input(name = "count", type = OoaType.NUMBER, required = false, defaultValue = "1", description = "How many rows to delete.") int count,
            @Input(name = "sheet", type = OoaType.STRING, required = false, description = "Sheet name; active sheet when empty.") String sheet,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"excel\"", description = "Workbook session.") String session) {
        Sheet sh = sheet(session(ctx, session), sheet);
        removeRows(sh, rowIndex(row), count(count));
        return Map.of();
    }

    private static void removeRows(Sheet sh, int start, int k) {
        int last = sh.getLastRowNum();
        for (int r = start; r < start + k && r <= last; r++) { Row x = sh.getRow(r); if (x != null) sh.removeRow(x); }
        if (start + k <= last) sh.shiftRows(start + k, last, -k, true, false);
    }

    @Action(id = "insert_columns", name = "Insert columns", category = "Rows and columns", description = "Insert empty columns before a column, moving the columns to the right.")
    public Map<String, Object> insertColumns(ActionContext ctx,
            @Input(name = "column", example = "C", description = "Column letter or 1-based number the new columns go before.") Object column,
            @Input(name = "count", type = OoaType.NUMBER, required = false, defaultValue = "1", description = "How many columns to insert.") int count,
            @Input(name = "sheet", type = OoaType.STRING, required = false, description = "Sheet name; active sheet when empty.") String sheet,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"excel\"", description = "Workbook session.") String session) {
        Sheet sh = sheet(session(ctx, session), sheet);
        int start = columnIndex(column), k = count(count), last = lastCol(sh);
        if (start <= last) sh.shiftColumns(start, last, k);
        return Map.of();
    }

    @Action(id = "delete_columns", name = "Delete columns", category = "Rows and columns", description = "Delete whole columns and move the columns to the right leftwards.")
    public Map<String, Object> deleteColumns(ActionContext ctx,
            @Input(name = "column", example = "C", description = "Column letter or 1-based number of the first column to delete.") Object column,
            @Input(name = "count", type = OoaType.NUMBER, required = false, defaultValue = "1", description = "How many columns to delete.") int count,
            @Input(name = "sheet", type = OoaType.STRING, required = false, description = "Sheet name; active sheet when empty.") String sheet,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"excel\"", description = "Workbook session.") String session) {
        Sheet sh = sheet(session(ctx, session), sheet);
        int start = columnIndex(column), k = count(count), last = lastCol(sh);
        for (Row r : sh) for (int c = start; c < start + k; c++) { Cell x = r.getCell(c); if (x != null) r.removeCell(x); }
        if (start + k <= last) sh.shiftColumns(start + k, last, -k);
        return Map.of();
    }

    @Action(id = "delete_cells", name = "Delete cells", category = "Cells",
            description = "Delete the cells of a range and close the gap by moving the cells below up or the cells to the right left.")
    public Map<String, Object> deleteCells(ActionContext ctx,
            @Input(name = "range", type = OoaType.STRING, pattern = RANGE, example = "B2:C3", description = "Cell or range to delete.") String range,
            @Input(name = "shift", type = OoaType.STRING, required = false, defaultValue = "\"up\"", enumValues = {"up", "left"}, description = "Direction the remaining cells move: up or left.") String shift,
            @Input(name = "sheet", type = OoaType.STRING, required = false, description = "Sheet name; active sheet when empty.") String sheet,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"excel\"", description = "Workbook session.") String session) {
        WorkbookSession s = session(ctx, session);
        Sheet sh = sheet(s, sheet);
        AreaReference a = area(s, range);
        int r0 = a.getFirstCell().getRow(), r1 = a.getLastCell().getRow(), c0 = a.getFirstCell().getCol(), c1 = a.getLastCell().getCol();
        if ("left".equalsIgnoreCase(shift)) {
            int w = c1 - c0 + 1;
            for (int r = r0; r <= r1; r++) {
                Row row = sh.getRow(r);
                if (row == null) continue;
                int last = row.getLastCellNum() - 1;
                for (int c = c0; c <= last; c++) copyCell(c + w <= last ? row.getCell(c + w) : null, row, c);
            }
        } else if ("up".equalsIgnoreCase(shift) || shift == null) {
            int h = r1 - r0 + 1, last = sh.getLastRowNum();
            for (int r = r0; r <= last; r++) {
                Row src = r + h <= last ? sh.getRow(r + h) : null;
                Row dst = sh.getRow(r);
                for (int c = c0; c <= c1; c++) {
                    Cell from = src == null ? null : src.getCell(c);
                    if (from == null && dst == null) continue;
                    copyCell(from, dst == null ? (dst = sh.createRow(r)) : dst, c);
                }
            }
        } else throw new ActionException("EXCEL.BAD_SHIFT", "Shift must be up or left, not '" + shift + "'", "Use up or left.");
        return Map.of();
    }

    @Action(id = "hide_rows", name = "Hide rows", category = "Rows and columns", description = "Hide one or more rows.")
    public Map<String, Object> hideRows(ActionContext ctx,
            @Input(name = "row", type = OoaType.NUMBER, description = "1-based number of the first row.") int row,
            @Input(name = "count", type = OoaType.NUMBER, required = false, defaultValue = "1", description = "How many rows.") int count,
            @Input(name = "sheet", type = OoaType.STRING, required = false, description = "Sheet name; active sheet when empty.") String sheet,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"excel\"", description = "Workbook session.") String session) {
        Sheet sh = sheet(session(ctx, session), sheet);
        for (int r = rowIndex(row), e = r + count(count); r < e; r++) row(sh, r).setZeroHeight(true);
        return Map.of();
    }

    @Action(id = "unhide_rows", name = "Unhide rows", category = "Rows and columns", description = "Show hidden rows again.")
    public Map<String, Object> unhideRows(ActionContext ctx,
            @Input(name = "row", type = OoaType.NUMBER, description = "1-based number of the first row.") int row,
            @Input(name = "count", type = OoaType.NUMBER, required = false, defaultValue = "1", description = "How many rows.") int count,
            @Input(name = "sheet", type = OoaType.STRING, required = false, description = "Sheet name; active sheet when empty.") String sheet,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"excel\"", description = "Workbook session.") String session) {
        Sheet sh = sheet(session(ctx, session), sheet);
        for (int r = rowIndex(row), e = r + count(count); r < e; r++) { Row x = sh.getRow(r); if (x != null) x.setZeroHeight(false); }
        return Map.of();
    }

    @Action(id = "hide_columns", name = "Hide columns", category = "Rows and columns", description = "Hide one or more columns.")
    public Map<String, Object> hideColumns(ActionContext ctx,
            @Input(name = "column", example = "B", description = "Column letter or 1-based number of the first column.") Object column,
            @Input(name = "count", type = OoaType.NUMBER, required = false, defaultValue = "1", description = "How many columns.") int count,
            @Input(name = "sheet", type = OoaType.STRING, required = false, description = "Sheet name; active sheet when empty.") String sheet,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"excel\"", description = "Workbook session.") String session) {
        Sheet sh = sheet(session(ctx, session), sheet);
        for (int c = columnIndex(column), e = c + count(count); c < e; c++) sh.setColumnHidden(c, true);
        return Map.of();
    }

    @Action(id = "unhide_columns", name = "Unhide columns", category = "Rows and columns", description = "Show hidden columns again.")
    public Map<String, Object> unhideColumns(ActionContext ctx,
            @Input(name = "column", example = "B", description = "Column letter or 1-based number of the first column.") Object column,
            @Input(name = "count", type = OoaType.NUMBER, required = false, defaultValue = "1", description = "How many columns.") int count,
            @Input(name = "sheet", type = OoaType.STRING, required = false, description = "Sheet name; active sheet when empty.") String sheet,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"excel\"", description = "Workbook session.") String session) {
        Sheet sh = sheet(session(ctx, session), sheet);
        for (int c = columnIndex(column), e = c + count(count); c < e; c++) sh.setColumnHidden(c, false);
        return Map.of();
    }

    @Action(id = "remove_blank_rows", name = "Remove blank rows", category = "Rows and columns", description = "Delete every empty row inside the used area and close the gaps.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of rows removed.")
    public Map<String, Object> removeBlankRows(ActionContext ctx,
            @Input(name = "sheet", type = OoaType.STRING, required = false, description = "Sheet name; active sheet when empty.") String sheet,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"excel\"", description = "Workbook session.") String session) {
        Sheet sh = sheet(session(ctx, session), sheet);
        int removed = 0;
        int last = sh.getLastRowNum();
        while (last >= 0 && blankRow(sh.getRow(last))) { Row x = sh.getRow(last); if (x != null) sh.removeRow(x); last--; }  // trailing blanks are not counted
        for (int r = last - 1; r >= 0; r--) {
            if (!blankRow(sh.getRow(r))) continue;
            removeRows(sh, r, 1);
            removed++;
        }
        return out("count", n(removed));
    }

    @Action(id = "row_count", name = "Get number of rows", category = "Rows and columns", idempotent = true,
            description = "Count the rows that hold data and report the number of the last used row.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Rows with at least one non-empty cell.")
    @Output(name = "last_row", type = OoaType.NUMBER, description = "1-based number of the last row with data; 0 when the sheet is empty.")
    public Map<String, Object> rowCount(ActionContext ctx,
            @Input(name = "sheet", type = OoaType.STRING, required = false, description = "Sheet name; active sheet when empty.") String sheet,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"excel\"", description = "Workbook session.") String session) {
        Sheet sh = sheet(session(ctx, session), sheet);
        int count = 0, last = 0;
        for (Row r : sh) if (!blankRow(r)) { count++; last = Math.max(last, r.getRowNum() + 1); }
        return out("count", n(count), "last_row", n(last));
    }

    @Action(id = "get_table_range", name = "Get table range", category = "Cells", idempotent = true,
            description = "Return the address of the smallest rectangle holding all data on the sheet, e.g. A1:D20.")
    @Output(name = "range", type = OoaType.STRING, description = "Used range address; empty when the sheet is empty.")
    @Output(name = "rows", type = OoaType.NUMBER, description = "Number of rows in the range.")
    @Output(name = "columns", type = OoaType.NUMBER, description = "Number of columns in the range.")
    public Map<String, Object> getTableRange(ActionContext ctx,
            @Input(name = "sheet", type = OoaType.STRING, required = false, description = "Sheet name; active sheet when empty.") String sheet,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"excel\"", description = "Workbook session.") String session) {
        Sheet sh = sheet(session(ctx, session), sheet);
        int r0 = Integer.MAX_VALUE, r1 = -1, c0 = Integer.MAX_VALUE, c1 = -1;
        for (Row r : sh) for (Cell c : r) if (!blank(c)) {
            r0 = Math.min(r0, r.getRowNum()); r1 = Math.max(r1, r.getRowNum());
            c0 = Math.min(c0, c.getColumnIndex()); c1 = Math.max(c1, c.getColumnIndex());
        }
        if (r1 < 0) return out("range", "", "rows", n(0), "columns", n(0));
        return out("range", addr(r0, c0) + ":" + addr(r1, c1), "rows", n(r1 - r0 + 1), "columns", n(c1 - c0 + 1));
    }

    @Action(id = "find_next_empty_cell", name = "Find next empty cell", category = "Cells", idempotent = true,
            description = "Starting from a cell, walk down its column or along its row and return the first empty cell.")
    @Output(name = "cell", type = OoaType.STRING, description = "Address of the first empty cell, e.g. A12.")
    @Output(name = "row", type = OoaType.NUMBER, description = "1-based row number of that cell.")
    @Output(name = "column", type = OoaType.NUMBER, description = "1-based column number of that cell.")
    public Map<String, Object> findNextEmptyCell(ActionContext ctx,
            @Input(name = "start_cell", type = OoaType.STRING, required = false, pattern = CELL, description = "Where to start; the cell chosen by go_to_cell, or A1, when empty.") String start,
            @Input(name = "direction", type = OoaType.STRING, required = false, defaultValue = "\"down\"", enumValues = {"down", "right"}, description = "down searches the column, right searches the row.") String direction,
            @Input(name = "sheet", type = OoaType.STRING, required = false, description = "Sheet name; active sheet when empty.") String sheet,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"excel\"", description = "Workbook session.") String session) {
        WorkbookSession s = session(ctx, session);
        Sheet sh = sheet(s, sheet);
        String from = start != null && !start.isBlank() ? start : s.selection != null ? s.selection.split(":")[0] : "A1";
        CellReference ref = new CellReference(from.toUpperCase(Locale.ROOT));
        int r = ref.getRow(), c = ref.getCol();
        boolean right = "right".equalsIgnoreCase(direction);
        while (true) {
            Row row = sh.getRow(r);
            if (blank(row == null ? null : row.getCell(c))) break;
            if (right) c++; else r++;
        }
        return out("cell", addr(r, c), "row", n(r + 1), "column", n(c + 1));
    }

    @Action(id = "go_to_cell", name = "Go to cell", category = "Cells",
            description = "Make a cell the active cell of its sheet and remember it for later steps such as find_next_empty_cell.")
    public Map<String, Object> goToCell(ActionContext ctx,
            @Input(name = "cell", type = OoaType.STRING, pattern = CELL, example = "B7", description = "Cell address.") String address,
            @Input(name = "sheet", type = OoaType.STRING, required = false, description = "Sheet name; active sheet when empty.") String sheet,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"excel\"", description = "Workbook session.") String session) {
        return select(ctx, session, sheet, address.toUpperCase(Locale.ROOT));
    }

    @Action(id = "select_range", name = "Select range", category = "Cells",
            description = "Remember a range as the session's current selection and make its top-left cell active.")
    public Map<String, Object> selectRange(ActionContext ctx,
            @Input(name = "range", type = OoaType.STRING, pattern = RANGE, example = "A1:D20", description = "Range address.") String range,
            @Input(name = "sheet", type = OoaType.STRING, required = false, description = "Sheet name; active sheet when empty.") String sheet,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"excel\"", description = "Workbook session.") String session) {
        return select(ctx, session, sheet, range.toUpperCase(Locale.ROOT));
    }

    private Map<String, Object> select(ActionContext ctx, String session, String sheet, String range) {
        WorkbookSession s = session(ctx, session);
        Sheet sh = sheet(s, sheet);
        AreaReference a = area(s, range);
        activate(s, s.wb.getSheetIndex(sh));
        sh.setActiveCell(new CellAddress(a.getFirstCell().getRow(), a.getFirstCell().getCol()));
        s.selection = range;
        return Map.of();
    }

    @Action(id = "cell_address", name = "Get cell address", category = "Addresses", idempotent = true, description = "Turn a row number and a column number or letter into an address such as B7.")
    @Output(name = "cell", type = OoaType.STRING, description = "Cell address.")
    public Map<String, Object> cellAddress(ActionContext ctx,
            @Input(name = "row", type = OoaType.NUMBER, description = "1-based row number.") int row,
            @Input(name = "column", description = "1-based column number or column letters.") Object column) {
        return out("cell", addr(rowIndex(row), columnIndex(column)));
    }

    @Action(id = "column_name", name = "Get column name", category = "Addresses", idempotent = true, description = "Turn a 1-based column number into its letters, e.g. 28 becomes AB.")
    @Output(name = "name", type = OoaType.STRING, description = "Column letters.")
    public Map<String, Object> columnName(ActionContext ctx,
            @Input(name = "column", type = OoaType.NUMBER, description = "1-based column number.") int column) {
        return out("name", CellReference.convertNumToColString(checkCol(column)));
    }

    @Action(id = "row_number", name = "Get row and column number", category = "Addresses", idempotent = true, description = "Split a cell address such as B7 into its row and column numbers.")
    @Output(name = "row", type = OoaType.NUMBER, description = "1-based row number.")
    @Output(name = "column", type = OoaType.NUMBER, description = "1-based column number.")
    @Output(name = "column_name", type = OoaType.STRING, description = "Column letters.")
    public Map<String, Object> rowNumber(ActionContext ctx,
            @Input(name = "cell", type = OoaType.STRING, pattern = CELL, example = "B7", description = "Cell address.") String address) {
        CellReference r = new CellReference(address.toUpperCase(Locale.ROOT));
        return out("row", n(r.getRow() + 1), "column", n(r.getCol() + 1), "column_name", CellReference.convertNumToColString(r.getCol()));
    }

    // ------------------------------------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------------------------------------

    @Action(id = "read_formula", name = "Read cell formula", category = "Cells", idempotent = true, description = "Return the formula a cell holds, or an empty text when it holds a plain value.")
    @Output(name = "formula", type = OoaType.STRING, description = "Formula starting with =, or empty.")
    @Output(name = "has_formula", type = OoaType.BOOLEAN, description = "True when the cell holds a formula.")
    public Map<String, Object> readFormula(ActionContext ctx,
            @Input(name = "cell", type = OoaType.STRING, pattern = CELL, example = "C11", description = "Cell address.") String address,
            @Input(name = "sheet", type = OoaType.STRING, required = false, description = "Sheet name; active sheet when empty.") String sheet,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"excel\"", description = "Workbook session.") String session) {
        Cell c = cell(sheet(session(ctx, session), sheet), address, false);
        boolean f = c != null && c.getCellType() == CellType.FORMULA;
        return out("formula", f ? "=" + c.getCellFormula() : "", "has_formula", f);
    }

    @Action(id = "read_column", name = "Read column", category = "Cells", idempotent = true, description = "Read the values of one column, from a start row down to the last used row.")
    @Output(name = "values", type = OoaType.LIST, description = "Cell values from top to bottom; empty cells are null.")
    public Map<String, Object> readColumn(ActionContext ctx,
            @Input(name = "column", example = "B", description = "Column letter or 1-based number.") Object column,
            @Input(name = "start_row", type = OoaType.NUMBER, required = false, defaultValue = "1", description = "1-based row to start from, e.g. 2 to skip a header.") int startRow,
            @Input(name = "sheet", type = OoaType.STRING, required = false, description = "Sheet name; active sheet when empty.") String sheet,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"excel\"", description = "Workbook session.") String session) {
        WorkbookSession s = session(ctx, session);
        Sheet sh = sheet(s, sheet);
        FormulaEvaluator ev = evaluator(s);
        int c = columnIndex(column);
        List<Object> vals = new ArrayList<>();
        for (int r = rowIndex(startRow); r <= sh.getLastRowNum(); r++) { Row row = sh.getRow(r); vals.add(row == null ? null : read(row.getCell(c), ev)); }
        while (!vals.isEmpty() && vals.get(vals.size() - 1) == null) vals.remove(vals.size() - 1);
        return out("values", vals);
    }

    @Action(id = "read_row", name = "Read row", category = "Cells", idempotent = true, description = "Read the values of one row, from a start column to the last used cell.")
    @Output(name = "values", type = OoaType.LIST, description = "Cell values from left to right; empty cells are null.")
    public Map<String, Object> readRow(ActionContext ctx,
            @Input(name = "row", type = OoaType.NUMBER, description = "1-based row number.") int row,
            @Input(name = "start_column", required = false, defaultValue = "\"A\"", description = "Column letter or 1-based number to start from.") Object startColumn,
            @Input(name = "sheet", type = OoaType.STRING, required = false, description = "Sheet name; active sheet when empty.") String sheet,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"excel\"", description = "Workbook session.") String session) {
        WorkbookSession s = session(ctx, session);
        Row r = sheet(s, sheet).getRow(rowIndex(row));
        FormulaEvaluator ev = evaluator(s);
        List<Object> vals = new ArrayList<>();
        if (r != null) for (int c = columnIndex(startColumn == null ? "A" : startColumn); c < r.getLastCellNum(); c++) vals.add(read(r.getCell(c), ev));
        while (!vals.isEmpty() && vals.get(vals.size() - 1) == null) vals.remove(vals.size() - 1);
        return out("values", vals);
    }

    // ------------------------------------------------------------------------------------------------
    // Colours
    // ------------------------------------------------------------------------------------------------

    private static String hex(XSSFColor c) {
        if (c == null) return "";
        byte[] rgb = c.getRGBWithTint();
        if (rgb == null) rgb = c.getRGB();
        if (rgb == null) return "";
        return String.format("#%02X%02X%02X", rgb[rgb.length - 3] & 0xff, rgb[rgb.length - 2] & 0xff, rgb[rgb.length - 1] & 0xff);
    }

    static XSSFColor parseColor(String colour) {
        String h = colour == null ? "" : colour.trim().replace("#", "");
        if (!h.matches("[0-9A-Fa-f]{6}"))
            throw new ActionException("EXCEL.BAD_COLOR", "'" + colour + "' is not a colour", "Use a hex colour such as #FFC000.");
        int v = Integer.parseInt(h, 16);
        return new XSSFColor(new byte[]{(byte) (v >> 16), (byte) (v >> 8), (byte) v}, null);
    }

    @Action(id = "get_cell_color", name = "Get cell colour", category = "Formatting", idempotent = true, description = "Return a cell's fill colour or font colour as a hex value such as #FFC000.")
    @Output(name = "color", type = OoaType.STRING, description = "Hex colour; empty when the cell has no colour set.")
    public Map<String, Object> getCellColor(ActionContext ctx,
            @Input(name = "cell", type = OoaType.STRING, pattern = CELL, example = "B2", description = "Cell address.") String address,
            @Input(name = "target", type = OoaType.STRING, required = false, defaultValue = "\"fill\"", enumValues = {"fill", "font"}, description = "fill for the background, font for the text colour.") String target,
            @Input(name = "sheet", type = OoaType.STRING, required = false, description = "Sheet name; active sheet when empty.") String sheet,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"excel\"", description = "Workbook session.") String session) {
        WorkbookSession s = session(ctx, session);
        XSSFWorkbook wb = xlsx(s, "Cell colours");
        Cell c = cell(sheet(s, sheet), address, false);
        if (c == null) return out("color", "");
        XSSFCellStyle st = (XSSFCellStyle) c.getCellStyle();
        if ("font".equalsIgnoreCase(target)) return out("color", hex(wb.getFontAt(st.getFontIndex()).getXSSFColor()));
        if (st.getFillPattern() == FillPatternType.NO_FILL) return out("color", "");
        return out("color", hex(st.getFillForegroundXSSFColor()));
    }

    @Action(id = "set_cell_color", name = "Set cell colour", category = "Formatting", description = "Give a cell or range a solid fill colour or a font colour.")
    public Map<String, Object> setCellColor(ActionContext ctx,
            @Input(name = "range", type = OoaType.STRING, pattern = RANGE, example = "A1:D1", description = "Cell or range to colour.") String range,
            @Input(name = "color", type = OoaType.STRING, example = "#FFC000", description = "Hex colour.") String colour,
            @Input(name = "target", type = OoaType.STRING, required = false, defaultValue = "\"fill\"", enumValues = {"fill", "font"}, description = "fill for the background, font for the text colour.") String target,
            @Input(name = "sheet", type = OoaType.STRING, required = false, description = "Sheet name; active sheet when empty.") String sheet,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"excel\"", description = "Workbook session.") String session) {
        WorkbookSession s = session(ctx, session);
        XSSFWorkbook wb = xlsx(s, "Cell colours");
        Sheet sh = sheet(s, sheet);
        XSSFColor col = parseColor(colour);
        boolean font = "font".equalsIgnoreCase(target);
        Map<Short, XSSFCellStyle> done = new HashMap<>();
        AreaReference a = area(s, range);
        for (CellReference ref : a.getAllReferencedCells()) {
            Cell c = row(sh, ref.getRow()).getCell(ref.getCol(), Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
            XSSFCellStyle old = (XSSFCellStyle) c.getCellStyle();
            XSSFCellStyle st = done.computeIfAbsent(old.getIndex(), k -> {
                XSSFCellStyle ns = wb.createCellStyle();
                ns.cloneStyleFrom(old);
                if (font) {
                    XSSFFont of = wb.getFontAt(old.getFontIndex());
                    XSSFFont nf = wb.createFont();
                    nf.setFontName(of.getFontName()); nf.setFontHeight(of.getFontHeight()); nf.setBold(of.getBold());
                    nf.setItalic(of.getItalic()); nf.setUnderline(of.getUnderline()); nf.setStrikeout(of.getStrikeout());
                    nf.setColor(col);
                    ns.setFont(nf);
                } else {
                    ns.setFillForegroundColor(col);
                    ns.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                }
                return ns;
            });
            c.setCellStyle(st);
        }
        return Map.of();
    }

    // ------------------------------------------------------------------------------------------------
    // Protection
    // ------------------------------------------------------------------------------------------------

    @Action(id = "protect_workbook", name = "Protect workbook", category = "Protection",
            description = "Lock the workbook structure with a password so sheets cannot be added, removed, renamed or unhidden in Excel.")
    public Map<String, Object> protectWorkbook(ActionContext ctx,
            @Input(name = "password", type = OoaType.STRING, description = "Password needed to unlock the structure.") String password,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"excel\"", description = "Workbook session.") String session) {
        XSSFWorkbook wb = xlsx(session(ctx, session), "Workbook protection");
        wb.lockStructure();
        wb.setWorkbookPassword(password, HashAlgorithm.sha512);
        return Map.of();
    }

    @Action(id = "unprotect_workbook", name = "Unprotect workbook", category = "Protection", description = "Unlock a protected workbook structure; the password must match.")
    public Map<String, Object> unprotectWorkbook(ActionContext ctx,
            @Input(name = "password", type = OoaType.STRING, description = "Structure password.") String password,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"excel\"", description = "Workbook session.") String session) {
        XSSFWorkbook wb = xlsx(session(ctx, session), "Workbook protection");
        if (wb.isStructureLocked() && !wb.validateWorkbookPassword(password))
            throw new ActionException("EXCEL.WRONG_PASSWORD", "The workbook password is wrong", "Check the password stored in the credential vault.");
        wb.unLockStructure();
        wb.setWorkbookPassword(null, null);
        return Map.of();
    }

    @Action(id = "protect_sheet", name = "Protect sheet", category = "Protection", description = "Password-protect a worksheet so its locked cells cannot be edited in Excel.")
    public Map<String, Object> protectSheet(ActionContext ctx,
            @Input(name = "password", type = OoaType.STRING, description = "Password needed to unprotect the sheet.") String password,
            @Input(name = "sheet", type = OoaType.STRING, required = false, description = "Sheet name; active sheet when empty.") String sheet,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"excel\"", description = "Workbook session.") String session) {
        sheet(session(ctx, session), sheet).protectSheet(password);
        return Map.of();
    }

    @Action(id = "unprotect_sheet", name = "Unprotect sheet", category = "Protection", description = "Remove protection from a worksheet; for .xlsx files the password is checked first.")
    public Map<String, Object> unprotectSheet(ActionContext ctx,
            @Input(name = "password", type = OoaType.STRING, required = false, description = "Sheet password.") String password,
            @Input(name = "sheet", type = OoaType.STRING, required = false, description = "Sheet name; active sheet when empty.") String sheet,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"excel\"", description = "Workbook session.") String session) {
        Sheet sh = sheet(session(ctx, session), sheet);
        if (sh instanceof XSSFSheet x && x.isSheetLocked() && !x.validateSheetPassword(password == null ? "" : password))
            throw new ActionException("EXCEL.WRONG_PASSWORD", "The sheet password is wrong", "Check the password stored in the credential vault.");
        sh.protectSheet(null);
        return Map.of();
    }

    // ------------------------------------------------------------------------------------------------
    // Edit content
    // ------------------------------------------------------------------------------------------------

    @Action(id = "replace", name = "Replace", category = "Cells",
            description = "Replace text in the text cells of a sheet or range, optionally matching case or the whole cell only.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of cells changed.")
    public Map<String, Object> replace(ActionContext ctx,
            @Input(name = "find", type = OoaType.STRING, description = "Text to look for.") String find,
            @Input(name = "replace_with", type = OoaType.STRING, required = false, defaultValue = "\"\"", description = "Replacement text.") String with,
            @Input(name = "range", type = OoaType.STRING, required = false, pattern = RANGE, description = "Limit to this range; whole sheet when empty.") String range,
            @Input(name = "match_case", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Match upper/lower case exactly.") boolean matchCase,
            @Input(name = "whole_cell", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Only replace when the whole cell equals the text.") boolean wholeCell,
            @Input(name = "sheet", type = OoaType.STRING, required = false, description = "Sheet name; active sheet when empty.") String sheet,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"excel\"", description = "Workbook session.") String session) {
        if (find == null || find.isEmpty()) throw new ActionException("EXCEL.EMPTY_FIND", "Text to find is empty", "Give the text to replace.");
        WorkbookSession s = session(ctx, session);
        Sheet sh = sheet(s, sheet);
        String rep = with == null ? "" : with;
        AreaReference a = range == null || range.isBlank() ? null : area(s, range);
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(find), matchCase ? 0 : java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);
        int n = 0;
        for (Row r : sh) for (Cell c : r) {
            if (c.getCellType() != CellType.STRING) continue;
            if (a != null && !a.isWholeColumnReference() && !inArea(a, c)) continue;
            String v = c.getStringCellValue();
            String nv;
            if (wholeCell) nv = (matchCase ? v.equals(find) : v.equalsIgnoreCase(find)) ? rep : v;
            else nv = p.matcher(v).replaceAll(java.util.regex.Matcher.quoteReplacement(rep));
            if (!nv.equals(v)) { c.setCellValue(nv); n++; }
        }
        return out("count", n(n));
    }

    private static boolean inArea(AreaReference a, Cell c) {
        return c.getRowIndex() >= a.getFirstCell().getRow() && c.getRowIndex() <= a.getLastCell().getRow()
                && c.getColumnIndex() >= a.getFirstCell().getCol() && c.getColumnIndex() <= a.getLastCell().getCol();
    }

    private record Snap(CellType type, Object value, CellStyle style) { }

    private static Snap snap(Cell c) {
        if (c == null) return null;
        Object v = switch (c.getCellType()) {
            case STRING -> c.getStringCellValue();
            case NUMERIC -> c.getNumericCellValue();
            case BOOLEAN -> c.getBooleanCellValue();
            case FORMULA -> c.getCellFormula();
            case ERROR -> c.getErrorCellValue();
            default -> null;
        };
        return new Snap(c.getCellType(), v, c.getCellStyle());
    }

    private static void restore(Snap x, Row row, int col) {
        Cell old = row.getCell(col);
        if (x == null) { if (old != null) row.removeCell(old); return; }
        Cell c = old == null ? row.createCell(col) : old;
        c.setCellStyle(x.style());
        switch (x.type()) {
            case STRING -> c.setCellValue((String) x.value());
            case NUMERIC -> c.setCellValue((Double) x.value());
            case BOOLEAN -> c.setCellValue((Boolean) x.value());
            case FORMULA -> c.setCellFormula((String) x.value());
            case ERROR -> c.setCellErrorValue((Byte) x.value());
            default -> c.setBlank();
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compareValues(Object a, Object b) {
        int ra = rank(a), rb = rank(b);
        if (ra != rb) return Integer.compare(ra, rb);
        if (a instanceof String x && b instanceof String y) return x.compareToIgnoreCase(y);
        if (a instanceof Comparable x && a.getClass() == b.getClass()) return x.compareTo(b);
        return Values.text(a).compareToIgnoreCase(Values.text(b));
    }

    private static int rank(Object v) {
        if (v instanceof BigDecimal || v instanceof LocalDateTime) return 0;
        if (v instanceof String) return 1;
        if (v instanceof Boolean) return 2;
        return 3;
    }

    /** Resolve a column given as letters, a 1-based number, or (with a header row) a header name. */
    private static int resolveColumn(Object column, Row header, FormulaEvaluator ev) {
        String t = Values.text(column).trim();
        if (header != null) for (Cell c : header) {
            Object v = read(c, ev);
            if (v != null && Values.text(v).trim().equalsIgnoreCase(t)) return c.getColumnIndex();
        }
        return columnIndex(column);
    }

    @Action(id = "sort_range", name = "Sort", category = "Cells",
            description = "Sort the rows of a range (or of the used area) by one column, ascending or descending, keeping an optional header row in place.")
    public Map<String, Object> sortRange(ActionContext ctx,
            @Input(name = "column", example = "C", description = "Sort column: letter, 1-based sheet column number, or header name.") Object column,
            @Input(name = "descending", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Sort from largest to smallest.") boolean descending,
            @Input(name = "header", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "The first row is a header and stays at the top.") boolean header,
            @Input(name = "range", type = OoaType.STRING, required = false, pattern = RANGE, description = "Range to sort; the used area when empty.") String range,
            @Input(name = "sheet", type = OoaType.STRING, required = false, description = "Sheet name; active sheet when empty.") String sheet,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"excel\"", description = "Workbook session.") String session) {
        WorkbookSession s = session(ctx, session);
        Sheet sh = sheet(s, sheet);
        String rg = range;
        if (rg == null || rg.isBlank()) {
            rg = (String) getTableRange(ctx, sh.getSheetName(), session).get("range");
            if (rg.isEmpty()) return Map.of();
        }
        AreaReference a = area(s, rg);
        FormulaEvaluator ev = evaluator(s);
        int r0 = a.getFirstCell().getRow() + (header ? 1 : 0), r1 = a.getLastCell().getRow();
        int c0 = a.getFirstCell().getCol(), c1 = a.getLastCell().getCol();
        int key = resolveColumn(column, header ? sh.getRow(a.getFirstCell().getRow()) : null, ev);
        if (key < c0 || key > c1) throw new ActionException("EXCEL.BAD_COLUMN", "Sort column is outside the range " + rg, "Pick a column inside the range.");
        List<Object[]> rows = new ArrayList<>();
        for (int r = r0; r <= r1; r++) {
            Row row = sh.getRow(r);
            Snap[] cells = new Snap[c1 - c0 + 1];
            for (int c = c0; c <= c1; c++) cells[c - c0] = row == null ? null : snap(row.getCell(c));
            rows.add(new Object[]{row == null ? null : read(row.getCell(key), ev), cells});
        }
        Comparator<Object[]> cmp = (x, y) -> {
            if (x[0] == null || y[0] == null) return x[0] == null ? (y[0] == null ? 0 : 1) : -1;  // empty keys always last
            int v = compareValues(x[0], y[0]);
            return descending ? -v : v;
        };
        rows.sort(cmp);
        for (int i = 0; i < rows.size(); i++) {
            Snap[] cells = (Snap[]) rows.get(i)[1];
            Row row = row(sh, r0 + i);
            for (int c = c0; c <= c1; c++) restore(cells[c - c0], row, c);
        }
        return Map.of();
    }

    @Action(id = "filter", name = "Filter", category = "Cells", idempotent = true,
            description = "Return the rows of a sheet whose value in one column matches a condition, as a Data Table; the sheet is not changed.")
    @Output(name = "table", type = OoaType.TABLE, description = "Matching rows with the sheet's column names.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of matching rows.")
    @SuppressWarnings("unchecked")
    public Map<String, Object> filter(ActionContext ctx,
            @Input(name = "column", example = "Status", description = "Column to test: header name, letter or 1-based number.") Object column,
            @Input(name = "operator", type = OoaType.STRING, required = false, defaultValue = "\"equals\"",
                    enumValues = {"equals", "not_equals", "contains", "starts_with", "ends_with", "gt", "gte", "lt", "lte", "empty", "not_empty"},
                    description = "How the cell is compared with the value.") String operator,
            @Input(name = "value", required = false, description = "Value to compare with; not needed for empty and not_empty.") Object value,
            @Input(name = "match_case", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Match upper/lower case exactly for text comparisons.") boolean matchCase,
            @Input(name = "header", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "First row holds column names.") boolean header,
            @Input(name = "sheet", type = OoaType.STRING, required = false, description = "Sheet name; active sheet when empty.") String sheet,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"excel\"", description = "Workbook session.") String session) {
        Map<String, Object> all = getAllCells(ctx, sheet, header, session);
        Map<String, Object> t = (Map<String, Object>) all.get("table");
        List<?> cols = (List<?>) t.get("columns");
        String name = Values.text(column).trim();
        int idx = -1;
        for (int i = 0; i < cols.size(); i++) if (Values.text(cols.get(i)).equalsIgnoreCase(name)) { idx = i; break; }
        if (idx < 0) {
            idx = columnIndex(column);  // tables built from the sheet start at column A
            if (idx >= cols.size()) throw new ActionException("EXCEL.BAD_COLUMN", "Column '" + name + "' not found", "Columns: " + cols + ".");
        }
        String op = operator == null ? "equals" : operator.toLowerCase(Locale.ROOT);
        List<Object> hits = new ArrayList<>();
        for (Object r : (List<?>) t.get("rows")) {
            Object v = ((List<?>) r).get(idx);
            if (matches(v, op, value, matchCase)) hits.add(r);
        }
        return out("table", Values.table(cols.stream().map(Values::text).toList(), hits), "count", n(hits.size()));
    }

    private static boolean matches(Object cell, String op, Object value, boolean matchCase) {
        boolean empty = cell == null || Values.text(cell).isBlank();
        if (op.equals("empty")) return empty;
        if (op.equals("not_empty")) return !empty;
        if (op.equals("gt") || op.equals("gte") || op.equals("lt") || op.equals("lte")) {
            if (empty || value == null) return false;
            int c;
            BigDecimal a = asNumber(cell), b = asNumber(value);
            if (a != null && b != null) c = a.compareTo(b);
            else c = Values.text(cell).compareToIgnoreCase(Values.text(value));
            return switch (op) { case "gt" -> c > 0; case "gte" -> c >= 0; case "lt" -> c < 0; default -> c <= 0; };
        }
        String x = cell == null ? "" : Values.text(cell), y = value == null ? "" : Values.text(value);
        BigDecimal a = asNumber(cell), b = asNumber(value);
        boolean eq = a != null && b != null ? a.compareTo(b) == 0 : matchCase ? x.equals(y) : x.equalsIgnoreCase(y);
        if (!matchCase) { x = x.toLowerCase(Locale.ROOT); y = y.toLowerCase(Locale.ROOT); }
        return switch (op) {
            case "equals" -> eq;
            case "not_equals" -> !eq;
            case "contains" -> x.contains(y);
            case "starts_with" -> x.startsWith(y);
            case "ends_with" -> x.endsWith(y);
            default -> throw new ActionException("EXCEL.BAD_OPERATOR", "Unknown operator '" + op + "'", "Use equals, not_equals, contains, starts_with, ends_with, gt, gte, lt, lte, empty or not_empty.");
        };
    }

    private static BigDecimal asNumber(Object v) {
        if (v instanceof BigDecimal d) return d;
        if (v instanceof Number n) return new BigDecimal(n.toString());
        if (v instanceof String s) try { return new BigDecimal(s.trim()); } catch (NumberFormatException e) { return null; }
        return null;
    }

    // ------------------------------------------------------------------------------------------------
    // Excel tables (ListObjects)
    // ------------------------------------------------------------------------------------------------

    private static XSSFTable table(XSSFWorkbook wb, String name) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < wb.getNumberOfSheets(); i++) for (XSSFTable t : wb.getSheetAt(i).getTables()) {
            if (t.getName().equalsIgnoreCase(name) || t.getDisplayName().equalsIgnoreCase(name)) return t;
            names.add(t.getName());
        }
        throw new ActionException("EXCEL.NO_TABLE", "Excel table '" + name + "' not found", names.isEmpty() ? "The workbook has no Excel tables." : "Tables: " + String.join(", ", names) + ".");
    }

    @Action(id = "insert_table_column", name = "Insert table column", category = "Tables",
            description = "Add a named column to an Excel table, moving the table cells to its right over by one.")
    public Map<String, Object> insertTableColumn(ActionContext ctx,
            @Input(name = "table", type = OoaType.STRING, description = "Excel table name, e.g. Table1.") String tableName,
            @Input(name = "column_name", type = OoaType.STRING, description = "Header for the new column.") String columnName,
            @Input(name = "position", type = OoaType.NUMBER, required = false, description = "1-based position inside the table; at the end when empty.") Integer position,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"excel\"", description = "Workbook session.") String session) {
        XSSFWorkbook wb = xlsx(session(ctx, session), "Excel tables");
        XSSFTable t = table(wb, tableName);
        for (XSSFTableColumn col : t.getColumns()) if (col.getName().equalsIgnoreCase(columnName))
            throw new ActionException("EXCEL.COLUMN_EXISTS", "Table '" + tableName + "' already has a column '" + columnName + "'", "Choose another column name.");
        XSSFSheet sh = t.getXSSFSheet();
        CellReference a = t.getStartCellReference(), b = t.getEndCellReference();
        int n = b.getCol() - a.getCol() + 1;
        int pos = position == null ? n : position - 1;
        if (pos < 0 || pos > n) throw new ActionException("EXCEL.BAD_POSITION", "Position must be between 1 and " + (n + 1), "Leave position empty to add at the end.");
        int at = a.getCol() + pos;
        for (int r = a.getRow(); r <= b.getRow(); r++) {
            Row row = row(sh, r);
            for (int c = b.getCol(); c >= at; c--) copyCell(row.getCell(c), row, c + 1);
            copyCell(null, row, at);
        }
        row(sh, a.getRow()).createCell(at).setCellValue(columnName);
        t.createColumn(columnName, pos);
        t.setArea(new AreaReference(a, new CellReference(b.getRow(), b.getCol() + 1), SpreadsheetVersion.EXCEL2007));
        t.updateHeaders();
        return Map.of();
    }

    @Action(id = "delete_table_column", name = "Delete table column", category = "Tables", description = "Remove a column from an Excel table and move the table cells to its right back by one.")
    public Map<String, Object> deleteTableColumn(ActionContext ctx,
            @Input(name = "table", type = OoaType.STRING, description = "Excel table name, e.g. Table1.") String tableName,
            @Input(name = "column_name", type = OoaType.STRING, description = "Header of the column to remove.") String columnName,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"excel\"", description = "Workbook session.") String session) {
        XSSFWorkbook wb = xlsx(session(ctx, session), "Excel tables");
        XSSFTable t = table(wb, tableName);
        int idx = t.findColumnIndex(columnName);
        if (idx < 0) throw new ActionException("EXCEL.BAD_COLUMN", "Table '" + tableName + "' has no column '" + columnName + "'", "Check the header text.");
        if (t.getColumnCount() == 1) throw new ActionException("EXCEL.LAST_COLUMN", "A table must keep at least one column", "Delete the whole table range instead.");
        XSSFSheet sh = t.getXSSFSheet();
        CellReference a = t.getStartCellReference(), b = t.getEndCellReference();
        int at = a.getCol() + idx;
        for (int r = a.getRow(); r <= b.getRow(); r++) {
            Row row = row(sh, r);
            for (int c = at; c < b.getCol(); c++) copyCell(row.getCell(c + 1), row, c);
            copyCell(null, row, b.getCol());
        }
        t.removeColumn(idx);
        t.setArea(new AreaReference(a, new CellReference(b.getRow(), b.getCol() - 1), SpreadsheetVersion.EXCEL2007));
        t.updateHeaders();
        return Map.of();
    }

    // ------------------------------------------------------------------------------------------------
    // Links to other workbooks
    // ------------------------------------------------------------------------------------------------

    private static final java.util.regex.Pattern EXTERNAL = java.util.regex.Pattern.compile("\\[[^\\]]+\\]");

    private static List<Cell> externalFormulaCells(Workbook wb) {
        List<Cell> out = new ArrayList<>();
        for (int i = 0; i < wb.getNumberOfSheets(); i++) for (Row r : wb.getSheetAt(i)) for (Cell c : r)
            if (c.getCellType() == CellType.FORMULA && EXTERNAL.matcher(c.getCellFormula()).find()) out.add(c);
        return out;
    }

    @Action(id = "get_links", name = "Get workbook links", category = "Links", idempotent = true, description = "List the other workbooks this workbook's formulas refer to and the cells that use them.")
    @Output(name = "links", type = OoaType.LIST, description = "Linked workbook file names.")
    @Output(name = "cells", type = OoaType.LIST, description = "Cells with external formulas, as Sheet!A1.")
    public Map<String, Object> getLinks(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"excel\"", description = "Workbook session.") String session) {
        WorkbookSession s = session(ctx, session);
        Set<Object> links = new LinkedHashSet<>();
        if (s.wb instanceof XSSFWorkbook x) for (ExternalLinksTable t : x.getExternalLinksTable()) links.add(t.getLinkedFileName());
        List<Object> cells = new ArrayList<>();
        for (Cell c : externalFormulaCells(s.wb)) {
            cells.add(c.getSheet().getSheetName() + "!" + addr(c.getRowIndex(), c.getColumnIndex()));
            if (!(s.wb instanceof XSSFWorkbook)) {
                var m = EXTERNAL.matcher(c.getCellFormula());
                while (m.find()) links.add(m.group().substring(1, m.group().length() - 1));
            }
        }
        return out("links", new ArrayList<>(links), "cells", cells);
    }

    @Action(id = "break_links", name = "Break workbook links", category = "Links",
            description = "Replace every formula that refers to another workbook with its last calculated value.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of formulas replaced by values.")
    public Map<String, Object> breakLinks(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"excel\"", description = "Workbook session.") String session) {
        WorkbookSession s = session(ctx, session);
        List<Cell> cells = externalFormulaCells(s.wb);
        for (Cell c : cells) c.removeFormula();  // keeps the cached result as a plain value
        if (s.wb instanceof XSSFWorkbook x && x.getCTWorkbook().isSetExternalReferences()) x.getCTWorkbook().unsetExternalReferences();
        return out("count", n(cells.size()));
    }

    // ------------------------------------------------------------------------------------------------
    // Sensitivity labels (Microsoft Information Protection custom properties)
    // ------------------------------------------------------------------------------------------------

    @Action(id = "get_sensitivity_label", name = "Get sensitivity label", category = "Sensitivity", idempotent = true,
            description = "Read the sensitivity label stored in the workbook's MSIP_Label custom document properties.")
    @Output(name = "label_id", type = OoaType.STRING, description = "Label GUID; empty when no label is set.")
    @Output(name = "name", type = OoaType.STRING, description = "Label name as stored in the file.")
    @Output(name = "site_id", type = OoaType.STRING, description = "Tenant (site) id the label belongs to.")
    @Output(name = "method", type = OoaType.STRING, description = "How it was applied: Standard or Privileged.")
    @Output(name = "has_label", type = OoaType.BOOLEAN, description = "True when an enabled label was found.")
    public Map<String, Object> getSensitivityLabel(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"excel\"", description = "Workbook session.") String session) {
        XSSFWorkbook wb = xlsx(session(ctx, session), "Sensitivity labels");
        return Msip.read(wb.getProperties().getCustomProperties());
    }

    @Action(id = "set_sensitivity_label", name = "Set sensitivity label", category = "Sensitivity",
            description = "Write a sensitivity label into the workbook's MSIP_Label custom properties, replacing any earlier label; this marks the file but does not encrypt it.")
    public Map<String, Object> setSensitivityLabel(ActionContext ctx,
            @Input(name = "label_id", type = OoaType.STRING, description = "Label GUID from your organisation's compliance centre.") String labelId,
            @Input(name = "site_id", type = OoaType.STRING, description = "Tenant (site) GUID the label belongs to.") String siteId,
            @Input(name = "name", type = OoaType.STRING, required = false, description = "Label display name, e.g. Confidential.") String name,
            @Input(name = "method", type = OoaType.STRING, required = false, defaultValue = "\"Standard\"", enumValues = {"Standard", "Privileged"}, description = "Standard for a default label, Privileged for one chosen by a person.") String method,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"excel\"", description = "Workbook session.") String session) {
        XSSFWorkbook wb = xlsx(session(ctx, session), "Sensitivity labels");
        Msip.write(wb.getProperties().getCustomProperties(), labelId, siteId, name, method, "EXCEL");
        return Map.of();
    }

    // ------------------------------------------------------------------------------------------------
    // Save as
    // ------------------------------------------------------------------------------------------------

    @Action(id = "save_as", name = "Save as", category = "Workbook", capabilities = "filesystem",
            description = "Save the workbook to a new file as xlsx, xls or csv; after xlsx or xls the session continues on the new file.")
    public Map<String, Object> saveAs(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "Target file path.") String path,
            @Input(name = "format", type = OoaType.STRING, required = false, enumValues = {"xlsx", "xls", "csv"}, description = "File format; taken from the file extension when empty.") String format,
            @Input(name = "sheet", type = OoaType.STRING, required = false, description = "Sheet written for csv; active sheet when empty.") String sheet,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"excel\"", description = "Workbook session.") String session) {
        WorkbookSession s = session(ctx, session);
        Path target = ctx.resolvePath(path);
        String fmt = format == null || format.isBlank() ? extension(target) : format.toLowerCase(Locale.ROOT);
        try {
            if (target.getParent() != null) Files.createDirectories(target.getParent());
            switch (fmt) {
                case "csv" -> {
                    Sheet sh = sheet(s, sheet);
                    FormulaEvaluator ev = evaluator(s);
                    StringBuilder sb = new StringBuilder();
                    int width = lastCol(sh) + 1;
                    for (int r = 0; r <= sh.getLastRowNum(); r++) {
                        Row row = sh.getRow(r);
                        for (int c = 0; c < width; c++) {
                            if (c > 0) sb.append(',');
                            Object v = row == null ? null : read(row.getCell(c), ev);
                            String t = v == null ? "" : Values.text(v);
                            sb.append(t.matches(".*[\",\\r\\n].*") || t.startsWith(" ") || t.endsWith(" ") ? "\"" + t.replace("\"", "\"\"") + "\"" : t);
                        }
                        sb.append("\r\n");
                    }
                    Files.writeString(target, sb.toString(), java.nio.charset.StandardCharsets.UTF_8);
                }
                case "xlsx", "xls" -> {
                    boolean wantX = fmt.equals("xlsx");
                    Workbook wb = s.wb;
                    if (wantX != (wb instanceof XSSFWorkbook)) {
                        wb = wantX ? new XSSFWorkbook() : new org.apache.poi.hssf.usermodel.HSSFWorkbook();
                        for (int i = 0; i < s.wb.getNumberOfSheets(); i++) {
                            Sheet dst = wb.createSheet(s.wb.getSheetName(i));
                            copySheetInto(s.wb.getSheetAt(i), dst);
                            if (s.wb.isSheetHidden(i)) wb.setSheetHidden(i, true);
                        }
                        wb.setActiveSheet(s.wb.getActiveSheetIndex());
                    }
                    Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
                    try (OutputStream o = Files.newOutputStream(tmp)) { wb.write(o); }
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                    if (wb != s.wb) { try { s.wb.close(); } catch (IOException ignored) { } s.wb = wb; }
                    s.path = target;
                }
                default -> throw new ActionException("EXCEL.BAD_FORMAT", "Unsupported format '" + fmt + "'", "Use xlsx, xls or csv.");
            }
        } catch (IOException e) {
            throw new ActionException("EXCEL.SAVE_FAILED", "Cannot save " + target + ": " + e.getMessage(), "Close the file if it is open in Excel.", true);
        }
        return Map.of();
    }

    private static String extension(Path p) {
        String n = p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot < 0 ? "xlsx" : n.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
