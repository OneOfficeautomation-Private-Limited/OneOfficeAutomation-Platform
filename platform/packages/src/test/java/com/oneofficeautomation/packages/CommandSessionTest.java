package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.RunResult;
import com.oneofficeautomation.runner.Runner;
import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import com.oneofficeautomation.sdk.SecureString;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static com.oneofficeautomation.packages.PackagesTest.REG;
import static org.junit.jupiter.api.Assertions.*;

/** G20: command sessions (open / write / read / close) with real cmd.exe and powershell.exe processes. */
@EnabledOnOs(OS.WINDOWS)
class CommandSessionTest {
    @TempDir Path dir;
    final ApplicationPackage app = new ApplicationPackage();
    ActionContext ctx;

    ActionContext ctx() { if (ctx == null) ctx = PackagesTestSupport.ctx(dir, Map.of()); return ctx; }

    @AfterEach void closeAll() {
        if (ctx != null) for (Object s : ctx.sessions().values()) if (s instanceof AutoCloseable c) try { c.close(); } catch (Exception ignored) { }
    }

    Map<String, Object> open(String program, List<Object> args, String encoding, String session) {
        return app.openSession(ctx(), program, args, null, encoding, null, session);
    }
    void write(String session, String text) { app.writeSession(ctx(), session, text, null, true); }
    Map<String, Object> read(String session, String waitFor, String type, double timeout, boolean fail) {
        return app.readSession(ctx(), session, waitFor, type, timeout, "new", fail);
    }
    static final String PROMPT = "(?m)^[A-Z]:\\\\[^\\r\\n]*>$";

    @Test void cmdEchoVariableAcrossWritesAndPrompt() {
        Map<String, Object> o = open(null, null, null, null);
        assertTrue(((BigDecimal) o.get("process_id")).longValue() > 0);
        assertEquals(true, read("cmd", PROMPT, "regex", 20, true).get("matched"), "the first prompt");
        write("cmd", "echo hello-session");
        Map<String, Object> r = read("cmd", "(?m)^hello-session\\r?$", "regex", 20, true);
        assertEquals(true, r.get("matched"));
        assertEquals(false, r.get("exited"));
        assertEquals(BigDecimal.valueOf(-1), r.get("exit_code"));
        write("cmd", "set OOA_G20=abc123");
        write("cmd", "echo [%OOA_G20%]");
        assertTrue(((String) read("cmd", "[abc123]", "text", 20, true).get("output")).contains("[abc123]"), "a variable set by one write is seen by the next");
        // no wait_for: returns once the output is quiet; from all: everything so far
        String all = (String) app.readSession(ctx(), "cmd", null, "text", 5, "all", true).get("output");
        assertTrue(all.contains("hello-session") && all.contains("[abc123]"), all);
    }

    @Test void powershellSession() {
        open("powershell.exe", null, null, "ps");
        write("ps", "Write-Output ('ps-' + (6*7))");
        assertTrue(((String) read("ps", "ps-42", "text", 60, true).get("output")).contains("ps-42"));
        write("ps", "$x = 5; Write-Output \"x=$($x * 2)\"");
        assertEquals(true, read("ps", "x=10", "text", 30, true).get("matched"), "state kept between writes");
    }

    @Test void interactiveScriptWaitsForAnAnswer() throws Exception {
        Files.writeString(dir.resolve("ask.cmd"), "@echo off\r\nset /p NAME=What is your name? \r\necho Hello %NAME%, done.\r\n", StandardCharsets.US_ASCII);
        open("cmd.exe", null, null, null);
        read("cmd", PROMPT, "regex", 20, true);
        write("cmd", "call .\\ask.cmd");
        assertEquals(true, read("cmd", "What is your name?", "text", 20, true).get("matched"));
        // nothing more comes until it is answered
        assertEquals(false, read("cmd", "Hello", "text", 1, false).get("matched"));
        write("cmd", "Ada");
        assertTrue(((String) read("cmd", "done.", "text", 20, true).get("output")).contains("Hello Ada, done."));
    }

    @Test void timeoutFailsOrReturnsUnmatched() {
        open("cmd.exe", null, null, null);
        long t0 = System.nanoTime();
        ActionException e = assertThrows(ActionException.class, () -> read("cmd", "never-printed-9f3a", "text", 1, true));
        assertEquals("COMMAND.TIMEOUT", e.code());
        assertTrue(e.retryable());
        assertTrue((System.nanoTime() - t0) / 1e9 < 10);
        Map<String, Object> r = read("cmd", "never-printed-9f3a", "text", 1, false);
        assertEquals(false, r.get("matched"));
        assertTrue(((String) r.get("output")).contains(">"), "the unread output is returned: " + r);
        ActionException bad = assertThrows(ActionException.class, () -> read("cmd", "([", "regex", 1, true));
        assertEquals("COMMAND.BAD_PATTERN", bad.code());
    }

