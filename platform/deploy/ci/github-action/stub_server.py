"""A stand-in for One CommandCenter's test-set API, for CI to try the GitHub Action: set tset_pass passes, tset_fail fails."""
import json
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

POLLS = {}


class H(BaseHTTPRequestHandler):
    def _send(self, code, body):
        b = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def do_POST(self):
        if self.headers.get("Authorization") != "Bearer test-key":
            return self._send(401, {"error": {"code": "CR.UNAUTHENTICATED"}})
        n = int(self.headers.get("Content-Length") or 0)
        json.loads(self.rfile.read(n) or b"{}")
        parts = self.path.strip("/").split("/")   # api/test-sets/<id>/run
        if len(parts) == 4 and parts[1] == "test-sets" and parts[3] == "run":
            return self._send(200, {"test_run_id": "tr_" + parts[2], "cases": 2, "started": 2})
        self._send(404, {})

    def do_GET(self):
        parts = self.path.strip("/").split("/")   # api/test-sets/runs/<tr>
        if len(parts) == 4 and parts[2] == "runs":
            tr = parts[3]
            POLLS[tr] = POLLS.get(tr, 0) + 1
            if POLLS[tr] < 2:
                return self._send(200, {"status": "running"})
            ok = tr.endswith("pass")
            return self._send(200, {"status": "passed" if ok else "failed", "passed": 2 if ok else 1, "total": 2,
                                    "results": [{"case_name": "a", "passed": True, "detail": None}, {"case_name": "b", "passed": ok, "detail": None if ok else "label was low"}]})
        self._send(404, {})

    def log_message(self, *a):
        pass


HTTPServer(("127.0.0.1", int(sys.argv[1])), H).serve_forever()
