# Licensing

A ONEOFFICEAUTOMATION license is a signed file, installed per Command Center in Administration > Licenses. It works
fully offline. Without a license file, nothing is limited and the Licenses page says so.

## License file

The file is JSON: `{"format":"ooa-license-1","payload":"<Base64 JSON>","signature":"<Base64>"}`. The payload holds:

| Field | Meaning |
| --- | --- |
| `id` | License id, for example `LIC-20261026-9D50EA86` |
| `customer`, `edition` | Who it is for; the edition (default Enterprise) |
| `issued`, `expires` | Dates (expires is the last day the license is fully valid) |
| `grace_days` | Days after expiry during which everything keeps working (default 14, at most 90) |
| `seats` | Counts for `developer`, `citizen_developer`, `attended_runner`, `unattended_runner` |

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

## Life of a license

| State | When | Effect |
| --- | --- | --- |
| Unlicensed | No license file | Nothing is limited |
| Active | More than 30 days left | Seats apply |
| Expiring | 30 days or fewer left | Banner for administrators: a warning that can be put away, red and permanent in the last 7 days |
| Grace | After the expiry date, for the grace days | Everything keeps working; the red banner stays |
| Expired | After the grace days | Runs stop, queued runs fail, and only administrators can sign in to install a new license |

Every install, assignment and unassignment is audited (`license.install`, `license.assign`, `license.unassign`). The
same applies to failed sign-ins and refused runs.

## Issuing licenses (vendor only)

The vendor's signing key was created with `ooa license keygen --out "%USERPROFILE%\OOA License Keys"`. It is not in
the repository. Keep `private.key` secret and back it up offline: whoever holds it can issue licenses.

```
ooa license issue --key "%USERPROFILE%\OOA License Keys\private.key" --customer "Acme Ltd" --expires 2027-12-31 --developer 5 --citizen-developer 10 --attended 20 --unattended 8 --out acme.lic
ooa license show acme.lic
```

`show` prints the terms and checks the signature against the key built into this release.

## API

| Call | Who | What |
| --- | --- | --- |
| `GET /api/license` | Everyone signed in (details for administrators) | State, banner, terms, seats in use |
| `POST /api/license` `{content}` | `settings.manage` | Install a license file |
| `GET /api/license/assignments` | `users.manage` | Who holds which seat |
| `PUT /api/license/assignments` `{license_type, principal, assigned}` | `users.manage` | Give or take back a seat (principal: user name, or runner id for Unattended Runner) |
