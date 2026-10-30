# Zendesk

Toolkit `ooa.zendesk`, version 1.0.0. Handle Zendesk Support tickets, comments, users, organizations, groups, attachments and views.

[All toolkits](README.md)

## Contents

- **Tickets**: Add comment, Add tags, Create ticket, Delete ticket, Get ticket, List comments, List ticket fields, List tickets, Search tickets, Update ticket
- **Organizations**: Create organization, Get organization, List organizations, Update organization
- **Users**: Create user, Find users, Get user, Update user
- **Attachments**: Download attachment, Upload attachment
- **Groups**: List groups
- **Views**: List tickets in view, List views

## Tickets

### Add comment

`ooa.zendesk/add_comment` (action)

Reply to a ticket with a public comment or add an internal note, optionally changing its status.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Site | `site` | Text | Yes |  | Main | Your Zendesk address, e.g. https://acme.zendesk.com, or just the subdomain acme. |
| Email | `email` | Text | No |  | Main; choice Sign in with = Email and API token | Email of the agent the API token belongs to. Leave empty when using an OAuth token. |
| API token | `api_token` | Credential (vault) | No |  | Main; choice Sign in with = Email and API token | Zendesk API token from the vault (used with email). |
| OAuth token | `oauth_token` | Credential (vault) | No |  | Main; choice Sign in with = OAuth token | OAuth access token from the vault, instead of email and API token. |
| Ticket ID | `ticket_id` | Number | Yes |  | Main | Ticket number, e.g. 35436. |
| Text | `text` | Text | Yes |  | Main | Comment text. Example: `Your refund was issued today.` |
| Public | `public` | Yes/No | No | Yes | Main | true for a reply the requester sees, false for an internal note. |
| Upload tokens | `upload_tokens` | List | No |  | Main | Tokens from Upload attachment, to attach files to this comment. |
| Body is HTML | `html` | Yes/No | No | No | More options | Treat the text as HTML. |
| New status | `new_status` | Text | No |  | More options | Also set this status, e.g. pending or solved. One of: `open`, `pending`, `hold`, `solved` |

*Time limit 60 s; needs: network.*

### Add tags

`ooa.zendesk/add_tags` (action)

Add tags to a ticket and keep the ones it already has.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Site | `site` | Text | Yes |  | Main | Your Zendesk address, e.g. https://acme.zendesk.com, or just the subdomain acme. |
| Email | `email` | Text | No |  | Main; choice Sign in with = Email and API token | Email of the agent the API token belongs to. Leave empty when using an OAuth token. |
| API token | `api_token` | Credential (vault) | No |  | Main; choice Sign in with = Email and API token | Zendesk API token from the vault (used with email). |
| OAuth token | `oauth_token` | Credential (vault) | No |  | Main; choice Sign in with = OAuth token | OAuth access token from the vault, instead of email and API token. |
| Ticket ID | `ticket_id` | Number | Yes |  | Main | Ticket number, e.g. 35436. |
| Tags | `tags` | List | Yes |  | Main | Tags to add, e.g. ["vip", "escalated"]. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `tags` | List | All tags on the ticket afterwards. |

*Time limit 60 s; needs: network.*

### Create ticket

`ooa.zendesk/create_ticket` (action)

