"""Microsoft 365 for the command unit tests: Microsoft Graph under /msgraph/v1.0/ (mail, calendar, OneDrive/SharePoint
files, Excel online workbooks, Teams, chats, Planner, SharePoint lists, Entra ID users and groups, $batch) and a
SharePoint site's own REST API under /msgsp/sites/<site>/_api/ (lists, items, attachments).

Made-up records only. POST /msgraph/reset puts everything back as seeded (each unit-test agent does this first).
The real-world tests keep using the core stand-in's /graph/v1.0/ routes; this plugin lives under its own prefix.
"""
from __future__ import annotations

import base64
import copy
import importlib.util
import io
import json
import os
import re
import time
import uuid
import zipfile
from email.message import EmailMessage

_spec = importlib.util.spec_from_file_location("_msg_common", os.path.join(os.path.dirname(os.path.abspath(__file__)), "_msg_common.py"))
C = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(C)

PREFIX = "/msgraph/v1.0"
ME = {"id": "0b1c2d3e-4f50-6172-8394-a5b6c7d8e9f0", "displayName": "Una Tester", "mail": "una.tester@ut-m365.test",
      "userPrincipalName": "una.tester@ut-m365.test", "jobTitle": "RPA developer", "department": "Automation",
      "officeLocation": "Pune", "mobilePhone": "+91 90000 00001", "accountEnabled": True}
ANN = {"id": "1c2d3e4f-5061-7283-94a5-b6c7d8e9f0a1", "displayName": "Ann Lee", "mail": "ann.lee@ut-m365.test",
       "userPrincipalName": "ann.lee@ut-m365.test", "jobTitle": "Analyst", "department": "Finance", "accountEnabled": True,
       "signInActivity": {"lastSignInDateTime": "2026-11-20T08:00:00Z"}}
BOB = {"id": "2d3e4f50-6172-8394-a5b6-c7d8e9f0a1b2", "displayName": "Bob Ray", "mail": "bob.ray@ut-m365.test",
       "userPrincipalName": "bob.ray@ut-m365.test", "jobTitle": "Clerk", "department": "Finance", "accountEnabled": False}
G_FIN = "3e4f5061-7283-94a5-b6c7-d8e9f0a1b2c3"
G_ALL = "4f506172-8394-a5b6-c7d8-e9f0a1b2c3d4"
TEAM = "5061-team-ut-finance"
NOW = "2026-11-20T09:00:00Z"
S: dict = {}


def nid(p=""):
    return p + uuid.uuid4().hex[:16]


def b64(s):
    return base64.b64encode(s if isinstance(s, bytes) else s.encode()).decode()


# ---------------------------------------------------------------- seed

def _msg(subject, frm, body, received, read=False, atts=None, folder="inbox", to=None, cc=None):
    return {"id": nid("AAMk"), "subject": subject, "from": {"emailAddress": {"address": frm, "name": frm.split("@")[0]}},
            "toRecipients": [{"emailAddress": {"address": a}} for a in (to or [ME["mail"]])],
            "ccRecipients": [{"emailAddress": {"address": a}} for a in (cc or [])], "bccRecipients": [], "replyTo": [],
            "receivedDateTime": received, "sentDateTime": received, "isRead": read, "importance": "normal",
            "flag": {"flagStatus": "notFlagged"}, "categories": [], "conversationId": nid("conv"),
            "internetMessageId": "<" + nid() + "@ut-m365.test>", "body": {"contentType": "html", "content": body},
            "parentFolderId": folder, "attachments": atts or [], "hasAttachments": bool(atts)}


def _att(name, data, inline=False):
    return {"@odata.type": "#microsoft.graph.fileAttachment", "id": nid("att"), "name": name, "contentType": "application/octet-stream",
            "isInline": inline, "contentBytes": b64(data), "size": len(data)}


def _xlsx(names):
    z = io.BytesIO()
    with zipfile.ZipFile(z, "w") as f:
        f.writestr("xl/workbook.xml", "<workbook><sheets>" + "".join('<sheet name="%s"/>' % n for n in names) + "</sheets></workbook>")
    return z.getvalue()


