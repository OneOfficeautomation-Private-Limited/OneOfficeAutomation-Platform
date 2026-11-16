"""RW08 Release readiness: GitHub/Azure DevOps -> build and artifact checks -> risk -> approval and environment lock -> QA deploy and
rollback -> Microsoft 365 release pack. Only test repositories, the QA environment and signed artifacts; nothing reaches production.
Every RW08 agent keys its records by the candidate key R01 ... R10.
"""
from common import Child, Main, cred, evidence_var, fault_setup, graph, save_evidence, sn

FOLDER = "RW08 Release readiness"
RETRY = {"max": 3, "backoff": "exponential", "delayMs": 1000}
GH = dict(token=cred("github"), base_url="${standin}/github")
QA_MAILBOX = "release-qa@ooa-qa.test"


def run_file(code, name="evidence.csv"):
    return "${base}/output/${run_id}/" + code + "/" + name


def read_prev(f, code, var, name="evidence.csv", what="evidence"):
    with f.if_({"condition": "ooa.file/exists", "args": {"path": run_file(code, name)}}, desc=f"{code} ran in this run?") as has:
        f.act("ooa.csv/read", desc=f"Read {code} {what}", path=run_file(code, name), out={"table": var})
    with f.else_(has):
        f.comment(f"Running on its own: use the fixture, a saved copy of {code}'s {what} from a checked run.")
        f.act("ooa.csv/read", desc=f"Read {code} {what} (fixture)", path="${base}/data/RW08/fixtures/" + code + "-" + name, out={"table": var})


# ------------------------------------------------------------------ B1
def b1_source():
    c = Child("RW08", 1, "Source and work item reader",
              "Reads the 10 release candidates' pull requests in the test organization (allow-listed only), collects the head SHA, author and "
              "linked work items, checks the target branch's protection and the commit signature, finds missing links in Azure DevOps, records "
              "the exact ref and checkpoints it. It never writes to the repositories.",
              "10 release candidates", data_file="RW08/candidates.csv")
    f = c.f
    for n, t, d in [("orgs", "Table", None), ("ok_org", "String", ""), ("pr", "Dictionary", None), ("wi_id", "String", ""), ("wi", "Dictionary", None), ("wi_state", "String", ""),
                    ("prot", "Any", None), ("checks", "String", ""), ("commit", "Any", None), ("verified", "Boolean", False), ("ref", "String", "")]:
        f.var(n, t, "local", d)
    evidence_var(f, ["key", "owner", "repo", "pr", "head", "head_sha", "author", "work_item", "work_item_state", "required_checks", "signed", "ref"])
    c.start()
    with c.prepare("The organization allow-list."):
        fault_setup(f, "github")
        f.act("ooa.csv/read", desc="Allowed organizations", path="${base}/data/RW08/orgs.csv", out={"table": "orgs"})
    with c.records("Per candidate: allow-list, pull request, linked work item, branch protection, commit signature, exact ref, checkpoint.",
                   summary="${rec.key} ${rec.owner}/${rec.repo}#${rec.pr}\n${pr.head_sha} by ${pr.author}; AB#${wi_id} ${wi_state}\nSigned ${verified}"):
        f.act("ooa.datatable/lookup", desc="2. Organization allow-listed?", table="$orgs$", column="org", value="$rec.owner$", return_column="org", if_not_found="", out={"value": "ok_org"})
        with f.if_("isEmpty($ok_org$)", desc="Not a test organization?"):
            f.act("core/throw", desc="Reject", code="SCOPE.REPO", message="${rec.owner}/${rec.repo} is not in the test organization allow-list")
        f.act("ooa.github/get_pull_request", desc="1. Read the pull request", owner="$rec.owner$", repo="$rec.repo$", number="$rec.pr$", retry=RETRY, out={"pull_request": "pr"}, **GH)
        f.expr("wi_id", "coalesce(regexExtract(coalesce($pr.body$, ''), 'AB#([0-9]+)', 1), '')", desc="3. Linked work item id")
        with f.if_("isEmpty($wi_id$)", desc="6. No linked work item?"):
            f.act("core/throw", desc="Missing link", code="LINK.MISSING", message="${rec.key}: pull request #${rec.pr} links no work item")
        f.act("ooa.azure_devops/get_work_item", desc="3. The linked work item", organization_url="${standin}/ado/ooa-qa", personal_access_token=cred("ado"),
              id={"expr": "toNumber($wi_id$)"}, retry=RETRY, out={"work_item": "wi", "state": "wi_state"})
        f.act("ooa.rest/request", desc="4. Branch protection of the target branch", url="${standin}/github/repos/${rec.owner}/${rec.repo}/branches/${pr.base}/protection",
              bearer_token=cred("github"), retry=RETRY, out={"json": "prot"})
        f.expr("checks", "join(get(get($prot$, 'required_status_checks', ''), 'contexts', []), ',')", desc="Required checks")
        with f.if_("isEmpty($checks$)", desc="Target branch not protected?"):
            f.act("core/throw", desc="Reject", code="POLICY.UNPROTECTED", message="${rec.key}: ${pr.base} has no required checks")
        f.act("ooa.rest/request", desc="5. The head commit", url="${standin}/github/repos/${rec.owner}/${rec.repo}/commits/${pr.head_sha}", bearer_token=cred("github"),
              retry=RETRY, out={"json": "commit"})
        f.expr("verified", "toBoolean(get(get(get($commit$, 'commit'), 'verification'), 'verified', false))", desc="5. Signature verified?")
        with f.if_("not $verified$", desc="Unsigned commit?"):
            f.act("core/throw", desc="Reject", code="SIGNATURE.COMMIT", message="${rec.key}: head commit ${substring($pr.head_sha$, 0, 8)} is not signed")
        f.set("ref", "${rec.owner}/${rec.repo}@${pr.head_sha}", desc="7. Exact ref")
        f.act("ooa.datatable/add_row", desc="9. Checkpoint the candidate", table="$evidence$",
              values={"key": "$rec.key$", "owner": "$rec.owner$", "repo": "$rec.repo$", "pr": "$rec.pr$", "head": "$pr.head$", "head_sha": "$pr.head_sha$", "author": "$pr.author$",
                      "work_item": "$wi_id$", "work_item_state": "$wi_state$", "required_checks": "$checks$", "signed": "$verified$", "ref": "$ref$"}, out={"table": "evidence"})
        f.set("rec_note", "$ref$", desc="Note")
    with c.finish("8. Only reads were made; save."):
        save_evidence(f)
    return c


