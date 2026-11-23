"""Workday cases: a session opened by Authenticate, then workers, REST, reports and integrations against the stand-in."""
from helpers_business import drop_samples, cred, url, vars_

HOST = url("workday")
TENANT = "ut_acme"


def add(ua):
    drop_samples(ua)
    vars_(ua, "wd_token", "wd_expires", "wd_table", "wd_count", "wd_json", "wd_event", "wd_status", "wd_done",
          "wd_pct", "wd_closed", "wd_path")

    with ua.case("ooa.workday/authenticate", "sign in and keep a session",
                 "Authenticate swaps the API client's refresh token for an access token and keeps host, tenant and token "
                 "as the Workday session, so the next steps need no address or sign-in. Checks the token's lifetime.") as f:
        f.act("ooa.workday/authenticate", desc="Sign in to the stand-in tenant", host=HOST, tenant=TENANT,
              client_id="ut-client", client_secret=cred("workday", "client_secret"),
              refresh_token=cred("workday", "refresh_token"),
              out={"access_token": "wd_token", "expires_in": "wd_expires"})
        ua.check_eq("wd_expires", 3600)
        ua.check("notEmpty", "$wd_token$", what="the access token")

    with ua.case("ooa.workday/search_workers", "find a worker in the session",
                 "Search workers finds workers whose name or id contains the text; this step has no host, tenant or "
                 "token of its own, so it uses the session. Checks one worker is found.") as f:
        f.act("ooa.workday/search_workers", desc="Search for Patel", search="patel",
              out={"table": "wd_table", "count": "wd_count"})
        ua.check_eq("wd_count", 1)

    with ua.case("ooa.workday/get", "read one worker by its REST path",
                 "Call REST endpoint (GET) reads any Workday REST path and gives back the JSON. Checks the worker's name.") as f:
        f.act("ooa.workday/get", desc="Read worker a000...0001",
              path="/ccx/api/v1/" + TENANT + "/workers/a0000000000000000000000000000001", out={"json": "wd_json"})
        ua.check("eq", "$wd_json.descriptor$", "Asha Patel", what="the worker's name")

    for fmt, what in (("json", "JSON"), ("csv", "CSV"), ("workday_xml", "Workday XML with reference ids")):
        with ua.case("ooa.workday/get_report", "report as " + fmt,
                     "Get report (RaaS) runs a custom report published as a web service and turns its rows into a "
                     f"table; here Workday is asked for {what}. Checks both report rows arrive.") as f:
            args = dict(report_path="/ccx/service/customreport2/" + TENANT + "/ut_reports/Finance_Workers",
                        format=fmt, parameters={"Cost_Center": "Finance"}, out={"table": "wd_table", "count": "wd_count"})
            if fmt == "csv":
                args.update(save_to_file="ut_workday_report.csv")
                args["out"]["path"] = "wd_path"
            f.act("ooa.workday/get_report", desc="Run the Finance_Workers report", **args)
            ua.check_eq("wd_count", 2)
            if fmt == "csv":
                ua.check("contains", "$wd_path$", "ut_workday_report.csv", what="the saved file")

    with ua.case("ooa.workday/launch_integration", "start an integration with parameters",
                 "Launch integration starts an integration system through the Integrations web service, with launch "
                 "parameters, and gives back the event id. Checks an event id came back.") as f:
        f.act("ooa.workday/launch_integration", desc="Launch INT_Worker_Load", integration_system_id="INT_Worker_Load",
              parameters={"Effective_Date": "2026-11-01", "Include_Terminated": False}, out={"event_id": "wd_event"})
        ua.check("notEmpty", "$wd_event$", what="the event id")

    with ua.case("ooa.workday/get_integration_event", "status of a launched integration",
                 "Get integration event reads how an integration run is going. The case launches one, then checks it "
                 "is Completed, 100 percent and done.") as f:
        f.act("ooa.workday/launch_integration", desc="Launch an integration to look at", integration_system_id="INT_Worker_Load",
              out={"event_id": "wd_event"})
        f.act("ooa.workday/get_integration_event", desc="Read its status", event_id="$wd_event$",
              out={"status": "wd_status", "done": "wd_done", "percent_complete": "wd_pct"})
        ua.check_eq("wd_status", "Completed")
        ua.check_eq("wd_done", True)
        ua.check_eq("wd_pct", 100)

    with ua.case("ooa.workday/launch_integration", "unknown integration is refused",
                 "When Workday does not know the integration system, the step fails with WORKDAY.SOAP_FAULT and Workday's reason.") as f:
        ua.expect_error("WORKDAY.SOAP_FAULT", lambda ff: ff.act("ooa.workday/launch_integration", desc="Launch an unknown integration",
                                                     integration_system_id="INT_Missing", out={"event_id": "wd_event"}))

    with ua.case("ooa.workday/launch_eib", "load a file through an EIB",
                 "Launch EIB with file starts an inbound EIB and attaches the file to load. The case writes a small CSV "
                 "first. Checks an event id came back.") as f:
        f.act("ooa.file/write_text", desc="Prepare the file to load", path="ut_workday_load.csv",
              text="Employee_ID,Cost_Center\nUT-1001,Finance\n")
        f.act("ooa.workday/launch_eib", desc="Launch EIB_Cost_Centers with the file", integration_system_id="EIB_Cost_Centers",
              file="ut_workday_load.csv", file_field="Attachment", out={"event_id": "wd_event"})
        ua.check("notEmpty", "$wd_event$", what="the event id")

    with ua.case("ooa.workday/end_session", "forget the session",
                 "End session forgets the Workday session; later steps give their own host, tenant and token. Checks "
                 "a session was open, then searches with the step's own sign-in.") as f:
        f.act("ooa.workday/end_session", desc="Close the Workday session", out={"closed": "wd_closed"})
        ua.check_eq("wd_closed", True)
        f.act("ooa.workday/search_workers", desc="Search with the step's own host, tenant and token", host=HOST,
              tenant=TENANT, access_token=cred("workday", "token"), out={"count": "wd_count"})
        ua.check_eq("wd_count", 3)