def seed():
    S.clear()
    S["users"] = [copy.deepcopy(ME), copy.deepcopy(ANN), copy.deepcopy(BOB)]
    S["groups"] = [{"id": G_FIN, "displayName": "UT Finance", "mail": "ut-finance@ut-m365.test", "securityEnabled": True, "description": "Finance team"},
                   {"id": G_ALL, "displayName": "UT Everyone", "mail": "ut-everyone@ut-m365.test", "securityEnabled": False, "description": "All staff"}]
    S["members"] = {G_FIN: [ANN["id"], BOB["id"]], G_ALL: [ME["id"], G_FIN]}
    S["manager"] = {ANN["id"]: ME["id"]}
    S["folders"] = [{"id": f, "displayName": n, "parent": None} for f, n in
                    (("inbox", "Inbox"), ("archive", "Archive"), ("sentitems", "Sent Items"), ("deleteditems", "Deleted Items"), ("drafts", "Drafts"))]
    S["folders"].append({"id": "fld-invoices", "displayName": "Invoices", "parent": "inbox"})
    S["mail"] = [
        _msg("Invoice 7 from Acme", "ap@acme.test", "<p>Please pay invoice 7.</p>", "2026-11-18T09:15:00Z", atts=[_att("inv-7.pdf", b"%PDF-1.7 invoice 7"), _att("logo.png", b"PNGDATA", True)]),
        _msg("Invoice 8 from Acme", "ap@acme.test", "<p>Please pay invoice 8.</p>", "2026-11-19T09:15:00Z", atts=[_att("inv-8.pdf", b"%PDF-1.7 invoice 8")]),
        _msg("Team lunch", "ann.lee@ut-m365.test", "<p>Lunch on Friday?</p>", "2026-11-17T12:00:00Z", read=True, to=[ME["mail"], BOB["mail"]], cc=["carol@ut-m365.test"]),
        _msg("Old newsletter", "news@vendor.test", "<p>News</p>", "2026-10-01T07:00:00Z", read=True),
    ]
    S["calendars"] = [{"id": "cal-default", "name": "Calendar"}, {"id": "cal-holidays", "name": "Team holidays"}]
    S["events"] = [{"id": nid("AAMkEV"), "subject": "Month-end review", "calendar": "cal-default", "type": "singleInstance",
                    "start": {"dateTime": "2026-11-25T10:00:00.0000000", "timeZone": "UTC"}, "end": {"dateTime": "2026-11-25T11:00:00.0000000", "timeZone": "UTC"},
                    "location": {"displayName": "Room 4"}, "organizer": {"emailAddress": {"address": ANN["mail"]}},
                    "attendees": [{"emailAddress": {"address": ME["mail"]}, "type": "required"}], "webLink": "https://outlook.test/ev/1", "attachments": []}]
    # drives: me/drive
    root = {"id": "root-me", "name": "root", "parent": None, "folder": True}
    items = {root["id"]: root}
    S["drives"] = {"me": {"id": "drive-me", "items": items, "root": root["id"]}}

    def add(parent, name, data=None, folder=False):
        it = {"id": nid("item-"), "name": name, "parent": parent, "folder": folder, "created": NOW, "modified": NOW,
              "data": data, "versions": [], "perms": [{"id": "perm-owner", "roles": ["owner"], "grantedToV2": {"user": {"email": ME["mail"], "displayName": ME["displayName"]}}}]}
        items[it["id"]] = it
        return it
    ut = add(root["id"], "UT", folder=True)
    add(ut["id"], "Archive", folder=True)
    rd = add(ut["id"], "readme.txt", b"Unit test file, version 2")
    rd["versions"] = [{"id": "2.0", "data": b"Unit test file, version 2"}, {"id": "1.0", "data": b"Unit test file, version 1"}]
    rd["perms"].append({"id": "perm-ann", "roles": ["read"], "grantedToV2": {"user": {"email": ANN["mail"], "displayName": "Ann Lee"}}})
    add(ut["id"], "report.docx", b"PK fake word document")
    bk = add(ut["id"], "Budget.xlsx", _xlsx(["Sheet1", "Sheet2"]))
    wb = workbook(bk)
    g = wb["sheets"][0]["grid"]
    for r, row in enumerate([["Item", "Qty", "Price"], ["Pens", 10, 2.5], ["Paper", 4, 6], ["Ink", 2, 15]], 1):
        for c, v in enumerate(row, 1):
            g.set(r, c, v)
    g.cell(1, 1, True)["fmt"] = {"font": {"bold": True, "color": "#000000", "name": "Calibri", "size": 11}, "fill": {"color": "#FFF2CC"}}
    wb["tables"].append({"id": "{T-ORDERS}", "name": "Orders", "sheet": "Sheet1", "box": (6, 1, 8, 2), "hasHeaders": True, "style": "TableStyleMedium2", "showHeaders": True})
    for r, row in enumerate([["Order", "Amount"], ["A-1", 100], ["A-2", 250]], 6):
        for c, v in enumerate(row, 1):
            g.set(r, c, v)
    # Teams
    S["teams"] = {TEAM: {"id": TEAM, "displayName": "UT Finance", "description": "Finance team", "visibility": "private", "isArchived": False,
                         "webUrl": "https://teams.test/t/fin", "channels": {}}}
    for cid, name in (("19:general@thread.tacv2", "General"), ("19:invoices@thread.tacv2", "Invoices")):
        S["teams"][TEAM]["channels"][cid] = {"id": cid, "displayName": name, "description": name + " channel", "membershipType": "standard",
                                            "email": name.lower() + "@ut-m365.test", "createdDateTime": "2026-01-05T09:00:00Z", "webUrl": "https://teams.test/c/" + name, "messages": []}
    S["teams"][TEAM]["channels"]["19:general@thread.tacv2"]["messages"] = [
        {"id": "1700000000001", "createdDateTime": "2026-11-18T09:00:00Z", "subject": "Welcome", "from": {"user": {"displayName": "Ann Lee"}},
         "body": {"contentType": "html", "content": "<p>Hello &amp; welcome</p>"}, "replyToId": None, "webUrl": "https://teams.test/m/1", "replies": []},
        {"id": "1700000000002", "createdDateTime": "2026-11-19T09:00:00Z", "subject": None, "from": {"user": {"displayName": "Bob Ray"}},
         "body": {"contentType": "text", "content": "Invoices are due"}, "replyToId": None, "webUrl": "https://teams.test/m/2", "replies": []}]
    S["chats"] = {"19:chat-ann@unq.gbl.spaces": {"id": "19:chat-ann@unq.gbl.spaces", "topic": None, "chatType": "oneOnOne", "lastUpdatedDateTime": "2026-11-19T10:00:00Z",
                                                 "members": [ME["id"], ANN["id"]], "messages": [
                                                     {"id": "c1", "messageType": "message", "createdDateTime": "2026-11-19T10:00:00Z", "from": {"user": {"displayName": "Ann Lee"}},
                                                      "body": {"contentType": "html", "content": "<p>See you at 3</p>"}, "attachments": []},
                                                     {"id": "c0", "messageType": "systemEventMessage", "createdDateTime": "2026-11-18T10:00:00Z", "body": {"content": ""}}]}}
    # Planner
    S["plans"] = {"plan-ut-1": {"id": "plan-ut-1", "title": "Month end", "createdDateTime": "2026-11-01T09:00:00Z", "container": {"containerId": TEAM}, "group": TEAM}}
    S["tasks"] = {"task-ut-1": {"id": "task-ut-1", "planId": "plan-ut-1", "bucketId": "bucket-todo", "title": "Close ledger", "percentComplete": 0,
                                "dueDateTime": "2026-11-30T17:00:00Z", "startDateTime": None, "createdDateTime": "2026-11-01T09:00:00Z", "completedDateTime": None,
                                "assignments": {ANN["id"]: {}}, "priority": 5, "etag": 'W/"1"'}}
    # SharePoint site Finance: lists shared by Graph and the site's REST API
    S["sites"] = {"finance": {"title": "Finance", "lists": {}}}
    lst = new_list("finance", "Purchase requests", "genericList", "What the team buys")
    lst["columns"].append({"id": nid("col-"), "name": "Status", "displayName": "Status"})
    lst["columns"].append({"id": nid("col-"), "name": "field_1", "displayName": "Amount"})
    for t, st, amt in (("Laptop", "Open", 1200), ("Chair", "Closed", 150), ("Monitor", "Open", 300)):
        add_item(lst, {"Title": t, "Status": st, "field_1": amt})
    lst["items"][0]["attachments"] = [{"FileName": "quote.pdf", "data": b"%PDF quote"}]


def new_list(site, name, template="genericList", description=""):
    lid = str(uuid.uuid4())
    lst = {"id": lid, "name": name.replace(" ", ""), "displayName": name, "description": description, "template": template,
           "webUrl": "https://ut.sharepoint.test/sites/Finance/Lists/" + name.replace(" ", ""), "items": [], "next": 1,
           "columns": [{"id": nid("col-"), "name": "Title", "displayName": "Title"}]}
    S["sites"].setdefault(site, {"title": site.title(), "lists": {}})["lists"][lid] = lst
    return lst


def add_item(lst, fields):
    it = {"id": str(lst["next"]), "fields": dict(fields), "attachments": []}
    lst["next"] += 1
    lst["items"].append(it)
    return it


def workbook(item):
    if item.get("wb") is None:
        names = ["Sheet1"]
        try:
            with zipfile.ZipFile(io.BytesIO(item.get("data") or b"")) as z:
                names = re.findall(r'<sheet name="([^"]+)"', z.read("xl/workbook.xml").decode()) or names
        except (zipfile.BadZipFile, KeyError):
            pass
        item["wb"] = {"sheets": [{"id": "{%s}" % nid(), "name": n, "visibility": "Visible", "grid": C.Grid()} for n in names], "tables": [], "next_table": 1}
    return item["wb"]


# ---------------------------------------------------------------- small helpers

def err(h, status, code, message):
    return h.send(status, {"error": {"code": code, "message": message}})


def clean(d):
    return {k: v for k, v in d.items() if not k.startswith("_") and k not in ("attachments", "parentFolderId", "calendar", "messages", "replies", "channels", "members", "data", "grid", "etag")}


def page(h, items, q, key="value"):
    top = int(q.get("$top", 0) or 0)
    return h.send(200, {key: items[:top] if top else items})


def body(h):
    b = h.body()
    return b if isinstance(b, dict) else {}


def user_by(ref):
    ref = ref.strip("'")
    for u in S["users"]:
        if ref.lower() in (u["id"].lower(), u["userPrincipalName"].lower(), str(u.get("mail", "")).lower()):
            return u
    return None


def is_me(box):
    return box in ("me", ME["id"]) or box.lower() == ME["mail"]


# ---------------------------------------------------------------- mail

def folder_id(fid):
    for f in S["folders"]:
        if f["id"].lower() == fid.lower():
            return f["id"]
    return None


