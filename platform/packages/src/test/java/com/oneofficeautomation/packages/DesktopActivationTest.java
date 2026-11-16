package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.ActionException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** A window that exists but stays in the background is reported as such, not as missing (real-world tests finding 5). */
class DesktopActivationTest {
    @Test void aWindowKeptInTheBackgroundIsNotReportedMissing() {
        ActionException kept = DesktopPackage.activationError("OOA Order Entry", true);
        assertEquals("WINDOW.NOT_ACTIVATED", kept.code());
        assertTrue(kept.getMessage().contains("come to the front"));
        assertTrue(kept.suggestedFix().contains("Desktop objects"), "says which steps still work");
        ActionException missing = DesktopPackage.activationError("OOA Order Entry", false);
        assertEquals("DESKTOP.WINDOW_NOT_FOUND", missing.code());
        assertTrue(kept.retryable() && missing.retryable());
    }
}
