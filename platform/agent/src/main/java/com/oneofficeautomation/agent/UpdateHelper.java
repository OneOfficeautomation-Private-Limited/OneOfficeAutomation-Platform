package com.oneofficeautomation.agent;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/**
 * Finishes a runner update in its own process, started from a copy of this jar in the temp folder (so it holds no file
 * in the program folder): waits for the runner to exit, moves update/lib into place of lib (keeping the old lib until
 * the new one is in), removes the leftovers and, for a runner started at sign-in, starts its task again. Uses the JDK
 * only. Arguments: installation folder, the runner's process id, and optionally the task name.
 */
public final class UpdateHelper {
    private UpdateHelper() { }

    public static void main(String[] a) throws Exception {
        apply(Path.of(a[0]), Long.parseLong(a[1]), a.length > 2 ? a[2] : null);
    }

    /** True when the new program files are in place. */
    static boolean apply(Path home, long pid, String task) throws Exception {
        ProcessHandle.of(pid).ifPresent(h -> { try { h.onExit().get(); } catch (Exception ignored) { } });
        Path lib = home.resolve("lib"), staged = home.resolve("update").resolve("lib"), old = home.resolve("lib.old");
        if (!Files.isDirectory(staged)) return false;
        boolean swapped = false;
        // files may stay locked for a moment after the runner exits (virus scanners, the service manager)
        for (int attempt = 0; attempt < 30 && !swapped; attempt++) {
            try {
                Updater.deleteTree(old);
                Files.move(lib, old);
                try { Files.move(staged, lib); swapped = true; }
                catch (Exception e) { Files.move(old, lib); throw e; }
            } catch (Exception e) { Thread.sleep(Duration.ofSeconds(1)); }
        }
        if (!swapped) return false;
        try { Updater.deleteTree(old); Updater.deleteTree(home.resolve("update")); } catch (Exception ignored) { }
        if (task != null) new ProcessBuilder(restartTask(task)).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        return true;
    }

    /** Starts the runner's sign-in task again (a service is started again by Windows itself). */
    static List<String> restartTask(String task) { return List.of("schtasks.exe", "/run", "/tn", task); }
}
