"""UT Item Workflow: starts the sample workflow UT Workflow.process.json through One CommandCenter's workflow API (no
toolkit command starts a workflow), reads its run back and cancels it. Needs the workflow, its form and UT API agent
published under Commands unit testing folder/items/, and a session token in the vault."""
from helpers_platform import renumber, credential_var

PATH = "Commands unit testing folder/items/workflow/UT Workflow.process.json"
REST = "ooa.rest/request"


def add(ua):
    ua.f.var("cr_url", "String", "input", "http://127.0.0.1:8080",
             "One CommandCenter's address (its /api/workflows routes are used).")
    credential_var(ua, "cr_token", "unit_tests/ooa/cr_token")
    for v, t in (("wf_json", "Any"), ("wf_status", "Number"), ("wf_id", "String"), ("wf_ref", "String"),
                 ("wf_text", "String")):
        ua.var(v, t)
    f = ua.f
    start = dict(url="${cr_url}/api/workflows/start", method="POST", bearer_token="$cr_token$")
    data = {"quantity": 3, "unit_price": 12.5, "full_name": "UT Tester", "email": "ut.tester@example.com",
            "amount": 37.5, "category": "Travel", "agree": True}
    with ua.case(REST, "start the workflow with its start form's answers",
                 "POST /api/workflows/start starts the live version of the sample workflow with data that answers "
                 "its start form, under a reference of our own; the answer is the run's id and the reference."):
        f.set("wf_ref", "ut_wf_${sys.run_id}")
        f.act(REST, json={"path": PATH, "data": data, "reference": "$wf_ref$"}, **start,
              out={"status": "wf_status", "json": "wf_json"})
        ua.check("eq", "$wf_status$", 200, what="HTTP status")
        f.expr("wf_id", "$wf_json.id")
        ua.check("notEmpty", "$wf_id$", what="the workflow run id")
        f.expr("wf_text", "$wf_json.reference")
        ua.check("eq", "$wf_text$", "$wf_ref$", what="the reference")
        f.act(REST, desc="Read the run back by its reference", url="${cr_url}/api/workflows/runs",
              query={"reference": "$wf_ref$"}, bearer_token="$cr_token$", out={"body": "wf_text"})
        ua.check("contains", "$wf_text$", "$wf_id$", what="the run read back")
        f.act(REST, desc="Cancel the run (tidy up)", url="${cr_url}/api/workflows/runs/${wf_id}/cancel", method="POST",
              bearer_token="$cr_token$", out={"status": "wf_status"})
        ua.check("eq", "$wf_status$", 200, what="cancel status")
    with ua.case(REST, "the start form's checks apply",
                 "Starting without the required answers of the start form (no name, box not ticked) is refused; "
                 "the API answers with an error status."):
        f.act(REST, json={"path": PATH, "data": {"quantity": 1, "unit_price": 1}}, fail_on_error=False, **start,
              out={"status": "wf_status"})
        ua.check("ge", "$wf_status$", 400, what="HTTP status")
    renumber(ua)
