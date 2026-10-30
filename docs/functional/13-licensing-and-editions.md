# 13 Licensing and editions

## What this covers

How ONEOFFICEAUTOMATION is licensed and what the licence controls: the two builds, the 60-day evaluation, signed
licence files and their contents, the licence states and their effects, seat types and what each allows, how runs and
check-ins are checked against seats, seat assignment and usage reports, reminder e-mails, and subscription seats in the
SaaS service. Section 11 is the vendor's own procedure for issuing licences and looking after the signing key.

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
11. For the vendor: issuing licences

## 1. Builds and editions

| Build | Without a licence | With a licence |
| --- | --- | --- |
| Customer build (what customers install) | A 60-day evaluation | The licence's seats apply |
| Vendor build (with the licence tools) | Unlicensed: nothing is limited | The licence's seats apply |

The customer build can read and check licence files (`ooa license show`) but never make them: the module with the
licence tools (`vendor-tools`) is left out of every customer package. The product recognises the vendor build by a
marker that only that module carries, so the difference cannot be switched on by a setting.

The **edition** is a name carried by the licence (default "Enterprise"). Administration > Settings > This Command
Center shows "EDITION (licensed to CUSTOMER)", "Evaluation (60 days, until DATE)" or "Unlicensed".

## 2. The evaluation

| Rule | Detail |
| --- | --- |
| Length | 60 days from the first start of the Command Center |
| Seats | 2 Developer, 0 Citizen Developer, 2 Attended Runner, 2 Unattended Runner |
| Assignment | Free seats are given automatically the first time someone checks in or a runner takes work |
| Banner | Administrators see a countdown, red in the last 7 days |
| End | Works like an expired licence: runs stop and only administrators can sign in |
| Protection | The start date is stored encrypted with the vault key; an edited value ends the evaluation |
| Database | The evaluation may use the built-in database |

Installing a licence ends the evaluation; from then on seats are assigned by hand.

## 3. Licence files

A licence is a signed file (`.lic`) installed per Command Center in Administration > Licenses (paste or choose the
file, then **Install license**). It works fully offline and holds no secret.

To install a licence:

1. Find the **install id** on Administration > Licenses (`OOA-XXXX-XXXX-XXXX-XXXX`) and send it to
   ONEOFFICEAUTOMATION with the agreed terms. A licence issued for it works only on that Command Center.
2. On a customer build, move to SQL Server or PostgreSQL first (see `LICENSE.DB_REQUIRED` below).
3. When the `.lic` file arrives, check it if you like: `ooa license show FILE` prints its terms and says whether this
   release accepts its signature.
4. An administrator with `settings.manage` opens Administration > Licenses, chooses or pastes the file and clicks
   **Install license**.
5. Give seats to people and runners on the same page (section 7).

For a renewal or more seats, the vendor issues a new file with the new terms; install it the same way.

The file is JSON: `{"format":"ooa-license-1","payload":"<Base64 JSON>","signature":"<Base64>"}`. The payload is
signed with the vendor's Ed25519 private key and checked against the public key built into the product. The payload
holds:

| Field | Meaning |
| --- | --- |
| `id` | Licence id, for example `LIC-20261026-9D50EA86` (the issue date and a random part) |
| `customer`, `edition` | Who it is for; the edition |
| `issued`, `expires` | Dates; `expires` is the last fully valid day |
| `grace_days` | Days after expiry during which everything keeps working (default 14, at most 90) |
| `seats` | Counts of `developer`, `citizen_developer`, `attended_runner`, `unattended_runner` |
| `install_id` | The Command Center it is for (`OOA-XXXX-XXXX-XXXX-XXXX`, shown on the Licenses page); missing means any installation |
| `notes` | Optional free text from the vendor |

Installation is refused when:

| Code | Cause |
| --- | --- |
| `LICENSE.INVALID` | The file is empty, too large (over 20,000 characters), or its signature does not verify (any change breaks it) |
| `LICENSE.EXPIRED_FILE` | Its grace period has already passed |
| `LICENSE.OTHER_INSTALL` | It was issued for another install id |
| `LICENSE.DB_REQUIRED` | A customer install still runs on the built-in database; it must move to SQL Server (PostgreSQL is accepted too) first. The Licenses page shows the steps instead of the install form. |
| `LICENSE.TOO_FEW_SEATS` | It has fewer seats of a type than are assigned |

A new licence replaces the one in force; seats already given stay assigned. The install id is stored encrypted with
the vault key, so copying the database elsewhere does not move a licence. A Command Center moved or restored with
the same vault key keeps its install id, so its licence keeps working
([infrastructure/07](../infrastructure/07-backup-restore-and-disaster-recovery.md)); only a new install set up
from scratch needs a new licence.

Moving off the built-in database (for `LICENSE.DB_REQUIRED`), with the Command Center stopped: `ooa db configure`,
`ooa db set-password`, `ooa db set-password --setup`, `ooa db init`, then `ooa db copy --from` the evaluation's data
folder, which keeps its data. Restart and install the licence. The full steps are in
[infrastructure/05](../infrastructure/05-databases.md), section 10. The vendor build is never held to this rule.

## 4. Licence states

| State | When | Effect |
| --- | --- | --- |
| `unlicensed` | Vendor build, no licence | Nothing is limited |
| `evaluation` | Customer build, no licence, first 60 days | Evaluation seats; countdown banner |
| `evaluation_ended` | After the 60 days | As expired |
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

