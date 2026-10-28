# Okta

Toolkit `ooa.okta`, version 1.0.0. Create and manage Okta users, change their status, reset passwords and manage group membership.

[All toolkits](README.md)

## Contents

- **User status**: Activate user, Deactivate user, Reset password, Suspend user, Unsuspend user
- **Groups**: Add user to group, List groups, List user's groups, Remove user from group
- **Users**: Create user, Delete user, Get user, List users, Update user profile

## User status

### Activate user

`ooa.okta/activate_user` (action)

Activate a staged or newly created account.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Organization address (URL) | `org_url` | Text | Yes |  | Main | Your Okta address, e.g. https://yourcompany.okta.com. |
| API token | `api_token` | Credential (vault) | No |  | Main; choice Sign in with = An API token | Okta API token from the vault. Leave empty when you use an access token instead. |
| Access token | `access_token` | Credential (vault) | No |  | Main; choice Sign in with = An access token | OAuth access token from the vault, used instead of an API token. |
| User | `user` | Text | Yes |  | Main | The person's Okta user id, or their sign-in name (login), e.g. ann.lee@acme.com. |
| Send email | `send_email` | Yes/No | No | Yes | Main | Email the activation link to the person. Turn off to get the link back instead. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `activation_url` | Text | One-time activation link when no email is sent; empty otherwise. |

*Time limit 60 s; needs: network.*

### Deactivate user

`ooa.okta/deactivate_user` (action)

Turn off an account so the person can no longer sign in. Their app access is removed.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Organization address (URL) | `org_url` | Text | Yes |  | Main | Your Okta address, e.g. https://yourcompany.okta.com. |
| API token | `api_token` | Credential (vault) | No |  | Main; choice Sign in with = An API token | Okta API token from the vault. Leave empty when you use an access token instead. |
| Access token | `access_token` | Credential (vault) | No |  | Main; choice Sign in with = An access token | OAuth access token from the vault, used instead of an API token. |
| User | `user` | Text | Yes |  | Main | The person's Okta user id, or their sign-in name (login), e.g. ann.lee@acme.com. |
| Send email | `send_email` | Yes/No | No | No | More options | Tell the organization's admins by email. |

*Time limit 60 s; needs: network.*

### Reset password

`ooa.okta/reset_password` (action)

Start a password reset. The person gets an email, or you get a one-time reset link to pass on.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Organization address (URL) | `org_url` | Text | Yes |  | Main | Your Okta address, e.g. https://yourcompany.okta.com. |
| API token | `api_token` | Credential (vault) | No |  | Main; choice Sign in with = An API token | Okta API token from the vault. Leave empty when you use an access token instead. |
| Access token | `access_token` | Credential (vault) | No |  | Main; choice Sign in with = An access token | OAuth access token from the vault, used instead of an API token. |
| User | `user` | Text | Yes |  | Main | The person's Okta user id, or their sign-in name (login), e.g. ann.lee@acme.com. |
| Send email | `send_email` | Yes/No | No | Yes | Main | Email the reset link to the person. Turn off to get the link back instead. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `reset_url` | Text | One-time reset link when no email is sent; empty otherwise. |

*Time limit 60 s; needs: network.*

### Suspend user

`ooa.okta/suspend_user` (action)

Block sign-in for an active account for now, keeping its app assignments.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Organization address (URL) | `org_url` | Text | Yes |  | Main | Your Okta address, e.g. https://yourcompany.okta.com. |
| API token | `api_token` | Credential (vault) | No |  | Main; choice Sign in with = An API token | Okta API token from the vault. Leave empty when you use an access token instead. |
| Access token | `access_token` | Credential (vault) | No |  | Main; choice Sign in with = An access token | OAuth access token from the vault, used instead of an API token. |
| User | `user` | Text | Yes |  | Main | The person's Okta user id, or their sign-in name (login), e.g. ann.lee@acme.com. |

*Time limit 60 s; needs: network.*

### Unsuspend user

`ooa.okta/unsuspend_user` (action)

Let a suspended person sign in again.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Organization address (URL) | `org_url` | Text | Yes |  | Main | Your Okta address, e.g. https://yourcompany.okta.com. |
| API token | `api_token` | Credential (vault) | No |  | Main; choice Sign in with = An API token | Okta API token from the vault. Leave empty when you use an access token instead. |
| Access token | `access_token` | Credential (vault) | No |  | Main; choice Sign in with = An access token | OAuth access token from the vault, used instead of an API token. |
| User | `user` | Text | Yes |  | Main | The person's Okta user id, or their sign-in name (login), e.g. ann.lee@acme.com. |

