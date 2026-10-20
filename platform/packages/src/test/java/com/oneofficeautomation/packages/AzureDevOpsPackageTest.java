package com.oneofficeautomation.packages;

import com.oneofficeautomation.packages.ConnectorsTest.Call;
import com.oneofficeautomation.packages.ConnectorsTest.Fake;
import com.oneofficeautomation.runner.RunResult;
import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.JsonLite;
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

/** Azure DevOps toolkit against a local server that imitates the Azure DevOps REST API (collection "acme"). */
class AzureDevOpsPackageTest {
    final AzureDevOpsPackage az = new AzureDevOpsPackage();
    final List<String> log = new ArrayList<>();
    static final String PRJ = "/acme/Finance%20Portal";
    static final String BASIC = "Basic " + Base64.getEncoder().encodeToString((":" + SECRET).getBytes(StandardCharsets.UTF_8));

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> ops(Call c) { return (List<Map<String, Object>>) JsonLite.parse(c.text()); }

    @Test void catalog() { checkCatalog("ooa.azure_devops", 20); }

    @Test void projectsUseContinuationTokenPaging() throws Exception {
        try (Fake f = new Fake()) {
            String org = f.url() + "/acme/";
            f.on("GET /acme/_apis/projects", c -> q(c).contains("continuationToken=next 1")
                    ? ok("{\"value\":[{\"id\":\"p2\",\"name\":\"HR\"}]}")
                    : new Object[]{200, "{\"value\":[{\"id\":\"p1\",\"name\":\"Finance Portal\",\"state\":\"wellFormed\"}]}", Map.of("x-ms-continuationtoken", "next 1")})
             .on("GET /acme/_apis/projects/Finance%20Portal", c -> ok("{\"id\":\"p1\",\"name\":\"Finance Portal\",\"defaultTeam\":{\"name\":\"Finance Portal Team\"},\"_links\":{\"web\":{\"href\":\"w\"}}}"));
            ActionContext ctx = ctx(tmp(), log);
            Map<String, Object> r = az.listProjects(ctx, org, TOKEN, 200);
            assertEquals(List.of("p1", "p2"), col(r, 0));
            Call first = f.calls.get(0);
            assertEquals(BASIC, first.auth(), "PAT sent as basic auth with an empty user");
            assertTrue(q(first).contains("api-version=7.0") && q(first).contains("$top=200"), q(first));
            Map<?, ?> p = (Map<?, ?>) az.getProject(ctx, org, TOKEN, "Finance Portal").get("project");
            assertEquals("Finance Portal Team", p.get("default_team"));
            assertEquals("w", p.get("url"));
            noSecretInLog(log);
        }
    }

