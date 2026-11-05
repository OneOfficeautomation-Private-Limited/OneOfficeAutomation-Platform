# Document Automation

Toolkit `ooa.documents`, version 1.0.0. Send documents (PDF, PNG, JPG, TIFF) to a Document Automation process and get the fields it reads.

[All toolkits](README.md)

## Contents

- **Document Automation**: Send document to process

## Document Automation

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
