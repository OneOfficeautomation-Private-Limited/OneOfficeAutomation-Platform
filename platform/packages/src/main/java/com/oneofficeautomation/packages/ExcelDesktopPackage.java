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
@OoaPackage(id = "ooa.excel_desktop", name = "Excel (desktop, needs Excel)", version = "1.0.0",
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
            Files.writeString(script, s.full(), StandardCharsets.UTF_8);
            List<String> cmd = new ArrayList<>(List.of("powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", script.toString()));
            cmd.addAll(s.args());
            ProcessRunner.Result r = ProcessRunner.run(ctx, cmd, ctx.workDir(), null, timeout, s.env(), StandardCharsets.UTF_8);
            if (r.exitCode() != 0) {
                String err = r.stderr().isBlank() ? r.stdout() : r.stderr();
                if (err.contains("80040154") || err.contains("Retrieving the COM class factory"))
                    throw new ActionException("EXCEL_DESKTOP.NOT_INSTALLED", "Microsoft Excel is not installed on this runner", "Install Excel, or use Excel (no Office needed).");
                if (err.toLowerCase(Locale.ROOT).contains("password"))
                    throw new ActionException("EXCEL_DESKTOP.WRONG_PASSWORD", "Excel could not open the workbook with the given password", "Check the password stored in the credential vault.");
                throw new ActionException("EXCEL_DESKTOP.FAILED", "Excel reported an error: " + ApplicationPackage.tail(err), "Check the workbook, macro name and that macros are allowed.");
            }
            return r;
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), null, true);
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
        try { if (t.getParent() != null) Files.createDirectories(t.getParent()); } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage()); }
        return t;
    }

    private static final String D_PASSWORD = "Password needed to open the workbook; passed to Excel through the environment, not the command line.";
    private static final String D_VISIBLE = "Show the Excel window while the agent works, e.g. to watch a macro; hidden by default.";

    // ------------------------------------------------------------------------------------------------

    static Script runMacroScript(String file, String macro, List<Object> args, boolean save, Object password, boolean visible) {
        if (!macro.matches("^[A-Za-z0-9_.!' \\[\\]-]{1,200}$")) throw new ActionException("EXCEL_DESKTOP.BAD_MACRO", "Invalid macro name '" + macro + "'");
        List<String> a = new ArrayList<>(List.of(file, macro, save ? "1" : "0"));
        if (args != null) { if (args.size() > 10) throw new ActionException("EXCEL_DESKTOP.TOO_MANY_ARGS", "At most 10 macro arguments"); for (Object o : args) a.add(Values.text(o)); }
        String body = open(false, secret(password) != null) + "  $rest = @($args | Select-Object -Skip 3)\n"
                + "  $r = $xl.GetType().InvokeMember('Run', [Reflection.BindingFlags]::InvokeMethod, $null, $xl, (@($args[1]) + $rest))\n"
                + "  if ($args[2] -eq '1') { $wb.Save() }\n  if ($r -ne $null) { Write-Output $r }\n";
        return new Script(body, a, env(password), visible);
    }

    public Map<String, Object> runMacro(ActionContext ctx, String path, String macro, List<Object> args, boolean save, int timeout) {
        return runMacro(ctx, path, macro, args, save, timeout, null, false);
    }

    @Action(id = "run_macro", name = "Run macro", category = "Macros", timeoutSeconds = 3600, capabilities = "desktop",
            description = "Open a macro-enabled workbook, optionally password-protected, run a VBA macro with up to 10 arguments and optionally save.")
    @Output(name = "result", type = OoaType.STRING, description = "Value returned by the macro (for Functions), as text.")
    public Map<String, Object> runMacro(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "Workbook (.xlsm/.xlsb/.xls).") String path,
            @Input(name = "macro", type = OoaType.STRING, example = "Module1.BuildReport", description = "Macro name.") String macro,
            @Input(name = "arguments", type = OoaType.LIST, required = false, description = "Arguments passed to the macro (text).") List<Object> args,
            @Input(name = "save", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Save the workbook afterwards.") boolean save,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "600", description = "Maximum run time.") int timeout,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, description = D_PASSWORD) Object password,
            @Input(name = "visible", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = D_VISIBLE) boolean visible) {
        Script s = runMacroScript(file(ctx, path), macro, args, save, password, visible);
        return Map.of("result", excel(ctx, s, timeout).stdout().strip());
    }

    static Script refreshScript(String file, boolean save, Object password) {
        return new Script(open(false, secret(password) != null) + "  $wb.RefreshAll()\n  $xl.CalculateUntilAsyncQueriesDone()\n  $xl.Calculate()\n"
                + "  if ($args[1] -eq '1') { $wb.Save() }\n", List.of(file, save ? "1" : "0"), env(password), false);
    }

    public Map<String, Object> refreshAll(ActionContext ctx, String path, int timeout) { return refreshAll(ctx, path, timeout, true, null); }

    @Action(id = "refresh_all", name = "Refresh all data", category = "Data", timeoutSeconds = 3600, capabilities = "desktop",
            description = "Refresh every query, connection and pivot table in the workbook, wait for completion, recalculate and save unless told not to.")
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
            description = "Open the workbook in Excel, refresh the values taken from every linked workbook and save.")
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

    static Script exportPdfScript(String file, String target, String sheet, String quality, boolean includeProperties, boolean ignorePrintAreas,
                                  Integer from, Integer to, Object password) {
        String q = quality == null || quality.isBlank() || quality.equalsIgnoreCase("standard") ? "0" : quality.equalsIgnoreCase("minimum") ? "1" : null;
        if (q == null) throw new ActionException("EXCEL_DESKTOP.BAD_QUALITY", "Quality must be standard or minimum", "Use standard or minimum.");
        if (from != null && from < 1 || to != null && to < 1 || from != null && to != null && to < from)
            throw new ActionException("EXCEL_DESKTOP.BAD_PAGES", "from_page and to_page must be 1 or more, with to_page not before from_page", "Fix the page numbers.");
        String body = open(true, secret(password) != null)
                + "  $from = if ($args[5] -ne '-') { [int]$args[5] } else { [Type]::Missing }\n"
                + "  $to = if ($args[6] -ne '-') { [int]$args[6] } else { [Type]::Missing }\n"
                + "  $what = if ($args[7] -ne '-') { $wb.Worksheets.Item($args[7]) } else { $wb }\n"
                + "  $what.ExportAsFixedFormat(0, $args[1], [int]$args[2], ($args[3] -eq '1'), ($args[4] -eq '1'), $from, $to)\n";
        List<String> a = List.of(file, target, q, includeProperties ? "1" : "0", ignorePrintAreas ? "1" : "0",
                from == null ? "-" : String.valueOf(from), to == null ? "-" : String.valueOf(to), sheet == null || sheet.isBlank() ? "-" : sheet);
        return new Script(body, a, env(password), false);
    }

    public Map<String, Object> exportPdf(ActionContext ctx, String path, String target, String sheet) {
        return exportPdf(ctx, path, target, sheet, "standard", true, false, null, null, true, null);
    }

    @Action(id = "export_pdf", name = "Export to PDF", category = "Convert", timeoutSeconds = 600, capabilities = "desktop",
            description = "Export the whole workbook, or one sheet, to PDF, choosing quality, page range, print areas and document properties.")
    public Map<String, Object> exportPdf(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "Workbook.") String path,
            @Input(name = "target", type = OoaType.FILE, description = "PDF file to create.") String target,
            @Input(name = "sheet", type = OoaType.STRING, required = false, description = "Only this sheet; the whole workbook when empty.") String sheet,
            @Input(name = "quality", type = OoaType.STRING, required = false, defaultValue = "\"standard\"", enumValues = {"standard", "minimum"},
                    description = "standard for printing; minimum for a smaller file to publish online.") String quality,
            @Input(name = "include_properties", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Copy the workbook's title, author and other properties into the PDF.") boolean includeProperties,
            @Input(name = "ignore_print_areas", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Export whole sheets even where a print area is set.") boolean ignorePrintAreas,
            @Input(name = "from_page", type = OoaType.NUMBER, required = false, description = "First printed page to export; from the first page when empty.") Integer from,
            @Input(name = "to_page", type = OoaType.NUMBER, required = false, description = "Last printed page to export; to the last page when empty.") Integer to,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Replace the PDF when it already exists; false stops with EXCEL_DESKTOP.EXISTS.") boolean overwrite,
            @Input(name = "password", type = OoaType.CREDENTIAL, required = false, description = D_PASSWORD) Object password) {
        String src = file(ctx, path);
        Path t = target(ctx, target, overwrite);
        excel(ctx, exportPdfScript(src, t.toString(), sheet, quality, includeProperties, ignorePrintAreas, from, to, password), 600);
        return Map.of();
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
            description = "Save a workbook in another format, e.g. .xls to .xlsx or .csv to .xlsx, optionally with a password on the new file.")
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
