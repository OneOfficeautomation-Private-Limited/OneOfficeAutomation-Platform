# Email

Toolkit `ooa.email`, version 1.0.0. Send emails with attachments, read and search mailboxes, save attachments, move, mark and delete emails.

[All toolkits](README.md)

## Contents

- **Mailbox**: Connect to mailbox, Delete email, Delete all matching emails, Disconnect, Check folder exists, Read email, Get folders, Find emails, Mark read or unread, Move email, Move all matching emails, Save attachments of all matching emails, Save attachments, Save email as .eml
- **Send**: Forward email, Reply to email, Reply to all, Send email
- **Loop types**: Each email in a mailbox

## Mailbox

### Connect to mailbox

`ooa.email/connect` (action)

Sign in to a mailbox over IMAP or POP3 as a named session, optionally opening a shared mailbox.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Incoming mail server (IMAP) | `imap_host` | Text | Yes |  | Main | Mail server that the agent reads from (IMAP or POP3 host). Example: `outlook.office365.com` |
| User | `user` | Text | Yes |  | Main | Mailbox user name. |
| Password | `password` | Credential (vault) | No |  | Main; choice Sign in with = A password | Password or app password from the vault. |
| Access token | `access_token` | Credential (vault) | No |  | Main; choice Sign in with = An access token | OAuth 2.0 access token from the vault. |
| Protocol | `protocol` | Text | No | `imap` | Main | imap keeps folders and read status on the server; pop3 reads only the inbox and cannot mark or move emails. One of: `imap`, `pop3` |
| Security | `security` | Text | No | `ssl` | Main | Connection security. One of: `ssl`, `starttls`, `none` |
| Port | `port` | Number | No | `993` | Main | Server port: 993 IMAP over SSL, 143 IMAP, 995 POP3 over SSL, 110 POP3. Left at 993, the usual port of the chosen protocol and security is used. |
| Outgoing mail server (SMTP) | `smtp_host` | Text | No |  | Main; tick Keep an outgoing server for sending | Outgoing server to keep with this session, so Send, Reply and Forward need no server or sign-in of their own. Example: `smtp.office365.com` |
| SMTP port | `smtp_port` | Number | No | `587` | Main; tick Keep an outgoing server for sending | 587 for STARTTLS, 465 for SSL. Range: 1 to 65535; Shown when: `{"smtp_host": ["!", ""]}` |
| SMTP security | `smtp_security` | Text | No | `starttls` | Main; tick Keep an outgoing server for sending | Outgoing connection security. One of: `starttls`, `ssl`, `none`; Shown when: `{"smtp_host": ["!", ""]}` |
| Shared mailbox | `shared_mailbox` | Text | No |  | More options | Open this shared mailbox with the user's own sign-in (Exchange and Microsoft 365; the user needs full access to it). Example: `invoices@acme.com` |
| Timeout (seconds) | `timeout_seconds` | Number | No | `15` | More options | How long to wait for the server to answer when connecting. Range: 1 to 300 |
| Session | `session` | Text | No | `mail` | More options | Session name. |

*Time limit 60 s; needs: network.*

### Delete email

`ooa.email/delete` (action)

Delete an email (moved to Trash/Deleted Items when that folder exists), or delete it for good.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| ID | `id` | Number | Yes |  | Main | Email id. |
| Folder | `folder` | Text | No | `INBOX` | Main | Mail folder. |
| Permanent | `permanent` | Yes/No | No | No | More options | Delete for good instead of moving to Trash or Deleted Items. |
| Session | `session` | Text | No | `mail` | More options | Session name. |

*Time limit 60 s; needs: network.*

### Delete all matching emails

`ooa.email/delete_all` (action)

