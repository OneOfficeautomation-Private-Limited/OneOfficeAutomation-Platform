"""RW03 Procure-to-pay: invoice mail -> PDF/OCR -> PO and receipt match -> approvals -> SAP QA parked draft -> Excel close-out.

Payment and posting are forbidden: the SAP stand-in refuses them, and B5 and B6 check that none happened.
Every RW03 agent keys its records by the invoice mail's id (AAMkAP0001 ... AAMkAP0010), so the children's evidence lines up.
"""
from common import Child, Main, cred, evidence_var, fault_setup, graph, save_evidence

FOLDER = "RW03 Procure-to-pay"
MAILBOX = "ap-qa@ooa-qa.test"
RETRY = {"max": 3, "backoff": "exponential", "delayMs": 1000}   # bounded retry for the stand-in calls (slot 10)

PARSE_LINES = r'''"""Reads invoice lines (SKU, quantity, unit price, amount) from the invoice text on standard input; prints JSON.
Numbers are read the invoice's way: 1.234,50 (German) or 1,234.50 (English)."""
import json, re, sys
text = sys.stdin.read()
de = bool(re.search(r"\d\.\d{3},\d{2}\b|(?<![\d.,])\d+,\d{2}\b", text))
def num(s):
    s = s.strip()
    s = s.replace(".", "").replace(",", ".") if de else s.replace(",", "")
    return round(float(s), 4)
lines = [{"sku": m.group(1), "qty": int(m.group(2)), "unit_price": num(m.group(3)), "amount": num(m.group(4))}
         for m in re.finditer(r"^(SKU-\w+)\s+(\d+)\s+([\d.,]+)\s+([\d.,]+)\s*$", text, re.M)]
tax = re.search(r"Tax\s+(\d+)%", text)
print(json.dumps({"locale": "de-DE" if de else "en-US", "tax_rate": int(tax.group(1)) if tax else None, "lines": lines}))
'''


def run_file(code, name="evidence.csv"):
    return "${base}/output/${run_id}/" + code + "/" + name


def read_prev(f, code, var, name="evidence.csv", what="evidence"):
    """Read an earlier child's output of this run; run on its own, read the fixture (a saved copy from a checked run)."""
    with f.if_({"condition": "ooa.file/exists", "args": {"path": run_file(code, name)}}, desc=f"{code} ran in this run?") as has:
        f.act("ooa.csv/read", desc=f"Read {code} {what}", path=run_file(code, name), out={"table": var})
    with f.else_(has):
        f.comment(f"Running on its own: use the fixture, a saved copy of {code}'s {what} from a checked run.")
        f.act("ooa.csv/read", desc=f"Read {code} {what} (fixture)", path="${base}/data/RW03/fixtures/" + code + "-" + name, out={"table": var})


def find_python(f):
    """The runner's PATH may not have Python: look in the per-user install folder first (as the stand-in starter does)."""
    for n, t, d in [("local", "String", ""), ("pys", "List", None), ("pyf", "String", ""), ("py", "String", "python")]:
        f.var(n, t, "local", d)
    f.act("ooa.system/get_environment_variable", desc="Where per-user programs are", name="LOCALAPPDATA", out={"value": "local"})
    f.act("ooa.folder/list_subfolders", desc="Installed Pythons (one folder each)", path="${local}/Programs/Python", cont=True, out={"folders": "pys"})
    with f.loop(desc="Each installed Python", iterator="list", over={"expr": "coalesce($pys$, [])"}, **{"as": "pyf"}):
        with f.if_({"condition": "ooa.file/exists", "args": {"path": "${pyf}/python.exe"}}, desc="Has python.exe at its top?"):
            f.set("py", "${pyf}/python.exe", desc="Use it")


# ------------------------------------------------------------------ B1
def b1_mail_intake():
    c = Child("RW03", 1, "Invoice mail intake",
              "Reads the 10 tagged invoice mails of the QA accounts-payable mailbox, checks each sender against the supplier allow-list, routes "
              "mail without an attachment, saves the PDF under the run folder, enforces type and size, drops duplicates by file hash, checks "
              "the invoice period is open, creates a case id per invoice and checkpoints it; the accepted PDFs land in the intake folder.",
              "10 synthetic invoice mails", record_key="id")
    f = c.f
    for n, t, d in [("email", "Dictionary", None), ("invoice", "String", ""), ("supplier_id", "String", ""), ("case_id", "String", ""),
                    ("files", "List", None), ("pdf", "String", ""), ("info", "Dictionary", None), ("sha", "String", ""), ("seen", "List", []),
                    ("period", "String", ""), ("cfg", "Dictionary", None), ("suppliers", "Table", None), ("intake", "String", ""),
                    ("arrived", "Dictionary", None), ("handed", "Number", 0)]:
        f.var(n, t, "local", d)
    evidence_var(f, ["key", "case_id", "invoice", "sender", "supplier_id", "file", "hash", "size_bytes", "received", "period"])
    c.start()
    with c.prepare("Read the 10 tagged invoice mails (oldest first), the supplier allow-list and the open accounting period."):
        fault_setup(f, "graph")
        f.act("ooa.microsoft365/list_mail", desc="List the tagged invoice mails of the AP mailbox", folder="inbox", subject_contains="OOA-QA invoice",
              oldest_first=True, max_results=10, retry=RETRY, out={"table": "records"}, **graph(MAILBOX))
        f.act("ooa.csv/read", desc="Read the supplier master (sender allow-list)", path="${base}/data/RW03/suppliers.csv", out={"table": "suppliers"})
        f.act("ooa.json/read_file", desc="Read the open accounting period", path="${base}/data/RW03/period.json", out={"value": "cfg"})
        f.set("intake", "${out_dir}/intake", desc="Intake folder: the accepted PDFs, one per case")
        f.act("ooa.folder/create", desc="Create the intake folder", path="$intake$")
    with c.records("Per mail: read it, check the sender, route a missing attachment, save the PDF, check type and size, hash and drop duplicates, "
                   "check the period, make the case id, hand the PDF to intake and checkpoint.",
                   summary="${invoice} from ${rec.from}\nCase: ${case_id}"):
        f.act("ooa.microsoft365/get_mail", desc="1. Read the tagged message", id="$rec.id$", mark_read=False, body_as_text=True, retry=RETRY,
              out={"email": "email"}, **graph(MAILBOX))
        f.expr("invoice", "trim(replace($rec.subject$, 'OOA-QA invoice', ''))", desc="Invoice number from the subject")
        f.act("ooa.datatable/lookup", desc="2. Validate the sender against the test allow-list", table="$suppliers$", column="sender", value="$rec.from$",
              return_column="id", if_not_found="", out={"value": "supplier_id"})
        with f.if_({"op": "empty", "left": "$supplier_id$"}, desc="Sender not on the allow-list?"):
            f.act("ooa.microsoft365/move_mail", desc="Route it to Rejected", id="$rec.id$", to_folder="rejected", **graph(MAILBOX))
            f.act("core/throw", desc="Route the unknown sender", code="ROUTE.UNKNOWN_SENDER", message="${rec.from} is not an allow-listed supplier sender")
        with f.if_({"op": "ne", "left": "${rec.has_attachments}", "right": "true"}, desc="8. No attachment?"):
            f.act("ooa.microsoft365/move_mail", desc="Route it to Missing attachment", id="$rec.id$", to_folder="missing-attachment", **graph(MAILBOX))
            f.act("core/throw", desc="Route the mail", code="ROUTE.MISSING_ATTACHMENT", message="${invoice} from ${rec.from} came without the invoice PDF")
        f.act("ooa.microsoft365/save_mail_attachments", desc="3. Save the attachment under the run folder", id="$rec.id$",
              target_folder="${out_dir}/mail/${rec.id}", overwrite=True, include_inline=False, retry=RETRY, out={"files": "files"}, **graph(MAILBOX))
        f.expr("pdf", "first($files$)", desc="The saved file")
        f.act("ooa.file/get_info", desc="5. Read its type and size", path="$pdf$", out={"info": "info"})
        with f.if_("lower($info.extension$) != 'pdf' or toNumber($info.size_bytes$) > 5242880", desc="Not a PDF, or over 5 MB?"):
            f.act("ooa.microsoft365/move_mail", desc="Route it to Rejected", id="$rec.id$", to_folder="rejected", **graph(MAILBOX))
            f.act("core/throw", desc="Reject the attachment", code="VALIDATION.ATTACHMENT", message="${info.name}: only PDF files up to 5 MB are accepted")
        f.act("ooa.crypto/hash_file", desc="4. Hash the PDF (SHA-256)", path="$pdf$", out={"hash": "sha"})
        with f.if_({"op": "contains", "left": "$seen$", "right": "$sha$"}, desc="Same file seen already?"):
            f.act("ooa.microsoft365/move_mail", desc="Route the duplicate", id="$rec.id$", to_folder="duplicates", **graph(MAILBOX))
            f.act("core/throw", desc="Route the duplicate", code="ROUTE.DUPLICATE", message="${invoice}: the same PDF arrived before (hash ${substring($sha$, 0, 12)})")
        f.act("ooa.list/add_item", desc="Remember the hash", list="$seen$", item="$sha$", out={"list": "seen"})
        f.expr("period", "formatDate($rec.received$, '%Y-%m')", desc="7. Accounting period of the invoice mail")
        with f.if_({"op": "ne", "left": "$period$", "right": "$cfg.open_period$"}, desc="Period closed?"):
            f.act("core/throw", desc="Hold it for the next period", code="VALIDATION.PERIOD_CLOSED", message="${invoice} belongs to ${period}; open period is ${cfg.open_period}")
        f.set("case_id", "OOA-QA-${run_id}-AP${substring($rec.id$, 6)}", desc="9. Create the case id (6. the mail id is kept with it)")
        f.act("ooa.file/copy", desc="Hand the PDF to the intake folder", source="$pdf$", target="${intake}/${case_id}.pdf", overwrite=True)
        f.act("ooa.datatable/add_row", desc="10. Checkpoint: the case row", table="$evidence$",
              values={"key": "$rec.id$", "case_id": "$case_id$", "invoice": "$invoice$", "sender": "$rec.from$", "supplier_id": "$supplier_id$",
                      "file": "${intake}/${case_id}.pdf", "hash": "$sha$", "size_bytes": "$info.size_bytes$", "received": "$rec.received$", "period": "$period$"},
              out={"table": "evidence"})
        save_evidence(f)
        f.act("ooa.microsoft365/mark_mail", desc="Check the mail off (read)", id="$rec.id$", status="read", **graph(MAILBOX))
        f.act("ooa.log/write", desc="Log the checkpoint", path="$log_file$", message="${case_id}: ${invoice} from ${supplier_id}, mail ${rec.id}, hash ${substring($sha$, 0, 12)}")
        f.set("rec_note", "${case_id}", desc="Note for the results")
    with c.finish("Confirm every accepted PDF is in the intake folder (a trigger loop sees each file arrive), then save the results."):
        f.comment("The same Each-new-file loop a watching agent would use; it sees the PDFs already there and stops after the accepted count or 10 seconds.")
        with f.loop(desc="Each PDF in the intake folder", iterator="ooa.triggerloop/file_arrives",
                    iterator_args={"folder": "$intake$", "pattern": "*.pdf", "include_existing": True, "settle_seconds": 1, "poll_seconds": 1,
                                   "max_events": {"expr": "max($processed$, 1)"}, "max_seconds": 10}, **{"as": "arrived"}):
            f.expr("handed", "$handed$ + 1", desc="Count it")
            f.act("ooa.log/write", desc="Log the hand-over", path="$log_file$", message="Intake: ${arrived.name} ready for extraction")
        with f.if_("$handed$ != $processed$", desc="Intake folder does not match the accepted mails?"):
            f.act("ooa.log/write", desc="Flag it", path="$log_file$", level="WARN", message="Intake holds ${handed} PDFs for ${processed} accepted mails")
        save_evidence(f)
    return c


