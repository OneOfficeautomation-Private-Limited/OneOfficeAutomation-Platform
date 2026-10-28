package com.oneofficeautomation.packages;

import com.oneofficeautomation.packages.ConnectorsTest.Call;
import com.oneofficeautomation.packages.ConnectorsTest.Fake;
import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.JsonLite;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static com.oneofficeautomation.packages.ConnectorsTest.ok;
import static com.oneofficeautomation.packages.ConnectorsTest.q;
import static com.oneofficeautomation.packages.PackagesTest.*;
import static com.oneofficeautomation.packages.ToolkitTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** New options of the Azure DevOps toolkit: plan dates, test case fields, automated runs, skipped stages, pipeline YAML, test point testers,
 *  plan and point filters, suite queries and test case steps on Update work item. */
class AzureDevOpsMoreOptionsTest {
    final AzureDevOpsPackage az = new AzureDevOpsPackage();
    final List<String> log = new ArrayList<>();
    static final String PRJ = "/acme/Finance%20Portal";
    static final String REPO_ID = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0";
    static final String ANA_ID = "81fa6389-0872-6fdd-a451-7ba7880f566a";

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> list(Call c) { return (List<Map<String, Object>>) JsonLite.parse(c.text()); }

    @Test void catalogStillValid() { checkCatalog("ooa.azure_devops", 20); }

    @Test void testPlanStartAndEndDates() throws Exception {
        try (Fake f = new Fake()) {
            String org = f.url() + "/acme";
            f.on("POST " + PRJ + "/_apis/testplan/plans", c -> ok("{\"id\":31,\"rootSuite\":{\"id\":32}}"))
             .on("PATCH " + PRJ + "/_apis/testplan/plans/31", c -> ok("{\"id\":31}"));
            ActionContext ctx = ctx(tmp(), log);
            Map<String, Object> r = az.createTestPlan(ctx, org, TOKEN, "Finance Portal", "Release 3.2", null, null, null, "2026-11-01", "2026-11-14T17:30:00Z");
            assertEquals(new BigDecimal(31), r.get("plan_id"));
            assertEquals(new BigDecimal(32), r.get("root_suite_id"));
            Call c = f.last("POST", PRJ + "/_apis/testplan/plans");
            assertTrue(q(c).contains("api-version=7.0"), q(c));
            Map<String, Object> b = json(c);
            assertEquals("Release 3.2", b.get("name"));
            assertEquals("2026-11-01T00:00:00Z", b.get("startDate"));
            assertEquals("2026-11-14T17:30:00Z", b.get("endDate"));

            az.createTestPlan(ctx, org, TOKEN, "Finance Portal", "No dates", null, null, null);
            Map<String, Object> plain = json(f.last("POST", PRJ + "/_apis/testplan/plans"));
            assertFalse(plain.containsKey("startDate") || plain.containsKey("endDate"), plain.toString());

            az.updateTestPlan(ctx, org, TOKEN, "Finance Portal", 31, null, null, null, null, null, null, "2026-12-01");
            assertEquals(Map.of("endDate", "2026-12-01T00:00:00Z"), json(f.last("PATCH", PRJ + "/_apis/testplan/plans/31")));

            int before = f.calls.size();
            fails("AZURE_DEVOPS.BAD_DATES", () -> az.createTestPlan(ctx, org, TOKEN, "Finance Portal", "X", null, null, null, "2026-11-14", "2026-11-01"));
            fails("AZURE_DEVOPS.BAD_DATE", () -> az.createTestPlan(ctx, org, TOKEN, "Finance Portal", "X", null, null, null, "next week", null));
            assertEquals(before, f.calls.size(), "bad dates are refused before any request");
            noSecretInLog(log);
        }
    }

