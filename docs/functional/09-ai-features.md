# 09 AI features

## What this covers

The AI capabilities of ONEOFFICEAUTOMATION and the controls around them: One Model Connections (providers, roles,
budgets and limits, personal-data masking, content check, connection test), AI governance, the AI calls of a run,
Prompt Skills, the generative AI, AI agent and web goal agent actions, Knowledge Q&A, document extraction with
confidence, the Studio assistant, and AI assistants connecting over the Model Context Protocol (MCP).

## Contents

1. One Model Connections
2. Budgets and call limits
3. Personal-data masking and the content check
4. Testing a connection
5. How connections reach a run
6. AI calls of a run and AI governance
7. Prompt Skills
8. Generative AI actions
9. AI agents
10. Web goal agent
11. Knowledge Q&A
12. Document intelligence and confidence
13. The Studio assistant
14. AI assistants over MCP
15. Retention, permissions and audit

## 1. One Model Connections

One Model Connections (AI > One Model Connections, `connections.manage`) are named AI model connections that agents
choose by name in the Prompt Skill, AI agent, generative AI, knowledge and web goal agent actions.

| Field | Rule |
| --- | --- |
| Name | Letters, digits, `.`, `_`, `-`, up to 60 |
| Provider | `openai`, `azure_openai`, `anthropic`, `aws_bedrock`, `aws_bedrock_sagemaker` (a model on an Amazon SageMaker endpoint), `google_vertex`, `huggingface`, `openai_compatible` (local or hosted servers with the OpenAI interface) |
| Model, endpoint | The model id and, where needed, the endpoint (empty: the provider's default) |
| Key | A vault reference (`folder/credential/attribute`), never the key itself |
| Type | `standard` or `fine_tuned` |
| Roles that may use it | Empty: every role. Otherwise runs of people with none of these roles do not receive the connection (administrators always do). Every role named must exist. |
| Description | Free text |
| Tokens a month, calls a minute, calls a run | 0 = no limit (section 2) |
| PII analyzer address | Optional Microsoft Presidio analyzer, `https://` (or `http://` on this computer only) |
| Content check | `none` or `moderation` (OpenAI, Azure OpenAI and OpenAI-compatible only) |

Provider notes: Amazon Bedrock takes the model or inference profile id, the endpoint
`https://bedrock-runtime.REGION.amazonaws.com` and the key `ACCESS_KEY_ID:SECRET` in one credential. Google Vertex AI
takes the model, the endpoint `https://REGION-aiplatform.googleapis.com/v1/projects/PROJECT/locations/REGION`, and a
service-account JSON key (exchanged for an access token) or an access token.

The list shows each connection's provider, model (with a Fine-tuned tag), roles, endpoint, tokens used this month
against its budget, key reference and last change.

## 2. Budgets and call limits

| Limit | Range | When reached |
| --- | --- | --- |
| Tokens a month | 0 to 10^12 | Input plus output tokens through the connection since the first day of the month (UTC), from the AI calls runs report. When used up, the connection reaches runs as blocked and every AI step through it fails with `AI.BUDGET_REACHED` until the next month or a higher budget. |
| Calls a minute | 0 to 100,000 | A run that would pass it waits |
| Calls a run | 0 to 1,000,000 | The step that would pass it fails with `AI.CALL_LIMIT` |

## 3. Personal-data masking and the content check

- **Hide personal data** (an option of the AI actions) masks personal data in prompts before they leave the runner and
  puts the values back in the answer. With a Presidio analyzer address on the connection, what Presidio finds (names,
  places, ids, in many languages) is added to the built-in masking. The counts of what was masked appear in the AI calls
  and AI governance.
- **Content check** `moderation` sends each answer to the provider's moderation endpoint; a flagged answer fails the
  step with `AI.CONTENT_FLAGGED`, naming the categories.

## 4. Testing a connection

**Test connection** sends one very small request ("Answer with OK") with the key read from the vault, for that request
only, and shows the answer and the time. The tester must be a consumer of the key's vault folder. The test is audited
(`connection.test`), as are saves and deletes.

## 5. How connections reach a run

When a runner claims a run, it receives the connections the run's user may use (by role), with keys as vault references
only, plus their limits, analyzer address and content check. The first allowed connection is the default. Keys are
fetched just in time as the run's user. Studio test runs use Studio's own One Model Connections (Studio top bar).

## 6. AI calls of a run and AI governance

Every model call made by a run is reported with the run's result and kept:

| Recorded | Not recorded |
| --- | --- |
| Time, agent, person, purpose, provider, model, connection, input and output tokens, answer time, what was masked, the tools the AI asked for, a SHA-256 fingerprint of the prompt | The prompt and the answer |

- A run's details have an **AI calls** tab listing its calls in order. A run reports at most 500 calls.
- **AI governance** (AI > AI governance, `audit.view`) lists every AI call over a chosen period, with totals by model,
  agent and person, masking and tool use, and the latest calls with their fingerprints.

## 7. Prompt Skills

Prompt Skills (AI > Prompt Skills) are reviewed prompt templates that agents run with the Prompt Skills toolkit
(`ooa.ai_skills/run`).

| Property | Rule |
| --- | --- |
| Name | Letters, digits, `-`, `_`, up to 80 |
| Content | Instructions for the model, a prompt with `${name}` inputs (required), the inputs (name, description, default), the answer type `text` or `json`, a temperature, and optionally a connection |
| Size | At most 100,000 characters |
| Sharing | A new skill is private to its owner until **Shared** |
| Versions | Every save is a new version with a comment; earlier versions stay readable |
| Changes | Only the owner, or someone with `skills.all` |
| From a template | Starts a private skill from a built-in template (summary, request routing, invoice fields) or a template an administrator published |

Runs receive the skills their agents name (shared skills, and the run's person's own private ones). When a step names
the skill through a variable, every skill the person may use is sent. A skill in the Command Center wins over a skill
file of the same name in the runner's workspace. A missing skill fails the run before it starts.

Permissions: `skills.view` to see and use, `skills.manage` to create and change, `skills.all` to change anyone's.

## 8. Generative AI actions

The generative AI toolkit (`ooa.genai`) sends prompts to a connection for tasks such as summarising, classifying,
extracting, translating and analysing sentiment, entities and language. Every call goes through One Model Connections,
so roles, budgets, limits, masking, content checks and the AI calls record apply.

## 9. AI agents

**Run AI agent** (`ooa.ai_agent/run`) lets a model reach a goal by calling agents as tools.

| Input | Default | Meaning |
| --- | --- | --- |
| goal | required | What to achieve |
| instructions | "You are a careful business assistant …" | Role, policy and rules |
| role, action_plan | none | Who the agent acts as; the order in which to use the tools and when to stop or ask |
| tools | required | Ids of the agents it may call (their descriptions and inputs tell the model what they do) |
| sensitive_tools | none | Agent ids that need a person's approval before each call; unattended runs refuse such approvals |
| max_steps | 8 | Maximum model turns |
| max_tokens_total | 60,000 | Stop when this many tokens have been used |
| max_duration_seconds | 0 (none) | Stop with `limit_reached` after this time |
| allow_cancel | false | Let the agent end with `cancelled` when the goal should not be pursued (with output fields only) |
| temperature | 0 | 0 = consistent |

Outputs: `answer`, `status` (`completed`, `limit_reached`, `stopped`; with output fields also `failed` or `cancelled`),
`outputs` (requested output fields), `trace` (each model turn and tool call) and `tokens`. The action's time limit is
30 minutes. Tool agents travel with the run like child agents.

## 10. Web goal agent

The web goal agent (`ooa.web_agent`) works a web page from a plain-language goal: **Start web agent session** opens a
browser (start page, browser, headless, proxy with a vault password, another browser program), **Reach a goal** lets
the model work the page until the goal is reached, cannot be reached, or the step limit, and **End web agent session**
closes the browser. The session can be used by ordinary Browser actions too. Vault values marked Password fields only
are typed into password fields only.

## 11. Knowledge Q&A

The Knowledge Q&A toolkit (`ooa.knowledge`) answers questions from a company's own documents.

| Action | Behaviour |
| --- | --- |
| Build index | Reads files and folders (text, Markdown, HTML, CSV, JSON, XML, Word `.docx`, and PDF on runners with the PDF toolkit) and texts an agent already has; splits them into passages (1,000 characters by default, overlapping by 150); with an embeddings connection (OpenAI, Azure OpenAI, OpenAI-compatible, Vertex AI, Bedrock Titan and Cohere) adds a vector per passage; writes one index file (`.json` or `.json.gz`) |
| Search | Returns the best passages for a question: always by words (BM25), and by meaning when the index has vectors |
| Ask | Gives the best passages to a chat model, which answers from them only, cites them like `[1]`, and says when the documents do not say; when no passage matches, the model is not called |

Keep the index as a repository file so runs that need it receive it; rebuild it when documents change.

## 12. Document intelligence and confidence

Document intelligence items (Studio) define the fields to extract from documents with AI. Extract fields with a
*Min confidence* (for example 0.8) returns `needs_review` and `validation` with each field's confidence; returned as an
output named `validation` from a work queue agent, it sends the item to **To validate** ([document 05](05-work-queues.md)).

## 13. The Studio assistant

The **Assistant** in Studio (needs a model connection in Studio) has two tabs: **Build from a description**, which
proposes steps and variables for a description and inserts them at the insertion point when accepted (new variables
only when the name is free; inserted steps get fresh ids and stay selected for review), and **Explain this agent**.

## 14. AI assistants over MCP

| Rule | Detail |
| --- | --- |
| Address | `https://<address>/api/mcp` |
| Sign-in | A user's API key, or an OAuth access token from the company's identity provider (the single sign-on issuer) |
| OAuth checks | Signature, issuer, audience (`mcp_oauth_audience`), expiry and, when set, scope (`mcp_oauth_scope`); the token must belong to an existing, enabled account; it works for MCP only, never as a console session. Assistants find the issuer at `/.well-known/oauth-protected-resource`, and a 401 points them there. |
| What is offered | The agents an administrator lists (Settings > AI assistants, `mcp_agents`), that the user may see, with their inputs as the tool's input schema; Credential inputs are never offered |
| Runner, wait | The runner given in `mcp_runner`; each call waits up to `mcp_timeout_seconds` (10-3600, default 120) |
| Runs | Normal runs with source `mcp` at priority 5, as that user and with their permissions, in Agent Runs and the audit trail |

## 15. Retention, permissions and audit

- `ai_call_retention_days` (Settings, 0 keeps them) removes older AI call records once an hour (`ai.calls_purged`).

| Action | Permission |
| --- | --- |
| Manage and test model connections | `connections.manage` |
| See AI governance | `audit.view` |
| See and run Prompt Skills | `skills.view` |
| Create and change own Prompt Skills | `skills.manage` |
| Change anyone's Prompt Skills | `skills.all` |

## Related documents

- [02 Toolkits and actions](02-toolkits-and-actions.md)
- [05 Work queues](05-work-queues.md)
- [07 Vault, credentials and connections](07-vault-credentials-and-connections.md)
- [12 Agent tests](12-agent-tests.md) (checks suited to AI answers)
- [Command Center and runner, section 9](../CONTROL_ROOM_AND_AGENT.md)
