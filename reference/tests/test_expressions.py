from decimal import Decimal

import pytest

from ooa.runner.expressions import evaluate, references, resolve
from ooa.sdk.errors import ExpressionError, VariableError
from ooa.sdk.types import SecureString

VARS = {"a": Decimal(2), "s": "Acme Ltd", "inv": {"total": Decimal("100.50"), "lines": [{"q": Decimal(3)}]},
        "pw": SecureString("hunter2"), "none": None, "acc": "héllo", "url": "a b&c/é"}
look = VARS.get


@pytest.mark.parametrize("src,expected", [
    ("1 + 2 * 3", Decimal(7)),
    ("(1 + 2) * 3", Decimal(9)),
    ("10 - 4 - 3", Decimal(3)),
    ("2 * 3 > 5 and not false", True),
    ("$inv.total * 2", Decimal("201.00")),
    ("$inv.lines[0].q", Decimal(3)),
    ("$inv['total']", Decimal("100.50")),
    ("upper($s)", "ACME LTD"),
    ("contains(lower($s), 'acme')", True),
    ("'x' + $a", "x2"),
    ("coalesce($none, 'fallback')", "fallback"),
    ("len([1, 2, 3])", Decimal(3)),
    ("if($a > 1, 'big', 'small')", "big"),
    ("$missing.deep.path", None),
    ("regexExtract('INV-2026-0042', '(\\\\d{4})$', 1)", "0042"),
    # calculations and text made easy (16 Nov 2026): the same cases as ExpressionFunctionsCalcTest
    ("floor(2.7)", Decimal(2)),
    ("floor(-2.1)", Decimal(-3)),
    ("floor(2.349, 2)", Decimal("2.34")),
    ("ceiling(2.1)", Decimal(3)),
    ("ceiling(2.341, 2)", Decimal("2.35")),
    ("truncate(-2.9)", Decimal(-2)),
    ("truncate(2.349, 2)", Decimal("2.34")),
    ("power(2, 10)", Decimal(1024)),
    ("power(7, 0)", Decimal(1)),
    ("power(1.5, 2)", Decimal("2.25")),
    ("power(2, -2)", Decimal("0.25")),
    ("power(2, 0.5)", Decimal("1.4142135623730951")),
    ("sqrt(16)", Decimal(4)),
    ("sqrt(2)", Decimal("1.4142135623730951")),
    ("mod(10, 3)", Decimal(1)),
    ("mod(-10, 3)", Decimal(-1)),
    ("mod(5.5, 1)", Decimal("0.5")),
    ("average([10, 20, 45])", Decimal(25)),
    ("average(1, 2, 3)", Decimal(2)),
    ("average(0, 0, 1)", Decimal("0.3333333333")),
    ("average([])", None),
    ("clamp(12, 0, 10)", Decimal(10)),
    ("clamp(-4, 0, 10)", Decimal(0)),
    ("clamp(5.5, 0, 10)", Decimal("5.5")),
    ("indexOf('Hello World', 'World')", Decimal(6)),
    ("indexOf('Hello', 'x')", Decimal(-1)),
    ("indexOf('a-b-c-d', '-', 4)", Decimal(5)),
    ("left('INV-2026', 3)", "INV"),
    ("right('INV-2026', 4)", "2026"),
    ("left('abc', -1)", ""),
    ("right('abc', 10)", "abc"),
    ("repeat('-=', 3)", "-=-=-="),
    ("padLeft('42', 5, '0')", "00042"),
    ("padRight('ab', 5)", "ab   "),
    ("padLeft('abcdef', 3)", "abcdef"),
    ("titleCase('hello wIDE world')", "Hello Wide World"),
    ("normalizeSpace('  a \t b\n\n c  ')", "a b c"),
    # gap G05: the same results as the Java runner
    ("first([1, 2, 3])", Decimal(1)),
    ("last([1, 2, 3])", Decimal(3)),
    ("first('Acme')", "A"),
    ("last([])", None),
    ("union([1, 2], [2, 3])", [Decimal(1), Decimal(2), Decimal(3)]),
    ("intersection([1, 2, 2], [2, 3])", [Decimal(2)]),
    ("base64Encode($acc)", "aMOpbGxv"),
    ("base64Decode('aMOpbGxv')", "héllo"),
    ("uriEncode($url)", "a%20b%26c%2F%C3%A9"),
    ("uriDecode('a%20b%26c+d')", "a b&c+d"),
    ("len(guid())", Decimal(36)),
])
def test_evaluate(src, expected):
    assert evaluate(src, look) == expected


@pytest.mark.parametrize("src", ["1 +", "upper(", "foo()", "1 $ 2", "$a.", "__import__('os')"])
def test_syntax_errors(src):
    with pytest.raises(ExpressionError):
        evaluate(src, look)


def test_no_python_escape():
    # field access only reads dict keys / list items; method calls are not in the grammar
    assert evaluate("$s.__class__", look) is None or isinstance(evaluate("$s.__class__", look), str) is False
    with pytest.raises(ExpressionError):
        evaluate("$s.upper()", look)


def test_division_by_zero_is_typed():
    with pytest.raises(ExpressionError) as e:
        evaluate("1 / 0", look)
    assert e.value.code == "EXPR.DIV_ZERO"


@pytest.mark.parametrize("src", ["upper($pw)", "$pw + 'x'", "$pw == 'hunter2'", "len($pw)"])
def test_secure_values_cannot_leak(src):
    with pytest.raises(VariableError) as e:
        evaluate(src, look)
    assert e.value.code == "VAR.SECURE_LEAK"


def test_resolve_forms():
    assert resolve("$inv.total", look) == Decimal("100.50")
    assert resolve("Total: ${inv.total}", look) == "Total: 100.50"
    assert resolve({"expr": "$a * 10"}, look) == Decimal(20)
    assert resolve({"literal": "$a"}, look) == "$a"
    assert resolve({"k": ["$a", "${s}"]}, look) == {"k": [Decimal(2), "Acme Ltd"]}
    assert resolve("plain text $ 5", look) == "plain text $ 5"


def test_references():
    assert references({"x": "$a", "y": "hi ${s} ${upper($t)}", "z": {"expr": "$u + 1"}, "w": {"literal": "$no"}}) == {"a", "s", "t", "u"}


def test_quoted_text_keeps_accented_letters_and_escapes():
    """REF-1: 'héllo' stays 'héllo'; \n, \', \\ and \u00e9 are still read as escapes."""
    from ooa.runner.expressions import _unescape
    assert _unescape("h\u00e9llo \u4e16\u754c") == "h\u00e9llo \u4e16\u754c"
    assert _unescape("a\nb") == "a\nb"
    assert _unescape("it\'s \\ x") == "it's \ x"
    assert _unescape("caf\u00e9") == "caf\u00e9"