Open a ticket with a first comment, requester, priority, tags and custom fields.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Site | `site` | Text | Yes |  | Main | Your Zendesk address, e.g. https://acme.zendesk.com, or just the subdomain acme. |
| Email | `email` | Text | No |  | Main; choice Sign in with = Email and API token | Email of the agent the API token belongs to. Leave empty when using an OAuth token. |
| API token | `api_token` | Credential (vault) | No |  | Main; choice Sign in with = Email and API token | Zendesk API token from the vault (used with email). |
| OAuth token | `oauth_token` | Credential (vault) | No |  | Main; choice Sign in with = OAuth token | OAuth access token from the vault, instead of email and API token. |
| Subject | `subject` | Text | Yes |  | Main | Ticket subject. Example: `Refund not received for order 88123` |
| Description | `description` | Text | Yes |  | Main | Text of the first comment. |
| Priority | `priority` | Text | No |  | Main | low, normal, high or urgent. One of: `low`, `normal`, `high`, `urgent` |
| Requester email | `requester_email` | Text | No |  | Main | Email of the person asking; a user is created if needed. Empty means the signed-in agent. Example: `pat@customer.com` |
| Custom fields | `custom_fields` | Dictionary | No |  | Main | Custom field id to value, e.g. {"360012345678": "order_88123"}. Ids come from List ticket fields. |
| Requester name | `requester_name` | Text | No |  | More options | Name to use when a new requester is created. |
| Type | `type` | Text | No |  | More options | problem, incident, question or task. One of: `problem`, `incident`, `question`, `task` |
| Status | `status` | Text | No |  | More options | Starting status, e.g. new or open. One of: `new`, `open`, `pending`, `hold`, `solved` |
| Assignee ID | `assignee_id` | Number | No |  | More options | Agent user id to assign. |
| Group ID | `group_id` | Number | No |  | More options | Group id to assign, from List groups. |
| Tags | `tags` | List | No |  | More options | Tags, e.g. ["refund", "agent"]. |
| Public | `public` | Yes/No | No | Yes | More options | Whether the first comment is visible to the requester. |
| Upload tokens | `upload_tokens` | List | No |  | More options | Tokens from Upload attachment, to attach files to the first comment. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `ticket_id` | Number | Number of the new ticket. |
| `url` | Text | Agent web address of the ticket. |

*Time limit 60 s; needs: network.*

### Delete ticket

`ooa.zendesk/delete_ticket` (action)

Delete a ticket. Admins can restore deleted tickets for about 30 days.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Site | `site` | Text | Yes |  | Main | Your Zendesk address, e.g. https://acme.zendesk.com, or just the subdomain acme. |
| Email | `email` | Text | No |  | Main; choice Sign in with = Email and API token | Email of the agent the API token belongs to. Leave empty when using an OAuth token. |
| API token | `api_token` | Credential (vault) | No |  | Main; choice Sign in with = Email and API token | Zendesk API token from the vault (used with email). |
| OAuth token | `oauth_token` | Credential (vault) | No |  | Main; choice Sign in with = OAuth token | OAuth access token from the vault, instead of email and API token. |
| Ticket ID | `ticket_id` | Number | Yes |  | Main | Ticket number, e.g. 35436. |

*Time limit 60 s; needs: network.*

### Get ticket

`ooa.zendesk/get_ticket` (action)

Read a ticket's fields.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Site | `site` | Text | Yes |  | Main | Your Zendesk address, e.g. https://acme.zendesk.com, or just the subdomain acme. |
| Email | `email` | Text | No |  | Main; choice Sign in with = Email and API token | Email of the agent the API token belongs to. Leave empty when using an OAuth token. |
| API token | `api_token` | Credential (vault) | No |  | Main; choice Sign in with = Email and API token | Zendesk API token from the vault (used with email). |
| OAuth token | `oauth_token` | Credential (vault) | No |  | Main; choice Sign in with = OAuth token | OAuth access token from the vault, instead of email and API token. |
| Ticket ID | `ticket_id` | Number | Yes |  | Main | Ticket number, e.g. 35436. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `ticket` | Dictionary | id, subject, description, status, priority, type, requester_id, assignee_id, group_id, organization_id, tags, custom_fields (id to value), created_at, updated_at, url. |
| `status` | Text | new, open, pending, hold, solved or closed. |

*Time limit 60 s; safe to repeat; needs: network.*

### List comments

`ooa.zendesk/list_comments` (action)

Read a ticket's conversation, oldest first, including internal notes.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Site | `site` | Text | Yes |  | Main | Your Zendesk address, e.g. https://acme.zendesk.com, or just the subdomain acme. |
| Email | `email` | Text | No |  | Main; choice Sign in with = Email and API token | Email of the agent the API token belongs to. Leave empty when using an OAuth token. |
| API token | `api_token` | Credential (vault) | No |  | Main; choice Sign in with = Email and API token | Zendesk API token from the vault (used with email). |
| OAuth token | `oauth_token` | Credential (vault) | No |  | Main; choice Sign in with = OAuth token | OAuth access token from the vault, instead of email and API token. |
| Ticket ID | `ticket_id` | Number | Yes |  | Main | Ticket number, e.g. 35436. |
| Maximum results | `max_results` | Number | No | `500` | More options | Most items to return; pages are read until this many are found. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: id, author_id, body, public, created_at. |
| `count` | Number | Number of comments. |

