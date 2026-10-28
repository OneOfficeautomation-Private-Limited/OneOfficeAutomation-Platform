# 07 Vault, credentials and connections

## What this covers

One Agent Vault keeps the secrets agents use. This document specifies vault folders and the four member places
(owner, manager, participant, consumer), credentials and their attributes, people's own values and credential
requests, how a running agent receives a secret, external vaults, the vault key and its rotation, and the two related
stores that hold no secrets themselves: saved connections and shared values (global values).

## Contents

1. Principles
2. Vault folders
3. Member places on a vault folder
4. Credentials and attributes
5. Attribute settings
6. User-provided attributes and credential requests
7. How a run receives a secret
8. Other uses of vault references
9. External vaults
10. The vault key
11. Saved connections
12. Shared values
13. Permissions and audit

## 1. Principles

- Values are encrypted with AES-256-GCM under the Command Center's vault key. Each value is bound to its own reference,
  so an encrypted value cannot be moved to another credential or person.
- A secret is never shown again after it is stored, never written into settings, steps, schedules or inputs (only vault
  references `folder/credential/attribute` are), and is masked as `****` in every log, event, variable view and error.
- A secret is handed to an agent only while its run is running, only on the runner the run is on, and only when the
  run's user may use the folder. Every read is recorded in the One Audit Trail, including refusals.

## 2. Vault folders

| Rule | Detail |
| --- | --- |
| Name | Letters, digits, `.`, `_`, `-`, up to 80 |
| Create | People with `lockers.own` (agent builders) or `lockers.manage` (administrators); the creator is the first owner |
| Owners | At least one owner (a person) must remain |
| Delete | Only an empty folder (`CR.CONFLICT`: delete its credentials first), by an owner or a vault administrator |
| Visibility | A person sees only the folders where they have a member place; `lockers.manage` sees and owns all |

## 3. Member places on a vault folder

| Place | Who can be one | May |
| --- | --- | --- |
| Owner | People | Everything, including the members, linking an external vault and deleting the folder |
| Manager | Users, roles, teams | Add, change and delete every credential; hand credentials to another owner |
| Participant | Users, roles, teams | See the folder; add credentials; change and delete the credentials they own |
| Consumer | Users, roles, teams | Their runs may use the folder's credentials |

- Members are written `user:NAME`, `role:ROLE` or `team:NAME`; principals that do not exist are refused.
- A person with several places gets the highest. Only owners change members.
- Only the consumer place lets a run read a credential. Owners, managers and participants whose runs should use the
  folder's credentials must also be listed as consumers (directly, by role or by team).

## 4. Credentials and attributes

- A credential has a name and one or more attributes (for example `username`, `password`), each with its own value.
  Names use letters, digits, `.`, `_`, `-`.
- The person who creates a credential is its **owner**; owners and managers of the folder can hand it to another person.
  A credential also has a description.
- **Details** lists a credential's attributes, owner, description and user-provided attributes, never their values.
- An empty value is refused. Setting a value of an existing attribute replaces it.
- **Delete** removes the whole credential: every attribute, everyone's own values and its details.

## 5. Attribute settings

| Setting | Default | Effect |
| --- | --- | --- |
| Description | none | Shown to people filling in their own value (up to 300 characters) |
| Masked | on | The value is never shown. When cleared (for values that are not secret, such as a user name), owners and managers can **Show** it; each look is audited. Values of external vaults are never shown. |
| Password fields only | off | Agents may type the value only into password fields. The browser's Set text action and the web goal agent check the field before typing; other uses are refused. |

## 6. User-provided attributes and credential requests

- An attribute can be **user-provided**: each person stores their own value (for example their own password) under
  **Your values**. A shared value cannot exist for such an attribute (`CR.CONFLICT`: delete the shared value first),
  and a shared value cannot be set once it is user-provided.
- A run reads the value of the person it runs as (the run-as user, or the requester). When that person has not stored
  one, the read fails with `VAULT.NOT_FOUND` ("NAME has not stored their own value …").
- A person may store their own value only in folders they may use.
- **Credential requests** (top of the vault page, with a count): the user-provided attributes of folders the person may
  use that they have not filled in yet. They fill them in there.

## 7. How a run receives a secret

1. The step needs a Credential input; the runner asks the Command Center for `folder/credential/attribute` with the run id.
2. The Command Center checks: the run is running, on this runner; the reference has three parts; the folder exists; the
   run's user (run-as user, else requester) is a consumer directly, through a role or through a team.
3. It then reads the value: live from the external vault when the folder is linked; the person's own value for a
   user-provided attribute; otherwise the shared value.
4. The read is audited as `credential.read` with the result `ok`, `denied`, `not_found` or `failed` and the actor
   "agent:RUNNER for USER".
5. The value travels only to the worker process of that run, lives only in memory, and is added to the run's list of
   secrets so it is masked everywhere.

