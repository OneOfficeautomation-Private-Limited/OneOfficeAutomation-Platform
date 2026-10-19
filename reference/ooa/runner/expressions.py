"""OOA Flow expression language. A small, sandboxed grammar: no eval, no
attribute access on Python objects, no method calls, whitelisted functions only.

  $invoice.total > 1000 and contains(lower($vendor), 'acme')
  dateAdd(now(), 30, 'days')
  $rows[0]['Amount'] * 1.2

The Java Runner implements the same grammar with ANTLR 4; the conformance suite
keeps both in step."""
from __future__ import annotations

import datetime as _dt
import json
import re
from decimal import ROUND_HALF_UP, Decimal, DivisionByZero, InvalidOperation
from typing import Any, Callable

from ..sdk.errors import ExpressionError, VariableError
from ..sdk.types import SecureString, coerce, to_jsonable, to_number

_TOKEN = re.compile(r"""
    (?P<ws>\s+)
  | (?P<num>\d+(?:\.\d+)?)
  | (?P<str>'(?:[^'\\]|\\.)*'|"(?:[^"\\]|\\.)*")
  | (?P<var>\$[A-Za-z_][A-Za-z0-9_]*)
  | (?P<ident>[A-Za-z_][A-Za-z0-9_]*)
  | (?P<op>==|!=|<=|>=|&&|\|\||[-+*/%<>()\[\],.!])
""", re.VERBOSE)

_BIN_PREC = {"or": 1, "||": 1, "and": 2, "&&": 2, "==": 3, "!=": 3, "<": 4, "<=": 4, ">": 4, ">=": 4,
             "+": 5, "-": 5, "*": 6, "/": 6, "%": 6}


def tokenize(src: str) -> list[tuple[str, str]]:
    pos, out = 0, []
    while pos < len(src):
        m = _TOKEN.match(src, pos)
        if not m:
            raise ExpressionError(f"Unexpected character '{src[pos]}' at position {pos} in: {src}",
                                  suggested_fix="Check quotes and operators in the expression.")
        pos = m.end()
        kind = m.lastgroup
        if kind == "ws":
            continue
        text = m.group(kind)
        if kind == "ident" and text in ("and", "or", "not"):
            kind = "op"
        out.append((kind, text))
    out.append(("eof", ""))
    return out


class _Parser:
    def __init__(self, src: str):
        self.src = src
        self.toks = tokenize(src)
        self.i = 0

    def peek(self):
        return self.toks[self.i]

    def next(self):
        t = self.toks[self.i]
        self.i += 1
        return t

    def expect(self, text: str):
        t = self.next()
        if t[1] != text:
            raise ExpressionError(f"Expected '{text}' but found '{t[1] or 'end'}' in: {self.src}")
        return t

    def parse(self):
        node = self.expr(0)
        if self.peek()[0] != "eof":
            raise ExpressionError(f"Unexpected '{self.peek()[1]}' in: {self.src}")
        return node

    def expr(self, min_prec: int):
        left = self.unary()
        while True:
            kind, text = self.peek()
            prec = _BIN_PREC.get(text) if kind == "op" else None
            if prec is None or prec < min_prec:
                break
            self.next()
            right = self.expr(prec + 1)
            left = ("bin", text, left, right)
        return left

    def unary(self):
        kind, text = self.peek()
        if kind == "op" and text in ("not", "!", "-"):
            self.next()
            return ("unary", text, self.unary())
        return self.postfix(self.primary())

    def primary(self):
        kind, text = self.next()
        if kind == "num":
            return ("lit", Decimal(text))
        if kind == "str":
            return ("lit", bytes(text[1:-1], "utf-8").decode("unicode_escape"))
        if kind == "var":
            return ("var", text[1:])
        if kind == "ident":
            if text in ("true", "false"):
                return ("lit", text == "true")
            if text == "null":
                return ("lit", None)
            self.expect("(")
            args = []
            if self.peek()[1] != ")":
                args.append(self.expr(0))
                while self.peek()[1] == ",":
                    self.next()
                    args.append(self.expr(0))
            self.expect(")")
            if text not in FUNCTIONS:
                raise ExpressionError(f"Unknown function '{text}'",
                                      suggested_fix=f"Available: {', '.join(sorted(FUNCTIONS))}.")
            return ("call", text, args)
        if text == "(":
            node = self.expr(0)
            self.expect(")")
            return node
        if text == "[":
            items = []
            if self.peek()[1] != "]":
                items.append(self.expr(0))
                while self.peek()[1] == ",":
                    self.next()
                    items.append(self.expr(0))
            self.expect("]")
            return ("list", items)
        raise ExpressionError(f"Unexpected '{text or 'end of expression'}' in: {self.src}")

    def postfix(self, node):
        while True:
            t = self.peek()[1]
            if t == ".":
                self.next()
                kind, name = self.next()
                if kind not in ("ident", "num"):
                    raise ExpressionError(f"Expected field name after '.' in: {self.src}")
                node = ("get", node, ("lit", name))
            elif t == "[":
                self.next()
                idx = self.expr(0)
                self.expect("]")
                node = ("get", node, idx)
            else:
                return node


