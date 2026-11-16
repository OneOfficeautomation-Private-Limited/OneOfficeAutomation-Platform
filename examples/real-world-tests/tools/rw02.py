"""RW02 Employee lifecycle: Workday -> Entra ID/AD -> ServiceNow -> Outlook/Teams (child agents and main)."""
from common import Child, Main, cred, evidence_var, fault_setup, graph, save_evidence, sn

FOLDER = "RW02 Employee lifecycle"
TENANT = "ooa_qa"
QA_MAILBOX = "hr-qa@ooa-qa.test"


def wd():
    return {"host": "$standin$", "tenant": TENANT, "access_token": cred("workday")}


def g_api(path, method="GET", **kw):
    """A Microsoft Graph call through the REST toolkit (the Microsoft 365 toolkit has no Entra user and group commands)."""
    return dict(method=method, url="${standin}/graph/v1.0" + path, bearer_token=cred("graph"), **kw)


def common_prepare(f):
    f.act("ooa.csv/read", desc="Read the roles (groups, laptop, software)", path="${base}/data/RW02/roles.csv", out={"table": "roles"})
    f.act("ooa.csv/read", desc="Read the groups", path="${base}/data/RW02/groups.csv", out={"table": "groups"})


def b1_intake():
    c = Child("RW02", 1, "HR request intake",
              "Reads the 10 HR requests, normalizes names, checks manager, cost centre and worker type, drops duplicate request ids, works out "
              "the effective-date branch, masks personal data in the log, checks the approval and creates one lifecycle item per valid request.",
              "10 synthetic HR requests", data_file="RW02/requests.csv")
    f = c.f
    for n, t, d in [("first", "String", ""), ("last", "String", ""), ("manager", "String", ""), ("cc", "String", ""), ("wtype", "String", ""),
                    ("eff", "DateTime", None), ("days", "Number", 0), ("branch", "String", ""), ("seen", "List", []), ("idem", "String", ""),
                    ("masked", "String", ""), ("managers", "Table", None), ("ccs", "Table", None), ("wtypes", "Table", None)]:
        f.var(n, t, "local", d)
    evidence_var(f, ["key", "request_id", "action", "name", "manager", "cost_center", "worker_type", "effective", "branch", "idempotency"])
    c.start()
    with c.prepare("Read the managers, cost centres and allowed worker types."):
        f.act("ooa.csv/read", desc="Read the managers", path="${base}/data/RW02/managers.csv", out={"table": "managers"})
        f.act("ooa.csv/read", desc="Read the cost centres", path="${base}/data/RW02/cost_centers.csv", out={"table": "ccs"})
        f.act("ooa.csv/read", desc="Read the allowed worker types", path="${base}/data/RW02/worker_types.csv", out={"table": "wtypes"})
    with c.records("Per request: normalize, validate manager, cost centre and worker type, drop duplicates, date branch, approval, create the item.",
                   summary="${rec.request_id} ${rec.action}: ${first} ${last}\nBranch: ${branch}"):
        f.act("ooa.string/change_case", desc="Normalize the first name", text="$rec.first$", case="title", out={"result": "first"})
        f.act("ooa.string/change_case", desc="Normalize the last name", text="$rec.last$", case="title", out={"result": "last"})
        with f.if_({"op": "contains", "left": "$seen$", "right": "$rec.request_id$"}, desc="Request id seen already?"):
            f.act("core/throw", desc="Route the duplicate", code="ROUTE.DUPLICATE", message="${rec.request_id} was already taken in (row ${rec.key})")
        f.act("ooa.list/add_item", desc="Remember the request id", list="$seen$", item="$rec.request_id$", out={"list": "seen"})
        f.act("ooa.datatable/lookup", desc="Validate the manager", table="$managers$", column="manager", value="$rec.manager$", return_column="name",
              if_not_found="", out={"value": "manager"})
        f.act("ooa.datatable/lookup", desc="Validate the cost centre", table="$ccs$", column="cost_center", value="$rec.cost_center$", return_column="name",
              if_not_found="", out={"value": "cc"})
        with f.if_({"any": [{"op": "empty", "left": "$manager$"}, {"op": "empty", "left": "$cc$"}]}, desc="Unknown manager or cost centre?"):
            f.act("core/throw", desc="Reject it", code="VALIDATION.ORG_DATA", message="${rec.request_id}: unknown manager ${rec.manager} or cost centre ${rec.cost_center}")
        f.act("ooa.datatable/lookup", desc="Validate the worker type", table="$wtypes$", column="worker_type", value="$rec.worker_type$", return_column="worker_type",
              if_not_found="", out={"value": "wtype"})
        with f.if_({"op": "empty", "left": "$wtype$"}, desc="Worker type not allowed?"):
            f.act("core/throw", desc="Reject it", code="VALIDATION.WORKER_TYPE", message="${rec.request_id}: worker type ${rec.worker_type} is not allowed")
        f.act("ooa.datetime/parse", desc="Read the effective date", text="$rec.effective$", pattern="yyyy-MM-dd", out={"value": "eff"})
        f.act("ooa.datetime/difference", desc="Days until it takes effect", start={"expr": "today()"}, end="$eff$", unit="days", out={"result": "days"})
        f.expr("branch", "if($days$ > 30, 'delayed', if($days$ < 0, 'overdue', 'now'))", desc="Effective-date branch")
        with f.if_({"op": "ne", "left": "$rec.approved$", "right": "yes"}, desc="No manager approval?"):
            f.act("core/throw", desc="Hold it for approval", code="APPROVAL.MISSING", message="${rec.request_id} has no manager approval yet")
        f.act("ooa.string/replace", desc="Mask the last name for the log", text="${first} ${last}", find="$last$", replace_with="***", out={"result": "masked"})
        f.act("ooa.crypto/hash_text", desc="Idempotency key (the HR request id)", text="$rec.request_id$", out={"hash": "idem"})
        f.act("ooa.log/write", desc="Checkpoint the source request (masked)", path="$log_file$", message="${rec.request_id} ${rec.action} ${masked} -> ${branch}")
        f.act("ooa.datatable/add_row", desc="Create the lifecycle item", table="$evidence$",
              values={"key": "$rec.key$", "request_id": "$rec.request_id$", "action": "$rec.action$", "name": "${first} ${last}", "manager": "$manager$",
                      "cost_center": "$rec.cost_center$", "worker_type": "$wtype$", "effective": "$rec.effective$", "branch": "$branch$", "idempotency": "$idem$"},
              out={"table": "evidence"})
        f.set("rec_note", "${rec.action} ${branch}", desc="Note for the results")
    with c.finish():
        save_evidence(f)
    return c


