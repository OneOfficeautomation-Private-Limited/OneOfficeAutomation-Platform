# Exchange (EWS)

Toolkit `ooa.exchange`, version 1.0.0. Read, send, reply to, move, mark and delete emails in an on-premises Exchange Server 2013-2019 or SE mailbox through Exchange Web Services (EWS). For Exchange Online (Microsoft 365) use the Microsoft 365 toolkit, as Microsoft is retiring EWS there.

[All toolkits](README.md)

## Contents

- **Mailbox**: Connect to Exchange mailbox, Delete email, Delete all emails, Disconnect, Read email, Get folders, Find emails, Mark read or unread, Move email, Move all emails, Save all attachments, Save attachments, Save email as .eml, Test Exchange connection
- **Send**: Forward email, Reply to email, Reply to all, Send email
- **Conditions**: Exchange folder exists
- **Loop types**: Each email in an Exchange folder

## Mailbox

### Connect to Exchange mailbox

`ooa.exchange/connect` (action)

Sign in to an on-premises Exchange Server mailbox through Exchange Web Services (EWS) as a named session; checks the sign-in by opening the inbox. For Exchange Online (Microsoft 365) use the Microsoft 365 toolkit instead: Microsoft is retiring EWS there.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Exchange web services address (URL) | `ews_url` | Text | Yes |  | Main | Address of the Exchange server's web services, usually https://<mail server>/EWS/Exchange.asmx. Example: `https://mail.example.com/EWS/Exchange.asmx` |
| Sign in | `sign_in` | Text | No | `ntlm` | Main | ntlm: Windows sign-in, which Exchange accepts by default; basic: user name and password in each request, only over https and where the server allows it; oauth: an access token from a saved sign-in connection or the vault (hybrid modern authentication) (G5). One of: `ntlm`, `basic`, `oauth` |
| User | `user` | Text | No |  | Main | User that signs in: DOMAIN\user or user@domain (or the user alone with domain). Example: `ACME\svc-mailbot`; Shown when: `{"sign_in": ["!", "oauth"]}` |
| Domain | `domain` | Text | No |  | Main | Windows domain, when user has none (G5). Example: `ACME`; Shown when: `{"sign_in": ["!", "oauth"]}` |
| Password | `password` | Credential (vault) | No |  | Main | The user's password from the vault (a Credential). Shown when: `{"sign_in": ["!", "oauth"]}` |
| Access token | `access_token` | Credential (vault) | No |  | Main | OAuth access token for the Exchange server, from a saved sign-in connection or the vault (G5). Shown when: `{"sign_in": ["oauth"]}` |
| Mailbox | `mailbox` | Text | No |  | Main; tick Open another person's or a shared mailbox | Work in this mailbox instead of the user's own, e.g. a shared mailbox; the user needs full access to it, or the impersonation role with impersonate. Example: `invoices@acme.com` |
| Impersonate | `impersonate` | Yes/No | No | No | Main; tick Open another person's or a shared mailbox | Act as the mailbox's owner through Exchange impersonation (the user needs the ApplicationImpersonation role) instead of opening it as a delegate. Shown when: `{"mailbox": ["!", ""]}` |
| Version | `version` | Text | No | `Exchange2013` | More options | The Exchange version the requests are written for (G5). One of: `Exchange2010_SP2`, `Exchange2013`, `Exchange2013_SP1`, `Exchange2016`, `Exchange2019` |
| Timeout (seconds) | `timeout_seconds` | Number | No | `60` | More options | How long to wait for each answer from the server. Range: 1 to 600 |
| Allow untrusted certificates | `insecure_tls` | Yes/No | No | No | More options | Accept any server certificate (self-signed or expired); for test systems only. |
| Session | `session` | Text | No | `exchange` | More options | Session name. |

*Time limit 60 s; needs: network.*

### Delete email

`ooa.exchange/delete` (action)

Delete an email: moved to Deleted Items, or with permanent removed from the folders (Exchange still keeps it in Recoverable Items for its retention time).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| ID | `id` | Text | Yes |  | Main | Email id from Find emails (an EWS item id). |
| Permanent | `permanent` | Yes/No | No | No | More options | Remove the email instead of moving it to Deleted Items; only an administrator can bring it back. |
| Session | `session` | Text | No | `exchange` | More options | Session name of the open Exchange mailbox. |

*Time limit 60 s; needs: network.*

### Delete all emails

`ooa.exchange/delete_all` (action)

