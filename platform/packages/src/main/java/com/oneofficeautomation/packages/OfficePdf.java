package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Turns a Word document or PowerPoint presentation into PDF with what the runner has, best first:
 * <ol>
 *   <li>Microsoft Word / PowerPoint (2010 and later export directly; 2007 needs Microsoft's add-in),</li>
 *   <li>the same application printing to the Microsoft Print to PDF printer (Office 2007 without the add-in),</li>
 *   <li>LibreOffice in headless mode (Windows or Linux runners without Office).</li>
 * </ol>
 * The source must be a file the application can open without a password (callers write an unencrypted temporary copy).
 * Paths travel as script arguments, never inside script code.
 */
public final class OfficePdf {
    private OfficePdf() {}

    /** Which way the PDF was made, for the step's log. */
    public enum Method { OFFICE_EXPORT, OFFICE_PRINT, LIBREOFFICE }

    static final String WORD_SCRIPT = """
            $ErrorActionPreference = 'Stop'
            $src = $args[0]; $dst = $args[1]
            $w = New-Object -ComObject Word.Application
            $w.Visible = $false; $w.DisplayAlerts = 0
            try {
              $d = $w.Documents.Open($src, $false, $true)
              try { $d.ExportAsFixedFormat($dst, 17); Write-Output 'OOA_PDF export' }
              catch {
                $why = $_.Exception.Message
                if (-not (Get-CimInstance Win32_Printer -Filter "Name='Microsoft Print to PDF'")) { throw "OOA_NO_PDF $why" }
                $before = $w.ActivePrinter
                try { $w.Options.PrintBackground = $false; $w.ActivePrinter = 'Microsoft Print to PDF'; $d.PrintOut($false, $false, 0, $dst, '', '', 0, 1, '', 0, $true) }
                finally { $w.ActivePrinter = $before }
                Write-Output "OOA_PDF print $why"
              }
            } finally { if ($d) { $d.Close(0) }; $w.Quit(); [void][Runtime.InteropServices.Marshal]::ReleaseComObject($w) }
            """;

    static final String POWERPOINT_SCRIPT = """
            $ErrorActionPreference = 'Stop'
            $src = $args[0]; $dst = $args[1]
            $p = New-Object -ComObject PowerPoint.Application
            try {
              $pres = $p.Presentations.Open($src, -1, 0, 0)
              try { $pres.SaveAs($dst, 32); Write-Output 'OOA_PDF export' }
              catch {
                $why = $_.Exception.Message
                if (-not (Get-CimInstance Win32_Printer -Filter "Name='Microsoft Print to PDF'")) { throw "OOA_NO_PDF $why" }
                $pres.PrintOptions.ActivePrinter = 'Microsoft Print to PDF'
                $pres.PrintOptions.PrintInBackground = 0
                $pres.PrintOut(1, $pres.Slides.Count, $dst)
                Write-Output "OOA_PDF print $why"
              }
            } finally { if ($pres) { $pres.Close() }; $p.Quit(); [void][Runtime.InteropServices.Marshal]::ReleaseComObject($p) }
            """;

    /**
     * Writes {@code pdf} from {@code source}; {@code app} is "word" or "powerpoint". Throws {prefix}.PDF_UNAVAILABLE with
     * the reasons when no way works.
     */
    public static Method convert(ActionContext ctx, Path source, Path pdf, String app, String prefix, double timeoutSeconds) {
        List<String> tried = new ArrayList<>();
        try { Files.deleteIfExists(pdf); } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", "Cannot replace " + pdf + ": " + e.getMessage()); }
        if (ProcessRunner.WINDOWS) {
            String name = app.equals("word") ? "Microsoft Word" : "Microsoft PowerPoint";
            Path script = null;
            try {
                script = Files.createTempFile("ooa-pdf-", ".ps1");
                Files.writeString(script, "﻿" + ScriptPackage.UTF8_PREAMBLE + "\n" + (app.equals("word") ? WORD_SCRIPT : POWERPOINT_SCRIPT), StandardCharsets.UTF_8);
                ProcessRunner.Result r = ProcessRunner.run(ctx, List.of("powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File",
                        script.toString(), source.toAbsolutePath().toString(), pdf.toAbsolutePath().toString()), null, null, timeoutSeconds, null, StandardCharsets.UTF_8);
                String out = r.stdout(), err = r.stderr();
                if (r.exitCode() == 0 && out.contains("OOA_PDF")) {
                    waitForFile(pdf, 120);
                    if (out.contains("OOA_PDF print")) {
                        ctx.warn(name + " has no PDF export on this runner (" + out.substring(out.indexOf("OOA_PDF print") + 13).strip()
                                + "); the PDF was printed with Microsoft Print to PDF instead");
                        return Method.OFFICE_PRINT;
                    }
                    return Method.OFFICE_EXPORT;
                }
                String why = (err.isBlank() ? out : err).strip();
                if (why.contains("80040154") || why.contains("COM class factory")) tried.add(name + " is not installed");
                else if (why.contains("OOA_NO_PDF")) tried.add(name + " cannot export to PDF and the Microsoft Print to PDF printer is missing");
                else tried.add(name + " failed: " + ApplicationPackage.tail(why));
            } catch (ActionException e) {
                tried.add(name + ": " + e.getMessage());
            } catch (IOException e) {
                tried.add("cannot write the conversion script: " + e.getMessage());
            } finally { if (script != null) try { Files.deleteIfExists(script); } catch (IOException ignored) { } }
        }
        String soffice = libreOffice();
        if (soffice != null) {
            Path out = null;
            try {
                out = Files.createTempDirectory("ooa-pdf-");
                ProcessRunner.Result r = ProcessRunner.run(ctx, List.of(soffice, "--headless", "--norestore", "--nolockcheck", "--convert-to", "pdf", "--outdir",
                        out.toString(), source.toAbsolutePath().toString()), out, null, timeoutSeconds, null, StandardCharsets.UTF_8);
                String base = source.getFileName().toString();
                Path made = out.resolve(base.contains(".") ? base.substring(0, base.lastIndexOf('.')) + ".pdf" : base + ".pdf");
                if (r.exitCode() == 0 && Files.isRegularFile(made)) {
                    if (pdf.getParent() != null) Files.createDirectories(pdf.getParent());
                    Files.move(made, pdf, StandardCopyOption.REPLACE_EXISTING);
                    return Method.LIBREOFFICE;
                }
                tried.add("LibreOffice failed: " + ApplicationPackage.tail(r.stderr().isBlank() ? r.stdout() : r.stderr()));
            } catch (ActionException | IOException e) {
                tried.add("LibreOffice: " + e.getMessage());
            } finally { if (out != null) deleteTree(out); }
        } else tried.add("LibreOffice is not installed");
        throw new ActionException(prefix + ".PDF_UNAVAILABLE", "No PDF converter worked on this runner: " + String.join("; ", tried),
                "Install Microsoft Office (2010 or later, or 2007 with Microsoft's Save as PDF add-in) or LibreOffice on the runner.");
    }

    private static void deleteTree(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) { } });
        } catch (IOException ignored) { }
    }

    /** soffice from the usual install folders or the PATH, or null. */
    static String libreOffice() {
        List<Path> candidates = new ArrayList<>();
        for (String env : List.of("ProgramFiles", "ProgramFiles(x86)", "ProgramW6432")) {
            String base = System.getenv(env);
            if (base != null) candidates.add(Path.of(base, "LibreOffice", "program", "soffice.exe"));
        }
        candidates.add(Path.of("/usr/bin/soffice"));
        candidates.add(Path.of("/usr/bin/libreoffice"));
        candidates.add(Path.of("/opt/libreoffice/program/soffice"));
        candidates.add(Path.of("/Applications/LibreOffice.app/Contents/MacOS/soffice"));
        for (Path p : candidates) if (Files.isRegularFile(p)) return p.toString();
        String path = System.getenv("PATH");
        if (path != null) for (String dir : path.split(java.io.File.pathSeparator)) {
            for (String n : List.of("soffice", "soffice.exe", "libreoffice")) {
                Path p = Path.of(dir, n);
                if (Files.isRegularFile(p) && Files.isExecutable(p)) return p.toString();
            }
        }
        return null;
    }

    /**
     * Waits until a printed or exported file is complete: printers spool in the background, so the file can appear empty
     * and grow for a moment after the application returns.
     */
    public static void waitForFile(Path file, double maxSeconds) {
        long end = System.nanoTime() + (long) (maxSeconds * 1e9);
        long last = -1;
        int stable = 0;
        while (System.nanoTime() < end) {
            try {
                long size = Files.isRegularFile(file) ? Files.size(file) : -1;
                if (size > 0 && size == last) { if (++stable >= 2) return; }
                else stable = 0;
                last = size;
                Thread.sleep(500);
            } catch (IOException e) { stable = 0; }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new ActionException("RUN.CANCELLED", "Interrupted while waiting for " + file); }
        }
        throw new ActionException("FILE.NOT_CREATED", "The PDF " + file + " was not written within " + (int) maxSeconds + " s",
                "Check the printer queue and that the target folder is writable.");
    }
}
