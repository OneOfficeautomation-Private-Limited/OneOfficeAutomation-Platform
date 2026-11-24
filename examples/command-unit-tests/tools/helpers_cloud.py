"""Shared helpers for the cloud and integration unit-test agents (groups_cloud.py).

adapt_samples(ua, agent_name): the command samples a group's agent was built from talk to the samples test's own
short-lived servers ({mock}, {web}, {tmp}) and read made-up keys from a test vault ("credentials"). This turns those
cases into cases that run against the stand-in:
  {mock}  -> $standin_url$/mock/<file>-<index>   (standin/plugins/cloud_samples.py answers from the sample's list)
  {web}   -> $standin_url$/web/<agent name>       (the files the case wrote in the agent's run folder)
  {tmp}   -> .                                     (the run folder)
  $var    -> {"$credential": "unit_tests/..."}     for each sample credential (made-up values in secrets_cloud.env)
  an expected {"$contains": text} becomes a "contains" check.
"""
from __future__ import annotations

import glob
import json
import os
import re
from urllib.parse import quote

HERE = os.path.dirname(os.path.abspath(__file__))
SAMPLES = os.path.join(HERE, "..", "..", "..", "platform", "cli", "src", "test", "resources", "command-samples")


def _samples():
    out = []
    for path in sorted(glob.glob(os.path.join(SAMPLES, "*.json"))):
        num = os.path.basename(path).split("-", 1)[0]
        for i, c in enumerate(json.loads(open(path, encoding="utf-8").read(), strict=False)):
            out.append(("%s-%d" % (num, i), c))
    return out


def vault_ref(ref: str) -> str:
    """test/aws/id -> unit_tests/aws/id"""
    return "unit_tests/" + ref.split("/", 1)[1] if ref.startswith("test/") else "unit_tests/" + ref


def _walk(obj, fn):
    if isinstance(obj, dict):
        for k in list(obj):
            obj[k] = _walk(obj[k], fn)
        return fn(obj)
    if isinstance(obj, list):
        return [_walk(x, fn) for x in obj]
    return fn(obj)


def adapt_samples(ua, agent_name: str):
    pool = _samples()
    used = set()
    for step in ua.f.steps:
        if step.get("action") != "core/step":
            continue
        title = step["args"].get("title", "")
        mt = re.match(r"\d+\. (\S+) - (.*)$", title)
        if not mt:
            continue
        key, sample = next(((k, s) for k, s in pool if k not in used and s.get("command") == mt.group(1)
                            and (s.get("name") or "case") == mt.group(2)), (None, None))
        if sample is None:
            continue
        used.add(key)
        text = json.dumps(step["children"], ensure_ascii=False)
        text = text.replace("{mock}", "$standin_url$/mock/" + key)
        text = text.replace("{web}", "$standin_url$/web/" + quote(agent_name))
        text = text.replace("{tmp}/", "").replace("{tmp}", ".")
        children = json.loads(text)
        creds = {v: vault_ref(r) for v, r in (sample.get("credentials") or {}).items()}

        def fix(x):
            if isinstance(x, str):
                mv = re.fullmatch(r"\$([A-Za-z_]\w*)\$?", x)
                if mv and mv.group(1) in creds:
                    return {"$credential": creds[mv.group(1)]}
            if isinstance(x, dict) and isinstance(x.get("condition"), dict):
                c = x["condition"]
                if c.get("op") == "eq" and isinstance(c.get("right"), dict) and list(c["right"]) == ["$contains"]:
                    c["op"], c["right"] = "contains", c["right"]["$contains"]
                    ml = re.fullmatch(r"\$(\w+)\$", str(c.get("left")))
                    if ml:      # compare with the value as text (a dictionary would be searched by its keys)
                        c["left"] = "${" + ml.group(1) + "}"
            return x
        step["children"] = _walk(children, fix)
        # the variables the sample read from the vault are not needed any more
        ua.f.variables = [v for v in ua.f.variables if v["name"] not in creds]


def value(ua, expression: str, op: str, expected=None, what: str = "the result"):
    """Works out an expression into ut_v and checks it, e.g. value(ua, "len($t.rows)", "eq", 2, "rows")."""
    ua.var("ut_v", "Any", None)
    ua.f.expr("ut_v", expression, desc="Work out " + what)
    ua.check(op, "$ut_v$", expected, what=what)


def cred(path: str) -> dict:
    """A made-up secret from the vault: cred("aws/access_key_id") -> {"$credential": "unit_tests/aws/access_key_id"}."""
    return {"$credential": "unit_tests/" + path}


def secret_lines(groups_toolkits):
    """OOA_SECRET_... lines for the sample credentials of these toolkits (made-up values from the samples)."""
    lines = {}
    for _k, s in _samples():
        if not any(s.get("command", "").startswith(t + "/") for t in groups_toolkits):
            continue
        for _v, r in (s.get("credentials") or {}).items():
            val = (s.get("secrets") or {}).get(r, "made-up-" + r.replace("/", "-"))
            key = "OOA_SECRET_" + vault_ref(r).replace("/", "_").replace("-", "_").upper()
            lines.setdefault(key, val)
    return lines
