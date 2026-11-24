"""Jira for the command unit tests, under /biz/jira: issues, JQL search (Cloud /rest/api/3/search/jql and Data Center
/rest/api/2/search), comments, transitions, attachments, user search, projects and project categories. Made-up data."""
import re
import time

P = "/biz/jira"

SEED = {"biz_jira": {
    "next_issue": 1000, "next_comment": 50000, "next_project": 20000,
    "users": [
        {"accountId": "ut-acc-0001", "name": "asha.patel", "emailAddress": "asha.patel@ut.example", "displayName": "Asha Patel", "active": True},
        {"accountId": "ut-acc-0002", "name": "bruno.silva", "emailAddress": "bruno.silva@ut.example", "displayName": "Bruno Silva", "active": True},
    ],
    "categories": [{"id": "10100", "name": "Operations"}, {"id": "10101", "name": "Finance"}],
    "projects": [
        {"id": "10001", "key": "UTOPS", "name": "UT Operations", "projectTypeKey": "software", "lead": {"accountId": "ut-acc-0001", "displayName": "Asha Patel"},
         "projectCategory": {"id": "10100", "name": "Operations"}, "description": "Made-up operations project"},
        {"id": "10002", "key": "UTFIN", "name": "UT Finance", "projectTypeKey": "business", "lead": {"accountId": "ut-acc-0002", "displayName": "Bruno Silva"},
         "projectCategory": {"id": "10101", "name": "Finance"}, "description": "Made-up finance project"},
    ],
    "issues": [
        {"id": "900", "key": "UTOPS-1", "fields": {"summary": "UT disk full on db01", "status": {"name": "To Do"}, "priority": {"name": "High"},
                                                    "labels": ["seed", "database"], "assignee": {"accountId": "ut-acc-0001", "displayName": "Asha Patel"},
                                                    "reporter": {"accountId": "ut-acc-0002", "displayName": "Bruno Silva"}, "description": "db01 is at 98 percent",
                                                    "created": "2026-11-01T09:00:00.000+0000", "updated": "2026-11-01T09:00:00.000+0000", "duedate": "2026-12-01",
                                                    "issuetype": {"name": "Bug"}, "project": {"key": "UTOPS"}, "attachment": []},
         "_comments": [{"id": "40001", "body": "Looking at it", "author": {"displayName": "Asha Patel"}, "created": "2026-11-01T10:00:00.000+0000", "updated": "2026-11-01T10:00:00.000+0000"}]},
        {"id": "901", "key": "UTOPS-2", "fields": {"summary": "UT rotate backup keys", "status": {"name": "In Progress"}, "priority": {"name": "Medium"},
                                                    "labels": ["seed", "security"], "assignee": None, "reporter": {"accountId": "ut-acc-0001", "displayName": "Asha Patel"},
                                                    "description": "", "created": "2026-11-02T09:00:00.000+0000", "updated": "2026-11-02T09:00:00.000+0000",
                                                    "issuetype": {"name": "Task"}, "project": {"key": "UTOPS"}, "attachment": []}, "_comments": []},
        {"id": "902", "key": "UTFIN-1", "fields": {"summary": "UT close November books", "status": {"name": "To Do"}, "priority": {"name": "Low"},
                                                    "labels": ["seed"], "assignee": None, "reporter": {"accountId": "ut-acc-0002", "displayName": "Bruno Silva"},
                                                    "description": "", "created": "2026-11-03T09:00:00.000+0000", "updated": "2026-11-03T09:00:00.000+0000",
                                                    "issuetype": {"name": "Task"}, "project": {"key": "UTFIN"}, "attachment": []}, "_comments": []},
    ],
}}

TRANSITIONS = [("11", "To Do", "To Do"), ("21", "Start progress", "In Progress"), ("31", "Done", "Done")]


def _st():
    return core.STATE.setdefault("biz_jira", {"issues": [], "projects": [], "users": [], "categories": []})


def _now():
    return time.strftime("%Y-%m-%dT%H:%M:%S.000+0000", time.gmtime())


def _authorized(h):
    a = h.headers.get("Authorization", "")
    if not ((a.startswith("Bearer ") or a.startswith("Basic ")) and len(a) > 12):
        h.send(401, {"errorMessages": ["You are not authenticated. Authentication required to perform this operation."], "errors": {}})
        return False
    return True


def _public(issue):
    return {k: v for k, v in issue.items() if not k.startswith("_")}


def _jql(issues, jql):
    jql = re.split(r"\s+ORDER\s+BY\s+", jql or "", flags=re.I)[0]
    found = list(issues)
    for c in re.finditer(r'(\w+)\s*(=|~|!=)\s*("([^"]*)"|[\w-]+)', jql):
        field, op, raw, quoted = c.groups()
        v = quoted if quoted is not None else raw

        def have(i):
            f = i["fields"]
            if field == "project":
                return i["key"].split("-")[0]
            if field == "key":
                return i["key"]
            if field == "labels":
                return f.get("labels", [])
            x = f.get(field)
            return x.get("name") if isinstance(x, dict) else x
        if op == "~":
            found = [i for i in found if v.lower() in str(have(i) or "").lower()]
        elif field == "labels":
            found = [i for i in found if (v in have(i)) == (op == "=")]
        else:
            found = [i for i in found if (str(have(i)) == v) == (op == "=")]
    return found


