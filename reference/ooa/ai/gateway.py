"""AI Gateway (architecture s9). Every LLM call from Generative AI commands,
Document Intelligence, agents and the co-pilot goes through here, so model
access (FR-AI-002), PII masking (FR-AI-003), budgets and the AI governance log
(FR-AI-004) are enforced in one place. The MVP sits in front of a LiteLLM proxy;
this reference implements the same contract with pluggable provider adapters."""
from __future__ import annotations

import hashlib
import json
import re
import threading
import time
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable, Protocol

import jsonschema

from ..sdk.errors import BudgetExceeded, OoaError, PolicyDenied


# ---------- provider-neutral message model ----------
@dataclass
class ToolCall:
    id: str
    name: str
    arguments: dict


@dataclass
class ProviderResponse:
    content: str | None
    tool_calls: list[ToolCall]
    input_tokens: int
    output_tokens: int
    stop_reason: str = "end"


class ModelProvider(Protocol):
    def complete(self, *, model: str, messages: list[dict], tools: list[dict], temperature: float,
                 response_schema: dict | None) -> ProviderResponse: ...


@dataclass
class ModelConnection:
    name: str
    provider: ModelProvider
    model: str
    input_usd_per_mtok: float = 0.0
    output_usd_per_mtok: float = 0.0
    allowed_roles: set[str] | None = None  # None = every role with 'ai.use'
    region: str = "default"
    description: str = ""


@dataclass
class Caller:
    """Who is calling, for access control, budgets and governance."""
    tenant: str = "default"
    user: str = "system"
    roles: tuple[str, ...] = ("Automation Developer",)
    team: str = "default"
    automation_id: str | None = None
    run_id: str | None = None
    purpose: str = "genai"  # genai | agent | docintel | copilot


@dataclass
class ChatResponse:
    content: str | None
    tool_calls: list[ToolCall]
    input_tokens: int
    output_tokens: int
    cost_usd: float
    model: str
    connection: str
    latency_ms: int
    call_id: str
    masked: dict[str, int] = field(default_factory=dict)
    parsed: Any = None
    warnings: list[str] = field(default_factory=list)


# ---------- PII guard ----------
def _luhn(digits: str) -> bool:
    total, alt = 0, False
    for d in reversed(digits):
        n = int(d)
        if alt:
            n = n * 2 - 9 if n > 4 else n * 2
        total += n
        alt = not alt
    return total % 10 == 0


class PiiGuard:
    """Regex detectors with deterministic placeholders, so the same value maps to
    the same token across every turn of an agent conversation. Microsoft Presidio
    is the production adapter behind the same mask/unmask interface."""

    DETECTORS: dict[str, re.Pattern] = {
        "EMAIL": re.compile(r"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b"),
        # one contiguous 13-19 digit number, or 4-digit groups; never a run of unrelated tokens
        "CREDIT_CARD": re.compile(r"(?<![\w.-])(?:\d{4}(?:[ -]\d{4}){2}[ -]\d{1,7}|\d{4}[ -]\d{6}[ -]\d{4,5}|\d{13,19})(?![\w-])(?!\.\d)"),
        "IBAN": re.compile(r"\b[A-Z]{2}\d{2}[A-Z0-9]{11,30}\b"),
        "US_SSN": re.compile(r"\b\d{3}-\d{2}-\d{4}\b"),
        # a phone must not be a slice of a longer digit run (that would leak the remainder)
        "PHONE": re.compile(r"(?<!\w)(?<!\d[ .-])\+?\d{1,3}[ .-]?\(?\d{2,4}\)?[ .-]?\d{3,4}[ .-]?\d{3,4}(?!\w)(?![ .-]?\d)"),
        "IP_ADDRESS": re.compile(r"\b(?:\d{1,3}\.){3}\d{1,3}\b"),
    }

    def __init__(self, entities: set[str] | None = None, salt: str = "ooa"):
        self.entities = set(entities) if entities is not None else set(self.DETECTORS)
        self.salt = salt

    def _token(self, entity: str, value: str) -> str:
        return f"<{entity}_{hashlib.sha256((self.salt + value).encode()).hexdigest()[:6]}>"

    def mask(self, text: str) -> tuple[str, dict[str, str], dict[str, int]]:
        mapping: dict[str, str] = {}
        counts: dict[str, int] = {}
        for entity in ("EMAIL", "IBAN", "CREDIT_CARD", "US_SSN", "IP_ADDRESS", "PHONE"):
            if entity not in self.entities:
                continue

            def rep(m, entity=entity):
                value = m.group(0)
                if entity == "CREDIT_CARD":
                    digits = re.sub(r"\D", "", value)
                    if not (13 <= len(digits) <= 19 and _luhn(digits)):
                        return value
                tok = self._token(entity, value)
                mapping[tok] = value
                counts[entity] = counts.get(entity, 0) + 1
                return tok
            text = self.DETECTORS[entity].sub(rep, text)
        return text, mapping, counts

    @staticmethod
    def unmask(value: Any, mapping: dict[str, str]) -> Any:
        if not mapping:
            return value
        if isinstance(value, str):
            for tok, orig in mapping.items():
                value = value.replace(tok, orig)
            return value
        if isinstance(value, dict):
            return {k: PiiGuard.unmask(v, mapping) for k, v in value.items()}
        if isinstance(value, list):
            return [PiiGuard.unmask(v, mapping) for v in value]
        return value


