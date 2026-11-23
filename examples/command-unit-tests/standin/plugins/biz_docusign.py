"""DocuSign eSignature (REST v2.1) for the command unit tests, under /biz/docusign/restapi: envelopes, recipients,
documents (PDF and ZIP downloads), templates, embedded signing, custom fields and form data. Made-up account
ut-account; state key biz_docusign."""
import copy
import re
import uuid

TEMPLATE = "11111111-2222-3333-4444-555555555555"
DONE = "aaaaaaaa-bbbb-cccc-dddd-000000000001"

SEED = {"biz_docusign": {
    "templates": {TEMPLATE: {"templateId": TEMPLATE, "name": "UT Mutual NDA", "description": "Unit-test NDA", "folderName": "Legal",
                             "lastModified": "2026-10-01T00:00:00Z", "emailSubject": "Please sign the NDA",
                             "documents": [{"documentId": "1", "name": "nda.pdf"}],
                             "recipients": {"signers": [{"roleName": "Client", "recipientId": "1"}], "carbonCopies": [{"roleName": "Legal", "recipientId": "2"}]}}},
    "envelopes": {DONE: {"envelopeId": DONE, "status": "completed", "emailSubject": "UT signed contract", "sentDateTime": "2026-11-01T09:00:00Z",
                         "deliveredDateTime": "2026-11-01T09:05:00Z", "completedDateTime": "2026-11-01T10:00:00Z", "statusChangedDateTime": "2026-11-01T10:00:00Z",
                         "_recipients": {"signers": [{"recipientId": "1", "name": "Ann Lee", "email": "ann.lee@ut.example", "routingOrder": "1", "status": "completed",
                                                      "deliveredDateTime": "2026-11-01T09:05:00Z", "signedDateTime": "2026-11-01T10:00:00Z"}]},
                         "_documents": [{"documentId": "1", "name": "contract.pdf", "type": "content", "pages": [{}, {}], "order": "1"}],
                         "_custom": [{"fieldId": "1", "name": "ContractNumber", "value": "C-1042", "show": "false"}],
                         "_form": [{"name": "Company", "value": "Northwind"}, {"name": "Start date", "value": "2026-12-01"}]}},
}}

PDF = b"%PDF-1.4\n1 0 obj<</Type/Catalog>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF\n"
ZIP = b"PK\x05\x06" + b"\x00" * 18          # an empty ZIP archive


def _st():
    return core.STATE.setdefault("biz_docusign", copy.deepcopy(SEED["biz_docusign"]))


def _bytes(h, data, ctype):
    h.send_response(200)
    h.send_header("Content-Type", ctype)
    h.send_header("Content-Length", str(len(data)))
    h.end_headers()
    h.wfile.write(data)


def _pub(e):
    return {k: v for k, v in e.items() if not k.startswith("_")}


def _missing(h, eid):
    return h.send(400, {"errorCode": "ENVELOPE_DOES_NOT_EXIST", "message": "The envelope specified either does not exist or you have no rights to it. " + eid})


