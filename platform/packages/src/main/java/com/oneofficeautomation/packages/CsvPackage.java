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
        if (d.equals("regional")) {
            // the list separator of the machine's locale: ; where the decimal separator is a comma, otherwise ,
            return java.text.DecimalFormatSymbols.getInstance().getDecimalSeparator() == ',' ? ';' : ',';
        }
        if (d.length() != 1) throw new ActionException("CSV.BAD_DELIMITER", "Delimiter must be one character", "Use , ; | tab or regional.");
        return d.charAt(0);
    }

    /** A text encoding and whether a byte order mark is written. */
    record Enc(Charset charset, boolean bom) {}

    /** Accepts Java names plus the names people know from other tools: ansi, unicode, utf-8-bom. */
    static Enc encoding(String name) {
        String n = name == null || name.isBlank() ? "utf-8" : name.trim().toLowerCase(Locale.ROOT);
        try {
            return switch (n) {
                case "ansi" -> new Enc(Charset.forName("windows-1252"), false);
                case "unicode", "utf-16le" -> new Enc(java.nio.charset.StandardCharsets.UTF_16LE, true);
                case "utf-8-bom", "utf-8 bom", "utf8bom" -> new Enc(java.nio.charset.StandardCharsets.UTF_8, true);
                default -> new Enc(Charset.forName(n), false);
            };
        } catch (IllegalArgumentException e) {
            throw new ActionException("FILE.BAD_ENCODING", "Unknown text encoding '" + name + "'", "Use utf-8, utf-8-bom, ansi, unicode, utf-16 or windows-1251.");
        }
    }

    /** Reads a CSV file into a header and rows with the read options; shared by read and the rows iterator. */
    static Map<String, Object> load(ActionContext ctx, String path, String d, boolean header, String enc, String trim, int skipLines, Integer maxRows, boolean skipEmptyRows) {
        Path p = ctx.resolvePath(path);
        if (!Files.isRegularFile(p)) throw new ActionException("FILE.NOT_FOUND", "File not found: " + p, "Check the path.");
        if (skipLines < 0) throw new ActionException("CSV.BAD_OPTION", "skip_lines cannot be negative", "Use 0 or more.");
        String text;
        try { text = new String(Files.readAllBytes(p), encoding(enc).charset()); }
        catch (IOException e) { throw new ActionException("FILE.IO_ERROR", "Cannot read " + p + ": " + e.getMessage(), null, true); }
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1);
        for (int i = 0; i < skipLines && !text.isEmpty(); i++) { int nl = text.indexOf('\n'); text = nl < 0 ? "" : text.substring(nl + 1); }
        List<List<String>> rows = parse(text, delim(d));
        String t = trim == null ? "none" : trim;
        if (!t.equals("none")) for (List<String> r : rows) r.replaceAll(v -> switch (t) {
            case "leading" -> v.stripLeading(); case "trailing" -> v.stripTrailing(); case "both" -> v.strip();
            default -> throw new ActionException("CSV.BAD_OPTION", "Unknown trim option '" + t + "'", "Use none, leading, trailing or both.");
        });
        if (skipEmptyRows) rows.removeIf(r -> r.stream().allMatch(String::isEmpty));
        int width = rows.stream().mapToInt(List::size).max().orElse(0);
        List<String> cols = new ArrayList<>();
        if (header && !rows.isEmpty()) cols.addAll(rows.remove(0));
        while (cols.size() < width) cols.add("Column" + (cols.size() + 1));
        List<Object> data = new ArrayList<>();
        for (List<String> r : rows) {
            if (maxRows != null && data.size() >= maxRows) break;
            List<Object> row = new ArrayList<>(r); while (row.size() < cols.size()) row.add(""); data.add(row);
        }
        return Values.table(cols, data);
    }

    private static final java.util.regex.Pattern NUMBER = java.util.regex.Pattern.compile("-?(0|[1-9]\\d*)(\\.\\d+)?");
    private static final java.util.regex.Pattern ISO_DATE = java.util.regex.Pattern.compile("\\d{4}-\\d{2}-\\d{2}([T ]\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?)?");

    /**
     * A typed value for text that is clearly a number, true/false or an ISO date; anything else (including codes with
     * leading zeros such as 00123, which are usually identifiers) stays text.
     */
    static Object typed(Object v) {
        if (!(v instanceof String s) || s.isEmpty()) return v;
        if (NUMBER.matcher(s).matches()) return new java.math.BigDecimal(s);
        if (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false")) return Boolean.parseBoolean(s);
        if (ISO_DATE.matcher(s).matches()) {
            try { return s.length() == 10 ? java.time.LocalDate.parse(s).atStartOfDay() : java.time.LocalDateTime.parse(s.replace(' ', 'T')); }
            catch (java.time.DateTimeException e) { return v; }
        }
        return v;
    }

    private static Map<String, Object> typedTable(Map<String, Object> t) {
        for (List<Object> r : DataTablePackage.rows(t)) r.replaceAll(CsvPackage::typed);
        return t;
    }

    @Action(id = "read", name = "Read to table", category = "CSV", idempotent = true, capabilities = "filesystem",
            description = "Read a CSV or delimited text file into a table.",
            panel = "path, header, delimiter, trim, encoding | skip_lines, [Read only the first rows: max_rows], skip_empty_rows, detect_types")
    @Output(name = "table", type = OoaType.TABLE, description = "Table of text values (typed values with detect_types).")
    @Output(name = "row_count", type = OoaType.NUMBER, description = "Number of data rows.")
    public Map<String, Object> read(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "File path.") String path,
            @Input(name = "delimiter", type = OoaType.STRING, required = false, defaultValue = "\",\"", description = "Field delimiter: , ; | tab, or regional (the Windows list separator: ; when the machine's decimal separator is a comma, otherwise ,).") String d,
            @Input(name = "header", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "First row holds column names.") boolean header,
            @Input(name = "encoding", type = OoaType.STRING, required = false, defaultValue = "\"utf-8\"", description = "Text encoding: utf-8, utf-8-bom, ansi, unicode, utf-16, windows-1251 ...") String enc,
            @Input(name = "trim", type = OoaType.STRING, required = false, defaultValue = "\"none\"", enumValues = {"none", "leading", "trailing", "both"},
                    description = "Remove spaces at the start and/or end of every value.") String trim,
            @Input(name = "skip_lines", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0, description = "Lines to skip at the top of the file, before the header.") int skipLines,
            @Input(name = "max_rows", type = OoaType.NUMBER, required = false, min = 0, description = "Read at most this many data rows; all when empty.") Integer maxRows,
            @Input(name = "skip_empty_rows", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Leave out rows whose values are all empty (blank lines are always skipped).") boolean skipEmptyRows,
            @Input(name = "detect_types", type = OoaType.BOOLEAN, required = false, defaultValue = "false",
                    description = "Turn values that are clearly numbers, true/false or ISO dates (2026-10-23) into typed values; codes with leading zeros stay text.") boolean detectTypes) {
        Map<String, Object> t = load(ctx, path, d, header, enc, trim, skipLines, maxRows, skipEmptyRows);
        if (detectTypes) typedTable(t);
        return Out.of("table", t, "row_count", java.math.BigDecimal.valueOf(((List<?>) t.get("rows")).size()));
    }

    @LoopIterator(id = "rows", name = "Each row in CSV/TXT", description = "Yield each data row of a CSV or delimited text file as a record keyed by column name.")
    public Iterable<Object> rows(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "File path.") String path,
            @Input(name = "delimiter", type = OoaType.STRING, required = false, defaultValue = "\",\"", description = "Field delimiter: , ; | tab, or regional (the Windows list separator: ; when the machine's decimal separator is a comma, otherwise ,).") String d,
            @Input(name = "header", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "First row holds column names.") boolean header,
            @Input(name = "encoding", type = OoaType.STRING, required = false, defaultValue = "\"utf-8\"", description = "Text encoding.") String enc,
            @Input(name = "trim", type = OoaType.STRING, required = false, defaultValue = "\"none\"", enumValues = {"none", "leading", "trailing", "both"},
                    description = "Remove spaces at the start and/or end of every value.") String trim,
            @Input(name = "detect_types", type = OoaType.BOOLEAN, required = false, defaultValue = "false",
                    description = "Turn values that are clearly numbers, true/false or ISO dates into typed values.") boolean detectTypes) {
        Map<String, Object> t = load(ctx, path, d, header, enc, trim, 0, null, false);
        if (detectTypes) typedTable(t);
        List<String> cols = DataTablePackage.cols(t);
        List<Object> out = new ArrayList<>();
        for (List<Object> r : DataTablePackage.rows(t)) {
            Map<String, Object> rec = new LinkedHashMap<>();
            for (int i = 0; i < cols.size(); i++) rec.put(cols.get(i), DataTablePackage.cell(r, i));
            out.add(rec);
        }
        return out;
    }

    @Action(id = "write", name = "Write from table", category = "CSV", capabilities = "filesystem",
            description = "Write a table to a CSV file, overwriting or appending rows.",
            panel = "table, path, header, delimiter, encoding, append | quote_all, line_ending")
    public Map<String, Object> write(ActionContext ctx,
            @Input(name = "table", type = OoaType.TABLE, description = "Table to write.") Map<String, Object> table,
            @Input(name = "path", type = OoaType.FILE, description = "Target path.") String path,
            @Input(name = "delimiter", type = OoaType.STRING, required = false, defaultValue = "\",\"", description = "Field delimiter: , ; | tab, or regional (the Windows list separator: ; when the machine's decimal separator is a comma, otherwise ,).") String d,
            @Input(name = "header", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Write column names first (not when appending to an existing file).") boolean header,
            @Input(name = "append", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Append rows to an existing file.") boolean append,
            @Input(name = "encoding", type = OoaType.STRING, required = false, defaultValue = "\"utf-8\"", description = "Text encoding: utf-8, utf-8-bom, ansi, unicode, utf-16 ...") String enc,
            @Input(name = "quote_all", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Put every value in double quotes, not only values that need them.") boolean quoteAll,
            @Input(name = "line_ending", type = OoaType.STRING, required = false, defaultValue = "\"crlf\"", enumValues = {"crlf", "lf"}, description = "Line ending: crlf (Windows) or lf.") String lineEnding) {
        char c = delim(d);
        Path p = ctx.resolvePath(path);
        String eol = "lf".equals(lineEnding) ? "\n" : "\r\n";
        Enc e = encoding(enc);
        StringBuilder sb = new StringBuilder();
        boolean exists = append && Files.exists(p);
        boolean writeHeader = header && !exists;
        if (e.bom() && !exists) sb.append('\uFEFF');
        if (writeHeader) sb.append(line(new ArrayList<>(DataTablePackage.cols(table)), c, quoteAll, eol));
        for (List<Object> r : DataTablePackage.rows(table)) sb.append(line(r, c, quoteAll, eol));
        try {
            if (p.getParent() != null) Files.createDirectories(p.getParent());
            byte[] bytes = sb.toString().getBytes(e.charset());
            if (append) Files.write(p, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            else Files.write(p, bytes);
        } catch (IOException ex) { throw new ActionException("FILE.IO_ERROR", "Cannot write " + p + ": " + ex.getMessage(), null, true); }
        return Out.of();
    }

    private static String line(List<?> values, char d, boolean quoteAll, String eol) {
        StringJoiner j = new StringJoiner(String.valueOf(d));
        for (Object v : values) {
            String s = v == null ? "" : Values.text(Values.toJsonable(v));
            j.add(quoteAll ? '"' + s.replace("\"", "\"\"") + '"' : quote(s, d));
        }
        return j + eol;
    }
}
