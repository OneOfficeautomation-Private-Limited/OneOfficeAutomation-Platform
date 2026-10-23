# Reference product Community Edition: screens and AI packages compared with ours

Checked on 2026-10-23 in the Community Edition Control Room (signed in as the project owner). Nothing was saved
there: the create forms were opened and cancelled. This is a summary in our own words; no text, images or files
from the reference product are copied into the product.

## 1. Control Room screens

The Community Edition menu has these pages:

- **Explore** and **Home**
- **Automation** (the repository)
- **AI:** Skills, Model connections, Document Automation
- **Operations:** Activity, Bot Insights
- **Manage:** Devices, Global values, Credentials, Packages
- **Administration:** Users

| Page | What it offers | Ours | Gap |
| --- | --- | --- | --- |
| Automation | Folder tree with sub-folders; files and folders list with search, filters, column chooser; **Upload files** (non-bot files kept in the repository); delete checked items; Manage > Import. Bot row actions: **Move**, View insights, View, Edit, **Copy**, View dependencies and references, Delete. | Folders, folder permissions, rename folder, delete/recover, labels, versions, dependencies, statistics, export/import/promote | **Move one agent** to another folder; **Copy an agent**; **files in the repository** (templates, Excel files agents use) |
| AI > Skills | Reusable AI prompts kept as files in the repository (public and private workspaces), with **Templates**; created with name, description and folder, then edited (model connection, prompt, inputs) | Prompt Skills: JSON files in the Studio workspace (`ai-skills/`), run by `ooa.ai_skills/run` | **Skills in the Command Center** (shared, versioned, with templates, editable in the console) |
| AI > Model connections | Wizard: name, description, vendor (Amazon Bedrock, Anthropic, the vendor's own models, Azure OpenAI, Google Vertex AI, OpenAI), type (standard or fine-tuned), model; then sign-in from the vault, a test, and which roles may use it | One Model Connections: OpenAI, Azure OpenAI, Anthropic, OpenAI-compatible, Hugging Face, SageMaker; key from the vault | **Google Vertex AI** and **Amazon Bedrock** providers; **test connection**; **roles allowed** to use a connection |
| Operations > Activity | In progress / Historical; columns: status, type, name, priority, running time, progress, current action, device, run-as user, team or role, user; column chooser; CSV export | Agent Runs: live and history with progress, current step, run-as, filters, CSV | Column chooser (minor) |
| Devices | Columns: status, name, platform, default users, nickname, device type (single or multi-user), lifespan, log level, install type, agent version, pool, "my default device"; Run, **Connect local device**, **Update checked items** (for example log level), CSV | Runners: status, running, agent version, last seen; registration key; rename, disable, remove; default runner per user | **Log level per runner** (set remotely); **default users shown on the runner**; runner type (single/multi-user) |
| Global values | Name, type, **changeable scope** (each user may set their own value), modified | Shared values (one value for everyone) | **Values people may change for themselves** |
| Credentials | Tabs: Credentials, Lockers, **Credential requests**. A credential can exist outside a locker (a locker is needed for bots to use it). Attribute: name, description, Standard or User-provided, **Masked**, **only for password fields**. Locker consumers are asked to fill in user-provided values | Vault folders with owners/managers/participants/consumers, credential owner, user-provided attributes ("Your values"), key rotation | **Credential requests inbox** (user-provided values still to fill in); **attribute description**; **Masked** and **password-fields-only** flags |
| Packages | 178 packages, each with versions, a default version, recommended agent and Control Room versions, action and trigger counts, enabled | Toolkits: one version each, switch on/off, used-by, version ranges per agent | Several versions of a toolkit side by side (our packaging installs one version per toolkit; not planned) |
| Users | Columns: user type, user name, description, first and last name, email, **several roles**, last login, API key expiry | Users: one role, display name, email, default runner, delete | **Several roles per user**; first/last name and description; API key expiry shown |

## 2. AI packages (actions)

| Package | Actions | Ours |
| --- | --- | --- |
| Generative AI | Per provider (AI21, Anthropic, Stability AI and Amazon Titan on Bedrock; Vertex AI; Azure OpenAI; OpenAI): sign-in/connect/disconnect, **Chat**, **Prompt**, **MultiModal** (text with images), **Text to image**, OpenAI **Run Assistant** | `ooa.genai`: Prompt, Chat, End chat, Summarise, Classify, Extract fields, Translate, Sentiment, for OpenAI, Azure OpenAI, Anthropic, OpenAI-compatible, Hugging Face and SageMaker | **Images in prompts and chats** (multimodal); **image generation**; **Vertex AI** and **Bedrock** providers. Assistants: the provider is retiring that API, so we do not copy it |
| AI | SageMaker: sign-in, invoke endpoint; Hugging Face: sign-in, serverless inference | SageMaker and Hugging Face through `ooa.genai` | none |
| AI Skill | Execute a saved skill (plus planner and evaluation commands used internally) | `ooa.ai_skills`: Run, List | Skills kept in the Command Center (see above) |
| AI Agent (Local) | Run an AI agent on a workstation | `ooa.ai_agent/run` (tools are agents; governance, masking) | none |
| UI Agent – Web | Start session, **Run** (a goal in plain words; the AI finds and uses the page's controls), End session | none | **A goal-driven web agent** |
| OpenAI (older) | ChatGPT, GPT-3, Azure variants | covered by `ooa.genai` | none |
| Google Document AI / Document Extraction | Connect, Extract, get/update/download document data | PDF/OCR toolkits, `ooa.genai/extract`, Studio's document template | Document Automation (IDP with learning instances) is a product of its own: not planned now |
| AWS Comprehend NLP | Key phrases, detect language, named entities, sentiment | Sentiment, classify, extract | **Detect language, key phrases, named entities** |
| Aisera | Ingest content, ask questions of it | none | Knowledge Q&A over uploaded content: a candidate for later |

## 3. Proposed next steps (by value)

1. **Repository:** move and copy agents; files in the repository.
2. **AI:**
   - images in prompts and chats;
   - image generation;
   - Vertex AI and Bedrock providers;
   - detect language, key phrases and named entities;
   - a goal-driven web agent (start session, run a goal, end session) on top of the browser toolkit.
3. **Model connections:** test the connection; restrict a connection to roles.
4. **Vault:**
   - credential requests (the user-provided values still to fill in);
   - an attribute description;
   - Masked and password-fields-only flags (Studio offers password-only attributes only in password fields).
5. **People:** several roles per user; first/last name and description.
6. **Shared values** that people may change for themselves.
7. **Runners:** log level set remotely; default users shown on the runner; single or multi-user type.
8. **Skills in the Command Center:** shared, versioned prompt skills with templates, edited in the console.

## 4. Status

All eight groups above are built (see the last section of `docs/VALIDATION_REPORT.md`). Still not planned: several
versions of one toolkit side by side, Document Automation with learning instances, the retired Assistants API, and
knowledge Q&A over uploaded content.
