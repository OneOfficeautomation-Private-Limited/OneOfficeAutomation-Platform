package com.oneofficeautomation.cli;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** The command line a Windows service runs: full paths (a service account has another profile), quoting, modes. */
class WindowsServiceTest {

    @Test void serverServiceRunsWithoutBrowserOrBuiltInRunner() {
        List<String> a = WindowsService.serviceArgs("server", Map.of("data", "D:\\OOA Data", "port", "8443", "workspace", "D:\\OOA Workspace"));
        assertEquals(List.of("service-run", "server", "--data", "D:\\OOA Data", "--port", "8443", "--workspace", "D:\\OOA Workspace", "--no-browser", "--no-agent"), a);
    }

    @Test void commandCenterServiceKeepsHostAndDefaultsThePort() {
        List<String> a = WindowsService.serviceArgs("control-room", Map.of("data", "D:\\cc", "host", "0.0.0.0"));
        assertEquals(List.of("service-run", "control-room", "--data", "D:\\cc", "--port", "8720", "--host", "0.0.0.0"), a);
    }

    @Test void runnerServiceNamesItsConfigAndWorkspaceInFull() {
        List<String> a = WindowsService.serviceArgs("agent", Map.of());
        assertEquals("start", a.get(2));
        assertTrue(Path.of(a.get(a.indexOf("--config") + 1)).isAbsolute());
        assertTrue(Path.of(a.get(a.indexOf("--workspace") + 1)).isAbsolute());
    }

    @Test void unknownModeIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> WindowsService.serviceArgs("desktop", Map.of()));
    }

    @Test void commandLineQuotesPathsWithBlanks() {
        List<String> c = WindowsService.javaCommand(Path.of("C:\\Program Files\\ONEOFFICEAUTOMATION"), "service-run", "server");
        String line = WindowsService.commandLine(c);
        assertTrue(line.contains("\"C:\\Program Files\\ONEOFFICEAUTOMATION\\lib\\*\""), line);
        assertTrue(line.endsWith(Ooa.class.getName() + " service-run server"), line);
        assertTrue(c.get(0).endsWith("java.exe"));
        assertEquals("\"\"", WindowsService.quote(""));
        assertEquals("plain", WindowsService.quote("plain"));
    }
}
