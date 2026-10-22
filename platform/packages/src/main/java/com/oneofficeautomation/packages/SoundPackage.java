package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;

import javax.sound.sampled.*;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.io.IOException;
import java.nio.file.*;
import java.util.Map;

@OoaPackage(id = "ooa.sound", name = "Play sound", version = "1.1.0", description = "Beep or play a WAV file, e.g. to alert an attended user.")
public final class SoundPackage {

    @Action(id = "beep", name = "Beep", category = "Sound", capabilities = "desktop", description = "Play the system beep, once or several times.")
    public Map<String, Object> beep(ActionContext ctx,
            @Input(name = "times", type = OoaType.NUMBER, required = false, defaultValue = "1", description = "How many beeps (1 to 20).") int times,
            @Input(name = "interval_ms", type = OoaType.NUMBER, required = false, defaultValue = "300", description = "Pause between beeps.") int interval) {
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

    @Action(id = "play", name = "Play sound file", category = "Sound", capabilities = "desktop", timeoutSeconds = 600,
            description = "Play a WAV, AIFF or AU sound file, waiting until it ends or continuing while it plays.")
    public Map<String, Object> play(ActionContext ctx, @Input(name = "path", type = OoaType.FILE, description = "Sound file (WAV recommended).") String path,
            @Input(name = "wait", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Wait until the sound has finished; false continues while it plays.") boolean wait,
            @Input(name = "volume_percent", type = OoaType.NUMBER, required = false, defaultValue = "100", description = "Playback volume from 1 to 100 percent of the file's level.") int volume,
            @Input(name = "repeat", type = OoaType.NUMBER, required = false, defaultValue = "1", description = "How many times to play the sound (1 to 20).") int repeat) {
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
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), null, true);
        } catch (InterruptedException e) { Thread.currentThread().interrupt();
        } finally { if (clip != null) clip.close(); }
        return Map.of();
    }
}
