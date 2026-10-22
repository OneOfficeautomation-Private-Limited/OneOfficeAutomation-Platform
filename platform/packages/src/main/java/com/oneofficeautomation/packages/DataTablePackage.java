package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;

import java.math.BigDecimal;
import java.util.*;

/** In-memory tables: {"columns": [...], "rows": [[...], ...]}. Actions return new tables. */
@OoaPackage(id = "ooa.datatable", name = "Data Table", version = "1.0.0",
        description = "Build, filter, sort, search and change in-memory tables.")
public final class DataTablePackage {
    private static final String[] OPS = {"eq", "ne", "gt", "ge", "lt", "le", "contains", "starts_with", "empty", "not_empty"};

    @SuppressWarnings("unchecked")
    static List<String> cols(Map<String, Object> t) { return (List<String>) (List<?>) t.get("columns"); }

    @SuppressWarnings("unchecked")
    static List<List<Object>> rows(Map<String, Object> t) { return (List<List<Object>>) (List<?>) t.get("rows"); }

    static int col(Map<String, Object> t, String name) {
        int i = cols(t).indexOf(name);
        if (i < 0) throw new ActionException("TABLE.NO_COLUMN", "Column '" + name + "' not found", "Columns: " + String.join(", ", cols(t)) + ".");
        return i;
    }

    static Map<String, Object> copy(Map<String, Object> t, List<List<Object>> newRows) {
        List<Object> rs = new ArrayList<>();
        for (List<Object> r : newRows) rs.add(new ArrayList<>(r));
        return Values.table(new ArrayList<>(cols(t)), rs);
    }

    static Object cell(List<Object> row, int i) { return i < row.size() ? row.get(i) : null; }

    /** Column chosen by name, or by zero-based index when no name is given. */
    static int col(Map<String, Object> t, String name, Integer index) {
        if (name != null && !name.isBlank()) return col(t, name);
        if (index == null) throw new ActionException("TABLE.NO_COLUMN", "Give a column name or a column index", "Fill in 'column' or 'column_index'.");
        if (index < 0 || index >= cols(t).size())
            throw new ActionException("TABLE.NO_COLUMN", "Column index " + index + " is outside the table (" + cols(t).size() + " columns)", "Column indexes start at 0.");
        return index;
    }

    private static final String COL_INDEX = "Zero-based column index; used when 'column' is empty.";

    @Action(id = "from_records", name = "Create from records", category = "Table", idempotent = true, description = "Build a table from a list of dictionaries.")
    @Output(name = "table", type = OoaType.TABLE, description = "New table.")
    public Map<String, Object> fromRecords(ActionContext ctx, @Input(name = "records", type = OoaType.LIST, description = "List of dictionaries.") List<Object> records) {
        return Out.of("table", Values.coerce(records, "Table", "records"));
    }

    @Action(id = "to_records", name = "Convert to records", category = "Table", idempotent = true, description = "Turn each row into a dictionary keyed by column name.")
    @Output(name = "records", type = OoaType.LIST, description = "List of dictionaries.")
    public Map<String, Object> toRecords(ActionContext ctx, @Input(name = "table", type = OoaType.TABLE, description = "Source table.") Map<String, Object> table) {
        List<Object> out = new ArrayList<>();
        for (List<Object> r : rows(table)) {
            Map<String, Object> rec = new LinkedHashMap<>();
            for (int i = 0; i < cols(table).size(); i++) rec.put(cols(table).get(i), cell(r, i));
            out.add(rec);
        }
        return Out.of("records", out);
    }

