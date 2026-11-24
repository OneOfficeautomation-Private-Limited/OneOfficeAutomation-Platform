"""Insights cases. Locally the values are insights/<category>.jsonl in the workspace; the category ut_invoices is
cleared first and at the end so every run counts the same."""
from helpers_platform import prefix_names, fix_sample_checks, move_last_case_to, renumber

I = "ooa.insights/"


def _clear(f):
    f.act("ooa.file/delete", desc="Remove the values earlier runs recorded locally", path="insights/ut_invoices.jsonl")
    f.act("ooa.file/delete", desc="Remove the values earlier runs recorded locally", path="insights/ut_claims.jsonl")


def add(ua):
    prefix_names(ua, I, keys=("category",))
    fix_sample_checks(ua)
    for v, t in (("in_seconds", "Number"), ("in_table", "Table"), ("in_n", "Number")):
        ua.var(v, t)
    f = ua.f
    with ua.case(I + "read_values", "nothing recorded yet for a new category",
                 "Read values gives the values of a category as a table. It runs first, after the local values of "
                 "earlier runs are removed: the category ut_invoices has none."):
        _clear(f)
        f.act(I + "read_values", category="ut_invoices", out={"count": "in_n"})
        ua.check("eq", "$in_n$", 0, what="values recorded")
    move_last_case_to(ua, 1)

    with ua.case(I + "open_transaction", "open a transaction for one claim",
                 "Open transaction marks the start of one business transaction (claim CL-1 of category ut_claims); "
                 "Close transaction then records it, so reading that transaction back gives one row."):
        f.act(I + "open_transaction", transaction="CL-1", category="ut_claims")
        f.act(I + "close_transaction", transaction="CL-1", values={"amount": 250, "minutes_saved": 12},
              out={"duration_seconds": "in_seconds"})
        f.act(I + "read_values", category="ut_claims", transaction="CL-1", out={"count": "in_n", "table": "in_table"})
        ua.check("eq", "$in_n$", 1, what="rows for CL-1")
    with ua.case(I + "close_transaction", "a status in our own words, with its duration",
                 "Close transaction records the values, the outcome and how long the transaction took: the duration "
                 "is at least the one-second pause, and a status given in our own words (policy expired) is recorded "
                 "instead of the outcome."):
        f.act(I + "open_transaction", transaction="CL-2", category="ut_claims")
        f.act("core/delay", seconds=1)
        f.act(I + "close_transaction", transaction="CL-2", values={"amount": 90}, outcome="business_exception",
              status="policy expired", out={"duration_seconds": "in_seconds"})
        ua.check("ge", "$in_seconds$", 1, what="duration in seconds")
        f.act(I + "read_values", category="ut_claims", transaction="CL-2", out={"table": "in_table"})
        ua.check("contains", "${in_table}", "policy expired", what="the outcome in the table")
    with ua.case(I + "read_values", "at most one record of several",
                 "Read values with max_records gives no more records than asked: two claims were recorded, one is "
                 "read. The local values are removed afterwards."):
        f.act(I + "read_values", category="ut_claims", max_records=1, out={"count": "in_n"})
        ua.check("eq", "$in_n$", 1, what="records read")
        _clear(f)
    renumber(ua)
