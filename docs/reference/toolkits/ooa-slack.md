# Slack

Toolkit `ooa.slack`, version 1.0.0. Post messages to Slack channels and users.

[All toolkits](README.md)

## Contents

- **Channels**: Get channel details, Get channel id by name, Get conversation history
- **Users**: Get user details
- **Workspace**: Get workspace details, Get workspace id by name
- **Messages**: Post message, Send direct message, Update message, Upload file

## Channels

### Get channel details

`ooa.slack/get_channel_details` (action)

Read a channel's name, topic, purpose, privacy and member count.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Bot token | `bot_token` | Credential (vault) | Yes |  | Main | Slack bot token from the vault. |
| Channel | `channel` | Text | Yes |  | Main | Channel id. |
| Endpoint address | `base_url` | Text | No | `https://slack.com/api` | More options | API endpoint; change only for testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `channel` | Dictionary | id, name, is_private, is_archived, topic, purpose, num_members, created. |

*Time limit 60 s; safe to repeat; needs: network.*

### Get channel id by name

`ooa.slack/get_channel_id_by_name` (action)

Find a channel's id from its name, e.g. #ops-alerts.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Bot token | `bot_token` | Credential (vault) | Yes |  | Main | Slack bot token from the vault. |
| Name | `name` | Text | Yes |  | Main | Channel name, with or without the leading #. Example: `ops-alerts` |
| Include private | `include_private` | Yes/No | No | Yes | More options | Also look in private channels the agent belongs to. |
| Include archived | `include_archived` | Yes/No | No | No | More options | Also look in archived channels. |
| Endpoint address | `base_url` | Text | No | `https://slack.com/api` | More options | API endpoint; change only for testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `channel_id` | Text | Channel id, e.g. C0123456789. |

*Time limit 300 s; safe to repeat; needs: network.*

### Get conversation history

`ooa.slack/get_conversation_history` (action)

Read recent messages of a channel or direct conversation into a table, newest first.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Bot token | `bot_token` | Credential (vault) | Yes |  | Main | Slack bot token from the vault. |
| Channel | `channel` | Text | Yes |  | Main | Channel or conversation id. |
| Oldest | `oldest` | Text | No |  | Main | Only messages after this timestamp id (e.g. 1700000000.000100). |
| Latest | `latest` | Text | No |  | Main | Only messages before this timestamp id. |
| Maximum messages | `max_messages` | Number | No | `200` | Main | Maximum messages to read. |
| Thread ts | `thread_ts` | Text | No |  | More options | Read the replies of this thread instead of the channel. |
| Inclusive | `inclusive` | Yes/No | No | No | More options | Also include the messages exactly at oldest and latest. |
| Endpoint address | `base_url` | Text | No | `https://slack.com/api` | More options | API endpoint; change only for testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: ts, user, text, thread_ts, reply_count, subtype. |
| `count` | Number | Number of messages. |

*Time limit 300 s; safe to repeat; needs: network.*

## Users

### Get user details

`ooa.slack/get_user_details` (action)

Look up a person by member id or email and return their profile.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Bot token | `bot_token` | Credential (vault) | Yes |  | Main | Slack bot token from the vault. |
| User | `user` | Text | Yes |  | Main | Member id (U...) or email address. |
| Endpoint address | `base_url` | Text | No | `https://slack.com/api` | More options | API endpoint; change only for testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `user` | Dictionary | id, name, real_name, display_name, email, title, time_zone, is_bot, deleted. |

*Time limit 60 s; safe to repeat; needs: network.*

## Workspace

### Get workspace details

`ooa.slack/get_workspace_details` (action)

Read the workspace's id, name and domain.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Bot token | `bot_token` | Credential (vault) | Yes |  | Main | Slack bot token from the vault. |
| Workspace ID | `workspace_id` | Text | No |  | Main | Workspace (team) id, for apps installed in several workspaces; the token's own workspace when empty. |
| Endpoint address | `base_url` | Text | No | `https://slack.com/api` | More options | API endpoint; change only for testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `workspace` | Dictionary | id, name, domain, email_domain. |

*Time limit 60 s; safe to repeat; needs: network.*

### Get workspace id by name

`ooa.slack/get_workspace_id_by_name` (action)

Find a workspace's id from its name or domain, among the workspaces the agent's app is installed in.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Bot token | `bot_token` | Credential (vault) | Yes |  | Main | Slack bot token from the vault. |
| Name | `name` | Text | Yes |  | Main | Workspace name or domain (not case-sensitive). |
| Endpoint address | `base_url` | Text | No | `https://slack.com/api` | More options | API endpoint; change only for testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `workspace_id` | Text | Workspace (team) id, e.g. T0123456789. |

*Time limit 60 s; safe to repeat; needs: network.*

## Messages

### Post message

`ooa.slack/post_message` (action)