    @Test void testCaseNamedFieldsWinOverTheFieldsDictionary() throws Exception {
        try (Fake f = new Fake()) {
            String org = f.url() + "/acme";
            f.on("POST " + PRJ + "/_apis/wit/workitems/\\$Test%20Case", c -> ok("{\"id\":101}"));
            ActionContext ctx = ctx(tmp(), log);
            Map<String, Object> r = az.createTestCase(ctx, org, TOKEN, "Finance Portal", "Check totals", List.of("Open the invoice"),
                    Map.of("priority", 3, "Custom.Ref", "X"), null, null, "Finance Portal\\Payments", "Finance Portal\\Sprint 42", 1, "Planned");
            assertEquals(new BigDecimal(101), r.get("id"));
            Call c = f.last("POST", PRJ + "/_apis/wit/workitems/.*");
            assertEquals("application/json-patch+json", c.contentType());
            Map<String, Object> byPath = new HashMap<>();
            int priorities = 0;
            for (Map<String, Object> o : list(c)) {
                assertEquals("add", o.get("op"));
                byPath.put((String) o.get("path"), o.get("value"));
                if ("/fields/Microsoft.VSTS.Common.Priority".equals(o.get("path"))) priorities++;
            }
            assertEquals(1, priorities, "one priority operation");
            assertEquals("Check totals", byPath.get("/fields/System.Title"));
            assertEquals("Finance Portal\\Payments", byPath.get("/fields/System.AreaPath"));
            assertEquals("Finance Portal\\Sprint 42", byPath.get("/fields/System.IterationPath"));
            assertEquals(new BigDecimal(1), byPath.get("/fields/Microsoft.VSTS.Common.Priority"));
            assertEquals("Planned", byPath.get("/fields/Microsoft.VSTS.TCM.AutomationStatus"));
            assertEquals("X", byPath.get("/fields/Custom.Ref"));
            assertTrue(byPath.containsKey("/fields/Microsoft.VSTS.TCM.Steps"));

            az.createTestCase(ctx, org, TOKEN, "Finance Portal", "Old call", null, null, null, null);
            assertEquals(1, list(f.last("POST", PRJ + "/_apis/wit/workitems/.*")).size(), "only the title without the new inputs");

            int before = f.calls.size();
            fails("AZURE_DEVOPS.BAD_PRIORITY", () -> az.createTestCase(ctx, org, TOKEN, "Finance Portal", "T", null, null, null, null, null, null, 5, null));
            fails("AZURE_DEVOPS.BAD_AUTOMATION_STATUS", () -> az.createTestCase(ctx, org, TOKEN, "Finance Portal", "T", null, null, null, null, null, null, null, "Sometimes"));
            assertEquals(before, f.calls.size());
        }
    }

    @Test void automatedTestRunLinkedToABuild() throws Exception {
        try (Fake f = new Fake()) {
            String org = f.url() + "/acme";
            f.on("POST " + PRJ + "/_apis/test/runs", c -> ok("{\"id\":31,\"webAccessUrl\":\"run31\"}"));
            ActionContext ctx = ctx(tmp(), log);
            Map<String, Object> r = az.createTestRun(ctx, org, TOKEN, "Finance Portal", 5, "Nightly", List.of(1, 2), true, 77);
            assertEquals(new BigDecimal(31), r.get("run_id"));
            assertEquals("run31", r.get("url"));
            Map<String, Object> b = json(f.last("POST", PRJ + "/_apis/test/runs"));
            assertEquals(true, b.get("automated"));
            assertEquals(Map.of("id", "77"), b.get("build"));
            assertEquals(Map.of("id", "5"), b.get("plan"));
            assertEquals(List.of(new BigDecimal(1), new BigDecimal(2)), b.get("pointIds"));

            az.createTestRun(ctx, org, TOKEN, "Finance Portal", 5, "Manual", null);
            Map<String, Object> manual = json(f.last("POST", PRJ + "/_apis/test/runs"));
            assertEquals(false, manual.get("automated"));
            assertFalse(manual.containsKey("build"));

            fails("AZURE_DEVOPS.BAD_ID", () -> az.createTestRun(ctx, org, TOKEN, "Finance Portal", 5, "X", null, true, 0));
        }
    }

    @Test void runPipelineSkipsStages() throws Exception {
        try (Fake f = new Fake()) {
            String org = f.url() + "/acme";
            f.on("POST " + PRJ + "/_apis/pipelines/12/runs", c -> ok("{\"id\":900,\"state\":\"inProgress\"}"));
            ActionContext ctx = ctx(tmp(), log);
            assertEquals(new BigDecimal(900), az.runPipeline(ctx, org, TOKEN, "Finance Portal", 12, null, null, null, List.of("DeployProd", " Smoke ", "DeployProd")).get("run_id"));
            Map<String, Object> b = json(f.last("POST", PRJ + "/_apis/pipelines/12/runs"));
            assertEquals(List.of("DeployProd", "Smoke"), b.get("stagesToSkip"));

            az.runPipeline(ctx, org, TOKEN, "Finance Portal", 12, "main", null, null);
            Map<String, Object> old = json(f.last("POST", PRJ + "/_apis/pipelines/12/runs"));
            assertFalse(old.containsKey("stagesToSkip"));
            assertEquals("refs/heads/main", HttpJson.path(old, "resources.repositories.self.refName"));

            fails("AZURE_DEVOPS.BAD_STAGE", () -> az.runPipeline(ctx, org, TOKEN, "Finance Portal", 12, null, null, null, Arrays.asList("A", " ")));
        }
    }

