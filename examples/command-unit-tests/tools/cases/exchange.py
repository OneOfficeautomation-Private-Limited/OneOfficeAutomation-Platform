"""Exchange (EWS) cases on the stand-in's made-up mailbox (plugins/desktop_services.py, /dsk/ews).

The mailbox has two unread emails in the Inbox, AAMkID1 "Invoice 42" (attachments inv.pdf and note.txt) and AAMkID2
"Invoice 43", and the folders Inbox, Inbox/Invoices and Inbox/Done. Changes are answered but not kept, so every case
sees the same mailbox. The samples' cases replay their own canned answers (/dsk/mock).
"""
from helpers_desktop import cred, fix_samples, prepend

EWS = "$standin_url$/dsk/ews/EWS/Exchange.asmx"


def connect(f):
    f.act("ooa.exchange/connect", desc="Connect to the test mailbox", ews_url=EWS, user="UT\\bot", password=cred("exchange"),
          sign_in="basic")


def add(ua):
    fix_samples(ua, ["ooa.exchange"], service="exchange")
    prepend(ua, "list - no open session", "ooa.exchange/disconnect", "Close the session an earlier case left open")
    for n, t in (("ut_n", "Number"), ("ut_ok", "Boolean"), ("ut_msg", "String"), ("ut_list", "List"), ("ut_text", "String"),
                 ("ev", "Dictionary"), ("ut_table", "Table")):
        ua.var(n, t)

    with ua.case("ooa.exchange/test_connection", "the test mailbox answers",
                 "Test connection signs in once and says whether the mailbox answers, without opening a session.") as f:
        f.act("ooa.exchange/test_connection", desc="Test the connection", ews_url=EWS, user="UT\\bot", password=cred("exchange"),
              sign_in="basic", out={"ok": "ut_ok", "message": "ut_msg"})
        ua.check_eq("ut_ok", True)

    with ua.case("ooa.exchange/folder_exists", "a folder that is there, and one that is not",
                 "Folder exists is a condition: true when the folder path is in the mailbox. Inbox/Invoices is there, "
                 "Inbox/Nowhere is not.") as f:
        connect(f)
        f.set("ut_ok", False)
        with f.if_({"condition": "ooa.exchange/folder_exists", "args": {"folder": "Inbox/Invoices"}}, desc="Is Inbox/Invoices there?"):
            f.set("ut_ok", True)
        ua.check("eq", "$ut_ok$", True, what="Inbox/Invoices exists")
        with f.if_({"condition": "ooa.exchange/folder_exists", "args": {"folder": "Inbox/Nowhere"}}, desc="Is Inbox/Nowhere there?"):
            f.set("ut_ok", False)
        ua.check("eq", "$ut_ok$", True, what="Inbox/Nowhere missing")
        f.act("ooa.exchange/disconnect", desc="Close the session")

    with ua.case("ooa.exchange/disconnect", "a closed session cannot be used",
                 "Disconnect closes the session; a list step after it fails because no session is open.") as f:
        connect(f)
        f.act("ooa.exchange/disconnect", desc="Close the session")
        ua.expect_error("SESSION.NOT_FOUND", lambda ff: ff.act("ooa.exchange/list", desc="List after closing"))

    with ua.case("ooa.exchange/delete", "one email to Deleted Items",
                 "Delete moves one email, by its id, to Deleted Items (or removes it for good with permanent).") as f:
        connect(f)
        f.act("ooa.exchange/delete", desc="Delete Invoice 43", id="AAMkID2")
        f.act("ooa.exchange/delete", desc="Delete Invoice 42 for good", id="AAMkID1", permanent=True)
        f.act("ooa.exchange/disconnect", desc="Close the session")

    with ua.case("ooa.exchange/delete_all", "every invoice email",
                 "Delete all deletes every email that matches the filters and gives how many; both invoices match.") as f:
        connect(f)
        f.act("ooa.exchange/delete_all", desc="Delete the invoice emails", subject_contains="Invoice", out={"count": "ut_n"})
        ua.check_eq("ut_n", 2)
        f.act("ooa.exchange/disconnect", desc="Close the session")

    with ua.case("ooa.exchange/move_all", "invoices to Inbox/Done",
                 "Move all moves every matching email to a folder and gives their new ids.") as f:
        connect(f)
        f.act("ooa.exchange/move_all", desc="Move the unread invoices to Done", status="unread", subject_contains="Invoice",
              to_folder="Inbox/Done", out={"new_ids": "ut_list"})
        f.expr("ut_n", "len($ut_list)", desc="Count the moved emails")
        ua.check_eq("ut_n", 2)
        f.act("ooa.exchange/disconnect", desc="Close the session")

    with ua.case("ooa.exchange/save_all_attachments", "PDF files of every matching email",
                 "Save all attachments saves the attachments of every matching email that fit the name pattern; only "
                 "Invoice 42 has a PDF.") as f:
        f.act("ooa.folder/delete", desc="Start with an empty folder", path="ex_att", recursive=True, cont=True)
        connect(f)
        f.act("ooa.exchange/save_all_attachments", desc="Save the PDF attachments", subject_contains="Invoice", target_folder="ex_att",
              name_pattern="*.pdf", overwrite=True, out={"files": "ut_list"})
        f.expr("ut_n", "len($ut_list)", desc="Count the saved files")
        ua.check_eq("ut_n", 1)
        f.act("ooa.file/read_text", desc="Read the saved PDF's first bytes", path="ex_att/inv.pdf", out={"text": "ut_text"})
        ua.check("startsWith", "$ut_text$", "%PDF", what="saved file")
        f.act("ooa.exchange/disconnect", desc="Close the session")

    with ua.case("ooa.exchange/emails", "each unread invoice",
                 "Each email in the mailbox is a loop type: the steps run once per matching email, with its fields.") as f:
        connect(f)
        f.set("ut_n", 0)
        f.set("ut_text", "")
        with f.loop(desc="Each unread invoice", iterator="ooa.exchange/emails", **{"as": "ev"},
                    iterator_args={"unread_only": True, "subject_contains": "Invoice", "max_results": 10}):
            f.expr("ut_n", "$ut_n + 1", desc="Count the email")
            f.set("ut_text", "${ut_text}$ev.subject$;", desc="Collect the subject")
        ua.check_eq("ut_n", 2)
        ua.check_eq("ut_text", "Invoice 42;Invoice 43;")
        f.act("ooa.exchange/disconnect", desc="Close the session")

    with ua.case("ooa.exchange/list", "the whole test mailbox",
                 "List gives a table of the matching emails; the test mailbox's Inbox has two.") as f:
        connect(f)
        f.act("ooa.exchange/list", desc="List the Inbox", out={"table": "ut_table", "count": "ut_n"})
        ua.check_eq("ut_n", 2)
        f.act("ooa.exchange/get", desc="Read Invoice 42", id="AAMkID1", out={"email": "ev"})
        f.set("ut_text", "$ev.subject$", desc="Its subject")
        ua.check_eq("ut_text", "Invoice 42")
        f.act("ooa.exchange/disconnect", desc="Close the session")