def docusign(h, m, path, q):
    a = h.headers.get("Authorization", "")
    if not a.startswith("Bearer ") or len(a) < 12:
        return h.send(401, {"errorCode": "AUTHORIZATION_INVALID_TOKEN", "message": "The access token provided is expired, revoked or malformed."})
    st = _st()
    mp = re.match(r"/biz/docusign/restapi/v2\.1/accounts/([^/]+)(/.*)$", path)
    if not mp:
        raise KeyError(path)
    p = mp.group(2)
    with core.LOCK:
        if p == "/envelopes" and m == "POST":
            b = h.body()
            eid = str(uuid.uuid4())
            env = {"envelopeId": eid, "status": b.get("status", "created"), "emailSubject": b.get("emailSubject"), "statusChangedDateTime": core.now_iso(),
                   "_recipients": {}, "_documents": [], "_custom": [], "_form": []}
            if b.get("templateId"):
                t = st["templates"].get(b["templateId"])
                if t is None:
                    return h.send(400, {"errorCode": "TEMPLATE_ID_INVALID", "message": "Invalid template ID."})
                env["emailSubject"] = env["emailSubject"] or t["emailSubject"]
                env["_recipients"]["signers"] = [{"recipientId": str(i + 1), "name": r["name"], "email": r["email"], "roleName": r["roleName"],
                                                  "routingOrder": "1", "status": "created"} for i, r in enumerate(b.get("templateRoles", []))]
                env["_documents"] = [{"documentId": "1", "name": "nda.pdf", "type": "content", "pages": [{}], "order": "1"}]
                env["_form"] = [{"name": tab["tabLabel"], "value": tab["value"]} for r in b.get("templateRoles", [])
                                for tab in (r.get("tabs") or {}).get("textTabs", [])]
            else:
                for kind, rs in (b.get("recipients") or {}).items():
                    env["_recipients"][kind] = [dict(r, status="created") for r in rs]
                env["_documents"] = [{"documentId": d["documentId"], "name": d["name"], "type": "content", "pages": [{}], "order": d["documentId"]}
                                     for d in b.get("documents", [])]
            if env["status"] == "sent":
                env["sentDateTime"] = core.now_iso()
            st["envelopes"][eid] = env
            return h.send(201, {"envelopeId": eid, "status": env["status"], "statusDateTime": core.now_iso(), "uri": "/envelopes/" + eid})
        if p == "/envelopes" and m == "GET":
            want = [s for s in q.get("status", "").split(",") if s]
            text = q.get("search_text", "").lower()
            found = [_pub(e) for e in st["envelopes"].values()
                     if (not want or e["status"] in want) and (not text or text in str(e.get("emailSubject", "")).lower())]
            start, count = int(q.get("start_position", 0)), int(q.get("count", 100))
            return h.send(200, {"envelopes": found[start:start + count], "totalSetSize": str(len(found)), "resultSetSize": str(len(found[start:start + count]))})
        if p == "/templates" and m == "GET":
            text = q.get("search_text", "").lower()
            found = [{k: v for k, v in t.items() if k not in ("recipients", "documents")} for t in st["templates"].values()
                     if not text or text in (t["name"] + " " + t["description"]).lower()]
            return h.send(200, {"envelopeTemplates": found, "totalSetSize": str(len(found))})
        mt = re.match(r"/templates/([^/]+)$", p)
        if mt:
            t = st["templates"].get(mt.group(1))
            return h.send(200, t) if t else h.send(400, {"errorCode": "TEMPLATE_ID_INVALID", "message": "Invalid template ID."})
        me = re.match(r"/envelopes/([^/]+)(?:/(recipients|documents|custom_fields|form_data|views/recipient)(?:/([^/]+))?)?$", p)
        if not me:
            raise KeyError(path)
        eid, sub, item = me.groups()
        env = st["envelopes"].get(eid)
        if env is None:
            return _missing(h, eid)
        if sub is None:
            if m == "GET":
                return h.send(200, _pub(env))
            if m == "PUT":
                b = h.body()
                if q.get("resend_envelope") == "true":
                    env["_resent"] = env.get("_resent", 0) + 1
                    return h.send(200, {"envelopeId": eid})
                if b.get("status") == "sent":
                    if env["status"] != "created":
                        return h.send(400, {"errorCode": "ENVELOPE_CANNOT_SEND", "message": "Only drafts can be sent."})
                    env["status"], env["sentDateTime"] = "sent", core.now_iso()
                elif b.get("status") == "voided":
                    if env["status"] in ("completed", "voided"):
                        return h.send(400, {"errorCode": "ENVELOPE_CANNOT_VOID_INVALID_STATE", "message": "Only envelopes in progress can be voided."})
                    env["status"], env["voidedReason"] = "voided", b.get("voidedReason")
                env["statusChangedDateTime"] = core.now_iso()
                return h.send(200, {"envelopeId": eid})
        if sub == "recipients":
            r = env["_recipients"]
            if m == "GET":
                return h.send(200, dict(r, recipientCount=str(sum(len(v) for v in r.values()))))
            b = h.body()
            for kind, rs in b.items():
                for rec in rs:
                    lst = r.setdefault(kind, [])
                    old = next((x for x in lst if x["recipientId"] == rec["recipientId"]), None)
                    if m == "PUT":
                        if old is None:
                            return h.send(400, {"errorCode": "INVALID_RECIPIENT_ID", "message": "A recipient ID is missing or invalid."})
                        old.update(rec)
                    else:
                        lst.append(dict(rec, status="created", routingOrder=rec.get("routingOrder", "1")))
            return h.send(200 if m == "PUT" else 201, {"recipientUpdateResults": []})
        if sub == "documents":
            if item is None:
                return h.send(200, {"envelopeId": eid, "envelopeDocuments": env["_documents"]})
            if item == "archive":
                return _bytes(h, ZIP, "application/zip")
            if item in ("combined", "certificate") or any(d["documentId"] == item for d in env["_documents"]):
                return _bytes(h, PDF, "application/pdf")
            return h.send(400, {"errorCode": "DOCUMENT_DOES_NOT_EXIST", "message": "The document specified does not exist."})
        if sub == "custom_fields":
            if m == "GET":
                return h.send(200, {"textCustomFields": env["_custom"], "listCustomFields": []})
            for f in h.body().get("textCustomFields", []):
                old = next((x for x in env["_custom"] if x["fieldId"] == f.get("fieldId")), None)
                if old is not None:
                    old.update(f)
                else:
                    env["_custom"].append(dict(f, fieldId=str(len(env["_custom"]) + 1)))
            return h.send(200 if m == "PUT" else 201, {"textCustomFields": env["_custom"]})
        if sub == "form_data":
            return h.send(200, {"envelopeId": eid, "status": env["status"], "formData": env["_form"]})
        if sub == "views/recipient" and m == "POST":
            b = h.body()
            signers = env["_recipients"].get("signers", [])
            ok = any(s.get("email") == b.get("email") and s.get("name") == b.get("userName") and s.get("clientUserId") == b.get("clientUserId") for s in signers)
            if not ok:
                return h.send(400, {"errorCode": "UNKNOWN_ENVELOPE_RECIPIENT", "message": "The recipient you have identified is not a valid recipient of the specified envelope."})
            return h.send(201, {"url": "https://demo.docusign.example/Signing/StartInSession.aspx?t=ut-" + uuid.uuid4().hex[:10]})
    raise KeyError(path)


ROUTES = [("/biz/docusign/", docusign, "biz_docusign")]
