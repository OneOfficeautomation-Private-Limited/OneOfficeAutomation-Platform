"""ONEOFFICEAUTOMATION Runner (Python reference). Executes one OOA Flow run:
Load -> Plan (validate) -> Execute (walk the step tree) -> Stream events -> Finish.

The production Runner is Java 21 with one JVM per run (architecture s6); this
reference defines behaviour through the conformance suite. Leaf actions run on a
dedicated worker thread per branch (UI libraries need thread affinity); a stuck
action raises STEP.TIMEOUT and the Agent kills the process if it never returns."""
from __future__ import annotations

import concurrent.futures as cf
import copy
import hashlib
import json
import re
import threading
import time
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable

from ..sdk.errors import (ActionException, OoaError, RunCancelled, StepTimeoutError, ThrownError,
                          VariableError)
from ..sdk.package import ActionContext
from ..sdk.registry import PackageRegistry, canonical
from ..sdk.types import SecureString, coerce, safe_repr, to_jsonable
from .core_package import CONTAINERS
from .events import EventSink, ListSink, RunEvent
from .expressions import binary, evaluate, resolve, truthy
from .validate import validate_flow

MAX_CHILD_DEPTH = 10


class _Break(Exception):
    pass


class _Continue(Exception):
    pass


@dataclass
class RunResult:
    run_id: str
    automation_id: str
    status: str  # success | failed | stopped
    outputs: dict[str, Any] = field(default_factory=dict)
    error: dict | None = None
    duration_ms: int = 0
    plan_hash: str = ""
    steps_executed: int = 0

    @property
    def ok(self) -> bool:
        return self.status == "success"

    def to_dict(self) -> dict:
        return {"runId": self.run_id, "automationId": self.automation_id, "status": self.status,
                "outputs": to_jsonable(self.outputs), "error": self.error, "durationMs": self.duration_ms,
                "planHash": self.plan_hash, "stepsExecuted": self.steps_executed}


class Scope:
    """Bot-level variable scope. Child bots get a new scope (inputs/outputs only)."""

    def __init__(self, variables: list[dict]):
        self.types = {v["name"]: v["type"] for v in variables}
        self.values: dict[str, Any] = {}
        self.lock = threading.RLock()

    def get(self, name: str) -> Any:
        with self.lock:
            if name not in self.types:
                raise VariableError(f"Variable '${name}' is not declared", code="VAR.UNDECLARED",
                                    suggested_fix=f"Declare '{name}' in the bot's variables.")
            return self.values.get(name)

    def set(self, name: str, value: Any) -> Any:
        with self.lock:
            if name not in self.types:
                raise VariableError(f"Variable '{name}' is not declared", code="VAR.UNDECLARED")
            self.values[name] = coerce(value, self.types[name], name)
            return self.values[name]


class Debugger:
    """Hook for Studio's debugger (FR-STU-008). The live implementation blocks in
    before_step on breakpoints and exchanges commands over the control channel."""

    def before_step(self, run: "_Run", step: dict) -> None:  # pragma: no cover - interface
        pass


class Runner:
    def __init__(self, registry: PackageRegistry, *, services: dict[str, Any] | None = None,
                 sink: EventSink | None = None, work_dir: Path | None = None, debugger: Debugger | None = None,
                 tenant_id: str = "default", principal: str | None = None):
        self.registry = registry
        self.services = dict(services or {})
        self.sink = sink or ListSink()
        self.work_dir = Path(work_dir or Path.cwd())
        self.debugger = debugger
        self.tenant_id = tenant_id
        self.principal = principal

    def run(self, flow: dict, inputs: dict[str, Any] | None = None, *, run_id: str | None = None,
            cancel_event: threading.Event | None = None, max_run_seconds: float | None = None,
            depth: int = 0, validate: bool = True) -> RunResult:
        run = _Run(self, flow, run_id or f"run_{uuid.uuid4().hex[:12]}", cancel_event or threading.Event(),
                   max_run_seconds, depth)
        return run.execute(inputs or {}, validate)


