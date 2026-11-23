"""Shared helpers for the office/file/system unit-test agents (helper: office).

fix_samples(ua)  makes the cases converted from command samples runnable as one agent:
    * the samples write their files under {tmp} (a fresh folder per sample in the platform's own test); here each case
      gets its own folder case<N> in the run folder, emptied when the case starts, and every {tmp}, prepared file and
      expected file of the case points there;
    * ut.add_sample checks a file that should be gone with ooa.file/get_info out "exists", an output get_info does
      not have; that check becomes an If on the condition ooa.file/exists (or ooa.folder/exists for a folder);
    * an expected value {"$contains": text} becomes a "contains" check instead of "eq".
fcase(ua, ...)   a hand-written case with its own empty folder case<N> (yields the flow and the folder).
screen_case(...) a case whose command would take over the screen or print: built and validated, but its steps run
                 only when the input ut_screen is on.
"""
from __future__ import annotations

import json
import re
from contextlib import contextmanager

PNG_RED_3x2 = "iVBORw0KGgoAAAANSUhEUgAAAAMAAAACCAIAAAASFvFNAAAAEklEQVR4nGP4z8AAQVDqPwMDAEHSBfsl0XwmAAAAAElFTkSuQmCC"


def _walk(steps, fn):
    for s in steps:
        if not isinstance(s, dict):
            continue
        fn(s)
        for k in ("children", "else", "finally", "default"):
            if isinstance(s.get(k), list):
                _walk(s[k], fn)
        for k in ("elseIf", "catch", "cases"):
            for b in s.get(k) or []:
                if isinstance(b, dict) and isinstance(b.get("children"), list):
                    _walk(b["children"], fn)


def _replace_tmp(obj, d):
    if isinstance(obj, str):
        return obj.replace("{tmp}", d)
    if isinstance(obj, list):
        return [_replace_tmp(x, d) for x in obj]
    if isinstance(obj, dict):
        return {k: _replace_tmp(v, d) for k, v in obj.items()}
    return obj


def _prep_steps(ua, d):
    return [
        {"id": ua.f._id(), "action": "ooa.folder/delete", "args": {"path": d, "recursive": True},
         "continueOnError": True, "description": "Start from an empty case folder (left from an earlier run)"},
        {"id": ua.f._id(), "action": "ooa.folder/create", "args": {"path": d}, "description": "Create the case folder"},
    ]


def _closers(ua, close):
    return [{"id": ua.f._id(), "action": a, "args": dict(args), "continueOnError": True,
             "description": "Close the session if an earlier case left it open"} for a, args in (close or [])]


def fix_samples(ua, close=None):
    """close: optional [(action, args)] run (errors ignored) at the start of every case, e.g. closing a session."""
    for top in ua.f.steps:
        if top.get("action") != "core/step":
            continue
        m = re.match(r"(\d+)\. ", top.get("args", {}).get("title", ""))
        tries = [s for s in top.get("children", []) if s.get("action") == "core/try"]
        if not m or not tries:
            continue
        tr = tries[0]
        d = "case" + m.group(1)
        text = json.dumps(tr)
        uses_files = "{tmp}" in text or '"Prepare the test file ' in text or '"Read ' in text or '"Look for ' in text
        if not uses_files:
            _walk(tr["children"], _fix_contains)
            tr["children"][0:0] = _closers(ua, close)
            continue
        tr["children"] = _replace_tmp(tr["children"], d)

        def fix(s):
            desc = s.get("description", "")
            a = s.get("args", {})
            if s.get("action") == "ooa.file/write_text" and desc.startswith("Prepare the test file "):
                a["path"] = d + "/" + a["path"]
            elif s.get("action") == "ooa.file/read_text" and desc == "Read " + str(a.get("path")):
                a["path"] = d + "/" + a["path"]
            elif s.get("action") == "ooa.file/get_info" and "exists" in (s.get("out") or {}):
                var = s["out"]["exists"]
                path = d + "/" + a["path"]
                is_folder = "." not in path.rsplit("/", 1)[-1]
                s.clear()
                s.update({"id": ua.f._id(), "action": "core/if", "description": "Look for " + path,
                          "args": {"condition": {"condition": "ooa.folder/exists" if is_folder else "ooa.file/exists",
                                                 "args": {"path": path}}},
                          "children": [{"id": ua.f._id(), "action": "core/set", "args": {"variable": var, "value": True}}],
                          "else": [{"id": ua.f._id(), "action": "core/set", "args": {"variable": var, "value": False}}]})
            _fix_contains(s)

        _walk(tr["children"], fix)
        # expectFiles {path: true}: ut.add_sample reads the file as text (which fails on .xlsx, .pdf, ...) and checks
        # nothing; the file only has to exist, so that read becomes an existence check.
        kids = tr["children"]
        for i, s in enumerate(kids):
            if s.get("action") == "ooa.file/read_text" and s.get("description", "").startswith("Read "):
                nxt = kids[i + 1] if i + 1 < len(kids) else {}
                checked = nxt.get("action") == "core/if" and (nxt.get("args", {}).get("condition") or {}).get("left") == "$ut_text$"
                if not checked:
                    path = s["args"]["path"]
                    kids[i] = {"id": ua.f._id(), "action": "core/if", "description": "Check " + path + " was made",
                               "args": {"condition": {"condition": "ooa.file/exists", "args": {"path": path}}},
                               "children": [{"id": ua.f._id(), "action": "core/comment", "args": {"text": "As expected"}}],
                               "else": [{"id": ua.f._id(), "action": "core/throw",
                                         "args": {"code": "UT.CHECK_FAILED", "message": path + " was not made"}}]}
        tr["children"][0:0] = _closers(ua, close) + _prep_steps(ua, d)
    _declare_loop_vars(ua)
    _walk(ua.f.steps, lambda s: _runtime_invalid(ua, s))
    _sample_credentials(ua)


