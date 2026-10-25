"""Checks the panel layouts (@Action panel = "...") of toolkit source files without building.

Usage: python check-panels.py <Toolkit.java> [...]
Each layout must name every @Input of its method exactly once, using the same syntax as ActionSpec.panelLayout():
  "a, b, [Tick label: c, d], (Choice label: Option = e / Option = f, g / Only a label =) | more1, more2"
Prints one line per problem and exits 1 when there are problems.
"""
import re
import sys


def annotation_end(s, i):
    """Index just past the ')' that closes the annotation whose '(' is at s[i-1]."""
    depth, inq = 1, False
    while depth:
        c = s[i]
        if inq:
            if c == "\\":
                i += 1
            elif c == '"':
                inq = False
        elif c == '"':
            inq = True
        elif c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
        i += 1
    return i


def java_string(expr):
    """Joins a Java string expression made of literals and +, e.g. "a, " + "b"."""
    parts = re.findall(r'"((?:[^"\\]|\\.)*)"', expr)
    return "".join(p.replace('\\"', '"').replace("\\\\", "\\") for p in parts)


def parse(panel, inputs):
    problems, seen = [], []

    def place(name):
        name = name.strip()
        if name not in inputs:
            problems.append("names '%s', which is not an input" % name)
        elif name in seen:
            problems.append("places '%s' twice" % name)
        else:
            seen.append(name)

    halves = panel.split("|")
    if len(halves) > 2:
        problems.append("has more than one '|'")
    for half in halves[:2]:
        i = 0
        while i < len(half):
            c = half[i]
            if c in ", \t":
                i += 1
                continue
            if c in "([":
                close = ")" if c == "(" else "]"
                end = half.find(close, i)
                if end < 0:
                    problems.append("has an unclosed '%s'" % c)
                    break
                body = half[i + 1:end]
                if ":" not in body or body.index(":") < 1:
                    problems.append("group '%s' needs 'Label: ...'" % body)
                else:
                    rest = body[body.index(":") + 1:]
                    if c == "[":
                        fields = [f for f in rest.split(",") if f.strip()]
                        if not fields:
                            problems.append("tick '%s' has no fields" % body)
                        for f in fields:
                            place(f)
                    else:
                        options = rest.split("/")
                        if len(options) < 2:
                            problems.append("choice '%s' needs two options" % body)
                        for opt in options:
                            label, eq, fs = opt.partition("=")
                            fields = [f for f in (fs if eq else label).split(",") if f.strip()]
                            if not fields and not (eq and label.strip()):
                                problems.append("choice '%s' has an option with no label and no fields" % body)
                            for f in fields:
                                place(f)
                i = end + 1
            else:
                end = i
                while end < len(half) and half[end] not in ",[(":
                    end += 1
                place(half[i:end])
                i = end
    for name in inputs:
        if name not in seen:
            problems.append("does not place input '%s'" % name)
    return problems


def check(path):
    s = open(path, encoding="utf-8").read()
    bad = 0
    for m in re.finditer(r"@Action\(", s):
        end = annotation_end(s, m.end())
        ann = s[m.start():end]
        aid = re.search(r'id\s*=\s*"([^"]+)"', ann).group(1)
        pm = re.search(r'panel\s*=\s*((?:"(?:[^"\\]|\\.)*"\s*\+?\s*)+)', ann)
        # the method's inputs: @Input(name = "...") inside its parameter list
        sig = re.compile(r"\bpublic\s+[\w<>,.? \[\]]+?\s+\w+\s*\(").search(s, end)
        params_end = annotation_end(s, sig.end())
        inputs = re.findall(r'@Input\(\s*name\s*=\s*"([^"]+)"', s[sig.end():params_end])
        if not pm:
            continue
        for p in parse(java_string(pm.group(1)), inputs):
            print("%s: %s: the panel %s" % (path.split("/")[-1].split("\\")[-1], aid, p))
            bad += 1
    return bad


if __name__ == "__main__":
    total = sum(check(p) for p in sys.argv[1:])
    print("OK" if not total else "%d problems" % total)
    sys.exit(1 if total else 0)
