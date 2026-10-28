# 10 Users, roles, teams and security features

## What this covers

Who can use the Command Center and what they may do: user accounts and their life cycle, built-in and custom roles,
the complete permission list, further roles per person, switching off built-in roles, teams, the ways to sign in
(password, single sign-on with OpenID Connect or SAML, two-step sign-in), password and session rules, API keys and
session tokens, address allow-lists and the other security settings.

## Contents

1. User accounts
2. Built-in roles
3. Every permission
4. Custom roles, further roles and switched-off roles
5. Teams
6. Principals in rules
7. Signing in with a password
8. Password rules
9. Two-step sign-in
10. Single sign-on (OpenID Connect and SAML)
11. Sessions, API keys and tokens
12. Other security settings
13. Recovery
14. Permissions and audit

## 1. User accounts

| Property | Rule |
| --- | --- |
| User name | 3-80 characters: letters, digits, `.`, `_`, `@`, `-` |
| Display name, first and last name | Up to 100 characters each |
| Description | Up to 500 characters |
| E-mail | Used for single sign-on linking and licence reminders |
| Main role | One role; further roles optional (section 4) |
| Enabled | Disabled accounts cannot sign in, and their schedules and triggers do not start runs |
| Default runner | Optional ([document 03](03-runs-runners-and-devices.md)) |

Life cycle:

- **New user** gives a one-time temporary password, which must be changed at the first sign-in.
- The first start of a new Command Center creates the user `admin` with a one-time password printed on the console.
- **Inactive accounts**: with `inactive_user_days` (0-3650, 0 = never) accounts that have not signed in for that many
  days are disabled automatically.
- Rules that protect the Command Center: nobody can disable themselves or remove their own administrator role, and at
  least one enabled administrator must remain.
- **Delete** a user is refused while they own schedules, triggers, API tasks, running work queues or vault folders
  where they are the only owner (`CR.IN_USE`, listing them); hand those over or disable the user instead. Deleting
  removes their sign-ins, API keys, default runner, team places, own vault values and every rule that names them; the
  audit trail keeps their name. Nobody can delete themselves.
- The Users page filters by text, role and enabled, with paging.

## 2. Built-in roles

| Role (stored id) | Shown as | Permissions |
| --- | --- | --- |
| `admin` | Administrator | All permissions |
| `bot_creator` | Agent builder | repo.view, repo.checkin, repo.delete, runs.view, runs.start, runs.stop, devices.view, schedules.view, schedules.manage, triggers.view, triggers.manage, globals.view, lockers.own, queues.view, queues.work, queues.manage, analytics.view, apitasks.manage, skills.view, skills.manage |
| `bot_runner` | Agent operator | repo.view, runs.view, runs.start, runs.stop, devices.view, schedules.view, triggers.view, globals.view, queues.view, queues.work, analytics.view, skills.view |
| `viewer` | Viewer | repo.view, runs.view, devices.view, schedules.view, triggers.view, globals.view, queues.view, analytics.view, skills.view |

## 3. Every permission

Permissions marked "admin only" belong to administrators unless a custom role grants them.

