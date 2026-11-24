"""GitHub REST for the command unit tests, under /biz/github: repositories, issues, pull requests (with merge),
branches and refs, file contents, releases and Actions workflow runs. Made-up organization ut-org."""
import base64
import hashlib
import re

P = "/biz/github"


def _sha(text):
    return hashlib.sha1(text.encode("utf-8")).hexdigest()


def _repo(name, desc, branch="main"):
    return {"name": name, "full_name": "ut-org/" + name, "description": desc, "private": False, "default_branch": branch,
            "open_issues_count": 0, "archived": False, "html_url": "https://github.example/ut-org/" + name,
            "updated_at": "2026-11-01T10:00:00Z"}


SEED = {"biz_github": {
    "repos": {
        "ut-org/billing-service": {
            "meta": _repo("billing-service", "Invoices and payments"),
            "branches": {"main": _sha("main-1"), "release/2.4": _sha("rel-1")},
            "files": {"main": {"config/rates.json": '{"vat": 20}\n'}},
            "issues": [
                {"number": 1, "title": "Invoice upload fails over 10 MB", "state": "open", "state_reason": None, "body": "Seen in run 41",
                 "user": {"login": "ut-dev"}, "labels": [{"name": "bug"}, {"name": "finance"}], "assignees": [{"login": "ut-dev"}],
                 "milestone": {"title": "2.4"}, "comments": 0, "created_at": "2026-10-01T09:00:00Z", "updated_at": "2026-10-02T09:00:00Z", "closed_at": None},
                {"number": 2, "title": "Old report layout", "state": "closed", "state_reason": "completed", "body": "", "user": {"login": "ut-ops"},
                 "labels": [], "assignees": [], "milestone": None, "comments": 1, "created_at": "2026-09-01T09:00:00Z", "updated_at": "2026-09-05T09:00:00Z", "closed_at": "2026-09-05T09:00:00Z"},
                {"number": 3, "title": "Add rates for 2027", "state": "open", "pull_request": {"url": "x"}, "body": "", "user": {"login": "ut-dev"},
                 "labels": [], "assignees": [], "milestone": None, "comments": 0, "created_at": "2026-10-10T09:00:00Z", "updated_at": "2026-10-10T09:00:00Z", "closed_at": None},
            ],
            "pulls": [
                {"number": 3, "title": "Add rates for 2027", "state": "open", "draft": False, "merged": False, "mergeable": True, "body": "Rates",
                 "user": {"login": "ut-dev"}, "head": {"ref": "feature/rates-2027", "sha": _sha("rates")}, "base": {"ref": "main"},
                 "created_at": "2026-10-10T09:00:00Z", "updated_at": "2026-10-10T09:00:00Z", "merged_at": None},
                {"number": 4, "title": "Hotfix 2.3.1", "state": "closed", "draft": False, "merged": True, "mergeable": None, "body": "",
                 "user": {"login": "ut-ops"}, "head": {"ref": "hotfix/2.3.1", "sha": _sha("hotfix")}, "base": {"ref": "release/2.4"},
                 "created_at": "2026-09-10T09:00:00Z", "updated_at": "2026-09-11T09:00:00Z", "merged_at": "2026-09-11T09:00:00Z"},
            ],
            "releases": [
                {"id": 7001, "tag_name": "v2.3.0", "name": "2.3.0", "body": "Older", "draft": False, "prerelease": False, "published_at": "2026-08-01T09:00:00Z",
                 "author": {"login": "ut-ops"}, "html_url": "https://github.example/ut-org/billing-service/releases/v2.3.0", "assets": []},
                {"id": 7002, "tag_name": "v2.4.0", "name": "2.4.0", "body": "Rates for 2027", "draft": False, "prerelease": False, "published_at": "2026-10-20T09:00:00Z",
                 "author": {"login": "ut-ops"}, "html_url": "https://github.example/ut-org/billing-service/releases/v2.4.0", "assets": [{"name": "billing-2.4.0.zip"}]},
                {"id": 7003, "tag_name": "v2.5.0-rc1", "name": "2.5.0 rc1", "body": "", "draft": False, "prerelease": True, "published_at": "2026-11-01T09:00:00Z",
                 "author": {"login": "ut-ops"}, "html_url": "x", "assets": []},
            ],
            "runs": [
                {"id": 9001, "name": "CI", "status": "completed", "conclusion": "success", "head_branch": "main", "head_sha": _sha("main-1"), "event": "push",
                 "created_at": "2026-11-01T09:00:00Z", "updated_at": "2026-11-01T09:05:00Z", "html_url": "https://github.example/runs/9001", "_workflow": "ci.yml"},
                {"id": 9002, "name": "Deploy", "status": "in_progress", "conclusion": None, "head_branch": "main", "head_sha": _sha("main-1"), "event": "workflow_dispatch",
                 "created_at": "2026-11-02T09:00:00Z", "updated_at": "2026-11-02T09:01:00Z", "html_url": "https://github.example/runs/9002", "_workflow": "deploy.yml"},
                {"id": 9003, "name": "CI", "status": "completed", "conclusion": "failure", "head_branch": "feature/rates-2027", "head_sha": _sha("rates"), "event": "pull_request",
                 "created_at": "2026-10-10T09:00:00Z", "updated_at": "2026-10-10T09:04:00Z", "html_url": "https://github.example/runs/9003", "_workflow": "ci.yml"},
            ],
            "workflows": ["ci.yml", "deploy.yml"],
            "comments": [],
        },
        "ut-org/docs-site": {"meta": _repo("docs-site", "Help pages"), "branches": {"main": _sha("docs")}, "files": {}, "issues": [], "pulls": [],
                             "releases": [], "runs": [], "workflows": [], "comments": []},
    },
    "next_run": 9100,
}}


