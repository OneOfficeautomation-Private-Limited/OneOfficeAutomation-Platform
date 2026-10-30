# Getting started

**Audience:** everyone, on their first day with ONEOFFICEAUTOMATION. Administrators and builders will follow every
step; business users and operators can skip the install part if someone has already set the Command Center up.

**What you will have at the end:** ONEOFFICEAUTOMATION running on one computer, your own administrator password, a
tour of the menu, and a first agent that you built in One Agent Studio, checked in to the Command Center, ran on a
runner and looked at in **Agent Runs**.

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
  need a Windows runner; the Command Center itself also runs on Linux (see [Installation on Linux and in containers](../infrastructure/03-installation-linux-and-containers.md)).
- One of the install packages from your ONEOFFICEAUTOMATION releases page:

  | Package | Contains | Choose it when |
  | --- | --- | --- |
  | `ooa-setup-<version>.zip` | The product with its own Java, and a guided installer (`Install.cmd`) | Almost always. It installs, adds a Start menu entry and can set up a service. |
  | `ooa-<version>.msi` | The same, as an MSI for all users | Your IT team deploys software with SCCM, Intune or Group Policy |
  | `ooa-customer-<version>.zip` | The product without an installer | You want to try it without installing; you need Java 21 or later on the computer |

- A free port, 8720 by default. The browser opens `http://localhost:8720`.
- About 1 GB of free disk space, more for the data that builds up.

> **Tip.** For a first look, one computer does everything: the Command Center, Studio and a runner. Later you move
> the Command Center to a server and add runners on other machines ([07 Runner guide](07-runner-guide.md)).

## 2. Install on one computer

### With the setup package (recommended)

1. Unzip `ooa-setup-<version>.zip` into a temporary folder.
2. Double-click **Install.cmd**.
3. When asked what this computer is, choose **Command Center**.
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

1. A console window opens. It starts **One Command Center**, **One Agent Studio** (at `/studio/`) and a **One
   Runner** for this computer, all on `http://localhost:8720`.
2. The runner for this computer registers itself, so you need no registration key on this computer.
3. Your browser opens the Command Center sign-in page.

After 30 to 60 seconds (longer the first time) the window shows:

```
ONEOFFICEAUTOMATION is running.
  Open:             http://localhost:8720
  Command Center:   http://localhost:8720/
  Agent Studio:     http://localhost:8720/studio/   (this computer only)
  One Runner:       this computer, connected
  My Workspace:     C:\Users\<you>\OOA Workspace
  Database:         ...
```

Studio answers only on this computer, even when the Command Center is opened to the network.

Keep the console window open while you work: closing it, or pressing Ctrl+C in it, stops everything (answer **Y** if
Windows asks "Terminate batch job"). To copy text from the window, select it with the mouse and right-click; Ctrl+C
there stops the server.

Options for `ooa server` (and `START-OOA.cmd`, which passes them on):

| Option | Meaning |
| --- | --- |
| `--port 8730` | Use another port (8720 by default), for example when another copy is still running |
| `--host 0.0.0.0` | Accept connections from other machines (turn on HTTPS first). The default, 127.0.0.1, is this computer only |
| `--data D:\OOA\cc` | Keep the Command Center's data in another folder |
| `--workspace D:\Agents` | Another folder for Studio's **My Workspace** |
| `--agent-workspace DIR` | Another working folder for this computer's runner |
| `--no-agent` | Do not start the runner for this computer |
| `--no-browser` | Do not open the browser |

Your data (the database, the vault key file, repository files, backups) is kept in the data folder, by default
`C:\Users\<you>\OOA Command Center`. If an earlier version left a database in `C:\Users\<you>\OOA Control Room`,
that folder is used instead, so an upgrade never starts empty. Back this folder up.

| Folder | Contents |
| --- | --- |
| `C:\Users\<you>\OOA Command Center` | The Command Center's database, vault key file, repository files and backups (back this up) |
| `C:\Users\<you>\OOA Workspace` | Your private Studio workspace (**My Workspace**) |
| `C:\Users\<you>\OOA Agent Workspace` | This computer's runner: its working files, and one log file per run in `logs` |

