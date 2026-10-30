# AI in agents

**Audience:** agent builders who want agents to read, write, classify and decide with large language models, safely and
within budget.

## Contents

1. [What you can do with AI](#1-what-you-can-do-with-ai)
2. [Set up a model connection](#2-set-up-a-model-connection)
3. [Generative AI steps](#3-generative-ai-steps)
4. [Prompt Skills](#4-prompt-skills)
5. [AI agents that use your agents as tools](#5-ai-agents-that-use-your-agents-as-tools)
6. [The web goal agent](#6-the-web-goal-agent)
7. [Knowledge Q&A](#7-knowledge-qa)
8. [Document extraction with validation](#8-document-extraction-with-validation)
9. [The Studio assistant](#9-the-studio-assistant)
10. [Good practice: masking, budgets, testing](#10-good-practice-masking-budgets-testing)
11. [If it goes wrong](#11-if-it-goes-wrong)
12. [Related guides](#related-guides)

---

## 1. What you can do with AI

The **AI** section of the Actions panel has four groups:

| Group | Use it to |
| --- | --- |
| **Generative AI** | Prompt a model; chat; work with pictures; summarise, classify, translate, extract fields, sentiment, language, key phrases, named entities; generate pictures |
| **Prompt Skills** | Run a reviewed, reusable prompt by name |
| **Knowledge Q&A** | Answer questions from your own documents, with citations |
| **AI agents** | Let a model reach a goal by calling your agents as tools |

The **Web goal agent** (under **More toolkits**) works a web page towards a goal you describe.

Supported providers: OpenAI, Azure OpenAI, Anthropic, an OpenAI-compatible server (local or hosted, for example
Ollama), Hugging Face, Amazon Bedrock, a model on an Amazon SageMaker endpoint, and Google Vertex AI.

## 2. Set up a model connection

Instead of repeating the provider, model and key on every step, name the settings once as a **model connection**.

**In Studio (for your local runs):**

1. Open the **Theme and density** menu (sun icon) in the top bar and choose **Model Connections…**.
2. Click **+ Connection**. For each connection set:
   - a **name** (for example `gpt`), and **Default** for the one used when a step names none;
   - **Provider** and **Model** (for example `gpt-4o-mini`);
   - **API key from**: *Environment variable* (its name, such as `OPENAI_API_KEY`), *Credential vault credential*
     (`vault folder/credential/attribute`) or *No key (local model)*;
   - **Endpoint (optional)**: for Azure or a local server.
3. Click **Save**. The settings go to `model-connections.json` in your workspace; keys are never stored there, only where
   to find them.

**In One CommandCenter (for runs on runners):** AI > **Model Connections**. Administrators also set which roles may
use each connection, monthly token budgets, call limits, personal-data detection and content checks. Runs receive the
connections they are allowed to use.

**In a step:** AI steps have a choice **Model to use**: *A saved connection* (type its name in **Connection**) or *Set up
here* (**Provider**, **Model**, **API key**, **Endpoint address**, and for Bedrock **AWS access key id** and
**AWS region**).

## 3. Generative AI steps

| Action | Main fields | Gives |
| --- | --- | --- |
| **Prompt** | **Prompt**, **System** (instructions) | The answer text (or JSON with **Response format**) |
| **Chat** | **Message**, **Chat session**, **System**, **Images** | The reply; the session remembers earlier turns until **End chat** |
| **Prompt with pictures** | **Prompt**, **Images** | The answer about the pictures |
| **Summarise** | **Text**, **Sentences** | A summary |
| **Classify** | **Text**, **Labels** (a list such as `["urgent","normal","spam"]`) | The chosen label |
| **Extract fields** | **Text**, **Fields** (name to description) | A Dictionary of values (see section 8) |
| **Translate** | **Text**, **Target language** | The translation |
| **Sentiment**, **Detect language**, **Key phrases**, **Named entities** | **Text** | The result |
| **Generate picture** | **Prompt**, **Output folder**, **Number of images** | Picture files |

More options include **Temperature** (0 is the most predictable), **Max tokens**, **Hide personal data** and
**Timeout seconds**.

**Example: sort support emails.**

1. **Find emails** and loop over them; **Read email** into `$email`.
2. **Classify** with **Text** `${email.subject}\n${email.text}` and **Labels** `["billing","technical","sales"]`; save
   the label in `$category`.
3. **If** `$category` **equals** `billing` → **Move email** to the Billing folder; and so on.

The sample agent `O5-ai-ticket-triage` in `examples/use-cases` (and the *Sorting support tickets with AI* template) is a
complete version.

## 4. Prompt Skills

A Prompt Skill is a prompt that someone has written and reviewed once, kept as a named item, and run by many agents.

**Create one in Studio:**

1. **+ Create > Prompt Skill**, give a name (for example `invoice_summary`). It goes into the `ai-skills` folder.
2. In the designer:
   - **Description**: what the skill does; shown in the Prompt Skills list.
   - **Instructions (system)**: the role and rules for the model.
   - **Prompt template**: the prompt, with `${name}` where values go. The placeholders found are shown as chips.
   - **Model**: **Provider**, **Model**, **Temperature**, **Base URL (optional)**.
   - **Answer format**: **Text** or **JSON (Dictionary)**.
   - **Mask personal data (emails, phone numbers, card numbers) before sending** (on by default).
3. Save (Ctrl+S).
4. Click **Copy as an agent step**, open an agent and press Ctrl+V: you get a **Run Prompt Skill** step with **Skill**
   set and one **Values** entry per placeholder. Fill them in, for example `{"text": "$invoice_text"}`.

API keys never go into skill files: give the step a **Connection**, or an **API key** credential.

In One CommandCenter (AI > **Prompt Skills**), skills are shared, versioned and can start from templates. A skill kept
there wins over a file of the same name in a runner's workspace.

## 5. AI agents that use your agents as tools

**Run AI agent** (group **AI agents**) gives a model a goal and a set of your agents as **tools**. The model decides
which tools to call, in which order, with which inputs, until the goal is reached.

1. Build small, well-described agents to act as tools, for example `lookup_order` and `check_refund_policy`. The model
   reads each agent's **Description** and its input and output descriptions, so write them carefully.
2. Add **Run AI agent**:
   - **Goal**: what to achieve, for example "Handle this customer email: ${email.text}".
   - **Instructions**: rules ("You are a careful business assistant. Use the tools to act; never invent results." by
     default).
   - **Tools**: the agent ids, `["lookup_order", "check_refund_policy"]`.
   - **Model to use**: a saved connection or set up here.
   - More options: **Sensitive tools** (agents that need a person's approval before each call, such as `issue_refund`),
     **Role**, **Action plan** (the order to use the tools and when to stop, in plain words), **Output fields** (values
     it must return, name to description), **Allow cancel**, **Max steps** (8), **Max tokens total** (60000),
     **Max duration seconds**, **Temperature**, **Hide personal data**.
3. Outputs: **answer**, **status** (`completed`, `limit_reached` or `stopped`; with output fields also `failed` or
   `cancelled`), **outputs**, **trace** (each model turn and tool call, for review) and **tokens**.

Check the tool agents in to One CommandCenter too. In unattended runs, approvals of sensitive tools are refused.

## 6. The web goal agent

The **Web goal agent** works a web page for you: it looks at the page and clicks, types, selects, scrolls and goes to
addresses until the goal is reached.

1. **Start web agent session**: **Address (URL)**, **Browser**, **Headless**.
2. **Reach a goal**:
   - **Start url** and **Goal** in plain words, for example "Search for invoice ${invoice} and tell me its total and due
     date.";
   - **Secrets**: name to Credential, for example `{"password": "$portal_password"}`. The model types them as
     `{{secret:password}}` and never sees the values. Vault attributes marked **Password fields only** are typed into
     password fields only;
   - tick **Return the findings in a fixed shape** for structured results;
   - More options: **Allowed sites** (host names it may visit; any site when empty), **Instructions** (for example
     "Never press Delete or Pay."), **Max steps**, **Hide personal data**, **Save trajectory to**.
3. **End web agent session**.

Outputs: **success**, **answer**, **data**, **steps** (each step in words), **url**, and the tokens used.

> **Tip:** Always set **Allowed sites** and clear rules in **Instructions** for goal agents that can change data.

## 7. Knowledge Q&A

Answer questions from your company's documents:

1. **Build index**: **Files** (files and folders: text, Markdown, HTML, CSV, JSON, XML, Word .docx and PDF) and/or
   **Texts** you already have; **Index file** (for example `knowledge/policies.json`). Choose **Search by meaning too**
   (a connection with an embedding model) to find passages by meaning, not only by words. Rebuild when documents change.
2. **Search**: **Index file**, **Query**, **Top k**: returns the best passages.
3. **Ask**: **Index file**, **Question**, **Model to answer with**: the model answers from the passages only, cites them
   like `[1]`, and says when the documents do not cover the question. When nothing matches, the model is not called.

Keep the index file in One CommandCenter's repository files so runners get it with the run.

## 8. Document extraction with validation

### 8.1 Extract fields

**Extract fields** takes **Text** and **Fields**, a Dictionary of name to description:

```json
{"invoice_number": "the invoice id", "total": "amount due as a number", "due_date": "due date as yyyy-mm-dd"}
```

and returns **fields**, a Dictionary of values. Read them as `$fields.total`.

Get the text first: **PDF > Extract text** for digital PDFs, **OCR** for scans, **Word > Extract text from a file**,
or the body of an email.

### 8.2 A document intelligence item

1. **+ Create > Document intelligence**, name it (for example *Supplier invoice*).
2. Under **Document type**: **Name**, **Description**, **AI provider**, **Model** and **Accepted files** (PDF, TXT,
   CSV, XML, JSON, MD, HTML).
3. Under **Fields to extract**, click **+ Field** for each value: **Field**, **What to extract** (described for the AI),
   **Type** (String, Number, DateTime, Boolean, List) and **Required**.
4. Under **Use it**, click **Create extraction agent**. Studio creates *<name> extraction*, an agent with inputs
   `document` (File) and `api_key` (Credential) and the output `fields`. It reads PDFs with Extract text and other files
   as text, then calls Extract fields.

### 8.3 Let a person check unsure values

Set **Min confidence** (More options of Extract fields), for example `0.8`:

- **confidence** gives, per field, how sure the model is (0 to 1);
- **needs_review** is true when any field is below the threshold;
- **validation** holds the values, the confidence and the threshold.

When the agent runs from a work queue and returns `validation` as an output named `validation`, the item waits as
**To validate** in One CommandCenter. A person opens **Validate**, sees the unsure fields in red, corrects them and
**Approve**s (the item goes back to the agent with `validated_fields`) or **Reject**s it.

## 9. The Studio assistant

The **Assistant** button in the top bar opens **Agent assistant** with two tabs:

- **Build from a description**: describe the job ("Read invoices.csv, and for every row with amount above 1000 send an
  email to approvals@company.com with the invoice number"), choose a **One Model Connection** and click
  **Generate steps**. Review the proposed steps and click **Insert into the agent**. They go where the canvas hint says;
  new variables come with them. Problems to fix are listed. Always review before running.
- **Explain this agent**: the agent in plain words (what it needs, what it returns, each step). **Copy** puts it on the
  clipboard, handy for documentation or a hand-over.

The assistant needs a model connection; without one it offers **Set up a One Model Connection**.

## 10. Good practice: masking, budgets, testing

- **Mask personal data.** Keep **Hide personal data** on (it is on by default for AI agents and Prompt Skills): email
  addresses, phone numbers and card numbers are replaced before the text leaves, and put back in the answer.
  Administrators can add a Presidio analyzer for names, places and IDs.
- **Send only what is needed.** Extract the relevant part of a document before prompting; never send whole mailboxes.
- **Keep keys in the vault.** Use connections or Credential variables, never keys in steps or skill files.
- **Mind the budget.** Connections in One CommandCenter have monthly token budgets and call limits; past them, steps
  fail with `AI.BUDGET_REACHED` or `AI.CALL_LIMIT`. Set **Max tokens**, **Max steps** and **Max tokens total**.
- **Make answers predictable.** Use temperature 0, **Classify** with fixed labels, **Extract fields** with clear
  descriptions, or JSON answers, rather than free text that later steps must parse.
- **Check before acting.** Use Min confidence and work-queue validation, approvals for sensitive tools, and **If**
  steps that check a value is sensible before it is used.
- **Test with variety.** One CommandCenter's **Agent tests** can check outputs with `contains`, `one_of`, `approx` or
  `not_empty`, which suit answers worded differently each time.
- **Audit.** Every AI call of a run is listed in its **AI calls** tab and in **AI governance**; prompts and answers are
  never stored, only fingerprints.

## 11. If it goes wrong

| What you see | Cause and fix |
| --- | --- |
| The assistant says it needs a language model | Set up a connection in **Model Connections…**. |
| "API keys are never stored in connections" when saving | Point to an environment variable or a vault credential instead of typing the key. |
| An authorisation error from the provider | The key is wrong or expired, or the environment variable is not set for ONEOFFICEAUTOMATION. |
| `AI.BUDGET_REACHED` / `AI.CALL_LIMIT` | The connection's monthly budget or per-run limit is used up. Ask your administrator. |
| `AI.CONTENT_FLAGGED` | The provider's content check flagged the answer. Review the input. |
| Extract fields returns empty values | The text is empty (a scanned PDF needs OCR) or the field descriptions are vague. |
| The AI agent stops with `limit_reached` | Raise **Max steps** or **Max tokens total**, or give a clearer **Action plan**. |
| The web goal agent wanders to other sites | Set **Allowed sites**. |

## Related guides

- [Building agents](03-studio-building-agents.md)
- [Office files, email and documents](11-working-with-office-files-email-and-documents.md)
- [Cloud and business apps](12-cloud-and-business-apps.md)
- [Child agents](09-child-agents.md)
- [Business users: tasks and notifications](14-business-users.md)
- [Publishing to Command Center](15-publishing-to-command-center.md)
