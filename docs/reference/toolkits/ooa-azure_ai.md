# Azure AI services

Toolkit `ooa.azure_ai`, version 1.0.0. Read sentiment, entities and personal data in text with Azure AI Language, text, fields and tables from documents with Document Intelligence, and tags, captions and text in pictures with Image Analysis.

[All toolkits](README.md)

## Contents

- **Connection**: Connect, Disconnect
- **Document Intelligence**: Analyse a document, Read a document
- **Language (text)**: Recognise entities, Find personal data, Analyse sentiment
- **Vision (pictures)**: Analyse a picture

## Connection

### Connect

`ooa.azure_ai/connect` (action)

Open a session for an Azure AI resource with its endpoint and its key from the vault; the Language, Document Intelligence and Vision steps use it.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `azure_ai` | Main | Session name. |
| Endpoint | `endpoint` | Text | Yes |  | Main | The resource's endpoint from the Azure portal (Keys and Endpoint), e.g. https://contoso-ai.cognitiveservices.azure.com. Example: `https://contoso-ai.cognitiveservices.azure.com` |
| Credential | `credential` | Credential (vault) | Yes |  | Main | The resource's key from the vault (or a Microsoft Entra access token, see credential_type). |
| Credential type | `credential_type` | Text | No | `key` | More options | key: the resource key, sent as Ocp-Apim-Subscription-Key. access_token: a Microsoft Entra token for the resource, sent as Bearer. One of: `key`, `access_token` |

*Time limit 60 s.*

### Disconnect

`ooa.azure_ai/disconnect` (action)

Close the Azure AI session and forget its key.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `azure_ai` | Main | Session name. |

*Time limit 60 s.*

## Document Intelligence

### Analyse a document

`ooa.azure_ai/document_analyze` (action)

Read an invoice, receipt or any document's layout with a prebuilt model: its fields (e.g. vendor, total, date), key and value pairs and tables, each table as a Data Table (Azure AI Document Intelligence).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `azure_ai` | Main | Name of the session opened by Connect. |
| File | `file` | File path | Yes |  | Main | PDF, picture (JPEG, PNG, BMP, TIFF, HEIF) or Office file; at most 50 MB. |
| Model | `model` | Text | No | `prebuilt-invoice` | Main | prebuilt-invoice: invoice fields and line items. prebuilt-receipt: receipt fields. prebuilt-layout: text, tables and structure of any document. One of: `prebuilt-invoice`, `prebuilt-receipt`, `prebuilt-layout` |
| Key value pairs | `key_value_pairs` | Yes/No | No | No | More options | Also find key and value pairs, e.g. "Order no.: 4711" (an add-on feature). |
| Pages | `pages` | Text | No |  | More options | Only these pages, e.g. 1-3,5. Leave empty for all. |
| Locale | `locale` | Text | No |  | More options | Language hint, e.g. en-US or de-DE. Leave empty to detect it. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `120` | More options | Fail if the analysis has not finished after this many seconds. Range: 5 to 3600 |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `text` | Text | All text in reading order. |
| `document_type` | Text | The kind of document found, e.g. invoice or receipt.retailMeal (empty for layout). |
| `fields` | Dictionary | Field name to value, e.g. {"InvoiceId": "INV-100", "InvoiceTotal": 110}; line items become a Data Table. |
| `fields_table` | Table | Columns: name, value, type, confidence. |
| `key_values` | Table | Key and value pairs found on the pages. Columns: key, value, confidence. |
| `tables` | List | One Data Table per table found. |
| `table_count` | Number | How many tables were found. |

*Time limit 60 s; safe to repeat; needs: network, filesystem.*

### Read a document

`ooa.azure_ai/document_read` (action)

Read all printed and handwritten text of a PDF, picture or Office file with the prebuilt read model, page by page (Azure AI Document Intelligence).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `azure_ai` | Main | Name of the session opened by Connect. |
| File | `file` | File path | Yes |  | Main | PDF, picture (JPEG, PNG, BMP, TIFF, HEIF) or Office file; at most 50 MB. |
| Pages | `pages` | Text | No |  | More options | Only these pages, e.g. 1-3,5. Leave empty for all. |
| Locale | `locale` | Text | No |  | More options | Language hint, e.g. en-US or de-DE. Leave empty to detect it. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `120` | More options | Fail if the analysis has not finished after this many seconds. Range: 5 to 3600 |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `text` | Text | All text in reading order. |
| `lines` | Table | Columns: page, text. |
| `page_count` | Number | Number of pages read. |
| `languages` | List | Languages found, e.g. ["en", "de"]. |

