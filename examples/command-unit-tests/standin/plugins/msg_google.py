"""Google Workspace for the command unit tests, under /msgoogle/ (the toolkit's base_url replaces https://*.googleapis.com):
Sheets (/v4/spreadsheets), Drive (/drive/v3, /upload/drive/v3), Gmail (/gmail/v1/users/me), Calendar (/calendar/v3) and
Docs (/v1/documents). Made-up records only; POST /msgoogle/reset puts everything back as seeded.
"""
from __future__ import annotations

import base64
import copy
import email
import email.policy
import importlib.util
import json
import os
import re
import time
import uuid
from email.message import EmailMessage

_spec = importlib.util.spec_from_file_location("_msg_common", os.path.join(os.path.dirname(os.path.abspath(__file__)), "_msg_common.py"))
C = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(C)

PREFIX = "/msgoogle"
ME = "una.tester@ut-google.test"
FOLDER = "application/vnd.google-apps.folder"
SHEET = "application/vnd.google-apps.spreadsheet"
DOC = "application/vnd.google-apps.document"
S: dict = {}


def nid(p=""):
    return p + uuid.uuid4().hex[:16]


def now():
    return time.strftime("%Y-%m-%dT%H:%M:%S.000Z", time.gmtime())


def b64u(b):
    return base64.urlsafe_b64encode(b).decode().rstrip("=")


def unb64u(s):
    s = str(s).replace("+", "-").replace("/", "_").strip()
    return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))


def err(h, status, message, reason="notFound"):
    return h.send(status, {"error": {"code": status, "message": message, "errors": [{"reason": reason}]}})


def body(h):
    b = h.body()
    return b if isinstance(b, dict) else {}


# ---------------------------------------------------------------- seed

def mime(frm, to, subject, text, date, cc=None, atts=()):
    e = EmailMessage()
    e["From"] = frm
    e["To"] = to
    if cc:
        e["Cc"] = cc
    e["Subject"] = subject
    e["Date"] = date
    e["Message-ID"] = "<%s@ut-google.test>" % nid()
    e.set_content(text)
    e.add_alternative("<p>" + text + "</p>", subtype="html")
    for name, data in atts:
        e.add_attachment(data, maintype="application", subtype="octet-stream", filename=name)
    return e.as_bytes()


def add_msg(raw, labels, internal, thread=None):
    m = {"id": nid("m"), "threadId": thread or nid("t"), "labelIds": list(labels), "raw": raw, "internalDate": internal}
    S["msgs"].append(m)
    return m


def add_file(name, mime_type, parents=("root",), data=None, **kw):
    f = {"id": kw.pop("id", None) or nid("f"), "name": name, "mimeType": mime_type, "parents": list(parents), "trashed": False, "data": data,
         "createdTime": now(), "modifiedTime": now(), "owners": [{"emailAddress": ME}], "description": kw.pop("description", ""),
         "perms": [{"id": "perm-owner", "type": "user", "role": "owner", "emailAddress": ME, "displayName": "Una Tester"}]}
    f.update(kw)
    if data is not None:
        f["size"] = str(len(data))
    S["files"][f["id"]] = f
    return f


def new_sheet_book(title, names, sid=None):
    sid = sid or nid("ss")
    S["books"][sid] = {"title": title, "sheets": [{"sheetId": 0 if i == 0 else 1000 + i, "title": n, "index": i, "hidden": False, "grid": C.Grid()}
                                                  for i, n in enumerate(names or ["Sheet1"])]}
    add_file(title, SHEET, id=sid)
    return sid


def new_doc(title, text="", did=None):
    did = did or nid("doc")
    S["docs"][did] = {"title": title, "text": text}
    add_file(title, DOC, id=did)
    return did


