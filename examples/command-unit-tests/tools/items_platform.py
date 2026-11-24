"""Writes the sample items of OneAgentStudio's Create list (every type but Agent) to ../agents/items/<type>/:

    python -I items_platform.py

Each sample uses the features of its type; the "UT Item <type>" agents (cases/item_*.py) use them. In One
CommandCenter the items are checked in under Commands unit testing folder/items/<type>/ (CC_ITEMS below), which is
where the workflow finds its agent and form.
"""
from __future__ import annotations

import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
ITEMS = os.path.join(HERE, "..", "agents", "items")
CC_ITEMS = "Commands unit testing folder/items"
FLOW = "https://schemas.oneofficeautomation.com/flow/v1.json"

API_AGENT_ID = "ut_api_agent_item"
AI_AGENT_ID = "ut_ai_agent_item"
SKILL = "UT_classify_ticket"

# where each sample goes: (type folder, file name)
FILES = {
    "ai_agent": ("ai-agent", "UT AI agent.flow.json"),
    "api_agent": ("api-agent", "UT API agent.flow.json"),
    "workflow": ("workflow", "UT Workflow.process.json"),
    "form": ("form", "UT Form.form.json"),
    "document_type": ("document-type", "UT Invoice.doc.json"),
    "object_repository": ("object-repository", "UT Shop.objects.json"),
    "prompt_skill": ("prompt-skill", SKILL + ".json"),
    "python": ("python-script", "UT invoice tools.py"),
    "powershell": ("powershell-script", "UT invoice tools.ps1"),
}


def rel(key):
    """An item's path below the items folder, e.g. form/UT Form.form.json."""
    d, n = FILES[key]
    return d + "/" + n


def api_agent():
    return {
        "$schema": FLOW, "kind": "ApiTask", "id": API_AGENT_ID, "name": "UT API agent",
        "description": "Works out an invoice line total: quantity times unit price, in the currency given (in capitals). "
                       "Use it whenever a total for a quantity and a unit price is needed.",
        "tags": ["unit-test", "item-sample"],
        "variables": [
            {"name": "quantity", "type": "Number", "direction": "input", "required": True, "description": "How many units."},
            {"name": "unit_price", "type": "Number", "direction": "input", "required": True, "description": "Price of one unit."},
            {"name": "currency", "type": "String", "direction": "in_out", "default": "eur", "description": "Currency code; returned in capitals."},
            {"name": "total", "type": "Number", "direction": "output", "default": 0, "description": "quantity x unit_price, rounded to cents."},
            {"name": "summary", "type": "String", "direction": "output", "default": "", "description": "The total in words for people."},
        ],
        "steps": [
            {"id": "s1", "action": "core/comment", "args": {"text": "An API agent runs without a desktop: only steps that need no screen (here core, String and Number)."}},
            {"id": "s2", "action": "core/if", "description": "Refuse a quantity that is not positive",
             "args": {"condition": {"op": "le", "left": "$quantity$", "right": 0}},
             "children": [{"id": "s3", "action": "core/throw", "args": {"code": "BUSINESS.BAD_QUANTITY", "message": "Quantity must be more than 0, got ${quantity}"}}]},
            {"id": "s4", "action": "ooa.string/change_case", "description": "Currency in capitals",
             "args": {"text": "$currency$", "case": "upper"}, "out": {"result": "currency"}},
            {"id": "s5", "action": "core/set", "description": "Total, rounded to cents",
             "args": {"variable": "total", "value": {"expr": "round($quantity * $unit_price, 2)"}}},
            {"id": "s6", "action": "core/set", "description": "The total in words",
             "args": {"variable": "summary", "value": "${quantity} x ${unit_price} = ${total} ${currency}"}},
            {"id": "s7", "action": "core/log", "args": {"message": "UT API agent: ${summary}", "level": "info"}},
        ],
    }


