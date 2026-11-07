"""Writes .github/CODEOWNERS from module-map.json (docs/architecture/13-modules.md, phase 7).

Each module lists its "owners" (GitHub users or teams, such as @org/insights-team); pull requests that change a
module's files then ask its owners for a review. A module without owners falls back to the map's "defaultOwners".

Usage (in the repository root):
    python -I platform/scripts/codeowners.py            writes .github/CODEOWNERS
    python -I platform/scripts/codeowners.py --check    fails when .github/CODEOWNERS is not what the map says (CI)
"""
import json
import os
import sys

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
OUT = os.path.join(REPO, ".github", "CODEOWNERS")


def specificity(pattern):
    """As affected_modules.py and ModuleMapTest: an exact path beats a wildcard, a longer prefix beats a shorter one."""
    star = pattern.find("*")
    return 100_000 + len(pattern) if star < 0 else star


def render():
    with open(os.path.join(REPO, "module-map.json"), encoding="utf-8") as f:
        data = json.load(f)
    default = data.get("defaultOwners", [])
    rows = []
    for m in data["modules"]:
        owners = m.get("owners") or default
        for p in m["paths"]:
            rows.append((specificity(p), p, m["id"], owners))
    # CODEOWNERS: the last matching line wins, so the most specific paths go last
    rows.sort(key=lambda r: (r[0], r[1]))
    lines = ["# Generated from module-map.json by platform/scripts/codeowners.py; change the map, not this file.",
             "# The last matching line wins: the most specific paths come last.",
             "* " + " ".join(default), ""]
    for _, p, mid, owners in rows:
        lines.append("/" + p + " " + " ".join(owners))
    return "\n".join(lines) + "\n"


def main():
    text = render()
    if "--check" in sys.argv:
        current = open(OUT, encoding="utf-8").read() if os.path.exists(OUT) else ""
        if current != text:
            print(".github/CODEOWNERS is out of date: run python -I platform/scripts/codeowners.py")
            return 1
        print("CODEOWNERS matches module-map.json")
        return 0
    with open(OUT, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    print("wrote .github/CODEOWNERS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
