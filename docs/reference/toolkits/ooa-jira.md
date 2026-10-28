# Jira

Toolkit `ooa.jira`, version 1.0.0. Create, read and search Jira issues, add comments, move issues through the workflow and attach files.

[All toolkits](README.md)

## Contents

- **Issues**: Add comment, Attach file, Create issue, Delete issue, Get issue, Search issues (JQL), Move issue (transition), Update issue
- **Projects**: Create project, Delete project, Get projects, Update project
- **Comments**: Delete comment, Get comments, Update comment

## Issues

### Add comment

`ooa.jira/add_comment` (action)

Add a comment to an issue, optionally visible only to a project role or group.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Site address (URL) | `site_url` | Text | Yes |  | Main | Site URL, e.g. https://yourcompany.atlassian.net. |
| Email | `email` | Text | No |  | Main; choice Sign in with = Cloud email and API token | Account email (Cloud). |
| API token | `api_token` | Credential (vault) | No |  | Main; choice Sign in with = Cloud email and API token | API token from the vault (Cloud). |
| Personal access token | `personal_access_token` | Credential (vault) | No |  | Main; choice Sign in with = Data Center access token | Personal access token (Data Center). |
| Key | `key` | Text | Yes |  | Main | Issue key. |
| Text | `text` | Text | Yes |  | Main | Comment text. |
| Visible to role | `visible_to_role` | Text | No |  | More options; choice Who can see it = A project role | Only members of this project role can see the comment. Example: `Administrators` |
| Visible to group | `visible_to_group` | Text | No |  | More options; choice Who can see it = A group | Only members of this group can see the comment (instead of a role). Example: `jira-servicedesk-users` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `comment_id` | Text | Id of the new comment. |
| `author` | Text | Display name of the author. |
| `created` | Text | When it was created (ISO 8601). |

*Time limit 60 s; needs: network.*

### Attach file

`ooa.jira/attach_file` (action)

Attach a local file to an issue.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Site address (URL) | `site_url` | Text | Yes |  | Main | Site URL, e.g. https://yourcompany.atlassian.net. |
| Email | `email` | Text | No |  | Main; choice Sign in with = Cloud email and API token | Account email (Cloud). |
| API token | `api_token` | Credential (vault) | No |  | Main; choice Sign in with = Cloud email and API token | API token from the vault (Cloud). |
| Personal access token | `personal_access_token` | Credential (vault) | No |  | Main; choice Sign in with = Data Center access token | Personal access token (Data Center). |
| Key | `key` | Text | Yes |  | Main | Issue key. |
| Path | `path` | File path | Yes |  | Main | File to attach. |

*Time limit 600 s; needs: network, filesystem.*

### Create issue

`ooa.jira/create_issue` (action)

Create an issue and return its key.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Site address (URL) | `site_url` | Text | Yes |  | Main | Site URL, e.g. https://yourcompany.atlassian.net. |
| Email | `email` | Text | No |  | Main; choice Sign in with = Cloud email and API token | Account email (Cloud). |
| API token | `api_token` | Credential (vault) | No |  | Main; choice Sign in with = Cloud email and API token | API token from the vault (Cloud). |
| Personal access token | `personal_access_token` | Credential (vault) | No |  | Main; choice Sign in with = Data Center access token | Personal access token (Data Center). |
| Project | `project` | Text | Yes |  | Main | Project key. Example: `OPS` |
| Issue type | `issue_type` | Text | No | `Task` | Main | Issue type name, e.g. Task, Bug, Story. |
| Parent key | `parent_key` | Text | No |  | Main | Parent issue key, for a sub-task. Example: `OPS-12` |
| Summary | `summary` | Text | Yes |  | Main | Summary. |
| Description | `description` | Text | No |  | Main | Description. |
| Priority | `priority` | Text | No |  | Main | Priority name, e.g. Highest, High, Medium, Low, Lowest. Example: `High` |
| Labels | `labels` | List | No |  | Main | Labels, e.g. ["invoice", "urgent"]. |
| Components | `components` | List | No |  | Main | Names of existing project components. |
| Extra fields | `extra_fields` | Dictionary | No |  | Main | More fields, e.g. {"priority": {"name": "High"}, "customfield_10010": "Gold"}. |
| Assignee | `assignee` | Text | No |  | More options | Assignee: an email address (looked up), a Cloud account id, or a Data Center user name; the project default when empty. |
| Due date | `due_date` | Text | No |  | More options | Due date, yyyy-MM-dd. Example: `2026-12-31` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `key` | Text | Issue key, e.g. OPS-123. |
| `id` | Text | Issue id. |
| `url` | Text | Link to the issue in the browser. |

