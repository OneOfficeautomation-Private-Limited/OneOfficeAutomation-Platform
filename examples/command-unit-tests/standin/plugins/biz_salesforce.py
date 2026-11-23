"""Salesforce for the command unit tests, under /biz/salesforce: OAuth token and revoke, SOQL query/queryAll,
sObject records (by id and by external id), describe, sObject Collections, Files (ContentVersion, ContentDocument,
ContentDocumentLink) and classic Attachments. Made-up records only."""
import base64
import re
import uuid

P = "/biz/salesforce"

SEED = {"biz_salesforce": {
    "revoked": [],
    "objects": {
        "Account": [
            {"Id": "001UT0000000001AAA", "Name": "Northwind Traders", "Industry": "Retail", "ERP_Number__c": "ERP-1001", "Rating": "Hot"},
            {"Id": "001UT0000000002AAA", "Name": "Contoso Bank", "Industry": "Banking", "ERP_Number__c": "ERP-1002", "Rating": "Warm"},
            {"Id": "001UT0000000003AAA", "Name": "Fabrikam Stores", "Industry": "Retail", "ERP_Number__c": "ERP-1003", "Rating": "Cold"},
            {"Id": "001UT0000000004AAA", "Name": "O'Brien Pharmacy", "Industry": "Healthcare", "ERP_Number__c": "ERP-1004", "Rating": "Warm", "_deleted": True},
        ],
        "Contact": [
            {"Id": "003UT0000000001AAA", "FirstName": "Asha", "LastName": "Patel", "Email": "asha.patel@ut.example", "AccountId": "001UT0000000001AAA"},
        ],
        "Case": [],
        "ContentDocument": [
            {"Id": "069UT0000000001AAA", "Title": "Signed contract", "FileExtension": "pdf", "ContentSize": 17, "LatestPublishedVersionId": "068UT0000000001AAA"},
            {"Id": "069UT0000000002AAA", "Title": "Price list", "FileExtension": "xlsx", "ContentSize": 12, "LatestPublishedVersionId": "068UT0000000002AAA"},
        ],
        "ContentVersion": [
            {"Id": "068UT0000000001AAA", "ContentDocumentId": "069UT0000000001AAA", "Title": "Signed contract", "PathOnClient": "Signed contract.pdf", "VersionData": base64.b64encode(b"%PDF-1.4 contract").decode()},
            {"Id": "068UT0000000002AAA", "ContentDocumentId": "069UT0000000002AAA", "Title": "Price list", "PathOnClient": "Price list.xlsx", "VersionData": base64.b64encode(b"PK price list").decode()},
        ],
        "ContentDocumentLink": [
            {"Id": "06AUT0000000001AAA", "ContentDocumentId": "069UT0000000001AAA", "LinkedEntityId": "001UT0000000001AAA", "ShareType": "V", "Visibility": "AllUsers"},
            {"Id": "06AUT0000000002AAA", "ContentDocumentId": "069UT0000000002AAA", "LinkedEntityId": "001UT0000000001AAA", "ShareType": "V", "Visibility": "AllUsers"},
        ],
        "Attachment": [
            {"Id": "00PUT0000000001AAA", "ParentId": "001UT0000000001AAA", "Name": "old-terms.txt", "BodyLength": 9, "ContentType": "text/plain", "Body": base64.b64encode(b"old terms").decode()},
        ],
    },
}}

PREFIX = {"Account": "001", "Contact": "003", "Case": "500", "ContentVersion": "068", "ContentDocument": "069", "ContentDocumentLink": "06A", "Attachment": "00P"}

