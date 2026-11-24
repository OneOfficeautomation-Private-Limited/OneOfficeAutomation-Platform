"""Google Workspace Drive against the Google stand-in: My Drive holds the folder UT (id fld-ut) with Archive, Templates
(letter.txt), readme.txt (id file-readme, shared with Ann and the domain), the spreadsheet UT Budget and the document UT Letter."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from helpers_msgoogle import start, check, gg  # noqa: E402

M = "ooa.google/drive_"
ANN = "ann.lee@ut-google.test"


def upload(f, name, var="s"):
    f.act("ooa.file/write_text", desc="Prepare " + name, path="google/" + name, text="unit test content")
    f.act(M + "upload", desc="Upload it into UT", out={"file_id": var}, **gg(local_path="google/" + name, folder_id="fld-ut"))


def add(ua):
    start(ua, "/msgoogle/reset", "Every case below talks to the local Google stand-in; My Drive starts as seeded.")
    for v, t in (("t", "Table"), ("n", "Number"), ("s", "String"), ("s2", "String"), ("e", "Dictionary"), ("b", "Boolean")):
        ua.var(v, t)

    with ua.case(M + "upload", "into a folder by path", "Upload to Drive puts a local file into a folder given by path and gives its id.") as f:
        f.act("ooa.file/write_text", desc="Prepare the file", path="google/up.txt", text="12345")
        f.act(M + "upload", desc="Upload it to /UT/Archive", out={"file_id": "s"}, **gg(local_path="google/up.txt", folder_id="/UT/Archive", description="unit test"))
        f.act(M + "get_file_info", desc="Read it", out={"info": "e"}, **gg(item_id="$s$"))
        check(ua, "$e.size", 5, "size")
        check(ua, "$e.parents", ["fld-archive"], "parent folder")
    with ua.case(M + "list", "the UT folder", "List Drive files lists the files in a folder, here those whose name contains readme.") as f:
        f.act(M + "list", desc="List UT", out={"table": "t", "count": "n"}, **gg(folder_id="fld-ut", name_contains="readme"))
        ua.check_eq("n", 1)
        check(ua, "$t.rows[0][0]", "file-readme", "id")
    with ua.case(M + "download", "readme.txt by path", "Download from Drive saves a file, picked by its path, into a local folder.") as f:
        f.act(M + "download", desc="Download /UT/readme.txt", out={"path": "s"}, **gg(file_id="/UT/readme.txt", local_path="google/dl/", overwrite=True))
        f.act("ooa.file/read_text", desc="Read it", path="$s$", out={"text": "s2"})
        ua.check_eq("s2", "Unit test file")
    with ua.case(M + "download_export", "the document as text", "Export Google file saves a Google Doc in another format, here plain text.") as f:
        f.act(M + "download_export", desc="Export UT Letter", out={"path": "s"}, **gg(file_id="doc-letter", format="txt", local_path="google/letter.txt"))
        f.act("ooa.file/read_text", desc="Read it", path="$s$", out={"text": "s2"})
        ua.check("contains", "$s2$", "Dear {{name}}", what="exported text")
    with ua.case(M + "create_folder", "inside UT", "Create Drive folder makes a folder and gives its id and link.") as f:
        f.act(M + "create_folder", desc="Make UT/Reports", out={"folder_id": "s"}, **gg(name="Reports", parent_id="fld-ut"))
        f.act(M + "find_items", desc="Find it", out={"count": "n"}, **gg(name_equals="Reports", item_type="folder", folder_id="fld-ut"))
        ua.check_eq("n", 1)
    with ua.case(M + "copy_file", "by path into Archive", "Copy Drive file copies a file, picked by path, into another folder under a new name.") as f:
        f.act(M + "copy_file", desc="Copy the letter", out={"file_id": "s"}, **gg(item_path="UT/Templates/letter.txt", destination_path="UT/Archive", name="letter copy.txt"))
        f.act(M + "get_file_info", desc="Read the copy", out={"info": "e"}, **gg(item_id="$s$"))
        check(ua, "$e.name", "letter copy.txt", "name")
    with ua.case(M + "copy_folder", "Templates with its files", "Copy Drive folder copies a folder and every file in it, and counts the files.") as f:
        f.act(M + "copy_folder", desc="Copy Templates into Archive", out={"folder_id": "s", "file_count": "n"}, **gg(folder_path="UT/Templates", destination_path="UT/Archive", name="Templates copy"))
        ua.check_eq("n", 1)
        f.act(M + "list", desc="List the copy", out={"count": "n"}, **gg(folder_id="$s$"))
        ua.check_eq("n", 1)
    with ua.case(M + "move_item", "into Archive", "Move Drive item moves a file to another folder.") as f:
        upload(f, "move-me.txt")
        f.act(M + "move_item", desc="Move it", **gg(item_id="$s$", folder_id="fld-archive"))
        f.act(M + "get_file_info", desc="Read it", out={"info": "e"}, **gg(item_id="$s$"))
        check(ua, "$e.parents", ["fld-archive"], "parents")
    with ua.case(M + "rename_item", "a new name", "Rename Drive item gives a file a new name.") as f:
        upload(f, "rename-me.txt")
        f.act(M + "rename_item", desc="Rename it", **gg(item_id="$s$", new_name="renamed.txt"))
        f.act(M + "get_file_info", desc="Read it", out={"info": "e"}, **gg(item_id="$s$"))
        check(ua, "$e.name", "renamed.txt", "name")
    with ua.case(M + "delete_item", "to the trash", "Delete Drive item trashes a file; it is then trashed in its info.") as f:
        upload(f, "trash-me.txt")
        f.act(M + "delete_item", desc="Trash it", **gg(item_id="$s$"))
        f.act(M + "get_file_info", desc="Read it", out={"info": "e"}, **gg(item_id="$s$"))
        check(ua, "$e.trashed", True, "trashed")
    with ua.case(M + "find_items", "files in UT", "Find Drive items finds by folder path and type; folders are left out.") as f:
        f.act(M + "find_items", desc="Files directly in UT", out={"table": "t", "count": "n"}, **gg(folder_path="UT", item_type="file", name_contains="readme"))
        ua.check_eq("n", 1)
        check(ua, "$t.rows[0][3]", False, "is_folder")
    with ua.case(M + "get_file_info", "readme.txt", "Get Drive item info reads a file's name, type, size, owners and parents.") as f:
        f.act(M + "get_file_info", desc="Read readme.txt", out={"info": "e"}, **gg(item_id="file-readme"))
        check(ua, "$e.mime_type", "text/plain", "type")
        check(ua, "$e.owners", ["una.tester@ut-google.test"], "owners")
    with ua.case(M + "list_permissions", "readme.txt", "List Drive permissions lists the owner, Ann and the domain.") as f:
        f.act(M + "list_permissions", desc="Permissions of readme", out={"table": "t", "count": "n"}, **gg(item_id="file-readme"))
        ua.check_eq("n", 3)
        check(ua, "$t.rows[1][3]", ANN, "email of the second")
    with ua.case(M + "create_permission", "a writer", "Share Drive item gives a person access with a role and gives the permission id.") as f:
        upload(f, "share-me.txt")
        f.act(M + "create_permission", desc="Share with Bob as writer", out={"permission_id": "s2"}, **gg(item_id="$s$", email="bob.ray@ut-google.test", role="writer", notify=False))
        ua.check("notEmpty", "$s2$", what="permission id")
        f.act(M + "check_permission", desc="Can Bob write?", out={"has_access": "b"}, **gg(item_id="$s$", email="bob.ray@ut-google.test", access="write"))
        ua.check_eq("b", True)
    with ua.case(M + "update_permission", "reader to writer", "Change Drive permission changes a permission's role.") as f:
        upload(f, "role-me.txt")
        f.act(M + "create_permission", desc="Share with Bob as reader", out={"permission_id": "s2"}, **gg(item_id="$s$", email="bob.ray@ut-google.test", notify=False))
        f.act(M + "update_permission", desc="Make him a writer", **gg(item_id="$s$", permission_id="$s2$", role="writer"))
        f.act(M + "check_permission", desc="Bob's role", out={"role": "s2"}, **gg(item_id="$s$", email="bob.ray@ut-google.test"))
        ua.check_eq("s2", "writer")
    with ua.case(M + "delete_permission", "take access away", "Remove Drive permission removes a permission and says so.") as f:
        upload(f, "unshare-me.txt")
        f.act(M + "create_permission", desc="Share with Bob", out={"permission_id": "s2"}, **gg(item_id="$s$", email="bob.ray@ut-google.test", notify=False))
        f.act(M + "delete_permission", desc="Remove it", out={"deleted": "b"}, **gg(item_id="$s$", permission_id="$s2$"))
        ua.check_eq("b", True)
        f.act(M + "check_permission", desc="Can Bob read?", out={"has_access": "b"}, **gg(item_id="$s$", email="bob.ray@ut-google.test"))
        ua.check_eq("b", False)
    with ua.case(M + "check_permission", "through the domain", "Check Drive permission counts a domain permission for a person of that domain.") as f:
        f.act(M + "check_permission", desc="Can Carol comment?", out={"has_access": "b", "role": "s"}, **gg(item_id="file-readme", email="carol@ut-google.test", access="comment"))
        ua.check_eq("b", True)
        ua.check_eq("s", "commenter")
