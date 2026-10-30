# Azure DevOps

Toolkit `ooa.azure_devops`, version 1.0.0. Work with Azure DevOps: work items and WIQL queries, comments and attachments, Git repositories and pull requests, pipelines, builds and sprints. Works with the cloud service and Azure DevOps Server.

[All toolkits](README.md)

## Contents

- **Repos**: Comment on pull request, Complete pull request, Create pull request, Get pull request, List pull requests, List repositories
- **Test plans**: Add test cases to suite, Create test case, Create test plan, Create test run, Create test suite, Delete test plan, Delete test suite, Get test plan details, Get test run details, List test plans, List test points, Remove test cases from suite, Edit test plan, Update test point, Edit test suite
- **Work items**: Add work item comment, Attach file to work item, Create work item, Delete work item, Get work item, List work item comments, Query work items (WIQL), Update work item
- **Pipelines**: Create pipeline, Delete pipeline, Get pipeline details, Get pipeline run, List pipeline runs, List pipelines, Run pipeline, Edit pipeline
- **Builds**: Get build, List builds, Queue build
- **Projects**: Get project, List projects
- **Boards**: List sprint work items, List sprints (iterations)
- **Conditions**: Pipeline run finished

## Repos

### Comment on pull request

`ooa.azure_devops/add_pull_request_comment` (action)

Start a new comment thread on a pull request.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Organization address (URL) | `organization_url` | Text | Yes |  | Main | Organization or collection address, e.g. https://dev.azure.com/contoso, or https://devops.contoso.local/tfs/DefaultCollection for Azure DevOps Server. |
| Personal access token | `personal_access_token` | Credential (vault) | Yes |  | Main | Personal access token from the vault, with the scopes this action needs (e.g. Work Items read and write). |
| Project | `project` | Text | Yes |  | Main | Project name, e.g. Finance Portal. |
| Repository | `repository` | Text | Yes |  | Main | Repository name or id, e.g. finance-portal-api. |
| Pull request ID | `pull_request_id` | Number | Yes |  | Main | Pull request id. |
| Text | `text` | Text | Yes |  | Main | Comment text in Markdown. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `thread_id` | Number | Id of the new thread. |

*Time limit 60 s; needs: network.*

### Complete pull request

`ooa.azure_devops/complete_pull_request` (action)

Merge an active pull request into its target branch.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Organization address (URL) | `organization_url` | Text | Yes |  | Main | Organization or collection address, e.g. https://dev.azure.com/contoso, or https://devops.contoso.local/tfs/DefaultCollection for Azure DevOps Server. |
| Personal access token | `personal_access_token` | Credential (vault) | Yes |  | Main | Personal access token from the vault, with the scopes this action needs (e.g. Work Items read and write). |
| Project | `project` | Text | Yes |  | Main | Project name, e.g. Finance Portal. |
| Repository | `repository` | Text | Yes |  | Main | Repository name or id, e.g. finance-portal-api. |
| Pull request ID | `pull_request_id` | Number | Yes |  | Main | Pull request id. |
| Merge strategy | `merge_strategy` | Text | No | `noFastForward` | Main | noFastForward (merge commit), squash, rebase or rebaseMerge. One of: `noFastForward`, `squash`, `rebase`, `rebaseMerge` |
| Delete source branch | `delete_source_branch` | Yes/No | No | No | Main | Delete the source branch after merging. |
| Merge commit message | `merge_commit_message` | Text | No |  | More options | Message for the merge commit. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `status` | Text | Status after the request, normally completed. |
| `merge_status` | Text | Merge result, e.g. succeeded, queued or conflicts. |

*Time limit 60 s; needs: network.*

### Create pull request

`ooa.azure_devops/create_pull_request` (action)

Propose merging one branch into another, optionally linking work items.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Organization address (URL) | `organization_url` | Text | Yes |  | Main | Organization or collection address, e.g. https://dev.azure.com/contoso, or https://devops.contoso.local/tfs/DefaultCollection for Azure DevOps Server. |
| Personal access token | `personal_access_token` | Credential (vault) | Yes |  | Main | Personal access token from the vault, with the scopes this action needs (e.g. Work Items read and write). |
| Project | `project` | Text | Yes |  | Main | Project name, e.g. Finance Portal. |
| Repository | `repository` | Text | Yes |  | Main | Repository name or id, e.g. finance-portal-api. |
| Source branch | `source_branch` | Text | Yes |  | Main | Branch with the changes. Example: `feature/vat-2027` |
| Target branch | `target_branch` | Text | Yes |  | Main | Branch to merge into. Example: `main` |
| Title | `title` | Text | Yes |  | Main | Title of the pull request. |
| Description | `description` | Text | No |  | Main | Description in Markdown. |
| Draft | `draft` | Yes/No | No | No | More options | Create as a draft. |
| Work item IDs | `work_item_ids` | List | No |  | More options | Work item ids to link, e.g. [4711, 4712]. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `pull_request_id` | Number | Id of the new pull request. |

*Time limit 60 s; needs: network.*

### Get pull request

`ooa.azure_devops/get_pull_request` (action)

Read a pull request by id.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Organization address (URL) | `organization_url` | Text | Yes |  | Main | Organization or collection address, e.g. https://dev.azure.com/contoso, or https://devops.contoso.local/tfs/DefaultCollection for Azure DevOps Server. |
| Personal access token | `personal_access_token` | Credential (vault) | Yes |  | Main | Personal access token from the vault, with the scopes this action needs (e.g. Work Items read and write). |
| Project | `project` | Text | Yes |  | Main | Project name, e.g. Finance Portal. |
| Repository | `repository` | Text | Yes |  | Main | Repository name or id, e.g. finance-portal-api. |
| Pull request ID | `pull_request_id` | Number | Yes |  | Main | Pull request id. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `pull_request` | Dictionary | id, title, description, status, is_draft, merge_status, created_by, source_branch, target_branch, created, closed, repository, last_source_commit. |
| `status` | Text | active, completed or abandoned. |

*Time limit 60 s; safe to repeat; needs: network.*

### List pull requests

`ooa.azure_devops/list_pull_requests` (action)

