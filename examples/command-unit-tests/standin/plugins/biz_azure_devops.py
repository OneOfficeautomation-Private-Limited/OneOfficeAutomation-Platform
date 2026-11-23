"""Azure DevOps for the command unit tests, under /biz/azure_devops/<org>: projects, work items (WIQL, comments,
attachments), Git repositories and pull requests, pipelines and runs, build definitions and builds, test plans
(plans, suites, test cases, points, runs, results), sprints and identities. Made-up organization utorg, project
"UT Portal". Sign-in: a personal access token as Basic (empty user) or a Bearer token."""
import copy
import re
import uuid

P = "/biz/azure_devops"
REPO_ID = "6f1c0a52-0000-4000-8000-00000000a001"
IT1 = "a1b2c3d4-0000-4000-8000-000000000101"
IT2 = "a1b2c3d4-0000-4000-8000-000000000102"

SEED = {"biz_azure_devops": {
    "next": 5000,
    "projects": [{"id": "0d1e2f30-0000-4000-8000-0000000000aa", "name": "UT Portal", "state": "wellFormed", "visibility": "private",
                  "description": "Made-up project for the unit tests", "lastUpdateTime": "2026-11-01T08:00:00Z",
                  "defaultTeam": {"name": "UT Portal Team"}}],
    "workitems": {
        "4101": {"id": 4101, "rev": 3, "fields": {"System.WorkItemType": "User Story", "System.Title": "Export invoices to PDF", "System.State": "Active",
                                                   "System.AssignedTo": {"displayName": "Ana Lima", "uniqueName": "ana.lima@ut.example"},
                                                   "System.AreaPath": "UT Portal", "System.IterationPath": "UT Portal\\Sprint 2", "System.Tags": "ut-seed",
                                                   "Microsoft.VSTS.Common.Priority": 2, "System.CreatedBy": {"displayName": "Ben Ode"},
                                                   "System.CreatedDate": "2026-11-01T08:00:00Z", "System.ChangedDate": "2026-11-02T08:00:00Z"}},
        "4102": {"id": 4102, "rev": 1, "fields": {"System.WorkItemType": "Bug", "System.Title": "Totals rounded wrongly", "System.State": "New",
                                                   "System.AreaPath": "UT Portal", "System.IterationPath": "UT Portal\\Sprint 2", "System.Tags": "ut-seed"}},
    },
    "comments": {},
    "repos": [{"id": REPO_ID, "name": "ut-portal-api", "defaultBranch": "refs/heads/main", "size": 20480,
               "webUrl": "https://ut.example/utorg/UT%20Portal/_git/ut-portal-api", "remoteUrl": "https://ut.example/utorg/_git/ut-portal-api"}],
    "pulls": [
        {"pullRequestId": 31, "title": "Release 2026.11", "description": "Seeded", "status": "active", "isDraft": False, "mergeStatus": "succeeded",
         "createdBy": {"displayName": "Ana Lima"}, "sourceRefName": "refs/heads/feature/ut-release", "targetRefName": "refs/heads/release/2026",
         "creationDate": "2026-11-10T08:00:00Z", "repository": {"name": "ut-portal-api"}, "lastMergeSourceCommit": {"commitId": "c0ffee01"}},
        {"pullRequestId": 32, "title": "Old fix", "description": "Seeded", "status": "completed", "isDraft": False, "mergeStatus": "succeeded",
         "createdBy": {"displayName": "Ben Ode"}, "sourceRefName": "refs/heads/fix/old", "targetRefName": "refs/heads/release/2026",
         "creationDate": "2026-10-10T08:00:00Z", "closedDate": "2026-10-11T08:00:00Z", "repository": {"name": "ut-portal-api"}, "lastMergeSourceCommit": {"commitId": "c0ffee02"}},
    ],
    "threads": {},
    "pipelines": [{"id": 12, "name": "ut-portal-ci", "folder": "\\", "revision": 1,
                   "configuration": {"type": "yaml", "path": "/azure-pipelines.yml", "repository": {"id": REPO_ID, "name": "ut-portal-api", "type": "azureReposGit"}}}],
    "runs": {"12": [{"id": 700, "name": "20261101.1", "state": "completed", "result": "succeeded", "createdDate": "2026-11-01T08:00:00Z", "finishedDate": "2026-11-01T08:05:00Z"},
                    {"id": 701, "name": "20261102.1", "state": "completed", "result": "failed", "createdDate": "2026-11-02T08:00:00Z", "finishedDate": "2026-11-02T08:05:00Z"},
                    {"id": 702, "name": "20261103.1", "state": "inProgress", "result": None, "createdDate": "2026-11-03T08:00:00Z", "finishedDate": None}]},
    "builds": [{"id": 900, "buildNumber": "20261101.1", "status": "completed", "result": "succeeded", "definition": {"id": 12, "name": "ut-portal-ci"},
                "sourceBranch": "refs/heads/main", "requestedFor": {"displayName": "Ana Lima"}, "queueTime": "2026-11-01T08:00:00Z",
                "startTime": "2026-11-01T08:00:10Z", "finishTime": "2026-11-01T08:05:00Z"}],
    "plans": [], "suites": [], "suite_cases": {}, "points": [], "testruns": [],
    "identities": [{"id": "1d000000-0000-4000-8000-000000000001", "providerDisplayName": "Ana Lima", "isContainer": False,
                    "properties": {"Mail": {"$value": "ana.lima@ut.example"}, "Account": {"$value": "ana.lima@ut.example"}}},
                   {"id": "1d000000-0000-4000-8000-000000000002", "providerDisplayName": "Ben Ode", "isContainer": False,
                    "properties": {"Mail": {"$value": "ben.ode@ut.example"}, "Account": {"$value": "ben.ode@ut.example"}}}],
    "iterations": [{"id": IT1, "name": "Sprint 1", "path": "UT Portal\\Sprint 1", "attributes": {"startDate": "2026-10-19T00:00:00Z", "finishDate": "2026-10-30T00:00:00Z", "timeFrame": "past"}},
                   {"id": IT2, "name": "Sprint 2", "path": "UT Portal\\Sprint 2", "attributes": {"startDate": "2026-11-16T00:00:00Z", "finishDate": "2026-11-27T00:00:00Z", "timeFrame": "current"}}],
    "iteration_items": {IT2: [4101, 4102]},
}}

