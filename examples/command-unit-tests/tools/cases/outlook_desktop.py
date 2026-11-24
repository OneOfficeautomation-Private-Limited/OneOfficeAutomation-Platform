"""Outlook desktop cases. They need Outlook with a mailbox. Nothing is ever sent: every email is created as a draft whose
subject starts "UT outlook 9f3a", and every bulk command is limited to that subject, so the person's own mail is never
touched; the drafts are deleted again. Drives the screen (Outlook may open)."""
from helpers_desktop import add_converted, find, fix_samples

TAG = "UT outlook 9f3a"
NOBODY = "nobody@example.invalid"


def add(ua):
    fix_samples(ua, ["ooa.outlook_desktop"])
    for s in find("ooa.outlook_desktop/", requires="desktop"):
        add_converted(ua, s)
    for n, t in (("ut_id", "String"), ("ut_path", "String"), ("ut_id2", "String"), ("ut_n", "Number"), ("ut_text", "String"), ("ut_list", "List"),
                 ("ut_mail", "Dictionary"), ("ev", "Dictionary"), ("ut_cond", "Boolean")):
        ua.var(n, t)

    def draft(f, subject, attach=None):
        f.act("ooa.outlook_desktop/send", desc="Create a test draft (not sent)", to=NOBODY, subject=TAG + " " + subject,
              body="Unit test draft", attachments=attach, save_as_draft=True, out={"draft_id": "ut_id"})

    def tidy(f):
        f.act("ooa.outlook_desktop/delete_all", desc="Delete the test drafts", folder="Drafts", subject_contains=TAG, max=50, cont=True)

    with ua.case("ooa.outlook_desktop/connect", "the default mailbox",
                 "Connect attaches to Outlook (starting it if needed) for the steps that follow; Disconnect lets it go.") as f:
        f.act("ooa.outlook_desktop/connect", desc="Connect to Outlook")
        f.act("ooa.outlook_desktop/get_folders", desc="List the folders", out={"folders": "ut_list"})
        f.expr("ut_n", "len($ut_list)", desc="Count them")
        ua.check("gt", "$ut_n$", 0, what="folders")
        f.act("ooa.outlook_desktop/disconnect", desc="Disconnect")

    with ua.case("ooa.outlook_desktop/disconnect", "after connecting",
                 "Disconnect releases Outlook; Outlook itself stays open.") as f:
        f.act("ooa.outlook_desktop/connect", desc="Connect to Outlook")
        f.act("ooa.outlook_desktop/disconnect", desc="Disconnect")

    with ua.case("ooa.outlook_desktop/folder_exists", "Drafts is there",
                 "Folder exists is a condition: true when the mailbox has the folder.") as f:
        f.set("ut_cond", False)
        with f.if_({"condition": "ooa.outlook_desktop/folder_exists", "args": {"folder": "Drafts"}}, desc="Is there a Drafts folder?"):
            f.set("ut_cond", True)
        ua.check("eq", "$ut_cond$", True, what="Drafts")

    with ua.case("ooa.outlook_desktop/get", "a draft by its id",
                 "Get reads one email by its id.") as f:
        draft(f, "get")
        f.act("ooa.outlook_desktop/get", desc="Read the draft", id="$ut_id$", out={"email": "ut_mail"})
        f.set("ut_text", "$ut_mail.subject$", desc="Its subject")
        ua.check_eq("ut_text", TAG + " get")
        tidy(f)

    with ua.case("ooa.outlook_desktop/mark", "read with a category",
                 "Mark sets an email read or unread and can give it a category.") as f:
        draft(f, "mark")
        f.act("ooa.outlook_desktop/mark", desc="Mark it read, category Blue", id="$ut_id$", read=True, category="Blue category")
        tidy(f)

    with ua.case("ooa.outlook_desktop/reply", "to an unsent draft: Outlook refuses",
                 "Reply answers a received email; with save_as_draft the answer is only saved, never sent. The test mailbox "
                 "has no received email to answer, and Outlook does not allow a reply to an unsent draft, so the step "
                 "fails with OUTLOOK.FAILED.") as f:
        draft(f, "reply")
        ua.expect_error("OUTLOOK.FAILED", lambda g: g.act("ooa.outlook_desktop/reply", desc="Reply as a draft", id="$ut_id$",
                                                          body="Noted", save_as_draft=True, out={"draft_id": "ut_id2"}))
        tidy(f)

    with ua.case("ooa.outlook_desktop/forward", "saved as a draft",
                 "Forward sends an email on; with save_as_draft it is only saved, never sent.") as f:
        draft(f, "forward")
        f.act("ooa.outlook_desktop/forward", desc="Forward as a draft", id="$ut_id$", to=NOBODY, body="FYI", save_as_draft=True,
              out={"draft_id": "ut_id2"})
        ua.check("notEmpty", "$ut_id2$", what="draft id")
        f.act("ooa.outlook_desktop/delete", desc="Delete the forward draft", id="$ut_id2$", cont=True)
        tidy(f)

    with ua.case("ooa.outlook_desktop/save_email", "as a text file",
                 "Save email writes one email to a file (msg, txt, html or mht).") as f:
        draft(f, "save")
        f.act("ooa.outlook_desktop/save_email", desc="Save it as text", id="$ut_id$", save_to="ol_save", format="txt", file_name="draft.txt",
              overwrite=True, out={"path": "ut_text"})
        f.act("ooa.file/read_text", desc="Read the file", path="$ut_text$", out={"text": "ut_text"})
        ua.check("contains", "$ut_text$", TAG, what="saved email")
        tidy(f)

    with ua.case("ooa.outlook_desktop/save_attachments", "the draft's CSV",
                 "Save attachments saves one email's attachments that fit a pattern.") as f:
        f.act("ooa.file/write_text", desc="A file to attach", path="ol_att/src/a.csv", text="1,2")
        f.act("ooa.file/get_info", desc="Its folder and name", path="ol_att/src/a.csv", out={"info": "ut_mail"})
        f.expr("ut_path", "$ut_mail.folder + '/' + $ut_mail.name", desc="Its full path")
        draft(f, "attachments", ["$ut_path$"])
        f.act("ooa.outlook_desktop/save_attachments", desc="Save the CSV", id="$ut_id$", folder="ol_att/out", name_pattern="*.csv",
              overwrite=True, out={"files": "ut_list"})
        f.expr("ut_n", "len($ut_list)", desc="Count them")
        ua.check_eq("ut_n", 1)
        tidy(f)

    with ua.case("ooa.outlook_desktop/save_all", "every test draft",
                 "Save all saves every matching email of a folder to files.") as f:
        draft(f, "save all")
        f.act("ooa.outlook_desktop/save_all", desc="Save the test drafts", folder="Drafts", subject_contains=TAG, save_to="ol_all",
              format="txt", overwrite=True, out={"files": "ut_list"})
        f.expr("ut_n", "len($ut_list)", desc="Count them")
        ua.check("ge", "$ut_n$", 1, what="files")
        tidy(f)

    with ua.case("ooa.outlook_desktop/emails", "each test draft",
                 "Each email in Outlook is a loop type over the matching emails of a folder.") as f:
        draft(f, "loop")
        f.set("ut_n", 0)
        with f.loop(desc="Each test draft", iterator="ooa.outlook_desktop/emails", **{"as": "ev"},
                    iterator_args={"folder": "Drafts", "subject_contains": TAG, "max": 20}):
            f.expr("ut_n", "$ut_n + 1", desc="Count it")
        ua.check("ge", "$ut_n$", 1, what="drafts")
        tidy(f)

    with ua.case("ooa.outlook_desktop/move", "a draft to Deleted Items",
                 "Move moves one email to another folder and gives its new id.") as f:
        draft(f, "move")
        f.act("ooa.outlook_desktop/move", desc="Move it to Deleted Items", id="$ut_id$", folder="Deleted Items", out={"id": "ut_id2"})
        ua.check("notEmpty", "$ut_id2$", what="new id")
        f.act("ooa.outlook_desktop/delete", desc="Delete it for good", id="$ut_id2$", cont=True)

    with ua.case("ooa.outlook_desktop/move_all", "the test drafts to Deleted Items",
                 "Move all moves every matching email of a folder to another and gives their new ids.") as f:
        draft(f, "move all")
        f.act("ooa.outlook_desktop/move_all", desc="Move the test drafts", folder="Drafts", to_folder="Deleted Items", subject_contains=TAG,
              max=50, out={"count": "ut_n"})
        ua.check("ge", "$ut_n$", 1, what="moved")
        f.act("ooa.outlook_desktop/delete_all", desc="Remove them from Deleted Items", folder="Deleted Items", subject_contains=TAG,
              max=50, cont=True)

    with ua.case("ooa.outlook_desktop/delete_all", "the test drafts only",
                 "Delete all deletes every matching email of a folder (here only drafts with the test subject) and gives how many.") as f:
        draft(f, "delete all")
        f.act("ooa.outlook_desktop/delete_all", desc="Delete the test drafts", folder="Drafts", subject_contains=TAG, max=50,
              out={"count": "ut_n"})
        ua.check("ge", "$ut_n$", 1, what="deleted")

    with ua.case("ooa.outlook_desktop/delete", "one draft",
                 "Delete removes one email by its id.") as f:
        draft(f, "delete")
        f.act("ooa.outlook_desktop/delete", desc="Delete it", id="$ut_id$")
