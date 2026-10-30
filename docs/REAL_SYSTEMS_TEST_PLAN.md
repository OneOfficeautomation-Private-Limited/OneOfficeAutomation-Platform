# Testing against real systems

Open items S2 (single sign-on and external vaults) and Q4 (the systems the toolkits automate) need real accounts,
which the automated tests cannot have: they use simulators and in-memory servers. This is the checklist for running
those tests by hand. Record each result (date, version, pass or fail, notes) in the table at the end, and file a fix
for every failure.

Use test tenants and test accounts only. Keep their passwords in the Command Center vault, never in agents or in
this repository.

## Before you start

- A test Command Center on SQL Server with HTTPS (see [Security architecture](architecture/06-security-architecture.md#14-independent-security-review), section 2), and one
  desktop runner (Install.cmd -Role Runner), signed in and unlocked.
- `public_url` set in Administration > Settings to the address people use.
- For each system below: a test account, and its secrets stored in a vault folder that the test agents can read.

## S2: single sign-on

| Provider | Set up | Check |
| --- | --- | --- |
| Microsoft Entra ID | App registration, web redirect `<public_url>/api/auth/sso/callback`, client secret in the vault; `sso_issuer` = `https://login.microsoftonline.com/<tenant>/v2.0` | Sign in; a new user gets `sso_default_role`; a disabled OOA user is refused; a user from another tenant is refused; sign-out |
| Okta | OIDC web app, same redirect | As above, plus an Okta user removed from the app is refused |
| Google Workspace, Keycloak | OIDC client | Sign in and default role |

Also check: the console's sign-in button label (`sso_label`), sessions end after `session_hours`, and the audit trail
records each SSO sign-in.

## S2: external vaults

| Vault | Set up | Check |
| --- | --- | --- |
| HashiCorp Vault | KV v2 path, AppRole or token with read access | Link a vault folder; an agent reads a secret; a missing secret fails with a clear error; changing the secret in Vault is seen by the next run |
| Azure Key Vault | Service principal with Get on secrets | As above |
| AWS Secrets Manager | IAM user or role with `secretsmanager:GetSecretValue` | As above |
| CyberArk CCP | Application id with access to the safe | As above |

For each one also check that the secret never appears in run logs, outputs, the audit trail or error messages.

## S7, S8: vault key stores and Windows sign-in to SQL Server

| What | Set up | Check |
| --- | --- | --- |
| Vault key in Azure Key Vault | A secret with `openssl rand -base64 32`; the VM's managed identity with Get on secrets | `OOA_MASTER_KEY_SOURCE=azure-keyvault:https://...` starts the Command Center; Settings shows the source; a wrong secret name fails at start with a clear error |
| Vault key in AWS Secrets Manager | A secret; an EC2 instance role with `secretsmanager:GetSecretValue` | As above with `aws-secrets:REGION/ID` |
| Vault key in Google Secret Manager | A secret; the VM's service account with Secret Accessor | As above with `gcp-secret:projects/...` |
| Windows sign-in to SQL Server | A gMSA installed on the server; the service running as it | `ooa db configure --windows-auth --windows-login DOMAIN\gmsa$`, `ooa db init`, the service starts, runs work; no password in `config` |

## Q4: toolkits against the real product

| System | Samples or agents to run | Notes |
| --- | --- | --- |
| Tesseract OCR | The OCR toolkit's commands on real scanned invoices and forms | Install Tesseract on the runner |
| Excel desktop | `09-excel.json` (desktop part) and the Excel macro and refresh commands | Office installed and activated on the runner |
| SAP GUI | A login, a transaction (for example VA03) and a table read | SAP GUI scripting enabled on the server and client |
| Mainframes | The terminal toolkit (TN5250, and VT over SSH) against a test host: sign in, read a screen, enter a field, sign off | IBM i for TN5250; any Linux host over SSH for VT |
| Microsoft 365 | Mail, OneDrive or SharePoint files, Teams message, through Microsoft Graph | App registration with the needed Graph permissions |
| Google Workspace | Gmail, Drive and Sheets commands | Service account or OAuth client |
| ServiceNow | Create, update and query an incident; the ServiceNow trigger | A developer instance is enough |
| Workday | Read a worker and a report | Integration system user |
| IMAP mail | `12-email.json` and the mail trigger against a real mailbox | |
| Outlook desktop | Outlook commands and the Outlook trigger | Outlook profile on the runner |
| Active Directory | `16-active-directory.json` against a test domain | A test OU only |

Run each one attended first, then unattended on a runner (screen left unlocked), and note anything that only fails
unattended.

## Results

| Date | Version | Item | Result | Notes |
| --- | --- | --- | --- | --- |
| | | | | |
