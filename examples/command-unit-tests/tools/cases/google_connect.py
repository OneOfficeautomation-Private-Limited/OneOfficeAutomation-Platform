"""Google Workspace Connect and Disconnect. Connect picks a saved connection, which only One CommandCenter (or
OneAgentStudio) keeps; an agent that names a connection is signed in through it, so these cases live in their own agent,
built and validated here and run by the lead in One CommandCenter with a saved connection called UT Google."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from helpers_msgoogle import start  # noqa: E402

M = "ooa.google/"


def add(ua):
    start(ua, None, "")
    ua.var("s", "String")
    with ua.case(M + "connect", "a saved connection", "Connect picks the saved connection UT Google for the later Google steps and gives its name.") as f:
        f.act(M + "connect", desc="Use the connection UT Google", out={"connection": "s"}, connection="UT Google")
        ua.check_eq("s", "UT Google")
        f.act(M + "disconnect", desc="Forget it again")
    with ua.case(M + "disconnect", "forget every connection", "Disconnect forgets the connections Connect chose; it answers without an error.") as f:
        f.act(M + "connect", desc="Use the connection UT Google", connection="UT Google")
        f.act(M + "disconnect", desc="Forget it")
        f.set("s", "forgotten", desc="Disconnect went through")
        ua.check_eq("s", "forgotten")
