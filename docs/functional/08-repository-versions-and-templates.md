# 08 Repository, versions and templates

## What this covers

The Command Center's agent repository: the public, versioned store of agents, workflows, forms and their files. This
document specifies folders and folder permissions, check-out and check-in, versions and their checksums, labels, the
live production version and approval before production, delete, recover, move, copy and rename, repository files,
dependencies, code analysis, the Git mirror, moving agents between Command Centers, and the templates catalogue.

## Contents

1. Workspace and repository
2. Folders and folder permissions
3. Check-out and check-in
4. Versions
5. Labels
6. The live production version
7. Approval before production
8. Delete, recover, move, copy and rename
9. Files in the repository
10. Dependencies
11. Code analysis
12. Git sync
13. Export, import and promote (lifecycle)
14. Templates
15. Minutes saved
16. Permissions and audit

## 1. Workspace and repository

| | Studio workspace (My Workspace) | Agent Repository |
| --- | --- | --- |
| Where | The designer's computer | The Command Center database |
| Who sees it | Only the designer | Everyone allowed by role and folder permissions |
| History | One working copy per item (deleted items go to the workspace trash) | Every version kept |
| What runs from it | Studio test runs | Every Command Center run (live production version) |

Repository paths are `Folder/Sub-folder/Name.flow.json` (or `.process.json`, `.form.json`); `..` and other invalid
paths are refused (`CR.BAD_PATH`).

## 2. Folders and folder permissions

- A folder is the path before an item's name; **New folder** creates one even when empty.
- An administrator (`repo.permissions`) can give a folder permissions: users, roles or teams each get one level.

| Level | Allows |
| --- | --- |
| View | See the items and export them |
| Run | Also run and schedule them, and set triggers |
| Edit | Also check out, check in, delete, recover and move |
| Manage | Also set the live production version and labels |

How they apply:

- The nearest folder with permissions decides; a sub-folder without its own follows its parent.
- A folder with permissions shuts out everyone it does not name. Where no folder up to the root has permissions, the
  roles alone decide.
- Role permissions still apply: folder access never grants what the role does not allow.
- People with `repo.permissions` see everything. An empty permission list removes the folder's own permissions.
- A refused action returns `CR.FORBIDDEN`.

## 3. Check-out and check-in

| Action | Rule |
| --- | --- |
| Check-out | Locks a public item for the person and copies its latest version into their workspace. Refused while someone else has it (`CR.LOCKED`, naming who and since when). Others see "Checked out by NAME". Needs folder access edit. |
| Check-in | Validates the item ([document 01](01-agents-and-automation-items.md), section 11; workflows and forms by their own rules) and applies the code analysis policy. Problems block it (`CR.INVALID_BOT`, with the list). An existing item must be checked out by the same person (`CR.NOT_CHECKED_OUT`). Stores a new version with a comment and releases the lock. Needs a Developer or Citizen Developer seat while a licence is in force. |
| Check-in as production | Also makes the new version live (needs folder access manage), or asks for approval when approval before production is on. Studio's **Agent check-in** does this only when **Make this the live version** is ticked (off by default); **Publish to Command Center** for workflows and forms always does. |
| Undo check-out | Releases the lock without a new version; by the person who has it, or someone with `repo.production` |
| Publish to Command Center (workflows, forms) | Checks the item in as the live production version |
| First check-in | Creates the item at version 1 |

An agent's id must be unique across the repository (`CR.DUPLICATE_ID`). Checking in at the path of a deleted item is
refused until it is recovered (`CR.DELETED`).

## 4. Versions

- Versions are numbered from 1 and never changed. Each keeps its content, a SHA-256 checksum, comment, author and time.
- Runs, editors and exports read a version only if its content still matches its checksum (`CR.AGENT_TAMPERED` otherwise).
- A version can be asked for as `latest`, `production`, a number, or a label.
- The version list shows each version's labels.

## 5. Labels

- **Versions > Label** puts a label on a version; labels use letters, digits, `.`, `_`, `-` (up to 40). `latest` and
  `production` are reserved.
- A label is on one version of an item at a time; labelling another version moves it.
- Runs, child agents and exports can name an item by label (`CR.NOT_FOUND` when there is none).
- Labelling needs `repo.checkin` and folder access manage.

