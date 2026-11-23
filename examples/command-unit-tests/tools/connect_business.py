"""Connect with a saved connection in the business unit tests (Slack, GitHub and the other toolkits whose Connect step
names a saved connection).

Two things make the pattern work in a local run:
  * the saved connection lives in connections.json of the run folder, which the agent writes itself (saved_connection),
    with the stand-in's address and a vault reference for the token;
  * the steps after Connect leave their sign-in inputs empty (""): the flow check (FlowValidator) asks for every
    required input unless the step names its own connection, while the runner fills empty inputs from the connection
    Connect chose (leave_empty).
In One CommandCenter the saved connection comes from its Connections list instead of the file: create one with the same
name there before running the agent.
"""
from __future__ import annotations

import json


def saved_connection(f, name: str, package: str, values: dict, credentials: dict):
    """A step that writes connections.json in the run folder with one saved connection."""
    text = json.dumps({name: {"package": package, "values": values, "credentials": credentials}})
    f.act("ooa.file/write_text", desc=f"Save the connection {name} (stand-in address, token from the vault)",
          path="connections.json", text=text)


def leave_empty(steps, toolkit, inputs, skip=("connect", "disconnect")):
    """Gives every step, loop and condition of the toolkit an empty value for the inputs it does not set."""
    for s in steps:
        if not isinstance(s, dict):
            continue
        a = s.get("action", "")
        args = s.setdefault("args", {})
        if a.startswith(toolkit + "/") and a.split("/")[1] not in skip:
            for k in inputs:
                args.setdefault(k, "")
        if a == "core/loop" and str(args.get("iterator", "")).startswith(toolkit + "/"):
            ia = args.setdefault("iterator_args", {})
            for k in inputs:
                ia.setdefault(k, "")
        cond = args.get("condition")
        if isinstance(cond, dict) and str(cond.get("condition", "")).startswith(toolkit + "/"):
            ca = cond.setdefault("args", {})
            for k in inputs:
                ca.setdefault(k, "")
        for k in ("children", "else", "finally", "default"):
            if isinstance(s.get(k), list):
                leave_empty(s[k], toolkit, inputs, skip)
        for k in ("elseIf", "catch", "cases"):
            for b in s.get(k) or []:
                if isinstance(b, dict) and isinstance(b.get("children"), list):
                    leave_empty(b["children"], toolkit, inputs, skip)
