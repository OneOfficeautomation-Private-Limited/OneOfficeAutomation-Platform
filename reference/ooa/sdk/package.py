"""OOA Package SDK (Python reference). Mirrors the Java SDK in architecture
section 5: decorators play the role of @OoaPackage/@Action/@Condition/@Iterator
annotations, and `build_manifest` plays the role of the annotation processor.
The manifest format is identical across SDKs; Studio renders command panels from
it and the Runner validates step args against it (FR-PKG-001, FR-PKG-002)."""
from __future__ import annotations

import inspect
import logging
import re
import threading
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable

from .errors import PackageError, RunCancelled
from .labels import label_of
from .types import MASK, OoaType, SecureString, json_schema_for

MANIFEST_VERSION = 1
_ID = re.compile(r"^[a-z][a-z0-9_]*$")
_PKG_ID = re.compile(r"^[a-z][a-z0-9_.]*$")


@dataclass(frozen=True)
class Input:
    name: str
    type: str = "Any"
    label: str | None = None
    description: str = ""
    required: bool = True
    default: Any = None
    pattern: str | None = None
    enum: tuple | None = None
    example: Any = None
    # when the field applies, e.g. "iterator=times" or "mode!=none;regex=true" (same syntax as the Java SDK)
    visible_when: str | None = None
    minimum: float | None = None
    maximum: float | None = None

    def visible_rule(self) -> dict:
        rule: dict = {}
        for part in (self.visible_when or "").split(";"):
            part = part.strip()
            if not part:
                continue
            neg = "!=" in part
            key, vals = part.split("!=" if neg else "=", 1)
            rule[key.strip()] = (["!"] if neg else []) + [v.strip() for v in vals.split("|")]
        return rule

    def schema(self) -> dict:
        s = dict(json_schema_for(self.type))
        if self.type == "Any":
            s = {}
        if self.description:
            s["description"] = self.description
        if self.pattern:
            s["pattern"] = self.pattern
        if self.enum:
            s["enum"] = list(self.enum)
        if self.default is not None:
            s["default"] = self.default
        if self.example is not None:
            s["examples"] = [self.example]
        if self.minimum is not None:
            s["minimum"] = self.minimum
        if self.maximum is not None:
            s["maximum"] = self.maximum
        if self.visible_when:
            s["x-ooa-visible-when"] = self.visible_rule()
        s["x-ooa-type"] = self.type
        s["x-ooa-label"] = self.label or label_of(self.name)
        return s


@dataclass(frozen=True)
class Output:
    name: str
    type: str = "Any"
    description: str = ""


@dataclass
class ActionSpec:
    kind: str  # action | condition | iterator
    id: str
    name: str
    description: str
    category: str = "General"
    inputs: tuple[Input, ...] = ()
    outputs: tuple[Output, ...] = ()
    timeout_seconds: float = 60
    idempotent: bool = False
    capabilities: tuple[str, ...] = ()
    fn: Callable | None = None

    def manifest(self) -> dict:
        props = {i.name: i.schema() for i in self.inputs}
        required = [i.name for i in self.inputs if i.required and i.default is None]
        m = {
            "id": self.id, "name": self.name, "description": self.description, "category": self.category,
            "inputs": {"type": "object", "properties": props, "required": required, "additionalProperties": False},
        }
        if self.kind == "action":
            m["outputs"] = {o.name: {"x-ooa-type": o.type, "description": o.description} for o in self.outputs}
            m.update(timeoutSeconds=self.timeout_seconds, idempotent=self.idempotent,
                     capabilities=list(self.capabilities))
        return m


def _validate_spec(spec: ActionSpec) -> None:
    if not _ID.match(spec.id):
        raise PackageError(f"Invalid {spec.kind} id '{spec.id}'", suggested_fix="Use lower_snake_case.")
    if not spec.description:
        # Architecture s19: CI fails on missing descriptions, because LLMs read them.
        raise PackageError(f"{spec.kind} '{spec.id}' has no description",
                           suggested_fix="Add a one-sentence description; the co-pilot and agents rely on it.")
    for i in spec.inputs:
        OoaType(i.type)
        if not i.description:
            raise PackageError(f"Input '{i.name}' of '{spec.id}' has no description")


def _decorator(kind: str):
    def outer(id: str, name: str, *, description: str, category: str = "General",
              inputs: tuple | list = (), outputs: tuple | list = (), timeout_seconds: float = 60,
              idempotent: bool = False, capabilities: tuple | list = ()):
        def deco(fn: Callable) -> Callable:
            spec = ActionSpec(kind, id, name, description, category, tuple(inputs), tuple(outputs),
                              timeout_seconds, idempotent, tuple(capabilities), fn)
            _validate_spec(spec)
            fn.__ooa_spec__ = spec
            return fn
        return deco
    return outer