# ------------------------------------------------------------------ B2
def b2_extraction():
    c = Child("RW03", 2, "PDF and OCR extraction",
              "Opens each accepted invoice PDF, reads its text and (as a cross-check) the OCR text of the page picture, asks the scripted AI model "
              "for the header fields with confidence, reads the lines with a Python script, parses the numbers the invoice's way (German or "
              "English), recomputes lines, tax and total, applies the confidence threshold, rejects no-PO and duplicate invoices and returns typed fields.",
              "10 invoices (the accepted ones)", data_file="RW03/invoices.csv")
    f = c.f
    for n, t, d in [("b1", "Table", None), ("pdf", "String", ""), ("text", "String", ""), ("pages", "Number", 0), ("imgs", "List", None),
                    ("ocr", "String", ""), ("ai", "Dictionary", None), ("conf", "Dictionary", None), ("review", "Boolean", False),
                    ("parsed", "Any", None), ("ln", "Dictionary", None), ("net", "Number", 0), ("tax", "Number", 0), ("total", "Number", 0),
                    ("sum_lines", "Number", 0), ("ok", "Boolean", False), ("dup_key", "String", ""), ("seen", "List", []), ("how", "String", ""),
                    ("lines", "Table", {"columns": ["key", "invoice", "sku", "qty", "unit_price", "amount"], "rows": []})]:
        f.var(n, t, "local", d)
    f.var("threshold", "Number", "input", 0.8, "AI confidence below this sends a field to a person (unless the bot can prove it by calculation).")
    evidence_var(f, ["key", "invoice", "supplier", "po", "currency", "invoice_date", "locale", "net", "tax_rate", "tax", "total", "line_count",
                     "ai_review", "how", "page_image"])
    f.var("lines_file", "String", "output", "", "The invoice lines (one row per line).")
    c.start()
    with c.prepare("Use the PDFs intake accepted (or all 10 when run on its own), find Python and write the line reader script."):
        fault_setup(f, "ai")
        with f.if_({"condition": "ooa.file/exists", "args": {"path": run_file("RW03-B1")}}, desc="Intake ran in this run?") as has:
            f.act("ooa.csv/read", desc="Read what intake accepted", path=run_file("RW03-B1"), out={"table": "records"})
        with f.else_(has):
            f.comment("Running on its own: all 10 invoice PDFs of the test data.")
        find_python(f)
        f.act("ooa.file/write_text", desc="Write the line reader (Python)", path="${out_dir}/parse_lines.py", text=PARSE_LINES)
        f.act("ooa.folder/create", desc="Folder for the page pictures (evidence)", path="${out_dir}/pages")
    with c.records("Per invoice: text, page picture and OCR, AI fields with confidence, Python lines, locale numbers, totals, threshold, PO and duplicate checks.",
                   summary="${rec.invoice}: ${ai.supplier}\nPO ${ai.po_number}  ${ai.currency} ${total}\nAI review: ${review} (${how})"):
        f.expr("pdf", "if(contains($rec.file$, ':'), $rec.file$, $base$ + '/data/' + $rec.file$)", desc="1. Open the PDF (intake folder, or test data)")
        f.act("ooa.pdf/extract_text", desc="2. Extract the text", path="$pdf$", mode="plain", out={"text": "text", "page_count": "pages"})
        with f.if_("$pages$ == 0 or isEmpty(trim($text$))", desc="Malformed (no pages or no text)?"):
            f.act("core/throw", desc="Reject it", code="ROUTE.MALFORMED", message="${rec.invoice}: the PDF has no readable text")
        f.act("ooa.pdf/to_images", desc="6. Page picture as evidence", path="$pdf$", pages="1", target_folder="${out_dir}/pages", file_prefix="${rec.key}-",
              dpi=150, color="gray", out={"files": "imgs"})
        f.act("ooa.ocr/read_image", desc="2. OCR the page picture (cross-check)", path="${first($imgs$)}", out={"text": "ocr"})
        f.act("ooa.genai/extract", desc="3. Identify supplier, invoice, date, PO, currency and totals (scripted model, personal data masked)", text="$text$",
              fields={"supplier": "supplier name", "invoice_number": "invoice number", "invoice_date": "invoice date", "po_number": "purchase order number",
                      "currency": "ISO currency code", "net": "net amount as written", "tax": "tax amount as written", "total": "total amount as written"},
              provider="openai_compatible", model="qa-scripted", api_key=cred("ai"), base_url="${standin}/v1", min_confidence="$threshold$", mask_pii=True,
              retry=RETRY, out={"fields": "ai", "confidence": "conf", "needs_review": "review"})
        with f.if_("isEmpty(coalesce($ai.po_number$, ''))", desc="No PO number?"):
            f.act("core/throw", desc="Reject: no PO, no payment", code="ROUTE.NO_PO", message="${rec.invoice} has no purchase order number: rejected to the supplier")
        f.set("dup_key", "${ai.supplier}|${ai.invoice_number}", desc="Supplier and invoice number")
        with f.if_({"op": "contains", "left": "$seen$", "right": "$dup_key$"}, desc="8. Same supplier invoice seen already?"):
            f.act("core/throw", desc="Reject the duplicate", code="ROUTE.DUPLICATE", message="${ai.invoice_number} of ${ai.supplier} was already extracted")
        f.act("ooa.list/add_item", desc="Remember it", list="$seen$", item="$dup_key$", out={"list": "seen"})
        f.act("ooa.script/run_python", desc="3. Read the lines (Python)", file="${out_dir}/parse_lines.py", input="$text$", interpreter="$py$",
              out={"json": "parsed"})
        f.act("ooa.string/to_number", desc="4. Net, read the invoice's way", text="$ai.net$", locale="$parsed.locale$", out={"result": "net"})
        f.act("ooa.string/to_number", desc="4. Tax, read the invoice's way", text="$ai.tax$", locale="$parsed.locale$", out={"result": "tax"})
        f.act("ooa.string/to_number", desc="4. Total, read the invoice's way", text="$ai.total$", locale="$parsed.locale$", out={"result": "total"})
        f.set("sum_lines", 0, desc="Start the line sum")
        with f.loop(desc="Each invoice line", iterator="list", over="$parsed.lines$", **{"as": "ln"}):
            f.expr("sum_lines", "$sum_lines$ + toNumber($ln.amount$)", desc="5. Add the line amount")
            f.act("ooa.datatable/add_row", desc="Keep the line", table="$lines$",
                  values={"key": "$rec.key$", "invoice": "$ai.invoice_number$", "sku": "$ln.sku$", "qty": "$ln.qty$", "unit_price": "$ln.unit_price$",
                          "amount": "$ln.amount$"}, out={"table": "lines"})
        f.expr("ok", "abs($sum_lines$ - $net$) <= 0.02 and abs(round($net$ * toNumber($parsed.tax_rate$) / 100, 2) - $tax$) <= 0.02 and abs($net$ + $tax$ - $total$) <= 0.02",
               desc="5. Lines add up to the net, tax and total")
        with f.if_("not $ok$", desc="Totals do not add up?"):
            f.act("core/throw", desc="Send it to a person", code="ROUTE.MANUAL_REVIEW", message="${rec.invoice}: lines ${sum_lines}, net ${net}, tax ${tax}, total ${total} do not add up")
        f.set("how", "model sure", desc="The model was sure")
        with f.if_("$review$", desc="7. Model below the confidence threshold?"):
            f.comment("Low confidence (here: German number format). The bot proved net, tax and total by recomputing them from the lines, "
                      "so it accepts them; had they not added up, the invoice would have gone to manual review above.")
            f.set("how", "low confidence, proved by calculation", desc="Accepted on the calculation")
            f.act("ooa.log/write", desc="Note the low confidence", path="$log_file$", level="WARN",
                  message="${rec.invoice}: model confidence total ${conf.total}; proved by recomputing the lines")
        with f.if_("not contains($ocr$, $ai.invoice_number$)", desc="OCR text lacks the invoice number?"):
            f.act("ooa.log/write", desc="Note the OCR difference", path="$log_file$", level="WARN", message="${rec.invoice}: OCR did not read the invoice number")
        f.act("ooa.datatable/add_row", desc="9. Return the typed fields", table="$evidence$",
              values={"key": "$rec.key$", "invoice": "$ai.invoice_number$", "supplier": "$ai.supplier$", "po": "$ai.po_number$", "currency": "$ai.currency$",
                      "invoice_date": "$ai.invoice_date$", "locale": "$parsed.locale$", "net": "$net$", "tax_rate": "$parsed.tax_rate$", "tax": "$tax$",
                      "total": "$total$", "line_count": {"expr": "len($parsed.lines$)"}, "ai_review": "$review$", "how": "$how$",
                      "page_image": "${first($imgs$)}"}, out={"table": "evidence"})
        f.set("rec_note", "${ai.currency} ${total} (${how})", desc="Note for the results")
    with c.finish():
        save_evidence(f)
        f.set("lines_file", "${out_dir}/lines.csv", desc="Name of the lines file")
        f.act("ooa.datatable/write_to_file", desc="Save the invoice lines", table="$lines$", path="$lines_file$")
    return c


