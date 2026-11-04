# Google Cloud AI

Toolkit `ooa.google_ai`, version 1.0.0. Read sentiment and entities in text with Cloud Natural Language, labels and text in pictures with Cloud Vision, and text, entities, form fields and tables from documents with Document AI.

[All toolkits](README.md)

## Contents

- **Connection**: Connect, Disconnect
- **Document AI**: Process a document
- **Natural Language (text)**: Analyse entities, Analyse sentiment
- **Vision (pictures)**: Detect labels, Read text in a picture

## Connection

### Connect

`ooa.google_ai/connect` (action)

Open a session with an API key or OAuth access token from the vault, and the project and location of your Document AI processors.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `google_ai` | Main | Session name. |
| Credential | `credential` | Credential (vault) | Yes |  | Main | API key or OAuth access token from the vault. |
| Credential type | `credential_type` | Text | No | `api_key` | Main | api_key: a Google Cloud API key (Natural Language and Vision). access_token: an OAuth token of a service account or user (needed for Document AI). One of: `api_key`, `access_token` |
| Project | `project` | Text | No |  | More options | Google Cloud project id or number; needed for Document AI, and billed for calls made with an access token. Example: `acme-ai-prod` |
| Location | `location` | Text | No | `us` | More options | Location of the Document AI processors: us or eu. One of: `us`, `eu` |
| Endpoint | `endpoint` | Text | No |  | More options | Another address for the APIs, e.g. a Private Service Connect address; {service} in it becomes language, vision or us-documentai (eu-documentai). Leave empty for Google's addresses. |

*Time limit 60 s.*

### Disconnect

`ooa.google_ai/disconnect` (action)

Close the Google Cloud AI session and forget its key.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `google_ai` | Main | Session name. |

*Time limit 60 s.*

## Document AI

### Process a document

`ooa.google_ai/documentai_process` (action)

Send a document to a Document AI processor (e.g. an invoice, form, OCR or custom extractor) and get its text, entities, form fields and tables; each table becomes a Data Table.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `google_ai` | Main | Name of the session opened by Connect. |
| Processor ID | `processor_id` | Text | Yes |  | Main | Id of the processor, from its page in the Google Cloud console. Example: `a1b2c3d4e5f60718` |
| File | `file` | File path | Yes |  | Main | PDF, TIFF, GIF, JPEG, PNG, BMP or WEBP file; at most 20 MB. |
| Processor version | `processor_version` | Text | No |  | More options | A processor version id, e.g. pretrained-invoice-v2.0-2023-12-06. Leave empty for the default version. |
| MIME type | `mime_type` | Text | No |  | More options | Content type of the file, e.g. application/pdf. Leave empty to take it from the file name. |
| First row headers | `first_row_headers` | Yes/No | No | Yes | More options | Use each table's header row (or first row) as its column names. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `text` | Text | All text of the document. |
| `entities` | Table | What the processor extracted. Columns: type, text, normalized, confidence. |
| `fields` | Dictionary | Entity type to its text, e.g. {"invoice_id": "INV-100", "total_amount": "110.00"}; repeated types become a list. |
| `form_fields` | Table | Form fields found on the pages (form parser). Columns: name, value, confidence, page. |
| `tables` | List | One Data Table per table found. |
| `page_count` | Number | Number of pages processed. |

*Time limit 60 s; safe to repeat; needs: network, filesystem.*

## Natural Language (text)

### Analyse entities

`ooa.google_ai/language_entities` (action)

Find people, places, organisations, events, products, dates, prices and other named things in a text, ranked by importance (Cloud Natural Language).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `google_ai` | Main | Name of the session opened by Connect. |
| Text | `text` | Text | Yes |  | Main | The text to read, at most 1,000,000 bytes. |
| Language | `language` | Text | No |  | Main | Language of the text, e.g. en, de or ja. Leave empty to detect it. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `entities` | Table | Columns: name, type, salience, mentions, wikipedia_url. |
| `count` | Number | How many entities were found. |
| `language` | Text | Language of the text, as given or detected. |

*Time limit 60 s; safe to repeat; needs: network.*

### Analyse sentiment

`ooa.google_ai/language_sentiment` (action)

Read how positive or negative a text is, overall and sentence by sentence (Cloud Natural Language).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `google_ai` | Main | Name of the session opened by Connect. |
| Text | `text` | Text | Yes |  | Main | The text to read, at most 1,000,000 bytes. |
| Language | `language` | Text | No |  | Main | Language of the text, e.g. en, de or ja. Leave empty to detect it. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `sentiment` | Text | positive (score 0.25 or more), negative (-0.25 or less) or neutral. |
| `score` | Number | From -1 (negative) to 1 (positive). |
| `magnitude` | Number | How strong the feeling is overall, from 0 up. |
| `language` | Text | Language of the text, as given or detected. |
| `sentences` | Table | Columns: text, score, magnitude. |

*Time limit 60 s; safe to repeat; needs: network.*

## Vision (pictures)

### Detect labels

`ooa.google_ai/vision_labels` (action)

Name what a picture shows, e.g. Invoice, Car or Building, each with a score (Cloud Vision).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `google_ai` | Main | Name of the session opened by Connect. |
| File | `file` | File path | Yes |  | Main | JPEG, PNG, GIF, BMP, WEBP, ICO, TIFF or PDF picture, at most 7 MB. |
| Maximum results | `max_results` | Number | No | `10` | More options | Most labels to return. Range: 1 to 100 |
| Minimum score | `min_score` | Number | No | `0` | More options | Leave out labels scored lower (0 to 1). Range: 0 to 1 |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `labels` | Table | Columns: description, score, topicality, mid. |
| `label_names` | List | The label names, best first. |

*Time limit 60 s; safe to repeat; needs: network, filesystem.*

### Read text in a picture

`ooa.google_ai/vision_ocr` (action)

Read the text of a photo, scan or screenshot: dense documents or short text such as signs (Cloud Vision).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `google_ai` | Main | Name of the session opened by Connect. |
| File | `file` | File path | Yes |  | Main | JPEG, PNG, GIF, BMP, WEBP, ICO, TIFF or PDF picture, at most 7 MB. |
| Mode | `mode` | Text | No | `document` | More options | document: dense text such as letters and forms. text: short text in photos, such as signs. One of: `document`, `text` |
| Language hints | `language_hints` | List | No |  | More options | Languages to expect, e.g. ["en", "hi"]. Leave empty to detect them. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `text` | Text | All text found. |
| `words` | Table | Columns: text (one row per word or piece of text found). |
| `language` | Text | Language of the text, when Vision could tell. |

*Time limit 60 s; safe to repeat; needs: network, filesystem.*