    @Test void workItemsCreateGetUpdateDeleteCommentsAndAttach() throws Exception {
        try (Fake f = new Fake()) {
            String org = f.url() + "/acme";
            f.on("POST " + PRJ + "/_apis/wit/workitems/\\$User%20Story", c -> ok("{\"id\":4711,\"_links\":{\"html\":{\"href\":\"wi4711\"}}}"))
             .on("GET /acme/_apis/wit/workitems/4711", c -> ok("{\"id\":4711,\"rev\":3,\"fields\":{\"System.WorkItemType\":\"User Story\",\"System.Title\":\"Pay run\","
                    + "\"System.State\":\"Active\",\"System.AssignedTo\":{\"displayName\":\"Ana Silva\",\"uniqueName\":\"ana@contoso.com\"},\"System.Tags\":\"agent\"},\"_links\":{\"html\":{\"href\":\"wi4711\"}}}"))
             .on("PATCH /acme/_apis/wit/workitems/4711", c -> ok("{\"id\":4711,\"rev\":4}"))
             .on("DELETE /acme/_apis/wit/workitems/4711", c -> ok("{\"id\":4711}"))
             .on("POST " + PRJ + "/_apis/wit/workItems/4711/comments", c -> ok("{\"id\":12}"))
             .on("GET " + PRJ + "/_apis/wit/workItems/4711/comments", c -> q(c).contains("continuationToken=c2")
                    ? ok("{\"comments\":[{\"id\":2,\"text\":\"second\",\"createdBy\":{\"displayName\":\"Bo\"}}]}")
                    : ok("{\"comments\":[{\"id\":1,\"text\":\"first\",\"createdBy\":{\"displayName\":\"Ana\"},\"createdDate\":\"2026-10-01\"}],\"continuationToken\":\"c2\"}"))
             .on("POST " + PRJ + "/_apis/wit/attachments", c -> ok("{\"id\":\"a1\",\"url\":\"" + org + "/_apis/wit/attachments/a1\"}"));
            Path w = tmp();
            Files.writeString(w.resolve("error log.txt"), "stack");
            ActionContext ctx = ctx(w, log);

            Map<String, Object> created = az.createWorkItem(ctx, org, TOKEN, "Finance Portal", "User Story", "Pay run", "<b>x</b>", "ana@contoso.com",
                    null, "Finance Portal\\Sprint 42", "agent; payroll", 100, Map.of("Microsoft.VSTS.Common.Priority", 1));
            assertEquals(new BigDecimal(4711), created.get("id"));
            assertEquals("wi4711", created.get("url"));
            Call c = f.last("POST", PRJ + "/_apis/wit/workitems/.*");
            assertEquals("application/json-patch+json", c.contentType());
            assertTrue(q(c).contains("api-version=7.0"));
            List<Map<String, Object>> ops = ops(c);
            Map<String, Object> byPath = new HashMap<>();
            for (Map<String, Object> o : ops) { assertEquals("add", o.get("op")); byPath.put((String) o.get("path"), o.get("value")); }
            assertEquals("Pay run", byPath.get("/fields/System.Title"));
            assertEquals("ana@contoso.com", byPath.get("/fields/System.AssignedTo"));
            assertEquals("Finance Portal\\Sprint 42", byPath.get("/fields/System.IterationPath"));
            assertEquals("agent; payroll", byPath.get("/fields/System.Tags"));
            assertEquals(new BigDecimal(1), byPath.get("/fields/Microsoft.VSTS.Common.Priority"));
            assertFalse(byPath.containsKey("/fields/System.AreaPath"));
            Map<?, ?> rel = (Map<?, ?>) byPath.get("/relations/-");
            assertEquals("System.LinkTypes.Hierarchy-Reverse", rel.get("rel"));
            assertEquals(org + "/_apis/wit/workItems/100", rel.get("url"));

            Map<String, Object> got = az.getWorkItem(ctx, org, TOKEN, 4711);
            assertEquals("Active", got.get("state"));
            Map<?, ?> wi = (Map<?, ?>) got.get("work_item");
            assertEquals("Ana Silva", wi.get("assigned_to"));
            assertEquals("User Story", wi.get("type"));
            assertEquals(new BigDecimal(3), wi.get("rev"));

            assertEquals(new BigDecimal(4), az.updateWorkItem(ctx, org, TOKEN, 4711, null, "Resolved", null, null, null, null, Map.of("Custom.Ref", "X")).get("rev"));
            List<Map<String, Object>> up = ops(f.last("PATCH", "/acme/_apis/wit/workitems/4711"));
            assertEquals(2, up.size());
            assertEquals("/fields/System.State", up.get(0).get("path"));
            assertEquals("Resolved", up.get(0).get("value"));
            fails("AZURE_DEVOPS.NOTHING_TO_UPDATE", () -> az.updateWorkItem(ctx, org, TOKEN, 4711, null, null, null, null, null, null, null));

            az.deleteWorkItem(ctx, org, TOKEN, 4711, false);
            assertTrue(q(f.last("DELETE", ".*")).contains("destroy=false"));
            az.deleteWorkItem(ctx, org, TOKEN, 4711, true);
            assertTrue(q(f.last("DELETE", ".*")).contains("destroy=true"));

            assertEquals(new BigDecimal(12), az.addComment(ctx, org, TOKEN, "Finance Portal", 4711, "Re-sent").get("comment_id"));
            Call cc = f.last("POST", PRJ + "/_apis/wit/workItems/4711/comments");
            assertEquals(Map.of("text", "Re-sent"), json(cc));
            assertTrue(q(cc).contains("api-version=7.0-preview.3"));
            Map<String, Object> comments = az.listComments(ctx, org, TOKEN, "Finance Portal", 4711, 200);
            assertEquals(List.of("first", "second"), col(comments, 1));
            assertEquals("Ana", rows(comments).get(0).get(2));

            String url = (String) az.attachFile(ctx, org, TOKEN, "Finance Portal", 4711, "error log.txt", "From the nightly agent").get("attachment_url");
            assertTrue(url.endsWith("/attachments/a1"));
            Call upl = f.last("POST", PRJ + "/_apis/wit/attachments");
            assertEquals("stack", upl.text());
            assertTrue(q(upl).contains("fileName=error log.txt"));
            Map<String, Object> link = ops(f.last("PATCH", "/acme/_apis/wit/workitems/4711")).get(0);
            assertEquals("/relations/-", link.get("path"));
            assertEquals("AttachedFile", HttpJson.path(link, "value.rel"));
            assertEquals("From the nightly agent", HttpJson.path(link, "value.attributes.comment"));
            noSecretInLog(log);
        }
    }

