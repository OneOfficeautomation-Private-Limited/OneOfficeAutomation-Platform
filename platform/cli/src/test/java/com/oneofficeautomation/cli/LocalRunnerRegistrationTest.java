package com.oneofficeautomation.cli;

import com.oneofficeautomation.controlroom.ControlRoom;
import com.oneofficeautomation.controlroom.ControlRoomServer;
import com.oneofficeautomation.controlroom.Db;
import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.JsonLite;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.nio.file.*;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * "ooa server" registers this computer's runner once per Command Center. Two Command Centers on one computer (a test
 * install next to the real one) used to share one registration file and kept making each other register again: the
 * real one ended up with several runners of the same name, most of them offline, and runs sent to those waited forever.
 */
class LocalRunnerRegistrationTest {

    static int freePort() throws Exception { try (ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); } }

    @Test @SuppressWarnings("unchecked")
    void eachCommandCenterKeepsItsOwnRunner() throws Exception {
        Path home = Files.createTempDirectory("ooa-home"), dataA = Files.createTempDirectory("ooa-a"), dataB = Files.createTempDirectory("ooa-b");
        String realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        Db dbA = Db.embedded(dataA), dbB = Db.embedded(dataB);
        try {
            ControlRoom a = new ControlRoom(dbA, dataA, FirstPartyPackages.registry(), null), b = new ControlRoom(dbB, dataB, FirstPartyPackages.registry(), null);
            Path cfgA = Ooa.localAgentConfig(dataA), cfgB = Ooa.localAgentConfig(dataB);
            assertNotEquals(cfgA, cfgB, "a registration file per data folder");
            assertEquals(home.resolve(".ooa-agent"), cfgA.getParent());

            ControlRoomServer sa = new ControlRoomServer(a, "127.0.0.1", freePort()), sb = new ControlRoomServer(b, "127.0.0.1", freePort());
            sa.start(); sb.start();
            String urlA = "http://127.0.0.1:" + sa.port(), urlB = "http://127.0.0.1:" + sb.port();
            Ooa.startLocalAgent(a, urlA, cfgA, home.resolve("work")).stop();
            Ooa.startLocalAgent(b, urlB, cfgB, home.resolve("work")).stop();
            // the two take turns, as a test server and the real one do
            Ooa.startLocalAgent(a, urlA, cfgA, home.resolve("work")).stop();
            Ooa.startLocalAgent(b, urlB, cfgB, home.resolve("work")).stop();
            assertEquals(1, dbA.query("SELECT id FROM devices").size(), "A still has one runner");
            assertEquals(1, dbB.query("SELECT id FROM devices").size(), "B still has one runner");

            // A again on another port: the same runner, its address updated in the file
            sa.stop();
            ControlRoomServer sa2 = new ControlRoomServer(a, "127.0.0.1", freePort());
            sa2.start();
            String urlA2 = "http://127.0.0.1:" + sa2.port();
            Ooa.startLocalAgent(a, urlA2, cfgA, home.resolve("work")).stop();
            assertEquals(1, dbA.query("SELECT id FROM devices").size(), "a new port is no new runner");
            assertEquals(urlA2, ((Map<String, Object>) JsonLite.parse(Files.readString(cfgA))).get("url"));

            // the shared file of earlier versions is taken over by the Command Center it belongs to, not registered again
            Path legacy = cfgA.resolveSibling("local-agent.json");
            Files.move(cfgA, legacy);
            Ooa.startLocalAgent(a, urlA2, cfgA, home.resolve("work")).stop();
            assertTrue(Files.exists(cfgA));
            assertEquals(1, dbA.query("SELECT id FROM devices").size(), "the earlier registration is kept");
            // ...but not by another one, which registers its own
            Files.copy(cfgA, legacy, StandardCopyOption.REPLACE_EXISTING);
            Files.delete(cfgB);
            Ooa.startLocalAgent(b, urlB, cfgB, home.resolve("work")).stop();
            assertEquals(2, dbB.query("SELECT id FROM devices").size(), "B's own file was gone: B registers again, A's file is not B's");
            sa2.stop(); sb.stop();
        } finally {
            System.setProperty("user.home", realHome);
            dbA.close(); dbB.close();
        }
    }
}
