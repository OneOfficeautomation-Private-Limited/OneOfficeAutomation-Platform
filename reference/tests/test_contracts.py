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


def test_every_example_flow_validates(registry):
    from ooa.runner import validate_flow
    for f in list((ROOT / "examples").rglob("*.flow.json")):
        assert validate_flow(load(f), registry, raise_on_error=False) == [], f


def test_llms_txt_exists():
    assert (ROOT / "docs" / "llms.txt").is_file()
