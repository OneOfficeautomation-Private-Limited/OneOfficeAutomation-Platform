"""Shared helpers for the platform groups (groups_platform.py): tidy the cases made from command samples so they fit a
unit-test agent (names starting ut_, no checks the validator refuses, declared variables), and small building blocks
used by several case files."""
from __future__ import annotations

import re

STEP_TITLE = re.compile(r"^(\d+)\. (\S+) - (.*)$", re.S)


def _case_steps(ua):
    """The numbered case groups at the top of the agent (not 0. About, not the summary)."""
    return [s for s in ua.f.steps if s.get("action") == "core/step" and STEP_TITLE.match(s["args"].get("title", ""))]


def drop(ua, *title_parts):
    """Removes the sample cases whose title contains any of the given parts (e.g. cases the validator refuses before
    the run, or cases that only say a command needs One CommandCenter)."""
    keep = []
    for s in ua.f.steps:
        t = s.get("args", {}).get("title", "") if s.get("action") == "core/step" else ""
        if STEP_TITLE.match(t) and any(p in t for p in title_parts):
            continue
        keep.append(s)
    ua.f.steps[:] = keep


def renumber(ua):
    """Numbers the cases 1, 2, 3 ... again after drop(), and recounts which commands are covered."""
    ua.covered = set()
    n = 0
    for s in _case_steps(ua):
        m = STEP_TITLE.match(s["args"]["title"])
        n += 1
        title = f"{n}. {m.group(2)} - {m.group(3)}"
        s["args"]["title"] = title
        s["description"] = title
        ua.covered.add(m.group(2))
    ua.n = n


def walk(steps, fn):
    """Calls fn(step) for every step, inside blocks too."""
    for s in steps:
        if not isinstance(s, dict):
            continue
        fn(s)
        for k in ("children", "else", "finally", "default"):
            if isinstance(s.get(k), list):
                walk(s[k], fn)
        for k in ("elseIf", "catch", "cases"):
            for b in s.get(k) or []:
                if isinstance(b, dict) and isinstance(b.get("children"), list):
                    walk(b["children"], fn)


def prefix_names(ua, action_prefix: str, keys=("queue", "to_queue"), prefix="ut_"):
    """Gives the names the samples use (queues, locks, categories ...) the ut_ prefix, so everything the agent makes is
    easy to find and tidy away."""
    def fn(s):
        act = s.get("action", "")
        if act.startswith(action_prefix):
            for k in keys:
                v = s.get("args", {}).get(k)
                if isinstance(v, str) and v.strip() and not v.startswith(prefix) and "$" not in v:
                    s["args"][k] = prefix + v
        if act == "core/loop" and str(s.get("args", {}).get("iterator", "")).startswith(action_prefix):
            ia = s["args"].get("iterator_args") or {}
            for k in keys:
                if isinstance(ia.get(k), str) and not ia[k].startswith(prefix):
                    ia[k] = prefix + ia[k]
        cond = s.get("args", {}).get("condition")
        if isinstance(cond, dict) and str(cond.get("condition", "")).startswith(action_prefix):
            for k in keys:
                v = (cond.get("args") or {}).get(k)
                if isinstance(v, str) and not v.startswith(prefix):
                    cond["args"][k] = prefix + v
    walk(ua.f.steps, fn)


def fix_sample_checks(ua):
    """Sample expectations {"$contains": x} and {"$type": ...} become checks the If step understands: the value's text
    contains x, or the value is not empty."""
    def fn(s):
        if s.get("action") != "core/if":
            return
        c = s["args"].get("condition")
        if not isinstance(c, dict) or c.get("op") != "eq" or not isinstance(c.get("right"), dict):
            return
        r, left = c["right"], c.get("left")
        name = left[1:-1] if isinstance(left, str) and left.startswith("$") and left.endswith("$") else None
        if "$contains" in r and name:
            want = r["$contains"]
            m = re.fullmatch(r"(\w+)=(.*)", str(want))
            if m:   # the samples write a dictionary field as key=value; its text here is JSON
                v = m.group(2)
                want = f'"{m.group(1)}":' + (v if re.fullmatch(r"-?\d+(\.\d+)?|true|false", v) else f'"{v}"')
            s["args"]["condition"] = {"op": "contains", "left": "${" + name + "}", "right": want}
            for e in s.get("else") or []:
                if e.get("action") == "core/throw":
                    e["args"]["message"] = f"{name}: expected it to contain {want}, got ${{{name}}}"
        elif "$type" in r:
            s["args"]["condition"] = {"op": "notEmpty", "left": left}
    walk(ua.f.steps, fn)


def move_last_case_to(ua, index=1):
    """Moves the case added last to the given place among the top-level steps (1 = right after 0. About)."""
    last = _case_steps(ua)[-1]
    ua.f.steps.remove(last)
    ua.f.steps.insert(index, last)


def names_used(ua, action_prefix, key):
    """The names (e.g. queues) the agent's steps of one toolkit use, in order."""
    out = []

    def fn(s):
        a = s.get("args", {})
        vals = []
        if s.get("action", "").startswith(action_prefix):
            vals.append(a.get(key))
            if key == "queue":
                vals.append(a.get("to_queue"))
        if s.get("action") == "core/loop" and str(a.get("iterator", "")).startswith(action_prefix):
            vals.append((a.get("iterator_args") or {}).get(key))
        c = a.get("condition")
        if isinstance(c, dict) and str(c.get("condition", "")).startswith(action_prefix):
            vals.append((c.get("args") or {}).get(key))
        for v in vals:
            if isinstance(v, str) and v and "$" not in v and v not in out:
                out.append(v)
    walk(ua.f.steps, fn)
    return out


def make_pdf(lines) -> str:
    """A one-page text PDF (plain ASCII, so an agent can write it with Write text file): each line in Helvetica."""
    text = "BT /F1 12 Tf 72 760 Td 16 TL " + " ".join(
        "(" + ln.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)") + ") Tj T*" for ln in lines) + " ET"
    objs = ["<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>",
            f"<< /Length {len(text)} >>\nstream\n{text}\nendstream",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"]
    out = "%PDF-1.4\n"
    offsets = []
    for i, o in enumerate(objs, 1):
        offsets.append(len(out))
        out += f"{i} 0 obj\n{o}\nendobj\n"
    xref = len(out)
    out += f"xref\n0 {len(objs) + 1}\n0000000000 65535 f \n" + "".join(f"{o:010d} 00000 n \n" for o in offsets)
    out += f"trailer\n<< /Size {len(objs) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n"
    return out


INVOICE_LINES = ["UT Supplies Ltd", "Invoice number: INV-UT-1", "Invoice date: 2026-11-20", "PO number: PO-4711",
                 "Currency: EUR", "Net: 100.00", "Tax: 20.50", "Total: 120.50"]


def declare(ua, name, type_="Any", default=None):
    ua.f.var(name, type_, "local", default)


def credential_var(ua, name, ref):
    """A Credential variable read from the vault (made-up value for the tests)."""
    ua.f.var(name, "Credential", "local", {"$credential": ref})


def error_case(ua, command, title, why, code, steps_fn):
    """A case whose command must fail with the given error code."""
    with ua.case(command, title, why):
        ua.expect_error(code, steps_fn)
