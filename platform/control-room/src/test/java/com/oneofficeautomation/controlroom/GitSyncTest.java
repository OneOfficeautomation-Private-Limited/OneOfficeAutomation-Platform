package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Git sync: the repository mirrored to a Git remote (a bare repository here), commits only on change, Git edits overwritten. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GitSyncTest {
    Db db; ControlRoom cr; ControlRoom.User admin; Path remote, tmp;

    @BeforeAll void start() throws Exception {
        tmp = Files.createTempDirectory("ooa-git");
        Path data = Files.createDirectories(tmp.resolve("data"));
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        admin = RepositoryLifecycleTest.boot(cr);
        remote = tmp.resolve("remote.git");
        Git.init().setBare(true).setDirectory(remote.toFile()).setInitialBranch("main").call().close();
        cr.platform.updateSettings(admin, Map.of("git_remote_url", remote.toUri().toString(), "git_branch", "main"), null);
    }
    @AfterAll void stop() { cr.triggerPollers.stop(); db.close(); }

    /** A fresh clone of the remote, to see what is there. */
    Path cloneRemote(String name) throws Exception {
        Path dir = tmp.resolve(name);
        Git.cloneRepository().setURI(remote.toUri().toString()).setDirectory(dir.toFile()).setBranch("main").call().close();
        return dir;
    }

    RevCommit head(Path clone) throws Exception { try (Git g = Git.open(clone.toFile())) { return g.log().setMaxCount(1).call().iterator().next(); } }

    @Test @SuppressWarnings("unchecked")
    void theRepositoryIsMirroredToGit() throws Exception {
        assertEquals("CR.BAD_INPUT", assertThrows(OoaException.class, () -> cr.platform.updateSettings(admin, Map.of("git_remote_url", "ftp://x"), null)).code());
        assertEquals("CR.BAD_INPUT", assertThrows(OoaException.class, () -> cr.platform.updateSettings(admin, Map.of("git_token_ref", "just-a-token"), null)).code(), "the token is a vault reference, never the token");

        cr.checkin(admin, "Finance/Invoices.flow.json", LicensingAndChecksTest.agent("invoices"), "first", true, null);
        cr.checkin(admin, "HR/Onboard.flow.json", LicensingAndChecksTest.agent("onboard"), "first", true, null);
        Map<String, Object> first = cr.gitSync.syncNow(admin, null);
        assertEquals("ok", first.get("state"));
        assertEquals(2, first.get("agents"));
        assertEquals(true, first.get("changed"));

        Path c1 = cloneRemote("c1");
        assertTrue(Files.exists(c1.resolve("agents/Finance/Invoices.flow.json")));
        String flow = Files.readString(c1.resolve("agents/Finance/Invoices.flow.json"));
        assertTrue(flow.contains("\n  \"id\": \"invoices\""), "indented, one value per line: " + flow);
        Map<String, Object> index = (Map<String, Object>) JsonLite.parse(Files.readString(c1.resolve("ooa-repository.json")));
        List<Map<String, Object>> agents = (List<Map<String, Object>>) index.get("agents");
        assertEquals(List.of("Finance/Invoices.flow.json", "HR/Onboard.flow.json"), agents.stream().map(a -> a.get("path")).toList());
        assertEquals(1, ((Number) agents.get(0).get("production_version")).intValue());
        assertTrue(Files.exists(c1.resolve("README.md")));

        // nothing changed: no new commit
        assertEquals(false, cr.gitSync.syncNow(admin, null).get("changed"));

        // automatic push after a check-in, with its comment and author
        cr.platform.updateSettings(admin, Map.of("git_auto_push", 1), null);
        cr.checkout(admin, "Finance/Invoices.flow.json", null);
        Map<String, Object> v2 = LicensingAndChecksTest.agent("invoices");
        v2.put("description", "now with VAT");
        cr.checkin(admin, "Finance/Invoices.flow.json", v2, "adds VAT", false, null);
        RevCommit auto = null;
        for (int i = 0; i < 100; i++) {
            Path c = cloneRemote("auto" + i);
            RevCommit h = head(c);
            if (h.getFullMessage().contains("adds VAT")) { auto = h; assertTrue(Files.readString(c.resolve("agents/Finance/Invoices.flow.json")).contains("now with VAT")); break; }
            Thread.sleep(100);
        }
        assertNotNull(auto, "the check-in was pushed by itself");
        assertEquals("Finance/Invoices.flow.json v2: adds VAT", auto.getShortMessage());
        assertTrue(auto.getAuthorIdent().getName().startsWith("admin"));

        // a deleted agent disappears; a change made in Git is overwritten by the next sync
        cr.platform.updateSettings(admin, Map.of("git_auto_push", 0), null);
        cr.deleteBot(admin, "HR/Onboard.flow.json", null);
        Path c2 = cloneRemote("c2");
        Files.writeString(c2.resolve("agents/Finance/Invoices.flow.json"), "{\"hand\": \"edited\"}");
        try (Git g = Git.open(c2.toFile())) { g.commit().setAll(true).setMessage("edited in Git").call(); g.push().call(); }
        assertEquals(true, cr.gitSync.syncNow(admin, null).get("changed"));
        Path c3 = cloneRemote("c3");
        assertFalse(Files.exists(c3.resolve("agents/HR/Onboard.flow.json")));
        assertTrue(Files.readString(c3.resolve("agents/Finance/Invoices.flow.json")).contains("now with VAT"), "the Command Center is the source of truth");
        try (Git g = Git.open(c3.toFile())) {
            List<String> messages = new ArrayList<>();
            for (RevCommit c : g.log().call()) messages.add(c.getShortMessage());
            assertTrue(messages.contains("edited in Git"), "earlier history stays: " + messages);
        }
        assertEquals("ok", cr.gitSync.status(admin).get("state"));
    }

    @Test void syncNeedsTheSettingsRightAndAReachableRemote() throws Exception {
        assertEquals("CR.FORBIDDEN", assertThrows(OoaException.class, () -> cr.gitSync.syncNow(RepositoryLifecycleTest.person(cr, admin, "vic", "viewer"), null)).code());
        cr.platform.updateSettings(admin, Map.of("git_remote_url", tmp.resolve("missing.git").toUri().toString()), null);
        try {
            assertEquals("CR.GIT", assertThrows(OoaException.class, () -> cr.gitSync.syncNow(admin, null)).code());
            assertEquals("failed", cr.gitSync.status(admin).get("state"));
        } finally {
            cr.platform.updateSettings(admin, Map.of("git_remote_url", remote.toUri().toString()), null);
        }
    }
}
