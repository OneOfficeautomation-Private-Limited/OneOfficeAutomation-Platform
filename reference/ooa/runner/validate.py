"""Flow validation (FR-STU-007, architecture s6 'Plan'). Returns every problem
at once, each linked to a step id with a suggested fix, so Studio can list them
and the co-pilot can repair them. Only schema-valid Flows are ever executed."""
from __future__ import annotations

import json
import os
import re
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
        for c in s.get("cases", []) or []:
            yield from walk_steps(c.get("children"), s)
        yield from walk_steps(s.get("default"), s)


def _switch_problems(step: dict, args: dict, add) -> None:
    """core/switch: a value unless the cases are conditions, at least one case with values, literal patterns that compile."""
    sid, match = step["id"], args.get("match", "equals")
    if match != "expression" and "value" not in args:
        add("Switch has no value to compare", sid, "args/value", "Give the value (for example $status), or set match to expression.")
    cases = step.get("cases") or []
    if not cases:
        add("Switch needs at least one case", sid, "cases", "Add a case with the values that select it.")
    for i, case in enumerate(cases):
        values = case.get("values") or []
        if not values:
            add(f"Case {i + 1} has no values", sid, f"cases/{i}/values", "Give at least one value that selects this case.")
        for j, v in enumerate(values):
            if match == "regex" and isinstance(v, str) and "${" not in v and not _has_refs(v):
                try:
                    re.compile(v)
                except re.error as e:
                    add(f"Case {i + 1}: '{v}' is not a valid pattern: {e}", sid, f"cases/{i}/values/{j}", "Fix the regular expression.")


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

    # the agent's error handler: its variable here, its steps below like any others
    handler = flow.get("onError") or {}
    if handler.get("as"):
        var = handler["as"]
        if var in ("sys", "global"):
            add(f"Cannot store the error in the built-in variable '{var}'", path="onError/as", fix="Choose one of your own variables.")
        elif var not in declared:
            add(f"Error handler variable '{var}' is not declared", path="onError/as", fix=f"Declare a Dictionary variable '{var}'.")
        elif declared[var]["type"] not in ("Dictionary", "Any"):
            add(f"Error handler variable '{var}' must be a Dictionary, not {declared[var]['type']}", path="onError/as",
                fix=f"Make '{var}' a Dictionary variable.")

    # subflows: named step lists run by core/run_subflow; Go to and loop exits are checked per step tree
    sub_names: list[str] = []
    for i, sub in enumerate(flow.get("subflows") or []):
        name = sub.get("name")
        if not isinstance(name, str) or not name.strip():
            add(f"Subflow {i + 1} has no name", path=f"subflows/{i}/name", fix="Give every subflow a name.")
        elif name in sub_names:
            add(f"Duplicate subflow name '{name}'", path=f"subflows/{i}/name", fix="Give every subflow a unique name.")
        else:
            sub_names.append(name)
    _check_tree(add, flow.get("steps") or [], None)
    for sub in flow.get("subflows") or []:
        if sub.get("name") in sub_names:
            _check_tree(add, sub.get("steps") or [], sub["name"])
    _check_tree(add, handler.get("steps") or [], None)
    every = [*(flow.get("steps") or []), *(st for sub in flow.get("subflows") or [] for st in sub.get("steps") or []),
             *(handler.get("steps") or [])]

    seen: set[str] = set()
    for step, parent in walk_steps(every):
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
            by_condition = args.get("match") == "expression"
            for case in step.get("cases", []) or []:
                for v in case.get("values") or []:
                    refs |= references(_norm_condition(v) if by_condition else v)
        except OoaError as e:
            add(e.message, sid, "args", e.suggested_fix)
            refs = set()
        for name in sorted(refs):
            if name not in declared and name not in ("sys", "global"):   # built in: facts about the run, shared values
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
        if ("cases" in step or "default" in step) and step["action"] != "core/switch":
            add("cases/default are only allowed on core/switch", sid)
        if step["action"] == "core/switch":
            _switch_problems(step, args, add)
        if step["action"] == "core/loop":
            it = args.get("iterator")
            if isinstance(it, str) and it not in BUILTIN_ITERATORS and "/" not in it:
                add(f"Unknown iterator '{it}'", sid, "args/iterator",
                    f"Use one of {', '.join(BUILTIN_ITERATORS)} or package/iterator.")
            for k in ("as", "index"):
                if k in args and args[k] not in declared:
                    add(f"Loop variable '{args[k]}' is not declared", sid, f"args/{k}")
        if step["action"] == "core/run_subflow" and isinstance(args.get("subflow"), str) and "$" not in args["subflow"] \
                and args["subflow"].strip() and args["subflow"].strip() not in sub_names:
            add(f"There is no subflow named '{args['subflow']}'", sid, "args/subflow",
                f"Subflows: {', '.join(sub_names)}." if sub_names else "Add the subflow to the automation, or remove the step.")
        if step["action"] == "core/set" and isinstance(args.get("variable"), str) and args["variable"] not in declared:
            add(f"Variable '{args['variable']}' is not declared", sid, "args/variable")
        if step["action"] == "core/set_many" and isinstance(args.get("assignments"), list):
            if not args["assignments"]:
                add("No assignments", sid, "args/assignments", "Add at least one variable and value.")
            for i, row in enumerate(args["assignments"]):
                v = row.get("variable") if isinstance(row, dict) else None
                if not isinstance(v, str) or not v.strip():
                    add(f"Assignment {i + 1} has no variable", sid, f"args/assignments/{i}", "Give each row a declared variable and a value.")
                elif v not in declared:
                    add(f"Variable '{v}' is not declared", sid, f"args/assignments/{i}/variable")
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


