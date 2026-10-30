# Easy UI study (V6): six screen areas against AA, UiPath and Blue Prism

Written on 2026-10-29 for pending item V6 ("Easy UI"). OOA's screens were read in the source
(`platform/control-room/src/main/resources/console/cr.js`, `platform/cli/src/main/resources/studio/studio.js`). The other
three products were read from their public documentation, forums and reviews only. Nothing was signed into, and every
description is in our own words. Points the sources did not confirm are marked *not confirmed*.

The result feeds the Studio and Command Center redesign decided on 2026-10-29 (V1: TypeScript with React, Studio
first). The gap list at the end is ordered by priority and copied into the pending log.

## Summary

OOA already has more than it shows. Its lists have search, sorting and CSV export everywhere, and queues already have
bulk retry, defer and delete. The problems are in how things are arranged and named:

- **Too many places.** 28 pages in 5 menu sections. Human tasks are split over three places, analytics over two,
  AI over three.
- **Technical words on screen.** Roles show permission codes (`repo.view`); role names must be codes; model
  connections ask for raw model ids, endpoints and a packed `ID:SECRET` key.
- **One long settings page** of about 50 fields, where the others use short screens or wizards.
- **No agent builder.** An AI agent is a step inside a flow; AA and UiPath give agents their own editor.

What the three vendors do that people find easiest: wizards that show only the steps that apply (AA), "duplicate and
customise" for built-in items (UiPath), filters on every column with saved views (Blue Prism), one inbox with tabs
(Blue Prism and UiPath), and a test button before saving any connection (all three).

What users complain about in each, so we avoid it: AA has no auto-refresh on dashboards and asks for a desktop agent
just to test an AI connection; UiPath's dashboards depend on a slow embedded tool and its bulk edit only works for
specially built forms; Blue Prism needs SQL for custom dashboard tiles and has coarse permissions with no team scoping.

## 1. Work queues

| | How it works |
| --- | --- |
| OOA today | Operations > Work queues. A list with live counts, progress bars, service level with an "overdue" badge, start and stop. Create is one dialog. A queue's detail page has Add items, Retry, Defer and Delete for selected items, and CSV export. Work item models are a separate dialog. |
| AA | Manage > Queues. Create is a short wizard that can be saved as a draft. The detail page has tabs (work items, owners, participants, consumers, item structure). Seven plain item states; bulk status change; automatic retries up to 10 with a "retries left" column. Users ask for a "mark failed" action. |
| UiPath | Orchestrator > folder > Queues. Create is one window, which also holds automatic retries and a service level with an early-warning margin ("risk" level). Each queue has a chart over hour, day, week or 30 days. Items can be uploaded from CSV. Retry chains are traced item to item. |
| Blue Prism | Created in the desktop client; watched in a split view (queues on top, items below). Every column has a filter, including tags with include and exclude, and dates like "Today". Filter sets can be saved and one made the default. Right-click bulk actions; export the current view. Users complain that permissions cannot be scoped to a team. |

**Gaps for OOA:** filters on each column with saved views; an early-warning service level (amber before red); a
progress chart per queue; a "mark failed" and "mark done" action for selected items; owners and users of a queue on
the queue itself (team scoping).

## 2. Human tasks