def seed():
    S.clear()
    S.update({"msgs": [], "files": {}, "books": {}, "docs": {}, "events": {}, "labels": [
        {"id": x, "name": x, "type": "system"} for x in ("INBOX", "UNREAD", "STARRED", "IMPORTANT", "SENT", "TRASH", "SPAM", "DRAFT")]
        + [{"id": "Label_1", "name": "Invoices", "type": "user"}, {"id": "Label_2", "name": "Processed", "type": "user"}]})
    add_msg(mime("Acme AP <ap@acme.test>", ME, "Invoice 7 from Acme", "Please pay invoice 7.", "Wed, 18 Nov 2026 09:15:00 +0000", atts=[("inv-7.pdf", b"%PDF-1.7 seven")]),
            ["INBOX", "UNREAD", "Label_1"], "1795511700000")
    add_msg(mime("Acme AP <ap@acme.test>", ME, "Invoice 8 from Acme", "Please pay invoice 8.", "Thu, 19 Nov 2026 09:15:00 +0000", atts=[("inv-8.pdf", b"%PDF-1.7 eight")]),
            ["INBOX", "UNREAD", "Label_1"], "1795598100000")
    add_msg(mime("Ann Lee <ann.lee@ut-google.test>", ME + ", bob.ray@ut-google.test", "Team lunch", "Lunch on Friday?", "Tue, 17 Nov 2026 12:00:00 +0000",
                 cc="carol@ut-google.test"), ["INBOX"], "1795435200000")
    add_msg(mime("News <news@vendor.test>", ME, "Old newsletter", "News", "Thu, 01 Oct 2026 07:00:00 +0000"), ["INBOX"], "1791349200000")
    # Drive: /UT/readme.txt, /UT/Archive, /UT/Templates/letter.txt
    S["files"]["root"] = {"id": "root", "name": "My Drive", "mimeType": FOLDER, "parents": [], "trashed": False, "perms": [], "owners": [{"emailAddress": ME}]}
    ut = add_file("UT", FOLDER, id="fld-ut")
    add_file("Archive", FOLDER, parents=[ut["id"]], id="fld-archive")
    tpl = add_file("Templates", FOLDER, parents=[ut["id"]], id="fld-templates")
    add_file("letter.txt", "text/plain", parents=[tpl["id"]], data=b"Dear customer")
    rd = add_file("readme.txt", "text/plain", parents=[ut["id"]], data=b"Unit test file", id="file-readme")
    rd["perms"].append({"id": "perm-ann", "type": "user", "role": "reader", "emailAddress": "ann.lee@ut-google.test", "displayName": "Ann Lee"})
    rd["perms"].append({"id": "perm-domain", "type": "domain", "role": "commenter", "domain": "ut-google.test", "displayName": "ut-google.test"})
    # a spreadsheet with data, and a document
    sid = new_sheet_book("UT Budget", ["Sheet1", "Sheet2"], sid="ss-budget")
    S["files"][sid]["parents"] = [ut["id"]]
    g = S["books"][sid]["sheets"][0]["grid"]
    for r, row in enumerate([["Item", "Qty", "Price"], ["Pens", 10, 2.5], ["Paper", 4, 6], ["Ink", 2, 15]], 1):
        for c, v in enumerate(row, 1):
            g.set(r, c, v)
    g.cell(1, 1, True)["fmt"] = {"backgroundColor": {"red": 1, "green": 0, "blue": 0}, "textFormat": {"foregroundColor": {"red": 1, "green": 1, "blue": 1}}}
    did = new_doc("UT Letter", "Dear {{name}},\nYour order {{order}} is ready.\n", did="doc-letter")
    S["files"][did]["parents"] = [ut["id"]]
    S["events"]["primary"] = [{"id": "ev-review", "summary": "Month-end review", "start": {"dateTime": "2026-11-25T10:00:00Z"}, "end": {"dateTime": "2026-11-25T11:00:00Z"},
                               "location": "Room 4", "organizer": {"email": "ann.lee@ut-google.test"}, "attendees": [{"email": ME}], "status": "confirmed",
                               "htmlLink": "https://calendar.test/ev-review"}]


# ---------------------------------------------------------------- Gmail

def parsed(m):
    return email.message_from_bytes(m["raw"], policy=email.policy.default)


def payload(part, msg_id, atts):
    p = {"mimeType": part.get_content_type(), "filename": part.get_filename() or "",
         "headers": [{"name": k, "value": str(v)} for k, v in part.items()], "body": {"size": 0}}
    if part.is_multipart():
        p["parts"] = [payload(x, msg_id, atts) for x in part.iter_parts()]
    else:
        data = part.get_payload(decode=True) or b""
        if p["filename"]:
            aid = "att-%d" % len(atts)
            atts.append(data)
            p["body"] = {"attachmentId": aid, "size": len(data)}
        else:
            p["body"] = {"data": b64u(data), "size": len(data)}
    return p


