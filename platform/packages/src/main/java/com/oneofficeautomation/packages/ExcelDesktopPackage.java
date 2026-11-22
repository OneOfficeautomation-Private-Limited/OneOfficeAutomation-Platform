package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Drives the installed Microsoft Excel (Windows) through its COM automation interface, for what only Excel itself
 * can do: run VBA macros, refresh queries and pivots, update links, export to PDF and convert old formats. File paths
 * and macro arguments are passed as script arguments and passwords as environment variables, never pasted into
 * script code or shown on the command line.
 */
@OoaPackage(id = "ooa.excel_desktop", name = "Excel desktop", version = "1.0.0",
        description = "Run macros, refresh data and pivots, update links, export to PDF and convert workbooks using the installed Microsoft Excel.")
public final class ExcelDesktopPackage {

    static final String PASSWORD_ENV = "OOA_XL_PASSWORD";
    static final String NEW_PASSWORD_ENV = "OOA_XL_NEW_PASSWORD";

    /** A PowerShell script body with its arguments and the secrets handed over in the environment. */
    record Script(String body, List<String> args, Map<String, String> env, boolean visible) {
        String full() { return prelude(visible) + body + CLOSE; }
    }

    static String prelude(boolean visible) {
        return "$ErrorActionPreference = 'Stop'\n"
                + "$xl = New-Object -ComObject Excel.Application\n$xl.DisplayAlerts = $false\n$xl.Visible = $" + visible + "\n$xl.AskToUpdateLinks = $false\n"
                + "try {\n";
    }

    private static final String CLOSE = "} finally {\n  if ($wb) { $wb.Close($false) | Out-Null }\n  $xl.Quit()\n  [void][Runtime.InteropServices.Marshal]::ReleaseComObject($xl)\n}\n";

    /** Workbooks.Open call: file name, do not update links, read-only flag, and the open password from the environment when one is set. */
    static String open(boolean readOnly, boolean password) {
        return "  $wb = $xl.Workbooks.Open($args[0], 0, $" + readOnly + (password ? ", 5, $env:" + PASSWORD_ENV : "") + ")\n";
    }

    private static Map<String, String> env(Object password) {
        Map<String, String> env = new HashMap<>();
        String pw = secret(password);
        if (pw != null) env.put(PASSWORD_ENV, pw);
        return env;
    }

    static String secret(Object password) {
        if (password == null) return null;
        String s = password instanceof SecureString ss ? ss.reveal() : Values.text(password);
        return s.isEmpty() ? null : s;
    }