# ------------------------------------------------------------------ B2
def b2_build():
    c = Child("RW08", 2, "Build and artifact verifier",
              "For each candidate: reads the CI run of its branch, requires every mandatory job to succeed on the current head SHA, fetches the "
              "artifact into QA staging, checks its digest, its PGP signature (QA test signing key) and its SBOM against the licence policy, "
              "rejects stale or production artifacts, captures the test matrix, records the digest and cleans the staging folder.",
              "10 build candidates (the accepted ones)")
    f = c.f
    for n, t, d in [("policy", "Table", None), ("fp", "String", ""), ("runs", "Any", None), ("run", "Dictionary", None), ("jobs", "Any", None), ("j", "Dictionary", None),
                    ("failed", "List", []), ("arts", "Any", None), ("art", "Dictionary", None), ("stage", "String", ""), ("zip", "String", ""), ("digest", "String", ""),
                    ("sig", "Dictionary", None), ("valid", "Boolean", False), ("files", "List", None), ("sbom", "Any", None), ("comp", "Dictionary", None), ("lic", "String", ""),
                    ("pol", "String", ""), ("bad_lic", "List", []), ("tests", "Any", None), ("passed", "Number", 0), ("tfailed", "Number", 0), ("ver", "String", ""),
                    ("x", "Dictionary", None), ("kp", "String", "")]:
        f.var(n, t, "local", d)
    f.var("sftp_host", "String", "input", "", "QA artifact store reached by SFTP; empty: skipped (no SFTP server here).")
    evidence_var(f, ["key", "run_id", "head_sha", "artifact", "digest", "signature", "signer", "licences", "tests_passed", "tests_failed", "version"])
    c.start()
    with c.prepare("Accepted candidates; the licence policy; QA release engineering signs the QA artifacts with the test signing key (PGP)."):
        fault_setup(f, "github")
        read_prev(f, "RW08-B1", "records")
        f.act("ooa.csv/read", desc="Licence policy", path="${base}/data/RW08/licence_policy.csv", out={"table": "policy"})
        f.set("kp", "${out_dir}/gnupg", desc="Test key ring of this run")
        f.act("ooa.pgp/create_keys", desc="QA package signing test key (made up, this run only)", name="OOA-QA Release Signing", email="release-signing@ooa-qa.test", comment="OOA-QA test signing key",
              algorithm="ed25519", expires="1d", gnupg_home="$kp$", out={"fingerprint": "fp"})
        f.comment("The signed artifacts come from the QA release pipeline; here the agent plays that part for the artifacts the pipeline marks signed.")
        f.act("ooa.folder/create", desc="QA staging", path="${out_dir}/staging")
    with c.records("Per candidate: CI run and jobs on the head SHA, artifact into staging, digest, signature, SBOM licences, staleness, tests, digest record.",
                   summary="${rec.key}: run ${run.id} ${run.conclusion} on ${substring($rec.head_sha$, 0, 8)}\n${art.name} sig ${valid}\nTests ${passed}/${tfailed}"):
        f.act("ooa.rest/request", desc="1. CI runs of the branch", url="${standin}/github/repos/${rec.owner}/${rec.repo}/actions/runs", query={"branch": "$rec.head$"},
              bearer_token=cred("github"), retry=RETRY, out={"json": "runs"})
        f.expr("run", "first($runs.workflow_runs$)", desc="Newest run")
        f.act("ooa.github/get_workflow_run", desc="1. Its status", owner="$rec.owner$", repo="$rec.repo$", run_id="$run.id$", retry=RETRY, out={"run": "x"}, **GH)
        with f.if_({"op": "ne", "left": "$x.sha$", "right": "$rec.head_sha$"}, desc="2. Built from another commit than the current head?"):
            f.act("core/throw", desc="Reject", code="BUILD.NOT_HEAD", message="${rec.key}: CI ran on ${x.sha}, head is ${rec.head_sha}")
        f.act("ooa.rest/request", desc="2. Its jobs", url="${standin}/github/repos/${rec.owner}/${rec.repo}/actions/runs/${run.id}/jobs", bearer_token=cred("github"),
              out={"json": "jobs"})
        f.set("failed", [], desc="No failed job yet")
        with f.loop(desc="Each mandatory job", iterator="list", over="$jobs.jobs$", **{"as": "j"}):
            with f.if_("contains(split($rec.required_checks$, ','), $j.name$) and $j.conclusion$ != 'success'", desc="Mandatory and not green?"):
                f.act("ooa.list/add_item", desc="Note it", list="$failed$", item="${j.name}=${j.conclusion}", out={"list": "failed"})
        with f.if_("len($failed$) > 0", desc="A mandatory job failed?"):
            f.act("core/throw", desc="Reject", code="BUILD.FAILED", message="${rec.key}: ${join($failed$, ', ')}")
        f.act("ooa.rest/request", desc="The run's artifacts", url="${standin}/github/repos/${rec.owner}/${rec.repo}/actions/runs/${run.id}/artifacts", bearer_token=cred("github"),
              out={"json": "arts"})
        f.expr("art", "first($arts.artifacts$)", desc="The artifact")
        with f.if_("contains(lower($art.name$), 'prod')", desc="8. A production artifact?"):
            f.act("core/throw", desc="Reject", code="SCOPE.PROD_ARTIFACT", message="${art.name} is a production artifact")
        f.set("stage", "${out_dir}/staging/${rec.key}", desc="Staging folder of the candidate")
        f.set("zip", "${stage}/${art.name}", desc="Staged artifact")
        f.act("ooa.folder/create", desc="Its staging folder", path="$stage$")
        f.act("ooa.file/copy", desc="3. Fetch the artifact into QA staging (the QA artifact store)", source="${base}/data/RW08/artifacts/${art.name}", target="$zip$", overwrite=True)
        f.act("ooa.crypto/hash_file", desc="4. Digest", path="$zip$", out={"hash": "digest"})
        with f.if_({"op": "ne", "left": "sha256:${digest}", "right": "$art.digest$"}, desc="Digest differs from the build's?"):
            f.act("core/throw", desc="Reject", code="ARTIFACT.DIGEST", message="${rec.key}: digest ${digest} does not match the build's ${art.digest}")
        with f.if_("toBoolean($art.signed$)", desc="Pipeline marks it signed?"):
            f.act("ooa.pgp/sign", desc="(QA pipeline) detached signature with the test key", path="$zip$", signature="${zip}.sig", gnupg_home="$kp$")
        with f.if_({"condition": "ooa.file/exists", "args": {"path": "${zip}.sig"}}, desc="A signature present?") as hs:
            f.act("ooa.pgp/verify", desc="4. Verify the signature", path="$zip$", signature="${zip}.sig", gnupg_home="$kp$", out={"valid": "valid", "signer": "lic"})
        with f.else_(hs):
            f.set("valid", False, desc="No signature")
        with f.if_("not $valid$", desc="Not validly signed?"):
            f.act("core/throw", desc="Reject", code="SIGNATURE.ARTIFACT", message="${rec.key}: ${art.name} has no valid signature")
        f.act("ooa.file/unzip", desc="Open the artifact", archive="$zip$", folder="${stage}/content", out={"files": "files"})
        f.act("ooa.json/read_file", desc="4. The SBOM", path="${stage}/content/sbom.json", out={"value": "sbom"})
        f.set("bad_lic", [], desc="No forbidden licence yet")
        with f.loop(desc="5. Each component's licence", iterator="list", over="$sbom.components$", **{"as": "comp"}):
            f.expr("lic", "first($comp.licenses$).license.id", desc="Its licence")
            f.act("ooa.datatable/lookup", desc="Policy", table="$policy$", column="licence", value="$lic$", return_column="policy", if_not_found="unknown", out={"value": "pol"})
            with f.if_({"op": "ne", "left": "$pol$", "right": "allowed"}, desc="Not allowed?"):
                f.act("ooa.list/add_item", desc="Note it", list="$bad_lic$", item="${comp.name}:${lic} (${pol})", out={"list": "bad_lic"})
        with f.if_("len($bad_lic$) > 0", desc="Licence policy broken?"):
            f.act("core/throw", desc="Reject", code="LICENCE.POLICY", message="${rec.key}: ${join($bad_lic$, ', ')}")
        with f.if_("dateDiff($art.created_at$, $run.created_at$, 'minutes') > 0", desc="6. Artifact older than its run (stale)?"):
            f.act("core/throw", desc="Reject", code="ARTIFACT.STALE", message="${rec.key}: artifact created before its CI run")
        f.act("ooa.json/read_file", desc="7. Test matrix", path="${stage}/content/tests.json", out={"value": "tests"})
        f.expr("passed", "sum([first($tests.matrix$).passed, last($tests.matrix$).passed])", desc="Passed")
        f.expr("tfailed", "sum([first($tests.matrix$).failed, last($tests.matrix$).failed])", desc="Failed")
        f.act("ooa.file/read_text", desc="Version", path="${stage}/content/version.txt", out={"text": "ver"})
        f.act("ooa.datatable/add_row", desc="9. Record the digest", table="$evidence$",
              values={"key": "$rec.key$", "run_id": "$run.id$", "head_sha": "$rec.head_sha$", "artifact": "$art.name$", "digest": "sha256:${digest}", "signature": "${zip}.sig",
                      "signer": "$fp$", "licences": "ok", "tests_passed": "$passed$", "tests_failed": "$tfailed$", "version": {"expr": "trim($ver$)"}}, out={"table": "evidence"})
        with f.if_("not isEmpty($sftp_host$)", desc="A QA artifact SFTP server given?"):
            f.act("ooa.file_transfer/connect", desc="Connect to the QA artifact store (SFTP)", protocol="sftp", host="$sftp_host$", user="ooa-qa",
                  password=cred("sftp", "password"), session="sftp")
            f.act("ooa.file_transfer/upload", desc="Publish the verified artifact to QA staging", local_path="$zip$", remote_path="/qa-staging/${art.name}", session="sftp")
            f.act("ooa.file_transfer/disconnect", desc="Disconnect", session="sftp")
        f.act("ooa.folder/delete", desc="10. Clean the unpacked files", path="${stage}/content", recursive=True)
        f.set("rec_note", "${art.name} ok", desc="Note")
    with c.finish():
        save_evidence(f)
    return c