Delete every email in a folder that matches the filters (all emails when no filter is set).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Folder | `folder` | Text | No | `INBOX` | Main | Mail folder. |
| Unread only | `unread_only` | Yes/No | No | No | Main | Only unread emails. |
| Read only | `read_only` | Yes/No | No | No | Main | Only emails already read. |
| Subject contains | `subject_contains` | Text | No |  | Main | Subject contains this text; separate several with ; to match any of them. |
| From contains | `from_contains` | Text | No |  | Main | Sender contains this text; separate several with ; to match any of them. |
| Since | `since` | DateTime | No |  | Main | Received on or after this date. |
| Before | `before` | DateTime | No |  | Main | Received before this date. |
| Permanent | `permanent` | Yes/No | No | No | More options | Delete for good instead of moving to Trash or Deleted Items. |
| Expunge | `expunge` | Yes/No | No | Yes | More options | Permanent mode: remove the emails now; when false they are only marked deleted until the session closes. |
| To contains | `to_contains` | Text | No |  | More options | A To recipient contains this text. |
| Body contains | `body_contains` | Text | No |  | More options | The body contains this text (searched by the server). |
| Session | `session` | Text | No | `mail` | More options | Session name of the open mailbox. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `count` | Number | Number of emails deleted. |

*Time limit 600 s; needs: network.*

### Disconnect

`ooa.email/disconnect` (action)

Close the mailbox session.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `mail` | Main | Session name. |

*Time limit 60 s.*

### Check folder exists

`ooa.email/folder_exists` (action)

Check whether a mail folder exists in the open mailbox.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Folder | `folder` | Text | Yes |  | Main | Folder name; use / or the server's separator for subfolders. Example: `Processed` |
| Session | `session` | Text | No | `mail` | More options | Session name of the open mailbox. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `exists` | Yes/No | True when the folder exists. |

*Time limit 60 s; safe to repeat; needs: network.*

### Read email

`ooa.email/get` (action)

Read one email: sender, recipients, subject, text, HTML and attachment names.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| ID | `id` | Number | Yes |  | Main | Email id from Find emails. |
| Folder | `folder` | Text | No | `INBOX` | Main | Mail folder. |
| Mark read | `mark_read` | Yes/No | No | Yes | Main | Mark the email as read (IMAP only). |
| Session | `session` | Text | No | `mail` | More options | Session name. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `email` | Dictionary | id, from, to, cc, subject, received, text, html, attachments, reply_to, sent, message_id, importance, flagged, size. |

*Time limit 60 s; safe to repeat; needs: network.*

### Get folders

`ooa.email/get_folders` (action)

List all mail folders.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Session | `session` | Text | No | `mail` | Main | Session name. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `folders` | List | Folder names. |

*Time limit 60 s; safe to repeat; needs: network.*

### Find emails

`ooa.email/list` (action)

List emails in a folder, newest first, filtered by unread, sender, subject and date; loop over the table to process them.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Folder | `folder` | Text | No | `INBOX` | Main | Mail folder. |
| Unread only | `unread_only` | Yes/No | No | No | Main | Only unread emails. |
| Read only | `read_only` | Yes/No | No | No | Main | Only emails already read. |
| From contains | `from_contains` | Text | No |  | Main | Sender contains this text; separate several with ; to match any of them. |
| Subject contains | `subject_contains` | Text | No |  | Main | Subject contains this text; separate several with ; to match any of them. |
| Since | `since` | DateTime | No |  | Main | Received on or after this date. |
| Before | `before` | DateTime | No |  | Main | Received before this date. |
| Maximum results | `max_results` | Number | No | `100` | Main | Maximum emails returned. |
| To contains | `to_contains` | Text | No |  | More options | A To recipient contains this text. |
| Body contains | `body_contains` | Text | No |  | More options | The body contains this text (searched by the server). |
| Has attachments only | `has_attachments_only` | Yes/No | No | No | More options | Only emails that have attachments. |
| Oldest first | `oldest_first` | Yes/No | No | No | More options | Return the oldest emails first instead of the newest. |
| Session | `session` | Text | No | `mail` | More options | Session name. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: id, from, subject, received, unread, has_attachments. |
| `count` | Number | Number of emails. |

*Time limit 300 s; safe to repeat; needs: network.*

### Mark read or unread

`ooa.email/mark` (action)

