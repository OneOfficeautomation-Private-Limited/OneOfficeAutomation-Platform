"""RW07 Cloud incident: ServiceNow -> AWS EC2 (stand-in) / Windows diagnostics -> approvals -> verification -> Teams.

Read-only diagnostics first. A change needs approval, the OOA-QA tag, an allow-listed region and a health check before and after;
only start, stop and reboot of disposable QA instances; never terminate or power off; Windows checks on this computer are read-only.
Every RW07 agent keys its records by the incident key N01 ... N10.
"""
from common import Child, Main, cred, evidence_var, fault_setup, save_evidence, sn

FOLDER = "RW07 Cloud incident"
RETRY = {"max": 3, "backoff": "exponential", "delayMs": 1000}


def ec2_connect(f, region="us-east-1"):
    f.act("ooa.aws_ec2/connect", desc="Connect to EC2 (" + region + ", QA account; stand-in address)", region=region, endpoint="${standin}/ec2",
          access_key_id=cred("aws", "access_key_id"), secret_access_key=cred("aws", "secret_access_key"), retry=RETRY)


def run_file(code, name="evidence.csv"):
    return "${base}/output/${run_id}/" + code + "/" + name


def read_prev(f, code, var, name="evidence.csv", what="evidence"):
    with f.if_({"condition": "ooa.file/exists", "args": {"path": run_file(code, name)}}, desc=f"{code} ran in this run?") as has:
        f.act("ooa.csv/read", desc=f"Read {code} {what}", path=run_file(code, name), out={"table": var})
    with f.else_(has):
        f.comment(f"Running on its own: use the fixture, a saved copy of {code}'s {what} from a checked run.")
        f.act("ooa.csv/read", desc=f"Read {code} {what} (fixture)", path="${base}/data/RW07/fixtures/" + code + "-" + name, out={"table": var})


# ------------------------------------------------------------------ B1
def b1_intake():
    c = Child("RW07", 1, "Incident intake and correlation",
              "Reads the 10 QA incidents from ServiceNow and their alerts from Kafka, normalizes severity, service and time, drops repeat alerts, "
              "holds incidents inside a maintenance window, maps the affected QA resource, correlates earlier alerts, decides whether approval "
              "is needed, adds the runbook, makes the incident key and checkpoints it in the work queue.",
              "10 QA incidents", data_file="RW07/incidents.csv")
    f = c.f
    for n, t, d in [("alerts", "List", None), ("inc", "Table", None), ("i", "Dictionary", None), ("sev", "Number", 0), ("service", "String", ""), ("opened", "DateTime", None),
                    ("symptom", "String", ""), ("dkey", "String", ""), ("seen", "List", []), ("maint", "Table", None), ("mstart", "String", ""), ("mend", "String", ""),
                    ("in_maint", "Boolean", False), ("prior", "Number", 0), ("a", "Dictionary", None), ("runbooks", "Table", None), ("runbook", "String", ""),
                    ("ikey", "String", ""), ("item", "String", ""), ("kind", "String", ""), ("needs_ok", "Boolean", False), ("number", "String", "")]:
        f.var(n, t, "local", d)
    evidence_var(f, ["key", "number", "incident_key", "severity", "service", "resource", "kind", "opened", "dedupe_key", "prior_alerts", "needs_approval",
                     "runbook", "wanted_action", "queue_item"])
    c.start()
    with c.prepare("Read the alert stream (Kafka, without moving the group on), the maintenance windows and the runbooks."):
        fault_setup(f, "servicenow")
        f.act("ooa.kafka/read_messages", desc="Alerts on the QA alert topic", base_url="${standin}/kafka", access_token=cred("kafka"), topics=["ooa-qa.alerts"],
              consumer_group="rw07-intake", max_messages=100, wait_seconds=1, mark_as_read=False, retry=RETRY, out={"messages": "alerts"})
        f.act("ooa.csv/read", desc="Maintenance windows", path="${base}/data/RW07/maintenance.csv", out={"table": "maint"})
        f.act("ooa.csv/read", desc="Runbooks", path="${base}/data/RW07/runbooks.csv", out={"table": "runbooks"})
    with c.records("Per incident: read, normalize, dedupe, maintenance window, resource, earlier alerts, approval need, runbook, key, checkpoint.",
                   summary="${rec.key} SEV${sev} ${service} on ${rec.resource}\nPrior alerts ${prior}; approval needed ${needs_ok}\nKey ${ikey}"):
        f.act("ooa.servicenow/query", desc="1. Read the incident", table="incident", query="correlation_id=OOA-QA-RW07-${rec.key}", max_records=1, retry=RETRY,
              out={"table": "inc"}, **sn())
        f.act("ooa.datatable/get_value", desc="Number", table="$inc$", row=0, column="number", out={"value": "ikey"})
        f.act("ooa.datatable/get_value", desc="Impact", table="$inc$", row=0, column="impact", out={"value": "sev"})
        f.act("ooa.datatable/get_value", desc="Service", table="$inc$", row=0, column="u_service", out={"value": "service"})
        f.act("ooa.datatable/get_value", desc="Opened", table="$inc$", row=0, column="opened_at", out={"value": "item"})
        f.act("ooa.datetime/parse", desc="2. Opened time (UTC)", text="$item$", pattern="yyyy-MM-dd HH:mm:ss", time_zone="UTC", out={"value": "opened"})
        f.act("ooa.datatable/get_value", desc="Urgency", table="$inc$", row=0, column="urgency", out={"value": "item"})
        f.expr("sev", "min(toNumber($sev$), toNumber($item$))", desc="2. Severity (the higher of impact and urgency)")
        f.expr("service", "lower(trim($service$))", desc="2. Service name")
        f.expr("kind", "if(startsWith($rec.resource$, 'svc:'), 'windows', 'ec2')", desc="5. Resource kind")
        f.set("symptom", "", desc="Symptom from the alert")
        f.set("prior", 0, desc="Earlier alerts")
        with f.loop(desc="6. Alerts for this resource", iterator="list", over="$alerts$", **{"as": "a"}):
            with f.if_({"op": "eq", "left": "$a.key$", "right": "$rec.resource$"}, desc="Same resource?"):
                f.expr("prior", "$prior$ + 1", desc="Count it")
                f.set("symptom", "$a.value.symptom$", desc="Its symptom")
        f.act("ooa.crypto/hash_text", desc="3. Alert dedupe key", text="${service}|${rec.resource}|${symptom}", out={"hash": "dkey"})
        with f.if_({"op": "contains", "left": "$seen$", "right": "$dkey$"}, desc="Repeat alert?"):
            f.act("core/throw", desc="Drop the repeat", code="ROUTE.DUPLICATE_ALERT", message="${rec.key} repeats an alert already handled (${substring($dkey$, 0, 10)})")
        f.act("ooa.list/add_item", desc="Remember the key", list="$seen$", item="$dkey$", out={"list": "seen"})
        f.act("ooa.datatable/lookup", desc="4. Maintenance window start", table="$maint$", column="service", value="$service$", return_column="start", if_not_found="", out={"value": "mstart"})
        f.act("ooa.datatable/lookup", desc="4. Maintenance window end", table="$maint$", column="service", value="$service$", return_column="end", if_not_found="", out={"value": "mend"})
        f.expr("in_maint", "not isEmpty($mstart$) and dateDiff($mstart$, now(), 'minutes') >= 0 and dateDiff(now(), $mend$, 'minutes') >= 0", desc="Inside a maintenance window?")
        with f.if_("$in_maint$", desc="Planned work?"):
            f.act("core/throw", desc="Hold until the window ends", code="ROUTE.MAINTENANCE", message="${rec.key}: ${service} is in maintenance until ${mend}")
        f.expr("needs_ok", "$sev$ <= 2 or $rec.wanted_action$ != 'none'", desc="7. Approval needed (severity 1-2 or any change)")
        f.act("ooa.datatable/lookup", desc="8. Runbook", table="$runbooks$", column="service", value="$service$", return_column="url", if_not_found="", out={"value": "runbook"})
        f.set("number", "$ikey$", desc="The ServiceNow number")
        f.set("ikey", "OOA-QA-${run_id}-${rec.key}", desc="9. Incident key")
        f.act("ooa.workload/add_item", desc="10. Checkpoint in the incident queue", queue="rw07-incidents", reference="$ikey$", if_exists="skip",
              data={"key": "$rec.key$", "resource": "$rec.resource$", "severity": "$sev$"}, out={"item_id": "item"})
        f.act("ooa.datatable/add_row", desc="Incident row", table="$evidence$",
              values={"key": "$rec.key$", "number": "$number$", "incident_key": "$ikey$", "severity": "$sev$", "service": "$service$", "resource": "$rec.resource$",
                      "kind": "$kind$", "opened": "${formatDate($opened$, '%Y-%m-%dT%H:%M:%SZ')}", "dedupe_key": "$dkey$", "prior_alerts": "$prior$", "needs_approval": "$needs_ok$",
                      "runbook": "$runbook$", "wanted_action": "$rec.wanted_action$", "queue_item": "$item$"}, out={"table": "evidence"})
        f.set("rec_note", "SEV${sev} ${kind}", desc="Note")
    with c.finish():
        save_evidence(f)
    return c