*Time limit 60 s; safe to repeat; needs: network, filesystem.*

## Language (text)

### Recognise entities

`ooa.azure_ai/language_entities` (action)

Find people, places, organisations, dates, quantities and other named things in a text (Azure AI Language).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `azure_ai` | Main | Name of the session opened by Connect. |
| Text | `text` | Text | Yes |  | Main | The text to read, at most 5,120 characters. |
| Language | `language` | Text | No | `en` | Main | Language of the text, e.g. en, de, fr, es or hi. |
| Minimum confidence | `min_confidence` | Number | No | `0` | More options | Leave out entities with a lower confidence (0 to 1). Range: 0 to 1 |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `entities` | Table | Columns: text, category, subcategory, confidence, offset, length. |
| `count` | Number | How many entities were found. |

*Time limit 60 s; safe to repeat; needs: network.*

### Find personal data

`ooa.azure_ai/language_pii` (action)

Find personal data such as names, phone numbers, emails, addresses and ID or account numbers in a text, and get a masked copy (Azure AI Language).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `azure_ai` | Main | Name of the session opened by Connect. |
| Text | `text` | Text | Yes |  | Main | The text to read, at most 5,120 characters. |
| Language | `language` | Text | No | `en` | Main | Language of the text, e.g. en, de, fr, es or hi. |
| Categories | `categories` | List | No |  | More options | Only look for these kinds, e.g. ["Email", "PhoneNumber", "Person"]. Leave empty for all kinds. |
| Domain | `domain` | Text | No | `none` | More options | phi also looks for protected health information. One of: `none`, `phi` |
| Minimum confidence | `min_confidence` | Number | No | `0` | More options | Leave out findings with a lower confidence (0 to 1). Range: 0 to 1 |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `redacted_text` | Text | The text with the personal data masked with * characters. |
| `entities` | Table | Columns: text, category, subcategory, confidence, offset, length. |
| `count` | Number | How many pieces of personal data were found. |

*Time limit 60 s; safe to repeat; needs: network.*

### Analyse sentiment

`ooa.azure_ai/language_sentiment` (action)

Read whether a text is positive, neutral, negative or mixed, overall and sentence by sentence (Azure AI Language).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `azure_ai` | Main | Name of the session opened by Connect. |
| Text | `text` | Text | Yes |  | Main | The text to read, at most 5,120 characters. |
| Language | `language` | Text | No | `en` | Main | Language of the text, e.g. en, de, fr, es or hi. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `sentiment` | Text | positive, neutral, negative or mixed. |
| `scores` | Dictionary | Confidence from 0 to 1 for each, e.g. {"positive": 0.95, "neutral": 0.04, "negative": 0.01}. |
| `sentences` | Table | Columns: text, sentiment, positive, neutral, negative. |

*Time limit 60 s; safe to repeat; needs: network.*

## Vision (pictures)

### Analyse a picture

`ooa.azure_ai/vision_analyze` (action)

Describe a picture in a sentence, tag what is in it, find objects and read its text (Azure AI Vision Image Analysis 4.0).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `azure_ai` | Main | Name of the session opened by Connect. |
| File | `file` | File path | Yes |  | Main | JPEG, PNG, GIF, BMP, WEBP, ICO, TIFF or MPO picture, at most 20 MB. |
| Features | `features` | List | No | `["tags", "caption", "read"]` | Main | What to find: any of tags, caption, read, objects. |
| Language | `language` | Text | No | `en` | More options | Language of the tags, e.g. en, de or fr (captions are in English). |
| Gender neutral caption | `gender_neutral_caption` | Yes/No | No | No | More options | Say "person" rather than "man" or "woman" in the caption. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `caption` | Text | A sentence describing the picture (when caption is asked for). |
| `caption_confidence` | Number | Confidence of the caption (0 to 1). |
| `tags` | Table | Columns: name, confidence. |
| `tag_names` | List | The tag names. |
| `objects` | Table | Columns: name, confidence, x, y, width, height. |
| `text` | Text | Text read from the picture, one line per line (when read is asked for). |
| `lines` | Table | Columns: text, confidence (the lowest word confidence in the line). |

*Time limit 60 s; safe to repeat; needs: network, filesystem.*
