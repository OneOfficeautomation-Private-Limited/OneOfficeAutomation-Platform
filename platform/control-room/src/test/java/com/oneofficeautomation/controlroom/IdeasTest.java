package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** G65: automation ideas suggested and voted by anyone, assessed and scored by the team, with the linked agent's savings. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IdeasTest {
    Db db; ControlRoom cr; ControlRoom.User admin, vic, rita;

    @BeforeAll void start() throws Exception {
        Path data = Files.createTempDirectory("ooa-ideas");
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        admin = RepositoryLifecycleTest.boot(cr);
        cr.createUser(admin, "vic", "Vic Viewer", "viewer", null);
        cr.createUser(admin, "rita", "Rita Runner", "bot_runner", null);
        vic = new ControlRoom.User("vic", "vic", "viewer", "Vic Viewer", false);
        rita = new ControlRoom.User("rita", "rita", "bot_runner", "Rita Runner", false);
    }
    @AfterAll void stop() { cr.triggerPollers.stop(); db.close(); }

    @Test void scoreIsWorkedOutFromTheAssessment() {
        assertNull(Ideas.score(5, 5, null, "low", 1000), "no score until the three marks are given");
        assertEquals(100, Ideas.score(5, 5, 5, "low", 9999));
        assertEquals(1200.0, Ideas.hoursYear(400, 15));
        int easy = Ideas.score(4, 4, 4, "low", 1200), hard = Ideas.score(4, 4, 4, "high", 1200), small = Ideas.score(4, 4, 4, "low", 10);
        assertTrue(easy > hard && easy > small, easy + " " + hard + " " + small);
    }

    @Test @SuppressWarnings("unchecked")
    void anyoneSuggestsAndVotesTheTeamAssesses() {
        Map<String, Object> idea = cr.ideas.save(vic, null, new HashMap<>(Map.of("title", "Post supplier invoices", "department", "Finance",
            "volume_month", 400, "minutes_each", 15, "status", "live", "rules_score", 5)), null);
        String id = (String) idea.get("id");
        assertEquals("submitted", idea.get("status"), "people who do not assess cannot set the status");
        assertNull(idea.get("rules_score"));
        assertEquals(1200L, idea.get("hours_year"));
        assertNull(idea.get("score"));
        assertEquals(true, idea.get("mine"));

        // the person who suggested it may change it while it is submitted; others may not
        cr.ideas.save(vic, id, new HashMap<>(Map.of("title", "Post supplier invoices from the mailbox", "volume_month", 400, "minutes_each", 15)), null);
        assertEquals("CR.FORBIDDEN", assertThrows(OoaException.class, () -> cr.ideas.save(rita, id, new HashMap<>(Map.of("title", "x")), null)).code());
        assertEquals("CR.FORBIDDEN", assertThrows(OoaException.class, () -> cr.ideas.delete(rita, id, null)).code());

        assertEquals(1, cr.ideas.vote(rita, id).get("votes"));
        assertEquals(true, cr.ideas.vote(vic, id).get("voted"));
        assertEquals(1, cr.ideas.vote(vic, id).get("votes"), "voting again takes the vote back");

        Map<String, Object> b = new HashMap<>(Map.of("title", "Post supplier invoices from the mailbox", "volume_month", 400, "minutes_each", 15, "status", "approved",
            "rules_score", 5, "structured_score", 4, "stable_score", 4, "complexity", "medium", "hourly_cost", 40));
        Map<String, Object> assessed = cr.ideas.save(admin, id, b, "127.0.0.1");
        assertEquals("approved", assessed.get("status"));
        assertEquals(Ideas.score(5, 4, 4, "medium", 1200), assessed.get("score"));
        assertEquals(48000L, assessed.get("value_year"));
        assertEquals(1L, db.one("SELECT COUNT(*) AS n FROM audit WHERE action = 'idea.update' AND object_id = ? AND details LIKE '%from_status%'", id).get("n"));

        // once it is past submitted, its author can no longer change it
        assertEquals("CR.FORBIDDEN", assertThrows(OoaException.class, () -> cr.ideas.save(vic, id, new HashMap<>(Map.of("title", "y")), null)).code());
        assertEquals("CR.BAD_INPUT", assertThrows(OoaException.class, () -> cr.ideas.save(admin, id, new HashMap<>(Map.of("title", "y", "status", "done")), null)).code());
        assertEquals("CR.BAD_INPUT", assertThrows(OoaException.class, () -> cr.ideas.save(admin, id, new HashMap<>(Map.of("title", "y", "rules_score", 9)), null)).code());

        Map<String, Object> l = cr.ideas.list(vic);
        assertEquals(false, l.get("can_manage"));
        assertEquals(true, cr.ideas.list(admin).get("can_manage"));
        Map<String, Object> seen = ((List<Map<String, Object>>) l.get("ideas")).stream().filter(i -> id.equals(i.get("id"))).findFirst().orElseThrow();
        assertEquals(false, seen.get("voted"));
        assertFalse(seen.containsKey("voters"), "who voted is not shown");
        cr.ideas.delete(admin, id, null);
        assertEquals("CR.NOT_FOUND", assertThrows(OoaException.class, () -> cr.ideas.vote(vic, id)).code());
    }

    @Test void aLinkedAgentShowsTheHoursItSaved() {
        cr.checkin(admin, "Ideas/Post.flow.json", LicensingAndChecksTest.agent("post"), "v1", true, null);
        Map<String, Object> idea = cr.ideas.save(admin, null, new HashMap<>(Map.of("title", "Post", "volume_month", 100, "minutes_each", 12, "bot_path", "Ideas/Post.flow.json")), null);
        for (int i = 0; i < 5; i++)
            db.update("INSERT INTO runs (id, bot_path, version, device_id, status, requested_by, source, created, cancel_requested, pause_requested) VALUES (?, ?, 1, 'd', 'success', 'admin', 'manual', ?, FALSE, FALSE)",
                "run_idea_" + i, "Ideas/Post.flow.json", ControlRoom.now());
        Map<String, Object> again = cr.ideas.get(admin, (String) idea.get("id"));
        assertEquals(5L, again.get("runs_30_days"));
        assertEquals(1.0, again.get("hours_saved_30_days"), "5 runs of 12 minutes");
    }
}
