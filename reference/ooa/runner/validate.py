"""Flow validation (FR-STU-007, architecture s6 'Plan'). Returns every problem
at once, each linked to a step id with a suggested fix, so Studio can list them
and the co-pilot can repair them. Only schema-valid Flows are ever executed."""
from __future__ import annotations

import json
import os
from functools import lru_cache
from pathlib import Path

import jsonschema

from ..sdk.errors import FlowValidationError, OoaError
from ..sdk.registry import PackageRegistry
from .core_package import BUILTIN_ITERATORS, CONTAINERS
from .expressions import references, resolve

_SCHEMA_CANDIDATES = [
    os.environ.get("OOA_FLOW_SCHEMA", ""),
    str(Path(__file__).resolve().parents[3] / "contracts" / "flow-schema" / "flow.v1.json"),
    str(Path(__file__).resolve().parent / "flow.v1.json"),
]


@lru_cache(maxsize=1)
def flow_schema() -> dict:
    for c in _SCHEMA_CANDIDATES:
        if c and Path(c).is_file():
            return json.loads(Path(c).read_text())
    raise OoaError("OOA Flow schema not found", code="FLOW.SCHEMA_MISSING",
                   suggested_fix="Set OOA_FLOW_SCHEMA to contracts/flow-schema/flow.v1.json.")


def _strip_ooa(schema: dict) -> dict:
    return {k: v for k, v in schema.items() if not k.startswith("x-ooa")}


def _has_refs(v) -> bool:
    try:
        return bool(references(v))
    except OoaError:
        return True


def _norm_condition(c):
    if isinstance(c, str):
        return {"expr": c}
    if isinstance(c, dict):
        if "all" in c or "any" in c:
            k = "all" if "all" in c else "any"
            return {k: [_norm_condition(x) for x in c[k] or []]}
        if "not" in c:
            return {"not": _norm_condition(c["not"])}
    return c


def _cond(args: dict) -> dict:
    """Condition strings are expressions, so scan them as {"expr": ...}."""
    if "condition" in args:
        return {**args, "condition": _norm_condition(args["condition"])}
    return args


def walk_steps(steps, parent=None):
    for s in steps or []:
        yield s, parent
        for key in ("children", "else", "finally"):
            yield from walk_steps(s.get(key), s)
        for b in s.get("elseIf", []) or []:
            yield from walk_steps(b.get("children"), s)
        for c in s.get("catch", []) or []:
            yield from walk_steps(c.get("children"), s)


