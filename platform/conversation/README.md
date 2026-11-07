# One Chat: the conversational component

People ask in plain words for an agent to run ("post today's invoices for Acme") or for their runs ("what are my
runs?"). This module decides what to propose and when to ask; the product it runs in (the host) decides who may do
what and actually runs things.

```
host (control-room: Chat.java)  ──implements──▶  ChatPlatform   ◀──uses──  Conversation  ──▶  ChatEngine
  agents a person may run                                                    rules: confirm,    ├─ DeterministicChat (no model)
  start a run as that person                                                 approve, audit     ├─ LlmChat (AI model via host)
  approvals (Task inbox), notices                                                                └─ Router (hybrid)
  AI Gateway, audit trail, settings
```

## What lives where

| Here (conversation) | In the host (control-room) |
| --- | --- |
| `Conversation`: sessions, confirmation (used once, bound to the inputs), R3 approval flow | `Chat`: implements `ChatPlatform` with the Command Center's permissions, runners, Task inbox, audit |
| `ChatEngine` contract, `Router` (hybrid), `DeterministicChat`, `LlmChat` | `ChatModel`: model calls through the AI Gateway (connections, masking, budgets, governance) |
| `BotTools`: an agent's inputs as a tool schema (also used by the MCP server) | API route `/api/chat`, the One Chat console page, Settings, the Task inbox hook |

The module depends only on `sdk`. It never reads the database or calls the Command Center directly, so it can be
tested on its own (`ConversationTest` uses an in-memory host) and could later be released separately if needed.

## Rules the component enforces in every mode

- R0 agents start at once; R1 and R2 wait for the person's yes on a summary the component writes, not the model.
- A confirmation is used once and expires after 10 minutes.
- R3 agents go to the approvers after the yes; the run starts, as the person who asked, only after someone else approves.
  The host refuses self-approval.
- The model (llm and hybrid modes) only proposes; unknown tools are refused and undeclared inputs are dropped.

## Host settings read through `ChatPlatform.setting`

`chat_mode` (deterministic, llm, hybrid), `chat_connection` (One Model Connection; blank = the person's first allowed one).
`chat_mode_by_role` (one `role=mode` per line; the person's main role decides; anyone not listed gets `chat_mode`).
The Command Center also uses `chat_runner` and `chat_approvers`.

## Making an agent easy to reach in chat

Add a top-level `chat` block to the flow (part of the OOA Flow v1 schema): `examples`, `aliases`, `not_for`,
`risk` (R0 to R3; R2 when absent) and `prompts` (the question to ask per input). Flows written before that field
may keep the same block under `ui.chat`.

```json
"chat": {
  "risk": "R2",
  "examples": ["post today's invoices for Acme"],
  "aliases": ["AP run"],
  "not_for": ["issue a credit note"],
  "prompts": {"vendor": "Which vendor should I process?"}
}
```

## Acceptance test (UAT)

`uat/one-chat-uat.ps1` starts a fresh Command Center from the installed build and drives One Chat only through the
HTTP API: four people with their own sign-ins, a scripted runner, R0/R2/R3, approvals, languages, chat mode per role,
the schema check and a restart. It writes `uat/UAT-report.md` (the latest run is checked in).

## Languages

Replies follow the person's console language (en, de, fr, es, ja; `Messages`). Yes/no and cancel words are understood
in all of them. Agent names, codes and values are never translated.
