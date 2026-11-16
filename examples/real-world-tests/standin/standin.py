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

import base64
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
            for coll in ("servicenow", "salesforce", "jira", "shopify", "sap", "approvals", "workday", "graph_directory", "carrier"):
                store = STATE.get(coll, {})
                for name, recs in list(store.items()):
                    if isinstance(recs, list):
                        keep = [r for r in recs if not (run and run in json.dumps(r) and r.get("_created"))]
                        removed += len(recs) - len(keep)
                        store[name] = keep
            # stock levels are a fixture: put them back as seeded, so the next run starts from the same stock
            with open(os.path.join(BASE, "standin", "seed.json"), encoding="utf-8") as fh:
                seeded = json.load(fh).get("shopify", {})
            if "shopify" in STATE and seeded:
                STATE["shopify"]["inventory"] = seeded.get("inventory", {})
                STATE["shopify"]["conflict_once"] = seeded.get("conflict_once", [])
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


def approvals(h: H, m, path, q):
    """Approval requests (RW03, RW05, RW08): decisions come from the seeded answers of the QA approvers."""
    store = STATE.setdefault("approvals", {"decisions": {}, "requests": []})
    with LOCK:
        if path == "/approvals/requests" and m == "POST":
            b = h.body()
            if str(b.get("approver", "")).lower() == str(b.get("requester", "")).lower():
                return h.send(409, {"error": {"message": "QA approvals: an approver cannot approve their own request"}})
            r = {"id": "APR-" + uuid.uuid4().hex[:8].upper(), "status": "pending", "created": now_iso(), **b, "_created": True}
            store["requests"].append(r)
            return h.send(201, r)
        mr = re.match(r"/approvals/requests/([^/]+)$", path)
        if mr and m == "GET":
            r = next((x for x in store["requests"] if x["id"] == mr.group(1)), None)
            if r is None:
                raise KeyError(path)
            decision = store["decisions"].get(str(r.get("subject", "")), "approve")
            if decision != "timeout":
                r["status"] = "approved" if decision == "approve" else "declined"
                r.setdefault("decided", now_iso())
            return h.send(200, {k: v for k, v in r.items() if not k.startswith("_")})
    raise KeyError(path)


def sap(h: H, m, path, q):
    """SAP QA draft simulator (RW03, RW06): sign on to client QA-100, find vendors and POs, park, read back, cancel, sign off.
    It works out the totals itself as SAP does (amounts to two decimals, tax from the tax code); posting and payment are refused."""
    store = STATE.setdefault("sap", {})
    for k, v in (("parked", []), ("posted", []), ("sessions", []), ("clients", ["QA-100"]), ("vendors", {}), ("pos", {}), ("tax_codes", {})):
        store.setdefault(k, v)
    if path.startswith("/sap/post") and path != "/sap/posted" or path.startswith("/sap/pay"):
        return h.send(403, {"error": {"message": "QA SAP: posting and payment are forbidden in the real-world tests"}})
    with LOCK:
        if path == "/sap/posted":   # audit read, no sign-on needed: must stay empty
            return h.send(200, {"value": store["posted"], "count": len(store["posted"])})
        if path == "/sap/logon" and m == "POST":
            b = h.body()
            if b.get("client") not in store["clients"]:
                return h.send(403, {"error": {"message": "QA SAP: client %s is not a QA client" % b.get("client")}})
            auth = h.headers.get("Authorization", "")
            try:
                user, _, password = base64.b64decode(auth[6:]).decode("utf-8").partition(":") if auth.startswith("Basic ") else ("", "", "")
            except ValueError:
                user, password = "", ""
            if not user or not password:
                return h.send(401, {"error": {"message": "QA SAP: sign on with a user and password (basic)"}})
            sid = "SAPQA-" + uuid.uuid4().hex[:10]
            store["sessions"].append(sid)
            return h.send(200, {"session": sid, "client": b["client"], "system": "QA1"})
        sid = h.headers.get("X-SAP-Session", "")
        if sid not in store["sessions"]:
            return h.send(401, {"error": {"message": "QA SAP: not signed on (X-SAP-Session missing or ended)"}})
        if path == "/sap/logoff" and m == "POST":
            store["sessions"].remove(sid)
            return h.send(200, {"signed_off": sid})
        mv = re.match(r"/sap/vendors/([^/]+)$", path)
        if mv:
            name = store["vendors"].get(mv.group(1))
            return h.send(200, {"vendor": mv.group(1), "name": name}) if name else h.send(404, {"error": {"message": "QA SAP: no vendor " + mv.group(1)}})
        mo = re.match(r"/sap/po/([^/]+)$", path)
        if mo:
            sup = store["pos"].get(mo.group(1))
            return h.send(200, {"po": mo.group(1), "vendor": sup}) if sup else h.send(404, {"error": {"message": "QA SAP: no PO " + mo.group(1)}})
        if path == "/sap/parked" and m == "POST":
            b = h.body()
            if b.get("client") not in store["clients"]:
                return h.send(403, {"error": {"message": "QA SAP: only the QA client QA-100 may be used"}})
            if b.get("tax_code") not in store["tax_codes"]:
                return h.send(400, {"error": {"message": "QA SAP: unknown tax code %s" % b.get("tax_code")}})
            dup = next((d for d in store["parked"] if d.get("invoice") == b.get("invoice") and d.get("vendor") == b.get("vendor") and d["status"] == "parked"), None)
            if dup:
                return h.send(409, {"error": {"message": "QA SAP: invoice already parked as " + dup["document"]}})
            lines = b.get("lines") or []
            if isinstance(lines, str):
                lines = json.loads(lines or "[]")
            net = sum(round(float(x.get("qty", 0)) * round(float(x.get("unit_price", 0)), 2), 2) for x in lines)
            tax = round(net * store["tax_codes"][b["tax_code"]] / 100, 2)
            d = {"document": str(5100000000 + len(store["parked"]) + 1), "status": "parked", "created": now_iso(), **b, "lines": lines,
                 "net": round(net, 2), "tax": tax, "total": round(net + tax, 2), "_created": True}
            store["parked"].append(d)
            return h.send(201, {k: v for k, v in d.items() if not k.startswith("_")})
        mp = re.match(r"/sap/parked/(\d+)$", path)
        if mp:
            d = next((x for x in store["parked"] if x["document"] == mp.group(1)), None)
            if d is None:
                return h.send(404, {"error": {"message": "QA SAP: no parked document " + mp.group(1)}})
            if m == "DELETE":
                d["status"] = "cancelled"
                return h.send(200, {"document": d["document"], "status": "cancelled"})
            return h.send(200, {k: v for k, v in d.items() if not k.startswith("_")})
    raise KeyError(path)


