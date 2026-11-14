# Getting started

**Audience:** everyone, on their first day with ONEOFFICEAUTOMATION. Administrators and builders will follow every
step; business users and operators can skip the install part if someone has already set One CommandCenter up.

**What you will have at the end:** ONEOFFICEAUTOMATION running on one computer, your own administrator password, a
tour of the menu, and a first agent that you built in OneAgentStudio, checked in to One CommandCenter, ran on a
runner and looked at in **Agent Activity**.

## Contents

1. [Before you start](#1-before-you-start)
2. [Install on one computer](#2-install-on-one-computer)
3. [Start ONEOFFICEAUTOMATION](#3-start-oneofficeautomation)
4. [Signing in for the first time](#4-signing-in-for-the-first-time)
5. [Finding your way: the screen and the menu](#5-finding-your-way-the-screen-and-the-menu)
6. [Your first agent, end to end](#6-your-first-agent-end-to-end)
7. [The evaluation and your licence](#7-the-evaluation-and-your-licence)
8. [If it goes wrong](#8-if-it-goes-wrong)
9. [Where to go next](#9-where-to-go-next)
10. [Appendix: run from a source checkout](#appendix-run-from-a-source-checkout)
11. [Related guides](#related-guides)

## 1. Before you start

You need:

- A Windows 10 or 11 computer, or Windows Server, where you may install programs. Agents that drive the desktop
  need a Windows runner; One CommandCenter itself also runs on Linux (see [Installation on Linux and in containers](../infrastructure/03-installation-linux-and-containers.md)).
- One of the install packages from your ONEOFFICEAUTOMATION releases page:

  | Package | Contains | Choose it when |
  | --- | --- | --- |
  | `ooa-setup-<version>.zip` | The product with its own Java, and a guided installer (`Install.cmd`) | Almost always. It installs, adds a Start menu entry and can set up a service. |
  | `ooa-<version>.msi` | The same, as an MSI for all users | Your IT team deploys software with SCCM, Intune or Group Policy |
  | `ooa-customer-<version>.zip` | The product without an installer | You want to try it without installing; you need Java 21 or later on the computer |

- A free port, 8720 by default. The browser opens `http://localhost:8720`.
- About 1 GB of free disk space, more for the data that builds up.

> **Tip.** For a first look, one computer does everything: One CommandCenter, Studio and a runner. Later you move
> One CommandCenter to a server and add runners on other machines ([07 Runner guide](07-runner-guide.md)).

## 2. Install on one computer

### With the setup package (recommended)

1. Unzip `ooa-setup-<version>.zip` into a temporary folder.
2. Double-click **Install.cmd**.
3. When asked what this computer is, choose **One CommandCenter**.
4. Accept the suggested install folder or type another. Without administrator rights the product goes to your own
   Programs folder (`%LOCALAPPDATA%\Programs\ONEOFFICEAUTOMATION`); with `-AllUsers` it goes to Program Files.
5. Setup first checks the computer with `bin\ooa.bat check` (Java, memory, free disk space, the port, the data folder,
   the vault key, the database and HTTPS). Each line says **PASS**, **WARN** or **FAIL**. A FAIL stops the install
   unless you add `-SkipCheck`.
6. When setup finishes, the Start menu has an **ONEOFFICEAUTOMATION** entry. Setup writes a log to
   `%TEMP%\ooa-install.log`.

If you are asked about a database: the built-in database is fine for an evaluation. A licensed install runs on
Microsoft SQL Server (PostgreSQL also works); your administrator sets that up later
([06 Administrator's guide](06-administrators-guide.md#licences-and-seats)).

### With the zip only

1. Unzip `ooa-customer-<version>.zip`.
2. Double-click **START-OOA.cmd**.

### On a developer computer (source checkout)

Double-click `platform\scripts\start-ooa.bat`, or from PowerShell in the `platform` folder run
`.\gradlew.bat :cli:run --args="server"`. See [Appendix: run from a source checkout](#appendix-run-from-a-source-checkout).

## 3. Start ONEOFFICEAUTOMATION

Start it from the Start menu (**ONEOFFICEAUTOMATION**), with `START-OOA.cmd`, or with `bin\ooa.bat server`.

What happens:

1. A console window opens. It starts **One CommandCenter**, **OneAgentStudio** (at `/studio/`) and a **One
   Runner** for this computer, all on `http://localhost:8720`.
2. The runner for this computer registers itself, so you need no registration key on this computer.
3. Your browser opens One CommandCenter sign-in page.

After 30 to 60 seconds (longer the first time) the window shows:

```
ONEOFFICEAUTOMATION is running.
  Open:             http://localhost:8720
  One CommandCenter:   http://localhost:8720/
  Private workspace:   http://localhost:8720/studio/   (Automation > Private; this computer only)
  OneRunner:       this computer, connected
  My Workspace:     C:\Users\<you>\OOA Workspace
  Database:         ...
```

Studio answers only on this computer, even when One CommandCenter is opened to the network.

Keep the console window open while you work: closing it, or pressing Ctrl+C in it, stops everything (answer **Y** if
Windows asks "Terminate batch job"). To copy text from the window, select it with the mouse and right-click; Ctrl+C
there stops the server.

Options for `ooa server` (and `START-OOA.cmd`, which passes them on):

| Option | Meaning |
| --- | --- |
| `--port 8730` | Use another port (8720 by default), for example when another copy is still running |
| `--host 0.0.0.0` | Accept connections from other machines (turn on HTTPS first). The default, 127.0.0.1, is this computer only |
| `--data D:\OOA\cc` | Keep One CommandCenter's data in another folder |
| `--workspace D:\Agents` | Another folder for Studio's **My Workspace** |
| `--agent-workspace DIR` | Another working folder for this computer's runner |
| `--no-agent` | Do not start the runner for this computer |
| `--no-browser` | Do not open the browser |

Your data (the database, the vault key file, repository files, backups) is kept in the data folder, by default
`C:\Users\<you>\OOA Command Center`. If an earlier version left a database in `C:\Users\<you>\OOA Control Room`,
that folder is used instead, so an upgrade never starts empty. Back this folder up.

| Folder | Contents |
| --- | --- |
| `C:\Users\<you>\OOA Command Center` | One CommandCenter's database, vault key file, repository files and backups (back this up) |
| `C:\Users\<you>\OOA Workspace` | Your private Studio workspace (**My Workspace**) |
| `C:\Users\<you>\OOA Agent Workspace` | This computer's runner: its working files, and one log file per run in `logs` |

> **Tip.** `ooa server` listens on 127.0.0.1 only, so nobody else on the network can reach it. That is what you want
> for a first try. To let other machines in, turn on HTTPS first and use `--host 0.0.0.0`
> ([06 Administrator's guide](06-administrators-guide.md#https-and-network-access)).

## 4. Signing in for the first time

1. On the very first start, the console window prints a **one-time password** for the user `admin`. Copy it.
2. On the **One CommandCenter** sign-in page enter **User name** `admin` and the one-time password, then press
   **Sign in**.
3. The **Choose a new password** page appears. Enter the one-time password as **Current password**, then a **New
   password** twice. It must have at least 12 characters, with letters and digits (an administrator can make the rule
   stricter later). Press **Change password**.
4. You land on **Home**.

What you see on Home: two strips of figures. **Runs · last 24 hours** has **Running now**, **Waiting to start**,
**Succeeded**, **Failed**, **Success rate** and **Average run time**; **Platform** has **Runners online**,
**Agents (Public)**, **Active schedules**, **Work items waiting**, **Hours saved (30 days)** and **Tasks waiting for
you**. A figure turns red only when something is wrong (for example failed runs, or no runner online). Click a figure
to open the page behind it. Below are **Runs in the last 7 days**, **Recent failures**, **Most used agents (7 days)**,
**Runners** and **Recent runs**, and, once you have worked a little, **Pick up where you left off**.

### People who were given an account

If an administrator created your account, they give you a temporary password. Sign in with it and choose your own
password, as in step 3. If your company uses single sign-on, the sign-in page shows an extra button such as
**Sign in with your organisation account**: use that instead, with your normal company sign-in. If the sign-in page
says that your Windows (directory) user name and password work, sign in with those; you need no separate password.

The sign-in page can also show:

- the name of this One CommandCenter, such as **Production** or **Test**, in its own colour, so you know which one you
  are signing in to (it is shown in the top bar too);
- a notice from your company, for example terms of use. When it has a tick box **I have read and accept this**, tick
  it before you sign in;
- "Signed out after n minutes without activity": your administrator signs people out after a time without use. Sign
  in again.

When your password must be changed soon, a warning at the top of the console offers **Change it now**.

If your account needs two-step sign-in, a dialog **Set up two-step sign-in (required)** appears after the password:

1. In an authenticator app (Microsoft Authenticator, Google Authenticator or another time-based app) add an account
   with the key shown, or tap **open it in the app** on a phone.
2. Type the 6-digit code from the app and press **Turn on**.
3. Write down the ten **Recovery codes** shown once, and press **I have saved them**.

From then on, the sign-in page asks for the **Code from your authenticator app (or a recovery code)** after your
password.

## 5. Finding your way: the screen and the menu

### The top bar

- **☰** folds the menu on the left to icons, and opens it again.
- The company logo, then the environment name when your administrator has set one (for example **Production** in red).
- Where you are: the section and the page, for example **Agent Insights › Business**. Click the section to go to its
  first page.
- **Search pages and agents** (Ctrl+K): type part of a page's or an agent's name, or paste a run id to open that run.
- **OneOfficeAutomation**, in the middle (it hides on narrow windows).
- The bell shows how many alerts are open; click it to open **Alerts**.
- **Help** (the question mark): **Keyboard shortcuts**.
- Your account (the person icon): **My account**, **My devices** and **Sign out** (see [My account](#my-account)).

Banners can appear under the top bar: an administrator's **announcement** (for example planned maintenance), a
warning that your password must be changed soon, and, for administrators, the **licence banner** (evaluation days
left, a licence about to expire).

### The menu on the left

The menu has **Home** and five sections, each a row with its icon and name. Click a section to open it; one section is
open at a time, so the whole menu fits on the screen, and the section of the page you are on opens by itself. Type in
**Find a page** at the top to search all pages; press Enter to open the first match. **☰** on the top bar makes the menu
narrow (icons only) or full again; when it is narrow, point at (or click) a section's icon to see its pages and open
one. Drag the menu's edge to make it wider or narrower. The menu is the same in OneAgentStudio and Document Automation.

| Section | Pages | What you do there |
| --- | --- | --- |
| **Build** | Agents (Public: the agent repository; Private: your own workspace), Agent Templates, Agent APIs, Agent tests | Find, build and run agents, install ready-made ones, publish agents as web services, test agents |
| **Manage** | Agent Activity, Task inbox, Runner Machines, Schedules, Triggers, Work queues, Workflow, Shared Global values, Connections, Credential vault, Agent ToolKits | Daily running: runs, tasks for people, the machines, when agents start, queues, workflows, secrets and the shared settings agents use |
| **AI** | One Chat, AI setup (Model Connections, Prompt Skills, Knowledge bases, AI governance), Document Automation | Asking in plain words, AI models and prompts, reading documents with AI (an add-on module; the page says when it is not installed) and the record of AI use |
| **Administration** | Users, Roles, Teams, Settings, Policies, Audit Trail, Event subscriptions, Licenses | People, access, rules for agents' folders, audit, licences and system settings |
| **Agent Insights** | Overview, Alerts, Operations, Business | How things stand now, alerts, how agents, runners and queues worked (Operations), and what the work is worth (Business). See [05 Operator's guide](05-operators-guide.md#11-operational-insights-overview-alerts-and-operations) |

You only see the pages your role allows. For example, a **Viewer** sees the agent repository, runs, runners,
schedules, triggers, shared values, work queues, Agent Insights and Prompt Skills, but cannot change anything.

**Agents** has two tabs, as AA's one Automation page: **Public** (on the left) is the agent repository, with the agents
checked in to run; **Private** (on the right) is a builder's own workspace. Everyone sees the same menu and pages; only
what you may open differs. **Private** works for people who build agents (who may check agents in) when the workspace
runs beside One CommandCenter on the same computer. The top bar names no product, except **OneAgentStudio** in the
editor, when an agent or other item is open.

Tables fit the window, down to a 1366 px wide screen, without scrolling sideways. Text too long for its cell ends in
"…"; point at it to see all of it. The same goes for headings and chart labels.

The small funnel beside a column's heading filters the table by that column's values, as in a spreadsheet; a line
above the table then says which columns filter it, with **Clear column filters**. Fields that name people (vault folder
members, work queue members, alert recipients, dashboard sharing, workflow stages) let you search and pick users, teams
and roles. An agent's inputs (when running it, in a schedule, in a workflow stage or an app button) are one field per
input, with **Edit as JSON** for those who prefer it.

Every page has a **? Help** button after its title: it opens a help panel that explains each part of the page. The
**?** in the top bar opens the **User guide** and the keyboard shortcuts. After it comes one
line saying what the page does, then **Docs** (the documentation, in a new tab) and **Video** (how to use it, played
beside the page). Dialogs, Settings sections and each agent command have the same two links, and an error with a code
has **Learn more**.

### My account

The person icon on the top bar opens **My account** (as AA's user menu):

- **My profile**: your name, user name, e-mail, roles, how many permissions they give, teams (and which you lead), your
  licence seat, how you sign in, two-step sign-in, your last sign-in and from where, and when your password must change.
  From here open **Your API keys**, **Change password**, **Turn on two-step sign-in** and **Phone notifications**.
  **Where you are signed in** lists your sessions; **Sign out everywhere else** ends all but this one (recorded in the
  Audit Trail).
- **Edit profile** (people who sign in with a password): your first name, last name and contact e-mail. With single
  sign-on or a directory, these come from your organisation's sign-in.
- **My devices**: your default runner, every runner you are a default user of, and the runners you connected yourself
  (**your local runner**), each with its status, computer, version and when it last reported. When your last run
  stopped because its runner stopped reporting, it says so. As in AA's My settings:
  - **Choose default runner** (permission *Choose own default runner*, which runners and developers have): any switched-on
    runner, except a single-user runner someone else already has.
  - **Connect to my computer** (permission *Connect own computer as a runner*): gives this server's address and a key
    that works once, for one hour. Install the runner with the setup package (`Install.cmd` asks for both) or, where the
    product is installed, run `bin\ooa.bat agent register --url <address> --key <key>` and `bin\ooa.bat agent install-task`.
    The runner becomes your local runner and, if you have none, your default runner. Registrations must be open
    (Settings > Runner Machines).
  - **Device credentials**: the Windows user name (`DOMAIN\name`, `.\name` or `AzureAD\name@company.com`) and password
    your unattended runs sign in with, for every runner of yours or one runner only. They are kept encrypted for you
    alone (not in the Credential vault's list), never shown again, and sent only to your own runners when you press
    **Check** (Windows signs the account in once to test it) or **Use for automatic sign-in** (the runner sets it as its
    Windows sign-in after a restart, as `ooa agent autologon` does; the runner needs Windows administrator rights for
    that). The answer shows within a minute. Every change, check and use is in the Audit Trail; the password never is.
    Administrators who prefer passwords never to reach the server keep using `ooa agent autologon` on the runner.

### Keys that save time

Press **?** anywhere (or choose **Help > Keyboard shortcuts**) to see every key, grouped by area. In lists: `/` goes
to the list's search, ↓ and ↑ move between rows, Enter opens the row, and Shift+F10 opens the row's **⋯** menu.
Ctrl+K goes to the search in the top bar. If you use speech input or press keys by mistake, switch off the
single-key shortcuts (`[`, `]`, `/` and `?`) in the **Keyboard shortcuts** window; your browser remembers it.

Pages update by themselves: when a run starts or finishes, or someone changes a schedule, the open page is redrawn
within about a second. Tables have a search box, sortable columns, **Columns** (hide columns you do not need; this
browser remembers it) and **Export CSV**.

## 6. Your first agent, end to end

You will build an agent that takes a name and writes a greeting to the run log, then run it from One CommandCenter.

### 6.1 Build it in OneAgentStudio

1. In One CommandCenter, open **Build > Agents** and click its **Private** tab (it is there for people who may
   check agents in). Your own workspace opens, on the **Private** tab of the **Agents** page.
2. Press **Create ▾** and choose **Agent**. In the **New agent** dialog keep **Start from** on **Blank**, type the name `Hello` and press
   **Create and edit**. The agent opens in OneAgentStudio's editor. (On the **Public** tab, **Create ▾ > New agent** does steps 1
   and 2 at once.)
3. In the **Details** panel on the right, open the **Variables** tab and press **New**. Name the variable `name`, keep
   **Type** String, tick **Use as input** and press **Create variable**. Add a second one, `greeting`, type String,
   with **Use as output** ticked.
4. In the **Actions** panel on the left, type `log` in the search box and click **Log message** to add it to the
   agent. On the **Properties** tab on the right, set its message to `Hello ${name}`.
5. Search for `assign` and click **Assign variable**. Set its variable to `greeting` and its value to
   `Hello ${name}` (see [03 Building agents](03-studio-building-agents.md) for more on variables).
6. Press **Save** (Ctrl+S). Saving also checks the agent: Studio says "Saved Hello." when it finds no problems, and
   lists any it finds on the **Problems** tab of the output panel.
7. Press **Run**. The **Run Hello** dialog asks for the input `name`; type `World` and press **Run**. The **Run
   output** tab shows each step and the log line `Hello World`.

### 6.2 Check it in

1. With `Hello` open, press **Check in** at the top of the editor. The **Check in Hello** dialog opens.
2. If Studio is not signed in to One CommandCenter yet, the dialog asks you to sign in first. Because your workspace
   and One CommandCenter run together here, it uses your One CommandCenter sign-in: press **Open One CommandCenter to
   sign in** if needed, then **I have signed in**. (Against another One CommandCenter, fill in **One CommandCenter
   address**, **User name**, **Password** and, if asked, **Two-step code**, then press **Sign in**.)
3. In **What changed**, type a comment such as `First version`, tick **Make this the live version**, and press
   **Check in**. Studio reports "Checked in ... as version 1; it is now the live version."

Without the tick, a check-in stores a new version only and the live version stays as it was (an agent's first version
is always live). With the tick, the new version becomes the **live production version** straight away, unless your
administrator has turned on **Approval before production**; then the version waits for a second person to approve it
(**Production approvals** in the **Manage ▾** menu of **Build > Agents**).

### 6.3 Run it from One CommandCenter

1. In One CommandCenter open **Build > Agents**. On its **Public** tab the `Hello` agent shows `v1` under
   **Latest** and **Live v1** under **Live**.
2. Choose **Run…** in its row's **⋯** menu (or click the row and press **Run**). The **Run Hello** dialog opens; it
   runs the live version, v1.
3. **Run on**: choose your computer's runner (it shows as online; the first online runner is chosen already).
4. Fill in the input `name`, for example `One CommandCenter`.
5. Press **Run**. One CommandCenter says "Started Hello (run ...): follow it in Agent Activity."

### 6.4 Look at the result

Open **Manage > Agent Activity**. Your run is on the **In progress** tab while it runs and on **Completed** once it
has ended. Click it: its details open beside the list with the status, how long it took, the steps run, and these
tabs:

- **Steps**: each step with a tick (or **!** when it failed) and its duration.
- **Log**: every event; filter it with **Everything**, **Errors and retries**, **Log messages** or **Variables set**.
  Your `Hello One CommandCenter` line is here.
- **Variables**: the last value of each variable.
- **Inputs and outputs**: what went in and what came out (`greeting`).

Press **Download log** to save the log as a text file, or **Run again** to run the live version again with the same
inputs. On the **Completed** tab your run is listed with status **Completed**.

You have now been through the whole cycle: build, check in, run, monitor.

## 7. The evaluation and your licence

Without a licence file, a customer install runs as a **60-day evaluation** from its first start, for 2 developers, 2
attended runners and 2 unattended runners. Seats are given out automatically the first time someone checks in or a
runner takes work. Administrators see a banner with the days left (red in the last 7 days). After 60 days runs stop
and only administrators can sign in, to install a licence.

To license the product, an administrator moves One CommandCenter to SQL Server, sends the **install id** from
**Administration > Licenses** to ONEOFFICEAUTOMATION and installs the `.lic` file received. See
[06 Administrator's guide](06-administrators-guide.md#licences-and-seats).

## 8. If it goes wrong

| What you see | What to do |
| --- | --- |
| The browser does not open, or shows "cannot reach" | Check the console window is still open and shows "ONEOFFICEAUTOMATION is running" (wait for it). Open `http://localhost:8720` by hand. |
| The window closes at once, or says port 8720 is in use | Another copy is still running: close its window, or start with `--port 8730` and open `http://localhost:8730`. |
| "Wrong user name or password" | Check for spaces when pasting the one-time password. |
| You lost the one-time password, or forgot your password | Stop ONEOFFICEAUTOMATION (Ctrl+C in its window), run `bin\ooa.bat reset-admin` (or `reset-admin --user jane.doe`), start again and sign in with the new one-time password it prints. The reset is recorded in the audit trail. |
| "Account locked" after wrong passwords | Five wrong passwords lock an account for 15 minutes. Wait, ask an administrator to **Reset password**, or run `reset-admin` (above), which also unlocks the account. |
| **Run** is missing on an agent | The agent has no live production version yet, or your role cannot start runs (`runs.start`). |
| The run stays **queued** | The chosen runner is offline. Check **Manage > Runner Machines**: a runner is online when it has checked in during the last minute. |
| The check-in is refused with validation problems | Studio opens the problems list. Fix them, save and check in again. |
| `bin\ooa.bat check` shows FAIL | Each line says what to fix (memory, disk, port, Java). Fix it and run the check again. |

## 9. Where to go next

- Builders: [02 Studio basics](02-studio-basics.md) and [15 Publishing to One CommandCenter](15-publishing-to-command-center.md).
- Operators: [05 Operator's guide](05-operators-guide.md).
- Administrators: [06 Administrator's guide](06-administrators-guide.md), then [07 Runner guide](07-runner-guide.md)
  to add runners on other machines.
- Business users: [14 Business users](14-business-users.md).

## Appendix: run from a source checkout

For developers who work on ONEOFFICEAUTOMATION itself and run it from the Git repository instead of an install
package.

**You need:** a Java 21 JDK (for example Eclipse Adoptium) with `JAVA_HOME` set, the repository checked out (for
example in `C:\OOA\oneofficeautomation`), and Edge or Chrome. Gradle downloads itself the first time.

### Start, stop and reset

1. Get the latest code if you like: `git pull` in the repository folder.
2. Double-click `platform\scripts\start-ooa.bat`, or from PowerShell:

   ```
   cd C:\OOA\oneofficeautomation\platform
   .\scripts\start-ooa.bat
   ```

   The script runs `gradlew :cli:run --args="server"` and passes options on, for example
   `.\scripts\start-ooa.bat --port 8730`. The first start builds the product, so it takes longer.
3. Wait for "ONEOFFICEAUTOMATION is running" and sign in as in sections 3 and 4.
4. To stop, press Ctrl+C in the window (answer **Y** to "Terminate batch job") or close it.

Any other command runs the same way: `ooa X` becomes `.\gradlew.bat :cli:run --args="X"` in the `platform` folder.
For a forgotten password, stop the server and run `.\gradlew.bat :cli:run --args="reset-admin"` (or
`--args="reset-admin --user jane.doe"`), then start again and sign in with the one-time password it prints.

| Problem | What to do |
| --- | --- |
| "JAVA_HOME is not set" | Install a Java 21 JDK, set `JAVA_HOME`, and open a new PowerShell window |
| The first start seems stuck | Gradle is downloading and building; wait for "ONEOFFICEAUTOMATION is running" |

### Build the install packages

From the `platform` folder:

| Package | Command | Result |
| --- | --- | --- |
| The zip without installer | `.\gradlew.bat :cli:customerDistZip` | `cli\build\distributions\ooa-customer-<version>.zip` |
| The setup package (with its own Java and `Install.cmd`) | `.\gradlew.bat :cli:setupDistZip` | `cli\build\distributions\ooa-setup-<version>.zip` |
| The MSI | `powershell -ExecutionPolicy Bypass -File scripts\build-msi.ps1` | `cli\build\distributions\ooa-<version>.msi` |

The MSI script builds the setup package's files first and needs the WiX Toolset 5, installed once with
`dotnet tool install --global wix --version 5.0.2` (or give its path with `-Wix`). The packages' `README.txt` lists
every install option. A package built this way installs as in section 2.

## Related guides

- [00 Index of all guides](00-README.md)
- [05 Operator's guide](05-operators-guide.md)
- [06 Administrator's guide](06-administrators-guide.md)
- [07 Runner guide](07-runner-guide.md)
- [02 Studio basics](02-studio-basics.md)
