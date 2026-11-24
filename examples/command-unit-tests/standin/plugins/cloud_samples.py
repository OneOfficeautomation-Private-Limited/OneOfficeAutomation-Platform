"""Command samples as stand-in answers (cloud unit-test agents).

The command samples (platform/cli/src/test/resources/command-samples) script the answers of an outside service in
their "mock" list. The unit-test agents built from those samples point the command at
    $standin_url$/mock/<file number>-<sample index>/...
and this plugin answers from that sample's list exactly as the samples test does: routes in order, the path matched
exactly (or as a prefix when it ends in *), optionally only when the request body contains a given text.

It also serves the files an agent wrote in its own run folder, for samples that read from a local web server:
    $standin_url$/web/<agent name>/<file>      (GET returns the file; any other method also answers with the file)
"""
import glob
import json
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
SAMPLES = os.path.abspath(os.path.join(HERE, "..", "..", "..", "..", "platform", "cli", "src", "test", "resources", "command-samples"))
_MOCKS = {}


def _load():
    if _MOCKS:
        return _MOCKS
    for path in sorted(glob.glob(os.path.join(SAMPLES, "*.json"))):
        num = os.path.basename(path).split("-", 1)[0]
        try:
            cases = json.loads(open(path, encoding="utf-8").read(), strict=False)
        except ValueError:
            continue
        for i, c in enumerate(cases):
            if isinstance(c, dict) and c.get("mock"):
                _MOCKS["%s-%d" % (num, i)] = c["mock"]
    return _MOCKS


def mock(h, m, path, q):
    mm = re.match(r"/mock/([0-9]+-[0-9]+)(/.*)?$", path)
    if not mm:
        raise KeyError(path)
    routes = _load().get(mm.group(1))
    if routes is None:
        raise KeyError(path)
    rest = mm.group(2) or "/"
    body = h.raw().decode("utf-8", "replace")
    for rt in routes:
        want = str(rt.get("path", "*"))
        ok = rest.startswith(want[:-1]) if want.endswith("*") else rest == want
        if ok and isinstance(rt.get("contains"), str) and rt["contains"] not in body:
            ok = False
        if ok and str(rt.get("method", m)).upper() != m:
            ok = False
        if ok:
            b = rt.get("body", {})
            status = int(rt.get("status", 200))
            if isinstance(b, str):
                return h.send(status, b if b else None, ctype="text/plain")
            return h.send(status, b)
    return h.send(404, {"error": "no mock route"})


def web(h, m, path, q):
    mw = re.match(r"/web/([^/]+)/(.+)$", path)
    if not mw:
        raise KeyError(path)
    base = os.path.join(core.BASE, "runs", mw.group(1))
    f = os.path.normpath(os.path.join(base, mw.group(2)))
    if not f.startswith(os.path.normpath(base)) or not os.path.isfile(f):
        return h.send(404, "not found", ctype="text/plain")
    data = open(f, "rb").read()
    ctype = "application/json" if f.endswith(".json") else "text/xml" if f.endswith(".xml") else "text/plain"
    h.send_response(200)
    h.send_header("Content-Type", ctype)
    h.send_header("Content-Length", str(len(data)))
    h.end_headers()
    h.wfile.write(data)


ROUTES = [("/mock/", mock, None), ("/web/", web, None)]
SEED = {}
