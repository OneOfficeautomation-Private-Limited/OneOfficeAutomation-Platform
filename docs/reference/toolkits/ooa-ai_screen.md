# AI screen (vision)

Toolkit `ooa.ai_screen`, version 1.0.0. Find and click controls on the screen from a plain description, with a vision model.

[All toolkits](README.md)

## Contents

- **AI screen**: Click described control (AI), Find on screen (AI), Read described control (AI), Type into described control (AI)

## AI screen

### Click described control (AI)

`ooa.ai_screen/click_described` (action)

Find a control on the screen from a plain description with a vision model, and click it; fails when it is not found.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Description | `description` | Text | Yes |  | Main | The control in plain words: its text, kind, colour or place. Example: `the Submit button below the address fields` |
| Click | `click` | Text | No | `single` | Main | Which click. One of: `single`, `double`, `right` |
| Connection | `connection` | Text | No |  | Main; choice Model to use = A saved connection | Name of a Model Connection (provider, model and key managed in one place). Leave empty to set them on this action. |
| Provider | `provider` | Text | No | `openai` | Main; choice Model to use = Set up here | openai, azure_openai, anthropic, openai_compatible (LiteLLM, vLLM, Ollama), huggingface (Hugging Face router or your own TGI server), aws_bedrock_sagemaker (a model on an Amazon SageMaker endpoint), aws_bedrock (Amazon Bedrock) or google_vertex (Gemini on Google Vertex AI). One of: `openai`, `azure_openai`, `anthropic`, `openai_compatible`, `aws_bedrock`, `google_vertex` |
| Model | `model` | Text | No |  | Main; choice Model to use = Set up here | A model that reads pictures. |
| API key | `api_key` | Credential (vault) | No |  | Main; choice Model to use = Set up here | API key or access token from the vault (not needed for some self-hosted endpoints). For aws_bedrock and aws_bedrock_sagemaker: the AWS secret access key (or ACCESS_KEY_ID:SECRET). For google_vertex: a service-account JSON key or an access token. |
| Endpoint address | `base_url` | Text | No |  | Main; choice Model to use = Set up here | Endpoint; required for Azure OpenAI, Vertex AI (https://REGION-aiplatform.googleapis.com/v1/projects/PROJECT/locations/REGION) and self-hosted models (for a TGI server, e.g. http://tgi-host:8080/v1). |
| Minimum confidence | `min_confidence` | Number | No | `0.7` | More options | Below this, the step fails instead of clicking. Range: 0 to 1 |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `x` | Number | Where it clicked, from the left. |
| `y` | Number | Where it clicked, from the top. |
| `label` | Text | What the model says it clicked. |

*Time limit 180 s; needs: desktop, network, ai.*

### Find on screen (AI)

`ooa.ai_screen/find_on_screen` (action)

Find a control on the screen from a plain description, with a vision model; returns where it is.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Description | `description` | Text | Yes |  | Main | The control in plain words: its text, kind, colour or place. Example: `the Submit button below the address fields` |
| Connection | `connection` | Text | No |  | Main; choice Model to use = A saved connection | Name of a Model Connection (provider, model and key managed in one place). Leave empty to set them on this action. |
| Provider | `provider` | Text | No | `openai` | Main; choice Model to use = Set up here | openai, azure_openai, anthropic, openai_compatible (LiteLLM, vLLM, Ollama), huggingface (Hugging Face router or your own TGI server), aws_bedrock_sagemaker (a model on an Amazon SageMaker endpoint), aws_bedrock (Amazon Bedrock) or google_vertex (Gemini on Google Vertex AI). One of: `openai`, `azure_openai`, `anthropic`, `openai_compatible`, `aws_bedrock`, `google_vertex` |
| Model | `model` | Text | No |  | Main; choice Model to use = Set up here | A model that reads pictures. |
| API key | `api_key` | Credential (vault) | No |  | Main; choice Model to use = Set up here | API key or access token from the vault (not needed for some self-hosted endpoints). For aws_bedrock and aws_bedrock_sagemaker: the AWS secret access key (or ACCESS_KEY_ID:SECRET). For google_vertex: a service-account JSON key or an access token. |
| Endpoint address | `base_url` | Text | No |  | Main; choice Model to use = Set up here | Endpoint; required for Azure OpenAI, Vertex AI (https://REGION-aiplatform.googleapis.com/v1/projects/PROJECT/locations/REGION) and self-hosted models (for a TGI server, e.g. http://tgi-host:8080/v1). |
| Minimum confidence | `min_confidence` | Number | No | `0.6` | More options | Below this, it counts as not found. Range: 0 to 1 |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `found` | Yes/No | True when it was found with at least min_confidence. |
| `x` | Number | The middle of the control, in screen pixels from the left (-1 when not found). |
| `y` | Number | The middle of the control, in screen pixels from the top. |
| `box` | Dictionary | left, top, width and height in screen pixels. |
| `confidence` | Number | How sure the model is, 0 to 1. |
| `label` | Text | What the model says it found. |
| `text` | Text | The text the model reads in or on the control. |

*Time limit 180 s; needs: desktop, network, ai.*

### Read described control (AI)

`ooa.ai_screen/read_described` (action)

Read the text of a control on the screen, found from a plain description with a vision model (for screens without selectors or OCR zones).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Description | `description` | Text | Yes |  | Main | The control in plain words. Example: `the Total amount at the bottom right` |
| Connection | `connection` | Text | No |  | Main; choice Model to use = A saved connection | Name of a Model Connection (provider, model and key managed in one place). Leave empty to set them on this action. |
| Provider | `provider` | Text | No | `openai` | Main; choice Model to use = Set up here | openai, azure_openai, anthropic, openai_compatible (LiteLLM, vLLM, Ollama), huggingface (Hugging Face router or your own TGI server), aws_bedrock_sagemaker (a model on an Amazon SageMaker endpoint), aws_bedrock (Amazon Bedrock) or google_vertex (Gemini on Google Vertex AI). One of: `openai`, `azure_openai`, `anthropic`, `openai_compatible`, `aws_bedrock`, `google_vertex` |
| Model | `model` | Text | No |  | Main; choice Model to use = Set up here | A model that reads pictures. |
| API key | `api_key` | Credential (vault) | No |  | Main; choice Model to use = Set up here | API key or access token from the vault (not needed for some self-hosted endpoints). For aws_bedrock and aws_bedrock_sagemaker: the AWS secret access key (or ACCESS_KEY_ID:SECRET). For google_vertex: a service-account JSON key or an access token. |
| Endpoint address | `base_url` | Text | No |  | Main; choice Model to use = Set up here | Endpoint; required for Azure OpenAI, Vertex AI (https://REGION-aiplatform.googleapis.com/v1/projects/PROJECT/locations/REGION) and self-hosted models (for a TGI server, e.g. http://tgi-host:8080/v1). |
| Minimum confidence | `min_confidence` | Number | No | `0.6` | More options | Below this, it counts as not found. Range: 0 to 1 |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `text` | Text | The text read; empty when not found. |
| `found` | Yes/No | True when it was found with at least min_confidence. |

*Time limit 180 s; needs: desktop, network, ai.*

### Type into described control (AI)

`ooa.ai_screen/type_described` (action)

Find a field on the screen from a plain description with a vision model, click into it and type text (pasted, so any language works).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Description | `description` | Text | Yes |  | Main | The field in plain words. Example: `the Invoice number field` |
| Text | `text` | Text | Yes |  | Main | What to type. |
| Connection | `connection` | Text | No |  | Main; choice Model to use = A saved connection | Name of a Model Connection (provider, model and key managed in one place). Leave empty to set them on this action. |
| Provider | `provider` | Text | No | `openai` | Main; choice Model to use = Set up here | openai, azure_openai, anthropic, openai_compatible (LiteLLM, vLLM, Ollama), huggingface (Hugging Face router or your own TGI server), aws_bedrock_sagemaker (a model on an Amazon SageMaker endpoint), aws_bedrock (Amazon Bedrock) or google_vertex (Gemini on Google Vertex AI). One of: `openai`, `azure_openai`, `anthropic`, `openai_compatible`, `aws_bedrock`, `google_vertex` |
| Model | `model` | Text | No |  | Main; choice Model to use = Set up here | A model that reads pictures. |
| API key | `api_key` | Credential (vault) | No |  | Main; choice Model to use = Set up here | API key or access token from the vault (not needed for some self-hosted endpoints). For aws_bedrock and aws_bedrock_sagemaker: the AWS secret access key (or ACCESS_KEY_ID:SECRET). For google_vertex: a service-account JSON key or an access token. |
| Endpoint address | `base_url` | Text | No |  | Main; choice Model to use = Set up here | Endpoint; required for Azure OpenAI, Vertex AI (https://REGION-aiplatform.googleapis.com/v1/projects/PROJECT/locations/REGION) and self-hosted models (for a TGI server, e.g. http://tgi-host:8080/v1). |
| Minimum confidence | `min_confidence` | Number | No | `0.7` | More options | Below this, the step fails instead of typing. Range: 0 to 1 |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `label` | Text | What the model says it typed into. |

*Time limit 180 s; needs: desktop, network, ai.*
