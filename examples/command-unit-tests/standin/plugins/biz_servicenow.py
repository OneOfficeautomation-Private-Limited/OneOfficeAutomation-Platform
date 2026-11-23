"""ServiceNow for the command unit tests, under /biz/servicenow: OAuth token and revoke, Table API (latest, v1, v2) with
encoded queries and display values, comments and work notes, Attachment API. Made-up records only."""
import re
import time
import uuid

P = "/biz/servicenow"

SEED = {"biz_servicenow": {
    "revoked": [],
    "tables": {
        "incident": [
            {"sys_id": "5a000000000000000000000000000001", "number": "INC0100001", "short_description": "UT printer offline", "priority": "1", "active": "true",
             "caller_id": {"value": "6b000000000000000000000000000001", "display_value": "Asha Patel"}, "assignment_group": "Service Desk",
             "sys_created_on": "2026-11-01 09:00:00", "sys_updated_on": "2026-11-01 09:00:00"},
            {"sys_id": "5a000000000000000000000000000002", "number": "INC0100002", "short_description": "UT VPN drops", "priority": "2", "active": "true",
             "caller_id": {"value": "6b000000000000000000000000000002", "display_value": "Bruno Silva"}, "assignment_group": "Network",
             "sys_created_on": "2026-11-02 09:00:00", "sys_updated_on": "2026-11-10 12:30:00"},
            {"sys_id": "5a000000000000000000000000000003", "number": "INC0100003", "short_description": "UT mailbox full", "priority": "3", "active": "false",
             "caller_id": {"value": "6b000000000000000000000000000001", "display_value": "Asha Patel"}, "assignment_group": "Service Desk",
             "sys_created_on": "2026-11-03 09:00:00", "sys_updated_on": "2026-11-12 08:15:00"},
        ],
        "change_request": [],
    },
    "attachments": [
        {"sys_id": "7c000000000000000000000000000001", "table_name": "incident", "table_sys_id": "5a000000000000000000000000000001",
         "file_name": "printer-log.txt", "content_type": "text/plain", "size_bytes": "18", "sys_created_on": "2026-11-01 09:05:00", "_data": "paper jam in tray2"},
    ],
}}


def _st():
    return core.STATE.setdefault("biz_servicenow", {"revoked": [], "tables": {}, "attachments": []})


def _now():
    return time.strftime("%Y-%m-%d %H:%M:%S", time.gmtime())


def _authorized(h):
    a = h.headers.get("Authorization", "")
    ok = (a.startswith("Bearer ") and len(a) > 12 and a[7:] not in _st()["revoked"]) or (a.startswith("Basic ") and len(a) > 12)
    if not ok:
        h.send(401, {"error": {"message": "User Not Authenticated", "detail": "Required to provide Auth information"}, "status": "failure"})
    return ok


def _bytes(h, data, ctype="application/octet-stream"):
    h.send_response(200)
    h.send_header("Content-Type", ctype)
    h.send_header("Content-Length", str(len(data)))
    h.end_headers()
    h.wfile.write(data)


def _plain(v):
    return v["value"] if isinstance(v, dict) and "value" in v else v


def _show(rec, dv, fields):
    out = {}
    for k, v in rec.items():
        if k.startswith("_") or (fields and k not in fields):
            continue
        if isinstance(v, dict) and "value" in v:
            out[k] = v if dv == "all" else v["display_value"] if dv == "true" else v["value"]
        else:
            out[k] = {"value": v, "display_value": v} if dv == "all" else v
    return out


def _matches(rec, query):
    order = None
    for part in [x for x in (query or "").split("^") if x]:
        if part.startswith("ORDERBYDESC"):
            order = (part[11:], True)
            continue
        if part.startswith("ORDERBY"):
            order = (part[7:], False)
            continue
        md = re.match(r"(\w+)>javascript:gs\.dateGenerate\('([\d-]+)','([\d:]+)'\)$", part)
        if md:
            if not str(rec.get(md.group(1), "")) > md.group(2) + " " + md.group(3):
                return False, order
            continue
        mi = re.match(r"(\w+)ISEMPTY$", part)
        if mi:
            if _plain(rec.get(mi.group(1))) not in (None, ""):
                return False, order
            continue
        m = re.match(r"([\w.]+)(!=|=|LIKE|STARTSWITH)(.*)$", part)
        if not m:
            raise ValueError("unsupported encoded query part " + part)
        f, op, v = m.groups()
        have = str(_plain(rec.get(f, "")))
        if (op == "=" and have != v) or (op == "!=" and have == v) or (op == "LIKE" and v.lower() not in have.lower()) or (op == "STARTSWITH" and not have.startswith(v)):
            return False, order
    return True, order


def _select(rows, query):
    found = [r for r in rows if _matches(r, query)[0]]
    order = None
    for part in (query or "").split("^"):
        if part.startswith("ORDERBYDESC"):
            order = (part[11:], True)
        elif part.startswith("ORDERBY"):
            order = (part[7:], False)
    if order:
        found.sort(key=lambda r: str(_plain(r.get(order[0], ""))), reverse=order[1])
    return found


