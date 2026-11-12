package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;

import javax.sound.sampled.*;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

@OoaPackage(id = "ooa.sound", name = "Play sound", version = "1.2.0", description = "Beep, play a WAV file, or play MP3, WMA, M4A and video files, e.g. to alert an attended user.")
public final class SoundPackage {
    /** Media players started without waiting, so Stop media can end them. */
    static final List<Process> PLAYING = new CopyOnWriteArrayList<>();
    static final Set<String> MEDIA = Set.of("mp3", "wma", "m4a", "aac", "wav", "mid", "midi", "aif", "aiff", "au", "snd", "flac",
            "mp4", "m4v", "wmv", "avi", "mov", "mpg", "mpeg", "3gp", "mkv", "webm");
    static final Set<String> VIDEO = Set.of("mp4", "m4v", "wmv", "avi", "mov", "mpg", "mpeg", "3gp", "mkv", "webm");

    /**
     * Plays one file with Windows Media Foundation (WPF MediaPlayer, or MediaElement in a window to show video).
     * Arguments arrive on stdin, one per line: path, volume 0-1, repeat, show_video, full_screen, width, height, max seconds.
     */
    static final String WINDOWS_PLAYER = """
            $ErrorActionPreference = 'Stop'
            $path = [Console]::In.ReadLine(); $vol = [double]::Parse([Console]::In.ReadLine(), [Globalization.CultureInfo]::InvariantCulture)
            $repeat = [int]([Console]::In.ReadLine()); $show = [Console]::In.ReadLine() -eq 'true'; $full = [Console]::In.ReadLine() -eq 'true'
            $width = [int]([Console]::In.ReadLine()); $height = [int]([Console]::In.ReadLine()); $max = [double]::Parse([Console]::In.ReadLine(), [Globalization.CultureInfo]::InvariantCulture)
            Add-Type -AssemblyName PresentationCore, PresentationFramework, WindowsBase
            $uri = New-Object System.Uri($path)
            $script:failed = $null; $script:plays = 0; $script:length = 0; $script:opened = $false
            # max_seconds counts from the moment playing starts: opening a file can take seconds on a busy runner
            $clock = [Diagnostics.Stopwatch]::StartNew(); $playing = New-Object Diagnostics.Stopwatch; $openLimit = 60
            if ($show) {
              $w = New-Object System.Windows.Window
              $w.Title = [IO.Path]::GetFileName($path); $w.Topmost = $true; $w.WindowStartupLocation = 'CenterScreen'; $w.Background = 'Black'
              if ($full) { $w.WindowStyle = 'None'; $w.WindowState = 'Maximized' } else { $w.Width = $width; $w.Height = $height }
              $me = New-Object System.Windows.Controls.MediaElement
              $me.LoadedBehavior = 'Manual'; $me.UnloadedBehavior = 'Close'; $me.Volume = $vol; $me.Source = $uri
              $me.Add_MediaOpened({ $script:opened = $true; $playing.Start(); if ($me.NaturalDuration.HasTimeSpan) { $script:length = $me.NaturalDuration.TimeSpan.TotalSeconds } })
              $me.Add_MediaEnded({ $script:plays++; if ($script:plays -lt $repeat) { $me.Position = [TimeSpan]::Zero; $me.Play() } else { $w.Close() } })
              $me.Add_MediaFailed({ param($s, $e) $script:failed = $e.ErrorException.Message; $w.Close() })
              $timer = New-Object System.Windows.Threading.DispatcherTimer
              $timer.Interval = [TimeSpan]::FromMilliseconds(250)
              $timer.Add_Tick({
                if (-not $script:opened -and $clock.Elapsed.TotalSeconds -ge $openLimit) { $script:failed = "The file did not open within $openLimit s."; $w.Close() }
                elseif ($script:opened -and $max -gt 0 -and $playing.Elapsed.TotalSeconds -ge $max) { $w.Close() } })
              $timer.Start()
              $w.Content = $me
              $w.Add_Loaded({ $me.Play() })
              $w.Add_KeyDown({ param($s, $e) if ($e.Key -eq 'Escape') { $w.Close() } })
              [void]$w.ShowDialog()
              if (-not $script:failed -and -not $script:opened) { $script:failed = 'The window was closed before the file started playing.' }
            } else {
              $p = New-Object System.Windows.Media.MediaPlayer
              $p.Volume = $vol
              $p.Add_MediaFailed({ param($s, $e) $script:failed = $e.ErrorException.Message })
              $p.Add_MediaEnded({ $script:plays++; if ($script:plays -lt $repeat) { $p.Position = [TimeSpan]::Zero; $p.Play() } })
              $p.Open($uri)
              function Pump { $f = New-Object System.Windows.Threading.DispatcherFrame
                [void][System.Windows.Threading.Dispatcher]::CurrentDispatcher.BeginInvoke([System.Windows.Threading.DispatcherPriority]::Background,
                  [System.Windows.Threading.DispatcherOperationCallback]{ param($o) $o.Continue = $false; $null }, $f)
                [System.Windows.Threading.Dispatcher]::PushFrame($f) }
              while (-not $p.NaturalDuration.HasTimeSpan -and -not $script:failed -and $clock.Elapsed.TotalSeconds -lt $openLimit) { Pump; Start-Sleep -Milliseconds 50 }
              if (-not $script:failed -and -not $p.NaturalDuration.HasTimeSpan) { $script:failed = 'The file could not be opened as audio or video.' }
              if (-not $script:failed) {
                $script:length = $p.NaturalDuration.TimeSpan.TotalSeconds
                $p.Play(); $playing.Start()
                while ($script:plays -lt $repeat -and -not $script:failed) {
                  Pump; Start-Sleep -Milliseconds 50
                  if ($max -gt 0 -and $playing.Elapsed.TotalSeconds -ge $max) { break }
                }
                $p.Stop()
              }
              $p.Close()
            }
            if ($script:failed) { Write-Output ("__OOA_MEDIA_FAIL__ " + $script:failed); exit 2 }
            Write-Output ("__OOA_MEDIA__ " + $script:length.ToString([Globalization.CultureInfo]::InvariantCulture) + " " + $playing.Elapsed.TotalSeconds.ToString([Globalization.CultureInfo]::InvariantCulture))
            """;

