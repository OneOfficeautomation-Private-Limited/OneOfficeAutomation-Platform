"""Asana REST 1.0 for the command unit tests, under /biz/asana: workspaces, teams, projects, sections, tasks, subtasks,
followers, stories (comments), attachments, tags and users. Every answer is wrapped in {"data": ...}. Made-up records."""
import itertools
import re

P = "/biz/asana"

SEED = {"biz_asana": {
    "workspaces": [{"gid": "1100000000000001", "name": "UT Org", "is_organization": True}],
    "users": [
        {"gid": "1300000000000001", "name": "Asha Patel", "email": "asha.patel@ut.example"},
        {"gid": "1300000000000002", "name": "Bruno Silva", "email": "bruno.silva@ut.example"},
        {"gid": "1300000000000003", "name": "Chloe Martin", "email": "chloe.martin@ut.example"},
    ],
    "teams": [{"gid": "1200000000000001", "name": "Finance Ops", "description": "Month-end close", "organization": "1100000000000001",
               "members": ["1300000000000001", "1300000000000002"]}],
    "projects": [{"gid": "1400000000000001", "name": "UT Month End", "notes": "Close the books", "archived": False, "owner": "1300000000000001",
                  "team": "1200000000000001", "workspace": "1100000000000001", "due_on": "2026-11-30"},
                 {"gid": "1400000000000002", "name": "UT Old Audit", "notes": "", "archived": True, "owner": "1300000000000002",
                  "team": "1200000000000001", "workspace": "1100000000000001", "due_on": None}],
    "sections": [{"gid": "1500000000000001", "name": "To do", "project": "1400000000000001"},
                 {"gid": "1500000000000002", "name": "Done", "project": "1400000000000001"}],
    "tasks": [{"gid": "1600000000000001", "name": "Reconcile bank account", "notes": "Main account", "completed": False,
               "assignee": "1300000000000001", "due_on": "2026-11-28", "projects": ["1400000000000001"],
               "memberships": [["1400000000000001", "1500000000000001"]], "tags": [], "followers": ["1300000000000001"],
               "parent": None, "custom_fields": [{"name": "Priority", "display_value": "High"}]},
              {"gid": "1600000000000002", "name": "Post accruals", "notes": "", "completed": True,
               "assignee": "1300000000000002", "due_on": "2026-11-20", "projects": ["1400000000000001"],
               "memberships": [["1400000000000001", "1500000000000002"]], "tags": [], "followers": [], "parent": None, "custom_fields": []}],
    "tags": [{"gid": "1700000000000001", "name": "urgent", "color": "dark-red", "workspace": "1100000000000001"}],
    "stories": [],
    "attachments": [],
}}

_ids = itertools.count(1900000000000001)


def _gid():
    return str(next(_ids))


def _st():
    return core.STATE.setdefault("biz_asana", {})


def _find(kind, gid):
    r = next((x for x in _st().get(kind, []) if x["gid"] == gid), None)
    if r is None:
        raise KeyError(kind + " " + gid)
    return r


def _user(ref):
    us = _st()["users"]
    if ref == "me":
        return us[0]
    return next((u for u in us if u["gid"] == ref or u["email"].lower() == str(ref).lower()), None)


def _uref(gid):
    u = _user(gid) if gid else None
    return {"gid": u["gid"], "name": u["name"], "email": u["email"]} if u else None


def _named(kind, gid):
    try:
        x = _find(kind, gid)
        return {"gid": x["gid"], "name": x["name"]}
    except KeyError:
        return None


def _link(kind, gid):
    return "https://app.asana.example/0/%s/%s" % (kind, gid)


