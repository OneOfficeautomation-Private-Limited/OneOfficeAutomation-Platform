# Administrator's guide

**Audience:** administrators of One Command Center: the people who set it up, give people access, keep secrets safe,
manage licences and answer to auditors.

**You need:** the **Administrator** role, or a custom role with the permissions named in each section. The
administrator-only permissions are `repo.production`, `repo.approve`, `repo.permissions`, `lifecycle.export`,
`lifecycle.import`, `queues.all`, `teams.manage`, `users.manage`, `devices.manage`, `lockers.manage`,
`globals.manage`, `audit.view`, `pools.manage`, `settings.manage`, `connections.manage`, `toolkits.manage` and
`skills.all`.

## Contents

1. [Before you go live](#1-before-you-go-live)
2. [Users](#2-users)
3. [Roles and permissions](#3-roles-and-permissions)
4. [Teams](#4-teams)
5. [Sign-in and security](#5-sign-in-and-security)
6. [API keys and tokens](#6-api-keys-and-tokens)
7. [One Agent Vault](#7-one-agent-vault)
8. [Shared values and Connections](#8-shared-values-and-connections)
9. [Toolkits](#9-toolkits)
10. [The agent repository: folders, approvals, Git, code analysis](#10-the-agent-repository-folders-approvals-git-code-analysis)
11. [Templates](#11-templates)
12. [Settings](#12-settings)
13. [Licences and seats](#licences-and-seats)
14. [One Audit Trail](#14-one-audit-trail)
15. [AI: model connections, Prompt Skills and AI governance](#15-ai-model-connections-prompt-skills-and-ai-governance)
16. [Languages](#16-languages)
17. [If it goes wrong](#17-if-it-goes-wrong)
18. [Related guides](#related-guides)

## 1. Before you go live

A checklist for a production Command Center:

1. **Install it on a server** with the setup package as a service:
   `Install.cmd -Silent -Role CommandCenter -AllUsers -Service -Port 8720`, or the MSI
   `msiexec /i ooa-<version>.msi /qn ROLE=CommandCenter SERVICE=1 PORT=8720`. Or, from an installed `bin` folder,
   `ooa service install --mode control-room --data D:\OOA\cr --port 8720` (`--mode server` adds Studio; a service has no
   desktop, so no built-in runner). Manage it with `ooa service start|stop|status|uninstall` or in Windows Services; it
   restarts by itself after a failure. Its log is `<data>\logs\service-<mode>.log`.
2. **Move to SQL Server** (needed before a licence can be installed):
   `ooa db configure`, `ooa db set-password`, `ooa db set-password --setup`, `ooa db init`, and, to keep evaluation
   data, `ooa db copy --from "<old data folder>"`. Details in [DATABASE.md](../DATABASE.md). Setup can do it in one go
   with `-DbServer SQL01 -DbPasswordEnv OOA_APP_PW -DbSetupPasswordEnv OOA_SETUP_PW`.
3. **Protect the vault key**: set `OOA_MASTER_KEY` (32 random bytes, Base64) outside the data folder, or point
   `OOA_MASTER_KEY_SOURCE` at Azure Key Vault (`azure-keyvault:https://NAME.vault.azure.net/secrets/SECRET`), AWS
   Secrets Manager (`aws-secrets:REGION/SECRET-ID`), Google Secret Manager
   (`gcp-secret:projects/P/secrets/S/versions/latest`) or `prompt` (an operator types it at start). Without it, a
   `master.key` file is made next to the database.
4. **Turn on HTTPS** (below), then let other machines in with `--host 0.0.0.0`.
5. Run `bin\ooa.bat check`. Fix every **FAIL**.
6. Set the **Public address of the Command Center** in Settings (needed for single sign-on redirects).
7. Set up backups, e-mail, password rules and two-step sign-in in **Settings** (section 12).
8. Install the licence and give out seats (section 13).

### HTTPS and network access

Stop the Command Center, then from `bin`:

| Command | What it does |
| --- | --- |
| `ooa tls configure --pfx C:\certs\ooa.pfx` | Use the company's certificate (.pfx or .p12 with its private key) |
| `ooa tls set-password` | Store the .pfx password, encrypted with the vault key (or `--from-env VAR`) |
| `ooa tls self-signed --host ooa.example.com` | A test certificate (browsers warn; runners need `--ca`) |
| `ooa tls show` | The certificate in use, its names and validity |
| `ooa tls export-cert --out ooa.cer` | The certificate without its key, for runners and browsers to trust |
| `ooa tls off` | Back to plain HTTP (the settings file is renamed, not deleted) |

With HTTPS on, the port no longer answers plain HTTP, the session cookie is `Secure` and responses carry
`Strict-Transport-Security`. A reverse proxy (IIS, nginx) in front also works; leave HTTPS off on the server then.

## 2. Users

**Administration > Users** (needs `users.manage`). Columns: **User** (display name, user name, first and last name,
description), **Role** (a drop-down you can change in place), **Further roles**, **Status** (Active, Must change
password, Disabled), **Default runner** (a drop-down), **Last sign-in**.

### Add a person

1. Press **New user**. Enter **User name** (for example `jane.doe`), **Display name** and **Role**.
2. Press **Create**. The **User created** box shows a **temporary password** once. Give it to the person by a safe
   channel; they must change it at their first sign-in.

### Change a person

- **Edit**: first name, last name, display name, description and **Further roles** (tick extra roles; the person has
  every permission of each role; Administrator is only ever a main role).
- **Default runner**: choose the runner their runs go to when they are a run-as user, or that the notification-area
  assistant uses. See [07 Runner guide](07-runner-guide.md#default-users-and-the-default-runner-of-a-person).
- **Disable**/**Enable**: a disabled person cannot sign in, and schedules they own stop running.
- **Reset password**: makes a new temporary password (shown once); the account is also unlocked.
- **Reset two-step**: for a lost phone. Their sign-ins end and they set two-step sign-in up again.
- **Delete**: allowed when they own no schedules, triggers, API tasks, running queues or single-owner vault folders.
  Their sign-ins, API keys, team places, own vault values and every rule naming them go; the audit trail keeps their
  name.
- Click a person (with `audit.view`) to see their details and **Recent activity** from the audit trail.

Five wrong passwords lock an account for 15 minutes.

## 3. Roles and permissions

**Administration > Roles** (needs `users.manage`) lists built-in and custom roles, their permissions and the number
of users.

| Built-in role | Id | Can |
| --- | --- | --- |
| Administrator | `admin` | Everything |
| Agent builder | `bot_creator` | View, check in and delete agents; start and stop runs; manage schedules, triggers, work queues, API tasks and Prompt Skills; own vault folders; view runners, shared values and Operations |
| Agent operator | `bot_runner` | View agents, start and stop runs, work on work queue items; view runners, schedules, triggers, shared values, Operations and Prompt Skills |
| Viewer | `viewer` | Read only: agents, runs, runners, schedules, triggers, shared values, work queues, Operations, Prompt Skills |

### Make a custom role

1. Press **New role**. Give a **Name** (letters, digits, `_`, for example `invoice_clerk`) and a **Description**.
2. Tick permissions, grouped by area (`repo`, `runs`, `devices`, `schedules`, `triggers`, `queues`, `lockers`,
   `globals`, `analytics`, `skills`, `audit`, `settings` ...). A "queue clerk" might have only `queues.view` and
   `queues.work`.
3. Press **Save role**. Give it to people on the Users page.

Roles in use cannot be deleted. Rules that name a role, such as `role:invoice_clerk` in folder permissions, vault
folders, work queues, workflow tasks and model connections, apply to everyone with that role as main or further role.

**Switching off built-in roles**: in **Settings > Security**, tick **Switch off Agent builder**, **Switch off Agent
operator** or **Switch off Viewer** for roles you do not use. They then grant nothing and cannot be given; people who
still have one keep only their other roles. Administrator cannot be switched off.

## 4. Teams

**Administration > Teams** (needs `teams.manage`): named groups of people, each a member or a lead.

1. Press **New team**, give a **Name** (used in rules as `team:name`) and **Description**, then **Save**.
2. In **Members of ...**, pick people (or type a user name), press **+ Add**, choose **Member** or **Lead (changes the
   members)**, and **Save**.

Name the team as `team:finance` in folder permissions, vault folders (managers, participants, consumers) and work
queue members: everyone added to the team gets all of it. A team that a rule still names is not deleted. Team leads
may change their own team's members (the API allows it), but the **Teams** page is only in the menu for people with
`teams.manage`.

## 5. Sign-in and security

All in **Administration > Settings** unless stated; press **Save settings** at the bottom.

### Password rules (Security)

| Field | Meaning |
| --- | --- |
| **Shortest password (characters)** | 12 to 64 (12 by default) |
| **Passwords that cannot be used again** | The last N passwords (0 = no rule) |
| **Change passwords every (days)** | 0 = never. Someone whose password expired must choose a new one before anything else |
| **Sign-ins per person at once** | Sessions per person (5 by default); the oldest end first |
| **Sign-in session length (hours)** | 8 by default, up to 720 |
| **Disable users inactive for (days)** | 0 = never |
| **IP allow list** | Addresses or CIDR ranges (`10.0.0.0/8, 203.0.113.7`). Empty lets every address in; this computer is always allowed |

Single sign-on users are not affected by password rules.

### Two-step sign-in (MFA)

**Two-step sign-in**: **Optional (each person decides)**, **Required for administrators** or **Required for everyone
with a password**. People who still need to set it up can do nothing else until they have. Single sign-on users are
left to their identity provider, and API keys are not affected. Each person turns it on in the user menu > **Two-step
sign-in** and gets ten recovery codes. For a lost phone use **Reset two-step** on the Users page (or `ooa reset-admin`,
which also clears it).

### Single sign-on with OpenID Connect

1. In your identity provider (Microsoft Entra ID, Okta, Google, Keycloak ...) register an application with the
   redirect address shown in Settings: `<public address>/api/auth/sso/callback`.
2. Store its client secret in a vault folder.
3. Fill in **Issuer URL**, **Client id**, **Client secret (vault reference folder/credential/attribute)**, **Role for new
   people** (empty = existing accounts only) and **Button text**.
4. Save. The sign-in page shows the button. At first sign-in an existing account with the same verified e-mail or
   user name is linked; otherwise a new account is made with the role for new people. Signature, issuer, audience,
   expiry and a one-time nonce are checked, with PKCE.

### Single sign-on with SAML 2.0

1. Give the identity provider (Entra ID, Okta, ADFS ...) the metadata address `<public address>/api/auth/saml/metadata`
   (sign-in address `/api/auth/saml/acs`).
2. Fill in **Identity provider sign-in address (SAML SSO URL)**, **Identity provider entity id (issuer)**,
   **This Command Center's entity id** (empty = the metadata address), **Button text** and **Identity provider
   signing certificate (PEM)**.
3. Have the provider sign the assertion or the response, and not encrypt it: encrypted assertions are refused.
   Sign-ins must start at the Command Center's sign-in page.

### Other security switches

- **Block programs and scripts (.exe, .dll, .ps1 and similar) in repository uploads**.
- **Approval before production** (section 10).
- **Audit forwarding (SIEM)**: **Syslog target (host:port)** and **Protocol** (UDP or TCP); every audit entry is also
  sent as an RFC 5424 syslog message. **Send a test message** checks it.
- **Announcement for all users**: a banner for everyone, for example before maintenance.

## 6. API keys and tokens

Each person makes their own keys in the user menu > **API keys** > **New key**: a **Name** and **Valid for** (30 days,
90 days, 1 year, never). The key (`ooak_...`) is shown once; only a hash is stored. Systems call the API with
`Authorization: Bearer ooak_...`, with that person's permissions. **Revoke** stops a key at once.

For other systems that prefer short-lived tokens: `POST /api/auth/token` with `{"api_key": "ooak_..."}` returns a
session token (8 hours, extended while used); `POST /api/auth/refresh` replaces it; `GET /api/auth/token` tells
whether a token is valid, for whom and until when.

## 7. One Agent Vault

**Manage > One Agent Vault** holds credentials, encrypted with AES-256-GCM. Values are never shown again after saving,
are handed to an agent only while it runs, and every use is audited. Agents refer to a value as
`folder/credential/attribute`, for example `finance/erp/password`.

### Vault folders and members

1. Press **New vault folder** (needs `lockers.manage`, or `lockers.own` for builders). Give a **Name**, **Description**
   and **Consumers (who may use it in agents)**, such as `role:bot_runner, user:jane`. You become the owner.
2. **Members** sets who may do what:

   | Member | Can |
   | --- | --- |
   | Owners (user names) | Everything, members included |
   | Managers | Add, change and delete every credential |
   | Participants | See the folder, add credentials and change the ones they own |
   | Consumers | Their agents may use the credentials |

   Managers, participants and consumers can be users (`user:jane`), roles (`role:bot_runner`) or teams (`team:finance`).
3. **Add or update credential**: **Credential**, **Attribute** (for example `password`) and **Value**, then **Save**.
4. An empty folder can be deleted (**Delete folder**).

### Credential details

Each folder card lists credentials with their attributes, owner and last change. **Details** opens:

- one row per attribute with a **Description**, **Masked** (on by default: never shown; clear it for non-secret
  values such as a user name, so owners and managers can **Show** it, each look audited) and **Password fields only**
  (agents may type the value only into password fields);
- **Owner** (owners and managers can hand a credential over) and **Description**;
- **User-provided attributes (each person stores their own)**: for example `password`, so each person keeps their
  own value under **Your values**. A run reads the value of the person it runs as. A shared value cannot be set for
  such an attribute.

**Credential requests** (with a count) lists the user-provided attributes you have not filled in yet; fill them in
and press **Save**.

### The vault key

**Vault key** (needs `lockers.manage`) shows where the master key comes from, its fingerprint and how many values it
protects. **Rotate key** makes a new key and encrypts every stored value again in one transaction (shared values,
people's own values and external-vault secrets); the old key file stays as `master.key.old-...`. With
`OOA_MASTER_KEY`, rotate where that variable is managed. Keep the key apart from backups: a backup never holds it.

### External vaults

On a folder, **Link an external vault** keeps the secrets in your company vault instead:

| Provider | Fields | Secret |
| --- | --- | --- |
| HashiCorp Vault | Vault address, KV v2 mount, Namespace | Token |
| Azure Key Vault | Key Vault address, Tenant id, Application (client) id | Client secret |
| AWS Secrets Manager | Region, Access key id, Endpoint | Secret access key |
| CyberArk Central Credential Provider | Central Credential Provider address, Application id, Safe, Folder | Password (optional) |

Agents still ask for `folder/credential/attribute`; the credential name is the secret's name and the attribute its
field (`value` for single-value secrets). The value is read live. **Test reading** checks access without showing the
value. **Unlink** removes the link.

## 8. Shared values and Connections

**Manage > Shared values** (view `globals.view`, change `globals.manage`): values every agent reads as
`$global.name`, such as a reports folder. **New shared value**: a **Name** and a **Value (JSON)** (`"D:/Reports"`,
`1000`, `{"a": 1}`). Tick **People may set their own value** to let each person keep a **My value** that their runs
use; turning it off removes everyone's own values.

**Manage > Connections** (change `globals.manage`): a connector's address and sign-in, kept once and chosen by name on
agent steps. **New connection**: **Name**, **Toolkit**, **Description** and the toolkit's settings; secret settings
are vault references, read when the agent runs, as the person it runs as. Leave a setting empty to set it on each
step instead. Deleting a connection makes steps that use it fail until another is chosen.

## 9. Toolkits

**Manage > Toolkits** (needs `toolkits.manage`) lists every toolkit with its id, versions, number of actions,
**Used by** (click for the agents) and **Status**.

- **Switch off** stops agents that use the toolkit from being checked in or run (you are warned how many use it).
  **Switch on** reverses it. The core toolkit is always on.
- **Versions** lists the agents that use a toolkit, the range each requires and whether the installed version fits.
  **Update agents** sets a new range (for example `^2.0.0`) on all of them; each gets a new version, the live version
  stays until someone makes the new one live, and agents checked out by others are left alone.

### Your own signed toolkits

Developers build toolkits with the SDK and sign them:

```
ooa toolkit keygen --publisher acme --out keys
ooa toolkit sign --jar billing.jar --class com.acme.billing.BillingToolkit --key keys/acme.key
ooa toolkit inspect --jar billing.jar
```

1. **Trusted publishers**: enter the **Publisher name** and paste the contents of its `.pub` file as **Public key**,
   then **Trust publisher**.
2. **Upload toolkit**: choose the signed jar (10 MB at most). It is refused when unsigned, signed by an untrusted or
   different key, using a product toolkit's id or another publisher's id, or reusing a version number with other
   contents.
3. **Your toolkit versions** lists uploaded versions with their SHA-256; **Delete** removes one unless it is the only
   one and agents use it. A publisher can be removed once none of its versions are left.

Several versions stay installed side by side; agents get the newest version their range allows. Runners check each
jar's checksum and signature before loading it (`RUN.TOOLKIT_LOAD` otherwise). A trusted publisher's code runs with
the runner's rights: trust only code you would install yourself.

## 10. The agent repository: folders, approvals, Git, code analysis

These are in **Build > Agent repository**; builders use it daily (see
[15 Publishing](15-publishing-to-command-center.md)). Administrator tasks:

- **Folder permissions** (needs `repo.permissions`): choose a folder, press **Folder permissions**, add roles
  (or **Everyone**) or users with **View (see, export)**, **Run (also run and schedule)**, **Edit (also check out,
  check in, delete)** or **Manage (also live version and labels)**. The nearest folder with permissions decides; a
  folder with permissions shuts out everyone it does not name; folder access never grants what the role does not
  allow.
- **Approval before production** (Settings > Security): a version then goes live only after a second person with
  `repo.approve` approves it. Requests appear under **Production approvals (n waiting)**, with **Approve**, **Reject**
  and **Withdraw**; nobody decides their own request.
- **Set as production** in **Versions** (needs `repo.production`) picks the live production version directly when
  approval is off.
- **Export / promote** and **Import** (need `lifecycle.export` and `lifecycle.import`) move agents between Command
  Centers as checked bundles.
- **Deleted agents** brings deleted agents back (**Recover**).
- **Code analysis** sets the rules every check-in is checked against, each on or off and **Error** or **Warning**, and
  **Refuse check-ins that have errors**. **Report for all agents (CSV)** downloads the findings.
- **Git sync of the agent repository** (Settings): **Remote**, **Branch**, **User name**, **Access token (vault
  reference)** and **Push after every check-in, production change and deletion**; **Save and sync now** pushes at once.
  The Command Center stays the source of truth: changes made in Git are overwritten.

## 11. Templates

**Build > Templates** lists ready-made agents. **Install** (needs `lifecycle.import`) puts a template's agents into a
folder (default `Templates`); every agent is checked first and nothing is installed unless all pass. The catalogue is
signed by ONEOFFICEAUTOMATION; a changed one is refused. **Settings > Template catalogue** sets another https address,
or `off`. Without internet access, download `templates.json` from the releases page and use **Upload a catalogue
file**.

## 12. Settings

**Administration > Settings** (needs `settings.manage`) is one long page; press **Save settings** at the end.

| Group | What you set |
| --- | --- |
| General | Announcement; inactive-user days; **Sign-in session length**; **Largest upload (MB)** (5 by default; limits files sent inside requests, such as Excel work items or licences); **Work queue check interval (seconds)**; **Minutes saved per successful run** (the default for Home's "hours saved"); **Public address of the Command Center** |
| Single sign-on | OpenID Connect and SAML 2.0 (section 5) |
| Git sync | Section 10 |
| AI assistants (Model Context Protocol) | **Agents offered as tools** (paths, or `*`), **Runner or runner pool id**, **Wait for a result (seconds)**, **Token audience** and **Scope** for OAuth sign-in. Assistants connect to `/api/mcp` with a user's API key |
| Security | Section 5, plus **Update runners automatically**, **Remove temporary runners after this many hours without contact** (8 by default) and **Give runners a new secret every (days)** (30 by default) |
| Outgoing connections | **Proxy (http://host:port)** and **Reach these directly** for the Command Center's own connections (single sign-on, Git, AI, external vaults). Runners have their own proxy (`ooa agent network`) |
| Audit forwarding (SIEM) | Section 5 |
| E-mail | **Outgoing mail server**, **Port**, **Security** (STARTTLS, SSL/TLS, None on a trusted network only), **Account**, **Password (vault reference)**, **Sender address**, **Also send licence reminders to**; **Send a test e-mail**. Used for licence reminders and workflow e-mail steps |
| Several nodes | Lists the nodes and which one leads background work; **Shared folder for repository files** (for example `\\fileserver\ooa\repo-files`). Every node needs the same `OOA_MASTER_KEY` |
| Template catalogue | Section 11 |
| Backups | **Back up every (hours, 0 = never)**, **Keep backups for (days)** (7 by default), **Backup folder**; **Back up now**. One zip holds every table, the repository files and the configuration, never the vault key |
| Run history | **Keep step logs for**, **Keep runs for**, **Keep audit entries for** (at least 90 days, older ones archived to `audit-archive`), **Keep AI call records for**, **Keep finished work-queue items for** (0 = always); **Remove now** |
| This Command Center | Edition, limits, active users, runners, agents and toolkits (read only) |
| Database | Engine, server, database, account, encryption, schema version, size (read only; changed with `ooa db`) |

Backups from the command line: `ooa backup now`, `ooa backup list`, and with the server stopped
`ooa backup restore FILE` (`--replace` to overwrite). Restore refuses a backup made with another vault key.

## Licences and seats

**Administration > Licenses** (view needs `users.manage`; installing needs `settings.manage`).

### Install a licence

1. Make sure the Command Center runs on SQL Server (or PostgreSQL). On the built-in database the page shows the
   **Move to Microsoft SQL Server before licensing** steps instead of the install form.
2. Copy the **Install id of this Command Center** (`OOA-XXXX-XXXX-XXXX-XXXX`) and send it with your licence request.
   A licence issued for it works only here.
3. When the `.lic` file arrives, check it if you like with `bin\ooa.bat license show yourfile.lic`.
4. Under **Install a license**, choose the **License file** (or paste its text) and press **Install license**.
   Installing ends the evaluation. A renewal or a file with more seats replaces the old one; seats already given stay.
   A file with fewer seats than are in use is refused until seats are taken back.

### Give out seats

| Seat | Given to | Allows |
| --- | --- | --- |
| Developer | A person | Checking agents in (Studio, API), and running by hand on their own default runner |
| Citizen Developer | A person | The same |
| Attended Runner | A person | Running agents by hand on their own default runner |
| Unattended Runner | A runner | Taking any work: manual, scheduled, triggered, API, work queues |

Tick the boxes in the **People** table (Developer, Citizen Developer, Attended Runner) and the **Runners** table
(Unattended Runner). More seats than the file holds is refused with `CR.LICENSE_LIMIT`. Deleting a runner frees its
seat. A serverless API task's virtual runner also needs an Unattended Runner seat.

The **Seats** card shows each type's use; the **Usage** card shows seats in use per day over 90 days and every holder
with **Since**, **Last active** and **Runs (30 days)**. A seat unused for 30 days is marked **idle**: a candidate to
take back. **Export CSV** downloads the list.

### Licence states

| State on the page | Effect |
| --- | --- |
| Evaluation | 30 days, 2 developers, 2 attended, 2 unattended runners, given out automatically |
| Active | Seats apply |
| Expiring soon | 30 days or fewer left: a banner for administrators, red and permanent in the last 7 days |
| Expired: grace period | Everything keeps working for the grace days |
| Expired / Evaluation ended / Needs SQL Server | Runs stop, queued runs fail, only administrators can sign in |

Once e-mail is set up, administrators with an e-mail address (and the extra addresses in Settings) get reminders at 30,
15, 7, 3, 2 and 1 days, and when it expired.

## 14. One Audit Trail

**Administration > One Audit Trail** (needs `audit.view`). Every sign-in, change, run, credential use, task and
workflow event is recorded and hash-chained.

- Search with **Search everything**, **Who**, **Action** (a prefix such as `repo.` or an exact action like
  `run.start`), **Object type**, **Result** (Succeeded, Failed, Refused, Not found), **From**, **To** and
  **Newest first**/**Oldest first**. Results come 100 per page with the total.
- **Export CSV** downloads the search (up to 50,000 records); the export itself is recorded.
- **Verify integrity** re-checks the chain: "Chain intact: n records verified" or "TAMPERING DETECTED at record n". It
  also compares with `audit-checkpoints.log` in the data folder (written every hour; copy it to write-once storage),
  so deleting the newest records is found too.

## 15. AI: model connections, Prompt Skills and AI governance

**AI > One Model Connections** (needs `connections.manage`):

1. **New connection**: **Name**, **Provider** (OpenAI, Azure OpenAI, Anthropic, Amazon Bedrock, Google Vertex AI,
   Amazon SageMaker endpoint, Hugging Face, OpenAI-compatible), **Model**, **Type** (standard or fine-tuned),
   **Endpoint**, **API key from the One Agent Vault** (a vault reference), **Roles that may use it** (empty = every
   role) and **Description**. A help line explains each provider's model and endpoint.
2. **Privacy and safety**: a **Microsoft Presidio analyzer** address for extra masking, and **Content check** with the
   provider's moderation endpoint (`AI.CONTENT_FLAGGED` when an answer is flagged).
3. **Budget and limits**: **Tokens a month**, **Calls a minute** (more calls wait) and **Calls a run** (more fail with
   `AI.CALL_LIMIT`). When the month's tokens are used, steps fail with `AI.BUDGET_REACHED`.
4. **Test connection** sends one tiny request with the key from the vault (audited). **Save connection**.

**AI > Prompt Skills**: reviewed prompt templates. People with `skills.all` can mark a skill as a **Template** and
change anyone's skills.

**AI > AI governance** (needs `audit.view`) shows every AI call over 7, 30, 90 days or a year: calls, tokens, calls
with personal data masked, calls where the AI used tools, average answer time, **By model**, **By agent** and **Recent
AI calls** with the prompt's fingerprint (SHA-256). Prompts are never stored.

## 16. Languages

The Command Center and Studio are available in English, Deutsch, Français, Español and 日本語. Each person chooses under
**Language** in the user menu; the choice is kept per browser, and without one the browser's language is used when
available. Names, ids, data, code and server messages are not translated.

## 17. If it goes wrong

| Problem | What to do |
| --- | --- |
| Nobody can sign in as administrator | Stop the server, run `ooa reset-admin` (or `--user NAME`), start it and sign in with the printed one-time password |
| Start-up stops with `CR.DB_UPGRADE_NEEDED` | Stop it, run `ooa db migrate`, start again |
| Licence refused with `LICENSE.INVALID` | The file was changed; ask for a new one |
| Licence refused with `LICENSE.OTHER_INSTALL` | It was issued for another install id |
| Licence refused with `LICENSE.DB_REQUIRED` | Move to SQL Server first |
| Single sign-on fails | Check the **Public address**, the redirect address registered with the provider, and the client secret's vault reference |
| An upload is refused as too large | Raise **Largest upload (MB)** |
| Moving to a new server | Copy the data (or point at the same database), keep the same vault key, then run `ooa agent move --url ...` on each runner ([07](07-runner-guide.md)) |

## Related guides

- [00 Index](00-README.md)
- [01 Getting started](01-getting-started.md)
- [05 Operator's guide](05-operators-guide.md)
- [07 Runner guide](07-runner-guide.md)
- [15 Publishing to the Command Center](15-publishing-to-command-center.md)
- Reference: [CONTROL_ROOM_AND_AGENT.md](../CONTROL_ROOM_AND_AGENT.md), [LICENSING.md](../LICENSING.md),
  [DATABASE.md](../DATABASE.md), [CLOUD_INSTALL.md](../CLOUD_INSTALL.md)
