# Credential vault

Toolkit `ooa.vault`, version 1.0.0. Save a credential's new value in One CommandCenter's vault, for example after a password reset, and check when a credential expires.

[All toolkits](README.md)

## Contents

- **Credential vault**: Check credential expiry, Save credential value
- **Conditions**: Credential has expired

## Credential vault

### Check credential expiry

`ooa.vault/credential_expiry` (action)

Read when a credential expires, how many days are left and whether it has expired. The user the run runs as must be able to use the vault folder.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Credential | `credential` | Text | Yes |  | Main | vault folder/credential, or folder/credential/attribute (the expiry belongs to the whole credential). |
| Warn (days) | `warn_days` | Number | No | `14` | Main | Report expiring_soon when this many days or fewer are left. Range: 0 to 3650 |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `expires` | Text | The expiry date (yyyy-MM-dd), or empty when it has none. |
| `days_left` | Number | Days until it expires (negative once past); empty when it has no expiry date. |
| `expired` | Yes/No | True when the expiry date has passed. |
| `expiring_soon` | Yes/No | True when it expires within warn_days (and has not expired yet). |
| `has_expiry` | Yes/No | True when the credential has an expiry date. |

*Time limit 60 s; safe to repeat.*

### Save credential value

`ooa.vault/save_credential` (action)

Store a new value of a credential attribute in One CommandCenter's vault, for example the new password after a reset, and optionally its new expiry date. Needs Settings > Agents may save credentials and permission to change the credential for the user the run runs as.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Credential | `credential` | Text | Yes |  | Main | Where to store it: vault folder/credential/attribute, e.g. finance/sap-login/password. |
| Value | `value` | Credential (vault) | Yes |  | Main | The new value: a Credential variable or a vault reference, never typed text. |
| Expires | `expires` | Text | No |  | More options; tick Set the expiry date | The credential's new expiry date, yyyy-MM-dd. Empty: unchanged. |
| Expires in (days) | `expires_in_days` | Number | No |  | More options; tick Set the expiry date | Or: the credential expires this many days from today (used when expires is empty). Range: 1 to 3650 |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `expires` | Text | The credential's expiry date (yyyy-MM-dd), or empty when it has none. |
| `days_left` | Number | Days until it expires; empty when it has no expiry date. |

*Time limit 60 s.*

## Conditions

Used in If, Loop while and Wait until steps.

### Credential has expired

`ooa.vault/is_expired` (condition)

True when the credential's expiry date has passed.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Credential | `credential` | Text | Yes |  | Main | vault folder/credential, or folder/credential/attribute. |
