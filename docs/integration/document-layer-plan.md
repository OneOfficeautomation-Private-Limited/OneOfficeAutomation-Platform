# Document Automation: our own document layer (plan DA-1 to DA-7)

The user approved this plan on 15 Nov 2026 ("our own document layer with the phases given; even if a session ends,
resume in the next session and continue developing"). It builds on the agreement with DocFlow in
[document-automation-contract.md](document-automation-contract.md). Every session that works on it starts here:
pick the first phase whose status is not **Done**, follow its list, and update its status line when it is finished.

## Rules for every phase

- Additive only. Nothing that works today may break; DocFlow's API v1 only grows, and anything new is v2 beside v1.
- One branch per phase: `docauto/da<N>-<short-name>` from `origin/master` (platform) and, when DocFlow changes,
  `da<N>-<short-name>` from `main` in oneoffice-docauto. Commit, push, and watch CI. **No merge or release without the
  user's OK.**
- Screens: show the design on the design canvas first (artboards, step by step) and wait for the user's choice
  before building it.
- Tests with every change: DocFlow's pytest suite, the platform's DocFlowProxyTest / DocFlowLiveTest, and the UI tests
  (`platform/ui`, `npx vitest run`). Check each screen change in the browser at full width.
- Words on screen: plain English for people without IT experience; technical details only under **Advanced**.
- A DocFlow change becomes a DocFlow release (VERSION, tag `vX.Y.Z`, the user's OK); then move the platform's pin
  (`modules/document-automation/src/main/resources/docflow-compat.properties`, the compose files, the docs).

## The layer

| Layer | What it is |
|---|---|
| Intake | Upload, email, watched folder, agents, API, MCP: one way in |
| Engine adapters | Every vendor (Tesseract, PaddleOCR, Azure, Google, AWS Textract, ABBYY, Bedrock Data Automation, Claude, OpenAI, Gemini, self-hosted) maps its output into our model; keys come from the platform's Model connections |
| Our document model | Pages and elements with ids; fields with value, typed value, how sure, where on the page, which engine/model and its cost; validation; review history; split batches; e-invoice data; personal-data tags; a versioned schema per document type |
| Storage | DocFlow keeps the full results and encrypted files (retention per customer); the platform keeps a small index |
| Views | One viewer and review screen that draws boxes for any engine; engine comparison; accuracy reports |
| Use | Agents get a typed Document; document events start agents; MCP tools; One Chat questions; Excel, webhooks, business systems |
| Governance | Residency and cloud opt-in per customer, approved models, personal data masked before cloud models, every AI call in the platform's audit (`ai_calls`), costs to licensing and billing |

## DA-1 Ease of use (platform UI) — status: In progress (branch `docauto/da1-ease-of-use`, not merged)

Done (15 Nov 2026, the user chose plan B: "Show advanced settings" per section, no page-wide switch): items 1-3 (advanced
sections on Document types, steps 4 and 6, the routing card and the Test lab; checks in words, the built-in Saudi
patterns named), item 4 (the vault picker for mailbox secrets, and **Sign in with Microsoft**), items 6-7 (templates
and Try it with a sample), item 8 (Engines: "On this server", "Cloud reading", "AI reading" with the OneCore /
OneConnect / OneMind labels; "Ready", "Off: cloud not allowed", "Needs a key", "Needs setting up"). Commits 0375dc67,
de22f01d, f8120b1c, 2cf943a5, 03fbf3f1. Checked in the browser against DocFlow 3.1.2 (Sign in with Microsoft against a
fake Microsoft); UI tests 493/493, module tests green.
Left: item 5 (a watched-folder picker: needs a folder-browse endpoint on DocFlow's server, limited to allowed
folders); and the user registering OOA's Microsoft app in Entra, then its Application (client) ID goes into
`MicrosoftSignIn.OOA_APP_ID` (until then an administrator sets Settings > Document Automation > Microsoft app ID).

**Sign in with Microsoft: built (user chose "One OOA app", 15 Nov 2026; commit 03fbf3f1).** The platform reads the
inbox (DocFlowProxy.pollMail), so DocFlow needed no change. OAuth device code flow with a multi-tenant public app;
MicrosoftSignIn keeps the device code and refresh token encrypted with the vault key (table docflow_ms_signin, module
schema 2); only the person who signed in can put the sign-in in a process or change its mailbox or folder;
ModuleContext.graphMailbox(accessToken, mailbox, folder); OOA_MICROSOFT_LOGIN_URL / OOA_MICROSOFT_GRAPH_URL for
national clouds; docs/DOCUMENT_AUTOMATION.md 3.1. Not done: the same button on Microsoft 365 email triggers (step 6,
optional).
App registration for the user (Microsoft Entra admin centre > App registrations > New registration): name "One
CommandCenter"; "Accounts in any organizational directory (multitenant)"; no redirect URI; Authentication > "Allow
public client flows" = Yes; API permissions > Microsoft Graph > Delegated: Mail.Read, Mail.Read.Shared, User.Read,
offline_access. Later, optionally, publisher verification (Microsoft partner ID).

Designs on the canvas first, then:
1. A **Simple / Advanced** switch on the Document Automation screens (remembered per person; Simple for new users if
   the user agrees). Simple hides format patterns (regex), field thresholds, routing, engine A/B and JSON.
2. Plain words in Simple: "how sure" for confidence, "check by a person below" for threshold, "read the text" for
   OCR, "send results to a web address" for webhook; the technical word stays in the help text.
3. Document types: the **Format check** column shows a description ("Saudi IBAN", "Date") with the pattern under
   Advanced; a list of ready-made checks instead of typing a pattern.
4. Email inbox: a vault **picker** (the folders and credentials the person may use) instead of typing
   `folder/credential/attribute`; for Microsoft 365, a **Sign in with Microsoft** button (OAuth device or redirect
   flow) instead of tenant and client ids, with the manual fields under Advanced.
5. Watched folder: a folder picker (the runner's or server's folders) instead of typing a network path.
6. **Templates** for new processes: "AP invoices to Excel", "Receipts for expense claims", "Bank statements",
   "Purchase orders", "ID documents"; each fills the 8 steps, and the person only names it and chooses where results go.
7. **Try it with a sample**: built-in sample documents per template, read in the Test lab without saving.
8. Engines in Simple: three cards (On this server, Cloud reading, AI reading) with "Ready / needs a key / off";
   OneCore/OneConnect/OneMind names stay as labels.
Done when: a person who has never seen it can make a process from a template, try a sample, and review a document
without typing any technical value (walk-through in the browser at full width), and all tests pass.

## DA-2 One place for AI keys, every AI call audited — status: Not started

1. DocFlow asks the Command Center for an engine's credentials when it needs them: `POST
   /api/docflow-callback/credentials` (service token + tenant) with the engine id; the Command Center answers from
   **Model connections** (vault `key_ref`; providers anthropic, openai, azure_openai, aws_bedrock, google_vertex,
   openai_compatible) chosen per tenant in Settings > Document Automation (new setting: which connection each
   engine uses). Short-lived cache in DocFlow memory only; never written to disk.
2. Engine keys typed into DocFlow's Engines page keep working (fallback) until the user retires them.
3. DocFlow reports each AI call (engine, model, tokens or pages, cost, document, process, masked prompt hash) to
   `POST /api/docflow-callback/ai-calls`; the Command Center records them in `ai_calls` (purpose `document`), so
   Agent Insights and audit show them.
4. Per-customer keys in SaaS (folds in plan phase 7): a tenant chooses **our keys** (credits from the usage meter)
   or **their own** Model connection.
5. Contract: both callbacks added to PLATFORM-CONTRACT.md and the platform contract document; DocFlow release.
Done when: a Claude extraction runs with a key that exists only in the platform vault, its call appears in
`ai_calls`, and DocFlowLiveTest covers it.

## DA-3 Our document model v2 — status: Not started

1. DocFlow: element ids on words, lines, cells, key-values, checkboxes, signatures, barcodes; each field gets
   `citations` (element ids, page, box), `provenance` (engine, model, version, prompt hash, cost) and `normalized`.
2. A JSON Schema per document type (`GET /api/docs/v2/doctypes/{id}/schema`) and per result (`/api/docs/v2/...`).
3. v1 responses unchanged; v2 beside them; contract tests for both.
4. Adapters for each existing engine fill the v2 fields (Azure, Google, Textract, Tesseract, PaddleOCR, Claude,
   OpenAI-compatible, self-hosted).
Done when: every engine's result validates against the v2 schema in DocFlow's tests.

## DA-4 Agents use documents — status: Not started

1. The Command Center's `/api/docflow-callback/events` (DocFlow already posts processed, needs_review, approved,
   rejected, delivery_failed, review_overdue); replaces the 20-second review polling where possible.
2. A **Document** variable type for agents (fields, tables, how sure, status, link) and the toolkit actions
   Send document / Wait for document / Get document / Mark collected returning it.
3. A trigger **When a document is processed** (by process and status) that starts an agent with the Document.
4. A small platform index of documents (id, process, status, key fields, time) for search and Insights.
Done when: an agent started by a document event reads its fields as a typed Document (end-to-end test).

## DA-5 New engines — status: Not started

1. Claude through **Amazon Bedrock, Google Vertex AI and Microsoft Foundry** (same connector, provider chosen by the
   Model connection), and Claude reading PDFs directly with page citations.
2. **Amazon Bedrock Data Automation** (blueprints) and **AWS Textract** async for large files.
3. **Azure AI Content Understanding** (analyzers with schemas) and Azure OpenAI through Model connections.
4. **Google Gemini** (direct PDF reading, structured output) and Document AI custom extractors.
5. Each with contract tests on vendor-shaped fixtures, cost per page in its manifest, residency region.
Done when: each engine passes its contract tests and shows in Engines with Ready / needs a key.

## DA-6 Volume — status: Not started

1. Batch processing for AI extraction (Claude Message Batches at half price; OpenAI/Gemini batch where offered).
2. More DocFlow workers and a queue for large intakes; progress shown per batch.
3. Review in bulk (approve all that pass checks), assignment rules, second-person check.
Done when: 1,000 documents run through a process with batch extraction in a test, and bulk review works.

## DA-7 Deployment — status: Not started

1. The Helm chart (plan phase 6): platform-wide, DocFlow optional, one DocFlow replica until its store is shared.
2. SaaS regions India / US / EU with DocFlow per region (plan phase 8), backups and restore tested.
Done when: a test cluster installs, upgrades and restores with DocFlow.

## How to resume

1. Read this file, `docs/integration/document-automation-contract.md` and the memory note `docauto-integration`.
2. `git fetch`; find the phase branch (`docauto/da*`) if one exists and continue it; otherwise start the next phase.
3. Local test setup: `C:\OOA\OneOfficeAutomation-Platform` (master, built with `gradlew :cli:installDist`), server
   started with `platform\cli\build\install\ooa\bin\ooa.bat server --no-browser --data "%USERPROFILE%\OOA Control Room"`,
   which starts DocFlow from `C:\OOA\oneoffice-docauto` (main).
