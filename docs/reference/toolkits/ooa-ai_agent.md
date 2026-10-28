# AI Agents

Toolkit `ooa.ai_agent`, version 1.0.0. Run an AI agent that works toward a goal using your agents as tools, with approvals and limits.

[All toolkits](README.md)

## Contents

- **AI Agents**: Run AI agent

## AI Agents

### Run AI agent

`ooa.ai_agent/run` (action)

Give an agent a goal and a set of agents it may use; it plans, calls the agents and returns its final answer and a trace.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Goal | `goal` | Text | Yes |  | Main | What the agent should achieve, e.g. the customer email to handle. |
| Instructions | `instructions` | Text | No | `You are a careful business assistant. Use the tools to act; never invent results.` | Main | Role, policy and rules for the agent. |
| Tools | `tools` | List | Yes |  | Main | Ids of the agents it may call, e.g. ["lookup_order", "check_refund_policy"]. |
| Connection | `connection` | Text | No |  | Main; choice Model to use = A saved connection | Name of a One Model Connection (provider, model and key managed in one place). Leave empty to set them on this action. |
| Provider | `provider` | Text | No | `openai` | Main; choice Model to use = Set up here | openai, azure_openai, anthropic, openai_compatible (LiteLLM, vLLM, Ollama), huggingface (Hugging Face router or your own TGI server), aws_bedrock_sagemaker (a model on an Amazon SageMaker endpoint), aws_bedrock (Amazon Bedrock) or google_vertex (Gemini on Google Vertex AI). One of: `openai`, `azure_openai`, `anthropic`, `openai_compatible`, `huggingface`, `aws_bedrock_sagemaker`, `aws_bedrock`, `google_vertex` |
| Model | `model` | Text | No |  | Main; choice Model to use = Set up here | Model name; the deployment name for Azure OpenAI; the endpoint name for aws_bedrock_sagemaker; the model or inference profile id for aws_bedrock. |
| API key | `api_key` | Credential (vault) | No |  | Main; choice Model to use = Set up here | API key or access token from the vault (not needed for some self-hosted endpoints). For aws_bedrock and aws_bedrock_sagemaker: the AWS secret access key (or ACCESS_KEY_ID:SECRET). For google_vertex: a service-account JSON key or an access token. |
| Endpoint address | `base_url` | Text | No |  | Main; choice Model to use = Set up here | Endpoint; required for Azure OpenAI, Vertex AI (https://REGION-aiplatform.googleapis.com/v1/projects/PROJECT/locations/REGION) and self-hosted models (for a TGI server, e.g. http://tgi-host:8080/v1). |
| AWS access key ID | `aws_access_key_id` | Credential (vault) | No |  | Main; choice Model to use = Set up here | aws_bedrock and aws_bedrock_sagemaker only: AWS access key id from the vault (api_key then holds the secret access key). |
| AWS region | `aws_region` | Text | No |  | Main; choice Model to use = Set up here | aws_bedrock and aws_bedrock_sagemaker only: AWS region, e.g. eu-west-1. |
| Sensitive tools | `sensitive_tools` | List | No |  | More options | Agent ids that need a person's approval before each call (e.g. issue_refund). |
| Role | `role` | Text | No |  | More options | Who the agent acts as; added in front of the instructions. Example: `Accounts payable clerk` |
| Action plan | `action_plan` | Text | No |  | More options | The order in which to use the tools and when to stop or ask a person, in plain words. |
| Output fields | `output_fields` | Dictionary | No |  | More options | Values the agent must return, name to description; they come back in outputs. Plain text answer when empty. Example: `{"refund_amount": "amount refunded as a number"}` |
| Allow cancel | `allow_cancel` | Yes/No | No | No | More options | Let the agent end with status cancelled when the goal should not be pursued (with output fields only). |
| Maximum steps | `max_steps` | Number | No | `8` | More options | Maximum model turns. |
| Maximum tokens total | `max_tokens_total` | Number | No | `60000` | More options | Stop when this many tokens have been used. |
| Maximum duration (seconds) | `max_duration_seconds` | Number | No | `0` | More options | Stop with limit_reached after this many seconds; no time limit when 0. |
| Temperature | `temperature` | Number | No | `0` | More options | 0 = consistent, higher = more creative. |
| Hide personal data | `mask_pii` | Yes/No | No | Yes | More options | Replace emails, phone and card numbers, IBANs and similar before sending; restored in the answer. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `answer` | Text | The agent's final answer. |
| `status` | Text | completed, limit_reached or stopped; with output fields also failed or cancelled, as the agent decided. |
| `outputs` | Dictionary | The output fields asked for (empty when none were). |
| `trace` | List | Each model turn and tool call, for review and audit. |
| `tokens` | Number | Total tokens used. |

*Time limit 1800 s; needs: network, ai.*
