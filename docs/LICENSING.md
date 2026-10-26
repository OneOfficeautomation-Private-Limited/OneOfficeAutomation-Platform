# Licensing

A ONEOFFICEAUTOMATION license is a signed file, installed per Command Center in Administration > Licenses. It works
fully offline.

There are two builds:

| Build | Made with | Holds | Without a license |
| --- | --- | --- | --- |
| Customer (what customers install) | `gradlew :cli:customerDistZip` → `cli/build/distributions/ooa-customer-<version>.zip` | The product, `START-OOA.cmd`, `README.txt`; `ooa license show` only | A 30-day evaluation |
| Vendor (yours) | `gradlew :cli:installDist` or `start-ooa.bat` | The product plus the `vendor-tools` module (License Studio, `ooa license ui`, `keygen`, `issue`) | Nothing is limited |

The vendor build is recognised by `META-INF/ooa-vendor`, which only the `vendor-tools` jar carries. The customer zip
leaves that jar out, and its start scripts load `lib\*`, so they never name it.

## Licensing a customer, step by step

1. **Agree the terms:** the customer's name, the end date, and the seats (developers, citizen developers, attended and
   unattended runners). Ask for the **install id** of their Command Center: Administration > Licenses shows it
   (`OOA-XXXX-XXXX-XXXX-XXXX`). A license issued for it works only on that Command Center.
2. **Issue the file.** The easy way is **License Studio**: double-click `platform\scripts\License Studio.bat`, or run
   `ooa license ui`. It opens a page on your PC (127.0.0.1 only, with an access token in the address) that has three tabs:
   - **Issue a license:** fill in the customer, the end date (with 1 month to 3 years shortcuts), the grace days, the
     install id and the seats, then click Issue license and download the `.lic` file. "Works on any installation"
     leaves the install id out; keep that for trials.
   - **Issued licenses:** every license you issued, with days left, its state, Download, and Renew. Renew fills the
     form with the same customer and seats and a new end date one year later.
   - **Check a file:** reads any `.lic` file and tells you whether the product accepts it.

   A copy of every license it issues is kept in `%USERPROFILE%\OOA License Keys\issued`. The pill at the top says
   whether the private key matches the key built into the product; issuing is switched off when it doesn't.

   The same from the command line, in your vendor build:
   ```
   ooa license issue --key "%USERPROFILE%\OOA License Keys\private.key" --customer "Acme Ltd" --expires 2027-12-31 --developer 5 --attended 10 --unattended 8 --install-id OOA-ABCD-EFGH-JKLM-NPQR --out "Acme Ltd.lic"
   ```
   `--install-id` is required; `--any-install` issues a file for any installation instead. Optional: `--citizen-developer N`, `--grace-days N` (default 14), `--edition NAME`, `--notes TEXT`, `--id ID`.
3. **Check it:** `ooa license show "Acme Ltd.lic"` prints the terms and checks the signature.
4. **Send the `.lic` file** to the customer, with the customer zip if they don't have it yet. The file holds no secret;
   any change to it breaks the signature.
5. **The customer installs it:** an administrator opens Administration > Licenses, chooses the file (or pastes it) and
   clicks Install license. Then they give seats to people and runners on the same page.
6. **Renewal or more seats:** issue a new file with the new terms and the same customer name. Installing it replaces the
   old one; seats already given stay assigned. A file with fewer seats than are in use is refused until seats are
   taken back.

Keep a list of what you issued: the `.lic` files themselves are enough, since each holds its own terms.

## Evaluation (customer build without a license)

- **Length:** a fresh customer install runs for 30 days from its first start.
- **Seats:** 2 developers, 2 attended runners and 2 unattended runners, given out automatically the first time someone
  checks in or a runner takes work.
- **Banner:** administrators see a countdown, red in the last 7 days.
- **After 30 days:** like an expired license, runs stop and only administrators can sign in, to install a license.
- **Protection:** the start date is stored encrypted with the vault key, so editing it ends the evaluation.
- **Licensing:** installing a license ends the evaluation. From then on, seats are assigned by hand.

## Licensed customer installs run on SQL Server

The evaluation may use the built-in database, but a customer install cannot install a license until it runs on
Microsoft SQL Server (PostgreSQL is accepted too). Until then:

- the Licenses page shows the steps in place of the install form;
- the API refuses the license with `LICENSE.DB_REQUIRED`.

The steps are `ooa db configure`, `set-password` (runtime and `--setup`), `init`, and `copy --from` the evaluation's
data folder, which keeps its data. Then restart and install the license.

