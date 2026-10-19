"""Agent runtime (FR-AI-006/007, FR-GAI-004, architecture s9).

An agent is an OOA Flow of kind Agent: instructions, a tool allow-list of
published automations, a step limit and a cost ceiling. The runtime:

  * offers the model only the declared tools, rejects any other tool name
  * validates tool arguments against the tool's input schema before running
  * runs each tool as a normal bot run through the Runner (same policy, audit, events)
  * pauses on sensitive tools until a human approves (Assist inbox)
  * stops at max steps or the cost ceiling
  * routes every model call through the AI Gateway (masking, budgets, governance)
  * checkpoints state after every step so a paused or crashed run resumes exactly

In production the loop is a Temporal workflow (LangChain4j on Temporal); the
checkpoint JSON here is the same state that workflow history would hold."""
from __future__ import annotations

import json
import time
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import jsonschema

from ..ai.gateway import AIGateway, Caller, _strip_fences
from ..governance import AuditLog, Policy, Principal, Repository
from ..runner.events import EventSink, ListSink
from ..runner.expressions import resolve
from ..runner.interpreter import Runner
from ..runner.validate import validate_flow
from ..sdk.errors import OoaError
from ..sdk.registry import PackageRegistry
from ..sdk.types import to_jsonable
from .approvals import APPROVED, DENIED, PENDING, ApprovalGate, ApprovalRequest
from .tools import ToolSpec, build_tool

MAX_TOOL_RESULT_CHARS = 8000

GUARDRAILS = """Operating rules (enforced by the platform):
- Act only through the tools provided. Never claim an action happened unless a tool result confirms it.
- Use only facts from the user's request and tool results; if information is missing, say so.
- Some tools require human approval; a reviewer may deny them. Respect a denial and do not retry the same action.
- When you are finished, reply with your final answer and no tool calls."""


@dataclass
class AgentResult:
    run_id: str
    agent_id: str
    status: str  # completed | awaiting_approval | denied_stop | limit_reached | failed
    answer: str | None = None
    parsed: Any = None
    steps: int = 0
    cost_usd: float = 0.0
    approval_id: str | None = None
    error: dict | None = None
    trace: list[dict] = field(default_factory=list)

    def to_dict(self) -> dict:
        return {"runId": self.run_id, "agentId": self.agent_id, "status": self.status, "answer": self.answer,
                "parsed": to_jsonable(self.parsed), "steps": self.steps, "costUsd": round(self.cost_usd, 6),
                "approvalId": self.approval_id, "error": self.error, "trace": self.trace}