    @Test void wiqlQueryReadsFieldsInBatches() throws Exception {
        try (Fake f = new Fake()) {
            String org = f.url() + "/acme";
            StringBuilder ids = new StringBuilder();
            for (int i = 1; i <= 250; i++) ids.append(i > 1 ? "," : "").append("{\"id\":").append(i).append("}");
            f.on("POST " + PRJ + "/_apis/wit/wiql", c -> ok("{\"workItems\":[" + ids + "]}"))
             .on("POST /acme/_apis/wit/workitemsbatch", c -> {
                 List<?> asked = (List<?>) json(c).get("ids");
                 StringBuilder v = new StringBuilder();
                 for (Object id : asked) v.append(v.length() > 0 ? "," : "").append("{\"id\":").append(id).append(",\"fields\":{\"System.Title\":\"T").append(id)
                         .append("\",\"System.State\":\"New\",\"System.AssignedTo\":{\"displayName\":\"Ana\"},\"Microsoft.VSTS.Common.Priority\":2}}");
                 return ok("{\"value\":[" + v + "]}");
             });
            ActionContext ctx = ctx(tmp(), log);
            Map<String, Object> r = az.queryWorkItems(ctx, org, TOKEN, "Finance Portal", "SELECT [System.Id] FROM WorkItems", List.of("Microsoft.VSTS.Common.Priority"), 1000);
            assertEquals(new BigDecimal(250), r.get("count"));
            assertEquals(List.of("id", "type", "title", "state", "assigned_to", "changed_date", "Microsoft.VSTS.Common.Priority"), columns(r));
            List<Object> row = rows(r).get(249);
            assertEquals("T250", row.get(2));
            assertEquals("Ana", row.get(4));
            assertEquals(new BigDecimal(2), row.get(6));
            assertEquals(2, f.calls.stream().filter(c -> c.path().endsWith("workitemsbatch")).count(), "200 ids per batch");
            assertEquals("SELECT [System.Id] FROM WorkItems", json(f.last("POST", PRJ + "/_apis/wit/wiql")).get("query"));
            assertTrue(((List<?>) json(f.last("POST", "/acme/_apis/wit/workitemsbatch")).get("fields")).contains("System.Title"));
            Map<String, Object> few = az.queryWorkItems(ctx, org, TOKEN, "Finance Portal", "SELECT [System.Id] FROM WorkItems", null, 5);
            assertEquals(new BigDecimal(5), few.get("count"));
        }
    }

