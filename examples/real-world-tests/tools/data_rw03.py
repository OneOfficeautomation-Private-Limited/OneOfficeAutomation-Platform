"""Test data for RW03 Procure-to-pay: 10 synthetic OOA-QA invoices (PDF), their e-mails, suppliers, POs, receipts, approvals."""
import base64
import datetime as _dt

MAILBOX = "ap-qa@ooa-qa.test"
TODAY = _dt.date.today()


def pdf(lines):
    """A small text-only PDF (Helvetica), enough for PDF text extraction and for rendering to an image for OCR."""
    text = "BT /F1 11 Tf 50 790 Td 14 TL " + " ".join("(%s) '" % l.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)") for l in lines) + " ET"
    objs = ["<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>",
            "<< /Length %d >>\nstream\n%s\nendstream" % (len(text.encode("latin-1")), text)]
    out, offs = b"%PDF-1.4\n", []
    for i, o in enumerate(objs, 1):
        offs.append(len(out))
        out += ("%d 0 obj\n%s\nendobj\n" % (i, o)).encode("latin-1")
    x = len(out)
    out += ("xref\n0 %d\n0000000000 65535 f \n" % (len(objs) + 1)).encode() + b"".join(b"%010d 00000 n \n" % o for o in offs)
    out += ("trailer\n<< /Size %d /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n" % (len(objs) + 1, x)).encode()
    return out


# key, sender, supplier as written, invoice no, PO, currency, lines [(sku, qty, unit price)], tax %, number style, has attachment
INVOICES = [
    ("V01", "billing@acme-qa.test", "Acme Supplies Ltd", "INV-1001", "PO-5001", "EUR", [("SKU-A", 10, 50.00), ("SKU-B", 5, 120.00)], 19, "en", True),
    ("V02", "billing@acme-qa.test", "ACME Supplies Limited", "INV-1002", "PO-5002", "EUR", [("SKU-A", 20, 51.50)], 19, "en", True),
    ("V03", "ar@globex-qa.test", "Globex Corp", "INV-2001", "PO-5003", "USD", [("SKU-C", 4, 280.00)], 0, "en", True),
    ("V04", "ar@globex-qa.test", "Globex Corp", "INV-2002", "", "USD", [("SKU-C", 1, 250.00)], 0, "en", True),
    ("V05", "billing@acme-qa.test", "Acme Supplies Ltd", "INV-1001", "PO-5001", "EUR", [("SKU-A", 10, 50.00), ("SKU-B", 5, 120.00)], 19, "en", True),
    ("V06", "sales@stark-qa.test", "Stark Industries", "INV-3001", "PO-5006", "USD", [("SKU-X", 50, 500.00)], 0, "en", True),
    ("V07", "billing@initech-qa.test", "Initech", "INV-4001", "PO-5007", "EUR", [("SKU-D", 3, 90.00)], 19, "en", False),
    ("V08", "rechnung@umbrella-qa.test", "Umbrella GmbH", "RE-7781", "PO-5008", "EUR", [("SKU-E", 12, 102.875)], 19, "de", True),
    ("V09", "someone@unknown-vendor.example", "Unknown Vendor", "X-1", "PO-5009", "EUR", [("SKU-F", 1, 10.00)], 19, "en", True),
    ("V10", "sales@stark-qa.test", "Stark Industries", "INV-3002", "PO-5010", "USD", [("SKU-Y", 30, 40.00)], 0, "en", True),
]
SUPPLIERS = [  # id, name, aliases, sender allow-list, currency
    ("S-ACME", "Acme Supplies Ltd", "ACME Supplies Limited;Acme Supplies", "billing@acme-qa.test", "EUR"),
    ("S-GLOBEX", "Globex Corp", "Globex Corporation", "ar@globex-qa.test", "USD"),
    ("S-STARK", "Stark Industries", "Stark Ind.", "sales@stark-qa.test", "USD"),
    ("S-INITECH", "Initech", "Initech Inc", "billing@initech-qa.test", "EUR"),
    ("S-UMBRELLA", "Umbrella GmbH", "Umbrella Corp", "rechnung@umbrella-qa.test", "EUR"),
]
POS = [  # po, supplier, sku, qty, unit price, currency, cost centre
    ("PO-5001", "S-ACME", "SKU-A", 10, 50.00, "EUR", "CC-410"), ("PO-5001", "S-ACME", "SKU-B", 5, 120.00, "EUR", "CC-410"),
    ("PO-5002", "S-ACME", "SKU-A", 20, 50.00, "EUR", "CC-410"), ("PO-5003", "S-GLOBEX", "SKU-C", 4, 250.00, "USD", "CC-520"),
    ("PO-5006", "S-STARK", "SKU-X", 50, 500.00, "USD", "CC-630"), ("PO-5007", "S-INITECH", "SKU-D", 3, 90.00, "EUR", "CC-410"),
    ("PO-5008", "S-UMBRELLA", "SKU-E", 12, 102.875, "EUR", "CC-520"), ("PO-5010", "S-STARK", "SKU-Y", 30, 40.00, "USD", "CC-630"),
]
RECEIPTS = [  # po, sku, received qty
    ("PO-5001", "SKU-A", 10), ("PO-5001", "SKU-B", 5), ("PO-5002", "SKU-A", 20), ("PO-5003", "SKU-C", 4), ("PO-5006", "SKU-X", 50),
    ("PO-5007", "SKU-D", 3), ("PO-5008", "SKU-E", 12), ("PO-5010", "SKU-Y", 24),
]
RATES = {"EUR": 1, "USD": 1.08, "GBP": 0.86, "INR": 90.5}   # fixed rates for automated runs (1 EUR = ...)
APPROVAL_MATRIX = [  # up to amount (EUR), first approver, second approver needed
    (5000, "lead.ap@ooa-qa.test", "no"), (20000, "controller.ap@ooa-qa.test", "no"), (10000000, "controller.ap@ooa-qa.test", "yes"),
]
# the approvers' answers in the QA tenant, by request subject (others approve); "#2" is the second approval
DECISIONS = {"INV-2001": "decline", "INV-3001#2": "timeout"}
REQUESTERS = [("CC-410", "lead.ap@ooa-qa.test"), ("CC-520", "buyer.ap@ooa-qa.test"), ("CC-630", "requester.ap@ooa-qa.test")]
TAX_CODES = [(19, "V1"), (0, "V0")]   # SAP QA tax codes by rate


