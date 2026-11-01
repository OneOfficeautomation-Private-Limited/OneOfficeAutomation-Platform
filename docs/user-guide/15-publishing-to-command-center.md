# Publishing agents to One CommandCenter

**Audience:** agent builders who are ready to share agents with their team and run them on runners, on schedules and
in workflows; and team leads who approve what goes live.

## Contents

1. [Private and Public: your workspace and the agent repository](#1-private-and-public-your-workspace-and-the-agent-repository)
2. [Connect Studio to One CommandCenter](#2-connect-studio-to-one-commandcenter)
3. [Before you check in](#3-before-you-check-in)
4. [Check in an agent](#4-check-in-an-agent)
5. [Check in workflows and forms](#5-check-in-workflows-and-forms)
6. [Check out, edit and check in again](#6-check-out-edit-and-check-in-again)
7. [Versions and the live production version](#7-versions-and-the-live-production-version)
8. [Approval before production](#8-approval-before-production)
9. [Files the agent needs](#9-files-the-agent-needs)
10. [Run what you published](#10-run-what-you-published)
11. [Templates](#11-templates)
12. [If it goes wrong](#12-if-it-goes-wrong)
13. [Related guides](#related-guides)

---

## 1. Private and Public: your workspace and the agent repository

The **Automation** page has two tabs: **Private**, your own workspace, and **Public**, One CommandCenter's agent
repository.

| | Private (your workspace) | Public (the agent repository) |
| --- | --- | --- |
| Who sees it | Only you | Everyone allowed by roles and folder permissions |
| History | The file as it is now | Every version, with author, date and comment |
| Runs | Your test runs in Studio | Runs on runners, schedules, triggers, work queues, workflows, API calls |
| Secrets | Environment variables for local tests | Credential vault |

Moving an agent from one to the other is called **check-in**. Taking a repository agent into your workspace to change
it is **check-out**; it locks the agent for you so nobody else changes it at the same time.

## 2. Connect Studio to One CommandCenter

Until Studio is signed in, the **Public** tab of the **Automation** page says "Your workspace is private. Sign in to
One CommandCenter to share agents through its agent repository, with check-out and check-in." and shows the sign-in.
The check-in dialog shows the same sign-in when needed.

**When Studio and One CommandCenter run together** (the Private workspace opened from One CommandCenter, at
`/studio/`):

1. Studio says "Your Private workspace and One CommandCenter run together here. Sign in to One CommandCenter in this
   browser, and the workspace uses that sign-in for check-in and check-out."
2. Click **Open One CommandCenter to sign in** and sign in there.
3. Back in Studio, click **I have signed in**.

**When Studio runs on its own:**

1. Fill in **One CommandCenter address** (for example `http://localhost:8720` or your company's address), **User
   name**, **Password**, and **Two-step code** if your account uses two-step sign-in.
2. Click **Sign in** (or press Enter). Studio says "Signed in to One CommandCenter."

Once signed in, the **Public** tab shows the address and who you are signed in as, **Search the agent repository**,
**Columns**, **Refresh** and **Sign out** (or "Signed in through One CommandCenter"), with the repository's
**Folders** on the left and the chosen folder's agents on the right.

## 3. Before you check in

1. Check the agent: **Save** checks it, and **Check now** on the **Problems** tab looks again without saving.
   Check-in refuses agents with validation problems, and your company may also refuse check-ins that break its
   code-analysis rules (passwords typed into steps, empty Catch blocks, no description …).
2. With no step selected (press Esc), fill in **Properties**: a clear **Agent id** and a **Description**.
3. Make inputs, outputs and descriptions clear: people see them when they run the agent.
4. Replace any test values with **Credential** inputs or shared values. Never leave secrets in steps.
5. Look at the **Dependencies** tab in the **Details** panel: the **Child agents and agent tools** must be in the
   repository too (the check-in dialog lists them under **Agents it runs**), and note the **Files** the agent uses
   (see section 9).
6. Test it once more with **Run**.

## 4. Check in an agent

1. Open the agent in Studio.
2. Click **Check in** at the top of the editor (or, once signed in, **Check in…** in the agent's **⋯** menu on the
   **Private** tab). Unsaved changes are saved first.
3. The **Check in <name>** dialog opens (asking you to sign in first when needed, see section 2). Type a short comment
   in **What changed** (shown in the version history).
4. Decide on **Make this the live version (runs, schedules and triggers use it from now on)**. It is off by default.
5. When the agent runs other agents, the dialog lists them under **Agents it runs**: new ones are checked in first,
   with the same comment, and you can tick ones checked out to you to check them in too.
6. Press **Check in**.

| Tick | What happens | Message |
| --- | --- | --- |
| Off (default) | A new version only. The live version stays as it was. Make it live later in One CommandCenter (section 7). | "Checked in <path> as version n; the live version is unchanged." |
| On | The new version becomes the **live production version** at once. Schedules, triggers and workflows use it from their next run. Needs manage access on the folder. | "…; it is now the live version." |
| On, with approval before production | A **production request** instead (see section 8) | "…; it waits for approval before it goes live." |

The agent appears in the repository list with its version (`v3`).

> **Note:** An agent's first check-in always becomes its live version, tick or not, so that it can run. The tick
> matters from the second version on.

The repository path is the agent's path in your workspace. An agent you checked out goes back to the path it came from.

If the check-in is refused, Studio says why ("Not checked in: …"). For validation problems, press **Check now** on the
**Problems** tab to list them, fix them and check in again.

## 5. Check in workflows and forms

Workflows and forms are checked in like agents, from the **Private** tab of the **Automation** page, once Studio is
signed in (section 2):

1. Find the workflow (or form) in its folder.
2. Choose **Check in…** in its **⋯** menu (or tick several items, up to 10, and press **Check in…** in the bar above
   the list).
3. Type what changed, tick **Make this the live version** so that it can be started, and press **Check in**. Studio
   says "Checked in <path> as version n; it is now the live version."

Check in the forms a workflow uses and the agents it runs as well. People then start the workflow from **Manage >
Workflow** in One CommandCenter, and get their steps in the **Task inbox** (see [Business users](14-business-users.md)).

## 6. Check out, edit and check in again

On the **Public** tab of Studio's **Automation** page, each repository agent shows its **Latest** and **Live**
versions and who has it **Checked out**:

| Button | What it does |
| --- | --- |
| **Check out** | Locks the agent for you and copies the latest version into your workspace, into the same folder as in the repository, and opens it. Studio says "Checked out vn into your workspace: <path>." Agents it runs that you do not have yet are copied too (not checked out). |
| **Open my copy** | Opens your checked-out copy as it is (when you hold the lock). |
| **Undo check-out…** | In the row's **⋯** menu: releases the lock without a new version, after asking ("Check-out undone. Your private copy stays in the workspace."). |
| **Versions** | In the row's **⋯** menu: every version with **Version** ("live" for the live one), **By**, **When** and **What changed**. |
| **Check out a version…** | In the row's **⋯** menu: check out an earlier version, for example to bring it back. |

"By jane" under **Checked out** means someone else is working on it; you can still look at **Versions**. Agents you
have a private copy of show a **private copy** tag (hover for "Private copy: <path>").

Typical cycle:

1. **Check out**.
2. Change the agent, save, test.
3. **Check in** with a comment. The lock is released.

## 7. Versions and the live production version

Every check-in is a new version. Only one version is **live in production** at a time, and runs, schedules, triggers,
work queues and workflows always use it. A check-in changes the live version only when you tick **Make this the live
version** (agents) or publish a workflow or form.

In One CommandCenter (**Build > Automation**, click an agent, its **Versions** tab):

- each version with its **Labels**, **Comment**, **By** and **When**; a **Live** tag marks the live one;
- **Make live** makes an older or newer version live (or **Ask to make live**, when approval is on);
- **Label** puts a label such as `UAT` or `Release-3` on a version (a label sits on one version at a time); runs and
  exports can ask for an agent by label;
- tick two versions and click **Compare** to see the differences line by line.

Going back to an older version is therefore one click: make it live again.

## 8. Approval before production

When an administrator switches on approval before production (**Administration > Settings > Agents and repository**,
**Approval before production**):

1. Your check-in with **Make this the live version** ticked (or **Ask to make live** on the **Versions** tab) creates a
   **production request** instead of changing the live version. Studio says "…; it waits for approval before it goes
   live." (One CommandCenter says "Sent vn of <agent> for approval.")
2. A second person with the approval right opens **Production approvals** in the **Manage ▾** menu of **Build >
   Automation** and clicks **Approve** (the version becomes live: "vn of <agent> is live.") or **Reject**, with an
   optional **Note**. You can **Withdraw** your own request.
3. Nobody can decide their own request. Every step is in Audit Trail.

Until then, runs keep using the version that was live before.

## 9. Files the agent needs

Workbooks, templates and lists that your agent opens must be on the runner too:

1. In One CommandCenter, open **Build > Automation**, choose the folder, and in **Files** below its agents press
   **Upload a file** (up to 25 MB; uploading again makes a new version).
2. The agent lists the files it needs (its `requires.files`, for example `Finance/Templates/invoice.xlsx`). Before each
   run, the runner places each file at the same path in its workspace, so the agent opens it by that path.

Keep the same folder layout in your Private workspace so the agent works the same in Studio and on runners.

## 10. Run what you published

From One CommandCenter (details in the [Operators guide](05-operators-guide.md)):

- **Automation > Run…**: choose a runner or a runner pool, and give the inputs. Credential inputs are vault
  references.
- **Schedules**: every N minutes, daily or weekly, in a time zone, with a holiday calendar.
- **Triggers**: files, windows, processes, hotkeys on runners; email, ServiceNow and web polling on the server;
  webhooks.
- **Work queues**: one run per work item.
- **Workflow**: agents as stages beside people's tasks and approvals.
- **Agent Activity** (**In progress** and **Completed**): follow and inspect every run, with the step timeline, the
  log, the variables and the error with its suggested fix.

People can also start agents from the assistant in the notification area (see [Business users](14-business-users.md)).

## 11. Templates

**Build > Agent Templates** in One CommandCenter lists ready-made agents to install into the repository and adapt:

- *Sort support tickets with AI* (Customer service)
- *Collect invoice emails*, *Invoice summary in Excel*, *Overdue invoice report* (Finance)
- *Letters from PDF invoices* (Documents)
- *REST API data into a database* (IT)

Search them or pick a folder (the catalogue's categories are the first folders), then choose **Install…** in a
template's **⋯** menu, or open it and press **Install** (needs the import permission). The agents go into a folder
(`Templates` by default); every agent is checked first, nothing is installed unless all pass, and agents already there
are kept. Then **Check out** the installed agent on Studio's **Public** tab, adapt it (addresses, files, credentials),
test it and check it in.

Without internet access, an administrator downloads `templates.json` from the releases page and uses **Upload a
catalogue file** (in **Manage ▾**). On the SaaS service, **Share an agent** (also in **Manage ▾**) offers one of your
agents to other companies in your region once the operators approve it.

For a quick start inside Studio, **Create ▾ > Agent** offers smaller patterns in its **Start from** list (blueprints)
that are built into Studio itself (see [Studio basics](02-studio-basics.md)).

## 12. If it goes wrong

| What you see | Cause and fix |
| --- | --- |
| "Sign in to One CommandCenter first." in the check-in dialog | Sign in right there in the dialog (section 2). |
| "Not checked in: …" because of validation problems | Press **Check now** on the **Problems** tab, fix what it lists and check in again. |
| `CR.POLICY_VIOLATION` | The code-analysis policy refused it; the message lists the findings (for example a password typed into a step). |
| `CR.LOCKED` | Someone else has the agent checked out. Ask them to check it in or undo the check-out. |
| `CR.NOT_CHECKED_OUT` | Check the agent out before checking it in again. |
| `CR.DUPLICATE_ID` | Another agent in the repository already has this agent id. Select no step and change **Agent id** under **Properties**. Copies made with **Copy** or **Duplicate** get an id of their own. |
| `CR.FORBIDDEN` | Your role or the folder permissions do not allow it. Ask an administrator. |
| A runner fails with a missing child agent or file | Check in the child agents; upload the files to the repository and list them in the agent's required files. |
| Runs still use the old version | You checked in without **Make this the live version**, the new version is waiting for approval, or another version was made live. Look at **Versions** and use **Make live**. |
| `CR.NO_PRODUCTION` ("has no live production version") | The agent has no live version (for example a request for production is still waiting for approval). Use **Make live** in **Versions**, or approve the request. |

## Related guides

- [Studio basics](02-studio-basics.md)
- [Running and debugging](04-studio-running-and-debugging.md)
- [Child agents](09-child-agents.md)
- [Cloud and business apps](12-cloud-and-business-apps.md) (schedules example)
- [Business users](14-business-users.md)
- [Operators guide](05-operators-guide.md)
- [Administrators guide](06-administrators-guide.md)
