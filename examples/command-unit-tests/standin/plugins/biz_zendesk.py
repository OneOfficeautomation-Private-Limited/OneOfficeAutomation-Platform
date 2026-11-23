"""Zendesk Support (API v2) for the command unit tests. The toolkit takes only a site address (no path), so this
plugin answers /api/v2/... at the root of the stand-in: tickets, comments, tags, users, organizations, groups,
uploads and attachments, views and ticket fields. Made-up records; state key biz_zendesk."""
import copy
import re

SEED = {"biz_zendesk": {
    "users": [
        {"id": 9001, "name": "Sam Agent", "email": "sam.agent@ut.example", "role": "agent", "organization_id": None, "phone": None, "external_id": None,
         "time_zone": "UTC", "suspended": False, "created_at": "2026-01-05T09:00:00Z", "user_fields": {}},
        {"id": 9002, "name": "Pat Customer", "email": "pat.customer@ut.example", "role": "end-user", "organization_id": 7001, "phone": None, "external_id": "CRM-42",
         "time_zone": "UTC", "suspended": False, "created_at": "2026-02-01T09:00:00Z", "user_fields": {"customer_tier": "silver"}},
    ],
    "organizations": [
        {"id": 7001, "name": "Northwind Unit Test", "domain_names": ["northwind.ut.example"], "details": "", "notes": "", "external_id": "ACC-1",
         "group_id": None, "tags": [], "created_at": "2026-01-01T00:00:00Z", "organization_fields": {}},
    ],
    "groups": [{"id": 6001, "name": "Billing", "description": "Money questions", "default": False},
               {"id": 6002, "name": "Support", "description": "Everything else", "default": True}],
    "ticket_fields": [{"id": 5001, "title": "Order number", "type": "text", "active": True, "required": False},
                      {"id": 5002, "title": "Reason", "type": "tagger", "active": True, "required": False},
                      {"id": 5003, "title": "Subject", "type": "subject", "active": True, "required": True}],
    "views": [{"id": 8001, "title": "Unassigned urgent tickets", "position": 1, "updated_at": "2026-11-01T00:00:00Z", "active": True}],
    "tickets": [
        {"id": 1001, "subject": "Refund not received", "description": "Where is my refund?", "status": "open", "priority": "urgent", "type": "problem",
         "requester_id": 9002, "assignee_id": None, "group_id": 6001, "organization_id": 7001, "tags": ["refund"], "custom_fields": [{"id": 5001, "value": "SO-88123"}],
         "created_at": "2026-11-20T08:00:00Z", "updated_at": "2026-11-20T08:00:00Z",
         "_comments": [{"id": 1, "author_id": 9002, "body": "Where is my refund?", "plain_body": "Where is my refund?", "public": True, "created_at": "2026-11-20T08:00:00Z", "attachments": []}]},
        {"id": 1002, "subject": "Password reset", "description": "Cannot sign in", "status": "new", "priority": "normal", "type": "question",
         "requester_id": 9001, "assignee_id": None, "group_id": 6002, "organization_id": None, "tags": [], "custom_fields": [],
         "created_at": "2026-11-21T08:00:00Z", "updated_at": "2026-11-21T08:00:00Z",
         "_comments": [{"id": 1, "author_id": 9001, "body": "Cannot sign in", "plain_body": "Cannot sign in", "public": True, "created_at": "2026-11-21T08:00:00Z", "attachments": []}]},
    ],
    "uploads": {},
    "attachments": {},
    "next_id": 20000,
}}


def _st():
    return core.STATE.setdefault("biz_zendesk", copy.deepcopy(SEED["biz_zendesk"]))


def _nid(st):
    st["next_id"] += 1
    return st["next_id"]


def _authorized(h):
    a = h.headers.get("Authorization", "")
    if not (a.startswith("Bearer ") or a.startswith("Basic ")) or len(a) < 10:
        h.send(401, {"error": "Couldn't authenticate you"})
        return False
    return True


def _bytes(h, data, ctype):
    h.send_response(200)
    h.send_header("Content-Type", ctype)
    h.send_header("Content-Length", str(len(data)))
    h.end_headers()
    h.wfile.write(data)


def _pub(t):
    return {k: v for k, v in t.items() if not k.startswith("_")}


def _page(h, key, items):
    """Cursor page answer (the toolkit asks for page[size]; everything fits on one page here)."""
    return h.send(200, {key: items, "meta": {"has_more": False}, "links": {"next": None}})


