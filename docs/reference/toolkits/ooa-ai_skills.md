# Prompt Skills

Toolkit `ooa.ai_skills`, version 1.0.0. Run saved, reusable prompt templates (Prompt Skills) with your values.

[All toolkits](README.md)

## Contents

- **Prompt Skills**: List Prompt Skills, Run Prompt Skill

## Prompt Skills

### List Prompt Skills

`ooa.ai_skills/list` (action)

List the Prompt Skills this run can use (from Command Center and the workspace) with their descriptions.

No settings.

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `skills` | Table | Columns: name, description, model, output. |

*Time limit 60 s; safe to repeat.*

### Run Prompt Skill

`ooa.ai_skills/run` (action)

Fill a saved prompt template with your values and run it; returns text, or a Dictionary for JSON skills.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Skill | `skill` | Text | Yes |  | Main | Skill name (file name in ai-skills without .json). Example: `summarise_complaint` |
| Values | `values` | Dictionary | No |  | Main | Values for the skill's ${...} placeholders. |
| Connection | `connection` | Text | No |  | Main | One Model Connection to use instead of the skill's own provider and model. |
| API key | `api_key` | Credential (vault) | No |  | More options | API key or access token from the vault (not needed for some self-hosted endpoints). For aws_bedrock and aws_bedrock_sagemaker: the AWS secret access key (or ACCESS_KEY_ID:SECRET). For google_vertex: a service-account JSON key or an access token. |
| Model | `model` | Text | No |  | More options | Override the skill's model. |
| Endpoint address | `base_url` | Text | No |  | More options | Endpoint; required for Azure OpenAI, Vertex AI (https://REGION-aiplatform.googleapis.com/v1/projects/PROJECT/locations/REGION) and self-hosted models (for a TGI server, e.g. http://tgi-host:8080/v1). |
| AWS access key ID | `aws_access_key_id` | Credential (vault) | No |  | More options | aws_bedrock and aws_bedrock_sagemaker only: AWS access key id from the vault (api_key then holds the secret access key). |
| AWS region | `aws_region` | Text | No |  | More options | aws_bedrock and aws_bedrock_sagemaker only: AWS region, e.g. eu-west-1. Overrides the skill's "region". |
| Temperature | `temperature` | Number | No |  | More options | Override the skill's temperature; the skill's own (or 0) when empty. |
| Maximum tokens | `max_tokens` | Number | No |  | More options | Override the skill's maximum answer length; the skill's own (or 2000) when empty. |
| Fail on error | `fail_on_error` | Yes/No | No | Yes | More options | Stop the agent when the skill fails; when off, success is false and error says why. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `0` | More options | Seconds to wait for the model; 180 when 0. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `text` | Text | The answer as text. |
| `json` | Any | The answer parsed as JSON, for skills with output json. |
| `success` | Yes/No | True when the skill ran; false only when fail_on_error is off and it failed. |
| `error` | Text | What went wrong, when success is false. |
| `input_tokens` | Number | Tokens in the request. |
| `output_tokens` | Number | Tokens in the answer. |

*Time limit 300 s; needs: network, ai.*
