# One Chat: the contract with the OneChat service

**Status: draft, 20 Nov 2026. Nothing on this page is built yet.** Updated the same day: three assistant options only,
no tray app or desktop chat window, administration in One Chat, and assistant settings (section 10).

The assistant becomes a separate component, the same way Document Automation's DocFlow is. **OneChat** is a Python
service with its own database, built by its own developer in the repository
`OneOfficeautomation-Private-Limited/OneOfficeAutomation-OneChat`. This page is the platform's side of the agreement
between the two. OneChat keeps the same agreement in its `PLATFORM-CONTRACT.md`, and a contract test there fails if
OneChat stops serving something listed here. The DocFlow version of this page is
[document-automation-contract.md](document-automation-contract.md).

## Decisions (20 Nov 2026)

| # | Decision |
| --- | --- |
| 1 | There are **three assistant options only**: Studio's **assistant panel** (development only), **One Chat** (everything else) and **AI assistants over MCP** (outside assistants such as Copilot or Claude). There is **no tray app and no desktop chat window** |
| 2 | OneChat makes **no model calls of its own**: it asks One CommandCenter, so model keys, masking, budgets and the record of each call stay in One Model Connections |
| 3 | OneChat is written in **Python** |
| 4 | It replaces **One Chat's brain** and **Studio's Agent assistant**. In Studio it helps with **development only**. One Chat covers everything else, administration included, always within the person's own permissions |
| 5 | It has **its own server and its own database** |
| 6 | It runs **on premises and in SaaS**, so it keeps each customer's data apart from day one |
| 7 | **No tray app:** `ooa assistant` was removed on 20 Nov 2026, and OneChat brings none |
| 8 | Both engines stay: B, the OneChat service, and C, today's built-in engine as the fallback (section 9). A, the clients, are the One Chat page and Studio's panel |
| 9 | Each assistant is **configurable like Claude**: administrators set the company rules (model and behaviour, instructions and skills, tools and permissions, memory, limits and privacy), and each person adjusts within them (section 10) |

## 1. How the parts fit together

```
One Chat page ─────────┐
Studio's assistant ────┴─> One CommandCenter  /api/chat/...      ──(OneChatProxy: sign-in, permission, area,
                                              /api/onechat/...      settings, person token)──> OneChat /api/chat/v1/...
                                               │
                                               └─ or the built-in engine (platform/conversation), when Settings says so

OneChat           ──> One CommandCenter /api/onechat-callback/v1/...   agents, runs, tasks, admin, model calls, audit, Studio checks
One CommandCenter ──> OneChat /api/chat/v1/events                       a run ended, an approval was decided
One CommandCenter ──> clients /api/chat/stream                          notifications
```

