package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.JsonLite;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Start program with a window state (hidden, minimized, maximized) goes through PowerShell's Start-Process. Under Windows
 * PowerShell 5.1 every argument used to arrive as one, because ConvertFrom-Json returns a JSON array as a single object
 * (real-world tests, 16 Nov 2026). The argument part now runs in 5.1 itself here.
 */
class ApplicationArgsTest {
    /** Runs the argument part of the start script in Windows PowerShell 5.1 and returns "count|first|second|...". */
    private static String args(List<String> given) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                ApplicationPackage.ARGS_SCRIPT + "[Console]::Out.Write([string]$a.Count + '|' + ($a -join '|'))");
        pb.environment().put("OOA_APP_ARGS", JsonLite.write(given));
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertEquals(0, p.waitFor(), out);
        return out;
    }

    @Test void everyArgumentStaysItsOwnInWindowsPowerShell51() throws Exception {
        assumeTrue(ProcessRunner.WINDOWS, "Windows PowerShell exists on Windows only");
        assertEquals("2|C:/QA tests/standin.py|C:/QA tests", args(List.of("C:/QA tests/standin.py", "C:/QA tests")));
        assertEquals("3|-n|3|127.0.0.1", args(List.of("-n", "3", "127.0.0.1")));
        assertEquals("1|only one", args(List.of("only one")));
        assertEquals("0|", args(List.of()));
    }

    @Test void theStartScriptUsesTheArgumentPart() {
        assertTrue(ApplicationPackage.START_SCRIPT.contains(ApplicationPackage.ARGS_SCRIPT));
        assertFalse(ApplicationPackage.START_SCRIPT.contains("@(ConvertFrom-Json"), "the 5.1 one-object array must not come back");
    }
}