List pull requests of a repository, filtered by status and target branch.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Organization address (URL) | `organization_url` | Text | Yes |  | Main | Organization or collection address, e.g. https://dev.azure.com/contoso, or https://devops.contoso.local/tfs/DefaultCollection for Azure DevOps Server. |
| Personal access token | `personal_access_token` | Credential (vault) | Yes |  | Main | Personal access token from the vault, with the scopes this action needs (e.g. Work Items read and write). |
| Project | `project` | Text | Yes |  | Main | Project name, e.g. Finance Portal. |
| Repository | `repository` | Text | Yes |  | Main | Repository name or id, e.g. finance-portal-api. |
| Status | `status` | Text | No | `active` | Main | active, completed, abandoned or all. One of: `active`, `completed`, `abandoned`, `all` |
| Target branch | `target_branch` | Text | No |  | Main | Only pull requests into this branch. Example: `main` |
| Maximum results | `max_results` | Number | No | `100` | More options | Most items to return; pages are read until this many are found. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: id, title, status, is_draft, created_by, source_branch, target_branch, created, merge_status. |
| `count` | Number | Number of pull requests returned. |

*Time limit 300 s; safe to repeat; needs: network.*

### List repositories

`ooa.azure_devops/list_repositories` (action)

List the Git repositories of a project.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Organization address (URL) | `organization_url` | Text | Yes |  | Main | Organization or collection address, e.g. https://dev.azure.com/contoso, or https://devops.contoso.local/tfs/DefaultCollection for Azure DevOps Server. |
| Personal access token | `personal_access_token` | Credential (vault) | Yes |  | Main | Personal access token from the vault, with the scopes this action needs (e.g. Work Items read and write). |
| Project | `project` | Text | Yes |  | Main | Project name, e.g. Finance Portal. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: id, name, default_branch, size, web_url, remote_url. |
| `count` | Number | Number of repositories. |

*Time limit 60 s; safe to repeat; needs: network.*

## Test plans

### Add test cases to suite

`ooa.azure_devops/add_test_cases_to_suite` (action)

Add existing test case work items to a static suite.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Organization address (URL) | `organization_url` | Text | Yes |  | Main | Organization or collection address, e.g. https://dev.azure.com/contoso, or https://devops.contoso.local/tfs/DefaultCollection for Azure DevOps Server. |
| Personal access token | `personal_access_token` | Credential (vault) | Yes |  | Main | Personal access token from the vault, with the scopes this action needs (e.g. Work Items read and write). |
| Project | `project` | Text | Yes |  | Main | Project name, e.g. Finance Portal. |
| Plan ID | `plan_id` | Number | Yes |  | Main | Test plan id, from List test plans. Range: 1 to  |
| Suite ID | `suite_id` | Number | Yes |  | Main | Test suite id; a plan's top suite has the plan's root suite id (see Get test plan). Range: 1 to  |
| Test case IDs | `test_case_ids` | List | Yes |  | Main | Work item ids of the test cases, e.g. [101, 102]. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `count` | Number | Number of test cases added. |

*Time limit 60 s; needs: network.*

### Create test case

`ooa.azure_devops/create_test_case` (action)

Create a Test Case work item with numbered steps (action and expected result), optionally added to a suite.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Organization address (URL) | `organization_url` | Text | Yes |  | Main | Organization or collection address, e.g. https://dev.azure.com/contoso, or https://devops.contoso.local/tfs/DefaultCollection for Azure DevOps Server. |
| Personal access token | `personal_access_token` | Credential (vault) | Yes |  | Main | Personal access token from the vault, with the scopes this action needs (e.g. Work Items read and write). |
| Project | `project` | Text | Yes |  | Main | Project name, e.g. Finance Portal. |
| Title | `title` | Text | Yes |  | Main | Test case title. |
| Steps | `steps` | List | No |  | Main | Steps in order: text, or {"action": ..., "expected": ...}, e.g. [{"action": "Open the invoice", "expected": "Total shows 120.50"}]. |
| Area path | `area_path` | Text | No |  | Main | Area path; the project's root area when empty. Example: `Finance Portal\Payments` |
| Iteration path | `iteration_path` | Text | No |  | Main | Iteration (sprint) path; the project's root iteration when empty. Example: `Finance Portal\Sprint 42` |
| Priority | `priority` | Number | No |  | Main | Priority from 1 (highest) to 4 (lowest). Range: 1 to 4 |
| Automation status | `automation_status` | Text | No |  | More options | Whether the test is automated: Not Automated, Planned or Automated. One of: `Not Automated`, `Planned`, `Automated` |
| Fields | `fields` | Dictionary | No |  | More options | Other fields, e.g. {"priority": 2, "area_path": "Finance Portal"}. |
| Plan ID | `plan_id` | Number | No |  | More options | Add the new test case to this plan's suite (with suite_id). Range: 1 to  |
| Suite ID | `suite_id` | Number | No |  | More options | Suite to add it to. Range: 1 to ; Shown when: `{"plan_id": ["!", ""]}` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `id` | Number | Work item id of the test case. |

*Time limit 60 s; needs: network.*

### Create test plan

`ooa.azure_devops/create_test_plan` (action)

Create a test plan; its root suite is created with it.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Organization address (URL) | `organization_url` | Text | Yes |  | Main | Organization or collection address, e.g. https://dev.azure.com/contoso, or https://devops.contoso.local/tfs/DefaultCollection for Azure DevOps Server. |
| Personal access token | `personal_access_token` | Credential (vault) | Yes |  | Main | Personal access token from the vault, with the scopes this action needs (e.g. Work Items read and write). |
| Project | `project` | Text | Yes |  | Main | Project name, e.g. Finance Portal. |
| Name | `name` | Text | Yes |  | Main | Plan name, e.g. Release 3.2 regression. |
| Plan description | `plan_description` | Text | No |  | Main | Description. |
| Area path | `area_path` | Text | No |  | Main | Area path; the project's root area when empty. |
| Iteration | `iteration` | Text | No |  | Main | Iteration path, e.g. Finance Portal\Sprint 12. |
| Start date | `start_date` | DateTime | No |  | Main; tick Set the plan's dates | First day of the testing period (UTC); the service picks today when empty. Example: `2026-11-01` |
| End date | `end_date` | DateTime | No |  | Main; tick Set the plan's dates | Last day of the testing period (UTC); on or after the start date. Example: `2026-11-14` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `plan_id` | Number | Id of the new plan. |
| `root_suite_id` | Number | Id of the plan's root suite, for Create test suite. |

*Time limit 60 s; needs: network.*

### Create test run

`ooa.azure_devops/create_test_run` (action)