def valid_items(f, c):
    """Children after intake work only on the items intake accepted (its evidence), or on the approved requests when run alone."""
    f.var("items", "Table", "local", None)
    with f.if_({"condition": "ooa.file/exists", "args": {"path": "${base}/output/${run_id}/RW02-B1/evidence.csv"}}, desc="Intake ran in this run?") as has:
        f.act("ooa.csv/read", desc="Read the accepted lifecycle items", path="${base}/output/${run_id}/RW02-B1/evidence.csv", out={"table": "items"})
        f.act("ooa.datatable/join", desc="Keep the requests intake accepted", table="$records$", key_columns=["key"], other="$items$", join_type="inner",
              out={"table": "records"})
    with f.else_(has):
        f.act("ooa.datatable/filter", desc="Approved requests only", table="$records$", column="approved", operator="eq", value="yes", out={"table": "records"})


def b2_workday():
    c = Child("RW02", 2, "Workday record validator",
              "Finds each person in the Workday sandbox, checks movers and leavers exist and joiners do not (a terminated match is a rehire), "
              "validates dates and worker type, stages the joiner's worker record, reads it back and keeps the worker id.",
              "10 Workday sandbox workers", data_file="RW02/requests.csv")
    f = c.f
    for n, t, d in [("found", "Table", None), ("hits", "List", None), ("hit", "Dictionary", None), ("again", "Any", None), ("wid", "String", ""),
                    ("staged", "Any", None), ("rehire", "Boolean", False), ("cnt", "Number", 0), ("wxml", "String", ""), ("wxid", "String", "")]:
        f.var(n, t, "local", d)
    evidence_var(f, ["key", "request_id", "action", "worker_id", "rehire", "staged"])
    c.start()
    with c.prepare("Read the requests intake accepted."):
        fault_setup(f, "workday")
        valid_items(f, c)
    with c.records("Per request: search Workday, check existence by action, detect rehires, validate, stage joiners, read back, keep the id.",
                   summary="${rec.first} ${rec.last} (${rec.action})\nWorker: ${wid}  rehire ${rehire}"):
        f.act("ooa.workday/search_workers", desc="Search the sandbox for the person", search="${rec.first} ${rec.last}", max_results=5,
              out={"table": "found", "count": "cnt"}, **wd())
        f.act("ooa.datatable/to_records", desc="As records", table="$found$", out={"records": "hits"})
        f.set("rehire", False, desc="Not a rehire")
        f.set("wid", "", desc="No worker id yet")
        with f.switch("$rec.action$", desc="What the request needs from Workday") as sw:
            with f.case(sw, "joiner"):
                with f.if_("$cnt$ > 0", desc="Already in Workday?"):
                    f.act("ooa.workday/get", desc="Read the existing worker", path="/ccx/api/v1/" + TENANT + "/workers/${first($hits$).id}", out={"json": "hit"}, **{k: v for k, v in wd().items() if k != "tenant"})
                    with f.if_({"op": "eq", "left": "$hit.status$", "right": "Active"}, desc="Active already?"):
                        f.act("core/throw", desc="Duplicate person", code="VALIDATION.DUPLICATE_WORKER", message="${rec.first} ${rec.last} is already an active worker")
                    f.set("rehire", True, desc="A terminated worker returns: rehire")
                f.act("ooa.rest/request", desc="Stage the joiner's worker record (approved only)", method="POST", url="${standin}/ccx/api/v1/" + TENANT + "/staged",
                      bearer_token=cred("workday"), json={"request_id": "$rec.request_id$", "descriptor": "${rec.first} ${rec.last}", "rehire": "$rehire$",
                                                          "workerType": "$rec.worker_type$", "start": "$rec.effective$", "run": "$run_id$"}, out={"json": "staged"})
                f.act("ooa.workday/get", desc="Read the staged record back", path="/ccx/api/v1/" + TENANT + "/staged/${staged.id}", out={"json": "again"},
                      **{k: v for k, v in wd().items() if k != "tenant"})
                f.set("wid", "$again.id$", desc="Keep the staged id")
            with f.default(sw):
                with f.if_("$cnt$ == 0", desc="Mover or leaver not in Workday?"):
                    f.act("core/throw", desc="Reject it", code="VALIDATION.NO_WORKER", message="${rec.first} ${rec.last} is not in Workday")
                f.act("ooa.workday/get", desc="Read the worker", path="/ccx/api/v1/" + TENANT + "/workers/${first($hits$).id}", out={"json": "hit"},
                      **{k: v for k, v in wd().items() if k != "tenant"})
                with f.if_({"op": "ne", "left": "$hit.status$", "right": "Active"}, desc="Not active?"):
                    f.act("core/throw", desc="Reject it", code="VALIDATION.NOT_ACTIVE", message="${rec.first} ${rec.last} is not active in Workday")
                f.set("wid", "$hit.employeeId$", desc="Keep the worker id")
        f.set("wxml", "<wd:Worker xmlns:wd=\"urn:com.workday/bsvc\"><wd:Request_ID>${rec.request_id}</wd:Request_ID><wd:Worker_ID>${wid}</wd:Worker_ID>"
                      "<wd:Action>${rec.action}</wd:Action><wd:Rehire>${rehire}</wd:Rehire></wd:Worker>", desc="The reconciliation record as Workday-style XML")
        f.act("ooa.xml/get_value", desc="Read the worker id back from the XML", xml="$wxml$", xpath="//*[local-name()='Worker_ID']", out={"value": "wxid"})
        with f.if_({"op": "ne", "left": "$wxid$", "right": "$wid$"}, desc="XML disagrees?"):
            f.act("core/throw", desc="Report it", code="VERIFY.XML", message="${rec.request_id}: XML holds ${wxid}, expected ${wid}")
        f.act("ooa.log/write", desc="Write the reconciliation event", path="$log_file$", message="${rec.request_id}: Workday ${wid} rehire ${rehire}")
        f.act("ooa.datatable/add_row", desc="Record it", table="$evidence$",
              values={"key": "$rec.key$", "request_id": "$rec.request_id$", "action": "$rec.action$", "worker_id": "$wid$", "rehire": "$rehire$",
                      "staged": {"expr": "$rec.action$ == 'joiner'"}}, out={"table": "evidence"})
        f.set("rec_note", "${wid} rehire ${rehire}", desc="Note for the results")
    with c.finish():
        save_evidence(f)
    return c