    @Action(id = "filter", name = "Filter rows", category = "Table", idempotent = true, description = "Keep rows where a column satisfies a comparison.")
    @Output(name = "table", type = OoaType.TABLE, description = "Filtered table.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Rows kept.")
    public Map<String, Object> filter(ActionContext ctx,
            @Input(name = "table", type = OoaType.TABLE, description = "Source table.") Map<String, Object> table,
            @Input(name = "column", type = OoaType.STRING, description = "Column name.") String column,
            @Input(name = "operator", type = OoaType.STRING, enumValues = {"eq", "ne", "gt", "ge", "lt", "le", "contains", "not_contains", "starts_with", "ends_with", "matches", "empty", "not_empty"},
                    description = "Comparison; matches takes a regular expression.") String op,
            @Input(name = "value", required = false, description = "Value to compare with (not needed for empty/not_empty).") Object value,
            @Input(name = "match_case", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Compare text with upper/lower case taken into account.") boolean matchCase) {
        int c = col(table, column);
        List<List<Object>> kept = new ArrayList<>();
        for (List<Object> r : rows(table)) if (matches(cell(r, c), op, value, matchCase)) kept.add(r);
        return Out.of("table", copy(table, kept), "count", BigDecimal.valueOf(kept.size()));
    }

    static boolean matches(Object cell, String op, Object value) { return matches(cell, op, value, false); }

    static boolean matches(Object cell, String op, Object value, boolean matchCase) {
        java.util.function.Function<Object, String> t = v -> matchCase ? Values.text(v) : Values.text(v).toLowerCase(Locale.ROOT);
        switch (op) {
            case "empty": return cell == null || "".equals(cell);
            case "not_empty": return !(cell == null || "".equals(cell));
            case "contains": return t.apply(cell).contains(t.apply(value));
            case "not_contains": return !t.apply(cell).contains(t.apply(value));
            case "starts_with": return t.apply(cell).startsWith(t.apply(value));
            case "ends_with": return t.apply(cell).endsWith(t.apply(value));
            case "matches":
                try { return java.util.regex.Pattern.compile(Values.text(value), matchCase ? 0 : java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE).matcher(Values.text(cell)).find(); }
                catch (java.util.regex.PatternSyntaxException e) { throw new ActionException("TABLE.BAD_REGEX", "Invalid regular expression: " + e.getDescription(), "Escape special characters such as ( ) [ ] . *"); }
            default:
                int r = ListPackage.compare(cell, value, !matchCase);
                return switch (op) { case "eq" -> r == 0; case "ne" -> r != 0; case "gt" -> r > 0; case "ge" -> r >= 0; case "lt" -> r < 0; default -> r <= 0; };
        }
    }

    @Action(id = "sort", name = "Sort", category = "Table", idempotent = true, description = "Sort rows by a column.")
    @Output(name = "table", type = OoaType.TABLE, description = "Sorted table.")
    public Map<String, Object> sort(ActionContext ctx,
            @Input(name = "table", type = OoaType.TABLE, description = "Source table.") Map<String, Object> table,
            @Input(name = "column", type = OoaType.STRING, required = false, description = "Column name.") String column,
            @Input(name = "descending", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Largest first.") boolean desc,
            @Input(name = "column_index", type = OoaType.NUMBER, required = false, description = COL_INDEX) Integer columnIndex,
            @Input(name = "ignore_case", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Sort text without regard to upper/lower case.") boolean ignoreCase,
            @Input(name = "then_by", type = OoaType.LIST, required = false, description = "Further column names that order rows with equal values, in the same direction.") List<Object> thenBy) {
        List<Integer> keys = new ArrayList<>();
        keys.add(col(table, column, columnIndex));
        if (thenBy != null) for (Object k : thenBy) keys.add(col(table, Values.text(k)));
        List<List<Object>> rs = new ArrayList<>(rows(table));
        Comparator<List<Object>> cmp = (a, b) -> {
            for (int c : keys) { int r = ListPackage.compare(cell(a, c), cell(b, c), ignoreCase); if (r != 0) return r; }
            return 0;
        };
        rs.sort(desc ? cmp.reversed() : cmp);
        return Out.of("table", copy(table, rs));
    }

    @Action(id = "get_value", name = "Get value", category = "Table", idempotent = true, description = "Read one cell by zero-based row number and column name.")
    @Output(name = "value", description = "Cell value.")
    public Map<String, Object> getValue(ActionContext ctx,
            @Input(name = "table", type = OoaType.TABLE, description = "Source table.") Map<String, Object> table,
            @Input(name = "row", type = OoaType.NUMBER, description = "Zero-based row number.") int row,
            @Input(name = "column", type = OoaType.STRING, required = false, description = "Column name.") String column,
            @Input(name = "column_index", type = OoaType.NUMBER, required = false, description = COL_INDEX) Integer columnIndex) {
        int c = col(table, column, columnIndex);
        if (row < 0 || row >= rows(table).size()) throw new ActionException("TABLE.NO_ROW", "Row " + row + " does not exist (table has " + rows(table).size() + " rows)", "Row numbers start at 0.");
        return Out.of("value", cell(rows(table).get(row), c));
    }

    @Action(id = "set_value", name = "Set value", category = "Table", idempotent = true, description = "Change one cell.")
    @Output(name = "table", type = OoaType.TABLE, description = "Updated table.")
    public Map<String, Object> setValue(ActionContext ctx,
            @Input(name = "table", type = OoaType.TABLE, description = "Source table.") Map<String, Object> table,
            @Input(name = "row", type = OoaType.NUMBER, description = "Zero-based row number.") int row,
            @Input(name = "column", type = OoaType.STRING, required = false, description = "Column name.") String column,
            @Input(name = "value", required = false, description = "New value.") Object value,
            @Input(name = "column_index", type = OoaType.NUMBER, required = false, description = COL_INDEX) Integer columnIndex) {
        int c = col(table, column, columnIndex);
        Map<String, Object> out = copy(table, rows(table));
        if (row < 0 || row >= rows(out).size()) throw new ActionException("TABLE.NO_ROW", "Row " + row + " does not exist");
        List<Object> r = rows(out).get(row);
        while (r.size() <= c) r.add(null);
        r.set(c, value);
        return Out.of("table", out);
    }

    @Action(id = "add_row", name = "Add row", category = "Table", idempotent = true, description = "Append a row given as a dictionary of column values.")
    @Output(name = "table", type = OoaType.TABLE, description = "Updated table.")
    public Map<String, Object> addRow(ActionContext ctx,
            @Input(name = "table", type = OoaType.TABLE, description = "Source table.") Map<String, Object> table,
            @Input(name = "values", type = OoaType.DICTIONARY, description = "Column name to value.") Map<String, Object> values) {
        for (String k : values.keySet()) col(table, k);
        Map<String, Object> out = copy(table, rows(table));
        List<Object> r = new ArrayList<>();
        for (String c : cols(table)) r.add(values.get(c));
        rows(out).add(r);
        return Out.of("table", out);
    }

    @Action(id = "remove_duplicates", name = "Remove duplicate rows", category = "Table", idempotent = true,
            description = "Keep the first row for each distinct value of the chosen columns (all columns when empty).")
    @Output(name = "table", type = OoaType.TABLE, description = "Table without duplicates.")
    public Map<String, Object> removeDuplicates(ActionContext ctx,
            @Input(name = "table", type = OoaType.TABLE, description = "Source table.") Map<String, Object> table,
            @Input(name = "columns", type = OoaType.LIST, required = false, description = "Columns that define a duplicate.") List<Object> columns) {
        List<Integer> idx = new ArrayList<>();
        if (columns == null || columns.isEmpty()) for (int i = 0; i < cols(table).size(); i++) idx.add(i);
        else for (Object c : columns) idx.add(col(table, Values.text(c)));
        Set<String> seen = new HashSet<>();
        List<List<Object>> kept = new ArrayList<>();
        for (List<Object> r : rows(table)) {
            List<Object> key = new ArrayList<>();
            for (int i : idx) key.add(Values.toJsonable(cell(r, i)) instanceof BigDecimal d ? d.stripTrailingZeros() : cell(r, i));
            if (seen.add(JsonLite.canonical(key))) kept.add(r);
        }
        return Out.of("table", copy(table, kept));
    }

    @Action(id = "row_count", name = "Row count", category = "Table", idempotent = true, description = "Number of rows.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of rows.")
    public Map<String, Object> rowCount(ActionContext ctx, @Input(name = "table", type = OoaType.TABLE, description = "Source table.") Map<String, Object> table) {
        return Out.of("count", BigDecimal.valueOf(rows(table).size()));
    }

    @Action(id = "sum_column", name = "Sum column", category = "Table", idempotent = true, description = "Add up the numeric values in a column; empty cells are ignored.")
    @Output(name = "total", type = OoaType.NUMBER, description = "Sum.")
    public Map<String, Object> sumColumn(ActionContext ctx,
            @Input(name = "table", type = OoaType.TABLE, description = "Source table.") Map<String, Object> table,
            @Input(name = "column", type = OoaType.STRING, description = "Column name.") String column) {
        int c = col(table, column);
        BigDecimal t = BigDecimal.ZERO;
        for (List<Object> r : rows(table)) { Object v = cell(r, c); if (v != null && !"".equals(v)) t = t.add(Values.toNumber(v)); }
        return Out.of("total", t);
    }

    @Condition(id = "is_empty", name = "Table is empty", description = "True when the table has no rows.")
    public boolean isEmpty(ActionContext ctx, @Input(name = "table", type = OoaType.TABLE, description = "Table.") Map<String, Object> table) {
        return rows(table).isEmpty();
    }

    private static boolean countIs(int actual, String op, int expected) {
        return switch (op == null ? "eq" : op) {
            case "eq" -> actual == expected; case "ne" -> actual != expected; case "gt" -> actual > expected;
            case "ge" -> actual >= expected; case "lt" -> actual < expected; case "le" -> actual <= expected;
            default -> throw new ActionException("TABLE.BAD_OPERATOR", "Unknown operator '" + op + "'", "Use eq, ne, gt, ge, lt or le.");
        };
    }

    @Condition(id = "row_count_is", name = "Row count is", description = "Compare the number of rows with a number, e.g. more than 0 rows.")
    public boolean rowCountIs(ActionContext ctx,
            @Input(name = "table", type = OoaType.TABLE, description = "Table.") Map<String, Object> table,
            @Input(name = "operator", type = OoaType.STRING, required = false, defaultValue = "\"eq\"", enumValues = {"eq", "ne", "gt", "ge", "lt", "le"}, description = "Comparison.") String op,
            @Input(name = "count", type = OoaType.NUMBER, description = "Number to compare with.") int count) {
        return countIs(rows(table).size(), op, count);
    }

    @Condition(id = "column_count_is", name = "Column count is", description = "Compare the number of columns with a number.")
    public boolean columnCountIs(ActionContext ctx,
            @Input(name = "table", type = OoaType.TABLE, description = "Table.") Map<String, Object> table,
            @Input(name = "operator", type = OoaType.STRING, required = false, defaultValue = "\"eq\"", enumValues = {"eq", "ne", "gt", "ge", "lt", "le"}, description = "Comparison.") String op,
            @Input(name = "count", type = OoaType.NUMBER, description = "Number to compare with.") int count) {
        return countIs(cols(table).size(), op, count);
    }

    @Condition(id = "has_column", name = "Table has column", description = "True when the table has a column with this name.")
    public boolean hasColumn(ActionContext ctx,
            @Input(name = "table", type = OoaType.TABLE, description = "Table.") Map<String, Object> table,
            @Input(name = "column", type = OoaType.STRING, description = "Column name.") String column) {
        return cols(table).contains(column);
    }

    @Condition(id = "contains_value", name = "Column contains value", description = "True when any row has this value in the column.")
    public boolean containsValue(ActionContext ctx,
            @Input(name = "table", type = OoaType.TABLE, description = "Table.") Map<String, Object> table,
            @Input(name = "column", type = OoaType.STRING, description = "Column name.") String column,
            @Input(name = "value", description = "Value to look for.") Object value) {
        int c = col(table, column);
        return rows(table).stream().anyMatch(r -> matches(cell(r, c), "eq", value));
    }

    // ---- Rows and columns ----

    private static List<Object> rowFrom(Map<String, Object> table, Map<String, Object> values) {
        List<Object> r = new ArrayList<>();
        if (values != null) for (String k : values.keySet()) col(table, k);
        for (String c : cols(table)) r.add(values == null ? null : values.get(c));
        return r;
    }

    private static Object keyOf(Object v) {
        Object j = Values.toJsonable(v);
        return j instanceof BigDecimal d ? d.stripTrailingZeros() : j;
    }

    @Action(id = "insert_row", name = "Insert row", category = "Table", idempotent = true,
            description = "Insert a row, given as a dictionary of column values, at a zero-based position.")
    @Output(name = "table", type = OoaType.TABLE, description = "Updated table.")
    public Map<String, Object> insertRow(ActionContext ctx,
            @Input(name = "table", type = OoaType.TABLE, description = "Source table.") Map<String, Object> table,
            @Input(name = "row", type = OoaType.NUMBER, required = false, description = "Zero-based position for the new row; at the end when empty.") Integer rowAt,
            @Input(name = "values", type = OoaType.DICTIONARY, required = false, description = "Column name to value; missing columns stay empty.") Map<String, Object> values) {
        int n = rows(table).size();
        int row = rowAt == null ? n : rowAt;
        if (row < 0 || row > n) throw new ActionException("TABLE.NO_ROW", "Row position " + row + " is outside the table (" + n + " rows)", "Use a position from 0 to " + n + ".");
        Map<String, Object> out = copy(table, rows(table));
        rows(out).add(row, rowFrom(table, values));
        return Out.of("table", out);
    }

    @Action(id = "delete_row", name = "Delete rows", category = "Table", idempotent = true,
            description = "Delete the row at a position, or every row where a column equals a value.")
    @Output(name = "table", type = OoaType.TABLE, description = "Updated table.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Rows deleted.")
    public Map<String, Object> deleteRow(ActionContext ctx,
            @Input(name = "table", type = OoaType.TABLE, description = "Source table.") Map<String, Object> table,
            @Input(name = "row", type = OoaType.NUMBER, required = false, description = "Zero-based row to delete.") Integer row,
            @Input(name = "column", type = OoaType.STRING, required = false, description = "Column to match (used when row is empty).") String column,
            @Input(name = "value", required = false, description = "Delete rows whose column equals this value.") Object value) {
        List<List<Object>> kept = new ArrayList<>(rows(table));
        int before = kept.size();
        if (row != null) {
            if (row < 0 || row >= before) throw new ActionException("TABLE.NO_ROW", "Row " + row + " does not exist (table has " + before + " rows)", "Row numbers start at 0.");
            kept.remove((int) row);
        } else if (column != null && !column.isBlank()) {
            int c = col(table, column);
            kept.removeIf(r -> matches(cell(r, c), "eq", value));
        } else throw new ActionException("TABLE.NOTHING_TO_DELETE", "Give a row number, or a column and value", "Fill in 'row' or 'column' and 'value'.");
        return Out.of("table", copy(table, kept), "count", BigDecimal.valueOf(before - kept.size()));
    }

    @Action(id = "insert_column", name = "Insert column", category = "Table", idempotent = true,
            description = "Add a column, filled with a default value, at the end or at a zero-based position.")
    @Output(name = "table", type = OoaType.TABLE, description = "Updated table.")
    public Map<String, Object> insertColumn(ActionContext ctx,
            @Input(name = "table", type = OoaType.TABLE, description = "Source table.") Map<String, Object> table,
            @Input(name = "name", type = OoaType.STRING, description = "New column name.") String name,
            @Input(name = "default_value", required = false, description = "Value for every existing row; empty when not set.") Object def,
            @Input(name = "position", type = OoaType.NUMBER, required = false, description = "Zero-based column position; at the end when empty.") Integer position) {
        if (name == null || name.isBlank()) throw new ActionException("TABLE.BAD_COLUMN_NAME", "Column name is empty", "Give the new column a name.");
        if (cols(table).contains(name)) throw new ActionException("TABLE.COLUMN_EXISTS", "Column '" + name + "' already exists", "Choose a different name.");
        int width = cols(table).size();
        int at = position == null ? width : position;
        if (at < 0 || at > width) throw new ActionException("TABLE.BAD_POSITION", "Column position " + at + " is outside 0.." + width, "Use a position from 0 to " + width + ".");
        List<String> newCols = new ArrayList<>(cols(table));
        newCols.add(at, name);
        List<Object> rs = new ArrayList<>();
        for (List<Object> r : rows(table)) {
            List<Object> nr = new ArrayList<>(r);
            while (nr.size() < width) nr.add(null);
            nr.add(at, def);
            rs.add(nr);
        }
        return Out.of("table", Values.table(newCols, rs));
    }

    @Action(id = "delete_column", name = "Delete column", category = "Table", idempotent = true, description = "Remove a column and its values.")
    @Output(name = "table", type = OoaType.TABLE, description = "Updated table.")
    public Map<String, Object> deleteColumn(ActionContext ctx,
            @Input(name = "table", type = OoaType.TABLE, description = "Source table.") Map<String, Object> table,
            @Input(name = "column", type = OoaType.STRING, required = false, description = "Column to remove.") String column,
            @Input(name = "column_index", type = OoaType.NUMBER, required = false, description = COL_INDEX) Integer columnIndex) {
        int c = col(table, column, columnIndex);
        List<String> newCols = new ArrayList<>(cols(table));
        newCols.remove(c);
        List<Object> rs = new ArrayList<>();
        for (List<Object> r : rows(table)) { List<Object> nr = new ArrayList<>(r); if (c < nr.size()) nr.remove(c); rs.add(nr); }
        return Out.of("table", Values.table(newCols, rs));
    }

    @Action(id = "column_count", name = "Column count", category = "Table", idempotent = true, description = "Number of columns.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of columns.")
    public Map<String, Object> columnCount(ActionContext ctx, @Input(name = "table", type = OoaType.TABLE, description = "Source table.") Map<String, Object> table,
            @Input(name = "non_empty_only", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Count only columns with at least one value.") boolean nonEmptyOnly) {
        if (!nonEmptyOnly) return Out.of("count", BigDecimal.valueOf(cols(table).size()));
        int n = 0;
        for (int c = 0; c < cols(table).size(); c++) { final int ci = c; if (rows(table).stream().anyMatch(r -> !matches(cell(r, ci), "empty", null))) n++; }
        return Out.of("count", BigDecimal.valueOf(n));
    }

    @Action(id = "column_name", name = "Column name", category = "Table", idempotent = true, description = "Return the name of the column at a zero-based index.")
    @Output(name = "name", type = OoaType.STRING, description = "Column name.")
    public Map<String, Object> columnName(ActionContext ctx,
            @Input(name = "table", type = OoaType.TABLE, description = "Source table.") Map<String, Object> table,
            @Input(name = "column_index", type = OoaType.NUMBER, description = "Zero-based column index.") int index) {
        return Out.of("name", cols(table).get(col(table, null, index)));
    }

    @Action(id = "rename_column", name = "Rename column", category = "Table", idempotent = true, description = "Give a column a new name; its values stay as they are.")
    @Output(name = "table", type = OoaType.TABLE, description = "Updated table.")
    public Map<String, Object> renameColumn(ActionContext ctx,
            @Input(name = "table", type = OoaType.TABLE, description = "Source table.") Map<String, Object> table,
            @Input(name = "column", type = OoaType.STRING, required = false, description = "Current column name.") String column,
            @Input(name = "new_name", type = OoaType.STRING, description = "New column name.") String newName,
            @Input(name = "column_index", type = OoaType.NUMBER, required = false, description = COL_INDEX) Integer columnIndex) {
        int c = col(table, column, columnIndex);
        if (newName == null || newName.isBlank()) throw new ActionException("TABLE.BAD_COLUMN_NAME", "The new column name is empty", "Give the column a name.");
        if (cols(table).contains(newName) && !cols(table).get(c).equals(newName)) throw new ActionException("TABLE.COLUMN_EXISTS", "Column '" + newName + "' already exists", "Choose a different name.");
        Map<String, Object> out = copy(table, rows(table));
        @SuppressWarnings("unchecked") List<Object> names = (List<Object>) out.get("columns");
        names.set(c, newName);
        return Out.of("table", out);
    }

    @Action(id = "column_names", name = "Column names", category = "Table", idempotent = true, description = "List the column names in order.")
    @Output(name = "names", type = OoaType.LIST, description = "Column names.")
    public Map<String, Object> columnNames(ActionContext ctx, @Input(name = "table", type = OoaType.TABLE, description = "Source table.") Map<String, Object> table) {
        return Out.of("names", new ArrayList<Object>(cols(table)));
    }

    @Action(id = "change_column_type", name = "Change column type", category = "Table", idempotent = true,
            description = "Convert every value in a column to String, Number, DateTime or Boolean; empty cells stay empty.")
    @Output(name = "table", type = OoaType.TABLE, description = "Updated table.")
    public Map<String, Object> changeColumnType(ActionContext ctx,
            @Input(name = "table", type = OoaType.TABLE, description = "Source table.") Map<String, Object> table,
            @Input(name = "column", type = OoaType.STRING, required = false, description = "Column to convert.") String column,
            @Input(name = "type", type = OoaType.STRING, enumValues = {"String", "Number", "DateTime", "Boolean"}, description = "Target type.") String type,
            @Input(name = "column_index", type = OoaType.NUMBER, required = false, description = COL_INDEX) Integer columnIndex,
            @Input(name = "date_format", type = OoaType.STRING, required = false, description = "Pattern of the text when converting to DateTime, e.g. dd/MM/yyyy; ISO dates when empty.") String dateFormat) {
        int c = col(table, column, columnIndex);
        if (column == null || column.isBlank()) column = cols(table).get(c);
        if (!List.of("String", "Number", "DateTime", "Boolean").contains(type))
            throw new ActionException("TABLE.BAD_TYPE", "Unknown column type '" + type + "'", "Use String, Number, DateTime or Boolean.");
        Map<String, Object> out = copy(table, rows(table));
        List<List<Object>> rs = rows(out);
        for (int i = 0; i < rs.size(); i++) {
            List<Object> r = rs.get(i);
            Object v = cell(r, c);
            if (c >= r.size()) continue;
            if (v == null || (!type.equals("String") && v instanceof String s && s.isBlank())) { r.set(c, null); continue; }
            Object conv;
            try {
                if (type.equals("DateTime") && dateFormat != null && !dateFormat.isBlank() && v instanceof String s) {
                    java.time.format.DateTimeFormatter f = java.time.format.DateTimeFormatter.ofPattern(dateFormat, Locale.ENGLISH);
                    try { conv = dateFormat.contains("H") || dateFormat.contains("h") ? java.time.LocalDateTime.parse(s.trim(), f) : java.time.LocalDate.parse(s.trim(), f).atStartOfDay(); }
                    catch (RuntimeException e) { throw new OoaException("VAR.TYPE_MISMATCH", "not a date"); }
                    r.set(c, conv);
                    continue;
                }
                conv = Values.coerce(v, type, column);
                if (type.equals("Boolean") && !(conv instanceof Boolean)) throw new OoaException("VAR.TYPE_MISMATCH", "not a Boolean");
            } catch (OoaException e) {
                throw new ActionException("TABLE.CONVERT_FAILED", "Row " + i + ", column '" + column + "': cannot convert " + Values.safeRepr(v) + " to " + type,
                        type.equals("Number") ? "Clean the value so it is numeric, e.g. 1234.50." : type.equals("DateTime") ? "Use ISO dates such as 2026-10-18 or 2026-10-18T14:30:00."
                                : type.equals("Boolean") ? "Use true/false, yes/no or 1/0." : "Check the value.");
            }
            r.set(c, conv);
        }
        return Out.of("table", out);
    }

    @Action(id = "clear", name = "Clear rows", category = "Table", idempotent = true, description = "Remove every row but keep the columns.")
    @Output(name = "table", type = OoaType.TABLE, description = "Empty table with the same columns.")
    public Map<String, Object> clear(ActionContext ctx, @Input(name = "table", type = OoaType.TABLE, description = "Source table.") Map<String, Object> table) {
        return Out.of("table", copy(table, List.of()));
    }

    // ---- Combining tables ----

    @Action(id = "join", name = "Join tables", category = "Table", idempotent = true,
            description = "Combine two tables on matching key columns; inner keeps matches only, left keeps every row of the first table.")
    @Output(name = "table", type = OoaType.TABLE, description = "Joined table: first table columns, then the second table's other columns.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Rows in the result.")
    public Map<String, Object> join(ActionContext ctx,
            @Input(name = "table", type = OoaType.TABLE, description = "First (left) table.") Map<String, Object> left,
            @Input(name = "other", type = OoaType.TABLE, description = "Second (right) table.") Map<String, Object> right,
            @Input(name = "key_columns", type = OoaType.LIST, description = "Columns present in both tables that must match.") List<Object> keys,
            @Input(name = "join_type", type = OoaType.STRING, required = false, defaultValue = "\"inner\"", enumValues = {"inner", "left", "right", "full"},
                    description = "inner keeps matches only; left keeps every row of the first table, right every row of the second, full every row of both.") String joinType,
            @Input(name = "other_key_columns", type = OoaType.LIST, required = false,
                    description = "Key columns of the second table when their names differ, in the same order as key_columns.") List<Object> otherKeys) {
        if (keys == null || keys.isEmpty()) throw new ActionException("TABLE.NO_KEYS", "No key columns given", "List the columns to match, e.g. [\"id\"].");
        String jt = joinType == null ? "inner" : joinType;
        if (!List.of("inner", "left", "right", "full").contains(jt)) throw new ActionException("TABLE.BAD_JOIN_TYPE", "Unknown join type '" + jt + "'", "Use inner, left, right or full.");
        List<Object> rkeys = otherKeys == null || otherKeys.isEmpty() ? keys : otherKeys;
        if (rkeys.size() != keys.size()) throw new ActionException("TABLE.NO_KEYS", "key_columns and other_key_columns have different lengths", "Give one second-table column for each key column.");
        List<Integer> lk = new ArrayList<>(), rk = new ArrayList<>();
        for (Object k : keys) lk.add(col(left, Values.text(k)));
        for (Object k : rkeys) rk.add(col(right, Values.text(k)));
        List<Integer> rOther = new ArrayList<>();
        List<String> outCols = new ArrayList<>(cols(left));
        for (int i = 0; i < cols(right).size(); i++) {
            if (rk.contains(i)) continue;
            rOther.add(i);
            String name = cols(right).get(i);
            String n = name;
            for (int s = 2; outCols.contains(n); s++) n = name + "_" + s;
            outCols.add(n);
        }
        Map<String, List<List<Object>>> index = new HashMap<>();
        for (List<Object> r : rows(right)) {
            List<Object> key = new ArrayList<>();
            for (int i : rk) key.add(keyOf(cell(r, i)));
            index.computeIfAbsent(JsonLite.canonical(key), x -> new ArrayList<>()).add(r);
        }
        List<Object> out = new ArrayList<>();
        int lw = cols(left).size();
        Set<List<Object>> usedRight = Collections.newSetFromMap(new IdentityHashMap<>());
        boolean keepLeft = jt.equals("left") || jt.equals("full"), keepRight = jt.equals("right") || jt.equals("full");
        for (List<Object> r : rows(left)) {
            List<Object> key = new ArrayList<>();
            for (int i : lk) key.add(keyOf(cell(r, i)));
            List<List<Object>> m = index.getOrDefault(JsonLite.canonical(key), List.of());
            if (m.isEmpty() && keepLeft) m = Collections.singletonList(null);
            for (List<Object> rr : m) {
                if (rr != null) usedRight.add(rr);
                List<Object> nr = new ArrayList<>();
                for (int i = 0; i < lw; i++) nr.add(cell(r, i));
                for (int i : rOther) nr.add(rr == null ? null : cell(rr, i));
                out.add(nr);
            }
        }
        if (keepRight) for (List<Object> rr : rows(right)) {
            if (usedRight.contains(rr)) continue;
            List<Object> nr = new ArrayList<>();
            for (int i = 0; i < lw; i++) { int k = lk.indexOf(i); nr.add(k >= 0 ? cell(rr, rk.get(k)) : null); }
            for (int i : rOther) nr.add(cell(rr, i));
            out.add(nr);
        }
        return Out.of("table", Values.table(outCols, out), "count", BigDecimal.valueOf(out.size()));
    }

    @Action(id = "merge", name = "Merge tables", category = "Table", idempotent = true,
            description = "Stack the rows of several tables; columns are matched by name, using the union of all columns or requiring the same ones.")
    @Output(name = "table", type = OoaType.TABLE, description = "Merged table.")
    public Map<String, Object> merge(ActionContext ctx,
            @Input(name = "tables", type = OoaType.LIST, description = "Tables to merge, in order.") List<Object> tables,
            @Input(name = "columns", type = OoaType.STRING, required = false, defaultValue = "\"union\"", enumValues = {"union", "same"},
                    description = "union adds missing columns as empty; same fails when columns differ.") String mode) {
        if (tables == null || tables.isEmpty()) throw new ActionException("TABLE.NO_TABLES", "No tables to merge", "Pass a list of at least one table.");
        List<Map<String, Object>> ts = new ArrayList<>();
        for (int i = 0; i < tables.size(); i++) {
            try { @SuppressWarnings("unchecked") Map<String, Object> t = (Map<String, Object>) Values.coerce(tables.get(i), "Table", "tables[" + i + "]"); ts.add(t); }
            catch (OoaException e) { throw new ActionException("TABLE.NOT_A_TABLE", "Item " + i + " in 'tables' is not a table", "Pass only tables."); }
        }
        LinkedHashSet<String> all = new LinkedHashSet<>(cols(ts.get(0)));
        for (Map<String, Object> t : ts) {
            if ("same".equals(mode) && !new HashSet<>(cols(t)).equals(new HashSet<>(cols(ts.get(0)))))
                throw new ActionException("TABLE.COLUMNS_DIFFER", "Tables have different columns: " + cols(ts.get(0)) + " vs " + cols(t), "Use columns = union, or make the columns match.");
            all.addAll(cols(t));
        }
        List<String> outCols = new ArrayList<>(all);
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> t : ts) {
            List<String> tc = cols(t);
            for (List<Object> r : rows(t)) {
                List<Object> nr = new ArrayList<>();
                for (String c : outCols) { int i = tc.indexOf(c); nr.add(i < 0 ? null : cell(r, i)); }
                out.add(nr);
            }
        }
        return Out.of("table", Values.table(outCols, out));
    }

    @Action(id = "find", name = "Find value", category = "Table", idempotent = true,
            description = "Return the zero-based numbers of rows containing a value, in one column or in any column.")
    @Output(name = "rows", type = OoaType.LIST, description = "Zero-based row numbers that match.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of matching rows.")
    @Output(name = "found", type = OoaType.BOOLEAN, description = "True when at least one row matches.")
    @Output(name = "cells", type = OoaType.LIST, description = "Every matching cell as a dictionary with zero-based row and column numbers and the column name.")
    @Output(name = "first_row", type = OoaType.NUMBER, description = "Zero-based number of the first matching row, or -1.")
    public Map<String, Object> find(ActionContext ctx,
            @Input(name = "table", type = OoaType.TABLE, description = "Source table.") Map<String, Object> table,
            @Input(name = "value", description = "Value to look for.") Object value,
            @Input(name = "column", type = OoaType.STRING, required = false, description = "Search only this column; all columns when empty.") String column,
            @Input(name = "match", type = OoaType.STRING, required = false, defaultValue = "\"equals\"", enumValues = {"equals", "contains"},
                    description = "equals (numbers compare by value) or contains (text).") String match,
            @Input(name = "match_case", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Compare text with upper/lower case taken into account.") boolean matchCase) {
        String op = "contains".equals(match) ? "contains" : "eq";
        List<Integer> search = new ArrayList<>();
        if (column != null && !column.isBlank()) search.add(col(table, column));
        else for (int i = 0; i < cols(table).size(); i++) search.add(i);
        List<Object> hits = new ArrayList<>(), cells = new ArrayList<>();
        List<List<Object>> rs = rows(table);
        for (int r = 0; r < rs.size(); r++) {
            boolean rowHit = false;
            for (int c : search) if (matches(cell(rs.get(r), c), op, value, matchCase)) {
                rowHit = true;
                cells.add(Out.of("row", BigDecimal.valueOf(r), "column", BigDecimal.valueOf(c), "column_name", cols(table).get(c)));
            }
            if (rowHit) hits.add(BigDecimal.valueOf(r));
        }
        return Out.of("rows", hits, "count", BigDecimal.valueOf(hits.size()), "found", !hits.isEmpty(), "cells", cells,
                "first_row", hits.isEmpty() ? BigDecimal.valueOf(-1) : hits.get(0));
    }

    @Action(id = "write_to_file", name = "Write to file", category = "Table", capabilities = "filesystem",
            description = "Save a table as a CSV file or as a JSON list of records, replacing the file.")
    @Output(name = "path", type = OoaType.FILE, description = "Full path of the written file.")
    public Map<String, Object> writeToFile(ActionContext ctx,
            @Input(name = "table", type = OoaType.TABLE, description = "Table to save.") Map<String, Object> table,
            @Input(name = "path", type = OoaType.FILE, description = "Target file, e.g. output/report.csv.") String path,
            @Input(name = "format", type = OoaType.STRING, required = false, defaultValue = "\"csv\"", enumValues = {"csv", "json"}, description = "csv or json.") String format,
            @Input(name = "delimiter", type = OoaType.STRING, required = false, defaultValue = "\",\"", description = "CSV delimiter (one character).") String delimiter,
            @Input(name = "header", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Write the column names as the first CSV line.") boolean header,
            @Input(name = "encoding", type = OoaType.STRING, required = false, defaultValue = "\"utf-8\"", description = "Text encoding, e.g. utf-8, utf-8-bom, ansi (windows-1252), utf-16.") String encoding,
            @Input(name = "append", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Add the rows to the end of an existing CSV file (no header is repeated).") boolean append,
            @Input(name = "line_ending", type = OoaType.STRING, required = false, defaultValue = "\"crlf\"", enumValues = {"crlf", "lf"}, description = "Line ending of CSV rows.") String lineEnding) {
        String fmt = format == null ? "csv" : format.toLowerCase(Locale.ROOT);
        java.nio.file.Path p = ctx.resolvePath(path);
        boolean appending = append && fmt.equals("csv") && java.nio.file.Files.exists(p);
        if (appending) header = false;
        String eol = "lf".equals(lineEnding) ? "\n" : "\r\n";
        String text;
        if (fmt.equals("json")) {
            text = JsonLite.write(Values.toJsonable(toRecords(ctx, table).get("records")));
        } else if (fmt.equals("csv")) {
            String d = delimiter == null || delimiter.isEmpty() ? "," : delimiter.equals("\\t") ? "\t" : delimiter;
            if (d.length() != 1) throw new ActionException("TABLE.BAD_DELIMITER", "Delimiter must be one character, got '" + d + "'", "Use , ; | or a tab.");
            char dc = d.charAt(0);
            StringBuilder sb = new StringBuilder();
            if (header) sb.append(csvLine(new ArrayList<>(cols(table)), dc, eol));
            for (List<Object> r : rows(table)) {
                List<Object> vals = new ArrayList<>();
                for (int i = 0; i < cols(table).size(); i++) vals.add(cell(r, i));
                sb.append(csvLine(vals, dc, eol));
            }
            text = sb.toString();
        } else throw new ActionException("TABLE.BAD_FORMAT", "Unknown file format '" + format + "'", "Use csv or json.");
        CsvPackage.Enc enc = CsvPackage.encoding(encoding);
        try {
            if (p.getParent() != null) java.nio.file.Files.createDirectories(p.getParent());
            byte[] bytes = (enc.bom() && !appending ? "\uFEFF" + text : text).getBytes(enc.charset());
            if (appending) java.nio.file.Files.write(p, bytes, java.nio.file.StandardOpenOption.APPEND);
            else java.nio.file.Files.write(p, bytes);
        } catch (java.io.IOException e) { throw new ActionException("FILE.IO_ERROR", "Cannot write " + p + ": " + e.getMessage(), null, true); }
        return Out.of("path", p.toString());
    }

    private static String csvLine(List<Object> values, char d, String eol) {
        StringJoiner j = new StringJoiner(String.valueOf(d), "", eol);
        for (Object v : values) j.add(CsvPackage.quote(v == null ? "" : Values.text(Values.toJsonable(v)), d));
        return j.toString();
    }
}
