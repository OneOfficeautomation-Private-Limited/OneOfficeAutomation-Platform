"""Workday for the command unit tests, under /biz/workday: workers (REST), RaaS reports, OAuth token, Integrations
web service (SOAP: Launch_Integration_Event, Launch_EIB, Get_Integration_Events). Made-up tenant ut_acme."""
import re
import uuid

SEED = {"biz_workday": {
    "workers": [
        {"id": "a0000000000000000000000000000001", "descriptor": "Asha Patel", "employeeId": "UT-1001", "businessTitle": "Payroll analyst", "primaryWorkEmail": "asha.patel@ut.example"},
        {"id": "a0000000000000000000000000000002", "descriptor": "Bruno Silva", "employeeId": "UT-1002", "businessTitle": "Accountant", "primaryWorkEmail": "bruno.silva@ut.example"},
        {"id": "a0000000000000000000000000000003", "descriptor": "Chloe Martin", "employeeId": "UT-1003", "businessTitle": "HR partner", "primaryWorkEmail": "chloe.martin@ut.example"},
    ],
    "report": [
        {"Worker": "Asha Patel", "Employee_ID": "UT-1001", "Cost_Center": {"Name": "Finance"}},
        {"Worker": "Bruno Silva", "Employee_ID": "UT-1002", "Cost_Center": {"Name": "Finance"}},
    ],
    "events": {},
}}

P = "/biz/workday"


def _authorized(h):
    a = h.headers.get("Authorization", "")
    if not (a.startswith("Bearer ") or a.startswith("Basic ")) or len(a) < 10:
        h.send(401, {"error": "invalid_token", "description": "UT stand-in: no sign-in"})
        return False
    return True


def _xml_rows(rows, workday_ids):
    out = ['<?xml version="1.0" encoding="UTF-8"?><wd:Report_Data xmlns:wd="urn:com.workday.report/UT">']
    for r in rows:
        out.append("<wd:Report_Entry><wd:Worker>%s</wd:Worker><wd:Employee_ID>%s</wd:Employee_ID>"
                   "<wd:Cost_Center><wd:Name>%s</wd:Name></wd:Cost_Center>" % (r["Worker"], r["Employee_ID"], r["Cost_Center"]["Name"]))
        if workday_ids:
            out.append('<wd:Org wd:Descriptor="Finance"><wd:ID wd:type="WID">f00d</wd:ID><wd:ID wd:type="Organization_Reference_ID">CC-100</wd:ID></wd:Org>')
        out.append("</wd:Report_Entry>")
    out.append("</wd:Report_Data>")
    return "".join(out)


def _soap(inner):
    return ('<?xml version="1.0" encoding="UTF-8"?><env:Envelope xmlns:env="http://schemas.xmlsoap.org/soap/envelope/">'
            '<env:Body>' + inner + '</env:Body></env:Envelope>')