*Time limit 300 s; safe to repeat; needs: network.*

### List ticket fields

`ooa.zendesk/list_ticket_fields` (action)

List ticket fields with their ids, to fill custom_fields in Create ticket and Update ticket.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Site | `site` | Text | Yes |  | Main | Your Zendesk address, e.g. https://acme.zendesk.com, or just the subdomain acme. |
| Email | `email` | Text | No |  | Main; choice Sign in with = Email and API token | Email of the agent the API token belongs to. Leave empty when using an OAuth token. |
| API token | `api_token` | Credential (vault) | No |  | Main; choice Sign in with = Email and API token | Zendesk API token from the vault (used with email). |
| OAuth token | `oauth_token` | Credential (vault) | No |  | Main; choice Sign in with = OAuth token | OAuth access token from the vault, instead of email and API token. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: id, title, type, active, required. |
| `count` | Number | Number of fields. |

*Time limit 60 s; safe to repeat; needs: network.*

### List tickets

`ooa.zendesk/list_tickets` (action)

List all tickets, or only those requested by one user (by id or email) or belonging to one organization, optionally only with a given status or priority.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Site | `site` | Text | Yes |  | Main | Your Zendesk address, e.g. https://acme.zendesk.com, or just the subdomain acme. |
| Email | `email` | Text | No |  | Main; choice Sign in with = Email and API token | Email of the agent the API token belongs to. Leave empty when using an OAuth token. |
| API token | `api_token` | Credential (vault) | No |  | Main; choice Sign in with = Email and API token | Zendesk API token from the vault (used with email). |
| OAuth token | `oauth_token` | Credential (vault) | No |  | Main; choice Sign in with = OAuth token | OAuth access token from the vault, instead of email and API token. |
| Requester ID | `requester_id` | Number | No |  | Main; choice Requested by = A user id | Only tickets requested by this user id. |
| Requester email | `requester_email` | Text | No |  | Main; choice Requested by = An email address | Only tickets requested by the user with this email address; used instead of requester_id. Example: `pat@customer.com` |
| Organization ID | `organization_id` | Number | No |  | Main | Only tickets of this organization id. |
| Status | `status` | Text | No |  | Main | Only tickets with this status. One of: `new`, `open`, `pending`, `hold`, `solved`, `closed` |
| Priority | `priority` | Text | No |  | Main | Only tickets with this priority. One of: `low`, `normal`, `high`, `urgent` |
| Maximum results | `max_results` | Number | No | `500` | More options | Most items to return; pages are read until this many are found. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: id, subject, status, priority, type, requester_id, assignee_id, group_id, updated_at. |
| `count` | Number | Number of tickets returned. |

*Time limit 300 s; safe to repeat; needs: network.*

### Search tickets

`ooa.zendesk/search_tickets` (action)

Find tickets with Zendesk search terms, e.g. status<solved priority:urgent tags:refund. Search returns at most 1000 results.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Site | `site` | Text | Yes |  | Main | Your Zendesk address, e.g. https://acme.zendesk.com, or just the subdomain acme. |
| Email | `email` | Text | No |  | Main; choice Sign in with = Email and API token | Email of the agent the API token belongs to. Leave empty when using an OAuth token. |
| API token | `api_token` | Credential (vault) | No |  | Main; choice Sign in with = Email and API token | Zendesk API token from the vault (used with email). |
| OAuth token | `oauth_token` | Credential (vault) | No |  | Main; choice Sign in with = OAuth token | OAuth access token from the vault, instead of email and API token. |
| Query | `query` | Text | Yes |  | Main | Search terms; type:ticket is added for you. Example: `status<solved requester:pat@customer.com` |
| Maximum results | `max_results` | Number | No | `100` | More options | Most items to return; pages are read until this many are found. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: id, subject, status, priority, type, requester_id, assignee_id, group_id, updated_at. |
| `count` | Number | Number of tickets returned. |

*Time limit 300 s; safe to repeat; needs: network.*

### Update ticket

`ooa.zendesk/update_ticket` (action)

