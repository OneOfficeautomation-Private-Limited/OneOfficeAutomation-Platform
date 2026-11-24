"""A Snowflake SQL API (/api/v2/statements) for the "UT Snowflake" agent, at /cloud-snowflake (the account url).

One made-up table UT_DB.PUBLIC.ORDERS (ORDER_ID, CUSTOMER, AMOUNT, STATUS) answers SELECT, INSERT, UPDATE ... WHERE key
IN (...), DELETE ... WHERE key IN (...) and COPY INTO from a file PUT on a stage (the stand-in reads the local file, as
it runs on the same computer). Also SHOW DATABASES / SCHEMAS / TABLES / WAREHOUSES, DESCRIBE TABLE and CALL of a
procedure. SQL containing SLOW keeps running (HTTP 202) until cancelled; SQL containing FAIL fails (HTTP 422).
A result of more than two rows comes in two partitions.
"""
import csv
import json
import re
import uuid

P = "/cloud-snowflake/api/v2/statements"
COLS = [{"name": "ORDER_ID", "type": "fixed", "scale": 0, "precision": 38}, {"name": "CUSTOMER", "type": "text"},
        {"name": "AMOUNT", "type": "fixed", "scale": 2, "precision": 12}, {"name": "STATUS", "type": "text"}]
SEED = {"cloud_snowflake": {"orders": [["1", "Acme GmbH", "120.50", "OPEN"], ["2", "Globex Ltd", "75.00", "OPEN"],
                                       ["3", "Initech", "310.25", "PAID"]], "statements": {}, "stage": {}}}


def text_cols(*names):
    return [{"name": n, "type": "text"} for n in names]


def result(handle, cols, rows, stats=None):
    out = {"statementHandle": handle, "message": "Statement executed successfully.", "code": "090001",
           "resultSetMetaData": {"numRows": len(rows), "format": "jsonv2", "rowType": cols},
           "data": rows[:2] if len(rows) > 2 else rows}
    if len(rows) > 2:
        out["resultSetMetaData"]["partitionInfo"] = [{"rowCount": 2}, {"rowCount": len(rows) - 2}]
    if stats:
        out["stats"] = stats
    return out


def run(st, sql, binds):
    """The result body of one statement, or (status, body) for a failure."""
    s = " ".join(sql.split())
    up = s.upper()
    orders = st["orders"]
    vals = [binds[str(i)]["value"] for i in range(1, len(binds) + 1)] if binds else []
    if up == "SHOW DATABASES":
        return text_cols("name", "owner", "comment"), [["UT_DB", "SYSADMIN", "unit tests"], ["SNOWFLAKE", "", ""]], None
    if up.startswith("SHOW SCHEMAS IN DATABASE"):
        return text_cols("name", "database_name"), [["PUBLIC", "UT_DB"], ["STAGING", "UT_DB"]], None
    if up.startswith("SHOW TABLES IN SCHEMA"):
        return text_cols("name", "database_name", "schema_name", "rows"), [["ORDERS", "UT_DB", "PUBLIC", str(len(orders))]], None
    if up.startswith("DESCRIBE TABLE"):
        return text_cols("name", "type", "null?"), [["ORDER_ID", "NUMBER(38,0)", "N"], ["CUSTOMER", "VARCHAR(100)", "Y"],
                                                     ["AMOUNT", "NUMBER(12,2)", "Y"], ["STATUS", "VARCHAR(20)", "Y"]], None
    if up == "SHOW WAREHOUSES":
        return text_cols("name", "state", "size"), [["UT_WH", "STARTED", "X-Small"]], None
    if up.startswith("SELECT"):
        rows = orders
        if "WHERE AMOUNT > ?" in up and vals:
            rows = [r for r in rows if float(r[2]) > float(vals[0])]
        if "WHERE STATUS = ?" in up and vals:
            rows = [r for r in rows if r[3] == vals[0]]
        if "WHERE ORDER_ID = ?" in up and vals:
            rows = [r for r in rows if r[0] == str(vals[0])]
        return COLS, [list(r) for r in rows], None
    if up.startswith("INSERT INTO"):
        mc = re.search(r"\(([^)]*)\)\s+VALUES", s, re.I)
        names = [c.strip().strip('"').upper() for c in mc.group(1).split(",")]
        n = 0
        for i in range(0, len(vals), len(names)):
            rec = dict(zip(names, vals[i:i + len(names)]))
            orders.append([str(rec.get("ORDER_ID")), rec.get("CUSTOMER"), rec.get("AMOUNT"), rec.get("STATUS") or "OPEN"])
            n += 1
        return [{"name": "number of rows inserted", "type": "fixed"}], [[str(n)]], {"numRowsInserted": n}
    mk = re.search(r"WHERE\s+\"?(\w+)\"?\s+IN\s*\(([^)]*)\)", s, re.I)
    if up.startswith("UPDATE") and mk:
        sets = re.findall(r"\"?(\w+)\"?\s*=\s*\?", s.split(" WHERE ")[0])
        new, keys = vals[:len(sets)], [str(v) for v in vals[len(sets):]]
        idx = {c["name"]: i for i, c in enumerate(COLS)}
        n = 0
        for r in orders:
            if r[idx[mk.group(1).upper()]] in keys:
                for c, v in zip(sets, new):
                    r[idx[c.upper()]] = str(v)
                n += 1
        return [{"name": "number of rows updated", "type": "fixed"}], [[str(n)]], {"numRowsUpdated": n}
    if up.startswith("DELETE FROM") and mk:
        keys = [str(v) for v in vals]
        idx = {c["name"]: i for i, c in enumerate(COLS)}[mk.group(1).upper()]
        before = len(orders)
        orders[:] = [r for r in orders if r[idx] not in keys]
        n = before - len(orders)
        return [{"name": "number of rows deleted", "type": "fixed"}], [[str(n)]], {"numRowsDeleted": n}
    mp = re.match(r"PUT 'file://(.+?)' (\S+)", s, re.I)
    if mp:
        st["stage"][mp.group(2)] = mp.group(1)
        return text_cols("source", "target", "status"), [[mp.group(1).rsplit("/", 1)[-1], mp.group(1).rsplit("/", 1)[-1] + ".gz", "UPLOADED"]], None
    mcp = re.match(r"COPY INTO (\S+) FROM (\S+) .*SKIP_HEADER = (\d+).*ON_ERROR = (\S+)", s, re.I)
    if mcp:
        path = st["stage"].pop(mcp.group(2), None)
        if path is None:
            return 422, {"message": "Remote file '%s' was not found." % mcp.group(2), "code": "002003"}
        lines = list(csv.reader(open(path, encoding="utf-8")))[int(mcp.group(3)):]
        good, bad = [], 0
        for ln in lines:
            if len(ln) == 4 and re.fullmatch(r"\d+", ln[0]) and re.fullmatch(r"-?\d+(\.\d+)?", ln[2]):
                good.append(ln)
            else:
                bad += 1
        if bad and mcp.group(4).upper() == "ABORT_STATEMENT":
            return 422, {"message": "Numeric value is not recognized (file has %d bad rows)" % bad, "code": "100038"}
        orders.extend(good)
        return ([{"name": "file", "type": "text"}, {"name": "status", "type": "text"}, {"name": "rows_parsed", "type": "fixed"},
                 {"name": "rows_loaded", "type": "fixed"}, {"name": "errors_seen", "type": "fixed"}, {"name": "first_error", "type": "text"}],
                [[path.rsplit("/", 1)[-1], "PARTIALLY_LOADED" if bad else "LOADED", str(len(lines)), str(len(good)), str(bad),
                  "Numeric value is not recognized" if bad else None]], None)
    mcall = re.match(r"CALL (\S+?)\((.*)\)$", s, re.I)
    if mcall:
        name = mcall.group(1).split(".")[-1].upper()
        return [{"name": name, "type": "text"}], [["closed %s with %d open orders" % (vals[0] if vals else "?", sum(1 for r in orders if r[3] == "OPEN"))]], None
    return 422, {"message": "SQL compilation error: UT stand-in does not know: " + s[:80], "code": "001003"}