def hdr(m, name):
    return str(parsed(m).get(name) or "")


def matches(m, q):
    if "TRASH" in m["labelIds"] and "in:trash" not in q:
        return False
    for group in re.findall(r"\{([^}]*)\}|(\S+:\"[^\"]*\"|\S+)", q):
        terms = group[0].split() if group[0] else [group[1]]
        if not any(term_ok(m, t) for t in terms):
            return False
    return True


def term_ok(m, t):
    k, _, v = t.partition(":")
    v = v.strip('"').lower()
    if not _:
        return t.lower() in (hdr(m, "Subject") + " " + parsed(m).get_body(("plain",)).get_content()).lower()
    if k == "from":
        return v in hdr(m, "From").lower()
    if k == "subject":
        return v in hdr(m, "Subject").lower()
    if k == "is":
        return ("UNREAD" in m["labelIds"]) == (v == "unread")
    if k == "label":
        lab = next((l for l in S["labels"] if l["name"].lower().replace(" ", "-") == v or l["id"].lower() == v), None)
        return bool(lab) and lab["id"] in m["labelIds"]
    if k == "has":
        return any(p.get_filename() for p in parsed(m).walk())
    if k in ("after", "before"):
        t0 = int(m["internalDate"]) // 1000
        return t0 >= int(v) if k == "after" else t0 < int(v)
    if k == "in":
        return True
    return True


def gmail(h, m, rest, q):
    msgs = S["msgs"]
    if rest == "/profile":
        return h.send(200, {"emailAddress": ME, "messagesTotal": len(msgs)})
    if rest == "/labels":
        return h.send(200, {"labels": S["labels"]})
    if rest == "/messages" and m == "GET":
        found = [x for x in sorted(msgs, key=lambda x: x["internalDate"], reverse=True) if matches(x, q.get("q", ""))]
        found = found[:int(q.get("maxResults", 100))]
        return h.send(200, {"messages": [{"id": x["id"], "threadId": x["threadId"]} for x in found], "resultSizeEstimate": len(found)})
    if rest == "/messages/send":
        b = body(h)
        raw = unb64u(b["raw"])
        mm = add_msg(raw, ["SENT"], str(int(time.time() * 1000)), b.get("threadId"))
        rcpt = (str(email.message_from_bytes(raw).get("To") or "") + "," + str(email.message_from_bytes(raw).get("Cc") or "")).lower()
        if ME in rcpt:
            add_msg(raw, ["INBOX", "UNREAD"], str(int(time.time() * 1000)), b.get("threadId"))
        return h.send(200, {"id": mm["id"], "threadId": mm["threadId"], "labelIds": mm["labelIds"]})
    if rest in ("/messages/batchModify", "/messages/batchDelete"):
        b = body(h)
        for x in [x for x in msgs if x["id"] in b.get("ids", [])]:
            if rest.endswith("Delete"):
                msgs.remove(x)
            else:
                x["labelIds"] = [l for l in x["labelIds"] if l not in b.get("removeLabelIds", [])] + [l for l in b.get("addLabelIds", []) if l not in x["labelIds"]]
        return h.send(204)
    mi = re.fullmatch(r"/messages/([^/]+)(/.*)?", rest)
    if not mi:
        raise KeyError(rest)
    msg = next((x for x in msgs if x["id"] == mi.group(1)), None)
    if msg is None:
        return err(h, 404, "Requested entity was not found.")
    sub = mi.group(2) or ""
    if sub == "" and m == "DELETE":
        msgs.remove(msg)
        return h.send(204)
    if sub == "":
        fmt = q.get("format", "full")
        out = {"id": msg["id"], "threadId": msg["threadId"], "labelIds": msg["labelIds"], "internalDate": msg["internalDate"],
               "snippet": hdr(msg, "Subject")[:40]}
        if fmt == "raw":
            out["raw"] = b64u(msg["raw"])
        elif fmt == "metadata":
            out["payload"] = {"headers": [{"name": k, "value": str(v)} for k, v in parsed(msg).items()]}
        elif fmt == "full":
            out["payload"] = payload(parsed(msg), msg["id"], [])
        return h.send(200, out)
    ma = re.fullmatch(r"/attachments/att-(\d+)", sub)
    if ma:
        atts = []
        payload(parsed(msg), msg["id"], atts)
        return h.send(200, {"data": b64u(atts[int(ma.group(1))]), "size": len(atts[int(ma.group(1))])})
    if sub == "/modify":
        b = body(h)
        msg["labelIds"] = [l for l in msg["labelIds"] if l not in b.get("removeLabelIds", [])] + [l for l in b.get("addLabelIds", []) if l not in msg["labelIds"]]
        return h.send(200, {"id": msg["id"], "labelIds": msg["labelIds"]})
    if sub == "/trash":
        msg["labelIds"] = [l for l in msg["labelIds"] if l != "INBOX"] + ["TRASH"]
        return h.send(200, {"id": msg["id"], "labelIds": msg["labelIds"]})
    raise KeyError(rest)


