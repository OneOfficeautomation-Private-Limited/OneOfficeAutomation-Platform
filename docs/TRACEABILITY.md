# Requirements traceability (reference implementation)

Status: **Built** = implemented and tested here; **Partial** = core behaviour built, production parts missing;
**Planned** = not in this slice. IDs are from the Product Specification v1.1.

| ID | Requirement | Status | Where | Notes |
| --- | --- | --- | --- | --- |
| FR-STU-005 | Typed variables | Built | `ooa/sdk/types.py`, `runner/interpreter.py` (Scope) | Decimal Number; Credential = SecureString |
| FR-STU-006 | Expressions | Built | `runner/expressions.py` | Sandboxed grammar, allow-listed functions; Java port uses ANTLR |
| FR-STU-007 | Validation with links to steps | Built | `runner/validate.py` | Returns all problems with stepId + suggestedFix |
| FR-STU-008 | Debugger | Partial | `Debugger.before_step` hook | Live control channel to Studio is Planned |
| FR-STU-010 | Child bots | Built | `core/run_bot` | Depth limit 10; child errors keep their code |
| FR-STU-011 | Error handling design | Built | `core/try`, step `retry`, `errorHandling` | Catch by code/prefix, finally, backoff |
| FR-STU-001..004, 009, 012..019 | Studio UI | Planned | — | React app; manifests already drive command panels |
| FR-REC-* | Recorder | Planned | — | Selector model lives in the Flow schema description |
| FR-PKG-001 | Package SDK | Built (Python) | `ooa/sdk/package.py` | Java SDK with annotations is Planned; manifest format shared |
| FR-PKG-002 | Manifest-driven UI | Built (data) | `build_manifest`, `ooa manifest` | Studio rendering Planned |
| FR-PKG-003 | Signing and verification | Built | `sdk/registry.py` | Ed25519 stands in for Sigstore/cosign |
| FR-PKG-004 | Versioning | Built | `satisfies`, `resolve`, `set_default` | ^ ~ >= exact ranges |
| FR-PKG-005 | Dependency isolation | Planned | — | Java: PF4J classloader per version |
| FR-PKG-006 | Usage index | Partial | `record_usage` | Delete-blocking Planned |
| FR-PKG-007 | Publisher allow-list | Built | `PackageRegistry.allowed_publishers` | |
| FR-DOC-* | Document Intelligence | Planned | — | Next slice; reuses AI Gateway + approvals |
| FR-GAI-001 | Prompt with schema output | Built | `ooa.genai/prompt`, `extract_to_schema` | Schema-validated output |
| FR-GAI-002 | Task actions | Partial | classify, summarize, extract_to_schema | Translate, Sentiment, Embed Planned |
| FR-GAI-003 | Prompt templates | Planned | — | |
| FR-GAI-004 | Run AI agent | Built | `ooa.agents/run_agent` | |
| FR-EXE-009 | Run isolation | Partial | Per-run scope, sessions closed, secrets wiped | Process/cgroup isolation is the Agent's job (Planned) |
| FR-EXE-010 | Remote stop | Built (engine) | `cancel_event` | Control-plane wiring Planned |
| FR-EXE-001..008, 011..014 | Agent service | Planned | — | Java service, gRPC, MSI |
| FR-CC-AUT004/005 | Check-in, versions, production version | Built | `governance.Repository` | File-backed; PostgreSQL Planned |
| FR-CC-AUT010 | Run now | Built (CLI) | `ooa run` | |
| FR-CC-RES003/004 | Credentials and lockers | Partial | `vault.py` | Consumer check per locker; OpenBao adapter Planned |
| FR-CC-ADM004 | Roles with scopes | Built | `governance.Policy` | OPA/Rego in production |
| FR-CC-ADM007 | Tamper-evident audit | Built | `governance.AuditLog` | Hash chain verified by `ooa audit verify` |
| FR-AI-001 | Model connections | Built | `ai/gateway.py`, `ai/providers.py` | Anthropic, OpenAI-compatible (LiteLLM/vLLM), scripted |
| FR-AI-002 | Model access control and budgets | Built | `allowed_roles`, `BudgetManager` | 80%/100% alerts; rate limits Planned |
| FR-AI-003 | PII masking | Built | `PiiGuard` | Regex adapter; Presidio adapter Planned |
| FR-AI-004 | AI governance log | Built | `GovernanceLog` | No raw prompts stored |
| FR-AI-006 | AI agents | Built | `agents/runtime.py` | Tool allow-list, schema checks, step and cost limits, checkpoints |
| FR-AI-007 | Human approval for tools | Built | `agents/approvals.py` | Separation of duties; Assist inbox UI Planned |
| FR-AI-009 | MCP server | Built | `agents/mcp_server.py` | stdio; OAuth HTTP transport Planned |
| FR-AI-011 | LLM-readable docs | Built | `docs/llms.txt` | |
| FR-AI-012 | Structured errors | Built | `sdk/errors.py` | |
| FR-API-*, FR-AST-*, FR-VOX-*, FR-INS-* | API, Assist, Voice, Insights | Planned | — | |
| NFR-007 | Secrets never shown | Built | SecureString, log redaction | Tested in `test_runner.py`, conformance 012 |
| NFR-008 | Signed supply chain | Partial | Package signing | Images/agents/SBOM Planned |
| NFR-011 | Immutable, hash-chained audit | Built | `AuditLog` | Retention/SIEM Planned |