Delete every email of a folder that the filters find (G5).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Folder | `folder` | Text | No | `inbox` | Main | Folder: a well-known name (inbox, sentitems, deleteditems, drafts, junkemail, archive), a path such as Inbox/Invoices, or a folder id. |
| Status | `status` | Text | No | `any` | Main | Which emails: any, only unread or only read. One of: `any`, `unread`, `read` |
| From contains | `from_contains` | Text | No |  | Main | Sender contains this text; separate several with ; to match any of them. |
| Subject contains | `subject_contains` | Text | No |  | Main | Subject contains this text; separate several with ; to match any of them. |
| Since | `since` | DateTime | No |  | Main | Received on or after this time (UTC). |
| Before | `before` | DateTime | No |  | Main | Received before this time (UTC). |
| Permanent | `permanent` | Yes/No | No | No | More options | Remove them instead of moving them to Deleted Items. |
| Maximum results | `max_results` | Number | No | `500` | More options | At most this many emails. Range: 1 to 10000 |
| Session | `session` | Text | No | `exchange` | More options | Session name of the open Exchange mailbox. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `count` | Number | How many emails were deleted. |

*Time limit 1800 s; needs: network.*

### Disconnect

`ooa.exchange/disconnect` (action)

Close the Exchange mailbox session.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `exchange` | Main | Session name. |

*Time limit 60 s.*

### Read email

`ooa.exchange/get` (action)

Read one email: sender, recipients, subject, text, HTML and attachment names.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| ID | `id` | Text | Yes |  | Main | Email id from Find emails (an EWS item id). |
| Mark read | `mark_read` | Yes/No | No | Yes | Main | Mark the email as read. |
| Session | `session` | Text | No | `exchange` | More options | Session name of the open Exchange mailbox. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `email` | Dictionary | id, from, to, cc, subject, received, text, html, attachments, reply_to, sent, message_id, importance, flagged, size, unread, conversation_id. Also, the same on every mail service: id, from, to, cc, subject, received, text, html, attachments (names), has_attachments, unread, message_id. |

*Time limit 60 s; safe to repeat; needs: network.*

### Get folders

`ooa.exchange/get_folders` (action)

List all mail folders of the mailbox as paths such as Inbox/Invoices.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `exchange` | Main | Session name of the open Exchange mailbox. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `folders` | List | Folder paths. |

*Time limit 60 s; safe to repeat; needs: network.*

### Find emails

`ooa.exchange/list` (action)

List emails in an Exchange folder, newest first, filtered by unread, sender, subject and date; loop over the table to process them.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Folder | `folder` | Text | No | `inbox` | Main | Folder: a well-known name (inbox, sentitems, deleteditems, drafts, junkemail, archive), a path such as Inbox/Invoices, or a folder id. |
| Unread only | `unread_only` | Yes/No | No | No | Main | Only unread emails. |
| Read only | `read_only` | Yes/No | No | No | Main | Only emails already read. |
| From contains | `from_contains` | Text | No |  | Main | Sender contains this text; separate several with ; to match any of them. |
| Subject contains | `subject_contains` | Text | No |  | Main | Subject contains this text; separate several with ; to match any of them. |
| Since | `since` | DateTime | No |  | Main | Received on or after this time (UTC). |
| Before | `before` | DateTime | No |  | Main | Received before this time (UTC). |
| Maximum results | `max_results` | Number | No | `100` | Main | Maximum emails returned. Range: 1 to  |
| Body contains | `body_contains` | Text | No |  | More options | Body contains this text (searched by the server); separate several with ; to match any of them. |
| Has attachments only | `has_attachments_only` | Yes/No | No | No | More options | Only emails that have attachments. |
| Oldest first | `oldest_first` | Yes/No | No | No | More options | Return the oldest emails first instead of the newest. |
| Session | `session` | Text | No | `exchange` | More options | Session name of the open Exchange mailbox. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: id, from, subject, received, unread, has_attachments. |
| `count` | Number | Number of emails. |

*Time limit 300 s; safe to repeat; needs: network.*

### Mark read or unread

`ooa.exchange/mark` (action)

Mark an email as read or unread, and optionally set or clear its follow-up flag.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| ID | `id` | Text | Yes |  | Main | Email id from Find emails (an EWS item id). |
| Status | `status` | Text | No |  | Main | Mark it read or unread (G5); the tick below for older steps. One of: `read`, `unread` |
| Read | `read` | Yes/No | No | Yes | Main | True = read, false = unread. |
| Flagged | `flagged` | Yes/No | No |  | More options | Set (true) or clear (false) the follow-up flag; unchanged when empty. |
| Session | `session` | Text | No | `exchange` | More options | Session name of the open Exchange mailbox. |