def mail(h, m, rest, q):
    msgs = S["mail"]
    if rest == "/sendMail" and m == "POST":
        b = body(h)
        msg = b.get("message", {})
        to = [r["emailAddress"]["address"] for r in msg.get("toRecipients", [])]
        atts = [dict(a, id=nid("att"), isInline=False) for a in msg.get("attachments", [])]
        base = {"subject": msg.get("subject", ""), "from": msg.get("from") or {"emailAddress": {"address": ME["mail"]}},
                "toRecipients": msg.get("toRecipients", []), "ccRecipients": msg.get("ccRecipients", []), "bccRecipients": msg.get("bccRecipients", []),
                "replyTo": msg.get("replyTo", []), "receivedDateTime": C_now(), "sentDateTime": C_now(), "importance": msg.get("importance", "normal"),
                "flag": {"flagStatus": "notFlagged"}, "categories": [], "conversationId": nid("conv"), "internetMessageId": "<" + nid() + "@ut-m365.test>",
                "body": {"contentType": str(msg.get("body", {}).get("contentType", "Text")).lower(), "content": msg.get("body", {}).get("content", "")},
                "attachments": atts, "hasAttachments": bool(atts)}
        if b.get("saveToSentItems", True):
            msgs.append(dict(copy.deepcopy(base), id=nid("AAMk"), parentFolderId="sentitems", isRead=True))
        if any(a.lower() == ME["mail"] for a in to):
            msgs.append(dict(copy.deepcopy(base), id=nid("AAMk"), parentFolderId="inbox", isRead=False))
        return h.send(202)
    if rest == "" or rest.startswith("?"):
        return h.send(200, ME)
    mf = re.fullmatch(r"/mailFolders(?:/([^/]+)/childFolders)?", rest)
    if mf and m == "GET":
        parent = folder_id(mf.group(1)) if mf.group(1) else None
        found = [f for f in S["folders"] if f["parent"] == parent and C.odata(q.get("$filter"), f)]
        return h.send(200, {"value": [{"id": f["id"], "displayName": f["displayName"]} for f in found]})
    mm = re.fullmatch(r"/mailFolders/([^/]+)/messages", rest)
    if mm and m == "GET":
        fid = folder_id(mm.group(1))
        if fid is None:
            return err(h, 404, "ErrorInvalidIdMalformed", "Folder not found")
        found = [x for x in msgs if x["parentFolderId"] == fid and C.odata(q.get("$filter"), x)]
        found.sort(key=lambda x: x["receivedDateTime"], reverse=not q.get("$orderby", "").endswith("asc"))
        return page(h, [clean(x) for x in found], q)
    mi = re.fullmatch(r"/messages/([^/]+)(/.*)?", rest)
    if not mi:
        raise KeyError(rest)
    mid, sub = mi.group(1), mi.group(2) or ""
    msg = next((x for x in msgs if x["id"] == mid), None)
    if msg is None:
        return err(h, 404, "ErrorItemNotFound", "The specified object was not found in the store.")
    if sub == "":
        if m == "GET":
            out = clean(msg)
            if "text" in str(h.headers.get("Prefer", "")) and msg["body"]["contentType"].lower() == "html":
                out["body"] = {"contentType": "text", "content": re.sub(r"<[^>]+>", "", msg["body"]["content"]).strip()}
            return h.send(200, out)
        if m == "PATCH":
            msg.update(body(h))
            return h.send(200, clean(msg))
        if m == "DELETE":
            msgs.remove(msg)
            return h.send(204)
    if sub == "/move":
        dest = folder_id(str(body(h).get("destinationId", ""))) or "inbox"
        msg["parentFolderId"] = dest
        msg["id"] = nid("AAMk")
        return h.send(201, clean(msg))
    if sub == "/attachments":
        if m == "POST":
            a = dict(body(h), id=nid("att"), isInline=False)
            msg["attachments"].append(a)
            msg["hasAttachments"] = True
            return h.send(201, a)
        return h.send(200, {"value": msg["attachments"]})
    ma = re.fullmatch(r"/attachments/([^/]+)/\$value", sub)
    if ma:
        a = next(x for x in msg["attachments"] if x["id"] == ma.group(1))
        return C.sendb(h, 200, base64.b64decode(a["contentBytes"]), "application/octet-stream")
    if sub == "/$value":
        return C.sendb(h, 200, mime_of(msg), "message/rfc822")
    op = sub[1:]
    if op in ("reply", "replyAll", "forward"):
        sent = respond_copy(msg, op, body(h))
        sent["parentFolderId"] = "sentitems"
        msgs.append(sent)
        return h.send(202)
    if op in ("createReply", "createReplyAll", "createForward"):
        d = respond_copy(msg, op[6].lower() + op[7:], body(h))
        d["parentFolderId"] = "drafts"
        d["isDraft"] = True
        msgs.append(d)
        return h.send(201, clean(d))
    if op == "send":
        msg["parentFolderId"] = "sentitems"
        return h.send(202)
    raise KeyError(rest)


def C_now():
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


def respond_copy(msg, op, b):
    d = copy.deepcopy(msg)
    d.update({"id": nid("AAMk"), "isRead": True, "receivedDateTime": C_now(), "attachments": [], "hasAttachments": False,
              "subject": ("FW: " if op == "forward" else "RE: ") + msg["subject"],
              "body": {"contentType": "html", "content": "<html><body>" + str(b.get("comment", "")) + "<hr>" + msg["body"]["content"] + "</body></html>"},
              "from": {"emailAddress": {"address": ME["mail"]}}})
    if op == "forward":
        d["toRecipients"] = b.get("toRecipients", [])
    else:
        d["toRecipients"] = [msg["from"]] + ([r for r in msg["toRecipients"] if r["emailAddress"]["address"].lower() != ME["mail"]] if op == "replyAll" else [])
    extra = b.get("message") or {}
    d.update({k: v for k, v in extra.items()})
    d["_op"] = op
    return d


def mime_of(msg):
    e = EmailMessage()
    e["From"] = msg["from"]["emailAddress"]["address"]
    e["To"] = ", ".join(r["emailAddress"]["address"] for r in msg["toRecipients"])
    e["Subject"] = msg["subject"]
    e["Message-ID"] = msg["internetMessageId"]
    e.set_content(re.sub(r"<[^>]+>", "", msg["body"]["content"]))
    for a in msg["attachments"]:
        e.add_attachment(base64.b64decode(a["contentBytes"]), maintype="application", subtype="octet-stream", filename=a["name"])
    return e.as_bytes()


# ---------------------------------------------------------------- calendar

def calendar(h, m, rest, q):
    evs = S["events"]
    if rest == "/calendars" and m == "GET":
        return h.send(200, {"value": S["calendars"]})
    mc = re.fullmatch(r"(?:/calendars/([^/]+))?/(events|calendarView)", rest)
    if mc:
        cal = mc.group(1) or "cal-default"
        if m == "POST":
            ev = dict(body(h), id=nid("AAMkEV"), calendar=cal, type="singleInstance", webLink="https://outlook.test/ev/" + nid(),
                      organizer={"emailAddress": {"address": ME["mail"]}}, attachments=[])
            if ev.get("isOnlineMeeting"):
                ev["onlineMeeting"] = {"joinUrl": "https://teams.test/join/" + nid()}
            evs.append(ev)
            return h.send(201, clean(ev))
        lo, hi = q.get("startDateTime", "0").rstrip("Z"), q.get("endDateTime", "9").rstrip("Z")
        found = [e for e in evs if e["calendar"] == cal and e["start"]["dateTime"][:19] < hi and e["end"]["dateTime"][:19] > lo]
        found.sort(key=lambda e: e["start"]["dateTime"])
        return h.send(200, {"value": [clean(e) for e in found]})
    if rest == "/findMeetingTimes" and m == "POST":
        b = body(h)
        slot = b["timeConstraint"]["timeSlots"][0]
        start = slot["start"]["dateTime"][:16]
        mins = int(re.search(r"PT(\d+)M", b.get("meetingDuration", "PT30M")).group(1))
        sugg = []
        t0 = time.mktime(time.strptime(start, "%Y-%m-%dT%H:%M"))
        for k in range(min(int(b.get("maxCandidates", 2)), 2)):
            s0 = time.strftime("%Y-%m-%dT%H:%M:%S", time.localtime(t0 + k * 3600))
            s1 = time.strftime("%Y-%m-%dT%H:%M:%S", time.localtime(t0 + k * 3600 + mins * 60))
            sugg.append({"meetingTimeSlot": {"start": {"dateTime": s0, "timeZone": "UTC"}, "end": {"dateTime": s1, "timeZone": "UTC"}},
                         "confidence": 100.0, "organizerAvailability": "free"})
        return h.send(200, {"meetingTimeSuggestions": sugg, "emptySuggestionsReason": ""})
    me_ = re.fullmatch(r"/events/([^/]+)(/.*)?", rest)
    if not me_:
        raise KeyError(rest)
    ev = next((e for e in evs if e["id"] == me_.group(1)), None)
    if ev is None:
        return err(h, 404, "ErrorItemNotFound", "The specified object was not found in the store.")
    sub = me_.group(2) or ""
    if sub == "":
        if m == "GET":
            return h.send(200, clean(ev))
        if m == "PATCH":
            for k, v in body(h).items():
                if v is None:
                    ev.pop(k, None)
                else:
                    ev[k] = v
            return h.send(200, clean(ev))
        if m == "DELETE":
            evs.remove(ev)
            return h.send(204)
    if sub == "/attachments" and m == "POST":
        a = dict(body(h), id=nid("att"))
        ev["attachments"].append(a)
        return h.send(201, a)
    if sub in ("/cancel", "/accept", "/tentativelyAccept", "/decline"):
        ev["responseStatus"] = {"response": sub[1:]}
        if sub == "/cancel":
            ev["isCancelled"] = True
        return h.send(202)
    raise KeyError(rest)