class AgentRuntime:
    def __init__(self, *, gateway: AIGateway, registry: PackageRegistry, repository: Repository,
                 approvals: ApprovalGate, policy: Policy | None = None, audit: AuditLog | None = None,
                 services: dict[str, Any] | None = None, checkpoint_dir: Path | None = None,
                 sink: EventSink | None = None, work_dir: Path | None = None):
        self.gateway = gateway
        self.registry = registry
        self.repository = repository
        self.approvals = approvals
        self.audit = audit or AuditLog()
        self.policy = policy or Policy(self.audit)
        self.services = services or {}
        self.checkpoint_dir = Path(checkpoint_dir) if checkpoint_dir else None
        self.sink = sink or ListSink()
        self.work_dir = work_dir
        self._memory: dict[str, dict] = {}

    # ---------- public API ----------
    def run(self, agent: str | dict, inputs: dict | None, principal: Principal) -> AgentResult:
        flow = self.repository.get(agent) if isinstance(agent, str) else agent
        validate_flow(flow)
        if flow["kind"] != "Agent":
            raise OoaError(f"'{flow['id']}' is not an Agent", code="AGENT.WRONG_KIND")
        self.policy.check(principal, "agent.run", "agent", flow["id"])
        spec = flow["agent"]
        inputs = inputs or {}
        goal = self._goal(flow, inputs)
        system = spec["instructions"].strip() + "\n\n" + GUARDRAILS
        if spec.get("outputSchema"):
            system += "\n- Your final answer must be JSON only, matching this schema:\n" + json.dumps(spec["outputSchema"])
        state = {
            "runId": f"agt_{uuid.uuid4().hex[:12]}", "agentId": flow["id"], "agentVersion": flow.get("version"),
            "principal": {"user": principal.user, "roles": list(principal.roles), "team": principal.team,
                          "tenant": principal.tenant, "scopes": {k: sorted(v) for k, v in principal.scopes.items()}},
            "messages": [{"role": "system", "content": system}, {"role": "user", "content": goal}],
            "queue": [], "steps": 0, "costUsd": 0.0, "status": "running", "trace": [], "startedAt": time.time(),
            "pending": None, "flow": flow,
        }
        self.audit.record(principal.user, "agent.run.started", f"agent:{flow['id']}",
                          details={"runId": state["runId"], "goal": goal[:500]}, tenant=principal.tenant)
        return self._drive(state)

    def resume(self, run_id: str) -> AgentResult:
        state = self._load(run_id)
        if state["status"] != "awaiting_approval":
            raise OoaError(f"Agent run {run_id} is {state['status']}, not awaiting approval", code="AGENT.NOT_PAUSED")
        req = self.approvals.get(state["pending"]["approvalId"])
        if req.status == PENDING:
            return self._result(state, "awaiting_approval", approval_id=req.id)
        state["status"] = "running"
        return self._drive(state)

    # ---------- loop ----------
    def _drive(self, state: dict) -> AgentResult:
        flow = state["flow"]
        spec = flow["agent"]
        principal = self._principal(state)
        tools = self._tools(flow, principal)
        max_steps = spec.get("maxSteps", 12)
        max_cost = spec.get("maxCostUsd", 1.0)
        caller = Caller(tenant=principal.tenant, user=principal.user, roles=principal.roles, team=principal.team,
                        automation_id=flow["id"], run_id=state["runId"], purpose="agent")
        try:
            while True:
                # 1. drain tool calls from the last model turn
                while state["queue"]:
                    tc = state["queue"][0]
                    outcome = self._tool_call(state, tc, tools, principal)
                    if outcome == "PENDING":
                        state["status"] = "awaiting_approval"
                        self._save(state)
                        return self._result(state, "awaiting_approval", approval_id=state["pending"]["approvalId"])
                    state["queue"].pop(0)
                    state["messages"].append({"role": "tool", "tool_call_id": tc["id"], "name": tc["name"],
                                              "content": outcome})
                    self._save(state)
                # 2. limits
                if state["steps"] >= max_steps:
                    return self._finish(state, "limit_reached", error=OoaError(
                        f"Agent stopped after {max_steps} steps", code="AGENT.STEP_LIMIT",
                        suggested_fix="Raise maxSteps or narrow the agent's instructions.").to_dict())
                if state["costUsd"] >= max_cost:
                    return self._finish(state, "limit_reached", error=OoaError(
                        f"Agent reached its cost ceiling of ${max_cost:.2f}", code="AGENT.COST_LIMIT",
                        suggested_fix="Raise maxCostUsd or use a cheaper model connection.").to_dict())
                # 3. next model turn
                resp = self.gateway.chat(state["messages"], caller=caller, connection=spec.get("modelConnection"),
                                         tools=[t.for_model() for t in tools.values()],
                                         temperature=spec.get("temperature", 0))
                state["steps"] += 1
                state["costUsd"] += resp.cost_usd
                calls = [{"id": tc.id, "name": tc.name, "arguments": tc.arguments} for tc in resp.tool_calls]
                state["messages"].append({"role": "assistant", "content": resp.content, "tool_calls": calls})
                state["trace"].append({"type": "model", "step": state["steps"], "content": resp.content,
                                       "toolCalls": [{"name": c["name"], "arguments": c["arguments"]} for c in calls],
                                       "tokens": resp.input_tokens + resp.output_tokens,
                                       "costUsd": round(resp.cost_usd, 6), "callId": resp.call_id,
                                       "masked": resp.masked})
                if calls:
                    state["queue"] = calls
                    self._save(state)
                    continue
                # 4. final answer
                answer = resp.content or ""
                parsed = None
                if spec.get("outputSchema"):
                    try:
                        parsed = json.loads(_strip_fences(answer))
                        jsonschema.validate(parsed, spec["outputSchema"])
                    except (ValueError, jsonschema.ValidationError) as e:
                        state["messages"].append({"role": "user", "content":
                                                  f"Your answer did not match the required JSON schema ({str(e)[:200]}). "
                                                  "Reply again with valid JSON only."})
                        state["trace"].append({"type": "guardrail", "message": "final answer failed schema; asked to repair"})
                        continue
                return self._finish(state, "completed", answer=answer, parsed=parsed)
        except OoaError as e:
            return self._finish(state, "failed", error=e.to_dict())

    def _tool_call(self, state: dict, tc: dict, tools: dict[str, ToolSpec], principal: Principal) -> str:
        """Returns the tool-result text for the model, or 'PENDING'."""
        tool = tools.get(tc["name"])
        if tool is None:
            state["trace"].append({"type": "guardrail", "message": f"rejected unknown tool '{tc['name']}'"})
            self.audit.record(principal.user, "agent.tool.rejected", f"agent:{state['agentId']}", result="denied",
                              details={"runId": state["runId"], "tool": tc["name"]}, tenant=principal.tenant)
            return json.dumps({"status": "rejected", "error": {
                "code": "AGENT.UNKNOWN_TOOL", "message": f"Tool '{tc['name']}' is not available to this agent.",
                "suggestedFix": f"Use one of: {', '.join(sorted(tools))}."}})
        try:
            jsonschema.validate(tc["arguments"], tool.input_schema)
        except jsonschema.ValidationError as e:
            state["trace"].append({"type": "guardrail", "message": f"invalid arguments for {tool.name}: {e.message}"})
            return json.dumps({"status": "rejected", "error": {"code": "AGENT.BAD_ARGUMENTS", "message": e.message,
                                                               "suggestedFix": "Call the tool again with arguments matching its schema."}})
        try:  # check permission before asking a human to approve something the caller may not run
            self.policy.check(principal, "automation.run", "automation", tool.automation_id)
        except OoaError as e:
            state["trace"].append({"type": "guardrail", "message": f"policy denied {tool.name}"})
            return json.dumps({"status": "rejected", "error": {"code": e.code, "message": e.message,
                                                               "suggestedFix": e.suggested_fix}})
        if tool.sensitive:
            pending = state.get("pending")
            if pending and pending["toolCallId"] == tc["id"]:
                req = self.approvals.get(pending["approvalId"])
            else:
                req = self.approvals.request(ApprovalRequest(
                    agent_run_id=state["runId"], agent_id=state["agentId"], tool=tool.name,
                    automation_id=tool.automation_id, arguments=tc["arguments"], requested_for=principal.user,
                    reason=self._last_assistant_text(state)))
                state["pending"] = {"approvalId": req.id, "toolCallId": tc["id"]}
                self.audit.record(principal.user, "agent.approval.requested", f"automation:{tool.automation_id}",
                                  details={"runId": state["runId"], "approvalId": req.id, "arguments": tc["arguments"]},
                                  tenant=principal.tenant)
                state["trace"].append({"type": "approval", "tool": tool.name, "approvalId": req.id, "status": req.status})
            if req.status == PENDING:
                return "PENDING"
            self.audit.record(req.decided_by or "?", f"agent.approval.{req.status}", f"automation:{tool.automation_id}",
                              details={"runId": state["runId"], "approvalId": req.id, "comment": req.comment},
                              tenant=principal.tenant)
            state["pending"] = None
            if req.status == DENIED:
                state["trace"].append({"type": "approval", "tool": tool.name, "approvalId": req.id, "status": DENIED})
                return json.dumps({"status": "denied", "error": {
                    "code": "AGENT.APPROVAL_DENIED", "message": f"A human reviewer denied this action. {req.comment or ''}".strip(),
                    "suggestedFix": "Do not retry; explain the outcome to the user."}})
            state["trace"].append({"type": "approval", "tool": tool.name, "approvalId": req.id, "status": APPROVED,
                                   "decidedBy": req.decided_by})
        # execute as a normal bot run, under the caller's permissions
        runner = Runner(self.registry, services={**self.services, "repository": self.repository},
                        sink=self.sink, work_dir=self.work_dir, tenant_id=principal.tenant, principal=principal.user)
        res = runner.run(tool.flow, tc["arguments"], run_id=f"{state['runId']}.{tc['id']}")
        self.audit.record(principal.user, "agent.tool.called", f"automation:{tool.automation_id}",
                          result="success" if res.ok else "failed",
                          details={"runId": state["runId"], "botRunId": res.run_id, "status": res.status,
                                   "errorCode": (res.error or {}).get("code")}, tenant=principal.tenant)
        payload = {"status": res.status, "outputs": to_jsonable(res.outputs)} if res.ok else \
                  {"status": res.status, "error": {k: (res.error or {}).get(k) for k in ("code", "message", "suggestedFix")}}
        state["trace"].append({"type": "tool", "tool": tool.name, "arguments": tc["arguments"], "botRunId": res.run_id,
                               "status": res.status, "durationMs": res.duration_ms,
                               "result": payload.get("outputs") or payload.get("error")})
        text = json.dumps(payload, default=str)
        return text if len(text) <= MAX_TOOL_RESULT_CHARS else text[:MAX_TOOL_RESULT_CHARS] + '..."(truncated)"'

    # ---------- helpers ----------
    def _tools(self, flow: dict, principal: Principal) -> dict[str, ToolSpec]:
        out: dict[str, ToolSpec] = {}
        for t in flow["agent"]["tools"]:
            version = t.get("version", "production")
            bot = self.repository.get(t["automation"], version)
            spec = build_tool(bot, sensitive=t.get("sensitive", False), description=t.get("description"), version=version)
            out[spec.name] = spec
        return out

    @staticmethod
    def _goal(flow: dict, inputs: dict) -> str:
        tpl = flow["agent"].get("goalTemplate")
        declared = {v["name"] for v in flow.get("variables", []) if v.get("direction") == "input"}
        unknown = set(inputs) - declared
        if unknown:
            raise OoaError(f"Unknown agent input(s): {', '.join(sorted(unknown))}", code="RUN.INPUT_UNKNOWN")
        for v in flow.get("variables", []):
            if v.get("direction") == "input" and v.get("required") and v["name"] not in inputs:
                raise OoaError(f"Required input '{v['name']}' is missing", code="RUN.INPUT_MISSING")
        if tpl:
            return str(resolve(tpl, lambda n: inputs.get(n)))
        return json.dumps(inputs, default=str)

    @staticmethod
    def _last_assistant_text(state: dict) -> str:
        for m in reversed(state["messages"]):
            if m["role"] == "assistant" and m.get("content"):
                return m["content"][:500]
        return ""

    @staticmethod
    def _principal(state: dict) -> Principal:
        p = state["principal"]
        return Principal(user=p["user"], roles=tuple(p["roles"]), team=p["team"], tenant=p["tenant"],
                         scopes={k: set(v) for k, v in p.get("scopes", {}).items()})

    def _finish(self, state: dict, status: str, *, answer=None, parsed=None, error=None) -> AgentResult:
        state["status"] = status
        state["answer"], state["parsed"], state["error"] = answer, to_jsonable(parsed), error
        self._save(state)
        p = state["principal"]
        self.audit.record(p["user"], "agent.run.finished", f"agent:{state['agentId']}",
                          result="success" if status == "completed" else status,
                          details={"runId": state["runId"], "steps": state["steps"], "costUsd": round(state["costUsd"], 6),
                                   "errorCode": (error or {}).get("code")}, tenant=p["tenant"])
        return self._result(state, status, answer=answer, parsed=parsed, error=error)

    def _result(self, state, status, **kw) -> AgentResult:
        return AgentResult(state["runId"], state["agentId"], status, steps=state["steps"], cost_usd=state["costUsd"],
                           trace=list(state["trace"]), **kw)

    def _save(self, state: dict) -> None:
        self._memory[state["runId"]] = json.loads(json.dumps(state, default=str))
        if self.checkpoint_dir:
            self.checkpoint_dir.mkdir(parents=True, exist_ok=True)
            tmp = self.checkpoint_dir / f"{state['runId']}.json.tmp"
            tmp.write_text(json.dumps(state, default=str, indent=1))
            tmp.replace(self.checkpoint_dir / f"{state['runId']}.json")

    def _load(self, run_id: str) -> dict:
        if run_id in self._memory:
            return json.loads(json.dumps(self._memory[run_id]))
        if self.checkpoint_dir and (self.checkpoint_dir / f"{run_id}.json").exists():
            return json.loads((self.checkpoint_dir / f"{run_id}.json").read_text())
        raise OoaError(f"Agent run '{run_id}' not found", code="AGENT.RUN_NOT_FOUND")