_CACHE: dict[str, Any] = {}


def parse(src: str):
    node = _CACHE.get(src)
    if node is None:
        node = _Parser(src).parse()
        if len(_CACHE) < 5000:
            _CACHE[src] = node
    return node


def _guard(v: Any, where: str) -> Any:
    if isinstance(v, SecureString):
        raise VariableError(f"A Credential value cannot be used in {where}", code="VAR.SECURE_LEAK",
                            suggested_fix="Pass the Credential variable directly to an action input that accepts credentials.")
    return v


def _num(v):
    return to_number(_guard(v, "arithmetic"))


def _cmp_pair(a, b):
    if isinstance(a, (Decimal, int)) and not isinstance(a, bool) and isinstance(b, str):
        b = to_number(b)
    if isinstance(b, (Decimal, int)) and not isinstance(b, bool) and isinstance(a, str):
        a = to_number(a)
    return a, b


def evaluate_node(node, lookup: Callable[[str], Any]):
    t = node[0]
    if t == "lit":
        return node[1]
    if t == "var":
        return lookup(node[1])
    if t == "list":
        return [evaluate_node(n, lookup) for n in node[1]]
    if t == "get":
        base = evaluate_node(node[1], lookup)
        key = evaluate_node(node[2], lookup)
        _guard(base, "field access")
        if isinstance(base, dict):
            if isinstance(key, Decimal):
                key = str(int(key))
            if "columns" in base and "rows" in base and key == "rows":
                return base["rows"]
            return base.get(key)
        if isinstance(base, (list, str)):
            try:
                return base[int(to_number(key))]
            except (IndexError, VariableError):
                return None
        if base is None:
            return None
        raise ExpressionError(f"Cannot read field '{key}' of a {type(base).__name__}")
    if t == "unary":
        v = evaluate_node(node[2], lookup)
        if node[1] in ("not", "!"):
            return not truthy(v)
        return -_num(v)
    if t == "bin":
        op = node[1]
        if op in ("and", "&&"):
            return truthy(evaluate_node(node[2], lookup)) and truthy(evaluate_node(node[3], lookup))
        if op in ("or", "||"):
            return truthy(evaluate_node(node[2], lookup)) or truthy(evaluate_node(node[3], lookup))
        a = evaluate_node(node[2], lookup)
        b = evaluate_node(node[3], lookup)
        return binary(op, a, b)
    if t == "call":
        fn = FUNCTIONS[node[1]]
        args = [evaluate_node(n, lookup) for n in node[2]]
        if node[1] not in ("coalesce", "isEmpty", "if"):
            for a in args:
                _guard(a, f"function {node[1]}()")
        try:
            return fn(*args)
        except (ExpressionError, VariableError):
            raise
        except TypeError as e:
            raise ExpressionError(f"Wrong arguments for {node[1]}(): {e}") from None
        except (ValueError, InvalidOperation) as e:
            raise ExpressionError(f"{node[1]}() failed: {e}") from None
    raise ExpressionError(f"Bad expression node {t}")