If a license ever ends up on the built-in database by going around this check, the state becomes
`database_required`. It works like an expired license (runs stop, only administrators sign in) until the database is
moved. Your vendor build is never held to this.

## License file

The file is JSON: `{"format":"ooa-license-1","payload":"<Base64 JSON>","signature":"<Base64>"}`. The payload holds:

| Field | Meaning |
| --- | --- |
| `id` | License id, for example `LIC-20261026-9D50EA86` |
| `customer`, `edition` | Who it is for; the edition (default Enterprise) |
| `issued`, `expires` | Dates (expires is the last day the license is fully valid) |
| `grace_days` | Days after expiry during which everything keeps working (default 14, at most 90) |
| `seats` | Counts for `developer`, `citizen_developer`, `attended_runner`, `unattended_runner` |
| `install_id` | The Command Center it is for (missing: any installation). Elsewhere it is refused with `LICENSE.OTHER_INSTALL`, and one that reaches another installation's database counts as expired |

The payload is signed with the vendor's Ed25519 private key. The matching public key is built into the product as
`License.VENDOR_PUBLIC_KEY`. Any change to the file makes the signature fail, and the install is refused with
`LICENSE.INVALID`.

## Seats

| Seat | Given to | Allows |
| --- | --- | --- |
| Developer | A person | Checking agents in (Studio, API) |
| Citizen Developer | A person | Checking agents in |
| Attended Runner | A person | Running agents by hand on their own default runner |
| Unattended Runner | A runner | Taking any Command Center work: manual, scheduled, triggered, API, work queues |

A person with a Developer or Citizen Developer seat may also run agents by hand on their own default runner. Seats are
assigned on the Licenses page, which needs `users.manage`. Assigning more seats than the file holds is refused with
`CR.LICENSE_LIMIT`. A license with fewer seats than are already assigned cannot be installed until seats are taken back.

**Usage** (Licenses page, the Usage card): seats in use per type each day over the last 90 days, and every seat holder
with when the seat was given, when they last signed in (a runner: last online) and how many runs they started in the
last 30 days. A seat with neither sign-in nor runs for 30 days is marked idle, as a candidate to take back. **CSV**
downloads the holders list. Deleting a runner frees its seat.

## Life of a license

| State | When | Effect |
| --- | --- | --- |
| Unlicensed | No license file, vendor build | Nothing is limited |
| Evaluation | No license file, customer build, first 30 days | Evaluation seats, banner with the days left |
| Evaluation ended | After the 30 days | As Expired |
| Database required | A license on the built-in database, customer build | As Expired, with the SQL Server steps in the banner |
| Active | More than 30 days left | Seats apply |
| Expiring | 30 days or fewer left | Banner for administrators: a warning that can be put away, red and permanent in the last 7 days |
| Grace | After the expiry date, for the grace days | Everything keeps working; the red banner stays |
| Expired | After the grace days | Runs stop, queued runs fail, and only administrators can sign in to install a new license |

**Reminder e-mails**: once e-mail is set up (Administration > Settings > E-mail), administrators with an e-mail address,
and any addresses in "Also send licence reminders to", get one e-mail when 30, 15, 7, 3, 2 and 1 days are left, and one
when the license has expired (naming the end of the grace period). The evaluation gets the same reminders. Each is
audited as `license.mail`.

Every install, assignment and unassignment is audited (`license.install`, `license.assign`, `license.unassign`). The
same applies to failed sign-ins and refused runs.

## The signing key (vendor only)

The vendor's signing key was created with `ooa license keygen --out "%USERPROFILE%\OOA License Keys"`. It is not in
the repository.

- Keep `private.key` secret and back it up offline: whoever holds it can issue licenses.
- Never make a new key: every license issued with the old one would stop working. `keygen` refuses to overwrite one.

## API

| Call | Who | What |
| --- | --- | --- |
| `GET /api/license` | Everyone signed in (details for administrators) | State, banner, terms, seats in use |
| `POST /api/license` `{content}` | `settings.manage` | Install a license file |
| `GET /api/license/assignments` | `users.manage` | Who holds which seat |
| `PUT /api/license/assignments` `{license_type, principal, assigned}` | `users.manage` | Give or take back a seat (principal: user name, or runner id for Unattended Runner) |
| `GET /api/license/usage?days=90` | `users.manage` or `settings.manage` | Seats, holders with last activity and idle flag, daily trend |
| `GET /api/license/usage/export` | `users.manage` or `settings.manage` | The holders list as CSV |
