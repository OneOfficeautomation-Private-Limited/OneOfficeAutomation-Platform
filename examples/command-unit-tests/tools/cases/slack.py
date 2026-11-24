"""Slack cases: Connect picks a saved connection (written to the run folder's connections.json, pointing at the
stand-in), so the steps after it give no bot token or address."""
from helpers_business import drop_samples, cred, url, vars_
from connect_business import saved_connection, leave_empty

API = url("slack", "/api")


def add(ua):
    drop_samples(ua)
    vars_(ua, "sl_conn", "sl_id", "sl_channel", "sl_ts", "sl_ts2", "sl_table", "sl_count", "sl_user", "sl_ws",
          "sl_files", "sl_n", "sl_item", "sl_dm")

    with ua.case("ooa.slack/connect", "use a saved connection for the later steps",
                 "Connect names a saved connection (address and bot token from the vault); the later Slack steps use it "
                 "when they give no token of their own. The case saves the connection ut_slack in the run folder first. "
                 "Checks the connection's name comes back and a step without a token works.") as f:
        saved_connection(f, "ut_slack", "ooa.slack", {"base_url": API}, {"bot_token": "unit_tests/slack/bot_token"})
        f.act("ooa.slack/connect", desc="Connect with ut_slack", connection="ut_slack", out={"connection": "sl_conn"})
        ua.check_eq("sl_conn", "ut_slack")
        f.act("ooa.slack/get_workspace_details", desc="Read the workspace through the connection", out={"workspace": "sl_ws"})
        ua.check("eq", "$sl_ws.id$", "TUT00001", what="the workspace id")

    with ua.case("ooa.slack/get_channel_id_by_name", "find a channel's id",
                 "Get channel id by name looks through the channels for the name (with or without #). Checks ut-ops' id.") as f:
        f.act("ooa.slack/get_channel_id_by_name", desc="Look up #ut-ops", name="#ut-ops", out={"channel_id": "sl_id"})
        ua.check_eq("sl_id", "CUT0001")

    with ua.case("ooa.slack/get_channel_details", "topic and member count",
                 "Get channel details reads a channel's name, topic, purpose, privacy and member count; a #name is looked "
                 "up first. Checks the topic and the three members.") as f:
        f.act("ooa.slack/get_channel_details", desc="Read #ut-ops", channel="#ut-ops", out={"channel": "sl_channel"})
        ua.check("eq", "$sl_channel.topic$", "Operations alerts", what="the topic")
        ua.check("eq", "$sl_channel.num_members$", 3, what="the member count")

    with ua.case("ooa.slack/post_message", "post with blocks to a named channel",
                 "Post message posts to a channel given by name, here with a Block Kit layout and the text as the "
                 "notification fallback. Checks a message timestamp id came back and the newest message has the text.") as f:
        f.act("ooa.slack/post_message", desc="Post to #ut-ops", channel="#ut-ops", text="Run 42 finished",
              blocks=[{"type": "section", "text": {"type": "mrkdwn", "text": "*Run 42* finished"}}], unfurl_links=False,
              out={"ts": "sl_ts"})
        ua.check("notEmpty", "$sl_ts$", what="the message id")
        f.act("ooa.slack/get_conversation_history", desc="Read the newest message", channel="CUT0001", max_messages=1,
              out={"table": "sl_table"})
        ua.check("eq", "$sl_table.rows[0][2]$", "Run 42 finished", what="the newest message")

    with ua.case("ooa.slack/post_message", "reply in a thread",
                 "Post message with thread_ts answers in a thread. Checks the reply is in the thread's replies.") as f:
        f.act("ooa.slack/post_message", desc="Reply in the nightly-run thread", channel="CUT0001", text="Reply from the agent",
              thread_ts="1767600000.000100", out={"ts": "sl_ts"})
        f.act("ooa.slack/get_conversation_history", desc="Read the thread", channel="CUT0001", thread_ts="1767600000.000100",
              out={"count": "sl_count", "table": "sl_table"})
        ua.check("ge", "$sl_count$", 3, what="the replies")

    with ua.case("ooa.slack/update_message", "edit a posted message",
                 "Update message changes the text of a message the agent posted. Checks the channel shows the new text.") as f:
        f.act("ooa.slack/post_message", desc="Post a progress message", channel="CUT0001", text="Progress 10%", out={"ts": "sl_ts"})
        f.act("ooa.slack/update_message", desc="Change it to 100%", channel="#ut-ops", ts="$sl_ts$", text="Progress 100%")
        f.act("ooa.slack/get_conversation_history", desc="Read the newest message", channel="CUT0001", max_messages=1,
              out={"table": "sl_table"})
        ua.check("eq", "$sl_table.rows[0][2]$", "Progress 100%", what="the edited text")

    with ua.case("ooa.slack/get_conversation_history", "messages between two dates",
                 "Get conversation history reads a channel's messages newest first; oldest and latest take dates. "
                 "Checks the two seeded January messages.") as f:
        f.act("ooa.slack/get_conversation_history", desc="Messages of 5 January 2026", channel="#ut-ops",
              oldest="2026-01-05", latest="2026-01-06", out={"table": "sl_table", "count": "sl_count"})
        ua.check_eq("sl_count", 2)
        ua.check("eq", "$sl_table.rows[0][2]$", "Invoices loaded", what="the newest of the two")

    with ua.case("ooa.slack/upload_file", "share a file with a comment",
                 "Upload file shares local files in a channel (Slack's external upload: get an address, send the bytes, "
                 "complete). Checks one file id came back.") as f:
        f.act("ooa.file/write_text", desc="Prepare the file to share", path="ut_slack_report.csv", text="run,result\n42,ok\n")
        f.act("ooa.slack/upload_file", desc="Share the report in #ut-ops", channel="#ut-ops", files=["ut_slack_report.csv"],
              comment="Report of run 42", out={"file_ids": "sl_files"})
        f.expr("sl_n", "len($sl_files$)", desc="Count the file ids")
        ua.check_eq("sl_n", 1)

    with ua.case("ooa.slack/send_direct_message", "message a person by email",
                 "Send direct message opens the direct conversation with a person found by email and posts there. "
                 "Checks the conversation id (D...) and the message id.") as f:
        f.act("ooa.slack/send_direct_message", desc="Message Ben", user="ben.ray@ut.example", text="Your invoices are ready",
              out={"ts": "sl_ts", "channel": "sl_dm"})
        ua.check("matches", "$sl_dm$", "^D", what="the direct conversation id")
        ua.check("notEmpty", "$sl_ts$", what="the message id")

    with ua.case("ooa.slack/open_conversation", "group conversation with two people",
                 "Open conversation with members opens (or finds) the conversation with the given members. Checks a "
                 "group conversation id (G...) and that opening it again gives the same id.") as f:
        f.act("ooa.slack/open_conversation", desc="Open with Ann and Ben", members=["UUT0001", "UUT0002"], out={"channel_id": "sl_id"})
        ua.check("matches", "$sl_id$", "^G", what="the conversation id")
        f.act("ooa.slack/open_conversation", desc="Open it again", members=["UUT0002", "UUT0001"], out={"channel_id": "sl_dm"})
        ua.check("eq", "$sl_dm$", "$sl_id$", what="the same conversation")

    with ua.case("ooa.slack/get_user_details", "by email and by member id",
                 "Get user details looks a person up by email or member id. Checks the title and the time zone.") as f:
        f.act("ooa.slack/get_user_details", desc="Look up Ann by email", user="ann.lee@ut.example", out={"user": "sl_user"})
        ua.check("eq", "$sl_user.title$", "Ops lead", what="Ann's title")
        f.act("ooa.slack/get_user_details", desc="Look up Ben by id", user="UUT0002", out={"user": "sl_user"})
        ua.check("eq", "$sl_user.time_zone$", "Asia/Kolkata", what="Ben's time zone")

    with ua.case("ooa.slack/get_workspace_id_by_name", "among the installed workspaces",
                 "Get workspace id by name finds a workspace by name or domain among those the app is installed in. "
                 "Checks UT Sandbox's id.") as f:
        f.act("ooa.slack/get_workspace_id_by_name", desc="Find UT Sandbox", name="ut sandbox", out={"workspace_id": "sl_id"})
        ua.check_eq("sl_id", "TUT00002")

    with ua.case("ooa.slack/get_workspace_details", "the token's workspace",
                 "Get workspace details reads the workspace's id, name and domain. Checks the domain.") as f:
        f.act("ooa.slack/get_workspace_details", desc="Read the workspace", out={"workspace": "sl_ws"})
        ua.check("eq", "$sl_ws.domain$", "ut-workspace", what="the domain")

    with ua.case("ooa.slack/list_channels", "public and private, archived too",
                 "List channels lists the conversations of the kinds asked for. Checks all three seeded channels with "
                 "archived ones included, and two without.") as f:
        f.act("ooa.slack/list_channels", desc="Public and private, archived too", types=["public_channel", "private_channel"],
              include_archived=True, out={"table": "sl_table", "count": "sl_count"})
        ua.check_eq("sl_count", 3)
        f.act("ooa.slack/list_channels", desc="Public and private, not archived", types=["public_channel", "private_channel"],
              out={"count": "sl_count"})
        ua.check_eq("sl_count", 2)

    with ua.case("ooa.slack/list_user_conversations", "a person's channels",
                 "List a person's conversations lists the channels a member belongs to. Ben is in ut-ops and ut-finance.") as f:
        f.act("ooa.slack/list_user_conversations", desc="Ben's channels", user="UUT0002",
              types=["public_channel", "private_channel"], out={"table": "sl_table", "count": "sl_count"})
        ua.check_eq("sl_count", 2)

    with ua.case("ooa.slack/channels", "loop over channels",
                 "The channels loop visits each channel of the kinds asked for, one record per pass. Checks two passes "
                 "and that ut-finance was seen.") as f:
        f.set("sl_n", 0)
        f.set("sl_id", "")
        with f.loop(desc="For each public or private channel", iterator="ooa.slack/channels",
                    iterator_args={"types": ["public_channel", "private_channel"]}, **{"as": "sl_item"}):
            f.expr("sl_n", "$sl_n$ + 1", desc="Count the channel")
            with f.if_({"op": "eq", "left": "$sl_item.name$", "right": "ut-finance"}, desc="Is it ut-finance?"):
                f.set("sl_id", "$sl_item.id$", desc="Keep its id")
        ua.check_eq("sl_n", 2)
        ua.check_eq("sl_id", "CUT0002")

    with ua.case("ooa.slack/messages", "loop over a thread's messages",
                 "The messages loop visits each message of a channel or thread, newest first. Checks the seeded "
                 "thread has at least two messages and the first is the opening one.") as f:
        f.set("sl_n", 0)
        f.set("sl_id", "")
        with f.loop(desc="For each message of the nightly-run thread", iterator="ooa.slack/messages",
                    iterator_args={"channel": "#ut-ops", "thread_ts": "1767600000.000100"}, **{"as": "sl_item"}):
            f.expr("sl_n", "$sl_n$ + 1", desc="Count the message")
            with f.if_({"op": "eq", "left": "$sl_n$", "right": 1}, desc="The first one?"):
                f.set("sl_id", "$sl_item.text$", desc="Keep its text")
        ua.check("ge", "$sl_n$", 2, what="the messages")
        ua.check_eq("sl_id", "Nightly run started")

    with ua.case("ooa.slack/disconnect", "forget the connection",
                 "Disconnect forgets the connection Connect chose; later steps then give their own bot token and address. "
                 "Checks a step without a token now fails, and one with its own token works.") as f:
        f.act("ooa.slack/disconnect", desc="Forget ut_slack")
        ua.expect_error("*", lambda ff: ff.act("ooa.slack/get_workspace_details", desc="No token now (stand-in address): should fail", base_url=API,
                                               out={"workspace": "sl_ws"}))
        f.act("ooa.slack/get_workspace_details", desc="With the step's own token and address",
              bot_token=cred("slack", "bot_token"), base_url=API, out={"workspace": "sl_ws"})
        ua.check("eq", "$sl_ws.name$", "UT Workspace", what="the workspace name")

    leave_empty(ua.f.steps, "ooa.slack", ("bot_token",))