def binary(op: str, a, b):
    _guard(a, f"operator {op}")
    _guard(b, f"operator {op}")
    if op == "+":
        if isinstance(a, str) or isinstance(b, str):
            return f"{_s(a)}{_s(b)}"
        if isinstance(a, list) and isinstance(b, list):
            return a + b
        return _num(a) + _num(b)
    if op in ("-", "*", "/", "%"):
        x, y = _num(a), _num(b)
        try:
            return {"-": x - y, "*": x * y, "/": x / y if y else _div0(), "%": x % y if y else _div0()}[op]
        except (DivisionByZero, InvalidOperation):
            _div0()
    if op in ("==", "!="):
        x, y = _cmp_pair(a, b)
        eq = x == y
        return eq if op == "==" else not eq
    x, y = _cmp_pair(a, b)
    try:
        return {"<": x < y, "<=": x <= y, ">": x > y, ">=": x >= y}[op]
    except TypeError:
        raise ExpressionError(f"Cannot compare {type(a).__name__} with {type(b).__name__} using {op}") from None


def _div0():
    raise ExpressionError("Division by zero", code="EXPR.DIV_ZERO", suggested_fix="Check the divisor before dividing.")


def truthy(v) -> bool:
    if isinstance(v, SecureString):
        return bool(v.reveal())
    if isinstance(v, Decimal):
        return v != 0
    return bool(v)


def _s(v) -> str:
    return coerce(v, "String") if v is not None else ""


# ---------- whitelisted functions ----------
_UNITS = {"seconds": 1, "minutes": 60, "hours": 3600, "days": 86400, "weeks": 604800}


def _date_add(dt, n, unit="days"):
    dt = coerce(dt, "DateTime")
    n = to_number(n)
    if unit in ("months", "years"):
        months = int(n) * (12 if unit == "years" else 1)
        y, m = divmod(dt.month - 1 + months, 12)
        import calendar
        day = min(dt.day, calendar.monthrange(dt.year + y, m + 1)[1])
        return dt.replace(year=dt.year + y, month=m + 1, day=day)
    if unit not in _UNITS:
        raise ExpressionError(f"Unknown unit '{unit}'", suggested_fix="Use seconds, minutes, hours, days, weeks, months or years.")
    return dt + _dt.timedelta(seconds=float(n) * _UNITS[unit])


def _date_diff(a, b, unit="days"):
    secs = (coerce(b, "DateTime") - coerce(a, "DateTime")).total_seconds()
    if unit not in _UNITS:
        raise ExpressionError(f"Unknown unit '{unit}'")
    return (Decimal(repr(secs)) / _UNITS[unit]).quantize(Decimal("0.000001")).normalize()


def _round(n, digits=0):
    q = Decimal(1).scaleb(-int(to_number(digits)))
    return to_number(n).quantize(q, rounding=ROUND_HALF_UP)


def _len(v):
    if v is None:
        return Decimal(0)
    if isinstance(v, dict) and "rows" in v and "columns" in v:
        return Decimal(len(v["rows"]))
    return Decimal(len(v))


def _regex_extract(s, pattern, group=0):
    m = re.search(pattern, _s(s))
    return m.group(int(to_number(group))) if m else None


