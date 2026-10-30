# GitHub

Toolkit `ooa.github`, version 1.0.0. Work with GitHub repositories: issues, pull requests, branches, files, releases and Actions workflows. Works with github.com and GitHub Enterprise Server.

[All toolkits](README.md)

## Contents

- **Issues**: Comment on issue or pull request, Close issue, Create issue, Get issue, List issues, Update issue
- **Branches**: Create branch, List branches
- **Pull requests**: Create pull request, Get pull request, List pull requests, Merge pull request
- **Releases**: Create release, Get latest release
- **Files**: Get file contents, Create or update file
- **Repositories**: Get repository, List repositories
- **Workflows**: Get workflow run, List workflow runs, Run workflow
- **Conditions**: Pull request is merged

## Issues

### Comment on issue or pull request

`ooa.github/add_issue_comment` (action)

Add a comment to an issue or a pull request conversation.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Token | `token` | Credential (vault) | Yes |  | Main | Personal access token or app token from the vault, with access to the repository. |
| Owner | `owner` | Text | Yes |  | Main | User or organization that owns the repository, e.g. acme-corp. |
| Repo | `repo` | Text | Yes |  | Main | Repository name without the owner, e.g. billing-service. |
| Number | `number` | Number | Yes |  | Main | Issue or pull request number. |
| Text | `text` | Text | Yes |  | Main | Comment text in Markdown. Example: `Fixed by the nightly agent run.` |
| Endpoint address | `base_url` | Text | No | `https://api.github.com` | More options | API address. Keep the default for github.com; for GitHub Enterprise Server use https://github.yourcompany.com/api/v3. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `comment_id` | Number | Id of the new comment. |
| `url` | Text | Web address of the comment. |

*Time limit 60 s; needs: network.*

### Close issue

`ooa.github/close_issue` (action)

Close an issue as completed or as not planned.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Token | `token` | Credential (vault) | Yes |  | Main | Personal access token or app token from the vault, with access to the repository. |
| Owner | `owner` | Text | Yes |  | Main | User or organization that owns the repository, e.g. acme-corp. |
| Repo | `repo` | Text | Yes |  | Main | Repository name without the owner, e.g. billing-service. |
| Number | `number` | Number | Yes |  | Main | Issue number. |
| Reason | `reason` | Text | No | `completed` | Main | Why it is closed: completed or not_planned. One of: `completed`, `not_planned` |
| Endpoint address | `base_url` | Text | No | `https://api.github.com` | More options | API address. Keep the default for github.com; for GitHub Enterprise Server use https://github.yourcompany.com/api/v3. |

*Time limit 60 s; needs: network.*

### Create issue

`ooa.github/create_issue` (action)

Open a new issue, optionally with labels and assignees.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Token | `token` | Credential (vault) | Yes |  | Main | Personal access token or app token from the vault, with access to the repository. |
| Owner | `owner` | Text | Yes |  | Main | User or organization that owns the repository, e.g. acme-corp. |
| Repo | `repo` | Text | Yes |  | Main | Repository name without the owner, e.g. billing-service. |
| Title | `title` | Text | Yes |  | Main | Short title of the issue. Example: `Invoice upload fails for PDF over 10 MB` |
| Body | `body` | Text | No |  | Main | Details in Markdown. |
| Labels | `labels` | List | No |  | Main | Label names, e.g. ["bug", "finance"]. A comma-separated text also works. |
| Assignees | `assignees` | List | No |  | Main | GitHub user names to assign, e.g. ["octo-dev"]. |
| Milestone | `milestone` | Number | No |  | More options | Milestone number to put the issue in. |
| Endpoint address | `base_url` | Text | No | `https://api.github.com` | More options | API address. Keep the default for github.com; for GitHub Enterprise Server use https://github.yourcompany.com/api/v3. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `number` | Number | Issue number, e.g. 42. |
| `url` | Text | Web address of the issue. |

*Time limit 60 s; needs: network.*

### Get issue

`ooa.github/get_issue` (action)

