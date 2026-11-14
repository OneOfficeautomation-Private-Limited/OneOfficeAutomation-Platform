"""Variables written $name$ (as AA writes them, 14 Nov 2026 item F) next to the earlier $name and ${name}: a whole value
keeps its type, $name$ inside text is replaced by the value, a $ without a closing $ stays as typed, a spreadsheet's
fixed cell reference ($F$1) is not a variable, and calculations read both forms. The same cases as the Java runner's
VariableNotationTest."""
from decimal import Decimal

from ooa.runner.expressions import evaluate, name_of, references, resolve

VARS = {"count": Decimal("3"), "customer": "Acme", "order": {"id": "A-7", "lines": ["x", "y"]},
        "price": Decimal("2.5"), "qty": Decimal("4")}
look = VARS.get


def test_a_whole_value_keeps_its_type_in_either_form():
    assert resolve("$count$", look) == Decimal("3")
    assert isinstance(resolve("$count$", look), Decimal)
    assert resolve("$count", look) == Decimal("3")
    assert resolve("$order.id$", look) == "A-7"
    assert resolve("$order.lines[1]$", look) == "y"


def test_an_earlier_reference_with_a_variable_index_is_still_read():
    # as before 14 Nov: an [index] may hold a variable (the refund example's $policy.reason_rates[$reason])
    v = {"rates": {"damaged": Decimal("1.0")}, "reason": "damaged", "lines": ["x", "y"], "i": Decimal("1")}
    assert resolve("$rates[$reason]", v.get) == Decimal("1.0")
    assert resolve("$lines[$i]", v.get) == "y"
    assert resolve("$lines[$i$]", v.get) == "y"
    assert references("$rates[$reason]") == {"rates", "reason"}


def test_dollar_name_dollar_inside_text_is_replaced():
    assert resolve("Total $count$ for $customer$", look) == "Total 3 for Acme"
    assert resolve("Order $order.id$", look) == "Order A-7"
    assert resolve("Total $count$ and ${count}", look) == "Total 3 and 3"


def test_a_dollar_without_its_closing_dollar_stays_as_typed():
    assert resolve("Costs US$ 5 or $5", look) == "Costs US$ 5 or $5"
    assert resolve("Total $count", look) == "Total $count"   # only $count$ or ${count} is replaced in text


def test_spreadsheet_cell_references_stay_as_typed():
    assert resolve("B2*$F$1", look) == "B2*$F$1"
    assert resolve("=SUM($A$1:$B$20)", look) == "=SUM($A$1:$B$20)"
    assert references("=SUM($F$2:$F$5)") == set()
    assert resolve("$count$ at $F$1", look) == "3 at $F$1"


def test_calculations_read_both_forms():
    assert evaluate("$price$ * $qty$", look) == Decimal("10")
    assert evaluate("$price * $qty$", look) == Decimal("10")
    assert resolve({"expr": "$price$ * $qty$"}, look) == Decimal("10")
    assert evaluate("get($order$, upper(trim('id')))", look) is None
    assert evaluate("get($order$, trim(' id '))", look) == "A-7"


def test_references_are_found_in_every_form():
    assert references("Total $count$ for $customer$") == {"count", "customer"}
    assert references("$count$") == {"count"}
    assert references({"expr": "$price$ * $qty"}) == {"price", "qty"}
    assert references("Total $count") == set()


def test_a_name_is_read_from_every_way_of_writing_it():
    assert name_of("$total$") == "total"
    assert name_of("$total") == "total"
    assert name_of(" total ") == "total"