    @Test void secretIsMaskedInOutput() throws Exception {
        String pw = "Pw-77xq-secret";
        Files.writeString(dir.resolve("login.cmd"), "@echo off\r\nset /p PW=Password: \r\necho got %PW% end\r\n", StandardCharsets.US_ASCII);
        open("cmd.exe", null, null, null);
        write("cmd", "call .\\login.cmd");
        read("cmd", "Password:", "text", 20, true);
        app.writeSession(ctx(), "cmd", null, new SecureString(pw, "test/x/password"), true);
        String out = (String) read("cmd", "end", "text", 20, true).get("output");
        assertTrue(out.contains("got **** end"), out);
        assertFalse(out.contains(pw));
        // a secret typed as a command is echoed by cmd: masked too, in all output
        app.writeSession(ctx(), "cmd", null, new SecureString("echo " + pw, "test/x/cmd"), true);
        read("cmd", PROMPT, "regex", 20, true);
        String all = (String) app.readSession(ctx(), "cmd", null, "text", 2, "all", true).get("output");
        assertFalse(all.contains(pw), all);
    }

    @Test void exitCodeAfterExitAndErrors() {
        open("cmd.exe", null, null, null);
        write("cmd", "exit 7");
        Map<String, Object> r = read("cmd", null, "text", 10, true);
        assertEquals(true, r.get("exited"));
        assertEquals(BigDecimal.valueOf(7), r.get("exit_code"));
        ActionException w = assertThrows(ActionException.class, () -> write("cmd", "echo too late"));
        assertEquals("COMMAND.EXITED", w.code());
        ActionException ended = assertThrows(ActionException.class, () -> read("cmd", "x", "text", 5, true));
        assertEquals("COMMAND.EXITED", ended.code(), "the program ended before the text appeared");
        assertEquals(BigDecimal.valueOf(7), app.closeSession(ctx(), "cmd", true, 5).get("exit_code"));
        ActionException none = assertThrows(ActionException.class, () -> read("cmd", null, "text", 1, true));
        assertEquals("COMMAND.NO_SESSION", none.code());
        // close with exit: cmd ends by itself with 0
        open("cmd.exe", null, null, "c2");
        assertEquals(BigDecimal.ZERO, app.closeSession(ctx(), "c2", true, 10).get("exit_code"));
    }

    @Test void closeStopsTheProcessTree() {
        open("cmd.exe", null, null, "tree");
        write("tree", "ping -n 60 127.0.0.1");
        read("tree", "127.0.0.1", "text", 20, true);
        CommandSession s = (CommandSession) ctx().sessions().get("tree");
        List<ProcessHandle> kids = s.process.descendants().toList();
        assertFalse(kids.isEmpty(), "ping runs under cmd");
        app.closeSession(ctx(), "tree", false, 0);
        assertFalse(s.process.isAlive());
        for (ProcessHandle k : kids) assertFalse(k.isAlive(), "child " + k.pid() + " stopped");
    }

    @Test void sessionClosedAtRunEnd() {
        Map<String, Object> flow = Map.of("kind", "TaskBot", "id", "g20", "name", "g20",
                "variables", List.of(Map.of("name", "pid", "type", "Any", "direction", "output")),
                "steps", List.of(
                        Map.of("id", "o", "action", "ooa.application/open_session", "args", Map.of("session", "left"), "out", Map.of("process_id", "pid")),
                        Map.of("id", "w", "action", "ooa.application/write_session", "args", Map.of("session", "left", "text", "ping -n 60 127.0.0.1")),
                        Map.of("id", "r", "action", "ooa.application/read_session", "args", Map.of("session", "left", "wait_for", "127.0.0.1"))));
        RunResult r = Runner.builder(REG).workDir(dir).build().run(flow, Map.of());
        assertTrue(r.ok(), String.valueOf(r.error()));
        long pid = ((Number) r.outputs().get("pid")).longValue();
        Optional<ProcessHandle> h = ProcessHandle.of(pid);
        assertTrue(h.isEmpty() || !h.get().isAlive(), "cmd.exe stopped when the run ended");
        assertTrue(ProcessHandle.allProcesses().noneMatch(p -> p.parent().map(pp -> pp.pid() == pid).orElse(false) && p.isAlive()), "no children left");
    }

    @Test void utf8RoundTrip() throws Exception {
        String text = "Grüße ñ € 日本語 Ωmega";
        // powershell.exe: typed in and printed back, also as the answer to a question
        open("powershell.exe", null, "utf-8", "ps");
        write("ps", "$t = '" + text + "'; Write-Output \"[$t] len=$($t.Length)\"");
        Map<String, Object> r = read("ps", "len=" + text.length(), "text", 60, true);
        assertTrue(((String) r.get("output")).contains("[" + text + "]"), r.toString());
        write("ps", "$a = Read-Host 'Your name'; Write-Output \"<$a>\"");
        write("ps", text);
        assertTrue(((String) read("ps", ">", "text", 30, true).get("output")).contains("<" + text + ">"));
        // cmd.exe: its output (a file name here) is read as UTF-8, and the code page switch is not shown
        Files.writeString(dir.resolve(text + ".txt"), "x");
        open("cmd.exe", null, "utf-8", null);
        write("cmd", "dir /b *.txt");
        Map<String, Object> c = read("cmd", text + ".txt", "text", 20, true);
        assertFalse(((String) c.get("output")).contains("chcp 65001"), c.toString());
    }
}