    @Test void reposAndPullRequests() throws Exception {
        try (Fake f = new Fake()) {
            String org = f.url() + "/acme";
            String repo = PRJ + "/_apis/git/repositories/api";
            StringBuilder full = new StringBuilder("{\"pullRequestId\":1,\"title\":\"A\",\"createdBy\":{\"displayName\":\"Ana\"}}");
            for (int i = 2; i <= 100; i++) full.append(",{\"pullRequestId\":").append(i).append("}");
            f.on("GET " + PRJ + "/_apis/git/repositories", c -> ok("{\"value\":[{\"id\":\"r1\",\"name\":\"api\",\"defaultBranch\":\"refs/heads/main\"}]}"))
             .on("GET " + repo + "/pullrequests", c -> q(c).contains("$skip=0") ? ok("{\"value\":[" + full + "]}")
                    : ok("{\"value\":[{\"pullRequestId\":101,\"title\":\"C\"}]}"))
             .on("GET " + repo + "/pullrequests/9", c -> ok("{\"pullRequestId\":9,\"status\":\"active\",\"lastMergeSourceCommit\":{\"commitId\":\"abc\"},\"sourceRefName\":\"refs/heads/feat\"}"))
             .on("GET " + repo + "/pullrequests/8", c -> ok("{\"pullRequestId\":8,\"status\":\"abandoned\"}"))
             .on("PATCH " + repo + "/pullrequests/9", c -> ok("{\"status\":\"completed\",\"mergeStatus\":\"succeeded\"}"))
             .on("POST " + repo + "/pullrequests", c -> new Object[]{201, "{\"pullRequestId\":10}"})
             .on("POST " + repo + "/pullRequests/9/threads", c -> ok("{\"id\":55}"));
            ActionContext ctx = ctx(tmp(), log);
            Map<String, Object> repos = az.listRepositories(ctx, org, TOKEN, "Finance Portal");
            assertEquals(Arrays.asList("r1", "api", "refs/heads/main", null, null, null), rows(repos).get(0));
            Map<String, Object> prs = az.listPullRequests(ctx, org, TOKEN, "Finance Portal", "api", "active", "main", 500);
            assertEquals(new BigDecimal(101), prs.get("count"), "skip paging reads the second page");
            assertEquals(new BigDecimal(101), rows(prs).get(100).get(0));
            assertTrue(q(f.last("GET", repo + "/pullrequests")).contains("$skip=100"));
            String pq = q(f.calls.stream().filter(c -> c.path().endsWith("/pullrequests")).findFirst().orElseThrow());
            assertTrue(pq.contains("searchCriteria.status=active") && pq.contains("searchCriteria.targetRefName=refs/heads/main") && pq.contains("$top=100"), pq);
            assertEquals("Ana", rows(prs).get(0).get(4));
            Map<String, Object> pr = az.getPullRequest(ctx, org, TOKEN, "Finance Portal", "api", 9);
            assertEquals("active", pr.get("status"));
            assertEquals("abc", ((Map<?, ?>) pr.get("pull_request")).get("last_source_commit"));

            assertEquals(new BigDecimal(10), az.createPullRequest(ctx, org, TOKEN, "Finance Portal", "api", "feat", "refs/heads/main", "VAT", null, true, List.of(new BigDecimal(4711))).get("pull_request_id"));
            Map<String, Object> cb = json(f.last("POST", repo + "/pullrequests"));
            assertEquals("refs/heads/feat", cb.get("sourceRefName"));
            assertEquals("refs/heads/main", cb.get("targetRefName"));
            assertEquals(true, cb.get("isDraft"));
            assertEquals(List.of(Map.of("id", "4711")), cb.get("workItemRefs"));

            Map<String, Object> done = az.completePullRequest(ctx, org, TOKEN, "Finance Portal", "api", 9, "squash", true, "Merged by agent");
            assertEquals("completed", done.get("status"));
            assertEquals("succeeded", done.get("merge_status"));
            Map<String, Object> pb = json(f.last("PATCH", repo + "/pullrequests/9"));
            assertEquals("completed", pb.get("status"));
            assertEquals("abc", HttpJson.path(pb, "lastMergeSourceCommit.commitId"));
            assertEquals("squash", HttpJson.path(pb, "completionOptions.mergeStrategy"));
            assertEquals(true, HttpJson.path(pb, "completionOptions.deleteSourceBranch"));
            fails("AZURE_DEVOPS.NOT_ACTIVE", () -> az.completePullRequest(ctx, org, TOKEN, "Finance Portal", "api", 8, "squash", false, null));

            assertEquals(new BigDecimal(55), az.commentPullRequest(ctx, org, TOKEN, "Finance Portal", "api", 9, "LGTM").get("thread_id"));
            Map<String, Object> tb = json(f.last("POST", repo + "/pullRequests/9/threads"));
            assertEquals("LGTM", HttpJson.path(((List<?>) tb.get("comments")).get(0), "content"));
            assertEquals("active", tb.get("status"));
        }
    }

