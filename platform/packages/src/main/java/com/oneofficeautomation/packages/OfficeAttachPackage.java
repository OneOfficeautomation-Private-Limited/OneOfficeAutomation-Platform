package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Works in an Excel workbook or Word document that is already open on the desktop, as a person left it (gap G21,
 * 3 Nov 2026): it attaches to the running Excel or Word through COM, finds the file by its name or full path, and reads,
 * writes or saves it. It never opens or closes files, never quits Excel or Word and never changes what is shown, so a
 * person can keep working in the same window. File names and values are passed as script arguments or a temporary JSON
 * file, never pasted into script code.
 */
@OoaPackage(id = "ooa.office_attach", name = "Open Excel and Word files (needs Office)", version = "1.0.0",
        description = "Read, write and save an Excel workbook or Word document that is already open on the desktop, in the same window.")
public final class OfficeAttachPackage {

    /**
     * Lists the files open in any running Office program through the Windows table of running objects, and binds to one
     * of them. Used when the file is not in the Excel or Word that answers first, for example when a second, hidden Excel
     * was started by another program. Only paths found in the table are bound, so nothing is ever opened.
     */
    static final String ROT = String.join("\n",
            "function Get-OoaOpenFiles([string]$pattern) {",
            "  if (-not ('OoaRot' -as [type])) { Add-Type -TypeDefinition @'",
            "using System; using System.Collections.Generic; using System.Runtime.InteropServices; using System.Runtime.InteropServices.ComTypes;",
            "public static class OoaRot {",
            "  [DllImport(\"ole32.dll\")] static extern int GetRunningObjectTable(int r, out IRunningObjectTable t);",
            "  [DllImport(\"ole32.dll\")] static extern int CreateBindCtx(int r, out IBindCtx c);",
            "  public static List<string> Names() {",
            "    var list = new List<string>(); IRunningObjectTable rot; IEnumMoniker en; IBindCtx ctx;",
            "    if (GetRunningObjectTable(0, out rot) != 0) return list; CreateBindCtx(0, out ctx); rot.EnumRunning(out en);",
            "    var m = new IMoniker[1];",
            "    while (en.Next(1, m, IntPtr.Zero) == 0) { try { string n; m[0].GetDisplayName(ctx, null, out n); list.Add(n); } catch { } }",
            "    return list; } }",
            "'@",
            "  }",
            "  [OoaRot]::Names() | Where-Object { $_ -match $pattern -and (Test-Path -LiteralPath $_ -PathType Leaf) } | Sort-Object -Unique",
            "}",
            "function Find-OoaOpenFile([string]$pattern, [string]$want) {",
            "  foreach ($p in (Get-OoaOpenFiles $pattern)) { if ($p -ieq $want -or (Split-Path -Leaf $p) -ieq $want) { return [Runtime.InteropServices.Marshal]::BindToMoniker($p) } }",
            "  return $null",
            "}",
            "");

    // templates such as Word's Normal.dotm are left out: they are open in the background, not as a person's file
    static final String EXCEL_FILES = "'\\.(xlsx|xlsm|xlsb|xls|csv)$'";
    static final String WORD_FILES = "'\\.(docx|docm|doc|rtf)$'";

