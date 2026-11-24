"""Cases for the Database group: the samples (an in-memory H2 database) and cases against the SQL Server test database
ooa_unit_test (tables Customers, Products, Orders; procedures CustomerOrders and RaisePrice). Changes are made in
transactions that are rolled back, or cleaned up straight after; the session ut_sql is opened in the first of these
cases and closed in the last."""
from helpers_core import resample

URL = "jdbc:sqlserver://localhost:1433;databaseName=ooa_unit_test;encrypt=false"
S = "ut_sql"


def add(ua):
    resample(ua)
    for n, t in (("db_n", "Number"), ("db_t", "Table"), ("db_rows", "Number"), ("db_out", "Any"),
                 ("db_path", "Any"), ("db_text", "String"), ("db_rec", "Any"), ("db_sum", "Number"),
                 ("db_data", "Table"), ("db_open", "Boolean")):
        ua.var(n, t)
    ua.var("db_user", "Credential")
    ua.var("db_password", "Credential")

    def count(f, sql, params=None, what="the count"):
        f.act("ooa.database/query", desc="Count: " + sql, session=S, sql=sql, parameters=params,
              out={"table": "db_t"})
        f.act("ooa.datatable/get_value", desc="The count", table="$db_t$", row=0, column_index=0,
              out={"value": "db_n"})

    with ua.case("ooa.database/connect", "SQL Server test database with vault sign-in",
                 "Connect opens a database connection as a named session, here SQL Server with the user name and "
                 "password from the vault. It stays open for the cases below. Checks: a query on it sees the 5 "
                 "customers of the test data.") as f:
        f.set("db_user", {"$credential": "unit_tests/sqlserver/username"}, desc="User name from the vault")
        f.set("db_password", {"$credential": "unit_tests/sqlserver/password"}, desc="Password from the vault")
        f.act("ooa.database/connect", desc="Connect to ooa_unit_test", jdbc_url=URL, user="$db_user$",
              password="$db_password$", session=S)
        count(f, "SELECT COUNT(*) FROM dbo.Customers")
        ua.check_eq("db_n", 5)

    with ua.case("ooa.database/query", "active customers with a parameter",
                 "Query runs a SELECT with ? parameters and gives the rows as a table. Checks: 3 active customers "
                 "with a credit limit of at least 10000, first by name is Ann Lee.") as f:
        f.act("ooa.database/query", desc="Active customers over a limit", session=S,
              sql="SELECT Name, City FROM dbo.Customers WHERE Active = 1 AND CreditLimit >= ? ORDER BY Name",
              parameters=[10000], out={"table": "db_t", "row_count": "db_rows"})
        ua.check_eq("db_rows", 3)
        f.act("ooa.datatable/get_value", desc="First name", table="$db_t$", row=0, column="Name",
              out={"value": "db_out"})
        ua.check_eq("db_out", "Ann Lee")

    with ua.case("ooa.database/query_rows", "loop over a customer's orders",
                 "Query rows is a loop: it runs a SELECT and gives each row as a record. Checks: Ann Lee's orders "
                 "add up to 3 items.") as f:
        f.set("db_sum", 0)
        with f.loop(desc="Each order of customer 1", iterator="ooa.database/query_rows",
                    iterator_args={"session": S, "sql": "SELECT Qty FROM dbo.Orders WHERE CustomerId = ?",
                                   "parameters": [1]}, **{"as": "db_rec"}):
            f.expr("db_sum", "$db_sum + $db_rec.Qty")
        ua.check_eq("db_sum", 3)

    with ua.case("ooa.database/begin_transaction", "an insert rolled back",
                 "Begin transaction starts a transaction: changes are kept only after Commit. Checks: a new customer "
                 "is seen inside the transaction and gone after Rollback.") as f:
        f.act("ooa.database/begin_transaction", desc="Start a transaction", session=S)
        f.act("ooa.database/execute", desc="Add a customer", session=S,
              sql="INSERT INTO dbo.Customers (CustomerId, Name, City, CreditLimit) VALUES (?, ?, ?, ?)",
              parameters=[901, "UT Temp", "Pune", 100])
        count(f, "SELECT COUNT(*) FROM dbo.Customers")
        ua.check_eq("db_n", 6)
        f.act("ooa.database/rollback", desc="Undo it", session=S)
        count(f, "SELECT COUNT(*) FROM dbo.Customers")
        ua.check_eq("db_n", 5)

    with ua.case("ooa.database/execute", "update with parameters, rows affected",
                 "Execute runs an INSERT, UPDATE, DELETE or DDL statement with ? parameters and gives the number of "
                 "rows changed. Done in a transaction that is rolled back. Checks: 2 open orders closed.") as f:
        f.act("ooa.database/begin_transaction", desc="Start a transaction", session=S)
        f.act("ooa.database/execute", desc="Close the open orders", session=S,
              sql="UPDATE dbo.Orders SET Status = ? WHERE Status = ?", parameters=["Closed", "Open"],
              out={"rows_affected": "db_n"})
        ua.check_eq("db_n", 2)
        f.act("ooa.database/rollback", desc="Undo it", session=S)
        count(f, "SELECT COUNT(*) FROM dbo.Orders WHERE Status = 'Open'")
        ua.check_eq("db_n", 2)

    with ua.case("ooa.database/rollback", "a delete undone",
                 "Rollback undoes the changes made since Begin transaction. Checks: products deleted in the "
                 "transaction are all back.") as f:
        f.act("ooa.database/begin_transaction", desc="Start a transaction", session=S)
        f.act("ooa.database/execute", desc="Delete the products without stock", session=S,
              sql="DELETE FROM dbo.Products WHERE Stock = 0 AND Sku NOT IN (SELECT Sku FROM dbo.Orders)",
              out={"rows_affected": "db_rows"})
        f.act("ooa.database/rollback", desc="Undo it", session=S)
        count(f, "SELECT COUNT(*) FROM dbo.Products")
        ua.check_eq("db_n", 4)

    with ua.case("ooa.database/commit", "a product kept, then cleaned up",
                 "Commit keeps the changes made since Begin transaction. Checks: a product added and committed is "
                 "there after the transaction; it is deleted again at the end.") as f:
        f.act("ooa.database/execute", desc="Remove a leftover from an earlier run", session=S,
              sql="DELETE FROM dbo.Products WHERE Sku = 'UT-COMMIT'")
        f.act("ooa.database/begin_transaction", desc="Start a transaction", session=S)
        f.act("ooa.database/execute", desc="Add a product", session=S,
              sql="INSERT INTO dbo.Products (Sku, Title, Price, Stock) VALUES ('UT-COMMIT', 'Test item', 1.00, 1)")
        f.act("ooa.database/commit", desc="Keep it", session=S)
        count(f, "SELECT COUNT(*) FROM dbo.Products WHERE Sku = 'UT-COMMIT'")
        ua.check_eq("db_n", 1)
        f.act("ooa.database/execute", desc="Clean up", session=S,
              sql="DELETE FROM dbo.Products WHERE Sku = 'UT-COMMIT'")

    with ua.case("ooa.database/bulk_insert", "a Data Table into Products, then cleaned up",
                 "Bulk insert inserts every row of a Data Table into a database table in batches, all or nothing. "
                 "Checks: 3 rows inserted and found; they are deleted again at the end.") as f:
        f.act("ooa.database/execute", desc="Remove leftovers from an earlier run", session=S,
              sql="DELETE FROM dbo.Products WHERE Sku LIKE 'UT-B%'")
        f.set("db_data", {"columns": ["Sku", "Title", "Price", "Stock"],
                          "rows": [["UT-B1", "Bulk one", 10.5, 1], ["UT-B2", "Bulk two", 20, 2],
                                   ["UT-B3", "Bulk three", 30, 3]]}, desc="Three new products")
        f.act("ooa.database/bulk_insert", desc="Insert them", session=S, table="dbo.Products", data="$db_data$",
              batch_size=2, out={"rows_inserted": "db_rows"})
        ua.check_eq("db_rows", 3)
        count(f, "SELECT COUNT(*) FROM dbo.Products WHERE Sku LIKE 'UT-B%'")
        ua.check_eq("db_n", 3)
        f.act("ooa.database/execute", desc="Clean up", session=S, sql="DELETE FROM dbo.Products WHERE Sku LIKE 'UT-B%'")

    with ua.case("ooa.database/bulk_insert", "from a CSV file, then cleaned up",
                 "Bulk insert can also read a delimited text file line by line. Checks: 2 rows inserted.") as f:
        f.act("ooa.database/execute", desc="Remove leftovers from an earlier run", session=S,
              sql="DELETE FROM dbo.Products WHERE Sku LIKE 'UT-F%'")
        f.act("ooa.file/write_text", desc="A CSV file of products", path="ut_tmp/db/products.csv",
              text="Sku,Title,Price,Stock\nUT-F1,File one,5,1\nUT-F2,File two,6,2\n")
        f.act("ooa.database/bulk_insert", desc="Insert the file's rows", session=S, table="dbo.Products",
              source_file="ut_tmp/db/products.csv", out={"rows_inserted": "db_rows"})
        ua.check_eq("db_rows", 2)
        f.act("ooa.database/execute", desc="Clean up", session=S, sql="DELETE FROM dbo.Products WHERE Sku LIKE 'UT-F%'")

    with ua.case("ooa.database/call_procedure", "rows from CustomerOrders",
                 "Call procedure runs a stored procedure and gives back the rows it produces. Checks: customer 1 "
                 "has 2 orders, the first for a Laptop stand.") as f:
        f.act("ooa.database/call_procedure", desc="Orders of customer 1", session=S, procedure="dbo.CustomerOrders",
              parameters=[1], out={"table": "db_t", "row_count": "db_rows"})
        ua.check_eq("db_rows", 2)
        f.act("ooa.datatable/get_value", desc="First title", table="$db_t$", row=0, column="Title",
              out={"value": "db_out"})
        ua.check_eq("db_out", "Laptop stand")

    with ua.case("ooa.database/call_procedure", "an output parameter from RaisePrice (rolled back)",
                 "Call procedure also gives back output parameters: RaisePrice raises a price by a percentage and "
                 "returns the new price. Run in a transaction that is rolled back. Checks: 1299.00 plus 10% is "
                 "1428.90.") as f:
        f.act("ooa.database/begin_transaction", desc="Start a transaction", session=S)
        f.act("ooa.database/call_procedure", desc="Raise SKU-100 by 10%", session=S, procedure="dbo.RaisePrice",
              parameters=["SKU-100", 10, {"value": None, "direction": "out", "type": "decimal", "name": "NewPrice"}],
              out={"out_parameters": "db_out"})
        f.act("ooa.database/rollback", desc="Undo it", session=S)
        ua.check("eq", "$db_out.NewPrice$", 1428.9, what="NewPrice")

    with ua.case("ooa.database/describe_table", "the columns of Products",
                 "Describe table gives the columns of a table with their types and sizes. Checks: Products has 4 "
                 "columns and Sku is one of them.") as f:
        f.act("ooa.database/describe_table", desc="Describe Products", session=S, table="Products",
              out={"table": "db_t"})
        f.act("ooa.datatable/row_count", desc="Number of columns", table="$db_t$", out={"count": "db_n"})
        ua.check_eq("db_n", 4)
        ua.check("contains", "${db_t}", "Sku", what="the description")

    with ua.case("ooa.database/list_tables", "the test tables",
                 "List tables gives the tables and views the connection can see, here in schema dbo. Checks: "
                 "Customers, Products and Orders are listed.") as f:
        f.act("ooa.database/list_tables", desc="Tables in dbo", session=S, schema="dbo", include_views=False,
              out={"table": "db_t"})
        for name in ("Customers", "Products", "Orders"):
            ua.check("contains", "${db_t}", name, what="the list of tables")

    with ua.case("ooa.database/export_to_file", "products to CSV",
                 "Export to file runs a SELECT and writes its rows to a CSV file without loading them into memory. "
                 "Checks: 4 rows, and the file has the header and SKU-100.") as f:
        f.act("ooa.database/export_to_file", desc="Write the products to a CSV file", session=S,
              sql="SELECT Sku, Stock FROM dbo.Products WHERE Price > ? ORDER BY Sku", parameters=[0],
              path="ut_tmp/db/products-out.csv", out={"row_count": "db_n", "path": "db_path"})
        ua.check_eq("db_n", 4)
        f.act("ooa.file/read_text", desc="Read the file", path="ut_tmp/db/products-out.csv", out={"text": "db_text"})
        ua.check("startsWith", "$db_text$", "Sku,Stock", what="the header")
        ua.check("contains", "$db_text$", "SKU-100,40", what="the file")

    with ua.case("ooa.database/disconnect", "the session is closed",
                 "Disconnect closes the session (uncommitted changes are rolled back). Checks: the session is no "
                 "longer open, and a query on it fails with SESSION.NOT_FOUND.") as f:
        f.act("ooa.database/disconnect", desc="Close the SQL Server session", session=S)
        f.set("db_open", False)
        with f.if_({"condition": "core/session_open", "args": {"session": S}}, desc="Still open?"):
            f.set("db_open", True)
        ua.check_eq("db_open", False)
        ua.expect_error("SESSION.NOT_FOUND", lambda ff: ff.act("ooa.database/query", desc="Query the closed session",
                                                                session=S, sql="SELECT 1"))
