"""Asana cases: Connect with a saved connection (written to the run folder's connections.json, pointing at the
stand-in), then every Asana command without its own address or token; Disconnect at the end."""
import json

from helpers_business import drop_samples, cred, url, vars_

BASE = url("asana")
WS = "1100000000000001"          # UT Org
TEAM = "1200000000000001"        # Finance Ops
PROJECT = "1400000000000001"     # UT Month End
TASK = "1600000000000001"        # Reconcile bank account
TAG = "1700000000000001"         # urgent
CONN = "UT Asana"


def add(ua):
    drop_samples(ua)
    vars_(ua, "as_conn", "as_table", "as_count", "as_project", "as_id", "as_id2", "as_task", "as_done", "as_comment",
          "as_url", "as_path", "as_text", "as_team", "as_user", "as_n")

    with ua.case("ooa.asana/connect", "use a saved connection",
                 "Connect picks a saved connection (address plus vault reference for the token) for the later Asana "
                 "steps, which then need neither. The case first saves the connection in the run folder's "
                 "connections.json, pointing at the stand-in. Checks the connection's name comes back.") as f:
        conn = {CONN: {"package": "ooa.asana", "values": {"base_url": BASE},
                       "credentials": {"token": "unit_tests/asana/token"}}}
        f.act("ooa.file/write_text", desc="Save the stand-in connection", path="connections.json",
              text=json.dumps(conn, indent=1))
        f.act("ooa.asana/connect", desc="Use the UT Asana connection", connection=CONN, out={"connection": "as_conn"})
        ua.check_eq("as_conn", CONN)

    with ua.case("ooa.asana/list_workspaces", "workspaces the token sees",
                 "List workspaces gives the workspaces and organizations of the token. Checks the one stand-in workspace.") as f:
        f.act("ooa.asana/list_workspaces", desc="List workspaces", out={"table": "as_table", "count": "as_count"})
        ua.check_eq("as_count", 1)
        ua.check("eq", "$as_table.rows[0][1]$", "UT Org", what="the workspace name")

    with ua.case("ooa.asana/list_projects", "active and all projects",
                 "List projects lists a workspace's projects; archived chooses active, archived or all. Checks one "
                 "active project and two in all.") as f:
        f.act("ooa.asana/list_projects", desc="Active projects of the team", workspace_id=WS, team_id=TEAM,
              archived="active", out={"count": "as_count"})
        ua.check_eq("as_count", 1)
        f.act("ooa.asana/list_projects", desc="All projects", workspace_id=WS, archived="all", out={"count": "as_count"})
        ua.check_eq("as_count", 2)

    with ua.case("ooa.asana/get_project", "project picked by its name",
                 "Get project reads a project's details; the project can be given by its name, which is looked up. "
                 "Checks the owner and the team.") as f:
        f.act("ooa.asana/get_project", desc="Read UT Month End", project_id="UT Month End", out={"project": "as_project"})
        ua.check("eq", "$as_project.gid$", PROJECT, what="the project id")
        ua.check("eq", "$as_project.owner$", "Asha Patel", what="the owner")
        ua.check("eq", "$as_project.team$", "Finance Ops", what="the team")

    with ua.case("ooa.asana/create_project", "new project in a team",
                 "Create project adds a project to a workspace and team. Checks the new id, then reads the project back.") as f:
        f.act("ooa.asana/create_project", desc="Create UT Budget 2027", workspace_id=WS, team_id=TEAM, name="UT Budget 2027",
              notes="Plan next year", owner="bruno.silva@ut.example", due_on="2026-12-15",
              out={"project_id": "as_id", "url": "as_url"})
        ua.check("notEmpty", "$as_id$", what="the project id")
        f.act("ooa.asana/get_project", desc="Read it back", project_id="$as_id$", out={"project": "as_project"})
        ua.check("eq", "$as_project.owner$", "Bruno Silva", what="the owner")
        ua.check("eq", "$as_project.due_on$", "2026-12-15", what="the due date")

    with ua.case("ooa.asana/update_project", "rename and archive",
                 "Update project changes a project's fields, here the name and the archived flag. Checks both.") as f:
        f.act("ooa.asana/create_project", desc="A project to change", workspace_id=WS, team_id=TEAM, name="UT Draft Plan",
              out={"project_id": "as_id"})
        f.act("ooa.asana/update_project", desc="Rename and archive", project_id="$as_id$", name="UT Final Plan", archived=True)
        f.act("ooa.asana/get_project", desc="Read it back", project_id="$as_id$", out={"project": "as_project"})
        ua.check("eq", "$as_project.name$", "UT Final Plan", what="the new name")
        ua.check("eq", "$as_project.archived$", True, what="archived")

    with ua.case("ooa.asana/delete_project", "delete with confirm",
                 "Delete project removes a project when confirm is true. Checks that reading it afterwards fails.") as f:
        f.act("ooa.asana/create_project", desc="A project to delete", workspace_id=WS, name="UT Scratch Project",
              out={"project_id": "as_id"})
        f.act("ooa.asana/delete_project", desc="Delete it", project_id="$as_id$", confirm=True)
        ua.expect_error("ASANA.*", lambda ff: ff.act("ooa.asana/get_project", desc="Read the deleted project",
                                                     project_id="$as_id$", out={"project": "as_project"}))

    with ua.case("ooa.asana/list_sections", "sections of a project",
                 "List sections gives the sections (board columns) of a project. Checks the first is To do.") as f:
        f.act("ooa.asana/create_project", desc="A fresh project", workspace_id=WS, name="UT Sections Project", out={"project_id": "as_id"})
        f.act("ooa.asana/list_sections", desc="Sections of UT Month End", project_id=PROJECT, out={"table": "as_table", "count": "as_count"})
        ua.check("eq", "$as_table.rows[0][1]$", "To do", what="the first section")

    with ua.case("ooa.asana/create_section", "add a section",
                 "Create section adds a section to a project. Checks the new project then has exactly that section.") as f:
        f.act("ooa.asana/create_project", desc="A fresh project", workspace_id=WS, name="UT Board", out={"project_id": "as_id"})
        f.act("ooa.asana/create_section", desc="Add Review", project_id="$as_id$", name="Review", out={"section_id": "as_id2"})
        ua.check("notEmpty", "$as_id2$", what="the section id")
        f.act("ooa.asana/list_sections", desc="List its sections", project_id="$as_id$", out={"count": "as_count"})
        ua.check_eq("as_count", 1)

    with ua.case("ooa.asana/create_task", "task in a section with tags, followers and fields",
                 "Create task adds a task; here in a project's section (Project/Section by name), with assignee, due date, "
                 "tag, follower and a custom field. Checks the task as read back.") as f:
        f.act("ooa.asana/create_task", desc="Create Accrue utilities", name="Accrue utilities", project_id="UT Month End",
              section_id="To do", assignee="bruno.silva@ut.example", due_on="2026-11-29", notes="Gas and power",
              tag_ids=[TAG], follower_ids=["chloe.martin@ut.example"], custom_fields={"Priority": "Low"},
              out={"task_id": "as_id", "url": "as_url"})
        ua.check("notEmpty", "$as_url$", what="the task's link")
        f.act("ooa.asana/get_task", desc="Read it back", task_id="$as_id$", out={"task": "as_task"})
        ua.check("eq", "$as_task.assignee$", "Bruno Silva", what="the assignee")
        ua.check("contains", "$as_task.sections$", "To do", what="the section")
        ua.check("contains", "$as_task.tags$", "urgent", what="the tag")
        ua.check("contains", "$as_task.followers$", "Chloe Martin", what="the follower")

    with ua.case("ooa.asana/get_task", "read a task",
                 "Get task reads a task with its assignee, projects, sections and custom fields. Checks several of them.") as f:
        f.act("ooa.asana/get_task", desc="Read Reconcile bank account", task_id=TASK, out={"task": "as_task", "completed": "as_done"})
        ua.check_eq("as_done", False)
        ua.check("eq", "$as_task.name$", "Reconcile bank account", what="the name")
        ua.check("eq", "$as_task.custom_fields.Priority$", "High", what="the Priority field")
        ua.check("contains", "$as_task.projects$", "UT Month End", what="the project")

    with ua.case("ooa.asana/update_task", "complete and reassign",
                 "Update task changes a task's fields; here it is marked done and given to someone else. Checks both.") as f:
        f.act("ooa.asana/create_task", desc="A task to change", name="UT Check invoices", workspace_id=WS, out={"task_id": "as_id"})
        f.act("ooa.asana/update_task", desc="Complete and reassign", task_id="$as_id$", completed=True, assignee="asha.patel@ut.example")
        f.act("ooa.asana/get_task", desc="Read it back", task_id="$as_id$", out={"task": "as_task", "completed": "as_done"})
        ua.check_eq("as_done", True)
        ua.check("eq", "$as_task.assignee$", "Asha Patel", what="the assignee")

    with ua.case("ooa.asana/delete_task", "delete a task",
                 "Delete task removes a task. Checks that reading it afterwards fails.") as f:
        f.act("ooa.asana/create_task", desc="A task to delete", name="UT Scratch task", workspace_id=WS, out={"task_id": "as_id"})
        f.act("ooa.asana/delete_task", desc="Delete it", task_id="$as_id$")
        ua.expect_error("ASANA.*", lambda ff: ff.act("ooa.asana/get_task", desc="Read the deleted task", task_id="$as_id$",
                                                     out={"task": "as_task"}))

    with ua.case("ooa.asana/list_tasks", "open and all tasks of a project",
                 "List tasks lists a project's tasks; only_open leaves out finished ones. The case makes a project with "
                 "one open and one finished task. Checks 1 open and 2 in all.") as f:
        f.act("ooa.asana/create_project", desc="A fresh project", workspace_id=WS, name="UT Tasks Project", out={"project_id": "as_id"})
        f.act("ooa.asana/create_task", desc="Open task", name="UT open one", project_id="$as_id$")
        f.act("ooa.asana/create_task", desc="Finished task", name="UT done one", project_id="$as_id$", completed=True)
        f.act("ooa.asana/list_tasks", desc="Open tasks", project_id="$as_id$", only_open=True, out={"count": "as_count"})
        ua.check_eq("as_count", 1)
        f.act("ooa.asana/list_tasks", desc="All tasks", project_id="$as_id$", only_open=False, out={"table": "as_table", "count": "as_count"})
        ua.check_eq("as_count", 2)

    with ua.case("ooa.asana/search_tasks", "search by text",
                 "Search tasks searches a workspace's tasks by text, assignee, project or done state. Checks the one match.") as f:
        f.act("ooa.asana/search_tasks", desc="Search for Reconcile bank", workspace_id=WS, text="Reconcile bank",
              out={"table": "as_table", "count": "as_count"})
        ua.check_eq("as_count", 1)
        ua.check("eq", "$as_table.rows[0][0]$", TASK, what="the task id")

    with ua.case("ooa.asana/add_followers", "two followers",
                 "Add followers makes people follow a task so they hear about changes. Checks both are followers.") as f:
        f.act("ooa.asana/create_task", desc="A task to follow", name="UT Follow me", workspace_id=WS, out={"task_id": "as_id"})
        f.act("ooa.asana/add_followers", desc="Add two followers", task_id="$as_id$",
              followers=["asha.patel@ut.example", "1300000000000003"])
        f.act("ooa.asana/get_task", desc="Read the followers", task_id="$as_id$", out={"task": "as_task"})
        ua.check("contains", "$as_task.followers$", "Asha Patel", what="follower Asha")
        ua.check("contains", "$as_task.followers$", "Chloe Martin", what="follower Chloe")

    with ua.case("ooa.asana/remove_followers", "one follower stops",
                 "Remove followers stops people following a task. Checks the removed person is gone.") as f:
        f.act("ooa.asana/create_task", desc="A task with a follower", name="UT Unfollow", workspace_id=WS,
              follower_ids=["chloe.martin@ut.example"], out={"task_id": "as_id"})
        f.act("ooa.asana/remove_followers", desc="Remove Chloe", task_id="$as_id$", followers=["chloe.martin@ut.example"])
        f.act("ooa.asana/get_task", desc="Read the followers", task_id="$as_id$", out={"task": "as_task"})
        ua.check("notContains", "$as_task.followers$", "Chloe Martin", what="Chloe is no follower")

    with ua.case("ooa.asana/add_task_to_section", "move to Done",
                 "Move task to section puts a task in another section of its project (section as Project/Section). "
                 "Checks the task is in Done.") as f:
        f.act("ooa.asana/create_task", desc="A task in To do", name="UT Move me", project_id=PROJECT, section_id="To do",
              out={"task_id": "as_id"})
        f.act("ooa.asana/add_task_to_section", desc="Move it to Done", section_id="UT Month End/Done", task_id="$as_id$")
        f.act("ooa.asana/get_task", desc="Read its sections", task_id="$as_id$", out={"task": "as_task"})
        ua.check("contains", "$as_task.sections$", "Done", what="the section")
        ua.check("notContains", "$as_task.sections$", "To do", what="not in To do any more")

    with ua.case("ooa.asana/add_task_to_project", "also in a second project",
                 "Add task to project adds a task to one more project, optionally in a section. Checks both projects.") as f:
        f.act("ooa.asana/create_project", desc="A second project", workspace_id=WS, name="UT Second Project", out={"project_id": "as_id2"})
        f.act("ooa.asana/create_task", desc="A task in UT Month End", name="UT Shared task", project_id=PROJECT, out={"task_id": "as_id"})
        f.act("ooa.asana/add_task_to_project", desc="Add it to the second project", task_id="$as_id$", project_id="$as_id2$")
        f.act("ooa.asana/get_task", desc="Read its projects", task_id="$as_id$", out={"task": "as_task"})
        ua.check("contains", "$as_task.projects$", "UT Second Project", what="the second project")
        ua.check("contains", "$as_task.projects$", "UT Month End", what="the first project")

    with ua.case("ooa.asana/create_subtask", "subtask under a task",
                 "Create subtask adds a subtask under a task. Checks the subtask's parent.") as f:
        f.act("ooa.asana/create_task", desc="A parent task", name="UT Parent", workspace_id=WS, out={"task_id": "as_id"})
        f.act("ooa.asana/create_subtask", desc="Add a subtask", parent_task_id="$as_id$", name="UT Child step",
              assignee="me", due_on="2026-11-30", out={"task_id": "as_id2", "url": "as_url"})
        f.act("ooa.asana/get_task", desc="Read the subtask", task_id="$as_id2$", out={"task": "as_task"})
        ua.check("eq", "$as_task.parent$", "UT Parent", what="the parent")

    with ua.case("ooa.asana/list_subtasks", "two subtasks",
                 "List subtasks gives a task's subtasks. Checks both subtasks are listed.") as f:
        f.act("ooa.asana/create_task", desc="A parent task", name="UT Parent 2", workspace_id=WS, out={"task_id": "as_id"})
        f.act("ooa.asana/create_subtask", desc="First subtask", parent_task_id="$as_id$", name="UT step 1")
        f.act("ooa.asana/create_subtask", desc="Second subtask", parent_task_id="$as_id$", name="UT step 2")
        f.act("ooa.asana/list_subtasks", desc="List them", task_id="$as_id$", out={"count": "as_count"})
        ua.check_eq("as_count", 2)

    with ua.case("ooa.asana/update_task", "make a task a subtask",
                 "Update task with parent_task_id moves the task under another task. Checks the new parent.") as f:
        f.act("ooa.asana/create_task", desc="A parent", name="UT New parent", workspace_id=WS, out={"task_id": "as_id"})
        f.act("ooa.asana/create_task", desc="A task to move", name="UT Moved task", workspace_id=WS, out={"task_id": "as_id2"})
        f.act("ooa.asana/update_task", desc="Put it under the parent", task_id="$as_id2$", parent_task_id="$as_id$")
        f.act("ooa.asana/get_task", desc="Read it back", task_id="$as_id2$", out={"task": "as_task"})
        ua.check("eq", "$as_task.parent$", "UT New parent", what="the parent")

    with ua.case("ooa.asana/add_comment", "pinned rich-text comment",
                 "Add comment posts a comment on a task, here as rich text and pinned. Checks the comment's text and pin.") as f:
        f.act("ooa.asana/add_comment", desc="Comment on the bank task", task_id=TASK, text="<body>Paid <strong>today</strong></body>",
              text_format="html", pinned=True, out={"comment_id": "as_id"})
        f.act("ooa.asana/get_comment", desc="Read the comment", comment_id="$as_id$", out={"comment": "as_comment"})
        ua.check("eq", "$as_comment.text$", "Paid today", what="the text")
        ua.check("eq", "$as_comment.pinned$", True, what="pinned")

    with ua.case("ooa.asana/get_comment", "author and task of a comment",
                 "Get comment reads one comment with its author and task. Checks the task's name and the author.") as f:
        f.act("ooa.asana/add_comment", desc="A plain comment", task_id=TASK, text="Statement received", out={"comment_id": "as_id"})
        f.act("ooa.asana/get_comment", desc="Read it", comment_id="$as_id$", out={"comment": "as_comment"})
        ua.check("eq", "$as_comment.task$", "Reconcile bank account", what="the task")
        ua.check("eq", "$as_comment.author$", "Asha Patel", what="the author")
        ua.check("eq", "$as_comment.type$", "comment_added", what="the type")

    with ua.case("ooa.asana/list_comments", "only comments, not activity",
                 "List comments lists a task's comments and leaves out activity entries. Checks the two comments.") as f:
        f.act("ooa.asana/create_task", desc="A fresh task", name="UT Talk", workspace_id=WS, out={"task_id": "as_id"})
        f.act("ooa.asana/add_comment", desc="First comment", task_id="$as_id$", text="One")
        f.act("ooa.asana/add_comment", desc="Second comment", task_id="$as_id$", text="Two")
        f.act("ooa.asana/list_comments", desc="List them", task_id="$as_id$", out={"table": "as_table", "count": "as_count"})
        ua.check_eq("as_count", 2)
        ua.check("eq", "$as_table.rows[1][1]$", "Two", what="the second comment")

    with ua.case("ooa.asana/update_comment", "change the text and unpin",
                 "Update comment changes a comment's text or pin. Checks the new text and that it is unpinned.") as f:
        f.act("ooa.asana/add_comment", desc="A pinned comment", task_id=TASK, text="Draft", pinned=True, out={"comment_id": "as_id"})
        f.act("ooa.asana/update_comment", desc="Change it", comment_id="$as_id$", text="Final", pinned=False)
        f.act("ooa.asana/get_comment", desc="Read it back", comment_id="$as_id$", out={"comment": "as_comment"})
        ua.check("eq", "$as_comment.text$", "Final", what="the text")
        ua.check("eq", "$as_comment.pinned$", False, what="pinned")

    with ua.case("ooa.asana/delete_comment", "delete a comment",
                 "Delete comment removes a comment. Checks reading it afterwards fails.") as f:
        f.act("ooa.asana/add_comment", desc="A comment to delete", task_id=TASK, text="Oops", out={"comment_id": "as_id"})
        f.act("ooa.asana/delete_comment", desc="Delete it", comment_id="$as_id$")
        ua.expect_error("ASANA.*", lambda ff: ff.act("ooa.asana/get_comment", desc="Read the deleted comment",
                                                     comment_id="$as_id$", out={"comment": "as_comment"}))

    with ua.case("ooa.asana/upload_attachment", "attach a file",
                 "Upload attachment attaches a local file to a task. Checks the attachment id and its address.") as f:
        f.act("ooa.file/write_text", desc="Prepare a file", path="ut_asana_note.txt", text="Bank statement November")
        f.act("ooa.asana/upload_attachment", desc="Attach it to the bank task", task_id=TASK, path="ut_asana_note.txt",
              file_name="statement-nov.txt", out={"attachment_id": "as_id", "url": "as_url"})
        ua.check("notEmpty", "$as_id$", what="the attachment id")
        ua.check("contains", "$as_url$", "$as_id$", what="the address")

    with ua.case("ooa.asana/list_attachments", "attachments of a task",
                 "List attachments lists the files attached to a task. Checks the one file and its size.") as f:
        f.act("ooa.asana/create_task", desc="A fresh task", name="UT With file", workspace_id=WS, out={"task_id": "as_id"})
        f.act("ooa.file/write_text", desc="Prepare a file", path="ut_asana_list.txt", text="12345")
        f.act("ooa.asana/upload_attachment", desc="Attach it", task_id="$as_id$", path="ut_asana_list.txt")
        f.act("ooa.asana/list_attachments", desc="List the attachments", task_id="$as_id$", out={"table": "as_table", "count": "as_count"})
        ua.check_eq("as_count", 1)
        ua.check("eq", "$as_table.rows[0][1]$", "ut_asana_list.txt", what="the file name")
        ua.check("eq", "$as_table.rows[0][2]$", 5, what="the size")

    with ua.case("ooa.asana/download_attachment", "save an attachment",
                 "Download attachment saves an attached file to a folder. Checks the saved file's text.") as f:
        f.act("ooa.file/write_text", desc="Prepare a file", path="ut_asana_up.txt", text="Round trip")
        f.act("ooa.asana/upload_attachment", desc="Attach it", task_id=TASK, path="ut_asana_up.txt", file_name="round-trip.txt",
              out={"attachment_id": "as_id"})
        f.act("ooa.asana/download_attachment", desc="Download it", attachment_id="$as_id$", folder="ut_asana_downloads",
              overwrite=True, out={"path": "as_path"})
        f.act("ooa.file/read_text", desc="Read the saved file", path="$as_path$", out={"text": "as_text"})
        ua.check_eq("as_text", "Round trip")
        ua.check("endsWith", "$as_path$", "round-trip.txt", what="the file name")

    with ua.case("ooa.asana/delete_attachment", "remove an attachment",
                 "Delete attachment removes a file from its task. Checks the task has no attachments left.") as f:
        f.act("ooa.asana/create_task", desc="A fresh task", name="UT Remove file", workspace_id=WS, out={"task_id": "as_id2"})
        f.act("ooa.file/write_text", desc="Prepare a file", path="ut_asana_del.txt", text="x")
        f.act("ooa.asana/upload_attachment", desc="Attach it", task_id="$as_id2$", path="ut_asana_del.txt", out={"attachment_id": "as_id"})
        f.act("ooa.asana/delete_attachment", desc="Delete it", attachment_id="$as_id$")
        f.act("ooa.asana/list_attachments", desc="List what is left", task_id="$as_id2$", out={"count": "as_count"})
        ua.check_eq("as_count", 0)

    with ua.case("ooa.asana/duplicate_task", "copy with subtasks and notes",
                 "Duplicate task copies a task under a new name with the parts chosen in include. Checks the copy's name, "
                 "notes and its subtask.") as f:
        f.act("ooa.asana/create_task", desc="A task with notes", name="UT Template", notes="Steps inside", workspace_id=WS,
              out={"task_id": "as_id"})
        f.act("ooa.asana/create_subtask", desc="With one subtask", parent_task_id="$as_id$", name="UT sub")
        f.act("ooa.asana/duplicate_task", desc="Copy it", task_id="$as_id$", name="UT Copy", include=["subtasks", "notes"],
              out={"task_id": "as_id2"})
        f.act("ooa.asana/get_task", desc="Read the copy", task_id="$as_id2$", out={"task": "as_task"})
        ua.check("eq", "$as_task.name$", "UT Copy", what="the name")
        ua.check("eq", "$as_task.notes$", "Steps inside", what="the notes")
        ua.check("eq", "$as_task.subtasks$", 1, what="the subtasks")

    with ua.case("ooa.asana/create_team", "new team",
                 "Create team adds a team to an organization workspace. Checks the new team reads back with its description.") as f:
        f.act("ooa.asana/create_team", desc="Create UT Treasury", workspace_id=WS, name="UT Treasury",
              team_description="Cash and banks", out={"team_id": "as_id"})
        f.act("ooa.asana/get_team", desc="Read it back", team_id="$as_id$", out={"team": "as_team"})
        ua.check("eq", "$as_team.description$", "Cash and banks", what="the description")

    with ua.case("ooa.asana/get_team", "read a team",
                 "Get team reads a team's name, description and organization. Checks them.") as f:
        f.act("ooa.asana/get_team", desc="Read Finance Ops", team_id=TEAM, out={"team": "as_team"})
        ua.check("eq", "$as_team.name$", "Finance Ops", what="the name")
        ua.check("eq", "$as_team.organization$", "UT Org", what="the organization")

    with ua.case("ooa.asana/update_team", "rename a team",
                 "Update team renames a team or changes its description. Checks the new name.") as f:
        f.act("ooa.asana/create_team", desc="A team to rename", workspace_id=WS, name="UT Temp Team", out={"team_id": "as_id"})
        f.act("ooa.asana/update_team", desc="Rename it", team_id="$as_id$", name="UT Renamed Team", team_description="Renamed")
        f.act("ooa.asana/get_team", desc="Read it back", team_id="$as_id$", out={"team": "as_team"})
        ua.check("eq", "$as_team.name$", "UT Renamed Team", what="the name")

    with ua.case("ooa.asana/list_teams", "teams of the organization",
                 "List teams lists the teams of an organization workspace. Checks Finance Ops is first.") as f:
        f.act("ooa.asana/list_teams", desc="List teams", workspace_id=WS, out={"table": "as_table", "count": "as_count"})
        ua.check("eq", "$as_table.rows[0][1]$", "Finance Ops", what="the first team")
        ua.check("ge", "$as_count$", 1, what="the number of teams")

    with ua.case("ooa.asana/list_user_teams", "teams of a user",
                 "List teams of user lists the teams a person belongs to in an organization. Checks Bruno is in one team.") as f:
        f.act("ooa.asana/list_user_teams", desc="Teams of Bruno", user="bruno.silva@ut.example", workspace_id=WS,
              out={"table": "as_table", "count": "as_count"})
        ua.check_eq("as_count", 1)
        ua.check("eq", "$as_table.rows[0][1]$", "Finance Ops", what="the team")

    with ua.case("ooa.asana/list_tags", "tags of a workspace",
                 "List tags lists a workspace's tags. Checks urgent is there with its colour.") as f:
        f.act("ooa.asana/list_tags", desc="List tags", workspace_id=WS, out={"table": "as_table", "count": "as_count"})
        ua.check("eq", "$as_table.rows[0][1]$", "urgent", what="the tag")
        ua.check("eq", "$as_table.rows[0][2]$", "dark-red", what="the colour")

    with ua.case("ooa.asana/create_tag", "new tag",
                 "Create tag adds a tag to a workspace. Checks a new id came back and the tag can be found by name.") as f:
        f.act("ooa.asana/create_tag", desc="Create ut-review", workspace_id=WS, name="ut-review", out={"tag_id": "as_id"})
        ua.check("notEmpty", "$as_id$", what="the tag id")
        f.act("ooa.asana/create_task", desc="A task", name="UT Tagged", workspace_id=WS, out={"task_id": "as_id2"})
        f.act("ooa.asana/add_tag_to_task", desc="Tag it by the tag's name", task_id="$as_id2$", tag_id="ut-review")
        f.act("ooa.asana/get_task", desc="Read its tags", task_id="$as_id2$", out={"task": "as_task"})
        ua.check("contains", "$as_task.tags$", "ut-review", what="the tag")

    with ua.case("ooa.asana/add_tag_to_task", "tag a task by id",
                 "Add tag to task puts a tag on a task. Checks the tag is on it.") as f:
        f.act("ooa.asana/create_task", desc="A task", name="UT Tag me", workspace_id=WS, out={"task_id": "as_id"})
        f.act("ooa.asana/add_tag_to_task", desc="Add urgent", task_id="$as_id$", tag_id=TAG)
        f.act("ooa.asana/get_task", desc="Read its tags", task_id="$as_id$", out={"task": "as_task"})
        ua.check("contains", "$as_task.tags$", "urgent", what="the tag")

    with ua.case("ooa.asana/remove_tag_from_task", "untag a task",
                 "Remove tag from task takes a tag off a task. Checks the tag is gone.") as f:
        f.act("ooa.asana/create_task", desc="A tagged task", name="UT Untag me", workspace_id=WS, tag_ids=[TAG], out={"task_id": "as_id"})
        f.act("ooa.asana/remove_tag_from_task", desc="Remove urgent", task_id="$as_id$", tag_id=TAG)
        f.act("ooa.asana/get_task", desc="Read its tags", task_id="$as_id$", out={"task": "as_task"})
        ua.check("notContains", "$as_task.tags$", "urgent", what="no tag")

    with ua.case("ooa.asana/list_users", "people of a workspace and of a team",
                 "List users lists the people of a workspace, or of one team. Checks 3 in the workspace and 2 in the team.") as f:
        f.act("ooa.asana/list_users", desc="People of the workspace", workspace_id=WS, out={"count": "as_count"})
        ua.check_eq("as_count", 3)
        f.act("ooa.asana/list_users", desc="People of Finance Ops", team_id=TEAM, out={"table": "as_table", "count": "as_count"})
        ua.check_eq("as_count", 2)
        ua.check("eq", "$as_table.rows[1][2]$", "bruno.silva@ut.example", what="the second email")

    with ua.case("ooa.asana/get_user", "user by email and me",
                 "Get user reads a person by id, email or me (the token's own user). Checks name, id and workspaces.") as f:
        f.act("ooa.asana/get_user", desc="Read Chloe by email", user="chloe.martin@ut.example", out={"user": "as_user", "user_id": "as_id"})
        ua.check_eq("as_id", "1300000000000003")
        ua.check("eq", "$as_user.name$", "Chloe Martin", what="the name")
        ua.check("contains", "$as_user.workspaces$", "UT Org", what="the workspace")
        f.act("ooa.asana/get_user", desc="Read me", user="me", out={"user_id": "as_id"})
        ua.check_eq("as_id", "1300000000000001")

    with ua.case("ooa.asana/disconnect", "forget the connection",
                 "Disconnect forgets the connection Connect chose; later steps give their own address and token. Checks a "
                 "step with its own address and token still works, and one with neither fails.") as f:
        f.act("ooa.asana/disconnect", desc="Forget the UT Asana connection")
        f.act("ooa.asana/list_workspaces", desc="List with the step's own address and token", base_url=BASE,
              token=cred("asana"), out={"count": "as_count"})
        ua.check_eq("as_count", 1)
        ua.expect_error("*", lambda ff: ff.act("ooa.asana/get_user", desc="No connection and no token", user="me",
                                               base_url=BASE, out={"user_id": "as_id"}))

    # After Connect the steps leave the token to the connection. The flow check still asks for every required input
    # on a step without its own "connection", so these steps carry an empty token, which the connection then fills.
    blank_inputs(ua.f.steps, "ooa.asana/", {"token": ""})


def blank_inputs(steps, prefix, blanks):
    for s in steps:
        if not isinstance(s, dict):
            continue
        a = s.get("action", "")
        if a.startswith(prefix) and not a.endswith(("/connect", "/disconnect")):
            for k, v in blanks.items():
                s["args"].setdefault(k, v)
        for k in ("children", "else", "finally", "default"):
            if isinstance(s.get(k), list):
                blank_inputs(s[k], prefix, blanks)
        for k in ("elseIf", "catch", "cases"):
            for b in s.get(k) or []:
                if isinstance(b, dict) and isinstance(b.get("children"), list):
                    blank_inputs(b["children"], prefix, blanks)