Change a ticket's status, priority, assignee, group, tags or custom fields. Only the inputs you fill in are changed.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Site | `site` | Text | Yes |  | Main | Your Zendesk address, e.g. https://acme.zendesk.com, or just the subdomain acme. |
| Email | `email` | Text | No |  | Main; choice Sign in with = Email and API token | Email of the agent the API token belongs to. Leave empty when using an OAuth token. |
| API token | `api_token` | Credential (vault) | No |  | Main; choice Sign in with = Email and API token | Zendesk API token from the vault (used with email). |
| OAuth token | `oauth_token` | Credential (vault) | No |  | Main; choice Sign in with = OAuth token | OAuth access token from the vault, instead of email and API token. |
| Ticket ID | `ticket_id` | Number | Yes |  | Main | Ticket number, e.g. 35436. |
| Status | `status` | Text | No |  | Main | New status. One of: `new`, `open`, `pending`, `hold`, `solved`, `closed` |
| Priority | `priority` | Text | No |  | Main | New priority. One of: `low`, `normal`, `high`, `urgent` |
| Assignee ID | `assignee_id` | Number | No |  | Main | Agent user id to assign. |
| Custom fields | `custom_fields` | Dictionary | No |  | Main | Custom field id to new value. |
| Type | `type` | Text | No |  | More options | New type. One of: `problem`, `incident`, `question`, `task` |
| Subject | `subject` | Text | No |  | More options | New subject. |
| Group ID | `group_id` | Number | No |  | More options | Group id to assign. |
| Tags | `tags` | List | No |  | More options | Replace all tags with these. To add tags without removing others use Add tags. |

*Time limit 60 s; needs: network.*

## Organizations

### Create organization

`ooa.zendesk/create_organization` (action)

Create a customer organization; users with matching email domains can join it automatically.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Site | `site` | Text | Yes |  | Main | Your Zendesk address, e.g. https://acme.zendesk.com, or just the subdomain acme. |
| Email | `email` | Text | No |  | Main; choice Sign in with = Email and API token | Email of the agent the API token belongs to. Leave empty when using an OAuth token. |
| API token | `api_token` | Credential (vault) | No |  | Main; choice Sign in with = Email and API token | Zendesk API token from the vault (used with email). |
| OAuth token | `oauth_token` | Credential (vault) | No |  | Main; choice Sign in with = OAuth token | OAuth access token from the vault, instead of email and API token. |
| Name | `name` | Text | Yes |  | Main | Organization name (must be unique). Example: `Northwind Traders` |
| Domain names | `domain_names` | List | No |  | Main | Email domains, e.g. ["northwind.com"]. |
| Organization fields | `organization_fields` | Dictionary | No |  | Main | Custom organization fields by field key, e.g. {"account_tier": "enterprise"}. |
| Details | `details` | Text | No |  | More options | Details such as an address. |
| Notes | `notes` | Text | No |  | More options | Internal notes. |
| External ID | `external_id` | Text | No |  | More options | Your own id, e.g. a CRM account number. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `organization_id` | Number | Id of the new organization. |

*Time limit 60 s; needs: network.*

### Get organization

`ooa.zendesk/get_organization` (action)

Read an organization's details.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Site | `site` | Text | Yes |  | Main | Your Zendesk address, e.g. https://acme.zendesk.com, or just the subdomain acme. |
| Email | `email` | Text | No |  | Main; choice Sign in with = Email and API token | Email of the agent the API token belongs to. Leave empty when using an OAuth token. |
| API token | `api_token` | Credential (vault) | No |  | Main; choice Sign in with = Email and API token | Zendesk API token from the vault (used with email). |
| OAuth token | `oauth_token` | Credential (vault) | No |  | Main; choice Sign in with = OAuth token | OAuth access token from the vault, instead of email and API token. |
| Organization ID | `organization_id` | Number | Yes |  | Main | Organization id. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `organization` | Dictionary | id, name, domain_names, details, notes, external_id, group_id, tags, created_at, organization_fields. |

*Time limit 60 s; safe to repeat; needs: network.*

### List organizations

`ooa.zendesk/list_organizations` (action)

