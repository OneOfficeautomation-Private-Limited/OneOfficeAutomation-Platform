"""Builder for the command unit-test agents ("Commands unit testing folder" in One CommandCenter).

One agent per command group (usually one toolkit). Every command gets its own numbered Step group:
  * a Comment saying what the command does and what the case checks (so the agent doubles as a usage example);
  * the command's steps inside a Try, then checks of the outputs (a failed check throws UT.CHECK_FAILED);
  * a PASS or FAIL line added to the agent's report, so one failing command never hides the others.
The agent ends with a summary: outputs ut_pass, ut_fail and ut_report, a log line, and an error UT.FAILED when any
case failed, so the run itself shows red in One CommandCenter.

Test data lives in the folder given by the input ut_dir (default C:/Users/virup/OOA Unit tests); outside services are
the local stand-in on http://127.0.0.1:8791 (input standin_url).
"""
from __future__ import annotations

import json
import re
from contextlib import contextmanager

from flowlib import Flow

DATA_DIR = "C:/Users/virup/OOA Unit tests"
STANDIN = "http://127.0.0.1:8791"
FOLDER = "Commands unit testing folder"


def _safe(text: str) -> str:
    """Text placed inside a ${...} interpolated string: no $ or braces from the case title."""
    return re.sub(r"[${}]", "", str(text))


class UnitAgent:
    def __init__(self, agent_id: str, name: str, description: str, commands_note: str = ""):
        self.f = Flow(agent_id, name, description)
        self.f.tags = ["unit-test", "commands"]
        self.n = 0
        self.covered: set[str] = set()
        f = self.f
        f.var("ut_dir", "String", "input", DATA_DIR, "Folder with the test data (files, workbooks, fixtures).")
        f.var("standin_url", "String", "input", STANDIN, "Address of the local stand-in that imitates outside services.")
        f.var("ut_pass", "Number", "output", 0, "Commands whose checks passed.")
        f.var("ut_fail", "Number", "output", 0, "Commands whose checks failed.")
        f.var("ut_report", "String", "output", "", "One line per case: PASS or FAIL, the command and the case.")
        f.var("ut_err", "String", "local", "")
        with f.step("0. About this agent", why=description + (" " + commands_note if commands_note else "")):
            f.log("Unit test agent " + name + " started")

    # ---------- variables used by cases ----------
    def var(self, name, type_="Any", default=None):
        self.f.var(name, type_, "local", default)

    # ---------- one case ----------
    @contextmanager
    def case(self, command: str, title: str, why: str):
        """A numbered Step group for one case of a command; yields the Flow to add the case's steps to."""
        self.n += 1
        self.covered.add(command)
        f = self.f
        label = f"{command}: {_safe(title)}"
        f.cleanup = []          # steps a case asks to run even when it fails (e.g. close its test window)
        with f.step(f"{self.n}. {command} - {title}", why=why):
            with f.try_(desc="Run the command and check what it gave back") as t:
                yield f
                f.expr("ut_pass", "$ut_pass + 1", desc="Count the case as passed")
                f.set("ut_report", "${ut_report}PASS  " + label + "\n", desc="Add a PASS line to the report")
            with f.catch(t, codes=("*",), message_to="ut_err"):
                f.expr("ut_fail", "$ut_fail + 1", desc="Count the case as failed")
                f.set("ut_report", "${ut_report}FAIL  " + label + ": ${ut_err}\n", desc="Add a FAIL line with the reason")
            if f.cleanup:
                with f.finally_(t):
                    for step in f.cleanup:
                        step(f)
        f.cleanup = []

    # ---------- checks ----------
    def check(self, op: str, left, right=None, what: str = "the result"):
        """Throws UT.CHECK_FAILED unless the condition holds (ops as in If: eq, ne, contains, notEmpty, gt, ...)."""
        cond = {"op": op, "left": left}
        if right is not None or op in ("eq", "ne"):
            cond["right"] = right
        f = self.f
        with f.if_(cond, desc="Check " + what) as s:
            f.comment("As expected")
        with f.else_(s):
            shown = json.dumps(right, ensure_ascii=False) if right is not None else ""
            f.act("core/throw", desc="Stop the case: the check failed", code="UT.CHECK_FAILED",
                  message=_safe(f"{what}: expected {op} {shown}"[:300]) + (", got ${" + left[1:-1] + "}" if isinstance(left, str) and re.fullmatch(r"\$[A-Za-z_][A-Za-z0-9_]*\$", left) else ""))

    def check_eq(self, var: str, expected):
        self.check("eq", f"${var}$", expected, what=var)

    def condition_case(self, condition: str, title: str, why: str, args: dict, expected: bool):
        """A case for a toolkit condition: used in an If, and checked to come out true or false as expected."""
        self.var("ut_cond", "Boolean", False)
        with self.case(condition, title, why) as f:
            f.set("ut_cond", False)
            with f.if_({"condition": condition, "args": args}, desc="Use the condition in an If"):
                f.set("ut_cond", True, desc="The condition was true")
            self.check("eq", "$ut_cond$", expected, what="the condition")

    def expect_error(self, code: str, steps_fn):
        """The steps must fail with this error code (or one starting with it)."""
        f = self.f
        self.var("ut_got_error", "Boolean", False)
        f.set("ut_got_error", False)
        with f.try_(desc="These steps should fail with " + code) as t:
            steps_fn(f)
        with f.catch(t, codes=(code,)):
            f.set("ut_got_error", True, desc="The expected error came")
        self.check("eq", "$ut_got_error$", True, what="error " + code)

    # ---------- end ----------
    def finish(self):
        f = self.f
        with f.step(f"{self.n + 1}. Summary", why="How many cases passed; the run fails when any case failed."):
            f.log("Unit test results: ${ut_pass} passed, ${ut_fail} failed\n${ut_report}")
            with f.if_({"op": "gt", "left": "$ut_fail$", "right": 0}, desc="Did any case fail?"):
                f.act("core/throw", desc="Mark the run failed", code="UT.FAILED", message="${ut_fail} of the cases failed; see ut_report")
        return f

    def to_json(self):
        return self.f.to_json()