# ---------- budgets ----------
class BudgetManager:
    """Per-team USD budgets with 80% and 100% alerts (spec section 12)."""

    def __init__(self, notify: Callable[[str, dict], None] | None = None):
        self.limits: dict[str, float] = {}
        self.spent: dict[str, float] = {}
        self._alerted: set[tuple[str, int]] = set()
        self.notify = notify or (lambda event, data: None)
        self._lock = threading.Lock()

    def set_limit(self, team: str, usd: float) -> None:
        self.limits[team] = usd

    def check(self, team: str) -> None:
        limit = self.limits.get(team)
        if limit is not None and self.spent.get(team, 0.0) >= limit:
            raise BudgetExceeded(f"AI budget for team '{team}' is used up (${limit:.2f})",
                                 suggested_fix="Ask an AI admin to raise the team budget in AI > Model connections.")

    def consume(self, team: str, usd: float) -> list[str]:
        with self._lock:
            self.spent[team] = self.spent.get(team, 0.0) + usd
            limit = self.limits.get(team)
            warnings = []
            if limit:
                pct = self.spent[team] / limit * 100
                for threshold in (80, 100):
                    if pct >= threshold and (team, threshold) not in self._alerted:
                        self._alerted.add((team, threshold))
                        self.notify("ai.budget", {"team": team, "percent": threshold, "spent": self.spent[team], "limit": limit})
                        warnings.append(f"AI budget for team '{team}' reached {threshold}%")
            return warnings


# ---------- governance log ----------
class GovernanceLog:
    """Every AI call: who, which automation, model, prompt hash, tokens, cost,
    latency and masking applied. Raw prompts are not stored by default."""

    def __init__(self, path: Path | None = None):
        self.path = Path(path) if path else None
        self.records: list[dict] = []
        self._lock = threading.Lock()

    def record(self, rec: dict) -> None:
        with self._lock:
            self.records.append(rec)
            if self.path:
                self.path.parent.mkdir(parents=True, exist_ok=True)
                with open(self.path, "a", encoding="utf-8") as fh:
                    fh.write(json.dumps(rec, default=str) + "\n")


