"""Cases for the DLL commands, against Windows system libraries (kernel32)."""
from helpers_office import fix_samples, fcase


def add(ua):
    fix_samples(ua, close=[("ooa.dll/close", {})])
    ua.var("r", "Any")
    ua.var("p", "String")
    with fcase(ua, "ooa.dll/close", "a closed session cannot be used",
               "Close unloads a library opened with Open DLL; a call through that session afterwards stops with an "
               "error.", close=[("ooa.dll/close", {})]) as (f, d):
        f.act("ooa.dll/open", desc="Load kernel32", library="kernel32", out={"path": "p"})
        ua.check("notEmpty", "$p$", what="path")
        f.act("ooa.dll/call", desc="Call through the session", session="dll", function="GetCurrentProcessId",
              out={"result": "r"})
        ua.check("gt", "$r$", 0, what="process id")
        f.act("ooa.dll/close", desc="Unload it")
        ua.expect_error("*", lambda ff: ff.act("ooa.dll/call", desc="Call through the closed session", session="dll",
                                               function="GetCurrentProcessId"))
