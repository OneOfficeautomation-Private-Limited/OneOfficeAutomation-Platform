# Generative AI

Toolkit `ooa.genai`, version 1.1.0. Prompt large language models (OpenAI, Azure OpenAI, Anthropic, Amazon Bedrock, Google Vertex AI, self-hosted) to answer, read pictures, draw pictures and analyse text.

[All toolkits](README.md)

## Contents

- **Generative AI**: Chat, Classify, Detect language, End chat, Named entities, Extract fields, Generate picture, Key phrases, Prompt, Prompt with pictures, Sentiment, Summarise, Translate

## Generative AI

### Chat

`ooa.genai/chat` (action)

Send a message in a conversation that remembers earlier messages of the same chat session until End chat.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Message | `message` | Text | Yes |  | Main | Your next message. |
| Chat session | `chat_session` | Text | No | `Default` | Main | Name of the conversation; use different names for separate chats in one agent. |
| System | `system` | Text | No |  | Main | Instructions for the model's role; set on the first message of a session (a new value replaces the old one). |
| Connection | `connection` | Text | No |  | Main; choice Model to use = A saved connection | Name of a Model Connection (provider, model and key managed in one place). Leave empty to set them on this action. |
| Provider | `provider` | Text | No | `openai` | Main; choice Model to use = Set up here | openai, azure_openai, anthropic, openai_compatible (LiteLLM, vLLM, Ollama), huggingface (Hugging Face router or your own TGI server), aws_bedrock_sagemaker (a model on an Amazon SageMaker endpoint), aws_bedrock (Amazon Bedrock) or google_vertex (Gemini on Google Vertex AI). One of: `openai`, `azure_openai`, `anthropic`, `openai_compatible`, `huggingface`, `aws_bedrock_sagemaker`, `aws_bedrock`, `google_vertex` |
| Model | `model` | Text | No |  | Main; choice Model to use = Set up here | Model name; the deployment name for Azure OpenAI; the endpoint name for aws_bedrock_sagemaker; the model or inference profile id for aws_bedrock. |
| API key | `api_key` | Credential (vault) | No |  | Main; choice Model to use = Set up here | API key or access token from the vault (not needed for some self-hosted endpoints). For aws_bedrock and aws_bedrock_sagemaker: the AWS secret access key (or ACCESS_KEY_ID:SECRET). For google_vertex: a service-account JSON key or an access token. |
| Endpoint address | `base_url` | Text | No |  | Main; choice Model to use = Set up here | Endpoint; required for Azure OpenAI, Vertex AI (https://REGION-aiplatform.googleapis.com/v1/projects/PROJECT/locations/REGION) and self-hosted models (for a TGI server, e.g. http://tgi-host:8080/v1). |
| AWS access key ID | `aws_access_key_id` | Credential (vault) | No |  | Main; choice Model to use = Set up here | aws_bedrock and aws_bedrock_sagemaker only: AWS access key id from the vault (api_key then holds the secret access key). |
| AWS region | `aws_region` | Text | No |  | Main; choice Model to use = Set up here | aws_bedrock and aws_bedrock_sagemaker only: AWS region, e.g. eu-west-1. |
| Images | `images` | List | No |  | Main | Pictures sent with this message (file paths: .png, .jpg, .gif, .webp); the model must accept pictures. |
| Temperature | `temperature` | Number | No | `0` | More options | 0 = consistent, higher = more creative. |
| Maximum tokens | `max_tokens` | Number | No | `1024` | More options | Maximum reply length in tokens. |
| Hide personal data | `mask_pii` | Yes/No | No | Yes | More options | Replace emails, phone and card numbers, IBANs and similar before sending; restored in the answer. |
| History limit | `history_limit` | Number | No | `40` | More options | Most messages to keep in the session; the oldest are dropped first. |
| Top p | `top_p` | Number | No |  | More options | Nucleus sampling: only the most likely words whose chances add up to this share are used (above 0 to 1); provider default when empty. |
| Stop sequences | `stop_sequences` | List | No |  | More options | Up to 4 texts at which the model stops writing. |
| Presence penalty | `presence_penalty` | Number | No |  | More options | From -2 to 2; higher values push the model toward new topics (OpenAI-style providers). |
| Frequency penalty | `frequency_penalty` | Number | No |  | More options | From -2 to 2; higher values make the model repeat itself less (OpenAI-style providers). |
| Timeout (seconds) | `timeout_seconds` | Number | No | `0` | More options | Seconds to wait for the model; 180 when 0. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `text` | Text | The model's reply. |
| `turns` | Number | Number of messages now held in the session (yours and the model's). |
| `input_tokens` | Number | Tokens in the request. |
| `output_tokens` | Number | Tokens in the reply. |

*Time limit 600 s; needs: network, ai.*

### Classify

`ooa.genai/classify` (action)

Put text into exactly one of your categories, with a short reason.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | Yes |  | Main | Text to classify, e.g. an email body. |
| Labels | `labels` | List | Yes |  | Main | Allowed categories, e.g. ["invoice","complaint","other"]. |
| Connection | `connection` | Text | No |  | Main; choice Model to use = A saved connection | Name of a Model Connection (provider, model and key managed in one place). Leave empty to set them on this action. |
| Provider | `provider` | Text | No | `openai` | Main; choice Model to use = Set up here | openai, azure_openai, anthropic, openai_compatible (LiteLLM, vLLM, Ollama), huggingface (Hugging Face router or your own TGI server), aws_bedrock_sagemaker (a model on an Amazon SageMaker endpoint), aws_bedrock (Amazon Bedrock) or google_vertex (Gemini on Google Vertex AI). One of: `openai`, `azure_openai`, `anthropic`, `openai_compatible`, `huggingface`, `aws_bedrock_sagemaker`, `aws_bedrock`, `google_vertex` |
| Model | `model` | Text | No |  | Main; choice Model to use = Set up here | Model name; the deployment name for Azure OpenAI; the endpoint name for aws_bedrock_sagemaker; the model or inference profile id for aws_bedrock. |
| API key | `api_key` | Credential (vault) | No |  | Main; choice Model to use = Set up here | API key or access token from the vault (not needed for some self-hosted endpoints). For aws_bedrock and aws_bedrock_sagemaker: the AWS secret access key (or ACCESS_KEY_ID:SECRET). For google_vertex: a service-account JSON key or an access token. |
| Endpoint address | `base_url` | Text | No |  | Main; choice Model to use = Set up here | Endpoint; required for Azure OpenAI, Vertex AI (https://REGION-aiplatform.googleapis.com/v1/projects/PROJECT/locations/REGION) and self-hosted models (for a TGI server, e.g. http://tgi-host:8080/v1). |
| AWS access key ID | `aws_access_key_id` | Credential (vault) | No |  | Main; choice Model to use = Set up here | aws_bedrock and aws_bedrock_sagemaker only: AWS access key id from the vault (api_key then holds the secret access key). |
| AWS region | `aws_region` | Text | No |  | Main; choice Model to use = Set up here | aws_bedrock and aws_bedrock_sagemaker only: AWS region, e.g. eu-west-1. |
| Hide personal data | `mask_pii` | Yes/No | No | Yes | More options | Replace emails, phone and card numbers, IBANs and similar before sending; restored in the answer. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `label` | Text | Chosen category (always one of the labels). |
| `reason` | Text | Why. |

*Time limit 300 s; needs: network, ai.*

### Detect language

`ooa.genai/detect_language` (action)

Tell which language a text is written in.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | Yes |  | Main | Text, e.g. an email body. |
| Connection | `connection` | Text | No |  | Main; choice Model to use = A saved connection | Name of a Model Connection (provider, model and key managed in one place). Leave empty to set them on this action. |
| Provider | `provider` | Text | No | `openai` | Main; choice Model to use = Set up here | openai, azure_openai, anthropic, openai_compatible (LiteLLM, vLLM, Ollama), huggingface (Hugging Face router or your own TGI server), aws_bedrock_sagemaker (a model on an Amazon SageMaker endpoint), aws_bedrock (Amazon Bedrock) or google_vertex (Gemini on Google Vertex AI). One of: `openai`, `azure_openai`, `anthropic`, `openai_compatible`, `huggingface`, `aws_bedrock_sagemaker`, `aws_bedrock`, `google_vertex` |
| Model | `model` | Text | No |  | Main; choice Model to use = Set up here | Model name; the deployment name for Azure OpenAI; the endpoint name for aws_bedrock_sagemaker; the model or inference profile id for aws_bedrock. |
| API key | `api_key` | Credential (vault) | No |  | Main; choice Model to use = Set up here | API key or access token from the vault (not needed for some self-hosted endpoints). For aws_bedrock and aws_bedrock_sagemaker: the AWS secret access key (or ACCESS_KEY_ID:SECRET). For google_vertex: a service-account JSON key or an access token. |
| Endpoint address | `base_url` | Text | No |  | Main; choice Model to use = Set up here | Endpoint; required for Azure OpenAI, Vertex AI (https://REGION-aiplatform.googleapis.com/v1/projects/PROJECT/locations/REGION) and self-hosted models (for a TGI server, e.g. http://tgi-host:8080/v1). |
| AWS access key ID | `aws_access_key_id` | Credential (vault) | No |  | Main; choice Model to use = Set up here | aws_bedrock and aws_bedrock_sagemaker only: AWS access key id from the vault (api_key then holds the secret access key). |
| AWS region | `aws_region` | Text | No |  | Main; choice Model to use = Set up here | aws_bedrock and aws_bedrock_sagemaker only: AWS region, e.g. eu-west-1. |
| Hide personal data | `mask_pii` | Yes/No | No | Yes | More options | Replace emails, phone and card numbers, IBANs and similar before sending; restored in the answer. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `language` | Text | Language name in English, e.g. German. |
| `code` | Text | Two-letter ISO 639-1 code, e.g. de. |
| `confidence` | Number | From 0 to 1. |

*Time limit 300 s; needs: network, ai.*

### End chat

`ooa.genai/end_chat` (action)

Forget the messages of a chat session, so the next Chat starts a new conversation.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Chat session | `chat_session` | Text | No | `Default` | Main | Name of the conversation to end. |

*Time limit 60 s.*

### Named entities

`ooa.genai/entities` (action)

Find the people, organisations, places, dates, amounts and products a text names.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | Yes |  | Main | Text, e.g. a contract clause or an email. |
| Connection | `connection` | Text | No |  | Main; choice Model to use = A saved connection | Name of a Model Connection (provider, model and key managed in one place). Leave empty to set them on this action. |
| Provider | `provider` | Text | No | `openai` | Main; choice Model to use = Set up here | openai, azure_openai, anthropic, openai_compatible (LiteLLM, vLLM, Ollama), huggingface (Hugging Face router or your own TGI server), aws_bedrock_sagemaker (a model on an Amazon SageMaker endpoint), aws_bedrock (Amazon Bedrock) or google_vertex (Gemini on Google Vertex AI). One of: `openai`, `azure_openai`, `anthropic`, `openai_compatible`, `huggingface`, `aws_bedrock_sagemaker`, `aws_bedrock`, `google_vertex` |
| Model | `model` | Text | No |  | Main; choice Model to use = Set up here | Model name; the deployment name for Azure OpenAI; the endpoint name for aws_bedrock_sagemaker; the model or inference profile id for aws_bedrock. |
| API key | `api_key` | Credential (vault) | No |  | Main; choice Model to use = Set up here | API key or access token from the vault (not needed for some self-hosted endpoints). For aws_bedrock and aws_bedrock_sagemaker: the AWS secret access key (or ACCESS_KEY_ID:SECRET). For google_vertex: a service-account JSON key or an access token. |
| Endpoint address | `base_url` | Text | No |  | Main; choice Model to use = Set up here | Endpoint; required for Azure OpenAI, Vertex AI (https://REGION-aiplatform.googleapis.com/v1/projects/PROJECT/locations/REGION) and self-hosted models (for a TGI server, e.g. http://tgi-host:8080/v1). |
| AWS access key ID | `aws_access_key_id` | Credential (vault) | No |  | Main; choice Model to use = Set up here | aws_bedrock and aws_bedrock_sagemaker only: AWS access key id from the vault (api_key then holds the secret access key). |
| AWS region | `aws_region` | Text | No |  | Main; choice Model to use = Set up here | aws_bedrock and aws_bedrock_sagemaker only: AWS region, e.g. eu-west-1. |
| Language | `language` | Text | No |  | More options | Language of the text, e.g. en or de; worked out from the text when empty. Example: `de` |
| Types | `types` | List | No |  | More options | Only these types (PERSON, ORGANIZATION, LOCATION, DATE, QUANTITY, EVENT, TITLE, COMMERCIAL_ITEM, OTHER); all when empty. |
| Hide personal data | `mask_pii` | Yes/No | No | No | More options | Replace emails, phone and card numbers, IBANs and similar before sending; restored in the answer. Off by default because names and numbers are what you are looking for. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `entities` | Table | Columns: text, type, score. |
| `by_type` | Dictionary | Type to the list of texts of that type. |

*Time limit 300 s; needs: network, ai.*

### Extract fields

`ooa.genai/extract` (action)

Extract named fields from text (an email, a contract) into a Dictionary; missing fields come back empty.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | Yes |  | Main | Source text. |
| Fields | `fields` | Dictionary | Yes |  | Main | Field name to description, e.g. {"invoice_number": "the invoice id", "total": "amount due as a number"}. |
| Connection | `connection` | Text | No |  | Main; choice Model to use = A saved connection | Name of a Model Connection (provider, model and key managed in one place). Leave empty to set them on this action. |
| Provider | `provider` | Text | No | `openai` | Main; choice Model to use = Set up here | openai, azure_openai, anthropic, openai_compatible (LiteLLM, vLLM, Ollama), huggingface (Hugging Face router or your own TGI server), aws_bedrock_sagemaker (a model on an Amazon SageMaker endpoint), aws_bedrock (Amazon Bedrock) or google_vertex (Gemini on Google Vertex AI). One of: `openai`, `azure_openai`, `anthropic`, `openai_compatible`, `huggingface`, `aws_bedrock_sagemaker`, `aws_bedrock`, `google_vertex` |
| Model | `model` | Text | No |  | Main; choice Model to use = Set up here | Model name; the deployment name for Azure OpenAI; the endpoint name for aws_bedrock_sagemaker; the model or inference profile id for aws_bedrock. |
| API key | `api_key` | Credential (vault) | No |  | Main; choice Model to use = Set up here | API key or access token from the vault (not needed for some self-hosted endpoints). For aws_bedrock and aws_bedrock_sagemaker: the AWS secret access key (or ACCESS_KEY_ID:SECRET). For google_vertex: a service-account JSON key or an access token. |
| Endpoint address | `base_url` | Text | No |  | Main; choice Model to use = Set up here | Endpoint; required for Azure OpenAI, Vertex AI (https://REGION-aiplatform.googleapis.com/v1/projects/PROJECT/locations/REGION) and self-hosted models (for a TGI server, e.g. http://tgi-host:8080/v1). |
| AWS access key ID | `aws_access_key_id` | Credential (vault) | No |  | Main; choice Model to use = Set up here | aws_bedrock and aws_bedrock_sagemaker only: AWS access key id from the vault (api_key then holds the secret access key). |
| AWS region | `aws_region` | Text | No |  | Main; choice Model to use = Set up here | aws_bedrock and aws_bedrock_sagemaker only: AWS region, e.g. eu-west-1. |
| Minimum confidence | `min_confidence` | Number | No | `0` | More options | 0 (off) to 1: the model also says how sure it is of each field; fields below this send the values to a person (needs_review, validation). Range: 0 to 1 |
| Hide personal data | `mask_pii` | Yes/No | No | No | More options | Replace emails, phone and card numbers, IBANs and similar before sending; restored in the answer. Off by default because extraction often needs those details. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `fields` | Dictionary | Field name to value. |
| `confidence` | Dictionary | With min_confidence: field name to how sure the model is, from 0 to 1 (0 for a missing value). |
| `needs_review` | Yes/No | With min_confidence: true when any field is below it, so a person should check the values. |
| `validation` | Dictionary | With min_confidence and a field below it: {fields, confidence, min_confidence}. Return it as the agent's output named validation from a work-queue run, and the item waits in the queue for a person to check it. |

*Time limit 300 s; needs: network, ai.*

### Generate picture

`ooa.genai/generate_image` (action)

Draw pictures from a description and save them as PNG files (OpenAI, Azure OpenAI, OpenAI-compatible, Vertex AI Imagen, Amazon Bedrock).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Prompt | `prompt` | Text | Yes |  | Main | What the picture shows, its style and colours. |
| Output folder | `output_folder` | Text | No | `generated-images` | Main | Folder for the pictures (created when missing). |
| Number of images | `number_of_images` | Number | No | `1` | Main | How many pictures, 1 to 4. |
| Connection | `connection` | Text | No |  | Main; choice Model to use = A saved connection | Name of a Model Connection (provider, model and key managed in one place). Leave empty to set them on this action. |
| Provider | `provider` | Text | No | `openai` | Main; choice Model to use = Set up here | openai, azure_openai, anthropic, openai_compatible (LiteLLM, vLLM, Ollama), huggingface (Hugging Face router or your own TGI server), aws_bedrock_sagemaker (a model on an Amazon SageMaker endpoint), aws_bedrock (Amazon Bedrock) or google_vertex (Gemini on Google Vertex AI). One of: `openai`, `azure_openai`, `anthropic`, `openai_compatible`, `huggingface`, `aws_bedrock_sagemaker`, `aws_bedrock`, `google_vertex` |
| Model | `model` | Text | No |  | Main; choice Model to use = Set up here | Model name; the deployment name for Azure OpenAI; the endpoint name for aws_bedrock_sagemaker; the model or inference profile id for aws_bedrock. |
| API key | `api_key` | Credential (vault) | No |  | Main; choice Model to use = Set up here | API key or access token from the vault (not needed for some self-hosted endpoints). For aws_bedrock and aws_bedrock_sagemaker: the AWS secret access key (or ACCESS_KEY_ID:SECRET). For google_vertex: a service-account JSON key or an access token. |
| Endpoint address | `base_url` | Text | No |  | Main; choice Model to use = Set up here | Endpoint; required for Azure OpenAI, Vertex AI (https://REGION-aiplatform.googleapis.com/v1/projects/PROJECT/locations/REGION) and self-hosted models (for a TGI server, e.g. http://tgi-host:8080/v1). |
| AWS access key ID | `aws_access_key_id` | Credential (vault) | No |  | Main; choice Model to use = Set up here | aws_bedrock and aws_bedrock_sagemaker only: AWS access key id from the vault (api_key then holds the secret access key). |
| AWS region | `aws_region` | Text | No |  | Main; choice Model to use = Set up here | aws_bedrock and aws_bedrock_sagemaker only: AWS region, e.g. eu-west-1. |
| File name | `file_name` | Text | No | `picture` | More options | File name start; pictures are numbered: picture_1.png, picture_2.png. |
| Size | `size` | Text | No | `1024x1024` | More options | Width x height in pixels; providers that work with aspect ratios use the nearest one. Example: `1792x1024` |
| Hide personal data | `mask_pii` | Yes/No | No | Yes | More options | Replace emails, phone and card numbers, IBANs and similar before sending; restored in the answer. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `0` | More options | Seconds to wait for the model; 180 when 0. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `file` | Text | The first picture's file. |
| `files` | List | Every picture's file. |

*Time limit 600 s; needs: network, ai, filesystem.*

### Key phrases

`ooa.genai/key_phrases` (action)

List the phrases that say what a text is about, most important first.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | Yes |  | Main | Text, e.g. a support ticket. |
| Connection | `connection` | Text | No |  | Main; choice Model to use = A saved connection | Name of a Model Connection (provider, model and key managed in one place). Leave empty to set them on this action. |
| Provider | `provider` | Text | No | `openai` | Main; choice Model to use = Set up here | openai, azure_openai, anthropic, openai_compatible (LiteLLM, vLLM, Ollama), huggingface (Hugging Face router or your own TGI server), aws_bedrock_sagemaker (a model on an Amazon SageMaker endpoint), aws_bedrock (Amazon Bedrock) or google_vertex (Gemini on Google Vertex AI). One of: `openai`, `azure_openai`, `anthropic`, `openai_compatible`, `huggingface`, `aws_bedrock_sagemaker`, `aws_bedrock`, `google_vertex` |
| Model | `model` | Text | No |  | Main; choice Model to use = Set up here | Model name; the deployment name for Azure OpenAI; the endpoint name for aws_bedrock_sagemaker; the model or inference profile id for aws_bedrock. |
| API key | `api_key` | Credential (vault) | No |  | Main; choice Model to use = Set up here | API key or access token from the vault (not needed for some self-hosted endpoints). For aws_bedrock and aws_bedrock_sagemaker: the AWS secret access key (or ACCESS_KEY_ID:SECRET). For google_vertex: a service-account JSON key or an access token. |
| Endpoint address | `base_url` | Text | No |  | Main; choice Model to use = Set up here | Endpoint; required for Azure OpenAI, Vertex AI (https://REGION-aiplatform.googleapis.com/v1/projects/PROJECT/locations/REGION) and self-hosted models (for a TGI server, e.g. http://tgi-host:8080/v1). |
| AWS access key ID | `aws_access_key_id` | Credential (vault) | No |  | Main; choice Model to use = Set up here | aws_bedrock and aws_bedrock_sagemaker only: AWS access key id from the vault (api_key then holds the secret access key). |
| AWS region | `aws_region` | Text | No |  | Main; choice Model to use = Set up here | aws_bedrock and aws_bedrock_sagemaker only: AWS region, e.g. eu-west-1. |
| Language | `language` | Text | No |  | More options | Language of the text, e.g. en or de; worked out from the text when empty. Example: `de` |
| Maximum phrases | `max_phrases` | Number | No | `10` | More options | Most phrases to return, 1 to 50. |
| Hide personal data | `mask_pii` | Yes/No | No | Yes | More options | Replace emails, phone and card numbers, IBANs and similar before sending; restored in the answer. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `phrases` | List | Phrases as they appear in the text. |

*Time limit 300 s; needs: network, ai.*

### Prompt

`ooa.genai/prompt` (action)

Send a prompt and return the model's answer.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Prompt | `prompt` | Text | Yes |  | Main | Your request; ${...} interpolation allowed. |
| System | `system` | Text | No | `You are a precise business assistant.` | Main | Instructions for the model's role and tone. |
| Connection | `connection` | Text | No |  | Main; choice Model to use = A saved connection | Name of a Model Connection (provider, model and key managed in one place). Leave empty to set them on this action. |
| Provider | `provider` | Text | No | `openai` | Main; choice Model to use = Set up here | openai, azure_openai, anthropic, openai_compatible (LiteLLM, vLLM, Ollama), huggingface (Hugging Face router or your own TGI server), aws_bedrock_sagemaker (a model on an Amazon SageMaker endpoint), aws_bedrock (Amazon Bedrock) or google_vertex (Gemini on Google Vertex AI). One of: `openai`, `azure_openai`, `anthropic`, `openai_compatible`, `huggingface`, `aws_bedrock_sagemaker`, `aws_bedrock`, `google_vertex` |
| Model | `model` | Text | No |  | Main; choice Model to use = Set up here | Model name; the deployment name for Azure OpenAI; the endpoint name for aws_bedrock_sagemaker; the model or inference profile id for aws_bedrock. |
| API key | `api_key` | Credential (vault) | No |  | Main; choice Model to use = Set up here | API key or access token from the vault (not needed for some self-hosted endpoints). For aws_bedrock and aws_bedrock_sagemaker: the AWS secret access key (or ACCESS_KEY_ID:SECRET). For google_vertex: a service-account JSON key or an access token. |
| Endpoint address | `base_url` | Text | No |  | Main; choice Model to use = Set up here | Endpoint; required for Azure OpenAI, Vertex AI (https://REGION-aiplatform.googleapis.com/v1/projects/PROJECT/locations/REGION) and self-hosted models (for a TGI server, e.g. http://tgi-host:8080/v1). |
| AWS access key ID | `aws_access_key_id` | Credential (vault) | No |  | Main; choice Model to use = Set up here | aws_bedrock and aws_bedrock_sagemaker only: AWS access key id from the vault (api_key then holds the secret access key). |
| AWS region | `aws_region` | Text | No |  | Main; choice Model to use = Set up here | aws_bedrock and aws_bedrock_sagemaker only: AWS region, e.g. eu-west-1. |
| Temperature | `temperature` | Number | No | `0` | More options | 0 = consistent, higher = more creative. |
| Maximum tokens | `max_tokens` | Number | No | `1024` | More options | Maximum answer length in tokens. |
| Response format | `response_format` | Text | No | `text` | More options | json: ask for one JSON object and also return it parsed. One of: `text`, `json` |
| Hide personal data | `mask_pii` | Yes/No | No | Yes | More options | Replace emails, phone and card numbers, IBANs and similar before sending; restored in the answer. |
| Top p | `top_p` | Number | No |  | More options | Nucleus sampling: only the most likely words whose chances add up to this share are used (above 0 to 1); provider default when empty. |
| Top k | `top_k` | Number | No |  | More options | Only pick from this many most likely words (Anthropic models). |
| Stop sequences | `stop_sequences` | List | No |  | More options | Up to 4 texts at which the model stops writing. |
| Presence penalty | `presence_penalty` | Number | No |  | More options | From -2 to 2; higher values push the model toward new topics (OpenAI-style providers). |
| Frequency penalty | `frequency_penalty` | Number | No |  | More options | From -2 to 2; higher values make the model repeat itself less (OpenAI-style providers). |
| Seed | `seed` | Number | No |  | More options | Same seed and settings give the same answer where the provider supports it. |
| Number of responses | `number_of_responses` | Number | No | `1` | More options | How many alternative answers to ask for, 1 to 10 (OpenAI-style providers). |
| End user | `end_user` | Text | No |  | More options | An id for the person the answer is for, passed on for the provider's abuse monitoring (OpenAI-style providers). |
| Timeout (seconds) | `timeout_seconds` | Number | No | `0` | More options | Seconds to wait for the model; 180 when 0. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `text` | Text | Answer (the first one when several were asked for). |
| `responses` | List | Every answer, when number_of_responses is more than 1. |
| `json` | Any | The answer parsed as JSON, when response_format is json. |
| `input_tokens` | Number | Tokens in the request. |
| `output_tokens` | Number | Tokens in the answer. |

*Time limit 600 s; needs: network, ai.*

### Prompt with pictures

`ooa.genai/prompt_with_images` (action)

Ask about one or more pictures (a scanned form, a screenshot, a photo of a meter) and return the model's answer.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Prompt | `prompt` | Text | Yes |  | Main | What you want to know about the pictures; ${...} interpolation allowed. |
| Images | `images` | List | Yes |  | Main | Picture files: .png, .jpg, .gif or .webp, at most 10, each up to 20 MB. |
| System | `system` | Text | No | `You are a precise business assistant.` | Main | Instructions for the model's role and tone. |
| Connection | `connection` | Text | No |  | Main; choice Model to use = A saved connection | Name of a Model Connection (provider, model and key managed in one place). Leave empty to set them on this action. |
| Provider | `provider` | Text | No | `openai` | Main; choice Model to use = Set up here | openai, azure_openai, anthropic, openai_compatible (LiteLLM, vLLM, Ollama), huggingface (Hugging Face router or your own TGI server), aws_bedrock_sagemaker (a model on an Amazon SageMaker endpoint), aws_bedrock (Amazon Bedrock) or google_vertex (Gemini on Google Vertex AI). One of: `openai`, `azure_openai`, `anthropic`, `openai_compatible`, `huggingface`, `aws_bedrock_sagemaker`, `aws_bedrock`, `google_vertex` |
| Model | `model` | Text | No |  | Main; choice Model to use = Set up here | Model name; the deployment name for Azure OpenAI; the endpoint name for aws_bedrock_sagemaker; the model or inference profile id for aws_bedrock. |
| API key | `api_key` | Credential (vault) | No |  | Main; choice Model to use = Set up here | API key or access token from the vault (not needed for some self-hosted endpoints). For aws_bedrock and aws_bedrock_sagemaker: the AWS secret access key (or ACCESS_KEY_ID:SECRET). For google_vertex: a service-account JSON key or an access token. |
| Endpoint address | `base_url` | Text | No |  | Main; choice Model to use = Set up here | Endpoint; required for Azure OpenAI, Vertex AI (https://REGION-aiplatform.googleapis.com/v1/projects/PROJECT/locations/REGION) and self-hosted models (for a TGI server, e.g. http://tgi-host:8080/v1). |
| AWS access key ID | `aws_access_key_id` | Credential (vault) | No |  | Main; choice Model to use = Set up here | aws_bedrock and aws_bedrock_sagemaker only: AWS access key id from the vault (api_key then holds the secret access key). |
| AWS region | `aws_region` | Text | No |  | Main; choice Model to use = Set up here | aws_bedrock and aws_bedrock_sagemaker only: AWS region, e.g. eu-west-1. |
| Temperature | `temperature` | Number | No | `0` | More options | 0 = consistent, higher = more creative. |
| Maximum tokens | `max_tokens` | Number | No | `1024` | More options | Maximum answer length in tokens. |
| Response format | `response_format` | Text | No | `text` | More options | json: ask for one JSON object and also return it parsed. One of: `text`, `json` |
| Hide personal data | `mask_pii` | Yes/No | No | Yes | More options | Replace emails, phone and card numbers, IBANs and similar before sending; restored in the answer. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `0` | More options | Seconds to wait for the model; 180 when 0. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `text` | Text | Answer. |
| `json` | Any | The answer parsed as JSON, when response_format is json. |
| `input_tokens` | Number | Tokens in the request (pictures included). |
| `output_tokens` | Number | Tokens in the answer. |

*Time limit 600 s; needs: network, ai.*

### Sentiment

`ooa.genai/sentiment` (action)

Rate text as positive, neutral or negative, with a score from -1 to 1.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | Yes |  | Main | Text, e.g. a customer message. |
| Connection | `connection` | Text | No |  | Main; choice Model to use = A saved connection | Name of a Model Connection (provider, model and key managed in one place). Leave empty to set them on this action. |
| Provider | `provider` | Text | No | `openai` | Main; choice Model to use = Set up here | openai, azure_openai, anthropic, openai_compatible (LiteLLM, vLLM, Ollama), huggingface (Hugging Face router or your own TGI server), aws_bedrock_sagemaker (a model on an Amazon SageMaker endpoint), aws_bedrock (Amazon Bedrock) or google_vertex (Gemini on Google Vertex AI). One of: `openai`, `azure_openai`, `anthropic`, `openai_compatible`, `huggingface`, `aws_bedrock_sagemaker`, `aws_bedrock`, `google_vertex` |
| Model | `model` | Text | No |  | Main; choice Model to use = Set up here | Model name; the deployment name for Azure OpenAI; the endpoint name for aws_bedrock_sagemaker; the model or inference profile id for aws_bedrock. |
| API key | `api_key` | Credential (vault) | No |  | Main; choice Model to use = Set up here | API key or access token from the vault (not needed for some self-hosted endpoints). For aws_bedrock and aws_bedrock_sagemaker: the AWS secret access key (or ACCESS_KEY_ID:SECRET). For google_vertex: a service-account JSON key or an access token. |
| Endpoint address | `base_url` | Text | No |  | Main; choice Model to use = Set up here | Endpoint; required for Azure OpenAI, Vertex AI (https://REGION-aiplatform.googleapis.com/v1/projects/PROJECT/locations/REGION) and self-hosted models (for a TGI server, e.g. http://tgi-host:8080/v1). |
| AWS access key ID | `aws_access_key_id` | Credential (vault) | No |  | Main; choice Model to use = Set up here | aws_bedrock and aws_bedrock_sagemaker only: AWS access key id from the vault (api_key then holds the secret access key). |
| AWS region | `aws_region` | Text | No |  | Main; choice Model to use = Set up here | aws_bedrock and aws_bedrock_sagemaker only: AWS region, e.g. eu-west-1. |
| Language | `language` | Text | No |  | More options | Language of the text, e.g. en or de; worked out from the text when empty. Example: `de` |
| Hide personal data | `mask_pii` | Yes/No | No | Yes | More options | Replace emails, phone and card numbers, IBANs and similar before sending; restored in the answer. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `sentiment` | Text | positive, neutral or negative. |
| `score` | Number | From -1 (very negative) to 1 (very positive). |

*Time limit 300 s; needs: network, ai.*

### Summarise

`ooa.genai/summarize` (action)

Summarise text in a few sentences, keeping figures exact.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | Yes |  | Main | Text to summarise. |
| Sentences | `sentences` | Number | No | `3` | Main | Target length in sentences. |
| Connection | `connection` | Text | No |  | Main; choice Model to use = A saved connection | Name of a Model Connection (provider, model and key managed in one place). Leave empty to set them on this action. |
| Provider | `provider` | Text | No | `openai` | Main; choice Model to use = Set up here | openai, azure_openai, anthropic, openai_compatible (LiteLLM, vLLM, Ollama), huggingface (Hugging Face router or your own TGI server), aws_bedrock_sagemaker (a model on an Amazon SageMaker endpoint), aws_bedrock (Amazon Bedrock) or google_vertex (Gemini on Google Vertex AI). One of: `openai`, `azure_openai`, `anthropic`, `openai_compatible`, `huggingface`, `aws_bedrock_sagemaker`, `aws_bedrock`, `google_vertex` |
| Model | `model` | Text | No |  | Main; choice Model to use = Set up here | Model name; the deployment name for Azure OpenAI; the endpoint name for aws_bedrock_sagemaker; the model or inference profile id for aws_bedrock. |
| API key | `api_key` | Credential (vault) | No |  | Main; choice Model to use = Set up here | API key or access token from the vault (not needed for some self-hosted endpoints). For aws_bedrock and aws_bedrock_sagemaker: the AWS secret access key (or ACCESS_KEY_ID:SECRET). For google_vertex: a service-account JSON key or an access token. |
| Endpoint address | `base_url` | Text | No |  | Main; choice Model to use = Set up here | Endpoint; required for Azure OpenAI, Vertex AI (https://REGION-aiplatform.googleapis.com/v1/projects/PROJECT/locations/REGION) and self-hosted models (for a TGI server, e.g. http://tgi-host:8080/v1). |
| AWS access key ID | `aws_access_key_id` | Credential (vault) | No |  | Main; choice Model to use = Set up here | aws_bedrock and aws_bedrock_sagemaker only: AWS access key id from the vault (api_key then holds the secret access key). |
| AWS region | `aws_region` | Text | No |  | Main; choice Model to use = Set up here | aws_bedrock and aws_bedrock_sagemaker only: AWS region, e.g. eu-west-1. |
| Hide personal data | `mask_pii` | Yes/No | No | Yes | More options | Replace emails, phone and card numbers, IBANs and similar before sending; restored in the answer. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `summary` | Text | Summary. |

*Time limit 300 s; needs: network, ai.*

### Translate

`ooa.genai/translate` (action)

Translate text into another language.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | Yes |  | Main | Text to translate. |
| Target language | `target_language` | Text | Yes |  | Main | Language to translate into. Example: `German` |
| Connection | `connection` | Text | No |  | Main; choice Model to use = A saved connection | Name of a Model Connection (provider, model and key managed in one place). Leave empty to set them on this action. |
| Provider | `provider` | Text | No | `openai` | Main; choice Model to use = Set up here | openai, azure_openai, anthropic, openai_compatible (LiteLLM, vLLM, Ollama), huggingface (Hugging Face router or your own TGI server), aws_bedrock_sagemaker (a model on an Amazon SageMaker endpoint), aws_bedrock (Amazon Bedrock) or google_vertex (Gemini on Google Vertex AI). One of: `openai`, `azure_openai`, `anthropic`, `openai_compatible`, `huggingface`, `aws_bedrock_sagemaker`, `aws_bedrock`, `google_vertex` |
| Model | `model` | Text | No |  | Main; choice Model to use = Set up here | Model name; the deployment name for Azure OpenAI; the endpoint name for aws_bedrock_sagemaker; the model or inference profile id for aws_bedrock. |
| API key | `api_key` | Credential (vault) | No |  | Main; choice Model to use = Set up here | API key or access token from the vault (not needed for some self-hosted endpoints). For aws_bedrock and aws_bedrock_sagemaker: the AWS secret access key (or ACCESS_KEY_ID:SECRET). For google_vertex: a service-account JSON key or an access token. |
| Endpoint address | `base_url` | Text | No |  | Main; choice Model to use = Set up here | Endpoint; required for Azure OpenAI, Vertex AI (https://REGION-aiplatform.googleapis.com/v1/projects/PROJECT/locations/REGION) and self-hosted models (for a TGI server, e.g. http://tgi-host:8080/v1). |
| AWS access key ID | `aws_access_key_id` | Credential (vault) | No |  | Main; choice Model to use = Set up here | aws_bedrock and aws_bedrock_sagemaker only: AWS access key id from the vault (api_key then holds the secret access key). |
| AWS region | `aws_region` | Text | No |  | Main; choice Model to use = Set up here | aws_bedrock and aws_bedrock_sagemaker only: AWS region, e.g. eu-west-1. |
| Hide personal data | `mask_pii` | Yes/No | No | Yes | More options | Replace emails, phone and card numbers, IBANs and similar before sending; restored in the answer. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `text` | Text | Translation. |

*Time limit 300 s; needs: network, ai.*