# ------------------------------------------------------------------ B2
def b2_inventory():
    c = Child("RW07", 2, "Cloud inventory reader",
              "Reads each affected EC2 instance in the approved account and region: tags, state, health checks, CPU window and dependents; "
              "rejects untagged or production resources and other regions, keeps an immutable before snapshot with its hash, works out the "
              "blast radius and which action is allowed. It never changes anything.",
              "10 disposable EC2 resources (the accepted incidents)")
    f = c.f
    for n, t, d in [("regions", "Table", None), ("allowed", "Table", None), ("inst", "Table", None), ("n", "Number", 0), ("tags", "Dictionary", None), ("state", "String", ""),
                    ("az", "String", ""), ("region", "String", ""), ("rok", "String", ""), ("st", "Table", None), ("health", "String", ""), ("met", "Dictionary", None),
                    ("blast", "String", ""), ("act_ok", "String", ""), ("snap", "String", ""), ("hash", "String", "")]:
        f.var(n, t, "local", d)
    evidence_var(f, ["key", "resource", "kind", "region", "state", "health", "cpu", "baseline", "dependents", "blast_radius", "allowed_action", "before_hash", "before"])
    c.start()
    with c.prepare("Read the accepted incidents, the region allow-list and the allowed actions; connect to EC2 (read only from here on)."):
        fault_setup(f, "ec2")
        read_prev(f, "RW07-B1", "records")
        f.act("ooa.csv/read", desc="Allowed regions", path="${base}/data/RW07/regions.csv", out={"table": "regions"})
        f.act("ooa.csv/read", desc="Allowed actions", path="${base}/data/RW07/allowed_actions.csv", out={"table": "allowed"})
        ec2_connect(f)
    with c.records("Per resource: describe, QA tag, region, health, CPU window and dependents, before snapshot, blast radius, allowed action.",
                   summary="${rec.key} ${rec.resource}: ${state}/${health}\nCPU ${met.average} (baseline ${met.baseline}), dependents ${met.dependents} -> ${blast}\nAllowed: ${act_ok}"):
        with f.if_({"op": "eq", "left": "$rec.kind$", "right": "windows"}, desc="A Windows service incident (no cloud resource)?") as win:
            f.act("ooa.datatable/lookup", desc="Is a service restart an allowed action?", table="$allowed$", column="action", value="$rec.wanted_action$", return_column="kind",
                  if_not_found="", out={"value": "act_ok"})
            f.act("ooa.datatable/add_row", desc="Windows row", table="$evidence$",
                  values={"key": "$rec.key$", "resource": "$rec.resource$", "kind": "windows", "allowed_action": {"expr": "if(isEmpty($act_ok$), 'none', $rec.wanted_action$)"},
                          "blast_radius": "low"}, out={"table": "evidence"})
            f.set("rec_note", "Windows service: diagnostics in RW07-B3", desc="Note")
        with f.else_(win):
            f.act("ooa.aws_ec2/describe_instances", desc="1. Describe the instance", instance_ids=["$rec.resource$"], retry=RETRY, out={"instances": "inst", "count": "n"})
            with f.if_("$n$ == 0", desc="Not found?"):
                f.act("core/throw", desc="Reject", code="CLOUD.NOT_FOUND", message="${rec.resource} is not in the QA account")
            f.act("ooa.datatable/get_value", desc="Tags", table="$inst$", row=0, column="tags", out={"value": "tags"})
            f.act("ooa.datatable/get_value", desc="State", table="$inst$", row=0, column="state", out={"value": "state"})
            f.act("ooa.datatable/get_value", desc="Zone", table="$inst$", row=0, column="availability_zone", out={"value": "az"})
            with f.if_("get($tags$, 'OOA-QA', '') != 'true'", desc="2.-6. Not tagged OOA-QA (or production)?"):
                f.act("core/throw", desc="Reject: not a QA resource", code="SCOPE.UNTAGGED", message="${rec.resource} (${get($tags$, 'env', '?')}) has no OOA-QA tag: no action")
            f.expr("region", "substring($az$, 0, len($az$) - 1)", desc="Region from the zone")
            f.act("ooa.datatable/lookup", desc="1. Region allowed?", table="$regions$", column="region", value="$region$", return_column="region", if_not_found="", out={"value": "rok"})
            with f.if_("isEmpty($rok$)", desc="Region not on the allow-list?"):
                f.act("core/throw", desc="Reject: region", code="SCOPE.REGION", message="${rec.resource} is in ${region}, which is not allow-listed")
            f.act("ooa.aws_ec2/get_instance_status", desc="3. Health checks", instance_ids=["$rec.resource$"], retry=RETRY, out={"statuses": "st"})
            f.act("ooa.datatable/get_value", desc="Instance check", table="$st$", row=0, column="instance_status", out={"value": "health"})
            f.act("ooa.rest/request", desc="3. CPU window and dependents", url="${standin}/ec2-qa/metrics/${rec.resource}", retry=RETRY, out={"json": "met"})
            f.expr("blast", "if(toNumber($met.dependents$) >= 5, 'high', if(toNumber($met.dependents$) >= 2, 'medium', 'low'))", desc="5. Blast radius")
            f.act("ooa.datatable/lookup", desc="7. Is the wanted action allowed?", table="$allowed$", column="action", value="$rec.wanted_action$", return_column="kind",
                  if_not_found="", out={"value": "act_ok"})
            f.set("snap", "${rec.resource}|${state}|${health}|${met.average}|${jsonStringify($tags$)}", desc="4. Before snapshot")
            f.act("ooa.crypto/hash_text", desc="4. Its hash (immutable evidence)", text="$snap$", out={"hash": "hash"})
            f.act("ooa.datatable/add_row", desc="8. Evidence row", table="$evidence$",
                  values={"key": "$rec.key$", "resource": "$rec.resource$", "kind": "ec2", "region": "$region$", "state": "$state$", "health": "$health$", "cpu": "$met.average$",
                          "baseline": "$met.baseline$", "dependents": "$met.dependents$", "blast_radius": "$blast$",
                          "allowed_action": {"expr": "if(isEmpty($act_ok$), 'forbidden: ' + $rec.wanted_action$, $rec.wanted_action$)"}, "before_hash": "$hash$", "before": "$snap$"},
                  out={"table": "evidence"})
            f.set("rec_note", "${state}/${health}, blast ${blast}", desc="Note")
    with c.finish("9. Only Describe calls were made; disconnect and save."):
        f.act("ooa.aws_ec2/disconnect", desc="Disconnect")
        save_evidence(f)
    return c