    @Test void updatePipelinePointsAtAnotherYamlFileAndRepository() throws Exception {
        try (Fake f = new Fake()) {
            String org = f.url() + "/acme";
            String defs = PRJ + "/_apis/build/definitions/";
            f.on("GET " + defs + "12", c -> ok("{\"id\":12,\"revision\":4,\"name\":\"deploy\",\"path\":\"\\\\\",\"process\":{\"type\":2,\"yamlFilename\":\"/azure-pipelines.yml\"},"
                    + "\"repository\":{\"id\":\"old\",\"name\":\"api\",\"url\":\"u\",\"type\":\"TfsGit\",\"defaultBranch\":\"refs/heads/main\"}}"))
             .on("GET " + defs + "13", c -> ok("{\"id\":13,\"revision\":1,\"name\":\"classic\",\"process\":{\"type\":1}}"))
             .on("PUT " + defs + "12", c -> ok("{\"id\":12}"))
             .on("PUT " + defs + "13", c -> ok("{\"id\":13}"));
            ActionContext ctx = ctx(tmp(), log);
            az.updatePipeline(ctx, org, TOKEN, "Finance Portal", 12, null, null, "/pipelines/release.yml", REPO_ID);
            Map<String, Object> b = json(f.last("PUT", defs + "12"));
            assertEquals("/pipelines/release.yml", HttpJson.path(b, "process.yamlFilename"));
            assertEquals(new BigDecimal(2), HttpJson.path(b, "process.type"));
            assertEquals(REPO_ID, HttpJson.path(b, "repository.id"));
            assertEquals("TfsGit", HttpJson.path(b, "repository.type"));
            assertEquals("refs/heads/main", HttpJson.path(b, "repository.defaultBranch"));
            assertNull(HttpJson.path(b, "repository.name"), "the old repository's name is not sent");
            assertEquals(new BigDecimal(4), b.get("revision"), "the definition is sent back with its revision");
            assertEquals("deploy", b.get("name"));

            az.updatePipeline(ctx, org, TOKEN, "Finance Portal", 12, "renamed", null);
            Map<String, Object> renamed = json(f.last("PUT", defs + "12"));
            assertEquals("renamed", renamed.get("name"));
            assertEquals("/azure-pipelines.yml", HttpJson.path(renamed, "process.yamlFilename"));

            fails("AZURE_DEVOPS.NOT_YAML", () -> az.updatePipeline(ctx, org, TOKEN, "Finance Portal", 13, null, null, "/x.yml", null));
            assertNull(f.last("PUT", defs + "13"), "a classic pipeline is not changed");
            fails("AZURE_DEVOPS.BAD_REPOSITORY_ID", () -> az.updatePipeline(ctx, org, TOKEN, "Finance Portal", 12, null, null, null, "api"));
            fails("AZURE_DEVOPS.NOTHING_TO_UPDATE", () -> az.updatePipeline(ctx, org, TOKEN, "Finance Portal", 12, null, null, " ", null));
        }
    }

