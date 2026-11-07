package com.oneofficeautomation.agent;

import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** G50: one session per robot account on a Windows Server; programs replaced by a fake, so nothing is opened. */
class ServerSessionsTest {
    final ServerSessions.Exec real = ServerSessions.exec;
    final ServerSessions.Launch realLaunch = ServerSessions.launch;
    @AfterEach void restore() { ServerSessions.exec = real; ServerSessions.launch = realLaunch; }

    @Test void addressesAndPasswordVariables() {
        assertEquals("127.0.0.2", ServerSessions.address(0));
        assertEquals("127.0.0.11", ServerSessions.address(9));
        assertEquals("OOA_SESSION_PW_ROBOT_1", ServerSessions.passwordVariable("OOA_SESSION_PW_", "CORP\\robot-1"));
    }

    @Test void opensOnlyTheMissingSessionsAndKeepsPasswordsOutOfTheOutput() throws Exception {
        assumeTrue(DesktopSession.WINDOWS);
        List<List<String>> ran = new ArrayList<>();
        ServerSessions.exec = cmd -> {
            ran.add(cmd);
            if (cmd.get(0).equals("query")) return new Object[]{0, " USERNAME   SESSIONNAME   ID  STATE   IDLE TIME  LOGON TIME\n>admin      console        1  Active  none  6/11/2026 08:00\n robot1     rdp-tcp#3      2  Active  .     6/11/2026 08:01\n robot3                    4  Disc    .     6/11/2026 08:02\n"};
            return new Object[]{0, ""};
        };
        ServerSessions.launch = ran::add;
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        // robot2's password variable is not set in the test environment: it is reported, nothing opened for it
        ServerSessions.run(Map.of("users", "robot1,robot2"), new PrintStream(b));
        String said = b.toString();
        assertTrue(said.contains("robot1: signed in"), said);
        assertTrue(said.contains("robot2: no session, and OOA_SESSION_PW_ROBOT2 is not set"), said);
        assertEquals(1, ran.size(), "only query user ran: " + ran);
        // opening a session: the credential for its own address, then the connection, minimised
        ran.clear();
        assertTrue(ServerSessions.open("robot2", "127.0.0.3", "s3cret", 1600, 900).contains("robot2"));
        assertEquals(List.of("cmdkey", "/generic:TERMSRV/127.0.0.3", "/user:robot2", "/pass:s3cret"), ran.get(0));
        assertEquals(List.of("mstsc", "/v:127.0.0.3", "/w:1600", "/h:900"), ran.get(1));
        assertEquals("AGENT.BAD_INPUT", assertThrows(OoaException.class, () -> ServerSessions.open("a b&c", "127.0.0.2", "x", 1, 1)).code());
        // --check never opens anything
        ran.clear(); b.reset();
        ServerSessions.run(Map.of("users", "robot3", "check", ""), new PrintStream(b));
        assertTrue(b.toString().contains("robot3: no session"), "a disconnected session counts as gone");
        assertEquals(1, ran.size());
    }
}