Mark an email as read or unread, and optionally set or clear its follow-up flag.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| ID | `id` | Number | Yes |  | Main | Email id. |
| Read | `read` | Yes/No | No | Yes | Main | True = read, false = unread. |
| Folder | `folder` | Text | No | `INBOX` | Main | Mail folder. |
| Flagged | `flagged` | Yes/No | No |  | More options | Set (true) or clear (false) the follow-up flag; unchanged when empty. |
| Session | `session` | Text | No | `mail` | More options | Session name. |

*Time limit 60 s; needs: network.*

### Move email

`ooa.email/move` (action)

Move an email to another folder, e.g. Processed.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| ID | `id` | Number | Yes |  | Main | Email id. |
| Folder | `folder` | Text | No | `INBOX` | Main | Current folder. |
| To folder | `to_folder` | Text | Yes |  | Main | Destination folder. |
| Create folder | `create_folder` | Yes/No | No | No | More options | Create the destination folder when it does not exist. |
| Session | `session` | Text | No | `mail` | More options | Session name. |

*Time limit 60 s; needs: network.*

### Move all matching emails

`ooa.email/move_all` (action)

Move every email in a folder that matches the filters to another folder.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| To folder | `to_folder` | Text | Yes |  | Main | Destination folder. |
| Unread only | `unread_only` | Yes/No | No | No | Main | Only unread emails. |
| Read only | `read_only` | Yes/No | No | No | Main | Only emails already read. |
| Folder | `folder` | Text | No | `INBOX` | Main | Current folder. |
| Subject contains | `subject_contains` | Text | No |  | Main | Subject contains this text; separate several with ; to match any of them. |
| From contains | `from_contains` | Text | No |  | Main | Sender contains this text; separate several with ; to match any of them. |
| Since | `since` | DateTime | No |  | Main | Received on or after this date. |
| Before | `before` | DateTime | No |  | Main | Received before this date. |
| To contains | `to_contains` | Text | No |  | More options | A To recipient contains this text. |
| Body contains | `body_contains` | Text | No |  | More options | The body contains this text (searched by the server). |
| Create folder | `create_folder` | Yes/No | No | No | More options | Create the destination folder when it does not exist. |
| Session | `session` | Text | No | `mail` | More options | Session name of the open mailbox. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `count` | Number | Number of emails moved. |

*Time limit 600 s; needs: network.*

### Save attachments of all matching emails

`ooa.email/save_all_attachments` (action)

Save the attachments of every email in a folder that matches the filters, optionally marking those emails as read.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Unread only | `unread_only` | Yes/No | No | No | Main | Only unread emails. |
| Mark read | `mark_read` | Yes/No | No | No | Main | Mark each matching email as read afterwards (IMAP only). |
| Folder | `folder` | Text | No | `INBOX` | Main | Mail folder. |
| Target folder | `target_folder` | File path | Yes |  | Main | Folder to save into. |
| Overwrite | `overwrite` | Yes/No | No | No | Main | Replace files of the same name instead of adding a number such as (2). |
| Name pattern | `name_pattern` | Text | No | `*` | More options | Only names matching this pattern, e.g. *.pdf. |
| From contains | `from_contains` | Text | No |  | More options | Sender contains this text; separate several with ; to match any of them. |
| Subject contains | `subject_contains` | Text | No |  | More options | Subject contains this text; separate several with ; to match any of them. |
| Since | `since` | DateTime | No |  | More options | Received on or after this date. |
| Before | `before` | DateTime | No |  | More options | Received before this date. |
| Session | `session` | Text | No | `mail` | More options | Session name of the open mailbox. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `files` | List | Saved file paths. |
| `email_count` | Number | Number of emails that matched. |

*Time limit 600 s; needs: network, filesystem.*

### Save attachments

`ooa.email/save_attachments` (action)