# ------------------------------------------------------------------ B3
def b3_matcher():
    c = Child("RW03", 3, "PO and receipt matcher",
              "Loads the supplier, PO and receipt records into the QA database, fuzzy-matches the supplier name to the master (aliases too), joins "
              "invoice lines to PO lines by SKU, checks the agreed price with the ERP price service (SOAP), works out quantity, price and tax "
              "variance, converts to EUR at the fixed rates, branches by the tolerances, never matches a PO twice and gives the decision and its reason.",
              "10 invoice dossiers (the extracted ones)")
    f = c.f
    for n, t, d in [("sup", "Table", None), ("srow", "Dictionary", None), ("alias", "Dictionary", {}), ("cands", "List", []), ("al", "String", ""),
                    ("rates", "Dictionary", None), ("lines", "Table", None), ("found", "Boolean", False), ("match", "String", ""), ("score", "Number", 0),
                    ("supplier_id", "String", ""), ("po_lines", "Table", None), ("n", "Number", 0), ("inv_lines", "Table", None), ("joined", "Table", None),
                    ("jn", "Number", 0), ("ni", "Number", 0), ("ln", "Dictionary", None), ("erp", "List", None), ("var", "Number", 0), ("max_var", "Number", 0),
                    ("short", "Number", 0), ("tax_var", "Number", 0), ("eur", "Number", 0), ("decision", "String", ""), ("route", "String", ""),
                    ("reason", "String", ""), ("matched", "List", []), ("cc", "String", ""), ("db", "String", ""), ("po_supplier", "String", "")]:
        f.var(n, t, "local", d)
    f.var("price_tolerance", "Number", "input", 2, "Price variance (per cent) above which the invoice goes to the buyer.")
    evidence_var(f, ["key", "invoice", "supplier_id", "match_score", "po", "cost_center", "currency", "total", "amount_eur", "max_price_var",
                     "qty_short", "tax_var", "decision", "route", "reason"])
    c.start()
    with c.prepare("Read what extraction returned, load the ERP master data into the QA database (embedded H2) and list the supplier names and aliases."):
        fault_setup(f, "soap")
        read_prev(f, "RW03-B2", "records")
        read_prev(f, "RW03-B2", "lines", "lines.csv", "invoice lines")
        f.act("ooa.json/read_file", desc="Read the fixed exchange rates (automated runs use fixed rates)", path="${base}/data/RW03/rates.json", out={"value": "rates"})
        f.set("db", "${out_dir}/erp", desc="QA database file of this run")
        f.act("ooa.database/connect", desc="Open the QA database (embedded H2; a live run points at the SQL Server QA copy)", database_type="h2", file="$db$", session="erp")
        for table, cols in [("suppliers", "id VARCHAR(20), name VARCHAR(80), aliases VARCHAR(200), sender VARCHAR(80), currency VARCHAR(3)"),
                            ("pos", "po VARCHAR(20), supplier VARCHAR(20), sku VARCHAR(20), qty DECIMAL(12,3), unit_price DECIMAL(12,4), currency VARCHAR(3), cost_center VARCHAR(10)"),
                            ("receipts", "po VARCHAR(20), sku VARCHAR(20), received DECIMAL(12,3)")]:
            f.act("ooa.database/execute", desc=f"Drop an older {table} table", sql=f"DROP TABLE IF EXISTS {table}", session="erp")
            f.act("ooa.database/execute", desc=f"Load {table} from the test data", session="erp",
                  sql=f"CREATE TABLE {table}({cols}) AS SELECT * FROM CSVREAD('${{base}}/data/RW03/{table}.csv')")
        f.act("ooa.database/query", desc="Read the supplier master", sql='SELECT id AS "id", name AS "name", aliases AS "aliases" FROM suppliers', session="erp",
              out={"table": "sup"})
        with f.loop(desc="Each supplier: its name and aliases point to its id", iterator="table_rows", over="$sup$", **{"as": "srow"}):
            f.act("ooa.dictionary/put", desc="Name -> id", dictionary="$alias$", key="$srow.name$", value="$srow.id$", out={"dictionary": "alias"})
            f.act("ooa.list/add_item", desc="Candidate: the name", list="$cands$", item="$srow.name$", out={"list": "cands"})
            with f.loop(desc="Each alias", iterator="list", over={"expr": "split($srow.aliases$, ';')"}, **{"as": "al"}):
                f.act("ooa.dictionary/put", desc="Alias -> id", dictionary="$alias$", key="$al$", value="$srow.id$", out={"dictionary": "alias"})
                f.act("ooa.list/add_item", desc="Candidate: the alias", list="$cands$", item="$al$", out={"list": "cands"})
    with c.records("Per invoice: supplier match, PO and receipts from the database, SKU join, ERP price, variances, EUR amount, tolerance branch, decision.",
                   summary="${rec.invoice}: ${match} (${score}%) PO ${rec.po}\nEUR ${eur}  price var ${max_var}%  short ${short}\nDecision: ${decision} -> ${route}"):
        f.act("ooa.fuzzy/best_match", desc="2. Fuzzy-match the supplier name (aliases too)", text="$rec.supplier$", candidates="$cands$", threshold=85,
              algorithm="token_sort", out={"found": "found", "match": "match", "score": "score"})
        with f.if_("not $found$", desc="No supplier close enough?"):
            f.act("core/throw", desc="Route it", code="ROUTE.UNKNOWN_SUPPLIER", message="${rec.supplier} matches no supplier in the master")
        f.expr("supplier_id", "get($alias$, $match$)", desc="The supplier id")
        f.act("ooa.database/query", desc="1. Read the PO lines with their receipts", session="erp", parameters=["$rec.po$"], out={"table": "po_lines", "row_count": "n"},
              sql='SELECT p.sku AS "sku", p.qty AS "po_qty", p.unit_price AS "po_price", p.supplier AS "po_supplier", p.cost_center AS "cost_center", '
                  'COALESCE(r.received, 0) AS "received" FROM pos p LEFT JOIN receipts r ON r.po = p.po AND r.sku = p.sku WHERE p.po = ? ORDER BY p.sku')
        with f.if_("$n$ == 0", desc="PO not in the ERP?"):
            f.act("core/throw", desc="Reject: unknown PO", code="ROUTE.NO_PO", message="${rec.invoice}: PO ${rec.po} is not in the ERP")
        f.act("ooa.datatable/get_value", desc="Supplier on the PO", table="$po_lines$", row=0, column="po_supplier", out={"value": "po_supplier"})
        f.act("ooa.datatable/get_value", desc="Cost centre of the PO", table="$po_lines$", row=0, column="cost_center", out={"value": "cc"})
        with f.if_({"op": "ne", "left": "$po_supplier$", "right": "$supplier_id$"}, desc="PO belongs to another supplier?"):
            f.act("core/throw", desc="Route it", code="ROUTE.PO_SUPPLIER", message="PO ${rec.po} is not a ${supplier_id} order")
        with f.if_({"op": "contains", "left": "$matched$", "right": "$rec.po$"}, desc="8. PO matched already?"):
            f.act("core/throw", desc="Never match a PO twice", code="ROUTE.DOUBLE_MATCH", message="PO ${rec.po} was already matched to another invoice")
        f.act("ooa.datatable/filter", desc="This invoice's lines", table="$lines$", column="key", operator="eq", value="$rec.key$", out={"table": "inv_lines", "count": "ni"})
        f.act("ooa.datatable/join", desc="3. Join invoice lines to PO lines by SKU", table="$inv_lines$", key_columns=["sku"], other="$po_lines$", join_type="inner",
              out={"table": "joined", "count": "jn"})
        with f.if_("$jn$ != $ni$", desc="An invoice SKU is not on the PO?"):
            f.act("core/throw", desc="Route it", code="ROUTE.SKU_NOT_ON_PO", message="${rec.invoice}: ${$ni$ - $jn$} invoice line(s) are not on PO ${rec.po}")
        f.set("max_var", 0, desc="Largest price variance so far")
        f.set("short", 0, desc="Quantity invoiced but not received so far")
        with f.loop(desc="Each matched line", iterator="table_rows", over="$joined$", **{"as": "ln"}):
            f.act("ooa.soap/send", desc="Agreed price from the ERP price service (SOAP)", url="${standin}/soap/erp", soap_action="urn:ooa-qa:erp#GetPrice",
                  body="<GetPrice xmlns=\"urn:ooa-qa:erp\"><supplier>${supplier_id}</supplier><sku>${ln.sku}</sku></GetPrice>",
                  xpath="//*[local-name()='price']", retry=RETRY, out={"values": "erp"})
            with f.if_("abs(toNumber(first($erp$)) - toNumber($ln.po_price$)) > 0.001", desc="ERP and PO prices differ?"):
                f.act("core/throw", desc="Stop: master data differs", code="DATA.PRICE_MASTER", message="${ln.sku}: ERP price ${first($erp$)}, PO price ${ln.po_price}")
            f.expr("var", "round((toNumber($ln.unit_price$) - toNumber($ln.po_price$)) / toNumber($ln.po_price$) * 100, 2)", desc="4. Price variance (%)")
            f.expr("max_var", "max($max_var$, abs($var$))", desc="Keep the largest")
            f.expr("short", "$short$ + max(0, toNumber($ln.qty$) - toNumber($ln.received$))", desc="4. Invoiced but not received")
        f.expr("tax_var", "round(toNumber($rec.tax$) - round(toNumber($rec.net$) * toNumber($rec.tax_rate$) / 100, 2), 2)", desc="4. Tax variance")
        f.act("ooa.currency/convert", desc="5. Amount in EUR (fixed rates)", amount="$rec.total$", from_currency="$rec.currency$", to_currency="EUR", source="fixed",
              base_currency="EUR", rates="$rates$", out={"amount": "eur"})
        with f.if_("$short$ > 0", desc="6. Invoiced more than received?") as tol:
            f.set("decision", "quantity_variance", desc="Decision")
            f.set("route", "receiving", desc="Wait for the goods receipt")
            f.set("reason", "${short} unit(s) invoiced but not received", desc="Reason")
        with f.elif_(tol, "abs($tax_var$) > 0.05"):
            f.set("decision", "tax_variance", desc="Decision")
            f.set("route", "tax", desc="Tax team")
            f.set("reason", "tax differs by ${tax_var}", desc="Reason")
        with f.elif_(tol, "$max_var$ > $price_tolerance$"):
            f.set("decision", "price_variance", desc="Decision")
            f.set("route", "buyer", desc="The buyer approves the price")
            f.set("reason", "price ${max_var}% above the PO (tolerance ${price_tolerance}%)", desc="Reason")
        with f.else_(tol):
            f.set("decision", "matched", desc="Decision")
            f.set("route", "approval", desc="Normal approval")
            f.set("reason", "three-way match within tolerance", desc="Reason")
        f.act("ooa.datatable/add_row", desc="7. Matched or exception row, 9. with its reason", table="$evidence$",
              values={"key": "$rec.key$", "invoice": "$rec.invoice$", "supplier_id": "$supplier_id$", "match_score": "$score$", "po": "$rec.po$",
                      "cost_center": "$cc$", "currency": "$rec.currency$", "total": "$rec.total$", "amount_eur": "$eur$", "max_price_var": "$max_var$",
                      "qty_short": "$short$", "tax_var": "$tax_var$", "decision": "$decision$", "route": "$route$", "reason": "$reason$"}, out={"table": "evidence"})
        with f.if_({"any": [{"op": "eq", "left": "$route$", "right": "receiving"}, {"op": "eq", "left": "$route$", "right": "tax"}]}, desc="Not for approval?"):
            f.act("core/throw", desc="Route it to the exception list", code="ROUTE.VARIANCE", message="${rec.invoice}: ${reason} -> ${route}")
        f.act("ooa.list/add_item", desc="Mark the PO as matched", list="$matched$", item="$rec.po$", out={"list": "matched"})
        f.set("rec_note", "${decision} -> ${route}", desc="Note for the results")
    with c.finish():
        f.act("ooa.database/disconnect", desc="Close the QA database", session="erp")
        save_evidence(f)
        f.act("ooa.datatable/aggregate", desc="Decisions at a glance", table="$evidence$", group_by=["decision"], summaries={"invoices": "count", "eur": "sum:amount_eur"},
              out={"table": "sup"})
        f.act("ooa.datatable/write_to_file", desc="Save the decision summary", table="$sup$", path="${out_dir}/decisions.csv")
    return c