# ---------------------------------------------------------------- Drive

def split_and(q):
    parts, depth, cur, inq = [], 0, "", False
    i = 0
    while i < len(q):
        ch = q[i]
        if ch == "'" and (i == 0 or q[i - 1] != "\\"):
            inq = not inq
        if not inq and ch == "(":
            depth += 1
        if not inq and ch == ")":
            depth -= 1
        if not inq and depth == 0 and q[i:i + 5].lower() == " and ":
            parts.append(cur.strip())
            cur = ""
            i += 5
            continue
        cur += ch
        i += 1
    if cur.strip():
        parts.append(cur.strip())
    return parts


def lit(s):
    return s.strip()[1:-1].replace("\\'", "'").replace("\\\\", "\\") if s.strip().startswith("'") else s.strip()


def drive_match(f, q):
    for t in split_and(q or ""):
        if t.startswith("(") and t.endswith(")"):
            if not drive_match(f, t[1:-1]):
                return False
            continue
        mm = re.fullmatch(r"('(?:[^'\\]|\\.)*')\s+in\s+parents", t)
        if mm:
            if lit(mm.group(1)) not in f.get("parents", []):
                return False
            continue
        mm = re.fullmatch(r"(\w+)\s*(=|!=|contains|>|<|>=|<=)\s*(.+)", t)
        if not mm:
            continue
        k, op, v = mm.group(1), mm.group(2), lit(mm.group(3))
        have = f.get(k)
        if k == "trashed":
            have, v = str(bool(f.get("trashed"))).lower(), v.lower()
        have = "" if have is None else str(have)
        ok = {"=": have == v, "!=": have != v, "contains": v.lower() in have.lower(), ">": have > v, "<": have < v, ">=": have >= v, "<=": have <= v}[op]
        if not ok:
            return False
    return True


def file_json(f, h):
    j = {k: v for k, v in f.items() if k not in ("data", "perms")}
    j["webViewLink"] = "https://drive.test/" + f["id"]
    j["capabilities"] = {"canEdit": True, "canComment": True, "canDelete": True, "canTrash": True}
    return j


def multipart(raw, ctype):
    bnd = re.search(r'boundary="?([^";]+)"?', ctype).group(1).encode()
    parts = [p for p in raw.split(b"--" + bnd) if p.strip() not in (b"", b"--")]
    out = []
    for p in parts:
        head, _, data = p.lstrip(b"\r\n").partition(b"\r\n\r\n")
        out.append(data[:-2] if data.endswith(b"\r\n") else data)
    return out