action = _decorator("action")
condition = _decorator("condition")
iterator = _decorator("iterator")


def package(id: str, name: str, version: str, *, description: str, publisher: str = "oneofficeautomation"):
    if not _PKG_ID.match(id):
        raise PackageError(f"Invalid package id '{id}'")
    if not re.match(r"^\d+\.\d+\.\d+$", version):
        raise PackageError(f"Package version '{version}' is not semantic (x.y.z)")

    def deco(cls):
        specs: dict[str, dict[str, ActionSpec]] = {"action": {}, "condition": {}, "iterator": {}}
        for _, member in inspect.getmembers(cls, predicate=inspect.isfunction):
            spec = getattr(member, "__ooa_spec__", None)
            if spec:
                if spec.id in specs[spec.kind]:
                    raise PackageError(f"Duplicate {spec.kind} '{spec.id}' in {id}")
                specs[spec.kind][spec.id] = spec
        cls.__ooa_package__ = {"id": id, "name": name, "version": version, "description": description,
                               "publisher": publisher, "specs": specs}
        return cls
    return deco


def build_manifest(cls) -> dict:
    meta = getattr(cls, "__ooa_package__", None)
    if not meta:
        raise PackageError(f"{cls.__name__} is not decorated with @package")
    s = meta["specs"]
    return {
        "manifestVersion": MANIFEST_VERSION,
        "id": meta["id"], "name": meta["name"], "version": meta["version"],
        "description": meta["description"], "publisher": meta["publisher"],
        "actions": [a.manifest() for a in sorted(s["action"].values(), key=lambda x: x.id)],
        "conditions": [a.manifest() for a in sorted(s["condition"].values(), key=lambda x: x.id)],
        "iterators": [a.manifest() for a in sorted(s["iterator"].values(), key=lambda x: x.id)],
    }


class _RedactingAdapter(logging.LoggerAdapter):
    """Structured logger whose output never contains secret values."""

    def __init__(self, logger, extra, secrets: Callable[[], list[str]], sink: Callable | None):
        super().__init__(logger, extra)
        self._secrets = secrets
        self._sink = sink

    def log(self, level, msg, *args, **kwargs):
        text = str(msg) % args if args else str(msg)
        for s in self._secrets():
            if s:
                text = text.replace(s, MASK)
        if self._sink:
            self._sink(logging.getLevelName(level).lower(), text)
        self.logger.log(level, text, extra=self.extra)

    def info(self, msg, *a, **k): self.log(logging.INFO, msg, *a, **k)
    def warning(self, msg, *a, **k): self.log(logging.WARNING, msg, *a, **k)
    def error(self, msg, *a, **k): self.log(logging.ERROR, msg, *a, **k)
    def debug(self, msg, *a, **k): self.log(logging.DEBUG, msg, *a, **k)


@dataclass
class ActionContext:
    """What a package action can see. Created by the Runner for each step."""
    run_id: str
    step_id: str
    tenant_id: str = "default"
    work_dir: Path = field(default_factory=lambda: Path.cwd())
    sessions: dict[str, Any] = field(default_factory=dict)
    services: dict[str, Any] = field(default_factory=dict)
    cancel_event: threading.Event = field(default_factory=threading.Event)
    secret_values: Callable[[], list[str]] = lambda: []
    log_sink: Callable[[str, str], None] | None = None

    @property
    def log(self) -> logging.LoggerAdapter:
        return _RedactingAdapter(logging.getLogger("ooa.package"), {"run_id": self.run_id, "step_id": self.step_id},
                                 self.secret_values, self.log_sink)

    def session(self, name: str, kind: type | None = None) -> Any:
        if name not in self.sessions:
            raise PackageError(f"Session '{name}' is not open", code="SESSION.NOT_FOUND",
                               suggested_fix=f"Add an 'open' step that creates session '{name}' before this step.")
        obj = self.sessions[name]
        if kind is not None and not isinstance(obj, kind):
            raise PackageError(f"Session '{name}' is a {type(obj).__name__}, expected {kind.__name__}",
                               code="SESSION.WRONG_TYPE")
        return obj

    def service(self, name: str) -> Any:
        if name not in self.services:
            raise PackageError(f"Platform service '{name}' is not available to this run",
                               code="SERVICE.UNAVAILABLE",
                               suggested_fix="Check the run was started with the required platform services.")
        return self.services[name]

    def check_cancelled(self) -> None:
        if self.cancel_event.is_set():
            raise RunCancelled("Run was stopped from Control Center")

    def resolve_path(self, p: str) -> Path:
        path = Path(p)
        return path if path.is_absolute() else (self.work_dir / path)


def reveal(value: Any) -> Any:
    """Packages call this right before handing a secret to an external API."""
    return value.reveal() if isinstance(value, SecureString) else value