# ---------------------------------------------------------------- drive and Excel online

def drive_of(key):
    k = key.lower()
    if k in ("me/drive",) or k.startswith("users/") or k.startswith("drives/") or k.startswith("sites/"):
        return S["drives"]["me"]
    raise KeyError(key)


def children(d, pid):
    return [i for i in d["items"].values() if i["parent"] == pid and not i.get("deleted")]


def by_path(d, path):
    cur = d["items"][d["root"]]
    for part in [p for p in path.split("/") if p]:
        nxt = next((i for i in children(d, cur["id"]) if i["name"].lower() == part.lower()), None)
        if nxt is None:
            return None
        cur = nxt
    return cur


def item_path(d, it):
    parts = []
    while it["parent"]:
        parts.insert(0, it["name"])
        it = d["items"][it["parent"]]
    return "/".join(parts)


def item_json(h, d, it):
    host = "http://" + h.headers.get("Host", "127.0.0.1")
    p = item_path(d, it)
    parent = d["items"].get(it["parent"]) if it["parent"] else None
    j = {"id": it["id"], "name": it["name"], "createdDateTime": it.get("created", NOW), "lastModifiedDateTime": it.get("modified", NOW),
         "webUrl": host + "/msgdrive/" + p, "lastModifiedBy": {"user": {"displayName": ME["displayName"]}},
         "parentReference": {"driveId": d["id"], "id": parent["id"] if parent else None, "path": "/drive/root:" + ("/" + item_path(d, parent) if parent and parent["parent"] else "")}}
    if it["folder"]:
        j["folder"] = {"childCount": len(children(d, it["id"]))}
    else:
        j["file"] = {"mimeType": "application/octet-stream"}
        j["size"] = len(it.get("data") or b"")
    if not it["parent"]:
        j["root"] = {}
    return j


def free_name(d, pid, name, behaviour):
    taken = {i["name"].lower(): i for i in children(d, pid)}
    if name.lower() not in taken:
        return name, None
    if behaviour == "replace":
        return name, taken[name.lower()]
    if behaviour == "rename":
        stem, ext = os.path.splitext(name)
        n = 1
        while (stem + " " + str(n) + ext).lower() in taken:
            n += 1
        return stem + " " + str(n) + ext, None
    raise FileExistsError(name)


def new_item(d, pid, name, data=None, folder=False):
    it = {"id": nid("item-"), "name": name, "parent": pid, "folder": folder, "created": C_now(), "modified": C_now(), "data": data,
          "versions": [] if folder else [{"id": "1.0", "data": data}],
          "perms": [{"id": "perm-owner", "roles": ["owner"], "grantedToV2": {"user": {"email": ME["mail"], "displayName": ME["displayName"]}}}]}
    d["items"][it["id"]] = it
    return it


def drive(h, m, p, q):
    md = re.match(r"^/(me/drive|users/[^/]+/drive|drives/[^/]+|sites/.+?/drive)(/.*)$", p)
    if not md:
        raise KeyError(p)
    d = drive_of(md.group(1))
    rest = md.group(2)
    it, path, sub = None, None, ""
    if rest.startswith("/root:/"):
        s = rest[7:]
        i = s.find(":")
        path, sub = (s, "") if i < 0 else (s[:i], s[i + 1:])
        it = by_path(d, path)
    elif rest.startswith("/root"):
        it, sub = d["items"][d["root"]], rest[5:]
    elif rest.startswith("/items/"):
        mi = re.match(r"/items/([^/]+)(.*)", rest)
        it, sub = d["items"].get(mi.group(1)), mi.group(2)
    else:
        raise KeyError(p)
    behaviour = q.get("@microsoft.graph.conflictBehavior")
    if it is None or it.get("deleted"):
        if sub == "/content" and m == "PUT" and path:
            folder = by_path(d, "/".join(path.split("/")[:-1]))
            if folder is None:
                folder = d["items"][d["root"]]
                for part in path.split("/")[:-1]:
                    nxt = next((i for i in children(d, folder["id"]) if i["name"].lower() == part.lower()), None)
                    folder = nxt or new_item(d, folder["id"], part, folder=True)
            it = new_item(d, folder["id"], path.split("/")[-1], h.raw())
            return h.send(201, item_json(h, d, it))
        return err(h, 404, "itemNotFound", "The resource could not be found.")
    if sub == "" and m == "GET":
        return h.send(200, item_json(h, d, it))
    if sub == "/content":
        if m == "PUT":
            if behaviour == "fail":
                return err(h, 409, "nameAlreadyExists", "The specified item name already exists.")
            if behaviour == "rename":
                name, _ = free_name(d, it["parent"], it["name"], "rename")
                it = new_item(d, it["parent"], name, h.raw())
                return h.send(201, item_json(h, d, it))
            it["data"] = h.raw()
            it["wb"] = None
            it["modified"] = C_now()
            it["versions"].insert(0, {"id": "%d.0" % (len(it["versions"]) + 1), "data": it["data"]})
            return h.send(200, item_json(h, d, it))
        if q.get("format") == "pdf":
            return C.sendb(h, 200, b"%PDF-1.7\n% stand-in conversion of " + it["name"].encode() + b"\n%%EOF\n", "application/pdf")
        return C.sendb(h, 200, it.get("data") or b"", "application/octet-stream")
    if sub == "/children":
        if m == "POST":
            b = body(h)
            try:
                name, old = free_name(d, it["id"], b["name"], b.get("@microsoft.graph.conflictBehavior", "fail"))
            except FileExistsError:
                return err(h, 409, "nameAlreadyExists", "The specified item name already exists.")
            if old:
                return h.send(201, item_json(h, d, old))
            return h.send(201, item_json(h, d, new_item(d, it["id"], name, folder=True)))
        return h.send(200, {"value": [item_json(h, d, i) for i in children(d, it["id"])]})
    if sub == "" and m == "PATCH":
        b = body(h)
        pid = (b.get("parentReference") or {}).get("id") or it["parent"]
        name = b.get("name") or it["name"]
        try:
            name, old = free_name(d, pid, name, behaviour or "fail") if (pid != it["parent"] or name.lower() != it["name"].lower()) else (name, None)
        except FileExistsError:
            return err(h, 409, "nameAlreadyExists", "The specified item name already exists.")
        if old:
            old["deleted"] = True
        it["parent"], it["name"], it["modified"] = pid, name, C_now()
        return h.send(200, item_json(h, d, it))
    if sub == "" and m == "DELETE" or sub == "/permanentDelete":
        it["deleted"] = True
        return h.send(204)
    if sub == "/copy":
        b = body(h)
        pid = (b.get("parentReference") or {}).get("id") or it["parent"]
        try:
            name, old = free_name(d, pid, b.get("name") or it["name"], behaviour or "fail")
        except FileExistsError:
            return err(h, 409, "nameAlreadyExists", "The specified item name already exists.")
        if old:
            old["deleted"] = True
        cp = new_item(d, pid, name, it.get("data"), it["folder"])
        mon = "http://" + h.headers.get("Host", "127.0.0.1") + "/msgraph/monitor/" + cp["id"]
        return h.send(202, None, headers={"Location": mon})
    ms = re.fullmatch(r"/search\(q='(.*)'\)", sub)
    if ms:
        text = ms.group(1).replace("''", "'").lower()
        out = []

        def walk(pid):
            for c in children(d, pid):
                if text in c["name"].lower() or text in (c.get("data") or b"").decode("latin-1").lower():
                    out.append(item_json(h, d, c))
                if c["folder"]:
                    walk(c["id"])
        walk(it["id"])
        return page(h, out, q)
    if sub == "/permissions":
        return h.send(200, {"value": it["perms"]})
    if sub == "/invite":
        b = body(h)
        made = []
        for r in b.get("recipients", []):
            pm = {"id": nid("perm-"), "roles": b.get("roles", ["read"]), "grantedToV2": {"user": {"email": r["email"]}}, "invitation": {"email": r["email"]}}
            it["perms"].append(pm)
            made.append(pm)
        return h.send(200, {"value": made})
    if sub == "/createLink":
        b = body(h)
        pm = {"id": nid("link-"), "roles": ["write" if b.get("type") == "edit" else "read"],
              "link": {"type": b.get("type"), "scope": b.get("scope"), "webUrl": "https://ut.sharepoint.test/:x:/s/" + nid()}}
        it["perms"].append(pm)
        return h.send(201, pm)
    if sub == "/versions":
        return h.send(200, {"value": [{"id": v["id"], "size": len(v["data"] or b"")} for v in it["versions"]]})
    mv = re.fullmatch(r"/versions/([^/]+)/restoreVersion", sub)
    if mv:
        v = next((x for x in it["versions"] if x["id"] == mv.group(1)), None)
        if v is None:
            return err(h, 404, "itemNotFound", "No such version")
        it["data"] = v["data"]
        it["versions"].insert(0, {"id": "%d.0" % (len(it["versions"]) + 1), "data": v["data"]})
        return h.send(204)
    if sub.startswith("/workbook"):
        return excel(h, m, workbook(it), sub[len("/workbook"):], q)
    raise KeyError(p)


