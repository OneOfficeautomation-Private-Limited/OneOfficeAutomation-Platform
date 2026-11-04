"""Time, TimeSpan, Image and Binary (gap G22): coercion, JSON forms, display and expression functions, as the Java runner."""
import datetime as dt
from decimal import Decimal

import pytest

from ooa.runner.expressions import evaluate
from ooa.sdk.errors import ExpressionError, VariableError
from ooa.sdk.types import (BinaryValue, ImageValue, coerce, format_timespan, safe_repr, to_jsonable)

PNG = "iVBORw0KGgoAAAANSUhEUgAAAAMAAAACCAIAAAASFvFNAAAAEklEQVR4nGP4z8AAQVDqPwMDAEHSBfsl0XwmAAAAAElFTkSuQmCC"


@pytest.mark.parametrize("text,expected", [
    ("14:30", dt.time(14, 30)), ("14:30:05", dt.time(14, 30, 5)), ("9:05", dt.time(9, 5)),
    ("2:30 PM", dt.time(14, 30)), ("12:00 am", dt.time(0, 0)), ("2026-10-18T07:45:00", dt.time(7, 45)),
])
def test_time_coercion(text, expected):
    assert coerce(text, "Time") == expected


def test_time_from_datetime_and_bad_text():
    assert coerce(dt.datetime(2026, 1, 2, 3, 4, 5), "Time") == dt.time(3, 4, 5)
    for bad in ("25:00", "13:00 PM", "soon", 5):
        with pytest.raises(VariableError):
            coerce(bad, "Time")


@pytest.mark.parametrize("text,seconds", [
    ("2h 15m", 8100), ("1d 3h", 97200), ("PT2H15M", 8100), ("P1DT3H", 97200), ("01:30:00", 5400),
    ("1.02:03:04", 93784), ("90", 90), ("-PT30M", -1800), ("-2h", -7200), ("1.5 hours", 5400), ("1h, 30 minutes and 5s", 5405),
])
def test_timespan_coercion(text, seconds):
    assert coerce(text, "TimeSpan") == dt.timedelta(seconds=seconds)


def test_timespan_numbers_and_bad_text():
    assert coerce(Decimal("1.5"), "TimeSpan") == dt.timedelta(seconds=1.5)
    for bad in ("2 parsecs", "10:75", "P", "abc", True):
        with pytest.raises(VariableError):
            coerce(bad, "TimeSpan")


def test_json_forms_round_trip():
    assert to_jsonable(dt.time(14, 30)) == "14:30:00"
    assert format_timespan(dt.timedelta(hours=2, minutes=15)) == "PT2H15M"
    assert format_timespan(dt.timedelta(0)) == "PT0S"
    assert format_timespan(-dt.timedelta(minutes=30)) == "-PT30M"
    assert coerce(to_jsonable(coerce("-1d 2h", "TimeSpan")), "TimeSpan") == -dt.timedelta(hours=26)
    b = coerce({"$binary": "aGk=", "mime": "text/plain"}, "Binary")
    assert to_jsonable(b) == {"$binary": "aGk=", "mime": "text/plain"}
    assert coerce(to_jsonable(b), "Binary") == b
    img = coerce({"$binary": PNG}, "Image")
    assert (img.width, img.height) == (3, 2)
    assert to_jsonable(img) == {"$binary": PNG, "mime": "image/png", "width": 3, "height": 2}


def test_binary_sources(tmp_path):
    f = tmp_path / "a.bin"
    f.write_bytes(b"\x00\x01\xff")
    assert coerce(str(f), "Binary").data == b"\x00\x01\xff"
    assert coerce([0, 1, 255], "Binary").data == b"\x00\x01\xff"
    assert coerce("data:text/plain;base64,aGk=", "Binary") == BinaryValue(b"hi", "text/plain")
    assert coerce("aGk=", "Binary").data == b"hi"
    with pytest.raises(VariableError):
        coerce([1, 256], "Binary")
    with pytest.raises(VariableError):
        coerce("C:/nowhere/x.bin", "Binary")
    with pytest.raises(VariableError):
        coerce(BinaryValue(b"not a picture"), "Image")
    with pytest.raises(VariableError):
        coerce(BinaryValue(b"x"), "File")


def test_display_never_shows_bytes():
    img = coerce({"$binary": PNG}, "Image")
    assert safe_repr(img) == '"Image 3x2"'
    assert safe_repr([BinaryValue(b"x" * 2048)]) == '["Binary 2.0 KB"]'
    assert coerce(img, "String") == "Image 3x2"
    assert isinstance(coerce(img, "Binary"), BinaryValue) and isinstance(img, ImageValue)


def test_expressions():
    env = {"t": dt.time(14, 30), "s": dt.timedelta(hours=2, minutes=15), "d": dt.datetime(2026, 10, 18, 9, 0),
           "b": BinaryValue(b"hi"), "img": coerce({"$binary": PNG}, "Image")}
    ev = lambda src: evaluate(src, env.__getitem__)  # noqa: E731
    assert ev("$t + $s") == dt.time(16, 45)
    assert ev("$t - timeSpan('15h')") == dt.time(23, 30)
    assert ev("$d + $s") == dt.datetime(2026, 10, 18, 11, 15)
    assert ev("$d - $s") == dt.datetime(2026, 10, 18, 6, 45)
    assert ev("$s * 2") == dt.timedelta(hours=4, minutes=30)
    assert ev("$s / 3") == dt.timedelta(minutes=45)
    assert ev("$s / timeSpan('45m')") == 3
    assert ev("-$s") == -dt.timedelta(hours=2, minutes=15)
    assert ev("$t > '12:00'") is True and ev("$t == '14:30:00'") is True
    assert ev("$s > timeSpan('2h')") is True
    assert ev("time(9, 5)") == dt.time(9, 5) and ev("time('2:30 PM')") == dt.time(14, 30)
    assert ev("timeSpan(90, 'minutes')") == dt.timedelta(minutes=90)
    assert ev("totalSeconds($s)") == 8100 and ev("totalMinutes($s)") == 135 and ev("totalHours($s)") == Decimal("2.25")
    assert ev("totalDays('36h')") == Decimal("1.5")
    assert ev("timeBetween('2026-10-18T09:00:00', '2026-10-18T10:30:00')") == dt.timedelta(minutes=90)
    assert ev("timeBetween(time('09:00'), time('08:00'))") == -dt.timedelta(hours=1)
    assert ev("dateAdd($d, $s)") == dt.datetime(2026, 10, 18, 11, 15)
    assert ev("dateAdd($d, 1, 'days')") == dt.datetime(2026, 10, 19, 9, 0)
    assert ev("imageWidth($img)") == 3 and ev("imageHeight($img)") == 2
    assert ev("binaryLength($b)") == 2 and ev("len($b)") == 2
    assert ev("toBase64($b)") == "aGk=" and ev("toBase64('hi')") == "aGk="
    assert ev("fromBase64('aGk=')") == BinaryValue(b"hi")
    assert ev("'at ' + $t") == "at 14:30:00"
    for bad in ("$t - $t", "$d - $d", "$s % 2", "fromBase64('***')", "dateAdd($d, $s, 'days')"):
        with pytest.raises((ExpressionError, VariableError)):
            ev(bad)