- **A, the clients** (the One Chat page and Studio's assistant panel), talk only to One CommandCenter: one address,
  one sign-in, permissions checked in one place. They never see OneChat's address or token.
- **B, the OneChat service**, understands what people ask and answers. For every action (start a run, change a
  setting, call a model, check a step) it calls back to One CommandCenter, which decides.
- **C, the built-in engine**, is today's Java chat engine. It stays for installations without OneChat (section 9).
- **Outside AI assistants** don't go through OneChat. They connect to One CommandCenter's own MCP server
  (`/api/mcp`) with the person's API key or a company sign-in token.
- OneChat never receives secrets: no vault values, model keys, API keys or passwords.

**Who builds what**

| Part | Built by | Where |
| --- | --- | --- |
| OneChat service and its database | OneChat's developer | OneChat repository |
| Pass-through, person token, callback routes, settings, version check, start with the platform | Platform | `platform/modules/one-chat` |
| Assistant settings: Settings > AI assistants, My account > AI assistants (section 10) | Platform | One CommandCenter kernel and `platform/ui` |
| Notification stream | Platform | One CommandCenter kernel |
| One Chat page and Studio's assistant panel | Platform | `platform/ui` |
| Built-in engine | Platform (exists) | `platform/conversation` |

## 2. Where OneChat is and how it is trusted

| | On premises | SaaS (each region) |
| --- | --- | --- |
| Address | Settings `onechat_url`; when empty, `OOA_ONECHAT_URL` (set by the containers), then `DATA\config\ooa-onechat.properties` (set by Windows setup `-OneChat`); else `http://127.0.0.1:8086` | `OOA_ONECHAT_URL`, set by the region's operator |
| Service token | Settings `onechat_token_ref`, a vault reference `folder/credential/attribute`; when empty, `OOA_ONECHAT_TOKEN`, then the token in that file, encrypted with the vault key | `OOA_ONECHAT_TOKEN` |
| Tenant sent | `default` | The tenant's id |
| Starts with the platform | Settings `onechat_autostart` (default 1) | The region runs it |

OneChat has the same token in `ONECHAT_API_TOKEN`, and One CommandCenter's address in `ONECHAT_PLATFORM_URL` for its
callbacks. Port 8086 is proposed (DocFlow uses 8085, One CommandCenter 8720). A tenant can't point the assistant
somewhere else.

**Headers.** One CommandCenter to OneChat:

- `Authorization: Bearer <service token>`;
- `X-OOA-Tenant`;
- `X-OOA-Person` (the person token);
- `X-OOA-Area` (`chat` or `studio`);
- `X-OOA-Lang` (`en`, `de`, `fr`, `es` or `ja`).

OneChat's callbacks send `Authorization`, `X-OOA-Tenant` and `X-OOA-Person`, with the person token unchanged.

### The person token

One CommandCenter makes a person token for every request it passes on. It names who is speaking, so OneChat can act
only for that person, and only for a few minutes.

- **What it holds:** the user name, main role, tenant, area, chat id, the address the request came from, when it was
  made, when it expires (5 minutes later) and a unique id.
- **Format:** `v1.<payload>.<signature>`. The payload is JSON in base64url; the signature is HMAC-SHA256 with a key
  that only One CommandCenter holds, in its vault.
- **What OneChat does with it:** it may read the payload (for the user name and language) but can't make or change a
  token. It passes the token back unchanged on its callbacks and never stores it.
- **What One CommandCenter checks on every callback:**
  - the signature;
  - that the token hasn't expired;
  - that the tenant matches `X-OOA-Tenant`;
  - that the route is on the area's list (section 3) and allowed by the assistant's settings (section 10);
  - then the person's permissions **as they are now**. A person who was disabled or deleted is refused at once.
- **Later work:** when something happens after the 5 minutes (a run ends 20 minutes later), One CommandCenter sends a
  fresh token with the event (section 6).
- **The service token alone acts for nobody:** without a valid person token, a callback gets only `version` and
  `health`.

## 3. Two areas with different limits

The area comes from the client's route, not from OneChat: Studio's panel uses `/api/chat/studio`, the One Chat page
`/api/chat`. One CommandCenter refuses (403) any callback that isn't on the area's list.

### Chat area: the One Chat page

- **What it can do:**
  - list the agents the person may run, and start one as them;
  - show the person's runs and their open tasks;
  - **administration and other work** the person may already do in the console (people, runners, schedules,
    triggers, work queues, settings and so on), with the same permissions as the console page;
  - put a notice in the person's own Task inbox;
  - call a model and write to the Audit Trail.
- **Who can use it:** the same people as One Chat today. Viewing runs (`runs.view`) lets a person use the chat, and
  starting runs (`runs.start`) lets them start agents from it. Each administration tool needs the console
  permission of its page.
- **Risk**, enforced by One CommandCenter, not by OneChat. The same rules cover agents and administration tools:
  - **Read only (R0):** answers at once.
  - **Changes (R1 and R2):** the first call returns a summary and a confirmation id instead of acting. OneChat shows
    the summary. When the person says yes (a Yes button or a typed yes, in any console language), OneChat calls again
    with the confirmation id and the token of that later message. One CommandCenter checks that it is the same person
    and chat, that the message came after the summary, that it is within 10 minutes, and that the id hasn't been used
    before.
  - **High impact (R3):** an **Approve** task goes to the approvers in Settings (`chat_approvers`), never to the person
    who asked. It happens only when someone else approves, and OneChat hears the decision as an event. High-impact
    administration includes deleting people, sign-in and security settings, the vault and licences.
  - The assistant's settings can make a tool stricter (section 10), never looser.

### Studio area: the assistant panel in Studio

**Development only.** The panel works on the agent open in Studio; Studio sends that agent (the draft, and the steps
the person has selected) with each message. One CommandCenter checks any steps OneChat suggests before Studio shows
them, and Studio inserts them only when the person accepts.

| Allowed | Never |
| --- | --- |
| Read the agent open in Studio (sent by Studio) | Start, stop or schedule runs |
| The commands Studio offers, with their fields | Save, check in or publish an agent |
| Check steps or a whole agent | Settings, users, roles, teams, runners, licences |
| The agents the person may call from theirs (names, inputs, outputs; read-only) | Credentials and vault values |
| Call a model; write to the Audit Trail; the Prompt Skills allowed for this area | Anything in the chat area's list that isn't also in this list |

The panel needs Studio to be signed in to One CommandCenter, and the person needs the builder's right to check in
agents (`repo.checkin`). An administrator using Studio gets the same development-only list.

## 4. Routes

### OneChat calls One CommandCenter: `/api/onechat-callback/v1/`

| Route | Area | What it does | Was in `ChatPlatform` |
| --- | --- | --- | --- |
| `GET person` | both | The person: user name, display name, main role, language, and what they may do in this area | `person` |
| `GET settings` | both | The person's settings for this area, with the company's and the person's own merged (section 10): model, behaviour, instructions, allowed skills, tools with Always / Ask first / Never, memory, limits; plus the runner (`chat_runner`). Never secrets | `setting` |
| `GET skills/{name}` | both | The text of a Prompt Skill the area may use | |
| `GET agents` | chat | Agents the person may run now: path, name, description, risk, inputs (name, type, description, required, default, allowed values) and the agent's `chat` block (examples, aliases, not_for, prompts) | `agents` |
| `POST runs` | chat | Start an agent as the person: `{agent, inputs, session_id, confirmation_id?}`. Answers `{run_id}`, `{confirm: {confirmation_id, summary, expires}}`, `{approval: {task_id}}` or `{refused: {kind, message}}` (`kind` is `no_runner` or `refused`) | `startRun`, `requestApproval`, `watchRun` |
| `GET runs?limit=` | chat | The person's latest runs, newest first | `recentRuns`, `canViewRuns` |
| `GET runs/{id}` | chat | One of the person's runs, with its status and results | |
| `GET tasks` | chat | The person's open Task inbox items: title, kind, due | |
| `GET admin/tools` | chat | The administration tools the person may use now: name, description, inputs, and risk (read, change, high impact) | |
| `POST admin/actions` | chat | Use one: `{tool, inputs, session_id, confirmation_id?}`. Answers like `POST runs`: a result, `{confirm}`, `{approval}` or `{refused}` | |
| `POST notices` | chat | A notice in the person's own Task inbox, only to the person in the token | `notify` |
| `POST model` | both | One model call through One Model Connections: `{connection?, messages, tools, purpose}` answers `{text, tool_calls}`. Masking, the budget, the person's limits and the call record apply, and the connection must be one the person may use (default: the area's model in section 10) | `callModel` |
| `POST audit` | both | An Audit Trail entry; the action starts with `chat.` or `studio.` | `audit` |
| `GET studio/commands` | studio | The commands (toolkits and actions) Studio offers, with their fields | |
| `POST studio/check` | studio | Checks steps or a whole agent the way Studio and `ooa validate` do; answers the problems found | |
| `GET studio/agents` | studio | The agents the person may call from theirs: name, inputs, outputs | |
| `GET version`, `GET health` | none | No person token needed | |

