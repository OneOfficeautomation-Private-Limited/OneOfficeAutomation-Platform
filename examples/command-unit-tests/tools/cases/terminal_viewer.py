"""Show terminal: opens the terminal viewer window on the stand-in's VT host (Telnet, input terminal_port) and closes it.
Drives the screen."""
from helpers_desktop import cred


def add(ua):
    ua.f.var("terminal_port", "Number", "input", 2323, "Port of the stand-in's VT host (Telnet).")
    ua.var("ut_ok", "Boolean", False)
    with ua.case("ooa.terminal/show_terminal", "the viewer opens and closes",
                 "Show terminal opens a window showing the live terminal screen (for watching or for typing by hand); Hide "
                 "terminal closes it and the session goes on.") as f:
        f.act("ooa.terminal/connect", desc="Sign in to the test VT host", host="127.0.0.1", port="$terminal_port$", terminal_type="vt-telnet",
              user="agent", password=cred("terminal"), wait_for_text="Name:", connect_timeout_seconds=15)
        f.act("ooa.terminal/show_terminal", desc="Show the viewer", out={"shown": "ut_ok"})
        ua.check_eq("ut_ok", True)
        f.act("core/delay", desc="Leave it on screen for a moment", seconds=2)
        f.act("ooa.terminal/hide_terminal", desc="Close the viewer")
        f.act("ooa.terminal/disconnect", desc="Disconnect")
