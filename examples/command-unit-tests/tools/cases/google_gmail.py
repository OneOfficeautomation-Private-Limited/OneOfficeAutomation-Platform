"""Google Workspace Gmail against the Google stand-in (msg_google.py): the mailbox una.tester@ut-google.test
starts with two unread Acme invoices (label Invoices), a team lunch email and an old newsletter."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from helpers_msgoogle import start, check, gg, GOOGLE, GOOGLE_TOKEN, GME  # noqa: E402

M = "ooa.google/"


def find(f, subject, var="mid"):
    f.act(M + "gmail_list_messages", desc="Find the email '" + subject + "'", out={"table": "t"}, **gg(subject_contains=subject, label="INBOX"))
    f.expr(var, "$t.rows[0][0]", desc="Its id")


def send_self(f, subject):
    f.act(M + "gmail_send", desc="Send '" + subject + "' to the test mailbox", **gg(to=GME, subject=subject, body="unit test"))


def add(ua):
    start(ua, "/msgoogle/reset", "Every case below talks to the local Google stand-in; the Gmail mailbox starts as seeded.")
    for v, t in (("t", "Table"), ("n", "Number"), ("s", "String"), ("mid", "String"), ("e", "Dictionary"), ("l", "List"), ("files", "List"), ("cnt", "Number"), ("b", "Boolean")):
        ua.var(v, t)

    with ua.case(M + "gmail_send", "to yourself", "Send email sends through Gmail and gives the message id; a mail to the test mailbox arrives in INBOX.") as f:
        f.act(M + "gmail_send", desc="Send it", out={"message_id": "s"}, **gg(to=GME, subject="UT gmail hello", body="Hello", html=False))
        ua.check("notEmpty", "$s$", what="message id")
        f.act(M + "gmail_list_messages", desc="Look for it in INBOX", out={"count": "n"}, **gg(subject_contains="UT gmail hello", label="INBOX"))
        ua.check_eq("n", 1)
    with ua.case(M + "gmail_list_messages", "unread from Acme", "Find emails turns the filters into a Gmail search; the two unread Acme invoices come back, newest first.") as f:
        f.act(M + "gmail_list_messages", desc="Unread from Acme", out={"count": "n", "table": "t"}, **gg(status="unread", from_contains="acme"))
        ua.check_eq("n", 2)
        check(ua, "$t.rows[0][4]", "Invoice 8 from Acme", "newest subject")
    with ua.case(M + "gmail_get_message", "an invoice", "Read email gives headers, text and attachment names of one message.") as f:
        find(f, "Invoice 7")
        f.act(M + "gmail_get_message", desc="Read it", out={"email": "e"}, **gg(message_id="$mid$"))
        check(ua, "$e.subject", "Invoice 7 from Acme", "subject")
        check(ua, "$e.attachments", ["inv-7.pdf"], "attachment names")
        check(ua, "$e.text", "invoice 7", "text", op="contains")
    with ua.case(M + "gmail_mark_message", "mark read", "Change email status takes the UNREAD label off; the message is then not found among unread ones.") as f:
        send_self(f, "UT gmail mark")
        find(f, "UT gmail mark")
        f.act(M + "gmail_mark_message", desc="Mark it read", **gg(message_id="$mid$", status="read"))
        f.act(M + "gmail_list_messages", desc="Unread with that subject", out={"count": "n"}, **gg(subject_contains="UT gmail mark", status="unread"))
        ua.check_eq("n", 0)
    with ua.case(M + "gmail_move_message", "to Processed", "Move email adds the Processed label and takes INBOX off.") as f:
        send_self(f, "UT gmail move")
        find(f, "UT gmail move")
        f.act(M + "gmail_move_message", desc="Move it", out={"labels": "l"}, **gg(message_id="$mid$", add_labels="Processed"))
        ua.check("contains", "$l$", "Label_2", what="labels afterwards")
        check(ua, "contains($l$, 'INBOX')", False, "INBOX still there")
    with ua.case(M + "gmail_move_all", "every match to Processed", "Move all emails relabels every matching INBOX message and counts them.") as f:
        send_self(f, "UT gmail moveall 1")
        send_self(f, "UT gmail moveall 2")
        f.act(M + "gmail_move_all", desc="Move them", out={"count": "n"}, **gg(add_labels="Processed", subject_contains="UT gmail moveall", label="INBOX"))
        ua.check_eq("n", 2)
    with ua.case(M + "gmail_label_exists", "by name, not minding case", "Check label exists finds a label by name and gives its id.") as f:
        f.act(M + "gmail_label_exists", desc="Look for invoices", out={"exists": "b", "label_id": "s"}, **gg(label="invoices"))
        ua.check_eq("b", True)
        ua.check_eq("s", "Label_1")
    ua.condition_case(M + "gmail_label_exists", "as a condition", "Gmail label exists is also a condition: true for the Processed label.",
                      {"access_token": GOOGLE_TOKEN, "base_url": GOOGLE, "label": "Processed"}, True)
    ua.condition_case(M + "gmail_label_exists", "a label that is not there", "The same condition is false for a label that does not exist.",
                      {"access_token": GOOGLE_TOKEN, "base_url": GOOGLE, "label": "No such label"}, False)
    with ua.case(M + "gmail_delete_message", "to the Bin", "Delete email moves a message to the trash; it is then not found in INBOX.") as f:
        send_self(f, "UT gmail delete")
        find(f, "UT gmail delete")
        f.act(M + "gmail_delete_message", desc="Delete it", **gg(message_id="$mid$"))
        f.act(M + "gmail_list_messages", desc="Look in INBOX", out={"count": "n"}, **gg(subject_contains="UT gmail delete", label="INBOX"))
        ua.check_eq("n", 0)
    with ua.case(M + "gmail_delete_all", "every match, permanently", "Delete all emails deletes every matching message and counts them.") as f:
        send_self(f, "UT gmail delall 1")
        send_self(f, "UT gmail delall 2")
        f.act(M + "gmail_delete_all", desc="Delete them", out={"count": "n"}, **gg(subject_contains="UT gmail delall", label="INBOX", permanent=True))
        ua.check_eq("n", 2)
    with ua.case(M + "gmail_forward_message", "with a note", "Forward email sends the invoice on with a note and its attachment; it arrives as Fwd:.") as f:
        find(f, "Invoice 7")
        f.act(M + "gmail_forward_message", desc="Forward it to the test mailbox", out={"message_id": "s"}, **gg(message_id="$mid$", to=GME, note="FYI"))
        ua.check("notEmpty", "$s$", what="message id")
        f.act(M + "gmail_list_messages", desc="Look for the forward", out={"count": "n"}, **gg(subject_contains="Fwd: Invoice 7", label="INBOX", has_attachments_only=True))
        ua.check_eq("n", 1)
    with ua.case(M + "gmail_reply_message", "in the same thread", "Reply to email answers the sender in the conversation and gives the thread id.") as f:
        find(f, "Team lunch")
        f.act(M + "gmail_reply_message", desc="Reply", out={"message_id": "s", "thread_id": "mid"}, **gg(message_id="$mid$", body="Yes"))
        ua.check("notEmpty", "$s$", what="message id")
        ua.check("notEmpty", "$mid$", what="thread id")
    with ua.case(M + "gmail_reply_all", "everyone but the sender, new subject", "Reply all answers every recipient; here without the sender and with a new subject.") as f:
        find(f, "Team lunch")
        f.act(M + "gmail_reply_all", desc="Reply to all", out={"message_id": "s"}, **gg(message_id="$mid$", body="Count me in", exclude_sender=True, subject="UT lunch plan"))
        f.act(M + "gmail_list_messages", desc="Look for the reply", out={"count": "n"}, **gg(subject_contains="UT lunch plan"))
        ua.check("ge", "$n$", 1, what="replies found")
    with ua.case(M + "gmail_save_attachments", "PDFs of an invoice", "Save attachments writes the message's matching attachments into a folder.") as f:
        find(f, "Invoice 7")
        f.act(M + "gmail_save_attachments", desc="Save them", out={"files": "files"}, **gg(message_id="$mid$", target_folder="google/att1", name_pattern="*.pdf", overwrite=True))
        check(ua, "len($files$)", 1, "files saved")
        check(ua, "$files[0]", "inv-7.pdf", "saved file", op="contains")
    with ua.case(M + "gmail_save_all_attachments", "every invoice", "Save all attachments saves the files of every message with the Invoices label.") as f:
        f.act(M + "gmail_save_all_attachments", desc="Save them", out={"files": "files", "message_count": "n"}, **gg(target_folder="google/att2", label="Invoices", overwrite=True))
        ua.check_eq("n", 2)
        check(ua, "len($files$)", 2, "files saved")
    with ua.case(M + "gmail_save_message_as_eml", "the whole message", "Save email as EML writes the message's raw MIME to an .eml file.") as f:
        find(f, "Invoice 7")
        f.act(M + "gmail_save_message_as_eml", desc="Save it", out={"path": "s"}, **gg(message_id="$mid$", local_path="google/invoice7.eml"))
        f.act("ooa.file/read_text", desc="Read the file", path="$s$", out={"text": "s"})
        ua.check("contains", "$s$", "Invoice 7 from Acme", what="the .eml text")
    with ua.case(M + "gmail_messages", "each unread Acme email", "The Each email in Gmail loop goes through the matching messages one a turn.") as f:
        f.set("cnt", 0)
        with f.loop(desc="Each unread Acme email", iterator=M + "gmail_messages", iterator_args=gg(status="unread", from_contains="acme"), **{"as": "e"}):
            f.expr("cnt", "$cnt$ + 1", desc="Count it")
        ua.check_eq("cnt", 2)