`ChatPlatform`'s `newId`, `loadSession`, `saveSession` and `deleteSessionsIdleSince` become OneChat's own business, in
its own database. Errors use the platform's shape, `{"error": {"code", "message", "hint"}}`:

| Status | Meaning |
| --- | --- |
| 401 | A bad or expired token |
| 403 | Not allowed in this area, by the assistant's settings, or by the person's permissions |
| 409 | A confirmation that was already used or has expired |
| 429 | Over a limit: the model connection's budget, the person's limit, or the rate limit |

### One CommandCenter calls OneChat: `/api/chat/v1/`

| Route | What it does |
| --- | --- |
| `GET version` | `{service: "onechat", version, contract: "v1"}` |
| `GET health` | 200 when ready |
| `POST messages` | `{session_id?, message, context?}` answers `{session_id, messages: [{role, text, kind, choices?, confirmation_id?, proposal?}]}`. `kind` is `answer`, `question`, `confirm`, `started`, `proposal` or `error`. In the Studio area, `context` holds the open agent and the selected steps, and `proposal` holds suggested steps |
| `GET sessions` | The person's chats: id, title, last used |
| `GET sessions/{id}` | One chat's messages |
| `DELETE sessions/{id}` | Deletes one of the person's chats |
| `GET people/{username}/memory` | What the assistant remembers about the person, so they can see it (section 10) |
| `DELETE people/{username}/memory` | Clears it, all or one item |
| `POST events` | Something happened (section 6) |
| `DELETE people/{username}` | A person was deleted in One CommandCenter: remove their chats and memory |
| `GET tenants/{id}/export`, `DELETE tenants/{id}` | SaaS: a customer's data, exported or removed |