    private static ProcessRunner.Result excel(ActionContext ctx, Script s, int timeout) {
        if (!ProcessRunner.WINDOWS) throw new ActionException("SYSTEM.WINDOWS_ONLY", "Desktop Excel automation needs Windows with Excel installed",
                "Use Excel (no Office needed) for reading and writing cells on any runner.");
        Path script = null;
        try {
            script = Files.createTempFile("ooa-excel-", ".ps1");
            Files.writeString(script, "\uFEFF" + ScriptPackage.UTF8_PREAMBLE + "\n" + s.full(), StandardCharsets.UTF_8);  // BOM: Windows PowerShell reads it as UTF-8
            List<String> cmd = new ArrayList<>(List.of("powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", script.toString()));
            cmd.addAll(s.args());
            ProcessRunner.Result r = ProcessRunner.run(ctx, cmd, ctx.workDir(), null, timeout, s.env(), StandardCharsets.UTF_8);
            if (r.exitCode() != 0) {
                String err = r.stderr().isBlank() ? r.stdout() : r.stderr();
                if (err.contains("80040154") || err.contains("Retrieving the COM class factory"))
                    throw new ActionException("EXCEL_DESKTOP.NOT_INSTALLED", "Microsoft Excel is not installed on this runner", "Install Excel, or use Excel (no Office needed).");
                if (err.contains("Microsoft Print to PDF printer is missing"))
                    throw new ActionException("EXCEL_DESKTOP.PDF_UNAVAILABLE", NO_PDF + ".", "Install the add-in, a newer Office, or the Microsoft Print to PDF printer (Windows features).");
                if (err.toLowerCase(Locale.ROOT).contains("password"))
                    throw new ActionException("EXCEL_DESKTOP.WRONG_PASSWORD", "Excel could not open the workbook with the given password", "Check the password stored in the credential vault.");
                throw new ActionException("EXCEL_DESKTOP.FAILED", "Excel reported an error: " + ApplicationPackage.tail(err), "Check the workbook, macro name and that macros are allowed.");
            }
            return r;
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), "Check that the file or folder exists, is not open in another program, and that the runner's account may read and write it.", true);
        } finally { if (script != null) try { Files.deleteIfExists(script); } catch (IOException ignored) { } }
    }

    private static String file(ActionContext ctx, String path) {
        Path p = ctx.resolvePath(path).toAbsolutePath();
        if (!Files.isRegularFile(p)) throw new ActionException("FILE.NOT_FOUND", "Workbook not found: " + p, "Check the path.");
        return p.toString();
    }

    private static Path target(ActionContext ctx, String target, boolean overwrite) {
        Path t = ctx.resolvePath(target).toAbsolutePath();
        if (!overwrite && Files.exists(t)) throw new ActionException("EXCEL_DESKTOP.EXISTS", "A file already exists at " + t, "Set overwrite to true or choose another path.");
        try { if (t.getParent() != null) Files.createDirectories(t.getParent()); } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), "Check that the file or folder exists, is not open in another program, and that the runner's account may read and write it."); }
        return t;
    }

    private static final String D_PASSWORD = "Password needed to open the workbook; passed to Excel through the environment, not the command line.";
    private static final String D_SESSION = "An Excel session open in this run: the step works on that workbook as it is now, changes not yet saved included; the path is not needed then (G3).";

    /** The workbook path a step gives when it names no session (G3: the path became optional with the session choice). */
    static String needPath(String path) {
        if (path == null || path.isBlank()) throw new ActionException("EXCEL_DESKTOP.NO_WORKBOOK", "Give the workbook's path or an open Excel session", "Fill in the path, or choose An open Excel session and name it.");
        return path;
    }

    /** The workbook an Excel step names by session, or null when the step uses a path. */
    static OpenWorkbook openWorkbook(ActionContext ctx, String session) {
        if (session == null || session.isBlank()) return null;
        Object s = ctx.sessions().get(session.strip());
        if (s instanceof OpenWorkbook w) return w;
        throw new ActionException("EXCEL_DESKTOP.NO_SESSION", "No Excel session named '" + session.strip() + "' is open",
                "Open the workbook with Excel's Open step first, or leave the session empty and give the path.");
    }

    /** A copy of the open workbook in a new temporary folder, under the workbook's own file name (the extension keeps macros). */
    static Path copyOf(OpenWorkbook w) {
        try {
            Path dir = Files.createTempDirectory("ooa-xl-");
            Path copy = dir.resolve(w.file().getFileName().toString());
            w.writeCopy(copy);
            return copy;
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), "Check that the temporary folder may be written."); }
    }

    static void deleteCopy(Path copy) {
        try { Files.deleteIfExists(copy); Files.deleteIfExists(copy.getParent()); } catch (IOException ignored) { }
    }

    /** The target path when a file is already there: replace it, stop (fail), or add a number such as (2) to the name (number). */
    static Path target(ActionContext ctx, String target, boolean overwrite, String ifExists) {
        String how = ifExists == null || ifExists.isBlank() ? (overwrite ? "replace" : "fail") : ifExists.strip().toLowerCase(Locale.ROOT);
        if (!overwrite && how.equals("replace")) how = "fail";   // old steps that turned overwrite off keep stopping
        if (!how.equals("number")) return target(ctx, target, !how.equals("fail"));
        Path t = target(ctx, target, true);
        String n = t.getFileName().toString();
        for (int i = 2; Files.exists(t); i++) t = t.resolveSibling(n.replaceFirst("(\\.[^.]+)?$", " (" + i + ")$1"));
        return t;
    }
    private static final String D_VISIBLE = "Show the Excel window while the agent works, e.g. to watch a macro; hidden by default.";

    // ------------------------------------------------------------------------------------------------

    static Script runMacroScript(String file, String macro, List<Object> args, boolean save, Object password, boolean visible) {
        if (!macro.matches("^[A-Za-z0-9_.!' \\[\\]-]{1,200}$")) throw new ActionException("EXCEL_DESKTOP.BAD_MACRO", "Invalid macro name '" + macro + "'", "Write the macro's name as Excel shows it (Module1.MyMacro), with letters, digits, . and _ only.");
        List<String> a = new ArrayList<>(List.of(file, macro, save ? "1" : "0"));
        if (args != null) { if (args.size() > 10) throw new ActionException("EXCEL_DESKTOP.TOO_MANY_ARGS", "At most 10 macro arguments", "Pass at most 10 arguments; put more in cells the macro reads."); for (Object o : args) a.add(Values.text(o)); }
        String body = open(false, secret(password) != null) + "  $rest = @($args | Select-Object -Skip 3)\n"
                + "  $r = $xl.GetType().InvokeMember('Run', [Reflection.BindingFlags]::InvokeMethod, $null, $xl, (@($args[1]) + $rest))\n"
                + "  if ($args[2] -eq '1') { $wb.Save() }\n  if ($r -ne $null) { Write-Output $r }\n";
        return new Script(body, a, env(password), visible);
    }

    public Map<String, Object> runMacro(ActionContext ctx, String path, String macro, List<Object> args, boolean save, int timeout) {
        return runMacro(ctx, path, macro, args, save, timeout, null, false);
    }

    public Map<String, Object> runMacro(ActionContext ctx, String path, String macro, List<Object> args, boolean save, int timeout, Object password, boolean visible) {
        return runMacro(ctx, path, macro, args, save, timeout, password, visible, null);
    }

    @Action(id = "run_macro", name = "Run macro", category = "Macros", timeoutSeconds = 3600, capabilities = "desktop",
            description = "Run a VBA macro, with up to 10 arguments, in a macro-enabled workbook (a file, or a workbook open in an Excel session, unsaved changes included) and optionally save.",
            panel = "(Workbook: A file = path / An open Excel session = session), macro, arguments, save | timeout_seconds, password, visible")
    @Output(name = "result", type = OoaType.STRING, description = "Value returned by the macro (for Functions), as text.")
    public Map<String, Object> runMacro(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, required = false, description = "Workbook (.xlsm/.xlsb/.xls); not needed when a session is given.") String path,
            @Input(name = "macro", type = OoaType.STRING, example = "Module1.BuildReport", description = "Macro name.") String macro,
            @Input(name = "arguments", type = OoaType.LIST, required = false, description = "Arguments passed to the macro (text).") List<Object> args,
            @Input(name = "save", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Save the workbook afterwards.") boolean save,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "600", description = "Maximum run time.") int timeout,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, description = D_PASSWORD) Object password,
            @Input(name = "visible", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = D_VISIBLE) boolean visible,
            @Input(name = "session", type = OoaType.STRING, required = false, description = D_SESSION) String session) {
        OpenWorkbook w = openWorkbook(ctx, session);
        if (w == null) {
            Script s = runMacroScript(file(ctx, needPath(path)), macro, args, save, password, visible);
            return Map.of("result", excel(ctx, s, timeout).stdout().strip());
        }
        // G3: the macro runs on a copy of the open workbook; the session then holds what the macro made, saved when asked
        Path copy = copyOf(w);
        try {
            String result = excel(ctx, runMacroScript(copy.toString(), macro, args, true, null, visible), timeout).stdout().strip();
            w.replaceWith(copy);
            if (save) w.save();
            return Map.of("result", result);
        } finally { deleteCopy(copy); }
    }

    static Script refreshScript(String file, boolean save, Object password) {
        return new Script(open(false, secret(password) != null) + "  $wb.RefreshAll()\n  $xl.CalculateUntilAsyncQueriesDone()\n  $xl.Calculate()\n"
                + "  if ($args[1] -eq '1') { $wb.Save() }\n", List.of(file, save ? "1" : "0"), env(password), false);
    }

    public Map<String, Object> refreshAll(ActionContext ctx, String path, int timeout) { return refreshAll(ctx, path, timeout, true, null); }

    @Action(id = "refresh_all", name = "Refresh all data", category = "Data", timeoutSeconds = 3600, capabilities = "desktop",
            description = "Refresh every query, connection and pivot table in the workbook, wait for completion, recalculate and save unless told not to.",
            panel = "path, save | timeout_seconds, password")
    public Map<String, Object> refreshAll(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "Workbook.") String path,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "900", description = "Maximum time.") int timeout,
            @Input(name = "save", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Save the workbook after refreshing.") boolean save,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, description = D_PASSWORD) Object password) {
        excel(ctx, refreshScript(file(ctx, path), save, password), timeout);
        return Map.of();
    }

    static Script updateLinksScript(String file, boolean save, Object password) {
        String body = open(false, secret(password) != null)
                + "  $links = $wb.LinkSources(1)\n  $n = 0\n"
                + "  if ($links) { foreach ($l in $links) { $wb.UpdateLink($l, 1); $n++ } }\n"
                + "  if ($args[1] -eq '1') { $wb.Save() }\n  Write-Output $n\n";
        return new Script(body, List.of(file, save ? "1" : "0"), env(password), false);
    }

    @Action(id = "update_links", name = "Update workbook links", category = "Data", timeoutSeconds = 3600, capabilities = "desktop",
            description = "Open the workbook in Excel, refresh the values taken from every linked workbook and save.",
            panel = "path, save | password, timeout_seconds")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of linked workbooks updated.")
    public Map<String, Object> updateLinks(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "Workbook.") String path,
            @Input(name = "save", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Save the workbook after updating.") boolean save,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, description = D_PASSWORD) Object password,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "600", description = "Maximum time.") int timeout) {
        String out = excel(ctx, updateLinksScript(file(ctx, path), save, password), timeout).stdout().strip();
        String last = out.isEmpty() ? "0" : out.substring(out.lastIndexOf('\n') + 1).trim();
        return Map.of("count", new java.math.BigDecimal(last.matches("\\d+") ? last : "0"));
    }

    static final String FROM_ENV = "OOA_XL_FROM", TO_ENV = "OOA_XL_TO", SHEET_ENV = "OOA_XL_SHEET", ACTIVE_ENV = "OOA_XL_ACTIVE";

    /**
     * Optional values travel in environment variables: they used to be passed as "-" when empty, and powershell -File reads
     * a bare "-" as a parameter name, so every export without a sheet or page range failed. When Excel has no PDF export
     * (Office 2007 without Microsoft's add-in answers "Value does not fall within the expected range"), the sheet or
     * workbook is printed with the Microsoft Print to PDF printer instead.
     */
    static Script exportPdfScript(String file, String target, String sheet, String quality, boolean includeProperties, boolean ignorePrintAreas,
                                  Integer from, Integer to, Object password) {
        String q = quality == null || quality.isBlank() || quality.equalsIgnoreCase("standard") ? "0" : quality.equalsIgnoreCase("minimum") ? "1" : null;
        if (q == null) throw new ActionException("EXCEL_DESKTOP.BAD_QUALITY", "Quality must be standard or minimum", "Use standard or minimum.");
        if (from != null && from < 1 || to != null && to < 1 || from != null && to != null && to < from)
            throw new ActionException("EXCEL_DESKTOP.BAD_PAGES", "from_page and to_page must be 1 or more, with to_page not before from_page", "Fix the page numbers.");
        String body = open(true, secret(password) != null)
                + "  $from = if ($env:" + FROM_ENV + ") { [int]$env:" + FROM_ENV + " } else { [Type]::Missing }\n"
                + "  $to = if ($env:" + TO_ENV + ") { [int]$env:" + TO_ENV + " } else { [Type]::Missing }\n"
                + "  $what = if ($env:" + SHEET_ENV + ") { $wb.Worksheets.Item($env:" + SHEET_ENV + ") } elseif ($env:" + ACTIVE_ENV + ") { $wb.ActiveSheet } else { $wb }\n"
                + "  try { $what.ExportAsFixedFormat(0, $args[1], [int]$args[2], ($args[3] -eq '1'), ($args[4] -eq '1'), $from, $to) }\n"
                + "  catch {\n"
                + "    $why = $_.Exception.Message\n"
                + "    if (-not (Get-CimInstance Win32_Printer -Filter \"Name='Microsoft Print to PDF'\")) { throw \"" + NO_PDF + " ($why)\" }\n"
                + "    [Console]::Error.WriteLine(\"OOA_WARN Excel's own PDF export is not available ($why); the PDF was printed with Microsoft Print to PDF, so quality, document properties and print-area options do not apply\")\n"
                + "    $what.PrintOut($from, $to, 1, $false, 'Microsoft Print to PDF', $true, $true, $args[1])\n"
                + "  }\n";
        List<String> a = List.of(file, target, q, includeProperties ? "1" : "0", ignorePrintAreas ? "1" : "0");
        Map<String, String> env = env(password);
        if (from != null) env.put(FROM_ENV, String.valueOf(from));
        if (to != null) env.put(TO_ENV, String.valueOf(to));
        if (sheet != null && !sheet.isBlank()) env.put(SHEET_ENV, sheet);
        return new Script(body, a, env, false);
    }

    static final String NO_PDF = "Excel cannot export to PDF on this runner and the Microsoft Print to PDF printer is missing. Office 2007 needs Microsoft's Save as PDF or XPS add-in; Office 2010 and later export directly";

    public Map<String, Object> exportPdf(ActionContext ctx, String path, String target, String sheet) {
        return exportPdf(ctx, path, target, sheet, "standard", true, false, null, null, true, null);
    }

    public Map<String, Object> exportPdf(ActionContext ctx, String path, String target, String sheet, String quality, boolean includeProperties,
                                         boolean ignorePrintAreas, Integer from, Integer to, boolean overwrite, Object password) {
        return exportPdf(ctx, path, target, sheet, quality, includeProperties, ignorePrintAreas, from, to, overwrite, password, null, false, null);
    }

    @Action(id = "export_pdf", name = "Export to PDF", category = "Convert", timeoutSeconds = 600, capabilities = "desktop",
            description = "Export the whole workbook, the active sheet or one sheet to PDF (from a file, or a workbook open in an Excel session, unsaved changes included), choosing quality, page range, print areas and document properties.",
            panel = "(Workbook: A file = path / An open Excel session = session), (Sheets: The whole workbook = / The active sheet = active_sheet / One sheet = sheet), target, if_exists | quality, include_properties, ignore_print_areas, from_page, to_page, password, overwrite")
    @Output(name = "path", type = OoaType.FILE, description = "The PDF file written (with a number added when one was already there).")
    public Map<String, Object> exportPdf(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, required = false, description = "Workbook; not needed when a session is given.") String path,
            @Input(name = "target", type = OoaType.FILE, description = "PDF file to create.") String target,
            @Input(name = "sheet", type = OoaType.STRING, required = false, description = "Only this sheet; the whole workbook when empty.") String sheet,
            @Input(name = "quality", type = OoaType.STRING, required = false, defaultValue = "\"standard\"", enumValues = {"standard", "minimum"},
                    description = "standard for printing; minimum for a smaller file to publish online.") String quality,
            @Input(name = "include_properties", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Copy the workbook's title, author and other properties into the PDF.") boolean includeProperties,
            @Input(name = "ignore_print_areas", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Export whole sheets even where a print area is set.") boolean ignorePrintAreas,
            @Input(name = "from_page", type = OoaType.NUMBER, required = false, description = "First printed page to export; from the first page when empty.") Integer from,
            @Input(name = "to_page", type = OoaType.NUMBER, required = false, description = "Last printed page to export; to the last page when empty.") Integer to,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Replace the PDF when it already exists; false stops with EXCEL_DESKTOP.EXISTS.") boolean overwrite,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, description = D_PASSWORD) Object password,
            @Input(name = "session", type = OoaType.STRING, required = false, description = D_SESSION) String session,
            @Input(name = "active_sheet", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Only the active sheet (G3).") boolean activeSheet,
            @Input(name = "if_exists", type = OoaType.STRING, required = false, newStepDefault = "\"number\"", enumValues = {"replace", "fail", "number"},
                    description = "When the PDF is already there: replace it, stop the step, or add a number such as (2) to the name (G3); follows overwrite when empty.") String ifExists) {
        OpenWorkbook w = openWorkbook(ctx, session);
        Path copy = w == null ? null : copyOf(w);
        try {
            String src = copy != null ? copy.toString() : file(ctx, needPath(path));
            if (activeSheet && (sheet == null || sheet.isBlank()) && w != null) sheet = w.activeSheet();
            Path t = target(ctx, target, overwrite, ifExists);
            Script script = exportPdfScript(src, t.toString(), sheet, quality, includeProperties, ignorePrintAreas, from, to, copy != null ? null : password);
            if (activeSheet && (sheet == null || sheet.isBlank())) script.env().put(ACTIVE_ENV, "1");
            ProcessRunner.Result r = excel(ctx, script, 600);
            for (String line : r.stderr().split("\\R")) if (line.startsWith("OOA_WARN ")) ctx.warn(line.substring(9));
            OfficePdf.waitForFile(t, 120);
            return Map.of("path", t.toString());
        } finally { if (copy != null) deleteCopy(copy); }
    }

    /** Excel's XlFileFormat number for a target extension, or null when not supported. */
    static String formatCode(String ext) {
        return switch (ext) {
            case "xlsx" -> "51"; case "xlsm" -> "52"; case "xlsb" -> "50"; case "xls" -> "56"; case "csv" -> "6";
            case "txt" -> "42"; case "ods" -> "60"; case "xltx" -> "54"; case "xltm" -> "53";
            default -> null;
        };
    }

    static Script convertScript(String file, String target, String format, Object password, Object newPassword) {
        Map<String, String> env = env(password);
        String np = secret(newPassword);
        if (np != null) env.put(NEW_PASSWORD_ENV, np);
        String save = np != null ? "  $wb.SaveAs($args[1], [int]$args[2], $env:" + NEW_PASSWORD_ENV + ")\n" : "  $wb.SaveAs($args[1], [int]$args[2])\n";
        return new Script(open(false, secret(password) != null) + save, List.of(file, target, format), env, false);
    }

    public Map<String, Object> convert(ActionContext ctx, String path, String target) { return convert(ctx, path, target, true, null, null); }

    @Action(id = "convert", name = "Convert workbook", category = "Convert", timeoutSeconds = 600, capabilities = "desktop",
            description = "Save a workbook in another format, e.g. .xls to .xlsx or .csv to .xlsx, optionally with a password on the new file.",
            panel = "path, target, overwrite, [The workbook has a password: password], [Protect the new file with a password: new_password]")
    public Map<String, Object> convert(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "Source file.") String path,
            @Input(name = "target", type = OoaType.FILE, description = "Target file; the format follows its extension (.xlsx, .xlsm, .xlsb, .xls, .csv, .txt, .ods, .xltx, .xltm).") String target,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Replace the target when it already exists; false stops with EXCEL_DESKTOP.EXISTS.") boolean overwrite,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, description = D_PASSWORD) Object password,
            @Input(name = "new_password", type = OoaType.CREDENTIAL, required = false, description = "Password needed to open the converted file; none when empty.") Object newPassword) {
        String src = file(ctx, path);
        Path t = ctx.resolvePath(target).toAbsolutePath();
        String ext = t.getFileName().toString().replaceAll("^.*\\.", "").toLowerCase(Locale.ROOT);
        String format = formatCode(ext);
        if (format == null) throw new ActionException("EXCEL_DESKTOP.BAD_FORMAT", "Unsupported target format ." + ext, "Use .xlsx, .xlsm, .xlsb, .xls, .csv, .txt, .ods, .xltx or .xltm.");
        target(ctx, target, overwrite);
        excel(ctx, convertScript(src, t.toString(), format, password, newPassword), 600);
        return Map.of();
    }
}
