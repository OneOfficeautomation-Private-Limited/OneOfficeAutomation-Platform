package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;

import javax.sound.sampled.*;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.io.IOException;
import java.nio.file.*;
import java.util.Map;

@OoaPackage(id = "ooa.sound", name = "Play sound", version = "1.0.0", description = "Beep or play a WAV file, e.g. to alert an attended user.")
public final class SoundPackage {

    @Action(id = "beep", name = "Beep", category = "Sound", capabilities = "desktop", description = "Play the system beep.")
    public Map<String, Object> beep(ActionContext ctx) {
        if (!GraphicsEnvironment.isHeadless()) Toolkit.getDefaultToolkit().beep(); else ctx.info("Beep skipped: no desktop session");
        return Map.of();
    }

    @Action(id = "play", name = "Play sound file", category = "Sound", capabilities = "desktop", timeoutSeconds = 600, description = "Play a WAV, AIFF or AU sound file and wait until it ends.")
    public Map<String, Object> play(ActionContext ctx, @Input(name = "path", type = OoaType.FILE, description = "Sound file (WAV recommended).") String path) {
        Path p = ctx.resolvePath(path);
        if (!Files.isRegularFile(p)) throw new ActionException("FILE.NOT_FOUND", "Sound file not found: " + p, "Check the path.");
        try (AudioInputStream in = AudioSystem.getAudioInputStream(p.toFile()); Clip clip = AudioSystem.getClip()) {
            clip.open(in);
            clip.start();
            long ms = clip.getMicrosecondLength() / 1000;
            long end = System.currentTimeMillis() + ms + 200;
            while (System.currentTimeMillis() < end) { ctx.checkCancelled(); Thread.sleep(50); }
        } catch (UnsupportedAudioFileException e) { throw new ActionException("SOUND.UNSUPPORTED", "Unsupported sound format", "Use a WAV file.");
        } catch (LineUnavailableException | IllegalArgumentException e) { throw new ActionException("SOUND.NO_DEVICE", "No audio device available: " + e.getMessage(), "Run on a device with sound output.");
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), null, true);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return Map.of();
    }
}
