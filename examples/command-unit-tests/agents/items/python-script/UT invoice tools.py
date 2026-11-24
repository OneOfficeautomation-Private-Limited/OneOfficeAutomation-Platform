"""UT invoice tools: sample Python script for agents.

As a script (Run Python script): reads a JSON object with "lines" from standard input, takes the currency as the first
argument and prints one JSON object with the total. As functions (Run Python function): line_total, invoice_total,
normalise_reference and the class Vat.
"""
import json
import re
import sys


def line_total(quantity, unit_price):
    """One line: quantity x unit price, rounded to cents."""
    return round(float(quantity) * float(unit_price), 2)


def invoice_total(lines, discount_percent=0):
    """Every line added up, less a discount in percent."""
    total = sum(line_total(l["qty"], l["price"]) for l in lines)
    return {"lines": len(lines), "total": round(total * (100 - discount_percent) / 100, 2)}


def normalise_reference(text):
    """An invoice reference in one spelling: INV-<digits>, e.g. 'inv 42' -> 'INV-42'."""
    m = re.search(r"(\d+)", text or "")
    if not m:
        raise ValueError("no number in reference: " + str(text))
    return "INV-" + m.group(1)


class Vat:
    rate = 0.2

    @staticmethod
    def add(net):
        return round(net * (1 + Vat.rate), 2)


if __name__ == "__main__":
    data = json.loads(sys.stdin.read() or "{}")
    currency = sys.argv[1] if len(sys.argv) > 1 else "EUR"
    result = invoice_total(data.get("lines", []))
    result["currency"] = currency.upper()
    print(json.dumps(result))
