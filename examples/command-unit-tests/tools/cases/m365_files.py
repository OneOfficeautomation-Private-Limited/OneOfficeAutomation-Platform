"""Microsoft 365 OneDrive and SharePoint: drive files (folder UT with readme.txt, report.docx, Budget.xlsx and Archive),
SharePoint lists through Graph (site Finance, list Purchase requests) and through the site's own REST API (a site session)."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from helpers_msgoogle import start, check, g, SP_SITE, SP_TOKEN, SITE, ME, ANN  # noqa: E402

M = "ooa.microsoft365/"
LIST = "Purchase requests"


def upload(f, name, text="unit test content"):
    f.act("ooa.file/write_text", desc="Prepare " + name, path="m365/" + name, text=text)
    f.act(M + "upload_file", desc="Upload it to UT/work", **g(local_path="m365/" + name, remote_path="UT/work/" + name, if_exists="replace"))
    return "UT/work/" + name


def site_session(f):
    f.act(M + "sharepoint_connect", desc="Sign in to the Finance site", site_url=SP_SITE, access_token=SP_TOKEN, session="sharepoint")


def add(ua):
    start(ua, "/msgraph/reset", "Every case below talks to the local Graph and SharePoint stand-ins; the drive and the Finance site start as seeded.")
    for v, t in (("t", "Table"), ("n", "Number"), ("s", "String"), ("s2", "String"), ("e", "Dictionary"), ("l", "List"), ("b", "Boolean")):
        ua.var(v, t)

    # ---- drive files
    with ua.case(M + "upload_file", "a small file into a new folder", "Upload file puts a local file at a drive path and gives its id, link and size.") as f:
        f.act("ooa.file/write_text", desc="Prepare the file", path="m365/up.txt", text="12345")
        f.act(M + "upload_file", desc="Upload it", out={"item_id": "s", "size": "n"}, **g(local_path="m365/up.txt", remote_path="UT/uploads/up.txt", if_exists="replace"))
        ua.check("notEmpty", "$s$", what="item id")
        ua.check_eq("n", 5)
    with ua.case(M + "download_file", "readme.txt", "Download file saves a drive file locally.") as f:
        f.act(M + "download_file", desc="Download UT/readme.txt", out={"path": "s"}, **g(remote_path="UT/readme.txt", local_path="m365/dl/readme.txt"))
        f.act("ooa.file/read_text", desc="Read it", path="$s$", out={"text": "s2"})
        ua.check_eq("s2", "Unit test file, version 2")
    with ua.case(M + "list_files", "the UT folder", "List files lists the files and folders of a drive folder.") as f:
        f.act(M + "list_files", desc="List UT", out={"table": "t", "count": "n"}, **g(folder="UT"))
        ua.check("ge", "$n$", 4, what="items in UT")
        check(ua, "$t.rows[0][1]", "Archive", "first item")
        check(ua, "$t.rows[0][2]", "folder", "its type")
    with ua.case(M + "create_folder", "rename when taken", "Create folder makes a folder; asked again with rename, the second gets a number.") as f:
        f.act(M + "create_folder", desc="Make UT/New", **g(parent_folder="UT", name="New", if_exists="rename"))
        f.act(M + "create_folder", desc="Make UT/New again", out={"item_id": "s"}, **g(parent_folder="UT", name="New", if_exists="rename"))
        f.act(M + "get_item_info", desc="Read the second folder", out={"info": "e"}, **g(item="id:$s$"))
        check(ua, "$e.name", "New 1", "name of the second folder")
    with ua.case(M + "get_item_info", "readme.txt", "Get file info reads a file's name, type, size and parent path.") as f:
        f.act(M + "get_item_info", desc="Read UT/readme.txt", out={"info": "e"}, **g(item="UT/readme.txt"))
        check(ua, "$e.type", "file", "type")
        check(ua, "$e.size", 25, "size")
        check(ua, "$e.parent_path", "/drive/root:/UT", "parent path")
    with ua.case(M + "copy_item", "into Archive under a new name", "Copy file or folder copies and waits for the copy; it gives the new id.") as f:
        p = upload(f, "copy-me.txt")
        f.act(M + "copy_item", desc="Copy it", out={"status": "s", "item_id": "s2"}, **g(item=p, to_folder="UT/Archive", new_name="copied.txt", if_exists="replace"))
        ua.check_eq("s", "completed")
        f.act(M + "get_item_info", desc="Read the copy", out={"info": "e"}, **g(item="UT/Archive/copied.txt"))
        check(ua, "$e.id", "$s2$", "id of the copy")
    with ua.case(M + "move_item", "into Archive", "Move file or folder moves an item to another folder.") as f:
        p = upload(f, "move-me.txt")
        f.act(M + "move_item", desc="Move it", out={"item_id": "s"}, **g(item=p, to_folder="UT/Archive", if_exists="replace"))
        f.act(M + "get_item_info", desc="Read it in its new place", out={"info": "e"}, **g(item="UT/Archive/move-me.txt"))
        check(ua, "$e.id", "$s$", "same item")
    with ua.case(M + "rename_item", "a new name", "Rename file or folder renames an item in its folder.") as f:
        p = upload(f, "rename-me.txt")
        f.act(M + "rename_item", desc="Rename it", out={"item_id": "s"}, **g(item=p, new_name="renamed.txt", if_exists="replace"))
        f.act(M + "get_item_info", desc="Read it by the new name", out={"info": "e"}, **g(item="UT/work/renamed.txt"))
        check(ua, "$e.name", "renamed.txt", "new name")
    with ua.case(M + "delete_item", "to the recycle bin", "Delete file or folder removes the item; it is not found afterwards.") as f:
        p = upload(f, "delete-me.txt")
        f.act(M + "delete_item", desc="Delete it", **g(item=p))
        f.act(M + "find_items", desc="Search for it", out={"count": "n"}, **g(text="delete-me", exact_name=False))
        ua.check_eq("n", 0)
    with ua.case(M + "find_items", "files named readme", "Find files searches the drive by name; item_type file keeps only files.") as f:
        f.act(M + "find_items", desc="Search 'readme'", out={"table": "t", "count": "n"}, **g(text="readme", item_type="file"))
        ua.check_eq("n", 1)
        check(ua, "$t.rows[0][1]", "readme.txt", "name found")
    with ua.case(M + "list_permissions", "readme.txt", "List permissions lists who can open the file: the owner and Ann.") as f:
        f.act(M + "list_permissions", desc="Permissions of readme", out={"table": "t", "count": "n"}, **g(item="UT/readme.txt"))
        ua.check_eq("n", 2)
        check(ua, "$t.rows[1][2]", ANN, "granted to")
    with ua.case(M + "check_permissions", "Ann reads readme", "Check permission says whether a person has direct access, with the roles.") as f:
        f.act(M + "check_permissions", desc="Check Ann", out={"has_access": "b", "roles": "l"}, **g(item="UT/readme.txt", email=ANN))
        ua.check_eq("b", True)
        ua.check_eq("l", ["read"])
    with ua.case(M + "share_item", "edit rights for Bob", "Share file or folder invites people with a role and gives the new permission ids.") as f:
        p = upload(f, "share-me.txt")
        f.act(M + "share_item", desc="Share with Bob", out={"permission_ids": "l"}, **g(item=p, recipients="bob.ray@ut-m365.test", role="write", send_invitation=False))
        check(ua, "len($l$)", 1, "new permissions")
        f.act(M + "check_permissions", desc="Check Bob", out={"roles": "l"}, **g(item=p, email="bob.ray@ut-m365.test"))
        ua.check_eq("l", ["write"])
    with ua.case(M + "create_sharing_link", "view link for the organisation", "Create sharing link makes a view link and gives it with its permission id.") as f:
        f.act(M + "create_sharing_link", desc="Make a link to readme", out={"link": "s", "permission_id": "s2"}, **g(item="UT/readme.txt", type="view", scope="organization"))
        ua.check("contains", "$s$", "sharepoint.test", what="link")
        ua.check("notEmpty", "$s2$", what="permission id")
    with ua.case(M + "export_to_pdf", "the Word report", "Export to PDF converts a drive file and saves the PDF locally.") as f:
        f.act(M + "export_to_pdf", desc="Export report.docx", out={"path": "s"}, **g(item="UT/report.docx", local_path="m365/report.pdf"))
        f.act("ooa.file/read_text", desc="Read the PDF", path="$s$", out={"text": "s2"})
        ua.check("contains", "$s2$", "%PDF", what="PDF header")
    with ua.case(M + "restore_version", "the previous version", "Restore version makes the version before the current one current again.") as f:
        f.act(M + "restore_version", desc="Restore readme.txt", out={"restored_version": "s"}, **g(item="UT/readme.txt"))
        ua.check_eq("s", "1.0")
        f.act(M + "download_file", desc="Download it", out={"path": "s"}, **g(remote_path="UT/readme.txt", local_path="m365/dl/restored.txt"))
        f.act("ooa.file/read_text", desc="Read it", path="$s$", out={"text": "s2"})
        ua.check_eq("s2", "Unit test file, version 1")

    # ---- SharePoint lists through Graph
    G = dict(site=SITE, list=LIST)
    with ua.case(M + "list_lists", "the Finance site", "List SharePoint lists lists the site's lists.") as f:
        f.act(M + "list_lists", desc="Lists of Finance", out={"table": "t", "count": "n"}, **g(site=SITE))
        ua.check("ge", "$n$", 1, what="lists")
        check(ua, "$t.rows[0][2]", LIST, "display name")
    with ua.case(M + "get_list_items", "open items with display names", "Get SharePoint list items filters by field values; use_display_names names the columns as people see them.") as f:
        f.act(M + "get_list_items", desc="Open purchase requests", out={"table": "t", "count": "n"}, **g(match={"Status": "Open"}, use_display_names=True, **G))
        ua.check_eq("n", 2)
        check(ua, "$t.columns", "Amount", "column names", op="contains")
    with ua.case(M + "create_list_item", "a new request", "Create SharePoint list item adds an item and gives its id.") as f:
        f.act(M + "create_list_item", desc="Add Desk", out={"item_id": "s"}, **g(fields={"Title": "Desk", "Status": "Open"}, **G))
        f.act(M + "get_list_items", desc="Find Desk", out={"count": "n"}, **g(filter="fields/Title eq 'Desk'", **G))
        ua.check_eq("n", 1)
    with ua.case(M + "update_list_item", "close a request", "Update SharePoint list item changes one field; others keep their values.") as f:
        f.act(M + "create_list_item", desc="Add Lamp", out={"item_id": "s"}, **g(fields={"Title": "Lamp", "Status": "Open"}, **G))
        f.act(M + "update_list_item", desc="Close it", **g(item_id="$s$", fields={"Status": "Closed"}, **G))
        f.act(M + "get_list_items", desc="Read Lamp", out={"table": "t"}, **g(filter="fields/Title eq 'Lamp'", columns=["Title", "Status"], **G))
        check(ua, "$t.rows[0][2]", "Closed", "status")
    with ua.case(M + "delete_list_item", "remove a request", "Delete SharePoint list item removes the item.") as f:
        f.act(M + "create_list_item", desc="Add Bin", out={"item_id": "s"}, **g(fields={"Title": "Bin"}, **G))
        f.act(M + "delete_list_item", desc="Delete it", **g(item_id="$s$", **G))
        f.act(M + "get_list_items", desc="Look for Bin", out={"count": "n"}, **g(filter="fields/Title eq 'Bin'", **G))
        ua.check_eq("n", 0)
    with ua.case(M + "create_list", "a task list", "Create SharePoint list makes a list from a template and gives its id.") as f:
        f.act(M + "create_list", desc="Make UT Tasks", out={"list_id": "s"}, **g(site=SITE, name="UT Tasks", template="tasks", description="Unit test"))
        ua.check("notEmpty", "$s$", what="list id")
    with ua.case(M + "update_list", "rename it", "Update SharePoint list renames a list.") as f:
        f.act(M + "create_list", desc="Make UT Rename", **g(site=SITE, name="UT Rename"))
        f.act(M + "update_list", desc="Rename it", **g(site=SITE, list="UT Rename", new_name="UT Renamed"))
        f.act(M + "get_list_items", desc="Read the renamed list", out={"count": "n"}, **g(site=SITE, list="UT Renamed"))
        ua.check_eq("n", 0)
    with ua.case(M + "delete_list", "remove it", "Delete SharePoint list removes a list; reading it afterwards fails.") as f:
        f.act(M + "create_list", desc="Make UT Gone", **g(site=SITE, name="UT Gone"))
        f.act(M + "delete_list", desc="Delete it", **g(site=SITE, list="UT Gone"))
        ua.expect_error("*", lambda ff: ff.act(M + "get_list_items", desc="Read the deleted list", **g(site=SITE, list="UT Gone")))
    with ua.case(M + "add_list_column", "a choice column", "Add SharePoint list column adds a choice column and gives its id.") as f:
        f.act(M + "add_list_column", desc="Add Priority", out={"column_id": "s"}, **g(column_name="Priority", type="choice", choices=["High", "Low"], **G))
        ua.check("notEmpty", "$s$", what="column id")

    # ---- the site's own REST API (a site session)
    with ua.case(M + "sharepoint_connect", "the Finance site", "Sign in to SharePoint site opens a site session and gives the site's title; later list steps use it.") as f:
        f.act(M + "sharepoint_connect", desc="Sign in", out={"title": "s"}, site_url=SP_SITE, access_token=SP_TOKEN)
        ua.check_eq("s", "Finance")
        f.act(M + "list_lists", desc="Lists through the session", out={"count": "n"}, session="sharepoint")
        ua.check("ge", "$n$", 1, what="lists")
    with ua.case(M + "list_attachments", "the laptop request", "Get SharePoint item attachments lists the files attached to an item.") as f:
        site_session(f)
        f.act(M + "list_attachments", desc="Attachments of item 1", out={"names": "l"}, list=LIST, item_id="1")
        ua.check_eq("l", ["quote.pdf"])
    with ua.case(M + "add_attachment", "a picture", "Add SharePoint item attachment attaches a local file to an item.") as f:
        site_session(f)
        f.act("ooa.file/write_text", desc="Prepare the file", path="m365/photo.txt", text="photo")
        f.act(M + "add_attachment", desc="Attach it to item 2", list=LIST, item_id="2", file="m365/photo.txt", file_name="photo.txt")
        f.act(M + "list_attachments", desc="Attachments of item 2", out={"names": "l"}, list=LIST, item_id="2")
        ua.check_eq("l", ["photo.txt"])
    with ua.case(M + "download_attachments", "the quote", "Download SharePoint item attachments saves the files attached to an item.") as f:
        site_session(f)
        f.act(M + "download_attachments", desc="Save item 1's files", out={"files": "l"}, list=LIST, item_id="1", folder="m365/sp", overwrite=True)
        check(ua, "$l[0]", "quote.pdf", "saved file", op="contains")
    with ua.case(M + "delete_attachment", "remove a file", "Delete SharePoint item attachment removes an attached file.") as f:
        site_session(f)
        f.act("ooa.file/write_text", desc="Prepare the file", path="m365/old.txt", text="old")
        f.act(M + "add_attachment", desc="Attach it to item 3", list=LIST, item_id="3", file="m365/old.txt")
        f.act(M + "delete_attachment", desc="Delete it", list=LIST, item_id="3", file_name="old.txt")
        f.act(M + "list_attachments", desc="Attachments of item 3", out={"names": "l"}, list=LIST, item_id="3")
        check(ua, "len($l$)", 0, "attachments left")