def b3_identity():
    c = Child("RW02", 3, "Entra and AD identity worker",
              "Maps each role to its approved groups, finds or creates the OOA-QA test identity in Entra ID, sets its state, adds and removes "
              "group memberships (movers get the difference, leavers are disabled, lose every group and have their sign-in sessions revoked), "
              "re-reads the memberships and compares them with the expected set. AD steps run only when a test domain is configured.",
              "10 test identities in Entra and AD", data_file="RW02/requests.csv")
    f = c.f
    f.var("ad_server", "String", "input", "", "Address of an isolated AD test domain (ldaps://...); empty skips the AD steps.")
    for n, t, d in [("roles", "Table", None), ("groups", "Table", None), ("upn", "String", ""), ("users", "Any", None), ("uid", "String", ""),
                    ("want", "List", []), ("old", "List", []), ("g", "String", ""), ("gid", "String", ""), ("have", "Any", None), ("names", "String", ""),
                    ("missing", "Number", 0), ("cm", "Dictionary", None)]:
        f.var(n, t, "local", d)
    evidence_var(f, ["key", "request_id", "action", "user_id", "groups_now", "enabled"])
    c.start()
    with c.prepare("Read the approved items, the role-to-group matrix and the group ids; prove the Windows Credential Manager path with a test entry."):
        fault_setup(f, "graph")
        valid_items(f, c)
        common_prepare(f)
        f.comment("Credential Manager: write, read and delete one OOA-QA test entry (no real secret), as identity tools keep service sign-ins there.")
        f.act("ooa.credential_manager/write_credential", desc="Write a test entry", target="OOA-QA-${run_id}", username="qa-service", password=cred("sign_key", "value"), persist="session")
        f.act("ooa.credential_manager/read_credential", desc="Read it back", target="OOA-QA-${run_id}", out={"username": "g"})
        f.act("ooa.credential_manager/delete_credential", desc="Delete the test entry", target="OOA-QA-${run_id}")
    with c.records("Per identity: groups for the role, find or create the user, set state, change memberships, revoke for leavers, re-read, compare.",
                   summary="${upn} (${rec.action})\nGroups: ${names}"):
        f.set("upn", "${replace(lower($rec.first + '.' + $rec.last), \"'\", '')}.qa@ooa-qa.test", desc="The test user's sign-in name")
        with f.if_("not endsWith($upn$, '.qa@ooa-qa.test')", desc="Only OOA-QA identities"):
            f.act("core/throw", desc="Refuse others", code="POLICY.NOT_QA_IDENTITY", message="${upn} is not an OOA-QA identity")
        f.expr("want", "[]", desc="No groups yet")
        with f.if_("not isEmpty($rec.role$)", desc="Has a new role (leavers have none)?"):
            f.act("ooa.datatable/lookup", desc="Groups of the role", table="$roles$", column="role", value="$rec.role$", return_column="groups",
                  if_not_found="", out={"value": "g"})
            f.expr("want", "if(isEmpty($g$), [], split($g$, ';'))", desc="Approved groups for the new role")
        f.expr("old", "[]", desc="No earlier groups yet")
        with f.if_("not isEmpty($rec.prior_role$)", desc="Had an earlier role (movers, leavers)?"):
            f.act("ooa.datatable/lookup", desc="Groups of the earlier role", table="$roles$", column="role", value="$rec.prior_role$",
                  return_column="groups", if_not_found="", out={"value": "g"})
            f.expr("old", "if(isEmpty($g$), [], split($g$, ';'))", desc="Groups of the earlier role")
        f.act("ooa.rest/request", desc="Find the user in Entra ID", out={"json": "users"}, **g_api("/users", query={"$filter": "userPrincipalName eq '${upn}'"}))
        with f.if_("len($users.value$) == 0", desc="Not there yet?") as none:
            with f.if_({"op": "ne", "left": "$rec.action$", "right": "joiner"}, desc="Only joiners are created"):
                f.act("core/throw", desc="Reject it", code="VALIDATION.NO_IDENTITY", message="${upn} does not exist")
            f.act("ooa.rest/request", desc="Create the test user (disabled until the start date)", out={"json": "users"},
                  **g_api("/users", "POST", json={"displayName": "${rec.first} ${rec.last}", "userPrincipalName": "$upn$", "accountEnabled": False,
                                                  "jobTitle": "$rec.role$", "employeeId": "$rec.request_id$", "mailNickname": "qa"}))
            f.set("uid", "$users.id$", desc="Its object id")
        with f.else_(none):
            f.expr("uid", "get(first($users.value$), 'id')", desc="Its object id")
        with f.switch("$rec.action$", desc="Joiner, mover or leaver") as sw:
            with f.case(sw, "leaver"):
                f.act("ooa.rest/request", desc="Disable the account first", **g_api("/users/${uid}", "PATCH", json={"accountEnabled": False}))
                f.act("ooa.rest/request", desc="Revoke its sign-in sessions", **g_api("/users/${uid}/revokeSignInSessions", "POST", json={}))
                f.set("want", [], desc="A leaver keeps no groups")
            with f.case(sw, "mover"):
                f.act("ooa.rest/request", desc="Update the job title", **g_api("/users/${uid}", "PATCH", json={"jobTitle": "$rec.role$"}))
        f.act("ooa.rest/request", desc="Current memberships", out={"json": "have"}, **g_api("/users/${uid}/memberOf"))
        with f.loop(desc="Each current membership", iterator="list", over="$have.value$", **{"as": "cm"}):
            with f.if_("not contains($want$, $cm.displayName$)", desc="Not in the approved set any more?"):
                f.act("ooa.rest/request", desc="Remove the membership", **g_api("/groups/${cm.id}/members/${uid}/$ref", "DELETE"))
        with f.loop(desc="Each approved group", iterator="list", over="$want$", **{"as": "g"}):
            f.act("ooa.datatable/lookup", desc="Group id", table="$groups$", column="name", value="$g$", return_column="id", out={"value": "gid"})
            with f.if_("not contains(jsonStringify($have.value$), $gid$)", desc="Not a member yet?"):
                f.act("ooa.rest/request", desc="Add the membership", fail_on_error=False,
                      **g_api("/groups/${gid}/members/$ref", "POST", json={"@odata.id": "${standin}/graph/v1.0/directoryObjects/${uid}"}))
        f.act("ooa.rest/request", desc="Re-query the memberships", out={"json": "have"}, **g_api("/users/${uid}/memberOf"))
        f.expr("names", "jsonStringify($have.value$)", desc="Memberships now (for the evidence)")
        f.expr("missing", "len($want$) - len($have.value$)", desc="Entitlement difference")
        with f.if_("$missing$ != 0", desc="Memberships differ from the approved set?"):
            f.act("core/throw", desc="Report the difference", code="VERIFY.ENTITLEMENTS", message="${upn}: ${len($have.value$)} groups, ${len($want$)} expected")
        with f.if_({"op": "ne", "left": "$ad_server$", "right": ""}, desc="AD test domain configured?") as ad:
            f.act("ooa.active_directory/connect", desc="Sign in to the AD test domain", url="$ad_server$", bind_user="qa-svc@ooa-qa.test",
                  password=cred("sign_key", "value"))
            f.act("ooa.active_directory/get_user", desc="Read the AD account in the test OU", username="$upn$")
        with f.else_(ad):
            f.act("ooa.log/write", desc="Note the skipped AD step", path="$log_file$", message="${upn}: AD test domain not configured; AD step skipped")
        f.act("ooa.datatable/add_row", desc="Record the object id", table="$evidence$",
              values={"key": "$rec.key$", "request_id": "$rec.request_id$", "action": "$rec.action$", "user_id": "$uid$", "groups_now": {"expr": "len($have.value$)"},
                      "enabled": {"expr": "$rec.action$ != 'leaver'"}}, out={"table": "evidence"})
        f.set("rec_note", "${uid}: ${len($have.value$)} groups", desc="Note for the results")
    with c.finish():
        save_evidence(f)
    return c


