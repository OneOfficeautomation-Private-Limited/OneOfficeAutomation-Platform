"""Hand-written cases for the Folder commands (the samples cover each command; these add the positive cases)."""
from helpers_office import fix_samples, fcase, screen_case


def add(ua):
    fix_samples(ua)
    for n, t in (("p", "File"), ("ok", "Boolean"), ("created", "Boolean"), ("renamed", "Boolean"), ("n", "Number")):
        ua.var(n, t)

    with fcase(ua, "ooa.folder/create", "numbered when it exists",
               "With if_exists = numbered, Create makes a new folder with a number added when the name is taken.") as (f, d):
        f.act("ooa.folder/create", desc="The first Reports folder", path=d + "/Reports")
        f.act("ooa.folder/create", desc="Create Reports again, numbered", path=d + "/Reports", if_exists="numbered",
              out={"path": "p", "created": "created"})
        ua.check_eq("created", True)
        ua.check("ne", "$p$", d + "/Reports", what="the numbered path")
    with fcase(ua, "ooa.folder/create_shortcut", "shortcut to a folder",
               "Create shortcut makes a .lnk file that opens the folder; the case checks the shortcut file exists.") as (f, d):
        f.act("ooa.folder/create", desc="The folder the shortcut opens", path=d + "/Target")
        f.act("ooa.folder/create_shortcut", desc="Create a shortcut to Target", path=d + "/Target",
              shortcut=d + "/Target.lnk", out={"path": "p"})
        f.set("ok", False)
        with f.if_({"condition": "ooa.file/exists", "args": {"path": d + "/Target.lnk"}}, desc="Is the shortcut there?"):
            f.set("ok", True)
        ua.check_eq("ok", True)
    with fcase(ua, "ooa.folder/rename_folder", "only when larger than a limit",
               "Rename folder with larger_than_kb leaves a smaller folder as it is and reports renamed false.") as (f, d):
        f.act("ooa.file/write_text", desc="A small file in the folder", path=d + "/a/x.txt", text="1")
        f.act("ooa.folder/rename_folder", desc="Rename only when over 100 KB", path=d + "/a", new_name="b",
              larger_than_kb=100, out={"renamed": "renamed"})
        ua.check_eq("renamed", False)
    with fcase(ua, "ooa.folder/is_empty", "a new folder is empty",
               "Is empty is true for a folder with nothing in it.") as (f, d):
        f.act("ooa.folder/create", desc="An empty folder", path=d + "/empty")
        f.set("ok", False)
        with f.if_({"condition": "ooa.folder/is_empty", "args": {"path": d + "/empty"}}, desc="Is it empty?"):
            f.set("ok", True)
        ua.check_eq("ok", True)
    with screen_case(ua, "ooa.folder/open_folder", "show the case folder",
                     "Open folder shows the folder in File Explorer.") as (f, d):
        f.act("ooa.folder/open_folder", desc="Show the case folder", path=d)