def handle(h, m, path, q):
    if not path.startswith(P):
        raise KeyError(path)
    if not h.headers.get("Authorization", "").startswith("Bearer "):
        return h.send(401, {"message": "Authentication token is missing", "code": "390101"})
    st = core.STATE.setdefault("cloud_snowflake", json.loads(json.dumps(SEED["cloud_snowflake"])))
    with core.LOCK:
        if path == P and m == "POST":
            b = h.body()
            sql = b.get("statement", "")
            hid = str(uuid.uuid4())
            if "FAIL" in sql:
                return h.send(422, {"message": "SQL compilation error: syntax error near 'FAIL'", "code": "001003", "statementHandle": hid})
            if "SLOW" in sql:
                st["statements"][hid] = {"state": "running", "sql": sql.replace("SLOW", ""), "binds": b.get("bindings")}
                return h.send(202, {"statementHandle": hid, "message": "Asynchronous execution in progress.", "code": "333334"})
            got = run(st, sql, b.get("bindings"))
            if isinstance(got[0], int):
                return h.send(got[0], dict(got[1], statementHandle=hid))
            st["statements"][hid] = {"state": "done", "cols": got[0], "rows": got[1], "stats": got[2]}
            return h.send(200, result(hid, *got))
        mh = re.match(re.escape(P) + r"/([^/]+)(/cancel)?$", path)
        if mh:
            rec = st["statements"].get(mh.group(1))
            if rec is None:
                return h.send(404, {"message": "Statement not found", "code": "000709"})
            if mh.group(2):
                if rec["state"] == "running":
                    rec["state"] = "cancelled"
                return h.send(200, {"message": "Statement cancelled.", "code": "000604", "statementHandle": mh.group(1)})
            if rec["state"] == "running":
                return h.send(202, {"statementHandle": mh.group(1), "message": "Asynchronous execution in progress.", "code": "333334"})
            if rec["state"] == "cancelled":
                return h.send(422, {"message": "SQL execution canceled", "code": "000604", "statementHandle": mh.group(1)})
            if q.get("partition") == "1":
                return h.send(200, {"data": rec["rows"][2:]})
            return h.send(200, result(mh.group(1), rec["cols"], rec["rows"], rec["stats"]))
    raise KeyError(path)


ROUTES = [("/cloud-snowflake/", handle, "cloud_snowflake")]