Post a message to a channel (or a user's DM), optionally as a thread reply.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Bot token | `bot_token` | Credential (vault) | Yes |  | Main | Slack bot token (xoxb-...) from the vault. |
| Channel | `channel` | Text | Yes |  | Main | Channel id, a user id for a direct message, or a channel name such as #ops-alerts (looked up first). Example: `C0123456789` |
| Text | `text` | Text | Yes |  | Main | Message text (Slack markdown). |
| Thread ts | `thread_ts` | Text | No |  | Main | Reply in the thread of this message. |
| Files | `files` | List | No |  | Main | Local files to share in the same conversation, right after the message. |
| Reply broadcast | `reply_broadcast` | Yes/No | No | No | More options | Thread replies only: also show the reply in the channel. |
| Blocks | `blocks` | Any | No |  | More options | Block Kit layout: a List of blocks or its JSON text; the text is then the notification fallback. |
| Unfurl links | `unfurl_links` | Yes/No | No |  | More options | Show previews of linked pages; Slack decides when empty. |
| Unfurl media | `unfurl_media` | Yes/No | No |  | More options | Show previews of linked images and videos; Slack decides when empty. |
| Mrkdwn | `mrkdwn` | Yes/No | No | Yes | More options | Format the text as Slack markdown; false sends it as typed. |
| Username | `username` | Text | No |  | More options | Name shown as the sender (needs the chat:write.customize scope). |
| Icon emoji | `icon_emoji` | Text | No |  | More options | Emoji shown as the sender's picture (needs chat:write.customize). Example: `:robot_face:` |
| Endpoint address | `base_url` | Text | No | `https://slack.com/api` | More options | API endpoint; change only for testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `ts` | Text | Message timestamp id (use it to reply in a thread). |

*Time limit 60 s; needs: network.*

### Send direct message

`ooa.slack/send_direct_message` (action)

Open a direct conversation with a person (by member id or email) and post a message there.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Bot token | `bot_token` | Credential (vault) | Yes |  | Main | Slack bot token (xoxb-...) from the vault. |
| User | `user` | Text | Yes |  | Main | Member id (U...) or the person's email address; several separated by , or ; open a group conversation. Example: `ann@example.com` |
| Text | `text` | Text | Yes |  | Main | Message text (Slack markdown). |
| Thread ts | `thread_ts` | Text | No |  | More options | Reply in the thread of this message. |
| Blocks | `blocks` | Any | No |  | More options | Block Kit layout: a List of blocks or its JSON text; the text is then the notification fallback. |
| Unfurl links | `unfurl_links` | Yes/No | No |  | More options | Show previews of linked pages; Slack decides when empty. |
| Unfurl media | `unfurl_media` | Yes/No | No |  | More options | Show previews of linked images and videos; Slack decides when empty. |
| Mrkdwn | `mrkdwn` | Yes/No | No | Yes | More options | Format the text as Slack markdown; false sends it as typed. |
| Files | `files` | List | No |  | More options | Local files to share in the conversation, right after the message. |
| Endpoint address | `base_url` | Text | No | `https://slack.com/api` | More options | API endpoint; change only for testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `ts` | Text | Message timestamp id. |
| `channel` | Text | Direct conversation id. |

*Time limit 60 s; needs: network.*

### Update message

`ooa.slack/update_message` (action)

Edit a message the agent posted, e.g. to show progress.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Bot token | `bot_token` | Credential (vault) | Yes |  | Main | Slack bot token from the vault. |
| Channel | `channel` | Text | Yes |  | Main | Channel id. |
| Ts | `ts` | Text | Yes |  | Main | Timestamp id of the message. |
| Text | `text` | Text | Yes |  | Main | New text. |
| Blocks | `blocks` | Any | No |  | More options | New Block Kit layout: a List of blocks or its JSON text. |
| Endpoint address | `base_url` | Text | No | `https://slack.com/api` | More options | API endpoint; change only for testing. |

*Time limit 60 s; needs: network.*

### Upload file

`ooa.slack/upload_file` (action)

Share one or more local files in a channel or direct conversation, with an optional comment or as a thread reply.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Bot token | `bot_token` | Credential (vault) | Yes |  | Main | Slack bot token (xoxb-...) from the vault; needs the files:write scope. |
| Channel | `channel` | Text | Yes |  | Main | Channel or conversation id. Example: `C0123456789` |
| Files | `files` | List | Yes |  | Main | Local file paths. |
| Comment | `comment` | Text | No |  | Main | Message posted with the files. |
| Thread ts | `thread_ts` | Text | No |  | More options | Share in the thread of this message. |
| Endpoint address | `base_url` | Text | No | `https://slack.com/api` | More options | API endpoint; change only for testing. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `file_ids` | List | Slack file ids. |

*Time limit 600 s; needs: network, filesystem.*