def b4_fulfilment():
    c = Child("RW02", 4, "IT fulfilment worker",
              "Creates one ServiceNow catalog task per lifecycle item with the identity and request ids, picks the approved laptop and software "
              "profile, sets the due date in working days, branches on joiner, mover and leaver, verifies there is exactly one task and saves it.",
              "10 IT tasks", data_file="RW02/requests.csv")
    f = c.f
    for n, t, d in [("roles", "Table", None), ("groups", "Table", None), ("laptop", "String", ""), ("software", "String", ""), ("due", "DateTime", None),
                    ("task", "Dictionary", None), ("sysid", "String", ""), ("found", "Table", None), ("cnt", "Number", 0), ("what", "String", ""), ("again", "Dictionary", None)]:
        f.var(n, t, "local", d)
    evidence_var(f, ["key", "request_id", "task", "profile", "due", "what"])
    c.start()
    with c.prepare("Read the accepted items and the role profiles."):
        fault_setup(f, "servicenow")
        valid_items(f, c)
        common_prepare(f)
    with c.records("Per item: profile, due date, branch, create the task, start it, check there is one, read it back, keep it.",
                   summary="${task.number}: ${what}\nDue ${due}"):
        f.act("ooa.datatable/lookup", desc="Laptop profile of the role", table="$roles$", column="role", value={"expr": "coalesce($rec.role$, $rec.prior_role$)"},
              return_column="laptop", if_not_found="standard-laptop", out={"value": "laptop"})
        f.act("ooa.datatable/lookup", desc="Software of the role", table="$roles$", column="role", value={"expr": "coalesce($rec.role$, $rec.prior_role$)"},
              return_column="software", if_not_found="Office", out={"value": "software"})
        f.act("ooa.datetime/add_working_days", desc="Due two working days before the start", value={"expr": "parseDate($rec.effective$, '%Y-%m-%d')"}, days=-2,
              out={"result": "due"})
        with f.switch("$rec.action$", desc="What IT has to do") as sw:
            with f.case(sw, "joiner"):
                f.set("what", "Prepare ${laptop} with ${software}", desc="Joiner: new laptop and software")
            with f.case(sw, "mover"):
                f.set("what", "Change software to ${software}", desc="Mover: software change")
            with f.default(sw):
                f.set("what", "Collect the laptop and remove software", desc="Leaver: collect and remove")
        f.act("ooa.servicenow/query", desc="Is there a task for this request already?", table="sc_task", query="u_request_id=${rec.request_id}^u_run=${run_id}",
              out={"table": "found", "count": "cnt"}, **sn())
        with f.if_("$cnt$ > 0", desc="Already created (a rerun)?"):
            f.act("core/throw", desc="Never create it twice", code="ROUTE.ALREADY_DONE", message="${rec.request_id} already has a task")
        f.act("ooa.servicenow/create_record", desc="Create the catalog task", table="sc_task", out={"record": "task", "sys_id": "sysid"},
              fields={"short_description": "OOA-QA ${rec.request_id} ${what}", "u_request_id": "$rec.request_id$", "u_run": "$run_id$", "u_action": "$rec.action$",
                      "u_profile": "$laptop$", "due_date": "${formatDate($due, '%Y-%m-%d')}"}, **sn())
        f.act("ooa.servicenow/update_record", desc="Start the task", table="sc_task", sys_id="$sysid$", fields={"state": "2"}, **sn())
        f.act("ooa.servicenow/query", desc="Verify there is exactly one task", table="sc_task", query="u_request_id=${rec.request_id}^u_run=${run_id}",
              out={"table": "found", "count": "cnt"}, **sn())
        with f.if_("$cnt$ != 1", desc="Not exactly one?"):
            f.act("core/throw", desc="Report it", code="VERIFY.TASK_COUNT", message="${rec.request_id} has ${cnt} tasks")
        f.act("ooa.servicenow/get_record", desc="Read the task back", table="sc_task", sys_id="$sysid$", out={"record": "again"}, **sn())
        f.act("ooa.datatable/add_row", desc="Save the result", table="$evidence$",
              values={"key": "$rec.key$", "request_id": "$rec.request_id$", "task": "$again.number$", "profile": "$laptop$",
                      "due": "${formatDate($due, '%Y-%m-%d')}", "what": "$what$"}, out={"table": "evidence"})
        f.set("rec_note", "${again.number}", desc="Note for the results")
    with c.finish():
        save_evidence(f)
    return c


