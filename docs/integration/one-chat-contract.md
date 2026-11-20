# One Chat: the contract with the OneChat service

**Status: draft, 20 Nov 2026. Nothing on this page is built yet.**

The assistant becomes a separate component, the same way Document Automation's DocFlow is. **OneChat** is a Python
service with its own database, built by its own developer in the repository
`OneOfficeautomation-Private-Limited/OneOfficeAutomation-OneChat`. This page is the platform's side of the agreement
between the two. OneChat keeps the same agreement in its `PLATFORM-CONTRACT.md`, and a contract test there fails if
OneChat stops serving something listed here. The DocFlow version of this page is
[document-automation-contract.md](document-automation-contract.md).

## Decisions (20 Nov 2026)

| # | Decision |
| --- | --- |
| 1 | People use the assistant from a **tray app** on the desktop, a **chat window**, the **One Chat** page and Studio's **assistant panel** |
| 2 | OneChat makes **no model calls of its own**: it asks One CommandCenter, so model keys, masking, budgets and the record of each call stay in One Model Connections |
| 3 | OneChat is written in **Python** |
| 4 | It replaces **One Chat's brain** and **Studio's Agent assistant**. In Studio it helps with **development only**, never with administration or anything else |
| 5 | It has **its own server and its own database** |
| 6 | It runs **on premises and in SaaS**, so it keeps each customer's data apart from day one |
| 7 | Its new tray app **replaces `ooa assistant`** |
| 8 | All three ways stay: A, the clients; B, the OneChat service; C, today's built-in engine as the fallback |

## 1. How the parts fit together

```
tray app, chat window ─┐
One Chat page ─────────┼─> One CommandCenter  /api/chat/...      ──(OneChatProxy: sign-in, permission, area,
Studio's assistant ────┘                     /api/onechat/...      person token)──> OneChat /api/chat/v1/...
                                               │
                                               └─ or the built-in engine (platform/conversation), when Settings says so

OneChat           ──> One CommandCenter /api/onechat-callback/v1/...   agents, runs, tasks, model calls, audit, Studio checks
One CommandCenter ──> OneChat /api/chat/v1/events                       a run ended, an approval was decided
One CommandCenter ──> clients /api/chat/stream                          notifications
```

- **A, the clients**, talk only to One CommandCenter: one address, one sign-in, permissions checked in one place. They
  never see OneChat's address or token.
- **B, the OneChat service**, understands what people ask and answers. For every action (start a run, call a model,
  check a step) it calls back to One CommandCenter, which decides.
- **C, the built-in engine**, is today's Java chat engine. It stays for installations without OneChat (section 9).
- OneChat never receives secrets: no vault values, model keys, API keys or passwords.

**Who builds what**

| Part | Built by | Where |
| --- | --- | --- |
| Tray app and chat window | OneChat's developer | OneChat repository |
| OneChat service and its database | OneChat's developer | OneChat repository |
| Pass-through, person token, callback routes, settings, version check, start with the platform | Platform | `platform/modules/one-chat` |
| Browser sign-in for desktop apps, notification stream | Platform | One CommandCenter kernel |
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
  - that the route is on the area's list (section 3);
  - then the person's permissions **as they are now**. A person who was disabled or deleted is refused at once.
- **Later work:** when something happens after the 5 minutes (a run ends 20 minutes later), One CommandCenter sends a
  fresh token with the event (section 6).
- **The service token alone acts for nobody:** without a valid person token, a callback gets only `version` and
  `health`.

## 3. Two areas with different limits

The area comes from the client's route, not from OneChat: Studio's panel uses `/api/chat/studio`, everything else
`/api/chat`. One CommandCenter refuses (403) any callback that isn't on the area's list.

### Chat area: tray app, chat window, One Chat page

- **What it can do:**
  - list the agents the person may run, and start one as them;
  - show the person's runs and their open tasks;
  - put a notice in the person's own Task inbox;
  - call a model and write to the Audit Trail.
- **Who can use it:** the same people as One Chat today. Viewing runs (`runs.view`) lets a person use the chat, and
  starting runs (`runs.start`) lets them start agents from it.
- **Risk classes**, enforced by One CommandCenter, not by OneChat:
  - **R0:** starts at once.
  - **R1 and R2:** the first `POST runs` returns a summary and a confirmation id instead of starting. OneChat shows the
    summary. When the person says yes (a Yes button or a typed yes, in any console language), OneChat calls `POST runs`
    again with the confirmation id and the token of that later message. One CommandCenter checks that it is the same
    person and chat, that the message came after the summary, that it is within 10 minutes, and that the id hasn't been
    used before.
  - **R3:** `POST runs` makes an **Approve** task for the approvers in Settings (`chat_approvers`), never for the person
    who asked. The run starts only when someone else approves. OneChat hears the decision as an event.

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
| Call a model; write to the Audit Trail | Anything in the chat area's list that isn't also in this list |

