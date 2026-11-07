package com.oneofficeautomation.agent;

import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * G49: the checks before anything is changed. Turning automatic sign-in on or off changes the computer's Windows sign-in
 * and needs an administrator, so tests never do it; --status only reads.
 */
class AutoLogonTest {
    @Test void accountNames() {
        assertArrayEquals(new String[]{"", "robot1"}, AutoLogon.account("robot1", null));
        assertArrayEquals(new String[]{"CORP", "robot1"}, AutoLogon.account("CORP\\robot1", null));
        assertArrayEquals(new String[]{"corp.local", "robot1"}, AutoLogon.account("robot1", "corp.local"));
        assertEquals("AGENT.BAD_INPUT", assertThrows(OoaException.class, () -> AutoLogon.account("bad|name", null)).code());
        assertEquals("AGENT.BAD_INPUT", assertThrows(OoaException.class, () -> AutoLogon.account(" ", null)).code());
        assertEquals("AGENT.BAD_INPUT", assertThrows(OoaException.class, () -> AutoLogon.account("u", "dom ain")).code());
    }

    @Test void thePasswordComesFromTheEnvironmentNeverTheCommandLine() {
        assumeTrue(DesktopSession.WINDOWS);
        PrintStream out = new PrintStream(new ByteArrayOutputStream());
        OoaException none = assertThrows(OoaException.class, () -> AutoLogon.run(Map.of("user", "robot1"), out));
        assertTrue(none.getMessage().contains("--password-env"), none.getMessage());
        assertEquals("AGENT.BAD_INPUT", assertThrows(OoaException.class, () -> AutoLogon.run(Map.of("user", "robot1", "password-env", "OOA_NO_SUCH_VARIABLE_G49"), out)).code());
    }

    @Test void statusOnlyReads() {
        assumeTrue(DesktopSession.WINDOWS);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        assertEquals(0, AutoLogon.run(Map.of("status", ""), new PrintStream(b)));
        assertTrue(b.toString().startsWith("Automatic sign-in: "), b.toString());
    }
}
