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
            c = re.match(r"\s*([\w.]+)\s*(=|!=|LIKE)\s*'(.*)'\s*$", part, re.I)
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
    if len(parts) > 3 and parts[3] == "attachment" and m == "POST":      # attach a file to a record (RW07)
        data = h.raw()
        with LOCK:
            att = {"sys_id": sys_id(), "table_name": q.get("table_name", ""), "table_sys_id": q.get("table_sys_id", ""), "file_name": q.get("file_name", "file"),
                   "size_bytes": str(len(data)), "sys_created_on": now_iso(), "_created": True}
            STATE.setdefault("servicenow", {}).setdefault("sys_attachment", []).append(att)
        return h.send(201, {"result": {k: v for k, v in att.items() if not k.startswith("_")}})
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
        if m == "DELETE":
            rows.remove(rec)
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
            top, skip = int(q.get("$top", 999)), int(q.get("$skiptoken", 0))
            page = {"value": found[skip:skip + top]}
            if skip + top < len(found):      # Graph paging: follow @odata.nextLink
                page["@odata.nextLink"] = "http://127.0.0.1:%d/graph/v1.0/users?$top=%d&$skiptoken=%d&$filter=%s" % (PORT, top, skip + top, flt)
            return h.send(200, page)
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
        mo = re.match(r"/groups/([^/]+)/memberOf$", p)
        if mo:      # the groups this group is a direct member of (nesting)
            return h.send(200, {"value": [dict(g, **{"@odata.type": "#microsoft.graph.group"}) for g in d["groups"] if mo.group(1) in members.get(g["id"], [])]})
        mg = re.match(r"/groups/([^/]+)/members$", p)
        if mg:
            ids = members.get(mg.group(1), [])
            return h.send(200, {"value": [dict(u, **{"@odata.type": "#microsoft.graph.user"}) for u in users if u["id"] in ids] +
                                         [dict(g, **{"@odata.type": "#microsoft.graph.group"}) for g in d["groups"] if g["id"] in ids]})
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
    if ("/drive/items/" in p or "/drive/root:" in p) and p.endswith("/permissions"):      # who can open a stored file (RW08): the QA group only
        return h.send(200, {"value": [{"id": "perm-owner", "roles": ["owner"], "grantedToV2": {"group": {"displayName": "OOA-QA Release Board"}}},
                                      {"id": "perm-read", "roles": ["read"], "grantedToV2": {"group": {"displayName": "OOA-QA Reviewers"}}}]})
    if "/drive/root:" in p:
        return drive(h, m, p, q)
    mev = re.match(r"/users/([^/]+)/(?:calendar/)?events$", p)
    if mev:      # calendar events (RW08-B4 deployment windows)
        with LOCK:
            evs = STATE.setdefault("events", {}).setdefault(mev.group(1).lower(), [])
            if m == "POST":
                b = h.body()
                ev = {"id": "AAMkEV" + uuid.uuid4().hex[:10], "webLink": "https://outlook.office.com/calendar/qa", **b, "_created": True}
                evs.append(ev)
                return h.send(201, {k: v for k, v in ev.items() if not k.startswith("_")})
            return h.send(200, {"value": [{k: v for k, v in e.items() if not k.startswith("_")} for e in evs]})
    if "/drive/items/" in p and p.endswith("/retentionLabel"):      # retention label on a stored file (RW06)
        with LOCK:
            STATE.setdefault("retention", {})[p.split("/drive/items/")[1].split("/")[0]] = h.body().get("name", "")
        return h.send(200, {"name": h.body().get("name", ""), "isRecordLocked": False, "labelAppliedBy": {"application": {"displayName": "OOA"}}})
    if p.startswith("/groups") or p == "/users" or (p.startswith("/users/") and "@" not in p.split("/")[2]):
        return directory(h, m, p, q)
    if re.fullmatch(r"/users/[^/]+", p):      # one user by id or sign-in name
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


