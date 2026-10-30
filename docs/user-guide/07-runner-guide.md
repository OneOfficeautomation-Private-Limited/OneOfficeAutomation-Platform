# Runner guide

**Audience:** runner owners (the people who look after the computers that run agents) and administrators who roll
runners out. Business users with an attended runner on their own PC will find the sections on attended work and the
assistant useful.

**You need:** on the runner computer, rights to install programs (administrator rights for a Windows service or an
all-users install). In the Command Center, `devices.manage` to register, change and remove runners (administrators),
and `pools.manage` for runner pools.

## Contents

1. [What a runner is](#1-what-a-runner-is)
2. [Choose the kind of runner](#2-choose-the-kind-of-runner)
3. [Register a runner](#3-register-a-runner)
4. [Start it at sign-in or as a service](#4-start-it-at-sign-in-or-as-a-service)
5. [Proxies and certificates](#5-proxies-and-certificates)
6. [The runner in the Command Center](#6-the-runner-in-the-command-center)
7. [The screen: locked screens and after-run actions](#7-the-screen-locked-screens-and-after-run-actions)
8. [Default users and the default runner of a person](#default-users-and-the-default-runner-of-a-person)
9. [Runner pools](#9-runner-pools)
10. [Temporary runners](#10-temporary-runners)
11. [Updates, toolkits and secrets](#11-updates-toolkits-and-secrets)
12. [The assistant in the notification area](#12-the-assistant-in-the-notification-area)
13. [Diagnosing problems: ooa agent diagnose](#13-diagnosing-problems-ooa-agent-diagnose)
14. [Moving a runner to a new Command Center address](#14-moving-a-runner-to-a-new-command-center-address)
15. [Removing a runner](#15-removing-a-runner)
16. [Related guides](#related-guides)

## 1. What a runner is

OneRunner is the program that runs agents. It registers once with a Command Center, then:

- sends a heartbeat every few seconds (a runner is **online** when it checked in during the last minute);
- keeps one request open so that queued work, and stop or pause requests, reach it at once;
- runs each agent in its own process, so a crashing agent never stops the runner;
- reports every step, log line and variable back to the Command Center;
- watches its own machine for runner triggers (files, windows, processes, services, hotkeys) every two seconds.

Its workspace is `C:\Users\<you>\OOA Agent Workspace`; log files are in its `logs` folder (one file per run, named by
run id). Its registration is saved in `~/.ooa-agent/agent.json` by default.

The computer that runs `ooa server` (or `START-OOA.cmd`) already has a runner that registered itself. This guide is
for every other computer.

## 2. Choose the kind of runner

| Question | Choose |
| --- | --- |
| Do agents use the screen (keyboard, mouse, windows, images, desktop dialogs, visible browsers)? | A **signed-in runner** that starts at Windows sign-in |
| Do agents only work with files, data, web services, e-mail, databases? | A **service runner** (Windows service, no desktop) is enough and survives sign-outs |
| Is a person at the machine during runs? | **Attended** work: they start runs by hand on their own default runner and answer dialogs |
| Does nobody watch the machine? | **Unattended** work: scheduled, triggered, API and queue runs. Needs an **Unattended Runner** seat |
| Is it a pooled virtual desktop or a short-lived build machine? | A **temporary runner** (section 10) |

Attended and unattended are about who sits at the machine and how runs are started; signed-in and service are about
how the runner process itself runs. An unattended runner that uses the screen is a signed-in runner with a dedicated
account that stays unlocked.

**Seats:** a runner needs an **Unattended Runner** seat to take Command Center work (manual, scheduled, triggered,
API, queue). A person with an **Attended Runner** seat (or a Developer or Citizen Developer seat) may run agents by
hand on their own default runner. Administrators assign seats on **Administration > Licenses**.

## 3. Register a runner

### Step 1: make a registration key (administrator)

1. In the Command Center open **Manage > Runners** and press **Register a runner**.
2. Tick **Temporary runner (VDI or pooled desktop)** only for runners that come and go (section 10).
3. Press **Create key**. The dialog shows the key and three ready-made commands. The key works **once**, expires in
   **24 hours** and is shown only now:

   | Situation | Command shown |
   | --- | --- |
   | ONEOFFICEAUTOMATION already installed | `bin\ooa.bat agent register --url <address> --key ooareg_...`, then `bin\ooa.bat agent install-task` |
   | With the MSI (runs as a Windows service) | `msiexec /i ooa.msi /qn ROLE=Runner URL=<address> REGISTRATION_KEY=ooareg_...` |
   | With the setup package | `Install.cmd -Silent -Role Runner -Url <address> -RegistrationKey ooareg_...` |

### Step 2a: install and register with the setup package (desktop runners)

Run this **as the Windows user the runner should work as** (not as an administrator account, unless that is the
account):

1. Unzip `ooa-setup-<version>.zip` and double-click **Install.cmd**, or run it silently:

   ```
   Install.cmd -Silent -Role Runner -Url https://ooa.example.com:8720 -RegistrationKey ooareg_... [-CaFile ooa.cer] [-RunnerName FIN-RPA-01] [-Proxy system|none|URL]
   ```

2. Setup installs the product with its own Java, registers the runner and sets it to start when this user signs in.
3. Add `-Service` (as administrator) to make it a service runner instead.

Exit codes: 0 installed, 1 a parameter is missing or wrong, 2 the install failed, 3 administrator rights needed, 4 the
prerequisite check failed. The log is `%TEMP%\ooa-install.log`.

### Step 2b: install with the MSI (service runners, mass roll-out)

```
msiexec /i ooa-<version>.msi /qn ROLE=Runner URL=https://ooa.example.com:8720 REGISTRATION_KEY=ooareg_...
```

Other properties: `DATA_DIR`, `RUNNER_NAME`, `CA_FILE`. A runner installed by the MSI runs as a Windows service, for
agents that need no screen. For desktop runners use `Install.cmd` as the user. `msiexec /x ooa-<version>.msi /qn`
removes it; data folders are kept.

### Step 2c: register an existing install by hand

From the install's `bin` folder:

```
ooa agent register --url https://ooa.example.com:8720 --key ooareg_... [--name FIN-RPA-01] [--ca ooa.cer]
ooa agent start
```

From a source checkout use `.\gradlew.bat :cli:run --args="agent register --url ... --key ..."`. The runner's name
defaults to the computer's name.

### What you see

In **Manage > Runners** the new runner appears with a green dot and **online** within seconds.

## 4. Start it at sign-in or as a service

| Kind | How | Notes |
| --- | --- | --- |
| Signed-in runner | `ooa agent install-task` | Starts the runner when this user signs in to Windows and restarts it if it stops. `ooa agent uninstall-task` removes it. Required for agents that use the screen |
| Service runner | `ooa service install --mode agent` (administrator prompt) | Starts with Windows and restarts after a failure (three times, a minute apart). Name "OOA Runner" unless `--name`; `--account DOMAIN\user --password-env VAR` to run as an account instead of Local System |
| By hand | `ooa agent start` | Runs until you press Ctrl+C. Useful for testing |

Manage a service with `ooa service start|stop|status|uninstall --mode agent` or in Windows Services. Its log is
`<data>\logs\service-agent.log`.

## 5. Proxies and certificates

**Proxies.** By default the runner uses the Windows proxy settings (including automatic configuration scripts).
Choose otherwise at registration, or later with `ooa agent network`:

```
ooa agent network --proxy none
ooa agent network --proxy http://proxy.corp:8080 --proxy-user svc-rpa --proxy-password-env PROXY_PW
ooa agent network            (no option: show the current proxy and trusted certificate)
```

The proxy password is read from the environment variable. On Windows it is stored encrypted with DPAPI; on Linux and
macOS it is stored in the file `proxy-password` next to the runner's settings, readable only by the account that owns
it. Proxies that only accept Windows (NTLM) sign-in are not supported. Restart the runner after a change.

**Certificates.** A Command Center certificate from a company authority that Windows and Java already trust needs
nothing. For a self-signed certificate, get `ooa.cer` from the administrator (`ooa tls export-cert --out ooa.cer`) and
add `--ca ooa.cer` when registering (or `ooa agent network --ca ooa.cer`). The runner keeps a copy beside its settings
and trusts it from then on. Runners on Windows also trust the Windows certificate store, so a company authority rolled
out by Group Policy needs nothing more.

## 6. The runner in the Command Center

**Manage > Runners** lists every runner. Columns: **Runner** (name, host name, operating system), **Status**,
**Running**, **Agent** (the runner's version), **Type**, **Default users**, **Log level**, **Screen**, **After a run**,
**Program**, **Last seen**, **Registered** and **Disable**/**Enable**. The page refreshes every 15 seconds.

Click a runner for its details: host name, operating system, agent version, last seen, type, default runner of,
log level, screen, after unattended runs, **Toolkits** (the list it reported), registered by, runner id, **Now** and
**History**. Buttons: **Run an agent here**, **Rename**, **Disable**/**Enable**, **Remove**.

If **Agent** shows a red "update the Command Center" pill, the runner is newer than the Command Center and gets no
work until the Command Center is updated.

### Change settings for several runners at once

Press **Update runners**, tick runners, then choose (or leave **(keep)**):

| Field | Choices | Effect |
| --- | --- | --- |
| **Log level** | error, warning, info, debug | The level of the runner's own log; picked up at the next heartbeat |
| **Runner type** | Single-user, Multi-user | Single-user: the default runner of one person. Multi-user: of several people. Runners nobody set are multi-user |
| **After unattended runs** | Leave the screen as it is, Lock the screen, Sign out | Section 7 |

Press **Update checked runners**. No one has to visit the machines.

## 7. The screen: locked screens and after-run actions

Each runner reports its screen with every heartbeat:

| Screen | Meaning |
| --- | --- |
| **Unlocked** | Someone is signed in and the screen is usable |
| **Locked** | Agents that use the screen cannot run until someone signs in |
| **No desktop** | The runner is a Windows service: only agents that need no screen can run |

A run of an agent that uses the screen does not start on a locked runner or one without a desktop: it fails at once
with `RUN.NO_DESKTOP` and the reason, instead of failing halfway. Headless runs and agents that need no screen run
anywhere. Signing a user in on a locked or signed-out runner by itself is not built yet: keep unattended screen
runners signed in and unlocked, for example with a dedicated account and the screen saver lock off.

**After unattended runs** the runner can **Lock the screen** or **Sign out**, so nobody walks up to a signed-in
machine. It only acts when the screen was unlocked and the run was unattended. Remember that after **Sign out** the
runner (started at sign-in) stops until someone signs in again, and after **Lock the screen** the next screen agent
cannot start until the machine is unlocked: use these on machines where that is what you want.

## Default users and the default runner of a person

Each person can have a **default runner**, chosen by an administrator in **Administration > Users > Default runner**.
It is used when:

- someone runs an agent for **Run-as users**: each person's run goes to their default runner, as them;
- a schedule or trigger runs as a person with **Use the run-as user's default runner**;
- the person starts an agent from the notification-area assistant;
- an Attended Runner seat holder runs agents by hand.

The **Default users** column on the Runners page shows whose default runner each runner is. Set a personal PC to
**Single-user** and a shared machine to **Multi-user** (**Update runners**).

## 9. Runner pools

**Manage > Runners > Runner pools** groups runners. A run, schedule, work queue, test run or API task sent to a pool
goes to its least busy online runner.

1. Press **New runner pool** (needs `pools.manage`).
2. Give a **Name** (for example `Finance runners`) and **Description**, tick the **Runners in the pool**, and **Save**.
3. **Edit** changes the runners; **Delete** removes the pool (not the runners).

The list shows each pool's runners with a status dot and **Online** "n of m". Put runners with the same applications
and toolkits in one pool, so any of them can take any run.

## 10. Temporary runners

For pooled virtual desktops or short-lived machines, tick **Temporary runner** when making the registration key. The
runner shows a **Temporary** pill. It is removed by itself once it has been offline for the hours set in **Settings >
Remove temporary runners after this many hours without contact** (8 by default), and its licence seat is freed.

## 11. Updates, toolkits and secrets

**Program updates.** The **Program** column says **Up to date** or **Update available**. **Update** on a runner (or
**Settings > Update runners automatically to this Command Center's version when they are idle**) makes an idle runner
download the files it needs, check every checksum, swap its program folder and start again: a service is restarted by
Windows, a signed-in runner by its task. The bundled Java is not updated this way. A runner installed for all users
but started at a user's sign-in may not be allowed to write to Program Files: update it with the MSI instead.

**Toolkits.** Each runner reports its toolkits (shown in its details). A run that needs a toolkit or version the
runner lacks fails at once with `RUN.TOOLKIT_MISSING`, naming what is missing. Custom toolkits travel with the run;
the runner checks each jar's checksum and signature (`RUN.TOOLKIT_LOAD` if either fails).

**Runner secrets.** Each runner's secret is replaced by itself every 30 days (**Settings > Give runners a new secret
every (days)**, 0 = never). The runner saves the new secret safely; the old one keeps working for a short while.

## 12. The assistant in the notification area

For people who work beside their agents, `ooa assistant` (Windows, from `bin`) puts ONEOFFICEAUTOMATION in the
notification area. It runs as the signed-in person.

1. In the Command Center, make an API key under your name > **API keys**.
2. Run `bin\ooa.bat assistant`. The first start asks for the Command Center address and the API key. They are stored
   in `%APPDATA%\ONEOFFICEAUTOMATION\assistant.json`, protected for your Windows user with DPAPI.
3. Right-click the icon. The menu has **Signed in as ...**, **Run an agent** (the agents with a live version you may
   see; it asks for each input and starts the agent on your default runner, as you), **Task inbox** (with a count of
   open workflow tasks), **My runs**, **Open One CommandCenter**, **Refresh**, **Sign out** and **Exit**.
4. A notice appears when a new task arrives and when one of your runs finishes, fails or is stopped; click it to open
   the page.

To start it at sign-in, put a shortcut to `bin\ooa.bat assistant` in your Startup folder. Revoking the API key stops
the assistant; **Sign out** (or `ooa assistant --sign-out`) forgets the address and key.

## 13. Diagnosing problems: ooa agent diagnose

When a runner will not connect or will not take work, run on it:

```
bin\ooa.bat agent diagnose
```

Each line is **PASS**, **WARN** or **FAIL** with what to fix, and it ends with "No problems found." or the number of
problems:

| Check | A FAIL or WARN usually means |
| --- | --- |
| Runner settings | Not registered yet: `ooa agent register` |
| Name lookup | The Command Center's host name is unknown here: DNS or a typo in the address |
| Proxy | Which proxy is used |
| Network | The port does not answer: firewall, or the Command Center is stopped |
| Certificate | Not trusted: register with `--ca FILE`, or install the company authority in Windows |
| Command Center | No answer or an error; if a proxy asks for a sign-in, set it with `ooa agent network` |
| Clock | More than a minute apart: set both clocks from the same time server |
| Runner sign-in | The runner was deleted or disabled: register it again |
| Screen | Locked or no desktop: screen agents will not start |
| Free disk space | Less than about 1 GB free |
| Toolkits | How many toolkits are installed |

For the Command Center computer itself use `bin\ooa.bat check`.

### Other common problems

| Symptom | What to do |
| --- | --- |
| Online, but runs stay queued | Check it is enabled, has an Unattended Runner seat, and is not busy with a long run |
| Runs fail with `RUN.NO_DESKTOP` | Unlock the screen, or move screen agents to a signed-in runner |
| Runs fail with `RUN.NEEDS_PERSON` | An unattended run hit a dialog; run it attended or add a timeout in the agent |
| The runner stops when you sign out | Expected for a signed-in runner; use a service runner for screenless work |
| Need more detail | Set **Log level** to debug (**Update runners**) and read the runner's log in its workspace `logs` folder |

## 14. Moving a runner to a new Command Center address

When the Command Center moves to a new server or a new address with its data, keep each runner's registration:

```
ooa agent move --url https://new-address:8720 [--ca new.cer]
```

Then restart the runner. Runners that reach the Command Center through a DNS name you repoint need nothing. See
[Installation on Linux and in containers](../infrastructure/03-installation-linux-and-containers.md).

## 15. Removing a runner

In the runner's details press **Remove**. It is allowed when nothing is queued or running on it and no schedule or
trigger uses it. The runner is taken off pools and default runners and its seat is freed; register it again to use it.
On the machine, remove the start task (`ooa agent uninstall-task`) or service (`ooa service uninstall --mode agent`),
then uninstall the product in Apps and Features. **Disable** instead keeps the runner but gives it no work.

## Related guides

- [00 Index](00-README.md)
- [05 Operator's guide](05-operators-guide.md)
- [06 Administrator's guide](06-administrators-guide.md)
- [08 Browser connector guide](08-browser-connector-guide.md)
- [14 Business users](14-business-users.md)