List customer organizations.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Site | `site` | Text | Yes |  | Main | Your Zendesk address, e.g. https://acme.zendesk.com, or just the subdomain acme. |
| Email | `email` | Text | No |  | Main; choice Sign in with = Email and API token | Email of the agent the API token belongs to. Leave empty when using an OAuth token. |
| API token | `api_token` | Credential (vault) | No |  | Main; choice Sign in with = Email and API token | Zendesk API token from the vault (used with email). |
| OAuth token | `oauth_token` | Credential (vault) | No |  | Main; choice Sign in with = OAuth token | OAuth access token from the vault, instead of email and API token. |
| Maximum results | `max_results` | Number | No | `1000` | More options | Most items to return; pages are read until this many are found. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: id, name, domain_names, external_id, created_at. |
| `count` | Number | Number of organizations returned. |

*Time limit 300 s; safe to repeat; needs: network.*

### Update organization

`ooa.zendesk/update_organization` (action)

Change an organization's name, email domains, details, notes, external id or custom fields; empty inputs are kept.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Site | `site` | Text | Yes |  | Main | Your Zendesk address, e.g. https://acme.zendesk.com, or just the subdomain acme. |
| Email | `email` | Text | No |  | Main; choice Sign in with = Email and API token | Email of the agent the API token belongs to. Leave empty when using an OAuth token. |
| API token | `api_token` | Credential (vault) | No |  | Main; choice Sign in with = Email and API token | Zendesk API token from the vault (used with email). |
| OAuth token | `oauth_token` | Credential (vault) | No |  | Main; choice Sign in with = OAuth token | OAuth access token from the vault, instead of email and API token. |
| Organization ID | `organization_id` | Number | Yes |  | Main | Organization id. |
| Name | `name` | Text | No |  | Main | New name. |
| Organization fields | `organization_fields` | Dictionary | No |  | Main | Custom organization fields by key. |
| Domain names | `domain_names` | List | No |  | More options | Email domains (replaces the list). |
| Details | `details` | Text | No |  | More options | Details such as an address. |
| Notes | `notes` | Text | No |  | More options | Internal notes. |
| External ID | `external_id` | Text | No |  | More options | Your own id. |

*Time limit 60 s; needs: network.*

## Users

### Create user

`ooa.zendesk/create_user` (action)

Create an end user or agent, or update the existing one with the same email when asked to.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Site | `site` | Text | Yes |  | Main | Your Zendesk address, e.g. https://acme.zendesk.com, or just the subdomain acme. |
| Email | `email` | Text | No |  | Main; choice Sign in with = Email and API token | Email of the agent the API token belongs to. Leave empty when using an OAuth token. |
| API token | `api_token` | Credential (vault) | No |  | Main; choice Sign in with = Email and API token | Zendesk API token from the vault (used with email). |
| OAuth token | `oauth_token` | Credential (vault) | No |  | Main; choice Sign in with = OAuth token | OAuth access token from the vault, instead of email and API token. |
| Name | `name` | Text | Yes |  | Main | Full name. Example: `Pat Kim` |
| User email | `user_email` | Text | No |  | Main | Email of the new user. Example: `pat@customer.com` |
| Role | `role` | Text | No | `end-user` | Main | end-user, agent or admin. One of: `end-user`, `agent`, `admin` |
| User fields | `user_fields` | Dictionary | No |  | Main | Custom user fields by field key, e.g. {"customer_tier": "gold"}. |
| Organization ID | `organization_id` | Number | No |  | More options | Organization id to put the user in. |
| Phone | `phone` | Text | No |  | More options | Phone number. |
| External ID | `external_id` | Text | No |  | More options | Your own id for the user, e.g. a CRM customer number. |
| Update if exists | `update_if_exists` | Yes/No | No | No | More options | Update the existing user with this email or external id instead of failing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `user_id` | Number | Id of the user. |

*Time limit 60 s; needs: network.*

### Find users

`ooa.zendesk/find_users` (action)

