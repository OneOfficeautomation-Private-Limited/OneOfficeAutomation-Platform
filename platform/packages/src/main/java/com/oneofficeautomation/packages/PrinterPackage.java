package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;

import javax.print.*;
import javax.print.attribute.HashPrintRequestAttributeSet;
import javax.print.attribute.standard.Copies;
import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;

@OoaPackage(id = "ooa.printer", name = "Printer", version = "1.0.0", description = "List printers and print files.")
public final class PrinterPackage {

    @Action(id = "list_printers", name = "Get printers", category = "Printer", idempotent = true, description = "List the printers installed on the runner.")
    @Output(name = "printers", type = OoaType.LIST, description = "Printer names.")
    @Output(name = "default_printer", type = OoaType.STRING, description = "Default printer name, or empty.")
    public Map<String, Object> list(ActionContext ctx) {
        List<Object> names = new ArrayList<>();
        for (PrintService s : PrintServiceLookup.lookupPrintServices(null, null)) names.add(s.getName());
        PrintService d = PrintServiceLookup.lookupDefaultPrintService();
        return Out.of("printers", names, "default_printer", d == null ? "" : d.getName());
    }

    @Action(id = "print_file", name = "Print file", category = "Printer", capabilities = "filesystem",
            description = "Print a file on a named printer, or on the default printer using the file's default application.")
    public Map<String, Object> print(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "File to print.") String path,
            @Input(name = "printer", type = OoaType.STRING, required = false, description = "Printer name; default printer when empty.") String printer,
            @Input(name = "copies", type = OoaType.NUMBER, required = false, defaultValue = "1", min = 1, max = 999, description = "Number of copies.") int copies) {
        Path p = ctx.resolvePath(path);
        if (!Files.isRegularFile(p)) throw new ActionException("FILE.NOT_FOUND", "File not found: " + p, "Check the path.");
        if (printer == null || printer.isBlank()) {
            if (GraphicsEnvironment.isHeadless() || !Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.PRINT))
                throw new ActionException("PRINTER.NO_DEFAULT_APP", "Cannot print with the default application on this runner", "Give a printer name, or run on a desktop session.");
            try { for (int i = 0; i < copies; i++) Desktop.getDesktop().print(p.toFile()); }
            catch (IOException e) { throw new ActionException("PRINTER.FAILED", e.getMessage(), "Check a default application is set for this file type."); }
            return Map.of();
        }
        PrintService svc = Arrays.stream(PrintServiceLookup.lookupPrintServices(null, null)).filter(s -> s.getName().equalsIgnoreCase(printer)).findFirst()
                .orElseThrow(() -> new ActionException("PRINTER.NOT_FOUND", "Printer '" + printer + "' not found", "Use Get printers to see the exact names."));
        try (InputStream in = Files.newInputStream(p)) {
            HashPrintRequestAttributeSet attrs = new HashPrintRequestAttributeSet();
            attrs.add(new Copies(Math.max(1, copies)));
            svc.createPrintJob().print(new SimpleDoc(in, DocFlavor.INPUT_STREAM.AUTOSENSE, null), attrs);
        } catch (PrintException | IOException e) {
            throw new ActionException("PRINTER.FAILED", "Printing failed: " + e.getMessage(), "Some printers accept only certain formats; try printing via the default application.", true);
        }
        return Map.of();
    }

    @Action(id = "get_default", name = "Get default printer", category = "Printer", idempotent = true, description = "Return the name of the runner's default printer.")
    @Output(name = "printer", type = OoaType.STRING, description = "Default printer name, or empty when none is set.")
    public Map<String, Object> getDefault(ActionContext ctx) {
        PrintService d = PrintServiceLookup.lookupDefaultPrintService();
        return Map.of("printer", d == null ? "" : d.getName());
    }

    private static void windows(String what) {
        if (!ProcessRunner.WINDOWS) throw new ActionException("PRINTER.WINDOWS_ONLY", what + " is supported on Windows only", "Run this agent on a Windows runner.");
    }

    private static void checkName(String printer) {
        if (printer == null || printer.isBlank() || printer.length() > 256 || printer.chars().anyMatch(c -> c < 0x20))
            throw new ActionException("PRINTER.BAD_NAME", "Invalid printer name '" + printer + "'", "Use Get printers to see the exact names.");
    }

    /** Exact-name match (no wildcards); exit code 3 means the printer does not exist. The name arrives in OOA_PRINTER. */
    static final String SET_DEFAULT_SCRIPT = "$ErrorActionPreference='Stop'; $p = Get-CimInstance -ClassName Win32_Printer | Where-Object { $_.Name -eq $env:OOA_PRINTER } | Select-Object -First 1; "
            + "if (-not $p) { exit 3 }; $r = Invoke-CimMethod -InputObject $p -MethodName SetDefaultPrinter; if ($r.ReturnValue -ne 0) { Write-Error ('SetDefaultPrinter returned ' + $r.ReturnValue); exit 4 }";
    static final String REMOVE_SCRIPT = "$ErrorActionPreference='Stop'; $p = Get-Printer | Where-Object { $_.Name -eq $env:OOA_PRINTER } | Select-Object -First 1; "
            + "if (-not $p) { exit 3 }; Remove-Printer -InputObject $p";

    private static void runPs(ActionContext ctx, String script, String printer, String what) {
        ProcessRunner.Result r = ProcessRunner.run(ctx, ScriptPackage.powershellCommand(script), null, null, 120, Map.of("OOA_PRINTER", printer), java.nio.charset.StandardCharsets.UTF_8);
        if (r.exitCode() == 3) throw new ActionException("PRINTER.NOT_FOUND", "Printer '" + printer + "' not found", "Use Get printers to see the exact names.");
        if (r.exitCode() != 0) {
            String err = ApplicationPackage.tail(r.stderr() + r.stdout());
            boolean denied = err.toLowerCase(Locale.ROOT).contains("access") && err.toLowerCase(Locale.ROOT).contains("denied");
            throw new ActionException(denied ? "PRINTER.ACCESS_DENIED" : "PRINTER.FAILED", what + " failed: " + err,
                    denied ? "Run the agent with administrator rights." : "Check the printer name and the Print Spooler service.");
        }
    }

    @Action(id = "set_default", name = "Set default printer", category = "Printer", timeoutSeconds = 120,
            description = "Make a printer the default for the runner's signed-in user (Windows).")
    public Map<String, Object> setDefault(ActionContext ctx,
            @Input(name = "printer", type = OoaType.STRING, description = "Exact printer name from Get printers.") String printer) {
        windows("Setting the default printer");
        checkName(printer);
        runPs(ctx, SET_DEFAULT_SCRIPT, printer, "Setting the default printer");
        return Map.of();
    }

    @Action(id = "remove_printer", name = "Remove printer", category = "Printer", timeoutSeconds = 120,
            description = "Uninstall a printer from the runner (Windows); needs confirm because it cannot be undone.")
    public Map<String, Object> removePrinter(ActionContext ctx,
            @Input(name = "printer", type = OoaType.STRING, description = "Exact printer name from Get printers.") String printer,
            @Input(name = "confirm", type = OoaType.BOOLEAN, description = "Must be true to remove the printer.") boolean confirm) {
        if (!confirm) throw new ActionException("PRINTER.CONFIRM_REQUIRED", "The printer was not removed because confirm is false",
                "Set confirm to true to allow this agent to remove the printer.");
        windows("Removing a printer");
        checkName(printer);
        ctx.warn("Removing printer '" + printer + "' as requested by the agent");
        runPs(ctx, REMOVE_SCRIPT, printer, "Removing the printer");
        return Map.of();
    }
}