def _sample_credentials(ua):
    """Samples name made-up passwords in "credentials" {variable: vault path} (values in "secrets"); ut.add_sample
    leaves them out. Here an argument "$var" naming such a variable becomes a vault reference
    unit_tests/samples_<path with _>/<last part> (the made-up value is in secrets.env as OOA_SECRET_UNIT_TESTS_SAMPLES_...)."""
    import gen  # the samples loader (gen.py is the script that imports this module)
    creds = {}
    for s in gen.load_samples():
        creds.update(s.get("credentials") or {})

    def fix(s):
        for k, v in list((s.get("args") or {}).items()):
            if isinstance(v, str) and v.startswith("$") and v.strip("$") in creds and not ua.f.has_var(v.strip("$")):
                s["args"][k] = {"$credential": "unit_tests/samples_" + creds[v.strip("$")].rsplit("/", 1)[0].replace("/", "_") + "/" + creds[v.strip("$")].rsplit("/", 1)[1]}
    _walk(ua.f.steps, fix)


def _runtime_invalid(ua, s):
    """A sample expecting FLOW.INVALID (a value the editor refuses) would make the whole agent invalid. Here the
    refused values reach the command through variables, so only this case meets them, when it runs; the command's
    own check must then refuse them (any error code: the editor's check cannot run on a variable)."""
    if s.get("action") != "core/try":
        return
    catches = s.get("catch") or []
    if not any(c.get("codes") == ["FLOW.INVALID"] for c in catches):
        return
    last = s["children"][-1]
    sets = []
    for k, v in list(last.get("args", {}).items()):
        if isinstance(v, (int, float, str)) and not isinstance(v, bool) and not (isinstance(v, str) and "$" in v):
            name = f"ut_arg_{last['id']}_{k}"
            ua.var(name, "Any")
            sets.append({"id": ua.f._id(), "action": "core/set", "args": {"variable": name, "value": v},
                         "description": "The value the editor would refuse, given through a variable"})
            last["args"][k] = f"${name}$"
    s["children"][-1:-1] = sets
    for c in catches:
        if c.get("codes") == ["FLOW.INVALID"]:
            c["codes"] = ["*"]


def _declare_loop_vars(ua):
    """Loop variables of the samples ("as") are not declared by ut.add_sample; the runner wants them declared."""
    names = []
    _walk(ua.f.steps, lambda s: names.append(s["args"]["as"]) if s.get("action") == "core/loop" and isinstance(s.get("args", {}).get("as"), str) else None)
    for n in names:
        ua.var(n, "Any")


def _fix_contains(s):
    if s.get("action") != "core/if":
        return
    c = s.get("args", {}).get("condition")
    if isinstance(c, dict) and c.get("op") == "eq" and isinstance(c.get("right"), dict) and "$contains" in c["right"]:
        c["op"] = "contains"
        c["right"] = c["right"]["$contains"]
    elif isinstance(c, dict) and c.get("op") == "eq" and isinstance(c.get("right"), dict) and "$type" in c["right"]:
        c["op"] = "notEmpty"        # {"$type": "object"}: a value of that kind came back
        del c["right"]