# ------------------------------------------------------------------ B4
def b4_approvals():
    c = Child("RW03", 4, "Approval and SoD controller",
              "Creates an approval request per matched invoice, chooses the approver by amount and route (the buyer for price variance), never lets "
              "requesters approve their own invoice, asks a second approver above the threshold, handles decline and timeout, signs each decision "
              "(HMAC) so SAP staging can check it, resumes safely from its checkpoint and notifies QA recipients only.",
              "10 approval dossiers (the matched ones)")
    f = c.f
    cols = ["key", "invoice", "amount_eur", "route", "requester", "approver", "second_approver", "approval_id", "status", "signature"]
    for n, t, d in [("matrix", "Table", None), ("requesters", "Table", None), ("done", "Table", {"columns": cols, "rows": []}), ("prev", "String", ""),
                    ("again", "Table", None),
                    ("mrow", "Dictionary", None), ("amount", "Number", 0), ("approver", "String", ""), ("second", "String", "no"), ("requester", "String", ""),
                    ("req", "Dictionary", None), ("req2", "Dictionary", None), ("state", "Dictionary", None), ("status", "String", ""), ("status2", "String", ""),
                    ("tries", "Number", 0), ("sig", "String", ""), ("approvals", "String", ""), ("checkpoint", "String", "")]:
        f.var(n, t, "local", d)
    evidence_var(f, cols)
    c.start()
    with c.prepare("Read the matcher's decisions (only invoices for approval), the approval matrix, the cost-centre requesters and this run's checkpoint."):
        fault_setup(f, "approvals")
        read_prev(f, "RW03-B3", "records")
        f.act("ooa.datatable/filter", desc="Only invoices that go to approval", table="$records$", column="route", operator="eq", value="approval",
              more_conditions=[{"column": "route", "operator": "eq", "value": "buyer"}], match="any", out={"table": "records"})
        f.act("ooa.csv/read", desc="Read the approval matrix", path="${base}/data/RW03/approval_matrix.csv", out={"table": "matrix"})
        f.act("ooa.csv/read", desc="Read who requests for each cost centre", path="${base}/data/RW03/requesters.csv", out={"table": "requesters"})
        f.set("approvals", "${standin}/approvals/requests", desc="Approval service (QA stand-in)")
        f.set("checkpoint", "${out_dir}/evidence.csv", desc="Checkpoint: the evidence saved after each invoice")
        with f.if_({"condition": "ooa.file/exists", "args": {"path": "$checkpoint$"}}, desc="8. A checkpoint from an earlier try of this run?"):
            f.act("ooa.csv/read", desc="Read it to resume safely", path="$checkpoint$", out={"table": "done"})
    with c.records("Per invoice: approver by amount and route, self-approval check, request, wait for the answer, second approver, signature, notice.",
                   summary="${rec.invoice}: EUR ${amount}\nApprover ${approver} (second: ${second})\nAnswer: ${status} ${status2}"):
        f.act("ooa.datatable/lookup", desc="8. Approved in an earlier try of this run?", table="$done$", column="key", value="$rec.key$", return_column="approval_id",
              if_not_found="", out={"value": "prev"})
        with f.if_("not isEmpty($prev$)", desc="Already approved: resume") as resumed:
            f.act("ooa.log/write", desc="Resume without asking again", path="$log_file$", message="${rec.invoice}: approved earlier in this run as ${prev}")
            f.act("ooa.datatable/filter", desc="Keep the earlier row", table="$done$", column="key", operator="eq", value="$rec.key$", out={"table": "again"})
            f.act("ooa.datatable/merge", desc="Carry it into the evidence", tables=["$evidence$", "$again$"], out={"table": "evidence"})
            f.set("rec_note", "resumed ${prev}", desc="Note for the results")
        with f.else_(resumed):
            f.expr("amount", "toNumber($rec.amount_eur$)", desc="Amount in EUR")
            f.set("approver", "", desc="No approver yet")
            with f.loop(desc="2. Approval matrix, smallest limit first", iterator="table_rows", over="$matrix$", **{"as": "mrow"}):
                with f.if_("isEmpty($approver$) and $amount$ <= toNumber($mrow.up_to_eur$)", desc="Within this limit?"):
                    f.set("approver", "$mrow.approver$", desc="Approver for the amount")
                    f.set("second", "$mrow.second_approver$", desc="Second approver needed?")
            with f.if_({"op": "eq", "left": "$rec.route$", "right": "buyer"}, desc="Price variance: the buyer decides"):
                f.set("approver", "buyer.ap@ooa-qa.test", desc="The buyer approves the price")
            f.act("ooa.datatable/lookup", desc="Who requested it (cost centre owner)", table="$requesters$", column="cost_center", value="$rec.cost_center$",
                  return_column="requester", if_not_found="ap.clerk@ooa-qa.test", out={"value": "requester"})
            with f.if_({"op": "eq", "left": "$approver$", "right": "$requester$"}, desc="3. Would the requester approve their own invoice?"):
                f.act("ooa.log/write", desc="Segregation of duties: escalate", path="$log_file$", level="WARN",
                      message="${rec.invoice}: ${requester} cannot approve their own request; escalated to the controller")
                f.set("approver", "controller.ap@ooa-qa.test", desc="Escalate to the controller")
            with f.if_({"op": "eq", "left": "$approver$", "right": "$requester$"}, desc="Still the same person?"):
                f.act("core/throw", desc="Stop: no independent approver", code="SOD.SELF_APPROVAL", message="${rec.invoice}: no approver other than ${requester}")
            f.act("ooa.rest/request", desc="1. Create the approval request", method="POST", url="$approvals$", bearer_token=cred("servicenow"), retry=RETRY,
                  json={"subject": "$rec.invoice$", "approver": "$approver$", "requester": "$requester$", "amount_eur": "$amount$", "run": "$run_id$",
                        "comment": "OOA-QA ${rec.invoice} ${rec.decision}: ${rec.reason}"}, out={"json": "req"})
            f.set("status", "pending", desc="Waiting for the answer")
            f.set("tries", 0, desc="Start counting the checks")
            with f.loop(desc="5. Wait for the answer (3 checks, then it is a timeout)", iterator="while", condition="$status$ == 'pending' and $tries$ < 3", max_iterations=5):
                f.act("core/delay", desc="Wait a second", seconds=1)
                f.expr("tries", "$tries$ + 1", desc="Count the check")
                f.act("ooa.rest/request", desc="Read the request's state", url="${approvals}/${req.id}", bearer_token=cred("servicenow"), retry=RETRY, out={"json": "state"})
                f.set("status", "$state.status$", desc="Its state")
            with f.switch("$status$", desc="5. The approver's answer") as sw:
                with f.case(sw, "declined"):
                    f.act("core/throw", desc="Declined", code="APPROVAL.DECLINED", message="${rec.invoice} was declined by ${approver}")
                with f.case(sw, "pending"):
                    f.act("core/throw", desc="No answer in time", code="APPROVAL.TIMEOUT", message="${approver} did not answer for ${rec.invoice}")
            f.set("status2", "", desc="No second approval yet")
            with f.if_({"op": "eq", "left": "$second$", "right": "yes"}, desc="4. Above the threshold: second approver"):
                f.act("ooa.rest/request", desc="Ask the second approver", method="POST", url="$approvals$", bearer_token=cred("servicenow"), retry=RETRY,
                      json={"subject": "${rec.invoice}#2", "approver": "cfo.ap@ooa-qa.test", "requester": "$requester$", "amount_eur": "$amount$", "run": "$run_id$",
                            "comment": "Second approval above EUR 20,000"}, out={"json": "req2"})
                f.set("status2", "pending", desc="Waiting")
                f.set("tries", 0, desc="Start counting the checks")
                with f.loop(desc="Wait for the second answer", iterator="while", condition="$status2$ == 'pending' and $tries$ < 3", max_iterations=5):
                    f.act("core/delay", desc="Wait a second", seconds=1)
                    f.expr("tries", "$tries$ + 1", desc="Count the check")
                    f.act("ooa.rest/request", desc="Read its state", url="${approvals}/${req2.id}", bearer_token=cred("servicenow"), retry=RETRY, out={"json": "state"})
                    f.set("status2", "$state.status$", desc="Its state")
                with f.if_({"op": "ne", "left": "$status2$", "right": "approved"}, desc="Second approver did not approve?"):
                    f.act("core/throw", desc="6. No SAP staging without both approvals", code="APPROVAL.SECOND_MISSING",
                          message="${rec.invoice}: second approval ${status2} (first approved by ${approver})")
            f.act("ooa.crypto/hmac", desc="7. Sign the decision (approval hash)", text="${rec.key}|${rec.invoice}|${approver}|${status}|${req.id}", key=cred("sign_key"),
                  out={"signature": "sig"})
            with f.if_("not endsWith(lower($approver$), '@ooa-qa.test')", desc="9. Recipient outside the QA tenant?"):
                f.act("core/throw", desc="Never notify outside QA", code="NOTIFY.EXTERNAL", message="${approver} is not a QA recipient")
            f.act("ooa.microsoft365/send_mail", desc="9. Notify the approver (QA mailbox only)", to="$approver$",
                  subject="OOA-QA ${run_id} ${rec.invoice} approved", body="Approval ${req.id} recorded for ${rec.invoice} (EUR ${amount}).", **graph(MAILBOX))
            f.act("ooa.datatable/add_row", desc="7. Record the decision and its signature", table="$evidence$",
                  values={"key": "$rec.key$", "invoice": "$rec.invoice$", "amount_eur": "$amount$", "route": "$rec.route$", "requester": "$requester$",
                          "approver": "$approver$", "second_approver": {"expr": "if($second$ == 'yes', 'cfo.ap@ooa-qa.test', '')"}, "approval_id": "$req.id$",
                          "status": "approved", "signature": "$sig$"}, out={"table": "evidence"})
            save_evidence(f)
            f.set("rec_note", "approved by ${approver}", desc="Note for the results")
    with c.finish():
        save_evidence(f)
    return c


