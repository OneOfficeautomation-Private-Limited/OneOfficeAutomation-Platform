"""Okta Management API (v1) for the command unit tests, under /biz/okta: users, lifecycle, sessions, groups.
Made-up org; ids are 20 characters like Okta's. Accepts "SSWS <token>" or "Bearer <token>"."""
import re
from urllib.parse import unquote

P = "/biz/okta"


def _u(n, first, last, status="ACTIVE"):
    login = "%s.%s@ut.example" % (first.lower(), last.lower())
    return {"id": "00uut%015d" % n, "status": status, "created": "2026-01-0%dT08:00:00.000Z" % n, "lastLogin": None,
            "profile": {"login": login, "email": login, "firstName": first, "lastName": last}}


SEED = {"biz_okta": {
    "users": [_u(1, "Ann", "Lee"), _u(2, "Ben", "Ray"), _u(3, "Cara", "Ng", "LOCKED_OUT"), _u(4, "Dev", "Rao", "SUSPENDED")],
    "groups": [
        {"id": "00gut000000000000001", "type": "OKTA_GROUP", "profile": {"name": "UT Finance", "description": "Finance team"}, "_members": ["00uut000000000000001"]},
        {"id": "00gut000000000000002", "type": "OKTA_GROUP", "profile": {"name": "UT Sales", "description": "Sales team"}, "_members": []},
        {"id": "00gut000000000000003", "type": "APP_GROUP", "profile": {"name": "UT App Users", "description": "From an app"}, "_members": ["00uut000000000000001"]},
    ],
    "next": 10,
}}


def _authorized(h):
    a = h.headers.get("Authorization", "")
    if not (a.startswith("SSWS ") or a.startswith("Bearer ")) or len(a) < 12:
        h.send(401, {"errorCode": "E0000011", "errorSummary": "Invalid token provided"})
        return False
    return True


def _pub(x):
    return {k: v for k, v in x.items() if not k.startswith("_")}


