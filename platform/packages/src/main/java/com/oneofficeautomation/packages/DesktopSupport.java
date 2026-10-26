package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.ActionException;

import java.awt.*;

/** Shared checks for actions that need an interactive desktop session. */
final class DesktopSupport {
    private DesktopSupport() {}

    static void requireDesktop() {
        if (GraphicsEnvironment.isHeadless())
            throw new ActionException("DESKTOP.NO_SESSION", "This action needs a signed-in desktop session",
                    "Run the agent attended, or unattended on a runner with auto-login enabled.");
    }

    /**
     * The mode of the run in progress, set by the runner: attended, unattended or headless. Empty outside a runner
     * (Studio, ooa run), where the person who started the agent is at the screen.
     */
    static String runMode() { return System.getProperty("ooa.runMode", ""); }

    /** An unattended run has nobody at the screen: a dialog that would wait for an answer without a timeout fails at once. */
    static void requirePerson(int timeoutSeconds, String what) {
        if (timeoutSeconds <= 0 && runMode().equals("unattended"))
            throw new ActionException("RUN.NEEDS_PERSON", what + " waits for an answer, but this run is unattended and nobody is at the runner",
                    "Give it a timeout so it closes by itself, or run the agent attended.");
    }

    static Robot robot() {
        requireDesktop();
        try { Robot r = new Robot(); r.setAutoWaitForIdle(true); return r; }
        catch (AWTException | SecurityException e) { throw new ActionException("DESKTOP.NO_SESSION", "Cannot control the desktop: " + e.getMessage(), "Check the session is unlocked."); }
    }
}