### Clients call One CommandCenter

| Route | What it does |
| --- | --- |
| `POST /api/chat`, `GET /api/chat/{id}` | Send a message, read a chat (they exist today); they go to the engine chosen in Settings |
| `GET /api/chat` | The person's chats |
| `POST /api/chat/studio` | A message from Studio's panel (Studio area) |
| `GET /api/chat/stream` | Notifications (section 6) |
| `GET /api/onechat/version` | OneChat's version and whether it is compatible, for Settings |

## 5. Signing in

There is no separate sign-in. The One Chat page uses the person's console session, and Studio's panel uses Studio's
existing sign-in to One CommandCenter. API keys stay for MCP clients and scripts. (A browser sign-in for desktop apps
was planned for a tray app and a desktop chat window; both were dropped on 20 Nov 2026.)

## 6. Notifications

- **To the One Chat page and Studio's panel:** `GET /api/chat/stream` (server-sent events) sends:
  - `new_task`, when a task arrives for the person;
  - `run_ended`, when a run they started finishes;
  - `approval_decided`, when their high-impact request is approved or rejected;
  - `chat_reply`, when an answer that took a while is ready.

  A dropped stream reconnects and picks up where it stopped (`Last-Event-ID`). Where a proxy blocks streams, the page
  checks every 30 seconds instead.
- **To OneChat:** `POST /api/chat/v1/events` sends `run_ended` (`{run_id, status, results, session_id}`) and
  `approval_decided` (`{task_id, decision, comment, approver}`), each with a fresh person token. Each event has an id,
  so OneChat ignores repeats. One CommandCenter retries, waiting longer each time, for up to an hour.

## 7. Data

| Kept by | What |
| --- | --- |
| OneChat (its own PostgreSQL) | Chats, messages, what the assistant remembers for each person (only when settings allow it), its own logs |
| One CommandCenter | Runs, tasks, approvals, notices, the Audit Trail, the record of model calls, assistant settings, people and permissions |

- **Every OneChat record carries the tenant id and the user name.** On premises the tenant is `default`.
- **How long chats are kept:** an administrator's setting (section 10). Today's built-in engine drops a chat after 8
  hours unused.
- **A deleted person:** One CommandCenter calls `DELETE people/{username}`.
- **A customer who leaves (SaaS):** their data is exported, then removed, through the `tenants/{id}` routes, as part
  of the region's steps for removing a tenant.
- **Secrets:** credential inputs are never asked for in chat (as today), and masking is applied in One CommandCenter
  before any text reaches a model. Messages kept in OneChat hold only what people typed and what the assistant
  answered.

