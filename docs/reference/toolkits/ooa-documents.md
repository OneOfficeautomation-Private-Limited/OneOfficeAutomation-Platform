# Document Automation

Toolkit `ooa.documents`, version 1.0.0. Send documents (PDF, PNG, JPG, TIFF) to a Document Automation process, then pick up what it read: fields, tables and confidence, once read or reviewed.

[All toolkits](README.md)

## Contents

- **Document Automation**: Get document result, List finished documents, Mark collected, Send document to process, Wait for document

## Document Automation

### Get document result

`ooa.documents/get_result` (action)

Read a document's status and what Document Automation read from it, by its id: fields, tables (such as line items) and each field's confidence.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Document ID | `document_id` | Text | Yes |  | Main | The document id that Send document to process gave (or a document_id input given by a Start-a-bot delivery). |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `status` | Text | queued or processing (still being read), success (read and checked), validation (waits for a reviewer), validated (approved), invalid (rejected) or failed. |
| `done` | Yes/No | True when the document is no longer being read. |
| `needs_review` | Yes/No | True while it waits for a person's review. |
| `reviewed` | Yes/No | True when a person approved or rejected it. |
| `fields` | Dictionary | The fields read, by name (after review: the corrected values). |
| `tables` | Dictionary | Table fields (such as line items), by name, each a Data table. |
| `confidence` | Dictionary | How sure the reading was for each field, from 0 to 1. |
| `document_type` | Text | The document type it was classified as. |
| `file_name` | Text | The document's file name. |
| `reviewed_by` | Text | Who approved or rejected it, when reviewed. |
| `error` | Text | Why reading failed, when it failed. |

*Time limit 60 s; safe to repeat.*

### List finished documents

`ooa.documents/list_finished` (action)

List a process's documents that are read and need nothing more (read and checked, approved or rejected) and are not yet marked collected.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Process | `process` | Text | Yes |  | Main | The process's name (or id) as shown in Document Automation. |
| Reviewed only | `reviewed_only` | Yes/No | No | No | Main; tick Only approved or rejected | Only documents a person approved or rejected (not those read and checked without a review). |
| Collector | `collector` | Text | No |  | Main | A name for whoever collects: agents with the same name share the collected marks, so none takes a document twice. Empty: default. |
| Include collected | `include_collected` | Yes/No | No | No | More options | Also list documents already marked collected. |
| Limit | `limit` | Number | No | `100` | More options | At most this many documents. Range: 1 to 500 |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `documents` | Table | Columns: document_id, file_name, status, document_type, created_at, reviewed_by, collected. |
| `document_ids` | List | The documents' ids, for a loop or for Mark collected. |
| `count` | Number | How many documents are listed. |

*Time limit 60 s; safe to repeat.*

### Mark collected

`ooa.documents/mark_collected` (action)

Mark documents as collected, so List finished documents leaves them out for the same collector name.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Document IDs | `document_ids` | List | Yes |  | Main | The ids to mark (a list, or one id). |
| Collector | `collector` | Text | No |  | Main | A name for whoever collects: agents with the same name share the collected marks, so none takes a document twice. Empty: default. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `marked` | Number | How many documents were marked now. |
| `already_collected` | Number | How many were marked before. |

*Time limit 60 s.*

### Send document to process

`ooa.documents/send` (action)

Send a document to a live Document Automation process. Optionally wait until it is read, to use its fields and status in the agent.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Process | `process` | Text | Yes |  | Main | The process's name (or id) as shown in Document Automation. It must be live. |
| Path | `path` | File path | Yes |  | Main | The document: PDF, PNG, JPG or TIFF. |
| Wait | `wait` | Yes/No | No | No | Main; tick Wait for the fields | Wait until the document is read (up to 15 minutes) and return its status and fields. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `document_id` | Text | The document's id in Document Automation. |
| `status` | Text | processing, success (read and checked), validation (waits for a reviewer), invalid or failed. |
| `needs_review` | Yes/No | True when a person must review it (only known when waiting). |
| `fields` | Dictionary | The fields read, by name (only when waiting). |

*Time limit 900 s; needs: filesystem.*

### Wait for document

`ooa.documents/wait_for_document` (action)

Wait until a document is read, or until it is also reviewed when it needs a review, then give its result.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Document ID | `document_id` | Text | Yes |  | Main | The document id that Send document to process gave (or a document_id input given by a Start-a-bot delivery). |
| Until | `until` | Text | No | `read` | Main | Read: until it is read (it may then wait for a review). Reviewed: also until a person has reviewed it, when it needs a review. One of: `read`, `reviewed` |
| Timeout (seconds) | `timeout_seconds` | Number | No | `900` | More options | Give up after this many seconds with the error DOCS.WAIT_TIMEOUT (a review may take hours). Range: 1 to 86400 |
| Check every (seconds) | `check_every_seconds` | Number | No | `10` | More options | How often to ask Document Automation. Range: 1 to 600 |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `status` | Text | The document's status when the wait ended. |
| `done` | Yes/No | True when the document is no longer being read. |
| `needs_review` | Yes/No | True while it waits for a person's review. |
| `reviewed` | Yes/No | True when a person approved or rejected it. |
| `fields` | Dictionary | The fields read, by name. |
| `tables` | Dictionary | Table fields (such as line items), by name, each a Data table. |
| `confidence` | Dictionary | How sure the reading was for each field, from 0 to 1. |
| `document_type` | Text | The document type it was classified as. |
| `file_name` | Text | The document's file name. |
| `reviewed_by` | Text | Who approved or rejected it, when reviewed. |
| `error` | Text | Why reading failed, when it failed. |

*Time limit 86400 s.*