def sheet_of(wb, name):
    for s in wb["sheets"]:
        if s["name"].lower() == name.lower() or s["id"] == name:
            return s
    raise KeyError("worksheet " + name)


def range_json(sh, r0, c0, r1, c1):
    g = sh["grid"]
    return {"address": sh["name"] + "!" + C.addr(r0, c0, r1, c1), "values": g.matrix(r0, c0, r1, c1, "v"), "text": g.matrix(r0, c0, r1, c1, "t"),
            "formulas": g.matrix(r0, c0, r1, c1, "f"), "numberFormat": g.matrix(r0, c0, r1, c1, "n"), "rowCount": r1 - r0 + 1, "columnCount": c1 - c0 + 1}


def table_of(wb, ref):
    for t in wb["tables"]:
        if t["name"].lower() == ref.lower() or t["id"] == ref:
            return t
    raise KeyError("table " + ref)


def table_json(t):
    return {"id": t["id"], "name": t["name"], "style": t["style"], "showHeaders": t["showHeaders"]}


DEFAULT_FONT = {"name": "Calibri", "size": 11, "color": "#000000", "bold": False, "italic": False, "underline": "None", "strikethrough": False}
DEFAULT_FMT = {"horizontalAlignment": "General", "verticalAlignment": "Bottom", "wrapText": False, "columnWidth": 48, "rowHeight": 15}


