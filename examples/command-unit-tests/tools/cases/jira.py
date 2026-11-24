"""Jira cases: Connect with a saved connection (connections.json in the run folder, as a workspace keeps it), then
issues, comments, transitions, attachments and projects against the stand-in; Disconnect at the end, after which the
steps give their own sign-in (Cloud email and API token).

The steps after Connect still carry site_url: the agent check (FlowValidator.checkArgs) asks for every required input
unless the step names its own connection, so a step that leaves site_url to the Connect step's connection is refused
before the run starts (reported to the lead). The access token does come from the connection."""
from helpers_business import drop_samples, cred, url, vars_

SITE = url("jira")
CONNECTIONS = ('{"ut_jira": {"package": "ooa.jira", "values": {"site_url": "' + SITE + '"}, '
               '"credentials": {"personal_access_token": "unit_tests/jira/token"}}}')


def _new_issue(f, summary):
    f.act("ooa.jira/create_issue", desc="Create an issue for this case", site_url=SITE, project="UTOPS", issue_type="Task",
          summary=summary, labels=["ut-created"], out={"key": "ji_key"})


def add(ua):
    drop_samples(ua)
    vars_(ua, "ji_conn", "ji_key", "ji_id", "ji_url", "ji_issue", "ji_raw", "ji_table", "ji_count", "ji_comment",
          "ji_author", "ji_created")

    with ua.case("ooa.jira/connect", "use a saved connection for the later steps",
                 "Connect names a saved connection (site address and a Data Center access token from the vault); the "
                 "later Jira steps of the agent use it and need no site or sign-in of their own. The case first saves "
                 "the connection in the run folder, as a workspace keeps it. Checks the connection's name.") as f:
        f.act("ooa.file/write_text", desc="Save the connection ut_jira (connections.json)", path="connections.json", text=CONNECTIONS)
        f.act("ooa.jira/connect", desc="Connect with ut_jira", connection="ut_jira", out={"connection": "ji_conn"})
        ua.check_eq("ji_conn", "ut_jira")

    with ua.case("ooa.jira/create_issue", "a task with priority, labels, assignee and due date",
                 "Create issue makes an issue and returns its key, id and browser link; the assignee is given by "
                 "email and looked up. Checks the key and reads the issue back.") as f:
        f.act("ooa.jira/create_issue", desc="Create a task in UTOPS", site_url=SITE, project="UTOPS", issue_type="Task",
              summary="UT renew TLS certificate", description="Expires on 15 December", priority="High",
              labels=["ut-created", "certificates"], assignee="bruno.silva@ut.example", due_date="2026-12-10",
              out={"key": "ji_key", "id": "ji_id", "url": "ji_url"})
        ua.check("contains", "$ji_key$", "UTOPS-", what="the key")
        ua.check("contains", "$ji_url$", "/browse/UTOPS-", what="the link")
        f.act("ooa.jira/get_issue", desc="Read it back", site_url=SITE, key="$ji_key$", extra_fields=["labels", "duedate"], out={"issue": "ji_issue"})
        ua.check("eq", "$ji_issue.assignee$", "Bruno Silva", what="the assignee")
        ua.check("eq", "$ji_issue.duedate$", "2026-12-10", what="the due date")

    with ua.case("ooa.jira/create_issue", "a sub-task under a parent",
                 "With parent_key, Create issue makes a sub-task of another issue. Checks the parent is set.") as f:
        f.act("ooa.jira/create_issue", desc="Create a sub-task of UTOPS-1", site_url=SITE, project="UTOPS", issue_type="Sub-task",
              parent_key="UTOPS-1", summary="UT clean old logs", labels=["ut-created"], out={"key": "ji_key"})
        f.act("ooa.jira/get_issue", desc="Read it back", site_url=SITE, key="$ji_key$", extra_fields=["parent"], out={"issue": "ji_issue"})
        ua.check("eq", "$ji_issue.parent$", "UTOPS-1", what="the parent")

    with ua.case("ooa.jira/get_issue", "main fields and extra fields",
                 "Get issue reads an issue's main fields (status, assignee, priority...) and the extra fields asked "
                 "for. Checks status, priority and labels.") as f:
        f.act("ooa.jira/get_issue", desc="Read UTOPS-1", site_url=SITE, key="UTOPS-1", extra_fields=["labels"], out={"issue": "ji_issue", "raw": "ji_raw"})
        ua.check("eq", "$ji_issue.summary$", "UT disk full on db01", what="the summary")
        ua.check("eq", "$ji_issue.priority$", "High", what="the priority")
        ua.check("contains", "$ji_issue.labels$", "database", what="the labels")
        ua.check("eq", "$ji_raw.id$", "900", what="the raw issue's id")

    with ua.case("ooa.jira/search", "JQL with the Data Center sign-in",
                 "Search issues (JQL) finds issues with a JQL query and returns them as a table. With the connection's "
                 "access token Jira Data Center's search is used. Checks the two seeded UTOPS issues.") as f:
        f.act("ooa.jira/search", desc="Seeded UTOPS issues", site_url=SITE, jql='project = UTOPS AND labels = seed ORDER BY created DESC',
              extra_fields=["labels"], out={"table": "ji_table", "count": "ji_count"})
        ua.check_eq("ji_count", 2)

    with ua.case("ooa.jira/add_comment", "a comment only a role can see",
                 "Add comment posts a comment, here visible only to the Administrators role. Checks the comment id and author.") as f:
        _new_issue(f, "UT reset VPN token")
        f.act("ooa.jira/add_comment", desc="Comment for administrators", site_url=SITE, key="$ji_key$", text="Token reset done.",
              visible_to_role="Administrators", out={"comment_id": "ji_comment", "author": "ji_author", "created": "ji_created"})
        ua.check("notEmpty", "$ji_comment$", what="the comment id")
        ua.check_eq("ji_author", "Asha Patel")

    with ua.case("ooa.jira/get_comments", "comments of an issue",
                 "Get comments lists an issue's comments, oldest first, as a table. Checks the two comments the case added.") as f:
        _new_issue(f, "UT order new laptops")
        f.act("ooa.jira/add_comment", desc="First comment", site_url=SITE, key="$ji_key$", text="Quote requested.")
        f.act("ooa.jira/add_comment", desc="Second comment", site_url=SITE, key="$ji_key$", text="Quote approved.")
        f.act("ooa.jira/get_comments", desc="List the comments", site_url=SITE, key="$ji_key$", max_results=10, out={"table": "ji_table", "count": "ji_count"})
        ua.check_eq("ji_count", 2)

    with ua.case("ooa.jira/update_comment", "replace a comment's text",
                 "Update comment replaces the text of a comment. The case adds one, changes it and lists the comments "
                 "to check there is still one.") as f:
        _new_issue(f, "UT move printer")
        f.act("ooa.jira/add_comment", desc="Add a comment", site_url=SITE, key="$ji_key$", text="Moving on Monday.", out={"comment_id": "ji_comment"})
        f.act("ooa.jira/update_comment", desc="Change it", site_url=SITE, key="$ji_key$", comment_id="$ji_comment$", text="Moving on Tuesday.")
        f.act("ooa.jira/get_comments", desc="List the comments", site_url=SITE, key="$ji_key$", out={"count": "ji_count"})
        ua.check_eq("ji_count", 1)

    with ua.case("ooa.jira/delete_comment", "remove a comment",
                 "Delete comment removes a comment from an issue. Checks the issue has no comments afterwards.") as f:
        _new_issue(f, "UT wrong comment")
        f.act("ooa.jira/add_comment", desc="Add a comment", site_url=SITE, key="$ji_key$", text="Posted by mistake.", out={"comment_id": "ji_comment"})
        f.act("ooa.jira/delete_comment", desc="Delete it", site_url=SITE, key="$ji_key$", comment_id="$ji_comment$")
        f.act("ooa.jira/get_comments", desc="List the comments", site_url=SITE, key="$ji_key$", out={"count": "ji_count"})
        ua.check_eq("ji_count", 0)

    with ua.case("ooa.jira/transition", "move to Done with a comment",
                 "Move issue (transition) moves an issue through its workflow by transition or status name, here "
                 "with a comment and a resolution. Checks the status is Done and the comment was added.") as f:
        _new_issue(f, "UT replace mouse")
        f.act("ooa.jira/transition", desc="Move it to Done", site_url=SITE, key="$ji_key$", transition="Done", comment="Replaced.",
              fields={"resolution": {"name": "Fixed"}})
        f.act("ooa.jira/get_issue", desc="Read it back", site_url=SITE, key="$ji_key$", out={"issue": "ji_issue"})
        ua.check("eq", "$ji_issue.status$", "Done", what="the status")
        f.act("ooa.jira/get_comments", desc="List the comments", site_url=SITE, key="$ji_key$", out={"count": "ji_count"})
        ua.check_eq("ji_count", 1)

    with ua.case("ooa.jira/transition", "unknown transition is refused",
                 "When the workflow has no such transition, the step fails with JIRA.NO_TRANSITION.") as f:
        _new_issue(f, "UT no such move")
        ua.expect_error("JIRA.NO_TRANSITION", lambda ff: ff.act("ooa.jira/transition", desc="Try a move that does not exist", site_url=SITE,
                                                               key="$ji_key$", transition="Archived"))

    with ua.case("ooa.jira/attach_file", "attach a text file",
                 "Attach file uploads a local file to an issue. Checks the issue then lists the file.") as f:
        _new_issue(f, "UT collect logs")
        f.act("ooa.file/write_text", desc="Prepare a file", path="ut_jira_note.txt", text="log excerpt")
        f.act("ooa.jira/attach_file", desc="Attach it", site_url=SITE, key="$ji_key$", path="ut_jira_note.txt")
        f.act("ooa.jira/get_issue", desc="Read the attachments", site_url=SITE, key="$ji_key$", extra_fields=["attachment"], out={"issue": "ji_issue"})
        ua.check("contains", "$ji_issue.attachment$", "ut_jira_note.txt", what="the attachments")

    with ua.case("ooa.jira/update_issue", "summary, priority and labels",
                 "Update issue changes named fields and any others given in fields. Checks the new summary and priority.") as f:
        _new_issue(f, "UT old title")
        f.act("ooa.jira/update_issue", desc="Change the issue", site_url=SITE, key="$ji_key$", summary="UT new title", priority="Low",
              labels=["ut-created", "renamed"], fields={"duedate": "2026-12-24"}, notify_users=False)
        f.act("ooa.jira/get_issue", desc="Read it back", site_url=SITE, key="$ji_key$", extra_fields=["duedate"], out={"issue": "ji_issue"})
        ua.check("eq", "$ji_issue.summary$", "UT new title", what="the summary")
        ua.check("eq", "$ji_issue.priority$", "Low", what="the priority")
        ua.check("eq", "$ji_issue.duedate$", "2026-12-24", what="the due date")

    with ua.case("ooa.jira/delete_issue", "delete with its sub-tasks",
                 "Delete issue removes an issue for good, here with its sub-task. Checks reading it afterwards fails.") as f:
        _new_issue(f, "UT parent to delete")
        f.act("ooa.jira/create_issue", desc="Give it a sub-task", site_url=SITE, project="UTOPS", issue_type="Sub-task", parent_key="$ji_key$",
              summary="UT child to delete", out={"key": "ji_id"})
        f.act("ooa.jira/delete_issue", desc="Delete both", site_url=SITE, key="$ji_key$", delete_subtasks=True)
        ua.expect_error("JIRA.*", lambda ff: ff.act("ooa.jira/get_issue", desc="Try to read the sub-task", site_url=SITE, key="$ji_id$",
                                                   out={"issue": "ji_issue"}))

    with ua.case("ooa.jira/get_projects", "projects of one category",
                 "Get projects lists the projects the account can see, here only those in the Finance category. "
                 "Checks one project.") as f:
        f.act("ooa.jira/get_projects", desc="Finance projects", site_url=SITE, category="Finance", out={"table": "ji_table", "count": "ji_count"})
        ua.check_eq("ji_count", 1)

    with ua.case("ooa.jira/create_project", "new project, lead by email, in a category",
                 "Create project makes a project with a key, name, type and lead (an email, looked up) in a category "
                 "found by name. Checks the key, then finds it with Get projects.") as f:
        f.act("ooa.jira/delete_project", desc="Remove UTNEW if an earlier run left it", site_url=SITE, project_key="UTNEW", cont=True)
        f.act("ooa.jira/create_project", desc="Create UTNEW", site_url=SITE, project_key="UTNEW", project_name="UT New Team",
              project_type="business", lead_account_id="asha.patel@ut.example", project_description="Made-up project",
              category="Operations", assignee_type="unassigned", out={"id": "ji_id", "key": "ji_key"})
        ua.check_eq("ji_key", "UTNEW")
        f.act("ooa.jira/get_projects", desc="Find it", site_url=SITE, keys=["UTNEW"], out={"count": "ji_count"})
        ua.check_eq("ji_count", 1)

    with ua.case("ooa.jira/update_project", "rename and change the lead",
                 "Update project changes a project's name, lead (by email) and other details. Checks it can be found "
                 "by its new name.") as f:
        f.act("ooa.jira/update_project", desc="Rename UTNEW", site_url=SITE, project_key="UTNEW", project_name="UT Renamed Team",
              lead="bruno.silva@ut.example", category_id="10101")
        f.act("ooa.jira/get_projects", desc="Look for the new name in Finance", site_url=SITE, query="Renamed", category="Finance", out={"count": "ji_count"})
        ua.check_eq("ji_count", 1)

    with ua.case("ooa.jira/delete_project", "delete a project",
                 "Delete project removes a project and its issues. Checks it is no longer listed.") as f:
        f.act("ooa.jira/delete_project", desc="Delete UTNEW", site_url=SITE, project_key="UTNEW")
        f.act("ooa.jira/get_projects", desc="Look for it", site_url=SITE, keys=["UTNEW"], out={"count": "ji_count"})
        ua.check_eq("ji_count", 0)

    with ua.case("ooa.jira/disconnect", "forget the connection, then sign in on the step",
                 "Disconnect forgets the connection Connect chose; later steps give their own site and sign-in, here "
                 "Jira Cloud's email and API token. Checks a search with the step's own sign-in.") as f:
        f.act("ooa.jira/disconnect", desc="Stop using ut_jira")
        f.act("ooa.jira/search", desc="Search with the step's own site, email and API token", site_url=SITE,
              email="ut.agent@ut.example", api_token=cred("jira", "api_token"), jql='project = UTFIN', out={"count": "ji_count"})
        ua.check_eq("ji_count", 1)
        ua.expect_error("JIRA.*", lambda ff: ff.act("ooa.jira/get_issue", desc="A step with no sign-in of its own is refused now", site_url=SITE, key="UTOPS-1",
                                              out={"issue": "ji_issue"}))