Start a test run for test points of a plan, so their results can be recorded; manual by default, or automated and linked to a build.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Organization address (URL) | `organization_url` | Text | Yes |  | Main | Organization or collection address, e.g. https://dev.azure.com/contoso, or https://devops.contoso.local/tfs/DefaultCollection for Azure DevOps Server. |
| Personal access token | `personal_access_token` | Credential (vault) | Yes |  | Main | Personal access token from the vault, with the scopes this action needs (e.g. Work Items read and write). |
| Project | `project` | Text | Yes |  | Main | Project name, e.g. Finance Portal. |
| Plan ID | `plan_id` | Number | Yes |  | Main | Test plan id, from List test plans. Range: 1 to  |
| Name | `name` | Text | Yes |  | Main | Run name. |
| Point IDs | `point_ids` | List | No |  | Main | Test points to include; the run starts empty when none are given. |
| Automated | `automated` | Yes/No | No | No | More options | Mark the run as automated instead of manual. |
| Build ID | `build_id` | Number | No |  | More options | Id of the build the run tests, from Queue build or List builds. Range: 1 to  |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `run_id` | Number | Id of the new run. |
| `url` | Text | Web address of the run. |

*Time limit 60 s; needs: network.*

### Create test suite

`ooa.azure_devops/create_test_suite` (action)

Create a static, requirement-based or query-based suite inside a plan's suite.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Organization address (URL) | `organization_url` | Text | Yes |  | Main | Organization or collection address, e.g. https://dev.azure.com/contoso, or https://devops.contoso.local/tfs/DefaultCollection for Azure DevOps Server. |
| Personal access token | `personal_access_token` | Credential (vault) | Yes |  | Main | Personal access token from the vault, with the scopes this action needs (e.g. Work Items read and write). |
| Project | `project` | Text | Yes |  | Main | Project name, e.g. Finance Portal. |
| Plan ID | `plan_id` | Number | Yes |  | Main | Test plan id, from List test plans. Range: 1 to  |
| Suite type | `suite_type` | Text | No | `static` | Main | static: test cases added by hand; requirement: the tests of one work item; query: tests matching a WIQL query. One of: `static`, `requirement`, `query` |
| Requirement ID | `requirement_id` | Number | No |  | Main | Work item whose tests the suite holds. Range: 1 to ; Shown when: `{"suite_type": ["requirement"]}` |
| Query | `query` | Text | No |  | Main | WIQL query selecting the test cases. Shown when: `{"suite_type": ["query"]}` |
| Parent suite ID | `parent_suite_id` | Number | Yes |  | Main | Suite to create it in, usually the plan's root suite. Range: 1 to  |
| Name | `name` | Text | Yes |  | Main | Suite name. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `suite_id` | Number | Id of the new suite. |

*Time limit 60 s; needs: network.*

### Delete test plan

`ooa.azure_devops/delete_test_plan` (action)

Delete a test plan with its suites (test cases stay as work items). Needs confirm.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Organization address (URL) | `organization_url` | Text | Yes |  | Main | Organization or collection address, e.g. https://dev.azure.com/contoso, or https://devops.contoso.local/tfs/DefaultCollection for Azure DevOps Server. |
| Personal access token | `personal_access_token` | Credential (vault) | Yes |  | Main | Personal access token from the vault, with the scopes this action needs (e.g. Work Items read and write). |
| Project | `project` | Text | Yes |  | Main | Project name, e.g. Finance Portal. |
| Plan ID | `plan_id` | Number | Yes |  | Main | Test plan id, from List test plans. Range: 1 to  |
| Confirm | `confirm` | Yes/No | Yes |  | Main | Must be true: a safeguard against deleting by mistake. |

*Time limit 60 s; needs: network.*

### Delete test suite

`ooa.azure_devops/delete_test_suite` (action)

Delete a suite and its child suites (test cases stay as work items).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Organization address (URL) | `organization_url` | Text | Yes |  | Main | Organization or collection address, e.g. https://dev.azure.com/contoso, or https://devops.contoso.local/tfs/DefaultCollection for Azure DevOps Server. |
| Personal access token | `personal_access_token` | Credential (vault) | Yes |  | Main | Personal access token from the vault, with the scopes this action needs (e.g. Work Items read and write). |
| Project | `project` | Text | Yes |  | Main | Project name, e.g. Finance Portal. |
| Plan ID | `plan_id` | Number | Yes |  | Main | Test plan id, from List test plans. Range: 1 to  |
| Suite ID | `suite_id` | Number | Yes |  | Main | Test suite id; a plan's top suite has the plan's root suite id (see Get test plan). Range: 1 to  |

*Time limit 60 s; needs: network.*

### Get test plan details

`ooa.azure_devops/get_test_plan` (action)

Read one test plan.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Organization address (URL) | `organization_url` | Text | Yes |  | Main | Organization or collection address, e.g. https://dev.azure.com/contoso, or https://devops.contoso.local/tfs/DefaultCollection for Azure DevOps Server. |
| Personal access token | `personal_access_token` | Credential (vault) | Yes |  | Main | Personal access token from the vault, with the scopes this action needs (e.g. Work Items read and write). |
| Project | `project` | Text | Yes |  | Main | Project name, e.g. Finance Portal. |
| Plan ID | `plan_id` | Number | Yes |  | Main | Test plan id, from List test plans. Range: 1 to  |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `plan` | Dictionary | Keys: id, name, state, area_path, iteration, root_suite_id, owner, start_date, end_date. |

*Time limit 60 s; safe to repeat; needs: network.*

### Get test run details

`ooa.azure_devops/get_test_run` (action)

Read a test run: state, counts of passed, failed and incomplete tests, dates.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Organization address (URL) | `organization_url` | Text | Yes |  | Main | Organization or collection address, e.g. https://dev.azure.com/contoso, or https://devops.contoso.local/tfs/DefaultCollection for Azure DevOps Server. |
| Personal access token | `personal_access_token` | Credential (vault) | Yes |  | Main | Personal access token from the vault, with the scopes this action needs (e.g. Work Items read and write). |
| Project | `project` | Text | Yes |  | Main | Project name, e.g. Finance Portal. |
| Run ID | `run_id` | Number | Yes |  | Main | Test run id. Range: 1 to  |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `run` | Dictionary | Keys: id, name, state, total_tests, passed_tests, failed_tests, incomplete_tests, started, completed, url. |

*Time limit 60 s; safe to repeat; needs: network.*

### List test plans

`ooa.azure_devops/list_test_plans` (action)

