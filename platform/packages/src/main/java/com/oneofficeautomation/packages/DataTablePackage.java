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
            @Input(name = "operator", type = OoaType.STRING, enumValues = {"eq", "ne", "gt", "ge", "lt", "le", "contains", "starts_with", "empty", "not_empty"},
                    description = "Comparison.") String op,
            @Input(name = "value", required = false, description = "Value to compare with (not needed for empty/not_empty).") Object value) {
        int c = col(table, column);
        List<List<Object>> kept = new ArrayList<>();
        for (List<Object> r : rows(table)) if (matches(cell(r, c), op, value)) kept.add(r);
        return Out.of("table", copy(table, kept), "count", BigDecimal.valueOf(kept.size()));
    }

    static boolean matches(Object cell, String op, Object value) {
        switch (op) {
            case "empty": return cell == null || "".equals(cell);
            case "not_empty": return !(cell == null || "".equals(cell));
            case "contains": return Values.text(cell).toLowerCase(Locale.ROOT).contains(Values.text(value).toLowerCase(Locale.ROOT));
            case "starts_with": return Values.text(cell).toLowerCase(Locale.ROOT).startsWith(Values.text(value).toLowerCase(Locale.ROOT));
            default:
                int r = ListPackage.compare(cell, value, true);
                return switch (op) { case "eq" -> r == 0; case "ne" -> r != 0; case "gt" -> r > 0; case "ge" -> r >= 0; case "lt" -> r < 0; default -> r <= 0; };
        }
    }

    @Action(id = "sort", name = "Sort", category = "Table", idempotent = true, description = "Sort rows by a column.")
    @Output(name = "table", type = OoaType.TABLE, description = "Sorted table.")
    public Map<String, Object> sort(ActionContext ctx,
            @Input(name = "table", type = OoaType.TABLE, description = "Source table.") Map<String, Object> table,
            @Input(name = "column", type = OoaType.STRING, description = "Column name.") String column,
            @Input(name = "descending", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Largest first.") boolean desc) {
        int c = col(table, column);
        List<List<Object>> rs = new ArrayList<>(rows(table));
        Comparator<List<Object>> cmp = (a, b) -> ListPackage.compare(cell(a, c), cell(b, c), true);
        rs.sort(desc ? cmp.reversed() : cmp);
        return Out.of("table", copy(table, rs));
    }

    @Action(id = "get_value", name = "Get value", category = "Table", idempotent = true, description = "Read one cell by zero-based row number and column name.")
    @Output(name = "value", description = "Cell value.")
    public Map<String, Object> getValue(ActionContext ctx,
            @Input(name = "table", type = OoaType.TABLE, description = "Source table.") Map<String, Object> table,
            @Input(name = "row", type = OoaType.NUMBER, description = "Zero-based row number.") int row,
            @Input(name = "column", type = OoaType.STRING, description = "Column name.") String column) {
        int c = col(table, column);
        if (row < 0 || row >= rows(table).size()) throw new ActionException("TABLE.NO_ROW", "Row " + row + " does not exist (table has " + rows(table).size() + " rows)", "Row numbers start at 0.");
        return Out.of("value", cell(rows(table).get(row), c));
    }

    @Action(id = "set_value", name = "Set value", category = "Table", idempotent = true, description = "Change one cell.")
    @Output(name = "table", type = OoaType.TABLE, description = "Updated table.")
    public Map<String, Object> setValue(ActionContext ctx,
            @Input(name = "table", type = OoaType.TABLE, description = "Source table.") Map<String, Object> table,
            @Input(name = "row", type = OoaType.NUMBER, description = "Zero-based row number.") int row,
            @Input(name = "column", type = OoaType.STRING, description = "Column name.") String column,
            @Input(name = "value", required = false, description = "New value.") Object value) {
        int c = col(table, column);
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
}