    /** Finds the open workbook ($args[0], a name such as Sales.xlsx or a full path) in the running Excel. */
    static final String EXCEL_FIND = ROT + String.join("\n",
            "$ErrorActionPreference = 'Stop'",
            "$xl = $null; try { $xl = [Runtime.InteropServices.Marshal]::GetActiveObject('Excel.Application') } catch { }",
            "$wb = $null",
            "if ($xl) { foreach ($w in $xl.Workbooks) { if ($w.Name -ieq $args[0] -or $w.FullName -ieq $args[0]) { $wb = $w } } }",
            "if (-not $wb) { $wb = Find-OoaOpenFile " + EXCEL_FILES + " $args[0]; if ($wb) { $xl = $wb.Application } }",
            "if (-not $wb -and -not $xl -and -not (Get-OoaOpenFiles " + EXCEL_FILES + ")) { 'OOA_NO_APP'; return }",
            "if (-not $wb) { 'OOA_NOT_OPEN=' + ((@(if ($xl) { @($xl.Workbooks) | ForEach-Object { $_.Name } }) + @(Get-OoaOpenFiles " + EXCEL_FILES + " | ForEach-Object { Split-Path -Leaf $_ }) | Sort-Object -Unique) -join '|'); return }",
            "if ($args[1]) { $ws = $wb.Worksheets.Item($args[1]) } else { $ws = $wb.ActiveSheet }",
            "");

    /** Finds the open document ($args[0]) in the running Word. */
    static final String WORD_FIND = ROT + String.join("\n",
            "$ErrorActionPreference = 'Stop'",
            "$wd = $null; try { $wd = [Runtime.InteropServices.Marshal]::GetActiveObject('Word.Application') } catch { }",
            "$doc = $null",
            "if ($wd) { foreach ($d in $wd.Documents) { if ($d.Name -ieq $args[0] -or $d.FullName -ieq $args[0]) { $doc = $d } } }",
            "if (-not $doc) { $doc = Find-OoaOpenFile " + WORD_FILES + " $args[0]; if ($doc) { $wd = $doc.Application } }",
            "if (-not $doc -and -not $wd -and -not (Get-OoaOpenFiles " + WORD_FILES + ")) { 'OOA_NO_APP'; return }",
            "if (-not $doc) { 'OOA_NOT_OPEN=' + ((@(if ($wd) { @($wd.Documents) | ForEach-Object { $_.Name } }) + @(Get-OoaOpenFiles " + WORD_FILES + " | ForEach-Object { Split-Path -Leaf $_ }) | Sort-Object -Unique) -join '|'); return }",
            "");

