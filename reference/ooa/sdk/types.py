"""OOA data types and coercion rules. Number is decimal, never float, so money
arithmetic is exact. Credential values are SecureString: their repr/str/JSON
form is always '****' (NFR-007: secrets never shown in UI, logs or exports)."""
from __future__ import annotations

import base64
import binascii
import datetime as _dt
import json
import re
import struct
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
    TIME = "Time"           # datetime.time: a time of day, no date or zone
    TIMESPAN = "TimeSpan"   # datetime.timedelta: a length of time, may be negative
    IMAGE = "Image"         # ImageValue: PNG bytes with width and height
    BINARY = "Binary"       # BinaryValue: bytes in memory


class BinaryValue:
    """Bytes held in memory with a media type. JSON form {"$binary": Base64, "mime": ...}; text form a summary."""
    __slots__ = ("data", "mime")

    def __init__(self, data: bytes, mime: str | None = None):
        self.data = bytes(data or b"")
        self.mime = (mime or "").strip() or "application/octet-stream"

    def base64(self) -> str:
        return base64.b64encode(self.data).decode("ascii")

    def to_json(self) -> dict:
        return {"$binary": self.base64(), "mime": self.mime}

    def __len__(self) -> int:
        return len(self.data)

    def __eq__(self, other: object) -> bool:
        return isinstance(other, BinaryValue) and other.data == self.data and other.mime == self.mime

    def __hash__(self) -> int:
        return hash((self.data, self.mime))

    def __str__(self) -> str:
        return "Binary " + size_text(len(self.data))

    __repr__ = __str__


class ImageValue:
    """A picture as PNG bytes with its width and height. The reference reads PNG only (the Java runner also converts
    JPEG, BMP and GIF to PNG)."""
    __slots__ = ("data", "width", "height")

    def __init__(self, data: bytes):
        data = bytes(data or b"")
        if len(data) < 24 or data[:8] != b"\x89PNG\r\n\x1a\n" or data[12:16] != b"IHDR":
            raise ValueError("not a PNG picture")
        self.data = data
        self.width, self.height = struct.unpack(">II", data[16:24])

    mime = "image/png"

    def base64(self) -> str:
        return base64.b64encode(self.data).decode("ascii")

    def to_json(self) -> dict:
        return {"$binary": self.base64(), "mime": "image/png", "width": self.width, "height": self.height}

    def __len__(self) -> int:
        return len(self.data)

    def __eq__(self, other: object) -> bool:
        return isinstance(other, ImageValue) and other.data == self.data

    def __hash__(self) -> int:
        return hash(self.data)

    def __str__(self) -> str:
        return f"Image {self.width}x{self.height}"

    __repr__ = __str__


def size_text(n: int) -> str:
    if n < 1024:
        return f"{n} byte" if n == 1 else f"{n} bytes"
    if n < 1024 * 1024:
        return f"{n / 1024:.1f} KB"
    return f"{n / (1024 * 1024):.1f} MB"


# ---------- Time and TimeSpan ----------
_CLOCK = re.compile(r"^(\d{1,2}):(\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?$")
_AM_PM = re.compile(r"^(\d{1,2})(?::(\d{2}))?(?::(\d{2}))?\s*([ap])\.?\s*m\.?$", re.I)
_SPAN_CLOCK = re.compile(r"^(?:(\d+)\.)?(\d+):(\d{1,2})(?::(\d{1,2})(?:\.(\d{1,9}))?)?$")
_SPAN_ISO = re.compile(r"^p(?:(\d+(?:\.\d+)?)d)?(?:t(?:(\d+(?:\.\d+)?)h)?(?:(\d+(?:\.\d+)?)m)?(?:(\d+(?:\.\d+)?)s)?)?$")
_SPAN_PART = re.compile(r"(\d+(?:\.\d+)?)\s*([a-z]+)")
_SPAN_UNITS = {**dict.fromkeys(("w", "wk", "wks", "week", "weeks"), 604800), **dict.fromkeys(("d", "day", "days"), 86400),
               **dict.fromkeys(("h", "hr", "hrs", "hour", "hours"), 3600), **dict.fromkeys(("m", "min", "mins", "minute", "minutes"), 60),
               **dict.fromkeys(("s", "sec", "secs", "second", "seconds"), 1), **dict.fromkeys(("ms", "millisecond", "milliseconds"), Decimal("0.001"))}


def _mismatch(value: Any, type_: str, fix: str) -> VariableError:
    return VariableError(f"Cannot read {safe_repr(value)} as a {type_}", code="VAR.TYPE_MISMATCH", suggested_fix=fix)


def format_time(t: _dt.time) -> str:
    return t.isoformat()


