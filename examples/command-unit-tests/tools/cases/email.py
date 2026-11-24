"""Email (IMAP/POP3 + SMTP) cases. They need a test mail server with the mailbox bot@sample.test (inputs mail_host,
imap_port, smtp_port; plain connections, no TLS), such as a local GreenMail; the default host mail.ut.test is made up,
so here the agent is built and validated only. Every message goes to the test mailbox itself, never outside."""
from helpers_desktop import add_converted, cred, find, fix_samples

MAIL = {'"127.0.0.1"': '"$mail_host$"', '"{imap_port}"': '"$imap_port$"', '"{smtp_port}"': '"$smtp_port$"'}
BOX = "bot@sample.test"


def connect(f):
    f.act("ooa.email/connect", desc="Open the test mailbox", imap_host="$mail_host$", port="$imap_port$", user=BOX,
          password=cred("email"), security="none", smtp_host="$mail_host$", smtp_port="$smtp_port$", smtp_security="none")


def seed(f, subject, attach=None):
    f.act("ooa.email/send", desc="Send a test email to the mailbox itself", to=BOX, subject=subject, body="UT body " + subject,
          attachments=attach)
    f.act("core/delay", desc="Let it arrive", seconds=2)


def add(ua):
    for k, v in (("mail_host", "mail.ut.test"), ("imap_port", 143), ("smtp_port", 25)):
        ua.f.var(k, "String" if isinstance(v, str) else "Number", "input", v, "Test mail server: " + k + ".")
    ua.var("mail", "Dictionary")          # the loop variable of the emails sample
    fix_samples(ua, ["ooa.email"], service="email", extra=MAIL)
    for s in find("ooa.email/", requires="mail"):
        add_converted(ua, s, service="email", extra=MAIL)
    for n, t in (("ut_n", "Number"), ("ut_ok", "Boolean"), ("ut_list", "List"), ("ut_table", "Table"), ("ut_id", "Number"),
                 ("ut_text", "String"), ("ev", "Dictionary"), ("ut_mail", "Dictionary")):
        ua.var(n, t)

    def first_id(f, subject):
        f.act("ooa.email/list", desc="Find the test email", subject_contains=subject, out={"table": "ut_table"})
        f.act("ooa.datatable/get_value", desc="Its id", table="$ut_table$", row=0, column="id", out={"value": "ut_id"})

    with ua.case("ooa.email/get_folders", "the mailbox's folders",
                 "Get folders lists the mailbox's folders; folder_exists tells whether one is there.") as f:
        connect(f)
        f.act("ooa.email/get_folders", desc="List the folders", out={"folders": "ut_list"})
        ua.check("contains", "$ut_list$", "INBOX", what="folders")
        f.act("ooa.email/folder_exists", desc="Is there an INBOX?", folder="INBOX", out={"exists": "ut_ok"})
        ua.check_eq("ut_ok", True)
        f.act("ooa.email/folder_exists", desc="A folder that is not there", folder="UT-Nowhere", out={"exists": "ut_ok"})
        ua.check_eq("ut_ok", False)
        f.act("ooa.email/disconnect", desc="Close the mailbox")

    with ua.case("ooa.email/get", "read one email by its id",
                 "Get reads one email (subject, sender, body, attachments) by its id.") as f:
        connect(f)
        seed(f, "UT get 1")
        first_id(f, "UT get 1")
        f.act("ooa.email/get", desc="Read it", id="$ut_id$", out={"email": "ut_mail"})
        f.set("ut_text", "$ut_mail.subject$", desc="Its subject")
        ua.check_eq("ut_text", "UT get 1")
        f.act("ooa.email/disconnect", desc="Close the mailbox")

    with ua.case("ooa.email/mark", "unread again",
                 "Mark sets an email read or unread (and flagged); listing the unread ones finds it again.") as f:
        connect(f)
        seed(f, "UT mark 1")
        first_id(f, "UT mark 1")
        f.act("ooa.email/mark", desc="Mark it read", id="$ut_id$", status="read")
        f.act("ooa.email/mark", desc="Mark it unread", id="$ut_id$", status="unread")
        f.act("ooa.email/list", desc="Unread test emails", status="unread", subject_contains="UT mark 1", out={"count": "ut_n"})
        ua.check("ge", "$ut_n$", 1, what="unread")
        f.act("ooa.email/disconnect", desc="Close the mailbox")

    with ua.case("ooa.email/move_all", "into a new folder",
                 "Move all moves every matching email to a folder (created when asked) and gives how many.") as f:
        connect(f)
        seed(f, "UT move-all 1")
        f.act("ooa.email/move_all", desc="Move them", to_folder="UT-Moved", subject_contains="UT move-all", create_folder=True,
              out={"count": "ut_n"})
        ua.check("ge", "$ut_n$", 1, what="moved")
        f.act("ooa.email/disconnect", desc="Close the mailbox")

    with ua.case("ooa.email/delete_all", "every test email of a subject",
                 "Delete all deletes every matching email and gives how many.") as f:
        connect(f)
        seed(f, "UT delete-all 1")
        f.act("ooa.email/delete_all", desc="Delete them", subject_contains="UT delete-all", permanent=True, out={"count": "ut_n"})
        ua.check("ge", "$ut_n$", 1, what="deleted")
        f.act("ooa.email/disconnect", desc="Close the mailbox")

    with ua.case("ooa.email/save_all_attachments", "the text files of matching emails",
                 "Save all attachments saves the attachments of every matching email into a folder.") as f:
        f.act("ooa.folder/delete", desc="Empty folders", path="em_all", recursive=True, cont=True)
        f.act("ooa.file/write_text", desc="A file to attach", path="em_all/src/note.txt", text="note")
        connect(f)
        seed(f, "UT save-all 1", ["em_all/src/note.txt"])
        f.act("ooa.email/save_all_attachments", desc="Save the attachments", target_folder="em_all/out", subject_contains="UT save-all",
              overwrite=True, out={"files": "ut_list", "email_count": "ut_n"})
        ua.check("ge", "$ut_n$", 1, what="emails")
        f.act("ooa.email/disconnect", desc="Close the mailbox")

    with ua.case("ooa.email/save_email_as_msg", "an Outlook .msg file",
                 "Save email as MSG writes one email as an Outlook .msg file.") as f:
        connect(f)
        seed(f, "UT msg 1")
        first_id(f, "UT msg 1")
        f.act("ooa.email/save_email_as_msg", desc="Save it", id="$ut_id$", local_path="em_msg", file_name="mail.msg",
              out={"path": "ut_text"})
        ua.check("endsWith", "$ut_text$", ".msg", what="path")
        f.act("ooa.email/disconnect", desc="Close the mailbox")

    with ua.case("ooa.email/save_attachments", "one email's attachment",
                 "Save attachments saves one email's attachments that fit a name pattern.") as f:
        f.act("ooa.folder/delete", desc="Empty folders", path="em_one", recursive=True, cont=True)
        f.act("ooa.file/write_text", desc="A file to attach", path="em_one/src/a.csv", text="1,2")
        connect(f)
        seed(f, "UT att 1", ["em_one/src/a.csv"])
        first_id(f, "UT att 1")
        f.act("ooa.email/save_attachments", desc="Save the CSV", id="$ut_id$", target_folder="em_one/out", name_pattern="*.csv",
              out={"files": "ut_list"})
        f.expr("ut_n", "len($ut_list)", desc="Count them")
        ua.check_eq("ut_n", 1)
        f.act("ooa.email/disconnect", desc="Close the mailbox")

    with ua.case("ooa.email/connect", "sign in, and a wrong password",
                 "Connect opens the mailbox over IMAP (POP3 also works) and names the outgoing server for later sends; a "
                 "wrong password is refused with an error.") as f:
        connect(f)
        f.act("ooa.email/disconnect", desc="Close the mailbox")
        ua.expect_error("*", lambda ff: ff.act("ooa.email/connect", desc="Sign in with a wrong password", imap_host="$mail_host$",
                                               port="$imap_port$", user=BOX, password=cred("email", "wrong_password"),
                                               security="none", session="wrong"))

    with ua.case("ooa.email/disconnect", "the session is gone afterwards",
                 "Disconnect closes the mailbox; listing after it fails because no session is open.") as f:
        connect(f)
        f.act("ooa.email/disconnect", desc="Close the mailbox")
        ua.expect_error("SESSION.NOT_FOUND", lambda ff: ff.act("ooa.email/list", desc="List after closing"))

    with ua.case("ooa.email/folder_exists", "INBOX is there",
                 "Folder exists says whether the mailbox has a folder.") as f:
        connect(f)
        f.act("ooa.email/folder_exists", desc="Is there an INBOX?", folder="INBOX", out={"exists": "ut_ok"})
        ua.check_eq("ut_ok", True)
        f.act("ooa.email/disconnect", desc="Close the mailbox")

    with ua.case("ooa.email/list", "test emails by subject, oldest first",
                 "List gives a table of the matching emails (id, subject, from, date, ...) and how many.") as f:
        connect(f)
        seed(f, "UT list A")
        seed(f, "UT list B")
        f.act("ooa.email/list", desc="List them", subject_contains="UT list", oldest_first=True, out={"table": "ut_table", "count": "ut_n"})
        ua.check("ge", "$ut_n$", 2, what="count")
        f.act("ooa.email/disconnect", desc="Close the mailbox")

    with ua.case("ooa.email/reply_all", "to the test mailbox",
                 "Reply all answers the sender and everyone in copy; the answer arrives in the test mailbox.") as f:
        connect(f)
        seed(f, "UT reply-all 1")
        first_id(f, "UT reply-all 1")
        f.act("ooa.email/reply_all", desc="Reply to all", id="$ut_id$", body="Noted by all", out={"message_id": "ut_text"})
        ua.check("contains", "$ut_text$", "@", what="message id")
        f.act("ooa.email/disconnect", desc="Close the mailbox")

    with ua.case("ooa.email/forward", "with a note, to the test mailbox",
                 "Forward sends an email on to other people with a note above it.") as f:
        connect(f)
        seed(f, "UT forward 1")
        first_id(f, "UT forward 1")
        f.act("ooa.email/forward", desc="Forward it", id="$ut_id$", to=BOX, note="FYI", out={"message_id": "ut_text"})
        ua.check("contains", "$ut_text$", "@", what="message id")
        f.act("ooa.email/disconnect", desc="Close the mailbox")

    with ua.case("ooa.email/save_email_as_eml", "a whole email as .eml",
                 "Save email as EML writes one email as a standard .eml file.") as f:
        connect(f)
        seed(f, "UT eml 1")
        first_id(f, "UT eml 1")
        f.act("ooa.email/save_email_as_eml", desc="Save it", id="$ut_id$", local_path="em_eml", file_name="mail.eml", out={"path": "ut_text"})
        f.act("ooa.file/read_text", desc="Read the file", path="$ut_text$", out={"text": "ut_text"})
        ua.check("contains", "$ut_text$", "UT eml 1", what="eml")
        f.act("ooa.email/disconnect", desc="Close the mailbox")

    with ua.case("ooa.email/delete", "one email",
                 "Delete removes one email by its id (to the trash, or for good with permanent).") as f:
        connect(f)
        seed(f, "UT delete 1")
        first_id(f, "UT delete 1")
        f.act("ooa.email/delete", desc="Delete it for good", id="$ut_id$", permanent=True)
        f.act("ooa.email/list", desc="Look for it", subject_contains="UT delete 1", out={"count": "ut_n"})
        ua.check_eq("ut_n", 0)
        f.act("ooa.email/disconnect", desc="Close the mailbox")