class AIGateway:
    def __init__(self, *, pii_guard: PiiGuard | None = None, budgets: BudgetManager | None = None,
                 governance: GovernanceLog | None = None, unmask_responses: bool = True,
                 local_only: bool = False):
        self.connections: dict[str, ModelConnection] = {}
        self.default_connection: str | None = None
        self.pii = pii_guard if pii_guard is not None else PiiGuard()
        self.budgets = budgets or BudgetManager()
        self.governance = governance or GovernanceLog()
        self.unmask_responses = unmask_responses
        self.local_only = local_only  # FR-DOC-012 style tenant switch: only self-hosted connections

    def add_connection(self, conn: ModelConnection, default: bool = False) -> None:
        self.connections[conn.name] = conn
        if default or self.default_connection is None:
            self.default_connection = conn.name

    def _connection(self, name: str | None, caller: Caller) -> ModelConnection:
        name = name or self.default_connection
        conn = self.connections.get(name or "")
        if conn is None:
            raise OoaError(f"Model connection '{name}' is not configured", code="AI.NO_CONNECTION",
                           suggested_fix="An AI admin can add it under AI > Model connections.")
        if conn.allowed_roles is not None and not (set(caller.roles) & conn.allowed_roles):
            raise PolicyDenied(f"Your roles may not use model connection '{conn.name}'", code="AI.MODEL_DENIED",
                               suggested_fix="Ask an AI admin to add your role to the connection's allow-list.")
        if self.local_only and not getattr(conn.provider, "self_hosted", False):
            raise PolicyDenied(f"Tenant is in local-only mode; '{conn.name}' is an external provider",
                               code="AI.LOCAL_ONLY")
        return conn

    def chat(self, messages: list[dict], *, caller: Caller, connection: str | None = None,
             tools: list[dict] | None = None, response_schema: dict | None = None,
             temperature: float = 0.0) -> ChatResponse:
        conn = self._connection(connection, caller)
        self.budgets.check(caller.team)
        call_id = f"llm_{uuid.uuid4().hex[:12]}"

        # mask everything except system instructions (authored by admins, not end-user data)
        mapping: dict[str, str] = {}
        counts: dict[str, int] = {}
        masked_msgs = []
        for m in messages:
            m2 = dict(m)
            if m["role"] != "system" and isinstance(m.get("content"), str) and self.pii.entities:
                m2["content"], mp, c = self.pii.mask(m["content"])
                mapping.update(mp)
                for k, v in c.items():
                    counts[k] = counts.get(k, 0) + v
            if m.get("tool_calls"):
                m2["tool_calls"] = []
                for tc in m["tool_calls"]:
                    masked_args, mp, _ = self.pii.mask(json.dumps(tc["arguments"]))
                    mapping.update(mp)
                    m2["tool_calls"].append({**tc, "arguments": json.loads(masked_args)})
            masked_msgs.append(m2)

        prompt_hash = hashlib.sha256(json.dumps(masked_msgs, sort_keys=True, default=str).encode()).hexdigest()
        t0 = time.monotonic()
        status, err = "ok", None
        try:
            resp = conn.provider.complete(model=conn.model, messages=masked_msgs, tools=tools or [],
                                          temperature=temperature, response_schema=response_schema)
        except OoaError as e:
            status, err = "error", e
            resp = ProviderResponse(None, [], 0, 0, "error")
        except Exception as e:
            status, err = "error", OoaError(f"Model provider error: {e}", code="AI.PROVIDER_ERROR", retryable=True)
            resp = ProviderResponse(None, [], 0, 0, "error")
        latency = int((time.monotonic() - t0) * 1000)
        cost = (resp.input_tokens * conn.input_usd_per_mtok + resp.output_tokens * conn.output_usd_per_mtok) / 1e6
        warnings = self.budgets.consume(caller.team, cost) if cost else []

        content, tool_calls = resp.content, resp.tool_calls
        if self.unmask_responses:
            content = PiiGuard.unmask(content, mapping)
            tool_calls = [ToolCall(tc.id, tc.name, PiiGuard.unmask(tc.arguments, mapping)) for tc in tool_calls]

        parsed = None
        if status == "ok" and response_schema is not None and not tool_calls:
            try:
                parsed = json.loads(_strip_fences(content or ""))
                jsonschema.validate(parsed, response_schema)
            except (ValueError, jsonschema.ValidationError) as e:
                status, err = "error", OoaError(f"Model output does not match the required schema: {str(e)[:200]}",
                                                code="AI.SCHEMA_MISMATCH", retryable=True,
                                                suggested_fix="Retry, simplify the schema or use a stronger model.")

        self.governance.record({
            "callId": call_id, "ts": time.time(), "tenant": caller.tenant, "user": caller.user, "team": caller.team,
            "automationId": caller.automation_id, "runId": caller.run_id, "purpose": caller.purpose,
            "connection": conn.name, "model": conn.model, "region": conn.region, "promptHash": prompt_hash,
            "inputTokens": resp.input_tokens, "outputTokens": resp.output_tokens, "costUsd": round(cost, 6),
            "latencyMs": latency, "masked": counts, "toolCalls": [tc.name for tc in tool_calls],
            "status": status, "errorCode": err.code if err else None,
        })
        if err:
            raise err
        return ChatResponse(content, tool_calls, resp.input_tokens, resp.output_tokens, cost, conn.model,
                            conn.name, latency, call_id, counts, parsed, warnings)


def _strip_fences(s: str) -> str:
    s = s.strip()
    if s.startswith("```"):
        s = re.sub(r"^```[a-zA-Z]*\n?", "", s)
        s = re.sub(r"\n?```$", "", s)
    return s.strip()
