import json

import pytest

from ooa.governance import AuditLog, Policy, Principal, Repository
from ooa.sdk.errors import FlowValidationError, OoaError, PolicyDenied


def test_audit_chain_detects_tampering(tmp_path):
    log = AuditLog(tmp_path / "audit.jsonl")
    for i in range(5):
        log.record("u", "x.did", f"obj:{i}", after={"i": i})
    assert log.verify() == (True, None)
    reloaded = AuditLog(tmp_path / "audit.jsonl")
    assert reloaded.verify() == (True, None)
    reloaded.events[2]["after"] = {"i": 999}
    assert reloaded.verify() == (False, 3)
    log.events.pop(1)
    assert log.verify()[0] is False


def test_policy_permissions_and_scopes():
    audit = AuditLog()
    pol = Policy(audit)
    dev = Principal("d", roles=("Automation Developer",), scopes={"automation": {"finance/*"}})
    pol.check(dev, "automation.run", "automation", "finance/invoices")
    with pytest.raises(PolicyDenied):
        pol.check(dev, "automation.run", "automation", "hr/payroll")
    with pytest.raises(PolicyDenied):
        pol.check(dev, "user.manage")
    assert sum(e["action"] == "policy.deny" for e in audit.events) == 2


def test_repository_versions_and_production(tmp_path, registry):
    repo = Repository(tmp_path / "repo", AuditLog())
    f = {"kind": "ApiTask", "id": "a", "name": "A", "steps": [{"id": "c", "action": "core/comment"}]}
    repo.check_in(f, registry=registry, comment="v1")
    repo.check_in({**f, "name": "A2"}, registry=registry, comment="v2", set_production=False)
    assert repo.get("a")["name"] == "A" and repo.get("a", 2)["name"] == "A2"
    repo.set_production("a", 2)
    assert Repository(tmp_path / "repo").get("a")["name"] == "A2"  # persisted
    with pytest.raises(FlowValidationError):
        repo.check_in({**f, "steps": [{"id": "c", "action": "core/nope"}]}, registry=registry)
    with pytest.raises(OoaError):
        repo.get("missing")