def format_timespan(d: _dt.timedelta) -> str:
    """ISO 8601 as the Java runner writes it: PT2H15M, PT0S, PT1.5S, -PT30M (hours are not folded into days)."""
    if d < _dt.timedelta(0):
        return "-" + format_timespan(-d)
    total = Decimal(d.days * 86400 + d.seconds) + Decimal(d.microseconds) / Decimal(1_000_000)
    if total == 0:
        return "PT0S"
    h, rest = divmod(total, 3600)
    m, s = divmod(rest, 60)
    out = "PT" + (f"{int(h)}H" if h else "") + (f"{int(m)}M" if m else "")
    if s:
        out += format(s.normalize(), "f") + "S"
    return out


def to_time(value: Any) -> _dt.time:
    if isinstance(value, _dt.datetime):
        return value.time().replace(tzinfo=None)
    if isinstance(value, _dt.time):
        return value
    if isinstance(value, str):
        x = value.strip()
        try:
            m = _CLOCK.match(x)
            if m:
                micro = int((m.group(4) or "").ljust(6, "0")[:6] or 0)
                return _dt.time(int(m.group(1)), int(m.group(2)), int(m.group(3) or 0), micro)
            m = _AM_PM.match(x)
            if m:
                h = int(m.group(1))
                if not 1 <= h <= 12:
                    raise ValueError
                h = h % 12 + (12 if m.group(4).lower() == "p" else 0)
                return _dt.time(h, int(m.group(2) or 0), int(m.group(3) or 0))
            if len(x) > 10:
                return coerce(x, "DateTime").time().replace(tzinfo=None)
        except (ValueError, VariableError):
            pass
    raise _mismatch(value, "Time", "Use a time of day such as 14:30, 14:30:05 or 2:30 PM.")


def to_timespan(value: Any) -> _dt.timedelta:
    if isinstance(value, _dt.timedelta):
        return value
    try:
        if isinstance(value, (int, float, Decimal)) and not isinstance(value, bool):
            return _dt.timedelta(seconds=float(value))
        if isinstance(value, str):
            x = value.strip().lower()
            neg = x.startswith("-")
            if neg or x.startswith("+"):
                x = x[1:].strip()
            secs: Decimal | None = None
            m = _SPAN_ISO.match(x)
            if x.startswith("p") and m and x not in ("p", "pt"):
                d, h, mi, s = (Decimal(g) if g else Decimal(0) for g in m.groups())
                secs = d * 86400 + h * 3600 + mi * 60 + s
            elif re.fullmatch(r"\d+(\.\d+)?", x):
                secs = Decimal(x)
            else:
                m = _SPAN_CLOCK.match(x)
                if m:
                    mins, ss = int(m.group(3)), int(m.group(4) or 0)
                    if mins < 60 and ss < 60:
                        secs = (Decimal(int(m.group(1) or 0) * 86400 + int(m.group(2)) * 3600 + mins * 60 + ss)
                                + Decimal("0." + (m.group(5) or "0")))
                else:
                    rest = re.sub(r"\band\b", " ", x.replace(",", " "))
                    total, at, ok = Decimal(0), 0, False
                    for p in _SPAN_PART.finditer(rest):
                        unit = _SPAN_UNITS.get(p.group(2))
                        if rest[at:p.start()].strip() or unit is None:
                            ok = False
                            break
                        total += Decimal(p.group(1)) * unit
                        at, ok = p.end(), True
                    if ok and not rest[at:].strip():
                        secs = total
            if secs is not None:
                d = _dt.timedelta(seconds=float(secs))
                return -d if neg else d
    except (ValueError, OverflowError, InvalidOperation):
        pass
    raise _mismatch(value, "TimeSpan", "Use a length of time such as 2h 15m, 1d 3h, 01:30:00, PT2H15M or a number of seconds.")


def total_seconds(d: _dt.timedelta) -> Decimal:
    return (Decimal(d.days * 86400 + d.seconds) + Decimal(d.microseconds) / Decimal(1_000_000)).normalize()


# ---------- Image and Binary ----------
_DATA_URI = re.compile(r"^data:([^;,]*)(?:;[^,]*)?;base64,(.*)$", re.S)


def b64decode_strict(s: str) -> bytes | None:
    x = re.sub(r"\s+", "", s)
    if not x or len(x) % 4 or not re.fullmatch(r"[A-Za-z0-9+/]*={0,2}", x):
        return None
    try:
        return base64.b64decode(x, validate=True)
    except (ValueError, binascii.Error):
        return None


