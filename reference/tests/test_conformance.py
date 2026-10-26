"""Runs the language-neutral conformance suite (contracts/conformance)."""
import json
from decimal import Decimal

import pytest

from conftest import ROOT, load
from ooa.runner import Runner

CASES = sorted((ROOT / "contracts" / "conformance" / "cases").glob("*.json"))


def _norm(v):
    if isinstance(v, (int, float, Decimal)) and not isinstance(v, bool):
        return Decimal(str(v)).normalize()
    if isinstance(v, dict):
        return {k: _norm(x) for k, x in v.items()}
    if isinstance(v, list):
        return [_norm(x) for x in v]
    return v


@pytest.mark.parametrize("path", CASES, ids=[p.stem for p in CASES])
def test_case(path, registry, vault, tmp_path):
    case = load(path)
    services = {"vault": vault}
    if "globals" in case:
        services["global_values"] = case["globals"]   # shared values ($global) the case gives its run
    res = Runner(registry, services=services, work_dir=tmp_path).run(case["flow"], case["inputs"])
    exp = case["expect"]
    assert res.status == exp["status"], res.error
    if "outputs" in exp:
        got = json.loads(json.dumps(res.to_dict()["outputs"]))
        assert _norm(got) == _norm(exp["outputs"])
    if "errorCode" in exp:
        assert res.error["code"] == exp["errorCode"]
    if "errorStepId" in exp:
        assert res.error["stepId"] == exp["errorStepId"]


def test_suite_is_not_empty():
    assert len(CASES) >= 18
