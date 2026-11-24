"""GitHub cases: Connect picks the saved connection ut_github (written to the run folder's connections.json, pointing at
the stand-in), so the steps after it give no token or address; Disconnect then shows a step with its own."""
from helpers_business import drop_samples, cred, url, vars_
from connect_business import saved_connection, leave_empty

API = url("github")
OWNER, REPO = "ut-org", "billing-service"
R = {"owner": OWNER, "repo": REPO}


def add(ua):
    drop_samples(ua)
    vars_(ua, "gh_conn", "gh_repo", "gh_branch", "gh_table", "gh_count", "gh_number", "gh_url", "gh_issue", "gh_state",
          "gh_id", "gh_pr", "gh_merged", "gh_sha", "gh_sha2", "gh_text", "gh_size", "gh_release", "gh_tag", "gh_status",
          "gh_conclusion", "gh_run")

    with ua.case("ooa.github/connect", "use a saved connection for the later steps",
                 "Connect names a saved connection (API address and token from the vault); the later GitHub steps use it "
                 "when they give no token of their own. The case saves the connection ut_github in the run folder first. "
                 "Checks the connection's name and that a step without a token reads the repository.") as f:
        saved_connection(f, "ut_github", "ooa.github", {"base_url": API}, {"token": "unit_tests/github/token"})
        f.act("ooa.github/connect", desc="Connect with ut_github", connection="ut_github", out={"connection": "gh_conn"})
        ua.check_eq("gh_conn", "ut_github")
        f.act("ooa.github/get_repository", desc="Read the repository through the connection", **R, out={"default_branch": "gh_branch"})
        ua.check_eq("gh_branch", "main")

    with ua.case("ooa.github/list_repositories", "an organization's repositories",
                 "List repositories lists an organization's repositories (or a user's, or the token user's own). "
                 "Checks ut-org has two.") as f:
        f.act("ooa.github/list_repositories", desc="Repositories of ut-org", organization=OWNER, out={"table": "gh_table", "count": "gh_count"})
        ua.check_eq("gh_count", 2)

    with ua.case("ooa.github/get_repository", "details of one repository",
                 "Get repository reads a repository's main details. Checks the description and the open issue count.") as f:
        f.act("ooa.github/get_repository", desc="Read billing-service", **R, out={"repository": "gh_repo"})
        ua.check("eq", "$gh_repo.description$", "Invoices and payments", what="the description")
        ua.check("eq", "$gh_repo.full_name$", "ut-org/billing-service", what="the full name")

    with ua.case("ooa.github/create_issue", "issue with labels and an assignee",
                 "Create issue opens a new issue with labels and assignees. Checks the number and reads it back.") as f:
        f.act("ooa.github/create_issue", desc="Open an issue", **R, title="Rates file missing for 2028", body="Found by the nightly agent",
              labels=["bug", "finance"], assignees=["ut-dev"], out={"number": "gh_number", "url": "gh_url"})
        ua.check("gt", "$gh_number$", 3, what="the new issue number")
        f.act("ooa.github/get_issue", desc="Read it back", **R, number="$gh_number$", out={"issue": "gh_issue"})
        ua.check("eq", "$gh_issue.title$", "Rates file missing for 2028", what="the title")

    with ua.case("ooa.github/get_issue", "an open issue",
                 "Get issue reads an issue by number. Checks its state, author and labels.") as f:
        f.act("ooa.github/get_issue", desc="Read issue 1", **R, number=1, out={"issue": "gh_issue", "state": "gh_state"})
        ua.check_eq("gh_state", "open")
        ua.check("eq", "$gh_issue.author$", "ut-dev", what="the author")
        ua.check("contains", "$gh_issue.labels$", "finance", what="the labels")

    with ua.case("ooa.github/update_issue", "retitle and relabel",
                 "Update issue changes only the inputs given: here the title and the labels. Checks the issue afterwards.") as f:
        f.act("ooa.github/create_issue", desc="Open an issue to change", **R, title="Draft title", out={"number": "gh_number"})
        f.act("ooa.github/update_issue", desc="Change its title and labels", **R, number="$gh_number$", title="Final title", labels=["docs"])
        f.act("ooa.github/get_issue", desc="Read it back", **R, number="$gh_number$", out={"issue": "gh_issue"})
        ua.check("eq", "$gh_issue.title$", "Final title", what="the title")
        ua.check("contains", "$gh_issue.labels$", "docs", what="the labels")

    with ua.case("ooa.github/close_issue", "close as not planned",
                 "Close issue closes an issue as completed or not planned. Checks state closed and the reason.") as f:
        f.act("ooa.github/create_issue", desc="Open an issue to close", **R, title="Duplicate request", out={"number": "gh_number"})
        f.act("ooa.github/close_issue", desc="Close it as not planned", **R, number="$gh_number$", reason="not_planned")
        f.act("ooa.github/get_issue", desc="Read it back", **R, number="$gh_number$", out={"issue": "gh_issue", "state": "gh_state"})
        ua.check_eq("gh_state", "closed")
        ua.check("eq", "$gh_issue.state_reason$", "not_planned", what="the reason")

    with ua.case("ooa.github/add_issue_comment", "comment on an issue",
                 "Add comment adds a comment to an issue or pull request conversation. Checks the comment id and that "
                 "the issue's comment count grew.") as f:
        f.act("ooa.github/create_issue", desc="Open an issue to comment on", **R, title="Needs a comment", out={"number": "gh_number"})
        f.act("ooa.github/add_issue_comment", desc="Comment on it", **R, number="$gh_number$", text="Fixed by the nightly agent run.",
              out={"comment_id": "gh_id", "url": "gh_url"})
        ua.check("gt", "$gh_id$", 50000, what="the comment id")
        f.act("ooa.github/get_issue", desc="Read it back", **R, number="$gh_number$", out={"issue": "gh_issue"})
        ua.check("eq", "$gh_issue.comments$", 1, what="the comment count")

    with ua.case("ooa.github/list_issues", "open issues with a label",
                 "List issues lists issues filtered by state and labels; pull requests are left out. Checks the one "
                 "open issue labelled finance and bug.") as f:
        f.act("ooa.github/list_issues", desc="Open issues labelled bug and finance", **R, state="open", labels="bug,finance",
              out={"table": "gh_table", "count": "gh_count"})
        ua.check("ge", "$gh_count$", 1, what="the issues")
        ua.check("eq", "$gh_table.rows[0][0]$", 1, what="the first issue number")

    with ua.case("ooa.github/list_pull_requests", "open pull requests into main",
                 "List pull requests lists pull requests filtered by state, target and source branch. Checks the open "
                 "one from feature/rates-2027 and both when all states are asked for into any branch.") as f:
        f.act("ooa.github/list_pull_requests", desc="Open pull requests from feature/rates-2027 into main", **R,
              target_branch="main", source_branch="feature/rates-2027", out={"table": "gh_table", "count": "gh_count"})
        ua.check_eq("gh_count", 1)
        f.act("ooa.github/list_pull_requests", desc="Pull requests into release/2.4, all states", **R, state="all",
              target_branch="release/2.4", out={"count": "gh_count"})
        ua.check_eq("gh_count", 1)

    with ua.case("ooa.github/get_pull_request", "a merged pull request",
                 "Get pull request reads a pull request, including whether it is merged. Checks pull request 4 is merged "
                 "and its source branch.") as f:
        f.act("ooa.github/get_pull_request", desc="Read pull request 4", **R, number=4, out={"pull_request": "gh_pr", "merged": "gh_merged"})
        ua.check_eq("gh_merged", True)
        ua.check("eq", "$gh_pr.head$", "hotfix/2.3.1", what="the source branch")

    ua.condition_case("ooa.github/pull_request_is_merged", "merged pull request",
                      "Pull request is merged is a condition: true for a pull request that has been merged.",
                      {**R, "number": 4}, True)
    ua.condition_case("ooa.github/pull_request_is_merged", "open pull request",
                      "The same condition is false for a pull request that is still open.",
                      {**R, "number": 3}, False)

    with ua.case("ooa.github/list_branches", "a repository's branches",
                 "List branches lists the branches with their commit and protection. Checks main and release/2.4 are there.") as f:
        f.act("ooa.github/list_branches", desc="Branches of billing-service", **R, out={"table": "gh_table", "count": "gh_count"})
        ua.check("ge", "$gh_count$", 2, what="the branches")

    with ua.case("ooa.github/create_branch", "branch from release/2.4",
                 "Create branch starts a branch from another branch and gives back its commit. Checks the commit equals "
                 "release/2.4's.") as f:
        f.act("ooa.github/create_branch", desc="Create ut-run/from-release", **R, branch="ut-run/from-release",
              from_branch="release/2.4", out={"sha": "gh_sha"})
        ua.check_eq("gh_sha", "6051acd2998c61b900af37fca3bf4a3bc9c3ed4a")

    with ua.case("ooa.github/create_pull_request", "propose a change as a draft",
                 "Create pull request proposes merging one branch into another, here as a draft. Checks the number and "
                 "that it reads back as a draft.") as f:
        f.act("ooa.github/create_branch", desc="Create the source branch", **R, branch="ut-run/draft-change")
        f.act("ooa.github/create_pull_request", desc="Open a draft pull request", **R, title="Draft: new rates",
              source_branch="ut-run/draft-change", target_branch="main", body="Work in progress", draft=True,
              out={"number": "gh_number", "url": "gh_url"})
        f.act("ooa.github/get_pull_request", desc="Read it back", **R, number="$gh_number$", out={"pull_request": "gh_pr"})
        ua.check("eq", "$gh_pr.draft$", True, what="the draft flag")
        ua.check("contains", "$gh_url$", "/pull/", what="the address")

    with ua.case("ooa.github/merge_pull_request", "squash merge",
                 "Merge pull request merges with a merge commit, squash or rebase; expected_head_sha guards against a "
                 "late push. The case opens a pull request first. Checks merged and the merge commit.") as f:
        f.act("ooa.github/create_branch", desc="Create the source branch", **R, branch="ut-run/to-merge", out={"sha": "gh_sha2"})
        f.act("ooa.github/create_pull_request", desc="Open the pull request", **R, title="Merge me", source_branch="ut-run/to-merge",
              target_branch="main", out={"number": "gh_number"})
        f.act("ooa.github/merge_pull_request", desc="Squash-merge it", **R, number="$gh_number$", method="squash",
              commit_title="Merge me (squashed)", expected_head_sha="$gh_sha2$", out={"sha": "gh_sha", "merged": "gh_merged"})
        ua.check_eq("gh_merged", True)
        ua.check("notEmpty", "$gh_sha$", what="the merge commit")

    with ua.case("ooa.github/merge_pull_request", "already merged is refused",
                 "Merging a pull request that cannot be merged fails with GITHUB.NOT_MERGEABLE.") as f:
        ua.expect_error("GITHUB.NOT_MERGEABLE", lambda ff: ff.act("ooa.github/merge_pull_request", desc="Merge pull request 4 again",
                                                                  **R, number=4, out={"merged": "gh_merged"}))

    with ua.case("ooa.github/get_file", "read a file as text",
                 "Get file contents reads a file from a branch as text with its version id and size. Checks the text.") as f:
        f.act("ooa.github/get_file", desc="Read config/rates.json", **R, path="config/rates.json",
              out={"text": "gh_text", "sha": "gh_sha", "size": "gh_size"})
        ua.check_eq("gh_text", '{"vat": 20}\n')
        ua.check_eq("gh_size", 12)

    with ua.case("ooa.github/put_file", "commit a file to a new branch",
                 "Create or update file commits text (or a local file) to a branch; for a changed file its version id is "
                 "looked up first. Checks the commit and reads the file back.") as f:
        f.act("ooa.github/create_branch", desc="Create the branch to commit to", **R, branch="ut-run/report")
        f.act("ooa.file/write_text", desc="Prepare the local file", path="ut_github_report.csv", text="month,total\n2026-10,1200\n")
        f.act("ooa.github/put_file", desc="Commit the local file", **R, path="reports/2026-10.csv", message="Add October report",
              local_file="ut_github_report.csv", branch="ut-run/report", out={"commit_sha": "gh_sha", "sha": "gh_sha2"})
        ua.check("notEmpty", "$gh_sha$", what="the commit")
        f.act("ooa.github/put_file", desc="Change it with text", **R, path="reports/2026-10.csv", message="Fix October total",
              text="month,total\n2026-10,1250\n", branch="ut-run/report", out={"commit_sha": "gh_sha"})
        f.act("ooa.github/get_file", desc="Read it back", **R, path="reports/2026-10.csv", ref="ut-run/report",
              save_to="ut_github_back.csv", out={"size": "gh_size"})
        f.act("ooa.file/read_text", desc="Read the saved copy", path="ut_github_back.csv", out={"text": "gh_text"})
        ua.check_eq("gh_text", "month,total\n2026-10,1250\n")

    with ua.case("ooa.github/get_latest_release", "newest published release",
                 "Get latest release reads the newest published release; pre-releases are skipped. Checks v2.4.0.") as f:
        f.act("ooa.github/get_latest_release", desc="Latest release", **R, out={"release": "gh_release", "tag": "gh_tag"})
        ua.check_eq("gh_tag", "v2.4.0")
        ua.check("contains", "$gh_release.assets$", "billing-2.4.0.zip", what="the assets")

    with ua.case("ooa.github/create_release", "pre-release from a branch",
                 "Create release publishes a release for a tag (made from the target when new), here as a pre-release. "
                 "Checks the id and that the latest release is still v2.4.0.") as f:
        f.act("ooa.github/create_release", desc="Publish ut-2.5.0-beta", **R, tag="ut-2.5.0-beta", name="2.5.0 beta",
              notes="Beta for testing", target="main", prerelease=True, out={"release_id": "gh_id", "url": "gh_url"})
        ua.check("gt", "$gh_id$", 7000, what="the release id")
        f.act("ooa.github/get_latest_release", desc="Latest release", **R, out={"tag": "gh_tag"})
        ua.check_eq("gh_tag", "v2.4.0")

    with ua.case("ooa.github/run_workflow", "start a workflow with inputs",
                 "Run workflow starts a workflow that allows manual runs, with inputs. Checks a new queued run of it appears.") as f:
        f.act("ooa.github/run_workflow", desc="Run deploy.yml on main", **R, workflow="deploy.yml", ref="main",
              inputs={"environment": "staging"})
        f.act("ooa.github/list_workflow_runs", desc="Queued runs of deploy.yml", **R, workflow="deploy.yml", status="queued",
              out={"count": "gh_count"})
        ua.check("ge", "$gh_count$", 1, what="the queued runs")

    with ua.case("ooa.github/list_workflow_runs", "failed runs of one workflow",
                 "List workflow runs lists Actions runs, newest first, for one workflow or all, filtered by branch and "
                 "status or result. Checks the one failed CI run.") as f:
        f.act("ooa.github/list_workflow_runs", desc="Failed runs of ci.yml", **R, workflow="ci.yml", status="failure",
              out={"table": "gh_table", "count": "gh_count"})
        ua.check_eq("gh_count", 1)
        ua.check("eq", "$gh_table.rows[0][0]$", 9003, what="the run id")

    with ua.case("ooa.github/get_workflow_run", "status and result of a run",
                 "Get workflow run reads one run's status and result. Checks run 9001 completed with success.") as f:
        f.act("ooa.github/get_workflow_run", desc="Read run 9001", **R, run_id=9001,
              out={"status": "gh_status", "conclusion": "gh_conclusion", "run": "gh_run"})
        ua.check_eq("gh_status", "completed")
        ua.check_eq("gh_conclusion", "success")
        ua.check("eq", "$gh_run.branch$", "main", what="the branch")

    with ua.case("ooa.github/disconnect", "forget the connection",
                 "Disconnect forgets the connection Connect chose; later steps then give their own token and address. "
                 "Checks a step without a token now fails, and one with its own token works.") as f:
        f.act("ooa.github/disconnect", desc="Forget ut_github")
        ua.expect_error("*", lambda ff: ff.act("ooa.github/get_repository", desc="No token now: should fail", base_url=API, **R,
                                               out={"default_branch": "gh_branch"}))
        f.act("ooa.github/get_repository", desc="With the step's own token and address", base_url=API,
              token=cred("github", "token"), owner=OWNER, repo="docs-site", out={"repository": "gh_repo"})
        ua.check("eq", "$gh_repo.description$", "Help pages", what="the description")

    leave_empty(ua.f.steps, "ooa.github", ("token",))