Read an issue by its number.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Token | `token` | Credential (vault) | Yes |  | Main | Personal access token or app token from the vault, with access to the repository. |
| Owner | `owner` | Text | Yes |  | Main | User or organization that owns the repository, e.g. acme-corp. |
| Repo | `repo` | Text | Yes |  | Main | Repository name without the owner, e.g. billing-service. |
| Number | `number` | Number | Yes |  | Main | Issue number. Example: `42` |
| Endpoint address | `base_url` | Text | No | `https://api.github.com` | More options | API address. Keep the default for github.com; for GitHub Enterprise Server use https://github.yourcompany.com/api/v3. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `issue` | Dictionary | number, title, state, state_reason, body, author, labels, assignees, milestone, comments, created_at, updated_at, closed_at, is_pull_request, url. |
| `state` | Text | open or closed. |

*Time limit 60 s; safe to repeat; needs: network.*

### List issues

`ooa.github/list_issues` (action)

List a repository's issues, filtered by state, labels or assignee. Pull requests are left out.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Token | `token` | Credential (vault) | Yes |  | Main | Personal access token or app token from the vault, with access to the repository. |
| Owner | `owner` | Text | Yes |  | Main | User or organization that owns the repository, e.g. acme-corp. |
| Repo | `repo` | Text | Yes |  | Main | Repository name without the owner, e.g. billing-service. |
| State | `state` | Text | No | `open` | Main | open, closed or all. One of: `open`, `closed`, `all` |
| Labels | `labels` | Text | No |  | Main | Only issues having all these labels (comma-separated). Example: `bug,finance` |
| Assignee | `assignee` | Text | No |  | Main | Only issues assigned to this user name; * for any, none for unassigned. |
| Since | `since` | Text | No |  | More options | Only issues updated at or after this time (ISO 8601). Example: `2026-10-01T00:00:00Z` |
| Maximum results | `max_results` | Number | No | `100` | More options | Most items to return; pages are read until this many are found. |
| Endpoint address | `base_url` | Text | No | `https://api.github.com` | More options | API address. Keep the default for github.com; for GitHub Enterprise Server use https://github.yourcompany.com/api/v3. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: number, title, state, author, comments, created_at, updated_at, url. |
| `count` | Number | Number of issues returned. |

*Time limit 300 s; safe to repeat; needs: network.*

### Update issue

`ooa.github/update_issue` (action)

Change an issue's title, text, state, labels or assignees. Only the inputs you fill in are changed.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Token | `token` | Credential (vault) | Yes |  | Main | Personal access token or app token from the vault, with access to the repository. |
| Owner | `owner` | Text | Yes |  | Main | User or organization that owns the repository, e.g. acme-corp. |
| Repo | `repo` | Text | Yes |  | Main | Repository name without the owner, e.g. billing-service. |
| Number | `number` | Number | Yes |  | Main | Issue number. |
| State | `state` | Text | No |  | Main | open or closed. One of: `open`, `closed` |
| Title | `title` | Text | No |  | Main | New title. |
| Body | `body` | Text | No |  | Main | New text in Markdown (replaces the old text). |
| Labels | `labels` | List | No |  | Main | Replace all labels with these, e.g. ["bug"]. |
| Assignees | `assignees` | List | No |  | Main | Replace all assignees with these user names. |
| Endpoint address | `base_url` | Text | No | `https://api.github.com` | More options | API address. Keep the default for github.com; for GitHub Enterprise Server use https://github.yourcompany.com/api/v3. |

*Time limit 60 s; needs: network.*

## Branches

### Create branch

`ooa.github/create_branch` (action)

Create a branch starting from another branch (the default branch when none is given).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Token | `token` | Credential (vault) | Yes |  | Main | Personal access token or app token from the vault, with access to the repository. |
| Owner | `owner` | Text | Yes |  | Main | User or organization that owns the repository, e.g. acme-corp. |
| Repo | `repo` | Text | Yes |  | Main | Repository name without the owner, e.g. billing-service. |
| Branch | `branch` | Text | Yes |  | Main | Name of the new branch. Example: `agent/update-rates` |
| From branch | `from_branch` | Text | No |  | Main | Branch to start from. Empty means the default branch. Example: `main` |
| Endpoint address | `base_url` | Text | No | `https://api.github.com` | More options | API address. Keep the default for github.com; for GitHub Enterprise Server use https://github.yourcompany.com/api/v3. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `sha` | Text | Commit id the new branch points to. |