The panel needs Studio to be signed in to One CommandCenter, and the person needs the builder's right to check in
agents (`repo.checkin`). An administrator using Studio gets the same development-only list.

## 4. Routes

### OneChat calls One CommandCenter: `/api/onechat-callback/v1/`

| Route | Area | What it does | Was in `ChatPlatform` |
| --- | --- | --- | --- |
| `GET person` | both | The person: user name, display name, main role, language, and what they may do in this area | `person` |
| `GET settings` | both | The settings OneChat follows: the mode for this person (`chat_mode`, `chat_mode_by_role`), the runner (`chat_runner`). Never secrets | `setting` |
| `GET agents` | chat | Agents the person may run now: path, name, description, risk, inputs (name, type, description, required, default, allowed values) and the agent's `chat` block (examples, aliases, not_for, prompts) | `agents` |
| `POST runs` | chat | Start an agent as the person: `{agent, inputs, session_id, confirmation_id?}`. Answers `{run_id}`, `{confirm: {confirmation_id, summary, expires}}`, `{approval: {task_id}}` or `{refused: {kind, message}}` (`kind` is `no_runner` or `refused`) | `startRun`, `requestApproval`, `watchRun` |
| `GET runs?limit=` | chat | The person's latest runs, newest first | `recentRuns`, `canViewRuns` |
| `GET runs/{id}` | chat | One of the person's runs, with its status and results | |
| `GET tasks` | chat | The person's open Task inbox items: title, kind, due | |
| `POST notices` | chat | A notice in the person's own Task inbox, only to the person in the token | `notify` |
| `POST model` | both | One model call through One Model Connections: `{connection?, messages, tools, purpose}` answers `{text, tool_calls}`. Masking, the budget and the call record apply, and the connection must be one the person's roles may use (default: `chat_connection`, else the person's first allowed one) | `callModel` |
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
| 403 | Not allowed in this area, or not by the person's permissions |
| 409 | A confirmation that was already used or has expired |
| 429 | Over the model connection's budget or the rate limit |

### One CommandCenter calls OneChat: `/api/chat/v1/`

| Route | What it does |
| --- | --- |
| `GET version` | `{service: "onechat", version, contract: "v1"}` |
| `GET health` | 200 when ready |
| `POST messages` | `{session_id?, message, context?}` answers `{session_id, messages: [{role, text, kind, choices?, confirmation_id?, proposal?}]}`. `kind` is `answer`, `question`, `confirm`, `started`, `proposal` or `error`. In the Studio area, `context` holds the open agent and the selected steps, and `proposal` holds suggested steps |
| `GET sessions` | The person's chats: id, title, last used |
| `GET sessions/{id}` | One chat's messages |
| `DELETE sessions/{id}` | Deletes one of the person's chats |
| `POST events` | Something happened (section 6) |
| `DELETE people/{username}` | A person was deleted in One CommandCenter: remove their chats |
| `GET tenants/{id}/export`, `DELETE tenants/{id}` | SaaS: a customer's data, exported or removed |

### Clients call One CommandCenter

| Route | What it does |
| --- | --- |
| `POST /api/chat`, `GET /api/chat/{id}` | Send a message, read a chat (they exist today); they go to the engine chosen in Settings |
| `GET /api/chat` | The person's chats |
| `POST /api/chat/studio` | A message from Studio's panel (Studio area) |
| `GET /api/chat/stream` | Notifications (section 6) |
| `GET /api/onechat/version` | OneChat's version and whether it is compatible, for Settings |

## 5. Signing in from the tray app and chat window