@contextmanager
def fcase(ua, command, title, why, close=None):
    """A case with its own empty folder: yields (flow, folder) where folder is a path relative to the run folder."""
    with ua.case(command, title, why) as f:
        f._stack[-1].extend(_closers(ua, close))
        d = f"case{ua.n}"
        f.act("ooa.folder/delete", desc="Start from an empty case folder (left from an earlier run)", path=d,
              recursive=True, cont=True)
        f.act("ooa.folder/create", desc="Create the case folder", path=d)
        yield f, d


def screen_input(ua):
    ua.f.var("ut_screen", "Boolean", "input", False,
             "Run the cases that take over the screen, open windows or print (off: they are built but not run).")


@contextmanager
def screen_case(ua, command, title, why):
    """A case that runs its steps only when ut_screen is on; otherwise it records that it was not run."""
    screen_input(ua)
    with fcase(ua, command, title, why + " (Runs only when the input ut_screen is on, because it takes over the screen.)") as (f, d):
        with f.if_("$ut_screen$", desc="Only when screen cases are switched on") as s:
            yield f, d
        with f.else_(s):
            f.log("Not run: " + command + " needs the screen (switch on ut_screen to run it)")


@contextmanager
def cc_case(ua, command, title, why):
    """A case that needs One CommandCenter: its steps run only when the input ut_commandcenter is on."""
    ua.f.var("ut_commandcenter", "Boolean", "input", False,
             "On when the agent runs from One CommandCenter: runs the cases that need it (files kept there, ...).")
    with fcase(ua, command, title, why + " (Runs only when the input ut_commandcenter is on.)") as (f, d):
        with f.if_("$ut_commandcenter$", desc="Only when run from One CommandCenter") as s:
            yield f, d
        with f.else_(s):
            f.log("Not run: " + command + " needs One CommandCenter (switch on ut_commandcenter)")


# ---------------------------------------------------------------------------------------------------------------------
# Small test documents written by the agents themselves (ooa.file/write_text): plain-ASCII PDFs
# ---------------------------------------------------------------------------------------------------------------------

def text_pdf(pages, size=(300, 200), font_size=12):
    """A PDF with Helvetica text: pages = [[(x, y_from_bottom, text), ...], ...]."""
    w, h = size
    objs = {1: "<< /Type /Catalog /Pages 2 0 R >>", 3: "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"}
    kids = []
    for i, lines in enumerate(pages):
        po, co = 4 + 2 * i, 5 + 2 * i
        kids.append(f"{po} 0 R")
        stream = "\n".join(f"BT /F1 {font_size} Tf {x} {y} Td ({t}) Tj ET" for x, y, t in lines)
        objs[po] = (f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 {w} {h}] /Contents {co} 0 R "
                    f"/Resources << /Font << /F1 3 0 R >> >> >>")
        objs[co] = f"<< /Length {len(stream)} >>\nstream\n{stream}\nendstream"
    objs[2] = f"<< /Type /Pages /Kids [{' '.join(kids)}] /Count {len(pages)} >>"
    return _pdf(objs)


def form_pdf(fields):
    """A one-page PDF form with text fields: fields = {name: value}."""
    objs = {2: "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            5: "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"}
    refs = []
    for i, name in enumerate(fields):
        o = 10 + i
        refs.append(f"{o} 0 R")
        y = 160 - 40 * i
        objs[o] = (f"<< /Type /Annot /Subtype /Widget /FT /Tx /T ({name}) /V ({fields[name]}) /Rect [20 {y} 250 {y + 25}] "
                   f"/P 3 0 R /DA (/Helv 12 Tf 0 g) /F 4 >>")
    objs[1] = (f"<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [{' '.join(refs)}] /NeedAppearances true "
               f"/DA (/Helv 0 Tf 0 g) /DR << /Font << /Helv 5 0 R >> >> >> >>")
    objs[3] = (f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 200] /Annots [{' '.join(refs)}] "
               f"/Resources << /Font << /Helv 5 0 R >> >> >>")
    return _pdf(objs)


def _pdf(objs):
    out = "%PDF-1.4\n"
    offsets = {}
    for k in sorted(objs):
        offsets[k] = len(out)
        out += f"{k} 0 obj\n{objs[k]}\nendobj\n"
    xref = len(out)
    top = max(objs)
    out += f"xref\n0 {top + 1}\n0000000000 65535 f \n"
    for k in range(1, top + 1):
        out += f"{offsets[k]:010d} 00000 n \n" if k in offsets else "0000000000 65535 f \n"
    out += f"trailer\n<< /Size {top + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n"
    return out


