"""Builds the command unit-test agents.

    python -I gen.py <group> [<group> ...]      build these groups (see groups.py), print what is not covered yet
    python -I gen.py --all                       build every group

Each group's agent = the command samples for its commands (converted by ut.add_sample) + the hand-written cases in
cases/<group>.py (a function add(ua) that adds cases for commands the samples miss). Agents are written to
../agents/<file>.flow.json and to the Studio workspace folder given by --workspace (for check-in).
"""
from __future__ import annotations

import argparse
import glob
import importlib.util
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from ut import UnitAgent, add_sample, FOLDER  # noqa: E402
from groups import GROUPS  # noqa: E402

ROOT = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
SAMPLES = os.path.join(ROOT, "platform", "cli", "src", "test", "resources", "command-samples")
MANIFEST = os.environ.get("OOA_MANIFEST", os.path.join(HERE, "..", "manifest.json"))


def load_samples():
    out = []
    for path in sorted(glob.glob(os.path.join(SAMPLES, "*.json"))):
        for c in json.loads(open(path, encoding="utf-8").read(), strict=False):
            c["_file"] = os.path.basename(path)
            out.append(c)
    return out


def load_commands():
    """{toolkit id: [command ids as used in steps]} from the toolkit manifest (ooa manifest)."""
    m = json.loads(open(MANIFEST, encoding="utf-8").read(), strict=False)
    out = {}
    for t in m:
        pid = t["id"]
        short = pid[4:] if pid.startswith("ooa.") else pid
        ids = []
        for kind in ("actions", "conditions", "iterators"):
            for a in t.get(kind) or []:
                ids.append(short + "/" + (a["id"] if isinstance(a, dict) else a))
        out[pid] = ids
    return out


def short(cmd: str) -> str:
    return cmd[4:] if cmd.startswith("ooa.") else cmd


def build(gid: str, samples, commands, workspace=None):
    g = GROUPS[gid]
    wanted = set()
    for pid in g["toolkits"]:
        for c in commands.get(pid, []):
            if not g.get("only") or any(c.split("/", 1)[1].startswith(p) for p in g["only"]):
                if not any(c.split("/", 1)[1].startswith(p) for p in g.get("skip", [])):
                    wanted.add(c)
    ua = UnitAgent(g["id"], g["name"], g["about"])
    skipped = []
    for s in samples:
        if short(s["command"]) in wanted:
            why = add_sample(ua, s)
            if why:
                skipped.append(f'{s["command"]} ({s.get("name", "")}): {why}')
    case_file = os.path.join(HERE, "cases", gid + ".py")
    if os.path.exists(case_file):
        spec = importlib.util.spec_from_file_location("cases_" + gid, case_file)
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        mod.add(ua)
    ua.finish()
    covered = {short(c) for c in ua.covered}
    missing = sorted(wanted - covered)
    text = ua.to_json()
    name = g["name"] + ".flow.json"
    os.makedirs(os.path.join(HERE, "..", "agents"), exist_ok=True)
    open(os.path.join(HERE, "..", "agents", name), "w", encoding="utf-8").write(text)
    if workspace:
        d = os.path.join(workspace, FOLDER)
        os.makedirs(d, exist_ok=True)
        open(os.path.join(d, name), "w", encoding="utf-8").write(text)
    return {"group": gid, "agent": name, "cases": ua.n, "commands": len(wanted), "covered": len(wanted & covered),
            "missing": missing, "skipped_samples": skipped}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("groups", nargs="*")
    ap.add_argument("--all", action="store_true")
    ap.add_argument("--workspace")
    ap.add_argument("--quiet", action="store_true")
    a = ap.parse_args()
    samples, commands = load_samples(), load_commands()
    ids = list(GROUPS) if a.all else a.groups
    for gid in ids:
        r = build(gid, samples, commands, a.workspace)
        print(f'{r["agent"]}: {r["cases"]} cases, {r["covered"]}/{r["commands"]} commands covered')
        if not a.quiet:
            for m in r["missing"]:
                print("   missing:", m)
            for s in r["skipped_samples"]:
                print("   sample not converted:", s)


if __name__ == "__main__":
    main()
