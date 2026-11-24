"""Cases for the CSV/TXT group: the samples (fuller converter) and the session commands Open and Close."""
from helpers_core import resample

CSV = "sku;qty;price\nA-1;2;9.50\nB-7;1;20\nC-3;4;1.25\n"


def add(ua):
    resample(ua)
    for n, t in (("csv_rows", "Number"), ("csv_cols", "List"), ("csv_table", "Table"), ("csv_n", "Number"),
                 ("csv_rec", "Any"), ("csv_skus", "String")):
        ua.var(n, t)

    with ua.case("ooa.csv/open", "a semicolon file read once as a session",
                 "Open reads a CSV or delimited text file once as a named session; Read and Each row then use the "
                 "session. Checks: the row count and columns Open gives, a Read and a loop over the session.") as f:
        f.act("ooa.file/write_text", desc="A semicolon-separated file", path="ut_tmp/csv/items.csv", text=CSV)
        f.act("ooa.csv/open", desc="Open it as session ut_items", path="ut_tmp/csv/items.csv", session="ut_items",
              delimiter=";", out={"row_count": "csv_rows", "columns": "csv_cols"})
        ua.check_eq("csv_rows", 3)
        ua.check_eq("csv_cols", ["sku", "qty", "price"])
        f.act("ooa.csv/read", desc="Read the session into a table", session="ut_items",
              out={"table": "csv_table", "row_count": "csv_n"})
        ua.check_eq("csv_n", 3)
        f.set("csv_skus", "")
        with f.loop(desc="Each row of the session", iterator="ooa.csv/rows", iterator_args={"session": "ut_items"},
                    **{"as": "csv_rec"}):
            f.set("csv_skus", "${csv_skus}${csv_rec.sku};")
        ua.check_eq("csv_skus", "A-1;B-7;C-3;")
        f.act("ooa.csv/close", desc="Close the session", session="ut_items")

    with ua.case("ooa.csv/close", "a closed session can no longer be read",
                 "Close ends a CSV/TXT session opened with Open. Checks: Read of the closed session fails with "
                 "SESSION.NOT_FOUND.") as f:
        f.act("ooa.file/write_text", desc="A small file", path="ut_tmp/csv/two.csv", text="a,b\n1,2\n")
        f.act("ooa.csv/open", desc="Open it as session ut_two", path="ut_tmp/csv/two.csv", session="ut_two")
        f.act("ooa.csv/close", desc="Close the session", session="ut_two")
        ua.expect_error("SESSION.NOT_FOUND", lambda ff: ff.act("ooa.csv/read", desc="Read the closed session",
                                                                session="ut_two", out={"table": "csv_table"}))
