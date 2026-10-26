package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Two Command Center nodes on one database: one leader at a time, takeover, and sign-ins that finish on the other node. */
class ClusterTest {
    Db db; ControlRoom a, b; ControlRoom.User admin;

    @BeforeEach void start() throws Exception {
        Path data = Files.createTempDirectory("ooa-cluster");
        db = Db.embedded(data);
        a = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        admin = RepositoryLifecycleTest.boot(a);
        b = new ControlRoom(db, data, FirstPartyPackages.registry(), null);   // the second node, same database
    }
    @AfterEach void stop() { a.triggerPollers.stop(); b.triggerPollers.stop(); db.close(); }

    @Test void oneLeaderAtATimeAndTakeover() {
        assertNotEquals(a.cluster.nodeId, b.cluster.nodeId);
        assertTrue(a.cluster.tick(), "the first node takes the lease");
        assertFalse(b.cluster.tick(), "the second does not while it is held");
        assertTrue(a.cluster.tick(), "the leader renews it");
        assertEquals(2, a.cluster.nodes().size());
        assertEquals(1, a.cluster.nodes().stream().filter(n -> Boolean.TRUE.equals(n.get("leader"))).count());

        // the leader stops renewing: after the lease runs out the other node leads
        b.cluster.clock = Clock.offset(Clock.systemUTC(), Duration.ofSeconds(Cluster.LEASE_SECONDS + 5));
        assertTrue(b.cluster.tick(), "takeover once the lease ran out");
        assertFalse(a.cluster.tick(), "the old leader learns it no longer leads");

        // a clean stop hands over at once
        b.cluster.release();
        assertTrue(a.cluster.tick());
    }

    @Test void signInsInProgressAreSharedByTheNodes() {
        assertTrue(a.cluster.put("x", "t1", "data", Duration.ofMinutes(1)));
        assertFalse(b.cluster.put("x", "t1", "other", Duration.ofMinutes(1)), "a token is used once");
        assertEquals("data", b.cluster.take("x", "t1"));
        assertNull(a.cluster.take("x", "t1"), "and taken once");
        assertTrue(a.cluster.put("x", "t2", "old", Duration.ofSeconds(-1)));
        assertNull(b.cluster.take("x", "t2"), "expired values are not handed out");

        // a two-step sign-in: password on node a, code on node b
        String t = (String) a.login("admin", "Admin-Passw0rd-2026", null).get("token");
        ControlRoom.User u = a.session(t);
        String secret = ((String) a.mfa.setup(u, null).get("secret")).replace(" ", "");
        a.mfa.enable(u, Mfa.totp(Mfa.base32Decode(secret), Instant.now().getEpochSecond() / 30), null);
        String challenge = (String) a.login("admin", "Admin-Passw0rd-2026", null).get("mfa_token");
        assertEquals("CR.MFA_WRONG_CODE", assertThrows(OoaException.class, () -> b.loginMfa(challenge, "000000", null)).code());
        String code = Mfa.totp(Mfa.base32Decode(secret), Instant.now().getEpochSecond() / 30 + 1);
        assertNotNull(b.loginMfa(challenge, code, null).get("token"), "finished on the other node");
    }

    @Test void repositoryFilesCanLiveInASharedFolder() throws Exception {
        Path shared = Files.createTempDirectory("ooa-shared-files");
        a.platform.updateSettings(admin, Map.of("repo_files_folder", shared.toString()), null);
        a.repoFiles.put(admin, "Fin/rates.csv", "eur,1.1".getBytes(), "text/csv", null);
        try (var s = Files.list(shared)) { assertEquals(1, s.count(), "the content went to the shared folder"); }
        Thread.sleep(5200);   // node b's settings cache refreshes within 5 seconds
        assertEquals("eur,1.1", new String((byte[]) b.repoFiles.get(admin, "Fin/rates.csv").get("content")), "the other node reads it");
    }
}
