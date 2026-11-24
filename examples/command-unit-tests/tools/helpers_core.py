"""Shared code for the core and data toolkit groups (groups_core.py).

`resample(ua)` rebuilds the sample cases of an agent with a fuller sample converter than ut.add_sample, so the samples
of these toolkits run as they do in CommandSamplesTest:
  * {tmp} becomes a folder of its own for each case (ut_tmp/c<N> in the run folder, emptied when the agent starts),
    and the case's files and expected files live there;
  * {web}/rates.json (the tests' web server) becomes the stand-in's /rates/usd.json (standin/plugins/rates.py);
  * samples the check refuses before a run (error FLOW.INVALID) are left out: an agent with them would not start;
  * expected values {"$contains": text} and {"$type": object|array|number|string|boolean} are checked as such;
  * file contents are compared with Windows line ends turned into \n, as the tests do;
  * "credentials" become Credential variables set from the vault (unit_tests/samples_<locker>_<credential>/<attribute>);
    their test values are in secrets.env (SAMPLE_SECRETS lists them);
  * loop, catch and Assign-several variables, and the steps inside state machines, are declared and numbered;
  * "subflows" become subflows of the agent.
Samples with child agents ("agents") are left for hand-written cases.
"""
from __future__ import annotations

import json
import os
import re

from ut import UnitAgent, _renumber as _renumber_ut  # noqa: F401
import ut

SPECIAL = ut.SPECIAL
# the vault values the sample cases read (OOA_SECRET_... lines in secrets.env)
SAMPLE_SECRETS = {
    "test/crypto/pass": "s3cret!",
    "test/crypto/a": "one",
    "test/crypto/b": "two",
    "test/hmac/key": "key",
    "test/mail/password": "Sample-Mail-Passw0rd",
}


def vault_ref(ref: str) -> str:
    locker, cred, attr = ref.split("/")
    return f"unit_tests/samples_{locker}_{cred}/{attr}"


def env_name(ref: str) -> str:
    return "OOA_SECRET_" + vault_ref(ref).replace("/", "_").replace("-", "_").upper()


# ---------------------------------------------------------------------------------------------------------------------

def _wanted(gid, commands):
    from groups import GROUPS
    g = GROUPS[gid]
    wanted = set()
    for pid in g["toolkits"]:
        for c in commands.get(pid, []):
            if not g.get("only") or any(c.split("/", 1)[1].startswith(p) for p in g["only"]):
                if not any(c.split("/", 1)[1].startswith(p) for p in g.get("skip", [])):
                    wanted.add(c)
    return wanted


def resample(ua: UnitAgent, drop=()):
    """Throws away the cases gen.py made from the samples and adds them again with the fuller converter.
    drop: (command, sample name) pairs to leave out (they get hand-written cases instead)."""
    import gen
    gid = ua.f.id[3:]
    f = ua.f
    del f.steps[1:]
    keep = {"ut_dir", "standin_url", "ut_pass", "ut_fail", "ut_report", "ut_err"}
    f.variables[:] = [v for v in f.variables if v["name"] in keep]
    ua.n = 0
    ua.covered = set()
    ua.skipped = []
    # an empty case folder for each run
    f._stack.append(f.steps[0]["children"])
    f.act("ooa.file/delete", desc="Empty the case folders left by an earlier run", path="ut_tmp", pattern="*",
          include_subfolders=True, cont=True)
    f._stack.pop()
    wanted = _wanted(gid, gen.load_commands())
    for s in gen.load_samples():
        if gen.short(s["command"]) in wanted and (s["command"], s.get("name")) not in drop:
            why = add_sample(ua, s)
            if why:
                ua.skipped.append(f'{s["command"]} ({s.get("name", "")}): {why}')


