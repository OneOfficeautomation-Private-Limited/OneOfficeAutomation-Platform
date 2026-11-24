"""Cases for the Data Table group: the samples (fuller converter) and Create table."""
from helpers_core import resample


def add(ua):
    resample(ua)
    ua.var("new_table", "Table")
    ua.var("new_rows", "Number", 0)
    ua.var("new_cols", "List")
    with ua.case("ooa.datatable/create_table", "named columns and empty rows",
                 "Create table makes a table with the columns named and a number of empty rows, ready to fill. "
                 "Checks: 3 rows and the 2 columns asked for.") as f:
        f.act("ooa.datatable/create_table", desc="A table with columns Sku and Qty and 3 empty rows",
              columns=["Sku", "Qty"], rows=3, out={"table": "new_table"})
        f.act("ooa.datatable/row_count", desc="Count its rows", table="$new_table$", out={"count": "new_rows"})
        ua.check_eq("new_rows", 3)
        f.act("ooa.datatable/column_names", desc="Its column names", table="$new_table$",
              out={"names": "new_cols"})
        ua.check_eq("new_cols", ["Sku", "Qty"])
