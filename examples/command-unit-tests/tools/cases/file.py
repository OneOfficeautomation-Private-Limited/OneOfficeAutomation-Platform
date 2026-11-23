"""Hand-written cases for the File commands the samples do not cover (or cover only with an error)."""
from helpers_office import fix_samples, fcase, cc_case, screen_case


def add(ua):
    fix_samples(ua)
    for n, t in (("p", "File"), ("ok", "Boolean"), ("t", "String"), ("n", "Number"), ("info", "Dictionary"),
                 ("files", "List"), ("created", "Boolean")):
        ua.var(n, t)

    with fcase(ua, "ooa.file/create", "new file with text",
               "Create makes a file (here holding some text); the case reads it back and checks created is true.") as (f, d):
        f.act("ooa.file/create", desc="Create notes.txt with a line of text", new_file=d + "/notes.txt",
              text="first line", out={"path": "p", "created": "created"})
        f.act("ooa.file/read_text", desc="Read the new file", path="$p$", out={"text": "t"})
        ua.check_eq("created", True)
        ua.check_eq("t", "first line")
    with fcase(ua, "ooa.file/create", "keep an existing file",
               "With if_exists = keep, Create leaves an existing file as it is and reports created false.") as (f, d):
        f.act("ooa.file/write_text", desc="An existing file", path=d + "/a.txt", text="old")
        f.act("ooa.file/create", desc="Create a.txt, keeping the existing one", new_file=d + "/a.txt",
              if_exists="keep", text="new", out={"created": "created"})
        f.act("ooa.file/read_text", desc="Read a.txt", path=d + "/a.txt", out={"text": "t"})
        ua.check_eq("created", False)
        ua.check_eq("t", "old")
    with fcase(ua, "ooa.file/rename", "new name in the same folder",
               "Rename gives a file a new name in its folder; the case checks the new path and that the old name is gone.") as (f, d):
        f.act("ooa.file/write_text", desc="A file to rename", path=d + "/draft.txt", text="v1")
        f.act("ooa.file/rename", desc="Rename draft.txt to final.txt", path=d + "/draft.txt", new_name="final.txt",
              out={"path": "p"})
        ua.check("contains", "$p$", "final.txt", what="the new path")
        f.act("ooa.file/read_text", desc="Read the renamed file", path=d + "/final.txt", out={"text": "t"})
        ua.check_eq("t", "v1")
        f.set("ok", False)
        with f.if_({"condition": "ooa.file/exists", "args": {"path": d + "/draft.txt"}}, desc="Is the old name still there?"):
            f.set("ok", True)
        ua.check_eq("ok", False)
    with fcase(ua, "ooa.file/create_shortcut", "shortcut to a file",
               "Create shortcut makes a .lnk file that opens the target; the case checks the shortcut file exists.") as (f, d):
        f.act("ooa.file/write_text", desc="The file the shortcut opens", path=d + "/target.txt", text="x")
        f.act("ooa.file/create_shortcut", desc="Create a shortcut to target.txt", path=d + "/target.txt",
              shortcut=d + "/target.lnk", comment="Unit test shortcut", out={"path": "p"})
        f.set("ok", False)
        with f.if_({"condition": "ooa.file/exists", "args": {"path": d + "/target.lnk"}}, desc="Is the shortcut there?"):
            f.set("ok", True)
        ua.check_eq("ok", True)
    with fcase(ua, "ooa.file/files_in_folder", "with details",
               "Files in folder with details gives each file's name, extension and size; the case adds up the sizes.") as (f, d):
        f.act("ooa.file/write_text", desc="First file", path=d + "/a.csv", text="12")
        f.act("ooa.file/write_text", desc="Second file", path=d + "/b.csv", text="345")
        f.set("n", 0)
        f.set("t", "")
        with f.loop(desc="Each csv file with its details", iterator="ooa.file/files_in_folder",
                    iterator_args={"folder": d, "pattern": "*.csv", "details": True}, **{"as": "info"}):
            f.expr("n", "$n + $info.size_bytes", desc="Add the size")
            f.set("t", "${t}${info.name};", desc="Collect the name")
        ua.check_eq("n", 5)
        ua.check("contains", "$t$", "b.csv", what="the names")
    with fcase(ua, "ooa.file/print_files", "a listed file is missing",
               "Print files sends files to a printer. Never run against a real printer: every file is checked before "
               "anything is printed, so a list with a missing file stops with FILE.NOT_FOUND and nothing is printed.") as (f, d):
        f.act("ooa.file/write_text", desc="One file that exists", path=d + "/a.txt", text="x")
        f.set("files", [d + "/a.txt", d + "/missing.txt"])
        ua.expect_error("FILE.NOT_FOUND", lambda ff: ff.act("ooa.file/print_files", desc="Try to print the two files",
                                                            files="$files$", printer="UT printer that does not exist"))
    with cc_case(ua, "ooa.file/get_from_command_center", "a file kept in One CommandCenter",
                 "Get file from One CommandCenter copies a file kept on its Agents page (Files) to the runner; the case "
                 "copies Commands unit testing folder/ut-sample.txt and checks its size is above 0.") as (f, d):
        f.act("ooa.file/get_from_command_center", desc="Copy the kept file", repository_path="Commands unit testing folder/ut-sample.txt",
              save_to=d + "/ut-sample.txt", out={"path": "p", "size": "n"})
        ua.check("gt", "$n$", 0, what="size")