*Time limit 60 s; needs: network.*

### Move email

`ooa.exchange/move` (action)

Move an email to another folder, e.g. Inbox/Processed; returns its new id, as Exchange gives a moved email a new one.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| ID | `id` | Text | Yes |  | Main | Email id from Find emails (an EWS item id). |
| To folder | `to_folder` | Text | Yes |  | Main | Destination: a well-known name such as archive, a path such as Inbox/Processed, or a folder id. |
| Create folder | `create_folder` | Yes/No | No | No | More options | Create the destination folder (and the folders on its path) when it does not exist. |
| Session | `session` | Text | No | `exchange` | More options | Session name of the open Exchange mailbox. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `new_id` | Text | The email's id in the destination folder. |

*Time limit 60 s; needs: network.*

### Move all emails

`ooa.exchange/move_all` (action)

Move every email of a folder that the filters find to another folder (G5).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Folder | `folder` | Text | No | `inbox` | Main | Folder: a well-known name (inbox, sentitems, deleteditems, drafts, junkemail, archive), a path such as Inbox/Invoices, or a folder id. |
| Status | `status` | Text | No | `any` | Main | Which emails: any, only unread or only read. One of: `any`, `unread`, `read` |
| From contains | `from_contains` | Text | No |  | Main | Sender contains this text; separate several with ; to match any of them. |
| Subject contains | `subject_contains` | Text | No |  | Main | Subject contains this text; separate several with ; to match any of them. |
| Since | `since` | DateTime | No |  | Main | Received on or after this time (UTC). |
| Before | `before` | DateTime | No |  | Main | Received before this time (UTC). |
| To folder | `to_folder` | Text | Yes |  | Main | Destination: a well-known name, a path such as Inbox/Processed, or a folder id. |
| Create folder | `create_folder` | Yes/No | No | No | More options | Create the destination when it does not exist. |
| Maximum results | `max_results` | Number | No | `500` | More options | At most this many emails. Range: 1 to 10000 |
| Session | `session` | Text | No | `exchange` | More options | Session name of the open Exchange mailbox. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `new_ids` | List | The moved emails' new ids. |

*Time limit 1800 s; needs: network.*

### Save all attachments

`ooa.exchange/save_all_attachments` (action)

Save the attachments of every email of a folder that the filters find (G5).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Folder | `folder` | Text | No | `inbox` | Main | Folder: a well-known name (inbox, sentitems, deleteditems, drafts, junkemail, archive), a path such as Inbox/Invoices, or a folder id. |
| Status | `status` | Text | No | `any` | Main | Which emails: any, only unread or only read. One of: `any`, `unread`, `read` |
| From contains | `from_contains` | Text | No |  | Main | Sender contains this text; separate several with ; to match any of them. |
| Subject contains | `subject_contains` | Text | No |  | Main | Subject contains this text; separate several with ; to match any of them. |
| Since | `since` | DateTime | No |  | Main | Received on or after this time (UTC). |
| Before | `before` | DateTime | No |  | Main | Received before this time (UTC). |
| Target folder | `target_folder` | File path | Yes |  | Main | Folder to save into. |
| Overwrite | `overwrite` | Yes/No | No | No | Main | Replace files of the same name instead of adding a number. |
| Name pattern | `name_pattern` | Text | No | `*` | More options | Only names matching this pattern, e.g. *.pdf. |
| Maximum results | `max_results` | Number | No | `500` | More options | At most this many emails. Range: 1 to 10000 |
| Session | `session` | Text | No | `exchange` | More options | Session name of the open Exchange mailbox. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `files` | List | Saved file paths. |

*Time limit 1800 s; needs: network, filesystem.*

### Save attachments

`ooa.exchange/save_attachments` (action)

Save an email's file attachments to a folder, optionally only names matching a pattern such as *.pdf.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| ID | `id` | Text | Yes |  | Main | Email id from Find emails (an EWS item id). |
| Target folder | `target_folder` | File path | Yes |  | Main | Folder to save into. |
| Overwrite | `overwrite` | Yes/No | No | No | Main | Replace files of the same name instead of adding a number such as (2). |
| Name pattern | `name_pattern` | Text | No | `*` | More options | Only names matching this pattern, e.g. *.pdf. |
| Include inline | `include_inline` | Yes/No | No | Yes | More options | Also save images embedded in the body, such as signature logos. |
| Session | `session` | Text | No | `exchange` | More options | Session name of the open Exchange mailbox. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `files` | List | Saved file paths. |

