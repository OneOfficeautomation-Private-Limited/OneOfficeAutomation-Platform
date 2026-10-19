import io
import json
import shutil
from decimal import Decimal

from conftest import REFUND, load
from ooa.agents import InboxApproval, MCPServer
from ooa.governance import Principal
from ooa.platform import Platform
from ooa.runner import Runner


def test_mcp_lists_and_calls_published_bots(tmp_path):
    shutil.copytree(REFUND / "data", tmp_path / "data")
    p = Platform(work_dir=tmp_path, approvals=InboxApproval())
    for f in sorted((REFUND / "bots").glob("*.json")):
        p.repository.check_in(load(f), registry=p.registry)
    srv = MCPServer(p, Principal("claude-desktop-user", roles=("Automation Runner",)))
    lines = "\n".join(json.dumps(m) for m in [
        {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {}},
        {"jsonrpc": "2.0", "method": "notifications/initialized"},
        {"jsonrpc": "2.0", "id": 2, "method": "tools/list"},
        {"jsonrpc": "2.0", "id": 3, "method": "tools/call", "params": {"name": "lookup_order", "arguments": {"order_id": "ORD-10101"}}},
        {"jsonrpc": "2.0", "id": 4, "method": "tools/call", "params": {"name": "issue_refund", "arguments": {"order_id": "ORD-10101", "amount": 10}}},
        {"jsonrpc": "2.0", "id": 5, "method": "tools/call", "params": {"name": "lookup_order", "arguments": {"bad": 1}}},
    ])
    out = io.StringIO()
    srv.serve_stdio(io.StringIO(lines), out)
    resp = {r["id"]: r for r in map(json.loads, out.getvalue().splitlines())}
    assert resp[1]["result"]["serverInfo"]["name"] == "ONEOFFICEAUTOMATION"
    tools = {t["name"]: t for t in resp[2]["result"]["tools"]}
    assert set(tools) == {"lookup_order", "check_refund_policy", "issue_refund"}
    assert tools["issue_refund"]["annotations"]["x-ooa-requires-approval"] is True
    assert resp[3]["result"]["structuredContent"]["outputs"]["order"]["item"] == "Monitor 27in"
    assert resp[4]["result"]["structuredContent"]["status"] == "pending"  # sensitive: approval, not execution
    assert not (tmp_path / "data" / "refunds.json").exists()
    assert resp[5]["result"]["isError"] is True


def test_excel_bot(tmp_path, registry):
    import openpyxl
    wb = openpyxl.Workbook()
    ws = wb.active
    ws.append(["Invoice", "Vendor", "Amount"])
    for row in [["INV-1", "Acme", 1200.5], ["INV-2", "Globex", 99], ["INV-3", "Acme", 5000]]:
        ws.append(row)
    wb.save(tmp_path / "invoices.xlsx")
    flow = load(REFUND.parent / "excel" / "invoice_summary.flow.json")
    res = Runner(registry, work_dir=tmp_path).run(flow, {"workbook": "invoices.xlsx", "threshold": 1000})
    assert res.ok, res.error
    assert res.outputs["total"] == Decimal("6299.5") and res.outputs["flagged"] == 2
    out = openpyxl.load_workbook(tmp_path / "invoices.xlsx")
    assert out["Review"]["A2"].value == "INV-3"  # sorted by amount, descending