List the test plans of a project.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Organization address (URL) | `organization_url` | Text | Yes |  | Main | Organization or collection address, e.g. https://dev.azure.com/contoso, or https://devops.contoso.local/tfs/DefaultCollection for Azure DevOps Server. |
| Personal access token | `personal_access_token` | Credential (vault) | Yes |  | Main | Personal access token from the vault, with the scopes this action needs (e.g. Work Items read and write). |
| Project | `project` | Text | Yes |  | Main | Project name, e.g. Finance Portal. |
| Active only | `active_only` | Yes/No | No | No | Main | Leave out inactive plans. |
| Owner | `owner` | Text | No |  | Main | Only plans owned by this person: identity id or name. Example: `ana@contoso.com` |
| Maximum results | `max_results` | Number | No | `5000` | More options | Most items to return; pages are read until this many are found. Range: 1 to  |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: id, name, state, area_path, iteration, root_suite_id. |
| `count` | Number | Number of plans. |

*Time limit 60 s; safe to repeat; needs: network.*

### List test points

`ooa.azure_devops/list_test_points` (action)

List the test points of a suite (one per test case and configuration) with their latest outcome.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Organization address (URL) | `organization_url` | Text | Yes |  | Main | Organization or collection address, e.g. https://dev.azure.com/contoso, or https://devops.contoso.local/tfs/DefaultCollection for Azure DevOps Server. |
| Personal access token | `personal_access_token` | Credential (vault) | Yes |  | Main | Personal access token from the vault, with the scopes this action needs (e.g. Work Items read and write). |
| Project | `project` | Text | Yes |  | Main | Project name, e.g. Finance Portal. |
| Plan ID | `plan_id` | Number | Yes |  | Main | Test plan id, from List test plans. Range: 1 to  |
| Suite ID | `suite_id` | Number | Yes |  | Main | Test suite id; a plan's top suite has the plan's root suite id (see Get test plan). Range: 1 to  |
| Test case ID | `test_case_id` | Number | No |  | Main | Only the points of this test case (work item id). Range: 1 to  |
| Maximum results | `max_results` | Number | No | `10000` | More options | Most items to return; pages are read until this many are found. Range: 1 to  |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: id, test_case_id, test_case, configuration, tester, outcome, state. |
| `count` | Number | Number of points. |

*Time limit 60 s; safe to repeat; needs: network.*

### Remove test cases from suite

`ooa.azure_devops/remove_test_cases_from_suite` (action)

Take test cases out of a suite; the test case work items are kept.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Organization address (URL) | `organization_url` | Text | Yes |  | Main | Organization or collection address, e.g. https://dev.azure.com/contoso, or https://devops.contoso.local/tfs/DefaultCollection for Azure DevOps Server. |
| Personal access token | `personal_access_token` | Credential (vault) | Yes |  | Main | Personal access token from the vault, with the scopes this action needs (e.g. Work Items read and write). |
| Project | `project` | Text | Yes |  | Main | Project name, e.g. Finance Portal. |
| Plan ID | `plan_id` | Number | Yes |  | Main | Test plan id, from List test plans. Range: 1 to  |
| Suite ID | `suite_id` | Number | Yes |  | Main | Test suite id; a plan's top suite has the plan's root suite id (see Get test plan). Range: 1 to  |
| Test case IDs | `test_case_ids` | List | Yes |  | Main | Work item ids of the test cases. |

*Time limit 60 s; needs: network.*

### Edit test plan

`ooa.azure_devops/update_test_plan` (action)

Rename a test plan, change its state, description, area, iteration or dates; empty inputs are kept.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Organization address (URL) | `organization_url` | Text | Yes |  | Main | Organization or collection address, e.g. https://dev.azure.com/contoso, or https://devops.contoso.local/tfs/DefaultCollection for Azure DevOps Server. |
| Personal access token | `personal_access_token` | Credential (vault) | Yes |  | Main | Personal access token from the vault, with the scopes this action needs (e.g. Work Items read and write). |
| Project | `project` | Text | Yes |  | Main | Project name, e.g. Finance Portal. |
| Plan ID | `plan_id` | Number | Yes |  | Main | Test plan id, from List test plans. Range: 1 to  |
| Name | `name` | Text | No |  | Main | New name. |
| Plan description | `plan_description` | Text | No |  | Main | New description. |
| Area path | `area_path` | Text | No |  | Main | New area path. |
| Iteration | `iteration` | Text | No |  | Main | New iteration path. |
| State | `state` | Text | No |  | Main | Plan state. One of: `Active`, `Inactive` |
| Start date | `start_date` | DateTime | No |  | Main; tick Change the plan's dates | New first day of the testing period (UTC). |
| End date | `end_date` | DateTime | No |  | Main; tick Change the plan's dates | New last day of the testing period (UTC). |

*Time limit 60 s; needs: network.*

### Update test point

`ooa.azure_devops/update_test_point` (action)

Set the outcome of test points (passed, failed, blocked, not applicable), reset them to active, or assign them to a tester, e.g. after a manual check done by an agent.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Organization address (URL) | `organization_url` | Text | Yes |  | Main | Organization or collection address, e.g. https://dev.azure.com/contoso, or https://devops.contoso.local/tfs/DefaultCollection for Azure DevOps Server. |
| Personal access token | `personal_access_token` | Credential (vault) | Yes |  | Main | Personal access token from the vault, with the scopes this action needs (e.g. Work Items read and write). |
| Project | `project` | Text | Yes |  | Main | Project name, e.g. Finance Portal. |
| Plan ID | `plan_id` | Number | Yes |  | Main | Test plan id, from List test plans. Range: 1 to  |
| Suite ID | `suite_id` | Number | Yes |  | Main | Test suite id; a plan's top suite has the plan's root suite id (see Get test plan). Range: 1 to  |
| Point IDs | `point_ids` | List | Yes |  | Main | Test point ids, from List test points. |
| Outcome | `outcome` | Text | No |  | Main | New outcome; active resets the point. Leave empty to only change the tester. One of: `passed`, `failed`, `blocked`, `notApplicable`, `active` |
| Tester | `tester` | Text | No |  | Main | Person to assign the points to: sign-in email, display name or identity id. Needs the token to read identities. Example: `ana@contoso.com` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `count` | Number | Number of points updated. |

*Time limit 60 s; needs: network.*

### Edit test suite

`ooa.azure_devops/update_test_suite` (action)

Rename a suite, move it under another suite of the plan, or change a query-based suite's query.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Organization address (URL) | `organization_url` | Text | Yes |  | Main | Organization or collection address, e.g. https://dev.azure.com/contoso, or https://devops.contoso.local/tfs/DefaultCollection for Azure DevOps Server. |
| Personal access token | `personal_access_token` | Credential (vault) | Yes |  | Main | Personal access token from the vault, with the scopes this action needs (e.g. Work Items read and write). |
| Project | `project` | Text | Yes |  | Main | Project name, e.g. Finance Portal. |
| Plan ID | `plan_id` | Number | Yes |  | Main | Test plan id, from List test plans. Range: 1 to  |
| Suite ID | `suite_id` | Number | Yes |  | Main | Test suite id; a plan's top suite has the plan's root suite id (see Get test plan). Range: 1 to  |
| Parent suite ID | `parent_suite_id` | Number | No |  | Main | Move under this suite. Range: 1 to  |
| Name | `name` | Text | No |  | Main | New name. |
| Query | `query` | Text | No |  | Main | New WIQL query selecting the test cases (query-based suites only). |