def ai_agent():
    return {
        "$schema": FLOW, "kind": "TaskBot", "id": AI_AGENT_ID, "name": "UT AI agent",
        "description": "AI agent sample: given a request about an invoice line, it uses the agent UT API agent as its "
                       "tool and returns the total and currency as output fields.",
        "tags": ["ai-agent", "unit-test", "item-sample"],
        "variables": [
            {"name": "request", "type": "String", "direction": "input", "required": True, "description": "What the agent is asked to do this time."},
            {"name": "standin_url", "type": "String", "direction": "input", "default": "http://127.0.0.1:8791", "description": "The local stand-in that plays the model."},
            {"name": "api_key", "type": "Credential", "direction": "input", "default": {"$credential": "unit_tests/ai/api_key"}, "description": "Made-up key for the stand-in model."},
            {"name": "answer", "type": "String", "direction": "output", "description": "The agent's final answer."},
            {"name": "status", "type": "String", "direction": "output", "description": "completed, limit_reached, stopped, failed or cancelled."},
            {"name": "outputs", "type": "Dictionary", "direction": "output", "description": "The output fields total and currency."},
            {"name": "trace", "type": "List", "direction": "output", "description": "Each model turn and tool call."},
            {"name": "tokens", "type": "Number", "direction": "output", "description": "Tokens used."},
        ],
        "steps": [{
            "id": "s1", "action": "ooa.ai_agent/run",
            "description": "Plan with the model and call UT API agent as a tool",
            "args": {
                "goal": "$request$",
                "instructions": "You are a careful accounts assistant. Use the tools to work out figures; never invent results.",
                "role": "Accounts payable clerk",
                "action_plan": "1. Call the total tool with the quantity and unit price. 2. Report the total and the currency.",
                "tools": [API_AGENT_ID],
                "provider": "openai_compatible", "model": "ut-model", "base_url": "${standin_url}/plat-ai/v1",
                "api_key": "$api_key$",
                "output_fields": {"total": "the invoice line total as a number", "currency": "the currency code"},
                "max_steps": 4, "max_tokens_total": 20000, "max_duration_seconds": 120, "temperature": 0,
                "mask_pii": False, "toxicity_guardrail": "log", "toxicity_level": "medium", "blocked_terms": ["password"],
            },
            "out": {"answer": "answer", "status": "status", "outputs": "outputs", "trace": "trace", "tokens": "tokens"},
        }],
    }


def form():
    return {
        "$schema": "https://schemas.oneofficeautomation.com/form/v1.json",
        "id": "UT_Form", "name": "UT Form", "description": "Form sample with every kind of field: an expense claim.",
        "fields": [
            {"id": "title", "type": "heading", "label": "Expense claim"},
            {"id": "intro", "type": "paragraph", "label": "About", "text": "Fill in the claim; fields with * are required."},
            {"id": "full_name", "type": "text", "label": "Full name", "required": True, "placeholder": "First and last name",
             "help": "As on your ID card", "tooltip": "Your full name"},
            {"id": "email", "type": "email", "label": "Email address", "required": True},
            {"id": "amount", "type": "number", "label": "Amount", "required": True, "min": 1, "max": 5000, "default": 100},
            {"id": "spent_on", "type": "date", "label": "Date spent"},
            {"id": "category", "type": "dropdown", "label": "Category", "required": True,
             "options": ["Travel", "Meals", "Office"], "default": "Travel"},
            {"id": "payment", "type": "radio", "label": "Paid with", "options": ["Company card", "Own money"], "default": "Own money"},
            {"id": "details", "type": "multiline", "label": "Details", "placeholder": "What was it for?"},
            {"id": "receipt", "type": "file", "label": "Receipt"},
            {"id": "pin", "type": "password", "label": "Approval PIN"},
            {"id": "reference", "type": "text", "label": "Reference", "read_only": True, "default": "EXP-UT-1"},
            {"id": "lines", "type": "area", "label": "Lines", "fields": [
                {"id": "line_1", "type": "text", "label": "Line 1"}]},
            {"id": "agree", "type": "checkbox", "label": "I confirm the claim is correct", "required": True},
            {"id": "check", "type": "button", "label": "Check answers"},
        ],
        "submitLabel": "Send claim",
    }