## Java platform status after Part 4 (20 Oct 2026)

The rows above trace the Python reference implementation. The Java platform under `platform/` now covers these
requirements; where a row above says Planned or Partial, this table is the current status.

| ID | Requirement | Status | Where (Java) | Notes |
| --- | --- | --- | --- | --- |
| FR-STU-008 | Debugger | Built | `Runner.Debugger`, `cli/StudioDebugger` | Breakpoints (also in child agents), step into/over/out, call stack, variable edits |
| FR-STU-001..019 | Studio UI | Built | `cli/src/main/resources/studio` | List and flow views, Quick add, AI assistant, designers for forms, workflows, documents, Prompt Skills |
| FR-PKG-006 | Usage index | Built | `Platform.toolkits`, `toolkitUsers`, `setToolkitEnabled` | "Used by", switch off blocks check-in and runs |
| FR-GAI-003 | Prompt templates | Built | `AiSkillsPackage` (Prompt Skill) | Skills can name a Model Connection |
| FR-EXE-001..014 | Runner service | Built | `agent` module (OneRunner) | Process per run, remote stop and pause, runner pools, priority |
| FR-CC-AUT004/005 | Check-in, versions, live production version | Built | `ControlRoom.checkin` | Agents, workflows and forms; H2 or PostgreSQL |
| FR-CC-RES003/004 | Credentials and vault folders | Built | `ControlRoom`, `ExternalVaults` | HashiCorp Vault, Azure Key Vault, AWS Secrets Manager, CyberArk CCP |
| FR-CC-ADM004 | Roles with scopes | Built | `Rbac`, `Platform` custom roles | Fine-grained permissions per custom role |
| FR-AI-001 | Model connections | Built | `ModelConnections`, Command Center connections | Keys only as vault or environment references |
| FR-AI-004 | AI governance log | Built | `LlmClient.govern`, `Platform.aiGovernance` | Stored centrally per run; no prompts, only a SHA-256 fingerprint |
| FR-AI-007 | Human approval for tools | Built | `AiAgentPackage.approve` | Desktop approval with timeout; Workflow approvals in the Task inbox |
| FR-AI-009 | MCP server | Built | `McpServer` (`POST /api/mcp`) | HTTP transport with API keys, administrator-chosen agents, run as the caller |
| FR-AST-* | Assist (task inbox) | Built | `Workflows`, console Task inbox | Forms, approvals, notices, claim and release, due times |
| FR-API-* | Public API | Built | `ControlRoomServer`, API keys | Runs with pools, priority and callbacks; work queues; workflows |
| SSO | Single sign-on | Built | `Sso` | OpenID Connect with PKCE, signature and nonce checks |
| FR-INS-* | Insights | Partial | Home dashboard, hours saved, AI governance | Business KPI dashboards from tagged values remain |
| FR-VOX-* | Voice | Planned | — | |
| FR-AI-002 | Model budgets and rate limits | Built | `Platform.connectionsForJob`, `ModelConnections.beforeCall` | Tokens a month, calls a minute, calls a run per connection |
| NFR-008 | Signed supply chain | Built | `ReleaseCommand`, `:cli:sbom` | Signed release manifest, CycloneDX SBOM; licences signed with Ed25519 |
| NFR-011 | Immutable, hash-chained audit | Built | `Audit` | Keyed chain, checkpoints outside the database, archived retention, syslog forwarding |
| FR-CC-RES003/004 | Vault master key | Built | `Vault`, `KeySources` | Key file, environment, Azure Key Vault, AWS Secrets Manager, Google Secret Manager or typed at start |
| FR-AI-009 | MCP over OAuth | Built | `ControlRoom.oauthUser`, protected resource metadata | Access tokens from the SSO issuer, audience and scope checked; existing accounts only |
| FR-PKG-005 | Dependency isolation | Planned | — | All toolkits share one class path |
