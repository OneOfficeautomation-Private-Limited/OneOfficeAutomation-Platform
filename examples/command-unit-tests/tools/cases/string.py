"""Hand-written cases for the String commands the samples do not cover."""


def add(ua):
    ua.condition_case("ooa.string/starts_with", "invoice number prefix",
                      "Starts with is a condition: true when the text begins with the given start (here ignoring case).",
                      {"text": "INV-2026-0042", "start": "inv-", "ignore_case": True}, True)
    ua.condition_case("ooa.string/starts_with", "different start",
                      "The same condition is false when the text begins differently.",
                      {"text": "PO-77", "start": "INV-"}, False)
    ua.condition_case("ooa.string/ends_with", "file extension",
                      "Ends with is a condition: true when the text finishes with the given end.",
                      {"text": "statement.PDF", "end": ".pdf", "ignore_case": True}, True)
    ua.condition_case("ooa.string/is_empty", "only spaces count as empty",
                      "Is empty is a condition: with spaces_are_empty on, text made only of spaces counts as empty.",
                      {"text": "   ", "spaces_are_empty": True}, True)
    ua.condition_case("ooa.string/is_empty", "real text",
                      "Is empty is false for text with characters in it.",
                      {"text": "Ann"}, False)
