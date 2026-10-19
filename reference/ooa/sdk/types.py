"""OOA data types and coercion rules. Number is decimal, never float, so money
arithmetic is exact. Credential values are SecureString: their repr/str/JSON
form is always '****' (NFR-007: secrets never shown in UI, logs or exports)."""
from __future__ import annotations

import datetime as _dt
import json
from decimal import Decimal, InvalidOperation
from enum import Enum
from pathlib import Path
from typing import Any

from .errors import VariableError

MASK = "****"


class OoaType(str, Enum):
    STRING = "String"
    NUMBER = "Number"
    BOOLEAN = "Boolean"
    DATETIME = "DateTime"
    LIST = "List"
    DICTIONARY = "Dictionary"
    TABLE = "Table"
    RECORD = "Record"
    FILE = "File"
    WINDOW = "Window"
    UIELEMENT = "UIElement"
    CREDENTIAL = "Credential"
    ANY = "Any"


class SecureString:
    """Holds a secret. Only `reveal()` returns the value; packages call it at the
    last moment (e.g. when handing a password to a login API)."""
    __slots__ = ("_value", "ref")

    def __init__(self, value: str, ref: str | None = None):
        self._value = value
        self.ref = ref

    def reveal(self) -> str:
        return self._value

    def __repr__(self) -> str:
        return MASK

    __str__ = __repr__

    def __eq__(self, other: object) -> bool:
        return isinstance(other, SecureString) and other._value == self._value

    def __hash__(self) -> int:
        return hash(("secure", self.ref))


# Table = {"columns": [...], "rows": [[...], ...]}; Record = dict of column -> value.
def make_table(columns: list[str], rows: list[list[Any]]) -> dict:
    return {"columns": list(columns), "rows": [list(r) for r in rows]}


def to_number(value: Any) -> Decimal:
    if isinstance(value, bool):
        return Decimal(int(value))
    if isinstance(value, Decimal):
        return value
    if isinstance(value, int):
        return Decimal(value)
    if isinstance(value, float):
        return Decimal(repr(value))
    if isinstance(value, str):
        try:
            return Decimal(value.strip().replace(",", ""))
        except InvalidOperation:
            pass
    raise VariableError(f"Cannot convert {type(value).__name__} value {safe_repr(value)!s} to Number",
                        suggested_fix="Check the value is numeric, e.g. 42 or 19.99.")


def coerce(value: Any, type_: str, name: str = "value") -> Any:
    """Coerce a value to an OOA type, raising VAR.TYPE_MISMATCH with a fix hint."""
    t = OoaType(type_)
    if value is None or t is OoaType.ANY:
        return value
    try:
        if t is OoaType.STRING:
            if isinstance(value, SecureString):
                raise VariableError(f"Credential cannot be assigned to String variable '{name}'",
                                    code="VAR.SECURE_LEAK",
                                    suggested_fix="Keep secrets in Credential variables.")
            if isinstance(value, Decimal):
                return format(value.normalize(), "f") if value == value.to_integral() else str(value)
            if isinstance(value, (dict, list)):
                return json.dumps(value, default=str)
            return str(value)
        if t is OoaType.NUMBER:
            return to_number(value)
        if t is OoaType.BOOLEAN:
            if isinstance(value, bool):
                return value
            if isinstance(value, str) and value.strip().lower() in ("true", "false", "yes", "no", "1", "0"):
                return value.strip().lower() in ("true", "yes", "1")
            if isinstance(value, (int, Decimal)):
                return value != 0
            raise ValueError
        if t is OoaType.DATETIME:
            if isinstance(value, _dt.datetime):
                return value
            if isinstance(value, _dt.date):
                return _dt.datetime(value.year, value.month, value.day)
            if isinstance(value, str):
                return _dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
            raise ValueError
        if t is OoaType.LIST:
            if isinstance(value, (list, tuple)):
                return list(value)
            raise ValueError
        if t in (OoaType.DICTIONARY, OoaType.RECORD):
            if isinstance(value, dict):
                return value
            raise ValueError
        if t is OoaType.TABLE:
            if isinstance(value, dict) and "columns" in value and "rows" in value:
                return value
            if isinstance(value, list) and all(isinstance(r, dict) for r in value):
                cols = list(dict.fromkeys(k for r in value for k in r))
                return make_table(cols, [[r.get(c) for c in cols] for r in value])
            raise ValueError
        if t is OoaType.FILE:
            return str(Path(value)) if isinstance(value, (str, Path)) else value
        if t is OoaType.CREDENTIAL:
            if isinstance(value, SecureString):
                return value
            raise VariableError(f"Variable '{name}' needs a Credential reference, not a plain value",
                                code="VAR.SECURE_REQUIRED",
                                suggested_fix="Pass a vault reference like {\"$credential\": \"locker/name/attribute\"}.")
        return value
    except VariableError:
        raise
    except (ValueError, TypeError):
        raise VariableError(f"Variable '{name}' expects {t.value}, got {type(value).__name__}",
                            code="VAR.TYPE_MISMATCH",
                            suggested_fix=f"Provide a {t.value} value for '{name}'.") from None


def json_schema_for(type_: str) -> dict:
    """JSON Schema used for tool inputs (MCP/agents) and manifests."""
    return {
        "String": {"type": "string"},
        "Number": {"type": "number"},
        "Boolean": {"type": "boolean"},
        "DateTime": {"type": "string", "format": "date-time"},
        "List": {"type": "array"},
        "Dictionary": {"type": "object"},
        "Record": {"type": "object"},
        "Table": {"type": "object", "properties": {"columns": {"type": "array"}, "rows": {"type": "array"}}},
        "File": {"type": "string", "description": "File path"},
        "Credential": {"type": "string", "description": "Vault reference; value is never sent to models"},
    }.get(type_, {})


def to_jsonable(value: Any) -> Any:
    """Serialise run values for events, outputs and tool results. Secrets stay masked."""
    if isinstance(value, SecureString):
        return MASK
    if isinstance(value, Decimal):
        return int(value) if value == value.to_integral() and abs(value) < 10**15 else float(value)
    if isinstance(value, (_dt.datetime, _dt.date)):
        return value.isoformat()
    if isinstance(value, dict):
        return {str(k): to_jsonable(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [to_jsonable(v) for v in value]
    if isinstance(value, Path):
        return str(value)
    if isinstance(value, (str, int, float, bool)) or value is None:
        return value
    return f"<{type(value).__name__}>"


def safe_repr(value: Any, limit: int = 200) -> str:
    s = json.dumps(to_jsonable(value), default=str)
    return s if len(s) <= limit else s[: limit - 3] + "..."