def shopify(h: H, m, path, q):
    """Shopify development store (RW04): products, draft orders, inventory levels and a reservation API with versions.
    Payments, refunds, transactions and product writes are refused: the workbook forbids charging cards and catalogue changes."""
    store = STATE.setdefault("shopify", {})
    for k in ("products", "draft_orders", "reservations", "conflict_once"):
        store.setdefault(k, [])
    store.setdefault("inventory", {})
    p = re.sub(r"^/admin/api/[\d-]+", "", path)
    if re.search(r"/(transactions|refunds|payments|capture)", p) or (p.startswith("/products") and m in ("POST", "PUT", "DELETE")):
        return h.send(403, {"errors": "QA store: payments, refunds and product changes are forbidden in the real-world tests"})
    with LOCK:
        if p == "/products.json":
            tag = q.get("tag", "")
            return h.send(200, {"products": [x for x in store["products"] if not tag or tag in x.get("tags", "")]})
        if p == "/products/count.json":
            return h.send(200, {"count": len(store["products"])})
        mp = re.match(r"/products/(\d+)\.json$", p)
        if mp:
            x = next((x for x in store["products"] if str(x["id"]) == mp.group(1)), None)
            return h.send(200, {"product": x}) if x else h.send(404, {"errors": "Not Found"})
        if p == "/draft_orders.json":
            tag = q.get("tag", "")
            return h.send(200, {"draft_orders": [x for x in store["draft_orders"] if not tag or tag in x.get("tags", "")]})
        md = re.match(r"/draft_orders/(\d+)\.json$", p)
        if md:
            x = next((x for x in store["draft_orders"] if str(x["id"]) == md.group(1)), None)
            if x is None:
                return h.send(404, {"errors": "Not Found"})
            if m == "PUT":   # only the note and tags of a draft order may change
                b = h.body().get("draft_order", {})
                x.update({k: v for k, v in b.items() if k in ("note", "tags")})
            return h.send(200, {"draft_order": x})
        if p == "/inventory_levels.json":
            sku = q.get("sku", "")
            return h.send(200, {"inventory_levels": [dict(v, sku=k.split("|")[0], location=k.split("|")[1]) for k, v in store["inventory"].items()
                                                     if not sku or k.split("|")[0] == sku]})
        if p in ("/inventory/reserve.json", "/inventory/release.json") and m == "POST":
            b = h.body()
            key = "%s|%s" % (b.get("sku"), b.get("location"))
            lvl = store["inventory"].get(key)
            if lvl is None:
                return h.send(404, {"errors": "No stock record " + key})
            if p.endswith("reserve.json") and key in store["conflict_once"]:
                store["conflict_once"].remove(key)
                lvl["version"] += 1      # another channel reserved first
                return h.send(409, {"errors": "Version conflict: stock %s changed (now version %d)" % (key, lvl["version"])})
            if int(b.get("version", -1)) != lvl["version"]:
                return h.send(409, {"errors": "Version conflict: stock %s is at version %d" % (key, lvl["version"])})
            qty = int(b.get("quantity", 0))
            if p.endswith("reserve.json"):
                if qty > lvl["available"]:
                    return h.send(422, {"errors": "Only %d available" % lvl["available"]})
                lvl["available"] -= qty
                lvl["reserved"] += qty
                store["reservations"].append({"order": b.get("order"), "key": key, "quantity": qty, "run": b.get("run", ""), "_created": True})
            else:
                lvl["available"] += qty
                lvl["reserved"] -= qty
            lvl["version"] += 1
            return h.send(200, dict(lvl, sku=b.get("sku"), location=b.get("location")))
    raise KeyError(path)