def validate_flow(flow: dict, registry: PackageRegistry | None = None, *, raise_on_error: bool = True) -> list[dict]:
    problems: list[dict] = []

    def add(msg, step_id=None, path=None, fix=None):
        problems.append({"stepId": step_id, "path": path, "message": msg, "suggestedFix": fix})

    # 1. JSON Schema
    validator = jsonschema.Draft202012Validator(flow_schema())
    for err in sorted(validator.iter_errors(flow), key=lambda e: list(e.path)):
        path = "/".join(str(p) for p in err.absolute_path)
        add(err.message, path=path or "/", fix="Fix the Flow JSON so it matches ooa.flow/v1.")
    if problems:
        return _finish(problems, raise_on_error)

    declared = {v["name"]: v for v in flow.get("variables", [])}
    if len(declared) != len(flow.get("variables", [])):
        add("Duplicate variable names", fix="Give every variable a unique name.")
    for v in flow.get("variables", []):
        if v["type"] == "Credential" and "default" in v:
            add(f"Credential variable '{v['name']}' must not have a default value", path=f"variables/{v['name']}",
                fix="Remove the default; supply a vault reference at run time.")

    requires = (flow.get("requires") or {}).get("packages", {})
    caps = set((flow.get("requires") or {}).get("capabilities", []))
    if flow["kind"] == "ApiTask" and "desktop" in caps:
        add("ApiTask flows cannot require the desktop capability", path="requires/capabilities",
            fix="Use kind TaskBot for bots that drive a desktop.")

    seen: set[str] = set()
    for step, parent in walk_steps(flow.get("steps")):
        sid = step["id"]
        if sid in seen:
            add(f"Duplicate step id '{sid}'", sid, fix="Step ids must be unique; Studio regenerates them on paste.")
        seen.add(sid)
        pid, _, aid = step["action"].partition("/")
        args = step.get("args", {}) or {}

        # variable references
        try:
            refs = references(_cond(args)) | references([_cond({"condition": b.get("condition")})
                                                         for b in step.get("elseIf", []) or []])
        except OoaError as e:
            add(e.message, sid, "args", e.suggested_fix)
            refs = set()
        for name in sorted(refs):
            if name not in declared:
                add(f"Variable '${name}' is not declared", sid, "args",
                    f"Declare '{name}' in variables or fix the spelling.")
        for out_name, var in (step.get("out") or {}).items():
            if var not in declared:
                add(f"Output target '{var}' is not declared", sid, f"out/{out_name}", f"Declare variable '{var}'.")

        # container shape
        is_container = pid == "core" and aid in CONTAINERS
        if step.get("children") and not is_container:
            add(f"'{step['action']}' cannot have children", sid, fix="Only if, loop, try, step and parallel hold children.")
        if (step.get("catch") or step.get("finally")) and step["action"] != "core/try":
            add("catch/finally are only allowed on core/try", sid)
        if (step.get("else") or step.get("elseIf")) and step["action"] != "core/if":
            add("else/elseIf are only allowed on core/if", sid)
        if step["action"] == "core/loop":
            it = args.get("iterator")
            if isinstance(it, str) and it not in BUILTIN_ITERATORS and "/" not in it:
                add(f"Unknown iterator '{it}'", sid, "args/iterator",
                    f"Use one of {', '.join(BUILTIN_ITERATORS)} or package/iterator.")
            for k in ("as", "index"):
                if k in args and args[k] not in declared:
                    add(f"Loop variable '{args[k]}' is not declared", sid, f"args/{k}")
        if step["action"] == "core/set" and isinstance(args.get("variable"), str) and args["variable"] not in declared:
            add(f"Variable '{args['variable']}' is not declared", sid, "args/variable")
        for c in step.get("catch", []) or []:
            if c.get("as") and c["as"] not in declared:
                add(f"Catch variable '{c['as']}' is not declared", sid, "catch/as")

        # manifest checks
        if registry is None:
            continue
        try:
            pkg = registry.resolve(pid, requires.get(pid))
        except OoaError as e:
            add(e.message, sid, "action", e.suggested_fix)
            continue
        spec = pkg.spec("action", aid)
        if spec is None:
            add(f"Package {pid}@{pkg.version} has no action '{aid}'", sid, "action",
                "Pick the action from the command library.")
            continue
        if flow["kind"] == "ApiTask" and "desktop" in spec.capabilities:
            add(f"'{step['action']}' needs a desktop and cannot run in an ApiTask", sid, "action")
        mf = spec.manifest()["inputs"]
        for k in args:
            if k not in mf["properties"]:
                add(f"Unknown input '{k}' for {step['action']}", sid, f"args/{k}",
                    f"Valid inputs: {', '.join(mf['properties']) or 'none'}.")
        for k in mf["required"]:
            if k not in args:
                add(f"Missing required input '{k}' for {step['action']}", sid, f"args/{k}",
                    mf["properties"][k].get("description"))
        for k, v in args.items():
            prop = mf["properties"].get(k)
            if prop and not _has_refs(v) and not (isinstance(v, dict) and set(v) in ({"expr"}, {"literal"})):
                try:
                    literal = resolve(v, lambda n: None)
                    jsonschema.validate(literal, _strip_ooa(prop))
                except jsonschema.ValidationError as e:
                    add(f"Input '{k}': {e.message}", sid, f"args/{k}", prop.get("description"))
                except OoaError:
                    pass
        outputs = spec.manifest().get("outputs", {})
        for out_name in step.get("out") or {}:
            if out_name not in outputs:
                add(f"{step['action']} has no output '{out_name}'", sid, f"out/{out_name}",
                    f"Outputs: {', '.join(outputs) or 'none'}.")

    return _finish(problems, raise_on_error)


def _finish(problems, raise_on_error):
    if problems and raise_on_error:
        first = problems[0]
        raise FlowValidationError(f"Flow has {len(problems)} problem(s); first: {first['message']}",
                                  problems=problems, suggested_fix=first.get("suggestedFix"))
    return problems
