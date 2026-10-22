package com.oneofficeautomation.packages.ui;

import java.util.*;

/**
 * Operating-system desktop access that needs native code: visible window titles, closing and activating windows,
 * and global hotkeys. An optional module (packages-windows) provides it through java.util.ServiceLoader, so the
 * built-in packages and the One Runner keep working, with these features reported as unavailable, without it.
 */
public interface DesktopEvents {
    /** Titles of the visible top-level windows. */
    List<String> windowTitles();

    /** Asks the first window whose title matches the pattern (see {@link TitlePattern}) to close; false when none matches. */
    boolean closeWindow(String titlePattern);

    /** Brings the first matching window to the front; false when none matches. */
    boolean activateWindow(String titlePattern);

    /**
     * Screen rectangle {x, y, width, height} of the first matching window, used by mouse, keyboard and screen actions
     * that work relative to a window. Empty when no window matches or the implementation cannot tell.
     */
    default Optional<int[]> windowBounds(String titlePattern) { return Optional.empty(); }

    /**
     * Calls back on every press of a global hotkey such as "CTRL+ALT+F9" while the returned handle is open.
     * Throws IllegalArgumentException for an unknown key and IllegalStateException when another program owns the combination.
     */
    AutoCloseable onHotkey(String combination, Runnable onPress);

    /** The installed implementation, if any (cached). */
    static Optional<DesktopEvents> installed() { return Holder.INSTANCE; }

    final class Holder {
        private Holder() {}
        static final Optional<DesktopEvents> INSTANCE = load();
        private static Optional<DesktopEvents> load() {
            try {
                for (DesktopEvents d : ServiceLoader.load(DesktopEvents.class, DesktopEvents.class.getClassLoader())) return Optional.of(d);
            } catch (ServiceConfigurationError | LinkageError ignored) { /* native library missing on this machine */ }
            return Optional.empty();
        }
    }
}