## 6. The live production version

- One version of each item can be live. Runs, schedules, triggers, work queues, Agent APIs, tests and workflows always
  use it; child agents use their own live version unless the step names another.
- Setting it needs `repo.production` and folder access manage. An item without one cannot run (`CR.NO_PRODUCTION`).
- Changes are audited (`repo.set_production`) and reported to the Git mirror.

## 7. Approval before production

When **Approval before production** is on (Administration > Settings > Security, setting `production_approval`):

- Check-in "as production", **Ask to make live** in the version list and imports as production create a **request**
  instead of making the version live. Setting the live version directly is refused (`CR.APPROVAL_REQUIRED`).
- A person with `repo.approve` (administrators, or a custom role) approves or rejects, with an optional note. Nobody
  decides their own request. Approving makes the version live.

| Request state | Meaning |
| --- | --- |
| `pending` | Waiting for a decision |
| `approved` | Approved; the version is live |
| `rejected` | Rejected with a note |
| `withdrawn` | Taken back by the requester |
| `replaced` | A newer request for the same item replaced it |

- **Production approvals** in the agent repository lists requests the person can see (folders they can view), pending
  first. Asking needs `repo.checkin` and edit access; deciding needs `repo.approve`. Every step is audited.

## 8. Delete, recover, move, copy and rename

| Action | Rule |
| --- | --- |
| Delete | Removes the item from the list and stops it running; versions are kept. Refused while someone else has it checked out, or while a schedule, trigger, work queue or API task uses it (`CR.IN_USE`, listing them, for example "API task NAME"). The answer names agents that call it. |
| Deleted agents / Recover | Lists deleted items and brings one back with its versions (needs `repo.delete`) |
| Move | Moves one item to another folder with its versions, labels, analysis reports, the schedules, triggers, queues and Agent APIs that run it, its run history and workflow runs. Refused while someone else has it checked out, when an item exists at the target, or while a workflow names it by path. Needs edit access on both folders. |
| Copy | Makes a new item from the latest version: version 1, not live, agent id = old id + `_copy` (numbered if taken), name "Copy of …". The kind of file is kept. Schedules and history are not copied. |
| Rename folder | Renames or moves a folder with everything in it and below, including files, permissions and run history. Refused while someone else has an item in it checked out, or a workflow names an item in it by path. |
| Delete folder | Only an empty folder (no items, deleted ones included, and no files) |

## 9. Files in the repository

- Templates, workbooks, lists and pictures live next to agents, in the same folders and under the same permissions.
- **Upload file** adds a file of up to 25 MB; uploading the same name again makes a new version. Download and Delete are
  on each row. Agent files are checked in from Studio instead.
- With `block_executable_uploads` on (Settings), files ending `.exe`, `.dll`, `.msi`, `.bat`, `.cmd`, `.com`, `.scr`,
  `.ps1`, `.vbs` or `.jar` are refused.
- An agent lists the files it needs in `requires.files`. The runner places each at the same path in its workspace before
  the run (up to 50 MB per run). The run's person must be allowed to view each file's folder; a missing or forbidden
  file fails the run before it starts.

## 10. Dependencies

An item's details show the agents it calls (Run Agent steps and AI agent tools), the agents that call it, and the
toolkits it uses.

## 11. Code analysis

**Agent Repository > Code analysis** sets the policy: a set of rules, each on or off and an error or a warning.

| Rule |
| --- |
| Passwords, tokens or keys typed into steps |
| Forbidden actions or toolkits |
| No Try/Catch in larger agents |
| Empty Catch blocks |
| No description |
| Variable naming |
| Unused variables |
| Switched-off steps |
| Long fixed delays |
| Too many steps |
| Deep nesting |

- Every check-in is analysed and the report is kept with that version.
- With **Refuse check-ins that have errors**, a check-in with error findings is refused (`CR.POLICY_VIOLATION`, with the findings).
- An agent's **Code analysis** shows its report and can analyse again; a report for every agent can be downloaded.
- Changing the policy needs `settings.manage`; analysing needs `repo.view`.
- API: `POST /api/repo/analyze` with `{path, version}` (or `{flow}` for an agent not checked in yet),
  `GET /api/repo/analysis?path=&version=` (the stored report), `GET /api/repo/analysis/report` (every agent, CSV), and
  `GET`/`PUT /api/repo/policy`.

