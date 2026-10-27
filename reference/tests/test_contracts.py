import json

import jsonschema

from conftest import REFUND, ROOT, load
from ooa.runner.validate import flow_schema


def test_bundled_schema_matches_contract():
    contract = load(ROOT / "contracts" / "flow-schema" / "flow.v1.json")
    bundled = load(ROOT / "reference" / "ooa" / "runner" / "flow.v1.json")
    assert contract == bundled, "run: cp contracts/flow-schema/flow.v1.json reference/ooa/runner/"


def test_schema_is_valid_draft_2020_12():
    jsonschema.Draft202012Validator.check_schema(flow_schema())


def _actions(steps):
    for s in steps or []:
        yield s.get("action", "")
        for k in ("children", "else", "finally"):
            yield from _actions(s.get(k))
        for c in s.get("catch", []) or []:
            yield from _actions(c.get("children"))
        for c in s.get("elseIf", []) or []:
            yield from _actions(c.get("children"))


def test_every_example_flow_validates(registry):
    """Every example made only of toolkits this reference implements validates (the use-case agents need the Java toolkits)."""
    from ooa.runner import validate_flow
    known = set(registry.packages)
    checked = 0
    for f in list((ROOT / "examples").rglob("*.flow.json")):
        flow = load(f)
        if any(a.split("/")[0] not in known for a in _actions(flow.get("steps"))):
            continue
        assert validate_flow(flow, registry, raise_on_error=False) == [], f
        checked += 1
    assert checked >= 1


def test_llms_txt_exists():
    assert (ROOT / "docs" / "llms.txt").is_file()