Find users by name, email or other search terms, optionally only with a given role or in a given organization.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Site | `site` | Text | Yes |  | Main | Your Zendesk address, e.g. https://acme.zendesk.com, or just the subdomain acme. |
| Email | `email` | Text | No |  | Main; choice Sign in with = Email and API token | Email of the agent the API token belongs to. Leave empty when using an OAuth token. |
| API token | `api_token` | Credential (vault) | No |  | Main; choice Sign in with = Email and API token | Zendesk API token from the vault (used with email). |
| OAuth token | `oauth_token` | Credential (vault) | No |  | Main; choice Sign in with = OAuth token | OAuth access token from the vault, instead of email and API token. |
| Query | `query` | Text | No |  | Main | Name, email, or search terms such as role:agent; may be empty when role or organization_id is given. Example: `pat@customer.com` |
| Role | `role` | Text | No |  | Main | Only users with this role. One of: `end-user`, `agent`, `admin` |
| Organization ID | `organization_id` | Number | No |  | Main | Only users of this organization id. |
| Maximum results | `max_results` | Number | No | `100` | More options | Most items to return; pages are read until this many are found. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: id, name, email, role, organization_id, created_at. |
| `count` | Number | Number of users found. |

*Time limit 300 s; safe to repeat; needs: network.*

### Get user

`ooa.zendesk/get_user` (action)

Read a user's details.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Site | `site` | Text | Yes |  | Main | Your Zendesk address, e.g. https://acme.zendesk.com, or just the subdomain acme. |
| Email | `email` | Text | No |  | Main; choice Sign in with = Email and API token | Email of the agent the API token belongs to. Leave empty when using an OAuth token. |
| API token | `api_token` | Credential (vault) | No |  | Main; choice Sign in with = Email and API token | Zendesk API token from the vault (used with email). |
| OAuth token | `oauth_token` | Credential (vault) | No |  | Main; choice Sign in with = OAuth token | OAuth access token from the vault, instead of email and API token. |
| User ID | `user_id` | Number | Yes |  | Main | User id. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `user` | Dictionary | id, name, email, role, phone, organization_id, external_id, time_zone, suspended, created_at, user_fields. |

*Time limit 60 s; safe to repeat; needs: network.*

### Update user

`ooa.zendesk/update_user` (action)

Change a user's name, email, phone, organization or role. Only the inputs you fill in are changed.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Site | `site` | Text | Yes |  | Main | Your Zendesk address, e.g. https://acme.zendesk.com, or just the subdomain acme. |
| Email | `email` | Text | No |  | Main; choice Sign in with = Email and API token | Email of the agent the API token belongs to. Leave empty when using an OAuth token. |
| API token | `api_token` | Credential (vault) | No |  | Main; choice Sign in with = Email and API token | Zendesk API token from the vault (used with email). |
| OAuth token | `oauth_token` | Credential (vault) | No |  | Main; choice Sign in with = OAuth token | OAuth access token from the vault, instead of email and API token. |
| User ID | `user_id` | Number | Yes |  | Main | User id. |
| Name | `name` | Text | No |  | Main | New name. |
| Role | `role` | Text | No |  | Main | New role. One of: `end-user`, `agent`, `admin` |
| User fields | `user_fields` | Dictionary | No |  | Main | Custom user fields by key, e.g. {"customer_tier": "gold"}. |
| User email | `user_email` | Text | No |  | More options | New primary email. |
| Phone | `phone` | Text | No |  | More options | New phone number. |
| Organization ID | `organization_id` | Number | No |  | More options | New organization id. |

*Time limit 60 s; needs: network.*

## Attachments

### Download attachment

`ooa.zendesk/download_attachment` (action)

Save an attachment (from a ticket comment) to a local folder under its own name.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Site | `site` | Text | Yes |  | Main | Your Zendesk address, e.g. https://acme.zendesk.com, or just the subdomain acme. |
| Email | `email` | Text | No |  | Main; choice Sign in with = Email and API token | Email of the agent the API token belongs to. Leave empty when using an OAuth token. |
| API token | `api_token` | Credential (vault) | No |  | Main; choice Sign in with = Email and API token | Zendesk API token from the vault (used with email). |
| OAuth token | `oauth_token` | Credential (vault) | No |  | Main; choice Sign in with = OAuth token | OAuth access token from the vault, instead of email and API token. |
| Attachment ID | `attachment_id` | Number | Yes |  | Main | Attachment id, from List comments. |
| Folder | `folder` | File path | No | `.` | Main | Folder to save into. |
| Overwrite | `overwrite` | Yes/No | No | Yes | Main | Replace a file of the same name; otherwise a number is added. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `path` | File path | Saved file. |

