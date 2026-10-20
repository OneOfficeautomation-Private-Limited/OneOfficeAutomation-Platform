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
            @Input(name = "row", type = OoaType.NUMBER, description = "Zero-based position for the new row; the row count adds it at the end.") int row,
            @Input(name = "values", type = OoaType.DICTIONARY, required = false, description = "Column name to value; missing columns stay empty.") Map<String, Object> values) {
        int n = rows(table).size();
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
            @Input(name = "column", type = OoaType.STRING, description = "Column to remove.") String column) {
        int c = col(table, column);
        List<String> newCols = new ArrayList<>(cols(table));
        newCols.remove(c);
        List<Object> rs = new ArrayList<>();
        for (List<Object> r : rows(table)) { List<Object> nr = new ArrayList<>(r); if (c < nr.size()) nr.remove(c); rs.add(nr); }
        return Out.of("table", Values.table(newCols, rs));
    }

    @Action(id = "column_count", name = "Column count", category = "Table", idempotent = true, description = "Number of columns.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of columns.")
    public Map<String, Object> columnCount(ActionContext ctx, @Input(name = "table", type = OoaType.TABLE, description = "Source table.") Map<String, Object> table) {
        return Out.of("count", BigDecimal.valueOf(cols(table).size()));
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
            @Input(name = "column", type = OoaType.STRING, description = "Column to convert.") String column,
            @Input(name = "type", type = OoaType.STRING, enumValues = {"String", "Number", "DateTime", "Boolean"}, description = "Target type.") String type) {
        int c = col(table, column);
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
            @Input(name = "join_type", type = OoaType.STRING, required = false, defaultValue = "\"inner\"", enumValues = {"inner", "left"},
                    description = "inner or left.") String joinType) {
        if (keys == null || keys.isEmpty()) throw new ActionException("TABLE.NO_KEYS", "No key columns given", "List the columns to match, e.g. [\"id\"].");
        String jt = joinType == null ? "inner" : joinType;
        if (!jt.equals("inner") && !jt.equals("left")) throw new ActionException("TABLE.BAD_JOIN_TYPE", "Unknown join type '" + jt + "'", "Use inner or left.");
        List<Integer> lk = new ArrayList<>(), rk = new ArrayList<>();
        for (Object k : keys) { lk.add(col(left, Values.text(k))); rk.add(col(right, Values.text(k))); }
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
        for (List<Object> r : rows(left)) {
            List<Object> key = new ArrayList<>();
            for (int i : lk) key.add(keyOf(cell(r, i)));
            List<List<Object>> m = index.getOrDefault(JsonLite.canonical(key), List.of());
            if (m.isEmpty() && jt.equals("left")) m = Collections.singletonList(null);
            for (List<Object> rr : m) {
                List<Object> nr = new ArrayList<>();
                for (int i = 0; i < lw; i++) nr.add(cell(r, i));
                for (int i : rOther) nr.add(rr == null ? null : cell(rr, i));
                out.add(nr);
            }
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
    public Map<String, Object> find(ActionContext ctx,
            @Input(name = "table", type = OoaType.TABLE, description = "Source table.") Map<String, Object> table,
            @Input(name = "value", description = "Value to look for.") Object value,
            @Input(name = "column", type = OoaType.STRING, required = false, description = "Search only this column; all columns when empty.") String column,
            @Input(name = "match", type = OoaType.STRING, required = false, defaultValue = "\"equals\"", enumValues = {"equals", "contains"},
                    description = "equals (numbers compare by value) or contains (text, case ignored).") String match) {
        String op = "contains".equals(match) ? "contains" : "eq";
        List<Integer> search = new ArrayList<>();
        if (column != null && !column.isBlank()) search.add(col(table, column));
        else for (int i = 0; i < cols(table).size(); i++) search.add(i);
        List<Object> hits = new ArrayList<>();
        List<List<Object>> rs = rows(table);
        for (int r = 0; r < rs.size(); r++) {
            for (int c : search) if (matches(cell(rs.get(r), c), op, value)) { hits.add(BigDecimal.valueOf(r)); break; }
        }
        return Out.of("rows", hits, "count", BigDecimal.valueOf(hits.size()), "found", !hits.isEmpty());
    }

    @Action(id = "write_to_file", name = "Write to file", category = "Table", capabilities = "filesystem",
            description = "Save a table as a CSV file or as a JSON list of records, replacing the file.")
    @Output(name = "path", type = OoaType.FILE, description = "Full path of the written file.")
    public Map<String, Object> writeToFile(ActionContext ctx,
            @Input(name = "table", type = OoaType.TABLE, description = "Table to save.") Map<String, Object> table,
            @Input(name = "path", type = OoaType.FILE, description = "Target file, e.g. output/report.csv.") String path,
            @Input(name = "format", type = OoaType.STRING, required = false, defaultValue = "\"csv\"", enumValues = {"csv", "json"}, description = "csv or json.") String format,
            @Input(name = "delimiter", type = OoaType.STRING, required = false, defaultValue = "\",\"", description = "CSV delimiter (one character).") String delimiter,
            @Input(name = "header", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Write the column names as the first CSV line.") boolean header) {
        String fmt = format == null ? "csv" : format.toLowerCase(Locale.ROOT);
        String text;
        if (fmt.equals("json")) {
            text = JsonLite.write(Values.toJsonable(toRecords(ctx, table).get("records")));
        } else if (fmt.equals("csv")) {
            String d = delimiter == null || delimiter.isEmpty() ? "," : delimiter.equals("\\t") ? "\t" : delimiter;
            if (d.length() != 1) throw new ActionException("TABLE.BAD_DELIMITER", "Delimiter must be one character, got '" + d + "'", "Use , ; | or a tab.");
            char dc = d.charAt(0);
            StringBuilder sb = new StringBuilder();
            if (header) sb.append(csvLine(new ArrayList<>(cols(table)), dc));
            for (List<Object> r : rows(table)) {
                List<Object> vals = new ArrayList<>();
                for (int i = 0; i < cols(table).size(); i++) vals.add(cell(r, i));
                sb.append(csvLine(vals, dc));
            }
            text = sb.toString();
        } else throw new ActionException("TABLE.BAD_FORMAT", "Unknown file format '" + format + "'", "Use csv or json.");
        java.nio.file.Path p = ctx.resolvePath(path);
        try {
            if (p.getParent() != null) java.nio.file.Files.createDirectories(p.getParent());
            java.nio.file.Files.writeString(p, text);
        } catch (java.io.IOException e) { throw new ActionException("FILE.IO_ERROR", "Cannot write " + p + ": " + e.getMessage(), null, true); }
        return Out.of("path", p.toString());
    }

    private static String csvLine(List<Object> values, char d) {
        StringJoiner j = new StringJoiner(String.valueOf(d), "", "\r\n");
        for (Object v : values) j.add(CsvPackage.quote(v == null ? "" : Values.text(Values.toJsonable(v)), d));
        return j.toString();
    }
}
