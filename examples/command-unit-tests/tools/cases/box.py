"""Box cases: Connect with a saved connection (written to the run folder's connections.json, pointing at the
stand-in), then every Box command without its own address or token; Disconnect at the end."""
import json

from helpers_business import drop_samples, cred, url, vars_


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


BASE = url("box")
FIN = "5000001"        # UT Finance
ARCH = "5000002"       # UT Archive
INVOICE = "6000001"    # invoice-2026-11.pdf in UT Finance
CONN = "UT Box"


def add(ua):
    drop_samples(ua)
    vars_(ua, "bx_conn", "bx_table", "bx_count", "bx_id", "bx_id2", "bx_info", "bx_url", "bx_durl", "bx_path", "bx_text")

    with ua.case("ooa.box/connect", "use a saved connection",
                 "Connect picks a saved connection (API and upload addresses plus a vault reference for the token) for "
                 "the later Box steps, which then need neither. The case first saves the connection in the run folder's "
                 "connections.json, pointing at the stand-in. Checks the connection's name comes back.") as f:
        conn = {CONN: {"package": "ooa.box", "values": {"base_url": BASE, "upload_url": BASE},
                       "credentials": {"access_token": "unit_tests/box/token"}}}
        f.act("ooa.file/write_text", desc="Save the stand-in connection", path="connections.json", text=json.dumps(conn, indent=1))
        f.act("ooa.box/connect", desc="Use the UT Box connection", connection=CONN, out={"connection": "bx_conn"})
        ua.check_eq("bx_conn", CONN)

    with ua.case("ooa.box/list_folder", "items of a folder",
                 "List folder items gives the files and folders inside a folder. Checks the two files of UT Finance.") as f:
        f.act("ooa.box/list_folder", desc="List UT Finance", folder_id=FIN, out={"table": "bx_table", "count": "bx_count"})
        ua.check_eq("bx_count", 2)
        ua.check("eq", "$bx_table.rows[0][2]$", "invoice-2026-11.pdf", what="the first file")

    with ua.case("ooa.box/create_folder", "new folder, then the same name again",
                 "Create folder makes a folder and gives its id; with reuse_existing on, the same name again gives the "
                 "existing folder's id instead of failing. Checks both ids match.") as f:
        f.act("ooa.box/create_folder", desc="Create UT Reports", parent_folder_id=FIN, name="UT Reports", out={"folder_id": "bx_id"})
        ua.check("notEmpty", "$bx_id$", what="the folder id")
        f.act("ooa.box/create_folder", desc="Create it again", parent_folder_id=FIN, name="UT Reports", reuse_existing=True,
              out={"folder_id": "bx_id2"})
        ua.check("eq", "$bx_id2$", "$bx_id$", what="the same folder")

    with ua.case("ooa.box/create_folder", "same name without reuse fails",
                 "With reuse_existing off, a folder name already in use fails with BOX.ALREADY_EXISTS.") as f:
        ua.expect_error("BOX.ALREADY_EXISTS", lambda ff: ff.act("ooa.box/create_folder", desc="Create UT Archive again",
                                                                 parent_folder_id="0", name="UT Archive", reuse_existing=False,
                                                                 out={"folder_id": "bx_id"}))

    with ua.case("ooa.box/get_folder_info", "folder details",
                 "Get folder info reads a folder's name, item count, parent and owner. Checks them.") as f:
        f.act("ooa.box/get_folder_info", desc="Read UT Archive", folder_id=ARCH, out={"info": "bx_info"})
        ua.check("eq", "$bx_info.name$", "UT Archive", what="the name")
        ua.check("eq", "$bx_info.item_count$", 1, what="the item count")
        ua.check("eq", "$bx_info.parent_name$", "All Files", what="the parent")
        ua.check("eq", "$bx_info.owner$", "ut.owner@ut.example", what="the owner")

    with ua.case("ooa.box/delete_folder", "folder with content",
                 "Delete folder moves a folder to the trash; one that still has content goes only with include_contents. "
                 "Checks the refusal without it, then that the folder is gone.") as f:
        f.act("ooa.box/create_folder", desc="A folder to delete", parent_folder_id="0", name="UT Scratch", out={"folder_id": "bx_id"})
        f.act("ooa.file/write_text", desc="Prepare a file", path="ut_box_scratch.txt", text="scratch")
        f.act("ooa.box/upload_file", desc="Put a file in it", folder_id="$bx_id$", local_path="ut_box_scratch.txt")
        ua.expect_error("BOX*", lambda ff: ff.act("ooa.box/delete_folder", desc="Delete without its content", folder_id="$bx_id$"))
        f.act("ooa.box/delete_folder", desc="Delete with its content", folder_id="$bx_id$", include_contents=True)
        ua.expect_error("BOX*", lambda ff: ff.act("ooa.box/get_folder_info", desc="Read the deleted folder", folder_id="$bx_id$",
                                                  out={"info": "bx_info"}))

    with ua.case("ooa.box/upload_file", "upload, then a new version",
                 "Upload file sends a local file to a folder; with replace_existing on, the same name again becomes a new "
                 "version of that file. Checks the version keeps the id and has the new size.") as f:
        f.act("ooa.box/create_folder", desc="A folder for uploads", parent_folder_id="0", name="UT Uploads", out={"folder_id": "bx_id2"})
        f.act("ooa.file/write_text", desc="Prepare a file", path="ut_box_up.txt", text="first")
        f.act("ooa.box/upload_file", desc="Upload it as notes.txt", folder_id="$bx_id2$", local_path="ut_box_up.txt",
              file_name="notes.txt", out={"file_id": "bx_id"})
        ua.check("notEmpty", "$bx_id$", what="the file id")
        f.act("ooa.file/write_text", desc="Change the file", path="ut_box_up.txt", text="second version")
        f.act("ooa.box/upload_file", desc="Upload it again as a new version", folder_id="$bx_id2$", local_path="ut_box_up.txt",
              file_name="notes.txt", replace_existing=True, out={"file_id": "bx_id2"})
        ua.check("eq", "$bx_id2$", "$bx_id$", what="the same file")
        f.act("ooa.box/get_file_info", desc="Read its size", file_id="$bx_id$", out={"info": "bx_info"})
        ua.check("eq", "$bx_info.size$", 14, what="the new size")

    with ua.case("ooa.box/download_file", "save a file",
                 "Download file saves a Box file on this machine. Checks the saved text.") as f:
        f.act("ooa.box/download_file", desc="Download the November invoice", file_id=INVOICE, save_as="ut_box_downloads/invoice.pdf",
              out={"path": "bx_path"})
        f.act("ooa.file/read_text", desc="Read the saved file", path="$bx_path$", out={"text": "bx_text"})
        ua.check_eq("bx_text", "PDF invoice November")

    with ua.case("ooa.box/copy_file", "copy under a new name",
                 "Copy file copies a file into a folder, optionally under a new name. Checks the copy's name and folder.") as f:
        f.act("ooa.box/copy_file", desc="Copy the invoice to UT Archive", file_id=INVOICE, target_folder_id=ARCH,
              new_name="invoice-copy.pdf", out={"file_id": "bx_id"})
        f.act("ooa.box/get_file_info", desc="Read the copy", file_id="$bx_id$", out={"info": "bx_info"})
        ua.check("eq", "$bx_info.name$", "invoice-copy.pdf", what="the name")
        ua.check("eq", "$bx_info.parent_id$", ARCH, what="the folder")

    with ua.case("ooa.box/move_file", "move to another folder",
                 "Move file moves a file to another folder. Checks the file's new folder.") as f:
        f.act("ooa.box/create_folder", desc="A target folder", parent_folder_id="0", name="UT Moved", out={"folder_id": "bx_id2"})
        f.act("ooa.box/copy_file", desc="A file to move", file_id=INVOICE, target_folder_id=ARCH, new_name="to-move.pdf", out={"file_id": "bx_id"})
        f.act("ooa.box/move_file", desc="Move it", file_id="$bx_id$", target_folder_id="$bx_id2$")
        f.act("ooa.box/get_file_info", desc="Read its folder", file_id="$bx_id$", out={"info": "bx_info"})
        ua.check("eq", "$bx_info.parent_name$", "UT Moved", what="the folder")

    with ua.case("ooa.box/rename_file", "new name",
                 "Rename file gives a file a new name in the same folder. Checks the new name.") as f:
        f.act("ooa.box/copy_file", desc="A file to rename", file_id=INVOICE, target_folder_id=ARCH, new_name="old-name.pdf", out={"file_id": "bx_id"})
        f.act("ooa.box/rename_file", desc="Rename it", file_id="$bx_id$", new_name="new-name.pdf")
        f.act("ooa.box/get_file_info", desc="Read its name", file_id="$bx_id$", out={"info": "bx_info"})
        ua.check("eq", "$bx_info.name$", "new-name.pdf", what="the name")

    with ua.case("ooa.box/delete_file", "delete for good",
                 "Delete file moves a file to the trash, and with permanently also empties it from the trash. Checks the "
                 "file can no longer be read.") as f:
        f.act("ooa.box/copy_file", desc="A file to delete", file_id=INVOICE, target_folder_id=ARCH, new_name="delete-me.pdf", out={"file_id": "bx_id"})
        f.act("ooa.box/delete_file", desc="Delete it for good", file_id="$bx_id$", permanently=True)
        ua.expect_error("BOX*", lambda ff: ff.act("ooa.box/get_file_info", desc="Read the deleted file", file_id="$bx_id$",
                                                  out={"info": "bx_info"}))

    with ua.case("ooa.box/get_file_info", "file details",
                 "Get file info reads a file's name, size, folder, owner and checksum. Checks them.") as f:
        f.act("ooa.box/get_file_info", desc="Read budget.xlsx", file_id="6000002", out={"info": "bx_info"})
        ua.check("eq", "$bx_info.name$", "budget.xlsx", what="the name")
        ua.check("eq", "$bx_info.size$", 12, what="the size")
        ua.check("eq", "$bx_info.parent_name$", "UT Finance", what="the folder")
        ua.check("notEmpty", "$bx_info.sha1$", what="the checksum")

    with ua.case("ooa.box/search", "pdf invoices in one folder",
                 "Search finds files and folders by name; it can keep to file types and one folder. Checks two invoices "
                 "overall and one inside UT Archive.") as f:
        f.act("ooa.box/search", desc="Search invoice pdf files", query="invoice-2026", item_type="file", file_extensions="pdf",
              out={"count": "bx_count"})
        ua.check_eq("bx_count", 2)
        f.act("ooa.box/search", desc="Only inside UT Archive", query="invoice-2026", in_folder_id=ARCH, out={"table": "bx_table", "count": "bx_count"})
        ua.check_eq("bx_count", 1)
        ua.check("eq", "$bx_table.rows[0][2]$", "invoice-2026-10.pdf", what="the file")

    with ua.case("ooa.box/create_shared_link", "company link with download",
                 "Create shared link turns on a file's shared link and gives the link and its download address. Checks both, "
                 "and that file info shows the link.") as f:
        f.act("ooa.box/create_shared_link", desc="Share the invoice", item_type="file", item_id=INVOICE, access="company",
              allow_download=True, expires_at="2026-12-31T00:00:00", out={"url": "bx_url", "download_url": "bx_durl"})
        ua.check("contains", "$bx_url$", INVOICE, what="the link")
        ua.check("notEmpty", "$bx_durl$", what="the download address")
        f.act("ooa.box/get_file_info", desc="Read the link back", file_id=INVOICE, out={"info": "bx_info"})
        ua.check("eq", "$bx_info.shared_link$", "$bx_url$", what="the shared link")

    with ua.case("ooa.box/remove_shared_link", "turn the link off",
                 "Remove shared link turns off an item's shared link. Checks the folder has no link afterwards.") as f:
        f.act("ooa.box/create_shared_link", desc="Share UT Archive", item_type="folder", item_id=ARCH, access="collaborators",
              out={"url": "bx_url"})
        f.act("ooa.box/remove_shared_link", desc="Turn the link off", item_type="folder", item_id=ARCH)
        f.act("ooa.box/get_folder_info", desc="Read the folder", folder_id=ARCH, out={"info": "bx_info"})
        ua.check("empty", "$bx_info.shared_link$", what="no shared link")

    with ua.case("ooa.box/add_collaborator", "invite a person as viewer",
                 "Invite collaborator gives a person or group access with a role. Checks the new collaboration is listed.") as f:
        f.act("ooa.box/add_collaborator", desc="Invite Bruno as viewer", item_type="folder", item_id=ARCH, email="bruno.silva@ut.example",
              role="viewer", notify=False, out={"collaboration_id": "bx_id"})
        ua.check("notEmpty", "$bx_id$", what="the collaboration id")
        f.act("ooa.box/list_collaborators", desc="List who has access", item_type="folder", item_id=ARCH, out={"table": "bx_table"})
        ua.check("eq", "$bx_table.rows[0][2]$", "bruno.silva@ut.example", what="the login")
        ua.check("eq", "$bx_table.rows[0][4]$", "viewer", what="the role")

    with ua.case("ooa.box/list_collaborators", "who has access",
                 "List collaborators lists who was invited to an item and their role. Checks the editor of UT Finance.") as f:
        f.act("ooa.box/list_collaborators", desc="List UT Finance collaborators", item_type="folder", item_id=FIN, out={"table": "bx_table"})
        ua.check("eq", "$bx_table.rows[0][0]$", "7000001", what="the collaboration id")
        ua.check("eq", "$bx_table.rows[0][4]$", "editor", what="the role")

    with ua.case("ooa.box/remove_collaborator", "take access away",
                 "Remove collaborator takes away access given by an invitation. Checks the file has no collaborators left.") as f:
        f.act("ooa.box/add_collaborator", desc="Invite a group as editor", item_type="file", item_id="6000002", group_id="9200",
              role="editor", out={"collaboration_id": "bx_id"})
        f.act("ooa.box/remove_collaborator", desc="Remove it", collaboration_id="$bx_id$")
        f.act("ooa.box/list_collaborators", desc="List what is left", item_type="file", item_id="6000002", out={"table": "bx_table"})
        ua.check("empty", "$bx_table.rows$", what="no collaborators")

    with ua.case("ooa.box/disconnect", "forget the connection",
                 "Disconnect forgets the connection Connect chose; later steps give their own address and token. Checks a "
                 "step with its own address and token still works.") as f:
        f.act("ooa.box/disconnect", desc="Forget the UT Box connection")
        f.act("ooa.box/list_folder", desc="List with the step's own address and token", folder_id=FIN, base_url=BASE,
              access_token=cred("box"), out={"count": "bx_count"})
        ua.check("ge", "$bx_count$", 2, what="the number of items")
        ua.expect_error("*", lambda ff: ff.act("ooa.box/list_folder", desc="No connection and no token", folder_id=FIN,
                                               base_url=BASE, out={"count": "bx_count"}))

    # After Connect the steps leave the token and addresses to the connection; the flow check still asks for the
    # required token on a step without its own "connection", so these steps carry an empty one, which Connect fills.
    blank_inputs(ua.f.steps, "ooa.box/", {"access_token": ""})