def _label_name(step):
    v = (step.get("args") or {}).get("label" if step.get("action") == "core/goto" else "name")
    return v.strip() if isinstance(v, str) and v.strip() else None


def _check_tree(add, steps, subflow):
    """One step tree (main steps, or a subflow's): Go to reaches a Label in its own list or a list around it, never into
    a block, out of a Parallel branch or out of the tree; label names are used once among those a Go to can see. In a
    subflow, Exit loop and Continue loop need a loop of the subflow around them. Exit subflow is only for subflows."""
    all_labels = {_label_name(s) for s, _ in walk_steps(steps) if s.get("action") == "core/label"} - {None}
    where = "" if subflow is None else f" of subflow '{subflow}'"

    def visit(lst, outer, loops, owns):
        scope = set(outer)
        if owns:
            own = set()
            for s in lst:
                if s.get("action") == "core/label":
                    n = _label_name(s)
                    if n is None:
                        add("The Label has no name", s.get("id"), "args/name", "Give the label a name for Go to steps.")
                    elif n in own or n in outer:
                        add(f"Duplicate label '{n}'" + (" (a list around it has one too)" if n in outer else ""), s.get("id"), "args/name",
                            "Give each label a name of its own, so Go to knows where to carry on.")
                    if n:
                        own.add(n)
            scope |= own
        for s in lst:
            act, sid, args = s.get("action"), s.get("id"), s.get("args") or {}
            if act == "core/goto":
                n = _label_name(s)
                if n is None:
                    add("Go to has no label", sid, "args/label", "Choose the label to carry on from.")
                elif n not in scope:
                    add(f"Go to label '{n}' cannot be reached from here: it would jump into a block or out of a Parallel branch"
                        if n in all_labels else f"There is no label '{n}' in the steps{where}", sid, "args/label",
                        "Go to can jump to a label in its own step list or a list around it.")
            elif act == "core/exit_subflow" and subflow is None:
                add("Exit subflow is only for subflows", sid, fix="Use Stop agent to end the main steps.")
            elif act in ("core/break", "core/continue") and subflow is not None:
                lb = args.get("label")
                lb = lb.strip() if isinstance(lb, str) and lb.strip() and "$" not in lb else None
                if not loops or (lb is not None and lb not in loops):
                    add(("Exit loop" if act == "core/break" else "Continue loop") + " is not inside a loop"
                        + ("" if lb is None else f" labelled '{lb}'") + where, sid, None if lb is None else "args/label",
                        "A subflow can only leave or continue its own loops.")
            if act == "core/parallel":
                for b in s.get("children") or []:
                    visit([b], set(), [], False)
                continue
            inner = loops + [str(args.get("label") or "").strip()] if act == "core/loop" else loops
            for k in ("children", "else", "finally", "default"):
                if s.get(k):
                    visit(s[k], scope, inner, True)
            for k in ("elseIf", "catch", "cases"):
                for b in s.get(k) or []:
                    visit(b.get("children") or [], scope, inner, True)

    visit(steps, set(), [], True)


def _finish(problems, raise_on_error):
    if problems and raise_on_error:
        first = problems[0]
        raise FlowValidationError(f"Flow has {len(problems)} problem(s); first: {first['message']}",
                                  problems=problems, suggested_fix=first.get("suggestedFix"))
    return problems
