# Mail accounts (Manage > Connections > Mail accounts)

A mailbox is set up **once** and chosen by name everywhere: by Document Automation's processes, by triggers, and by
agents. Nobody types a server, a password or a sign-in into an automation. Built 15–16 Nov 2026 (MA-1 to MA-5) from the
design plan's Mail accounts rows.

## 1. Add a mail account

Manage > Connections > **Mail accounts** > **New mail account** (needs the Manage shared global values permission).

1. **Type the address.** The vendor is found: by the address's domain (gmail.com, yahoo.com, outlook.com…), or for a
   company domain by where its mail goes (its MX record): Microsoft 365, Google Workspace, Zoho (with its region).
   You can always choose another vendor.
2. **Sign in.**
   - Microsoft 365, Outlook.com and Hotmail: **Sign in with Microsoft** (a code to type at microsoft.com/devicelogin;
     work, school and personal accounts). A shared mailbox can be named. Tick **Allow mail rules to mark emails read
     and move them** to let rules change the mailbox (Microsoft then asks for that permission).
   - Your own Microsoft app: tenant id, application (client) id and the client secret from the vault.
   - Gmail, Zoho, Yahoo, AOL, iCloud, Fastmail: an **app password** saved in the vault; the dialog links to the
     vendor's page for making one. GMX, Rediffmail, Exchange and other servers: the password from the vault.
   - The server, port and SSL come from the catalogue; **Show advanced settings** changes them.
3. **Who may use it**: people, teams or roles. They can choose the account; only its owner and administrators change it.

The password never reaches the account: it keeps the vault reference, and reads the secret **as its owner**, who
lends the account. Each read is audited as `mail-account:ID for USER`.

**Vendors** come from a catalogue file (`control-room/src/main/resources/com/oneofficeautomation/controlroom/mail/providers.json`):
a new vendor is a new entry, not a new release.

## 2. Health

Each account shows **Working**, **Sign-in expired**, **Wrong password** or **Not reached**, its last check and what
uses it. **Test now** reaches the Inbox. Signing in again (or changing the password) fixes every process, trigger and
agent that uses the account.

## 3. Mail rules: one reader per mailbox

Each account is read **once** every interval (1 minute or more) for all its rules, per folder the rules name; the first
look only marks where the mailbox is. Each new email is checked against the account's rules **in order**:

- **The first matching rule takes it**, or **every matching rule** does (one email can then start a process and a trigger).
- A rule says which emails (all / any of its conditions, and **Except when…**): from (an address, a domain such as
  `@supplier.com`), to or cc (an alias such as `invoices+ap@`), subject and text (words, or a pattern), attachment
  type (pdf, image, xml, office, zip) and name (`INV-*.pdf`), has attachments, received after a date.
- **After reading** (optional): leave it, mark it read, or move it to a folder (made when missing). Microsoft accounts
  need the sign-in that allows changing the mailbox.
- An email is never handled twice, even when a forwarded copy arrives in another account that sends it to the same place.
- Emails no rule takes wait in **Not matched** (the newest 500): send one on to a rule's target, or ignore it.
- **Try the rule** checks conditions on the latest 200 emails, reading nothing in, and says how many an earlier rule takes first.

The account's **Mail rules** page lists its rules in order (move up and down), how they apply, and Not matched.

## 4. Where accounts are used

| Where | How |
|---|---|
| Document Automation, a process's step 2 | **Mail accounts** > Add a mail account: the account, a rule name, its conditions (PDF, image and XML attachments by default), after reading. Those attachments become documents in the process, added as the rule's owner (who needs run access to the process's folder). The process's own email inbox still works as before. |
| Manage > Triggers | Event **New email (any mail account)**: the account, a folder, the rule and after reading. Its agent starts with `trigger_email`. Deleting the trigger removes its rule; Check now reads the account. |
| Agents (Studio) | The Email toolkit's steps choose a mail account as their **connection** (its name). Accounts read over IMAP only; the password is fetched for the run when its user may use the account. Microsoft accounts sign in for Microsoft Graph, so the Email toolkit's IMAP steps cannot use them. |

## 5. For administrators

- Database: schema 48 (`mail_accounts`, `mail_rules`, `mail_seen`, `mail_unmatched`; `ms_signins.audience` and `scopes`).
- API: `/api/mail-accounts` (list with the catalogue, `detect?address=`, make, change, delete, `test`, `ms-signin`,
  `{id}/rules`, `rules/order`, `rules/try`, `read`, `unmatched`), `/api/mail-rules` (`?target_kind=&target=`, change,
  delete), `/api/mail-unmatched/{id}/send` and `/ignore`.
- Modules add places rules can send emails through `ModuleContext.mailTarget` (Document Automation: `process`).
- The Microsoft app for Sign in with Microsoft: Administration > Settings > E-mail, proxy and SIEM > **Sign in with
  Microsoft**. When registering OOA's app in Microsoft Entra, choose **Accounts in any organizational directory and
  personal Microsoft accounts**, so Outlook.com and Hotmail work too.

## 6. Not yet

- Reading the email itself into a process (only its attachments today).
- Sign in with Google, Zoho or Yahoo in the browser (app passwords today); it needs the server's web address registered with each.
- Microsoft accounts in agents (through the Microsoft 365 toolkit).
