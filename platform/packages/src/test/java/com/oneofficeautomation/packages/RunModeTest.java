package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.ActionException;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/** Commands that wait for a person never hang an unattended run; attended runs and runs outside a runner are unchanged. */
class RunModeTest {
    @AfterEach void clear() { System.clearProperty("ooa.runMode"); }

    @Test void unattendedRunsRefuseDialogsThatWouldWaitForever() {
        System.setProperty("ooa.runMode", "unattended");
        ActionException e = assertThrows(ActionException.class, () -> DesktopSupport.requirePerson(0, "The message box"));
        assertEquals("RUN.NEEDS_PERSON", e.code());
        assertTrue(e.getMessage().startsWith("The message box waits for an answer"), e.getMessage());
        assertDoesNotThrow(() -> DesktopSupport.requirePerson(30, "The message box"), "a dialog with a timeout closes by itself");
    }

    @Test void attendedRunsAndRunsOutsideARunnerMayWait() {
        assertEquals("", DesktopSupport.runMode());
        assertDoesNotThrow(() -> DesktopSupport.requirePerson(0, "The prompt"));
        System.setProperty("ooa.runMode", "attended");
        assertDoesNotThrow(() -> DesktopSupport.requirePerson(0, "The prompt"));
    }

    @Test void unattendedAndHeadlessRunsDenyAiApprovalAtOnce() {
        System.setProperty("ooa.runMode", "unattended");
        long t0 = System.nanoTime();
        assertFalse(AiAgentPackage.approve(null, null, java.util.Map.of()));
        assertTrue(System.nanoTime() - t0 < 2_000_000_000L, "no waiting for an answer nobody can give");
    }
}
