"""Structured run events (architecture s6 'Stream'). The Agent forwards these to
the Control Center: live Runs view, logs search and Insights."""
from __future__ import annotations

import json
import sys
import threading
import time
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Protocol

from ..sdk.types import to_jsonable


@dataclass
class RunEvent:
    run_id: str
    seq: int
    type: str  # run.started | step.started | step.finished | step.failed | step.retry | variable.set | log | run.finished
    ts: float = field(default_factory=time.time)
    step_id: str | None = None
    data: dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> dict:
        d = asdict(self)
        d["data"] = to_jsonable(self.data)
        return d


class EventSink(Protocol):
    def emit(self, event: RunEvent) -> None: ...


class ListSink:
    def __init__(self):
        self.events: list[RunEvent] = []
        self._lock = threading.Lock()

    def emit(self, event: RunEvent) -> None:
        with self._lock:
            self.events.append(event)

    def of_type(self, t: str) -> list[RunEvent]:
        return [e for e in self.events if e.type == t]


class JsonlSink:
    def __init__(self, path: Path | None = None):
        self._fh = open(path, "a", encoding="utf-8") if path else sys.stdout
        self._lock = threading.Lock()

    def emit(self, event: RunEvent) -> None:
        with self._lock:
            self._fh.write(json.dumps(event.to_dict()) + "\n")
            self._fh.flush()


class ConsoleSink:
    """Human-readable progress for the CLI."""
    ICON = {"step.started": "▶", "step.finished": "✓", "step.failed": "✗", "step.retry": "↻",
            "log": "·", "run.started": "■", "run.finished": "■"}

    def __init__(self, verbose: bool = False, stream=None):
        self.verbose = verbose
        self.stream = stream or sys.stderr

    def emit(self, e: RunEvent) -> None:
        if e.type == "variable.set" and not self.verbose:
            return
        if e.type == "step.started" and not self.verbose:
            return
        icon = self.ICON.get(e.type, " ")
        detail = ""
        if e.type == "step.finished":
            detail = f"{e.data.get('action')} ({e.data.get('durationMs')} ms)"
        elif e.type == "step.failed":
            detail = f"{e.data.get('code')}: {e.data.get('message')}"
        elif e.type == "log":
            detail = e.data.get("message", "")
        elif e.type == "run.finished":
            detail = f"{e.data.get('status')} in {e.data.get('durationMs')} ms"
        elif e.type == "run.started":
            detail = f"{e.data.get('automation')} plan={e.data.get('planHash', '')[:12]}"
        else:
            detail = json.dumps(to_jsonable(e.data))[:160]
        step = f"[{e.step_id}] " if e.step_id else ""
        print(f"  {icon} {step}{detail}", file=self.stream)


class FanoutSink:
    def __init__(self, *sinks: EventSink):
        self.sinks = [s for s in sinks if s is not None]

    def emit(self, event: RunEvent) -> None:
        for s in self.sinks:
            s.emit(event)
