package com.oneofficeautomation.agent;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** A run that uses the screen does not start on a locked runner or one without a desktop; others always start. */
class DesktopSessionTest {
    static final Map<String, Object> SCREEN_AGENT = Map.of("requires", Map.of("capabilities", List.of("desktop", "filesystem")));
    static final Map<String, Object> FILE_AGENT = Map.of("requires", Map.of("capabilities", List.of("filesystem")));

    @Test void screenAgentsNeedAnUnlockedDesktop() {
        assertNull(DesktopSession.problem("unattended", SCREEN_AGENT, "unlocked"));
        assertTrue(DesktopSession.problem("unattended", SCREEN_AGENT, "locked").contains("locked"));
        assertTrue(DesktopSession.problem("attended", SCREEN_AGENT, "no_desktop").contains("Windows service"));
    }

    @Test void otherAgentsAndHeadlessRunsStartAnyway() {
        assertNull(DesktopSession.problem("unattended", FILE_AGENT, "locked"));
        assertNull(DesktopSession.problem("unattended", Map.of(), "no_desktop"));
        assertNull(DesktopSession.problem("headless", SCREEN_AGENT, "locked"));
    }

    @Test void theScreenStateIsOneOfThree() {
        assertTrue(List.of("unlocked", "locked", "no_desktop").contains(DesktopSession.state()), DesktopSession.state());
        assertNull(DesktopSession.afterRun("nothing"));
        assertNull(DesktopSession.afterRun(null));
    }
}