def carrier(h: H, m, path, q):
    """Shipping carrier stub (RW04-B5): test shipments with tracking numbers, and delivery events."""
    store = STATE.setdefault("carrier", {"shipments": []})
    with LOCK:
        if path == "/carrier/health":
            return h.send(200, {"carrier": "OOA-QA-CARRIER", "mode": "test"})
        if path == "/carrier/shipments" and m == "POST":
            b = h.body()
            if not str(b.get("ship_to", {}).get("email", "")).endswith("@ooa-qa.test"):
                return h.send(422, {"error": "QA carrier: test shipments go to QA addresses only"})
            sh = {"id": "SHP-" + uuid.uuid4().hex[:8].upper(), "tracking": "1ZQA" + uuid.uuid4().hex[:12].upper(), "status": "label_created",
                  "created": now_iso(), **b, "_created": True}
            store["shipments"].append(sh)
            return h.send(201, {k: v for k, v in sh.items() if not k.startswith("_")})
        me = re.match(r"/carrier/shipments/([^/]+)/events$", path)
        if me:
            sh = next((x for x in store["shipments"] if x["id"] == me.group(1)), None)
            if sh is None:
                return h.send(404, {"error": "no shipment"})
            return h.send(200, {"events": [{"status": "label_created", "at": sh["created"]}, {"status": "in_transit", "at": now_iso()},
                                           {"status": "delivered", "at": now_iso()}]})
    raise KeyError(path)


def kafka(h: H, m, path, q):
    """Kafka REST proxy (v3 produce, v2 consumers) for RW04-B3 and RW07-B1; topics live in memory."""
    store = STATE.setdefault("kafka", {"topics": {}, "groups": {}})
    with LOCK:
        if path == "/kafka/v3/clusters":
            return h.send(200, {"data": [{"cluster_id": "qa-cluster"}]})
        mt = re.match(r"/kafka/v3/clusters/[^/]+/topics(?:/([^/]+))?(/records)?$", path)
        if mt:
            topic, rec = mt.groups()
            if topic is None:
                if m == "POST":
                    store["topics"].setdefault(h.body().get("topic_name"), [])
                    return h.send(201, {})
                return h.send(200, {"data": [{"topic_name": t, "partitions_count": 1, "replication_factor": 1, "is_internal": False} for t in store["topics"]]})
            msgs = store["topics"].setdefault(topic, [])
            if rec and m == "POST":
                b = h.body()
                msgs.append({"key": (b.get("key") or {}).get("data"), "value": (b.get("value") or {}).get("data"), "offset": len(msgs)})
                return h.send(200, {"error_code": 200, "partition_id": 0, "offset": len(msgs) - 1, "timestamp": now_iso()})
            return h.send(200, {"topic_name": topic, "partitions_count": 1})
        mg = re.match(r"/kafka/consumers/([^/]+)(?:/instances/([^/]+))?(/subscription|/records|/offsets)?$", path)
        if mg:
            group, inst, sub = mg.groups()
            g = store["groups"].setdefault(group, {"offsets": {}, "instances": {}})
            if inst is None and m == "POST":
                iid = "i-" + uuid.uuid4().hex[:6]
                g["instances"][iid] = {"topics": [], "sent": False, "read": {}}
                return h.send(200, {"instance_id": iid, "base_uri": "http://127.0.0.1:%d/kafka/consumers/%s/instances/%s" % (PORT, group, iid)})
            it = g["instances"].get(inst, {"topics": [], "sent": True, "read": {}})
            if sub == "/subscription":
                it["topics"] = h.body().get("topics", [])
                return h.send(204)
            if sub == "/records":
                out = []
                if not it["sent"]:
                    for t in it["topics"]:
                        start = g["offsets"].get(t, 0)
                        out += [{"topic": t, "partition": 0, "offset": x["offset"], "key": x["key"], "value": x["value"]} for x in store["topics"].get(t, [])[start:]]
                        it["read"][t] = len(store["topics"].get(t, []))
                    it["sent"] = True
                return h.send(200, out, ctype="application/vnd.kafka.json.v2+json")
            if sub == "/offsets":
                for t, n in it["read"].items():
                    g["offsets"][t] = n
                return h.send(200, {})
            if m == "DELETE":
                g["instances"].pop(inst, None)
                return h.send(204)
    raise KeyError(path)


