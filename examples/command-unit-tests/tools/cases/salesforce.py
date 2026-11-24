"""Salesforce cases: a session opened by Authenticate, then records, SOQL, collections, describe and files against the
stand-in; at the end the session is closed, a step signs in on its own, and a spare token is revoked."""
from helpers_business import drop_samples, cred, url, vars_

LOGIN = url("salesforce")
NORTHWIND = "001UT0000000001AAA"
CONTOSO = "001UT0000000002AAA"


def add(ua):
    drop_samples(ua)
    vars_(ua, "sf_token", "sf_instance", "sf_table", "sf_count", "sf_total", "sf_soql", "sf_rec", "sf_id", "sf_created",
          "sf_results", "sf_ok", "sf_bad", "sf_fields", "sf_obj", "sf_files", "sf_path", "sf_version", "sf_doc",
          "sf_closed", "sf_spare")

    with ua.case("ooa.salesforce/authenticate", "client credentials on a custom sign-in site",
                 "Authenticate gets an access token from the connected app (client credentials) and keeps it, with the "
                 "instance address Salesforce gives back, as the Salesforce session for the next steps. Checks the "
                 "instance address and that a token came back.") as f:
        f.act("ooa.salesforce/authenticate", desc="Sign in to the stand-in", login_site="custom", login_url=LOGIN,
              grant_type="client_credentials", client_id="ut-consumer-key", client_secret=cred("salesforce", "client_secret"),
              out={"access_token": "sf_token", "instance_url": "sf_instance"})
        ua.check("contains", "$sf_instance$", "/biz/salesforce", what="the instance address")
        ua.check("notEmpty", "$sf_token$", what="the access token")

    with ua.case("ooa.salesforce/query", "SOQL with a parameter, in the session",
                 "Query (SOQL) runs a query and returns every matching record as a table; :ind is filled in safely "
                 "from parameters. No address or token on the step: the session gives them. Checks two Retail accounts.") as f:
        f.act("ooa.salesforce/query", desc="Retail accounts", soql="SELECT Id, Name FROM Account WHERE Industry = :ind ORDER BY Name",
              parameters={"ind": "Retail"}, out={"table": "sf_table", "count": "sf_count", "total_size": "sf_total"})
        ua.check_eq("sf_count", 2)
        ua.check_eq("sf_total", 2)

    with ua.case("ooa.salesforce/query", "deleted records too (queryAll)",
                 "With include_deleted on, Query also returns deleted records (queryAll). Checks the deleted "
                 "Healthcare account is found, with an apostrophe in its name passed as a parameter.") as f:
        f.act("ooa.salesforce/query", desc="Look for the deleted account", soql="SELECT Id, Name FROM Account WHERE Name = :name",
              parameters={"name": "O'Brien Pharmacy"}, include_deleted=True, out={"count": "sf_count"})
        ua.check_eq("sf_count", 1)

    with ua.case("ooa.salesforce/find_records", "any of two industries, sorted",
                 "Find records builds the SOQL from field values (a list means any of them) and runs it. Checks "
                 "three accounts are found and the SOQL that was run.") as f:
        f.act("ooa.salesforce/find_records", desc="Retail or Banking accounts", object="Account",
              conditions={"Industry": ["Retail", "Banking"]}, fields=["Id", "Name", "Industry"], order_by="Name ASC",
              out={"table": "sf_table", "count": "sf_count", "soql": "sf_soql"})
        ua.check_eq("sf_count", 3)
        ua.check("contains", "$sf_soql$", "Industry IN ('Retail', 'Banking')", what="the SOQL")

    with ua.case("ooa.salesforce/get_record", "by Salesforce id",
                 "Get record reads one record by its id, here only two fields. Checks the account's name.") as f:
        f.act("ooa.salesforce/get_record", desc="Read Northwind Traders", object="Account", id=NORTHWIND,
              fields=["Name", "Industry"], out={"record": "sf_rec"})
        ua.check("eq", "$sf_rec.Name$", "Northwind Traders", what="the name")
        ua.check("eq", "$sf_rec.Industry$", "Retail", what="the industry")

    with ua.case("ooa.salesforce/get_record", "by external id",
                 "Get record can also find the record by an external id field's value. Checks it is Contoso Bank.") as f:
        f.act("ooa.salesforce/get_record", desc="Read the account with ERP number ERP-1002", object="Account",
              id="ERP-1002", external_id_field="ERP_Number__c", out={"record": "sf_rec"})
        ua.check("eq", "$sf_rec.Id$", CONTOSO, what="the record id")

    with ua.case("ooa.salesforce/create_record", "a new case",
                 "Create record makes a record and returns its id. The case reads it back to check the subject.") as f:
        f.act("ooa.salesforce/create_record", desc="Create a case", object="Case",
              fields={"Subject": "UT printer jam", "Priority": "High"}, out={"id": "sf_id"})
        ua.check("notEmpty", "$sf_id$", what="the new id")
        f.act("ooa.salesforce/get_record", desc="Read the new case", object="Case", id="$sf_id$", out={"record": "sf_rec"})
        ua.check("eq", "$sf_rec.Subject$", "UT printer jam", what="the subject")

    with ua.case("ooa.salesforce/update_record", "change one field",
                 "Update record changes fields of an existing record. The case creates an account, changes its "
                 "rating and reads it back.") as f:
        f.act("ooa.salesforce/create_record", desc="Create an account to change", object="Account",
              fields={"Name": "UT Update Ltd", "Industry": "UT-Update", "Rating": "Cold"}, out={"id": "sf_id"})
        f.act("ooa.salesforce/update_record", desc="Make the rating Hot", object="Account", id="$sf_id$", fields={"Rating": "Hot"})
        f.act("ooa.salesforce/get_record", desc="Read it back", object="Account", id="$sf_id$", fields=["Rating"], out={"record": "sf_rec"})
        ua.check("eq", "$sf_rec.Rating$", "Hot", what="the rating")

    with ua.case("ooa.salesforce/delete_record", "delete, then it cannot be read",
                 "Delete record sends a record to the recycle bin. The case creates an account, deletes it and checks "
                 "that reading it fails.") as f:
        f.act("ooa.salesforce/create_record", desc="Create an account to delete", object="Account",
              fields={"Name": "UT Delete Ltd", "Industry": "UT-Delete"}, out={"id": "sf_id"})
        f.act("ooa.salesforce/delete_record", desc="Delete it", object="Account", id="$sf_id$")
        ua.expect_error("SALESFORCE.*", lambda ff: ff.act("ooa.salesforce/get_record", desc="Try to read the deleted account",
                                                         object="Account", id="$sf_id$", out={"record": "sf_rec"}))

    with ua.case("ooa.salesforce/upsert_by_external_id", "existing external id is updated",
                 "Upsert by external id updates the record whose external id matches. Checks created is false and "
                 "the new rating is stored.") as f:
        f.act("ooa.salesforce/upsert_by_external_id", desc="Upsert ERP-1003", object="Account", external_id_field="ERP_Number__c",
              external_id_value="ERP-1003", fields={"Rating": "Warm"}, out={"id": "sf_id", "created": "sf_created"})
        ua.check_eq("sf_created", False)
        f.act("ooa.salesforce/get_record", desc="Read it back", object="Account", id="ERP-1003", external_id_field="ERP_Number__c",
              out={"record": "sf_rec"})
        ua.check("eq", "$sf_rec.Rating$", "Warm", what="the rating")

    with ua.case("ooa.salesforce/upsert_by_external_id", "new external id creates a record",
                 "When no record has the external id, Upsert creates one. Checks created is true; the case then "
                 "deletes the record so the next run starts the same way.") as f:
        f.act("ooa.salesforce/upsert_by_external_id", desc="Upsert ERP-NEW-1", object="Account", external_id_field="ERP_Number__c",
              external_id_value="ERP-NEW-1", fields={"Name": "UT Upsert Ltd", "Industry": "UT-Upsert"},
              out={"id": "sf_id", "created": "sf_created"})
        ua.check_eq("sf_created", True)
        ua.check("notEmpty", "$sf_id$", what="the new id")
        f.act("ooa.salesforce/delete_record", desc="Tidy up", object="Account", id="$sf_id$")

    with ua.case("ooa.salesforce/save_records", "create many, one row is refused",
                 "Save many records writes the rows of a table as records (200 a call) and gives a result per row. "
                 "One row has no Name, so Salesforce refuses it. Checks 2 written and 1 failed.") as f:
        f.act("ooa.salesforce/save_records", desc="Create three accounts", object="Account", operation="create",
              records={"columns": ["Name", "Industry"], "rows": [["UT Batch One", "UT-Batch"], ["UT Batch Two", "UT-Batch"], ["", "UT-Batch"]]},
              out={"results": "sf_results", "succeeded": "sf_ok", "failed": "sf_bad"})
        ua.check_eq("sf_ok", 2)
        ua.check_eq("sf_bad", 1)

    with ua.case("ooa.salesforce/save_records", "upsert by external id",
                 "With operation upsert, rows are matched on the external id field: ERP-1001 exists and is updated. "
                 "Checks one record written and the rating stored.") as f:
        f.act("ooa.salesforce/save_records", desc="Upsert one account", object="Account", operation="upsert",
              external_id_field="ERP_Number__c",
              records={"columns": ["ERP_Number__c", "Rating"], "rows": [["ERP-1001", "Hot"]]},
              out={"succeeded": "sf_ok", "failed": "sf_bad"})
        ua.check_eq("sf_ok", 1)
        f.act("ooa.salesforce/get_record", desc="Read it back", object="Account", id=NORTHWIND, fields=["Rating"], out={"record": "sf_rec"})
        ua.check("eq", "$sf_rec.Rating$", "Hot", what="the rating")

    with ua.case("ooa.salesforce/describe_object", "fields of Account",
                 "Describe object reads an object's fields (labels, types, required, picklist values, lookups) and "
                 "facts about the object. Checks the key prefix and that five fields are listed.") as f:
        f.act("ooa.salesforce/describe_object", desc="Describe Account", object="Account", out={"fields": "sf_fields", "object": "sf_obj"})
        ua.check("eq", "$sf_obj.key_prefix$", "001", what="the key prefix")
        ua.check("eq", "$sf_obj.label_plural$", "Accounts", what="the plural label")

    with ua.case("ooa.salesforce/get_attachments", "list a record's files and attachments",
                 "Get attachments lists the Salesforce Files and classic attachments on a record. Checks two files "
                 "and one attachment are found.") as f:
        f.act("ooa.salesforce/get_attachments", desc="List what Northwind Traders has", record_id=NORTHWIND,
              out={"table": "sf_table", "count": "sf_count"})
        ua.check_eq("sf_count", 3)

    with ua.case("ooa.salesforce/get_attachments", "download only the PDF",
                 "With a download folder and an extension filter, only matching files are saved. Checks one file was "
                 "saved under its name.") as f:
        f.act("ooa.salesforce/get_attachments", desc="Download PDFs of Northwind Traders", record_id=NORTHWIND,
              download_folder="ut_salesforce_files", extension="pdf", out={"count": "sf_count", "files": "sf_files", "path": "sf_path"})
        ua.check_eq("sf_count", 1)
        ua.check("contains", "$sf_path$", "Signed contract.pdf", what="the saved file")

    with ua.case("ooa.salesforce/get_attachments", "one file by its id",
                 "Given a file id (069...), Get attachments looks at just that file, with no record id. Checks it is "
                 "the price list.") as f:
        f.act("ooa.salesforce/get_attachments", desc="Look at file 069UT0000000002AAA", content_document_id="069UT0000000002AAA",
              out={"count": "sf_count", "table": "sf_table"})
        ua.check_eq("sf_count", 1)

    with ua.case("ooa.salesforce/upload_attachment", "upload, then a new version",
                 "Upload attachment uploads a file and links it to a record with the chosen visibility; given the "
                 "file id, a second upload becomes a new version of the same file. Checks both ids.") as f:
        f.act("ooa.file/write_text", desc="Prepare a file", path="ut_sf_quote.txt", text="Quote 42: 3 printers")
        f.act("ooa.salesforce/upload_attachment", desc="Attach it to Contoso Bank", record_id=CONTOSO, path="ut_sf_quote.txt",
              title="Quote 42", visibility="AllUsers", out={"content_version_id": "sf_version", "content_document_id": "sf_doc"})
        ua.check("notEmpty", "$sf_version$", what="the version id")
        ua.check("notEmpty", "$sf_doc$", what="the file id")
        f.act("ooa.file/write_text", desc="Change the file", path="ut_sf_quote.txt", text="Quote 42: 4 printers")
        f.act("ooa.salesforce/upload_attachment", desc="Upload it as a new version", content_document_id="$sf_doc$",
              path="ut_sf_quote.txt", out={"content_document_id": "sf_id"})
        ua.check("eq", "$sf_id$", "$sf_doc$", what="the file id of the new version")

    with ua.case("ooa.salesforce/end_session", "forget the session, then sign in on the step",
                 "End session forgets the Salesforce session; later steps give their own instance address and token. "
                 "Checks a session was open, then queries with the step's own sign-in.") as f:
        f.act("ooa.salesforce/end_session", desc="Close the Salesforce session", out={"closed": "sf_closed"})
        ua.check_eq("sf_closed", True)
        f.act("ooa.salesforce/query", desc="Query with the step's own address and token", instance_url=LOGIN,
              access_token=cred("salesforce", "token"), soql="SELECT Id FROM Account WHERE Industry = 'Banking'", out={"count": "sf_count"})
        ua.check_eq("sf_count", 1)

    with ua.case("ooa.salesforce/revoke_authentication", "a revoked token no longer works",
                 "Revoke authentication makes a token unusable. The case signs in for a spare token, revokes it, and "
                 "checks a query with it is refused.") as f:
        f.act("ooa.salesforce/authenticate", desc="Get a spare token", login_site="custom", login_url=LOGIN,
              client_id="ut-consumer-key", client_secret=cred("salesforce", "client_secret"), session="sf_spare",
              out={"access_token": "sf_spare"})
        f.act("ooa.salesforce/revoke_authentication", desc="Revoke the spare token", instance_url=LOGIN, access_token="$sf_spare$")
        ua.expect_error("SALESFORCE.*", lambda ff: ff.act("ooa.salesforce/query", desc="Query with the revoked token",
                                                         instance_url=LOGIN, access_token="$sf_spare$",
                                                         soql="SELECT Id FROM Account", out={"count": "sf_count"}))
        f.act("ooa.salesforce/end_session", desc="Close the spare session", session="sf_spare")
