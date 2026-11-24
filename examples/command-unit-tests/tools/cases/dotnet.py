"""Cases for the .NET assembly session commands, against assemblies of the .NET Framework that ship with Windows."""
from helpers_office import fix_samples, fcase

FW = "C:/Windows/Microsoft.NET/Framework64/v4.0.30319/"


def add(ua):
    fix_samples(ua)
    for n, t in (("asm", "String"), ("types", "List"), ("n", "Number"), ("r", "Any"), ("ok", "Boolean"), ("txt", "String")):
        ua.var(n, t)
    close = [("ooa.dotnet/close_assembly", {"session": "ut_net"})]

    with fcase(ua, "ooa.dotnet/read_assembly", "public types of System.Numerics",
               "Read assembly lists an assembly's public types with their methods; BigInteger is among them.") as (f, d):
        f.act("ooa.dotnet/read_assembly", desc="Read System.Numerics.dll", path=FW + "System.Numerics.dll",
              out={"types": "types"})
        f.expr("n", "len($types)", desc="Count the types")
        ua.check("gt", "$n$", 0, what="types")
        ua.check("contains", "${types}", "BigInteger", what="types")
    with fcase(ua, "ooa.dotnet/open_assembly", "open System.dll",
               "Open assembly loads an assembly into a session kept for the next steps; the condition Assembly "
               "session open is then true.", close=close) as (f, d):
        f.act("ooa.dotnet/open_assembly", desc="Open System.dll", path=FW + "System.dll", session="ut_net",
              out={"assembly": "asm"})
        ua.check("contains", "$asm$", "System", what="assembly")
        f.act("ooa.dotnet/close_assembly", desc="Close the session", session="ut_net")
    with fcase(ua, "ooa.dotnet/create_object", "a collection that keeps its items",
               "Create object makes an object of the open assembly and keeps it in the session by a name; Call method "
               "then calls its methods, and the object keeps its state between the calls.", close=close) as (f, d):
        f.act("ooa.dotnet/open_assembly", desc="Open System.dll", path=FW + "System.dll", session="ut_net")
        f.act("ooa.dotnet/create_object", desc="Create a StringCollection", session="ut_net",
              type="System.Collections.Specialized.StringCollection", object_name="names")
        f.act("ooa.dotnet/call_method", desc="Add Asha", session="ut_net", object_name="names", method="Add",
              arguments=["Asha"])
        f.act("ooa.dotnet/call_method", desc="Add Ben", session="ut_net", object_name="names", method="Add",
              arguments=["Ben"], out={"result": "r"})
        ua.check_eq("r", 1)
        f.act("ooa.dotnet/call_method", desc="Does it hold Ben?", session="ut_net", object_name="names",
              method="Contains", arguments=["Ben"], out={"result": "r"})
        ua.check_eq("r", True)
        f.act("ooa.dotnet/close_assembly", desc="Close the session", session="ut_net")
    with fcase(ua, "ooa.dotnet/call_method", "a static method",
               "Call method also calls a static method of a type, here Uri.EscapeDataString.", close=close) as (f, d):
        f.act("ooa.dotnet/open_assembly", desc="Open System.dll", path=FW + "System.dll", session="ut_net")
        f.act("ooa.dotnet/call_method", desc="Escape a text", session="ut_net", type="System.Uri",
              method="EscapeDataString", arguments=["a b&c"], out={"result": "r"})
        ua.check_eq("r", "a%20b%26c")
        f.act("ooa.dotnet/close_assembly", desc="Close the session", session="ut_net")
    with fcase(ua, "ooa.dotnet/close_assembly", "session ends",
               "Close assembly ends a .NET session; the condition Assembly session open is false afterwards.", close=close) as (f, d):
        f.act("ooa.dotnet/open_assembly", desc="Open System.dll", path=FW + "System.dll", session="ut_net")
        f.act("ooa.dotnet/close_assembly", desc="Close the session", session="ut_net")
        f.set("ok", False)
        with f.if_({"condition": "ooa.dotnet/assembly_session_open", "args": {"session": "ut_net"}}, desc="Still open?"):
            f.set("ok", True)
        ua.check_eq("ok", False)
    with fcase(ua, "ooa.dotnet/assembly_session_open", "open session",
               "Assembly session open is a condition: true while the session is open.", close=close) as (f, d):
        f.act("ooa.dotnet/open_assembly", desc="Open System.dll", path=FW + "System.dll", session="ut_net")
        f.set("ok", False)
        with f.if_({"condition": "ooa.dotnet/assembly_session_open", "args": {"session": "ut_net"}}, desc="Is it open?"):
            f.set("ok", True)
        f.act("ooa.dotnet/close_assembly", desc="Close the session", session="ut_net")
        ua.check_eq("ok", True)
