# AWS AI services

Toolkit `ooa.aws_ai`, version 1.0.0. Read sentiment, entities, language and personal data in text with Amazon Comprehend, text, forms and tables from scans with Amazon Textract, and labels and text in pictures with Amazon Rekognition.

[All toolkits](README.md)

## Contents

- **Comprehend (text)**: Detect language, Detect personal data, Detect entities, Detect sentiment
- **Connection**: Connect, Disconnect
- **Rekognition (pictures)**: Detect labels in a picture, Detect text in a picture
- **Textract (documents)**: Read forms and tables, Read text from a scan

## Comprehend (text)

### Detect language

`ooa.aws_ai/comprehend_detect_language` (action)

Find which language a text is written in (Amazon Comprehend).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `aws_ai` | Main | Name of the session opened by Connect. |
| Text | `text` | Text | Yes |  | Main | The text to read, at most 100,000 bytes. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `language` | Text | Code of the most likely language, e.g. en, de or fr. |
| `score` | Number | How sure Comprehend is about that language (0 to 1). |
| `languages` | Table | Every language found. Columns: language, score. |

*Time limit 60 s; safe to repeat; needs: network.*

### Detect personal data

`ooa.aws_ai/comprehend_detect_pii` (action)

Find personal data such as names, addresses, phone numbers, emails and card or account numbers in a text, and give a copy with them masked (Amazon Comprehend).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `aws_ai` | Main | Name of the session opened by Connect. |
| Text | `text` | Text | Yes |  | Main | The text to read, at most 100,000 bytes. |
| Language | `language` | Text | No | `en` | Main | Language of the text: en or es. |
| Minimum score | `min_score` | Number | No | `0` | More options | Leave out findings scored below this (0 to 1). Range: 0 to 1 |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `entities` | Table | Columns: text, type, score, begin_offset, end_offset. |
| `redacted_text` | Text | The text with each piece of personal data replaced by its type, e.g. [EMAIL]. |
| `count` | Number | How many pieces of personal data were found. |

*Time limit 60 s; safe to repeat; needs: network.*

### Detect entities

`ooa.aws_ai/comprehend_entities` (action)

Find people, places, organisations, dates, quantities and other named things in a text (Amazon Comprehend).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `aws_ai` | Main | Name of the session opened by Connect. |
| Text | `text` | Text | Yes |  | Main | The text to read, at most 100,000 bytes. |
| Language | `language` | Text | No | `en` | Main | Language of the text, e.g. en, es, fr, de, it, pt, ja, ko, zh, ar or hi. |
| Minimum score | `min_score` | Number | No | `0` | More options | Leave out entities scored below this (0 to 1). Range: 0 to 1 |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `entities` | Table | Columns: text, type, score, begin_offset, end_offset. |
| `count` | Number | How many entities were found. |

*Time limit 60 s; safe to repeat; needs: network.*

### Detect sentiment

`ooa.aws_ai/comprehend_sentiment` (action)

Read whether a text is positive, negative, neutral or mixed, with a score for each (Amazon Comprehend).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `aws_ai` | Main | Name of the session opened by Connect. |
| Text | `text` | Text | Yes |  | Main | The text to read, at most 5,000 bytes. |
| Language | `language` | Text | No | `en` | Main | Language of the text, e.g. en, es, fr, de, it, pt, ja, ko, zh, ar or hi. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `sentiment` | Text | POSITIVE, NEGATIVE, NEUTRAL or MIXED. |
| `scores` | Dictionary | Score from 0 to 1 for each, e.g. {"positive": 0.97, "negative": 0.01, "neutral": 0.02, "mixed": 0}. |

*Time limit 60 s; safe to repeat; needs: network.*

## Connection

### Connect

`ooa.aws_ai/connect` (action)

