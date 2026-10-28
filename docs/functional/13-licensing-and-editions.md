# 13 Licensing and editions

## What this covers

How ONEOFFICEAUTOMATION is licensed and what the licence controls: the two builds, the 30-day evaluation, signed
licence files and their contents, the licence states and their effects, seat types and what each allows, how runs and
check-ins are checked against seats, seat assignment and usage reports, reminder e-mails, and subscription seats in the
SaaS service. The vendor's own procedure for issuing licences is in [../LICENSING.md](../LICENSING.md).

## Contents

1. Builds and editions
2. The evaluation
3. Licence files
4. Licence states
5. Seat types
6. What is enforced
7. Assigning seats and usage
8. Reminders
9. SaaS subscription seats
10. Permissions, API and audit

## 1. Builds and editions

| Build | Without a licence | With a licence |
| --- | --- | --- |
| Customer build (what customers install) | A 30-day evaluation | The licence's seats apply |
| Vendor build (with the licence tools) | Unlicensed: nothing is limited | The licence's seats apply |

The **edition** is a name carried by the licence (default "Enterprise"). Administration > Settings > This Command
Center shows "EDITION (licensed to CUSTOMER)", "Evaluation (30 days, until DATE)" or "Unlicensed".

## 2. The evaluation

| Rule | Detail |
| --- | --- |
| Length | 30 days from the first start of the Command Center |
| Seats | 2 Developer, 0 Citizen Developer, 2 Attended Runner, 2 Unattended Runner |
| Assignment | Free seats are given automatically the first time someone checks in or a runner takes work |
| Banner | Administrators see a countdown, red in the last 7 days |
| End | Works like an expired licence: runs stop and only administrators can sign in |
| Protection | The start date is stored encrypted with the vault key; an edited value ends the evaluation |
| Database | The evaluation may use the built-in database |

Installing a licence ends the evaluation; from then on seats are assigned by hand.

## 3. Licence files

A licence is a signed file installed per Command Center in Administration > Licenses (paste or choose the file, then
**Install license**). It works fully offline.

| Field | Meaning |
| --- | --- |
| `id` | Licence id |
| `customer`, `edition` | Who it is for; the edition |
| `issued`, `expires` | Dates; `expires` is the last fully valid day |
| `grace_days` | Days after expiry during which everything keeps working (default 14, at most 90) |
| `seats` | Counts of `developer`, `citizen_developer`, `attended_runner`, `unattended_runner` |
| `install_id` | The Command Center it is for (`OOA-XXXX-XXXX-XXXX-XXXX`, shown on the Licenses page); missing means any installation |

Installation is refused when:

| Code | Cause |
| --- | --- |
| `LICENSE.INVALID` | The file is empty, too large (over 20,000 characters), or its signature does not verify (any change breaks it) |
| `LICENSE.EXPIRED_FILE` | Its grace period has already passed |
| `LICENSE.OTHER_INSTALL` | It was issued for another install id |
| `LICENSE.DB_REQUIRED` | A customer install still runs on the built-in database; it must move to SQL Server (PostgreSQL is accepted too) first. The Licenses page shows the steps instead of the install form. |
| `LICENSE.TOO_FEW_SEATS` | It has fewer seats of a type than are assigned |

A new licence replaces the one in force; seats already given stay assigned. The install id is stored encrypted with
the vault key, so copying the database elsewhere does not move a licence.

## 4. Licence states

| State | When | Effect |
| --- | --- | --- |
| `unlicensed` | Vendor build, no licence | Nothing is limited |
| `evaluation` | Customer build, no licence, first 30 days | Evaluation seats; countdown banner |
| `evaluation_ended` | After the 30 days | As expired |
| `database_required` | A licence on the built-in database (customer build) | As expired, with the database steps in the banner |
| `active` | More than 30 days left | Seats apply |
| `expiring` | 30 days or fewer left | Seats apply; a banner for administrators that can be put away, red and permanent in the last 7 days |
| `grace` | After the expiry date, within the grace days | Everything keeps working; the red banner stays |
| `expired` | After the grace days, or a licence that no longer verifies or belongs to another installation | Runs stop, queued runs fail when claimed, only administrators can sign in to install a new licence |
| `subscription` | A SaaS tenant | The operator's seats apply (section 9) |

