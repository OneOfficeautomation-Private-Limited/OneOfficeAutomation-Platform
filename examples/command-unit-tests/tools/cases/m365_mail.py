"""Microsoft 365 Outlook mail and calendar, and the Connect session, against the Graph stand-in (msg_graph.py)."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from helpers_msgoogle import start, check, g, GRAPH, M365_TOKEN, ME, ANN  # noqa: E402

M = "ooa.microsoft365/"
TBL = ("t", "Table")


def find(f, subject, folder="inbox", var="mid"):
    """The id of the first email whose subject contains the text."""
    f.act(M + "list_mail", desc="Find the email '" + subject + "'", out={"table": "t"}, **g(folder=folder, subject_contains=subject))
    f.expr(var, "$t.rows[0][0]", desc="Its id")


def event(f, subject, var="eid", **more):
    f.act(M + "create_event", desc="Create the test event '" + subject + "'", out={"event_id": var},
          **g(subject=subject, start="2026-12-01T10:00:00", end="2026-12-01T11:00:00", **more))


def add(ua):
    start(ua, "/msgraph/reset", "Every case below talks to the local Graph stand-in; its made-up mailbox una.tester@ut-m365.test and calendar start as seeded.")
    for v, t in (("t", "Table"), ("n", "Number"), ("mid", "String"), ("eid", "String"), ("e", "Dictionary"), ("files", "List"), ("s", "String"),
                 ("b", "Boolean"), ("l", "List"), ("cnt", "Number")):
        ua.var(v, t)

    # ---- Connect session
    with ua.case(M + "connect", "sign in once, later steps without a token",
                 "Connect keeps the access token and the Graph address; Find emails after it gives neither and still reads the inbox.") as f:
        f.act(M + "connect", desc="Sign in to Microsoft 365 once", access_token=M365_TOKEN, base_url=GRAPH)
        f.act(M + "list_mail", desc="Find emails with no token or address of its own", out={"count": "n"})
        ua.check("gt", "$n$", 0, what="emails found through the session")
        f.act(M + "disconnect", desc="Forget the sign-in again", out={"closed": "b"})
    with ua.case(M + "disconnect", "forget the sign-in", "Disconnect closes the Microsoft 365 session that Connect opened and says so.") as f:
        f.act(M + "connect", desc="Sign in", access_token=M365_TOKEN, base_url=GRAPH)
        f.act(M + "disconnect", desc="Sign out", out={"closed": "b"})
        ua.check_eq("b", True)

    # ---- Mail
    with ua.case(M + "send_mail", "to yourself with an attachment", "Send email posts through Graph; a mail to the test mailbox arrives in its inbox with the file.") as f:
        f.act("ooa.file/write_text", desc="Prepare the attachment", path="m365/note.txt", text="hello attachment")
        f.act(M + "send_mail", desc="Send the email", **g(to=ME, subject="UT hello", body="Hello from the unit test", attachments=["m365/note.txt"], importance="high"))
        f.act(M + "list_mail", desc="Look for it in the inbox", out={"count": "n", "table": "t"}, **g(subject_contains="UT hello"))
        ua.check_eq("n", 1)
        check(ua, "$t.rows[0][5]", True, "has_attachments of the arrived email")
    with ua.case(M + "list_mail", "unread emails from one sender", "Find emails lists the inbox newest first; unread_only and from_contains keep the two unread Acme invoices.") as f:
        f.act(M + "list_mail", desc="Find unread emails from Acme", out={"count": "n", "table": "t"}, **g(unread_only=True, from_contains="acme"))
        ua.check_eq("n", 2)
        check(ua, "$t.rows[0][2]", "Invoice 8 from Acme", "newest subject")
    with ua.case(M + "get_mail", "read one email without marking it", "Read email gives the email's details: subject, sender, text and the file attachment names (inline logo left out).") as f:
        find(f, "Invoice 7")
        f.act(M + "get_mail", desc="Read the email", out={"email": "e"}, **g(id="$mid$", mark_read=False))
        check(ua, "$e.subject", "Invoice 7 from Acme", "subject")
        check(ua, "$e.from", "ap@acme.test", "sender")
        check(ua, "$e.attachments", "inv-7.pdf", "attachment names", op="contains")
        check(ua, "$e.unread", True, "still unread")
    with ua.case(M + "mark_mail", "mark an email read", "Change status sets the read flag; Find emails then shows the email as read.") as f:
        f.act(M + "send_mail", desc="Send an email to mark", **g(to=ME, subject="UT mark me", body="x", save_to_sent_items=False))
        find(f, "UT mark me")
        f.act(M + "mark_mail", desc="Mark it read", **g(id="$mid$", status="read"))
        f.act(M + "list_mail", desc="Find it again", out={"table": "t"}, **g(subject_contains="UT mark me"))
        check(ua, "$t.rows[0][4]", False, "unread column")
    with ua.case(M + "move_mail", "to the Archive folder", "Move email moves an email by folder name and gives its new id; it is then in Archive.") as f:
        f.act(M + "send_mail", desc="Send an email to move", **g(to=ME, subject="UT move me", body="x", save_to_sent_items=False))
        find(f, "UT move me")
        f.act(M + "move_mail", desc="Move it to Archive", out={"new_id": "s"}, **g(id="$mid$", to_folder="archive"))
        ua.check("notEmpty", "$s$", what="new id")
        f.act(M + "list_mail", desc="Look in Archive", out={"count": "n"}, **g(folder="archive", subject_contains="UT move me"))
        ua.check_eq("n", 1)
    with ua.case(M + "mail_folder_exists", "a folder path and a missing folder", "Folder exists finds Inbox/Invoices by path with its id, and says false for a folder that is not there.") as f:
        f.act(M + "mail_folder_exists", desc="Look for Inbox/Invoices", out={"exists": "b", "folder_id": "s"}, **g(name="Inbox/Invoices"))
        ua.check_eq("b", True)
        ua.check_eq("s", "fld-invoices")
        f.act(M + "mail_folder_exists", desc="Look for a folder that is not there", out={"exists": "b"}, **g(name="Nope"))
        ua.check_eq("b", False)
    with ua.case(M + "move_all_mail", "newsletters to Archive", "Move all emails moves every email matching the filters and counts them.") as f:
        f.act(M + "move_all_mail", desc="Move the newsletter emails", out={"count": "n"}, **g(to_folder="archive", from_contains="news@"))
        ua.check_eq("n", 1)
    with ua.case(M + "delete_mail", "to Deleted Items", "Delete email moves the email to Deleted Items (not permanent).") as f:
        f.act(M + "send_mail", desc="Send an email to delete", **g(to=ME, subject="UT delete me", body="x", save_to_sent_items=False))
        find(f, "UT delete me")
        f.act(M + "delete_mail", desc="Delete it", **g(id="$mid$"))
        f.act(M + "list_mail", desc="Look in Deleted Items", out={"count": "n"}, **g(folder="deleteditems", subject_contains="UT delete me"))
        ua.check_eq("n", 1)
    with ua.case(M + "delete_all_mail", "every matching email", "Delete all emails removes each email matching the subject filter and counts them.") as f:
        for i in (1, 2):
            f.act(M + "send_mail", desc="Send bulk email %d" % i, **g(to=ME, subject="UT bulk %d" % i, body="x", save_to_sent_items=False))
        f.act(M + "delete_all_mail", desc="Delete the bulk emails", out={"count": "n"}, **g(subject_contains="UT bulk", permanent=True))
        ua.check_eq("n", 2)
    with ua.case(M + "forward_mail", "with a note", "Forward email sends the invoice on to a colleague with a note; a copy lands in Sent Items.") as f:
        find(f, "Invoice 7")
        f.act(M + "forward_mail", desc="Forward it", **g(id="$mid$", to=ANN, comment="Please check"))
        f.act(M + "list_mail", desc="Look in Sent Items", out={"count": "n"}, **g(folder="sentitems", subject_contains="FW: Invoice 7"))
        ua.check_eq("n", 1)
    with ua.case(M + "reply_mail", "to the sender", "Reply to email answers the sender; the reply is in Sent Items with RE: before the subject.") as f:
        find(f, "Team lunch")
        f.act(M + "reply_mail", desc="Reply", **g(id="$mid$", comment="Yes, Friday works"))
        f.act(M + "list_mail", desc="Look in Sent Items", out={"count": "n"}, **g(folder="sentitems", subject_contains="RE: Team lunch"))
        ua.check_eq("n", 1)
    with ua.case(M + "reply_all_mail", "everyone but the sender", "Reply all answers every recipient; with exclude_sender the sender is left out.") as f:
        find(f, "Team lunch")
        f.act(M + "reply_all_mail", desc="Reply to all", **g(id="$mid$", comment="Count me in", exclude_sender=True))
        f.act(M + "list_mail", desc="Look in Sent Items", out={"count": "n"}, **g(folder="sentitems", subject_contains="RE: Team lunch"))
        ua.check("gt", "$n$", 0, what="replies in Sent Items")
    with ua.case(M + "save_mail_attachments", "file attachments only", "Save attachments writes the email's files into a folder; without inline images only the PDF.") as f:
        find(f, "Invoice 7")
        f.act(M + "save_mail_attachments", desc="Save the attachments", out={"files": "files"},
              **g(id="$mid$", target_folder="m365/att1", include_inline=False, overwrite=True))
        check(ua, "len($files$)", 1, "files saved")
        check(ua, "$files[0]", "inv-7.pdf", "saved file", op="contains")
    with ua.case(M + "save_all_mail_attachments", "PDFs of every invoice", "Save all attachments saves the matching files of every email that matches.") as f:
        f.act(M + "save_all_mail_attachments", desc="Save the invoice PDFs", out={"files": "files", "email_count": "n"},
              **g(target_folder="m365/att2", subject_contains="Invoice", name_pattern="*.pdf", overwrite=True))
        ua.check_eq("n", 2)
        check(ua, "len($files$)", 2, "files saved")
    with ua.case(M + "save_mail_as_eml", "a whole email as .eml", "Save email as EML writes the email's MIME text to a file.") as f:
        find(f, "Invoice 7")
        f.act(M + "save_mail_as_eml", desc="Save as EML", out={"path": "s"}, **g(id="$mid$", local_path="m365/invoice7.eml"))
        f.act("ooa.file/read_text", desc="Read the file", path="$s$", out={"text": "s"})
        ua.check("contains", "$s$", "Invoice 7 from Acme", what="the .eml text")
    with ua.case(M + "save_mail_as_msg", "a whole email as Outlook .msg", "Save email as MSG builds an Outlook .msg file with the attachments in it.") as f:
        find(f, "Invoice 7")
        f.act(M + "save_mail_as_msg", desc="Save as MSG", out={"path": "s", "attachments": "n"}, **g(id="$mid$", local_path="m365/invoice7.msg"))
        ua.check_eq("n", 2)
        ua.check("contains", "$s$", "invoice7.msg", what="saved path")
    with ua.case(M + "emails", "each unread email", "The Each email loop goes through the unread emails from Acme one a turn.") as f:
        f.set("cnt", 0)
        with f.loop(desc="Each unread email", iterator=M + "emails", iterator_args=g(unread_only=True, from_contains="acme"), **{"as": "e"}):
            f.expr("cnt", "$cnt$ + 1", desc="Count it")
        ua.check_eq("cnt", 2)

    # ---- Calendar
    with ua.case(M + "create_event", "a Teams meeting with an attendee", "Create calendar event gives the event id and, for an online meeting, the Teams join link.") as f:
        event(f, "UT planning", attendees=ANN, online_meeting=True, location="Room 1")
        f.act(M + "create_event", desc="Create it again to read the link", out={"online_meeting_url": "s"},
              **g(subject="UT planning 2", start="2026-12-02T10:00:00", end="2026-12-02T11:00:00", online_meeting=True))
        ua.check("notEmpty", "$eid$", what="event id")
        ua.check("contains", "$s$", "teams.test/join", what="join link")
    with ua.case(M + "list_events", "events of a period", "Get calendar events lists the events between two dates; the seeded review is found by its title.") as f:
        f.act(M + "list_events", desc="List November events", out={"count": "n", "table": "t"},
              **g(start="2026-11-01T00:00:00", end="2026-11-30T00:00:00", subject_contains="Month-end"))
        ua.check_eq("n", 1)
        check(ua, "$t.rows[0][4]", "Room 4", "location")
    with ua.case(M + "update_event", "a new title", "Update calendar event changes the title; the period's list then shows it.") as f:
        event(f, "UT to rename")
        f.act(M + "update_event", desc="Rename it", out={"web_link": "s"}, **g(event_id="$eid$", subject="UT renamed"))
        f.act(M + "list_events", desc="List it", out={"count": "n"}, **g(start="2026-12-01T00:00:00", end="2026-12-02T00:00:00", subject_contains="UT renamed"))
        ua.check_eq("n", 1)
    with ua.case(M + "add_attendees", "one more person", "Add event attendees invites another person without inviting anyone twice.") as f:
        event(f, "UT add people", attendees=ANN)
        f.act(M + "add_attendees", desc="Invite Bob, and Ann again", out={"attendees": "l"}, **g(event_id="$eid$", attendees=[ANN, "bob.ray@ut-m365.test"]))
        check(ua, "len($l$)", 2, "attendees after the change")
    with ua.case(M + "remove_attendees", "take a person off", "Remove event attendees takes the person off the list.") as f:
        event(f, "UT remove people", attendees=ANN)
        f.act(M + "remove_attendees", desc="Take Ann off", out={"attendees": "l"}, **g(event_id="$eid$", attendees=ANN))
        check(ua, "len($l$)", 0, "attendees after the change")
    with ua.case(M + "clear_event_details", "blank the location", "Clear event details empties the ticked details, here the location.") as f:
        event(f, "UT clear", location="Room 9")
        f.act(M + "clear_event_details", desc="Clear the location", out={"web_link": "s"}, **g(event_id="$eid$", clear_location=True))
        f.act(M + "list_events", desc="List it", out={"table": "t"}, **g(start="2026-12-01T00:00:00", end="2026-12-02T00:00:00", subject_contains="UT clear"))
        check(ua, "$t.rows[0][4]", "", "location")
    with ua.case(M + "cancel_event", "with a note", "Cancel calendar event sends the cancellation; the step answers without an error.") as f:
        event(f, "UT cancel")
        f.act(M + "cancel_event", desc="Cancel it", **g(event_id="$eid$", message="Sorry, cancelled"))
        f.set("b", True, desc="The cancellation went through")
        ua.check_eq("b", True)
    with ua.case(M + "delete_event", "remove it", "Delete calendar event takes the event out of the calendar.") as f:
        event(f, "UT delete event")
        f.act(M + "delete_event", desc="Delete it", **g(event_id="$eid$"))
        f.act(M + "list_events", desc="List it", out={"count": "n"}, **g(start="2026-12-01T00:00:00", end="2026-12-02T00:00:00", subject_contains="UT delete event"))
        ua.check_eq("n", 0)
    with ua.case(M + "respond_to_event", "accept an invitation", "Respond to invitation accepts the seeded review meeting with a note.") as f:
        f.act(M + "list_events", desc="Find the review", out={"table": "t"}, **g(start="2026-11-01T00:00:00", end="2026-11-30T00:00:00", subject_contains="Month-end"))
        f.expr("eid", "$t.rows[0][0]")
        f.act(M + "respond_to_event", desc="Accept it", **g(event_id="$eid$", response="accept", comment="See you there"))
        f.set("b", True, desc="The answer went through")
        ua.check_eq("b", True)
    with ua.case(M + "add_event_attachment", "a local file", "Add event attachment attaches a small file to an event and gives its id.") as f:
        f.act("ooa.file/write_text", desc="Prepare the file", path="m365/agenda.txt", text="1. Budget")
        event(f, "UT with agenda")
        f.act(M + "add_event_attachment", desc="Attach the agenda", out={"attachment_id": "s"}, **g(event_id="$eid$", file="m365/agenda.txt"))
        ua.check("notEmpty", "$s$", what="attachment id")
    with ua.case(M + "find_meeting_times", "free slots", "Find free meeting times suggests slots when the attendees are free.") as f:
        f.act(M + "find_meeting_times", desc="Find an hour", out={"count": "n", "table": "t"},
              **g(attendees=[ANN], start="2026-12-03T09:00:00", end="2026-12-03T17:00:00", duration_minutes=60, max_results=2))
        ua.check_eq("n", 2)
        check(ua, "$t.rows[0][0]", "2026-12-03T09:00", "first slot start", op="contains")
    with ua.case(M + "calendar_events", "each event of a period", "The Each calendar event loop goes through the period's events one a turn.") as f:
        f.set("s", "")
        with f.loop(desc="Each November event", iterator=M + "calendar_events",
                    iterator_args=g(start="2026-11-01T00:00:00", end="2026-11-30T00:00:00"), **{"as": "e"}):
            f.expr("s", "$s$ + $e.subject + ';'", desc="Collect the title")
        ua.check_eq("s", "Month-end review;")