def _pub(x):
    return {k: v for k, v in x.items() if not k.startswith("_")}


def github(h, m, path, q):
    a = h.headers.get("Authorization", "")
    if not a.startswith("Bearer ") or len(a) < 12:
        return h.send(401, {"message": "Bad credentials"})
    g = core.STATE.setdefault("biz_github", {"repos": {}, "next_run": 9100})
    p = path[len(P):]
    nf = lambda: h.send(404, {"message": "Not Found"})
    with core.LOCK:
        if p == "/user/repos" or re.match(r"/(orgs|users)/[^/]+/repos$", p):
            owner = None if p == "/user/repos" else p.split("/")[2]
            found = [r["meta"] for k, r in g["repos"].items() if owner is None or k.startswith(owner + "/")]
            if owner and not found:
                return nf()
            return h.send(200, found)
        mr = re.match(r"/repos/([^/]+)/([^/]+)(/.*)?$", p)
        if not mr:
            return nf()
        repo = g["repos"].get(mr.group(1) + "/" + mr.group(2))
        if repo is None:
            return nf()
        rest = mr.group(3) or ""
        html = "https://github.example/" + mr.group(1) + "/" + mr.group(2)
        if rest == "" and m == "GET":
            meta = dict(repo["meta"], open_issues_count=len([i for i in repo["issues"] if i["state"] == "open"]))
            return h.send(200, meta)
        # ---- issues
        if rest == "/issues":
            if m == "POST":
                b = h.body()
                if not b.get("title"):
                    return h.send(422, {"message": "Validation Failed"})
                n = max([i["number"] for i in repo["issues"]] + [x["number"] for x in repo["pulls"]] + [0]) + 1
                i = {"number": n, "title": b["title"], "state": "open", "state_reason": None, "body": b.get("body"), "user": {"login": "ut-agent"},
                     "labels": [{"name": x} for x in b.get("labels", [])], "assignees": [{"login": x} for x in b.get("assignees", [])],
                     "milestone": {"title": str(b["milestone"])} if b.get("milestone") else None, "comments": 0,
                     "created_at": core.now_iso(), "updated_at": core.now_iso(), "closed_at": None, "html_url": html + "/issues/%d" % n}
                repo["issues"].append(i)
                return h.send(201, i)
            st = q.get("state", "open")
            found = [i for i in repo["issues"] if st == "all" or i["state"] == st]
            if q.get("labels"):
                want = set(q["labels"].split(","))
                found = [i for i in found if want <= {x["name"] for x in i["labels"]}]
            if q.get("assignee"):
                found = [i for i in found if any(x["login"] == q["assignee"] for x in i["assignees"])]
            return h.send(200, found)
        mi = re.match(r"/issues/(\d+)(/comments)?$", rest)
        if mi:
            i = next((x for x in repo["issues"] if x["number"] == int(mi.group(1))), None)
            if i is None:
                return nf()
            if mi.group(2) and m == "POST":
                c = {"id": 50000 + len(repo["comments"]) + 1, "body": h.body().get("body"), "html_url": html + "/issues/%s#issuecomment" % mi.group(1)}
                repo["comments"].append(c)
                i["comments"] += 1
                return h.send(201, c)
            if m == "PATCH":
                b = h.body()
                for k in ("title", "body", "state", "state_reason"):
                    if k in b:
                        i[k] = b[k]
                if "labels" in b:
                    i["labels"] = [{"name": x} for x in b["labels"]]
                if "assignees" in b:
                    i["assignees"] = [{"login": x} for x in b["assignees"]]
                if b.get("state") == "closed":
                    i["closed_at"] = core.now_iso()
                    i.setdefault("state_reason", "completed")
                elif b.get("state") == "open":
                    i["closed_at"], i["state_reason"] = None, None
                i["updated_at"] = core.now_iso()
                return h.send(200, i)
            return h.send(200, dict(i, html_url=html + "/issues/" + mi.group(1)))
        # ---- pull requests
        if rest == "/pulls":
            if m == "POST":
                b = h.body()
                if b.get("head") not in repo["branches"] or b.get("base") not in repo["branches"]:
                    return h.send(422, {"message": "Validation Failed", "errors": [{"field": "head", "code": "invalid"}]})
                n = max([i["number"] for i in repo["issues"]] + [x["number"] for x in repo["pulls"]] + [0]) + 1
                pr = {"number": n, "title": b["title"], "state": "open", "draft": bool(b.get("draft")), "merged": False, "mergeable": True, "body": b.get("body"),
                      "user": {"login": "ut-agent"}, "head": {"ref": b["head"], "sha": repo["branches"][b["head"]]}, "base": {"ref": b["base"]},
                      "created_at": core.now_iso(), "updated_at": core.now_iso(), "merged_at": None, "html_url": html + "/pull/%d" % n}
                repo["pulls"].append(pr)
                return h.send(201, pr)
            st = q.get("state", "open")
            found = [x for x in repo["pulls"] if st == "all" or x["state"] == st]
            if q.get("base"):
                found = [x for x in found if x["base"]["ref"] == q["base"]]
            if q.get("head"):
                found = [x for x in found if mr.group(1) + ":" + x["head"]["ref"] == q["head"]]
            return h.send(200, found)
        mp = re.match(r"/pulls/(\d+)(/merge)?$", rest)
        if mp:
            pr = next((x for x in repo["pulls"] if x["number"] == int(mp.group(1))), None)
            if pr is None:
                return nf()
            if mp.group(2):
                if m == "GET":
                    return h.send(204) if pr["merged"] else nf()
                if m == "PUT":
                    b = h.body()
                    if pr["merged"] or pr["draft"]:
                        return h.send(405, {"message": "Pull Request is not mergeable"})
                    if b.get("sha") and b["sha"] != pr["head"]["sha"]:
                        return h.send(409, {"message": "Head branch was modified. Review and try the merge again."})
                    sha = _sha("merge-%d-%s" % (pr["number"], b.get("merge_method")))
                    pr.update(merged=True, state="closed", merged_at=core.now_iso(), mergeable=None)
                    repo["branches"][pr["base"]["ref"]] = sha
                    return h.send(200, {"sha": sha, "merged": True, "message": "Pull Request successfully merged"})
            return h.send(200, dict(pr, html_url=html + "/pull/" + mp.group(1)))
        # ---- branches and refs
        if rest == "/branches":
            return h.send(200, [{"name": k, "commit": {"sha": v}, "protected": k == "main"} for k, v in repo["branches"].items()])
        mref = re.match(r"/git/ref/heads/(.+)$", rest)
        if mref:
            b = mref.group(1)
            return h.send(200, {"ref": "refs/heads/" + b, "object": {"sha": repo["branches"][b], "type": "commit"}}) if b in repo["branches"] else nf()
        if rest == "/git/refs" and m == "POST":
            b = h.body()
            name = str(b.get("ref", "")).replace("refs/heads/", "", 1)
            if name in repo["branches"] and not name.startswith("ut-run/"):     # ut-run/ branches: a re-run may make them again
                return h.send(422, {"message": "Reference already exists"})
            repo["branches"][name] = b.get("sha")
            return h.send(201, {"ref": b.get("ref"), "object": {"sha": b.get("sha")}})
        # ---- contents
        mc = re.match(r"/contents/(.+)$", rest)
        if mc:
            fpath = mc.group(1)
            branch = q.get("ref") or repo["meta"]["default_branch"]
            files = repo["files"].setdefault(branch, {})
            if m == "GET":
                if fpath not in files:
                    if any(k.startswith(fpath + "/") for k in files):
                        return h.send(200, [{"type": "file", "name": k} for k in files if k.startswith(fpath + "/")])
                    return nf()
                text = files[fpath]
                return h.send(200, {"type": "file", "path": fpath, "sha": _sha(text), "size": len(text.encode()), "encoding": "base64",
                                    "content": base64.encodebytes(text.encode()).decode()})
            if m == "PUT":
                b = h.body()
                branch = b.get("branch") or repo["meta"]["default_branch"]
                if branch not in repo["branches"]:
                    return nf()
                files = repo["files"].setdefault(branch, {})
                if fpath in files and b.get("sha") != _sha(files[fpath]):
                    return h.send(409, {"message": "%s does not match" % b.get("sha")})
                text = base64.b64decode(b["content"]).decode("utf-8", "replace")
                created = fpath not in files
                files[fpath] = text
                commit = _sha("commit-" + fpath + text)
                repo["branches"][branch] = commit
                return h.send(201 if created else 200, {"content": {"path": fpath, "sha": _sha(text)}, "commit": {"sha": commit, "message": b.get("message")}})
        # ---- releases
        if rest == "/releases" and m == "POST":
            b = h.body()
            repo["releases"] = [r for r in repo["releases"] if not (r["tag_name"] == b.get("tag_name") and r["tag_name"].startswith("ut-"))]
            if any(r["tag_name"] == b.get("tag_name") for r in repo["releases"]):
                return h.send(422, {"message": "Validation Failed", "errors": [{"resource": "Release", "code": "already_exists", "field": "tag_name"}]})
            r = {"id": 7000 + len(repo["releases"]) + 10, "tag_name": b["tag_name"], "name": b.get("name") or b["tag_name"], "body": b.get("body"),
                 "draft": bool(b.get("draft")), "prerelease": bool(b.get("prerelease")), "published_at": None if b.get("draft") else core.now_iso(),
                 "author": {"login": "ut-agent"}, "html_url": html + "/releases/tag/" + b["tag_name"], "assets": []}
            repo["releases"].append(r)
            return h.send(201, r)
        if rest == "/releases/latest":
            pub = [r for r in repo["releases"] if not r["draft"] and not r["prerelease"]]
            return h.send(200, max(pub, key=lambda r: r["published_at"])) if pub else nf()
        # ---- Actions
        md = re.match(r"/actions/workflows/([^/]+)/dispatches$", rest)
        if md and m == "POST":
            if md.group(1) not in repo["workflows"]:
                return nf()
            b = h.body()
            if b.get("ref") not in repo["branches"]:
                return h.send(422, {"message": "No ref found for: %s" % b.get("ref")})
            g["next_run"] += 1
            repo["runs"].insert(0, {"id": g["next_run"], "name": md.group(1).split(".")[0].title(), "status": "queued", "conclusion": None,
                                    "head_branch": b["ref"], "head_sha": repo["branches"][b["ref"]], "event": "workflow_dispatch",
                                    "created_at": core.now_iso(), "updated_at": core.now_iso(), "html_url": "https://github.example/runs/%d" % g["next_run"],
                                    "_workflow": md.group(1), "_inputs": b.get("inputs")})
            return h.send(204)
        mw = re.match(r"/actions(?:/workflows/([^/]+))?/runs$", rest)
        if mw:
            runs = [r for r in repo["runs"] if not mw.group(1) or r["_workflow"] == mw.group(1)]
            if q.get("branch"):
                runs = [r for r in runs if r["head_branch"] == q["branch"]]
            if q.get("status"):
                runs = [r for r in runs if q["status"] in (r["status"], r["conclusion"])]
            return h.send(200, {"total_count": len(runs), "workflow_runs": [_pub(r) for r in runs]})
        mx = re.match(r"/actions/runs/(\d+)$", rest)
        if mx:
            r = next((x for x in repo["runs"] if x["id"] == int(mx.group(1))), None)
            return h.send(200, _pub(r)) if r else nf()
    return nf()


ROUTES = [(P + "/", github, "biz_github")]
