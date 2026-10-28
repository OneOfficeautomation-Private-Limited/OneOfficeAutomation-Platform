# ONEOFFICEAUTOMATION user guides

**Product version:** 0.2.0
**Audience:** everyone who uses ONEOFFICEAUTOMATION (OOA): people who build agents, people who use them in their daily
work, operators, administrators and the owners of the computers that run agents.

These guides explain how to do things, step by step, in the product's own screens. They are written per role, so you
only need to read the guides for the work you do. Reference material (every API call, every command option, the
architecture) lives in the other documents of the `docs` folder; the guides link to it where it helps.

## Contents

- [The product in one minute](#the-product-in-one-minute)
- [All guides](#all-guides)
- [Start here: a reading path for each role](#start-here-a-reading-path-for-each-role)
- [Words used in these guides](#words-used-in-these-guides)
- [Conventions](#conventions)
- [Related guides](#related-guides)

## The product in one minute

ONEOFFICEAUTOMATION has three parts:

| Part | What it is | Who uses it |
| --- | --- | --- |
| **One Agent Studio** | The designer. You build agents (automations), forms, workflows, Prompt Skills and document intelligence items in your own private workspace, and test them on your computer. | Builders |
| **One Command Center** | The web console and server. It keeps the shared agent repository, runs agents on runners, holds schedules, triggers, work queues, the vault, users, licences and the audit trail. | Operators, administrators, business users, builders |
| **One Runner** | The program on each computer that runs agents. It registers with the Command Center, takes work and reports back every step. | Runner owners (and, indirectly, everyone) |

A typical life of an agent: a builder creates it in Studio, tests it, and presses **Agent check-in**, ticking **Make
this the live version** when it is ready for production. The agent is now in the Command Center's **Agent
repository**, with a version number. An operator runs it on a runner, schedules it,
or connects it to a trigger or a work queue. Every run is visible in **Agent Runs**, and every action anyone takes is
recorded in the **One Audit Trail**.

## All guides

| No. | Guide | For | What it covers |
| --- | --- | --- | --- |
| 00 | This page | Everyone | The list of guides and where to start |
| 01 | [Getting started](01-getting-started.md) | Everyone | First install on one computer, first sign-in, the menu, building and running a first agent end to end |
| 02 | [Studio basics](02-studio-basics.md) | Builders | One Agent Studio's screen, workspace, explorer, actions, variables |
| 03 | [Building agents in Studio](03-studio-building-agents.md) | Builders | Steps, conditions, loops, error handling, inputs and outputs, good practice |
| 04 | [Running and debugging in Studio](04-studio-running-and-debugging.md) | Builders | Run, Debug, breakpoints, the run console, problems |
| 05 | [Operator's guide](05-operators-guide.md) | Operators | Agent Runs, running and deploying agents, schedules, triggers, work queues, workflows, Operations, dashboards, handling failures |
| 06 | [Administrator's guide](06-administrators-guide.md) | Administrators | Users, roles, teams, sign-in and security, API keys, the vault, shared values and connections, toolkits, templates, settings, licences, audit, AI governance |
| 07 | [Runner guide](07-runner-guide.md) | Runner owners, administrators | Installing and registering runners, attended and unattended work, screens, pools, updates, the notification-area assistant, diagnosing problems |
| 08 | [Browser connector guide](08-browser-connector-guide.md) | Builders, runner owners | Installing the connector for Edge, Chrome and Firefox, checking it, what works where, troubleshooting |
| 09 | [Child agents](09-child-agents.md) | Builders | Agents that call other agents, passing values, reuse |
| 10 | [Web and desktop automation](10-web-and-desktop-automation.md) | Builders | Browsers, windows, capture, recording, older Windows and Java applications |
| 11 | [Office files, e-mail and documents](11-working-with-office-files-email-and-documents.md) | Builders | Excel, Word, PowerPoint, PDF, e-mail, document intelligence |
| 12 | [Cloud and business apps](12-cloud-and-business-apps.md) | Builders | REST, Microsoft 365, Google, Salesforce, ServiceNow and the other connectors |
| 13 | [AI in agents](13-ai-in-agents.md) | Builders | Prompt Skills, AI agents, model connections, Knowledge Q&A |
| 14 | [Business users](14-business-users.md) | Business users | The Task inbox, starting workflows, running agents you are allowed to run, the notification-area assistant |
| 15 | [Publishing to the Command Center](15-publishing-to-command-center.md) | Builders | Check-in and check-out, versions, labels, approvals, folders |

## Start here: a reading path for each role

### Builder (you make agents)

1. [01 Getting started](01-getting-started.md), to have a working install and to see one agent go all the way.
2. [02 Studio basics](02-studio-basics.md), then [03 Building agents](03-studio-building-agents.md) and
   [04 Running and debugging](04-studio-running-and-debugging.md).
3. The topic guides you need: [09](09-child-agents.md), [10](10-web-and-desktop-automation.md),
   [11](11-working-with-office-files-email-and-documents.md), [12](12-cloud-and-business-apps.md), [13](13-ai-in-agents.md).
   For browser work on your own Edge, Chrome or Firefox also read [08](08-browser-connector-guide.md).
4. [15 Publishing to the Command Center](15-publishing-to-command-center.md) before you share your first agent.
5. Skim [05 Operator's guide](05-operators-guide.md) so you know how your agents will be run and watched.

### Business user (you use agents and workflows in your daily work)

1. [01 Getting started](01-getting-started.md), sections "Signing in for the first time" and "Finding your way".
2. [14 Business users](14-business-users.md): your Task inbox, starting a workflow, the assistant in the notification area.
3. If you have your own runner on your PC, the parts of [07 Runner guide](07-runner-guide.md) about attended runners.

### Operator (you run, schedule and watch agents)

1. [01 Getting started](01-getting-started.md).
2. [05 Operator's guide](05-operators-guide.md), from start to end. Keep its "Handling failures" section at hand.
3. [07 Runner guide](07-runner-guide.md), sections on runner status, pools and `ooa agent diagnose`.

### Administrator (you set the Command Center up and keep it safe)

1. [01 Getting started](01-getting-started.md) on a test computer first.
2. [06 Administrator's guide](06-administrators-guide.md), from start to end.
3. [07 Runner guide](07-runner-guide.md), to roll runners out.
4. The reference documents for production installs: [RUN_LOCALLY.md](../RUN_LOCALLY.md),
   [CLOUD_INSTALL.md](../CLOUD_INSTALL.md), [DATABASE.md](../DATABASE.md), [LICENSING.md](../LICENSING.md) and
   [CONTROL_ROOM_AND_AGENT.md](../CONTROL_ROOM_AND_AGENT.md).

### Runner owner (you look after the computers that run agents)

1. [07 Runner guide](07-runner-guide.md).
2. [08 Browser connector guide](08-browser-connector-guide.md) if agents drive your own browsers.
3. [05 Operator's guide](05-operators-guide.md), section "Handling failures", to understand the error codes runners report.

## Words used in these guides

| Word | Meaning |
| --- | --- |
| Agent | An automation: a list of steps that One Runner carries out. Saved as a `.flow.json` file. |
| Workflow | A chain of agents, people (forms, approvals) and decisions, run by the Command Center. Saved as `.process.json`. |
| Form | A set of fields people fill in, used by workflows. Saved as `.form.json`. |
| Agent repository | The Command Center's shared store of agents, with every version. Your Studio workspace is private; the repository is shared. |
| Live production version | The version of an agent that runs, schedules, triggers, work queues and workflows use. |
| Runner | A computer with One Runner registered to a Command Center. |
| Runner pool | A named group of runners; work sent to a pool goes to its least busy online runner. |
| Run | One execution of an agent on a runner. |
| Run-as user | The person a run acts as: their vault values and default runner are used. |
| Vault folder | A folder in One Agent Vault holding encrypted credentials. Agents refer to values as `folder/credential/attribute`. |
| Toolkit | A package of actions (Excel, Browser, Email, ...). Administrators can switch toolkits on and off. |
| Seat | A licence right given to a person (Developer, Citizen Developer, Attended Runner) or to a runner (Unattended Runner). |

## Conventions

- **Bold** text is a page name, button, field or menu exactly as it appears on screen, for example **Agent Runs** or
  **Register a runner**.
- A path such as **Operations > Schedules** means: open the **Operations** section of the menu on the left, then the
  **Schedules** page.
- `Code font` is something you type: a command, a value or a vault reference.
- Commands are shown as `ooa ...`. On an installed computer run them from the install's `bin` folder as
  `bin\ooa.bat ...` (or add `bin` to your PATH).
- What you see depends on your role: a page or button your role does not allow is simply not shown.

## Related guides

- [01 Getting started](01-getting-started.md)
- [05 Operator's guide](05-operators-guide.md)
- [06 Administrator's guide](06-administrators-guide.md)
- [07 Runner guide](07-runner-guide.md)
- [08 Browser connector guide](08-browser-connector-guide.md)
- Reference: [CONTROL_ROOM_AND_AGENT.md](../CONTROL_ROOM_AND_AGENT.md), [LICENSING.md](../LICENSING.md),
  [BROWSER_CONNECTOR.md](../BROWSER_CONNECTOR.md), [DATABASE.md](../DATABASE.md)
