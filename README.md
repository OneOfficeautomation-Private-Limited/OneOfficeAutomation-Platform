# ONEOFFICEAUTOMATION

The Java 21 production platform lives in `platform/` (see its README). The Python code in `reference/` is the
behavioural specification it must match.

## Python reference implementation

Contracts and the Python reference implementation of the ONEOFFICEAUTOMATION platform: the OOA Flow
format, Package SDK, Runner, AI Gateway and agent runtime. Built from the Product Specification v1.1
and Technical Architecture (18 Oct 2026). Architecture section 1 makes this Python slice the behavioural
reference; the Java 21 production Runner must pass the same conformance suite.

## Layout (architecture section 15)

```
contracts/flow-schema/   OOA Flow v1 JSON Schema (the shared contract)
platform/                Java 21 production platform (Gradle): SDK, Runner, packages, adapters, CLI
contracts/conformance/   language-neutral Runner test cases
reference/ooa/sdk/       Package SDK: decorators, manifests, signing, types, errors
reference/ooa/runner/    Runner: expressions, validation, interpreter, events, core package
reference/ooa/packages/  first-party packages (string, json, datatable, file, excel, rest, genai, agents)
reference/ooa/ai/        AI Gateway, PII guard, budgets, governance log, provider adapters
reference/ooa/agents/    agent runtime, tools from bots, approvals, MCP server
reference/ooa/governance.py  audit chain, RBAC with scopes, versioned repository
examples/refund/         spec journey 9.6: refund agent with bots as tools
examples/excel/          invoice summary TaskBot
docs/                    llms.txt and requirements traceability
```

## Quick start

```bash
cd reference && pip install -e ".[test]" && python -m pytest -q
```

Run the refund agent journey (spec 9.6) with the deterministic scripted model:

```bash
cd examples/refund
export OOA_MODELS=models.json
ooa validate bots/*.json refund_agent.flow.json
ooa publish bots/*.json refund_agent.flow.json -m "refund v1"
ooa --user ops.anna --roles "Automation Runner,AI Admin" agent run refund_agent \
    -i "customer_email=Order ORD-10042 arrived cracked. Please refund."
ooa approvals list
ooa --user fin.lead approvals approve <approval-id> --comment "Checked"
ooa --user ops.anna agent resume <agent-run-id>
ooa audit verify
```

To use a real model, point `OOA_MODELS` at `models.anthropic.json` (needs `ANTHROPIC_API_KEY`) or
`models.litellm.json` (needs `OOA_LITELLM_URL`). State lives in `.ooa/` (repository, audit chain,
AI governance log, approvals, agent checkpoints).

## Enterprise controls in this slice

Signed packages with publisher allow-lists; secrets as SecureString that never reach logs, events,
text variables or models; schema validation before any run; deterministic plan hashes; typed errors with
suggested fixes; agent tool allow-lists, argument validation, step and cost ceilings; human approval with
separation of duties; PII masking on every model call; role-based model access and team budgets; a
hash-chained audit trail; permission scopes enforced for people, agents and MCP clients alike.

## Not in this slice

This Python slice is the reference implementation. The product itself is the Java platform under `platform/`
(One Agent Studio, One Command Center, One Runner, the recorder and UI automation, SQL Server and PostgreSQL, single
sign-on, external vaults, the task inbox). Still open there: voice, business KPI dashboards and the agent marketplace.
See `docs/TRACEABILITY.md` (the Java table) for status by requirement ID.