    @Test void testPointTesterResolvedByEmailOrId() throws Exception {
        try (Fake f = new Fake()) {
            String org = f.url() + "/acme";
            String points = PRJ + "/_apis/testplan/Plans/5/Suites/6/TestPoint";
            f.on("GET /acme/_apis/identities", c -> {
                 String q = q(c);
                 if (q.contains("filterValue=ana@contoso.com")) return ok("{\"count\":2,\"value\":[{\"id\":\"" + ANA_ID + "\",\"providerDisplayName\":\"Ana Silva\","
                         + "\"properties\":{\"Mail\":{\"$type\":\"System.String\",\"$value\":\"ana@contoso.com\"}}},{\"id\":\"g1\",\"providerDisplayName\":\"Testers\",\"isContainer\":true}]}");
                 if (q.contains("filterValue=Bo")) return ok("{\"count\":2,\"value\":[{\"id\":\"b1\",\"providerDisplayName\":\"Bo Li\"},{\"id\":\"b2\",\"providerDisplayName\":\"Bo Chen\"}]}");
                 return ok("{\"count\":0,\"value\":[]}");
             })
             .on("PATCH " + points, c -> ok("[]"));
            ActionContext ctx = ctx(tmp(), log);
            assertEquals(new BigDecimal(2), az.updateTestPoint(ctx, org, TOKEN, "Finance Portal", 5, 6, List.of(11, 12), null, "ana@contoso.com").get("count"));
            String iq = q(f.last("GET", "/acme/_apis/identities"));
            assertTrue(iq.contains("searchFilter=General") && iq.contains("queryMembership=None") && iq.contains("api-version=7.0"), iq);
            List<Map<String, Object>> body = list(f.last("PATCH", points));
            assertEquals(2, body.size());
            assertEquals(new BigDecimal(11), body.get(0).get("id"));
            assertEquals(Map.of("id", ANA_ID, "displayName", "Ana Silva"), body.get(0).get("tester"));
            assertFalse(body.get(0).containsKey("results"), "a tester-only update leaves the outcome alone");

            int identityCalls = (int) f.calls.stream().filter(c -> c.path().endsWith("/identities")).count();
            az.updateTestPoint(ctx, org, TOKEN, "Finance Portal", 5, 6, List.of(11), "passed", ANA_ID);
            assertEquals(identityCalls, f.calls.stream().filter(c -> c.path().endsWith("/identities")).count(), "an identity id is used as it is");
            Map<String, Object> both = list(f.last("PATCH", points)).get(0);
            assertEquals("passed", HttpJson.path(both, "results.outcome"));
            assertEquals(ANA_ID, HttpJson.path(both, "tester.id"));

            az.updateTestPoint(ctx, org, TOKEN, "Finance Portal", 5, 6, List.of(11), "failed");
            assertFalse(list(f.last("PATCH", points)).get(0).containsKey("tester"));

            fails("AZURE_DEVOPS.NOTHING_TO_UPDATE", () -> az.updateTestPoint(ctx, org, TOKEN, "Finance Portal", 5, 6, List.of(11), null, " "));
            fails("AZURE_DEVOPS.BAD_OUTCOME", () -> az.updateTestPoint(ctx, org, TOKEN, "Finance Portal", 5, 6, List.of(11), "great", null));
            fails("AZURE_DEVOPS.TESTER_NOT_FOUND", () -> az.updateTestPoint(ctx, org, TOKEN, "Finance Portal", 5, 6, List.of(11), null, "nobody@contoso.com"));
            fails("AZURE_DEVOPS.AMBIGUOUS_TESTER", () -> az.updateTestPoint(ctx, org, TOKEN, "Finance Portal", 5, 6, List.of(11), null, "Bo"));
            noSecretInLog(log);
        }
    }

    @Test void identityServiceAddress() {
        assertEquals("https://vssps.dev.azure.com/contoso", AzureDevOpsPackage.identityBase("https://dev.azure.com/contoso/"));
        assertEquals("https://contoso.vssps.visualstudio.com", AzureDevOpsPackage.identityBase("https://contoso.visualstudio.com"));
        assertEquals("https://devops.contoso.local/tfs/DefaultCollection", AzureDevOpsPackage.identityBase("https://devops.contoso.local/tfs/DefaultCollection"));
    }

    @Test void testPlanOwnerFilterAndMaxResults() throws Exception {
        try (Fake f = new Fake()) {
            String org = f.url() + "/acme";
            f.on("GET " + PRJ + "/_apis/testplan/plans", c -> ok("{\"value\":[{\"id\":31,\"name\":\"R3.2\"},{\"id\":32,\"name\":\"R3.3\"},{\"id\":33,\"name\":\"R3.4\"}]}"));
            ActionContext ctx = ctx(tmp(), log);
            Map<String, Object> r = az.listTestPlans(ctx, org, TOKEN, "Finance Portal", true, "ana@contoso.com", 2);
            assertEquals(new BigDecimal(2), r.get("count"), "max_results caps the rows");
            String query = q(f.last("GET", PRJ + "/_apis/testplan/plans"));
            assertTrue(query.contains("filterActivePlans=true") && query.contains("owner=ana@contoso.com") && query.contains("$top=2"), query);

            assertEquals(new BigDecimal(3), az.listTestPlans(ctx, org, TOKEN, "Finance Portal", false).get("count"));
            assertFalse(q(f.last("GET", PRJ + "/_apis/testplan/plans")).contains("owner="), "no owner filter by default");
        }
    }