*Time limit 60 s; needs: network.*

### Delete issue

`ooa.jira/delete_issue` (action)

Permanently delete an issue, optionally with its sub-tasks.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Site address (URL) | `site_url` | Text | Yes |  | Main | Site URL, e.g. https://yourcompany.atlassian.net. |
| Email | `email` | Text | No |  | Main; choice Sign in with = Cloud email and API token | Account email (Cloud). |
| API token | `api_token` | Credential (vault) | No |  | Main; choice Sign in with = Cloud email and API token | API token from the vault (Cloud). |
| Personal access token | `personal_access_token` | Credential (vault) | No |  | Main; choice Sign in with = Data Center access token | Personal access token (Data Center). |
| Key | `key` | Text | Yes |  | Main | Issue key. |
| Delete subtasks | `delete_subtasks` | Yes/No | No | No | Main | Also delete its sub-tasks (required when it has any). |

*Time limit 60 s; needs: network.*

### Get issue

`ooa.jira/get_issue` (action)

Read an issue's main fields, and optionally more fields by name.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Site address (URL) | `site_url` | Text | Yes |  | Main | Site URL, e.g. https://yourcompany.atlassian.net. |
| Email | `email` | Text | No |  | Main; choice Sign in with = Cloud email and API token | Account email (Cloud). |
| API token | `api_token` | Credential (vault) | No |  | Main; choice Sign in with = Cloud email and API token | API token from the vault (Cloud). |
| Personal access token | `personal_access_token` | Credential (vault) | No |  | Main; choice Sign in with = Data Center access token | Personal access token (Data Center). |
| Key | `key` | Text | Yes |  | Main | Issue key. |
| Extra fields | `extra_fields` | List | No |  | More options | More fields to return in issue, e.g. ["labels", "duedate", "customfield_10010"]. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `issue` | Dictionary | key, summary, status, assignee, reporter, priority, created, updated, description, plus the extra fields asked for. |
| `raw` | Dictionary | The full issue as Jira returned it. |

*Time limit 60 s; safe to repeat; needs: network.*

### Search issues (JQL)

`ooa.jira/search` (action)

Find issues with a JQL query.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Site address (URL) | `site_url` | Text | Yes |  | Main | Site URL, e.g. https://yourcompany.atlassian.net. |
| Email | `email` | Text | No |  | Main; choice Sign in with = Cloud email and API token | Account email (Cloud). |
| API token | `api_token` | Credential (vault) | No |  | Main; choice Sign in with = Cloud email and API token | API token from the vault (Cloud). |
| Personal access token | `personal_access_token` | Credential (vault) | No |  | Main; choice Sign in with = Data Center access token | Personal access token (Data Center). |
| JQL | `jql` | Text | Yes |  | Main | JQL query. Example: `project = OPS AND status = "To Do" ORDER BY created DESC` |
| Maximum results | `max_results` | Number | No | `100` | Main | Maximum issues. |
| Extra fields | `extra_fields` | List | No |  | More options | More fields to add as columns, e.g. ["labels", "duedate", "reporter"]. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: key, summary, status, assignee, priority, updated. |
| `count` | Number | Number of issues returned. |

*Time limit 300 s; safe to repeat; needs: network.*

### Move issue (transition)

`ooa.jira/transition` (action)

Move an issue through its workflow by transition name, e.g. In Progress or Done, optionally with a comment and field values.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Site address (URL) | `site_url` | Text | Yes |  | Main | Site URL, e.g. https://yourcompany.atlassian.net. |
| Email | `email` | Text | No |  | Main; choice Sign in with = Cloud email and API token | Account email (Cloud). |
| API token | `api_token` | Credential (vault) | No |  | Main; choice Sign in with = Cloud email and API token | API token from the vault (Cloud). |
| Personal access token | `personal_access_token` | Credential (vault) | No |  | Main; choice Sign in with = Data Center access token | Personal access token (Data Center). |
| Key | `key` | Text | Yes |  | Main | Issue key. |
| Transition | `transition` | Text | Yes |  | Main | Transition or target status name. Example: `Done` |
| Comment | `comment` | Text | No |  | Main | Comment to add with the move. |
| Fields | `fields` | Dictionary | No |  | More options | Fields the transition screen asks for, e.g. {"resolution": {"name": "Fixed"}}. |

*Time limit 60 s; needs: network.*

### Update issue

`ooa.jira/update_issue` (action)