def workday(h, m, path, q):
    core.STATE.setdefault("biz_workday", {"workers": [], "report": [], "events": {}})
    st = core.STATE["biz_workday"]
    p = path[len(P):]
    if re.match(r"/ccx/oauth2/[^/]+/token$", p) and m == "POST":
        b = h.body()
        if b.get("grant_type") != "refresh_token" or not b.get("refresh_token") or not h.headers.get("Authorization", "").startswith("Basic "):
            return h.send(400, {"error": "invalid_grant"})
        return h.send(200, {"access_token": "ut-workday-access-" + uuid.uuid4().hex[:8], "token_type": "Bearer", "expires_in": 3600})
    if not _authorized(h):
        return
    mw = re.match(r"/ccx/api/v1/([^/]+)/workers(?:/([^/]+))?$", p)
    if mw and m == "GET":
        with core.LOCK:
            rows = st["workers"]
            if mw.group(2):
                w = next((x for x in rows if x["id"] == mw.group(2)), None)
                return h.send(200, w) if w else h.send(404, {"error": "invalid resource"})
            s = q.get("search", "").lower()
            found = [w for w in rows if not s or s in w["descriptor"].lower() or s in w["employeeId"].lower()]
            off, lim = int(q.get("offset", 0)), int(q.get("limit", 100))
            return h.send(200, {"total": len(found), "data": found[off:off + lim]})
    if p.startswith("/ccx/service/customreport2/") and m == "GET":
        fmt = q.get("format", "json")
        rows = [r for r in st["report"] if not q.get("Cost_Center") or r["Cost_Center"]["Name"] == q["Cost_Center"]]
        if fmt == "csv":
            text = "Worker,Employee_ID,Cost_Center\n" + "".join("%s,%s,%s\n" % (r["Worker"], r["Employee_ID"], r["Cost_Center"]["Name"]) for r in rows)
            return h.send(200, text, ctype="text/csv")
        if fmt.endswith("xml"):
            return h.send(200, _xml_rows(rows, fmt == "workday_xml"), ctype="application/xml")
        return h.send(200, {"Report_Entry": rows})
    mi = re.match(r"/ccx/service/([^/]+)/Integrations/(v[\d.]+)$", p)
    if mi and m == "POST":
        body = h.raw().decode("utf-8", "replace")
        if "Launch_Integration_Event_Request" in body or "Launch_EIB_Request" in body:
            sysid = re.search(r'type="(?:Integration_System_ID|WID)">([^<]+)<', body)
            if "Launch_EIB_Request" in body and "<bsvc:File_Content>" not in body:
                return h.send(500, _soap("<env:Fault><faultcode>env:Client</faultcode><faultstring>No file attached</faultstring></env:Fault>"), ctype="text/xml")
            if sysid and sysid.group(1) == "INT_Missing":
                return h.send(500, _soap("<env:Fault><faultcode>env:Client</faultcode><faultstring>Invalid ID value. 'INT_Missing' is not a valid ID value for type = 'Integration_System_ID'</faultstring></env:Fault>"), ctype="text/xml")
            wid = uuid.uuid4().hex
            params = re.findall(r'Launch_Parameter_Name">([^<]+)<', body)
            with core.LOCK:
                st["events"][wid] = {"system": sysid.group(1) if sysid else "", "params": params, "eib": "Launch_EIB_Request" in body}
            return h.send(200, _soap('<wd:Launch_Integration_Event_Response xmlns:wd="urn:com.workday/bsvc"><wd:Integration_Event>'
                                     '<wd:Integration_Event_Reference><wd:ID wd:type="WID">%s</wd:ID></wd:Integration_Event_Reference>'
                                     '</wd:Integration_Event></wd:Launch_Integration_Event_Response>' % wid), ctype="text/xml")
        if "Get_Integration_Events_Request" in body:
            eid = re.search(r'Integration_Event_Reference><bsvc:ID bsvc:type="[^"]+">([^<]+)<', body)
            ev = st["events"].get(eid.group(1)) if eid else None
            if ev is None:
                return h.send(200, _soap('<wd:Get_Integration_Events_Response xmlns:wd="urn:com.workday/bsvc"><wd:Response_Data/></wd:Get_Integration_Events_Response>'), ctype="text/xml")
            return h.send(200, _soap(
                '<wd:Get_Integration_Events_Response xmlns:wd="urn:com.workday/bsvc"><wd:Response_Data><wd:Integration_Event><wd:Integration_Event_Data>'
                '<wd:Initiated_DateTime>2026-11-23T09:00:00Z</wd:Initiated_DateTime><wd:Completed_DateTime>2026-11-23T09:01:30Z</wd:Completed_DateTime>'
                '<wd:Percent_Complete>100</wd:Percent_Complete>'
                '<wd:Background_Process_Instance_Status_Reference><wd:ID wd:type="Background_Process_Instance_Status_ID">Completed</wd:ID></wd:Background_Process_Instance_Status_Reference>'
                '</wd:Integration_Event_Data></wd:Integration_Event></wd:Response_Data></wd:Get_Integration_Events_Response>'), ctype="text/xml")
    raise KeyError(path)


ROUTES = [(P + "/", workday, "biz_workday")]