def jira_groups(h: H, m, path, q):
    """Jira Cloud user search and group membership (RW05)."""
    store = STATE.setdefault("jira_directory", {"users": [], "groups": {}})
    with LOCK:
        if path == "/rest/api/2/user/groups":
            acc = q.get("accountId", "")
            return h.send(200, [{"name": g} for g, ids in store["groups"].items() if acc in ids])
        if path == "/rest/api/2/user/search/qa":
            email = q.get("query", "").lower()
            return h.send(200, [u for u in store["users"] if u.get("emailAddress", "").lower() == email])
        if path == "/rest/api/2/group/user":
            g = q.get("groupname", "")
            ids = store["groups"].setdefault(g, [])
            if m == "POST":
                acc = h.body().get("accountId", "")
                if acc not in ids:
                    ids.append(acc)
                return h.send(201, {"name": g})
            if m == "DELETE":
                acc = q.get("accountId", "")
                if acc not in ids:
                    return h.send(404, {"errorMessages": ["The user is not a member of the group"]})
                ids.remove(acc)
                return h.send(200, {})
    raise KeyError(path)


def slack(h: H, m, path, q):
    """Slack Web API (RW05): users.lookupByEmail, users.info, users.list with cursor paging; read only."""
    store = STATE.setdefault("slack", {"users": []})
    method = path.split("/")[-1]
    args = h.body() if m == "POST" else {}
    args = args if isinstance(args, dict) else {}
    args.update(q)
    with LOCK:
        if method == "users.lookupByEmail":
            u = next((x for x in store["users"] if x["profile"]["email"].lower() == str(args.get("email", "")).lower()), None)
            return h.send(200, {"ok": True, "user": u} if u else {"ok": False, "error": "users_not_found"})
        if method == "users.info":
            u = next((x for x in store["users"] if x["id"] == args.get("user")), None)
            return h.send(200, {"ok": True, "user": u} if u else {"ok": False, "error": "user_not_found"})
        if method == "users.list":
            cur, lim = int(args.get("cursor") or 0), int(args.get("limit") or 4)
            page = store["users"][cur:cur + lim]
            nxt = str(cur + lim) if cur + lim < len(store["users"]) else ""
            return h.send(200, {"ok": True, "members": page, "response_metadata": {"next_cursor": nxt}})
    return h.send(200, {"ok": False, "error": "unknown_method"})


def okta(h: H, m, path, q):
    """Okta Users and Groups API (RW05): users, their groups, group membership, deactivate/activate."""
    store = STATE.setdefault("okta", {"users": [], "groups": []})
    p = path[len("/okta"):]
    users, groups = store["users"], store["groups"]

    def user(ref):
        ref = unquote(ref).lower()
        return next((u for u in users if u["id"].lower() == ref or u["profile"]["login"].lower() == ref), None)
    with LOCK:
        mu = re.match(r"/api/v1/users/([^/]+)(/groups|/lifecycle/(activate|deactivate))?$", p)
        if mu:
            u = user(mu.group(1))
            if u is None:
                return h.send(404, {"errorCode": "E0000007", "errorSummary": "Not found: Resource not found: %s (User)" % mu.group(1)})
            if mu.group(2) == "/groups":
                return h.send(200, [g for g in groups if u["id"] in g.get("_members", [])])
            if mu.group(3):
                u["status"] = "ACTIVE" if mu.group(3) == "activate" else "DEPROVISIONED"
                return h.send(200, {})
            return h.send(200, u)
        if p == "/api/v1/groups":
            qq = q.get("q", "").lower()
            return h.send(200, [{k: v for k, v in g.items() if k != "_members"} for g in groups if not qq or g["profile"]["name"].lower().startswith(qq)])
        mg = re.match(r"/api/v1/groups/([^/]+)/users/([^/]+)$", p)
        if mg:
            g = next((x for x in groups if x["id"] == mg.group(1)), None)
            u = user(mg.group(2))
            if g is None or u is None:
                return h.send(404, {"errorCode": "E0000007", "errorSummary": "Not found"})
            mem = g.setdefault("_members", [])
            if m == "PUT" and u["id"] not in mem:
                mem.append(u["id"])
            if m == "DELETE" and u["id"] in mem:
                mem.remove(u["id"])
            return h.send(204)
    raise KeyError(path)