DIAG = r'''param([string]$Kind, [string]$Name)
# Read-only diagnostics for RW07-B3. Only these kinds exist; there is no way to run anything else.
switch ($Kind) {
  'events'  { Get-WinEvent -LogName System -MaxEvents 5 -ErrorAction SilentlyContinue | Select-Object TimeCreated, Id, LevelDisplayName, ProviderName | ConvertTo-Json -Compress }
  'disk'    { $d = Get-PSDrive -Name C; [pscustomobject]@{ used_gb = [math]::Round($d.Used / 1GB, 1); free_gb = [math]::Round($d.Free / 1GB, 1); free_pct = [math]::Round(100 * $d.Free / ($d.Used + $d.Free), 1) } | ConvertTo-Json -Compress }
  'process' { Get-Process -Name $Name -ErrorAction SilentlyContinue | Select-Object -First 3 Id, CPU, WorkingSet64 | ConvertTo-Json -Compress }
  default   { Write-Error "kind not allowed"; exit 3 }
}
'''


# ------------------------------------------------------------------ B3
def b3_windows():
    c = Child("RW07", 3, "Windows diagnostics bot",
              "On this QA runner, read only: the service status, the latest system events, process health, disk space and uptime, through an "
              "allow-listed PowerShell script with argument arrays; keeps output and exit codes, redacts secrets, compares thresholds, takes a "
              "screenshot only on an unlocked desktop and packages the logs. It never runs a free-form shell command.",
              "10 QA Windows service incidents (the accepted incidents)")
    f = c.f
    for n, t, d in [("svc", "String", ""), ("status", "String", ""), ("kinds", "List", ["events", "disk", "process"]), ("k", "String", ""), ("out", "String", ""),
                    ("errout", "String", ""), ("code", "Number", 0), ("disk", "Any", None), ("free", "Number", 0), ("ticks", "Any", None), ("uptime_h", "Number", 0),
                    ("locked", "Boolean", False), ("desktop", "Boolean", False), ("shot", "String", ""), ("dir", "String", ""), ("zip", "String", ""), ("warn", "String", "")]:
        f.var(n, t, "local", d)
    f.var("free_pct_min", "Number", "input", 10, "Free disk space below this per cent is a finding.")
    f.var("terminal_host", "String", "input", "", "A QA terminal host for read-only checks over SSH; empty: skipped (no terminal host here).")
    f.var("term_screen", "String", "local", "")
    evidence_var(f, ["key", "service", "status", "disk_free_pct", "uptime_hours", "exit_codes", "screenshot", "package", "finding"])
    c.start()
    with c.prepare("Read the accepted incidents; write the allow-listed diagnostics script; is the desktop unlocked?"):
        read_prev(f, "RW07-B1", "records")
        f.act("ooa.file/write_text", desc="9. The one allow-listed diagnostics script", path="${out_dir}/diag.ps1", text=DIAG)
        f.expr("locked", "false", desc="Assume unlocked")
        with f.if_({"condition": "ooa.triggerloop/process_running", "args": {"process_name": "LogonUI"}}, desc="7. Is the session locked (sign-in screen running)?"):
            f.set("locked", True, desc="Locked")
        with f.if_({"condition": "ooa.triggerloop/process_running", "args": {"process_name": "explorer"}}, desc="A desktop session?"):
            f.set("desktop", True, desc="Desktop present")
        f.act("ooa.dll/call", desc="8. Uptime from kernel32 (read only)", library="kernel32.dll", function="GetTickCount64", return_type="long", calling_convention="stdcall",
              out={"result": "ticks"})
        f.expr("uptime_h", "round(toNumber($ticks$) / 3600000, 1)", desc="Uptime in hours")
        with f.if_("not isEmpty($terminal_host$)", desc="A QA terminal host given (VT over SSH)?"):
            f.act("ooa.terminal/connect", desc="Sign in to the QA terminal host", host="$terminal_host$", terminal_type="vt-ssh", user="ooa-qa",
                  password=cred("terminal", "password"), session="term")
            f.act("ooa.terminal/send_text", desc="Read-only command: uptime", text="uptime", session="term")
            f.act("ooa.terminal/send_key", desc="Enter", key="Enter", session="term")
            f.act("ooa.terminal/get_screen", desc="Read the screen", session="term", out={"text": "term_screen"})
            f.act("ooa.terminal/disconnect", desc="Sign off", session="term")
    with c.records("Per incident: service status, allow-listed diagnostics, exit codes, redaction, thresholds, screenshot if allowed, log package.",
                   summary="${rec.key}: ${svc} ${status}\nDisk free ${free}%  uptime ${uptime_h} h\n${warn}"):
        f.expr("svc", "if(startsWith($rec.resource$, 'svc:'), substring($rec.resource$, 4), 'W32Time')", desc="Service to check (the clock service for cloud incidents)")
        f.act("ooa.services/get_status", desc="1. Service status (read only)", name="$svc$", out={"status": "status"})
        f.set("dir", "${out_dir}/${rec.key}", desc="This incident's log folder")
        f.act("ooa.folder/create", desc="Its folder", path="$dir$")
        f.set("warn", "", desc="No finding yet")
        with f.loop(desc="2. Each allow-listed diagnostic", iterator="list", over="$kinds$", **{"as": "k"}):
            with f.if_("not contains(['events', 'disk', 'process'], $k$)", desc="9. Not on the allow-list?"):
                f.act("core/throw", desc="Refuse", code="DIAG.NOT_ALLOWED", message="${k} is not an allow-listed diagnostic")
            f.act("ooa.script/run_powershell", desc="2. Run it with an argument array", file="${out_dir}/diag.ps1", arguments=["-Kind", "$k$", "-Name", "spoolsv"],
                  fail_on_error=False, timeout_seconds=60, out={"output": "out", "error_output": "errout", "exit_code": "code"})
            f.act("ooa.string/replace", desc="4. Redact secrets", text={"expr": "coalesce($out$, '')"}, find="(?i)(password|token|secret)[=:]\\S+", replace_with="$1=***", regex=True,
                  out={"result": "out"})
            f.act("ooa.file/write_text", desc="3. Keep the output and exit code", path="${dir}/${k}.txt", text="exit ${code}\n${out}\n${errout}")
            with f.if_({"op": "eq", "left": "$k$", "right": "disk"}, desc="Disk numbers?"):
                f.expr("free", "toNumber(get(jsonParse($out$), 'free_pct', 0))", desc="Free space %")
        with f.if_("$free$ < $free_pct_min$", desc="5. Below the disk threshold?"):
            f.set("warn", "disk free ${free}% below ${free_pct_min}%", desc="Finding")
        with f.if_({"op": "ne", "left": "$status$", "right": "RUNNING"}, desc="Service not running?"):
            f.set("warn", "${svc} is ${status}", desc="Finding")
        f.set("shot", "", desc="No screenshot")
        with f.if_("$desktop$ and not $locked$", desc="6. Desktop and unlocked?") as d:
            f.act("ooa.desktop/capture_screen", desc="6. Screenshot for the evidence", path="${dir}/screen.png", out={"path": "shot"}, cont=True)
        with f.else_(d):
            f.act("ooa.log/write", desc="No screenshot", path="$log_file$", message="${rec.key}: no screenshot (no desktop or locked)")
        f.set("zip", "${out_dir}/${rec.key}-diagnostics.zip", desc="8. Log package")
        f.act("ooa.file/zip", desc="8. Package the logs", source="$dir$", target="$zip$", overwrite=True)
        f.act("ooa.datatable/add_row", desc="Diagnostics row", table="$evidence$",
              values={"key": "$rec.key$", "service": "$svc$", "status": "$status$", "disk_free_pct": "$free$", "uptime_hours": "$uptime_h$", "exit_codes": "$code$",
                      "screenshot": "$shot$", "package": "$zip$", "finding": {"expr": "if(isEmpty($warn$), 'none', $warn$)"}}, out={"table": "evidence"})
        f.set("rec_note", "${svc} ${status}", desc="Note")
    with c.finish():
        save_evidence(f)
    return c


