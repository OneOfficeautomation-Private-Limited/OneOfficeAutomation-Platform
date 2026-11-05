package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The OCR engine built into Windows 10 and 11 (Windows.Media.Ocr), used when Tesseract is not installed, so OCR commands
 * work on a fresh runner without any installation (they used to stop with OCR.NOT_INSTALLED). Its answer is written in
 * Tesseract's TSV layout (or as plain text), so every OCR command reads it the same way. Languages are those installed in
 * Windows (Settings > Time & language > Language, or Add-WindowsCapability Language.OCR~~~ar-SA~0.0.1.0).
 * <p>The Windows engine misreads small text that Tesseract reads (a 900 x 240 px "INVOICE 12345" came back as
 * "INWICE12345"), so an image that the command has not already enlarged and whose longer side is at most
 * {@value #ENLARGE_UP_TO} px is read at twice its size (bicubic, as the scale input does) and the word boxes are mapped
 * back to the original pixels.
 */
final class WindowsOcr {
    private WindowsOcr() {}

    private static final AtomicBoolean NOTED = new AtomicBoolean();

    /** Tesseract language codes and the Windows language for each. */
    static final Map<String, String> LANGUAGES = Map.ofEntries(
            Map.entry("eng", "en"), Map.entry("deu", "de"), Map.entry("fra", "fr"), Map.entry("spa", "es"), Map.entry("ita", "it"),
            Map.entry("por", "pt"), Map.entry("nld", "nl"), Map.entry("ara", "ar"), Map.entry("hin", "hi"), Map.entry("jpn", "ja"),
            Map.entry("chi_sim", "zh-Hans"), Map.entry("chi_tra", "zh-Hant"), Map.entry("kor", "ko"), Map.entry("rus", "ru"),
            Map.entry("pol", "pl"), Map.entry("tur", "tr"), Map.entry("ces", "cs"), Map.entry("swe", "sv"), Map.entry("dan", "da"),
            Map.entry("nor", "nb"), Map.entry("fin", "fi"), Map.entry("ell", "el"), Map.entry("heb", "he"), Map.entry("tha", "th"),
            Map.entry("vie", "vi"), Map.entry("ukr", "uk"), Map.entry("hun", "hu"), Map.entry("ron", "ro"), Map.entry("srp", "sr"),
            Map.entry("hrv", "hr"), Map.entry("slk", "sk"), Map.entry("slv", "sl"), Map.entry("bul", "bg"), Map.entry("cat", "ca"));

    static final String SCRIPT = """
            $ErrorActionPreference = 'Stop'
            $path = $args[0]; $want = $args[1]; $mode = $args[2]
            try {
              Add-Type -AssemblyName System.Runtime.WindowsRuntime
              $null = [Windows.Storage.StorageFile, Windows.Storage, ContentType = WindowsRuntime]
              $null = [Windows.Media.Ocr.OcrEngine, Windows.Foundation, ContentType = WindowsRuntime]
              $null = [Windows.Graphics.Imaging.BitmapDecoder, Windows.Graphics, ContentType = WindowsRuntime]
              $null = [Windows.Globalization.Language, Windows.Globalization, ContentType = WindowsRuntime]
            } catch { [Console]::Error.WriteLine('OOA_NO_WINRT ' + $_.Exception.Message); exit 4 }
            $asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]
            function Await($op, [Type]$type) { $t = $asTask.MakeGenericMethod($type).Invoke($null, @($op)); $t.Wait(-1) | Out-Null; $t.Result }
            $engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromLanguage((New-Object Windows.Globalization.Language $want))
            if ($engine -eq $null) {
              [Console]::Error.WriteLine('OOA_NO_LANGUAGE ' + (([Windows.Media.Ocr.OcrEngine]::AvailableRecognizerLanguages | ForEach-Object { $_.LanguageTag }) -join ', '))
              exit 3
            }
            $file = Await ([Windows.Storage.StorageFile]::GetFileFromPathAsync($path)) ([Windows.Storage.StorageFile])
            $stream = Await ($file.OpenAsync([Windows.Storage.FileAccessMode]::Read)) ([Windows.Storage.Streams.IRandomAccessStream])
            try {
              $decoder = Await ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($stream)) ([Windows.Graphics.Imaging.BitmapDecoder])
              $bitmap = Await ($decoder.GetSoftwareBitmapAsync()) ([Windows.Graphics.Imaging.SoftwareBitmap])
              $result = Await ($engine.RecognizeAsync($bitmap)) ([Windows.Media.Ocr.OcrResult])
            } finally { $stream.Dispose() }
            if ($mode -eq 'tsv') {
              Write-Output "level`tpage_num`tblock_num`tpar_num`tline_num`tword_num`tleft`ttop`twidth`theight`tconf`ttext"
              $ln = 0
              foreach ($line in $result.Lines) {
                $ln++; $wn = 0
                foreach ($w in $line.Words) { $wn++; $r = $w.BoundingRect; Write-Output ("5`t1`t1`t1`t$ln`t$wn`t" + [int]$r.X + "`t" + [int]$r.Y + "`t" + [int]$r.Width + "`t" + [int]$r.Height + "`t90`t" + $w.Text) }
              }
            } else { foreach ($line in $result.Lines) { Write-Output $line.Text } }
            """;

    /** Longest side, in pixels, of an image that is read at twice its size. */
    static final int ENLARGE_UP_TO = 2000;

    /** Runs the Windows engine on an image file; the result looks like Tesseract's (text, or TSV with tsv). */
    static ProcessRunner.Result run(ActionContext ctx, Path image, String lang, boolean tsv) { return run(ctx, image, lang, tsv, false); }

    /**
     * As {@link #run(ActionContext, Path, String, boolean)}; {@code enlarged} says the caller already scaled the image up
     * (scale above 1, or a screen captured at twice its size), otherwise a small image is read at twice its size.
     */
    static ProcessRunner.Result run(ActionContext ctx, Path image, String lang, boolean tsv, boolean enlarged) {
        Path big = enlarged ? null : enlarge(image);
        if (big != null) {
            try {
                ProcessRunner.Result r = recognise(ctx, big, lang, tsv);
                return tsv ? new ProcessRunner.Result(r.exitCode(), halve(r.stdout()), r.stderr(), r.truncated()) : r;
            } finally { try { Files.deleteIfExists(big); } catch (IOException ignored) { } }
        }
        return recognise(ctx, image, lang, tsv);
    }

    /** A copy of a small image at twice its size, or null (large, unreadable, or it cannot be written). */
    static Path enlarge(Path image) {
        try {
            BufferedImage img = ImageIO.read(image.toFile());
            if (img == null || Math.max(img.getWidth(), img.getHeight()) > ENLARGE_UP_TO) return null;
            Path big = Files.createTempFile("ooa-ocr-", ".png");
            try {
                ImageIO.write(OcrPackage.prepare(img, null, null, null, null, 2, "none"), "png", big.toFile());
                return big;
            } catch (IOException | RuntimeException e) { Files.deleteIfExists(big); return null; }
        } catch (IOException | RuntimeException e) { return null; }
    }

    /** Word boxes of a Tesseract-style TSV read from an image at twice the size, in the original image's pixels. */
    static String halve(String tsv) {
        StringBuilder out = new StringBuilder();
        for (String line : tsv.split("\\r?\\n", -1)) {
            String[] c = line.split("\t", -1);
            if (c.length >= 12 && c[0].equals("5")) {
                try { for (int i = 6; i <= 9; i++) c[i] = String.valueOf(Math.round(Integer.parseInt(c[i].strip()) / 2.0)); }
                catch (NumberFormatException ignored) { }
                line = String.join("\t", c);
            }
            out.append(line).append('\n');
        }
        return out.length() > 0 ? out.substring(0, out.length() - 1) : "";
    }

    private static ProcessRunner.Result recognise(ActionContext ctx, Path image, String lang, boolean tsv) {
        if (!ProcessRunner.WINDOWS) throw new ActionException("OCR.NOT_INSTALLED", "The Windows OCR engine is only on Windows", "Install Tesseract, or unset OOA_OCR_ENGINE.");
        String first = lang.split("\\+")[0];
        String tag = LANGUAGES.getOrDefault(first, first.length() == 2 ? first : null);
        if (tag == null) throw new ActionException("OCR.LANGUAGE_MISSING", "The Windows OCR engine has no language for '" + first + "'",
                "Use a language Windows knows (" + String.join(", ", new TreeSet<>(LANGUAGES.keySet())) + ") or install Tesseract.");
        Path script = null;
        try {
            script = Files.createTempFile("ooa-ocr-", ".ps1");
            Files.writeString(script, "﻿" + ScriptPackage.UTF8_PREAMBLE + "\n" + SCRIPT, StandardCharsets.UTF_8);
            ProcessRunner.Result r = ProcessRunner.run(ctx, List.of("powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File",
                    script.toString(), image.toAbsolutePath().toString(), tag, tsv ? "tsv" : "text"), null, null, 300, null, StandardCharsets.UTF_8);
            if (r.exitCode() == 3) {
                String have = r.stderr().replaceAll("(?s).*OOA_NO_LANGUAGE", "").strip();
                throw new ActionException("OCR.LANGUAGE_MISSING", "Windows has no OCR language '" + tag + "' installed" + (have.isEmpty() ? "" : " (installed: " + have + ")"),
                        "Add the language in Windows Settings > Time & language > Language (with its OCR feature), or install Tesseract with that language.");
            }
            if (r.exitCode() == 4) throw new ActionException("OCR.NOT_INSTALLED", "No OCR engine: Tesseract is not installed and Windows OCR is not available on this Windows version",
                    "Install Tesseract (the UB Mannheim installer) or set OOA_TESSERACT to tesseract.exe.");
            if (r.exitCode() != 0) throw new ActionException("OCR.FAILED", "Windows OCR failed: " + ApplicationPackage.tail(r.stderr().isBlank() ? r.stdout() : r.stderr()), "Check the image is readable.");
            return r;
        } catch (IOException e) {
            throw new ActionException("FILE.IO_ERROR", "Cannot write the OCR script: " + e.getMessage(), null, true);
        } finally { if (script != null) try { Files.deleteIfExists(script); } catch (IOException ignored) { } }
    }

    static void noteOnce(ActionContext ctx) {
        if (NOTED.compareAndSet(false, true))
            ctx.info("Tesseract is not installed; using the OCR engine built into Windows (install Tesseract, or set OOA_OCR_ENGINE, to choose)");
    }
}