class _Run:
    def __init__(self, runner: Runner, flow: dict, run_id: str, cancel: threading.Event,
                 max_run_seconds: float | None, depth: int):
        self.r = runner
        self.flow = flow
        self.run_id = run_id
        self.cancel = cancel
        self.deadline = time.monotonic() + max_run_seconds if max_run_seconds else None
        self.depth = depth
        self.scope = Scope(flow.get("variables", []))
        self.requires = (flow.get("requires") or {}).get("packages", {})
        self.sessions: dict[str, Any] = {}
        self.secrets: list[str] = []
        self._seq = 0
        self._seq_lock = threading.Lock()
        self._local = threading.local()
        self.steps_executed = 0

    # ---------- events ----------
    def emit(self, type_: str, step_id: str | None = None, **data) -> None:
        with self._seq_lock:
            self._seq += 1
            seq = self._seq
        self.r.sink.emit(RunEvent(self.run_id, seq, type_, step_id=step_id, data=data))

    def _log(self, level: str, message: str, step_id: str | None = None):
        for s in self.secrets:
            if s:
                message = message.replace(s, "****")
        self.emit("log", step_id, level=level, message=message)

    # ---------- lifecycle ----------
    def execute(self, inputs: dict, validate: bool) -> RunResult:
        t0 = time.monotonic()
        aid = self.flow.get("id", "?")
        status, error = "success", None
        plan_hash = ""
        try:
            if self.flow.get("kind") == "Agent":
                raise OoaError("Agent definitions run in the agent runtime, not the Runner",
                               code="RUN.WRONG_KIND", suggested_fix="Use `ooa agent run`.")
            if validate:
                validate_flow(self.flow, self.r.registry)
            self._bind_inputs(inputs)
            plan_hash = self._plan_hash(inputs)
            self.emit("run.started", automation=aid, kind=self.flow["kind"], planHash=plan_hash,
                      depth=self.depth, inputs=self._masked_inputs())
            self._exec_steps(self.flow.get("steps", []), top_level=True)
        except RunCancelled as e:
            status, error = "stopped", e.to_dict()
        except (_Break, _Continue):
            status, error = "failed", OoaError("break/continue used outside a loop", code="FLOW.BAD_BREAK").to_dict()
        except OoaError as e:
            status, error = "failed", e.to_dict()
        except Exception as e:  # defensive: never crash the agent
            status, error = "failed", OoaError(f"Internal runner error: {e}", code="RUN.INTERNAL").to_dict()
        finally:
            self._close_sessions()
        outputs = {v["name"]: self.scope.values.get(v["name"]) for v in self.flow.get("variables", [])
                   if v.get("direction") == "output"}
        dur = int((time.monotonic() - t0) * 1000)
        self.emit("run.finished", status=status, durationMs=dur, error=error, outputs=outputs)
        self.secrets.clear()
        return RunResult(self.run_id, aid, status, outputs if status == "success" else outputs, error, dur,
                         plan_hash, self.steps_executed)

    def _bind_inputs(self, inputs: dict) -> None:
        declared = {v["name"]: v for v in self.flow.get("variables", [])}
        unknown = [k for k in inputs if k not in declared or declared[k].get("direction") != "input"]
        if unknown:
            raise OoaError(f"Unknown input(s): {', '.join(unknown)}", code="RUN.INPUT_UNKNOWN",
                           suggested_fix="Only variables with direction 'input' can be supplied.")
        for name, v in declared.items():
            if v.get("direction") == "input" and name in inputs:
                val = inputs[name]
                if isinstance(val, dict) and set(val) == {"$credential"}:
                    vault = self.r.services.get("vault")
                    if vault is None:
                        raise OoaError("No vault configured for credential inputs", code="VAULT.UNAVAILABLE")
                    val = vault.get_secret(val["$credential"], principal=self.r.principal)
                if isinstance(val, SecureString):
                    self.secrets.append(val.reveal())
                if v.get("enum") and val not in v["enum"]:
                    raise OoaError(f"Input '{name}' must be one of {v['enum']}", code="RUN.INPUT_INVALID")
                self.scope.set(name, val)
            elif "default" in v:
                self.scope.set(name, copy.deepcopy(v["default"]))
            elif v.get("direction") == "input" and v.get("required"):
                raise OoaError(f"Required input '{name}' is missing", code="RUN.INPUT_MISSING",
                               suggested_fix=v.get("description") or f"Provide a {v['type']} value for '{name}'.")
            else:
                self.scope.values[name] = None

    def _masked_inputs(self) -> dict:
        return {k: self.scope.values.get(k) for k, v in self.scope.types.items()
                if any(x["name"] == k and x.get("direction") == "input" for x in self.flow.get("variables", []))}

    def _plan_hash(self, inputs: dict) -> str:
        """Same Flow + inputs + package versions -> same plan (determinism, replay, audit)."""
        logic = {k: v for k, v in self.flow.items() if k != "ui"}
        versions = {}
        for pid in sorted({s.split("/")[0] for s in _all_actions(self.flow.get("steps", []))}):
            try:
                versions[pid] = self.r.registry.resolve(pid, self.requires.get(pid)).version
            except OoaError:
                versions[pid] = "?"
        h = hashlib.sha256(canonical(logic) + canonical(versions) + canonical(to_jsonable(inputs)))
        return h.hexdigest()

    def _close_sessions(self) -> None:
        for name, obj in list(self.sessions.items()):
            close = getattr(obj, "close", None)
            if callable(close):
                try:
                    close()
                except Exception:
                    pass
        self.sessions.clear()

    # ---------- execution ----------
    def lookup(self, name: str) -> Any:
        return self.scope.get(name)

    def _check(self) -> None:
        if self.cancel.is_set():
            raise RunCancelled("Run was stopped from Control Center")
        if self.deadline and time.monotonic() > self.deadline:
            raise StepTimeoutError("Run exceeded its maximum duration", code="RUN.TIMEOUT", retryable=False,
                                   suggested_fix="Raise the run time limit or split the bot.")

    def _exec_steps(self, steps: list[dict], top_level: bool = False) -> None:
        continue_default = top_level and (self.flow.get("errorHandling") or {}).get("default") == "continue"
        for step in steps or []:
            if step.get("disabled"):
                continue
            self._exec_step(step, force_continue=continue_default)

    def _exec_step(self, step: dict, force_continue: bool = False) -> None:
        self._check()
        sid, act = step["id"], step["action"]
        if self.r.debugger:
            self.r.debugger.before_step(self, step)
        self.emit("step.started", sid, action=act)
        t0 = time.monotonic()
        try:
            pid, _, aid = act.partition("/")
            if pid == "core":
                self._exec_core(step, aid)
            else:
                self._exec_leaf(step, pid, aid)
            self.steps_executed += 1
        except (_Break, _Continue):
            self.emit("step.finished", sid, action=act, durationMs=_ms(t0))
            raise
        except RunCancelled:
            raise
        except OoaError as e:
            e.step_id = e.step_id or sid
            self.emit("step.failed", sid, action=act, durationMs=_ms(t0), **e.to_dict())
            if step.get("continueOnError") or force_continue:
                self._log("warning", f"Continuing after error {e.code}", sid)
                return
            raise
        self.emit("step.finished", sid, action=act, durationMs=_ms(t0))

    # ----- core / containers -----
    def _exec_core(self, step: dict, aid: str) -> None:
        a = step.get("args", {}) or {}
        sid = step["id"]
        if aid == "if":
            if self.condition(a.get("condition"), sid):
                return self._exec_steps(step.get("children"))
            for b in step.get("elseIf", []) or []:
                if self.condition(b["condition"], sid):
                    return self._exec_steps(b["children"])
            return self._exec_steps(step.get("else"))
        if aid == "loop":
            return self._loop(step, a)
        if aid == "try":
            return self._try(step)
        if aid == "step":
            return self._exec_steps(step.get("children"))
        if aid == "parallel":
            return self._parallel(step, a)
        if aid == "set":
            value = resolve(a.get("value"), self.lookup)
            stored = self.scope.set(a["variable"], value)
            self.emit("variable.set", sid, name=a["variable"], value=safe_repr(stored))
            return
        if aid == "log":
            return self._log(a.get("level", "info"), str(resolve(a["message"], self.lookup)), sid)
        if aid == "delay":
            return self._sleep(float(coerce(resolve(a["seconds"], self.lookup), "Number")))
        if aid == "wait_until":
            timeout = float(coerce(resolve(a.get("timeout_seconds", 30), self.lookup), "Number"))
            poll = float(coerce(resolve(a.get("poll_seconds", 1), self.lookup), "Number"))
            end = time.monotonic() + timeout
            while not self.condition(a["condition"], sid):
                if time.monotonic() >= end:
                    raise OoaError(f"Condition not met within {timeout:g} s", code="WAIT.TIMEOUT", retryable=True)
                self._sleep(poll)
            return
        if aid == "throw":
            raise ThrownError(str(resolve(a["message"], self.lookup)), code=str(resolve(a["code"], self.lookup)),
                              suggested_fix=resolve(a.get("suggested_fix"), self.lookup))
        if aid == "comment":
            return
        if aid == "break":
            raise _Break()
        if aid == "continue":
            raise _Continue()
        if aid == "run_bot":
            return self._run_child(step, a)
        raise OoaError(f"Unknown core action '{aid}'", code="FLOW.UNKNOWN_ACTION")

    def _sleep(self, seconds: float) -> None:
        end = time.monotonic() + seconds
        while True:
            self._check()
            left = end - time.monotonic()
            if left <= 0:
                return
            time.sleep(min(left, 0.05))

    def condition(self, c: Any, step_id: str | None = None) -> bool:
        if isinstance(c, bool) or c is None:
            return bool(c)
        if isinstance(c, str):
            return truthy(evaluate(c, self.lookup))
        if isinstance(c, dict):
            if "all" in c:
                return all(self.condition(x, step_id) for x in c["all"])
            if "any" in c:
                return any(self.condition(x, step_id) for x in c["any"])
            if "not" in c:
                return not self.condition(c["not"], step_id)
            if "expr" in c:
                return truthy(evaluate(c["expr"], self.lookup))
            if "op" in c:
                return _compare(c["op"], resolve(c.get("left"), self.lookup), resolve(c.get("right"), self.lookup))
            if "condition" in c:
                pid, _, cid = c["condition"].partition("/")
                pkg = self.r.registry.resolve(pid, self.requires.get(pid))
                spec = pkg.spec("condition", cid)
                if spec is None:
                    raise OoaError(f"Unknown condition '{c['condition']}'", code="FLOW.UNKNOWN_CONDITION")
                args = self._prepare_args(spec, resolve(c.get("args", {}), self.lookup))
                return bool(spec.fn(pkg.instance, self._ctx(step_id or "?"), **args))
        return truthy(resolve(c, self.lookup))

    def _loop(self, step: dict, a: dict) -> None:
        sid = step["id"]
        limit = int(coerce(resolve(a.get("max_iterations", 100000), self.lookup), "Number"))
        it = a["iterator"]
        if it == "times":
            items = range(int(coerce(resolve(a.get("count"), self.lookup), "Number")))
        elif it == "while":
            items = _while(lambda: self.condition(a.get("condition"), sid))
        elif it in ("list", "dictionary", "table_rows"):
            over = resolve(a.get("over"), self.lookup)
            if it == "list":
                items = list(over or [])
            elif it == "dictionary":
                items = [{"key": k, "value": v} for k, v in (over or {}).items()]
            else:
                table = coerce(over, "Table") if over is not None else {"columns": [], "rows": []}
                items = [dict(zip(table["columns"], r)) for r in table["rows"]]
        else:
            pid, _, iid = it.partition("/")
            pkg = self.r.registry.resolve(pid, self.requires.get(pid))
            spec = pkg.spec("iterator", iid)
            if spec is None:
                raise OoaError(f"Unknown iterator '{it}'", code="FLOW.UNKNOWN_ITERATOR")
            args = self._prepare_args(spec, resolve(a.get("iterator_args", {}), self.lookup))
            items = spec.fn(pkg.instance, self._ctx(sid), **args)
        for i, item in enumerate(items):
            if i >= limit:
                raise OoaError(f"Loop exceeded {limit} iterations", code="LOOP.LIMIT",
                               suggested_fix="Check the loop condition or raise max_iterations.")
            self._check()
            if a.get("as"):
                self.scope.set(a["as"], item)
            if a.get("index"):
                self.scope.set(a["index"], i)
            try:
                self._exec_steps(step.get("children"))
            except _Continue:
                continue
            except _Break:
                break

    def _try(self, step: dict) -> None:
        try:
            self._exec_steps(step.get("children"))
        except RunCancelled:
            raise
        except OoaError as e:
            for c in step.get("catch", []) or []:
                codes = c.get("codes") or []
                if not codes or any(e.code == x or (x.endswith("*") and e.code.startswith(x[:-1])) for x in codes):
                    if c.get("as"):
                        self.scope.set(c["as"], e.to_dict())
                    self._log("info", f"Caught {e.code}", step["id"])
                    self._exec_steps(c.get("children"))
                    break
            else:
                raise
        finally:
            if step.get("finally"):
                self._exec_steps(step["finally"])

    def _parallel(self, step: dict, a: dict) -> None:
        n = int(coerce(resolve(a.get("max_concurrency", 4), self.lookup), "Number"))
        branches = [s for s in step.get("children", []) or [] if not s.get("disabled")]
        errors: list[OoaError] = []
        with cf.ThreadPoolExecutor(max_workers=max(1, n), thread_name_prefix=f"ooa-par-{step['id']}") as pool:
            futures = [pool.submit(self._exec_step, b) for b in branches]
            for f in futures:
                try:
                    f.result()
                except OoaError as e:
                    errors.append(e)
        if errors:
            raise errors[0]

    def _run_child(self, step: dict, a: dict) -> None:
        if self.depth + 1 > MAX_CHILD_DEPTH:
            raise OoaError("Child bot nesting is too deep", code="CHILD.DEPTH",
                           suggested_fix=f"Keep child bot chains under {MAX_CHILD_DEPTH} levels; check for recursion.")
        repo = self.r.services.get("repository")
        if repo is None:
            raise OoaError("No repository available for child bots", code="SERVICE.UNAVAILABLE")
        args = resolve(a, self.lookup)
        child_flow = repo.get(args["automation"], args.get("version") or "production")
        child = _Run(self.r, child_flow, f"{self.run_id}.{step['id']}", self.cancel,
                     None, self.depth + 1)
        child.deadline = self.deadline
        res = child.execute(args.get("inputs") or {}, validate=True)
        self.steps_executed += res.steps_executed
        if res.status == "stopped":
            raise RunCancelled("Child run was stopped")
        if not res.ok:
            err = res.error or {}
            raise OoaError(f"Child bot '{args['automation']}' failed: {err.get('message')}",
                           code=err.get("code", "CHILD.FAILED"), suggested_fix=err.get("suggestedFix"),
                           details={"childRunId": res.run_id, "childStepId": err.get("stepId")})
        self._store_outputs(step, {"outputs": res.outputs, "status": res.status})

    # ----- leaf actions -----
    def _ctx(self, step_id: str) -> ActionContext:
        return ActionContext(run_id=self.run_id, step_id=step_id, tenant_id=self.r.tenant_id,
                             work_dir=self.r.work_dir, sessions=self.sessions, services=self.r.services,
                             cancel_event=self.cancel, secret_values=lambda: list(self.secrets),
                             log_sink=lambda lvl, msg, s=step_id: self.emit("log", s, level=lvl, message=msg))

    def _prepare_args(self, spec, raw: dict) -> dict:
        out = {}
        for inp in spec.inputs:
            if inp.name in raw and raw[inp.name] is not None:
                v = raw[inp.name]
                if inp.type not in ("Any", "Credential") and not isinstance(v, SecureString):
                    v = coerce(v, inp.type, inp.name)
                if inp.pattern and isinstance(v, str) and not re.match(inp.pattern, v):
                    raise ActionException(f"Input '{inp.name}' value {v!r} does not match {inp.pattern}",
                                          code="ACTION.INPUT_INVALID", suggested_fix=inp.description)
                out[inp.name] = v
            elif inp.default is not None:
                out[inp.name] = inp.default
            elif inp.required:
                raise ActionException(f"Input '{inp.name}' resolved to empty", code="ACTION.INPUT_MISSING",
                                      suggested_fix=inp.description)
        return out

    def _call(self, fn: Callable, timeout: float):
        ex = getattr(self._local, "executor", None)
        if ex is None:
            ex = cf.ThreadPoolExecutor(max_workers=1, thread_name_prefix="ooa-action")
            self._local.executor = ex
        fut = ex.submit(fn)
        try:
            return fut.result(timeout=timeout)
        except cf.TimeoutError:
            self._local.executor = None  # abandon the stuck worker; the Agent kills the process if needed
            ex.shutdown(wait=False, cancel_futures=True)
            raise StepTimeoutError(f"Action did not finish within {timeout:g} s",
                                   suggested_fix="Raise timeoutSeconds on the step or check the target application.")

    def _exec_leaf(self, step: dict, pid: str, aid: str) -> None:
        sid = step["id"]
        pkg = self.r.registry.resolve(pid, self.requires.get(pid))
        spec = pkg.spec("action", aid)
        if spec is None:
            raise OoaError(f"{pid}@{pkg.version} has no action '{aid}'", code="FLOW.UNKNOWN_ACTION")
        self.r.registry.record_usage(pid, pkg.version, self.flow.get("id", "?"))
        policy = step.get("retry") or (self.flow.get("errorHandling") or {}).get("retry") or {}
        max_retry = int(policy.get("max", 0))
        timeout = float(step.get("timeoutSeconds") or spec.timeout_seconds)
        attempt = 0
        while True:
            try:
                args = self._prepare_args(spec, resolve(step.get("args", {}) or {}, self.lookup))
                ctx = self._ctx(sid)
                result = self._call(lambda: spec.fn(pkg.instance, ctx, **args), timeout)
                break
            except RunCancelled:
                raise
            except OoaError as e:
                err = e
            except Exception as e:  # package bug -> typed error, never a crash
                err = ActionException(f"{pid}/{aid} raised {type(e).__name__}: {e}", code="ACTION.UNEXPECTED",
                                      suggested_fix="Report this to the package publisher with the run id.")
            codes = policy.get("codes")
            should = attempt < max_retry and (err.code in codes if codes else err.retryable)
            if not should:
                raise err
            attempt += 1
            delay = int(policy.get("delayMs", 500)) / 1000
            if policy.get("backoff") == "exponential":
                delay *= 2 ** (attempt - 1)
            self.emit("step.retry", sid, attempt=attempt, code=err.code, delayMs=int(delay * 1000))
            self._sleep(delay)
        self._store_outputs(step, result or {})

    def _store_outputs(self, step: dict, result: dict) -> None:
        for out_name, var in (step.get("out") or {}).items():
            if out_name not in result:
                raise ActionException(f"Action did not return output '{out_name}'", code="ACTION.OUTPUT_MISSING")
            value = result[out_name]
            if isinstance(value, SecureString):
                self.secrets.append(value.reveal())
            stored = self.scope.set(var, value)
            self.emit("variable.set", step["id"], name=var, value=safe_repr(stored))