def money(v, style):
    s = "{:,.2f}".format(v)
    return s.replace(",", "X").replace(".", ",").replace("X", ".") if style == "de" else s


def lines_of(inv):
    key, sender, supplier, no, po, cur, items, tax, style, _ = inv
    net = sum(q * p for _, q, p in items)
    total = round(net * (1 + tax / 100), 2)
    out = ["OOA-QA TEST INVOICE (synthetic)", "Supplier: " + supplier, "Invoice number: " + no, "Invoice date: " + TODAY.isoformat(),
           "PO number: " + (po or "-"), "Currency: " + cur, "", "SKU  Qty  Unit price  Amount"]
    out += ["%s  %d  %s  %s" % (s, q, money(p, style), money(q * p, style)) for s, q, p in items]
    out += ["", "Net: " + money(net, style), "Tax %d%%: %s" % (tax, money(total - net, style)), "Total: " + money(total, style)]
    return out


def write(w):
    for inv in INVOICES:
        w("RW03/invoices/%s-%s.pdf" % (inv[0], inv[3]), pdf(lines_of(inv)))
    w("RW03/invoices.csv", [(mail_id(n), i[0], i[3], i[1], "RW03/invoices/%s-%s.pdf" % (i[0], i[3])) for n, i in enumerate(INVOICES)],
      ["key", "dossier", "invoice", "sender", "file"])
    w("RW03/requesters.csv", REQUESTERS, ["cost_center", "requester"])
    w("RW03/tax_codes.csv", TAX_CODES, ["rate", "tax_code"])
    w("RW03/period.json", '{"open_period": "%s", "closed_before": "%s"}\n' % (TODAY.strftime("%Y-%m"), TODAY.replace(day=1).isoformat()))
    w("RW03/suppliers.csv", SUPPLIERS, ["id", "name", "aliases", "sender", "currency"])
    w("RW03/pos.csv", POS, ["po", "supplier", "sku", "qty", "unit_price", "currency", "cost_center"])
    w("RW03/receipts.csv", RECEIPTS, ["po", "sku", "received"])
    w("RW03/approval_matrix.csv", APPROVAL_MATRIX, ["up_to_eur", "approver", "second_approver"])
    w("RW03/rates.json", '{"EUR": 1, "USD": 1.08, "GBP": 0.86, "INR": 90.5}\n')


def mail_id(n):
    """The QA mailbox gives the n-th invoice mail this id; every RW03 agent keys its records by it."""
    return "AAMkAP%04d" % (n + 1)


def seed():
    msgs = []
    for n, inv in enumerate(INVOICES):
        att = [{"@odata.type": "#microsoft.graph.fileAttachment", "name": "%s.pdf" % inv[3], "contentType": "application/pdf", "isInline": False,
                "contentBytes": base64.b64encode(pdf(lines_of(inv))).decode("ascii")}] if inv[9] else []
        msgs.append({"id": mail_id(n), "subject": "OOA-QA invoice %s" % inv[3], "isRead": False, "hasAttachments": bool(att),
                     "parentFolderId": "inbox", "receivedDateTime": (_dt.datetime.now(_dt.timezone.utc) - _dt.timedelta(hours=12 - n)).strftime("%Y-%m-%dT%H:%M:%SZ"),
                     "from": {"emailAddress": {"address": inv[1]}}, "toRecipients": [{"emailAddress": {"address": MAILBOX}}],
                     "body": {"contentType": "text", "content": "Please find invoice %s attached." % inv[3]}, "_attachments": att})
    prices = {"%s|%s" % (p[1], p[2]): [p[4], p[5]] for p in POS}   # the ERP's agreed unit prices (SOAP price service)
    sap = {"parked": [], "posted": [], "clients": ["QA-100"], "vendors": {sid: name for sid, name, *_ in SUPPLIERS},
           "pos": {po: sup for po, sup, *_ in POS}, "tax_codes": {code: rate for rate, code in TAX_CODES}}
    return {"mail": {MAILBOX: msgs}, "approvals": {"decisions": DECISIONS, "requests": []}, "sap": sap,
            "erp": {"prices": prices}}