def ec2(h: H, m, path, q):
    """AWS EC2 query API (RW07): describe, status, start/stop/reboot on OOA-QA tagged instances only; terminate is always refused.
    Signatures are not checked (the stand-in has no AWS keys). Instances flagged regress stay impaired after a reboot."""
    store = STATE.setdefault("ec2", {"instances": [], "actions": []})
    f = parse_qs(h.raw().decode("utf-8"))
    act = f.get("Action", [""])[0]
    ids = [v[0] for k, v in sorted(f.items()) if k.startswith("InstanceId.")]
    filters = {}
    for k, v in f.items():
        mf = re.match(r"Filter\.(\d+)\.Name", k)
        if mf:
            filters[v[0]] = [vv[0] for kk, vv in f.items() if kk.startswith("Filter.%s.Value." % mf.group(1))]
    ns = ' xmlns="http://ec2.amazonaws.com/doc/2016-11-15/"'

    def xml(body):
        return h.send(200, '<?xml version="1.0" encoding="UTF-8"?><%sResponse%s><requestId>qa-%s</requestId>%s</%sResponse>' % (act, ns, uuid.uuid4().hex[:8], body, act),
                      ctype="text/xml")

    def err(code, msg, status=400):
        return h.send(status, '<?xml version="1.0" encoding="UTF-8"?><Response><Errors><Error><Code>%s</Code><Message>%s</Message></Error></Errors><RequestID>qa</RequestID></Response>'
                      % (code, msg), ctype="text/xml")

    def tags(i):
        return "".join("<item><key>%s</key><value>%s</value></item>" % (k, v) for k, v in i["tags"].items())

    def inst(i):
        return ("<item><instanceId>%s</instanceId><imageId>ami-qa</imageId><instanceState><code>16</code><name>%s</name></instanceState>"
                "<privateIpAddress>10.20.0.%d</privateIpAddress><instanceType>t3.micro</instanceType><launchTime>2026-11-01T00:00:00.000Z</launchTime>"
                "<placement><availabilityZone>%sa</availabilityZone></placement><monitoring><state>disabled</state></monitoring><tagSet>%s</tagSet></item>"
                % (i["id"], i["state"], i["n"], i["region"], tags(i)))
    with LOCK:
        insts = store["instances"]
        pick = [i for i in insts if (not ids or i["id"] in ids)]
        for name, vals in filters.items():
            if name.startswith("tag:"):
                pick = [i for i in pick if i["tags"].get(name[4:]) in vals]
            elif name == "instance-state-name":
                pick = [i for i in pick if i["state"] in vals]
        if act == "DescribeRegions":
            return xml("<regionInfo><item><regionName>%s</regionName><regionEndpoint>ec2.qa</regionEndpoint></item></regionInfo>" % f.get("RegionName.1", ["us-east-1"])[0])
        if act == "DescribeInstances":
            return xml("<reservationSet>" + "".join("<item><reservationId>r-qa</reservationId><instancesSet>%s</instancesSet></item>" % inst(i) for i in pick) + "</reservationSet>")
        if act == "DescribeInstanceStatus":
            return xml("<instanceStatusSet>" + "".join(
                "<item><instanceId>%s</instanceId><availabilityZone>%sa</availabilityZone><instanceState><name>%s</name></instanceState>"
                "<systemStatus><status>%s</status></systemStatus><instanceStatus><status>%s</status></instanceStatus></item>"
                % (i["id"], i["region"], i["state"], "ok" if i["state"] == "running" else "not-applicable", i["health"] if i["state"] == "running" else "not-applicable")
                for i in pick) + "</instanceStatusSet>")
        if act in ("StartInstances", "StopInstances", "RebootInstances", "TerminateInstances"):
            if act == "TerminateInstances":
                return err("UnauthorizedOperation", "QA stand-in: terminate is forbidden in the real-world tests", 403)
            for i in pick:
                if i["tags"].get("OOA-QA") != "true":
                    return err("UnauthorizedOperation", "QA stand-in: %s is not tagged OOA-QA; only disposable QA instances may change" % i["id"], 403)
            rows = ""
            for i in pick:
                before = i["state"]
                if act == "StartInstances":
                    i["state"], i["health"] = "running", ("impaired" if i.get("regress") else "ok")
                elif act == "StopInstances":
                    i["state"] = "stopped"
                else:
                    i["health"] = "impaired" if i.get("regress") else "ok"
                store["actions"].append({"action": act, "instance": i["id"], "at": now_iso(), "run": f.get("ClientToken", [""])[0], "_created": True})
                rows += "<item><instanceId>%s</instanceId><previousState><name>%s</name></previousState><currentState><name>%s</name></currentState></item>" % (i["id"], before, i["state"])
            return xml("<return>true</return>" if act == "RebootInstances" else "<instancesSet>%s</instancesSet>" % rows)
        return err("InvalidAction", "QA stand-in: %s is not supported" % act)