def servicenow(h, m, path, q):
    st = _st()
    p = path[len(P):]
    if p == "/oauth_token.do" and m == "POST":
        b = h.body()
        g = b.get("grant_type")
        if not b.get("client_id") or not b.get("client_secret") or (g == "password" and not (b.get("username") and b.get("password"))):
            return h.send(401, {"error": "access_denied", "error_description": "access_denied"})
        return h.send(200, {"access_token": "ut-sn-" + uuid.uuid4().hex, "refresh_token": "ut-sn-refresh-" + uuid.uuid4().hex[:8],
                            "scope": "useraccount", "token_type": "Bearer", "expires_in": 1799})
    if p == "/oauth_revoke_token.do" and m == "POST":
        b = h.body()
        if not b.get("token") or not b.get("client_id") or not b.get("client_secret"):
            return h.send(400, {"error": "invalid_request"})
        with core.LOCK:
            st["revoked"].append(b["token"])
        return h.send(200, {})
    if not _authorized(h):
        return
    mp = re.match(r"/api/now(?:/v\d+)?(/.*)$", p)
    if not mp:
        raise KeyError(path)
    rest = mp.group(1)
    atts = st.setdefault("attachments", [])
    with core.LOCK:
        if rest == "/attachment/file" and m == "POST":
            data = h.raw()
            rows = st["tables"].get(q.get("table_name"), [])
            if not any(r["sys_id"] == q.get("table_sys_id") for r in rows):
                return h.send(400, {"error": {"message": "Invalid table or record"}, "status": "failure"})
            a = {"sys_id": uuid.uuid4().hex, "table_name": q.get("table_name"), "table_sys_id": q.get("table_sys_id"), "file_name": q.get("file_name"),
                 "content_type": h.headers.get("Content-Type", "application/octet-stream"), "size_bytes": str(len(data)), "sys_created_on": _now(),
                 "_data": data.decode("latin-1")}
            atts.append(a)
            return h.send(201, {"result": {k: v for k, v in a.items() if not k.startswith("_")}})
        if rest == "/attachment" and m == "GET":
            found = _select(atts, q.get("sysparm_query", ""))[:int(q.get("sysparm_limit", 1000))]
            return h.send(200, {"result": [{k: v for k, v in a.items() if not k.startswith("_")} for a in found]})
        ma = re.match(r"/attachment/([0-9a-f]{32})(/file)?$", rest)
        if ma:
            a = next((x for x in atts if x["sys_id"] == ma.group(1)), None)
            if a is None:
                return h.send(404, {"error": {"message": "Record doesn't exist or ACL restricts the record retrieval"}, "status": "failure"})
            if m == "DELETE":
                atts.remove(a)
                return h.send(204)
            if ma.group(2):
                return _bytes(h, a["_data"].encode("latin-1"), a["content_type"])
            return h.send(200, {"result": {k: v for k, v in a.items() if not k.startswith("_")}})
        mt = re.match(r"/table/(\w+)(?:/([0-9a-f]{32}))?$", rest)
        if not mt:
            raise KeyError(path)
        table, sid = mt.groups()
        rows = st["tables"].setdefault(table, [])
        dv = q.get("sysparm_display_value", "false")
        fields = [f for f in q.get("sysparm_fields", "").split(",") if f]
        if m == "GET" and sid is None:
            found = _select(rows, q.get("sysparm_query", ""))
            off, lim = int(q.get("sysparm_offset", 0)), int(q.get("sysparm_limit", 10000))
            return h.send(200, {"result": [_show(r, dv, fields) for r in found[off:off + lim]]})
        if m == "POST" and sid is None:
            rec = dict(h.body())
            if table == "incident" and not rec.get("short_description"):
                return h.send(400, {"error": {"message": "short_description is mandatory"}, "status": "failure"})
            if q.get("sysparm_input_display_value") == "true" and isinstance(rec.get("caller_id"), str):
                rec["caller_id"] = {"value": "6b000000000000000000000000000001" if rec["caller_id"] == "Asha Patel" else "", "display_value": rec["caller_id"]}
            rec.update({"sys_id": uuid.uuid4().hex, "number": "%s%07d" % ({"incident": "INC", "change_request": "CHG"}.get(table, "TASK"), 200000 + len(rows)),
                        "sys_created_on": _now(), "sys_updated_on": _now(), "active": "true"})
            rows.append(rec)
            return h.send(201, {"result": _show(rec, "false", [])})
        rec = next((r for r in rows if r["sys_id"] == sid), None)
        if rec is None:
            return h.send(404, {"error": {"message": "No Record found", "detail": "Record doesn't exist or ACL restricts the record retrieval"}, "status": "failure"})
        if m == "GET":
            return h.send(200, {"result": _show(rec, dv, fields)})
        if m in ("PATCH", "PUT"):
            b = dict(h.body())
            for key in ("comments", "work_notes"):
                if key in b:
                    rec.setdefault("_journal", []).append({"field": key, "text": b[key]})
            rec.update(b)
            rec["sys_updated_on"] = _now()
            return h.send(200, {"result": _show(rec, "false", [])})
        if m == "DELETE":
            rows.remove(rec)
            return h.send(204)
    raise KeyError(path)


ROUTES = [(P + "/", servicenow, "biz_servicenow")]
