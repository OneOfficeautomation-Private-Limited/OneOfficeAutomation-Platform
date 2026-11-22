"""Runs unit-test agents on this computer with `ooa run` and prints their results.

    python -I run.py "UT String" ["UT Excel" ...]      agent names (files in ../agents)
    python -I run.py --all                            every agent whose group needs "local", "office", "standin" or "database"

Runs in <data>/runs (the agent's work folder) with the vault values from <data>/secrets.env (OOA_SECRET_... lines).
Writes <data>/runs/<agent>.result.json and prints PASS/FAIL counts and the FAIL lines.
"""
from __future__ import annotations

import json
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from groups import GROUPS  # noqa: E402

DATA = os.environ.get("UT_DATA", "C:/Users/virup/OOA Unit tests")
OOA = os.environ.get("OOA_BAT", "C:/OOA/ooa-local-server/app/bin/ooa.bat")
AGENTS = os.path.join(HERE, "..", "agents")


def env():
    e = dict(os.environ)
    p = os.path.join(DATA, "secrets.env")
    if os.path.exists(p):
        for line in open(p, encoding="utf-8"):
            if "=" in line and not line.startswith("#"):
                k, v = line.rstrip("\n").split("=", 1)
                e[k] = v
    return e


def run(name: str, timeout=1800):
    runs = os.path.join(DATA, "runs")
    os.makedirs(runs, exist_ok=True)
    flow = os.path.join(AGENTS, name + ".flow.json")
    p = subprocess.run([OOA, "run", flow], cwd=runs, env=env(), capture_output=True, text=True, encoding="utf-8",
                       errors="replace", timeout=timeout)
    out = p.stdout
    try:
        r = json.loads(out[out.index("{"):])
    except ValueError:
        r = {"status": "no result", "stdout": out[-2000:], "stderr": p.stderr[-2000:]}
    open(os.path.join(runs, name + ".result.json"), "w", encoding="utf-8").write(json.dumps(r, indent=1, ensure_ascii=False))
    o = r.get("outputs") or {}
    fails = [l for l in str(o.get("ut_report", "")).splitlines() if l.startswith("FAIL")]
    err = r.get("error") or {}
    print(f'{name}: {r.get("status")}  pass {o.get("ut_pass")}  fail {o.get("ut_fail")}'
          + (f'  error {err.get("code")}: {err.get("message")}' if err and err.get("code") != "UT.FAILED" else ""))
    for l in fails:
        print("   " + l)
    if r.get("status") == "no result":
        print("   " + (r.get("stderr") or r.get("stdout") or "")[-600:])
    return r


if __name__ == "__main__":
    names = sys.argv[1:]
    if names == ["--all"]:
        names = [g["name"] for g in GROUPS.values() if g.get("needs", "local") in ("local", "office", "standin", "database")]
    for n in names:
        run(n)
