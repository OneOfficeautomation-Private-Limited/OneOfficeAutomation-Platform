package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.modules.integration.GitSync;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** G70: Git as the source of truth: agents changed in Git are read back as versions; conflicts are left alone. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GitPullTest {
    Db db; ControlRoom cr; ControlRoom.User admin; Path remote, tmp;

    @BeforeAll void start() throws Exception {
        tmp = Files.createTempDirectory("ooa-gitpull");
        Path data = Files.createDirectories(tmp.resolve("data"));
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        admin = RepositoryLifecycleTest.boot(cr);
        remote = tmp.resolve("remote.git");
        Git.init().setBare(true).setDirectory(remote.toFile()).setInitialBranch("main").call().close();
        cr.platform.updateSettings(admin, Map.of("git_remote_url", remote.toUri().toString(), "git_branch", "main"), null);
    }
    @AfterAll void stop() { cr.triggerPollers.stop(); db.close(); }

    /** A developer's change in Git: edit files in a clone and push. */
    void developer(String name, String message, Map<String, String> files) throws Exception {
        Path dir = tmp.resolve(name);
        try (Git g = Git.cloneRepository().setURI(remote.toUri().toString()).setDirectory(dir.toFile()).setBranch("main").call()) {
            for (var e : files.entrySet()) {
                Path f = dir.resolve("agents").resolve(e.getKey());
                Files.createDirectories(f.getParent());
                Files.writeString(f, e.getValue(), StandardCharsets.UTF_8);
            }
            g.add().addFilepattern(".").call();
            g.commit().setMessage(message).setAuthor(new PersonIdent("Dev Asha", "asha@example.com")).call();
            g.push().call();
        }
    }

    @SuppressWarnings("unchecked")
    static String withDescription(String id, String description) {
        Map<String, Object> a = new LinkedHashMap<>(LicensingAndChecksTest.agent(id));
        a.put("description", description);
        return GitSync.pretty(a) + "\n";
    }

    @Test @SuppressWarnings("unchecked")
    void changesMadeInGitBecomeVersionsAndConflictsAreLeftAlone() throws Exception {
        cr.checkin(admin, "Finance/Post.flow.json", LicensingAndChecksTest.agent("post"), "v1", true, null);
        cr.checkin(admin, "Finance/Pay.flow.json", LicensingAndChecksTest.agent("pay"), "v1", true, null);
        assertEquals("CR.GIT", assertThrows(OoaException.class, () -> cr.modules.service(GitSync.class).pullNow(admin, null)).code(), "nothing in Git yet");
        cr.modules.service(GitSync.class).syncNow(admin, null);

        Map<String, Object> nothing = cr.modules.service(GitSync.class).pullNow(admin, null);
        assertEquals(List.of(), nothing.get("imported"), "the same as One CommandCenter");

        developer("dev1", "Explain the posting rule", Map.of("Finance/Post.flow.json", withDescription("post", "Posts invoices over 1,000 for review"),
            "Finance/Refund.flow.json", withDescription("refund", "New in Git"), "Finance/Broken.flow.json", "{ not json"));
        Map<String, Object> r = cr.modules.service(GitSync.class).pullNow(admin, "127.0.0.1");
        List<Map<String, Object>> imported = (List<Map<String, Object>>) r.get("imported");
        assertEquals(Set.of("Finance/Post.flow.json", "Finance/Refund.flow.json"), new HashSet<>(imported.stream().map(i -> (String) i.get("path")).toList()));
        Map<String, Object> v = db.one("SELECT version, comment, author FROM bot_versions WHERE path = 'Finance/Post.flow.json' ORDER BY version DESC LIMIT 1");
        assertEquals(2, ((Number) v.get("version")).intValue());
        assertTrue(String.valueOf(v.get("comment")).matches("From Git [0-9a-f]{8} by Dev Asha: Explain the posting rule"), String.valueOf(v.get("comment")));
        assertEquals("Posts invoices over 1,000 for review", ((Map<String, Object>) cr.content("Finance/Post.flow.json", "latest").get("flow")).get("description"));
        assertEquals(1, ((Number) db.one("SELECT production_version FROM bots WHERE path = 'Finance/Post.flow.json'").get("production_version")).intValue(), "production stays as it was");
        assertEquals(1, ((List<?>) r.get("problems")).size(), "the file that is not JSON is reported");
        assertNotNull(r.get("synced"), "the branch is synced again");

        // changed in both places since the last sync: left alone
        cr.checkout(admin, "Finance/Pay.flow.json", null);
        Map<String, Object> here = new LinkedHashMap<>(LicensingAndChecksTest.agent("pay"));
        here.put("description", "changed in One CommandCenter");
        cr.checkin(admin, "Finance/Pay.flow.json", here, "v2 here", true, null);
        developer("dev2", "Change pay", Map.of("Finance/Pay.flow.json", withDescription("pay", "changed in Git")));
        Map<String, Object> c = cr.modules.service(GitSync.class).pullNow(admin, null);
        assertEquals(List.of("Finance/Pay.flow.json"), ((List<Map<String, Object>>) c.get("conflicts")).stream().map(x -> x.get("path")).toList());
        assertEquals(2, ((Number) db.one("SELECT latest_version FROM bots WHERE path = 'Finance/Pay.flow.json'").get("latest_version")).intValue());

        // deleted in Git: only reported
        cr.modules.service(GitSync.class).syncNow(admin, null);
        Path dir = tmp.resolve("dev3");
        try (Git g = Git.cloneRepository().setURI(remote.toUri().toString()).setDirectory(dir.toFile()).setBranch("main").call()) {
            g.rm().addFilepattern("agents/Finance/Refund.flow.json").call();
            g.commit().setMessage("drop refund").setAuthor(new PersonIdent("Dev Asha", "asha@example.com")).call();
            g.push().call();
        }
        Map<String, Object> d = cr.modules.service(GitSync.class).pullNow(admin, null);
        assertEquals(List.of("Finance/Refund.flow.json"), d.get("deleted_in_git"));
        assertNotNull(db.one("SELECT path FROM bots WHERE path = 'Finance/Refund.flow.json' AND deleted_at IS NULL"));
        assertEquals("CR.FORBIDDEN", assertThrows(OoaException.class, () -> cr.modules.service(GitSync.class).pullNow(new ControlRoom.User("v", "vic", "viewer", "V", false), null)).code());
    }
}