*Time limit 60 s; needs: network.*

## Work items

### Add work item comment

`ooa.azure_devops/add_work_item_comment` (action)

Add a comment to a work item's discussion.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Organization address (URL) | `organization_url` | Text | Yes |  | Main | Organization or collection address, e.g. https://dev.azure.com/contoso, or https://devops.contoso.local/tfs/DefaultCollection for Azure DevOps Server. |
| Personal access token | `personal_access_token` | Credential (vault) | Yes |  | Main | Personal access token from the vault, with the scopes this action needs (e.g. Work Items read and write). |
| Project | `project` | Text | Yes |  | Main | Project name, e.g. Finance Portal. |
| ID | `id` | Number | Yes |  | Main | Work item id. |
| Text | `text` | Text | Yes |  | Main | Comment text; simple HTML is allowed. Example: `Invoice re-sent by the finance agent.` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `comment_id` | Number | Id of the new comment. |

*Time limit 60 s; needs: network.*

### Attach file to work item

`ooa.azure_devops/attach_file_to_work_item` (action)

Upload a local file and attach it to a work item.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Organization address (URL) | `organization_url` | Text | Yes |  | Main | Organization or collection address, e.g. https://dev.azure.com/contoso, or https://devops.contoso.local/tfs/DefaultCollection for Azure DevOps Server. |
| Personal access token | `personal_access_token` | Credential (vault) | Yes |  | Main | Personal access token from the vault, with the scopes this action needs (e.g. Work Items read and write). |
| Project | `project` | Text | Yes |  | Main | Project name, e.g. Finance Portal. |
| ID | `id` | Number | Yes |  | Main | Work item id. |
| Path | `path` | File path | Yes |  | Main | Local file to attach. Example: `out/error-log.txt` |
| Comment | `comment` | Text | No |  | Main | Short note shown next to the attachment. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `attachment_url` | Text | Address of the uploaded file. |

*Time limit 600 s; needs: network, filesystem.*

### Create work item

`ooa.azure_devops/create_work_item` (action)

Create a work item such as a Bug, Task or User Story.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Organization address (URL) | `organization_url` | Text | Yes |  | Main | Organization or collection address, e.g. https://dev.azure.com/contoso, or https://devops.contoso.local/tfs/DefaultCollection for Azure DevOps Server. |
| Personal access token | `personal_access_token` | Credential (vault) | Yes |  | Main | Personal access token from the vault, with the scopes this action needs (e.g. Work Items read and write). |
| Project | `project` | Text | Yes |  | Main | Project name, e.g. Finance Portal. |
| Type | `type` | Text | Yes |  | Main | Work item type, e.g. Bug, Task, User Story, Issue. Example: `Bug` |
| Title | `title` | Text | Yes |  | Main | Title of the work item. |
| Description | `description` | Text | No |  | Main | Description; simple HTML is allowed. |
| Assigned to | `assigned_to` | Text | No |  | Main | Person to assign, by sign-in email or display name. Example: `ana@contoso.com` |
| Area path | `area_path` | Text | No |  | More options | Area path. Example: `Finance Portal\Payments` |
| Iteration path | `iteration_path` | Text | No |  | More options | Iteration (sprint) path. Example: `Finance Portal\Sprint 42` |
| Tags | `tags` | Text | No |  | More options | Tags separated by semicolons. Example: `agent; invoices` |
| Parent ID | `parent_id` | Number | No |  | More options | Id of a parent work item to link to. |
| Fields | `fields` | Dictionary | No |  | More options | More fields by reference name, e.g. {"Microsoft.VSTS.Common.Priority": 1}. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `id` | Number | Work item id, e.g. 4711. |
| `url` | Text | Web address of the work item. |

*Time limit 60 s; needs: network.*

### Delete work item

`ooa.azure_devops/delete_work_item` (action)

Move a work item to the Recycle Bin, or remove it for good.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Organization address (URL) | `organization_url` | Text | Yes |  | Main | Organization or collection address, e.g. https://dev.azure.com/contoso, or https://devops.contoso.local/tfs/DefaultCollection for Azure DevOps Server. |
| Personal access token | `personal_access_token` | Credential (vault) | Yes |  | Main | Personal access token from the vault, with the scopes this action needs (e.g. Work Items read and write). |
| ID | `id` | Number | Yes |  | Main | Work item id. |
| Permanently | `permanently` | Yes/No | No | No | More options | Remove for good instead of moving to the Recycle Bin. This cannot be undone. |

*Time limit 60 s; needs: network.*

### Get work item

`ooa.azure_devops/get_work_item` (action)

Read a work item by id.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Organization address (URL) | `organization_url` | Text | Yes |  | Main | Organization or collection address, e.g. https://dev.azure.com/contoso, or https://devops.contoso.local/tfs/DefaultCollection for Azure DevOps Server. |
| Personal access token | `personal_access_token` | Credential (vault) | Yes |  | Main | Personal access token from the vault, with the scopes this action needs (e.g. Work Items read and write). |
| ID | `id` | Number | Yes |  | Main | Work item id. Example: `4711` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `work_item` | Dictionary | id, rev, type, title, state, reason, assigned_to, area_path, iteration_path, tags, priority, description, created_by, created_date, changed_date, url, fields (all fields). |
| `state` | Text | Current state, e.g. Active. |

*Time limit 60 s; safe to repeat; needs: network.*

### List work item comments

`ooa.azure_devops/list_work_item_comments` (action)

Read the comments in a work item's discussion, newest first.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Organization address (URL) | `organization_url` | Text | Yes |  | Main | Organization or collection address, e.g. https://dev.azure.com/contoso, or https://devops.contoso.local/tfs/DefaultCollection for Azure DevOps Server. |
| Personal access token | `personal_access_token` | Credential (vault) | Yes |  | Main | Personal access token from the vault, with the scopes this action needs (e.g. Work Items read and write). |
| Project | `project` | Text | Yes |  | Main | Project name, e.g. Finance Portal. |
| ID | `id` | Number | Yes |  | Main | Work item id. |
| Maximum results | `max_results` | Number | No | `200` | More options | Most items to return; pages are read until this many are found. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: id, text, created_by, created_date. |
| `count` | Number | Number of comments returned. |

