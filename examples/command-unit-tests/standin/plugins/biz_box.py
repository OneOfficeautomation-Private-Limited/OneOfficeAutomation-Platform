"""Box Content API 2.0 for the command unit tests, under /biz/box: folders, files (upload on /biz/box/api/2.0, the
rest on /biz/box/2.0), search, shared links and collaborations. Names are unique per folder (409 item_name_in_use)."""
import hashlib
import itertools
import json
import re

P = "/biz/box"

SEED = {"biz_box": {
    "items": [
        {"id": "0", "type": "folder", "name": "All Files", "parent": None},
        {"id": "5000001", "type": "folder", "name": "UT Finance", "parent": "0"},
        {"id": "5000002", "type": "folder", "name": "UT Archive", "parent": "0"},
        {"id": "6000001", "type": "file", "name": "invoice-2026-11.pdf", "parent": "5000001", "content": "PDF invoice November"},
        {"id": "6000002", "type": "file", "name": "budget.xlsx", "parent": "5000001", "content": "budget sheet"},
        {"id": "6000003", "type": "file", "name": "invoice-2026-10.pdf", "parent": "5000002", "content": "PDF invoice October"},
    ],
    "collaborations": [{"id": "7000001", "item": "5000001", "login": "asha.patel@ut.example", "name": "Asha Patel", "type": "user", "role": "editor"}],
}}

_ids = itertools.count(8000001)


def _st():
    return core.STATE.setdefault("biz_box", {"items": [], "collaborations": []})


def _item(iid, kind=None):
    it = next((x for x in _st()["items"] if x["id"] == iid and (kind is None or x["type"] == kind) and not x.get("trashed")), None)
    if it is None:
        raise KeyError(iid)
    return it


def _children(fid):
    return [x for x in _st()["items"] if x.get("parent") == fid and not x.get("trashed")]


def _ref(x):
    return {"type": x["type"], "id": x["id"], "name": x["name"]} if x else None


def _out(x):
    parent = next((p for p in _st()["items"] if p["id"] == x.get("parent")), None)
    o = {"type": x["type"], "id": x["id"], "name": x["name"], "modified_at": "2026-11-22T09:00:00Z", "created_at": "2026-11-01T09:00:00Z",
         "parent": _ref(parent), "owned_by": {"type": "user", "id": "9001", "login": "ut.owner@ut.example", "name": "UT Owner"},
         "shared_link": x.get("shared_link")}
    if x["type"] == "file":
        data = x["content"].encode() if isinstance(x["content"], str) else x["content"]
        o.update(size=len(data), sha1=hashlib.sha1(data).hexdigest())
    else:
        o.update(size=sum(len(c["content"]) if c["type"] == "file" else 0 for c in _children(x["id"])),
                 item_collection={"total_count": len(_children(x["id"])), "entries": [_ref(c) for c in _children(x["id"])]})
    return o


def _conflict(h, name):
    return h.send(409, {"type": "error", "status": 409, "code": "item_name_in_use", "message": "Item with the same name already exists",
                        "context_info": {"conflicts": [{"name": name}]}})


def _clash(fid, name, kind=None):
    return next((c for c in _children(fid) if c["name"].lower() == name.lower() and (kind is None or c["type"] == kind)), None)


def _multipart(h):
    m = re.search(r"boundary=\"?([^\";]+)", h.headers.get("Content-Type", ""))
    out = {}
    if m:
        for part in h.raw().split(b"--" + m.group(1).encode()):
            if b"\r\n\r\n" not in part:
                continue
            head, body = part.split(b"\r\n\r\n", 1)
            body = body[:-2] if body.endswith(b"\r\n") else body
            n = re.search(rb'name="([^"]+)"', head)
            if n:
                out[n.group(1).decode()] = body
    return out


def _bytes(h, data):
    h.send_response(200)
    h.send_header("Content-Type", "application/octet-stream")
    h.send_header("Content-Length", str(len(data)))
    h.end_headers()
    h.wfile.write(data)


def _page(h, q, rows):
    off, lim = int(q.get("offset", 0)), int(q.get("limit", 100))
    return h.send(200, {"total_count": len(rows), "entries": [_out(x) for x in rows[off:off + lim]], "offset": off, "limit": lim})