def excel(h, m, wb, rest, q):
    if rest in ("/createSession", "/closeSession"):
        return h.send(201, {"id": nid("session-"), "persistChanges": True})
    if rest == "/worksheets":
        return h.send(200, {"value": [{"id": s["id"], "name": s["name"], "position": i, "visibility": s["visibility"]} for i, s in enumerate(wb["sheets"])]})
    if rest == "/worksheets/add":
        name = body(h).get("name") or "Sheet%d" % (len(wb["sheets"]) + 1)
        if any(s["name"].lower() == name.lower() for s in wb["sheets"]):
            return err(h, 409, "ItemAlreadyExists", "A worksheet with that name already exists.")
        s = {"id": "{%s}" % nid(), "name": name, "visibility": "Visible", "grid": C.Grid()}
        wb["sheets"].append(s)
        return h.send(201, {"id": s["id"], "name": s["name"], "position": len(wb["sheets"]) - 1, "visibility": "Visible"})
    if rest == "/tables":
        return h.send(200, {"value": [table_json(t) for t in wb["tables"]]})
    mt = re.fullmatch(r"/tables/([^/]+)(/.*)?", rest)
    if mt:
        t = table_of(wb, mt.group(1))
        sub = mt.group(2) or ""
        sh = sheet_of(wb, t["sheet"])
        g = sh["grid"]
        r0, c0, r1, c1 = t["box"]
        if sub == "" and m == "PATCH":
            t.update({k: v for k, v in body(h).items() if k in ("name", "style", "showHeaders")})
            return h.send(200, table_json(t))
        if sub == "" and m == "DELETE":
            g.clear(r0, c0, r1, c1)
            wb["tables"].remove(t)
            return h.send(204)
        if sub == "/headerRowRange":
            return h.send(200, range_json(sh, r0, c0, r0, c1))
        if sub == "/rows":
            return h.send(200, {"value": [{"index": i, "values": [row]} for i, row in enumerate(g.matrix(r0 + 1, c0, r1, c1))] if r1 > r0 else []})
        mi = re.fullmatch(r"/rows/itemAt\(index=(\d+)\)", sub)
        if mi:
            r = r0 + 1 + int(mi.group(1))
            if r > r1:
                return err(h, 404, "ItemNotFound", "The row does not exist.")
            return h.send(200, {"index": int(mi.group(1)), "values": g.matrix(r, c0, r, c1)})
        if sub == "/rows/add":
            vals = body(h).get("values", [])
            g.shift(r1 + 1, c0, r1 + len(vals), c1, "Down")
            for i, row in enumerate(vals):
                for j, v in enumerate(row):
                    g.set(r1 + 1 + i, c0 + j, v)
            t["box"] = (r0, c0, r1 + len(vals), c1)
            return h.send(201, {"index": r1 - r0, "values": vals})
        raise KeyError(rest)
    mw = re.fullmatch(r"/worksheets\('(.+?)'\)(/.*)?", rest)
    if not mw:
        raise KeyError(rest)
    sh = sheet_of(wb, mw.group(1))
    g = sh["grid"]
    sub = mw.group(2) or ""
    if sub == "":
        if m == "PATCH":
            b = body(h)
            if "name" in b:
                sh["name"] = b["name"]
                for t in wb["tables"]:
                    if t["sheet"].lower() == mw.group(1).lower():
                        t["sheet"] = b["name"]
            if "visibility" in b:
                sh["visibility"] = b["visibility"]
            return h.send(200, {"id": sh["id"], "name": sh["name"], "visibility": sh["visibility"]})
        if m == "DELETE":
            wb["sheets"].remove(sh)
            return h.send(204)
        return h.send(200, {"id": sh["id"], "name": sh["name"], "visibility": sh["visibility"]})
    if sub == "/tables":
        return h.send(200, {"value": [table_json(t) for t in wb["tables"] if t["sheet"].lower() == sh["name"].lower()]})
    if sub == "/tables/add":
        b = body(h)
        r0, c0, r1, c1 = C.parse_range(b["address"].split("!")[-1])
        t = {"id": "{T-%s}" % nid(), "name": "Table%d" % wb["next_table"], "sheet": sh["name"], "box": (r0, c0, r1, c1), "hasHeaders": b.get("hasHeaders", True),
             "style": "TableStyleMedium2", "showHeaders": True}
        wb["next_table"] += 1
        if not t["hasHeaders"]:
            g.shift(r0, c0, r0, c1, "Down")
            for c in range(c0, c1 + 1):
                g.set(r0, c, "Column%d" % (c - c0 + 1))
            t["box"] = (r0, c0, r1 + 1, c1)
        wb["tables"].append(t)
        return h.send(201, table_json(t))
    mu = re.fullmatch(r"(?:/range\(address='([^']+)'\))?/usedRange(?:\(valuesOnly=(?:true|false)\))?", sub)
    if mu:
        u = g.used()
        if mu.group(1) and u:
            a = C.parse_range(mu.group(1))
            keys = [k for k in g.cells if (a[0] is None or a[0] <= k[0] <= a[2]) and (a[1] is None or a[1] <= k[1] <= a[3]) and g.value(*k) not in ("", None)]
            u = (min(k[0] for k in keys), min(k[1] for k in keys), max(k[0] for k in keys), max(k[1] for k in keys)) if keys else None
        if not u:
            return h.send(200, {"address": sh["name"] + "!A1", "values": [[""]], "text": [[""]], "formulas": [[""]], "numberFormat": [["General"]], "rowCount": 0, "columnCount": 0})
        return h.send(200, range_json(sh, *u))
    mr = re.fullmatch(r"/range\(address='([^']+)'\)(/.*)?", sub)
    if not mr:
        raise KeyError(rest)
    r0, c0, r1, c1 = g.bound(*C.parse_range(mr.group(1)))
    op = mr.group(2) or ""
    if op == "":
        if m == "PATCH":
            b = body(h)
            for key in ("values", "formulas"):
                for i, row in enumerate(b.get(key) or []):
                    for j, v in enumerate(row):
                        g.set(r0 + i, c0 + j, v)
            for i, row in enumerate(b.get("numberFormat") or []):
                for j, v in enumerate(row):
                    g.cell(r0 + i, c0 + j, True)["nf"] = v
        return h.send(200, range_json(sh, r0, c0, r1, c1))
    if op == "/clear":
        g.clear(r0, c0, r1, c1, body(h).get("applyTo", "All"))
        return h.send(204)
    if op in ("/insert", "/delete"):
        g.shift(r0, c0, r1, c1, body(h).get("shift", "Down" if op == "/insert" else "Up"))
        return h.send(200 if op == "/insert" else 204, range_json(sh, r0, c0, r1, c1) if op == "/insert" else None)
    if op in ("/merge", "/unmerge"):
        if op == "/merge":
            g.merged.append((r0, c0, r1, c1))
        return h.send(204)
    if op in ("/format/autofitColumns", "/format/autofitRows"):
        return h.send(204)
    mf = re.fullmatch(r"/format(?:/(font|fill|borders/\w+))?", op)
    if mf:
        part = mf.group(1) or "format"
        part = "borders" if part.startswith("borders") else part
        if m == "PATCH":
            b = body(h)
            for r in range(r0, r1 + 1):
                for c in range(c0, c1 + 1):
                    g.cell(r, c, True).setdefault("fmt", {}).setdefault(part, {}).update(b)
            return h.send(200, b)
        fmt = g.cell(r0, c0).get("fmt", {})
        if part == "font":
            return h.send(200, dict(DEFAULT_FONT, **fmt.get("font", {})))
        if part == "fill":
            return h.send(200, {"color": fmt.get("fill", {}).get("color", "#FFFFFF")})
        return h.send(200, dict(DEFAULT_FMT, **fmt.get("format", {})))
    raise KeyError(rest)


def batch(h, q):
    out = []
    for r in body(h).get("requests", []):
        fake = _Fake(h, r.get("method", "GET"), r.get("body"))
        try:
            graph(fake, r.get("method", "GET"), PREFIX + r["url"].split("?")[0], {})
        except KeyError as e:
            fake.status, fake.payload = 404, {"error": {"message": str(e)}}
        out.append({"id": r.get("id"), "status": fake.status, "body": fake.payload})
    return h.send(200, {"responses": out})


class _Fake:
    def __init__(self, h, method, b):
        self.headers = h.headers
        self._b = b
        self.status, self.payload = 200, None

    def body(self):
        return self._b or {}

    def raw(self):
        return json.dumps(self._b or {}).encode()

    def send(self, status, payload=None, ctype=None, headers=None):
        self.status, self.payload = status, payload


# ---------------------------------------------------------------- Teams, chats and Planner

def teams(h, m, p, q):
    if re.fullmatch(r"/(me|users/[^/]+)/joinedTeams", p):
        return h.send(200, {"value": [clean(t) for t in S["teams"].values()]})
    mt = re.fullmatch(r"/teams/([^/]+)(?:/channels(?:/([^/]+))?)?(/messages(?:/([^/]+)/replies)?)?", p)
    if mt:
        t = S["teams"].get(mt.group(1))
        if not t:
            return err(h, 404, "NotFound", "Team not found")
        if "/channels" not in p:
            return h.send(200, clean(t))
        if mt.group(2) is None:
            if m == "POST":
                b = body(h)
                cid = "19:%s@thread.tacv2" % nid()
                t["channels"][cid] = dict(b, id=cid, email="", createdDateTime=C_now(), webUrl="https://teams.test/c/" + cid, messages=[])
                return h.send(201, clean(t["channels"][cid]))
            return h.send(200, {"value": [clean(c) for c in t["channels"].values()]})
        ch = t["channels"].get(mt.group(2))
        if not ch:
            return err(h, 404, "NotFound", "Channel not found")
        if not mt.group(3):
            return h.send(200, clean(ch))
        if m == "POST":
            b = body(h)
            msg = dict(b, id=str(int(time.time() * 1000)) + nid()[:3], createdDateTime=C_now(), **{"from": {"user": {"displayName": ME["displayName"]}}})
            if mt.group(4):
                parent = next((x for x in ch["messages"] if x["id"] == mt.group(4)), None)
                if parent is None:
                    return err(h, 404, "NotFound", "Message not found")
                msg["replyToId"] = parent["id"]
                parent["replies"].append(msg)
            else:
                msg["replies"] = []
                ch["messages"].append(msg)
            return h.send(201, clean(msg))
        msgs = sorted(ch["messages"], key=lambda x: x["createdDateTime"], reverse=True)
        return page(h, [clean(x) for x in msgs], q)
    if p == "/me/chats":
        return h.send(200, {"value": [clean(c) for c in S["chats"].values()]})
    if p == "/chats" and m == "POST":
        b = body(h)
        ids = [re.search(r"users\('([^']+)'\)", x["user@odata.bind"]).group(1) for x in b.get("members", [])]
        ids = [(user_by(i) or {"id": i})["id"] for i in ids]
        for c in S["chats"].values():
            if sorted(c["members"]) == sorted(ids):
                return h.send(200, clean(c))
        cid = "19:%s@unq.gbl.spaces" % nid()
        S["chats"][cid] = {"id": cid, "topic": None, "chatType": "oneOnOne", "lastUpdatedDateTime": C_now(), "members": ids, "messages": []}
        return h.send(201, clean(S["chats"][cid]))
    mc = re.fullmatch(r"/chats/([^/]+)/messages", p)
    if mc:
        c = S["chats"].get(mc.group(1))
        if not c:
            return err(h, 404, "NotFound", "Chat not found")
        if m == "POST":
            msg = dict(body(h), id=nid("cm"), messageType="message", createdDateTime=C_now(), attachments=[], **{"from": {"user": {"displayName": ME["displayName"]}}})
            c["messages"].insert(0, msg)
            return h.send(201, msg)
        return h.send(200, {"value": sorted(c["messages"], key=lambda x: x["createdDateTime"], reverse=True)})
    mp = re.fullmatch(r"/groups/([^/]+)/planner/plans", p)
    if mp:
        return h.send(200, {"value": [x for x in S["plans"].values() if x["group"] == mp.group(1)]})
    mp = re.fullmatch(r"/planner/plans/([^/]+)/tasks", p)
    if mp:
        return h.send(200, {"value": [clean(t) for t in S["tasks"].values() if t["planId"] == mp.group(1)]})
    if p == "/planner/tasks" and m == "POST":
        b = body(h)
        if b.get("planId") not in S["plans"]:
            return err(h, 404, "NotFound", "Plan not found")
        t = dict({"bucketId": None, "percentComplete": 0, "startDateTime": None, "completedDateTime": None, "assignments": {}, "priority": 5, "dueDateTime": None}, **b)
        t.update(id=nid("task-"), createdDateTime=C_now(), etag='W/"1"')
        S["tasks"][t["id"]] = t
        return h.send(201, clean(t))
    mt = re.fullmatch(r"/planner/tasks/([^/]+)", p)
    if mt:
        t = S["tasks"].get(mt.group(1))
        if not t:
            return err(h, 404, "NotFound", "Task not found")
        if m == "PATCH":
            if h.headers.get("If-Match") != t["etag"]:
                return err(h, 412, "PreconditionFailed", "The ETag does not match")
            t.update(body(h))
            t["etag"] = 'W/"%d"' % (int(re.search(r"\d+", t["etag"]).group(0)) + 1)
            return h.send(204)
        return h.send(200, dict(clean(t), **{"@odata.etag": t["etag"]}), headers={"ETag": t["etag"]})
    raise KeyError(p)