*Time limit 60 s; needs: network.*

## Groups

### Add user to group

`ooa.okta/add_user_to_group` (action)

Make a person a member of a group. Nothing changes if they are already a member.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Organization address (URL) | `org_url` | Text | Yes |  | Main | Your Okta address, e.g. https://yourcompany.okta.com. |
| API token | `api_token` | Credential (vault) | No |  | Main; choice Sign in with = An API token | Okta API token from the vault. Leave empty when you use an access token instead. |
| Access token | `access_token` | Credential (vault) | No |  | Main; choice Sign in with = An access token | OAuth access token from the vault, used instead of an API token. |
| Group | `group` | Text | Yes |  | Main | Group id, or the group's exact name. |
| User | `user` | Text | Yes |  | Main | The person's Okta user id, or their sign-in name (login), e.g. ann.lee@acme.com. |

*Time limit 60 s; safe to repeat; needs: network.*

### List groups

`ooa.okta/list_groups` (action)

Return groups, optionally only those whose name starts with some text or that match a search expression.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Organization address (URL) | `org_url` | Text | Yes |  | Main | Your Okta address, e.g. https://yourcompany.okta.com. |
| API token | `api_token` | Credential (vault) | No |  | Main; choice Sign in with = An API token | Okta API token from the vault. Leave empty when you use an access token instead. |
| Access token | `access_token` | Credential (vault) | No |  | Main; choice Sign in with = An access token | OAuth access token from the vault, used instead of an API token. |
| Name starts with | `name_starts_with` | Text | No |  | Main; choice Groups = Name starts with | Only groups whose name starts with this text. |
| Search | `search` | Text | No |  | Main; choice Groups = Search expression | Okta search expression on group properties, e.g. profile.name eq "Finance" for an exact name, or type eq "APP_GROUP". Used instead of name_starts_with. Example: `profile.name eq "Finance"` |
| Maximum results | `max_results` | Number | No | `1000` | More options | Most groups to return. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: id, name, description, type. |
| `count` | Number | Number of groups returned. |

*Time limit 600 s; safe to repeat; needs: network.*

### List user's groups

`ooa.okta/list_user_groups` (action)

Return every group a person belongs to.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Organization address (URL) | `org_url` | Text | Yes |  | Main | Your Okta address, e.g. https://yourcompany.okta.com. |
| API token | `api_token` | Credential (vault) | No |  | Main; choice Sign in with = An API token | Okta API token from the vault. Leave empty when you use an access token instead. |
| Access token | `access_token` | Credential (vault) | No |  | Main; choice Sign in with = An access token | OAuth access token from the vault, used instead of an API token. |
| User | `user` | Text | Yes |  | Main | The person's Okta user id, or their sign-in name (login), e.g. ann.lee@acme.com. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: id, name, description, type. |
| `names` | List | Group names only. |

*Time limit 60 s; safe to repeat; needs: network.*

### Remove user from group

`ooa.okta/remove_user_from_group` (action)

Take a person out of a group.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Organization address (URL) | `org_url` | Text | Yes |  | Main | Your Okta address, e.g. https://yourcompany.okta.com. |
| API token | `api_token` | Credential (vault) | No |  | Main; choice Sign in with = An API token | Okta API token from the vault. Leave empty when you use an access token instead. |
| Access token | `access_token` | Credential (vault) | No |  | Main; choice Sign in with = An access token | OAuth access token from the vault, used instead of an API token. |
| Group | `group` | Text | Yes |  | Main | Group id, or the group's exact name. |
| User | `user` | Text | Yes |  | Main | The person's Okta user id, or their sign-in name (login), e.g. ann.lee@acme.com. |

*Time limit 60 s; needs: network.*

## Users

### Create user

`ooa.okta/create_user` (action)

