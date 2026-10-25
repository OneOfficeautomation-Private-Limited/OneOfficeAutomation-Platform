package com.oneofficeautomation.packages;

import com.oneofficeautomation.packages.ConnectorsTest.Call;
import com.oneofficeautomation.packages.ConnectorsTest.Fake;
import com.oneofficeautomation.sdk.ActionContext;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static com.oneofficeautomation.packages.ConnectorsTest.ok;
import static com.oneofficeautomation.packages.ConnectorsTest.q;
import static com.oneofficeautomation.packages.PackagesTest.*;
import static com.oneofficeautomation.packages.ToolkitTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** New options of the GitHub toolkit: listing another user's repositories. */
class GitHubMoreOptionsTest {
    final GitHubPackage gh = new GitHubPackage();
    final List<String> log = new ArrayList<>();

    @Test void repositoriesOfAnotherUser() throws Exception {
        try (Fake f = new Fake()) {
            String b = f.url();
            f.on("GET /users/octo-cat/repos", c -> ok("[{\"name\":\"tools\",\"full_name\":\"octo-cat/tools\",\"private\":false,\"html_url\":\"https://gh/octo-cat/tools\"}]"))
             .on("GET /orgs/acme/repos", c -> ok("[{\"name\":\"a\"}]"));
            ActionContext ctx = ctx(tmp(), log);
            Map<String, Object> r = gh.listRepositories(ctx, b, TOKEN, "octo-cat", 50, "user");
            assertEquals(List.of("tools"), col(r, 0));
            assertEquals(new BigDecimal(1), r.get("count"));
            Call c = f.last("GET", "/users/octo-cat/repos");
            assertEquals("Bearer " + SECRET, c.auth());
            assertTrue(q(c).contains("sort=updated") && q(c).contains("per_page=50"), q(c));
            assertNull(f.last("GET", "/orgs/.*"), "a user is not looked up as an organization");

            assertEquals(List.of("a"), col(gh.listRepositories(ctx, b, TOKEN, "acme", 50, "organization"), 0));
            assertEquals(List.of("a"), col(gh.listRepositories(ctx, b, TOKEN, "acme", 50), 0), "the earlier call shape lists an organization");
            assertEquals(List.of("a"), col(gh.listRepositories(ctx, b, TOKEN, "acme", 50, null), 0), "empty owner_type means organization");

            int before = f.calls.size();
            fails("GITHUB.BAD_OWNER_TYPE", () -> gh.listRepositories(ctx, b, TOKEN, "acme", 50, "team"));
            fails("GITHUB.BAD_NAME", () -> gh.listRepositories(ctx, b, TOKEN, "octo/../cat", 50, "user"));
            assertEquals(before, f.calls.size(), "bad input is refused before any request");
            noSecretInLog(log);
        }
    }
}