## 5. Seat types

| Seat | Given to | Allows |
| --- | --- | --- |
| Developer | A person | Checking agents in (Studio, API); running agents by hand on their own default runner |
| Citizen Developer | A person | The same as Developer |
| Attended Runner | A person | Running agents by hand on their own default runner |
| Unattended Runner | A runner | Taking any Command Center work: manual, scheduled, triggered, API, work queues, workflows, tests |

## 6. What is enforced

While a licence is in force (active, expiring, grace, evaluation, subscription):

- **Check-in** needs a Developer or Citizen Developer seat (`CR.LICENSE_LIMIT`).
- **A run** may go to a runner when the runner has an Unattended Runner seat (given automatically during the
  evaluation and in the SaaS service while seats are free). Otherwise it is allowed only when it was started **by hand**
  (source manual or Studio) by a person who holds an Attended Runner, Developer or Citizen Developer seat **on their own
  default runner**. Refusals: `CR.LICENSE_REQUIRED` naming the reason.
- The check is made when a run is started and again when the runner claims it, so a licence that expired, or a seat
  taken back in the meantime, fails the queued run.
- The serverless virtual runner needs an Unattended Runner seat like any runner.
- After expiry: runs stop (`CR.LICENSE_EXPIRED`) and only administrators (`settings.manage`) can sign in.

## 7. Assigning seats and usage

- On Administration > Licenses, seats are given to user names (person seats) or runner ids (Unattended Runner) and taken
  back. Assigning beyond the file's count is refused (`CR.LICENSE_LIMIT`, "All N … seats are in use").
- Deleting a runner, or the automatic removal of a temporary runner, frees its seat.
- **Usage**: seats in use per type each day over the last 90 days (recorded once a day), and every seat holder with
  when the seat was given, last sign-in (a runner: last online) and runs started in the last 30 days. A seat with
  neither sign-in nor runs for 30 days is marked **idle**, a candidate to take back. **CSV** downloads the holders list;
  cells never start like a spreadsheet formula.

## 8. Reminders

With e-mail set up ([document 11](11-operations-audit-and-dashboards.md)), administrators with an e-mail address and the
addresses in `license_mail_to` get one e-mail when 30, 15, 7, 3, 2 and 1 days are left, and one when the licence has
expired (naming the end of the grace period). The evaluation gets the same reminders. Each is audited as `license.mail`.

## 9. SaaS subscription seats

- SaaS tenants do not install licence files (installation is refused). Their state is `subscription`.
- The service operator sets the tenant's seats when creating it and can change them; no seats given means no limit.
- Free seats go to people and runners on first use; when all are in use, the tenant's administrator takes one back in
  Administration > Licenses or asks for more ("All N … seats of the subscription are in use").
- On-premises installs keep signed licence files.

## 10. Permissions, API and audit

| Action | Permission |
| --- | --- |
| See the state and banner | Everyone signed in (terms and seats for administrators) |
| Install a licence | `settings.manage` |
| See the Licenses page and the seat holders | `users.manage` or `settings.manage` |
| Assign and take back seats | `users.manage` |
| Usage report and CSV | `users.manage` or `settings.manage` |

API: `GET /api/license`, `POST /api/license` `{content}`, `GET`/`PUT /api/license/assignments`,
`GET /api/license/usage?days=90`, `GET /api/license/usage/export`.

Audited: `license.install`, `license.assign`, `license.unassign` (including automatic evaluation and subscription
assignments), `license.mail`, failed sign-ins and refused runs.

## Related documents

- [03 Runs, runners and devices](03-runs-runners-and-devices.md)
- [08 Repository, versions and templates](08-repository-versions-and-templates.md)
- [Licensing (vendor procedure and file format)](../LICENSING.md)
- [SaaS service](../SAAS.md)
- [Database](../DATABASE.md)