    @Test void testPointsByTestCaseWithMaxResults() throws Exception {
        try (Fake f = new Fake()) {
            String org = f.url() + "/acme";
            String path = PRJ + "/_apis/testplan/Plans/31/Suites/32/TestPoint";
            f.on("GET " + path, c -> ok("{\"value\":[{\"id\":1,\"testCaseReference\":{\"id\":101}},{\"id\":2,\"testCaseReference\":{\"id\":101}}]}"));
            ActionContext ctx = ctx(tmp(), log);
            Map<String, Object> r = az.listTestPoints(ctx, org, TOKEN, "Finance Portal", 31, 32, 101, 1);
            assertEquals(new BigDecimal(1), r.get("count"));
            String query = q(f.last("GET", path));
            assertTrue(query.contains("testCaseId=101") && query.contains("$top=1"), query);

            assertEquals(new BigDecimal(2), az.listTestPoints(ctx, org, TOKEN, "Finance Portal", 31, 32).get("count"));
            assertFalse(q(f.last("GET", path)).contains("testCaseId"), "all points by default");
        }
    }

    @Test void updateQuerySuite() throws Exception {
        try (Fake f = new Fake()) {
            String org = f.url() + "/acme";
            String path = PRJ + "/_apis/testplan/Plans/31/suites/40";
            f.on("PATCH " + path, c -> ok("{\"id\":40}"));
            ActionContext ctx = ctx(tmp(), log);
            String wiql = "SELECT [System.Id] FROM WorkItems WHERE [System.Tags] CONTAINS 'smoke'";
            az.updateTestSuite(ctx, org, TOKEN, "Finance Portal", 31, 40, null, null, wiql);
            assertEquals(Map.of("queryString", wiql), json(f.last("PATCH", path)));
            az.updateTestSuite(ctx, org, TOKEN, "Finance Portal", 31, 40, "Smoke", null);
            assertEquals(Map.of("name", "Smoke"), json(f.last("PATCH", path)), "earlier signature unchanged");
            int before = f.calls.size();
            fails("AZURE_DEVOPS.NOTHING_TO_UPDATE", () -> az.updateTestSuite(ctx, org, TOKEN, "Finance Portal", 31, 40, null, null, " "));
            assertEquals(before, f.calls.size());
        }
    }

    @Test void updateWorkItemSteps() throws Exception {
        try (Fake f = new Fake()) {
            String org = f.url() + "/acme";
            f.on("PATCH /acme/_apis/wit/workitems/4711", c -> ok("{\"id\":4711,\"rev\":5}"));
            ActionContext ctx = ctx(tmp(), log);
            List<Object> steps = List.of("Sign in", Map.of("action", "Open the invoice", "expected", "Total shows 120.50"));
            Map<String, Object> r = az.updateWorkItem(ctx, org, TOKEN, 4711, null, null, null, null, null, null, null, steps);
            assertEquals(new BigDecimal(5), r.get("rev"));
            List<Map<String, Object>> ops = list(f.last("PATCH", "/acme/_apis/wit/workitems/4711"));
            assertEquals(1, ops.size());
            assertEquals("/fields/Microsoft.VSTS.TCM.Steps", ops.get(0).get("path"));
            assertEquals(AzureDevOpsPackage.stepsXml(steps), ops.get(0).get("value"), "same steps XML as Create test case");
            assertTrue(String.valueOf(ops.get(0).get("value")).contains("Total shows 120.50"));

            az.updateWorkItem(ctx, org, TOKEN, 4711, "New title", null, null, null, null, null, null);
            assertEquals(List.of("/fields/System.Title"), list(f.last("PATCH", "/acme/_apis/wit/workitems/4711")).stream().map(o -> o.get("path")).toList(), "earlier signature unchanged");
            fails("AZURE_DEVOPS.NOTHING_TO_UPDATE", () -> az.updateWorkItem(ctx, org, TOKEN, 4711, null, null, null, null, null, null, null, List.of()));
        }
    }
}
