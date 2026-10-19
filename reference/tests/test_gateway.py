import json

import pytest

from ooa.ai import AIGateway, BudgetManager, Caller, ModelConnection, PiiGuard, ScriptedProvider
from ooa.sdk.errors import BudgetExceeded, OoaError


def test_pii_masking_is_deterministic_and_reversible():
    g = PiiGuard()
    text = "Mail jane@acme.com, card 4111 1111 1111 1111, call +44 20 7946 0958, order ORD-10042 on 2026-10-18 for 129.99"
    masked, mapping, counts = g.mask(text)
    assert "jane@acme.com" not in masked and "4111" not in masked and "7946" not in masked
    assert "ORD-10042" in masked and "2026-10-18" in masked and "129.99" in masked
    assert counts == {"EMAIL": 1, "CREDIT_CARD": 1, "PHONE": 1}
    assert g.mask(text)[0] == masked
    assert PiiGuard.unmask(masked, mapping) == text


def test_invalid_card_numbers_are_not_masked():
    assert "1234 5678 9012 3456" in PiiGuard().mask("ref 1234 5678 9012 3456")[0]


def test_model_never_sees_raw_pii_but_tool_args_are_unmasked():
    seen = {}

    def model(messages, tools):
        seen["user"] = messages[-1]["content"]
        token = seen["user"].split("email ")[1].split(" ")[0]
        return {"tool_calls": [{"name": "notify", "arguments": {"to": token}}]}
    gw = AIGateway()
    gw.add_connection(ModelConnection("m", ScriptedProvider(model), "x"))
    r = gw.chat([{"role": "user", "content": "Send to email bob@corp.com now"}], caller=Caller(),
                tools=[{"name": "notify", "description": "d", "input_schema": {"type": "object"}}])
    assert "bob@corp.com" not in seen["user"]
    assert r.tool_calls[0].arguments == {"to": "bob@corp.com"}


def test_budget_alerts_and_enforcement():
    events = []
    gw = AIGateway(budgets=BudgetManager(notify=lambda e, d: events.append(d["percent"])))
    gw.add_connection(ModelConnection("m", ScriptedProvider(lambda m, t: {"content": "x" * 400}), "x", 0, 10000.0))
    gw.budgets.set_limit("fin", 0.012)
    c = Caller(team="fin")
    gw.chat([{"role": "user", "content": "hi"}], caller=c)
    with pytest.raises(BudgetExceeded):
        for _ in range(5):
            gw.chat([{"role": "user", "content": "hi"}], caller=c)
    assert events == [80, 100]


def test_schema_constrained_output():
    gw = AIGateway()
    gw.add_connection(ModelConnection("m", ScriptedProvider(lambda m, t: {"content": "```json\n{\"total\": 12.5}\n```"}), "x"))
    r = gw.chat([{"role": "user", "content": "x"}], caller=Caller(),
                response_schema={"type": "object", "required": ["total"], "properties": {"total": {"type": "number"}}})
    assert r.parsed == {"total": 12.5}
    gw2 = AIGateway()
    gw2.add_connection(ModelConnection("m", ScriptedProvider(lambda m, t: {"content": "{\"total\": \"lots\"}"}), "x"))
    with pytest.raises(OoaError) as e:
        gw2.chat([{"role": "user", "content": "x"}], caller=Caller(), response_schema={"type": "object", "properties": {"total": {"type": "number"}}})
    assert e.value.code == "AI.SCHEMA_MISMATCH"
    assert gw2.governance.records[-1]["status"] == "error"


def test_local_only_mode_blocks_external_providers():
    from ooa.ai import AnthropicProvider
    gw = AIGateway(local_only=True)
    gw.add_connection(ModelConnection("cloud", AnthropicProvider(), "claude"))
    with pytest.raises(OoaError) as e:
        gw.chat([{"role": "user", "content": "x"}], caller=Caller())
    assert e.value.code == "AI.LOCAL_ONLY"


def test_governance_record_has_no_raw_prompt():
    gw = AIGateway()
    gw.add_connection(ModelConnection("m", ScriptedProvider(lambda m, t: {"content": "ok"}), "x"))
    gw.chat([{"role": "user", "content": "secret plan for jane@acme.com"}], caller=Caller(user="u1", purpose="genai"))
    rec = gw.governance.records[-1]
    assert "secret plan" not in json.dumps(rec) and rec["user"] == "u1" and len(rec["promptHash"]) == 64


@pytest.mark.parametrize("text,masked_entity,kept", [
    ("ref 1234 5678 9012 3456", None, "1234 5678 9012 3456"),        # non-Luhn: untouched, never fragmented
    ("ORD-10042 2026-10-18 129.99", None, "ORD-10042 2026-10-18 129.99"),  # business data must survive intact
    ("card 4111 1111 1111 1111 ok", "CREDIT_CARD", "ok"),
    ("Amex 3782-822463-10005", "CREDIT_CARD", "Amex"),
    ("call +44 20 7946 0958.", "PHONE", "call"),
    ("or +1 415-555-0132 today", "PHONE", "today"),
])
def test_pii_regressions(text, masked_entity, kept):
    masked, _, counts = PiiGuard().mask(text)
    assert kept in masked
    assert (masked_entity in counts) if masked_entity else counts == {}
    if masked_entity:
        assert not any(ch.isdigit() for ch in masked.replace(masked_entity, "").split("<")[0][-3:])