| Permission | Allows |
| --- | --- |
| `repo.view` | See the agent repository, versions, toolkits, templates, test sets and results, production requests; analyse agents |
| `repo.checkin` | Check out and check in, label versions, create folders, move agents and folders, upload repository files, ask for production, manage test sets, update agents' toolkit ranges |
| `repo.delete` | Delete and recover agents, delete folders and repository files, see deleted agents |
| `repo.production` (admin only) | Set the live production version, set minutes saved, undo other people's check-outs |
| `repo.approve` (admin only) | Approve or reject production requests |
| `repo.permissions` (admin only) | Set folder permissions; see every folder |
| `lifecycle.export` (admin only) | Export and promote agents; share templates |
| `lifecycle.import` (admin only) | Import agents; install templates |
| `runs.view` | See runs and workflow runs |
| `runs.start` | Start runs and workflows |
| `runs.stop` | Pause, resume and stop runs; cancel other people's workflows |
| `devices.view` | See runners and run-as users |
| `devices.manage` (admin only) | Register, update, enable, disable and remove runners |
| `pools.manage` (admin only) | Manage runner pools |
| `schedules.view` | See schedules and holiday calendars |
| `schedules.manage` | Manage schedules and holiday calendars |
| `triggers.view` | See triggers |
| `triggers.manage` | Manage triggers |
| `queues.view` | See work queues |
| `queues.work` | Add, change, delete and validate work items; start and stop processing |
| `queues.manage` | Create, change and delete queues and work item models; members; import and export |
| `queues.all` (admin only) | Use every queue regardless of its members |
| `globals.view` | See shared values and saved connections; set own values |
| `globals.manage` (admin only) | Change shared values and saved connections |
| `lockers.own` | Create vault folders (and own them) |
| `lockers.manage` (admin only) | Administer every vault folder; vault key status and rotation |
| `analytics.view` | Operations, dashboards, Home figures |
| `apitasks.manage` | Manage API tasks |
| `skills.view` | See and use Prompt Skills |
| `skills.manage` | Create and change own Prompt Skills |
| `skills.all` (admin only) | Change anyone's Prompt Skills |
| `connections.manage` (admin only) | Manage and test One Model Connections |
| `toolkits.manage` (admin only) | Switch toolkits on and off, upload own toolkits, trusted publishers |
| `users.manage` (admin only) | Manage users and roles, default runners, licence seats, act on any task |
| `teams.manage` (admin only) | Create teams and change any team |
| `audit.view` (admin only) | One Audit Trail and AI governance |
| `settings.manage` (admin only) | Settings, licences, backups, retention, Git sync, code analysis policy, template catalogue, nodes |

Every API call checks one permission; the console hides pages and buttons a person cannot use. A refused call answers
`CR.FORBIDDEN` ("You do not have permission to …").

## 4. Custom roles, further roles and switched-off roles

- **Custom roles** (Administration > Roles, `users.manage`): a name and the ticked permissions, for example a "queue
  clerk" that may only see and add work items. A role in use cannot be deleted. Built-in roles cannot be edited.
- **Further roles** (Users > Edit): besides the main role, a person can have further roles. They have every permission
  of each of their roles, and rules naming a role (`role:NAME` in folder permissions, vault folders, work queues,
  workflow tasks, model connections) apply to them for each role. Administrator can only be a main role.
- **Switched-off built-in roles** (Administration > Settings > Security, `switched_off_roles`): Agent builder, Agent
  operator or Viewer can be switched off. A switched-off role gives no permissions and cannot be given to anyone; people
  who still have it keep only their other roles. Administrator cannot be switched off; custom roles are deleted instead.
- Giving someone a role that does not exist or is switched off is refused.

## 5. Teams

- A team (Administration > Teams) is a named group of people, each a **member** or a **lead**.
- `team:NAME` can be used in folder permissions, vault folder members and consumers, and work queue members; adding
  someone to the team gives them all of it.
- People with `teams.manage` create teams and change any team; a team's leads change its members (and stay leads).
  People without `teams.manage` see the teams they are in.
- A team that a rule still names cannot be deleted.

## 6. Principals in rules

| Form | Matches |
| --- | --- |
| `user:NAME` | That person |
| `role:ROLE` | Everyone whose main or further role is ROLE |
| `role:*` | Everyone (workflow assignees and similar rules) |
| `team:NAME` | Every member and lead of the team |

A principal that does not exist is refused when a rule is saved.

## 7. Signing in with a password

- A wrong user name or password answers `CR.LOGIN_FAILED`. Every fifth failure in a row (passwords or two-step codes)
  locks the account for 15 minutes (`CR.LOCKED_OUT`).
- A disabled account is refused (`CR.USER_DISABLED`).
- A person whose password is temporary or has expired must choose a new one before doing anything else.
- After the licence's grace period only administrators can sign in ([document 13](13-licensing-and-editions.md)).

## 8. Password rules

| Rule | Setting | Default |
| --- | --- | --- |
| Minimum length | `password_min_length` (12-64) | 12 |
| Maximum length | fixed | 128 |
| Letters and digits | fixed | both required |
| Not the user name; no character four times in a row; not a common password | fixed | |
| Not one of the last N passwords | `password_history` (0-24) | 0 |
| Expires after N days | `password_max_days` (0-3650, 0 = never) | 0 |
| Different from the current password | fixed | |

Passwords are stored as PBKDF2-SHA256 hashes. Single sign-on users are not affected by these rules.

## 9. Two-step sign-in