Errors: `VAULT.DENIED` ("Credentials are only available to running agents", or "USER may not use vault folder …"),
`VAULT.NOT_FOUND`.

## 8. Other uses of vault references

| Where | Reads as |
| --- | --- |
| Command Center triggers (IMAP password, Graph token or client secret, ServiceNow, poller authorisation) | The trigger's owner, who must be a consumer |
| Model connection keys | The run's user at run time; the person testing the connection for **Test connection** |
| Saved connections | The run's user at run time |
| Command Center settings (SSO client secret, Git token, mail password, promotion API key) | The Command Center itself |

## 9. External vaults

A vault folder can be linked to an external vault; only the values live outside, while members, consumers and the
audit trail work as before.

| Provider (stored id) | Needs |
| --- | --- |
| HashiCorp Vault KV v2 (`hashicorp`) | Address and a token |
| Azure Key Vault (`azure_keyvault`) | Vault address, tenant id, client id and client secret |
| AWS Secrets Manager (`aws_secrets_manager`) | Region, access key id and secret access key |
| CyberArk Central Credential Provider (`cyberark_ccp`) | Address, application id and safe |

- A credential name is the secret's name; the attribute is a field of it (`value` for single-value secrets). Secrets
  that hold JSON objects are read field by field.
- The secret the Command Center uses to sign in to the external vault is stored encrypted and never shown; leaving it
  empty when changing the link keeps the old one.
- **Test reading** (owners) reads one credential and reports success and the value's length, never the value.
- Linking, unlinking and tests are audited (`locker.link_external`, `locker.unlink_external`, `locker.test_external`).
- The Command Center's outgoing proxy applies to calls to external vaults.

## 10. The vault key

**Vault key** (vault administrators, `lockers.manage`) shows where the master key comes from (the key file, the
environment variable `OOA_MASTER_KEY`, or a key store through `OOA_MASTER_KEY_SOURCE`), its fingerprint and how much
the vault holds.

**Rotate key** makes a new key and re-encrypts, in one transaction, every shared value, every person's own value and
every external-vault sign-in secret, and the encrypted entries of the Command Center's configuration files. The new key
is written next to the old one first, then replaces it; the old key file is kept as `master.key.old-…` until an
administrator removes it. With `OOA_MASTER_KEY` or a key store, the key cannot be rotated here (`CR.VAULT_KEY`).
Instead, stop the Command Center and run `ooa vault rekey --new-key-env VAR`, with the new key (32 random bytes,
Base64) in the environment variable `VAR`: every stored value is re-encrypted with it (`vault.rekey` is recorded, the
key itself is never printed). Then put the new key where the old one is kept and start the Command Center again; until
then it cannot read its secrets. With a key file, the command writes the new key into it and keeps the old one as
`master.key.old-…`.

## 11. Saved connections

A saved connection (Manage > Connections, and in Studio) keeps a connector's address and settings with vault
references for its secrets, under a name. The rules are in [document 02, section 5](02-toolkits-and-actions.md). They
hold references only, so seeing them never reveals a secret.

## 12. Shared values

Shared values (Manage > Shared values; called global values in the API and `$global.NAME` in agents) are named values
every run receives.

| Rule | Detail |
| --- | --- |
| Name | Starts with a letter or `_`, then letters, digits and `_`, up to 80 |
| Value | Any JSON value |
| Delivery | Every run receives all shared values when it starts |
| **People may set their own value** | When ticked (Edit), each person can keep **My value** (up to 100,000 characters). Their runs, and runs as them, use it instead of the shared one. Turning the option off removes everyone's own values. |
| My value refused | When the value is not changeable (`CR.FORBIDDEN`, "Everyone uses the same value of NAME") |

Shared values are not encrypted secrets; keep secrets in the vault.

## 13. Permissions and audit

| Action | Permission or place |
| --- | --- |
| Create vault folders | `lockers.own` or `lockers.manage` |
| Administer every folder, see the vault key, rotate it | `lockers.manage` |
| Change members, link external vaults, delete a folder | Owner |
| Change credentials | Owner, manager, or participant for their own |
| Use credentials in runs | Consumer |
| See shared values and saved connections; set own value | `globals.view` |
| Change shared values and saved connections | `globals.manage` |

Audited: `locker.create`, `locker.update`, `locker.delete`, `credential.set`, `credential.delete`, `credential.read`,
credential detail and member changes, people's own values, `global.set`, `global.delete`, `global.my_value`,
`global.my_value_delete`, `connection.save`, `connection.delete`, key status and rotation.

## Related documents

- [02 Toolkits and actions](02-toolkits-and-actions.md)
- [04 Schedules and triggers](04-schedules-and-triggers.md)
- [10 Users, roles, teams and security](10-users-roles-teams-and-security-features.md)
- [Command Center and runner, section 8](../user-guide/00-README.md)
