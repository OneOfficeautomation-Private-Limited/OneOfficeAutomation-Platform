# Asana

Toolkit `ooa.asana`, version 1.0.0. Manage Asana work: workspaces, projects, sections, tasks and subtasks, followers, comments, attachments, tags and users.

[All toolkits](README.md)

## Contents

- **Comments**: Add comment, Delete comment, Get comment, List comments, Update comment
- **Tasks**: Add followers, Add task to project, Move task to section, Create task, Delete task, Duplicate task, Get task, List tasks, Remove followers, Search tasks, Update task
- **Tags**: Add tag to task, Create tag, List tags, Remove tag from task
- **Projects**: Create project, Create section, Delete project, Get project, List projects, List sections, Update project
- **Subtasks**: Create subtask, List subtasks
- **Teams**: Create team, Get team, List teams, List teams of user, Update team
- **Attachments**: Delete attachment, Download attachment, List attachments, Upload attachment
- **Users**: Get user, List users
- **Workspaces**: List workspaces

## Comments

### Add comment

`ooa.asana/add_comment` (action)

Post a comment on a task, as plain or rich text, optionally pinned to the top.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Token | `token` | Credential (vault) | Yes |  | Main | Personal access token or OAuth token from the vault. |
| Task ID | `task_id` | Text | Yes |  | Main | Task id: the long number at the end of the task's link, e.g. 1208000000000001. |
| Text | `text` | Text | Yes |  | Main | Comment text. Example: `Invoice approved by the finance agent.` |
| Text format | `text_format` | Text | No | `plain` | More options | plain sends the text as it is; html sends Asana rich text, wrapped in <body>...</body>, e.g. <body>Paid <strong>today</strong></body>. One of: `plain`, `html` |
| Pinned | `pinned` | Yes/No | No | No | More options | Pin the comment to the top of the task's activity. |
| Endpoint address | `base_url` | Text | No | `https://app.asana.com/api/1.0` | More options | API address. Keep the default unless your company routes Asana through a proxy. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `comment_id` | Text | Id of the new comment. |

*Time limit 60 s; needs: network.*

### Delete comment

`ooa.asana/delete_comment` (action)