def workflow():
    return {
        "$schema": "https://schemas.oneofficeautomation.com/process/v1.json",
        "id": "UT_Workflow", "name": "UT Workflow",
        "description": "Workflow sample: an agent works out a total; large totals need an approval; then a person fills "
                       "in the claim form and everyone concerned is told.",
        "start_form": CC_ITEMS + "/" + rel("form"),
        "stages": [
            {"id": "work_out", "type": "bot", "name": "Work out the total",
             "bot": CC_ITEMS + "/" + rel("api_agent"),
             "inputs": {"quantity": "$quantity$", "unit_price": "$unit_price$", "currency": "eur"},
             "description": "Runs UT API agent with the start data."},
            {"id": "big", "type": "decision", "name": "Over 100?", "condition": "$total$ > 100",
             "if_true": "approve", "if_false": "claim"},
            {"id": "approve", "type": "approval", "name": "Approve a large total", "assignee": "role:admin",
             "due_hours": 24, "on_reject": "end"},
            {"id": "claim", "type": "form", "name": "Fill in the claim", "form": CC_ITEMS + "/" + rel("form"),
             "assignee": "role:admin", "due_hours": 48},
            {"id": "tell", "type": "notify", "name": "Tell the team", "to": "role:admin",
             "subject": "UT Workflow finished", "message": "Total ${total} ${currency} for ${full_name}.", "next": "end"},
        ],
    }


def document_type():
    return {
        "$schema": "https://schemas.oneofficeautomation.com/document/v1.json",
        "id": "UT_Invoice", "name": "UT Invoice", "description": "Document type sample: the fields of a supplier invoice.",
        "accept": ["pdf", "txt"], "provider": "openai_compatible", "model": "ut-model",
        "fields": [
            {"name": "supplier", "description": "The supplier's name", "type": "String", "required": True},
            {"name": "invoice_number", "description": "The invoice number", "type": "String", "required": True},
            {"name": "invoice_date", "description": "The invoice date", "type": "DateTime", "required": True},
            {"name": "po_number", "description": "The purchase order number", "type": "String", "required": False},
            {"name": "currency", "description": "The currency code", "type": "String", "required": True},
            {"name": "total", "description": "The amount to pay", "type": "Number", "required": True},
        ],
    }


def _web(props, search_by="properties"):
    return {"technology": "web",
            "page": {"url": "/plat-app/login", "title": "UT Shop", "url_match": "contains", "title_match": "contains"},
            "frames": [], "search_by": search_by, "index": 0, "ignore_case": False,
            "properties": [{"name": k, "value": v, "match": "equals", "use": True} for k, v in props]}


def object_repository():
    return {
        "kind": "ObjectRepository", "name": "UT Shop",
        "description": "Object repository sample: the sign-in screen of the stand-in's UT Shop page.",
        "applications": [{
            "name": "UT Shop web", "description": "The stand-in page /plat-app/login.",
            "screens": [{"name": "Login", "elements": [
                {"name": "User name", "description": "Found by tag and id.", "object": _web([("tag", "input"), ("id", "username")])},
                {"name": "Password", "description": "Found by tag and name.", "object": _web([("tag", "input"), ("name", "pass")])},
                {"name": "Country", "description": "A drop-down, found by id.", "object": _web([("tag", "select"), ("id", "country")])},
                {"name": "Sign in", "description": "Found by a CSS selector.", "object": _web([("css", "button#signin")], "css")},
                {"name": "Message", "description": "Found by an XPath.", "object": _web([("xpath", "//p[@data-test='message']")], "xpath")},
                {"name": "Heading", "description": "Found by its text (contains).",
                 "object": dict(_web([("tag", "h1")]), properties=[{"name": "tag", "value": "h1", "match": "equals", "use": True},
                                                                  {"name": "text", "value": "UT", "match": "contains", "use": True}])},
            ]}],
        }],
    }