- Anyone with a local password can turn on codes from an authenticator app (time-based codes; issuer "ONEOFFICEAUTOMATION")
  in the user menu > Two-step sign-in, and gets ten one-time recovery codes. Turning it off needs the password and a code.
- `mfa_required`: 0 optional, 1 required for administrators, 2 required for everyone who signs in with a password.
  People who must set it up can do nothing else until they have.
- The password step answers with a challenge valid for 5 minutes; the code completes the sign-in (or a code can be sent
  with the password, as Studio's connect form does).
- Single sign-on users are left to their identity provider; API keys are not affected.
- An administrator can **Reset two-step** on the Users page; `ooa reset-admin` also clears it.

## 10. Single sign-on (OpenID Connect and SAML)

| | OpenID Connect | SAML 2.0 |
| --- | --- | --- |
| Settings | Issuer URL, client id, client secret (vault reference), default role, button label | Identity provider sign-in address (`https://`), entity id, signing certificate (PEM), our entity id, button label |
| Provider set-up | Register the redirect address shown | Give the provider the metadata address `/api/auth/saml/metadata` |
| Checks | Signature, issuer, audience, expiry, one-time nonce, PKCE | Signed assertion or response; encrypted assertions are refused; sign-ins must start at the Command Center |

On the first sign-in an existing account with the same verified e-mail address or user name is linked; otherwise a new
account is made with the role for new people (`sso_default_role`). With no default role, only existing accounts may sign
in (`SSO.NO_ACCOUNT`). A provider refusal shows as `SSO.DENIED`.

## 11. Sessions, API keys and tokens

| Item | Rule |
| --- | --- |
| Session length | `session_hours` (1-720, default 8), extended while used; the cookie is HttpOnly, SameSite=Strict, and Secure with HTTPS |
| Sessions per person | `max_sessions_per_user` (1-50, default 5); the oldest end when exceeded |
| API keys | Made under the person's name (top bar > API keys), `ooak_…`, shown once, stored as a hash, optional expiry up to 730 days, revocable; calls act as that person with `Authorization: Bearer ooak_…` |
| Token from a key | `POST /api/auth/token` with `{"api_key": …}` gives a session token (8 hours, extended while used) |
| Refresh | `POST /api/auth/refresh` gives a new token and ends the old one (not for API keys) |
| Check | `GET /api/auth/token` tells whether a token is valid, for whom and until when, without extending it |

## 12. Other security settings

| Setting | Effect |
| --- | --- |
| IP allow-list (`ip_allow_list`) | Single addresses or CIDR ranges allowed to use the API; this computer is always allowed, and a list that would lock out the person saving it is refused |
| Block executable uploads (`block_executable_uploads`) | Refuses executable and script files as repository files |
| Maximum upload (`max_upload_mb`, 1-1024, default 5) | The largest request body |
| Announcement | A banner shown to everyone |
| Approval before production (`production_approval`) | [document 08](08-repository-versions-and-templates.md) |
| Outgoing proxy (`outbound_proxy`, `outbound_proxy_bypass`) | The Command Center's own outgoing connections go through `http://host:port`, except listed hosts and this computer |
| Syslog (`syslog_target`, `syslog_protocol` udp or tcp) | Audit records are forwarded to `host:port` |

## 13. Recovery

`ooa reset-admin` (or `--user NAME`), run on the Command Center's machine with it stopped, gives a one-time password,
re-enables and unlocks the account, clears two-step sign-in, ends its sessions and records the reset in the audit trail.

## 14. Permissions and audit

Users, roles and default runners need `users.manage`; teams `teams.manage` (or being a lead); security settings
`settings.manage`. Audited: sign-ins and failures (`auth.login`, `auth.sso`, `auth.token`), password changes, two-step
changes, user and role changes, team changes, API key creation and revocation, and resets.

## Related documents

- [07 Vault, credentials and connections](07-vault-credentials-and-connections.md)
- [08 Repository, versions and templates](08-repository-versions-and-templates.md)
- [11 Operations, audit and dashboards](11-operations-audit-and-dashboards.md)
- [13 Licensing and editions](13-licensing-and-editions.md)
- [Command Center and runner, section 2](../CONTROL_ROOM_AND_AGENT.md)
- [Security review](../SECURITY_REVIEW.md)