FUNCTIONS: dict[str, Callable] = {
    "upper": lambda s: _s(s).upper(),
    "lower": lambda s: _s(s).lower(),
    "trim": lambda s: _s(s).strip(),
    "len": _len,
    "concat": lambda *a: "".join(_s(x) for x in a),
    "contains": lambda h, n: (n in h) if isinstance(h, (list, dict)) else (_s(n) in _s(h)),
    "startsWith": lambda s, p: _s(s).startswith(_s(p)),
    "endsWith": lambda s, p: _s(s).endswith(_s(p)),
    "replace": lambda s, old, new: _s(s).replace(_s(old), _s(new)),
    "substring": lambda s, start, length=None: _s(s)[int(start): (int(start) + int(length)) if length is not None else None],
    "split": lambda s, sep: _s(s).split(_s(sep)),
    "join": lambda lst, sep="": _s(sep).join(_s(x) for x in (lst or [])),
    "toNumber": to_number,
    "toString": _s,
    "toBoolean": lambda v: coerce(v, "Boolean"),
    "round": _round,
    "abs": lambda n: abs(to_number(n)),
    "min": lambda *a: min(to_number(x) for x in (a[0] if len(a) == 1 and isinstance(a[0], list) else a)),
    "max": lambda *a: max(to_number(x) for x in (a[0] if len(a) == 1 and isinstance(a[0], list) else a)),
    "sum": lambda lst: sum((to_number(x) for x in lst), Decimal(0)),
    "now": lambda: _dt.datetime.now(_dt.timezone.utc).replace(microsecond=0),
    "today": lambda: _dt.datetime.combine(_dt.date.today(), _dt.time()),
    "dateAdd": _date_add,
    "dateDiff": _date_diff,
    "formatDate": lambda dt, fmt="%Y-%m-%d": coerce(dt, "DateTime").strftime(fmt),
    "parseDate": lambda s, fmt=None: _dt.datetime.strptime(_s(s), fmt) if fmt else coerce(s, "DateTime"),
    "coalesce": lambda *a: next((x for x in a if x not in (None, "")), None),
    "isEmpty": lambda v: v is None or v == "" or v == [] or v == {},
    "keys": lambda d: list((d or {}).keys()),
    "values": lambda d: list((d or {}).values()),
    "get": lambda d, k, default=None: (d or {}).get(k, default),
    "regexMatch": lambda s, p: re.search(p, _s(s)) is not None,
    "regexExtract": _regex_extract,
    "jsonParse": lambda s: json.loads(_s(s), parse_float=Decimal, parse_int=Decimal),
    "jsonStringify": lambda v: json.dumps(to_jsonable(v)),
    "if": lambda c, a, b: a if truthy(c) else b,
}


def evaluate(src: str, lookup: Callable[[str], Any]):
    return evaluate_node(parse(src), lookup)


_REF = re.compile(r"^\$[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+|\[[^\]]+\])*$")
_INTERP = re.compile(r"\$\{([^}]*)\}")
_BARE = re.compile(r"^\s*[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+|\[[^\]]+\])*\s*$")


def _interp_src(inner: str) -> str:
    """Inside ${...} a bare path means a variable: ${name} == ${$name}."""
    if _BARE.match(inner) and inner.strip() not in ("true", "false", "null"):
        return "$" + inner.strip()
    return inner


def resolve(value: Any, lookup: Callable[[str], Any]) -> Any:
    """Resolve a step argument:
       "$a.b[0]"           -> the referenced value (keeps its type)
       "Hello ${name}"     -> interpolated string
       {"expr": "1 + 2"}   -> full expression
       {"literal": "$x"}   -> the literal string "$x" (escape hatch)
       dicts / lists       -> resolved recursively"""
    if isinstance(value, str):
        if _REF.match(value):
            return evaluate(value, lookup)
        if "${" in value:
            def rep(m):
                v = evaluate(_interp_src(m.group(1)), lookup)
                _guard(v, "text interpolation")
                return _s(v)
            return _INTERP.sub(rep, value)
        return value
    if isinstance(value, dict):
        if set(value) == {"expr"}:
            return evaluate(value["expr"], lookup)
        if set(value) == {"literal"}:
            return value["literal"]
        return {k: resolve(v, lookup) for k, v in value.items()}
    if isinstance(value, list):
        return [resolve(v, lookup) for v in value]
    return value


def references(value: Any) -> set[str]:
    """Variable names referenced by an argument (used by Flow validation)."""
    found: set[str] = set()

    def walk(v):
        if isinstance(v, str):
            srcs = [v] if _REF.match(v) else [_interp_src(x) for x in _INTERP.findall(v)]
            for s in srcs:
                parse(s)  # surfaces syntax errors at validation time
                for kind, text in tokenize(s):
                    if kind == "var":
                        found.add(text[1:])
        elif isinstance(v, dict):
            if set(v) == {"literal"}:
                return
            if set(v) == {"expr"}:
                parse(v["expr"])
                for kind, text in tokenize(v["expr"]):
                    if kind == "var":
                        found.add(text[1:])
                return
            for x in v.values():
                walk(x)
        elif isinstance(v, list):
            for x in v:
                walk(x)
    walk(value)
    return found