def prompt_skill():
    return {
        "description": "Sorts a customer ticket into a category and a priority; answers as JSON.",
        "system": "You sort customer tickets. CLASSIFY-TICKET. Reply only with a JSON object with the keys category "
                  "(billing, delivery or other) and priority (high or normal).",
        "prompt": "Customer: $customer$\nTicket: ${ticket}",
        "provider": "openai_compatible", "model": "ut-model", "output": "json",
        "temperature": 0, "max_tokens": 200, "mask_pii": False,
    }


PYTHON = '''"""UT invoice tools: sample Python script for agents.

As a script (Run Python script): reads a JSON object with "lines" from standard input, takes the currency as the first
argument and prints one JSON object with the total. As functions (Run Python function): line_total, invoice_total,
normalise_reference and the class Vat.
"""
import json
import re
import sys


def line_total(quantity, unit_price):
    """One line: quantity x unit price, rounded to cents."""
    return round(float(quantity) * float(unit_price), 2)


def invoice_total(lines, discount_percent=0):
    """Every line added up, less a discount in percent."""
    total = sum(line_total(l["qty"], l["price"]) for l in lines)
    return {"lines": len(lines), "total": round(total * (100 - discount_percent) / 100, 2)}


def normalise_reference(text):
    """An invoice reference in one spelling: INV-<digits>, e.g. 'inv 42' -> 'INV-42'."""
    m = re.search(r"(\\d+)", text or "")
    if not m:
        raise ValueError("no number in reference: " + str(text))
    return "INV-" + m.group(1)


class Vat:
    rate = 0.2

    @staticmethod
    def add(net):
        return round(net * (1 + Vat.rate), 2)


if __name__ == "__main__":
    data = json.loads(sys.stdin.read() or "{}")
    currency = sys.argv[1] if len(sys.argv) > 1 else "EUR"
    result = invoice_total(data.get("lines", []))
    result["currency"] = currency.upper()
    print(json.dumps(result))
'''

POWERSHELL = '''<#
UT invoice tools: sample PowerShell script for agents (Run PowerShell script).
Parameters by position: Currency, then VatRate. Standard input: a JSON object with "lines" (qty, price).
Prints one JSON object: lines, net, vat, gross, currency, computer.
#>
param([string]$Currency = "EUR", [double]$VatRate = 0.2)
$ErrorActionPreference = "Stop"
$data = [Console]::In.ReadToEnd() | ConvertFrom-Json
$net = 0.0
foreach ($line in $data.lines) { $net += [double]$line.qty * [double]$line.price }
$net = [math]::Round($net, 2)
$vat = [math]::Round($net * $VatRate, 2)
[pscustomobject]@{
    lines    = @($data.lines).Count
    net      = $net
    vat      = $vat
    gross    = [math]::Round($net + $vat, 2)
    currency = $Currency.ToUpper()
    computer = $env:COMPUTERNAME -ne $null
} | ConvertTo-Json -Compress
exit 0
'''


def write_all():
    makers = {"api_agent": api_agent, "ai_agent": ai_agent, "form": form, "workflow": workflow,
              "document_type": document_type, "object_repository": object_repository, "prompt_skill": prompt_skill}
    for key, (d, name) in FILES.items():
        os.makedirs(os.path.join(ITEMS, d), exist_ok=True)
        path = os.path.join(ITEMS, d, name)
        if key in makers:
            text = json.dumps(makers[key](), indent=2, ensure_ascii=False) + "\n"
        else:
            text = PYTHON if key == "python" else POWERSHELL
        with open(path, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(text)
        print("wrote", os.path.relpath(path, os.path.join(HERE, "..")))


if __name__ == "__main__":
    write_all()