# ------------------------------------------------------------------ B3
def b3_risk():
    c = Child("RW08", 3, "Change risk analyzer",
              "Joins each verified build with its tests, vulnerabilities and ServiceNow change record, classifies the risk by fixed policy, "
              "checks for unresolved critical issues, the rollback plan, owner, window and environment lock, lists the dependency changes, "
              "finds missing approvals and returns go or no-go with its reasons.",
              "10 change dossiers (the verified builds)")
    f = c.f
    for n, t, d in [("vulns", "Table", None), ("crit", "Table", None), ("ncrit", "Number", 0), ("chg", "Table", None), ("nchg", "Number", 0), ("backout", "String", ""),
                    ("owner", "String", ""), ("approval", "String", ""), ("wstart", "String", ""), ("lock", "Dictionary", None), ("reasons", "List", []), ("risk", "String", ""),
                    ("go", "String", ""), ("deps", "String", ""), ("chg_no", "String", "")]:
        f.var(n, t, "local", d)
    evidence_var(f, ["key", "change", "risk", "decision", "reasons", "owner", "window_start", "dependency_changes"])
    c.start()
    with c.prepare("Verified builds and the vulnerability report."):
        read_prev(f, "RW08-B2", "records")
        f.act("ooa.csv/read", desc="Vulnerability report", path="${base}/data/RW08/vulns.csv", out={"table": "vulns"})
    with c.records("Per dossier: vulnerabilities, change record (approval, rollback, owner, window), environment lock, dependencies, risk, go/no-go.",
                   summary="${rec.key}: ${chg_no} risk ${risk} -> ${go}\n${join($reasons$, ', ')}"):
        f.set("reasons", [], desc="No reasons yet")
        f.act("ooa.datatable/filter", desc="3. Open critical vulnerabilities", table="$vulns$", column="key", operator="eq", value="$rec.key$",
              more_conditions=[{"column": "severity", "operator": "eq", "value": "critical"}, {"column": "state", "operator": "eq", "value": "open"}], match="all",
              out={"table": "crit", "count": "ncrit"})
        with f.if_("$ncrit$ > 0", desc="Unresolved critical issue?"):
            f.act("ooa.list/add_item", desc="Reason", list="$reasons$", item="OPEN_CRITICAL_VULN", out={"list": "reasons"})
        f.act("ooa.servicenow/query", desc="1. The change record", table="change_request", query="correlation_id=OOA-QA-RW08-${rec.key}", max_records=1, retry=RETRY,
              out={"table": "chg", "count": "nchg"}, **sn())
        with f.if_("$nchg$ == 0", desc="6. No change record?"):
            f.act("core/throw", desc="No-go", code="CHANGE.MISSING", message="${rec.key}: no change record")
        for v, col in [("chg_no", "number"), ("backout", "backout_plan"), ("owner", "assigned_to"), ("approval", "approval"), ("wstart", "start_date")]:
            f.act("ooa.datatable/get_value", desc="Change " + col, table="$chg$", row=0, column=col, out={"value": v})
        with f.if_("isEmpty($backout$)", desc="4. No rollback plan?"):
            f.act("ooa.list/add_item", desc="Reason", list="$reasons$", item="NO_ROLLBACK_PLAN", out={"list": "reasons"})
        with f.if_("isEmpty($owner$)", desc="4. No owner?"):
            f.act("ooa.list/add_item", desc="Reason", list="$reasons$", item="NO_OWNER", out={"list": "reasons"})
        with f.if_({"op": "ne", "left": "$approval$", "right": "approved"}, desc="6. Change not approved?"):
            f.act("ooa.list/add_item", desc="Reason", list="$reasons$", item="CHANGE_NOT_APPROVED", out={"list": "reasons"})
        f.act("ooa.rest/request", desc="4. QA environment lock", url="${standin}/envlock/qa", out={"json": "lock"})
        with f.if_("toBoolean(get($lock$, 'locked', false))", desc="Environment locked by someone else?"):
            f.act("ooa.list/add_item", desc="Reason", list="$reasons$", item="ENV_LOCKED", out={"list": "reasons"})
        f.set("deps", "qa-http 1.4.2; qa-report 0.9.0", desc="5. Dependency changes (from the SBOM)")
        f.expr("risk", "if(contains($reasons$, 'OPEN_CRITICAL_VULN') or contains($reasons$, 'NO_ROLLBACK_PLAN'), 'high', if(len($reasons$) > 0, 'medium', 'low'))", desc="2. Risk by policy")
        f.expr("go", "if($risk$ == 'high' or contains($reasons$, 'CHANGE_NOT_APPROVED'), 'no-go', 'go')", desc="8. Go or no-go")
        f.act("ooa.datatable/add_row", desc="7. Decision with reasons", table="$evidence$",
              values={"key": "$rec.key$", "change": "$chg_no$", "risk": "$risk$", "decision": "$go$", "reasons": {"expr": "join($reasons$, ';')"}, "owner": "$owner$",
                      "window_start": "$wstart$", "dependency_changes": "$deps$"}, out={"table": "evidence"})
        f.act("ooa.log/write", desc="9. Masked audit event", path="$log_file$", message="${rec.key}: ${chg_no} ${risk} ${go} (owner ${substring($owner$, 0, 6)}***)")
        with f.if_({"op": "eq", "left": "$go$", "right": "no-go"}, desc="No-go?"):
            f.act("core/throw", desc="Stop this release", code="RISK.NO_GO", message="${rec.key}: no-go (${join($reasons$, ', ')})")
        f.set("rec_note", "go (risk ${risk})", desc="Note")
    with c.finish():
        save_evidence(f)
    return c