    @Test void pipelinesBuildsAndSprints() throws Exception {
        try (Fake f = new Fake()) {
            String org = f.url() + "/acme";
            f.on("GET " + PRJ + "/_apis/pipelines", c -> ok("{\"value\":[{\"id\":12,\"name\":\"deploy\",\"folder\":\"\\\\\"}]}"))
             .on("POST " + PRJ + "/_apis/pipelines/12/runs", c -> ok("{\"id\":900,\"state\":\"inProgress\",\"_links\":{\"web\":{\"href\":\"run900\"}}}"))
             .on("GET " + PRJ + "/_apis/pipelines/12/runs/900", c -> ok("{\"id\":900,\"state\":\"completed\",\"result\":\"succeeded\",\"finishedDate\":\"2026-10-20\"}"))
             .on("GET " + PRJ + "/_apis/pipelines/12/runs/901", c -> ok("{\"id\":901,\"state\":\"inProgress\"}"))
             .on("POST " + PRJ + "/_apis/build/builds", c -> ok("{\"id\":77,\"buildNumber\":\"20261020.3\",\"_links\":{\"web\":{\"href\":\"b77\"}}}"))
             .on("GET " + PRJ + "/_apis/build/builds/77", c -> ok("{\"id\":77,\"status\":\"completed\",\"result\":\"failed\",\"definition\":{\"name\":\"ci\"},\"requestedFor\":{\"displayName\":\"Ana\"}}"))
             .on("GET " + PRJ + "/_apis/build/builds", c -> ok("{\"value\":[{\"id\":77,\"buildNumber\":\"20261020.3\",\"status\":\"completed\",\"result\":\"failed\",\"definition\":{\"name\":\"ci\"}}]}"))
             .on("GET " + PRJ + "/Payments%20Team/_apis/work/teamsettings/iterations", c -> ok("{\"value\":[{\"id\":\"it1\",\"name\":\"Sprint 42\",\"path\":\"FP\\\\Sprint 42\",\"attributes\":{\"timeFrame\":\"current\",\"startDate\":\"2026-10-13\"}}]}"))
             .on("GET " + PRJ + "/_apis/work/teamsettings/iterations/it1/workitems", c -> ok("{\"workItemRelations\":[{\"target\":{\"id\":1}},{\"source\":{\"id\":1},\"target\":{\"id\":2}},{\"target\":{\"id\":1}}]}"));
            ActionContext ctx = ctx(tmp(), log);
            assertEquals(List.of(new BigDecimal(12)), col(az.listPipelines(ctx, org, TOKEN, "Finance Portal", 200), 0));
            Map<String, Object> run = az.runPipeline(ctx, org, TOKEN, "Finance Portal", 12, "release/1", Map.of("environment", "test"), Map.of("note", 5));
            assertEquals(new BigDecimal(900), run.get("run_id"));
            assertEquals("run900", run.get("url"));
            Map<String, Object> rb = json(f.last("POST", PRJ + "/_apis/pipelines/12/runs"));
            assertEquals("refs/heads/release/1", HttpJson.path(rb, "resources.repositories.self.refName"));
            assertEquals("test", HttpJson.path(rb, "templateParameters.environment"));
            assertEquals("5", HttpJson.path(rb, "variables.note.value"));
            Map<String, Object> st = az.getRun(ctx, org, TOKEN, "Finance Portal", 12, 900);
            assertEquals("completed", st.get("state"));
            assertEquals("succeeded", st.get("result"));
            assertTrue(az.runFinished(ctx, org, TOKEN, "Finance Portal", 12, 900));
            assertFalse(az.runFinished(ctx, org, TOKEN, "Finance Portal", 12, 901));

            Map<String, Object> qb = az.queueBuild(ctx, org, TOKEN, "Finance Portal", 7, "main", Map.of("Configuration", "Release"));
            assertEquals("20261020.3", qb.get("build_number"));
            Map<String, Object> bb = json(f.last("POST", PRJ + "/_apis/build/builds"));
            assertEquals(new BigDecimal(7), HttpJson.path(bb, "definition.id"));
            assertEquals("refs/heads/main", bb.get("sourceBranch"));
            assertEquals("{\"Configuration\":\"Release\"}", bb.get("parameters"), "queue-time variables are sent as a JSON string");
            Map<String, Object> b = az.getBuild(ctx, org, TOKEN, "Finance Portal", 77);
            assertEquals("failed", b.get("result"));
            assertEquals("Ana", ((Map<?, ?>) b.get("build")).get("requested_for"));
            Map<String, Object> lb = az.listBuilds(ctx, org, TOKEN, "Finance Portal", 7, "main", "completed", "failed", 50);
            assertEquals("ci", rows(lb).get(0).get(4));
            String bq = q(f.last("GET", PRJ + "/_apis/build/builds"));
            assertTrue(bq.contains("definitions=7") && bq.contains("branchName=refs/heads/main") && bq.contains("statusFilter=completed")
                    && bq.contains("resultFilter=failed") && bq.contains("queryOrder=queueTimeDescending"), bq);

            Map<String, Object> its = az.listIterations(ctx, org, TOKEN, "Finance Portal", "Payments Team", true);
            assertEquals(Arrays.asList("it1", "Sprint 42", "FP\\Sprint 42", "2026-10-13", null, "current"), rows(its).get(0));
            assertTrue(q(f.last("GET", ".*/iterations")).contains("$timeframe=current"));
            Map<String, Object> wi = az.iterationWorkItems(ctx, org, TOKEN, "Finance Portal", null, "it1");
            assertEquals(List.of(new BigDecimal(1), new BigDecimal(2)), wi.get("ids"));
        }
    }