*Time limit 300 s; safe to repeat; needs: network.*

### Query work items (WIQL)

`ooa.azure_devops/query_work_items` (action)

Find work items with a WIQL query and return their main fields as a table.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Organization address (URL) | `organization_url` | Text | Yes |  | Main | Organization or collection address, e.g. https://dev.azure.com/contoso, or https://devops.contoso.local/tfs/DefaultCollection for Azure DevOps Server. |
| Personal access token | `personal_access_token` | Credential (vault) | Yes |  | Main | Personal access token from the vault, with the scopes this action needs (e.g. Work Items read and write). |
| Project | `project` | Text | Yes |  | Main | Project name, e.g. Finance Portal. |
| Wiql | `wiql` | Text | Yes |  | Main | WIQL query. Only the ids it finds are used; choose columns with extra_fields. Example: `SELECT [System.Id] FROM WorkItems WHERE [System.State] = 'Active' AND [System.AssignedTo] = @Me` |
| Extra fields | `extra_fields` | List | No |  | More options | More field reference names to add as columns, e.g. ["Microsoft.VSTS.Common.Priority"]. |
| Maximum results | `max_results` | Number | No | `1000` | More options | Most items to return; pages are read until this many are found. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: id, type, title, state, assigned_to, changed_date, then any extra fields asked for. |
| `ids` | List | Ids of the work items found. |
| `count` | Number | Number of work items returned. |

*Time limit 300 s; safe to repeat; needs: network.*

### Update work item

`ooa.azure_devops/update_work_item` (action)

Change fields of a work item, such as its state or assignee. Only the inputs you fill in are changed.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Organization address (URL) | `organization_url` | Text | Yes |  | Main | Organization or collection address, e.g. https://dev.azure.com/contoso, or https://devops.contoso.local/tfs/DefaultCollection for Azure DevOps Server. |
| Personal access token | `personal_access_token` | Credential (vault) | Yes |  | Main | Personal access token from the vault, with the scopes this action needs (e.g. Work Items read and write). |
| ID | `id` | Number | Yes |  | Main | Work item id. |
| Title | `title` | Text | No |  | Main | New title. |
| Iteration path | `iteration_path` | Text | No |  | Main | New iteration (sprint) path. |
| State | `state` | Text | No |  | Main | New state, e.g. Active, Resolved, Closed. Example: `Resolved` |
| Assigned to | `assigned_to` | Text | No |  | More options | New assignee by email or display name. |
| Description | `description` | Text | No |  | More options | New description. |
| Tags | `tags` | Text | No |  | More options | Replace tags; separate with semicolons. |
| Steps | `steps` | List | No |  | More options | Test cases only: replace the steps, in order: text, or {"action": ..., "expected": ...}. |
| Fields | `fields` | Dictionary | No |  | More options | More fields by reference name, e.g. {"Microsoft.VSTS.Scheduling.RemainingWork": 2}. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `rev` | Number | New revision number. |

*Time limit 60 s; needs: network.*

## Pipelines

### Create pipeline

`ooa.azure_devops/create_pipeline` (action)