DESCRIBE = {
    "Account": {"name": "Account", "label": "Account", "labelPlural": "Accounts", "keyPrefix": "001", "createable": True, "updateable": True, "deletable": True, "custom": False,
                "fields": [
                    {"name": "Id", "label": "Account ID", "type": "id", "length": 18, "createable": False, "nillable": False, "defaultedOnCreate": True, "updateable": False, "picklistValues": [], "referenceTo": []},
                    {"name": "Name", "label": "Account Name", "type": "string", "length": 255, "createable": True, "nillable": False, "defaultedOnCreate": False, "updateable": True, "picklistValues": [], "referenceTo": []},
                    {"name": "Industry", "label": "Industry", "type": "picklist", "length": 255, "createable": True, "nillable": True, "defaultedOnCreate": False, "updateable": True,
                     "picklistValues": [{"value": "Retail", "active": True}, {"value": "Banking", "active": True}, {"value": "Shipping", "active": False}], "referenceTo": []},
                    {"name": "ParentId", "label": "Parent Account ID", "type": "reference", "length": 18, "createable": True, "nillable": True, "defaultedOnCreate": False, "updateable": True, "picklistValues": [], "referenceTo": ["Account"]},
                    {"name": "ERP_Number__c", "label": "ERP Number", "type": "string", "length": 20, "createable": True, "nillable": True, "defaultedOnCreate": False, "updateable": True, "picklistValues": [], "referenceTo": []},
                ]},
}


def _st():
    return core.STATE.setdefault("biz_salesforce", {"revoked": [], "objects": {}})


def _new_id(obj):
    return PREFIX.get(obj, "a0U") + "UT" + uuid.uuid4().hex[:13].upper()


def _authorized(h):
    a = h.headers.get("Authorization", "")
    if not a.startswith("Bearer ") or len(a) < 12 or a[7:] in _st()["revoked"]:
        h.send(401, [{"message": "Session expired or invalid", "errorCode": "INVALID_SESSION_ID"}])
        return False
    return True


def _bytes(h, data, ctype="application/octet-stream"):
    h.send_response(200)
    h.send_header("Content-Type", ctype)
    h.send_header("Content-Length", str(len(data)))
    h.end_headers()
    h.wfile.write(data)


# ---------------------------------------------------------------- SOQL (enough for the toolkit's queries)

LIT = r"'(?:\\.|[^'\\])*'|\((?:[^()']|'(?:\\.|[^'\\])*')*\)|[\w.:+-]+"


def _lit(text):
    text = text.strip()
    if text.startswith("("):
        return [_lit(x) for x in re.findall(LIT, text[1:-1])]
    if text.startswith("'"):
        return re.sub(r"\\(.)", lambda m: {"n": "\n", "r": "\r", "t": "\t"}.get(m.group(1), m.group(1)), text[1:-1])
    if text.lower() in ("true", "false"):
        return text.lower() == "true"
    if text.lower() == "null":
        return None
    return text


def _same(a, b):
    if a is None or b is None:
        return a is None and b is None
    if isinstance(a, bool) or isinstance(b, bool):
        return str(a).lower() == str(b).lower()
    try:
        return float(a) == float(b)
    except (TypeError, ValueError):
        return str(a) == str(b)


def _value(objects, obj, rec, field):
    if "." not in field:
        return rec.get(field)
    rel, rest = field.split(".", 1)
    ref = rec.get(rel + "Id")
    other = next((r for r in objects.get(rel, []) if r["Id"] == ref), None)
    return None if other is None else _value(objects, rel, other, rest)


def _nest(objects, obj, rec, fields):
    out = {"attributes": {"type": obj, "url": "/services/data/v61.0/sobjects/%s/%s" % (obj, rec["Id"])}}
    for f in fields:
        if "." in f:
            rel, rest = f.split(".", 1)
            out.setdefault(rel, {"attributes": {"type": rel}})[rest] = _value(objects, obj, rec, f)
        else:
            out[f] = rec.get(f)
    return out