def _task_out(t):
    st = _st()
    return {"gid": t["gid"], "name": t["name"], "notes": t.get("notes", ""), "completed": t.get("completed", False),
            "completed_at": "2026-11-21T10:00:00.000Z" if t.get("completed") else None,
            "assignee": _uref(t.get("assignee")), "due_on": t.get("due_on"), "due_at": None, "start_on": None,
            "projects": [_named("projects", p) for p in t.get("projects", [])],
            "memberships": [{"project": _named("projects", p), "section": _named("sections", s)} for p, s in t.get("memberships", [])],
            "tags": [_named("tags", g) for g in t.get("tags", [])], "followers": [_uref(u) for u in t.get("followers", [])],
            "parent": _named("tasks", t["parent"]) if t.get("parent") else None,
            "num_subtasks": len([x for x in st["tasks"] if x.get("parent") == t["gid"]]),
            "custom_fields": t.get("custom_fields", []), "created_at": "2026-11-01T09:00:00.000Z",
            "modified_at": t.get("modified_at", "2026-11-22T09:00:00.000Z"), "permalink_url": _link("task", t["gid"])}


def _project_out(p):
    return {"gid": p["gid"], "name": p["name"], "notes": p.get("notes", ""), "archived": p.get("archived", False),
            "owner": _uref(p.get("owner")), "team": _named("teams", p.get("team")) if p.get("team") else None,
            "workspace": _named("workspaces", p.get("workspace")), "due_on": p.get("due_on"),
            "created_at": "2026-10-01T09:00:00.000Z", "modified_at": "2026-11-22T09:00:00.000Z", "permalink_url": _link("project", p["gid"])}


def _team_out(t):
    return {"gid": t["gid"], "name": t["name"], "description": t.get("description", ""),
            "organization": _named("workspaces", t["organization"]), "permalink_url": _link("team", t["gid"])}


def _story_out(s):
    return {"gid": s["gid"], "type": "comment", "resource_subtype": "comment_added", "text": s.get("text", ""),
            "html_text": s.get("html_text", "<body>" + s.get("text", "") + "</body>"), "is_pinned": s.get("is_pinned", False),
            "created_by": _uref(s.get("author")), "created_at": "2026-11-22T10:00:00.000Z", "target": _named("tasks", s["task"])}


def _multipart(h):
    ctype = h.headers.get("Content-Type", "")
    m = re.search(r"boundary=\"?([^\";]+)", ctype)
    out = {}
    if not m:
        return out
    for part in h.raw().split(b"--" + m.group(1).encode()):
        if b"\r\n\r\n" not in part:
            continue
        head, body = part.split(b"\r\n\r\n", 1)
        body = body[:-2] if body.endswith(b"\r\n") else body
        n = re.search(rb'name="([^"]+)"', head)
        fn = re.search(rb'filename="([^"]*)"', head)
        if n:
            out[n.group(1).decode()] = (fn.group(1).decode() if fn else None, body)
    return out


