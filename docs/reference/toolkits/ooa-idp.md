# Document processing

Toolkit `ooa.idp`, version 1.0.0. Classify documents by type, extract fields by rules, and extract with AI that learns from validated examples.

[All toolkits](README.md)

## Contents

- **Document processing**: Classify document, Extract by rules, Extract with examples, Save validated example

## Document processing

### Classify document

`ooa.idp/classify_document` (action)

Decide which type of document a text is (invoice, purchase order, claim...) by keywords, by AI, or keywords first and AI when they do not decide.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | Yes |  | Main | The document's text (read it with the PDF, OCR or Word toolkits). |
| Types | `types` | List | Yes |  | Main | The document types: name, keywords and a description for the AI. Example: `[{"name": "invoice", "keywords": ["invoice", "VAT", "amount due"], "description": "a supplier invoice"}]` |
| Method | `method` | Text | No | `keywords_then_ai` | Main | How to decide. One of: `keywords`, `ai`, `keywords_then_ai` |
| Connection | `connection` | Text | No |  | Main; choice Model to use = A saved connection | Name of a Model Connection (provider, model and key managed in one place). Leave empty to set them on this action. |
| Provider | `provider` | Text | No | `openai` | Main; choice Model to use = Set up here | openai, azure_openai, anthropic, openai_compatible (LiteLLM, vLLM, Ollama), huggingface (Hugging Face router or your own TGI server), aws_bedrock_sagemaker (a model on an Amazon SageMaker endpoint), aws_bedrock (Amazon Bedrock) or google_vertex (Gemini on Google Vertex AI). One of: `openai`, `azure_openai`, `anthropic`, `openai_compatible`, `huggingface`, `aws_bedrock_sagemaker`, `aws_bedrock`, `google_vertex` |
| Model | `model` | Text | No |  | Main; choice Model to use = Set up here | Model name; the deployment name for Azure OpenAI; the endpoint name for aws_bedrock_sagemaker; the model or inference profile id for aws_bedrock. |
| API key | `api_key` | Credential (vault) | No |  | Main; choice Model to use = Set up here | API key or access token from the vault (not needed for some self-hosted endpoints). For aws_bedrock and aws_bedrock_sagemaker: the AWS secret access key (or ACCESS_KEY_ID:SECRET). For google_vertex: a service-account JSON key or an access token. |
| Endpoint address | `base_url` | Text | No |  | Main; choice Model to use = Set up here | Endpoint; required for Azure OpenAI, Vertex AI (https://REGION-aiplatform.googleapis.com/v1/projects/PROJECT/locations/REGION) and self-hosted models (for a TGI server, e.g. http://tgi-host:8080/v1). |
| Minimum score | `min_score` | Number | No | `0.5` | More options | Keywords decide when the best type reaches this and leads the next. Range: 0 to 1 |
| Hide personal data | `mask_pii` | Yes/No | No | Yes | More options | Replace emails, phone and card numbers, IBANs and similar before sending; restored in the answer. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `type` | Text | The document type, or unknown. |
| `confidence` | Number | 0 to 1. |
| `method` | Text | keywords or ai: what decided. |
| `scores` | Dictionary | Keyword score per type. |

*Time limit 300 s; needs: ai.*

### Extract by rules

`ooa.idp/extract_by_rules` (action)

Extract fields with regular expressions or the text after a label, without AI: nothing leaves the runner.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | Yes |  | Main | The document's text. |
| Rules | `rules` | Dictionary | Yes |  | Main | Per field: regex (the first match, or its first group when it has one), or after (the rest of the line after a label; until gives where it stops). Example: `{"invoice_number": {"regex": "INV-\\d+"}, "total": {"after": "Total due"}}` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `fields` | Dictionary | Field name to value (text); fields not found are left out. |
| `missing` | List | Fields no rule found. |

*Time limit 60 s; safe to repeat.*

### Extract with examples

`ooa.idp/extract_with_examples` (action)

Extract fields with AI, showing the model the latest validated examples of the same document type so it follows how your documents are read.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | Yes |  | Main | The document's text. |
| Fields | `fields` | Dictionary | Yes |  | Main | Field name to what it is. Example: `{"invoice_number": "the invoice id", "total": "amount due as a number"}` |
| Examples file | `examples_file` | File path | No |  | Main | The example file Save validated example writes; none: no examples. |
| Document type | `document_type` | Text | No |  | Main | Which type's examples to use. |
| Connection | `connection` | Text | No |  | Main; choice Model to use = A saved connection | Name of a Model Connection (provider, model and key managed in one place). Leave empty to set them on this action. |
| Provider | `provider` | Text | No | `openai` | Main; choice Model to use = Set up here | openai, azure_openai, anthropic, openai_compatible (LiteLLM, vLLM, Ollama), huggingface (Hugging Face router or your own TGI server), aws_bedrock_sagemaker (a model on an Amazon SageMaker endpoint), aws_bedrock (Amazon Bedrock) or google_vertex (Gemini on Google Vertex AI). One of: `openai`, `azure_openai`, `anthropic`, `openai_compatible`, `huggingface`, `aws_bedrock_sagemaker`, `aws_bedrock`, `google_vertex` |
| Model | `model` | Text | No |  | Main; choice Model to use = Set up here | Model name; the deployment name for Azure OpenAI; the endpoint name for aws_bedrock_sagemaker; the model or inference profile id for aws_bedrock. |
| API key | `api_key` | Credential (vault) | No |  | Main; choice Model to use = Set up here | API key or access token from the vault (not needed for some self-hosted endpoints). For aws_bedrock and aws_bedrock_sagemaker: the AWS secret access key (or ACCESS_KEY_ID:SECRET). For google_vertex: a service-account JSON key or an access token. |
| Endpoint address | `base_url` | Text | No |  | Main; choice Model to use = Set up here | Endpoint; required for Azure OpenAI, Vertex AI (https://REGION-aiplatform.googleapis.com/v1/projects/PROJECT/locations/REGION) and self-hosted models (for a TGI server, e.g. http://tgi-host:8080/v1). |
| Maximum examples | `max_examples` | Number | No | `3` | More options | The newest this many examples go with the request. Range: 0 to 10 |
| Hide personal data | `mask_pii` | Yes/No | No | Yes | More options | Replace emails, phone and card numbers, IBANs and similar before sending; restored in the answer. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `fields` | Dictionary | Field name to value (null when not found). |
| `examples_used` | Number | How many examples went with the request. |

*Time limit 600 s; needs: ai, filesystem.*

### Save validated example

`ooa.idp/save_example` (action)

Keep a document's checked values as an example for its type, so Extract with examples learns from it (the newest 50 per type are kept).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Examples file | `examples_file` | File path | Yes |  | Main | The example file (made when missing). Example: `Documents/invoice.examples.json` |
| Document type | `document_type` | Text | Yes |  | Main | The document type. Example: `invoice` |
| Text | `text` | Text | Yes |  | Main | The document's text (the first 1,500 characters are kept). |
| Fields | `fields` | Dictionary | Yes |  | Main | The validated values, as a person checked them. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `count` | Number | Examples kept for the type now. |

*Time limit 30 s; needs: filesystem.*