# ---------------------------------------------------------------- SharePoint lists (Graph) and the site REST API

def site_key(s):
    return s.rstrip(":").split("/")[-1].lower()


def list_of(site, ref):
    lists = S["sites"].get(site, {}).get("lists", {})
    for l in lists.values():
        if ref.lower() in (l["id"].lower(), l["displayName"].lower(), l["name"].lower()):
            return l
    raise KeyError("list " + ref)


def sp_graph(h, m, p, q):
    ms = re.fullmatch(r"/sites/(.+?)/lists(?:/([^/]+)(/.*)?)?", p)
    if not ms:
        raise KeyError(p)
    site = site_key(ms.group(1))
    if ms.group(2) is None:
        if m == "POST":
            b = body(h)
            l = new_list(site, b["displayName"], (b.get("list") or {}).get("template", "genericList"), b.get("description", ""))
            return h.send(201, {"id": l["id"], "displayName": l["displayName"], "webUrl": l["webUrl"]})
        return h.send(200, {"value": [{"id": l["id"], "name": l["name"], "displayName": l["displayName"], "list": {"template": l["template"]}, "webUrl": l["webUrl"]}
                                      for l in S["sites"].get(site, {}).get("lists", {}).values()]})
    l = list_of(site, ms.group(2))
    sub = ms.group(3) or ""
    if sub == "":
        if m == "PATCH":
            l.update({k: v for k, v in body(h).items() if k in ("displayName", "description")})
            return h.send(200, {"id": l["id"]})
        if m == "DELETE":
            del S["sites"][site]["lists"][l["id"]]
            return h.send(204)
        return h.send(200, {"id": l["id"], "displayName": l["displayName"], "description": l["description"]})
    if sub == "/columns":
        if m == "POST":
            b = body(h)
            col = dict(b, id=nid("col-"))
            l["columns"].append(col)
            return h.send(201, col)
        return h.send(200, {"value": l["columns"]})
    if sub == "/items":
        if m == "POST":
            it = add_item(l, body(h).get("fields", {}))
            return h.send(201, {"id": it["id"], "fields": it["fields"]})
        flt = q.get("$filter")
        pick = re.search(r"fields\(\$select=([^)]*)\)", q.get("$expand", ""))
        out = []
        for it in l["items"]:
            if not C.odata(flt, {"fields": it["fields"], "id": it["id"]}):
                continue
            f = dict(it["fields"]) if not pick else {k: it["fields"].get(k) for k in pick.group(1).split(",")}
            out.append({"id": it["id"], "fields": dict(f, **{"@odata.etag": '"1"'})})
        return page(h, out, q)
    mi = re.fullmatch(r"/items/([^/]+)(/fields)?", sub)
    if mi:
        it = next((x for x in l["items"] if x["id"] == mi.group(1)), None)
        if it is None:
            return err(h, 404, "itemNotFound", "Item not found")
        if m == "DELETE":
            l["items"].remove(it)
            return h.send(204)
        if m == "PATCH":
            it["fields"].update(body(h))
            return h.send(200, it["fields"])
        return h.send(200, {"id": it["id"], "fields": it["fields"]})
    raise KeyError(p)


def sp_rest(h, m, path, q):
    ms = re.match(r"^/msgsp/sites/([^/]+)(/_api/.*)$", path)
    if not ms:
        raise KeyError(path)
    site, rest = ms.group(1).lower(), ms.group(2)
    if site not in S["sites"]:
        return h.send(404, {"error": {"message": "No such site"}})
    if rest == "/_api/web":
        return h.send(200, {"Title": S["sites"][site]["title"]})
    if rest == "/_api/contextinfo":
        return h.send(200, {"FormDigestValue": "digest-" + nid()})
    if rest == "/_api/web/lists":
        if m == "POST":
            b = body(h)
            l = new_list(site, b["Title"], "genericList", b.get("Description", ""))
            return h.send(201, {"Id": l["id"], "Title": l["displayName"]})
        return h.send(200, {"value": [{"Id": l["id"], "Title": l["displayName"], "BaseTemplate": 100, "RootFolder": {"Name": l["name"], "ServerRelativeUrl": "/sites/" + site + "/Lists/" + l["name"]}}
                                      for l in S["sites"][site]["lists"].values()]})
    gf = re.fullmatch(r"/_api/web/GetFileByServerRelativeUrl\('(.+)'\)/\$value", rest)
    if gf:
        m2 = re.search(r"/Attachments/(\d+)/(.+)$", gf.group(1))
        for l in S["sites"][site]["lists"].values():
            for it in l["items"]:
                for a in it["attachments"]:
                    if m2 and it["id"] == m2.group(1) and a["FileName"] == m2.group(2):
                        return C.sendb(h, 200, a["data"], "application/octet-stream")
        return h.send(404, {"error": {"message": "File not found"}})
    ml = re.fullmatch(r"/_api/web/lists(?:/getbytitle\('(.+?)'\)|\(guid'(.+?)'\))(/.*)?", rest)
    if not ml:
        raise KeyError(rest)
    l = list_of(site, ml.group(1) or ml.group(2))
    sub = ml.group(3) or ""
    if sub == "":
        if m == "POST":
            b = body(h)
            if "Title" in b:
                l["displayName"] = b["Title"]
            if "Description" in b:
                l["description"] = b["Description"]
            return h.send(204)
        return h.send(200, {"Id": l["id"], "Title": l["displayName"], "ListItemEntityTypeFullName": "SP.Data." + l["name"] + "ListItem"})
    if sub == "/recycle()":
        del S["sites"][site]["lists"][l["id"]]
        return h.send(200, {"value": nid()})
    if sub == "/items":
        if m == "POST":
            b = {k: v for k, v in body(h).items() if k != "__metadata"}
            it = add_item(l, b)
            return h.send(201, dict(it["fields"], Id=int(it["id"])))
        flt = (q.get("$filter") or "")
        out = [dict(it["fields"], Id=int(it["id"])) for it in l["items"] if C.odata(flt, it["fields"])]
        return h.send(200, {"value": out})
    mi = re.fullmatch(r"/items\((\d+)\)(/.*)?", sub)
    if not mi:
        raise KeyError(rest)
    it = next((x for x in l["items"] if x["id"] == mi.group(1)), None)
    if it is None:
        return h.send(404, {"error": {"message": "Item not found"}})
    s2 = mi.group(2) or ""
    if s2 == "":
        it["fields"].update({k: v for k, v in body(h).items() if k != "__metadata"})
        return h.send(204)
    if s2 == "/recycle()":
        l["items"].remove(it)
        return h.send(200, {"value": nid()})
    if s2 == "/AttachmentFiles":
        return h.send(200, {"value": [{"FileName": a["FileName"], "ServerRelativeUrl": "/sites/%s/Lists/%s/Attachments/%s/%s" % (site, l["name"], it["id"], a["FileName"])}
                                      for a in it["attachments"]]})
    ma = re.fullmatch(r"/AttachmentFiles/add\(FileName='(.+)'\)", s2)
    if ma:
        it["attachments"].append({"FileName": ma.group(1).replace("''", "'"), "data": h.raw()})
        return h.send(200, {"FileName": ma.group(1)})
    md = re.fullmatch(r"/AttachmentFiles\('(.+)'\)", s2)
    if md:
        before = len(it["attachments"])
        it["attachments"] = [a for a in it["attachments"] if a["FileName"] != md.group(1).replace("''", "'")]
        return h.send(200 if len(it["attachments"]) < before else 404, None if len(it["attachments"]) < before else {"error": {"message": "No such attachment"}})
    raise KeyError(rest)


