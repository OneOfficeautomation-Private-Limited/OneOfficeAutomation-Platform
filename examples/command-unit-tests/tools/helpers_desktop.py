"""Shared code for the desktop, browser, mail and transfer unit-test agents (groups_desktop.py).

The platform's command samples are written for its own test harness: {tmp} is a fresh folder per case, {web} a page
server, {mock} a mock server with the sample's canned answers, {ftp_port}/{ssh_port} in-process servers, and
"credentials" a test vault. Here they become:
  {tmp}      a folder of the case's own in the run folder (ut_caseNN), emptied when the case starts
  {web}      the stand-in serving the sample's own files ($standin_url$/dsk/sample/<file>/<n>)
  {mock}     the stand-in replaying that sample's canned answers ($standin_url$/dsk/mock/<file>/<n>)
  "$pw"      a vault value {"$credential": "unit_tests/<service>/password"} (made-up values in secrets.env)
and expected values written as {"$contains": ...} or {"$type": ...} become matching checks.

fix_samples(ua, ...) rewrites, in place, the cases gen.py made from samples with such placeholders;
add_converted(ua, sample, ...) adds a sample the generator skips (requires desktop, browser, ftp, ssh).
"""
from __future__ import annotations

import glob
import json
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
SAMPLES = os.path.join(HERE, "..", "..", "..", "platform", "cli", "src", "test", "resources", "command-samples")
_CACHE: list | None = None


def cred(service: str, attribute: str = "password") -> dict:
    return {"$credential": f"unit_tests/{service}/{attribute}"}


def samples() -> list[dict]:
    """Every command sample, with _file and _idx (its place in its file)."""
    global _CACHE
    if _CACHE is None:
        _CACHE = []
        for path in sorted(glob.glob(os.path.join(SAMPLES, "*.json"))):
            for i, c in enumerate(json.loads(open(path, encoding="utf-8").read(), strict=False)):
                c["_file"], c["_idx"] = os.path.basename(path), i
                _CACHE.append(c)
    return _CACHE


def find(command_prefix: str, requires=None, name: str | None = None) -> list[dict]:
    """Samples whose command starts with the prefix (and, when given, with this requires and name)."""
    out = []
    for s in samples():
        if not s["command"].startswith(command_prefix):
            continue
        if requires is not None and (s.get("requires") or "") != requires:
            continue
        if name is not None and s.get("name") != name:
            continue
        out.append(s)
    return out


def _needs_fixing(s: dict) -> bool:
    text = json.dumps(s.get("steps")) + json.dumps(s.get("files") or {})
    return bool(re.search(r"\{(tmp|web|mock|ftp_port|ssh_port|imap_port|smtp_port|ldap)\}", text) or s.get("credentials")
                or s.get("files") or s.get("expectFiles")
                or any(isinstance(v, dict) for v in (s.get("expect") or {}).values()))


def _prepare(s: dict, folder: str, service: str, extra: dict | None):
    text = json.dumps({k: v for k, v in s.items() if not k.startswith("_")}, ensure_ascii=False)
    text = text.replace("{mock}", f"$standin_url$/dsk/mock/{s['_file']}/{s['_idx']}")
    text = text.replace("{web}", f"$standin_url$/dsk/sample/{s['_file']}/{s['_idx']}").replace("{tmp}", folder)
    for k, v in (extra or {}).items():
        text = text.replace(k, v)
    c = json.loads(text)
    c.pop("requires", None)
    c["files"] = {folder + "/" + k: v for k, v in (c.get("files") or {}).items()}
    c["expectFiles"] = {folder + "/" + k: v for k, v in (c.get("expectFiles") or {}).items()}
    special = {}
    for k, v in list(c["expectFiles"].items()):
        if isinstance(v, bool):                       # present or absent: checked with the File exists condition
            special["file:" + k] = {"$exists": c["expectFiles"].pop(k)}
    for k, v in list((c.get("expect") or {}).items()):
        if isinstance(v, dict):
            special[k] = c["expect"].pop(k)
    names = list((c.pop("credentials", None) or {}).keys())

    def walk(o):
        if isinstance(o, dict):
            return {k: walk(v) for k, v in o.items()}
        if isinstance(o, list):
            return [walk(x) for x in o]
        if isinstance(o, str) and o.startswith("$") and o[1:] in names:
            return cred(service)
        return o
    c["steps"] = walk(c["steps"])
    return c, special


def _special_checks(ua, special: dict) -> list:
    """Check steps for {"$contains": ...}, {"$type": ...} and table expectations, built aside and returned."""
    f = ua.f
    tmp: list = []
    f._stack.append(tmp)
    try:
        for var, want in special.items():
            if "$exists" in want:
                path = var[5:]
                ua.var("ut_exists", "Boolean", False)
                f.set("ut_exists", False)
                with f.if_({"condition": "ooa.file/exists", "args": {"path": path}}, desc="Is " + path + " there?"):
                    f.set("ut_exists", True)
                ua.check("eq", "$ut_exists$", want["$exists"], what=path + (" exists" if want["$exists"] else " absent"))
                continue
            ua.var(var)
            if "$contains" in want:
                ua.check("contains", f"${var}$", want["$contains"], what=var)
            elif "$type" in want:
                ua.var("ut_json", "String", "")
                f.expr("ut_json", f"jsonStringify(${var})", desc="The value as JSON, to see its type")
                ua.check("startsWith", "$ut_json$", {"array": "[", "string": '"', "object": "{"}.get(want["$type"], ""),
                         what=var + " is " + want["$type"])
            else:
                ua.check("notEmpty", f"${var}$", what=var)
    finally:
        f._stack.pop()
    return tmp