def drive(h, m, rest, q, upload=False):
    files = S["files"]
    if rest == "/files" and upload:
        meta_raw, data = multipart(h.raw(), h.headers.get("Content-Type", ""))[:2]
        meta = json.loads(meta_raw.decode())
        f = add_file(meta["name"], meta.get("mimeType", "application/octet-stream"), parents=meta.get("parents") or ["root"], data=data, description=meta.get("description", ""))
        return h.send(200, {"id": f["id"]})
    if rest == "/files":
        if m == "POST":
            b = body(h)
            f = add_file(b["name"], b.get("mimeType", "application/octet-stream"), parents=b.get("parents") or ["root"])
            return h.send(200, file_json(f, h))
        found = [file_json(f, h) for f in files.values() if f["id"] != "root" and drive_match(f, q.get("q"))]
        return h.send(200, {"files": found[:int(q.get("pageSize", 1000))]})
    mf = re.fullmatch(r"/files/([^/]+)(/.*)?", rest)
    if not mf:
        raise KeyError(rest)
    f = files.get(mf.group(1))
    if f is None:
        return err(h, 404, "File not found: " + mf.group(1))
    sub = mf.group(2) or ""
    if sub == "":
        if upload:
            f["data"] = h.raw()
            f["size"] = str(len(f["data"]))
            f["modifiedTime"] = now()
            return h.send(200, {"id": f["id"]})
        if m == "GET":
            if q.get("alt") == "media":
                return C.sendb(h, 200, f.get("data") or b"", "application/octet-stream")
            return h.send(200, file_json(f, h))
        if m == "PATCH":
            b = body(h)
            for k in ("name", "trashed", "description"):
                if k in b:
                    f[k] = b[k]
            if q.get("addParents"):
                f["parents"] = [p for p in f["parents"] if p not in (q.get("removeParents") or "").split(",")]
                if q["addParents"] not in f["parents"]:
                    f["parents"].append(q["addParents"])
            return h.send(200, file_json(f, h))
        if m == "DELETE":
            del files[f["id"]]
            return h.send(204)
    if sub == "/export":
        mt = q.get("mimeType", "")
        data = ("%%PDF-1.7 export of %s" % f["name"]).encode() if "pdf" in mt else ("export of %s as %s" % (f["name"], mt)).encode()
        if f["id"] in S["docs"] and mt == "text/plain":
            data = S["docs"][f["id"]]["text"].encode()
        return C.sendb(h, 200, data, mt or "application/octet-stream")
    if sub == "/copy":
        b = body(h)
        cp = add_file(b.get("name") or "Copy of " + f["name"], f["mimeType"], parents=b.get("parents") or f["parents"], data=f.get("data"))
        return h.send(200, {"id": cp["id"]})
    if sub == "/permissions":
        if m == "POST":
            b = body(h)
            pm = dict(b, id=nid("perm-"))
            if pm.get("role") == "owner":
                for x in f["perms"]:
                    if x["role"] == "owner":
                        x["role"] = "writer"
            f["perms"].append(pm)
            return h.send(200, {"id": pm["id"]})
        return h.send(200, {"permissions": f["perms"]})
    mp = re.fullmatch(r"/permissions/([^/]+)", sub)
    if mp:
        pm = next((x for x in f["perms"] if x["id"] == mp.group(1)), None)
        if pm is None:
            return err(h, 404, "Permission not found")
        if m == "DELETE":
            f["perms"].remove(pm)
            return h.send(204)
        pm.update(body(h))
        return h.send(200, pm)
    raise KeyError(rest)


# ---------------------------------------------------------------- Sheets

def book_of(sid):
    if sid not in S["books"]:
        raise KeyError("spreadsheet " + sid)
    return S["books"][sid]


def sheet_by_title(bk, title):
    for s in bk["sheets"]:
        if s["title"].lower() == title.lower():
            return s
    raise ValueError("Unable to parse range: " + title)


def sheet_by_id(bk, sid):
    return next(s for s in bk["sheets"] if s["sheetId"] == sid)


def split_a1(rng):
    r = rng.strip()
    if "!" in r:
        sh, cells = r.rsplit("!", 1)
    elif re.fullmatch(r"\$?[A-Za-z]{0,3}\$?\d*(:\$?[A-Za-z]{0,3}\$?\d*)?", r) and r:
        sh, cells = None, r
    else:
        sh, cells = r, ""
    if sh and sh.startswith("'") and sh.endswith("'"):
        sh = sh[1:-1].replace("''", "'")
    return sh, cells


def resolve(bk, rng):
    sh_name, cells = split_a1(rng)
    sh = sheet_by_title(bk, sh_name) if sh_name else sorted(bk["sheets"], key=lambda s: s["index"])[0]
    g = sh["grid"]
    if not cells:
        u = g.used() or (1, 1, 1, 1)
        return sh, (1, 1, u[2], u[3])
    return sh, g.bound(*C.parse_range(cells))


def a1(sh, box):
    return "'%s'!%s" % (sh["title"].replace("'", "''"), C.addr(*box)) if " " in sh["title"] else "%s!%s" % (sh["title"], C.addr(*box))


def trimmed(rows):
    out = []
    for row in rows:
        while row and row[-1] in ("", None):
            row = row[:-1]
        out.append(row)
    while out and not out[-1]:
        out.pop()
    return out