| | How it works |
| --- | --- |
| OOA today | Three places: **Task inbox** (Operations) with To do and Done tabs; **One Workflow** (Build) to start workflows and watch their runs; forms and workflows are designed in Studio. Approvals are Approve or Reject, one at a time. |
| AA | Builder (Process Composer) on a canvas with bot, form, API and filter steps. People use one web portal with Processes (a catalogue to start a request), Requests and Tasks, with saved table views and notifications. Tasks can go to any member of a group to share the load. |
| UiPath | Action Center inbox with My Actions and Manage Actions (an admin view of everyone's). Filters by priority, type, label and time; bulk assign; bulk edit of up to 100 tasks (only for specially built forms). A dashboard slice opens the inbox already filtered. |
| Blue Prism | Interact web app with Home (forms to start), History, and My Work (Approvals, Drafts, Inbox). Bulk approve and decline; declining needs a reason; people cannot approve their own submission by default; a "locked by" column; a bell for notifications. |

**Gaps for OOA:** one **Work for people** area with tabs (Start a request, My tasks, Approvals, Drafts, History) and an
admin view of everyone's tasks; bulk approve and reject, with a reason required to reject; a rule that the submitter
cannot approve their own request; a catalogue of workflows people may start; saved views and labels; a notification
bell. Workflow runs move into the same area.

## 3. Analytics and dashboards

| | How it works |
| --- | --- |
| OOA today | Two pages: **Operations** (run figures for a period) and **Dashboards** (KPI widgets with size, move, copy and sharing with roles and teams). Home has tiles. AI governance is a third page under AI. |
| AA | Bot Insight: operations dashboards plus a business dashboard built automatically from each bot's values. "Save as" turns a standard dashboard into a custom one; adding a widget is a 3-screen wizard. Complaints: no auto-refresh, times only in UTC. |
| UiPath | Insights: My dashboards and Tenant dashboards, a template gallery, filters for the whole dashboard, click a chart to filter the others, duplicate, scheduled delivery by email. Complaints: slow to load. |
| Blue Prism | Drag tiles from a library onto a grid in three sizes; Personal or Global dashboards. Custom tiles need a SQL stored procedure, which users dislike. |

**Gaps for OOA:** one **Insights** area (operations, dashboards and AI usage as tabs); ready-made dashboard templates
(runs, queues, runners, value saved, AI cost); drag to place widgets; filters for the whole dashboard and click to
filter; scheduled email of a dashboard; auto-refresh and the viewer's own time zone (we already send the time zone).

## 4. Roles and permissions

| | How it works |
| --- | --- |
| OOA today | Administration > Roles. A list of roles with their permissions as code chips. New role is one dialog: a code name, a description, and checkboxes grouped by the first part of each code. Built-in roles cannot be edited but can be switched off. Folder rights and teams are on other pages. |
| AA | Administration > Roles. A wizard of about four screens; the bot and runner screens appear only when the matching permission is ticked. Permissions are grouped in named sections, each with a one-line description. Friction: base "view" permissions must be ticked by hand. |
| UiPath | Manage access > Roles. Pick tenant or folder role first and only the matching permissions show; permissions with no effect are greyed out. "Duplicate & customise" for built-in roles; CSV export and import; a warning for powerful permissions; a list of who holds a role before it is deleted. |
| Blue Prism | Roles list beside a permission panel; ten categories; membership dialog with directory group mapping. Complaints: no view/edit split and no team scoping. |

**Gaps for OOA:** plain names and a one-line description for every permission (codes only in a tooltip); a display
name for roles; "Duplicate and customise" for built-in roles; ticking a permission ticks the "view" it needs; a warning
on powerful permissions; who holds the role, shown before delete; a short wizard (permissions, folders, people).

## 5. AI model settings

| | How it works |
| --- | --- |
| OOA today | AI > One Model Connections (shown inside the Administration page). One dialog: provider, model id, endpoint and a vault key, typed by hand from help text; AWS keys packed as `ID:SECRET`. A Test connection button. Prompt Skills and AI governance are separate pages. |
| AA | AI > Model connections. Name, pick vendor and model, key from the vault, test, share with roles. Friction: the test needs a desktop agent installed. |
| UiPath | Admin > AI Trust Layer. Connect a provider, map a model to each product feature (with a fallback to the default model), test, save; a connection can be disabled without deleting it. Governance tab with usage by model, call audit and personal-data masking. |
| Blue Prism | AI Gateway (new; screens not public, *not confirmed*); older Skills and Digital Exchange connectors. |

**Gaps for OOA:** a provider picker that fills the endpoint and lists the models (no typing ids); separate fields for
access key and secret; region as a list; disable without delete; choose which connection each feature uses (Studio
assistant, document extraction, agents) with a default; usage and cost per connection on the same page. Our test
already runs on the server, which is better than AA's.

## 6. AI agents

| | How it works |
| --- | --- |
| OOA today | No builder. In Studio an AI agent is a **Run AI agent** step inside a flow: goal, model, the agents it may call as tools, limits. Prompt Skills are their own page. Agent test sets exist. |
| AA | Automation > Create new > AI Agent. Write one plain-language prompt; the system expands it into role, goal and action plan. Tabs for model, inputs and outputs, end-user display and tools (bots, processes, API tasks, other agents, a person). Free test runs. |
| UiPath | Studio Web > Create new > Agent. Autonomous or conversational; start blank or generate from a description. A canvas and a form view kept in sync; tools, context and escalations; an evaluation score; publish and run. |
| Blue Prism | WorkHQ (launched April 2026) lists an agent builder and templates; screens not public (*not confirmed*). |

**Gaps for OOA:** an **AI agent** item type in Studio with its own editor: prompt first, expanded into role, goal and
plan; tabs for model, inputs and outputs, tools (agents, API tasks, workflows, a person) and limits; a test panel with
the existing test sets and a score; templates. The Run AI agent step stays, and calls the saved agent.

## Across all areas

- **Menu:** group by what people do: Build, Run and watch, Work for people, Insights, AI, Administration. Drop the
  mixed "One ..." names in the menu, or use them for every page; give Users, Roles and Teams different icons.
- **Settings:** split the long page into short pages (Sign-in and SSO, Security, Data retention, Runners, Backups,
  AI assistants) with search across all settings.
- **Wizards with drafts** for first-time setup of queues, roles, model connections and triggers; show only the steps that apply.
- **Duplicate** on every item that people configure (roles, dashboards, queues, connections).
- **Same patterns everywhere:** list with filters on each column and saved views, detail in a side drawer, bulk
  actions on selection, a test button before save.

## Gap list (priority order)

| ID | Gap | Area | Size |
| --- | --- | --- | --- |
| V6.1 | New menu grouped by task; consistent names and icons | All | M |
| V6.2 | One Work for people area (start a request, my tasks, approvals, drafts, history, admin view) | Human tasks | L |
| V6.3 | Permissions in plain words with descriptions; role display names; duplicate and customise; auto-tick needed view | Roles | M |
| V6.4 | Settings split into short pages with search | Settings | M |
| V6.5 | Model connections: provider picker, model list, separate key fields, disable, per-feature choice | AI | M |
| V6.6 | AI agent editor in Studio (prompt first, tabs, test panel, templates) | AI agents | L |
| V6.7 | Column filters and saved views for every list (queues, items, runs, tasks) | All | M |
| V6.8 | Bulk approve and reject with reason; no self-approval by default | Human tasks | S |
| V6.9 | One Insights area; dashboard templates; drag to place; dashboard filters; email schedule; auto-refresh | Analytics | L |
| V6.10 | Queue early-warning service level, progress chart, mark failed or done, owners on the queue | Queues | M |
| V6.11 | Wizards with save-as-draft for first setup of queues, roles, connections, triggers | All | M |

S is days, M about a week, L two weeks or more. All of them are to be built in the new TypeScript screens (V1), Studio
first, so nothing is built twice; V6.8 and V6.10's server parts can be done earlier.

## Sources

- AA: docs.automationanywhere.com pages on creating and viewing queues, Process Composer, the Co-Pilot portal, Bot
  Insight dashboards and widgets, creating roles and feature permissions, model connections, AI Skills and AI agents;
  community forum threads on retrying and failing work items; G2 reviews.
- UiPath: docs.uipath.com pages on managing queues, Action Center, Insights dashboards, managing roles, the AI Trust
  Layer and LLM configuration, building agents in Studio Web and Maestro; forum threads on Insights loading and agent
  feedback; PeerSpot reviews.
- Blue Prism: documentation.blueprism.com pages on System Manager queues, Control Room queue management, Hub Control
  Room work queues and dashboard widgets, Interact, dashboards and tiles, user roles and permissions; community idea
  posts on queue access and permissions; SS&C announcements of WorkHQ and AI Gateway; PeerSpot reviews.