Delete a comment (only the comment's author can).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Token | `token` | Credential (vault) | Yes |  | Main | Personal access token or OAuth token from the vault. |
| Comment ID | `comment_id` | Text | Yes |  | Main | Comment id, from Add comment or List comments. |
| Endpoint address | `base_url` | Text | No | `https://app.asana.com/api/1.0` | More options | API address. Keep the default unless your company routes Asana through a proxy. |

*Time limit 60 s; needs: network.*

### Get comment

`ooa.asana/get_comment` (action)

Read one comment (or other task story) by its id.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Token | `token` | Credential (vault) | Yes |  | Main | Personal access token or OAuth token from the vault. |
| Comment ID | `comment_id` | Text | Yes |  | Main | Comment id, from Add comment or List comments. |
| Endpoint address | `base_url` | Text | No | `https://app.asana.com/api/1.0` | More options | API address. Keep the default unless your company routes Asana through a proxy. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `comment` | Dictionary | gid, type, text, html_text, pinned, author, created_at, task, task_id. |

*Time limit 60 s; safe to repeat; needs: network.*

### List comments

`ooa.asana/list_comments` (action)

Read the comments on a task, oldest first. System messages such as assignment changes are left out.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Token | `token` | Credential (vault) | Yes |  | Main | Personal access token or OAuth token from the vault. |
| Task ID | `task_id` | Text | Yes |  | Main | Task id: the long number at the end of the task's link, e.g. 1208000000000001. |
| Endpoint address | `base_url` | Text | No | `https://app.asana.com/api/1.0` | More options | API address. Keep the default unless your company routes Asana through a proxy. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: gid, text, author, created_at. |
| `count` | Number | Number of comments. |

*Time limit 60 s; safe to repeat; needs: network.*

### Update comment

`ooa.asana/update_comment` (action)

Change the text of a comment (only the comment's author can), or pin or unpin it.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Token | `token` | Credential (vault) | Yes |  | Main | Personal access token or OAuth token from the vault. |
| Comment ID | `comment_id` | Text | Yes |  | Main | Comment id, from Add comment or List comments. |
| Text | `text` | Text | No |  | Main | New comment text; leave empty to keep the text and only pin or unpin. |
| Text format | `text_format` | Text | No | `plain` | More options | plain sends the text as it is; html sends Asana rich text, wrapped in <body>...</body>, e.g. <body>Paid <strong>today</strong></body>. One of: `plain`, `html` |
| Pinned | `pinned` | Yes/No | No |  | More options | true pins the comment to the top of the task, false unpins it; empty leaves it as it is. |
| Endpoint address | `base_url` | Text | No | `https://app.asana.com/api/1.0` | More options | API address. Keep the default unless your company routes Asana through a proxy. |

*Time limit 60 s; needs: network.*

## Tasks

### Add followers

`ooa.asana/add_followers` (action)

Add people as followers so they are notified about the task.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Token | `token` | Credential (vault) | Yes |  | Main | Personal access token or OAuth token from the vault. |
| Task ID | `task_id` | Text | Yes |  | Main | Task id: the long number at the end of the task's link, e.g. 1208000000000001. |
| Followers | `followers` | List | Yes |  | Main | Users to add: ids, emails or me, e.g. ["ana@contoso.com"]. |
| Endpoint address | `base_url` | Text | No | `https://app.asana.com/api/1.0` | More options | API address. Keep the default unless your company routes Asana through a proxy. |

*Time limit 60 s; needs: network.*

### Add task to project

`ooa.asana/add_task_to_project` (action)

Also show a task in another project, optionally in a given section.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Token | `token` | Credential (vault) | Yes |  | Main | Personal access token or OAuth token from the vault. |
| Task ID | `task_id` | Text | Yes |  | Main | Task id: the long number at the end of the task's link, e.g. 1208000000000001. |
| Project ID | `project_id` | Text | Yes |  | Main | Project id: the long number in the project's link, e.g. 1207000000000001. |
| Section ID | `section_id` | Text | No |  | Main | Section of that project to place the task in. |
| Endpoint address | `base_url` | Text | No | `https://app.asana.com/api/1.0` | More options | API address. Keep the default unless your company routes Asana through a proxy. |

*Time limit 60 s; needs: network.*

### Move task to section

`ooa.asana/add_task_to_section` (action)

Place a task in a section of a project it belongs to, e.g. to move it to another board column.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Token | `token` | Credential (vault) | Yes |  | Main | Personal access token or OAuth token from the vault. |
| Task ID | `task_id` | Text | Yes |  | Main | Task id: the long number at the end of the task's link, e.g. 1208000000000001. |
| Section ID | `section_id` | Text | Yes |  | Main | Target section id, from List sections. |
| Endpoint address | `base_url` | Text | No | `https://app.asana.com/api/1.0` | More options | API address. Keep the default unless your company routes Asana through a proxy. |

*Time limit 60 s; needs: network.*

### Create task

`ooa.asana/create_task` (action)

Create a task in a project (optionally in one of its sections), in a workspace, or as a subtask of another task.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Token | `token` | Credential (vault) | Yes |  | Main | Personal access token or OAuth token from the vault. |
| Workspace ID | `workspace_id` | Text | No |  | Main | Workspace, when the task belongs to no project. |
| Name | `name` | Text | Yes |  | Main | Task name. Example: `Approve invoice INV-2041` |
| Project ID | `project_id` | Text | No |  | Main | Project to add the task to. Give this or workspace_id. |
| Assignee | `assignee` | Text | No |  | Main | User id, an email address, or me for the token's own user. Example: `ana@contoso.com` |
| Due on | `due_on` | Text | No |  | Main | Due date as YYYY-MM-DD. Example: `2026-11-15` |
| Notes | `notes` | Text | No |  | Main | Task description. |
| Parent task ID | `parent_task_id` | Text | No |  | More options | Create the task as a subtask of this task; then project_id and workspace_id may stay empty. |
| Completed | `completed` | Yes/No | No | No | More options | Create the task already marked complete, e.g. to record work that is done. |
| Section ID | `section_id` | Text | No |  | More options | Section of the project to place the task in. |
| Tag IDs | `tag_ids` | List | No |  | More options | Tag ids to add. |
| Follower IDs | `follower_ids` | List | No |  | More options | Users to follow the task: ids, emails or me. |
| Custom fields | `custom_fields` | Dictionary | No |  | More options | Custom field id to value, e.g. {"1201234567890": "High"}. For drop-downs use the option id. |
| Endpoint address | `base_url` | Text | No | `https://app.asana.com/api/1.0` | More options | API address. Keep the default unless your company routes Asana through a proxy. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `task_id` | Text | Id of the new task. |
| `url` | Text | Web address of the task. |

*Time limit 60 s; needs: network.*

### Delete task

`ooa.asana/delete_task` (action)

Delete a task. It goes to the trash of the user who owns the token and can be restored from there for a limited time.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Token | `token` | Credential (vault) | Yes |  | Main | Personal access token or OAuth token from the vault. |
| Task ID | `task_id` | Text | Yes |  | Main | Task id: the long number at the end of the task's link, e.g. 1208000000000001. |
| Endpoint address | `base_url` | Text | No | `https://app.asana.com/api/1.0` | More options | API address. Keep the default unless your company routes Asana through a proxy. |

*Time limit 60 s; needs: network.*

### Duplicate task

`ooa.asana/duplicate_task` (action)

Copy a task with a new name, choosing what is copied along (notes, assignee, subtasks, attachments, tags, followers, projects, dates).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Token | `token` | Credential (vault) | Yes |  | Main | Personal access token or OAuth token from the vault. |
| Task ID | `task_id` | Text | Yes |  | Main | Task id: the long number at the end of the task's link, e.g. 1208000000000001. |
| Name | `name` | Text | Yes |  | Main | Name of the copy. |
| Include | `include` | List | No |  | Main | What to copy: any of notes, assignee, subtasks, attachments, tags, followers, projects, dates; notes, assignee and projects when empty. |
| Endpoint address | `base_url` | Text | No | `https://app.asana.com/api/1.0` | More options | API address. Keep the default unless your company routes Asana through a proxy. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `task_id` | Text | Id of the new task (it may take a moment to fill in its subtasks). |

*Time limit 60 s; needs: network.*

### Get task

`ooa.asana/get_task` (action)

Read a task with its assignee, dates, projects, tags and custom fields.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Token | `token` | Credential (vault) | Yes |  | Main | Personal access token or OAuth token from the vault. |
| Task ID | `task_id` | Text | Yes |  | Main | Task id: the long number at the end of the task's link, e.g. 1208000000000001. |
| Endpoint address | `base_url` | Text | No | `https://app.asana.com/api/1.0` | More options | API address. Keep the default unless your company routes Asana through a proxy. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `task` | Dictionary | gid, name, notes, completed, completed_at, assignee, assignee_email, due_on, due_at, start_on, projects, sections, tags, followers, parent, subtasks, custom_fields (name to value), created_at, modified_at, url. |
| `completed` | Yes/No | True when the task is done. |

*Time limit 60 s; safe to repeat; needs: network.*

### List tasks

`ooa.asana/list_tasks` (action)

List the tasks of a project, of a section, or assigned to a user in a workspace.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Token | `token` | Credential (vault) | Yes |  | Main | Personal access token or OAuth token from the vault. |
| Workspace ID | `workspace_id` | Text | No |  | Main | Workspace, used together with assignee. |
| Project ID | `project_id` | Text | No |  | Main | List the tasks of this project. |
| Assignee | `assignee` | Text | No |  | Main | Or list tasks assigned to this user (id, email or me); also needs workspace_id. |
| Modified since | `modified_since` | Text | No |  | Main | Only tasks changed at or after this time (ISO 8601). Example: `2026-10-01T00:00:00Z` |
| Section ID | `section_id` | Text | No |  | More options | Or list the tasks of this section. |
| Only open | `only_open` | Yes/No | No | No | More options | Leave out completed tasks. |
| Maximum results | `max_results` | Number | No | `500` | More options | Most items to return; pages are read until this many are found. |
| Endpoint address | `base_url` | Text | No | `https://app.asana.com/api/1.0` | More options | API address. Keep the default unless your company routes Asana through a proxy. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: gid, name, completed, assignee, due_on, modified_at, url. |
| `count` | Number | Number of tasks returned. |

*Time limit 300 s; safe to repeat; needs: network.*

### Remove followers

`ooa.asana/remove_followers` (action)

Stop people from following a task.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Token | `token` | Credential (vault) | Yes |  | Main | Personal access token or OAuth token from the vault. |
| Task ID | `task_id` | Text | Yes |  | Main | Task id: the long number at the end of the task's link, e.g. 1208000000000001. |
| Followers | `followers` | List | Yes |  | Main | Users to remove: ids, emails or me. |
| Endpoint address | `base_url` | Text | No | `https://app.asana.com/api/1.0` | More options | API address. Keep the default unless your company routes Asana through a proxy. |

*Time limit 60 s; needs: network.*

### Search tasks

`ooa.asana/search_tasks` (action)

Search a workspace for tasks by text, assignee, project or completion (needs a paid Asana plan). Returns up to 100 tasks, most recently changed first.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Token | `token` | Credential (vault) | Yes |  | Main | Personal access token or OAuth token from the vault. |
| Workspace ID | `workspace_id` | Text | Yes |  | Main | Workspace id, from List workspaces. |
| Text | `text` | Text | No |  | Main | Words to look for in task names and descriptions. Example: `invoice` |
| Assignee | `assignee` | Text | No |  | Main | Only tasks assigned to this user (id or me). |
| Project ID | `project_id` | Text | No |  | Main | Only tasks in this project. |
| Completed | `completed` | Yes/No | No |  | Main | true for done tasks only, false for open tasks only; empty for both. |
| Maximum results | `max_results` | Number | No | `100` | More options | Most tasks to return (up to 100). |
| Endpoint address | `base_url` | Text | No | `https://app.asana.com/api/1.0` | More options | API address. Keep the default unless your company routes Asana through a proxy. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: gid, name, completed, assignee, due_on, modified_at, url. |
| `count` | Number | Number of tasks found. |

*Time limit 60 s; safe to repeat; needs: network.*

### Update task

`ooa.asana/update_task` (action)

Change a task's name, notes, assignee, due date, completion, custom fields or parent task. Only the inputs you fill in are changed.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Token | `token` | Credential (vault) | Yes |  | Main | Personal access token or OAuth token from the vault. |
| Task ID | `task_id` | Text | Yes |  | Main | Task id: the long number at the end of the task's link, e.g. 1208000000000001. |
| Name | `name` | Text | No |  | Main | New name. |
| Assignee | `assignee` | Text | No |  | Main | New assignee: user id, email or me; none to unassign. |
| Due on | `due_on` | Text | No |  | Main | New due date as YYYY-MM-DD. |
| Notes | `notes` | Text | No |  | Main | New description (replaces the old one). |
| Completed | `completed` | Yes/No | No |  | Main | true marks the task done, false reopens it. |
| Parent task ID | `parent_task_id` | Text | No |  | More options | Move the task under this parent task, making it a subtask; none makes it a top-level task again. |
| Custom fields | `custom_fields` | Dictionary | No |  | More options | Custom field id to new value. |
| Endpoint address | `base_url` | Text | No | `https://app.asana.com/api/1.0` | More options | API address. Keep the default unless your company routes Asana through a proxy. |

*Time limit 60 s; needs: network.*

## Tags

### Add tag to task

`ooa.asana/add_tag_to_task` (action)

Put a tag on a task.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Token | `token` | Credential (vault) | Yes |  | Main | Personal access token or OAuth token from the vault. |
| Task ID | `task_id` | Text | Yes |  | Main | Task id: the long number at the end of the task's link, e.g. 1208000000000001. |
| Tag ID | `tag_id` | Text | Yes |  | Main | Tag id, from List tags. |
| Endpoint address | `base_url` | Text | No | `https://app.asana.com/api/1.0` | More options | API address. Keep the default unless your company routes Asana through a proxy. |

*Time limit 60 s; needs: network.*

### Create tag

`ooa.asana/create_tag` (action)

Create a tag in a workspace.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Token | `token` | Credential (vault) | Yes |  | Main | Personal access token or OAuth token from the vault. |
| Workspace ID | `workspace_id` | Text | Yes |  | Main | Workspace id, from List workspaces. |
| Name | `name` | Text | Yes |  | Main | Tag name. Example: `needs-review` |
| Endpoint address | `base_url` | Text | No | `https://app.asana.com/api/1.0` | More options | API address. Keep the default unless your company routes Asana through a proxy. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `tag_id` | Text | Id of the new tag. |

*Time limit 60 s; needs: network.*

### List tags

`ooa.asana/list_tags` (action)

List the tags of a workspace.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Token | `token` | Credential (vault) | Yes |  | Main | Personal access token or OAuth token from the vault. |
| Workspace ID | `workspace_id` | Text | Yes |  | Main | Workspace id, from List workspaces. |
| Endpoint address | `base_url` | Text | No | `https://app.asana.com/api/1.0` | More options | API address. Keep the default unless your company routes Asana through a proxy. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: gid, name, color. |
| `count` | Number | Number of tags. |

*Time limit 60 s; safe to repeat; needs: network.*

### Remove tag from task

`ooa.asana/remove_tag_from_task` (action)

Take a tag off a task.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Token | `token` | Credential (vault) | Yes |  | Main | Personal access token or OAuth token from the vault. |
| Task ID | `task_id` | Text | Yes |  | Main | Task id: the long number at the end of the task's link, e.g. 1208000000000001. |
| Tag ID | `tag_id` | Text | Yes |  | Main | Tag id. |
| Endpoint address | `base_url` | Text | No | `https://app.asana.com/api/1.0` | More options | API address. Keep the default unless your company routes Asana through a proxy. |

*Time limit 60 s; needs: network.*

## Projects

### Create project

`ooa.asana/create_project` (action)

Create a project in a workspace. Organization workspaces also need a team.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Token | `token` | Credential (vault) | Yes |  | Main | Personal access token or OAuth token from the vault. |
| Name | `name` | Text | Yes |  | Main | Project name. Example: `Q1 2027 month-end close` |
| Workspace ID | `workspace_id` | Text | Yes |  | Main | Workspace id, from List workspaces. |
| Team ID | `team_id` | Text | No |  | Main | Team id; required in organization workspaces. |
| Notes | `notes` | Text | No |  | Main | Project description. |
| Endpoint address | `base_url` | Text | No | `https://app.asana.com/api/1.0` | More options | API address. Keep the default unless your company routes Asana through a proxy. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `project_id` | Text | Id of the new project. |
| `url` | Text | Web address of the project. |

*Time limit 60 s; needs: network.*

### Create section

`ooa.asana/create_section` (action)

Add a section (board column) to a project.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Token | `token` | Credential (vault) | Yes |  | Main | Personal access token or OAuth token from the vault. |
| Project ID | `project_id` | Text | Yes |  | Main | Project id: the long number in the project's link, e.g. 1207000000000001. |
| Name | `name` | Text | Yes |  | Main | Section name. Example: `Waiting for approval` |
| Endpoint address | `base_url` | Text | No | `https://app.asana.com/api/1.0` | More options | API address. Keep the default unless your company routes Asana through a proxy. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `section_id` | Text | Id of the new section. |

*Time limit 60 s; needs: network.*

### Delete project

`ooa.asana/delete_project` (action)

Delete a project; its tasks stay in other projects they belong to. Needs confirm, as this cannot be undone from the API.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Token | `token` | Credential (vault) | Yes |  | Main | Personal access token or OAuth token from the vault. |
| Project ID | `project_id` | Text | Yes |  | Main | Project id: the long number in the project's link, e.g. 1207000000000001. |
| Confirm | `confirm` | Yes/No | Yes |  | Main | Must be true: a safeguard against deleting by mistake. |
| Endpoint address | `base_url` | Text | No | `https://app.asana.com/api/1.0` | More options | API address. Keep the default unless your company routes Asana through a proxy. |

*Time limit 60 s; needs: network.*

### Get project

`ooa.asana/get_project` (action)

Read a project's details.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Token | `token` | Credential (vault) | Yes |  | Main | Personal access token or OAuth token from the vault. |
| Project ID | `project_id` | Text | Yes |  | Main | Project id: the long number in the project's link, e.g. 1207000000000001. |
| Endpoint address | `base_url` | Text | No | `https://app.asana.com/api/1.0` | More options | API address. Keep the default unless your company routes Asana through a proxy. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `project` | Dictionary | gid, name, notes, archived, owner, team, workspace, due_on, created_at, modified_at, url. |

*Time limit 60 s; safe to repeat; needs: network.*

### List projects

`ooa.asana/list_projects` (action)

List the projects in a workspace, optionally only those of one team.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Token | `token` | Credential (vault) | Yes |  | Main | Personal access token or OAuth token from the vault. |
| Team ID | `team_id` | Text | No |  | Main | Only projects of this team id. |
| Workspace ID | `workspace_id` | Text | Yes |  | Main | Workspace id, from List workspaces. |
| Include archived | `include_archived` | Yes/No | No | No | Main | Also list archived projects. |
| Maximum results | `max_results` | Number | No | `500` | More options | Most items to return; pages are read until this many are found. |
| Endpoint address | `base_url` | Text | No | `https://app.asana.com/api/1.0` | More options | API address. Keep the default unless your company routes Asana through a proxy. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: gid, name, archived, owner, url. |
| `count` | Number | Number of projects returned. |

*Time limit 300 s; safe to repeat; needs: network.*

### List sections

`ooa.asana/list_sections` (action)

List the sections (board columns) of a project.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Token | `token` | Credential (vault) | Yes |  | Main | Personal access token or OAuth token from the vault. |
| Project ID | `project_id` | Text | Yes |  | Main | Project id: the long number in the project's link, e.g. 1207000000000001. |
| Endpoint address | `base_url` | Text | No | `https://app.asana.com/api/1.0` | More options | API address. Keep the default unless your company routes Asana through a proxy. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: gid, name. |
| `count` | Number | Number of sections. |

*Time limit 60 s; safe to repeat; needs: network.*

### Update project

`ooa.asana/update_project` (action)

Change a project's name, notes, colour, owner, due date or archived state; empty inputs are left as they are.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Token | `token` | Credential (vault) | Yes |  | Main | Personal access token or OAuth token from the vault. |
| Project ID | `project_id` | Text | Yes |  | Main | Project id: the long number in the project's link, e.g. 1207000000000001. |
| Name | `name` | Text | No |  | Main | New name. |
| Owner | `owner` | Text | No |  | Main | User id, an email address, or me for the token's own user. |
| Notes | `notes` | Text | No |  | Main | New description. |
| Due on | `due_on` | Text | No |  | Main | Due date, yyyy-MM-dd. Example: `2026-12-31` |
| Archived | `archived` | Yes/No | No |  | Main | Archive (true) or restore (false) the project. |
| Fields | `fields` | Dictionary | No |  | More options | Other project fields by their API name, e.g. {"color": "dark-green"}. |
| Endpoint address | `base_url` | Text | No | `https://app.asana.com/api/1.0` | More options | API address. Keep the default unless your company routes Asana through a proxy. |

*Time limit 60 s; needs: network.*

## Subtasks

### Create subtask

`ooa.asana/create_subtask` (action)

Add a subtask under a task.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Token | `token` | Credential (vault) | Yes |  | Main | Personal access token or OAuth token from the vault. |
| Parent task ID | `parent_task_id` | Text | Yes |  | Main | Id of the parent task. |
| Name | `name` | Text | Yes |  | Main | Subtask name. |
| Assignee | `assignee` | Text | No |  | Main | User id, an email address, or me for the token's own user. |
| Due on | `due_on` | Text | No |  | Main | Due date as YYYY-MM-DD. |
| Notes | `notes` | Text | No |  | Main | Subtask description. |
| Endpoint address | `base_url` | Text | No | `https://app.asana.com/api/1.0` | More options | API address. Keep the default unless your company routes Asana through a proxy. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `task_id` | Text | Id of the new subtask. |
| `url` | Text | Web address of the subtask. |

*Time limit 60 s; needs: network.*

### List subtasks

`ooa.asana/list_subtasks` (action)

List the subtasks of a task.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Token | `token` | Credential (vault) | Yes |  | Main | Personal access token or OAuth token from the vault. |
| Task ID | `task_id` | Text | Yes |  | Main | Id of the parent task. |
| Endpoint address | `base_url` | Text | No | `https://app.asana.com/api/1.0` | More options | API address. Keep the default unless your company routes Asana through a proxy. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: gid, name, completed, assignee, due_on, modified_at, url. |
| `count` | Number | Number of subtasks. |

*Time limit 60 s; safe to repeat; needs: network.*

## Teams

### Create team

`ooa.asana/create_team` (action)

Create a team in an organization workspace.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Token | `token` | Credential (vault) | Yes |  | Main | Personal access token or OAuth token from the vault. |
| Name | `name` | Text | Yes |  | Main | Team name. |
| Team description | `team_description` | Text | No |  | Main | What the team is for. |
| Workspace ID | `workspace_id` | Text | Yes |  | Main | Organization (workspace) id, from List workspaces. |
| Endpoint address | `base_url` | Text | No | `https://app.asana.com/api/1.0` | More options | API address. Keep the default unless your company routes Asana through a proxy. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `team_id` | Text | Id of the new team. |

*Time limit 60 s; needs: network.*

### Get team

`ooa.asana/get_team` (action)

Read a team's name, description, organization and link.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Token | `token` | Credential (vault) | Yes |  | Main | Personal access token or OAuth token from the vault. |
| Team ID | `team_id` | Text | Yes |  | Main | Team id. |
| Endpoint address | `base_url` | Text | No | `https://app.asana.com/api/1.0` | More options | API address. Keep the default unless your company routes Asana through a proxy. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `team` | Dictionary | Keys: gid, name, description, organization, url. |

*Time limit 60 s; safe to repeat; needs: network.*

### List teams

`ooa.asana/list_teams` (action)

List the teams of an organization workspace that the token's user can see.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Token | `token` | Credential (vault) | Yes |  | Main | Personal access token or OAuth token from the vault. |
| Workspace ID | `workspace_id` | Text | Yes |  | Main | Organization (workspace) id, from List workspaces. |
| Maximum results | `max_results` | Number | No | `500` | More options | Most items to return; pages are read until this many are found. Range: 1 to  |
| Endpoint address | `base_url` | Text | No | `https://app.asana.com/api/1.0` | More options | API address. Keep the default unless your company routes Asana through a proxy. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: gid, name, description, url. |
| `count` | Number | Number of teams returned. |

*Time limit 60 s; safe to repeat; needs: network.*

### List teams of user

`ooa.asana/list_user_teams` (action)

List the teams a person belongs to in an organization workspace.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Token | `token` | Credential (vault) | Yes |  | Main | Personal access token or OAuth token from the vault. |
| User | `user` | Text | No | `me` | Main | User id, an email address, or me for the token's own user. |
| Workspace ID | `workspace_id` | Text | Yes |  | Main | Organization (workspace) id, from List workspaces. |
| Maximum results | `max_results` | Number | No | `500` | More options | Most items to return; pages are read until this many are found. Range: 1 to  |
| Endpoint address | `base_url` | Text | No | `https://app.asana.com/api/1.0` | More options | API address. Keep the default unless your company routes Asana through a proxy. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: gid, name, description, url. |
| `count` | Number | Number of teams returned. |

*Time limit 60 s; safe to repeat; needs: network.*

### Update team

`ooa.asana/update_team` (action)

Rename a team or change its description.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Token | `token` | Credential (vault) | Yes |  | Main | Personal access token or OAuth token from the vault. |
| Team ID | `team_id` | Text | Yes |  | Main | Team id. |
| Name | `name` | Text | No |  | Main | New name. |
| Team description | `team_description` | Text | No |  | Main | New description. |
| Endpoint address | `base_url` | Text | No | `https://app.asana.com/api/1.0` | More options | API address. Keep the default unless your company routes Asana through a proxy. |

*Time limit 60 s; needs: network.*

## Attachments

### Delete attachment

`ooa.asana/delete_attachment` (action)

Remove an attachment from its task.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Token | `token` | Credential (vault) | Yes |  | Main | Personal access token or OAuth token from the vault. |
| Attachment ID | `attachment_id` | Text | Yes |  | Main | Attachment id. |
| Endpoint address | `base_url` | Text | No | `https://app.asana.com/api/1.0` | More options | API address. Keep the default unless your company routes Asana through a proxy. |

*Time limit 60 s; needs: network.*

### Download attachment

`ooa.asana/download_attachment` (action)

Save an attachment of a task to a local folder.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Token | `token` | Credential (vault) | Yes |  | Main | Personal access token or OAuth token from the vault. |
| Attachment ID | `attachment_id` | Text | Yes |  | Main | Attachment id, from Upload attachment or the task's attachments. |
| Folder | `folder` | File path | No | `.` | Main | Folder to save into. |
| Overwrite | `overwrite` | Yes/No | No | Yes | More options | Replace a file of the same name; otherwise a number is added. |
| Endpoint address | `base_url` | Text | No | `https://app.asana.com/api/1.0` | More options | API address. Keep the default unless your company routes Asana through a proxy. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `path` | File path | Saved file. |

*Time limit 600 s; needs: network, filesystem.*

### List attachments

`ooa.asana/list_attachments` (action)

List the files attached to a task, with their size and download link.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Token | `token` | Credential (vault) | Yes |  | Main | Personal access token or OAuth token from the vault. |
| Task ID | `task_id` | Text | Yes |  | Main | Task id: the long number at the end of the task's link, e.g. 1208000000000001. |
| Maximum results | `max_results` | Number | No | `100` | More options | Most items to return; pages are read until this many are found. Range: 1 to  |
| Endpoint address | `base_url` | Text | No | `https://app.asana.com/api/1.0` | More options | API address. Keep the default unless your company routes Asana through a proxy. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: gid, name, size (bytes), host, created_at, download_url, view_url. The download link is temporary; use Download attachment to save a file. |
| `count` | Number | Number of attachments returned. |

*Time limit 300 s; safe to repeat; needs: network.*

### Upload attachment

`ooa.asana/upload_attachment` (action)

Attach a local file to a task.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Token | `token` | Credential (vault) | Yes |  | Main | Personal access token or OAuth token from the vault. |
| Task ID | `task_id` | Text | Yes |  | Main | Task id: the long number at the end of the task's link, e.g. 1208000000000001. |
| Path | `path` | File path | Yes |  | Main | Local file to attach (Asana accepts up to 100 MB). Example: `out/INV-2041.pdf` |
| File name | `file_name` | Text | No |  | More options | Name to give the attachment; the local file name when empty. |
| Endpoint address | `base_url` | Text | No | `https://app.asana.com/api/1.0` | More options | API address. Keep the default unless your company routes Asana through a proxy. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `attachment_id` | Text | Id of the attachment. |
| `url` | Text | Address to view the attachment. |

*Time limit 600 s; needs: network, filesystem.*

## Users

### Get user

`ooa.asana/get_user` (action)

Read a person's name, email and workspaces; use me to check which account the token belongs to.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Token | `token` | Credential (vault) | Yes |  | Main | Personal access token or OAuth token from the vault. |
| User | `user` | Text | No | `me` | Main | User id, an email address, or me for the token's own user. |
| Endpoint address | `base_url` | Text | No | `https://app.asana.com/api/1.0` | More options | API address. Keep the default unless your company routes Asana through a proxy. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `user` | Dictionary | gid, name, email, workspaces (names). |
| `user_id` | Text | Numeric user id. |

*Time limit 60 s; safe to repeat; needs: network.*

### List users

`ooa.asana/list_users` (action)

List the people in a workspace.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Token | `token` | Credential (vault) | Yes |  | Main | Personal access token or OAuth token from the vault. |
| Workspace ID | `workspace_id` | Text | Yes |  | Main | Workspace id, from List workspaces. |
| Maximum results | `max_results` | Number | No | `2000` | More options | Most items to return; pages are read until this many are found. |
| Endpoint address | `base_url` | Text | No | `https://app.asana.com/api/1.0` | More options | API address. Keep the default unless your company routes Asana through a proxy. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: gid, name, email. |
| `count` | Number | Number of users returned. |

*Time limit 300 s; safe to repeat; needs: network.*

## Workspaces

### List workspaces

`ooa.asana/list_workspaces` (action)

List the workspaces and organizations the token can see.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Token | `token` | Credential (vault) | Yes |  | Main | Personal access token or OAuth token from the vault. |
| Endpoint address | `base_url` | Text | No | `https://app.asana.com/api/1.0` | More options | API address. Keep the default unless your company routes Asana through a proxy. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: gid, name, is_organization. |
| `count` | Number | Number of workspaces. |

*Time limit 60 s; safe to repeat; needs: network.*
