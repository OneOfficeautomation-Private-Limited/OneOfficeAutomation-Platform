package com.oneofficeautomation.packages;

import com.oneofficeautomation.packages.ConnectorsTest.Call;
import com.oneofficeautomation.packages.ConnectorsTest.Fake;
import com.oneofficeautomation.runner.RunResult;
import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static com.oneofficeautomation.packages.ConnectorsTest.ok;
import static com.oneofficeautomation.packages.ConnectorsTest.q;
import static com.oneofficeautomation.packages.PackagesTest.*;
import static com.oneofficeautomation.packages.ToolkitTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** GitHub toolkit against a local server that imitates the GitHub REST API. */
class GitHubPackageTest {
    final GitHubPackage gh = new GitHubPackage();
    final List<String> log = new ArrayList<>();

    @Test void catalog() { checkCatalog("ooa.github", 14); }

    @Test void repositoriesFollowLinkPagingOnSameHostOnly() throws Exception {
        try (Fake f = new Fake()) {
            String b = f.url();
            f.on("GET /orgs/acme/repos", c -> q(c).contains("&page=2") ? ok("[{\"name\":\"c\",\"full_name\":\"acme/c\",\"private\":false}]")
                    : new Object[]{200, "[{\"name\":\"a\",\"full_name\":\"acme/a\",\"private\":true,\"default_branch\":\"main\",\"html_url\":\"https://gh/acme/a\"},{\"name\":\"b\"}]",
                    Map.of("Link", "<" + b + "/orgs/acme/repos?sort=updated&per_page=100&page=2>; rel=\"next\", <" + b + "/x?page=9>; rel=\"last\"")})
             .on("GET /user/repos", c -> new Object[]{200, "[{\"name\":\"mine\"}]", Map.of("Link", "<http://evil.example/user/repos?page=2>; rel=\"next\"")})
             .on("GET /repos/acme/a", c -> ok("{\"name\":\"a\",\"full_name\":\"acme/a\",\"default_branch\":\"main\",\"open_issues_count\":3,\"private\":true}"));
            ActionContext ctx = ctx(tmp(), log);
            Map<String, Object> r = gh.listRepositories(ctx, b, TOKEN, "acme", 100);
            assertEquals(List.of("a", "b", "c"), col(r, 0));
            assertEquals(new BigDecimal(3), r.get("count"));
            assertEquals(List.of("name", "full_name", "private", "default_branch", "description", "updated_at", "url"), columns(r));
            Call first = f.calls.get(0);
            assertEquals("Bearer " + SECRET, first.auth());
            assertEquals("2022-11-28", first.header("X-GitHub-Api-Version"));
            assertEquals("application/vnd.github+json", first.header("Accept"));
            assertTrue(q(first).contains("sort=updated") && q(first).contains("per_page=100"));
            assertEquals(1, gh.listRepositories(ctx, b, TOKEN, null, 100).get("count") instanceof BigDecimal n ? n.intValue() : -1, "foreign next link ignored");
            assertEquals(1, f.calls.stream().filter(c -> c.path().equals("/user/repos")).count());
            Map<String, Object> repo = gh.getRepository(ctx, b + "/", TOKEN, "acme", "a");
            assertEquals("main", repo.get("default_branch"));
            assertEquals(new BigDecimal(3), ((Map<?, ?>) repo.get("repository")).get("open_issues"));
            assertEquals(2, gh.listRepositories(ctx, b, TOKEN, "acme", 2).get("count") instanceof BigDecimal n ? n.intValue() : -1, "max_results stops paging");
            noSecretInLog(log);
        }
    }