- **Through the browser, not with pasted API keys.** The app opens the person's browser at One CommandCenter, and the
  person signs in as usual (password and two-step sign-in, or the company's single sign-on). This is OAuth 2.0 with
  PKCE and a loopback address, the standard way for desktop apps (RFC 8252).
- **What the app gets:** a device token tied to that person and that PC. It lasts as long as the administrator sets,
  is renewed while the app is in use, and shows under **My account**, where the person or an administrator can remove
  it.
- **Where the app keeps it:** in Windows' protected store (Credential Manager), for that Windows user only.
- **Sign out** removes the token from the PC and from One CommandCenter.
- **IT can preset One CommandCenter's address** in the installer.
- **API keys still work** for scripts and other systems.

This is new platform work: the sign-in and token routes, the device tokens, and their list in My account.

## 6. Notifications

- **To the clients:** `GET /api/chat/stream` (server-sent events) sends:
  - `new_task`, when a task arrives for the person;
  - `run_ended`, when a run they started finishes;
  - `approval_decided`, when their R3 request is approved or rejected;
  - `chat_reply`, when an answer that took a while is ready.

  A dropped stream reconnects and picks up where it stopped (`Last-Event-ID`). Where a proxy blocks streams, the app
  checks every 30 seconds instead. The tray app shows Windows notifications, and clicking one opens the matching page.
- **To OneChat:** `POST /api/chat/v1/events` sends `run_ended` (`{run_id, status, results, session_id}`) and
  `approval_decided` (`{task_id, decision, comment, approver}`), each with a fresh person token. Each event has an id,
  so OneChat ignores repeats. One CommandCenter retries, waiting longer each time, for up to an hour.

## 7. Data

| Kept by | What |
| --- | --- |
| OneChat (its own PostgreSQL) | Chats, messages, what the assistant remembers for each person, its own logs |
| One CommandCenter | Runs, tasks, approvals, notices, the Audit Trail, the record of model calls, settings, people and permissions |

- **Every OneChat record carries the tenant id and the user name.** On premises the tenant is `default`.
- **How long chats are kept:** a setting (open item 7). Today's built-in engine drops a chat after 8 hours unused.
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
- **Version check:** Settings > One Chat shows OneChat's version and whether it is compatible. With an incompatible
  OneChat, the chat says so and nothing runs.
- **CI:** a job named `onechat` starts the real service at `ref` and runs the platform's contract tests against it.
  OneChat's repository runs its own contract test against a stand-in for the callback routes.
- **Upgrade order:** OneChat first, then the platform. You approve each release, as with DocFlow.

## 9. The built-in engine (C)

- **Settings > One Chat > Chat engine:** **Built in** (default) or **OneChat service** (setting `chat_engine`,
  `builtin` or `service`).
- **Built in** is today's engine (`platform/conversation`). In Studio, it is today's Agent assistant, with the
  person's own model connections. Studio also uses it when it isn't signed in to One CommandCenter.
- **The default stays Built in** until OneChat passes the contract tests in CI and you approve the switch.
- **When OneChat is chosen but not answering:** the chat says so and operators get an alert. There is no silent
  switch to the built-in engine, so people never get different behaviour without knowing.
- **Today's settings keep their meaning with both engines:** `chat_mode`, `chat_mode_by_role`, `chat_connection`,
  `chat_runner` and `chat_approvers`.

## 10. Retiring the old parts

| Part | What happens |
| --- | --- |
| `ooa assistant` (the Java tray app) | Removed one release after the new tray app passes the contract tests; until then both work. [For business users](../user-guide/14-business-users.md) moves to the new app |
| Studio's Agent assistant dialog | Its **Build from a description** moves to the new panel; see open item 6 for **Explain this agent** |
| One Chat page | Stays, and talks to whichever engine is chosen |
| Built-in engine | Stays (section 9) |

Before anything is removed, the routes the old part used are compared with this contract, so nothing is lost.

## 11. Packaging

| | On premises (Windows) | Containers | SaaS |
| --- | --- | --- | --- |
| OneChat service | Windows setup option `-OneChat` installs it beside the platform, with its own Python, like DocFlow. The platform starts it (`onechat_autostart`) | Add-on `docker/compose.onechat.yaml` | Each region runs it; an optional part of the Helm chart |
| Tray app and chat window | An installer (MSI) IT can push to PCs, with the address preset | n/a | The same installer, with the region's address |

The platform finds OneChat the way it finds DocFlow:

1. `OOA_ONECHAT_HOME`;
2. the `home` line in `ooa-onechat.properties`;
3. the program's `OneChat` folder.

It needs `ONECHAT_API_TOKEN` set. When OneChat isn't installed, the platform prints one line saying why it didn't
start it.

## 12. Open items for OneChat's developer

1. The tray app and chat window: one Python app or two, which UI toolkit, and how it becomes an MSI.
2. Languages: replies and yes/no in the five console languages (English, German, French, Spanish, Japanese).
3. Keep the risk classes R0 to R3 and the agent's `chat` block (examples, aliases, not_for, prompts) as they are.
4. Licence: whether the platform licence covers OneChat, as it covers Document Automation.
5. The database on Windows: embedded PostgreSQL like DocFlow, or the customer's database server; and how it is backed
   up with the platform.
6. Studio: whether **Explain this agent** (it needs no model and works offline) stays as it is.
7. How long chats are kept by default.
8. Port 8086.