def grid_range(bk, gr):
    sh = sheet_by_id(bk, gr.get("sheetId", 0))
    u = sh["grid"].used() or (1, 1, 1, 1)
    r0 = gr.get("startRowIndex", 0) + 1
    c0 = gr.get("startColumnIndex", 0) + 1
    r1 = gr.get("endRowIndex", max(u[2], r0))
    c1 = gr.get("endColumnIndex", max(u[3], c0))
    return sh, (r0, c0, r1, c1)


def sheets_batch(bk, reqs):
    replies = []
    for r in reqs:
        (k, v), = r.items()
        reply = {}
        if k == "addSheet":
            p = v.get("properties", {})
            if any(s["title"].lower() == p["title"].lower() for s in bk["sheets"]):
                raise ValueError('A sheet with the name "%s" already exists.' % p["title"])
            idx = p.get("index", len(bk["sheets"]))
            for s in bk["sheets"]:
                if s["index"] >= idx:
                    s["index"] += 1
            s = {"sheetId": max(x["sheetId"] for x in bk["sheets"]) + 1, "title": p["title"], "index": idx, "hidden": False, "grid": C.Grid()}
            bk["sheets"].append(s)
            reply = {"addSheet": {"properties": {k2: s[k2] for k2 in ("sheetId", "title", "index")}}}
        elif k == "deleteSheet":
            s = sheet_by_id(bk, v["sheetId"])
            bk["sheets"].remove(s)
            for i, x in enumerate(sorted(bk["sheets"], key=lambda x: x["index"])):
                x["index"] = i
        elif k == "updateSheetProperties":
            s = sheet_by_id(bk, v["properties"]["sheetId"])
            for f in v["fields"].split(","):
                s[f] = v["properties"][f]
        elif k in ("deleteRange", "insertRange"):
            sh, box = grid_range(bk, v["range"])
            dim = v["shiftDimension"]
            sh["grid"].shift(*box, ("Up" if dim == "ROWS" else "Left") if k == "deleteRange" else ("Down" if dim == "ROWS" else "Right"))
        elif k in ("insertDimension", "deleteDimension"):
            rg = v["range"]
            sh = sheet_by_id(bk, rg["sheetId"])
            a, b = rg["startIndex"] + 1, rg["endIndex"]
            box = (a, 1, b, 18278) if rg["dimension"] == "ROWS" else (1, a, 1048576, b)
            sh["grid"].shift(*box, ("Down" if rg["dimension"] == "ROWS" else "Right") if k == "insertDimension" else ("Up" if rg["dimension"] == "ROWS" else "Left"))
        elif k == "repeatCell":
            sh, (r0, c0, r1, c1) = grid_range(bk, v["range"])
            fmt = v["cell"]["userEnteredFormat"]
            for rr in range(r0, r1 + 1):
                for cc in range(c0, c1 + 1):
                    cur = sh["grid"].cell(rr, cc, True).setdefault("fmt", {})
                    for fk, fv in fmt.items():
                        if isinstance(fv, dict) and isinstance(cur.get(fk), dict):
                            cur[fk].update(fv)
                        else:
                            cur[fk] = copy.deepcopy(fv)
                    if "numberFormat" in fmt:
                        sh["grid"].cell(rr, cc, True)["nf"] = fmt["numberFormat"]["pattern"]
        elif k == "copyPaste":
            ssh, (r0, c0, r1, c1) = grid_range(bk, v["source"])
            dsh, (d0, e0, _, _) = grid_range(bk, v["destination"])
            snap = {(rr, cc): copy.deepcopy(ssh["grid"].cells.get((rr, cc), {})) for rr in range(r0, r1 + 1) for cc in range(c0, c1 + 1)}
            for (rr, cc), cl in snap.items():
                tr, tc = (d0 + rr - r0, e0 + cc - c0) if v.get("pasteOrientation") != "TRANSPOSE" else (d0 + cc - c0, e0 + rr - r0)
                if v.get("pasteType") == "PASTE_VALUES":
                    cl = {"v": ssh["grid"].value(rr, cc)}
                dsh["grid"].cells[(tr, tc)] = cl
        elif k in ("mergeCells", "unmergeCells", "updateBorders", "autoResizeDimensions"):
            pass
        else:
            raise ValueError("Unsupported request " + k)
        replies.append(reply)
    return replies