*Time limit 600 s; needs: network, filesystem.*

### Upload attachment

`ooa.zendesk/upload_attachment` (action)

Upload a local file and get a token; pass the token to Create ticket or Add comment to attach the file.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Site | `site` | Text | Yes |  | Main | Your Zendesk address, e.g. https://acme.zendesk.com, or just the subdomain acme. |
| Email | `email` | Text | No |  | Main; choice Sign in with = Email and API token | Email of the agent the API token belongs to. Leave empty when using an OAuth token. |
| API token | `api_token` | Credential (vault) | No |  | Main; choice Sign in with = Email and API token | Zendesk API token from the vault (used with email). |
| OAuth token | `oauth_token` | Credential (vault) | No |  | Main; choice Sign in with = OAuth token | OAuth access token from the vault, instead of email and API token. |
| Path | `path` | File path | Yes |  | Main | Local file to upload. Example: `out/receipt-88123.pdf` |
| File name | `file_name` | Text | No |  | More options | Name to give the attachment; the local file name when empty. |
| Add to token | `add_to_token` | Text | No |  | More options | Token of an earlier upload, to group several files under one token. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `token` | Text | Upload token to pass in upload_tokens. |
| `attachment_id` | Number | Id of the stored file. |

*Time limit 600 s; needs: network, filesystem.*

## Groups

### List groups

`ooa.zendesk/list_groups` (action)

List the agent groups tickets can be assigned to.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Site | `site` | Text | Yes |  | Main | Your Zendesk address, e.g. https://acme.zendesk.com, or just the subdomain acme. |
| Email | `email` | Text | No |  | Main; choice Sign in with = Email and API token | Email of the agent the API token belongs to. Leave empty when using an OAuth token. |
| API token | `api_token` | Credential (vault) | No |  | Main; choice Sign in with = Email and API token | Zendesk API token from the vault (used with email). |
| OAuth token | `oauth_token` | Credential (vault) | No |  | Main; choice Sign in with = OAuth token | OAuth access token from the vault, instead of email and API token. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: id, name, description, default. |
| `count` | Number | Number of groups. |

*Time limit 60 s; safe to repeat; needs: network.*

## Views

### List tickets in view

`ooa.zendesk/list_view_tickets` (action)

List the tickets a view currently shows, e.g. a team's unassigned queue.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Site | `site` | Text | Yes |  | Main | Your Zendesk address, e.g. https://acme.zendesk.com, or just the subdomain acme. |
| Email | `email` | Text | No |  | Main; choice Sign in with = Email and API token | Email of the agent the API token belongs to. Leave empty when using an OAuth token. |
| API token | `api_token` | Credential (vault) | No |  | Main; choice Sign in with = Email and API token | Zendesk API token from the vault (used with email). |
| OAuth token | `oauth_token` | Credential (vault) | No |  | Main; choice Sign in with = OAuth token | OAuth access token from the vault, instead of email and API token. |
| View ID | `view_id` | Number | Yes |  | Main | View id, from List views. |
| Maximum results | `max_results` | Number | No | `500` | More options | Most items to return; pages are read until this many are found. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: id, subject, status, priority, type, requester_id, assignee_id, group_id, updated_at. |
| `count` | Number | Number of tickets returned. |

*Time limit 300 s; safe to repeat; needs: network.*

### List views

`ooa.zendesk/list_views` (action)

List the active ticket views the agent can use.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Site | `site` | Text | Yes |  | Main | Your Zendesk address, e.g. https://acme.zendesk.com, or just the subdomain acme. |
| Email | `email` | Text | No |  | Main; choice Sign in with = Email and API token | Email of the agent the API token belongs to. Leave empty when using an OAuth token. |
| API token | `api_token` | Credential (vault) | No |  | Main; choice Sign in with = Email and API token | Zendesk API token from the vault (used with email). |
| OAuth token | `oauth_token` | Credential (vault) | No |  | Main; choice Sign in with = OAuth token | OAuth access token from the vault, instead of email and API token. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: id, title, position, updated_at. |
| `count` | Number | Number of views. |

*Time limit 60 s; safe to repeat; needs: network.*
