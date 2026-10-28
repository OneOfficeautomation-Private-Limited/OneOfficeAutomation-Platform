# Outlook (desktop, needs Outlook)

Toolkit `ooa.outlook_desktop`, version 1.0.0. Find, read, send, reply to, forward, move and file emails with the Outlook installed on the runner, using its mail profile.

[All toolkits](README.md)

## Contents

- **Mailbox**: Delete email, Read email, Get folders, Find emails, Mark read or unread, Move email, Save attachments
- **Send**: Forward email, Reply to email, Send email

## Mailbox

### Delete email

`ooa.outlook_desktop/delete` (action)

Delete an email (Outlook moves it to Deleted Items).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| ID | `id` | Text | Yes |  | Main | The email's id from Find emails. |

*Time limit 60 s; needs: desktop.*

### Read email

`ooa.outlook_desktop/get` (action)

Read one email: sender, recipients, subject, text and HTML body and attachment names.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| ID | `id` | Text | Yes |  | Main | The email's id from Find emails. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `email` | Dictionary | id, subject, from, from_name, to, cc, received, unread, body, html_body, categories, attachment_names, folder. |

*Time limit 60 s; safe to repeat; needs: desktop.*

### Get folders

`ooa.outlook_desktop/get_folders` (action)

List the folders of the mailbox with how many emails and unread emails each has.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Mailbox | `mailbox` | Text | No |  | Main | Another mailbox open in the runner's Outlook profile (its name or address), e.g. a shared mailbox; empty for the user's own. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `folders` | List | Folders: path (as Move email and Find emails take it), emails, unread. |

*Time limit 300 s; safe to repeat; needs: desktop.*

### Find emails

`ooa.outlook_desktop/list` (action)

List the newest emails in an Outlook folder, optionally only unread ones, by subject, by sender or received after a date.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Folder | `folder` | Text | No | `Inbox` | Main | Folder path: Inbox, Sent Items, Drafts, or a folder below them, e.g. Inbox/Invoices. Example: `Inbox/Invoices` |
| Unread only | `unread_only` | Yes/No | No | No | Main | Only unread emails. |
| Subject contains | `subject_contains` | Text | No |  | Main | Only emails whose subject contains this text. |
| From contains | `from_contains` | Text | No |  | Main | Only emails whose sender address or name contains this text. |
| Received after | `received_after` | DateTime | No |  | Main | Only emails received after this date and time. |
| Largest value | `max` | Number | No | `50` | Main | At most this many emails. Range: 1 to 5000 |
| Mailbox | `mailbox` | Text | No |  | More options | Another mailbox open in the runner's Outlook profile (its name or address), e.g. a shared mailbox; empty for the user's own. |
| Timeout (seconds) | `timeout_seconds` | Number | No | `120` | More options | Maximum time. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `emails` | List | Emails, newest first: id, subject, from, from_name, to, received, unread, attachments, folder. |
| `count` | Number | How many were found. |

*Time limit 300 s; safe to repeat; needs: desktop.*

### Mark read or unread

`ooa.outlook_desktop/mark` (action)

Mark an email as read or unread, and optionally add an Outlook category to it.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| ID | `id` | Text | Yes |  | Main | The email's id from Find emails. |
| Read | `read` | Yes/No | No | Yes | Main | true marks it read, false unread. |
| Category | `category` | Text | No |  | Main | An Outlook category to add, e.g. Processed. Example: `Processed` |

*Time limit 60 s; needs: desktop.*

### Move email

`ooa.outlook_desktop/move` (action)

Move an email to another folder, e.g. Inbox/Done. The email gets a new id.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| ID | `id` | Text | Yes |  | Main | The email's id from Find emails. |
| Folder | `folder` | Text | Yes |  | Main | Folder path to move it to. Example: `Inbox/Done` |
| Mailbox | `mailbox` | Text | No |  | More options | Another mailbox open in the runner's Outlook profile (its name or address), e.g. a shared mailbox; empty for the user's own. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `id` | Text | The email's new id. |

*Time limit 60 s; needs: desktop.*

### Save attachments

`ooa.outlook_desktop/save_attachments` (action)

Save an email's attachments to a folder, optionally only those matching a name pattern; existing files are kept and new ones numbered.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| ID | `id` | Text | Yes |  | Main | The email's id from Find emails. |
| Folder | `folder` | File path | Yes |  | Main | Folder to save into. |
| Name pattern | `name_pattern` | Text | No |  | Main | Only attachments whose name matches, e.g. *.pdf. Example: `*.pdf` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `files` | List | The saved files. |

*Time limit 60 s; needs: desktop, filesystem.*

## Send

### Forward email

`ooa.outlook_desktop/forward` (action)

Forward an email with its attachments, with a note above it; send it or keep it in Drafts.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| ID | `id` | Text | Yes |  | Main | The email's id from Find emails. |
| To | `to` | Text | Yes |  | Main | Recipients, separated by semicolons. |
| Body | `body` | Text | No |  | Main | A note above the forwarded email. |
| Save as draft | `save_as_draft` | Yes/No | No | No | More options | Keep it in Drafts instead of sending. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `draft_id` | Text | The draft's id when it was kept as a draft, else empty. |

*Time limit 120 s; needs: desktop.*

### Reply to email

`ooa.outlook_desktop/reply` (action)

Reply to the sender (or everyone) above the original message; send it or keep it in Drafts.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| ID | `id` | Text | Yes |  | Main | The email's id from Find emails. |
| Body | `body` | Text | No |  | Main | Your reply (text). |
| Reply all | `reply_all` | Yes/No | No | No | Main | Reply to everyone on the email. |
| Save as draft | `save_as_draft` | Yes/No | No | No | More options | Keep it in Drafts instead of sending. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `draft_id` | Text | The draft's id when it was kept as a draft, else empty. |

*Time limit 120 s; needs: desktop.*

### Send email

`ooa.outlook_desktop/send` (action)

Send an email from Outlook, or keep it in Drafts for someone to check, with attachments and optionally from another account of the profile.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| To | `to` | Text | Yes |  | Main | Recipients, separated by semicolons. Example: `ap@acme.com; finance@acme.com` |
| Cc | `cc` | Text | No |  | Main | Copy to. |
| Subject | `subject` | Text | Yes |  | Main | Subject. |
| Body | `body` | Text | No |  | Main | The message. |
| Body is HTML | `html` | Yes/No | No | No | Main | The message is HTML. |
| Attachments | `attachments` | List | No |  | Main | Files to attach. |
| Bcc | `bcc` | Text | No |  | More options | Blind copy to. |
| From account | `from_account` | Text | No |  | More options | Send from this account of the Outlook profile (its address); empty for the default. |
| Save as draft | `save_as_draft` | Yes/No | No | No | More options | Keep it in Drafts instead of sending. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `draft_id` | Text | The draft's id when it was kept as a draft, else empty. |

*Time limit 120 s; needs: desktop.*
