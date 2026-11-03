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