## 12. Git sync

Administration > Settings > Git sync mirrors the repository to a branch of a Git server (GitHub, GitLab, Azure DevOps
or any Git server).

| Setting | Rule |
| --- | --- |
| Remote (`git_remote_url`) | An `https://` address (or `file:` for tests) |
| Branch (`git_branch`) | Default `main`; letters, digits, `.`, `_`, `/`, `-` |
| User name, token (`git_token_ref`) | The token is a vault reference |
| Push after every check-in (`git_auto_push`) | On or off; otherwise **Save and sync now** |

The mirror holds the latest version of every item as indented JSON under `agents/`, and `ooa-repository.json` listing
each item's latest and live version. Each change (check-in, live version, delete) becomes a commit with its comment and
author. The Command Center stays the source of truth: changes made in Git are overwritten at the next sync.

## 13. Export, import and promote (lifecycle)

| Action | Rule |
| --- | --- |
| Export | A bundle of the ticked items at a version (production, latest, a number or a label) and optionally every agent they call. Each item's content carries its SHA-256; the bundle has a checksum. Needs `lifecycle.export` and view access. |
| Import | Checks the bundle and validates every item as check-in does, with folder permissions. Stores all of them or none. An existing item is skipped or gets a new version; history is never replaced. Can place items under another folder and make the versions live (or request approval). Needs `lifecycle.import`. |
| Promote | Export and import in one step against another Command Center, using an API key of someone allowed to import there, kept in a vault folder here; the key is read from the vault and sent only to that address |

## 14. Templates

**Build > Templates** lists ready-made agents to install and adapt.

| Source | Rules |
| --- | --- |
| ONEOFFICEAUTOMATION catalogue | Published with each release (`templates.json`) and signed by ONEOFFICEAUTOMATION; a changed catalogue is refused as a whole. Setting `template_catalog_url` names another `https://` address, or `off`. Without internet, **Upload a catalogue file** (needs `settings.manage`). |
| The region's shared templates (SaaS only) | **Share an agent** offers one agent's live version and the agents it calls to the other companies of the region. It appears once the service's operators approve it. Only agents are shared, never credentials, shared values or runs. Sharing needs `lifecycle.export`. |

**Install** places the template's agents under a folder (default `Templates`) as an import does: every agent is
checked first, nothing is installed unless all pass, and agents already there are kept. Installing needs
`lifecycle.import`; seeing templates needs `repo.view`.

## 15. Minutes saved

Each agent can record the minutes of manual work one successful run saves (setting it needs `repo.production`). Home
and Operations show the hours saved; agents without a value use `default_minutes_saved` from Settings.

## 16. Permissions and audit

| Action | Permission | Folder access |
| --- | --- | --- |
| See the repository, versions, dependencies, templates | `repo.view` | view |
| Check out, check in, undo own check-out, label, move, upload files, create folders, ask for production | `repo.checkin` | edit (label: manage) |
| Delete, recover, delete files and folders, see deleted items | `repo.delete` | edit |
| Set the live version, minutes saved, undo others' check-outs | `repo.production` | manage |
| Approve production requests | `repo.approve` | |
| Set folder permissions | `repo.permissions` | |
| Export, promote, share templates | `lifecycle.export` | view |
| Import, install templates | `lifecycle.import` | edit |

Audited: `repo.checkout`, `repo.undo_checkout`, `repo.checkin`, `repo.import`, `repo.set_production`, `repo.delete`,
`repo.recover`, `repo.move`, `repo.copy`, `repo.label`, `repo.unlabel`, production request steps, folder and
permission changes, file uploads and deletes.

## Related documents

- [01 Agents and automation items](01-agents-and-automation-items.md)
- [10 Users, roles, teams and security](10-users-roles-teams-and-security-features.md)
- [13 Licensing and editions](13-licensing-and-editions.md)
- [14 One AgentStudio](14-studio-features.md)
- [Command Center and runner, sections 4 and 10i](../user-guide/00-README.md)
