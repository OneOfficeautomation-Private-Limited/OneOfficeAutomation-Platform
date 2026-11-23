"""Office, file and system command groups (helper: office). Merged into groups.GROUPS."""

_BOOK = ["open", "close", "create_", "save", "append_", "add_sheet", "copy_sheet", "delete_sheet", "delete_table",
         "rename_sheet", "switch_sheet", "hide_sheet", "unhide_sheet", "unhide_all", "get_sheet", "get_current",
         "sheet_count", "protect", "unprotect", "get_links", "change_link", "break_links", "get_sensitivity",
         "set_sensitivity", "insert_table", "get_table", "write_table"]


def _g(gid, name, toolkits, about, needs="local", **kw):
    d = {"id": "ut_" + gid, "name": name, "toolkits": toolkits, "needs": needs, "about": about}
    d.update(kw)
    return gid, d


GROUPS = dict([
    _g("file", "UT File", ["ooa.file"],
       "Unit tests of every File command: each case writes its own files in the run folder, uses the command and checks the result."),
    _g("folder", "UT Folder", ["ooa.folder"],
       "Unit tests of every Folder command: each case makes its own folders in the run folder and checks the result."),
    _g("excel_book", "UT Excel Workbooks", ["ooa.excel"],
       "Unit tests of the Excel workbook, sheet, table, chart, link and protection commands (no Excel needed): "
       "each case makes its own workbook in the run folder.", needs="local", only=_BOOK),
    _g("excel_cells", "UT Excel Cells", ["ooa.excel"],
       "Unit tests of the Excel cell, row, column and range commands (no Excel needed): each case makes its own "
       "workbook in the run folder and checks the values it reads back.", needs="local", skip=_BOOK),
    _g("excel_desktop", "UT Excel Desktop", ["ooa.excel_desktop"],
       "Unit tests of the commands that drive the installed Excel program (it runs hidden).", needs="office"),
    _g("word", "UT Word", ["ooa.word"],
       "Unit tests of every Word document command: each case makes its own document in the run folder."),
    _g("powerpoint", "UT PowerPoint", ["ooa.powerpoint"],
       "Unit tests of every PowerPoint file command: each case makes its own presentation in the run folder."),
    _g("powerpoint_desktop", "UT PowerPoint Desktop", ["ooa.powerpoint_desktop"],
       "Unit tests of the commands that drive the installed PowerPoint program.", needs="office"),
    _g("pdf", "UT PDF", ["ooa.pdf"],
       "Unit tests of every PDF command: each case makes its own PDF in the run folder."),
    _g("office_attach", "UT Office Attachments", ["ooa.office_attach"],
       "Unit tests of the commands that embed and extract files in Office documents."),
    _g("ocr", "UT OCR", ["ooa.ocr"],
       "Unit tests of the OCR commands on picture files made by the agent (never the screen)."),
    _g("idp", "UT Document Extraction", ["ooa.idp"],
       "Unit tests of the document extraction commands on documents made by the agent."),
    _g("image", "UT Image", ["ooa.image"],
       "Unit tests of the picture-file commands (not the screen): each case makes its own picture."),
    _g("system", "UT System", ["ooa.system"],
       "Unit tests of the System commands that read information or run harmless programs; nothing changes settings."),
    _g("services", "UT Windows Services", ["ooa.services"],
       "Unit tests of the Windows service commands: read-only checks on real services; commands that start, stop or "
       "change a service are built against a service name that does not exist."),
    _g("network", "UT Network", ["ooa.network"],
       "Unit tests of the Network commands against this computer and the local stand-in."),
    _g("dll", "UT DLL", ["ooa.dll"],
       "Unit tests of the DLL commands against Windows system libraries."),
    _g("dotnet", "UT DotNet", ["ooa.dotnet"],
       "Unit tests of the .NET commands against .NET Framework classes."),
])