def asana(h, m, path, q):
    p = path[len(P):]
    if p.startswith("/files/") and m == "GET":          # the signed download address: no token
        with core.LOCK:
            a = _find("attachments", p.split("/")[2])
        data = a["_content"]
        h.send_response(200)
        h.send_header("Content-Type", "application/octet-stream")
        h.send_header("Content-Length", str(len(data)))
        h.end_headers()
        h.wfile.write(data)
        return
    a = h.headers.get("Authorization", "")
    if not a.startswith("Bearer ") or len(a) < 12:
        return h.send(401, {"errors": [{"message": "Not Authorized"}]})
    host = "http://" + h.headers.get("Host", "127.0.0.1")
    b = h.body() if m in ("POST", "PUT") and "json" in h.headers.get("Content-Type", "") else {}
    d = b.get("data", {}) if isinstance(b, dict) else {}

    def ok(data, status=200):
        return h.send(status, {"data": data, "next_page": None} if isinstance(data, list) else {"data": data})

    parts = [x for x in p.split("/") if x]
    with core.LOCK:
        st = _st()
        if parts == ["workspaces"]:
            return ok(st["workspaces"])
        if len(parts) == 3 and parts[0] == "workspaces":
            ws, what = parts[1], parts[2]
            _find("workspaces", ws)
            if what == "typeahead":
                kind = {"project": "projects", "tag": "tags", "user": "users"}.get(q.get("resource_type"), "projects")
                qq = q.get("query", "").lower()
                return ok([{"gid": x["gid"], "name": x["name"]} for x in st[kind] if qq in x["name"].lower()])
            if what == "teams":
                return ok([_team_out(t) for t in st["teams"] if t["organization"] == ws])
            if what == "tags":
                return ok([{"gid": t["gid"], "name": t["name"], "color": t.get("color")} for t in st["tags"] if t["workspace"] == ws])
        if len(parts) == 4 and parts[0] == "workspaces" and parts[2:] == ["tasks", "search"]:
            found = st["tasks"]
            if q.get("text"):
                found = [t for t in found if q["text"].lower() in (t["name"] + " " + t.get("notes", "")).lower()]
            if q.get("assignee.any"):
                u = _user(q["assignee.any"])
                found = [t for t in found if u and t.get("assignee") == u["gid"]]
            if q.get("projects.any"):
                found = [t for t in found if q["projects.any"] in t.get("projects", [])]
            if q.get("completed") is not None and q.get("completed") != "":
                found = [t for t in found if str(t.get("completed", False)).lower() == q["completed"].lower()]
            return ok([_task_out(t) for t in found])
        # ---- projects
        if parts == ["projects"]:
            if m == "GET":
                found = [x for x in st["projects"] if x["workspace"] == q.get("workspace")]
                if q.get("team"):
                    found = [x for x in found if x.get("team") == q["team"]]
                if q.get("archived") in ("true", "false"):
                    found = [x for x in found if x.get("archived") == (q["archived"] == "true")]
                return ok([_project_out(x) for x in found])
            if not d.get("name") or not d.get("workspace"):
                return h.send(400, {"errors": [{"message": "name and workspace: Missing input"}]})
            x = {"gid": _gid(), "name": d["name"], "notes": d.get("notes", ""), "archived": False, "owner": (_user(d["owner"]) or {}).get("gid") if d.get("owner") else None,
                 "team": d.get("team"), "workspace": d["workspace"], "due_on": d.get("due_on")}
            st["projects"].append(x)
            return ok(_project_out(x), 201)
        if len(parts) >= 2 and parts[0] == "projects":
            x = _find("projects", parts[1])
            if len(parts) == 2:
                if m == "GET":
                    return ok(_project_out(x))
                if m == "PUT":
                    for k, v in d.items():
                        x[k] = (_user(v) or {}).get("gid") if k == "owner" else v
                    return ok(_project_out(x))
                if m == "DELETE":
                    st["projects"].remove(x)
                    return ok({})
            if parts[2] == "sections":
                if m == "POST":
                    s = {"gid": _gid(), "name": d["name"], "project": x["gid"]}
                    st["sections"].append(s)
                    return ok({"gid": s["gid"], "name": s["name"]}, 201)
                return ok([{"gid": s["gid"], "name": s["name"]} for s in st["sections"] if s["project"] == x["gid"]])
            if parts[2] == "tasks":
                found = [t for t in st["tasks"] if x["gid"] in t.get("projects", [])]
                if q.get("completed_since") == "now":
                    found = [t for t in found if not t.get("completed")]
                return ok([_task_out(t) for t in found])
        # ---- sections
        if len(parts) == 3 and parts[0] == "sections":
            s = _find("sections", parts[1])
            if parts[2] == "tasks":
                return ok([_task_out(t) for t in st["tasks"] if any(ms[1] == s["gid"] for ms in t.get("memberships", []))])
            if parts[2] == "addTask":
                t = _find("tasks", d["task"])
                t["memberships"] = [ms for ms in t.get("memberships", []) if ms[0] != s["project"]] + [[s["project"], s["gid"]]]
                if s["project"] not in t.setdefault("projects", []):
                    t["projects"].append(s["project"])
                return ok({})
        # ---- tasks
        if parts == ["tasks"]:
            if m == "GET":
                u = _user(q.get("assignee", ""))
                return ok([_task_out(t) for t in st["tasks"] if u and t.get("assignee") == u["gid"]])
            return ok(_task_out(_new_task(st, d)), 201)
        if len(parts) >= 2 and parts[0] == "tasks":
            t = _find("tasks", parts[1])
            if len(parts) == 2:
                if m == "GET":
                    return ok(_task_out(t))
                if m == "PUT":
                    for k, v in d.items():
                        t[k] = (_user(v) or {}).get("gid") if k == "assignee" and v else v
                    t["modified_at"] = core.now_iso()
                    return ok(_task_out(t))
                if m == "DELETE":
                    st["tasks"].remove(t)
                    return ok({})
            op = parts[2]
            if op == "setParent":
                t["parent"] = d.get("parent")
                return ok(_task_out(t))
            if op == "subtasks":
                if m == "POST":
                    return ok(_task_out(_new_task(st, dict(d, parent=t["gid"]))), 201)
                return ok([_task_out(x) for x in st["tasks"] if x.get("parent") == t["gid"]])
            if op == "stories":
                if m == "POST":
                    s = {"gid": _gid(), "task": t["gid"], "author": "1300000000000001", **{k: v for k, v in d.items() if k in ("text", "html_text", "is_pinned")}}
                    if "html_text" in s and "text" not in s:
                        s["text"] = re.sub(r"<[^>]+>", "", s["html_text"])
                    st["stories"].append(s)
                    return ok(_story_out(s), 201)
                system = {"gid": "1800000000000099", "type": "system", "resource_subtype": "assigned", "text": "assigned to you", "created_by": None, "created_at": "2026-11-01T09:00:00.000Z"}
                return ok([system] + [_story_out(s) for s in st["stories"] if s["task"] == t["gid"]])
            if op in ("addFollowers", "removeFollowers"):
                for ref in d.get("followers", []):
                    u = _user(ref)
                    if u is None:
                        return h.send(400, {"errors": [{"message": "Not a recognized user: " + ref}]})
                    fl = t.setdefault("followers", [])
                    if op == "addFollowers" and u["gid"] not in fl:
                        fl.append(u["gid"])
                    if op == "removeFollowers" and u["gid"] in fl:
                        fl.remove(u["gid"])
                return ok(_task_out(t))
            if op == "addProject":
                _find("projects", d["project"])
                if d["project"] not in t.setdefault("projects", []):
                    t["projects"].append(d["project"])
                if d.get("section"):
                    t.setdefault("memberships", []).append([d["project"], d["section"]])
                return ok({})
            if op in ("addTag", "removeTag"):
                _find("tags", d["tag"])
                tg = t.setdefault("tags", [])
                if op == "addTag" and d["tag"] not in tg:
                    tg.append(d["tag"])
                if op == "removeTag" and d["tag"] in tg:
                    tg.remove(d["tag"])
                return ok({})
            if op == "duplicate":
                inc = d.get("include", "").split(",")
                n = {"gid": _gid(), "name": d["name"], "notes": t.get("notes", "") if "notes" in inc else "", "completed": False,
                     "assignee": t.get("assignee") if "assignee" in inc else None, "due_on": None,
                     "projects": list(t.get("projects", [])) if "projects" in inc else [], "memberships": [], "tags": list(t.get("tags", [])) if "tags" in inc else [],
                     "followers": [], "parent": None, "custom_fields": []}
                st["tasks"].append(n)
                if "subtasks" in inc:
                    for x in [x for x in st["tasks"] if x.get("parent") == t["gid"]]:
                        st["tasks"].append(dict(x, gid=_gid(), parent=n["gid"]))
                return ok({"gid": _gid(), "resource_type": "job", "status": "in_progress", "new_task": {"gid": n["gid"], "name": n["name"]}}, 201)
        # ---- stories
        if len(parts) == 2 and parts[0] == "stories":
            s = _find("stories", parts[1])
            if m == "GET":
                return ok(_story_out(s))
            if m == "PUT":
                s.update({k: v for k, v in d.items() if k in ("text", "html_text", "is_pinned")})
                if "html_text" in d:
                    s["text"] = re.sub(r"<[^>]+>", "", d["html_text"])
                return ok(_story_out(s))
            if m == "DELETE":
                st["stories"].remove(s)
                return ok({})
        # ---- attachments
        if parts == ["attachments"]:
            if m == "POST":
                mp = _multipart(h)
                parent = mp.get("parent", (None, b""))[1].decode()
                _find("tasks", parent)
                fname, content = mp.get("file", ("file", b""))
                x = {"gid": _gid(), "name": fname, "task": parent, "_content": content}
                st["attachments"].append(x)
                return ok(_att_out(x, host), 200)
            return ok([_att_out(x, host) for x in st["attachments"] if x["task"] == q.get("parent")])
        if len(parts) == 2 and parts[0] == "attachments":
            x = _find("attachments", parts[1])
            if m == "GET":
                return ok(_att_out(x, host))
            if m == "DELETE":
                st["attachments"].remove(x)
                return ok({})
        # ---- teams
        if parts == ["teams"] and m == "POST":
            _find("workspaces", d.get("organization", ""))
            t = {"gid": _gid(), "name": d["name"], "description": d.get("description", ""), "organization": d["organization"], "members": []}
            st["teams"].append(t)
            return ok(_team_out(t), 201)
        if len(parts) >= 2 and parts[0] == "teams":
            t = _find("teams", parts[1])
            if len(parts) == 3 and parts[2] == "users":
                return ok([_uref(u) for u in t["members"]])
            if m == "GET":
                return ok(_team_out(t))
            if m == "PUT":
                t.update({k: v for k, v in d.items() if k in ("name", "description")})
                return ok(_team_out(t))
        # ---- tags
        if parts == ["tags"] and m == "POST":
            _find("workspaces", d.get("workspace", ""))
            t = {"gid": _gid(), "name": d["name"], "color": None, "workspace": d["workspace"]}
            st["tags"].append(t)
            return ok({"gid": t["gid"], "name": t["name"]}, 201)
        # ---- users
        if parts == ["users"]:
            return ok([_uref(u["gid"]) for u in st["users"]])
        if len(parts) >= 2 and parts[0] == "users":
            u = _user(parts[1])
            if u is None:
                return h.send(404, {"errors": [{"message": "user: Unknown object: " + parts[1]}]})
            if len(parts) == 3 and parts[2] == "teams":
                return ok([_team_out(t) for t in st["teams"] if u["gid"] in t["members"] and t["organization"] == q.get("organization")])
            return ok(dict(_uref(u["gid"]), workspaces=[{"gid": w["gid"], "name": w["name"]} for w in st["workspaces"]]))
    raise KeyError(path)