# ---------------------------------------------------------------------------------------------------------------------
# Command samples (platform/cli/src/test/resources/command-samples) turned into cases
# ---------------------------------------------------------------------------------------------------------------------

def _vars_in(obj, out: set):
    if isinstance(obj, dict):
        for k, v in obj.items():
            if k == "out" and isinstance(v, dict):
                out.update(str(x) for x in v.values())
            elif k == "variable" and isinstance(v, str):
                out.add(v)
            else:
                _vars_in(v, out)
    elif isinstance(obj, list):
        for x in obj:
            _vars_in(x, out)


SPECIAL = {"browser", "desktop", "mail", "ldap", "ssh", "ftp", "internet", "node"}


def add_sample(ua: UnitAgent, sample: dict) -> str | None:
    """Adds one sample case; returns a reason when it cannot be turned into a stand-alone case."""
    req = sample.get("requires")
    if req in SPECIAL:
        return f"needs {req}"
    if sample.get("agents") or sample.get("subflows"):
        return "uses child agents or subflows"
    cmd = sample["command"]
    title = sample.get("name") or "case"
    names: set[str] = set()
    _vars_in(sample.get("steps"), names)
    names.update((sample.get("vars") or {}).keys())
    names.update((sample.get("expect") or {}).keys())
    types = sample.get("types") or {}
    for n in sorted(names):
        if n.startswith("ut_"):
            continue
        ua.var(n, types.get(n, "Any"))
    why = sample.get("why") or f"Shows {cmd} ({title}) and checks its result."
    with ua.case(cmd, title, why) as f:
        for path, text in (sample.get("files") or {}).items():
            f.act("ooa.file/write_text", desc="Prepare the test file " + path, path=path, text=text)
        for n, v in (sample.get("vars") or {}).items():
            f.set(n, v, desc="Test value for " + n)
        steps = json.loads(json.dumps(sample["steps"]))
        _renumber(f, steps)
        if sample.get("error"):
            code = sample["error"]
            ua.expect_error(code, lambda ff: ff._stack[-1].extend(steps))
        else:
            f._stack[-1].extend(steps)
            for n, v in (sample.get("expect") or {}).items():
                ua.check_eq(n, v)
            for path, want in (sample.get("expectFiles") or {}).items():
                ua.var("ut_exists", "Boolean", False)
                if want is False:
                    f.act("ooa.file/get_info", desc="Look for " + path, path=path, out={"exists": "ut_exists"}, cont=True)
                    ua.check("eq", "$ut_exists$", False, what=path + " absent")
                else:
                    ua.var("ut_text", "String", "")
                    f.act("ooa.file/read_text", desc="Read " + path, path=path, out={"text": "ut_text"})
                    if isinstance(want, str):
                        ua.check("eq", "$ut_text$", want, what=path)
    return None


def _renumber(f: Flow, steps):
    """Gives sample steps fresh ids unique in the agent."""
    for s in steps:
        if isinstance(s, dict):
            s["id"] = f._id()
            for k in ("children", "else", "finally", "default"):
                if isinstance(s.get(k), list):
                    _renumber(f, s[k])
            for k in ("elseIf", "catch", "cases"):
                for b in s.get(k) or []:
                    if isinstance(b, dict) and isinstance(b.get("children"), list):
                        _renumber(f, b["children"])