Change fields of an issue, such as summary, priority, labels or assignee.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Site address (URL) | `site_url` | Text | Yes |  | Main | Site URL, e.g. https://yourcompany.atlassian.net. |
| Email | `email` | Text | No |  | Main; choice Sign in with = Cloud email and API token | Account email (Cloud). |
| API token | `api_token` | Credential (vault) | No |  | Main; choice Sign in with = Cloud email and API token | API token from the vault (Cloud). |
| Personal access token | `personal_access_token` | Credential (vault) | No |  | Main; choice Sign in with = Data Center access token | Personal access token (Data Center). |
| Key | `key` | Text | Yes |  | Main | Issue key. |
| Summary | `summary` | Text | No |  | Main | New summary; unchanged when empty. |
| Description | `description` | Text | No |  | Main | New description; unchanged when empty. |
| Priority | `priority` | Text | No |  | Main | New priority name; unchanged when empty. |
| Labels | `labels` | List | No |  | Main | Replace the labels with these; unchanged when empty. |
| Fields | `fields` | Dictionary | No |  | Main | Field name to new value, e.g. {"summary": "Disk full on db01", "priority": {"name": "High"}}. |
| Assignee | `assignee` | Text | No |  | More options | New assignee: an email address (looked up), a Cloud account id or a Data Center user name; unchanged when empty. |
| Due date | `due_date` | Text | No |  | More options | New due date, yyyy-MM-dd; unchanged when empty. |
| Notify users | `notify_users` | Yes/No | No | Yes | More options | Send the usual change notifications to watchers. |

*Time limit 60 s; safe to repeat; needs: network.*

## Projects

### Create project

`ooa.jira/create_project` (action)

Create a project with a key, name, type and project lead.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Site address (URL) | `site_url` | Text | Yes |  | Main | Site URL, e.g. https://yourcompany.atlassian.net. |
| Email | `email` | Text | No |  | Main; choice Sign in with = Cloud email and API token | Account email (Cloud). |
| API token | `api_token` | Credential (vault) | No |  | Main; choice Sign in with = Cloud email and API token | API token from the vault (Cloud). |
| Personal access token | `personal_access_token` | Credential (vault) | No |  | Main; choice Sign in with = Data Center access token | Personal access token (Data Center). |
| Project key | `project_key` | Text | Yes |  | Main | New project key (upper-case letters and digits). Example: `OPS` |
| Project name | `project_name` | Text | Yes |  | Main | Project name. |
| Project type | `project_type` | Text | No | `software` | Main | Project type key. One of: `software`, `business`, `service_desk` |
| Template key | `template_key` | Text | No |  | Main | Project template key; the site default when empty. |
| Project description | `project_description` | Text | No |  | Main | Project description. |
| Lead account ID | `lead_account_id` | Text | No |  | Main | Project lead's account id (Cloud). |
| Lead user name | `lead_user_name` | Text | No |  | More options | Project lead's user name (Data Center). |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `id` | Text | Project id. |
| `key` | Text | Project key. |

*Time limit 60 s; needs: network.*

### Delete project

`ooa.jira/delete_project` (action)

Delete a project and the issues in it (Cloud keeps it in the trash for a while).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Site address (URL) | `site_url` | Text | Yes |  | Main | Site URL, e.g. https://yourcompany.atlassian.net. |
| Email | `email` | Text | No |  | Main; choice Sign in with = Cloud email and API token | Account email (Cloud). |
| API token | `api_token` | Credential (vault) | No |  | Main; choice Sign in with = Cloud email and API token | API token from the vault (Cloud). |
| Personal access token | `personal_access_token` | Credential (vault) | No |  | Main; choice Sign in with = Data Center access token | Personal access token (Data Center). |
| Project key | `project_key` | Text | Yes |  | Main | Project key. |

*Time limit 60 s; needs: network.*

### Get projects

`ooa.jira/get_projects` (action)

List the projects the account can see as a table.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Site address (URL) | `site_url` | Text | Yes |  | Main | Site URL, e.g. https://yourcompany.atlassian.net. |
| Email | `email` | Text | No |  | Main; choice Sign in with = Cloud email and API token | Account email (Cloud). |
| API token | `api_token` | Credential (vault) | No |  | Main; choice Sign in with = Cloud email and API token | API token from the vault (Cloud). |
| Personal access token | `personal_access_token` | Credential (vault) | No |  | Main; choice Sign in with = Data Center access token | Personal access token (Data Center). |
| Query | `query` | Text | No |  | Main | Only projects whose key or name contains this text. |
| Project type | `project_type` | Text | No |  | Main | Only projects of this type. One of: `software`, `business`, `service_desk` |
| Maximum results | `max_results` | Number | No | `1000` | Main | Maximum projects. |
| Start at | `start_at` | Number | No | `0` | Main | Skip this many projects first (for paging). |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: id, key, name, project_type, lead. |
| `count` | Number | Number of projects. |