def box(h, m, path, q):
    a = h.headers.get("Authorization", "")
    if not a.startswith("Bearer ") or len(a) < 12:
        return h.send(401, {"type": "error", "status": 401, "code": "unauthorized"})
    p = path[len(P):]
    st = _st()
    with core.LOCK:
        mu = re.match(r"/api/2\.0/files/(?:(\d+)/)?content$", p)
        if mu and m == "POST":
            mp = _multipart(h)
            attrs = json.loads(mp.get("attributes", b"{}").decode() or "{}")
            data = mp.get("file", b"")
            if mu.group(1):                                  # a new version
                f = _item(mu.group(1), "file")
                f["content"] = data.decode("utf-8", "replace")
                f["version"] = f.get("version", 1) + 1
                return h.send(201, {"total_count": 1, "entries": [_out(f)]})
            fid = (attrs.get("parent") or {}).get("id", "0")
            _item(fid, "folder")
            if _clash(fid, attrs.get("name", "")):
                return _conflict(h, attrs["name"])
            f = {"id": str(next(_ids)), "type": "file", "name": attrs["name"], "parent": fid, "content": data.decode("utf-8", "replace")}
            st["items"].append(f)
            return h.send(201, {"total_count": 1, "entries": [_out(f)]})
        if not p.startswith("/2.0/"):
            raise KeyError(path)
        parts = p[len("/2.0/"):].split("/")
        b = h.body() if m in ("POST", "PUT") else {}
        b = b if isinstance(b, dict) else {}
        if parts == ["folders"] and m == "POST":
            fid = (b.get("parent") or {}).get("id", "0")
            _item(fid, "folder")
            if _clash(fid, b.get("name", "")):
                return _conflict(h, b["name"])
            x = {"id": str(next(_ids)), "type": "folder", "name": b["name"], "parent": fid}
            st["items"].append(x)
            return h.send(201, _out(x))
        if parts[0] == "folders" and len(parts) >= 2:
            x = _item(parts[1], "folder")
            if len(parts) == 3 and parts[2] == "items":
                return _page(h, q, _children(x["id"]))
            if len(parts) == 3 and parts[2] == "collaborations":
                return _collabs(h, x["id"])
            if m == "GET":
                return h.send(200, _out(x))
            if m == "PUT":
                return _put(h, x, b)
            if m == "DELETE":
                if _children(x["id"]) and q.get("recursive") != "true":
                    return h.send(400, {"type": "error", "status": 400, "code": "folder_not_empty", "message": "Cannot delete - folder not empty"})
                _trash(x)
                return h.send(204)
        if parts[0] == "files" and len(parts) >= 2:
            x = _item(parts[1], "file") if not (len(parts) == 3 and parts[2] == "trash") else next(i for i in st["items"] if i["id"] == parts[1])
            if len(parts) == 3:
                if parts[2] == "content":
                    return _bytes(h, x["content"].encode() if isinstance(x["content"], str) else x["content"])
                if parts[2] == "copy":
                    fid = (b.get("parent") or {}).get("id")
                    _item(fid, "folder")
                    name = b.get("name") or x["name"]
                    if _clash(fid, name):
                        return _conflict(h, name)
                    c = dict(x, id=str(next(_ids)), parent=fid, name=name)
                    c.pop("shared_link", None)
                    st["items"].append(c)
                    return h.send(201, _out(c))
                if parts[2] == "trash" and m == "DELETE":
                    st["items"].remove(x)
                    return h.send(204)
                if parts[2] == "collaborations":
                    return _collabs(h, x["id"])
            if m == "GET":
                return h.send(200, _out(x))
            if m == "PUT":
                return _put(h, x, b)
            if m == "DELETE":
                _trash(x)
                return h.send(204)
        if parts == ["search"]:
            words = q.get("query", "").lower().split()
            rows = [x for x in st["items"] if x["id"] != "0" and not x.get("trashed") and all(w in x["name"].lower() for w in words)]
            if q.get("type"):
                rows = [x for x in rows if x["type"] == q["type"]]
            if q.get("file_extensions"):
                exts = q["file_extensions"].split(",")
                rows = [x for x in rows if x["type"] == "file" and x["name"].rsplit(".", 1)[-1] in exts]
            if q.get("ancestor_folder_ids"):
                rows = [x for x in rows if _under(x, q["ancestor_folder_ids"])]
            return _page(h, q, rows)
        if parts == ["collaborations"] and m == "POST":
            it = b.get("item") or {}
            _item(it.get("id"), it.get("type"))
            who = b.get("accessible_by") or {}
            c = {"id": str(next(_ids)), "item": it["id"], "type": who.get("type"), "role": b.get("role"),
                 "login": who.get("login"), "name": "UT Group" if who.get("type") == "group" else who.get("login", "").split("@")[0], "group": who.get("id")}
            st["collaborations"].append(c)
            return h.send(201, {"type": "collaboration", "id": c["id"], "role": c["role"], "status": "accepted"})
        if len(parts) == 2 and parts[0] == "collaborations" and m == "DELETE":
            c = next((c for c in st["collaborations"] if c["id"] == parts[1]), None)
            if c is None:
                raise KeyError(parts[1])
            st["collaborations"].remove(c)
            return h.send(204)
    raise KeyError(path)


def _under(x, fid):
    items = {i["id"]: i for i in _st()["items"]}
    p = x.get("parent")
    while p is not None:
        if p == fid:
            return True
        p = items.get(p, {}).get("parent")
    return False


def _trash(x):
    x["trashed"] = True
    for c in _children(x["id"]):
        _trash(c)


def _put(h, x, b):
    if "parent" in b:
        fid = b["parent"]["id"]
        _item(fid, "folder")
        if _clash(fid, x["name"]):
            return _conflict(h, x["name"])
        x["parent"] = fid
    if "name" in b:
        if _clash(x["parent"], b["name"]):
            return _conflict(h, b["name"])
        x["name"] = b["name"]
    if "shared_link" in b:
        sl = b["shared_link"]
        if sl is None:
            x.pop("shared_link", None)
        else:
            x["shared_link"] = {"url": "https://ut.app.box.example/s/" + x["id"], "access": sl.get("access"),
                                "download_url": "https://ut.app.box.example/shared/static/" + x["id"] if x["type"] == "file" and (sl.get("permissions") or {}).get("can_download", True) else None,
                                "unshared_at": sl.get("unshared_at"), "permissions": sl.get("permissions")}
    return h.send(200, _out(x))


def _collabs(h, iid):
    rows = [c for c in _st()["collaborations"] if c["item"] == iid]
    return h.send(200, {"entries": [{"type": "collaboration", "id": c["id"], "role": c["role"], "status": "accepted",
                                     "accessible_by": {"type": c["type"], "id": c.get("group") or "9100", "name": c["name"], "login": c.get("login")}} for c in rows],
                        "next_marker": None})


ROUTES = [(P + "/", box, "biz_box")]