# ---------------------------------------------------------------- Entra ID users and groups

def obj_by(ref):
    u = user_by(ref)
    if u:
        return dict(u, **{"@odata.type": "#microsoft.graph.user"})
    g = next((g for g in S["groups"] if g["id"] == ref), None)
    return dict(g, **{"@odata.type": "#microsoft.graph.group"}) if g else None


def member_of(oid):
    return [g for g in S["groups"] if oid in S["members"].get(g["id"], [])]


def transitive_member_of(oid, seen=None):
    seen = seen or []
    for g in member_of(oid):
        if g not in seen:
            seen.append(g)
            transitive_member_of(g["id"], seen)
    return seen


def members(gid, nested):
    out = []
    for mid in S["members"].get(gid, []):
        o = obj_by(mid)
        if o and o not in out:
            out.append(o)
            if nested and o["@odata.type"].endswith("group"):
                out += [x for x in members(mid, True) if x not in out]
    return out


def directory(h, m, p, q):
    if p == "/users":
        if m == "POST":
            b = body(h)
            if user_by(b.get("userPrincipalName", "")):
                return err(h, 400, "Request_BadRequest", "Another object with the same value for property userPrincipalName already exists.")
            u = dict({k: v for k, v in b.items() if k != "passwordProfile"}, id=str(uuid.uuid4()), mail=b.get("userPrincipalName"))
            S["users"].append(u)
            return h.send(201, u)
        return page(h, [u for u in S["users"] if C.odata(q.get("$filter"), u)], q)
    mu = re.fullmatch(r"/users/([^/]+)(/manager|/memberOf|/transitiveMemberOf|/revokeSignInSessions)?", p)
    if mu:
        u = user_by(mu.group(1))
        if u is None:
            return err(h, 404, "Request_ResourceNotFound", "Resource '%s' does not exist." % mu.group(1))
        sub = mu.group(2)
        if sub == "/manager":
            mg = S["manager"].get(u["id"])
            return h.send(200, user_by(mg)) if mg else err(h, 404, "Request_ResourceNotFound", "No manager")
        if sub in ("/memberOf", "/transitiveMemberOf"):
            gs = member_of(u["id"]) if sub == "/memberOf" else transitive_member_of(u["id"])
            return h.send(200, {"value": [dict(g, **{"@odata.type": "#microsoft.graph.group"}) for g in gs]})
        if sub == "/revokeSignInSessions":
            u["signInSessionsValidFromDateTime"] = C_now()
            return h.send(200, {"value": True})
        if m == "PATCH":
            u.update(body(h))
            return h.send(204)
        if m == "DELETE":
            S["users"].remove(u)
            return h.send(204)
        return h.send(200, u)
    if p == "/groups":
        return page(h, [g for g in S["groups"] if C.odata(q.get("$filter"), g)], q)
    mg = re.fullmatch(r"/groups/([^/]+)/(members|transitiveMembers)(?:/(?!\$ref)([^/]+))?(/\$ref)?", p)
    if mg:
        gid = mg.group(1)
        lst = S["members"].setdefault(gid, [])
        if mg.group(4) and m == "POST":
            oid = str(body(h).get("@odata.id", "")).rstrip("/").split("/")[-1]
            if oid in lst:
                return err(h, 400, "Request_BadRequest", "One or more added object references already exist for the following modified properties: 'members'.")
            lst.append(oid)
            return h.send(204)
        if mg.group(4) and m == "DELETE":
            if mg.group(3) not in lst:
                return err(h, 404, "Request_ResourceNotFound", "Not a member")
            lst.remove(mg.group(3))
            return h.send(204)
        return h.send(200, {"value": members(gid, mg.group(2) == "transitiveMembers")})
    raise KeyError(p)


# ---------------------------------------------------------------- entry points

def graph(h, m, path, q):
    if not S:
        seed()
    p = path[len(PREFIX):]
    with core.LOCK:   # noqa: F821 - core is set by standin.py
        if re.match(r"^/(me/drive|users/[^/]+/drive|drives/[^/]+|sites/.+?/drive)/", p):
            return drive(h, m, p, q)
        if p.startswith("/sites/"):
            return sp_graph(h, m, p, q)
        if p == "/$batch":
            return batch(h, q)
        mb = re.fullmatch(r"/(me|users/[^/]+)((?:/(?:sendMail|mailFolders|messages|calendars|events|calendarView|findMeetingTimes)).*)?", p)
        if mb and mb.group(2):
            box = mb.group(1).split("/")[-1]
            if not is_me(box):
                return err(h, 403, "ErrorAccessDenied", "Only the test mailbox " + ME["mail"] + " exists in the stand-in")
            rest = mb.group(2)
            if re.match(r"/(sendMail|mailFolders|messages)", rest):
                return mail(h, m, rest, q)
            return calendar(h, m, rest, q)
        if p == "/me":
            return h.send(200, ME)
        if re.match(r"^/(me|users/[^/]+)/joinedTeams$", p) or p.startswith(("/teams/", "/chats", "/me/chats", "/planner/")) or re.match(r"^/groups/[^/]+/planner", p):
            return teams(h, m, p, q)
        return directory(h, m, p, q)


def monitor(h, m, path, q):
    return h.send(200, {"status": "completed", "resourceId": path.rsplit("/", 1)[-1], "percentageComplete": 100})


def reset(h, m, path, q):
    with core.LOCK:   # noqa: F821
        seed()
    return h.send(200, {"reset": "msgraph"})


ROUTES = [("/msgraph/reset", reset, None), ("/msgraph/monitor/", monitor, None), (PREFIX + "/", graph, "msgraph"), ("/msgsp/", lambda h, m, p, q: _locked(sp_rest, h, m, p, q), "msgsp")]


def _locked(fn, h, m, p, q):
    if not S:
        seed()
    with core.LOCK:   # noqa: F821
        return fn(h, m, p, q)


SEED = {}