## 8. How versions work

- **OneChat has its own version numbers**, starting at 1.0.0.
- **Contract v1 only grows.** New routes and new fields are fine. Removing a route or changing what one means needs
  v2, and One CommandCenter then speaks both for one release.
- **The pin** is `platform/modules/one-chat/src/main/resources/onechat-compat.properties`, laid out like DocFlow's:
  - `contract`: the contract version;
  - `pinned`: the version shipped with and tested against this release;
  - `minimum`: the oldest version that still works;
  - `ref`: the git tag CI tests against.
- **Version check:** Settings > AI assistants shows OneChat's version and whether it is compatible. With an
  incompatible OneChat, the chat says so and nothing runs.
- **CI:** a job named `onechat` starts the real service at `ref` and runs the platform's contract tests against it.
  OneChat's repository runs its own contract test against a stand-in for the callback routes.
- **Upgrade order:** OneChat first, then the platform. You approve each release, as with DocFlow.

## 9. The built-in engine (C)

- **Settings > AI assistants > One Chat > Chat engine:** **Built in** (default) or **OneChat service** (setting
  `chat_engine`, `builtin` or `service`).
- **Built in** is today's engine (`platform/conversation`). In Studio, it is today's Agent assistant, with the
  person's own model connections. Studio also uses it when it isn't signed in to One CommandCenter.
- **The default stays Built in** until OneChat passes the contract tests in CI and you approve the switch.
- **When OneChat is chosen but not answering:** the chat says so and operators get an alert. There is no silent
  switch to the built-in engine, so people never get different behaviour without knowing.
- **Today's settings keep their meaning with both engines:** `chat_mode`, `chat_mode_by_role`, `chat_connection`,
  `chat_runner` and `chat_approvers`. They move into Settings > AI assistants > One Chat.
- **The assistant settings in section 10 apply to both engines.**

## 10. Assistant settings

Each assistant is configurable like Claude. Administrators set the company rules; each person adjusts their own
choices within them. One CommandCenter keeps the settings and enforces them, for the built-in engine and for OneChat
alike.

**Built so far (S2, 20 Nov 2026), in the One Chat module 1.1.0:** Settings › AI assistants with its three tabs; the
groups **Model and behaviour** and **Instructions and skills** for One Chat and Studio's assistant (settings
`chat_*` and `studio_*`, defaults equal to the values used before); My account › AI assistants (model, language, own
instructions; table `assistant_prefs`); `AssistantSettings.effective`, the one rule that combines them; One Chat's
model calls and Studio's assistant (through `POST /api/assistants/studio/complete` while Studio is signed in) use it.
**Built in S3 (20 Nov 2026), One Chat module 1.2.0:** tools and permissions for One Chat (settings `chat_tool_read`,
`chat_tool_agents`, `chat_tool_admin_read`, `chat_tool_admin_change`, `chat_tool_admin_high`; administration
groups Never at first) and the person's "always ask me first" (schema step 2); the confirmation card; and the
kernel's administration tools (`AdminTools`, reached by modules through `ModuleContext.adminTools` and
`runAdminTool`): five look-ups, five changes and deleting a person, each done by the kernel's own method. The
callbacks `GET admin/tools` and `POST admin/actions` will offer the same catalogue to OneChat.

**Built in S4 (20 Nov 2026), One Chat module 1.3.0:** memory (`chat_memory`: chat, off or across; the notes people ask
it to remember in `assistant_memory`, schema step 3, seen and forgotten in My account), how long chats are kept
(`chat_keep_hours`) and the person's chats list (`GET /api/chat`), tokens per person a month (`chat_ktokens_per_person`,
`studio_ktokens_per_person`, counted from the AI call record), wait times (`chat_timeout_seconds`,
`studio_timeout_seconds`), masking (`chat_mask_pii`, `studio_mask_pii`) and MCP's runs per person an hour
(`mcp_runs_per_hour`, the Integration module). Every default keeps what the assistants did before. The callback `GET settings` will return `AssistantSettings.effective` for the person and
area.