*Time limit 60 s; needs: network.*

### List branches

`ooa.github/list_branches` (action)

List a repository's branches.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Token | `token` | Credential (vault) | Yes |  | Main | Personal access token or app token from the vault, with access to the repository. |
| Owner | `owner` | Text | Yes |  | Main | User or organization that owns the repository, e.g. acme-corp. |
| Repo | `repo` | Text | Yes |  | Main | Repository name without the owner, e.g. billing-service. |
| Maximum results | `max_results` | Number | No | `100` | More options | Most items to return; pages are read until this many are found. |
| Endpoint address | `base_url` | Text | No | `https://api.github.com` | More options | API address. Keep the default for github.com; for GitHub Enterprise Server use https://github.yourcompany.com/api/v3. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: name, sha, protected. |
| `count` | Number | Number of branches returned. |

*Time limit 300 s; safe to repeat; needs: network.*

## Pull requests

### Create pull request

`ooa.github/create_pull_request` (action)

Propose merging one branch into another.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Token | `token` | Credential (vault) | Yes |  | Main | Personal access token or app token from the vault, with access to the repository. |
| Owner | `owner` | Text | Yes |  | Main | User or organization that owns the repository, e.g. acme-corp. |
| Repo | `repo` | Text | Yes |  | Main | Repository name without the owner, e.g. billing-service. |
| Title | `title` | Text | Yes |  | Main | Title of the pull request. |
| Body | `body` | Text | No |  | Main | Description in Markdown. |
| Source branch | `source_branch` | Text | Yes |  | Main | Branch with the changes. For a fork use owner:branch. Example: `feature/rates-2027` |
| Target branch | `target_branch` | Text | Yes |  | Main | Branch to merge into. Example: `main` |
| Draft | `draft` | Yes/No | No | No | More options | Open as a draft that cannot be merged yet. |
| Endpoint address | `base_url` | Text | No | `https://api.github.com` | More options | API address. Keep the default for github.com; for GitHub Enterprise Server use https://github.yourcompany.com/api/v3. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `number` | Number | Pull request number. |
| `url` | Text | Web address of the pull request. |

*Time limit 60 s; needs: network.*

### Get pull request

`ooa.github/get_pull_request` (action)

Read a pull request, including whether it is merged and whether it can be merged.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Token | `token` | Credential (vault) | Yes |  | Main | Personal access token or app token from the vault, with access to the repository. |
| Owner | `owner` | Text | Yes |  | Main | User or organization that owns the repository, e.g. acme-corp. |
| Repo | `repo` | Text | Yes |  | Main | Repository name without the owner, e.g. billing-service. |
| Number | `number` | Number | Yes |  | Main | Pull request number. |
| Endpoint address | `base_url` | Text | No | `https://api.github.com` | More options | API address. Keep the default for github.com; for GitHub Enterprise Server use https://github.yourcompany.com/api/v3. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `pull_request` | Dictionary | number, title, state, draft, merged, mergeable, body, author, head, head_sha, base, created_at, updated_at, merged_at, url. |
| `merged` | Yes/No | True when already merged. |

*Time limit 60 s; safe to repeat; needs: network.*

### List pull requests

`ooa.github/list_pull_requests` (action)

List pull requests, filtered by state and target branch.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Token | `token` | Credential (vault) | Yes |  | Main | Personal access token or app token from the vault, with access to the repository. |
| Owner | `owner` | Text | Yes |  | Main | User or organization that owns the repository, e.g. acme-corp. |
| Repo | `repo` | Text | Yes |  | Main | Repository name without the owner, e.g. billing-service. |
| State | `state` | Text | No | `open` | Main | open, closed or all. One of: `open`, `closed`, `all` |
| Target branch | `target_branch` | Text | No |  | Main | Only pull requests that merge into this branch. Example: `main` |
| Maximum results | `max_results` | Number | No | `100` | More options | Most items to return; pages are read until this many are found. |
| Endpoint address | `base_url` | Text | No | `https://api.github.com` | More options | API address. Keep the default for github.com; for GitHub Enterprise Server use https://github.yourcompany.com/api/v3. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: number, title, state, draft, author, head, base, updated_at, url. |
| `count` | Number | Number of pull requests returned. |