def okta(h, m, path, q):
    if not _authorized(h):
        return
    st = core.STATE.setdefault("biz_okta", {"users": [], "groups": [], "next": 10})
    users, groups = st["users"], st["groups"]
    p = path[len(P):]

    def find(ref):
        ref = unquote(ref).lower()
        return next((u for u in users if u["id"].lower() == ref or u["profile"]["login"].lower() == ref), None)

    def nf():
        return h.send(404, {"errorCode": "E0000007", "errorSummary": "Not found: Resource not found"})

    with core.LOCK:
        if p == "/api/v1/users" and m == "POST":
            b = h.body()
            prof = b.get("profile") or {}
            if not prof.get("login") or any(u["profile"]["login"].lower() == prof["login"].lower() for u in users):
                return h.send(400, {"errorCode": "E0000001", "errorSummary": "Api validation failed: login",
                                    "errorCauses": [{"errorSummary": "login: An object with this field already exists in the current organization"}]})
            st["next"] += 1
            u = {"id": "00uut%015d" % st["next"], "status": "ACTIVE" if q.get("activate", "true") == "true" else "STAGED",
                 "created": core.now_iso(), "lastLogin": None, "profile": prof}
            if "credentials" in b and q.get("activate", "true") == "true":
                u["status"] = "ACTIVE"
            elif q.get("activate", "true") == "true":
                u["status"] = "PROVISIONED"
            users.append(u)
            for gid in b.get("groupIds", []):
                g = next((x for x in groups if x["id"] == gid), None)
                if g:
                    g["_members"].append(u["id"])
            return h.send(200, u)
        if p == "/api/v1/users" and m == "GET":
            found = users
            if q.get("q"):
                s = q["q"].lower()
                found = [u for u in users if any(str(u["profile"].get(k, "")).lower().startswith(s) for k in ("login", "email", "firstName", "lastName"))]
            elif q.get("search"):
                ms = re.match(r'(profile\.\w+|status)\s+eq\s+"([^"]*)"', q["search"])
                if not ms:
                    return h.send(400, {"errorCode": "E0000031", "errorSummary": "Invalid search criteria."})
                k, v = ms.groups()
                found = [u for u in users if str(u["status"] if k == "status" else u["profile"].get(k.split(".")[1], "")) == v]
            return h.send(200, found[:int(q.get("limit", 200))])
        mu = re.match(r"/api/v1/users/([^/]+)(/groups|/sessions|/lifecycle/(\w+))?$", p)
        if mu:
            u = find(mu.group(1))
            if u is None:
                return nf()
            if mu.group(2) == "/groups":
                return h.send(200, [_pub(g) for g in groups if u["id"] in g["_members"]])
            if mu.group(2) == "/sessions" and m == "DELETE":
                u["_sessions_cleared"] = q.get("oauthTokens", "false")
                return h.send(204)
            op = mu.group(3)
            if op and m == "POST":
                send_email = q.get("sendEmail", "true") == "true"
                if op == "activate":
                    u["status"] = "ACTIVE"
                    return h.send(200, {} if send_email else {"activationUrl": "https://ut-org.example/welcome/ut-activation-" + u["id"][-4:], "activationToken": "ut-act"})
                if op == "deactivate":
                    u["status"] = "DEPROVISIONED"
                    return h.send(200, {})
                if op == "suspend":
                    if u["status"] != "ACTIVE":
                        return h.send(400, {"errorCode": "E0000001", "errorSummary": "Api validation failed: Cannot suspend a user that is not active"})
                    u["status"] = "SUSPENDED"
                    return h.send(200, {})
                if op == "unsuspend":
                    u["status"] = "ACTIVE"
                    return h.send(200, {})
                if op == "unlock":
                    u["status"] = "ACTIVE"
                    return h.send(200, {})
                if op == "expire_password":
                    u["status"] = "PASSWORD_EXPIRED"
                    return h.send(200, {"tempPassword": "Ut-Temp-1234"} if q.get("tempPassword") == "true" else _pub(u))
                if op == "reset_password":
                    u["status"] = "RECOVERY"
                    return h.send(200, {} if send_email else {"resetPasswordUrl": "https://ut-org.example/reset_password/ut-reset-" + u["id"][-4:]})
                return nf()
            if m == "GET":
                return h.send(200, u)
            if m == "POST":
                u["profile"].update((h.body() or {}).get("profile") or {})
                return h.send(200, u)
            if m == "DELETE":
                if u["status"] != "DEPROVISIONED":
                    return h.send(403, {"errorCode": "E0000001", "errorSummary": "Deactivate the user first"})
                users.remove(u)
                for g in groups:
                    if u["id"] in g["_members"]:
                        g["_members"].remove(u["id"])
                return h.send(204)
        if p == "/api/v1/groups" and m == "GET":
            found = groups
            if q.get("q"):
                found = [g for g in groups if g["profile"]["name"].lower().startswith(q["q"].lower())]
            elif q.get("search"):
                ms = re.match(r'(profile\.name|type)\s+eq\s+"([^"]*)"', q["search"])
                if not ms:
                    return h.send(400, {"errorCode": "E0000031", "errorSummary": "Invalid search criteria."})
                k, v = ms.groups()
                found = [g for g in groups if (g["type"] if k == "type" else g["profile"]["name"]) == v]
            return h.send(200, [_pub(g) for g in found[:int(q.get("limit", 200))]])
        mg = re.match(r"/api/v1/groups/([^/]+)/users/([^/]+)$", p)
        if mg:
            g = next((x for x in groups if x["id"] == mg.group(1)), None)
            u = find(mg.group(2))
            if g is None or u is None:
                return nf()
            if m == "PUT" and u["id"] not in g["_members"]:
                g["_members"].append(u["id"])
            if m == "DELETE" and u["id"] in g["_members"]:
                g["_members"].remove(u["id"])
            return h.send(204)
    raise KeyError(path)


ROUTES = [(P + "/", okta, "biz_okta")]