    @Action(id = "beep", name = "Beep", category = "Sound", capabilities = "desktop", description = "Play the system beep, once or several times.",
            panel = "times, interval_ms")
    public Map<String, Object> beep(ActionContext ctx,
            @Input(name = "times", type = OoaType.NUMBER, required = false, defaultValue = "1", min = 1, max = 100, description = "How many beeps (1 to 20).") int times,
            @Input(name = "interval_ms", type = OoaType.NUMBER, required = false, defaultValue = "300", min = 0, description = "Pause between beeps.") int interval) {
        if (GraphicsEnvironment.isHeadless()) { ctx.info("Beep skipped: no desktop session"); return Map.of(); }
        int n = Math.max(1, Math.min(20, times));
        for (int i = 0; i < n; i++) {
            if (i > 0) { ctx.checkCancelled(); try { Thread.sleep(Math.max(0, Math.min(10_000, interval))); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; } }
            Toolkit.getDefaultToolkit().beep();
        }
        return Map.of();
    }

    /** Gain in decibels for a volume from 1 to 100 percent (100 leaves the file's level unchanged). */
    static float gainDb(int volumePercent) {
        int v = Math.max(1, Math.min(100, volumePercent));
        return (float) (20.0 * Math.log10(v / 100.0));
    }

    @Action(id = "play_media", name = "Play media file", category = "Sound", capabilities = "desktop", timeoutSeconds = 3600,
            description = "Play an MP3, WMA, M4A, WAV or other audio file, or a video (MP4, WMV, AVI...) in a window, with the media support of Windows; wait until it ends or continue while it plays.",
            panel = "path, show_video | wait, volume_percent, repeat, max_seconds, [Video window: full_screen, window_width, window_height]")
    @Output(name = "duration_seconds", type = OoaType.NUMBER, description = "Length of the media in seconds (0 when not known, e.g. when not waiting).")
    public Map<String, Object> playMedia(ActionContext ctx,
            @Input(name = "path", type = OoaType.FILE, description = "Audio or video file, e.g. C:\\alerts\\done.mp3.") String path,
            @Input(name = "show_video", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Show the picture in a window (for video files); audio files then show an empty window. Esc closes it.") boolean showVideo,
            @Input(name = "wait", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Wait until playing has finished; false continues while it plays (Stop media ends it).") boolean wait,
            @Input(name = "volume_percent", type = OoaType.NUMBER, required = false, defaultValue = "100", min = 0, max = 100, description = "Playback volume from 0 to 100 percent.") int volume,
            @Input(name = "repeat", type = OoaType.NUMBER, required = false, defaultValue = "1", min = 1, max = 100, description = "How many times to play the file.") int repeat,
            @Input(name = "max_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", min = 0, description = "Stop after this many seconds; 0 plays to the end.") double maxSeconds,
            @Input(name = "full_screen", type = OoaType.BOOLEAN, required = false, defaultValue = "false", visibleWhen = "show_video=true", description = "Show the video full screen.") boolean fullScreen,
            @Input(name = "window_width", type = OoaType.NUMBER, required = false, defaultValue = "800", min = 100, visibleWhen = "show_video=true", description = "Width of the video window in pixels.") int width,
            @Input(name = "window_height", type = OoaType.NUMBER, required = false, defaultValue = "480", min = 100, visibleWhen = "show_video=true", description = "Height of the video window in pixels.") int height) {
        Path p = ctx.resolvePath(path);
        if (!Files.isRegularFile(p)) throw new ActionException("FILE.NOT_FOUND", "Media file not found: " + p, "Check the path.");
        String ext = extension(p);
        if (!MEDIA.contains(ext)) throw new ActionException("SOUND.UNSUPPORTED", "Unsupported media type '." + ext + "'", "Use " + String.join(", ", new TreeSet<>(MEDIA)) + ".");
        if (volume < 0 || volume > 100) throw new ActionException("SOUND.BAD_VOLUME", "volume_percent must be 0 to 100, not " + volume, "Set volume_percent between 0 and 100.");
        int loops = Math.max(1, Math.min(100, repeat));
        List<String> cmd = mediaCommand(p, showVideo);
        String stdin = ProcessRunner.WINDOWS ? String.join("\n", p.toAbsolutePath().toString(), String.valueOf(volume / 100.0), String.valueOf(loops), String.valueOf(showVideo),
                String.valueOf(fullScreen), String.valueOf(Math.max(100, width)), String.valueOf(Math.max(100, height)), String.valueOf(Math.max(0, maxSeconds))) + "\n" : null;
        Path script = null;
        try {
            if (ProcessRunner.WINDOWS) {
                script = Files.createTempFile("ooa-media-", ".ps1");
                Files.writeString(script, "\uFEFF" + WINDOWS_PLAYER, StandardCharsets.UTF_8);
                cmd.add(script.toString());
            }
            if (!wait) {
                ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
                Process proc = pb.start();
                if (stdin != null) try (var os = proc.getOutputStream()) { os.write(stdin.getBytes(StandardCharsets.UTF_8)); }
                PLAYING.add(proc);
                Path s = script;
                proc.onExit().thenRun(() -> { PLAYING.remove(proc); if (s != null) try { Files.deleteIfExists(s); } catch (IOException ignored) { } });
                script = null;
                return Map.of("duration_seconds", java.math.BigDecimal.ZERO);
            }
            double limit = maxSeconds > 0 ? maxSeconds + 120 : 3600;   // the player allows 60 s to open the file
            ProcessRunner.Result r = ProcessRunner.run(ctx, cmd, null, stdin, limit, null, StandardCharsets.UTF_8);
            return Map.of("duration_seconds", mediaResult(r, ProcessRunner.WINDOWS));
        } catch (IOException e) {
            throw new ActionException("SOUND.NO_PLAYER", "Cannot start the media player: " + e.getMessage(), "Check the runner can play media.");
        } finally {
            if (script != null) try { Files.deleteIfExists(script); } catch (IOException ignored) { }
        }
    }

    @Action(id = "stop_media", name = "Stop media", category = "Sound", capabilities = "desktop",
            description = "Stop media files started by Play media file without waiting.", panel = "")
    @Output(name = "stopped", type = OoaType.NUMBER, description = "Number of players stopped.")
    public Map<String, Object> stopMedia(ActionContext ctx) {
        int n = 0;
        for (Process p : List.copyOf(PLAYING)) {
            if (p.isAlive()) { p.descendants().forEach(ProcessHandle::destroyForcibly); p.destroyForcibly(); n++; }
            PLAYING.remove(p);
        }
        return Map.of("stopped", java.math.BigDecimal.valueOf(n));
    }

    static String extension(Path p) {
        String name = p.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** The player command: PowerShell with Windows Media Foundation on Windows, afplay on macOS, ffplay, mpv or cvlc elsewhere. */
    static List<String> mediaCommand(Path p, boolean video) {
        if (ProcessRunner.WINDOWS) return new ArrayList<>(List.of("powershell", "-NoProfile", "-NonInteractive", "-STA", "-ExecutionPolicy", "Bypass", "-File"));
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac") && !video) return new ArrayList<>(List.of("afplay", p.toString()));
        for (String[] c : new String[][]{{"ffplay", "-autoexit", "-loglevel", "error"}, {"mpv", "--really-quiet"}, {"cvlc", "--play-and-exit"}}) {
            if (onPath(c[0])) {
                List<String> cmd = new ArrayList<>(List.of(c));
                if (!video && c[0].equals("ffplay")) cmd.add("-nodisp");
                if (!video && c[0].equals("mpv")) cmd.add("--no-video");
                cmd.add(p.toString());
                return cmd;
            }
        }
        throw new ActionException("SOUND.NO_PLAYER", "No media player found on this runner", "Install ffplay (FFmpeg), mpv or VLC, or run the bot on a Windows runner.");
    }

    static boolean onPath(String exe) {
        String path = System.getenv("PATH");
        if (path == null) return false;
        for (String d : path.split(java.io.File.pathSeparator)) if (Files.isExecutable(Path.of(d, exe))) return true;
        return false;
    }

    /** Duration from the Windows player's last line, or a typed error. */
    static java.math.BigDecimal mediaResult(ProcessRunner.Result r, boolean windows) {
        String out = r.stdout();
        int fail = out.indexOf("__OOA_MEDIA_FAIL__");
        if (fail >= 0) {
            String why = out.substring(fail + 18).strip();
            throw new ActionException("SOUND.CANNOT_PLAY", "Cannot play the file: " + why,
                    "Check the file plays in Windows Media Player; some formats (e.g. MKV, FLAC on older Windows) need a codec.");
        }
        int ok = out.indexOf("__OOA_MEDIA__");
        if (ok >= 0) {
            String[] parts = out.substring(ok + 13).strip().split("\\s+");
            try { return new java.math.BigDecimal(parts[0]).setScale(3, java.math.RoundingMode.HALF_UP).stripTrailingZeros(); }
            catch (NumberFormatException e) { return java.math.BigDecimal.ZERO; }
        }
        if (r.exitCode() != 0)
            throw new ActionException("SOUND.CANNOT_PLAY", "The media player stopped with code " + r.exitCode() + ": " + ApplicationPackage.tail((r.stderr().isBlank() ? out : r.stderr()).strip()), "Check the file plays on this runner.");
        if (windows) throw new ActionException("SOUND.CANNOT_PLAY", "The media player gave no result", "Check the file plays in Windows Media Player.");
        return java.math.BigDecimal.ZERO;
    }

    @Action(id = "play", name = "Play sound file", category = "Sound", capabilities = "desktop", timeoutSeconds = 600,
            description = "Play a WAV, AIFF or AU sound file, waiting until it ends or continuing while it plays.",
            panel = "path | wait, volume_percent, repeat")
    public Map<String, Object> play(ActionContext ctx, @Input(name = "path", type = OoaType.FILE, description = "Sound file (WAV recommended).") String path,
            @Input(name = "wait", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Wait until the sound has finished; false continues while it plays.") boolean wait,
            @Input(name = "volume_percent", type = OoaType.NUMBER, required = false, defaultValue = "100", min = 0, max = 100, description = "Playback volume from 1 to 100 percent of the file's level.") int volume,
            @Input(name = "repeat", type = OoaType.NUMBER, required = false, defaultValue = "1", min = 1, max = 100, description = "How many times to play the sound (1 to 20).") int repeat) {
        Path p = ctx.resolvePath(path);
        if (!Files.isRegularFile(p)) throw new ActionException("FILE.NOT_FOUND", "Sound file not found: " + p, "Check the path.");
        int loops = Math.max(1, Math.min(20, repeat));
        Clip clip = null;
        try (AudioInputStream in = AudioSystem.getAudioInputStream(p.toFile())) {
            clip = AudioSystem.getClip();
            clip.open(in);
            if (volume < 100 && clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                FloatControl g = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
                g.setValue(Math.max(g.getMinimum(), gainDb(volume)));
            }
            if (!wait) {
                Clip c = clip;
                c.addLineListener(e -> { if (e.getType() == LineEvent.Type.STOP) c.close(); });
                c.loop(loops - 1);
                clip = null;  // closes itself when it stops
                return Map.of();
            }
            clip.loop(loops - 1);
            long ms = clip.getMicrosecondLength() / 1000 * loops;
            long end = System.currentTimeMillis() + ms + 200;
            while (System.currentTimeMillis() < end) { ctx.checkCancelled(); Thread.sleep(50); }
        } catch (UnsupportedAudioFileException e) { throw new ActionException("SOUND.UNSUPPORTED", "Unsupported sound format", "Use a WAV file.");
        } catch (LineUnavailableException | IllegalArgumentException e) { throw new ActionException("SOUND.NO_DEVICE", "No audio runner available: " + e.getMessage(), "Run on a runner with sound output.");
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), "Check that the file or folder exists, is not open in another program, and that the runner's account may read and write it.", true);
        } catch (InterruptedException e) { Thread.currentThread().interrupt();
        } finally { if (clip != null) clip.close(); }
        return Map.of();
    }
}
