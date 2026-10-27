package com.oneofficeautomation.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Q13: the command line read by picocli. The command lines that the installer, the container, the Windows service,
 * the browser and CI write must still be understood exactly; wrong ones are refused before anything runs.
 */
class OoaCliTest {
    final OoaCli cli = OoaCli.full();

    @Test void installerAndContainerCommandLines() {
        OoaCli.Args a = cli.parse("db", "configure", "--engine", "sqlserver", "--host", "sql01", "--database", "ooa", "--user", "ooa_app",
            "--setup-user", "ooa_setup", "--data", "D:\\OOA Data");
        assertEquals("db configure", a.command());
        assertEquals(Map.of("engine", "sqlserver", "host", "sql01", "database", "ooa", "user", "ooa_app", "setup-user", "ooa_setup", "data", "D:\\OOA Data"), a.o());
        assertEquals(Map.of("setup", "true", "from-env", "SETUP_PW"), cli.parse("db", "set-password", "--setup", "--from-env", "SETUP_PW").o());
        assertEquals(Map.of("port", "8720", "data", "D:\\cc"), cli.parse("check", "--port", "8720", "--data", "D:\\cc").o());
        assertEquals(Map.of("mode", "agent", "no-start", "true"), cli.parse("service", "install", "--mode", "agent", "--no-start").o());
        assertEquals(Map.of("url", "https://cc:8720", "key", "ooareg_x", "name", "PC 7", "ca", "C:\\ooa.cer", "proxy", "none"),
            cli.parse("agent", "register", "--url", "https://cc:8720", "--key", "ooareg_x", "--name", "PC 7", "--ca", "C:\\ooa.cer", "--proxy", "none").o());
        assertEquals("agent uninstall-task", cli.parse("agent", "uninstall-task").command());
        // docker-entrypoint.sh: an empty --db-user is a value, not a missing one
        OoaCli.Args cr = cli.parse("control-room", "--host", "0.0.0.0", "--port", "8720", "--data", "/data", "--db-url", "jdbc:postgresql://pg/ooa",
            "--db-user", "", "--db-password-env", "OOA_DB_PASSWORD");
        assertEquals("", cr.get("db-user"));
        assertEquals("0.0.0.0", cr.get("host"));
        OoaCli.Args restore = cli.parse("backup", "restore", "/backups/b.zip", "--replace", "--data", "/data");
        assertEquals(List.of("/backups/b.zip"), restore.pos());
        assertEquals("true", restore.get("replace"));
    }

    @Test void serviceBrowserAndRunCommandLinesPassThrough() {
        List<String> service = WindowsService.serviceArgs("server", Map.of("data", "D:\\OOA Data", "port", "8443"));
        OoaCli.Args s = cli.parse(service.toArray(String[]::new));
        assertEquals("service-run", s.command());
        assertEquals(service.subList(1, service.size()), s.pos());
        // Chrome passes the extension's origin and, on Windows, its window
        assertEquals(List.of("chrome-extension://abcdef/", "--parent-window=0"), cli.parse("native-host", "chrome-extension://abcdef/", "--parent-window=0").pos());
        OoaCli.Args run = cli.parse("run", "flow.json", "-i", "amount=12.5", "--input", "name=@Acme");
        assertEquals(List.of("flow.json"), run.pos());
        assertEquals(List.of("amount=12.5", "name=@Acme"), run.all("input"));
        assertEquals(List.of("a.flow.json", "b.flow.json"), cli.parse("validate", "a.flow.json", "b.flow.json").pos());
        assertEquals("8811", cli.parse("server", "--port", "8720", "--port", "8811").get("port"), "the last one counts");
    }

    @Test void wrongCommandLinesAreRefusedBeforeRunning() {
        assertThrows(CommandLine.UnmatchedArgumentException.class, () -> cli.parse("server", "--prot", "8720"));
        assertThrows(CommandLine.MissingParameterException.class, () -> cli.parse("agent", "register", "--url", "https://cc:8720"));
        assertThrows(CommandLine.ParameterException.class, () -> cli.parse("control-room", "--port", "eighty"));
        assertThrows(CommandLine.MissingParameterException.class, () -> cli.parse("run"));
        String[] err = new String[1];
        assertEquals(1, capture(() -> cli.execute("tls", "configure"), err));
        assertTrue(err[0].contains("--pfx"), err[0]);
    }

    @Test void helpVersionAndCompletion() {
        String[] out = new String[1];
        assertEquals(1, capture(() -> cli.execute(), out), "no command: the help, as wrong use");
        for (String cmd : List.of("validate", "run", "server", "control-room", "agent", "db", "backup", "tls", "service", "completion"))
            assertTrue(out[0].contains("  " + cmd + " "), cmd + " missing from:\n" + out[0]);
        assertEquals(0, capture(() -> cli.execute("db", "configure", "--help"), out));
        assertTrue(out[0].contains("--setup-user=NAME") && out[0].contains("--windows-auth"), out[0]);
        assertEquals(0, capture(() -> cli.execute("--version"), out));
        assertTrue(out[0].startsWith("ONEOFFICEAUTOMATION "), out[0]);
        assertEquals(0, capture(() -> cli.execute("completion"), out));
        assertTrue(out[0].contains("complete -F") && out[0].contains("set-password"), "a bash completion script");
    }

    /** Colours in the help, when the test runs where picocli thinks there is a terminal. */
    static final String ANSI = "\u001B\\[[0-9;]*m";

    /** Runs with System.out and System.err captured together; returns the exit code. */
    static int capture(Supplier<Integer> run, String[] text) {
        PrintStream out = System.out, err = System.err;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream p = new PrintStream(buf, true, StandardCharsets.UTF_8);
        System.setOut(p);
        System.setErr(p);
        try { return run.get(); }
        finally { System.setOut(out); System.setErr(err); text[0] = buf.toString(StandardCharsets.UTF_8).replaceAll(ANSI, ""); }
    }
}
