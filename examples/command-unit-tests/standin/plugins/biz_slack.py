"""Slack Web API for the command unit tests, under /biz/slack/api/<method>: messages, channels, users, workspace,
direct conversations and the external file upload flow. Answers like Slack: HTTP 200 with ok true/false."""
import itertools
import json
import time

P = "/biz/slack"
_ts = itertools.count(1)

SEED = {"biz_slack": {
    "team": {"id": "TUT00001", "name": "UT Workspace", "domain": "ut-workspace", "email_domain": "ut.example"},
    "users": [
        {"id": "UUT0001", "name": "ann.lee", "real_name": "Ann Lee", "tz": "Europe/London", "is_bot": False, "deleted": False,
         "profile": {"email": "ann.lee@ut.example", "display_name": "ann", "real_name": "Ann Lee", "title": "Ops lead"}},
        {"id": "UUT0002", "name": "ben.ray", "real_name": "Ben Ray", "tz": "Asia/Kolkata", "is_bot": False, "deleted": False,
         "profile": {"email": "ben.ray@ut.example", "display_name": "ben", "real_name": "Ben Ray", "title": "Accountant"}},
        {"id": "UUTBOT1", "name": "ut-agent", "real_name": "UT Agent", "is_bot": True, "deleted": False,
         "profile": {"email": "", "display_name": "ut-agent", "real_name": "UT Agent", "title": ""}},
    ],
    "channels": [
        {"id": "CUT0001", "name": "ut-ops", "is_private": False, "is_archived": False, "is_im": False, "is_mpim": False, "created": 1767225600,
         "topic": {"value": "Operations alerts"}, "purpose": {"value": "Where the agents post"}, "_members": ["UUT0001", "UUT0002", "UUTBOT1"],
         "_messages": [{"ts": "1767600000.000100", "user": "UUT0001", "text": "Nightly run started", "reply_count": 1, "thread_ts": "1767600000.000100"},
                       {"ts": "1767610000.000100", "user": "UUT0002", "text": "Invoices loaded"}],
         "_replies": {"1767600000.000100": [{"ts": "1767600000.000100", "user": "UUT0001", "text": "Nightly run started", "thread_ts": "1767600000.000100"},
                                             {"ts": "1767600100.000100", "user": "UUTBOT1", "text": "All green", "thread_ts": "1767600000.000100"}]}},
        {"id": "CUT0002", "name": "ut-finance", "is_private": True, "is_archived": False, "is_im": False, "is_mpim": False, "created": 1767225600,
         "topic": {"value": ""}, "purpose": {"value": "Finance only"}, "_members": ["UUT0002", "UUTBOT1"], "_messages": [], "_replies": {}},
        {"id": "CUT0003", "name": "ut-old", "is_private": False, "is_archived": True, "is_im": False, "is_mpim": False, "created": 1735689600,
         "topic": {"value": ""}, "purpose": {"value": ""}, "_members": ["UUT0001"], "_messages": [], "_replies": {}},
    ],
    "uploads": {},
}}


def _st():
    return core.STATE.setdefault("biz_slack", {"team": {}, "users": [], "channels": [], "uploads": {}})


def _pub(c):
    out = {k: v for k, v in c.items() if not k.startswith("_")}
    out["num_members"] = len(c.get("_members", []))
    return out


def _new_ts():
    return "%d.%06d" % (int(time.time()), next(_ts))