def _ms(t0: float) -> int:
    return int((time.monotonic() - t0) * 1000)


def _while(check):
    while check():
        yield None


def _all_actions(steps):
    for s in steps or []:
        yield s["action"]
        for k in ("children", "else", "finally"):
            yield from _all_actions(s.get(k))
        for b in s.get("elseIf", []) or []:
            yield from _all_actions(b.get("children"))
        for c in s.get("catch", []) or []:
            yield from _all_actions(c.get("children"))


def _compare(op: str, left, right) -> bool:
    sym = {"eq": "==", "ne": "!=", "lt": "<", "le": "<=", "gt": ">", "ge": ">="}
    if op in sym:
        return bool(binary(sym[op], left, right))
    s = lambda v: "" if v is None else coerce(v, "String")  # noqa: E731
    if op == "contains":
        return (right in left) if isinstance(left, (list, dict)) else s(right) in s(left)
    if op == "notContains":
        return not _compare("contains", left, right)
    if op == "startsWith":
        return s(left).startswith(s(right))
    if op == "endsWith":
        return s(left).endswith(s(right))
    if op == "matches":
        return re.search(s(right), s(left)) is not None
    if op == "empty":
        return left in (None, "", [], {})
    if op == "notEmpty":
        return left not in (None, "", [], {})
    if op == "in":
        return left in (right or [])
    raise OoaError(f"Unknown condition operator '{op}'", code="FLOW.BAD_CONDITION",
                   suggested_fix="Use eq, ne, lt, le, gt, ge, contains, notContains, startsWith, endsWith, matches, empty, notEmpty or in.")
