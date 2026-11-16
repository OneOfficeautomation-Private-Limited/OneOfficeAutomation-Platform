"""QA stand-in server for the real-world tests (RW01-RW08): answers the parts of the SaaS APIs the agents' real toolkit
commands call, on this computer only (127.0.0.1), with made-up OOA-QA records. It is the workbook's "local test API":
a deterministic fallback, not a substitute for the live test tenants.

    python standin.py "<base folder>" [port]        (default port 8791)

Services, each under its own path so one port serves them all:
  /api/now/table/...                ServiceNow Table API          /services/...         Salesforce REST
  /rest/api/2|3/...                 Jira                          /graph/v1.0/...       Microsoft Graph (mail, users, groups,
  /ccx/...                          Workday                                             Teams, drives, calendar)
  /api/v1/...                       Okta                          /slack/api/...        Slack Web API
  /aws/ec2                          EC2 Query API (XML)           /github/...           GitHub REST
  /ado/{org}/...                    Azure DevOps                  /shopify/...          Shopify Admin REST (via ooa.rest)
  /sap/...                          SAP draft simulator (REST)    /approvals/...        approval decisions (REST)
  /ai/v1/chat/completions           scripted OpenAI-compatible model                  /kb/<page>   allow-listed pages
  /qa/health, /qa/faults, /qa/cleanup, /qa/state    the stand-in itself

Faults (runbook step 09): POST /qa/faults {"service": "...", "profile": "resilience"} makes the next requests to that
service fail once each with 503, 429 (Retry-After 1), 401, a slow answer and a duplicate (409).
"""
from __future__ import annotations

import json
import os
import re
import sys
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, unquote, urlparse

BASE = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.expanduser("~"), "OOA Real-world tests")
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 8791
LOCK = threading.RLock()
STATE: dict = {}
FAULTS: dict[str, list[str]] = {}
LOG = os.path.join(BASE, "standin", "requests.log")


def load():
    seed = os.path.join(BASE, "standin", "seed.json")
    with open(seed, encoding="utf-8") as fh:
        data = json.load(fh)
    with LOCK:
        STATE.clear()
        STATE.update(data)
        STATE.setdefault("created", [])


def sys_id():
    return uuid.uuid4().hex


def now_iso():
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


# ---------------------------------------------------------------- tiny query helpers

def soql(q: str):
    """SELECT a, b FROM Obj [WHERE F = 'v' [AND G = 'w']] [LIMIT n] -> (fields, object, conditions, limit)."""
    m = re.match(r"\s*SELECT\s+(.+?)\s+FROM\s+(\w+)(?:\s+WHERE\s+(.+?))?(?:\s+LIMIT\s+(\d+))?\s*$", q, re.I | re.S)
    if not m:
        raise ValueError("unsupported SOQL: " + q)
    fields = [f.strip() for f in m.group(1).split(",")]
    conds = []
    if m.group(3):
        for part in re.split(r"\s+AND\s+", m.group(3), flags=re.I):
            c = re.match(r"\s*(\w+)\s*(=|!=|LIKE)\s*'(.*)'\s*$", part, re.I)
            if c:
                conds.append((c.group(1), c.group(2).upper(), c.group(3)))
    return fields, m.group(2), conds, int(m.group(4)) if m.group(4) else None


def sn_query(q: str):
    """ServiceNow encoded query: a=b^c!=d^xLIKEy^ORDERBY..."""
    conds = []
    for part in (q or "").split("^"):
        if not part or part.startswith("ORDERBY"):
            continue
        m = re.match(r"(\w+)(!=|=|LIKE|STARTSWITH)(.*)", part)
        if m:
            conds.append(m.groups())
    return conds


def match(rec: dict, field, op, value):
    v = str(rec.get(field, ""))
    op = op.upper()
    if op in ("=",):
        return v == value
    if op == "!=":
        return v != value
    if op == "LIKE":
        return value.strip("%").lower() in v.lower()
    if op == "STARTSWITH":
        return v.startswith(value)
    return False


# ---------------------------------------------------------------- the handler

