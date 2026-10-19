"""Agent runtime guardrails, approvals and resume, on the refund journey (spec 9.6)."""
import json
import shutil

import pytest

from conftest import REFUND, load
from ooa.agents import AutoDecision, InboxApproval
from ooa.ai import AIGateway, ModelConnection, ScriptedProvider
from ooa.governance import Principal
from ooa.platform import Platform

EMAIL = "Order ORD-10042 arrived cracked. Refund please. jane.doe@example.com"
OPS = Principal("ops.anna", roles=("Automation Runner", "AI Admin"))


def make(tmp_path, script, approvals=None, max_cost=None, connection_kw=None):
    work = tmp_path / "work"
    shutil.copytree(REFUND / "data", work / "data")
    gw = AIGateway()
    gw.add_connection(ModelConnection("default", ScriptedProvider(script), "scripted", 3.0, 15.0, **(connection_kw or {})))
    p = Platform(gateway=gw, approvals=approvals or InboxApproval(), work_dir=work)
    for f in sorted((REFUND / "bots").glob("*.json")):
        p.repository.check_in(load(f), registry=p.registry)
    agent = load(REFUND / "refund_agent.flow.json")
    if max_cost is not None:
        agent["agent"]["maxCostUsd"] = max_cost
    p.repository.check_in(agent)
    return p, work


def test_happy_path_with_inbox_approval(tmp_path):
    p, work = make(tmp_path, load(REFUND / "demo_script.json"))
    r = p.agents.run("refund_agent", {"customer_email": EMAIL}, OPS)
    assert r.status == "awaiting_approval"
    assert not (work / "data" / "refunds.json").exists()  # nothing moved before approval
    tools = [t for t in r.trace if t["type"] == "tool"]
    assert tools[1]["result"]["eligible"] is True and tools[1]["result"]["max_refund"] == 129.99
    p.approvals.decide(r.approval_id, True, "fin.lead")
    r2 = p.agents.resume(r.run_id)
    assert r2.status == "completed" and r2.parsed["decision"] == "refunded"
    assert json.loads((work / "data" / "refunds.json").read_text())[0]["refund_id"] == "RF-ORD-10042"
    ok, _ = p.audit.verify()
    assert ok
    actions = [e["action"] for e in p.audit.events]
    for a in ("agent.run.started", "agent.approval.requested", "agent.approval.approved", "agent.tool.called", "agent.run.finished"):
        assert a in actions


def test_resume_while_pending_stays_paused(tmp_path):
    p, _ = make(tmp_path, load(REFUND / "demo_script.json"))
    r = p.agents.run("refund_agent", {"customer_email": EMAIL}, OPS)
    assert p.agents.resume(r.run_id).status == "awaiting_approval"


def test_denial_is_respected(tmp_path):
    script = load(REFUND / "demo_script.json")[:3] + [
        {"content": json.dumps({"decision": "escalated", "order_id": "ORD-10042", "amount": 0,
                                "reply_to_customer": "A colleague will review your request."})}]
    p, work = make(tmp_path, script, approvals=AutoDecision("denied", "fin.lead"))
    r = p.agents.run("refund_agent", {"customer_email": EMAIL}, OPS)
    assert r.status == "completed" and r.parsed["decision"] == "escalated"
    assert not (work / "data" / "refunds.json").exists()
    denied = [m for m in p.gateway.connections["default"].provider.calls[-1]["messages"] if m["role"] == "tool"][-1]
    assert "AGENT.APPROVAL_DENIED" in denied["content"]


def test_unknown_tool_and_bad_arguments_are_rejected(tmp_path):
    script = [
        {"tool_calls": [{"name": "delete_database", "arguments": {}}]},
        {"tool_calls": [{"name": "check_refund_policy", "arguments": {"order_id": "ORD-10042", "reason": "bored"}}]},
        {"content": json.dumps({"decision": "escalated", "order_id": None, "amount": 0, "reply_to_customer": "x"})}]
    p, _ = make(tmp_path, script)
    r = p.agents.run("refund_agent", {"customer_email": EMAIL}, OPS)
    assert r.status == "completed"
    guards = [t["message"] for t in r.trace if t["type"] == "guardrail"]
    assert any("unknown tool 'delete_database'" in g for g in guards)
    assert any("invalid arguments" in g for g in guards)
    assert not any(t["type"] == "tool" for t in r.trace)  # nothing executed