    @Test void issuesCreateGetUpdateCloseCommentAndList() throws Exception {
        try (Fake f = new Fake()) {
            String b = f.url();
            f.on("POST /repos/acme/app/issues", c -> new Object[]{201, "{\"number\":42,\"html_url\":\"https://gh/acme/app/issues/42\"}"})
             .on("GET /repos/acme/app/issues/42", c -> ok("{\"number\":42,\"title\":\"Disk full\",\"state\":\"open\",\"user\":{\"login\":\"ann\"},"
                    + "\"labels\":[{\"name\":\"bug\"}],\"assignees\":[{\"login\":\"bob\"}],\"milestone\":{\"title\":\"Q4\"},\"comments\":2,\"html_url\":\"u42\"}"))
             .on("PATCH /repos/acme/app/issues/42", c -> ok("{}"))
             .on("POST /repos/acme/app/issues/42/comments", c -> new Object[]{201, "{\"id\":991,\"html_url\":\"c991\"}"})
             .on("GET /repos/acme/app/issues", c -> ok("[{\"number\":1,\"title\":\"A\",\"state\":\"open\",\"user\":{\"login\":\"ann\"}},"
                    + "{\"number\":2,\"title\":\"PR\",\"pull_request\":{}},{\"number\":3,\"title\":\"C\",\"state\":\"open\"}]"));
            ActionContext ctx = ctx(tmp(), log);
            Map<String, Object> created = gh.createIssue(ctx, b, TOKEN, "acme", "app", "Disk full", "Server **x**", "bug, ops", List.of("bob"), 7);
            assertEquals(new BigDecimal(42), created.get("number"));
            assertEquals("https://gh/acme/app/issues/42", created.get("url"));
            Call c = f.last("POST", "/repos/acme/app/issues");
            assertEquals("Disk full", json(c, "title"));
            assertEquals(List.of("bug", "ops"), json(c, "labels"));
            assertEquals(List.of("bob"), json(c, "assignees"));
            assertEquals(new BigDecimal(7), json(c, "milestone"));
            assertEquals("application/json", c.contentType());

            Map<String, Object> got = gh.getIssue(ctx, b, TOKEN, "acme", "app", 42);
            Map<?, ?> issue = (Map<?, ?>) got.get("issue");
            assertEquals("open", got.get("state"));
            assertEquals("ann", issue.get("author"));
            assertEquals(List.of("bug"), issue.get("labels"));
            assertEquals(List.of("bob"), issue.get("assignees"));
            assertEquals("Q4", issue.get("milestone"));
            assertEquals(false, issue.get("is_pull_request"));

            gh.updateIssue(ctx, b, TOKEN, "acme", "app", 42, "New title", null, "open", null, null);
            assertEquals(Map.of("title", "New title", "state", "open"), json(f.last("PATCH", "/repos/acme/app/issues/42")));
            fails("GITHUB.NOTHING_TO_UPDATE", () -> gh.updateIssue(ctx, b, TOKEN, "acme", "app", 42, null, null, null, null, null));

            gh.closeIssue(ctx, b, TOKEN, "acme", "app", 42, "not_planned");
            assertEquals(Map.of("state", "closed", "state_reason", "not_planned"), json(f.last("PATCH", "/repos/acme/app/issues/42")));

            Map<String, Object> cm = gh.addIssueComment(ctx, b, TOKEN, "acme", "app", 42, "Done by the agent");
            assertEquals(new BigDecimal(991), cm.get("comment_id"));
            assertEquals(Map.of("body", "Done by the agent"), json(f.last("POST", "/repos/acme/app/issues/42/comments")));

            Map<String, Object> list = gh.listIssues(ctx, b, TOKEN, "acme", "app", "all", "bug", "ann", "2026-10-01T00:00:00Z", 50);
            assertEquals(List.of(new BigDecimal(1), new BigDecimal(3)), col(list, 0), "pull requests are left out");
            String lq = q(f.last("GET", "/repos/acme/app/issues"));
            assertTrue(lq.contains("state=all") && lq.contains("labels=bug") && lq.contains("assignee=ann") && lq.contains("since=2026-10-01T00:00:00Z") && lq.contains("per_page=50"), lq);
            noSecretInLog(log);
        }
    }

