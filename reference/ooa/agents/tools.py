"""Published automations as tools (architecture s9 'Agents and MCP').

A bot's input variables become the tool's input schema and its output variables
become the tool result, so any checked-in TaskBot or ApiTask can be called by an
ONEOFFICEAUTOMATION agent or an external MCP client with the same contract.
Credential inputs are never exposed to models: a model can never see or supply
a secret."""
from __future__ import annotations

import re
from dataclasses import dataclass

from ..sdk.errors import OoaError
from ..sdk.types import json_schema_for

TOOL_KINDS = {"TaskBot", "ApiTask"}


def tool_name(automation_id: str) -> str:
    return re.sub(r"[^A-Za-z0-9_-]", "_", automation_id)[:64]


@dataclass
class ToolSpec:
    name: str
    automation_id: str
    version: int | str
    description: str
    input_schema: dict
    output_schema: dict
    sensitive: bool
    flow: dict

    def for_model(self) -> dict:
        return {"name": self.name, "description": self.description, "input_schema": self.input_schema}

    def to_mcp(self) -> dict:
        return {"name": self.name, "title": self.flow.get("name", self.name), "description": self.description,
                "inputSchema": self.input_schema, "outputSchema": self.output_schema,
                "annotations": {"destructiveHint": self.sensitive, "readOnlyHint": False,
                                "x-ooa-automation": self.automation_id, "x-ooa-version": self.flow.get("version"),
                                "x-ooa-requires-approval": self.sensitive}}


def build_tool(flow: dict, *, sensitive: bool = False, description: str | None = None,
               version: int | str = "production") -> ToolSpec:
    if flow["kind"] not in TOOL_KINDS:
        raise OoaError(f"'{flow['id']}' is a {flow['kind']}; only TaskBot and ApiTask can be tools",
                       code="AGENT.BAD_TOOL")
    desc = description or flow.get("description")
    if not desc:
        raise OoaError(f"Automation '{flow['id']}' has no description", code="AGENT.TOOL_UNDOCUMENTED",
                       suggested_fix="Add a description: the model reads it to decide when to call the tool.")
    props, required = {}, []
    for v in flow.get("variables", []):
        if v.get("direction") != "input":
            continue
        if v["type"] == "Credential":
            if v.get("required"):
                raise OoaError(f"Tool '{flow['id']}' needs Credential input '{v['name']}' that a model cannot supply",
                               code="AGENT.TOOL_NEEDS_SECRET",
                               suggested_fix="Read the credential inside the bot from a locker instead of an input.")
            continue
        s = dict(json_schema_for(v["type"]))
        if v.get("description"):
            s["description"] = v["description"]
        if v.get("enum"):
            s["enum"] = v["enum"]
        props[v["name"]] = s
        if v.get("required") and "default" not in v:
            required.append(v["name"])
    outputs = {v["name"]: {**json_schema_for(v["type"]), **({"description": v["description"]} if v.get("description") else {})}
               for v in flow.get("variables", []) if v.get("direction") == "output"}
    if sensitive:
        desc = desc.rstrip(".") + ". Requires human approval before it runs; a reviewer may deny it."
    return ToolSpec(
        name=tool_name(flow["id"]), automation_id=flow["id"], version=version, description=desc,
        input_schema={"type": "object", "properties": props, "required": required, "additionalProperties": False},
        output_schema={"type": "object", "properties": outputs}, sensitive=sensitive, flow=flow)