class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "OOA-QA-standin/1.0"

    def log_message(self, fmt, *args):   # one line per request in standin/requests.log
        try:
            with open(LOG, "a", encoding="utf-8") as fh:
                fh.write(time.strftime("%H:%M:%S ") + (fmt % args) + "\n")
        except OSError:
            pass

    # ---- plumbing
    def raw(self):
        """The request body, read once (and always, so a kept-alive connection never sees leftovers)."""
        if not hasattr(self, "_raw"):
            n = int(self.headers.get("Content-Length") or 0)
            self._raw = self.rfile.read(n) if n else b""
        return self._raw

    def body(self):
        raw = self.raw()
        ctype = self.headers.get("Content-Type", "")
        if not raw:
            return {}
        if "json" in ctype:
            try:
                return json.loads(raw.decode("utf-8"))
            except ValueError:
                return {}
        if "x-www-form-urlencoded" in ctype:
            return {k: v[0] for k, v in parse_qs(raw.decode("utf-8")).items()}
        return raw.decode("utf-8", "replace")

    def send(self, status, payload=None, ctype="application/json", headers=None):
        if isinstance(payload, (dict, list)):
            data = json.dumps(payload).encode("utf-8")
        elif payload is None:
            data = b""
        else:
            data = str(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", ctype + ("; charset=utf-8" if ctype.startswith("text") or "json" in ctype else ""))
        self.send_header("Content-Length", str(len(data)))
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        if data:
            self.wfile.write(data)

    def fault(self, service):
        """Injected failures for the resilience run, one per request, in order."""
        with LOCK:
            q = FAULTS.get(service) or []
            f = q.pop(0) if q else None
        if not f:
            return False
        if f == "503":
            self.send(503, {"error": {"message": "QA stand-in: injected temporary failure"}})
        elif f == "429":
            self.send(429, {"error": {"message": "QA stand-in: injected rate limit"}}, headers={"Retry-After": "1"})
        elif f == "401":
            self.send(401, {"error": {"message": "QA stand-in: injected expired sign-in"}})
        elif f.startswith("slow"):
            time.sleep(float(f.split(":")[1]) if ":" in f else 5)
            return False
        elif f == "409":
            self.send(409, {"error": {"message": "QA stand-in: injected duplicate"}})
        return True

    def route(self, method):
        if hasattr(self, "_raw"):
            del self._raw
        self.raw()
        u = urlparse(self.path)
        path, q = unquote(u.path), {k: v[0] for k, v in parse_qs(u.query, keep_blank_values=True).items()}
        try:
            for prefix, fn, service in ROUTES:
                if path.startswith(prefix):
                    if service and self.fault(service):
                        return
                    return fn(self, method, path, q)
            self.send(404, {"error": {"message": "QA stand-in: no such path " + path}})
        except KeyError as e:
            self.send(404, {"error": {"message": "QA stand-in: not found " + str(e)}})
        except ValueError as e:
            self.send(400, {"error": {"message": "QA stand-in: " + str(e)}})

    def do_GET(self): self.route("GET")
    def do_POST(self): self.route("POST")
    def do_PUT(self): self.route("PUT")
    def do_PATCH(self): self.route("PATCH")
    def do_DELETE(self): self.route("DELETE")


# ---------------------------------------------------------------- the stand-in itself

def qa(h: H, m, path, q):
    if path == "/qa/health":
        return h.send(200, {"ok": True, "port": PORT, "records": {k: len(v) if isinstance(v, (list, dict)) else 1 for k, v in STATE.items()}})
    if path == "/qa/faults" and m == "POST":
        b = h.body()
        svc = b.get("service", "all")
        plan = ["503", "429", "401", "slow:3", "409"] if b.get("profile") == "resilience" else []
        with LOCK:
            for s in ([svc] if svc != "all" else list(SERVICES)):
                FAULTS[s] = list(plan)
        return h.send(200, {"armed": svc, "faults": plan})
    if path == "/qa/reset" and m == "POST":
        load()
        return h.send(200, {"ok": True})
    if path == "/qa/cleanup" and m == "POST":
        run = h.body().get("run_id", "")
        removed = 0
        with LOCK:
            for coll in ("servicenow", "salesforce", "jira", "shopify", "sap", "approvals", "workday", "graph_directory"):
                store = STATE.get(coll, {})
                for name, recs in list(store.items()):
                    if isinstance(recs, list):
                        keep = [r for r in recs if not (run and run in json.dumps(r) and r.get("_created"))]
                        removed += len(recs) - len(keep)
                        store[name] = keep
        return h.send(200, {"removed": removed, "run_id": run})
    if path == "/qa/state":
        return h.send(200, STATE)
    raise KeyError(path)


def kb(h: H, m, path, q):
    page = path.split("/kb/", 1)[1]
    html = STATE.get("kb_pages", {}).get(page)
    if html is None:
        raise KeyError(page)
    return h.send(200, html, ctype="text/html")


# ---------------------------------------------------------------- ServiceNow

def servicenow(h: H, m, path, q):
    parts = path.split("/")          # ['', 'api', 'now', 'table', table, sys_id?]
    table, rid = parts[4], (parts[5] if len(parts) > 5 else None)
    with LOCK:
        rows = STATE.setdefault("servicenow", {}).setdefault(table, [])
        if m == "GET" and rid is None:
            found = [r for r in rows if all(match(r, *c) for c in sn_query(q.get("sysparm_query", "")))]
            off, lim = int(q.get("sysparm_offset", 0)), int(q.get("sysparm_limit", 1000))
            page = found[off:off + lim]
            fields = [f for f in q.get("sysparm_fields", "").split(",") if f]
            if fields:
                page = [{k: r.get(k) for k in fields} for r in page]
            return h.send(200, {"result": page})
        if m == "POST":
            rec = dict(h.body())
            rec.update({"sys_id": sys_id(), "number": "%s%07d" % ("INC" if table == "incident" else "TASK", 9000000 + len(rows)),
                        "sys_created_on": now_iso(), "_created": True})
            rows.append(rec)
            return h.send(201, {"result": rec})
        rec = next((r for r in rows if r.get("sys_id") == rid), None)
        if rec is None:
            return h.send(404, {"error": {"message": "No Record found", "detail": "Record doesn't exist or ACL restricts the record retrieval"}, "status": "failure"})
        if m == "GET":
            return h.send(200, {"result": rec})
        if m in ("PATCH", "PUT"):
            b = h.body()
            if "work_notes" in b or "comments" in b:
                key = "work_notes" if "work_notes" in b else "comments"
                rec.setdefault("_journal", []).append({"field": key, "text": b.pop(key), "at": now_iso()})
            rec.update(b)
            rec["sys_updated_on"] = now_iso()
            return h.send(200, {"result": rec})
        if m == "DELETE":
            rows.remove(rec)
            return h.send(204)
    raise KeyError(path)


# ---------------------------------------------------------------- Salesforce

def salesforce(h: H, m, path, q):
    if path == "/services/oauth2/token":
        return h.send(200, {"access_token": "qa-sf-token", "instance_url": "http://127.0.0.1:%d" % PORT, "token_type": "Bearer", "id": "qa"})
    store = STATE.setdefault("salesforce", {})
    mq = re.match(r"/services/data/v[\d.]+/(query|queryAll)$", path)
    if mq:
        fields, obj, conds, limit = soql(q.get("q", ""))
        with LOCK:
            found = [r for r in store.get(obj, []) if all(match(r, *c) for c in conds)]
        if limit:
            found = found[:limit]
        recs = [{**{"attributes": {"type": obj, "url": "/services/data/v61.0/sobjects/%s/%s" % (obj, r["Id"])}}, **{f: r.get(f) for f in fields}} for r in found]
        return h.send(200, {"totalSize": len(recs), "done": True, "records": recs})
    ms = re.match(r"/services/data/v[\d.]+/sobjects/(\w+)(?:/([^/]+))?(?:/([^/]+))?$", path)
    if not ms:
        raise KeyError(path)
    obj, a, b = ms.groups()
    with LOCK:
        rows = store.setdefault(obj, [])
        if m == "POST" and a is None:
            rec = dict(h.body())
            rec.update({"Id": "500QA" + uuid.uuid4().hex[:13].upper(), "_created": True})
            rows.append(rec)
            return h.send(201, {"id": rec["Id"], "success": True, "errors": []})
        if b is not None:            # by external id: /sobjects/Obj/Field/value
            rec = next((r for r in rows if str(r.get(a)) == b), None)
            if m == "PATCH":
                if rec is None:
                    rec = {a: b, "Id": "500QA" + uuid.uuid4().hex[:13].upper(), "_created": True}
                    rows.append(rec)
                    rec.update(h.body())
                    return h.send(201, {"id": rec["Id"], "success": True, "created": True, "errors": []})
                rec.update(h.body())
                return h.send(200, {"id": rec["Id"], "success": True, "created": False, "errors": []})
        else:
            rec = next((r for r in rows if r.get("Id") == a), None)
        if rec is None:
            return h.send(404, [{"message": "The requested resource does not exist", "errorCode": "NOT_FOUND"}])
        if m == "GET":
            fields = [f for f in q.get("fields", "").split(",") if f]
            out = {k: rec.get(k) for k in fields} if fields else {k: v for k, v in rec.items() if not k.startswith("_")}
            out["Id"] = rec["Id"]
            return h.send(200, {"attributes": {"type": obj}, **out})
        if m == "PATCH":
            rec.update(h.body())
            return h.send(204)
    raise KeyError(path)


# ---------------------------------------------------------------- Jira

def jira(h: H, m, path, q):
    store = STATE.setdefault("jira", {})
    issues = store.setdefault("issues", [])
    if path in ("/rest/api/3/search/jql", "/rest/api/2/search"):
        b = h.body() if m == "POST" else q
        jql = b.get("jql", "")
        conds = re.findall(r'(\w+)\s*=\s*"?([^"\s]+)"?', jql)
        with LOCK:
            found = [i for i in issues if all((v in i["fields"].get("labels", [])) if k == "labels" else
                                              (i["key"].split("-")[0] == v if k == "project" else str(i["fields"].get(k, "")) == v) for k, v in conds)]
        if path.endswith("/jql"):
            return h.send(200, {"issues": found, "isLast": True})
        return h.send(200, {"issues": found, "startAt": 0, "maxResults": len(found), "total": len(found)})
    if path.startswith("/rest/api/2/user/search"):
        return h.send(200, [{"accountId": "qa-account-1", "name": "qa.agent", "emailAddress": q.get("query", q.get("username", ""))}])
    if path == "/rest/api/2/issue" and m == "POST":
        f = h.body().get("fields", {})
        with LOCK:
            n = len(issues) + 1
            proj = (f.get("project") or {}).get("key", "OOAQA")
            issue = {"id": str(10000 + n), "key": "%s-%d" % (proj, n), "self": "http://127.0.0.1:%d/rest/api/2/issue/%d" % (PORT, 10000 + n),
                     "fields": {**f, "status": {"name": "To Do"}, "labels": f.get("labels", []), "created": now_iso()}, "_created": True, "_comments": []}
            issues.append(issue)
        return h.send(201, {"id": issue["id"], "key": issue["key"], "self": issue["self"]})
    mi = re.match(r"/rest/api/2/issue/([^/]+)(?:/(transitions|comment))?$", path)
    if not mi:
        raise KeyError(path)
    key, sub = mi.groups()
    with LOCK:
        issue = next((i for i in issues if i["key"] == key or i["id"] == key), None)
        if issue is None:
            return h.send(404, {"errorMessages": ["Issue does not exist or you do not have permission to see it."], "errors": {}})
        if sub == "transitions":
            if m == "GET":
                return h.send(200, {"transitions": [{"id": "11", "name": "To Do", "to": {"name": "To Do"}}, {"id": "21", "name": "Start", "to": {"name": "In Progress"}},
                                                    {"id": "31", "name": "Done", "to": {"name": "Done"}}]})
            tid = str((h.body().get("transition") or {}).get("id"))
            issue["fields"]["status"] = {"name": {"11": "To Do", "21": "In Progress", "31": "Done"}.get(tid, "To Do")}
            return h.send(204)
        if sub == "comment":
            c = {"id": str(len(issue["_comments"]) + 1), "body": h.body().get("body", ""), "created": now_iso()}
            issue["_comments"].append(c)
            return h.send(201, c)
        if m == "GET":
            return h.send(200, {k: v for k, v in issue.items() if not k.startswith("_")})
        if m == "PUT":
            issue["fields"].update(h.body().get("fields", {}))
            return h.send(204)
    raise KeyError(path)


# ---------------------------------------------------------------- Microsoft Graph (mail first; more as the RWs need it)

def directory(h: H, m, p, q):
    """Entra ID users and groups (RW02, RW05), as Graph answers them; agents call these with ooa.rest."""
    d = STATE.setdefault("graph_directory", {"users": [], "groups": [], "members": {}})
    users, members = d["users"], d["members"]
    with LOCK:
        if p == "/users" and m == "GET":
            flt = q.get("$filter", "")
            found = users
            mm = re.search(r"(\w+) eq '([^']*)'", flt)
            if mm:
                found = [u for u in users if str(u.get(mm.group(1), "")).lower() == mm.group(2).lower()]
            ms = re.search(r"startswith\((\w+),\s*'([^']*)'\)", flt)
            if ms:
                found = [u for u in found if str(u.get(ms.group(1), "")).lower().startswith(ms.group(2).lower())]
            return h.send(200, {"value": found})
        if p == "/users" and m == "POST":
            b = h.body()
            if any(u["userPrincipalName"].lower() == str(b.get("userPrincipalName", "")).lower() for u in users):
                return h.send(400, {"error": {"code": "Request_BadRequest", "message": "Another object with the same value for property userPrincipalName already exists."}})
            u = {"id": "u-" + uuid.uuid4().hex[:10], "accountEnabled": b.get("accountEnabled", True), **{k: v for k, v in b.items() if k != "passwordProfile"}, "_created": True}
            users.append(u)
            return h.send(201, u)
        mu = re.match(r"/users/([^/]+)(/memberOf|/revokeSignInSessions)?$", p)
        if mu:
            u = next((x for x in users if x["id"] == mu.group(1) or x.get("userPrincipalName", "").lower() == mu.group(1).lower()), None)
            if u is None:
                return h.send(404, {"error": {"code": "Request_ResourceNotFound", "message": "Resource '%s' does not exist." % mu.group(1)}})
            if mu.group(2) == "/memberOf":
                gs = [g for g in d["groups"] if u["id"] in members.get(g["id"], [])]
                return h.send(200, {"value": [{"@odata.type": "#microsoft.graph.group", **g} for g in gs]})
            if mu.group(2) == "/revokeSignInSessions":
                u["signInSessionsValidFromDateTime"] = now_iso()
                return h.send(200, {"value": True})
            if m == "PATCH":
                u.update(h.body())
                return h.send(204)
            if m == "DELETE":
                users.remove(u)
                return h.send(204)
            return h.send(200, u)
        mg = re.match(r"/groups/([^/]+)/members(?:/([^/]+))?/\$ref$", p)
        if mg:
            gid, uid = mg.groups()
            lst = members.setdefault(gid, [])
            if m == "POST":
                uid = str(h.body().get("@odata.id", "")).rstrip("/").split("/")[-1]
                if uid in lst:
                    return h.send(400, {"error": {"code": "Request_BadRequest", "message": "One or more added object references already exist."}})
                lst.append(uid)
                return h.send(204)
            if m == "DELETE":
                if uid not in lst:
                    return h.send(404, {"error": {"code": "Request_ResourceNotFound", "message": "Not a member"}})
                lst.remove(uid)
                return h.send(204)
        mg = re.match(r"/groups/([^/]+)/members$", p)
        if mg:
            ids = members.get(mg.group(1), [])
            return h.send(200, {"value": [u for u in users if u["id"] in ids]})
        if p == "/groups":
            return h.send(200, {"value": d["groups"]})
    raise KeyError(p)


def teams(h: H, m, p, q):
    mt = re.match(r"/teams/([^/]+)/channels/([^/]+)/messages$", p)
    with LOCK:
        t = STATE.setdefault("teams", {}).get(mt.group(1)) if mt else None
        ch = t and t["channels"].get(mt.group(2))
        if not ch:
            return h.send(404, {"error": {"code": "NotFound", "message": "Team or channel not found"}})
        if m == "POST":
            b = h.body()
            msg = {"id": str(int(time.time() * 1000)), "createdDateTime": now_iso(), "subject": b.get("subject"), "body": b.get("body", {}), "_created": True}
            ch["messages"].append(msg)
            return h.send(201, {**msg, "webUrl": "http://127.0.0.1:%d/teams/%s/%s/%s" % (PORT, mt.group(1), mt.group(2), msg["id"])})
        return h.send(200, {"value": [{k: v for k, v in x.items() if not k.startswith("_")} for x in ch["messages"]]})


def drive(h: H, m, p, q):
    """OneDrive / SharePoint files: PUT .../root:/path:/content stores the file under <base>/standin/drive/."""
    md = re.match(r"/(.+?/drive)/root:/(.+?):/content$", p) or re.match(r"/(.+?/drive)/root:/(.+?):$", p)
    if not md:
        raise KeyError(p)
    where = os.path.join(BASE, "standin", "drive", re.sub(r"[^A-Za-z0-9_.-]+", "_", md.group(1)), *md.group(2).split("/"))
    if m == "PUT":
        data = h.raw()
        os.makedirs(os.path.dirname(where), exist_ok=True)
        with open(where, "wb") as fh:
            fh.write(data)
        iid = "item-" + uuid.uuid5(uuid.NAMESPACE_URL, where).hex[:12]
        return h.send(201, {"id": iid, "name": os.path.basename(where), "size": len(data), "webUrl": "http://127.0.0.1:%d/drive/%s" % (PORT, md.group(2))})
    if not os.path.exists(where):
        return h.send(404, {"error": {"code": "itemNotFound", "message": "The resource could not be found."}})
    if p.endswith("/content"):
        with open(where, "rb") as fh:
            return h.send(200, fh.read(), ctype="application/octet-stream")
    return h.send(200, {"id": "item-" + uuid.uuid5(uuid.NAMESPACE_URL, where).hex[:12], "name": os.path.basename(where), "size": os.path.getsize(where)})


def graph(h: H, m, path, q):
    p = path[len("/graph/v1.0"):]
    if p.startswith("/teams/"):
        return teams(h, m, p, q)
    if "/drive/root:" in p:
        return drive(h, m, p, q)
    if p.startswith("/groups") or p == "/users" or (p.startswith("/users/") and "@" not in p.split("/")[2]):
        return directory(h, m, p, q)
    mail = STATE.setdefault("mail", {})
    mu = re.match(r"/users/([^/]+)(/.*)$", p) or re.match(r"/(me)(/.*)$", p)
    if not mu:
        raise KeyError(path)
    if mu.group(2) in ("/memberOf", "/revokeSignInSessions") or re.fullmatch(r"/users/[^/]+", p):
        return directory(h, m, p, q)
    box, rest = mu.group(1).lower(), mu.group(2)
    with LOCK:
        msgs = mail.setdefault(box, [])
        if rest == "/sendMail" and m == "POST":
            msg = h.body().get("message", {})
            to = [r.get("emailAddress", {}).get("address", "") for r in msg.get("toRecipients", [])]
            bad = [a for a in to if not a.lower().endswith("@ooa-qa.test")]
            if bad:   # the QA tenant blocks external recipients, as the workbook asks
                return h.send(403, {"error": {"code": "ErrorAccessDenied", "message": "QA stand-in: external recipients are blocked: " + ", ".join(bad)}})
            for a in to:
                mail.setdefault(a.lower(), []).append({"id": "AAMk" + uuid.uuid4().hex, "subject": msg.get("subject", ""), "isRead": False,
                    "from": {"emailAddress": {"address": box}}, "receivedDateTime": now_iso(), "hasAttachments": bool(msg.get("attachments")),
                    "body": msg.get("body", {}), "toRecipients": msg.get("toRecipients", []), "parentFolderId": "inbox", "_created": True})
            mail.setdefault(box + "#sent", []).append({"subject": msg.get("subject", ""), "to": to, "at": now_iso()})
            return h.send(202)
        mf = re.match(r"/mailFolders/([^/]+)/messages$", rest)
        if mf and m == "GET":
            folder = mf.group(1).lower()
            found = [x for x in msgs if x.get("parentFolderId", "inbox").lower() == folder]
            flt = q.get("$filter", "")
            if "isRead eq false" in flt:
                found = [x for x in found if not x.get("isRead")]
            top = int(q.get("$top", 50))
            return h.send(200, {"value": [{k: v for k, v in x.items() if not k.startswith("_") and k != "body"} for x in found[:top]]})
        if rest.startswith("/mailFolders") and m == "GET":
            name = re.search(r"displayName eq '([^']*)'", q.get("$filter", ""))
            return h.send(200, {"value": [{"id": name.group(1).lower(), "displayName": name.group(1)}] if name else []})
        mm = re.match(r"/messages/([^/]+)(/move|/attachments)?$", rest)
        if mm:
            mid, sub = mm.groups()
            msg = next((x for x in msgs if x["id"] == mid), None)
            if msg is None:
                return h.send(404, {"error": {"code": "ErrorItemNotFound", "message": "The specified object was not found in the store."}})
            if sub == "/move":
                msg["parentFolderId"] = str(h.body().get("destinationId", "inbox")).lower()
                return h.send(201, {"id": msg["id"]})
            if sub == "/attachments":
                return h.send(200, {"value": msg.get("_attachments", [])})
            if m == "GET":
                return h.send(200, {k: v for k, v in msg.items() if not k.startswith("_")})
            if m == "PATCH":
                msg.update(h.body())
                return h.send(200, {k: v for k, v in msg.items() if not k.startswith("_")})
    raise KeyError(path)


def workday(h: H, m, path, q):
    mt = re.match(r"/ccx/oauth2/([^/]+)/token$", path)
    if mt:
        return h.send(200, {"access_token": "qa-workday-token", "token_type": "Bearer", "expires_in": 3600})
    mw = re.match(r"/ccx/api/v1/([^/]+)/(workers|staged)(?:/([^/?]+))?$", path)
    if not mw:
        raise KeyError(path)
    tenant, coll, wid = mw.groups()
    store = STATE.setdefault("workday", {}).setdefault(tenant, {"workers": [], "staged": []})
    with LOCK:
        rows = store.setdefault(coll, [])
        if m == "GET" and wid is None:
            s = q.get("search", "").lower()
            found = [w for w in rows if not s or s in w.get("descriptor", "").lower() or s in w.get("employeeId", "").lower()]
            off, lim = int(q.get("offset", 0)), int(q.get("limit", 100))
            return h.send(200, {"total": len(found), "data": found[off:off + lim]})
        if m == "POST":
            rec = {"id": uuid.uuid4().hex, **h.body(), "stagedAt": now_iso(), "_created": True}
            rows.append(rec)
            return h.send(201, rec)
        rec = next((w for w in rows if w["id"] == wid or w.get("employeeId") == wid), None)
        if rec is None:
            return h.send(404, {"error": "invalid resource", "description": "No worker " + str(wid)})
        return h.send(200, rec)


SERVICES = ["servicenow", "salesforce", "jira", "graph", "workday"]
ROUTES = [
    ("/qa/", qa, None), ("/kb/", kb, None),
    ("/api/now/", servicenow, "servicenow"), ("/services/", salesforce, "salesforce"), ("/rest/api/", jira, "jira"),
    ("/graph/v1.0/", graph, "graph"), ("/ccx/", workday, "workday"),
]


def main():
    os.makedirs(os.path.join(BASE, "standin"), exist_ok=True)
    load()
    srv = ThreadingHTTPServer(("127.0.0.1", PORT), H)
    if sys.stdout:   # pythonw (started by the "Start QA stand-ins" agent) has no console
        print("QA stand-in on http://127.0.0.1:%d (base %s)" % (PORT, BASE), flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
