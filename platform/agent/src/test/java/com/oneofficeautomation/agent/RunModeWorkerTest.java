package com.oneofficeautomation.agent;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** The runner starts each run's worker with its mode: headless gets no desktop, and every mode reaches the commands. */
class RunModeWorkerTest {

    @Test void theModeComesFromTheJobAndDefaultsToUnattended() {
        assertEquals("attended", Agent.workerMode(Map.of("mode", "attended")));
        assertEquals("headless", Agent.workerMode(Map.of("mode", "headless")));
        assertEquals("unattended", Agent.workerMode(Map.of()));
        assertEquals("unattended", Agent.workerMode(Map.of("mode", "something-else")));
    }

    @Test void headlessWorkersHaveNoDesktopAndEveryWorkerKnowsItsMode() {
        List<String> headless = Agent.workerCommand("java", "headless", "false", Path.of("ws"));
        assertTrue(headless.contains("-Djava.awt.headless=true"), headless.toString());
        assertTrue(headless.contains("-Dooa.runMode=headless"));
        List<String> unattended = Agent.workerCommand("java", "unattended", "false", Path.of("ws"));
        assertTrue(unattended.contains("-Djava.awt.headless=false"), "unattended runs keep the desktop: " + unattended);
        assertTrue(unattended.contains("-Dooa.runMode=unattended"));
        assertTrue(Agent.workerCommand("java", "attended", "true", Path.of("ws")).contains("-Djava.awt.headless=true"), "a runner without a desktop stays without one");
        assertEquals(RunWorker.class.getName(), unattended.get(unattended.size() - 2));
    }
}