def test_step_limit(tmp_path):
    loop = [{"tool_calls": [{"name": "lookup_order", "arguments": {"order_id": "ORD-10042"}}]}] * 20
    p, _ = make(tmp_path, loop)
    r = p.agents.run("refund_agent", {"customer_email": EMAIL}, OPS)
    assert r.status == "limit_reached" and r.error["code"] == "AGENT.STEP_LIMIT" and r.steps == 8


def test_cost_ceiling(tmp_path):
    loop = [{"tool_calls": [{"name": "lookup_order", "arguments": {"order_id": "ORD-10042"}}]}] * 20
    p, _ = make(tmp_path, loop, max_cost=0.004)
    r = p.agents.run("refund_agent", {"customer_email": EMAIL}, OPS)
    assert r.status == "limit_reached" and r.error["code"] == "AGENT.COST_LIMIT"


def test_final_answer_schema_repair(tmp_path):
    script = [{"content": "I refunded it!"},
              {"content": json.dumps({"decision": "declined", "order_id": "ORD-1", "amount": 0, "reply_to_customer": "No."})}]
    p, _ = make(tmp_path, script)
    r = p.agents.run("refund_agent", {"customer_email": EMAIL}, OPS)
    assert r.status == "completed" and r.steps == 2
    assert any(t["type"] == "guardrail" and "schema" in t["message"] for t in r.trace)


def test_scope_limits_which_bots_an_agent_can_run(tmp_path):
    p, work = make(tmp_path, load(REFUND / "demo_script.json"), approvals=AutoDecision())
    scoped = Principal("intern", roles=("Automation Runner", "AI Admin"),
                       scopes={"automation": {"lookup_order", "check_refund_policy"}})
    p.gateway.connections["default"].provider.script.append({"content": "{}"})
    r = p.agents.run("refund_agent", {"customer_email": EMAIL}, scoped)
    assert not (work / "data" / "refunds.json").exists()
    assert any(t["type"] == "guardrail" and "policy denied issue_refund" in t["message"] for t in r.trace)
    assert any(e["action"] == "policy.deny" for e in p.audit.events)


def test_model_access_control(tmp_path):
    p, _ = make(tmp_path, load(REFUND / "demo_script.json"), connection_kw={"allowed_roles": {"Finance AI"}})
    r = p.agents.run("refund_agent", {"customer_email": EMAIL}, OPS)
    assert r.status == "failed" and r.error["code"] == "AI.MODEL_DENIED"


def test_agent_requires_permission(tmp_path):
    p, _ = make(tmp_path, load(REFUND / "demo_script.json"))
    from ooa.sdk.errors import PolicyDenied
    with pytest.raises(PolicyDenied):
        p.agents.run("refund_agent", {"customer_email": EMAIL}, Principal("bob", roles=("Assist User",)))


def test_genai_package_inside_a_bot(tmp_path):
    def reply(messages, tools):
        return {"content": json.dumps({"label": "refund_request", "reason": "asks for money back"})}
    gw = AIGateway()
    gw.add_connection(ModelConnection("default", ScriptedProvider(reply), "scripted"))
    p = Platform(gateway=gw, work_dir=tmp_path)
    f = {"kind": "ApiTask", "id": "triage", "name": "triage", "variables": [
            {"name": "label", "type": "String", "direction": "output"}],
         "steps": [{"id": "c", "action": "ooa.genai/classify",
                    "args": {"text": "Please refund ORD-1, call me on +44 20 7946 0958", "labels": ["refund_request", "complaint", "other"]},
                    "out": {"label": "label"}}]}
    res = p.run(f, {}, Principal("dev", roles=("Automation Developer",)))
    assert res.ok and res.outputs["label"] == "refund_request"
    rec = gw.governance.records[-1]
    assert rec["purpose"] == "genai" and rec["automationId"] == "triage" and rec["masked"] == {"PHONE": 1}