*Time limit 300 s; safe to repeat; needs: network.*

### Merge pull request

`ooa.github/merge_pull_request` (action)

Merge a pull request with a merge commit, squash or rebase.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Token | `token` | Credential (vault) | Yes |  | Main | Personal access token or app token from the vault, with access to the repository. |
| Owner | `owner` | Text | Yes |  | Main | User or organization that owns the repository, e.g. acme-corp. |
| Repo | `repo` | Text | Yes |  | Main | Repository name without the owner, e.g. billing-service. |
| Number | `number` | Number | Yes |  | Main | Pull request number. |
| Method | `method` | Text | No | `merge` | Main | merge, squash or rebase. One of: `merge`, `squash`, `rebase` |
| Commit title | `commit_title` | Text | No |  | Main | Title of the merge commit; GitHub picks one when empty. |
| Commit message | `commit_message` | Text | No |  | Main | Extra text for the merge commit. |
| Only if the branch is still at (commit SHA) | `expected_head_sha` | Text | No |  | More options | Merge only if the source branch is still at this commit id. |
| Endpoint address | `base_url` | Text | No | `https://api.github.com` | More options | API address. Keep the default for github.com; for GitHub Enterprise Server use https://github.yourcompany.com/api/v3. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `sha` | Text | Commit id of the merge. |
| `merged` | Yes/No | True when merged. |

*Time limit 60 s; needs: network.*

## Releases

### Create release

`ooa.github/create_release` (action)

Publish a release for a tag; the tag is created from the target branch when it does not exist yet.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Token | `token` | Credential (vault) | Yes |  | Main | Personal access token or app token from the vault, with access to the repository. |
| Owner | `owner` | Text | Yes |  | Main | User or organization that owns the repository, e.g. acme-corp. |
| Repo | `repo` | Text | Yes |  | Main | Repository name without the owner, e.g. billing-service. |
| Tag | `tag` | Text | Yes |  | Main | Tag name of the release. Example: `v2.4.0` |
| Name | `name` | Text | No |  | Main | Release title. Empty uses the tag. |
| Notes | `notes` | Text | No |  | Main | Release notes in Markdown. |
| Target | `target` | Text | No |  | More options | Branch or commit to tag when the tag is new. Empty means the default branch. |
| Draft | `draft` | Yes/No | No | No | More options | Save as an unpublished draft. |
| Prerelease | `prerelease` | Yes/No | No | No | More options | Mark as a pre-release. |
| Endpoint address | `base_url` | Text | No | `https://api.github.com` | More options | API address. Keep the default for github.com; for GitHub Enterprise Server use https://github.yourcompany.com/api/v3. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `release_id` | Number | Id of the release. |
| `url` | Text | Web address of the release. |

*Time limit 60 s; needs: network.*

### Get latest release

`ooa.github/get_latest_release` (action)

Read the newest published release (drafts and pre-releases are skipped).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Token | `token` | Credential (vault) | Yes |  | Main | Personal access token or app token from the vault, with access to the repository. |
| Owner | `owner` | Text | Yes |  | Main | User or organization that owns the repository, e.g. acme-corp. |
| Repo | `repo` | Text | Yes |  | Main | Repository name without the owner, e.g. billing-service. |
| Endpoint address | `base_url` | Text | No | `https://api.github.com` | More options | API address. Keep the default for github.com; for GitHub Enterprise Server use https://github.yourcompany.com/api/v3. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `release` | Dictionary | id, tag, name, notes, published_at, author, url, assets (list of file names). |
| `tag` | Text | Tag of the release, e.g. v2.4.0. |

*Time limit 60 s; safe to repeat; needs: network.*

## Files

### Get file contents

`ooa.github/get_file` (action)

