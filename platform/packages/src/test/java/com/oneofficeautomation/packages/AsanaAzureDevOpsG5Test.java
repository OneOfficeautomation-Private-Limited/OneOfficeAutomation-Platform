package com.oneofficeautomation.packages;

import com.oneofficeautomation.packages.ConnectorsTest.Fake;
import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static com.oneofficeautomation.packages.ConnectorsTest.ok;
import static com.oneofficeautomation.packages.Microsoft365OptionsTest.q;
import static com.oneofficeautomation.packages.PackagesTest.tmp;
import static org.junit.jupiter.api.Assertions.*;

/** G5 (AA parity, 22 Nov 2026), Asana and Azure DevOps against a fake server. */
class AsanaAzureDevOpsG5Test {
    final SecureString tok = new SecureString("tok", "t");

    @Test void asanaSeveralProjectsTeamPeopleArchivedAndOwner() throws Exception {
        try (Fake f = new Fake()) {
            f.on("GET /projects/1/tasks", c -> ok("{\"data\":[{\"gid\":\"11\",\"name\":\"A\"}]}"))
             .on("GET /projects/2/tasks", c -> ok("{\"data\":[{\"gid\":\"21\",\"name\":\"B\"}]}"))
             .on("GET /teams/7/users", c -> ok("{\"data\":[{\"gid\":\"U1\",\"name\":\"Ann\",\"email\":\"ann@x.com\"}]}"))
             .on("GET /projects", c -> ok("{\"data\":[]}"))
             .on("POST /projects", c -> ok("{\"data\":{\"gid\":\"P9\"}}"));
            var a = new AsanaPackage();
            var ctx = PackagesTestSupport.ctx(tmp(), Map.of());
            assertEquals(new BigDecimal(2), a.listTasks(ctx, f.url(), tok, null, null, null, null, false, null, 100, List.of("1", "2"), "2026-10-01T00:00:00Z").get("count"));
            assertTrue(q(f.last("GET", "/projects/2/tasks")).contains("completed_since=2026-10-01T00:00:00Z"));
            assertEquals(new BigDecimal(1), a.listUsers(ctx, f.url(), tok, null, 100, "7").get("count"));
            a.listProjects(ctx, f.url(), tok, "1208000000000001", null, false, 100, "all");
            assertFalse(q(f.last("GET", "/projects")).contains("archived="), "all: no archived filter");
            a.createProject(ctx, f.url(), tok, "1208000000000001", "Close", null, null, "me", "2027-03-31");
            Map<String, Object> body = f.last("POST", "/projects").json();
            assertEquals("me", ((Map<?, ?>) body.get("data")).get("owner"));
            assertEquals("2027-03-31", ((Map<?, ?>) body.get("data")).get("due_on"));
        }
    }

    @SuppressWarnings("unchecked")
    @Test void azureDevOpsSuiteRunOutcomesStepsTableAndRepoByName() throws Exception {
        try (Fake f = new Fake()) {
            f.on("GET /Proj/_apis/testplan/Plans/5/suites/9", c -> ok("{\"id\":9,\"name\":\"Smoke\",\"suiteType\":\"staticTestSuite\",\"testCaseCount\":4,\"parentSuite\":{\"id\":1}}"))
             .on("GET /Proj/_apis/test/runs/3", c -> ok("{\"id\":3,\"state\":\"Completed\",\"totalTests\":3}"))
             .on("GET /Proj/_apis/test/runs/3/Statistics", c -> ok("{\"runStatistics\":[{\"outcome\":\"Passed\",\"count\":2,\"state\":\"Completed\"},{\"outcome\":\"Timeout\",\"count\":1,\"state\":\"Completed\"}]}"))
             .on("GET /Proj/_apis/git/repositories", c -> ok("{\"value\":[{\"id\":\"1f0e2d3c-4b5a-6978-8a9b-0c1d2e3f4a5b\",\"name\":\"invoices\"}]}"))
             .on("POST /Proj/_apis/pipelines", c -> ok("{\"id\":42}"))
             .on("POST /Proj/_apis/wit/workitems/.*", c -> ok("{\"id\":77}"));
            var z = new AzureDevOpsPackage();
            var ctx = PackagesTestSupport.ctx(tmp(), Map.of());
            Map<String, Object> suite = (Map<String, Object>) z.getTestSuite(ctx, f.url(), tok, "Proj", 5, 9).get("suite");
            assertEquals("Smoke", suite.get("name"));
            assertEquals(4, ((Number) suite.get("test_case_count")).intValue());
            Map<String, Object> run = (Map<String, Object>) z.getTestRun(ctx, f.url(), tok, "Proj", 3).get("run");
            assertEquals(Map.of("Passed", new BigDecimal(2), "Timeout", new BigDecimal(1)), run.get("outcomes"));
            assertEquals(42, ((Number) z.createPipeline(ctx, f.url(), tok, "Proj", "CI", "invoices", "/ci.yml", null).get("pipeline_id")).intValue());
            assertTrue(new String(f.last("POST", "/Proj/_apis/pipelines").body()).contains("1f0e2d3c-4b5a-6978-8a9b-0c1d2e3f4a5b"), "the name became the id");
            Map<String, Object> steps = Values.table(List.of("action", "expected"), List.of(List.of("Open the invoice", "Total shows 120.50")));
            z.createTestCase(ctx, f.url(), tok, "Proj", "Invoice total", null, null, null, null, null, null, 2, null, steps);
            String sent = new String(f.last("POST", "/Proj/_apis/wit/workitems/.*").body());
            assertTrue(sent.contains("Open the invoice") && sent.contains("Total shows 120.50"), sent);
        }
    }
}