def soql(objects, q, all_rows):
    m = re.match(r"\s*SELECT\s+(.+?)\s+FROM\s+(\w+)(?:\s+WHERE\s+(.+?))?(?:\s+ORDER\s+BY\s+([\w.]+)(?:\s+(ASC|DESC))?)?(?:\s+LIMIT\s+(\d+))?\s*$", q, re.I | re.S)
    if not m:
        raise ValueError("unsupported SOQL: " + q)
    fields = [f.strip() for f in m.group(1).split(",")]
    obj = m.group(2)
    rows = [r for r in objects.get(obj, []) if all_rows or not r.get("_deleted")]
    if m.group(3):
        for c in re.finditer(r"([\w.]+)\s*(=|!=|IN)\s*(" + LIT + ")", m.group(3), re.I):
            f, op, v = c.group(1), c.group(2).upper(), _lit(c.group(3))
            if op == "IN":
                rows = [r for r in rows if any(_same(_value(objects, obj, r, f), x) for x in v)]
            elif op == "=":
                rows = [r for r in rows if _same(_value(objects, obj, r, f), v)]
            else:
                rows = [r for r in rows if not _same(_value(objects, obj, r, f), v)]
    if m.group(4):
        rows = sorted(rows, key=lambda r: str(_value(objects, obj, r, m.group(4)) or ""), reverse=(m.group(5) or "").upper() == "DESC")
    total = len(rows)
    if m.group(6):
        rows = rows[:int(m.group(6))]
    return obj, fields, rows, total


# ---------------------------------------------------------------- the routes