BASE_URL = "https://ut.example/utorg"


def _st():
    return core.STATE.setdefault("biz_azure_devops", copy.deepcopy(SEED["biz_azure_devops"]))


def _next(st):
    st["next"] += 1
    return st["next"]


def _authorized(h):
    a = h.headers.get("Authorization", "")
    if not (a.startswith("Basic ") or a.startswith("Bearer ")) or len(a) < 10:
        h.send(203, "<html><body>Sign in</body></html>", ctype="text/html")
        return False
    return True


def _wi_out(w):
    o = copy.deepcopy(w)
    o["_links"] = {"html": {"href": "%s/UT%%20Portal/_workitems/edit/%s" % (BASE_URL, w["id"])}}
    o.pop("_deleted", None)
    return o


def _apply_ops(w, ops):
    for op in ops or []:
        path, val = op.get("path", ""), op.get("value")
        if path.startswith("/fields/"):
            w["fields"][path[len("/fields/"):]] = val
        elif path.startswith("/relations"):
            w.setdefault("relations", []).append(val)


def _wiql(st, query):
    conds = re.findall(r"\[([\w.]+)\]\s*(=|CONTAINS)\s*'([^']*)'", query, re.I)
    out = []
    for w in st["workitems"].values():
        if w.get("_deleted"):
            continue
        ok = True
        for f, op, v in conds:
            if f == "System.TeamProject":
                continue
            cur = str(w["fields"].get(f, ""))
            ok = ok and (v.lower() in cur.lower() if op.upper() == "CONTAINS" else cur == v)
        if ok:
            out.append({"id": w["id"], "url": ""})
    return out


def _plan_out(st, p):
    return p


