"""Model provider adapters behind the AI Gateway.

ScriptedProvider        deterministic responses for tests, demos and evaluation suites
OpenAICompatibleProvider LiteLLM proxy (the MVP gateway), vLLM self-hosted, OpenAI, Azure OpenAI
AnthropicProvider       Anthropic Messages API

Adding Bedrock, Vertex or a commercial gateway is a new adapter with the same
`complete` signature (swap-ready principle)."""
from __future__ import annotations

import json
import os
import urllib.error
import urllib.request
import uuid
from typing import Any, Callable

from ..sdk.errors import OoaError
from .gateway import ProviderResponse, ToolCall


def _estimate_tokens(obj: Any) -> int:
    return max(1, len(json.dumps(obj, default=str)) // 4)


class ScriptedProvider:
    """Returns pre-written turns. Each turn is {"content": "..."} or
    {"tool_calls": [{"name": "...", "arguments": {...}}]}, or a callable
    (messages, tools) -> turn for reactive scripts."""
    self_hosted = True

    def __init__(self, script: list[dict] | Callable[[list[dict], list[dict]], dict]):
        self.script = script
        self.calls: list[dict] = []

    def complete(self, *, model, messages, tools, temperature, response_schema) -> ProviderResponse:
        self.calls.append({"messages": messages, "tools": tools})
        if callable(self.script):
            turn = self.script(messages, tools)
        else:
            # stateless: the turn index is the number of assistant turns so far, so a
            # paused agent resumed in another process gets the right next turn
            i = sum(1 for m in messages if m["role"] == "assistant")
            if i >= len(self.script):
                raise OoaError("Scripted provider ran out of turns", code="AI.SCRIPT_EXHAUSTED")
            turn = self.script[i]
        tcs = [ToolCall(tc.get("id") or f"call_{uuid.uuid4().hex[:8]}", tc["name"], tc.get("arguments", {}))
               for tc in turn.get("tool_calls", [])]
        content = turn.get("content")
        if content is not None and not isinstance(content, str):
            content = json.dumps(content)
        return ProviderResponse(content, tcs, _estimate_tokens(messages) + _estimate_tokens(tools),
                                _estimate_tokens(turn), "tool_use" if tcs else "end")


def _post(url: str, body: dict, headers: dict, timeout: float = 120) -> dict:
    req = urllib.request.Request(url, data=json.dumps(body).encode(), method="POST",
                                 headers={"Content-Type": "application/json", **headers})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.loads(r.read())
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "replace")[:300]
        raise OoaError(f"Model API returned HTTP {e.code}: {detail}", code="AI.PROVIDER_HTTP",
                       retryable=e.code in (429, 500, 502, 503, 529)) from None
    except urllib.error.URLError as e:
        raise OoaError(f"Model API unreachable: {e.reason}", code="AI.PROVIDER_UNREACHABLE", retryable=True) from None


class OpenAICompatibleProvider:
    def __init__(self, base_url: str, api_key_env: str | None = "OOA_LLM_API_KEY", self_hosted: bool = False):
        self.base_url = base_url.rstrip("/")
        self.api_key_env = api_key_env
        self.self_hosted = self_hosted

    def complete(self, *, model, messages, tools, temperature, response_schema) -> ProviderResponse:
        msgs = []
        for m in messages:
            if m["role"] == "assistant" and m.get("tool_calls"):
                msgs.append({"role": "assistant", "content": m.get("content"),
                             "tool_calls": [{"id": tc["id"], "type": "function",
                                             "function": {"name": tc["name"], "arguments": json.dumps(tc["arguments"])}}
                                            for tc in m["tool_calls"]]})
            elif m["role"] == "tool":
                msgs.append({"role": "tool", "tool_call_id": m["tool_call_id"], "content": m["content"]})
            else:
                msgs.append({"role": m["role"], "content": m["content"]})
        body: dict = {"model": model, "messages": msgs, "temperature": temperature}
        if tools:
            body["tools"] = [{"type": "function", "function": {"name": t["name"], "description": t["description"],
                                                               "parameters": t["input_schema"]}} for t in tools]
        if response_schema and not tools:
            body["response_format"] = {"type": "json_schema",
                                       "json_schema": {"name": "result", "schema": response_schema}}
        headers = {}
        key = os.environ.get(self.api_key_env or "", "")
        if key:
            headers["Authorization"] = f"Bearer {key}"
        data = _post(f"{self.base_url}/chat/completions", body, headers)
        choice = data["choices"][0]["message"]
        tcs = [ToolCall(tc["id"], tc["function"]["name"], json.loads(tc["function"].get("arguments") or "{}"))
               for tc in choice.get("tool_calls") or []]
        usage = data.get("usage", {})
        return ProviderResponse(choice.get("content"), tcs, usage.get("prompt_tokens", 0),
                                usage.get("completion_tokens", 0), "tool_use" if tcs else "end")


class AnthropicProvider:
    self_hosted = False

    def __init__(self, api_key_env: str = "ANTHROPIC_API_KEY", base_url: str = "https://api.anthropic.com",
                 max_tokens: int = 2048):
        self.api_key_env = api_key_env
        self.base_url = base_url.rstrip("/")
        self.max_tokens = max_tokens

    def complete(self, *, model, messages, tools, temperature, response_schema) -> ProviderResponse:
        system = "\n\n".join(m["content"] for m in messages if m["role"] == "system")
        if response_schema and not tools:
            system += "\n\nRespond with JSON only, matching this JSON Schema:\n" + json.dumps(response_schema)
        out: list[dict] = []
        for m in messages:
            if m["role"] == "system":
                continue
            if m["role"] == "tool":
                block = {"type": "tool_result", "tool_use_id": m["tool_call_id"], "content": m["content"]}
                if out and out[-1]["role"] == "user" and isinstance(out[-1]["content"], list) \
                        and out[-1]["content"] and out[-1]["content"][0].get("type") == "tool_result":
                    out[-1]["content"].append(block)
                else:
                    out.append({"role": "user", "content": [block]})
            elif m["role"] == "assistant":
                blocks = [{"type": "text", "text": m["content"]}] if m.get("content") else []
                blocks += [{"type": "tool_use", "id": tc["id"], "name": tc["name"], "input": tc["arguments"]}
                           for tc in m.get("tool_calls") or []]
                out.append({"role": "assistant", "content": blocks})
            else:
                out.append({"role": "user", "content": m["content"]})
        body: dict = {"model": model, "max_tokens": self.max_tokens, "messages": out, "temperature": temperature}
        if system:
            body["system"] = system
        if tools:
            body["tools"] = [{"name": t["name"], "description": t["description"], "input_schema": t["input_schema"]}
                             for t in tools]
        key = os.environ.get(self.api_key_env)
        if not key:
            raise OoaError(f"Environment variable {self.api_key_env} is not set", code="AI.NO_API_KEY",
                           suggested_fix="Store the provider key in the vault and expose it to the gateway.")
        data = _post(f"{self.base_url}/v1/messages", body,
                     {"x-api-key": key, "anthropic-version": "2023-06-01"})
        text = "".join(b.get("text", "") for b in data.get("content", []) if b["type"] == "text") or None
        tcs = [ToolCall(b["id"], b["name"], b.get("input", {})) for b in data.get("content", []) if b["type"] == "tool_use"]
        usage = data.get("usage", {})
        return ProviderResponse(text, tcs, usage.get("input_tokens", 0), usage.get("output_tokens", 0),
                                data.get("stop_reason", "end"))
