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

    static Robot robot() {
        requireDesktop();
        try { Robot r = new Robot(); r.setAutoWaitForIdle(true); return r; }
        catch (AWTException | SecurityException e) { throw new ActionException("DESKTOP.NO_SESSION", "Cannot control the desktop: " + e.getMessage(), "Check the session is unlocked."); }
    }
}