*Time limit 300 s; safe to repeat; needs: network.*

### Update project

`ooa.jira/update_project` (action)

Change project details such as its name, lead, description, default assignee or category.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Site address (URL) | `site_url` | Text | Yes |  | Main | Site URL, e.g. https://yourcompany.atlassian.net. |
| Email | `email` | Text | No |  | Main; choice Sign in with = Cloud email and API token | Account email (Cloud). |
| API token | `api_token` | Credential (vault) | No |  | Main; choice Sign in with = Cloud email and API token | API token from the vault (Cloud). |
| Personal access token | `personal_access_token` | Credential (vault) | No |  | Main; choice Sign in with = Data Center access token | Personal access token (Data Center). |
| Project key | `project_key` | Text | Yes |  | Main | Project key. |
| Project name | `project_name` | Text | No |  | Main | New name; unchanged when empty. |
| Project description | `project_description` | Text | No |  | Main | New description; unchanged when empty. |
| Assignee type | `assignee_type` | Text | No |  | Main | Who new issues go to by default. One of: `PROJECT_LEAD`, `UNASSIGNED` |
| Category ID | `category_id` | Text | No |  | Main | Id of the project category. |
| Fields | `fields` | Dictionary | No |  | Main | Project field to new value, e.g. {"name": "Operations", "description": "..."}. |
| Lead | `lead` | Text | No |  | More options | New lead: an email address (looked up), a Cloud account id or a Data Center user name. |

*Time limit 60 s; safe to repeat; needs: network.*

## Comments

### Delete comment

`ooa.jira/delete_comment` (action)

Remove a comment from an issue.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Site address (URL) | `site_url` | Text | Yes |  | Main | Site URL, e.g. https://yourcompany.atlassian.net. |
| Email | `email` | Text | No |  | Main; choice Sign in with = Cloud email and API token | Account email (Cloud). |
| API token | `api_token` | Credential (vault) | No |  | Main; choice Sign in with = Cloud email and API token | API token from the vault (Cloud). |
| Personal access token | `personal_access_token` | Credential (vault) | No |  | Main; choice Sign in with = Data Center access token | Personal access token (Data Center). |
| Key | `key` | Text | Yes |  | Main | Issue key. |
| Comment ID | `comment_id` | Text | Yes |  | Main | Comment id, from Get comments. |

*Time limit 60 s; needs: network.*

### Get comments

`ooa.jira/get_comments` (action)

List the comments on an issue, oldest first, as a table.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Site address (URL) | `site_url` | Text | Yes |  | Main | Site URL, e.g. https://yourcompany.atlassian.net. |
| Email | `email` | Text | No |  | Main; choice Sign in with = Cloud email and API token | Account email (Cloud). |
| API token | `api_token` | Credential (vault) | No |  | Main; choice Sign in with = Cloud email and API token | API token from the vault (Cloud). |
| Personal access token | `personal_access_token` | Credential (vault) | No |  | Main; choice Sign in with = Data Center access token | Personal access token (Data Center). |
| Key | `key` | Text | Yes |  | Main | Issue key. |
| Maximum results | `max_results` | Number | No | `500` | Main | Maximum comments to read. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: id, author, created, updated, body. |
| `count` | Number | Number of comments returned. |

*Time limit 300 s; safe to repeat; needs: network.*

### Update comment

`ooa.jira/update_comment` (action)

Replace the text of an existing comment.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Site address (URL) | `site_url` | Text | Yes |  | Main | Site URL, e.g. https://yourcompany.atlassian.net. |
| Email | `email` | Text | No |  | Main; choice Sign in with = Cloud email and API token | Account email (Cloud). |
| API token | `api_token` | Credential (vault) | No |  | Main; choice Sign in with = Cloud email and API token | API token from the vault (Cloud). |
| Personal access token | `personal_access_token` | Credential (vault) | No |  | Main; choice Sign in with = Data Center access token | Personal access token (Data Center). |
| Key | `key` | Text | Yes |  | Main | Issue key. |
| Comment ID | `comment_id` | Text | Yes |  | Main | Comment id, from Get comments. |
| Text | `text` | Text | Yes |  | Main | New comment text. |

*Time limit 60 s; safe to repeat; needs: network.*
