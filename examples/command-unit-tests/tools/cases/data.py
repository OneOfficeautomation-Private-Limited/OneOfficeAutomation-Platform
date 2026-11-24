"""Data store cases on the entity ut_customers and the bucket ut_files. Locally they are files in the workspace
(data-store/, buckets/); in One CommandCenter the entity (fields name, country, credit) and the bucket must exist.
Every record and file the agent makes is removed again."""
from helpers_platform import renumber

D = "ooa.data/"
E = "ut_customers"
B = "ut_files"


def _remove_test_records(ua, f):
    """Deletes every ut_customers record whose name starts with UT (left by an earlier run, or made just now)."""
    f.act(D + "query_records", desc="Find the test records", entity=E,
          filters=[{"field": "name", "op": "starts_with", "value": "UT "}], out={"records": "ds_records"})
    with f.loop(desc="Delete each test record", iterator="list", over="$ds_records$", **{"as": "ds_rec"}):
        f.act(D + "delete_record", entity=E, id="$ds_rec.id$")


def add(ua):
    for v, t in (("ds_id", "String"), ("ds_id2", "String"), ("ds_record", "Dictionary"), ("ds_records", "List"),
                 ("ds_rec", "Dictionary"), ("ds_table", "Table"), ("ds_total", "Number"), ("ds_text", "String"),
                 ("ds_n", "Number"), ("ds_deleted", "Boolean"), ("ds_path", "String"), ("ds_paths", "List"),
                 ("ds_file", "String"), ("ds_files", "Table")):
        ua.var(v, t)
    f = ua.f
    with ua.case(D + "create_record", "add a customer",
                 "Create record adds a record to an entity and gives back its id and the record as saved. Test "
                 "records of earlier runs are removed first."):
        _remove_test_records(ua, f)
        f.act(D + "create_record", entity=E, values={"name": "UT Contoso", "country": "IN", "credit": 5000},
              out={"id": "ds_id", "record": "ds_record"})
        ua.check("notEmpty", "$ds_id$", what="the new id")
        f.expr("ds_text", "$ds_record.name")
        ua.check_eq("ds_text", "UT Contoso")
    with ua.case(D + "get_record", "read the customer by id",
                 "Get record reads one record by its id: its id and fields."):
        f.act(D + "create_record", entity=E, values={"name": "UT Fabrikam", "country": "US", "credit": 1200},
              out={"id": "ds_id"})
        f.act(D + "get_record", entity=E, id="$ds_id$", out={"record": "ds_record"})
        f.expr("ds_text", "$ds_record.country")
        ua.check_eq("ds_text", "US")
        f.expr("ds_n", "$ds_record.credit")
        ua.check("eq", "$ds_n$", 1200, what="credit")
    with ua.case(D + "update_record", "change one field, clear another",
                 "Update record changes the fields given; fields not given keep their values and an empty value "
                 "clears a field."):
        f.act(D + "create_record", entity=E, values={"name": "UT Northwind", "country": "DE", "credit": 300},
              out={"id": "ds_id"})
        f.act(D + "update_record", entity=E, id="$ds_id$", values={"credit": 900, "country": ""},
              out={"record": "ds_record"})
        f.expr("ds_n", "$ds_record.credit")
        ua.check("eq", "$ds_n$", 900, what="the changed credit")
        f.expr("ds_text", "$ds_record.name")
        ua.check_eq("ds_text", "UT Northwind")
        ua.check("notContains", "$ds_record$", "country", what="the cleared field")
    with ua.case(D + "query_records", "filter, sort and count",
                 "Query records gives the records matching every filter, sorted, as a table and a list. The three "
                 "test customers made by the cases above, sorted by credit from the highest, start with Contoso; "
                 "with a second filter (credit over 1000) two are left."):
        f.act(D + "query_records", entity=E, filters=[{"field": "name", "op": "starts_with", "value": "UT "}],
              sort_by="credit", descending=True, out={"table": "ds_table", "records": "ds_records", "total": "ds_total"})
        ua.check("eq", "$ds_total$", 3, what="test records")
        f.expr("ds_text", "$ds_records[0].name")
        ua.check_eq("ds_text", "UT Contoso")
        f.act(D + "query_records", entity=E, filters=[{"field": "name", "op": "starts_with", "value": "UT "},
                                                     {"field": "credit", "op": ">", "value": 1000}],
              out={"total": "ds_total", "table": "ds_table"})
        ua.check("eq", "$ds_total$", 2, what="records with credit over 1000")
        f.expr("ds_n", "len($ds_table)")
        ua.check("eq", "$ds_n$", 2, what="table rows")
    with ua.case(D + "delete_record", "delete every test customer; a second delete changes nothing",
                 "Delete record removes a record (deleted true); deleting it again changes nothing (deleted false). "
                 "All test customers are removed, and a query finds none."):
        f.act(D + "create_record", entity=E, values={"name": "UT Temporary"}, out={"id": "ds_id2"})
        f.act(D + "delete_record", entity=E, id="$ds_id2$", out={"deleted": "ds_deleted"})
        ua.check_eq("ds_deleted", True)
        f.act(D + "delete_record", entity=E, id="$ds_id2$", out={"deleted": "ds_deleted"})
        ua.check_eq("ds_deleted", False)
        _remove_test_records(ua, f)
        f.act(D + "query_records", entity=E, filters=[{"field": "name", "op": "starts_with", "value": "UT "}],
              out={"total": "ds_total"})
        ua.check("eq", "$ds_total$", 0, what="test records left")

    with ua.case(D + "upload_file", "put a file at a path in the bucket",
                 "Upload file puts a file from the run's folder into a bucket at the path given, and gives back the "
                 "path and its size in bytes."):
        f.act("ooa.file/write_text", desc="A small file to upload", path="ut-data/invoice.txt", text="Invoice INV-77 total 120.50")
        f.act(D + "upload_file", file="ut-data/invoice.txt", bucket=B, path="ut/2026/invoice.txt",
              out={"path": "ds_path", "bytes": "ds_n"})
        ua.check_eq("ds_path", "ut/2026/invoice.txt")
        ua.check("eq", "$ds_n$", 27, what="bytes")
    with ua.case(D + "upload_file", "no overwrite when asked",
                 "With overwrite off, a path the bucket already has is refused with DATA.EXISTS."):
        f.act(D + "upload_file", file="ut-data/invoice.txt", bucket=B, path="ut/2026/invoice.txt")
        ua.expect_error("DATA.EXISTS", lambda ff: ff.act(D + "upload_file", file="ut-data/invoice.txt", bucket=B,
                                                          path="ut/2026/invoice.txt", overwrite=False))
    with ua.case(D + "list_files", "files under a prefix",
                 "List files gives the bucket's files whose path starts with the prefix, as a table and a list of "
                 "paths."):
        f.act(D + "upload_file", file="ut-data/invoice.txt", bucket=B, path="ut/2026/second.txt")
        f.act(D + "list_files", bucket=B, prefix="ut/2026/", out={"files": "ds_files", "paths": "ds_paths"})
        ua.check("contains", "$ds_paths$", "ut/2026/invoice.txt", what="the paths")
        ua.check("contains", "$ds_paths$", "ut/2026/second.txt", what="the paths")
        f.expr("ds_n", "len($ds_files)")
        ua.check("eq", "$ds_n$", 2, what="files listed")
    with ua.case(D + "download_file", "save a bucket file in the run's folder",
                 "Download file saves a file from the bucket where asked; its text is what was uploaded."):
        f.act(D + "download_file", bucket=B, path="ut/2026/invoice.txt", save_as="ut-data/downloaded.txt",
              out={"file": "ds_file"})
        f.act("ooa.file/read_text", path="$ds_file$", out={"text": "ds_text"})
        ua.check_eq("ds_text", "Invoice INV-77 total 120.50")
    with ua.case(D + "delete_file", "tidy up the bucket",
                 "Delete file removes a file from the bucket (deleted true, then false for one that is gone); both "
                 "test files are removed and the prefix lists nothing."):
        f.act(D + "delete_file", bucket=B, path="ut/2026/invoice.txt", out={"deleted": "ds_deleted"})
        ua.check_eq("ds_deleted", True)
        f.act(D + "delete_file", bucket=B, path="ut/2026/second.txt")
        f.act(D + "delete_file", bucket=B, path="ut/2026/invoice.txt", out={"deleted": "ds_deleted"})
        ua.check_eq("ds_deleted", False)
        f.act(D + "list_files", bucket=B, prefix="ut/", out={"paths": "ds_paths"})
        ua.check("empty", "$ds_paths$", what="files left")
    renumber(ua)