def _collect(steps, names: set):
    for s in steps or []:
        if not isinstance(s, dict):
            continue
        for v in (s.get("out") or {}).values():
            names.add(str(v))
        a = s.get("args") or {}
        if s.get("action") == "core/set" and isinstance(a.get("variable"), str):
            names.add(a["variable"])
        if s.get("action") == "core/set_many":
            for r in a.get("assignments") or []:
                if isinstance(r, dict) and isinstance(r.get("variable"), str):
                    names.add(r["variable"])
        if s.get("action") == "core/loop":
            for k in ("as", "index"):
                if isinstance(a.get(k), str):
                    names.add(a[k])
        for k in ("children", "else", "finally", "default"):
            _collect(s.get(k), names)
        for k in ("elseIf", "catch", "cases", "handlers"):
            for b in s.get(k) or []:
                if isinstance(b, dict):
                    for kk in ("as", "message_to"):
                        if isinstance(b.get(kk), str):
                            names.add(b[kk])
                    _collect(b.get("children"), names)
        for l in _state_lists(s):
            _collect(l, names)


def _state_lists(s):
    out = []
    for st in s.get("states") or []:
        if isinstance(st, dict):
            if isinstance(st.get("entry"), list):
                out.append(st["entry"])
            for t in st.get("transitions") or []:
                if isinstance(t, dict) and isinstance(t.get("steps"), list):
                    out.append(t["steps"])
    return out


def renumber(f, steps):
    for s in steps:
        if isinstance(s, dict):
            s["id"] = f._id()
            for k in ("children", "else", "finally", "default"):
                if isinstance(s.get(k), list):
                    renumber(f, s[k])
            for k in ("elseIf", "catch", "cases", "handlers"):
                for b in s.get(k) or []:
                    if isinstance(b, dict) and isinstance(b.get("children"), list):
                        renumber(f, b["children"])
            for l in _state_lists(s):
                renumber(f, l)


def _set_type(ua, name, type_):
    for v in ua.f.variables:
        if v["name"] == name:
            v["type"] = type_
            return
    ua.var(name, type_)


def add_subflows(ua, subflows):
    f = ua.f
    if not hasattr(f, "subflows"):
        f.subflows = []
        orig = f.to_dict

        def to_dict():
            d = orig()
            d["subflows"] = f.subflows
            return d
        f.to_dict = to_dict
    have = {s["name"] for s in f.subflows}
    for sub in subflows:
        if sub["name"] in have:
            raise ValueError("subflow name used twice: " + sub["name"])
        f.subflows.append(sub)


def rename_var(raw: str, old: str, new: str) -> str:
    """Renames a variable in a sample's JSON text: $old, ${old...} and the string "old" (variable fields, out maps,
    vars, types and expect keys)."""
    o = re.escape(old)
    raw = re.sub(r"\$" + o + r"(?![A-Za-z0-9_])", "$" + new, raw)
    raw = re.sub(r"\$\{" + o + r"(?=[}.\[])", "${" + new, raw)
    return re.sub(r'"' + o + r'"', '"' + new + '"', raw)


def check_value(ua, name, want):
    """A check of a sample's expected value, with the {"$contains"} and {"$type"} matchers."""
    if isinstance(want, dict) and len(want) == 1 and "$contains" in want:
        ua.check("contains", f"${name}$", want["$contains"], what=name)
    elif isinstance(want, dict) and len(want) == 1 and "$type" in want:
        t = want["$type"]
        if t == "object":
            ua.check("startsWith", f"${name}$", "{", what=name + " is a Dictionary")
        elif t == "array":
            ua.check("startsWith", f"${name}$", "[", what=name + " is a List")
        elif t == "number":
            ua.check("matches", f"${name}$", r"^-?[0-9]", what=name + " is a number")
        elif t == "boolean":
            ua.check("in", f"${name}$", [True, False], what=name + " is true or false")
        else:
            ua.check("notEmpty", f"${name}$", what=name + " is text")
    elif isinstance(want, dict) and "$binary" in want:
        # a Binary or Image value (shown as Base64 in run results): its size and content are checked by other values
        ua.check("notEmpty", f"${name}$", what=name + " (Binary or Image) holds a value")
    elif want == "****":
        # a Credential result (masked in run results): it can only be checked to hold something
        ua.check("notEmpty", f"${name}$", what=name + " (a Credential) holds a value")
    else:
        ua.check_eq(name, want)