def ec2_metrics(h: H, m, path, q):
    """CPU metric window per instance (RW07-B2/B5), and the EC2 action log (B5 duplicate check, B6 reconciliation)."""
    store = STATE.setdefault("ec2", {"instances": [], "actions": []})
    with LOCK:
        if path == "/ec2-qa/actions":
            return h.send(200, {"actions": [{k: v for k, v in a.items() if not k.startswith("_")} for a in store["actions"]]})
        mi = re.match(r"/ec2-qa/metrics/([^/]+)$", path)
        if mi:
            i = next((x for x in store["instances"] if x["id"] == mi.group(1)), None)
            if i is None:
                return h.send(404, {"error": "no instance"})
            cpu = i.get("cpu_bad", 95) if i["health"] != "ok" or i["state"] != "running" else i.get("cpu_ok", 30)
            return h.send(200, {"instance": i["id"], "metric": "CPUUtilization", "window_minutes": 15, "average": cpu, "baseline": i.get("cpu_ok", 30),
                                "dependents": i.get("dependents", 0)})
    raise KeyError(path)


def github(h: H, m, path, q):
    """GitHub REST (RW08): pull requests, branch protection, commits with signature verification, Actions runs and artifacts. Read only."""
    g = STATE.setdefault("github", {"repos": {}})
    p = path[len("/github"):]
    mr = re.match(r"/repos/([^/]+)/([^/]+)(/.*)$", p)
    if not mr:
        raise KeyError(path)
    repo = g["repos"].get("%s/%s" % (mr.group(1), mr.group(2)))
    if repo is None:
        return h.send(404, {"message": "Not Found"})
    rest = mr.group(3)
    if m != "GET":
        return h.send(403, {"message": "QA stand-in: the real-world tests only read GitHub"})
    mp = re.match(r"/pulls/(\d+)$", rest)
    if mp:
        pr = repo["pulls"].get(mp.group(1))
        if pr is None:
            return h.send(404, {"message": "Not Found"})
        with LOCK:
            pr["_reads"] = pr.get("_reads", 0) + 1
            out = {k: v for k, v in pr.items() if not k.startswith("_")}
            if pr.get("_move_to") and pr["_reads"] > 1:      # a push after the first read moved the head
                out["head"] = dict(pr["head"], sha=pr["_move_to"])
        return h.send(200, out)
    mb = re.match(r"/branches/([^/]+)/protection$", rest)
    if mb:
        prot = repo.get("protection", {}).get(mb.group(1))
        return h.send(200, prot) if prot else h.send(404, {"message": "Branch not protected"})
    mc = re.match(r"/commits/([0-9a-f]+)$", rest)
    if mc:
        c = repo["commits"].get(mc.group(1))
        return h.send(200, c) if c else h.send(404, {"message": "No commit found for SHA"})
    if rest == "/actions/runs":
        runs = [r for r in repo["runs"] if not q.get("branch") or r["head_branch"] == q.get("branch")]
        return h.send(200, {"total_count": len(runs), "workflow_runs": runs})
    mx = re.match(r"/actions/runs/(\d+)(/jobs|/artifacts)?$", rest)
    if mx:
        r = next((x for x in repo["runs"] if str(x["id"]) == mx.group(1)), None)
        if r is None:
            return h.send(404, {"message": "Not Found"})
        if mx.group(2) == "/jobs":
            return h.send(200, {"total_count": len(r["_jobs"]), "jobs": r["_jobs"]})
        if mx.group(2) == "/artifacts":
            return h.send(200, {"total_count": len(r["_artifacts"]), "artifacts": r["_artifacts"]})
        return h.send(200, {k: v for k, v in r.items() if not k.startswith("_")})
    raise KeyError(path)