# ------------------------------------------------------------------ B5
def b5_sap_drafts():
    c = Child("RW03", 5, "SAP QA draft operator",
              "Checks the SAP host and client are QA, signs on with the vault credential, checks each approval signature, finds the vendor and PO, "
              "parks a draft invoice only (lines and tax code), reads it back, compares the totals SAP worked out with the invoice, keeps the "
              "parked number or cancels the draft on a mismatch, always signs off and proves no posting or payment happened.",
              "10 approved dossiers (the approved ones)")
    f = c.f
    f.var("sap_mode", "String", "input", "simulator", "simulator (the QA stand-in) or gui (SAP GUI scripting on a machine with SAP Logon and the QA system).")
    f.var("sap_client", "String", "input", "QA-100", "SAP client (mandant); must be a QA client.")
    f.var("sap_system", "String", "input", "QA1 - OOA QA (synthetic)", "SAP Logon entry of the QA system (gui mode).")
    for n, t, d in [("b2", "Table", None), ("b3", "Table", None), ("lines", "Table", None), ("taxcodes", "Table", None), ("host", "String", ""),
                    ("hosts", "List", ["127.0.0.1", "localhost"]), ("clients", "List", ["QA-100"]), ("logon", "Dictionary", {}), ("docno", "String", ""),
                    ("sap_total", "Number", 0),
                    ("sig", "String", ""), ("vendor", "String", ""), ("po", "String", ""), ("currency", "String", ""), ("total", "Number", 0),
                    ("rate", "String", ""), ("tax_code", "String", ""), ("my_lines", "Table", None), ("items", "List", None), ("doc", "Dictionary", None),
                    ("back", "Dictionary", None), ("check", "Dictionary", None), ("posted", "Dictionary", None), ("bar", "String", ""), ("conn", "Number", 0)]:
        f.var(n, t, "local", d)
    f.var("posted_count", "Number", "output", 0, "Posted or paid documents found afterwards (must be 0).")
    evidence_var(f, ["key", "invoice", "vendor", "po", "tax_code", "document", "status", "sap_total", "invoice_total"])
    c.start()
    with c.prepare("1. Check host and client are QA, sign on with the vault credential, and read the approved invoices and their lines."):
        fault_setup(f, "sap")
        f.expr("host", "regexExtract($standin$, '//([^:/]+)', 1)", desc="The SAP host")
        with f.if_("not contains($hosts$, $host$) or not contains($clients$, $sap_client$)", desc="Not the QA host and client?"):
            f.act("core/throw", desc="Stop: QA only", code="SAP.NOT_QA", message="SAP host ${host} / client ${sap_client} is not on the QA allow-list")
        read_prev(f, "RW03-B4", "records")
        f.act("ooa.datatable/filter", desc="Approved invoices only (6. no staging before approval)", table="$records$", column="status", operator="eq", value="approved",
              out={"table": "records"})
        read_prev(f, "RW03-B2", "b2")
        read_prev(f, "RW03-B2", "lines", "lines.csv", "invoice lines")
        read_prev(f, "RW03-B3", "b3")
        f.act("ooa.csv/read", desc="Read the SAP tax codes", path="${base}/data/RW03/tax_codes.csv", out={"table": "taxcodes"})
        with f.if_({"op": "eq", "left": "$sap_mode$", "right": "gui"}, desc="SAP GUI (on a machine with SAP Logon)?") as gui:
            f.comment("Built for the customer's SAP QA system; not run here (SAP GUI is not installed on this PC). Field ids come from MIR7 "
                      "in SAP GUI Scripting and must be confirmed with a recording on the QA system.")
            f.act("ooa.sap/log_on", desc="2. Sign on to the QA system", system="$sap_system$", client="$sap_client$", user="OOA_QA_BOT",
                  password=cred("sap", "password"), language="EN", multiple_logon="fail", out={"connection": "conn"})
        with f.else_(gui):
            f.act("ooa.rest/request", desc="2. Sign on to the SAP QA simulator (vault credential)", method="POST", url="${standin}/sap/logon",
                  basic_user="OOA_QA_BOT", basic_password=cred("sap", "password"), json={"client": "$sap_client$"}, retry=RETRY, out={"json": "logon"})
    with f.try_(desc="Work through the invoices; whatever happens, sign off afterwards") as tf:
        with c.records("Per invoice: signature check, vendor and PO, park the draft, read it back, compare totals, keep or cancel.",
                       summary="${rec.invoice}: vendor ${vendor}, PO ${po}\nParked ${docno}: SAP ${sap_total} vs invoice ${total}"):
            f.act("ooa.crypto/hmac", desc="Recompute the approval signature", text="${rec.key}|${rec.invoice}|${rec.approver}|approved|${rec.approval_id}",
                  key=cred("sign_key"), out={"signature": "sig"})
            with f.if_({"op": "ne", "left": "$sig$", "right": "$rec.signature$"}, desc="Signature does not match?"):
                f.act("core/throw", desc="6. Never stage without a valid approval", code="APPROVAL.SIGNATURE", message="${rec.invoice}: the approval signature does not match")
            f.act("ooa.datatable/lookup", desc="Vendor (supplier id)", table="$b3$", column="key", value="$rec.key$", return_column="supplier_id", out={"value": "vendor"})
            f.act("ooa.datatable/lookup", desc="PO", table="$b3$", column="key", value="$rec.key$", return_column="po", out={"value": "po"})
            f.act("ooa.datatable/lookup", desc="Currency", table="$b2$", column="key", value="$rec.key$", return_column="currency", out={"value": "currency"})
            f.act("ooa.datatable/lookup", desc="Invoice total", table="$b2$", column="key", value="$rec.key$", return_column="total", out={"value": "total"})
            f.act("ooa.datatable/lookup", desc="Tax rate", table="$b2$", column="key", value="$rec.key$", return_column="tax_rate", out={"value": "rate"})
            f.act("ooa.datatable/lookup", desc="SAP tax code for the rate", table="$taxcodes$", column="rate", value="$rate$", return_column="tax_code",
                  if_not_found="", out={"value": "tax_code"})
            f.act("ooa.datatable/filter", desc="The invoice's lines", table="$lines$", column="key", operator="eq", value="$rec.key$",
                  columns=["sku", "qty", "unit_price"], out={"table": "my_lines"})
            f.act("ooa.datatable/to_records", desc="As SAP line items", table="$my_lines$", out={"records": "items"})
            with f.switch("$sap_mode$", desc="Simulator or SAP GUI") as sw:
                with f.case(sw, "gui"):
                    f.act("ooa.sap/run_transaction", desc="5. Enter invoice (MIR7, park)", tcode="/nMIR7", connection="$conn$")
                    f.act("ooa.sap/set_field", desc="Invoice date", id="wnd[0]/usr/subHEADER_AND_ITEMS:SAPLMR1M:6005/tabsHEADER/tabpHEADER_TOTAL/ssubHEADER_SCREEN:SAPLFDCB:0010/ctxtINVFO-BLDAT",
                          value="${formatDate(now(), '%d.%m.%Y')}", connection="$conn$")
                    f.act("ooa.sap/set_field", desc="Reference (invoice number)", id="wnd[0]/usr/subHEADER_AND_ITEMS:SAPLMR1M:6005/tabsHEADER/tabpHEADER_TOTAL/ssubHEADER_SCREEN:SAPLFDCB:0010/txtINVFO-XBLNR",
                          value="$rec.invoice$", connection="$conn$")
                    f.act("ooa.sap/set_field", desc="Amount", id="wnd[0]/usr/subHEADER_AND_ITEMS:SAPLMR1M:6005/tabsHEADER/tabpHEADER_TOTAL/ssubHEADER_SCREEN:SAPLFDCB:0010/txtINVFO-WRBTR",
                          value="$total$", connection="$conn$")
                    f.act("ooa.sap/set_field", desc="PO (lines come from the PO)", id="wnd[0]/usr/subHEADER_AND_ITEMS:SAPLMR1M:6005/subITEMS:SAPLMR1M:6010/tabsITEMTAB/tabpITEMS_PO/ssubTABS:SAPLMR1M:6020/subREFERENZBELEG:SAPLMR1M:6211/ctxtRM08M-EBELN",
                          value="$po$", press_enter=True, connection="$conn$")
                    f.act("ooa.sap/press", desc="Park (never Post)", id="wnd[0]/tbar[1]/btn[27]", connection="$conn$")
                    f.act("ooa.sap/get_status_bar", desc="8. Parked number from the status bar", connection="$conn$", out={"text": "bar"})
                    f.expr("docno", "coalesce(regexExtract($bar$, '([0-9]{10})', 1), '')", desc="Keep the parked number")
                    f.set("sap_total", "$total$", desc="SAP GUI: MIR7 checks the balance on the screen before parking")
                with f.default(sw):
                    f.act("ooa.rest/request", desc="3. Find the vendor", url="${standin}/sap/vendors/${vendor}", headers={"X-SAP-Session": "$logon.session$"}, retry=RETRY,
                          out={"json": "check"})
                    f.act("ooa.rest/request", desc="3. Find the PO", url="${standin}/sap/po/${po}", headers={"X-SAP-Session": "$logon.session$"}, retry=RETRY,
                          out={"json": "check"})
                    with f.if_({"op": "ne", "left": "$check.vendor$", "right": "$vendor$"}, desc="PO of another vendor in SAP?"):
                        f.act("core/throw", desc="Stop", code="SAP.PO_VENDOR", message="PO ${po} belongs to ${check.vendor} in SAP")
                    f.act("ooa.rest/request", desc="4. Park a draft only (5. lines and tax code)", method="POST", url="${standin}/sap/parked",
                          headers={"X-SAP-Session": "$logon.session$"}, retry=RETRY,
                          json={"client": "$sap_client$", "vendor": "$vendor$", "invoice": "$rec.invoice$", "po": "$po$", "currency": "$currency$",
                                "tax_code": "$tax_code$", "lines": "$items$", "run": "$run_id$", "approval": "$rec.approval_id$"}, out={"json": "doc"})
                    f.act("ooa.rest/request", desc="6. Read the parked document back", url="${standin}/sap/parked/${doc.document}",
                          headers={"X-SAP-Session": "$logon.session$"}, retry=RETRY, out={"json": "back"})
                    f.set("docno", "$doc.document$", desc="The parked number")
                    f.set("sap_total", "$back.total$", desc="The total SAP worked out")
            with f.if_("abs(toNumber($sap_total$) - toNumber($total$)) > 0.01", desc="7. SAP's total differs from the invoice?"):
                with f.if_({"op": "ne", "left": "$sap_mode$", "right": "gui"}, desc="Simulator: cancel the draft"):
                    f.act("ooa.rest/request", desc="9. Cancel the parked draft", method="DELETE", url="${standin}/sap/parked/${docno}",
                          headers={"X-SAP-Session": "$logon.session$"}, retry=RETRY)
                f.act("core/throw", desc="Route the mismatch", code="SAP.TOTAL_MISMATCH",
                      message="${rec.invoice}: SAP worked out ${sap_total}, the invoice says ${total}; draft ${docno} cancelled")
            f.act("ooa.datatable/add_row", desc="8. Keep the parked number", table="$evidence$",
                  values={"key": "$rec.key$", "invoice": "$rec.invoice$", "vendor": "$vendor$", "po": "$po$", "tax_code": "$tax_code$",
                          "document": "$docno$", "status": "parked", "sap_total": "$sap_total$", "invoice_total": "$total$"}, out={"table": "evidence"})
            f.act("ooa.log/write", desc="Log it", path="$log_file$", message="${rec.invoice}: parked as ${docno} (${currency} ${sap_total})")
            f.set("rec_note", "parked ${docno}", desc="Note for the results")
    with f.finally_(tf):
        f.comment("10. Always sign off, also after an error.")
        with f.if_({"op": "eq", "left": "$sap_mode$", "right": "gui"}, desc="SAP GUI?") as g2:
            f.act("ooa.sap/close", desc="Sign off and close SAP", connection="$conn$", cont=True)
        with f.else_(g2):
            with f.if_("not isEmpty(get($logon$, 'session', ''))", desc="Signed on?"):
                f.act("ooa.rest/request", desc="Sign off the simulator", method="POST", url="${standin}/sap/logoff", headers={"X-SAP-Session": "$logon.session$"}, cont=True)
    with c.finish("Prove that no posting or payment was made, then save the parked numbers."):
        f.act("ooa.rest/request", desc="Posted or paid documents in SAP QA (must be none)", url="${standin}/sap/posted", retry=RETRY, out={"json": "posted"})
        f.expr("posted_count", "toNumber($posted.count$)", desc="How many")
        with f.if_("$posted_count$ > 0", desc="Anything posted?"):
            f.act("core/throw", desc="Stop: forbidden action happened", code="SAP.POSTED", message="${posted_count} document(s) were posted: the forbidden action ran")
        f.act("ooa.log/write", desc="Report: posting was never invoked", path="$log_file$", message="No posting or payment action was invoked; SAP QA shows 0 posted documents")
        save_evidence(f)
    return c


