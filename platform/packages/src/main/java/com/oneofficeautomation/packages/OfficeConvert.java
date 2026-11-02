package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Converts Word and PowerPoint files between formats the Office toolkits cannot write themselves: .doc, .rtf and .odt
 * documents, and .ppt and .odp presentations, to and from .docx/.pptx. It uses Microsoft Word / PowerPoint on the runner
 * (Office 2007 and later) and otherwise LibreOffice in headless mode. Paths travel as script arguments.
 */
public final class OfficeConvert {
    private OfficeConvert() {}

    /** Word's SaveAs format numbers (WdSaveFormat) and LibreOffice filters, by extension. */
    static final Map<String, Integer> WORD_FORMATS = Map.of("doc", 0, "rtf", 6, "docx", 12, "odt", 23, "txt", 7, "dot", 1, "docm", 13);
    static final Map<String, Integer> POWERPOINT_FORMATS = Map.of("ppt", 1, "pptx", 24, "odp", 35, "pps", 7, "ppsx", 28, "pptm", 25);
    static final Map<String, String> LIBRE_FILTERS = Map.ofEntries(
            Map.entry("doc", "doc:MS Word 97"), Map.entry("rtf", "rtf:Rich Text Format"), Map.entry("docx", "docx:MS Word 2007 XML"),
            Map.entry("odt", "odt:writer8"), Map.entry("txt", "txt:Text (encoded):UTF8"), Map.entry("ppt", "ppt:MS PowerPoint 97"),
            Map.entry("pptx", "pptx:Impress MS PowerPoint 2007 XML"), Map.entry("odp", "odp:impress8"));

    static final String WORD_SCRIPT = """
            $ErrorActionPreference = 'Stop'
            $src = $args[0]; $dst = $args[1]; $fmt = [int]$args[2]
            $w = New-Object -ComObject Word.Application
            $w.Visible = $false; $w.DisplayAlerts = 0
            try {
              $d = $w.Documents.Open($src, $false, $true, $false)
              $d.SaveAs([ref]$dst, [ref]$fmt)
              Write-Output 'OOA_CONVERTED'
            } finally { if ($d) { $d.Close(0) }; $w.Quit(); [void][Runtime.InteropServices.Marshal]::ReleaseComObject($w) }
            """;

    static final String POWERPOINT_SCRIPT = """
            $ErrorActionPreference = 'Stop'
            $src = $args[0]; $dst = $args[1]; $fmt = [int]$args[2]
            $p = New-Object -ComObject PowerPoint.Application
            try {
              $pres = $p.Presentations.Open($src, -1, 0, 0)
              $pres.SaveAs($dst, $fmt)
              Write-Output 'OOA_CONVERTED'
            } finally { if ($pres) { $pres.Close() }; $p.Quit(); [void][Runtime.InteropServices.Marshal]::ReleaseComObject($p) }
            """;

    /** Lower-case extension without the dot, or "". */
    public static String extension(Path p) {
        String n = p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot < 0 ? "" : n.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** Whether the toolkit for {@code app} ("word" or "powerpoint") needs this converter for the file's extension. */
    public static boolean needsConversion(String app, String ext) {
        return app.equals("word") ? Set.of("doc", "rtf", "odt", "dot").contains(ext) : Set.of("ppt", "odp", "pps").contains(ext);
    }

    /**
     * Writes {@code target} (format from its extension) from {@code source}. {@code app} is "word" or "powerpoint".
     * Throws {prefix}.CONVERTER_UNAVAILABLE when neither Office nor LibreOffice could do it.
     */
    public static void convert(ActionContext ctx, Path source, Path target, String app, String prefix, double timeoutSeconds) {
        String ext = extension(target);
        Map<String, Integer> formats = app.equals("word") ? WORD_FORMATS : POWERPOINT_FORMATS;
        if (!formats.containsKey(ext))
            throw new ActionException(prefix + ".BAD_FORMAT", "Cannot save as ." + ext, "Use one of: " + String.join(", ", new TreeSet<>(formats.keySet())) + ".");
        List<String> tried = new ArrayList<>();
        try {
            if (target.getParent() != null) Files.createDirectories(target.getParent());
            Files.deleteIfExists(target);
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", "Cannot replace " + target + ": " + e.getMessage()); }
        if (ProcessRunner.WINDOWS) {
            String name = app.equals("word") ? "Microsoft Word" : "Microsoft PowerPoint";
            Path script = null;
            try {
                script = Files.createTempFile("ooa-convert-", ".ps1");
                Files.writeString(script, "﻿" + ScriptPackage.UTF8_PREAMBLE + "\n" + (app.equals("word") ? WORD_SCRIPT : POWERPOINT_SCRIPT), StandardCharsets.UTF_8);
                ProcessRunner.Result r = ProcessRunner.run(ctx, List.of("powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File",
                        script.toString(), source.toAbsolutePath().toString(), target.toAbsolutePath().toString(), String.valueOf(formats.get(ext))),
                        null, null, timeoutSeconds, null, StandardCharsets.UTF_8);
                if (r.exitCode() == 0 && r.stdout().contains("OOA_CONVERTED")) {
                    OfficePdf.waitForFile(target, 60);
                    return;
                }
                String why = (r.stderr().isBlank() ? r.stdout() : r.stderr()).strip();
                if (why.contains("80040154") || why.contains("COM class factory")) tried.add(name + " is not installed");
                else if (why.contains("Invalid enumeration value") || why.contains("0x80070057"))
                    tried.add(name + " on this runner cannot save ." + ext + " files (OpenDocument needs Office 2007 Service Pack 2 or later; PowerPoint 2007 saves .ppt and .pptx)");
                else tried.add(name + " failed: " + ApplicationPackage.tail(why));
            } catch (ActionException e) {
                tried.add(name + ": " + e.getMessage());
            } catch (IOException e) {
                tried.add("cannot write the conversion script: " + e.getMessage());
            } finally { if (script != null) try { Files.deleteIfExists(script); } catch (IOException ignored) { } }
        }
        String soffice = OfficePdf.libreOffice();
        if (soffice != null) {
            Path out = null;
            try {
                out = Files.createTempDirectory("ooa-convert-");
                ProcessRunner.Result r = ProcessRunner.run(ctx, List.of(soffice, "--headless", "--norestore", "--nolockcheck", "--convert-to", LIBRE_FILTERS.getOrDefault(ext, ext),
                        "--outdir", out.toString(), source.toAbsolutePath().toString()), out, null, timeoutSeconds, null, StandardCharsets.UTF_8);
                String base = source.getFileName().toString();
                Path made = out.resolve((base.contains(".") ? base.substring(0, base.lastIndexOf('.')) : base) + "." + ext);
                if (r.exitCode() == 0 && Files.isRegularFile(made)) {
                    Files.move(made, target, StandardCopyOption.REPLACE_EXISTING);
                    return;
                }
                tried.add("LibreOffice failed: " + ApplicationPackage.tail(r.stderr().isBlank() ? r.stdout() : r.stderr()));
            } catch (ActionException | IOException e) {
                tried.add("LibreOffice: " + e.getMessage());
            } finally { if (out != null) deleteTree(out); }
        } else tried.add("LibreOffice is not installed");
        throw new ActionException(prefix + ".CONVERTER_UNAVAILABLE", "Cannot convert " + source.getFileName() + " to ." + ext + " on this runner: " + String.join("; ", tried),
                "Install Microsoft Office or LibreOffice on the runner, or use ." + (app.equals("word") ? "docx" : "pptx") + " files.");
    }

    static void deleteTree(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) { } });
        } catch (IOException ignored) { }
    }
}