Create a YAML pipeline from a file in an Azure Repos Git repository.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Organization address (URL) | `organization_url` | Text | Yes |  | Main | Organization or collection address, e.g. https://dev.azure.com/contoso, or https://devops.contoso.local/tfs/DefaultCollection for Azure DevOps Server. |
| Personal access token | `personal_access_token` | Credential (vault) | Yes |  | Main | Personal access token from the vault, with the scopes this action needs (e.g. Work Items read and write). |
| Project | `project` | Text | Yes |  | Main | Project name, e.g. Finance Portal. |
| Name | `name` | Text | Yes |  | Main | Pipeline name. |
| Repository ID | `repository_id` | Text | Yes |  | Main | Repository id (a GUID, from List repositories). |
| Yaml path | `yaml_path` | Text | Yes |  | Main | Path of the YAML file in the repository. Example: `/azure-pipelines.yml` |
| Folder | `folder` | Text | No | `\` | More options | Folder for the pipeline, e.g. \Finance. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `pipeline_id` | Number | Id of the new pipeline. |

*Time limit 60 s; needs: network.*

### Delete pipeline

`ooa.azure_devops/delete_pipeline` (action)

Delete a pipeline and its run history. Needs confirm.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Organization address (URL) | `organization_url` | Text | Yes |  | Main | Organization or collection address, e.g. https://dev.azure.com/contoso, or https://devops.contoso.local/tfs/DefaultCollection for Azure DevOps Server. |
| Personal access token | `personal_access_token` | Credential (vault) | Yes |  | Main | Personal access token from the vault, with the scopes this action needs (e.g. Work Items read and write). |
| Project | `project` | Text | Yes |  | Main | Project name, e.g. Finance Portal. |
| Pipeline ID | `pipeline_id` | Number | Yes |  | Main | Pipeline id. Range: 1 to  |
| Confirm | `confirm` | Yes/No | Yes |  | Main | Must be true: a safeguard against deleting by mistake. |

*Time limit 60 s; needs: network.*

### Get pipeline details

`ooa.azure_devops/get_pipeline` (action)

Read a pipeline: name, folder, YAML file and repository.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Organization address (URL) | `organization_url` | Text | Yes |  | Main | Organization or collection address, e.g. https://dev.azure.com/contoso, or https://devops.contoso.local/tfs/DefaultCollection for Azure DevOps Server. |
| Personal access token | `personal_access_token` | Credential (vault) | Yes |  | Main | Personal access token from the vault, with the scopes this action needs (e.g. Work Items read and write). |
| Project | `project` | Text | Yes |  | Main | Project name, e.g. Finance Portal. |
| Pipeline ID | `pipeline_id` | Number | Yes |  | Main | Pipeline id, from List pipelines. Range: 1 to  |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `pipeline` | Dictionary | Keys: id, name, folder, yaml_path, repository, url. |

*Time limit 60 s; safe to repeat; needs: network.*

### Get pipeline run

`ooa.azure_devops/get_pipeline_run` (action)

Read the state and result of a pipeline run.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Organization address (URL) | `organization_url` | Text | Yes |  | Main | Organization or collection address, e.g. https://dev.azure.com/contoso, or https://devops.contoso.local/tfs/DefaultCollection for Azure DevOps Server. |
| Personal access token | `personal_access_token` | Credential (vault) | Yes |  | Main | Personal access token from the vault, with the scopes this action needs (e.g. Work Items read and write). |
| Project | `project` | Text | Yes |  | Main | Project name, e.g. Finance Portal. |
| Pipeline ID | `pipeline_id` | Number | Yes |  | Main | Pipeline id. |
| Run ID | `run_id` | Number | Yes |  | Main | Run id, from Run pipeline. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `state` | Text | inProgress, canceling or completed. |
| `result` | Text | Once completed: succeeded, failed or canceled. |
| `run` | Dictionary | id, name, state, result, created, finished, url. |

*Time limit 60 s; safe to repeat; needs: network.*

### List pipeline runs

`ooa.azure_devops/list_pipeline_runs` (action)

List the recent runs of one pipeline, newest first, optionally only those in a given state or with a given result.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Organization address (URL) | `organization_url` | Text | Yes |  | Main | Organization or collection address, e.g. https://dev.azure.com/contoso, or https://devops.contoso.local/tfs/DefaultCollection for Azure DevOps Server. |
| Personal access token | `personal_access_token` | Credential (vault) | Yes |  | Main | Personal access token from the vault, with the scopes this action needs (e.g. Work Items read and write). |
| Project | `project` | Text | Yes |  | Main | Project name, e.g. Finance Portal. |
| Pipeline ID | `pipeline_id` | Number | Yes |  | Main | Pipeline id, from List pipelines. Example: `12` |
| State | `state` | Text | No |  | Main | Only runs in this state. One of: `inProgress`, `canceling`, `completed` |
| Result | `result` | Text | No |  | Main | Only completed runs with this result. One of: `succeeded`, `failed`, `canceled` |
| Maximum results | `max_results` | Number | No | `100` | More options | Most items to return; pages are read until this many are found. Range: 1 to  |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: id, name, state, result, created, finished, url. |
| `count` | Number | Number of runs returned. |

*Time limit 300 s; safe to repeat; needs: network.*

### List pipelines

`ooa.azure_devops/list_pipelines` (action)

List the pipelines of a project.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Organization address (URL) | `organization_url` | Text | Yes |  | Main | Organization or collection address, e.g. https://dev.azure.com/contoso, or https://devops.contoso.local/tfs/DefaultCollection for Azure DevOps Server. |
| Personal access token | `personal_access_token` | Credential (vault) | Yes |  | Main | Personal access token from the vault, with the scopes this action needs (e.g. Work Items read and write). |
| Project | `project` | Text | Yes |  | Main | Project name, e.g. Finance Portal. |
| Maximum results | `max_results` | Number | No | `200` | Main | Most items to return; pages are read until this many are found. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: id, name, folder, revision. |
| `count` | Number | Number of pipelines returned. |

*Time limit 300 s; safe to repeat; needs: network.*

### Run pipeline

`ooa.azure_devops/run_pipeline` (action)

Start a pipeline run on a branch, with optional parameters and variables, optionally skipping some stages.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Organization address (URL) | `organization_url` | Text | Yes |  | Main | Organization or collection address, e.g. https://dev.azure.com/contoso, or https://devops.contoso.local/tfs/DefaultCollection for Azure DevOps Server. |
| Personal access token | `personal_access_token` | Credential (vault) | Yes |  | Main | Personal access token from the vault, with the scopes this action needs (e.g. Work Items read and write). |
| Project | `project` | Text | Yes |  | Main | Project name, e.g. Finance Portal. |
| Pipeline ID | `pipeline_id` | Number | Yes |  | Main | Pipeline id, from List pipelines. Example: `12` |
| Parameters | `parameters` | Dictionary | No |  | Main | Template parameters, e.g. {"environment": "test"}. |
| Variables | `variables` | Dictionary | No |  | Main | Variables settable at queue time, e.g. {"releaseNote": "Hotfix"}. |
| Branch | `branch` | Text | No |  | More options | Branch to run on. Empty uses the pipeline's default branch. Example: `main` |
| Stages to skip | `stages_to_skip` | List | No |  | More options | Names of YAML stages not to run, as written after 'stage:' in the YAML, e.g. ["DeployProd"]. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `run_id` | Number | Id of the new run; use it with Get pipeline run. |
| `state` | Text | State right after starting, normally inProgress. |
| `url` | Text | Web address of the run. |

*Time limit 60 s; needs: network.*

### Edit pipeline

`ooa.azure_devops/update_pipeline` (action)

Rename a pipeline, move it to another folder, or point it at a different YAML file or Azure Repos repository (the YAML content itself is changed in the repository).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Organization address (URL) | `organization_url` | Text | Yes |  | Main | Organization or collection address, e.g. https://dev.azure.com/contoso, or https://devops.contoso.local/tfs/DefaultCollection for Azure DevOps Server. |
| Personal access token | `personal_access_token` | Credential (vault) | Yes |  | Main | Personal access token from the vault, with the scopes this action needs (e.g. Work Items read and write). |
| Project | `project` | Text | Yes |  | Main | Project name, e.g. Finance Portal. |
| Pipeline ID | `pipeline_id` | Number | Yes |  | Main | Pipeline id. Range: 1 to  |
| Name | `name` | Text | No |  | More options | New name. |
| Folder | `folder` | Text | No |  | More options | New folder, e.g. \Finance. |
| Yaml path | `yaml_path` | Text | No |  | More options | New path of the YAML file in the repository. Example: `/pipelines/release.yml` |
| Repository ID | `repository_id` | Text | No |  | More options | Id (a GUID, from List repositories) of another Azure Repos Git repository that holds the YAML file. |

*Time limit 60 s; needs: network.*

## Builds

### Get build

`ooa.azure_devops/get_build` (action)

Read the status and result of a build.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Organization address (URL) | `organization_url` | Text | Yes |  | Main | Organization or collection address, e.g. https://dev.azure.com/contoso, or https://devops.contoso.local/tfs/DefaultCollection for Azure DevOps Server. |
| Personal access token | `personal_access_token` | Credential (vault) | Yes |  | Main | Personal access token from the vault, with the scopes this action needs (e.g. Work Items read and write). |
| Project | `project` | Text | Yes |  | Main | Project name, e.g. Finance Portal. |
| Build ID | `build_id` | Number | Yes |  | Main | Build id. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `status` | Text | notStarted, inProgress, cancelling or completed. |
| `result` | Text | Once completed: succeeded, partiallySucceeded, failed or canceled. |
| `build` | Dictionary | id, build_number, status, result, definition, branch, requested_for, queued, started, finished, url. |

*Time limit 60 s; safe to repeat; needs: network.*

### List builds

`ooa.azure_devops/list_builds` (action)

List recent builds, newest first, optionally for one definition, branch, status or result.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Organization address (URL) | `organization_url` | Text | Yes |  | Main | Organization or collection address, e.g. https://dev.azure.com/contoso, or https://devops.contoso.local/tfs/DefaultCollection for Azure DevOps Server. |
| Personal access token | `personal_access_token` | Credential (vault) | Yes |  | Main | Personal access token from the vault, with the scopes this action needs (e.g. Work Items read and write). |
| Project | `project` | Text | Yes |  | Main | Project name, e.g. Finance Portal. |
| Definition ID | `definition_id` | Number | No |  | Main | Only builds of this definition. |
| Branch | `branch` | Text | No |  | Main | Only builds of this branch. |
| Status | `status` | Text | No |  | More options | Only builds with this status, e.g. inProgress or completed. Example: `completed` |
| Result | `result` | Text | No |  | More options | Only builds with this result, e.g. succeeded or failed. Example: `failed` |
| Maximum results | `max_results` | Number | No | `50` | More options | Most items to return; pages are read until this many are found. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: id, build_number, status, result, definition, branch, queued, finished. |
| `count` | Number | Number of builds returned. |

*Time limit 300 s; safe to repeat; needs: network.*

### Queue build

`ooa.azure_devops/queue_build` (action)

Queue a build of a build definition (classic or YAML) on a branch.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Organization address (URL) | `organization_url` | Text | Yes |  | Main | Organization or collection address, e.g. https://dev.azure.com/contoso, or https://devops.contoso.local/tfs/DefaultCollection for Azure DevOps Server. |
| Personal access token | `personal_access_token` | Credential (vault) | Yes |  | Main | Personal access token from the vault, with the scopes this action needs (e.g. Work Items read and write). |
| Project | `project` | Text | Yes |  | Main | Project name, e.g. Finance Portal. |
| Definition ID | `definition_id` | Number | Yes |  | Main | Build definition id. Example: `7` |
| Branch | `branch` | Text | No |  | Main | Branch to build. Empty uses the definition's default. Example: `main` |
| Parameters | `parameters` | Dictionary | No |  | More options | Queue-time variables, e.g. {"Configuration": "Release"}. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `build_id` | Number | Id of the queued build. |
| `build_number` | Text | Build number, e.g. 20261020.3. |
| `url` | Text | Web address of the build. |

*Time limit 60 s; needs: network.*

## Projects

### Get project

`ooa.azure_devops/get_project` (action)

Read a project's details.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Organization address (URL) | `organization_url` | Text | Yes |  | Main | Organization or collection address, e.g. https://dev.azure.com/contoso, or https://devops.contoso.local/tfs/DefaultCollection for Azure DevOps Server. |
| Personal access token | `personal_access_token` | Credential (vault) | Yes |  | Main | Personal access token from the vault, with the scopes this action needs (e.g. Work Items read and write). |
| Project | `project` | Text | Yes |  | Main | Project name or id. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `project` | Dictionary | id, name, description, state, visibility, default_team, url. |

*Time limit 60 s; safe to repeat; needs: network.*

### List projects

`ooa.azure_devops/list_projects` (action)

List the projects in the organization or collection.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Organization address (URL) | `organization_url` | Text | Yes |  | Main | Organization or collection address, e.g. https://dev.azure.com/contoso, or https://devops.contoso.local/tfs/DefaultCollection for Azure DevOps Server. |
| Personal access token | `personal_access_token` | Credential (vault) | Yes |  | Main | Personal access token from the vault, with the scopes this action needs (e.g. Work Items read and write). |
| Maximum results | `max_results` | Number | No | `200` | More options | Most items to return; pages are read until this many are found. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: id, name, state, visibility, description, last_update. |
| `count` | Number | Number of projects returned. |

*Time limit 300 s; safe to repeat; needs: network.*

## Boards

### List sprint work items

`ooa.azure_devops/list_iteration_work_items` (action)

Return the ids of the work items planned in a sprint; read details with Get work item or Query work items.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Organization address (URL) | `organization_url` | Text | Yes |  | Main | Organization or collection address, e.g. https://dev.azure.com/contoso, or https://devops.contoso.local/tfs/DefaultCollection for Azure DevOps Server. |
| Personal access token | `personal_access_token` | Credential (vault) | Yes |  | Main | Personal access token from the vault, with the scopes this action needs (e.g. Work Items read and write). |
| Project | `project` | Text | Yes |  | Main | Project name, e.g. Finance Portal. |
| Iteration ID | `iteration_id` | Text | Yes |  | Main | Sprint id (a GUID) from List sprints. |
| Team | `team` | Text | No |  | More options | Team name. Empty uses the project's default team. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `ids` | List | Work item ids in the sprint. |
| `count` | Number | Number of work items. |

*Time limit 60 s; safe to repeat; needs: network.*

### List sprints (iterations)

`ooa.azure_devops/list_iterations` (action)

List a team's sprints, or only the current one.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Organization address (URL) | `organization_url` | Text | Yes |  | Main | Organization or collection address, e.g. https://dev.azure.com/contoso, or https://devops.contoso.local/tfs/DefaultCollection for Azure DevOps Server. |
| Personal access token | `personal_access_token` | Credential (vault) | Yes |  | Main | Personal access token from the vault, with the scopes this action needs (e.g. Work Items read and write). |
| Project | `project` | Text | Yes |  | Main | Project name, e.g. Finance Portal. |
| Current only | `current_only` | Yes/No | No | No | Main | Return only the sprint in progress today. |
| Team | `team` | Text | No |  | More options | Team name. Empty uses the project's default team. Example: `Payments Team` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: id, name, path, start_date, finish_date, time_frame. |
| `count` | Number | Number of sprints. |

*Time limit 60 s; safe to repeat; needs: network.*

## Conditions

Used in If, Loop while and Wait until steps.

### Pipeline run finished

`ooa.azure_devops/pipeline_run_finished` (condition)

True when the pipeline run has completed, whatever its result.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Organization address (URL) | `organization_url` | Text | Yes |  | Main | Organization or collection address, e.g. https://dev.azure.com/contoso, or https://devops.contoso.local/tfs/DefaultCollection for Azure DevOps Server. |
| Personal access token | `personal_access_token` | Credential (vault) | Yes |  | Main | Personal access token from the vault, with the scopes this action needs (e.g. Work Items read and write). |
| Project | `project` | Text | Yes |  | Main | Project name, e.g. Finance Portal. |
| Pipeline ID | `pipeline_id` | Number | Yes |  | Main | Pipeline id. |
| Run ID | `run_id` | Number | Yes |  | Main | Run id. |