# ------------------------------------------------------------------ B6
def b6_closeout():
    c = Child("RW03", 6, "Excel control and close-out",
              "Brings every child's results together for the 10 invoice dossiers, reconciles counts and EUR totals, sorts variances into buckets, "
              "builds the control workbook with formulas and a pivot table, checks the formulas read back, stores it in the QA SharePoint site, "
              "scans every output for the secret canary and confirms no posted or paid documents.",
              "10 invoice dossiers", data_file="RW03/invoices.csv")
    f = c.f
    src = {"b1": "RW03-B1", "b2": "RW03-B2", "b3": "RW03-B3", "b4": "RW03-B4", "b5": "RW03-B5"}
    for k in src:
        f.var(k, "Table", "local", {"columns": ["key"], "rows": []})
        f.var(k + "r", "Table", "local", {"columns": ["key", "status", "note"], "rows": []})
    for n, t, d in [("case", "String", ""), ("total", "String", ""), ("cur", "String", ""), ("eur", "String", ""), ("decision", "String", ""),
                    ("appr", "String", ""), ("sapdoc", "String", ""), ("stage", "String", ""), ("bucket", "String", ""), ("note", "String", ""), ("st", "String", ""),
                    ("buckets", "Table", None), ("item", "String", ""), ("formula", "String", ""), ("v", "Any", None), ("canary", "String", ""),
                    ("files", "List", None), ("file", "String", ""), ("text", "String", ""), ("leaks", "Number", 0), ("posted", "Dictionary", None),
                    ("control", "Table", {"columns": ["key", "dossier", "invoice", "case", "currency", "total", "amount_eur", "decision", "approval",
                                                      "sap_document", "stage", "bucket"], "rows": []})]:
        f.var(n, t, "local", d)
    f.var("workbook", "String", "output", "", "The control workbook.")
    c.start()
    with c.prepare("Read every child's evidence and results of this run (fixtures when run on its own)."):
        for k, code in src.items():
            read_prev(f, code, k)
            read_prev(f, code, k + "r", "results.csv", "results")
    with c.records("Per dossier: where it got to in each child, its EUR amount and its variance bucket.",
                   summary="${rec.dossier} ${rec.invoice}: ${stage}\nBucket: ${bucket}"):
        f.act("ooa.datatable/lookup", desc="1. Intake case", table="$b1$", column="key", value="$rec.key$", return_column="case_id", if_not_found="", out={"value": "case"})
        f.act("ooa.datatable/lookup", desc="Invoice total", table="$b2$", column="key", value="$rec.key$", return_column="total", if_not_found="", out={"value": "total"})
        f.act("ooa.datatable/lookup", desc="Currency", table="$b2$", column="key", value="$rec.key$", return_column="currency", if_not_found="", out={"value": "cur"})
        f.act("ooa.datatable/lookup", desc="EUR amount", table="$b3$", column="key", value="$rec.key$", return_column="amount_eur", if_not_found="", out={"value": "eur"})
        f.act("ooa.datatable/lookup", desc="Match decision", table="$b3$", column="key", value="$rec.key$", return_column="decision", if_not_found="", out={"value": "decision"})
        f.act("ooa.datatable/lookup", desc="Approval", table="$b4$", column="key", value="$rec.key$", return_column="approval_id", if_not_found="", out={"value": "appr"})
        f.act("ooa.datatable/lookup", desc="SAP parked number", table="$b5$", column="key", value="$rec.key$", return_column="document", if_not_found="", out={"value": "sapdoc"})
        f.set("bucket", "", desc="No exception yet")
        f.set("stage", "parked in SAP QA", desc="Furthest stage, from the end back")
        for k, code, stage in [("b1r", "RW03-B1", "intake"), ("b2r", "RW03-B2", "extraction"), ("b3r", "RW03-B3", "matching"), ("b4r", "RW03-B4", "approval"),
                               ("b5r", "RW03-B5", "SAP draft")]:
            f.act("ooa.datatable/lookup", desc=f"Exception at {stage}?", table=f"${k}$", column="key", value="$rec.key$", return_column="status",
                  if_not_found="", out={"value": "st"})
            with f.if_({"all": [{"op": "empty", "left": "$bucket$"}, {"op": "eq", "left": "$st$", "right": "exception"}]}, desc=f"3. Stopped at {stage}?"):
                f.act("ooa.datatable/lookup", desc="Why", table=f"${k}$", column="key", value="$rec.key$", return_column="note", if_not_found="", out={"value": "note"})
                f.expr("bucket", f"'{stage}: ' + coalesce(regexExtract($note$, '^([A-Z_]+[.][A-Z_]+)', 1), $note$)", desc="Variance bucket")
                f.set("stage", f"stopped at {stage}", desc="Where it stopped")
        with f.if_({"all": [{"op": "empty", "left": "$bucket$"}, {"op": "empty", "left": "$sapdoc$"}]}, desc="Not parked and no exception noted?"):
            f.set("stage", "not processed", desc="Not processed in this run")
            f.set("bucket", "not processed", desc="Bucket")
        f.act("ooa.datatable/add_row", desc="2. Control row", table="$control$",
              values={"key": "$rec.key$", "dossier": "$rec.dossier$", "invoice": "$rec.invoice$", "case": "$case$", "currency": "$cur$", "total": "$total$",
                      "amount_eur": "$eur$", "decision": "$decision$", "approval": "$appr$", "sap_document": "$sapdoc$", "stage": "$stage$",
                      "bucket": {"expr": "if(isEmpty($bucket$), 'none', $bucket$)"}}, out={"table": "control"})
        f.act("ooa.log/write", desc="Log the dossier", path="$log_file$", message="${rec.dossier} ${rec.invoice}: ${stage} (${bucket})")
        f.set("rec_note", "$stage$", desc="Note for the results")
    with c.finish("Workbook with formulas and a pivot table, read back, stored in SharePoint; canary scan; no posting."):
        f.set("workbook", "${out_dir}/RW03-control.xlsx", desc="Name of the workbook")
        f.act("ooa.excel/create_workbook", desc="4. Create the control workbook", path="$workbook$", overwrite=True, sheet="Dossiers")
        f.act("ooa.excel/write_table", desc="The 10 dossiers", table="$control$", sheet="Dossiers")
        for cell, label, formula in [("N1", "Dossiers", "COUNTA(A2:A11)"), ("N2", "Parked in SAP QA", "COUNTIF(K2:K11,\"parked in SAP QA\")"),
                                     ("N3", "Stopped", "COUNTIF(K2:K11,\"stopped*\")"), ("N4", "EUR matched", "SUM(G2:G11)"),
                                     ("N5", "Counts reconcile", "IF(N2+N3+COUNTIF(K2:K11,\"not processed\")=N1,\"yes\",\"NO\")")]:
            f.act("ooa.excel/set_cell", desc="Label: " + label, cell="M" + cell[1:], value=label, sheet="Dossiers")
            f.act("ooa.excel/set_formula", desc="Formula: " + label, cell=cell, formula=formula, sheet="Dossiers")
        f.act("ooa.datatable/aggregate", desc="3. Variance buckets", table="$control$", group_by=["bucket"], summaries={"dossiers": "count", "eur": "sum:amount_eur"},
              out={"table": "buckets"})
        f.act("ooa.excel/add_sheet", desc="Buckets sheet", name="Buckets")
        f.act("ooa.excel/write_table", desc="Write the buckets", table="$buckets$", sheet="Buckets")
        f.act("ooa.excel/create_pivot_table", desc="4. Pivot-ready: dossiers by stage", sheet="Dossiers", range="A1:L11", destination_sheet="Pivot", rows=["stage"],
              values=["key:count"])
        f.act("ooa.excel/save", desc="Save it")
        f.act("ooa.excel/read_formula", desc="6. Read a formula back", cell="N5", sheet="Dossiers", out={"formula": "formula"})
        with f.if_("not startsWith(toString($formula$), 'IF(') and not startsWith(toString($formula$), '=IF(')", desc="Formula lost?"):
            f.act("core/throw", desc="Stop", code="VERIFY.FORMULA", message="The reconcile formula did not read back: ${formula}")
        f.act("ooa.excel/close", desc="9. Close the workbook")
        f.act("ooa.microsoft365/upload_file", desc="5. Store it in the QA SharePoint site", local_path="$workbook$", remote_path="OOA-QA/${run_id}/RW03-control.xlsx",
              drive="sites/ooa-qa/drive", access_token=cred("graph"), base_url="${standin}/graph/v1.0", retry=RETRY, out={"item_id": "item"})
        f.act("ooa.datatable/write_to_file", desc="The control table as CSV", table="$control$", path="${out_dir}/RW03-control.csv")
        f.act("ooa.global_values/get", desc="7. Read the canary text", name="rw_canary", default="", type="String", out={"value": "canary"})
        f.act("ooa.file/list", desc="Every file this run wrote", folder="${base}/output/${run_id}", pattern="*.csv", include_subfolders=True, out={"files": "files"})
        with f.loop(desc="Each output file", iterator="list", over="$files$", **{"as": "file"}):
            f.act("ooa.file/read_text", desc="Read it", path="$file$", out={"text": "text"})
            with f.if_("not isEmpty($canary$) and contains($text$, $canary$)", desc="Holds the canary?"):
                f.expr("leaks", "$leaks$ + 1", desc="Count the leak")
        with f.if_("$leaks$ > 0", desc="Secret found in an output?"):
            f.act("core/throw", desc="Stop", code="SECURITY.CANARY", message="The secret canary is in ${leaks} output file(s)")
        f.act("ooa.rest/request", desc="8. Posted or paid documents (must be none)", url="${standin}/sap/posted", retry=RETRY, out={"json": "posted"})
        with f.if_("toNumber($posted.count$) > 0", desc="Anything posted?"):
            f.act("core/throw", desc="Stop", code="SAP.POSTED", message="SAP QA shows posted documents")
        f.act("ooa.log/write", desc="Close-out", path="$log_file$", message="Close-out: no canary in outputs, no posted or paid documents; workbook ${workbook}")
    return c


def main():
    return Main("RW03", "Procure-to-pay", "Procure-to-pay control: invoice mail -> PDF/OCR -> PO match -> approvals -> SAP QA parked draft (no posting, no payment).",
                [("rw03_b1", "RW03-B1", "Invoice mail intake"), ("rw03_b2", "RW03-B2", "PDF and OCR extraction"),
                 ("rw03_b3", "RW03-B3", "PO and receipt matcher"), ("rw03_b4", "RW03-B4", "Approval and SoD controller"),
                 ("rw03_b5", "RW03-B5", "SAP QA draft operator"), ("rw03_b6", "RW03-B6", "Excel control and close-out")], FOLDER, parallel_group=None).build()


CHILDREN = [b1_mail_intake, b2_extraction, b3_matcher, b4_approvals, b5_sap_drafts, b6_closeout]
MAINS = [main]