Save an email's attachments to a folder, optionally only names matching a pattern such as *.pdf.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| ID | `id` | Number | Yes |  | Main | Email id. |
| Target folder | `target_folder` | File path | Yes |  | Main | Folder to save into. |
| Overwrite | `overwrite` | Yes/No | No | No | Main | Replace files of the same name instead of adding a number such as (2). |
| Name pattern | `name_pattern` | Text | No | `*` | More options | Only names matching this pattern, e.g. *.pdf. |
| Folder | `folder` | Text | No | `INBOX` | More options | Mail folder. |
| Include inline | `include_inline` | Yes/No | No | Yes | More options | Also save images embedded in the body, such as signature logos. |
| Session | `session` | Text | No | `mail` | More options | Session name. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `files` | List | Saved file paths. |

*Time limit 60 s; needs: network, filesystem.*

### Save email as .eml

`ooa.email/save_email_as_eml` (action)

Save a complete email, with attachments, as an .eml file that mail programs can open.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| ID | `id` | Number | Yes |  | Main | Email id from Find emails. |
| Folder | `folder` | Text | No | `INBOX` | Main | Mail folder. |
| Local path | `local_path` | File path | Yes |  | Main | Where to save the .eml file; a path ending in / or \ is a folder, and the file is named after the subject. Example: `mail/invoice.eml` |
| Overwrite | `overwrite` | Yes/No | No | Yes | Main | Replace an existing file; when false a number such as (2) is added to the name. |
| Session | `session` | Text | No | `mail` | More options | Session name of the open mailbox. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `path` | File path | Saved local path. |

*Time limit 60 s; needs: network, filesystem.*

## Send

### Forward email

`ooa.email/forward` (action)

Forward an email from the open mailbox with a note, either inline with its attachments or attached as an .eml file.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| ID | `id` | Number | Yes |  | Main | Email id from Find emails. |
| To | `to` | Any | Yes |  | Main | Recipient address(es): text separated by , or ; or a List. |
| Cc | `cc` | Any | No |  | Main | CC address(es). |
| Bcc | `bcc` | Any | No |  | Main | BCC address(es). |
| Reply to | `reply_to` | Any | No |  | Main | Address(es) that replies go to, when not the sender. |
| Attachments | `attachments` | List | No |  | Main | More file paths to attach. |
| Importance | `importance` | Text | No | `normal` | Main | Importance of the forwarded email. One of: `normal`, `high`, `low` |
| Note | `note` | Text | No | (empty) | Main | Text placed above the forwarded email. |
| Body is HTML | `html` | Yes/No | No | No | Main | Note is HTML. |
| Outgoing mail server (SMTP) | `smtp_host` | Text | No |  | Main; tick Send through another server | SMTP server used to send, e.g. smtp.office365.com; when empty, the outgoing server kept by Connect (smtp_host there). Example: `smtp.office365.com` |
| Port | `port` | Number | No | `587` | Main; tick Send through another server | 587 for STARTTLS, 465 for SSL. |
| Security | `security` | Text | No | `starttls` | Main; tick Send through another server | Connection security. One of: `starttls`, `ssl`, `none` |
| User | `user` | Text | No |  | Main; tick Send through another server | Mailbox user name for SMTP sign-in; the session's user when empty. |
| Password | `password` | Credential (vault) | No |  | Main; tick Send through another server | Password or app password from the vault. |
| Access token | `access_token` | Credential (vault) | No |  | Main; tick Send through another server | OAuth 2.0 access token from the vault. |
| As attachment | `as_attachment` | Yes/No | No | No | More options | Attach the original as an .eml file instead of quoting it. |
| From | `from` | Text | No |  | More options | Sender address; the user when empty. |
| Folder | `folder` | Text | No | `INBOX` | More options | Mail folder of the email. |
| Session | `session` | Text | No | `mail` | More options | Session name of the open mailbox. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `message_id` | Text | Message-ID of the sent email. |

*Time limit 120 s; needs: network.*

### Reply to email

`ooa.email/reply` (action)