    private static String run(ActionContext ctx, String app, String script, List<String> args, int timeout) {
        if (!ProcessRunner.WINDOWS) throw new ActionException("SYSTEM.WINDOWS_ONLY", "Attaching to an open " + app + " file needs Windows with " + app, "Run this agent on the Windows computer where the file is open.");
        Path file = null;
        try {
            file = Files.createTempFile("ooa-attach-", ".ps1");
            Files.writeString(file, "\uFEFF" + ScriptPackage.UTF8_PREAMBLE + "\n" + script, StandardCharsets.UTF_8);
            List<String> cmd = new ArrayList<>(List.of("powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", file.toString()));
            cmd.addAll(args);
            ProcessRunner.Result r = ProcessRunner.run(ctx, cmd, ctx.workDir(), null, timeout, Map.of(), StandardCharsets.UTF_8);
            String out = r.stdout();
            String code = app.equals("Excel") ? "EXCEL_OPEN" : "WORD_OPEN";
            if (out.lines().anyMatch(l -> l.strip().equals("OOA_NO_APP")))
                throw new ActionException(code + ".NOT_RUNNING", app + " is not running, or runs as another user or as administrator", "Open the file in " + app + " on this computer first, as the same user as the runner.", true);
            Optional<String> notOpen = out.lines().filter(l -> l.startsWith("OOA_NOT_OPEN=")).findFirst();
            if (notOpen.isPresent()) {
                String open = notOpen.get().substring(13);
                throw new ActionException(code + ".NOT_OPEN", "'" + args.get(0) + "' is not open in " + app,
                        open.isBlank() ? "No file is open in " + app + "." : "Open files: " + open.replace("|", ", ") + ".", true);
            }
            if (r.exitCode() != 0)
                throw new ActionException(code + ".FAILED", app + " reported an error: " + ApplicationPackage.tail(r.stderr().isBlank() ? out : r.stderr()),
                        "Check the sheet name, range or text; " + app + " may also be busy (a cell being edited or a dialog open).", true);
            return out;
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), null, true);
        } finally { if (file != null) try { Files.deleteIfExists(file); } catch (IOException ignored) { } }
    }

    private static Object json(String out) {
        String line = out.lines().filter(l -> l.startsWith("OOA_JSON=")).reduce((a, b) -> b).orElse("OOA_JSON=null");
        return JsonLite.parse(line.substring(9));
    }

    private static String name(String file) {
        if (file == null || file.isBlank()) throw new ActionException("OFFICE_ATTACH.NO_FILE", "Give the open file's name or full path", "For example Sales.xlsx or C:\\Reports\\Sales.xlsx.");
        return file.strip();
    }

    private static final String D_WORKBOOK = "Name of the open workbook (Sales.xlsx) or its full path.";
    private static final String D_DOCUMENT = "Name of the open document (Letter.docx) or its full path.";
    private static final String D_SHEET = "Sheet name; the sheet shown in Excel when empty.";
    private static final String D_TIMEOUT = "Maximum time in seconds.";

    // ---------- Excel ----------

    @Action(id = "excel_list_open", name = "List open workbooks", category = "Excel", idempotent = true, capabilities = "desktop",
            description = "List the workbooks open in the running Excel, with their full paths and sheets.")
    @Output(name = "workbooks", type = OoaType.LIST, description = "Records with name, path, sheets (list) and saved (false when it has unsaved changes).")
    @SuppressWarnings("unchecked")
    public Map<String, Object> excelListOpen(ActionContext ctx) {
        String out = run(ctx, "Excel", ROT + String.join("\n",
                "$ErrorActionPreference = 'Stop'",
                "$xl = $null; try { $xl = [Runtime.InteropServices.Marshal]::GetActiveObject('Excel.Application') } catch { }",
                "$books = New-Object System.Collections.ArrayList",
                "if ($xl) { foreach ($w in $xl.Workbooks) { [void]$books.Add($w) } }",
                "foreach ($p in (Get-OoaOpenFiles " + EXCEL_FILES + ")) { if (-not (@($books) | Where-Object { $_.FullName -ieq $p })) { try { [void]$books.Add([Runtime.InteropServices.Marshal]::BindToMoniker($p)) } catch { } } }",
                "if (-not $xl -and $books.Count -eq 0) { 'OOA_NO_APP'; return }",
                "$list = New-Object System.Collections.ArrayList",
                "foreach ($w in $books) { [void]$list.Add([ordered]@{ name = $w.Name; path = $w.FullName; sheets = @($w.Worksheets | ForEach-Object { $_.Name }); saved = [bool]$w.Saved }) }",
                "'OOA_JSON=' + (ConvertTo-Json -InputObject $list -Depth 4 -Compress)"), List.of(), 60);
        Object j = json(out);
        return Map.of("workbooks", j instanceof List<?> l ? l : j == null ? List.of() : List.of(j));
    }

    @Action(id = "excel_read_range", name = "Read range from open workbook", category = "Excel", idempotent = true, capabilities = "desktop",
            description = "Read a range, or the used area, of a sheet in a workbook that is open in Excel, including values not yet saved, as a Data Table.",
            panel = "workbook, sheet, range, header | timeout_seconds")
    @Output(name = "table", type = OoaType.TABLE, description = "The cells; columns named from the first row when header is on.")
    @Output(name = "row_count", type = OoaType.NUMBER, description = "Number of data rows.")
    @SuppressWarnings("unchecked")
    public Map<String, Object> excelReadRange(ActionContext ctx,
            @Input(name = "workbook", type = OoaType.STRING, example = "Sales.xlsx", description = D_WORKBOOK) String workbook,
            @Input(name = "sheet", type = OoaType.STRING, required = false, description = D_SHEET) String sheet,
            @Input(name = "range", type = OoaType.STRING, required = false, example = "A1:D20", description = "Range to read; the sheet's used area when empty.") String range,
            @Input(name = "header", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "The first row holds the column names.") boolean header,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "60", description = D_TIMEOUT) int timeout) {
        if (range != null && !range.isBlank() && !range.strip().matches("(?i)\\$?[A-Z]{1,3}\\$?\\d+(:\\$?[A-Z]{1,3}\\$?\\d+)?"))
            throw new ActionException("EXCEL_OPEN.BAD_RANGE", "'" + range + "' is not a range", "Use an address such as A1:D20.");
        String out = run(ctx, "Excel", EXCEL_FIND + String.join("\n",
                // plain assignments: "$rng = if (...) { range }" would unroll the range into its cells
                "if ($args[2]) { $rng = $ws.Range($args[2]) } else { $rng = $ws.UsedRange }",
                "$v = $rng.Value2",
                "$rows = New-Object System.Collections.ArrayList",
                "if ($v -is [Array]) {",
                "  for ($r = 1; $r -le $v.GetLength(0); $r++) { $row = New-Object System.Collections.ArrayList; for ($c = 1; $c -le $v.GetLength(1); $c++) { [void]$row.Add($v.GetValue($r, $c)) }; [void]$rows.Add($row) }",
                "} else { $row = New-Object System.Collections.ArrayList; [void]$row.Add($v); [void]$rows.Add($row) }",
                "'OOA_JSON=' + (ConvertTo-Json -InputObject $rows -Depth 4 -Compress)"),
                List.of(name(workbook), sheet == null ? "" : sheet, range == null ? "" : range.strip()), timeout);
        Object j = json(out);
        List<List<Object>> rows = new ArrayList<>();
        if (j instanceof List<?> l) for (Object r : l) rows.add(r instanceof List<?> rl ? new ArrayList<>((List<Object>) rl) : new ArrayList<>(Collections.singletonList(r)));
        int width = rows.stream().mapToInt(List::size).max().orElse(0);
        List<String> cols = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int c = 0; c < width; c++) {
            Object h = header && !rows.isEmpty() && c < rows.get(0).size() ? rows.get(0).get(c) : null;
            String n = h == null || Values.text(h).isBlank() ? "Column" + (c + 1) : Values.text(h).strip();
            String u = n;
            for (int k = 2; !seen.add(u.toLowerCase(Locale.ROOT)); k++) u = n + "_" + k;
            cols.add(u);
        }
        List<Object> data = new ArrayList<>();
        for (List<Object> r : header && !rows.isEmpty() ? rows.subList(1, rows.size()) : rows) {
            while (r.size() < width) r.add(null);
            data.add(r);
        }
        return Map.of("table", Values.table(cols, data), "row_count", BigDecimal.valueOf(data.size()));
    }

    @Action(id = "excel_write_range", name = "Write to open workbook", category = "Excel", capabilities = "desktop",
            description = "Write a value, a list of rows or a Data Table into a sheet of a workbook that is open in Excel, starting at a cell; the person sees it appear. Saving is a separate step.",
            panel = "workbook, sheet, start_cell, values, include_header | timeout_seconds")
    @Output(name = "range", type = OoaType.STRING, description = "Address of the cells written, e.g. B2:E10.")
    public Map<String, Object> excelWriteRange(ActionContext ctx,
            @Input(name = "workbook", type = OoaType.STRING, example = "Sales.xlsx", description = D_WORKBOOK) String workbook,
            @Input(name = "sheet", type = OoaType.STRING, required = false, description = D_SHEET) String sheet,
            @Input(name = "start_cell", type = OoaType.STRING, required = false, defaultValue = "\"A1\"", description = "Top-left cell to write to.") String startCell,
            @Input(name = "values", description = "One value, a list of rows (each a list of values) or a Data Table.") Object values,
            @Input(name = "include_header", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "For a Data Table: write its column names as the first row.") boolean includeHeader,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "60", description = D_TIMEOUT) int timeout) {
        String start = startCell == null || startCell.isBlank() ? "A1" : startCell.strip();
        if (!start.matches("(?i)[A-Z]{1,3}\\d+")) throw new ActionException("EXCEL_OPEN.BAD_RANGE", "'" + startCell + "' is not a cell", "Use a cell such as B2.");
        List<List<Object>> rows = rowsOf(values, includeHeader);
        if (rows.isEmpty()) throw new ActionException("EXCEL_OPEN.NO_VALUES", "Nothing to write", "Give a value, a list of rows or a Data Table.");
        Path data = null;
        try {
            data = Files.createTempFile("ooa-attach-", ".json");
            Files.writeString(data, JsonLite.write(Values.toJsonable(rows)), StandardCharsets.UTF_8);
            String out = run(ctx, "Excel", EXCEL_FIND + String.join("\n",
                    "$rows = Get-Content -Raw -Encoding UTF8 $args[3] | ConvertFrom-Json",
                    "$h = @($rows).Count; $w = 0; foreach ($r in $rows) { $w = [Math]::Max($w, @($r).Count) }",
                    "$arr = New-Object 'object[,]' $h, $w",
                    "for ($i = 0; $i -lt $h; $i++) { $r = @($rows[$i]); for ($k = 0; $k -lt $r.Count; $k++) { $arr[$i, $k] = $r[$k] } }",
                    "$target = $ws.Range($args[2]).Resize($h, $w)",
                    "$target.Value2 = $arr",
                    "'OOA_JSON=' + (ConvertTo-Json -InputObject $target.Address($false, $false) -Compress)"),
                    List.of(name(workbook), sheet == null ? "" : sheet, start, data.toString()), timeout);
            return Map.of("range", String.valueOf(json(out)));
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), null, true);
        } finally { if (data != null) try { Files.deleteIfExists(data); } catch (IOException ignored) { } }
    }

    /** A value, a list of rows or a Data Table as rows of plain values (text, numbers, true/false). */
    @SuppressWarnings("unchecked")
    static List<List<Object>> rowsOf(Object values, boolean header) {
        List<List<Object>> rows = new ArrayList<>();
        if (values == null) return rows;
        if (values instanceof Map<?, ?> m && m.containsKey("columns") && m.containsKey("rows")) {
            if (header) rows.add(new ArrayList<>((List<Object>) m.get("columns")));
            for (Object r : (List<Object>) m.get("rows")) rows.add(new ArrayList<>((List<Object>) r));
        } else if (values instanceof List<?> l) {
            boolean nested = !l.isEmpty() && l.stream().allMatch(x -> x instanceof List<?>);
            if (nested) for (Object r : l) rows.add(new ArrayList<>((List<Object>) r));
            else for (Object v : l) rows.add(new ArrayList<>(Collections.singletonList(v)));   // a plain list goes down one column
        } else rows.add(new ArrayList<>(Collections.singletonList(values)));
        for (List<Object> r : rows) r.replaceAll(v -> v instanceof SecureString ? SecureString.MASK : v instanceof Map || v instanceof List ? Values.text(Values.toJsonable(v)) : v);
        return rows;
    }

    @Action(id = "excel_save", name = "Save open workbook", category = "Excel", capabilities = "desktop",
            description = "Save a workbook that is open in Excel, under its own name; it stays open.",
            panel = "workbook | timeout_seconds")
    public Map<String, Object> excelSave(ActionContext ctx,
            @Input(name = "workbook", type = OoaType.STRING, example = "Sales.xlsx", description = D_WORKBOOK) String workbook,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "120", description = D_TIMEOUT) int timeout) {
        run(ctx, "Excel", EXCEL_FIND + "if ($wb.ReadOnly) { throw 'The workbook is open read-only' }\n$wb.Save()\n'OOA_JSON=true'", List.of(name(workbook), ""), timeout);
        return Map.of();
    }

    // ---------- Word ----------

    @Action(id = "word_list_open", name = "List open documents", category = "Word", idempotent = true, capabilities = "desktop",
            description = "List the documents open in the running Word, with their full paths.")
    @Output(name = "documents", type = OoaType.LIST, description = "Records with name, path and saved (false when it has unsaved changes).")
    public Map<String, Object> wordListOpen(ActionContext ctx) {
        String out = run(ctx, "Word", ROT + String.join("\n",
                "$ErrorActionPreference = 'Stop'",
                "$wd = $null; try { $wd = [Runtime.InteropServices.Marshal]::GetActiveObject('Word.Application') } catch { }",
                "$docs = New-Object System.Collections.ArrayList",
                "if ($wd) { foreach ($d in $wd.Documents) { [void]$docs.Add($d) } }",
                "foreach ($p in (Get-OoaOpenFiles " + WORD_FILES + ")) { if (-not (@($docs) | Where-Object { $_.FullName -ieq $p })) { try { [void]$docs.Add([Runtime.InteropServices.Marshal]::BindToMoniker($p)) } catch { } } }",
                "if (-not $wd -and $docs.Count -eq 0) { 'OOA_NO_APP'; return }",
                "$list = New-Object System.Collections.ArrayList",
                "foreach ($d in $docs) { [void]$list.Add([ordered]@{ name = $d.Name; path = $d.FullName; saved = [bool]$d.Saved }) }",
                "'OOA_JSON=' + (ConvertTo-Json -InputObject $list -Depth 3 -Compress)"), List.of(), 60);
        Object j = json(out);
        return Map.of("documents", j instanceof List<?> l ? l : j == null ? List.of() : List.of(j));
    }

    @Action(id = "word_read_text", name = "Read open document", category = "Word", idempotent = true, capabilities = "desktop",
            description = "Read the text of a document that is open in Word, including changes not yet saved.",
            panel = "document | timeout_seconds")
    @Output(name = "text", type = OoaType.STRING, description = "The document's text, paragraphs on separate lines.")
    public Map<String, Object> wordReadText(ActionContext ctx,
            @Input(name = "document", type = OoaType.STRING, example = "Letter.docx", description = D_DOCUMENT) String document,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "60", description = D_TIMEOUT) int timeout) {
        String out = run(ctx, "Word", WORD_FIND + "'OOA_JSON=' + (ConvertTo-Json -InputObject ([string]$doc.Content.Text) -Compress)", List.of(name(document)), timeout);
        Object t = json(out);
        return Map.of("text", t == null ? "" : String.valueOf(t).replace("\r\n", "\n").replace('\r', '\n').replace("\u0007", ""));
    }

    @Action(id = "word_replace_text", name = "Replace text in open document", category = "Word", capabilities = "desktop",
            description = "Replace every occurrence of a text in a document that is open in Word, e.g. a {{name}} placeholder; the person sees it change. Saving is a separate step.",
            panel = "document, find, replace_with | match_case, whole_word, timeout_seconds")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of occurrences replaced.")
    public Map<String, Object> wordReplaceText(ActionContext ctx,
            @Input(name = "document", type = OoaType.STRING, example = "Letter.docx", description = D_DOCUMENT) String document,
            @Input(name = "find", type = OoaType.STRING, example = "{{name}}", description = "Text to find (up to 255 characters).") String find,
            @Input(name = "replace_with", type = OoaType.STRING, required = false, defaultValue = "\"\"", description = "New text (up to 255 characters); empty removes it.") String replaceWith,
            @Input(name = "match_case", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Match upper and lower case exactly.") boolean matchCase,
            @Input(name = "whole_word", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Only whole words.") boolean wholeWord,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "60", description = D_TIMEOUT) int timeout) {
        if (find == null || find.isEmpty()) throw new ActionException("WORD_OPEN.NO_TEXT", "Nothing to find", "Fill in the text to find.");
        if (find.length() > 255 || (replaceWith != null && replaceWith.length() > 255))
            throw new ActionException("WORD_OPEN.TOO_LONG", "Word replaces at most 255 characters at a time", "Split the text, or use Word (no Office needed) for long texts.");
        // counts with Find in a loop over a duplicate range, then replaces all in one call (one undo step for the person)
        String out = run(ctx, "Word", WORD_FIND + String.join("\n",
                "$n = 0; $r = $doc.Content.Duplicate",
                "$f = $r.Find; $f.ClearFormatting(); $f.Text = $args[1]; $f.MatchCase = ($args[3] -eq '1'); $f.MatchWholeWord = ($args[4] -eq '1'); $f.Forward = $true; $f.Wrap = 0",
                "while ($f.Execute() -and $n -lt 100000) { $n++; [void]$r.Collapse(0) }",
                "if ($n -gt 0) {",
                "  $g = $doc.Content.Find; $g.ClearFormatting(); $g.Replacement.ClearFormatting()",
                "  $g.Text = $args[1]; $g.Replacement.Text = $args[2]; $g.MatchCase = ($args[3] -eq '1'); $g.MatchWholeWord = ($args[4] -eq '1'); $g.Forward = $true; $g.Wrap = 1",
                "  [void]$g.Execute([Type]::Missing, [Type]::Missing, [Type]::Missing, [Type]::Missing, [Type]::Missing, [Type]::Missing, [Type]::Missing, [Type]::Missing, [Type]::Missing, [Type]::Missing, 2)",
                "}",
                "'OOA_JSON=' + $n"),
                List.of(name(document), find, replaceWith == null ? "" : replaceWith, matchCase ? "1" : "0", wholeWord ? "1" : "0"), timeout);
        Object n = json(out);
        return Map.of("count", n instanceof Number x ? BigDecimal.valueOf(x.longValue()) : BigDecimal.ZERO);
    }

    @Action(id = "word_append_text", name = "Append text to open document", category = "Word", capabilities = "desktop",
            description = "Add text at the end of a document that is open in Word, as a new paragraph by default. Saving is a separate step.",
            panel = "document, text, new_paragraph | timeout_seconds")
    public Map<String, Object> wordAppendText(ActionContext ctx,
            @Input(name = "document", type = OoaType.STRING, example = "Letter.docx", description = D_DOCUMENT) String document,
            @Input(name = "text", type = OoaType.STRING, description = "Text to add; line breaks start new paragraphs.") String text,
            @Input(name = "new_paragraph", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Start a new paragraph first.") boolean newParagraph,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "60", description = D_TIMEOUT) int timeout) {
        if (text == null || text.isEmpty()) throw new ActionException("WORD_OPEN.NO_TEXT", "Nothing to add", "Fill in the text.");
        run(ctx, "Word", WORD_FIND + String.join("\n",
                "$t = $args[1] -replace \"`r`n|`n\", \"`r\"",
                "if ($args[2] -eq '1') { $doc.Content.InsertParagraphAfter() }",
                "$doc.Content.InsertAfter($t)",
                "'OOA_JSON=true'"), List.of(name(document), text, newParagraph ? "1" : "0"), timeout);
        return Map.of();
    }

    @Action(id = "word_save", name = "Save open document", category = "Word", capabilities = "desktop",
            description = "Save a document that is open in Word, under its own name; it stays open.",
            panel = "document | timeout_seconds")
    public Map<String, Object> wordSave(ActionContext ctx,
            @Input(name = "document", type = OoaType.STRING, example = "Letter.docx", description = D_DOCUMENT) String document,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "120", description = D_TIMEOUT) int timeout) {
        run(ctx, "Word", WORD_FIND + "if ($doc.ReadOnly) { throw 'The document is open read-only' }\n$doc.Save()\n'OOA_JSON=true'", List.of(name(document)), timeout);
        return Map.of();
    }
}
