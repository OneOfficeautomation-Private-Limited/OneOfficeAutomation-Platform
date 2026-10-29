# 12. UI redesign: TypeScript and React, Studio first

Status: **screens approved** (2026-10-29); building (P0) starts once the three decisions in section 7 are made. Pending items V1 (screens in TypeScript with React, Studio first), V2
(Studio from the Command Center later) and V6 (easy UI, gap list in `docs/research/easy-ui-study.md`).

## 1. Goal

Make Studio, then the Command Center, as easy to use as the best of AA, UiPath and Blue Prism, without losing what
works today (strict security policy, offline installs, the shared look, 278 Studio UI checks) and without building
any screen twice.

## 2. What stays

- **The servers and their APIs.** `StudioServer` and the Command Center keep every endpoint. The new screens are
  another client of the same API.
- **The design tokens** (`ooa-tokens.css`): colours, spacing, type, light, dark and Studio themes. New components are
  built on them, so the new screens look like OOA, not like a library's default.
- **The security policy** `script-src 'self'`. The build makes plain files with no inline script, served by our own
  servers. No CDN, no fonts from outside.
- **Offline installs.** The built files go inside the product (`ooa` executable, MSI, container). Customers never
  need Node or the internet.
- **The translation catalogue** (`ooa-i18n-*.json`, German, Spanish, French, Japanese): the new screens use the same keys.

## 3. Architecture

```
platform/ui/                     one npm workspace, built by Gradle
  package.json, package-lock.json  versions pinned; "npm ci" only
  packages/
    components/   @ooa/components: buttons, inputs, tables, dialogs, drawers, menus, tabs, toasts, empty states,
                  wizards, filter bar; built on accessible headless parts, styled with ooa-tokens.css
    api/          @ooa/api: typed client for the Studio and Command Center APIs; one base URL (local or cloud)
    studio/       the Studio application
    console/      the Command Center application (after Studio)
```

| Choice | Decision | Why |
| --- | --- | --- |
| Language | TypeScript, strict | Errors found at build time; easier to grow and hire for |
| UI library | React 18 | Decided in V1; most common, largest pool of people |
| Accessible parts | Radix UI primitives (MIT), styled by us | Keyboard, focus and screen-reader behaviour done right, with our own look |
| Flow and workflow canvas | Our own list canvas for agents (as today); React Flow (MIT) for workflows and AI agents' tool maps | Agents read top to bottom like today; workflows are graphs |
| Build | Vite | Fast; outputs plain hashed files that pass the security policy |
| Tests | Vitest for components; Playwright for whole screens (already used) | Keeps the 278 Studio checks as the parity bar |
| Gradle | A `uiBuild` task runs `npm ci` and `npm run build`, copies `dist/` into the resources | One build command for everything, in CI and locally |

**Dependencies stay few**, each pinned with its licence checked (all MIT): React, React DOM, Radix primitives, React
Flow, TypeScript, Vite, Vitest. CI runs `npm audit` and fails on high findings.

**One client, two places (V2).** `@ooa/api` takes a base URL. Locally it is `StudioServer`; later, Studio served by the
Command Center uses the same screens, with the Runner as the local helper for recording and test runs. Designing this
seam now means the cloud designer needs no second rebuild.

## 4. New Studio layout (approved 2026-10-29)

```
+----------------------------------------------------------------------------------------------+
| Top bar (dark): logo · breadcrumb · search (white field, Ctrl+K) · Save · Run · Debug · Close |
+--------------+-------------+-------------------------------------+---------------------------+
| Command      | Actions     | Editor tabs: Invoice intake × | ... | Properties | Variables |  |
| Center menu  | library     | Toolbar: Undo Redo Cut Copy Paste   | Triggers (tabs)           |
| Home         | (search,    |-------------------------------------|                           |
| BUILD        | toolkits    | Steps: numbered rows, coloured      | Labels beside fields,     |
| MONITOR      | with        | toolkit icons, guide lines for      | sections Input, Output,   |
| AI           | coloured    | blocks                              | If unsure                 |
| ADMINISTRATION icons)      |-------------------------------------|                           |
|              |             | Problems · Run output · Debug values|                           |
+--------------+-------------+-------------------------------------+---------------------------+
```

The design canvas https://claude.ai/artifact/HafD989gNmovYiwHkHmZrB has four clickable screens, approved on
2026-10-29: the workbench, Automation with Create, the AI agent editor and the model connection wizard.

