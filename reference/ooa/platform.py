"""Platform: wires the reference services together the way ooa-core does in
production. With `home` set, state persists on disk (repository, audit chain,
AI governance log, approvals, agent checkpoints) so CLI invocations share it."""
from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Any

from .agents.approvals import ApprovalGate, InboxApproval
from .agents.runtime import AgentRuntime
from .ai.gateway import AIGateway, GovernanceLog, ModelConnection, PiiGuard
from .ai.providers import AnthropicProvider, OpenAICompatibleProvider, ScriptedProvider
from .governance import AuditLog, Policy, Principal, Repository
from .packages import builtin_registry
from .runner.events import EventSink, ListSink
from .runner.interpreter import RunResult, Runner
from .sdk.registry import PackageRegistry
from .vault import EnvVault, InMemoryVault


class Platform:
    def __init__(self, home: Path | None = None, *, gateway: AIGateway | None = None,
                 registry: PackageRegistry | None = None, approvals: ApprovalGate | None = None,
                 vault=None, sink: EventSink | None = None, work_dir: Path | None = None):
        self.home = Path(home) if home else None
        p = (lambda name: self.home / name) if self.home else (lambda name: None)
        self.audit = AuditLog(p("audit.jsonl"))
        self.registry = registry or builtin_registry()
        self.repository = Repository(p("repository"), self.audit)
        self.policy = Policy(self.audit)
        self.vault = vault or (EnvVault() if self.home else InMemoryVault())
        self.gateway = gateway or AIGateway(pii_guard=PiiGuard(), governance=GovernanceLog(p("ai_governance.jsonl")))
        self.approvals = approvals or InboxApproval(p("approvals.json"))
        self.sink = sink or ListSink()
        self.work_dir = Path(work_dir) if work_dir else Path.cwd()
        self.agents = AgentRuntime(gateway=self.gateway, registry=self.registry, repository=self.repository,
                                   approvals=self.approvals, policy=self.policy, audit=self.audit,
                                   services=self._base_services(), checkpoint_dir=p("agent_runs"),
                                   sink=self.sink, work_dir=self.work_dir)

    def _base_services(self) -> dict[str, Any]:
        return {"repository": self.repository, "vault": self.vault, "ai_gateway": self.gateway, "audit": self.audit}

    def services_for(self, principal: Principal, automation_id: str | None = None) -> dict[str, Any]:
        return {**self._base_services(), "agent_runtime": self.agents, "principal": principal,
                "principal_user": principal.user, "principal_roles": principal.roles,
                "principal_team": principal.team, "automation_id": automation_id}

    def run(self, flow: dict | str, inputs: dict | None, principal: Principal, **kw) -> RunResult:
        flow = self.repository.get(flow) if isinstance(flow, str) else flow
        self.policy.check(principal, "automation.run", "automation", flow["id"])
        runner = Runner(self.registry, services=self.services_for(principal, flow["id"]), sink=self.sink,
                        work_dir=self.work_dir, tenant_id=principal.tenant, principal=principal.user)
        res = runner.run(flow, inputs, **kw)
        self.audit.record(principal.user, "run.finished", f"automation:{flow['id']}",
                          result="success" if res.ok else res.status,
                          details={"runId": res.run_id, "planHash": res.plan_hash, "durationMs": res.duration_ms,
                                   "errorCode": (res.error or {}).get("code")}, tenant=principal.tenant)
        return res

    def configure_models_from_file(self, path: Path) -> None:
        """Load model connections (what an AI admin sets in AI > Model connections)."""
        cfg = json.loads(Path(path).read_text())
        for c in cfg["connections"]:
            kind = c["provider"]
            if kind == "scripted":
                script = json.loads((Path(path).parent / c["script"]).read_text()) if "script" in c else c["turns"]
                provider = ScriptedProvider(script)
            elif kind == "anthropic":
                provider = AnthropicProvider(api_key_env=c.get("apiKeyEnv", "ANTHROPIC_API_KEY"))
            elif kind == "openai-compatible":
                provider = OpenAICompatibleProvider(os.path.expandvars(c["baseUrl"]), c.get("apiKeyEnv"),
                                                    self_hosted=c.get("selfHosted", False))
            else:
                raise ValueError(f"Unknown provider '{kind}'")
            self.gateway.add_connection(ModelConnection(
                c["name"], provider, c.get("model", "scripted"), c.get("inputUsdPerMtok", 0.0),
                c.get("outputUsdPerMtok", 0.0), set(c["allowedRoles"]) if c.get("allowedRoles") else None,
                c.get("region", "default")), default=c.get("default", False))
        for team, usd in (cfg.get("budgets") or {}).items():
            self.gateway.budgets.set_limit(team, usd)
