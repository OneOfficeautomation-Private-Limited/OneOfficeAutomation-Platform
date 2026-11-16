"""Writes the real-world test agents.

    python generate.py [--workspace "C:/Users/<you>/OOA Workspace"] [--only rw01_b2,...]

Each agent goes to examples/real-world-tests/agents/<folder>/<name>.flow.json and, with --workspace, also to
"<workspace>/Real-world tests/<folder>/" so OneAgentStudio (and its check-in to One CommandCenter) sees it.
agents/index.json lists every agent with its repository path, for the check-in and run scripts.
"""
from __future__ import annotations

import argparse
import importlib
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

ROOT = "Real-world tests"
MODULES = ["rw01", "rw02", "rw03", "rw04", "rw05", "rw06", "rw07", "rw08", "shared"]


def build_all(only=None):
    out = []
    for mod_name in MODULES:
        try:
            mod = importlib.import_module(mod_name)
        except ModuleNotFoundError as e:
            if e.name == mod_name:
                continue
            raise
        for make in getattr(mod, "CHILDREN", []) + getattr(mod, "MAINS", []):
            built = make()
            flow = getattr(built, "f", built)
            if only and flow.id not in only:
                continue
            out.append((mod.FOLDER, flow))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--workspace")
    ap.add_argument("--only")
    a = ap.parse_args()
    only = set(a.only.split(",")) if a.only else None
    agents_dir = os.path.join(os.path.dirname(HERE), "agents")
    index = []
    seen = set()
    for folder, flow in build_all(only):
        if flow.id in seen:
            raise SystemExit(f"duplicate agent id {flow.id}")
        seen.add(flow.id)
        rel = f"{ROOT}/{folder}/{flow.name}.flow.json"
        targets = [os.path.join(agents_dir, folder, flow.name + ".flow.json")]
        if a.workspace:
            targets.append(os.path.join(a.workspace, ROOT, folder, flow.name + ".flow.json"))
        text = flow.to_json()
        for t in targets:
            os.makedirs(os.path.dirname(t), exist_ok=True)
            with open(t, "w", encoding="utf-8", newline="\n") as fh:
                fh.write(text)
        index.append({"id": flow.id, "name": flow.name, "folder": f"{ROOT}/{folder}", "path": rel,
                      "file": os.path.relpath(targets[0], os.path.dirname(HERE)).replace("\\", "/")})
        print(f"{flow.id:14} {rel}")
    if not only:
        with open(os.path.join(agents_dir, "index.json"), "w", encoding="utf-8", newline="\n") as fh:
            json.dump(index, fh, indent=2)
    print(f"{len(index)} agents written")


if __name__ == "__main__":
    main()
