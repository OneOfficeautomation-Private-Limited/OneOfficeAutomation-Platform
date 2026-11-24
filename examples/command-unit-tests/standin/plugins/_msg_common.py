"""Shared helpers of the Microsoft 365 and Google stand-in plugins (not a plugin itself: the name starts with _).

* odata(): a small OData $filter evaluator (eq ne gt ge lt le, and or not, startswith, contains, parentheses).
* Grid: a worksheet of cells for the Excel online and Google Sheets stand-ins (values, formulas, formats).
"""
from __future__ import annotations

import re


def sendb(h, status, data, ctype="application/octet-stream"):
    """Bytes as they are (the core send() writes str(bytes) for anything that is not a dict, list or text)."""
    data = data if isinstance(data, bytes) else str(data).encode("utf-8")
    h.send_response(status)
    h.send_header("Content-Type", ctype)
    h.send_header("Content-Length", str(len(data)))
    h.end_headers()
    h.wfile.write(data)

# ---------------------------------------------------------------- OData $filter

_TOK = re.compile(r"\s*(?:(\()|(\))|(,)|'((?:[^']|'')*)'|([A-Za-z_][\w./@-]*)|([-+]?\d[\w:.+-]*))")


def _tokens(text):
    out, pos = [], 0
    while pos < len(text):
        m = _TOK.match(text, pos)
        if not m or m.end() == pos:
            if text[pos:].strip() == "":
                break
            raise ValueError("bad filter near: " + text[pos:pos + 20])
        pos = m.end()
        if m.group(1):
            out.append(("(", None))
        elif m.group(2):
            out.append((")", None))
        elif m.group(3):
            out.append((",", None))
        elif m.group(4) is not None:
            out.append(("str", m.group(4).replace("''", "'")))
        elif m.group(5):
            out.append(("word", m.group(5)))
        else:
            out.append(("lit", m.group(6)))
    return out


def get_path(rec, path):
    cur = rec
    for part in path.split("/"):
        if isinstance(cur, dict):
            low = {k.lower(): k for k in cur}
            cur = cur.get(part, cur.get(low.get(part.lower(), ""), None))
        else:
            return None
    return cur


def _cmp(a, op, b):
    if isinstance(a, bool) or isinstance(b, bool):
        a = str(a).lower() if isinstance(a, bool) else a
        b = str(b).lower() if isinstance(b, bool) else b
    try:
        if isinstance(a, (int, float)) or isinstance(b, (int, float)):
            a, b = float(a), float(b)
    except (TypeError, ValueError):
        a, b = str(a), str(b)
    if isinstance(a, str) and isinstance(b, str):
        a, b = a.lower(), b.lower()
    if a is None or b is None:
        return op == "eq" and a is b or op == "ne" and a is not b
    return {"eq": a == b, "ne": a != b, "gt": a > b, "ge": a >= b, "lt": a < b, "le": a <= b}[op]