def ado(h, m, path, q):
    st = _st()
    rest = path[len(P):]
    mo = re.match(r"/([^/]+)((?:/[^/]+)*?)/_apis/(.*)$", rest)
    if not mo:
        raise KeyError(path)
    if not _authorized(h):
        return
    scope = [s for s in mo.group(2).split("/") if s]          # [project] or [project, team]
    api = mo.group(3)
    al = api.lower()
    with core.LOCK:
        # ---- identities (the collection itself on Azure DevOps Server)
        if al == "identities" and m == "GET":
            fv = q.get("filterValue", "").lower()
            found = [i for i in st["identities"] if fv in (i["providerDisplayName"].lower(), i["properties"]["Mail"]["$value"].lower())]
            return h.send(200, {"count": len(found), "value": found})
        # ---- projects
        if al == "projects" and m == "GET":
            return h.send(200, {"count": len(st["projects"]), "value": st["projects"]})
        mp = re.match(r"projects/([^/]+)$", api)
        if mp and m == "GET":
            pr = next((x for x in st["projects"] if x["name"] == mp.group(1) or x["id"] == mp.group(1)), None)
            if pr is None:
                return h.send(404, {"message": "TF200016: The project does not exist"})
            return h.send(200, dict(pr, _links={"web": {"href": BASE_URL + "/UT%20Portal"}}))
        # ---- work items
        mc = re.match(r"wit/workitems/\$(.+)$", api, re.I)
        if mc and m == "POST":
            wid = _next(st)
            w = {"id": wid, "rev": 1, "fields": {"System.WorkItemType": mc.group(1), "System.State": "New", "System.TeamProject": scope[0] if scope else "",
                                                 "System.CreatedDate": core.now_iso(), "System.ChangedDate": core.now_iso()}}
            _apply_ops(w, h.body())
            st["workitems"][str(wid)] = w
            return h.send(200, _wi_out(w))
        mw = re.match(r"wit/workitems/(\d+)$", api, re.I)
        if mw:
            w = st["workitems"].get(mw.group(1))
            if w is None or w.get("_deleted"):
                return h.send(404, {"message": "TF401232: Work item %s does not exist." % mw.group(1)})
            if m == "GET":
                return h.send(200, _wi_out(w))
            if m == "PATCH":
                _apply_ops(w, h.body())
                w["rev"] += 1
                w["fields"]["System.ChangedDate"] = core.now_iso()
                return h.send(200, _wi_out(w))
            if m == "DELETE":
                if q.get("destroy") == "true":
                    del st["workitems"][mw.group(1)]
                else:
                    w["_deleted"] = True
                return h.send(200, {"id": w["id"], "code": 200})
        if al == "wit/wiql" and m == "POST":
            return h.send(200, {"queryType": "flat", "workItems": _wiql(st, h.body().get("query", ""))})
        if al == "wit/workitemsbatch" and m == "POST":
            b = h.body()
            out = []
            for i in b.get("ids", []):
                w = st["workitems"].get(str(i))
                if w:
                    out.append({"id": w["id"], "fields": {k: w["fields"].get(k) for k in b.get("fields", []) if k in w["fields"] or k == "System.Id"}})
            return h.send(200, {"count": len(out), "value": out})
        mcm = re.match(r"wit/workItems/(\d+)/comments$", api, re.I)
        if mcm:
            cs = st["comments"].setdefault(mcm.group(1), [])
            if m == "POST":
                c = {"id": len(cs) + 1, "text": h.body().get("text", ""), "createdBy": {"displayName": "UT Agent"}, "createdDate": core.now_iso()}
                cs.insert(0, c)
                return h.send(200, c)
            return h.send(200, {"totalCount": len(cs), "count": len(cs), "comments": cs})
        if al == "wit/attachments" and m == "POST":
            aid = str(uuid.uuid4())
            st.setdefault("attachments", {})[aid] = {"fileName": q.get("fileName"), "size": len(h.raw())}
            return h.send(201, {"id": aid, "url": "%s/_apis/wit/attachments/%s?fileName=%s" % (BASE_URL, aid, q.get("fileName", ""))})
        # ---- repos and pull requests
        if al == "git/repositories" and m == "GET":
            return h.send(200, {"count": len(st["repos"]), "value": st["repos"]})
        mr = re.match(r"git/repositories/([^/]+)/pullrequests(?:/(\d+))?(/threads)?$", api, re.I)
        if mr:
            if not any(r["name"] == mr.group(1) or r["id"] == mr.group(1) for r in st["repos"]):
                return h.send(404, {"message": "TF401019: The Git repository does not exist"})
            if mr.group(2) is None and m == "GET":
                s, t = q.get("searchCriteria.status", "active"), q.get("searchCriteria.targetRefName")
                found = [p for p in st["pulls"] if (s == "all" or p["status"] == s) and (not t or p["targetRefName"] == t)]
                skip, top = int(q.get("$skip", 0)), int(q.get("$top", 100))
                return h.send(200, {"count": len(found), "value": found[skip:skip + top]})
            if mr.group(2) is None and m == "POST":
                b = h.body()
                pr = {"pullRequestId": 100 + len(st["pulls"]), "title": b.get("title"), "description": b.get("description"), "status": "active",
                      "isDraft": b.get("isDraft", False), "mergeStatus": "queued", "createdBy": {"displayName": "UT Agent"},
                      "sourceRefName": b.get("sourceRefName"), "targetRefName": b.get("targetRefName"), "creationDate": core.now_iso(),
                      "repository": {"name": mr.group(1)}, "lastMergeSourceCommit": {"commitId": uuid.uuid4().hex[:8]},
                      "workItemRefs": b.get("workItemRefs", [])}
                st["pulls"].append(pr)
                return h.send(201, pr)
            pr = next((p for p in st["pulls"] if str(p["pullRequestId"]) == mr.group(2)), None)
            if pr is None:
                return h.send(404, {"message": "TF401180: The requested pull request was not found."})
            if mr.group(3):
                th = st["threads"].setdefault(mr.group(2), [])
                t = {"id": len(th) + 1, "comments": h.body().get("comments", []), "status": "active"}
                th.append(t)
                return h.send(200, t)
            if m == "GET":
                return h.send(200, pr)
            if m == "PATCH":
                b = h.body()
                if (b.get("lastMergeSourceCommit") or {}).get("commitId") != pr["lastMergeSourceCommit"]["commitId"]:
                    return h.send(409, {"message": "The last merge source commit does not match"})
                pr.update(status=b.get("status", pr["status"]), mergeStatus="succeeded", closedDate=core.now_iso(), completionOptions=b.get("completionOptions"))
                return h.send(200, pr)
        # ---- pipelines
        if al == "pipelines":
            if m == "GET":
                return h.send(200, {"count": len(st["pipelines"]), "value": st["pipelines"]})
            b = h.body()
            pid = _next(st)
            pl = {"id": pid, "name": b.get("name"), "folder": b.get("folder"), "revision": 1, "configuration": b.get("configuration")}
            st["pipelines"].append(pl)
            return h.send(200, pl)
        mpl = re.match(r"pipelines/(\d+)(?:/runs(?:/(\d+))?)?$", api)
        if mpl:
            pl = next((x for x in st["pipelines"] if x["id"] == int(mpl.group(1))), None)
            if pl is None:
                return h.send(404, {"message": "Pipeline %s not found" % mpl.group(1)})
            runs = st["runs"].setdefault(str(pl["id"]), [])
            if api.endswith("/runs") and m == "GET":
                return h.send(200, {"count": len(runs), "value": [dict(r, _links={"web": {"href": BASE_URL + "/_build/results?buildId=%d" % r["id"]}}) for r in reversed(runs)]})
            if api.endswith("/runs") and m == "POST":
                b = h.body()
                r = {"id": _next(st), "name": "ut-run", "state": "inProgress", "result": None, "createdDate": core.now_iso(), "finishedDate": None, "_body": b, "_reads": 0}
                runs.append(r)
                return h.send(200, dict({k: v for k, v in r.items() if not k.startswith("_")}, _links={"web": {"href": BASE_URL + "/_build/results?buildId=%d" % r["id"]}}))
            if mpl.group(2):
                r = next((x for x in runs if x["id"] == int(mpl.group(2))), None)
                if r is None:
                    return h.send(404, {"message": "Run not found"})
                if r.get("state") == "inProgress" and "_reads" in r:          # a started run finishes once it has been read
                    r["_reads"] = r.get("_reads", 0) + 1
                    if r["_reads"] > 1:
                        r.update(state="completed", result="succeeded", finishedDate=core.now_iso())
                return h.send(200, dict({k: v for k, v in r.items() if not k.startswith("_")}, _links={"web": {"href": BASE_URL + "/_build/results?buildId=%d" % r["id"]}}))
            return h.send(200, dict(pl, _links={"web": {"href": BASE_URL + "/_build?definitionId=%d" % pl["id"]}}))
        mbd = re.match(r"build/definitions/(\d+)$", api)
        if mbd:
            pl = next((x for x in st["pipelines"] if x["id"] == int(mbd.group(1))), None)
            if pl is None:
                return h.send(404, {"message": "Definition not found"})
            if m == "GET":
                return h.send(200, {"id": pl["id"], "name": pl["name"], "path": pl["folder"], "revision": pl["revision"],
                                    "process": {"type": 2, "yamlFilename": pl["configuration"]["path"]},
                                    "repository": {"id": pl["configuration"]["repository"]["id"], "name": "ut-portal-api", "type": "TfsGit"}})
            if m == "PUT":
                b = h.body()
                pl.update(name=b.get("name", pl["name"]), folder=b.get("path", pl["folder"]), revision=pl["revision"] + 1)
                pl["configuration"] = dict(pl["configuration"], path=(b.get("process") or {}).get("yamlFilename", pl["configuration"]["path"]),
                                           repository=dict(pl["configuration"]["repository"], id=(b.get("repository") or {}).get("id", pl["configuration"]["repository"]["id"])))
                return h.send(200, b)
            if m == "DELETE":
                st["pipelines"].remove(pl)
                return h.send(204)
        # ---- builds
        if al == "build/builds":
            if m == "POST":
                b = h.body()
                d = next((x for x in st["pipelines"] if x["id"] == (b.get("definition") or {}).get("id")), None)
                if d is None:
                    return h.send(400, {"message": "Definition not found"})
                bid = _next(st)
                bl = {"id": bid, "buildNumber": "20261123.%d" % bid, "status": "notStarted", "result": None, "definition": {"id": d["id"], "name": d["name"]},
                      "sourceBranch": b.get("sourceBranch", "refs/heads/main"), "requestedFor": {"displayName": "UT Agent"}, "queueTime": core.now_iso(),
                      "parameters": b.get("parameters")}
                st["builds"].append(bl)
                return h.send(200, dict(bl, _links={"web": {"href": BASE_URL + "/_build/results?buildId=%d" % bid}}))
            found = [b for b in st["builds"] if (not q.get("definitions") or str(b["definition"]["id"]) == q["definitions"])
                     and (not q.get("branchName") or b["sourceBranch"] == q["branchName"])
                     and (not q.get("statusFilter") or b["status"] == q["statusFilter"]) and (not q.get("resultFilter") or b["result"] == q["resultFilter"])]
            return h.send(200, {"count": len(found), "value": list(reversed(found))[:int(q.get("$top", 50))]})
        mb = re.match(r"build/builds/(\d+)$", api)
        if mb and m == "GET":
            bl = next((x for x in st["builds"] if x["id"] == int(mb.group(1))), None)
            return h.send(200, dict(bl, _links={"web": {"href": BASE_URL + "/_build/results?buildId=%d" % bl["id"]}})) if bl else h.send(404, {"message": "Build not found"})
        # ---- test plans
        if al == "testplan/plans":
            if m == "GET":
                found = [p for p in st["plans"] if (q.get("filterActivePlans") != "true" or p["state"] == "Active")
                         and (not q.get("owner") or q["owner"].lower() in (p["owner"]["displayName"].lower(), p["owner"]["uniqueName"].lower()))]
                return h.send(200, {"count": len(found), "value": found})
            b = h.body()
            pid, sid = _next(st), _next(st)
            p = {"id": pid, "name": b.get("name"), "state": "Active", "areaPath": b.get("areaPath", "UT Portal"), "iteration": b.get("iteration", "UT Portal"),
                 "description": b.get("description"), "startDate": b.get("startDate"), "endDate": b.get("endDate"),
                 "rootSuite": {"id": sid}, "owner": {"displayName": "UT Agent", "uniqueName": "ut.agent@ut.example"}}
            st["plans"].append(p)
            st["suites"].append({"id": sid, "plan": pid, "name": b.get("name"), "suiteType": "staticTestSuite", "parentSuite": None})
            return h.send(200, p)
        mtp = re.match(r"testplan/plans/(\d+)$", api, re.I)
        if mtp:
            p = next((x for x in st["plans"] if x["id"] == int(mtp.group(1))), None)
            if p is None:
                return h.send(404, {"message": "Test plan %s not found" % mtp.group(1)})
            if m == "GET":
                return h.send(200, p)
            if m == "PATCH":
                p.update(h.body())
                return h.send(200, p)
            if m == "DELETE":
                st["plans"].remove(p)
                st["suites"] = [s for s in st["suites"] if s["plan"] != p["id"]]
                return h.send(204)
        ms = re.match(r"testplan/plans/(\d+)/suites(?:/(\d+))?$", api, re.I)
        if ms:
            plan = int(ms.group(1))
            if not any(x["id"] == plan for x in st["plans"]):
                return h.send(404, {"message": "Test plan %d not found" % plan})
            if ms.group(2) is None:
                if m == "GET":
                    found = [_suite_out(st, s) for s in st["suites"] if s["plan"] == plan]
                    return h.send(200, {"count": len(found), "value": found})
                b = h.body()
                s = {"id": _next(st), "plan": plan, "name": b.get("name"), "suiteType": b.get("suiteType"), "parentSuite": b.get("parentSuite"),
                     "requirementId": b.get("requirementId"), "queryString": b.get("queryString"), "inheritDefaultConfigurations": True}
                st["suites"].append(s)
                return h.send(200, _suite_out(st, s))
            s = next((x for x in st["suites"] if x["id"] == int(ms.group(2)) and x["plan"] == plan), None)
            if s is None:
                return h.send(404, {"message": "Test suite %s not found" % ms.group(2)})
            if m == "GET":
                return h.send(200, _suite_out(st, s))
            if m == "PATCH":
                s.update(h.body())
                return h.send(200, _suite_out(st, s))
            if m == "DELETE":
                st["suites"].remove(s)
                return h.send(204)
        mtc = re.match(r"testplan/plans/(\d+)/suites/(\d+)/(testcase|testpoint)$", api, re.I)
        if mtc:
            plan, suite, what = int(mtc.group(1)), int(mtc.group(2)), mtc.group(3).lower()
            if not any(x["id"] == suite and x["plan"] == plan for x in st["suites"]):
                return h.send(404, {"message": "Test suite %d not found" % suite})
            cases = st["suite_cases"].setdefault(str(suite), [])
            pts = [p for p in st["points"] if p["suite"] == suite]
            if what == "testcase":
                if m == "POST":
                    added = []
                    for item in h.body():
                        wid = (item.get("workItem") or {}).get("id")
                        if str(wid) not in st["workitems"]:
                            return h.send(404, {"message": "Work item %s not found" % wid})
                        if wid not in cases:
                            cases.append(wid)
                            st["points"].append({"id": _next(st), "suite": suite, "plan": plan, "case": wid, "outcome": "unspecified", "state": "ready", "tester": None})
                        added.append({"workItem": {"id": wid}})
                    return h.send(200, {"count": len(added), "value": added})
                if m == "DELETE":
                    ids = [int(x) for x in q.get("testCaseIds", "").split(",") if x]
                    cases[:] = [c for c in cases if c not in ids]
                    st["points"] = [p for p in st["points"] if not (p["suite"] == suite and p["case"] in ids)]
                    return h.send(204)
                out = []
                for c in cases:
                    w = st["workitems"].get(str(c), {"fields": {}})
                    out.append({"workItem": {"id": c, "name": w["fields"].get("System.Title"),
                                             "workItemFields": [{"System.State": w["fields"].get("System.State")}, {"System.AssignedTo": w["fields"].get("System.AssignedTo")}]},
                                "pointAssignments": [{"id": p["id"]} for p in pts if p["case"] == c]})
                return h.send(200, {"count": len(out), "value": out})
            if m == "PATCH":
                upd = 0
                for u in h.body():
                    p = next((x for x in pts if x["id"] == u.get("id")), None)
                    if p is None:
                        return h.send(404, {"message": "Test point %s not found" % u.get("id")})
                    if u.get("resetToActive"):
                        p.update(outcome="unspecified", state="ready")
                    if u.get("results"):
                        p.update(outcome=u["results"]["outcome"], state="completed")
                    if u.get("tester"):
                        p["tester"] = u["tester"]
                    upd += 1
                return h.send(200, {"count": upd, "value": []})
            tc = q.get("testCaseId")
            out = []
            for p in pts:
                if tc and str(p["case"]) != tc:
                    continue
                w = st["workitems"].get(str(p["case"]), {"fields": {}})
                out.append({"id": p["id"], "testCaseReference": {"id": p["case"], "name": w["fields"].get("System.Title")},
                            "configuration": {"name": "Windows 11"}, "tester": p["tester"], "results": {"outcome": p["outcome"], "state": p["state"]}})
            return h.send(200, {"count": len(out), "value": out})
        # ---- test runs
        if al == "test/runs":
            if m == "POST":
                b = h.body()
                rid = _next(st)
                pts = b.get("pointIds") or []
                run = {"id": rid, "name": b.get("name"), "state": "InProgress", "plan": int((b.get("plan") or {}).get("id", 0)), "automated": b.get("automated"),
                       "build": b.get("build"), "totalTests": len(pts), "passedTests": 0, "unanalyzedTests": 0, "incompleteTests": len(pts),
                       "startedDate": core.now_iso(), "completedDate": None, "webAccessUrl": BASE_URL + "/UT%%20Portal/_testManagement/runs?runId=%d" % rid,
                       "results": [{"id": 100000 + i, "testPoint": {"id": str(pid)}, "outcome": "Unspecified", "state": "Pending"} for i, pid in enumerate(pts)]}
                st["testruns"].append(run)
                return h.send(200, {k: v for k, v in run.items() if k != "results"})
            found = [{k: v for k, v in r.items() if k != "results"} for r in st["testruns"] if not q.get("planId") or str(r["plan"]) == q["planId"]]
            return h.send(200, {"count": len(found), "value": found})
        mtr = re.match(r"test/runs/(\d+)(/statistics|/results)?$", api, re.I)
        if mtr:
            run = next((x for x in st["testruns"] if x["id"] == int(mtr.group(1))), None)
            if run is None:
                return h.send(404, {"message": "Test run %s not found" % mtr.group(1)})
            sub = (mtr.group(2) or "").lower()
            if sub == "/statistics":
                counts = {}
                for r in run["results"]:
                    counts[r["outcome"]] = counts.get(r["outcome"], 0) + 1
                return h.send(200, {"run": {"id": run["id"]}, "runStatistics": [{"state": "Completed", "outcome": k, "count": v} for k, v in counts.items()]})
            if sub == "/results":
                if m == "GET":
                    return h.send(200, {"count": len(run["results"]), "value": run["results"]})
                for u in h.body():
                    r = next((x for x in run["results"] if x["id"] == int(u.get("id"))), None)
                    if r is None:
                        return h.send(404, {"message": "Result %s not found" % u.get("id")})
                    r.update({k: v for k, v in u.items() if k != "id"})
                run["passedTests"] = sum(1 for r in run["results"] if r["outcome"] == "Passed")
                run["unanalyzedTests"] = sum(1 for r in run["results"] if r["outcome"] == "Failed")
                run["incompleteTests"] = sum(1 for r in run["results"] if r.get("state") != "Completed")
                return h.send(200, {"count": len(run["results"]), "value": run["results"]})
            if m == "GET":
                return h.send(200, {k: v for k, v in run.items() if k != "results"})
            if m == "PATCH":
                b = h.body()
                run.update(state=b.get("state", run["state"]), comment=b.get("comment"), completedDate=core.now_iso())
                return h.send(200, {k: v for k, v in run.items() if k != "results"})
        # ---- sprints
        if al == "work/teamsettings/iterations" and m == "GET":
            its = [i for i in st["iterations"] if q.get("$timeframe") != "current" or i["attributes"]["timeFrame"] == "current"]
            return h.send(200, {"count": len(its), "value": its})
        mit = re.match(r"work/teamsettings/iterations/([^/]+)/workitems$", api)
        if mit and m == "GET":
            ids = st["iteration_items"].get(mit.group(1))
            if ids is None:
                return h.send(404, {"message": "Iteration not found"})
            return h.send(200, {"workItemRelations": [{"rel": None, "source": None, "target": {"id": i}} for i in ids]})
    raise KeyError(path)


def _suite_out(st, s):
    o = {k: v for k, v in s.items() if k != "plan"}
    o["testCaseCount"] = len(st["suite_cases"].get(str(s["id"]), []))
    return o


ROUTES = [(P + "/", ado, "biz_azure_devops")]
