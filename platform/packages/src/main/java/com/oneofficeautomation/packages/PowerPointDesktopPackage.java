package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Drives the installed Microsoft PowerPoint (Windows) through COM for what needs PowerPoint itself: inserting videos
 * and files, pasting the clipboard, and running VBA macros (gap G13, 3 Nov 2026). File paths and macro arguments are
 * passed as script arguments, never pasted into script code. PowerPoint runs one instance per user, so when the user
 * already has it open the scripts work in that instance: they close only the presentation they opened, and quit
 * PowerPoint only when it showed no window before (a hidden instance left by an earlier step is quit too).
 */
@OoaPackage(id = "ooa.powerpoint_desktop", name = "PowerPoint (desktop, needs PowerPoint)", version = "1.0.0",
        description = "Insert videos and files, paste the clipboard and run macros using the installed Microsoft PowerPoint.")
public final class PowerPointDesktopPackage {

    /** Opens $args[0], without a window unless %WINDOW% is -1; $pres and $pp are closed or released in the finally of every script. */
    static final String PRELUDE = String.join("\n",
            "$ErrorActionPreference = 'Stop'",
            // PowerPoint is the user's only when it shows a window; a hidden one is a leftover of an earlier step (it takes
            // a few seconds to exit after Quit) and is closed again at the end when nothing is left open in it
            "$started = @(Get-Process POWERPNT -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowHandle -ne 0 }).Count -eq 0",
            "$pp = New-Object -ComObject PowerPoint.Application",
            "if ($pp.Presentations.Count -eq 0) { $started = $true }   # nothing open in it: quitting at the end loses nothing",
            "$pres = $null",
            "try {",
            "  $pres = $pp.Presentations.Open($args[0], 0, 0, %WINDOW%)",
            "");

    /** Saves to $args[1] when given, else in place when $args[2] is 1; then closes only what this script opened. */
    static final String SAVE_AND_CLOSE = String.join("\n",
            "  if ($args[1]) { $pres.SaveAs($args[1]) } elseif ($args[2] -eq '1') { $pres.Save() }",
            "} finally {",
            "  if ($pres) { try { $pres.Close() } catch { } }",
            "  if ($started -and $pp.Presentations.Count -eq 0) { try { $pp.Quit() } catch { } }",
            "  [void][Runtime.InteropServices.Marshal]::ReleaseComObject($pp)",
            "}",
            "");

    /** Places $shape at $args[4..7] (left, top, width, height in points; empty keeps PowerPoint's own) and reports it. */
    private static String placeShape() {
        return String.join("\n",
                "  if ($args[4]) { $shape.Left = [single]$args[4] }",
                "  if ($args[5]) { $shape.Top = [single]$args[5] }",
                "  if ($args[6]) { $shape.Width = [single]$args[6] }",
                "  if ($args[7]) { $shape.Height = [single]$args[7] }",
                "  \"OOA_SHAPE=$($shape.Name)|$($shape.Left)|$($shape.Top)|$($shape.Width)|$($shape.Height)\"",
                "");
    }

    private static ProcessRunner.Result powerpoint(ActionContext ctx, String body, List<String> args, int timeout) { return powerpoint(ctx, body, args, timeout, false); }