def odata(text, rec, prefix=""):
    """True when the record matches the filter. prefix (e.g. 'fields/') is taken off field names."""
    if not text or not str(text).strip():
        return True
    toks = _tokens(str(text))
    i = [0]

    def peek():
        return toks[i[0]] if i[0] < len(toks) else (None, None)

    def take():
        t = peek()
        i[0] += 1
        return t

    def value(t):
        kind, v = t
        if kind == "str":
            return v
        if kind == "lit":
            try:
                return float(v) if "." in v and not v.count("-") >= 2 else int(v)
            except ValueError:
                return v
        if v in ("true", "false"):
            return v == "true"
        if v == "null":
            return None
        name = v[len(prefix):] if prefix and v.startswith(prefix) else v
        return get_path(rec, name)

    def func(name):
        take()  # (
        args = []
        while peek()[0] != ")":
            t = take()
            if t[0] == ",":
                continue
            args.append(value(t))
        take()
        a = "" if args[0] is None else str(args[0]).lower()
        b = "" if len(args) < 2 or args[1] is None else str(args[1]).lower()
        return a.startswith(b) if name == "startswith" else b in a if name == "contains" else a.endswith(b)

    def atom():
        t = peek()
        if t[0] == "(":
            take()
            r = expr()
            take()
            return r
        if t == ("word", "not"):
            take()
            return not atom()
        t = take()
        if t[0] == "word" and t[1] in ("startswith", "contains", "endswith") and peek()[0] == "(":
            return func(t[1])
        if t[0] == "word" and re.match(r"^(\w+)/any\(", t[1] or ""):
            return True
        left = value(t)
        op = peek()
        if op[0] == "word" and op[1] in ("eq", "ne", "gt", "ge", "lt", "le"):
            take()
            return _cmp(left, op[1], value(take()))
        return bool(left)

    def conj():
        r = atom()
        while peek() == ("word", "and"):
            take()
            r = atom() and r
        return r

    def expr():
        r = conj()
        while peek() == ("word", "or"):
            take()
            r = conj() or r
        return r

    return expr()


# ---------------------------------------------------------------- A1 helpers and a worksheet grid

def col_num(letters):
    n = 0
    for ch in letters.upper():
        n = n * 26 + ord(ch) - 64
    return n


def col_name(n):
    s = ""
    while n > 0:
        n, r = divmod(n - 1, 26)
        s = chr(65 + r) + s
    return s


def parse_cell(ref):
    m = re.fullmatch(r"\$?([A-Za-z]{0,3})\$?(\d*)", ref.strip())
    if not m:
        raise ValueError("bad cell " + ref)
    return (int(m.group(2)) if m.group(2) else None, col_num(m.group(1)) if m.group(1) else None)


def parse_range(a1):
    """'A1:C3', 'B2', 'A:A', '3:3' -> (r0, c0, r1, c1); None for open ends."""
    parts = a1.split(":")
    r0, c0 = parse_cell(parts[0])
    r1, c1 = parse_cell(parts[1]) if len(parts) > 1 else (r0, c0)
    return r0, c0, r1, c1


def addr(r0, c0, r1, c1):
    a = col_name(c0) + str(r0)
    b = col_name(c1) + str(r1)
    return a if a == b else a + ":" + b


