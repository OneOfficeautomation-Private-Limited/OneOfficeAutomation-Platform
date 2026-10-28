# Web goal agent

Toolkit `ooa.web_agent`, version 1.0.0. Reach a goal on a web page described in plain words: an AI model reads the page and clicks, types and selects until done.

[All toolkits](README.md)

## Contents

- **Web goal agent**: End web agent session, Reach a goal, Start web agent session

## Web goal agent

### End web agent session

`ooa.web_agent/end_session` (action)

Close the browser the web agent session opened.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `browser` | Main | Session name. |

*Time limit 60 s.*

### Reach a goal

`ooa.web_agent/run` (action)

Describe the goal in plain words; the AI model works the page in a browser session until the goal is reached, cannot be reached, or the step limit. Can start from an address and return its findings in a given shape (JSON Schema), also as a file.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Start address (URL) | `start_url` | Text | No |  | Main | Open this address before the first step; the page already open when empty. Example: `https://portal.example.com/invoices` |
| Goal | `goal` | Text | Yes |  | Main | What to achieve, in plain words; ${...} interpolation allowed. Example: `Search for invoice 4711 and tell me its total and due date.` |
| Secrets | `secrets` | Dictionary | No |  | Main | Name to Credential, e.g. {"password": a Credential}. The model types them as {{secret:password}} and never sees the values. |
| Connection | `connection` | Text | No |  | Main; choice Model = A saved connection | Name of a One Model Connection (provider, model and key managed in one place). Leave empty to set them on this action. |
| Provider | `provider` | Text | No | `openai` | Main; choice Model = Set up here | openai, azure_openai, anthropic, openai_compatible (LiteLLM, vLLM, Ollama), huggingface (Hugging Face router or your own TGI server), aws_bedrock_sagemaker (a model on an Amazon SageMaker endpoint), aws_bedrock (Amazon Bedrock) or google_vertex (Gemini on Google Vertex AI). One of: `openai`, `azure_openai`, `anthropic`, `openai_compatible`, `huggingface`, `aws_bedrock_sagemaker`, `aws_bedrock`, `google_vertex` |
| Model | `model` | Text | No |  | Main; choice Model = Set up here | Model name; the deployment name for Azure OpenAI; the endpoint name for aws_bedrock_sagemaker; the model or inference profile id for aws_bedrock. |
| API key | `api_key` | Credential (vault) | No |  | Main; choice Model = Set up here | API key or access token from the vault (not needed for some self-hosted endpoints). For aws_bedrock and aws_bedrock_sagemaker: the AWS secret access key (or ACCESS_KEY_ID:SECRET). For google_vertex: a service-account JSON key or an access token. |
| Endpoint address | `base_url` | Text | No |  | Main; choice Model = Set up here | Endpoint; required for Azure OpenAI, Vertex AI (https://REGION-aiplatform.googleapis.com/v1/projects/PROJECT/locations/REGION) and self-hosted models (for a TGI server, e.g. http://tgi-host:8080/v1). |
| AWS access key ID | `aws_access_key_id` | Credential (vault) | No |  | Main; choice Model = Set up here | aws_bedrock and aws_bedrock_sagemaker only: AWS access key id from the vault (api_key then holds the secret access key). |
| AWS region | `aws_region` | Text | No |  | Main; choice Model = Set up here | aws_bedrock and aws_bedrock_sagemaker only: AWS region, e.g. eu-west-1. |
| Output schema | `output_schema` | Dictionary | No |  | Main; tick Return the findings in a fixed shape | JSON Schema the data output must match; the model is shown it and asked to correct an answer that does not match. Example: `{"type": "object", "required": ["total"], "properties": {"total": {"type": "number"}, "due": {"type": "string"}}}` |
| Output path | `output_path` | File path | No |  | Main; tick Return the findings in a fixed shape | Also write the data output to this JSON file when the goal is reached. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `0` | Main | Seconds to wait for the model on each step; 180 when 0. |
| Session | `session` | Text | No | `browser` | More options | Browser session (from Start web agent session or Open browser). |
| Maximum steps | `max_steps` | Number | No | `15` | More options | Most actions to take, 1 to 60. |
| Allowed sites | `allowed_sites` | List | No |  | More options | Host names the agent may visit (sub-domains included), e.g. ["portal.example.com"]; any site when empty. |
| Instructions | `instructions` | Text | No |  | More options | Extra rules, e.g. "Never press Delete or Pay." |
| Hide personal data | `mask_pii` | Yes/No | No | Yes | More options | Replace emails, phone and card numbers, IBANs and similar before sending; restored in the answer. |
| Save trajectory to | `save_trajectory_to` | File path | No |  | More options | Keep a record of the run in this JSON file: each step's action, the model's reasoning and a screenshot (saved in a folder next to it). Secrets appear only as placeholders. Example: `logs/web-agent-run.json` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `success` | Yes/No | Whether the model reports the goal reached. |
| `answer` | Text | What was done or found. |
| `data` | Dictionary | Values the goal asked for, if any. |
| `steps` | List | Each step taken, in words. |
| `url` | Text | Address of the page at the end. |
| `input_tokens` | Number | Tokens sent to the model in all steps. |
| `output_tokens` | Number | Tokens the model answered with in all steps. |

*Time limit 1800 s; needs: browser, network, ai.*

### Start web agent session

`ooa.web_agent/start_session` (action)

Open a browser for the web goal agent (the same session works with every Browser action).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Address (URL) | `url` | Text | Yes |  | Main | Page to start on. |
| Browser | `browser` | Text | No | `edge` | Main | Browser to use. One of: `edge`, `chrome`, `chromium`, `firefox` |
| Headless | `headless` | Yes/No | No | No | Main | Run without a visible window (for unattended agents). |
| Proxy server | `proxy_server` | Text | No |  | More options; tick Go through a proxy server | Send the browser's traffic through this proxy: host:port, or with http://, https://, socks4:// or socks5:// in front. Example: `http://proxy.example.com:3128` |
| Proxy username | `proxy_username` | Text | No |  | More options; tick Go through a proxy server | User name for a proxy that asks for one. |
| Proxy password | `proxy_password` | Credential (vault) | No |  | More options; tick Go through a proxy server | Password for the proxy, from the vault; never logged. |
| Browser path | `browser_path` | File path | No |  | More options | Start this browser program instead of the installed one (a Chromium-based browser, or Firefox when browser is firefox). Example: `C:\Program Files\Google\Chrome Beta\Application\chrome.exe` |
| Session | `session` | Text | No | `browser` | More options | Session name. |

*Time limit 300 s; needs: browser.*
