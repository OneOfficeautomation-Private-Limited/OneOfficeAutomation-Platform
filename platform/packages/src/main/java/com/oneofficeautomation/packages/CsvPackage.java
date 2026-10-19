package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.util.*;

/** RFC 4180 CSV: quoted fields, embedded delimiters, quotes and line breaks. */
@OoaPackage(id = "ooa.csv", name = "CSV/TXT", version = "1.0.0", description = "Read and write delimited text files as tables.")
public final class CsvPackage {

    static List<List<String>> parse(String text, char delim) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder f = new StringBuilder();
        boolean quoted = false, any = false;
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            any = true;
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') { f.append('"'); i++; } else quoted = false;
                } else f.append(c);
            } else if (c == '"') quoted = true;
            else if (c == delim) { row.add(f.toString()); f.setLength(0); }
            else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(f.toString()); f.setLength(0);
                rows.add(row); row = new ArrayList<>(); any = false;
            } else f.append(c);
        }
        if (quoted) throw new ActionException("CSV.UNCLOSED_QUOTE", "A quoted field is not closed", "Check for a missing closing quote.");
        if (any || f.length() > 0 || !row.isEmpty()) { row.add(f.toString()); rows.add(row); }
        rows.removeIf(r -> r.size() == 1 && r.get(0).isEmpty());
        return rows;
    }

    static String quote(String v, char delim) {
        if (v.indexOf(delim) >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0)
            return '"' + v.replace("\"", "\"\"") + '"';
        return v;
    }

    private static char delim(String d) {
        if (d.equals("\\t") || d.equals("tab")) return '\t';
        if (d.length() != 1) throw new ActionException("CSV.BAD_DELIMITER", "Delimiter must be one character", "Use , ; | or tab.");
        return d.charAt(0);
    }

    @Action(id = "read", name = "Read to table", category = "CSV", idempotent = true, capabilities = "filesystem",
            description = "Read a CSV or delimited text file into a table.")
    @Output(name = "table", type = OoaType.TABLE, description = "Table of text values.")
    @Output(name = "row_count", type = OoaType.NUMBER, description = "Number of data rows.")
    public Map<String, Object> read(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "File path.") String path,
            @Input(name = "delimiter", type = OoaType.STRING, required = false, defaultValue = "\",\"", description = "Field delimiter: , ; | or tab.") String d,
            @Input(name = "header", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "First row holds column names.") boolean header,
            @Input(name = "encoding", type = OoaType.STRING, required = false, defaultValue = "\"utf-8\"", description = "Text encoding.") String enc) {
        Path p = ctx.resolvePath(path);
        if (!Files.isRegularFile(p)) throw new ActionException("FILE.NOT_FOUND", "File not found: " + p, "Check the path.");
        List<List<String>> rows;
        try { rows = parse(Files.readString(p, Charset.forName(enc)), delim(d)); }
        catch (IOException e) { throw new ActionException("FILE.IO_ERROR", "Cannot read " + p + ": " + e.getMessage(), null, true); }
        int width = rows.stream().mapToInt(List::size).max().orElse(0);
        List<String> cols = new ArrayList<>();
        if (header && !rows.isEmpty()) cols.addAll(rows.remove(0));
        while (cols.size() < width) cols.add("Column" + (cols.size() + 1));
        List<Object> data = new ArrayList<>();
        for (List<String> r : rows) { List<Object> row = new ArrayList<>(r); while (row.size() < cols.size()) row.add(""); data.add(row); }
        return Out.of("table", Values.table(cols, data), "row_count", java.math.BigDecimal.valueOf(data.size()));
    }

    @Action(id = "write", name = "Write from table", category = "CSV", capabilities = "filesystem",
            description = "Write a table to a CSV file, overwriting or appending rows.")
    public Map<String, Object> write(ActionContext ctx,
            @Input(name = "table", type = OoaType.TABLE, description = "Table to write.") Map<String, Object> table,
            @Input(name = "path", type = OoaType.FILE, description = "Target path.") String path,
            @Input(name = "delimiter", type = OoaType.STRING, required = false, defaultValue = "\",\"", description = "Field delimiter.") String d,
            @Input(name = "header", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Write column names first (not when appending to an existing file).") boolean header,
            @Input(name = "append", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Append rows to an existing file.") boolean append) {
        char c = delim(d);
        Path p = ctx.resolvePath(path);
        StringBuilder sb = new StringBuilder();
        boolean writeHeader = header && !(append && Files.exists(p));
        if (writeHeader) sb.append(line(new ArrayList<>(DataTablePackage.cols(table)), c));
        for (List<Object> r : DataTablePackage.rows(table)) sb.append(line(r, c));
        try {
            if (p.getParent() != null) Files.createDirectories(p.getParent());
            if (append) Files.writeString(p, sb, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            else Files.writeString(p, sb);
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", "Cannot write " + p + ": " + e.getMessage(), null, true); }
        return Out.of();
    }

    private static String line(List<?> values, char d) {
        StringJoiner j = new StringJoiner(String.valueOf(d));
        for (Object v : values) j.add(quote(v == null ? "" : Values.text(Values.toJsonable(v)), d));
        return j + "\r\n";
    }
}
