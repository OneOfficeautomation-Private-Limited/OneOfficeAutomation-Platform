package com.oneofficeautomation.cli;

import com.oneofficeautomation.sdk.BuildInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** One release number everywhere: the build's, as the Command Center, the runner and ooa --version report it. */
class VersionTest {
    @Test void everyPartReportsTheBuildVersion() {
        assertTrue(BuildInfo.VERSION.matches("^\\d+\\.\\d+\\.\\d+.*$"), BuildInfo.VERSION);
        assertEquals(BuildInfo.VERSION, com.oneofficeautomation.controlroom.ControlRoom.VERSION);
        assertEquals(BuildInfo.VERSION, com.oneofficeautomation.agent.Agent.VERSION);
    }
}
