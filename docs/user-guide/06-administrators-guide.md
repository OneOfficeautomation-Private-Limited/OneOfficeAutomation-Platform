# Administrator's guide

**Audience:** administrators of One CommandCenter: the people who set it up, give people access, keep secrets safe,
manage licences and answer to auditors.

**You need:** the **Administrator** role, or a custom role with the permissions named in each section. The
administrator-only permissions are `repo.production`, `repo.approve`, `repo.permissions`, `lifecycle.export`,
`lifecycle.import`, `queues.all`, `teams.manage`, `users.manage`, `devices.manage`, `lockers.manage`,
`globals.manage`, `audit.view`, `pools.manage`, `settings.manage`, `connections.manage`, `toolkits.manage`,
`skills.all`, `alerts.manage`, `insights.design` (make, share and e-mail dashboards) and `insights.admin` (savings and
the yearly goal in **Business > Value**).

## Contents

1. [Before you go live](#1-before-you-go-live)
2. [Users](#2-users)
3. [Roles and permissions](#3-roles-and-permissions)
4. [Teams](#4-teams)
5. [Sign-in and security](#5-sign-in-and-security)
6. [API keys and tokens](#6-api-keys-and-tokens)
7. [Credential vault](#7-credential-vault)
8. [Shared Global values and Connections](#8-shared-global-values-and-connections)
9. [Toolkits](#9-toolkits)
10. [The agent repository: folders, approvals, Git and policies](#10-the-agent-repository-folders-approvals-git-and-policies)
11. [Templates](#11-templates)
12. [Settings](#12-settings)
13. [Licences and seats](#licences-and-seats)
14. [Audit Trail](#14-audit-trail)
15. [AI: model connections, Prompt Skills and AI governance](#15-ai-model-connections-prompt-skills-and-ai-governance)
16. [Languages](#16-languages)
17. [If it goes wrong](#17-if-it-goes-wrong)
18. [Related guides](#related-guides)

## 1. Before you go live

A checklist for a production One CommandCenter:

1. **Install it on a server** with the setup package as a service:
   `Install.cmd -Silent -Role CommandCenter -AllUsers -Service -Port 8720`, or the MSI
   `msiexec /i ooa-<version>.msi /qn ROLE=CommandCenter SERVICE=1 PORT=8720`. Both make a service that listens on every
   network card, so runners on other machines reach it (`-ListenAddress 127.0.0.1` / `LISTEN_ADDRESS=127.0.0.1` keeps
   it on this computer only); the MSI keeps the data in `%ProgramData%\OneOfficeAutomation\One CommandCenter` unless you
   give `DATA_DIR`. Installing again keeps an existing service and its settings; to change them, run
   `ooa service uninstall` and install again. Or, from an installed `bin` folder,
   `ooa service install --mode control-room --data D:\OOA\cr --port 8720` (`--mode server` adds Studio; a service has no
   desktop, so no built-in runner). Manage it with `ooa service start|stop|status|uninstall` or in Windows Services; it
   restarts by itself after a failure. Its log is `<data>\logs\service-<mode>.log`.
2. **Move to SQL Server** (needed before a licence can be installed):
   `ooa db configure`, `ooa db set-password`, `ooa db set-password --setup`, `ooa db init`, and, to keep evaluation
   data, `ooa db copy --from "<old data folder>"`. Details in [Databases](../infrastructure/05-databases.md). Setup can do it in one go
   with `-DbServer SQL01 -DbPasswordEnv OOA_APP_PW -DbSetupPasswordEnv OOA_SETUP_PW`.
3. **Protect the vault key**: set `OOA_MASTER_KEY` (32 random bytes, Base64) outside the data folder, or point
   `OOA_MASTER_KEY_SOURCE` at Azure Key Vault (`azure-keyvault:https://NAME.vault.azure.net/secrets/SECRET`), AWS
   Secrets Manager (`aws-secrets:REGION/SECRET-ID`), Google Secret Manager
   (`gcp-secret:projects/P/secrets/S/versions/latest`) or `prompt` (an operator types it at start). Without it, a
   `master.key` file is made next to the database.
4. **Turn on HTTPS** (below) before other machines connect. A service made by the installers already listens on
   every network card; one made with `ooa service install` needs `--host 0.0.0.0`.
5. Run `bin\ooa.bat check`. Fix every **FAIL**.
6. Set the **Public address of One CommandCenter** in Settings (needed for single sign-on redirects).
7. Set up backups, e-mail, password rules and two-step sign-in in **Settings** (section 12). Give the install an
   **Environment name** (for example *Production*, in red) so nobody mistakes it for a test install.
8. Install the licence and give out seats (section 13).

### HTTPS and network access

Stop One CommandCenter, then from `bin`:

| Command | What it does |
| --- | --- |
| `ooa tls configure --pfx C:\certs\ooa.pfx` | Use the company's certificate (.pfx or .p12 with its private key) |
| `ooa tls set-password` | Store the .pfx password, encrypted with the vault key (or `--from-env VAR`) |
| `ooa tls self-signed --host ooa.example.com` | A test certificate (browsers warn; runners need `--ca`) |
| `ooa tls show` | The certificate in use, its names and validity |
| `ooa tls export-cert --out ooa.cer` | The certificate without its key, for runners and browsers to trust |
| `ooa tls off` | Back to plain HTTP (the settings file is renamed, not deleted) |

Every `ooa tls` command takes `--data DIR` when the data folder is not the default. The settings take effect at the
next start.

With HTTPS on, the port no longer answers plain HTTP, the session cookie is `Secure` and responses carry
`Strict-Transport-Security`. The runner and Studio built into `ooa server` keep working without trusting the
certificate: they use a second, plain-HTTP listener that only this computer can reach (127.0.0.1). Runners on other
machines must trust the certificate ([07 Runner guide](07-runner-guide.md#5-proxies-and-certificates)). A reverse proxy
(IIS, nginx) in front also works; leave HTTPS off on the server then.

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
- **Delete**: allowed when they own no schedules, triggers, Agent APIs, running queues or single-owner vault folders.
  Their sign-ins, API keys, team places, own vault values and every rule naming them go; the audit trail keeps their
  name.
- Click a person (with `audit.view`) to see their details and **Recent activity** from the audit trail.

By default, five wrong passwords in a row lock an account for 15 minutes; you can change both numbers in **Settings >
Sign-in and security > Lockout**. **Reset password** unlocks the account at once.

People can also arrive without being added here: from Active Directory or LDAP at their first sign-in, or from your
identity provider through SCIM (section 5). Their role then comes from their groups.

## 3. Roles and permissions

**Administration > Roles** (needs `users.manage`) lists built-in and custom roles, their permissions and the number
of users.

| Built-in role | Id | Can |
| --- | --- | --- |
| Administrator | `admin` | Everything |
| Agent builder | `bot_creator` | View, check in and delete agents; start and stop runs; manage schedules, triggers, work queues, Agent APIs and Prompt Skills; own vault folders; view runners, shared values and Operations |
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

**Switching off built-in roles**: in **Settings > Sign-in and security > Built-in roles in use**, switch off the roles
you do not use (Agent builder, Agent operator, Viewer). They then grant nothing and cannot be given; people who
still have one keep only their other roles. Administrator cannot be switched off.

## 4. Teams

**Administration > Teams** (needs `teams.manage`, or leading a team): named groups of people, each a member or a lead.

1. Press **New team**, give a **Name** (used in rules as `team:name`) and **Description**, then **Save**.
2. In **Members of ...**, pick people (or type a user name), press **+ Add**, choose **Member** or **Lead (changes the
   members)**, and **Save**.

Name the team as `team:finance` in folder permissions, vault folders (managers, participants, consumers) and work
queue members: everyone added to the team gets all of it. A team that a rule still names is not deleted. Team leads
may change their own team's members: the **Teams** page is in the menu for people with `teams.manage` and for anyone
who leads a team, and a lead sees the teams they are in.

## 5. Sign-in and security

All in **Administration > Settings > Sign-in and security** unless stated. Change the values, then press **Save Sign-in
and security** in the bar that appears (section 12). The defaults keep the behaviour of earlier versions, so nothing
changes until you change it.

### Sessions

| Setting | Meaning |
| --- | --- |
| **Sign-in session length** | How long a sign-in lasts, in hours: 8 by default, up to 720 |
| **Sign-ins per person at once** | Sessions per person (5 by default); the oldest end first |
| **Disable people inactive for** | Accounts nobody has used for this many days are switched off (0 = never) |
| **Sign out after no activity for** | Signs people out of the console after this many minutes without a key press, click or scroll; the sign-in page then tells them why (0 = never) |

### Password rules

| Setting | Meaning |
| --- | --- |
| **Shortest password** | 12 to 64 characters (12 by default). Letters and digits are always needed |
| **Passwords that cannot be used again** | The last N passwords a person used (0 = no rule) |
| **Change passwords every** | In days; 0 = never. Someone whose password expired must choose a new one before anything else |
| **Warn before a password must change** | This many days before, a warning at the top of the console offers **Change it now** (needs **Change passwords every**) |
| **Keep a new password at least** | Days before a new password can be changed again, so nobody cycles back to an old one (a reset or an expired password can always be changed) |
| **Upper- and lower-case letters** | New passwords need both |
| **A symbol, such as ! or #** | New passwords need a character that is not a letter or a digit |

New rules apply to new passwords; passwords people have now keep working until they are changed. Single sign-on and
directory users are not affected by password rules.

### Lockout

**Lock an account after** a number of wrong passwords or two-step codes in a row (5 by default; 0 never locks, which
makes guessing easier), and **Keep it locked for** a number of minutes (15 by default). **Reset password** on the Users
page unlocks an account at once.

### The sign-in page

- **Notice on the sign-in page**: text shown before anyone signs in, for example terms of use or "This system is for
  authorised use only".
- **People accept the notice to sign in**: people must tick **I have read and accept this** before the sign-in and
  single sign-on buttons work. OneAgentStudio asks for the tick too when it connects.
- The **Environment name** (Settings > General, section 12) is shown on the sign-in page as well.

### Network

| Setting | Meaning |
| --- | --- |
| **IP allow list** | Addresses or CIDR ranges (`10.0.0.0/8, 203.0.113.7`). Empty lets every address in; this computer is always allowed |
| **Trusted proxies** | The addresses or CIDR ranges of your load balancers and reverse proxies (`10.0.5.10, 10.0.6.0/24`). Requests through them are counted under the client's own address (from `X-Forwarded-For`) for the allow list, the sign-in limits and the audit trail. Leave empty when nothing sits in front of One CommandCenter |

### Two-step sign-in (MFA)

**Two-step sign-in**: **Optional (each person decides)**, **Required for administrators** or **Required for everyone
with a password**. People who still need to set it up can do nothing else until they have. Single sign-on users are
left to their identity provider, and API keys are not affected. Each person turns it on in the user menu > **Two-step
sign-in** and gets ten recovery codes. For a lost phone use **Reset two-step** on the Users page (or `ooa reset-admin`,
which also clears it).

### Single sign-on with OpenID Connect

These settings are in **Settings > Single sign-on**.

1. In your identity provider (Microsoft Entra ID, Okta, Google, Keycloak ...) register an application with the
   redirect address shown in Settings: `<public address>/api/auth/sso/callback`.
2. Store its client secret in a vault folder.
3. Fill in **Issuer URL**, **Client id**, **Client secret** (a vault reference, folder/credential/attribute) and
   **OpenID Connect button text**, and under **New people and groups** the **Role for new people** (empty = existing
   accounts only).
4. Save. The sign-in page shows the button. At first sign-in an existing account with the same verified e-mail or
   user name is linked; otherwise a new account is made with the role for new people. Signature, issuer, audience,
   expiry and a one-time nonce are checked, with PKCE.

### Single sign-on with SAML 2.0

1. Give the identity provider (Entra ID, Okta, ADFS ...) the metadata address `<public address>/api/auth/saml/metadata`
   (sign-in address `/api/auth/saml/acs`).
2. Fill in **Identity provider sign-in address**, **Identity provider entity id** (its issuer),
   **This One CommandCenter's entity id** (empty = the metadata address), **SAML button text** and **Identity provider
   signing certificate** (PEM).
3. Have the provider sign the assertion or the response, and not encrypt it: encrypted assertions are refused.
   Sign-ins must start at One CommandCenter's sign-in page.

### Groups to roles

**Settings > Single sign-on > New people and groups > Groups to roles** gives people their role from the groups they
are in, at every sign-in. Write one line per group, `group = role`, for example:

```
OOA Admins = admin
Finance Automation = bot_runner
```

The first line that matches wins. The same list is used for single sign-on, Active Directory or LDAP, and SCIM. Every
role named must exist.

### Sign-in with Active Directory or LDAP

People can sign in on the normal sign-in page with their Windows (directory) user name and password. Their password
stays in the directory: One CommandCenter only asks the directory whether it is right.

1. Ask your directory team for a **read-only service account** and put its password in a vault folder.
2. Make sure the Java of One CommandCenter trusts the certificate of your domain controllers (the connection is
   encrypted, `ldaps://`).
3. In **Settings > Single sign-on > Active Directory or LDAP**, fill in:

   | Setting | Example | Meaning |
   | --- | --- | --- |
   | **Directory addresses** | `ldaps://dc1.corp.example.com:636 ldaps://dc2.corp.example.com:636` | One or more addresses; the next is tried when one does not answer. Empty switches directory sign-in off |
   | **Where people are found** | `DC=corp,DC=example,DC=com` | The base of the search |
   | **Service account** | `svc-ooa@corp.example.com` | The account that finds people; it only reads |
   | **Service account password** | `directory/svc-ooa/password` | A vault reference |
   | **Search filter** | `(\|(sAMAccountName={0})(userPrincipalName={0}))` | `{0}` is the user name the person types. Empty: the Windows account name or the user principal name |
   | **User name from** | `sAMAccountName` | The attribute a new account is named after |
   | **Groups inside groups count** | On | Active Directory only: groups that are members of a mapped group count too |

4. Fill in **Groups to roles** (above) with the directory groups and the roles they give.
5. Press **Save Single sign-on**.
6. Check it: type a user name in the box beside **Test the directory** and press the button. It says whether the
   directory answered, who it found, their groups, and the role they would get. With the box empty it only checks that
   the service account can sign in.

The sign-in page then says that Windows (directory) user names and passwords work. The first time someone in a mapped
group signs in, they get an account with their name and e-mail address from the directory. Someone in no mapped group
cannot sign in. Things to know:

- Local accounts (such as the first administrator) keep signing in with their own password.
- An account you switched off here stays off, even if the directory password is right.
- Wrong passwords count towards the lockout. Two-step sign-in works as for local accounts.
- When the directory cannot be reached, sign-in fails; the local password is never used instead.

### SCIM provisioning: people and groups from your identity provider

With SCIM, your identity provider (Microsoft Entra ID, Okta and others) creates people in One CommandCenter, changes
them, switches them off when they leave, and sends their groups. You no longer add people by hand.

1. Open **Settings > Single sign-on** and scroll to **SCIM provisioning**. It shows the address to give the identity
   provider: `<public address>/scim/v2`.
2. Type the name of the identity provider (for example `Entra ID`) and press **Make a SCIM token**.
3. The token is shown **once**: copy it into the identity provider's provisioning settings at once, with the address,
   and press **Done**. Only a fingerprint of it is kept.
4. In **Groups to roles** (above), give the groups the identity provider sends the roles they should give. Without a
   matching group, people get the **Role for new people**; with neither, their account stays switched off.
5. Start provisioning in the identity provider.

People made by SCIM sign in with single sign-on (OpenID Connect or SAML), so set that up too. Things to know:

- Removing a person in the identity provider switches their account off here and ends their sign-ins; their history
  stays.
- SCIM only sees and changes the accounts it made itself. Local and administrator accounts cannot be read, changed or
  switched off by it, and their user names cannot be taken.
- The list of tokens shows who made each one, when, and when it was last used. **Revoke** stops a token at once.
- The **IP allow list** applies to SCIM calls too: allow your identity provider's addresses.

### Other security switches

- **Block programs and scripts in uploads** (.exe, .dll, .ps1 and similar) and **Approval before production**: in
  Settings > Agents and repository (section 10).
- **Audit forwarding to a SIEM** by syslog or HTTPS: in Settings > E-mail, proxy and SIEM (section 12).
- **Announcement**: a banner for everyone, for example before maintenance (Settings > General).

## 6. API keys and tokens

Each person makes their own keys in the user menu > **API keys** > **New key**: a **Name** and **Valid for** (30 days,
90 days, 1 year, never). The key (`ooak_...`) is shown once; only a hash is stored. Systems call the API with
`Authorization: Bearer ooak_...`, with that person's permissions. **Revoke** stops a key at once.

To limit how long keys live, set **Settings > Sign-in and security > Longest an API key may last** (in days, up to
730). A new key asked for with no end, or with a longer one, then gets this lifetime. Keys made earlier keep theirs.
0 leaves the choice to each person (up to 730 days, or no end).

For other systems that prefer short-lived tokens: `POST /api/auth/token` with `{"api_key": "ooak_..."}` returns a
session token (8 hours, extended while used); `POST /api/auth/refresh` replaces it; `GET /api/auth/token` tells
whether a token is valid, for whom and until when.

## 7. Credential vault

**Manage > Credential vault** holds credentials, encrypted with AES-256-GCM. Values are never shown again after
saving, are handed to an agent only while it runs, and every use is audited. Agents refer to a value as
`folder/credential/attribute`, for example `finance/erp/password`. The page has three tabs: **Credentials**, **Vault
folders** and **My requests**.

### Vault folders and members

1. On the **Vault folders** tab press **Create vault folder** (needs `lockers.manage`, or `lockers.own` for builders).
   Give a **Name**, **Description** and **Consumers** (whose agents may use it), such as `role:bot_runner, user:jane`.
   You become the owner.
2. Click the folder to open its page. Its **Members** tab sets who may do what; press **Save members**:

   | Member | Can |
   | --- | --- |
   | Owners (user names) | Everything, members included |
   | Managers | Add, change and delete every credential |
   | Participants | See the folder, add credentials and change the ones they own |
   | Consumers | Their agents may use the credentials |

   Managers, participants and consumers can be users (`user:jane`), roles (`role:bot_runner`) or teams (`team:finance`).
3. On the **Credentials** tab press **Create credential**: choose the **Vault folder**, give a **Credential name** and
   **Description**, and its **Attributes** (for example `password`), each with a **Value** or ticked as **Each
   person's own** (**Add attribute** adds another); then **Create credential**.
4. An empty folder can be deleted: **Delete folder…** in the folder page's **⋯** menu.

### Credential details

The **Credentials** tab lists every credential you can see with its **Vault folder**, **Attributes**, **Each person's
own**, **Owner**, **Expires** and **Updated**. A credential whose expiry date has passed is marked **Expired**, one
that expires within 14 days **In N days**; the count at the top says how many, and the **Expired or expiring** filter
shows only those. Click one to open its page:

- **Attributes**: one row per attribute with its value (type a new one and press **Change**), a **Description**,
  **Masked** (on by default: never shown; clear it for non-secret values such as a user name, so owners and managers
  can **Show** it, each look audited) and **Password fields only** (agents may type the value only into password
  fields);
- **Details**: **Description**, **Owner** (owners and managers can hand a credential over), **Expires on** (the date
  the credential stops working, for example a password changed every 90 days; empty when it does not expire) and
  **Each person's own
  attributes (separated by commas)**: for example `password`, so each person keeps their own value under **Your
  values** (**Save mine**, **Clear mine**). A run reads the value of the person it runs as. A shared value cannot be
  set for such an attribute.

Press **Save details** to keep changes to descriptions, **Masked**, **Password fields only** and **Details**.

**My requests** (with a count) lists the attributes you have to fill in for yourself and have not filled in yet; fill
them in and press **Save**.

### Agents that save credentials

An agent can store a new value of a credential, for example the new password after it reset one, with **Save
credential value** (`ooa.vault`), and set the next expiry date at the same time. This is off until an administrator
ticks **Settings > Agents and repository > Agents may save credentials**. Even then One CommandCenter checks, as the
user the run runs as (its run-as user, or whoever started it), the same rules as for people:

| What the agent saves | The run's user must be |
| --- | --- |
| A shared attribute | An owner or manager of the vault folder, or a participant who owns the credential (or creates it) |
| An attribute that is each person's own | Any member of the vault folder, consumers included: their own value is stored, as **Save mine** does |

Folders linked to a company vault are not written; change the value there. The value must come from the vault or
another Credential, never typed text. Every save, and every refusal, is in the Audit Trail as `credential.set` with
the runner, run, agent and run's user, never the value.

**Check credential expiry** (`ooa.vault`) tells an agent the expiry date, the days left and whether it has expired;
the run's user must be a member of the folder (consumers included). Reading an expired credential still works, as
before: agents that must not use one check first.

### The vault key

**Vault key** (needs `lockers.manage`) shows where the master key comes from, its fingerprint and how many values it
protects. **Rotate key** makes a new key and encrypts every stored value again in one transaction (shared values,
people's own values and external-vault secrets); the old key file stays as `master.key.old-...`. With
`OOA_MASTER_KEY` or a key store, stop One CommandCenter and run `ooa vault rekey --new-key-env VAR` (the new key in
the variable `VAR`), then replace the old key where it is kept and start again. Keep the key apart from backups: a
backup never holds it.

### External vaults

On a folder's **Company vault** tab (for those who manage the folder), choose the **Provider**, fill in its fields and
secret and press **Link** to keep the secrets in your company vault instead:

| Provider | Fields | Secret |
| --- | --- | --- |
| HashiCorp Vault | Vault address, KV v2 mount, Namespace | Token |
| Azure Key Vault | Key Vault address, Tenant id, Application (client) id | Client secret |
| AWS Secrets Manager | Region, Access key id, Endpoint | Secret access key |
| CyberArk Central Credential Provider | Central Credential Provider address, Application id, Safe, Folder | Password (optional) |

Agents still ask for `folder/credential/attribute`; the credential name is the secret's name and the attribute its
field (`value` for single-value secrets). The value is read live. **Test reading** checks access without showing the
value. **Unlink…** removes the link, after asking.

## 8. Shared Global values and Connections

**Manage > Shared Global values** (view `globals.view`, change `globals.manage`): values every agent reads as
`$global.name`, such as a reports folder. **New shared value**: a **Name** and a **Value (JSON)** (`"D:/Reports"`,
`1000`, `{"a": 1}`). Tick **People may set their own value** to let each person keep a **My value** that their runs
use; turning it off removes everyone's own values.

Agents can change an existing shared value with **Set shared value** (`ooa.global_values`) when the user the run runs
as has `globals.manage` (Manage shared values); the value must fit the shared value's type. Agents never create or
delete shared values. Each change is in the Audit Trail as `global.set` with the runner, run, agent and run's user.
A person's or team's own value still wins for their runs.

**Manage > Connections** (change `globals.manage`): a connector's address and sign-in, kept once and chosen by name on
agent steps. **New connection**: **Name**, **Toolkit**, **Description** and the toolkit's settings; secret settings
are vault references, read when the agent runs, as the person it runs as. Leave a setting empty to set it on each
step instead. Deleting a connection makes steps that use it fail until another is chosen.

## 9. Toolkits

**Manage > Agent ToolKits** (needs `toolkits.manage`) lists every toolkit with its **State** (On or Off),
**Versions** (with the default one), number of **Actions**, **Used by** and **Publisher**; hover over a name for its
id. Click a toolkit (or **Open** in its **⋯** menu) to open its own page.

- **Switch off…** (in the row's **⋯** menu, or on the toolkit's page) stops agents that use the toolkit from being
  checked in or run (you are warned how many use it). **Switch on** reverses it. The core toolkit is always on.
- The toolkit's **Agents that use it** tab lists those agents, the range each **Requires** and whether the default
  version fits. Type a new range (for example `^2.0.0`) in **Require in every agent that uses it** and press **Update
  agents**; each gets a new version, the live version stays until someone makes the new one live, and agents checked
  out by others are left alone.

### Your own signed toolkits

Developers build toolkits with the SDK and sign them:

```
ooa toolkit keygen --publisher acme --out keys
ooa toolkit sign --jar billing.jar --class com.acme.billing.BillingToolkit --key keys/acme.key
ooa toolkit inspect --jar billing.jar
```

1. **Trusted publishers**: enter the **Publisher** and paste the contents of its `.pub` file as **Public key**, then
   **Trust publisher**.
2. **Upload toolkit**: choose the signed jar (10 MB at most). It is refused when unsigned, signed by an untrusted or
   different key, using a product toolkit's id or another publisher's id, or reusing a version number with other
   contents.
3. **Your toolkit versions** lists uploaded versions with their SHA-256; **Delete…** removes one unless it is the
   only one and agents use it. **Stop trusting…** in **Trusted publishers** removes a publisher once none of its
   versions are left.

Several versions stay installed side by side; agents get the newest version their range allows. Runners check each
jar's checksum and signature before loading it (`RUN.TOOLKIT_LOAD` otherwise). A trusted publisher's code runs with
the runner's rights: trust only code you would install yourself.

## 10. The agent repository: folders, approvals, Git and policies

The agent repository is the **Public** tab of **Build > Automation**; builders use it daily (see
[15 Publishing](15-publishing-to-command-center.md)). Administrator tasks:

- **Folder permissions** (needs `repo.permissions`): choose a folder, press **Folder permissions**, add roles
  (or **Everyone**) or users with **View (see, export)**, **Run (also run and schedule)**, **Edit (also check out,
  check in, delete)** or **Manage (also live version and labels)**. The nearest folder with permissions decides; a
  folder with permissions shuts out everyone it does not name; folder access never grants what the role does not
  allow.
- **Approval before production** (Settings > Agents and repository): a version then goes live only after a second person with
  `repo.approve` approves it. Requests appear under **Production approvals (n waiting)**, with **Approve**, **Reject**
  and **Withdraw**; nobody decides their own request.
- **Make live** on an agent's **Versions** tab (needs `repo.production`) picks the live production version directly when
  approval is off.
- **Export / promote** and **Import** (need `lifecycle.export` and `lifecycle.import`) move agents between Command
  Centers as checked bundles. To stop agents being promoted anywhere but your own test and production installs, list
  their addresses in **Settings > Agents and repository > Promote agents only to** (separated by commas). Any other
  address is then refused (`CR.TARGET_NOT_ALLOWED`) and the attempt is recorded in the Audit Trail.
- **Delete** on an agent is refused while a schedule, trigger, work queue or API task uses it; the message names
  them (for example "API task Invoice lookup"). Change or delete those first.
- **Deleted agents** brings deleted agents back (**Recover**).
- **Code analysis** and **AI guardrails** are policies given to folders: see [Policies](#policies-code-analysis-and-ai-guardrails)
  below.
- **Git copy of the agent repository** (Settings > Agents and repository): **Git remote**, **Git branch**, **Git user
  name**, **Git access token** (a vault reference) and **Push to Git after every change** (after every check-in,
  production change and deletion); **Sync Git now** pushes at once. One CommandCenter stays the source of truth:
  changes made in Git are overwritten.

### Policies: code analysis and AI guardrails

**Administration > Policies** holds rules that agents follow, set once and given to folders. There are two kinds:

| Kind | What it sets | When it applies |
| --- | --- | --- |
| **Code analysis policy** | The rules agents are checked against (each used or not, an **Error** or a **Warning**, some with a setting such as the most steps an agent may have), and **Refuse check-ins that have errors** | At every check-in and analysis |
| **AI guardrails policy** | **Mask personal data before it reaches a model**, **Models agents may use**, and a **Content check** on answers | At every AI step, when the agent runs |

Two code analysis rules keep screen work in one place. Both are off until an administrator ticks them:

| Rule | What it checks |
| --- | --- |
| **repository_objects** | Web and desktop steps use elements of a shared object repository, not objects captured into the agent itself. |
| **ui_in_object_agents** | Only agents with the tag set on the rule (`object-agent` at first) contain screen steps. By default these are the Browser, Desktop objects, Mouse/keyboard and Window toolkits; the rule's toolkit list sets which. Other agents ("process agents") call those object agents with **Run agent**, as Blue Prism separates objects from processes. |

The code analysis rule **connector_groups** (off at first) groups toolkits the way Power Automate's data policies group
connectors:

- **business**: the toolkits for company systems, such as `ooa.sap`, `ooa.microsoft365` or `ooa.aws*`;
- **blocked**: toolkits no agent in these folders may use;
- **neutral**: toolkits that only work on the agent's own values (text, numbers, dates, lists, tables, logs). They go
  with both groups and are filled in at first;
- every other toolkit is **non-business**.

An agent that uses a business toolkit and a non-business one together is reported, because data could pass from one
to the other. So is every step of a blocked toolkit. Toolkits are named by id (Agent ToolKits shows them), and a name
ending in `*` covers every toolkit that starts with it. With **Refuse check-ins that have errors**, such agents cannot be
checked in to the folders the policy is given to.

A folder follows its own policy of each kind, or else the nearest folder above it that has one; a folder can have one
policy of each kind. Folders with no code analysis policy anywhere above them follow **Default code analysis**, which
cannot be deleted (change its rules to change the default). The page is in the menu for people with `settings.manage`,
who make and change policies.

**Make a policy:**

1. Press **Create policy ▾** and choose **Code analysis policy** or **AI guardrails policy**.
2. On the **Rules** tab, give a **Name** and **What it is for**, then:
   - for code analysis: tick the rules to use, choose **Error** or **Warning** for each, and decide on **Refuse
     check-ins that have errors** (an agent with an error finding then cannot be checked in to these folders; warnings
     are only shown);
   - for AI guardrails: tick **Mask personal data before it reaches a model** (the data is replaced with placeholders
     before it reaches a model and put back in the answer; AI steps cannot switch it off), and under **Personal data to
     mask** tick the kinds to mask:

     | Kind | Masks |
     | --- | --- |
     | **Names and places** | People's names and places. Needs a Presidio analyzer address on the model connection (section 15) |
     | **E-mail addresses and phone numbers** | Contact details |
     | **Card numbers and bank accounts (IBAN)** | Payment details |
     | **Government ids (social security numbers)** | Identity numbers |
     | **IP addresses** | Network addresses |

     All kinds are ticked at first; keep at least one, or switch masking off. Then tick the **Models agents may use**
     (none ticked: any model; a step that asks for another model stops with a clear message), and choose the **Content
     check**: **Off**, **Log only** (a harmful answer is noted in the run log) or **Block** (the step stops). The content
     check uses the model provider's moderation service (OpenAI and Azure OpenAI).
3. On the **Folders** tab, choose a folder and press **Add folder** for each folder the policy applies to. Folders below
   them follow it too.
4. Press **Create policy** (or **Save changes** later). It is used from the next check-in, analysis or run; every
   change is recorded in the Audit Trail.

**See what an AI guardrails policy did.** Open a saved AI guardrails policy and choose its **What it stopped** tab.
Pick a period (the last 7, 30 or 90 days, or the last year). It shows:

- **Steps stopped**, with a list of each one: when, the agent, why (**A model not allowed** or **An answer flagged as
  harmful**) and a link to the run;
- **AI calls with personal data masked** and **Pieces of personal data masked**, with a table of how many of each kind
  (e-mail addresses, phone numbers, card numbers, bank accounts, social security numbers, IP addresses, names, places).

The list shows each policy's **Kind**, **Folders it applies to** and **What it does**; filter it by **Kind** or search
it. **Report (CSV)** checks every agent against its folder's policy and downloads one line per finding. **Delete…** in
a policy's **⋯** menu asks first; its folders then follow the nearest policy above them, or the default.

## 11. Templates

**Build > Templates** lists ready-made agents. **Install** (needs `lifecycle.import`) puts a template's agents into a
folder (default `Templates`); every agent is checked first and nothing is installed unless all pass. The catalogue is
signed by ONEOFFICEAUTOMATION; a changed one is refused. **Settings > General > Template catalogue** sets another https address,
or `off`. Without internet access, download `templates.json` from the releases page and use **Upload a catalogue
file**.

## 12. Settings

**Administration > Settings** (needs `settings.manage`) has its sections down the left side. Each section saves on its
own:

1. Click a section, or type in **Find a setting** to search every section by name, hint or setting key (for example
   `password_min_length`).
2. Change the values. A changed row shows what it was, and a bar appears with the number of changes.
3. Press **Save** *section* in the bar, or **Undo changes**. Every change is recorded in the Audit Trail.

The **i** after the page title, after each section and after each group opens help on every setting. The buttons that
test something (**Send a test e-mail** and the like) use the saved settings, so save first.

| Section | Group | What you set |
| --- | --- | --- |
| **General** | For everyone | **Announcement** (a banner for everyone); **Public address** of One CommandCenter (for sign-in redirects and links in e-mails) |
| | This environment | **Environment name** (for example *Development*, *Test* or *Production*) and **Environment colour** (blue, green, amber, red or grey), shown in the top bar and on the sign-in page so nobody changes production by mistake; **Time zone for new schedules** (such as `Asia/Kolkata`; empty: each person's own; existing schedules keep theirs) |
| | Limits | **Largest upload** (25 MB by default; limits files sent inside requests, such as Excel work items or licences); **Work queue check interval** |
| | Value of automation | **Minutes saved per successful run** (for agents without their own), **Cost of an hour of the manual work** and **Currency**, used by Home and **Business > Value** |
| | Templates | **Template catalogue** (section 11) |
| **Sign-in and security** | | Sessions, passwords, lockout, the sign-in page, API keys, two-step sign-in, network and built-in roles (sections 3, 5 and 6) |
| **Single sign-on** | | OpenID Connect, SAML 2.0, Active Directory or LDAP, groups to roles and SCIM provisioning (section 5) |
| **Runner Machines** | Updates and secrets | **Update runners automatically**, **Give runners a new secret every** (days), **Remove temporary runners after** (hours without contact) |
| | Limits | **Take no new work above this CPU use** and **... memory use** (%) |
| | Registration and connections | **New runners may register** (off: registration keys stop working until it is on again; runners already registered keep working); **Runners connect only over HTTPS** (set up HTTPS first; this computer's own runner always may) |
| | Runs, triggers and work queues | **Refuse runs for a runner that is offline** (off: the run waits until the runner is back; runs for a pool still go to any online runner of the pool); **Switch a trigger off after** a number of failed runs in a row (the reason is shown on Triggers; 0 = never); **Mark work items abandoned after** a number of hours in progress (they are marked failed, so they can be retried; 0 = never) |
| | Screens | **Pictures of a runner's screen** (administrators may ask for one, each recorded in the Audit Trail); **Screenshots when a step fails** (switch it off where screens show private data; the run log then says no picture was taken) |
| **Agents and repository** | Rules | **Approval before production**, **Block programs and scripts in uploads**, **Promote agents only to** (section 10) |
| | Git copy | Section 10 |
| | Files | **Shared folder for repository files**, for several nodes (for example `\\fileserver\ooa\repo-files`) |
| **AI assistants (MCP)** | | **Agents offered as tools** (paths, or `*`), **Runner or runner pool id**, **Wait for a result**, **Token audience** and **Scope the token must carry** for OAuth sign-in. Assistants connect to `/api/mcp` with a person's API key |
| **E-mail, proxy and SIEM** | E-mail | **Outgoing mail server**, **Port**, **Security** (STARTTLS, SSL/TLS, None on a trusted network only), **Mail account**, **Mail account password** (a vault reference), **Sender address**, **Also send licence reminders to**; **Send a test e-mail** |
| | Notifications | **E-mail people when a run they started fails** (the person who started it, or who made its schedule, gets the agent, the reason and a link); **E-mail people when a workflow gives them a task** (forms and approvals; a task for a role goes to each person in it, tasks for everyone stay in the inbox only). People need an e-mail address on their account |
| | Approvals outside the Task inbox | **Approve and reject from e-mail, Teams and phone** (off at first; needs the **Public address**): each person a task is given to gets their own Approve and Reject links, signed by One CommandCenter. A link opens a page that asks to confirm, so a mail scanner that opens links decides nothing, and the task is decided as that person with the inbox's own checks (`task.link_used` in Audit Trail). **Links work for** (1 to 720 hours, 72 at first). **Teams channel webhook**: a vault reference to the address of a Teams Workflows webhook ("Post to a channel when a webhook request is received"), which must be https://; each new form or approval posts an Adaptive Card there, with Approve and Reject when the task is for one person. **Push server for phones**: an ntfy server (ntfy.sh, or one on your own network) and its token, when it needs one; people turn phone notifications on themselves under their account menu. **Send a test Teams card** checks the channel |
| | Proxy | **Proxy for outgoing connections** and **Reach these directly**, for One CommandCenter's own connections (single sign-on, Git, AI, external vaults). Runners have their own proxy (`ooa agent network`) |
| | SIEM | **Syslog target** (`host:port`) and **Syslog protocol** (UDP or TCP); **HTTPS collector** (an address that takes every audit entry as JSON, such as Splunk's HTTP Event Collector, Microsoft Sentinel, Elastic or QRadar), **Collector token** (a vault reference) and **Collector sign-in** (**Bearer token** or **Splunk HTTP Event Collector**). **Send a test syslog message** and **Send a test SIEM entry** check them |
| **Data retention and backups** | Keep history | **Keep step logs for**, **Keep runs for**, **Keep audit entries for** (at least 90 days, older ones archived to `audit-archive`), **Keep AI call records for**, **Keep finished work-queue items for** (0 = always); **Remove old runs now…** asks first, and uses the saved numbers |
| | Backups | **Back up every** (hours, 0 = never), **Keep backups for** (7 days by default), **Backup folder**; **Back up now**. One zip holds every table, the repository files and the configuration, never the vault key |
| **This One CommandCenter** | | The database (engine, server, name, account, encryption, schema version, size; changed with `ooa db`) and the nodes, with the one that leads background work. Every node needs the same `OOA_MASTER_KEY` |

The new settings of November 2026 start with the behaviour of earlier versions (for example, registration open,
screenshots on, nothing refused or switched off), so an upgrade changes nothing until you change a setting.

Backups from the command line: `ooa backup now`, `ooa backup list`, and with the server stopped
`ooa backup restore FILE` (`--replace` to overwrite). Restore refuses a backup made with another vault key.

## Licences and seats

**Administration > Licenses** (shown with `users.manage` or `settings.manage`; installing a licence needs
`settings.manage`, ticking seats needs `users.manage`).

### Install a licence

1. Make sure One CommandCenter runs on SQL Server (or PostgreSQL). On the built-in database the page shows the
   **Move to Microsoft SQL Server before licensing** steps instead of the install form.
2. Copy the **Install id of this One CommandCenter** (`OOA-XXXX-XXXX-XXXX-XXXX`) and send it with your licence request.
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
| Evaluation | 60 days, 2 developers, 2 attended, 2 unattended runners, given out automatically |
| Active | Seats apply |
| Expiring soon | 30 days or fewer left: a banner for administrators, red and permanent in the last 7 days |
| Expired: grace period | Everything keeps working for the grace days |
| Expired / Evaluation ended / Needs SQL Server | Runs stop, queued runs fail, only administrators can sign in |

Once e-mail is set up, administrators with an e-mail address (and the extra addresses in Settings) get reminders at 30,
15, 7, 3, 2 and 1 days, and when it expired.

## 14. Audit Trail

**Administration > Audit Trail** (needs `audit.view`). Every sign-in, change, run, credential use, task and
workflow event is recorded and hash-chained.

- Search with **Search everything**, **Who**, **Action** (a prefix such as `repo.` or an exact action like
  `run.start`), **Object type**, **Result** (Succeeded, Failed, Refused, Not found), **From**, **To** and
  **Newest first**/**Oldest first**. Results come 100 per page with the total.
- **Export CSV** downloads the search (up to 50,000 records); the export itself is recorded.
- **Verify integrity** re-checks the chain: "Chain intact: n records verified" or "TAMPERING DETECTED at record n". It
  also compares with `audit-checkpoints.log` in the data folder (written every hour; copy it to write-once storage),
  so deleting the newest records is found too.

For a summary (who acted, sign-ins and failed sign-ins, what failed or was refused, the busiest day) open
**Operational Insights > Operations > Audit**; its **Open in Audit Trail** brings you back here with the same filters.
To keep a copy outside One CommandCenter, forward every entry to your SIEM by syslog or HTTPS (section 12).

### Event subscriptions: events and run logs to other systems

**Administration > Event subscriptions** (needs `events.manage`; administrators) sends events to other systems the
moment they happen, so a ticketing tool, an integration platform or Splunk does not have to keep asking.

1. Click **New subscription**. Give it a **Name** and the **Receiver address** (`https://...`).
2. Choose the **Format**: **JSON, signed** for your own receiver, or **Splunk HTTP Event Collector** (then paste the
   collector token as the **Collector token**; each run log line becomes one Splunk event).
3. Tick the **Events**: runs (queued, started, finished, failed, paused, resumed, run log lines), work items (added,
   started, finished, abandoned), runners (came online, went offline), alerts raised, every audit entry.
4. Optionally limit it with **Only for these folders or agents** (for example `Finance/`).
5. **Make the subscription**. For the JSON format a signing secret is made and shown **once**: copy it into the
   receiver. It is kept encrypted and never shown again; **Edit > Make a new signing secret** replaces it.

The receiver checks each message: the `X-OOA-Signature` header is `sha256=` and the HMAC-SHA256 of the
`X-OOA-Timestamp` value, a dot and the body, made with the secret. Use `X-OOA-Event-Id` to ignore an event that arrives
twice. Use **Send a test event** in the row's menu to check the receiver; the list shows the last delivery and the
**Failures** in a row (hover for the last error). A receiver that is down is tried again for about three minutes;
events never hold up a run.

Receivers must be `https://` addresses on the public internet. For a test system or a receiver inside your own
network, switch on **Settings > E-mail, proxy and SIEM > Event subscriptions > Allow plain http:// receivers** or **Allow receivers
in private networks**. Every change is in the Audit Trail.

### Metrics for Prometheus and OpenTelemetry

At the bottom of the same page, **Make a metrics token** makes the token Prometheus uses to read `/metrics` (shown
once; making a new one replaces it). Point Prometheus, Grafana Agent or the OpenTelemetry Collector's Prometheus
receiver at `https://<One CommandCenter>/metrics` with the token as a bearer token; with several nodes, scrape each
node. The figures and a scrape example are in
[Monitoring, logging and troubleshooting](../infrastructure/11-monitoring-logging-and-troubleshooting.md#2-health-endpoints).

## 15. AI: model connections, Prompt Skills and AI governance

**AI > Model Connections** (needs `connections.manage`):

1. **New connection**: **Name**, **Provider** (OpenAI, Azure OpenAI, Anthropic, Amazon Bedrock, Google Vertex AI,
   Amazon SageMaker endpoint, Hugging Face, OpenAI-compatible), **Model**, **Type** (standard or fine-tuned),
   **Endpoint**, **API key from the Credential vault** (a vault reference), **Roles that may use it** (empty = every
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
AI calls** with the prompt's fingerprint (SHA-256). Prompts are never stored. **Operations > AI usage** opens the same
page.

To set masking, the models allowed and a content check for all the agents of a folder, use an **AI guardrails**
policy (section 10). It wins over a connection's own content check.

## 16. Languages

One CommandCenter and Studio are available in English, Deutsch, Français, Español and 日本語. Each person chooses under
**Language** in the user menu; the choice is kept per browser, and without one the browser's language is used when
available. Names, ids, data, code and server messages are not translated.

## Moving bots from Automation Anywhere or UiPath

`ooa migrate` turns existing bots into OOA agents, so a migration starts from working structure instead of a blank
page:

```
ooa migrate aa C:\Export\AA-bots C:\Migrated
ooa migrate uipath C:\Projects\Invoices C:\Migrated
```

- **Automation Anywhere**: each Task Bot's JSON content (from the Control Room API, or the JSON in a bot export).
- **UiPath**: each workflow (.xaml) in the folder.

The structure is converted: steps, comments, if / else if / else, loops, try / catch / finally, delays, log lines
(message boxes become log lines), assignments, variables and arguments with their types and directions, and calls to
other bots (as Run agent, to point at the migrated agent). AA's `$var$` becomes `${var}`; UiPath's VB.NET expressions
are kept as expressions to check. Every other command becomes a comment step **TODO migrate:** with the command and
its settings, in its place, so nothing is lost: search the agents for "TODO migrate" and rebuild each with the matching
OOA toolkit. `migration-report.json` lists each bot with how many commands were converted and how many are left, by
command, which is the migration's work list. Check every converted condition and expression before running an agent
in production. Blue Prism processes are not converted yet.

## 17. If it goes wrong

| Problem | What to do |
| --- | --- |
| Nobody can sign in as administrator | Stop the server, run `ooa reset-admin` (or `--user NAME`), start it and sign in with the printed one-time password |
| Start-up stops with `CR.DB_UPGRADE_NEEDED` | Stop it, run `ooa db migrate`, start again |
| Licence refused with `LICENSE.INVALID` | The file was changed; ask for a new one |
| Licence refused with `LICENSE.OTHER_INSTALL` | It was issued for another install id |
| Licence refused with `LICENSE.DB_REQUIRED` | Move to SQL Server first |
| Single sign-on fails | Check the **Public address**, the redirect address registered with the provider, and the client secret's vault reference |
| A directory user cannot sign in: `LDAP.NO_ROLE` | They are in no group listed in **Groups to roles**. Add the group, or add them to a listed group. **Test the directory** shows their groups |
| Directory sign-in fails with `LDAP.UNREACHABLE` | No domain controller answered over `ldaps://`. Check **Directory addresses**, the network, and that Java trusts the controllers' certificate |
| The identity provider's SCIM sync reports a name already taken (409) | A local account already has that user name; SCIM never takes over local accounts. Rename one of them |
| A new runner cannot register: `CR.REGISTRATION_CLOSED` | Switch on **New runners may register** in Settings > Runner Machines |
| Runners are refused with `CR.HTTPS_REQUIRED` | **Runners connect only over HTTPS** is on: point the runners at the `https://` address ([07 Runner guide](07-runner-guide.md)) |
| Promotion is refused with `CR.TARGET_NOT_ALLOWED` | The target is not in **Promote agents only to**; add it there if it is right |
| An AI step stops with `AI.MODEL_NOT_ALLOWED` | The folder's AI guardrails policy does not allow that model connection. Tick it in the policy, or change the step |
| An upload is refused as too large | Raise **Largest upload (MB)** |
| Moving to a new server | Copy the data (or point at the same database), keep the same vault key, then run `ooa agent move --url ...` on each runner ([07](07-runner-guide.md)) |

## Related guides

- [00 Index](00-README.md)
- [01 Getting started](01-getting-started.md)
- [05 Operator's guide](05-operators-guide.md)
- [07 Runner guide](07-runner-guide.md)
- [15 Publishing to One CommandCenter](15-publishing-to-command-center.md)
