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

    @Action(id = "list_printers", name = "Get printers", category = "Printer", idempotent = true, description = "List the printers installed on the device.")
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
            @Input(name = "copies", type = OoaType.NUMBER, required = false, defaultValue = "1", description = "Number of copies.") int copies) {
        Path p = ctx.resolvePath(path);
        if (!Files.isRegularFile(p)) throw new ActionException("FILE.NOT_FOUND", "File not found: " + p, "Check the path.");
        if (printer == null || printer.isBlank()) {
            if (GraphicsEnvironment.isHeadless() || !Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.PRINT))
                throw new ActionException("PRINTER.NO_DEFAULT_APP", "Cannot print with the default application on this device", "Give a printer name, or run on a desktop session.");
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
}
