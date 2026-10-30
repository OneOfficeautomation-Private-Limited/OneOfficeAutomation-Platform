# 10. Upgrades, releases and patching

## What this covers / audience

How releases are published and how often, how to verify a download with its signed manifest and software bill of
materials, the order in which to upgrade the Command Center, the database and the runners, how runners update
themselves, sandbox previews, and operating system and Java patching. For release managers, change managers and
administrators.

## Contents

1. Release cadence
2. Where releases are published
3. What a release contains
4. Verifying a download: `ooa release verify`
5. The software bill of materials
6. Sandbox previews
7. Upgrade order
8. Upgrading the Command Center
9. Runner self-update
10. Updating runners by package
11. Version compatibility
12. Rolling back
13. Patching the operating system and Java
14. For the vendor: making a release

## 1. Release cadence

| Release | When | Content |
| --- | --- | --- |
| Feature release `x.y.0` | Each quarter | New functions |
| Fix release `x.y.z` | When needed | Only the fixes |
| Sandbox preview `v<version>-preview` | Three weeks before a feature release | The same version, for testing on a sandbox Command Center |

Upgrades need a short downtime of the Command Center (no rolling upgrades).

## 2. Where releases are published

> **Since 2026-10-30** the customer repository is empty: the releases page and template catalogue are offline until the
> next release is published (releases v0.1.0 and v0.1.1 stay under the repository's Releases). The steps below publish
> them again.

Releases are on the public releases page `https://oneofficeautomation-private-limited.github.io/OneOfficeAutomation-Customer-Build/`, which lists every
release newest first (previews marked) with each file's download link, size and SHA-256, and `releases.json` for
scripts. Checksums appear only for releases whose signed manifest checks out; drafts are not listed, and only
`https` links are made. Licence files are never published; each customer receives its own `.lic` file directly from
the vendor, and a new install without one runs the 60-day evaluation.

The files themselves are GitHub releases of the public repository `OneOfficeautomation-Private-Limited/OneOfficeAutomation-Customer-Build`, tagged
`v<version>` (a sandbox preview: `v<version>-preview`, marked as a pre-release). That repository also holds the
customer-facing `README.md`, `INSTALL.md` and `RELEASES.md` (what changed in each release). Source code, tests and the
vendor's licence tools stay in the development repository and are never published.

## 3. What a release contains

| File | Purpose |
| --- | --- |
| `ooa-setup-<version>.zip` | Setup package with Java and installer |
| `ooa-customer-<version>.zip` | Product without Java (Linux package and plain zip) |
| `ooa-<version>.manifest.json` | Signed manifest: product, version, creation time, and each file's name, size and SHA-256 |
| `ooa-<version>-sbom.cdx.json` | CycloneDX 1.5 software bill of materials |
| `templates.json` | Signed template catalogue (published beside the releases page, not attached to the release) |

The manifest is signed with the vendor's Ed25519 key; the matching public key is built into the product. It covers
the two zips and the SBOM.

The MSI `ooa-<version>.msi` is built separately from the setup package (section 14) and is not attached by the
publish script, so it is not in the signed manifest and `ooa release verify` does not check it. Where a verified
download matters, install from the setup package.

## 4. Verifying a download: `ooa release verify`

With any installed release:

```
ooa release verify ooa-0.2.0.manifest.json [--dir C:\downloads]
```

| Output line | Meaning |
| --- | --- |
| `Signed by ONEOFFICEAUTOMATION: release V of DATE` | The signature is valid |
| `PASS     file` | Size and SHA-256 match |
| `FAIL     file (changed: do not install it)` | The file differs from what was signed |
| `MISSING  file` | Not in the folder (fine if you did not download it) |

An edited manifest is refused as a whole (`RELEASE.BAD_SIGNATURE`). Exit code: 0 when no file failed; 2 when a file
failed, the signature is bad or the manifest is missing. Without an earlier install, compare SHA-256 values from the
releases page: `certutil -hashfile FILE SHA256` (Windows) or `sha256sum FILE` (Linux).

## 5. The software bill of materials

Every package carries `sbom.cdx.json`: every third-party library with its version and SHA-256. Feed it to your
vulnerability scanner (Dependency-Track, Grype and similar). The bundled Java runtime is a jlink image of Java 21;
check its version with `runtime\bin\java -version`.

## 6. Sandbox previews

1. Install the preview on a **sandbox** Command Center: a separate install with its own database and runners, never
   production.
2. Export your agents from production (Export / promote, with the agents they call) and import them into the
   sandbox.
3. Run your agent tests (Build > Agent tests) for a quick pass or fail, and your own checks.
4. Report problems. Three weeks later the same version is published as final (or a fix version if something
   changed).

## 7. Upgrade order

| Step | Why |
| --- | --- |
| 1. Back up (`ooa backup now` and a database backup) | Rollback point |
| 2. Stop every Command Center node | No mixed versions on one database |
| 3. Install the new version over the old one | The data folder is kept |
| 4. Upgrade the schema (`ooa db migrate`, or at start when the account may change tables) | Data-only runtime accounts cannot |
| 5. Start one node, check, then start the rest | |
| 6. Update the runners | A runner newer than the Command Center gets no work, so the Command Center always goes first |
| 7. Update Studio computers and browser connector installs | Same package |

Queued runs wait during the downtime and then continue. In multi-region service, upgrade one region at a time, a
week apart, smallest first ([09](09-saas-and-multi-region-operations.md)).

## 8. Upgrading the Command Center

| Install type | Command |
| --- | --- |
| Setup package | Run `Install.cmd` (or silent with the same parameters) over the existing install: it stops the services, replaces `lib` and `runtime`, runs `ooa db migrate` when it finds the database settings in the default data folder, and starts the services again. With a non-default data folder run `ooa db migrate --data DIR` yourself before starting. |
| MSI | Stop the service (`ooa service stop`), then `msiexec /i ooa-<new>.msi /qn` **without** `ROLE` (a major upgrade keeps the existing services; giving `ROLE` again would try to create the service a second time and fail the install). Then `ooa db migrate --data DIR` and `ooa service start`. |
| Linux package | `systemctl stop ooa-command-center`, replace `/opt/ooa`, `sudo -u ooa ooa db migrate --data /var/lib/ooa` (with the vault key in the environment), `systemctl start ooa-command-center` |
| Container | Pull the new image, stop the old container, run `ooa db migrate` with a setup account if the runtime account is data-only, start the new image on the same volume |

Before upgrading: `ooa check` on the server; afterwards: `ooa check`, `/api/health/ready`, sign in, Runners online, a
test run.

## 9. Runner self-update

The Command Center offers its own program files (`lib`, without the vendor's licence tools) to runners:

- Each runner reports the build id of its program files with every heartbeat. The Runners list shows whether each
  runner matches the Command Center.
- When they differ and either **Update** was clicked for that runner or `runner_auto_update` is 1 (Settings >
  Update runners automatically), an **idle** runner is told to update.
- The runner fetches the file list, reuses files it already has, downloads the rest, checks every SHA-256, and stages
  them in an `update` folder beside the installation. A helper process waits for the runner to exit, swaps the
  folders and, for a runner started at sign-in, starts the "OOA Runner" task again; a runner service is started again
  by Windows.
- If anything fails, the runner stays on its current version and logs why.
- If a run starts while staging, the update waits for the next heartbeat.

Limits:

- Windows installations only; Linux and macOS runners and builds from source are updated by hand.
- The bundled Java runtime is not updated this way.
- A runner installed for all users in Program Files but started at a user's sign-in may not be allowed to write
  there; update it with the MSI or setup package instead.
- Update files are verified by SHA-256 over the TLS connection, not separately signed.

## 10. Updating runners by package

Run the new setup package with the same parameters (it re-uses the existing registration), or deploy the new MSI
through your software distribution tool. The registration in `%USERPROFILE%\.ooa-agent` is kept.

## 11. Version compatibility

| Combination | Result |
| --- | --- |
| Runner same as Command Center | Supported |
| Runner older than Command Center | Works; update it to get new toolkits (runs that need a missing toolkit fail with `RUN.TOOLKIT_MISSING`) |
| Runner newer than Command Center | Gets no work; the Runners page says to update the Command Center |
| Two Command Center versions on one database | Not supported; a node meeting a newer schema stops with `CR.DB_NEWER` |
| Backup from a newer release | Restore refuses it |

## 12. Rolling back

A schema upgrade cannot be undone in place. To roll back:

1. Stop all nodes.
2. Restore the database backup taken before the upgrade (native, or `ooa backup restore --replace` into an empty or
   cleared database).
3. Reinstall the previous version (the MSI blocks downgrades: uninstall first; data is kept).
4. Start, check, and downgrade runners that already updated (reinstall the older package).

## 13. Patching the operating system and Java

- Command Center: patch one node at a time behind the balancer ([06](06-high-availability-and-scaling.md)); a single
  server needs a short window. Services restart automatically after reboot.
- Runners: patch outside run windows; a runner that reboots during a run causes `AGENT.LOST` after two minutes, and
  queue items retry according to their attempts. Screen runners need their user signed in again after a reboot
  (automatic sign-in is not provided by the product).
- Java: the setup package and MSI bring their own Java; a product release carries Java updates. The customer zip,
  Linux package and container use the Java you provide or the image's base; patch it with your normal process (Java
  21).
- Databases: patch per the DBA's plan; the nodes reconnect after a failover or restart.

## 14. For the vendor: making a release

This section is for ONEOFFICEAUTOMATION's own release manager. Customers do not need it.

### What you need, once

| Item | Detail |
| --- | --- |
| A source checkout | The development repository, with the vendor build (`gradlew :cli:installDist`) working |
| The vendor signing key | `%USERPROFILE%\OOA License Keys\private.key`, the same key that signs licences (see [functional/13](../functional/13-licensing-and-editions.md), section 11). It never leaves the vendor's computer. Another place: `-SigningKey FILE`. |
| GitHub access | Git on this computer must already be signed in to GitHub with an account that may publish releases to `OneOfficeautomation-Private-Limited/OneOfficeAutomation-Customer-Build` (push once from this computer; Git Credential Manager keeps the sign-in). The script reads that sign-in; it stores and prints nothing. |
| GitHub Pages | In `OneOfficeAutomation-Customer-Build`: Settings > Pages > Deploy from a branch, branch `main`, folder `/docs` |

### Publishing a release

1. Raise `version` in `platform\build.gradle.kts` (for example from `0.2.0` to `0.3.0`). The product's own version
   comes from this line. A version can be published only once: the script refuses a tag that exists already.
2. Write what changed at the top of `RELEASES.md` in `OneOfficeAutomation-Customer-Build`, then commit and push that repository.
3. From the `platform` folder run:
   ```
   powershell -ExecutionPolicy Bypass -File scripts\publish-customer-release.ps1 -Notes "What changed in one line"
   ```
4. Check the release address the script prints, and the releases page.

| Option | Effect |
| --- | --- |
| `-Notes TEXT` | One line for the release text (default: "See RELEASES.md for what changed.") |
| `-Draft` | Creates the release as a GitHub draft, which customers cannot see; the releases page is not refreshed. Publish the draft on GitHub when it is right. |
| `-Preview` | Publishes `v<version>-preview` as a GitHub pre-release, the sandbox preview (section 6), about three weeks before the final release of the same version |
| `-PageOnly` | Only refreshes the releases page and template catalogue |
| `-SigningKey FILE` | Another place for the vendor key |
| `-Repository OWNER/NAME` | Another release repository (default `OneOfficeautomation-Private-Limited/OneOfficeAutomation-Customer-Build`) |

What the script does, in order:

1. Reads the version from `platform\build.gradle.kts`.
2. Builds the customer zip, the setup package, the SBOM and the vendor build
   (`gradlew :cli:customerDistZip :cli:setupDistZip :cli:sbom :cli:installDist`).
3. Opens both zips and **stops** if either holds the vendor tools (`vendor-tools`), a `private.key` or a `.lic` file.
4. Copies the two zips and the SBOM (as `ooa-<version>-sbom.cdx.json`) into `cli\build\release-<version>`, signs the
   manifest `ooa-<version>.manifest.json` over them with `ooa release sign` and the vendor key, and checks it at once
   with `ooa release verify`.
5. Signs in to GitHub with the credential git already uses.
6. Creates release `v<version>` (or `v<version>-preview`) on the `main` branch, with install instructions and the short
   commit id of the source it was built from in the release text.
7. Uploads `ooa-customer-<version>.zip`, `ooa-setup-<version>.zip`, the manifest and the SBOM.
8. Unless `-Draft`, refreshes the releases page (below).

### The releases page and template catalogue

The script (and `-PageOnly`) runs, in the vendor build:

```
ooa release page --repo OneOfficeautomation-Private-Limited/OneOfficeAutomation-Customer-Build --out DIR [--token-env VAR] [--api URL]
ooa release templates --dir <repository>\templates --key private.key --out DIR\templates.json
```

`release page` reads the repository's GitHub releases and writes `index.html` and `releases.json`. It checks each
release's signed manifest with the key built into the product and shows sizes and checksums only from a manifest that
checks out; drafts are left out. `release templates` signs the template catalogue from the `templates` folder (one
folder per template with `template.json` and its agents); Command Centers read it as the ONEOFFICEAUTOMATION template
catalogue ([functional/08](../functional/08-repository-versions-and-templates.md)). The script then
puts `docs/index.html`, `docs/releases.json` and `docs/templates.json` on the `main` branch of `OneOfficeAutomation-Customer-Build`,
which GitHub Pages serves at `https://oneofficeautomation-private-limited.github.io/OneOfficeAutomation-Customer-Build/`.

To refresh the page alone (for example after publishing a draft by hand):

```
powershell -ExecutionPolicy Bypass -File scripts\publish-customer-release.ps1 -PageOnly
```

### The MSI

The MSI is built from the setup package with WiX Toolset 5 and is not part of the publish script:

```
dotnet tool install --global wix --version 5.0.2        (once)
powershell -ExecutionPolicy Bypass -File scripts\build-msi.ps1 [-Wix PATH\wix.exe]
```

It writes `cli\build\distributions\ooa-<version>.msi`. CI also builds it on every push, installs it as a Command
Center service with a runner service, and uninstalls it (`scripts\test-msi.ps1`).

### After publishing

- Tell customers the releases page address; it lists every release.
- Licence files are never attached to a release: send each customer its own `.lic` file
  ([functional/13](../functional/13-licensing-and-editions.md), section 11).
- For a feature release, publish with `-Preview` first, fix what the sandboxes find, then publish the same version
  without `-Preview` three weeks later (or a new fix version if anything changed).

## Related documents

- [README](README.md)
- [02. Installation on Windows](02-installation-windows.md)
- [05. Databases](05-databases.md)
- [06. High availability and scaling](06-high-availability-and-scaling.md)
- [07. Backup, restore and disaster recovery](07-backup-restore-and-disaster-recovery.md)
- [09. SaaS and multi-region operations](09-saas-and-multi-region-operations.md)
- [Functional 13. Licensing and editions](../functional/13-licensing-and-editions.md)
