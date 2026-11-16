"""Test data for RW08 Release readiness: 10 synthetic release candidates (pull requests, commits, CI runs, artifacts with an SBOM
and test results), Azure DevOps work items, ServiceNow change records, a vulnerability report and the QA app's known-bad version."""
import datetime as _dt
import hashlib
import io
import json
import zipfile

ALLOWED_ORGS = ["ooa-qa"]
# key, org/repo, PR, linked work item, signed commit, CI unit job, artifact signed, licence, critical vuln, approval head moved, bad deploy, version
CANDIDATES = [
    ("R01", "ooa-qa/app-alpha", 101, 4101, True, "success", True, "MIT", False, False, False, "2.1.0"),
    ("R02", "other-org/app-beta", 102, 4102, True, "success", True, "MIT", False, False, False, "2.2.0"),
    ("R03", "ooa-qa/app-alpha", 103, None, True, "success", True, "MIT", False, False, False, "2.3.0"),
    ("R04", "ooa-qa/app-alpha", 104, 4104, False, "success", True, "MIT", False, False, False, "2.4.0"),
    ("R05", "ooa-qa/app-alpha", 105, 4105, True, "failure", True, "MIT", False, False, False, "2.5.0"),
    ("R06", "ooa-qa/app-alpha", 106, 4106, True, "success", False, "MIT", False, False, False, "2.6.0"),
    ("R07", "ooa-qa/app-alpha", 107, 4107, True, "success", True, "AGPL-3.0", False, False, False, "2.7.0"),
    ("R08", "ooa-qa/app-alpha", 108, 4108, True, "success", True, "Apache-2.0", True, False, False, "2.8.0"),
    ("R09", "ooa-qa/app-alpha", 109, 4109, True, "success", True, "MIT", False, True, False, "2.9.0"),
    ("R10", "ooa-qa/app-alpha", 110, 4110, True, "success", True, "MIT", False, False, True, "2.10.0"),
]
LICENCE_POLICY = [("MIT", "allowed"), ("Apache-2.0", "allowed"), ("BSD-3-Clause", "allowed"), ("AGPL-3.0", "forbidden"), ("SSPL-1.0", "forbidden")]


def sha(*parts):
    return hashlib.sha1("|".join(map(str, parts)).encode()).hexdigest()


def artifact(c):
    """The build output: app files, version, an SBOM (CycloneDX-like) and the test matrix."""
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("app/app.txt", "OOA-QA demo application %s\n" % c[11])
        z.writestr("version.txt", c[11] + "\n")
        z.writestr("sbom.json", json.dumps({"bomFormat": "CycloneDX", "specVersion": "1.5", "components": [
            {"name": "qa-http", "version": "1.4.2", "licenses": [{"license": {"id": "MIT"}}]},
            {"name": "qa-report", "version": "0.9.0", "licenses": [{"license": {"id": c[7]}}]}]}, indent=1))
        z.writestr("tests.json", json.dumps({"matrix": [{"os": "windows-2022", "passed": 120, "failed": 0}, {"os": "ubuntu-22.04", "passed": 118, "failed": 0}]}))
    return buf.getvalue()


def write(w):
    for c in CANDIDATES:
        w("RW08/artifacts/%s-app-%s.zip" % (c[0], c[11]), artifact(c))
    w("RW08/candidates.csv", [(c[0], c[1].split("/")[0], c[1].split("/")[1], c[2]) for c in CANDIDATES], ["key", "owner", "repo", "pr"])
    w("RW08/orgs.csv", [(o,) for o in ALLOWED_ORGS], ["org"])
    w("RW08/licence_policy.csv", LICENCE_POLICY, ["licence", "policy"])
    w("RW08/vulns.csv", [(c[0], "CVE-2026-0%03d" % i, "critical" if c[8] else "low", "open" if c[8] else "fixed") for i, c in enumerate(CANDIDATES, 1)],
      ["key", "cve", "severity", "state"])


