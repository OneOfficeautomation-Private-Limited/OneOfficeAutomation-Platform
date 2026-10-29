# 12. UI redesign: TypeScript and React, Studio first

Status: **design, for review** (2026-10-29). Pending items V1 (screens in TypeScript with React, Studio first), V2
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

## 4. New Studio layout

```
+--------------------------------------------------------------------------------+
| Top bar: logo · breadcrumb · search everything (Ctrl+K) · Save · Run · Debug · ⋮ |
+---+----------------------+-----------------------------------+------------------+
| R | Side panel           | Editor tabs: Invoice bot × | Approvals ×           |
| a | (Explorer, Actions,  |                                   | Properties of the |
| i | Variables, Triggers, |   canvas / form / workflow /      | selected step or  |
| l | Assistant)           |   AI agent editor                 | item              |
|   |                      |                                   |                  |
|   |                      +-----------------------------------+                  |
|   |                      | Bottom panel: Run output · Problems · Debug values   |
+---+----------------------+-----------------------------------+------------------+
```

- **Editor tabs**: several items open at once (today only one).
- **Search everything** (Ctrl+K): items, actions, variables and commands in one box.
- **New item dialog** instead of browser prompts: item types as cards with a one-line description (Agent, AI agent,
  Workflow, Form, Document type, Prompt Skill, API agent, Python or PowerShell script) and templates.
- **Problems panel**: validation results always visible, click to jump to the step.
- **Plain words everywhere**: no codes on screen; codes only in tooltips for experts.

Clickable mockups of four screens (the workbench, New item, the AI agent editor and the model connection wizard) are
on the design canvas https://claude.ai/artifact/HafD989gNmovYiwHkHmZrB, in OOA's own tokens. The design check
showed that white text on the teal and cyan accents is under 4.5:1; the mockups use darker shades
(`#0f766e`, `#0e7490`). The new components use those shades wherever white text sits on teal or cyan; the
lighter ones stay for icons, where 3:1 is enough.

New and changed screens from the V6 gap list that live in Studio:

| Gap | In Studio |
| --- | --- |
| V6.6 AI agent editor | New item type. Prompt first, expanded into role, goal and plan; tabs Model, Inputs and outputs, Tools, Limits, Test; templates |
| V6.5 Model connections | Provider cards, model list, separate key fields, Test, disable |
| V6.7 Filters and saved views | Explorer, action library and run history |
| V6.11 Wizards with drafts | New agent from template, new connection, new trigger |
| V6.2 Work for people | Forms and workflows grouped under one "Work for people" section of the New dialog and Explorer filter |

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
