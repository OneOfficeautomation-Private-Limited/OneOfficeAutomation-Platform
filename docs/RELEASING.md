# Releasing to customers

Customer releases are published to the public repository
[VirupakshaPrabhu/OOA_Customer_Build](https://github.com/VirupakshaPrabhu/OOA_Customer_Build), with a public
releases page (Q10). Development stays here in `oneofficeautomation`.

| Repository | Holds |
| --- | --- |
| `oneofficeautomation` | All source code, tests, the vendor tools (License Studio) and these documents |
| `OOA_Customer_Build` | Customer-facing `README.md`, `INSTALL.md` and `RELEASES.md`; each release's zips, signed manifest and SBOM under Releases; the releases page in `docs/` |

## Publish a release

1. Raise `version` in `platform\build.gradle.kts`, for example from `0.1.0` to `0.2.0`. A version can be published once.
2. Add the release's changes at the top of `RELEASES.md` in `OOA_Customer_Build`, then commit and push that repository.
3. From `platform`, run:

   ```
   powershell -ExecutionPolicy Bypass -File scripts\publish-customer-release.ps1 -Notes "What changed in one line"
   ```

   The script:
   - builds the customer zip and refuses to publish if the vendor tools, a key or a license file is in it;
   - writes the software bill of materials (`gradlew :cli:sbom`: every third-party library with its version and
     SHA-256, CycloneDX 1.5) and a manifest of the release files signed with the vendor key in
     `%USERPROFILE%\OOA License Keys\private.key` (`-SigningKey` for another place), and checks it;
   - signs in with the GitHub account git already uses;
   - creates release `v<version>` with the zips, `ooa-<version>.manifest.json` and `ooa-<version>-sbom.cdx.json`
     attached, noting the source commit it was built from;
   - refreshes the releases page (below).

   Add `-Draft` to check a release on GitHub before customers can see it, and `-Preview` for a sandbox preview (below).

## Release cadence and the sandbox

- **A feature release each quarter** (`x.y.0`). Fix releases (`x.y.z`) come when needed, and only change what they fix.
- **A sandbox preview three weeks ahead.** Publish the quarter's version with `-Preview` first. That makes
  `v<version>-preview`, a GitHub pre-release. Customers install it on a sandbox Command Center: a separate install with
  its own database and runners, never the production one. They test their agents there: Export them from production
  (Automation > Export / promote, with what they depend on) and import them into the sandbox. Agent tests (5a) give a quick
  pass or fail. Three weeks later, publish the same version without `-Preview`, after fixing what the sandbox found
  (with a new fix version if anything changed).
- **Short downtime on upgrade**, in this order:
  1. back up (`ooa backup now`);
  2. stop the Command Center (on every node);
  3. install the new version over the old one (the setup keeps the data folder);
  4. start it: the database is upgraded on start, or beforehand with `ooa db migrate`;
  5. update the runners from Runners > Update. Runners newer than the Command Center get no work, so update the
     Command Center first.

  Runs queued in the meantime wait and then go on. With several nodes, stop them all before starting the first
  upgraded one.
- **Region by region**: customers in several regions upgrade one region's Command Center at a time, a week apart,
  starting with the smallest.

## Give customers the release

Releases are public: send customers the releases page, https://virupakshaprabhu.github.io/OOA_Customer_Build/. It
lists every release, newest first (sandbox previews marked), with each file's download link, size and SHA-256, and
`releases.json` for scripts. `ooa release page --repo VirupakshaPrabhu/OOA_Customer_Build --out DIR` writes it from
the repository's GitHub releases. It checks each release's signed manifest with the vendor key built into the product
and shows checksums only from a manifest that checks out; drafts are left out, and only https links are made.

The publish script refreshes the page after each release (not for `-Draft`). To refresh it alone:

```
powershell -ExecutionPolicy Bypass -File scripts\publish-customer-release.ps1 -PageOnly
```

It puts `docs/index.html` and `docs/releases.json` on the `main` branch of `OOA_Customer_Build`. Set up once:
Settings > Pages > Deploy from a branch: `main`, folder `/docs`.

License files are never published: each customer gets their own `.lic` directly from you. Without one, a new
install runs the 30-day evaluation.

## How customers check a download

`ooa release verify ooa-<version>.manifest.json` (with any installed release, in the folder holding the downloads, or
`--dir`) checks the manifest's signature against the vendor key built into the product, then each file's size and
SHA-256: PASS, FAIL (changed: do not install) or MISSING. A manifest that was edited is refused as a whole. Customers
without an earlier install compare the SHA-256 values with `certutil -hashfile FILE SHA256`. The SBOM is also inside
each package as `sbom.cdx.json`, for their vulnerability scanners.
