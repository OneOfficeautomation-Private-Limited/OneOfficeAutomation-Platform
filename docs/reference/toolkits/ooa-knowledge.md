# Knowledge Q&A

Toolkit `ooa.knowledge`, version 1.0.0. Index your documents and answer questions from them, with the passages each answer comes from.

[All toolkits](README.md)

## Contents

- **Knowledge**: Ask, Build index, Search

## Knowledge

### Ask

`ooa.knowledge/ask` (action)

Answer a question from a knowledge index: the best passages go to the model, which answers from them only and cites them.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Index file | `index_file` | File path | Yes |  | Main | The index file, e.g. knowledge/policies.json (.json.gz keeps it smaller). |
| Question | `question` | Text | Yes |  | Main | The question. |
| Top k | `top_k` | Number | No | `5` | Main | How many passages the model gets. Range: 1 to 20 |
| Connection | `connection` | Text | No |  | Main; choice Model to answer with = A saved connection | Name of a One Model Connection (provider, model and key managed in one place). Leave empty to set them on this action. |
| Provider | `provider` | Text | No | `openai` | Main; choice Model to answer with = Set up here | openai, azure_openai, anthropic, openai_compatible (LiteLLM, vLLM, Ollama), huggingface (Hugging Face router or your own TGI server), aws_bedrock_sagemaker (a model on an Amazon SageMaker endpoint), aws_bedrock (Amazon Bedrock) or google_vertex (Gemini on Google Vertex AI). One of: `openai`, `azure_openai`, `anthropic`, `openai_compatible`, `huggingface`, `aws_bedrock_sagemaker`, `aws_bedrock`, `google_vertex` |
| Model | `model` | Text | No |  | Main; choice Model to answer with = Set up here | Model name; the deployment name for Azure OpenAI; the endpoint name for aws_bedrock_sagemaker; the model or inference profile id for aws_bedrock. |
| API key | `api_key` | Credential (vault) | No |  | Main; choice Model to answer with = Set up here | API key or access token from the vault (not needed for some self-hosted endpoints). For aws_bedrock and aws_bedrock_sagemaker: the AWS secret access key (or ACCESS_KEY_ID:SECRET). For google_vertex: a service-account JSON key or an access token. |
| Endpoint address | `base_url` | Text | No |  | Main; choice Model to answer with = Set up here | Endpoint; required for Azure OpenAI, Vertex AI (https://REGION-aiplatform.googleapis.com/v1/projects/PROJECT/locations/REGION) and self-hosted models (for a TGI server, e.g. http://tgi-host:8080/v1). |
| Embedding connection | `embedding_connection` | Text | No |  | More options | The embedding connection, when not the one the index was built with. |
| Maximum tokens | `max_tokens` | Number | No | `1024` | More options | Maximum answer length in tokens. |
| Hide personal data | `mask_pii` | Yes/No | No | Yes | More options | Replace emails, phone and card numbers, IBANs and similar before sending; restored in the answer. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `0` | More options | Seconds to wait for the model; 180 when 0. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `answer` | Text | The answer, citing passages like [1]. |
| `sources` | List | The passages given to the model: n, source, passage, score, text. |
| `found` | Yes/No | False when no passage matched, so the model was not asked. |
| `input_tokens` | Number | Tokens in the request. |
| `output_tokens` | Number | Tokens in the answer. |

*Time limit 600 s; needs: filesystem, network, ai.*

### Build index

`ooa.knowledge/build_index` (action)

Read documents (files or folders) and texts, split them into passages and write a knowledge index, with embeddings when a model connection is given.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Files | `files` | List | No |  | Main | Files and folders to read (folders with their sub-folders). |
| Texts | `texts` | List | No |  | Main | Texts to add, each a dictionary with source and text (for example from the PDF or OCR toolkit). |
| Index file | `index_file` | File path | Yes |  | Main | The index file, e.g. knowledge/policies.json (.json.gz keeps it smaller). |
| Connection | `connection` | Text | No |  | Main; choice Search by meaning too = A saved connection | A saved model connection for embeddings, e.g. one for text-embedding-3-small; without one the index is searched by words only. |
| Provider | `provider` | Text | No |  | Main; choice Search by meaning too = Set up here | Embedding provider, when not using a saved connection. One of: `openai`, `azure_openai`, `openai_compatible`, `google_vertex`, `aws_bedrock` |
| Model | `model` | Text | No |  | Main; choice Search by meaning too = Set up here | Embedding model (the deployment for Azure OpenAI). Example: `text-embedding-3-small` |
| API key | `api_key` | Credential (vault) | No |  | Main; choice Search by meaning too = Set up here | API key or access token from the vault (not needed for some self-hosted endpoints). For aws_bedrock and aws_bedrock_sagemaker: the AWS secret access key (or ACCESS_KEY_ID:SECRET). For google_vertex: a service-account JSON key or an access token. |
| Endpoint address | `base_url` | Text | No |  | Main; choice Search by meaning too = Set up here | Endpoint; required for Azure OpenAI, Vertex AI (https://REGION-aiplatform.googleapis.com/v1/projects/PROJECT/locations/REGION) and self-hosted models (for a TGI server, e.g. http://tgi-host:8080/v1). |
| Chunk size | `chunk_size` | Number | No | `1000` | More options | Passage length in characters. Range: 200 to 8000 |
| Overlap | `overlap` | Number | No | `150` | More options | Characters each passage shares with the one before, so no sentence is cut off from its context. Range: 0 to 2000 |
| Hide personal data | `mask_pii` | Yes/No | No | No | More options | Mask emails, phone and card numbers before they are sent for embedding (search by those then works by words only). |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `passages` | Number | How many passages the index holds. |
| `documents` | Number | How many documents were read. |
| `skipped` | List | Files that could not be read, with the reason. |

*Time limit 3600 s; needs: filesystem, network, ai.*

### Search

`ooa.knowledge/search` (action)

Find the passages of a knowledge index that best answer a question, by words and, when the index has embeddings, by meaning.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Index file | `index_file` | File path | Yes |  | Main | The index file, e.g. knowledge/policies.json (.json.gz keeps it smaller). |
| Query | `query` | Text | Yes |  | Main | The question or words to look for. |
| Top k | `top_k` | Number | No | `5` | Main | How many passages to return. Range: 1 to 50 |
| Connection | `connection` | Text | No |  | More options | The embedding connection, when not the one the index was built with. |
| API key | `api_key` | Credential (vault) | No |  | More options | The embedding API key, for an index built without a saved connection. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `results` | List | Best passages first: n, source, passage, score (0 to 1), text. |

*Time limit 120 s; safe to repeat; needs: filesystem, network, ai.*
