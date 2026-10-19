package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Drives the installed Microsoft Excel (Windows) through its COM automation interface, for what only Excel itself
 * can do: run VBA macros, refresh queries and pivots, export to PDF and convert old formats. File paths and macro
 * arguments are passed as script arguments, never pasted into script code.
 */
@OoaPackage(id = "ooa.excel_desktop", name = "Excel (desktop, needs Excel)", version = "1.0.0",
        description = "Run macros, refresh data and pivots, export to PDF and convert workbooks using the installed Microsoft Excel.")
public final class ExcelDesktopPackage {

    private static final String PRELUDE = "$ErrorActionPreference = 'Stop'\n"
            + "$xl = New-Object -ComObject Excel.Application\n$xl.DisplayAlerts = $false\n$xl.Visible = $false\n$xl.AskToUpdateLinks = $false\n"
            + "try {\n";
    private static final String CLOSE = "} finally {\n  if ($wb) { $wb.Close($false) | Out-Null }\n  $xl.Quit()\n  [void][Runtime.InteropServices.Marshal]::ReleaseComObject($xl)\n}\n";

    private static ProcessRunner.Result excel(ActionContext ctx, String body, List<String> args, int timeout) {
        if (!ProcessRunner.WINDOWS) throw new ActionException("SYSTEM.WINDOWS_ONLY", "Desktop Excel automation needs Windows with Excel installed",
                "Use Excel (no Office needed) for reading and writing cells on any device.");
        Path script = null;
        try {
            script = Files.createTempFile("ooa-excel-", ".ps1");
            Files.writeString(script, PRELUDE + body + CLOSE, StandardCharsets.UTF_8);
            List<String> cmd = new ArrayList<>(List.of("powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", script.toString()));
            cmd.addAll(args);
            ProcessRunner.Result r = ProcessRunner.run(ctx, cmd, ctx.workDir(), null, timeout);
            if (r.exitCode() != 0) {
                String err = r.stderr().isBlank() ? r.stdout() : r.stderr();
                if (err.contains("80040154") || err.contains("Retrieving the COM class factory"))
                    throw new ActionException("EXCEL_DESKTOP.NOT_INSTALLED", "Microsoft Excel is not installed on this device", "Install Excel, or use Excel (no Office needed).");
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

    @Action(id = "run_macro", name = "Run macro", category = "Macros", timeoutSeconds = 3600, capabilities = "desktop",
            description = "Open a macro-enabled workbook, run a VBA macro with up to 10 arguments and optionally save.")
    @Output(name = "result", type = OoaType.STRING, description = "Value returned by the macro (for Functions), as text.")
    public Map<String, Object> runMacro(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "Workbook (.xlsm/.xlsb/.xls).") String path,
            @Input(name = "macro", type = OoaType.STRING, example = "Module1.BuildReport", description = "Macro name.") String macro,
            @Input(name = "arguments", type = OoaType.LIST, required = false, description = "Arguments passed to the macro (text).") List<Object> args,
            @Input(name = "save", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Save the workbook afterwards.") boolean save,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "600", description = "Maximum run time.") int timeout) {
        if (!macro.matches("^[A-Za-z0-9_.!' \\[\\]-]{1,200}$")) throw new ActionException("EXCEL_DESKTOP.BAD_MACRO", "Invalid macro name '" + macro + "'");
        List<String> a = new ArrayList<>(List.of(file(ctx, path), macro, save ? "1" : "0"));
        if (args != null) { if (args.size() > 10) throw new ActionException("EXCEL_DESKTOP.TOO_MANY_ARGS", "At most 10 macro arguments"); for (Object o : args) a.add(Values.text(o)); }
        String body = "  $wb = $xl.Workbooks.Open($args[0])\n  $rest = @($args | Select-Object -Skip 3)\n"
                + "  $r = $xl.GetType().InvokeMember('Run', [Reflection.BindingFlags]::InvokeMethod, $null, $xl, (@($args[1]) + $rest))\n"
                + "  if ($args[2] -eq '1') { $wb.Save() }\n  if ($r -ne $null) { Write-Output $r }\n";
        return Map.of("result", excel(ctx, body, a, timeout).stdout().strip());
    }

    @Action(id = "refresh_all", name = "Refresh all data", category = "Data", timeoutSeconds = 3600, capabilities = "desktop",
            description = "Refresh every query, connection and pivot table in the workbook, wait for completion and save.")
    public Map<String, Object> refreshAll(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "Workbook.") String path,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "900", description = "Maximum time.") int timeout) {
        excel(ctx, "  $wb = $xl.Workbooks.Open($args[0])\n  $wb.RefreshAll()\n  $xl.CalculateUntilAsyncQueriesDone()\n  $wb.Save()\n", List.of(file(ctx, path)), timeout);
        return Map.of();
    }

    @Action(id = "export_pdf", name = "Export to PDF", category = "Convert", timeoutSeconds = 600, capabilities = "desktop", description = "Export the whole workbook, or one sheet, to PDF.")
    public Map<String, Object> exportPdf(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "Workbook.") String path,
            @Input(name = "target", type = OoaType.FILE, description = "PDF file to create.") String target,
            @Input(name = "sheet", type = OoaType.STRING, required = false, description = "Only this sheet; the whole workbook when empty.") String sheet) {
        Path t = ctx.resolvePath(target).toAbsolutePath();
        try { if (t.getParent() != null) Files.createDirectories(t.getParent()); } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage()); }
        String body = "  $wb = $xl.Workbooks.Open($args[0], 0, $true)\n"
                + "  if ($args.Count -gt 2 -and $args[2]) { $wb.Worksheets.Item($args[2]).ExportAsFixedFormat(0, $args[1]) } else { $wb.ExportAsFixedFormat(0, $args[1]) }\n";
        List<String> a = new ArrayList<>(List.of(file(ctx, path), t.toString()));
        if (sheet != null && !sheet.isBlank()) a.add(sheet);
        excel(ctx, body, a, 600);
        return Map.of();
    }

    @Action(id = "convert", name = "Convert workbook", category = "Convert", timeoutSeconds = 600, capabilities = "desktop",
            description = "Save a workbook in another format, e.g. .xls to .xlsx or .csv to .xlsx.")
    public Map<String, Object> convert(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "Source file.") String path,
            @Input(name = "target", type = OoaType.FILE, description = "Target file; the format follows its extension (.xlsx, .xlsm, .xls, .csv).") String target) {
        Path t = ctx.resolvePath(target).toAbsolutePath();
        String ext = t.getFileName().toString().replaceAll("^.*\\.", "").toLowerCase(Locale.ROOT);
        String format = switch (ext) { case "xlsx" -> "51"; case "xlsm" -> "52"; case "xls" -> "56"; case "csv" -> "6"; default -> null; };
        if (format == null) throw new ActionException("EXCEL_DESKTOP.BAD_FORMAT", "Unsupported target format ." + ext, "Use .xlsx, .xlsm, .xls or .csv.");
        excel(ctx, "  $wb = $xl.Workbooks.Open($args[0])\n  $wb.SaveAs($args[1], [int]$args[2])\n", List.of(file(ctx, path), t.toString(), format), 600);
        return Map.of();
    }
}