def b5_m365():
    c = Child("RW02", 5, "Microsoft 365 access worker",
              "Checks each test principal's group memberships, writes the onboarding note to the QA SharePoint site, posts to the QA Teams "
              "channel, sends the welcome (joiners) or the deprovision notice (leavers) only to QA addresses, and confirms delivery.",
              "10 test principals", data_file="RW02/requests.csv")
    f = c.f
    for n, t, d in [("upn", "String", ""), ("users", "Any", None), ("uid", "String", ""), ("have", "Any", None), ("note", "String", ""),
                    ("item", "String", ""), ("post", "String", ""), ("seen", "Number", 0), ("subject", "String", "")]:
        f.var(n, t, "local", d)
    evidence_var(f, ["key", "request_id", "groups", "sharepoint_item", "teams_post", "mail_delivered"])
    c.start()
    with c.prepare("Read the accepted items; a folder for the onboarding notes."):
        fault_setup(f, "graph")
        valid_items(f, c)
        f.act("ooa.folder/create", desc="Folder for the notes", path="${out_dir}/notes")
    with c.records("Per principal: memberships, onboarding note to SharePoint, Teams post, QA-only mail, delivery check.",
                   summary="${upn}\nSharePoint ${item}; Teams ${post}; mail ${seen}"):
        f.set("upn", "${replace(lower($rec.first + '.' + $rec.last), \"'\", '')}.qa@ooa-qa.test", desc="The principal")
        with f.if_("not endsWith($upn$, '@ooa-qa.test')", desc="Only test recipients"):
            f.act("core/throw", desc="Refuse others", code="POLICY.NOT_QA_RECIPIENT", message="${upn} is outside the QA tenant")
        f.act("ooa.rest/request", desc="Find the principal", out={"json": "users"}, **g_api("/users", query={"$filter": "userPrincipalName eq '${upn}'"}))
        with f.if_("len($users.value$) == 0", desc="No principal?"):
            f.act("core/throw", desc="Route it", code="ROUTE.NO_PRINCIPAL", message="${upn} has no identity yet (identity worker first)")
        f.expr("uid", "get(first($users.value$), 'id')", desc="Its id")
        f.act("ooa.rest/request", desc="Verify its group memberships", out={"json": "have"}, **g_api("/users/${uid}/memberOf"))
        f.set("note", "Onboarding note for ${rec.first} ${rec.last} (${rec.action}, ${rec.role}), effective ${rec.effective}. Run ${run_id}.", desc="The note")
        f.act("ooa.file/write_text", desc="Write the note", path="${out_dir}/notes/${rec.request_id}.txt", text="$note$")
        f.act("ooa.microsoft365/upload_file", desc="Store it in the QA SharePoint site", local_path="${out_dir}/notes/${rec.request_id}.txt",
              remote_path="OOA-QA/${run_id}/${rec.request_id}.txt", drive="sites/ooa-qa/drive", access_token=cred("graph"), base_url="${standin}/graph/v1.0",
              out={"item_id": "item"})
        f.act("ooa.microsoft365/teams_message", desc="Post to the QA onboarding channel", team_id="t-onboarding", channel_id="c-general",
              message="OOA-QA ${rec.request_id}: ${rec.action} for ${rec.first} effective ${rec.effective}", access_token=cred("graph"),
              base_url="${standin}/graph/v1.0", out={"message_id": "post"})
        f.set("subject", "OOA-QA ${run_id} ${rec.request_id} ${rec.action}", desc="Mail subject")
        f.act("ooa.microsoft365/send_mail", desc="Welcome or deprovision notice, QA mailbox only", to="$upn$", subject="$subject$",
              body={"expr": "if($rec.action$ == 'leaver', 'Your test access ends today.', 'Welcome to the QA tenant.')"}, **graph(QA_MAILBOX))
        f.act("ooa.microsoft365/list_mail", desc="Confirm delivery", folder="inbox", subject_contains="$subject$", max_results=3,
              out={"count": "seen"}, **graph("$upn$"))
        with f.if_("$seen$ == 0", desc="Not delivered?"):
            f.act("core/throw", desc="Report it", code="VERIFY.NOT_DELIVERED", message="Mail to ${upn} did not arrive")
        f.act("ooa.datatable/add_row", desc="Write the audit row", table="$evidence$",
              values={"key": "$rec.key$", "request_id": "$rec.request_id$", "groups": {"expr": "len($have.value$)"}, "sharepoint_item": "$item$",
                      "teams_post": "$post$", "mail_delivered": "$seen$"}, out={"table": "evidence"})
        f.set("rec_note", "${len($have.value$)} groups; mail ${seen}", desc="Note for the results")
    with c.finish():
        save_evidence(f)
    return c


