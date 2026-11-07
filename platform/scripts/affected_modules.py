"""Which modules a change touches, from module-map.json (docs/architecture/13-modules.md).

A module is affected when one of its files changed, or a file of a module it depends on (directly or not): a kernel
change affects every feature module, a Document Automation change affects only Document Automation. Files no module
claims (the CI workflow itself, the root README) affect everything, to be safe.

Usage:
    python -I platform/scripts/affected_modules.py <base ref> [<head ref>]     (in the repository root)
    git diff --name-only A B | python -I platform/scripts/affected_modules.py -

Prints the affected module ids, one per line, then "all" when the change could affect everything. With
GITHUB_OUTPUT set (GitHub Actions) it also writes modules=<comma list> and one <id>=true|false line per module
(dashes become underscores: document_automation=true).
"""
import re
import json
import os
import subprocess
import sys

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))


def load_map():
    with open(os.path.join(REPO, "module-map.json"), encoding="utf-8") as f:
        return json.load(f)["modules"]


_compiled = {}


def glob_match(path, pattern):
    """** is any path, * any name within one folder (as ModuleMapTest reads the map)."""
    rx = _compiled.get(pattern)
    if rx is None:
        out, i = [], 0
        while i < len(pattern):
            if pattern.startswith("**", i):
                out.append(".*")
                i += 2
            elif pattern[i] == "*":
                out.append("[^/]*")
                i += 1
            else:
                out.append(re.escape(pattern[i]))
                i += 1
        rx = _compiled[pattern] = re.compile("".join(out))
    return rx.fullmatch(path) is not None


def specificity(pattern):
    star = pattern.find("*")
    return 100_000 + len(pattern) if star < 0 else star


def owner(modules, path):
    best, score = None, -1
    for m in modules:
        for p in m["paths"]:
            if glob_match(path, p) and specificity(p) > score:
                best, score = m["id"], specificity(p)
    return best


def affected(modules, changed):
    """The modules a list of changed paths affects, and whether the change could affect everything."""
    deps = {m["id"]: set(m.get("dependsOn", [])) for m in modules}
    direct, everything = set(), False
    for path in changed:
        o = owner(modules, path)
        if o is None:
            everything = True
        else:
            direct.add(o)
    if everything:
        return sorted(deps), True
    out = set(direct)
    grew = True
    while grew:  # a module is affected when anything it depends on is
        grew = False
        for m, ds in deps.items():
            if m not in out and ds & out:
                out.add(m)
                grew = True
    return sorted(out), False


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    if sys.argv[1] == "-":
        changed = [l.strip() for l in sys.stdin if l.strip()]
    else:
        head = sys.argv[2] if len(sys.argv) > 2 else "HEAD"
        changed = subprocess.run(["git", "diff", "--name-only", sys.argv[1], head], cwd=REPO, capture_output=True, text=True, check=True).stdout.split()
    modules = load_map()
    ids, everything = affected(modules, changed)
    for i in ids:
        print(i)
    if everything:
        print("all")
    out = os.environ.get("GITHUB_OUTPUT")
    if out:
        with open(out, "a", encoding="utf-8") as f:
            f.write("modules=" + ",".join(ids) + "\n")
            for m in modules:
                f.write(m["id"].replace("-", "_") + "=" + ("true" if m["id"] in ids else "false") + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