def salesforce(h, m, path, q):
    st = _st()
    objects = st.setdefault("objects", {})
    p = path[len(P):]
    if p == "/services/oauth2/token" and m == "POST":
        b = h.body()
        g = b.get("grant_type")
        if not b.get("client_id") or not b.get("client_secret") or g not in ("client_credentials", "password") or (g == "password" and not (b.get("username") and b.get("password"))):
            return h.send(400, {"error": "invalid_grant", "error_description": "authentication failure"})
        return h.send(200, {"access_token": "00DUT!" + uuid.uuid4().hex, "instance_url": "http://127.0.0.1:%d%s" % (core.PORT, P),
                            "token_type": "Bearer", "id": "https://login.salesforce.com/id/00DUT/005UT", "issued_at": "1795000000000"})
    if p == "/services/oauth2/revoke" and m == "POST":
        tok = h.body().get("token", "")
        if not tok:
            return h.send(400, {"error": "unsupported_token_type"})
        with core.LOCK:
            st["revoked"].append(tok)
        return h.send(200, "")
    if not _authorized(h):
        return
    mq = re.match(r"/services/data/v[\d.]+/(query|queryAll)$", p)
    if mq and m == "GET":
        with core.LOCK:
            obj, fields, rows, total = soql(objects, q.get("q", ""), mq.group(1) == "queryAll")
            recs = [_nest(objects, obj, r, fields) for r in rows]
        return h.send(200, {"totalSize": total, "done": True, "records": recs})
    mc = re.match(r"/services/data/v[\d.]+/composite/sobjects(?:/(\w+)/(\w+))?$", p)
    if mc and m in ("POST", "PATCH"):
        b = h.body()
        out = []
        with core.LOCK:
            for rec in b.get("records", []):
                rec = {k: v for k, v in rec.items() if k != "attributes"}
                obj = (b.get("records")[0].get("attributes") or {}).get("type") or mc.group(1)
                rows = objects.setdefault(obj, [])
                if not rec.get("Name") and obj == "Account" and m == "POST":
                    out.append({"success": False, "errors": [{"statusCode": "REQUIRED_FIELD_MISSING", "message": "Required fields are missing: [Name]", "fields": ["Name"]}]})
                    continue
                if m == "POST":
                    rec["Id"] = _new_id(obj)
                    rows.append(rec)
                    out.append({"id": rec["Id"], "success": True, "errors": []})
                elif mc.group(1):
                    f = mc.group(2)
                    found = next((r for r in rows if str(r.get(f)) == str(rec.get(f))), None)
                    if found:
                        found.update(rec)
                        out.append({"id": found["Id"], "success": True, "created": False, "errors": []})
                    else:
                        rec["Id"] = _new_id(obj)
                        rows.append(rec)
                        out.append({"id": rec["Id"], "success": True, "created": True, "errors": []})
                else:
                    found = next((r for r in rows if r["Id"] == rec.get("Id")), None)
                    if found:
                        found.update(rec)
                        out.append({"id": found["Id"], "success": True, "errors": []})
                    else:
                        out.append({"id": rec.get("Id"), "success": False, "errors": [{"statusCode": "ENTITY_IS_DELETED", "message": "entity is deleted", "fields": []}]})
        return h.send(200, out)
    ms = re.match(r"/services/data/v[\d.]+/sobjects/(\w+)(?:/([^/]+))?(?:/([^/]+))?$", p)
    if not ms:
        raise KeyError(path)
    obj, a, b = ms.groups()
    with core.LOCK:
        rows = objects.setdefault(obj, [])
        if a == "describe" and m == "GET":
            d = DESCRIBE.get(obj)
            return h.send(200, d) if d else h.send(404, [{"errorCode": "NOT_FOUND", "message": "The requested resource does not exist"}])
        if a is None and m == "POST":
            rec = dict(h.body())
            rec["Id"] = _new_id(obj)
            if obj == "ContentVersion":
                doc_id = rec.get("ContentDocumentId")
                data = base64.b64decode(rec.get("VersionData", ""))
                name = rec.get("PathOnClient", "")
                ext = name.rsplit(".", 1)[1] if "." in name else ""
                if doc_id:
                    doc = next((d for d in objects["ContentDocument"] if d["Id"] == doc_id), None)
                    if doc is None:
                        return h.send(400, [{"errorCode": "INVALID_ID_FIELD", "message": "invalid ContentDocumentId"}])
                    doc.update({"LatestPublishedVersionId": rec["Id"], "ContentSize": len(data), "FileExtension": ext})
                else:
                    doc = {"Id": _new_id("ContentDocument"), "Title": rec.get("Title"), "FileExtension": ext, "ContentSize": len(data), "LatestPublishedVersionId": rec["Id"]}
                    objects.setdefault("ContentDocument", []).append(doc)
                    rec["ContentDocumentId"] = doc["Id"]
                    if rec.get("FirstPublishLocationId"):
                        objects.setdefault("ContentDocumentLink", []).append({"Id": _new_id("ContentDocumentLink"), "ContentDocumentId": doc["Id"],
                                                                              "LinkedEntityId": rec["FirstPublishLocationId"], "ShareType": "V", "Visibility": "AllUsers"})
            rows.append(rec)
            return h.send(201, {"id": rec["Id"], "success": True, "errors": []})
        if a and b in ("VersionData", "Body") and m == "GET":
            rec = next((r for r in rows if r["Id"] == a), None)
            if rec is None:
                raise KeyError(path)
            return _bytes(h, base64.b64decode(rec.get(b, "")))
        if b is not None:      # by external id: /sobjects/Obj/Field/value
            found = [r for r in rows if str(r.get(a)) == b and not r.get("_deleted")]
            if m == "GET":
                if len(found) > 1:
                    return h.send(300, ["/services/data/v61.0/sobjects/%s/%s" % (obj, r["Id"]) for r in found])
                rec = found[0] if found else None
            elif m == "PATCH":
                body = h.body()
                if found:
                    found[0].update(body)
                    return h.send(200, {"id": found[0]["Id"], "success": True, "created": False, "errors": []})
                rec = {a: b, "Id": _new_id(obj), **body}
                rows.append(rec)
                return h.send(201, {"id": rec["Id"], "success": True, "created": True, "errors": []})
            else:
                rec = None
        else:
            rec = next((r for r in rows if r["Id"] == a and not r.get("_deleted")), None)
        if rec is None:
            return h.send(404, [{"errorCode": "NOT_FOUND", "message": "The requested resource does not exist"}])
        if m == "GET":
            fields = [f for f in q.get("fields", "").split(",") if f]
            out = {"attributes": {"type": obj, "url": "/services/data/v61.0/sobjects/%s/%s" % (obj, rec["Id"])}}
            out.update({k: rec.get(k) for k in fields} if fields else {k: v for k, v in rec.items() if not k.startswith("_") and k not in ("VersionData", "Body")})
            out["Id"] = rec["Id"]
            return h.send(200, out)
        if m == "PATCH":
            rec.update(h.body())
            return h.send(204)
        if m == "DELETE":
            rec["_deleted"] = True
            return h.send(204)
    raise KeyError(path)


ROUTES = [(P + "/", salesforce, "biz_salesforce")]