AI_LABELS = {"supplier": "Supplier", "invoice_number": "Invoice number", "invoice_date": "Invoice date", "po_number": "PO number",
             "currency": "Currency", "net": "Net", "tax": "Tax", "total": "Total"}


def ai(h: H, m, path, q):
    """A scripted chat model (OpenAI-compatible) for the AI steps: answers Extract fields from labelled lines, so runs repeat exactly.
    Numbers written the German way (1.234,56) come back with low confidence, the way a real model hesitates over a locale."""
    if path != "/v1/chat/completions" or m != "POST":
        raise KeyError(path)
    b = h.body()
    msgs = b.get("messages", [])
    system = " ".join(str(x.get("content", "")) for x in msgs if x.get("role") == "system")
    user = "\n".join(str(x.get("content", "")) for x in msgs if x.get("role") == "user")
    fm = re.search(r"Extract these fields from the user's text: (\{.*?\})\. Reply", system, re.S)
    if fm:
        fields = json.loads(fm.group(1))
        values, conf = {}, {}
        for k in fields:
            label = AI_LABELS.get(k, k.replace("_", " "))
            mv = re.search(r"^\s*" + re.escape(label) + r"[^:\n]*:\s*(.+?)\s*$", user, re.I | re.M)
            v = mv.group(1) if mv and mv.group(1) not in ("-", "") else None
            values[k] = v
            conf[k] = 0.0 if v is None else (0.62 if re.search(r"\d\.\d{3},\d{2}$|^\d+,\d{2}$", v) else 0.96)
        answer = {"values": values, "confidence": conf} if "confidence" in system else values
        content = json.dumps(answer)
    else:
        content = "OOA-QA scripted model: no scripted answer for this prompt."
    return h.send(200, {"id": "chatcmpl-qa", "object": "chat.completion", "model": b.get("model", "qa-scripted"),
                        "choices": [{"index": 0, "message": {"role": "assistant", "content": content}, "finish_reason": "stop"}],
                        "usage": {"prompt_tokens": len(system + user) // 4, "completion_tokens": len(content) // 4}})


def soap(h: H, m, path, q):
    """ERP price service (SOAP 1.1) for RW03-B3: the agreed unit price of a supplier's SKU."""
    if path != "/soap/erp" or m != "POST":
        raise KeyError(path)
    xml = h.raw().decode("utf-8", "replace")
    sup = re.search(r"<(?:\w+:)?supplier>([^<]*)<", xml)
    sku = re.search(r"<(?:\w+:)?sku>([^<]*)<", xml)
    price = STATE.get("erp", {}).get("prices", {}).get("%s|%s" % (sup.group(1) if sup else "", sku.group(1) if sku else ""))
    if price is None:
        body = ('<soap:Fault><faultcode>soap:Client</faultcode><faultstring>QA ERP: no agreed price for %s %s</faultstring></soap:Fault>'
                % (sup.group(1) if sup else "?", sku.group(1) if sku else "?"))
        status = 500
    else:
        body = ('<GetPriceResponse xmlns="urn:ooa-qa:erp"><sku>%s</sku><price>%s</price><currency>%s</currency></GetPriceResponse>'
                % (sku.group(1), price[0], price[1]))
        status = 200
    env = '<?xml version="1.0" encoding="utf-8"?><soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"><soap:Body>%s</soap:Body></soap:Envelope>' % body
    return h.send(status, env, ctype="text/xml")


SERVICES = ["servicenow", "salesforce", "jira", "graph", "workday", "approvals", "sap", "ai", "soap", "shopify", "carrier", "kafka"]
ROUTES = [
    ("/qa/", qa, None), ("/kb/", kb, None),
    ("/api/now/", servicenow, "servicenow"), ("/services/", salesforce, "salesforce"), ("/rest/api/", jira, "jira"),
    ("/graph/v1.0/", graph, "graph"), ("/ccx/", workday, "workday"), ("/approvals/", approvals, "approvals"), ("/sap/", sap, "sap"), ("/v1/", ai, "ai"), ("/soap/", soap, "soap"), ("/admin/api/", shopify, "shopify"), ("/carrier/", carrier, "carrier"), ("/kafka/", kafka, "kafka"),
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
