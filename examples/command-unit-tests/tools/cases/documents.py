"""Document Automation cases for One CommandCenter: a made-up one-page invoice PDF is sent to the live process
ut_invoices (fields invoice_number and total), read, corrected, listed, marked collected, downloaded and deleted."""
from helpers_platform import drop, renumber, make_pdf, INVOICE_LINES, move_last_case_to

D = "ooa.documents/"
P = "ut_invoices"
PDF = "ut-docs/ut-invoice.pdf"


def add(ua):
    drop(ua, "outside One CommandCenter", "only PDF and pictures")   # the second uses the sample runner's {tmp}
    for v, t in (("dc_id", "String"), ("dc_id2", "String"), ("dc_status", "String"), ("dc_fields", "Dictionary"),
                 ("dc_done", "Boolean"), ("dc_text", "String"), ("dc_ids", "List"), ("dc_n", "Number"),
                 ("dc_path", "String"), ("dc_deleted", "Boolean"), ("dc_review", "Boolean"), ("dc_table", "Table")):
        ua.var(v, t)
    f = ua.f
    with ua.case(D + "send", "send an invoice and wait until it is read",
                 "Send document sends a PDF to a live Document Automation process; with wait on it comes back once "
                 "read, with its id, status and the fields read. The made-up invoice is written first."):
        f.act("ooa.file/write_text", desc="Write the made-up invoice PDF", path=PDF, text=make_pdf(INVOICE_LINES))
        f.act(D + "send", process=P, path=PDF, wait=True,
              out={"document_id": "dc_id", "status": "dc_status", "fields": "dc_fields", "needs_review": "dc_review"})
        ua.check("notEmpty", "$dc_id$", what="the document id")
        ua.check("in", "$dc_status$", ["success", "validation"], what="status")
        ua.check("contains", "$dc_fields$", "invoice_number", what="the fields read")
    move_last_case_to(ua, 1)

    with ua.case(D + "send", "only PDF and pictures are sent",
                 "Send document refuses other files before sending anything: a .txt fails with DOCS.FILE_TYPE."):
        f.act("ooa.file/write_text", path="ut-docs/notes.txt", text="not a document")
        ua.expect_error("DOCS.FILE_TYPE", lambda ff: ff.act(D + "send", process=P, path="ut-docs/notes.txt"))
    with ua.case(D + "wait_for_document", "send without waiting, then wait until read",
                 "Wait for document waits until a document sent without waiting is read, then gives its result: "
                 "done, and the file name sent."):
        f.act(D + "send", process=P, path=PDF, out={"document_id": "dc_id2"})
        f.act(D + "wait_for_document", document_id="$dc_id2$", until="read", timeout_seconds=600,
              check_every_seconds=5, out={"done": "dc_done", "file_name": "dc_text"})
        ua.check_eq("dc_done", True)
        ua.check_eq("dc_text", "ut-invoice.pdf")
    with ua.case(D + "get_result", "status, fields and confidence by id",
                 "Get result reads a document's status and what was read, by its id: done, the invoice number "
                 "INV-UT-1 and a confidence for it."):
        f.act(D + "get_result", document_id="$dc_id$", out={"done": "dc_done", "fields": "dc_fields"})
        ua.check_eq("dc_done", True)
        f.expr("dc_text", "$dc_fields.invoice_number")
        ua.check("contains", "$dc_text$", "INV-UT-1", what="the invoice number read")
    with ua.case(D + "update_result", "corrected values count as an approval",
                 "Update result writes corrected field values back; they are kept as a review by the run's user, so "
                 "the document is then approved (validated) with the corrected values."):
        f.act(D + "update_result", document_id="$dc_id$", fields={"invoice_number": "INV-UT-1", "total": 120.5},
              out={"status": "dc_status", "fields": "dc_fields"})
        ua.check_eq("dc_status", "validated")
        f.act(D + "get_result", document_id="$dc_id$", out={"reviewed": "dc_review", "fields": "dc_fields"})
        ua.check_eq("dc_review", True)
        f.expr("dc_n", "$dc_fields.total")
        ua.check("eq", "$dc_n$", 120.5, what="the corrected total")
    with ua.case(D + "list_finished", "the approved invoice is listed for a new collector",
                 "List finished documents lists the process's documents that need nothing more and that this "
                 "collector has not collected; the approved invoice is among them."):
        f.act(D + "list_finished", process=P, collector="ut_collector", out={"document_ids": "dc_ids", "count": "dc_n",
                                                                           "documents": "dc_table"})
        ua.check("contains", "$dc_ids$", "$dc_id$", what="the listed ids")
        ua.check("ge", "$dc_n$", 1, what="documents listed")
    with ua.case(D + "mark_collected", "mark once; the second time it was already collected",
                 "Mark collected marks documents for a collector: marked 1 the first time, already_collected 1 the "
                 "second; then List finished documents leaves it out for that collector."):
        f.act(D + "mark_collected", document_ids=["$dc_id$"], collector="ut_collector", out={"marked": "dc_n"})
        ua.check("eq", "$dc_n$", 1, what="marked")
        f.act(D + "mark_collected", document_ids="$dc_id$", collector="ut_collector", out={"already_collected": "dc_n"})
        ua.check("eq", "$dc_n$", 1, what="already collected")
        f.act(D + "list_finished", process=P, collector="ut_collector", out={"document_ids": "dc_ids"})
        ua.check("notContains", "$dc_ids$", "$dc_id$", what="the listed ids")
    with ua.case(D + "download", "results as JSON, kept in Document Automation",
                 "Download saves a document's results to a folder; with delete_after off the document stays. The "
                 "JSON holds the corrected invoice number."):
        f.act(D + "download", document_id="$dc_id$", kind="json", folder="ut-docs/out", delete_after=False,
              out={"path": "dc_path", "deleted": "dc_deleted"})
        ua.check_eq("dc_deleted", False)
        f.act("ooa.file/read_text", path="$dc_path$", out={"text": "dc_text"})
        ua.check("contains", "$dc_text$", "INV-UT-1", what="the JSON")
    with ua.case(D + "download", "tidy up: CSV results, then the documents are deleted",
                 "Download with delete_after on (the default) saves the results and deletes the document from "
                 "Document Automation; both test documents are removed this way."):
        f.act(D + "download", document_id="$dc_id$", kind="csv", folder="ut-docs/out", out={"deleted": "dc_deleted",
                                                                                         "path": "dc_path"})
        ua.check_eq("dc_deleted", True)
        ua.check("endsWith", "$dc_path$", ".csv", what="the saved file")
        f.act(D + "download", document_id="$dc_id2$", kind="original", folder="ut-docs/out", out={"deleted": "dc_deleted"})
        ua.check_eq("dc_deleted", True)
    renumber(ua)