> **Tip.** `ooa server` listens on 127.0.0.1 only, so nobody else on the network can reach it. That is what you want
> for a first try. To let other machines in, turn on HTTPS first and use `--host 0.0.0.0`
> ([06 Administrator's guide](06-administrators-guide.md#https-and-network-access)).

## 4. Signing in for the first time

1. On the very first start, the console window prints a **one-time password** for the user `admin`. Copy it.
2. On the **One Command Center** sign-in page enter **User name** `admin` and the one-time password, then press
   **Sign in**.
3. The **Choose a new password** page appears. Enter the one-time password as **Current password**, then a **New
   password** twice. It must have at least 12 characters, with letters and digits (an administrator can make the rule
   stricter later). Press **Change password**.
4. You land on **Home**.

What you see on Home: tiles for **Running now**, **Waiting to start**, **Succeeded (24 h)**, **Failed (24 h)**,
**Success rate (24 h)**, **Average run time**, **Runners online**, **Agents in the agent repository**,
**Active schedules**, **Work items waiting**, **Hours saved (30 days)** and **Tasks waiting for you**. Click a tile to
open the page behind it. Below are **Runs in the last 7 days**, **Recent failures**, **Most used agents (7 days)**,
**Runners** and **Recent runs**, and, once you have worked a little, **Pick up where you left off**.

### People who were given an account

If an administrator created your account, they give you a temporary password. Sign in with it and choose your own
password, as in step 3. If your company uses single sign-on, the sign-in page shows an extra button such as
**Sign in with your organisation account**: use that instead, with your normal company sign-in.

If your account needs two-step sign-in, a dialog **Set up two-step sign-in (required)** appears after the password:

1. In an authenticator app (Microsoft Authenticator, Google Authenticator or another time-based app) add an account
   with the key shown, or tap **open it in the app** on a phone.
2. Type the 6-digit code from the app and press **Turn on**.
3. Write down the ten **Recovery codes** shown once, and press **I have saved them**.

From then on, the sign-in page asks for the **Code from your authenticator app (or a recovery code)** after your
password.

## 5. Finding your way: the screen and the menu

### The top bar

- **Open One Agent Studio** opens the designer in a new tab (shown when Studio runs with this Command Center).
- The half-moon button switches between a light and a dark theme.
- Your name opens the user menu: **API keys**, **Change password**, **Two-step sign-in**, **Language** (English,
  Deutsch, Français, Español, 日本語) and **Sign out**.

Banners can appear under the top bar: an administrator's **announcement** (for example planned maintenance) and,
for administrators, the **licence banner** (evaluation days left, a licence about to expire).

### The menu on the left

The menu has **Home** and five sections. Click a section name to open it. The section of the page you are on is
always open. Type in **Find a page** at the top to search all pages; press Enter to open the first match. The button
at the bottom makes the menu narrow (icons only) or full again.

| Section | Pages | What you do there |
| --- | --- | --- |
| **Build** | Agent repository, Templates, One Agent Studio (opens a new tab), API tasks, Agent tests | Find and run agents, install ready-made ones, publish agents as web services, test agents |
| **Manage** | Agent Runs, Task inbox, Runners, Schedules, Triggers, Work queues, One Workflow, Shared values, Connections, Toolkits | Daily running: runs, tasks for people, the machines, when agents start, queues, workflows and the shared settings agents use |
| **Insights** | Operations, Dashboards | How agents and work queues are doing |
| **Administration** | Users, Roles, Teams, Agent credential vault, One Audit Trail, Licenses, Settings | People, access, secrets, audit, licences and system settings |
| **AI** | One Model Connections, Prompt Skills, Document Automation, AI governance | Model connections, AI prompts, reading documents with AI (an add-on module; the page says when it is not installed) and the record of AI use |

You only see the pages your role allows. For example, a **Viewer** sees the agent repository, runs, runners,
schedules, triggers, shared values, work queues, Operations, Dashboards and Prompt Skills, but cannot change anything.

Pages update by themselves: when a run starts or finishes, or someone changes a schedule, the open page is redrawn
within about a second. Tables have a search box, sortable columns, **Columns** (hide columns you do not need; this
browser remembers it) and **Export CSV**.

## 6. Your first agent, end to end

You will build an agent that takes a name and writes a greeting to the run log, then run it from the Command Center.

### 6.1 Build it in One Agent Studio

1. In the Command Center, click **Open One Agent Studio** (or **Build > One Agent Studio**). Studio opens in a new tab.
2. In the **Explorer** on the left, press **+ Create** and choose **Agent**. Give it the name `Hello`.
3. Open the **Variables** panel (the `{ }` button on the left rail) and add a variable `name`, type **String**,
   direction **input**. Add a second one, `greeting`, type **String**, direction **output**.
4. Open the **Actions** panel (the four-squares button on the rail). Type `log` in **Search actions** and click
   **Log message** to add it to the agent. In its properties set the message to `Hello ${name}`.
5. Search for `assign` and click **Assign variable**. Set its variable to `greeting` and its value to
   `Hello ${name}` (see [03 Building agents](03-studio-building-agents.md) for more on variables).
6. Press **Save** (Ctrl+S), then **Validate**. The problems list should be empty.
7. Press **Run**. Studio asks for the input `name`; type `World`. The run console shows each step and the log line
   `Hello World`.

### 6.2 Check it in

1. At the bottom of the Explorer, open the **Command Center** section. Because Studio and the Command Center run
   together here, it says you can use your Command Center sign-in: press **Open Command Center to sign in** if needed,
   then **I have signed in**. (Against another Command Center, fill in **Address**, **User name**, **Password** and, if
   asked, **Two-step code**, then press **Connect**.)
2. With `Hello` open, press **Agent check-in** in the Explorer toolbar.
3. In the **Agent check-in** dialog, type a comment such as `First version`, tick **Make this the live version**, and
   press **Check in**. Studio reports "Checked in ... as version 1; it is now the live version".

Without the tick, a check-in stores a new version only and the live version stays as it was; a new agent then has no
live version and cannot run from the Command Center yet. With the tick, the new version becomes the **live production
version** straight away, unless your administrator has turned on **Approval before production**; then the version
waits for a second person to approve it (**Build > Agent repository > Production approvals**).

### 6.3 Run it from the Command Center

1. In the Command Center open **Build > Agent repository**. The `Hello` agent shows **Latest** `v1` and
   **Live production** `v1`.
2. Press **Run** on its row. The **Run Hello** dialog opens.
3. **Run on**: leave **A runner** and choose your computer's runner (it shows as online).
4. Fill in the input `name`, for example `Command Center`.
5. Optional: open **Run mode, priority and result callback** to choose **Attended**, **Unattended** or **Headless**,
   a priority, or a callback URL. Leave the defaults for now.
6. Press **Run now**. The Command Center opens **Manage > Agent Runs** on the **Live** tab and the run's details.

### 6.4 Look at the result

The run details panel shows the status, how long it took, the steps run, and these tabs:

- **Steps**: each step with a tick or a cross and its duration.
- **Log**: every event; filter it with **All**, **Errors**, **Log messages** or **Variables**. Your `Hello Command
  Center` line is here.
- **Variables**: the last value of each variable.
- **Inputs and outputs**: what went in and what came out (`greeting`).

Press **Download log** to save the log as a text file, or **Rerun** to run the live version again with the same
inputs. Close the panel and open the **History** tab: your run is listed with status **success**.

You have now been through the whole cycle: build, check in, run, monitor.

## 7. The evaluation and your licence

Without a licence file, a customer install runs as a **60-day evaluation** from its first start, for 2 developers, 2
attended runners and 2 unattended runners. Seats are given out automatically the first time someone checks in or a
runner takes work. Administrators see a banner with the days left (red in the last 7 days). After 60 days runs stop
and only administrators can sign in, to install a licence.

To license the product, an administrator moves the Command Center to SQL Server, sends the **install id** from
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
| The run stays **queued** | The chosen runner is offline. Check **Manage > Runners**: a runner is online when it has checked in during the last minute. |
| The check-in is refused with validation problems | Studio opens the problems list. Fix them, save and check in again. |
| `bin\ooa.bat check` shows FAIL | Each line says what to fix (memory, disk, port, Java). Fix it and run the check again. |

## 9. Where to go next

- Builders: [02 Studio basics](02-studio-basics.md) and [15 Publishing to the Command Center](15-publishing-to-command-center.md).
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