def _search_tickets(st, query):
    found = []
    terms = query.split()
    for t in st["tickets"]:
        ok = True
        for term in terms:
            m = re.match(r"(\w+)([:<>])(.+)$", term)
            if not m:
                ok = ok and term.lower() in (t["subject"] + " " + t["description"]).lower()
                continue
            k, op, v = m.groups()
            if k == "type" and v == "ticket":
                continue
            if k == "requester":
                u = next((u for u in st["users"] if str(u.get("email") or "").lower() == v.lower()), None)
                ok = ok and u is not None and t["requester_id"] == u["id"]
            elif k == "tags":
                ok = ok and v in t["tags"]
            elif k == "status" and op == "<":
                order = ["new", "open", "pending", "hold", "solved", "closed"]
                ok = ok and order.index(t["status"]) < order.index(v)
            elif k in ("status", "priority", "type"):
                ok = ok and str(t.get(k)) == v
        if ok:
            found.append(_pub(t))
    return found


def _search_users(st, query):
    out = []
    for u in st["users"]:
        ok = True
        for term in re.findall(r'"[^"]*"|\S+', query):
            if term.startswith('"'):
                ok = ok and term.strip('"').lower() == u["name"].lower()
            elif term.startswith("email:"):
                ok = ok and term[6:].lower() == str(u.get("email") or "").lower()
            elif term.startswith("role:"):
                ok = ok and term[5:] == u["role"]
            elif term.startswith("organization:"):
                ok = ok and str(u.get("organization_id")) == term[13:]
            else:
                ok = ok and (term.lower() in u["name"].lower() or term.lower() in str(u.get("email") or "").lower())
        if ok:
            out.append(u)
    return out


