# Windows Credential Manager

Toolkit `ooa.credential_manager`, version 1.0.0. Read, list, save and remove generic credentials in the Windows Credential Manager of the agent's user account.

[All toolkits](README.md)

## Contents

- **Credential Manager**: Remove credential, List credential targets, Read credential, Save credential
- **Conditions**: Credential exists

## Credential Manager

### Remove credential

`ooa.credential_manager/delete_credential` (action)

Remove a generic credential from Credential Manager.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Target | `target` | Text | Yes |  | Main | Target name to remove. |
| Missing ok | `missing_ok` | Yes/No | No | No | More options | Succeed quietly when there is nothing to remove. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `deleted` | Yes/No | True when a credential was removed; false when none existed and missing_ok is on. |

*Time limit 60 s; needs: desktop.*

### List credential targets

`ooa.credential_manager/list_targets` (action)

List the target names of generic credentials, optionally filtered with a * wildcard pattern. Secrets are not returned.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Filter | `filter` | Text | No |  | Main | Pattern ending in *, e.g. MyApp/*; empty lists all. Example: `MyApp/*` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `targets` | List | Target names, sorted. |
| `credentials` | Table | Columns: target, username, comment, persist, last_written. |

*Time limit 60 s; safe to repeat; needs: desktop.*

### Read credential

`ooa.credential_manager/read_credential` (action)

Read a generic credential by its target name; the secret comes back as a Credential that is masked everywhere.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Target | `target` | Text | Yes |  | Main | Target name as shown in Credential Manager. Example: `MyApp/service-account` |
| Encoding | `encoding` | Text | No | `auto` | More options | How the secret is stored. auto: detects the UTF-16 used by Windows and most tools, otherwise UTF-8. One of: `auto`, `utf16`, `utf8` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `username` | Text | User name stored with the credential (may be empty). |
| `password` | Credential (vault) | The stored secret. |
| `comment` | Text | Comment stored with the credential. |
| `last_written` | DateTime | When the credential was last changed. |

*Time limit 60 s; safe to repeat; needs: desktop.*

### Save credential

`ooa.credential_manager/write_credential` (action)

Create or replace a generic credential for the agent's user account.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Target | `target` | Text | Yes |  | Main | Target name to store under. Example: `MyApp/service-account` |
| Username | `username` | Text | No |  | Main | User name to store with it. |
| Password | `password` | Credential (vault) | Yes |  | Main | Secret to store, from the vault or another Credential variable. |
| Persist | `persist` | Text | No | `local_machine` | Main | session: until logoff. local_machine: this computer. enterprise: roams with the user profile. One of: `session`, `local_machine`, `enterprise` |
| Comment | `comment` | Text | No |  | More options | Optional note shown in Credential Manager. |
| Encoding | `encoding` | Text | No | `utf16` | More options | How to store the secret; utf16 is what Windows tools expect. One of: `utf16`, `utf8` |

*Time limit 60 s; needs: desktop.*

## Conditions

Used in If, Loop while and Wait until steps.

### Credential exists

`ooa.credential_manager/credential_exists` (condition)

True when a generic credential with this target name is stored for the agent's account.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Target | `target` | Text | Yes |  | Main | Target name. |
