"""A Databricks workspace for the "UT Databricks" agent, at /cloud-dbx (the workspace url is $standin_url$/cloud-dbx).

SQL statements (warehouse "UT warehouse"): SQL that names ut_invoices returns made-up invoice rows in two result chunks
(a :min_amount parameter filters them); SQL containing SLOW stays RUNNING until cancelled. Jobs: "UT nightly load" runs
and ends SUCCESS at once; "UT long job" keeps RUNNING until cancelled. Clusters start and stop at once. Files: the Files
API (/Volumes/...) and DBFS (dbfs:/...), kept in memory. OAuth for a service principal: /oidc/v1/token.
"""
import base64
import json
import re
import uuid
from decimal import Decimal

P = "/cloud-dbx"
WAREHOUSE = {"id": "0123456789abcdef", "name": "UT warehouse"}
INVOICES = [[1, "Acme GmbH", "120.50"], [2, "Globex Ltd", "75.00"], [3, "Initech", "310.25"]]
SEED = {"cloud_dbx": {
    "jobs": {"101": {"name": "UT nightly load", "long": False}, "102": {"name": "UT long job", "long": True}},
    "clusters": {"1123-090000-ut1": {"name": "UT shared cluster", "state": "TERMINATED"}},
    "statements": {}, "runs": {}, "files": {}, "handles": {}, "next_run": 5001}}


