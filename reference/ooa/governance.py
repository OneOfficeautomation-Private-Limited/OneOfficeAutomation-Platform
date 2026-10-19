"""Governance building blocks used by the agent runtime and the Control Center:

AuditLog    append-only, hash-chained audit events (NFR-011, FR-CC-ADM007)
Policy      permissions x scopes, like AA custom roles (FR-CC-ADM004); OPA/Rego in production
Repository  immutable automation versions with production pointer and check-in
            comments (FR-CC-AUT004/005); PostgreSQL in production"""
from __future__ import annotations

import copy
import hashlib
import json
import threading
import time
from dataclasses import dataclass, field
from pathlib import Path

from .sdk.errors import OoaError, PolicyDenied
from .sdk.registry import canonical
from .sdk.types import to_jsonable

GENESIS = "0" * 64


class AuditLog:
    def __init__(self, path: Path | None = None):
        self.path = Path(path) if path else None
        self.events: list[dict] = []
        self._lock = threading.Lock()
        if self.path and self.path.exists():
            self.events = [json.loads(line) for line in self.path.read_text().splitlines() if line.strip()]

    def record(self, actor: str, action: str, obj: str, *, result: str = "success", before=None, after=None,
               details: dict | None = None, tenant: str = "default") -> dict:
        with self._lock:
            prev = self.events[-1]["hash"] if self.events else GENESIS
            ev = {"seq": len(self.events) + 1, "ts": time.time(), "tenant": tenant, "actor": actor, "action": action,
                  "object": obj, "result": result, "before": to_jsonable(before), "after": to_jsonable(after),
                  "details": to_jsonable(details or {}), "prevHash": prev}
            ev["hash"] = hashlib.sha256(prev.encode() + canonical({k: v for k, v in ev.items() if k != "hash"})).hexdigest()
            self.events.append(ev)
            if self.path:
                self.path.parent.mkdir(parents=True, exist_ok=True)
                with open(self.path, "a", encoding="utf-8") as fh:
                    fh.write(json.dumps(ev) + "\n")
            return ev

    def verify(self) -> tuple[bool, int | None]:
        """Returns (ok, first_bad_seq). Any edit, deletion or reorder breaks the chain."""
        prev = GENESIS
        for i, ev in enumerate(self.events, start=1):
            body = {k: v for k, v in ev.items() if k != "hash"}
            expect = hashlib.sha256(prev.encode() + canonical(body)).hexdigest()
            if ev.get("prevHash") != prev or ev.get("hash") != expect or ev.get("seq") != i:
                return False, i
            prev = ev["hash"]
        return True, None


@dataclass
class Principal:
    user: str
    roles: tuple[str, ...] = ()
    permissions: frozenset[str] = frozenset()
    scopes: dict[str, set[str]] = field(default_factory=dict)  # e.g. {"automation": {"refunds/*"}}
    team: str = "default"
    tenant: str = "default"


SYSTEM_ROLES: dict[str, set[str]] = {
    "Administrator": {"user.manage", "role.manage", "settings.manage", "licence.manage", "audit.view_all", "package.manage"},
    "Automation Developer": {"automation.view", "automation.edit", "automation.run", "automation.checkin", "ai.use", "credential.use"},
    "Automation Runner": {"automation.view", "automation.run", "credential.use"},
    "AI Admin": {"ai.manage", "ai.use", "agent.manage", "agent.run", "ai.governance.view"},
    "Assist User": {"assist.submit", "task.complete"},
    "Auditor": {"audit.view_all", "ai.governance.view"},
}


class Policy:
    """Allow when the principal holds the permission AND the resource is in scope.
    Every deny is logged to the audit trail."""

    def __init__(self, audit: AuditLog | None = None):
        self.audit = audit

    @staticmethod
    def permissions_for(roles: tuple[str, ...]) -> frozenset[str]:
        perms: set[str] = set()
        for r in roles:
            perms |= SYSTEM_ROLES.get(r, set())
        return frozenset(perms)

    def check(self, p: Principal, permission: str, resource_type: str | None = None, resource: str | None = None) -> None:
        perms = p.permissions or self.permissions_for(p.roles)
        ok = permission in perms
        if ok and resource_type and resource is not None:
            patterns = p.scopes.get(resource_type)
            if patterns is not None:
                ok = any(_match(resource, pat) for pat in patterns)
        if not ok:
            if self.audit:
                self.audit.record(p.user, "policy.deny", f"{resource_type or '-'}:{resource or '-'}", result="denied",
                                  details={"permission": permission}, tenant=p.tenant)
            raise PolicyDenied(f"'{p.user}' lacks {permission}" + (f" on {resource_type} '{resource}'" if resource else ""),
                               suggested_fix="Ask an administrator to grant the permission or extend your role's scope.")