# ------------------------------------------------------------------ B4
def b4_remediate():
    c = Child("RW07", 4, "Approval and remediation guard",
              "For each incident with an allowed action: writes the recommendation, asks the incident commander (and a second approver for "
              "severity 1 or a high blast radius), checks the tag and region again right before the change, then starts or reboots the "
              "disposable QA instance with a bounded retry, tracks the action id, never terminates or powers off and stops when an approval is revoked.",
              "10 approved remediation plans (the cloud resources)")
    f = c.f
    for n, t, d in [("b1", "Table", None), ("sev", "String", ""), ("rec_text", "String", ""), ("r1", "Dictionary", None), ("r2", "Dictionary", None), ("ans", "Dictionary", None),
                    ("two", "Boolean", False), ("inst", "Table", None), ("tags", "Dictionary", None), ("aid", "String", ""), ("chg", "Table", None)]:
        f.var(n, t, "local", d)
    evidence_var(f, ["key", "resource", "action", "recommendation", "approval_1", "approval_2", "action_id", "status"])
    c.start()
    with c.prepare("Cloud resources with an action to take; the incidents (severity); connect to EC2."):
        fault_setup(f, "approvals")
        read_prev(f, "RW07-B2", "records")
        f.act("ooa.datatable/filter", desc="Incidents with an action", table="$records$", column="allowed_action", operator="ne", value="none",
              more_conditions=[{"column": "allowed_action", "operator": "not_empty"}], match="all", out={"table": "records"})
        read_prev(f, "RW07-B1", "b1")
        ec2_connect(f)
    with c.records("Per plan: recommendation, forbidden actions refused, approvals (two-person when needed), re-check, act with retry, action id.",
                   summary="${rec.key}: ${rec.allowed_action} on ${rec.resource}\nApprovals ${r1.id} ${if($two$, $r2.id$, '')}\nAction ${aid}"):
        f.set("rec_text", "${rec.allowed_action} ${rec.resource} (health ${rec.health}, CPU ${rec.cpu}, blast ${rec.blast_radius})", desc="1. Recommendation")
        with f.if_("startsWith($rec.allowed_action$, 'forbidden') or contains(['terminate', 'power_off'], $rec.allowed_action$)", desc="6. Terminate or power off?"):
            f.act("core/throw", desc="Refuse", code="REMEDIATE.FORBIDDEN", message="${rec.key}: ${rec.allowed_action} is never done by an agent")
        with f.if_({"op": "eq", "left": "$rec.kind$", "right": "windows"}, desc="Windows service restart?"):
            f.act("core/throw", desc="Advise only: this runner is not a QA service host", code="REMEDIATE.ADVISE_ONLY",
                  message="${rec.key}: restart of ${rec.resource} is advised; this computer is not a disposable QA host, so nothing is restarted")
        f.act("ooa.datatable/lookup", desc="Severity", table="$b1$", column="key", value="$rec.key$", return_column="severity", out={"value": "sev"})
        f.expr("two", "toNumber($sev$) == 1 or $rec.blast_radius$ == 'high'", desc="3. Two-person rule needed?")
        f.act("ooa.rest/request", desc="2. Ask the incident commander", method="POST", url="${standin}/approvals/requests", bearer_token=cred("servicenow"), retry=RETRY,
              json={"subject": "remediate ${rec.key}", "approver": "commander.ops@ooa-qa.test", "requester": "ops-bot@ooa-qa.test", "comment": "$rec_text$", "run": "$run_id$"},
              out={"json": "r1"})
        f.act("ooa.rest/request", desc="Commander's answer", url="${standin}/approvals/requests/${r1.id}", bearer_token=cred("servicenow"), out={"json": "ans"})
        with f.if_({"op": "ne", "left": "$ans.status$", "right": "approved"}, desc="Not approved?"):
            f.act("core/throw", desc="No change", code="APPROVAL.NOT_GRANTED", message="${rec.key}: the commander answered ${ans.status}")
        with f.if_("$two$", desc="3. Second approver"):
            f.act("ooa.rest/request", desc="Ask the second approver", method="POST", url="${standin}/approvals/requests", bearer_token=cred("servicenow"), retry=RETRY,
                  json={"subject": "remediate ${rec.key}#2", "approver": "lead.sre@ooa-qa.test", "requester": "ops-bot@ooa-qa.test", "comment": "$rec_text$", "run": "$run_id$"},
                  out={"json": "r2"})
        f.act("ooa.aws_ec2/describe_instances", desc="4. Tag and region again, right before the change", instance_ids=["$rec.resource$"], retry=RETRY,
              out={"instances": "inst"})
        f.act("ooa.datatable/get_value", desc="Tags now", table="$inst$", row=0, column="tags", out={"value": "tags"})
        with f.if_("get($tags$, 'OOA-QA', '') != 'true'", desc="Tag gone?"):
            f.act("core/throw", desc="Stop", code="SCOPE.UNTAGGED", message="${rec.resource} lost its OOA-QA tag")
        with f.if_("$two$", desc="9. Second approval still standing?"):
            f.act("ooa.rest/request", desc="Re-read the second approval", url="${standin}/approvals/requests/${r2.id}", bearer_token=cred("servicenow"), out={"json": "ans"})
            with f.if_({"op": "ne", "left": "$ans.status$", "right": "approved"}, desc="Revoked or declined?"):
                f.act("core/throw", desc="Stop on revocation", code="APPROVAL.REVOKED", message="${rec.key}: the second approval is ${ans.status}; nothing changed")
        with f.switch("$rec.allowed_action$", desc="5. The allowed action") as sw:
            with f.case(sw, "reboot"):
                f.act("ooa.aws_ec2/reboot_instances", desc="Reboot the disposable QA instance", instance_ids=["$rec.resource$"], retry=RETRY)
            with f.case(sw, "start"):
                f.act("ooa.aws_ec2/start_instances", desc="Start the disposable QA instance", instance_ids=["$rec.resource$"], retry=RETRY, out={"changes": "chg"})
            with f.case(sw, "stop"):
                f.act("ooa.aws_ec2/stop_instances", desc="Stop the disposable QA instance", instance_ids=["$rec.resource$"], retry=RETRY, out={"changes": "chg"})
            with f.default(sw):
                f.act("core/throw", desc="Not an allowed action", code="REMEDIATE.FORBIDDEN", message="${rec.allowed_action} is not allowed")
        f.act("ooa.crypto/hash_text", desc="8. Action id", text="${run_id}|${rec.key}|${rec.allowed_action}|${r1.id}", out={"hash": "aid"})
        f.act("ooa.datatable/add_row", desc="Action row", table="$evidence$",
              values={"key": "$rec.key$", "resource": "$rec.resource$", "action": "$rec.allowed_action$", "recommendation": "$rec_text$", "approval_1": "$r1.id$",
                      "approval_2": {"expr": "if($two$, $r2.id$, '')"}, "action_id": {"expr": "substring($aid$, 0, 16)"}, "status": "done"}, out={"table": "evidence"})
        f.set("rec_note", "${rec.allowed_action} done", desc="Note")
    with c.finish():
        f.act("ooa.aws_ec2/disconnect", desc="Disconnect")
        save_evidence(f)
    return c