def _add(ua, s: dict, service: str, extra: dict | None, number: int):
    """Adds the sample as case <number> at the end of the agent; returns the new top-level Step group."""
    from ut import add_sample
    folder = f"ut_case{number:02d}"
    c, special = _prepare(s, folder, service, extra)
    why = add_sample(ua, c)
    if why:
        raise ValueError(f"{s['command']} ({s.get('name')}): {why}")
    group = ua.f.steps[-1]
    try_step = next(x for x in group["children"] if x["action"] == "core/try")
    if special and not c.get("error"):
        body = try_step["children"]
        body[-2:-2] = _special_checks(ua, special)
    clean = {"id": ua.f._id(), "action": "ooa.folder/delete", "args": {"path": folder, "recursive": True},
             "continueOnError": True, "description": "Start with an empty case folder " + folder}
    try_step["children"].insert(0, clean)
    return group


def add_converted(ua, s: dict, service: str = "test", extra: dict | None = None):
    """Adds a sample that gen.py does not convert by itself (it needs a browser, the desktop or a server)."""
    _add(ua, s, service, extra, ua.n + 1)


def fix_samples(ua, toolkits: list[str], service: str = "test", extra: dict | None = None):
    """Rebuilds, in place, the cases gen.py made from samples that use placeholders or special expectations."""
    used: set = set()
    for pos, step in enumerate(list(ua.f.steps)):
        title = (step.get("args") or {}).get("title", "")
        m = re.match(r"^(\d+)\. (\S+) - (.*)$", title)
        if not m:
            continue
        num, cmd, name = int(m.group(1)), m.group(2), m.group(3)
        match = None
        for s in samples():
            key = (s["_file"], s["_idx"])
            if key in used or s["command"] != cmd or (s.get("name") or "case") != name or (s.get("requires") or "") not in ("", "None"):
                continue
            if s.get("requires") in ("browser", "desktop", "mail", "ldap", "ssh", "ftp", "internet", "node"):
                continue
            match = s
            used.add(key)
            break
        if not match or not _needs_fixing(match):
            continue
        group = _add(ua, match, service, extra, num)
        ua.f.steps.pop()
        ua.n -= 1
        new_title = f"{num}. {cmd} - {name}"
        group["args"]["title"] = new_title
        group["description"] = new_title
        ua.f.steps[pos] = group


# ---------------------------------------------------------------------------------------------- desktop helpers

def winform_script(title: str, body: str = "", run: bool = True) -> str:
    """PowerShell that shows a small Windows Forms window (title, then the controls added by body)."""
    return ("Add-Type -AssemblyName System.Windows.Forms; $f = New-Object Windows.Forms.Form; $f.Text = '" + title + "'; "
            "$f.Width = 460; $f.Height = 320; $f.StartPosition = 'CenterScreen'; " + body
            + ("[System.Windows.Forms.Application]::Run($f)" if run else ""))


def open_test_window(f, title: str, body: str = "", wait: int = 30):
    """Starts a test window and waits for it (steps added to the current case)."""
    f.act("ooa.application/start_program", desc="Open the test window '" + title + "'", program="powershell.exe",
          arguments=["-NoProfile", "-WindowStyle", "Hidden", "-Command", winform_script(title, body)])
    f.act("ooa.window/wait_for", desc="Wait for the test window", title=title, timeout_seconds=wait)


def close_test_window(f, title: str):
    f.act("ooa.window/close", desc="Close the test window", title=title, cont=True)


def prepend(ua, title_part: str, action: str, desc: str, **args):
    """Puts a step (continue on error) first in the case whose title contains title_part, e.g. to close a session an
    earlier case left open (the platform's sample harness gives every sample a fresh runner)."""
    for step in ua.f.steps:
        if title_part in (step.get("args") or {}).get("title", ""):
            t = next(x for x in step["children"] if x["action"] == "core/try")
            t["children"].insert(0, {"id": ua.f._id(), "action": action, "args": args, "continueOnError": True, "description": desc})
            return
    raise KeyError(title_part)


def remove_case(ua, title_part: str):
    """Takes out a case that cannot live in an agent (e.g. a sample whose error comes when the flow is checked, which
    would make the whole agent invalid), and numbers the later cases again."""
    steps = ua.f.steps
    pos = next(i for i, s in enumerate(steps) if title_part in (s.get("args") or {}).get("title", ""))
    del steps[pos]
    for s in steps[pos:]:
        t = (s.get("args") or {}).get("title", "")
        m = re.match(r"^(\d+)\. (.*)$", t)
        if m and s.get("action") == "core/step":
            new = f"{int(m.group(1)) - 1}. {m.group(2)}"
            s["args"]["title"] = new
            s["description"] = new
    ua.n -= 1


def drop_steps(sample: dict, *actions: str) -> dict:
    """A copy of the sample without the steps of these actions."""
    c = json.loads(json.dumps(sample))
    c["steps"] = [s for s in c["steps"] if s.get("action") not in actions]
    return c


def desktop_object(window_title: str, **props) -> dict:
    """A captured desktop object: the window and the properties to match (all equals, all used)."""
    return {"technology": "desktop", "window": {"title": window_title, "title_match": "equals"},
            "properties": [{"name": k, "value": v, "match": "equals", "use": True} for k, v in props.items()], "index": 0}