def _match(value: str, pattern: str) -> bool:
    return pattern == "*" or value == pattern or (pattern.endswith("*") and value.startswith(pattern[:-1]))


class Repository:
    """Published automations: immutable versions, checksums, production pointer."""

    def __init__(self, root: Path | None = None, audit: AuditLog | None = None):
        self.root = Path(root) if root else None
        self.audit = audit
        self._versions: dict[str, list[dict]] = {}
        self._production: dict[str, int] = {}
        self._lock = threading.Lock()
        if self.root and (self.root / "index.json").exists():
            idx = json.loads((self.root / "index.json").read_text())
            self._production = {k: int(v) for k, v in idx.get("production", {}).items()}
            for aid, versions in idx.get("versions", {}).items():
                self._versions[aid] = [json.loads((self.root / aid / f"v{n}.json").read_text()) for n in versions]

    def check_in(self, flow: dict, *, author: str = "system", comment: str = "", set_production: bool = True,
                 registry=None) -> dict:
        from .runner.validate import validate_flow
        validate_flow(flow, registry)  # only valid Flows are versioned
        with self._lock:
            aid = flow["id"]
            versions = self._versions.setdefault(aid, [])
            n = len(versions) + 1
            body = copy.deepcopy(flow)
            body["version"] = n
            checksum = hashlib.sha256(canonical({k: v for k, v in body.items() if k != "ui"})).hexdigest()
            rec = {"automationId": aid, "version": n, "flow": body, "checksum": checksum, "author": author,
                   "comment": comment, "ts": time.time()}
            versions.append(rec)
            if set_production:
                self._production[aid] = n
            self._persist(aid, rec)
        if self.audit:
            self.audit.record(author, "automation.checked_in", f"automation:{aid}",
                              after={"version": n, "checksum": checksum, "production": set_production},
                              details={"comment": comment})
        return rec

    def set_production(self, aid: str, version: int, actor: str = "system") -> None:
        if not any(v["version"] == version for v in self._versions.get(aid, [])):
            raise OoaError(f"{aid} has no version {version}", code="REPO.NO_VERSION")
        before = self._production.get(aid)
        self._production[aid] = version
        self._persist(aid, None)
        if self.audit:
            self.audit.record(actor, "automation.production_set", f"automation:{aid}", before=before, after=version)

    def get(self, aid: str, version: int | str = "production") -> dict:
        versions = self._versions.get(aid)
        if not versions:
            raise OoaError(f"Automation '{aid}' is not published", code="REPO.NOT_FOUND",
                           suggested_fix="Check in the automation before calling it as a child bot or tool.")
        n = self._production.get(aid, len(versions)) if version in (None, "production") else int(version)
        for v in versions:
            if v["version"] == n:
                return copy.deepcopy(v["flow"])
        raise OoaError(f"{aid} has no version {n}", code="REPO.NO_VERSION")

    def list(self) -> list[dict]:
        return [{"automationId": aid, "versions": len(v), "production": self._production.get(aid),
                 "kind": v[-1]["flow"]["kind"], "name": v[-1]["flow"]["name"]} for aid, v in sorted(self._versions.items())]

    def _persist(self, aid: str, rec: dict | None) -> None:
        if not self.root:
            return
        if rec:
            d = self.root / aid
            d.mkdir(parents=True, exist_ok=True)
            (d / f"v{rec['version']}.json").write_text(json.dumps(rec, indent=2, default=str))
        idx = {"production": self._production,
               "versions": {k: [v["version"] for v in vs] for k, vs in self._versions.items()}}
        self.root.mkdir(parents=True, exist_ok=True)
        (self.root / "index.json").write_text(json.dumps(idx, indent=2))