def slack(h, m, path, q):
    p = path[len(P):]
    if p.startswith("/upload/") and m == "POST":           # the upload address files.getUploadURLExternal gave
        fid = p.split("/")[-1]
        with core.LOCK:
            up = _st()["uploads"].get(fid)
            if up is None:
                return h.send(404, "no such upload", ctype="text/plain")
            up["received"] = len(h.raw())
        return h.send(200, "OK - %d" % len(h.raw()), ctype="text/plain")
    a = h.headers.get("Authorization", "")
    if not a.startswith("Bearer ") or len(a) < 12:
        return h.send(200, {"ok": False, "error": "not_authed"})
    method = p.rsplit("/", 1)[-1]
    args = h.body() if m == "POST" else {}
    args = dict(args) if isinstance(args, dict) else {}
    args.update(q)
    st = _st()

    def ok(**kw):
        return h.send(200, {"ok": True, **kw})

    def err(e):
        return h.send(200, {"ok": False, "error": e})

    def chan(cid):
        return next((c for c in st["channels"] if c["id"] == cid), None)

    def user(uid):
        return next((u for u in st["users"] if u["id"] == uid), None)

    def kinds(c):
        return "im" if c.get("is_im") else "mpim" if c.get("is_mpim") else "private_channel" if c["is_private"] else "public_channel"

    def listing(found):
        lim, cur = int(args.get("limit") or 1000), int(args.get("cursor") or 0)
        page = found[cur:cur + lim]
        nxt = str(cur + lim) if cur + lim < len(found) else ""
        return ok(channels=[_pub(c) for c in page], response_metadata={"next_cursor": nxt})

    with core.LOCK:
        if method == "chat.postMessage":
            c = chan(args.get("channel"))
            if c is None:
                return err("channel_not_found")
            if not args.get("text") and not args.get("blocks"):
                return err("no_text")
            msg = {"ts": _new_ts(), "user": "UUTBOT1", "text": args.get("text", "")}
            for k in ("thread_ts", "blocks", "username", "icon_emoji", "unfurl_links", "mrkdwn"):
                if k in args:
                    msg[k] = args[k]
            if args.get("thread_ts"):
                c["_replies"].setdefault(args["thread_ts"], []).append(msg)
            else:
                c["_messages"].append(msg)
            return ok(channel=c["id"], ts=msg["ts"], message=msg)
        if method == "chat.update":
            c = chan(args.get("channel"))
            if c is None:
                return err("channel_not_found")
            msg = next((x for x in c["_messages"] if x["ts"] == args.get("ts")), None)
            if msg is None:
                return err("message_not_found")
            msg["text"] = args.get("text", msg["text"])
            if "blocks" in args:
                msg["blocks"] = args["blocks"]
            return ok(channel=c["id"], ts=msg["ts"], text=msg["text"])
        if method == "conversations.list":
            types = str(args.get("types") or "public_channel").split(",")
            arch = str(args.get("exclude_archived")).lower() != "true"
            return listing([c for c in st["channels"] if kinds(c) in types and (arch or not c["is_archived"])])
        if method == "users.conversations":
            uid = args.get("user") or "UUTBOT1"
            types = str(args.get("types") or "public_channel").split(",")
            arch = str(args.get("exclude_archived")).lower() != "true"
            return listing([c for c in st["channels"] if uid in c.get("_members", []) and kinds(c) in types and (arch or not c["is_archived"])])
        if method == "conversations.info":
            c = chan(args.get("channel"))
            return ok(channel=_pub(c)) if c else err("channel_not_found")
        if method in ("conversations.history", "conversations.replies"):
            c = chan(args.get("channel"))
            if c is None:
                return err("channel_not_found")
            msgs = c["_replies"].get(args.get("ts"), []) if method == "conversations.replies" else list(reversed(c["_messages"]))
            if args.get("oldest"):
                msgs = [x for x in msgs if float(x["ts"]) > float(args["oldest"])]
            if args.get("latest"):
                msgs = [x for x in msgs if float(x["ts"]) < float(args["latest"])]
            lim, cur = int(args.get("limit") or 100), int(args.get("cursor") or 0)
            page = msgs[cur:cur + lim]
            more = cur + lim < len(msgs)
            return ok(messages=page, has_more=more, response_metadata={"next_cursor": str(cur + lim) if more else ""})
        if method == "users.lookupByEmail":
            u = next((x for x in st["users"] if x["profile"]["email"].lower() == str(args.get("email", "")).lower() and x["profile"]["email"]), None)
            return ok(user=u) if u else err("users_not_found")
        if method == "users.info":
            u = user(args.get("user"))
            return ok(user=u) if u else err("user_not_found")
        if method == "conversations.open":
            ids = [x for x in str(args.get("users", "")).split(",") if x]
            if not ids or any(user(x) is None for x in ids):
                return err("user_not_found")
            members = sorted(set(ids + ["UUTBOT1"]))
            c = next((x for x in st["channels"] if (x.get("is_im") or x.get("is_mpim")) and sorted(x["_members"]) == members), None)
            if c is None:
                c = {"id": ("D" if len(ids) == 1 else "G") + "UT%04d" % (len(st["channels"]) + 1), "name": "", "is_private": True, "is_archived": False,
                     "is_im": len(ids) == 1, "is_mpim": len(ids) > 1, "user": ids[0] if len(ids) == 1 else None, "created": int(time.time()),
                     "_members": members, "_messages": [], "_replies": {}}
                st["channels"].append(c)
            return ok(channel={"id": c["id"]})
        if method == "auth.teams.list":
            return ok(teams=[{"id": st["team"]["id"], "name": st["team"]["name"]}, {"id": "TUT00002", "name": "UT Sandbox"}], response_metadata={"next_cursor": ""})
        if method == "team.info":
            if args.get("team") and args["team"] != st["team"]["id"]:
                return err("team_not_found")
            return ok(team=st["team"])
        if method == "files.getUploadURLExternal":
            if not args.get("filename") or not args.get("length"):
                return err("invalid_arguments")
            fid = "FUT%05d" % (len(st["uploads"]) + 1)
            st["uploads"][fid] = {"filename": args["filename"], "length": int(args["length"])}
            return ok(upload_url="http://%s%s/upload/%s" % (h.headers.get("Host"), P, fid), file_id=fid)
        if method == "files.completeUploadExternal":
            files = args.get("files") or []
            if isinstance(files, str):
                files = json.loads(files)
            c = chan(args.get("channel_id"))
            if c is None:
                return err("channel_not_found")
            for f in files:
                up = st["uploads"].get(f.get("id"))
                if up is None or up.get("received") != up["length"]:
                    return err("upload_incomplete")
            c["_messages"].append({"ts": _new_ts(), "user": "UUTBOT1", "text": args.get("initial_comment", ""), "files": files})
            return ok(files=[{"id": f["id"], "title": f.get("title")} for f in files])
    return h.send(200, {"ok": False, "error": "unknown_method"})


ROUTES = [(P + "/", slack, "biz_slack")]