*Time limit 60 s; needs: network, filesystem.*

### Save email as .eml

`ooa.exchange/save_email_as_eml` (action)

Save a whole email (text, HTML, attachments and headers) as an .eml file that any mail program opens.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| ID | `id` | Text | Yes |  | Main | Email id from Find emails (an EWS item id). |
| Local path | `local_path` | File path | Yes |  | Main | The .eml file to write, or a folder: the subject then names the file. |
| Overwrite | `overwrite` | Yes/No | No | Yes | More options | Replace a file that is already there; when false it stops the step. |
| Session | `session` | Text | No | `exchange` | More options | Session name of the open Exchange mailbox. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `path` | File path | The saved file. |

*Time limit 60 s; needs: network, filesystem.*

### Test Exchange connection

`ooa.exchange/test_connection` (action)

Check an Exchange address and sign-in without keeping a session: ok and what went wrong (G5).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Ews address (URL) | `ews_url` | Text | Yes |  | Main | Address of the Exchange server's web services. Example: `https://mail.example.com/EWS/Exchange.asmx` |
| Sign in | `sign_in` | Text | No | `ntlm` | Main | How to sign in. One of: `ntlm`, `basic`, `oauth` |
| User | `user` | Text | No |  | Main | User that signs in. Shown when: `{"sign_in": ["!", "oauth"]}` |
| Domain | `domain` | Text | No |  | Main | Windows domain, when user has none. Shown when: `{"sign_in": ["!", "oauth"]}` |
| Password | `password` | Credential (vault) | No |  | Main | The user's password from the vault. Shown when: `{"sign_in": ["!", "oauth"]}` |
| Access token | `access_token` | Credential (vault) | No |  | Main | OAuth access token. Shown when: `{"sign_in": ["oauth"]}` |
| Version | `version` | Text | No | `Exchange2013` | More options | The Exchange version. One of: `Exchange2010_SP2`, `Exchange2013`, `Exchange2013_SP1`, `Exchange2016`, `Exchange2019` |
| Allow untrusted certificates | `insecure_tls` | Yes/No | No | No | More options | Accept any server certificate; for test systems only. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `ok` | Yes/No | True when the sign-in worked and the inbox opened. |
| `message` | Text | What went wrong, or empty. |

*Time limit 60 s; needs: network.*

## Send

### Forward email

`ooa.exchange/forward` (action)

Forward an email, with its attachments, and a note shown above the original.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| ID | `id` | Text | Yes |  | Main | Email id from Find emails (an EWS item id). |
| To | `to` | Any | Yes |  | Main | Recipient address(es). |
| Cc | `cc` | Any | No |  | Main | CC address(es). |
| Bcc | `bcc` | Any | No |  | Main | BCC address(es). |
| Note | `note` | Text | No | (empty) | Main | Text shown above the forwarded email. |
| Body is HTML | `html` | Yes/No | No | No | Main | The note is HTML. |
| Attachments | `attachments` | List | No |  | Main | Files to attach (paths); the reply is then saved as a draft, given the files and sent. |
| As attachment | `as_attachment` | Yes/No | No | No | More options | Attach the original email as an .eml file instead of quoting it below the note. |
| Session | `session` | Text | No | `exchange` | More options | Session name of the open Exchange mailbox. |

*Time limit 120 s; needs: network.*

### Reply to email

`ooa.exchange/reply` (action)

Reply to the sender of an email, keeping it in the same conversation with the original quoted below.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| ID | `id` | Text | Yes |  | Main | Email id from Find emails (an EWS item id). |
| Body | `body` | Text | No | (empty) | Main | Reply text, shown above the original. |
| Body is HTML | `html` | Yes/No | No | No | Main | Body is HTML. |
| Attachments | `attachments` | List | No |  | Main | Files to attach (paths); the reply is then saved as a draft, given the files and sent. |
| Cc | `cc` | Any | No |  | More options | Extra CC address(es). |
| Bcc | `bcc` | Any | No |  | More options | BCC address(es). |
| Session | `session` | Text | No | `exchange` | More options | Session name of the open Exchange mailbox. |

*Time limit 120 s; needs: network.*

### Reply to all

`ooa.exchange/reply_all` (action)

