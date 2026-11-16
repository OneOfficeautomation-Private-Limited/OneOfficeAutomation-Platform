"""Writes the synthetic test data of the real-world tests and the stand-in server's seed.

    python make_data.py "C:/Users/<you>/OOA Real-world tests"

Everything is made up and tagged OOA-QA; nothing refers to real people, companies or systems.
Writes <base>/data/RWxx/... (CSV, text, PDF) and <base>/standin/seed.json.
"""
from __future__ import annotations

import csv
import importlib
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

MODULES = ["data_rw01", "data_rw02", "data_rw03", "data_rw04", "data_rw05", "data_rw06", "data_rw07", "data_rw08"]


def merge(into: dict, more: dict):
    """Deep merge of the RWs' stand-in seeds: dictionaries merge, lists add up."""
    for k, v in more.items():
        if isinstance(v, dict) and isinstance(into.get(k), dict):
            merge(into[k], v)
        elif isinstance(v, list) and isinstance(into.get(k), list):
            into[k].extend(v)
        else:
            into[k] = v


def main():
    base = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.expanduser("~"), "OOA Real-world tests")
    data = os.path.join(base, "data")

    def w(rel, content, header=None):
        p = os.path.join(data, rel)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        if isinstance(content, (bytes, bytearray)):
            with open(p, "wb") as fh:
                fh.write(content)
        elif isinstance(content, str):
            with open(p, "w", encoding="utf-8", newline="\n") as fh:
                fh.write(content)
        else:
            with open(p, "w", encoding="utf-8", newline="") as fh:
                cw = csv.writer(fh)
                if header:
                    cw.writerow(header)
                for row in content:
                    cw.writerow(row)

    seed = {}
    for name in MODULES:
        try:
            mod = importlib.import_module(name)
        except ModuleNotFoundError as e:
            if e.name == name:
                continue
            raise
        mod.write(w)
        merge(seed, mod.seed())
    # fixtures: saved outputs of checked runs, which a child reads when it runs on its own (without the child before it)
    fixtures = os.path.join(os.path.dirname(HERE), "fixtures")
    if os.path.isdir(fixtures):
        for rw in sorted(os.listdir(fixtures)):
            for name in sorted(os.listdir(os.path.join(fixtures, rw))):
                with open(os.path.join(fixtures, rw, name), "rb") as fh:
                    w(rw + "/fixtures/" + name, fh.read())
    os.makedirs(os.path.join(base, "standin"), exist_ok=True)
    with open(os.path.join(base, "standin", "seed.json"), "w", encoding="utf-8") as fh:
        json.dump(seed, fh, indent=1)
    for d in ("output", "db", "keys"):
        os.makedirs(os.path.join(base, d), exist_ok=True)
    # the stand-in server goes next to its seed, where the "Start QA stand-ins" agent starts it
    import shutil
    shutil.copy2(os.path.join(os.path.dirname(HERE), "standin", "standin.py"), os.path.join(base, "standin", "standin.py"))
    print("data written to", data)


if __name__ == "__main__":
    main()