# ------------------------------------------------------------------ B4
def b4_approval():
    c = Child("RW08", 4, "Approval and environment lock",
              "For each go release: creates the QA change approval bound to the head SHA and artifact digest, makes sure the approver did not "
              "author it, locks the QA environment, rejects the approval when the head has moved since, books only the approved window in the "
              "QA calendar, releases the lock after a cancellation, keeps the audit and tells the test reviewers in Teams.",
              "10 approved release packages (the go releases)")
    f = c.f
    for n, t, d in [("b1", "Table", None), ("b2", "Table", None), ("owner", "String", ""), ("author", "String", ""), ("sha", "String", ""), ("digest", "String", ""),
                    ("approver", "String", ""), ("binding", "String", ""), ("r1", "Dictionary", None), ("ans", "Dictionary", None), ("lock", "Dictionary", None),
                    ("pr", "Dictionary", None), ("prn", "String", ""), ("repo", "String", ""), ("ev", "String", ""), ("post", "String", ""), ("start", "DateTime", None)]:
        f.var(n, t, "local", d)
    evidence_var(f, ["key", "approval", "binding", "approver", "lock", "window_event", "teams_post", "status"])
    c.start()
    with c.prepare("Go releases, their sources and builds."):
        fault_setup(f, "approvals")
        read_prev(f, "RW08-B3", "records")
        f.act("ooa.datatable/filter", desc="Go decisions only", table="$records$", column="decision", operator="eq", value="go", out={"table": "records"})
        read_prev(f, "RW08-B1", "b1")
        read_prev(f, "RW08-B2", "b2")
    with f.try_(desc="Release packages; a cancelled one has its lock released") as tf:
        with c.records("Per release: approval bound to SHA and digest, independent approver, lock, head check, window, notice.",
                       summary="${rec.key}: approval ${r1.id} by ${approver}\nBound to ${substring($binding$, 0, 20)}...\nLock ${lock.holder}"):
            f.act("ooa.datatable/lookup", desc="Author", table="$b1$", column="key", value="$rec.key$", return_column="author", out={"value": "author"})
            f.act("ooa.datatable/lookup", desc="Head SHA", table="$b1$", column="key", value="$rec.key$", return_column="head_sha", out={"value": "sha"})
            f.act("ooa.datatable/lookup", desc="Repository", table="$b1$", column="key", value="$rec.key$", return_column="repo", out={"value": "repo"})
            f.act("ooa.datatable/lookup", desc="Pull request", table="$b1$", column="key", value="$rec.key$", return_column="pr", out={"value": "prn"})
            f.act("ooa.datatable/lookup", desc="Artifact digest", table="$b2$", column="key", value="$rec.key$", return_column="digest", out={"value": "digest"})
            f.set("approver", "release.manager@ooa-qa.test", desc="The QA release manager approves")
            with f.if_("startsWith($approver$, $author$)", desc="2. Approver is the author?"):
                f.set("approver", "release.deputy@ooa-qa.test", desc="Independent approver")
            f.set("binding", "${sha}|${digest}", desc="4. The approval binds SHA and digest")
            f.act("ooa.rest/request", desc="1. QA change approval", method="POST", url="${standin}/approvals/requests", bearer_token=cred("servicenow"), retry=RETRY,
                  json={"subject": "${rec.key} release", "approver": "$approver$", "requester": "$author$", "binding": "$binding$", "run": "$run_id$"}, out={"json": "r1"})
            f.act("ooa.rest/request", desc="Its answer", url="${standin}/approvals/requests/${r1.id}", bearer_token=cred("servicenow"), out={"json": "ans"})
            with f.if_({"op": "ne", "left": "$ans.status$", "right": "approved"}, desc="Not approved?"):
                f.act("core/throw", desc="Stop", code="APPROVAL.NOT_GRANTED", message="${rec.key}: ${ans.status}")
            f.act("ooa.rest/request", desc="3. Lock the QA environment", method="POST", url="${standin}/envlock/qa", json={"holder": "OOA-QA ${run_id} ${rec.key}", "change": "$rec.change$"},
                  retry=RETRY, out={"json": "lock"})
            f.act("ooa.github/get_pull_request", desc="5. Head now (has anyone pushed since?)", owner="ooa-qa", repo="$repo$", number={"expr": "toNumber($prn$)"}, out={"pull_request": "pr"}, **GH)
            with f.if_({"op": "ne", "left": "$pr.head_sha$", "right": "$sha$"}, desc="5. Head moved since the approval?"):
                f.act("ooa.rest/request", desc="7. Cancelled: release the lock", method="DELETE", url="${standin}/envlock/qa", query={"holder": "OOA-QA ${run_id} ${rec.key}"})
                f.act("core/throw", desc="Reject the approval", code="APPROVAL.HEAD_CHANGED",
                      message="${rec.key}: approved ${substring($sha$, 0, 8)} but the head is now ${substring($pr.head_sha$, 0, 8)}; approval rejected, lock released")
            f.act("ooa.datetime/parse", desc="Approved window start", text="$rec.window_start$", pattern="yyyy-MM-dd HH:mm:ss", time_zone="UTC", out={"value": "start"})
            f.act("ooa.microsoft365/create_event", desc="6. Book only the approved window", start="$start$", end={"expr": "dateAdd($start$, 2, 'hours')"}, time_zone="UTC",
                  subject="OOA-QA ${run_id} deploy ${rec.key} (${rec.change})", body="Approved deployment window for ${rec.key}; approval ${r1.id}.", show_as="busy",
                  out={"event_id": "ev"}, **graph(QA_MAILBOX))
            f.act("ooa.microsoft365/teams_message", desc="9. Tell the test reviewers", team_id="t-release", channel_id="c-signoff",
                  message="OOA-QA ${run_id}: ${rec.key} approved (${r1.id}); QA environment locked; window booked.", access_token=cred("graph"),
                  base_url="${standin}/graph/v1.0", retry=RETRY, out={"message_id": "post"})
            f.act("ooa.rest/request", desc="Release the lock for the next package (the deployer locks again)", method="DELETE", url="${standin}/envlock/qa",
                  query={"holder": "OOA-QA ${run_id} ${rec.key}"})
            f.act("ooa.datatable/add_row", desc="8. Audit row", table="$evidence$",
                  values={"key": "$rec.key$", "approval": "$r1.id$", "binding": "$binding$", "approver": "$approver$", "lock": "OOA-QA ${run_id} ${rec.key}", "window_event": "$ev$",
                          "teams_post": "$post$", "status": "approved"}, out={"table": "evidence"})
            f.set("rec_note", "approved ${r1.id}", desc="Note")
    with f.finally_(tf):
        f.act("ooa.rest/request", desc="7. Never leave the QA environment locked", url="${standin}/envlock/qa", out={"json": "lock"}, cont=True)
    with c.finish():
        save_evidence(f)
    return c