# ------------------------------------------------------------------ B5
def b5_verify():
    c = Child("RW07", 5, "Post-action verification",
              "For each remediated incident: waits for the expected state, polls the health checks, compares the CPU window with the baseline, "
              "pings and (with a lab device) reads SNMP, calls the local health address, makes sure the action ran once, cycles the instance "
              "safely if health got worse, keeps the after snapshot, updates ServiceNow and escalates what is not verified.",
              "10 remediated incidents (the remediated ones)")
    f = c.f
    f.var("snmp_host", "String", "input", "", "SNMP lab device to read (SNMPv2c sysUpTime); empty: skipped (no lab device here).")
    for n, t, d in [("b1", "Table", None), ("states", "Dictionary", None), ("st", "Table", None), ("health", "String", ""), ("tries", "Number", 0), ("met", "Dictionary", None),
                    ("ping", "Boolean", False), ("snmp", "Dictionary", None), ("local", "Dictionary", None), ("acts", "Dictionary", None), ("x", "Dictionary", None),
                    ("count", "Number", 0), ("rolled", "Boolean", False), ("after", "String", ""), ("inc", "Table", None), ("sysid", "String", ""), ("ok", "Boolean", False)]:
        f.var(n, t, "local", d)
    evidence_var(f, ["key", "resource", "state", "health", "cpu_after", "baseline", "actions_counted", "rolled_back", "after", "verified"])
    c.start()
    with c.prepare("The remediated incidents; connect to EC2."):
        read_prev(f, "RW07-B4", "records")
        read_prev(f, "RW07-B1", "b1")
        ec2_connect(f)
    with c.records("Per incident: state, health polling, metrics against baseline, ping/SNMP, local health, single action, rollback, after snapshot, ServiceNow.",
                   summary="${rec.key}: ${health}, CPU ${met.average} vs ${met.baseline}\nActions ${count}, rolled back ${rolled}\nVerified ${ok}"):
        f.act("ooa.aws_ec2/wait_for_state", desc="1. Wait until running", instance_ids=["$rec.resource$"], state="running", timeout_seconds=60, check_every_seconds=2, out={"states": "states"})
        f.set("health", "", desc="Not polled yet")
        f.set("tries", 0, desc="Count")
        with f.loop(desc="2. Poll the health checks", iterator="while", condition="$health$ != 'ok' and $tries$ < 3", max_iterations=4):
            f.expr("tries", "$tries$ + 1", desc="Count")
            f.act("ooa.aws_ec2/get_instance_status", desc="Health checks", instance_ids=["$rec.resource$"], out={"statuses": "st"})
            f.act("ooa.datatable/get_value", desc="Instance check", table="$st$", row=0, column="instance_status", out={"value": "health"})
            with f.if_({"op": "ne", "left": "$health$", "right": "ok"}, desc="Not yet ok?"):
                f.act("core/delay", desc="Wait a second", seconds=1)
        f.set("rolled", False, desc="No rollback yet")
        with f.if_({"all": [{"op": "ne", "left": "$health$", "right": "ok"}, {"op": "eq", "left": "$rec.action$", "right": "reboot"}]}, desc="7. Health got worse after a reboot?"):
            f.act("ooa.aws_ec2/stop_instances", desc="Safe cycle: stop", instance_ids=["$rec.resource$"], wait=True, timeout_seconds=60)
            f.act("ooa.aws_ec2/start_instances", desc="Safe cycle: start", instance_ids=["$rec.resource$"], wait=True, timeout_seconds=60)
            f.act("ooa.aws_ec2/get_instance_status", desc="Health after the cycle", instance_ids=["$rec.resource$"], out={"statuses": "st"})
            f.act("ooa.datatable/get_value", desc="Instance check", table="$st$", row=0, column="instance_status", out={"value": "health"})
            f.set("rolled", True, desc="Cycled")
        f.act("ooa.rest/request", desc="3. CPU window against the baseline", url="${standin}/ec2-qa/metrics/${rec.resource}", retry=RETRY, out={"json": "met"})
        f.act("ooa.network/ping", desc="4. Ping the QA network (loopback here)", host="127.0.0.1", count=1, out={"reachable": "ping"})
        with f.if_("not isEmpty($snmp_host$)", desc="4. SNMP lab device given?"):
            f.act("ooa.network/snmp_get", desc="4. SNMP GET sysUpTime", host="$snmp_host$", version="v2c", community="public", oids=["1.3.6.1.2.1.1.3.0"], out={"values": "snmp"})
        f.act("ooa.rest/request", desc="5. Local health address", url="${standin}/qa/health", out={"json": "local"})
        f.act("ooa.rest/request", desc="6. The EC2 action log", url="${standin}/ec2-qa/actions", out={"json": "acts"})
        f.set("count", 0, desc="Count this incident's actions")
        with f.loop(desc="Actions on this instance", iterator="list", over="$acts.actions$", **{"as": "x"}):
            with f.if_({"all": [{"op": "eq", "left": "$x.instance$", "right": "$rec.resource$"}, {"op": "ne", "left": "$x.action$", "right": "StopInstances"}]}, desc="A remediation on it?"):
                f.expr("count", "$count$ + 1", desc="Count")
        f.expr("ok", "$health$ == 'ok' and toNumber($met.average$) <= toNumber($met.baseline$) * 1.5 and $ping$ and $count$ <= 2", desc="Verified?")
        f.set("after", "${rec.resource}|running|${health}|${met.average}", desc="8. After snapshot")
        f.act("ooa.servicenow/query", desc="The incident", table="incident", query="correlation_id=OOA-QA-RW07-${rec.key}", max_records=1, out={"table": "inc"}, **sn())
        f.act("ooa.datatable/get_value", desc="Its sys_id", table="$inc$", row=0, column="sys_id", out={"value": "sysid"})
        f.act("ooa.servicenow/update_record", desc="9. Update ServiceNow", table="incident", sys_id="$sysid$", retry=RETRY,
              fields={"work_notes": "OOA-QA ${run_id}: ${rec.action} done; health ${health}; verified ${ok}", "state": {"expr": "if($ok$, '6', '2')"}}, **sn())
        f.act("ooa.datatable/add_row", desc="Verification row", table="$evidence$",
              values={"key": "$rec.key$", "resource": "$rec.resource$", "state": "running", "health": "$health$", "cpu_after": "$met.average$", "baseline": "$met.baseline$",
                      "actions_counted": "$count$", "rolled_back": "$rolled$", "after": "$after$", "verified": "$ok$"}, out={"table": "evidence"})
        with f.if_("not $ok$", desc="10. Not verified?"):
            f.act("core/throw", desc="Escalate", code="ESCALATE.UNVERIFIED", message="${rec.key}: still ${health} after ${rec.action}${if($rolled$, ' and a safe cycle', '')}; escalated")
        f.set("rec_note", "verified (${health})", desc="Note")
    with c.finish():
        f.act("ooa.aws_ec2/disconnect", desc="Disconnect")
        save_evidence(f)
    return c