def sheets(h, m, rest, q):
    if rest == "" and m == "POST":
        b = body(h)
        names = [s["properties"]["title"] for s in b.get("sheets", [])]
        sid = new_sheet_book(b["properties"]["title"], names)
        return h.send(200, {"spreadsheetId": sid, "spreadsheetUrl": "https://docs.google.com/spreadsheets/d/%s/edit" % sid})
    mb = re.fullmatch(r"/([^/:]+)(:batchUpdate)?(?:/values/(.+?)(:append|:clear)?)?", rest)
    if not mb:
        raise KeyError(rest)
    bk = book_of(mb.group(1))
    if mb.group(2):
        try:
            return h.send(200, {"spreadsheetId": mb.group(1), "replies": sheets_batch(bk, body(h).get("requests", []))})
        except ValueError as e:
            return err(h, 400, str(e), "badRequest")
    if mb.group(3) is None:
        if q.get("ranges"):
            sh, (r0, c0, r1, c1) = resolve(bk, q["ranges"])
            rows = []
            for rr in range(r0, r1 + 1):
                vals = []
                for cc in range(c0, c1 + 1):
                    fmt = sh["grid"].cell(rr, cc).get("fmt", {})
                    eff = {}
                    if "backgroundColor" in fmt:
                        eff["backgroundColor"] = fmt["backgroundColor"]
                    if "foregroundColor" in fmt.get("textFormat", {}):
                        eff["textFormat"] = {"foregroundColor": fmt["textFormat"]["foregroundColor"]}
                    vals.append({"effectiveFormat": eff} if eff else {})
                rows.append({"values": vals})
            return h.send(200, {"sheets": [{"data": [{"rowData": rows}]}]})
        return h.send(200, {"spreadsheetId": mb.group(1), "properties": {"title": bk["title"]},
                            "sheets": [{"properties": {"sheetId": s["sheetId"], "title": s["title"], "index": s["index"], "hidden": s["hidden"],
                                                       "gridProperties": {"rowCount": 1000, "columnCount": 26}}}
                                       for s in sorted(bk["sheets"], key=lambda s: s["index"])]})
    try:
        sh, box = resolve(bk, mb.group(3))
    except ValueError as e:
        return err(h, 400, str(e), "badRequest")
    g = sh["grid"]
    op = mb.group(4)
    if op == ":clear":
        g.clear(*box, "Contents")
        return h.send(200, {"clearedRange": a1(sh, box)})
    if op == ":append":
        vals = body(h).get("values", [])
        u = g.used()
        start = (u[2] + 1) if u else box[0]
        for i, row in enumerate(vals):
            for j, v in enumerate(row):
                g.set(start + i, box[1] + j, v)
        out = (start, box[1], start + len(vals) - 1, box[1] + max((len(r) for r in vals), default=1) - 1)
        return h.send(200, {"updates": {"updatedRange": a1(sh, out), "updatedRows": len(vals)}})
    if m == "PUT":
        vals = body(h).get("values", [])
        n = 0
        for i, row in enumerate(vals):
            for j, v in enumerate(row):
                g.set(box[0] + i, box[1] + j, v)
                n += 1
        out = (box[0], box[1], box[0] + len(vals) - 1, box[1] + max((len(r) for r in vals), default=1) - 1)
        return h.send(200, {"updatedRange": a1(sh, out), "updatedCells": n, "updatedRows": len(vals)})
    render = q.get("valueRenderOption", "FORMATTED_VALUE")
    kind = {"FORMULA": "f", "UNFORMATTED_VALUE": "v"}.get(render, "t")
    rows = trimmed(g.matrix(*box, kind))
    if q.get("majorDimension") == "COLUMNS":
        width = max((len(r) for r in rows), default=0)
        rows = trimmed([[r[c] if c < len(r) else "" for r in rows] for c in range(width)])
        rows = [trimmed([col])[0] if col else col for col in rows]
    return h.send(200, {"range": a1(sh, box), "majorDimension": q.get("majorDimension", "ROWS"), "values": rows} if rows else {"range": a1(sh, box)})


# ---------------------------------------------------------------- Calendar and Docs

def utc(t):
    v = t.get("dateTime")
    if v and not re.search(r"(Z|[+-]\d\d:\d\d)$", v):
        t = dict(t, dateTime=v + "Z")
    return t