    @Test void pullRequestsListGetCreateMergeAndCondition() throws Exception {
        try (Fake f = new Fake()) {
            String b = f.url();
            f.on("GET /repos/acme/app/pulls", c -> ok("[{\"number\":5,\"title\":\"Rates\",\"state\":\"open\",\"draft\":false,\"user\":{\"login\":\"ann\"},\"head\":{\"ref\":\"feat\"},\"base\":{\"ref\":\"main\"}}]"))
             .on("GET /repos/acme/app/pulls/5", c -> ok("{\"number\":5,\"title\":\"Rates\",\"state\":\"open\",\"merged\":false,\"mergeable\":true,\"head\":{\"ref\":\"feat\",\"sha\":\"abc\"},\"base\":{\"ref\":\"main\"}}"))
             .on("POST /repos/acme/app/pulls", c -> new Object[]{201, "{\"number\":6,\"html_url\":\"pr6\"}"})
             .on("PUT /repos/acme/app/pulls/5/merge", c -> ok("{\"sha\":\"m1\",\"merged\":true}"))
             .on("PUT /repos/acme/app/pulls/7/merge", c -> new Object[]{405, "{\"message\":\"Pull Request is not mergeable\"}"})
             .on("GET /repos/acme/app/pulls/5/merge", c -> new Object[]{204, ""})
             .on("GET /repos/acme/app/pulls/6/merge", c -> new Object[]{404, "{\"message\":\"Not Found\"}"});
            ActionContext ctx = ctx(tmp(), log);
            Map<String, Object> list = gh.listPullRequests(ctx, b, TOKEN, "acme", "app", "open", "main", 100);
            assertEquals(Arrays.asList(new BigDecimal(5), "Rates", "open", false, "ann", "feat", "main", null, null), rows(list).get(0));
            assertTrue(q(f.last("GET", "/repos/acme/app/pulls")).contains("base=main"));
            Map<String, Object> pr = gh.getPullRequest(ctx, b, TOKEN, "acme", "app", 5);
            assertEquals(false, pr.get("merged"));
            assertEquals("abc", ((Map<?, ?>) pr.get("pull_request")).get("head_sha"));
            Map<String, Object> created = gh.createPullRequest(ctx, b, TOKEN, "acme", "app", "New rates", "feat", "main", "Body", true);
            assertEquals(new BigDecimal(6), created.get("number"));
            Map<String, Object> body = json(f.last("POST", "/repos/acme/app/pulls"));
            assertEquals("feat", body.get("head"));
            assertEquals("main", body.get("base"));
            assertEquals(true, body.get("draft"));
            Map<String, Object> merged = gh.mergePullRequest(ctx, b, TOKEN, "acme", "app", 5, "squash", "Rates (#5)", null, "abc");
            assertEquals("m1", merged.get("sha"));
            assertEquals(true, merged.get("merged"));
            Map<String, Object> mb = json(f.last("PUT", "/repos/acme/app/pulls/5/merge"));
            assertEquals("squash", mb.get("merge_method"));
            assertEquals("abc", mb.get("sha"));
            assertFalse(mb.containsKey("commit_message"));
            ActionException e = fails("GITHUB.NOT_MERGEABLE", () -> gh.mergePullRequest(ctx, b, TOKEN, "acme", "app", 7, "merge", null, null, null));
            assertTrue(e.getMessage().contains("not mergeable"));
            assertTrue(gh.isMerged(ctx, b, TOKEN, "acme", "app", 5));
            assertFalse(gh.isMerged(ctx, b, TOKEN, "acme", "app", 6));
        }
    }