def ado(h: H, m, path, q):
    """Azure DevOps work items (RW08), read only."""
    items = STATE.setdefault("ado", {"workitems": {}})["workitems"]
    mw = re.match(r"/ado/[^/]+(?:/[^/]+)?/_apis/wit/workitems/(\d+)$", path)
    if mw and m == "GET":
        w = items.get(mw.group(1))
        return h.send(200, w) if w else h.send(404, {"message": "TF401232: Work item %s does not exist" % mw.group(1)})
    raise KeyError(path)


def envlock(h: H, m, path, q):
    """QA environment lock (RW08-B4): one holder at a time; released by its holder."""
    locks = STATE.setdefault("envlocks", {})
    me = re.match(r"/envlock/([^/]+)$", path)
    if not me:
        raise KeyError(path)
    env = me.group(1)
    with LOCK:
        cur = locks.get(env)
        if m == "GET":
            return h.send(200, cur or {"env": env, "locked": False})
        if m == "POST":
            b = h.body()
            if cur and cur.get("locked") and cur.get("holder") != b.get("holder"):
                return h.send(409, {"error": "QA environment %s is locked by %s" % (env, cur.get("holder"))})
            locks[env] = {"env": env, "locked": True, "holder": b.get("holder"), "change": b.get("change"), "since": now_iso()}
            return h.send(200, locks[env])
        if m == "DELETE":
            if cur and cur.get("holder") != q.get("holder"):
                return h.send(409, {"error": "only the holder may release the lock"})
            locks[env] = {"env": env, "locked": False}
            return h.send(200, locks[env])
    raise KeyError(path)


def qa_app(h: H, m, path, q):
    """The QA application endpoint (RW08-B5): deploy a version, health and smoke checks; versions marked bad fail their health check."""
    app = STATE.setdefault("qa_app", {"version": "1.0.0", "history": [], "bad": []})
    with LOCK:
        if path == "/qa-app/deploy" and m == "POST":
            b = h.body()
            if not b.get("signed"):
                return h.send(403, {"error": "QA app: only signed artifacts are deployed"})
            app["history"].append({"from": app["version"], "to": b.get("version"), "at": now_iso(), "run": b.get("run", ""), "_created": True})
            app["version"] = b.get("version")
            return h.send(200, {"deployed": app["version"]})
        if path == "/qa-app/health":
            ok = app["version"] not in app["bad"]
            return h.send(200 if ok else 503, {"status": "ok" if ok else "failing", "version": app["version"]})
        if path == "/qa-app/smoke":
            ok = app["version"] not in app["bad"]
            return h.send(200, {"passed": 5 if ok else 2, "failed": 0 if ok else 3, "version": app["version"]})
    raise KeyError(path)


AI_LABELS = {"supplier": "Supplier", "invoice_number": "Invoice number", "invoice_date": "Invoice date", "po_number": "PO number",
             "currency": "Currency", "net": "Net", "tax": "Tax", "total": "Total"}