Read a file from a repository as text, or save it to a local file.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Token | `token` | Credential (vault) | Yes |  | Main | Personal access token or app token from the vault, with access to the repository. |
| Owner | `owner` | Text | Yes |  | Main | User or organization that owns the repository, e.g. acme-corp. |
| Repo | `repo` | Text | Yes |  | Main | Repository name without the owner, e.g. billing-service. |
| Path | `path` | Text | Yes |  | Main | Path of the file inside the repository. Example: `config/rates.json` |
| Ref | `ref` | Text | No |  | Main | Branch, tag or commit to read from. Empty means the default branch. |
| Save to | `save_to` | File path | No |  | Main; tick Save it to a file | Local file to save the contents to, e.g. downloads/rates.json. |
| Endpoint address | `base_url` | Text | No | `https://api.github.com` | More options | API address. Keep the default for github.com; for GitHub Enterprise Server use https://github.yourcompany.com/api/v3. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `text` | Text | File contents as UTF-8 text (empty when saved to a file). |
| `sha` | Text | Version id of the file; pass it to Create or update file to change it safely. |
| `size` | Number | Size in bytes. |

*Time limit 300 s; safe to repeat; needs: network, filesystem.*

### Create or update file

`ooa.github/put_file` (action)

Commit a new or changed file to a branch, from text or from a local file.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Token | `token` | Credential (vault) | Yes |  | Main | Personal access token or app token from the vault, with access to the repository. |
| Owner | `owner` | Text | Yes |  | Main | User or organization that owns the repository, e.g. acme-corp. |
| Repo | `repo` | Text | Yes |  | Main | Repository name without the owner, e.g. billing-service. |
| Path | `path` | Text | Yes |  | Main | Path of the file inside the repository. Example: `reports/2026-10.csv` |
| Message | `message` | Text | Yes |  | Main | Commit message. Example: `Update October report` |
| Text | `text` | Text | No |  | Main; choice Contents = Typed text | New contents as text. Use this or local_file. |
| Local file | `local_file` | File path | No |  | Main; choice Contents = A local file | Local file whose contents to commit. Use this or text. |
| Branch | `branch` | Text | No |  | Main | Branch to commit to. Empty means the default branch. |
| Current version (SHA) | `sha` | Text | No |  | More options | Current version id of the file. Leave empty to look it up; it is not needed for new files. |
| Endpoint address | `base_url` | Text | No | `https://api.github.com` | More options | API address. Keep the default for github.com; for GitHub Enterprise Server use https://github.yourcompany.com/api/v3. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `commit_sha` | Text | Id of the new commit. |
| `sha` | Text | New version id of the file. |

*Time limit 300 s; needs: network, filesystem.*

## Repositories

### Get repository

`ooa.github/get_repository` (action)

Read a repository's main details, such as its default branch and visibility.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Token | `token` | Credential (vault) | Yes |  | Main | Personal access token or app token from the vault, with access to the repository. |
| Owner | `owner` | Text | Yes |  | Main | User or organization that owns the repository, e.g. acme-corp. |
| Repo | `repo` | Text | Yes |  | Main | Repository name without the owner, e.g. billing-service. |
| Endpoint address | `base_url` | Text | No | `https://api.github.com` | More options | API address. Keep the default for github.com; for GitHub Enterprise Server use https://github.yourcompany.com/api/v3. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `repository` | Dictionary | name, full_name, description, private, default_branch, open_issues, archived, url. |
| `default_branch` | Text | Default branch, e.g. main. |

*Time limit 60 s; safe to repeat; needs: network.*

### List repositories

`ooa.github/list_repositories` (action)

List the repositories of an organization or of another user, or the repositories the token's user can access when no owner is given.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Token | `token` | Credential (vault) | Yes |  | Main | Personal access token or app token from the vault, with access to the repository. |
| Owner type | `owner_type` | Text | No | `organization` | Main | organization: the name in organization is an organization; user: it is a user's login, and their public repositories are listed. One of: `organization`, `user` |
| Organization | `organization` | Text | No |  | Main | Organization name, or a user's login when owner_type is user. Leave empty for the repositories of the token's user. Example: `acme-corp` |
| Maximum results | `max_results` | Number | No | `100` | More options | Most items to return; pages are read until this many are found. |
| Endpoint address | `base_url` | Text | No | `https://api.github.com` | More options | API address. Keep the default for github.com; for GitHub Enterprise Server use https://github.yourcompany.com/api/v3. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: name, full_name, private, default_branch, description, updated_at, url. |
| `count` | Number | Number of repositories returned. |

*Time limit 300 s; safe to repeat; needs: network.*

## Workflows