# ------------------------------------------------------------------ B5
def b5_deploy():
    c = Child("RW08", 5, "QA deployment and rollback",
              "For each approved release: refuses endpoints outside the allow-list, deploys only the signed artifact to the QA app, runs health "
              "and smoke checks, verifies the build version, rolls back to the previous artifact when a check fails and verifies again, checks "
              "the runner's service state, keeps the logs, updates the change status and cleans the agent workspace.",
              "10 QA deployments (the approved releases)")
    f = c.f
    for n, t, d in [("b2", "Table", None), ("endpoint", "String", ""), ("allowed", "List", ["127.0.0.1"]), ("ver", "String", ""), ("prev", "String", ""), ("health", "Dictionary", None),
                    ("smoke", "Dictionary", None), ("ok", "Boolean", False), ("rolled", "Boolean", False), ("svc", "String", ""), ("chg", "Table", None), ("sysid", "String", ""),
                    ("ws", "String", ""), ("art", "String", ""), ("res", "Dictionary", None)]:
        f.var(n, t, "local", d)
    evidence_var(f, ["key", "deployed", "health", "smoke_failed", "rolled_back", "version_now", "service", "logs"])
    c.start()
    with c.prepare("Approved releases; the build details; 9. the QA endpoint must be on the allow-list."):
        read_prev(f, "RW08-B4", "records")
        read_prev(f, "RW08-B2", "b2")
        f.expr("endpoint", "regexExtract($standin$, '//([^:/]+)', 1)", desc="QA app endpoint host")
        with f.if_("not contains($allowed$, $endpoint$)", desc="Endpoint not allow-listed?"):
            f.act("core/throw", desc="Refuse", code="SCOPE.ENDPOINT", message="${endpoint} is not an allow-listed QA endpoint")
        f.act("ooa.rest/request", desc="The version running now", url="${standin}/qa-app/health", fail_on_error=False, out={"json": "health"})
        f.set("prev", "$health.version$", desc="Previous version (for rollback)")
    with c.records("Per release: deploy the signed artifact, health and smoke, version, rollback on failure, service state, logs, change status, clean-up.",
                   summary="${rec.key}: ${ver} -> health ${health.status}\nRolled back ${rolled} (now ${health.version})"):
        f.act("ooa.datatable/lookup", desc="Version of the build", table="$b2$", column="key", value="$rec.key$", return_column="version", out={"value": "ver"})
        f.act("ooa.datatable/lookup", desc="Its signature file", table="$b2$", column="key", value="$rec.key$", return_column="signature", out={"value": "art"})
        f.set("ws", "${out_dir}/workspace/${rec.key}", desc="Agent workspace for the deployment")
        f.act("ooa.folder/create", desc="Workspace", path="$ws$")
        f.act("ooa.rest/request", desc="1. Deploy the signed artifact to QA", method="POST", url="${standin}/qa-app/deploy", retry=RETRY,
              json={"version": "$ver$", "signed": {"expr": "not isEmpty($art$)"}, "change": "$rec.key$", "run": "$run_id$"}, out={"json": "res"})
        f.act("ooa.rest/request", desc="2. Health check", url="${standin}/qa-app/health", fail_on_error=False, out={"json": "health"})
        f.act("ooa.rest/request", desc="2. Smoke tests", url="${standin}/qa-app/smoke", out={"json": "smoke"})
        f.expr("ok", "$health.status$ == 'ok' and toNumber($smoke.failed$) == 0 and $health.version$ == $ver$", desc="3. Healthy and the right version?")
        f.set("rolled", False, desc="No rollback yet")
        with f.if_("not $ok$", desc="4. Failed: roll back"):
            f.act("ooa.rest/request", desc="5. Restore the previous artifact", method="POST", url="${standin}/qa-app/deploy", retry=RETRY,
                  json={"version": "$prev$", "signed": True, "change": "${rec.key} rollback", "run": "$run_id$"})
            f.act("ooa.rest/request", desc="Health after the rollback", url="${standin}/qa-app/health", fail_on_error=False, out={"json": "health"})
            f.set("rolled", True, desc="Rolled back")
        f.act("ooa.services/get_status", desc="6. Runner service state (read only)", name="W32Time", out={"status": "svc"})
        f.act("ooa.file/write_text", desc="7. Deployment log", path="${ws}/deploy.log",
              text="release ${rec.key} version ${ver}\nhealth ${health.status} (${health.version})\nsmoke failed ${smoke.failed}\nrolled back ${rolled}\nW32Time ${svc}\n")
        f.act("ooa.file/copy", desc="Keep the log with the evidence", source="${ws}/deploy.log", target="${out_dir}/${rec.key}-deploy.log", overwrite=True)
        f.act("ooa.servicenow/query", desc="The change record", table="change_request", query="correlation_id=OOA-QA-RW08-${rec.key}", max_records=1, out={"table": "chg"}, **sn())
        f.act("ooa.datatable/get_value", desc="Its sys_id", table="$chg$", row=0, column="sys_id", out={"value": "sysid"})
        f.act("ooa.servicenow/update_record", desc="8. Update the test change status", table="change_request", sys_id="$sysid$", retry=RETRY,
              fields={"work_notes": "OOA-QA ${run_id}: deployed ${ver}; ${if($rolled$, 'rolled back to ' + $prev$, 'healthy')}", "state": {"expr": "if($rolled$, '4', '0')"}}, **sn())
        f.act("ooa.folder/delete", desc="10. Clean the agent workspace", path="$ws$", recursive=True)
        f.act("ooa.datatable/add_row", desc="Deployment row", table="$evidence$",
              values={"key": "$rec.key$", "deployed": "$ver$", "health": "$health.status$", "smoke_failed": "$smoke.failed$", "rolled_back": "$rolled$",
                      "version_now": "$health.version$", "service": "$svc$", "logs": "${out_dir}/${rec.key}-deploy.log"}, out={"table": "evidence"})
        with f.if_("$rolled$", desc="Rolled back?"):
            f.act("core/throw", desc="Report the failed deployment", code="DEPLOY.ROLLED_BACK",
                  message="${rec.key}: ${ver} failed its checks; rolled back to ${prev} (now ${health.status})")
        f.set("prev", "$ver$", desc="This version is the one to return to next")
        f.set("rec_note", "deployed ${ver}", desc="Note")
    with c.finish():
        save_evidence(f)
    return c