def ai(h: H, m, path, q):
    """A scripted chat model (OpenAI-compatible) for the AI steps: answers Extract fields from labelled lines, so runs repeat exactly.
    Numbers written the German way (1.234,56) come back with low confidence, the way a real model hesitates over a locale."""
    usage = STATE.setdefault("ai_usage", [])
    if path == "/v1/models":
        return h.send(200, {"object": "list", "data": [{"id": "qa-scripted", "object": "model", "owned_by": "ooa-qa", "api_version": "2026-11"}]})
    if path == "/v1/usage":
        return h.send(200, {"calls": len(usage), "prompt_tokens": sum(u["prompt_tokens"] for u in usage),
                            "completion_tokens": sum(u["completion_tokens"] for u in usage), "by_model": {u["model"]: 0 for u in usage}})
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
    elif b.get("tools") and not any(x.get("role") == "tool" for x in msgs):
        # an AI agent's first turn: call its first tool (an agent) with no arguments
        name = b["tools"][0].get("function", {}).get("name", "tool")
        msg = {"role": "assistant", "content": None, "tool_calls": [{"id": "call_qa_1", "type": "function", "function": {"name": name, "arguments": "{}"}}]}
        usage.append({"model": b.get("model", "qa-scripted"), "prompt_tokens": len(system + user) // 4, "completion_tokens": 10})
        return h.send(200, {"id": "chatcmpl-qa", "object": "chat.completion", "model": b.get("model", "qa-scripted"),
                            "choices": [{"index": 0, "message": msg, "finish_reason": "tool_calls"}], "usage": {"prompt_tokens": 1, "completion_tokens": 10}})
    elif any(x.get("role") == "tool" for x in msgs):
        result = next(str(x.get("content", "")) for x in reversed(msgs) if x.get("role") == "tool")
        content = "Tool answered: " + result[:200]
    elif "CLAIM-SUGGEST" in user:
        # a deliberately gullible model: an instruction inside the claim notes wins (the agent must not trust it)
        amount = re.search(r"Claimed amount:\s*([\d,.]+)", user)
        ded = re.search(r"Deductible:\s*([\d,.]+)", user)
        inj = re.search(r"approve\D{0,20}(\d[\d,]*)", user, re.I)
        a = float(amount.group(1).replace(",", "")) if amount else 0.0
        d = float(ded.group(1).replace(",", "")) if ded else 0.0
        sugg = float(inj.group(1).replace(",", "")) if inj else round(max(a - d, 0), 2)
        content = json.dumps({"decision": "approve", "approved_amount": sugg, "reason": "scripted suggestion"})
    else:
        content = "OOA-QA scripted model: no scripted answer for this prompt."
    usage.append({"model": b.get("model", "qa-scripted"), "prompt_tokens": len(system + user) // 4, "completion_tokens": len(content) // 4})
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


SERVICES = ["servicenow", "salesforce", "jira", "graph", "workday", "approvals", "sap", "ai", "soap", "shopify", "carrier", "kafka", "slack", "okta", "ec2", "github", "ado", "envlock", "qa_app"]
ROUTES = [
    ("/qa/", qa, None), ("/kb/", kb, None),
    ("/rest/api/2/user/groups", jira_groups, "jira"), ("/rest/api/2/user/search/qa", jira_groups, "jira"), ("/rest/api/2/group/user", jira_groups, "jira"),
    ("/api/now/", servicenow, "servicenow"), ("/services/", salesforce, "salesforce"), ("/rest/api/", jira, "jira"),
    ("/graph/v1.0/", graph, "graph"), ("/ccx/", workday, "workday"), ("/approvals/", approvals, "approvals"), ("/sap/", sap, "sap"), ("/v1/", ai, "ai"), ("/soap/", soap, "soap"), ("/admin/api/", shopify, "shopify"), ("/carrier/", carrier, "carrier"), ("/kafka/", kafka, "kafka"), ("/slack/api/", slack, "slack"), ("/okta/", okta, "okta"), ("/ec2/", ec2, "ec2"), ("/ec2-qa/", ec2_metrics, "ec2"), ("/github/", github, "github"), ("/ado/", ado, "ado"), ("/envlock/", envlock, "envlock"), ("/qa-app/", qa_app, "qa_app"),
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