    /** Runs the script; with a window, the presentation opens in a PowerPoint window (minimised), which pasting needs. */
    private static ProcessRunner.Result powerpoint(ActionContext ctx, String body, List<String> args, int timeout, boolean window) {
        if (!ProcessRunner.WINDOWS) throw new ActionException("SYSTEM.WINDOWS_ONLY", "Desktop PowerPoint automation needs Windows with PowerPoint installed",
                "Use PowerPoint (no Office needed) for slides, text, pictures and tables on any runner.");
        Path script = null;
        try {
            script = Files.createTempFile("ooa-ppt-", ".ps1");
            Files.writeString(script, "\uFEFF" + ScriptPackage.UTF8_PREAMBLE + "\n" + PRELUDE.replace("%WINDOW%", window ? "-1" : "0") + body + SAVE_AND_CLOSE, StandardCharsets.UTF_8);
            List<String> cmd = new ArrayList<>(List.of("powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", script.toString()));
            cmd.addAll(args);
            ProcessRunner.Result r = ProcessRunner.run(ctx, cmd, ctx.workDir(), null, timeout, Map.of(), StandardCharsets.UTF_8);
            String all = r.stdout() + "\n" + r.stderr();
            if (all.contains("OOA_MACRO_ERROR=")) {
                String msg = all.substring(all.indexOf("OOA_MACRO_ERROR=") + 16).lines().findFirst().orElse("").strip();
                throw new ActionException("POWERPOINT_DESKTOP.MACRO_ERROR", "The macro stopped with an error: " + msg, "Fix the macro, or handle the error inside it.");
            }
            if (r.stdout().lines().anyMatch(l -> l.strip().equals("OOA_NO_MACRO")))
                throw new ActionException("POWERPOINT_DESKTOP.NO_MACRO", "The macro was not found in the presentation, or macros are disabled", "Check the macro name, e.g. Module1.BuildDeck, and that the file is a .pptm.");
            if (r.exitCode() != 0) {
                if (all.contains("80040154") || all.contains("Retrieving the COM class factory"))
                    throw new ActionException("POWERPOINT_DESKTOP.NOT_INSTALLED", "Microsoft PowerPoint is not installed on this runner", "Install PowerPoint, or use PowerPoint (no Office needed).");
                if (all.contains("OOA_BAD_SLIDE: "))
                    throw new ActionException("POWERPOINT_DESKTOP.BAD_SLIDE", all.substring(all.indexOf("OOA_BAD_SLIDE: ") + 15).lines().findFirst().orElse("").strip(), "Use a slide number from 1 to the number of slides.");
                if (all.contains("OOA_NO_MACRO"))
                    throw new ActionException("POWERPOINT_DESKTOP.NO_MACRO", "The macro was not found in the presentation, or macros are disabled", "Check the macro name, e.g. Module1.BuildDeck, and that the file is a .pptm.");
                throw new ActionException("POWERPOINT_DESKTOP.FAILED", "PowerPoint reported an error: " + ApplicationPackage.tail(r.stderr().isBlank() ? r.stdout() : r.stderr()),
                        "Check the presentation and the file paths.", true);
            }
            return r;
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), null, true);
        } finally { if (script != null) try { Files.deleteIfExists(script); } catch (IOException ignored) { } }
    }

    private static String existing(ActionContext ctx, String path, String what) {
        Path p = ctx.resolvePath(path).toAbsolutePath();
        if (!Files.isRegularFile(p)) throw new ActionException("FILE.NOT_FOUND", what + " not found: " + p, "Check the path.");
        return p.toString();
    }

    private static String target(ActionContext ctx, String saveAs, boolean overwrite) {
        if (saveAs == null || saveAs.isBlank()) return "";
        Path t = ctx.resolvePath(saveAs).toAbsolutePath();
        if (!overwrite && Files.exists(t)) throw new ActionException("POWERPOINT_DESKTOP.EXISTS", "A file already exists at " + t, "Set overwrite to true or choose another path.");
        try { if (t.getParent() != null) Files.createDirectories(t.getParent()); } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage()); }
        return t.toString();
    }

    private static String num(Double d) { return d == null ? "" : String.valueOf(d); }

    private static List<String> args(String file, String saveAs, boolean save, int slide, Double left, Double top, Double width, Double height, String... more) {
        if (slide < 1) throw new ActionException("POWERPOINT_DESKTOP.BAD_SLIDE", "Slide numbers start at 1", "Use a slide number from 1.");
        List<String> a = new ArrayList<>(List.of(file, saveAs, save ? "1" : "0", String.valueOf(slide), num(left), num(top), num(width), num(height)));
        a.addAll(Arrays.asList(more));
        return a;
    }

    /** The OOA_SHAPE line: shape name and its place in points. */
    private static Map<String, Object> shapeOut(String stdout) {
        String line = stdout.lines().filter(l -> l.startsWith("OOA_SHAPE=")).reduce((x, y) -> y).orElse("OOA_SHAPE=||||");
        String[] p = line.substring(10).split("\\|", -1);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("shape", p[0]);
        Map<String, Object> pos = new LinkedHashMap<>();
        String[] keys = {"left", "top", "width", "height"};
        for (int i = 0; i < 4; i++) pos.put(keys[i], i + 1 < p.length && !p[i + 1].isBlank() ? new BigDecimal(p[i + 1].replace(',', '.')).setScale(1, java.math.RoundingMode.HALF_UP) : null);
        out.put("position", pos);
        return out;
    }

    private static final String D_PATH = "Presentation (.pptx, .pptm, .ppt).";
    private static final String D_SLIDE = "Slide number, 1 for the first.";
    private static final String D_SAVE = "Save the presentation in place afterwards (when save_as is empty).";
    private static final String D_SAVE_AS = "Save to this file instead of the opened one.";
    private static final String D_OVERWRITE = "Replace save_as when it exists; false stops with POWERPOINT_DESKTOP.EXISTS.";
    private static final String D_POS = "In points (72 per inch) from the slide's top-left corner; PowerPoint's own when empty.";

    @Action(id = "insert_video", name = "Insert video", category = "Media", timeoutSeconds = 900, capabilities = {"desktop", "filesystem"},
            description = "Put a video (MP4, WMV, MOV …) on a slide, embedded in the presentation, at a position and size.",
            panel = "path, slide, video, [Position and size: left, top, width, height] | save, save_as, overwrite, timeout_seconds")
    @Output(name = "shape", type = OoaType.STRING, description = "Name of the new shape on the slide.")
    @Output(name = "position", type = OoaType.DICTIONARY, description = "left, top, width and height of the shape in points.")
    public Map<String, Object> insertVideo(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = D_PATH) String path,
            @Input(name = "slide", type = OoaType.NUMBER, required = false, defaultValue = "1", min = 1, description = D_SLIDE) int slide,
            @Input(name = "video", type = OoaType.FILE, description = "Video file to embed.") String video,
            @Input(name = "left", type = OoaType.NUMBER, required = false, description = D_POS) Double left,
            @Input(name = "top", type = OoaType.NUMBER, required = false, description = D_POS) Double top,
            @Input(name = "width", type = OoaType.NUMBER, required = false, min = 1, description = D_POS) Double width,
            @Input(name = "height", type = OoaType.NUMBER, required = false, min = 1, description = D_POS) Double height,
            @Input(name = "save", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = D_SAVE) boolean save,
            @Input(name = "save_as", type = OoaType.FILE, required = false, description = D_SAVE_AS) String saveAs,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = D_OVERWRITE) boolean overwrite,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "300", description = "Maximum run time.") int timeout) {
        String body = "  $n = [int]$args[3]\n"
                + "  if ($n -lt 1 -or $n -gt $pres.Slides.Count) { throw \"OOA_BAD_SLIDE: there is no slide $n; the presentation has $($pres.Slides.Count)\" }\n"
                + "  $shape = $pres.Slides.Item($n).Shapes.AddMediaObject2($args[8], 0, -1, 10, 10)\n" + placeShape();
        ProcessRunner.Result r = powerpoint(ctx, body, args(existing(ctx, path, "Presentation"), target(ctx, saveAs, overwrite), save, slide, left, top, width, height,
                existing(ctx, video, "Video")), timeout);
        return shapeOut(r.stdout());
    }

    @Action(id = "insert_file", name = "Insert file", category = "Media", timeoutSeconds = 900, capabilities = {"desktop", "filesystem"},
            description = "Embed a file (an Excel sheet, a PDF, any file) on a slide as an icon that opens it when double-clicked in the slide show or editor.",
            panel = "path, slide, file, label, [Position and size: left, top, width, height] | save, save_as, overwrite, timeout_seconds")
    @Output(name = "shape", type = OoaType.STRING, description = "Name of the new shape on the slide.")
    @Output(name = "position", type = OoaType.DICTIONARY, description = "left, top, width and height of the shape in points.")
    public Map<String, Object> insertFile(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = D_PATH) String path,
            @Input(name = "slide", type = OoaType.NUMBER, required = false, defaultValue = "1", min = 1, description = D_SLIDE) int slide,
            @Input(name = "file", type = OoaType.FILE, description = "File to embed.") String file,
            @Input(name = "label", type = OoaType.STRING, required = false, description = "Text under the icon; the file name when empty.") String label,
            @Input(name = "left", type = OoaType.NUMBER, required = false, description = D_POS) Double left,
            @Input(name = "top", type = OoaType.NUMBER, required = false, description = D_POS) Double top,
            @Input(name = "width", type = OoaType.NUMBER, required = false, min = 1, description = D_POS) Double width,
            @Input(name = "height", type = OoaType.NUMBER, required = false, min = 1, description = D_POS) Double height,
            @Input(name = "save", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = D_SAVE) boolean save,
            @Input(name = "save_as", type = OoaType.FILE, required = false, description = D_SAVE_AS) String saveAs,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = D_OVERWRITE) boolean overwrite,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "300", description = "Maximum run time.") int timeout) {
        String f = existing(ctx, file, "File");
        String l = label == null || label.isBlank() ? Path.of(f).getFileName().toString() : label;
        String body = "  $n = [int]$args[3]\n"
                + "  if ($n -lt 1 -or $n -gt $pres.Slides.Count) { throw \"OOA_BAD_SLIDE: there is no slide $n; the presentation has $($pres.Slides.Count)\" }\n"
                // AddOLEObject(Left, Top, Width, Height, ClassName, FileName, DisplayAsIcon, IconFileName, IconIndex, IconLabel, Link)
                + "  $shape = $pres.Slides.Item($n).Shapes.AddOLEObject(10, 10, -1, -1, '', $args[8], -1, '', 0, $args[9], 0)\n" + placeShape();
        ProcessRunner.Result r = powerpoint(ctx, body, args(existing(ctx, path, "Presentation"), target(ctx, saveAs, overwrite), save, slide, left, top, width, height, f, l), timeout);
        return shapeOut(r.stdout());
    }

    @Action(id = "paste", name = "Paste into slide", category = "Media", timeoutSeconds = 900, capabilities = {"desktop", "filesystem"},
            description = "Paste what is on the clipboard (a chart or range copied from Excel, a picture, text) onto a slide, optionally as a picture, and place it. PowerPoint only pastes into an open window, so a minimised PowerPoint window shows while it works.",
            panel = "path, slide, paste_as, [Position and size: left, top, width, height] | save, save_as, overwrite, timeout_seconds")
    @Output(name = "shape", type = OoaType.STRING, description = "Name of the pasted shape on the slide.")
    @Output(name = "position", type = OoaType.DICTIONARY, description = "left, top, width and height of the shape in points.")
    public Map<String, Object> paste(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = D_PATH) String path,
            @Input(name = "slide", type = OoaType.NUMBER, required = false, defaultValue = "1", min = 1, description = D_SLIDE) int slide,
            @Input(name = "paste_as", type = OoaType.STRING, required = false, defaultValue = "\"default\"", enumValues = {"default", "picture", "text"},
                    description = "default pastes as PowerPoint would (a chart stays a chart); picture pastes a PNG picture of it; text pastes plain text.") String pasteAs,
            @Input(name = "left", type = OoaType.NUMBER, required = false, description = D_POS) Double left,
            @Input(name = "top", type = OoaType.NUMBER, required = false, description = D_POS) Double top,
            @Input(name = "width", type = OoaType.NUMBER, required = false, min = 1, description = D_POS) Double width,
            @Input(name = "height", type = OoaType.NUMBER, required = false, min = 1, description = D_POS) Double height,
            @Input(name = "save", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = D_SAVE) boolean save,
            @Input(name = "save_as", type = OoaType.FILE, required = false, description = D_SAVE_AS) String saveAs,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = D_OVERWRITE) boolean overwrite,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "300", description = "Maximum run time.") int timeout) {
        String mode = pasteAs == null || pasteAs.isBlank() ? "default" : pasteAs.toLowerCase(Locale.ROOT);
        String type = switch (mode) { case "picture" -> "2"; case "text" -> "9"; case "default" -> "0";
            default -> throw new ActionException("POWERPOINT_DESKTOP.BAD_OPTION", "paste_as must be default, picture or text", "Pick one of the three."); };
        String body = "  $n = [int]$args[3]\n"
                + "  if ($n -lt 1 -or $n -gt $pres.Slides.Count) { throw \"OOA_BAD_SLIDE: there is no slide $n; the presentation has $($pres.Slides.Count)\" }\n"
                + "  try { $pres.Windows.Item(1).WindowState = 2 } catch { }   # ppWindowMinimized\n"
                + "  $pres.Windows.Item(1).View.GotoSlide($n)   # with a window, PowerPoint pastes into the slide it shows\n"
                + "  $s = $pres.Slides.Item($n)\n"
                // PpPasteDataType: ppPastePNG = 6, ppPasteText = 7; default is a plain Paste
                + "  $range = if ($args[8] -eq '0') { $s.Shapes.Paste() } elseif ($args[8] -eq '2') { $s.Shapes.PasteSpecial(6) } else { $s.Shapes.PasteSpecial(7) }\n"
                + "  $shape = @($range)[0]\n" + placeShape();
        ProcessRunner.Result r = powerpoint(ctx, body, args(existing(ctx, path, "Presentation"), target(ctx, saveAs, overwrite), save, slide, left, top, width, height, type), timeout, true);
        return shapeOut(r.stdout());
    }

    /**
     * Runs the macro with its arguments as plain strings (a call per argument count, so PowerShell never wraps them) while
     * a second runspace watches for VBA's error box: it reads the message, presses End, and the script reports the
     * message instead of waiting until the time limit.
     */
    static final String MACRO_BODY = String.join("\n",
            "  Add-Type -TypeDefinition @'",
            "using System; using System.Text; using System.Runtime.InteropServices; using System.Collections.Generic;",
            "public static class OoaVba {",
            "  delegate bool EnumProc(IntPtr h, IntPtr l);",
            "  [DllImport(\"user32.dll\")] static extern bool EnumWindows(EnumProc f, IntPtr l);",
            "  [DllImport(\"user32.dll\")] static extern bool EnumChildWindows(IntPtr p, EnumProc f, IntPtr l);",
            "  [DllImport(\"user32.dll\")] static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);",
            "  [DllImport(\"user32.dll\", CharSet = CharSet.Unicode)] static extern int GetWindowText(IntPtr h, StringBuilder s, int n);",
            "  [DllImport(\"user32.dll\", CharSet = CharSet.Unicode)] static extern int GetClassName(IntPtr h, StringBuilder s, int n);",
            "  [DllImport(\"user32.dll\")] static extern bool IsWindowVisible(IntPtr h);",
            "  [DllImport(\"user32.dll\")] static extern IntPtr SendMessage(IntPtr h, uint m, IntPtr w, IntPtr l);",
            "  static string Text(IntPtr h) { var b = new StringBuilder(1024); GetWindowText(h, b, 1024); return b.ToString(); }",
            "  static string Cls(IntPtr h) { var b = new StringBuilder(256); GetClassName(h, b, 256); return b.ToString(); }",
            "  /** The message of a 'Microsoft Visual Basic' error box of the process, after pressing its End button; null when there is none. */",
            "  public static string EndErrorBox(uint pid) {",
            "    string found = null;",
            "    EnumWindows((h, l) => { uint p; GetWindowThreadProcessId(h, out p);",
            "      if (p != pid || !IsWindowVisible(h) || Cls(h) != \"#32770\" || !Text(h).Contains(\"Visual Basic\")) return true;",
            "      var parts = new List<string>(); IntPtr end = IntPtr.Zero;",
            "      EnumChildWindows(h, (c, x) => { string cl = Cls(c), t = Text(c);",
            "        if (cl == \"Static\" && t.Trim().Length > 0) parts.Add(t.Trim());",
            "        if (cl == \"Button\" && t.Replace(\"&\", \"\") == \"End\") end = c; return true; }, IntPtr.Zero);",
            "      if (end == IntPtr.Zero) return true;",
            "      found = string.Join(\" \", parts).Replace(\"\\r\", \" \").Replace(\"\\n\", \" \");",
            "      SendMessage(end, 0x00F5, IntPtr.Zero, IntPtr.Zero);   // BM_CLICK",
            "      return false; }, IntPtr.Zero);",
            "    return found;",
            "  }",
            "}",
            "'@",
            "  $pptPid = (Get-Process POWERPNT | Sort-Object StartTime -Descending | Select-Object -First 1).Id",
            "  $watch = [PowerShell]::Create()",
            "  [void]$watch.AddScript({ param($p) for ($i = 0; $i -lt 36000; $i++) { $m = [OoaVba]::EndErrorBox([uint32]$p); if ($m) { return $m }; Start-Sleep -Milliseconds 200 } }).AddArgument($pptPid)",
            "  $async = $watch.BeginInvoke()",
            "  $name = $args[3]",
            "  if ($name -notmatch '!') { $name = \"'\" + $pres.Name + \"'!\" + $name }",
            // an object[] filled element by element with [string]s: PowerShell neither wraps the values (Excel's
            // DISP_E_TYPEMISMATCH, DESK-4) nor passes them ByRef ($pp.Run's ParamArray refuses those)
            "  $rest = @($args | Select-Object -Skip 4)",
            "  $params = New-Object 'object[]' ($rest.Count + 1)",
            "  $params[0] = [string]$name",
            "  for ($i = 0; $i -lt $rest.Count; $i++) { $params[$i + 1] = [string]$rest[$i] }",
            "  $r = $null",
            "  try {",
            "    $r = [System.__ComObject].InvokeMember('Run', [Reflection.BindingFlags]::InvokeMethod, $null, $pp, $params)",
            "  } catch {",
            "    $msg = if ($async.IsCompleted) { @($watch.EndInvoke($async)) -join ' ' } else { '' }",
            "    if ($msg) { \"OOA_MACRO_ERROR=$msg\"; return }   # return, not exit, so the finally below closes the presentation",
            "    if (\"$_ $($_.Exception.InnerException.Message)\" -match 'not defined|cannot run|not be found|cannot be found|disabled') { 'OOA_NO_MACRO'; return }",
            "    throw",
            "  } finally { $watch.Stop(); $watch.Dispose() }",
            "  if ($r -ne $null) { \"OOA_RESULT=$r\" }",
            "");

    @Action(id = "run_macro", name = "Run macro", category = "Macros", timeoutSeconds = 3600, capabilities = {"desktop", "filesystem"},
            description = "Open a macro-enabled presentation, run a VBA macro with up to 5 text arguments, return its result and optionally save. A macro that stops with an error is reported straight away with its message.",
            panel = "path, macro, arguments, save | save_as, overwrite, timeout_seconds")
    @Output(name = "result", type = OoaType.STRING, description = "Value the macro returned (for a Function), as text; empty for a Sub.")
    public Map<String, Object> runMacro(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "Presentation with macros (.pptm, .ppsm, .ppt).") String path,
            @Input(name = "macro", type = OoaType.STRING, example = "Module1.BuildDeck", description = "Macro name, optionally with its module.") String macro,
            @Input(name = "arguments", type = OoaType.LIST, required = false, description = "Up to 5 arguments, passed to the macro as text.") List<Object> arguments,
            @Input(name = "save", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Save the presentation in place afterwards (when save_as is empty).") boolean save,
            @Input(name = "save_as", type = OoaType.FILE, required = false, description = D_SAVE_AS) String saveAs,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = D_OVERWRITE) boolean overwrite,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "600", description = "Maximum run time.") int timeout) {
        if (macro == null || !macro.matches("^[A-Za-z0-9_.!' \\[\\]-]{1,200}$"))
            throw new ActionException("POWERPOINT_DESKTOP.BAD_MACRO", "Invalid macro name '" + macro + "'", "Use a name such as Module1.BuildDeck.");
        List<String> a = new ArrayList<>(List.of(existing(ctx, path, "Presentation"), target(ctx, saveAs, overwrite), save ? "1" : "0", macro));
        if (arguments != null) {
            if (arguments.size() > 5) throw new ActionException("POWERPOINT_DESKTOP.TOO_MANY_ARGS", "At most 5 macro arguments", "Pass more values as one text, e.g. separated by |.");
            for (Object o : arguments) a.add(Values.text(o));
        }
        ProcessRunner.Result r = powerpoint(ctx, MACRO_BODY, a, timeout);
        String result = r.stdout().lines().filter(l -> l.startsWith("OOA_RESULT=")).map(l -> l.substring(11)).reduce((x, y) -> y).orElse("");
        return Map.of("result", result);
    }
}