def calendar(h, m, rest, q):
    mc = re.fullmatch(r"/calendars/([^/]+)/events(?:/([^/]+))?", rest)
    if not mc:
        raise KeyError(rest)
    evs = S["events"].setdefault(mc.group(1), [])
    if mc.group(2) is None:
        if m == "POST":
            ev = dict(body(h), id=nid("ev"), status="confirmed", htmlLink="https://calendar.test/" + nid(), organizer={"email": ME})
            ev["start"], ev["end"] = utc(ev["start"]), utc(ev["end"])
            if "conferenceData" in ev:
                ev["hangoutLink"] = "https://meet.test/" + nid()
            evs.append(ev)
            return h.send(200, ev)
        lo, hi = q.get("timeMin", ""), q.get("timeMax", "")

        def key(e, k):
            t = e[k]
            return (t.get("dateTime") or t.get("date") + "T00:00:00Z").replace("+00:00", "Z")
        found = [e for e in evs if key(e, "start") < hi.replace("+00:00", "Z") and key(e, "end") > lo.replace("+00:00", "Z")
                 and (not q.get("q") or q["q"].lower() in json.dumps(e).lower())]
        found.sort(key=lambda e: key(e, "start"))
        return h.send(200, {"items": found[:int(q.get("maxResults", 250))]})
    ev = next((e for e in evs if e["id"] == mc.group(2)), None)
    if ev is None:
        return err(h, 404, "Not Found")
    if m == "DELETE":
        evs.remove(ev)
        return h.send(204)
    if m == "PATCH":
        b = body(h)
        for k in ("start", "end"):
            if k in b:
                b[k] = utc(b[k])
        ev.update(b)
    return h.send(200, ev)


def docs(h, m, rest, q):
    if rest == "" and m == "POST":
        did = new_doc(body(h).get("title", "Untitled"))
        return h.send(200, {"documentId": did, "title": S["docs"][did]["title"]})
    md = re.fullmatch(r"/([^/:]+)(:batchUpdate)?", rest)
    d = S["docs"].get(md.group(1)) if md else None
    if d is None:
        return err(h, 404, "Requested entity was not found.")
    if md.group(2):
        replies = []
        for r in body(h).get("requests", []):
            if "insertText" in r:
                d["text"] += r["insertText"]["text"]
                replies.append({})
            elif "replaceAllText" in r:
                v = r["replaceAllText"]
                find, rep = v["containsText"]["text"], v.get("replaceText", "")
                flags = 0 if v["containsText"].get("matchCase") else re.I
                d["text"], n = re.subn(re.escape(find), lambda _m: rep, d["text"], flags=flags)
                replies.append({"replaceAllText": {"occurrencesChanged": n}} if n else {"replaceAllText": {}})
        return h.send(200, {"documentId": md.group(1), "replies": replies})
    text = d["text"] if d["text"].endswith("\n") else d["text"] + "\n"
    paras = [{"paragraph": {"elements": [{"textRun": {"content": line + "\n"}}]}} for line in text[:-1].split("\n")]
    return h.send(200, {"documentId": md.group(1), "title": d["title"], "body": {"content": [{"sectionBreak": {}}] + paras}})


# ---------------------------------------------------------------- entry

def google(h, m, path, q):
    if not S:
        seed()
    p = path[len(PREFIX):]
    with core.LOCK:   # noqa: F821 - core is set by standin.py
        if p.startswith("/v4/spreadsheets"):
            return sheets(h, m, p[len("/v4/spreadsheets"):], q)
        if p.startswith("/drive/v3"):
            return drive(h, m, p[len("/drive/v3"):], q)
        if p.startswith("/upload/drive/v3"):
            return drive(h, m, p[len("/upload/drive/v3"):], q, upload=True)
        if p.startswith("/gmail/v1/users/me"):
            return gmail(h, m, p[len("/gmail/v1/users/me"):], q)
        if p.startswith("/calendar/v3"):
            return calendar(h, m, p[len("/calendar/v3"):], q)
        if p.startswith("/v1/documents"):
            return docs(h, m, p[len("/v1/documents"):], q)
    raise KeyError(path)


def reset(h, m, path, q):
    with core.LOCK:   # noqa: F821
        seed()
    return h.send(200, {"reset": "msgoogle"})


ROUTES = [(PREFIX + "/reset", reset, None), (PREFIX + "/", google, "msgoogle")]
SEED = {}