def add_sample(ua: UnitAgent, sample: dict) -> str | None:
    req = sample.get("requires")
    if req in SPECIAL:
        return f"needs {req}"
    if sample.get("agents"):
        return "uses child agents"
    if sample.get("onError") or sample.get("mock"):
        return "uses an error handler or a mock server"
    cmd = sample["command"]
    title = sample.get("name") or "case"
    n = ua.n + 1
    tmp = f"ut_tmp/c{n}"
    raw = json.dumps(sample, ensure_ascii=False)
    if sample.get("error") == "FLOW.INVALID":
        return "refused by the check before the run (FLOW.INVALID), so it cannot sit in an agent that runs"
    # Credential variables and variables of a given type get names of their own (cred_<name>, <name>_c<N>), so they
    # never clash with a variable of the same name and another type in another case
    for nm in sample.get("credentials") or {}:
        raw = rename_var(raw, nm, "cred_" + nm)
    for nm in sample.get("types") or {}:
        raw = rename_var(raw, nm, f"{nm}_c{n}")
    uses_tmp = "{tmp}" in raw
    raw = raw.replace("{tmp}", tmp)
    if "{web}" in raw:
        # the tests' little web server: the rates file is served by the stand-in (standin/plugins/rates.py)
        raw = raw.replace("{web}/rates.json", "${standin_url}/rates/usd.json")
        if "{web}" in raw:
            return "reads a file from the tests' web server"
    sample = json.loads(raw)
    base = tmp + "/" if uses_tmp else ""
    names: set[str] = set(sample.get("vars") or {})
    _collect(sample.get("steps"), names)
    for sub in sample.get("subflows") or []:
        _collect(sub.get("steps"), names)
    names.update((sample.get("expect") or {}).keys())
    types = sample.get("types") or {}
    creds = sample.get("credentials") or {}
    for nm in sorted(names - set(creds)):
        if not nm.startswith("ut_"):
            ua.var(nm, types.get(nm, "Any"))
    for nm in creds:
        _set_type(ua, nm, "Credential")
    why = sample.get("why") or f"Shows {cmd} ({title}) and checks its result."
    with ua.case(cmd, title, why) as f:
        for nm, ref in creds.items():
            f.set(nm, {"$credential": vault_ref(ref)}, desc="Test passphrase from the vault for " + nm)
        for path, text in (sample.get("files") or {}).items():
            f.act("ooa.file/write_text", desc="Prepare the test file " + path, path=base + path, text=text)
        for nm, v in (sample.get("vars") or {}).items():
            f.set(nm, v, desc="Test value for " + nm)
        steps = sample["steps"]
        renumber(f, steps)
        if sample.get("subflows"):
            for sub in sample["subflows"]:
                renumber(f, sub["steps"])
            add_subflows(ua, sample["subflows"])
        if sample.get("error"):
            ua.expect_error(sample["error"], lambda ff: ff._stack[-1].extend(steps))
        else:
            f._stack[-1].extend(steps)
            for nm, v in (sample.get("expect") or {}).items():
                check_value(ua, nm, v)
        for path, want in (sample.get("expectFiles") or {}).items():
            p = base + path
            if isinstance(want, bool):
                ua.var("ut_exists", "Boolean", False)
                f.set("ut_exists", False)
                with f.if_({"condition": "ooa.file/exists", "args": {"path": p}}, desc="Look for " + path):
                    f.set("ut_exists", True)
                ua.check("eq", "$ut_exists$", want, what=path + (" exists" if want else " absent"))
            else:
                ua.var("ut_text", "String", "")
                f.act("ooa.file/read_text", desc="Read " + path, path=p, out={"text": "ut_text"})
                f.expr("ut_text", "replace($ut_text, '\\r\\n', '\\n')", desc="Windows line ends as \\n")
                ua.check("eq", "$ut_text$", str(want), what=path)
    return None


def write_secrets(path):
    """Adds the sample vault values to secrets.env (lines that are not there yet)."""
    have = open(path, encoding="utf-8").read() if os.path.exists(path) else ""
    add = [f"{env_name(r)}={v}" for r, v in SAMPLE_SECRETS.items() if env_name(r) + "=" not in have]
    if add:
        with open(path, "a", encoding="utf-8") as fh:
            fh.write(("" if have.endswith("\n") or not have else "\n") + "\n".join(add) + "\n")
    return add
