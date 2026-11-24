"""Google Workspace Calendar and Docs against the Google stand-in: the primary calendar holds the Month-end review on
25 Nov 2026; the document doc-letter (UT Letter) holds a letter with {{name}} and {{order}} placeholders."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from helpers_msgoogle import start, check, gg  # noqa: E402

M = "ooa.google/"
ANN = "ann.lee@ut-google.test"


def event(f, subject, var="s", **more):
    f.act(M + "calendar_create_event", desc="Create the test event '" + subject + "'", out={"event_id": var},
          **gg(subject=subject, start="2026-12-01T10:00:00", end="2026-12-01T11:00:00", time_zone="UTC", notify=False, **more))


def listed(f, text, out):
    f.act(M + "calendar_list_events", desc="List 1 December events with '" + text + "'", out=out,
          **gg(start="2026-12-01T00:00:00", end="2026-12-02T00:00:00", time_zone="UTC", search=text))


def add(ua):
    start(ua, "/msgoogle/reset", "Every case below talks to the local Google stand-in; the calendar and the documents start as seeded.")
    for v, t in (("t", "Table"), ("n", "Number"), ("s", "String"), ("s2", "String")):
        ua.var(v, t)

    with ua.case(M + "calendar_create_event", "with Meet link and attendee", "Create Google Calendar event gives the event id, its link and a Meet link.") as f:
        event(f, "UT planning", attendees=ANN, location="Room 1", add_meet_link=True, reminder_minutes=10)
        f.act(M + "calendar_create_event", desc="Create another to read the Meet link", out={"meet_link": "s2"},
              **gg(subject="UT planning 2", start="2026-12-02T10:00:00", end="2026-12-02T11:00:00", time_zone="UTC", add_meet_link=True, notify=False))
        ua.check("notEmpty", "$s$", what="event id")
        ua.check("contains", "$s2$", "meet.test", what="Meet link")
    with ua.case(M + "calendar_list_events", "the review in November", "List Google Calendar events lists the events of a period, earliest first.") as f:
        f.act(M + "calendar_list_events", desc="List November", out={"table": "t", "count": "n"},
              **gg(start="2026-11-01T00:00:00", end="2026-11-30T00:00:00", time_zone="UTC", search="Month-end"))
        ua.check_eq("n", 1)
        check(ua, "$t.rows[0][5]", "Room 4", "location")
    with ua.case(M + "calendar_update_event", "a new title and place", "Update Google Calendar event changes the title and location.") as f:
        event(f, "UT to rename")
        f.act(M + "calendar_update_event", desc="Rename it", out={"link": "s2"}, **gg(event_id="$s$", subject="UT renamed", location="Room 7", notify=False))
        listed(f, "UT renamed", {"table": "t", "count": "n"})
        ua.check_eq("n", 1)
        check(ua, "$t.rows[0][5]", "Room 7", "location")
    with ua.case(M + "calendar_delete_event", "remove it", "Delete Google Calendar event removes the event from the calendar.") as f:
        event(f, "UT delete me")
        f.act(M + "calendar_delete_event", desc="Delete it", **gg(event_id="$s$", notify=False))
        listed(f, "UT delete me", {"count": "n"})
        ua.check_eq("n", 0)

    with ua.case(M + "docs_create_document", "with starting text", "Create Google Doc makes a document with text and gives its id and link.") as f:
        f.act(M + "docs_create_document", desc="Create UT Notes", out={"document_id": "s", "url": "s2"}, **gg(title="UT Notes", text="First line"))
        ua.check("contains", "$s2$", "docs.google.com/document/d/", what="link")
        f.act(M + "docs_read_text", desc="Read it", out={"text": "s2"}, **gg(document_id="$s$"))
        ua.check_eq("s2", "First line\n")
    with ua.case(M + "docs_read_text", "the letter by its link", "Read Google Doc text reads the text of a document given by its link.") as f:
        f.act(M + "docs_read_text", desc="Read UT Letter", out={"text": "s", "title": "s2"}, **gg(document_id="https://docs.google.com/document/d/doc-letter/edit"))
        ua.check_eq("s2", "UT Letter")
        ua.check("contains", "$s$", "Your order {{order}} is ready.", what="text")
    with ua.case(M + "docs_replace_text", "fill the placeholders", "Replace text in Google Doc fills placeholders and counts the places changed.") as f:
        f.act(M + "docs_create_document", desc="Make a copy of the letter text", out={"document_id": "s"}, **gg(title="UT Filled", text="Dear {{name}}, order {{order}} for {{name}}."))
        f.act(M + "docs_replace_text", desc="Fill it", out={"occurrences_changed": "n"}, **gg(document_id="$s$", replacements={"{{name}}": "Ann", "{{order}}": "A-7"}))
        ua.check_eq("n", 3)
        f.act(M + "docs_read_text", desc="Read it", out={"text": "s2"}, **gg(document_id="$s$"))
        ua.check_eq("s2", "Dear Ann, order A-7 for Ann.\n")
    with ua.case(M + "docs_append_text", "a new paragraph", "Add text to Google Doc adds text at the end, on a new line.") as f:
        f.act(M + "docs_create_document", desc="Make a document", out={"document_id": "s"}, **gg(title="UT Append", text="Line one"))
        f.act(M + "docs_append_text", desc="Add a line", **gg(document_id="$s$", text="Line two"))
        f.act(M + "docs_read_text", desc="Read it", out={"text": "s2"}, **gg(document_id="$s$"))
        ua.check_eq("s2", "Line one\nLine two\n")