def zendesk(h, m, path, q):
    if not _authorized(h):
        return
    st = _st()
    p = path[len("/api/v2"):]
    with core.LOCK:
        # ---- tickets
        if p == "/tickets" and m == "POST":
            t = h.body().get("ticket", {})
            tid = _nid(st)
            req_id = 9001
            if t.get("requester"):
                u = next((u for u in st["users"] if str(u.get("email") or "").lower() == t["requester"]["email"].lower()), None)
                if u is None:
                    u = {"id": _nid(st), "name": t["requester"].get("name"), "email": t["requester"]["email"], "role": "end-user", "organization_id": None,
                         "phone": None, "external_id": None, "time_zone": "UTC", "suspended": False, "created_at": core.now_iso(), "user_fields": {}}
                    st["users"].append(u)
                req_id = u["id"]
            c = t.get("comment", {})
            rec = {"id": tid, "subject": t.get("subject"), "description": c.get("body"), "status": t.get("status", "new"), "priority": t.get("priority"),
                   "type": t.get("type"), "requester_id": req_id, "assignee_id": t.get("assignee_id"), "group_id": t.get("group_id"), "organization_id": None,
                   "tags": t.get("tags", []), "custom_fields": t.get("custom_fields", []), "created_at": core.now_iso(), "updated_at": core.now_iso(),
                   "_comments": [{"id": 1, "author_id": req_id, "body": c.get("body"), "plain_body": c.get("body"), "public": c.get("public", True),
                                  "created_at": core.now_iso(), "attachments": [st["uploads"].get(x, {}).get("attachment") for x in c.get("uploads", []) if x in st["uploads"]]}]}
            st["tickets"].append(rec)
            return h.send(201, {"ticket": _pub(rec)})
        if p == "/tickets" and m == "GET":
            return _page(h, "tickets", [_pub(t) for t in st["tickets"]])
        mt = re.match(r"/tickets/(\d+)(/tags|/comments)?$", p)
        if mt:
            t = next((x for x in st["tickets"] if x["id"] == int(mt.group(1))), None)
            if t is None:
                return h.send(404, {"error": "RecordNotFound", "description": "Not found"})
            if mt.group(2) == "/tags" and m == "PUT":
                for tag in h.body().get("tags", []):
                    if tag not in t["tags"]:
                        t["tags"].append(tag)
                return h.send(200, {"tags": t["tags"]})
            if mt.group(2) == "/comments":
                return _page(h, "comments", t["_comments"])
            if m == "GET":
                return h.send(200, {"ticket": _pub(t)})
            if m == "PUT":
                b = dict(h.body().get("ticket", {}))
                c = b.pop("comment", None)
                if c is not None:
                    text = c.get("body", c.get("html_body"))
                    t["_comments"].append({"id": len(t["_comments"]) + 1, "author_id": 9001, "body": text, "plain_body": re.sub(r"<[^>]+>", "", text or ""),
                                           "public": c.get("public", True), "created_at": core.now_iso(),
                                           "attachments": [st["uploads"][x]["attachment"] for x in c.get("uploads", []) if x in st["uploads"]]})
                t.update(b)
                t["updated_at"] = core.now_iso()
                return h.send(200, {"ticket": _pub(t)})
            if m == "DELETE":
                st["tickets"].remove(t)
                return h.send(204)
        if p == "/search" and m == "GET":
            return h.send(200, {"results": _search_tickets(st, q.get("query", "")), "next_page": None, "count": 0})
        # ---- users
        if p in ("/users", "/users/create_or_update") and m == "POST":
            u = h.body().get("user", {})
            old = next((x for x in st["users"] if u.get("email") and str(x.get("email") or "").lower() == str(u.get("email") or "").lower()), None)
            if old is not None:
                if p == "/users":
                    return h.send(422, {"error": "RecordInvalid", "description": "Record validation errors", "details": {"email": [{"description": "Email is already being used by another user"}]}})
                old.update(u)
                return h.send(200, {"user": old})
            rec = {"id": _nid(st), "phone": None, "external_id": None, "organization_id": None, "time_zone": "UTC", "suspended": False,
                   "created_at": core.now_iso(), "user_fields": {}, **u}
            st["users"].append(rec)
            return h.send(201, {"user": rec})
        mu = re.match(r"/users/(\d+)(/tickets/requested)?$", p)
        if mu:
            uid = int(mu.group(1))
            if mu.group(2):
                return _page(h, "tickets", [_pub(t) for t in st["tickets"] if t["requester_id"] == uid])
            u = next((x for x in st["users"] if x["id"] == uid), None)
            if u is None:
                return h.send(404, {"error": "RecordNotFound", "description": "Not found"})
            if m == "PUT":
                b = h.body().get("user", {})
                if "user_fields" in b:
                    u.setdefault("user_fields", {}).update(b.pop("user_fields"))
                u.update(b)
            return h.send(200, {"user": u})
        if p == "/users/search":
            return h.send(200, {"users": _search_users(st, q.get("query", "")), "next_page": None})
        # ---- organizations and groups
        if p == "/organizations" and m == "POST":
            o = h.body().get("organization", {})
            rec = {"id": _nid(st), "domain_names": [], "details": None, "notes": None, "external_id": None, "group_id": None, "tags": [],
                   "created_at": core.now_iso(), "organization_fields": {}, **o}
            st["organizations"].append(rec)
            return h.send(201, {"organization": rec})
        if p == "/organizations" and m == "GET":
            return _page(h, "organizations", st["organizations"])
        mo = re.match(r"/organizations/(\d+)(/tickets)?$", p)
        if mo:
            oid = int(mo.group(1))
            if mo.group(2):
                return _page(h, "tickets", [_pub(t) for t in st["tickets"] if t.get("organization_id") == oid])
            o = next((x for x in st["organizations"] if x["id"] == oid), None)
            if o is None:
                return h.send(404, {"error": "RecordNotFound", "description": "Not found"})
            if m == "PUT":
                b = h.body().get("organization", {})
                if "organization_fields" in b:
                    o.setdefault("organization_fields", {}).update(b.pop("organization_fields"))
                o.update(b)
            return h.send(200, {"organization": o})
        if p == "/groups":
            return _page(h, "groups", st["groups"])
        # ---- uploads and attachments
        if p == "/uploads" and m == "POST":
            data = h.raw()
            aid = _nid(st)
            tok = q.get("token") or "ut-upload-%d" % aid
            host = h.headers.get("Host", "127.0.0.1")
            att = {"id": aid, "file_name": q.get("filename", "file"), "size": len(data), "content_type": h.headers.get("Content-Type", ""),
                   "content_url": "http://%s/api/v2/attachments/%d/content" % (host, aid)}
            st["attachments"][str(aid)] = {"meta": att, "data": data.decode("latin-1")}
            st["uploads"].setdefault(tok, {"token": tok, "attachments": []})["attachments"].append(att)
            st["uploads"][tok]["attachment"] = att
            return h.send(201, {"upload": {"token": tok, "attachment": att, "attachments": st["uploads"][tok]["attachments"]}})
        ma = re.match(r"/attachments/(\d+)(/content)?$", p)
        if ma:
            a = st["attachments"].get(ma.group(1))
            if a is None:
                return h.send(404, {"error": "RecordNotFound", "description": "Not found"})
            if ma.group(2):
                return _bytes(h, a["data"].encode("latin-1"), a["meta"]["content_type"] or "application/octet-stream")
            return h.send(200, {"attachment": a["meta"]})
        # ---- views and fields
        if p == "/views/active":
            return _page(h, "views", st["views"])
        mv = re.match(r"/views/(\d+)/tickets$", p)
        if mv:
            if not any(v["id"] == int(mv.group(1)) for v in st["views"]):
                return h.send(404, {"error": "RecordNotFound", "description": "Not found"})
            return _page(h, "tickets", [_pub(t) for t in st["tickets"] if t["priority"] == "urgent" and t["assignee_id"] is None])
        if p == "/ticket_fields":
            return h.send(200, {"ticket_fields": st["ticket_fields"], "next_page": None})
    raise KeyError(path)


ROUTES = [("/api/v2/", zendesk, "biz_zendesk")]