def handle(h, m, path, q):
    if not path.startswith(P + "/"):
        raise KeyError(path)
    p = path[len(P):]
    st = core.STATE.setdefault("cloud_dbx", json.loads(json.dumps(SEED["cloud_dbx"])))
    if p == "/oidc/v1/token" and m == "POST":
        if not h.headers.get("Authorization", "").startswith("Basic "):
            return h.send(401, {"error": "invalid_client"})
        return h.send(200, {"access_token": "ut-dbx-oauth-" + uuid.uuid4().hex[:8], "token_type": "Bearer", "expires_in": 3600})
    if not h.headers.get("Authorization", "").startswith("Bearer "):
        return h.send(401, {"error_code": "UNAUTHENTICATED", "message": "No token"})
    with core.LOCK:
        if p == "/api/2.0/sql/warehouses":
            return h.send(200, {"warehouses": [WAREHOUSE]})
        if p == "/api/2.0/sql/statements" and m == "POST":
            b = h.body()
            if b.get("warehouse_id") != WAREHOUSE["id"]:
                return h.send(404, {"error_code": "RESOURCE_DOES_NOT_EXIST", "message": "No warehouse " + str(b.get("warehouse_id"))})
            sid = "01ef-" + uuid.uuid4().hex[:12]
            sql = b.get("statement", "")
            rec = {"sql": sql, "state": "RUNNING" if "SLOW" in sql else "SUCCEEDED"}
            if "ut_invoices" in sql.lower():
                rows = INVOICES
                for prm in b.get("parameters") or []:
                    if prm.get("name") == "min_amount":
                        rows = [r for r in rows if Decimal(r[2]) >= Decimal(str(prm.get("value")))]
                rec["rows"] = rows
            elif "FAIL" in sql:
                rec["state"] = "FAILED"
            st["statements"][sid] = rec
            return h.send(200, statement(sid, rec))
        ms = re.match(r"/api/2.0/sql/statements/([^/]+)(/cancel|/result/chunks/(\d+))?$", p)
        if ms:
            rec = st["statements"].get(ms.group(1))
            if rec is None:
                return h.send(404, {"error_code": "NOT_FOUND", "message": "No statement " + ms.group(1)})
            if ms.group(2) == "/cancel":
                rec["state"] = "CANCELED"
                return h.send(200, {})
            if ms.group(3):
                return h.send(200, {"chunk_index": 1, "data_array": [[str(x) for x in r] for r in rec.get("rows", [])[2:]]})
            return h.send(200, statement(ms.group(1), rec))
        if p == "/api/2.1/jobs/list":
            jobs = [{"job_id": int(k), "settings": {"name": v["name"]}, "creator_user_name": "ut.bot@example.test", "created_time": 1763000000000}
                    for k, v in st["jobs"].items() if not q.get("name") or q["name"].lower() == v["name"].lower()]
            return h.send(200, {"jobs": jobs, "has_more": False})
        if p == "/api/2.1/jobs/run-now" and m == "POST":
            b = h.body()
            job = st["jobs"].get(str(b.get("job_id")))
            if job is None:
                return h.send(400, {"error_code": "INVALID_PARAMETER_VALUE", "message": "Job %s does not exist." % b.get("job_id")})
            rid = str(st["next_run"])
            st["next_run"] += 1
            st["runs"][rid] = {"job": str(b.get("job_id")), "state": "RUNNING" if job["long"] else "TERMINATED",
                               "result": None if job["long"] else "SUCCESS", "params": b.get("job_parameters") or {}}
            return h.send(200, {"run_id": int(rid), "number_in_job": 1})
        if p in ("/api/2.1/jobs/runs/get", "/api/2.1/jobs/runs/get-output"):
            r = st["runs"].get(q.get("run_id", ""))
            if r is None:
                return h.send(404, {"error_code": "RESOURCE_DOES_NOT_EXIST", "message": "Run %s does not exist." % q.get("run_id")})
            summary = {"run_id": int(q["run_id"]), "run_page_url": "https://ut.databricks.test/#job/%s/run/%s" % (r["job"], q["run_id"]),
                       "start_time": 1763900000000, "state": {"life_cycle_state": r["state"], "state_message": ""}}
            if r["result"]:
                summary["state"]["result_state"] = r["result"]
            if p.endswith("get-output"):
                out = {"metadata": summary, "logs": "UT load started\nUT load done"}
                if r["result"] == "SUCCESS":
                    out["notebook_output"] = {"result": "loaded 42 rows for " + str(r["params"].get("run_date", "today"))}
                return h.send(200, out)
            return h.send(200, summary)
        if p == "/api/2.1/jobs/runs/cancel" and m == "POST":
            r = st["runs"].get(str(h.body().get("run_id")))
            if r is None:
                return h.send(404, {"error_code": "RESOURCE_DOES_NOT_EXIST", "message": "No such run"})
            r["state"], r["result"] = "TERMINATED", "CANCELED"
            return h.send(200, {})
        if p == "/api/2.0/clusters/list":
            return h.send(200, {"clusters": [{"cluster_id": k, "cluster_name": v["name"], "state": v["state"], "spark_version": "15.4.x-scala2.12",
                                              "node_type_id": "Standard_DS3_v2", "creator_user_name": "ut.bot@example.test"} for k, v in st["clusters"].items()]})
        if p == "/api/2.0/clusters/get":
            c = st["clusters"].get(q.get("cluster_id", ""))
            return h.send(200, {"cluster_id": q.get("cluster_id"), "state": c["state"]}) if c else h.send(400, {"error_code": "INVALID_PARAMETER_VALUE", "message": "No cluster"})
        if p in ("/api/2.0/clusters/start", "/api/2.0/clusters/delete") and m == "POST":
            c = st["clusters"].get(h.body().get("cluster_id", ""))
            if c is None:
                return h.send(400, {"error_code": "INVALID_PARAMETER_VALUE", "message": "No cluster"})
            c["state"] = "RUNNING" if p.endswith("start") else "TERMINATED"
            return h.send(200, {})
        files = st["files"]
        mf = re.match(r"/api/2.0/fs/files(/.+)$", p)
        if mf:
            name = mf.group(1)
            if m == "PUT":
                if name in files and q.get("overwrite") == "false":
                    return h.send(409, {"error_code": "ALREADY_EXISTS", "message": "The file already exists"})
                files[name] = base64.b64encode(h.raw()).decode()
                return h.send(204)
            if name not in files:
                return h.send(404, {"error_code": "NOT_FOUND", "message": "No file " + name})
            if m == "DELETE":
                del files[name]
                return h.send(204)
            data = base64.b64decode(files[name])
            h.send_response(200)
            h.send_header("Content-Type", "application/octet-stream")
            h.send_header("Content-Length", str(len(data)))
            h.end_headers()
            h.wfile.write(data)
            return
        if p == "/api/2.0/dbfs/create" and m == "POST":
            b = h.body()
            hid = len(st["handles"]) + 1
            st["handles"][str(hid)] = {"path": b.get("path"), "data": ""}
            return h.send(200, {"handle": hid})
        if p == "/api/2.0/dbfs/add-block" and m == "POST":
            b = h.body()
            hd = st["handles"][str(b.get("handle"))]
            hd["data"] = base64.b64encode(base64.b64decode(hd["data"]) + base64.b64decode(b.get("data", ""))).decode()
            return h.send(200, {})
        if p == "/api/2.0/dbfs/close" and m == "POST":
            hd = st["handles"].pop(str(h.body().get("handle")))
            files["dbfs:" + hd["path"]] = hd["data"]
            return h.send(200, {})
        if p == "/api/2.0/dbfs/read":
            key = "dbfs:" + q.get("path", "")
            if key not in files:
                return h.send(404, {"error_code": "RESOURCE_DOES_NOT_EXIST", "message": "No file " + q.get("path", "")})
            data = base64.b64decode(files[key])[int(q.get("offset", 0)):int(q.get("offset", 0)) + int(q.get("length", 1048576))]
            return h.send(200, {"bytes_read": len(data), "data": base64.b64encode(data).decode()})
        if p == "/api/2.0/dbfs/delete" and m == "POST":
            b = h.body()
            key = "dbfs:" + b.get("path", "")
            gone = [k for k in files if k == key or (b.get("recursive") and k.startswith(key.rstrip("/") + "/"))]
            for k in gone:
                del files[k]
            return h.send(200, {})
    raise KeyError(path)


def statement(sid, rec):
    out = {"statement_id": sid, "status": {"state": rec["state"]}}
    if rec["state"] == "FAILED":
        out["status"]["error"] = {"message": "UT stand-in: syntax error near FAIL"}
    if rec["state"] == "SUCCEEDED":
        rows = rec.get("rows")
        if rows is None:
            out["manifest"] = {"schema": {"columns": [{"name": "ok", "type_name": "BOOLEAN"}]}, "total_row_count": 1}
            out["result"] = {"chunk_index": 0, "data_array": [["true"]]}
        else:
            out["manifest"] = {"schema": {"columns": [{"name": "id", "type_name": "INT"}, {"name": "customer", "type_name": "STRING"},
                                                      {"name": "amount", "type_name": "DECIMAL"}]}, "total_row_count": len(rows)}
            out["result"] = {"chunk_index": 0, "data_array": [[str(x) for x in r] for r in rows[:2]]}
            if len(rows) > 2:
                out["result"]["next_chunk_internal_link"] = "/api/2.0/sql/statements/%s/result/chunks/1" % sid
    return out


ROUTES = [(P + "/", handle, "cloud_dbx")]