Reply to the sender of an email in the open mailbox, keeping it in the same conversation and quoting the original.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| ID | `id` | Number | Yes |  | Main | Email id from Find emails. |
| Cc | `cc` | Any | No |  | Main | More CC address(es). |
| Bcc | `bcc` | Any | No |  | Main | BCC address(es). |
| Reply to | `reply_to` | Any | No |  | Main | Address(es) that replies go to, when not the sender. |
| Attachments | `attachments` | List | No |  | Main | File paths to attach. |
| Importance | `importance` | Text | No | `normal` | Main | Importance of the reply. One of: `normal`, `high`, `low` |
| Body | `body` | Text | No | (empty) | Main | Reply text. |
| Body is HTML | `html` | Yes/No | No | No | Main | Body is HTML. |
| Outgoing mail server (SMTP) | `smtp_host` | Text | No |  | Main; tick Send through another server | SMTP server used to send, e.g. smtp.office365.com; when empty, the outgoing server kept by Connect (smtp_host there). Example: `smtp.office365.com` |
| Port | `port` | Number | No | `587` | Main; tick Send through another server | 587 for STARTTLS, 465 for SSL. |
| Security | `security` | Text | No | `starttls` | Main; tick Send through another server | Connection security. One of: `starttls`, `ssl`, `none` |
| User | `user` | Text | No |  | Main; tick Send through another server | Mailbox user name for SMTP sign-in; the session's user when empty. |
| Password | `password` | Credential (vault) | No |  | Main; tick Send through another server | Password or app password from the vault. |
| Access token | `access_token` | Credential (vault) | No |  | Main; tick Send through another server | OAuth 2.0 access token from the vault. |
| Include the original message | `include_original` | Yes/No | No | Yes | More options | Quote the original email below the reply. |
| From | `from` | Text | No |  | More options | Sender address; the user when empty. |
| Folder | `folder` | Text | No | `INBOX` | More options | Mail folder of the email. |
| Session | `session` | Text | No | `mail` | More options | Session name of the open mailbox. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `message_id` | Text | Message-ID of the reply. |

*Time limit 120 s; needs: network.*

### Reply to all

`ooa.email/reply_all` (action)

Reply to the sender and all other recipients of an email, keeping it in the same conversation and quoting the original.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| ID | `id` | Number | Yes |  | Main | Email id from Find emails. |
| Cc | `cc` | Any | No |  | Main | More CC address(es). |
| Bcc | `bcc` | Any | No |  | Main | BCC address(es). |
| Reply to | `reply_to` | Any | No |  | Main | Address(es) that replies go to, when not the sender. |
| Exclude sender | `exclude_sender` | Yes/No | No | No | Main | Leave the original sender out and reply only to the other recipients. |
| Importance | `importance` | Text | No | `normal` | Main | Importance of the reply. One of: `normal`, `high`, `low` |
| Attachments | `attachments` | List | No |  | Main | File paths to attach. |
| Body | `body` | Text | No | (empty) | Main | Reply text. |
| Body is HTML | `html` | Yes/No | No | No | Main | Body is HTML. |
| Outgoing mail server (SMTP) | `smtp_host` | Text | No |  | Main; tick Send through another server | SMTP server used to send, e.g. smtp.office365.com; when empty, the outgoing server kept by Connect (smtp_host there). Example: `smtp.office365.com` |
| Port | `port` | Number | No | `587` | Main; tick Send through another server | 587 for STARTTLS, 465 for SSL. |
| Security | `security` | Text | No | `starttls` | Main; tick Send through another server | Connection security. One of: `starttls`, `ssl`, `none` |
| User | `user` | Text | No |  | Main; tick Send through another server | Mailbox user name for SMTP sign-in; the session's user when empty. |
| Password | `password` | Credential (vault) | No |  | Main; tick Send through another server | Password or app password from the vault. |
| Access token | `access_token` | Credential (vault) | No |  | Main; tick Send through another server | OAuth 2.0 access token from the vault. |
| Include the original message | `include_original` | Yes/No | No | Yes | More options | Quote the original email below the reply. |
| From | `from` | Text | No |  | More options | Sender address; the user when empty; also left out of the recipients. |
| Folder | `folder` | Text | No | `INBOX` | More options | Mail folder of the email. |
| Session | `session` | Text | No | `mail` | More options | Session name of the open mailbox. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `message_id` | Text | Message-ID of the reply. |

