"""Human approval for sensitive tools (FR-AI-007, spec journey 9.6 step 3).
In production these are Assist tasks with SLA, escalation and notifications;
the agent's Temporal workflow waits on the approval signal. Here, InboxApproval
persists requests to a JSON file and the agent run checkpoints until decided."""
from __future__ import annotations

import json
import threading
import time
import uuid
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Callable, Protocol

from ..sdk.errors import OoaError
from ..sdk.types import to_jsonable

PENDING, APPROVED, DENIED = "pending", "approved", "denied"


@dataclass
class ApprovalRequest:
    agent_run_id: str
    agent_id: str
    tool: str
    automation_id: str
    arguments: dict
    requested_for: str
    reason: str = ""
    id: str = field(default_factory=lambda: f"apr_{uuid.uuid4().hex[:10]}")
    status: str = PENDING
    created_at: float = field(default_factory=time.time)
    decided_by: str | None = None
    decided_at: float | None = None
    comment: str | None = None


class ApprovalGate(Protocol):
    def request(self, req: ApprovalRequest) -> ApprovalRequest: ...
    def get(self, approval_id: str) -> ApprovalRequest: ...


class AutoDecision:
    """For tests and sandboxes only: decides every request immediately."""

    def __init__(self, decision: str = APPROVED, decided_by: str = "auto-policy"):
        self.decision = decision
        self.decided_by = decided_by
        self.requests: dict[str, ApprovalRequest] = {}

    def request(self, req: ApprovalRequest) -> ApprovalRequest:
        req.status, req.decided_by, req.decided_at = self.decision, self.decided_by, time.time()
        self.requests[req.id] = req
        return req

    def get(self, approval_id: str) -> ApprovalRequest:
        return self.requests[approval_id]


class CallbackApproval:
    """Synchronous decision, e.g. an attended tray-app prompt."""

    def __init__(self, decide: Callable[[ApprovalRequest], tuple[bool, str, str | None]]):
        self.decide = decide
        self.requests: dict[str, ApprovalRequest] = {}

    def request(self, req: ApprovalRequest) -> ApprovalRequest:
        ok, who, comment = self.decide(req)
        req.status, req.decided_by, req.decided_at, req.comment = (APPROVED if ok else DENIED), who, time.time(), comment
        self.requests[req.id] = req
        return req

    def get(self, approval_id: str) -> ApprovalRequest:
        return self.requests[approval_id]


class InboxApproval:
    """Asynchronous approvals: requests stay pending until a human decides."""

    def __init__(self, path: Path | None = None, notify: Callable[[str, dict], None] | None = None):
        self.path = Path(path) if path else None
        self.notify = notify or (lambda e, d: None)
        self._lock = threading.Lock()
        self._items: dict[str, ApprovalRequest] = {}
        if self.path and self.path.exists():
            for d in json.loads(self.path.read_text()):
                self._items[d["id"]] = ApprovalRequest(**d)

    def _save(self):
        if self.path:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            self.path.write_text(json.dumps([to_jsonable(asdict(r)) for r in self._items.values()], indent=2))

    def request(self, req: ApprovalRequest) -> ApprovalRequest:
        with self._lock:
            self._items[req.id] = req
            self._save()
        self.notify("task.assigned", {"approvalId": req.id, "tool": req.tool, "agent": req.agent_id})
        return req

    def get(self, approval_id: str) -> ApprovalRequest:
        if approval_id not in self._items:
            raise OoaError(f"Approval '{approval_id}' not found", code="APPROVAL.NOT_FOUND")
        return self._items[approval_id]

    def pending(self) -> list[ApprovalRequest]:
        return [r for r in self._items.values() if r.status == PENDING]

    def decide(self, approval_id: str, approve: bool, decided_by: str, comment: str | None = None,
               requester_cannot_approve: bool = True) -> ApprovalRequest:
        with self._lock:
            req = self.get(approval_id)
            if req.status != PENDING:
                raise OoaError(f"Approval '{approval_id}' is already {req.status}", code="APPROVAL.DECIDED")
            if requester_cannot_approve and decided_by == req.requested_for:
                raise OoaError("The user who started the agent cannot approve its sensitive actions",
                               code="APPROVAL.SOD", suggested_fix="Ask another reviewer (separation of duties).")
            req.status = APPROVED if approve else DENIED
            req.decided_by, req.decided_at, req.comment = decided_by, time.time(), comment
            self._save()
            return req