def jira(h, m, path, q):
    st = _st()
    p = path[len(P):]
    if not _authorized(h):
        return
    issues = st["issues"]
    with core.LOCK:
        if p == "/rest/api/3/search/jql" and m == "POST":
            b = h.body()
            found = _jql(issues, b.get("jql"))
            start = int(b.get("nextPageToken") or 0)
            n = int(b.get("maxResults", 50))
            page = found[start:start + n]
            nxt = str(start + n) if start + n < len(found) else None
            return h.send(200, {"issues": [_public(i) for i in page], "isLast": nxt is None, **({"nextPageToken": nxt} if nxt else {})})
        if p == "/rest/api/2/search" and m == "POST":
            b = h.body()
            found = _jql(issues, b.get("jql"))
            start, n = int(b.get("startAt", 0)), int(b.get("maxResults", 50))
            return h.send(200, {"issues": [_public(i) for i in found[start:start + n]], "startAt": start, "maxResults": n, "total": len(found)})
        if p == "/rest/api/2/user/search" and m == "GET":
            who = (q.get("query") or q.get("username") or "").lower()
            return h.send(200, [u for u in st["users"] if u["emailAddress"].lower() == who or u["name"] == who])
        if p == "/rest/api/2/projectCategory" and m == "GET":
            return h.send(200, st["categories"])
        if p.startswith("/rest/api/2/project"):
            mp = re.match(r"/rest/api/2/project(?:/([A-Z][A-Z0-9_]*))?$", p)
            if not mp:
                raise KeyError(path)
            projects = st["projects"]
            if mp.group(1) is None:
                if m == "GET":
                    return h.send(200, projects)
                if m == "POST":
                    b = h.body()
                    if any(x["key"] == b.get("key") for x in projects):
                        return h.send(400, {"errorMessages": [], "errors": {"projectKey": "A project with that project key already exists."}})
                    if not b.get("leadAccountId") and not b.get("lead"):
                        return h.send(400, {"errorMessages": [], "errors": {"projectLead": "You must specify a valid project lead."}})
                    lead = next((u for u in st["users"] if u["accountId"] == b.get("leadAccountId") or u["name"] == b.get("lead")), None)
                    st["next_project"] = st.get("next_project", 20000) + 1
                    pr = {"id": str(st["next_project"]), "key": b["key"], "name": b.get("name"), "projectTypeKey": b.get("projectTypeKey"),
                          "lead": {"accountId": lead["accountId"], "displayName": lead["displayName"]} if lead else None, "description": b.get("description", ""),
                          "assigneeType": b.get("assigneeType", "PROJECT_LEAD")}
                    cat = next((c for c in st["categories"] if str(c["id"]) == str(b.get("categoryId"))), None)
                    if cat:
                        pr["projectCategory"] = cat
                    projects.append(pr)
                    return h.send(201, {"id": int(pr["id"]), "key": pr["key"], "self": "http://127.0.0.1/rest/api/2/project/" + pr["id"]})
            pr = next((x for x in projects if x["key"] == mp.group(1)), None)
            if pr is None:
                return h.send(404, {"errorMessages": ["No project could be found with key '%s'." % mp.group(1)], "errors": {}})
            if m == "GET":
                return h.send(200, pr)
            if m == "PUT":
                b = h.body()
                if "leadAccountId" in b:
                    lead_id = b.pop("leadAccountId")
                    u = next((x for x in st["users"] if x["accountId"] == lead_id), None)
                    pr["lead"] = {"accountId": u["accountId"], "displayName": u["displayName"]} if u else None
                if "categoryId" in b:
                    want = str(b.pop("categoryId"))
                    pr["projectCategory"] = next((c for c in st["categories"] if str(c["id"]) == want), None)
                pr.update(b)
                return h.send(200, pr)
            if m == "DELETE":
                projects.remove(pr)
                st["issues"][:] = [i for i in issues if i["key"].split("-")[0] != pr["key"]]
                return h.send(204)
        if p == "/rest/api/2/issue" and m == "POST":
            f = h.body().get("fields", {})
            proj = (f.get("project") or {}).get("key")
            if not any(x["key"] == proj for x in st["projects"]):
                return h.send(400, {"errorMessages": [], "errors": {"project": "valid project is required"}})
            if not f.get("summary"):
                return h.send(400, {"errorMessages": [], "errors": {"summary": "You must specify a summary of the issue."}})
            st["next_issue"] = st.get("next_issue", 1000) + 1
            n = st["next_issue"]
            fields = dict(f)
            if isinstance(fields.get("assignee"), dict):
                u = next((x for x in st["users"] if x["accountId"] == fields["assignee"].get("accountId") or x["name"] == fields["assignee"].get("name")), None)
                fields["assignee"] = {"accountId": u["accountId"], "displayName": u["displayName"]} if u else None
            fields.update({"status": {"name": "To Do"}, "created": _now(), "updated": _now(), "attachment": [],
                           "reporter": {"accountId": "ut-acc-0001", "displayName": "Asha Patel"}})
            issue = {"id": str(n), "key": "%s-%d" % (proj, n), "fields": fields, "_comments": []}
            issues.append(issue)
            return h.send(201, {"id": issue["id"], "key": issue["key"], "self": "http://127.0.0.1/rest/api/2/issue/" + issue["id"]})
        mi = re.match(r"/rest/api/2/issue/([^/]+)(?:/(transitions|comment|attachments))?(?:/(\d+))?$", p)
        if not mi:
            raise KeyError(path)
        key, sub, cid = mi.groups()
        issue = next((i for i in issues if i["key"] == key or i["id"] == key), None)
        if issue is None:
            return h.send(404, {"errorMessages": ["Issue does not exist or you do not have permission to see it."], "errors": {}})
        f = issue["fields"]
        if sub == "transitions":
            if m == "GET":
                return h.send(200, {"transitions": [{"id": i, "name": n, "to": {"name": to}} for i, n, to in TRANSITIONS]})
            b = h.body()
            tid = str((b.get("transition") or {}).get("id"))
            t = next((x for x in TRANSITIONS if x[0] == tid), None)
            if t is None:
                return h.send(400, {"errorMessages": ["Transition id '%s' is not valid for this issue." % tid], "errors": {}})
            f["status"] = {"name": t[2]}
            f.update(b.get("fields") or {})
            for c in ((b.get("update") or {}).get("comment") or []):
                st["next_comment"] = st.get("next_comment", 50000) + 1
                issue["_comments"].append({"id": str(st["next_comment"]), "body": c["add"]["body"], "author": {"displayName": "Asha Patel"}, "created": _now(), "updated": _now()})
            return h.send(204)
        if sub == "attachments" and m == "POST":
            if h.headers.get("X-Atlassian-Token") != "no-check":
                return h.send(403, {"errorMessages": ["XSRF check failed"]})
            raw = h.raw()
            mf = re.search(rb'filename="([^"]+)"', raw)
            name = mf.group(1).decode("utf-8", "replace") if mf else "file"
            att = {"id": str(len(f.get("attachment", [])) + 70000), "filename": name, "name": name, "size": len(raw)}
            f.setdefault("attachment", []).append(att)
            return h.send(200, [att])
        if sub == "comment":
            comments = issue["_comments"]
            if cid is None and m == "GET":
                start, n = int(q.get("startAt", 0)), int(q.get("maxResults", 50))
                return h.send(200, {"comments": comments[start:start + n], "startAt": start, "maxResults": n, "total": len(comments)})
            if cid is None and m == "POST":
                b = h.body()
                st["next_comment"] = st.get("next_comment", 50000) + 1
                c = {"id": str(st["next_comment"]), "body": b.get("body", ""), "author": {"displayName": "Asha Patel"}, "created": _now(), "updated": _now()}
                if b.get("visibility"):
                    c["visibility"] = b["visibility"]
                comments.append(c)
                return h.send(201, c)
            c = next((x for x in comments if x["id"] == cid), None)
            if c is None:
                return h.send(404, {"errorMessages": ["Can not find a comment for the id: %s." % cid], "errors": {}})
            if m == "PUT":
                c["body"] = h.body().get("body", "")
                c["updated"] = _now()
                return h.send(200, c)
            if m == "DELETE":
                comments.remove(c)
                return h.send(204)
        if sub is None:
            if m == "GET":
                return h.send(200, _public(issue))
            if m == "PUT":
                b = h.body().get("fields", {})
                if isinstance(b.get("assignee"), dict):
                    u = next((x for x in st["users"] if x["accountId"] == b["assignee"].get("accountId") or x["name"] == b["assignee"].get("name")), None)
                    b["assignee"] = {"accountId": u["accountId"], "displayName": u["displayName"]} if u else None
                f.update(b)
                f["updated"] = _now()
                return h.send(204)
            if m == "DELETE":
                if any((i["fields"].get("parent") or {}).get("key") == issue["key"] for i in issues) and q.get("deleteSubtasks") != "true":
                    return h.send(400, {"errorMessages": ["The issue has subtasks; set deleteSubtasks to delete them too."], "errors": {}})
                issues[:] = [i for i in issues if i is not issue and (i["fields"].get("parent") or {}).get("key") != issue["key"]]
                return h.send(204)
    raise KeyError(path)


ROUTES = [(P + "/", jira, "biz_jira")]