*Time limit 120 s; needs: network.*

### Send email

`ooa.email/send` (action)

Send an email (plain text or HTML) with optional CC, BCC and attachments through an SMTP server.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| To | `to` | Any | Yes |  | Main | Recipient address(es): text separated by , or ; or a List. |
| Cc | `cc` | Any | No |  | Main | CC address(es). |
| Bcc | `bcc` | Any | No |  | Main | BCC address(es). |
| Reply to | `reply_to` | Any | No |  | Main | Address(es) that replies go to, when not the sender. |
| Importance | `importance` | Text | No | `normal` | Main | Importance shown by the recipient's mail program. One of: `normal`, `high`, `low` |
| Subject | `subject` | Text | Yes |  | Main | Subject. |
| Attachments | `attachments` | List | No |  | Main | File paths to attach. |
| Body | `body` | Text | No | (empty) | Main | Message body. |
| Body is HTML | `html` | Yes/No | No | No | Main | Body is HTML. |
| Outgoing mail server (SMTP) | `smtp_host` | Text | No |  | Main; tick Send through a specific server | SMTP server used to send, e.g. smtp.office365.com; when empty, the outgoing server kept by Connect (smtp_host there). Example: `smtp.office365.com` |
| Port | `port` | Number | No | `587` | Main; tick Send through a specific server | 587 for STARTTLS, 465 for SSL. |
| Security | `security` | Text | No | `starttls` | Main; tick Send through a specific server | Connection security. One of: `starttls`, `ssl`, `none` |
| User | `user` | Text | No |  | Main; tick Send through a specific server | Mailbox user name; the mailbox session's user when empty. |
| Password | `password` | Credential (vault) | No |  | Main; tick Send through a specific server | Password or app password from the vault; with neither password nor token the server is used without signing in (internal relay). |
| Access token | `access_token` | Credential (vault) | No |  | Main; tick Send through a specific server | OAuth 2.0 access token (Microsoft 365, Gmail) from the vault. |
| From | `from` | Text | No |  | More options | Sender address; the user when empty. |
| Read receipt | `read_receipt` | Yes/No | No | No | More options | Ask the recipient's mail program to confirm when the email is opened. |
| Inline images | `inline_images` | List | No |  | More options | HTML body only: image files embedded in the email; refer to each in the body as cid: plus its file name, e.g. <img src="cid:logo.png">. |
| Session | `session` | Text | No | `mail` | More options | Mailbox session whose outgoing server is used when smtp_host is empty. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `message_id` | Text | Message-ID of the sent email. |

*Time limit 120 s; needs: network.*

## Loop types

Used as the kind of loop in a Loop step.

### Each email in a mailbox

`ooa.email/emails` (loop type)

Yield each email matching the Find emails filters as a dictionary: id, from, subject, received, unread, has_attachments.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Folder | `folder` | Text | No | `INBOX` | Main | Mail folder. |
| Unread only | `unread_only` | Yes/No | No | No | Main | Only unread emails. |
| From contains | `from_contains` | Text | No |  | Main | Sender contains this text; separate several with ; to match any of them. |
| Subject contains | `subject_contains` | Text | No |  | Main | Subject contains this text; separate several with ; to match any of them. |
| Since | `since` | DateTime | No |  | Main | Received on or after this date. |
| Maximum results | `max_results` | Number | No | `100` | Main | Stop after this many emails. Range: 1 to  |
| Before | `before` | DateTime | No |  | Main | Received before this date. |
| Read only | `read_only` | Yes/No | No | No | Main | Only emails already read. |
| To contains | `to_contains` | Text | No |  | Main | A To recipient contains this text. |
| Body contains | `body_contains` | Text | No |  | Main | The body contains this text (searched by the server). |
| Has attachments only | `has_attachments_only` | Yes/No | No | No | Main | Only emails that have attachments. |
| Oldest first | `oldest_first` | Yes/No | No | No | Main | Give the oldest emails first instead of the newest. |
| Session | `session` | Text | No | `mail` | Main | Session name of the open mailbox. |