def seed():
    now = _dt.datetime.now(_dt.timezone.utc)
    repos, wis, changes = {}, {}, []
    for n, c in enumerate(CANDIDATES):
        key, full = c[0], c[1]
        r = repos.setdefault(full, {"pulls": {}, "commits": {}, "runs": [], "protection": {"main": {
            "required_status_checks": {"strict": True, "contexts": ["unit", "integration", "sbom"]},
            "required_pull_request_reviews": {"required_approving_review_count": 1}, "enforce_admins": {"enabled": True}}}})
        head = sha(key, "head")
        branch = "release/%s" % c[11]
        r["pulls"][str(c[2])] = {"number": c[2], "title": "Release %s" % c[11], "state": "open", "draft": False, "merged": False, "mergeable": True,
                                 "body": ("Release candidate %s. Fixes AB#%d" % (c[11], c[3])) if c[3] else "Release candidate %s." % c[11],
                                 "user": {"login": "dev-%s" % key.lower()}, "head": {"ref": branch, "sha": head}, "base": {"ref": "main"},
                                 "created_at": (now - _dt.timedelta(days=2)).strftime("%Y-%m-%dT%H:%M:%SZ"), "updated_at": now.strftime("%Y-%m-%dT%H:%M:%SZ"),
                                 "html_url": "https://github.com/%s/pull/%d" % (full, c[2])}
        if c[9]:      # someone pushes to the branch after the first read: the head moves
            r["pulls"][str(c[2])]["_move_to"] = sha(key, "newer")
        r["commits"][head] = {"sha": head, "commit": {"author": {"name": "Dev %s" % key, "email": "dev-%s@ooa-qa.test" % key.lower(),
                                                                 "date": (now - _dt.timedelta(hours=30)).strftime("%Y-%m-%dT%H:%M:%SZ")},
                                                      "verification": {"verified": c[4], "reason": "valid" if c[4] else "unsigned"}}}
        art = "%s-app-%s.zip" % (key, c[11])
        r["runs"].append({"id": 8800 + n, "name": "release-ci", "head_branch": branch, "head_sha": head, "status": "completed",
                          "conclusion": "success" if c[5] == "success" else "failure", "event": "pull_request",
                          "created_at": (now - _dt.timedelta(hours=29)).strftime("%Y-%m-%dT%H:%M:%SZ"), "html_url": "https://github.com/%s/actions/runs/%d" % (full, 8800 + n),
                          "_jobs": [{"name": "unit", "conclusion": c[5]}, {"name": "integration", "conclusion": "success"}, {"name": "sbom", "conclusion": "success"}],
                          "_artifacts": [{"id": 9900 + n, "name": art, "size_in_bytes": len(artifact(c)), "created_at": (now - _dt.timedelta(hours=28)).strftime("%Y-%m-%dT%H:%M:%SZ"),
                                          "digest": "sha256:" + hashlib.sha256(artifact(c)).hexdigest(), "signed": c[6]}]})
        if c[3]:
            wis[str(c[3])] = {"id": c[3], "rev": 3, "fields": {"System.Title": "Release %s" % c[11], "System.State": "Resolved", "System.WorkItemType": "User Story",
                                                               "System.AssignedTo": {"uniqueName": "owner-%s@ooa-qa.test" % key.lower()}}}
        changes.append({"sys_id": "%032x" % (0x8800 + n), "number": "CHG%07d" % (8800100 + n), "short_description": "OOA-QA release %s %s" % (key, c[11]),
                        "correlation_id": "OOA-QA-RW08-" + key, "approval": "approved", "backout_plan": "" if key == "R08" else "Redeploy the previous artifact",
                        "assigned_to": "owner-%s@ooa-qa.test" % key.lower(), "start_date": (now + _dt.timedelta(hours=1)).strftime("%Y-%m-%d %H:%M:%S"),
                        "end_date": (now + _dt.timedelta(hours=3)).strftime("%Y-%m-%d %H:%M:%S"), "state": "-2"})
    return {"github": {"repos": repos}, "ado": {"workitems": wis}, "servicenow": {"change_request": changes},
            "qa_app": {"version": "2.0.0", "history": [], "bad": [c[11] for c in CANDIDATES if c[10]]},
            "approvals": {"decisions": {}, "requests": []},
            "teams": {"t-release": {"displayName": "OOA-QA Release Board", "channels": {"c-signoff": {"displayName": "Sign-off", "messages": []}}}}}