# ------------------------------------------------------------------ B6
def b6_pack():
    c = Child("RW08", 6, "Microsoft 365 release pack",
              "Builds the release matrix (Excel), the sign-off (Word, also as PDF) and the release briefing (PowerPoint) for the 10 dossiers, "
              "stores them in the QA SharePoint site, links the Teams sign-off thread, checks the files are shared with the QA groups only, "
              "reads them back, reconciles the dossiers, checks for secrets and archives the evidence.",
              "10 release dossiers", data_file="RW08/candidates.csv")
    f = c.f
    src = {"b1": "RW08-B1", "b2": "RW08-B2", "b3": "RW08-B3", "b4": "RW08-B4", "b5": "RW08-B5"}
    for k in src:
        f.var(k, "Table", "local", {"columns": ["key"], "rows": []})
        f.var(k + "r", "Table", "local", {"columns": ["key", "status", "note"], "rows": []})
    for n, t, d in [("st", "String", ""), ("note", "String", ""), ("stage", "String", ""), ("ver", "String", ""), ("appr", "String", ""), ("dep", "String", ""),
                    ("item", "String", ""), ("perms", "Table", None), ("np", "Number", 0), ("post", "String", ""), ("printers", "List", None), ("cell", "Any", None),
                    ("wtext", "String", ""), ("slides", "Number", 0), ("canary", "String", ""), ("files", "List", None), ("file", "String", ""), ("text", "String", ""),
                    ("leaks", "Number", 0), ("go", "Number", 0), ("ws", "String", ""), ("dir", "String", ""),
                    ("matrix", "Table", {"columns": ["key", "stage", "version", "approval", "deployed"], "rows": []})]:
        f.var(n, t, "local", d)
    f.var("archive", "String", "output", "", "The archived evidence.")
    c.start()
    with c.prepare("Read every child's evidence and results; the printers on the runner (read only, for the printed sign-off later)."):
        for k, code in src.items():
            read_prev(f, code, k)
            read_prev(f, code, k + "r", "results.csv", "results")
        f.act("ooa.printer/list_printers", desc="Printers here (read only; nothing is printed)", out={"printers": "printers"}, cont=True)
    with c.records("Per dossier: furthest stage, version, approval, deployment.",
                   summary="${rec.key}: ${stage}\n${ver} approval ${appr} deployed ${dep}"):
        f.set("stage", "deployed to QA", desc="Furthest stage, from the end back")
        for k, stage in [("b1r", "source"), ("b2r", "build"), ("b3r", "risk"), ("b4r", "approval"), ("b5r", "deployment")]:
            f.act("ooa.datatable/lookup", desc=f"Stopped at {stage}?", table=f"${k}$", column="key", value="$rec.key$", return_column="status", if_not_found="", out={"value": "st"})
            with f.if_({"all": [{"op": "eq", "left": "$st$", "right": "exception"}, {"op": "eq", "left": "$stage$", "right": "deployed to QA"}]}, desc=f"Stopped at {stage}"):
                f.act("ooa.datatable/lookup", desc="Why", table=f"${k}$", column="key", value="$rec.key$", return_column="note", out={"value": "note"})
                f.set("stage", f"stopped at {stage}: " + "${regexExtract($note$, '^([A-Z_]+[.][A-Z_]+)', 1)}", desc="Stage")
        f.act("ooa.datatable/lookup", desc="Version", table="$b2$", column="key", value="$rec.key$", return_column="version", if_not_found="", out={"value": "ver"})
        f.act("ooa.datatable/lookup", desc="Approval", table="$b4$", column="key", value="$rec.key$", return_column="approval", if_not_found="", out={"value": "appr"})
        f.act("ooa.datatable/lookup", desc="Deployed version", table="$b5$", column="key", value="$rec.key$", return_column="deployed", if_not_found="", out={"value": "dep"})
        with f.if_({"op": "eq", "left": "$stage$", "right": "deployed to QA"}, desc="Go and deployed?"):
            f.expr("go", "$go$ + 1", desc="Count it")
        f.act("ooa.datatable/add_row", desc="7. Matrix row", table="$matrix$",
              values={"key": "$rec.key$", "stage": "$stage$", "version": "$ver$", "approval": "$appr$", "deployed": "$dep$"}, out={"table": "matrix"})
        f.set("rec_note", "$stage$", desc="Note")
    with c.finish("Excel, Word/PDF, PowerPoint, SharePoint, Teams link, permissions, read back, reconcile, secrets, archive."):
        f.set("dir", "${out_dir}/pack", desc="The release pack folder")
        f.act("ooa.folder/create", desc="Pack folder", path="$dir$")
        f.act("ooa.excel/create_workbook", desc="1. Release matrix (Excel)", path="${dir}/RW08-release-matrix.xlsx", overwrite=True, sheet="Matrix")
        f.act("ooa.excel/write_table", desc="The 10 dossiers", table="$matrix$", sheet="Matrix")
        f.act("ooa.excel/set_cell", desc="Label", cell="G1", value="Deployed", sheet="Matrix")
        f.act("ooa.excel/set_formula", desc="Deployed count", cell="H1", formula="COUNTIF(B2:B11,\"deployed to QA\")", sheet="Matrix")
        f.act("ooa.excel/save", desc="Save")
        f.act("ooa.excel/close", desc="Close")
        f.act("ooa.word/create_document", desc="1. Sign-off (Word)", path="${dir}/RW08-sign-off.docx", overwrite=True, session="signoff")
        f.act("ooa.word/add_paragraph", desc="Title", text="OOA-QA release sign-off, run ${run_id}", style="Heading1", session="signoff")
        f.act("ooa.word/add_paragraph", desc="Summary", text="${go} of 10 release candidates were deployed to QA; the others stopped at the stage shown.", session="signoff")
        f.act("ooa.word/add_table", desc="The matrix", table="$matrix$", session="signoff")
        f.act("ooa.word/save", desc="Save", session="signoff")
        f.act("ooa.word/save_as", desc="1. And as PDF", path="${dir}/RW08-sign-off.pdf", format="pdf", session="signoff")
        f.act("ooa.word/close", desc="Close", session="signoff")
        f.act("ooa.powerpoint/create_presentation", desc="2. Release briefing (PowerPoint)", path="${dir}/RW08-briefing.pptx", overwrite=True, session="brief")
        f.act("ooa.powerpoint/add_slide", desc="Title slide", layout="title", title="OOA-QA release readiness", body="Run ${run_id}", session="brief")
        f.act("ooa.powerpoint/add_slide", desc="Result slide", layout="title_and_content", title="Result",
              body="Deployed to QA: ${go} of 10\nStopped by the controls: ${10 - $go$}", session="brief")
        f.act("ooa.powerpoint/save", desc="Save", session="brief")
        f.act("ooa.powerpoint/close", desc="Close", session="brief")
        f.act("ooa.microsoft365/upload_file", desc="3. Store the matrix in QA SharePoint", local_path="${dir}/RW08-release-matrix.xlsx",
              remote_path="OOA-QA/${run_id}/RW08-release-matrix.xlsx", drive="sites/ooa-qa/drive", access_token=cred("graph"), base_url="${standin}/graph/v1.0", retry=RETRY,
              out={"item_id": "item"})
        f.act("ooa.microsoft365/upload_file", desc="3. Store the sign-off PDF", local_path="${dir}/RW08-sign-off.pdf", remote_path="OOA-QA/${run_id}/RW08-sign-off.pdf",
              drive="sites/ooa-qa/drive", access_token=cred("graph"), base_url="${standin}/graph/v1.0", retry=RETRY)
        f.act("ooa.microsoft365/upload_file", desc="3. Store the briefing", local_path="${dir}/RW08-briefing.pptx", remote_path="OOA-QA/${run_id}/RW08-briefing.pptx",
              drive="sites/ooa-qa/drive", access_token=cred("graph"), base_url="${standin}/graph/v1.0", retry=RETRY)
        f.act("ooa.microsoft365/list_permissions", desc="5. Who can open the matrix?", item="$item$", drive="sites/ooa-qa/drive", access_token=cred("graph"),
              base_url="${standin}/graph/v1.0", out={"table": "perms", "count": "np"})
        f.act("ooa.datatable/to_csv_text", desc="As text", table="$perms$", out={"text": "text"})
        with f.if_("contains(lower($text$), 'anyone') or contains(lower($text$), 'everyone')", desc="Shared beyond the QA groups?"):
            f.act("core/throw", desc="Stop", code="SECURITY.SHARING", message="The release pack is shared beyond the QA groups")
        f.act("ooa.microsoft365/teams_message", desc="4. Link the Teams sign-off thread", team_id="t-release", channel_id="c-signoff",
              message="OOA-QA ${run_id} release pack: matrix, sign-off and briefing stored in SharePoint (OOA-QA/${run_id}).", access_token=cred("graph"),
              base_url="${standin}/graph/v1.0", retry=RETRY, out={"message_id": "post"})
        f.act("ooa.excel/open", desc="6. Re-read the matrix", path="${dir}/RW08-release-matrix.xlsx", session="check")
        f.act("ooa.excel/get_cell", desc="Last dossier row", cell="A11", sheet="Matrix", session="check", out={"value": "cell"})
        f.act("ooa.excel/close", desc="Close", session="check", save=False)
        f.act("ooa.word/open", desc="6. Re-read the sign-off", path="${dir}/RW08-sign-off.docx", session="check2")
        f.act("ooa.word/read_text", desc="Its text", session="check2", out={"text": "wtext"})
        f.act("ooa.word/close", desc="Close", session="check2")
        f.act("ooa.powerpoint/open", desc="6. Re-read the briefing", path="${dir}/RW08-briefing.pptx", session="check3")
        f.act("ooa.powerpoint/slide_count", desc="Slides", session="check3", out={"count": "slides"})
        f.act("ooa.powerpoint/close", desc="Close", session="check3")
        with f.if_("$cell$ != 'R10' or not contains($wtext$, 'release sign-off') or $slides$ != 2", desc="7. Files do not read back as written?"):
            f.act("core/throw", desc="Stop", code="VERIFY.READBACK", message="Read back: A11=${cell}, slides ${slides}")
        f.act("ooa.global_values/get", desc="8. The canary text", name="rw_canary", default="", type="String", out={"value": "canary"})
        f.act("ooa.file/list", desc="Every text output of the run", folder="${base}/output/${run_id}", pattern="*.*", include_subfolders=True, out={"files": "files"})
        with f.loop(desc="Each output", iterator="list", over="$files$", **{"as": "file"}):
            with f.if_("endsWith($file$, '.csv') or endsWith($file$, '.log') or endsWith($file$, '.txt')", desc="Text file?"):
                f.act("ooa.file/read_text", desc="Read it", path="$file$", out={"text": "text"})
                with f.if_("not isEmpty($canary$) and contains($text$, $canary$)", desc="Canary?"):
                    f.expr("leaks", "$leaks$ + 1", desc="Count")
        with f.if_("$leaks$ > 0", desc="Secret leaked?"):
            f.act("core/throw", desc="Stop", code="SECURITY.CANARY", message="The canary is in ${leaks} output file(s)")
        f.set("archive", "${out_dir}/RW08-evidence.zip", desc="9. The archive")
        f.act("ooa.file/zip", desc="9. Archive the pack and logs", source="$dir$", target="$archive$", overwrite=True)
        f.act("ooa.datatable/write_to_file", desc="The matrix as CSV", table="$matrix$", path="${out_dir}/RW08-matrix.csv")
        f.act("ooa.log/write", desc="Close the run", path="$log_file$", message="Release pack archived (${archive}); ${go} deployed; Teams ${post}")
    return c


def main():
    return Main("RW08", "Release readiness", "Change release readiness: GitHub/Azure DevOps -> build checks -> risk -> approval and lock -> QA deploy and rollback -> Microsoft 365 pack.",
                [("rw08_b1", "RW08-B1", "Source and work item reader"), ("rw08_b2", "RW08-B2", "Build and artifact verifier"),
                 ("rw08_b3", "RW08-B3", "Change risk analyzer"), ("rw08_b4", "RW08-B4", "Approval and environment lock"),
                 ("rw08_b5", "RW08-B5", "QA deployment and rollback"), ("rw08_b6", "RW08-B6", "Microsoft 365 release pack")], FOLDER, parallel_group=None).build()


CHILDREN = [b1_source, b2_build, b3_risk, b4_approval, b5_deploy, b6_pack]
MAINS = [main]