**Layout, as in AA's Control Room and bot editor**
- The left menu is the Command Center's own: Home, then four sections, BUILD (Automation, Templates, Workflows,
  Forms), MONITOR (Activity, Runners, Schedules and triggers, Work queues, Task inbox), AI (Model connections, Prompt
  Skills, AI governance) and ADMINISTRATION (Users and roles, Vault, Audit, Licenses, Settings). Headings are bold
  white capitals with a coloured icon and a divider; items are indented under a guide line; the current one is solid
  blue. The menu can be collapsed.
- Actions on the left; Properties, Variables and Triggers as tabs on the right.
- Editor tabs: several items open at once (today only one).
- **Creating an item follows AA's order**: pick the folder in Automation, press Create, choose the type (Agent, AI
  agent, Workflow, Form, Document type, Prompt Skill, API agent, Script), then a short dialog with name, description,
  the folder already filled in, and Start from (blank or a template). Close with X, Esc or Cancel.
- Model connections: a wizard of four steps (Service, Model and region, Sign-in, Test and share) using dropdowns,
  with Save as draft.
- Plain words everywhere; codes only in tooltips.

**Look (dense and professional)**
- White panels and a dark frame (top bar and menu). Grey only for borders, never behind text.
- Text #17202b, secondary #353e4c, nothing lighter than #4b5565 on white.
- Rows 32 to 36 px; tabs, fields and buttons share one height (30 to 32 px), one 6 px radius and one field border
  (#7d8797, 3.6:1).
- Buttons are never grey: Save solid blue, Run solid green, Debug solid amber, Close solid white on the dark bar;
  secondary buttons white with a blue border and blue text; toolbar buttons plain dark text.
- Every colour pair is checked by calculation: text at least 4.5:1, borders and icons at least 3:1. White text on
  teal or cyan uses the darker shades `#0f766e` and `#0e7490`.

New and changed screens from the V6 gap list that live in Studio:

| Gap | In Studio |
| --- | --- |
| V6.6 AI agent editor | New item type. Prompt first, expanded into role, goal and plan; tabs Model, Inputs and outputs, Tools, Limits, Test; templates |
| V6.5 Model connections | Wizard with dropdowns: service, region, model, separate vault keys, test on the server, default use, on or off |
| V6.7 Filters and saved views | Explorer, action library and run history |
| V6.11 Wizards with drafts | New agent from template, new connection, new trigger |
| V6.1 Menu | Command Center menu in four sections: BUILD, MONITOR, AI, ADMINISTRATION |
| V6.2 Work for people | Forms and workflows under BUILD; the Task inbox under MONITOR |

## 5. Moving over without a big bang

The old Studio stays the default until the new one passes the same checks. Both are served side by side: the new
one at `/studio/` behind a "Try the new Studio" switch, the old one as "Classic".

| Step | What | Size |
| --- | --- | --- |
| P0 | Workspace, build in Gradle and CI, `@ooa/components` first set, typed API client, test harness | M |
| P1 | Shell: top bar, rail, side panel, tabs, search everything, New dialog; Explorer | M |
| P2 | Agent canvas and Properties panel (the largest part: drag and drop, blocks, selection, undo) | L |
| P3 | Action library, variables, triggers, run, debug, bottom panel | L |
| P4 | Other editors: form, workflow, document type, Prompt Skill, script | L |
| P5 | AI agent editor (new, V6.6) and model connections (V6.5) | M |
| P6 | All 278 checks pass on the new Studio; make it the default; remove Classic one release later | S |

Then the Command Center, on the same components (V6.1 menu, V6.2, V6.3, V6.4, V6.7 to V6.10).

## 6. Quality bar (checked at every step)

- **Architecture**: no screen calls the server except through `@ooa/api`; no business rules in the screens; no new
  server services.
- **UI**: each screen reviewed against the V6 study before it is built (a mockup), and in the browser after.
- **Performance budgets**, measured in CI with Playwright:
  - Studio opens in under 1.5 s on a local install.
  - First download under 400 KB compressed, with the editors loaded only when opened.
  - An agent of 1,000 steps scrolls smoothly; only the visible steps are drawn.
- **Accessibility**: WCAG 2.2 AA. Every action works from the keyboard, the existing focus ring is used, and
  automated axe checks run in CI.
- **Security**: the security policy is unchanged; no secrets in browser storage; vault values are never sent to the
  screens (as today).
- **Translations**: no text outside the catalogue; the catalogue check covers the new screens.

## 7. Decisions needed

1. **npm packages.** The build downloads the packages listed in section 3 into `platform/ui/node_modules` (not
   system-wide), on developer machines and in CI. Is that acceptable?
2. **Radix primitives** with our own styling (recommended), or Microsoft's Fluent UI, which looks like Windows
   but is larger and harder to make look like OOA?
3. **Editor tabs**, so several items can be open at once: yes (recommended) or keep one item at a time?