    @Test void errorsAreTypedIncludingSignInPageAnswers() throws Exception {
        try (Fake f = new Fake()) {
            String org = f.url() + "/acme";
            f.on("GET /acme/_apis/wit/workitems/1", c -> new Object[]{404, "{\"$id\":\"1\",\"message\":\"TF401232: Work item 1 does not exist.\",\"typeKey\":\"WorkItemDoesNotExistException\"}"})
             .on("GET /acme/_apis/wit/workitems/2", c -> new Object[]{203, "<html><body>Sign in</body></html>"})
             .on("GET /acme/_apis/wit/workitems/3", c -> new Object[]{503, "{\"message\":\"Service unavailable\"}"})
             .on("GET /acme/_apis/wit/workitems/4", c -> new Object[]{429, "{\"message\":\"Request was blocked\"}"})
             .on("GET /acme/_apis/wit/workitems/5", c -> new Object[]{401, ""});
            ActionContext ctx = ctx(tmp(), log);
            assertTrue(fails("AZURE_DEVOPS.NOT_FOUND", () -> az.getWorkItem(ctx, org, TOKEN, 1)).getMessage().contains("TF401232"));
            fails("AZURE_DEVOPS.AUTH_FAILED", () -> az.getWorkItem(ctx, org, TOKEN, 2));
            assertTrue(fails("AZURE_DEVOPS.SERVER_ERROR", () -> az.getWorkItem(ctx, org, TOKEN, 3)).retryable());
            assertTrue(fails("AZURE_DEVOPS.RATE_LIMITED", () -> az.getWorkItem(ctx, org, TOKEN, 4)).retryable());
            assertFalse(fails("AZURE_DEVOPS.AUTH_FAILED", () -> az.getWorkItem(ctx, org, TOKEN, 5)).retryable());
            fails("AZURE_DEVOPS.MISSING_PROJECT", () -> az.listRepositories(ctx, org, TOKEN, " "));
            fails("AZURE_DEVOPS.NO_PASSWORD", () -> az.getWorkItem(ctx, org, null, 1));
            noSecretInLog(log);
        }
    }

    @Test void runsInsideAFlowWithAVaultCredential() throws Exception {
        try (Fake f = new Fake()) {
            f.on("GET /acme/_apis/wit/workitems/9", c -> ok("{\"id\":9,\"fields\":{\"System.State\":\"Closed\"}}"));
            Map<String, Object> o = run(tmp(), "[" + ConnectorsTest.TOKEN_VAR + ",{\"name\":\"s\",\"type\":\"String\",\"direction\":\"output\"}]",
                    "[{\"id\":\"g\",\"action\":\"ooa.azure_devops/get_work_item\",\"args\":{\"organization_url\":\"" + f.url() + "/acme\",\"personal_access_token\":\"$tok\",\"id\":9},\"out\":{\"state\":\"s\"}}]",
                    ConnectorsTest.TOKEN);
            assertEquals("Closed", o.get("s"));
            RunResult bad = ConnectorsTest.runRawWithToken(tmp(), "[" + ConnectorsTest.TOKEN_VAR + "]",
                    "[{\"id\":\"g\",\"action\":\"ooa.azure_devops/get_work_item\",\"args\":{\"organization_url\":\"" + f.url() + "/acme\",\"personal_access_token\":\"$tok\",\"id\":10}}]");
            assertEquals("AZURE_DEVOPS.NOT_FOUND", bad.error().get("code"));
        }
    }
}
