"""MCP server (FR-AI-009, architecture s12). Exposes published TaskBots and
ApiTasks as MCP tools for external AI clients, under the caller's permissions.
Automations tagged 'sensitive' are never run directly from MCP: the call creates
an approval request and returns its id. Transport: newline-delimited JSON-RPC
2.0 over stdio. The production server lives in ooa-core with OAuth2 auth."""
from __future__ import annotations

import json
import sys
from typing import Any

from ..sdk.errors import OoaError
from ..sdk.types import to_jsonable
from .approvals import ApprovalRequest
from .tools import TOOL_KINDS, build_tool

PROTOCOL_VERSION = "2025-06-18"


class MCPServer:
    def __init__(self, platform, principal):
        self.p = platform
        self.principal = principal

    def _tools(self) -> dict[str, Any]:
        out = {}
        for item in self.p.repository.list():
            if item["kind"] not in TOOL_KINDS:
                continue
            flow = self.p.repository.get(item["automationId"])
            if not flow.get("description"):
                continue
            try:
                self.p.policy.check(self.principal, "automation.run", "automation", flow["id"])
            except OoaError:
                continue  # only list what the caller may run (Policy audits the deny)
            try:
                spec = build_tool(flow, sensitive="sensitive" in flow.get("tags", []))
            except OoaError:
                continue
            out[spec.name] = spec
        return out

    def handle(self, msg: dict) -> dict | None:
        mid, method, params = msg.get("id"), msg.get("method"), msg.get("params") or {}
        if mid is None:  # notification
            return None
        try:
            if method == "initialize":
                result = {"protocolVersion": PROTOCOL_VERSION, "capabilities": {"tools": {"listChanged": False}},
                          "serverInfo": {"name": "ONEOFFICEAUTOMATION", "version": "0.1.0"},
                          "instructions": "Each tool runs a published ONEOFFICEAUTOMATION automation under your permissions."}
            elif method == "ping":
                result = {}
            elif method == "tools/list":
                result = {"tools": [t.to_mcp() for t in self._tools().values()]}
            elif method == "tools/call":
                result = self._call(params.get("name"), params.get("arguments") or {})
            else:
                return {"jsonrpc": "2.0", "id": mid, "error": {"code": -32601, "message": f"Method not found: {method}"}}
        except OoaError as e:
            result = {"content": [{"type": "text", "text": str(e)}], "isError": True}
        return {"jsonrpc": "2.0", "id": mid, "result": result}

    def _call(self, name: str, args: dict) -> dict:
        import jsonschema
        tool = self._tools().get(name)
        if tool is None:
            return {"content": [{"type": "text", "text": f"Unknown or unauthorised tool '{name}'"}], "isError": True}
        try:
            jsonschema.validate(args, tool.input_schema)
        except jsonschema.ValidationError as e:
            return {"content": [{"type": "text", "text": f"Invalid arguments: {e.message}"}], "isError": True}
        if tool.sensitive:
            req = self.p.approvals.request(ApprovalRequest(agent_run_id="mcp", agent_id="mcp-client", tool=name,
                                                           automation_id=tool.automation_id, arguments=args,
                                                           requested_for=self.principal.user))
            self.p.audit.record(self.principal.user, "mcp.approval.requested", f"automation:{tool.automation_id}",
                                details={"approvalId": req.id})
            text = f"'{name}' requires human approval. Approval request {req.id} was created in the Assist inbox."
            return {"content": [{"type": "text", "text": text}], "structuredContent": {"approvalId": req.id,
                                                                                         "status": "pending"}}
        res = self.p.run(tool.flow, args, self.principal)
        payload = {"status": res.status, "outputs": to_jsonable(res.outputs), "runId": res.run_id}
        if not res.ok:
            payload = {"status": res.status, "error": res.error, "runId": res.run_id}
        return {"content": [{"type": "text", "text": json.dumps(payload)}], "structuredContent": payload,
                "isError": not res.ok}

    def serve_stdio(self, stdin=None, stdout=None) -> None:
        stdin, stdout = stdin or sys.stdin, stdout or sys.stdout
        for line in stdin:
            line = line.strip()
            if not line:
                continue
            try:
                msg = json.loads(line)
            except json.JSONDecodeError:
                resp = {"jsonrpc": "2.0", "id": None, "error": {"code": -32700, "message": "Parse error"}}
            else:
                resp = self.handle(msg)
            if resp is not None:
                stdout.write(json.dumps(resp) + "\n")
                stdout.flush()
