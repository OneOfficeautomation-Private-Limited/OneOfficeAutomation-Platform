"""Microsoft 365 Teams, chats and Planner against the Graph stand-in (team UT Finance with channels General and Invoices,
a one-on-one chat with Ann, plan Month end with the task Close ledger)."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from helpers_msgoogle import start, check, g, ANN, ANN_ID, TEAM, GENERAL  # noqa: E402

M = "ooa.microsoft365/"
CHAT = "19:chat-ann@unq.gbl.spaces"


def add(ua):
    start(ua, "/msgraph/reset", "Every case below talks to the local Graph stand-in; the team UT Finance, its channels, a chat and a plan start as seeded.")
    for v, t in (("t", "Table"), ("n", "Number"), ("s", "String"), ("s2", "String"), ("e", "Dictionary"), ("cnt", "Number")):
        ua.var(v, t)

    with ua.case(M + "list_teams", "the user's teams", "List teams lists the teams the signed-in user belongs to.") as f:
        f.act(M + "list_teams", desc="List my teams", out={"table": "t", "count": "n"}, **g())
        ua.check_eq("n", 1)
        check(ua, "$t.rows[0][1]", "UT Finance", "team name")
    with ua.case(M + "get_team_by_name", "UT Finance", "Get team by name finds a team's id from its name, not minding case.") as f:
        f.act(M + "get_team_by_name", desc="Find ut finance", out={"team_id": "s"}, **g(name="ut finance"))
        ua.check_eq("s", TEAM)
    with ua.case(M + "get_team_details", "name and visibility", "Get team details reads a team's name, visibility and archive state.") as f:
        f.act(M + "get_team_details", desc="Read the team", out={"team": "e"}, **g(team_id=TEAM))
        check(ua, "$e.visibility", "private", "visibility")
        check(ua, "$e.archived", False, "archived")
    with ua.case(M + "list_channels", "the team's channels", "List channels lists a team's channels.") as f:
        f.act(M + "list_channels", desc="List the channels", out={"count": "n", "table": "t"}, **g(team_id=TEAM))
        ua.check_eq("n", 2)
        check(ua, "$t.rows[1][1]", "Invoices", "second channel")
    with ua.case(M + "get_channel_by_name", "by team name", "Get channel by name finds a channel's id, the team given by name too.") as f:
        f.act(M + "get_channel_by_name", desc="Find General in UT Finance", out={"channel_id": "s"}, **g(team_name="UT Finance", name="general"))
        ua.check_eq("s", GENERAL)
    with ua.case(M + "get_channel_details", "General", "Get channel details reads a channel's name, type and email address.") as f:
        f.act(M + "get_channel_details", desc="Read General", out={"channel": "e"}, **g(team_id=TEAM, channel_id=GENERAL))
        check(ua, "$e.membership_type", "standard", "membership type")
        check(ua, "$e.email", "general@ut-m365.test", "email")
    with ua.case(M + "create_channel", "a standard channel", "Create channel adds a channel; List channels then shows three.") as f:
        f.act(M + "create_channel", desc="Add UT Reports", out={"channel_id": "s"}, **g(team_id=TEAM, name="UT Reports", description="Reports"))
        ua.check("notEmpty", "$s$", what="channel id")
        f.act(M + "get_channel_by_name", desc="Find it by name", out={"channel_id": "s2"}, **g(team_id=TEAM, name="UT Reports"))
        ua.check_eq("s2", "$s$")
    with ua.case(M + "get_channel_messages", "posts, newest first", "Get channel messages reads a channel's posts with their text taken out of the HTML.") as f:
        f.act(M + "get_channel_messages", desc="Read General", out={"table": "t", "count": "n"}, **g(team_id=TEAM, channel_id=GENERAL))
        ua.check_eq("n", 2)
        check(ua, "$t.rows[1][4]", "Hello & welcome", "text of the oldest post")
    with ua.case(M + "teams_message", "by team and channel name", "Post Teams message posts to a channel chosen by names, with a subject; it is then the newest post.") as f:
        f.act(M + "teams_message", desc="Post to Invoices", out={"message_id": "s"}, **g(team_name="UT Finance", channel_name="Invoices", message="Run finished", subject="UT run"))
        ua.check("notEmpty", "$s$", what="message id")
        f.act(M + "get_channel_messages", desc="Read Invoices", out={"table": "t"}, **g(team_id=TEAM, channel_id="19:invoices@thread.tacv2"))
        check(ua, "$t.rows[0][3]", "UT run", "subject of the newest post")
    with ua.case(M + "reply_to_message", "in a thread", "Reply in channel posts a reply in the thread of a post and gives the reply's id.") as f:
        f.act(M + "reply_to_message", desc="Reply to the welcome post", out={"reply_id": "s"}, **g(team_id=TEAM, channel_id=GENERAL, message_id="1700000000001", message="Thanks"))
        ua.check("notEmpty", "$s$", what="reply id")
    with ua.case(M + "create_chat", "with Ann, reused", "Create chat finds the existing one-on-one chat with Ann instead of making a second one.") as f:
        f.act(M + "create_chat", desc="Chat with Ann", out={"chat_id": "s"}, **g(user=ANN))
        ua.check_eq("s", CHAT)
    with ua.case(M + "send_chat_message", "straight to a person", "Send chat message to a person uses their one-on-one chat and gives the message and chat ids.") as f:
        f.act(M + "send_chat_message", desc="Message Ann", out={"chat_id": "s", "message_id": "s2"}, **g(to=ANN, message="Report is ready", importance="high"))
        ua.check_eq("s", CHAT)
        ua.check("notEmpty", "$s2$", what="message id")
    with ua.case(M + "get_user", "Ann's profile", "Get user reads a person's directory profile.") as f:
        f.act(M + "get_user", desc="Read Ann", out={"user": "e"}, **g(user=ANN))
        check(ua, "$e.job_title", "Analyst", "job title")
        check(ua, "$e.id", ANN_ID, "id")
    with ua.case(M + "teams", "each team", "The Each team loop goes through the user's teams.") as f:
        f.set("s", "")
        with f.loop(desc="Each team", iterator=M + "teams", iterator_args=g(), **{"as": "e"}):
            f.expr("s", "$s$ + $e.name", desc="Collect the name")
        ua.check_eq("s", "UT Finance")
    with ua.case(M + "channels", "each channel", "The Each channel loop goes through a team's channels.") as f:
        f.set("cnt", 0)
        with f.loop(desc="Each channel", iterator=M + "channels", iterator_args=g(team_id=TEAM), **{"as": "e"}):
            f.expr("cnt", "$cnt$ + 1")
        ua.check("ge", "$cnt$", 2, what="channels")
    with ua.case(M + "channel_messages", "each post since a date", "The Each message in a channel loop goes through the posts created since a date.") as f:
        f.set("cnt", 0)
        with f.loop(desc="Each post since 19 Nov", iterator=M + "channel_messages", iterator_args=g(team_id=TEAM, channel_id=GENERAL, since="2026-11-19T00:00:00"), **{"as": "e"}):
            f.expr("cnt", "$cnt$ + 1")
        ua.check_eq("cnt", 1)
    with ua.case(M + "chats", "each chat", "The Each chat loop goes through the user's chats.") as f:
        f.set("s", "")
        with f.loop(desc="Each chat", iterator=M + "chats", iterator_args=g(), **{"as": "e"}):
            f.expr("s", "$s$ + $e.chat_type", desc="Collect the chat type")
        ua.check("contains", "$s$", "oneOnOne", what="chat types")
    with ua.case(M + "chat_messages", "each message, system events left out", "The Each chat message loop gives the chat's messages but not its system events.") as f:
        f.set("s", "")
        with f.loop(desc="Each message", iterator=M + "chat_messages", iterator_args=g(chat_id=CHAT), **{"as": "e"}):
            f.expr("s", "$s$ + $e.text + ';'", desc="Collect the text")
        ua.check("contains", "$s$", "See you at 3;", what="message texts")
    # ---- Planner
    with ua.case(M + "list_plans", "the team's plans", "List plans lists the Planner plans of a group (a team's id is its group id).") as f:
        f.act(M + "list_plans", desc="List the plans", out={"table": "t", "count": "n"}, **g(group_id=TEAM))
        ua.check_eq("n", 1)
        check(ua, "$t.rows[0][1]", "Month end", "plan title")
    with ua.case(M + "list_tasks", "assigned to Ann", "List tasks lists a plan's tasks, here only those assigned to Ann.") as f:
        f.act(M + "list_tasks", desc="Ann's tasks", out={"table": "t", "count": "n"}, **g(plan_id="plan-ut-1", assigned_to=ANN_ID))
        ua.check_eq("n", 1)
        check(ua, "$t.rows[0][1]", "Close ledger", "task title")
    with ua.case(M + "create_task", "with a due date", "Create task adds a task to the plan; List tasks then finds it.") as f:
        f.act(M + "create_task", desc="Add a task", out={"task_id": "s"}, **g(plan_id="plan-ut-1", bucket_id="bucket-todo", title="UT reconcile", due_date="2026-12-05T17:00:00", assigned_to=[ANN_ID]))
        ua.check("notEmpty", "$s$", what="task id")
        f.act(M + "list_tasks", desc="Tasks of the plan", out={"count": "n"}, **g(plan_id="plan-ut-1"))
        ua.check_eq("n", 2)
    with ua.case(M + "update_task", "half done", "Update task reads the task's version (ETag) and changes its progress.") as f:
        f.act(M + "create_task", desc="Add a task to change", out={"task_id": "s"}, **g(plan_id="plan-ut-1", bucket_id="bucket-ut-progress", title="UT progress"))
        f.act(M + "update_task", desc="Set it to 50 percent", **g(task_id="$s$", percent_complete=50, title="UT progress, half"))
        f.act(M + "list_tasks", desc="Read the task back by its bucket", out={"table": "t"}, **g(plan_id="plan-ut-1", bucket_id="bucket-ut-progress"))
        check(ua, "$t.rows[0][3]", 50, "percent complete")