def _bytes_of(value: Any, type_: str, base_dir: Path | None = None) -> tuple[bytes, str | None]:
    fix = 'Give a file path, Base64 text, a data: URI or {"$binary": "<Base64>", "mime": "..."}.'
    if isinstance(value, dict) and isinstance(value.get("$binary"), str):
        b = b64decode_strict(value["$binary"])
        if b is None:
            raise VariableError("The $binary text is not valid Base64", code="VAR.TYPE_MISMATCH", suggested_fix=fix)
        return b, value.get("mime") if isinstance(value.get("mime"), str) else None
    if isinstance(value, (bytes, bytearray)):
        return bytes(value), None
    if isinstance(value, (str, Path)):
        x = str(value).strip()
        m = _DATA_URI.match(x)
        if m and b64decode_strict(m.group(2)) is not None:
            return b64decode_strict(m.group(2)), m.group(1) or None
        p = (base_dir / x) if base_dir else Path(x)
        if x and p.is_file():
            return p.read_bytes(), None
        b = b64decode_strict(x) if x else None
        if b is not None:
            return b, None
        raise VariableError(f"Cannot read '{x[:300]}' as {type_}: it is not an existing file nor Base64 text",
                            code="VAR.TYPE_MISMATCH", suggested_fix=fix)
    raise VariableError(f"Cannot read a {type(value).__name__} value as {type_}", code="VAR.TYPE_MISMATCH", suggested_fix=fix)


def to_binary(value: Any, base_dir: Path | None = None) -> BinaryValue:
    if isinstance(value, BinaryValue):
        return value
    if isinstance(value, ImageValue):
        return BinaryValue(value.data, "image/png")
    if isinstance(value, list):
        out = bytearray()
        for i, x in enumerate(value):
            try:
                n = to_number(x)
            except VariableError:
                n = None
            if n is None or n != n.to_integral() or not -128 <= n <= 255:
                raise VariableError(f"Cannot read a list as Binary: item {i + 1} ({safe_repr(x)}) is not a byte value",
                                    code="VAR.TYPE_MISMATCH", suggested_fix="Give whole numbers from 0 to 255.")
            out.append(int(n) & 0xFF)
        return BinaryValue(bytes(out))
    data, mime = _bytes_of(value, "Binary", base_dir)
    return BinaryValue(data, mime)


def to_image(value: Any, base_dir: Path | None = None) -> ImageValue:
    if isinstance(value, ImageValue):
        return value
    data = value.data if isinstance(value, BinaryValue) else _bytes_of(value, "Image", base_dir)[0]
    try:
        return ImageValue(data)
    except ValueError as e:
        raise VariableError(f"Cannot read a {type(value).__name__} value as an Image: {e}", code="VAR.TYPE_MISMATCH",
                            suggested_fix="Give a PNG file path, or a Binary value holding a PNG picture.") from None


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
            if isinstance(value, _dt.timedelta):
                return format_timespan(value)
            if isinstance(value, _dt.time):
                return format_time(value)
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
            if isinstance(value, (ImageValue, BinaryValue)):
                raise VariableError(f"'{name}' expects a file path, got {value}", code="VAR.TYPE_MISMATCH",
                                    suggested_fix="Give a file path. An Image can be given directly to the picture fields of the Image commands.")
            return str(Path(value)) if isinstance(value, (str, Path)) else value
        if t is OoaType.TIME:
            return to_time(value)
        if t is OoaType.TIMESPAN:
            return to_timespan(value)
        if t is OoaType.IMAGE:
            return to_image(value)
        if t is OoaType.BINARY:
            return to_binary(value)
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
        "Time": {"type": "string", "format": "time"},
        "TimeSpan": {"type": "string", "format": "duration"},
        "Image": {"type": "object", "required": ["$binary"]},
        "Binary": {"type": "object", "required": ["$binary"]},
    }.get(type_, {})


def to_jsonable(value: Any, display: bool = False) -> Any:
    """Serialise run values for events, outputs and tool results. Secrets stay masked. With display=True an Image or
    Binary becomes its summary ("Image 640x480"), so events and logs never carry the bytes."""
    if isinstance(value, SecureString):
        return MASK
    if isinstance(value, Decimal):
        return int(value) if value == value.to_integral() and abs(value) < 10**15 else float(value)
    if isinstance(value, (_dt.datetime, _dt.date)):
        return value.isoformat()
    if isinstance(value, _dt.time):
        return format_time(value)
    if isinstance(value, _dt.timedelta):
        return format_timespan(value)
    if isinstance(value, (ImageValue, BinaryValue)):
        return str(value) if display else value.to_json()
    if isinstance(value, dict):
        return {str(k): to_jsonable(v, display) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [to_jsonable(v, display) for v in value]
    if isinstance(value, Path):
        return str(value)
    if isinstance(value, (str, int, float, bool)) or value is None:
        return value
    return f"<{type(value).__name__}>"


def safe_repr(value: Any, limit: int = 200) -> str:
    s = json.dumps(to_jsonable(value, display=True), default=str)
    return s if len(s) <= limit else s[: limit - 3] + "..."
