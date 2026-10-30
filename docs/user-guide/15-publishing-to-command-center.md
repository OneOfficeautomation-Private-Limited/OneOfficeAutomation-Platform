# Publishing agents to One Command Center

**Audience:** agent builders who are ready to share agents with their team and run them on runners, on schedules and
in workflows; and team leads who approve what goes live.

## Contents

1. [My Workspace and the agent repository](#1-my-workspace-and-the-agent-repository)
2. [Connect Studio to One Command Center](#2-connect-studio-to-one-command-center)
3. [Before you check in](#3-before-you-check-in)
4. [Check in an agent](#4-check-in-an-agent)
5. [Publish workflows and forms](#5-publish-workflows-and-forms)
6. [Check out, edit and check in again](#6-check-out-edit-and-check-in-again)
7. [Versions and the live production version](#7-versions-and-the-live-production-version)
8. [Approval before production](#8-approval-before-production)
9. [Files the agent needs](#9-files-the-agent-needs)
10. [Run what you published](#10-run-what-you-published)
11. [Templates](#11-templates)
12. [If it goes wrong](#12-if-it-goes-wrong)
13. [Related guides](#related-guides)

---

## 1. My Workspace and the agent repository

| | My Workspace (Studio) | Agent repository (One Command Center) |
| --- | --- | --- |
| Who sees it | Only you | Everyone allowed by roles and folder permissions |
| History | The file as it is now | Every version, with author, date and comment |
| Runs | Your test runs in Studio | Runs on runners, schedules, triggers, work queues, workflows, API calls |
| Secrets | Environment variables for local tests | One Agent Vault |

Moving an agent from one to the other is called **check-in**. Taking a repository agent into your workspace to change
it is **check-out**; it locks the agent for you so nobody else changes it at the same time.

## 2. Connect Studio to One Command Center

The **Command Center** section is at the bottom of the Explorer. Its heading shows who you are connected as, or
"(not connected)".

**When Studio and One Command Center run together** (Studio opened from One Command Center, at `/studio/`):

1. Studio says "Agent Studio and Command Center run together here. Sign in to Command Center in this browser and Agent
   Studio uses that sign-in for check-in and check-out."
2. Click **Open Command Center to sign in** and sign in there.
3. Back in Studio, click **I have signed in**.

**When Studio runs on its own:**

1. Fill in **Address** (for example `http://localhost:8720` or your company's address), **User name**, **Password**,
   and **Two-step code** if your account uses two-step sign-in.
2. Click **Connect** (or press Enter). The toast says "Connected to Command Center".

Once connected, the section shows the address, **Search the agent repository**, the list of repository agents,
**Refresh** and **Disconnect** (or "Signed in through Command Center").

## 3. Before you check in

1. **Validate** the agent. Check-in refuses agents with validation problems, and your company may also refuse check-ins
   that break its code-analysis rules (passwords typed into steps, empty Catch blocks, no description …).
2. Fill in **Agent settings**: a clear **Agent id**, **Name** and **Description**.
3. Make inputs, outputs and descriptions clear: people see them when they run the agent.
4. Replace any test values with **Credential** inputs or shared values. Never leave secrets in steps.
5. Look at the **Dependencies** tab: check in every **child agent** too, and note the **Files** the agent uses (see
   section 9).
6. Test it once more with **Run**.

## 4. Check in an agent

1. Open the agent in Studio.
2. Click **Agent check-in** in the Explorer toolbar. Unsaved changes are saved first.
3. The **Agent check-in** dialog opens. Type a short comment in **What changed (shown in the version history)**.
4. Decide on **Make this the live version (runs, schedules and triggers use it from now on)**. It is off by default.
5. Press **Check in**.

| Tick | What happens | Toast |
| --- | --- | --- |
| Off (default) | A new version only. The live version stays as it was. Make it live later in One Command Center (section 7). | "Checked in <path> as version n; the live version is unchanged" |
| On | The new version becomes the **live production version** at once. Schedules, triggers and workflows use it from their next run. Needs manage access on the folder. | "…; it is now the live version" |
| On, with approval before production | A **production request** instead (see section 8) | "…; sent for approval before it goes live" |

The agent appears in the repository list with its version (`v3`).

> **Note:** An agent's first check-in always becomes its live version, tick or not, so that it can run. The tick
> matters from the second version on.

The repository path is the agent's path in your workspace. An agent you checked out goes back to the path it came from.

If the check-in is refused for validation problems, Studio opens the **Problems** tab so you can fix them.

## 5. Publish workflows and forms

Workflows and forms are published from their designers:

1. Open the workflow (or form) in Studio.
2. Click **Publish to Command Center** at the top right ("Check in to the Command Center as the live production
   version").
3. Type what changed and press OK. The toast says "Published <path> as version n".

Publish the forms a workflow uses and check in the agents it runs as well. People then start the workflow from **One
Workflow** in One Command Center, and get their steps in the Task inbox (see [Business users](14-business-users.md)).

## 6. Check out, edit and check in again

In the Command Center section of the Explorer, each repository agent shows its path, its latest version, and who has it
checked out:

| Button | What it does |
| --- | --- |
| **Agent check-out** | Locks the agent for you and copies the latest version into your workspace, under the `Control Room` folder. The toast says "Checked out vn into My Workspace: <path>". |
| **Open my copy** | Opens your checked-out copy (when you hold the lock). |
| **Undo check-out** | Releases the lock without a new version ("Your private copy stays in the workspace"). |
| **Versions** | Lists every version: number, "(production)" for the live one, author, date and comment. |

"Checked out by jane" means someone else is working on it; you can still look at **Versions**. Your private copy shows
as "Private copy: <path>".

Typical cycle:

1. **Agent check-out**.
2. Change the agent, save, test.
3. **Agent check-in** with a comment. The lock is released.

## 7. Versions and the live production version

Every check-in is a new version. Only one version is **live in production** at a time, and runs, schedules, triggers,
work queues and workflows always use it. A check-in changes the live version only when you tick **Make this the live
version** (agents) or publish a workflow or form.

In One Command Center (**Agent repository**, an agent > **Versions**):

- each version with its **Labels**, **Author**, **Date** and **Comment**; "(production)" marks the live one;
- **Set as production** makes an older or newer version live (or **Ask to make live**, when approval is on);
- **Label** puts a label such as `UAT` or `Release-3` on a version (a label sits on one version at a time); runs and
  exports can ask for an agent by label;
- tick two versions and click **Compare selected** to see the differences line by line.

Going back to an older version is therefore one click: set it as production again.

## 8. Approval before production

When an administrator switches on approval before production (Administration > Settings > Security):

1. Your check-in with **Make this the live version** ticked (or **Ask to make live**, or **Publish to Command
   Center**) creates a **production request** instead of changing the live version. The
   toast says "…; sent for approval before it goes live" (or "Sent for approval: vn").
2. A second person with the approval right opens **Production approvals** in the Agent repository and clicks **Approve**
   (the version becomes live: "vn is live") or **Reject**, with an optional note.
3. Nobody can decide their own request. Every step is in One Audit Trail.

Until then, runs keep using the version that was live before.

## 9. Files the agent needs

Workbooks, templates and lists that your agent opens must be on the runner too:

1. In One Command Center's Agent repository, open the folder and use **Upload file** (up to 25 MB; uploading again makes
   a new version).
2. The agent lists the files it needs (its `requires.files`, for example `Finance/Templates/invoice.xlsx`). Before each
   run, the runner places each file at the same path in its workspace, so the agent opens it by that path.

Keep the same folder layout in My Workspace so the agent works the same in Studio and on runners.

## 10. Run what you published

From One Command Center (details in the [Operators guide](05-operators-guide.md)):

- **Agent repository > Run**: choose a runner, a runner pool or run-as users, and give the inputs. Credential inputs are
  vault references.
- **Schedules**: every N minutes, daily or weekly, in a time zone, with a holiday calendar.
- **Triggers**: files, windows, processes, hotkeys on runners; email, ServiceNow and web polling on the server;
  webhooks.
- **Work queues**: one run per work item.
- **One Workflow**: agents as stages beside people's tasks and approvals.
- **Agent Runs > Live** and **History**: follow and inspect every run, with the step timeline, the log, the variables
  and the error with its suggested fix.

People can also start agents from the assistant in the notification area (see [Business users](14-business-users.md)).

## 11. Templates

**Build > Templates** in One Command Center lists ready-made agents to install into the repository and adapt:

- *Sort support tickets with AI* (Customer service)
- *Collect invoice emails*, *Invoice summary in Excel*, *Overdue invoice report* (Finance)
- *Letters from PDF invoices* (Documents)
- *REST API data into a database* (IT)

Search them or filter by category, then click **Install** (needs the import permission). The agents go into a folder
(`Templates` by default); every agent is checked first, nothing is installed unless all pass, and agents already there
are kept. Then **Agent check-out** the installed agent in Studio, adapt it (addresses, files, credentials), test it and
check it in.

Without internet access, an administrator downloads `templates.json` from the releases page and uses **Upload a
catalogue file**. On the SaaS service, **Share an agent** offers one of your agents to other companies in your region
once the operators approve it.

For a quick start inside Studio, **+ Create > Agent from a blueprint** offers smaller patterns that are built into Studio
itself (see [Studio basics](02-studio-basics.md)).

## 12. If it goes wrong

| What you see | Cause and fix |
| --- | --- |
| "Connect to Command Center first (bottom of the Explorer)." | Connect in the Command Center section. |
| "Open the agent you want to check in." | Open the agent on the canvas first. |
| The check-in is refused with validation problems | The Problems tab opens; fix them and check in again. |
| `CR.POLICY_VIOLATION` | The code-analysis policy refused it; the message lists the findings (for example a password typed into a step). |
| `CR.LOCKED` | Someone else has the agent checked out. Ask them to check it in or undo the check-out. |
| `CR.NOT_CHECKED_OUT` | Check the agent out before checking it in again. |
| `CR.DUPLICATE_ID` | Another agent in the repository already has this agent id. Change **Agent id** in Agent settings. |
| `CR.FORBIDDEN` | Your role or the folder permissions do not allow it. Ask an administrator. |
| A runner fails with a missing child agent or file | Check in the child agents; upload the files to the repository and list them in the agent's required files. |
| Runs still use the old version | You checked in without **Make this the live version**, the new version is waiting for approval, or another version was set as production. Look at **Versions** and use **Set as production**. |
| `CR.NO_PRODUCTION` ("has no live production version") | The agent has no live version (for example a request for production is still waiting for approval). Use **Set as production** in **Versions**, or approve the request. |

## Related guides

- [Studio basics](02-studio-basics.md)
- [Running and debugging](04-studio-running-and-debugging.md)
- [Child agents](09-child-agents.md)
- [Cloud and business apps](12-cloud-and-business-apps.md) (schedules example)
- [Business users](14-business-users.md)
- [Operators guide](05-operators-guide.md)
- [Administrators guide](06-administrators-guide.md)
