"""Small builder for OOA agent files (flow.v1 JSON) used by the real-world test generator.

Every agent built with it follows the same shape, so a person watching a run can follow it:
  * steps are grouped in named Step blocks ("1. Start", "2. Prepare", ...), each opened by a Comment that says
    what the block does and why;
  * every leaf step has a description (shown as the step's caption in OneAgentStudio);
  * when the agent's `watch` input is on, message boxes that close by themselves say what is happening, and a short
    pause follows each record so the screen can be followed.
"""
from __future__ import annotations

import json
from contextlib import contextmanager

SCHEMA = "https://schemas.oneofficeautomation.com/flow/v1.json"


class Flow:
    def __init__(self, flow_id: str, name: str, description: str, kind: str = "TaskBot"):
        self.id = flow_id
        self.name = name
        self.description = description
        self.kind = kind
        self.variables: list[dict] = []
        self.steps: list[dict] = []
        self._stack: list[list[dict]] = [self.steps]
        self._n = 0
        self.tags: list[str] = []
        self.on_error: dict | None = None

    # ---------- variables ----------
    def var(self, name, type_, direction="local", default=None, description=None, required=False):
        if any(v["name"] == name for v in self.variables):
            return
        v = {"name": name, "type": type_, "direction": direction}
        if default is not None:
            v["default"] = default
        if required:
            v["required"] = True
        if description:
            v["description"] = description
        self.variables.append(v)

    def has_var(self, name):
        return any(v["name"] == name for v in self.variables)

    # ---------- steps ----------
    def _id(self):
        self._n += 1
        return f"s{self._n}"

    def _add(self, step):
        self._stack[-1].append(step)
        return step

    def act(self, action, desc=None, out=None, retry=None, cont=False, timeout=None, **args):
        """A leaf step. Keyword arguments become the step's args (None values are left out)."""
        s = {"id": self._id(), "action": action, "args": {k: v for k, v in args.items() if v is not None}}
        if out:
            s["out"] = out
        if retry:
            s["retry"] = retry
        if cont:
            s["continueOnError"] = True
        if timeout:
            s["timeoutSeconds"] = timeout
        if desc:
            s["description"] = desc
        return self._add(s)

    def set(self, variable, value, desc=None):
        return self.act("core/set", desc=desc, variable=variable, value=value)

    def expr(self, variable, expression, desc=None):
        return self.set(variable, {"expr": expression}, desc=desc)

    def comment(self, text):
        return self.act("core/comment", text=text)

    def log(self, message, level="info"):
        return self.act("core/log", desc="Write to the run log", message=message, level=level)

    @contextmanager
    def block(self, action, desc=None, **args):
        s = {"id": self._id(), "action": action, "args": {k: v for k, v in args.items() if v is not None}, "children": []}
        if desc:
            s["description"] = desc
        self._add(s)
        self._stack.append(s["children"])
        try:
            yield s
        finally:
            self._stack.pop()

    @contextmanager
    def step(self, title, why=None):
        """A named group; `why` becomes the first Comment inside it."""
        with self.block("core/step", desc=title, title=title) as s:
            if why:
                self.comment(why)
            yield s

    @contextmanager
    def if_(self, condition, desc=None):
        with self.block("core/if", desc=desc, condition=condition) as s:
            yield s

    @contextmanager
    def else_(self, if_step):
        if_step["else"] = []
        self._stack.append(if_step["else"])
        try:
            yield
        finally:
            self._stack.pop()

    @contextmanager
    def elif_(self, if_step, condition):
        branch = {"condition": condition, "children": []}
        if_step.setdefault("elseIf", []).append(branch)
        self._stack.append(branch["children"])
        try:
            yield
        finally:
            self._stack.pop()

    @contextmanager
    def loop(self, desc=None, **args):
        with self.block("core/loop", desc=desc, **args) as s:
            yield s

    @contextmanager
    def try_(self, desc=None):
        with self.block("core/try", desc=desc) as s:
            yield s

    @contextmanager
    def catch(self, try_step, codes=("*",), as_=None, message_to=None, screenshot=False):
        c = {"codes": list(codes), "children": []}
        if as_:
            c["as"] = as_
        if message_to:
            c["message_to"] = message_to
        if screenshot:
            c["screenshot"] = True
        try_step.setdefault("catch", []).append(c)
        self._stack.append(c["children"])
        try:
            yield
        finally:
            self._stack.pop()

    @contextmanager
    def finally_(self, try_step):
        try_step["finally"] = []
        self._stack.append(try_step["finally"])
        try:
            yield
        finally:
            self._stack.pop()

    @contextmanager
    def switch(self, value, desc=None, match="equals", ignore_case=True):
        with self.block("core/switch", desc=desc, value=value, match=match, ignore_case=ignore_case) as s:
            s["cases"] = []
            del s["children"]
            yield s

    @contextmanager
    def case(self, switch_step, *values):
        c = {"values": list(values), "children": []}
        switch_step["cases"].append(c)
        self._stack.append(c["children"])
        try:
            yield
        finally:
            self._stack.pop()

    @contextmanager
    def default(self, switch_step):
        switch_step["default"] = []
        self._stack.append(switch_step["default"])
        try:
            yield
        finally:
            self._stack.pop()

    @contextmanager
    def parallel(self, max_concurrency, desc=None):
        with self.block("core/parallel", desc=desc, max_concurrency=max_concurrency) as s:
            yield s

    # ---------- watching helpers ----------
    def box(self, message, title=None, seconds=3, icon="information"):
        """A message box that closes by itself, shown only when the watch input is on."""
        with self.if_("$watch$", desc="Show what is happening (watch is on)"):
            self.act("ooa.desktop/message_box", desc="Message box that closes by itself",
                     title=title or self.name, message=message, timeout_seconds=seconds, icon=icon,
                     always_on_top=True)

    def pause(self, seconds="$pause_seconds$"):
        with self.if_("$watch$", desc="Pause so the screen can be followed (watch is on)"):
            self.act("core/delay", desc="Short pause", seconds=seconds)

    # ---------- output ----------
    def to_dict(self):
        d = {
            "$schema": SCHEMA,
            "kind": self.kind,
            "id": self.id,
            "name": self.name,
            "description": self.description,
        }
        if self.tags:
            d["tags"] = self.tags
        d["variables"] = self.variables
        d["steps"] = self.steps
        if self.on_error:
            d["onError"] = self.on_error
        return d

    def to_json(self):
        return json.dumps(self.to_dict(), indent=2, ensure_ascii=False) + "\n"