Add a new person to Okta, optionally with a starting password and group memberships.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Organization address (URL) | `org_url` | Text | Yes |  | Main | Your Okta address, e.g. https://yourcompany.okta.com. |
| API token | `api_token` | Credential (vault) | No |  | Main; choice Sign in with = An API token | Okta API token from the vault. Leave empty when you use an access token instead. |
| Access token | `access_token` | Credential (vault) | No |  | Main; choice Sign in with = An access token | OAuth access token from the vault, used instead of an API token. |
| Login | `login` | Text | No |  | Main | Sign-in name. Leave empty to use the email address. |
| Password | `password` | Credential (vault) | No |  | Main | Starting password from the vault. Leave empty to let the person set one when they activate. |
| Email | `email` | Text | Yes |  | Main | Primary email address. Example: `ann.lee@acme.com` |
| First name | `first_name` | Text | Yes |  | Main | Given name. |
| Last name | `last_name` | Text | Yes |  | Main | Family name. |
| Activate | `activate` | Yes/No | No | Yes | Main | Activate the account now. When off, the account is created in a staged state. |
| Profile fields | `profile_fields` | Dictionary | No |  | More options | More profile values, e.g. {"department": "Finance", "mobilePhone": "+1 555 0100"}. |
| Group IDs | `group_ids` | List | No |  | More options | Ids of groups the person should join straight away. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `user_id` | Text | Okta id of the new user. |
| `status` | Text | Account status, e.g. ACTIVE, PROVISIONED or STAGED. |

*Time limit 60 s; needs: network.*

### Delete user

`ooa.okta/delete_user` (action)

Remove a person from Okta for good. An account that is still active is deactivated first.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Organization address (URL) | `org_url` | Text | Yes |  | Main | Your Okta address, e.g. https://yourcompany.okta.com. |
| API token | `api_token` | Credential (vault) | No |  | Main; choice Sign in with = An API token | Okta API token from the vault. Leave empty when you use an access token instead. |
| Access token | `access_token` | Credential (vault) | No |  | Main; choice Sign in with = An access token | OAuth access token from the vault, used instead of an API token. |
| User | `user` | Text | Yes |  | Main | The person's Okta user id, or their sign-in name (login), e.g. ann.lee@acme.com. |

*Time limit 60 s; needs: network.*

### Get user

`ooa.okta/get_user` (action)

Look up one person by Okta id or sign-in name.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Organization address (URL) | `org_url` | Text | Yes |  | Main | Your Okta address, e.g. https://yourcompany.okta.com. |
| API token | `api_token` | Credential (vault) | No |  | Main; choice Sign in with = An API token | Okta API token from the vault. Leave empty when you use an access token instead. |
| Access token | `access_token` | Credential (vault) | No |  | Main; choice Sign in with = An access token | OAuth access token from the vault, used instead of an API token. |
| User | `user` | Text | Yes |  | Main | The person's Okta user id, or their sign-in name (login), e.g. ann.lee@acme.com. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `user` | Dictionary | id, status, login, email, first_name, last_name, created, last_login. |
| `profile` | Dictionary | All profile values held in Okta. |

*Time limit 60 s; safe to repeat; needs: network.*

### List users

`ooa.okta/list_users` (action)

Return people that match a quick text search or an Okta filter expression.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Organization address (URL) | `org_url` | Text | Yes |  | Main | Your Okta address, e.g. https://yourcompany.okta.com. |
| API token | `api_token` | Credential (vault) | No |  | Main; choice Sign in with = An API token | Okta API token from the vault. Leave empty when you use an access token instead. |
| Access token | `access_token` | Credential (vault) | No |  | Main; choice Sign in with = An access token | OAuth access token from the vault, used instead of an API token. |
| Search text | `search_text` | Text | No |  | Main; choice Users = Name or email starts with | Match the start of first name, last name or email, e.g. ann. |
| Filter | `filter` | Text | No |  | Main; choice Users = Matching a search expression | Okta search expression, used instead of search_text, e.g. profile.department eq "Finance". Example: `status eq "ACTIVE"` |
| Maximum results | `max_results` | Number | No | `1000` | More options | Most users to return. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: id, status, login, email, first_name, last_name, created, last_login. |
| `count` | Number | Number of users returned. |

*Time limit 600 s; safe to repeat; needs: network.*

### Update user profile

`ooa.okta/update_user` (action)

Change selected profile values; values you leave out stay as they are.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Organization address (URL) | `org_url` | Text | Yes |  | Main | Your Okta address, e.g. https://yourcompany.okta.com. |
| API token | `api_token` | Credential (vault) | No |  | Main; choice Sign in with = An API token | Okta API token from the vault. Leave empty when you use an access token instead. |
| Access token | `access_token` | Credential (vault) | No |  | Main; choice Sign in with = An access token | OAuth access token from the vault, used instead of an API token. |
| User | `user` | Text | Yes |  | Main | The person's Okta user id, or their sign-in name (login), e.g. ann.lee@acme.com. |
| Profile fields | `profile_fields` | Dictionary | Yes |  | Main | Profile values to change, e.g. {"title": "Team Lead", "department": "Sales"}. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `user` | Dictionary | The user after the change. |

*Time limit 60 s; needs: network.*
