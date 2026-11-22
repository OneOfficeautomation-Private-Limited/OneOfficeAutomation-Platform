# Generative AI

Toolkit `ooa.genai`, version 1.1.0. Prompt large language models (OpenAI, Azure OpenAI, Anthropic, Amazon Bedrock, Google Vertex AI, self-hosted) to answer, read pictures, draw pictures and analyse text.

[All toolkits](README.md)

## Contents

- **Generative AI**: Chat, Classify, Detect language, Detect personal data, End chat, Named entities, Extract fields, Generate picture, Hugging Face model, Call SageMaker endpoint, Key phrases, Prompt, Prompt with pictures, Sentiment, Summarise, Translate

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
| Provider | `provider` | Text | No | `openai` | Main; choice Model to use = Set up here | Who runs the model: OpenAI, Azure OpenAI, Anthropic, OpenAI-compatible (LiteLLM, vLLM, Ollama), Hugging Face (its router or your own TGI server), AWS SageMaker (a model on an Amazon SageMaker endpoint), AWS Bedrock or Google Vertex AI (Gemini). One of: `openai`, `azure_openai`, `anthropic`, `openai_compatible`, `huggingface`, `aws_bedrock_sagemaker`, `aws_bedrock`, `google_vertex` |
| Model | `model` | Text | No |  | Main; choice Model to use = Set up here | Model name; the deployment name for Azure OpenAI; the endpoint name for AWS SageMaker; the model or inference profile id for AWS Bedrock. |
| API key | `api_key` | Credential (vault) | No |  | Main; choice Model to use = Set up here | API key or access token from the vault (not needed for some self-hosted endpoints). For AWS Bedrock and AWS SageMaker: the AWS secret access key (or ACCESS_KEY_ID:SECRET). For Google Vertex AI: a service-account JSON key or an access token. |
| Endpoint address | `base_url` | Text | No |  | Main; choice Model to use = Set up here | Endpoint; required for Azure OpenAI, Vertex AI (https://REGION-aiplatform.googleapis.com/v1/projects/PROJECT/locations/REGION) and self-hosted models (for a TGI server, e.g. http://tgi-host:8080/v1). |
| AWS access key ID | `aws_access_key_id` | Credential (vault) | No |  | Main; choice Model to use = Set up here | AWS Bedrock and AWS SageMaker only: AWS access key id from the vault (api_key then holds the secret access key). |
| AWS session token | `aws_session_token` | Credential (vault) | No |  | Main; choice Model to use = Set up here | AWS Bedrock and AWS SageMaker only: the session token from the vault, when the access key is a temporary one (for example from an assumed role). |
| AWS region | `aws_region` | Text | No |  | Main; choice Model to use = Set up here | AWS Bedrock and AWS SageMaker only: AWS region, e.g. eu-west-1. |
| Images | `images` | List | No |  | Main | Pictures: file paths (.png, .jpg, .gif, .webp) or web links starting with http:// or https://, which the provider fetches (not Amazon Bedrock). The model must accept pictures. |
| Temperature | `temperature` | Number | No | `0` | More options | 0 = consistent, higher = more creative. |
| Maximum tokens | `max_tokens` | Number | No | `1024` | More options | Maximum reply length in tokens. |
| Response format | `response_format` | Text | No | `text` | More options | json: ask for one JSON object and also return it parsed. One of: `text`, `json` |
| Hide personal data | `mask_pii` | Yes/No | No | Yes | More options | Replace emails, phone and card numbers, IBANs and similar before sending; restored in the answer. |
| History limit | `history_limit` | Number | No | `40` | More options | Most messages to keep in the session; the oldest are dropped first. |
| Examples | `examples` | Dictionary | No |  | More options | Sample questions and the answers you want, question to answer; sent before the conversation as earlier turns so the model follows the pattern. Example: `{"Where is my order 123?": "intent: order_status"}` |
| Image detail | `image_detail` | Text | No |  | More options | How closely the model looks at pictures (OpenAI and Azure OpenAI): low is quicker and cheaper, high reads fine print; auto lets the model decide. One of: `auto`, `high`, `low` |
| Top p | `top_p` | Number | No |  | More options | Nucleus sampling: only the most likely words whose chances add up to this share are used (above 0 to 1); provider default when empty. |
| Top k | `top_k` | Number | No |  | More options | Only pick from this many most likely words (Anthropic models). |
| Stop sequences | `stop_sequences` | List | No |  | More options | Up to 4 texts at which the model stops writing. |
| Presence penalty | `presence_penalty` | Number | No |  | More options | From -2 to 2; higher values push the model toward new topics (OpenAI-style providers). |
| Frequency penalty | `frequency_penalty` | Number | No |  | More options | From -2 to 2; higher values make the model repeat itself less (OpenAI-style providers). |
| Seed | `seed` | Number | No |  | More options | Same seed and settings give the same answer where the provider supports it. |
| End user | `end_user` | Text | No |  | More options | An id for the person the answer is for, passed on for the provider's abuse monitoring (OpenAI-style providers). |
| Logit bias | `logit_bias` | Dictionary | No |  | More options | Make words more or less likely (OpenAI and Azure OpenAI): token id to a weight from -100 (never) to 100 (always), e.g. {"50256": -100}. |
| Provider options | `provider_options` | Dictionary | No |  | More options | Extra settings for this provider, added to the request as they are, e.g. {"reasoning_effort": "low"}; a dictionary is merged into the setting of the same name. |
| API version | `api_version` | Text | No | `2024-06-01` | More options | Azure OpenAI only: the API version to call; change it when your resource needs a newer one. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `0` | More options | Seconds to wait for the model; 180 when 0. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `text` | Text | The model's reply. |
| `json` | Any | The reply parsed as JSON, when response_format is json. |
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
| Provider | `provider` | Text | No | `openai` | Main; choice Model to use = Set up here | Who runs the model: OpenAI, Azure OpenAI, Anthropic, OpenAI-compatible (LiteLLM, vLLM, Ollama), Hugging Face (its router or your own TGI server), AWS SageMaker (a model on an Amazon SageMaker endpoint), AWS Bedrock or Google Vertex AI (Gemini). One of: `openai`, `azure_openai`, `anthropic`, `openai_compatible`, `huggingface`, `aws_bedrock_sagemaker`, `aws_bedrock`, `google_vertex` |
| Model | `model` | Text | No |  | Main; choice Model to use = Set up here | Model name; the deployment name for Azure OpenAI; the endpoint name for AWS SageMaker; the model or inference profile id for AWS Bedrock. |
| API key | `api_key` | Credential (vault) | No |  | Main; choice Model to use = Set up here | API key or access token from the vault (not needed for some self-hosted endpoints). For AWS Bedrock and AWS SageMaker: the AWS secret access key (or ACCESS_KEY_ID:SECRET). For Google Vertex AI: a service-account JSON key or an access token. |
| Endpoint address | `base_url` | Text | No |  | Main; choice Model to use = Set up here | Endpoint; required for Azure OpenAI, Vertex AI (https://REGION-aiplatform.googleapis.com/v1/projects/PROJECT/locations/REGION) and self-hosted models (for a TGI server, e.g. http://tgi-host:8080/v1). |
| AWS access key ID | `aws_access_key_id` | Credential (vault) | No |  | Main; choice Model to use = Set up here | AWS Bedrock and AWS SageMaker only: AWS access key id from the vault (api_key then holds the secret access key). |
| AWS region | `aws_region` | Text | No |  | Main; choice Model to use = Set up here | AWS Bedrock and AWS SageMaker only: AWS region, e.g. eu-west-1. |
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
| Provider | `provider` | Text | No | `openai` | Main; choice Model to use = Set up here | Who runs the model: OpenAI, Azure OpenAI, Anthropic, OpenAI-compatible (LiteLLM, vLLM, Ollama), Hugging Face (its router or your own TGI server), AWS SageMaker (a model on an Amazon SageMaker endpoint), AWS Bedrock or Google Vertex AI (Gemini). One of: `openai`, `azure_openai`, `anthropic`, `openai_compatible`, `huggingface`, `aws_bedrock_sagemaker`, `aws_bedrock`, `google_vertex` |
| Model | `model` | Text | No |  | Main; choice Model to use = Set up here | Model name; the deployment name for Azure OpenAI; the endpoint name for AWS SageMaker; the model or inference profile id for AWS Bedrock. |
| API key | `api_key` | Credential (vault) | No |  | Main; choice Model to use = Set up here | API key or access token from the vault (not needed for some self-hosted endpoints). For AWS Bedrock and AWS SageMaker: the AWS secret access key (or ACCESS_KEY_ID:SECRET). For Google Vertex AI: a service-account JSON key or an access token. |
| Endpoint address | `base_url` | Text | No |  | Main; choice Model to use = Set up here | Endpoint; required for Azure OpenAI, Vertex AI (https://REGION-aiplatform.googleapis.com/v1/projects/PROJECT/locations/REGION) and self-hosted models (for a TGI server, e.g. http://tgi-host:8080/v1). |
| AWS access key ID | `aws_access_key_id` | Credential (vault) | No |  | Main; choice Model to use = Set up here | AWS Bedrock and AWS SageMaker only: AWS access key id from the vault (api_key then holds the secret access key). |
| AWS region | `aws_region` | Text | No |  | Main; choice Model to use = Set up here | AWS Bedrock and AWS SageMaker only: AWS region, e.g. eu-west-1. |
| Hide personal data | `mask_pii` | Yes/No | No | Yes | More options | Replace emails, phone and card numbers, IBANs and similar before sending; restored in the answer. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `language` | Text | Language name in English, e.g. German. |
| `code` | Text | Two-letter ISO 639-1 code, e.g. de. |
| `confidence` | Number | From 0 to 1. |

*Time limit 300 s; needs: network, ai.*

### Detect personal data

`ooa.genai/detect_pii` (action)

Find personal data in text (emails, phone, card and ID numbers, IBANs, IP addresses; with a model also names and addresses) and give a masked copy. Patterns work without a model; numbers with a check digit must pass it.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | Yes |  | Main | Text to check, e.g. an email body or a document's text. |
| Kinds | `kinds` | List | No |  | Main | Only these kinds: email, phone, credit_card, iban, ip_address, us_ssn, in_pan, in_aadhaar, person_name, address (the last two need use_ai). Every kind when empty (names and addresses only with use_ai). |
| Mask style | `mask_style` | Text | No | `partial` | Main | partial keeps a little so values can be told apart (j***@acme.com, last 4 digits of numbers); full masks every character. One of: `partial`, `full` |
| Mask char | `mask_char` | Text | No | `*` | Main | The one character that replaces masked characters. |
| Use AI | `use_ai` | Yes/No | No | No | Main | Also ask the model for people's names and postal addresses; the patterns still find the rest without it. |
| Connection | `connection` | Text | No |  | Main; choice Model to use = A saved connection | Name of a Model Connection (provider, model and key managed in one place). Leave empty to set them on this action. |
| Provider | `provider` | Text | No | `openai` | Main; choice Model to use = Set up here | Who runs the model: OpenAI, Azure OpenAI, Anthropic, OpenAI-compatible (LiteLLM, vLLM, Ollama), Hugging Face (its router or your own TGI server), AWS SageMaker (a model on an Amazon SageMaker endpoint), AWS Bedrock or Google Vertex AI (Gemini). One of: `openai`, `azure_openai`, `anthropic`, `openai_compatible`, `huggingface`, `aws_bedrock_sagemaker`, `aws_bedrock`, `google_vertex` |
| Model | `model` | Text | No |  | Main; choice Model to use = Set up here | Model name; the deployment name for Azure OpenAI; the endpoint name for AWS SageMaker; the model or inference profile id for AWS Bedrock. |
| API key | `api_key` | Credential (vault) | No |  | Main; choice Model to use = Set up here | API key or access token from the vault (not needed for some self-hosted endpoints). For AWS Bedrock and AWS SageMaker: the AWS secret access key (or ACCESS_KEY_ID:SECRET). For Google Vertex AI: a service-account JSON key or an access token. |
| Endpoint address | `base_url` | Text | No |  | Main; choice Model to use = Set up here | Endpoint; required for Azure OpenAI, Vertex AI (https://REGION-aiplatform.googleapis.com/v1/projects/PROJECT/locations/REGION) and self-hosted models (for a TGI server, e.g. http://tgi-host:8080/v1). |
| AWS access key ID | `aws_access_key_id` | Credential (vault) | No |  | Main; choice Model to use = Set up here | AWS Bedrock and AWS SageMaker only: AWS access key id from the vault (api_key then holds the secret access key). |
| AWS region | `aws_region` | Text | No |  | Main; choice Model to use = Set up here | AWS Bedrock and AWS SageMaker only: AWS region, e.g. eu-west-1. |
| Hide personal data | `mask_pii` | Yes/No | No | Yes | More options | Replace emails, phone and card numbers, IBANs and similar before sending; restored in the answer. Only used with use_ai; the model then sees the patterns' finds as placeholders. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `found` | Yes/No | True when any personal data was found. |
| `count` | Number | How many pieces were found. |
| `items` | List | Each piece as {kind, text, start, end}, in text order and never overlapping; start counts from 0 and end is just past the last character. |
| `masked_text` | Text | The text with every piece found masked. |
| `kinds_found` | List | Each kind found, once, e.g. ["email", "phone"]. |

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
| Provider | `provider` | Text | No | `openai` | Main; choice Model to use = Set up here | Who runs the model: OpenAI, Azure OpenAI, Anthropic, OpenAI-compatible (LiteLLM, vLLM, Ollama), Hugging Face (its router or your own TGI server), AWS SageMaker (a model on an Amazon SageMaker endpoint), AWS Bedrock or Google Vertex AI (Gemini). One of: `openai`, `azure_openai`, `anthropic`, `openai_compatible`, `huggingface`, `aws_bedrock_sagemaker`, `aws_bedrock`, `google_vertex` |
| Model | `model` | Text | No |  | Main; choice Model to use = Set up here | Model name; the deployment name for Azure OpenAI; the endpoint name for AWS SageMaker; the model or inference profile id for AWS Bedrock. |
| API key | `api_key` | Credential (vault) | No |  | Main; choice Model to use = Set up here | API key or access token from the vault (not needed for some self-hosted endpoints). For AWS Bedrock and AWS SageMaker: the AWS secret access key (or ACCESS_KEY_ID:SECRET). For Google Vertex AI: a service-account JSON key or an access token. |
| Endpoint address | `base_url` | Text | No |  | Main; choice Model to use = Set up here | Endpoint; required for Azure OpenAI, Vertex AI (https://REGION-aiplatform.googleapis.com/v1/projects/PROJECT/locations/REGION) and self-hosted models (for a TGI server, e.g. http://tgi-host:8080/v1). |
| AWS access key ID | `aws_access_key_id` | Credential (vault) | No |  | Main; choice Model to use = Set up here | AWS Bedrock and AWS SageMaker only: AWS access key id from the vault (api_key then holds the secret access key). |
| AWS region | `aws_region` | Text | No |  | Main; choice Model to use = Set up here | AWS Bedrock and AWS SageMaker only: AWS region, e.g. eu-west-1. |
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
| Provider | `provider` | Text | No | `openai` | Main; choice Model to use = Set up here | Who runs the model: OpenAI, Azure OpenAI, Anthropic, OpenAI-compatible (LiteLLM, vLLM, Ollama), Hugging Face (its router or your own TGI server), AWS SageMaker (a model on an Amazon SageMaker endpoint), AWS Bedrock or Google Vertex AI (Gemini). One of: `openai`, `azure_openai`, `anthropic`, `openai_compatible`, `huggingface`, `aws_bedrock_sagemaker`, `aws_bedrock`, `google_vertex` |
| Model | `model` | Text | No |  | Main; choice Model to use = Set up here | Model name; the deployment name for Azure OpenAI; the endpoint name for AWS SageMaker; the model or inference profile id for AWS Bedrock. |
| API key | `api_key` | Credential (vault) | No |  | Main; choice Model to use = Set up here | API key or access token from the vault (not needed for some self-hosted endpoints). For AWS Bedrock and AWS SageMaker: the AWS secret access key (or ACCESS_KEY_ID:SECRET). For Google Vertex AI: a service-account JSON key or an access token. |
| Endpoint address | `base_url` | Text | No |  | Main; choice Model to use = Set up here | Endpoint; required for Azure OpenAI, Vertex AI (https://REGION-aiplatform.googleapis.com/v1/projects/PROJECT/locations/REGION) and self-hosted models (for a TGI server, e.g. http://tgi-host:8080/v1). |
| AWS access key ID | `aws_access_key_id` | Credential (vault) | No |  | Main; choice Model to use = Set up here | AWS Bedrock and AWS SageMaker only: AWS access key id from the vault (api_key then holds the secret access key). |
| AWS region | `aws_region` | Text | No |  | Main; choice Model to use = Set up here | AWS Bedrock and AWS SageMaker only: AWS region, e.g. eu-west-1. |
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
| Output folder | `output_folder` | File path | No | `generated-images` | Main | Folder for the pictures (created when missing). |
| Number of images | `number_of_images` | Number | No | `1` | Main | How many pictures, 1 to 4. |
| Connection | `connection` | Text | No |  | Main; choice Model to use = A saved connection | Name of a Model Connection (provider, model and key managed in one place). Leave empty to set them on this action. |
| Provider | `provider` | Text | No | `openai` | Main; choice Model to use = Set up here | Who runs the model: OpenAI, Azure OpenAI, Anthropic, OpenAI-compatible (LiteLLM, vLLM, Ollama), Hugging Face (its router or your own TGI server), AWS SageMaker (a model on an Amazon SageMaker endpoint), AWS Bedrock or Google Vertex AI (Gemini). One of: `openai`, `azure_openai`, `anthropic`, `openai_compatible`, `huggingface`, `aws_bedrock_sagemaker`, `aws_bedrock`, `google_vertex` |
| Model | `model` | Text | No |  | Main; choice Model to use = Set up here | Model name; the deployment name for Azure OpenAI; the endpoint name for AWS SageMaker; the model or inference profile id for AWS Bedrock. |
| API key | `api_key` | Credential (vault) | No |  | Main; choice Model to use = Set up here | API key or access token from the vault (not needed for some self-hosted endpoints). For AWS Bedrock and AWS SageMaker: the AWS secret access key (or ACCESS_KEY_ID:SECRET). For Google Vertex AI: a service-account JSON key or an access token. |
| Endpoint address | `base_url` | Text | No |  | Main; choice Model to use = Set up here | Endpoint; required for Azure OpenAI, Vertex AI (https://REGION-aiplatform.googleapis.com/v1/projects/PROJECT/locations/REGION) and self-hosted models (for a TGI server, e.g. http://tgi-host:8080/v1). |
| AWS access key ID | `aws_access_key_id` | Credential (vault) | No |  | Main; choice Model to use = Set up here | AWS Bedrock and AWS SageMaker only: AWS access key id from the vault (api_key then holds the secret access key). |
| AWS session token | `aws_session_token` | Credential (vault) | No |  | Main; choice Model to use = Set up here | AWS Bedrock and AWS SageMaker only: the session token from the vault, when the access key is a temporary one (for example from an assumed role). |
| AWS region | `aws_region` | Text | No |  | Main; choice Model to use = Set up here | AWS Bedrock and AWS SageMaker only: AWS region, e.g. eu-west-1. |
| File name | `file_name` | Text | No | `picture` | More options | File name start; pictures are numbered: picture_1.png, picture_2.png. |
| Size | `size` | Text | No | `1024x1024` | More options | Width x height in pixels; providers that work with aspect ratios use the nearest one. Example: `1792x1024` |
| Prompt strength | `prompt_strength` | Number | No |  | More options | How closely the picture follows the description; higher is closer (Amazon Titan and Nova from 1.1 to 10, Stable Diffusion XL up to 35). Model default when empty. Range: 0 to 35 |
| Steps | `steps` | Number | No |  | More options | How many refining passes the model makes, 10 to 150; more is slower and more detailed (Stable Diffusion XL). Model default when empty. Range: 10 to 150 |
| Seed | `seed` | Number | No |  | More options | Same seed and description give the same picture (Amazon Titan, Nova and Stability models). Random when empty. Range: 0 to 2147483646 |
| Hide personal data | `mask_pii` | Yes/No | No | Yes | More options | Replace emails, phone and card numbers, IBANs and similar before sending; restored in the answer. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `0` | More options | Seconds to wait for the model; 180 when 0. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `file` | Text | The first picture's file. |
| `files` | List | Every picture's file. |

*Time limit 600 s; needs: network, ai, filesystem.*

### Hugging Face model

`ooa.genai/huggingface_inference` (action)

Call a Hugging Face model for any task (classify, fill in, read a picture or sound) with a JSON payload or a data file, and return its answer.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Model | `model` | Text | Yes |  | Main | The model's id on Hugging Face, owner/name. Example: `facebook/bart-large-mnli` |
| Payload | `payload` | Text | No |  | Main; choice Send = A JSON payload | The request as JSON, as the model's task expects it, usually {"inputs": ..., "parameters": {...}}. Example: `{"inputs": "I love this product"}`; Shown when: `{"data_file": [""]}` |
| Data file | `data_file` | File path | No |  | Main; choice Send = A data file | A picture or sound file sent as it is, for tasks that read one (at most 25 MB). Shown when: `{"payload": [""]}` |
| API key | `api_key` | Credential (vault) | No |  | Main | Your Hugging Face access token (hf_...) from the vault; not needed for your own server. |
| Endpoint address | `base_url` | Text | No |  | More options | Address the model id is added to; https://router.huggingface.co/hf-inference/models when empty, or your own inference server. |
| Output file | `output_file` | File path | No |  | More options | Where to save the answer when the model answers with a picture or sound. Example: `out/picture.png` |
| Timeout (seconds) | `timeout_seconds` | Number | No | `0` | More options | Seconds to wait for the model; 180 when 0. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `text` | Text | The model's answer as text (empty when it answered with a file). |
| `json` | Any | The answer parsed as JSON, when it is JSON. |
| `content_type` | Text | The answer's content type. |
| `file` | Text | Where a picture or sound the model made was saved (output_file). |

*Time limit 600 s; needs: network, ai, filesystem.*

### Call SageMaker endpoint

`ooa.genai/invoke_endpoint` (action)

Send a request as it is to any Amazon SageMaker endpoint (a model of any kind: forecasting, classification, pictures) and return its answer as text.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Endpoint name | `endpoint_name` | Text | Yes |  | Main | Name of the SageMaker endpoint. Example: `churn-xgboost-prod` |
| Body | `body` | Text | No |  | Main; choice Request = Typed here | The request body, sent as it is. Example: `{"instances": [[1.5, 2, 0]]}`; Shown when: `{"body_file": [""]}` |
| Body file | `body_file` | File path | No |  | Main; choice Request = From a file | A file whose bytes are the request body (a picture, a CSV or protobuf file); at most 25 MB. Shown when: `{"body": [""]}` |
| Content type | `content_type` | Text | No | `application/json` | Main | What the body is; image/* takes the picture type from the file's extension; custom: give it in custom_content_type. One of: `application/json`, `application/jsonlines`, `text/csv`, `application/x-protobuf`, `image/*`, `custom` |
| Custom content type | `custom_content_type` | Text | No |  | Main | The content type, when content_type is custom. Example: `application/x-npy`; Shown when: `{"content_type": ["custom"]}` |
| Connection | `connection` | Text | No |  | Main; choice Sign-in = A saved connection | Name of a Model Connection (provider, model and key managed in one place). Leave empty to set them on this action. |
| API key | `api_key` | Credential (vault) | No |  | Main; choice Sign-in = Set up here | The AWS secret access key from the vault (or ACCESS_KEY_ID:SECRET). |
| AWS access key ID | `aws_access_key_id` | Credential (vault) | No |  | Main; choice Sign-in = Set up here | The AWS access key id from the vault. |
| AWS session token | `aws_session_token` | Credential (vault) | No |  | Main; choice Sign-in = Set up here | AWS Bedrock and AWS SageMaker only: the session token from the vault, when the access key is a temporary one (for example from an assumed role). |
| AWS region | `aws_region` | Text | No |  | Main; choice Sign-in = Set up here | AWS region of the endpoint. Example: `eu-west-1` |
| Endpoint address | `base_url` | Text | No |  | Main; choice Sign-in = Set up here | Another address for the SageMaker runtime (a private endpoint); https://runtime.sagemaker.REGION.amazonaws.com when empty. |
| Accept | `accept` | Text | No | `*/*` | More options | The answer type to ask for; any when */*. Example: `application/json` |
| Custom attributes | `custom_attributes` | Text | No |  | More options | Extra information passed to the model's code as it is, up to 1024 characters. |
| Enable explanations | `enable_explanations` | Text | No |  | More options | With SageMaker Clarify explanations set up: an expression saying which answers to explain. Example: ``true`` |
| Inference ID | `inference_id` | Text | No |  | More options | Your id for this request, kept with the captured data (up to 64 characters). |
| Target container hostname | `target_container_hostname` | Text | No |  | More options | Which container answers, on an endpoint with several containers called directly. |
| Target model | `target_model` | Text | No |  | More options | Which model answers, on a multi-model endpoint. Example: `model-42.tar.gz` |
| Target variant | `target_variant` | Text | No |  | More options | Which production variant answers, instead of the endpoint's own split. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `0` | More options | Seconds to wait for the model; 180 when 0. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `text` | Text | The endpoint's answer as text. |
| `json` | Any | The answer parsed as JSON, when it is JSON. |
| `content_type` | Text | The answer's content type, as the endpoint gave it. |
| `invoked_variant` | Text | The endpoint variant that answered. |

*Time limit 600 s; needs: network, ai, filesystem.*

### Key phrases

`ooa.genai/key_phrases` (action)

List the phrases that say what a text is about, most important first.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | Yes |  | Main | Text, e.g. a support ticket. |
| Connection | `connection` | Text | No |  | Main; choice Model to use = A saved connection | Name of a Model Connection (provider, model and key managed in one place). Leave empty to set them on this action. |
| Provider | `provider` | Text | No | `openai` | Main; choice Model to use = Set up here | Who runs the model: OpenAI, Azure OpenAI, Anthropic, OpenAI-compatible (LiteLLM, vLLM, Ollama), Hugging Face (its router or your own TGI server), AWS SageMaker (a model on an Amazon SageMaker endpoint), AWS Bedrock or Google Vertex AI (Gemini). One of: `openai`, `azure_openai`, `anthropic`, `openai_compatible`, `huggingface`, `aws_bedrock_sagemaker`, `aws_bedrock`, `google_vertex` |
| Model | `model` | Text | No |  | Main; choice Model to use = Set up here | Model name; the deployment name for Azure OpenAI; the endpoint name for AWS SageMaker; the model or inference profile id for AWS Bedrock. |
| API key | `api_key` | Credential (vault) | No |  | Main; choice Model to use = Set up here | API key or access token from the vault (not needed for some self-hosted endpoints). For AWS Bedrock and AWS SageMaker: the AWS secret access key (or ACCESS_KEY_ID:SECRET). For Google Vertex AI: a service-account JSON key or an access token. |
| Endpoint address | `base_url` | Text | No |  | Main; choice Model to use = Set up here | Endpoint; required for Azure OpenAI, Vertex AI (https://REGION-aiplatform.googleapis.com/v1/projects/PROJECT/locations/REGION) and self-hosted models (for a TGI server, e.g. http://tgi-host:8080/v1). |
| AWS access key ID | `aws_access_key_id` | Credential (vault) | No |  | Main; choice Model to use = Set up here | AWS Bedrock and AWS SageMaker only: AWS access key id from the vault (api_key then holds the secret access key). |
| AWS region | `aws_region` | Text | No |  | Main; choice Model to use = Set up here | AWS Bedrock and AWS SageMaker only: AWS region, e.g. eu-west-1. |
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
| Provider | `provider` | Text | No | `openai` | Main; choice Model to use = Set up here | Who runs the model: OpenAI, Azure OpenAI, Anthropic, OpenAI-compatible (LiteLLM, vLLM, Ollama), Hugging Face (its router or your own TGI server), AWS SageMaker (a model on an Amazon SageMaker endpoint), AWS Bedrock or Google Vertex AI (Gemini). One of: `openai`, `azure_openai`, `anthropic`, `openai_compatible`, `huggingface`, `aws_bedrock_sagemaker`, `aws_bedrock`, `google_vertex` |
| Model | `model` | Text | No |  | Main; choice Model to use = Set up here | Model name; the deployment name for Azure OpenAI; the endpoint name for AWS SageMaker; the model or inference profile id for AWS Bedrock. |
| API key | `api_key` | Credential (vault) | No |  | Main; choice Model to use = Set up here | API key or access token from the vault (not needed for some self-hosted endpoints). For AWS Bedrock and AWS SageMaker: the AWS secret access key (or ACCESS_KEY_ID:SECRET). For Google Vertex AI: a service-account JSON key or an access token. |
| Endpoint address | `base_url` | Text | No |  | Main; choice Model to use = Set up here | Endpoint; required for Azure OpenAI, Vertex AI (https://REGION-aiplatform.googleapis.com/v1/projects/PROJECT/locations/REGION) and self-hosted models (for a TGI server, e.g. http://tgi-host:8080/v1). |
| AWS access key ID | `aws_access_key_id` | Credential (vault) | No |  | Main; choice Model to use = Set up here | AWS Bedrock and AWS SageMaker only: AWS access key id from the vault (api_key then holds the secret access key). |
| AWS session token | `aws_session_token` | Credential (vault) | No |  | Main; choice Model to use = Set up here | AWS Bedrock and AWS SageMaker only: the session token from the vault, when the access key is a temporary one (for example from an assumed role). |
| AWS region | `aws_region` | Text | No |  | Main; choice Model to use = Set up here | AWS Bedrock and AWS SageMaker only: AWS region, e.g. eu-west-1. |
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
| Logit bias | `logit_bias` | Dictionary | No |  | More options | Make words more or less likely (OpenAI and Azure OpenAI): token id to a weight from -100 (never) to 100 (always), e.g. {"50256": -100}. |
| Provider options | `provider_options` | Dictionary | No |  | More options | Extra settings for this provider, added to the request as they are, e.g. {"reasoning_effort": "low"}; a dictionary is merged into the setting of the same name. |
| API version | `api_version` | Text | No | `2024-06-01` | More options | Azure OpenAI only: the API version to call; change it when your resource needs a newer one. |
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

Ask about one or more pictures (a scanned form, a screenshot, a photo of a meter), or about videos with Gemini models, and return the model's answer.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Prompt | `prompt` | Text | Yes |  | Main | What you want to know about the pictures; ${...} interpolation allowed. |
| Images | `images` | List | No |  | Main | Picture files: .png, .jpg, .gif or .webp, at most 10, each up to 20 MB; or web links starting with http:// or https://, which the provider fetches (not Amazon Bedrock). |
| Videos | `videos` | List | No |  | Main | Video files for Gemini models (provider google_vertex): .mp4, .mov, .mpeg, .avi, .wmv, .webm, .flv or .3gp, at most 5, each up to 20 MB. |
| System | `system` | Text | No | `You are a precise business assistant.` | Main | Instructions for the model's role and tone. |
| Connection | `connection` | Text | No |  | Main; choice Model to use = A saved connection | Name of a Model Connection (provider, model and key managed in one place). Leave empty to set them on this action. |
| Provider | `provider` | Text | No | `openai` | Main; choice Model to use = Set up here | Who runs the model: OpenAI, Azure OpenAI, Anthropic, OpenAI-compatible (LiteLLM, vLLM, Ollama), Hugging Face (its router or your own TGI server), AWS SageMaker (a model on an Amazon SageMaker endpoint), AWS Bedrock or Google Vertex AI (Gemini). One of: `openai`, `azure_openai`, `anthropic`, `openai_compatible`, `huggingface`, `aws_bedrock_sagemaker`, `aws_bedrock`, `google_vertex` |
| Model | `model` | Text | No |  | Main; choice Model to use = Set up here | Model name; the deployment name for Azure OpenAI; the endpoint name for AWS SageMaker; the model or inference profile id for AWS Bedrock. |
| API key | `api_key` | Credential (vault) | No |  | Main; choice Model to use = Set up here | API key or access token from the vault (not needed for some self-hosted endpoints). For AWS Bedrock and AWS SageMaker: the AWS secret access key (or ACCESS_KEY_ID:SECRET). For Google Vertex AI: a service-account JSON key or an access token. |
| Endpoint address | `base_url` | Text | No |  | Main; choice Model to use = Set up here | Endpoint; required for Azure OpenAI, Vertex AI (https://REGION-aiplatform.googleapis.com/v1/projects/PROJECT/locations/REGION) and self-hosted models (for a TGI server, e.g. http://tgi-host:8080/v1). |
| AWS access key ID | `aws_access_key_id` | Credential (vault) | No |  | Main; choice Model to use = Set up here | AWS Bedrock and AWS SageMaker only: AWS access key id from the vault (api_key then holds the secret access key). |
| AWS session token | `aws_session_token` | Credential (vault) | No |  | Main; choice Model to use = Set up here | AWS Bedrock and AWS SageMaker only: the session token from the vault, when the access key is a temporary one (for example from an assumed role). |
| AWS region | `aws_region` | Text | No |  | Main; choice Model to use = Set up here | AWS Bedrock and AWS SageMaker only: AWS region, e.g. eu-west-1. |
| Temperature | `temperature` | Number | No | `0` | More options | 0 = consistent, higher = more creative. |
| Maximum tokens | `max_tokens` | Number | No | `1024` | More options | Maximum answer length in tokens. |
| Response format | `response_format` | Text | No | `text` | More options | json: ask for one JSON object and also return it parsed. One of: `text`, `json` |
| Hide personal data | `mask_pii` | Yes/No | No | Yes | More options | Replace emails, phone and card numbers, IBANs and similar before sending; restored in the answer. |
| Image detail | `image_detail` | Text | No |  | More options | How closely the model looks at pictures (OpenAI and Azure OpenAI): low is quicker and cheaper, high reads fine print; auto lets the model decide. One of: `auto`, `high`, `low` |
| Top p | `top_p` | Number | No |  | More options | Nucleus sampling: only the most likely words whose chances add up to this share are used (above 0 to 1); provider default when empty. |
| Top k | `top_k` | Number | No |  | More options | Only pick from this many most likely words (Anthropic models). |
| Stop sequences | `stop_sequences` | List | No |  | More options | Up to 4 texts at which the model stops writing. |
| Presence penalty | `presence_penalty` | Number | No |  | More options | From -2 to 2; higher values push the model toward new topics (OpenAI-style providers). |
| Frequency penalty | `frequency_penalty` | Number | No |  | More options | From -2 to 2; higher values make the model repeat itself less (OpenAI-style providers). |
| Seed | `seed` | Number | No |  | More options | Same seed and settings give the same answer where the provider supports it. |
| Provider options | `provider_options` | Dictionary | No |  | More options | Extra settings for this provider, added to the request as they are, e.g. {"reasoning_effort": "low"}; a dictionary is merged into the setting of the same name. |
| API version | `api_version` | Text | No | `2024-06-01` | More options | Azure OpenAI only: the API version to call; change it when your resource needs a newer one. |
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
| Provider | `provider` | Text | No | `openai` | Main; choice Model to use = Set up here | Who runs the model: OpenAI, Azure OpenAI, Anthropic, OpenAI-compatible (LiteLLM, vLLM, Ollama), Hugging Face (its router or your own TGI server), AWS SageMaker (a model on an Amazon SageMaker endpoint), AWS Bedrock or Google Vertex AI (Gemini). One of: `openai`, `azure_openai`, `anthropic`, `openai_compatible`, `huggingface`, `aws_bedrock_sagemaker`, `aws_bedrock`, `google_vertex` |
| Model | `model` | Text | No |  | Main; choice Model to use = Set up here | Model name; the deployment name for Azure OpenAI; the endpoint name for AWS SageMaker; the model or inference profile id for AWS Bedrock. |
| API key | `api_key` | Credential (vault) | No |  | Main; choice Model to use = Set up here | API key or access token from the vault (not needed for some self-hosted endpoints). For AWS Bedrock and AWS SageMaker: the AWS secret access key (or ACCESS_KEY_ID:SECRET). For Google Vertex AI: a service-account JSON key or an access token. |
| Endpoint address | `base_url` | Text | No |  | Main; choice Model to use = Set up here | Endpoint; required for Azure OpenAI, Vertex AI (https://REGION-aiplatform.googleapis.com/v1/projects/PROJECT/locations/REGION) and self-hosted models (for a TGI server, e.g. http://tgi-host:8080/v1). |
| AWS access key ID | `aws_access_key_id` | Credential (vault) | No |  | Main; choice Model to use = Set up here | AWS Bedrock and AWS SageMaker only: AWS access key id from the vault (api_key then holds the secret access key). |
| AWS region | `aws_region` | Text | No |  | Main; choice Model to use = Set up here | AWS Bedrock and AWS SageMaker only: AWS region, e.g. eu-west-1. |
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
| Provider | `provider` | Text | No | `openai` | Main; choice Model to use = Set up here | Who runs the model: OpenAI, Azure OpenAI, Anthropic, OpenAI-compatible (LiteLLM, vLLM, Ollama), Hugging Face (its router or your own TGI server), AWS SageMaker (a model on an Amazon SageMaker endpoint), AWS Bedrock or Google Vertex AI (Gemini). One of: `openai`, `azure_openai`, `anthropic`, `openai_compatible`, `huggingface`, `aws_bedrock_sagemaker`, `aws_bedrock`, `google_vertex` |
| Model | `model` | Text | No |  | Main; choice Model to use = Set up here | Model name; the deployment name for Azure OpenAI; the endpoint name for AWS SageMaker; the model or inference profile id for AWS Bedrock. |
| API key | `api_key` | Credential (vault) | No |  | Main; choice Model to use = Set up here | API key or access token from the vault (not needed for some self-hosted endpoints). For AWS Bedrock and AWS SageMaker: the AWS secret access key (or ACCESS_KEY_ID:SECRET). For Google Vertex AI: a service-account JSON key or an access token. |
| Endpoint address | `base_url` | Text | No |  | Main; choice Model to use = Set up here | Endpoint; required for Azure OpenAI, Vertex AI (https://REGION-aiplatform.googleapis.com/v1/projects/PROJECT/locations/REGION) and self-hosted models (for a TGI server, e.g. http://tgi-host:8080/v1). |
| AWS access key ID | `aws_access_key_id` | Credential (vault) | No |  | Main; choice Model to use = Set up here | AWS Bedrock and AWS SageMaker only: AWS access key id from the vault (api_key then holds the secret access key). |
| AWS region | `aws_region` | Text | No |  | Main; choice Model to use = Set up here | AWS Bedrock and AWS SageMaker only: AWS region, e.g. eu-west-1. |
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
| Provider | `provider` | Text | No | `openai` | Main; choice Model to use = Set up here | Who runs the model: OpenAI, Azure OpenAI, Anthropic, OpenAI-compatible (LiteLLM, vLLM, Ollama), Hugging Face (its router or your own TGI server), AWS SageMaker (a model on an Amazon SageMaker endpoint), AWS Bedrock or Google Vertex AI (Gemini). One of: `openai`, `azure_openai`, `anthropic`, `openai_compatible`, `huggingface`, `aws_bedrock_sagemaker`, `aws_bedrock`, `google_vertex` |
| Model | `model` | Text | No |  | Main; choice Model to use = Set up here | Model name; the deployment name for Azure OpenAI; the endpoint name for AWS SageMaker; the model or inference profile id for AWS Bedrock. |
| API key | `api_key` | Credential (vault) | No |  | Main; choice Model to use = Set up here | API key or access token from the vault (not needed for some self-hosted endpoints). For AWS Bedrock and AWS SageMaker: the AWS secret access key (or ACCESS_KEY_ID:SECRET). For Google Vertex AI: a service-account JSON key or an access token. |
| Endpoint address | `base_url` | Text | No |  | Main; choice Model to use = Set up here | Endpoint; required for Azure OpenAI, Vertex AI (https://REGION-aiplatform.googleapis.com/v1/projects/PROJECT/locations/REGION) and self-hosted models (for a TGI server, e.g. http://tgi-host:8080/v1). |
| AWS access key ID | `aws_access_key_id` | Credential (vault) | No |  | Main; choice Model to use = Set up here | AWS Bedrock and AWS SageMaker only: AWS access key id from the vault (api_key then holds the secret access key). |
| AWS region | `aws_region` | Text | No |  | Main; choice Model to use = Set up here | AWS Bedrock and AWS SageMaker only: AWS region, e.g. eu-west-1. |
| Hide personal data | `mask_pii` | Yes/No | No | Yes | More options | Replace emails, phone and card numbers, IBANs and similar before sending; restored in the answer. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `text` | Text | Translation. |

*Time limit 300 s; needs: network, ai.*