### Get workflow run

`ooa.github/get_workflow_run` (action)

Read the status and result of one GitHub Actions run.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Token | `token` | Credential (vault) | Yes |  | Main | Personal access token or app token from the vault, with access to the repository. |
| Owner | `owner` | Text | Yes |  | Main | User or organization that owns the repository, e.g. acme-corp. |
| Repo | `repo` | Text | Yes |  | Main | Repository name without the owner, e.g. billing-service. |
| Run ID | `run_id` | Number | Yes |  | Main | Run id, from List workflow runs. |
| Endpoint address | `base_url` | Text | No | `https://api.github.com` | More options | API address. Keep the default for github.com; for GitHub Enterprise Server use https://github.yourcompany.com/api/v3. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `status` | Text | queued, in_progress or completed. |
| `conclusion` | Text | Result once completed: success, failure, cancelled, skipped or timed_out. |
| `run` | Dictionary | id, name, status, conclusion, branch, sha, event, created_at, updated_at, url. |

*Time limit 60 s; safe to repeat; needs: network.*

### List workflow runs

`ooa.github/list_workflow_runs` (action)

List recent GitHub Actions runs, newest first, for one workflow or the whole repository.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Token | `token` | Credential (vault) | Yes |  | Main | Personal access token or app token from the vault, with access to the repository. |
| Owner | `owner` | Text | Yes |  | Main | User or organization that owns the repository, e.g. acme-corp. |
| Repo | `repo` | Text | Yes |  | Main | Repository name without the owner, e.g. billing-service. |
| Workflow | `workflow` | Text | No |  | Main | Workflow file name or id. Empty lists runs of all workflows. |
| Branch | `branch` | Text | No |  | Main | Only runs on this branch. |
| Status | `status` | Text | No |  | Main | Only runs with this status or result, e.g. queued, in_progress, completed, success, failure. Example: `in_progress` |
| Maximum results | `max_results` | Number | No | `30` | More options | Most items to return; pages are read until this many are found. |
| Endpoint address | `base_url` | Text | No | `https://api.github.com` | More options | API address. Keep the default for github.com; for GitHub Enterprise Server use https://github.yourcompany.com/api/v3. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: id, name, status, conclusion, branch, event, created_at, url. |
| `count` | Number | Number of runs returned. |

*Time limit 300 s; safe to repeat; needs: network.*

### Run workflow

`ooa.github/run_workflow` (action)

Start a GitHub Actions workflow that allows manual runs, with optional inputs.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Token | `token` | Credential (vault) | Yes |  | Main | Personal access token or app token from the vault, with access to the repository. |
| Owner | `owner` | Text | Yes |  | Main | User or organization that owns the repository, e.g. acme-corp. |
| Repo | `repo` | Text | Yes |  | Main | Repository name without the owner, e.g. billing-service. |
| Workflow | `workflow` | Text | Yes |  | Main | Workflow file name (e.g. deploy.yml) or numeric workflow id. Example: `deploy.yml` |
| Ref | `ref` | Text | Yes |  | Main | Branch or tag to run the workflow on. Example: `main` |
| Inputs | `inputs` | Dictionary | No |  | Main | Workflow inputs, e.g. {"environment": "staging"}. |
| Endpoint address | `base_url` | Text | No | `https://api.github.com` | More options | API address. Keep the default for github.com; for GitHub Enterprise Server use https://github.yourcompany.com/api/v3. |

*Time limit 60 s; needs: network.*

## Conditions

Used in If, Loop while and Wait until steps.

### Pull request is merged

`ooa.github/pull_request_is_merged` (condition)

True when the pull request has been merged.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Endpoint address | `base_url` | Text | No | `https://api.github.com` | Main | API address. Keep the default for github.com; for GitHub Enterprise Server use https://github.yourcompany.com/api/v3. |
| Token | `token` | Credential (vault) | Yes |  | Main | Personal access token or app token from the vault, with access to the repository. |
| Owner | `owner` | Text | Yes |  | Main | User or organization that owns the repository, e.g. acme-corp. |
| Repo | `repo` | Text | Yes |  | Main | Repository name without the owner, e.g. billing-service. |
| Number | `number` | Number | Yes |  | Main | Pull request number. |