def b6_readiness():
    c = Child("RW02", 6, "Windows readiness and evidence",
              "Reads this computer's details with VBScript, reconciles Workday, Entra ID and ServiceNow for every lifecycle item, highlights "
              "orphans and mismatches, puts each line on the clipboard, writes the evidence workbook (also as a PDF through Excel) and stores it "
              "in the QA SharePoint site.",
              "10 lifecycle bundles", data_file="RW02/requests.csv")
    f = c.f
    src = {"b1": "RW02-B1", "b2": "RW02-B2", "b3": "RW02-B3", "b4": "RW02-B4", "b5": "RW02-B5"}
    for k in src:
        f.var(k, "Table", "local", {"columns": ["key"], "rows": []})
    for n, t, d in [("computer", "String", ""), ("wd", "String", ""), ("entra", "String", ""), ("task", "String", ""), ("mail", "String", ""),
                    ("state", "String", ""), ("line", "String", ""), ("clip", "String", ""), ("item", "String", ""),
                    ("bundle", "Table", {"columns": ["key", "request_id", "action", "workday", "entra", "servicenow", "mail", "state"], "rows": []})]:
        f.var(n, t, "local", d)
    f.var("workbook", "String", "output", "", "The evidence workbook.")
    for n, t, d in [("jhome", "String", ""), ("jpid", "Number", 0), ("japps", "List", None), ("desk", "Boolean", False), ("jstatus", "Any", None),
                    ("pa", "List", None), ("pb", "List", None), ("same_img", "Boolean", False), ("diff_img", "Number", 0)]:
        f.var(n, t, "local", d)
    c.start()
    with c.prepare("Read this computer's details and every child's evidence."):
        f.act("ooa.file/write_text", desc="A small VBScript that names this computer", path="${out_dir}/computer.vbs",
              text='Set n = CreateObject("WScript.Network")\nWScript.Echo n.ComputerName')
        f.act("ooa.script/run_vbscript", desc="Read the computer name (VBScript)", file="${out_dir}/computer.vbs", out={"output": "computer"})
        f.comment("The HR team's legacy Java desk app records each readiness confirmation; the Java applications commands drive it.")
        f.act("ooa.system/get_environment_variable", desc="Where Java is", name="JAVA_HOME", out={"value": "jhome"}, cont=True)
        with f.if_({"condition": "ooa.file/exists", "args": {"path": "${base}/data/RW02/qa-desk.jar"}}, desc="Desk app available?") as hasj:
            f.act("ooa.application/start_program", desc="Start the Java desk app", program={"expr": "if(isEmpty($jhome$), 'java', $jhome$ + '/bin/java.exe')"},
                  arguments=["-jar", "${base}/data/RW02/qa-desk.jar", "${out_dir}/desk-confirmations.txt"], window_state="normal", out={"process_id": "jpid"})
            f.act("ooa.window/wait_for", desc="Wait for its window", title="OOA QA Desk", timeout_seconds=30)
            f.act("ooa.java_apps/list_apps", desc="Java programs with windows", out={"apps": "japps"})
            f.set("desk", True, desc="Desk app running")
        with f.else_(hasj):
            f.act("ooa.log/write", desc="No desk app", path="$log_file$", level="WARN", message="qa-desk.jar not built (no JDK): Java steps skipped")
        for k, code in src.items():
            with f.if_({"condition": "ooa.file/exists", "args": {"path": "${base}/output/${run_id}/" + code + "/evidence.csv"}}, desc=code + " evidence present?"):
                f.act("ooa.csv/read", desc="Read " + code + " evidence", path="${base}/output/${run_id}/" + code + "/evidence.csv", out={"table": k})
    with c.records("Per item: Workday, Entra, ServiceNow and mail side by side, the state, a clipboard check, the bundle row.",
                   summary="${rec.request_id}: ${state}\nWorkday ${wd} | Entra ${entra} | ${task}"):
        f.act("ooa.datatable/lookup", desc="Workday id", table="$b2$", column="key", value="$rec.key$", return_column="worker_id", if_not_found="", out={"value": "wd"})
        f.act("ooa.datatable/lookup", desc="Entra object id", table="$b3$", column="key", value="$rec.key$", return_column="user_id", if_not_found="", out={"value": "entra"})
        f.act("ooa.datatable/lookup", desc="ServiceNow task", table="$b4$", column="key", value="$rec.key$", return_column="task", if_not_found="", out={"value": "task"})
        f.act("ooa.datatable/lookup", desc="Mail delivered", table="$b5$", column="key", value="$rec.key$", return_column="mail_delivered", if_not_found="", out={"value": "mail"})
        f.expr("state", "if(isEmpty($wd$) and isEmpty($entra$), 'not processed', if(isEmpty($wd$) or isEmpty($entra$) or isEmpty($task$), 'mismatch', 'complete'))",
               desc="Complete, mismatch or not processed")
        with f.if_({"op": "eq", "left": "$state$", "right": "mismatch"}, desc="A mismatch?"):
            f.act("ooa.log/write", desc="Highlight it", path="$log_file$", level="WARN", message="${rec.request_id}: Workday ${wd}, Entra ${entra}, task ${task}")
        f.set("line", "${rec.request_id};${state};${wd};${entra};${task}", desc="The line")
        with f.if_("$desk$", desc="Desk app running?"):
            f.act("ooa.java_apps/object_action", desc="Java app: employee field", window_title="OOA QA Desk", role="text", name="Employee", action_="set_text",
                  value="${rec.request_id} ${rec.first} ${rec.last}")
            f.act("ooa.java_apps/object_action", desc="Java app: readiness field", window_title="OOA QA Desk", role="text", name="Readiness", action_="set_text", value="$state$")
            f.act("ooa.java_apps/object_action", desc="Java app: confirm", window_title="OOA QA Desk", role="push button", name="Confirm", action_="do_action")
            f.act("ooa.java_apps/object_action", desc="Java app: read its status", window_title="OOA QA Desk", role="label", index=4, action_="get_text",
                  out={"result": "jstatus"})
            with f.if_("not startsWith(toString($jstatus$), 'Confirmed')", desc="Not confirmed?"):
                f.act("core/throw", desc="Report it", code="VERIFY.DESK_APP", message="${rec.request_id}: the desk app says ${jstatus}")
        f.act("ooa.desktop/clipboard_set", desc="Put the line on the clipboard", text="$line$")
        f.act("ooa.desktop/clipboard_get", desc="Read it back", out={"text": "clip"})
        with f.if_({"op": "ne", "left": "$clip$", "right": "$line$"}, desc="Clipboard changed on the way?"):
            f.act("core/throw", desc="Report it", code="VERIFY.CLIPBOARD", message="The clipboard did not keep the line")
        f.act("ooa.datatable/add_row", desc="Add the bundle row", table="$bundle$",
              values={"key": "$rec.key$", "request_id": "$rec.request_id$", "action": "$rec.action$", "workday": "$wd$", "entra": "$entra$", "servicenow": "$task$",
                      "mail": "$mail$", "state": "$state$"}, out={"table": "bundle"})
        f.set("rec_note", "$state$", desc="Note for the results")
    with c.finish("The workbook (and its PDF through Excel), stored in SharePoint and read back."):
        f.set("workbook", "${out_dir}/RW02-evidence.xlsx", desc="Name of the workbook")
        f.act("ooa.excel/create_workbook", desc="Create the evidence workbook", path="$workbook$", overwrite=True, sheet="Lifecycle")
        f.act("ooa.excel/write_table", desc="Write the bundles", table="$bundle$", sheet="Lifecycle")
        f.act("ooa.excel/set_cell", desc="Note the computer", cell="J1", value="Computer: ${computer}", sheet="Lifecycle")
        f.act("ooa.excel/save", desc="Save it")
        f.act("ooa.excel/close", desc="Close it")
        f.act("ooa.excel_desktop/export_pdf", desc="Export it as PDF with Excel", path="$workbook$", target="${out_dir}/RW02-evidence.pdf")
        f.act("ooa.pdf/to_images", desc="Picture of the PDF's first page", path="${out_dir}/RW02-evidence.pdf", pages="1", target_folder="${out_dir}/pdf-a", out={"files": "pa"})
        f.act("ooa.pdf/to_images", desc="And again (a second rendering)", path="${out_dir}/RW02-evidence.pdf", pages="1", target_folder="${out_dir}/pdf-b", out={"files": "pb"})
        f.act("ooa.image/compare_images", desc="Image check: both renderings are the same picture", image="${first($pa$)}", other="${first($pb$)}", tolerance=10,
              out={"same": "same_img", "difference_percent": "diff_img"})
        with f.if_("not $same_img$", desc="Pictures differ?"):
            f.act("core/throw", desc="Report it", code="VERIFY.PDF_RENDER", message="The PDF renders differently each time (${diff_img}%)")
        with f.if_("$desk$", desc="Close the Java desk app"):
            f.act("ooa.application/stop_program", desc="Stop it", process_id="$jpid$", force=True, cont=True)
        f.act("ooa.microsoft365/upload_file", desc="Store the workbook in the QA SharePoint site", local_path="$workbook$", remote_path="OOA-QA/${run_id}/RW02-evidence.xlsx",
              drive="sites/ooa-qa/drive", access_token=cred("graph"), base_url="${standin}/graph/v1.0", out={"item_id": "item"})
        f.act("ooa.datatable/write_to_file", desc="Export the CSV", table="$bundle$", path="${out_dir}/RW02-evidence.csv")
    return c


def main():
    return Main("RW02", "Employee lifecycle", "Employee lifecycle: Workday -> Entra ID/AD -> ServiceNow -> Outlook/Teams.",
                [("rw02_b1", "RW02-B1", "HR request intake"), ("rw02_b2", "RW02-B2", "Workday record validator"),
                 ("rw02_b3", "RW02-B3", "Entra and AD identity worker"), ("rw02_b4", "RW02-B4", "IT fulfilment worker"),
                 ("rw02_b5", "RW02-B5", "Microsoft 365 access worker"), ("rw02_b6", "RW02-B6", "Windows readiness and evidence")], FOLDER).build()


CHILDREN = [b1_intake, b2_workday, b3_identity, b4_fulfilment, b5_m365, b6_readiness]
MAINS = [main]
