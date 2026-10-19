"""OOA CLI (reference). The production CLI is Java (picocli, GraalVM native image);
command names and flags are the contract.

  ooa validate FLOW...                 validate Flows against schema and manifests
  ooa manifest [--package ID]          print package manifests (what Studio renders)
  ooa run FLOW [-i k=v]...             run a Flow file or published automation id
  ooa publish FLOW... [-m COMMENT]     check in Flows to the repository
  ooa list                             list published automations
  ooa agent run AGENT [-i k=v]...      run an AI agent
  ooa agent resume RUN_ID              continue a paused agent after a decision
  ooa approvals list|approve|deny      Assist inbox for sensitive tools
  ooa audit verify                     verify the hash-chained audit log
  ooa mcp serve                        MCP server on stdio
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

from .governance import Principal
from .platform import Platform
from .runner.events import ConsoleSink, JsonlSink, ListSink
from .runner.validate import validate_flow
from .sdk.errors import OoaError
from .sdk.types import to_jsonable


def _inputs(pairs: list[str], json_file: str | None) -> dict:
    out = json.loads(Path(json_file).read_text()) if json_file else {}
    for p in pairs or []:
        k, sep, v = p.partition("=")
        if not sep:
            raise SystemExit(f"--input expects key=value, got '{p}'")
        if v.startswith("@"):
            v = Path(v[1:]).read_text()
        else:
            try:
                v = json.loads(v)
            except json.JSONDecodeError:
                pass
        out[k] = v
    return out


def _platform(a, sink=None) -> Platform:
    p = Platform(Path(a.home), sink=sink or ListSink(), work_dir=Path(a.work_dir) if a.work_dir else None)
    models = a.models or os.environ.get("OOA_MODELS")
    if models:
        p.configure_models_from_file(Path(models))
    return p


def _principal(a) -> Principal:
    return Principal(a.user, roles=tuple(r.strip() for r in a.roles.split(",") if r.strip()), team=a.team)


def _load_flow(p: Platform, ref: str) -> dict:
    path = Path(ref)
    return json.loads(path.read_text()) if path.suffix == ".json" and path.exists() else p.repository.get(ref)


def _print(obj) -> None:
    print(json.dumps(to_jsonable(obj), indent=2))


def cmd_validate(a):
    p = _platform(a)
    bad = 0
    for f in a.flows:
        problems = validate_flow(json.loads(Path(f).read_text()), p.registry, raise_on_error=False)
        status = "OK" if not problems else f"{len(problems)} problem(s)"
        print(f"{f}: {status}")
        for pr in problems:
            bad += 1
            loc = pr.get("stepId") or pr.get("path") or "/"
            fix = f"  -> {pr['suggestedFix']}" if pr.get("suggestedFix") else ""
            print(f"  [{loc}] {pr['message']}{fix}")
    return 1 if bad else 0


def cmd_manifest(a):
    p = _platform(a)
    ms = p.registry.all_manifests()
    if a.package:
        ms = [m for m in ms if m["id"] == a.package]
    _print(ms if not a.summary else [{"id": m["id"], "version": m["version"], "actions": [x["id"] for x in m["actions"]]} for m in ms])
    return 0


def cmd_run(a):
    sink = JsonlSink() if a.events == "jsonl" else ConsoleSink(verbose=a.verbose) if a.events == "console" else ListSink()
    p = _platform(a, sink)
    flow = _load_flow(p, a.flow)
    res = p.run(flow, _inputs(a.input, a.inputs_json), _principal(a), max_run_seconds=a.timeout)
    _print(res.to_dict())
    return 0 if res.ok else 2


def cmd_publish(a):
    p = _platform(a)
    for f in a.flows:
        rec = p.repository.check_in(json.loads(Path(f).read_text()), author=a.user, comment=a.comment or "",
                                    registry=p.registry)
        print(f"checked in {rec['automationId']} v{rec['version']} ({rec['checksum'][:12]})")
    return 0


def cmd_list(a):
    _print(_platform(a).repository.list())
    return 0


def cmd_agent(a):
    sink = ConsoleSink(verbose=False) if a.events == "console" else ListSink()
    p = _platform(a, sink)
    if a.agent_cmd == "run":
        res = p.agents.run(a.agent, _inputs(a.input, a.inputs_json), _principal(a))
    else:
        res = p.agents.resume(a.run_id)
    out = res.to_dict()
    if not a.trace:
        out.pop("trace")
    _print(out)
    if res.status == "awaiting_approval":
        print(f"\nPaused for approval {res.approval_id}. A different reviewer runs:\n"
              f"  ooa approvals approve {res.approval_id} --user <reviewer>\n  ooa agent resume {res.run_id}",
              file=sys.stderr)
    return 0 if res.status in ("completed", "awaiting_approval") else 2


def cmd_approvals(a):
    p = _platform(a)
    if a.ap_cmd == "list":
        _print([{"id": r.id, "tool": r.tool, "automation": r.automation_id, "arguments": r.arguments,
                 "requestedFor": r.requested_for, "agentRun": r.agent_run_id, "reason": r.reason}
                for r in p.approvals.pending()])
        return 0
    req = p.approvals.decide(a.id, a.ap_cmd == "approve", a.user, a.comment)
    p.audit.record(a.user, f"approval.{req.status}", f"approval:{req.id}", details={"comment": a.comment})
    print(f"{req.id} {req.status} by {a.user}")
    return 0


def cmd_audit(a):
    p = _platform(a)
    ok, bad = p.audit.verify()
    print(f"audit chain: {'intact' if ok else f'BROKEN at event {bad}'} ({len(p.audit.events)} events)")
    return 0 if ok else 3


def cmd_mcp(a):
    from .agents.mcp_server import MCPServer
    p = _platform(a)
    MCPServer(p, _principal(a)).serve_stdio()
    return 0


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="ooa", description="ONEOFFICEAUTOMATION CLI (reference)")
    ap.add_argument("--home", default=os.environ.get("OOA_HOME", ".ooa"), help="Platform state directory")
    ap.add_argument("--work-dir", help="Working directory for bots (relative file paths)")
    ap.add_argument("--models", help="Model connections JSON (or OOA_MODELS)")
    ap.add_argument("--user", default=os.environ.get("OOA_USER", "dev"))
    ap.add_argument("--roles", default=os.environ.get("OOA_ROLES", "Automation Developer,AI Admin"))
    ap.add_argument("--team", default="default")
    sub = ap.add_subparsers(dest="cmd", required=True)

    s = sub.add_parser("validate"); s.add_argument("flows", nargs="+"); s.set_defaults(fn=cmd_validate)
    s = sub.add_parser("manifest"); s.add_argument("--package"); s.add_argument("--summary", action="store_true")
    s.set_defaults(fn=cmd_manifest)

    def run_args(s):
        s.add_argument("-i", "--input", action="append", default=[], help="key=value (JSON values, @file for text)")
        s.add_argument("--inputs-json")
        s.add_argument("--events", choices=["console", "jsonl", "none"], default="console")
    s = sub.add_parser("run"); s.add_argument("flow"); run_args(s)
    s.add_argument("--timeout", type=float); s.add_argument("-v", "--verbose", action="store_true"); s.set_defaults(fn=cmd_run)
    s = sub.add_parser("publish"); s.add_argument("flows", nargs="+"); s.add_argument("-m", "--comment")
    s.set_defaults(fn=cmd_publish)
    s = sub.add_parser("list"); s.set_defaults(fn=cmd_list)

    s = sub.add_parser("agent"); asub = s.add_subparsers(dest="agent_cmd", required=True)
    r = asub.add_parser("run"); r.add_argument("agent"); run_args(r); r.add_argument("--trace", action="store_true")
    r.set_defaults(fn=cmd_agent)
    r = asub.add_parser("resume"); r.add_argument("run_id"); r.add_argument("--trace", action="store_true")
    r.add_argument("--events", choices=["console", "none"], default="console"); r.set_defaults(fn=cmd_agent)

    s = sub.add_parser("approvals"); psub = s.add_subparsers(dest="ap_cmd", required=True)
    psub.add_parser("list").set_defaults(fn=cmd_approvals)
    for name in ("approve", "deny"):
        r = psub.add_parser(name); r.add_argument("id"); r.add_argument("--comment"); r.set_defaults(fn=cmd_approvals)

    s = sub.add_parser("audit"); s.add_argument("action", choices=["verify"]); s.set_defaults(fn=cmd_audit)
    s = sub.add_parser("mcp"); s.add_argument("action", choices=["serve"]); s.set_defaults(fn=cmd_mcp)

    a = ap.parse_args(argv)
    try:
        return a.fn(a)
    except OoaError as e:
        print(f"error: {e}", file=sys.stderr)
        problems = e.details.get("problems") if e.details else None
        for pr in problems or []:
            print(f"  [{pr.get('stepId') or pr.get('path')}] {pr['message']}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
