# One Chat: running agents in plain words

**Audience:** business users who start agents from chat, builders who make agents easy to reach in chat, and
administrators who set chat up.

## Contents

1. [What One Chat does](#1-what-one-chat-does)
2. [Use One Chat](#2-use-one-chat)
3. [What waits for your yes, and what needs approval](#3-what-waits-for-your-yes-and-what-needs-approval)
4. [Languages](#4-languages)
5. [For builders: make an agent easy to reach](#5-for-builders-make-an-agent-easy-to-reach)
6. [For administrators: set One Chat up](#6-for-administrators-set-one-chat-up)
7. [How chat understands requests: the three modes](#7-how-chat-understands-requests-the-three-modes)
8. [If it goes wrong](#8-if-it-goes-wrong)
9. [Related guides](#related-guides)

---

## 1. What One Chat does

One Chat is a page of One Command Center where you ask for an agent run in your own words, for example "post today's
invoices for Acme", or ask "what are my runs?".

- The run starts **as you**, with your permissions. You only reach agents your role may run.
- Anything that changes data shows you a summary first and waits for your **yes**.
- The chat tells you when the run finishes, with its results, or why it failed.
- Every run started from chat shows in **Agent Runs** (source **chat**) and in the **One Audit Trail**, like any
  other run.

## 2. Use One Chat

1. Open One Command Center and sign in.
2. Choose **One Chat** in the menu (under **AI**).
3. Type your request in the box ("For example: post today's invoices for Acme, or: what are my runs?") and press
   **Send**.

**What can happen next:**

| You see | What it means | What you do |
| --- | --- | --- |
| A question, for example "Which vendor should I process? (Acme, Globex)" | The agent needs a value you did not give | Answer it; you are asked for each missing value in turn |
| A summary ending in a yes/no question | Everything is known; nothing has run yet | Answer **yes** to start it, **no** to cancel |
| "Started … I'll tell you here when it finishes." | The run is on its way to a runner | Wait; the chat tells you when it ends |
| "I've asked for approval (task …)" | The agent is high-impact (R3) | Wait; someone else approves it in their Task inbox |
| "Sorry, I didn't understand that …", sometimes with an example | No agent you may run matched | Rephrase, or use the example shown |

Dates can be written as "today", "yesterday", "tomorrow" (or the same word in your console language, such as
"heute", "hier", "mañana"), "3 Nov", "Nov 3", "03/11/2026" (day first) or "2026-11-03". Amounts can use Indian
groupings and words ("1,12,000", "5 lakh", "2 crore", "10k"). "cancel" (or "stop") drops the current request while
you are being asked for values. A yes counts once: answering yes again does not start a second run, and a summary that waits more than
10 minutes expires.

**New chat** starts a fresh conversation. Your chats are kept when the Command Center restarts, so you can come back
to one later.

## 3. What waits for your yes, and what needs approval

Each agent has a risk class, set by its builder:

| Risk | Meaning | What happens |
| --- | --- | --- |
| **R0** | Reads only (reports, look-ups) | Starts at once |
| **R1**, **R2** | Changes data | A summary waits for your yes. Agents without a risk class are treated as R2 |
| **R3** | High impact (payments, deletions) | After your yes, an **Approve** task goes to the approvers. The run starts, as you, only when someone **else** approves. You are told in the chat either way, with the approver's comment |

Nobody can approve their own request, administrators included.

## 4. Languages

One Chat answers in the language of your console (English, German, French, Spanish or Japanese; change it at the
top of the console). Yes and no work in every one of them ("ja", "oui", "sí", "はい", "nein", "non", "いいえ" …).
Agent names, codes and values are never translated.

## 5. For builders: make an agent easy to reach

Without anything extra, One Chat knows an agent's name, description and input variables. Add a `chat` block to the
flow (OOA Flow v1) to make it easier to reach and to set its risk class:

```json
"chat": {
  "risk": "R2",
  "examples": ["post today's invoices for Acme", "process vendor invoices"],
  "aliases": ["AP run"],
  "not_for": ["issue a credit note"],
  "prompts": {"vendor": "Which vendor should I process?"}
}
```

| Field | What it does |
| --- | --- |
| `risk` | R0, R1, R2 or R3 (see [section 3](#3-what-waits-for-your-yes-and-what-needs-approval)). Default R2 |
| `examples` | Things people say to ask for it. The rules mode matches requests against these, so give a few real ones |
| `aliases` | Short names that always mean this agent |
| `not_for` | Requests that sound similar but belong elsewhere; they keep the agent from being picked |
| `prompts` | The question to ask for a missing input, by input variable name |

Good practice:

- Write a clear **description**: the AI modes read it to choose the agent.
- Give each input a **description** and, where the values are fixed, a list of allowed values: chat shows them as
  choices and refuses anything else. Inputs with a default are not asked for.
- Credential inputs are never asked for in chat.
- `ooa validate FILE` checks the block. Agents written before this field may keep it under `ui.chat`; it still works.

## 6. For administrators: set One Chat up

Open **Settings**, section **One Chat**:

| Setting | What it does |
| --- | --- |
| **How chat understands requests** | **Rules only (no AI model)**, **Rules first, the AI model when unsure** or **AI model**. See [section 7](#7-how-chat-understands-requests-the-three-modes) |
| **One Model Connection for chat** | The model connection the AI modes use. Empty = the person's first allowed connection. Its masking and budget apply |
| **Runner or runner pool id** | Where chat runs go. Empty = any online runner |
| **Who approves high-impact (R3) requests** | `user:name` and `role:name` entries. Default `role:admin`. Never the person who asked |
| **A different mode for some roles** | One `role=mode` per line, for example `bot_runner=deterministic` and `admin=hybrid`. A person's main role decides; anyone not listed gets the mode above. Unknown roles and modes are refused when you save |

People need permission to view runs to see **One Chat**, and permission to run an agent to start it from chat.

## 7. How chat understands requests: the three modes

| Mode | How it decides | Cost and data | Good for |
| --- | --- | --- | --- |
| Rules only | Matches the request against each agent's examples, aliases and name; reads values (enumerations, dates, numbers) from the words | No model, nothing leaves the Command Center | Most teams; clear, repeatable requests |
| Rules first, the AI model when unsure | Rules mode first; only requests it cannot place go to the model | Model calls only for the unclear requests | Mixed wording, keeping cost down |
| AI model | The model chooses the agent and its inputs from the descriptions | Every request is a model call; only the person's words and agent descriptions are sent | Free wording, many agents |

In every mode the model only **proposes**. The same rules decide what runs: your permissions, the yes for R1 to R3,
the approval for R3, and the check of every input. An agent the model names that you may not run is refused, and
inputs the agent does not declare are dropped.

## 8. If it goes wrong

| Message | Cause | What to do |
| --- | --- | --- |
| "No runner is online to run … right now" | The chat runner or pool is offline | An operator checks the runners, or the **Runner or runner pool id** setting |
| "Sorry, I didn't understand that …" | No agent you may run matched | Use the example shown; builders can add `examples` and `aliases` |
| "I couldn't get an answer from the AI model …" | The model connection failed or is not set | An administrator checks the connection, or sets the role to rules mode |
| "That confirmation expired …" | The yes came more than 10 minutes after the summary | Ask again |
| An R3 request never starts | Nobody has approved it yet | Ask an approver to look at their **Task inbox** |

## Related guides

- [14 Business users](14-business-users.md): the Task inbox, where R3 approvals arrive
- [06 Administrator's guide](06-administrators-guide.md): roles, settings and AI governance
- [13 AI in agents](13-ai-in-agents.md): One Model Connections
- [15 Publishing to the Command Center](15-publishing-to-command-center.md): check-in and the live version