## 11. For the vendor: issuing licences

This section is for ONEOFFICEAUTOMATION only. Customers never have these tools.

### The two builds

| Build | Made with | Holds |
| --- | --- | --- |
| Customer | `gradlew :cli:customerDistZip` (`cli\build\distributions\ooa-customer-<version>.zip`); the setup package and MSI are built from it | The product, `START-OOA.cmd`, `README.txt`, `sbom.cdx.json`; of the licence commands only `ooa license show` |
| Vendor | `gradlew :cli:installDist` (`cli\build\install\ooa`), or `scripts\start-ooa.bat` from a source checkout | The product plus `vendor-tools`: License Studio (`ooa license ui`), `ooa license keygen`, `ooa license issue`, `ooa release sign`, `ooa release templates` |

The customer packages leave the `vendor-tools` jar out, and their start scripts never name it. The release script
refuses to publish a package that holds it, a `private.key` or a `.lic` file
([infrastructure/10](../infrastructure/10-upgrades-releases-and-patching.md), section 14).

### The signing key

| Rule | Detail |
| --- | --- |
| Where | `%USERPROFILE%\OOA License Keys\private.key` (and `public.key`) on the vendor's computer, never in a repository |
| Made with | `ooa license keygen --out DIR`, once. Its public half is built into the product. |
| Never make a new one | Every licence and release manifest signed with the old key would stop verifying. `keygen` refuses to overwrite an existing `private.key`. |
| Keep it secret | Whoever holds it can issue licences and sign releases. Readable only by you; back it up offline. |
| Also signs | The release manifests and the template catalogue |

### Licensing a customer

1. **Agree the terms**: the customer's name, the end date, the grace days (default 14, at most 90) and the seats
   (Developer, Citizen Developer, Attended Runner, Unattended Runner).
2. **Get the install id** from the customer (Administration > Licenses shows it, `OOA-XXXX-XXXX-XXXX-XXXX`). A licence
   for "any installation" is only for trials or a customer who cannot send one.
3. **Issue the file**, with License Studio or the command line (below).
4. **Check it**: `ooa license show FILE` prints the terms and checks the signature, or use License Studio's
   **Check a file** tab.
5. **Send the `.lic` file** to the customer, with the download address of the releases page if they do not have the
   product yet. The file holds no secret; any change to it breaks the signature.
6. **Renewal or more seats**: issue a new file with the new terms and the same customer name. Installing it replaces
   the old one; seats already given stay assigned. A file with fewer seats than are in use is refused until the
   customer takes seats back.

Keep the issued files: each holds its own terms, so the files are the record of what was issued. License Studio keeps
a copy of each in `issued` next to the key.

### License Studio

Double-click `platform\scripts\License Studio.bat` (vendor build installed with `gradlew :cli:installDist`), or run
`ooa license ui [--key FILE] [--port 8790] [--no-browser]`. It opens a page on this computer only (127.0.0.1, port 8790
or a free one if that is taken) with an access token in the address; other web sites and pages without the token
cannot use it. Close the window or press Ctrl+C to stop it.

| Tab | What it does |
| --- | --- |
| Issue a license | Customer, edition, end date (shortcuts 1 month, 3 months, 1 year, 2 years, 3 years), grace days, install id or **Works on any installation**, seats and notes. **Issue license** signs the file and offers it for download. |
| Issued licenses | Every licence in the `issued` folder, newest first, with days left, state (active, expiring, grace, expired, invalid), **Download** and **Renew**. Renew fills the form with the same customer, edition, grace days, seats and install id, an end date one year after the old one (or after today when it has passed) and the note "Renewal of LIC-...". |
| Check a file | Reads any `.lic` file and says whether the product accepts it |

The pill at the top says whether `private.key` matches the key built into the product; issuing is refused when it
does not.

### From the command line

In the vendor build:

```
ooa license issue --key "%USERPROFILE%\OOA License Keys\private.key" --customer "Acme Ltd" --expires 2027-12-31 --developer 5 --attended 10 --unattended 8 --install-id OOA-ABCD-EFGH-JKLM-NPQR --out "Acme Ltd.lic"
```

| Option | Default | Meaning |
| --- | --- | --- |
| `--key FILE` | Required | The vendor private key |
| `--customer NAME` | Required | Who the licence is for |
| `--expires YYYY-MM-DD` | Required | Last fully valid day; a date in the past is refused |
| `--out FILE` | Required | The `.lic` file to write |
| `--install-id ID` or `--any-install` | One is required | The customer's Command Center, or any installation |
| `--developer N`, `--citizen-developer N`, `--attended N`, `--unattended N` | 0 | Seats |
| `--grace-days N` | 14 | Grace days (the product caps them at 90) |
| `--edition NAME` | Enterprise | Edition name |
| `--notes TEXT` | - | Free text in the file |
| `--id ID` | `LIC-<date>-<random>` | Licence id |

Unlike License Studio, `license issue` does not compare the key with the one built into the product, so check each
new file with `ooa license show`.

## Related documents

- [03 Runs, runners and devices](03-runs-runners-and-devices.md)
- [08 Repository, versions and templates](08-repository-versions-and-templates.md)
- [Infrastructure 05. Databases](../infrastructure/05-databases.md)
- [Infrastructure 09. SaaS and multi-region operations](../infrastructure/09-saas-and-multi-region-operations.md)
- [Infrastructure 10. Upgrades, releases and patching](../infrastructure/10-upgrades-releases-and-patching.md)