    @Test void branchesAndFiles() throws Exception {
        try (Fake f = new Fake()) {
            String b = f.url();
            String content = Base64.getMimeEncoder().encodeToString("{\"vat\": 19}".getBytes(StandardCharsets.UTF_8));
            f.on("GET /repos/acme/app/branches", c -> ok("[{\"name\":\"main\",\"commit\":{\"sha\":\"s1\"},\"protected\":true}]"))
             .on("GET /repos/acme/app", c -> ok("{\"default_branch\":\"main\"}"))
             .on("GET /repos/acme/app/git/ref/heads/main", c -> ok("{\"object\":{\"sha\":\"s1\"}}"))
             .on("POST /repos/acme/app/git/refs", c -> new Object[]{201, "{}"})
             .on("GET /repos/acme/app/contents/config/rates.json", c -> ok("{\"type\":\"file\",\"encoding\":\"base64\",\"sha\":\"f1\",\"content\":\"" + content.replace("\r\n", "\\n") + "\"}"))
             .on("GET /repos/acme/app/contents/config", c -> ok("[{\"name\":\"rates.json\"}]"))
             .on("GET /repos/acme/app/contents/big.bin", c -> c.header("Accept").equals("application/vnd.github.raw")
                    ? new Object[]{200, new byte[]{1, 2, 3}} : ok("{\"type\":\"file\",\"encoding\":\"none\",\"sha\":\"f2\",\"content\":\"\"}"))
             .on("GET /repos/acme/app/contents/reports/new%20file.csv", c -> new Object[]{404, "{\"message\":\"Not Found\"}"})
             .on("PUT /repos/acme/app/contents/.*", c -> new Object[]{201, "{\"commit\":{\"sha\":\"c9\"},\"content\":{\"sha\":\"f9\"}}"});
            Path w = tmp();
            ActionContext ctx = ctx(w, log);
            Map<String, Object> br = gh.listBranches(ctx, b, TOKEN, "acme", "app", 100);
            assertEquals(List.of(List.of("main", "s1", true)), rows(br));
            assertEquals("s1", gh.createBranch(ctx, b, TOKEN, "acme", "app", "agent/rates", null).get("sha"));
            assertEquals(Map.of("ref", "refs/heads/agent/rates", "sha", "s1"), json(f.last("POST", "/repos/acme/app/git/refs")));

            Map<String, Object> file = gh.getFile(ctx, b, TOKEN, "acme", "app", "config/rates.json", "release/1", null);
            assertEquals("{\"vat\": 19}", file.get("text"));
            assertEquals("f1", file.get("sha"));
            assertEquals("ref=release/1", q(f.last("GET", "/repos/acme/app/contents/config/rates.json")));
            Map<String, Object> big = gh.getFile(ctx, b, TOKEN, "acme", "app", "big.bin", null, "dl/big.bin");
            assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(w.resolve("dl/big.bin")), "large files fetched raw and saved");
            assertEquals(new BigDecimal(3), big.get("size"));
            fails("GITHUB.NOT_A_FILE", () -> gh.getFile(ctx, b, TOKEN, "acme", "app", "config", null, null));

            Map<String, Object> put = gh.putFile(ctx, b, TOKEN, "acme", "app", "reports/new file.csv", "Add report", "a,b", null, "main", null);
            assertEquals("c9", put.get("commit_sha"));
            assertEquals("f9", put.get("sha"));
            Map<String, Object> pb = json(f.last("PUT", "/repos/acme/app/contents/reports/new%20file.csv"));
            assertEquals("a,b", new String(Base64.getDecoder().decode((String) pb.get("content")), StandardCharsets.UTF_8));
            assertEquals("main", pb.get("branch"));
            assertFalse(pb.containsKey("sha"), "new file has no sha");

            Files.writeString(w.resolve("rates.json"), "{\"vat\": 20}");
            gh.putFile(ctx, b, TOKEN, "acme", "app", "config/rates.json", "Update VAT", null, "rates.json", null, null);
            Map<String, Object> pb2 = json(f.last("PUT", "/repos/acme/app/contents/config/rates.json"));
            assertEquals("f1", pb2.get("sha"), "existing file's sha looked up");
            assertEquals("{\"vat\": 20}", new String(Base64.getDecoder().decode((String) pb2.get("content")), StandardCharsets.UTF_8));
            fails("GITHUB.NO_CONTENT", () -> gh.putFile(ctx, b, TOKEN, "acme", "app", "x.txt", "m", null, null, null, null));
        }
    }

    @Test void releasesAndWorkflows() throws Exception {
        try (Fake f = new Fake()) {
            String b = f.url();
            f.on("POST /repos/acme/app/releases", c -> new Object[]{201, "{\"id\":77,\"html_url\":\"rel77\"}"})
             .on("GET /repos/acme/app/releases/latest", c -> ok("{\"id\":77,\"tag_name\":\"v2.4.0\",\"name\":\"2.4\",\"author\":{\"login\":\"ann\"},\"assets\":[{\"name\":\"app.zip\"}]}"))
             .on("POST /repos/acme/app/actions/workflows/deploy.yml/dispatches", c -> new Object[]{204, ""})
             .on("GET /repos/acme/app/actions/workflows/deploy.yml/runs", c -> ok("{\"total_count\":1,\"workflow_runs\":[{\"id\":555,\"name\":\"Deploy\",\"status\":\"completed\",\"conclusion\":\"success\",\"head_branch\":\"main\",\"event\":\"workflow_dispatch\"}]}"))
             .on("GET /repos/acme/app/actions/runs/555", c -> ok("{\"id\":555,\"status\":\"in_progress\",\"conclusion\":null,\"head_sha\":\"abc\"}"));
            ActionContext ctx = ctx(tmp(), log);
            Map<String, Object> rel = gh.createRelease(ctx, b, TOKEN, "acme", "app", "v2.4.0", "2.4", "Notes", "main", false, true);
            assertEquals(new BigDecimal(77), rel.get("release_id"));
            Map<String, Object> rb = json(f.last("POST", "/repos/acme/app/releases"));
            assertEquals("v2.4.0", rb.get("tag_name"));
            assertEquals("main", rb.get("target_commitish"));
            assertEquals(true, rb.get("prerelease"));
            Map<String, Object> latest = gh.latestRelease(ctx, b, TOKEN, "acme", "app");
            assertEquals("v2.4.0", latest.get("tag"));
            assertEquals(List.of("app.zip"), ((Map<?, ?>) latest.get("release")).get("assets"));

            gh.runWorkflow(ctx, b, TOKEN, "acme", "app", "deploy.yml", "main", Map.of("environment", "staging"));
            Map<String, Object> wb = json(f.last("POST", "/repos/acme/app/actions/workflows/deploy.yml/dispatches"));
            assertEquals("main", wb.get("ref"));
            assertEquals(Map.of("environment", "staging"), wb.get("inputs"));
            Map<String, Object> runs = gh.listRuns(ctx, b, TOKEN, "acme", "app", "deploy.yml", "main", "success", 10);
            assertEquals(Arrays.asList(new BigDecimal(555), "Deploy", "completed", "success", "main", "workflow_dispatch", null, null), rows(runs).get(0));
            String rq = q(f.last("GET", "/repos/acme/app/actions/workflows/deploy.yml/runs"));
            assertTrue(rq.contains("branch=main") && rq.contains("status=success") && rq.contains("per_page=10"), rq);
            Map<String, Object> run = gh.getRun(ctx, b, TOKEN, "acme", "app", 555);
            assertEquals("in_progress", run.get("status"));
            assertNull(run.get("conclusion"));
            assertEquals("abc", ((Map<?, ?>) run.get("run")).get("sha"));
        }
    }

    @Test void errorsAreTypedAndRetryableWhereSensible() throws Exception {
        try (Fake f = new Fake()) {
            String b = f.url();
            f.on("GET /repos/acme/app/issues/404", c -> new Object[]{404, "{\"message\":\"Not Found\"}"})
             .on("GET /repos/acme/app/issues/403", c -> new Object[]{403, "{\"message\":\"API rate limit exceeded for user\"}"})
             .on("GET /repos/acme/app/issues/401", c -> new Object[]{401, "{\"message\":\"Bad credentials\"}"})
             .on("GET /repos/acme/app/issues/502", c -> new Object[]{502, "{\"message\":\"Server Error\"}"})
             .on("GET /repos/acme/app/issues/429", c -> new Object[]{429, "{\"message\":\"secondary rate limit\"}"})
             .on("POST /repos/acme/app/issues", c -> new Object[]{422, "{\"message\":\"Validation Failed\",\"errors\":[{\"message\":\"title is too long\"}]}"});
            ActionContext ctx = ctx(tmp(), log);
            ActionException nf = fails("GITHUB.NOT_FOUND", () -> gh.getIssue(ctx, b, TOKEN, "acme", "app", 404));
            assertFalse(nf.retryable());
            assertTrue(fails("GITHUB.RATE_LIMITED", () -> gh.getIssue(ctx, b, TOKEN, "acme", "app", 403)).retryable());
            assertTrue(fails("GITHUB.RATE_LIMITED", () -> gh.getIssue(ctx, b, TOKEN, "acme", "app", 429)).retryable());
            assertTrue(fails("GITHUB.SERVER_ERROR", () -> gh.getIssue(ctx, b, TOKEN, "acme", "app", 502)).retryable());
            assertTrue(fails("GITHUB.AUTH_FAILED", () -> gh.getIssue(ctx, b, TOKEN, "acme", "app", 401)).getMessage().contains("Bad credentials"));
            assertTrue(fails("GITHUB.REQUEST_FAILED", () -> gh.createIssue(ctx, b, TOKEN, "acme", "app", "x", null, null, null, null)).getMessage().contains("title is too long"));
            fails("GITHUB.BAD_NAME", () -> gh.getIssue(ctx, b, TOKEN, "acme/../x", "app", 1));
            fails("GITHUB.BAD_NAME", () -> gh.getIssue(ctx, b, TOKEN, "acme", "..", 1));
            fails("GITHUB.NO_TOKEN", () -> gh.getIssue(ctx, b, null, "acme", "app", 1));
            noSecretInLog(log);
        }
    }

    @Test void runsInsideAFlowWithAVaultCredential() throws Exception {
        try (Fake f = new Fake()) {
            f.on("GET /repos/acme/app/issues/42", c -> ok("{\"number\":42,\"title\":\"Disk full\",\"state\":\"closed\"}"));
            Map<String, Object> o = run(tmp(), "[" + ConnectorsTest.TOKEN_VAR + ",{\"name\":\"s\",\"type\":\"String\",\"direction\":\"output\"}]",
                    "[{\"id\":\"g\",\"action\":\"ooa.github/get_issue\",\"args\":{\"base_url\":\"" + f.url() + "\",\"token\":\"$tok\",\"owner\":\"acme\",\"repo\":\"app\",\"number\":42},\"out\":{\"state\":\"s\"}}]",
                    ConnectorsTest.TOKEN);
            assertEquals("closed", o.get("s"));
            assertEquals("Bearer tok-123", f.last("GET", ".*").auth());
            RunResult bad = ConnectorsTest.runRawWithToken(tmp(), "[" + ConnectorsTest.TOKEN_VAR + "]",
                    "[{\"id\":\"g\",\"action\":\"ooa.github/get_issue\",\"args\":{\"base_url\":\"" + f.url() + "\",\"token\":\"$tok\",\"owner\":\"acme\",\"repo\":\"app\",\"number\":7}}]");
            assertEquals("GITHUB.NOT_FOUND", bad.error().get("code"));
        }
    }
}
