"""FTP / SFTP cases on the stand-in's FTP server (plugins/desktop_services.py: 127.0.0.1, input ftp_port, user agent).

The platform's FTP samples are used as they are (their own folders /c1 ... /c12 on the server), plus cases for the
commands they leave out. There is no SFTP server here: one case shows the SFTP sign-in and checks its clear error.
"""
from helpers_desktop import add_converted, cred, find, fix_samples, remove_case

FTP_PORT = {'"{ftp_port}"': '"$ftp_port$"'}


def connect(f):
    f.act("ooa.file_transfer/connect", desc="Sign in to the test FTP server", protocol="ftp", host="127.0.0.1", port="$ftp_port$",
          user="agent", password=cred("ftp"), passive_mode=True)


def add(ua):
    ua.f.var("ftp_port", "Number", "input", 2121, "Port of the stand-in's FTP server.")
    fix_samples(ua, ["ooa.file_transfer"], service="ftp", extra=FTP_PORT)
    # this sample's error comes when the flow is checked, so in an agent it would stop the whole agent: left out
    remove_case(ua, "sync_folder - unknown compare is caught when the flow is checked")
    for s in find("ooa.file_transfer/", requires="ftp"):
        add_converted(ua, s, service="ftp", extra=FTP_PORT)
    ua.var("ut_list", "List")
    ua.var("ut_n", "Number", 0)
    ua.var("ut_text", "String", "")
    ua.var("ut_table", "Table")

    with ua.case("ooa.file_transfer/upload_files", "the CSV files of a folder",
                 "Upload files sends the local files that fit a pattern to a server folder and gives the ones it uploaded.") as f:
        f.act("ooa.folder/delete", desc="Start with an empty local folder", path="ft_up", recursive=True, cont=True)
        f.act("ooa.file/write_text", desc="First CSV", path="ft_up/a.csv", text="1")
        f.act("ooa.file/write_text", desc="Second CSV", path="ft_up/b.csv", text="2")
        f.act("ooa.file/write_text", desc="A text file left out", path="ft_up/c.txt", text="3")
        connect(f)
        f.act("ooa.file_transfer/create_folder", desc="Server folder", folder="/u1")
        f.act("ooa.file_transfer/delete_files", desc="Empty it", folder="/u1", pattern="*")
        f.act("ooa.file_transfer/upload_files", desc="Upload the CSV files", local_folder="ft_up", pattern="*.csv", remote_folder="/u1",
              overwrite=True, out={"uploaded": "ut_list"})
        f.expr("ut_n", "len($ut_list)", desc="Count the uploads")
        ua.check_eq("ut_n", 2)
        f.act("ooa.file_transfer/list", desc="List the server folder", folder="/u1", kind="files", out={"table": "ut_table"})
        f.act("ooa.datatable/row_count", desc="Count the rows", table="$ut_table$", out={"count": "ut_n"})
        ua.check_eq("ut_n", 2)
        f.act("ooa.file_transfer/disconnect", desc="Sign out")

    with ua.case("ooa.file_transfer/download_files", "the files that fit a pattern",
                 "Download files fetches the server files that fit a pattern into a local folder and gives their local paths.") as f:
        f.act("ooa.folder/delete", desc="Start with empty local folders", path="ft_dl", recursive=True, cont=True)
        f.act("ooa.file/write_text", desc="A report to upload", path="ft_dl/src/report-1.txt", text="R1")
        f.act("ooa.file/write_text", desc="Another report", path="ft_dl/src/report-2.txt", text="R2")
        f.act("ooa.file/write_text", desc="A file left out", path="ft_dl/src/other.txt", text="X")
        connect(f)
        f.act("ooa.file_transfer/create_folder", desc="Server folder", folder="/d1")
        f.act("ooa.file_transfer/upload_folder", desc="Upload the files", local_folder="ft_dl/src", remote_folder="/d1")
        f.act("ooa.file_transfer/change_folder", desc="Work in /d1", folder="/d1")
        f.act("ooa.file_transfer/download_files", desc="Download the reports", folder=".", pattern="report-*.txt", local_folder="ft_dl/in",
              overwrite=True, out={"files": "ut_list"})
        f.expr("ut_n", "len($ut_list)", desc="Count the downloads")
        ua.check_eq("ut_n", 2)
        f.act("ooa.file/read_text", desc="Read one back", path="ft_dl/in/report-2.txt", out={"text": "ut_text"})
        ua.check_eq("ut_text", "R2")
        f.act("ooa.file_transfer/change_folder", desc="Back to the parent folder", to_parent=True, out={"folder": "ut_text"})
        ua.check_eq("ut_text", "/")
        f.act("ooa.file_transfer/disconnect", desc="Sign out")

    with ua.case("ooa.file_transfer/list", "files only, by pattern",
                 "List gives a table of a server folder (name, folder or not, size, modified); kind and pattern narrow it.") as f:
        f.act("ooa.folder/delete", desc="Start with an empty local folder", path="ft_ls", recursive=True, cont=True)
        f.act("ooa.file/write_text", desc="A CSV", path="ft_ls/x.csv", text="1")
        f.act("ooa.file/write_text", desc="A text file", path="ft_ls/y.txt", text="2")
        connect(f)
        f.act("ooa.file_transfer/create_folder", desc="Server folder with a subfolder", folder="/l1/sub")
        f.act("ooa.file_transfer/upload_folder", desc="Upload the files", local_folder="ft_ls", remote_folder="/l1", include_subfolders=False)
        f.act("ooa.file_transfer/list", desc="List the CSV files", folder="/l1", kind="files", pattern="*.csv", out={"table": "ut_table"})
        f.act("ooa.datatable/row_count", desc="Count the rows", table="$ut_table$", out={"count": "ut_n"})
        ua.check_eq("ut_n", 1)
        f.act("ooa.file_transfer/list", desc="List everything", folder="/l1", out={"table": "ut_table"})
        f.act("ooa.datatable/row_count", desc="Count the rows", table="$ut_table$", out={"count": "ut_n"})
        ua.check_eq("ut_n", 3)
        f.act("ooa.file_transfer/disconnect", desc="Sign out")

    with ua.case("ooa.file_transfer/disconnect", "the session is gone afterwards",
                 "Disconnect signs out and closes the session; a list step after it fails because no session is open.") as f:
        connect(f)
        f.act("ooa.file_transfer/disconnect", desc="Sign out")
        ua.expect_error("*", lambda ff: ff.act("ooa.file_transfer/list", desc="List after signing out", folder="/"))

    with ua.case("ooa.file_transfer/connect", "SFTP sign-in to a host that does not answer",
                 "Connect with protocol sftp signs in over SSH; no SSH server listens on port 1 here, so it fails with a clear "
                 "connection error instead of hanging.") as f:
        ua.expect_error("*", lambda ff: ff.act("ooa.file_transfer/connect", desc="SFTP sign-in", protocol="sftp", host="127.0.0.1",
                                               port=1, user="agent", password=cred("ftp"), session="sftp", timeout_seconds=5))