class Grid:
    """Cells of one sheet: {(row, col): {"v": value, "f": formula, "nf": number format, "fmt": {...}}}, rows and columns from 1."""

    def __init__(self):
        self.cells = {}
        self.merged = []

    def cell(self, r, c, create=False):
        if create:
            return self.cells.setdefault((r, c), {})
        return self.cells.get((r, c), {})

    def used(self, values_only=True):
        keys = [k for k, v in self.cells.items() if v.get("v") not in (None, "") or v.get("f") or (not values_only and v)]
        if not keys:
            return None
        return min(k[0] for k in keys), min(k[1] for k in keys), max(k[0] for k in keys), max(k[1] for k in keys)

    def bound(self, r0, c0, r1, c1):
        """Fills open ends (A:A, 3:3) from the used range."""
        u = self.used(False) or (1, 1, 1, 1)
        return (r0 or 1, c0 or 1, r1 or max(u[2], r0 or 1), c1 or max(u[3], c0 or 1))

    def value(self, r, c):
        cl = self.cells.get((r, c))
        if not cl:
            return ""
        if cl.get("f"):
            return self.evaluate(cl["f"])
        return cl.get("v", "")

    def evaluate(self, formula):
        f = formula[1:] if formula.startswith("=") else formula

        def rng(m):
            r0, c0, r1, c1 = parse_range(m.group(1))
            vals = [self.value(r, c) for r in range(r0, r1 + 1) for c in range(c0, c1 + 1)]
            return repr([v for v in vals if isinstance(v, (int, float)) and not isinstance(v, bool)])

        f = re.sub(r"(?i)\b(SUM|AVERAGE|MIN|MAX|COUNT)\(", lambda m: m.group(1).lower() + "_(", f)
        f = re.sub(r"\b([A-Z]{1,3}\d+:[A-Z]{1,3}\d+)\b", rng, f)

        def ref(m):
            v = self.value(*parse_cell(m.group(0)))
            return repr(v if isinstance(v, (int, float)) else (0 if v in ("", None) else v))

        f = re.sub(r"\b[A-Z]{1,3}\d+\b", ref, f)
        env = {"sum_": lambda l: sum(l), "average_": lambda l: sum(l) / len(l) if l else 0, "min_": lambda l: min(l) if l else 0,
               "max_": lambda l: max(l) if l else 0, "count_": lambda l: len(l), "__builtins__": {}}
        try:
            v = eval(f, env)  # noqa: S307 - formulas typed by the unit tests only
        except Exception:
            return "#VALUE!"
        if isinstance(v, float) and v.is_integer():
            v = int(v)
        return v

    def text(self, r, c):
        v = self.value(r, c)
        nf = self.cells.get((r, c), {}).get("nf") or "General"
        if isinstance(v, bool):
            return "TRUE" if v else "FALSE"
        if isinstance(v, (int, float)) and nf not in ("General", "@"):
            m = re.search(r"0\.(0+)", nf)
            dec = len(m.group(1)) if m else 0
            s = ("{:,.%df}" if "," in nf else "{:.%df}") % dec
            out = s.format(v * (100 if nf.endswith("%") else 1))
            return out + ("%" if nf.endswith("%") else "")
        if isinstance(v, float) and v.is_integer():
            return str(int(v))
        return "" if v is None else str(v)

    def set(self, r, c, v):
        cl = self.cell(r, c, True)
        if isinstance(v, str) and v.startswith("=") and len(v) > 1:
            cl["f"] = v
            cl["v"] = None
        else:
            cl.pop("f", None)
            if isinstance(v, str):
                try:
                    v = int(v) if re.fullmatch(r"-?\d+", v) else float(v) if re.fullmatch(r"-?\d+\.\d+", v) else v
                except ValueError:
                    pass
            cl["v"] = v

    def clear(self, r0, c0, r1, c1, what="All"):
        for r in range(r0, r1 + 1):
            for c in range(c0, c1 + 1):
                cl = self.cells.get((r, c))
                if not cl:
                    continue
                if what in ("All", "Contents"):
                    cl.pop("v", None)
                    cl.pop("f", None)
                if what in ("All", "Formats"):
                    cl.pop("fmt", None)
                    cl.pop("nf", None)
                if not cl:
                    del self.cells[(r, c)]

    def shift(self, r0, c0, r1, c1, how):
        """insert: Down/Right; delete: Up/Left."""
        h, w = r1 - r0 + 1, c1 - c0 + 1
        new = {}
        for (r, c), v in self.cells.items():
            if how == "Down" and c0 <= c <= c1 and r >= r0:
                new[(r + h, c)] = v
            elif how == "Right" and r0 <= r <= r1 and c >= c0:
                new[(r, c + w)] = v
            elif how == "Up" and c0 <= c <= c1 and r >= r0:
                if r > r1:
                    new[(r - h, c)] = v
            elif how == "Left" and r0 <= r <= r1 and c >= c0:
                if c > c1:
                    new[(r, c - w)] = v
            else:
                new[(r, c)] = v
        self.cells = new

    def matrix(self, r0, c0, r1, c1, kind="v"):
        out = []
        for r in range(r0, r1 + 1):
            row = []
            for c in range(c0, c1 + 1):
                cl = self.cells.get((r, c), {})
                if kind == "v":
                    row.append(self.value(r, c))
                elif kind == "t":
                    row.append(self.text(r, c))
                elif kind == "f":
                    row.append(cl.get("f") or cl.get("v", ""))
                else:
                    row.append(cl.get("nf", "General"))
            out.append(row)
        return out