# ------------------------------------------------------------------ B6
def b6_report():
    c = Child("RW07", 6, "Teams and evidence reporter",
              "Builds the incident timeline and the before/after workbook for the 10 incidents, attaches the sanitized evidence to each QA "
              "ticket, posts the summary to the QA Teams channel and checks it is there, reconciles the command ids and their cost, scans the "
              "outputs for the secret canary and confirms no disposable instance is left in a changed state.",
              "10 incident bundles", data_file="RW07/incidents.csv")
    f = c.f
    src = {"b1": "RW07-B1", "b2": "RW07-B2", "b3": "RW07-B3", "b4": "RW07-B4", "b5": "RW07-B5"}
    for k in src:
        f.var(k, "Table", "local", {"columns": ["key"], "rows": []})
        f.var(k + "r", "Table", "local", {"columns": ["key", "status", "note"], "rows": []})
    for n, t, d in [("st", "String", ""), ("note", "String", ""), ("stage", "String", ""), ("before", "String", ""), ("after", "String", ""), ("inc", "Table", None),
                    ("sysid", "String", ""), ("acts", "Dictionary", None), ("cost", "Number", 0), ("post", "String", ""), ("msgs", "Table", None), ("n", "Number", 0),
                    ("canary", "String", ""), ("files", "List", None), ("file", "String", ""), ("text", "String", ""), ("leaks", "Number", 0), ("inst", "Table", None),
                    ("stopped", "Table", None), ("ns", "Number", 0), ("att", "String", ""),
                    ("timeline", "Table", {"columns": ["key", "stage", "before", "after", "ticket_attachment"], "rows": []})]:
        f.var(n, t, "local", d)
    f.var("workbook", "String", "output", "", "The incident workbook.")
    c.start()
    with c.prepare("Read every child's evidence and results."):
        for k, code in src.items():
            read_prev(f, code, k)
            read_prev(f, code, k + "r", "results.csv", "results")
    with c.records("Per incident: furthest stage, before/after, sanitized evidence attached to the ticket.",
                   summary="${rec.key}: ${stage}\nBefore ${before}\nAfter ${after}"):
        f.set("stage", "remediated and verified", desc="Furthest stage, from the end back")
        for k, stage in [("b1r", "intake"), ("b2r", "inventory"), ("b4r", "remediation"), ("b5r", "verification")]:
            f.act("ooa.datatable/lookup", desc=f"Stopped at {stage}?", table=f"${k}$", column="key", value="$rec.key$", return_column="status", if_not_found="", out={"value": "st"})
            with f.if_({"all": [{"op": "eq", "left": "$st$", "right": "exception"}, {"op": "eq", "left": "$stage$", "right": "remediated and verified"}]}, desc=f"Stopped at {stage}"):
                f.act("ooa.datatable/lookup", desc="Why", table=f"${k}$", column="key", value="$rec.key$", return_column="note", out={"value": "note"})
                f.set("stage", f"stopped at {stage}: " + "${regexExtract($note$, '^([A-Z_]+[.][A-Z_]+)', 1)}", desc="Stage")
        f.act("ooa.datatable/lookup", desc="Before", table="$b2$", column="key", value="$rec.key$", return_column="before", if_not_found="", out={"value": "before"})
        f.act("ooa.datatable/lookup", desc="After", table="$b5$", column="key", value="$rec.key$", return_column="after", if_not_found="", out={"value": "after"})
        f.act("ooa.file/write_text", desc="Sanitized evidence of the incident (no secrets, no tokens)", path="${out_dir}/${rec.key}-evidence.txt",
              text="Incident ${rec.key} (${rec.resource})\nStage: ${stage}\nBefore: ${before}\nAfter: ${after}\n")
        f.act("ooa.servicenow/query", desc="The QA ticket", table="incident", query="correlation_id=OOA-QA-RW07-${rec.key}", max_records=1, out={"table": "inc"}, **sn())
        f.act("ooa.datatable/get_value", desc="Its sys_id", table="$inc$", row=0, column="sys_id", out={"value": "sysid"})
        f.act("ooa.servicenow/attach_file", desc="2. Attach the sanitized evidence", table="incident", sys_id="$sysid$", path="${out_dir}/${rec.key}-evidence.txt",
              retry=RETRY, out={"attachment_id": "att"}, **sn())
        f.act("ooa.datatable/add_row", desc="1. Timeline row", table="$timeline$",
              values={"key": "$rec.key$", "stage": "$stage$", "before": "$before$", "after": "$after$", "ticket_attachment": "$att$"}, out={"table": "timeline"})
        f.set("rec_note", "$stage$", desc="Note")
    with c.finish("Workbook, Teams post and check, command ids and cost, canary, clean-up check."):
        f.set("workbook", "${out_dir}/RW07-incidents.xlsx", desc="Name")
        f.act("ooa.excel/create_workbook", desc="1. Incident timeline and before/after", path="$workbook$", overwrite=True, sheet="Timeline")
        f.act("ooa.excel/write_table", desc="The timeline", table="$timeline$", sheet="Timeline")
        f.act("ooa.excel/add_sheet", desc="Diagnostics sheet", name="Diagnostics")
        f.act("ooa.excel/write_table", desc="Windows diagnostics", table="$b3$", sheet="Diagnostics")
        f.act("ooa.excel/save", desc="Save")
        f.act("ooa.excel/close", desc="Close")
        f.act("ooa.rest/request", desc="4. Command ids from the EC2 action log", url="${standin}/ec2-qa/actions", out={"json": "acts"})
        f.expr("cost", "round(len($acts.actions$) * 0.002, 3)", desc="4. Cost estimate (API calls x unit price)")
        f.act("ooa.microsoft365/teams_message", desc="3. Post the summary to the QA Teams channel", team_id="t-ops", channel_id="c-incidents",
              message="OOA-QA ${run_id} RW07: 10 incidents reviewed; ${len($acts.actions$)} EC2 actions; est. cost ${cost} USD; workbook ${workbook}",
              access_token=cred("graph"), base_url="${standin}/graph/v1.0", retry=RETRY, out={"message_id": "post"})
        f.act("ooa.microsoft365/get_channel_messages", desc="5. Is the post there?", team_id="t-ops", channel_id="c-incidents", access_token=cred("graph"),
              base_url="${standin}/graph/v1.0", out={"table": "msgs", "count": "n"})
        with f.if_("$n$ == 0", desc="Post missing?"):
            f.act("core/throw", desc="Stop", code="VERIFY.TEAMS", message="The Teams summary is not in the channel")
        f.act("ooa.global_values/get", desc="6. The canary text", name="rw_canary", default="", type="String", out={"value": "canary"})
        f.act("ooa.file/list", desc="Every text output of the run", folder="${base}/output/${run_id}", pattern="*.txt", include_subfolders=True, out={"files": "files"})
        with f.loop(desc="Each output file", iterator="list", over="$files$", **{"as": "file"}):
            f.act("ooa.file/read_text", desc="Read it", path="$file$", out={"text": "text"})
            with f.if_("not isEmpty($canary$) and contains($text$, $canary$)", desc="Canary?"):
                f.expr("leaks", "$leaks$ + 1", desc="Count")
        with f.if_("$leaks$ > 0", desc="Secret leaked?"):
            f.act("core/throw", desc="Stop", code="SECURITY.CANARY", message="The canary is in ${leaks} output file(s)")
        ec2_connect(f)
        f.act("ooa.aws_ec2/describe_instances", desc="7. QA instances left stopped?", filters={"tag:OOA-QA": ["true"], "instance-state-name": ["stopped"]}, out={"count": "ns"})
        f.act("ooa.aws_ec2/disconnect", desc="Disconnect")
        with f.if_("$ns$ > 0", desc="A disposable instance left stopped?"):
            f.act("core/throw", desc="Stop", code="CLEANUP.LEFT_STOPPED", message="${ns} QA instance(s) are still stopped")
        f.act("ooa.datatable/write_to_file", desc="9. Checkpoint: the timeline as CSV", table="$timeline$", path="${out_dir}/RW07-timeline.csv")
        f.act("ooa.log/write", desc="8. Close the run (masked audit)", path="$log_file$", message="RW07 report: ${len($acts.actions$)} EC2 actions, cost ${cost}; Teams post ${post}")
    return c


def main():
    return Main("RW07", "Cloud incident", "Cloud operations incident: ServiceNow -> AWS EC2 / Windows diagnostics -> approvals -> verification -> Teams.",
                [("rw07_b1", "RW07-B1", "Incident intake and correlation"), ("rw07_b2", "RW07-B2", "Cloud inventory reader"),
                 ("rw07_b3", "RW07-B3", "Windows diagnostics bot"), ("rw07_b4", "RW07-B4", "Approval and remediation guard"),
                 ("rw07_b5", "RW07-B5", "Post-action verification"), ("rw07_b6", "RW07-B6", "Teams and evidence reporter")], FOLDER, parallel_group=(1, 3)).build()


CHILDREN = [b1_intake, b2_inventory, b3_windows, b4_remediate, b5_verify, b6_report]
MAINS = [main]
