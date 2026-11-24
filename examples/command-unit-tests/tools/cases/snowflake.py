"""Hand-written cases for the Snowflake commands (stand-in SQL API at $standin_url$/cloud-snowflake, made-up token)."""
from helpers_cloud import adapt_samples, value, cred

T = "ooa.snowflake/"
ACC = "$standin_url$/cloud-snowflake"
ORDERS = "UT_DB.PUBLIC.ORDERS"


def add(ua):
    adapt_samples(ua, ua.f.name)
    for n, t in (("sf_table", "Table"), ("sf_rows", "Number"), ("sf_total", "Number"), ("sf_handle", "String"), ("sf_status", "String"),
                 ("sf_names", "List"), ("sf_n", "Number"), ("sf_errors", "Number"), ("sf_result", "Any"), ("sf_file", "String"),
                 ("sf_text", "String"), ("sf_closed", "Boolean"), ("sf_msg", "String"), ("sf_data", "Table")):
        ua.var(n, t)

    with ua.case(T + "connect", "account and token kept for later steps",
                 "Connect keeps the account address and the token (from the vault) under a session, so later steps need "
                 "neither. Checked: a statement runs without its own address or token.") as f:
        f.act(T + "connect", desc="Connect to the stand-in Snowflake", account_url=ACC, token=cred("snowflake/token"), token_type="OAUTH")
        f.act(T + "execute_statement", desc="Read one order", sql="SELECT * FROM ORDERS WHERE ORDER_ID = ?", bindings=[1],
              warehouse="UT_WH", database="UT_DB", schema="PUBLIC", out={"row_count": "sf_rows", "status": "sf_status"})
        ua.check_eq("sf_rows", 1)
        ua.check_eq("sf_status", "succeeded")

    with ua.case(T + "execute_statement", "rows with a binding, typed",
                 "Run SQL statement runs one statement with ? bindings and gives the rows as a table with numbers as "
                 "numbers. Checked: the orders over 100 and the amount of the first.") as f:
        f.act(T + "execute_statement", desc="Orders over 100", sql="SELECT * FROM ORDERS WHERE AMOUNT > ?", bindings=[100],
              database="UT_DB", schema="PUBLIC", role="UT_ROLE", wait_seconds=30, max_rows=1000,
              out={"table": "sf_table", "row_count": "sf_rows"})
        ua.check("ge", "$sf_rows$", 2, what="orders over 100")
        value(ua, "$sf_table.rows[0][2]", "eq", 120.5, "the first amount")

    with ua.case(T + "fetch_results", "every partition of a result",
                 "Get statement results reads the rows of a finished statement, partition by partition. Checked: all "
                 "rows and the total.") as f:
        f.act(T + "execute_statement", desc="All orders", sql="SELECT * FROM ORDERS", out={"statement_handle": "sf_handle"})
        f.act(T + "fetch_results", desc="Read the rows again", statement_handle="$sf_handle$", max_rows=1000,
              out={"table": "sf_table", "row_count": "sf_rows", "total_rows": "sf_total"})
        ua.check("ge", "$sf_rows$", 3, what="rows read")
        ua.check("eq", "$sf_rows$", "$sf_total$", what="rows read equal the total")

    with ua.case(T + "get_statement_status", "a statement left running",
                 "Get statement status tells whether a statement left running has finished. A slow statement is started "
                 "with no wait. Checked: running.") as f:
        f.act(T + "execute_statement", desc="Start a slow statement", sql="SELECT SLOW * FROM ORDERS", wait_seconds=0,
              out={"statement_handle": "sf_handle", "status": "sf_status"})
        ua.check_eq("sf_status", "running")
        f.act(T + "get_statement_status", desc="Ask how it is going", statement_handle="$sf_handle$", out={"status": "sf_status"})
        ua.check_eq("sf_status", "running")

    with ua.case(T + "cancel_statement", "stop a slow statement",
                 "Cancel statement stops a statement still running. Checked: its status is then failed with Snowflake's "
                 "cancel message.") as f:
        f.act(T + "execute_statement", desc="Start a slow statement", sql="SELECT SLOW * FROM ORDERS", wait_seconds=0,
              out={"statement_handle": "sf_handle"})
        f.act(T + "cancel_statement", desc="Cancel it", statement_handle="$sf_handle$")
        f.act(T + "get_statement_status", desc="Read its status", statement_handle="$sf_handle$", out={"status": "sf_status", "message": "sf_msg"})
        ua.check_eq("sf_status", "failed")
        ua.check("contains", "$sf_msg$", "canceled", what="the message")

    with ua.case(T + "export_results", "a query to a CSV file",
                 "Export results to file runs a statement and writes all its rows to a file (CSV with a header here). "
                 "Checked: the row count and the header line.") as f:
        f.act(T + "export_results", desc="Export the paid orders", sql="SELECT * FROM ORDERS WHERE STATUS = ?", bindings=["PAID"],
              file="snowflake/paid.csv", format="csv", header=True, compression="none", overwrite=True,
              out={"row_count": "sf_rows", "file": "sf_file"})
        ua.check("ge", "$sf_rows$", 1, what="rows written")
        f.act("ooa.file/read_text", desc="Read the file", path="snowflake/paid.csv", out={"text": "sf_text"})
        ua.check("startsWith", "$sf_text$", "ORDER_ID,CUSTOMER,AMOUNT,STATUS", what="the header line")

    with ua.case(T + "list_databases", "databases the role sees",
                 "List databases gives the databases the role can see. Checked: UT_DB is one.") as f:
        f.act(T + "list_databases", desc="List the databases", role="UT_ROLE", out={"names": "sf_names"})
        ua.check("contains", "$sf_names$", "UT_DB", what="the test database")

    with ua.case(T + "list_schemas", "schemas of a database",
                 "List schemas gives the schemas of a database. Checked: PUBLIC is one.") as f:
        f.act(T + "list_schemas", desc="Schemas of UT_DB", database="UT_DB", out={"names": "sf_names", "table": "sf_table"})
        ua.check("contains", "$sf_names$", "PUBLIC", what="the PUBLIC schema")

    with ua.case(T + "list_tables", "tables of a schema",
                 "List tables gives the tables of a schema. Checked: ORDERS is one.") as f:
        f.act(T + "list_tables", desc="Tables of UT_DB.PUBLIC", database="UT_DB", schema="PUBLIC", out={"names": "sf_names"})
        ua.check("contains", "$sf_names$", "ORDERS", what="the ORDERS table")

    with ua.case(T + "list_columns", "columns of a table",
                 "List columns gives a table's columns with their types. Checked: four columns, AMOUNT among them.") as f:
        f.act(T + "list_columns", desc="Columns of ORDERS", table_name=ORDERS, out={"names": "sf_names", "table": "sf_table"})
        value(ua, "len($sf_names)", "eq", 4, "the number of columns")
        ua.check("contains", "$sf_names$", "AMOUNT", what="the AMOUNT column")

    with ua.case(T + "list_warehouses", "warehouses the role may use",
                 "List warehouses gives the warehouses with size and state. Checked: UT_WH.") as f:
        f.act(T + "list_warehouses", desc="List the warehouses", out={"names": "sf_names"})
        ua.check("contains", "$sf_names$", "UT_WH", what="the warehouse")

    with ua.case(T + "insert_rows", "rows of a data table",
                 "Insert rows from data table adds every row of a data table, matching columns by name. Checked: two "
                 "rows added (removed again at the end).") as f:
        f.set("sf_data", {"columns": ["ORDER_ID", "CUSTOMER", "AMOUNT", "STATUS"],
                          "rows": [[9001, "Umbrella plc", 42.10, "OPEN"], [9002, "Hooli", 18, "OPEN"]]}, desc="Two new orders")
        f.act(T + "insert_rows", desc="Insert them", table_name=ORDERS, data="$sf_data$", batch_size=100, out={"rows_inserted": "sf_n"})
        ua.check_eq("sf_n", 2)
        f.act(T + "delete_rows", desc="Tidy up", table_name=ORDERS, key_column="ORDER_ID", key_values=[9001, 9002])

    with ua.case(T + "update_rows", "set a status by key",
                 "Update rows by key sets columns to new values in the rows whose key is one of those given. Checked: one "
                 "row changed, and reading it back.") as f:
        f.act(T + "update_rows", desc="Mark order 2 shipped", table_name=ORDERS, key_column="ORDER_ID", key_values=[2],
              values={"STATUS": "SHIPPED"}, out={"rows_changed": "sf_n"})
        ua.check_eq("sf_n", 1)
        f.act(T + "execute_statement", desc="Read order 2", sql="SELECT * FROM ORDERS WHERE ORDER_ID = ?", bindings=[2], out={"table": "sf_table"})
        value(ua, "$sf_table.rows[0][3]", "eq", "SHIPPED", "the new status")

    with ua.case(T + "delete_rows", "delete by key",
                 "Delete rows by key deletes the rows whose key is one of those given. A row is added first. Checked: one "
                 "row deleted.") as f:
        f.set("sf_data", {"columns": ["ORDER_ID", "CUSTOMER", "AMOUNT"], "rows": [[9100, "Temp Co", 1]]}, desc="An order to delete")
        f.act(T + "insert_rows", desc="Add it", table_name=ORDERS, data="$sf_data$")
        f.act(T + "delete_rows", desc="Delete it", table_name=ORDERS, key_column="ORDER_ID", key_values=[9100], out={"rows_changed": "sf_n"})
        ua.check_eq("sf_n", 1)

    with ua.case(T + "load_file", "CSV with a bad row, good rows loaded",
                 "Load file into table puts a local CSV on the table's stage and copies it in; with on_error continue the "
                 "good rows load and the bad ones are counted. Checked: 2 loaded, 1 error (removed again at the end).") as f:
        f.act("ooa.file/write_text", desc="A CSV with one bad row", path="snowflake/load.csv",
              text="ORDER_ID,CUSTOMER,AMOUNT,STATUS\n9201,Load One,10.00,OPEN\n9202,Load Two,20.00,OPEN\n9203,Bad Row,lots,OPEN\n")
        f.act(T + "load_file", desc="Load it", table_name=ORDERS, file="snowflake/load.csv", format="csv", skip_header=1,
              on_error="continue", out={"rows_loaded": "sf_n", "errors_seen": "sf_errors", "results": "sf_table"})
        ua.check_eq("sf_n", 2)
        ua.check_eq("sf_errors", 1)
        f.act(T + "delete_rows", desc="Tidy up", table_name=ORDERS, key_column="ORDER_ID", key_values=[9201, 9202])

    with ua.case(T + "call_procedure", "named argument",
                 "Run stored procedure calls a procedure with argument values sent apart from the SQL and gives back what "
                 "it returns. Checked: the procedure's answer names the month.") as f:
        f.act(T + "call_procedure", desc="Close October", procedure="UT_DB.PUBLIC.CLOSE_MONTH", parameters={"MONTH": "2026-10"},
              out={"result": "sf_result", "statement_handle": "sf_handle"})
        ua.check("startsWith", "$sf_result$", "closed 2026-10", what="the procedure's answer")

    with ua.case(T + "disconnect", "close the session",
                 "Disconnect forgets the open session; later steps then need their own address and token. Checked: it "
                 "reports the session closed.") as f:
        f.act(T + "disconnect", desc="Close the session", out={"closed": "sf_closed"})
        ua.check_eq("sf_closed", True)
