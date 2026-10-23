"""Which error codes a Catch entry stands for (parity with the Java runner's ErrorCodes).

An entry is an exact code (FILE.NOT_FOUND), a prefix with a star (BROWSER.*), or a kind name that cuts across
toolkits (NOT_FOUND, TIMEOUT ...), or an older code that was later split up (SAP.ERROR).
"""
from __future__ import annotations


def _tail(code: str) -> str:
    return code.split(".", 1)[1] if "." in code else code


_RULES = {
    "NOT_FOUND": lambda c: _tail(c).endswith("NOT_FOUND"),
    "FILE_NOT_FOUND": lambda c: c in ("FILE.NOT_FOUND", "FOLDER.NOT_FOUND") or _tail(c) == "FILE_NOT_FOUND",
    "WINDOW_NOT_FOUND": lambda c: c == "WINDOW.NOT_FOUND" or _tail(c) == "WINDOW_NOT_FOUND",
    "OBJECT_NOT_FOUND": lambda c: _tail(c) in ("OBJECT_NOT_FOUND", "ELEMENT_NOT_FOUND", "FIELD_NOT_FOUND") or c == "IMAGE.NOT_FOUND",
    "TIMEOUT": lambda c: _tail(c).endswith("TIMEOUT") and not _tail(c).startswith("BAD_"),
    "AUTH_FAILED": lambda c: _tail(c) in ("AUTH_FAILED", "UNAUTHORIZED", "FORBIDDEN", "LOGON_FAILED"),
    "RATE_LIMITED": lambda c: _tail(c) == "RATE_LIMITED",
    "SAP.ERROR": lambda c: c.startswith("SAP.") and not _tail(c).startswith("BAD_"),
}


def matches(entry: str, code: str) -> bool:
    e = str(entry).strip()
    if e == code or e == "*":
        return True
    if e.endswith("*"):
        return code.startswith(e[:-1])
    rule = _RULES.get(e.upper())
    return bool(rule and rule(code))


def any_matches(entries, code: str) -> bool:
    return not entries or any(matches(x, code) for x in entries)