def _att_out(x, host):
    return {"gid": x["gid"], "name": x["name"], "size": len(x["_content"]), "host": "asana", "created_at": "2026-11-22T11:00:00.000Z",
            "download_url": host + P + "/files/" + x["gid"], "view_url": "https://app.asana.example/attachments/" + x["gid"],
            "permanent_url": "https://app.asana.example/attachments/" + x["gid"]}


def _new_task(st, d):
    t = {"gid": _gid(), "name": d.get("name", ""), "notes": d.get("notes", ""), "completed": bool(d.get("completed")),
         "assignee": (_user(d["assignee"]) or {}).get("gid") if d.get("assignee") else None, "due_on": d.get("due_on"),
         "projects": list(d.get("projects", [])), "memberships": [], "tags": list(d.get("tags", [])),
         "followers": [(_user(f) or {}).get("gid") for f in d.get("followers", [])], "parent": d.get("parent"),
         "custom_fields": [{"name": k, "display_value": v} for k, v in (d.get("custom_fields") or {}).items()]}
    for ms in d.get("memberships", []):
        t["memberships"].append([ms["project"], ms["section"]])
        t["projects"].append(ms["project"])
    for pgid in t["projects"]:
        _find("projects", pgid)
    st["tasks"].append(t)
    return t


ROUTES = [(P + "/", asana, "biz_asana")]
