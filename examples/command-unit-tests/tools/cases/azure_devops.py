"""Azure DevOps cases: a saved connection chosen by Connect, then work items, repos, pipelines, builds, test plans and
sprints against the stand-in (made-up organization utorg, project "UT Portal")."""
from helpers_business import drop_samples, cred, url, vars_

TK = "ooa.azure_devops/"
ORG = url("azure_devops", "/utorg")
PROJ = "UT Portal"
REPO = "ut-portal-api"
REPO_ID = "6f1c0a52-0000-4000-8000-00000000a001"
SPRINT = "a1b2c3d4-0000-4000-8000-000000000102"
CONN = "ut_azure_devops"
# the saved connection the Connect step chooses: written to the run folder (connections.json) by the first case
CONN_JSON = ('{"' + CONN + '": {"package": "ooa.azure_devops", "values": {"organization_url": "' + ORG + '"}, '
             '"credentials": {"personal_access_token": "unit_tests/azure_devops/token"}}}')


def add(ua):
    drop_samples(ua)
    vars_(ua, "ado_conn", "ado_table", "ado_count", "ado_project", "ado_id", "ado_url", "ado_wi", "ado_state", "ado_rev",
          "ado_ids", "ado_comment", "ado_att", "ado_pr", "ado_prd", "ado_status", "ado_merge", "ado_thread", "ado_plan",
          "ado_root", "ado_planinfo", "ado_suite", "ado_suiteinfo", "ado_tc", "ado_points", "ado_point", "ado_run",
          "ado_runinfo", "ado_updated", "ado_pipeline", "ado_pipeinfo", "ado_result", "ado_build", "ado_buildno",
          "ado_buildinfo", "ado_n")

    def act(f, cmd, desc, **args):
        s = f.act(TK + cmd, desc=desc, **args)
        if "organization_url" not in args and cmd not in ("connect", "disconnect"):
            s["connection"] = CONN          # address and token come from the saved connection
        return s

    def new_item(f, title="UT work item", **more):
        act(f, "create_work_item", "Create a work item for this case", project=PROJ, type="Task", title=title,
            out={"id": "ado_id"}, **more)

    def new_plan(f):
        act(f, "create_test_plan", "Create a test plan for this case", project=PROJ, name="UT regression plan",
            out={"plan_id": "ado_plan", "root_suite_id": "ado_root"})

    def new_case_in_root(f):
        new_plan(f)
        act(f, "create_test_case", "Create a test case in the plan's root suite", project=PROJ, title="UT login works",
            steps=[{"action": "Open the portal", "expected": "Sign-in page shows"}], plan_id="$ado_plan$",
            suite_id="$ado_root$", out={"id": "ado_tc"})

    def points_of(f):
        act(f, "list_test_points", "Find the test case's points", project=PROJ, plan_id="$ado_plan$", suite_id="$ado_root$",
            out={"table": "ado_table", "count": "ado_count"})
        f.expr("ado_point", "$ado_table.rows[0][0]$", desc="The first point's id")

    # ------------------------------------------------------------------ steps with their own address and token
    with ua.case(TK + "list_projects", "with the step's own address and token",
                 "List projects reads the projects of the organization. This step gives its own organization address "
                 "and personal access token from the vault. Checks the one made-up project is listed.") as f:
        act(f, "list_projects", "List the projects", organization_url=ORG, personal_access_token=cred("azure_devops"),
            out={"table": "ado_table", "count": "ado_count"})
        ua.check_eq("ado_count", 1)

    # ------------------------------------------------------------------ Connect: later steps use the saved connection
    with ua.case(TK + "connect", "choose a saved connection",
                 "Connect picks a saved connection (address plus vault token) for the later Azure DevOps steps, which "
                 "then leave address and token empty. The case writes the connection file of the run folder first. "
                 "Checks the connection name comes back.") as f:
        f.act("ooa.file/write_text", desc="Save the connection for this run", path="connections.json", text=CONN_JSON)
        act(f, "connect", "Use the unit-test connection", connection=CONN, out={"connection": "ado_conn"})
        ua.check_eq("ado_conn", CONN)

    with ua.case(TK + "get_project", "project details through the connection",
                 "Get project reads one project; address and token come from the connection. Checks its default team.") as f:
        act(f, "get_project", "Read the project", project=PROJ, out={"project": "ado_project"})
        ua.check("eq", "$ado_project.default_team$", "UT Portal Team", what="the default team")

    # ------------------------------------------------------------------ work items
    with ua.case(TK + "create_work_item", "a bug with tags and a parent",
                 "Create work item adds a Bug with description, tags, priority and a parent link. Checks the new id and "
                 "web address.") as f:
        act(f, "create_work_item", "Create a bug", project=PROJ, type="Bug", title="UT totals wrong", description="Seen by the agent",
            assigned_to="ana.lima@ut.example", tags="ut; agent", parent_id=4101, fields={"Microsoft.VSTS.Common.Priority": 1},
            out={"id": "ado_id", "url": "ado_url"})
        ua.check("gt", "$ado_id$", 5000, what="the new id")
        ua.check("contains", "$ado_url$", "_workitems/edit/", what="the web address")

    with ua.case(TK + "get_work_item", "read a seeded story",
                 "Get work item reads a work item by id. Checks its state, title and assignee.") as f:
        act(f, "get_work_item", "Read work item 4101", id=4101, out={"work_item": "ado_wi", "state": "ado_state"})
        ua.check_eq("ado_state", "Active")
        ua.check("eq", "$ado_wi.title$", "Export invoices to PDF", what="the title")
        ua.check("eq", "$ado_wi.assigned_to$", "Ana Lima", what="the assignee")

    with ua.case(TK + "update_work_item", "resolve a new task",
                 "Update work item changes only the fields given. The case creates a task, resolves it and reads it "
                 "back. Checks the revision went up and the state is Resolved.") as f:
        new_item(f)
        act(f, "update_work_item", "Resolve it", id="$ado_id$", state="Resolved", tags="done", out={"rev": "ado_rev"})
        ua.check_eq("ado_rev", 2)
        act(f, "get_work_item", "Read it back", id="$ado_id$", out={"state": "ado_state"})
        ua.check_eq("ado_state", "Resolved")

    with ua.case(TK + "delete_work_item", "move a task to the Recycle Bin",
                 "Delete work item moves a work item to the Recycle Bin. Checks reading it afterwards fails.") as f:
        new_item(f, "UT to delete")
        act(f, "delete_work_item", "Delete it", id="$ado_id$")
        ua.expect_error("AZURE_DEVOPS.*", lambda ff: act(ff, "get_work_item", "Read the deleted item", id="$ado_id$",
                                                          out={"state": "ado_state"}))

    with ua.case(TK + "query_work_items", "WIQL by tag",
                 "Query work items runs a WIQL query and reads the main fields of what it finds. Checks both seeded "
                 "items tagged ut-seed are found.") as f:
        act(f, "query_work_items", "Find items tagged ut-seed", project=PROJ,
            wiql="SELECT [System.Id] FROM WorkItems WHERE [System.Tags] CONTAINS 'ut-seed'",
            extra_fields=["Microsoft.VSTS.Common.Priority"], out={"table": "ado_table", "ids": "ado_ids", "count": "ado_count"})
        ua.check_eq("ado_count", 2)
        f.expr("ado_n", "len($ado_ids$)", desc="How many ids came back")
        ua.check_eq("ado_n", 2)

    with ua.case(TK + "add_work_item_comment", "comment on a new task",
                 "Add work item comment adds to a work item's discussion. Checks the comment id.") as f:
        new_item(f)
        act(f, "add_work_item_comment", "Comment on it", project=PROJ, id="$ado_id$", text="Checked by the agent.",
            out={"comment_id": "ado_comment"})
        ua.check_eq("ado_comment", 1)

    with ua.case(TK + "list_work_item_comments", "two comments, newest first",
                 "List work item comments reads a work item's discussion. The case adds two comments. Checks both are listed.") as f:
        new_item(f)
        act(f, "add_work_item_comment", "First comment", project=PROJ, id="$ado_id$", text="One")
        act(f, "add_work_item_comment", "Second comment", project=PROJ, id="$ado_id$", text="Two")
        act(f, "list_work_item_comments", "List the comments", project=PROJ, id="$ado_id$", out={"table": "ado_table", "count": "ado_count"})
        ua.check_eq("ado_count", 2)

    with ua.case(TK + "attach_file_to_work_item", "attach a log file",
                 "Attach file to work item uploads a local file and links it to the work item. Checks the file's address.") as f:
        f.act("ooa.file/write_text", desc="Prepare the file", path="ut_ado_error.log", text="error 42 at step 3\n")
        new_item(f)
        act(f, "attach_file_to_work_item", "Attach the log", project=PROJ, id="$ado_id$", path="ut_ado_error.log",
            comment="Run log", out={"attachment_url": "ado_att"})
        ua.check("contains", "$ado_att$", "fileName=ut_ado_error.log", what="the attachment address")

    # ------------------------------------------------------------------ repos and pull requests
    with ua.case(TK + "list_repositories", "repositories of the project",
                 "List repositories reads the Git repositories of a project. Checks the one repository.") as f:
        act(f, "list_repositories", "List repositories", project=PROJ, out={"table": "ado_table", "count": "ado_count"})
        ua.check_eq("ado_count", 1)

    with ua.case(TK + "list_pull_requests", "completed ones into release/2026",
                 "List pull requests filters by status and target branch. Checks the one completed pull request into "
                 "release/2026.") as f:
        act(f, "list_pull_requests", "List completed pull requests", project=PROJ, repository=REPO, status="completed",
            target_branch="release/2026", out={"table": "ado_table", "count": "ado_count"})
        ua.check_eq("ado_count", 1)

    with ua.case(TK + "get_pull_request", "read a seeded pull request",
                 "Get pull request reads one pull request. Checks its status and source branch.") as f:
        act(f, "get_pull_request", "Read pull request 31", project=PROJ, repository=REPO, pull_request_id=31,
            out={"pull_request": "ado_prd", "status": "ado_status"})
        ua.check_eq("ado_status", "active")
        ua.check("eq", "$ado_prd.source_branch$", "refs/heads/feature/ut-release", what="the source branch")

    with ua.case(TK + "create_pull_request", "draft with a linked work item",
                 "Create pull request proposes merging a branch, as a draft, linking a work item. Checks the new "
                 "pull request is active and a draft.") as f:
        act(f, "create_pull_request", "Propose feature/ut-vat into main", project=PROJ, repository=REPO,
            source_branch="feature/ut-vat", target_branch="main", title="UT VAT change", description="By the agent",
            draft=True, work_item_ids=[4101], out={"pull_request_id": "ado_pr"})
        act(f, "get_pull_request", "Read it back", project=PROJ, repository=REPO, pull_request_id="$ado_pr$",
            out={"pull_request": "ado_prd", "status": "ado_status"})
        ua.check_eq("ado_status", "active")
        ua.check("eq", "$ado_prd.is_draft$", True, what="draft")

    with ua.case(TK + "complete_pull_request", "squash-merge a new pull request",
                 "Complete pull request merges an active pull request. The case creates one first. Checks it is "
                 "completed and the merge succeeded.") as f:
        act(f, "create_pull_request", "Propose a branch", project=PROJ, repository=REPO, source_branch="feature/ut-merge",
            target_branch="main", title="UT merge me", out={"pull_request_id": "ado_pr"})
        act(f, "complete_pull_request", "Merge it", project=PROJ, repository=REPO, pull_request_id="$ado_pr$",
            merge_strategy="squash", delete_source_branch=True, merge_commit_message="UT merge",
            out={"status": "ado_status", "merge_status": "ado_merge"})
        ua.check_eq("ado_status", "completed")
        ua.check_eq("ado_merge", "succeeded")

    with ua.case(TK + "complete_pull_request", "a completed one is refused",
                 "Only active pull requests can be completed: pull request 32 is completed already, so the step fails "
                 "with AZURE_DEVOPS.NOT_ACTIVE.") as f:
        ua.expect_error("AZURE_DEVOPS.NOT_ACTIVE", lambda ff: act(ff, "complete_pull_request", "Try to merge pull request 32",
                                                                  project=PROJ, repository=REPO, pull_request_id=32))

    with ua.case(TK + "add_pull_request_comment", "start a thread",
                 "Comment on pull request starts a comment thread. Checks the thread id.") as f:
        act(f, "add_pull_request_comment", "Comment on pull request 31", project=PROJ, repository=REPO, pull_request_id=31,
            text="Looks good to the agent.", out={"thread_id": "ado_thread"})
        ua.check("gt", "$ado_thread$", 0, what="the thread id")

    # ------------------------------------------------------------------ pipelines
    with ua.case(TK + "list_pipelines", "pipelines of the project",
                 "List pipelines reads the project's pipelines. Checks at least the seeded one is there.") as f:
        act(f, "list_pipelines", "List pipelines", project=PROJ, max_results=50, out={"table": "ado_table", "count": "ado_count"})
        ua.check("gt", "$ado_count$", 0, what="the number of pipelines")

    with ua.case(TK + "list_pipeline_runs", "only failed runs",
                 "List pipeline runs reads a pipeline's runs, here only the failed ones. Checks the one failed run.") as f:
        act(f, "list_pipeline_runs", "List failed runs of pipeline 12", project=PROJ, pipeline_id=12, result="failed",
            out={"table": "ado_table", "count": "ado_count"})
        ua.check_eq("ado_count", 1)

    with ua.case(TK + "get_pipeline", "details of pipeline 12",
                 "Get pipeline details reads a pipeline's name, YAML file and repository. Checks the YAML path.") as f:
        act(f, "get_pipeline", "Read pipeline 12", project=PROJ, pipeline_id=12, out={"pipeline": "ado_pipeinfo"})
        ua.check("eq", "$ado_pipeinfo.yaml_path$", "/azure-pipelines.yml", what="the YAML path")

    with ua.case(TK + "create_pipeline", "from a repository named, not its id",
                 "Create pipeline makes a YAML pipeline from a file in a repository; the repository can be given by "
                 "name and is looked up to its id. Checks the new pipeline points at the repository's id.") as f:
        act(f, "create_pipeline", "Create ut-nightly", project=PROJ, name="ut-nightly", repository_id=REPO,
            yaml_path="/pipelines/nightly.yml", folder="\\UT", out={"pipeline_id": "ado_pipeline"})
        act(f, "get_pipeline", "Read it back", project=PROJ, pipeline_id="$ado_pipeline$", out={"pipeline": "ado_pipeinfo"})
        ua.check("eq", "$ado_pipeinfo.repository$", REPO_ID, what="the repository id")

    with ua.case(TK + "update_pipeline", "rename and point at another YAML file",
                 "Edit pipeline renames a pipeline and points it at another YAML file. The case creates one first. "
                 "Checks the new name and YAML path.") as f:
        act(f, "create_pipeline", "Create a pipeline to edit", project=PROJ, name="ut-edit-me", repository_id=REPO_ID,
            yaml_path="/a.yml", out={"pipeline_id": "ado_pipeline"})
        act(f, "update_pipeline", "Rename it and change its file", project=PROJ, pipeline_id="$ado_pipeline$",
            name="ut-edited", yaml_path="/b.yml")
        act(f, "get_pipeline", "Read it back", project=PROJ, pipeline_id="$ado_pipeline$", out={"pipeline": "ado_pipeinfo"})
        ua.check("eq", "$ado_pipeinfo.name$", "ut-edited", what="the name")
        ua.check("eq", "$ado_pipeinfo.yaml_path$", "/b.yml", what="the YAML path")

    with ua.case(TK + "delete_pipeline", "delete with confirm",
                 "Delete pipeline removes a pipeline when confirm is true. Checks reading it afterwards fails.") as f:
        act(f, "create_pipeline", "Create a pipeline to delete", project=PROJ, name="ut-delete-me", repository_id=REPO_ID,
            yaml_path="/c.yml", out={"pipeline_id": "ado_pipeline"})
        act(f, "delete_pipeline", "Delete it", project=PROJ, pipeline_id="$ado_pipeline$", confirm=True)
        ua.expect_error("AZURE_DEVOPS.*", lambda ff: act(ff, "get_pipeline", "Read the deleted pipeline", project=PROJ,
                                                          pipeline_id="$ado_pipeline$", out={"pipeline": "ado_pipeinfo"}))

    with ua.case(TK + "delete_pipeline", "confirm false keeps it",
                 "Without confirm the pipeline is not deleted: the step fails with AZURE_DEVOPS.CONFIRM_REQUIRED.") as f:
        ua.expect_error("AZURE_DEVOPS.CONFIRM_REQUIRED", lambda ff: act(ff, "delete_pipeline", "Delete without confirm",
                                                                        project=PROJ, pipeline_id=12, confirm=False))

    with ua.case(TK + "run_pipeline", "on a branch, skipping a stage",
                 "Run pipeline starts a run with parameters and variables, skipping a stage. Checks it is in progress "
                 "and has an address.") as f:
        act(f, "run_pipeline", "Run pipeline 12", project=PROJ, pipeline_id=12, branch="main",
            parameters={"environment": "test"}, variables={"releaseNote": "UT"}, stages_to_skip=["DeployProd"],
            out={"run_id": "ado_run", "state": "ado_state", "url": "ado_url"})
        ua.check_eq("ado_state", "inProgress")
        ua.check("contains", "$ado_url$", "buildId=", what="the run address")

    with ua.case(TK + "get_pipeline_run", "a started run finishes",
                 "Get pipeline run reads a run's state and result. The case starts a run and reads it twice (the "
                 "stand-in finishes it after the first read). Checks completed and succeeded.") as f:
        act(f, "run_pipeline", "Start a run", project=PROJ, pipeline_id=12, out={"run_id": "ado_run"})
        act(f, "get_pipeline_run", "First look", project=PROJ, pipeline_id=12, run_id="$ado_run$", out={"state": "ado_state"})
        ua.check_eq("ado_state", "inProgress")
        act(f, "get_pipeline_run", "Second look", project=PROJ, pipeline_id=12, run_id="$ado_run$",
            out={"state": "ado_state", "result": "ado_result", "run": "ado_runinfo"})
        ua.check_eq("ado_state", "completed")
        ua.check_eq("ado_result", "succeeded")

    ua.condition_case(TK + "pipeline_run_finished", "a completed run",
                      "Pipeline run finished is a condition: true once the run has completed, whatever its result. "
                      "Run 701 completed (failed).", {"organization_url": ORG, "personal_access_token": cred("azure_devops"), "project": PROJ, "pipeline_id": 12, "run_id": 701}, True)
    ua.condition_case(TK + "pipeline_run_finished", "a run still going",
                      "The same condition is false while the run is in progress (run 702).",
                      {"organization_url": ORG, "personal_access_token": cred("azure_devops"), "project": PROJ, "pipeline_id": 12, "run_id": 702}, False)

    # ------------------------------------------------------------------ builds
    with ua.case(TK + "queue_build", "queue definition 12 on main",
                 "Queue build queues a build of a definition with queue-time variables. Checks the build id and number.") as f:
        act(f, "queue_build", "Queue a build", project=PROJ, definition_id=12, branch="main",
            parameters={"Configuration": "Release"}, out={"build_id": "ado_build", "build_number": "ado_buildno", "url": "ado_url"})
        ua.check("gt", "$ado_build$", 5000, what="the build id")
        ua.check("contains", "$ado_buildno$", "20261123.", what="the build number")

    with ua.case(TK + "get_build", "a finished build",
                 "Get build reads a build's status and result. Checks build 900 completed and succeeded on main.") as f:
        act(f, "get_build", "Read build 900", project=PROJ, build_id=900,
            out={"status": "ado_status", "result": "ado_result", "build": "ado_buildinfo"})
        ua.check_eq("ado_status", "completed")
        ua.check_eq("ado_result", "succeeded")
        ua.check("eq", "$ado_buildinfo.definition$", "ut-portal-ci", what="the definition")

    with ua.case(TK + "list_builds", "only succeeded builds of definition 12",
                 "List builds filters by definition, branch, status and result. Checks the one succeeded build.") as f:
        act(f, "list_builds", "List succeeded builds", project=PROJ, definition_id=12, branch="main", status="completed",
            result="succeeded", out={"table": "ado_table", "count": "ado_count"})
        ua.check_eq("ado_count", 1)

    # ------------------------------------------------------------------ sprints
    with ua.case(TK + "list_iterations", "only the current sprint",
                 "List sprints reads a team's sprints, here only the one in progress. Checks one sprint.") as f:
        act(f, "list_iterations", "List the current sprint", project=PROJ, team="UT Portal Team", current_only=True,
            out={"table": "ado_table", "count": "ado_count"})
        ua.check_eq("ado_count", 1)

    with ua.case(TK + "list_iteration_work_items", "items planned in Sprint 2",
                 "List sprint work items gives the ids of the work items in a sprint. Checks the two seeded items.") as f:
        act(f, "list_iteration_work_items", "List Sprint 2's items", project=PROJ, iteration_id=SPRINT,
            out={"ids": "ado_ids", "count": "ado_count"})
        ua.check_eq("ado_count", 2)

    # ------------------------------------------------------------------ test plans
    with ua.case(TK + "create_test_plan", "with dates",
                 "Create test plan makes a plan (and its root suite) with a testing period. Checks both ids.") as f:
        act(f, "create_test_plan", "Create a plan", project=PROJ, name="UT release plan", plan_description="Made by the agent",
            area_path="UT Portal", iteration="UT Portal\\Sprint 2", start_date="2026-11-16", end_date="2026-11-27",
            out={"plan_id": "ado_plan", "root_suite_id": "ado_root"})
        ua.check("gt", "$ado_plan$", 5000, what="the plan id")
        ua.check("gt", "$ado_root$", "$ado_plan$", what="the root suite id")

    with ua.case(TK + "create_test_plan", "end before start is refused",
                 "The end date must not be before the start date: the step fails with AZURE_DEVOPS.BAD_DATES.") as f:
        ua.expect_error("AZURE_DEVOPS.BAD_DATES", lambda ff: act(ff, "create_test_plan", "Create a plan with wrong dates",
                                                                 project=PROJ, name="UT wrong", start_date="2026-11-27",
                                                                 end_date="2026-11-16"))

    with ua.case(TK + "get_test_plan", "read a new plan",
                 "Get test plan details reads one plan. Checks its name and root suite.") as f:
        new_plan(f)
        act(f, "get_test_plan", "Read the plan", project=PROJ, plan_id="$ado_plan$", out={"plan": "ado_planinfo"})
        ua.check("eq", "$ado_planinfo.name$", "UT regression plan", what="the name")
        ua.check("eq", "$ado_planinfo.root_suite_id$", "$ado_root$", what="the root suite")

    with ua.case(TK + "list_test_plans", "active plans owned by the agent",
                 "List test plans reads the project's plans, here only active ones of one owner. Checks the new plan "
                 "is among them.") as f:
        new_plan(f)
        act(f, "list_test_plans", "List active plans", project=PROJ, active_only=True, owner="ut.agent@ut.example",
            out={"table": "ado_table", "count": "ado_count"})
        ua.check("gt", "$ado_count$", 0, what="the number of plans")

    with ua.case(TK + "update_test_plan", "rename and make inactive",
                 "Edit test plan changes only what is given. Checks the new name and state.") as f:
        new_plan(f)
        act(f, "update_test_plan", "Rename and close the plan", project=PROJ, plan_id="$ado_plan$", name="UT plan (done)",
            state="Inactive", end_date="2026-12-31")
        act(f, "get_test_plan", "Read it back", project=PROJ, plan_id="$ado_plan$", out={"plan": "ado_planinfo"})
        ua.check("eq", "$ado_planinfo.name$", "UT plan (done)", what="the name")
        ua.check("eq", "$ado_planinfo.state$", "Inactive", what="the state")

    with ua.case(TK + "delete_test_plan", "delete with confirm",
                 "Delete test plan removes a plan and its suites when confirm is true. Checks reading it fails.") as f:
        new_plan(f)
        act(f, "delete_test_plan", "Delete the plan", project=PROJ, plan_id="$ado_plan$", confirm=True)
        ua.expect_error("AZURE_DEVOPS.*", lambda ff: act(ff, "get_test_plan", "Read the deleted plan", project=PROJ,
                                                          plan_id="$ado_plan$", out={"plan": "ado_planinfo"}))

    with ua.case(TK + "create_test_suite", "a requirement suite",
                 "Create test suite makes a suite inside the root suite; this one holds the tests of story 4101. "
                 "Checks its type and requirement.") as f:
        new_plan(f)
        act(f, "create_test_suite", "Create a requirement suite", project=PROJ, plan_id="$ado_plan$", parent_suite_id="$ado_root$",
            name="Story 4101", suite_type="requirement", requirement_id=4101, out={"suite_id": "ado_suite"})
        act(f, "get_test_suite", "Read it back", project=PROJ, plan_id="$ado_plan$", suite_id="$ado_suite$", out={"suite": "ado_suiteinfo"})
        ua.check("eq", "$ado_suiteinfo.suite_type$", "requirementTestSuite", what="the suite type")
        ua.check("eq", "$ado_suiteinfo.requirement_id$", 4101, what="the requirement")

    with ua.case(TK + "get_test_suite", "a query suite",
                 "Get test suite reads a suite, including a query-based suite's query. Checks the query and parent.") as f:
        new_plan(f)
        act(f, "create_test_suite", "Create a query suite", project=PROJ, plan_id="$ado_plan$", parent_suite_id="$ado_root$",
            name="Priority 1", suite_type="query", query="SELECT [System.Id] FROM WorkItems WHERE [Microsoft.VSTS.Common.Priority] = 1",
            out={"suite_id": "ado_suite"})
        act(f, "get_test_suite", "Read the suite", project=PROJ, plan_id="$ado_plan$", suite_id="$ado_suite$", out={"suite": "ado_suiteinfo"})
        ua.check("contains", "$ado_suiteinfo.query$", "Priority", what="the query")
        ua.check("eq", "$ado_suiteinfo.parent_suite_id$", "$ado_root$", what="the parent suite")

    with ua.case(TK + "update_test_suite", "rename a static suite",
                 "Edit test suite renames a suite. Checks the new name.") as f:
        new_plan(f)
        act(f, "create_test_suite", "Create a static suite", project=PROJ, plan_id="$ado_plan$", parent_suite_id="$ado_root$",
            name="Smoke", out={"suite_id": "ado_suite"})
        act(f, "update_test_suite", "Rename it", project=PROJ, plan_id="$ado_plan$", suite_id="$ado_suite$", name="Smoke tests")
        act(f, "get_test_suite", "Read it back", project=PROJ, plan_id="$ado_plan$", suite_id="$ado_suite$", out={"suite": "ado_suiteinfo"})
        ua.check("eq", "$ado_suiteinfo.name$", "Smoke tests", what="the name")

    with ua.case(TK + "delete_test_suite", "delete a static suite",
                 "Delete test suite removes a suite. Checks the plan has only its root suite left.") as f:
        new_plan(f)
        act(f, "create_test_suite", "Create a suite to delete", project=PROJ, plan_id="$ado_plan$", parent_suite_id="$ado_root$",
            name="Temporary", out={"suite_id": "ado_suite"})
        act(f, "delete_test_suite", "Delete it", project=PROJ, plan_id="$ado_plan$", suite_id="$ado_suite$")
        act(f, "list_test_suites", "List the plan's suites", project=PROJ, plan_id="$ado_plan$", out={"count": "ado_count"})
        ua.check_eq("ado_count", 1)

    with ua.case(TK + "list_test_suites", "root and one child",
                 "List test suites reads a plan's suites. Checks the root suite and the new one.") as f:
        new_plan(f)
        act(f, "create_test_suite", "Create a suite", project=PROJ, plan_id="$ado_plan$", parent_suite_id="$ado_root$",
            name="Regression", out={"suite_id": "ado_suite"})
        act(f, "list_test_suites", "List the suites", project=PROJ, plan_id="$ado_plan$", out={"table": "ado_table", "count": "ado_count"})
        ua.check_eq("ado_count", 2)

    with ua.case(TK + "create_test_case", "steps as a table, added to a suite",
                 "Create test case makes a Test Case work item with numbered steps (here from a table) and adds it to a "
                 "suite. Checks the work item type and that the suite holds it.") as f:
        new_plan(f)
        act(f, "create_test_case", "Create a test case", project=PROJ, title="UT invoice total",
            steps_table={"columns": ["action", "expected"], "rows": [["Open invoice 7", "Invoice shows"], ["Read the total", "120.50"]]},
            priority=1, automation_status="Planned", area_path="UT Portal", plan_id="$ado_plan$", suite_id="$ado_root$",
            out={"id": "ado_tc"})
        act(f, "get_work_item", "Read the test case", id="$ado_tc$", out={"work_item": "ado_wi"})
        ua.check("eq", "$ado_wi.type$", "Test Case", what="the work item type")
        ua.check("contains", "$ado_wi.fields['Microsoft.VSTS.TCM.Steps']$", "ValidateStep", what="the steps")
        act(f, "list_test_cases", "List the suite's test cases", project=PROJ, plan_id="$ado_plan$", suite_id="$ado_root$",
            out={"count": "ado_count"})
        ua.check_eq("ado_count", 1)

    with ua.case(TK + "add_test_cases_to_suite", "two test cases into a new suite",
                 "Add test cases to suite puts existing test cases into a static suite. Checks the count added and the "
                 "suite's test case count.") as f:
        new_case_in_root(f)
        act(f, "create_test_case", "A second test case", project=PROJ, title="UT logout works", out={"id": "ado_id"})
        act(f, "create_test_suite", "Create a static suite", project=PROJ, plan_id="$ado_plan$", parent_suite_id="$ado_root$",
            name="Session", out={"suite_id": "ado_suite"})
        act(f, "add_test_cases_to_suite", "Add both", project=PROJ, plan_id="$ado_plan$", suite_id="$ado_suite$",
            test_case_ids=["$ado_tc$", "$ado_id$"], out={"count": "ado_count"})
        ua.check_eq("ado_count", 2)
        act(f, "get_test_suite", "Read the suite", project=PROJ, plan_id="$ado_plan$", suite_id="$ado_suite$", out={"suite": "ado_suiteinfo"})
        ua.check("eq", "$ado_suiteinfo.test_case_count$", 2, what="the test case count")

    with ua.case(TK + "remove_test_cases_from_suite", "take a test case out",
                 "Remove test cases from suite takes test cases out of a suite; the work items stay. Checks the suite "
                 "is empty and the work item still reads.") as f:
        new_case_in_root(f)
        act(f, "remove_test_cases_from_suite", "Remove it", project=PROJ, plan_id="$ado_plan$", suite_id="$ado_root$",
            test_case_ids=["$ado_tc$"])
        act(f, "list_test_cases", "List the suite's test cases", project=PROJ, plan_id="$ado_plan$", suite_id="$ado_root$",
            out={"count": "ado_count"})
        ua.check_eq("ado_count", 0)
        act(f, "get_work_item", "The test case is still there", id="$ado_tc$", out={"state": "ado_state"})
        ua.check("notEmpty", "$ado_state$", what="the test case's state")

    with ua.case(TK + "list_test_cases", "with their points",
                 "List test cases reads a suite's test cases with their test points. Checks one case with one point.") as f:
        new_case_in_root(f)
        act(f, "list_test_cases", "List the test cases", project=PROJ, plan_id="$ado_plan$", suite_id="$ado_root$",
            out={"table": "ado_table", "count": "ado_count"})
        ua.check_eq("ado_count", 1)

    with ua.case(TK + "list_test_points", "points of one test case",
                 "List test points reads a suite's points with their latest outcome, here for one test case. Checks one "
                 "point.") as f:
        new_case_in_root(f)
        act(f, "list_test_points", "List the points", project=PROJ, plan_id="$ado_plan$", suite_id="$ado_root$",
            test_case_id="$ado_tc$", out={"table": "ado_table", "count": "ado_count"})
        ua.check_eq("ado_count", 1)

    with ua.case(TK + "update_test_point", "passed and assigned to a tester",
                 "Update test point sets the outcome of points and assigns a tester found by sign-in email. Checks one "
                 "point was updated.") as f:
        new_case_in_root(f)
        points_of(f)
        act(f, "update_test_point", "Mark it passed for Ana", project=PROJ, plan_id="$ado_plan$", suite_id="$ado_root$",
            point_ids=["$ado_point$"], outcome="passed", tester="ana.lima@ut.example", out={"count": "ado_count"})
        ua.check_eq("ado_count", 1)

    with ua.case(TK + "create_test_run", "a manual run for a point",
                 "Create test run starts a run for test points so results can be recorded. Checks the run id and address.") as f:
        new_case_in_root(f)
        points_of(f)
        act(f, "create_test_run", "Start a run", project=PROJ, plan_id="$ado_plan$", name="UT manual run",
            point_ids=["$ado_point$"], build_id=900, out={"run_id": "ado_run", "url": "ado_url"})
        ua.check("gt", "$ado_run$", 5000, what="the run id")
        ua.check("contains", "$ado_url$", "runId=", what="the run address")

    with ua.case(TK + "update_test_results", "record a pass by test point",
                 "Update test results records outcomes in a run; rows can name the test point instead of the result. "
                 "Checks one result was recorded.") as f:
        new_case_in_root(f)
        points_of(f)
        act(f, "create_test_run", "Start a run", project=PROJ, plan_id="$ado_plan$", name="UT results run",
            point_ids=["$ado_point$"], out={"run_id": "ado_run"})
        act(f, "update_test_results", "Record the pass", project=PROJ, run_id="$ado_run$",
            results={"columns": ["test_point_id", "outcome", "comment", "duration_seconds"], "rows": [["$ado_point$", "Passed", "OK", 3]]},
            out={"updated": "ado_updated"})
        ua.check_eq("ado_updated", 1)

    with ua.case(TK + "get_test_run", "counts after a pass",
                 "Get test run details reads a run with its counts per outcome. Checks one passed test.") as f:
        new_case_in_root(f)
        points_of(f)
        act(f, "create_test_run", "Start a run", project=PROJ, plan_id="$ado_plan$", name="UT counted run",
            point_ids=["$ado_point$"], out={"run_id": "ado_run"})
        act(f, "update_test_results", "Record the pass", project=PROJ, run_id="$ado_run$",
            results={"columns": ["test_point_id", "outcome"], "rows": [["$ado_point$", "Passed"]]})
        act(f, "get_test_run", "Read the run", project=PROJ, run_id="$ado_run$", out={"run": "ado_runinfo"})
        ua.check("eq", "$ado_runinfo.passed_tests$", 1, what="passed tests")
        ua.check("eq", "$ado_runinfo.outcomes.Passed$", 1, what="the Passed count")

    with ua.case(TK + "complete_test_run", "close a run with a comment",
                 "Complete test run marks a run completed. Checks the run's state afterwards.") as f:
        new_plan(f)
        act(f, "create_test_run", "Start an empty run", project=PROJ, plan_id="$ado_plan$", name="UT run to close",
            out={"run_id": "ado_run"})
        act(f, "complete_test_run", "Complete it", project=PROJ, run_id="$ado_run$", comment="Closed by the agent")
        act(f, "get_test_run", "Read the run", project=PROJ, run_id="$ado_run$", out={"run": "ado_runinfo"})
        ua.check("eq", "$ado_runinfo.state$", "Completed", what="the state")

    with ua.case(TK + "list_test_runs", "completed runs of a plan",
                 "List test runs reads the project's runs, here of one plan and only completed. The case completes one "
                 "run and leaves one open. Checks one run is listed.") as f:
        new_plan(f)
        act(f, "create_test_run", "An open run", project=PROJ, plan_id="$ado_plan$", name="UT open run")
        act(f, "create_test_run", "A run to complete", project=PROJ, plan_id="$ado_plan$", name="UT done run", out={"run_id": "ado_run"})
        act(f, "complete_test_run", "Complete it", project=PROJ, run_id="$ado_run$")
        act(f, "list_test_runs", "List completed runs of the plan", project=PROJ, plan_id="$ado_plan$", state="Completed",
            out={"table": "ado_table", "count": "ado_count"})
        ua.check_eq("ado_count", 1)

    # ------------------------------------------------------------------ Disconnect
    with ua.case(TK + "disconnect", "forget the connection",
                 "Disconnect forgets the connection Connect chose; later steps then use their own address and token. "
                 "Checks a step with its own address and token works afterwards.") as f:
        act(f, "disconnect", "Forget the connection")
        act(f, "list_repositories", "List with the step's own address and token", organization_url=ORG,
            personal_access_token=cred("azure_devops"), project=PROJ, out={"count": "ado_count"})
        ua.check_eq("ado_count", 1)