Open a session for one AWS region with an access key from the vault; the Comprehend, Textract and Rekognition steps use it.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `aws_ai` | Main | Session name. |
| Region | `region` | Text | Yes |  | Main | AWS region of the services, e.g. us-east-1, eu-west-1 or ap-south-1. Example: `eu-west-1` |
| Access key ID | `access_key_id` | Credential (vault) | Yes |  | Main | Access key id from the vault. |
| Secret access key | `secret_access_key` | Credential (vault) | Yes |  | Main | Secret access key from the vault. |
| Session token | `session_token` | Credential (vault) | No |  | More options | Session token from the vault, for temporary keys (assumed roles, single sign-on). |
| Endpoint | `endpoint` | Text | No |  | More options | Another address for the services, e.g. a private VPC endpoint; {service} in it becomes comprehend, textract, rekognition or sts. Leave empty for AWS's addresses. |
| Check now | `check_now` | Yes/No | No | Yes | More options | Check the keys with AWS straight away (a free identity check, no AI call). |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `account` | Text | The AWS account id the keys belong to (empty when the keys were not checked). |

*Time limit 60 s; needs: network.*

### Disconnect

`ooa.aws_ai/disconnect` (action)

Close the AWS AI session and forget its keys.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `aws_ai` | Main | Session name. |

*Time limit 60 s.*

## Rekognition (pictures)

### Detect labels in a picture

`ooa.aws_ai/rekognition_detect_labels` (action)

Name the objects, scenes and activities in a picture, e.g. Car, Invoice or Person, each with a confidence (Amazon Rekognition).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `aws_ai` | Main | Name of the session opened by Connect. |
| File | `file` | File path | Yes |  | Main | PNG or JPEG picture, at most 5 MB. |
| Maximum labels | `max_labels` | Number | No | `20` | More options | Most labels to return. Range: 1 to 1000 |
| Minimum confidence | `min_confidence` | Number | No | `55` | More options | Leave out labels with a lower confidence (0 to 100). Range: 0 to 100 |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `labels` | Table | Columns: name, confidence, parents, instances. |
| `label_names` | List | The label names, most confident first. |

*Time limit 60 s; safe to repeat; needs: network, filesystem.*

### Detect text in a picture

`ooa.aws_ai/rekognition_detect_text` (action)

Read short text in a photo or screenshot, e.g. signs, number plates or labels (Amazon Rekognition).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `aws_ai` | Main | Name of the session opened by Connect. |
| File | `file` | File path | Yes |  | Main | PNG or JPEG picture, at most 5 MB. |
| Minimum confidence | `min_confidence` | Number | No | `0` | More options | Leave out text read with a lower confidence (0 to 100). Range: 0 to 100 |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `text` | Text | The lines found, one per line. |
| `detections` | Table | Columns: text, type (LINE or WORD), confidence. |

*Time limit 60 s; safe to repeat; needs: network, filesystem.*

## Textract (documents)

### Read forms and tables

`ooa.aws_ai/textract_analyze_document` (action)

Read the text, the form fields (key and value) and the tables of a picture or one-page PDF; each table becomes a Data Table (Amazon Textract).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `aws_ai` | Main | Name of the session opened by Connect. |
| File | `file` | File path | Yes |  | Main | PNG, JPEG or TIFF picture, or a one-page PDF; at most 10 MB. |
| Forms | `forms` | Yes/No | No | Yes | Main; tick Read | Read form fields (key and value pairs). |
| Tables | `tables` | Yes/No | No | Yes | Main; tick Read | Read tables. |
| First row headers | `first_row_headers` | Yes/No | No | Yes | More options | Use the first row of each table as its column names. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `text` | Text | All lines of text, one per line. |
| `key_values` | Table | Form fields. Columns: key, value, confidence, page. |
| `fields` | Dictionary | Form fields as key to value, e.g. {"Invoice number:": "INV-1001"}. |
| `tables` | List | One Data Table per table found, top of the page first. |
| `table_count` | Number | How many tables were found. |

*Time limit 60 s; safe to repeat; needs: network, filesystem.*

### Read text from a scan

`ooa.aws_ai/textract_detect_text` (action)

Read the printed and handwritten text of a picture or a one-page PDF (Amazon Textract).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `aws_ai` | Main | Name of the session opened by Connect. |
| File | `file` | File path | Yes |  | Main | PNG, JPEG or TIFF picture, or a one-page PDF; at most 10 MB. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `text` | Text | All lines of text, one per line. |
| `lines` | Table | Columns: page, text, confidence. |
| `page_count` | Number | Number of pages read. |

*Time limit 60 s; safe to repeat; needs: network, filesystem.*