Reply to the sender and all other recipients of an email, keeping it in the same conversation with the original quoted below.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| ID | `id` | Text | Yes |  | Main | Email id from Find emails (an EWS item id). |
| Body | `body` | Text | No | (empty) | Main | Reply text, shown above the original. |
| Body is HTML | `html` | Yes/No | No | No | Main | Body is HTML. |
| Attachments | `attachments` | List | No |  | Main | Files to attach (paths); the reply is then saved as a draft, given the files and sent. |
| Cc | `cc` | Any | No |  | More options | Extra CC address(es). |
| Bcc | `bcc` | Any | No |  | More options | BCC address(es). |
| Exclude sender | `exclude_sender` | Yes/No | No | No | More options | Leave the original sender out, replying only to the other recipients (e.g. to tell the team, not the customer). |
| Session | `session` | Text | No | `exchange` | More options | Session name of the open Exchange mailbox. |

*Time limit 120 s; needs: network.*

### Send email

`ooa.exchange/send` (action)

Send an email (plain text or HTML) with optional CC, BCC and attachments from the Exchange mailbox, keeping a copy in Sent Items.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| To | `to` | Any | Yes |  | Main | Recipient address(es); separate several with ; or give a list. |
| Cc | `cc` | Any | No |  | Main | CC address(es). |
| Bcc | `bcc` | Any | No |  | Main | BCC address(es). |
| Subject | `subject` | Text | Yes |  | Main | Subject. |
| Attachments | `attachments` | List | No |  | Main | File paths to attach. |
| Body | `body` | Text | No | (empty) | Main | Message body. |
| Body is HTML | `html` | Yes/No | No | No | Main | Body is HTML. |
| From | `from` | Text | No |  | More options | Send as this address, e.g. a shared mailbox; the user needs Send As or Send on Behalf rights. The session's mailbox when empty. Example: `invoices@acme.com` |
| Reply to | `reply_to` | Any | No |  | More options | Address(es) that replies go to, when not the sender. |
| Importance | `importance` | Text | No | `normal` | More options | Importance shown to the recipients. One of: `low`, `normal`, `high` |
| Read receipt | `read_receipt` | Yes/No | No | No | More options | Ask for a receipt when the email is read. |
| Save to sent items | `save_to_sent_items` | Yes/No | No | Yes | More options | Keep a copy in Sent Items. |
| Session | `session` | Text | No | `exchange` | More options | Session name of the open Exchange mailbox. |

*Time limit 120 s; needs: network.*

## Conditions

Used in If, Loop while and Wait until steps.

### Exchange folder exists

`ooa.exchange/folder_exists` (condition)

True when the mailbox has the folder, e.g. Inbox/Invoices (G5).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Folder | `folder` | Text | Yes |  | Main | Folder: a well-known name (inbox, sentitems, deleteditems, drafts, junkemail, archive), a path such as Inbox/Invoices, or a folder id. Example: `Inbox/Invoices` |
| Session | `session` | Text | No | `exchange` | Main | Session name of the open Exchange mailbox. |

## Loop types

Used as the kind of loop in a Loop step.

### Each email in an Exchange folder

`ooa.exchange/emails` (loop type)

Yield each email matching the Find emails filters as a dictionary: id, from, subject, received, unread, has_attachments.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Folder | `folder` | Text | No | `inbox` | Main | Folder: a well-known name (inbox, sentitems, deleteditems, drafts, junkemail, archive), a path such as Inbox/Invoices, or a folder id. |
| Unread only | `unread_only` | Yes/No | No | No | Main | Only unread emails. |
| From contains | `from_contains` | Text | No |  | Main | Sender contains this text; separate several with ; to match any of them. |
| Subject contains | `subject_contains` | Text | No |  | Main | Subject contains this text; separate several with ; to match any of them. |
| Since | `since` | DateTime | No |  | Main | Received on or after this time (UTC). |
| Maximum results | `max_results` | Number | No | `100` | Main | Stop after this many emails. Range: 1 to  |
| Session | `session` | Text | No | `exchange` | Main | Session name of the open Exchange mailbox. |
| Before | `before` | DateTime | No |  | Main | Received before this time (UTC). |
| Read only | `read_only` | Yes/No | No | No | Main | Only emails already read. |
| Has attachments only | `has_attachments_only` | Yes/No | No | No | Main | Only emails that have attachments. |
| Oldest first | `oldest_first` | Yes/No | No | No | Main | The oldest emails first instead of the newest. |
| Body contains | `body_contains` | Text | No |  | Main | Body contains this text (searched by the server); separate several with ; to match any of them. |
| With body | `with_body` | Yes/No | No | No | Main | Give each email whole: text, HTML, recipients and attachment names (one more request per email); the email stays unread. Without it each email has id, from, subject, received, unread and has_attachments. |