**Where:**

- **Settings > AI assistants** (administrators), with three tabs: **Studio assistant**, **One Chat** and **Outside
  assistants (MCP)**. It replaces today's "AI assistants (MCP)" section and One Chat's settings.
- **My account > AI assistants** (each person): only what the administrator allows, within its limits.

| Group | Administrator sets | Each person may adjust |
| --- | --- | --- |
| **Model and behaviour** | The default model per assistant (GPT, Claude, Bedrock, an in-house model) and which models people may choose; creativity (temperature); the longest answer; the answer language; One Chat's mode (rules, rules then AI, AI) | Their model, from the allowed list; their answer language |
| **Instructions and skills** | Company instructions per assistant (like Claude's project instructions); the Prompt Skills each assistant may use; whether people may add their own instructions | Their own instructions, added after the company's |
| **Tools and permissions** | For each tool group, **Always allowed**, **Ask first** or **Never**. Groups: read runs and tasks, run agents, administration, documents, Studio checks. Who approves high-impact requests | Stricter only, for example "always ask me first" |
| **Memory, limits and privacy** | Memory: off, this chat only, or across chats; how long chats are kept; token and cost limits per person; time-outs; masking personal data; whether prompts and answers are kept | Memory on or off for themselves; see and clear what it remembers |

**Never possible, whatever the settings:** credential values; checking in or publishing from Studio's assistant;
approving your own request; a tool more open than the person's console permissions.

**How OneChat follows them:**

- `GET settings` returns the person's settings for the area, already merged (company first, then the person within
  the limits). OneChat uses the model, behaviour, instructions and skills it is given, and asks again with each new
  message, so a change applies at once.
- One CommandCenter enforces the tools on every callback: **Never** is refused (403), and **Ask first** turns a call
  into a confirmation even when the risk alone wouldn't.
- Limits are enforced in `POST model`, which refuses with 429 once the person's limit is reached.
- OneChat stores memory only when the settings allow it, and the person can see and clear it
  (`people/{username}/memory`).

## 11. Retiring the old parts

| Part | What happens |
| --- | --- |
| `ooa assistant` (the Java tray app) | Removed on 20 Nov 2026. There is no tray app any more |
| Studio's Agent assistant dialog | Its **Build from a description** moves to the new panel; see open item 5 for **Explain this agent** |
| One Chat page | Stays, and talks to whichever engine is chosen |
| Built-in engine | Stays (section 9) |

Before anything else is removed, the routes the old part used are compared with this contract, so nothing is lost.

## 12. Packaging

| | On premises (Windows) | Containers | SaaS |
| --- | --- | --- | --- |
| OneChat service | Windows setup option `-OneChat` installs it beside the platform, with its own Python, like DocFlow. The platform starts it (`onechat_autostart`) | Add-on `docker/compose.onechat.yaml` | Each region runs it; an optional part of the Helm chart |

There is nothing to install on people's PCs: both clients are pages of One CommandCenter and Studio.

The platform finds OneChat the way it finds DocFlow:

1. `OOA_ONECHAT_HOME`;
2. the `home` line in `ooa-onechat.properties`;
3. the program's `OneChat` folder.

It needs `ONECHAT_API_TOKEN` set. When OneChat isn't installed, the platform prints one line saying why it didn't
start it.

## 13. Open items for OneChat's developer

1. Languages: replies and yes/no in the five console languages (English, German, French, Spanish, Japanese).
2. Keep the risk classes R0 to R3 and the agent's `chat` block (examples, aliases, not_for, prompts) as they are, and
   use the same classes for administration tools.
3. Licence: whether the platform licence covers OneChat, as it covers Document Automation.
4. The database on Windows: embedded PostgreSQL like DocFlow, or the customer's database server; and how it is backed
   up with the platform.
5. Studio: whether **Explain this agent** (it needs no model and works offline) stays as it is.
6. The default for how long chats are kept, and for memory (off, this chat only, or across chats).
7. Port 8086.
